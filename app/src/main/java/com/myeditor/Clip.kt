package com.myeditor

data class Clip(
    val uri: String,
    val durationMs: Long,
    val trimStartMs: Long = 0L,
    val trimEndMs: Long = durationMs
) {
    val trimmedMs: Long get() = trimEndMs - trimStartMs
}