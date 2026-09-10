package com.mharness.permission;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;

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

    private static String abbreviate(String arguments) {
        if (arguments == null) {
            return "";
        }
        String compact = arguments.replaceAll("\\s+", " ");
        return compact.length() <= 500 ? compact : compact.substring(0, 500) + "...";
    }
}
