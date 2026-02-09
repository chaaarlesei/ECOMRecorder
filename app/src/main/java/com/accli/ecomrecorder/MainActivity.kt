package com.accli.ecomrecorder

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.ColorStateList
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.view.View
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.ActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdView
import com.google.android.gms.ads.MobileAds
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.zxing.integration.android.IntentIntegrator
import java.io.File

class MainActivity : AppCompatActivity() {

    private enum class CaptureType { VIDEO, IMAGE }

    private var activeFilenameEditText: EditText? = null
    private var activeDialog: AlertDialog? = null
    private var currentFolder: String = "Ecom"
    private var currentCaptureType: CaptureType = CaptureType.VIDEO

    private val singleVideoCaptureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result: ActivityResult ->
        if (result.resultCode == Activity.RESULT_OK) {
            Toast.makeText(this, "Video saved", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "Recording cancelled", Toast.LENGTH_SHORT).show()
        }
        startBarcodeScan()
    }

    private val singleImageCaptureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result: ActivityResult ->
        if (result.resultCode == Activity.RESULT_OK) {
            Toast.makeText(this, "Image saved", Toast.LENGTH_SHORT).show()
            startBarcodeScan()
        } else {
            Toast.makeText(this, "Capture cancelled", Toast.LENGTH_SHORT).show()
            startBarcodeScan()
        }
    }

    private val barcodeScanLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result: ActivityResult ->
        if (result.resultCode == RESULT_FIRST_USER) {
            promptForFilenameAndStart(currentFolder)
            return@registerForActivityResult
        }

        if (result.resultCode == Activity.RESULT_OK) {
            val scanResult = result.data?.getStringExtra("SCAN_RESULT")
            if (scanResult != null) {
                val scanned = scanResult
                Toast.makeText(this, "Scanned: $scanned", Toast.LENGTH_LONG).show()
                val sanitized = sanitizeFilename(scanned)
                startCaptureWithFilename(sanitized, currentFolder)
            }
        }
    }

    private val permissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { perms ->
        // Check core permissions (Camera & Audio are mandatory)
        val cameraGranted = perms[android.Manifest.permission.CAMERA] ?: false
        val audioGranted = if (currentCaptureType == CaptureType.VIDEO) {
            perms[android.Manifest.permission.RECORD_AUDIO] ?: false
        } else {
            true
        }

        if (cameraGranted && audioGranted) {
            // Permission granted
            createAppFolders()
            // START THE SCAN
            startBarcodeScan()
        } else {
            val message = if (currentCaptureType == CaptureType.VIDEO) {
                "Permissions required: Camera + Audio"
            } else {
                "Permission required: Camera"
            }
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        }
    }

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val level = intent.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, -1)
            val batteryPct = (level.toFloat() / scale.toFloat() * 100).toInt()

            val status = intent.getIntExtra(android.os.BatteryManager.EXTRA_STATUS, -1)
            val isCharging = status == android.os.BatteryManager.BATTERY_STATUS_CHARGING ||
                    status == android.os.BatteryManager.BATTERY_STATUS_FULL

            updateBatteryStatus(batteryPct, isCharging)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()
        setContentView(R.layout.activity_main)

        MobileAds.initialize(this)
        val adView = findViewById<AdView>(R.id.ad_view)
        adView.loadAd(AdRequest.Builder().build())

        createAppFolders()

        // Set dynamic version
        val tvVersion = findViewById<TextView>(R.id.tv_version)
        tvVersion.text = "v${BuildConfig.VERSION_NAME}"

        // Set dynamic copyright year
        val tvCopyright = findViewById<TextView>(R.id.tv_copyright)
        val currentYear = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)
        tvCopyright.text = "© $currentYear Asia Cargo Container Line, Inc."

        // Register Battery receiver
        try {
            val batteryFilter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
            registerReceiver(batteryReceiver, batteryFilter)
        } catch (e: Exception) {
            // Failed to register battery receiver
        }

        // Check initial battery status
        checkInitialBatteryStatus()

        // Check storage status
        checkStorageStatus()

        // Use MaterialCardView instead of Button
        val cardPack = findViewById<MaterialCardView>(R.id.card_pack)
        val cardReturn = findViewById<MaterialCardView>(R.id.card_return)
        val cardContinuous = findViewById<MaterialCardView>(R.id.card_continuous)
        val cardImage = findViewById<MaterialCardView>(R.id.card_image)
        val btnGallery = findViewById<MaterialButton>(R.id.btn_gallery)

        cardPack.setOnClickListener {
            currentFolder = "Pack"
            requestPermissionsAndPrompt("Pack", CaptureType.VIDEO)
        }

        cardReturn.setOnClickListener {
            currentFolder = "Return"
            requestPermissionsAndPrompt("Return", CaptureType.VIDEO)
        }

        cardImage.setOnClickListener {
            currentFolder = "Image"
            requestPermissionsAndPrompt("Image", CaptureType.IMAGE)
        }

        // Continuous Mode - Modal Dialog
        cardContinuous.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Scanner Check")
                .setMessage("Make sure the scanner is connected before proceeding.")
                .setPositiveButton("Proceed") { _, _ ->
                    val intent = Intent(this, ContinuousCaptureActivity::class.java)
                    startActivity(intent)
                }
                .setNegativeButton("Back", null) // Dismisses dialog
                .show()
        }

        btnGallery.setOnClickListener {
            val intent = Intent(this, FolderListActivity::class.java)
            startActivity(intent)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(batteryReceiver)
        } catch (e: Exception) {
            // Receiver not registered
        }
    }

    private fun checkInitialBatteryStatus() {
        val batteryStatus = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        batteryStatus?.let { intent ->
            val level = intent.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, -1)
            val batteryPct = (level.toFloat() / scale.toFloat() * 100).toInt()

            val status = intent.getIntExtra(android.os.BatteryManager.EXTRA_STATUS, -1)
            val isCharging = status == android.os.BatteryManager.BATTERY_STATUS_CHARGING ||
                    status == android.os.BatteryManager.BATTERY_STATUS_FULL

            updateBatteryStatus(batteryPct, isCharging)
        }
    }

    private fun updateBatteryStatus(percentage: Int, isCharging: Boolean) {
        val indicator = findViewById<View>(R.id.battery_status_indicator)
        val statusText = findViewById<TextView>(R.id.tv_battery_status)

        // Color based on battery level
        val color = when {
            percentage >= 60 -> "#4CAF50"  // Green
            percentage >= 30 -> "#FF9800"  // Orange
            else -> "#F44336"              // Red
        }

        indicator.backgroundTintList = ColorStateList.valueOf(Color.parseColor(color))

        val chargingText = if (isCharging) " (Charging)" else ""
        statusText.text = "Battery: $percentage%$chargingText"
    }

    private fun checkStorageStatus() {
        val indicator = findViewById<View>(R.id.storage_status_indicator)
        val statusText = findViewById<TextView>(R.id.tv_storage_status)

        try {
            val stat = android.os.StatFs(Environment.getExternalStorageDirectory().path)
            val bytesAvailable = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2) {
                stat.blockSizeLong * stat.availableBlocksLong
            } else {
                @Suppress("DEPRECATION")
                stat.blockSize.toLong() * stat.availableBlocks.toLong()
            }

            val bytesTotal = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2) {
                stat.blockSizeLong * stat.blockCountLong
            } else {
                @Suppress("DEPRECATION")
                stat.blockSize.toLong() * stat.blockCount.toLong()
            }

            // Convert to GB
            val gbAvailable = bytesAvailable / (1024.0 * 1024.0 * 1024.0)
            val gbTotal = bytesTotal / (1024.0 * 1024.0 * 1024.0)
            val percentageUsed = ((gbTotal - gbAvailable) / gbTotal * 100).toInt()

            // Color based on available storage
            val color = when {
                gbAvailable >= 5.0 -> "#4CAF50"   // Green - 5GB+ available
                gbAvailable >= 2.0 -> "#FF9800"   // Orange - 2-5GB available
                else -> "#F44336"                  // Red - Less than 2GB
            }

            indicator.backgroundTintList = ColorStateList.valueOf(Color.parseColor(color))
            statusText.text = "Storage: %.1f GB free (%.0f%% used)".format(gbAvailable, percentageUsed.toFloat())

            // Show warning if low storage
            if (gbAvailable < 2.0) {
                Toast.makeText(
                    this,
                    "Warning: Low storage space. Please free up space or transfer videos.",
                    Toast.LENGTH_LONG
                ).show()
            }

        } catch (e: Exception) {
            indicator.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#9E9E9E"))
            statusText.text = "Storage: Unable to check"
        }
    }

    private fun createAppFolders() {
        try {
            val dcim = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM)
            val ecom = File(dcim, "Ecom")
            if (!ecom.exists()) ecom.mkdirs()

            File(ecom, "Pack").mkdirs()
            File(ecom, "Return").mkdirs()
            File(ecom, "Continuous").mkdirs()
            File(ecom, "Image").mkdirs()
        } catch (e: Exception) {
            // Ignore errors
        }
    }

    private fun requestPermissionsAndPrompt(folder: String, captureType: CaptureType) {
        currentCaptureType = captureType
        val permissions = mutableListOf(
            android.Manifest.permission.CAMERA
        )

        if (captureType == CaptureType.VIDEO) {
            permissions.add(android.Manifest.permission.RECORD_AUDIO)
        }

        // Add storage permissions correctly based on Android Version
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
            // Android 9 (Pie) and below need Write permission to create files
            permissions.add(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }

        // Add Read permission for the folder viewer feature
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Android 13+
            permissions.add(android.Manifest.permission.READ_MEDIA_VIDEO)
        } else {
            // Android 12 and below
            permissions.add(android.Manifest.permission.READ_EXTERNAL_STORAGE)
        }

        permissionsLauncher.launch(permissions.toTypedArray())
    }

    private fun promptForFilenameAndStart(folder: String) {
        val edit = EditText(this)
        edit.hint = "Tracking Number (e.g. JT0123456789)"
        activeFilenameEditText = edit

        val builder = AlertDialog.Builder(this)
            .setTitle("Enter filename")
            .setView(edit)
            .setPositiveButton("Record") { _, _ ->
                var name = edit.text.toString().trim()
                if (name.isEmpty()) {
                    Toast.makeText(this, "Filename required", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val sanitized = sanitizeFilename(name)
                val finalName = when (currentCaptureType) {
                    CaptureType.VIDEO -> if (sanitized.endsWith(".mp4")) sanitized else "$sanitized.mp4"
                    CaptureType.IMAGE -> if (sanitized.endsWith(".jpg") || sanitized.endsWith(".jpeg")) sanitized else "$sanitized.jpg"
                }

                startCaptureWithFilename(finalName, folder)
            }
            .setNegativeButton("Cancel") { dialog, _ ->
                dialog.dismiss()
            }

        activeDialog = builder.show()
    }

    private fun startBarcodeScan() {
        val integrator = IntentIntegrator(this)
        integrator.setDesiredBarcodeFormats(IntentIntegrator.ALL_CODE_TYPES)
        integrator.setPrompt("Scan a barcode")
        integrator.setCameraId(0)
        integrator.setBeepEnabled(true)
        integrator.setBarcodeImageEnabled(true)
        integrator.captureActivity = CaptureActivity::class.java
        val intent = integrator.createScanIntent()
        intent.putExtra("MODE", currentFolder)
        barcodeScanLauncher.launch(intent)
    }

    private fun sanitizeFilename(name: String): String {
        return name.replace(Regex("[^a-zA-Z0-9._-]"), "_")
    }

    private fun startCaptureWithFilename(filename: String, folder: String) {
        when (currentCaptureType) {
            CaptureType.VIDEO -> startVideoCaptureWithFilename(filename, folder)
            CaptureType.IMAGE -> startImageCaptureWithFilename(filename, folder)
        }
    }

    private fun startVideoCaptureWithFilename(filename: String, folder: String) {
        val intent = Intent(this, SingleCaptureActivity::class.java).apply {
            putExtra(SingleCaptureActivity.EXTRA_FILENAME, filename)
            putExtra(SingleCaptureActivity.EXTRA_FOLDER, folder)
        }
        singleVideoCaptureLauncher.launch(intent)
    }

    private fun startImageCaptureWithFilename(filename: String, folder: String) {
        val intent = Intent(this, SingleImageCaptureActivity::class.java).apply {
            putExtra(SingleImageCaptureActivity.EXTRA_FILENAME, filename)
            putExtra(SingleImageCaptureActivity.EXTRA_FOLDER, folder)
        }
        singleImageCaptureLauncher.launch(intent)
    }
}
