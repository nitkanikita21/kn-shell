package atlas

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.knshell.atlas.core.entry.MatrixView
import dev.knshell.atlas.dsl.group
import dev.knshell.compose.Button
import dev.knshell.compose.Color
import dev.knshell.compose.Column
import dev.knshell.compose.Icon
import dev.knshell.compose.Label
import dev.knshell.compose.Modifier
import dev.knshell.compose.Row
import dev.knshell.compose.background
import dev.knshell.compose.fontSize
import dev.knshell.compose.hoverBackground
import dev.knshell.compose.opacity
import dev.knshell.compose.padding
import dev.knshell.compose.pointerCursor
import dev.knshell.compose.pressedBackground
import dev.knshell.compose.radius
import dev.knshell.compose.textColor
import dev.knshell.icons.IconRef
import dev.knshell.icons.lucide.Lucide
import dev.knshell.ui.primitives.Space

enum class ButtonSize(val fontSize: Int, val horizontal: Int, val vertical: Int) {
    Small(11, 10, 4),
    Medium(13, 18, 8),
    Large(16, 28, 12),
}

private val ink = Color.hex(0x0B0E0F)
private val paper = Color.hex(0xC8D2CE)
private val clear = Color.hex(0x000000, 0f)

enum class ButtonVariant(
    val fill: Color,
    val hover: Color,
    val pressed: Color,
    val content: Color,
    val border: Color? = null,
) {
    Primary(Color.hex(0x7FD6A2), Color.hex(0x96E3B5), Color.hex(0x63B886), ink),
    Secondary(Color.hex(0x233029), Color.hex(0x2E4036), Color.hex(0x1A241F), paper),
    Outline(clear, Color.hex(0x181C1D), Color.hex(0x233029), paper, border = Color.hex(0x4A5756)),
    Ghost(clear, Color.hex(0x181C1D), Color.hex(0x233029), Color.hex(0x7FD6A2)),
    Destructive(Color.hex(0xD9534F), Color.hex(0xE66B67), Color.hex(0xB83F3B), Color.White),
}

enum class ButtonIcon(val ref: IconRef?) {
    None(null),
    Check(Lucide.Check),
    Plus(Lucide.Plus),
    Trash(Lucide.Trash2),
    Arrow(Lucide.ArrowRight),
}

/**
 * The demo button of the catalog: `framework` only has a bare [Button], so the look is built here
 * from modifiers. A disabled button is dimmed, ignores clicks and does not react to the pointer.
 */
@Composable
private fun DemoButton(
    text: String,
    variant: ButtonVariant,
    size: ButtonSize,
    enabled: Boolean,
    radius: Int,
    opacity: Float,
    icon: ButtonIcon = ButtonIcon.None,
    onClick: () -> Unit = {},
) {
    val surface = Modifier
        .background(
            variant.fill,
            borderWidth = if (variant.border != null) 1 else 0,
            borderColor = variant.border,
        )
        .radius(radius)
        .padding(size.horizontal, size.vertical)
        .opacity(if (enabled) opacity else opacity * 0.4f)
    val modifier = if (enabled) {
        surface.hoverBackground(variant.hover).pressedBackground(variant.pressed).pointerCursor()
    } else {
        surface
    }
    Button(modifier = modifier, onClick = { if (enabled) onClick() }) {
        icon.ref?.let { Icon(it, size = size.fontSize + 3, color = variant.content) }
        if (icon.ref != null && text.isNotEmpty()) Space(8)
        if (text.isNotEmpty()) {
            Label(text, modifier = Modifier.textColor(variant.content).fontSize(size.fontSize))
        }
    }
}

val buttonStories = group("Button") {
    val text by string("Save")
    val variant by enum(ButtonVariant.Primary)
    val size by enum(ButtonSize.Medium)
    val icon by enum(ButtonIcon.None)
    val enabled by boolean(true)
    val radius by int(8, 0..32)
    val opacity by float(1f, 0.1f..1f)

    render { v ->
        DemoButton(v[text], v[variant], v[size], v[enabled], v[radius], v[opacity], v[icon])
    }

    // усе керується контролами праворуч
    story("Playground")

    story("Icon") { v ->
        val shown = v[icon].takeIf { it != ButtonIcon.None } ?: ButtonIcon.Check
        DemoButton(v[text], v[variant], v[size], v[enabled], v[radius], v[opacity], shown)
    }

    story("Icon only") { v ->
        val shown = v[icon].takeIf { it != ButtonIcon.None } ?: ButtonIcon.Plus
        DemoButton("", v[variant], v[size], v[enabled], v[radius], v[opacity], shown)
    }

    // перевіряє, що onClick справді доходить
    story("Click counter") { v ->
        var clicks by remember { mutableStateOf(0) }
        Column(spacing = 12) {
            DemoButton("${v[text]} · $clicks", v[variant], v[size], v[enabled], v[radius], v[opacity], v[icon]) {
                clicks++
            }
            DemoButton("Reset counter", ButtonVariant.Ghost, ButtonSize.Small, clicks > 0, v[radius], 1f) {
                clicks = 0
            }
        }
    }

    story("All variants") { v ->
        // стовпчиком: пʼять великих кнопок у ряд не вміщаються в превʼю
        Column(spacing = 12) {
            ButtonVariant.entries.forEach {
                DemoButton(it.name, it, v[size], v[enabled], v[radius], v[opacity], v[icon])
            }
        }
    }

    story("All sizes") { v ->
        Row(spacing = 12) {
            ButtonSize.entries.forEach {
                DemoButton(it.name, v[variant], it, v[enabled], v[radius], v[opacity], v[icon])
            }
        }
    }

    story("Toolbar") { v ->
        Row(spacing = 8) {
            DemoButton("New", ButtonVariant.Primary, v[size], v[enabled], v[radius], v[opacity], ButtonIcon.Plus)
            DemoButton(v[text], ButtonVariant.Secondary, v[size], v[enabled], v[radius], v[opacity], ButtonIcon.Check)
            DemoButton("Next", ButtonVariant.Outline, v[size], v[enabled], v[radius], v[opacity], ButtonIcon.Arrow)
            DemoButton("", ButtonVariant.Destructive, v[size], v[enabled], v[radius], v[opacity], ButtonIcon.Trash)
        }
    }

    variants("Variant X Size", view = MatrixView.Table) {
        axis(variant)
        axis(size)
    }

    variants("Variant X State") {
        axis(variant)
        axis(enabled)
    }

    variants("Size X Icon") {
        axis(size)
        axis(icon)
    }
}
