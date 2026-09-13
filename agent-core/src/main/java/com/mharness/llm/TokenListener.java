package com.mharness.llm;

/**
 * 流式输出的单个 token/片段回调。CLI 直接打印，HTTP 服务推 SSE {@code token} 事件。
 */
@FunctionalInterface
public interface TokenListener {
    void onToken(String token);
}
