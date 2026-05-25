package com.vesodev.fx;

final class StageLiveStats {
    final int stageType;
    long samples;
    Long firstElapsedMs;
    Long lastElapsedMs;
    double sumErr;
    double sumAbsErr;
    double sumErrSq;
    long zeroCrossings;
    int lastNonZeroSign;
    boolean hasLastErr;
    double lastErr;
    int lastDeltaSign;
    boolean hasLastExtremum;
    double lastExtremumErr;
    double sumPeakToPeak;
    double maxPeakToPeak;
    long peakToPeakCount;
    double sumP;
    double sumI;
    double sumD;
    long pCount;
    long iCount;
    long dCount;

    StageLiveStats(int stageType) {
        this.stageType = stageType;
    }

    void reset() {
        samples = 0;
        firstElapsedMs = null;
        lastElapsedMs = null;
        sumErr = 0;
        sumAbsErr = 0;
        sumErrSq = 0;
        zeroCrossings = 0;
        lastNonZeroSign = 0;
        hasLastErr = false;
        lastErr = 0;
        lastDeltaSign = 0;
        hasLastExtremum = false;
        lastExtremumErr = 0;
        sumPeakToPeak = 0;
        maxPeakToPeak = 0;
        peakToPeakCount = 0;
        sumP = 0;
        sumI = 0;
        sumD = 0;
        pCount = 0;
        iCount = 0;
        dCount = 0;
    }

    void add(Long elapsed, double err, Double p, Double i, Double d) {
        if (elapsed != null) {
            if (firstElapsedMs == null) {
                firstElapsedMs = elapsed;
            }
            lastElapsedMs = elapsed;
        }

        samples++;
        sumErr += err;
        sumAbsErr += Math.abs(err);
        sumErrSq += err * err;

        int sign = err > 0 ? 1 : (err < 0 ? -1 : 0);
        if (sign != 0) {
            if (lastNonZeroSign != 0 && sign != lastNonZeroSign) {
                zeroCrossings++;
            }
            lastNonZeroSign = sign;
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

        if (p != null && !p.isNaN() && !p.isInfinite()) {
            sumP += p;
            pCount++;
        }
        if (i != null && !i.isNaN() && !i.isInfinite()) {
            sumI += i;
            iCount++;
        }
        if (d != null && !d.isNaN() && !d.isInfinite()) {
            sumD += d;
            dCount++;
        }
    }

    double meanError() {
        return samples == 0 ? Double.NaN : sumErr / samples;
    }

    double meanAbsError() {
        return samples == 0 ? Double.NaN : sumAbsErr / samples;
    }

    double rmse() {
        return samples == 0 ? Double.NaN : Math.sqrt(sumErrSq / samples);
    }

    double stdDev() {
        if (samples == 0) {
            return Double.NaN;
        }
        double mean = meanError();
        double variance = (sumErrSq / samples) - (mean * mean);
        return Math.sqrt(Math.max(0.0, variance));
    }

    double avgPeakToPeak() {
        return peakToPeakCount == 0 ? 0.0 : sumPeakToPeak / peakToPeakCount;
    }

    double zeroCrossingsPerMinute() {
        if (firstElapsedMs == null || lastElapsedMs == null) {
            return 0.0;
        }
        long duration = Math.max(1L, lastElapsedMs - firstElapsedMs);
        return zeroCrossings * 60_000.0 / duration;
    }

    double avgP() {
        return pCount == 0 ? Double.NaN : sumP / pCount;
    }

    double avgI() {
        return iCount == 0 ? Double.NaN : sumI / iCount;
    }

    double avgD() {
        return dCount == 0 ? Double.NaN : sumD / dCount;
    }
}
