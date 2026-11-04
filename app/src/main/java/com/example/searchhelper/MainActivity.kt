package com.example.searchhelper

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.provider.Settings
import android.text.InputType
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Entry point: edit the AI settings, grant permissions, start the capture service, then
 * hand over to the floating window. Everything after that happens without leaving the
 * answering app.
 */
class MainActivity : AppCompatActivity() {

    private var isBound = false
    private var pendingResultCode = 0
    private var pendingData: Intent? = null
    private var replaceOnImport = false

    // AI settings widgets.
    private lateinit var etEndpoint: EditText
    private lateinit var etModel: EditText
    private lateinit var etApiKey: EditText
    private lateinit var spinnerPreset: Spinner
    private lateinit var tvKeyStatus: TextView
    private lateinit var btnTestAi: Button

    /** Guards the preset spinner callback while we set the selection programmatically. */
    private var syncingPreset = false

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val capture = (service as ScreenCaptureService.CaptureBinder).getService()
            isBound = true
            pendingData?.let { data ->
                capture.initMediaProjection(pendingResultCode, data)
                pendingData = null
            }
            showFloatWindow()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            isBound = false
        }
    }

    /** Single place that puts the window on screen and steps out of the way. */
    private fun showFloatWindow() {
        FloatWindow.show(this)
        moveTaskToBack(true)
    }

    private val notificationLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { launchProjection() }

    private val projectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            pendingResultCode = result.resultCode
            pendingData = Intent(result.data!!)
            val intent = Intent(this, ScreenCaptureService::class.java)
            ContextCompat.startForegroundService(this, intent)
            val live = ScreenCaptureService.instance
            if (isBound && live != null) {
                // Already bound to a running service. Do not wait for a second
                // onServiceConnected (it may never fire for an existing binding) —
                // hand the new grant straight to the service and show the window now.
                pendingData?.let { data ->
                    live.initMediaProjection(pendingResultCode, data)
                    pendingData = null
                }
                showFloatWindow()
            } else {
                bindService(intent, connection, Context.BIND_AUTO_CREATE)
            }
            setStatus("服务已启动，请切到答题 App 后点击悬浮窗【截屏搜题】")
        } else {
            Toast.makeText(this, "需要授予截屏权限才能进行识屏", Toast.LENGTH_SHORT).show()
        }
    }

    /** System file picker: .xlsx / .csv / .txt. */
    private val importLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri == null) return@registerForActivityResult
        val replace = replaceOnImport
        replaceOnImport = false
        lifecycleScope.launch {
            setStatus("正在解析题库文件...")
            val outcome = withContext(Dispatchers.IO) {
                runCatching { BankImporter.readTable(this@MainActivity, uri) }
            }
            outcome.onSuccess { table ->
                when {
                    table.rows.isEmpty() -> setStatus("导入失败：未读取到任何数据行")
                    table.columnCount < 2 -> setStatus("导入失败：文件至少需要两列（题目、答案）")
                    else -> showColumnPicker(table, replace)
                }
            }.onFailure { error ->
                reportError("解析失败", error)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        etEndpoint = findViewById(R.id.et_endpoint)
        etModel = findViewById(R.id.et_model)
        etApiKey = findViewById(R.id.et_api_key)
        spinnerPreset = findViewById(R.id.spinner_preset)
        tvKeyStatus = findViewById(R.id.tv_key_status)
        btnTestAi = findViewById(R.id.btn_test_ai)

        bindAiSettings()

        findViewById<Button>(R.id.btn_start).setOnClickListener {
            // Persist whatever is on screen (and refuse to start on invalid input).
            if (!saveAiConfig()) return@setOnClickListener
            startOrShowFloat()
        }

        findViewById<Button>(R.id.btn_import).setOnClickListener { askImportMode() }

        findViewById<Button>(R.id.btn_clear).setOnClickListener { confirmClearImported() }

        findViewById<Button>(R.id.btn_stop).setOnClickListener {
            FloatWindow.dismiss()
            stopService(Intent(this, ScreenCaptureService::class.java))
            setStatus("服务已停止")
        }

        setupRegionMode()
        refreshStatus()
    }

    override fun onResume() {
        super.onResume()
        // Back from the answering app: refresh the service / bank / AI state line.
        refreshStatus()
    }

    // ---------------------------------------------------------------- AI settings

    private fun bindAiSettings() {
        etEndpoint.setText(Config.endpoint)
        etModel.setText(Config.model)
        etApiKey.setText(Config.apiKey)

        // Provider presets + a trailing "custom" entry.
        val labels = Config.PRESETS.map { it.label } + "自定义"
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, labels)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerPreset.adapter = adapter
        spinnerPreset.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, view: View?, position: Int, id: Long) {
                if (syncingPreset) return
                val preset = Config.PRESETS.getOrNull(position) ?: return // 自定义: keep typing
                etEndpoint.setText(preset.endpoint)
                etModel.setText(preset.model)
                etEndpoint.error = null
                etModel.error = null
            }

            override fun onNothingSelected(parent: AdapterView<*>) = Unit
        }
        syncPresetSelection()

        findViewById<CheckBox>(R.id.check_show_key).setOnCheckedChangeListener { _, checked ->
            val caret = etApiKey.selectionEnd
            etApiKey.inputType = if (checked) {
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            } else {
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            }
            etApiKey.setSelection(caret)
        }

        findViewById<Button>(R.id.btn_save_ai).setOnClickListener { saveAiConfig() }
        btnTestAi.setOnClickListener { testAiConnection() }

        findViewById<Button>(R.id.btn_reset_ai).setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("恢复默认接口配置")
                .setMessage("将接口地址与模型名称恢复为 DeepSeek 默认值。\n已保存的 API Key 不会被清除。")
                .setNegativeButton("取消", null)
                .setPositiveButton("恢复默认") { _, _ ->
                    Config.resetDefaults()
                    etEndpoint.setText(Config.endpoint)
                    etModel.setText(Config.model)
                    etEndpoint.error = null
                    etModel.error = null
                    syncPresetSelection()
                    refreshAiStatus()
                    refreshStatus()
                    Toast.makeText(this, "已恢复默认接口配置", Toast.LENGTH_SHORT).show()
                }
                .show()
        }

        refreshAiStatus()
    }

    /** Move the preset spinner to whatever matches the two text fields (or 自定义). */
    private fun syncPresetSelection() {
        syncingPreset = true
        val index = Config.indexOfPreset(etEndpoint.text.toString(), etModel.text.toString())
        spinnerPreset.setSelection(if (index >= 0) index else Config.PRESETS.size)
        syncingPreset = false
    }

    /**
     * Validate the three fields and persist them.
     * @return true when saved (and therefore already in effect), false when rejected.
     */
    private fun saveAiConfig(): Boolean {
        val endpointError = Config.checkEndpoint(etEndpoint.text.toString())
        val modelError = Config.checkModel(etModel.text.toString())
        val keyError = Config.checkApiKey(etApiKey.text.toString())

        etEndpoint.error = endpointError
        etModel.error = modelError
        etApiKey.error = keyError
        when {
            endpointError != null -> etEndpoint.requestFocus()
            modelError != null -> etModel.requestFocus()
            keyError != null -> etApiKey.requestFocus()
        }

        if (endpointError != null || modelError != null || keyError != null) {
            val reasons = listOfNotNull(endpointError, modelError, keyError)
            Toast.makeText(this, "保存失败：${reasons.first()}", Toast.LENGTH_LONG).show()
            setStatus("AI 配置未保存：${reasons.joinToString("；")}")
            return false
        }

        Config.endpoint = etEndpoint.text.toString()
        Config.model = etModel.text.toString()
        Config.apiKey = etApiKey.text.toString()

        syncPresetSelection()
        refreshAiStatus()
        refreshStatus()
        Toast.makeText(this, "AI 配置已保存，立即生效", Toast.LENGTH_SHORT).show()
        return true
    }

    /** Fire one tiny request so a wrong endpoint / key / model shows up immediately. */
    private fun testAiConnection() {
        if (!saveAiConfig()) return
        if (!Config.hasApiKey) {
            Toast.makeText(this, "未填写 API Key，无法测试连接", Toast.LENGTH_LONG).show()
            return
        }

        btnTestAi.isEnabled = false
        setStatus("正在测试 AI 接口连通性...")
        lifecycleScope.launch {
            val outcome = withContext(Dispatchers.IO) {
                runCatching { QuestionSolver.testConnection() }
            }
            btnTestAi.isEnabled = true
            outcome.onSuccess { message ->
                setStatus("连接成功：$message")
                Toast.makeText(this@MainActivity, "连接成功：$message", Toast.LENGTH_LONG).show()
            }.onFailure { error ->
                reportError("连接失败", error)
            }
        }
    }

    private fun refreshAiStatus() {
        tvKeyStatus.text = "接口：${Config.endpoint}\n模型：${Config.model}\nKey：${Config.maskedApiKey()}"
    }

    // ---------------------------------------------------------------- question bank

    private fun askImportMode() {
        AlertDialog.Builder(this)
            .setTitle("导入题库")
            .setMessage(
                "选择导入方式：\n\n" +
                    "· 追加：保留现有题库，跳过完全重复项，保留冲突答案供核对\n" +
                    "· 覆盖：先清空已导入的题库，再导入（内置示例题库保留）\n\n" +
                    "下一步会显示列预览，由你指定哪一列是题目、哪一列是答案"
            )
            .setPositiveButton("追加导入") { _, _ ->
                replaceOnImport = false
                importLauncher.launch("*/*")
            }
            .setNeutralButton("覆盖导入") { _, _ ->
                replaceOnImport = true
                importLauncher.launch("*/*")
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 清空前确认：没有导入文件时直接说明，避免无意义的确认弹窗。 */
    private fun confirmClearImported() {
        if (!QuestionBank.hasImported(this)) {
            Toast.makeText(this, "当前没有已导入的题库，无需清空", Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("清空导入题库")
            .setMessage("将删除已导入的 ${QuestionBank.importedCount} 条题目，内置题库保留。")
            .setNegativeButton("取消", null)
            .setPositiveButton("清空") { _, _ ->
                QuestionBank.clearImported(this)
                refreshStatus()
                Toast.makeText(this, "已清空导入的题库", Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    /**
     * Let the user confirm which column holds the question and which holds the answer.
     * Guessed defaults come from [BankImporter.guessColumns].
     */
    private fun showColumnPicker(data: BankImporter.TableData, replace: Boolean) {
        val view = layoutInflater.inflate(R.layout.dialog_column_picker, null)
        val spinnerQuestion = view.findViewById<Spinner>(R.id.spinner_question)
        val spinnerAnswer = view.findViewById<Spinner>(R.id.spinner_answer)
        val checkHeader = view.findViewById<CheckBox>(R.id.check_header)
        val tvInfo = view.findViewById<TextView>(R.id.tv_info)
        val tvSample = view.findViewById<TextView>(R.id.tv_sample)

        val labels = BankImporter.columnLabels(data)
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, labels)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerQuestion.adapter = adapter
        spinnerAnswer.adapter = adapter

        val guess = BankImporter.guessColumns(data)
        spinnerQuestion.setSelection(guess.question)
        spinnerAnswer.setSelection(guess.answer)
        checkHeader.isChecked = guess.hasHeader

        tvInfo.text = "共读取 ${data.rows.size} 行、${data.columnCount} 列。" +
            "已自动猜测列位置（${if (guess.hasHeader) "识别到表头" else "按内容长度推断"}），请确认或修改："
        tvSample.text = BankImporter.sampleText(data) + if (data.notices.isEmpty()) "" else
            "\n\n跳过 ${data.notices.size} 行，以下有效题目才会导入：\n" + data.notices.take(8).joinToString("\n")
        if (data.structured != null) {
            spinnerQuestion.isEnabled = false
            spinnerAnswer.isEnabled = false
            checkHeader.isEnabled = false
            checkHeader.isChecked = false
            tvInfo.text = "结构化题库：${data.structured.size} 题（含选项和答案），请核对预览。"
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle("确认列对应关系")
            .setView(view)
            .setNegativeButton("取消", null)
            .setPositiveButton("导入", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val questionColumn = spinnerQuestion.selectedItemPosition
                val answerColumn = spinnerAnswer.selectedItemPosition
                if (questionColumn == answerColumn) {
                    Toast.makeText(this, "题目列与答案列不能是同一列", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                dialog.dismiss()
                performImport(data, questionColumn, answerColumn, checkHeader.isChecked, replace)
            }
        }
        dialog.show()
    }

    private fun performImport(
        data: BankImporter.TableData,
        questionColumn: Int,
        answerColumn: Int,
        hasHeader: Boolean,
        replace: Boolean
    ) {
        lifecycleScope.launch {
            setStatus("正在写入题库...")
            val outcome = withContext(Dispatchers.IO) {
                runCatching {
                    val pairs = BankImporter.toQuestions(data, questionColumn, answerColumn, hasHeader)
                    QuestionBank.saveImported(this@MainActivity, pairs, append = !replace)
                }
            }
            outcome.onSuccess { total ->
                refreshStatus()
                Toast.makeText(this@MainActivity, "导入成功，题库共 $total 条", Toast.LENGTH_SHORT).show()
            }.onFailure { error ->
                reportError("导入失败", error)
            }
        }
    }

    private fun reportError(prefix: String, error: Throwable) {
        val message = error.message ?: error.toString()
        setStatus("$prefix：$message")
        Toast.makeText(this, "$prefix：$message", Toast.LENGTH_LONG).show()
    }

    // ---------------------------------------------------------------- launch / stop

    /**
     * The capture service outlives the floating window: closing the window does not stop
     * the service. So when the service is still alive and holds a working projection,
     * just re-open the window — asking for the screen-capture grant again is unnecessary
     * and, worse, the old code relied on a second bind callback that never arrives.
     */
    private fun startOrShowFloat() {
        if (ScreenCaptureService.instance?.isReady() == true) {
            showFloatWindow()
            setStatus("悬浮窗已重新显示（服务仍在运行，无需重新授权）")
            return
        }
        checkAndLaunch()
    }

    private fun checkAndLaunch() {
        if (!Settings.canDrawOverlays(this)) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
            Toast.makeText(this, "请授予悬浮窗权限后重新点击", Toast.LENGTH_LONG).show()
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }

        launchProjection()
    }

    private fun launchProjection() {
        val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projectionLauncher.launch(manager.createScreenCaptureIntent())
    }

    // ---------------------------------------------------------------- capture area

    /** Spinner entries: label -> Config.regionMode, order must match spinner_region. */
    private val regionLabels = arrayOf("全屏识别（默认）", "每次手动框选", "记住上次框选区域")
    private val regionValues = arrayOf(
        Config.REGION_MODE_FULL,
        Config.REGION_MODE_SELECT,
        Config.REGION_MODE_LAST
    )

    private lateinit var spinnerRegion: Spinner
    private lateinit var tvRegionHint: TextView
    private var syncingRegion = false

    private fun setupRegionMode() {
        spinnerRegion = findViewById(R.id.spinner_region)
        tvRegionHint = findViewById(R.id.tv_region_hint)

        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, regionLabels)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerRegion.adapter = adapter

        spinnerRegion.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, view: View?, position: Int, id: Long) {
                if (syncingRegion) return
                Config.regionMode = regionValues[position]
                FloatWindow.refreshModeLabel()
                refreshRegionHint()
            }

            override fun onNothingSelected(parent: AdapterView<*>) = Unit
        }

        findViewById<Button>(R.id.btn_clear_region).setOnClickListener {
            Config.clearLastRegion()
            refreshRegionHint()
            Toast.makeText(this, "已清除保存的框选区域", Toast.LENGTH_SHORT).show()
        }

        syncingRegion = true
        spinnerRegion.setSelection(regionValues.indexOf(Config.regionMode).coerceAtLeast(0))
        syncingRegion = false
        refreshRegionHint()
    }

    private fun refreshRegionHint() {
        if (!::tvRegionHint.isInitialized) return
        val saved = Config.lastRegionRect
        val savedText = if (saved == null) {
            "尚未保存框选区域"
        } else {
            "已保存区域：左 ${(saved.left * 100).toInt()}% 上 ${(saved.top * 100).toInt()}% " +
                "右 ${(saved.right * 100).toInt()}% 下 ${(saved.bottom * 100).toInt()}%"
        }
        tvRegionHint.text = when (Config.regionMode) {
            Config.REGION_MODE_SELECT ->
                "每次截屏后弹框选界面：空白处拖动新建、框内拖动移动、四角缩放，确定后自动记住本次区域。\n$savedText"
            Config.REGION_MODE_LAST ->
                "直接复用上次框选的区域进行 OCR，不再弹框。\n$savedText"
            else -> "每次【截屏搜题】识别整块屏幕（速度最快，原始行为）。"
        }
    }

    // ---------------------------------------------------------------- status

    private fun bankSummary(): String {
        return "题库：共 ${QuestionBank.size} 条（内置 ${QuestionBank.builtInCount}" +
            " + 导入 ${QuestionBank.importedCount}）；AI：${Config.model}"
    }

    /** Bank + AI + capture-service + floating-window state. */
    private fun refreshStatus() {
        val service = ScreenCaptureService.instance
        val serviceState = if (service == null) {
            "截屏服务：未运行"
        } else {
            val size = service.screenSize()
            val ready = if (service.isReady()) "可截屏" else "投影未就绪，请重启服务"
            "截屏服务：运行中（分辨率 ${size.first}×${size.second}，$ready）"
        }
        val floatState = if (FloatWindow.isShowing()) "显示中" else "未显示"
        setStatus("$serviceState\n悬浮窗：$floatState\n${bankSummary()}")
    }

    private fun setStatus(text: String) {
        findViewById<TextView>(R.id.tv_status).text = text
    }

    override fun onDestroy() {
        if (isBound) {
            unbindService(connection)
            isBound = false
        }
        super.onDestroy()
    }
}
