package com.boxflasher.tool

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.boxflasher.tool.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var currentFirmware: File? = null
    private var lastCheckReport: FirmwareChecker.CheckReport? = null
    private val fetcher = FirmwareFetcher()

    private val pickFile = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let { resolveUri(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnPickLocal.setOnClickListener { pickFile.launch("*/*") }
        binding.btnFetchServer.setOnClickListener { showFetchDialog() }
        binding.btnFlash.setOnClickListener { startFlash() }
        binding.btnCheckOnly.setOnClickListener { runCheck() }
        binding.btnRefreshBox.setOnClickListener { refreshBoxInfo() }
        binding.layoutGithub.setOnClickListener { openGithub() }

        binding.etProject.setText("")
        binding.etHost.setText(BoxFlasher.DEFAULT_HOST)

        updateUi()
    }

    private fun resolveUri(uri: Uri) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val file = copyToCache(uri)
                currentFirmware = file
                withContext(Dispatchers.Main) { runCheck() }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    toast(getString(R.string.err_read_file, safeErr(e)))
                }
            }
        }
    }

    private fun copyToCache(uri: Uri): File {
        val name = "update_${System.currentTimeMillis()}.img"
        val dest = File(cacheDir, name)
        contentResolver.openInputStream(uri)?.use { input ->
            dest.outputStream().use { out -> input.copyTo(out) }
        } ?: throw RuntimeException("cannot open uri")
        return dest
    }

    private fun runCheck() {
        val f = currentFirmware ?: return
        val parser = FirmwareParser(f)
        val checker = FirmwareChecker(parser)
        val report = checker.check(f)
        lastCheckReport = report

        binding.tvFirmwareName.text = f.name
        binding.tvFirmwareSize.text = formatSize(f.length())
        binding.tvFirmwareMd5.text = parser.parse().info.wholeMd5

        val lines = report.items.joinToString("\n") { item ->
            val mark = if (item.passed) "✅" else "❌"
            val label = if (isChinese()) item.name else item.nameEn
            val detail = if (isChinese()) item.detail else item.detailEn
            "$mark $label : $detail"
        }
        binding.tvCheckReport.text = lines
        binding.tvCheckSummary.text = if (report.allPassed) {
            getString(R.string.check_pass, report.passCount)
        } else {
            getString(R.string.check_fail, report.passCount, report.failCount)
        }
        updateUi()
    }

    private fun updateUi() {
        val hasFile = currentFirmware != null
        binding.btnFlash.isEnabled = hasFile
        binding.btnCheckOnly.isEnabled = hasFile
        binding.tvStatus.text = if (hasFile) {
            getString(R.string.status_ready)
        } else {
            getString(R.string.status_no_firmware)
        }
    }

    private fun startFlash() {
        val f = currentFirmware ?: return
        val report = lastCheckReport
        if (report != null && !report.allPassed) {
            AlertDialog.Builder(this)
                .setTitle(R.string.warn_title)
                .setMessage(R.string.warn_body)
                .setPositiveButton(R.string.warn_go) { _, _ -> doFlash(f) }
                .setNegativeButton(R.string.warn_cancel, null)
                .show()
        } else {
            doFlash(f)
        }
    }

    private fun doFlash(f: File) {
        val host = binding.etHost.text.toString().trim().ifBlank { BoxFlasher.DEFAULT_HOST }
        val fh = BoxFlasher(host)
        binding.progressFlash.isIndeterminate = false
        binding.progressFlash.progress = 0
        binding.btnFlash.isEnabled = false

        lifecycleScope.launch {
            try {
                setStatus(getString(R.string.status_connecting, host))
                val info = fh.queryBoxInfoAsync()
                setStatus(getString(R.string.status_box_found, host, info.update))

                setStatus(getString(R.string.status_uploading))
                val resp = fh.upload(f) { done, total ->
                    lifecycleScope.launch(Dispatchers.Main) {
                        if (total > 0) {
                            binding.progressFlash.max = 100
                            binding.progressFlash.progress = (done * 100 / total).toInt()
                        }
                    }
                }
                setStatus(getString(R.string.status_uploaded, resp))

                setStatus(getString(R.string.status_polling))
                fh.pollUntilFinished(maxWaitSeconds = 180, intervalMillis = 3000) { state ->
                    lifecycleScope.launch(Dispatchers.Main) {
                        binding.tvStatus.text = updateDesc(state)
                    }
                }

                val final = fh.queryBoxInfoAsync()
                if (final.update == 3) {
                    setStatus(getString(R.string.status_success_reboot))
                    try {
                        fh.rebootAsync()
                        toast(getString(R.string.toast_rebooting))
                    } catch (e: Exception) {
                        toast(getString(R.string.toast_reboot_fail, safeErr(e)))
                    }
                } else {
                    setStatus(getString(R.string.status_failed, final.update))
                }
            } catch (e: Exception) {
                setStatus(getString(R.string.status_error, safeErr(e)))
                toast(getString(R.string.err_flash, safeErr(e)))
            } finally {
                binding.btnFlash.isEnabled = true
                binding.progressFlash.progress = 0
            }
        }
    }

    private fun updateDesc(state: BoxFlasher.FlashState): String {
        return when (state.update) {
            0 -> getString(R.string.state_0)
            1 -> getString(R.string.state_1)
            2 -> getString(R.string.state_2)
            3 -> getString(R.string.state_3)
            4 -> getString(R.string.state_4)
            5 -> getString(R.string.state_5)
            else -> getString(R.string.state_unknown, state.update)
        }
    }

    private fun showFetchDialog() {
        val typed = binding.etProject.text.toString().trim()
        if (typed.isNotEmpty()) {
            lifecycleScope.launch { fetchOneAndPick(typed) }
        } else {
            showProjectPicker()
        }
    }

    /** 项目号分类选择器：全部 + 170 个项目号 */
    private fun showProjectPicker() {
        val items = mutableListOf(getString(R.string.proj_all))
        items.addAll(FirmwareFetcher.ALL_PROJECTS)
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.proj_picker_title, FirmwareFetcher.ALL_PROJECTS.size))
            .setItems(items.toTypedArray()) { _, which ->
                if (which == 0) {
                    lifecycleScope.launch { fetchAllAndPick() }
                } else {
                    lifecycleScope.launch { fetchOneAndPick(items[which]) }
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private suspend fun fetchAllAndPick() {
        try {
            setStatus(getString(R.string.status_fetching_all))
            val all = fetcher.fetchAllProjects { p, done, total ->
                lifecycleScope.launch(Dispatchers.Main) {
                    setStatus(getString(R.string.status_fetching_progress, p, done, total))
                }
            }
            if (all.isEmpty()) {
                toast(getString(R.string.err_no_firmware))
                return
            }
            setStatus(getString(R.string.status_fetched_count, all.size))
            showFirmwarePicker(getString(R.string.fetch_title, all.size), all)
        } catch (e: Exception) {
            withContext(Dispatchers.Main) {
                setStatus(getString(R.string.status_error, safeErr(e)))
                toast(getString(R.string.err_fetch, safeErr(e)))
            }
        }
    }

    private suspend fun fetchOneAndPick(project: String) {
        try {
            withContext(Dispatchers.Main) { setStatus(getString(R.string.status_fetching, project)) }
            val list = fetcher.fetchVersions(project)
            val options = mutableListOf<FirmwareFetcher.Firmware>()
            list.stable?.let { options.add(it) }
            options.addAll(list.tests)
            if (options.isEmpty()) {
                toast(getString(R.string.err_no_firmware))
                return
            }
            showFirmwarePicker(getString(R.string.fetch_title_project, project, options.size), options)
        } catch (e: Exception) {
            withContext(Dispatchers.Main) {
                setStatus(getString(R.string.status_error, safeErr(e)))
                toast(getString(R.string.err_fetch, safeErr(e)))
            }
        }
    }

    private suspend fun showFirmwarePicker(title: String, options: List<FirmwareFetcher.Firmware>) {
        val labels = options.map { fw ->
            val tag = if (fw.isTest) "TEST" else "STABLE"
            val snNote = if (fw.isTest && !fw.sn.isNullOrBlank()) " [SN]" else ""
            "${fw.project}  $tag  ${fw.appver}$snNote"
        }.toTypedArray()
        withContext(Dispatchers.Main) {
            AlertDialog.Builder(this@MainActivity)
                .setTitle(title)
                .setItems(labels) { _, which -> downloadFirmware(options[which]) }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
    }

    private fun downloadFirmware(fw: FirmwareFetcher.Firmware) {
        setStatus(getString(R.string.status_downloading, fw.appver))
        lifecycleScope.launch {
            try {
                val tmp = File(cacheDir, "download_tmp_${System.currentTimeMillis()}.img")
                fetcher.download(fw, tmp) { done, total ->
                    lifecycleScope.launch(Dispatchers.Main) {
                        if (total > 0) {
                            binding.progressFlash.max = 100
                            binding.progressFlash.progress = (done * 100 / total).toInt()
                        }
                    }
                }
                val dest = withContext(Dispatchers.IO) { saveToPublic(tmp, fw) }
                currentFirmware = dest
                withContext(Dispatchers.Main) { runCheck() }
                val displayPath = android.os.Environment.getExternalStoragePublicDirectory(
                    android.os.Environment.DIRECTORY_DOWNLOADS
                ).absolutePath + "/" + dest.name
                setStatus(getString(R.string.status_downloaded_path, displayPath))
                toast(getString(R.string.toast_saved_download))
            } catch (e: Exception) {
                setStatus(getString(R.string.status_error, safeErr(e)))
                toast(getString(R.string.err_download, safeErr(e)))
            }
        }
    }

    private fun saveToPublic(src: File, fw: FirmwareFetcher.Firmware): File {
        val fileName = "cpbox_${fw.appver}_${System.currentTimeMillis()}.img"
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            val values = android.content.ContentValues().apply {
                put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "application/octet-stream")
                put(
                    android.provider.MediaStore.MediaColumns.RELATIVE_PATH,
                    android.os.Environment.DIRECTORY_DOWNLOADS
                )
            }
            val uri = contentResolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw RuntimeException("cannot create download entry")
            contentResolver.openOutputStream(uri)?.use { out ->
                src.inputStream().use { it.copyTo(out) }
            } ?: throw RuntimeException("cannot open output stream")
            return src
        } else {
            val dir = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS)
            dir.mkdirs()
            val dest = File(dir, fileName)
            src.copyTo(dest, overwrite = true)
            src.delete()
            return dest
        }
    }

    private fun refreshBoxInfo() {
        val host = binding.etHost.text.toString().trim().ifBlank { BoxFlasher.DEFAULT_HOST }
        lifecycleScope.launch {
            try {
                val info = BoxFlasher(host).queryBoxInfoAsync()
                setStatus(getString(R.string.box_info, host, info.update))
            } catch (e: Exception) {
                setStatus(getString(R.string.status_error, safeErr(e)))
            }
        }
    }

    private fun openGithub() {
        val uri = Uri.parse("https://github.com/Srysfu")
        try {
            startActivity(Intent(Intent.ACTION_VIEW, uri))
        } catch (e: Exception) {
            toast(getString(R.string.err_read_file, safeErr(e)))
        }
    }

    private fun safeErr(e: Exception): String {
        return e.message ?: e.javaClass.simpleName
    }

    private fun setStatus(s: String) {
        runOnUiThread { binding.tvStatus.text = s }
    }

    private fun toast(s: String) {
        runOnUiThread { Toast.makeText(this, s, Toast.LENGTH_SHORT).show() }
    }

    private fun formatSize(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        if (bytes < 1024 * 1024) return String.format(Locale.US, "%.1f KB", bytes / 1024.0)
        return String.format(Locale.US, "%.2f MB", bytes / 1024.0 / 1024.0)
    }

    private fun isChinese(): Boolean {
        return Locale.getDefault().language == "zh"
    }
}
