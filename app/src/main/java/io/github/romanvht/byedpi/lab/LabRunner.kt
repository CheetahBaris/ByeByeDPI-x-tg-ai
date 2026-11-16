package io.github.romanvht.byedpi.lab

import android.content.Context
import android.net.Network
import androidx.core.content.edit
import io.github.romanvht.byedpi.data.AppStatus
import io.github.romanvht.byedpi.data.Mode
import io.github.romanvht.byedpi.services.NativeEngine
import io.github.romanvht.byedpi.services.ServiceManager
import io.github.romanvht.byedpi.services.appStatus
import io.github.romanvht.byedpi.utility.HistoryUtils
import io.github.romanvht.byedpi.utility.SiteCheckUtils
import io.github.romanvht.byedpi.utility.getPreferences
import io.github.romanvht.byedpi.utility.testConfiguration
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket
import java.util.LinkedHashMap

/**
 * Цикл подбора: раунд -> анализ LLM -> новые стратегии -> ...
 *
 * Движок запускается в режиме Proxy на отдельном порту (1081), чтобы не мешать
 * основному прокси приложения на 1080. Лучшая стратегия сохраняется в историю
 * и (опционально) автоматически применяется в настройки.
 */
class LabRunner(
    private val ctx: Context,
    private val cfg: LabConfig,
    private val store: LabStore,
    private val log: (String) -> Unit,
    private val progress: (LabProgress) -> Unit = {}
) {

    private var llm: LlmClient? = null
    private val history = ArrayList<String>()
    private var testNetwork: Network? = null
    private var llmNetwork: Network? = null
    private var bindWarned = false

    /** Нормализованные стратегии из встроенной базы: для них разрешён -d1. */
    private val d1Allowed = HashSet<String>()

    suspend fun run(): LabResult {
        try {
            return runInternal()
        } finally {
            withContext(NonCancellable + Dispatchers.IO) {
                LabNetwork.bindAppProcess(ctx, null)
                LabNetwork.release(ctx)
            }
        }
    }

    private suspend fun runInternal(): LabResult {
        val started = System.currentTimeMillis()
        var round = 0
        var bestScore = 0.0
        var bestStrategy = ""
        var lastImprove = 0
        var stopReason = "завершено"
        var baseline: StrategyResult? = null

        val tried = LinkedHashSet<String>()

        cfg.baseStrategies.forEach { raw ->
            val p = StrategyArgs.parse(raw, cfg.sni)
            if (p.ok && p.normalized.isNotBlank()) d1Allowed.add(p.normalized)
        }

        fun normalized(raw: String): String? {
            val p = StrategyArgs.parse(raw, cfg.sni)
            if (!p.ok || p.normalized.isBlank()) return null
            if (cfg.forbidD1 && isD1(p.argv) && p.normalized !in d1Allowed) return null
            return p.normalized
        }

        logConfig()

        val stored = runCatching { store.lastBest() }.getOrNull()
        if (stored != null && stored.score > 0 && stored.strategy.isNotBlank()) {
            log("Прошлый лучший результат: ${pct(stored.score)} — ${stored.strategy}")
        }

        val pending = LinkedHashSet<String>()
        if (stored != null) normalized(stored.strategy)?.let { pending.add(it) }

        val baseSeed = LinkedHashSet<String>()
        if (cfg.useBaseStrategies) {
            cfg.baseStrategies.forEach { raw ->
                normalized(raw)?.let { baseSeed.add(it) }
            }
        }
        val ownSeed = LinkedHashSet<String>()
        if (cfg.useOwnStrategies) {
            cfg.strategies.forEach { raw ->
                normalized(raw)?.let { ownSeed.add(it) }
            }
        }
        pending.addAll(baseSeed)
        pending.addAll(ownSeed)
        if (pending.isEmpty()) {
            pending.addAll(StrategyMutations.systematic(cfg, emptySet(), cfg.maxCandidates))
        }
        log(
            "Стартовый раунд: прошлый лучший ${if (stored?.strategy.isNullOrBlank()) "нет" else "да"}, " +
                "из базы ${baseSeed.size}, своих ${ownSeed.size}, всего ${pending.size}"
        )

        testNetwork = LabNetwork.acquire(ctx, cfg.testNetwork, cfg.bringUpNetwork)
        if (cfg.testNetwork == NetTarget.CELLULAR && testNetwork == null) {
            throw IllegalStateException(
                "Мобильная сеть недоступна: включи мобильные данные " +
                    "или выбери другую сеть для тестов"
            )
        }
        log(
            "Тестовая сеть: ${LabNetwork.targetLabel(cfg.testNetwork)}" +
                (testNetwork?.let { " — ${LabNetwork.label(ctx, it)}" } ?: " — сеть не найдена")
        )
        val localLlm = LabNetwork.isLoopbackUrl(cfg.baseUrl)
        if (!localLlm &&
            cfg.llmNetwork == NetTarget.CELLULAR && cfg.testNetwork == NetTarget.CELLULAR
        ) {
            log("Внимание: LLM и тесты в мобильной сети — локальный LM Studio будет недоступен")
        }
        if (cfg.llmNetwork == NetTarget.WIFI && cfg.testNetwork == NetTarget.CELLULAR) {
            log("Режим: модель по Wi-Fi, трафик сайтов — через мобильную сеть")
        }

        val hasLlm = cfg.baseUrl.isNotBlank()
        if (hasLlm) {
            llmNetwork = if (cfg.llmNetwork == NetTarget.USB || localLlm) {
                null
            } else {
                LabNetwork.acquire(ctx, cfg.llmNetwork, cfg.bringUpNetwork)
            }
            llm = LlmClient(
                baseUrl = cfg.baseUrl,
                model = cfg.model,
                apiKey = cfg.apiKey,
                network = llmNetwork,
                readTimeoutMs = cfg.llmTimeoutSec.coerceIn(10, 1800) * 1000,
                params = cfg.llmParams,
                session = cfg.useSession,
                contextTokens = cfg.contextTokens,
                onLog = log
            )
            log(
                "LLM: ${llm?.baseUrl} (model=${cfg.model.ifBlank { "auto" }}) через " +
                    LabNetwork.targetLabel(cfg.llmNetwork) +
                    when {
                        localLlm -> " — локально, без привязки к сети"
                        llmNetwork != null -> " — ${LabNetwork.label(ctx, llmNetwork)}"
                        cfg.llmNetwork == NetTarget.USB -> " — без привязки к сети"
                        else -> " — сеть не найдена"
                    }
            )
        } else {
            log("LLM отключена: работают только детерминированные мутации")
        }
        log("Сайтов: ${cfg.sites.size}, повторов: ${cfg.repeats}, раундов: ${cfg.rounds}")
        log("Стартовых стратегий: ${pending.size}")

        while (currentCoroutineContext().isActive) {
            if (round >= cfg.rounds) {
                stopReason = "достигнуто число раундов"
                break
            }
            val elapsedMin = (System.currentTimeMillis() - started) / 60_000.0
            if (elapsedMin >= cfg.maxMinutes) {
                stopReason = "лимит времени"
                break
            }
            if (bestScore >= cfg.target) {
                stopReason = "цель достигнута (${pct(bestScore)})"
                break
            }
            if (round > 0 && cfg.patience > 0 && round - lastImprove >= cfg.patience) {
                stopReason = "нет прироста ${cfg.patience} раунд(а)"
                break
            }

            round++

            // Baseline нужен один раз, если включено переиспользование.
            if (baseline == null || !cfg.reuseBaseline) {
                progress(LabProgress(phase = "baseline", round = round, message = "Измеряю baseline…"))
                val b = probeStrategy("")
                log("baseline: ${b.ok}/${b.total} (${pct(b.score)})${perSiteDetail(b)}")
                if (baseline == null || !cfg.reuseBaseline) baseline = b
            }

            val run = runRound(round, pending.toList(), baseline, bestStrategy, bestScore)
            runCatching { store.saveRun(run) }
            runCatching { store.recordRun(run) }
            val scoreBefore = bestScore
            val roundBest = run.results
                .filter { it.engineError == null && it.total > 0 && it.strategy.isNotBlank() }
                .maxByOrNull { it.score }
            if (roundBest != null) {
                if (roundBest.score > bestScore + 1e-9) {
                    bestScore = roundBest.score
                    bestStrategy = roundBest.strategy
                }
                if (roundBest.score > scoreBefore + cfg.minImprovement) {
                    lastImprove = round
                    log("↑ Новый лучший: ${pct(bestScore)} — $bestStrategy")
                }
            }

            tried += run.results.map { it.strategy }.filter { it.isNotBlank() }

            // После первого прогона встроенной базы оставляем для LLM только топ
            // «Кандидатов»: модель не тонет в 60 строках и видит лучшие результаты.
            val analysisRun = if (round == 1 && cfg.useBaseStrategies &&
                run.results.size > cfg.maxCandidates
            ) {
                val top = run.results
                    .filter { it.engineError == null && it.total > 0 && it.strategy.isNotBlank() }
                    .sortedByDescending { it.score }
                    .take(cfg.maxCandidates)
                log("Базовый прогон: ${run.results.size} стратегий, для анализа отобрано топ-${top.size}")
                top.forEachIndexed { i, r ->
                    log("  топ-${i + 1}: ${r.ok}/${r.total} ${pct(r.score)} ${r.strategy}")
                }
                if (top.isNotEmpty()) run.copy(results = top) else run
            } else {
                run
            }

            if (bestScore >= cfg.target) {
                stopReason = "цель достигнута (${pct(bestScore)})"
                runCatching { store.saveReport(analysisRun, null, bestStrategy, bestScore) }
                break
            }

            val analysis = if (hasLlm) {
                progress(LabProgress(
                    phase = "analyze",
                    round = round,
                    message = "Анализ раунда моделью…",
                    bestStrategy = bestStrategy,
                    bestScore = bestScore
                ))
                try {
                    analyze(analysisRun, bestStrategy, bestScore, tried)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log("LLM недоступна: ${e.message}")
                    null
                }
            } else {
                null
            }
            runCatching {
                store.saveReport(analysisRun, analysis, bestStrategy, bestScore)
            }

            val candidates = pickCandidates(analysisRun, analysis, tried, bestStrategy)
            if (candidates.isEmpty()) {
                stopReason = "нет новых кандидатов"
                log("СТОП: кандидаты закончились")
                break
            }

            pending.clear()
            pending.addAll(candidates)
            log("Следующий раунд: ${candidates.size} стратегий")
            candidates.forEachIndexed { i, s -> log("  ${i + 1}. $s") }
        }

        // Финальная проверка лучшей стратегии с большим числом повторов:
        // защищает от случайных 100% на одном прогоне.
        if (bestStrategy.isNotBlank() && bestScore > 0) {
            val repeatsForCheck = (cfg.repeats + 1).coerceAtMost(6)
            progress(LabProgress(
                phase = "validate",
                round = round,
                message = "Проверяю лучшую стратегию…",
                bestStrategy = bestStrategy,
                bestScore = bestScore
            ))
            val v = probeStrategy(bestStrategy, repeatsForCheck)
            log(
                "Проверка лучшей (${v.total} запросов): ${v.ok}/${v.total} " +
                    "(${pct(v.score)})${perSiteDetail(v)}"
            )
            if (v.score >= bestScore) bestScore = v.score

            val validationRun = LabRun(
                id = "${LabStore.timestamp()}-check",
                created = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
                    .format(java.util.Date()),
                sites = cfg.sites,
                repeats = repeatsForCheck,
                baseline = baseline,
                results = listOf(v),
                network = networkSummary()
            )
            runCatching { store.saveRun(validationRun) }
            runCatching { store.recordRun(validationRun) }
        }

        val topStrategies = runCatching {
            store.bestStrategies()
                .filter {
                    it.strategy.isNotBlank() && it.score > 0 &&
                        !(cfg.forbidD1 && isD1(StrategyArgs.parse(it.strategy, cfg.sni).argv))
                }
                .take(cfg.maxCandidates)
                .map { it.strategy }
        }.getOrDefault(emptyList())

        val result = LabResult(
            bestStrategy = bestStrategy,
            bestScore = bestScore,
            rounds = round,
            stopReason = stopReason,
            seconds = (System.currentTimeMillis() - started) / 1000,
            topStrategies = topStrategies
        )
        runCatching { store.saveResult(result) }

        if (topStrategies.isNotEmpty()) {
            log("Топ стратегий (${topStrategies.size}) для следующего запуска:")
            topStrategies.forEachIndexed { i, s -> log("  ${i + 1}. $s") }
        }

        if (cfg.autoApply && bestStrategy.isNotBlank() && bestScore > 0) {
            applyBest(bestStrategy)
        }

        log("Готово (${result.seconds} с). Лучшее: ${bestStrategy.ifBlank { "нет" }} (${pct(bestScore)})")
        log("Причина остановки: $stopReason")
        progress(LabProgress(
            phase = "done",
            round = round,
            message = "Готово: ${pct(bestScore)}",
            bestStrategy = bestStrategy,
            bestScore = bestScore
        ))
        return result
    }

    // ------------------------------------------------------------------ раунд

    private suspend fun runRound(
        round: Int,
        strategies: List<String>,
        baseline: StrategyResult?,
        bestStrategy: String,
        bestScore: Double
    ): LabRun {
        val id = "${LabStore.timestamp()}-r$round"
        val results = ArrayList<StrategyResult>()

        log("── Раунд $round ($id): ${strategies.size} стратегий")

        for ((i, strategy) in strategies.withIndex()) {
            currentCoroutineContext().ensureActive()
            progress(LabProgress(
                phase = "probe",
                round = round,
                index = i + 1,
                total = strategies.size,
                message = strategy,
                bestStrategy = bestStrategy,
                bestScore = bestScore
            ))
            val r = probeStrategy(strategy)
            results.add(r)
            log(
                "  ${r.ok}/${r.total}  ${pct(r.score)}  ${strategy.ifBlank { "<baseline>" }}" +
                    "  ${r.sec}с" +
                    perSiteDetail(r) +
                    if (r.engineError != null) "  [${r.engineError}]" else ""
            )
        }

        return LabRun(
            id = id,
            created = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
                .format(java.util.Date()),
            sites = cfg.sites,
            repeats = cfg.repeats,
            baseline = baseline,
            results = results,
            network = networkSummary()
        )
    }

    private fun networkSummary(): String = buildString {
        append("тест: ").append(LabNetwork.targetLabel(cfg.testNetwork))
        testNetwork?.let { append(" — ").append(LabNetwork.label(ctx, it)) }
        append("; LLM: ").append(LabNetwork.targetLabel(cfg.llmNetwork))
    }

    private suspend fun probeStrategy(
        strategy: String,
        repeats: Int = cfg.repeats
    ): StrategyResult {
        val parsed = StrategyArgs.parse(strategy, cfg.sni)
        if (!parsed.ok) {
            return StrategyResult(strategy, engineError = "невалидно: ${parsed.error}")
        }
        if (cfg.forbidD1 && isD1(parsed.argv) && parsed.normalized !in d1Allowed) {
            return StrategyResult(strategy, engineError = "запрещён -d 1")
        }

        val t0 = System.currentTimeMillis()
        val configuration = withContext(Dispatchers.IO) {
            runCatching { testConfiguration(ctx, parsed.normalized, "127.0.0.1", cfg.port) }
        }.getOrElse {
            return StrategyResult(parsed.normalized, engineError = "config: ${it.message}")
        }

        var engine: NativeEngine? = null
        // Привязываем процесс приложения к тестовой сети: тогда DNS и любые
        // прямые соединения тоже уходят в неё, а не в Wi-Fi по умолчанию.
        if (testNetwork != null && !LabNetwork.bindAppProcess(ctx, testNetwork) && !bindWarned) {
            bindWarned = true
            log("Не удалось привязать приложение к тестовой сети, проверь разрешения")
        }
        return try {
            withContext(Dispatchers.IO) {
                val e = NativeEngine(ctx, Mode.Proxy, foreground = false)
                engine = e
                e.connect()
                e.setNetwork(testNetwork)
                e.start(configuration)
            }
            delay(150)
            if (!waitForPort(cfg.port, 4_000, open = true)) {
                log("Порт ${cfg.port} не открылся (pid=${engine?.processId})")
                return StrategyResult(parsed.normalized, engineError = "движок не поднялся")
            }

            val pairs = withContext(Dispatchers.IO) {
                SiteCheckUtils("127.0.0.1", cfg.port).checkSitesAsync(
                    sites = cfg.sites,
                    requestsCount = repeats,
                    requestTimeout = cfg.timeoutSec,
                    concurrentRequests = cfg.concurrency.coerceAtLeast(1),
                    fullLog = false,
                    onSiteChecked = null
                )
            }

            val perSite = LinkedHashMap<String, SiteStat>()
            for (p in pairs) perSite[p.first] = SiteStat(p.second, repeats)

            StrategyResult(
                strategy = parsed.normalized,
                perSite = perSite,
                sec = (System.currentTimeMillis() - t0) / 1000
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            StrategyResult(
                parsed.normalized,
                engineError = e.message?.take(120) ?: e.javaClass.simpleName
            )
        } finally {
            withContext(NonCancellable + Dispatchers.IO) {
                runCatching { engine?.stop() }
                waitForPort(cfg.port, 2_500, open = false)
                LabNetwork.bindAppProcess(ctx, null)
            }
        }
    }

    /** Ждёт, пока порт откроется (open=true) или освободится (open=false). */
    private suspend fun waitForPort(port: Int, timeoutMs: Long, open: Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            // Обязательно вне главного потока: иначе Android бросает
            // NetworkOnMainThreadException и проверка всегда «не поднялся».
            val connected = withContext(Dispatchers.IO) {
                runCatching {
                    Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 250) }
                }.isSuccess
            }
            if (connected == open) return true
            delay(120)
        }
        return false
    }

    // ------------------------------------------------------------- LLM/отбор

    private suspend fun analyze(
        run: LabRun,
        best: String,
        bestScore: Double,
        tried: Collection<String>
    ): LlmAnalysis {
        val client = llm ?: throw IllegalStateException("LLM не настроена")
        val system = PromptBuilder.system(
            knowledgeBase = cfg.knowledgeBase,
            useKnowledge = cfg.useKnowledge,
            systemPrompt = cfg.systemPrompt,
            useSystemPrompt = cfg.useSystemPrompt
        )
        val systemTokens = PromptBuilder.estimateTokens(system)
        val reuseSession = cfg.useSession && client.hasSessionHistory()

        // Сжимаем промпт сами: история и список проверенных съедают контекст
        // быстрее всего, а таблицу результатов модель должна видеть целиком.
        var triedLimit = 15
        var historyLimit = 3
        var user: String
        var promptTokens: Int
        while (true) {
            user = PromptBuilder.user(
                run = run,
                history = history,
                bestStrategy = best,
                bestScore = bestScore,
                tried = tried,
                maxCandidates = cfg.maxCandidates,
                minImprovement = cfg.minImprovement,
                triedLimit = triedLimit,
                historyLimit = historyLimit
            )
            // В одной сессии system/правила/база уже лежат в истории:
            // повторно их не отправляем и не считаем как новые токены.
            val sessionPart = if (cfg.useSession) client.sessionTokens() else 0
            val systemPart = if (cfg.useSession && sessionPart > 0) 0 else systemTokens
            promptTokens = systemPart + sessionPart + PromptBuilder.estimateTokens(user)
            if (cfg.contextTokens <= 0) break
            if (promptTokens + cfg.maxTokens <= cfg.contextTokens) break
            if (triedLimit > 8) {
                triedLimit = 8
            } else if (historyLimit > 1) {
                historyLimit = 1
            } else {
                break
            }
        }
        if (cfg.contextTokens > 0) {
            log(
                "Контекст: промпт ~$promptTokens + ответ ≤${cfg.maxTokens} = " +
                    "~${promptTokens + cfg.maxTokens} из ${cfg.contextTokens} токенов" +
                    if (triedLimit < 15 || historyLimit < 3)
                        " (сжато: проверенных $triedLimit, истории $historyLimit)"
                    else ""
            )
            if (promptTokens + cfg.maxTokens > cfg.contextTokens) {
                log(
                    "ВНИМАНИЕ: промпт больше контекста модели — ответ может обрезаться. " +
                        "Уменьши сайты/повторы/кандидатов либо подними контекст модели."
                )
            }
        }

        // Полные промпты в лог: по ним можно разобрать поведение модели без повтора запуска.
        if (reuseSession) {
            log(
                "──── LLM system prompt: уже в сессии " +
                    "(история ~${client.sessionTokens()} токенов), повторно не отправляется ────"
            )
        } else {
            log("──── LLM system prompt (${systemTokens} токенов) ────")
            log(system)
        }
        log("──── LLM user prompt ($promptTokens всего) ────")
        log(user)

        val text = try {
            withContext(Dispatchers.IO) { client.chat(system, user, cfg.maxTokens) }
        } catch (e: java.net.SocketTimeoutException) {
            throw IllegalStateException(
                "таймаут LLM: модель не ответила за ${cfg.llmTimeoutSec} с. " +
                    "Увеличь «Ответ LLM, с» или уменьши «Ответ, токенов»/«Контекст».",
                e
            )
        }
        log("──── LLM ответ (${text.length} символов) ────")
        log(text)
        var analysis = PromptBuilder.parseAnalysis(text)
        if (!analysis.hasPayload()) {
            // Небольшие модели иногда отвечают прозой или ломают JSON — один повтор.
            log("Модель ответила не по формату — повторяю запрос с жёстким требованием JSON")
            val correction = "ВАЖНО: ответь ТОЛЬКО валидным JSON по схеме выше, " +
                "без markdown, пояснений и текста до/после."
            val retry = runCatching {
                withContext(Dispatchers.IO) {
                    if (cfg.useSession) {
                        client.followUp(correction, cfg.maxTokens)
                    } else {
                        client.chat(system, user + "\n\n" + correction, cfg.maxTokens)
                    }
                }
            }.getOrNull()
            if (retry != null) {
                log("──── LLM повторный ответ (${retry.length} символов) ────")
                log(retry)
                val second = PromptBuilder.parseAnalysis(retry)
                if (second.hasPayload()) analysis = second
            }
        }
        if (analysis.summary.isNotBlank()) {
            history.add("${run.id}: ${analysis.summary.take(220)}")
            while (history.size > 8) history.removeAt(0)
        }
        return analysis
    }

    private fun LlmAnalysis.hasPayload(): Boolean =
        next.isNotEmpty() || findings.isNotEmpty() || hypotheses.isNotEmpty()

    private fun pickCandidates(
        run: LabRun,
        analysis: LlmAnalysis?,
        tried: Set<String>,
        best: String
    ): List<String> {
        val out = ArrayList<String>()
        val seen = HashSet<String>()

        fun add(raw: String) {
            val p = StrategyArgs.parse(raw, cfg.sni)
            if (!p.ok || p.normalized.isBlank()) return
            if (p.normalized in tried || p.normalized in seen) return
            if (cfg.forbidD1 && isD1(p.argv) && p.normalized !in d1Allowed) return
            seen.add(p.normalized)
            out.add(p.normalized)
        }

        // ~60% мест — гипотезы модели.
        val llmLimit = (cfg.maxCandidates * 0.6).toInt().coerceAtLeast(2)
        analysis?.next?.forEach {
            if (out.size < llmLimit) add(it.args)
        }

        val extra = if (best.isNotBlank()) listOf(best) else emptyList()
        val blocked = tried + out

        // Остальное — систематические мутации лучших стратегий.
        for (s in StrategyMutations.from(
            run = run,
            cfg = cfg,
            tried = blocked,
            limit = (cfg.maxCandidates - out.size).coerceAtLeast(1),
            extraParents = extra
        )) add(s)

        // Если всё ещё мало — сетка вокруг известного шаблона.
        if (out.size < cfg.maxCandidates) {
            for (s in StrategyMutations.systematic(
                cfg = cfg,
                tried = tried + out,
                limit = cfg.maxCandidates - out.size
            )) add(s)
        }

        return out.take(cfg.maxCandidates)
    }

    // ------------------------------------------------------------- логирование

    private fun logConfig() {
        log("════════ Конфигурация запуска ════════")
        log(
            "LLM: ${cfg.baseUrl}  model=${cfg.model.ifBlank { "auto" }}  " +
                "timeout=${cfg.llmTimeoutSec}с  context=${cfg.contextTokens}  " +
                "max_tokens=${cfg.maxTokens}"
        )
        log("Параметры API: ${cfg.llmParams.describe()}")
        log(
            "Подбор: раундов=${cfg.rounds}  повторов=${cfg.repeats}  " +
                "кандидатов=${cfg.maxCandidates}  стоп без прироста=${cfg.patience}  " +
                "параллельно=${cfg.concurrency}  таймаут=${cfg.timeoutSec}с"
        )
        log(
            "Сеть: тесты=${LabNetwork.targetLabel(cfg.testNetwork)}, " +
                "LLM=${LabNetwork.targetLabel(cfg.llmNetwork)}, " +
                "поднимать=${cfg.bringUpNetwork}"
        )
        log("Сайты (${cfg.sites.size}): ${cfg.sites.joinToString(", ")}")
        log(
            "Стратегии: база=" + (if (cfg.useBaseStrategies) "${cfg.baseStrategies.size}" else "выкл") +
                ", свои=" + (if (cfg.useOwnStrategies) "${cfg.strategies.size}" else "выкл") +
                ", прошлый лучший=да/нет (см. ниже)"
        )
        log(
            "Системный промпт: " +
                if (cfg.useSystemPrompt) {
                    val p = cfg.systemPrompt.trim()
                    if (p.isEmpty()) "по умолчанию" else "~${PromptBuilder.estimateTokens(p)} токенов"
                } else {
                    "не отправляется"
                }
        )
        log(
            "Режим LLM: " +
                if (cfg.useSession) {
                    "одна сессия — system, правила и база отправляются один раз"
                } else {
                    "каждый запрос отдельно"
                }
        )
        log(
            "База знаний: " +
                if (cfg.useKnowledge && cfg.knowledgeBase.isNotBlank()) {
                    "~${PromptBuilder.estimateTokens(cfg.knowledgeBase)} токенов"
                } else {
                    "выключена"
                }
        )
        log("══════════════════════════════════════")
    }

    private fun perSiteDetail(r: StrategyResult): String {
        if (r.perSite.isEmpty()) return ""
        return " | " + r.perSite.entries.joinToString("  ") { (site, stat) ->
            "$site=${stat.ok}/${stat.total}"
        }
    }

    private fun applyBest(strategy: String) {        runCatching {
            val prefs = ctx.getPreferences()
            prefs.edit(commit = true) {
                putString("byedpi_cmd_args", strategy)
                if (!prefs.getBoolean("byedpi_enable_cmd_settings", true)) {
                    putBoolean("byedpi_enable_cmd_settings", true)
                }
            }
            HistoryUtils(ctx).addCommand(strategy)

            val (state, mode) = appStatus
            if (state == AppStatus.Running) {
                ServiceManager.restart(ctx, mode)
                log("Стратегия применена, прокси перезапускается.")
            } else {
                log("Лучшая стратегия сохранена в настройки приложения.")
            }
        }.onFailure {
            log("Не удалось применить стратегию: ${it.message}")
        }
    }

    private fun isD1(argv: List<String>): Boolean =
        argv.indices.any { i -> argv[i] == "-d" && i + 1 < argv.size && argv[i + 1] == "1" }

    private fun pct(v: Double): String = "${(v * 100).toInt()}%"
}
