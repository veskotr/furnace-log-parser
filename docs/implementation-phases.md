# Furnace Log Viewer - Implementation Phases

Last updated: 2026-05-22
Status: In progress

## Goals
- Use `chart-fx` for plotting (replace custom chart canvas implementation).
- Ensure stability for long runs (12-15h+) with bounded memory growth.
- Remove old Swing code from the project.
- Keep chart Y scale fixed (configurable) and X axis scrollable over long sessions.
- Generate Excel reports on demand.

## Phase Plan

### Phase 1 - Foundation and Cleanup
- [ ] Keep JavaFX app as the only runtime entrypoint.
- [x] Remove Swing-specific runtime dependencies from active configuration.
- [ ] Document migration architecture and acceptance criteria.
- [ ] Add explicit run profile for long-run testing.

Acceptance criteria:
- App builds and runs through JavaFX entrypoint only.
- No Swing dependency required for startup path.

### Phase 2 - chart-fx Migration
- [x] Replace `ViewportChart` with chart-fx based component.
- [x] Plot measured temperature, setpoint, PID error as separate datasets.
- [x] Implement fixed Y-axis mode with configurable min/max.
- [x] Implement scrollable X-axis for multi-hour timelines.

Acceptance criteria:
- Visual parity with current chart data.
- Y-axis remains fixed when enabled.
- User can scroll the timeline for long sessions.

### Phase 3 - Long-Run Performance Hardening
- [x] Introduce bounded in-memory ring buffer sized for UI viewport rendering.
- [x] Add optional disk-backed sample persistence for full-session history.
- [x] Decouple ingestion from UI redraw (batch updates and throttled refresh).
- [x] Add memory/throughput telemetry logs.

Acceptance criteria:
- Stable memory behavior for 12-15h sessions.
- No UI freeze under sustained input.

### Phase 4 - Report Pipeline
- [x] Implement report model independent of UI framework.
- [x] Add on-demand Excel export with summary + sample sheets.
- [x] Add export source selection: visible window vs entire session.
- [x] Validate output with representative sample logs.

Acceptance criteria:
- User can export `.xlsx` at any point during or after run.
- Report includes key furnace metrics and sample table.

### Phase 5 - Swing Removal Finalization
- [x] Delete old Swing app and dead code paths.
- [x] Remove no-longer-used imports/modules/dependencies.
- [x] Final regression pass for parser, monitor, chart, export.

Acceptance criteria:
- No Swing source remains in main code path.
- Build passes and app behavior matches target requirements.

## Risks and Notes
- Existing Swing class currently contains mature report logic that should be migrated before deletion.
- chart-fx JavaFX integration must be tuned to avoid redraw overhead on dense datasets.
- For 12-15h logs, keeping every sample in `ArrayList` is not acceptable without disk spill or aggressive decimation.
