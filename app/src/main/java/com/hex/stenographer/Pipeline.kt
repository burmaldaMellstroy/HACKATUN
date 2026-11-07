package com.hex.stenographer

import android.content.Context
import com.k2fsa.sherpa.onnx.*
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

data class Settings(
    val useLlm: Boolean = true,
    val denoise: Boolean = false,
    val useVad: Boolean = true,
    val numSpeakers: Int = -1,       // -1 = автоопределение
    val threshold: Float = 0.65f,    // порог кластеризации (меньше -> больше спикеров)
    val threads: Int = 4,
    val mergeMinor: Boolean = true   // присоединять «лишних» малозаметных говорящих к основным
)

/**
 * Этапы: предобработка -> (шумоподавление GTCRN) -> (VAD silero) -> диаризация ->
 * уточнение границ по VAD -> ASR по сегментам -> склейка -> (LLM-пунктуация).
 * Необязательные этапы включаются только если соответствующая модель лежит в assets.
 * Имена конфигов sherpa-onnx сверяйте с kotlin-api вашей версии.
 */
class Pipeline(private val ctx: Context) {

    class Result(val utterances: List<Utterance>, val audioSec: Double, val procSec: Double, val samples: FloatArray) {
        val rtf get() = procSec / audioSec
    }

    private data class Seg(val start: Float, val end: Float, val speaker: Int)

    fun run(raw: FloatArray, st: Settings, progress: (String, Float) -> Unit): Result {
        val t0 = System.nanoTime()

        progress("Подготовка звука…", 0.02f)
        var samples = AudioProc.prepare(raw)

        if (st.denoise && ModelStore.exists(ctx, ModelStore.DENOISE_MODEL)) {
            progress("Шумоподавление…", 0.05f)
            samples = AudioProc.normalize(denoise(samples))
        }
        val audioSec = samples.size / 16000.0

        var regions: List<Pair<Int, Int>>? = null
        if (st.useVad && ModelStore.exists(ctx, ModelStore.VAD_MODEL)) {
            progress("Поиск речи…", 0.15f)
            regions = try { vadRegions(samples) } catch (e: Throwable) { null }
        }

        progress("Диаризация…", 0.25f)
        var segs = diarize(samples, st)
        if (regions != null) segs = refine(segs, regions)
        if (st.mergeMinor && st.numSpeakers == -1) segs = mergeMinorSpeakers(segs)
        // Нумерация спикеров по порядку появления: speaker_1 — тот, кто заговорил первым
        val order = LinkedHashMap<Int, Int>()
        segs.forEach { if (it.speaker !in order) order[it.speaker] = order.size + 1 }

        val raws = ArrayList<Utterance>()
        val rec = createRecognizer(st.threads)
        segs.forEachIndexed { i, s ->
            progress("Распознавание ${i + 1}/${segs.size}", 0.35f + 0.45f * (i + 1) / segs.size)
            var a = s.start
            while (a < s.end) {
                val b = minOf(a + 20f, s.end)
                val text = decode(rec, samples, a, b)
                if (text.isNotEmpty())
                    raws += Utterance("speaker_${order[s.speaker]}", (a * 1000).toInt(), (b * 1000).toInt(), text)
                a = b
            }
        }
        rec.release()

        // Склейка соседних реплик одного спикера (пауза < 700 мс)
        val merged = ArrayList<Utterance>()
        for (u in raws) {
            val last = merged.lastOrNull()
            if (last != null && last.speakerId == u.speakerId && u.startMs - last.endMs < 700)
                merged[merged.size - 1] = last.copy(endMs = u.endMs, text = last.text + " " + u.text)
            else merged += u
        }

        val result = if (st.useLlm && ModelStore.exists(ctx, ModelStore.LLM_MODEL)) {
            try {
                LlmPostProcessor(ctx).use { llm ->
                    merged.mapIndexed { i, u ->
                        progress("Пунктуация ${i + 1}/${merged.size}", 0.8f + 0.2f * (i + 1) / merged.size)
                        u.copy(text = llm.punctuate(u.text))
                    }
                }
            } catch (e: Throwable) { merged }
        } else merged

        progress("Готово", 1f)
        return Result(result, audioSec, (System.nanoTime() - t0) / 1e9, samples)
    }

    /** Повторное распознавание одного фрагмента (для правки/перезапуска). */
    fun reRecognize(samples: FloatArray, startMs: Int, endMs: Int, threads: Int): String {
        val rec = createRecognizer(threads)
        val sb = StringBuilder()
        var a = startMs / 1000f
        val end = endMs / 1000f
        while (a < end) {
            val b = minOf(a + 20f, end)
            val t = decode(rec, samples, a, b)
            if (t.isNotEmpty()) { if (sb.isNotEmpty()) sb.append(' '); sb.append(t) }
            a = b
        }
        rec.release()
        return sb.toString()
    }

    // ---------- внутренние этапы ----------

    private fun createRecognizer(threads: Int) = OfflineRecognizer(null, OfflineRecognizerConfig(
        featConfig = FeatureConfig(sampleRate = 16000, featureDim = 64),
        modelConfig = OfflineModelConfig(
            nemo = OfflineNemoEncDecCtcModelConfig(model = ModelStore.path(ctx, ModelStore.ASR_MODEL)),
            tokens = ModelStore.path(ctx, ModelStore.ASR_TOKENS),
            numThreads = threads,
            modelType = "nemo_ctc"
        )
    ))

    private fun decode(rec: OfflineRecognizer, samples: FloatArray, a: Float, b: Float): String {
        val from = (a * 16000).toInt().coerceIn(0, samples.size)
        val to = (b * 16000).toInt().coerceIn(from, samples.size)
        if (to - from < 1600) return ""
        val st = rec.createStream()
        st.acceptWaveform(samples.copyOfRange(from, to), 16000)
        rec.decode(st)
        val text = rec.getResult(st).text.trim()
        st.release()
        return text
    }

    private fun diarize(samples: FloatArray, s: Settings): List<Seg> {
        val sd = OfflineSpeakerDiarization(null, OfflineSpeakerDiarizationConfig(
            segmentation = OfflineSpeakerSegmentationModelConfig(
                pyannote = OfflineSpeakerSegmentationPyannoteModelConfig(
                    model = ModelStore.path(ctx, ModelStore.SEG_MODEL)),
                numThreads = 2),
            embedding = SpeakerEmbeddingExtractorConfig(
                model = ModelStore.path(ctx, ModelStore.EMB_MODEL), numThreads = 2),
            clustering = FastClusteringConfig(numClusters = s.numSpeakers, threshold = s.threshold),
            minDurationOn = 0.1f,   // короткие реплики («да», «угу»)
            minDurationOff = 0.4f
        ))
        val r = sd.process(samples).map { Seg(it.start, it.end, it.speaker) }
        sd.release()
        return r
    }

    private fun denoise(x: FloatArray): FloatArray = try {
        val den = OfflineSpeechDenoiser(null, OfflineSpeechDenoiserConfig(
            model = OfflineSpeechDenoiserModelConfig(
                gtcrn = OfflineSpeechDenoiserGtcrnModelConfig(model = ModelStore.path(ctx, ModelStore.DENOISE_MODEL)),
                numThreads = 2)))
        val out = den.run(x, 16000).samples
        den.release()
        out
    } catch (e: Throwable) { x }

    /** Речевые участки по Silero VAD, в отсчётах. */
    private fun vadRegions(samples: FloatArray): List<Pair<Int, Int>> {
        val vad = Vad(null, VadModelConfig(
            sileroVadModelConfig = SileroVadModelConfig(
                model = ModelStore.path(ctx, ModelStore.VAD_MODEL),
                threshold = 0.5f, minSilenceDuration = 0.25f, minSpeechDuration = 0.15f, windowSize = 512),
            sampleRate = 16000, numThreads = 1))
        val out = ArrayList<Pair<Int, Int>>()
        fun drain() {
            while (!vad.empty()) {
                val seg = vad.front()
                val from: Int = seg.start.toInt()
                val to: Int = from + seg.samples.size
                out.add(Pair(from, to))
                vad.pop()
            }
        }
        var i = 0
        while (i + 512 <= samples.size) {
            vad.acceptWaveform(samples.copyOfRange(i, i + 512)); drain(); i += 512
        }
        vad.flush(); drain(); vad.release()
        return out
    }

    /** Убираем сегменты без речи и подрезаем границы по VAD (точнее временные метки). */
    private fun refine(segs: List<Seg>, regions: List<Pair<Int, Int>>): List<Seg> {
        val pad = 0.05f
        return segs.mapNotNull { s ->
            val ov = regions.filter { it.second / 16000f > s.start && it.first / 16000f < s.end }
            if (ov.isEmpty()) null
            else Seg(
                maxOf(s.start, ov.minOf { it.first } / 16000f - pad),
                minOf(s.end, ov.maxOf { it.second } / 16000f + pad),
                s.speaker
            )
        }.filter { it.end - it.start > 0.1f }
    }

    /**
     * Борьба с «лишними» говорящими: кластеры, у которых речи меньше 3 с или меньше 8% от всей,
     * считаются ложными и отдаются ближайшему по времени основному говорящему.
     */
    private fun mergeMinorSpeakers(segs: List<Seg>): List<Seg> {
        if (segs.isEmpty()) return segs
        val dur = HashMap<Int, Double>()
        segs.forEach { dur[it.speaker] = (dur[it.speaker] ?: 0.0) + (it.end - it.start) }
        val total = dur.values.sum()
        val limit = max(3.0, total * 0.08)
        val minor = dur.filter { it.value < limit }.keys
        if (minor.isEmpty() || minor.size == dur.size) return segs
        val major = segs.filter { it.speaker !in minor }
        return segs.map { s ->
            if (s.speaker !in minor) s
            else {
                val mid = (s.start + s.end) / 2f
                val nearest = major.minByOrNull { m ->
                    if (mid >= m.start && mid <= m.end) 0f else min(abs(m.start - mid), abs(m.end - mid))
                }!!
                s.copy(speaker = nearest.speaker)
            }
        }
    }
}
