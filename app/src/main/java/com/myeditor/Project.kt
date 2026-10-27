package com.myeditor

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class Project(var name: String = "Untitled") {
    val clips = mutableListOf<Clip>()

    fun totalMs(): Long = clips.maxOfOrNull { it.endMs } ?: 0L

    fun clipAt(t: Long): Clip? = clips.firstOrNull { t >= it.startMs && t < it.endMs }

    fun toJson(): String {
        val arr = JSONArray()
        for (c in clips) {
            arr.put(JSONObject().apply {
                put("uri", c.uri)
                put("durationMs", c.durationMs)
                put("startMs", c.startMs)
                put("trimStartMs", c.trimStartMs)
                put("trimEndMs", c.trimEndMs)
            })
        }
        return JSONObject().apply {
            put("version", 2)
            put("name", name)
            put("clips", arr)
        }.toString()
    }

    fun save(file: File) {
        file.writeText(toJson())
    }

    companion object {
        fun load(file: File): Project {
            val p = Project()
            if (!file.exists()) return p
            try {
                val o = JSONObject(file.readText())
                p.name = o.optString("name", "Untitled")
                val arr = o.optJSONArray("clips") ?: JSONArray()
                var cursor = 0L
                for (i in 0 until arr.length()) {
                    val c = arr.getJSONObject(i)
                    val start = if (c.has("startMs")) c.getLong("startMs") else cursor
                    val clip = Clip(
                        c.getString("uri"),
                        c.getLong("durationMs"),
                        start,
                        c.getLong("trimStartMs"),
                        c.getLong("trimEndMs")
                    )
                    p.clips.add(clip)
                    cursor = clip.endMs
                }
            } catch (e: Exception) {
            }
            return p
        }
    }
}