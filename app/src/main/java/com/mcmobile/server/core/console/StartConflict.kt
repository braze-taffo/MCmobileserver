package com.mcmobile.server.core.console

/**
 * ":server 进程同时只跑一个实例"这条规则的唯一实现：UI 侧和 :server 侧都用它来判断。
 *
 * 之前两边各写各的，服务端遇到第二个启动请求直接 `return` 丢掉，UI 却以为启动成功并跳到控制台，
 * 于是用户看到的是上一个实例的日志——像是"删除没生效"。判定集中在这里，两边就不会再各说各话。
 */
object StartConflict {

    /** 服务器进程认为"有实例在跑"的状态 */
    val ACTIVE_STATUSES = setOf("RUNNING", "STARTING", "STOPPING")

    fun isActive(status: String?): Boolean = status in ACTIVE_STATUSES

    /**
     * 已经在跑服务器时，还能不能启动 [requestedId]？
     *
     * @param runningStatus 当前状态（RunStatus 名）
     * @param runningInstanceId 正在跑的实例 id（拿不到时为 null）
     * @param requestedId 想要启动的实例 id
     * @return 不能启动时返回给用户看的说明；可以启动返回 null
     */
    fun describe(
        runningStatus: String?,
        runningInstanceId: String?,
        runningName: String,
        requestedId: String,
    ): String? {
        if (!isActive(runningStatus)) return null
        // 同一个实例重复下发启动请求（重连、UI 重建）不算冲突
        if (runningInstanceId != null && runningInstanceId == requestedId) return null
        // 正在跑的实例已被删除时 runningInstanceId 对不上任何实例，同样按冲突处理：
        // 必须先把那个进程停掉，否则它会一直占着端口
        val label = runningName.ifBlank { "另一个实例" }
        return "已有服务器在运行：$label。请先在控制台停止它，再启动本实例。"
    }
}
