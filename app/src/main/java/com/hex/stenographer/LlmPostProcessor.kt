package com.hex.stenographer

import android.content.Context
import com.google.mediapipe.tasks.genai.llminference.LlmInference

/** Локальная LLM: расстановка пунктуации и заглавных букв. Слова менять запрещено. */
class LlmPostProcessor(ctx: Context) : AutoCloseable {
    private val llm: LlmInference = LlmInference.createFromOptions(
        ctx,
        LlmInference.LlmInferenceOptions.builder()
            .setModelPath(ModelStore.path(ctx, ModelStore.LLM_MODEL))
            .setMaxTokens(768)
            .build()
    )

    fun punctuate(text: String): String {
        if (text.isBlank()) return text
        val prompt = "<start_of_turn>user\n" +
            "Расставь знаки препинания и заглавные буквы в русском тексте. " +
            "Не меняй, не удаляй и не добавляй слова. Выведи только исправленный текст.\n\n" +
            text + "<end_of_turn>\n<start_of_turn>model\n"
        val out = try { llm.generateResponse(prompt).trim() } catch (e: Exception) { return text }
        // Страховка: если LLM изменила набор слов — оставляем оригинал, чтобы не испортить WER.
        return if (sameWords(text, out)) out else text
    }

    private fun norm(s: String) = s.lowercase().replace(Regex("[^\\p{L}\\p{N}\\s]"), " ")
        .split(Regex("\\s+")).filter { it.isNotEmpty() }
    private fun sameWords(a: String, b: String) = norm(a) == norm(b)

    override fun close() = llm.close()
}
