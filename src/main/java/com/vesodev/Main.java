package com.vesodev;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
    private final JButton startBtn = new JButton("Start");
    private final MonitorProcess monitor = new MonitorProcess();

    private static final Pattern NUMBER_PATTERN = Pattern.compile("[-+]?[0-9]*\\.?[0-9]+(?:[eE][-+]?[0-9]+)?");

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
        top.add(new JLabel("Command:"), BorderLayout.WEST);
        top.add(commandField, BorderLayout.CENTER);
        top.add(startBtn, BorderLayout.EAST);

        // Right side: grid of fields
        JPanel fields = new JPanel();
        fields.setLayout(new BoxLayout(fields, BoxLayout.Y_AXIS));

        // Graph on top
        fields.add(new JLabel("Temperature Graph (Processed Avg = blue, Setpoint = red):"));
        graph.setPreferredSize(new Dimension(320, 220));
        fields.add(graph);
        fields.add(Box.createVerticalStrut(8));

        fields.add(labelled(new JLabel("Elapsed (ms):"), elapsedLabel));
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
            String[] cmd = cmdText.split("\\s+");
            try {
                monitor.start(cmd, this::handleLine);
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

            setLabel(elapsedLabel, d.getElapsedMs().map(Object::toString).orElse(null));
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

            // Feed graph if we have processed average or setpoint
            Double procAvg = d.getProcessedAvg().orElse(null);
            Double sp = d.getSetpoint().orElse(null);
            if (procAvg != null || sp != null) {
                graph.addSample(procAvg, sp);
            }

            // Extract all numeric tokens on the line and show them
            Matcher nm = NUMBER_PATTERN.matcher(line);
            StringBuilder nums = new StringBuilder();
            while (nm.find()) {
                if (nums.length() > 0) nums.append(", ");
                nums.append(nm.group());
            }
            numbersArea.setText(nums.toString());
        });
    }

    private void setLabel(JLabel label, String value) {
        if (value == null) {
            // keep existing value
            return;
        }
        label.setText(value);
    }

    private void appendLog(String text) {
        logArea.append(text + "\n");
        logArea.setCaretPosition(logArea.getDocument().getLength());
    }

    // Simple inlined time-series graph that keeps a sliding window of samples and paints them.
    private static class TimeSeriesGraph extends JPanel {
        private final int capacity;
        private final Deque<Double> avgSeries = new ArrayDeque<>();
        private final Deque<Double> spSeries = new ArrayDeque<>();

        public TimeSeriesGraph(int capacity) {
            this.capacity = Math.max(10, capacity);
            setBackground(Color.WHITE);
            setBorder(BorderFactory.createLineBorder(Color.GRAY));
        }

        // Called on EDT
        public void addSample(Double avg, Double sp) {
            // add entries (nulls represented as NaN)
            if (avg == null) avg = Double.NaN;
            if (sp == null) sp = Double.NaN;

            if (avgSeries.size() >= capacity) avgSeries.removeFirst();
            if (spSeries.size() >= capacity) spSeries.removeFirst();
            avgSeries.addLast(avg);
            spSeries.addLast(sp);
            repaint();
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                int w = getWidth();
                int h = getHeight();
                if (w <= 10 || h <= 10) return;

                // compute min/max from data (ignore NaN)
                double min = Double.POSITIVE_INFINITY;
                double max = Double.NEGATIVE_INFINITY;
                for (Double v : avgSeries) if (!v.isNaN()) { min = Math.min(min, v); max = Math.max(max, v); }
                for (Double v : spSeries) if (!v.isNaN()) { min = Math.min(min, v); max = Math.max(max, v); }
                if (min == Double.POSITIVE_INFINITY || max == Double.NEGATIVE_INFINITY) {
                    // nothing to draw
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

                // plot series
                int n = Math.max(avgSeries.size(), spSeries.size());
                if (n <= 1) return;
                int plotW = w - 46;
                int plotH = h - 12;

                // helper to map index,value to coords
                Double[] avgArr = avgSeries.toArray(new Double[0]);
                Double[] spArr = spSeries.toArray(new Double[0]);
                int start = Math.max(0, n - capacity);

                // draw avg (blue)
                g2.setColor(new Color(30,120,200));
                g2.setStroke(new BasicStroke(2f));
                for (int i=1;i<n;i++) {
                    int xi1 = 40 + (i-1) * plotW / Math.max(1, n-1);
                    int xi2 = 40 + i * plotW / Math.max(1, n-1);
                    Double yv1 = i-1 < avgArr.length ? avgArr[i-1] : Double.NaN;
                    Double yv2 = i < avgArr.length ? avgArr[i] : Double.NaN;
                    if (yv1 != null && yv2 != null && !yv1.isNaN() && !yv2.isNaN()) {
                        int yi1 = 6 + (int) Math.round((max - yv1) * (plotH) / (max - min));
                        int yi2 = 6 + (int) Math.round((max - yv2) * (plotH) / (max - min));
                        g2.drawLine(xi1, yi1, xi2, yi2);
                    }
                }

                // draw setpoint (red, dashed)
                g2.setColor(new Color(200,60,60));
                Stroke old = g2.getStroke();
                float[] dash = {6f,6f};
                g2.setStroke(new BasicStroke(2f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_BEVEL, 1f, dash, 0f));
                for (int i=1;i<n;i++) {
                    int xi1 = 40 + (i-1) * plotW / Math.max(1, n-1);
                    int xi2 = 40 + i * plotW / Math.max(1, n-1);
                    Double yv1 = i-1 < spArr.length ? spArr[i-1] : Double.NaN;
                    Double yv2 = i < spArr.length ? spArr[i] : Double.NaN;
                    if (yv1 != null && yv2 != null && !yv1.isNaN() && !yv2.isNaN()) {
                        int yi1 = 6 + (int) Math.round((max - yv1) * (plotH) / (max - min));
                        int yi2 = 6 + (int) Math.round((max - yv2) * (plotH) / (max - min));
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

    public static void main(String[] args) {
        SwingUtilities.invokeLater(Main::new);
    }
}