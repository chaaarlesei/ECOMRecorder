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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
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
        ActivityResultContracts.RequestMultiplePermissions()
    ) { perms ->
        val granted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val videoGranted = perms[Manifest.permission.READ_MEDIA_VIDEO] ?: false
            val imageGranted = perms[Manifest.permission.READ_MEDIA_IMAGES] ?: false
            videoGranted || imageGranted
        } else {
            perms[Manifest.permission.READ_EXTERNAL_STORAGE] ?: false
        }

        if (granted) {
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val videoGranted = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.READ_MEDIA_VIDEO
            ) == PackageManager.PERMISSION_GRANTED
            val imageGranted = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.READ_MEDIA_IMAGES
            ) == PackageManager.PERMISSION_GRANTED

            if (videoGranted || imageGranted) {
                loadFilesAndFolders(currentDirectory)
            } else {
                permissionLauncher.launch(
                    arrayOf(
                        Manifest.permission.READ_MEDIA_VIDEO,
                        Manifest.permission.READ_MEDIA_IMAGES
                    )
                )
            }
        } else {
            val permission = Manifest.permission.READ_EXTERNAL_STORAGE
            if (ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED) {
                loadFilesAndFolders(currentDirectory)
            } else {
                permissionLauncher.launch(arrayOf(permission))
            }
        }
    }

    private fun loadFilesAndFolders(directory: File?) {
        if (directory == null || !directory.exists()) {
            directory?.mkdirs()
        }

        currentDirectory = directory

        // Update Title
        supportActionBar?.title = if (directory == rootDirectory) "Recorded Media" else directory?.name ?: "Unknown"

        fileList.clear()

        if (directory != null && directory.isDirectory) {
            // 1. Get sub-folders (excluding hidden ones starting with .)
            val subDirs = directory.listFiles { file ->
                file.isDirectory && !file.name.startsWith(".")
            }
            if (subDirs != null) {
                fileList.addAll(subDirs.sortedBy { it.name })
            }

            // 2. Get media files (excluding hidden ones)
            val mediaFiles = directory.listFiles { file ->
                file.isFile &&
                        !file.name.startsWith(".") &&
                        (isVideoFile(file) || isImageFile(file))
            }
            if (mediaFiles != null) {
                fileList.addAll(mediaFiles.sortedByDescending { it.lastModified() })
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
            // OPEN MEDIA (External Viewer)
            if (isVideoFile(file)) {
                playVideo(file)
            } else if (isImageFile(file)) {
                viewImage(file)
            } else {
                Toast.makeText(this, "Unsupported file type", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun playVideo(file: File) {
        try {
            val intent = Intent(Intent.ACTION_VIEW)
            val authority = "${packageName}.provider"

            // 1. Try MediaStore Content URI (Best for Players)
            var contentUri = getMediaUriFromFile(file, true)

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

    private fun viewImage(file: File) {
        try {
            val intent = Intent(Intent.ACTION_VIEW)
            val authority = "${packageName}.provider"

            // 1. Try MediaStore Content URI
            var contentUri = getMediaUriFromFile(file, false)

            // 2. Fallback to FileProvider
            if (contentUri == null) {
                contentUri = FileProvider.getUriForFile(this, authority, file)
            }

            intent.setDataAndType(contentUri, "image/*")
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)

            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "Cannot open image: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun getMediaUriFromFile(file: File, isVideo: Boolean): Uri? {
        val projection = arrayOf(MediaStore.MediaColumns._ID)
        val selection = "${MediaStore.MediaColumns.DATA} = ?"
        val selectionArgs = arrayOf(file.absolutePath)
        val contentUri = if (isVideo) {
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        } else {
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        }

        contentResolver.query(
            contentUri,
            projection,
            selection,
            selectionArgs,
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID))
                return ContentUris.withAppendedId(contentUri, id)
            }
        }
        return null
    }

    private fun loadThumbnail(file: File, imageView: ImageView, isVideo: Boolean) {
        if (isVideo) {
            imageView.setImageResource(android.R.drawable.ic_media_play)
        } else {
            imageView.setImageResource(android.R.drawable.ic_menu_gallery)
        }

        thumbnailExecutor.execute {
            var bitmap: Bitmap? = null
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                try {
                    val uri = getMediaUriFromFile(file, isVideo)
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
            val date: TextView = view.findViewById(R.id.tv_date)
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
                holder.date.visibility = View.GONE
            } else {
                holder.date.visibility = View.VISIBLE
                holder.date.text = formatFileDate(file.lastModified())
                if (isVideoFile(file)) {
                    // It's a video file - load thumbnail and show play overlay
                    loadThumbnail(file, holder.icon, true)
                    holder.overlayDark.visibility = View.VISIBLE
                    holder.playIcon.visibility = View.VISIBLE
                } else {
                    // It's an image file - load thumbnail without overlay
                    loadThumbnail(file, holder.icon, false)
                    holder.overlayDark.visibility = View.GONE
                    holder.playIcon.visibility = View.GONE
                }
            }

            holder.itemView.setOnClickListener { onClick(file) }
        }

        override fun getItemCount() = list.size
    }

    override fun onDestroy() {
        super.onDestroy()
        thumbnailExecutor.shutdown()
    }

    private fun isVideoFile(file: File): Boolean {
        return file.name.endsWith(".mp4", ignoreCase = true)
    }

    private fun isImageFile(file: File): Boolean {
        return file.name.endsWith(".jpg", ignoreCase = true) ||
                file.name.endsWith(".jpeg", ignoreCase = true) ||
                file.name.endsWith(".png", ignoreCase = true)
    }

    private fun formatFileDate(timestamp: Long): String {
        val formatter = SimpleDateFormat("MMM dd, yyyy h:mm a", Locale.US)
        return formatter.format(Date(timestamp))
    }
}
