package io.github.romanvht.byedpi.lab

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import com.google.gson.JsonObject
import java.io.File

class LabLogicTest {

    private val winner = "-X -I 0.0.0.0 -s1+s -d3+s -o2 -r-5+se -a1"

    private val cfg = LabConfig(
        baseUrl = "",
        model = "",
        apiKey = "",
        rounds = 1,
        repeats = 1,
        sites = listOf("youtube.com", "instagram.com"),
        strategies = listOf(winner)
    )

    @Test
    fun `winner strategy is valid and normalized`() {
        val parsed = StrategyArgs.parse(winner)
        assertTrue(parsed.error, parsed.ok)
        assertEquals(
            "-X -I 0.0.0.0 -s 1+s -d 3+s -o 2 -r -5+se -a 1",
            parsed.normalized
        )
    }

    @Test
    fun `d1 is parsed but detectable`() {
        val parsed = StrategyArgs.parse("-d1 -s1+s")
        assertTrue(parsed.ok)
        assertTrue(
            parsed.argv.indices.any { i ->
                parsed.argv[i] == "-d" && i + 1 < parsed.argv.size && parsed.argv[i + 1] == "1"
            }
        )
    }

    @Test
    fun `forbidden and unknown options are rejected`() {
        assertFalse(StrategyArgs.parse("-p 1080 -s1+s").ok)
        assertFalse(StrategyArgs.parse("-s1+s --unknown-flag").ok)
        assertFalse(StrategyArgs.parse("-s1+s -o").ok)
    }

    @Test
    fun `systematic grid is valid, unique and has no d1`() {
        val list = StrategyMutations.systematic(cfg, emptySet(), 25)
        assertEquals(25, list.size)
        assertEquals(list.size, list.distinct().size)
        for (s in list) {
            val p = StrategyArgs.parse(s)
            assertTrue("bad: $s — ${p.error}", p.ok)
            assertFalse("contains -d 1: $s", p.argv.windowed(2).any { it == listOf("-d", "1") })
        }
    }

    @Test
    fun `mutations around winner are valid and new`() {
        val run = LabRun(
            id = "test-r1",
            created = "2026-01-01 00:00:00",
            sites = cfg.sites,
            repeats = 1,
            baseline = StrategyResult(
                strategy = "",
                perSite = linkedMapOf(
                    "youtube.com" to SiteStat(0, 1),
                    "instagram.com" to SiteStat(0, 1)
                )
            ),
            results = listOf(
                StrategyResult(
                    strategy = winner,
                    perSite = linkedMapOf(
                        "youtube.com" to SiteStat(1, 1),
                        "instagram.com" to SiteStat(1, 1)
                    )
                )
            )
        )

        val list = StrategyMutations.from(run, cfg, setOf(winner), 20)
        assertTrue(list.isNotEmpty())
        assertFalse(list.contains(winner))
        for (s in list) {
            val p = StrategyArgs.parse(s)
            assertTrue("bad: $s — ${p.error}", p.ok)
            assertFalse("contains -d 1: $s", p.argv.windowed(2).any { it == listOf("-d", "1") })
        }
    }

    @Test
    fun `all bundled strategies are valid`() {
        val candidates = listOf(
            File("src/main/assets/proxytest_strategies.list"),
            File("../app/src/main/assets/proxytest_strategies.list")
        )
        val file = candidates.firstOrNull { it.exists() }
        assertTrue("proxytest_strategies.list not found", file != null)
        val lines = file!!.readLines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
        assertEquals(60, lines.size)
        val bad = lines.mapNotNull { line ->
            val parsed = StrategyArgs.parse(line)
            if (parsed.ok) null else "$line -> ${parsed.error}"
        }
        assertTrue("invalid strategies:\n" + bad.joinToString("\n"), bad.isEmpty())
    }

    @Test
    fun `system prompt is self contained and keeps json schema`() {
        val system = PromptBuilder.system()
        assertTrue(system.contains("pos_t"))
        assertTrue(system.contains("next_strategies"))
        assertTrue(system.contains("arg_findings"))
        assertTrue(system.contains("Forbidden"))
        assertFalse(system.contains("Практика (Android"))
    }

    @Test
    fun `knowledge base is optional and goes to system only`() {
        val kb = "Моя сеть: провайдер X, IPv6 сломан"
        val withKb = PromptBuilder.system(kb, useKnowledge = true)
        assertTrue(withKb.contains(kb))
        assertFalse(PromptBuilder.system(kb, useKnowledge = false).contains(kb))

        val run = LabRun(
            id = "test-kb",
            created = "2026-01-01 00:00:00",
            sites = listOf("a.com"),
            repeats = 1,
            baseline = StrategyResult(strategy = "", perSite = linkedMapOf("a.com" to SiteStat(0, 1))),
            results = emptyList()
        )
        val user = PromptBuilder.user(
            run = run,
            history = emptyList(),
            bestStrategy = "",
            bestScore = 0.0,
            tried = emptyList()
        )
        assertFalse(user.contains(kb))
        assertFalse(user.contains("arg_findings"))
        assertTrue(user.endsWith("/no_think"))
    }

    @Test
    fun `system without knowledge keeps rules and schema`() {
        val kb = "Моя сеть: провайдер X, IPv6 сломан"
        val full = PromptBuilder.system(kb, useKnowledge = true)
        val trimmed = PromptBuilder.withoutKnowledge(full)
        assertFalse(trimmed.contains(kb))
        assertFalse(trimmed.contains("=== KNOWLEDGE BASE"))
        assertTrue(trimmed.contains("pos_t"))
        assertTrue(trimmed.contains("next_strategies"))
        assertTrue(trimmed.length < full.length)
        // Если базы нет — текст не меняется.
        val noKb = PromptBuilder.system()
        assertEquals(noKb, PromptBuilder.withoutKnowledge(noKb))
    }

    @Test
    fun `session compaction fits budget and keeps fresh round`() {
        val system = PromptBuilder.system("база", useKnowledge = true)
        val history = mutableListOf(msg("system", system))
        for (round in 1..4) {
            history.add(msg("user", "## Round $round\n" + "data ".repeat(400)))
            history.add(
                msg(
                    "assistant",
                    """{"summary":"итог раунда $round","next_strategies":[]}"""
                )
            )
        }
        val sysTokens = SessionCompactor.messageTokens(history.first())
        val budget = sysTokens + 300

        val after = SessionCompactor.compact(history, budget)

        assertEquals(after, SessionCompactor.estimate(history))
        assertTrue("after=$after budget=$budget", after <= budget)
        assertEquals("system", SessionCompactor.role(history.first()))
        assertTrue(history.any { SessionCompactor.isSummary(it) })
        assertTrue(history.any { SessionCompactor.content(it).contains("итог раунда 4") })
        assertFalse(history.any { SessionCompactor.content(it).contains("Round 1") })
    }

    @Test
    fun `session compaction strips knowledge base when no room`() {
        val kb = "K".repeat(3000)
        val system = PromptBuilder.system(kb, useKnowledge = true)
        val history = mutableListOf(
            msg("system", system),
            msg("user", "## Round 1\n" + "data ".repeat(400)),
            msg("assistant", """{"summary":"итог"}""")
        )

        SessionCompactor.compact(history, 200)

        val systemText = SessionCompactor.content(history.first())
        assertFalse(systemText.contains(kb))
        assertFalse(systemText.contains("=== KNOWLEDGE BASE"))
        assertEquals(PromptBuilder.responseFormat(), systemText)
    }

    @Test
    fun `session compaction is a no-op when everything fits`() {
        val system = PromptBuilder.system()
        val history = mutableListOf(
            msg("system", system),
            msg("user", "## Round 1\nshort"),
            msg("assistant", """{"summary":"итог"}""")
        )
        val before = history.map { SessionCompactor.content(it) }
        val after = SessionCompactor.compact(history, 100_000)
        assertEquals(SessionCompactor.estimate(history), after)
        assertEquals(before, history.map { SessionCompactor.content(it) })
    }

    private fun msg(role: String, content: String): JsonObject = JsonObject().apply {
        addProperty("role", role)
        addProperty("content", content)
    }

    @Test
    fun `token estimate is sane`() {
        assertTrue(PromptBuilder.estimateTokens("hello") in 1..10)
        assertTrue(PromptBuilder.estimateTokens("привет") in 1..10)
    }

    @Test
    fun `base url is normalized`() {
        assertEquals(
            "http://127.0.0.1:1234/v1",
            LlmClient.normalizeBaseUrl("127.0.0.1:1234")
        )
        assertEquals(
            "http://192.168.1.5:1234/v1",
            LlmClient.normalizeBaseUrl("http://192.168.1.5:1234/")
        )
        assertEquals(
            "http://192.168.1.5:1234/v1",
            LlmClient.normalizeBaseUrl("http://192.168.1.5:1234/v1")
        )
        assertEquals(
            "https://api.openai.com/v1",
            LlmClient.normalizeBaseUrl("https://api.openai.com/v1/")
        )
    }

    @Test
    fun `llm json is parsed`() {
        val text = """
            ```json
            {
              "summary": "ok",
              "data_quality": "medium",
              "arg_findings": [
                {"arg": "-r", "effect": "positive", "confidence": "high", "evidence": "12/12"}
              ],
              "hypotheses": ["tlsrec key"],
              "next_strategies": [
                {"args": "-X -s1+s -d3+s", "why": "variant"},
                "-X -s2+s -d3+s"
              ],
              "next_experiment": "check r",
              "apply_recommendation": "apply winner"
            }
            ```
        """.trimIndent()

        val a = PromptBuilder.parseAnalysis(text)
        assertEquals("ok", a.summary)
        assertEquals(1, a.findings.size)
        assertEquals(2, a.next.size)
        assertEquals("-X -s1+s -d3+s", a.next[0].args)
        assertEquals("-X -s2+s -d3+s", a.next[1].args)
    }

    @Test
    fun `prose answer has no payload and triggers retry`() {
        val a = PromptBuilder.parseAnalysis("Похоже, лучший вариант — tlsrec. Увеличь повторы.")
        assertTrue(a.summary.isNotBlank())
        assertTrue(a.next.isEmpty())
        assertTrue(a.findings.isEmpty())
        assertTrue(a.hypotheses.isEmpty())
    }

    @Test
    fun `prompt keeps only the last tried strategies`() {
        val run = LabRun(
            id = "test-r1",
            created = "2026-01-01 00:00:00",
            sites = cfg.sites,
            repeats = 1,
            baseline = StrategyResult(
                strategy = "",
                perSite = linkedMapOf("youtube.com" to SiteStat(0, 1))
            ),
            results = listOf(
                StrategyResult(
                    strategy = winner,
                    perSite = linkedMapOf("youtube.com" to SiteStat(1, 1))
                )
            )
        )
        val tried = (1..30).map { "-d $it+s" }

        val text = PromptBuilder.user(
            run = run,
            history = emptyList(),
            bestStrategy = winner,
            bestScore = 1.0,
            tried = tried,
            maxCandidates = 4,
            triedLimit = 3
        )

        assertTrue(text.contains("-d 28+s"))
        assertTrue(text.contains("-d 30+s"))
        assertFalse(text.contains("`-d 1+s`"))
        assertFalse(text.contains("`-d 20+s`"))
        assertTrue(text.contains("(27 more tested earlier"))
    }

    @Test
    fun `results use compact digit rows`() {
        val run = LabRun(
            id = "test-r2",
            created = "2026-01-01 00:00:00",
            sites = listOf("a.com", "b.com"),
            repeats = 2,
            baseline = StrategyResult(
                strategy = "",
                perSite = linkedMapOf(
                    "a.com" to SiteStat(0, 2),
                    "b.com" to SiteStat(0, 2)
                )
            ),
            results = listOf(
                StrategyResult(
                    strategy = winner,
                    perSite = linkedMapOf(
                        "a.com" to SiteStat(2, 2),
                        "b.com" to SiteStat(1, 2)
                    )
                )
            )
        )

        val text = PromptBuilder.user(
            run = run,
            history = emptyList(),
            bestStrategy = winner,
            bestScore = 0.75,
            tried = emptyList(),
            maxCandidates = 4
        )

        assertTrue(text.contains("1=a.com 2=b.com"))
        assertTrue(text.contains("3/4 `$winner` | 2 1"))
        assertFalse(text.contains("| стратегия |"))
    }

    @Test
    fun `long history entries are truncated in prompt`() {
        val run = LabRun(
            id = "test-r3",
            created = "2026-01-01 00:00:00",
            sites = listOf("a.com"),
            repeats = 1,
            baseline = StrategyResult(
                strategy = "",
                perSite = linkedMapOf("a.com" to SiteStat(0, 1))
            ),
            results = listOf(
                StrategyResult(
                    strategy = winner,
                    perSite = linkedMapOf("a.com" to SiteStat(1, 1))
                )
            )
        )
        val long = "r1: " + "я".repeat(400)

        val text = PromptBuilder.user(
            run = run,
            history = listOf(long),
            bestStrategy = winner,
            bestScore = 1.0,
            tried = emptyList(),
            maxCandidates = 3
        )

        assertTrue(text.contains("r1: яя"))
        assertFalse(text.contains("я".repeat(200)))
    }
}
