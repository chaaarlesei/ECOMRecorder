package com.accli.ecomrecorder

import android.app.Activity
import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.activity.result.ActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.accli.ecomrecorder.CaptureActivity
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.zxing.integration.android.IntentIntegrator
import java.io.File

class MainActivity : AppCompatActivity() {

    private var videoUri: Uri? = null
    private var activeFilenameEditText: EditText? = null
    private var activeDialog: AlertDialog? = null
    private var currentFolder: String = "Ecom"

    private val videoCaptureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result: ActivityResult ->
        if (result.resultCode == Activity.RESULT_OK) {
            Toast.makeText(this, "Video saved", Toast.LENGTH_SHORT).show()
            startBarcodeScan()
        } else {
            videoUri?.let { uri ->
                try {
                    contentResolver.delete(uri, null, null)
                } catch (_: Exception) {
                }
            }
            videoUri = null
            Toast.makeText(this, "Recording cancelled", Toast.LENGTH_SHORT).show()
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
                startVideoCaptureWithFilename(sanitized, currentFolder)
            }
        }
    }

    private val permissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { perms ->
        // Check core permissions (Camera & Audio are mandatory)
        val cameraGranted = perms[android.Manifest.permission.CAMERA] ?: false
        val audioGranted = perms[android.Manifest.permission.RECORD_AUDIO] ?: false

        if (cameraGranted && audioGranted) {
            // Permission granted
            createAppFolders()
            // START THE SCAN
            startBarcodeScan()
        } else {
            Toast.makeText(this, "Permissions required: Camera + Audio", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()
        setContentView(R.layout.activity_main)

        createAppFolders()

        val btnPack = findViewById<Button>(R.id.btn_pack)
        val btnReturn = findViewById<Button>(R.id.btn_return)
        val btnContinuous = findViewById<Button>(R.id.btn_continuous)
        val btnFolder = findViewById<FloatingActionButton>(R.id.btn_folder)

        btnPack.setOnClickListener {
            currentFolder = "Pack"
            requestPermissionsAndPrompt("Pack")
        }

        btnReturn.setOnClickListener {
            currentFolder = "Return"
            requestPermissionsAndPrompt("Return")
        }

        btnContinuous.setOnClickListener {
            val intent = Intent(this, ContinuousCaptureActivity::class.java)
            startActivity(intent)
        }

        btnFolder.setOnClickListener {
            val intent = Intent(this, FolderListActivity::class.java)
            startActivity(intent)
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
        } catch (e: Exception) {
            // Ignore errors
        }
    }

    private fun requestPermissionsAndPrompt(folder: String) {
        val permissions = mutableListOf(
            android.Manifest.permission.CAMERA,
            android.Manifest.permission.RECORD_AUDIO
        )

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
                if (!sanitized.endsWith(".mp4")) name = "$sanitized.mp4"

                startVideoCaptureWithFilename(name, folder)
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

    private fun startVideoCaptureWithFilename(filename: String, folder: String) {
        val relativePath = "DCIM/Ecom/$folder"

        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, filename)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, relativePath)
        }

        val resolver = contentResolver
        try {
            val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            if (uri == null) {
                Toast.makeText(this, "Failed to create file", Toast.LENGTH_SHORT).show()
                return
            }
            videoUri = uri

            val intent = Intent(MediaStore.ACTION_VIDEO_CAPTURE).apply {
                putExtra(MediaStore.EXTRA_OUTPUT, uri)
            }

            videoCaptureLauncher.launch(intent)

        } catch (e: Exception) {
            Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
}