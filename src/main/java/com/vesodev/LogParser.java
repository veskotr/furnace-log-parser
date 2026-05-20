package com.vesodev;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class LogParser {
    private static final Pattern ELAPSED_PATTERN = Pattern.compile("Elapsed:\\s*([0-9]+)\\s*ms");
    private static final Pattern STAGE_PHASE_PATTERN = Pattern.compile("Stage:\\s*([0-9]+),\\s*Phase:\\s*([0-9]+)");
    private static final Pattern SETPOINT_PATTERN = Pattern.compile("Setpoint:\\s*([0-9]+\\.?[0-9]*)\\s*C");

    private static final Pattern PID_PATTERN = Pattern.compile(
            "PID - .*SP:\\s*([0-9]+\\.?[0-9]*),\\s*PV:\\s*([0-9]+\\.?[0-9]*),\\s*err:\\s*([-+]?[0-9]+\\.?[0-9]*),\\s*dt:\\s*([0-9]+\\.?[0-9]*)s?,\\s*dyn_max:\\s*([0-9]+\\.?[0-9]*),\\s*P:\\s*([-+]?[0-9]+\\.?[0-9]*),\\s*I:\\s*([-+]?[0-9]+\\.?[0-9]*),\\s*D:\\s*([-+]?[0-9]+\\.?[0-9]*),\\s*Out:\\s*([-+]?[0-9]+\\.?[0-9]*)"
    );

    private static final Pattern SAMPLE_RANGE_PATTERN = Pattern.compile("Temperature samples range:\\s*([0-9]+\\.?[0-9]*)°C\\s*-\\s*([0-9]+\\.?[0-9]*)°C");
    private static final Pattern PROCESSED_AVG_PATTERN = Pattern.compile("Processed average temperature:\\s*([0-9]+\\.?[0-9]*)\\s*C");
    private static final Pattern CDAB_PATTERN = Pattern.compile("CDAB decode:.*→\\s*([0-9]+\\.?[0-9]*)\\s*C");
    private static final Pattern SENSOR_ID_PATTERN = Pattern.compile("updated device temp_sensor.*ID:\\s*([0-9]+)\\)" );

    public static ParsedData parse(String line) {
        ParsedData d = new ParsedData();
        if (line == null) return d;

        Matcher m = ELAPSED_PATTERN.matcher(line);
        if (m.find()) {
            try { d.setElapsedMs(Optional.of(Long.parseLong(m.group(1)))); } catch (NumberFormatException ignored) {}
        }
        m = STAGE_PHASE_PATTERN.matcher(line);
        if (m.find()) {
            try { d.setStage(Optional.of(Integer.parseInt(m.group(1)))); } catch (NumberFormatException ignored) {}
            try { d.setPhase(Optional.of(Integer.parseInt(m.group(2)))); } catch (NumberFormatException ignored) {}
        }
        m = SETPOINT_PATTERN.matcher(line);
        if (m.find()) {
            try { d.setSetpoint(Optional.of(Double.parseDouble(m.group(1)))); } catch (NumberFormatException ignored) {}
        }

        m = PID_PATTERN.matcher(line);
        if (m.find()) {
            try { d.setPidSp(Optional.of(Double.parseDouble(m.group(1)))); } catch (NumberFormatException ignored) {}
            try { d.setPidPv(Optional.of(Double.parseDouble(m.group(2)))); } catch (NumberFormatException ignored) {}
            try { d.setPidErr(Optional.of(Double.parseDouble(m.group(3)))); } catch (NumberFormatException ignored) {}
            try { d.setPidDt(Optional.of(Double.parseDouble(m.group(4)))); } catch (NumberFormatException ignored) {}
            try { d.setPidDynMax(Optional.of(Double.parseDouble(m.group(5)))); } catch (NumberFormatException ignored) {}
            try { d.setPidP(Optional.of(Double.parseDouble(m.group(6)))); } catch (NumberFormatException ignored) {}
            try { d.setPidI(Optional.of(Double.parseDouble(m.group(7)))); } catch (NumberFormatException ignored) {}
            try { d.setPidD(Optional.of(Double.parseDouble(m.group(8)))); } catch (NumberFormatException ignored) {}
            try { d.setPidOut(Optional.of(Double.parseDouble(m.group(9)))); } catch (NumberFormatException ignored) {}
        }

        m = SAMPLE_RANGE_PATTERN.matcher(line);
        if (m.find()) {
            try { d.setSampleMin(Optional.of(Double.parseDouble(m.group(1)))); } catch (NumberFormatException ignored) {}
            try { d.setSampleMax(Optional.of(Double.parseDouble(m.group(2)))); } catch (NumberFormatException ignored) {}
        }

        m = PROCESSED_AVG_PATTERN.matcher(line);
        if (m.find()) {
            try { d.setProcessedAvg(Optional.of(Double.parseDouble(m.group(1)))); } catch (NumberFormatException ignored) {}
        }

        m = CDAB_PATTERN.matcher(line);
        if (m.find()) {
            try { d.setCdabTemp(Optional.of(Double.parseDouble(m.group(1)))); } catch (NumberFormatException ignored) {}
        }

        m = SENSOR_ID_PATTERN.matcher(line);
        if (m.find()) {
            try { d.setSensorId(Optional.of(Integer.parseInt(m.group(1)))); } catch (NumberFormatException ignored) {}
        }

        return d;
    }

    public static class ParsedData {
        private Optional<Long> elapsedMs = Optional.empty();
        private Optional<Integer> stage = Optional.empty();
        private Optional<Integer> phase = Optional.empty();
        private Optional<Double> setpoint = Optional.empty();

        private Optional<Double> pidSp = Optional.empty();
        private Optional<Double> pidPv = Optional.empty();
        private Optional<Double> pidErr = Optional.empty();
        private Optional<Double> pidDt = Optional.empty();
        private Optional<Double> pidDynMax = Optional.empty();
        private Optional<Double> pidP = Optional.empty();
        private Optional<Double> pidI = Optional.empty();
        private Optional<Double> pidD = Optional.empty();
        private Optional<Double> pidOut = Optional.empty();

        private Optional<Double> sampleMin = Optional.empty();
        private Optional<Double> sampleMax = Optional.empty();
        private Optional<Double> processedAvg = Optional.empty();

        private Optional<Double> cdabTemp = Optional.empty();
        private Optional<Integer> sensorId = Optional.empty();

        public Optional<Long> getElapsedMs() { return elapsedMs; }
        public void setElapsedMs(Optional<Long> elapsedMs) { this.elapsedMs = elapsedMs; }
        public Optional<Integer> getStage() { return stage; }
        public void setStage(Optional<Integer> stage) { this.stage = stage; }
        public Optional<Integer> getPhase() { return phase; }
        public void setPhase(Optional<Integer> phase) { this.phase = phase; }
        public Optional<Double> getSetpoint() { return setpoint; }
        public void setSetpoint(Optional<Double> setpoint) { this.setpoint = setpoint; }

        public Optional<Double> getPidSp() { return pidSp; }
        public void setPidSp(Optional<Double> pidSp) { this.pidSp = pidSp; }
        public Optional<Double> getPidPv() { return pidPv; }
        public void setPidPv(Optional<Double> pidPv) { this.pidPv = pidPv; }
        public Optional<Double> getPidErr() { return pidErr; }
        public void setPidErr(Optional<Double> pidErr) { this.pidErr = pidErr; }
        public Optional<Double> getPidDt() { return pidDt; }
        public void setPidDt(Optional<Double> pidDt) { this.pidDt = pidDt; }
        public Optional<Double> getPidDynMax() { return pidDynMax; }
        public void setPidDynMax(Optional<Double> pidDynMax) { this.pidDynMax = pidDynMax; }
        public Optional<Double> getPidP() { return pidP; }
        public void setPidP(Optional<Double> pidP) { this.pidP = pidP; }
        public Optional<Double> getPidI() { return pidI; }
        public void setPidI(Optional<Double> pidI) { this.pidI = pidI; }
        public Optional<Double> getPidD() { return pidD; }
        public void setPidD(Optional<Double> pidD) { this.pidD = pidD; }
        public Optional<Double> getPidOut() { return pidOut; }
        public void setPidOut(Optional<Double> pidOut) { this.pidOut = pidOut; }

        public Optional<Double> getSampleMin() { return sampleMin; }
        public void setSampleMin(Optional<Double> sampleMin) { this.sampleMin = sampleMin; }
        public Optional<Double> getSampleMax() { return sampleMax; }
        public void setSampleMax(Optional<Double> sampleMax) { this.sampleMax = sampleMax; }
        public Optional<Double> getProcessedAvg() { return processedAvg; }
        public void setProcessedAvg(Optional<Double> processedAvg) { this.processedAvg = processedAvg; }

        public Optional<Double> getCdabTemp() { return cdabTemp; }
        public void setCdabTemp(Optional<Double> cdabTemp) { this.cdabTemp = cdabTemp; }
        public Optional<Integer> getSensorId() { return sensorId; }
        public void setSensorId(Optional<Integer> sensorId) { this.sensorId = sensorId; }
    }
}
