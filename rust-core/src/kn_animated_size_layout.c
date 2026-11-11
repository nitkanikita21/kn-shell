/*
 * GtkLayoutManager implementation that animates a container's width towards the natural
 * width of its child, in both directions.
 *
 * A plain size request is a *minimum*, so growth cannot be animated from Kotlin. Reporting
 * the animated width from measure() (as both minimum and natural) makes the parent allocate
 * exactly that value; the child keeps its own natural width and the container clips it.
 *
 * Deliberately written in C: subclassing GObject from gtk4-rs inside the Kotlin/Native
 * process corrupted the GObject type state (all widgets became invalid after attaching the
 * layout manager). Plain G_DEFINE_TYPE has neither the double-initialisation problem nor
 * the Rust subclass trait wrappers.
 */

#include <gtk/gtk.h>
#include <math.h>

typedef struct _KnAnimatedSizeLayout KnAnimatedSizeLayout;
typedef struct _KnAnimatedSizeLayoutClass KnAnimatedSizeLayoutClass;

struct _KnAnimatedSizeLayout {
    GtkLayoutManager parent_instance;

    double current_w;
    double from_w;
    double target_w;

    guint duration_ms;
    guint easing;

    gboolean initialized;
    gboolean have_probe;
    double probe_w;
    gboolean animating;
    gint64 start_us;
};

struct _KnAnimatedSizeLayoutClass {
    GtkLayoutManagerClass parent_class;
};

G_DEFINE_TYPE(KnAnimatedSizeLayout, kn_animated_size_layout, GTK_TYPE_LAYOUT_MANAGER)

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

static void kn_animated_size_layout_measure(GtkLayoutManager *manager,
                                            GtkWidget *widget,
                                            GtkOrientation orientation,
                                            int for_size,
                                            int *minimum,
                                            int *natural,
                                            int *minimum_baseline,
                                            int *natural_baseline) {
    KnAnimatedSizeLayout *self = (KnAnimatedSizeLayout *) manager;
    if (orientation == GTK_ORIENTATION_HORIZONTAL) {
        int value = self->current_w > 0.0 ? (int) lround(self->current_w) : 0;
        *minimum = value;
        *natural = value;
    } else {
        GtkWidget *child = gtk_widget_get_first_child(widget);
        int height = 0;
        if (child != NULL) {
            int min_h = 0;
            int nat_h = 0;
            int min_b = -1;
            int nat_b = -1;
            gtk_widget_measure(child, GTK_ORIENTATION_VERTICAL, -1,
                               &min_h, &nat_h, &min_b, &nat_b);
            height = nat_h;
        }
        *minimum = height;
        *natural = height;
    }

    *minimum_baseline = -1;
    *natural_baseline = -1;
}

static void kn_animated_size_layout_allocate(GtkLayoutManager *manager,
                                             GtkWidget *widget,
                                             int width,
                                             int height,
                                             int baseline) {
    (void) manager;
    (void) baseline;

    GtkWidget *child = gtk_widget_get_first_child(widget);
    if (child == NULL) return;

    int min_w = 0;
    int nat_w = 0;
    int min_b = -1;
    int nat_b = -1;
    gtk_widget_measure(child, GTK_ORIENTATION_HORIZONTAL, height,
                       &min_w, &nat_w, &min_b, &nat_b);

    /* Keep the content at its natural size; the container clips while the width animates. */
    int child_width = MAX(nat_w, width);
    gtk_widget_allocate(child, child_width, height, -1, NULL);
}

static void kn_animated_size_layout_class_init(KnAnimatedSizeLayoutClass *klass) {
    GtkLayoutManagerClass *manager_class = GTK_LAYOUT_MANAGER_CLASS(klass);
    manager_class->measure = kn_animated_size_layout_measure;
    manager_class->allocate = kn_animated_size_layout_allocate;
}

static void kn_animated_size_layout_init(KnAnimatedSizeLayout *self) {
    self->current_w = -1.0;
    self->from_w = -1.0;
    self->target_w = -1.0;
    self->duration_ms = 180;
    self->easing = 1;
    self->initialized = FALSE;
    self->have_probe = FALSE;
    self->probe_w = -1.0;
    self->animating = FALSE;
    self->start_us = 0;
}

static void kn_update(KnAnimatedSizeLayout *self, GtkWidget *widget) {
    GtkWidget *child = gtk_widget_get_first_child(widget);
    if (child == NULL) return;

    int min_w = 0;
    int nat_w = 0;
    int min_b = -1;
    int nat_b = -1;
    gtk_widget_measure(child, GTK_ORIENTATION_HORIZONTAL, -1,
                       &min_w, &nat_w, &min_b, &nat_b);
    double natural = (double) nat_w;

    if (!self->initialized) {
        if (!gtk_widget_get_mapped(widget)) return;
        if (natural <= 0.0) return; /* wait for the first real layout */
        if (!self->have_probe || fabs(natural - self->probe_w) > 0.5) {
            /* The first frames can measure a partly-laid-out child; wait until the
             * natural width is stable before snapping, otherwise the chip starts from
             * a bogus width and visibly grows once. */
            self->probe_w = natural;
            self->have_probe = TRUE;
            return;
        }
        self->initialized = TRUE;
        self->current_w = natural;
        self->target_w = natural;
        gtk_widget_queue_resize(widget);
        return;
    }

    if (fabs(natural - self->target_w) > 0.5) {
        self->from_w = self->current_w;
        self->target_w = natural;
        self->start_us = g_get_monotonic_time();
        self->animating = TRUE;
    }

    if (self->animating) {
        double elapsed = (double) (g_get_monotonic_time() - self->start_us) / 1e6;
        double duration = self->duration_ms > 0 ? (double) self->duration_ms / 1000.0 : 0.001;
        double progress = elapsed / duration;
        self->current_w = self->from_w + (self->target_w - self->from_w) * kn_ease(self->easing, progress);
        if (progress >= 1.0) {
            self->current_w = self->target_w;
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

/* Called from Rust over UniFFI; `widget` is a raw GtkWidget* (main thread only). */
void kn_attach_animated_size_layout(GtkWidget *widget, unsigned int duration_ms, unsigned int easing) {
    if (!GTK_IS_WIDGET(widget)) return;

    KnAnimatedSizeLayout *layout = g_object_new(kn_animated_size_layout_get_type(), NULL);
    layout->duration_ms = duration_ms == 0 ? 180 : duration_ms;
    layout->easing = easing;

    gtk_widget_set_layout_manager(widget, GTK_LAYOUT_MANAGER(layout));
    gtk_widget_set_overflow(widget, GTK_OVERFLOW_HIDDEN);

    g_object_ref(layout);
    gtk_widget_add_tick_callback(widget, kn_tick, layout, kn_tick_destroy);
    g_object_unref(layout);
}
