package atlas

import dev.knshell.atlas.core.StoryGroup
import stories.element.uAvatarStories
import stories.element.uBadgeStories
import stories.element.uButtonStories
import stories.element.uCardStories
import stories.element.uKbdStories
import stories.element.uSeparatorStories
import stories.feedback.uAlertStories
import stories.feedback.uProgressStories
import stories.feedback.uSkeletonStories
import stories.feedback.uToastStories
import stories.form.uCheckboxStories
import stories.form.uFormFieldStories
import stories.form.uInputStories
import stories.form.uRadioGroupStories
import stories.form.uSelectStories
import stories.form.uSliderStories
import stories.form.uSwitchStories
import stories.navigation.uAccordionStories
import stories.navigation.uTabsStories
import stories.overlays.uDropdownMenuStories
import stories.overlays.uModalStories
import stories.overlays.uPopoverStories
import stories.overlays.uTooltipStories

/** Every group of the kit's catalog, in sidebar order: by category, as the packages are. */
val kitStories: List<StoryGroup> = listOf(
    // Element
    uButtonStories,
    uBadgeStories,
    uCardStories,
    uSeparatorStories,
    uKbdStories,
    uAvatarStories,
    // Feedback
    uProgressStories,
    uSkeletonStories,
    uAlertStories,
    uToastStories,
    // Form
    uInputStories,
    uFormFieldStories,
    uCheckboxStories,
    uSwitchStories,
    uRadioGroupStories,
    uSliderStories,
    uSelectStories,
    // Navigation
    uTabsStories,
    uAccordionStories,
    // Overlays
    uTooltipStories,
    uPopoverStories,
    uDropdownMenuStories,
    uModalStories,
)
