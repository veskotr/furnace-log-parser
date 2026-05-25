# Furnace Log Viewer - Progress

## 2026-05-22
- Initialized phased implementation tracker.
- Audited current project status:
  - JavaFX app entrypoint: `com.vesodev.fx.FxMain`.
  - Legacy Swing app still present: `com.vesodev.Main`.
  - Custom canvas chart in use (`ViewportChart`), not chart-fx.
  - Excel export currently implemented only in legacy Swing class.
- Completed in this session:
  - Removed `javafx.swing` from Gradle JavaFX module list and run args.
  - Fixed chart-fx dependency coordinates to resolvable artifacts:
    - `io.fair-acc:chartfx:11.3.1`
    - `io.fair-acc:dataset:11.3.1`
  - Stabilized pre-existing compile issues in `FxMain` (method mismatch and missing helper stubs).
  - Verified build success with `gradlew.bat build`.
- Next execution step:
  - Implement Phase 2 chart-fx component and wire fixed Y-scale + scrollable long timeline.

- Phase 2 completed in this session:
  - Replaced custom canvas chart with chart-fx wrapper in `ViewportChart`.
  - Wired chart with `XYChart`, `DefaultNumericAxis`, `ErrorDataSetRenderer`, `Zoomer`, and `EditAxis`.
  - Kept three live datasets: measured temperature, setpoint, PID error.
  - Added fixed Y-axis controls in UI (toggle + min/max + apply).
  - Added scrollable timeline control to navigate long runs while not following live data.
  - Expanded visible window slider to 5..900 minutes (up to 15h).
  - Increased in-memory plotting retention to 120k samples as an interim Phase 2 capacity.
  - Verified build success with `gradlew.bat build`.

- Next execution step:
  - Start Phase 3 hardening: bounded ring buffer + decoupled UI update cadence + long-run memory stability.

- Phase 3 implemented (core) in this session:
  - Replaced list-based chart series storage with bounded `CircularDoubleErrorDataSet` series in `ViewportChart`.
  - Added batched UI ingestion pipeline in `FxMain`:
    - background line parsing enqueues `UiSample` objects,
    - UI timer flushes queue every 100 ms,
    - chart updates are batch-appended per flush.
  - Added queue backpressure guard (`MAX_PENDING_UI_EVENTS`) with explicit drop logging to keep memory bounded.
  - Added log viewport bound (`MAX_LOG_LINES`) to prevent unbounded ListView growth in very long runs.
  - Added runtime telemetry log lines for processed sample milestones and queue depth.
  - Updated chart series palette for dark mode contrast:
    - Measured Temperature: `#6AE3FF`
    - Setpoint Temperature: `#FFD166`
    - PID Error: `#FF7EB6`
  - Verified build success with `gradlew.bat build`.

- Remaining for full Phase 3 completion:
  - Completed in this session: optional disk-backed full-session persistence.

- Phase 3 completion update:
  - Added asynchronous disk-backed sample store `SessionSampleStore` that writes full-session CSV rows.
  - Session files are stored under `%USERPROFILE%/furnace-log-viewer/sessions`.
  - Started a new session file on app startup and whenever data is cleared/new sample feed starts.
  - Wired batch flush pipeline to write parsed samples to disk while chart remains bounded-memory.
  - Added telemetry for disk queue depth, written row count, and dropped-row warnings.
  - Verified build success with `gradlew.bat build`.

- Phase 4 completed in this session:
  - Added UI-independent report pipeline in `FurnaceReportService`.
  - Added report scope model `ReportScope` with `FULL_SESSION` and `VISIBLE_WINDOW`.
  - Implemented on-demand `.xlsx` export with:
    - `Summary` sheet (duration, row count, temperature/error aggregates, scope metadata),
    - `Samples` sheet (session samples table).
  - Wired export flow in `FxMain`:
    - user selects scope,
    - export runs in background thread,
    - pending UI + disk queues are flushed before export snapshot.
  - Verified build success with `gradlew.bat build`.

- Phase 5 completed in this session:
  - Removed legacy Swing application source `com.vesodev.Main`.
  - Verified there are no remaining Swing references in `src/main/java`.
  - Removed unused JavaFX module declaration `javafx.fxml` from Gradle runtime configuration.
  - Ran final regression build: `gradlew.bat build` succeeded.
