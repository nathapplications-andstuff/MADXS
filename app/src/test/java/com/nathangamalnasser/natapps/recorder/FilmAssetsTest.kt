package com.nathangamalnasser.natapps.recorder

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class FilmAssetsTest {
    @Test
    fun arFilmHtml_usesMadxtreamsportsRtdb() {
        val html = File("src/main/assets/ar-film.html").readText()
        assertTrue(html.contains(HubAuth.DATABASE_URL))
        assertFalse(html.contains("accelperformaceexponentialapex"))
    }

    @Test
    fun bundledTracerHtml_usesMadxtreamsportsRtdb() {
        val html = File("src/main/assets/tracer-real.html").readText()
        assertTrue(html.contains(HubAuth.DATABASE_URL))
        assertFalse(html.contains("accelperformaceexponentialapex"))
    }
}
