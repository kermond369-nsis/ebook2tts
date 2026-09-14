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
    const val BUILTIN_MANIFEST_VERSION: Long = 2026091301L
    const val BUILTIN_MANIFEST_UPDATED_AT: String = "2026-09-13"
    private const val GH =
        "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models"

    /**
     * 国内可达的 GitHub 加速前缀（2026-09-14 逐源实测，替代此前失效的 hf-mirror）。
     *
     * 为什么弃用 hf-mirror：HuggingFace 上的 `csukuangfj/<模型>` 仓库存的是**解压后的文件**，
     * 不提供 `.tar.bz2` 归档，按归档路径请求必然 404；本项目下载/解压流程依赖归档包，故不可用。
     *
     * 实测（国内直连、无代理、1MB Range 实拉）：ghfast.top 216–358 KB/s 且返回
     * `Content-Range`（支持断点续传）；hk.gh-proxy.com 302 跳转后可用；ghproxy.net 约 28 KB/s（保底）。
     */
    private const val ACC_FAST = "https://ghfast.top"
    private const val ACC_HK = "https://hk.gh-proxy.com"
    private const val ACC_SLOW = "https://ghproxy.net"

    /** 加速镜像 URL：[prefix]/<官方下载 URL> */
    private fun acc(prefix: String, archive: String): String = "$prefix/$GH/$archive"

    val ALL: List<ModelSpec> = listOf(
        ModelSpec(
            id = "kokoro-int8",
            label = "Kokoro int8（主推）",
            desc = "中英多音色，103 音色，体积与质量最平衡。首次引导默认下载。",
            archiveName = "kokoro-int8-multi-lang-v1_1.tar.bz2",
            primaryUrl = GH + "/kokoro-int8-multi-lang-v1_1.tar.bz2",
            mirrorUrl = acc(ACC_FAST, "kokoro-int8-multi-lang-v1_1.tar.bz2"),
            extraMirrors = listOf(
                acc(ACC_HK, "kokoro-int8-multi-lang-v1_1.tar.bz2"),
                acc(ACC_SLOW, "kokoro-int8-multi-lang-v1_1.tar.bz2"),
            ),
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
            mirrorUrl = acc(ACC_FAST, "sherpa-onnx-vits-zh-ll.tar.bz2"),
            extraMirrors = listOf(
                acc(ACC_HK, "sherpa-onnx-vits-zh-ll.tar.bz2"),
                acc(ACC_SLOW, "sherpa-onnx-vits-zh-ll.tar.bz2"),
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
            mirrorUrl = acc(ACC_FAST, "kokoro-multi-lang-v1_1.tar.bz2"),
            extraMirrors = listOf(
                acc(ACC_HK, "kokoro-multi-lang-v1_1.tar.bz2"),
                acc(ACC_SLOW, "kokoro-multi-lang-v1_1.tar.bz2"),
            ),
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
