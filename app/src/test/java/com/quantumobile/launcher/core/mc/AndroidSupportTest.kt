package com.quantumobile.launcher.core.mc

import org.junit.Test
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

class AndroidSupportTest {
    @Test fun glfwEraVersionsKeepTheGlfwBackend() {
        assertFalse(AndroidSupport.needsSdl(listOf("org.lwjgl:lwjgl:3.3.3", "org.lwjgl:lwjgl-glfw:3.3.3")))
        assertFalse(AndroidSupport.needsSdl(listOf("org.lwjgl:lwjgl:3.3.6")))
        assertFalse(AndroidSupport.needsSdl(emptyList()))
    }

    @Test fun lwjgl34AndSdlNeedTheSdlBackend() {
        assertTrue(AndroidSupport.needsSdl(listOf("org.lwjgl:lwjgl:3.4.3")))
        assertTrue(AndroidSupport.needsSdl(listOf("org.lwjgl:lwjgl:3.3.3", "org.lwjgl:lwjgl-sdl:3.4.3")))
    }
}
