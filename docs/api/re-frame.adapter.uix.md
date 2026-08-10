# re-frame.adapter.uix

The UIx adapter connects re-frame2's substrate-agnostic core to UIx, a hooks-first React substrate. It exposes:

- the hooks `use-subscribe`, `use-frame`, and `use-current-frame`;
- the `frame-provider` (SCOPE) + `frame-root` (ENSURE) components;
- the `adapter` spec map you pass to `init!`;
- adapter seams for tests, SSR, and code-gen.

It ships in the `day8/re-frame2-uix` artefact. The dependency direction is one-way: the adapter depends on `re-frame.core`, never the reverse. There is no auto-injection; UIx components read subscriptions with `use-subscribe` and take their frame ops (`dispatch`) off the `use-frame` hook directly.

```clojure
(:require [re-frame.adapter.uix :as uix-adapter])
```

Register views by Var (the React-component idiom) or with `rf/reg-view*` for registry-keyed view addressing. `reg-view` (the Reagent macro) does not cover UIx. A minimal app wires the adapter into `init!` and reads subscriptions through the hook:

```clojure
(:require [re-frame.core :as rf]
          [re-frame.adapter.uix :as uix-adapter]
          [uix.core :refer [$ defui]])

(rf/init! uix-adapter/adapter)

(defui cart-row [{:keys [item]}]
  (let [count (uix-adapter/use-subscribe [:cart/count])]
    ($ :tr
       ($ :td (:name item))
       ($ :td count))))
```

For narrative coverage and the substrate decision set, see [Use UIx or reagent-slim](../core/how-to/use-uix-or-slim.md).

## Adapter spec

### `adapter`

- **Kind**: Var (map)
- **Signature**:
  ```clojure
  {:kind                      :rf.adapter/uix
   :make-state-container      …
   :read-container            …
   :replace-container!        …
   :subscribe-container       …
   :make-derived-value        …
   :render                    …
   :render-to-string          …
   :register-context-provider …
   :flush-render!             …
   :dispose-adapter!          …}
  ```
- **Description**: The adapter spec passed to `(rf/init! ...)`. Implements the same substrate adapter contract as the Reagent adapter. `:kind` is `:rf.adapter/uix`.
- **Example**:
  ```clojure
  (rf/init! uix-adapter/adapter)
  ```

## Hooks

### `use-subscribe`

- **Kind**: UIx hook (function)
- **Signature**:
  ```clojure
  (use-subscribe query-v) → current sub value
  (use-subscribe frame-kw query-v) → current sub value
  ```
- **Description**: Subscribe inside a UIx component. This is the hook-shaped equivalent of `subscribe`.

  Returns the current sub value and re-renders the calling component when the value changes.

  - The 1-arg form resolves the frame through the standard chain: `with-frame` dynamic scope first, then the surrounding `frame-provider` (SCOPE) / `frame-root` (ENSURE) via React context. It raises `:rf.error/no-frame-context` when neither is in scope (there is no `:rf/default` floor).
  - The 2-arg form pins to an explicit frame-id, bypassing the chain.
- **Example**:
  ```clojure
  (defui cart-total []
    (let [total (uix-adapter/use-subscribe [:cart/total])]
      ($ :span total)))
  ```

### `use-frame`

- **Kind**: UIx hook (function)
- **Signature**:
  ```clojure
  (use-frame) → {:frame … :dispatch … :dispatch-sync … :subscribe …}
  ```
- **Description**: Returns the frame ops map for the ambient frame — exactly what `(rf/capture-frame)` returns (the frame-locked ops map), captured in hook position. This is how a UIx component gets hold of `dispatch` (and the other frame-locked ops) without auto-injection: destructure `dispatch` off it and close over that.

  `capture-frame` is *the* hold primitive; `reg-view` injection (Reagent) and `use-frame` (UIx) are its two ergonomic spellings — one primitive, three faces.

  - Frame resolution matches `use-subscribe`: `with-frame` dynamic scope first, then the surrounding `frame-provider` / `frame-root` via React context. It raises `:rf.error/no-frame-context` when neither is in scope.
  - The returned map is reference-stable across re-renders for the same resolved frame *incarnation* (safe in effect deps and child props). A provider swap re-renders the caller and yields a map locked to the new frame — and so does destroying the resolved frame and creating another under the same id, because a frame keyword is an address and the ops bundle is pinned to the incarnation it was captured against.
  - No options map, no variants — for an explicit frame, call `(rf/capture-frame frame-id)` directly.
- **Example**:
  ```clojure
  (defui counter-buttons []
    (let [count              (uix-adapter/use-subscribe [:counter/value])
          {:keys [dispatch]} (uix-adapter/use-frame)]
      ($ :button {:on-click #(dispatch [:counter/inc])} "+")))
  ```

### `use-current-frame`

- **Kind**: UIx hook (function)
- **Signature**:
  ```clojure
  (use-current-frame) → frame-kw, or :rf.frame/no-provider
  ```
- **Description**: Returns the frame keyword supplied by the surrounding `frame-provider` (SCOPE) or `frame-root` (ENSURE) — both install the one shared React context this read consults. It exists for components that thread the frame through hand-written child callbacks.

  This hook reads the React-context tier only. When neither `frame-provider` nor `frame-root` sits above, it returns the no-provider sentinel `:rf.frame/no-provider` — never nil, and never a synthesised default. It does not consult the `with-frame` dynamic var; for the full resolution chain, use `rf/current-frame-id`.

> **NOT USED** — no call sites found in `implementation/`, `examples/`, or `tools/`.

## Components

### `frame-provider`

- **Kind**: UIx component (function — SCOPE-only)
- **Signature**:
  ```clojure
  ($ uix-adapter/frame-provider {:frame :session} child…)   ;; SCOPE an existing frame
  ```
- **Description**: The UIx-shaped SCOPE-only frame provider (rf2-nyea0r split — **roots ensure; providers scope**; for create-if-absent, use [`frame-root`](#frame-root)). Scopes an already-created frame; creates nothing. Raises:
  - `:rf.error/frame-provider-frame-absent` when the frame does not exist
  - `:rf.error/no-frame-context` on a nil `:frame`
  - `:rf.error/bad-frame-provider-arg` on a `:frame` that is neither a keyword nor a live frame value
  - `:rf.error/frame-provider-given-id` when given an `:id` (the ENSURE key — use `frame-root`)

  Children ride the idiomatic `$` trailing-args channel. Pass them after the prop map, as for any other UIx component (there is no `:children` prop-map key).
- **Example**:
  ```clojure
  ($ uix-adapter/frame-provider {:frame :session}
     ($ dashboard))
  ```

### `frame-root`

- **Kind**: UIx component (function — ENSURE, a commit-owned two-pass boundary)
- **Signature**:
  ```clojure
  ($ uix-adapter/frame-root {:id :session :images [session-image]} child…)   ;; ENSURE create-if-absent / reuse
  ```
- **Description**: The UIx-shaped ENSURE component (rf2-nyea0r split). Creates the named frame if absent, or reuses it without re-seeding if present; **never destroys the frame on unmount**. Accepts `make-frame` opts, including `:images` / `:initial-events`. `:id` must be a keyword; a missing/nil/non-keyword `:id` raises `:rf.error/frame-root-missing-id`.
  - **Commit-owned two-pass**: the create/seed runs in a client `useLayoutEffect` (at commit), not during render — the first render emits no descendant subtree, and children render only after the frame is live. A render React discards before commit creates + seeds nothing (no ghost frame).
  - Re-mounting under the same `:id` (hot reload, React StrictMode dev double-invoke) neither destroys durable state nor re-runs `:initial-events`. A mounted `:id`/opts change raises `:rf.error/frame-root-reconfigured`; a stray `:frame` raises `:rf.error/frame-root-given-frame`.

  Children ride the idiomatic `$` trailing-args channel.
- **Example**:
  ```clojure
  ;; create the frame on first mount, seed it once via :initial-events,
  ;; reuse (no re-seed) on hot-reload re-mount.
  ($ uix-adapter/frame-root {:id :app :initial-events [[:counter/initialise]]}
     ($ counter-app))
  ```

## Adapter seams

### `wrap-view`

- **Kind**: function
- **Signature**:
  ```clojure
  (wrap-view id metadata user-fn) → wrapped fn
  ```
- **Description**: Adapter-side source-coord injection: wraps a component head so its rendered root DOM element carries `data-rf2-source-coord` in debug builds (see the Notes below). Most users register through `reg-view*`; `wrap-view` is for code-gen and library scaffolding.
- **Example**:
  ```clojure
  ;; Code-gen / scaffolding seam: wrap a component head so its root DOM element
  ;; carries data-rf2-source-coord (dev only; elided in production builds).
  (def wrapped-row
    (uix-adapter/wrap-view ::row {:line 42 :column 7}
                           (fn [_props] ($ :div "row"))))
  ```

### `flush-views!`

- **Kind**: function
- **Signature**:
  ```clojure
  (flush-views!)
  (flush-views! f)
  ```
- **Description**: Wraps React's `act()` for tests. Flushes pending renders synchronously and returns nil.
- **Example**:
  ```clojure
  ;; Test-only: flush pending renders synchronously, returns nil.
  (uix-adapter/flush-views!)               ;; 0-arity: drain queued renders + effects
  (uix-adapter/flush-views! (fn [] nil))   ;; 1-arity: run the thunk inside act()
  ```

### `set-hiccup-emitter!`

- **Kind**: function
- **Signature**:
  ```clojure
  (set-hiccup-emitter! f)
  ```
- **Description**: Install a render-tree → HTML fn. This is the UIx side of the SSR late-bind seam, at parity with the Reagent adapter. Normally you don't call this directly; requiring `re-frame.ssr` wires the emitter for you. Pass `nil` to reset.
- **Example**:
  ```clojure
  ;; SSR: install a render-tree → HTML emitter (normally wired for you by
  ;; requiring re-frame.ssr). Pass nil to reset.
  (uix-adapter/set-hiccup-emitter! (fn [tree _opts] (str tree)))
  (uix-adapter/set-hiccup-emitter! nil)
  ```

## Notes

- **Shared React Context.** The `frame-provider` in both adapters (Reagent and UIx) consumes the same `createContext` object, factored into `re-frame.adapter.context` (a CLJS-only file in core). There is exactly one Context, not two. A mixed-substrate app therefore composes: a UIx `frame-provider` can wrap a Reagent subtree, and vice versa.
- **DOM source-coord annotations.** Adapters inject `data-rf2-source-coord` on every registered view's root element; `wrap-view` is the explicit seam for that injection. The attribute is gated on debug builds and elided from production `:advanced` builds via dead-code elimination, so it costs no shipped bytes. It powers click-to-source in Xray and re-frame2-pair. The full contract is in the [Observability concept guide](../core/observability.md).
- **Controlled inputs use React's own implementation.** UIx can build a `<input>` two ways, and unset it chooses by asking whether Reagent happens to be on the classpath — so adding the Reagent adapter beside UIx used to change how the UIx app's inputs behaved, silently. Requiring `re-frame.adapter.uix` pins the choice to React's own path. See the note below for what that means for the caret.

## Controlled inputs and the caret

A UIx `:input` with a `:value` and an `:on-change` is a plain React controlled input, and it stays one whatever else is in the bundle. Requiring this namespace sets `uix.compiler.input/*use-reagent-input-enabled?*` to `false` at load, which is what makes that true.

**What this means when your handler refuses or rewrites a keystroke.** React converges the field inside the discrete event — the character the model refused is off the screen before `dispatchEvent` returns, with nothing re-rendered. Writing `value` moves the text cursor to the end of the field, though, so the caret lands at the end rather than where the edit was: type `z` into `"12345"` with the caret at position 2, have the model refuse it, and you get `"12345"` with the caret at 5. Every write React makes does this; it is React's own long-standing controlled-input caret jump, not something re-frame2 introduces. A model that takes the keystroke verbatim never triggers a write and never moves the caret.

**What changed, and why.** UIx also ships a port of Reagent's controlled-input workaround, which makes the element uncontrolled and restores value *and* caret itself — but one animation frame later, off Reagent's `requestAnimationFrame` queue, never inside the event. Before this pin you got that one whenever Reagent was on the classpath and React's one when it wasn't. An app whose inputs behave differently because of what else is in its bundle is the worse defect, so the adapter takes in-turn convergence and a predictable, React-native path over late caret preservation. Neither implementation gives you both halves; `rf2-fki5d` is the priced route to a path that does.

**If you want the port instead**, ask for it explicitly — after requiring the adapter, and before you render:

```clojure
(:require [re-frame.adapter.uix :as uix-adapter]
          [uix.compiler.input])

(set! uix.compiler.input/*use-reagent-input-enabled?* true)
```

That is the whole opt-in, and it is deliberately explicit. `true` selects UIx's port, `false` selects React's implementation, and `nil` restores UIx's own classpath-sniffing default — which is the behaviour this pin exists to keep you out of. The port reaches for `reagent.impl.batching`, so it needs Reagent on the classpath; it is not an option for a UIx-only bundle.

## See also

- [re-frame.core](re-frame.core.md) — the substrate-agnostic ergonomic surface (`capture-frame`, `with-frame`, `with-new-frame`, `frame-provider`) plus the `init!` / `install-adapter!` / `current-adapter` / `adapter-disposed?` lifecycle.
- [re-frame.adapter.reagent](re-frame.adapter.reagent.md) — the default (inline) substrate.
- [Use UIx or reagent-slim](../core/how-to/use-uix-or-slim.md) — narrative coverage with worked examples and the full decision set.
- [Adapter (glossary)](../core/glossary.md#adapter) — the substrate seam, defined.
