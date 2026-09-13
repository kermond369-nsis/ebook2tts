package com.mimo.ebook2tts.local.ui

import android.content.Intent
import android.os.Bundle
import android.widget.Button
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
    private lateinit var modelStatus: TextView
    private lateinit var narratorLabel: TextView
    private lateinit var progressText: TextView
    private lateinit var progressBar: SeekBar
    private lateinit var speedText: TextView
    private lateinit var speedBar: SeekBar
    private lateinit var threadText: TextView
    private lateinit var threadBar: SeekBar

    private var selectedModelId: String = ""
    private var downloading = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_local_settings)

        backendGroup = findViewById(R.id.backendGroup)
        modelLabel = findViewById(R.id.modelLabel)
        modelStatus = findViewById(R.id.modelStatus)
        narratorLabel = findViewById(R.id.narratorLabel)
        progressText = findViewById(R.id.progressText)
        progressBar = findViewById(R.id.progressBar)
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
        findViewById<Button>(R.id.btnOpenTtsSettings).setOnClickListener {
            startActivity(Intent(ACTION_TTS_SETTINGS))
        }
        findViewById<Button>(R.id.btnTest).setOnClickListener { testSpeak() }

        backendGroup.setOnCheckedChangeListener { _, checkedId ->
            val backend = if (checkedId == R.id.radioSystem) {
                LocalPrefs.Backend.SYSTEM.id
            } else {
                LocalPrefs.Backend.SHERPA.id
            }
            LocalPrefs.setBackend(this, backend)
            Toast.makeText(this, "后端已切换", Toast.LENGTH_SHORT).show()
        }

        speedBar.progress = ((LocalPrefs.speed(this) - 0.5f) / 1.5f * 100).toInt()
        speedBar.setOnSeekBarChangeListener(simpleSeek { p ->
            val v = 0.5f + p / 100f * 1.5f
            LocalPrefs.setSpeed(this, v)
            speedText.text = "语速 %.2fx".format(v)
        })
        speedText.text = "语速 %.2fx".format(LocalPrefs.speed(this))

        threadBar.progress = LocalPrefs.numThreads(this) - 1
        threadBar.setOnSeekBarChangeListener(simpleSeek { p ->
            LocalPrefs.setNumThreads(this, p + 1)
            threadText.text = "线程 ${p + 1}"
        })
        threadText.text = "线程 ${LocalPrefs.numThreads(this)}"
    }

    private fun bindUi() {
        val backend = LocalPrefs.backend(this)
        backendGroup.check(
            if (backend == LocalPrefs.Backend.SYSTEM.id) R.id.radioSystem else R.id.radioSherpa
        )
        val spec = ModelCatalog.byId(selectedModelId)
        modelLabel.text = "${spec.label}\n${spec.desc}"
        modelStatus.text = ModelDownloader.status(this, spec)
        narratorLabel.text = "旁白音色：${LocalVoice.byId(LocalVoice.poolForModel(spec.id), LocalPrefs.narratorVoice(this)).label}"
        progressBar.progress = 0
        progressText.text = ""
    }

    private fun pickModel() {
        val items = ModelCatalog.ALL.map { "${it.label}\n${ModelDownloader.status(this, it)}" }.toTypedArray()
        val ids = ModelCatalog.ALL.map { it.id }
        val current = ids.indexOf(selectedModelId).coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle("选择本地模型")
            .setSingleChoiceItems(items, current) { d, which ->
                selectedModelId = ids[which]
                LocalPrefs.setModelId(this, selectedModelId)
                bindUi()
                d.dismiss()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun downloadModel() {
        if (downloading) return
        val spec = ModelCatalog.byId(selectedModelId)
        downloading = true
        progressBar.progress = 0
        progressText.text = "准备下载…"
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    ModelDownloader.download(this@LocalSettingsActivity, spec) { p, msg ->
                        runOnUiThread {
                            progressBar.progress = (p * 100).toInt()
                            progressText.text = msg
                        }
                    }
                }
                Toast.makeText(this@LocalSettingsActivity, "模型就绪", Toast.LENGTH_SHORT).show()
            } catch (t: Throwable) {
                Toast.makeText(
                    this@LocalSettingsActivity,
                    "下载失败：${t.message}",
                    Toast.LENGTH_LONG
                ).show()
            } finally {
                downloading = false
                bindUi()
            }
        }
    }

    private fun deleteModel() {
        val spec = ModelCatalog.byId(selectedModelId)
        AlertDialog.Builder(this)
            .setTitle("删除模型")
            .setMessage("删除 ${spec.label}？")
            .setPositiveButton("删除") { _, _ ->
                lifecycleScope.launch(Dispatchers.IO) {
                    ModelDownloader.delete(this@LocalSettingsActivity, spec)
                    withContext(Dispatchers.Main) { bindUi() }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun pickNarrator() {
        val spec = ModelCatalog.byId(selectedModelId)
        val pool = LocalVoice.poolForModel(spec.id)
        val names = pool.map { "${it.label}（${it.kind}）" }.toTypedArray()
        val current = pool.indexOfFirst { it.id == LocalPrefs.narratorVoice(this) }.coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle("旁白音色")
            .setSingleChoiceItems(names, current) { d, which ->
                LocalPrefs.setNarratorVoice(this, pool[which].id)
                bindUi()
                d.dismiss()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private var tts: android.speech.tts.TextToSpeech? = null

    private fun testSpeak() {
        val sample = "书声本地测试。林晓站定，说道：“我答应过的事，就一定会做。”" +
            "周远靠在墙边：“你终于来了。”"
        Toast.makeText(this, "正在合成…", Toast.LENGTH_SHORT).show()
        tts?.shutdown()
        tts = android.speech.tts.TextToSpeech(
            this,
            { status ->
                if (status != android.speech.tts.TextToSpeech.SUCCESS) {
                    runOnUiThread {
                        Toast.makeText(this, "TTS 初始化失败 status=$status", Toast.LENGTH_LONG).show()
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
