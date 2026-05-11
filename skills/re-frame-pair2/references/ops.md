# Operations catalogue

The op vocabulary the skill operates through. Each op is a short `scripts/eval-cljs.sh` invocation wrapping a call into `re-frame-pair2.runtime`, or a dedicated script when the concern is broader than one form. Prefer the **structured ops** over `repl/eval` whenever a structured op fits.

## Contents

- [Read](#read)
- [Write](#write)
- [Trace](#trace) — trace stream + epoch history
- [DOM source bridge](#dom-source-bridge)
- [Live watch (push-mode)](#live-watch-push-mode)
- [Hot-reload coordination](#hot-reload-coordination)
- [Time-travel (epoch restore)](#time-travel-epoch-restore)

## Read

| Op | Invocation | Returns |
|---|---|---|
| `app-db/snapshot` | `scripts/eval-cljs.sh '(re-frame-pair2.runtime/snapshot)'` | Current app-db value for the operating frame (via `rf/get-frame-db`) |
| `app-db/get` | `scripts/eval-cljs.sh '(re-frame-pair2.runtime/app-db-at [:path :to :value])'` | Path-scoped value (via `rf/snapshot-of`) |
| `app-db/schemas` | `scripts/eval-cljs.sh '(re-frame-pair2.runtime/schemas)'` | Map of `path → schema` from `rf/app-schemas` |
| `registrar/list` | `scripts/eval-cljs.sh '(re-frame-pair2.runtime/registrar-list :event)'` | Ids registered under `:event` / `:sub` / `:fx` / `:cofx` (via `rf/handlers`) |
| `registrar/describe` | `scripts/eval-cljs.sh '(re-frame-pair2.runtime/registrar-describe :event :cart/apply-coupon)'` | Full handler metadata: kind, interceptor ids, `:ns` / `:line` / `:file`, `:rf/machine?`, retained source form when present |
| `subs/cache` | `scripts/eval-cljs.sh '(re-frame-pair2.runtime/sub-cache)'` | `rf/sub-cache` — `{query-v {:value v :ref-count n}}` for every materialised subscription (CLJS-only) |
| `subs/sample` | `scripts/eval-cljs.sh '(re-frame-pair2.runtime/subs-sample [:cart/total])'` | One-shot value via `rf/compute-sub` (no cache mutation) or `@(rf/subscribe ...)` |
| `machines/list` | `scripts/eval-cljs.sh '(re-frame-pair2.runtime/machines-list)'` | Machine ids (`rf/machines`) |
| `machines/describe` | `scripts/eval-cljs.sh '(re-frame-pair2.runtime/machine-describe :auth)'` | The registered spec map (`rf/machine-meta`) |
| `machines/state` | `scripts/eval-cljs.sh '(re-frame-pair2.runtime/machine-state :auth)'` | Current snapshot from `(rf/snapshot-of [:rf/machines :auth])` |

## Write

| Op | Invocation | Notes |
|---|---|---|
| `dispatch` | `scripts/dispatch.sh '[:cart/apply-coupon "SPRING25"]'` | Queued by default; `--sync` forces `dispatch-sync`. Skill-issued dispatches carry `:origin :pair` (Spec 002 §Dispatch origin tagging) so `:event/dispatched` traces can be filtered by who fired them. |
| `dispatch --frame` | `scripts/dispatch.sh '[:foo]' --frame :stories` | Targets a specific frame via the `:frame` opt on `rf/dispatch`. |
| `reg-event` / `reg-sub` / `reg-fx` | `scripts/eval-cljs.sh '<full reg-* form>'` | Re-registration replaces; emits `:rf.registry/handler-replaced` trace (Spec 001 §Hot-reload semantics). Ephemeral. |
| `app-db/reset` | `scripts/eval-cljs.sh '(re-frame-pair2.runtime/app-db-reset! ...)'` | Delegates to `rf/reset-frame-db!` (Tool-Pair §Pair-tool writes, rf2-zq55) — replaces app-db, records a synthetic `:rf.epoch/db-replaced` epoch, validates against schema, refuses during a drain. Logged explicitly via `tap>` so the user sees what the agent changed. Use sparingly. |
| `repl/eval` | `scripts/eval-cljs.sh '<arbitrary form>'` | Escape hatch. Prefer structured ops first. |
| `fx-overrides/with` | `scripts/dispatch.sh '[:cart/checkout]' --fx-override :http=:stub-http` | Per-call `:fx-overrides` (Spec 002 §Per-frame and per-call overrides) — redirect a registered fx to a stub for one experiment, restore on completion. |

## Trace

Read-only from the trace stream + epoch history.

| Op | Invocation | Returns |
|---|---|---|
| `trace/buffer` | `scripts/eval-cljs.sh '(rf/trace-buffer)'` | Recent N trace events from the retain-N ring (Spec 009 §Retain-N trace ring buffer). Optional `{:operation _ :op-type _ :since _ :frame _}` filter. |
| `trace/last-epoch` | `scripts/eval-cljs.sh '(re-frame-pair2.runtime/last-epoch)'` | Most recent `:rf/epoch-record` for the operating frame |
| `trace/last-pair-epoch` | `scripts/eval-cljs.sh '(re-frame-pair2.runtime/last-pair-epoch)'` | Most recent epoch whose `:trigger-event`'s top-level dispatch carried `:origin :pair` (i.e. *this skill* fired it) |
| `trace/epoch` | `scripts/eval-cljs.sh '(re-frame-pair2.runtime/epoch-by-id <id>)'` | The named epoch from the frame's history |
| `trace/dispatch-and-collect` | `scripts/dispatch.sh --trace '[:foo ...]'` | Fire + wait for drain-settle + return the resulting `:rf/epoch-record` |
| `trace/recent` | `scripts/trace-window.sh <ms>` | Epochs whose `:committed-at` falls inside the last N ms |
| `trace/find-where` | `scripts/eval-cljs.sh '(re-frame-pair2.runtime/find-where <pred>)'` | Most recent epoch matching a predicate — primary forensic op for "when did X happen?" post-mortems |
| `trace/find-all-where` | `scripts/eval-cljs.sh '(re-frame-pair2.runtime/find-all-where <pred>)'` | Every matching epoch, newest first — for trajectories rather than single transitions |
| `trace/cascade` | `scripts/eval-cljs.sh '(re-frame-pair2.runtime/cascade-of <dispatch-id>)'` | Walk `:dispatch-id` / `:parent-dispatch-id` (Spec 009 §Dispatch correlation) to reconstruct the full cascade tree from a root dispatch |

## DOM source bridge

**Why this family matters — read first.** When the runtime is configured to annotate rendered DOM (`(rf/configure :source-coords {:annotate-dom? true})` per Tool-Pair §Source-mapping), every rendered DOM node carries a `data-rf2-source-coord` attribute pointing back to the registration that produced it. The attribute's value resolves via `re-frame-pair2.runtime/parse-rf2-coord` to a structured `{:ns ... :line ... :file ...}` map keyed off the registration's source coords (auto-captured by `reg-*` macros, per Spec 001 §Source-coordinate capture). This gives you a direct, two-way bridge between a live DOM element and the exact line of source code that rendered it.

**Two attribute formats are recognised:**

- `data-rf2-source-coord` — re-frame2's own annotation when `:annotate-dom?` is on. Stable, preferred.
- `data-rc-src` — re-com's debug-instrumentation attribute. The runtime parses both; if both are present on a node, `data-rf2-source-coord` wins.

**Prerequisites — at least one of:**

- re-frame2 source-coord annotation enabled (`(rf/configure :source-coords {:annotate-dom? true})` at startup), *or*
- re-com debug instrumentation enabled and the call site passed `:src (at)`.

**Degradation is per-element.** When neither is present on a given element, the bridge returns `{:src nil :reason :no-coord-at-this-element}`. When neither annotation is enabled app-wide, every element returns `{:src nil :reason :source-coord-annotation-disabled}`. Tell the user which case they're hitting.

| Op | Invocation | Returns |
|---|---|---|
| `dom/source-at` | `scripts/eval-cljs.sh '(re-frame-pair2.runtime/dom-source-at "#save-button")'` or `'(... :last-clicked)'` | `{:ns :line :file}` for a CSS selector, or for the most recently clicked element |
| `dom/find-by-src` | `scripts/eval-cljs.sh '(re-frame-pair2.runtime/dom-find-by-src "view.cljs" 84)'` | Live DOM elements rendered by that source line |
| `dom/fire-click-at-src` | `scripts/eval-cljs.sh '(re-frame-pair2.runtime/dom-fire-click "view.cljs" 84)'` | Synthesise a click on the element rendered by that line |
| `dom/describe` | `scripts/eval-cljs.sh '(re-frame-pair2.runtime/dom-describe "#save-button")'` | Tag, classes, both source-coord attributes, and any registration metadata they resolve to |

## Live watch (push-mode)

| Op | Invocation | Behaviour |
|---|---|---|
| `watch/window` | `scripts/watch-epochs.sh --window-ms 30000 --event-id-prefix :checkout/` | Runs for N ms, reports every matching epoch, summarises at end |
| `watch/count` | `scripts/watch-epochs.sh --count 5` | Runs until N epochs match |
| `watch/stream` | `scripts/watch-epochs.sh --stream --event-id-prefix :cart/` | Streams until disconnect, idle-timeout, or `watch/stop` |
| `watch/stop` | `scripts/watch-epochs.sh --stop` | Terminates any active watch for this session |

Predicates (any combination): `--event-id`, `--event-id-prefix`, `--effects`, `--timing-ms '>100'`, `--touches-path`, `--sub-ran`, `--render`, `--origin :pair|:app|:ui|:timer|:http`, `--frame :foo`.

Mode rules:

- `--window-ms` and `--count` are independent. `--window-ms` alone runs for N ms with no count limit; `--count` alone runs until N matches with no window timeout. If both are set, the first condition to fire wins. With neither flag (and no `--stream`), the default is a 30 s window.

The watch transport polls the assembled-epoch stream by tracking the last seen `:epoch-id` in the operating frame's history and asking for everything since. See `docs/initial-spec.md` §4.4.

## Hot-reload coordination

After any source edit, before the next dispatch or trace:

```
scripts/tail-build.sh --wait-ms 5000 --probe '(some/probe-form)'
```

`--probe` is a CLJS form chosen to change when the edited code reloads. Good probes for re-frame2:

- After editing a `reg-*` handler: `(rf/handler-meta :event :foo)` — the `:line` / `:column` / `:handler-fn` change after re-registration. Capture the meta map's hash before the edit, compare after.
- After editing a `reg-machine`: `(rf/machine-meta :auth)` — same comparison.
- After editing a view: pick a CLJS form that derefs the view's namespace var (e.g. `(some-ns/my-view)` or `(meta #'some-ns/my-view)`).
- If you don't know a good probe, omit `--probe` and the script falls back to a 300ms timer; the result includes `:soft? true` so you know it's timer-based.

A successful probe-flip also coincides with a `:rf.registry/handler-replaced` trace event arriving in the buffer, so an alternative confirmation is `(filter #(= :rf.registry/handler-replaced (:operation %)) (rf/trace-buffer {:since <pre-edit-id>}))`. Use whichever fits — they're not exclusive.

For the strict source-edit protocol (when to call this, what to do if the probe times out), see [hot-reload-protocol.md](hot-reload-protocol.md).

## Time-travel (epoch restore)

re-frame2 ships first-class time-travel as part of the Tool-Pair contract — no adapter, no internal poking. These ops are **fully implemented** and use only public surfaces.

| Op | Invocation | Purpose |
|---|---|---|
| `epoch/history` | `scripts/eval-cljs.sh '(rf/epoch-history :rf/default)'` | The full ring of `:rf/epoch-record` values for the frame, oldest-first |
| `epoch/restore` | `scripts/eval-cljs.sh '(rf/restore-epoch :rf/default <epoch-id>)'` | Rewind the frame's `app-db` to the named epoch's `:db-after`. Returns `true` on success, `false` on any documented failure mode (see below). |
| `epoch/configure` | `scripts/eval-cljs.sh '(rf/configure :epoch-history {:depth 200})'` | Bump the ring depth (default 50). |
| `undo/step-back` | `scripts/eval-cljs.sh '(re-frame-pair2.runtime/undo-step-back)'` | Sugar: restore the previous epoch in the operating frame |
| `undo/to-epoch` | `scripts/eval-cljs.sh '(re-frame-pair2.runtime/undo-to-epoch <id>)'` | Sugar over `restore-epoch` for the operating frame |

**Documented failure modes** (Tool-Pair §Time-travel — restore is a no-op on failure):

| Failure | Trace operation | When |
|---|---|---|
| Unknown frame | `:rf.error/no-such-handler` (kind `:frame`) | `frame-id` not registered |
| Unknown epoch | `:rf.epoch/restore-unknown-epoch` | `epoch-id` not in current history (aged out or never recorded) |
| Schema mismatch | `:rf.epoch/restore-schema-mismatch` | `:db-after` no longer validates against currently-registered schemas (a schema was tightened since the snapshot) |
| Missing handler | `:rf.epoch/restore-missing-handler` | DB references a registration id no longer in the registrar (e.g. a machine snapshot whose machine was unregistered) |
| Version mismatch | `:rf.epoch/restore-version-mismatch` | Recorded `:rf/snapshot-version` of an active machine is incompatible with the currently-loaded definition (hot-reload bumped it) |
| Concurrent drain | `:rf.epoch/restore-during-drain` | Called while the frame's run-to-completion drain is in flight |

When `restore-epoch` returns `false`, read the matching trace event from `(rf/trace-buffer {:op-type :error})` to get the structured `:tags`, then report to the user.

**Caveat (always tell the user before restoring):** restore rewinds `app-db` only. Side effects that already fired (HTTP requests sent, navigation pushed, localStorage written, `:dispatch-later` already landed) are *not* undone.
