/*
 * GtkLayoutManager: a Row/Column-like container whose main-axis size is owned by Compose.
 *
 * This manager is deliberately "dumb": it never animates. Compose computes the animated
 * value (tween/spring/retarget, see docs/tasks/compose-owned-animated-size.md) and pushes
 * it through `kn_animated_size_layout_set_current()`; the manager only applies it and
 * reports the natural size of the content back through
 * `kn_animated_size_layout_content()`, so the Kotlin animator knows where to go.
 *
 * A plain size request is a *minimum*, so when content grows the natural size wins and the
 * widget jumps. This layout manager reports the applied size as both minimum and natural in
 * measure(), so the parent allocates exactly that value; children are laid out along the
 * main axis at their natural size and the container clips (overflow hidden), so content is
 * revealed instead of being reflowed every frame.
 *
 * Stability invariant (no feedback loop): the content target is measured with
 * `for_size = -1`, i.e. it does not depend on the current animated value. Until Compose
 * pushes the first `set_current()`, measure() is content-driven, so the very first frame
 * has no bogus size and the container does not jump.
 *
 * axis: 0 = horizontal main axis (animates width), 1 = vertical main axis (animates height).
 *
 * Deliberately C: subclassing GtkLayoutManager through gtk4-rs inside the Kotlin/Native
 * process corrupted the GObject type state (every widget became invalid after attaching the
 * layout manager). G_DEFINE_TYPE has neither the double-initialisation problem nor the Rust
 * subclass trait wrappers.
 */

#include <gtk/gtk.h>
#include <math.h>

#define KN_AXIS_WIDTH 0
#define KN_AXIS_HEIGHT 1

/* Below this delta a new `current` is not worth a resize pass (< 1/100 px). */
#define KN_CURRENT_EPSILON 0.01

typedef struct _KnAnimatedSizeLayout KnAnimatedSizeLayout;
typedef struct _KnAnimatedSizeLayoutClass KnAnimatedSizeLayoutClass;

struct _KnAnimatedSizeLayout {
    GtkLayoutManager parent_instance;

    /* Animated main-axis size applied verbatim by measure(), pushed by Compose. */
    double current;
    /* TRUE once Compose pushed the first `current`; before that the manager is
     * content-driven and never guesses a size on its own. */
    gboolean ready;

    guint spacing;
    guint axis;
    /* >= 0: explicit main-axis size for the pre-ready frame; -1: follow the content.
     * Once `ready` is set this value only documents the Kotlin-side target -- measure()
     * uses `current`, so switching content <-> explicit never changes the applied size. */
    int fixed_size;
    /* >= 0: explicit cross-axis size; -1: follow the children. */
    int fixed_cross;

    /* Last probed natural main-axis size (first-frame probe bookkeeping, see
     * kn_probe_content()). Never used to drive `current`. */
    gboolean have_probe;
    double probe;
};

struct _KnAnimatedSizeLayoutClass {
    GtkLayoutManagerClass parent_class;
};

G_DEFINE_TYPE(KnAnimatedSizeLayout, kn_animated_size_layout, GTK_TYPE_LAYOUT_MANAGER)

static GtkOrientation kn_axis_orientation(const KnAnimatedSizeLayout *self) {
    return self->axis == KN_AXIS_HEIGHT ? GTK_ORIENTATION_VERTICAL : GTK_ORIENTATION_HORIZONTAL;
}

static int kn_measure_child(GtkWidget *child, GtkOrientation orientation, int for_size) {
    int minimum = 0;
    int natural = 0;
    int minimum_baseline = -1;
    int natural_baseline = -1;
    gtk_widget_measure(child, orientation, for_size,
                       &minimum, &natural, &minimum_baseline, &natural_baseline);
    return natural;
}

/* Natural main-axis size of the whole row/column (children + spacing between them).
 *
 * Called from measure() and from the Kotlin probe; the latter must happen outside a layout
 * pass (docs/tasks/compose-owned-animated-size.md §7).
 */
/* Spacing to use for child packing.
 *
 * If the target widget is itself a GtkBox we honour the spacing it was created with
 * (gtk_box_new / gtk_box_set_spacing) so the Kotlin modifier is spacing-agnostic: a
 * Row/Column's own spacing is used automatically. For any other widget (no box spacing to
 * read) we fall back to the value supplied when the layout manager was attached. */
static int kn_effective_spacing(const KnAnimatedSizeLayout *self, GtkWidget *widget) {
    if (GTK_IS_BOX(widget)) return gtk_box_get_spacing(GTK_BOX(widget));
    return (int) self->spacing;
}

static double kn_content_main_size(const KnAnimatedSizeLayout *self, GtkWidget *widget) {
    GtkOrientation main_orientation = kn_axis_orientation(self);
    int total = 0;
    int count = 0;
    for (GtkWidget *child = gtk_widget_get_first_child(widget);
         child != NULL;
         child = gtk_widget_get_next_sibling(child)) {
        total += kn_measure_child(child, main_orientation, -1);
        count++;
    }
    if (count > 1) total += kn_effective_spacing(self, widget) * (count - 1);
    return (double) total;
}

/* First-frame probe stabilisation.
 *
 * The first frames can measure children that are only partially laid out, so the natural
 * size can wobble. `current` is never derived from this probe -- Compose decides when the
 * container becomes ready and animates from there (it waits for a stable natural size for
 * two frames, see §4.4). C only remembers the last probed value and reuses it while the
 * measurement stays within half a pixel, so the pre-ready measure() and the Kotlin probe
 * agree even if GTK measures in between layout passes.
 */
static double kn_probe_content(KnAnimatedSizeLayout *self, GtkWidget *widget) {
    double natural = kn_content_main_size(self, widget);
    if (self->have_probe && natural > 0.0 && fabs(natural - self->probe) < 0.5) {
        return self->probe;
    }
    self->probe = natural;
    self->have_probe = TRUE;
    return natural;
}

static void kn_animated_size_layout_measure(GtkLayoutManager *manager,
                                            GtkWidget *widget,
                                            GtkOrientation orientation,
                                            int for_size,
                                            int *minimum,
                                            int *natural,
                                            int *minimum_baseline,
                                            int *natural_baseline) {
    KnAnimatedSizeLayout *self = (KnAnimatedSizeLayout *) manager;
    (void) for_size;

    if (orientation == kn_axis_orientation(self)) {
        double value;
        if (self->ready) {
            value = self->current;
        } else if (self->fixed_size >= 0) {
            /* Pre-ready explicit size: Compose snaps its own initial `current` to this same
             * target (or to the content size), so there is no extra frame in between. */
            value = (double) self->fixed_size;
        } else {
            value = kn_probe_content(self, widget);
        }
        if (value < 0.0) value = 0.0;
        *minimum = (int) lround(value);
        *natural = *minimum;
    } else if (self->fixed_cross >= 0) {
        *minimum = self->fixed_cross;
        *natural = self->fixed_cross;
    } else {
        int cross = 0;
        for (GtkWidget *child = gtk_widget_get_first_child(widget);
             child != NULL;
             child = gtk_widget_get_next_sibling(child)) {
            int child_cross = kn_measure_child(child, orientation, -1);
            if (child_cross > cross) cross = child_cross;
        }
        *minimum = cross;
        *natural = cross;
    }

    *minimum_baseline = -1;
    *natural_baseline = -1;
}

static void kn_animated_size_layout_allocate(GtkLayoutManager *manager,
                                             GtkWidget *widget,
                                             int width,
                                             int height,
                                             int baseline) {
    KnAnimatedSizeLayout *self = (KnAnimatedSizeLayout *) manager;
    (void) baseline;

    GtkOrientation main_orientation = kn_axis_orientation(self);
    int cross_size = self->axis == KN_AXIS_HEIGHT ? width : height;
    int main_size  = self->axis == KN_AXIS_HEIGHT ? height : width;

    /* Collect children and their natural main-axis size. */
    GList *children = NULL;
    for (GtkWidget *c = gtk_widget_get_first_child(widget);
         c != NULL;
         c = gtk_widget_get_next_sibling(c)) {
        children = g_list_append(children, c);
    }
    int n = (int) g_list_length(children);

    int *child_main = g_new0(int, n > 0 ? n : 1);
    int total = 0;
    int n_expand = 0;
    for (int i = 0; i < n; i++) {
        GtkWidget *c = (GtkWidget *) g_list_nth_data(children, i);
        int m = kn_measure_child(c, main_orientation, cross_size);
        if (m < 1) m = 1;
        child_main[i] = m;
        total += m;
        if (gtk_widget_get_hexpand(c)) n_expand++;
    }
    if (n > 1) total += kn_effective_spacing(self, widget) * (n - 1);

    int extra = main_size - total;
    if (extra < 0) extra = 0;

    /* Distribute leftover space to expanding children; otherwise a single child is
     * centred in the container (e.g. a workspace chip that is wider than its label),
     * and multiple children keep GTK's default start packing. */
    int expand_share = n_expand > 0 ? extra / n_expand : 0;
    int expand_rem   = n_expand > 0 ? extra % n_expand : 0;
    int position = (n_expand == 0 && n == 1 && extra > 0) ? extra / 2 : 0;

    int expand_idx = 0;
    for (int i = 0; i < n; i++) {
        GtkWidget *c = (GtkWidget *) g_list_nth_data(children, i);
        int m = child_main[i];
        if (gtk_widget_get_hexpand(c)) {
            m += expand_share + (expand_idx < expand_rem ? 1 : 0);
            expand_idx++;
        }

        int child_width  = self->axis == KN_AXIS_HEIGHT ? width : m;
        int child_height = self->axis == KN_AXIS_HEIGHT ? m : height;

        /* Cross-axis alignment: honour the child's halign/valign so, e.g., a label is
         * vertically centred inside a taller chip. The main axis is packed above. */
        int cross = self->axis == KN_AXIS_HEIGHT ? width : height;
        GtkAlign align = self->axis == KN_AXIS_HEIGHT
                             ? gtk_widget_get_halign(c)
                             : gtk_widget_get_valign(c);
        int cross_pos = 0;
        if (self->axis == KN_AXIS_HEIGHT) {
            if (align == GTK_ALIGN_CENTER)      cross_pos = (cross - child_width)  / 2;
            else if (align == GTK_ALIGN_END)    cross_pos = cross - child_width;
        } else {
            if (align == GTK_ALIGN_CENTER)      cross_pos = (cross - child_height) / 2;
            else if (align == GTK_ALIGN_END)    cross_pos = cross - child_height;
        }

        graphene_point_t point = self->axis == KN_AXIS_HEIGHT
                                     ? GRAPHENE_POINT_INIT((float) cross_pos, (float) position)
                                     : GRAPHENE_POINT_INIT((float) position, (float) cross_pos);
        GskTransform *transform = gsk_transform_translate(NULL, &point);
        /* gtk_widget_allocate takes ownership of the transform (transfer full). */
        gtk_widget_allocate(c, child_width, child_height, -1, transform);

        position += m + kn_effective_spacing(self, widget);
    }

    g_free(child_main);
    g_list_free(children);
}

static void kn_animated_size_layout_class_init(KnAnimatedSizeLayoutClass *klass) {
    GtkLayoutManagerClass *manager_class = GTK_LAYOUT_MANAGER_CLASS(klass);
    manager_class->measure = kn_animated_size_layout_measure;
    manager_class->allocate = kn_animated_size_layout_allocate;
}

static void kn_animated_size_layout_init(KnAnimatedSizeLayout *self) {
    self->current = 0.0;
    self->ready = FALSE;
    self->spacing = 0;
    self->axis = KN_AXIS_WIDTH;
    self->fixed_size = -1;
    self->fixed_cross = -1;
    self->have_probe = FALSE;
    self->probe = 0.0;
}

/* The widget's layout manager if (and only if) it is ours; NULL for anything else.
 *
 * Every entry point goes through this: a stale handle, a widget of another type or a plain
 * GtkBox must never be touched.
 */
static KnAnimatedSizeLayout *kn_layout_get(GtkWidget *widget) {
    if (widget == NULL || !GTK_IS_WIDGET(widget)) return NULL;
    GtkLayoutManager *manager = gtk_widget_get_layout_manager(widget);
    if (manager == NULL) return NULL;
    if (!G_TYPE_CHECK_INSTANCE_TYPE(manager, kn_animated_size_layout_get_type())) return NULL;
    return (KnAnimatedSizeLayout *) manager;
}

/* Applies the Compose-owned animated main-axis size (>= 0 px). Main thread only.
 *
 * This is the only thing that makes the container stop being content-driven: the first call
 * sets `ready` and from then on measure() reports `current` verbatim. Compose is
 * responsible for animating that value (and for snapping it on the first frame).
 */
void kn_animated_size_layout_set_current(GtkWidget *widget, double current) {
    KnAnimatedSizeLayout *self = kn_layout_get(widget);
    if (self == NULL) return;
    if (!isfinite(current) || current < 0.0) return;

    gboolean was_ready = self->ready;
    gboolean changed = !was_ready || fabs(current - self->current) >= KN_CURRENT_EPSILON;

    self->current = current;
    self->ready = TRUE;

    /* Skip the resize pass while the value moves by less than 1/100 px: at 60-144 Hz the
     * integer allocation would be identical anyway. */
    if (changed) gtk_widget_queue_resize(widget);
}

/* Natural main-axis size (children + spacing); 0.0 when the widget is not an
 * animated-size container. Main thread only, outside a layout pass (§7). */
double kn_animated_size_layout_content(GtkWidget *widget) {
    KnAnimatedSizeLayout *self = kn_layout_get(widget);
    if (self == NULL) return 0.0;
    return kn_probe_content(self, widget);
}

/* Whether Compose has already pushed at least one `current` (diagnostics). */
gboolean kn_animated_size_layout_is_ready(GtkWidget *widget) {
    KnAnimatedSizeLayout *self = kn_layout_get(widget);
    if (self == NULL) return FALSE;
    return self->ready;
}

/* Sets an explicit cross-axis size (>= 0 px), or -1 for content-driven. */
void kn_animated_size_layout_set_cross(GtkWidget *widget, int fixed) {
    KnAnimatedSizeLayout *self = kn_layout_get(widget);
    if (self == NULL) return;
    if (self->fixed_cross == fixed) return;
    self->fixed_cross = fixed;
    gtk_widget_queue_resize(widget);
}

/* Records an explicit main-axis size (>= 0 px), or -1 for content-driven.
 *
 * It is only read before the first `set_current()`: afterwards the applied size is
 * `current`, and Compose keeps `current` moving towards whatever target it derives from the
 * content size or from this value. Therefore switching content <-> explicit here changes
 * nothing by itself and can never jump -- Kotlin animates the transition starting from the
 * value it already has (§4.2 "перехід анімує Kotlin з поточного значення").
 */
void kn_animated_size_layout_set_fixed(GtkWidget *widget, int fixed) {
    KnAnimatedSizeLayout *self = kn_layout_get(widget);
    if (self == NULL) return;
    if (self->fixed_size == fixed) return;
    self->fixed_size = fixed;
    /* The next content probe starts from a fresh value. */
    self->have_probe = FALSE;
    gtk_widget_queue_resize(widget);
}

/* Called from Rust over UniFFI; `widget` is a raw GtkWidget* (main thread only).
 *
 * `duration_ms` and `easing` are DEPRECATED and ignored: the animation lives in Compose
 * now (docs/tasks/compose-owned-animated-size.md). They stay in the signature only so the
 * existing Kotlin call site keeps working.
 */
void kn_attach_animated_size_layout(GtkWidget *widget,
                                    unsigned int duration_ms,
                                    unsigned int easing,
                                    unsigned int spacing,
                                    unsigned int axis) {
    (void) duration_ms; /* DEPRECATED: ignored, Compose owns the timing. */
    (void) easing;      /* DEPRECATED: ignored, Compose owns the easing/spec. */

    if (widget == NULL || !GTK_IS_WIDGET(widget)) return;

    KnAnimatedSizeLayout *layout = g_object_new(kn_animated_size_layout_get_type(), NULL);
    layout->spacing = spacing;
    layout->axis = axis == KN_AXIS_HEIGHT ? KN_AXIS_HEIGHT : KN_AXIS_WIDTH;

    gtk_widget_set_layout_manager(widget, GTK_LAYOUT_MANAGER(layout));
    gtk_widget_set_overflow(widget, GTK_OVERFLOW_HIDDEN);
}
