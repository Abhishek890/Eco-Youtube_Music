package sh.syk.composekit.util

inline fun <R, T : R, A : Any> T.thenWith(
    value: A?,
    nullAction: T.() -> R = { this },
    action: T.(A) -> R
): R = if (value != null) action(value) else nullAction()
