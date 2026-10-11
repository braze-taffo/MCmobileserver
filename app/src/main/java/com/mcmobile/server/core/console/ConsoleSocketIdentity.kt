package com.mcmobile.server.core.console

/** UI and :server share a UID; separately installed package clones never share a console. */
object ConsoleSocketIdentity {
    val name: String get() = "mcs.console.${android.os.Process.myUid()}"
}
