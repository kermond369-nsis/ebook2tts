package com.kermond.ebook2tts.ui

import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.tabs.TabLayout
import com.kermond.ebook2tts.core.ModelCatalog
import com.kermond.ebook2tts.core.VoiceCatalog
import com.kermond.ebook2tts.engine.ConfigStore
import com.kermond.ebook2tts.engine.DownloadService
import com.kermond.ebook2tts.MainActivity
import com.kermond.ebook2tts.R

class GuideFragment : Fragment() {
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val ctx = requireContext()
        val scroll = ScrollView(ctx)
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 24, 32, 32)
        }
        root.addView(TextView(ctx).apply {
            text = "三步开始听书"
            textSize = 22f
            setPadding(0, 0, 0, 16)
        })
        root.addView(stepCard("1. 下载模型", "首次使用请在「模型」页下载推荐模型（Kokoro int8）。下载完成后完全离线。"))
        root.addView(stepCard("2. 选择引擎", "系统设置 → 文字转语音 → 选择「书声本地 · 多角色有声」。"))
        root.addView(stepCard("3. 在阅读 App 朗读", "打开阅读/Legado，选择本引擎即可听书。杀掉本 App 不中断朗读。"))
        root.addView(stepCard("无障碍提示", getString(R.string.talkback_notice)))
        root.addView(MaterialButton(ctx).apply {
            text = "去下载模型"
            setOnClickListener {
                activity?.findViewById<TabLayout>(R.id.tabs)?.getTabAt(1)?.select()
            }
        })
        scroll.addView(root)
        return scroll
    }

    private fun stepCard(title: String, body: String): MaterialCardView {
        val ctx = requireContext()
        val card = MaterialCardView(ctx).apply {
            radius = 16f
            setContentPadding(24, 24, 24, 24)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 16 }
        }
        val box = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        box.addView(TextView(ctx).apply {
            text = title
            textSize = 18f
            setTextColor(ContextCompat.getColor(ctx, R.color.md_on_surface))
        })
        box.addView(TextView(ctx).apply {
            text = body
            textSize = 14f
            setPadding(0, 8, 0, 0)
            setTextColor(ContextCompat.getColor(ctx, R.color.md_secondary))
        })
        card.addView(box)
        return card
    }
}

class ModelFragment : Fragment() {
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val ctx = requireContext()
        val scroll = ScrollView(ctx)
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 16, 24, 24)
        }
        root.addView(TextView(ctx).apply {
            text = "模型管理"
            textSize = 20f
            setPadding(0, 0, 0, 12)
        })
        ModelCatalog.ALL.forEach { spec ->
            val card = MaterialCardView(ctx).apply {
                radius = 14f
                setContentPadding(20, 16, 20, 16)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = 12 }
            }
            val box = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
            val mb = spec.downloadBytes / 1_000_000
            box.addView(TextView(ctx).apply {
                text = buildString {
                    append(spec.label)
                    if (spec.recommended) append(" · 主推")
                    if (spec.lowEndDefault) append(" · 保底")
                }
                textSize = 16f
            })
            box.addView(TextView(ctx).apply {
                text = spec.desc + "\n下载 " + mb + "MB · 解压约 " +
                    (spec.extractedBytes / 1_000_000) + "MB"
                textSize = 13f
                setPadding(0, 6, 0, 8)
            })
            box.addView(MaterialButton(ctx).apply {
                text = "下载 / 安装"
                setOnClickListener {
                    DownloadService.start(ctx, spec.id)
                    (activity as? MainActivity)?.toast("已开始下载 " + spec.label)
                }
            })
            card.addView(box)
            root.addView(card)
        }
        scroll.addView(root)
        return scroll
    }
}

class VoicesFragment : Fragment() {
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val ctx = requireContext()
        val scroll = ScrollView(ctx)
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 16, 24, 24)
        }
        val pool = VoiceCatalog.poolForModel(ConfigStore.modelId())
        root.addView(TextView(ctx).apply {
            text = "音色库（" + pool.size + "）· 点选试听并设为旁白"
            textSize = 18f
            setPadding(0, 0, 0, 12)
        })
        val narrator = ConfigStore.narratorVoice()
        pool.forEach { v ->
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(4, 10, 4, 10)
            }
            row.addView(TextView(ctx).apply {
                text = v.displayName + "\n" + v.id
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                textSize = 13f
            })
            row.addView(MaterialButton(ctx).apply {
                text = "试听"
                setOnClickListener {
                    PreviewBridge.preview(ctx, v.id, "你好，这是「" + v.displayName + "」的试听。")
                }
            })
            row.addView(MaterialButton(ctx).apply {
                text = if (v.id == narrator) "旁白" else "设旁白"
                isEnabled = v.id != narrator
                setOnClickListener {
                    ConfigStore.setNarratorVoice(v.id)
                    ConfigStore.notifyReload(ctx, "narrator")
                    (activity as? MainActivity)?.toast("已设为旁白：" + v.displayName)
                    parentFragmentManager.beginTransaction()
                        .replace(R.id.container, VoicesFragment())
                        .commitAllowingStateLoss()
                }
            })
            root.addView(row)
        }
        scroll.addView(root)
        return scroll
    }
}

class SampleFragment : Fragment() {
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val ctx = requireContext()
        val scroll = ScrollView(ctx)
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 16, 24, 24)
        }
        root.addView(TextView(ctx).apply {
            text = "示例朗读（与引擎同管线）"
            textSize = 18f
        })
        val input = EditText(ctx).apply {
            setText(
                "第十二章 风起\n" +
                    "风从很远的地方吹过来，带着海的味道。\n" +
                    "他说：「今天天气不错。」\n" +
                    "她笑了笑：「是啊，我们走吧。」"
            )
            minLines = 6
            gravity = Gravity.TOP
        }
        root.addView(input)
        root.addView(MaterialButton(ctx).apply {
            text = "一键试听"
            setOnClickListener {
                val voice = ConfigStore.narratorVoice()
                PreviewBridge.preview(ctx, voice, input.text.toString())
                (activity as? MainActivity)?.toast("开始试听")
            }
        })
        root.addView(TextView(ctx).apply {
            text = "多角色拆解：旁白用默认音色；对白按角色分配。设置页可切换「尊重阅读器音色」。"
            textSize = 12f
            setPadding(0, 12, 0, 0)
        })
        scroll.addView(root)
        return scroll
    }
}

class SettingsFragment : Fragment() {
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val ctx = requireContext()
        val scroll = ScrollView(ctx)
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 16, 24, 24)
        }
        root.addView(TextView(ctx).apply {
            text = "设置（热生效）"
            textSize = 18f
        })
        val role = ConfigStore.roleMode()
        val roleGroup = RadioGroup(ctx)
        roleGroup.addView(RadioButton(ctx).apply {
            text = "智能多角色（默认）"
            isChecked = role == "smart"
            setOnClickListener {
                ConfigStore.setRoleMode("smart")
                ConfigStore.notifyReload(ctx, "role")
            }
        })
        roleGroup.addView(RadioButton(ctx).apply {
            text = "尊重阅读器音色（固定单音色）"
            isChecked = role == "respectReader"
            setOnClickListener {
                ConfigStore.setRoleMode("respectReader")
                ConfigStore.notifyReload(ctx, "role")
            }
        })
        root.addView(roleGroup)
        root.addView(TextView(ctx).apply {
            text = "推理线程数：" + ConfigStore.threads()
            setPadding(0, 16, 0, 0)
        })
        root.addView(SeekBar(ctx).apply {
            max = 3
            progress = ConfigStore.threads() - 1
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, u: Boolean) {
                    ConfigStore.setThreads(p + 1)
                }

                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {
                    ConfigStore.notifyReload(ctx, "threads")
                }
            })
        })
        root.addView(MaterialButton(ctx).apply {
            text = "一键重置角色库"
            setOnClickListener {
                ConfigStore.notifyReload(ctx, "reset_roles")
                (activity as? MainActivity)?.toast("角色库已请求重置")
            }
        })
        root.addView(MaterialButton(ctx).apply {
            text = "立即重新加载引擎"
            setOnClickListener {
                ConfigStore.notifyReload(ctx, "manual")
                (activity as? MainActivity)?.toast("将在当前句结束后生效")
            }
        })
        scroll.addView(root)
        return scroll
    }
}

class DiagnoseFragment : Fragment() {
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val ctx = requireContext()
        val scroll = ScrollView(ctx)
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 16, 24, 24)
        }
        root.addView(TextView(ctx).apply {
            text = buildString {
                appendLine("引擎状态：" + ConfigStore.statusState())
                appendLine("当前模型：" + ConfigStore.statusModelId())
                appendLine("最近错误：" + (ConfigStore.statusLastError().ifEmpty { "无" }))
                appendLine("DROP_UNIT：" + ConfigStore.dropUnitCount())
                appendLine("旁白：" + ConfigStore.narratorVoice())
                appendLine("角色模式：" + ConfigStore.roleMode())
            }
            textSize = 14f
        })
        root.addView(MaterialButton(ctx).apply {
            text = "刷新状态"
            setOnClickListener {
                parentFragmentManager.beginTransaction()
                    .replace(R.id.container, DiagnoseFragment())
                    .commitAllowingStateLoss()
            }
        })
        scroll.addView(root)
        return scroll
    }
}
