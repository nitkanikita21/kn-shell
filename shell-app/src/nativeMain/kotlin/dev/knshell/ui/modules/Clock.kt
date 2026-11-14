package dev.knshell.ui.modules

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.knshell.compose.AnimatedWidth
import dev.knshell.compose.Label
import dev.knshell.compose.Modifier
import dev.knshell.compose.onClick
import dev.knshell.compose.pointerCursor
import dev.knshell.compose.textColor
import dev.knshell.ui.Grayscale
import dev.knshell.ui.primitives.Icon
import kotlinx.coroutines.delay
import kotlinx.datetime.Clock
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.format
import kotlinx.datetime.format.DayOfWeekNames
import kotlinx.datetime.format.MonthNames
import kotlinx.datetime.format.char
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Duration.Companion.seconds

private val ShortFormat = LocalDateTime.Format {
    hour(); char(':'); minute()
}

private val LongFormat = LocalDateTime.Format {
    hour(); char(':'); minute(); chars("   ")
    dayOfMonth(); char(' '); dayOfWeek(DayOfWeekNames.ENGLISH_ABBREVIATED); chars(" ")
    monthName(MonthNames.ENGLISH_ABBREVIATED)
}

/** Clock module: time + date, click toggles the compact/extended format. */
@Composable
fun Clock() {
    var shortFormat by remember { mutableStateOf(true) }

    val now by produceState(Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())) {
        while (true) {
            value = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())
            delay(1.seconds)
        }
    }

    AnimatedWidth(spacing = 10) {
        Icon("\uF43A")
        Label(
            text = now.format(if (shortFormat) ShortFormat else LongFormat),
            modifier = Modifier
                .textColor(Grayscale.Accent)
                .onClick { shortFormat = !shortFormat }
                .pointerCursor(),
        )
    }
}
