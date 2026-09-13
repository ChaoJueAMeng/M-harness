package com.mharness.server;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 线程安全的 SSE 事件缓冲。
 * 后连上的客户端也能从第 0 帧重放，避免错过已经发出的 token/approval。
 */
final class EventLog {
    private final List<String> frames = new ArrayList<>();
    private boolean finished;
    private final Object lock = new Object();

    /** 追加一条 {@code data: ...} 帧并唤醒正在 {@link #stream} 的线程。 */
    void emitJson(String json) {
        push("data: " + json + "\n\n");
    }

    /** 标记任务结束；stream 在发完已有帧后返回。 */
    void finish() {
        synchronized (lock) {
            finished = true;
            lock.notifyAll();
        }
    }

    /**
     * 阻塞写出后续帧。空闲超过 15 秒写一条 SSE 注释心跳，防止代理断开连接。
     */
    void stream(OutputStream os) throws IOException, InterruptedException {
        int index = 0;
        while (true) {
            String next = nextFrame(index);
            if (next == null) {
                return;
            }
            if (next.isEmpty()) {
                os.write(": ping\n\n".getBytes(StandardCharsets.UTF_8));
                os.flush();
                continue;
            }
            os.write(next.getBytes(StandardCharsets.UTF_8));
            os.flush();
            index++;
        }
    }

    private void push(String frame) {
        synchronized (lock) {
            if (finished) {
                return;
            }
            frames.add(frame);
            lock.notifyAll();
        }
    }

    /**
     * {@code null} 表示已结束；空字符串表示心跳；其它为第 {@code index} 帧。
     */
    private String nextFrame(int index) throws InterruptedException {
        synchronized (lock) {
            long deadline = System.currentTimeMillis() + 15_000;
            while (index >= frames.size() && !finished) {
                long wait = deadline - System.currentTimeMillis();
                if (wait <= 0) {
                    return "";
                }
                lock.wait(wait);
            }
            if (index < frames.size()) {
                return frames.get(index);
            }
            return null;
        }
    }
}
