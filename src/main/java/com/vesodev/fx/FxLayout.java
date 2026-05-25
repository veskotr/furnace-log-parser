package com.vesodev.fx;

import javafx.geometry.Insets;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

final class FxLayout {
    private FxLayout() {
    }

    static void configureLogList(ListView<String> logList) {
        logList.setCellFactory(v -> new ListCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    setStyle("");
                    return;
                }
                setText(item);
                String t = item.stripLeading();
                if (!t.isEmpty() && (t.charAt(0) == 'E' || t.startsWith("[ERR]"))) {
                    setStyle("-fx-text-fill: #ff6b6b;");
                } else if (!t.isEmpty() && (t.charAt(0) == 'W' || t.startsWith("[WARN]"))) {
                    setStyle("-fx-text-fill: #ffd166;");
                } else {
                    setStyle("-fx-text-fill: #e6e6e6;");
                }
            }
        });
    }

    static VBox buildRightPanel(
            ListView<String> logList,
            Label elapsedValue,
            Label stageValue,
            Label phaseValue,
            Label spValue,
            Label pvValue,
            Label errValue,
            Label outValue,
            Label pValue,
            Label iValue,
            Label dValue,
            Label dtValue,
            Label dynMaxValue,
            Label rampStatsValue,
            Label rampOscValue,
            Label rampTuneValue,
            Label holdStatsValue,
            Label holdOscValue,
            Label holdTuneValue,
            Label analysisStatusValue) {
        VBox right = new VBox(10);
        right.setPadding(new Insets(8));
        right.setPrefWidth(360);
        right.setStyle("-fx-border-color: #333333; -fx-border-width: 1 0 0 1;");

        Label pidHeader = new Label("Live PID / Process");
        pidHeader.setStyle("-fx-text-fill: #ffffff; -fx-font-weight: bold; -fx-font-size: 13;");

        VBox pidBox = new VBox(4,
                row("Elapsed:", elapsedValue),
                row("Stage:", stageValue),
                row("Phase:", phaseValue),
                row("SP:", spValue),
                row("PV:", pvValue),
                row("Err:", errValue),
                row("Out:", outValue),
                row("P:", pValue),
                row("I:", iValue),
                row("D:", dValue),
                row("dt:", dtValue),
                row("dyn_max:", dynMaxValue)
        );
        pidBox.setStyle("-fx-background-color: #252525; -fx-padding: 8; -fx-background-radius: 6;");

        Label analysisHeader = new Label("Live PID Analysis (Phase 0/1)");
        analysisHeader.setStyle("-fx-text-fill: #ffffff; -fx-font-weight: bold; -fx-font-size: 13;");

        styleAnalysisValueLabel(rampStatsValue);
        styleAnalysisValueLabel(rampOscValue);
        styleAnalysisValueLabel(rampTuneValue);
        styleAnalysisValueLabel(holdStatsValue);
        styleAnalysisValueLabel(holdOscValue);
        styleAnalysisValueLabel(holdTuneValue);
        styleAnalysisValueLabel(analysisStatusValue);

        VBox analysisBox = new VBox(4,
                row("Phase0 Err:", rampStatsValue),
                row("Ramp Osc:", rampOscValue),
                row("Ramp Tune:", rampTuneValue),
                row("Phase1 Err:", holdStatsValue),
                row("Hold Osc:", holdOscValue),
                row("Hold Tune:", holdTuneValue),
                row("Status:", analysisStatusValue)
        );
        analysisBox.setStyle("-fx-background-color: #252525; -fx-padding: 8; -fx-background-radius: 6;");

        Label logHeader = new Label("Log");
        logHeader.setStyle("-fx-text-fill: #ffffff; -fx-font-weight: bold; -fx-font-size: 13;");
        VBox.setVgrow(logList, Priority.ALWAYS);

        right.getChildren().addAll(pidHeader, pidBox, analysisHeader, analysisBox, logHeader, logList);
        return right;
    }

    private static HBox row(String name, Label value) {
        Label n = new Label(name);
        n.setStyle("-fx-text-fill: #a8a8a8;");
        value.setStyle("-fx-text-fill: #ffffff;");
        return new HBox(6, n, value);
    }

    private static void styleAnalysisValueLabel(Label value) {
        value.setStyle("-fx-text-fill: #ffffff;");
        value.setWrapText(true);
        value.setMaxWidth(240);
    }
}
