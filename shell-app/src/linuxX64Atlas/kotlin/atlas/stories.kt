package atlas

import dev.knshell.atlas.dsl.group
import dev.knshell.compose.Button
import dev.knshell.compose.Color
import dev.knshell.compose.Label
import dev.knshell.compose.Modifier
import dev.knshell.compose.background
import dev.knshell.compose.padding
import dev.knshell.compose.radius
import dev.knshell.compose.textColor

enum class ButtonSize(val scale: Double) {
    Small(0.3),
    Medium(0.6),
    Large(1.0)
}

val buttonStories = group("Button") {
    val text by string("Save")
    val enabled by boolean(true)
    val size by enum(ButtonSize.Medium)

    render { v ->
        Button(
            modifier = Modifier
                .background(if(v[enabled]) Color.hex(0x00FF00) else Color.hex(0xFF0000))
                .padding((32*v[size].scale).toInt(), (16*v[size].scale).toInt())
                .radius(16)
                .textColor(if(v[enabled]) Color.hex(0x000000) else Color.White),
            onClick = {}
        ) {
            Label(
                text = "${v[text]} ${v[enabled]} ${v[size]}",
                modifier = Modifier.textColor(if (v[enabled]) Color.hex(0x000000) else Color.White),
            )
        }
    }

    story("Primary")
//    story("In dialog") { v -> Dialog { Button(size = v[size]) {} } }

    variants("Matrix") {
        axis(size)
        axis(enabled)
    }
}