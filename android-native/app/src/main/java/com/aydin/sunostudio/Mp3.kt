package com.aydin.sunostudio

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import java.io.File
import java.io.RandomAccessFile

class ConvertResult(val uri: Uri, val name: String, val note: String)

/**
 * Converts any audio (and the audio track of any video) to MP3 using the FFmpeg binary that ships
 * inside the app (LAME encoder). If that is unavailable for any reason the file is decoded with the
 * phone's own codecs and saved as WAV, so the user never ends up with nothing.
 */
object Mp3 {
    fun convert(
        ctx: Context,
        src: Uri,
        displayName: String,
        kbps: Int,
        onProgress: (Float, String) -> Unit
    ): ConvertResult {
        val base = Store.safeName(displayName.substringBeforeLast('.', displayName), "audio")
        val ext = displayName.substringAfterLast('.', "bin").lowercase().take(5)
        val stamp = System.currentTimeMillis()
        val input = File(ctx.cacheDir, "in_$stamp.$ext")
        val outMp3 = File(ctx.cacheDir, "out_$stamp.mp3")
        val outWav = File(ctx.cacheDir, "out_$stamp.wav")
        try {
            onProgress(-1f, "در حال آماده‌سازی فایل…")
            val stream = ctx.contentResolver.openInputStream(src)
                ?: throw IllegalStateException("خواندن فایل انتخاب‌شده ممکن نشد.")
            stream.use { i -> input.outputStream().use { o -> i.copyTo(o) } }
            val durationMs = durationOf(input)

            onProgress(0f, "در حال تبدیل به MP3…")
            val err = runFfmpeg(ctx, input, outMp3, kbps, durationMs, onProgress)
            if (err == null && outMp3.length() > 1000L) {
                val name = "$base.mp3"
                onProgress(1f, "در حال ذخیره…")
                val uri = Store.publish(ctx, outMp3, name, Store.Kind.AUDIO)
                return ConvertResult(uri, name, "")
            }

            // Fallback: decode with Android codecs and save as WAV.
            onProgress(-1f, "رمزگذار MP3 در دسترس نبود؛ در حال ساخت نسخهٔ WAV…")
            try {
                decodeWav(input, outWav)
            } catch (e: Exception) {
                throw IllegalStateException(
                    "تبدیل ناموفق بود. " + (err ?: "") .takeLast(160) + "\n" + (e.message ?: "")
                )
            }
            val name = "$base.wav"
            val uri = Store.publish(ctx, outWav, name, Store.Kind.AUDIO)
            return ConvertResult(uri, name, "تبدیل به MP3 روی این دستگاه ممکن نشد؛ نسخهٔ WAV ذخیره شد.")
        } finally {
            input.delete()
            outMp3.delete()
            outWav.delete()
        }
    }

    private fun durationOf(f: File): Long {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(f.absolutePath)
            r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        } catch (_: Exception) {
            0L
        } finally {
            try {
                r.release()
            } catch (_: Exception) {
            }
        }
    }

    private fun findBinary(ctx: Context): File? {
        val nat = File(ctx.applicationInfo.nativeLibraryDir)
        val direct = File(nat, "libffmpeg.so")
        if (direct.exists()) return direct
        val any = nat.listFiles()?.firstOrNull { it.name.contains("ffmpeg") && !it.name.contains("zip") }
        if (any != null) return any
        return ctx.noBackupFilesDir.walkTopDown().maxDepth(8)
            .firstOrNull { it.isFile && it.name == "libffmpeg.so" }
    }

    private fun libraryPath(ctx: Context): String {
        val dirs = LinkedHashSet<String>()
        val base = ctx.noBackupFilesDir
        val avcodec = base.walkTopDown().maxDepth(8).firstOrNull { it.isFile && it.name == "libavcodec.so" }
        avcodec?.parentFile?.let { dirs.add(it.absolutePath) }
        File(base, "youtubedl-android/packages").listFiles()?.forEach { pkg ->
            val lib = File(pkg, "usr/lib")
            if (lib.isDirectory) dirs.add(lib.absolutePath)
        }
        dirs.add(ctx.applicationInfo.nativeLibraryDir)
        val existing = System.getenv("LD_LIBRARY_PATH")
        if (!existing.isNullOrBlank()) dirs.add(existing)
        return dirs.joinToString(":")
    }

    /** Returns null on success, otherwise a short error text. */
    private fun runFfmpeg(
        ctx: Context,
        input: File,
        output: File,
        kbps: Int,
        durationMs: Long,
        onProgress: (Float, String) -> Unit
    ): String? {
        val bin = findBinary(ctx) ?: return "FFmpeg پیدا نشد."
        val cmd = listOf(
            bin.absolutePath, "-y", "-hide_banner", "-nostdin",
            "-i", input.absolutePath,
            "-vn", "-map", "0:a:0", "-map_metadata", "0",
            "-c:a", "libmp3lame", "-b:a", "${kbps}k",
            "-id3v2_version", "3",
            output.absolutePath
        )
        return try {
            val pb = ProcessBuilder(cmd)
            pb.redirectErrorStream(true)
            pb.environment()["LD_LIBRARY_PATH"] = libraryPath(ctx)
            val proc = pb.start()
            val tail = StringBuilder()
            val timeRe = Regex("time=(\\d+):(\\d+):(\\d+(?:\\.\\d+)?)")
            var lastP = 0f
            proc.inputStream.bufferedReader().use { r ->
                val line = StringBuilder()
                while (true) {
                    val ch = r.read()
                    if (ch < 0) break
                    if (ch == '\r'.code || ch == '\n'.code) {
                        val s = line.toString()
                        line.setLength(0)
                        if (s.isBlank()) continue
                        tail.append(s).append('\n')
                        if (tail.length > 1500) tail.delete(0, tail.length - 1000)
                        val m = timeRe.find(s)
                        if (m != null && durationMs > 0) {
                            val sec = m.groupValues[1].toInt() * 3600 + m.groupValues[2].toInt() * 60 +
                                m.groupValues[3].toDouble()
                            val p = (sec * 1000.0 / durationMs).toFloat().coerceIn(0f, 0.99f)
                            if (p - lastP >= 0.01f) {
                                lastP = p
                                onProgress(p, "در حال تبدیل به MP3…")
                            }
                        }
                    } else {
                        line.append(ch.toChar())
                    }
                }
            }
            val code = proc.waitFor()
            if (code == 0) null else tail.toString().lines().lastOrNull { it.isNotBlank() } ?: "کد خطا $code"
        } catch (t: Throwable) {
            t.message ?: t.javaClass.simpleName
        }
    }

    // ---- WAV fallback (Android MediaCodec) ----------------------------------------------------

    private fun decodeWav(source: File, file: File) {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        var out: RandomAccessFile? = null
        try {
            extractor.setDataSource(source.absolutePath)
            var track = -1
            var fmt: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                if (f.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                    track = i
                    fmt = f
                    break
                }
            }
            if (track < 0 || fmt == null) throw IllegalStateException("ترک صوتی در فایل پیدا نشد.")
            extractor.selectTrack(track)
            val inputFormat: MediaFormat = fmt
            val c = MediaCodec.createDecoderByType(inputFormat.getString(MediaFormat.KEY_MIME)!!)
            codec = c
            c.configure(inputFormat, null, null, 0)
            c.start()
            val raf = RandomAccessFile(file, "rw")
            out = raf
            raf.setLength(0)
            raf.write(ByteArray(44))
            val info = MediaCodec.BufferInfo()
            var inputEnd = false
            var outputEnd = false
            var rate = inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var total = 0L
            while (!outputEnd) {
                if (!inputEnd) {
                    val ix = c.dequeueInputBuffer(10000)
                    if (ix >= 0) {
                        val b = c.getInputBuffer(ix)!!
                        val n = extractor.readSampleData(b, 0)
                        if (n < 0) {
                            c.queueInputBuffer(ix, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputEnd = true
                        } else {
                            c.queueInputBuffer(ix, 0, n, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val ox = c.dequeueOutputBuffer(info, 10000)
                if (ox == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    rate = c.outputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    channels = c.outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                } else if (ox >= 0) {
                    val b = c.getOutputBuffer(ox)
                    if (b != null && info.size > 0) {
                        b.position(info.offset)
                        b.limit(info.offset + info.size)
                        val of = c.outputFormat
                        val encoding = if (of.containsKey(MediaFormat.KEY_PCM_ENCODING))
                            of.getInteger(MediaFormat.KEY_PCM_ENCODING) else AudioFormat.ENCODING_PCM_16BIT
                        if (encoding == AudioFormat.ENCODING_PCM_FLOAT) {
                            val floats = b.slice().order(java.nio.ByteOrder.nativeOrder()).asFloatBuffer()
                            val pcm = java.nio.ByteBuffer.allocate(floats.remaining() * 2)
                                .order(java.nio.ByteOrder.LITTLE_ENDIAN)
                            while (floats.hasRemaining()) {
                                val sample = floats.get().coerceIn(-1f, 1f)
                                pcm.putShort((sample * (if (sample < 0f) 32768f else 32767f)).toInt().toShort())
                            }
                            raf.write(pcm.array())
                            total += pcm.position()
                        } else {
                            val bytes = ByteArray(b.remaining())
                            b.get(bytes)
                            raf.write(bytes)
                            total += bytes.size
                        }
                    }
                    outputEnd = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    c.releaseOutputBuffer(ox, false)
                }
            }
            if (total <= 0L) throw IllegalStateException("داده‌ی صوتی قابل تبدیل پیدا نشد.")
            if (total > 0xFFFFFFFFL - 36) throw IllegalStateException("فایل برای قالب WAV بیش از حد بزرگ است.")
            raf.seek(0)
            wavHeader(raf, total, rate, channels)
            raf.fd.sync()
        } finally {
            try {
                codec?.stop()
            } catch (_: Exception) {
            }
            try {
                codec?.release()
            } catch (_: Exception) {
            }
            extractor.release()
            try {
                out?.close()
            } catch (_: Exception) {
            }
        }
    }

    private fun wavHeader(f: RandomAccessFile, size: Long, rate: Int, channels: Int) {
        f.writeBytes("RIFF"); intLE(f, (size + 36).toInt()); f.writeBytes("WAVEfmt ")
        intLE(f, 16); shortLE(f, 1); shortLE(f, channels); intLE(f, rate)
        intLE(f, rate * channels * 2); shortLE(f, channels * 2); shortLE(f, 16)
        f.writeBytes("data"); intLE(f, size.toInt())
    }

    private fun intLE(f: RandomAccessFile, n: Int) {
        f.write(n and 255); f.write(n ushr 8 and 255); f.write(n ushr 16 and 255); f.write(n ushr 24 and 255)
    }

    private fun shortLE(f: RandomAccessFile, n: Int) {
        f.write(n and 255); f.write(n ushr 8 and 255)
    }
}
