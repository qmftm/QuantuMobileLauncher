package com.quantumobile.afkhost

import com.quantumobile.afkhost.ui.HostPanel
import com.quantumobile.afkhost.ui.HostUiActions
import com.quantumobile.launcher.core.afk.AfkPhase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.imageio.ImageIO

class HostPanelTest {
    private val actions = object : HostUiActions {
        override fun copyCode() = Unit
        override fun setAutostart(on: Boolean) = Unit
        override fun hideWindow() = Unit
        override fun quit() = Unit
        override fun openLog() = Unit
        override fun openFolder() = Unit
    }

    private val now = 1_700_000_000_000L

    private fun snapshot(servers: List<ServerRowData>, contact: Long? = now - 3_000, tunnel: String? = "https://calm-river-fox-42.trycloudflare.com") = HostSnapshot(
        name = "DESKTOP-AFK", version = "1.5.5", code = "K7QM-2WNX-9PTD", port = 25580,
        lanAddresses = listOf("192.168.0.12"), tunnelUrl = tunnel, tunnelEnabled = true, lastContactAt = contact,
        servers = servers, autostart = true, autostartAvailable = true,
        logLines = listOf(
            "[21:04:11] QuantuMobile AFK 호스트 1.5.5 시작",
            "[21:04:13] 외부 주소 준비됨: https://calm-river-fox-42.trycloudflare.com",
            "[21:04:20] 마인 서버 (play.example.com): 접속됨 (1.21.4)",
            "[21:07:02] 건축 서버 (build.example.net:25566): 재접속 대기 · 서버가 꺼졌어요",
            "[21:07:30] 연결 코드가 틀린 요청을 거절했어요 (192.168.0.77)",
        ),
        now = now,
    )

    private val servers = listOf(
        ServerRowData("마인 서버", "play.example.com", "qmftm", AfkPhase.CONNECTED, true, "접속됨", "1.21.4", null),
        ServerRowData("건축 서버", "build.example.net:25566", "qmftm", AfkPhase.WAITING, true, "재접속 대기", "서버가 꺼졌어요 · 3분 뒤", null),
        ServerRowData("테스트", "127.0.0.1", "Steve", AfkPhase.ERROR, true, "오류", "화이트리스트에 없어요", null),
        ServerRowData("쉬는 서버", "rest.example.org", "qmftm", AfkPhase.OFF, false, "꺼짐", null, null),
    )

    private fun render(s: HostSnapshot) = HostPanel(actions).also { it.update(s) }.render(940, 740)

    @Test
    fun theWindowDrawsItsCardsAndServers() {
        val img = render(snapshot(servers))
        assertEquals(940, img.width)
        // The header banner is drawn (not the plain window colour) and the window background is dark.
        assertNotEquals(0xFF0E0B1F.toInt(), img.getRGB(300, 50))
        assertEquals(0xFF0E0B1F.toInt(), img.getRGB(3, 3))
        // Something is painted in the servers area, on the right half.
        val distinct = (150 until 400 step 5).flatMap { y -> (400 until 900 step 7).map { x -> img.getRGB(x, y) } }.toSet().size
        assertTrue("the server list has content ($distinct colours)", distinct > 12)
        System.getenv("QML_SHOT_DIR")?.let { dir ->
            File(dir).mkdirs()
            ImageIO.write(img, "png", File(dir, "host-full.png"))
            ImageIO.write(render(snapshot(emptyList(), contact = null, tunnel = null)), "png", File(dir, "host-empty.png"))
        }
    }

    @Test
    fun statusAndTimeTexts() {
        assertEquals("접속됨" to "1.21.4", HostText.status(AfkPhase.CONNECTED, true, "1.21.4", null, 0))
        assertEquals("꺼짐" to null, HostText.status(AfkPhase.CONNECTED, false, "1.21.4", null, 0))
        assertEquals("재접속 대기" to "서버가 꺼졌어요 · 3분 뒤", HostText.status(AfkPhase.WAITING, true, null, "서버가 꺼졌어요", 180_000))
        assertEquals("오류" to "계정 없음", HostText.status(AfkPhase.ERROR, true, null, "계정 없음", 0))
        assertEquals("접속 중" to null, HostText.status(AfkPhase.CONNECTING, true, null, null, 0))
        assertEquals("45초", HostText.waitText(44_100))
        assertEquals("1분 30초", HostText.waitText(90_000))
        assertEquals("10분", HostText.waitText(600_000))
        assertEquals("방금 전", HostText.ago(1_000, 3_000))
        assertEquals("12초 전", HostText.ago(1_000, 13_000))
        assertEquals("3분 전", HostText.ago(0, 190_000))
        assertTrue(HostText.phoneConnected(now - 20_000, now))
        assertTrue(!HostText.phoneConnected(now - 120_000, now))
        assertTrue(!HostText.phoneConnected(null, now))
    }
}
