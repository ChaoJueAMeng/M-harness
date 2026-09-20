package com.mharness.context;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TokenEstimatorTest {
    @Test
    void latinIsAboutFourCharsPerToken() {
        assertThat(TokenEstimator.estimate("abcd")).isEqualTo(1);
        assertThat(TokenEstimator.estimate("abcdefgh")).isEqualTo(2);
    }

    @Test
    void cjkCountsAsOneTokenEach() {
        assertThat(TokenEstimator.estimate("中文测试")).isEqualTo(4);
        assertThat(TokenEstimator.estimate("中文abcd")).isEqualTo(3);
    }

    @Test
    void truncateKeepsPrefixUnderBudget() {
        String text = "中".repeat(50);
        String clipped = TokenEstimator.truncate(text, 10);
        assertThat(TokenEstimator.estimate(clipped)).isLessThanOrEqualTo(10);
        assertThat(clipped).contains("truncated");
    }
}
