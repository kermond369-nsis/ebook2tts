package com.kermond.ebook2tts.core

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
    val dictDir: String = "",
    val ruleFsts: String = "",
    val ruleFars: String = "",
    val vocoder: String = "",
    val vocoderUrl: String = "",
    val vocoderSha256: String = "",
    val vocoderBytes: Long = 0L,
    val lang: String = "",
    val tokens: String = "tokens.txt",
)

data class ModelSpec(
    val id: String,
    val label: String,
    val desc: String,
    val archiveName: String,
    val primaryUrl: String,
    val mirrorUrl: String,
    /** 官方镜像之外的附加源（自定义镜像 / 备用镜像，IM-201） */
    val extraMirrors: List<String> = emptyList(),
    /** 下载包实测字节（ERR-001） */
    val downloadBytes: Long,
    /** 解压后实测字节（ERR-001） */
    val extractedBytes: Long,
    val sha256: String,
    val files: ModelFiles,
    val recommended: Boolean = false,
    val highEndOnly: Boolean = false,
    val lowEndDefault: Boolean = false,
) {
    /** 参与多源测速/回退的全部下载源：官方主源 → 官方镜像 → 附加源（去重保序） */
    val sources: List<String>
        get() = (listOf(primaryUrl, mirrorUrl) + extraMirrors)
            .filter { it.isNotBlank() }
            .distinct()
}

object ModelCatalog {
    const val DEFAULT_ID = "kokoro-int8"

    /** 内置兜底清单版本（与仓库根 manifest.json 由单测绑定一致；远程清单版本更高时以远程为准） */
    const val BUILTIN_MANIFEST_VERSION: Long = 2026112301L
    const val BUILTIN_MANIFEST_UPDATED_AT: String = "2026-11-23"
    private const val GH =
        "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models"
    private const val GH_VOCODER =
        "https://github.com/k2-fsa/sherpa-onnx/releases/download/vocoder-models"

    /**
     * 国内可达的 GitHub 加速前缀（2026-11-23 逐源 Range 实测 206 可用；淘汰返回 403/500/超时的旧镜像）。
     */
    const val ACC_PRIMARY = "https://gh-proxy.com"
    const val ACC_ORG = "https://gh-proxy.org"
    const val ACC_EDGEONE = "https://edgeone.gh-proxy.org"
    const val ACC_CDN = "https://cdn.gh-proxy.org"

    val MIRROR_PREFIXES: List<String> = listOf(ACC_PRIMARY, ACC_ORG, ACC_EDGEONE, ACC_CDN)

    /** 加速镜像 URL：[prefix]/<官方下载 URL> */
    private fun acc(prefix: String, archive: String): String = "$prefix/$GH/$archive"

    val ALL: List<ModelSpec> = listOf(
        ModelSpec(
            id = "kokoro-int8",
            label = "Kokoro int8（主推）",
            desc = "中英多音色，103 音色，体积与质量最平衡。首次引导默认下载。",
            archiveName = "kokoro-int8-multi-lang-v1_1.tar.bz2",
            primaryUrl = GH + "/kokoro-int8-multi-lang-v1_1.tar.bz2",
            mirrorUrl = acc(ACC_PRIMARY, "kokoro-int8-multi-lang-v1_1.tar.bz2"),
            extraMirrors = listOf(
                acc(ACC_ORG, "kokoro-int8-multi-lang-v1_1.tar.bz2"),
                acc(ACC_EDGEONE, "kokoro-int8-multi-lang-v1_1.tar.bz2"),
                acc(ACC_CDN, "kokoro-int8-multi-lang-v1_1.tar.bz2"),
            ),
            downloadBytes = 147_052_523L,
            extractedBytes = 215_321_602L,
            sha256 = "259c9c8bc906d6078e4e0570421885dabb68b539c2f970fcbb890a58eeaf3e9c",
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
            id = "matcha-zh-en",
            label = "Matcha 中英轻量（流畅推荐）",
            desc = "中英混排 Flow-Matching 端侧模型（配 Vocos 声码器），语调更自然、速度显著快于 Kokoro。",
            archiveName = "matcha-icefall-zh-en.tar.bz2",
            primaryUrl = GH + "/matcha-icefall-zh-en.tar.bz2",
            mirrorUrl = acc(ACC_PRIMARY, "matcha-icefall-zh-en.tar.bz2"),
            extraMirrors = listOf(
                acc(ACC_ORG, "matcha-icefall-zh-en.tar.bz2"),
                acc(ACC_EDGEONE, "matcha-icefall-zh-en.tar.bz2"),
                acc(ACC_CDN, "matcha-icefall-zh-en.tar.bz2"),
            ),
            downloadBytes = 79_033_838L,
            extractedBytes = 95_346_626L,
            sha256 = "271b804af570400d3bcdcb53bf6e53cc9f75180ee763b9f13eb5eaf2b0d086ef",
            files = ModelFiles(
                kind = "matcha",
                modelName = "model-steps-3.onnx",
                lexicon = "lexicon.txt",
                dataDir = "espeak-ng-data",
                ruleFsts = "phone-zh.fst,date-zh.fst,number-zh.fst",
                vocoder = "vocos-16khz-univ.onnx",
                vocoderUrl = "$GH_VOCODER/vocos-16khz-univ.onnx",
                vocoderSha256 = "b599142a1fb8ff03de3e84ac35ff537c619e56f4267a6fe894851a42844acf9e",
                vocoderBytes = 53_882_848L,
            ),
            lowEndDefault = true,
        ),
        ModelSpec(
            id = "vits-zh-ll",
            label = "VITS zh-ll（保底）",
            desc = "中文 5 音色，保底档（本地合成下限机型）默认推荐。实测约 119MB。",
            archiveName = "sherpa-onnx-vits-zh-ll.tar.bz2",
            primaryUrl = GH + "/sherpa-onnx-vits-zh-ll.tar.bz2",
            mirrorUrl = acc(ACC_PRIMARY, "sherpa-onnx-vits-zh-ll.tar.bz2"),
            extraMirrors = listOf(
                acc(ACC_ORG, "sherpa-onnx-vits-zh-ll.tar.bz2"),
                acc(ACC_EDGEONE, "sherpa-onnx-vits-zh-ll.tar.bz2"),
                acc(ACC_CDN, "sherpa-onnx-vits-zh-ll.tar.bz2"),
            ),
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
            mirrorUrl = acc(ACC_PRIMARY, "kokoro-multi-lang-v1_1.tar.bz2"),
            extraMirrors = listOf(
                acc(ACC_ORG, "kokoro-multi-lang-v1_1.tar.bz2"),
                acc(ACC_EDGEONE, "kokoro-multi-lang-v1_1.tar.bz2"),
                acc(ACC_CDN, "kokoro-multi-lang-v1_1.tar.bz2"),
            ),
            downloadBytes = 364_855_331L,
            extractedBytes = 426_654_376L,
            sha256 = "b78e1ac42b852e839cceaafbe88f66806bf5860992d1d7b9a3f2ad1198427aed",
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

    /**
     * 内置兜底清单（IM-201）：远程清单与缓存均不可用时使用。
     * 与仓库根 `manifest.json` 逐字段一致，由 `ManifestCodecTest` 断言防漂移。
     */
    fun manifest(): ModelManifest =
        ModelManifest(BUILTIN_MANIFEST_VERSION, BUILTIN_MANIFEST_UPDATED_AT, ALL)

    /**
     * 空间预检：可用空间 ≥ 2.5 × **实测**解压体积（RQ-206 / ERR-001）。
     * 新字节值为亿级，全程 Long（勿引入 int 字面量）。
     */
    fun requiredSpaceBytes(extractedBytes: Long): Long =
        (extractedBytes * ModelLimits.SPACE_FACTOR_NUM) / ModelLimits.SPACE_FACTOR_DEN

    fun hasEnoughSpace(extractedBytes: Long, availableBytes: Long): Boolean =
        availableBytes >= requiredSpaceBytes(extractedBytes)
}
