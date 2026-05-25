module com.vesodev.furnacemonitor {
    requires javafx.base;
    requires javafx.controls;
    requires javafx.graphics;

    requires com.fazecast.jSerialComm;
    requires org.apache.poi.ooxml;
    requires io.fair_acc.chartfx;
    requires io.fair_acc.dataset;
    requires org.kordamp.ikonli.fontawesome;

    exports com.vesodev;
    exports com.vesodev.fx;
}
