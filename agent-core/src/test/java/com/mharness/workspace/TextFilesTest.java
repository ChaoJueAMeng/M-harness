package com.mharness.workspace;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class TextFilesTest {
    @TempDir
    Path dir;

    @Test
    void readsUtf8BomAndPreservesCrlf() throws Exception {
        Path file = dir.resolve("a.txt");
        byte[] bom = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
        byte[] body = "hello\r\nworld\r\n".getBytes(StandardCharsets.UTF_8);
        byte[] raw = new byte[bom.length + body.length];
        System.arraycopy(bom, 0, raw, 0, bom.length);
        System.arraycopy(body, 0, raw, bom.length, body.length);
        Files.write(file, raw);

        TextFiles.Loaded loaded = TextFiles.read(file);
        assertThat(loaded.bom()).isTrue();
        assertThat(loaded.eol()).isEqualTo("\r\n");
        assertThat(loaded.withLf()).isEqualTo("hello\nworld\n");

        TextFiles.write(file, loaded, "hello\nchanged\n");
        byte[] written = Files.readAllBytes(file);
        assertThat(written[0] & 0xFF).isEqualTo(0xEF);
        assertThat(new String(written, 3, written.length - 3, StandardCharsets.UTF_8)).isEqualTo("hello\r\nchanged\r\n");
    }

    @Test
    void readsGb18030WhenNotUtf8() throws Exception {
        Path file = dir.resolve("gb.txt");
        Files.write(file, "中文".getBytes(TextFiles.GB18030));
        TextFiles.Loaded loaded = TextFiles.read(file);
        assertThat(loaded.charset()).isEqualTo(TextFiles.GB18030);
        assertThat(loaded.text()).isEqualTo("中文");
    }
}
