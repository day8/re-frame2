# Measure before paint (popovers, dropdowns, viewport geometry)

You are building a component-library primitive — a popover, a dropdown, a tooltip, a virtualised table — and it needs to *measure the DOM and place itself before the browser paints*. Read the trigger's rectangle, decide whether the panel opens below or flips above, and apply the position in the same frame, so the user never sees it jump.

This is the one job [`app-db`](../glossary.md#app-db), [subscriptions](../glossary.md#subscription), and events cannot do for you: they are your *application's* state, settled between paints, and geometry is a *host* fact that only exists once the element is in the document. So re-frame.ui gives native component-library code a deliberately small door onto React's own timing — the [`re-frame.ui.react`](../../api/re-frame.ui.md) interop tier — and this recipe is the sanctioned pattern for walking through it.

> **Measure in a layout effect, compute placement with a pure function, apply it, clean up exactly.**

??? info "For JavaScript developers"

    This is the `useRef` + `useLayoutEffect` measure-then-position pattern, unchanged in spirit. The two differences: the *placement maths* lives in a pure `.cljc` function you can unit-test on the JVM with no DOM, and the surface is closed — there is no `useMemo`/`useCallback`/`useState` here, because the compiler already owns per-site identity and [`local`](../../api/re-frame.ui.md) already owns ephemeral state. You reach for `react/use-layout-effect` *only* for measure-before-paint. Listeners and anything deferrable belong in the passive `react/use-effect`.

---

## The three pieces

**1 — A ref for the element.** `ui/ref` — the substrate DOM-node primitive — gives you a stable mutable box whose `.current` React points at the DOM node via a `:ref` prop. Writing it never re-renders — that is exactly why it exists next to `local`.

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

**3 — A layout effect that measures, computes, applies, and cleans up.** `react/use-layout-effect` runs after React has mutated the DOM but *before* the browser paints, so a position you write here is the one the user's eye first sees.

```clojure
(ns my.lib.popover
  (:require [re-frame.ui :as ui :refer [defview local]]
            [re-frame.ui.react :as react]
            [my.lib.geometry :refer [place]]))

(defview popover
  "A native measure-before-paint primitive. `anchor-bottom`/`viewport` arrive as
  ordinary props; the placement is applied before paint, so the panel never
  flashes in the wrong spot."
  [{:keys [anchor-bottom viewport]}]
  (let [el (ui/ref)
        _  (react/use-layout-effect
            (fn []
              (let [node   (.-current el)
                    height (.-offsetHeight node)
                    {:keys [side top]} (place anchor-bottom height viewport)]
                (set! (.. node -dataset -side) (name side))
                (set! (.. node -style -top) (str top "px"))
                ;; clean up EXACTLY what you set — the effect re-runs on every
                ;; deps change and on unmount, so leave no stale placement.
                (fn []
                  (set! (.. node -dataset -side) "")
                  (set! (.. node -style -top) ""))))
            [anchor-bottom viewport])]
    [:div {:ref el :class "popover"}
     "…panel content…"]))
```

The deps vector `[anchor-bottom viewport]` is compared by `rf=` (value equality) against the previous render's deps — re-frame.ui's one effect-dependency doctrine, shared with `effect` and memo — so the panel re-measures whenever the anchor moves or the viewport resizes, and not otherwise. A rebuilt-but-value-equal deps vector is the same dep (no re-measure); host/foreign values fall through to identity.

---

## The rules that keep this honest

**Layout effects are for measure-before-paint, and nothing else.** They block paint, so they are the wrong home for a scroll listener, a `ResizeObserver`, a network call, or anything the user would not notice a frame late. Put those in the passive `react/use-effect`, which runs *after* paint:

```clojure
(react/use-effect
  (fn []
    (let [on-resize #(recompute!)]
      (js/window.addEventListener "resize" on-resize)
      (fn [] (js/window.removeEventListener "resize" on-resize))))
  [])                                   ; [] deps ⇒ attach once, detach on unmount
```

**Every wrapper obeys the position law.** `ui/ref` and `use-layout-effect` are real host hooks, so they are legal only in the straight-line top of the view body — an outer `let` binding, never inside a branch, a loop, or a callback. React's hook order must be static; the compiler rejects a misplaced hook with a didactic message. Add or remove a hook and the view remounts cleanly (its hook signature changed); edit a deps vector or an effect body and state is preserved.

**Application state still goes through events.** Whether the popover is *open* is application state — a `local`, or `app-db` behind an event if other views care. Geometry is the only thing that belongs in the layout effect. Keep the split and both stay testable: the open/close logic through the [event pipeline](../events.md), the placement through a pure `place` function on the JVM.

**On the server there is no layout.** In a JVM/SSR structural render the layout effect does not run and `ui/ref` is inert (`.current` stays nil) — the panel renders its markup, and the real measurement happens once, on the client, after hydration. That is correct: there is nothing to measure until the DOM exists.

---

## See also

- [`re-frame.ui.react` — the interop tier](../../api/re-frame.ui.md) — all seven wrappers and their exact call shapes.
- [Fix a slow view](fix-a-slow-view.md) — the memoisation story for ordinary reactive views (you will almost never need these hooks there).
- [Where should this value live?](../where-state-lives.md) — geometry vs. `local` vs. `app-db`.
