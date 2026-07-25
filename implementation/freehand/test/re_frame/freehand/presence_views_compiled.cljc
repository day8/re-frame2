(ns re-frame.freehand.presence-views-compiled
  "The [[re-frame.freehand.presence-views]] declarations, PROMOTED.

  Every declaration below is its twin in
  [[re-frame.freehand.presence-views]] with `{:compiled true}` added and
  nothing else changed — same parameter vector, same body. The compiled
  analyzer recognises `(v/presence …)` as a seq form and the JVM emitter
  lowers it to `re-frame.freehand.tree/presence`; the interpreted twin
  reaches the SAME builder through the structural walk. `FH-PRESENCE-001`
  is rendered against BOTH, so the presence node's structural projection is
  proven identical across modes.

  Separate namespaces because a view id is derived from where a declaration
  LIVES, so two declarations of one name cannot share a namespace."
  (:require [re-frame.freehand :as v]))

(v/defview toasts
  "A presence boundary over two literal keyed children — the smallest shape
  that pins the structural projection: the `:rf.ui/presence` marker, the
  terminal `:timeout-ms`, and the retained children rendered `:present`."
  {:compiled true}
  [_]
  [:div.stack
   (v/presence {:timeout-ms 300}
     [:div.toast {:key "a"} "A"]
     [:div.toast {:key "b"} "B"])])

(v/defview rooted
  "A presence boundary as the ROOT of a declared view — proves the marker
  survives view-boundary adoption (a fragment-shaped node carrying the
  presence marker is NOT flattened into the boundary)."
  {:compiled true}
  [_]
  (v/presence {:timeout-ms 250}
    [:li.row {:key 1} "one"]
    [:li.row {:key 2} "two"]))

;; ---------------------------------------------------------------------------
;; FH-PRESENCE-004 — the presence-aware CHILD, which reads its own phase
;; ---------------------------------------------------------------------------

(v/defview phase-card
  "The presence-aware child, PROMOTED. `(v/presence-phase)` is a body form
  the compiled analyzer knows, and the phase it answers is decided by the
  RENDERER rather than by the compiler — so a compiled declaration under a
  structural render reads `:present` exactly as its interpreted twin does."
  {:compiled true}
  [{:keys [label]}]
  (let [phase    (v/presence-phase)
        exiting? (= :unmounting phase)]
    [:div.toast {:class       (when exiting? "toast--exit")
                 :aria-hidden (when exiting? "true")
                 :data-phase  (name phase)}
     label]))

(v/defview phase-stack
  "The same child under a compiled boundary. The crossing is compiled ->
  compiled, so the whole declaration is one mode; the fixture's expected
  view-ids are rewritten onto this namespace and nothing else changes."
  {:compiled true}
  [_]
  [:div.stack
   (v/presence {:timeout-ms 300}
     [phase-card {:key "a" :label "saved"}])])

(def by-name
  "Fixture view-name keyword -> the declared view. Keyed identically to
  `presence-views/by-name`, so one fixture table drives both."
  {:toasts     toasts
   :rooted     rooted
   :phase-card phase-card
   :phase-stack phase-stack})
