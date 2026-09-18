package com.esc.irminsul.capture

/** Why a capture call could not do its job. */
sealed interface CaptureError {
    /** `libirminsul.so` is not installed for this ABI, or was not loaded. */
    data object NativeUnavailable : CaptureError

    /** The library loaded but the sniffer could not be created. */
    data class SnifferInitFailed(val code: Int) : CaptureError

    /** No settings screen for that permission could be opened on this device. */
    data object NoSettingsPage : CaptureError

    /** Native declined the export; the reason went to [IrminsulCapture.logs]. */
    data object NativeRefused : CaptureError

    /** The packet's cached body is gone — the native cache evicted it. */
    data object PayloadUnavailable : CaptureError
}

/**
 * The module's single outcome type. Failure detail that needs a message lives
 * in [IrminsulCapture.logs] rather than here: the native layer reports errors
 * as it has always done, and duplicating them into two channels would let them
 * disagree.
 */
sealed interface CaptureResult<out T> {
    data class Ok<T>(val value: T) : CaptureResult<T>
    data class Err(val error: CaptureError) : CaptureResult<Nothing>
}
