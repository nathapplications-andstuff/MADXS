package com.nathangamalnasser.natapps.recorder

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import com.nathangamalnasser.natapps.recorder.databinding.ActivityLoginBinding

class LoginActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLoginBinding
    private lateinit var auth: FirebaseAuth
    private var googleClient: GoogleSignInClient? = null

    private val googleLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val task = GoogleSignIn.getSignedInAccountFromIntent(result.data)
        try {
            val account = task.getResult(ApiException::class.java)
            val credential = GoogleAuthProvider.getCredential(account.idToken, null)
            auth.signInWithCredential(credential)
                .addOnSuccessListener { goToMain() }
                .addOnFailureListener { setStatus(LoginAuth.mapAuthError(it.message, "Google sign-in")) }
        } catch (e: ApiException) {
            val reason = LoginAuth.mapGoogleApiException(e.statusCode)
            setStatus(reason)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)
        auth = FirebaseAuth.getInstance()

        setupGoogleSignIn()

        if (intent.getBooleanExtra(EXTRA_FROM_APP, false) || auth.currentUser?.isAnonymous == true) {
            binding.btnGuest.visibility = android.view.View.GONE
        }

        binding.btnSignIn.setOnClickListener {
            val (email, pass) = emailPass() ?: return@setOnClickListener
            auth.signInWithEmailAndPassword(email, pass)
                .addOnSuccessListener { goToMain() }
                .addOnFailureListener { setStatus(LoginAuth.mapAuthError(it.message, "Sign-in")) }
        }

        binding.btnSignUp.setOnClickListener {
            val (email, pass) = emailPass() ?: return@setOnClickListener
            auth.createUserWithEmailAndPassword(email, pass)
                .addOnSuccessListener { goToMain() }
                .addOnFailureListener { setStatus(LoginAuth.mapAuthError(it.message, "Create account")) }
        }

        binding.btnGoogle.setOnClickListener {
            val client = googleClient
            if (client == null) {
                setStatus(LoginAuth.GOOGLE_NOT_READY)
                return@setOnClickListener
            }
            googleLauncher.launch(client.signInIntent)
        }

        binding.btnGuest.setOnClickListener {
            auth.signInAnonymously()
                .addOnSuccessListener { goToMain() }
                .addOnFailureListener { setStatus(LoginAuth.mapAuthError(it.message, "Guest")) }
        }
    }

    // Google Sign-In needs a Web OAuth client ID that the google-services plugin only
    // generates once you've enabled Google in Firebase console > Authentication > Sign-in
    // method. Looked up by name (not R.string.default_web_client_id) so the build doesn't
    // break before that step is done — the resource plainly does not exist yet.
    private fun setupGoogleSignIn() {
        val resId = resources.getIdentifier("default_web_client_id", "string", packageName)
        val webClientId = if (resId != 0) getString(resId) else null
        if (!LoginAuth.googleReady(webClientId) || webClientId == null) {
            // Keep the button tappable so the user sees why Google is off, not a dead control.
            return
        }
        val options = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestIdToken(webClientId)
            .requestEmail()
            .build()
        googleClient = GoogleSignIn.getClient(this, options)
    }

    private fun emailPass(): Pair<String, String>? {
        val email = binding.etEmail.text.toString().trim()
        val pass = binding.etPassword.text.toString()
        val error = LoginAuth.validateCredentials(email, pass)
        if (error != null) {
            setStatus(error)
            return null
        }
        return email to pass
    }

    private fun setStatus(msg: String) {
        binding.tvStatus.setTextColor(ContextCompat.getColor(this, R.color.stop_red))
        binding.tvStatus.text = msg
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
    }

    private fun goToMain() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }

    companion object {
        const val EXTRA_FROM_APP = "from_app"
    }
}
