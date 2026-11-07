package com.hex.stenographer

import android.content.Context
import java.io.File

/**
 * Модели лежат в assets/models/ внутри APK и при первом запуске копируются во внутреннее
 * хранилище (sherpa-onnx и MediaPipe читают модели по пути к файлу). Интернет не используется.
 */
object ModelStore {
    // Обязательные
    const val ASR_MODEL = "asr/model.int8.onnx"
    const val ASR_TOKENS = "asr/tokens.txt"
    const val SEG_MODEL = "seg/model.int8.onnx"
    const val EMB_MODEL = "emb/model.onnx"
    // Необязательные (если файла нет — этап просто пропускается)
    const val LLM_MODEL = "llm/model.task"
    const val VAD_MODEL = "vad/silero_vad.onnx"
    const val DENOISE_MODEL = "denoise/gtcrn_simple.onnx"

    fun path(ctx: Context, rel: String) = File(ctx.filesDir, "models/$rel").absolutePath

    /** Модель доступна: уже скопирована или лежит в assets. */
    fun exists(ctx: Context, rel: String): Boolean {
        if (File(path(ctx, rel)).let { it.exists() && it.length() > 0 }) return true
        return try { ctx.assets.open("models/$rel").close(); true } catch (e: Exception) { false }
    }

    fun ensure(ctx: Context, onProgress: (String) -> Unit = {}) {
        copyDir(ctx, "models", File(ctx.filesDir, "models"), onProgress)
    }

    private fun copyDir(ctx: Context, assetDir: String, dst: File, onProgress: (String) -> Unit) {
        val list = ctx.assets.list(assetDir) ?: return
        for (name in list) {
            val child = "$assetDir/$name"
            val sub = ctx.assets.list(child)
            if (sub != null && sub.isNotEmpty()) {
                copyDir(ctx, child, File(dst, name), onProgress)
            } else {
                val out = File(dst, name)
                if (out.exists() && out.length() > 0) continue
                onProgress("Копирование модели $name…")
                out.parentFile!!.mkdirs()
                ctx.assets.open(child).use { i -> out.outputStream().use { o -> i.copyTo(o, 1 shl 20) } }
            }
        }
    }
}
