package com.mimo.ebook2tts.core

/**
 * 模型目录与实测体积（ERR-001 / RQ-203）。
 * 字节值必须使用 Long 字面量（勘误单工程提示）。
 */
data class ModelFiles(
    val kind: String,
    val modelName: String,
    val voices: String = "",
    val lexicon: String = "",
    val dataDir: String = "",
    val ruleFsts: String = "",
    val tokens: String = "tokens.txt",
)

data class ModelSpec(
    val id: String,
    val label: String,
    val desc: String,
    val archiveName: String,
    val primaryUrl: String,
    val mirrorUrl: String,
    /** 下载包实测字节（ERR-001） */
    val downloadBytes: Long,
    /** 解压后实测字节（ERR-001） */
    val extractedBytes: Long,
    val sha256: String,
    val files: ModelFiles,
    val recommended: Boolean = false,
    val highEndOnly: Boolean = false,
    val lowEndDefault: Boolean = false,
)

object ModelCatalog {
    const val DEFAULT_ID = "kokoro-int8"
    private const val GH =
        "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models"
    private const val HF =
        "https://hf-mirror.com/csukuangfj/sherpa-onnx-tts-models/resolve/main"

    val ALL: List<ModelSpec> = listOf(
        ModelSpec(
            id = "kokoro-int8",
            label = "Kokoro int8（主推）",
            desc = "中英多音色，103 音色，体积与质量最平衡。首次引导默认下载。",
            archiveName = "kokoro-int8-multi-lang-v1_1.tar.bz2",
            primaryUrl = GH + "/kokoro-int8-multi-lang-v1_1.tar.bz2",
            mirrorUrl = HF + "/kokoro-int8-multi-lang-v1_1.tar.bz2",
            downloadBytes = 147_031_220L,
            extractedBytes = 215_321_602L,
            sha256 = "a1e94694776049035c4f2c6529f003aaece993c76aae9a78995831c3c4dcafc6",
            files = ModelFiles(
                kind = "kokoro",
                modelName = "model.int8.onnx",
                voices = "voices.bin",
                lexicon = "lexicon-us-en.txt,lexicon-zh.txt",
                dataDir = "espeak-ng-data",
                ruleFsts = "phone-zh.fst,date-zh.fst,number-zh.fst",
            ),
            recommended = true,
        ),
        ModelSpec(
            id = "vits-zh-ll",
            label = "VITS zh-ll（保底）",
            desc = "中文 5 音色，低端机默认推荐。实测约 119MB。",
            archiveName = "sherpa-onnx-vits-zh-ll.tar.bz2",
            primaryUrl = GH + "/sherpa-onnx-vits-zh-ll.tar.bz2",
            mirrorUrl = HF + "/sherpa-onnx-vits-zh-ll.tar.bz2",
            downloadBytes = 118_810_709L,
            extractedBytes = 135_457_418L,
            sha256 = "f7393d1bbc59709d5f52ea76cd5bceeec62f6a29f9c1f79ff64b4c15ec841f3a",
            files = ModelFiles(
                kind = "vits",
                modelName = "model.onnx",
                lexicon = "lexicon.txt",
                ruleFsts = "phone.fst,date.fst,number.fst",
            ),
            lowEndDefault = true,
        ),
        ModelSpec(
            id = "kokoro-fp32",
            label = "Kokoro fp32（高端可选）",
            desc = "更高音质，约 365MB 下载 / 427MB 解压。",
            archiveName = "kokoro-multi-lang-v1_1.tar.bz2",
            primaryUrl = GH + "/kokoro-multi-lang-v1_1.tar.bz2",
            mirrorUrl = HF + "/kokoro-multi-lang-v1_1.tar.bz2",
            downloadBytes = 364_816_464L,
            extractedBytes = 426_654_376L,
            sha256 = "a3f4c73d043860e3fd2e5b06f36795eb81de0fc8e8de6df703245edddd87dbad",
            files = ModelFiles(
                kind = "kokoro",
                modelName = "model.onnx",
                voices = "voices.bin",
                lexicon = "lexicon-us-en.txt,lexicon-zh.txt",
                dataDir = "espeak-ng-data",
                ruleFsts = "phone-zh.fst,date-zh.fst,number-zh.fst",
            ),
            highEndOnly = true,
        ),
    )

    fun byId(id: String?): ModelSpec = ALL.firstOrNull { it.id == id } ?: ALL.first()

    /** 空间预检：可用 ≥ 2.5× 实测解压体积（Long） */
    fun requiredSpaceBytes(extractedBytes: Long): Long = (extractedBytes * 25L) / 10L

    fun hasEnoughSpace(extractedBytes: Long, availableBytes: Long): Boolean =
        availableBytes >= requiredSpaceBytes(extractedBytes)
}
