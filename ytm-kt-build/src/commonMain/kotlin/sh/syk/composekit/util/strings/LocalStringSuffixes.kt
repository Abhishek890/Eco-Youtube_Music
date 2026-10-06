package sh.syk.composekit.util.strings

import sh.syk.kmpresources.library.model.Locale
import sh.syk.kmpresources.library.util.selectClosestMatch

private val amountSuffixes =
    listOf(
        Locale("en", "GB") to mapOf("B" to 1_000_000_000, "M" to 1_000_000, "K" to 1_000),
        Locale("ja", "JP") to mapOf("億" to 100_000_000, "万" to 10_000, "千" to 1_000, "百" to 100),
        Locale("zh", "CN") to mapOf("亿" to 100_000_000, "万" to 10_000, "千" to 1_000, "百" to 100),
        Locale("zh", "TW") to mapOf("億" to 100_000_000, "萬" to 10_000, "千" to 1_000, "百" to 100),
        Locale("ru", "RU") to mapOf("млрд." to 1_000_000_000, "млн." to 1_000_000, "тыс." to 1_000)
    )

private val amountLocales = amountSuffixes.map { it.first }

fun getAmountSuffixes(locale: Locale): Map<String, Int> =
    amountSuffixes[amountLocales.selectClosestMatch(locale)].second

data class DurationStringSuffixes(
    val hours: String,
    val minutes: String,
    val seconds: String,
    val splitter: String = ""
)

fun getLocalisedDurationStringSuffixes(locale: Locale?): DurationStringSuffixes {
    when (locale) {
        Locale("zh", "cn") -> return DurationStringSuffixes("时间", "分", "秒")
        Locale("zh", "tw") -> return DurationStringSuffixes("時間", "分", "秒")
    }

    return when (locale?.language) {
        "ja" -> DurationStringSuffixes("時間", "分", "秒")
        "zh" -> DurationStringSuffixes("时间", "分", "秒")
        "fr" -> DurationStringSuffixes("heures", "minutes", "secondes", " ")
        "tr" -> DurationStringSuffixes("saat", "dakika", "saniye", " ")
        "ru" -> DurationStringSuffixes("часы", "минуты", "секунды", " ")
        else -> DurationStringSuffixes("hours", "minutes", "seconds", " ")
    }
}
