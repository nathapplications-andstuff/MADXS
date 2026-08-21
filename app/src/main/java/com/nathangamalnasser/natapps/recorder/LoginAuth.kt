package com.nathangamalnasser.natapps.recorder

/**
 * Login form rules and Firebase Auth error mapping. Pure so unit tests don't
 * need Android or a live Firebase project.
 */
object LoginAuth {
    const val MIN_PASSWORD_LEN = 6
    const val GOOGLE_NOT_READY =
        "Google Sign-In isn't set up — enable Google in Firebase Authentication, then rebuild the app"

    fun validateCredentials(email: String, password: String): String? {
        if (email.isBlank() || password.isEmpty()) return "Enter both email and password"
        if (!email.contains("@")) return "Enter a valid email"
        if (password.length < MIN_PASSWORD_LEN) {
            return "Password must be at least $MIN_PASSWORD_LEN characters"
        }
        return null
    }

    fun googleReady(webClientId: String?): Boolean = !webClientId.isNullOrBlank()

    /** Home-screen Sign In: guests have no other way back to the login screen. */
    fun showHomeSignInButton(isAnonymous: Boolean) = isAnonymous

    fun mapAuthError(message: String?, operation: String): String {
        val raw = message?.trim().orEmpty()
        if (raw.isEmpty()) return "$operation failed"
        val lower = raw.lowercase()
        return when {
            lower.contains("operation_not_allowed") ||
                lower.contains("sign-in provider is disabled") ->
                if (operation.contains("Guest", ignoreCase = true)) {
                    "Guest try isn't enabled in Firebase Authentication"
                } else {
                    "Email/password is not enabled in Firebase Authentication"
                }
            lower.contains("configuration_not_found") ||
                lower.contains("admin-restricted-operation") ||
                lower.contains("identity toolkit") ->
                "Firebase Authentication is not set up for this project yet"
            lower.contains("email address is already") ||
                lower.contains("email_exists") ->
                "That email already has an account — use Sign In"
            lower.contains("badly formatted") ||
                lower.contains("invalid_email") ->
                "Enter a valid email"
            lower.contains("weak password") ||
                lower.contains("at least 6") ->
                "Password must be at least $MIN_PASSWORD_LEN characters"
            lower.contains("password is invalid") ||
                lower.contains("wrong-password") ||
                lower.contains("invalid_login_credentials") ||
                lower.contains("malformed or has expired") ->
                "Wrong email or password"
            lower.contains("user not found") ||
                lower.contains("user-not-found") ->
                "No account for that email — use Create account"
            else -> raw
        }
    }

    /** Google Sign-In CommonStatusCodes: 10 = DEVELOPER_ERROR, 12501 = SIGN_IN_CANCELLED. */
    fun mapGoogleApiException(statusCode: Int): String = when (statusCode) {
        10 -> "Google Sign-In config mismatch (SHA-1 / OAuth client). Rebuild after enabling Google in Firebase."
        12501 -> "Google sign-in cancelled"
        else -> "Google sign-in cancelled or failed"
    }
}
