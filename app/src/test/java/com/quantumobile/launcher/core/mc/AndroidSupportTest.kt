package com.quantumobile.launcher.core.mc

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class AndroidSupportTest {
    @Test fun lwjgl33VersionsAreSupported() {
        assertNull(AndroidSupport.unsupportedReason(listOf("org.lwjgl:lwjgl:3.3.3", "org.lwjgl:lwjgl-glfw:3.3.3", "com.google.guava:guava:33.0")))
        assertNull(AndroidSupport.unsupportedReason(listOf("org.lwjgl:lwjgl:3.2.2", "org.lwjgl:lwjgl:3.3.1@jar")))
        assertNull(AndroidSupport.unsupportedReason(emptyList()))
    }

    @Test fun newerLwjglOrSdlIsRejected() {
        assertNotNull(AndroidSupport.unsupportedReason(listOf("org.lwjgl:lwjgl:3.4.0")))
        assertNotNull(AndroidSupport.unsupportedReason(listOf("org.lwjgl:lwjgl:3.10.1")))
        assertNotNull(AndroidSupport.unsupportedReason(listOf("org.lwjgl:lwjgl-sdl:3.3.3")))
    }
}
