package com.vesodev.fx;

import com.vesodev.LogParser;
import com.vesodev.MonitorProcess;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ChoiceDialog;
import javafx.stage.FileChooser;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.Slider;
import javafx.scene.control.TextField;
import javafx.scene.image.Image;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.stage.Stage;
import javafx.util.Duration;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Pure JavaFX furnace viewer with:
 * - dark mode
 * - chart for processed avg, setpoint, pid error
 * - extrema annotations for PID error and temperature
 * - live PID settings/current values panel
 */
public class FxMain extends Application {
    private static final int MAX_POINTS = 120_000;
    private static final int MAX_LOG_LINES = 20_000;
    private static final int UI_FLUSH_INTERVAL_MS = 100;
    private static final int MAX_UI_BATCH = 400;
    private static final int MAX_PENDING_UI_EVENTS = 50_000;
    private static final int TELEMETRY_INTERVAL_POINTS = 5_000;
    private static final int ANNOTATION_MIN_GAP = 12; // points between labels
    private static final double PID_MIN_PROMINENCE = 0.02;
    private static final double TEMP_MIN_PROMINENCE = 0.1;
    private static final double DEFAULT_VISIBLE_SPAN_MS = 120 * 60 * 1000.0;
    private static final double PLAYBACK_INTERVAL_MS = 10.0;

    private final ViewportChart chart = new ViewportChart();
    private final ViewportChart.Series seriesAvg = chart.addSeries("Measured Temperature", Color.web("#00D4FF"));
    private final ViewportChart.Series seriesSp = chart.addSeries("Setpoint Temperature", Color.web("#FFD166"));
    private final ViewportChart.Series seriesPidErr = chart.addPidErrorSeries("PID Error", Color.web("#FF4FA3"));

    private final ListView<String> logList = new ListView<>();
    private final CheckBox autoScroll = new CheckBox("Auto-scroll");
    private final CheckBox showPidAnnotations = new CheckBox("Annotate PID Peaks/Valleys");
    private final CheckBox showTempAnnotations = new CheckBox("Annotate Temp Peaks/Valleys");
    private final Label fileLabel = new Label("No file selected");
    private final Slider timeWindowSlider = new Slider(5, 900, 120);
    private final Label timeWindowLabel = new Label("120 min");
    private final Slider timelineSlider = new Slider(0, 1000, 1000);
    private final Label timelineLabel = new Label("live");
    private final CheckBox followLive = new CheckBox("Follow live");
    private final CheckBox fixedYRange = new CheckBox("Fixed Y");
    private final TextField commandField = new TextField("idf.py monitor");
    private final Button liveCommandButton = new Button("Start");
    private final TextField yMinField = new TextField("0");
    private final TextField yMaxField = new TextField("300");
    private final Button applyYRangeBtn = new Button("Apply Y");
    private final MonitorProcess monitorProcess = new MonitorProcess();

    // Live PID / process labels
    private final Label elapsedValue = new Label("N/A");
    private final Label stageValue = new Label("N/A");
    private final Label phaseValue = new Label("N/A");
    private final Label spValue = new Label("N/A");
    private final Label pvValue = new Label("N/A");
    private final Label errValue = new Label("N/A");
    private final Label outValue = new Label("N/A");
    private final Label pValue = new Label("N/A");
    private final Label iValue = new Label("N/A");
    private final Label dValue = new Label("N/A");
    private final Label dtValue = new Label("N/A");
    private final Label dynMaxValue = new Label("N/A");
    private final Label rampStatsValue = new Label("N/A");
    private final Label rampOscValue = new Label("N/A");
    private final Label rampTuneValue = new Label("N/A");
    private final Label holdStatsValue = new Label("N/A");
    private final Label holdOscValue = new Label("N/A");
    private final Label holdTuneValue = new Label("N/A");
    private final Label analysisStatusValue = new Label("Waiting for samples...");

    private long startTimeMs = -1;
    private int dataPointCount = 0;
    private long lastPidAnnotationIndex = -ANNOTATION_MIN_GAP;
    private long lastTempAnnotationIndex = -ANNOTATION_MIN_GAP;
    private Timeline samplePlayback;
    private Timeline uiFlushTimer;
    private final List<String> pendingSampleLines = new ArrayList<>();
    private int pendingSampleIndex = 0;
    private double latestSampleMs = 0;
    private final ConcurrentLinkedQueue<UiSample> pendingUiEvents = new ConcurrentLinkedQueue<>();
    private final AtomicInteger pendingUiSize = new AtomicInteger();
    private final SessionSampleStore sessionSampleStore = new SessionSampleStore(defaultStoreDir());
    private final FurnaceReportService reportService = new FurnaceReportService();
    private final StageLiveStats rampLiveStats = new StageLiveStats(0);
    private final StageLiveStats holdLiveStats = new StageLiveStats(1);
    private long droppedUiEvents;
    private long lastStoreDropCount;
    private double currentWindowStartMs;
    private double currentWindowEndMs;
    private Integer lastKnownPhaseType;

    /**
     * Initialize the JavaFX application.
     */
    @Override
    public void start(Stage primaryStage) {
        primaryStage.setTitle("Furnace Log Viewer");
        try (InputStream iconStream = FxMain.class.getResourceAsStream("/furnace.png")) {
            if (iconStream != null) {
                primaryStage.getIcons().add(new Image(iconStream));
            }
        } catch (Exception ignored) {
            // Fallback to default icon when packaged resource is unavailable.
        }

        chart.setSeriesCapacity(MAX_POINTS);
        configureChart();
        FxLayout.configureLogList(logList);

        Button loadSampleBtn = new Button("Load Sample (Default)");
        Button browseSampleBtn = new Button("Browse Sample...");
        Button exportBtn = new Button("Export Report (xlsx)");

        fileLabel.setStyle("-fx-text-fill: #AAAAAA; -fx-font-size: 11;");

        autoScroll.setSelected(true);
        showPidAnnotations.setSelected(true);
        showTempAnnotations.setSelected(true);
        followLive.setSelected(true);
        timelineSlider.setDisable(true);
        timelineSlider.setShowTickLabels(true);
        timelineSlider.setShowTickMarks(true);
        timelineSlider.setMajorTickUnit(250);
        timelineSlider.valueProperty().addListener((obs, o, n) -> {
            if (!followLive.isSelected()) {
                updateVisibleWindow();
            }
        });

        followLive.selectedProperty().addListener((obs, oldVal, newVal) -> {
            timelineSlider.setDisable(newVal);
            updateVisibleWindow();
        });

        applyYRangeBtn.setOnAction(e -> applyYRangeControls());
        fixedYRange.setOnAction(e -> applyYRangeControls());

        timeWindowSlider.setMajorTickUnit(10);
        timeWindowSlider.setShowTickLabels(true);
        timeWindowSlider.setShowTickMarks(true);
        timeWindowSlider.valueProperty().addListener((obs, o, n) -> {
            int minutes = n.intValue();
            timeWindowLabel.setText(formatMinutes(minutes));
            updateVisibleWindow();
        });

        HBox topLeft = new HBox(8, loadSampleBtn, browseSampleBtn);
        HBox topRight = new HBox(8, autoScroll, showPidAnnotations, showTempAnnotations, followLive, exportBtn);
        HBox.setHgrow(topLeft, Priority.ALWAYS);

        commandField.setPromptText("Enter idf.py command");
        HBox.setHgrow(commandField, Priority.ALWAYS);

        yMinField.setPrefColumnCount(5);
        yMaxField.setPrefColumnCount(5);
        HBox liveCommandRow = new HBox(8, new Label("Live command:"), commandField, liveCommandButton);
        HBox fixedYRow = new HBox(8, fixedYRange, new Label("Y min"), yMinField, new Label("Y max"), yMaxField, applyYRangeBtn);
        HBox timelineRow = new HBox(8, new Label("Timeline:"), timelineSlider, timelineLabel);
        HBox.setHgrow(timelineSlider, Priority.ALWAYS);

        VBox topPanel = new VBox(4);
        topPanel.setPadding(new Insets(8));
        topPanel.getChildren().addAll(new HBox(8, topLeft, topRight), liveCommandRow, new HBox(8, new Label("Window:"), timeWindowSlider, timeWindowLabel), timelineRow, fixedYRow, fileLabel);

        VBox rightPanel = FxLayout.buildRightPanel(
            logList,
            elapsedValue,
            stageValue,
            phaseValue,
            spValue,
            pvValue,
            errValue,
            outValue,
            pValue,
            iValue,
            dValue,
            dtValue,
            dynMaxValue,
            rampStatsValue,
            rampOscValue,
            rampTuneValue,
            holdStatsValue,
            holdOscValue,
            holdTuneValue,
            analysisStatusValue);

        BorderPane root = new BorderPane();
        root.setTop(topPanel);
        root.setCenter(chart);
        root.setRight(rightPanel);

        loadSampleBtn.setOnAction(e -> loadDefaultSample());
        browseSampleBtn.setOnAction(e -> browseSampleFile(primaryStage));
        exportBtn.setOnAction(e -> exportReport(primaryStage));
        liveCommandButton.setOnAction(e -> toggleLiveCommand());
        commandField.setOnAction(e -> toggleLiveCommand());

        showPidAnnotations.setOnAction(e -> refreshAllAnnotations());
        showTempAnnotations.setOnAction(e -> refreshAllAnnotations());

        Scene scene = new Scene(root, 1500, 920);
        applyDarkModeCSS(scene);
        primaryStage.setScene(scene);
        primaryStage.show();

        chart.setVisibleWindow(0, DEFAULT_VISIBLE_SPAN_MS);
        chart.widthProperty().addListener((obs, o, n) -> refreshAllAnnotations());
        chart.heightProperty().addListener((obs, o, n) -> refreshAllAnnotations());
        chart.enforceDarkIndicatorLabelStyle();

        sessionSampleStore.startNewSession();
        Path sessionFile = sessionSampleStore.getCurrentFile();
        if (sessionFile != null) {
            appendLog("Session sample persistence enabled: " + sessionFile);
        }

        uiFlushTimer = new Timeline(new KeyFrame(Duration.millis(UI_FLUSH_INTERVAL_MS), e -> flushPendingUiEvents()));
        uiFlushTimer.setCycleCount(Timeline.INDEFINITE);
        uiFlushTimer.play();
    }

    private void configureChart() {
        chart.setTitleAndLabels("Furnace Temperature and PID Error", "Elapsed Time", "Temperature (C)");
    }

    private void applyDarkModeCSS(Scene scene) {
        var css = FxMain.class.getResource("/furnace-dark.css");
        if (css != null) {
            scene.getStylesheets().add(css.toExternalForm());
        }
    }

    private void loadDefaultSample() {
        File f = new File("src/main/java/com/vesodev/sample log");
        if (!f.exists()) {
            appendLog("Default sample log not found at: " + f.getAbsolutePath());
            return;
        }
        feedFromFile(f);
    }

    private void browseSampleFile(Stage stage) {
        FileChooser fc = new FileChooser();
        fc.setTitle("Select sample log file");
        fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("Log Files", "*.log", "*.*"));
        File sel = fc.showOpenDialog(stage);
        if (sel != null) {
            feedFromFile(sel);
        }
    }

    private void feedFromFile(File f) {
        stopSamplePlayback();
        clearAllData();

        logList.getItems().clear();
        fileLabel.setText("Loaded: " + f.getAbsolutePath());
        startTimeMs = System.currentTimeMillis();
        dataPointCount = 0;

        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8))) {
            pendingSampleLines.clear();
            pendingSampleLines.addAll(br.lines().toList());
            pendingSampleIndex = 0;

            if (pendingSampleLines.isEmpty()) {
                appendLog("Sample file is empty.");
                return;
            }

            samplePlayback = new Timeline(new KeyFrame(Duration.millis(PLAYBACK_INTERVAL_MS), e -> playNextSampleLine()));
            samplePlayback.setCycleCount(Timeline.INDEFINITE);
            samplePlayback.play();
        } catch (Exception ex) {
            appendLog("Failed to read sample file: " + ex.getMessage());
        }
    }

    private void playNextSampleLine() {
        if (pendingSampleIndex >= pendingSampleLines.size()) {
            stopSamplePlayback();
            appendLog("Imported " + pendingSampleLines.size() + " lines with 10 ms pacing.");
            refreshAllAnnotations();
            return;
        }

        handleLine(pendingSampleLines.get(pendingSampleIndex++));
    }

    private void stopSamplePlayback() {
        if (samplePlayback != null) {
            samplePlayback.stop();
            samplePlayback = null;
        }
        pendingSampleLines.clear();
        pendingSampleIndex = 0;
    }

    private void clearAllData() {
        stopSamplePlayback();
        seriesAvg.clear();
        seriesSp.clear();
        seriesPidErr.clear();
        pendingUiEvents.clear();
        pendingUiSize.set(0);
        droppedUiEvents = 0;
        lastPidAnnotationIndex = -ANNOTATION_MIN_GAP;
        lastTempAnnotationIndex = -ANNOTATION_MIN_GAP;
        latestSampleMs = 0;
        lastKnownPhaseType = null;
        timelineSlider.setValue(1000);
        sessionSampleStore.startNewSession();
        Path sessionFile = sessionSampleStore.getCurrentFile();
        if (sessionFile != null) {
            appendLog("Started new session sample file: " + sessionFile);
        }
        resetLiveAnalysis();
        updateVisibleWindow();
    }

    private void handleLine(String line) {
        LogParser.ParsedData d = LogParser.parse(line);

        Long elapsed = d.getElapsedMs().orElse(null);
        if (startTimeMs < 0 && elapsed != null) startTimeMs = System.currentTimeMillis() - elapsed;
        double x = elapsed != null ? elapsed : (System.currentTimeMillis() - startTimeMs);

        Double procAvg = d.getProcessedAvg().orElse(null);
        Double pv = d.getPidPv().orElse(procAvg);
        Double sp = d.getSetpoint().orElse(null);
        Double err = d.getPidErr().orElse(null);
        Integer phaseType = resolvePhaseType(d);

        pendingUiEvents.offer(new UiSample(line, d, elapsed, x, procAvg, pv, sp, err, phaseType));
        int size = pendingUiSize.incrementAndGet();
        while (size > MAX_PENDING_UI_EVENTS) {
            UiSample dropped = pendingUiEvents.poll();
            if (dropped == null) {
                break;
            }
            droppedUiEvents++;
            size = pendingUiSize.decrementAndGet();
        }
    }

    private void updateLiveLabels(LogParser.ParsedData d, Long elapsed, Double sp, Double pv, Double err) {
        if (elapsed != null) elapsedValue.setText(formatElapsed(elapsed));
        d.getStage().ifPresent(v -> stageValue.setText(v.toString()));
        d.getPhase().ifPresent(v -> phaseValue.setText(v.toString()));

        if (sp != null) spValue.setText(String.format("%.3f", sp));
        if (pv != null) pvValue.setText(String.format("%.3f", pv));
        if (err != null) errValue.setText(String.format("%.4f", err));

        d.getPidOut().ifPresent(v -> outValue.setText(String.format("%.4f", v)));
        d.getPidP().ifPresent(v -> pValue.setText(String.format("%.5f", v)));
        d.getPidI().ifPresent(v -> iValue.setText(String.format("%.5f", v)));
        d.getPidD().ifPresent(v -> dValue.setText(String.format("%.5f", v)));
        d.getPidDt().ifPresent(v -> dtValue.setText(String.format("%.4f", v)));
        d.getPidDynMax().ifPresent(v -> dynMaxValue.setText(String.format("%.3f", v)));
    }

    private String formatElapsed(long ms) {
        long minutes = ms / 60000;
        long seconds = (ms % 60000) / 1000;
        long millis = ms % 1000;
        return String.format("%02d:%02d.%03d", minutes, seconds, millis);
    }

    private void updateVisibleWindow() {
        double spanMs = timeWindowSlider.getValue() * 60_000.0;
        double maxStart = Math.max(0.0, latestSampleMs - spanMs);
        double start;
        if (followLive.isSelected()) {
            start = maxStart;
            if (timelineSlider.getValue() != 1000.0) {
                timelineSlider.setValue(1000.0);
            }
        } else {
            double ratio = timelineSlider.getValue() / 1000.0;
            start = maxStart * ratio;
        }
        currentWindowStartMs = start;
        currentWindowEndMs = start + spanMs;
        timelineLabel.setText(String.format("%s -> %s", formatElapsed((long) start), formatElapsed((long) (start + spanMs))));
        chart.setVisibleWindow(start, spanMs);
        chart.redraw();
    }

    private void refreshAllAnnotations() {
        lastPidAnnotationIndex = -ANNOTATION_MIN_GAP;
        lastTempAnnotationIndex = -ANNOTATION_MIN_GAP;
        chart.redraw();
    }

    private String formatMinutes(int minutes) {
        if (minutes < 60) {
            return minutes + " min";
        }
        int hours = minutes / 60;
        int mins = minutes % 60;
        if (mins == 0) {
            return hours + " h";
        }
        return hours + " h " + mins + " min";
    }

    private void applyYRangeControls() {
        if (!fixedYRange.isSelected()) {
            chart.clearFixedYRange();
            appendLog("Y-axis set to auto range");
            return;
        }
        try {
            double yMin = Double.parseDouble(yMinField.getText().trim());
            double yMax = Double.parseDouble(yMaxField.getText().trim());
            chart.setFixedYRange(yMin, yMax);
            appendLog(String.format("Y-axis fixed range applied: %.2f to %.2f", yMin, yMax));
        } catch (NumberFormatException ex) {
            appendLog("Invalid Y range values. Enter numeric min/max.");
            fixedYRange.setSelected(false);
            chart.clearFixedYRange();
        } catch (IllegalArgumentException ex) {
            appendLog(ex.getMessage());
            fixedYRange.setSelected(false);
            chart.clearFixedYRange();
        }
    }

    private void exportReport(Stage stage) {
        Path sessionFile = sessionSampleStore.getCurrentFile();
        if (sessionFile == null || !sessionFile.toFile().exists()) {
            appendLog("No session data available for export yet.");
            return;
        }

        ChoiceDialog<ReportScope> scopeDialog = new ChoiceDialog<>(ReportScope.FULL_SESSION, ReportScope.FULL_SESSION, ReportScope.VISIBLE_WINDOW);
        scopeDialog.setTitle("Export Scope");
        scopeDialog.setHeaderText("Choose report source");
        scopeDialog.setContentText("Export:");
        var selected = scopeDialog.showAndWait();
        if (selected.isEmpty()) {
            return;
        }

        FileChooser fc = new FileChooser();
        fc.setTitle("Save report");
        fc.setInitialFileName("report.xlsx");
        File out = fc.showSaveDialog(stage);
        if (out == null) return;

        ReportScope scope = selected.get();
        double windowStart = currentWindowStartMs;
        double windowEnd = currentWindowEndMs;

        appendLog("Report export started: " + out.getAbsolutePath() + " (scope: " + scope + ")");
        Thread worker = new Thread(() -> {
            try {
                CountDownLatch flushLatch = new CountDownLatch(1);
                Platform.runLater(() -> {
                    try {
                        flushPendingUiEvents();
                    } finally {
                        flushLatch.countDown();
                    }
                });
                flushLatch.await();
                sessionSampleStore.awaitDrain(5000);

                FurnaceReportService.ExportResult result = reportService.exportXlsx(
                        sessionFile,
                        out.toPath(),
                        scope,
                        windowStart,
                        windowEnd);

                Platform.runLater(() -> appendLog("Report saved: " + result.outputFile
                        + " | rows=" + result.rows
                        + " | durationMs=" + result.durationMs
                        + " | maxTemp=" + formatMetric(result.maxTemp)
                        + " | maxAbsErr=" + formatMetric(result.maxAbsErr)));
            } catch (Exception ex) {
                Platform.runLater(() -> appendLog("Report export failed: " + ex.getMessage()));
            }
        }, "ReportExport-Worker");
        worker.setDaemon(true);
        worker.start();
    }

    @Override
    public void stop() {
        stopSamplePlayback();
        stopLiveCommand();
        if (uiFlushTimer != null) {
            uiFlushTimer.stop();
        }
        sessionSampleStore.close();
    }

    public static void main(String[] args) {
        launch(args);
    }

    private void appendLog(String line) {
        if (line == null) {
            return;
        }
        logList.getItems().add(line);
        if (logList.getItems().size() > MAX_LOG_LINES) {
            int removeCount = logList.getItems().size() - MAX_LOG_LINES;
            logList.getItems().remove(0, removeCount);
        }
        if (autoScroll.isSelected()) {
            int last = logList.getItems().size() - 1;
            if (last >= 0) {
                logList.scrollTo(last);
            }
        }
    }

    private void toggleLiveCommand() {
        if (monitorProcess.isRunning()) {
            stopLiveCommand();
            return;
        }
        startLiveCommand();
    }

    private void startLiveCommand() {
        String command = commandField.getText() == null ? "" : commandField.getText().trim();
        if (command.isEmpty()) {
            appendLog("Enter an idf.py command before starting.");
            return;
        }

        stopSamplePlayback();
        clearAllData();
        logList.getItems().clear();
        fileLabel.setText("Live command: " + command);
        setLiveCommandRunning(true);
        appendLog("Starting command: " + command);

        Thread starter = new Thread(() -> {
            try {
                monitorProcess.start(command, System.getProperty("user.dir"), line ->
                        Platform.runLater(() -> handleLine(line)));
            } catch (Exception ex) {
                Platform.runLater(() -> {
                    appendLog("Failed to start command: " + ex.getMessage());
                    setLiveCommandRunning(false);
                });
                return;
            }

            while (monitorProcess.isRunning()) {
                try {
                    Thread.sleep(200);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }

            Platform.runLater(() -> {
                if (!monitorProcess.isRunning()) {
                    appendLog("Command stopped.");
                    setLiveCommandRunning(false);
                }
            });
        }, "LiveCommand-Starter");
        starter.setDaemon(true);
        starter.start();
    }

    private void stopLiveCommand() {
        boolean wasRunning = monitorProcess.isRunning();
        monitorProcess.stop();
        setLiveCommandRunning(false);
        if (wasRunning) {
            appendLog("Stopping command...");
        }
    }

    private void setLiveCommandRunning(boolean running) {
        liveCommandButton.setText(running ? "Stop" : "Start");
        commandField.setDisable(running);
    }

    private void flushPendingUiEvents() {
        double[] xAvg = new double[MAX_UI_BATCH];
        double[] yAvg = new double[MAX_UI_BATCH];
        int cAvg = 0;
        double[] xSp = new double[MAX_UI_BATCH];
        double[] ySp = new double[MAX_UI_BATCH];
        int cSp = 0;
        double[] xErr = new double[MAX_UI_BATCH];
        double[] yErr = new double[MAX_UI_BATCH];
        int cErr = 0;
        List<String> csvBatch = new ArrayList<>(MAX_UI_BATCH);

        int processed = 0;
        UiSample latest = null;
        while (processed < MAX_UI_BATCH) {
            UiSample sample = pendingUiEvents.poll();
            if (sample == null) {
                break;
            }
            pendingUiSize.decrementAndGet();
            processed++;
            latest = sample;

            appendLog(sample.line);
            updateLiveLabels(sample.data, sample.elapsed, sample.sp, sample.pv, sample.err);
            updateLiveAnalysis(sample);
            csvBatch.add(toCsv(sample));

            Double plottedAvg = sample.procAvg != null ? sample.procAvg : sample.pv;
            if (plottedAvg != null) {
                xAvg[cAvg] = sample.x;
                yAvg[cAvg] = plottedAvg;
                cAvg++;
            }
            if (sample.sp != null) {
                xSp[cSp] = sample.x;
                ySp[cSp] = sample.sp;
                cSp++;
            }
            if (sample.err != null) {
                xErr[cErr] = sample.x;
                yErr[cErr] = sample.err;
                cErr++;
            }
        }

        if (processed == 0) {
            return;
        }

        chart.enforceDarkIndicatorLabelStyle();

        if (cAvg > 0) {
            seriesAvg.addBatch(xAvg, yAvg, cAvg);
        }
        if (cSp > 0) {
            seriesSp.addBatch(xSp, ySp, cSp);
        }
        if (cErr > 0) {
            seriesPidErr.addBatch(xErr, yErr, cErr);
        }
        sessionSampleStore.appendLines(csvBatch);

        dataPointCount += processed;
        if (latest != null) {
            latestSampleMs = Math.max(latestSampleMs, latest.x);
        }

        if (droppedUiEvents > 0) {
            appendLog("UI backpressure dropped " + droppedUiEvents + " old samples to keep memory bounded.");
            droppedUiEvents = 0;
        }

        if ((dataPointCount % TELEMETRY_INTERVAL_POINTS) < processed) {
            appendLog("Processed points: " + dataPointCount
                    + " (ui queue: " + pendingUiSize.get()
                    + ", disk queue: " + sessionSampleStore.getQueueSize()
                    + ", disk rows: " + sessionSampleStore.getWrittenCount() + ")");
        }

        long storeDrops = sessionSampleStore.getDroppedCount();
        if (storeDrops > lastStoreDropCount) {
            long delta = storeDrops - lastStoreDropCount;
            appendLog("Disk sample store dropped " + delta + " rows due to queue pressure.");
            lastStoreDropCount = storeDrops;
        }

        maybeAnnotateLatestExtrema();
        updateVisibleWindow();
        updateLiveAnalysisLabels();
    }

    private String toCsv(UiSample sample) {
        return csv(sample.elapsed) + ","
                + sample.x + ","
                + csv(sample.data.getStage().map(Object::toString).orElse(null)) + ","
                + csv(sample.data.getPhase().map(Object::toString).orElse(null)) + ","
                + csv(sample.sp) + ","
                + csv(sample.pv) + ","
                + csv(sample.procAvg) + ","
                + csv(sample.err) + ","
                + csv(sample.data.getPidP().orElse(null)) + ","
                + csv(sample.data.getPidI().orElse(null)) + ","
                + csv(sample.data.getPidD().orElse(null)) + ","
                + csv(sample.data.getPidOut().orElse(null));
    }

    private static String csv(Object value) {
        return value == null ? "" : value.toString();
    }

    private static Path defaultStoreDir() {
        return Paths.get(System.getProperty("user.home"), "furnace-log-viewer", "sessions");
    }

    private String formatMetric(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return "N/A";
        }
        return String.format("%.6f", value);
    }

    private void resetLiveAnalysis() {
        rampLiveStats.reset();
        holdLiveStats.reset();
        rampStatsValue.setText("N/A");
        rampOscValue.setText("N/A");
        rampTuneValue.setText("N/A");
        holdStatsValue.setText("N/A");
        holdOscValue.setText("N/A");
        holdTuneValue.setText("N/A");
        analysisStatusValue.setText("Waiting for samples...");
    }

    private void updateLiveAnalysis(UiSample sample) {
        Integer phase = sample.phaseType;
        if (phase == null || sample.err == null || sample.err.isNaN() || sample.err.isInfinite()) {
            return;
        }

        StageLiveStats target = null;
        if (phase == 0) {
            target = rampLiveStats;
        } else if (phase == 1) {
            target = holdLiveStats;
        }
        if (target == null) {
            return;
        }

        target.add(sample.elapsed, sample.err,
                sample.data.getPidP().orElse(null),
                sample.data.getPidI().orElse(null),
                sample.data.getPidD().orElse(null));
    }

    private void updateLiveAnalysisLabels() {
        TuneRecommendation rampRec = computeRecommendation(rampLiveStats);
        TuneRecommendation holdRec = computeRecommendation(holdLiveStats);

        rampStatsValue.setText(formatStageStats(rampLiveStats));
        rampOscValue.setText(formatStageOscillation(rampLiveStats));
        rampTuneValue.setText(formatTune(rampLiveStats, rampRec));

        holdStatsValue.setText(formatStageStats(holdLiveStats));
        holdOscValue.setText(formatStageOscillation(holdLiveStats));
        holdTuneValue.setText(formatTune(holdLiveStats, holdRec));

        analysisStatusValue.setText("Live suggestions update every flush from incoming samples.");
    }

    private TuneRecommendation computeRecommendation(StageLiveStats stats) {
        if (stats.samples < 20) {
            return new TuneRecommendation(1.0, 1.0, 1.0, "collecting more data");
        }

        double zpm = stats.zeroCrossingsPerMinute();
        double meanAbs = stats.meanAbsError();
        double stdDev = stats.stdDev();
        double p2p = stats.avgPeakToPeak();
        double meanErr = stats.meanError();

        boolean oscillatory = zpm >= 8.0
                || (p2p >= 2.5 && stdDev >= 1.2)
                || (meanAbs > 0.0 && p2p > meanAbs * 1.8);
        boolean biased = Math.abs(meanErr) >= (stats.stageType == 0 ? 0.8 : 0.5);
        boolean slow = meanAbs >= (stats.stageType == 0 ? 1.8 : 1.2) && zpm < 3.0;

        if (oscillatory) {
            if (stats.stageType == 0) {
                return new TuneRecommendation(0.90, 0.90, 1.20, "oscillatory: damp ramp swing");
            }
            return new TuneRecommendation(0.85, 0.80, 1.25, "oscillatory: damp hold cycling");
        }

        if (slow || (biased && meanErr > 0)) {
            if (stats.stageType == 0) {
                return new TuneRecommendation(1.10, 1.10, 1.00, "slow/below SP: faster ramp convergence");
            }
            return new TuneRecommendation(1.05, 1.15, 1.00, "offset below SP: remove hold bias");
        }

        if (biased && meanErr < 0) {
            return new TuneRecommendation(0.95, 0.95, 1.05, "overshoot tendency");
        }

        return new TuneRecommendation(1.00, 1.00, 1.00, "stable");
    }

    private String formatStageStats(StageLiveStats stats) {
        if (stats.samples == 0) {
            return "no samples";
        }
        return String.format("n=%d MAE=%.3f RMSE=%.3f meanErr=%.3f", stats.samples, stats.meanAbsError(), stats.rmse(), stats.meanError());
    }

    private String formatStageOscillation(StageLiveStats stats) {
        if (stats.samples < 2) {
            return "insufficient";
        }
        return String.format("zeroX/min=%.2f p2p(avg/max)=%.3f/%.3f", stats.zeroCrossingsPerMinute(), stats.avgPeakToPeak(), stats.maxPeakToPeak);
    }

    private String formatTune(StageLiveStats stats, TuneRecommendation rec) {
        if (stats.samples == 0) {
            return "collecting...";
        }
        String current = "";
        if (!Double.isNaN(stats.avgP()) && !Double.isNaN(stats.avgI()) && !Double.isNaN(stats.avgD())) {
            current = String.format(" -> P=%.5f I=%.5f D=%.5f",
                    stats.avgP() * rec.pFactor,
                    stats.avgI() * rec.iFactor,
                    stats.avgD() * rec.dFactor);
        }
        return String.format("P x%.2f I x%.2f D x%.2f (%s)%s",
                rec.pFactor, rec.iFactor, rec.dFactor, rec.reason, current);
    }

    private Integer resolvePhaseType(LogParser.ParsedData data) {
        Integer phase = normalizePhaseType(data.getPhase().orElse(null));
        if (phase != null) {
            lastKnownPhaseType = phase;
            return phase;
        }

        Integer stage = normalizePhaseType(data.getStage().orElse(null));
        if (stage != null) {
            lastKnownPhaseType = stage;
            return stage;
        }

        return lastKnownPhaseType;
    }

    private Integer normalizePhaseType(Integer value) {
        if (value == null) {
            return null;
        }
        return (value == 0 || value == 1) ? value : null;
    }

    private void maybeAnnotateLatestExtrema() {
        // Annotation logic will be reintroduced in the chart-fx migration phase.
    }
}
