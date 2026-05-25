package com.vesodev.fx;

import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

final class FurnaceReportService {
    private static final String[] SAMPLE_HEADERS = new String[] {
            "elapsed_ms", "chart_x_ms", "stage", "phase", "setpoint_c", "pv_c", "processed_avg_c", "pid_err", "pid_p", "pid_i", "pid_d", "pid_out"
    };

    ExportResult exportXlsx(Path sessionCsv,
                            Path outputXlsx,
                            ReportScope scope,
                            double visibleStartMs,
                            double visibleEndMs) throws IOException {
        Summary summary = summarize(sessionCsv, scope, visibleStartMs, visibleEndMs);

        try (SXSSFWorkbook wb = new SXSSFWorkbook(200)) {
            wb.setCompressTempFiles(true);

            Sheet summarySheet = wb.createSheet("Summary");
            writeSummary(summarySheet, summary, sessionCsv, scope, visibleStartMs, visibleEndMs);

            Sheet pidSheet = wb.createSheet("PID Analysis");
            writePidAnalysis(pidSheet, summary.pidAnalysis);

            Sheet samplesSheet = wb.createSheet("Samples");
            writeSamples(samplesSheet, sessionCsv, scope, visibleStartMs, visibleEndMs);

            try (OutputStream out = Files.newOutputStream(outputXlsx)) {
                wb.write(out);
            }
            wb.dispose();
        }

        return new ExportResult(summary.matchedRows, summary.durationMs, summary.maxTemp, summary.maxAbsErr, outputXlsx);
    }

    private Summary summarize(Path sessionCsv,
                              ReportScope scope,
                              double visibleStartMs,
                              double visibleEndMs) throws IOException {
        Summary s = new Summary();
        StageAccumulator rampAcc = new StageAccumulator(0);
        StageAccumulator holdAcc = new StageAccumulator(1);
        Integer lastKnownPhaseType = null;

        try (BufferedReader r = Files.newBufferedReader(sessionCsv, StandardCharsets.UTF_8)) {
            String line = r.readLine();
            if (line == null) {
                return s;
            }

            while ((line = r.readLine()) != null) {
                SampleRow row = parse(line);
                if (row == null || !inScope(row, scope, visibleStartMs, visibleEndMs)) {
                    continue;
                }

                s.matchedRows++;
                if (s.firstElapsedMs == null && row.elapsedMs != null) {
                    s.firstElapsedMs = row.elapsedMs;
                }
                if (row.elapsedMs != null) {
                    s.lastElapsedMs = row.elapsedMs;
                }

                double temp = row.processedAvg != null ? row.processedAvg : (row.pv != null ? row.pv : Double.NaN);
                if (!Double.isNaN(temp)) {
                    s.maxTemp = Math.max(s.maxTemp, temp);
                    s.minTemp = Math.min(s.minTemp, temp);
                    s.sumTemp += temp;
                    s.tempCount++;
                }

                if (row.pidErr != null) {
                    double absErr = Math.abs(row.pidErr);
                    s.maxAbsErr = Math.max(s.maxAbsErr, absErr);
                    s.sumAbsErr += absErr;
                    s.errCount++;
                }

                Integer phaseType = phaseType(row.phase);
                if (phaseType == null) {
                    phaseType = phaseType(row.stage);
                }
                if (phaseType != null) {
                    lastKnownPhaseType = phaseType;
                } else {
                    phaseType = lastKnownPhaseType;
                }

                if (phaseType != null) {
                    if (phaseType == 0) {
                        rampAcc.add(row);
                    } else if (phaseType == 1) {
                        holdAcc.add(row);
                    }
                }
            }
        }

        if (s.firstElapsedMs != null && s.lastElapsedMs != null) {
            s.durationMs = Math.max(0L, s.lastElapsedMs - s.firstElapsedMs);
        }
        s.pidAnalysis = new PidAnalysis(toStageMetrics(rampAcc), toStageMetrics(holdAcc));
        return s;
    }

    private void writeSummary(Sheet sheet,
                              Summary s,
                              Path source,
                              ReportScope scope,
                              double visibleStartMs,
                              double visibleEndMs) {
        int rowIdx = 0;
        rowIdx = kv(sheet, rowIdx, "Generated", LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
        rowIdx = kv(sheet, rowIdx, "Source CSV", source.toString());
        rowIdx = kv(sheet, rowIdx, "Scope", scope.name());
        if (scope == ReportScope.VISIBLE_WINDOW) {
            rowIdx = kv(sheet, rowIdx, "Visible Window Start (ms)", String.format("%.0f", visibleStartMs));
            rowIdx = kv(sheet, rowIdx, "Visible Window End (ms)", String.format("%.0f", visibleEndMs));
        }
        rowIdx = kv(sheet, rowIdx, "Sample Rows", Long.toString(s.matchedRows));
        rowIdx = kv(sheet, rowIdx, "Duration (ms)", Long.toString(s.durationMs));
        rowIdx = kv(sheet, rowIdx, "Max Temperature (C)", number(s.maxTemp));
        rowIdx = kv(sheet, rowIdx, "Min Temperature (C)", number(s.minTemp));
        rowIdx = kv(sheet, rowIdx, "Avg Temperature (C)", s.tempCount == 0 ? "" : String.format("%.4f", s.sumTemp / s.tempCount));
        rowIdx = kv(sheet, rowIdx, "Max Abs PID Error", number(s.maxAbsErr));
        rowIdx = kv(sheet, rowIdx, "Avg Abs PID Error", s.errCount == 0 ? "" : String.format("%.6f", s.sumAbsErr / s.errCount));
        rowIdx = kv(sheet, rowIdx, "", "");
        rowIdx = kv(sheet, rowIdx, "PID Analysis", "See PID Analysis sheet");
        rowIdx = kv(sheet, rowIdx, "Phase 0 Samples (Ramp)", Long.toString(s.pidAnalysis.ramp.errorSamples));
        kv(sheet, rowIdx, "Phase 1 Samples (Hold)", Long.toString(s.pidAnalysis.hold.errorSamples));

        sheet.setColumnWidth(0, 9000);
        sheet.setColumnWidth(1, 12000);
    }

    private void writePidAnalysis(Sheet sheet, PidAnalysis analysis) {
        int rowIdx = 0;
        Row header = sheet.createRow(rowIdx++);
        header.createCell(0).setCellValue("Metric");
        header.createCell(1).setCellValue("Phase 0 (Ramp)");
        header.createCell(2).setCellValue("Phase 1 (Hold)");

        rowIdx = metricRow(sheet, rowIdx, "Error Samples", Long.toString(analysis.ramp.errorSamples), Long.toString(analysis.hold.errorSamples));
        rowIdx = metricRow(sheet, rowIdx, "Duration (ms)", Long.toString(analysis.ramp.durationMs), Long.toString(analysis.hold.durationMs));
        rowIdx = metricRow(sheet, rowIdx, "Mean Error", number(analysis.ramp.meanError), number(analysis.hold.meanError));
        rowIdx = metricRow(sheet, rowIdx, "Mean Abs Error", number(analysis.ramp.meanAbsError), number(analysis.hold.meanAbsError));
        rowIdx = metricRow(sheet, rowIdx, "RMSE", number(analysis.ramp.rmse), number(analysis.hold.rmse));
        rowIdx = metricRow(sheet, rowIdx, "Error StdDev", number(analysis.ramp.stdDev), number(analysis.hold.stdDev));
        rowIdx = metricRow(sheet, rowIdx, "Max Abs Error", number(analysis.ramp.maxAbsError), number(analysis.hold.maxAbsError));
        rowIdx = metricRow(sheet, rowIdx, "Avg Peak-to-Peak Error", number(analysis.ramp.avgPeakToPeak), number(analysis.hold.avgPeakToPeak));
        rowIdx = metricRow(sheet, rowIdx, "Max Peak-to-Peak Error", number(analysis.ramp.maxPeakToPeak), number(analysis.hold.maxPeakToPeak));
        rowIdx = metricRow(sheet, rowIdx, "Zero Crossings", Long.toString(analysis.ramp.zeroCrossings), Long.toString(analysis.hold.zeroCrossings));
        rowIdx = metricRow(sheet, rowIdx, "Zero Crossings / min", number(analysis.ramp.zeroCrossingsPerMin), number(analysis.hold.zeroCrossingsPerMin));
        rowIdx = metricRow(sheet, rowIdx, "Settled Ratio", percent(analysis.ramp.settledRatio), percent(analysis.hold.settledRatio));
        rowIdx = metricRow(sheet, rowIdx, "Avg Logged P", number(analysis.ramp.avgP), number(analysis.hold.avgP));
        rowIdx = metricRow(sheet, rowIdx, "Avg Logged I", number(analysis.ramp.avgI), number(analysis.hold.avgI));
        rowIdx = metricRow(sheet, rowIdx, "Avg Logged D", number(analysis.ramp.avgD), number(analysis.hold.avgD));
        rowIdx = metricRow(sheet, rowIdx, "Suggested P", suggestion(analysis.ramp.avgP, analysis.ramp.pFactor), suggestion(analysis.hold.avgP, analysis.hold.pFactor));
        rowIdx = metricRow(sheet, rowIdx, "Suggested I", suggestion(analysis.ramp.avgI, analysis.ramp.iFactor), suggestion(analysis.hold.avgI, analysis.hold.iFactor));
        rowIdx = metricRow(sheet, rowIdx, "Suggested D", suggestion(analysis.ramp.avgD, analysis.ramp.dFactor), suggestion(analysis.hold.avgD, analysis.hold.dFactor));
        rowIdx = metricRow(sheet, rowIdx, "Reasoning", analysis.ramp.recommendation, analysis.hold.recommendation);

        rowIdx += 1;
        Row note = sheet.createRow(rowIdx);
        note.createCell(0).setCellValue("Note");
        note.createCell(1).setCellValue("Suggestions are heuristic and intended as safe first-step tuning adjustments.");

        sheet.setColumnWidth(0, 8000);
        sheet.setColumnWidth(1, 15000);
        sheet.setColumnWidth(2, 15000);
    }

    private void writeSamples(Sheet sheet,
                              Path sessionCsv,
                              ReportScope scope,
                              double visibleStartMs,
                              double visibleEndMs) throws IOException {
        int rowIdx = 0;
        Row header = sheet.createRow(rowIdx++);
        for (int i = 0; i < SAMPLE_HEADERS.length; i++) {
            header.createCell(i).setCellValue(SAMPLE_HEADERS[i]);
        }

        try (BufferedReader r = Files.newBufferedReader(sessionCsv, StandardCharsets.UTF_8)) {
            String line = r.readLine();
            if (line == null) {
                return;
            }

            while ((line = r.readLine()) != null) {
                SampleRow row = parse(line);
                if (row == null || !inScope(row, scope, visibleStartMs, visibleEndMs)) {
                    continue;
                }

                Row x = sheet.createRow(rowIdx++);
                writeCell(x, 0, row.elapsedMs);
                writeCell(x, 1, row.chartXMs);
                writeCell(x, 2, row.stage);
                writeCell(x, 3, row.phase);
                writeCell(x, 4, row.setpoint);
                writeCell(x, 5, row.pv);
                writeCell(x, 6, row.processedAvg);
                writeCell(x, 7, row.pidErr);
                writeCell(x, 8, row.pidP);
                writeCell(x, 9, row.pidI);
                writeCell(x, 10, row.pidD);
                writeCell(x, 11, row.pidOut);
            }
        }

        for (int i = 0; i < SAMPLE_HEADERS.length; i++) {
            sheet.setColumnWidth(i, 4200);
        }
    }

    private boolean inScope(SampleRow row, ReportScope scope, double visibleStartMs, double visibleEndMs) {
        if (scope == ReportScope.FULL_SESSION) {
            return true;
        }
        return row.chartXMs >= visibleStartMs && row.chartXMs <= visibleEndMs;
    }

    private int kv(Sheet sheet, int rowIdx, String k, String v) {
        Row r = sheet.createRow(rowIdx);
        r.createCell(0).setCellValue(k);
        r.createCell(1).setCellValue(v == null ? "" : v);
        return rowIdx + 1;
    }

    private int metricRow(Sheet sheet, int rowIdx, String metric, String ramp, String hold) {
        Row r = sheet.createRow(rowIdx);
        r.createCell(0).setCellValue(metric);
        r.createCell(1).setCellValue(ramp == null ? "" : ramp);
        r.createCell(2).setCellValue(hold == null ? "" : hold);
        return rowIdx + 1;
    }

    private String number(double value) {
        if (Double.isInfinite(value) || Double.isNaN(value)) {
            return "";
        }
        return String.format("%.6f", value);
    }

    private void writeCell(Row row, int idx, Number value) {
        if (value == null) {
            row.createCell(idx).setBlank();
        } else {
            row.createCell(idx).setCellValue(value.doubleValue());
        }
    }

    private String percent(double ratio) {
        if (Double.isNaN(ratio) || Double.isInfinite(ratio)) {
            return "";
        }
        return String.format(Locale.US, "%.2f%%", ratio * 100.0);
    }

    private String suggestion(double base, double factor) {
        if (Double.isNaN(base)) {
            return "";
        }
        double suggested = base * factor;
        return String.format(Locale.US, "%.6f (x%.2f)", suggested, factor);
    }

    private Integer phaseType(Double value) {
        if (value == null || value.isNaN() || value.isInfinite()) {
            return null;
        }
        int rounded = (int) Math.round(value);
        if (Math.abs(value - rounded) > 1e-6) {
            return null;
        }
        return (rounded == 0 || rounded == 1) ? rounded : null;
    }

    private StageMetrics toStageMetrics(StageAccumulator acc) {
        StageMetrics m = new StageMetrics(acc.stageType);
        m.errorSamples = acc.errorSamples;
        if (acc.firstElapsedMs != null && acc.lastElapsedMs != null) {
            m.durationMs = Math.max(0L, acc.lastElapsedMs - acc.firstElapsedMs);
        }
        if (acc.errorSamples > 0) {
            m.meanError = acc.sumErr / acc.errorSamples;
            m.meanAbsError = acc.sumAbsErr / acc.errorSamples;
            m.rmse = Math.sqrt(acc.sumErrSq / acc.errorSamples);
            double variance = (acc.sumErrSq / acc.errorSamples) - (m.meanError * m.meanError);
            m.stdDev = Math.sqrt(Math.max(0.0, variance));
            m.maxAbsError = acc.maxAbsErr;
        }
        m.zeroCrossings = acc.zeroCrossings;
        if (m.durationMs > 0) {
            m.zeroCrossingsPerMin = (acc.zeroCrossings * 60_000.0) / m.durationMs;
        }
        if (acc.peakToPeakCount > 0) {
            m.avgPeakToPeak = acc.sumPeakToPeak / acc.peakToPeakCount;
            m.maxPeakToPeak = acc.maxPeakToPeak;
        }
        if (acc.settledSamples > 0) {
            m.settledRatio = acc.settledWithinTol / (double) acc.settledSamples;
        }
        m.avgP = acc.pCount == 0 ? Double.NaN : acc.sumP / acc.pCount;
        m.avgI = acc.iCount == 0 ? Double.NaN : acc.sumI / acc.iCount;
        m.avgD = acc.dCount == 0 ? Double.NaN : acc.sumD / acc.dCount;

        applyRecommendation(m);
        return m;
    }

    private void applyRecommendation(StageMetrics m) {
        m.pFactor = 1.00;
        m.iFactor = 1.00;
        m.dFactor = 1.00;

        if (m.errorSamples < 20) {
            m.recommendation = "Not enough samples for stable tuning guidance.";
            return;
        }

        boolean oscillatory = m.zeroCrossingsPerMin >= 8.0
                || (m.avgPeakToPeak >= 2.5 && m.stdDev >= 1.2)
                || (m.meanAbsError > 0.0 && m.avgPeakToPeak > (m.meanAbsError * 1.8));
        boolean biased = Math.abs(m.meanError) >= (m.stageType == 0 ? 0.8 : 0.5);
        boolean slowResponse = m.meanAbsError >= (m.stageType == 0 ? 1.8 : 1.2) && m.zeroCrossingsPerMin < 3.0;

        if (oscillatory) {
            if (m.stageType == 0) {
                m.pFactor = 0.90;
                m.iFactor = 0.90;
                m.dFactor = 1.20;
                m.recommendation = "Ramp looks oscillatory: reduce P/I and increase D to damp swing while tracking setpoint changes.";
            } else {
                m.pFactor = 0.85;
                m.iFactor = 0.80;
                m.dFactor = 1.25;
                m.recommendation = "Hold looks oscillatory: stronger damping (lower P/I, higher D) to reduce sustained error cycling.";
            }
            return;
        }

        if (slowResponse || (biased && m.meanError > 0)) {
            if (m.stageType == 0) {
                m.pFactor = 1.10;
                m.iFactor = 1.10;
                m.dFactor = 1.00;
                m.recommendation = "Ramp appears sluggish or below setpoint: raise P and I moderately for faster convergence.";
            } else {
                m.pFactor = 1.05;
                m.iFactor = 1.15;
                m.dFactor = 1.00;
                m.recommendation = "Hold shows steady offset below setpoint: raise I (and slightly P) to remove residual bias.";
            }
            return;
        }

        if (biased && m.meanError < 0) {
            m.pFactor = 0.95;
            m.iFactor = 0.95;
            m.dFactor = 1.05;
            m.recommendation = "Mean error is negative (overshoot tendency): slightly lower P/I and add a bit of D.";
            return;
        }

        m.recommendation = "Current tuning appears balanced for this phase; keep coefficients and monitor after longer runs.";
    }

    private SampleRow parse(String csvLine) {
        String[] p = csvLine.split(",", -1);
        if (p.length < 12) {
            return null;
        }
        SampleRow s = new SampleRow();
        s.elapsedMs = parseLong(p[0]);
        Double x = parseDouble(p[1]);
        s.chartXMs = x == null ? 0.0 : x;
        s.stage = parseDouble(p[2]);
        s.phase = parseDouble(p[3]);
        s.setpoint = parseDouble(p[4]);
        s.pv = parseDouble(p[5]);
        s.processedAvg = parseDouble(p[6]);
        s.pidErr = parseDouble(p[7]);
        s.pidP = parseDouble(p[8]);
        s.pidI = parseDouble(p[9]);
        s.pidD = parseDouble(p[10]);
        s.pidOut = parseDouble(p[11]);
        return s;
    }

    private Long parseLong(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(s.trim());
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private Double parseDouble(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        try {
            return Double.parseDouble(s.trim());
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    static final class ExportResult {
        final long rows;
        final long durationMs;
        final double maxTemp;
        final double maxAbsErr;
        final Path outputFile;

        ExportResult(long rows, long durationMs, double maxTemp, double maxAbsErr, Path outputFile) {
            this.rows = rows;
            this.durationMs = durationMs;
            this.maxTemp = maxTemp;
            this.maxAbsErr = maxAbsErr;
            this.outputFile = outputFile;
        }
    }

    private static final class Summary {
        long matchedRows;
        Long firstElapsedMs;
        Long lastElapsedMs;
        long durationMs;
        double maxTemp = Double.NEGATIVE_INFINITY;
        double minTemp = Double.POSITIVE_INFINITY;
        double sumTemp;
        long tempCount;
        double maxAbsErr;
        double sumAbsErr;
        long errCount;
        PidAnalysis pidAnalysis = new PidAnalysis(new StageMetrics(0), new StageMetrics(1));
    }

    private static final class PidAnalysis {
        final StageMetrics ramp;
        final StageMetrics hold;

        private PidAnalysis(StageMetrics ramp, StageMetrics hold) {
            this.ramp = ramp;
            this.hold = hold;
        }
    }

    private static final class StageAccumulator {
        final int stageType;
        long errorSamples;
        Long firstElapsedMs;
        Long lastElapsedMs;
        double sumErr;
        double sumAbsErr;
        double sumErrSq;
        double maxAbsErr;
        long zeroCrossings;

        boolean hasLastErr;
        double lastErr;
        int lastNonZeroErrSign;
        int lastDeltaSign;
        boolean hasLastExtremum;
        double lastExtremumErr;
        double sumPeakToPeak;
        double maxPeakToPeak;
        long peakToPeakCount;

        long settledSamples;
        long settledWithinTol;

        double sumP;
        double sumI;
        double sumD;
        long pCount;
        long iCount;
        long dCount;

        private StageAccumulator(int stageType) {
            this.stageType = stageType;
        }

        void add(SampleRow row) {
            if (row.elapsedMs != null) {
                if (firstElapsedMs == null) {
                    firstElapsedMs = row.elapsedMs;
                }
                lastElapsedMs = row.elapsedMs;
            }

            if (row.pidP != null) {
                sumP += row.pidP;
                pCount++;
            }
            if (row.pidI != null) {
                sumI += row.pidI;
                iCount++;
            }
            if (row.pidD != null) {
                sumD += row.pidD;
                dCount++;
            }

            if (row.pidErr == null || row.pidErr.isNaN() || row.pidErr.isInfinite()) {
                return;
            }

            double err = row.pidErr;
            errorSamples++;
            sumErr += err;
            sumAbsErr += Math.abs(err);
            sumErrSq += err * err;
            maxAbsErr = Math.max(maxAbsErr, Math.abs(err));

            int sign = err > 0 ? 1 : (err < 0 ? -1 : 0);
            if (sign != 0) {
                if (lastNonZeroErrSign != 0 && sign != lastNonZeroErrSign) {
                    zeroCrossings++;
                }
                lastNonZeroErrSign = sign;
            }

            if (hasLastErr) {
                double delta = err - lastErr;
                int deltaSign = delta > 0 ? 1 : (delta < 0 ? -1 : 0);
                if (lastDeltaSign != 0 && deltaSign != 0 && deltaSign != lastDeltaSign) {
                    if (hasLastExtremum) {
                        double p2p = Math.abs(lastErr - lastExtremumErr);
                        sumPeakToPeak += p2p;
                        maxPeakToPeak = Math.max(maxPeakToPeak, p2p);
                        peakToPeakCount++;
                    }
                    lastExtremumErr = lastErr;
                    hasLastExtremum = true;
                }
                if (deltaSign != 0) {
                    lastDeltaSign = deltaSign;
                }
            }
            lastErr = err;
            hasLastErr = true;

            if (row.setpoint != null && !row.setpoint.isNaN() && !row.setpoint.isInfinite()) {
                double tolerance = Math.max(0.5, Math.abs(row.setpoint) * 0.01);
                settledSamples++;
                if (Math.abs(err) <= tolerance) {
                    settledWithinTol++;
                }
            }
        }
    }

    private static final class StageMetrics {
        final int stageType;
        long errorSamples;
        long durationMs;
        double meanError;
        double meanAbsError;
        double rmse;
        double stdDev;
        double maxAbsError;
        long zeroCrossings;
        double zeroCrossingsPerMin;
        double avgPeakToPeak;
        double maxPeakToPeak;
        double settledRatio;
        double avgP = Double.NaN;
        double avgI = Double.NaN;
        double avgD = Double.NaN;
        double pFactor = 1.0;
        double iFactor = 1.0;
        double dFactor = 1.0;
        String recommendation = "";

        private StageMetrics(int stageType) {
            this.stageType = stageType;
        }
    }

    private static final class SampleRow {
        Long elapsedMs;
        double chartXMs;
        Double stage;
        Double phase;
        Double setpoint;
        Double pv;
        Double processedAvg;
        Double pidErr;
        Double pidP;
        Double pidI;
        Double pidD;
        Double pidOut;
    }
}
