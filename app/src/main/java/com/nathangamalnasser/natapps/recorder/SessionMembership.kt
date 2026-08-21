package com.nathangamalnasser.natapps.recorder

/** Who may start or join a live session. Mirrors database.rules.json. */
object SessionMembership {
    const val MAX_MEMBERS = 10
    const val JOIN_NEEDS_SIGN_IN = "Sign in to join a session"
    const val SESSION_FULL = "Session is full (10 people)"

    fun canJoin(isAnonymous: Boolean, memberCount: Int): String? {
        if (isAnonymous) return JOIN_NEEDS_SIGN_IN
        if (memberCount >= MAX_MEMBERS) return SESSION_FULL
        return null
    }

    /** A code may only point at a session owned by the writer, and may not steal an existing code. */
    fun codesWriteAllowed(authUid: String?, existingOwnerUid: String?, newOwnerUid: String?): Boolean {
        if (authUid.isNullOrBlank() || newOwnerUid.isNullOrBlank()) return false
        if (authUid != newOwnerUid) return false
        if (existingOwnerUid != null && existingOwnerUid != authUid) return false
        return true
    }
}
