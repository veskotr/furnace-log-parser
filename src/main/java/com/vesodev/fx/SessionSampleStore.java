package com.vesodev.fx;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

final class SessionSampleStore {
    private static final String POISON = "__STOP__";
    private static final int QUEUE_CAPACITY = 200_000;
    private static final DateTimeFormatter TS_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final Path storeDir;
    private final ArrayBlockingQueue<String> queue = new ArrayBlockingQueue<>(QUEUE_CAPACITY);
    private final AtomicLong writtenCount = new AtomicLong();
    private final AtomicLong droppedCount = new AtomicLong();

    private volatile boolean running;
    private volatile Path currentFile;
    private Thread writerThread;

    SessionSampleStore(Path storeDir) {
        this.storeDir = storeDir;
    }

    synchronized void startNewSession() {
        close();
        try {
            Files.createDirectories(storeDir);
            String name = "furnace-session-" + LocalDateTime.now().format(TS_FORMAT) + ".csv";
            currentFile = storeDir.resolve(name);
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to initialize session store directory", ex);
        }

        running = true;
        writtenCount.set(0);
        droppedCount.set(0);
        queue.clear();

        writerThread = new Thread(this::writerLoop, "SessionSampleStore-Writer");
        writerThread.setDaemon(true);
        writerThread.start();

        enqueue("elapsed_ms,chart_x_ms,stage,phase,setpoint_c,pv_c,processed_avg_c,pid_err,pid_p,pid_i,pid_d,pid_out");
    }

    void appendLines(List<String> lines) {
        for (String line : lines) {
            enqueue(line);
        }
    }

    long getWrittenCount() {
        return writtenCount.get();
    }

    long getDroppedCount() {
        return droppedCount.get();
    }

    int getQueueSize() {
        return queue.size();
    }

    Path getCurrentFile() {
        return currentFile;
    }

    boolean awaitDrain(long timeoutMs) {
        long deadline = System.currentTimeMillis() + Math.max(1L, timeoutMs);
        while (System.currentTimeMillis() < deadline) {
            if (queue.isEmpty()) {
                return true;
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return queue.isEmpty();
    }

    synchronized void close() {
        if (!running) {
            return;
        }
        running = false;
        queue.offer(POISON);
        if (writerThread != null) {
            try {
                writerThread.join(3000);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            writerThread = null;
        }
        queue.clear();
    }

    private void enqueue(String line) {
        if (!running || line == null) {
            return;
        }
        if (!queue.offer(line)) {
            queue.poll();
            if (!queue.offer(line)) {
                droppedCount.incrementAndGet();
            }
        }
    }

    private void writerLoop() {
        try (BufferedWriter writer = Files.newBufferedWriter(
                currentFile,
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE)) {
            while (running || !queue.isEmpty()) {
                String line = queue.poll(500, TimeUnit.MILLISECONDS);
                if (line == null) {
                    continue;
                }
                if (POISON.equals(line)) {
                    break;
                }
                writer.write(line);
                writer.newLine();
                writtenCount.incrementAndGet();
            }
            writer.flush();
        } catch (Exception ex) {
            droppedCount.incrementAndGet();
        }
    }
}
