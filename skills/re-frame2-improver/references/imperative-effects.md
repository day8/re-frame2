# Anti-pattern — Imperative interop inside handlers

Direct JS / DOM / browser-API interop inside the body of a `reg-event` handler. The handler stops being a pure function from coeffects to effects; the impurity leaks out of the data-only channels (`:fx` for effects, the coeffect map for inputs).

Two directions of impurity hide under this one heading, and they route to **two different fixes**:

- **Effectful writes** — the handler *mutates the world*: `(.setItem js/localStorage …)`, `(set! (.-title js/document) …)`, `(.scrollTo js/window 0 0)`, `(js/console.log …)`, an inline `(rf/dispatch …)`, `(js/setTimeout …)`. → wrap as a **data-only fx** (`reg-fx`), or a managed effect for HTTP/timers.
- **Impure / nondeterministic reads** — the handler *reads a value the world supplies*: `(js/Date.now)`, `(.getTime (js/Date.))`, `(js/Math.random)`, `(.getItem js/localStorage …)`, `crypto.randomUUID`, `js/navigator.language`, `@(rf/subscribe …)`. → bring the value in as **input data**, not a mid-body read. The channel forks on whether the read decides a **durable write** — see [Reads — the durable/diagnostic fork](#reads--the-durablediagnostic-fork-ep-0010).

Getting the direction right matters: a read routed to `reg-fx` produces a broken rewrite (an fx can't hand a value *back* into the same handler turn), and a write routed to `reg-cofx` is equally wrong. Classify direction first; then, for reads, classify durable vs diagnostic.

## Detection rules

Greppable signals inside `reg-event` handler bodies, **grouped by direction**:

**Write signals → `reg-fx` (effect):**

- `set!` on a `.-prop` of a browser global — `(set! (.-href js/location) …)`, `(set! (.-title js/document) …)`, `(set! (.-className js/document.body) …)`.
- Mutating `.method` calls on browser globals — `(.setItem js/localStorage …)`, `(.scrollTo js/window …)`, `(.focus el)`, `(.alert js/window …)`, `(js/console.log …)`.
- `(rf/dispatch …)` invoked **from inside** a handler body (rather than returned as `:fx [[:dispatch …]]`) — it queues without going through the fx data channel.
- Native timer / network calls — `js/setTimeout`, `js/setInterval`, `js/requestAnimationFrame`, `js/fetch` (HTTP belongs in Managed HTTP — see [`manual-retry-loops.md`](manual-retry-loops.md)).

**Read signals → input data (declared recordable cofx / event payload / ambient cofx — see the fork):**

- Nondeterministic time reads — `(js/Date.now)`, `(.getTime (js/Date.))`, `(js/Date.)` used as a value.
- Randomness / id generation — `(js/Math.random)`, `(.randomUUID js/crypto)`, `(random-uuid)`.
- Environment / storage **reads** — `(.getItem js/localStorage …)`, `js/navigator.language`, `js/location.href`, `js/navigator.onLine`.
- A **reactive** subscription opened inside a handler — `(rf/subscribe …)` / `@(rf/subscribe …)` — establishes a reaction in the drain-loop's evaluation context and leaks it until GC. That is the leak. **`rf/subscribe-once` is NOT in this bucket** — see [`subscribe-once`](#subscribe-once-in-a-handler-is-not-an-anti-pattern) below.

**Structural signal (durable read):** the fetched value is `assoc`'d into the returned `:db` (or runtime-db / a resource / a snapshot / a ledger row) — e.g. `:created-at (js/Date.now)`, `:id (random-uuid)`. Replaying the same event then produces a *different* result, so an ambient cofx is not enough — it must be a recorded fact.

## Why it's an anti-pattern

Handlers describe *what* should happen as data (effects out via `:fx`) and consume *what they need* as data (inputs in via coeffects). Both kinds of leak defeat:

- **Testability** — the handler no longer runs under `dispatch-sync` against a JVM frame; tests need DOM/time/random mocks instead of injecting a fixed `:now` / `:new-id` and asserting the emitted `[fx-id args]`.
- **Time-travel & replay** — imperative *writes* can double-write `localStorage` or refocus the wrong element on replay; impure *reads that decide a durable write* replay to a *different value* because `Date.now` / `Math.random` / the generated id moved (the durable-write rule, below).
- **SSR & `:platforms` gating** — [Spec 011](../../../spec/011-SSR.md) runs handlers on the server; `js/document` blows up and client-only reads return nonsense. Both `reg-fx` and `reg-cofx` may declare `:platforms #{:client}` and skip cleanly; an inlined call cannot.
- **Instrumentation** — Spec 009's `:rf.fx/*` trace channel sees only what flows through `reg-fx`; imperative calls are invisible to Xray, Story, and re-frame2-pair.

## The canonical fix

Route by direction:

- **Writes → data-only fx.** [`fx.md`](../../re-frame2/references/fundamentals/fx.md) — wrap the side-effect in `reg-fx` once, then issue `[[:my-fx args]]` from the handler's `:fx`. The fx-handler body is the one place imperative interop is legitimate. (HTTP + transport retry/timers → Managed HTTP, [`manual-retry-loops.md`](manual-retry-loops.md).)
- **Reads → input data, by destination.** A **durable write** needs a **recorded fact**; a **diagnostic / host-transient** slot may stay an **ambient** `reg-cofx`. [`cofx.md`](../../re-frame2/references/fundamentals/cofx.md) owns the cofx mechanics (grades, `:rf/time-ms`, recordable generators); the routing rule is the fork below.

### Reads — the durable/diagnostic fork (EP-0010)

**Durable state folds facts, never reads.** A read inside a handler routes by **where its value lands**, not merely by being impure:

1. **Durable read → declared recordable coeffect / event payload.** The value is written into app-db, runtime-db, a resource, a machine snapshot, a ledger row, or a hydration/epoch payload (a `:created-at` timestamp, a generated id, a `:loaded-at`). It must fold a **recorded fact**, not an ambient host read at the write site.
   - **Durable wall-clock time** is the headline case: declare `:rf.cofx/requires [:rf/time-ms]` and read `time-ms` flat — *not* a `:now` cofx that re-reads `js/Date`. A hand-rolled durable `:now` cofx is itself the milder anti-pattern (it re-reads the host on replay). Mechanics in [`cofx.md`](../../re-frame2/references/fundamentals/cofx.md).
   - **Generated ids / random choices / durable host facts** ride the **event payload** (the caller pins the value — preferred) or a **recordable** `reg-cofx` (a stable id, EDN-serializable value, re-presented from the record on replay). A plain ambient cofx here replays to a different value — the defect.
2. **Diagnostic / host-transient read → ambient `reg-cofx` is fine.** The value powers only a dev log, a perf span, error metadata, or a host-transient side-table (a timer handle, an AbortController, a cache key) and decides no durable write. Demanding a recordable coeffect here is over-engineering.

**How to grade a read finding.** Trace where the value lands: a returned `:db` / runtime-db / resource / snapshot / ledger write → durable → recorded fact; only a log / span / side-table → diagnostic → ambient cofx. The rule is *"no hidden host facts in durable writes,"* not "no host reads ever."

### `subscribe-once` in a handler is NOT an anti-pattern

`rf/subscribe-once` is the shipped public **one-shot, non-reactive read** ([`spec/006-ReactiveSubstrate.md`](../../../spec/006-ReactiveSubstrate.md) §`subscribe-once`; [`spec/API.md`](../../../spec/API.md)). It subscribes, derefs, and unsubscribes in one call, leaving no reaction behind; the contract names **event handlers, REPL sessions, SSR builders, and any non-reactive consumer** as legitimate callers. Three rules for a reviewer:

- **Do not flag `(rf/subscribe-once [:some/sub])` in a handler body.** Converting it to a cofx purely because it appears in a handler is a policy-inverted rewrite. A cofx wrap is a *preference* — recommend it only when the read should be reusable across handlers, parameterised by name, stubbable, schema-validated, or visible as a named coeffect.
- **Do flag a *reactive* `@(rf/subscribe …)` or a retained reaction** in a handler body — that leaks (the write-signals list above).
- **Do flag `subscribe-once` inside a machine callback.** A machine `:guard` / `:action` / `:entry` / `:exit` MUST NOT call `subscribe-once` (nor read app-db any other ambient way): an in-callback ambient read is unrecorded, so replay can select a *different* transition ([`spec/006-ReactiveSubstrate.md`](../../../spec/006-ReactiveSubstrate.md) §`subscribe-once`; [`spec/005-StateMachines.md`](../../../spec/005-StateMachines.md) §Causal host facts). The fix is **payload threading** (the triggering event carries the fact) or a **declared recordable coeffect** on the machine's `:rf.cofx` record.

Spec source: [`spec/Conventions.md`](../../../spec/Conventions.md) (data-only fx) and Cardinal Rule #1. `reg-fx` and `reg-cofx` are public `re-frame.core` exports; there is no `inject-cofx` — coeffect delivery is the `:rf.cofx/requires` declaration.

## Worked example

**Before** — both directions of leak in one handler (a write *and* an impure read):

```clojure
(rf/reg-event :prefs/save-theme
  (fn [{:keys [db]} [_ theme]]
    (.setItem js/localStorage "theme" (name theme))                  ;; write: side-effect
    (set! (.-className js/document.body) (str "theme-" (name theme))) ;; write: DOM mutation
    {:db (assoc db :prefs/theme    theme
                   :prefs/saved-at (js/Date.now))}))                  ;; read → durable :db write
```

**After** — writes become data-only fx; the impure read is a **durable timestamp** (it lands in `:prefs/saved-at`), so it is the declared recordable `:rf/time-ms` coeffect, **not** a `js/Date`-reading `:now` cofx:

```clojure
(rf/reg-fx :local-storage/set
  {:platforms #{:client}}                                          ;; browser-only: skipped under SSR
  (fn [_ctx {:keys [k v]}]
    (when-let [ls (.-localStorage js/globalThis)] (.setItem ls k v))))

(rf/reg-fx :dom/set-body-class
  {:platforms #{:client}}
  (fn [_ctx class] (set! (.-className js/document.body) class)))

(rf/reg-event :prefs/save-theme
  {:rf.cofx/requires [:rf/time-ms]}                                 ;; declare the fact you fold
  (fn [{:keys [db rf/time-ms]} [_ theme]]                           ;; arrives flat under its id
    {:db (assoc db :prefs/theme    theme
                   :prefs/saved-at time-ms)                         ;; durable timestamp from the token
     :fx [[:local-storage/set    {:k "theme" :v (name theme)}]      ;; effects out as data
          [:dom/set-body-class   (str "theme-" (name theme))]]}))
```

The handler is now a pure function of `[{:db :rf/time-ms} event]`: a test passes the coeffects as a literal (`{:db {} :rf/time-ms 0}`), asserts the returned `:db` and emitted `:fx` without touching `localStorage`, the DOM, or the wall clock — and an epoch restore reproduces the exact `:prefs/saved-at`.

## Edge cases — when interop is fine

- **Inside a `reg-fx` / `reg-cofx` body** — that's the whole job. One caveat: if a cofx feeds a *durable* write, the fact must be **recordable** (for durable *time*, skip the cofx and declare `:rf/time-ms`).
- **Pure local computation using `js/Math` or similar** — `(js/parseInt s 10)`, `(js/Math.max a b)`, `(.toUpperCase s)` are deterministic and side-effect-free. Not findings. (Note `js/Math.random` *is* a nondeterministic read — route it by destination.)
- **A diagnostic / host-transient read that decides no durable write** — a millis read used only for a `console.log`, an `AbortController` in a side-table. Ambient `reg-cofx` is correct; do not demand a recordable coeffect.
- **Boot-time DOM reads outside any handler** — `(def !root (js/document.getElementById "app"))` is outside the event loop. Not in scope.
