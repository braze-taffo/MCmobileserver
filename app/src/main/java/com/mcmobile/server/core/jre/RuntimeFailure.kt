package com.mcmobile.server.core.jre

/** Preserve exception identity even when Android reports a null or blank message. */
internal object RuntimeFailure {
    fun describe(stage: String, failure: Throwable): String {
        val type = failure.javaClass.name
        val detail = failure.message?.takeIf { it.isNotBlank() }
        return "$stage: $type${detail?.let { ": $it" } ?: "（异常未提供详情）"}"
    }
}
