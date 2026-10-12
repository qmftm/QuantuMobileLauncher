package com.quantumobile.launcher.game

import java.io.File
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.rules.TemporaryFolder

class JreLayoutTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun touch(root: File, path: String) = File(root, path).apply { parentFile.mkdirs(); writeText("x") }

    @Test fun modernLayoutUsesLibAndServer() {
        val home = tmp.newFolder("jre17")
        touch(home, "lib/server/libjvm.so"); touch(home, "lib/jli/libjli.so"); touch(home, "lib/libjava.so"); touch(home, "lib/libzzz.so")
        val l = JreLayout(home)
        assertEquals(File(home, "lib"), l.libDir)
        assertEquals(File(home, "lib/server"), l.jvmDir)
        val names = l.preloadList().map { it.name }
        assertEquals(listOf("libjli.so", "libjvm.so", "libjava.so"), names.take(3))
        assertTrue("libzzz.so" in names)
    }

    @Test fun java8LayoutUsesArchDirAndClientFallback() {
        val home = tmp.newFolder("jre8")
        File(home, "release").writeText("OS_ARCH=\"aarch64\"\n")
        touch(home, "lib/aarch64/client/libjvm.so"); touch(home, "lib/aarch64/jli/libjli.so")
        val l = JreLayout(home)
        assertEquals(File(home, "lib/aarch64"), l.libDir)
        assertEquals(File(home, "lib/aarch64/client"), l.jvmDir)
        assertTrue(l.ldLibraryPath(listOf("/x")).endsWith(":/x"))
    }
}
