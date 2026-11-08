package io.github.romanvht.byedpi.lab

/** Настройки лаборатории, приходят из SharedPreferences (LabActivity). */
data class LabConfig(
    val baseUrl: String = "http://127.0.0.1:1234/v1",
    val model: String = "",
    val apiKey: String = "",
    val rounds: Int = 3,
    val repeats: Int = 2,
    val sites: List<String> = emptyList(),
    /** Свои стратегии из поля ввода (используются, только если включён флажок). */
    val strategies: List<String> = emptyList(),
    /** Стратегии из встроенной базы приложения (proxytest_strategies.list). */
    val baseStrategies: List<String> = emptyList(),
    /** Первый раунд начинается с прогона встроенной базы. */
    val useBaseStrategies: Boolean = false,
    /** Добавлять к первому раунду стратегии из поля ввода. */
    val useOwnStrategies: Boolean = true,
    val maxMinutes: Int = 120,
    val target: Double = 1.0,
    val patience: Int = 2,
    val minImprovement: Double = 0.02,
    val forbidD1: Boolean = true,
    val sni: String = "google.com",
    val port: Int = 1081,
    val timeoutSec: Long = 6,
    val maxCandidates: Int = 14,
    /** Лимит токенов ответа модели. Для локальных 4B-моделей 900–1200. */
    val maxTokens: Int = 1200,
    /** Контекст модели в токенах: для проверки бюджета промпта. 0 — не проверять. */
    val contextTokens: Int = 8192,
    /** Сколько ждать ответ LLM, секунд. Локальные модели на телефоне могут думать долго. */
    val llmTimeoutSec: Int = 300,
    /** Редактируемая база знаний; добавляется в системный промпт. */
    val knowledgeBase: String = "",
    val useKnowledge: Boolean = true,
    /** Редактируемый системный промпт. */
    val systemPrompt: String = "",
    /** Необязательные параметры REST API; по умолчанию не отправляются. */
    val llmParams: LlmParams = LlmParams.EMPTY,
    val reuseBaseline: Boolean = true,
    val autoApply: Boolean = false,
    val concurrency: Int = 8,
    /** Через какую сеть ходить к LLM (Wi-Fi, кабель/loopback, мобильная...). */
    val llmNetwork: NetTarget = NetTarget.AUTO,
    /** Через какую сеть проверять сайты. */
    val testNetwork: NetTarget = NetTarget.SYSTEM,
    /** Как искать LM Studio. */
    val scanMode: ScanMode = ScanMode.BOTH,
    /** Поднимать сеть, если она есть в системе, но не активна (например, LTE при Wi-Fi). */
    val bringUpNetwork: Boolean = true
)