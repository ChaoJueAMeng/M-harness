package com.mharness.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mharness.config.HarnessLog;

import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Map;

/**
 * 桌面端配套的本机 HTTP 服务入口。
 * 只允许绑定 127.0.0.1 / localhost；启动后第一行 stdout 是含 port、token 的 JSON，供 WinUI 进程读取。
 */
public final class Main {
    public static void main(String[] args) throws Exception {
        String bind = "127.0.0.1";
        int port = 0;
        String token = randomToken();
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--bind" -> bind = requireValue(args, ++i, "--bind");
                case "--port" -> port = Integer.parseInt(requireValue(args, ++i, "--port"));
                case "--token" -> token = requireValue(args, ++i, "--token");
                default -> throw new IllegalArgumentException("未知参数: " + args[i]);
            }
        }
        if (!"127.0.0.1".equals(bind) && !"localhost".equals(bind)) {
            throw new IllegalArgumentException("只允许绑定 127.0.0.1");
        }
        try (HarnessHttpServer server = HarnessHttpServer.start(bind, port, token)) {
            String ready = new ObjectMapper().writeValueAsString(Map.of(
                    "port", server.port(),
                    "token", server.token()
            ));
            System.out.println(ready);
            System.out.flush();
            HarnessLog.info("本机服务已启动 port=" + server.port());
            // 一直阻塞主线程；窗口关闭时桌面会杀掉本进程。
            Thread.currentThread().join();
        }
    }

    /** 取出 flag 后面的值；缺值则抛错。 */
    private static String requireValue(String[] args, int index, String flag) {
        if (index >= args.length) {
            throw new IllegalArgumentException(flag + " 需要值");
        }
        return args[index];
    }

    /** 生成 24 字节随机 Bearer token（十六进制）。 */
    static String randomToken() {
        byte[] bytes = new byte[24];
        new SecureRandom().nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }
}
