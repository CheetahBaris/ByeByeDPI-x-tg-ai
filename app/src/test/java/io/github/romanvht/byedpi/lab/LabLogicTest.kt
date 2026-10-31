package io.github.romanvht.byedpi.lab

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

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
        assertTrue(text.contains("(ещё 27 проверено раньше"))
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
