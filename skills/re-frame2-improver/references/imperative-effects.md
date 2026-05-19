# Anti-pattern — Imperative effects inside handlers

Direct JS / DOM / browser-API calls inside the body of a `reg-event-db` or `reg-event-fx` handler — `(.setItem js/localStorage ...)`, `(js/console.log ...)`, `(.scrollTo js/window ...)`, `(set! (.-title js/document) ...)`. The handler stops being a pure function from `db` to `db`; the side-effect leaks out of the data-only `:fx` channel.

## Detection rules

Greppable signals inside `reg-event-db` / `reg-event-fx` handler bodies:

- `js/` interop calls — `js/localStorage`, `js/sessionStorage`, `js/document`, `js/window`, `js/console`, `js/fetch`, `js/Math.random`, `js/Date.now`.
- `.-prop` setter forms with `set!` — `(set! (.-href js/location) "...")`, `(set! (.-title js/document) "...")`.
- Direct `.method` calls on browser globals — `(.scrollTo js/window 0 0)`, `(.focus el)`, `(.preventDefault e)`, `(.alert js/window "...")`.
- `(rf/dispatch ...)` invoked **from inside** a handler body (rather than returned as `:fx [[:dispatch ...]]`) — a sneaky imperative form because it queues without going through the fx data channel.
- Native timer calls — `js/setTimeout`, `js/setInterval`, `js/requestAnimationFrame` (manual `setTimeout` retry loops also covered by [`manual-retry-loops.md`](manual-retry-loops.md)).

Structural signal: the handler returns a `db` value (or an fx-map) **and** has visible side-effects on the way there.

## Why it's an anti-pattern

The whole point of re-frame's data-driven loop is that handlers describe *what* should happen as data, and a separate registered fx-handler decides *how* it happens. Imperative leaks defeat:

- **Testability** — the handler can no longer run under `compute-event` without a browser; tests need DOM mocks.
- **Time-travel & replay** — Causa and re-frame2-pair restore through re-frame2's epoch surfaces; imperative side-effects inside handlers cannot be represented as data, so replay/restoration can double-write `localStorage` or refocus the wrong element.
- **Server-side rendering** — Spec 011 SSR runs handlers on the server; `js/document` blows up.
- **Instrumentation** — Spec 009's `:rf.fx/*` trace channel sees only what flows through `reg-fx`; imperative calls are invisible to the inspector, story, and pair-tool surfaces.
- **`:platforms` gating** — Spec 003 lets an fx declare `:platforms #{:client}`; an imperative side-effect inside a handler cannot be skipped on the server.

A re-frame2 effect is data: a `[fx-id args]` pair. The runtime walks the `:fx` vector and dispatches to the registered handler.

## The canonical fix

[`skills/re-frame2/references/fundamentals/fx.md`](../../re-frame2/references/fundamentals/fx.md) — wrap the side-effect in `reg-fx` once, then issue `[[:my-fx args]]` from the handler. The fx-handler is the one place where imperative interop is legitimate.

Spec source: [`spec/Conventions.md`](../../../spec/Conventions.md) (data-only fx) and Cardinal Rule #1 (implementation is ground truth; the runtime's effect-map shape is closed — `:rf.error/effect-map-shape` fires if you try to sneak `:dispatch` or `:http` as a top-level key).

## Worked example

**Before** — imperative side-effect in the handler:

```clojure
(rf/reg-event-db :prefs/save-theme
  (fn [db [_ theme]]
    (.setItem js/localStorage "theme" (name theme))            ;; <-- imperative side-effect
    (set! (.-className js/document.body) (str "theme-" (name theme)))   ;; <-- DOM mutation
    (assoc db :prefs/theme theme)))
```

**After** — data-only fx:

```clojure
(rf/reg-fx :local-storage/set
  (fn [_ctx {:keys [k v]}] (.setItem js/localStorage k v)))

(rf/reg-fx :dom/set-body-class
  {:platforms #{:client}}
  (fn [_ctx class] (set! (.-className js/document.body) class)))

(rf/reg-event-fx :prefs/save-theme
  (fn [{:keys [db]} [_ theme]]
    {:db (assoc db :prefs/theme theme)
     :fx [[:local-storage/set    {:k "theme" :v (name theme)}]
          [:dom/set-body-class   (str "theme-" (name theme))]]}))
```

## Edge cases — when imperative is fine

- **Inside a `reg-fx` handler body itself** — that's the *whole job* of an fx-handler. Imperative interop is legitimate here.
- **Pure local computation that happens to use `js/Math` or similar** — `(js/parseInt s 10)`, `(js/Math.max a b)` are pure function calls, not side-effects. The line is: does the call observably mutate anything outside the handler? `parseInt` doesn't; `localStorage.setItem` does.
- **Boot-time DOM reads** that aren't inside any handler (top-level `(def !root (js/document.getElementById "app"))`) — outside the event loop entirely. Not in scope for this anti-pattern.
