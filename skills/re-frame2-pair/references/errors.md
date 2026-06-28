# Error handling

Every script returns structured edn (`{:ok? false :reason ...}`) rather than raising. Translate to plain English and suggest the fix named in `:reason`.

## Common cases

- `:nrepl-port-not-found` → no shadow-cljs nREPL is reachable at all — this is the **pre-connection degraded-mode** envelope every tool short-circuits to before any runtime check. Tell the user to start their dev build with `shadow-cljs watch <build>`.
- `:debug-disabled` → re-frame2's `interop/debug-enabled?` is false (production build, or `goog.DEBUG` was set false). The trace stream and epoch history are elided in this build.
- `:ns-not-loaded :missing :re-frame2` → re-frame2 isn't loaded; check the user's deps.
- `:no-frames-registered` → no frame is up yet. `init!` only installs the adapter — it creates no frame under EP-0002, so calling it won't help; tell the user to establish the app's frame at the root (e.g. a root `frame-provider {:id ...}`, or `reg-frame`), or wait for app boot.

### `discover-app` preload-failure ladder

When `discover-app` can't find the runtime marker, it runs a **diagnostic ladder** returning one of four *named* reasons instead of a blanket "not preloaded" — each a **different** root cause with a **different** recovery, so translate the exact `:reason` (don't collapse them). Each rung carries `:build` (the targeted id) plus a targeted `:hint`; quote the `:hint` verbatim.

- `:nrepl-unreachable` → the JVM round-trip failed — the nREPL socket is dead even though the MCP server is up (the shadow-cljs JVM stopped, or restarted leaving a stale socket). Recovery: restart `shadow-cljs watch` and retry; the MCP server reconnects on the next tool call.
- `:build-not-running` → nREPL is reachable but shadow isn't running the **targeted** build (a build-id typo, not a short-tail — suffix resolution already ran). Carries `:running-builds` (what IS up) plus `:running-builds-arg-forms` (each in the paste-ready `:build` arg form). Recovery: re-target a running build — `discover-app {build: ":other-build"}` (or set `SHADOW_CLJS_BUILD_ID`).
- `:no-runtime-connected` → the build IS running but no CLJS runtime answered — no browser tab has connected, or the tab's WebSocket dropped. Carries `:running-builds`. Recovery: open the app in a browser tab, or if a tab is open, reload the page so the runtime reconnects. **You can't reload a browser yourself — relay the `:hint` to the user.**
- `:runtime-loaded-but-preload-missing` → a CLJS runtime is alive but the `re-frame2-pair.runtime` preload marker is absent — **this is the normal missing-preload case.** The fix is the two-line `shadow-cljs.edn` preload entry (see `SKILL.md` §Setup). Common after a fresh clone, or when the consumer wired in the MCP server but not the `@day8/re-frame2-pair` preload package.
- `:runtime-not-preloaded` → **degradation fallback only.** Fires when the ladder *itself* errors mid-diagnosis (e.g. a transient nREPL failure), so the response degrades to this blanket reason with the generic preload hint. On the normal missing-preload path the server returns `:runtime-loaded-but-preload-missing` (above), not this — so if you see `:runtime-not-preloaded`, suspect a flaky connection, not just a missing preload entry.
- `:ambiguous-frame` → multiple frames are registered and no session pin is set. The envelope is recovery-shaped: `:operation` (the refusing op), `:available-frames` (the app frames you may pick from), `:selected-frame` (the current pin, nil = none), and `:event` / `:query` when the op knew it. Pin one of `:available-frames` with `set-operating-frame {frame: ":foo"}` (the escape from this refusal — SKILL.md §Multi-frame model), or pass a per-call `frame: ":foo"` arg.
- `:handler-error` inside an epoch → the user's handler threw; surface the `:rf.error/handler-exception` trace event from `(re-frame.trace.tooling/trace-buffer :rf/default {:flat true :op-type :error})`. (Frame-id first; `:op-type` is a `:flat-only` filter so pass `:flat true`. Use the `re-frame.trace.tooling` ns — `rf/trace-buffer` is JVM-only and returns nil in the browser runtime.)

## A structured read came back blank

A structured read (`read-dom`, `read-ui`, `read-sub`, `get-path`) returning an empty / blank / `:*-blank-result` value — or an unexpected `nil` — is **usually a broken OP or eval form, NOT a stale connection.** Do **not** act on a "reload the tab / reconnect" hint on this signal alone (a misleading `read-dom` hint cost real debug time).

Recovery: **confirm the runtime is answering** before blaming the connection:

1. Re-run the *equivalent query* as `eval-cljs` — the same selector via `(re-frame2-pair.runtime/dom-describe "sel")` for a blank `read-dom`, the same sub via `@(re-frame.core/subscribe [:foo])` for a blank `read-sub`, the same path via `(re-frame2-pair.runtime/app-db-at [...])` for a blank `get-path`.
2. If the `eval-cljs` form **returns the value**, the runtime is live and the structured op is the suspect — report the op as broken, don't reconnect.
3. Only if `eval-cljs` *also* comes back blank/errored should you suspect liveness — then check `discover-app`'s `:freshness :liveness` (a `:stale-build` means RELOAD; `:no-runtime` means no tab is attached). See [§eval-cljs is the workhorse](recipes.md#eval-cljs-is-the-workhorse) for the broader recovery posture.

## Pointing the user at the offending handler

Every `:rf.error/*` trace event carries `:rf.trace/trigger-handler` — `{:kind :event :id :user/save :source-coord {:ns ... :file ... :line ... :column ...}}` — naming the handler that was executing when the error fired (event, sub, fx, cofx, view, interceptor, or late-bind hook). Report the `:source-coord` as `<file>:<line>` so the user can jump to source. The field rides the trace surface — fine for this skill, which only ever drives a dev build (`goog.DEBUG=true`); it is **production-elided** with the rest of the trace surface, so in a `:advanced` build the production-surviving coord comes from the always-on error-emit record's `:source-coord`, not this trace field. The trace field is **absent** for dispatch-time errors where no handler is in scope yet (e.g. `:rf.error/no-such-event`); for those, the `:rf.error/data` is the only handle.
- `:timed-out? true` on a `dispatch-and-collect` → drain didn't settle in the wait window (a long-running async cascade, or a stuck `:dispatch-later`). Inspect the in-flight cascade via the trace buffer.
- `:connection :lost` → reconnect by calling `mcp__re-frame2-pair__discover-app` again.
- `:unknown-tool` → you called a tool name the server doesn't expose (a typo or a renamed tool). The envelope is recovery-shaped: `{:ok? false :reason :unknown-tool :tool <name> :hint "..." :available-tools [...] :did-you-mean <near>}`. Take the `:did-you-mean` suggestion when present, else scan `:available-tools`; the `:hint` also points at `tools/list`. Don't guess a third name — read the catalogue it handed back.
- `:rf.error/concurrent-stream-limit` on a `subscribe` → every server stream slot is taken; the new subscription was **denied before touching the runtime** (a server resource cap, not a runtime fault). Recovery: `unsubscribe` an idle stream (read `list-streams` to see which are open), or have the operator raise `--max-concurrent-streams`. Confirm the slot picture with `get-stream-controls` (`:concurrent-streams :at-capacity? true`) — see [streaming-subscriptions.md §get-stream-controls](streaming-subscriptions.md#get-stream-controls--why-was-my-stream-denied--quiet--terminated).
- `:rf.error/stream-abuse-detected` terminating a `subscribe` → the consumer couldn't keep up (the per-sub queue evicted past `--abuse-overflow-threshold` over the abuse window), so the server shed the stream to stop burning CPU + bandwidth. This is **back-pressure, not a bug**. Recovery: re-open with a **narrower** `filter` / `pred` so fewer events match, a lower `poll-ms`/`max-buffered-*`, or accept the drop. `get-stream-controls` shows `:abuse-window :tripped? true`. (A separate, non-fatal `:rate-dropped` count on a stream's final summary just means the per-session token bucket deferred some cycles — raise `--max-events-per-sec` if it keeps tripping; no events were lost.)
- Restore failures (`:rf.epoch/restore-*`) → see the time-travel failure table in [ops.md](ops.md#time-travel-epoch-restore).

## Error observability

Errors are not steered by app policy — recovery is framework-owned (the per-category typed default: frame-destroyed recovers + emits, sub-exception returns `nil`, handler-exception fails loud without crashing the app). Observability is the always-on `:errors` stream of `rf/register-listener!`, whose payload is an **error-keyed union of several record shapes**:

- per-event error record `{:error :event :event-id :frame :time :exception :elapsed-ms}` (plus `:source-coord` for macro-registered handlers), fanned out per production-reachable `:rf.error/*`;
- frame-teardown report `{:error :rf.error/frame-teardown-failed :frame :hook-failures :reason :recovery :time}` — one bounded record per destroy whose cleanup hooks threw (EP-0008);
- EP-0008-promoted **non-event SSR records** (`:rf.error/ssr-render-failed`, `:rf.error/ssr-streaming-writer-failed`, `:rf.error/malformed-hydration-payload`, `:rf.error/ssr-head-resolution-failed`, `:rf.error/sanitised-on-projection`, `:rf.error/ssr-ring-error-view-failed` — `:frame` + category-specific slots, some with `:frame nil`, none with `:event`).

A consumer **must branch on `(:error record)`** — the teardown report and SSR records have no `:event` / `:event-id` / `:exception`, so assuming the per-event shape NPEs on a non-event record. Pull recent errors with `(re-frame.trace.tooling/trace-buffer :rf/default {:flat true :op-type :error})` and surface them verbatim (frame-id first; `:op-type` is a `:flat-only` filter). (There is no per-frame `:on-error` recovery policy — recovery is framework-owned.)
