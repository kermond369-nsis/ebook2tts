package com.mimo.ebook2tts.local.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.mimo.ebook2tts.local.LocalPrefs
import com.mimo.ebook2tts.local.R
import com.mimo.ebook2tts.local.model.ModelCatalog
import com.mimo.ebook2tts.local.model.ModelDownloader
import com.mimo.ebook2tts.local.voice.LocalVoice
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class LocalSettingsActivity : AppCompatActivity() {

    companion object {
        private const val ACTION_TTS_SETTINGS = "com.android.settings.TTS_SETTINGS"
    }

    private lateinit var backendGroup: RadioGroup
    private lateinit var modelLabel: TextView
    private lateinit var modelDesc: TextView
    private lateinit var modelStatus: TextView
    private lateinit var narratorLabel: TextView
    private lateinit var progressText: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var editCustomUrl: EditText
    private lateinit var speedText: TextView
    private lateinit var speedBar: SeekBar
    private lateinit var threadText: TextView
    private lateinit var threadBar: SeekBar

    private var selectedModelId: String = ""
    private var downloading = false
    private var tts: android.speech.tts.TextToSpeech? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_local_settings)

        backendGroup = findViewById(R.id.backendGroup)
        modelLabel = findViewById(R.id.modelLabel)
        modelDesc = findViewById(R.id.modelDesc)
        modelStatus = findViewById(R.id.modelStatus)
        narratorLabel = findViewById(R.id.narratorLabel)
        progressText = findViewById(R.id.progressText)
        progressBar = findViewById(R.id.progressBar)
        editCustomUrl = findViewById(R.id.editCustomUrl)
        speedText = findViewById(R.id.speedText)
        speedBar = findViewById(R.id.speedBar)
        threadText = findViewById(R.id.threadText)
        threadBar = findViewById(R.id.threadBar)

        selectedModelId = LocalPrefs.modelId(this)
        bindUi()

        findViewById<Button>(R.id.btnPickModel).setOnClickListener { pickModel() }
        findViewById<Button>(R.id.btnDownload).setOnClickListener { downloadModel() }
        findViewById<Button>(R.id.btnDeleteModel).setOnClickListener { deleteModel() }
        findViewById<Button>(R.id.btnPickNarrator).setOnClickListener { pickNarrator() }
        findViewById<Button>(R.id.btnOpenTtsSettings).setOnClickListener { openTtsSettings() }
        findViewById<Button>(R.id.btnTest).setOnClickListener { testSpeak() }

        backendGroup.setOnCheckedChangeListener { _, checkedId ->
            val backend = if (checkedId == R.id.radioSystem) {
                LocalPrefs.Backend.SYSTEM.id
            } else {
                LocalPrefs.Backend.SHERPA.id
            }
            LocalPrefs.setBackend(this, backend)
        }

        speedBar.progress = ((LocalPrefs.speed(this) - 0.5f) / 1.5f * 100).toInt()
        speedBar.setOnSeekBarChangeListener(simpleSeek { p ->
            val v = 0.5f + p / 100f * 1.5f
            LocalPrefs.setSpeed(this, v)
            speedText.text = getString(R.string.label_speed) + " %.2fx".format(v)
        })
        speedText.text = getString(R.string.label_speed) + " %.2fx".format(LocalPrefs.speed(this))

        threadBar.progress = LocalPrefs.numThreads(this) - 1
        threadBar.setOnSeekBarChangeListener(simpleSeek { p ->
            LocalPrefs.setNumThreads(this, p + 1)
            threadText.text = getString(R.string.label_threads) + " ${p + 1}"
        })
        threadText.text = getString(R.string.label_threads) + " ${LocalPrefs.numThreads(this)}"
    }

    private fun bindUi() {
        val backend = LocalPrefs.backend(this)
        backendGroup.check(
            if (backend == LocalPrefs.Backend.SYSTEM.id) R.id.radioSystem else R.id.radioSherpa
        )
        val spec = ModelCatalog.byId(selectedModelId)
        modelLabel.text = spec.label
        modelDesc.text = spec.desc
        modelStatus.text = ModelDownloader.status(this, spec)
        editCustomUrl.setText(LocalPrefs.customUrl(this, spec.id))
        val pool = LocalVoice.poolForModel(spec.id)
        narratorLabel.text = LocalVoice.byId(pool, LocalPrefs.narratorVoice(this)).label
        progressBar.visibility = View.GONE
        progressText.text = ""
    }

    private fun pickModel() {
        val items = ModelCatalog.ALL.map {
            "${it.label}\n${ModelDownloader.status(this, it)}"
        }.toTypedArray()
        val ids = ModelCatalog.ALL.map { it.id }
        val current = ids.indexOf(selectedModelId).coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle(R.string.label_model)
            .setSingleChoiceItems(items, current) { d, which ->
                selectedModelId = ids[which]
                LocalPrefs.setModelId(this, selectedModelId)
                bindUi()
                d.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun openTtsSettings() {
        val candidates = listOf(
            Intent(ACTION_TTS_SETTINGS),
            Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS),
            Intent(android.provider.Settings.ACTION_SETTINGS),
        )
        for (i in candidates) {
            try {
                startActivity(i)
                return
            } catch (_: android.content.ActivityNotFoundException) {
            }
        }
        Toast.makeText(
            this,
            "请到 系统设置 → 无障碍/语言 → 文字转语音，选择「书声本地」",
            Toast.LENGTH_LONG
        ).show()
    }

    private fun downloadModel() {
        if (downloading) {
            Toast.makeText(this, "正在安装中，请稍候", Toast.LENGTH_SHORT).show()
            return
        }
        val spec = ModelCatalog.byId(selectedModelId)
        val custom = editCustomUrl.text?.toString().orEmpty()
        LocalPrefs.setCustomUrl(this, spec.id, custom)
        downloading = true
        progressBar.visibility = View.VISIBLE
        progressBar.isIndeterminate = false
        progressBar.progress = 0
        progressText.text = "开始…"
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    ModelDownloader.install(this@LocalSettingsActivity, spec, custom) { p, msg ->
                        runOnUiThread {
                            progressBar.progress = (p * 100).toInt().coerceIn(0, 100)
                            progressText.text = msg
                        }
                    }
                }
                Toast.makeText(this@LocalSettingsActivity, "模型已就绪", Toast.LENGTH_SHORT).show()
            } catch (t: Throwable) {
                android.util.Log.e("LocalSettings", "install failed", t)
                Toast.makeText(
                    this@LocalSettingsActivity,
                    "失败：${t.message}",
                    Toast.LENGTH_LONG
                ).show()
                progressText.text = "错误：${t.message}"
            } finally {
                downloading = false
                bindUi()
            }
        }
    }

    private fun deleteModel() {
        if (downloading) {
            Toast.makeText(this, "安装中无法删除", Toast.LENGTH_SHORT).show()
            return
        }
        val spec = ModelCatalog.byId(selectedModelId)
        AlertDialog.Builder(this)
            .setTitle(R.string.btn_delete)
            .setMessage(spec.label)
            .setPositiveButton(R.string.btn_delete) { _, _ ->
                lifecycleScope.launch(Dispatchers.IO) {
                    ModelDownloader.delete(this@LocalSettingsActivity, spec)
                    withContext(Dispatchers.Main) {
                        LocalPrefs.setBackend(this@LocalSettingsActivity, LocalPrefs.backend(this@LocalSettingsActivity))
                        bindUi()
                        Toast.makeText(this@LocalSettingsActivity, "已删除", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun pickNarrator() {
        val spec = ModelCatalog.byId(selectedModelId)
        val pool = LocalVoice.poolForModel(spec.id)
        // 男声在前，方便旁白选沉稳男声
        val ordered = pool.sortedWith(compareBy({ if (it.kind == "male") 0 else 1 }, { it.speakerId }))
        val names = ordered.map {
            val g = if (it.kind == "male") "男" else "女"
            "${it.label}  [$g · sid=${it.speakerId}]"
        }.toTypedArray()
        val current = ordered.indexOfFirst { it.id == LocalPrefs.narratorVoice(this) }.coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle("${getString(R.string.label_narrator)}（男声在前）")
            .setSingleChoiceItems(names, current) { d, which ->
                LocalPrefs.setNarratorVoice(this, ordered[which].id)
                bindUi()
                d.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun testSpeak() {
        val sample = "林晓站定，呼吸有些乱：“我答应过的事，就一定会做。”"
        Toast.makeText(this, "正在合成…", Toast.LENGTH_SHORT).show()
        tts?.shutdown()
        tts = android.speech.tts.TextToSpeech(
            this,
            { status ->
                if (status != android.speech.tts.TextToSpeech.SUCCESS) {
                    runOnUiThread {
                        Toast.makeText(this, "TTS 初始化失败 $status", Toast.LENGTH_LONG).show()
                    }
                    return@TextToSpeech
                }
                tts?.setLanguage(java.util.Locale.SIMPLIFIED_CHINESE)
                val rc = tts?.speak(
                    sample,
                    android.speech.tts.TextToSpeech.QUEUE_FLUSH,
                    null,
                    "local-test"
                )
                runOnUiThread {
                    Toast.makeText(this, "speak rc=$rc", Toast.LENGTH_SHORT).show()
                }
            },
            packageName
        )
    }

    override fun onDestroy() {
        tts?.shutdown()
        tts = null
        super.onDestroy()
    }

    private fun simpleSeek(onChange: (Int) -> Unit) = object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
            if (fromUser) onChange(progress)
        }
        override fun onStartTrackingTouch(sb: SeekBar?) {}
        override fun onStopTrackingTouch(sb: SeekBar?) {}
    }
}
