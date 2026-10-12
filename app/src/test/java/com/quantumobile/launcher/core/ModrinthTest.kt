package com.quantumobile.launcher.core

import com.quantumobile.launcher.core.mods.ModException
import com.quantumobile.launcher.core.mods.ModInstaller
import com.quantumobile.launcher.core.mods.ModTarget
import com.quantumobile.launcher.core.mods.ModrinthApi
import com.quantumobile.launcher.core.mods.ProjectType
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.File
import java.net.URLDecoder
import java.nio.file.Files
import java.security.MessageDigest

class ModrinthTest {
    private lateinit var server: MockWebServer
    private lateinit var root: File
    private val requests = mutableListOf<String>()
    private val fabric = ModTarget("1.21.1", listOf("fabric"))
    private val modBytes = "mod-bytes".toByteArray()
    private val depBytes = "dep-bytes".toByteArray()

    private fun sha1(b: ByteArray) = MessageDigest.getInstance("SHA-1").digest(b).joinToString("") { "%02x".format(it) }

    private fun version(id: String, project: String, file: String, bytes: ByteArray, deps: String = "[]", type: String = "release", cdn: String = "https://cdn.modrinth.com") =
        """{"id":"$id","project_id":"$project","name":"$id","version_number":"1.$id","version_type":"$type",
            "files":[{"url":"$cdn/data/$project/$file","filename":"$file","primary":true,"size":${bytes.size},"hashes":{"sha1":"${sha1(bytes)}"}}],
            "dependencies":$deps}"""

    @Before
    fun setUp() {
        root = Files.createTempDirectory("qml-mods").toFile()
        server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val path = URLDecoder.decode(request.path!!, "UTF-8")
                    requests += path
                    if (path.startsWith("/data/")) {
                        val bytes = if (path.startsWith("/data/DEP1/")) depBytes else modBytes
                        return MockResponse().setBody(okio.Buffer().write(bytes))
                    }
                    val body = when {
                        path.startsWith("/project/X/version") -> "[]"
                        path.startsWith("/search") -> """{"hits":[{"project_id":"MOD1","slug":"cool","title":"Cool Mod","author":"me","description":"d","icon_url":null,"downloads":1234,"project_type":"mod"},{"broken":true}],"offset":0,"limit":20,"total_hits":45}"""
                        path.startsWith("/project/MOD1/version") -> "[" + version("v2beta", "MOD1", "cool-2b.jar", modBytes, type = "beta") + "," +
                            version("v1", "MOD1", "cool-1.jar", modBytes, """[{"project_id":"DEP1","dependency_type":"required"},{"project_id":"OPT","dependency_type":"optional"}]""") + "]"
                        path.startsWith("/project/DEP1/version") -> "[" + version("d1", "DEP1", "dep-1.jar", depBytes) + "]"
                        path.startsWith("/project/NOVER/version") -> "[]"
                        path.startsWith("/project/DEP1") -> """{"title":"Dep Lib"}"""
                        path.startsWith("/project/NOVER") -> """{"title":"No Version"}"""
                        path.startsWith("/project/EVIL/version") -> "[" + version("e1", "EVIL", "../../evil.jar", modBytes, cdn = "https://cdn.modrinth.com") + "]"
                        path.startsWith("/project/OFFSITE/version") -> "[" + version("o1", "OFFSITE", "o.jar", modBytes, cdn = "https://evil.example.com") + "]"
                        path.startsWith("/project/NEEDSMISSING/version") -> "[" + version("n1", "NEEDSMISSING", "n.jar", modBytes, """[{"project_id":"NOVER","dependency_type":"required"}]""") + "]"
                        path.startsWith("/project/NEEDSMISSING") -> """{"title":"Needs Missing"}"""
                        else -> return MockResponse().setResponseCode(404)
                    }
                    return MockResponse().setBody(body)
                }
            }
            start()
        }
    }

    @After
    fun tearDown() { server.shutdown(); root.deleteRecursively() }

    private fun api() = ModrinthApi(server.url("/").toString().trimEnd('/')) { url ->
        val client = okhttp3.OkHttpClient()
        client.newCall(okhttp3.Request.Builder().url(url).build()).execute().use { it.body!!.string() }
    }

    /** Serves downloads by rewriting the CDN host to the mock server (the installer itself only accepts the CDN). */
    private fun installer(): ModInstaller {
        val client = okhttp3.OkHttpClient.Builder().addInterceptor { chain ->
            val original = chain.request()
            val rewritten = original.url.newBuilder().scheme("http").host(server.hostName).port(server.port).build()
            chain.proceed(original.newBuilder().url(rewritten).build())
        }.build()
        return ModInstaller(api(), File(root, "game"), com.quantumobile.launcher.core.net.Downloader(client))
    }

    @Test
    fun detectsTargetFromVersionId() {
        assertEquals(ModTarget("1.21.1", listOf("fabric")), ModTarget.detect("fabric-loader-0.16.5-1.21.1", null))
        assertEquals(ModTarget("26.3", listOf("fabric")), ModTarget.detect("fabric-loader-0.19.5-26.3", "26.3"))
        assertEquals(ModTarget("26.2-rc-1", listOf("fabric")), ModTarget.detect("fabric-loader-0.19.5-26.2-rc-1", null))
        assertEquals(ModTarget("1.20.1", listOf("quilt", "fabric")), ModTarget.detect("quilt-loader-0.26.0-1.20.1", null))
        assertEquals(ModTarget("1.20.1", listOf("forge")), ModTarget.detect("1.20.1-forge-47.2.0", "1.20.1"))
        assertEquals(ModTarget("1.21.1", listOf("neoforge")), ModTarget.detect("neoforge-21.1.5", "1.21.1"))
        assertEquals(ModTarget("1.21.1", emptyList()), ModTarget.detect("1.21.1", null))
        assertTrue(ModTarget.detect("1.8.9", null).isVanilla)
    }

    @Test
    fun searchBuildsFacetsAndParsesHits() = runBlocking {
        val page = api().search("cool", ProjectType.MOD, ModTarget("1.20.1", listOf("quilt", "fabric")), offset = 20)
        assertEquals(1, page.hits.size) // malformed entry skipped
        assertEquals("Cool Mod", page.hits[0].title)
        assertEquals(1234L, page.hits[0].downloads)
        assertTrue(page.hasMore)
        val req = requests.single()
        assertTrue(req, req.contains("""["project_type:mod"]"""))
        assertTrue(req, req.contains("""["versions:1.20.1"]"""))
        assertTrue(req, req.contains("""["categories:quilt","categories:fabric"]"""))
        assertTrue(req, req.contains("offset=20"))
    }

    @Test
    fun resourcePacksIgnoreLoader() = runBlocking {
        api().search("", ProjectType.RESOURCEPACK, fabric)
        val req = requests.single()
        assertFalse(req, req.contains("categories:"))
        assertTrue(req, req.contains("project_type:resourcepack"))
        assertTrue(req, req.contains("index=downloads")) // empty query sorts by popularity
        api().versions("X", fabric, ProjectType.RESOURCEPACK)
        assertFalse(requests.last().contains("loaders="))
    }

    @Test
    fun picksStableReleaseOverNewerBeta() = runBlocking {
        val versions = api().versions("MOD1", fabric)
        assertEquals("v2beta", versions.first().id)
        assertEquals("v1", ModrinthApi.pick(versions)!!.id)
        assertTrue(requests.single().contains("""game_versions=["1.21.1"]"""))
        assertTrue(requests.single().contains("""loaders=["fabric"]"""))
    }

    @Test
    fun installsModWithRequiredDependencyAndRecordsIt() = runBlocking {
        val inst = installer()
        val plan = inst.resolve("MOD1", "Cool Mod", ProjectType.MOD, fabric)
        assertEquals(listOf("MOD1", "DEP1"), plan.items.map { it.projectId })
        assertEquals(listOf(false, true), plan.items.map { it.dependency })
        assertEquals("Dep Lib", plan.items[1].title)
        assertTrue(plan.missing.isEmpty())

        inst.apply(plan)
        assertEquals("mod-bytes", File(root, "game/mods/cool-1.jar").readText())
        assertEquals("dep-bytes", File(root, "game/mods/dep-1.jar").readText())
        assertEquals(setOf("MOD1", "DEP1"), inst.store.list().map { it.projectId }.toSet())

        // Dependencies already installed are not planned again.
        val again = inst.resolve("MOD1", "Cool Mod", ProjectType.MOD, fabric)
        assertEquals(listOf("MOD1"), again.items.map { it.projectId })

        inst.remove(inst.store.list().first { it.projectId == "MOD1" })
        assertFalse(File(root, "game/mods/cool-1.jar").exists())
        assertNull(inst.store.list().firstOrNull { it.projectId == "MOD1" })
    }

    @Test
    fun reportsMissingRequiredDependencyWithoutFailing() = runBlocking {
        val plan = installer().resolve("NEEDSMISSING", "Needs Missing", ProjectType.MOD, fabric)
        assertEquals(listOf("NEEDSMISSING"), plan.items.map { it.projectId })
        assertEquals(listOf("No Version"), plan.missing)
    }

    @Test
    fun failsClearlyWhenNoCompatibleVersion() = runBlocking {
        try {
            installer().resolve("NOVER", "No Version", ProjectType.MOD, fabric)
            fail("expected ModException")
        } catch (e: ModException) {
            assertTrue(e.message!!, e.message!!.contains("1.21.1"))
        }
    }

    @Test
    fun refusesModsOnVanillaButAllowsResourcePacks() = runBlocking {
        val vanilla = ModTarget("1.21.1", emptyList())
        try {
            installer().resolve("MOD1", "Cool", ProjectType.MOD, vanilla)
            fail("expected ModException")
        } catch (e: ModException) {
            assertTrue(e.message!!.contains("mod loader"))
        }
        assertNotNull(installer().resolve("MOD1", "Pack", ProjectType.RESOURCEPACK, vanilla))
    }

    @Test
    fun blocksPathTraversalAndForeignHosts() = runBlocking {
        val inst = installer()
        try {
            inst.apply(inst.resolve("EVIL", "Evil", ProjectType.MOD, fabric))
        } catch (_: Exception) { /* downloads the (sanitised) name or fails; never writes outside */ }
        assertFalse(File(root, "evil.jar").exists())
        assertFalse(File(root, "game/evil.jar").exists())

        try {
            inst.apply(inst.resolve("OFFSITE", "Offsite", ProjectType.MOD, fabric))
            fail("expected ModException")
        } catch (e: ModException) {
            assertTrue(e.message!!.contains("Refusing"))
        }
    }

    @Test
    fun findsUpdates() = runBlocking {
        val inst = installer()
        inst.apply(inst.resolve("MOD1", "Cool Mod", ProjectType.MOD, fabric))
        assertTrue(inst.findUpdates(fabric).isEmpty())
        // Pretend an older build is installed.
        inst.store.put(inst.store.list().first { it.projectId == "MOD1" }.copy(versionId = "old"))
        assertEquals(listOf("MOD1"), inst.findUpdates(fabric).map { it.projectId })
    }
}
