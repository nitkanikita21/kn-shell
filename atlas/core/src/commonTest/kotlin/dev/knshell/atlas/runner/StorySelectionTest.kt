package dev.knshell.atlas.runner

import dev.knshell.atlas.core.StoryGroup
import dev.knshell.atlas.dsl.group
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

class StorySelectionTest {
    private val button: StoryGroup = group("Button") {
        render { _ -> }
        story("Primary")
        variants("Matrix") { }
    }
    private val input: StoryGroup = group("Input") {
        render { _ -> }
        story("Size/Large")
        story("Plain")
    }
    private val empty: StoryGroup = group("Empty") { render { _ -> } }

    private val groups = listOf(button, input)

    // --- resolveStory ---

    @Test
    fun `resolveStory finds a story by group and entry name`() {
        val selection = resolveStory(groups, "Button/Primary")
        assertSame(button, selection?.group)
        assertEquals("Primary", selection?.entry?.name)
    }

    @Test
    fun `resolveStory finds a variants entry`() {
        val selection = resolveStory(groups, "Button/Matrix")
        assertSame(button, selection?.group)
        assertEquals("Matrix", selection?.entry?.name)
    }

    @Test
    fun `resolveStory ignores case for group and entry names`() {
        val selection = resolveStory(groups, "BUTTON/matrix")
        assertSame(button, selection?.group)
        assertEquals("Matrix", selection?.entry?.name)
    }

    @Test
    fun `resolveStory without a slash returns null`() {
        assertNull(resolveStory(groups, "Button"))
        assertNull(resolveStory(groups, ""))
    }

    @Test
    fun `resolveStory with unknown group returns null`() {
        assertNull(resolveStory(groups, "Missing/Primary"))
    }

    @Test
    fun `resolveStory with unknown entry returns null`() {
        assertNull(resolveStory(groups, "Button/Missing"))
    }

    @Test
    fun `resolveStory splits on the first slash so entry names may contain slashes`() {
        val selection = resolveStory(groups, "Input/Size/Large")
        assertSame(input, selection?.group)
        assertEquals("Size/Large", selection?.entry?.name)
    }

    // --- defaultSelection ---

    @Test
    fun `defaultSelection is null without groups`() {
        assertNull(defaultSelection(emptyList()))
    }

    @Test
    fun `defaultSelection is null when every group is empty`() {
        assertNull(defaultSelection(listOf(empty)))
    }

    @Test
    fun `defaultSelection is the first entry of the first group`() {
        val selection = defaultSelection(groups)
        assertSame(button, selection?.group)
        assertEquals("Primary", selection?.entry?.name)
    }

    @Test
    fun `defaultSelection skips leading empty groups`() {
        val selection = defaultSelection(listOf(empty, button))
        assertSame(button, selection?.group)
        assertEquals("Primary", selection?.entry?.name)
    }

    // --- parseStorySpec ---

    @Test
    fun `parseStorySpec reads separate story value`() {
        assertEquals("Button/Primary", parseStorySpec(arrayOf("--story", "Button/Primary"), null))
    }

    @Test
    fun `parseStorySpec reads equals form`() {
        assertEquals("Button/Primary", parseStorySpec(arrayOf("--story=Button/Primary"), null))
    }

    @Test
    fun `parseStorySpec prefers the argument over env`() {
        assertEquals("Button/Primary", parseStorySpec(arrayOf("--story", "Button/Primary"), "Input/Plain"))
        assertEquals("Button/Primary", parseStorySpec(arrayOf("--story=Button/Primary"), "Input/Plain"))
    }

    @Test
    fun `parseStorySpec falls back to env when flag has no value`() {
        assertEquals("Input/Plain", parseStorySpec(arrayOf("--story"), "Input/Plain"))
    }

    @Test
    fun `parseStorySpec falls back to env when no arguments are given`() {
        assertEquals("Input/Plain", parseStorySpec(emptyArray(), "Input/Plain"))
    }

    @Test
    fun `parseStorySpec returns null for empty env`() {
        assertNull(parseStorySpec(emptyArray(), ""))
        assertNull(parseStorySpec(arrayOf("--story"), ""))
    }

    @Test
    fun `parseStorySpec returns null without arguments and without env`() {
        assertNull(parseStorySpec(emptyArray(), null))
    }

    @Test
    fun `parseStorySpec keeps a given argument even when env is empty`() {
        assertEquals("Button/Primary", parseStorySpec(arrayOf("--story", "Button/Primary"), ""))
    }
}
