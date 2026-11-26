package dev.knshell.shell

/**
 * The composition threw while recomposing and has stopped: every window of the process keeps its last
 * state from now on. [cause] is what was thrown.
 */
class CompositionFailedException(cause: Throwable) :
    Exception("the composition failed: ${cause.message ?: cause::class.simpleName}", cause)
