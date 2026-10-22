package com.cast16x9.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.util.DisplayMetrics
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.cast16x9.app.adapter.FileAdapter
import com.cast16x9.app.adapter.MediaAdapter
import com.cast16x9.app.databinding.ActivityMainBinding
import com.cast16x9.app.dlna.DlnaDevice
import com.cast16x9.app.dlna.DlnaManager
import com.cast16x9.app.media.MediaScanner
import com.cast16x9.app.model.MediaItem
import com.cast16x9.app.model.SharedFile
import com.cast16x9.app.server.WebReceiverServer
import com.cast16x9.app.service.ScreenCaptureService
import com.cast16x9.app.utils.NetworkUtils
import com.cast16x9.app.utils.QrCodeGenerator
import kotlinx.coroutines.launch
import java.util.UUID

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var webServer: WebReceiverServer? = null
    private lateinit var dlnaManager: DlnaManager
    private lateinit var mediaScanner: MediaScanner

    private lateinit var mediaAdapter: MediaAdapter
    private lateinit var fileAdapter: FileAdapter

    private var activeDlnaDevice: DlnaDevice? = null
    private var isMirroring = false

    // Screen Capture MediaProjection Launcher
    private val screenCaptureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            startScreenCaptureService(result.resultCode, result.data!!)
        } else {
            Toast.makeText(this, "Screen capture permission denied", Toast.LENGTH_SHORT).show()
            updateMirrorButtonState(false)
        }
    }

    // Permission Launcher for Media Access
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val granted = permissions.entries.all { it.value }
        if (granted) {
            loadLocalMedia()
        } else {
            Toast.makeText(this, "Storage permissions are needed to browse and stream media", Toast.LENGTH_SHORT).show()
        }
    }

    // File Picker Launcher for Sharing Files
    private val filePickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (!uris.isNullOrEmpty()) {
            addSelectedFilesToShare(uris)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        dlnaManager = DlnaManager(this)
        mediaScanner = MediaScanner(this)

        startEmbeddedServer()
        setupUI()
        setupNavigation()
        setupAspectModeControls()
        checkAndRequestPermissions()
    }

    private fun startEmbeddedServer() {
        try {
            webServer = WebReceiverServer(this, NetworkUtils.DEFAULT_HTTP_PORT)
            webServer?.onMediaCancelledListener = {
                runOnUiThread {
                    binding.cardActiveCast.visibility = View.GONE
                }
            }
            webServer?.start()
            ScreenCaptureService.serverInstance = webServer

            val ip = NetworkUtils.getLocalIpAddress(this)
            val url = "http://$ip:${NetworkUtils.DEFAULT_HTTP_PORT}"
            binding.tvReceiverUrl.text = url

            // Generate QR Code
            val qrBmp = QrCodeGenerator.generateQrCodeBitmap(url, 400, 400)
            if (qrBmp != null) {
                binding.ivQrCode.setImageBitmap(qrBmp)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, "Failed to start local TV server: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun setupUI() {
        // RecyclerView: Media Items
        mediaAdapter = MediaAdapter { mediaItem ->
            showMediaActionDialog(mediaItem)
        }
        binding.rvMediaItems.layoutManager = LinearLayoutManager(this)
        binding.rvMediaItems.adapter = mediaAdapter

        // RecyclerView: Shared Files
        fileAdapter = FileAdapter { fileToRemove ->
            webServer?.sharedFiles?.remove(fileToRemove)
            refreshSharedFilesList()
        }
        binding.rvSharedFiles.layoutManager = LinearLayoutManager(this)
        binding.rvSharedFiles.adapter = fileAdapter

        // Mirror Button
        binding.btnToggleMirror.setOnClickListener {
            if (!isMirroring) {
                requestScreenCapture()
            } else {
                stopScreenCaptureService()
            }
        }

        // DLNA Scan Button
        binding.btnScanDlna.setOnClickListener {
            scanDlnaDevices()
        }

        // Add Files to Share Button
        binding.btnAddFiles.setOnClickListener {
            filePickerLauncher.launch(arrayOf("*/*"))
        }

        // Active Cast Controls (Stop / Change Video from APK)
        binding.btnStopCast.setOnClickListener {
            webServer?.stopCastingMedia()
            binding.cardActiveCast.visibility = View.GONE
            Toast.makeText(this, "Media beam canceled. TV storage & memory freed.", Toast.LENGTH_SHORT).show()
        }

        binding.btnChangeVideo.setOnClickListener {
            webServer?.stopCastingMedia()
            binding.cardActiveCast.visibility = View.GONE
            Toast.makeText(this, "Select any video below to beam freshly to TV", Toast.LENGTH_SHORT).show()
        }
    }

    private fun setupNavigation() {
        binding.bottomNavigation.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_mirror -> {
                    binding.layoutMirror.visibility = View.VISIBLE
                    binding.layoutMedia.visibility = View.GONE
                    binding.layoutFiles.visibility = View.GONE
                    true
                }
                R.id.nav_media -> {
                    binding.layoutMirror.visibility = View.GONE
                    binding.layoutMedia.visibility = View.VISIBLE
                    binding.layoutFiles.visibility = View.GONE
                    loadLocalMedia()
                    true
                }
                R.id.nav_files -> {
                    binding.layoutMirror.visibility = View.GONE
                    binding.layoutMedia.visibility = View.GONE
                    binding.layoutFiles.visibility = View.VISIBLE
                    refreshSharedFilesList()
                    true
                }
                else -> false
            }
        }
    }

    private fun setupAspectModeControls() {
        binding.rgAspectModes.setOnCheckedChangeListener { _, checkedId ->
            val mode = when (checkedId) {
                R.id.rb_cover -> "cover"     // 16:9 Zoom/Cover (No Black Bars)
                R.id.rb_stretch -> "stretch" // 16:9 Stretch/Fill (No Black Bars)
                R.id.rb_fit -> "fit"         // Original Aspect
                else -> "cover"
            }
            webServer?.setTvAspectMode(mode)
            Toast.makeText(this, "TV Display Mode: $mode", Toast.LENGTH_SHORT).show()
        }
    }

    private fun requestScreenCapture() {
        val mpManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        screenCaptureLauncher.launch(mpManager.createScreenCaptureIntent())
    }

    private fun startScreenCaptureService(resultCode: Int, data: Intent) {
        val metrics = DisplayMetrics()
        windowManager.defaultDisplay.getRealMetrics(metrics)

        val serviceIntent = Intent(this, ScreenCaptureService::class.java).apply {
            action = ScreenCaptureService.ACTION_START
            putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, resultCode)
            putExtra(ScreenCaptureService.EXTRA_RESULT_DATA, data)
            putExtra(ScreenCaptureService.EXTRA_WIDTH, metrics.widthPixels)
            putExtra(ScreenCaptureService.EXTRA_HEIGHT, metrics.heightPixels)
            putExtra(ScreenCaptureService.EXTRA_DENSITY, metrics.densityDpi)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }

        updateMirrorButtonState(true)
        Toast.makeText(this, "Mirroring started! View in full 16:9 on your TV.", Toast.LENGTH_SHORT).show()
    }

    private fun stopScreenCaptureService() {
        val serviceIntent = Intent(this, ScreenCaptureService::class.java).apply {
            action = ScreenCaptureService.ACTION_STOP
        }
        startService(serviceIntent)
        updateMirrorButtonState(false)
        Toast.makeText(this, "Mirroring stopped", Toast.LENGTH_SHORT).show()
    }

    private fun updateMirrorButtonState(running: Boolean) {
        isMirroring = running
        if (running) {
            binding.btnToggleMirror.text = getString(R.string.btn_stop_mirror)
            binding.btnToggleMirror.setBackgroundColor(ContextCompat.getColor(this, R.color.accent_red))
        } else {
            binding.btnToggleMirror.text = getString(R.string.btn_start_mirror)
            binding.btnToggleMirror.setBackgroundColor(ContextCompat.getColor(this, R.color.primary))
        }
    }

    private fun loadLocalMedia() {
        lifecycleScope.launch {
            val videos = mediaScanner.loadVideos()
            val photos = mediaScanner.loadPhotos()
            val allMedia = mutableListOf<MediaItem>().apply {
                addAll(videos)
                addAll(photos)
            }

            if (allMedia.isEmpty()) {
                binding.tvEmptyMedia.visibility = View.VISIBLE
                binding.rvMediaItems.visibility = View.GONE
            } else {
                binding.tvEmptyMedia.visibility = View.GONE
                binding.rvMediaItems.visibility = View.VISIBLE
                mediaAdapter.submitList(allMedia)
            }
        }
    }

    private fun showMediaActionDialog(item: MediaItem) {
        val options = mutableListOf("📺 Play on TV Web Receiver (1080p / 4K Fullscreen)")
        if (activeDlnaDevice != null) {
            options.add("📡 Native DLNA TV Player: ${activeDlnaDevice!!.friendlyName} (Pure 4K HDR)")
        } else {
            options.add("🔍 Scan & Play via Native DLNA TV Player (Pure 4K HDR)")
        }

        AlertDialog.Builder(this)
            .setTitle(item.title)
            .setItems(options.toTypedArray()) { _, which ->
                when (which) {
                    0 -> {
                        // Cast to Web Receiver in full 1080p/4K
                        webServer?.castMediaToTv(item.uri, item.mimeType, item.title, item.path, item.size)
                        binding.cardActiveCast.visibility = View.VISIBLE
                        binding.tvCastingTitle.text = item.title
                        Toast.makeText(this, "Casting 1080p/4K to TV Web Receiver...", Toast.LENGTH_SHORT).show()
                    }
                    1 -> {
                        binding.cardActiveCast.visibility = View.VISIBLE
                        binding.tvCastingTitle.text = item.title
                        if (activeDlnaDevice != null) {
                            castToDlna(item, activeDlnaDevice!!)
                        } else {
                            scanDlnaDevices { selectedDevice ->
                                castToDlna(item, selectedDevice)
                            }
                        }
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun castToDlna(item: MediaItem, device: DlnaDevice) {
        webServer?.currentMediaUri = item.uri
        webServer?.currentMediaMimeType = item.mimeType
        webServer?.currentMediaTitle = item.title
        webServer?.currentMediaPath = item.path
        webServer?.currentMediaSize = item.size

        val ip = NetworkUtils.getLocalIpAddress(this)
        val mediaStreamUrl = "http://$ip:${NetworkUtils.DEFAULT_HTTP_PORT}/media/active"

        lifecycleScope.launch {
            val success = dlnaManager.playMediaOnDlnaTv(device, mediaStreamUrl, item.title, item.mimeType)
            if (success) {
                Toast.makeText(this@MainActivity, "Playing on ${device.friendlyName}", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this@MainActivity, "Failed to beam to DLNA TV", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun scanDlnaDevices(onSelected: ((DlnaDevice) -> Unit)? = null) {
        Toast.makeText(this, "Scanning for Smart TVs on Wi-Fi...", Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            val devices = dlnaManager.discoverDevices()
            if (devices.isEmpty()) {
                Toast.makeText(this@MainActivity, "No DLNA TVs found. You can always use the TV Web Receiver URL!", Toast.LENGTH_LONG).show()
                return@launch
            }

            val names = devices.map { it.friendlyName }.toTypedArray()
            AlertDialog.Builder(this@MainActivity)
                .setTitle("Select Smart TV")
                .setItems(names) { _, which ->
                    activeDlnaDevice = devices[which]
                    binding.tvDlnaStatus.text = "Target: ${activeDlnaDevice!!.friendlyName}"
                    Toast.makeText(this@MainActivity, "Connected to ${activeDlnaDevice!!.friendlyName}", Toast.LENGTH_SHORT).show()
                    onSelected?.invoke(activeDlnaDevice!!)
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    private fun addSelectedFilesToShare(uris: List<Uri>) {
        for (uri in uris) {
            val name = queryFileName(uri) ?: "SharedFile_${System.currentTimeMillis()}"
            val size = queryFileSize(uri)
            val mime = contentResolver.getType(uri) ?: "application/octet-stream"
            val isVid = mime.startsWith("video/")
            val isPic = mime.startsWith("image/")

            val sharedFile = SharedFile(
                id = UUID.randomUUID().toString().take(8),
                name = name,
                size = size,
                uri = uri,
                mimeType = mime,
                isVideo = isVid,
                isPhoto = isPic
            )
            webServer?.sharedFiles?.add(sharedFile)
        }
        refreshSharedFilesList()
        Toast.makeText(this, "Added ${uris.size} file(s) for TV sharing", Toast.LENGTH_SHORT).show()
    }

    private fun refreshSharedFilesList() {
        val files = webServer?.sharedFiles ?: emptyList<SharedFile>()
        binding.tvFilesCount.text = "${files.size} file(s) shared"
        if (files.isEmpty()) {
            binding.tvEmptyFiles.visibility = View.VISIBLE
            binding.rvSharedFiles.visibility = View.GONE
        } else {
            binding.tvEmptyFiles.visibility = View.GONE
            binding.rvSharedFiles.visibility = View.VISIBLE
            fileAdapter.submitList(files.toList())
        }
    }

    private fun queryFileName(uri: Uri): String? {
        return contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (cursor.moveToFirst() && nameIdx != -1) {
                cursor.getString(nameIdx)
            } else null
        }
    }

    private fun queryFileSize(uri: Uri): Long {
        return contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (cursor.moveToFirst() && sizeIdx != -1) {
                cursor.getLong(sizeIdx)
            } else 0L
        } ?: 0L
    }

    private fun checkAndRequestPermissions() {
        val permissions = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_VIDEO) != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.READ_MEDIA_VIDEO)
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_IMAGES) != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.READ_MEDIA_IMAGES)
            }
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
        }

        if (permissions.isNotEmpty()) {
            requestPermissionLauncher.launch(permissions.toTypedArray())
        } else {
            loadLocalMedia()
        }
    }

    override fun onDestroy() {
        webServer?.stop()
        super.onDestroy()
    }
}
