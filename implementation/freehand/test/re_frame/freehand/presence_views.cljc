(ns re-frame.freehand.presence-views
  "The declared views the `FH-PRESENCE` structural rows render, INTERPRETED.

  A view's `:view-id` is derived from the namespace it is declared in, and
  the `FH-PRESENCE-001` / `FH-PRESENCE-004` fixtures pin expected structural
  trees LITERALLY — view-ids included — so these declarations give the
  fixtures a stable spelling to name, and let the structural suite (both
  hosts) render the SAME declaration the compiled twin in
  [[re-frame.freehand.presence-views-compiled]] renders. `FH-PRESENCE-001`
  pins the presence NODE; `FH-PRESENCE-004` pins the presence-aware CHILD's
  own phase read.

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

;; ---------------------------------------------------------------------------
;; FH-PRESENCE-004 — the presence-aware CHILD, which reads its own phase
;; ---------------------------------------------------------------------------

(v/defview phase-card
  "The presence-aware child Spec 004 §Presence teaches: it owns its exit
  styling and accessibility by reading its OWN `(v/presence-phase)`.

  Nothing here is host-bearing — the read is the whole point. It is declared
  OUTSIDE any boundary as well as used inside one, because `:present`
  outside a boundary is half the phase read's contract."
  [{:keys [label]}]
  (let [phase    (v/presence-phase)
        exiting? (= :unmounting phase)]
    [:div.toast {:class       (when exiting? "toast--exit")
                 :aria-hidden (when exiting? "true")
                 :data-phase  (name phase)}
     label]))

(v/defview phase-stack
  "The same child UNDER a real boundary — the whole declaration a consumer
  writes, rendered structurally in one call. The boundary's own marker and
  the child's own read have to agree, and this is where they meet."
  [_]
  [:div.stack
   (v/presence {:timeout-ms 300}
     [phase-card {:key "a" :label "saved"}])])

(def by-name
  "Fixture view-name keyword -> the declared view. A fixture is EDN, so it
  names a view rather than carrying one."
  {:toasts     toasts
   :rooted     rooted
   :phase-card phase-card
   :phase-stack phase-stack})
