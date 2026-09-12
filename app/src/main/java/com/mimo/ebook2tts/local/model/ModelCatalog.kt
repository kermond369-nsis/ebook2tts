package com.mimo.ebook2tts.local.model

/** 可下载的本地 TTS 模型。参数量均 ≤ 1.5B。 */
data class ModelSpec(
    val id: String,
    val label: String,
    val desc: String,
    val dirName: String,
    val archiveName: String,
    val primaryUrl: String,
    val mirrorUrl: String,
    val modelName: String,
    val voices: String = "",
    val lexicon: String = "",
    val dataDir: String = "",
    val ruleFsts: String = "",
    val ruleFars: String = "",
    val tokens: String = "tokens.txt",
    val approxBytes: Long,
    val kind: String, // kokoro | vits
)

object ModelCatalog {

    const val DEFAULT_ID = "kokoro-int8-multi-lang-v1_1"

    private val GH = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models"
    private val HF = "https://hf-mirror.com/csukuangfj/sherpa-onnx-tts-models/resolve/main"

    val ALL: List<ModelSpec> = listOf(
        ModelSpec(
            id = "kokoro-int8-multi-lang-v1_1",
            label = "Kokoro int8 · 中英多音色",
            desc = "约 82M 参数（int8），中文多音色。质量/体积最佳，推荐。",
            dirName = "kokoro-int8-multi-lang-v1_1",
            archiveName = "kokoro-int8-multi-lang-v1_1.tar.bz2",
            primaryUrl = "$GH/kokoro-int8-multi-lang-v1_1.tar.bz2",
            mirrorUrl = "$HF/kokoro-int8-multi-lang-v1_1.tar.bz2",
            modelName = "model.int8.onnx",
            voices = "voices.bin",
            lexicon = "lexicon-us-en.txt,lexicon-zh.txt",
            dataDir = "espeak-ng-data",
            ruleFsts = "phone-zh.fst,date-zh.fst,number-zh.fst",
            approxBytes = 90L * 1024 * 1024,
            kind = "kokoro",
        ),
        ModelSpec(
            id = "kokoro-multi-lang-v1_1",
            label = "Kokoro fp32 · 中英多音色",
            desc = "约 82M 参数（无量化），音质略优，体积更大。中端以上手机。",
            dirName = "kokoro-multi-lang-v1_1",
            archiveName = "kokoro-multi-lang-v1_1.tar.bz2",
            primaryUrl = "$GH/kokoro-multi-lang-v1_1.tar.bz2",
            mirrorUrl = "$HF/kokoro-multi-lang-v1_1.tar.bz2",
            modelName = "model.onnx",
            voices = "voices.bin",
            lexicon = "lexicon-us-en.txt,lexicon-zh.txt",
            dataDir = "espeak-ng-data",
            ruleFsts = "phone-zh.fst,date-zh.fst,number-zh.fst",
            approxBytes = 310L * 1024 * 1024,
            kind = "kokoro",
        ),
        ModelSpec(
            id = "sherpa-onnx-vits-zh-ll",
            label = "VITS 中文 · 5 音色",
            desc = "极小体积，5 个中文音色。低端机首选。",
            dirName = "sherpa-onnx-vits-zh-ll",
            archiveName = "sherpa-onnx-vits-zh-ll.tar.bz2",
            primaryUrl = "$GH/sherpa-onnx-vits-zh-ll.tar.bz2",
            mirrorUrl = "$HF/sherpa-onnx-vits-zh-ll.tar.bz2",
            modelName = "model.onnx",
            lexicon = "lexicon.txt",
            ruleFsts = "phone.fst,date.fst,number.fst",
            approxBytes = 40L * 1024 * 1024,
            kind = "vits",
        ),
        ModelSpec(
            id = "vits-zh-hf-fanchen-C",
            label = "VITS fanchen-C · 187 音色",
            desc = "187 个中文说话人，音色极多，质量参差。",
            dirName = "vits-zh-hf-fanchen-C",
            archiveName = "vits-zh-hf-fanchen-C.tar.bz2",
            primaryUrl = "$GH/vits-zh-hf-fanchen-C.tar.bz2",
            mirrorUrl = "$HF/vits-zh-hf-fanchen-C.tar.bz2",
            modelName = "vits-zh-hf-fanchen-C.onnx",
            ruleFsts = "phone.fst,date.fst",
            approxBytes = 120L * 1024 * 1024,
            kind = "vits",
        ),
        ModelSpec(
            id = "vits-melo-tts-zh_en",
            label = "MeloTTS 中英 · 1 音色",
            desc = "中英混合单音色，无多角色切换。",
            dirName = "vits-melo-tts-zh_en",
            archiveName = "vits-melo-tts-zh_en.tar.bz2",
            primaryUrl = "$GH/vits-melo-tts-zh_en.tar.bz2",
            mirrorUrl = "$HF/vits-melo-tts-zh_en.tar.bz2",
            modelName = "model.onnx",
            ruleFsts = "phone.fst,date.fst,number.fst",
            approxBytes = 110L * 1024 * 1024,
            kind = "vits",
        ),
    )

    fun byId(id: String?): ModelSpec =
        ALL.firstOrNull { it.id == id } ?: ALL.first()
}
