package com.editor.veyro.core.model.project

/** Whether a project's source file can still be used, as checked when the project is opened. */
public enum class MediaHealth {
    AVAILABLE,

    /** The file is gone, or read access was revoked or expired. */
    MISSING,

    /**
     * The URI still opens, but its size differs from import time, so it is
     * probably a different file. Clip timings may no longer match the
     * footage.
     */
    CHANGED,
}
