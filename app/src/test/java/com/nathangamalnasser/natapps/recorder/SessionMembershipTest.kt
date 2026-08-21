package com.nathangamalnasser.natapps.recorder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionMembershipTest {
    @Test
    fun guest_cannotJoin() {
        assertEquals(SessionMembership.JOIN_NEEDS_SIGN_IN, SessionMembership.canJoin(true, 1))
    }

    @Test
    fun signedIn_canJoinUntilTen() {
        assertNull(SessionMembership.canJoin(false, 1))
        assertNull(SessionMembership.canJoin(false, 9))
        assertEquals(SessionMembership.SESSION_FULL, SessionMembership.canJoin(false, 10))
    }

    @Test
    fun codesWrite_rejectsStealAndForeignSession() {
        assertFalse(SessionMembership.codesWriteAllowed(null, null, "host"))
        assertFalse(SessionMembership.codesWriteAllowed("attacker", null, "host"))
        assertFalse(SessionMembership.codesWriteAllowed("attacker", "host", "attacker"))
        assertTrue(SessionMembership.codesWriteAllowed("host", null, "host"))
        assertTrue(SessionMembership.codesWriteAllowed("host", "host", "host"))
    }
}
