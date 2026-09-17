package com.mcmobile.server.core.console

import org.junit.Assert.assertEquals
import org.junit.Test

class AnsiTest {

    @Test
    fun strip_removesColorCodes() {
        // MC/NeoForge 实际产出的形态：ESC[32m 开头、ESC[m 收尾
        assertEquals(
            "[15:43:30] [main/INFO] [Launcher/MODLAUNCHER]: ModLauncher running",
            Ansi.strip("\u001B[32m[15:43:30] [main/INFO] [Launcher/MODLAUNCHER]: ModLauncher running\u001B[m"),
        )
        // 带参数与不带参数的 CSI 都要处理
        assertEquals("bold", Ansi.strip("\u001B[0;1mbold"))
        assertEquals("reset", Ansi.strip("reset\u001B[m"))
    }

    @Test
    fun strip_leavesPlainTextAlone() {
        // 普通文本里的方括号不能被误删，没有 ESC 时原样返回
        assertEquals("[Server thread/INFO]: Done (9.6s)!", Ansi.strip("[Server thread/INFO]: Done (9.6s)!"))
        assertEquals("plain", Ansi.strip("plain"))
    }
}
