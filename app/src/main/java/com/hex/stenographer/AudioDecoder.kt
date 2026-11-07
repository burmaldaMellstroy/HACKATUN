package com.hex.stenographer

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.io.ByteArrayOutputStream
import java.nio.ByteOrder

/** Любой аудиоформат (mp3/m4a/wav/ogg/...) -> FloatArray, 16 кГц, моно, [-1..1]. */
object AudioDecoder {
    const val TARGET_SR = 16000

    fun decode(ctx: Context, uri: Uri): FloatArray {
        val ex = MediaExtractor()
        ctx.contentResolver.openFileDescriptor(uri, "r")!!.use { ex.setDataSource(it.fileDescriptor) }
        val idx = (0 until ex.trackCount).first {
            ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)!!.startsWith("audio/")
        }
        ex.selectTrack(idx)
        val fmt = ex.getTrackFormat(idx)
        val mime = fmt.getString(MediaFormat.KEY_MIME)!!
        var sr = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        var ch = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)

        val codec = MediaCodec.createDecoderByType(mime)
        codec.configure(fmt, null, null, 0)
        codec.start()

        val pcm = ByteArrayOutputStream()
        val info = MediaCodec.BufferInfo()
        var inDone = false
        var outDone = false
        while (!outDone) {
            if (!inDone) {
                val i = codec.dequeueInputBuffer(10_000)
                if (i >= 0) {
                    val buf = codec.getInputBuffer(i)!!
                    val n = ex.readSampleData(buf, 0)
                    if (n < 0) {
                        codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inDone = true
                    } else {
                        codec.queueInputBuffer(i, 0, n, ex.sampleTime, 0)
                        ex.advance()
                    }
                }
            }
            val o = codec.dequeueOutputBuffer(info, 10_000)
            when {
                o >= 0 -> {
                    val out = codec.getOutputBuffer(o)!!
                    val bytes = ByteArray(info.size)
                    out.get(bytes); out.clear()
                    pcm.write(bytes)
                    codec.releaseOutputBuffer(o, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outDone = true
                }
                o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val nf = codec.outputFormat
                    sr = nf.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    ch = nf.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                }
            }
        }
        codec.stop(); codec.release(); ex.release()

        val sb = java.nio.ByteBuffer.wrap(pcm.toByteArray()).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        val frames = sb.remaining() / ch
        val mono = FloatArray(frames)
        for (f in 0 until frames) {
            var acc = 0f
            for (c in 0 until ch) acc += sb.get(f * ch + c) / 32768f
            mono[f] = acc / ch
        }
        return resample(mono, sr, TARGET_SR)
    }

    /** Линейная интерполяция (для речи достаточно). */
    private fun resample(x: FloatArray, from: Int, to: Int): FloatArray {
        if (from == to) return x
        val n = (x.size.toLong() * to / from).toInt()
        val y = FloatArray(n)
        val ratio = from.toDouble() / to
        for (i in 0 until n) {
            val p = i * ratio
            val i0 = p.toInt().coerceAtMost(x.size - 1)
            val i1 = (i0 + 1).coerceAtMost(x.size - 1)
            val fr = (p - i0).toFloat()
            y[i] = x[i0] * (1 - fr) + x[i1] * fr
        }
        return y
    }
}
