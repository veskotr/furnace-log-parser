package com.vesodev;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.HashSet;
import java.util.Set;
import java.util.ArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.io.BufferedReader;
import java.io.FileReader;
import java.util.List;
import javax.swing.SwingWorker;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.CreationHelper;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.ClientAnchor;
import org.apache.poi.xssf.usermodel.XSSFDrawing;
import org.apache.poi.xssf.usermodel.XSSFPicture;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import java.nio.file.Files;

public class Main {
    private final JFrame frame = new JFrame("ESP Monitor Viewer");
    private final JTextArea logArea = new JTextArea();

    // Field labels
    private final JLabel elapsedLabel = new JLabel("N/A");
    private final JLabel stageLabel = new JLabel("N/A");
    private final JLabel phaseLabel = new JLabel("N/A");
    private final JLabel setpointLabel = new JLabel("N/A");

    private final JLabel pidSpLabel = new JLabel("N/A");
    private final JLabel pidPvLabel = new JLabel("N/A");
    private final JLabel pidErrLabel = new JLabel("N/A");
    private final JLabel pidDtLabel = new JLabel("N/A");
    private final JLabel pidDynMaxLabel = new JLabel("N/A");
    private final JLabel pidPLabel = new JLabel("N/A");
    private final JLabel pidILabel = new JLabel("N/A");
    private final JLabel pidDLabel = new JLabel("N/A");
    private final JLabel pidOutLabel = new JLabel("N/A");

    private final JLabel sampleMinLabel = new JLabel("N/A");
    private final JLabel sampleMaxLabel = new JLabel("N/A");
    private final JLabel processedAvgLabel = new JLabel("N/A");

    private final JLabel cdabTempLabel = new JLabel("N/A");
    private final JLabel sensorIdLabel = new JLabel("N/A");

    // Generic numbers area (shows all numeric tokens found on latest line)
    private final JTextArea numbersArea = new JTextArea();

    // Graph component
    private final TimeSeriesGraph graph = new TimeSeriesGraph(200);

    private final JTextField commandField = new JTextField("idf.py monitor");
    // new: working directory
    private final JTextField workingDirField = new JTextField("");
    private final JButton browseDirBtn = new JButton("…");

    private final JButton startBtn = new JButton("Start");
    private final JButton reportBtn = new JButton("Generate Report");
    private final JButton feedSampleBtn = new JButton("Feed Sample");
    private final JButton testGraphBtn = new JButton("Test Graph");

    private final MonitorProcess monitor = new MonitorProcess();

    private static final Pattern NUMBER_PATTERN = Pattern.compile("[-+]?[0-9]*\\.?[0-9]+(?:[eE][-+]?[0-9]+)?");

    // --- Reporting / metrics state ---
    private double maxMeasuredTemperature = Double.NEGATIVE_INFINITY; // across samples
    private double maxMeasuredError = 0.0; // absolute
    private final Set<String> observedPidCoefficients = new HashSet<>(); // unique P/I/D sets as strings
    private volatile Long firstElapsedMs = null;
    private volatile Long lastElapsedMs = null;

    // previous values for detecting ramp vs hold
    private Double prevSetpoint = null;
    private int stableCount = 0;
    private static final double STABLE_THRESHOLD = 0.5; // degrees
    private static final int STABLE_COUNT_FOR_HOLD = 5;
    private boolean inHold = false;

    private final OscillationStats rampStats = new OscillationStats();
    private final OscillationStats holdStats = new OscillationStats();
    private int graphAddCount = 0;
    private int feedLineCount = 0;
    // store history of parsed samples for reporting / CSV export
    private final java.util.List<Sample> samples = new ArrayList<>();
    // per-stage oscillation accumulators (stage -> stats)
    private final java.util.Map<String, OscillationStats> stageStats = new java.util.HashMap<>();
    // per-stage+mode (e.g. "StageA|hold" or "StageA|ramp")
    private final java.util.Map<String, OscillationStats> stageModeStats = new java.util.HashMap<>();

    public Main() {
        setupUI();
    }

    private JPanel labelled(JLabel name, JLabel value) {
        JPanel p = new JPanel(new BorderLayout(4, 4));
        p.add(name, BorderLayout.WEST);
        p.add(value, BorderLayout.CENTER);
        return p;
    }

    private void setupUI() {
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setSize(1000, 700);

        logArea.setEditable(false);
        JScrollPane logScroll = new JScrollPane(logArea);

        JPanel top = new JPanel(new BorderLayout(8, 8));

        JPanel cmdPanel = new JPanel(new BorderLayout(4,4));
        cmdPanel.add(new JLabel("Command:"), BorderLayout.WEST);
        cmdPanel.add(commandField, BorderLayout.CENTER);

        JPanel wdPanel = new JPanel(new BorderLayout(4,4));
        wdPanel.add(new JLabel("Working dir:"), BorderLayout.WEST);
        wdPanel.add(workingDirField, BorderLayout.CENTER);
        wdPanel.add(browseDirBtn, BorderLayout.EAST);

        JPanel northLeft = new JPanel(new GridLayout(2,1,4,4));
        northLeft.add(cmdPanel);
        northLeft.add(wdPanel);

        top.add(northLeft, BorderLayout.CENTER);
        // replace single start button with a small panel for start + report + feed sample + test graph
        JPanel btnPanel = new JPanel(new GridLayout(1,4,4,4));
        btnPanel.add(startBtn);
        btnPanel.add(reportBtn);
        btnPanel.add(feedSampleBtn);
        btnPanel.add(testGraphBtn);
        top.add(btnPanel, BorderLayout.EAST);

        browseDirBtn.addActionListener(e -> {
            JFileChooser fc = new JFileChooser();
            fc.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
            int res = fc.showOpenDialog(frame);
            if (res == JFileChooser.APPROVE_OPTION) {
                File f = fc.getSelectedFile();
                workingDirField.setText(f.getAbsolutePath());
            }
        });

        reportBtn.addActionListener(e -> generateReport());
        feedSampleBtn.addActionListener(e -> onFeedSample());
        testGraphBtn.addActionListener(e -> onTestGraph());

        // Right side: grid of fields
        JPanel fields = new JPanel();
        fields.setLayout(new BoxLayout(fields, BoxLayout.Y_AXIS));

        // Graph on top
        fields.add(new JLabel("Temperature Graph (Processed Avg = blue, Setpoint = red):"));
        graph.setPreferredSize(new Dimension(320, 220));
        fields.add(graph);
        fields.add(Box.createVerticalStrut(8));

        fields.add(labelled(new JLabel("Elapsed (mm:ss.SSS):"), elapsedLabel));
        fields.add(labelled(new JLabel("Stage:"), stageLabel));
        fields.add(labelled(new JLabel("Phase:"), phaseLabel));
        fields.add(labelled(new JLabel("Setpoint (C):"), setpointLabel));
        fields.add(Box.createVerticalStrut(6));

        fields.add(labelled(new JLabel("PID SP:"), pidSpLabel));
        fields.add(labelled(new JLabel("PID PV:"), pidPvLabel));
        fields.add(labelled(new JLabel("PID err:"), pidErrLabel));
        fields.add(labelled(new JLabel("PID dt (s):"), pidDtLabel));
        fields.add(labelled(new JLabel("PID dyn_max:"), pidDynMaxLabel));
        fields.add(labelled(new JLabel("PID P:"), pidPLabel));
        fields.add(labelled(new JLabel("PID I:"), pidILabel));
        fields.add(labelled(new JLabel("PID D:"), pidDLabel));
        fields.add(labelled(new JLabel("PID Out:"), pidOutLabel));
        fields.add(Box.createVerticalStrut(6));

        fields.add(labelled(new JLabel("Sample Min (C):"), sampleMinLabel));
        fields.add(labelled(new JLabel("Sample Max (C):"), sampleMaxLabel));
        fields.add(labelled(new JLabel("Processed Avg (C):"), processedAvgLabel));
        fields.add(Box.createVerticalStrut(6));

        fields.add(labelled(new JLabel("CDAB Temp (C):"), cdabTempLabel));
        fields.add(labelled(new JLabel("Sensor ID:"), sensorIdLabel));
        fields.add(Box.createVerticalStrut(12));

        fields.add(new JLabel("Numbers on last line:"));
        numbersArea.setEditable(false);
        numbersArea.setRows(6);
        fields.add(new JScrollPane(numbersArea));

        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, logScroll, fields);
        split.setResizeWeight(0.7);

        frame.getContentPane().setLayout(new BorderLayout(8, 8));
        frame.getContentPane().add(top, BorderLayout.NORTH);
        frame.getContentPane().add(split, BorderLayout.CENTER);

        startBtn.addActionListener(this::onStartStop);

        frame.setVisible(true);
    }

    private void onStartStop(ActionEvent e) {
        if (!monitor.isRunning()) {
            String cmdText = commandField.getText().trim();
            if (cmdText.isEmpty()) return;
            try {
                String wd = workingDirField.getText().trim();
                monitor.start(cmdText, wd.isEmpty() ? null : wd, this::handleLine);
                 startBtn.setText("Stop");
                 appendLog("Started: " + cmdText);
             } catch (Exception ex) {
                 appendLog("Failed to start: " + ex.getMessage());
             }
         } else {
             monitor.stop();
             startBtn.setText("Start");
             appendLog("Stopped.");
         }
     }

    private void handleLine(String line) {
        SwingUtilities.invokeLater(() -> {
            appendLog(line);
            LogParser.ParsedData d = LogParser.parse(line);

            // format elapsed in mm:ss.SSS
            setLabel(elapsedLabel, d.getElapsedMs().map(Main::formatElapsed).orElse(null));
            d.getElapsedMs().ifPresent(ms -> {
                if (firstElapsedMs == null) firstElapsedMs = ms;
                lastElapsedMs = ms;
            });

            setLabel(stageLabel, d.getStage().map(Object::toString).orElse(null));
            setLabel(phaseLabel, d.getPhase().map(Object::toString).orElse(null));
            setLabel(setpointLabel, d.getSetpoint().map(v -> String.format("%.2f", v)).orElse(null));

            setLabel(pidSpLabel, d.getPidSp().map(v -> String.format("%.2f", v)).orElse(null));
            setLabel(pidPvLabel, d.getPidPv().map(v -> String.format("%.2f", v)).orElse(null));
            setLabel(pidErrLabel, d.getPidErr().map(v -> String.format("%.3f", v)).orElse(null));
            setLabel(pidDtLabel, d.getPidDt().map(v -> String.format("%.3f", v)).orElse(null));
            setLabel(pidDynMaxLabel, d.getPidDynMax().map(v -> String.format("%.2f", v)).orElse(null));
            setLabel(pidPLabel, d.getPidP().map(v -> String.format("%.3f", v)).orElse(null));
            setLabel(pidILabel, d.getPidI().map(v -> String.format("%.3f", v)).orElse(null));
            setLabel(pidDLabel, d.getPidD().map(v -> String.format("%.3f", v)).orElse(null));
            setLabel(pidOutLabel, d.getPidOut().map(v -> String.format("%.3f", v)).orElse(null));

            setLabel(sampleMinLabel, d.getSampleMin().map(v -> String.format("%.2f", v)).orElse(null));
            setLabel(sampleMaxLabel, d.getSampleMax().map(v -> String.format("%.2f", v)).orElse(null));
            setLabel(processedAvgLabel, d.getProcessedAvg().map(v -> String.format("%.2f", v)).orElse(null));

            setLabel(cdabTempLabel, d.getCdabTemp().map(v -> String.format("%.4f", v)).orElse(null));
            setLabel(sensorIdLabel, d.getSensorId().map(Object::toString).orElse(null));

            // update metrics
            // max temperature considers processedAvg and sampleMax
            d.getProcessedAvg().ifPresent(v -> maxMeasuredTemperature = Math.max(maxMeasuredTemperature, v));
            d.getSampleMax().ifPresent(v -> maxMeasuredTemperature = Math.max(maxMeasuredTemperature, v));

            // max error
            d.getPidErr().ifPresent(v -> maxMeasuredError = Math.max(maxMeasuredError, Math.abs(v)));

            // collect observed PID coefficients
            if (d.getPidP().isPresent() || d.getPidI().isPresent() || d.getPidD().isPresent()) {
                String p = d.getPidP().map(Object::toString).orElse("-");
                String i = d.getPidI().map(Object::toString).orElse("-");
                String dd = d.getPidD().map(Object::toString).orElse("-");
                observedPidCoefficients.add(String.format("P=%s I=%s D=%s", p, i, dd));
            }

            // determine pv and sp to feed oscillation stats
            Double procAvg = d.getProcessedAvg().orElse(null);
            Double pv = d.getPidPv().orElse(procAvg);
            Double sp = d.getSetpoint().orElse(null);
            Long elapsed = d.getElapsedMs().orElse(null);

            if (sp != null && pv != null) {
                // detect hold vs ramp based on setpoint stability
                if (prevSetpoint == null || Math.abs(sp - prevSetpoint) > STABLE_THRESHOLD) {
                    // setpoint changed -> ramp
                    inHold = false;
                    stableCount = 0;
                } else {
                    // setpoint within threshold
                    stableCount++;
                    if (stableCount >= STABLE_COUNT_FOR_HOLD) inHold = true;
                }
                prevSetpoint = sp;

                // feed appropriate accumulator
                if (inHold) {
                    holdStats.addSample(elapsed, pv, sp);
                } else {
                    rampStats.addSample(elapsed, pv, sp);
                }
                // feed per-stage stats (stage may be numeric or string; use toString)
                String stage = d.getStage().map(Object::toString).orElse("unknown");
                OscillationStats sstats = stageStats.computeIfAbsent(stage, k -> new OscillationStats());
                sstats.addSample(elapsed, pv, sp);
                // feed per-stage+mode stats
                String modeKey = stage + "|" + (inHold ? "hold" : "ramp");
                OscillationStats sm = stageModeStats.computeIfAbsent(modeKey, k -> new OscillationStats());
                sm.addSample(elapsed, pv, sp);
             }

            // Feed graph: prefer processedAvg, fall back to pid PV so tests still show data
            Double spForGraph = d.getSetpoint().orElse(null);
            Double pvForGraph = pv; // derived earlier (pidPv or processedAvg)
            Double graphAvg = procAvg != null ? procAvg : pvForGraph;
            if (graphAvg != null || spForGraph != null) {
                graph.addSample(graphAvg, spForGraph);
                graphAddCount++;
                if ((graphAddCount % 200) == 0) appendLog("Graph samples added: " + graphAddCount);
            }

            // Extract all numeric tokens on the line and show them
            Matcher nm = NUMBER_PATTERN.matcher(line);
            StringBuilder nums = new StringBuilder();
            while (nm.find()) {
                if (!nums.isEmpty()) nums.append(", ");
                nums.append(nm.group());
            }
            numbersArea.setText(nums.toString());

            // store sample data for reporting
            samples.add(new Sample(
                d.getElapsedMs().orElse(null),
                d.getSetpoint().orElse(null),
                d.getPidPv().orElse(null),
                d.getProcessedAvg().orElse(null),
                d.getPidErr().orElse(null),
                d.getPidP().orElse(null),
                d.getPidI().orElse(null),
                d.getPidD().orElse(null),
                d.getPidOut().orElse(null),
                d.getSampleMin().orElse(null),
                d.getSampleMax().orElse(null),
                d.getCdabTemp().orElse(null),
                d.getSensorId().map(Object::toString).orElse(null)
            ));
        });
    }

    private static String formatElapsed(Long ms) {
        if (ms == null) return null;
        long totalMs = ms;
        long minutes = totalMs / 60000;
        long seconds = (totalMs % 60000) / 1000;
        long millis = totalMs % 1000;
        return String.format("%02d:%02d.%03d", minutes, seconds, millis);
    }

    private void setLabel(JLabel label, String value) {
        if (value == null) {
            // keep existing value
            return;
        }
        label.setText(value);
    }

    private void appendLog(String text) {
        if (SwingUtilities.isEventDispatchThread()) {
            logArea.append(text + "\n");
            logArea.setCaretPosition(logArea.getDocument().getLength());
        } else {
            SwingUtilities.invokeLater(() -> {
                logArea.append(text + "\n");
                logArea.setCaretPosition(logArea.getDocument().getLength());
            });
        }
    }

    private void generateReport() {
        JFileChooser fc = new JFileChooser();
        fc.setDialogTitle("Save report");
        int res = fc.showSaveDialog(frame);
        if (res != JFileChooser.APPROVE_OPTION) return;
        File f = fc.getSelectedFile();
        try (FileWriter w = new FileWriter(f)) {
            StringBuilder sb = new StringBuilder();
            sb.append("Furnace PID Report\n");
            sb.append("Generated: ").append(new java.util.Date()).append("\n\n");

            sb.append("Duration (ms): ");
            if (firstElapsedMs != null && lastElapsedMs != null) {
                long dur = lastElapsedMs - firstElapsedMs;
                sb.append(dur).append(" ms\n");
                sb.append("Duration (mm:ss.SSS): ").append(formatElapsed(dur)).append("\n");
            } else if (firstElapsedMs != null) {
                sb.append("Only first timestamp seen: ").append(firstElapsedMs).append(" ms\n");
            } else {
                sb.append("N/A\n");
            }

            sb.append("Max measured temperature (C): ");
            if (maxMeasuredTemperature != Double.NEGATIVE_INFINITY) sb.append(String.format("%.3f", maxMeasuredTemperature)).append("\n"); else sb.append("N/A\n");

            sb.append("Max measured absolute PID error: ").append(String.format("%.6f", maxMeasuredError)).append("\n\n");

            sb.append("Observed PID coefficients (unique sets):\n");
            if (observedPidCoefficients.isEmpty()) sb.append("  none\n");
            else for (String s : observedPidCoefficients) sb.append("  ").append(s).append("\n");
            sb.append("\n");

            sb.append("Ramp statistics:\n");
            sb.append(rampStats.summary());
            sb.append("\nHold statistics:\n");
            sb.append(holdStats.summary());
            sb.append("\n");

            sb.append("Per-stage statistics:\n");
            if (stageStats.isEmpty()) sb.append("  none\n");
            else {
                for (java.util.Map.Entry<String, OscillationStats> e : stageStats.entrySet()) {
                    sb.append("  Stage: ").append(e.getKey()).append("\n");
                    sb.append(e.getValue().summary());
                }
            }
            sb.append("Per-stage+mode statistics:\n");
            if (stageModeStats.isEmpty()) sb.append("  none\n");
            else {
                for (java.util.Map.Entry<String, OscillationStats> e : stageModeStats.entrySet()) {
                    sb.append("  Mode: ").append(e.getKey()).append("\n");
                    sb.append(e.getValue().summary());
                }
            }
            sb.append("\n");

            // CSV header
            sb.append("Elapsed (ms),Setpoint (C),PV (C),Processed Avg (C),PID Err,PID P,PID I,PID D,PID Out,Sample Min (C),Sample Max (C),CDAB Temp (C),Sensor ID\n");

            // CSV data rows
            for (Sample sample : samples) {
                sb.append(String.format("%d,%.2f,%.2f,%.2f,%.6f,%.3f,%.3f,%.3f,%.3f,%.2f,%.2f,%.4f,%s\n",
                    sample.elapsedMs != null ? sample.elapsedMs : 0,
                    sample.setpoint != null ? sample.setpoint : 0,
                    sample.pv != null ? sample.pv : 0,
                    sample.processedAvg != null ? sample.processedAvg : 0,
                    sample.pidErr != null ? sample.pidErr : 0,
                    sample.pidP != null ? sample.pidP : 0,
                    sample.pidI != null ? sample.pidI : 0,
                    sample.pidD != null ? sample.pidD : 0,
                    sample.pidOut != null ? sample.pidOut : 0,
                    sample.sampleMin != null ? sample.sampleMin : 0,
                    sample.sampleMax != null ? sample.sampleMax : 0,
                    sample.cdabTemp != null ? sample.cdabTemp : 0,
                    sample.sensorId != null ? sample.sensorId : ""
                ));
            }

            // Write CSV data
            w.write(sb.toString());
            appendLog("Report saved to: " + f.getAbsolutePath());

            // Generate and save graph image
            BufferedImage img = graphToImage();
            File imgFile = null;
            if (img != null) {
                imgFile = new File(f.getAbsolutePath() + ".png");
                ImageIO.write(img, "png", imgFile);
                appendLog("Graph image saved to: " + imgFile.getAbsolutePath());
            }

            // Also create an Excel workbook with the CSV data and embedded graph image (if available)
            try (XSSFWorkbook wb = new XSSFWorkbook()) {
                CreationHelper helper = wb.getCreationHelper();
                Sheet dataSheet = wb.createSheet("Samples");
                // header
                Row header = dataSheet.createRow(0);
                String[] cols = new String[]{"Elapsed (ms)","Setpoint (C)","PV (C)","Processed Avg (C)","PID Err","PID P","PID I","PID D","PID Out","Sample Min (C)","Sample Max (C)","CDAB Temp (C)","Sensor ID"};
                for (int i = 0; i < cols.length; i++) header.createCell(i).setCellValue(cols[i]);

                // rows
                int r = 1;
                for (Sample sample : samples) {
                    Row row = dataSheet.createRow(r++);
                    row.createCell(0).setCellValue(sample.elapsedMs != null ? sample.elapsedMs : 0);
                    row.createCell(1).setCellValue(sample.setpoint != null ? sample.setpoint : 0);
                    row.createCell(2).setCellValue(sample.pv != null ? sample.pv : 0);
                    row.createCell(3).setCellValue(sample.processedAvg != null ? sample.processedAvg : 0);
                    row.createCell(4).setCellValue(sample.pidErr != null ? sample.pidErr : 0);
                    row.createCell(5).setCellValue(sample.pidP != null ? sample.pidP : 0);
                    row.createCell(6).setCellValue(sample.pidI != null ? sample.pidI : 0);
                    row.createCell(7).setCellValue(sample.pidD != null ? sample.pidD : 0);
                    row.createCell(8).setCellValue(sample.pidOut != null ? sample.pidOut : 0);
                    row.createCell(9).setCellValue(sample.sampleMin != null ? sample.sampleMin : 0);
                    row.createCell(10).setCellValue(sample.sampleMax != null ? sample.sampleMax : 0);
                    row.createCell(11).setCellValue(sample.cdabTemp != null ? sample.cdabTemp : 0);
                    row.createCell(12).setCellValue(sample.sensorId != null ? sample.sensorId : "");
                }

                // autosize first few columns
                for (int i = 0; i < cols.length; i++) dataSheet.autoSizeColumn(i);

                // Summary sheet: per-stage and per-stage+mode numeric table
                Sheet summary = wb.createSheet("Summary");
                int sr = 0;
                Row hrow = summary.createRow(sr++);
                hrow.createCell(0).setCellValue("Type");
                hrow.createCell(1).setCellValue("Name");
                hrow.createCell(2).setCellValue("Samples");
                hrow.createCell(3).setCellValue("MaxAbsError");
                hrow.createCell(4).setCellValue("Amplitude");
                hrow.createCell(5).setCellValue("FrequencyHz");

                for (java.util.Map.Entry<String, OscillationStats> e : stageStats.entrySet()) {
                    Row row = summary.createRow(sr++);
                    row.createCell(0).setCellValue("stage");
                    row.createCell(1).setCellValue(e.getKey());
                    row.createCell(2).setCellValue(e.getValue().getSampleCount());
                    row.createCell(3).setCellValue(e.getValue().getMaxAbsError());
                    row.createCell(4).setCellValue(e.getValue().getAmplitude());
                    row.createCell(5).setCellValue(e.getValue().getFrequencyHz());
                }
                for (java.util.Map.Entry<String, OscillationStats> e : stageModeStats.entrySet()) {
                    Row row = summary.createRow(sr++);
                    row.createCell(0).setCellValue("stage+mode");
                    row.createCell(1).setCellValue(e.getKey());
                    row.createCell(2).setCellValue(e.getValue().getSampleCount());
                    row.createCell(3).setCellValue(e.getValue().getMaxAbsError());
                    row.createCell(4).setCellValue(e.getValue().getAmplitude());
                    row.createCell(5).setCellValue(e.getValue().getFrequencyHz());
                }
                for (int i=0;i<6;i++) summary.autoSizeColumn(i);

                if (imgFile != null && imgFile.exists()) {
                    // Add image to workbook
                    byte[] bytes = Files.readAllBytes(imgFile.toPath());
                    int pictureIdx = wb.addPicture(bytes, Workbook.PICTURE_TYPE_PNG);
                    XSSFSheet sheet = wb.createSheet("Graph");
                    XSSFDrawing drawing = sheet.createDrawingPatriarch();
                    ClientAnchor anchor = helper.createClientAnchor();
                    anchor.setCol1(0);
                    anchor.setRow1(0);
                    XSSFPicture pict = drawing.createPicture(anchor, pictureIdx);
                    pict.resize();
                }

                File xlsx = new File(f.getAbsolutePath() + ".xlsx");
                try (java.io.FileOutputStream fos = new java.io.FileOutputStream(xlsx)) {
                    wb.write(fos);
                }
                appendLog("Excel report saved to: " + xlsx.getAbsolutePath());
            } catch (Exception e) {
                appendLog("Failed to write Excel report: " + e.getMessage());
            }
        } catch (IOException ex) {
            appendLog("Failed to save report: " + ex.getMessage());
        }
    }

    // Simple container for parsed sample data to export to CSV/Excel
    private static class Sample {
        final Long elapsedMs;
        final Double setpoint;
        final Double pv;
        final Double processedAvg;
        final Double pidErr;
        final Double pidP;
        final Double pidI;
        final Double pidD;
        final Double pidOut;
        final Double sampleMin;
        final Double sampleMax;
        final Double cdabTemp;
        final String sensorId;

        Sample(Long elapsedMs, Double setpoint, Double pv, Double processedAvg, Double pidErr, Double pidP, Double pidI, Double pidD, Double pidOut, Double sampleMin, Double sampleMax, Double cdabTemp, String sensorId) {
            this.elapsedMs = elapsedMs;
            this.setpoint = setpoint;
            this.pv = pv;
            this.processedAvg = processedAvg;
            this.pidErr = pidErr;
            this.pidP = pidP;
            this.pidI = pidI;
            this.pidD = pidD;
            this.pidOut = pidOut;
            this.sampleMin = sampleMin;
            this.sampleMax = sampleMax;
            this.cdabTemp = cdabTemp;
            this.sensorId = sensorId;
        }
    }

    // Convert graph data to BufferedImage for saving as PNG
    private BufferedImage graphToImage() {
        int width = graph.getWidth();
        int height = graph.getHeight();
        BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g2 = img.createGraphics();
        try {
            // Render graph component to image
            graph.paint(g2);
            return img;
        } catch (Exception e) {
            appendLog("Failed to render graph to image: " + e.getMessage());
            return null;
        } finally {
            g2.dispose();
        }
    }

    // Simple inlined time-series graph using primitive double ring buffers (robust handling of NaN values)
    private static class TimeSeriesGraph extends JPanel {
        private final int capacity;
        private final double[] avgBuf;
        private final double[] spBuf;
        private int size = 0; // number of valid samples in buffer (<= capacity)
        private int next = 0; // next write index

        public TimeSeriesGraph(int capacity) {
            this.capacity = Math.max(10, capacity);
            this.avgBuf = new double[this.capacity];
            this.spBuf = new double[this.capacity];
            // initialize to NaN
            for (int i = 0; i < this.capacity; i++) { avgBuf[i] = Double.NaN; spBuf[i] = Double.NaN; }
            setBackground(Color.WHITE);
            setBorder(BorderFactory.createLineBorder(Color.GRAY));
        }

        // Called on EDT
        public synchronized void addSample(Double avg, Double sp) {
            double a = (avg == null) ? Double.NaN : avg.doubleValue();
            double s = (sp == null) ? Double.NaN : sp.doubleValue();
            avgBuf[next] = a;
            spBuf[next] = s;
            next = (next + 1) % capacity;
            if (size < capacity) size++;
            repaint();
        }

        // Clear the buffer
        public synchronized void clear() {
            for (int i = 0; i < capacity; i++) { avgBuf[i] = Double.NaN; spBuf[i] = Double.NaN; }
            size = 0;
            next = 0;
            repaint();
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                int w = getWidth();
                int h = getHeight();

                // copy buffer under lock to local arrays to avoid races
                int localSize;
                double[] localAvg;
                double[] localSp;
                synchronized (this) {
                    localSize = size;
                    localAvg = new double[localSize];
                    localSp = new double[localSize];
                    for (int i = 0; i < localSize; i++) {
                        int idx = (next - localSize + i + capacity) % capacity;
                        localAvg[i] = avgBuf[idx];
                        localSp[i] = spBuf[idx];
                    }
                }

                if (w <= 20 || h <= 20 || localSize <= 0) {
                    g2.setColor(Color.LIGHT_GRAY);
                    g2.drawString("No data", 8, 16);
                    return;
                }

                // compute min/max from data (ignore NaN)
                double min = Double.POSITIVE_INFINITY;
                double max = Double.NEGATIVE_INFINITY;
                for (int i = 0; i < localSize; i++) {
                    double av = localAvg[i];
                    double sp = localSp[i];
                    if (!Double.isNaN(av)) { min = Math.min(min, av); max = Math.max(max, av); }
                    if (!Double.isNaN(sp)) { min = Math.min(min, sp); max = Math.max(max, sp); }
                }
                if (min == Double.POSITIVE_INFINITY || max == Double.NEGATIVE_INFINITY) {
                    g2.setColor(Color.LIGHT_GRAY);
                    g2.drawString("No data", 8, 16);
                    return;
                }

                // add padding
                double pad = Math.max(0.5, (max - min) * 0.1);
                min -= pad; max += pad;
                if (min == max) { min -= 1; max += 1; }

                // draw grid lines
                g2.setColor(new Color(230,230,230));
                for (int i=0;i<5;i++) {
                    int y = 10 + i * (h - 20) / 4;
                    g2.drawLine(40, y, w - 6, y);
                }

                // axes
                g2.setColor(Color.DARK_GRAY);
                g2.drawRect(40, 6, w - 46, h - 12);

                // draw y labels
                g2.setColor(Color.BLACK);
                g2.setFont(g2.getFont().deriveFont(10f));
                for (int i=0;i<=4;i++) {
                    double v = max - i * (max - min) / 4.0;
                    String s = String.format("%.2f", v);
                    int y = 10 + i * (h - 20) / 4 + 4;
                    g2.drawString(s, 4, y);
                }

                int plotW = w - 46;
                int plotH = h - 12;

                // draw avg (blue)
                g2.setColor(new Color(30,120,200));
                g2.setStroke(new BasicStroke(2f));
                int n = localSize;
                for (int i=1;i<n;i++) {
                    double v1 = localAvg[i-1];
                    double v2 = localAvg[i];
                    if (!Double.isNaN(v1) && !Double.isNaN(v2)) {
                        int xi1 = 40 + (i-1) * plotW / Math.max(1, n-1);
                        int xi2 = 40 + i * plotW / Math.max(1, n-1);
                        int yi1 = 6 + (int) Math.round((max - v1) * (plotH) / (max - min));
                        int yi2 = 6 + (int) Math.round((max - v2) * (plotH) / (max - min));
                        g2.drawLine(xi1, yi1, xi2, yi2);
                    }
                }

                // draw setpoint (red, dashed)
                g2.setColor(new Color(200,60,60));
                Stroke old = g2.getStroke();
                float[] dash = {6f,6f};
                g2.setStroke(new BasicStroke(2f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_BEVEL, 1f, dash, 0f));
                for (int i=1;i<n;i++) {
                    double v1 = localSp[i-1];
                    double v2 = localSp[i];
                    if (!Double.isNaN(v1) && !Double.isNaN(v2)) {
                        int xi1 = 40 + (i-1) * plotW / Math.max(1, n-1);
                        int xi2 = 40 + i * plotW / Math.max(1, n-1);
                        int yi1 = 6 + (int) Math.round((max - v1) * (plotH) / (max - min));
                        int yi2 = 6 + (int) Math.round((max - v2) * (plotH) / (max - min));
                        g2.drawLine(xi1, yi1, xi2, yi2);
                    }
                }
                g2.setStroke(old);

                // legend
                g2.setFont(g2.getFont().deriveFont(11f));
                g2.setColor(new Color(30,120,200));
                g2.fillRect(w - 110, 10, 10, 6);
                g2.setColor(Color.BLACK);
                g2.drawString("Processed Avg", w - 94, 16);
                g2.setColor(new Color(200,60,60));
                g2.fillRect(w - 110, 26, 10, 6);
                g2.setColor(Color.BLACK);
                g2.drawString("Setpoint", w - 94, 32);

            } finally {
                g2.dispose();
            }
        }
    }

    // small accumulator for oscillation-like metrics
    private static class OscillationStats {
        private Double lastError = null;
        private int zeroCrossings = 0;
        private double maxAbsError = 0.0;
        private double minError = Double.POSITIVE_INFINITY; // signed
        private double maxError = Double.NEGATIVE_INFINITY; // signed
        private double sumAbsError = 0.0;
        private long samples = 0;
        // for average period estimation
        private Long lastZeroCrossingTime = null;
        private double sumPeriodsMs = 0.0;
        private int periodCount = 0;

        synchronized void addSample(Long timeMs, double pv, double sp) {
            double err = pv - sp; // signed
            double absErr = Math.abs(err);
            maxAbsError = Math.max(maxAbsError, absErr);
            // track signed min/max for amplitude estimation
            minError = Math.min(minError, err);
            maxError = Math.max(maxError, err);
            sumAbsError += absErr;
            samples++;

            if (lastError != null) {
                if ((lastError <= 0 && err > 0) || (lastError >= 0 && err < 0)) {
                    zeroCrossings++;
                    if (timeMs != null && lastZeroCrossingTime != null) {
                        sumPeriodsMs += (timeMs - lastZeroCrossingTime);
                        periodCount++;
                    }
                    if (timeMs != null) lastZeroCrossingTime = timeMs;
                }
            } else {
                // initialize lastZeroCrossingTime if this sample crosses zero
                if (err == 0 && timeMs != null) lastZeroCrossingTime = timeMs;
            }
            lastError = err;
        }

        synchronized String summary() {
            StringBuilder sb = new StringBuilder();
            sb.append("  Samples: ").append(samples).append("\n");
            sb.append(String.format("  Max abs error: %.6f\n", maxAbsError));
            double avgAbs = samples > 0 ? sumAbsError / samples : 0.0;
            sb.append(String.format("  Avg abs error: %.6f\n", avgAbs));
            sb.append("  Zero crossings: ").append(zeroCrossings).append("\n");
            double avgPeriod = periodCount > 0 ? sumPeriodsMs / periodCount : 0.0;
            sb.append(String.format("  Avg period between zero crossings (ms): %.1f\n", avgPeriod));
            double amplitude = (minError == Double.POSITIVE_INFINITY || maxError == Double.NEGATIVE_INFINITY) ? 0.0 : Math.abs(maxError - minError);
            sb.append(String.format("  Estimated amplitude (peak-to-peak error): %.6f\n", amplitude));
            double freqHz = avgPeriod > 0.0 ? 1000.0 / avgPeriod : 0.0;
            sb.append(String.format("  Estimated frequency (Hz): %.3f\n", freqHz));
            sb.append("\n");
            return sb.toString();
        }

        synchronized double getAmplitude() {
            return (minError == Double.POSITIVE_INFINITY || maxError == Double.NEGATIVE_INFINITY) ? 0.0 : Math.abs(maxError - minError);
        }

        synchronized double getFrequencyHz() {
            double avgPeriod = periodCount > 0 ? sumPeriodsMs / periodCount : 0.0;
            return avgPeriod > 0.0 ? 1000.0 / avgPeriod : 0.0;
        }
        synchronized long getSampleCount() { return samples; }
        synchronized double getMaxAbsError() { return maxAbsError; }
     }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(Main::new);
    }

    private void onFeedSample() {
        JFileChooser fc = new JFileChooser();
        fc.setDialogTitle("Select log file to feed samples from");
        int res = fc.showOpenDialog(frame);
        if (res != JFileChooser.APPROVE_OPTION) return;
        File file = fc.getSelectedFile();

        // Disable buttons to prevent interaction during feed
        startBtn.setEnabled(false);
        reportBtn.setEnabled(false);
        feedSampleBtn.setEnabled(false);

        // Background worker to read file and feed lines
        SwingWorker<Void, String> worker = new SwingWorker<Void, String>() {
            @Override
            protected Void doInBackground() {
                try (BufferedReader br = new BufferedReader(new FileReader(file))) {
                    String line;
                    while ((line = br.readLine()) != null) {
                        // Simulate real-time by sleeping (adjust rate as needed)
                        try { Thread.sleep(100); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); appendLog("Feed interrupted"); break; }
                        publish(line);
                    }
                } catch (IOException e) {
                    appendLog("Feed error: " + e.getMessage());
                }
                return null;
            }

            @Override
            protected void process(List<String> chunks) {
                for (String line : chunks) {
                    handleLine(line);
                }
            }

            @Override
            protected void done() {
                // Re-enable buttons after feed is complete
                startBtn.setEnabled(true);
                reportBtn.setEnabled(true);
                feedSampleBtn.setEnabled(true);
            }
        };
        worker.execute();
    }

    private void onTestGraph() {
        // Generate synthetic data: square wave for setpoint, random noise around setpoint for processed avg
        final double SP_MIN = 20.0;
        final double SP_MAX = 200.0;
        final double NOISE_LEVEL = 2.0;

        // Clear graph buffer before testing
        graph.clear();

        // Simple square wave + noise generator
        SwingWorker<Void, Double[]> worker = new SwingWorker<Void, Double[]>() {
            private double currentSp = SP_MIN;
            private boolean increasing = true;
            private int publishCount = 0;
            @Override
            protected Void doInBackground() {
                for (int i = 0; i < 1000; i++) {
                    if (isCancelled()) break;
                    try { Thread.sleep(10); } catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
                    double noise = (Math.random() - 0.5) * NOISE_LEVEL;
                    publish(new Double[]{currentSp + noise, currentSp});
                    if (increasing) {
                        currentSp += 0.1;
                        if (currentSp >= SP_MAX) increasing = false;
                    } else {
                        currentSp -= 0.1;
                        if (currentSp <= SP_MIN) increasing = true;
                    }
                }
                return null;
            }

            @Override
            protected void process(List<Double[]> chunks) {
                for (Double[] vals : chunks) {
                    graph.addSample(vals[0], vals[1]);
                    publishCount++;
                    if ((publishCount % 50) == 0) {
                        appendLog(String.format("TestGraph: published %d samples, last avg=%.2f sp=%.2f", publishCount, vals[0], vals[1]));
                    }
                }
            }
        };
        worker.execute();
    }
}
