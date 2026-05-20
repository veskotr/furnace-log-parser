package com.vesodev;

import com.fazecast.jSerialComm.SerialPort;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

public class MonitorProcess {
    private static final Pattern IDF_COMMAND_PATTERN = Pattern.compile("(^|\\s)idf\\.py(?:\\.exe)?(\\s|$)", Pattern.CASE_INSENSITIVE);
    private static final Pattern IDF_MONITOR_PATTERN = Pattern.compile("(^|\\s)monitor(\\s|$)", Pattern.CASE_INSENSITIVE);
    private static final Pattern IDF_OTHER_ACTION_PATTERN = Pattern.compile("(^|\\s)(build|flash|erase-flash|fullclean|menuconfig|size|app|bootloader|partition-table)(\\s|$)", Pattern.CASE_INSENSITIVE);
    private static final Pattern PORT_OPTION_PATTERN = Pattern.compile("(?:^|\\s)(?:-p|--port)(?:\\s+|=)(\"[^\"]+\"|\\S+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern BAUD_OPTION_PATTERN = Pattern.compile("(?:^|\\s)(?:-b|--monitor-baud)(?:\\s+|=)(\\d+)", Pattern.CASE_INSENSITIVE);
    private static final int DEFAULT_BAUD_RATE = 115200;

    private Process process;
    private SerialPort serialPort;
    private Thread readerThread;
    private volatile boolean running = false;

    public synchronized void start(String command, String workingDirectory, Consumer<String> onLine) throws Exception {
        if (running) return;

        Optional<SerialMonitorConfig> serialMonitorConfig = tryParseSerialMonitorConfig(command);
        if (serialMonitorConfig.isPresent()) {
            startSerialMonitor(serialMonitorConfig.get(), onLine);
            return;
        }

        String effectiveCommand = prepareCommand(command);
        ProcessBuilder pb = new ProcessBuilder(buildCommand(effectiveCommand));
        pb.redirectErrorStream(true);
        configureEnvironment(pb, command);
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

    private void startSerialMonitor(SerialMonitorConfig config, Consumer<String> onLine) throws Exception {
        SerialPort port = Arrays.stream(SerialPort.getCommPorts())
                .filter(candidate -> candidate.getSystemPortName().equalsIgnoreCase(config.portName()))
                .findFirst()
                .orElseGet(() -> SerialPort.getCommPort(config.portName()));

        port.setComPortParameters(config.baudRate(), 8, SerialPort.ONE_STOP_BIT, SerialPort.NO_PARITY);
        port.setComPortTimeouts(SerialPort.TIMEOUT_READ_SEMI_BLOCKING, 0, 0);

        if (!port.openPort()) {
            throw new IllegalStateException("Could not open serial port " + config.portName());
        }

        serialPort = port;
        running = true;
        onLine.accept("Direct serial monitor started on " + port.getSystemPortName() + " @ " + config.baudRate());

        readerThread = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(port.getInputStream()))) {
                String line;
                while (running && (line = reader.readLine()) != null) {
                    onLine.accept(line);
                }
            } catch (Exception ex) {
                if (running && ex.getMessage() != null && !ex.getMessage().isBlank()) {
                    onLine.accept("Serial monitor error: " + ex.getMessage());
                }
            } finally {
                stop();
            }
        }, "SerialMonitor-Reader");
        readerThread.setDaemon(true);
        readerThread.start();
    }

    private String prepareCommand(String command) {
        if (!isWindows() || !looksLikeIdfCommand(command) || command.toLowerCase(Locale.ROOT).contains("export.bat")) {
            return command;
        }

        return findEspIdfExportPath()
                .map(exportPath -> "call \"" + exportPath + "\" && " + command)
                .orElse(command);
    }

    private void configureEnvironment(ProcessBuilder processBuilder, String command) {
        if (!isWindows() || !looksLikeIdfCommand(command)) {
            return;
        }

        Map<String, String> environment = processBuilder.environment();
        if (environment.containsKey("IDF_PYTHON_ENV_PATH")) {
            return;
        }

        findEspIdfPythonEnvPath().ifPresent(path -> environment.put("IDF_PYTHON_ENV_PATH", path));
    }

    private String[] buildCommand(String command) {
        if (isWindows()) {
            return new String[]{"cmd.exe", "/c", command};
        }
        return new String[]{"sh", "-lc", command};
    }

    private boolean isWindows() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        return os.contains("win");
    }

    private boolean looksLikeIdfCommand(String command) {
        return IDF_COMMAND_PATTERN.matcher(command).find();
    }

    private Optional<SerialMonitorConfig> tryParseSerialMonitorConfig(String command) {
        if (!looksLikeIdfCommand(command) || !IDF_MONITOR_PATTERN.matcher(command).find() || IDF_OTHER_ACTION_PATTERN.matcher(command).find()) {
            return Optional.empty();
        }

        String portName = extractOptionValue(command, PORT_OPTION_PATTERN)
                .orElseGet(this::detectPortOrThrow);
        int baudRate = extractOptionValue(command, BAUD_OPTION_PATTERN)
                .map(Integer::parseInt)
                .orElse(DEFAULT_BAUD_RATE);
        return Optional.of(new SerialMonitorConfig(portName, baudRate));
    }

    private String detectPortOrThrow() {
        List<String> ports = Arrays.stream(SerialPort.getCommPorts())
                .map(SerialPort::getSystemPortName)
                .sorted(String::compareToIgnoreCase)
                .toList();
        if (ports.size() == 1) {
            return ports.get(0);
        }
        if (ports.isEmpty()) {
            throw new IllegalArgumentException("No serial ports found. Use -p PORT in the command field.");
        }
        throw new IllegalArgumentException("Multiple serial ports found. Use -p PORT in the command field. Available ports: " + String.join(", ", ports));
    }

    private Optional<String> extractOptionValue(String command, Pattern pattern) {
        Matcher matcher = pattern.matcher(command);
        if (!matcher.find()) {
            return Optional.empty();
        }

        String value = matcher.group(1).trim();
        if (value.length() > 1 && value.startsWith("\"") && value.endsWith("\"")) {
            value = value.substring(1, value.length() - 1);
        }
        return Optional.of(value);
    }

    private Optional<String> findEspIdfExportPath() {
        Optional<String> configuredPath = toSinglePathString(System.getenv("IDF_PATH"));
        if (configuredPath.isPresent()) {
            Path configuredIdfPath = Paths.get(configuredPath.get());
            Path candidate = Files.isDirectory(configuredIdfPath)
                    ? configuredIdfPath.resolve("export.bat")
                    : configuredIdfPath;
            if (Files.isRegularFile(candidate) && candidate.getFileName().toString().equalsIgnoreCase("export.bat")) {
                return Optional.of(candidate.toString());
            }
        }

        Path espRoot = Paths.get(System.getProperty("user.home", ""), "esp");
        if (!Files.isDirectory(espRoot)) {
            return Optional.empty();
        }

        try (Stream<Path> versions = Files.list(espRoot)) {
            return versions
                    .map(path -> path.resolve("esp-idf").resolve("export.bat"))
                    .filter(Files::isRegularFile)
                    .max(Comparator.comparing(Path::toString))
                    .map(Path::toString);
        } catch (Exception ignored) {
            return Optional.empty();
        }
    }

    private Optional<String> findEspIdfPythonEnvPath() {
        Optional<String> configuredPath = toSinglePathString(System.getenv("IDF_PYTHON_ENV_PATH"));
        if (configuredPath.isPresent()) {
            Path candidate = Paths.get(configuredPath.get());
            if (Files.isDirectory(candidate)) {
                return Optional.of(candidate.toString());
            }
        }

        Path pythonEnvRoot = Paths.get(System.getProperty("user.home", ""), ".espressif", "python_env");
        if (!Files.isDirectory(pythonEnvRoot)) {
            return Optional.empty();
        }

        try (Stream<Path> envs = Files.list(pythonEnvRoot)) {
            return envs
                    .filter(Files::isDirectory)
                    .filter(path -> path.getFileName().toString().startsWith("idf"))
                    .max(Comparator.comparing(path -> path.getFileName().toString()))
                    .map(Path::toString);
        } catch (Exception ignored) {
            return Optional.empty();
        }
    }

    private Optional<String> toSinglePathString(String rawValue) {
        if (rawValue == null) {
            return Optional.empty();
        }

        String trimmed = rawValue.trim();
        if (trimmed.isEmpty() || trimmed.contains(";") || trimmed.contains("\r") || trimmed.contains("\n")) {
            return Optional.empty();
        }

        if (trimmed.length() > 1 && trimmed.startsWith("\"") && trimmed.endsWith("\"")) {
            trimmed = trimmed.substring(1, trimmed.length() - 1).trim();
        }

        try {
            Paths.get(trimmed);
            return Optional.of(trimmed);
        } catch (InvalidPathException ignored) {
            return Optional.empty();
        }
    }

    public synchronized void stop() {
        running = false;
        if (serialPort != null) {
            try {
                serialPort.closePort();
            } catch (Exception ignored) {
            }
            serialPort = null;
        }
        if (process != null) {
            try {
                process.descendants().forEach(handle -> {
                    handle.destroy();
                    if (handle.isAlive()) {
                        handle.destroyForcibly();
                    }
                });
                process.destroy();
                if (process.isAlive()) {
                    process.destroyForcibly();
                }
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

    private record SerialMonitorConfig(String portName, int baudRate) {
    }
}
