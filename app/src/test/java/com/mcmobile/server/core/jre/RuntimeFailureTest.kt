package com.mcmobile.server.core.jre

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class RuntimeFailureTest {
    @Test fun messageLessExceptionsRetainTypeAndStage() {
        for (message in listOf(null, "", " \n\t")) {
            val description = RuntimeFailure.describe("Java 21 解压", IOException(message))
            assertTrue(description.contains("Java 21 解压"))
            assertTrue(description.contains("java.io.IOException"))
            assertTrue(description.contains("异常未提供详情"))
        }
    }

    @Test fun fileSystemFailureRetainsOriginalDetail() {
        val description = RuntimeFailure.describe("Java 21 补齐原生库", IOException("ENOSPC: No space left on device"))
        assertTrue(description.contains("ENOSPC: No space left on device"))
        assertTrue(description.contains("java.io.IOException"))
    }
}
