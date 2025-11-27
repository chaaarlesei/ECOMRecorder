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
import android.widget.Button
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
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import org.json.JSONObject
import androidx.camera.core.CameraEffect
import androidx.camera.core.UseCaseGroup
import androidx.camera.effects.OverlayEffect
import androidx.core.util.Consumer
import android.graphics.Color
import android.graphics.Paint
import android.os.Handler
import android.os.Looper

class ContinuousCaptureActivity : AppCompatActivity() {
    private lateinit var viewFinder: androidx.camera.view.PreviewView
    private lateinit var tvStatus: TextView
    private lateinit var tvWarning: TextView
    private lateinit var chronometer: Chronometer
    private lateinit var btnCapture: Button
    private lateinit var btnTestApi: Button
    private var videoCapture: VideoCapture<Recorder>? = null
    private var recording: Recording? = null
    private lateinit var cameraExecutor: ExecutorService
    private var barcodeBuffer = StringBuilder()
    private var blinkingAnimator: ObjectAnimator? = null

    // Variables for video splitting logic
    private var pendingFilename: String? = null
    private var shouldRestartRecording = false

    private var overlayEffect: OverlayEffect? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_continuous_capture)
        viewFinder = findViewById(R.id.viewFinder)
        tvStatus = findViewById(R.id.tv_status)
        tvWarning = findViewById(R.id.tv_warning)
        chronometer = findViewById(R.id.chronometer)
        btnCapture = findViewById(R.id.btn_capture)
        btnTestApi = findViewById(R.id.btn_test_api)

        startBlinking()

        btnCapture.setOnClickListener {
            if (recording != null) {
                recording?.stop()
                recording = null
            } else {
                startRecording()
            }
        }

        btnTestApi.setOnClickListener {
            processScan("Test")
        }

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
                CameraEffect.VIDEO_CAPTURE, // Target only the video recording
                0, // Queue depth of 0 for minimal latency
                Handler(Looper.getMainLooper()),
                { t -> Log.e(TAG, "Overlay error", t) }
            ).apply {
                setOnDrawListener { frame ->
                    val canvas = frame.overlayCanvas

                    // 1. CLEAR THE PREVIOUS FRAME (Fixes the "multiple layers" / ghosting)
                    canvas.drawColor(0, android.graphics.PorterDuff.Mode.CLEAR)

                    // 2. Setup Paint for clear visibility (White text with Black shadow)
                    val textPaint = Paint().apply {
                        color = Color.WHITE
                        textSize = 60f // Adjust size as needed
                        isAntiAlias = true
                        style = Paint.Style.FILL
                        // Add a shadow to make it readable on bright backgrounds
                        setShadowLayer(10f, 0f, 0f, Color.BLACK)
                        // Align text to the right so it stays anchored to the corner
                        textAlign = Paint.Align.RIGHT
                    }

                    // 3. Generate Timestamp
                    val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
                        .format(System.currentTimeMillis())

                    // 4. Calculate dynamic position (Bottom-Right corner with padding)
                    val padding = 50f
                    val x = frame.size.width - padding
                    val y = frame.size.height - padding

                    // 5. Draw the timestamp
                    canvas.drawText(timestamp, x, y, textPaint)

                    true // Return true to indicate we drew the frame
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
            } catch (exc: Exception) {
                Log.e(TAG, "Use case binding failed", exc)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun startRecording() {
        val videoCapture = this.videoCapture ?: return

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
                        stopBlinking()
                        tvStatus.text = "Recording..."
                        btnCapture.text = "Stop Recording"
                        chronometer.base = SystemClock.elapsedRealtime()
                        chronometer.start()
                    }
                    is VideoRecordEvent.Finalize -> {
                        chronometer.stop()
                        chronometer.base = SystemClock.elapsedRealtime()
                        tvStatus.text = "Ready"
                        btnCapture.text = "Start Recording"
                        startBlinking()
                        
                        if (!recordEvent.hasError()) {
                            val outputUri = recordEvent.outputResults.outputUri
                            val msg = "Video saved: $outputUri"
                            
                            // Rename file if a pending filename exists
                            if (pendingFilename != null) {
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
                                    Toast.makeText(baseContext, msg, Toast.LENGTH_SHORT).show()
                                }
                                pendingFilename = null
                            } else {
                                Toast.makeText(baseContext, msg, Toast.LENGTH_SHORT).show()
                            }
                            Log.d(TAG, msg)
                        } else {
                            recording?.close()
                            recording = null
                            Log.e(TAG, "Video capture ends with error: ${recordEvent.error}")
                        }
                        recording = null

                        // Automatically restart if requested (triggered by scan)
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
        // 1. Send to API
        sendBarcode(code)

        // 2. If recording, stop current video, queue rename, and set flag to restart
        if (recording != null) {
            pendingFilename = "$code.mp4"
            shouldRestartRecording = true
            recording?.stop()
            recording = null
        }
    }

    private fun sendBarcode(code: String) {
        cameraExecutor.execute {
            try {
                val url = URL("http://192.168.1.153:3000/pack")
                with(url.openConnection() as HttpURLConnection) {
                    requestMethod = "POST"
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json")

                    val json = JSONObject()
                    json.put("trackingNo", code)
                    val jsonString = json.toString()

                    outputStream.write(jsonString.toByteArray())
                    outputStream.flush()

                    val responseCode = responseCode
                    if (responseCode in 200..299) {
                        runOnUiThread {
                            Toast.makeText(this@ContinuousCaptureActivity, "Scanned: $code", Toast.LENGTH_SHORT).show()
                        }
                    } else {
                        Log.e(TAG, "Server returned error: $responseCode")
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                Log.e(TAG, "Failed to send barcode", e)
            }
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
