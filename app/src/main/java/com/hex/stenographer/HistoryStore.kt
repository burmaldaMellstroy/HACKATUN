package com.hex.stenographer

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class HistoryEntry(val id: String, val title: String, val created: Long, val count: Int, val durationSec: Int)

class LoadedTranscript(val title: String, val items: List<Utterance>, val names: Map<String, String>)

/** Локальная история расшифровок: <id>.json (текст, имена) + <id>.wav (звук для воспроизведения). */
object HistoryStore {
    private fun dir(ctx: Context) = File(ctx.filesDir, "history").also { it.mkdirs() }
    fun wavFile(ctx: Context, id: String) = File(dir(ctx), "$id.wav")
    private fun jsonFile(ctx: Context, id: String) = File(dir(ctx), "$id.json")

    fun save(ctx: Context, title: String, items: List<Utterance>, names: Map<String, String>, samples: FloatArray): String {
        val id = System.currentTimeMillis().toString()
        WavIO.write(wavFile(ctx, id), samples)
        writeJson(ctx, id, title, id.toLong(), items, names, samples.size / 16000)
        return id
    }

    fun update(ctx: Context, id: String, items: List<Utterance>, names: Map<String, String>) {
        val f = jsonFile(ctx, id)
        if (!f.exists()) return
        val old = JSONObject(f.readText())
        writeJson(ctx, id, old.getString("title"), old.getLong("created"), items, names, old.optInt("duration_sec"))
    }

    private fun writeJson(ctx: Context, id: String, title: String, created: Long,
                          items: List<Utterance>, names: Map<String, String>, durSec: Int) {
        val arr = JSONArray()
        items.forEach {
            arr.put(JSONObject().put("speaker_id", it.speakerId).put("start_ms", it.startMs)
                .put("end_ms", it.endMs).put("text", it.text))
        }
        val nm = JSONObject()
        names.forEach { (k, v) -> nm.put(k, v) }
        val j = JSONObject().put("title", title).put("created", created)
            .put("duration_sec", durSec).put("names", nm).put("transcript", arr)
        jsonFile(ctx, id).writeText(j.toString(), Charsets.UTF_8)
    }

    fun list(ctx: Context): List<HistoryEntry> =
        (dir(ctx).listFiles() ?: emptyArray<File>())
            .filter { it.extension == "json" }
            .mapNotNull { f ->
                try {
                    val j = JSONObject(f.readText())
                    HistoryEntry(f.nameWithoutExtension, j.getString("title"), j.getLong("created"),
                        j.getJSONArray("transcript").length(), j.optInt("duration_sec"))
                } catch (e: Exception) { null }
            }
            .sortedByDescending { it.created }

    fun load(ctx: Context, id: String): LoadedTranscript? = try {
        val j = JSONObject(jsonFile(ctx, id).readText())
        val arr = j.getJSONArray("transcript")
        val items = (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            Utterance(o.getString("speaker_id"), o.getInt("start_ms"), o.getInt("end_ms"), o.getString("text"))
        }
        val nm = j.optJSONObject("names")
        val names = HashMap<String, String>()
        if (nm != null) { val keys = nm.keys(); while (keys.hasNext()) { val k = keys.next(); names[k] = nm.getString(k) } }
        LoadedTranscript(j.getString("title"), items, names)
    } catch (e: Exception) { null }

    fun delete(ctx: Context, id: String) {
        jsonFile(ctx, id).delete()
        wavFile(ctx, id).delete()
    }
}
