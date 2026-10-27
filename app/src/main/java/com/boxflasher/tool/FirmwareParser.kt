package com.boxflasher.tool

import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import java.util.Locale

/**
 * 解析 CPBox 的 update.img 固件。
 *
 * 固件格式（实测确认）：
 *   [0..45)    : 32 字节小写 md5 hex + "  update.swu\n"
 *   [45..end)  : cpio crc 格式归档，包含 sw-description / customer / kernel
 */
class FirmwareParser(private val file: File) {

    companion object {
        const val SWU_HEADER_SIZE = 45
        const val SWU_SUFFIX = "  update.swu\n"
        const val CPIO_MAGIC_CRC = "070702"
        const val MAGIC_VALUE = "0xAA55A55A"
        const val PROJECT_NAME = "ly2872"
    }

    data class FirmwareInfo(
        val path: String,
        val fileSize: Long,
        val wholeMd5: String,
        val headerMd5: String,
        val contentMd5: String,
        val headerMd5MatchesContent: Boolean,
        val cpioMagic: String?,
        val cpioEntries: List<CpioEntry>,
        val swDescription: String?,
        val swDescriptionText: String?,
        val hasMagic: Boolean,
        val hasProjectName: Boolean,
        val customerSquashfs: Boolean,
        val kernelAndroidBoot: Boolean,
    )

    data class CpioEntry(
        val name: String,
        val mode: Long,
        val fileSize: Long,
        val dataOffset: Int,
        val headerOffset: Int,
    )

    data class ParseResult(
        val info: FirmwareInfo,
        val errors: List<String>,
    )

    fun parse(): ParseResult {
        val errors = mutableListOf<String>()
        val bytes = try {
            file.readBytes()
        } catch (e: Exception) {
            errors.add("cannot read file: ${e.message}")
            return ParseResult(emptyInfo(errors), errors)
        }

        if (bytes.size < SWU_HEADER_SIZE) {
            errors.add("file too small: ${bytes.size} bytes")
            return ParseResult(emptyInfo(errors), errors)
        }

        val headerMd5 = String(bytes, 0, 32, Charsets.US_ASCII)
        val suffix = String(bytes, 32, SWU_HEADER_SIZE - 32, Charsets.US_ASCII)
        val cpioBytes = bytes.copyOfRange(SWU_HEADER_SIZE, bytes.size)

        val wholeMd5 = md5(bytes)
        val contentMd5 = md5(cpioBytes)

        if (suffix != SWU_SUFFIX) {
            errors.add("swu header suffix mismatch: '${suffix.escape()}'")
        }

        val cpio = parseCpio(cpioBytes, errors)
        val swEntry = cpio.entries.firstOrNull { it.name == "sw-description" }
        val swText = swEntry?.let {
            val data = cpioBytes.copyOfRange(it.dataOffset, it.dataOffset + it.fileSize.toInt())
            String(data, Charsets.UTF_8)
        }

        val customerEntry = cpio.entries.firstOrNull { it.name == "customer" }
        val kernelEntry = cpio.entries.firstOrNull { it.name == "kernel" }

        val customerSquashfs = customerEntry?.let {
            readMagic(cpioBytes, it, 4) == "hsqs"
        } ?: false

        val kernelAndroidBoot = kernelEntry?.let {
            readMagic(cpioBytes, it, 8) == "ANDROID!"
        } ?: false

        val info = FirmwareInfo(
            path = file.absolutePath,
            fileSize = file.length(),
            wholeMd5 = wholeMd5,
            headerMd5 = headerMd5,
            contentMd5 = contentMd5,
            headerMd5MatchesContent = headerMd5.equals(contentMd5, ignoreCase = true),
            cpioMagic = cpio.magic,
            cpioEntries = cpio.entries,
            swDescription = swEntry?.name,
            swDescriptionText = swText,
            hasMagic = swText?.contains(MAGIC_VALUE) == true,
            hasProjectName = swText?.contains(PROJECT_NAME) == true,
            customerSquashfs = customerSquashfs,
            kernelAndroidBoot = kernelAndroidBoot,
        )

        return ParseResult(info, errors)
    }

    private fun emptyInfo(errors: List<String>): FirmwareInfo {
        return FirmwareInfo(
            path = file.absolutePath,
            fileSize = file.length(),
            wholeMd5 = "",
            headerMd5 = "",
            contentMd5 = "",
            headerMd5MatchesContent = false,
            cpioMagic = null,
            cpioEntries = emptyList(),
            swDescription = null,
            swDescriptionText = null,
            hasMagic = false,
            hasProjectName = false,
            customerSquashfs = false,
            kernelAndroidBoot = false,
        )
    }

    private data class CpioResult(val magic: String?, val entries: List<CpioEntry>)

    private fun parseCpio(data: ByteArray, errors: MutableList<String>): CpioResult {
        if (data.size < 6) {
            errors.add("cpio data too small")
            return CpioResult(null, emptyList())
        }
        val magic = String(data, 0, 6, Charsets.US_ASCII)
        if (magic != CPIO_MAGIC_CRC) {
            errors.add("cpio magic is not crc (070702): '${magic.escape()}'")
        }

        val entries = mutableListOf<CpioEntry>()
        var off = 0
        var guard = 0
        while (off + 110 <= data.size && guard < 64) {
            guard++
            val h = data.copyOfRange(off, off + 110)
            val nameMagic = String(h, 0, 6, Charsets.US_ASCII)
            if (nameMagic != CPIO_MAGIC_CRC) break

            val mode = parseHex(h, 14, 22)
            val fileSize = parseHex(h, 54, 62)
            val nameSize = parseHex(h, 94, 102)

            val nameStart = off + 110
            val nameEnd = nameStart + nameSize.toInt()
            if (nameEnd > data.size) break
            val name = String(data, nameStart, nameSize.toInt() - 1, Charsets.US_ASCII)

            val dataStart = align4(nameEnd)
            val dataEnd = dataStart + fileSize.toInt()
            if (dataEnd > data.size) break

            if (name == "TRAILER!!!") break

            entries.add(
                CpioEntry(
                    name = name,
                    mode = mode,
                    fileSize = fileSize,
                    dataOffset = dataStart,
                    headerOffset = off,
                )
            )

            off = align4(dataEnd)
        }

        return CpioResult(magic, entries)
    }

    private fun readMagic(data: ByteArray, entry: CpioEntry, len: Int): String? {
        val end = entry.dataOffset + entry.fileSize.toInt()
        if (entry.dataOffset + len > end || entry.dataOffset + len > data.size) return null
        return String(data, entry.dataOffset, len, Charsets.US_ASCII)
    }

    private fun parseHex(bytes: ByteArray, from: Int, to: Int): Long {
        var v = 0L
        for (i in from until to) {
            v = (v shl 4) or hexNibble(bytes[i].toInt())
        }
        return v
    }

    private fun hexNibble(b: Int): Long {
        return when (b) {
            in '0'.code..'9'.code -> (b - '0'.code).toLong()
            in 'a'.code..'f'.code -> (b - 'a'.code + 10).toLong()
            in 'A'.code..'F'.code -> (b - 'A'.code + 10).toLong()
            else -> 0
        }
    }

    private fun align4(v: Int): Int = (v + 3) and 3.inv()

    private fun md5(data: ByteArray): String {
        val md = MessageDigest.getInstance("MD5")
        val digest = md.digest(data)
        val sb = StringBuilder(32)
        for (b in digest) sb.append(String.format(Locale.US, "%02x", b))
        return sb.toString()
    }

    private fun String.escape(): String {
        return this.replace("\n", "\\n").replace("\r", "\\r")
    }

    fun md5OfFile(file: File): String {
        val md = MessageDigest.getInstance("MD5")
        FileInputStream(file).use { fis ->
            val buf = ByteArray(1024 * 64)
            var n: Int
            while (fis.read(buf).also { n = it } != -1) {
                if (n > 0) md.update(buf, 0, n)
            }
        }
        val digest = md.digest()
        val sb = StringBuilder(32)
        for (b in digest) sb.append(String.format(Locale.US, "%02x", b))
        return sb.toString()
    }
}
