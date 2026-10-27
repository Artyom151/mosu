package com.mosu.skins

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OsuSkinsClientTest {

    private val html = javaClass.classLoader!!.getResource("skins/osuskins-listing.html")!!.readText()

    @Test
    fun `listing is parsed from JSON-LD`() {
        val items = OsuSkinsClient.parse(html)

        assertTrue(items.isNotEmpty())

        val rafis = items.single { it.id == "ekynLzX" }
        assertEquals("Rafis 2018-03-26 HDDT", rafis.name)
        assertEquals("https://cdn.osuskins.net/screenshots/thumbnails/ekynLzX.webp", rafis.thumbnailUrl)
        assertEquals("https://osuskins.net/skin/ekynLzX", rafis.pageUrl)
    }

    @Test
    fun `malformed identifiers are rejected`() {
        val bad = """<script type="application/ld+json">{"@type":"ItemList","itemListElement":[
            {"item":{"name":"x","url":"https://osuskins.net/skin/../../etc"}}]}</script>"""

        assertTrue(OsuSkinsClient.parse(bad).isEmpty())
    }

    @Test
    fun `only the skin site and captcha provider are trusted`() {
        assertTrue(OsuSkinsClient.isTrustedHost("osuskins.net"))
        assertTrue(OsuSkinsClient.isTrustedHost("cdn.osuskins.net"))
        assertTrue(OsuSkinsClient.isTrustedHost("challenges.cloudflare.com"))
        assertFalse(OsuSkinsClient.isTrustedHost("evil-osuskins.net"))
        assertFalse(OsuSkinsClient.isTrustedHost(null))
    }

    @Test
    fun `HTML entities in names are decoded`() {
        assertEquals("Denying's Scathach", OsuSkinsClient.decodeEntities("Denying&#039;s Scathach"))
        assertEquals("A & B <C> \"D\"", OsuSkinsClient.decodeEntities("A &amp; B &lt;C&gt; &quot;D&quot;"))
        assertEquals("x&#zz;", OsuSkinsClient.decodeEntities("x&#zz;"))
    }
}
