package sh.syk.kmpresources.library.util

import sh.syk.kmpresources.library.model.Locale

internal actual fun getSystemLocaleImpl(): Locale? =
    java.util.Locale.getDefault().toKmpResourcesLocale()
