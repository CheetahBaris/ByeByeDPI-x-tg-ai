package io.github.romanvht.byedpi.lab

import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.edit
import androidx.core.widget.doAfterTextChanged
import androidx.preference.PreferenceManager
import com.google.android.material.appbar.MaterialToolbar
import io.github.romanvht.byedpi.R
import io.github.romanvht.byedpi.activities.BaseActivity
import io.github.romanvht.byedpi.data.AppStatus
import io.github.romanvht.byedpi.services.ServiceManager
import io.github.romanvht.byedpi.services.appStatus
import io.github.romanvht.byedpi.utility.HistoryUtils
import io.github.romanvht.byedpi.utility.PermissionUtils
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * GUI лаборатории AI-подбора.
 *
 * Экран построен на layout-ресурсе [R.layout.activity_lab] и компонентах Material,
 * поэтому выглядит так же, как остальные экраны приложения. Логика подбора
 * осталась в [LabRunner], здесь только настройки, прогресс и отчёты.
 */
class LabActivity : BaseActivity() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var job: Job? = null

    private val prefs by lazy { getSharedPreferences("lab", MODE_PRIVATE) }

    // Подключение
    private lateinit var baseUrl: EditText
    private lateinit var apiKey: EditText
    private lateinit var modelSpinner: Spinner
    private lateinit var connStatus: TextView

    // Параметры
    private lateinit var rounds: EditText
    private lateinit var repeats: EditText
    private lateinit var timeout: EditText
    private lateinit var candidates: EditText
    private lateinit var patience: EditText
    private lateinit var concurrency: EditText
    private lateinit var maxTokens: EditText
    private lateinit var contextTokens: EditText
    private lateinit var llmTimeout: EditText
    private lateinit var autoApply: CheckBox
    private lateinit var reuseBaseline: CheckBox
    private lateinit var useKnowledge: CheckBox
    private lateinit var knowledgeBase: EditText
    private lateinit var promptBudget: TextView
    private lateinit var sitesExpandBtn: Button
    private lateinit var strategiesExpandBtn: Button
    private lateinit var kbExpandBtn: Button
    private lateinit var logExpandBtn: Button
    private lateinit var kbResetBtn: Button

    // Сеть
    private lateinit var scanModeSpinner: Spinner
    private lateinit var llmNetSpinner: Spinner
    private lateinit var testNetSpinner: Spinner
    private lateinit var bringUpBox: CheckBox
    private lateinit var netSummary: TextView

    // Сайты и стратегии
    private lateinit var presetSpinner: Spinner
    private lateinit var sites: EditText
    private lateinit var strategies: EditText

    // Прогресс/лог
    private lateinit var status: TextView
    private lateinit var bestView: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var logView: TextView
    private lateinit var scroll: ScrollView
    private lateinit var settingsBox: LinearLayout

    // Кнопки
    private lateinit var startBtn: Button
    private lateinit var stopBtn: Button
    private lateinit var scanBtn: Button
    private lateinit var checkBtn: Button
    private lateinit var toggleSettingsBtn: Button

    private val modelIds = ArrayList<String>()
    private val modelAdapter by lazy {
        ArrayAdapter<String>(this, R.layout.lab_spinner_item).apply {
            setDropDownViewResource(R.layout.lab_spinner_dropdown_item)
            add("auto — модель по умолчанию")
        }
    }

    private var presets: List<Pair<String, String>> = emptyList()

    // Состояние для уведомления и бюджет промпта
    private var startedAt = 0L
    private var lastCfg: LabConfig? = null
    private var lastBestStrategy = ""
    private var lastBestScore = 0.0
    private var budgetColor = 0
    private val basePromptTokens by lazy {
        PromptBuilder.estimateTokens(PromptBuilder.system())
    }

    companion object {
        private const val REQUEST_NOTIFICATIONS = 1201
    }

    private val scanOptions = listOf(
        ScanMode.LOCAL to "Локально (адрес из поля)",
        ScanMode.BOTH to "Wi-Fi + USB-кабель",
        ScanMode.WIFI to "Только Wi-Fi",
        ScanMode.USB to "Только USB-кабель"
    )
    private val llmOptions = listOf(
        NetTarget.AUTO to "Авто (системная сеть)",
        NetTarget.USB to "USB / локально (127.0.0.1)",
        NetTarget.WIFI to "Wi-Fi (локальная сеть)",
        NetTarget.CELLULAR to "Мобильная сеть",
        NetTarget.ETHERNET to "Ethernet / USB-модем",
        NetTarget.SYSTEM to "Системная"
    )
    private val testOptions = listOf(
        NetTarget.SYSTEM to "Как в системе",
        NetTarget.CELLULAR to "Мобильная сеть",
        NetTarget.WIFI to "Wi-Fi",
        NetTarget.ETHERNET to "Ethernet / USB-модем"
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        setContentView(R.layout.activity_lab)
        bindViews()
        setupToolbar()
        supportActionBar?.title = getString(R.string.ai_lab)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        findViewById<MaterialToolbar>(R.id.toolbar)
            .setNavigationOnClickListener { finish() }

        presets = loadPresetList()
        presetSpinner.adapter = ArrayAdapter(
            this,
            R.layout.lab_spinner_item,
            presets.map { it.first }
        ).apply { setDropDownViewResource(R.layout.lab_spinner_dropdown_item) }
        modelSpinner.adapter = modelAdapter
        loadPrefs()
        refreshBest()
        wireActions()
        updatePromptBudget()
        LabNotifications.ensureChannel(applicationContext)

        if (intent?.action == LabNotifications.ACTION_STOP && job?.isActive == true) {
            stop()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.action == LabNotifications.ACTION_STOP && job?.isActive == true) {
            stop()
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    // ------------------------------------------------------------------- UI

    private fun bindViews() {
        status = findViewById(R.id.labStatus)
        bestView = findViewById(R.id.labBest)
        progressBar = findViewById(R.id.labProgress)
        logView = findViewById(R.id.labLog)
        scroll = findViewById(R.id.labScroll)
        settingsBox = findViewById(R.id.labSettingsBox)

        baseUrl = findViewById(R.id.labBaseUrl)
        apiKey = findViewById(R.id.labApiKey)
        modelSpinner = findViewById(R.id.labModel)
        connStatus = findViewById(R.id.labConnStatus)
        scanModeSpinner = findViewById(R.id.labScanMode)
        scanBtn = findViewById(R.id.labScanBtn)
        checkBtn = findViewById(R.id.labCheckBtn)

        llmNetSpinner = findViewById(R.id.labLlmNet)
        testNetSpinner = findViewById(R.id.labTestNet)
        bringUpBox = findViewById(R.id.labBringUp)
        netSummary = findViewById(R.id.labNetSummary)

        rounds = findViewById(R.id.labRounds)
        repeats = findViewById(R.id.labRepeats)
        timeout = findViewById(R.id.labTimeout)
        candidates = findViewById(R.id.labCandidates)
        patience = findViewById(R.id.labPatience)
        concurrency = findViewById(R.id.labConcurrency)
        maxTokens = findViewById(R.id.labMaxTokens)
        contextTokens = findViewById(R.id.labContextTokens)
        llmTimeout = findViewById(R.id.labLlmTimeout)
        autoApply = findViewById(R.id.labAutoApply)
        reuseBaseline = findViewById(R.id.labReuseBaseline)

        useKnowledge = findViewById(R.id.labUseKb)
        knowledgeBase = findViewById(R.id.labKb)
        promptBudget = findViewById(R.id.labPromptBudget)
        kbExpandBtn = findViewById(R.id.labKbExpandBtn)
        kbResetBtn = findViewById(R.id.labKbResetBtn)

        presetSpinner = findViewById(R.id.labPreset)
        sites = findViewById(R.id.labSites)
        strategies = findViewById(R.id.labStrategies)
        sitesExpandBtn = findViewById(R.id.labSitesExpandBtn)
        strategiesExpandBtn = findViewById(R.id.labStrategiesExpandBtn)
        logExpandBtn = findViewById(R.id.labLogExpandBtn)

        startBtn = findViewById(R.id.labStartBtn)
        stopBtn = findViewById(R.id.labStopBtn)
        toggleSettingsBtn = findViewById(R.id.labToggleSettingsBtn)

        setupSpinner(scanModeSpinner, scanOptions)
        setupSpinner(llmNetSpinner, llmOptions)
        setupSpinner(testNetSpinner, testOptions)
    }

    private fun wireActions() {
        startBtn.setOnClickListener { start() }
        stopBtn.setOnClickListener { stop() }
        toggleSettingsBtn.setOnClickListener { toggleSettings() }
        scanBtn.setOnClickListener { scanWifi() }
        checkBtn.setOnClickListener { checkLlm() }
        findViewById<Button>(R.id.labShowNetBtn).setOnClickListener { showNetworks() }
        findViewById<Button>(R.id.labLoadPresetBtn).setOnClickListener { loadPreset() }
        findViewById<Button>(R.id.labApplyBtn).setOnClickListener { applyLastBest() }
        findViewById<Button>(R.id.labTopBtn).setOnClickListener { showTop() }
        findViewById<Button>(R.id.labReportBtn).setOnClickListener { showReport() }
        findViewById<Button>(R.id.labClearBtn).setOnClickListener { logView.text = "" }

        sitesExpandBtn.setOnClickListener {
            showFullscreenEditor(getString(R.string.lab_sites_title), sites)
        }
        strategiesExpandBtn.setOnClickListener {
            showFullscreenEditor(getString(R.string.lab_strategies_title), strategies)
        }
        kbExpandBtn.setOnClickListener {
            showFullscreenEditor(getString(R.string.lab_kb_title), knowledgeBase)
        }
        kbResetBtn.setOnClickListener {
            knowledgeBase.setText(PromptBuilder.defaultKnowledge())
            updatePromptBudget()
        }
        logExpandBtn.setOnClickListener {
            showFullscreenText(getString(R.string.lab_log_title), logView.text.toString())
        }
        useKnowledge.setOnCheckedChangeListener { _, _ -> updatePromptBudget() }
        knowledgeBase.doAfterTextChanged { updatePromptBudget() }
        maxTokens.doAfterTextChanged { updatePromptBudget() }
        contextTokens.doAfterTextChanged { updatePromptBudget() }

        testNetSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>?,
                view: View?,
                position: Int,
                id: Long
            ) = updateNetSummary()

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        updateNetSummary()
    }

    private fun updateNetSummary() {
        if (!::netSummary.isInitialized) return
        val llmTarget = selected(llmNetSpinner, llmOptions, NetTarget.AUTO)
        val testTarget = selected(testNetSpinner, testOptions, NetTarget.SYSTEM)
        val llm = LabNetwork.find(this, llmTarget)
        val test = LabNetwork.find(this, testTarget)
        val localLlm = LabNetwork.isLoopbackUrl(baseUrl.text.toString())
        netSummary.text = buildString {
            append("Модель: ").append(LabNetwork.targetLabel(llmTarget))
            append(" → ").append(
                when {
                    localLlm -> "локально на телефоне (127.0.0.1)"
                    llm != null -> LabNetwork.label(this@LabActivity, llm)
                    else -> "нет сети"
                }
            )
            append('\n')
            append("Тесты: ").append(LabNetwork.targetLabel(testTarget))
            append(" → ").append(if (test != null) LabNetwork.label(this@LabActivity, test) else "нет сети")
        }
    }

    private fun showNetworks() {
        val lines = LabNetwork.describe(this)
        updateNetSummary()
        appendLog("Сети:")
        lines.forEach { appendLog("  $it") }
        AlertDialog.Builder(this)
            .setTitle("Доступные сети")
            .setMessage(
                lines.joinToString("\n\n") + "\n\n" +
                    "Выбрано:\n" + netSummary.text
            )
            .setPositiveButton("OK", null)
            .show()
    }

    // ------------------------------------------------------------- управление

    private fun start() {
        if (job?.isActive == true) return

        val siteList = sites.text.toString().lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
        if (siteList.isEmpty()) {
            toast("Добавь хотя бы один сайт")
            return
        }

        val url = LlmClient.normalizeBaseUrl(baseUrl.text.toString())
        baseUrl.setText(url)

        if (modelIds.size <= 1) {
            modelAdapter.clear()
            modelAdapter.add("auto — модель по умолчанию")
            modelAdapter.notifyDataSetChanged()
        }

        val strategyList = strategies.text.toString().lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .ifEmpty {
                readAsset("lab_strategies.txt").lines()
                    .map { it.trim() }
                    .filter { it.isNotEmpty() && !it.startsWith("#") }
            }

        val cfg = LabConfig(
            baseUrl = url,
            model = currentModel().ifBlank { modelIds.getOrNull(1).orEmpty() },
            apiKey = apiKey.text.toString().trim(),
            rounds = number(rounds, 3, 1, 50),
            repeats = number(repeats, 2, 1, 6),
            sites = siteList,
            strategies = strategyList,
            maxMinutes = 240,
            target = 1.0,
            patience = number(patience, 2, 0, 20),
            minImprovement = 0.02,
            forbidD1 = true,
            sni = "google.com",
            port = 1081,
            timeoutSec = number(timeout, 6, 2, 30).toLong(),
            maxCandidates = number(candidates, 10, 2, 30),
            maxTokens = number(maxTokens, 1200, 200, 8000),
            contextTokens = number(contextTokens, 8192, 0, 131072),
            llmTimeoutSec = number(llmTimeout, 300, 10, 1800),
            knowledgeBase = if (useKnowledge.isChecked) knowledgeBase.text.toString() else "",
            useKnowledge = useKnowledge.isChecked,
            reuseBaseline = reuseBaseline.isChecked,
            autoApply = autoApply.isChecked,
            concurrency = number(concurrency, 8, 1, 32),
            llmNetwork = selected(llmNetSpinner, llmOptions, NetTarget.AUTO),
            testNetwork = selected(testNetSpinner, testOptions, NetTarget.SYSTEM),
            scanMode = selected(scanModeSpinner, scanOptions, ScanMode.BOTH),
            bringUpNetwork = bringUpBox.isChecked
        )

        savePrefs()
        logView.text = ""
        lastCfg = cfg
        startedAt = System.currentTimeMillis()
        lastBestStrategy = ""
        lastBestScore = 0.0
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            !PermissionUtils.hasNotificationPermission(this)
        ) {
            PermissionUtils.requestNotificationPermission(this, REQUEST_NOTIFICATIONS)
        }
        setRunning(true)
        notifyLab("Запуск подбора…", null, true)
        job = scope.launch {
            try {
                val result = LabRunner(
                    ctx = applicationContext,
                    cfg = cfg,
                    store = LabStore(applicationContext),
                    log = { appendLog(it) },
                    progress = { onProgress(it) }
                ).run()
                onFinished(result)
            } catch (e: CancellationException) {
                appendLog("— остановлено пользователем —")
                status.text = "Остановлено"
                notifyLab("Остановлено пользователем", null, false, ongoing = false)
            } catch (e: Exception) {
                appendLog("ОШИБКА: ${e.message}")
                status.text = "Ошибка: ${e.message?.take(70)}"
                notifyLab("Ошибка: ${e.message?.take(80)}", null, false, ongoing = false)
            } finally {
                setRunning(false)
                refreshBest()
            }
        }
    }

    private fun stop() {
        job?.cancel()
        job = null
        status.text = "Останавливаю…"
        appendLog("— запрошена остановка —")
        notifyLab("Останавливаю…", null, true)
    }

    private fun onFinished(result: LabResult) {
        progressBar.progress = 100
        lastBestStrategy = result.bestStrategy
        lastBestScore = result.bestScore
        status.text = "Готово: ${pct(result.bestScore)} • ${result.rounds} раунд(ов)"
        notifyLab("Готово: ${pct(result.bestScore)}", 100, false, ongoing = false)
        if (result.bestStrategy.isNotBlank()) {
            bestView.text = "Лучшая: ${pct(result.bestScore)} • ${result.bestStrategy}"
            AlertDialog.Builder(this)
                .setTitle("Подбор завершён")
                .setMessage(
                    "Лучшая стратегия (${pct(result.bestScore)}):\n\n" +
                        result.bestStrategy +
                        "\n\nПричина остановки: " + result.stopReason
                )
                .setPositiveButton("Применить") { _, _ -> confirmApply(result.bestStrategy) }
                .setNeutralButton("Отчёт") { _, _ -> showReport() }
                .setNegativeButton("Закрыть", null)
                .show()
        }
    }

    private fun setRunning(running: Boolean) {
        startBtn.isEnabled = !running
        stopBtn.isEnabled = running
        scanBtn.isEnabled = !running
        checkBtn.isEnabled = !running
        if (running) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            progressBar.progress = 0
            if (settingsBox.visibility == View.VISIBLE) toggleSettings()
            status.text = "Запуск…"
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    private fun toggleSettings() {
        if (settingsBox.visibility == View.VISIBLE) {
            settingsBox.visibility = View.GONE
            toggleSettingsBtn.text = "Показать"
        } else {
            settingsBox.visibility = View.VISIBLE
            toggleSettingsBtn.text = "Скрыть"
        }
    }

    // --------------------------------------------------------------- LM Studio

    private fun scanWifi() {
        if (job?.isActive == true) return
        val scanMode = selected(scanModeSpinner, scanOptions, ScanMode.BOTH)
        val llmTarget = selected(llmNetSpinner, llmOptions, NetTarget.AUTO)
        val local = scanMode == ScanMode.LOCAL
        val enteredUrl = LlmClient.normalizeBaseUrl(baseUrl.text.toString())
        scope.launch {
            scanBtn.isEnabled = false
            connStatus.text = if (local) "Проверяю локальный сервер…" else "Сканирую сеть…"
            if (local) {
                appendLog("Проверяю адрес из поля «Адрес API»: $enteredUrl …")
            } else {
                appendLog(
                    "Ищу LM Studio (${LabNetwork.targetLabel(llmTarget)}, порт 1234), " +
                        "режим: ${scanOptions.first { it.first == scanMode }.second.lowercase()}…"
                )
            }
            val network = if (local) null else withContext(Dispatchers.IO) {
                runCatching { prepareNetwork(llmTarget) }.getOrNull()
            }
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    LlmDiscovery.scan(
                        this@LabActivity,
                        mode = scanMode,
                        network = network,
                        explicitUrl = baseUrl.text.toString(),
                        onProgress = { msg -> appendLog(msg) }
                    )
                }
            }
            withContext(Dispatchers.IO) { LabNetwork.release(applicationContext) }
            scanBtn.isEnabled = true

            result.onFailure {
                connStatus.text = "Сканирование не удалось"
                appendLog("Сканирование: ${it.message}")
                return@launch
            }

            val found = result.getOrThrow()
            if (found.isEmpty()) {
                connStatus.text = "LM Studio не найден"
                appendLog(
                    if (local) {
                        "Сервер не отвечает по адресу $enteredUrl. Проверь, что он " +
                            "запущен на телефоне, слушает нужный порт и открывает " +
                            "OpenAI API (/v1/models)."
                    } else {
                        "Сервер не найден. Проверь: LM Studio → Developer → " +
                            "«Serve on Local Network» включён, ПК и телефон в одной Wi-Fi-сети, " +
                            "брандмауэр не блокирует порт 1234. Для кабеля: " +
                            "adb reverse tcp:1234 tcp:1234. Если модель на телефоне — " +
                            "выбери режим «Локально (адрес из поля)»."
                    }
                )
                return@launch
            }

            appendLog("Найдено серверов: ${found.size}")
            found.forEach { f ->
                appendLog(
                    "  ${f.url}  моделей: ${f.models.size}" +
                        (if (f.onDevice) "  (локальный адрес)" else "") +
                        (if (f.viaUsb) "  (USB)" else "") +
                        (if (f.isGateway) "  (шлюз)" else "")
                )
            }

            if (found.size == 1) {
                applyFound(found.first())
            } else {
                val labels = found.map { f ->
                    "${f.host}:1234" +
                        (if (f.onDevice) "  (локальный адрес)" else "") +
                        (if (f.models.isEmpty()) "  (нет /v1/models)" else "  • ${f.models.size} моделей")
                }.toTypedArray()
                AlertDialog.Builder(this@LabActivity)
                    .setTitle("Выбери LM Studio")
                    .setItems(labels) { _, which -> applyFound(found[which]) }
                    .setNegativeButton("Отмена", null)
                    .show()
            }
        }
    }

    /**
     * Находит сеть для выбранной цели. Для Wi-Fi/Ethernet при необходимости
     * просит систему поднять её (актуально при активной мобильной сети).
     */
    private suspend fun prepareNetwork(target: NetTarget): android.net.Network? =
        when (target) {
            NetTarget.AUTO, NetTarget.SYSTEM ->
                LabNetwork.find(applicationContext, target)
            NetTarget.USB -> null
            NetTarget.WIFI, NetTarget.CELLULAR, NetTarget.ETHERNET ->
                LabNetwork.acquire(applicationContext, target, bringUpBox.isChecked)
        }

    private fun applyFound(found: LlmDiscovery.Found) {
        baseUrl.setText(found.url)
        setModels(found.models)
        connStatus.text = if (found.onDevice) {
            "Найден по адресу из поля: ${found.host}"
        } else {
            "Найден: ${found.host}"
        }
        appendLog("Выбран сервер: ${found.url}")
        updateNetSummary()
        savePrefs()
    }

    private fun checkLlm() {
        val url = LlmClient.normalizeBaseUrl(baseUrl.text.toString())
        baseUrl.setText(url)
        val llmTarget = selected(llmNetSpinner, llmOptions, NetTarget.AUTO)
        val localUrl = LabNetwork.isLoopbackUrl(url)
        scope.launch {
            checkBtn.isEnabled = false
            connStatus.text = "Проверяю $url …"
            appendLog(
                "Проверяю LLM: $url (" +
                    (if (localUrl) "локально, без привязки к сети" else "сеть: ${LabNetwork.targetLabel(llmTarget)}") +
                    ")"
            )
            val network = if (localUrl) null else withContext(Dispatchers.IO) {
                runCatching { prepareNetwork(llmTarget) }.getOrNull()
            }
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    LlmClient(
                        baseUrl = url,
                        model = currentModel(),
                        apiKey = apiKey.text.toString().trim(),
                        network = network,
                        readTimeoutMs = number(llmTimeout, 300, 10, 1800) * 1000
                    ).listModels()
                }
            }
            withContext(Dispatchers.IO) { LabNetwork.release(applicationContext) }
            checkBtn.isEnabled = true

            result.onSuccess { models ->
                setModels(models)
                if (models.isEmpty()) {
                    connStatus.text = "Сервер отвечает, моделей нет"
                    appendLog("LM Studio ответил, но список моделей пуст (загрузи модель).")
                } else {
                    connStatus.text = "OK • моделей: ${models.size}"
                    appendLog("OK. Модели: ${models.joinToString(", ")}")
                }
            }.onFailure {
                connStatus.text = "Ошибка подключения"
                appendLog("Не удалось подключиться: ${it.message}")
            }
        }
    }

    // ---------------------------------------------------------- применение/отчёты

    private fun applyLastBest() {
        val best = runCatching { LabStore(this).lastBest() }.getOrNull()
        if (best == null || best.strategy.isBlank()) {
            toast("Пока нет найденных стратегий")
            return
        }
        confirmApply(best.strategy)
    }

    private fun confirmApply(strategy: String) {
        AlertDialog.Builder(this)
            .setTitle("Применить стратегию?")
            .setMessage(
                "$strategy\n\n" +
                    "Будет записана в настройки приложения. " +
                    "Если VPN/Proxy запущен, он будет перезапущен."
            )
            .setPositiveButton("Применить") { _, _ -> applyStrategy(strategy) }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun applyStrategy(strategy: String) {
        if (strategy.isBlank()) return
        val appPrefs = PreferenceManager.getDefaultSharedPreferences(this)
        appPrefs.edit(commit = true) {
            putString("byedpi_cmd_args", strategy)
            if (!appPrefs.getBoolean("byedpi_enable_cmd_settings", true)) {
                putBoolean("byedpi_enable_cmd_settings", true)
            }
        }
        runCatching { HistoryUtils(this).addCommand(strategy) }

        val (state, mode) = appStatus
        if (state == AppStatus.Running) {
            runCatching { ServiceManager.restart(applicationContext, mode) }
            toast("Стратегия применена, прокси перезапускается")
        } else {
            toast("Стратегия сохранена в настройки")
        }
        appendLog("Применена стратегия: $strategy")
        refreshBest()
    }

    private fun showReport() {
        val f = runCatching { LabStore(this).latestReport() }.getOrNull()
        if (f == null) {
            toast("Отчётов пока нет")
            return
        }
        val text = runCatching { f.readText() }.getOrDefault("Не удалось прочитать отчёт")
        val view = TextView(this).apply {
            this.text = text
            textSize = 11f
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
            setPadding(dp(16), dp(8), dp(16), dp(8))
        }
        val sv = ScrollView(this).apply { addView(view) }
        AlertDialog.Builder(this)
            .setTitle("Отчёт: ${f.name}")
            .setView(sv)
            .setPositiveButton("Закрыть", null)
            .show()
    }

    private fun showTop() {
        val list = runCatching { LabStore(this).bestStrategies() }.getOrDefault(emptyList())
        if (list.isEmpty()) {
            toast("Топ пока пуст — запусти подбор")
            return
        }
        val text = list.take(12).joinToString("\n\n") { b ->
            "${pct(b.score)}  ${b.ok}/${b.total}\n${b.strategy}"
        }
        AlertDialog.Builder(this)
            .setTitle("Топ стратегий")
            .setMessage(text)
            .setPositiveButton("Применить лучшую") { _, _ ->
                list.firstOrNull()?.let { confirmApply(it.strategy) }
            }
            .setNegativeButton("Закрыть", null)
            .show()
    }

    private fun refreshBest() {
        val best = runCatching { LabStore(this).lastBest() }.getOrNull()
        bestView.text = if (best == null || best.strategy.isBlank()) {
            "Лучшая: —"
        } else {
            "Лучшая: ${pct(best.score)} • ${best.strategy}"
        }
    }

    // ----------------------------------------------------------------- прогресс

    private fun onProgress(p: LabProgress) {
        runOnUiThread {
            if (p.bestStrategy.isNotBlank()) {
                lastBestStrategy = p.bestStrategy
                lastBestScore = p.bestScore
            }
            when (p.phase) {
                "baseline" -> {
                    progressBar.progress = 0
                    status.text = "Раунд ${p.round}: измеряю baseline…"
                    notifyLab("Раунд ${p.round}: baseline…", null, true)
                }
                "probe" -> {
                    val value = if (p.total > 0) p.index * 100 / p.total else 0
                    progressBar.progress = value
                    status.text = "Раунд ${p.round} • ${p.index}/${p.total} • ${pct(p.bestScore)}"
                    notifyLab(
                        "Раунд ${p.round} • ${p.index}/${p.total} • ${pct(p.bestScore)}",
                        value,
                        false
                    )
                }
                "analyze" -> {
                    status.text = "Раунд ${p.round}: анализ LLM…"
                    notifyLab("Раунд ${p.round}: анализ LLM…", null, true)
                }
                "validate" -> {
                    status.text = "Финальная проверка лучшей стратегии…"
                    notifyLab("Финальная проверка лучшей стратегии…", null, true)
                }
                "done" -> {
                    progressBar.progress = 100
                    status.text = "Готово: ${pct(p.bestScore)}"
                    notifyLab("Готово: ${pct(p.bestScore)}", 100, false)
                }
            }
            if (p.bestStrategy.isNotBlank()) {
                bestView.text = "Лучшая: ${pct(p.bestScore)} • ${p.bestStrategy}"
            }
        }
    }

    private fun notifyLab(
        state: String,
        percent: Int?,
        indeterminate: Boolean,
        ongoing: Boolean = true
    ) {
        runCatching {
            val cfg = lastCfg
            val elapsedSec = if (startedAt > 0) {
                ((System.currentTimeMillis() - startedAt) / 1000).coerceAtLeast(0)
            } else {
                0
            }
            val best = if (lastBestStrategy.isBlank()) {
                "—"
            } else {
                "${pct(lastBestScore)} · $lastBestStrategy"
            }
            val big = buildString {
                append(state)
                if (cfg != null) {
                    append("\nСайтов: ").append(cfg.sites.size)
                    append(" · повторов: ").append(cfg.repeats)
                    append(" · раундов: ").append(cfg.rounds)
                }
                append("\nЛучшая: ").append(best)
                append("\nПрошло: ").append(elapsedSec / 60).append(" мин ")
                    .append(elapsedSec % 60).append(" с")
                if (!ongoing) append("\nНажми, чтобы открыть лабораторию.")
            }
            LabNotifications.update(
                context = applicationContext,
                title = getString(
                    if (ongoing) R.string.lab_notif_title else R.string.lab_notif_done_title
                ),
                text = state,
                bigText = big,
                percent = percent,
                indeterminate = indeterminate,
                ongoing = ongoing
            )
        }
    }

    private fun updatePromptBudget() {
        if (!::promptBudget.isInitialized) return
        if (budgetColor == 0) budgetColor = promptBudget.currentTextColor
        val useKb = useKnowledge.isChecked
        val kb = if (useKb) knowledgeBase.text.toString().trim() else ""
        val kbTokens = if (kb.isEmpty()) 0 else PromptBuilder.estimateTokens(kb)
        val systemTokens = basePromptTokens + kbTokens
        val answer = number(maxTokens, 1200, 200, 8000)
        val context = number(contextTokens, 8192, 0, 131072)
        promptBudget.text = getString(
            R.string.lab_prompt_budget,
            systemTokens,
            basePromptTokens,
            kbTokens,
            answer,
            context
        )
        promptBudget.setTextColor(
            if (context > 0 && systemTokens + answer > context) {
                0xFFD32F2F.toInt()
            } else {
                budgetColor
            }
        )
    }

    /** Полноэкранный редактор для длинных полей: удобно на маленьком экране. */
    private fun showFullscreenEditor(title: String, source: EditText) {
        val input = EditText(this).apply {
            setText(source.text)
            gravity = Gravity.TOP or Gravity.START
            inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            typeface = Typeface.MONOSPACE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(dp(16), dp(12), dp(16), dp(12))
            setScrollbarFadingEnabled(false)
            isVerticalScrollBarEnabled = true
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(title)
            .setView(input)
            .setPositiveButton(R.string.lab_done_btn) { _, _ ->
                source.setText(input.text)
                updatePromptBudget()
            }
            .setNegativeButton(R.string.lab_cancel_btn, null)
            .create()
        dialog.setOnShowListener {
            dialog.window?.setLayout(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT
            )
            input.requestFocus()
        }
        dialog.show()
    }

    /** Полноэкранный просмотр длинного текста (журнал). */
    private fun showFullscreenText(title: String, text: String) {
        val body = TextView(this).apply {
            this.text = text.ifBlank { "—" }
            typeface = Typeface.MONOSPACE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(dp(16), dp(12), dp(16), dp(12))
            setTextIsSelectable(true)
        }
        val scroll = ScrollView(this).apply { addView(body) }
        val dialog = AlertDialog.Builder(this)
            .setTitle(title)
            .setView(scroll)
            .setPositiveButton(R.string.lab_close_btn, null)
            .create()
        dialog.setOnShowListener {
            dialog.window?.setLayout(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT
            )
        }
        dialog.show()
    }

    private fun appendLog(line: String) {
        runOnUiThread {
            if (isFinishing || isDestroyed) return@runOnUiThread
            val current = logView.text?.toString().orEmpty()
            logView.text = when {
                current.isEmpty() -> line
                current.length > 80_000 -> current.takeLast(60_000) + "\n" + line
                else -> current + "\n" + line
            }
            scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
        }
    }

    // -------------------------------------------------------------- настройки

    private fun loadPrefs() {
        baseUrl.setText(prefs.getString("base_url", "http://127.0.0.1:1234/v1"))
        apiKey.setText(prefs.getString("api_key", ""))
        setModels(emptyList())

        rounds.setText(prefs.getString("rounds", "3"))
        repeats.setText(prefs.getString("repeats", "2"))
        timeout.setText(prefs.getString("timeout", "6"))
        candidates.setText(prefs.getString("candidates", "10"))
        patience.setText(prefs.getString("patience", "2"))
        concurrency.setText(prefs.getString("concurrency", "8"))
        maxTokens.setText(prefs.getString("max_tokens", "1200"))
        contextTokens.setText(prefs.getString("context_tokens", "8192"))
        llmTimeout.setText(prefs.getString("llm_timeout", "300"))
        autoApply.isChecked = prefs.getBoolean("auto_apply", true)
        reuseBaseline.isChecked = prefs.getBoolean("reuse_baseline", true)
        useKnowledge.isChecked = prefs.getBoolean("use_knowledge", true)
        knowledgeBase.setText(
            prefs.getString("knowledge_base", null) ?: PromptBuilder.defaultKnowledge()
        )

        select(
            scanModeSpinner, scanOptions,
            runCatching { ScanMode.valueOf(prefs.getString("scan_mode", "")!!) }
                .getOrDefault(ScanMode.BOTH)
        )
        select(
            llmNetSpinner, llmOptions,
            runCatching { NetTarget.valueOf(prefs.getString("llm_net", "")!!) }
                .getOrDefault(NetTarget.AUTO)
        )
        select(
            testNetSpinner, testOptions,
            runCatching { NetTarget.valueOf(prefs.getString("test_net", "")!!) }
                .getOrDefault(NetTarget.SYSTEM)
        )
        bringUpBox.isChecked = prefs.getBoolean("bring_up", true)
        updateNetSummary()

        sites.setText(prefs.getString("sites", null) ?: readAsset("lab_sites.txt"))
        strategies.setText(prefs.getString("strategies", null) ?: readAsset("lab_strategies.txt"))
    }

    private fun savePrefs() {
        prefs.edit {
            putString("base_url", baseUrl.text.toString().trim())
            putString("model", currentModel())
            putString("api_key", apiKey.text.toString().trim())
            putString("rounds", rounds.text.toString().trim())
            putString("repeats", repeats.text.toString().trim())
            putString("timeout", timeout.text.toString().trim())
            putString("candidates", candidates.text.toString().trim())
            putString("patience", patience.text.toString().trim())
            putString("concurrency", concurrency.text.toString().trim())
            putString("max_tokens", maxTokens.text.toString().trim())
            putString("context_tokens", contextTokens.text.toString().trim())
            putString("llm_timeout", llmTimeout.text.toString().trim())
            putBoolean("auto_apply", autoApply.isChecked)
            putBoolean("reuse_baseline", reuseBaseline.isChecked)
            putBoolean("use_knowledge", useKnowledge.isChecked)
            putString("knowledge_base", knowledgeBase.text.toString())
            putString("scan_mode", selected(scanModeSpinner, scanOptions, ScanMode.BOTH).name)
            putString("llm_net", selected(llmNetSpinner, llmOptions, NetTarget.AUTO).name)
            putString("test_net", selected(testNetSpinner, testOptions, NetTarget.SYSTEM).name)
            putBoolean("bring_up", bringUpBox.isChecked)
            putString("sites", sites.text.toString())
            putString("strategies", strategies.text.toString())
        }
    }

    private fun setModels(ids: List<String>) {
        modelIds.clear()
        modelIds.add("")
        modelIds.addAll(ids.map { it.trim() }.filter { it.isNotEmpty() }.distinct())

        val labels = ArrayList<String>()
        labels.add(if (modelIds.size > 1) "auto — доступно ${modelIds.size - 1}" else "auto — модель по умолчанию")
        for (i in 1 until modelIds.size) labels.add(modelIds[i])

        modelAdapter.clear()
        modelAdapter.addAll(labels)
        modelAdapter.notifyDataSetChanged()

        val saved = prefs.getString("model", "").orEmpty()
        val index = modelIds.indexOf(saved)
        if (index > 0) modelSpinner.setSelection(index) else modelSpinner.setSelection(0)
    }

    private fun currentModel(): String {
        val pos = modelSpinner.selectedItemPosition
        return if (pos in 1 until modelIds.size) modelIds[pos] else ""
    }

    private fun loadPresetList(): List<Pair<String, String>> {
        val files = runCatching { assets.list("")?.toList() }.getOrNull().orEmpty()
            .filter { it == "lab_sites.txt" || it.endsWith(".sites") }
        val order = listOf(
            "lab_sites.txt",
            "proxytest_youtube.sites",
            "proxytest_social.sites",
            "proxytest_googlevideo.sites",
            "proxytest_general.sites",
            "proxytest_cloudflare.sites",
            "proxytest_telegram.sites",
            "proxytest_telegram_soundcloud.sites",
            "proxytest_discord.sites",
            "proxytest_turkiye.sites"
        )
        return files.sortedBy { file -> order.indexOf(file).let { if (it < 0) 99 else it } }
            .map { file -> prettyPreset(file) to file }
    }

    private fun prettyPreset(file: String): String = when (file) {
        "lab_sites.txt" -> "Мобильный (YouTube + Instagram + TG + SC)"
        "proxytest_youtube.sites" -> "YouTube"
        "proxytest_social.sites" -> "Соцсети"
        "proxytest_googlevideo.sites" -> "Google Video"
        "proxytest_general.sites" -> "Общие"
        "proxytest_cloudflare.sites" -> "Cloudflare"
        "proxytest_telegram.sites" -> "Telegram"
        "proxytest_telegram_soundcloud.sites" -> "Telegram + SoundCloud"
        "proxytest_discord.sites" -> "Discord"
        "proxytest_turkiye.sites" -> "Турция"
        else -> file.removeSuffix(".sites").removePrefix("proxytest_")
    }

    private fun loadPreset() {
        val index = presetSpinner.selectedItemPosition
        val preset = presets.getOrNull(index) ?: return
        sites.setText(readAsset(preset.second))
        toast("Загружен набор: ${preset.first}")
    }

    // ------------------------------------------------------------------ утилиты

    private fun readAsset(name: String): String = runCatching {
        assets.open(name).bufferedReader().use { it.readText() }
    }.getOrDefault("")

    private fun <T> setupSpinner(spinner: Spinner, options: List<Pair<T, String>>) {
        spinner.adapter = ArrayAdapter(
            this,
            R.layout.lab_spinner_item,
            options.map { it.second }
        ).apply { setDropDownViewResource(R.layout.lab_spinner_dropdown_item) }
    }

    private fun <T> selected(spinner: Spinner, options: List<Pair<T, String>>, def: T): T =
        options.getOrNull(spinner.selectedItemPosition)?.first ?: def

    private fun <T> select(spinner: Spinner, options: List<Pair<T, String>>, value: T) {
        val index = options.indexOfFirst { it.first == value }
        if (index >= 0) spinner.setSelection(index)
    }

    private fun number(field: EditText, def: Int, min: Int, max: Int): Int =
        field.text.toString().toIntOrNull()?.coerceIn(min, max) ?: def

    private fun pct(v: Double): String = "${(v * 100).toInt()}%"

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (job?.isActive == true) {
            AlertDialog.Builder(this)
                .setTitle("Остановить подбор?")
                .setPositiveButton("Остановить и выйти") { _, _ ->
                    stop()
                    finish()
                }
                .setNegativeButton("Продолжить", null)
                .show()
        } else {
            super.onBackPressed()
        }
    }

    override fun onDestroy() {
        job?.cancel()
        scope.cancel()
        LabNotifications.cancel(this)
        super.onDestroy()
    }
}
