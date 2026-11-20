// The `ShellService` supertype implemented by every service object.

package dev.knshell.services

/**
 * A shell service backed by the Rust core.
 *
 * Services are created and started lazily by [Services]; [start] must be idempotent and
 * keeps the UniFFI callback object alive for the lifetime of the process.
 */
interface ShellService {
    /** Starts the service and its Rust subscription; idempotent. */
    fun start()
}
