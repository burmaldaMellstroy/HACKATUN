package com.hex.stenographer

object Exporters {
    fun speakerNum(id: String) = id.substringAfter("_").toIntOrNull() ?: 1
    fun defaultName(id: String) = "Говорящий ${speakerNum(id)}"

    fun txt(items: List<Utterance>, nameOf: (String) -> String): String =
        items.joinToString("\n") { "[${fmtTime(it.startMs)}] ${nameOf(it.speakerId)}: ${it.text}" }

    fun srt(items: List<Utterance>, nameOf: (String) -> String): String =
        items.mapIndexed { i, u ->
            "${i + 1}\n${ts(u.startMs)} --> ${ts(u.endMs)}\n${nameOf(u.speakerId)}: ${u.text}\n"
        }.joinToString("\n")

    private fun ts(ms: Int): String {
        val h = ms / 3_600_000
        val m = ms / 60_000 % 60
        val s = ms / 1000 % 60
        val x = ms % 1000
        return "%02d:%02d:%02d,%03d".format(h, m, s, x)
    }

    /** Склеивает подряд идущие реплики одного говорящего (пауза меньше gapMs). */
    fun mergeAdjacent(items: List<Utterance>, gapMs: Int = 700): List<Utterance> {
        val out = ArrayList<Utterance>()
        for (u in items) {
            val last = out.lastOrNull()
            if (last != null && last.speakerId == u.speakerId && u.startMs - last.endMs < gapMs)
                out[out.size - 1] = last.copy(endMs = u.endMs, text = last.text + " " + u.text)
            else out += u
        }
        return out
    }
}
