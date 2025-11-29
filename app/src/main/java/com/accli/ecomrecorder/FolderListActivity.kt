package com.accli.ecomrecorder

import android.Manifest
import android.content.ContentUris
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PorterDuff
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.Size
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.io.File
import java.util.concurrent.Executors

class FolderListActivity : AppCompatActivity() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var emptyLayout: LinearLayout
    private lateinit var toolbar: androidx.appcompat.widget.Toolbar

    private val fileList = mutableListOf<File>()
    private var currentDirectory: File? = null
    private var rootDirectory: File? = null
    private val thumbnailExecutor = Executors.newFixedThreadPool(4)

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            loadFilesAndFolders(rootDirectory)
        } else {
            Toast.makeText(this, "Permission denied. Cannot list files.", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_folder_list)

        // Initialize Root Directory
        rootDirectory = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM),
            "Ecom"
        )
        currentDirectory = rootDirectory

        // Fix Status Bar Overlap
        val mainLayout = findViewById<View>(R.id.toolbar).parent as View
        ViewCompat.setOnApplyWindowInsetsListener(mainLayout) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        toolbar = findViewById(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        // Set Back Button Color to Black
        toolbar.navigationIcon?.setColorFilter(Color.BLACK, PorterDuff.Mode.SRC_ATOP)

        // Back Button Logic (Toolbar)
        toolbar.setNavigationOnClickListener {
            handleBackNavigation()
        }

        // Back Button Logic (System Back)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                handleBackNavigation()
            }
        })

        recyclerView = findViewById(R.id.recyclerView)
        // Ensure this ID matches your layout file
        emptyLayout = findViewById(R.id.layout_empty)

        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = FileAdapter(fileList) { file ->
            openFileOrFolder(file)
        }

        checkPermissionAndLoad()
    }

    private fun handleBackNavigation() {
        if (currentDirectory != null && rootDirectory != null &&
            currentDirectory != rootDirectory &&
            currentDirectory?.parentFile == rootDirectory) {
            // If inside a subfolder, go back to root
            loadFilesAndFolders(rootDirectory)
        } else {
            // If at root, close activity
            finish()
        }
    }

    private fun checkPermissionAndLoad() {
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_VIDEO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }

        if (ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED) {
            loadFilesAndFolders(currentDirectory)
        } else {
            permissionLauncher.launch(permission)
        }
    }

    private fun loadFilesAndFolders(directory: File?) {
        if (directory == null || !directory.exists()) {
            directory?.mkdirs()
        }

        currentDirectory = directory

        // Update Title
        supportActionBar?.title = if (directory == rootDirectory) "Recorded Videos" else directory?.name ?: "Unknown"

        fileList.clear()

        if (directory != null && directory.isDirectory) {
            // 1. Get sub-folders (excluding hidden ones starting with .)
            val subDirs = directory.listFiles { file ->
                file.isDirectory && !file.name.startsWith(".")
            }
            if (subDirs != null) {
                fileList.addAll(subDirs.sortedBy { it.name })
            }

            // 2. Get video files (excluding hidden ones)
            val videos = directory.listFiles { file ->
                file.isFile &&
                        !file.name.startsWith(".") &&
                        (file.name.endsWith(".mp4", ignoreCase = true))
            }
            if (videos != null) {
                fileList.addAll(videos.sortedByDescending { it.lastModified() })
            }
        }

        if (fileList.isEmpty()) {
            emptyLayout.visibility = View.VISIBLE
            recyclerView.visibility = View.GONE
        } else {
            emptyLayout.visibility = View.GONE
            recyclerView.visibility = View.VISIBLE
            recyclerView.adapter?.notifyDataSetChanged()
        }
    }

    private fun openFileOrFolder(file: File) {
        if (file.isDirectory) {
            // NAVIGATE INTO FOLDER (Internal)
            loadFilesAndFolders(file)
        } else {
            // OPEN VIDEO (External Player)
            playVideo(file)
        }
    }

    private fun playVideo(file: File) {
        try {
            val intent = Intent(Intent.ACTION_VIEW)
            val authority = "${packageName}.provider"

            // 1. Try MediaStore Content URI (Best for Players)
            var contentUri = getMediaUriFromFile(file)

            // 2. Fallback to FileProvider
            if (contentUri == null) {
                contentUri = FileProvider.getUriForFile(this, authority, file)
            }

            intent.setDataAndType(contentUri, "video/*")
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)

            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "Cannot play video: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun getMediaUriFromFile(file: File): Uri? {
        val projection = arrayOf(MediaStore.Video.Media._ID)
        val selection = "${MediaStore.Video.Media.DATA} = ?"
        val selectionArgs = arrayOf(file.absolutePath)

        contentResolver.query(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            projection,
            selection,
            selectionArgs,
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID))
                return ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id)
            }
        }
        return null
    }

    private fun loadThumbnail(file: File, imageView: ImageView) {
        // Set default system play icon
        imageView.setImageResource(android.R.drawable.ic_media_play)

        thumbnailExecutor.execute {
            var bitmap: Bitmap? = null
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                try {
                    val uri = getMediaUriFromFile(file)
                    if (uri != null) {
                        bitmap = contentResolver.loadThumbnail(uri, Size(128, 128), null)
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }

            if (bitmap != null) {
                runOnUiThread {
                    imageView.setImageBitmap(bitmap)
                }
            }
        }
    }

    inner class FileAdapter(
        private val list: List<File>,
        private val onClick: (File) -> Unit
    ) : RecyclerView.Adapter<FileAdapter.ViewHolder>() {

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val icon: ImageView = view.findViewById(R.id.iv_icon)
            val text: TextView = view.findViewById(R.id.tv_name)
            val overlayDark: View = view.findViewById(R.id.overlay_dark)
            val playIcon: ImageView = view.findViewById(R.id.iv_play_icon)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_file_list, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val file = list[position]
            holder.text.text = file.name
            holder.text.setTextColor(Color.BLACK) // Set text color to Black

            if (file.isDirectory) {
                // It's a folder - show folder icon, hide play overlay
                holder.icon.setImageResource(R.drawable.ic_folder)
                holder.overlayDark.visibility = View.GONE
                holder.playIcon.visibility = View.GONE
            } else {
                // It's a video file - load thumbnail and show play overlay
                loadThumbnail(file, holder.icon)
                holder.overlayDark.visibility = View.VISIBLE
                holder.playIcon.visibility = View.VISIBLE
            }

            holder.itemView.setOnClickListener { onClick(file) }
        }

        override fun getItemCount() = list.size
    }

    override fun onDestroy() {
        super.onDestroy()
        thumbnailExecutor.shutdown()
    }
}