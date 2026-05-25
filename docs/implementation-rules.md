# Furnace Log Viewer - Implementation Rules

## Functional Rules
- JavaFX app (`FxMain`) is the only supported runtime UI.
- Charts must use chart-fx in production code.
- Y scale must support fixed bounds (configurable) for furnace runs.
- X axis must be scrollable for full 12-15h sessions.
- Excel export must be available on demand during runtime.

## Performance Rules
- Avoid unbounded in-memory lists for sample history.
- Separate data ingestion from UI rendering frequency.
- Prefer append-only storage model for full-session data.
- UI updates should be batched/throttled for sustained streams.

## Migration Rules
- Preserve parser contract in `LogParser` while refactoring UI.
- Preserve existing report fields unless explicitly changed.
- Remove Swing code only after report logic is migrated.
- Keep each phase buildable and testable before moving to next.

## Validation Rules
- Every phase must compile (`gradlew build`) before completion.
- Add at least one smoke path per phase (sample log playback or monitor line feed).
- Record phase completion in `docs/progress.md`.
