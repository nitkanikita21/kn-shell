/*
 * palette_core.h — C ABI for the `palette-core` Rust crate.
 *
 * This header is hand-written and intentionally minimal: it declares only the
 * raw C symbols exported by the `palette_core` cdylib/staticlib
 * (`#[no_mangle] extern "C"` functions in `src/ffi.rs`). It has no dependency
 * on Rust or `stddef.h` beyond the fixed-width integer types from `<stdint.h>`
 * and `char` from the C standard.
 *
 * ---------------------------------------------------------------------------
 * String ownership / lifetime contract
 * ---------------------------------------------------------------------------
 * Every `char *` returned by this library is allocated with Rust's
 * `CString::into_raw` and is owned by the caller. Free it exactly once with
 * `palette_free_string`. Freeing a pointer twice, or freeing a pointer that was
 * not produced by this library, is undefined behaviour.
 *
 *  * Synchronous (`palette_extract_sync`): `out_json` / `err_out` receive
 *    freshly allocated strings. The caller frees them on the *same thread* that
 *    called the function.
 *
 *  * Asynchronous (`palette_extract_async`): the supplied `callback` runs on a
 *    dedicated worker thread and receives two freshly allocated strings
 *    (`result` and `err`, exactly one of which is non-NULL). The callback MUST
 *    free BOTH of them with `palette_free_string` *before returning*, i.e. on
 *    that same worker thread. The pointers are valid only for the duration of
 *    the callback; copy anything you need out of them first. Freeing them on
 *    any other thread is unsafe on the target glibc (a cross-thread free of a
 *    tcache chunk aborts the process).
 *
 * `palette_free_string` is NULL-safe and may be called with a NULL pointer.
 *
 * ---------------------------------------------------------------------------
 * Options JSON shape (`opts_json`)
 * ---------------------------------------------------------------------------
 * Either NULL/empty for defaults, or a JSON object. Recognised fields:
 *
 *   {
 *     "k":            6,        // u8  : number of clusters (palette size)
 *     "max_input_dim": 64,      // u32 : downsample so the longest side <= this
 *     "min_weight":    0.03,    // f32 : drop clusters below this weight
 *     "chroma_floor":  0.0,     // f32 : ignore pixels with chroma below this
 *     "seed":          null     // u64?: fixed RNG seed for reproducibility
 *   }
 *
 * ---------------------------------------------------------------------------
 * Result JSON shape (the `result` string)
 * ---------------------------------------------------------------------------
 * A JSON object: `{ "colors": [ { "l": f64, "c": f64, "h": f64|null,
 * "weight": f64 }, ... ] }`, sorted by `weight` descending. `h` is NULL when
 * the colour is achromatic (hue is undefined).
 */

#ifndef PALETTE_CORE_H
#define PALETTE_CORE_H

#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

/*
 * Synchronous extraction.
 *
 * @param path       UTF-8 path to the image file. Must not be NULL or empty.
 * @param opts_json  JSON encoding of the options above, or NULL for defaults.
 * @param out_json   Receives a newly allocated JSON result string on success
 *                   (free with palette_free_string). May be NULL to ignore.
 * @param err_out    Receives a newly allocated error string on failure
 *                   (free with palette_free_string). May be NULL to ignore.
 *
 * @return 0 on success; non-zero on error (the error text is in *err_out).
 *         Specific codes are not guaranteed stable across versions.
 */
int32_t palette_extract_sync(const char *path,
                             const char *opts_json,
                             char **out_json,
                             char **err_out);

/*
 * Asynchronous, non-blocking extraction.
 *
 * The work runs on a dedicated worker thread (the k-means step itself uses the
 * rayon thread pool). The caller-supplied `callback` is invoked exactly once —
 * with a result, an error, or the literal string "cancelled" — from that worker
 * thread. The two string pointers passed to the callback are allocated on the
 * worker thread and are only valid for the duration of the callback; the
 * callback MUST free both of them with palette_free_string before returning.
 *
 * @return A non-zero handle that can be passed to palette_cancel. The value 0
 *         is reserved to mean "no handle" and is never returned.
 */
uint64_t palette_extract_async(const char *path,
                               const char *opts_json,
                               void (*callback)(const char *result,
                                                const char *err,
                                                void *user_data),
                               void *user_data);

/*
 * Request cancellation of an in-flight async extraction.
 *
 * Safe to call with an unknown or already-completed handle (it is a no-op).
 * Cancellation sets a flag that the extraction loop checks between steps; the
 * callback still fires exactly once, with "cancelled" as its error string.
 */
void palette_cancel(uint64_t handle);

/*
 * Free a string previously returned by palette-core.
 *
 * Safe to call with NULL. Frees a pointer obtained from palette_extract_sync
 * (by the caller, on the same thread) or from an async callback (on the worker
 * thread). Do not call it on any other pointer.
 */
void palette_free_string(char *s);

#ifdef __cplusplus
}
#endif

#endif /* PALETTE_CORE_H */
