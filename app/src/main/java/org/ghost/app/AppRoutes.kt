package org.ghost.app

/** Stable top-level destinations supplied to the final Compose navigation host. */
enum class AppRoute {
    HOME,
    REQUESTS,
    OFFER_HELP,
    NETWORK,
}

fun interface AppNavigator {
    fun navigate(route: AppRoute)
}
