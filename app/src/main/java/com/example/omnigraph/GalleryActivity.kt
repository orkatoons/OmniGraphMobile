package com.example.omnigraph

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.io.File

class GalleryActivity : AppCompatActivity() {
    private lateinit var recyclerView: RecyclerView
    private lateinit var emptyView: TextView
    private var mediaPlayer: MediaPlayer? = null
    private val STORAGE_PERMISSION_CODE = 100

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_gallery)

        recyclerView = findViewById(R.id.galleryRecyclerView)
        emptyView = findViewById(R.id.emptyView)

        recyclerView.layoutManager = GridLayoutManager(this, 2)
        
        if (checkStoragePermission()) {
            loadMediaFiles()
        } else {
            requestStoragePermission()
        }
    }

    private fun checkStoragePermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.READ_EXTERNAL_STORAGE
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestStoragePermission() {
        ActivityCompat.requestPermissions(
            this,
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE),
            STORAGE_PERMISSION_CODE
        )
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == STORAGE_PERMISSION_CODE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                loadMediaFiles()
            } else {
                Toast.makeText(this, "Storage permission required to view media files", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun loadMediaFiles() {
        val mediaDir = File(getExternalFilesDir(null), "OmniGraph")
        if (!mediaDir.exists()) {
            mediaDir.mkdirs()
        }

        val mediaFiles = mediaDir.listFiles()?.filter { file ->
            file.name.endsWith(".mp3") || file.name.endsWith(".jpg") || file.name.endsWith(".png")
        }?.sortedByDescending { it.lastModified() } ?: emptyList()

        if (mediaFiles.isEmpty()) {
            recyclerView.visibility = View.GONE
            emptyView.visibility = View.VISIBLE
        } else {
            recyclerView.visibility = View.VISIBLE
            emptyView.visibility = View.GONE
            recyclerView.adapter = MediaAdapter(mediaFiles) { file ->
                when {
                    file.name.endsWith(".mp3") -> playAudio(file)
                    file.name.endsWith(".jpg") || file.name.endsWith(".png") -> openImage(file)
                }
            }
        }
    }

    private fun playAudio(file: File) {
        mediaPlayer?.release()
        mediaPlayer = MediaPlayer().apply {
            setDataSource(file.absolutePath)
            prepare()
            start()
        }
    }

    private fun openImage(file: File) {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(Uri.fromFile(file), "image/*")
            flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
        }
        startActivity(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        mediaPlayer?.release()
        mediaPlayer = null
    }
}

class MediaAdapter(
    private val mediaFiles: List<File>,
    private val onItemClick: (File) -> Unit
) : RecyclerView.Adapter<MediaAdapter.MediaViewHolder>() {

    class MediaViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val thumbnail: ImageView = view.findViewById(R.id.mediaThumbnail)
        val name: TextView = view.findViewById(R.id.mediaName)
        val playButton: ImageButton = view.findViewById(R.id.playButton)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MediaViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_media, parent, false)
        return MediaViewHolder(view)
    }

    override fun onBindViewHolder(holder: MediaViewHolder, position: Int) {
        val file = mediaFiles[position]
        holder.name.text = file.name

        when {
            file.name.endsWith(".mp3") -> {
                holder.thumbnail.setImageResource(android.R.drawable.ic_media_play)
                holder.playButton.visibility = View.VISIBLE
            }
            file.name.endsWith(".jpg") || file.name.endsWith(".png") -> {
                holder.thumbnail.setImageURI(Uri.fromFile(file))
                holder.playButton.visibility = View.GONE
            }
        }

        holder.itemView.setOnClickListener { onItemClick(file) }
        holder.playButton.setOnClickListener { onItemClick(file) }
    }

    override fun getItemCount() = mediaFiles.size
} 