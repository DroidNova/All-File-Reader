package com.droidnova.allfilereader.data.text

import android.content.ContentResolver
import android.net.Uri
import java.io.BufferedReader
import java.io.Closeable
import java.io.InputStreamReader
import java.io.PushbackInputStream
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

class BinaryTextException : Exception()
class UnsupportedTextEncodingException : Exception()
class TextFileTooLargeException : Exception()

/** A bounded, sequential decoding session. InputStreamReader preserves decoder state across chunks. */
class TextReaderSession private constructor(
    private val reader: BufferedReader
) : Closeable {
    private var decodedCharacters = 0L

    fun readChunk(): String? {
        val buffer = CharArray(CHUNK_CHARACTERS + 1)
        var count = 0
        while (count < CHUNK_CHARACTERS) {
            val read = reader.read(buffer, count, CHUNK_CHARACTERS - count)
            if (read < 0) break
            count += read
        }
        if (count == 0) return null
        if (Character.isHighSurrogate(buffer[count - 1]) || buffer[count - 1] == '\r') {
            val next = reader.read()
            if (next >= 0) buffer[count++] = next.toChar()
        }
        decodedCharacters += count
        if (decodedCharacters > MAX_DECODED_CHARACTERS) throw TextFileTooLargeException()
        return String(buffer, 0, count)
    }

    override fun close() = reader.close()

    companion object {
        const val CHUNK_CHARACTERS = 16 * 1024
        const val MAX_FILE_BYTES = 64L * 1024L * 1024L
        private const val MAX_DECODED_CHARACTERS = 64L * 1024L * 1024L
        private const val SAMPLE_SIZE = 4096

        fun open(contentResolver: ContentResolver, uri: Uri): TextReaderSession {
            val raw = contentResolver.openInputStream(uri) ?: throw java.io.FileNotFoundException()
            try {
                val input = PushbackInputStream(raw, SAMPLE_SIZE)
                val sample = ByteArray(SAMPLE_SIZE)
                val count = input.read(sample).coerceAtLeast(0)
                val (charset, bomLength) = when {
                    count >= 3 && sample[0] == 0xEF.toByte() && sample[1] == 0xBB.toByte() && sample[2] == 0xBF.toByte() -> StandardCharsets.UTF_8 to 3
                    count >= 2 && sample[0] == 0xFF.toByte() && sample[1] == 0xFE.toByte() -> StandardCharsets.UTF_16LE to 2
                    count >= 2 && sample[0] == 0xFE.toByte() && sample[1] == 0xFF.toByte() -> StandardCharsets.UTF_16BE to 2
                    else -> {
                        if (looksBinary(sample, count)) throw BinaryTextException()
                        StandardCharsets.UTF_8 to 0
                    }
                }
                if (count > bomLength) input.unread(sample, bomLength, count - bomLength)
                val decoder = charset.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                return TextReaderSession(BufferedReader(InputStreamReader(input, decoder), CHUNK_CHARACTERS))
            } catch (error: Exception) {
                raw.close()
                throw error
            }
        }

        private fun looksBinary(sample: ByteArray, count: Int): Boolean {
            if (count == 0) return false
            var controls = 0
            for (index in 0 until count) {
                val value = sample[index].toInt() and 0xFF
                if (value == 0) return true
                if (value < 0x20 && value != 0x09 && value != 0x0A && value != 0x0D && value != 0x0C && value != 0x08) controls++
            }
            return controls * 20 > count
        }
    }
}
