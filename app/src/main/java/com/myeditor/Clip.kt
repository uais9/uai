package com.myeditor

data class Clip(
    val uri: String,
    val durationMs: Long,
    val startMs: Long = 0L,
    val trimStartMs: Long = 0L,
    val trimEndMs: Long = durationMs
) {
    val trimmedMs: Long get() = trimEndMs - trimStartMs
    val endMs: Long get() = startMs + trimmedMs
}