package com.mcmobile.server.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class EulaTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun notAcceptedByDefault_and_acceptWrites() {
        val dir = tmp.newFolder("inst")
        assertFalse(Eula.isAccepted(dir))

        Eula.accept(dir)
        assertTrue(Eula.isAccepted(dir))

        // 大小写/前后缀容错
        dir.resolve("eula.txt").writeText("#x\neula=TRUE\n")
        assertTrue(Eula.isAccepted(dir))
        dir.resolve("eula.txt").writeText("eula=false\n")
        assertFalse(Eula.isAccepted(dir))
    }
}
