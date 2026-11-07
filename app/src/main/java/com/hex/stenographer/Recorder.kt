package com.hex.stenographer

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.io.File
import java.io.RandomAccessFile

/** Запись с микрофона: 16 кГц, моно, PCM16. Перед start() должно быть выдано разрешение RECORD_AUDIO. */
class Recorder {
    class Recording(val samples: FloatArray, val file: File)

    private val sr = AudioDecoder.TARGET_SR
    private var rec: AudioRecord? = null
    private var thread: Thread? = null
    @Volatile private var active = false
    private val chunks = ArrayList<ShortArray>()

    @SuppressLint("MissingPermission")
    fun start() {
        val minBuf = AudioRecord.getMinBufferSize(sr, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val bufSize = maxOf(minBuf, sr) // не меньше ~0.5 с
        val r = AudioRecord(MediaRecorder.AudioSource.MIC, sr,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufSize)
        check(r.state == AudioRecord.STATE_INITIALIZED) { "Не удалось открыть микрофон" }
        chunks.clear()
        rec = r
        active = true
        r.startRecording()
        thread = Thread {
            val buf = ShortArray(sr / 5)
            while (active) {
                val n = r.read(buf, 0, buf.size)
                if (n > 0) synchronized(chunks) { chunks += buf.copyOf(n) }
            }
        }.also { it.start() }
    }

    /** Останавливает запись, сохраняет WAV в dir и возвращает samples для пайплайна. */
    fun stop(dir: File): Recording {
        active = false
        thread?.join()
        rec?.stop(); rec?.release(); rec = null

        val total = synchronized(chunks) { chunks.sumOf { it.size } }
        val pcm = ShortArray(total)
        var pos = 0
        synchronized(chunks) { for (c in chunks) { c.copyInto(pcm, pos); pos += c.size } }

        val file = File(dir, "recording_${System.currentTimeMillis()}.wav")
        writeWav(file, pcm)
        val floats = FloatArray(total) { pcm[it] / 32768f }
        return Recording(floats, file)
    }

    private fun writeWav(f: File, pcm: ShortArray) {
        RandomAccessFile(f, "rw").use { w ->
            val dataLen = pcm.size * 2
            fun i32(v: Int) = byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte())
            fun i16(v: Int) = byteArrayOf(v.toByte(), (v shr 8).toByte())
            w.write("RIFF".toByteArray()); w.write(i32(36 + dataLen))
            w.write("WAVE".toByteArray()); w.write("fmt ".toByteArray())
            w.write(i32(16)); w.write(i16(1)); w.write(i16(1))
            w.write(i32(sr)); w.write(i32(sr * 2)); w.write(i16(2)); w.write(i16(16))
            w.write("data".toByteArray()); w.write(i32(dataLen))
            val bb = java.nio.ByteBuffer.allocate(dataLen).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            pcm.forEach { bb.putShort(it) }
            w.write(bb.array())
        }
    }
}
