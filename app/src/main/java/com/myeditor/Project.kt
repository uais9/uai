package com.myeditor

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class Project(var name: String = "Untitled") {
    val clips = mutableListOf<Clip>()

    fun totalMs(): Long = clips.sumOf { it.trimmedMs }

    fun toJson(): String {
        val arr = JSONArray()
        for (c in clips) {
            arr.put(JSONObject().apply {
                put("uri", c.uri)
                put("durationMs", c.durationMs)
                put("trimStartMs", c.trimStartMs)
                put("trimEndMs", c.trimEndMs)
            })
        }
        return JSONObject().apply {
            put("version", 1)
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
                for (i in 0 until arr.length()) {
                    val c = arr.getJSONObject(i)
                    p.clips.add(
                        Clip(
                            c.getString("uri"),
                            c.getLong("durationMs"),
                            c.getLong("trimStartMs"),
                            c.getLong("trimEndMs")
                        )
                    )
                }
            } catch (e: Exception) {
            }
            return p
        }
    }
}