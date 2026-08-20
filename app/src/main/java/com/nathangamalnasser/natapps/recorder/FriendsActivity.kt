package com.nathangamalnasser.natapps.recorder

import android.content.res.ColorStateList
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.nathangamalnasser.natapps.recorder.databinding.ActivityFriendsBinding

/**
 * Two ways to link User 1 <-> User 2's accounts, both converging on the same
 * bidirectional users/{uid}/friends/{otherUid} pair once complete:
 *  1. QR: scanning a friend's code links instantly — physical proximity is the consent.
 *  2. Email: send a request; the other side accepts/declines from this same screen.
 */
class FriendsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityFriendsBinding
    private val db = FirebaseFirestore.getInstance()
    private val myUid get() = FirebaseAuth.getInstance().currentUser?.uid ?: ""
    private val myEmail get() = FirebaseAuth.getInstance().currentUser?.email ?: ""

    private val scanLauncher = registerForActivityResult(ScanContract()) { result ->
        val raw = result.contents ?: return@registerForActivityResult
        linkFromQrPayload(raw)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityFriendsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnMyQr.setOnClickListener { showMyQr() }
        binding.btnScanQr.setOnClickListener {
            scanLauncher.launch(ScanOptions().apply {
                setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                setPrompt("Scan your friend's MADXtreamSports QR code")
                setBeepEnabled(false)
                setOrientationLocked(true)
            })
        }
        binding.btnSendRequest.setOnClickListener { sendFriendRequest() }

        loadPendingRequests()
        loadFriends()
    }

    // ── Option 1: QR ──────────────────────────────────────────────────────────

    private fun showMyQr() {
        val payload = "madxsfriend:$myUid:$myEmail"
        val qr = ImageView(this).apply {
            setImageBitmap(MainActivity.generateQr(payload, 512))
            setPadding(48, 32, 48, 8)
        }
        AlertDialog.Builder(this)
            .setTitle("Your QR code")
            .setMessage("Have your friend scan this from their FRIENDS screen")
            .setView(qr)
            .setPositiveButton("OK", null)
            .show()
    }

    private fun linkFromQrPayload(raw: String) {
        val parts = raw.split(":", limit = 3)
        if (parts.size < 2 || parts[0] != "madxsfriend") {
            Toast.makeText(this, "Not a MADXtreamSports friend QR code", Toast.LENGTH_SHORT).show()
            return
        }
        val friendUid = parts[1]
        val friendEmail = parts.getOrElse(2) { "" }
        if (friendUid == myUid) {
            Toast.makeText(this, "That's your own code", Toast.LENGTH_SHORT).show()
            return
        }
        linkFriends(friendUid, friendEmail)
        Toast.makeText(this, "Linked with $friendEmail", Toast.LENGTH_SHORT).show()
    }

    // ── Option 2: email request ───────────────────────────────────────────────

    private fun sendFriendRequest() {
        val email = binding.etFriendEmail.text.toString().trim()
        if (email.isEmpty()) {
            binding.tvFriendStatus.text = "Enter an email first"
            return
        }
        if (email.equals(myEmail, ignoreCase = true)) {
            binding.tvFriendStatus.text = "That's your own email"
            return
        }
        binding.tvFriendStatus.text = "Looking up…"
        db.collection("users").whereEqualTo("email", email).limit(1).get()
            .addOnSuccessListener { snap ->
                if (snap.isEmpty) {
                    binding.tvFriendStatus.text = "No account found with that email"
                    return@addOnSuccessListener
                }
                val targetUid = snap.documents[0].id
                if (targetUid == myUid) {
                    binding.tvFriendStatus.text = "That's your own email"
                    return@addOnSuccessListener
                }
                db.collection("users").document(targetUid).collection("friendRequests")
                    .document(myUid)
                    .set(mapOf("fromUid" to myUid, "fromEmail" to myEmail, "sentAt" to FieldValue.serverTimestamp()))
                    .addOnSuccessListener {
                        binding.tvFriendStatus.text = "Request sent to $email"
                        binding.etFriendEmail.setText("")
                    }
                    .addOnFailureListener { binding.tvFriendStatus.text = "Failed: ${it.message}" }
            }
            .addOnFailureListener { binding.tvFriendStatus.text = "Lookup failed: ${it.message}" }
    }

    private fun loadPendingRequests() {
        val uid = myUid
        if (uid.isEmpty()) return
        db.collection("users").document(uid).collection("friendRequests")
            .addSnapshotListener { snap, _ ->
                binding.pendingContainer.removeAllViews()
                val docs = snap?.documents ?: emptyList()
                binding.tvNoPending.visibility = if (docs.isEmpty()) View.VISIBLE else View.GONE
                for (doc in docs) {
                    val fromUid = doc.getString("fromUid") ?: doc.id
                    val fromEmail = doc.getString("fromEmail") ?: fromUid
                    addRequestRow(fromUid, fromEmail)
                }
            }
    }

    private fun addRequestRow(fromUid: String, fromEmail: String) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 8, 0, 8)
        }
        val label = TextView(this).apply {
            text = fromEmail
            setTextColor(getColor(R.color.text))
            textSize = 13f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val accept = Button(this).apply {
            text = "ACCEPT"
            textSize = 11f
            setTextColor(getColor(R.color.bg))
            backgroundTintList = ColorStateList.valueOf(getColor(R.color.accent))
            setOnClickListener {
                linkFriends(fromUid, fromEmail)
                db.collection("users").document(myUid).collection("friendRequests").document(fromUid).delete()
            }
        }
        val decline = Button(this).apply {
            text = "DECLINE"
            textSize = 11f
            setTextColor(getColor(R.color.accent))
            backgroundTintList = ColorStateList.valueOf(getColor(R.color.surface))
            setOnClickListener {
                db.collection("users").document(myUid).collection("friendRequests").document(fromUid).delete()
            }
        }
        row.addView(label)
        row.addView(accept)
        row.addView(decline)
        binding.pendingContainer.addView(row)
    }

    // ── Friends list ──────────────────────────────────────────────────────────

    private fun loadFriends() {
        val uid = myUid
        if (uid.isEmpty()) return
        db.collection("users").document(uid).collection("friends")
            .addSnapshotListener { snap, _ ->
                binding.friendsContainer.removeAllViews()
                val docs = snap?.documents ?: emptyList()
                binding.tvNoFriends.visibility = if (docs.isEmpty()) View.VISIBLE else View.GONE
                for (doc in docs) {
                    val email = doc.getString("friendEmail") ?: doc.id
                    addFriendRow(email)
                }
            }
    }

    private fun addFriendRow(email: String) {
        val row = TextView(this).apply {
            text = "●  $email"
            setTextColor(getColor(R.color.accent2))
            textSize = 13f
            setPadding(0, 6, 0, 6)
        }
        binding.friendsContainer.addView(row)
    }

    // ── Shared: write both sides of the link ─────────────────────────────────

    private fun linkFriends(friendUid: String, friendEmail: String) {
        val uid = myUid
        if (uid.isEmpty() || friendUid.isEmpty()) return
        val batch = db.batch()
        batch.set(
            db.collection("users").document(uid).collection("friends").document(friendUid),
            mapOf("friendEmail" to friendEmail, "since" to FieldValue.serverTimestamp())
        )
        batch.set(
            db.collection("users").document(friendUid).collection("friends").document(uid),
            mapOf("friendEmail" to myEmail, "since" to FieldValue.serverTimestamp())
        )
        batch.commit()
    }
}
