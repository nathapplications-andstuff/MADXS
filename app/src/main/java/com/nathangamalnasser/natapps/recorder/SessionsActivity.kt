package com.nathangamalnasser.natapps.recorder

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.Uri
import android.os.Bundle
import android.os.IBinder
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.nathangamalnasser.natapps.recorder.databinding.ActivitySessionsBinding
import com.nathangamalnasser.natapps.recorder.databinding.ItemSessionBinding
import org.json.JSONArray
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class SessionsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySessionsBinding
    private var service: RecordingService? = null
    private var bound = false
    private var adapter: SessionAdapter? = null

    private var pendingSaveFile: File? = null
    private val saveJsonLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        writeToUri(uri, pendingSaveFile)
        pendingSaveFile = null
    }
    private var pendingSaveZip: File? = null
    private val saveZipLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        writeToUri(uri, pendingSaveZip)
        pendingSaveZip = null
    }

    private val svcConn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, b: IBinder) {
            service = (b as RecordingService.LocalBinder).get()
            bound = true
            refreshList()
        }
        override fun onServiceDisconnected(name: ComponentName) { bound = false; service = null }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySessionsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnBack.setOnClickListener { finish() }
        binding.btnExportAll.setOnClickListener { exportAllSessions() }
        binding.btnSaveAll.setOnClickListener { saveAllSessions() }

        adapter = SessionAdapter(
            onView   = { file -> viewSession(file) },
            onExport = { file -> exportSession(file) },
            onSave   = { file -> saveSession(file) },
            onDelete = { file -> confirmDelete(file) }
        )
        binding.rvSessions.layoutManager = LinearLayoutManager(this)
        binding.rvSessions.adapter = adapter

        val intent = Intent(this, RecordingService::class.java)
        startService(intent)
        bindService(intent, svcConn, Context.BIND_AUTO_CREATE)
    }

    override fun onDestroy() {
        super.onDestroy()
        if (bound) unbindService(svcConn)
    }

    private fun refreshList() {
        val files = service?.getSessionFiles() ?: emptyList()
        adapter?.setFiles(files)
        binding.tvEmpty.visibility = if (files.isEmpty()) android.view.View.VISIBLE else android.view.View.GONE
    }

    private fun viewSession(file: File) {
        val ip = service?.getLocalIp() ?: "?"
        val url = "http://$ip:8080/?session=${file.name}"
        val qr = android.widget.ImageView(this).apply {
            setImageBitmap(MainActivity.generateQr(url, 512))
            setPadding(48, 24, 48, 8)
        }
        AlertDialog.Builder(this)
            .setTitle("View Session")
            .setMessage("Scan QR or tap Open to view in browser")
            .setView(qr)
            .setPositiveButton("Open in Browser") { _, _ ->
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)))
                } catch (e: Exception) {
                    Toast.makeText(this, "No browser found", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun exportSession(file: File) {
        try {
            val json = JSONArray(file.readText())
            val ts = json.getJSONObject(0).optLong("id", System.currentTimeMillis())
            val date = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(ts))
            val outName = "madxs_session_$date.json"
            val shareFile = File(filesDir, outName)
            shareFile.writeText(file.readText())

            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", shareFile)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "application/json"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, outName)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, "Share session"))
        } catch (e: Exception) {
            Toast.makeText(this, "Export failed: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun exportAllSessions() {
        val zipFile = buildSessionsZip() ?: return
        try {
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", zipFile)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "application/zip"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, zipFile.name)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, "Share all sessions"))
        } catch (e: Exception) {
            Toast.makeText(this, "Export failed: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    /** Builds a zip of all session files in the app's private storage. Returns null (with a toast) if there's nothing to zip. */
    private fun buildSessionsZip(): File? {
        val files = service?.getSessionFiles() ?: emptyList()
        if (files.isEmpty()) {
            Toast.makeText(this, "No sessions to export", Toast.LENGTH_SHORT).show()
            return null
        }
        return try {
            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val zipFile = File(filesDir, "madxs_sessions_$stamp.zip")
            ZipOutputStream(FileOutputStream(zipFile)).use { zip ->
                files.forEach { f ->
                    zip.putNextEntry(ZipEntry(f.name))
                    zip.write(f.readBytes())
                    zip.closeEntry()
                }
            }
            zipFile
        } catch (e: Exception) {
            Toast.makeText(this, "Export failed: ${e.message}", Toast.LENGTH_SHORT).show()
            null
        }
    }

    // ── Save to device (Storage Access Framework — no share-target app required) ──

    private fun saveSession(file: File) {
        try {
            val json = JSONArray(file.readText())
            val ts = json.getJSONObject(0).optLong("id", System.currentTimeMillis())
            val date = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(ts))
            pendingSaveFile = file
            saveJsonLauncher.launch("madxs_session_$date.json")
        } catch (e: Exception) {
            Toast.makeText(this, "Save failed: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun saveAllSessions() {
        val zipFile = buildSessionsZip() ?: return
        pendingSaveZip = zipFile
        saveZipLauncher.launch(zipFile.name)
    }

    private fun writeToUri(uri: Uri?, file: File?) {
        if (uri == null || file == null) return
        try {
            contentResolver.openOutputStream(uri)?.use { out -> out.write(file.readBytes()) }
            Toast.makeText(this, "Saved to device", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Save failed: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun confirmDelete(file: File) {
        AlertDialog.Builder(this)
            .setTitle("Delete session?")
            .setMessage("This cannot be undone.")
            .setPositiveButton("Delete") { _, _ ->
                service?.deleteSession(file)
                refreshList()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ── Adapter ───────────────────────────────────────────────────────────────

    class SessionAdapter(
        private val onView:   (File) -> Unit,
        private val onExport: (File) -> Unit,
        private val onSave:   (File) -> Unit,
        private val onDelete: (File) -> Unit
    ) : RecyclerView.Adapter<SessionAdapter.VH>() {

        private val files = mutableListOf<File>()

        fun setFiles(list: List<File>) {
            files.clear(); files.addAll(list); notifyDataSetChanged()
        }

        inner class VH(val b: ItemSessionBinding) : RecyclerView.ViewHolder(b.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            VH(ItemSessionBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun getItemCount() = files.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val file = files[position]
            try {
                val arr = JSONArray(file.readText())
                val obj = arr.getJSONObject(0)
                val ts  = obj.optLong("id", 0L)
                val date = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(ts))
                val dur  = obj.optLong("duration_ms", 0L)
                val min  = dur / 60000; val sec = (dur / 1000) % 60
                val channels = if (arr.length() > 1) "Left + Right" else obj.optString("device", "?").replaceFirstChar { it.uppercase() }
                val count = obj.optInt("sample_count", 0)
                val name  = obj.optString("name", "")
                holder.b.tvTitle.text = if (name.isNotEmpty()) "$name  ·  $date  ·  $channels" else "$date  ·  $channels"
                holder.b.tvMeta.text  = "Duration %02d:%02d  ·  %,d samples".format(min, sec, count)
            } catch (e: Exception) {
                holder.b.tvTitle.text = file.name
                holder.b.tvMeta.text  = "Error reading file"
            }
            holder.b.btnView.setOnClickListener   { onView(file) }
            holder.b.btnExport.setOnClickListener { onExport(file) }
            holder.b.btnSave.setOnClickListener   { onSave(file) }
            holder.b.btnDelete.setOnClickListener { onDelete(file) }
        }
    }
}
