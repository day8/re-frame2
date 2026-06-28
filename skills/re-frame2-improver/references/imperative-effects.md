# Anti-pattern — Imperative interop inside handlers

Direct JS / DOM / browser-API interop inside the body of a `reg-event` handler. The handler stops being a pure function from coeffects to effects; the impurity leaks out of the data-only channels (`:fx` for effects, the coeffect map for inputs).

Two distinct directions of impurity hide under this one heading, and they route to **two different fixes**:

- **Effectful writes** — the handler *mutates the world*: `(.setItem js/localStorage ...)`, `(set! (.-title js/document) ...)`, `(.scrollTo js/window 0 0)`, `(js/console.log ...)`, `(rf/dispatch ...)` invoked inline, `(js/setTimeout ...)`. → wrap as a **data-only fx** (`reg-fx`), or a managed effect for HTTP/timers.
- **Impure / nondeterministic reads** — the handler *reads a value the world supplies*: `(js/Date.now)`, `(.getTime (js/Date.))`, `(js/Math.random)`, `(.getItem js/localStorage ...)`, `crypto.randomUUID`, `js/navigator.language`, or `@(rf/subscribe ...)` from inside the body. → bring the value in as **input data**, not a mid-body read. The right input channel forks on whether the read decides a **durable write** — see [Reads — the durable/diagnostic fork](#reads--the-durablediagnostic-fork-ep-0010) below.

Getting the direction right matters: a read routed to `reg-fx` produces a broken rewrite (an fx can't hand a value *back* into the same handler turn), and a write routed to `reg-cofx` is equally wrong. And within reads, routing a *durable* read to a plain ambient `reg-cofx` is a subtler-but-real defect (the value isn't replayable). Classify the call's direction first, then — for reads — classify durable vs diagnostic, then pick the fix.

## Detection rules

Greppable signals inside `reg-event` handler bodies, **grouped by direction**:

**Write signals → `reg-fx` (effect):**

- `.-prop` setter forms with `set!` — `(set! (.-href js/location) "...")`, `(set! (.-title js/document) "...")`, `(set! (.-className js/document.body) "...")`.
- Mutating `.method` calls on browser globals — `(.setItem js/localStorage ...)`, `(.setItem js/sessionStorage ...)`, `(.scrollTo js/window 0 0)`, `(.focus el)`, `(.alert js/window "...")`, `(js/console.log ...)`.
- `(rf/dispatch ...)` invoked **from inside** a handler body (rather than returned as `:fx [[:dispatch ...]]`) — a sneaky imperative form because it queues without going through the fx data channel.
- Native timer / network calls — `js/setTimeout`, `js/setInterval`, `js/requestAnimationFrame`, `js/fetch` (HTTP belongs in Managed HTTP — see [`manual-retry-loops.md`](manual-retry-loops.md); manual `setTimeout` retry loops are also covered there).

**Read signals → input data (declared recordable cofx / event payload / ambient cofx — see the fork below):**

- Nondeterministic time reads — `(js/Date.now)`, `(.getTime (js/Date.))`, `(js/Date.)` used as a value.
- Randomness — `(js/Math.random)`, `(.random js/Math)`, `(.randomUUID js/crypto)`, any id-generation call.
- Environment / storage **reads** — `(.getItem js/localStorage ...)`, `(.getItem js/sessionStorage ...)`, `js/navigator.language`, `(.-cookie js/document)` read, `js/location.href` / `js/navigator.onLine` reads.
- A **reactive** subscription opened inside a handler body — `(rf/subscribe ...)` or `@(rf/subscribe ...)` — establishes a reaction in whatever evaluation context the drain loop happens to be in and leaks it until GC. That is the leak. **`rf/subscribe-once` is NOT in this bucket** — see the dedicated rule below; flagging it as an anti-pattern is a false finding.

Then **classify each read by destination** (durable write vs diagnostic / host-transient slot — the fork below enumerates both). A durable time read overwhelmingly means: **declare `:rf/time-ms`** rather than hand-rolling a clock cofx.

Structural signal (write): the handler returns a `db` value (or fx-map) **and** has visible side-effects on the way there.
Structural signal (read): the handler's output depends on a value it *fetched mid-body* rather than one it received in `db` / coeffects / the event vector — so the same `[coeffects event]` pair no longer fully determines the result.
Structural signal (durable read): the fetched value is then `assoc`'d / `assoc-in`'d into the returned `:db` (or runtime-db / a resource / a snapshot / a ledger row) — e.g. `:created-at (js/Date.now)`, `:id (random-uuid)`. That is a **durable** read; replaying the same event produces a *different* result, so an ambient cofx is not enough — it must be a recorded fact (a declared `:rf/time-ms`, the event payload, or a recordable cofx).

## Why it's an anti-pattern

The whole point of re-frame's data-driven loop is that handlers describe *what* should happen as data (effects out via `:fx`) and consume *what they need* as data (inputs in via coeffects). A separate registered handler decides *how* an effect happens; a registered cofx decides *how* an input is materialised. Both kinds of leak defeat:

- **Testability** — the handler can no longer run under `dispatch-sync` against a JVM frame without a browser; tests need DOM/time/random mocks. With cofx, a test injects a fixed `:now` / `:new-id`; with fx, a test asserts the emitted `[fx-id args]` without performing it.
- **Time-travel & replay** — Xray and re-frame2-pair restore through re-frame2's epoch surfaces. Imperative *writes* inside handlers can double-write `localStorage` or refocus the wrong element on replay; impure *reads that decide a durable write* make the same epoch replay to a *different value* because `Date.now` / `Math.random` / the generated id moved — the durable-write rule (full statement in the fork section below).
- **Server-side rendering & `:platforms` gating** — [Spec 011](../../../spec/011-SSR.md) SSR runs handlers on the server; `js/document` blows up, and a client-only read (`localStorage`, `navigator`) returns nonsense. Both an fx (`reg-fx`) and a cofx (`reg-cofx`) may declare `:platforms #{:client}` and skip cleanly on the server; an imperative call inlined in a handler cannot be skipped per-platform.
- **Instrumentation** — Spec 009's `:rf.fx/*` trace channel sees only what flows through `reg-fx`; imperative calls are invisible to Xray, Story, and re-frame2-pair surfaces.

A re-frame2 effect is data: a `[fx-id args]` pair the runtime walks and dispatches. A re-frame2 coeffect is data too: a value the runtime stashes under `[:coeffects k]` before the handler runs.

## The canonical fix

Route by direction:

- **Writes → data-only fx.** [`skills/re-frame2/references/fundamentals/fx.md`](../../re-frame2/references/fundamentals/fx.md) — wrap the side-effect in `reg-fx` once, then issue `[[:my-fx args]]` from the handler's `:fx`. The fx-handler body is the one place imperative interop is legitimate. (For HTTP and transport-level retry/timers, reach for Managed HTTP — see [`manual-retry-loops.md`](manual-retry-loops.md).)
- **Reads → input data, by destination.** Route by where the value lands: a **durable write** needs a **recorded fact**; a **diagnostic / host-transient** slot may stay an **ambient** `reg-cofx`. [`skills/re-frame2/references/fundamentals/cofx.md`](../../re-frame2/references/fundamentals/cofx.md) carries the cofx mechanics; the full routing rule (and the three rungs) is [Reads — the durable/diagnostic fork](#reads--the-durablediagnostic-fork-ep-0010) immediately below.

### Reads — the durable/diagnostic fork (EP-0010)

re-frame2's core model is a causal fold: a durable transition is `next-state = f(prev-state, causal-token)`. The **durable-write rule** (Spec 002 §Recordable coeffects; EP-0010 recording / EP-0017 authoring): *if a host fact can affect a durable write, the transition must fold a recorded fact — a declared recordable coeffect or the event payload — never an ambient host read at the write site.* **Durable state folds facts, never reads.** So a read inside a handler routes by **where its value lands**, not merely by being impure:

1. **Durable read → declared recordable coeffect / event payload.** The value is written into app-db, runtime-db, a resource entry, a machine snapshot, a work-ledger row, durable routing state, or a hydration/epoch payload. Examples: a `:created-at` / `:updated-at` timestamp, a generated entity id, a `:loaded-at` on a cached resource.
   - **Durable wall-clock time** is the headline case: the framework's one built-in recordable coeffect, `:rf/time-ms`, is stamped once at enqueue, pinnable by tests/replay, and *replayed from the token on restore*. The fix is **not** a `:now` cofx that re-reads `js/Date` — it is to declare `:rf.cofx/requires [:rf/time-ms]` and read `time-ms` flat. A hand-rolled durable `:now` cofx is itself the milder anti-pattern (two names for one fact, and it re-reads the host on replay unless explicitly recorded).
   - **Generated ids / random choices / durable host facts** (a localStorage value or browser fact that becomes durable state) ride the **event payload** (the caller pins the id — the preferred rung) **or** a **recordable** `reg-cofx` (slice B): a fact with a stable id whose value is EDN-serializable, captured in the replay record, and *re-presented from the record on replay rather than re-read from the host*. A plain ambient cofx here is the defect — it replays to a fresh, different value.
2. **Diagnostic / host-transient read → ambient `reg-cofx` is fine.** The value powers only a dev log, a performance span, always-on error metadata, or a host-transient side-table (a timer handle, an AbortController, a cache key, a monotonic high-water mark) — and does **not** directly decide a durable write. An ordinary ambient `reg-cofx` is correct; demanding a recordable coeffect here is over-engineering.

**How to grade a read finding.** Trace where the value lands: a returned `:db` / `:rf.db/runtime` / resource / snapshot / ledger write → durable → recorded fact (for time, `:rf/time-ms`); only a log / span / side-table → diagnostic → ambient cofx is fine. The rule is **"no hidden host facts in durable writes,"** not "no host reads ever" — don't over-flag a genuinely diagnostic read.

### `subscribe-once` in a handler is NOT an anti-pattern

`rf/subscribe-once` is the **shipped public one-shot, non-reactive read** ([`spec/006-ReactiveSubstrate.md`](../../../spec/006-ReactiveSubstrate.md) §`subscribe-once`; [`spec/API.md`](../../../spec/API.md)). It subscribes, derefs, and unsubscribes in one call — it leaves no reaction behind — and the contract explicitly names event handlers, machine actions, REPL sessions, and SSR builders as legitimate callers. **Do not flag `(rf/subscribe-once [:some/sub])` in a handler body as a correctness finding.**

The cofx wrap (a value-returning `reg-cofx` supplier that materialises the value via `subscribe-once`, declared with `:rf.cofx/requires`) is a **preference**, not the only legal path. Recommend it when the read should be:

- **reusable** across several handlers,
- **parameterised** by name,
- **stubbable** in tests,
- **schema-validated** at the cofx boundary, or
- **visible** as a named input on the handler's coeffect map.

When none of those hold — a one-shot, cache-aware current-value read inside a single handler — a bare `rf/subscribe-once` is correct as written. Converting it to a cofx purely because it appears in a handler is a policy-inverted rewrite; do not suggest it. (Contrast: a **reactive** `@(rf/subscribe ...)` or a retained reaction in a handler body IS still a finding — flag those.)

Spec source: [`spec/Conventions.md`](../../../spec/Conventions.md) (data-only fx) and Cardinal Rule #1 (implementation is ground truth; the runtime's effect-map shape is closed — `:rf.error/effect-map-shape` fires if you try to sneak `:dispatch` or `:http` as a top-level key). `reg-fx` and `reg-cofx` are public `re-frame.core` exports (cofx also ships in the `re-frame.cofx` namespace). `inject-cofx` is **removed** in EP-0017 — coeffect delivery is the `:rf.cofx/requires` registration declaration.

## Worked example

**Before** — both directions of leak in one handler (a write *and* an impure read):

```clojure
(rf/reg-event :prefs/save-theme
  (fn [{:keys [db]} [_ theme]]
    (.setItem js/localStorage "theme" (name theme))                  ;; <-- write: side-effect
    (set! (.-className js/document.body) (str "theme-" (name theme))) ;; <-- write: DOM mutation
    {:db (assoc db :prefs/theme    theme
                   :prefs/saved-at (js/Date.now))}))                  ;; <-- read: nondeterministic input
```

**After** — writes become data-only fx; the impure read is a **durable timestamp** (it lands in `:prefs/saved-at`), so it is the declared recordable `:rf/time-ms` coeffect, **not** a `js/Date`-reading `:now` cofx:

```clojure
;; Writes -> data-only fx (effect performed after the handler returns):
(rf/reg-fx :local-storage/set
  {:platforms #{:client}}                                          ;; browser-only: skipped cleanly under SSR
  (fn [_ctx {:keys [k v]}]
    (when-let [ls (.-localStorage js/globalThis)]                  ;; defensive: globalThis works on every platform
      (.setItem ls k v))))

(rf/reg-fx :dom/set-body-class
  {:platforms #{:client}}
  (fn [_ctx class] (set! (.-className js/document.body) class)))

;; Durable read -> the declared recordable coeffect. :rf/time-ms is the
;; framework's one built-in fact, stamped once at enqueue and replayed from
;; the token on epoch restore. Declare it; read it flat. No :now cofx, no
;; js/Date re-read.
(rf/reg-event :prefs/save-theme
  {:rf.cofx/requires [:rf/time-ms]}                                 ;; declare the fact you fold
  (fn [{:keys [db rf/time-ms]} [_ theme]]                           ;; arrives flat under its id
    {:db (assoc db :prefs/theme    theme
                   :prefs/saved-at time-ms)                         ;; durable timestamp from the token
     :fx [[:local-storage/set    {:k "theme" :v (name theme)}]      ;; effects out as data
          [:dom/set-body-class   (str "theme-" (name theme))]]}))
```

The handler is now a pure function of `[{:db :rf/time-ms} event]`: a test passes the coeffects map as a literal (`{:db {} :rf/time-ms 0}`) or pins `:rf.cofx {:rf/time-ms 0}` in the dispatch opts, asserts the returned `:db`, and asserts the emitted `:fx` vector without touching `localStorage`, the DOM, or the wall clock — and an epoch restore reproduces the exact `:prefs/saved-at` rather than a moved clock value.

> **Contrast — a *diagnostic* time read is fine as an ambient cofx.** If `:prefs/save-theme` instead needed the current millis only to `(js/console.log "saved at" t)` (never written to `db`), a plain ambient `(rf/reg-cofx :log-now (fn [] (.getTime (js/Date.))))` declared with `:rf.cofx/requires [:log-now]` is correct — the value decides no durable write, so it need not be recordable. The fork is about *destination*, not about the call being impure.

## Edge cases — when interop is fine

- **Inside a `reg-fx` or `reg-cofx` handler body itself** — that's the *whole job*. The `reg-fx` body is where the imperative write lives; the `reg-cofx` supplier body is where the impure read lives. Interop is legitimate in both. **One caveat for cofx:** if the cofx's value feeds a *durable* write, the fact must be **recordable** (a recorded value re-presented on replay, not re-read) — an ambient supplier that re-reads `js/Date` on every replay is fine for a diagnostic but wrong for durable state. For durable *time* specifically, skip the cofx entirely and declare `:rf.cofx/requires [:rf/time-ms]`.
- **Pure local computation that happens to use `js/Math` or similar** — `(js/parseInt s 10)`, `(js/Math.max a b)`, `(.toUpperCase s)` are deterministic, side-effect-free function calls, not findings. The line is: does the call (a) observably mutate anything outside the handler, or (b) return a value the world supplies nondeterministically? `parseInt` / `Math.max` do neither; `localStorage.setItem` mutates; `Date.now` / `Math.random` read nondeterministically. Note: `js/Math.random` is **not** in this bucket — it is a nondeterministic read; route it by destination (a durable id/choice rides the event payload or a recordable cofx; a diagnostic jitter value → ambient cofx).
- **A diagnostic / host-transient read that decides no durable write** — a millis read used only for a `console.log` or a perf span, an `AbortController` stashed in a host-transient side-table, a monotonic high-water mark. Per the fork above, an ambient `reg-cofx` is correct here — do **not** flag it as a world-input issue or demand a recordable coeffect.
- **Boot-time DOM reads** that aren't inside any handler (top-level `(def !root (js/document.getElementById "app"))`) — outside the event loop entirely. Not in scope for this anti-pattern.
