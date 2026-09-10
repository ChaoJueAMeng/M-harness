package com.mharness.llm;

@FunctionalInterface
public interface TokenListener {
    void onToken(String token);
}
