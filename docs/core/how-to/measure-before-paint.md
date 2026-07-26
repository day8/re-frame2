# Measure before paint (popovers, dropdowns, viewport geometry)

You are building a component-library primitive — a popover, a dropdown, a tooltip, a virtualised table — and it needs to *measure the DOM and place itself before the browser paints*. Read the trigger's rectangle, decide whether the panel opens below or flips above, and apply the position in the same frame, so the user never sees it jump.

This is the one job [`app-db`](../glossary.md#app-db), [subscriptions](../glossary.md#subscription), and events cannot do for you: they are your *application's* state, settled between paints, and geometry is a *host* fact that only exists once the element is in the document. So Freehand gives host work exactly one door with before-paint timing — a [registered behavior](../freehand/host/host-boundaries.md#registered-behaviors) declaring `:timing :layout` — and this recipe is the sanctioned pattern for walking through it.

> **Measure in a `:layout` behavior, compute placement with a pure function, apply it, disconnect exactly.**

??? info "For JavaScript developers"

    This is the `useRef` + `useLayoutEffect` measure-then-position pattern, and the timing is the same one: after the host has mutated the DOM, before the browser paints. Three things move. The *ref* is not yours to hold — the behavior is handed the node it owns, as `:node` on its context map, and can reach no other. The *deps array* is `:config`, an ordinary data map compared by `rf=`, so `:update` runs only when the geometry inputs actually move. And the *placement maths* lives in a pure `.cljc` function you can unit-test on the JVM with no DOM. There is no `useState`/`useMemo`/`useCallback` here: application state is app-db behind events, and per-site identity is already owned for you.

---

## The three pieces

**1 — Nothing to ref.** Freehand has no application-held ref, and the recipe does not want one. The behavior's `:connect` / `:update` / `:disconnect` each receive one context map, and `:node` on it *is* the live DOM node. The boundary takes exactly one child element, and the behavior addresses that node and nothing else — no selector, no document query, no handle stored in app-db.

**2 — Pure placement geometry.** Keep the decision — "does this fit below, or flip above?" — in a plain function of numbers. No DOM, no host, no re-frame. It is trivially testable and it reads the same on every host.

```clojure
(defn place
  "Pure: given the trigger's bottom edge, the panel's measured height, and the
  viewport height, decide the panel's side + top. No DOM access."
  [anchor-bottom panel-height viewport-height]
  (if (> (+ anchor-bottom panel-height) viewport-height)
    {:side :above :top (max 0 (- anchor-bottom panel-height))}
    {:side :below :top anchor-bottom}))
```

**3 — A `:layout` behavior that measures, computes, applies, and cleans up.** `:layout` timing runs after the host has mutated the DOM but *before* the browser paints, so a position written there is the one the user's eye first sees. Registration and use site are two halves:

```clojure
(ns my.lib.popover
  (:require [re-frame.freehand :as v]
            [my.lib.geometry :refer [place]]))

(defn- apply-placement! [node {:keys [anchor-bottom viewport]}]
  (let [height             (.-offsetHeight node)
        {:keys [side top]} (place anchor-bottom height viewport)]
    (set! (.. node -dataset -side) (name side))
    (set! (.. node -style -top) (str top "px"))))

(v/defbehavior placed
  "Measure the panel and position it before the browser paints."
  {:timing     :layout
   :connect    (fn [{:keys [node config]}] (apply-placement! node config) nil)
   :update     (fn [{:keys [node config]}] (apply-placement! node config) nil)
   ;; Undo EXACTLY what you set. :disconnect runs once per committed
   ;; connection, and a connect/disconnect pair may be replayed against the
   ;; same node — so teardown has to be total, not best-effort.
   :disconnect (fn [{:keys [node]}]
                 (set! (.. node -dataset -side) "")
                 (set! (.. node -style -top) ""))})

(v/defview popover
  "A native measure-before-paint primitive. `anchor-bottom`/`viewport` arrive as
  ordinary props and ride out as `:config`; the placement is applied before
  paint, so the panel never flashes in the wrong spot."
  [{:keys [anchor-bottom viewport]}]
  [v/behavior {:use    placed
               :target :my.lib/popover-panel
               :config {:anchor-bottom anchor-bottom :viewport viewport}}
   [:div.popover "…panel content…"]])
```

`:config` is the deps vector's honest replacement: `:update` fires only when the committed config **moves by `rf=`** (value equality), and receives `:prev-config` alongside the new one. So the panel re-measures whenever the anchor moves or the viewport resizes, and a re-render that changes nothing touches no host state at all. `:config` is also data at every depth — a node, a ref, a callback or a preconstructed host instance is refused on both hosts — which is exactly what keeps this use site readable by a structural test and by [`v/describe`](../freehand/operate/debugging.md).

---

## The rules that keep this honest

**`:layout` is for measure-before-paint, and nothing else.** It blocks paint, so it is the wrong home for a scroll listener, a network call, or anything the user would not notice a frame late. `:timing` defaults to `:passive` — after paint — and that is where those belong. The set is closed at those two values; there is no third.

**Host listeners live in the connection's private memory.** `:connect`'s return value *is* that memory, and `:disconnect` gets it back, which is how an observer gets torn down exactly once. When the host notices a change the application should know about, dispatch outward — the context's `:dispatch` is fenced to the connection that minted it, so a callback outliving its node is inert rather than firing into a successor:

```clojure
(v/defbehavior watch-panel-size
  "Report the panel's own height as an application fact."
  {:connect    (fn [{:keys [node dispatch]}]          ; :passive — after paint
                 (doto (js/ResizeObserver.
                         (fn [_] (dispatch [:popover/panel-measured
                                            (.-offsetHeight node)])))
                   (.observe node)))
   :disconnect (fn [{:keys [memory]}] (some-> memory .disconnect))})
```

**Application state still goes through events.** Whether the popover is *open* is application state: an `app-db` fact behind an event, read back with `(v/sub …)`. Geometry is the only thing that belongs in the `:layout` behavior. Keep the split and both stay testable — the open/close logic through the [event pipeline](../events.md), the placement through a pure `place` function on the JVM. A viewport height several views care about is the same story: an ordinary application fact, put in app-db by ordinary browser wiring at boot, not a second reactive source hiding in a behavior.

**Re-measuring on demand is a command, not a handle.** If the application must force a re-measure for a reason `:config` cannot express, declare a `:commands` entry and reach it as an effect — the *request* stays data and the imperative call stays inside the behavior. It addresses the `:target` you authored at the use site, and hits only the currently committed connection:

```clojure
{:fx [[:re-frame.freehand.host/command
       {:target :my.lib/popover-panel :op :remeasure}]]}
```

**Connection is commit-only, and replayable.** A behavior connects from the host's own commit, so a render the host abandons — a suspended transition, a superseded render — performs no host work at all. Connect and disconnect may be *replayed* under StrictMode, hot reload or a host recovery, so write `:connect` to tolerate a later fresh connection rather than assuming its first is its only one.

**On the server there is no layout.** In a JVM/SSR structural render the boundary is an inert marker: it records `:use`, `:target` and `:config` with the decorated element as its child, and nothing connects. The panel renders its markup, and the real measurement happens once, on the client, after hydrate. That is correct — there is nothing to measure until the DOM exists.

---

## See also

- [Host boundaries](../freehand/host/host-boundaries.md) — the whole host door: behaviors, commands, React children, `v/->react`, and the [focus/measurement roster](../freehand/host/host-boundaries.md#focus-autofocus-and-measurement-no-neutral-refs) that says when to reach for this at all.
- [JS libraries](../freehand/host/js-libraries.md#animation-checklist) — the same behavior shape driving an imperative animation player, and when `:passive` is enough.
- [Reactivity and ownership](../freehand/authoring/reactivity-and-ownership.md) — the measure-then-compile order for ordinary reactive views (you will almost never need a behavior there).
- [Where should this value live?](../where-state-lives.md) — geometry vs. app-db.
