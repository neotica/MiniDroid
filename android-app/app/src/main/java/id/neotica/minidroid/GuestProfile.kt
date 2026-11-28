package id.neotica.minidroid

/**
 * Active guest OS profile supplied by the selected Gradle product flavor
 * (e.g. donut -> Android 1.6, gingerbread -> Android 2.3).
 */
object GuestProfile {
    val flavorName: String get() = BuildConfig.FLAVOR
    val appTitle: String get() = BuildConfig.GUEST_APP_TITLE
    val osName: String get() = BuildConfig.GUEST_OS_NAME
    val apiLevel: Int get() = BuildConfig.GUEST_API_LEVEL
    val ramMb: Int get() = BuildConfig.GUEST_RAM_MB
    val width: Int get() = BuildConfig.GUEST_WIDTH
    val height: Int get() = BuildConfig.GUEST_HEIGHT
    val romVersion: Int get() = BuildConfig.GUEST_ROM_VERSION
    val kernelAppend: String get() = BuildConfig.GUEST_KERNEL_APPEND
}
