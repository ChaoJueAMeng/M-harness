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
        assertThat(HardDeny.matches("git log --format=oneline")).isFalse();
        assertThat(HardDeny.matches("dotnet format")).isFalse();
        assertThat(HardDeny.matches("rd /s /q build")).isTrue();
        assertThat(HardDeny.matches("Remove-Item -Recurse -Force tmp")).isTrue();
        assertThat(HardDeny.matches("format C:")).isTrue();
    }
}
