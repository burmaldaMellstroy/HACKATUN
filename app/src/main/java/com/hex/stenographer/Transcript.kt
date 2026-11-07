package com.hex.stenographer

import org.json.JSONArray
import org.json.JSONObject

data class Utterance(val speakerId: String, val startMs: Int, val endMs: Int, val text: String)

object TranscriptJson {
    /** Формат строго по ТЗ: {"transcript":[{speaker_id,start_ms,end_ms,text}]} */
    fun toJson(list: List<Utterance>): String {
        val arr = JSONArray()
        list.forEach {
            arr.put(JSONObject().apply {
                put("speaker_id", it.speakerId)
                put("start_ms", it.startMs)
                put("end_ms", it.endMs)
                put("text", it.text)
            })
        }
        return JSONObject().put("transcript", arr).toString(2)
    }
}

fun fmtTime(ms: Int): String {
    val s = ms / 1000
    return "%02d:%02d".format(s / 60, s % 60)
}
