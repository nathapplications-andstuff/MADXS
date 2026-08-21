package com.nathangamalnasser.natapps.recorder

/** RTDB identity for live uploads. uid must be the Auth uid, never a device fallback. */
object HubAuth {
    const val DATABASE_URL = "https://madxtreamsports-default-rtdb.firebaseio.com"

    fun uidForWrite(authUid: String?): String? = authUid?.takeIf { it.isNotBlank() }
}
