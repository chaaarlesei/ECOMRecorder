package com.accli.ecomrecorder

import android.Manifest
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.ContentValues
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Paint
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.MediaStore
import android.view.MotionEvent
import android.view.View
import android.widget.Chronometer
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.Camera
import androidx.camera.core.CameraEffect
import androidx.camera.core.CameraSelector
import androidx.camera.core.DynamicRange
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.Preview
import androidx.camera.core.UseCaseGroup
import androidx.camera.effects.OverlayEffect
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.PermissionChecker
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class SingleCaptureActivity : AppCompatActivity() {
    private lateinit var viewFinder: androidx.camera.view.PreviewView
    private lateinit var tvStatus: TextView
    private lateinit var tvWarning: TextView
    private lateinit var chronometer: Chronometer
    private lateinit var btnCapture: ImageButton
    private lateinit var btnFlash: ImageButton
    private lateinit var btnPause: ImageButton
    private lateinit var btnMute: ImageButton
    private lateinit var btnSettings: ImageButton
    private lateinit var focusRing: View

    private var videoCapture: VideoCapture<Recorder>? = null
    private var recording: Recording? = null
    private lateinit var cameraExecutor: ExecutorService
    private var blinkingAnimator: ObjectAnimator? = null
    private var overlayEffect: OverlayEffect? = null
    private var camera: Camera? = null

    private var isTorchOn = false
    private var isMuted = true
    private var isPaused = false
    private var discardOnFinalize = false

    private var currentQualityName = "HD"
    private lateinit var prefs: SharedPreferences

    private var pendingFilename: String? = null
    private var targetFolder: String = "Pack"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_continuous_capture)

        pendingFilename = intent.getStringExtra(EXTRA_FILENAME)
        targetFolder = intent.getStringExtra(EXTRA_FOLDER) ?: "Pack"

        prefs = getSharedPreferences("ecom_recorder_prefs", Context.MODE_PRIVATE)
        isMuted = prefs.getBoolean("mute_state", true)
        isTorchOn = prefs.getBoolean("flash_state", false)
        currentQualityName = prefs.getString("quality_name", "HD") ?: "HD"

        viewFinder = findViewById(R.id.viewFinder)
        tvStatus = findViewById(R.id.tv_status)
        tvWarning = findViewById(R.id.tv_warning)
        chronometer = findViewById(R.id.chronometer)
        btnCapture = findViewById(R.id.btn_capture)
        btnFlash = findViewById(R.id.btn_flash)
        btnMute = findViewById(R.id.btn_mute)
        btnSettings = findViewById(R.id.btn_settings)
        btnPause = findViewById(R.id.btn_pause)
        focusRing = findViewById(R.id.focus_ring)

        updateMuteIcon()
        updateFlashIcon()
        startBlinking()

        btnFlash.setOnClickListener { toggleFlash() }
        btnMute.setOnClickListener { toggleMute() }
        btnSettings.setOnClickListener { showQualityDialog() }

        btnPause.setOnClickListener {
            if (recording != null) {
                if (isPaused) recording?.resume() else recording?.pause()
            }
        }

        btnCapture.setOnClickListener {
            if (recording != null) {
                stopRecordingAndFinish()
            } else {
                startRecording()
            }
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (recording != null) {
                    AlertDialog.Builder(this@SingleCaptureActivity)
                        .setTitle("Discard Recording?")
                        .setMessage("Exiting now will discard the current recording. Are you sure?")
                        .setPositiveButton("Discard & Exit") { _, _ ->
                            discardOnFinalize = true
                            recording?.stop()
                            recording = null
                        }
                        .setNegativeButton("Cancel", null)
                        .show()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })

        if (allPermissionsGranted()) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS)
        }

        cameraExecutor = Executors.newSingleThreadExecutor()
    }

    private fun showQualityDialog() {
        if (recording != null) {
            Toast.makeText(this, "Cannot change quality while recording", Toast.LENGTH_SHORT).show()
            return
        }

        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            try {
                val cameraProvider = cameraProviderFuture.get()
                val cameraInfo = cameraProvider.availableCameraInfos.firstOrNull {
                    it.lensFacing == CameraSelector.LENS_FACING_BACK
                } ?: return@addListener

                val capabilities = Recorder.getVideoCapabilities(cameraInfo)
                val supportedQualities = capabilities.getSupportedQualities(DynamicRange.SDR)
                val qualityMap = mutableListOf<Pair<String, Quality>>()

                if (supportedQualities.contains(Quality.UHD)) qualityMap.add("4K (UHD)" to Quality.UHD)
                if (supportedQualities.contains(Quality.FHD)) qualityMap.add("1080p (FHD)" to Quality.FHD)
                if (supportedQualities.contains(Quality.HD)) qualityMap.add("720p (HD)" to Quality.HD)
                if (supportedQualities.contains(Quality.SD)) qualityMap.add("480p (SD)" to Quality.SD)

                if (qualityMap.isEmpty()) {
                    Toast.makeText(this, "No supported qualities found", Toast.LENGTH_SHORT).show()
                    return@addListener
                }

                val options = qualityMap.map { it.first }.toTypedArray()
                val currentQualityObj = getQualityFromPreference()
                var selectedIndex = qualityMap.indexOfFirst { it.second == currentQualityObj }
                if (selectedIndex == -1) selectedIndex = 0

                AlertDialog.Builder(this)
                    .setTitle("Select Video Quality")
                    .setSingleChoiceItems(options, selectedIndex) { dialog, which ->
                        val selected = qualityMap[which]
                        val shortName = when (selected.second) {
                            Quality.UHD -> "UHD"
                            Quality.FHD -> "FHD"
                            Quality.HD -> "HD"
                            Quality.SD -> "SD"
                            else -> "HD"
                        }

                        currentQualityName = shortName
                        prefs.edit().putString("quality_name", shortName).apply()
                        Toast.makeText(this, "Quality set to ${selected.first}", Toast.LENGTH_SHORT).show()
                        startCamera()
                        dialog.dismiss()
                    }
                    .setNegativeButton("Cancel", null)
                    .show()

            } catch (_: Exception) {
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun getQualityFromPreference(): Quality {
        return when (currentQualityName) {
            "UHD" -> Quality.UHD
            "FHD" -> Quality.FHD
            "HD" -> Quality.HD
            "SD" -> Quality.SD
            else -> Quality.HD
        }
    }

    private fun toggleMute() {
        isMuted = !isMuted
        updateMuteIcon()
        recording?.mute(isMuted)
        prefs.edit().putBoolean("mute_state", isMuted).apply()
        val status = if (isMuted) "Muted" else "Unmuted"
        Toast.makeText(this, "Microphone $status", Toast.LENGTH_SHORT).show()
    }

    private fun updateMuteIcon() {
        val icon = if (isMuted) R.drawable.ic_mic_off else R.drawable.ic_mic_on
        btnMute.setImageResource(icon)
    }

    private fun toggleFlash() {
        val cam = camera ?: return
        if (cam.cameraInfo.hasFlashUnit()) {
            isTorchOn = !isTorchOn
            cam.cameraControl.enableTorch(isTorchOn)
            updateFlashIcon()
            prefs.edit().putBoolean("flash_state", isTorchOn).apply()
        } else {
            Toast.makeText(this, "Flash not available", Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateFlashIcon() {
        val icon = if (isTorchOn) R.drawable.ic_flash_on else R.drawable.ic_flash_off
        btnFlash.setImageResource(icon)
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(viewFinder.surfaceProvider)
            }

            val preferredQuality = getQualityFromPreference()
            val recorder = Recorder.Builder()
                .setQualitySelector(
                    QualitySelector.from(
                        preferredQuality,
                        FallbackStrategy.lowerQualityOrHigherThan(preferredQuality)
                    )
                )
                .build()

            videoCapture = VideoCapture.withOutput(recorder)

            overlayEffect = OverlayEffect(
                CameraEffect.VIDEO_CAPTURE,
                0,
                Handler(Looper.getMainLooper()),
                { }
            ).apply {
                setOnDrawListener { frame ->
                    val canvas = frame.overlayCanvas
                    canvas.drawColor(0, android.graphics.PorterDuff.Mode.CLEAR)
                    val textPaint = Paint().apply {
                        color = Color.WHITE
                        textSize = 30f
                        isAntiAlias = true
                        style = Paint.Style.FILL
                        setShadowLayer(4f, 0f, 0f, Color.BLACK)
                        textAlign = Paint.Align.RIGHT
                    }
                    val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
                        .format(System.currentTimeMillis())
                    val address = "15 Sta. Maria Drive, ACCLI, Taguig"
                    val fullText = "$timestamp $address"
                    val padding = 30f
                    val x = frame.size.width - padding
                    val y = frame.size.height - padding
                    canvas.drawText(fullText, x, y, textPaint)
                    true
                }
            }

            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                cameraProvider.unbindAll()
                val useCaseGroup = UseCaseGroup.Builder()
                    .addUseCase(preview)
                    .addUseCase(videoCapture!!)
                    .addEffect(overlayEffect!!)
                    .build()

                camera = cameraProvider.bindToLifecycle(this, cameraSelector, useCaseGroup)
                setupTapToFocus()

                if (camera!!.cameraInfo.hasFlashUnit()) {
                    camera!!.cameraControl.enableTorch(isTorchOn)
                    updateFlashIcon()
                }
            } catch (_: Exception) {
                Toast.makeText(this, "Camera init failed", Toast.LENGTH_SHORT).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun startRecording() {
        val capture = videoCapture ?: return

        val name = SimpleDateFormat(FILENAME_FORMAT, Locale.US).format(System.currentTimeMillis())
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            if (Build.VERSION.SDK_INT > Build.VERSION_CODES.P) {
                put(MediaStore.Video.Media.RELATIVE_PATH, "DCIM/Ecom/$targetFolder")
            }
        }

        val outputOptions = MediaStoreOutputOptions
            .Builder(contentResolver, MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
            .setContentValues(contentValues)
            .build()

        recording = capture.output
            .prepareRecording(this, outputOptions)
            .apply {
                if (PermissionChecker.checkSelfPermission(
                        this@SingleCaptureActivity,
                        Manifest.permission.RECORD_AUDIO
                    ) == PermissionChecker.PERMISSION_GRANTED
                ) {
                    withAudioEnabled()
                }
            }
            .start(ContextCompat.getMainExecutor(this)) { event ->
                when (event) {
                    is VideoRecordEvent.Start -> {
                        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                        stopBlinking()
                        tvStatus.text = "Recording..."
                        btnCapture.setImageResource(R.drawable.ic_record_stop)

                        btnPause.visibility = View.VISIBLE
                        btnPause.setImageResource(R.drawable.ic_pause)
                        isPaused = false

                        chronometer.base = SystemClock.elapsedRealtime()
                        chronometer.start()

                        recording?.mute(isMuted)
                    }

                    is VideoRecordEvent.Pause -> {
                        isPaused = true
                        btnPause.setImageResource(R.drawable.ic_resume)
                        tvStatus.text = "Paused"
                        chronometer.stop()
                        startBlinking()
                    }

                    is VideoRecordEvent.Resume -> {
                        isPaused = false
                        btnPause.setImageResource(R.drawable.ic_pause)
                        tvStatus.text = "Recording..."
                        stopBlinking()
                        val timePassed = SystemClock.elapsedRealtime() -
                            event.recordingStats.recordedDurationNanos / 1000000
                        chronometer.base = timePassed
                        chronometer.start()
                    }

                    is VideoRecordEvent.Finalize -> {
                        window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                        btnPause.visibility = View.GONE
                        isPaused = false
                        chronometer.stop()
                        chronometer.base = SystemClock.elapsedRealtime()
                        tvStatus.text = "Ready"
                        btnCapture.setImageResource(R.drawable.ic_record_start)
                        startBlinking()

                        if (!event.hasError()) {
                            val outputUri = event.outputResults.outputUri
                            if (discardOnFinalize) {
                                try {
                                    contentResolver.delete(outputUri, null, null)
                                } catch (_: Exception) {
                                }
                                setResult(RESULT_CANCELED)
                                finish()
                                return@start
                            }

                            val finalName = pendingFilename
                            if (!finalName.isNullOrBlank()) {
                                try {
                                    val values = ContentValues().apply {
                                        put(MediaStore.MediaColumns.DISPLAY_NAME, finalName)
                                    }
                                    contentResolver.update(outputUri, values, null, null)
                                    Toast.makeText(baseContext, "Saved as $finalName", Toast.LENGTH_SHORT).show()
                                } catch (_: Exception) {
                                    Toast.makeText(baseContext, "Saved (rename failed)", Toast.LENGTH_SHORT).show()
                                }
                            } else {
                                Toast.makeText(baseContext, "Video saved", Toast.LENGTH_SHORT).show()
                            }
                            setResult(RESULT_OK)
                            finish()
                        }
                        recording?.close()
                        recording = null
                    }
                }
            }

        recording?.mute(isMuted)
    }

    private fun stopRecordingAndFinish() {
        recording?.stop()
        recording = null
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(baseContext, it) == PackageManager.PERMISSION_GRANTED
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            if (allPermissionsGranted()) startCamera() else finish()
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (recording != null) {
            discardOnFinalize = true
            recording?.stop()
            recording = null
            Toast.makeText(this, "Recording discarded (left app)", Toast.LENGTH_LONG).show()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (recording != null) {
            discardOnFinalize = true
            recording?.stop()
            recording = null
        }
        cameraExecutor.shutdown()
        stopBlinking()
    }

    private fun startBlinking() {
        tvWarning.visibility = View.VISIBLE
        blinkingAnimator = ObjectAnimator.ofFloat(tvWarning, "alpha", 0f, 1f).apply {
            duration = 500
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            start()
        }
    }

    private fun stopBlinking() {
        blinkingAnimator?.cancel()
        tvWarning.visibility = View.GONE
        tvWarning.alpha = 1f
    }

    private fun setupTapToFocus() {
        viewFinder.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_DOWN) {
                val factory = viewFinder.meteringPointFactory
                val point = factory.createPoint(event.x, event.y)
                val action = FocusMeteringAction.Builder(point)
                    .setAutoCancelDuration(3, TimeUnit.SECONDS)
                    .build()
                camera?.cameraControl?.startFocusAndMetering(action)
                showFocusIndicator(event.x, event.y)
                return@setOnTouchListener true
            }
            false
        }
    }

    private fun showFocusIndicator(x: Float, y: Float) {
        val location = IntArray(2)
        viewFinder.getLocationOnScreen(location)
        val actualX = x + location[0]
        val actualY = y + location[1]
        focusRing.visibility = View.VISIBLE
        focusRing.x = actualX - focusRing.width / 2
        focusRing.y = actualY - focusRing.height / 2
        focusRing.scaleX = 1f
        focusRing.scaleY = 1f
        focusRing.alpha = 1f
        focusRing.animate()
            .scaleX(1.2f)
            .scaleY(1.2f)
            .alpha(0f)
            .setDuration(500)
            .withEndAction { focusRing.visibility = View.GONE }
            .start()
    }

    companion object {
        const val EXTRA_FILENAME = "EXTRA_FILENAME"
        const val EXTRA_FOLDER = "EXTRA_FOLDER"

        private const val FILENAME_FORMAT = "yyyy-MM-dd-HH-mm-ss-SSS"
        private const val REQUEST_CODE_PERMISSIONS = 11
        private val REQUIRED_PERMISSIONS =
            mutableListOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO).apply {
                if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }.toTypedArray()
    }
}
