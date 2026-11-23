package com.kermond.ebook2tts.engine;

import kotlin.jvm.functions.Function1;

/**
 * 显式命名的 JNI 音频回调类，配合 consumer-rules.pro 确保在开启 R8 压缩/混淆的 Release 包中
 * {@code sherpa-onnx} native 层反射查找的 {@code invoke([F)Ljava/lang/Integer;} 方法签名绝不被改名或内联消除。
 */
public final class SherpaAudioCallback implements Function1<float[], Integer> {
    private final Function1<? super float[], Integer> handler;
    private volatile boolean invoked = false;

    public SherpaAudioCallback(Function1<? super float[], Integer> handler) {
        this.handler = handler;
    }

    public boolean wasInvoked() {
        return invoked;
    }

    @Override
    public Integer invoke(float[] samples) {
        invoked = true;
        if (samples == null) {
            return 1;
        }
        Integer ret = handler.invoke(samples);
        return ret != null ? ret : 1;
    }
}
