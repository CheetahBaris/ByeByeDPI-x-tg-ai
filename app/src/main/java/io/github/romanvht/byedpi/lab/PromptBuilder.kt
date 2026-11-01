package io.github.romanvht.byedpi.lab

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * Промпты и разбор ответа модели.
 *
 * Всё, что не зависит от раунда (роль, синтаксис ciadpi, диагностика, схема JSON),
 * живёт в системном сообщении. Пользовательская база знаний добавляется туда же
 * и не дублируется в запросе. Пользовательское сообщение содержит только данные
 * текущего раунда — так маленькие модели не тратят контекст на повторение правил.
 */
object PromptBuilder {

    /** База знаний по умолчанию: её можно редактировать на экране лаборатории. */
    fun defaultKnowledge(): String = DEFAULT_KB.trim()

    fun system(knowledgeBase: String = "", useKnowledge: Boolean = false): String {
        val kb = knowledgeBase.trim()
        return buildString {
            append(CORE.trim())
            if (useKnowledge && kb.isNotEmpty()) {
                append("\n\n=== БАЗА ЗНАНИЙ (редактируемая) ===\n")
                append(kb)
            }
            append("\n\n")
            append(OUTPUT.trim())
        }
    }

    fun user(
        run: LabRun,
        history: List<String>,
        bestStrategy: String,
        bestScore: Double,
        tried: Collection<String>,
        maxCandidates: Int = 12,
        minImprovement: Double = 0.02,
        triedLimit: Int = 15,
        historyLimit: Int = 3
    ): String {
        val sites = run.sites
        val sb = StringBuilder()

        sb.append("## Раунд ").append(run.id).append('\n')
        sb.append("- повторов на сайт: ").append(run.repeats).append('\n')
        sb.append("- baseline (без обхода): ")
            .append(run.baseline?.let { "${it.ok}/${it.total}" } ?: "н/д")
            .append('\n')
        sb.append("- лучшая за всё время: ")
            .append(if (bestStrategy.isBlank()) "пока нет" else "`$bestStrategy` (${pct(bestScore)})")
            .append('\n')
        if (!run.network.isNullOrBlank()) sb.append("- сеть: ").append(run.network).append('\n')

        // Компактный формат: полные имена сайтов один раз, в строках — только цифры.
        // Экономит ~2/3 объёма таблицы, что критично для локальных 4B-моделей.
        sb.append("\n## Сайты (номер = колонка в результатах)\n")
        sites.forEachIndexed { i, s ->
            sb.append(i + 1).append('=').append(short(s))
                .append(if (i == sites.lastIndex) '\n' else ' ')
        }

        sb.append("\n## Результаты раунда\n")
        sb.append("Формат строки: Σ `стратегия` | цифры по сайтам 1..N. Цифра = сколько из ")
            .append(run.repeats).append(" повторов прошло (2 2 0 = сайт 1: 2/2, сайт 2: 2/2, сайт 3: 0/2).\n")
        run.results.sortedByDescending { it.score }.forEach { r ->
            sb.append(r.ok).append('/').append(r.total).append(' ')
                .append('`').append(r.strategy.ifBlank { "<baseline>" }).append("` |")
            for (s in sites) {
                val st = r.perSite[s]
                sb.append(' ').append(if (st == null) "?" else st.ok.toString())
            }
            sb.append('\n')
        }

        val dead = sites.withIndex().filter { (_, s) ->
            run.results.all { (it.perSite[s]?.ok ?: 0) == 0 }
        }
        if (dead.isNotEmpty()) {
            sb.append("\nМёртвые сайты (0 у всех стратегий — вероятно IP/DNS-блок, ")
                .append("их провалы не про аргументы): ")
                .append(dead.joinToString(", ") { "${it.index + 1}=${short(it.value)}" })
                .append('\n')
        }

        val bestRow = run.results.filter { it.engineError == null && it.total > 0 }
            .maxByOrNull { it.score }
        if (bestRow != null && bestRow.strategy.isNotBlank()) {
            val base = run.baseline?.score ?: 0.0
            sb.append("\nЛучшая стратегия раунда: `").append(bestRow.strategy)
                .append("` — ").append(bestRow.ok).append('/').append(bestRow.total)
                .append(", ").append(pct(bestRow.score - base)).append(" к baseline\n")
        }

        if (history.isNotEmpty()) {
            sb.append("\n## История прошлых раундов\n")
            history.takeLast(historyLimit.coerceAtLeast(1)).forEach {
                sb.append("- ").append(it.take(140)).append('\n')
            }
        }

        if (tried.isNotEmpty()) {
            val all = tried.toList()
            val shown = all.takeLast(triedLimit.coerceAtLeast(1))
            sb.append("\n## Уже проверенные стратегии (НЕ повторяй)\n")
            if (all.size > shown.size) {
                sb.append("(ещё ").append(all.size - shown.size)
                    .append(" проверено раньше — их тоже не повторяй)\n")
            }
            shown.forEach { sb.append("- `").append(it).append("`\n") }
        }

        sb.append("\n## Задача\n")
        sb.append("Предложи до ").append(maxCandidates)
            .append(" стратегий: большинство — одна правка лучшей, 1–2 смелых варианта. ")
            .append("Не повторяй проверенные. Разница меньше ")
            .append(pct(minImprovement)).append(" — шум, не доказательство.\n")
        sb.append("Ответь одним JSON-объектом по схеме из системного сообщения, ")
            .append("без markdown и текста вокруг.\n")
        // Qwen3 и совместимые модели понимают эту директиву; остальные просто игнорируют.
        sb.append("/no_think")
        return sb.toString()
    }

    fun parseAnalysis(text: String): LlmAnalysis {
        val json = extractJson(text)
            ?: return LlmAnalysis(summary = text.trim().take(1500))
        val root = runCatching { JsonParser.parseString(json).asJsonObject }.getOrNull()
            ?: return LlmAnalysis(summary = text.trim().take(1500))

        val nextArray = arrayOf("next_strategies", "strategies", "candidates")
            .firstNotNullOfOrNull { key -> root.array(key) }

        return LlmAnalysis(
            summary = root.str("summary"),
            dataQuality = root.str("data_quality"),
            findings = (root.array("arg_findings") ?: emptyList()).mapNotNull { item ->
                val x = item.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
                Finding(
                    arg = x.str("arg"),
                    effect = x.str("effect"),
                    confidence = x.str("confidence"),
                    evidence = x.str("evidence")
                )
            },
            hypotheses = (root.array("hypotheses") ?: emptyList()).mapNotNull { it.asStringOrNull() },
            next = (nextArray ?: emptyList()).mapNotNull { item ->
                when {
                    item.isJsonObject -> {
                        val x = item.asJsonObject
                        val args = x.str("args").ifBlank { x.str("strategy") }
                        if (args.isBlank()) null else Candidate(args, x.str("why"))
                    }
                    item.isJsonPrimitive -> Candidate(item.asString, "")
                    else -> null
                }
            },
            experiment = root.str("next_experiment"),
            apply = root.str("apply_recommendation")
        )
    }

    /** Грубая оценка токенов: латиница ~0.28 токена/символ, кириллица ~0.55. */
    fun estimateTokens(text: String): Int {
        var cost = 0.0
        for (ch in text) cost += if (ch.code < 0x80) 0.28 else 0.55
        return cost.toInt()
    }

    private fun extractJson(text: String): String? {
        var t = text.trim()
        if (t.startsWith("```")) {
            t = t.removePrefix("```json").removePrefix("```").trim()
            if (t.endsWith("```")) t = t.dropLast(3).trim()
        }
        val start = t.indexOf('{')
        val end = t.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return t.substring(start, end + 1)
    }

    private fun short(site: String): String {
        val s = site.removePrefix("https://").removePrefix("http://").trimEnd('/')
        return if (s.length <= 24) s else s.take(21) + "..."
    }

    private fun pct(v: Double): String {
        val sign = if (v > 0) "+" else ""
        return "$sign${(v * 100).toInt()}%"
    }

    private fun JsonObject.str(key: String): String =
        get(key)?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()

    private fun JsonObject.array(key: String): List<JsonElement>? {
        val node = get(key) ?: return null
        if (!node.isJsonArray) return null
        return runCatching { node.asJsonArray.toList() }.getOrNull()
    }

    private fun JsonElement.asStringOrNull(): String? =
        if (isJsonPrimitive) asString else null

    // ---------------------------------------------------------------- тексты

    private val CORE = """
Ты — инженер по обходу DPI (ciadpi, ByeByeDPI 1.8.2 / byedpi 17.x, Android).
По таблице прогонов определи, как DPI обрабатывает TLS/HTTP, и предложи следующие
стратегии. Ты видишь только числа, не трафик и не логи. Не выдумывай факты.

=== ВХОД ===
- «повторов на сайт: R» — число проверок каждого сайта.
- «Сайты»: 1=youtube.com 2=... — номера колонок.
- Строка `ok/total стратегия | 2 1 0`: цифра = успехов из R на сайте с этим номером,
  `?` = нет данных.
- `<baseline>` — без обхода; сравнивай с ним, не с нулём.
- «Лучшая…» — родитель новых кандидатов; «мёртвые» сайты (0 у всех) — IP/DNS-блок,
  их не лечат аргументами; «проверенные» — не повторяй.

=== СИНТАКСИС ===
pos_t = offset[:repeats:skip][+s|+h|+n][e|m|r]
- offset — байты от начала запроса, отрицательный — от конца.
- :repeats:skip — серия разрезов: 1:3:5 = позиции 1, 6, 11.
- +s — от SNI (TLS), +h — от Host (HTTP), +n — без привязки; затем +e (конец имени),
  +m (середина), +r (случайно в имени).
- Примеры: -s1+s, -d3+s, -o2, -r-5+se, -f-1 -t4, -s1:3:5+sm.
- Ключи можно повторять; порядок — как в командной строке; tlsrec (-r) всегда до split.

Операции:
- -s — разрезать и отправить по порядку.
- -d — disorder: первая часть с TTL=1, сервер перезапрашивает, порядок ломается.
  На мобильной сети -d 1 часто даёт 0 — не предлагай.
- -o — OOB (URG), часть вне потока; -q — disorder + OOB.
- -f — fake перед оригиналом; -t TTL фейка (по умолчанию 8, пробуй 3..6);
  -O сдвиг фейка, -l ':строка' свои байты, -n 'www.?????.com' случайный SNI,
  -Q r|o|msize=N модификация ClientHello.
- -r — tlsrec: разрезать TLS-запись с новым 5-байтовым заголовком; обычно -r-5+se.
- -M h,d,r — HTTP (регистр Host/домена, пробелы→табы); -m N — minor-версия TLS.

Сеть/служба:
- -X no-ipv6, -I IP (0.0.0.0 = только IPv4), -N no-domain, -U no-udp, -g N def-ttl,
  -a N UDP-fake, -Y drop-sack, -F tfo.
- -S md5sig на Android без root почти всегда EPERM — не предлагай.
- -T сек timeout auto; -A t,r,s,c,n — новая группа (timeout/RST, redirect, TLS error,
  connect error, всегда); -L s,o,n — auto-mode; группы слева направо, после сбоя
  берётся следующая подходящая.
- Можно: -X -I -N -U -g -s -d -o -q -f -t -O -l -n -Q -e -M -r -m -a -Y -T -A -L -R -K -H -j -V.
- Нельзя: -i -p -D -w -E -B -C -h -v. Без порта, IP и параметров запуска. -d 1 запрещён.

=== ДИАГНОСТИКА ===
1. Все нули, включая baseline → стенд/сеть/порт, не стратегия. data_quality=недостаточно.
2. baseline > 0, desync помогает на большинстве TLS → блок ClientHello/SNI.
3. timeout без RST, HTTP мёртв → тихий дроп IP/SNI; проверь -X, IPv4, fake SNI;
   мёртвый у всех сайт не лечи.
4. RST/TLS-ошибка на всех TLS, HTTP жив → ClientHello; пробуй -s/-d/-o/-r, -n, -Q.
5. Помогает только -f TTL 3..6 → фильтр по TTL, хрупко; проверяй TTL и повторы.
6. Помогает только -r → DPI разбирает TLS-запись целиком; двигай позицию, добавь второй -r.
7. Помогает только -o/-q → OOB; следи за мидлбоксами, комбинируй с -d.
8. Один сайт 0 у всех → CDN/QUIC/IPv6, не провал аргументов.
9. Разница <10–15% или 1 из 2 повторов → шум.
10. engineError/невалидно → ошибка запуска, не измерение.

=== ПОИСК ===
- Старт — лучшая за всё время; если нет: -X -I 0.0.0.0 -s1+s -d3+s -o2 -r-5+se -a1.
- 60–70% кандидатов — одна правка лучшей: ±1..3 offset, -o1/-o2/-o3, -r-5/-6+se,
  -s2+s, TTL 3..6, порядок split/disorder, убрать один аргумент (ablation).
- 20–30% — другая механика: -q вместо -d, -s1:3:5, -n, -M, -m, fake+split.
- 0–1 смелая гипотеза; ≤8–10 аргументов в строке.
- Лучшая ≥90% → проверяй устойчивость (соседние TTL/offset, повтор, ablation).
- Все на уровне baseline → смени механизм, не крути offset.
- На мобильной сети добавляй -X -I 0.0.0.0, если IPv6 не доказан.
- Не повторяй проверенные, не дублируй, не предлагай невалидное.
""".trimIndent()

    private val OUTPUT = """
=== ФОРМАТ ОТВЕТА ===
Верни строго один JSON-объект:
{
  "summary": "1–2 предложения: что показал раунд",
  "data_quality": "достаточно|мало|недостаточно: почему",
  "arg_findings": [
    {"arg": "-r-5+se", "effect": "positive|negative|neutral|unknown",
     "confidence": "low|medium|high",
     "evidence": "сайты 1,3: 2 2 против 0 0 у baseline"}
  ],
  "hypotheses": ["что предполагаем о работе DPI"],
  "next_strategies": [
    {"args": "-X -I 0.0.0.0 -s1+s -d3+s -o2 -r-5+se -a1",
     "why": "какой один параметр проверяем"}
  ],
  "next_experiment": "один вопрос, который закроет раунд",
  "apply_recommendation": "что применять и когда; если рано — так и скажи"
}

Правила ответа:
- Все текстовые поля — по-русски, кратко.
- evidence — только номера сайтов и цифры из запроса; не выдумывай.
- next_strategies — 3–5 (или сколько просит запрос), только валидные аргументы,
  без -i/-p; каждая строка уникальна.
- Не рекомендуй применять стратегию, которая не проверена в этом запуске.
- Никакого markdown, ```json, комментариев и лишних полей.
- Если данных мало: data_quality="недостаточно", но next_strategies всё равно дай.
""".trimIndent()

    /**
     * Компактная база знаний по умолчанию.
     *
     * Здесь только практические наблюдения, которые нельзя вывести из таблицы.
     * Всё остальное (синтаксис, диагностика, схема) уже есть в системном промпте,
     * поэтому база не дублирует правила и легко редактируется пользователем.
     */
    private val DEFAULT_KB = """
Практика (Android, ciadpi 17.x):
- На мобильной сети часто есть IPv6-адрес, но нет IPv6-маршрута: без -X -I 0.0.0.0
  часть доменов падает с ENETUNREACH. Если baseline низкий, начинай с этих аргументов.
- Рабочая формула на LTE (МегаФон, Xiaomi 13): -X -I 0.0.0.0 -s1+s -d3+s -o2 -r-5+se -a1
  (12/12 YouTube + Instagram). Устойчивые окрестности: -o1/-o3, -r-6+se, -s2+s,
  добавление -f-1 -t3.
- -d 1 на мобильном канале часто даёт 0/таймауты; вместо него -s1+s -d3+s.
- tlsrec -r-5+se — ключевой аргумент; -r1+s и -r4+se заметно хуже.
- -f/-q не всегда добавляют прирост к split+oob+tlsrec; проверяй отдельно.
- -S (md5sig) на Android без root почти всегда EPERM; -E требует root. Не предлагай.
- -Y и -F зависят от ядра: проверяй отдельным прогоном, не смешивай с рабочей формулой.
- telegram.org и web.telegram.org часто DNS-блок; discord.com, x.com, rutracker.org,
  speedtest.net — IP-блок. Их нули не лечатся аргументами.
- Мобильная сеть ≠ Wi-Fi: DPI и MTU разные, результаты не переноси между сетями.
- Адаптивный DPI: после серии неудач поведение меняется; baseline и повторы обязательны.
""".trimIndent()
}
