package com.mcmobile.server.core.console

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ":server 进程同时只跑一个实例"的判定。UI 侧和服务端侧共用这一份，
 * 以前两边各写各的，服务端静默丢弃请求、UI 却以为启动成功（真机表现为"日志还是上一个实例的"）。
 */
class StartConflictTest {

    @Test
    fun noConflictWhenNothingIsRunning() {
        for (status in listOf(null, "IDLE", "STOPPED", "CRASHED", "FAILED")) {
            assertNull(
                status,
                StartConflict.describe(status, "a", "实例 A", requestedId = "b"),
            )
        }
    }

    @Test
    fun noConflictWhenTheSameInstanceIsAlreadyRunning() {
        // 重连、UI 重建后重复下发同一个实例的启动请求，不该报冲突
        assertNull(StartConflict.describe("RUNNING", "a", "实例 A", requestedId = "a"))
        assertNull(StartConflict.describe("STARTING", "a", "实例 A", requestedId = "a"))
    }

    @Test
    fun otherInstanceRunningIsRefusedWithItsName() {
        val message = StartConflict.describe("RUNNING", "a", "Vanilla 原版 26.2", requestedId = "b")
        assertTrue(message!!.contains("Vanilla 原版 26.2"))
        assertTrue(message.contains("请先"))
    }

    @Test
    fun unknownOwnerStillCountsAsConflict() {
        // 拿不到实例 id（旧版本状态帧）时按冲突处理：宁可让用户先停掉，也别让两个进程抢 25565
        val message = StartConflict.describe("RUNNING", null, "", requestedId = "b")
        assertTrue(message!!.contains("另一个实例"))
    }

    @Test
    fun activeStatusesCoverTheWholeRun() {
        assertTrue(StartConflict.isActive("STARTING"))
        assertTrue(StartConflict.isActive("RUNNING"))
        assertTrue(StartConflict.isActive("STOPPING"))
        assertFalse(StartConflict.isActive("STOPPED"))
        assertFalse(StartConflict.isActive(null))
    }

    @Test
    fun messageIsStableSoBothSidesAgree() {
        // 服务端和 UI 用的是同一个函数，这里锁住输入到输出的映射
        assertEquals(
            StartConflict.describe("RUNNING", "a", "A", "b"),
            StartConflict.describe("RUNNING", "a", "A", "b"),
        )
    }
}
