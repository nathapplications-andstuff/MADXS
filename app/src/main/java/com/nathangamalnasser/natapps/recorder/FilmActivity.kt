package com.nathangamalnasser.natapps.recorder

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.widget.Button
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import com.google.firebase.firestore.FirebaseFirestore
import com.nathangamalnasser.natapps.recorder.databinding.ActivityFilmBinding

/**
 * User 2's screen: films User 1 live, with a contrail overlay driven by
 * User 1's real-time accel/rotation, placed near User 1's GPS position.
 * The actual camera + rendering logic lives in the bundled ar-film.html —
 * this activity only owns permissions and the WebView shell.
 */
class FilmActivity : AppCompatActivity() {

    private lateinit var binding: ActivityFilmBinding

    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { /* WebView's onPermissionRequest re-checks at getUserMedia time */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityFilmBinding.inflate(layoutInflater)
        setContentView(binding.root)

        requestNeededPermissions()
        setupWebView()
        loadLiveFriends()

        binding.btnJoin.setOnClickListener {
            val code = binding.etJoinCode.text.toString().trim()
            if (code.isEmpty()) {
                binding.tvJoinStatus.text = "Enter a code first"
                return@setOnClickListener
            }
            joinByCode(code)
        }
    }

    private fun joinByCode(code: String) {
        binding.joinOverlay.visibility = View.GONE
        binding.webFilm.loadUrl("file:///android_asset/ar-film.html?code=" + java.net.URLEncoder.encode(code, "UTF-8"))
    }

    // Skips the manual code entirely for linked friends who are recording right now —
    // this is the point of linking accounts (see FriendsActivity).
    private fun loadLiveFriends() {
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return
        FirebaseFirestore.getInstance().collection("users").document(uid).collection("friends")
            .addSnapshotListener { snap, _ ->
                val friends = snap?.documents ?: emptyList()
                binding.liveFriendsContainer.removeAllViews()
                if (friends.isEmpty()) {
                    binding.liveFriendsDivider.visibility = View.GONE
                    return@addSnapshotListener
                }
                for (doc in friends) {
                    val friendUid = doc.id
                    val friendEmail = doc.getString("friendEmail") ?: friendUid
                    FirebaseDatabase.getInstance().getReference("liveByUser").child(friendUid)
                        .addListenerForSingleValueEvent(object : ValueEventListener {
                            override fun onDataChange(data: DataSnapshot) {
                                val shortCode = data.child("shortCode").getValue(String::class.java)
                                if (shortCode != null) addLiveFriendRow(friendEmail, shortCode)
                            }
                            override fun onCancelled(error: DatabaseError) {}
                        })
                }
            }
    }

    private fun addLiveFriendRow(friendEmail: String, shortCode: String) {
        val params = android.widget.LinearLayout.LayoutParams(
            android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = 8 }
        val btn = Button(this).apply {
            text = "●  FILM $friendEmail — LIVE NOW"
            textSize = 12f
            setTextColor(getColor(R.color.bg))
            backgroundTintList = android.content.res.ColorStateList.valueOf(getColor(R.color.accent))
            setOnClickListener { joinByCode(shortCode) }
        }
        binding.liveFriendsContainer.addView(btn, params)
        binding.liveFriendsDivider.visibility = View.VISIBLE
    }

    private fun requestNeededPermissions() {
        val needed = mutableListOf<String>()
        if (!has(Manifest.permission.CAMERA)) needed += Manifest.permission.CAMERA
        if (!has(Manifest.permission.ACCESS_FINE_LOCATION)) needed += Manifest.permission.ACCESS_FINE_LOCATION
        if (needed.isNotEmpty()) permLauncher.launch(needed.toTypedArray())
    }

    private fun has(p: String) =
        ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    private fun setupWebView() {
        val web = binding.webFilm
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.settings.mediaPlaybackRequiresUserGesture = false
        web.settings.setGeolocationEnabled(true)

        web.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest) {
                // The OS-level CAMERA permission is requested above; this grants the
                // matching in-page getUserMedia request once that's in place.
                runOnUiThread { request.grant(request.resources) }
            }
            override fun onGeolocationPermissionsShowPrompt(
                origin: String?,
                callback: android.webkit.GeolocationPermissions.Callback?
            ) {
                callback?.invoke(origin, true, false)
            }
        }
    }

    override fun onDestroy() {
        binding.webFilm.destroy()
        super.onDestroy()
    }
}
