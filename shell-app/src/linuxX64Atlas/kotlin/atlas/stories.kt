package atlas

import dev.knshell.atlas.dsl.group
import dev.knshell.compose.Button
import dev.knshell.compose.Color
import dev.knshell.compose.Icon
import dev.knshell.compose.Label
import dev.knshell.compose.Modifier
import dev.knshell.compose.background
import dev.knshell.compose.opacity
import dev.knshell.compose.padding
import dev.knshell.compose.radius
import dev.knshell.compose.textColor
import dev.knshell.icons.lucide.Lucide
import dev.knshell.ui.primitives.Space

enum class ButtonSize(val scale: Double) {
    Small(0.3),
    Medium(0.6),
    Large(1.0)
}

private fun Modifier.buttonBase(enabled: Boolean, size: ButtonSize, radius: Int, opacity: Float): Modifier =
    background(if (enabled) Color.hex(0x00FF00) else Color.hex(0xFF0000))
        .padding((32 * size.scale).toInt(), (16 * size.scale).toInt())
        .radius(radius)
        .opacity(opacity)

val buttonStories = group("Button") {
    val text by string("Save")
    val enabled by boolean(true)
    val size by enum(ButtonSize.Medium)
    val radius by int(16, 0..32)
    val opacity by float(1f, 0.1f..1f)

    render { v ->
        Button(
            modifier = Modifier
                .buttonBase(v[enabled], v[size], v[radius], v[opacity])
                .textColor(if (v[enabled]) Color.hex(0x000000) else Color.White),
            onClick = {}
        ) {
            Label(
                text = "${v[text]} ${v[enabled]} ${v[size]}",
                modifier = Modifier.textColor(if (v[enabled]) Color.hex(0x000000) else Color.White),
            )
        }
    }

    story("Primary")
    story("Icon") { v ->
        Button(
            modifier = Modifier.buttonBase(v[enabled], v[size], v[radius], v[opacity]),
            onClick = {}
        ) {
            Icon(Lucide.Check, size = (24 * v[size].scale).toInt())
            Space(8)
            Label(
                text = "${v[text]} ${v[enabled]} ${v[size]}",
                modifier = Modifier.textColor(if (v[enabled]) Color.hex(0x000000) else Color.White),
            )
        }
    }
//    story("In dialog") { v -> Dialog { Button(size = v[size]) {} } }

    variants("Size X State") {
        axis(size)
        axis(enabled)
    }
}
