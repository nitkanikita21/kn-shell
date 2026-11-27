// ---------------------------------------------------------------------------
// The demo window of the example shell: the UI kit against the live wallpaper theme. Components in
// one tab, an example notification center in another, and the controls of the theme in the third.
// ---------------------------------------------------------------------------

package dev.knshell.app.ui.demo

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.knshell.app.ui.notifications.NotificationCenter
import dev.knshell.compose.Column
import dev.knshell.compose.Modifier
import dev.knshell.compose.OverlayHost
import dev.knshell.compose.Scroll
import dev.knshell.compose.expand
import dev.knshell.compose.hExpand
import dev.knshell.compose.padding
import dev.knshell.icons.lucide.Lucide
import dev.knshell.uikit.components.feedback.UToasterHost
import dev.knshell.uikit.components.feedback.rememberUToaster
import dev.knshell.uikit.components.feedback.uToastShortcut
import dev.knshell.uikit.components.navigation.UTabItem
import dev.knshell.uikit.components.navigation.UTabs
import dev.knshell.uikit.theme.USurface

private val sections = listOf(
    UTabItem("components", "Компоненти", icon = Lucide.LayoutGrid),
    UTabItem("notifications", "Сповіщення", icon = Lucide.Bell),
    UTabItem("theme", "Тема", icon = Lucide.SlidersHorizontal),
)

/** The content of the demo window. It expects the shell's theme around it. */
@Composable
fun TestUi() {
    var section by remember { mutableStateOf("components") }
    val toaster = rememberUToaster()

    // The layers of dialogs and toasts cover the whole window.
    OverlayHost(Modifier.expand()) {
        USurface(Modifier.expand().uToastShortcut(toaster)) {
            Scroll(modifier = Modifier.expand()) {
                Column(spacing = 16, modifier = Modifier.hExpand().padding(all = 20)) {
                    Header()
                    UTabs(
                        items = sections,
                        selected = section,
                        onSelect = { section = it },
                        accessibleLabel = "Розділи демо",
                        modifier = Modifier.hExpand(),
                    ) { item ->
                        when (item.id) {
                            "components" -> ComponentsSection(toaster)
                            "notifications" -> NotificationCenter(Modifier.hExpand())
                            else -> ThemeSection()
                        }
                    }
                }
            }
        }
        UToasterHost(toaster)
    }
}
