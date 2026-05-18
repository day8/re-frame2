# Anti-pattern — View-side state for non-render-local concerns

`reagent.core/atom` declared inside (or at the top of) a view function and used to hold state that is **read by another component**, **persisted across the view's lifetime**, or **observed by event handlers**. Equivalent React forms: `useState` / `useRef` / `useReducer` carrying state that ought to live in `app-db`.

## Detection rules

Greppable signals inside `.cljs` / `.cljc` files containing views:

- `(reagent/atom ...)` or `(r/atom ...)` declared **inside** a view function or at the top of a view namespace, and the resulting atom is **named & exported**, or referenced from a sibling view.
- `(let [!state (reagent/atom ...)] ...)` inside a view body whose value is then derefed in a sibling component imported elsewhere.
- React hooks (`uix.core/use-state`, `helix.hooks/use-state`, `(.useState js/React ...)`) holding values like `:current-filter`, `:selected-id`, `:modal-open?`, `:form-data` — concerns the rest of the application would want to query or dispatch about.
- Event handlers (in `events.cljs`) reaching into a view-owned atom — e.g. `(deref view-ns/!current-tab)` from inside a `reg-event-fx`.

Structural signal: the state lives outside `app-db` *but* is consumed by anything other than the very component that declared it.

## Why it's an anti-pattern

The re-frame2 mental model has one source of truth per frame: `app-db`. Subs project read-views; views render those projections; events transition `app-db`. State hidden inside a view's `reagent/atom` is:

- **Invisible to subs** — sibling components must `require` the view's namespace just to deref the atom, coupling otherwise-independent views.
- **Invisible to events** — handlers cannot consult the value as part of their decision (they'd have to reach into the view module, breaking the data-flow direction).
- **Invisible to tools** — Causa's panels and re-frame2-pair's `app-db` inspector/time-travel surfaces see only `app-db` plus the runtime trace/epoch surfaces. A view-side atom is dark to every diagnostic.
- **Invisible to SSR / story / replay** — the story artefact serialises `app-db`; the view-atom is reconstructed only when the view mounts, and its value at render time is not what produced the snapshot.
- **Not testable** — `compute-sub` and `compute-event` operate on `app-db` values; a test for "what does the view show when state is X" requires mounting the view to set the atom.

A `reagent/atom` is legitimate when its state is **render-local** — a hovered-over flag, an in-flight input draft that never leaves the component, an animation tick. The smell is using it as a stand-in for `app-db`.

## The canonical fix

Move the state to `app-db` behind a `reg-sub`. Reads use `(subscribe [:sub-id])`; writes go through `reg-event-db` / `reg-event-fx`. See [`skills/re-frame2/reference/fundamentals/subs.md`](../../re-frame2/reference/fundamentals/subs.md) and [`skills/re-frame2/reference/fundamentals/events.md`](../../re-frame2/reference/fundamentals/events.md).

Spec source: [`spec/Principles.md`](../../../spec/Principles.md) (single source of truth for application state) and [`spec/004-Views.md`](../../../spec/004-Views.md) (views as pure projections).

## Worked example

**Before** — view-side atom shared across components:

```clojure
(ns my-app.dashboard
  (:require [reagent.core :as r]
            [re-frame.core :as rf]))

(defonce !current-tab (r/atom :overview))                       ;; <-- module-level mutable state

(defn tab-buttons []
  [:div
   (for [t [:overview :stats :settings]]
     [:button {:on-click #(reset! !current-tab t)} (name t)])])

(defn tab-content []
  [:div (case @!current-tab                                     ;; deref'd by sibling — coupling
          :overview [overview-pane]
          :stats    [stats-pane]
          :settings [settings-pane])])
```

**After** — `app-db` + sub + events:

```clojure
(rf/reg-event-db :dashboard/select-tab
  (fn [db [_ tab]] (assoc-in db [:dashboard :current-tab] tab)))

(rf/reg-sub :dashboard/current-tab
  (fn [db _] (get-in db [:dashboard :current-tab] :overview)))

(defn tab-buttons []
  [:div
   (for [t [:overview :stats :settings]]
     [:button {:on-click #(rf/dispatch [:dashboard/select-tab t])} (name t)])])

(defn tab-content []
  [:div (case @(rf/subscribe [:dashboard/current-tab])
          :overview [overview-pane]
          :stats    [stats-pane]
          :settings [settings-pane])])
```

## Edge cases — when view-side state is fine

- **Genuinely render-local UI** — hover state, focus-within bookkeeping, drag-and-drop in-flight offsets, transient animation values that no other component consults. These can stay as `reagent/atom` or `useState`.
- **Performance-critical render-loop state** that would cause `app-db` churn at every frame (60fps animation cursors). Promote to `app-db` only when another part of the app needs to read it.
- **Form-draft state in tightly-scoped one-shot forms** that don't need Causa/re-frame2-pair debugging or replay. Once the form gets non-trivial, move it to `app-db` behind a state machine — see [`skills/re-frame2/patterns/forms.md`](../../re-frame2/patterns/forms.md).
- **Component-local refs to DOM nodes** (`useRef`, `(r/create-ref)`) — these hold a *handle*, not domain state. Not in scope.
