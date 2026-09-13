package com.mharness.permission;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;

/**
 * 在终端打印工具名和参数摘要，等待用户输入 y/yes。
 * 其它输入、空行或读 stdin 失败都视为拒绝。
 */
public final class ConsoleApprovalService implements ApprovalService {
    @Override
    public boolean approve(String toolName, String arguments) {
        System.out.printf("%n批准执行工具 %s ? [y/N]%n%s%n", toolName, abbreviate(arguments));
        System.out.flush();
        try {
            BufferedReader reader = new BufferedReader(new InputStreamReader(System.in, Charset.defaultCharset()));
            String line = reader.readLine();
            return line != null && (line.equalsIgnoreCase("y") || line.equalsIgnoreCase("yes"));
        } catch (IOException e) {
            return false;
        }
    }

    /** 压缩空白并截到 500 字符，避免把整份文件内容刷满终端。 */
    private static String abbreviate(String arguments) {
        if (arguments == null) {
            return "";
        }
        String compact = arguments.replaceAll("\\s+", " ");
        return compact.length() <= 500 ? compact : compact.substring(0, 500) + "...";
    }
}
