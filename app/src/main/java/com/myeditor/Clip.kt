package com.myeditor

const val MAX_TRACKS = 3

data class Clip(
    val uri: String,
    val durationMs: Long,
    val startMs: Long = 0L,
    val trimStartMs: Long = 0L,
    val trimEndMs: Long = durationMs,
    val track: Int = 0,
    val opacity: Float = 1f
) {
    val trimmedMs: Long get() = trimEndMs - trimStartMs
    val endMs: Long get() = startMs + trimmedMs
}