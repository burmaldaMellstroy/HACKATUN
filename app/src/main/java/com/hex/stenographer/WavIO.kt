package com.hex.stenographer

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Простое чтение/запись WAV (PCM16, моно). */
object WavIO {
    fun write(f: File, x: FloatArray, sr: Int = 16000) {
        val data = x.size * 2
        val bb = ByteBuffer.allocate(44 + data).order(ByteOrder.LITTLE_ENDIAN)
        bb.put("RIFF".toByteArray()); bb.putInt(36 + data)
        bb.put("WAVE".toByteArray()); bb.put("fmt ".toByteArray())
        bb.putInt(16); bb.putShort(1.toShort()); bb.putShort(1.toShort())
        bb.putInt(sr); bb.putInt(sr * 2); bb.putShort(2.toShort()); bb.putShort(16.toShort())
        bb.put("data".toByteArray()); bb.putInt(data)
        for (v in x) bb.putShort((v.coerceIn(-1f, 1f) * 32767f).toInt().toShort())
        f.parentFile?.mkdirs()
        f.writeBytes(bb.array())
    }

    fun read(f: File): FloatArray {
        val b = f.readBytes()
        if (b.size <= 44) return FloatArray(0)
        val n = (b.size - 44) / 2
        val sb = ByteBuffer.wrap(b, 44, b.size - 44).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        return FloatArray(n) { sb.get(it) / 32768f }
    }
}
