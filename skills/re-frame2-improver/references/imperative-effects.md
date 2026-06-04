# Anti-pattern — Imperative interop inside handlers

Direct JS / DOM / browser-API interop inside the body of a `reg-event-db` or `reg-event-fx` handler. The handler stops being a pure function from `db` (and coeffects) to `db`; the impurity leaks out of the data-only channels (`:fx` for effects, the coeffect map for inputs).

Two distinct directions of impurity hide under this one heading, and they route to **two different fixes**:

- **Effectful writes** — the handler *mutates the world*: `(.setItem js/localStorage ...)`, `(set! (.-title js/document) ...)`, `(.scrollTo js/window 0 0)`, `(js/console.log ...)`, `(rf/dispatch ...)` invoked inline, `(js/setTimeout ...)`. → wrap as a **data-only fx** (`reg-fx`), or a managed effect for HTTP/timers.
- **Impure / nondeterministic reads** — the handler *reads a value the world supplies*: `(js/Date.now)`, `(.getTime (js/Date.))`, `(js/Math.random)`, `(.getItem js/localStorage ...)`, `crypto.randomUUID`, `js/navigator.language`, or `@(rf/subscribe ...)` from inside the body. → inject as a **coeffect** (`reg-cofx` + `inject-cofx`, or an inline cofx interceptor).

Getting the direction right matters: a read routed to `reg-fx` produces a broken rewrite (an fx can't hand a value *back* into the same handler turn), and a write routed to `reg-cofx` is equally wrong. Classify the call's direction first, then pick the fix.

## Detection rules

Greppable signals inside `reg-event-db` / `reg-event-fx` handler bodies, **grouped by direction**:

**Write signals → `reg-fx` (effect):**

- `.-prop` setter forms with `set!` — `(set! (.-href js/location) "...")`, `(set! (.-title js/document) "...")`, `(set! (.-className js/document.body) "...")`.
- Mutating `.method` calls on browser globals — `(.setItem js/localStorage ...)`, `(.setItem js/sessionStorage ...)`, `(.scrollTo js/window 0 0)`, `(.focus el)`, `(.alert js/window "...")`, `(js/console.log ...)`.
- `(rf/dispatch ...)` invoked **from inside** a handler body (rather than returned as `:fx [[:dispatch ...]]`) — a sneaky imperative form because it queues without going through the fx data channel.
- Native timer / network calls — `js/setTimeout`, `js/setInterval`, `js/requestAnimationFrame`, `js/fetch` (HTTP belongs in Managed HTTP — see [`manual-retry-loops.md`](manual-retry-loops.md); manual `setTimeout` retry loops are also covered there).

**Read signals → `reg-cofx` / `inject-cofx` (coeffect):**

- Nondeterministic time reads — `(js/Date.now)`, `(.getTime (js/Date.))`, `(js/Date.)` used as a value.
- Randomness — `(js/Math.random)`, `(.random js/Math)`, `(.randomUUID js/crypto)`, any id-generation call.
- Environment / storage **reads** — `(.getItem js/localStorage ...)`, `(.getItem js/sessionStorage ...)`, `js/navigator.language`, `(.-cookie js/document)` read.
- `(rf/subscribe ...)` / `@(rf/subscribe ...)` / `rf/subscribe-once` invoked **from inside** a handler body to read a sub's current value.

Structural signal (write): the handler returns a `db` value (or fx-map) **and** has visible side-effects on the way there.
Structural signal (read): the handler's output depends on a value it *fetched mid-body* rather than one it received in `db` / coeffects / the event vector — so the same `[coeffects event]` pair no longer fully determines the result.

## Why it's an anti-pattern

The whole point of re-frame's data-driven loop is that handlers describe *what* should happen as data (effects out via `:fx`) and consume *what they need* as data (inputs in via coeffects). A separate registered handler decides *how* an effect happens; a registered cofx decides *how* an input is materialised. Both kinds of leak defeat:

- **Testability** — the handler can no longer run under `dispatch-sync` against a JVM frame without a browser; tests need DOM/time/random mocks. With cofx, a test injects a fixed `:now` / `:new-id`; with fx, a test asserts the emitted `[fx-id args]` without performing it.
- **Time-travel & replay** — Xray and re-frame2-pair restore through re-frame2's epoch surfaces. Imperative *writes* inside handlers can double-write `localStorage` or refocus the wrong element on replay; impure *reads* make the same epoch replay to a different result because `Date.now` / `Math.random` moved.
- **Server-side rendering** — Spec 011 SSR runs handlers on the server; `js/document` blows up, and a client-only read (`localStorage`, `navigator`) returns nonsense. A `:platforms #{:client}` fx or cofx is skipped cleanly on the server instead.
- **Instrumentation** — Spec 009's `:rf.fx/*` trace channel sees only what flows through `reg-fx`; imperative calls are invisible to Xray, Story, and re-frame2-pair surfaces.
- **`:platforms` gating** — [Spec 011 (SSR)](../../../spec/011-SSR.md) lets both an fx (`reg-fx`) and a cofx (`reg-cofx`) declare `:platforms`; an imperative call inlined in a handler cannot be skipped per-platform.

A re-frame2 effect is data: a `[fx-id args]` pair the runtime walks and dispatches. A re-frame2 coeffect is data too: a value the runtime stashes under `[:coeffects k]` before the handler runs.

## The canonical fix

Route by direction:

- **Writes → data-only fx.** [`skills/re-frame2/references/fundamentals/fx.md`](../../re-frame2/references/fundamentals/fx.md) — wrap the side-effect in `reg-fx` once, then issue `[[:my-fx args]]` from the handler's `:fx`. The fx-handler body is the one place imperative interop is legitimate. (For HTTP and transport-level retry/timers, reach for Managed HTTP — see [`manual-retry-loops.md`](manual-retry-loops.md).)
- **Reads → coeffect.** [`skills/re-frame2/references/fundamentals/cofx.md`](../../re-frame2/references/fundamentals/cofx.md) — register the impure read as `reg-cofx` and attach it with `inject-cofx` in the event's interceptor vector; the handler then destructures the value (`:now`, `:new-id`, the localStorage value, the sub value) off its coeffect map. For a one-off read used in a single event, the inline-interceptor escape hatch (`{:id ... :before (fn [ctx] (assoc-in ctx [:coeffects k] v))}`) avoids the registry hop — see cofx.md §When `reg-cofx` is overkill. Reading a sub from a handler has its own canonical wrap (`reg-cofx` + `subscribe-once`) — cofx.md §Reading a sub from a handler.

Spec source: [`spec/Conventions.md`](../../../spec/Conventions.md) (data-only fx) and Cardinal Rule #1 (implementation is ground truth; the runtime's effect-map shape is closed — `:rf.error/effect-map-shape` fires if you try to sneak `:dispatch` or `:http` as a top-level key). `reg-fx`, `reg-cofx`, and `inject-cofx` are all public `re-frame.core` exports (`implementation/core/src/re_frame/core.cljc`; cofx in `implementation/core/src/re_frame/cofx.cljc`).

## Worked example

**Before** — both directions of leak in one handler (a write *and* an impure read):

```clojure
(rf/reg-event-db :prefs/save-theme
  (fn [db [_ theme]]
    (.setItem js/localStorage "theme" (name theme))                  ;; <-- write: side-effect
    (set! (.-className js/document.body) (str "theme-" (name theme))) ;; <-- write: DOM mutation
    (assoc db :prefs/theme       theme
              :prefs/saved-at    (js/Date.now))))                     ;; <-- read: nondeterministic input
```

**After** — writes become data-only fx; the impure read becomes a coeffect:

```clojure
;; Reads -> coeffect (input materialised before the handler runs):
(rf/reg-cofx :now
  {:doc "Inject the current wall-clock millis under :now."}
  (fn [ctx] (assoc-in ctx [:coeffects :now] (.getTime (js/Date.)))))

;; Writes -> data-only fx (effect performed after the handler returns):
(rf/reg-fx :local-storage/set
  (fn [_ctx {:keys [k v]}] (.setItem js/localStorage k v)))

(rf/reg-fx :dom/set-body-class
  {:platforms #{:client}}
  (fn [_ctx class] (set! (.-className js/document.body) class)))

(rf/reg-event-fx :prefs/save-theme
  [(rf/inject-cofx :now)]                                            ;; cofx in the interceptor slot
  (fn [{:keys [db now]} [_ theme]]                                   ;; :now arrives as data
    {:db (assoc db :prefs/theme    theme
                   :prefs/saved-at now)
     :fx [[:local-storage/set    {:k "theme" :v (name theme)}]       ;; effects out as data
          [:dom/set-body-class   (str "theme-" (name theme))]]}))
```

The handler is now a pure function of `[{:db :now} event]`: tests inject a fixed `:now`, assert the returned `:db`, and assert the emitted `:fx` vector without touching `localStorage` or the DOM.

## Edge cases — when interop is fine

- **Inside a `reg-fx` or `reg-cofx` handler body itself** — that's the *whole job*. The `reg-fx` body is where the imperative write lives; the `reg-cofx` body is where the impure read lives. Interop is legitimate in both.
- **Pure local computation that happens to use `js/Math` or similar** — `(js/parseInt s 10)`, `(js/Math.max a b)`, `(.toUpperCase s)` are deterministic, side-effect-free function calls, not findings. The line is: does the call (a) observably mutate anything outside the handler, or (b) return a value the world supplies nondeterministically? `parseInt` / `Math.max` do neither; `localStorage.setItem` mutates; `Date.now` / `Math.random` read nondeterministically. Note: `js/Math.random` is **not** in this bucket — it is a nondeterministic read and routes to cofx.
- **Boot-time DOM reads** that aren't inside any handler (top-level `(def !root (js/document.getElementById "app"))`) — outside the event loop entirely. Not in scope for this anti-pattern.
