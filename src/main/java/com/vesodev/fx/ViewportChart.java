package com.vesodev.fx;

import io.fair_acc.chartfx.XYChart;
import io.fair_acc.chartfx.axes.spi.DefaultNumericAxis;
import io.fair_acc.chartfx.plugins.CrosshairIndicator;
import io.fair_acc.chartfx.plugins.DataPointTooltip;
import io.fair_acc.chartfx.plugins.EditAxis;
import io.fair_acc.chartfx.plugins.Zoomer;
import io.fair_acc.chartfx.renderer.ErrorStyle;
import io.fair_acc.chartfx.renderer.spi.ErrorDataSetRenderer;
import io.fair_acc.dataset.spi.CircularDoubleErrorDataSet;
import javafx.beans.property.DoubleProperty;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.layout.BorderPane;
import javafx.scene.paint.Color;
import javafx.scene.text.Text;

final class ViewportChart extends BorderPane {
    private static final int DEFAULT_SERIES_CAPACITY = 120_000;

    private final DefaultNumericAxis xAxis = new DefaultNumericAxis("Elapsed Time", "ms");
    private final DefaultNumericAxis yAxis = new DefaultNumericAxis("Temperature (C) / PID Error", "");
    private final XYChart chart = new XYChart(xAxis, yAxis);
    private final ErrorDataSetRenderer primaryRenderer;

    private final DoubleProperty visibleStartMs = new SimpleDoubleProperty(0.0);
    private final DoubleProperty visibleSpanMs = new SimpleDoubleProperty(60_000.0);

    private boolean fixedYRange;
    private double fixedYMin;
    private double fixedYMax;
    private int seriesCapacity = DEFAULT_SERIES_CAPACITY;

    ViewportChart() {
        chart.getStyleClass().add("furnace-chart");
        chart.setAnimated(false);
        chart.setLegendVisible(true);
        chart.setTitle("Furnace Temperature and PID Error");
        chart.setStyle("-fx-background-color:#161b22; -color-dataset-1:#00d4ff; -color-dataset-2:#ffd166; -color-dataset-3:#ff4fa3;");

        primaryRenderer = (ErrorDataSetRenderer) chart.getRenderers().get(0);
        primaryRenderer.setDrawBars(false);
        primaryRenderer.setDrawMarker(false);
        primaryRenderer.setErrorStyle(ErrorStyle.NONE);
        primaryRenderer.setAllowNaNs(true);

        chart.getPlugins().addAll(new Zoomer(), new EditAxis(), new CrosshairIndicator(), new DataPointTooltip());
        chart.addEventFilter(javafx.scene.input.MouseEvent.MOUSE_MOVED, e -> Platform.runLater(this::enforceDarkIndicatorLabelStyle));

        xAxis.setAutoRanging(false);
        xAxis.setAutoGrowRanging(false);
        yAxis.setAutoRanging(true);
        yAxis.setAutoRangePadding(0.05);

        visibleStartMs.addListener((obs, oldVal, newVal) -> applyVisibleWindow());
        visibleSpanMs.addListener((obs, oldVal, newVal) -> applyVisibleWindow());

        setCenter(chart);
        applyVisibleWindow();
    }

    void setSeriesCapacity(int capacity) {
        this.seriesCapacity = Math.max(1_000, capacity);
    }

    DoubleProperty visibleStartMsProperty() {
        return visibleStartMs;
    }

    DoubleProperty visibleSpanMsProperty() {
        return visibleSpanMs;
    }

    void setVisibleWindow(double startMs, double spanMs) {
        visibleStartMs.set(Math.max(0.0, startMs));
        visibleSpanMs.set(Math.max(1.0, spanMs));
    }

    void setTitleAndLabels(String title, String xLabel, String yLabel) {
        chart.setTitle(title);
        xAxis.setName(xLabel);
        yAxis.setName(yLabel);
    }

    void setFixedYRange(double min, double max) {
        if (max <= min) {
            throw new IllegalArgumentException("Y max must be greater than Y min");
        }
        fixedYRange = true;
        fixedYMin = min;
        fixedYMax = max;
        applyYRangeMode();
    }

    void clearFixedYRange() {
        fixedYRange = false;
        applyYRangeMode();
    }

    Series addSeries(String name, Color color) {
        Series s = new Series(name, color, seriesCapacity);
        primaryRenderer.getDatasets().add(s.dataSet);
        return s;
    }

    Series addPidErrorSeries(String name, Color color) {
        Series s = new Series(name, color, seriesCapacity);
        primaryRenderer.getDatasets().add(s.dataSet);
        return s;
    }

    void redraw() {
        enforceDarkIndicatorLabelStyle();
        chart.invalidate();
    }

    void enforceDarkIndicatorLabelStyle() {
        String labelSelector = ".value-watch-indicator-label, .value-indicator-label, .range-indicator-label, .x-value-indicator-label, .y-value-indicator-label, .chart-datapoint-tooltip-label, .chart-datapoint-tooltip-value, .chart-crosshair-label, #crosshairIndicator-Label";
        for (Node node : chart.lookupAll(labelSelector)) {
            node.setStyle("-fx-text-fill:#c9d1d9; -fx-fill:#c9d1d9; -fx-background-color:rgba(22,27,34,0.92); -fx-border-color:#6e7681;");
            if (node instanceof Label label) {
                label.setTextFill(Color.web("#c9d1d9"));
            }
            for (Node textNode : node.lookupAll(".text")) {
                if (textNode instanceof Text text) {
                    text.setFill(Color.web("#c9d1d9"));
                } else {
                    textNode.setStyle("-fx-fill:#c9d1d9;");
                }
            }
        }

        for (Node textNode : chart.lookupAll(".chart-datapoint-tooltip-label .text, .chart-datapoint-tooltip-value .text")) {
            if (textNode instanceof Text text) {
                text.setFill(Color.web("#c9d1d9"));
                text.setStroke(Color.TRANSPARENT);
            } else {
                textNode.setStyle("-fx-fill:#c9d1d9; -fx-stroke:transparent;");
            }
        }

        for (Node plainTextNode : chart.lookupAll(".chart-datapoint-tooltip-label, .chart-datapoint-tooltip-value")) {
            if (plainTextNode instanceof Text text) {
                text.setFill(Color.web("#c9d1d9"));
                text.setStroke(Color.TRANSPARENT);
            }
        }

        for (Node crosshairNode : chart.lookupAll(".chart-crosshair-label, #crosshairIndicator-Label")) {
            if (crosshairNode instanceof Text text) {
                text.setFill(Color.web("#c9d1d9"));
                text.setStroke(Color.TRANSPARENT);
            } else {
                crosshairNode.setStyle("-fx-fill:#c9d1d9; -fx-stroke:transparent;");
            }
        }
    }

    private void applyVisibleWindow() {
        double start = Math.max(0.0, visibleStartMs.get());
        double end = start + Math.max(1.0, visibleSpanMs.get());
        xAxis.setAutoRanging(false);
        xAxis.setAutoGrowRanging(false);
        xAxis.setMin(start);
        xAxis.setMax(end);
        chart.invalidate();
    }

    private void applyYRangeMode() {
        if (fixedYRange) {
            yAxis.setAutoRanging(false);
            yAxis.setAutoGrowRanging(false);
            yAxis.setMin(fixedYMin);
            yAxis.setMax(fixedYMax);
        } else {
            yAxis.setAutoRanging(true);
        }
        chart.invalidate();
    }

    static final class Series {
        final String name;
        final Color color;
        final CircularDoubleErrorDataSet dataSet;

        Series(String name, Color color, int capacity) {
            this.name = name;
            this.color = color;
            this.dataSet = new CircularDoubleErrorDataSet(name, capacity);
            this.dataSet.setStyle("strokeColor=" + toRgb(color) + ";lineWidth=1.4");
        }

        void add(double x, double y) {
            dataSet.add(x, y, 0.0, 0.0);
        }

        void addBatch(double[] xVals, double[] yVals, int count) {
            if (count <= 0) {
                return;
            }
            double[] zeros = new double[count];
            dataSet.add(xVals, yVals, zeros, zeros, count);
        }

        void clear() {
            dataSet.reset();
        }

        private static String toRgb(Color color) {
            int r = (int) Math.round(color.getRed() * 255.0);
            int g = (int) Math.round(color.getGreen() * 255.0);
            int b = (int) Math.round(color.getBlue() * 255.0);
            return "rgb(" + r + "," + g + "," + b + ")";
        }
    }
}
