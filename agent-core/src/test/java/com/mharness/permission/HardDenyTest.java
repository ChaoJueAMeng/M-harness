package com.mharness.permission;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class HardDenyTest {
    @Test
    void deniesForcePushAndHardReset() {
        assertThat(HardDeny.matches("git push --force origin main")).isTrue();
        assertThat(HardDeny.matches("git reset --hard HEAD")).isTrue();
        assertThat(HardDeny.matches("git clean -fd")).isTrue();
        assertThat(HardDeny.matches("shutdown /s")).isTrue();
        assertThat(HardDeny.matches("rm -rf /")).isTrue();
        assertThat(HardDeny.matches("mvn test")).isFalse();
        assertThat(HardDeny.matches("git status")).isFalse();
    }
}
