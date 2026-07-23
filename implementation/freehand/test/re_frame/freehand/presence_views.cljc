(ns re-frame.freehand.presence-views
  "The declared views the `FH-PRESENCE` structural rows render, INTERPRETED.

  A view's `:view-id` is derived from the namespace it is declared in, and
  the `FH-PRESENCE-001` fixture pins expected structural trees LITERALLY —
  view-ids included — so these declarations give the fixture a stable
  spelling to name, and let the structural suite (both hosts) render the
  SAME declaration the compiled twin in
  [[re-frame.freehand.presence-views-compiled]] renders.

  Everything here is host-neutral: no subscriptions, no host objects, no
  React. `(v/presence …)` is called as an ordinary function in the
  interpreted body and returns a reserved-head vector the structural walk
  lowers to the presence node — the same node the compiled emitter builds."
  (:require [re-frame.freehand :as v]))

(v/defview toasts
  "A presence boundary over two literal keyed children — the smallest shape
  that pins the structural projection: the `:rf.ui/presence` marker, the
  terminal `:timeout-ms`, and the retained children rendered `:present`."
  [_]
  [:div.stack
   (v/presence {:timeout-ms 300}
     [:div.toast {:key "a"} "A"]
     [:div.toast {:key "b"} "B"])])

(v/defview rooted
  "A presence boundary as the ROOT of a declared view — proves the marker
  survives view-boundary adoption (a fragment-shaped node carrying the
  presence marker is NOT flattened into the boundary)."
  [_]
  (v/presence {:timeout-ms 250}
    [:li.row {:key 1} "one"]
    [:li.row {:key 2} "two"]))

(def by-name
  "Fixture view-name keyword -> the declared view. A fixture is EDN, so it
  names a view rather than carrying one."
  {:toasts toasts
   :rooted rooted})
