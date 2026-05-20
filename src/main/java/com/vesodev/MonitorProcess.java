package com.vesodev;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.util.function.Consumer;

public class MonitorProcess {
    private Process process;
    private Thread readerThread;
    private volatile boolean running = false;

    public synchronized void start(String[] command, String workingDirectory, Consumer<String> onLine) throws Exception {
        if (running) return;
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(true);
        if (workingDirectory != null && !workingDirectory.isEmpty()) {
            File dir = new File(workingDirectory);
            if (!dir.exists() || !dir.isDirectory()) {
                throw new IllegalArgumentException("Working directory does not exist or is not a directory: " + workingDirectory);
            }
            pb.directory(dir);
        }
        process = pb.start();
        running = true;

        readerThread = new Thread(() -> {
            try (BufferedReader r = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while (running && (line = r.readLine()) != null) {
                    onLine.accept(line);
                }
            } catch (Exception ignored) {
            } finally {
                stop();
            }
        }, "MonitorProcess-Reader");
        readerThread.setDaemon(true);
        readerThread.start();
    }

    public synchronized void stop() {
        running = false;
        if (process != null) {
            try {
                process.destroy();
            } catch (Exception ignored) {
            }
            try {
                process.waitFor();
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            process = null;
        }
        if (readerThread != null) {
            readerThread.interrupt();
            readerThread = null;
        }
    }

    public boolean isRunning() {
        return running;
    }
}
