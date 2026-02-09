package com.accli.ecomrecorder

import android.graphics.BitmapFactory
import android.os.Bundle
import android.widget.ImageView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton

class ImagePreviewActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_image_preview)

        val imageView = findViewById<ImageView>(R.id.iv_preview)
        val btnUse = findViewById<MaterialButton>(R.id.btn_use)
        val btnRetake = findViewById<MaterialButton>(R.id.btn_retake)

        val uriString = intent.getStringExtra(EXTRA_URI)
        if (uriString == null) {
            setResult(RESULT_CANCELED)
            finish()
            return
        }

        val uri = android.net.Uri.parse(uriString)
        val options = BitmapFactory.Options().apply { inSampleSize = 2 }
        val input = contentResolver.openInputStream(uri)
        val bitmap = if (input != null) {
            val bmp = BitmapFactory.decodeStream(input, null, options)
            input.close()
            bmp
        } else {
            null
        }
        if (bitmap != null) {
            imageView.setImageBitmap(bitmap)
        }

        btnUse.setOnClickListener {
            setResult(RESULT_OK)
            finish()
        }

        btnRetake.setOnClickListener {
            setResult(RESULT_CANCELED)
            finish()
        }
    }

    companion object {
        const val EXTRA_URI = "EXTRA_URI"
    }
}
