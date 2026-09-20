package com.mharness.workspace;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 读写真文本：识别 BOM，UTF-8 失败时回退 GB18030，并保留原文件的换行风格。
 * Windows 上 GBK 源文件和 CRLF 配 LF 的 {@code old_string} 是最常见的编辑失败原因。
 */
public final class TextFiles {
    public static final Charset GB18030 = Charset.forName("GB18030");

    private TextFiles() {
    }

    public record Loaded(String text, Charset charset, String eol, boolean bom) {
        /** 把正文归一成 {@code \n}，供搜索 / 展示。 */
        public String withLf() {
            return normalizeLf(text);
        }

        /** 把已按 {@code \n} 编辑过的正文写回原换行风格。 */
        public String restoreEol(String lfText) {
            String body = lfText == null ? "" : lfText;
            if ("\r\n".equals(eol)) {
                return body.replace("\n", "\r\n");
            }
            if ("\r".equals(eol)) {
                return body.replace("\n", "\r");
            }
            return body;
        }
    }

    public static Loaded read(Path file) throws IOException {
        byte[] raw = Files.readAllBytes(file);
        boolean bom = startsWithUtf8Bom(raw);
        byte[] payload = bom ? slice(raw, 3) : raw;
        Charset charset = decodeUtf8OrFallback(payload);
        String text = new String(payload, charset);
        return new Loaded(text, charset, detectEol(text), bom);
    }

    public static void write(Path file, Loaded original, String lfText) throws IOException {
        String body = original.restoreEol(lfText);
        byte[] content = body.getBytes(original.charset());
        if (original.bom() && original.charset().equals(StandardCharsets.UTF_8)) {
            byte[] withBom = new byte[3 + content.length];
            withBom[0] = (byte) 0xEF;
            withBom[1] = (byte) 0xBB;
            withBom[2] = (byte) 0xBF;
            System.arraycopy(content, 0, withBom, 3, content.length);
            Files.write(file, withBom);
            return;
        }
        Files.write(file, content);
    }

    public static void writeNew(Path file, String text) throws IOException {
        Files.writeString(file, text == null ? "" : text, StandardCharsets.UTF_8);
    }

    public static String normalizeLf(String text) {
        if (text == null || text.isEmpty()) {
            return text == null ? "" : text;
        }
        return text.replace("\r\n", "\n").replace('\r', '\n');
    }

    static String detectEol(String text) {
        if (text.contains("\r\n")) {
            return "\r\n";
        }
        if (text.contains("\r")) {
            return "\r";
        }
        return "\n";
    }

    private static boolean startsWithUtf8Bom(byte[] raw) {
        return raw.length >= 3
                && (raw[0] & 0xFF) == 0xEF
                && (raw[1] & 0xFF) == 0xBB
                && (raw[2] & 0xFF) == 0xBF;
    }

    private static byte[] slice(byte[] raw, int offset) {
        byte[] next = new byte[raw.length - offset];
        System.arraycopy(raw, offset, next, 0, next.length);
        return next;
    }

    private static Charset decodeUtf8OrFallback(byte[] payload) {
        if (canDecode(payload, StandardCharsets.UTF_8)) {
            return StandardCharsets.UTF_8;
        }
        if (canDecode(payload, GB18030)) {
            return GB18030;
        }
        return StandardCharsets.UTF_8;
    }

    private static boolean canDecode(byte[] payload, Charset charset) {
        CharsetDecoder decoder = charset.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        try {
            decoder.decode(ByteBuffer.wrap(payload));
            return true;
        } catch (CharacterCodingException e) {
            return false;
        }
    }
}
