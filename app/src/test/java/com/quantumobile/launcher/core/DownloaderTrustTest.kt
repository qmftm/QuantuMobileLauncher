package com.quantumobile.launcher.core

import com.quantumobile.launcher.core.net.DownloadTask
import com.quantumobile.launcher.core.net.Downloader
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.MessageDigest

class DownloaderTrustTest {
    @get:Rule val tmp = TemporaryFolder()
    private val server = MockWebServer()

    @Before fun start() = server.start()
    @After fun stop() = server.shutdown()

    private fun sha1(b: ByteArray) = MessageDigest.getInstance("SHA-1").digest(b).joinToString("") { "%02x".format(it) }

    private val good = "hello world".toByteArray()
    private val sameSizeWrong = "HELLO WORLD".toByteArray()

    private fun task(dest: File, trust: Boolean) =
        DownloadTask(server.url("/x").toString(), dest, sha1(good), good.size.toLong(), trustIfSizeMatches = trust)

    @Test
    fun trustedFileWithTheRightSizeIsNotHashedOrDownloaded() = runBlocking {
        val dest = File(tmp.root, "asset").apply { writeBytes(sameSizeWrong) }
        Downloader().download(task(dest, trust = true))
        assertEquals(0, server.requestCount) // launch does not touch the network or hash the file
        assertEquals("HELLO WORLD", dest.readText())
    }

    @Test
    fun trustedFileWithTheWrongSizeIsFetchedAgain() = runBlocking {
        val dest = File(tmp.root, "asset").apply { writeText("short") }
        server.enqueue(MockResponse().setBody(okio.Buffer().write(good)))
        Downloader().download(task(dest, trust = true))
        assertEquals(1, server.requestCount)
        assertEquals("hello world", dest.readText())
    }

    @Test
    fun aMissingFileIsDownloadedAndHashChecked() = runBlocking {
        val dest = File(tmp.root, "asset")
        server.enqueue(MockResponse().setBody(okio.Buffer().write(good)))
        Downloader().download(task(dest, trust = true))
        assertEquals("hello world", dest.readText())
    }

    @Test
    fun untrustedFilesAreStillHashed() = runBlocking {
        val dest = File(tmp.root, "lib").apply { writeBytes(sameSizeWrong) } // right size, wrong content
        server.enqueue(MockResponse().setBody(okio.Buffer().write(good)))
        Downloader().download(task(dest, trust = false))
        assertEquals(1, server.requestCount)
        assertEquals("hello world", dest.readText())
    }
}
