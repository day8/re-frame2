# Error handling

Every script returns structured edn like `{:ok? false :reason ...}` rather than raising. Translate to plain English for the user and suggest the fix named in `:reason`.

## Common cases

- `:nrepl-port-not-found` → tell the user to start their dev build with `shadow-cljs watch <build>`.
- `:browser-runtime-not-attached` → tell the user to open the app in a browser tab.
- `:runtime-not-preloaded` → the `re-frame-pair2.runtime` namespace isn't loaded into the app. Quote the `:hint` verbatim; the fix is two lines in `shadow-cljs.edn` (see `SKILL.md` §Setup). Common after a fresh clone or when the consumer added pair2 without the preload entry.
- `:debug-disabled` → re-frame2's `interop/debug-enabled?` is false (production build, or `goog.DEBUG` was set false). The trace stream and epoch history are elided in this build.
- `:ns-not-loaded :missing :re-frame2` → re-frame2 isn't loaded; check the user's deps.
- `:no-frames-registered` → no frame is up yet. Tell the user to call `(rf/init!)` (or wait for app boot).
- `:ambiguous-frame` → multiple frames; ask the user to `frames/select` or pass `--frame :foo`.
- `:handler-error` inside an epoch → the user's handler threw; surface the `:rf.error/handler-exception` trace event from `(rf/trace-buffer {:op-type :error})`.

## Pointing the user at the offending handler

Every `:rf.error/*` trace event carries `:rf.trace/trigger-handler` — `{:kind :event :id :user/save :source-coord {:ns ... :file ... :line ... :column ...}}` — naming the handler that was executing when the error fired (event, sub, fx, cofx, view, interceptor, or late-bind hook). Report the `:source-coord` as `<file>:<line>` so the user can jump to source; the field is present in production traces too. The field is **absent** for dispatch-time errors where no handler is in scope yet (e.g. `:rf.error/no-such-event`); for those, the `:rf.error/data` is the only handle.
- `:timed-out? true` on a `dispatch-and-collect` → drain didn't settle in the wait window (a long-running async cascade, or a stuck `:dispatch-later`). Inspect the in-flight cascade via the trace buffer.
- `:connection :lost` → reconnect by calling `scripts/discover-app.sh` again.
- Restore failures (`:rf.epoch/restore-*`) → see the time-travel failure table in [ops.md](ops.md#time-travel-epoch-restore).

## `:on-error` policy violations (rf2-ciy)

Two error categories surface when a frame's `:on-error` policy violates its return-map contract. Both ride the trace stream like any other `:rf.error/*` event — pull them with `(rf/trace-buffer {:op-type :error})` and surface to the user verbatim. For the full contract (closed return shape, the `:recovery` enum, why `:retry-count` is gone), see [on-error.md](on-error.md).

- `:rf.error/bad-on-error-return` (`:recovery :logged-and-skipped`) → the policy returned a map with a `:recovery` outside the closed enum (commonly the now-removed `:retried`), or set `:replacement` malformed or on a category that has no substitutable value. The runtime falls back to the original error's category default. `:tags {:received <map> :reason <str>}` names the offending shape — quote it to the user; the fix is almost always "drop `:retry-count` and pick a real `:recovery` keyword".
- `:rf.error/on-error-policy-exception` (`:recovery :no-recovery`) → the policy fn itself threw. The runtime does NOT recursively invoke the policy on its own exception — it emits this trace and falls back to the original error's category default. `:tags {:original <input-error-event> :exception-message <str>}` carries the original error the policy was handling plus the throw message. Cascade halts; the policy's exception does not propagate to user code. Surface both the original op and the throw site to the user.
