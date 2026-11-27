package dev.knshell.uikit.components.feedback

import dev.knshell.uikit.core.UColor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

class ToastDurationTest {

    private val theme = 4.seconds

    @Test
    fun `the toast's own duration wins`() {
        assertEquals(1.seconds, resolveToastDuration(1.seconds, UColor.Info, 9.seconds, theme))
    }

    @Test
    fun `without its own a toast takes the toaster's duration then the theme's`() {
        assertEquals(9.seconds, resolveToastDuration(null, UColor.Info, 9.seconds, theme))
        assertEquals(theme, resolveToastDuration(null, UColor.Info, null, theme))
    }

    @Test
    fun `an error without a duration of its own never closes by itself`() {
        assertEquals(Duration.INFINITE, resolveToastDuration(null, UColor.Error, null, theme))
        assertEquals(Duration.INFINITE, resolveToastDuration(null, UColor.Error, 9.seconds, theme))
        assertEquals(2.seconds, resolveToastDuration(2.seconds, UColor.Error, null, theme))
    }
}

class UToasterTest {

    private fun toaster() = UToaster(duration = null)

    @Test
    fun `add keeps the order and gives each toast its own id`() {
        val toaster = toaster()
        val first = toaster.add(UToast("перша"))
        val second = toaster.add(UToast("друга"))
        assertNotEquals(first, second)
        assertEquals(listOf("перша", "друга"), toaster.toasts.map { it.title })
    }

    @Test
    fun `update replaces the toast under the same id`() {
        val toaster = toaster()
        val id = toaster.add(UToast("збірка"))
        toaster.update(id, UToast("збірка готова"))
        assertEquals(listOf("збірка готова"), toaster.toasts.map { it.title })
    }

    @Test
    fun `update of a removed id changes nothing`() {
        val toaster = toaster()
        val id = toaster.add(UToast("a"))
        toaster.remove(id)
        toaster.update(id, UToast("b"))
        assertTrue(toaster.toasts.isEmpty())
    }

    @Test
    fun `remove takes one toast away and clear takes all of them`() {
        val toaster = toaster()
        val first = toaster.add(UToast("a"))
        toaster.add(UToast("b"))
        toaster.remove(first)
        assertEquals(listOf("b"), toaster.toasts.map { it.title })
        toaster.clear()
        assertTrue(toaster.toasts.isEmpty())
    }
}

class UCountdownTest {

    @Test
    fun `advance takes time away and progress follows`() {
        val countdown = UCountdown(4.seconds)
        countdown.advance(1.seconds)
        assertEquals(3.seconds, countdown.remaining)
        assertEquals(0.75f, countdown.progress)
    }

    @Test
    fun `it finishes at zero and stays there`() {
        val countdown = UCountdown(2.seconds)
        countdown.advance(5.seconds)
        assertTrue(countdown.finished)
        assertEquals(Duration.ZERO, countdown.remaining)
        assertEquals(0f, countdown.progress)
    }

    @Test
    fun `paused time is not taken away`() {
        val countdown = UCountdown(4.seconds)
        countdown.pause()
        countdown.advance(1.seconds)
        assertEquals(4.seconds, countdown.remaining)
        countdown.resume()
        countdown.advance(1.seconds)
        assertEquals(3.seconds, countdown.remaining)
    }

    @Test
    fun `restart gives back the whole duration`() {
        val countdown = UCountdown(4.seconds)
        countdown.advance(3.seconds)
        countdown.restart()
        assertEquals(1f, countdown.progress)
        assertFalse(countdown.finished)
    }

    @Test
    fun `an infinite countdown never finishes and stays full`() {
        val countdown = UCountdown(Duration.INFINITE)
        countdown.advance(1000.seconds)
        assertFalse(countdown.finished)
        assertEquals(1f, countdown.progress)
    }
}
