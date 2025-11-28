package com.accli.ecomrecorder

import android.Manifest
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.ContentValues
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.widget.Chronometer
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
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
import androidx.camera.core.CameraEffect
import androidx.camera.core.UseCaseGroup
import androidx.camera.effects.OverlayEffect
import android.graphics.Color
import android.graphics.Paint
import android.os.Handler
import android.os.Looper
import androidx.appcompat.app.AlertDialog
import androidx.camera.core.Camera
import android.widget.ImageButton
import androidx.activity.OnBackPressedCallback

class ContinuousCaptureActivity : AppCompatActivity() {
    private lateinit var viewFinder: androidx.camera.view.PreviewView
    private lateinit var tvStatus: TextView
    private lateinit var tvWarning: TextView
    private lateinit var chronometer: Chronometer
    private lateinit var btnCapture: android.widget.ImageButton
    private var videoCapture: VideoCapture<Recorder>? = null
    private var recording: Recording? = null
    private lateinit var cameraExecutor: ExecutorService
    private var barcodeBuffer = StringBuilder()
    private var blinkingAnimator: ObjectAnimator? = null
    private var pendingFilename: String? = null
    private var shouldRestartRecording = false
    private var overlayEffect: OverlayEffect? = null
    private lateinit var btnFlash: ImageButton
    private var camera: Camera? = null
    private var isTorchOn = false
    private lateinit var btnPause: ImageButton
    private var isPaused = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_continuous_capture)
        viewFinder = findViewById(R.id.viewFinder)
        tvStatus = findViewById(R.id.tv_status)
        tvWarning = findViewById(R.id.tv_warning)
        chronometer = findViewById(R.id.chronometer)
        btnCapture = findViewById(R.id.btn_capture)
        btnFlash = findViewById(R.id.btn_flash)
        btnFlash.setOnClickListener {
            toggleFlash()
        }

        startBlinking()

        btnPause = findViewById(R.id.btn_pause)

        btnPause.setOnClickListener {
            if (recording != null) {
                if (isPaused) {
                    recording?.resume()
                } else {
                    recording?.pause()
                }
            }
        }

        btnCapture.setOnClickListener {
            if (recording != null) {
                // STOP LOGIC (Existing confirmation)
                AlertDialog.Builder(this)
                    .setTitle("Discard Recording?")
                    .setMessage("Stopping manually will discard the current video. Are you sure?")
                    .setPositiveButton("Stop & Discard") { _, _ ->
                        recording?.stop()
                        recording = null
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            } else {
                // START LOGIC (New confirmation with corrected grammar)
                AlertDialog.Builder(this)
                    .setTitle("Scanner Connected?")
                    .setMessage("Please make sure the scanner is connected to this device; otherwise, the recording will not be saved.")
                    .setPositiveButton("Start Recording") { _, _ ->
                        startRecording()
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (recording != null) {
                    // Recording is active -> Show Confirmation
                    AlertDialog.Builder(this@ContinuousCaptureActivity)
                        .setTitle("Discard Recording?")
                        .setMessage("Exiting now will discard the current recording. Are you sure?")
                        .setPositiveButton("Discard & Exit") { _, _ ->
                            // Stop the recording (triggers Finalize -> deletes file)
                            recording?.stop()
                            recording = null

                            // Proceed to exit the screen
                            isEnabled = false // Disable this callback so finish() works
                            finish()
                        }
                        .setNegativeButton("Cancel", null)
                        .show()
                } else {
                    // No recording -> Default back behavior (exit)
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })

        if (allPermissionsGranted()) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(
                this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS
            )
        }

        cameraExecutor = Executors.newSingleThreadExecutor()
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

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()

            // 1. Setup Preview
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(viewFinder.surfaceProvider)
            }

            // 2. Setup Recorder & VideoCapture
            val recorder = Recorder.Builder()
                .setQualitySelector(QualitySelector.from(Quality.HIGHEST))
                .build()
            videoCapture = VideoCapture.withOutput(recorder)

            // 3. Create the OverlayEffect (The OpenGL/MediaCodec magic)
            // This targets the VIDEO_CAPTURE use case specifically.
            overlayEffect = OverlayEffect(
                CameraEffect.VIDEO_CAPTURE,
                0,
                Handler(Looper.getMainLooper()),
                { t -> Log.e(TAG, "Overlay error", t) }
            ).apply {
                setOnDrawListener { frame ->
                    val canvas = frame.overlayCanvas

                    // 1. Clear the canvas to prevent "ghosting"
                    canvas.drawColor(0, android.graphics.PorterDuff.Mode.CLEAR)

                    // 2. Setup Paint (Smaller text size)
                    val textPaint = Paint().apply {
                        color = Color.WHITE
                        textSize = 30f // Reduced size (was 60f)
                        isAntiAlias = true
                        style = Paint.Style.FILL
                        setShadowLayer(4f, 0f, 0f, Color.BLACK) // Shadow for visibility
                        textAlign = Paint.Align.RIGHT // Align to the right side
                    }

                    // 3. Generate the combined text
                    val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
                        .format(System.currentTimeMillis())
                    val address = "15 Sta. Maria Drive, ACCLI, Taguig"
                    val fullText = "$timestamp $address"

                    // 4. Calculate position (Bottom-Right corner)
                    val padding = 30f
                    val x = frame.size.width - padding
                    val y = frame.size.height - padding

                    // 5. Draw the text
                    canvas.drawText(fullText, x, y, textPaint)

                    true
                }
            }

            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                cameraProvider.unbindAll()

                // 4. Bind using UseCaseGroup to include the Effect
                // We must use UseCaseGroup to attach the effect to the binding
                val useCaseGroup = UseCaseGroup.Builder()
                    .addUseCase(preview)
                    .addUseCase(videoCapture!!)
                    .addEffect(overlayEffect!!) // Add the overlay effect here
                    .build()

                cameraProvider.bindToLifecycle(
                    this, cameraSelector, useCaseGroup
                )

                try {
                    cameraProvider.unbindAll()

                    // UPDATE THIS BLOCK: Assign the result to 'this.camera'
                    this.camera = cameraProvider.bindToLifecycle(
                        this, cameraSelector, useCaseGroup
                    )

                    // Optional: Reset flash state on camera start
                    isTorchOn = false
                    updateFlashIcon()

                } catch (exc: Exception) {
                    Log.e(TAG, "Use case binding failed", exc)
                }

            } catch (exc: Exception) {
                Log.e(TAG, "Use case binding failed", exc)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun toggleFlash() {
        val cam = camera ?: return // If camera isn't ready, do nothing

        if (cam.cameraInfo.hasFlashUnit()) {
            isTorchOn = !isTorchOn
            cam.cameraControl.enableTorch(isTorchOn)
            updateFlashIcon()
        } else {
            Toast.makeText(this, "Flash not available", Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateFlashIcon() {
        val icon = if (isTorchOn) R.drawable.ic_flash_on else R.drawable.ic_flash_off
        btnFlash.setImageResource(icon)
    }

    private fun startRecording() {
        val videoCapture = this.videoCapture ?: return

        // Create a temporary filename (will be renamed or deleted later)
        val name = SimpleDateFormat(FILENAME_FORMAT, Locale.US)
            .format(System.currentTimeMillis())

        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            if (Build.VERSION.SDK_INT > Build.VERSION_CODES.P) {
                put(MediaStore.Video.Media.RELATIVE_PATH, "DCIM/Ecom/Continuous")
            }
        }

        val mediaStoreOutputOptions = MediaStoreOutputOptions
            .Builder(contentResolver, MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
            .setContentValues(contentValues)
            .build()

        recording = videoCapture.output
            .prepareRecording(this, mediaStoreOutputOptions)
            .apply {
                if (PermissionChecker.checkSelfPermission(
                        this@ContinuousCaptureActivity,
                        Manifest.permission.RECORD_AUDIO
                    ) == PermissionChecker.PERMISSION_GRANTED
                ) {
                    withAudioEnabled()
                }
            }
            .start(ContextCompat.getMainExecutor(this)) { recordEvent ->
                when (recordEvent) {
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
                    }

                    is VideoRecordEvent.Pause -> {
                        // PAUSED
                        isPaused = true
                        btnPause.setImageResource(R.drawable.ic_resume)
                        tvStatus.text = "Paused"
                        chronometer.stop()
                        startBlinking()
                    }

                    is VideoRecordEvent.Resume -> {
                        // RESUMED
                        isPaused = false
                        btnPause.setImageResource(R.drawable.ic_pause) // Change back to Pause icon
                        tvStatus.text = "Recording..."
                        stopBlinking()

                        val timePassed = SystemClock.elapsedRealtime() - recordEvent.recordingStats.recordedDurationNanos / 1000000
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

                        if (!recordEvent.hasError()) {
                            val outputUri = recordEvent.outputResults.outputUri

                            // CHECK: Was this triggered by a scan?
                            if (pendingFilename != null) {
                                // YES -> Rename and keep the file
                                try {
                                    val values = ContentValues().apply {
                                        put(MediaStore.MediaColumns.DISPLAY_NAME, pendingFilename)
                                    }
                                    contentResolver.update(outputUri, values, null, null)
                                    val renameMsg = "Saved as $pendingFilename"
                                    Toast.makeText(baseContext, renameMsg, Toast.LENGTH_SHORT).show()
                                    Log.d(TAG, renameMsg)
                                } catch (e: Exception) {
                                    Log.e(TAG, "Failed to rename video", e)
                                    Toast.makeText(baseContext, "Saved (rename failed)", Toast.LENGTH_SHORT).show()
                                }
                                // Reset the flag
                                pendingFilename = null
                            } else {
                                // NO (Manual Stop) -> Delete/Discard the file
                                try {
                                    contentResolver.delete(outputUri, null, null)
                                    val discardMsg = "Recording discarded (not scanned)"
                                    Toast.makeText(baseContext, discardMsg, Toast.LENGTH_SHORT).show()
                                    Log.d(TAG, discardMsg)
                                } catch (e: Exception) {
                                    Log.e(TAG, "Failed to delete discarded video", e)
                                }
                            }
                        } else {
                            // Handle actual recording errors
                            recording?.close()
                            recording = null
                            Log.e(TAG, "Video capture ends with error: ${recordEvent.error}")
                        }

                        recording = null

                        // Automatically restart loop if it was a scan event
                        if (shouldRestartRecording) {
                            shouldRestartRecording = false
                            startRecording()
                        }
                    }
                }
            }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            val char = event.unicodeChar.toChar()
            if (event.keyCode == KeyEvent.KEYCODE_ENTER) {
                val scannedCode = barcodeBuffer.toString().trim()
                if (scannedCode.isNotEmpty()) {
                    processScan(scannedCode)
                }
                barcodeBuffer.clear()
                return true
            } else {
                if (char.isLetterOrDigit() || "-_.".contains(char)) {
                    barcodeBuffer.append(char)
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun processScan(code: String) {
        if (recording != null) {
            pendingFilename = "$code.mp4"
            shouldRestartRecording = true
            recording?.stop()
            recording = null
        }
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(baseContext, it) == PackageManager.PERMISSION_GRANTED
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            if (allPermissionsGranted()) {
                startCamera()
            } else {
                Toast.makeText(this, "Permissions not granted by the user.", Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        recording?.stop()
        stopBlinking()
    }

    companion object {
        private const val TAG = "ContinuousCapture"
        private const val FILENAME_FORMAT = "yyyy-MM-dd-HH-mm-ss-SSS"
        private const val REQUEST_CODE_PERMISSIONS = 10
        private val REQUIRED_PERMISSIONS =
            mutableListOf(
                Manifest.permission.CAMERA,
                Manifest.permission.RECORD_AUDIO
            ).apply {
                if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
                    add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                }
            }.toTypedArray()
    }
}
