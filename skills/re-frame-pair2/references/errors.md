# Error handling

Every script returns structured edn like `{:ok? false :reason ...}` rather than raising. Translate to plain English for the user and suggest the fix named in `:reason`.

## Common cases

- `:nrepl-port-not-found` → tell the user to start their dev build with `shadow-cljs watch <build>`.
- `:browser-runtime-not-attached` → tell the user to open the app in a browser tab.
- `:debug-disabled` → re-frame2's `interop/debug-enabled?` is false (production build, or `goog.DEBUG` was set false). The trace stream and epoch history are elided in this build.
- `:ns-not-loaded :missing :re-frame2` → re-frame2 isn't loaded; check the user's deps.
- `:no-frames-registered` → no frame is up yet. Tell the user to call `(rf/init!)` (or wait for app boot).
- `:ambiguous-frame` → multiple frames; ask the user to `frames/select` or pass `--frame :foo`.
- `:handler-error` inside an epoch → the user's handler threw; surface the `:rf.error/handler-exception` trace event from `(rf/trace-buffer {:op-type :error})`.
- `:timed-out? true` on a `dispatch-and-collect` → drain didn't settle in the wait window (a long-running async cascade, or a stuck `:dispatch-later`). Inspect the in-flight cascade via the trace buffer.
- `:connection :lost` → reconnect by calling `scripts/discover-app.sh` again.
- Restore failures (`:rf.epoch/restore-*`) → see the time-travel failure table in [ops.md](ops.md#time-travel-epoch-restore).
