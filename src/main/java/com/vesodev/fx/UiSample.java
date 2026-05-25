package com.vesodev.fx;

import com.vesodev.LogParser;

final class UiSample {
    final String line;
    final LogParser.ParsedData data;
    final Long elapsed;
    final double x;
    final Double procAvg;
    final Double pv;
    final Double sp;
    final Double err;
    final Integer phaseType;

    UiSample(String line, LogParser.ParsedData data, Long elapsed, double x, Double procAvg, Double pv, Double sp, Double err, Integer phaseType) {
        this.line = line;
        this.data = data;
        this.elapsed = elapsed;
        this.x = x;
        this.procAvg = procAvg;
        this.pv = pv;
        this.sp = sp;
        this.err = err;
        this.phaseType = phaseType;
    }
}
