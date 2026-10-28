package com.ims134.dualhost

// Which clone (data folder) this process runs. One process holds one clone.
object ProcessClone {
    @Volatile var id: Int = -1
    // true: the guest app sees DualHost's package name (apps that check they are the real app)
    @Volatile var hostName: Boolean = false

    // The real process name (com.ims134.dualhost:pN), saved at startup. DualHost's own code reads this, never Application.getProcessName()
    @Volatile var realName: String = ""
    val slot: Int? get() = realName.substringAfterLast(":p", "").toIntOrNull()
}