package com.vesodev.fx;

final class TuneRecommendation {
    final double pFactor;
    final double iFactor;
    final double dFactor;
    final String reason;

    TuneRecommendation(double pFactor, double iFactor, double dFactor, String reason) {
        this.pFactor = pFactor;
        this.iFactor = iFactor;
        this.dFactor = dFactor;
        this.reason = reason;
    }
}
