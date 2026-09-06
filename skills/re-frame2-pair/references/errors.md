# Error handling

Every MCP tool returns structured edn (`{:ok? false :reason ...}`) rather than raising. Translate to plain English and suggest the fix named in `:reason`.

## Common cases

- `:nrepl-port-not-found` → no shadow-cljs nREPL is reachable at all — this is the **pre-connection degraded-mode** envelope every tool short-circuits to before any runtime check. Tell the user to start their dev build with `shadow-cljs watch <build>`.
- `:debug-disabled` → re-frame2's `interop/debug-enabled?` is false (production build, or `goog.DEBUG` was set false). The trace stream and epoch history are elided in this build.
- `:no-frames-registered` → no frame is up yet. `init!` only installs the adapter — it creates no frame under EP-0002, so calling it won't help; tell the user to establish the app's frame at the root (e.g. a root `frame-root {:id ...}`, or `make-frame`), or wait for app boot.

### `discover-app` preload-failure ladder

When `discover-app` can't find the runtime marker, it runs a **diagnostic ladder** returning one of four *named* reasons instead of a blanket "not preloaded" — each a **different** root cause with a **different** recovery, so translate the exact `:reason` (don't collapse them). Each rung carries `:build` (the targeted id) plus a targeted `:hint`; quote the `:hint` verbatim.

- `:nrepl-unreachable` → the JVM round-trip failed — the nREPL socket is dead even though the MCP server is up (the shadow-cljs JVM stopped, or restarted leaving a stale socket). Recovery: restart `shadow-cljs watch` and retry; the MCP server reconnects on the next tool call.
- `:build-not-running` → nREPL is reachable but shadow isn't running the **targeted** build (a build-id typo, not a short-tail — suffix resolution already ran). Carries `:running-builds` (what IS up) plus `:running-builds-arg-forms` (each in the paste-ready `:build` arg form). Recovery: re-target a running build — `discover-app {build: ":other-build"}` (or set `SHADOW_CLJS_BUILD_ID`).
- `:no-runtime-connected` → the build IS running but no CLJS runtime answered — no browser tab has connected, or the tab's WebSocket dropped. Carries `:running-builds`. Recovery: open the app in a browser tab, or if a tab is open, reload the page so the runtime reconnects. **You can't reload a browser yourself — relay the `:hint` to the user.**
- `:runtime-loaded-but-preload-missing` → a CLJS runtime is alive but the `re-frame2-pair.runtime` preload marker is absent — **this is the normal missing-preload case.** The fix is the two-line `shadow-cljs.edn` preload entry (see `SKILL.md` §Setup). Common after a fresh clone, or when the consumer wired in the MCP server but not the `re-frame2-pair.runtime` preload (the linked skill's `preload/` directory on `:source-paths`). **An app with no re-frame2 dependency at all lands on this rung too** — the preload namespace requires `re-frame.core`, so it never loads and never installs the marker. There is no separate "re-frame2 isn't loaded" reason to look for; if the deps are the real problem, this is the reason that says so.
- `:runtime-not-preloaded` → **degradation fallback only.** Fires when the ladder *itself* errors mid-diagnosis (e.g. a transient nREPL failure), so the response degrades to this blanket reason with the generic preload hint. On the normal missing-preload path the server returns `:runtime-loaded-but-preload-missing` (above), not this — so if you see `:runtime-not-preloaded`, suspect a flaky connection, not just a missing preload entry.
- `:ambiguous-frame` → multiple frames are registered and no session pin is set. The envelope is recovery-shaped: `:operation` (the refusing op), `:available-frames` (the app frames you may pick from), `:selected-frame` (the current pin, nil = none), and `:event` / `:query` when the op knew it. Pin one of `:available-frames` with `set-operating-frame {frame: ":foo"}` (the escape from this refusal — SKILL.md §Multi-frame model), or pass a per-call `frame: ":foo"` arg.
- **A handler that threw is not a `:reason`** — no tool returns one for it. It surfaces as the `:rf.error/handler-exception` **trace op**: read it from `(re-frame.trace.tooling/trace-buffer :rf/default {:flat true :op-type :error})` and report it against the handler. (Frame-id first; `:op-type` is a `:flat-only` filter so pass `:flat true`. `rf/trace-buffer` names the same fn on both platforms; an `eval-cljs` form spells whichever home fully qualified.)

## `eval-cljs` failures

`eval-cljs` carries the long tail, so these are the reasons you hit most. All are returned as `{:ok? false :reason ...}` envelopes, never raised.

- `:rf.error/eval-cljs-compile-error` → the form didn't compile (syntax, arity, or an unresolved symbol — most often an alias that doesn't exist in the runtime). Fix the form and fully-qualify the namespace; there are no ambient aliases.
- `:rf.error/eval-cljs-threw` → the form compiled and ran, then raised. The envelope carries `:ex` (the printed throwable), `:message` and `:ex-data` — read those and report the app-level cause, don't re-run blind.
- `:rf.error/eval-cljs-timeout` → the form didn't settle inside `:timeout-ms`. Raise `timeout-ms`, project the result smaller, or — if the value is a Promise — pass `await: true` so the runtime resolves it instead of returning the pending object.
- `:rf.error/eval-cljs-rejected` → the nREPL session refused the form. Re-run `discover-app` to confirm the build and runtime are still the ones you think you're talking to.
- `:rf.error/eval-cljs-disabled` → the operator launched the server with `--no-eval`. **Only they can lift it** (relaunch without the flag); until then, use the typed tools and say which gesture you can't reach.
- `:rf.error/eval-cljs-mailbox-missing` / `:rf.error/eval-cljs-await-wrap-failed` → the `await: true` path broke — the mailbox vanished (usually a page reload between the wrap and the poll) or the wrapper returned an unrecognised sentinel. Retry once without a reload in flight; a repeat is a wire-shape regression worth reporting.

## Tool-envelope refusals

Ops refuse with a `:reason` rather than guessing. Beyond `:ambiguous-frame` and `:debug-disabled` above:

- `:restore-rejected` → `restore-epoch` couldn't rewind: the epoch-id has aged out of the ring, or a drain is in flight. The frame-state is unchanged. Re-read the ring (`trace-window` / `snapshot`'s `:epochs` slice) and pick a live id.
- `:reset-rejected` → `replace-app-db` refused: no such frame, a drain in flight, or the supplied db failed the app-schema. The app-db is unchanged; fix the shape against the schema and retry.
- `:missing-baseline` → `tail-build` got a `:probe` with no `:baseline`. The baseline must be captured **before** the source edit; a post-edit self-baseline can't distinguish a fast reload from no reload.
- `:baseline-without-probe` → the mirror image: a `:baseline` with nothing to compare it against. Supply the probe form the baseline came from.
- `:port-unresolved` → `discover-app {port: N}` found no build serving that port in the shadow-cljs `:dev-http` map. Pass `:build` explicitly, or call `discover-app` with no arg to auto-select.

## A structured read came back blank

A structured read (`read-dom`, `read-ui`, `read-sub`, `get-path`) returning an empty / blank / `:*-blank-result` value — or an unexpected `nil` — is **usually a broken OP or eval form, NOT a stale connection.** Do **not** act on a "reload the tab / reconnect" hint on this signal alone (a misleading `read-dom` hint cost real debug time).

Recovery: **confirm the runtime is answering** before blaming the connection:

1. Re-run the *equivalent query* as `eval-cljs` — the same selector via `(re-frame2-pair.runtime/dom-describe "sel")` for a blank `read-dom`, the same sub via `@(re-frame.core/subscribe [:foo])` for a blank `read-sub`, the same path via `(re-frame2-pair.runtime/app-db-at [...])` for a blank `get-path`.
2. If the `eval-cljs` form **returns the value**, the runtime is live and the structured op is the suspect — report the op as broken, don't reconnect.
3. Only if `eval-cljs` *also* comes back blank/errored should you suspect liveness — then check `discover-app`'s `:freshness :liveness` (a `:stale-build` means RELOAD; `:no-runtime` means no tab is attached). See [§eval-cljs is the workhorse](recipes.md#eval-cljs-is-the-workhorse) for the broader recovery posture.

## Pointing the user at the offending handler

Every `:rf.error/*` trace event carries `:rf.trace/trigger-handler` — `{:kind :event :id :user/save :source-coord {:ns ... :file ... :line ... :column ...}}` — naming the handler that was executing when the error fired (event, sub, fx, cofx, view, interceptor, or late-bind hook). Report the `:source-coord` as `<file>:<line>` so the user can jump to source. The field rides the trace surface — fine for this skill, which only ever drives a dev build (`goog.DEBUG=true`); it is **production-elided** with the rest of the trace surface, so in a `:advanced` build the production-surviving coord comes from the always-on error-emit record's `:source-coord`, not this trace field. The trace field is **absent** for dispatch-time errors where no handler is in scope yet (e.g. `:rf.error/no-such-event`); for those, the `:rf.error/data` is the only handle.
- `:timed-out? true` on a `watch-until` → `{:ok? false :reason :watch-timeout :timed-out? true :last-sample {...}}`: the signal predicate never held in `timeout-ms`. (`dispatch-and-collect` is synchronous and has no timeout; the only await-timeout on the dispatch surface is `:rf.error/dispatch-await-render-timeout` for `dispatch {await-render: true}`.) Inspect the in-flight cascade via the trace buffer.
- `:unknown-tool` → you called a tool name the server doesn't expose (a typo or a renamed tool). The envelope is recovery-shaped: `{:ok? false :reason :unknown-tool :tool <name> :hint "..." :available-tools [...] :did-you-mean <near>}`. Take the `:did-you-mean` suggestion when present, else scan `:available-tools`; the `:hint` also points at `tools/list`. Don't guess a third name — read the catalogue it handed back.
- Restore failures (`:rf.epoch/restore-*`) → see the time-travel failure table in [ops.md](ops.md#time-travel-epoch-restore).

## Error observability

Errors are not steered by app policy — recovery is framework-owned (the per-category typed default: frame-destroyed recovers + emits, sub-exception returns `nil`, handler-exception fails loud without crashing the app). Observability is the always-on `:errors` stream of `rf/register-listener!`, whose payload is an **error-keyed union of several record shapes**:

- per-event error record `{:error :event :event-id :frame :time :exception :elapsed-ms}` (plus `:source-coord` for macro-registered handlers), fanned out per production-reachable `:rf.error/*`;
- frame-teardown report `{:error :rf.error/frame-teardown-failed :frame :hook-failures :reason :recovery :time}` — one bounded record per destroy whose cleanup hooks threw (EP-0008);
- EP-0008-promoted **non-event SSR records** (`:rf.error/ssr-render-failed`, `:rf.error/ssr-streaming-writer-failed`, `:rf.error/malformed-hydration-payload`, `:rf.error/ssr-head-resolution-failed`, `:rf.error/sanitised-on-projection`, `:rf.error/ssr-ring-error-view-failed` — `:frame` + category-specific slots, some with `:frame nil`, none with `:event`).

A consumer **must branch on `(:error record)`** — the teardown report and SSR records have no `:event` / `:event-id` / `:exception`, so assuming the per-event shape NPEs on a non-event record. Pull recent errors with `(re-frame.trace.tooling/trace-buffer :rf/default {:flat true :op-type :error})` and surface them verbatim (frame-id first; `:op-type` is a `:flat-only` filter). (There is no per-frame `:on-error` recovery policy — recovery is framework-owned.)
