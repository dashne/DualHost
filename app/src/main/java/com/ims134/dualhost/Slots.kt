package com.ims134.dualhost

// The one place that lists the process slots. Position = clone number.
object Slots {
    val stubs: List<Class<*>> = listOf(
        StubActivity::class.java,
        StubP1::class.java,
        StubP2::class.java,
        StubP3::class.java,
        StubP4::class.java,
        StubP5::class.java,
        StubP6::class.java,
        StubP7::class.java,
        StubP8::class.java,
        StubP9::class.java,
    )

    val jobs: List<Class<*>> = listOf(
        HostJob0::class.java,
        HostJob1::class.java,
        HostJob2::class.java,
        HostJob3::class.java,
        HostJob4::class.java,
        HostJob5::class.java,
        HostJob6::class.java,
        HostJob7::class.java,
        HostJob8::class.java,
        HostJob9::class.java,
    )

    val receivers: List<Class<*>> = listOf(
        HostReceiver0::class.java,
        HostReceiver1::class.java,
        HostReceiver2::class.java,
        HostReceiver3::class.java,
        HostReceiver4::class.java,
        HostReceiver5::class.java,
        HostReceiver6::class.java,
        HostReceiver7::class.java,
        HostReceiver8::class.java,
        HostReceiver9::class.java,
    )
    val services: List<Class<*>> = listOf(
        HostService0::class.java,
        HostService1::class.java,
        HostService2::class.java,
        HostService3::class.java,
        HostService4::class.java,
        HostService5::class.java,
        HostService6::class.java,
        HostService7::class.java,
        HostService8::class.java,
        HostService9::class.java,
    )
}