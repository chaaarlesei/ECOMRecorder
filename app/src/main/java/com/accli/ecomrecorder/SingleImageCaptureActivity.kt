package com.accli.ecomrecorder

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.view.MotionEvent
import android.view.View
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.exifinterface.media.ExifInterface
import java.io.InputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class SingleImageCaptureActivity : AppCompatActivity() {
    private lateinit var viewFinder: androidx.camera.view.PreviewView
    private lateinit var tvStatus: TextView
    private lateinit var tvWarning: TextView
    private lateinit var btnCapture: ImageButton
    private lateinit var btnFlash: ImageButton
    private lateinit var btnSettings: ImageButton
    private lateinit var btnMute: ImageButton
    private lateinit var btnPause: ImageButton
    private lateinit var focusRing: View

    private var imageCapture: ImageCapture? = null
    private lateinit var cameraExecutor: ExecutorService
    private var camera: Camera? = null
    private var isTorchOn = false

    private var pendingFilename: String? = null
    private var targetFolder: String = "Image"
    private var lastCapturedUri: android.net.Uri? = null
    private val previewLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val uri = lastCapturedUri
        if (uri == null) {
            tvStatus.text = "Ready"
            return@registerForActivityResult
        }

        if (result.resultCode == RESULT_OK) {
            stampTimestamp(uri)
            tvStatus.text = "Ready"
            setResult(RESULT_OK)
            finish()
        } else {
            try {
                contentResolver.delete(uri, null, null)
            } catch (_: Exception) {
            }
            lastCapturedUri = null
            tvStatus.text = "Ready"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_continuous_capture)

        pendingFilename = intent.getStringExtra(EXTRA_FILENAME)
        targetFolder = intent.getStringExtra(EXTRA_FOLDER) ?: "Image"

        viewFinder = findViewById(R.id.viewFinder)
        tvStatus = findViewById(R.id.tv_status)
        tvWarning = findViewById(R.id.tv_warning)
        btnCapture = findViewById(R.id.btn_capture)
        btnFlash = findViewById(R.id.btn_flash)
        btnSettings = findViewById(R.id.btn_settings)
        btnMute = findViewById(R.id.btn_mute)
        btnPause = findViewById(R.id.btn_pause)
        focusRing = findViewById(R.id.focus_ring)

        tvStatus.text = "Ready"
        tvWarning.text = ""
        tvWarning.visibility = View.GONE
        findViewById<View>(R.id.chronometer).visibility = View.GONE

        btnSettings.visibility = View.GONE
        btnMute.visibility = View.GONE
        btnPause.visibility = View.GONE
        btnCapture.setImageResource(R.drawable.ic_record_start)
        btnCapture.setColorFilter(Color.WHITE)

        btnFlash.setOnClickListener { toggleFlash() }
        btnCapture.setOnClickListener { takePhoto() }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                isEnabled = false
                onBackPressedDispatcher.onBackPressed()
            }
        })

        if (allPermissionsGranted()) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS)
        }

        cameraExecutor = Executors.newSingleThreadExecutor()
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(viewFinder.surfaceProvider)
            }

            imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .build()

            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
            try {
                cameraProvider.unbindAll()
                camera = cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageCapture)
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

    private fun takePhoto() {
        val capture = imageCapture ?: return

        val finalName = ensureJpegExtension(pendingFilename ?: timestampName())
        val relativePath = "DCIM/Ecom/$targetFolder"

        val contentValues = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, finalName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT > Build.VERSION_CODES.P) {
                put(MediaStore.Images.Media.RELATIVE_PATH, relativePath)
            }
        }

        val outputOptions = ImageCapture.OutputFileOptions
            .Builder(contentResolver, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
            .build()

        tvStatus.text = "Capturing..."

        capture.takePicture(
            outputOptions,
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                    val uri = outputFileResults.savedUri
                    if (uri != null) {
                        lastCapturedUri = uri
                        val intent = android.content.Intent(
                            this@SingleImageCaptureActivity,
                            ImagePreviewActivity::class.java
                        ).apply {
                            putExtra(ImagePreviewActivity.EXTRA_URI, uri.toString())
                        }
                        previewLauncher.launch(intent)
                        return
                    }
                    tvStatus.text = "Ready"
                    setResult(RESULT_OK)
                    finish()
                }

                override fun onError(exception: ImageCaptureException) {
                    tvStatus.text = "Ready"
                    Toast.makeText(this@SingleImageCaptureActivity, "Capture failed", Toast.LENGTH_SHORT).show()
                    setResult(RESULT_CANCELED)
                    finish()
                }
            }
        )
    }

    private fun stampTimestamp(uri: android.net.Uri) {
        try {
            val input = contentResolver.openInputStream(uri) ?: return
            val original = BitmapFactory.decodeStream(input)
            input.close()
            if (original == null) return

            val rotated = rotateIfNeeded(uri, original)
            val mutable = rotated.copy(Bitmap.Config.ARGB_8888, true)

            val canvas = Canvas(mutable)
            val paint = Paint().apply {
                color = Color.WHITE
                textSize = 40f
                isAntiAlias = true
                style = Paint.Style.FILL
                setShadowLayer(6f, 0f, 0f, Color.BLACK)
                textAlign = Paint.Align.RIGHT
            }

            val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
                .format(System.currentTimeMillis())
            val address = "15 Sta. Maria Drive, ACCLI, Taguig"
            val fullText = "$timestamp $address"
            val padding = 40f
            val x = mutable.width - padding
            val y = mutable.height - padding
            canvas.drawText(fullText, x, y, paint)

            val output: OutputStream? = contentResolver.openOutputStream(uri, "rwt")
            output?.use { out ->
                mutable.compress(Bitmap.CompressFormat.JPEG, 95, out)
            }
        } catch (_: Exception) {
        }
    }

    private fun rotateIfNeeded(uri: android.net.Uri, bitmap: Bitmap): Bitmap {
        val rotation = readExifRotation(uri)
        if (rotation == 0) return bitmap
        val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    private fun readExifRotation(uri: android.net.Uri): Int {
        return try {
            val input: InputStream? = contentResolver.openInputStream(uri)
            if (input != null) {
                val exif = ExifInterface(input)
                input.close()
                when (exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> 90
                    ExifInterface.ORIENTATION_ROTATE_180 -> 180
                    ExifInterface.ORIENTATION_ROTATE_270 -> 270
                    else -> 0
                }
            } else {
                0
            }
        } catch (_: Exception) {
            0
        }
    }

    private fun toggleFlash() {
        val cam = camera ?: return
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

    private fun timestampName(): String {
        return SimpleDateFormat("yyyy-MM-dd-HH-mm-ss-SSS", Locale.US)
            .format(System.currentTimeMillis())
    }

    private fun ensureJpegExtension(name: String): String {
        return if (name.endsWith(".jpg", true) || name.endsWith(".jpeg", true)) {
            name
        } else {
            "$name.jpg"
        }
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

    override fun onDestroy() {
        super.onDestroy()
        if (lastCapturedUri != null) {
            lastCapturedUri = null
        }
        cameraExecutor.shutdown()
    }

    companion object {
        const val EXTRA_FILENAME = "EXTRA_FILENAME"
        const val EXTRA_FOLDER = "EXTRA_FOLDER"

        private const val REQUEST_CODE_PERMISSIONS = 12
        private val REQUIRED_PERMISSIONS =
            mutableListOf(Manifest.permission.CAMERA).apply {
                if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }.toTypedArray()
    }
}
