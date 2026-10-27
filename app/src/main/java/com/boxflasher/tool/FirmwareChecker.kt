package com.boxflasher.tool

import java.io.File

/**
 * 固件可刷入性检测。
 */
class FirmwareChecker(private val parser: FirmwareParser) {

    data class CheckItem(
        val name: String,
        val nameEn: String,
        val passed: Boolean,
        val detail: String,
        val detailEn: String,
    )

    data class CheckReport(
        val items: List<CheckItem>,
        val passCount: Int,
        val failCount: Int,
        val allPassed: Boolean,
    )

    fun check(file: File): CheckReport {
        val result = parser.parse()
        val info = result.info
        val items = mutableListOf<CheckItem>()

        val suffixOk = run {
            if (file.length() < FirmwareParser.SWU_HEADER_SIZE) false
            else {
                val bytes = file.readBytes()
                val suffix = String(bytes, 32, FirmwareParser.SWU_HEADER_SIZE - 32, Charsets.US_ASCII)
                suffix == FirmwareParser.SWU_SUFFIX
            }
        }
        items.add(
            CheckItem(
                "swu 头后缀", "SWU header suffix",
                suffixOk,
                if (suffixOk) "格式正确" else "应为 \"  update.swu\\n\"",
                if (suffixOk) "correct" else "expected \"  update.swu\\n\"",
            )
        )

        items.add(
            CheckItem(
                "头部 MD5 匹配", "Header MD5 match",
                info.headerMd5MatchesContent,
                "头=${info.headerMd5} 内容=${info.contentMd5}",
                "head=${info.headerMd5} content=${info.contentMd5}",
            )
        )

        items.add(
            CheckItem(
                "CPIO 格式", "CPIO format",
                info.cpioMagic == FirmwareParser.CPIO_MAGIC_CRC,
                info.cpioMagic ?: "无法识别",
                info.cpioMagic ?: "unknown",
            )
        )

        val firstIsSw = info.cpioEntries.firstOrNull()?.name == "sw-description"
        items.add(
            CheckItem(
                "sw-description 位置", "sw-description order",
                firstIsSw,
                if (firstIsSw) "位于首位" else "首位=${info.cpioEntries.firstOrNull()?.name ?: "无"}",
                if (firstIsSw) "first" else "first=${info.cpioEntries.firstOrNull()?.name ?: "none"}",
            )
        )

        val permOk = info.cpioEntries.filter { it.name != "TRAILER!!!" }.all { (it.mode and 0x1FFL) == 0x1EDL }
        items.add(
            CheckItem(
                "文件权限 0755", "File mode 0755",
                permOk,
                if (permOk) "全部 0755" else "存在非 0755 文件",
                if (permOk) "all 0755" else "some not 0755",
            )
        )

        items.add(
            CheckItem(
                "magic 值", "Magic value",
                info.hasMagic,
                if (info.hasMagic) FirmwareParser.MAGIC_VALUE else "缺少 ${FirmwareParser.MAGIC_VALUE}",
                if (info.hasMagic) FirmwareParser.MAGIC_VALUE else "missing ${FirmwareParser.MAGIC_VALUE}",
            )
        )

        items.add(
            CheckItem(
                "ly_project_name", "ly_project_name",
                info.hasProjectName,
                if (info.hasProjectName) FirmwareParser.PROJECT_NAME else "缺少 ${FirmwareParser.PROJECT_NAME}",
                if (info.hasProjectName) FirmwareParser.PROJECT_NAME else "missing ${FirmwareParser.PROJECT_NAME}",
            )
        )

        val ckOk = info.customerSquashfs && info.kernelAndroidBoot
        items.add(
            CheckItem(
                "customer/kernel 格式", "customer/kernel type",
                ckOk,
                "customer=${if (info.customerSquashfs) "squashfs" else "?"} kernel=${if (info.kernelAndroidBoot) "boot" else "?"}",
                "customer=${if (info.customerSquashfs) "squashfs" else "?"} kernel=${if (info.kernelAndroidBoot) "boot" else "?"}",
            )
        )

        val pass = items.count { it.passed }
        val fail = items.count { !it.passed }
        return CheckReport(items, pass, fail, pass == items.size)
    }
}
