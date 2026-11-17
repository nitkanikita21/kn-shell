/*
 * KnBoxLayout — the single box layout manager of the framework (Row / Column / animated size).
 *
 * GTK 4 negotiates size bottom-up: every widget reports `(minimum, natural)` from `measure()`
 * and the parent decides how to spend the difference in `allocate()`. Android/Compose DSLs are
 * the opposite (the parent pushes constraints down), so the framework needs one place that
 * translates between the two models. That place is this manager. It replaces the ad-hoc hacks
 * that used to be spread over `GtkBox` + `reconcileMainAxisAlign()` + a second, animated-only
 * packing implementation (plan L1–L5 in docs/layout-model.md).
 *
 * What it implements:
 *   - `spacing` / `orientation` / `homogeneous`, plus a per-child `weight` (Android's
 *     `Modifier.weight`), which GTK has no API for at all (`GtkBoxLayout` is final,
 *     `GtkCustomLayout` has no per-child properties, GNOME's official position is that
 *     "percentage of parent" is incompatible with GTK's flow — discourse.gnome.org/t/8093).
 *   - main-axis `align` by giving the aligned child a *cell* and allocating it its natural size
 *     inside that cell. This is exactly what stock GtkBox does (measured in
 *       `framework/src/nativeTest/kotlin/dev/knshell/compose/LayoutProbeTest.kt`: a
 *     `halign=CENTER` + `hexpand` label gets `x=143 w=6` in a 600px box), and it is what makes
 *     the old `reconcileMainAxisAlign()` expand-flag hack unnecessary (plan L1).
 *   - the Compose-owned animated main-axis size: Compose computes the value and pushes it
 *     through `kn_animated_size_layout_set_current()`, this manager only applies it and reports
 *     the natural content size back through `kn_box_content()` / the legacy entry point, so the
 *     Kotlin animator knows where to go. See docs/tasks/compose-owned-animated-size.md.
 *
 * Two invariants that must not be broken:
 *   1. NO FEEDBACK LOOP. The content probe used by Compose measures children with
 *      `for_size = -1`, so the animation target never depends on the current animated value.
 *   2. CLIP, DON'T REFLOW. While animated, `measure()` reports the applied value as BOTH
 *      minimum and natural, so the parent allocates exactly that value and `overflow: hidden`
 *      reveals the content instead of re-laying-it-out every frame.
 *
 * Deliberately C: subclassing `GtkLayoutManager` through gtk4-rs in this process corrupts the
 * widget it is attached to (after `gtk_widget_set_layout_manager()` the box's GObject
 * `ref_count` reads 0, `GTK_IS_WIDGET()` returns false and the next access aborts with
 * "GLib-GObject-CRITICAL: invalid unclassed pointer in cast to 'GObject'"). The reproduction is
 * described in docs/bar-api.md. `G_DEFINE_TYPE` has neither the double-initialisation problem
 * nor the Rust subclass wrappers.
 */

#include <gtk/gtk.h>
#include <math.h>

#define KN_AXIS_WIDTH 0
#define KN_AXIS_HEIGHT 1

/* Below this delta a new animated `current` is not worth a resize pass (< 1/100 px). */
#define KN_CURRENT_EPSILON 0.01

/* `weight` value meaning "this child has no explicit weight" (i.e. behave like plain GTK). */
#define KN_NO_WEIGHT 0.0f

/* Max-size value meaning "this child asks for no ceiling on this axis". A cap of 0 px is legal
 * (`Modifier.maxWidth(0)`), so "absent" cannot be 0 — same reason `weight` uses `<= 0`. */
#define KN_NO_MAX (-1)

typedef struct _KnBoxLayout KnBoxLayout;
typedef struct _KnBoxLayoutClass KnBoxLayoutClass;

struct _KnBoxLayout {
    GtkLayoutManager parent_instance;

    guint orientation;   /* KN_AXIS_WIDTH | KN_AXIS_HEIGHT */
    guint spacing;
    gboolean homogeneous;

    /* Per-child `weight` and per-child max-size caps. Sparse on purpose: a box with no weights
     * and no caps pays nothing and behaves exactly like the plain manager it replaced. */
    GHashTable *child_layouts;   /* GtkWidget* -> KnChildLayout* */

    /* --- animated main-axis size (Compose-owned, see the header) --- */
    double current;
    gboolean ready;
    /* >= 0: explicit main-axis size for the pre-ready frame; -1: follow the content. */
    int fixed_size;
    /* >= 0: explicit cross-axis size; -1: follow the children. */
    int fixed_cross;
    /* Last probed natural main-axis size (first-frame stabilisation only). */
    gboolean have_probe;
    double probe;
};

struct _KnBoxLayoutClass {
    GtkLayoutManagerClass parent_class;
};

typedef struct {
    float weight;
    /* Upper bounds in px, stored in the CHILD's own axes (`maxWidth`/`maxHeight` of the DSL), not
     * in the container's main/cross — re-orienting the container must not change what
     * `Modifier.maxWidth(220)` means. -1 = uncapped. GTK has no ceiling for a widget's size, so
     * this clamp is the emulation the plan asks for (docs/layout-model.md, plan L6). */
    int max_width;
    int max_height;
} KnChildLayout;

G_DEFINE_TYPE(KnBoxLayout, kn_box_layout, GTK_TYPE_LAYOUT_MANAGER)

/* ------------------------------------------------------------------ child layout data */

static KnChildLayout *kn_child_layout_peek(KnBoxLayout *self, GtkWidget *child) {
    if (self->child_layouts == NULL) return NULL;
    return g_hash_table_lookup(self->child_layouts, child);
}

static KnChildLayout *kn_child_layout_get(KnBoxLayout *self, GtkWidget *child) {
    if (self->child_layouts == NULL) {
        self->child_layouts = g_hash_table_new_full(g_direct_hash, g_direct_equal,
                                                   NULL, g_free);
    }
    KnChildLayout *data = g_hash_table_lookup(self->child_layouts, child);
    if (data == NULL) {
        data = g_new0(KnChildLayout, 1);
        data->weight = KN_NO_WEIGHT;
        data->max_width = KN_NO_MAX;
        data->max_height = KN_NO_MAX;
        g_hash_table_insert(self->child_layouts, child, data);
    }
    return data;
}

static void kn_forget_child(KnBoxLayout *self, GtkWidget *child) {
    if (self->child_layouts != NULL) g_hash_table_remove(self->child_layouts, child);
}

static float kn_child_weight(KnBoxLayout *self, GtkWidget *child) {
    KnChildLayout *data = kn_child_layout_peek(self, child);
    return data == NULL ? KN_NO_WEIGHT : data->weight;
}

/* The ceiling `child` asks for along `orientation`, or KN_NO_MAX. A child with no record at all
 * answers KN_NO_MAX, which is what keeps an unweighted, uncapped box allocation-free. */
static int kn_child_max(KnBoxLayout *self, GtkWidget *child, GtkOrientation orientation) {
    KnChildLayout *data = kn_child_layout_peek(self, child);
    if (data == NULL) return KN_NO_MAX;
    return orientation == GTK_ORIENTATION_HORIZONTAL ? data->max_width : data->max_height;
}

/* ----------------------------------------------------------------------- measurements */

static GtkOrientation kn_main_orientation(const KnBoxLayout *self) {
    return self->orientation == KN_AXIS_HEIGHT ? GTK_ORIENTATION_VERTICAL
                                              : GTK_ORIENTATION_HORIZONTAL;
}

static void kn_measure_child(KnBoxLayout *self, GtkWidget *child, GtkOrientation orientation,
                            int for_size, int *minimum, int *natural) {
    int mn = 0, nat = 0, minimum_baseline = -1, natural_baseline = -1;
    gtk_widget_measure(child, orientation, for_size, &mn, &nat,
                      &minimum_baseline, &natural_baseline);
    /* The `Modifier.maxWidth()`/`maxHeight()` ceiling of this child (plan L6). GTK has no
     * ceiling, so the number the child answers with is capped here — in the child's OWN axis,
     * which is what lets a capped island stop the whole bar from asking for pixels nobody may
     * use.
     *
     * The ceiling stops at the child's minimum, and that is not laziness: a GtkLabel that
     * neither wraps nor ellipsizes has minimum width == natural width, and GTK's contract is
     * that it is never allocated less. Ask it anyway and it does not clip — it paints its full
     * line over the neighbours (seen in the probe window: two labels drawn on each other), and
     * reporting the smaller minimum makes the height-for-width negotiation ask it for a height
     * at a width it cannot honour:
     *   Gtk-WARNING: Trying to measure GtkLabel 0x… for width of 203, but it needs at least 249
     * So `max` means "never grow past here", and content that refuses to shrink keeps its size —
     * `Label(wrap = true)`/`ellipsize = true`/`maxWidthChars` is what lowers a label's minimum
     * and makes a tight cap real. Same rule as Compose, where a constraint is coerced to
     * `min <= max` instead of being broken. */
    int cap = kn_child_max(self, child, orientation);
    if (cap >= 0 && nat > cap) {
        nat = cap;
        if (nat < mn) nat = mn;
    }
    if (minimum != NULL) *minimum = mn;
    if (natural != NULL) *natural = nat;
}

/* Alignment along the container's OWN axis (`halign` in a Row, `valign` in a Column) — the one
 * this manager turns into a position inside the child's cell. Reading the other axis here would
 * silently invert the meaning of `Modifier.align(h = …)` / `align(v = …)`. */
static GtkAlign kn_main_align(gboolean vertical, GtkWidget *child) {
    return vertical ? gtk_widget_get_valign(child) : gtk_widget_get_halign(child);
}

static int kn_effective_spacing(const KnBoxLayout *self, GtkWidget *widget) {
    (void) widget;
    return (int) self->spacing;
}

/* Main-axis (minimum, natural) of the content. `for_size` is the cross-axis size the caller is
 * negotiating with (`-1` = unconstrained), and it is passed straight to the children: that is
 * the height-for-width contract the stock manager dropped (plan L3).
 *
 * A weight distributes *surplus*, so it cannot be resolved here — the sum of the children is
 * what the container reports: their minimums as the minimum, their naturals as the natural. */
static void kn_content_main_size(KnBoxLayout *self, GtkWidget *widget, int for_size,
                                int *minimum, int *natural) {
    GtkOrientation main = kn_main_orientation(self);
    int total_min = 0, total_nat = 0, count = 0;
    int max_min = 0, max_nat = 0;

    for (GtkWidget *child = gtk_widget_get_first_child(widget);
         child != NULL;
         child = gtk_widget_get_next_sibling(child)) {
        int mn = 0, nat = 0;
        kn_measure_child(self, child, main, for_size, &mn, &nat);
        if (mn > max_min) max_min = mn;
        if (nat > max_nat) max_nat = nat;
        total_min += mn;
        total_nat += nat;
        count++;
    }
    if (self->homogeneous && count > 0) {
        total_min = max_min * count;
        total_nat = max_nat * count;
    }
    if (count > 1) {
        int spacing = kn_effective_spacing(self, widget) * (count - 1);
        total_min += spacing;
        total_nat += spacing;
    }
    if (minimum != NULL) *minimum = total_min;
    if (natural != NULL) *natural = total_nat;
}

/* First-frame probe stabilisation: the first frames can measure children that are only
 * partially laid out, so the natural size can wobble. `current` is never derived from this
 * probe — Compose decides when the container becomes ready (it waits for a stable natural size
 * for two frames, see docs/tasks/compose-owned-animated-size.md §4.4). */
static double kn_probe_content(KnBoxLayout *self, GtkWidget *widget) {
    int natural = 0;
    /* The animation target is always the UNCONSTRAINED content size: it must not depend on
     * the currently animated value, otherwise the layout feeds back into the tween. */
    kn_content_main_size(self, widget, -1, NULL, &natural);
    double value = (double) natural;
    if (self->have_probe && value > 0.0 && fabs(value - self->probe) < 0.5) {
        return self->probe;
    }
    self->probe = value;
    self->have_probe = TRUE;
    return value;
}

/* ------------------------------------------------------------------------ negotiation */

static GtkSizeRequestMode kn_box_layout_get_request_mode(GtkLayoutManager *manager,
                                                        GtkWidget *widget) {
    (void) manager;
    (void) widget;
    /* Declared explicitly (GTK would default to it): the negotiation may start with the cross
     * axis, so measure() has to forward `for_size` to the children instead of dropping it.
     * Without that a wrapping Label never learns how wide it actually is (plan L3). */
    return GTK_SIZE_REQUEST_HEIGHT_FOR_WIDTH;
}

static void kn_box_layout_measure(GtkLayoutManager *manager,
                                 GtkWidget *widget,
                                 GtkOrientation orientation,
                                 int for_size,
                                 int *minimum,
                                 int *natural,
                                 int *minimum_baseline,
                                 int *natural_baseline) {
    KnBoxLayout *self = (KnBoxLayout *) manager;

    if (orientation == kn_main_orientation(self)) {
        int content_min = 0, content_nat = 0;
        if (for_size >= 0) {
            /* Height-for-width on the main axis: the caller is negotiating the size of THIS
             * axis with the other one fixed, so the children must be measured with that
             * constraint (`for_size = -1` would report the unconstrained content again). */
            kn_content_main_size(self, widget, for_size, &content_min, &content_nat);
        } else {
            kn_content_main_size(self, widget, -1, &content_min, &content_nat);
        }

        if (self->ready) {
            /* Animated phase: the applied value is reported as BOTH minimum and natural, which
             * is what makes the parent allocate exactly `current` and the container clip
             * instead of being reflowed — see the header comment. */
            double value = self->current < 0.0 ? 0.0 : self->current;
            *minimum = (int) lround(value);
            *natural = *minimum;
        } else if (self->fixed_size >= 0) {
            /* Pre-ready explicit size: Compose snaps its own initial `current` to this same
             * target, so there is no extra frame in between. */
            *minimum = self->fixed_size;
            *natural = self->fixed_size;
        } else {
            /* Content-driven phase: an honest (minimum, natural) pair, so a parent may shrink
             * this container down to its content's minimum (plan L3). */
            *minimum = content_min;
            *natural = content_nat;
        }
        if (*natural < *minimum) *natural = *minimum;
    } else if (self->fixed_cross >= 0) {
        *minimum = self->fixed_cross;
        *natural = self->fixed_cross;
    } else {
        int cross_min = 0, cross_nat = 0;
        for (GtkWidget *child = gtk_widget_get_first_child(widget);
             child != NULL;
             child = gtk_widget_get_next_sibling(child)) {
            int mn = 0, nat = 0;
            /* `for_size` is the main-axis size the caller is negotiating with: passing it
             * through is the whole height-for-width contract (plan L3). */
            kn_measure_child(self, child, orientation, for_size, &mn, &nat);
            if (mn > cross_min) cross_min = mn;
            if (nat > cross_nat) cross_nat = nat;
        }
        *minimum = cross_min;
        *natural = cross_nat;
        if (*natural < *minimum) *natural = *minimum;
    }

    *minimum_baseline = -1;
    *natural_baseline = -1;
}

static void kn_box_layout_allocate(GtkLayoutManager *manager,
                                  GtkWidget *widget,
                                  int width,
                                  int height,
                                  int baseline) {
    KnBoxLayout *self = (KnBoxLayout *) manager;
    (void) baseline;

    GtkOrientation main = kn_main_orientation(self);
    gboolean vertical = self->orientation == KN_AXIS_HEIGHT;
    int cross_size = vertical ? width : height;
    int main_size = vertical ? height : width;

    GList *children = NULL;
    for (GtkWidget *c = gtk_widget_get_first_child(widget);
         c != NULL;
         c = gtk_widget_get_next_sibling(c)) {
        children = g_list_append(children, c);
    }
    int n = (int) g_list_length(children);
    if (n == 0) return;

    int *child_min = g_new0(int, n);
    int *child_nat = g_new0(int, n);
    float *child_weight = g_new0(float, n);
    gboolean *child_expand = g_new0(gboolean, n);
    gboolean *child_fill = g_new0(gboolean, n);
    int *child_size = g_new0(int, n);
    /* The ceiling `Modifier.maxWidth()`/`maxHeight()` asked for, in the CHILD's own axes:
     * `child_cap` is the cap along the direction this box lays children out, `child_cross_cap`
     * the one across it. `kn_measure_child` clamps what a child reports about itself, but a size
     * the MANAGER hands out — a `weight` share, a homogeneous cell, a share of the surplus — the
     * child never measured, so those passes have to be clamped by hand. */
    int *child_cap = g_new0(int, n);
    int *child_cross_cap = g_new0(int, n);

    int n_expand = 0;
    int n_weighted = 0;
    float total_weight = 0.0f;
    int max_min = 0, max_nat = 0;
    int spacing = n > 1 ? kn_effective_spacing(self, widget) * (n - 1) : 0;

    for (int i = 0; i < n; i++) {
        GtkWidget *c = (GtkWidget *) g_list_nth_data(children, i);
        int mn = 0, nat = 0;
        /* The cross size of THIS pass is the child's `for_size` (height-for-width). */
        kn_measure_child(self, c, main, cross_size, &mn, &nat);
        if (mn > max_min) max_min = mn;
        if (nat > max_nat) max_nat = nat;
        child_min[i] = mn;
        child_nat[i] = nat;
        child_cap[i] = kn_child_max(self, c, main);
        child_cross_cap[i] = kn_child_max(self, c, vertical ? GTK_ORIENTATION_HORIZONTAL
                                                            : GTK_ORIENTATION_VERTICAL);
        child_weight[i] = kn_child_weight(self, c);
        /* Main-axis expansion is decided by the expand flag OF THE MAIN AXIS: counting hexpand
         * for a vertical container handed leftover height to `fillWidth()` children (plan L2).
         * `gtk_widget_compute_expand()` also follows a child's own expand properties, which a
         * bare getter does not. */
        child_expand[i] = child_weight[i] > KN_NO_WEIGHT
            ? FALSE /* the weight already owns this child's share of the leftover */
            : gtk_widget_compute_expand(c, main) != FALSE;
        if (child_expand[i]) n_expand++;
        if (child_weight[i] > KN_NO_WEIGHT) n_weighted++;
        /* Only a FILL child is allocated its whole cell; a CENTER/END child is allocated its
         * natural size inside a larger cell (stock GtkBox behaviour, see the header). */
        GtkAlign align = kn_main_align(vertical, c);
        child_fill[i] = (align == GTK_ALIGN_FILL);
        total_weight += child_weight[i];
    }
    int homogeneous_cell = 0;
    if (self->homogeneous) {
        /* GtkBox semantics: EVERY cell is `extra_space / n_children` of the container, not
         * "as large as the largest child" — so a homogeneous Row fills its width with equal
         * slots and a CENTER/END child floats inside its slot (see the `main-axis align`
         * test). Weights are still honoured on top, and they use the same equal cell. */
        homogeneous_cell = (main_size - spacing) / n;
        int hom_extra = (main_size - spacing) % n;
        for (int i = 0; i < n; i++) {
            child_min[i] = max_min;
            child_nat[i] = homogeneous_cell + (i < hom_extra ? 1 : 0);
            /* No cap arithmetic here: the equal cell is clamped once, at allocation, next to
             * every other size the container computed for the child. */
        }
    }

    /* --- weighted children claim the leftover before anything else. The model is "weight = a
     * proportional expand": unweighted children take their natural size first and the WHOLE
     * remainder is shared out by weight — a weighted child is not given its own natural on top
     * of its share, which is what makes `Modifier.weight(1f)` on a Label fill the row the way
     * `layout_width=0dp` does in Android. Every share is floored at that child's minimum, and
     * the deficit pass below still has the last word when the floors overflow the container. */
    for (int i = 0; i < n; i++) {
        if (child_weight[i] <= KN_NO_WEIGHT) child_size[i] = child_nat[i];
    }
    if (n_weighted > 0) {
        int fixed_nat = spacing;
        for (int i = 0; i < n; i++) {
            if (child_weight[i] <= KN_NO_WEIGHT) fixed_nat += child_size[i];
        }
        int leftover = main_size - fixed_nat;
        if (leftover < 0) leftover = 0;
        for (int i = 0; i < n; i++) {
            if (child_weight[i] <= KN_NO_WEIGHT) continue;
            double share = (double) leftover * (double) child_weight[i] / (double) total_weight;
            int wanted = (int) floor(share + 0.5);
            /* A weight allocates SPACE, not content: a share can never be wider than the
             * container itself. Without this a 9/10 weight inside a box whose children cannot
             * wrap at the offered height asked for 539px inside a 600px box and the deficit
             * pass had nothing to take it from (room = size - minimum = 0 when the minimum is
             * the one-line width). Stock GtkBox clamps the same overflow when it allocates. */
            if (wanted > main_size) wanted = main_size;
            /* A share a cap limits stays limited: 80px asked for on a `weight(1f)` child in a
             * 600px row is 80px, not 300. Clamped BEFORE the minimum floor on the next line, so
             * a cap the child cannot honour still loses to its minimum — the one rule
             * `kn_measure_child` states for every axis. The space a cap frees is NOT handed to
             * the other weighted children: they keep the share their weights say, and the row
             * simply does not fill. */
            if (child_cap[i] >= 0 && wanted > child_cap[i]) wanted = child_cap[i];
            if (wanted < child_min[i]) wanted = child_min[i];
            child_size[i] = wanted;
        }
    }

    /* --- surplus goes to the expanding children only; a deficit is taken first from the
     * children that did not ask to expand (they are the decoration around the growing
     * content), then from the expanding ones, never below each child's minimum — that is what
     * lets a shrink animation compress a Label instead of clipping it (plan L3/L4). A
     * zero-natural child keeps its zero cell: the old `if (m < 1) m = 1;` turned every
     * `Box(Modifier.size(0, 2))` divider into a 1px artifact (plan L4). */
    int sum_children = 0;
    for (int i = 0; i < n; i++) sum_children += child_size[i];
    int extra = main_size - spacing - sum_children;
    int deficit = extra < 0 ? -extra : 0;
    if (extra < 0) extra = 0;

    if (deficit > 0) {
        /* Two rounds: first only the children that did not ask to expand (they are the
         * decoration around the growing content), then — if that was not enough — the
         * expanders too. Each round shrinks every eligible child in proportion to how much
         * room it has above its own minimum, never below it. The old code divided the deficit
         * by the *pixel* total (`share = deficit / shrinkable`), which is not a unit, and then
         * tried to hand out the remainder in two passes over the children — so at most 2*n
         * pixels could ever be taken and a box simply overflowed. */
        for (int round = 0; round < 2 && deficit > 0; round++) {
            int room_total = 0;
            for (int i = 0; i < n; i++) {
                if (round == 0 && child_expand[i]) continue;
                int room = child_size[i] - child_min[i];
                if (room > 0) room_total += room;
            }
            if (room_total == 0) continue;

            int budget = deficit > room_total ? room_total : deficit;
            int given = 0;
            for (int i = 0; i < n; i++) {
                if (round == 0 && child_expand[i]) continue;
                int room = child_size[i] - child_min[i];
                if (room <= 0) continue;
                int take = (int)((double)budget * (double)room / (double)room_total);
                if (take > room) take = room;
                child_size[i] -= take;
                given += take;
            }
            deficit -= given;

            /* Pixels lost to truncation go out one at a time, to whoever still has room. */
            while (deficit > 0) {
                int progressed = 0;
                for (int i = 0; i < n && deficit > 0; i++) {
                    if (round == 0 && child_expand[i]) continue;
                    if (child_size[i] > child_min[i]) {
                        child_size[i]--;
                        deficit--;
                        progressed = 1;
                    }
                }
                if (!progressed) break;
            }
        }
        for (int i = 0; i < n; i++) {
            if (child_size[i] < child_min[i]) child_size[i] = child_min[i];
        }
    }

    /* Surplus goes to the expanders only (measured: stock GtkBox does the same in the
     * non-homogeneous case) — a plain child keeps its natural size, which is what makes
     * `Row { Label(); Label() }` pack instead of stretch. */
    int expand_share = n_expand > 0 ? extra / n_expand : 0;
    int expand_rem = n_expand > 0 ? extra % n_expand : 0;
    int expand_idx = 0;
    for (int i = 0; i < n; i++) {
        if (child_expand[i]) {
            child_size[i] += expand_share + (expand_idx < expand_rem ? 1 : 0);
            expand_idx++;
        }
    }

    /* Packing starts at the container's edge, exactly like GtkBox. Centring belongs to
     * `align()` and the cell this manager gives an aligned child (plan L5) — it is not a
     * property of "this container happens to animate". */
    int position = 0;
    for (int i = 0; i < n; i++) {
        GtkWidget *c = (GtkWidget *) g_list_nth_data(children, i);
        int m = child_size[i];
        /* Last word for the ceiling in the main direction. A share, a homogeneous cell and a
         * surplus are all arithmetic the child never measured, so they can each hand out more
         * than `maxWidth()` asked for. Never below the child's own minimum, for the reason in
         * `kn_measure_child`: GTK widgets do not clip, they overlap. */
        if (child_cap[i] >= 0) {
            int limit = child_cap[i] > child_min[i] ? child_cap[i] : child_min[i];
            if (m > limit) m = limit;
        }
        /* A FILL child gets the whole cell; a CENTER/END child is allocated its constrained
         * natural size and positioned inside the cell — and may well be WIDER than the cell,
         * which is exactly what stock GtkBox does. The old `child_main > m` clamp plus the
         * `m > main_size` clamp turned every aligned child into a squeezed widget, so the
         * alignment was only ever observable as a gap. */
        int child_main = child_fill[i] ? m : child_nat[i];
        if (child_cap[i] >= 0 && child_main > child_cap[i]) {
            int limit = child_cap[i] > child_min[i] ? child_cap[i] : child_min[i];
            if (child_main > limit) child_main = limit;
        }
        int main_pos = position;
        if (!child_fill[i]) {
            GtkAlign align = kn_main_align(vertical, c);
            if (align == GTK_ALIGN_CENTER) main_pos = position + (m - child_main) / 2;
            else if (align == GTK_ALIGN_END) main_pos = position + (m - child_main);
            if (main_pos < 0) main_pos = 0;
        }

        /* Cross axis: GtkBox allocates every child the full cross size (`child_allocation
         * .height = height`), and the widget's own align then decides what it does with it —
         * a CENTER-aligned Label floats in the middle of a taller Row, a Button fills it.
         * Re-measuring the cross size here would fight that and break the baseline path — with
         * one exception: `cross_own` is what the child is actually allocated, so the cross-axis
         * ceiling (`Modifier.height(16).maxHeight(16)` on an icon in a 40px row) is applied to
         * it below, and the align places the shortened box in the row. */
        GtkAlign cross_align = vertical ? gtk_widget_get_halign(c) : gtk_widget_get_valign(c);
        int cross_own = cross_size;
        if (cross_align == GTK_ALIGN_CENTER || cross_align == GTK_ALIGN_END) {
            /* Not FILL along the cross axis: ask the child how tall (wide) it wants to be at
             * the main size it just got, and place that box inside the cross size. */
            GtkOrientation cross = vertical ? GTK_ORIENTATION_HORIZONTAL : GTK_ORIENTATION_VERTICAL;
            int cross_min = 0, cross_nat = 0;
            kn_measure_child(self, c, cross, child_main, &cross_min, &cross_nat);
            cross_own = cross_nat > cross_size ? cross_size : cross_nat;
        }
        if (child_cross_cap[i] >= 0 && cross_own > child_cross_cap[i]) {
            /* The same floor as the main axis, and it needs the child's own cross minimum:
             * an icon that asked for 30px of height is not made 20px tall by `maxHeight(20)`.
             * Clamped to `cross_size` afterwards, because GtkBox already allocates less than a
             * child needs when the ROW itself is shorter than that — a cap must not make the
             * child taller than the container instead. */
            int own_min = 0, own_nat = 0;
            kn_measure_child(self, c, vertical ? GTK_ORIENTATION_HORIZONTAL
                                               : GTK_ORIENTATION_VERTICAL,
                            child_main, &own_min, &own_nat);
            int limit = child_cross_cap[i] > own_min ? child_cross_cap[i] : own_min;
            if (limit > cross_size) limit = cross_size;
            cross_own = limit;
        }
        int cross_pos = 0;
        if (cross_align == GTK_ALIGN_CENTER) cross_pos = (cross_size - cross_own) / 2;
        else if (cross_align == GTK_ALIGN_END) cross_pos = cross_size - cross_own;
        if (cross_pos < 0) cross_pos = 0;

        int child_width = vertical ? cross_own : child_main;
        int child_height = vertical ? child_main : cross_own;

        graphene_point_t point = vertical
            ? GRAPHENE_POINT_INIT((float) cross_pos, (float) main_pos)
            : GRAPHENE_POINT_INIT((float) main_pos, (float) cross_pos);
        GskTransform *transform = gsk_transform_translate(NULL, &point);
        /* gtk_widget_allocate takes ownership of the transform (transfer full). */
        gtk_widget_allocate(c, child_width, child_height, -1, transform);

        position += m + kn_effective_spacing(self, widget);
    }

    g_free(child_min);
    g_free(child_nat);
    g_free(child_weight);
    g_free(child_expand);
    g_free(child_fill);
    g_free(child_size);
    g_free(child_cap);
    g_free(child_cross_cap);
    g_list_free(children);
}

static void kn_box_layout_finalize(GObject *object) {
    KnBoxLayout *self = (KnBoxLayout *) object;
    if (self->child_layouts != NULL) {
        g_hash_table_destroy(self->child_layouts);
        self->child_layouts = NULL;
    }
    G_OBJECT_CLASS(kn_box_layout_parent_class)->finalize(object);
}

static void kn_box_layout_init(KnBoxLayout *self) {
    self->orientation = KN_AXIS_WIDTH;
    self->spacing = 0;
    self->homogeneous = FALSE;
    self->child_layouts = NULL;
    self->current = 0.0;
    self->ready = FALSE;
    self->fixed_size = -1;
    self->fixed_cross = -1;
    self->have_probe = FALSE;
    self->probe = 0.0;
}

static void kn_box_layout_class_init(KnBoxLayoutClass *klass) {
    GtkLayoutManagerClass *manager_class = GTK_LAYOUT_MANAGER_CLASS(klass);
    manager_class->get_request_mode = kn_box_layout_get_request_mode;
    manager_class->measure = kn_box_layout_measure;
    manager_class->allocate = kn_box_layout_allocate;
    G_OBJECT_CLASS(klass)->finalize = kn_box_layout_finalize;
}

/* ------------------------------------------------------------------------- entry points */

/* The widget's box manager if (and only if) it is ours; NULL for anything else.
 *
 * Every entry point goes through this: a stale handle, a widget of another type or a plain
 * GtkBox must never be touched. */
static KnBoxLayout *kn_layout_get(GtkWidget *widget) {
    if (widget == NULL || !GTK_IS_WIDGET(widget)) return NULL;
    GtkLayoutManager *manager = gtk_widget_get_layout_manager(widget);
    if (manager == NULL) return NULL;
    if (!G_TYPE_CHECK_INSTANCE_TYPE(manager, kn_box_layout_get_type())) return NULL;
    return (KnBoxLayout *) manager;
}

static void kn_queue_resize(GtkWidget *widget) {
    gtk_widget_queue_resize(widget);
}

void kn_box_attach(GtkWidget *widget, int orientation, int spacing, int homogeneous) {
    if (widget == NULL || !GTK_IS_WIDGET(widget)) return;
    if (kn_layout_get(widget) != NULL) return;   /* already ours */

    KnBoxLayout *layout = g_object_new(kn_box_layout_get_type(), NULL);
    layout->orientation = orientation == KN_AXIS_HEIGHT ? KN_AXIS_HEIGHT : KN_AXIS_WIDTH;
    layout->spacing = spacing > 0 ? (guint) spacing : 0u;
    layout->homogeneous = homogeneous != 0;
    /* `gtk_widget_set_layout_manager()` is `(transfer full)` and `gtk_layout_manager_set_widget()`
     * keeps only a back-pointer, so the widget owns the only strong reference from this line on —
     * unreffing here would leave a dangling manager, which GTK then rejects with
     * "assertion 'GTK_IS_LAYOUT_MANAGER (manager)' failed" and the widget allocates nothing at all.
     * Verified: see the same trap in the animated manager this file replaced. */
    gtk_widget_set_layout_manager(widget, GTK_LAYOUT_MANAGER(layout));
}

void kn_box_set_orientation(GtkWidget *widget, int orientation) {
    KnBoxLayout *self = kn_layout_get(widget);
    if (self == NULL) return;
    guint want = orientation == KN_AXIS_HEIGHT ? KN_AXIS_HEIGHT : KN_AXIS_WIDTH;
    if (self->orientation == want) return;
    self->orientation = want;
    self->have_probe = FALSE;
    kn_queue_resize(widget);
}

void kn_box_set_spacing(GtkWidget *widget, int spacing) {
    KnBoxLayout *self = kn_layout_get(widget);
    if (self == NULL) return;
    guint want = spacing > 0 ? (guint) spacing : 0u;
    if (self->spacing == want) return;
    self->spacing = want;
    self->have_probe = FALSE;
    kn_queue_resize(widget);
}

void kn_box_set_homogeneous(GtkWidget *widget, int homogeneous) {
    KnBoxLayout *self = kn_layout_get(widget);
    if (self == NULL) return;
    gboolean want = homogeneous != 0;
    if (self->homogeneous == want) return;
    self->homogeneous = want;
    self->have_probe = FALSE;
    kn_queue_resize(widget);
}

/* Sets the Android-style `weight` of one child (values <= 0 mean "no weight", which is the
 * default and drops the per-child record entirely so an unweighted box stays allocation-free).
 */
void kn_box_set_weight(GtkWidget *widget, GtkWidget *child, double weight) {
    KnBoxLayout *self = kn_layout_get(widget);
    if (self == NULL || child == NULL || !GTK_IS_WIDGET(child)) return;
    if (!isfinite(weight) || weight <= 0.0) {
        kn_forget_child(self, child);
        kn_queue_resize(widget);
        return;
    }
    KnChildLayout *data = kn_child_layout_get(self, child);
    if ((double) data->weight == weight) return;
    data->weight = (float) weight;
    self->have_probe = FALSE;
    kn_queue_resize(widget);
}

double kn_box_get_weight(GtkWidget *widget, GtkWidget *child) {
    KnBoxLayout *self = kn_layout_get(widget);
    if (self == NULL || child == NULL) return 0.0;
    return (double) kn_child_weight(self, child);
}

/* Sets the `Modifier.maxWidth()`/`maxHeight()` ceiling of one child (KN_NO_MAX = "no ceiling on
 * this axis\). Like `weight`, this is a per-child property of THIS container, which is the only
 * place a ceiling can be enforced: GTK would otherwise hand the child its natural size.
 *
 * A child whose weight and both caps are back to their defaults loses its record entirely, so an
 * unmanaged-looking box really is allocation-free again (plan L9's "absent means default"). */
void kn_box_set_child_max(GtkWidget *widget, GtkWidget *child, int max_width, int max_height) {
    KnBoxLayout *self = kn_layout_get(widget);
    if (self == NULL || child == NULL || !GTK_IS_WIDGET(child)) return;
    int want_w = max_width < KN_NO_MAX ? KN_NO_MAX : max_width;
    int want_h = max_height < KN_NO_MAX ? KN_NO_MAX : max_height;

    KnChildLayout *data = kn_child_layout_peek(self, child);
    if (data == NULL) {
        if (want_w == KN_NO_MAX && want_h == KN_NO_MAX) return;   /* nothing to clear */
        data = kn_child_layout_get(self, child);
    }
    if (data->max_width == want_w && data->max_height == want_h) return;
    data->max_width = want_w;
    data->max_height = want_h;
    if (data->weight <= KN_NO_WEIGHT && want_w == KN_NO_MAX && want_h == KN_NO_MAX) {
        kn_forget_child(self, child);
    }
    self->have_probe = FALSE;
    kn_queue_resize(widget);
}

/* Current ceiling of `child` (KN_NO_MAX when it asks for none). Diagnostics and tests. */
int kn_box_get_child_max_width(GtkWidget *widget, GtkWidget *child) {
    KnBoxLayout *self = kn_layout_get(widget);
    if (self == NULL || child == NULL) return KN_NO_MAX;
    return kn_child_max(self, child, GTK_ORIENTATION_HORIZONTAL);
}

int kn_box_get_child_max_height(GtkWidget *widget, GtkWidget *child) {
    KnBoxLayout *self = kn_layout_get(widget);
    if (self == NULL || child == NULL) return KN_NO_MAX;
    return kn_child_max(self, child, GTK_ORIENTATION_VERTICAL);
}

/* Drops the per-child record of a child that has been removed (Kotlin calls this from
 * GtkApplier.removeChild; a disposed child is also safe to forget because the key is a pointer
 * that will not be looked up again while it is dead). */
void kn_box_forget_child(GtkWidget *widget, GtkWidget *child) {
    KnBoxLayout *self = kn_layout_get(widget);
    if (self == NULL || child == NULL) return;
    kn_forget_child(self, child);
}

/* Natural main-axis size (children + spacing); 0.0 when the widget has no box manager. Main
 * thread only, and only outside a layout pass (docs/tasks/compose-owned-animated-size.md §7). */
double kn_box_content(GtkWidget *widget) {
    KnBoxLayout *self = kn_layout_get(widget);
    if (self == NULL) return 0.0;
    return kn_probe_content(self, widget);
}

/* Applies the Compose-owned animated main-axis size (>= 0 px). Main thread only.
 *
 * This is the only thing that makes the container stop being content-driven: the first call
 * sets `ready` and from then on measure() reports `current` verbatim. Compose is responsible
 * for animating that value (and for snapping it on the first frame).
 */
void kn_box_set_current(GtkWidget *widget, double current) {
    KnBoxLayout *self = kn_layout_get(widget);
    if (self == NULL) return;
    if (!isfinite(current) || current < 0.0) return;

    gboolean was_ready = self->ready;
    gboolean changed = !was_ready || fabs(current - self->current) >= KN_CURRENT_EPSILON;
    self->current = current;
    self->ready = TRUE;
    /* Skip the resize pass while the value moves by less than 1/100 px: at 60-144 Hz the
     * integer allocation would be identical anyway. */
    if (changed) kn_queue_resize(widget);
}

/* Explicit main-axis size (>= 0 px) or -1 for content-driven. Only read before the first
 * `set_current()`: afterwards the applied size is `current`, so switching content <-> explicit
 * never jumps (Kotlin animates from the value it already has). */
void kn_box_set_fixed(GtkWidget *widget, int fixed) {
    KnBoxLayout *self = kn_layout_get(widget);
    if (self == NULL) return;
    if (self->fixed_size == fixed) return;
    self->fixed_size = fixed;
    self->have_probe = FALSE;
    kn_queue_resize(widget);
}

/* Explicit cross-axis size (>= 0 px) or -1 for content-driven. */
void kn_box_set_cross(GtkWidget *widget, int fixed) {
    KnBoxLayout *self = kn_layout_get(widget);
    if (self == NULL) return;
    if (self->fixed_cross == fixed) return;
    self->fixed_cross = fixed;
    kn_queue_resize(widget);
}

gboolean kn_box_is_ready(GtkWidget *widget) {
    KnBoxLayout *self = kn_layout_get(widget);
    return self == NULL ? FALSE : self->ready;
}

/* Turns an existing widget into a box container: same job `kn_attach_animated_size_layout()`
 * used to do, but the orientation is now a property of the container rather than of the
 * animation, and it keeps working when nothing animates. `duration_ms`/`easing` are DEPRECATED
 * and ignored (Compose owns the timing since docs/tasks/compose-owned-animated-size.md); they
 * stay in the signature so existing Kotlin call sites keep compiling. */
void kn_attach_animated_size_layout(GtkWidget *widget,
                                    unsigned int duration_ms,
                                    unsigned int easing,
                                    unsigned int spacing,
                                    unsigned int axis) {
    (void) duration_ms;
    (void) easing;
    if (widget == NULL || !GTK_IS_WIDGET(widget)) return;

    KnBoxLayout *existing = kn_layout_get(widget);
    if (existing != NULL) {
        /* Already a box container: the animated attach only overrides axis + spacing. A plain
         * GtkBox keeps its own spacing when the caller passes 0, so the Kotlin modifier stays
         * spacing-agnostic. */
        if (spacing > 0 || existing->spacing == 0) {
            kn_box_set_spacing(widget, (int) (spacing > 0 ? spacing : 0));
        }
        existing->orientation = axis == KN_AXIS_HEIGHT ? KN_AXIS_HEIGHT : KN_AXIS_WIDTH;
        existing->have_probe = FALSE;
        kn_queue_resize(widget);
        return;
    }

    int effective_spacing = (int) spacing;
    if (effective_spacing == 0 && GTK_IS_BOX(widget)) {
        effective_spacing = gtk_box_get_spacing(GTK_BOX(widget));
    }
    kn_box_attach(widget, (int) axis, effective_spacing, 0);
    /* Animated containers clip: content is revealed, not reflowed (see the header). */
    gtk_widget_set_overflow(widget, GTK_OVERFLOW_HIDDEN);
}

/* Legacy C entry points kept for the animated-size pipeline (`box_layout.rs` forwards to the
 * `kn_box_*` functions directly; these names stay so C-side callers and old bindings work).
 * the single implementation so the animated-size pipeline keeps its ABI while there is only one
 * place that knows how a box packs. */
void kn_animated_size_layout_set_current(GtkWidget *widget, double current) {
    kn_box_set_current(widget, current);
}
void kn_animated_size_layout_set_fixed(GtkWidget *widget, int fixed) {
    kn_box_set_fixed(widget, fixed);
}
void kn_animated_size_layout_set_cross(GtkWidget *widget, int fixed) {
    kn_box_set_cross(widget, fixed);
}
double kn_animated_size_layout_content(GtkWidget *widget) {
    return kn_box_content(widget);
}
gboolean kn_animated_size_layout_is_ready(GtkWidget *widget) {
    return kn_box_is_ready(widget);
}
