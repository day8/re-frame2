# Error handling

Every script returns structured edn like `{:ok? false :reason ...}` rather than raising. Translate to plain English for the user and suggest the fix named in `:reason`.

## Common cases

- `:nrepl-port-not-found` → tell the user to start their dev build with `shadow-cljs watch <build>`.
- `:browser-runtime-not-attached` → tell the user to open the app in a browser tab.
- `:runtime-not-preloaded` → the `re-frame2-pair.runtime` namespace isn't loaded into the app. Quote the `:hint` verbatim; the fix is two lines in `shadow-cljs.edn` (see `SKILL.md` §Setup). Common after a fresh clone or when the consumer added re-frame2-pair without the preload entry.
- `:debug-disabled` → re-frame2's `interop/debug-enabled?` is false (production build, or `goog.DEBUG` was set false). The trace stream and epoch history are elided in this build.
- `:ns-not-loaded :missing :re-frame2` → re-frame2 isn't loaded; check the user's deps.
- `:no-frames-registered` → no frame is up yet. Tell the user to call `(rf/init!)` (or wait for app boot).
- `:ambiguous-frame` → multiple frames are registered and no session pin is set. Pin one with `set-operating-frame {frame: ":foo"}` (the escape from this refusal — SKILL.md §Multi-frame model), or pass a per-call `frame: ":foo"` arg.
- `:handler-error` inside an epoch → the user's handler threw; surface the `:rf.error/handler-exception` trace event from `(re-frame.trace.tooling/trace-buffer {:op-type :error})`. (Use the `re-frame.trace.tooling` ns — `rf/trace-buffer` is JVM-only and returns nil in the browser runtime.)

## A structured read came back blank

A structured read (`read-dom`, `read-ui`, `read-sub`, `get-path`) that returns an empty / blank / `:*-blank-result` value — or an unexpected `nil` — is **usually a broken OP or eval form, NOT a stale connection.** Do **not** act on a "reload the tab / reconnect" hint on this signal alone (a misleading `read-dom` hint cost real debug time, rf2-5ffuv).

The recovery is to **confirm the runtime is answering** before blaming the connection:

1. Re-run the *equivalent query* as `eval-cljs` — the same selector via `(re-frame2-pair.runtime/dom-describe "sel")` for a blank `read-dom`, the same sub via `@(re-frame.core/subscribe [:foo])` for a blank `read-sub`, the same path via `(re-frame2-pair.runtime/app-db-at [...])` for a blank `get-path`.
2. If the `eval-cljs` form **returns the value**, the runtime is live and the structured op is the suspect — report the op as broken (and consider filing a `bd` bead), don't reconnect.
3. Only if `eval-cljs` *also* comes back blank/errored should you suspect liveness — then check `discover-app`'s `:freshness :liveness` (a `:stale-build` means RELOAD; `:no-runtime` means no tab is attached). See [§eval-cljs is the workhorse](recipes.md#eval-cljs-is-the-workhorse) for the broader recovery posture.

## Pointing the user at the offending handler

Every `:rf.error/*` trace event carries `:rf.trace/trigger-handler` — `{:kind :event :id :user/save :source-coord {:ns ... :file ... :line ... :column ...}}` — naming the handler that was executing when the error fired (event, sub, fx, cofx, view, interceptor, or late-bind hook). Report the `:source-coord` as `<file>:<line>` so the user can jump to source; the field is present in production traces too. The field is **absent** for dispatch-time errors where no handler is in scope yet (e.g. `:rf.error/no-such-event`); for those, the `:rf.error/data` is the only handle.
- `:timed-out? true` on a `dispatch-and-collect` → drain didn't settle in the wait window (a long-running async cascade, or a stuck `:dispatch-later`). Inspect the in-flight cascade via the trace buffer.
- `:connection :lost` → reconnect by calling `mcp__re-frame2-pair__discover-app` again.
- `:unknown-tool` → you called a tool name the server doesn't expose (a typo or a renamed tool). The envelope is recovery-shaped (rf2-tkmik): `{:ok? false :reason :unknown-tool :tool <name> :hint "..." :available-tools [...] :did-you-mean <near>}`. Take the `:did-you-mean` suggestion when present, else scan `:available-tools`; the `:hint` also points at `tools/list`. Don't guess a third name — read the catalogue it handed back.
- Restore failures (`:rf.epoch/restore-*`) → see the time-travel failure table in [ops.md](ops.md#time-travel-epoch-restore).

## `:on-error` policy violations

Two error categories surface when a frame's `:on-error` policy violates its return-map contract. Both ride the trace stream like any other `:rf.error/*` event — pull them with `(re-frame.trace.tooling/trace-buffer {:op-type :error})` and surface to the user verbatim. For the full contract (closed return shape, the `:recovery` enum, why `:retry-count` is gone), see [on-error.md](on-error.md).

- `:rf.error/bad-on-error-return` (`:recovery :logged-and-skipped`) → the policy returned a map with a `:recovery` outside the closed enum (commonly the now-removed `:retried`), or set `:replacement` malformed or on a category that has no substitutable value. The runtime falls back to the original error's category default. `:tags {:received <map> :reason <str>}` names the offending shape — quote it to the user; the fix is almost always "drop `:retry-count` and pick a real `:recovery` keyword".
- `:rf.error/on-error-policy-exception` (`:recovery :no-recovery`) → the policy fn itself threw. The runtime does NOT recursively invoke the policy on its own exception — it emits this trace and falls back to the original error's category default. `:tags {:original <input-error-event> :exception-message <str>}` carries the original error the policy was handling plus the throw message. Cascade halts; the policy's exception does not propagate to user code. Surface both the original op and the throw site to the user.
