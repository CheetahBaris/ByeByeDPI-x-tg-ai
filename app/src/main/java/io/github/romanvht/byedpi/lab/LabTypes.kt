package io.github.romanvht.byedpi.lab

import java.util.LinkedHashMap

/**
 * Через какую сеть ходить.
 *
 * Нужно, чтобы можно было держать Wi-Fi (там LM Studio) и одновременно
 * тестировать мобильный интернет: трафик сайтов принудительно уходит
 * в сотовую сеть, а запросы к модели — в Wi-Fi.
 */
enum class NetTarget {
    /** Сеть по умолчанию (что выбрала система). */
    AUTO,

    /** Активная сеть системы. */
    SYSTEM,

    WIFI,
    CELLULAR,
    ETHERNET,

    /**
     * Кабель/adb reverse: LM Studio доступен по 127.0.0.1, сеть для этого
     * не нужна. Запросы идут без привязки к сети.
     */
    USB
}

/**
 * Как искать LM Studio:
 *  - [BOTH]  — локальная сеть + USB/adb reverse;
 *  - [WIFI]  — только локальная сеть;
 *  - [USB]   — только 127.0.0.1 через `adb reverse`;
 *  - [LOCAL] — сервер запущен на самом телефоне и слушает 127.0.0.1:1234.
 */
enum class ScanMode { BOTH, WIFI, USB, LOCAL }

/** Результат по одному сайту: успешных попыток / всего. */
data class SiteStat(val ok: Int, val total: Int)

/** Результат одной стратегии в раунде. */
data class StrategyResult(
    val strategy: String,
    val perSite: LinkedHashMap<String, SiteStat> = LinkedHashMap(),
    val errors: MutableMap<String, Int> = LinkedHashMap(),
    val sec: Long = 0,
    val engineError: String? = null
) {
    val ok: Int get() = perSite.values.sumOf { it.ok }
    val total: Int get() = perSite.values.sumOf { it.total }
    val score: Double get() = if (total == 0) 0.0 else ok.toDouble() / total

    /** Среднее время на сайт, мс. */
    val meanMs: Long get() = if (perSite.isEmpty()) 0 else sec * 1000 / perSite.size
}

/** Один раунд: baseline + набор стратегий. */
data class LabRun(
    val id: String,
    val created: String,
    val sites: List<String>,
    val repeats: Int,
    val baseline: StrategyResult?,
    val results: List<StrategyResult>,
    val network: String? = null
)

data class Finding(
    val arg: String = "",
    val effect: String = "",
    val confidence: String = "",
    val evidence: String = ""
)

data class Candidate(val args: String = "", val why: String = "")

data class LlmAnalysis(
    val summary: String = "",
    val dataQuality: String = "",
    val findings: List<Finding> = emptyList(),
    val hypotheses: List<String> = emptyList(),
    val next: List<Candidate> = emptyList(),
    val experiment: String = "",
    val apply: String = ""
)

/** Итог всего запуска лаборатории. */
data class LabResult(
    val bestStrategy: String = "",
    val bestScore: Double = 0.0,
    val rounds: Int = 0,
    val stopReason: String = "",
    val seconds: Long = 0,
    /** Топ стратегий запуска: записывается в поле «Стартовые стратегии». */
    val topStrategies: List<String> = emptyList()
)

/** Живой прогресс для GUI. */
data class LabProgress(
    val phase: String = "",
    val round: Int = 0,
    val index: Int = 0,
    val total: Int = 0,
    val message: String = "",
    val score: Double = 0.0,
    val bestStrategy: String = "",
    val bestScore: Double = 0.0
)
