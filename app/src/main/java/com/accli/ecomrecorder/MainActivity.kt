package com.accli.ecomrecorder

import android.app.Activity
import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.activity.result.ActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.accli.ecomrecorder.CaptureActivity
import com.google.zxing.integration.android.IntentIntegrator

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
        val granted = perms.entries.all { it.value }
        if (granted) {
            startBarcodeScan()
        } else {
            Toast.makeText(this, "Permissions required: Camera + Audio", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val btnPack = findViewById<Button>(R.id.btn_pack)
        val btnReturn = findViewById<Button>(R.id.btn_return)

        btnPack.setOnClickListener {
            currentFolder = "Pack"
            requestPermissionsAndPrompt("Pack")
        }

        btnReturn.setOnClickListener {
            currentFolder = "Return"
            requestPermissionsAndPrompt("Return")
        }
    }

    private fun requestPermissionsAndPrompt(folder: String) {
        permissionsLauncher.launch(
            arrayOf(
                android.Manifest.permission.CAMERA,
                android.Manifest.permission.RECORD_AUDIO,
            )
        )
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