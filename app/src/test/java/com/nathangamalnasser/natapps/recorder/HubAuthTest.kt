package com.nathangamalnasser.natapps.recorder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HubAuthTest {
    @Test
    fun uidForWrite_rejectsNullAndBlank() {
        assertNull(HubAuth.uidForWrite(null))
        assertNull(HubAuth.uidForWrite(""))
        assertNull(HubAuth.uidForWrite("   "))
    }

    @Test
    fun uidForWrite_keepsAuthUid() {
        assertEquals("firebase-uid-1", HubAuth.uidForWrite("firebase-uid-1"))
    }

    @Test
    fun databaseUrl_isMadxtreamsportsRtdb() {
        assertEquals("https://madxtreamsports-default-rtdb.firebaseio.com", HubAuth.DATABASE_URL)
    }
}
