package com.nathangamalnasser.natapps.recorder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LoginAuthTest {

    @Test
    fun validateCredentials_blankEmail_asksForBoth() {
        assertEquals("Enter both email and password", LoginAuth.validateCredentials("  ", "secret1"))
    }

    @Test
    fun validateCredentials_emptyPassword_asksForBoth() {
        assertEquals("Enter both email and password", LoginAuth.validateCredentials("a@b.com", ""))
    }

    @Test
    fun validateCredentials_missingAt_rejectsEmail() {
        assertEquals("Enter a valid email", LoginAuth.validateCredentials("not-an-email", "secret1"))
    }

    @Test
    fun validateCredentials_shortPassword_rejected() {
        assertEquals(
            "Password must be at least 6 characters",
            LoginAuth.validateCredentials("a@b.com", "12345")
        )
    }

    @Test
    fun validateCredentials_validPair_returnsNull() {
        assertNull(LoginAuth.validateCredentials("a@b.com", "secret1"))
    }

    @Test
    fun googleReady_blank_isFalse() {
        assertFalse(LoginAuth.googleReady(null))
        assertFalse(LoginAuth.googleReady(""))
        assertFalse(LoginAuth.googleReady("   "))
    }

    @Test
    fun googleReady_webClientId_isTrue() {
        assertTrue(LoginAuth.googleReady("123-abc.apps.googleusercontent.com"))
    }

    @Test
    fun mapAuthError_providerDisabled_pointsAtConsole() {
        assertEquals(
            "Email/password is not enabled in Firebase Authentication",
            LoginAuth.mapAuthError(
                "The given sign-in provider is disabled for this Firebase project.",
                "Create account"
            )
        )
    }

    @Test
    fun mapAuthError_configurationMissing_pointsAtSetup() {
        assertEquals(
            "Firebase Authentication is not set up for this project yet",
            LoginAuth.mapAuthError("CONFIGURATION_NOT_FOUND", "Create account")
        )
    }

    @Test
    fun mapAuthError_duplicateEmail_directsToSignIn() {
        assertEquals(
            "That email already has an account — use Sign In",
            LoginAuth.mapAuthError("The email address is already in use by another account.", "Create account")
        )
    }

    @Test
    fun mapAuthError_null_usesOperation() {
        assertEquals("Create account failed", LoginAuth.mapAuthError(null, "Create account"))
    }

    @Test
    fun mapAuthError_guestProviderDisabled_mentionsGuest() {
        assertEquals(
            "Guest try isn't enabled in Firebase Authentication",
            LoginAuth.mapAuthError("The given sign-in provider is disabled for this Firebase project.", "Guest")
        )
    }

    @Test
    fun mapGoogleApiException_developerError_explainsRebuild() {
        val msg = LoginAuth.mapGoogleApiException(10)
        assertTrue(msg.contains("SHA-1"))
    }

    @Test
    fun mapGoogleApiException_cancelled_isCancelled() {
        assertEquals("Google sign-in cancelled", LoginAuth.mapGoogleApiException(12501))
    }
}
