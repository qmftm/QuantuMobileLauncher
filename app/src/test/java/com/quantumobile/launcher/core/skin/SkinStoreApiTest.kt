package com.quantumobile.launcher.core.skin

import com.quantumobile.launcher.core.auth.Account
import com.quantumobile.launcher.core.auth.AccountType
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.Base64

class SkinStoreApiTest {
    @get:Rule val tmp = TemporaryFolder()
    private val server = MockWebServer()

    @After fun stop() = runCatching { server.shutdown() }.let { }

    // ---- store ----

    @Test
    fun storeKeepsEntriesAcrossRestarts() {
        val dir = tmp.newFolder("skins")
        var now = 1_000L
        val store = SkinStore(dir) { now }
        val a = store.add("내 스킨", SkinKind.SKIN, slim = true, png = byteArrayOf(1, 2, 3), thumb = byteArrayOf(9))
        now = 2_000
        val b = store.add("  ", SkinKind.CAPE, slim = false, png = byteArrayOf(4), thumb = ByteArray(0))
        assertEquals("망토", b.name) // blank name → default
        assertEquals(listOf(b.id, a.id), store.entries.value.map { it.id }) // newest first
        assertArrayEquals(byteArrayOf(1, 2, 3), store.textureFile(a.id).readBytes())

        val again = SkinStore(dir)
        assertEquals(listOf(b.id, a.id), again.entries.value.map { it.id })
        assertTrue(again.get(a.id)!!.slim)
        assertEquals(SkinModel.SLIM, again.get(a.id)!!.model)
    }

    @Test
    fun renameSlimAndDelete() {
        val dir = tmp.newFolder("skins")
        val store = SkinStore(dir)
        val a = store.add("a", SkinKind.SKIN, false, byteArrayOf(1), byteArrayOf(2))
        store.setSlim(a.id, true)
        assertEquals(SkinModel.SLIM, store.get(a.id)!!.model)
        store.rename(a.id, "  새 이름 ")
        assertEquals("새 이름", store.get(a.id)!!.name)
        store.rename(a.id, "   ") // blank is ignored
        assertEquals("새 이름", store.get(a.id)!!.name)
        assertTrue(SkinStore(dir).get(a.id)!!.slim) // kept across restarts
        store.delete(a.id)
        assertFalse(store.textureFile(a.id).exists())
        assertFalse(store.thumbFile(a.id).exists())
        assertNull(store.get(a.id))
    }

    @Test
    fun entriesWithoutATextureFileAreDropped() {
        val dir = tmp.newFolder("skins")
        val store = SkinStore(dir)
        val a = store.add("a", SkinKind.SKIN, false, byteArrayOf(1), byteArrayOf(2))
        store.textureFile(a.id).delete()
        assertTrue(SkinStore(dir).entries.value.isEmpty())
    }

    // ---- API ----

    private fun api(texturesHost: Boolean = true) = SkinApi(
        profileBase = server.url("/mojang").toString().trimEnd('/'),
        sessionBase = server.url("/session").toString().trimEnd('/'),
        servicesBase = server.url("/services").toString().trimEnd('/'),
        textureHostOk = { texturesHost },
    )

    private fun profileJson(slim: Boolean, cape: Boolean): String {
        val skin = """{"url":"http://textures.minecraft.net/texture/abc"${if (slim) ""","metadata":{"model":"slim"}""" else ""}}"""
        val textures = """{"textures":{"SKIN":$skin${if (cape) ""","CAPE":{"url":"http://textures.minecraft.net/texture/cape1"}""" else ""}}}"""
        val value = Base64.getEncoder().encodeToString(textures.toByteArray())
        return """{"id":"0123456789abcdef0123456789abcdef","name":"Alex","properties":[{"name":"textures","value":"$value"}]}"""
    }

    @Test
    fun looksUpATexturesByName() = runBlocking {
        server.start()
        server.enqueue(MockResponse().setBody("""{"id":"0123456789abcdef0123456789abcdef","name":"Alex"}"""))
        server.enqueue(MockResponse().setBody(profileJson(slim = true, cape = true)))
        val t = api().textures("Alex")!!
        assertEquals("/mojang/users/profiles/minecraft/Alex", server.takeRequest().path)
        assertEquals("/session/session/minecraft/profile/0123456789abcdef0123456789abcdef", server.takeRequest().path)
        assertTrue(t.slim)
        assertEquals("http://textures.minecraft.net/texture/abc", t.skinUrl)
        assertEquals("http://textures.minecraft.net/texture/cape1", t.capeUrl)
    }

    @Test
    fun aUuidSkipsTheNameLookupAndAClassicSkinHasNoCape() = runBlocking {
        server.start()
        server.enqueue(MockResponse().setBody(profileJson(slim = false, cape = false)))
        val t = api().textures("01234567-89ab-cdef-0123-456789abcdef")!!
        assertEquals("/session/session/minecraft/profile/0123456789abcdef0123456789abcdef", server.takeRequest().path)
        assertFalse(t.slim); assertNull(t.capeUrl)
    }

    @Test
    fun unknownPlayersAndBadNamesGiveNull() = runBlocking {
        server.start()
        server.enqueue(MockResponse().setResponseCode(404))
        assertNull(api().textures("Nobody"))
        assertNull(api().uuidOf("not a name!")) // never sent to the server
        assertEquals(1, server.requestCount)
    }

    @Test
    fun boundedReadStopsAtTheLimit() {
        assertEquals(5, readLimited(java.io.ByteArrayInputStream(ByteArray(100)), 5).size)
        assertEquals(3, readLimited(java.io.ByteArrayInputStream(ByteArray(3)), 5).size)
        assertEquals(0, readLimited(java.io.ByteArrayInputStream(ByteArray(0)), 5).size)
        assertEquals(20_000, readLimited(java.io.ByteArrayInputStream(ByteArray(50_000)), 20_000).size)
    }

    @Test
    fun theTextureServerIsReachedOverHttps() {
        val api = SkinApi()
        val http = "http://textures.minecraft.net/texture/abc".toHttpUrl()
        assertEquals("https://textures.minecraft.net/texture/abc", api.secure(http).toString())
        assertEquals("http://example.com/x", api.secure("http://example.com/x".toHttpUrl()).toString()) // other hosts untouched
    }

    @Test
    fun downloadOnlyFromTheTextureServer() = runBlocking {
        server.start()
        server.enqueue(MockResponse().setBody("png-bytes"))
        assertArrayEquals("png-bytes".toByteArray(), api(texturesHost = true).download(server.url("/t").toString()))
        try { api(texturesHost = false).download(server.url("/t").toString()); fail() } catch (e: SkinApiException) {
            assertTrue(e.message!!.contains("믿을 수 없는"))
        }
        try { SkinApi().download("https://evil.example/skin.png"); fail() } catch (e: SkinApiException) { }
    }

    @Test
    fun uploadSendsTheSkinWithTheTokenInTheHeaderOnly() = runBlocking {
        server.start()
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
        val account = Account(type = AccountType.MICROSOFT, username = "Alex", uuid = "0123456789abcdef0123456789abcdef", accessToken = "secret-token")
        api().uploadSkin(account, byteArrayOf(1, 2, 3), SkinModel.SLIM)
        val req = server.takeRequest()
        assertEquals("/services/minecraft/profile/skins", req.path)
        assertEquals("Bearer secret-token", req.getHeader("Authorization"))
        val body = req.body.readUtf8()
        assertTrue(body.contains("name=\"variant\"") && body.contains("slim"))
        assertTrue(body.contains("filename=\"skin.png\""))
        assertFalse("token is not in the body", body.contains("secret-token"))
    }

    @Test
    fun uploadErrorsHaveReadableMessagesWithoutTokens() = runBlocking {
        server.start()
        val account = Account(type = AccountType.MICROSOFT, username = "Alex", uuid = "00", accessToken = "secret-token")
        for ((code, word) in listOf(401 to "로그인", 400 to "받아들이지", 403 to "바꿀 수 없어요", 429 to "너무 많아요", 500 to "HTTP 500")) {
            server.enqueue(MockResponse().setResponseCode(code).setBody("details secret-token"))
            try { api().uploadSkin(account, byteArrayOf(1), SkinModel.CLASSIC); fail("$code") } catch (e: SkinApiException) {
                assertTrue("$code: ${e.message}", e.message!!.contains(word))
                assertFalse(e.message!!.contains("secret-token"))
            }
        }
    }
}
