package com.myeditor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ProjectTest {

    @Test
    fun testClipDurationAndEndMs() {
        val clip = Clip(
            uri = "content://test/video1",
            durationMs = 10000L,
            startMs = 2000L,
            trimStartMs = 1000L,
            trimEndMs = 6000L,
            track = 0
        )
        assertEquals(5000L, clip.trimmedMs)
        assertEquals(7000L, clip.endMs)
    }

    @Test
    fun testProjectTotalMsAndClipOnTrack() {
        val project = Project("Test Project")
        val clip1 = Clip("uri1", 5000L, startMs = 0L, track = 0)
        val clip2 = Clip("uri2", 4000L, startMs = 5000L, track = 0)
        val clip3 = Clip("uri3", 6000L, startMs = 1000L, track = 1)

        project.clips.addAll(listOf(clip1, clip2, clip3))

        assertEquals(9000L, project.totalMs())

        assertEquals(clip1, project.clipOnTrack(2500L, 0))
        assertEquals(clip2, project.clipOnTrack(6000L, 0))
        assertEquals(clip3, project.clipOnTrack(3000L, 1))

        assertNull(project.clipOnTrack(3000L, 2))
        assertNull(project.clipOnTrack(10000L, 0))
    }

    @Test
    fun testClipTrackAndTrimBoundary() {
        val clip = Clip(
            uri = "content://test/video2",
            durationMs = 15000L,
            startMs = 3000L,
            trimStartMs = 2000L,
            trimEndMs = 10000L,
            track = 1,
            opacity = 0.5f
        )
        assertEquals(1, clip.track)
        assertEquals(8000L, clip.trimmedMs)
        assertEquals(11000L, clip.endMs)
        assertEquals(0.5f, clip.opacity, 0.001f)
    }
}
