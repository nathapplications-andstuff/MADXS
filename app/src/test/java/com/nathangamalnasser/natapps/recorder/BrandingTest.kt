package com.nathangamalnasser.natapps.recorder

import org.junit.Assert.assertEquals
import org.junit.Test

class BrandingTest {
    @Test
    fun mad_means_motionActiveData() {
        assertEquals("Motion Active Data", Branding.MAD_MEANS)
    }

    @Test
    fun siteUrl_isMadxtreamsportsDotCom() {
        assertEquals("https://madxtreamsports.com", Branding.SITE_URL)
    }

    @Test
    fun startButton_saysStartSession() {
        assertEquals("●  START SESSION", Branding.START_SESSION_LABEL)
        assertEquals("■  STOP", Branding.STOP_SESSION_LABEL)
    }
}
