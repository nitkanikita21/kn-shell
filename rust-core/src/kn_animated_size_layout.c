/*
 * GtkLayoutManager: a Row/Column-like container whose main axis animates.
 *
 * A plain size request is a *minimum*, so when content grows the natural size wins and the
 * widget jumps. This layout manager reports the animated size as both minimum and natural
 * in measure(), so the parent allocates exactly that value in both directions; children are
 * laid out along the main axis at their natural size and the container clips (overflow
 * hidden), so content is revealed instead of being reflowed every frame.
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

typedef struct _KnAnimatedSizeLayout KnAnimatedSizeLayout;
typedef struct _KnAnimatedSizeLayoutClass KnAnimatedSizeLayoutClass;

struct _KnAnimatedSizeLayout {
    GtkLayoutManager parent_instance;

    double current; /* animated size along the main axis */
    double from;
    double target;

    guint duration_ms;
    guint easing;
    guint spacing;
    guint axis;
    /* >= 0: animate towards this explicit size; -1: follow the content size. */
    int fixed_size;
    /* >= 0: explicit cross-axis size; -1: follow the children. */
    int fixed_cross;

    gboolean initialized;
    gboolean have_probe;
    double probe;
    gboolean animating;
    gint64 start_us;
};

struct _KnAnimatedSizeLayoutClass {
    GtkLayoutManagerClass parent_class;
};

G_DEFINE_TYPE(KnAnimatedSizeLayout, kn_animated_size_layout, GTK_TYPE_LAYOUT_MANAGER)

static GtkOrientation kn_axis_orientation(const KnAnimatedSizeLayout *self) {
    return self->axis == KN_AXIS_HEIGHT ? GTK_ORIENTATION_VERTICAL : GTK_ORIENTATION_HORIZONTAL;
}

static GtkOrientation kn_cross_orientation(const KnAnimatedSizeLayout *self) {
    return self->axis == KN_AXIS_HEIGHT ? GTK_ORIENTATION_HORIZONTAL : GTK_ORIENTATION_VERTICAL;
}

/* 0 = linear, 1 = out-cubic, 2 = in-out-cubic (same codes as Kotlin Easing). */
static double kn_ease(guint kind, double t) {
    if (t <= 0.0) return 0.0;
    if (t >= 1.0) return 1.0;
    switch (kind) {
        case 1: {
            double u = 1.0 - t;
            return 1.0 - u * u * u;
        }
        case 2:
            return t < 0.5 ? 4.0 * t * t * t : 1.0 - pow(-2.0 * t + 2.0, 3.0) / 2.0;
        default:
            return t;
    }
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

static void kn_animated_size_layout_measure(GtkLayoutManager *manager,
                                            GtkWidget *widget,
                                            GtkOrientation orientation,
                                            int for_size,
                                            int *minimum,
                                            int *natural,
                                            int *minimum_baseline,
                                            int *natural_baseline) {
    KnAnimatedSizeLayout *self = (KnAnimatedSizeLayout *) manager;
    GtkOrientation main_orientation = kn_axis_orientation(self);

    if (orientation == main_orientation) {
        int value = self->current > 0.0 ? (int) lround(self->current) : 0;
        *minimum = value;
        *natural = value;
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

    int position = 0;
    for (GtkWidget *child = gtk_widget_get_first_child(widget);
         child != NULL;
         child = gtk_widget_get_next_sibling(child)) {
        int child_main = kn_measure_child(child, main_orientation, cross_size);
        if (child_main < 1) child_main = 1;

        int child_width = self->axis == KN_AXIS_HEIGHT ? width : child_main;
        int child_height = self->axis == KN_AXIS_HEIGHT ? child_main : height;

        graphene_point_t point = self->axis == KN_AXIS_HEIGHT
                                     ? GRAPHENE_POINT_INIT(0.0f, (float) position)
                                     : GRAPHENE_POINT_INIT((float) position, 0.0f);
        GskTransform *transform = gsk_transform_translate(NULL, &point);
        /* gtk_widget_allocate takes ownership of the transform (transfer full). */
        gtk_widget_allocate(child, child_width, child_height, -1, transform);

        position += child_main + (int) self->spacing;
    }
}

static void kn_animated_size_layout_class_init(KnAnimatedSizeLayoutClass *klass) {
    GtkLayoutManagerClass *manager_class = GTK_LAYOUT_MANAGER_CLASS(klass);
    manager_class->measure = kn_animated_size_layout_measure;
    manager_class->allocate = kn_animated_size_layout_allocate;
}

static void kn_animated_size_layout_init(KnAnimatedSizeLayout *self) {
    self->current = -1.0;
    self->from = -1.0;
    self->target = -1.0;
    self->probe = -1.0;
    self->duration_ms = 180;
    self->easing = 1;
    self->spacing = 0;
    self->axis = KN_AXIS_WIDTH;
    self->fixed_size = -1;
    self->fixed_cross = -1;
    self->initialized = FALSE;
    self->have_probe = FALSE;
    self->animating = FALSE;
    self->start_us = 0;
}

/* Natural main-axis size of the whole row/column (children + spacing). */
static double kn_natural_main_size(KnAnimatedSizeLayout *self, GtkWidget *widget) {
    GtkOrientation main_orientation = kn_axis_orientation(self);
    int total = 0;
    int count = 0;
    for (GtkWidget *child = gtk_widget_get_first_child(widget);
         child != NULL;
         child = gtk_widget_get_next_sibling(child)) {
        total += kn_measure_child(child, main_orientation, -1);
        count++;
    }
    if (count > 1) total += (int) self->spacing * (count - 1);
    return (double) total;
}

static void kn_update(KnAnimatedSizeLayout *self, GtkWidget *widget) {
    if (gtk_widget_get_first_child(widget) == NULL) return;

    double natural = self->fixed_size >= 0
                         ? (double) self->fixed_size
                         : kn_natural_main_size(self, widget);

    if (!self->initialized) {
        if (!gtk_widget_get_mapped(widget)) return;
        if (natural <= 0.0) return;
        if (!self->have_probe || fabs(natural - self->probe) > 0.5) {
            /* The first frames can measure a partially laid out child; wait until the
             * natural size is stable before snapping, otherwise the container starts from
             * a bogus size and visibly jumps once. */
            self->probe = natural;
            self->have_probe = TRUE;
            return;
        }
        self->initialized = TRUE;
        self->current = natural;
        self->target = natural;
        gtk_widget_queue_resize(widget);
        return;
    }

    if (fabs(natural - self->target) > 0.5) {
        self->from = self->current;
        self->target = natural;
        self->start_us = g_get_monotonic_time();
        self->animating = TRUE;
    }

    if (self->animating) {
        double elapsed = (double) (g_get_monotonic_time() - self->start_us) / 1e6;
        double duration = self->duration_ms > 0 ? (double) self->duration_ms / 1000.0 : 0.001;
        double progress = elapsed / duration;
        self->current = self->from + (self->target - self->from) * kn_ease(self->easing, progress);
        if (progress >= 1.0) {
            self->current = self->target;
            self->animating = FALSE;
        }
        gtk_widget_queue_resize(widget);
    }
}

static gboolean kn_tick(GtkWidget *widget, GdkFrameClock *clock, gpointer user_data) {
    (void) clock;
    kn_update((KnAnimatedSizeLayout *) user_data, widget);
    return G_SOURCE_CONTINUE;
}

static void kn_tick_destroy(gpointer user_data) {
    g_object_unref(user_data);
}

/* Sets an explicit cross-axis size (>= 0 px), or -1 for content-driven. */
void kn_animated_size_layout_set_cross(GtkWidget *widget, int fixed) {
    if (!GTK_IS_WIDGET(widget)) return;
    GtkLayoutManager *manager = gtk_widget_get_layout_manager(widget);
    if (manager == NULL) return;
    if (!G_TYPE_CHECK_INSTANCE_TYPE(manager, kn_animated_size_layout_get_type())) return;
    KnAnimatedSizeLayout *self = (KnAnimatedSizeLayout *) manager;
    if (self->fixed_cross == fixed) return;
    self->fixed_cross = fixed;
    gtk_widget_queue_resize(widget);
}

/* Sets an explicit animated main-axis size (>= 0 px), or -1 for content-driven. */
void kn_animated_size_layout_set_fixed(GtkWidget *widget, int fixed) {
    if (!GTK_IS_WIDGET(widget)) return;
    GtkLayoutManager *manager = gtk_widget_get_layout_manager(widget);
    if (manager == NULL) return;
    if (!G_TYPE_CHECK_INSTANCE_TYPE(manager, kn_animated_size_layout_get_type())) return;
    KnAnimatedSizeLayout *self = (KnAnimatedSizeLayout *) manager;
    if (self->fixed_size == fixed) return;
    self->fixed_size = fixed;
    self->have_probe = FALSE;
    gtk_widget_queue_resize(widget);
}

/* Called from Rust over UniFFI; `widget` is a raw GtkWidget* (main thread only). */
void kn_attach_animated_size_layout(GtkWidget *widget,
                                    unsigned int duration_ms,
                                    unsigned int easing,
                                    unsigned int spacing,
                                    unsigned int axis) {
    if (!GTK_IS_WIDGET(widget)) return;

    KnAnimatedSizeLayout *layout = g_object_new(kn_animated_size_layout_get_type(), NULL);
    layout->duration_ms = duration_ms == 0 ? 180 : duration_ms;
    layout->easing = easing;
    layout->spacing = spacing;
    layout->axis = axis == KN_AXIS_HEIGHT ? KN_AXIS_HEIGHT : KN_AXIS_WIDTH;

    gtk_widget_set_layout_manager(widget, GTK_LAYOUT_MANAGER(layout));
    gtk_widget_set_overflow(widget, GTK_OVERFLOW_HIDDEN);

    g_object_ref(layout);
    gtk_widget_add_tick_callback(widget, kn_tick, layout, kn_tick_destroy);
    g_object_unref(layout);
}
