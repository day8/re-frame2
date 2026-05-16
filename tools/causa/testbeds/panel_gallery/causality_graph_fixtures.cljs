(ns panel-gallery.causality-graph-fixtures
  "Pure fixture builders for the Causa causality-graph panel gallery
  (rf2-8r20i, Phase 2).

  The causality-graph panel reads its rows from
  `:rf.causa/causality-graph-data`, a composite over:

    - `:rf.causa/cascades`             — `group-cascades` over the
                                          trace buffer
    - `:rf.causa/trace-buffer`         — raw events for enrichment
                                          (`:origin` / `:parent-
                                          dispatch-id` are lifted off
                                          the raw `:event/dispatched`)
    - `:rf.causa/selected-dispatch-id` — drives node highlight
    - `:rf.causa/selected-epoch-id`    — drives cascade-family filter
    - `:rf.causa/epoch-history`        — for the epoch-filter walk

  Each variant seeds via `:rf.causa/sync-trace-buffer` — the raw
  buffer drives `:rf.causa/cascades` + the enrichment walk on the
  reactive path. Selection variants additionally dispatch
  `:rf.causa/select-dispatch-id`. ZERO source-side changes to the
  panel; the gallery exercises it as-is.

  ## Cascade composition

  Each cascade requires at minimum an `:event/dispatched` row carrying
  `:tags :dispatch-id`. Parent edges come from `:tags :parent-dispatch-
  id` on the dispatched-event row; `:origin` (`:app` / `:pair` /
  `:story` / `:test` / `:causa`) drives the node-fill colour.

  The minimal-cascade builder here pairs the `:event/dispatched` row
  with a `:handler`-phase row + an `:rf.fx/handled` row + a
  `:view/render` row so each cascade carries non-zero
  `:effect-count` / `:render-count` for the node sub-label. Errors
  / warnings tip the border colour via the cascade's `:other` bucket.")

;; ---- per-event builders -------------------------------------------------

(defn dispatched-ev
  ([id dispatch-id event-vec]
   (dispatched-ev id dispatch-id event-vec nil :app))
  ([id dispatch-id event-vec parent-dispatch-id]
   (dispatched-ev id dispatch-id event-vec parent-dispatch-id :app))
  ([id dispatch-id event-vec parent-dispatch-id origin]
   {:id        id
    :op-type   :event
    :operation :event/dispatched
    :tags      (cond-> {:dispatch-id dispatch-id
                        :event       event-vec
                        :origin      origin}
                 parent-dispatch-id (assoc :parent-dispatch-id parent-dispatch-id))}))

(defn handler-ev
  [id dispatch-id]
  {:id id :op-type :event :operation :event
   :tags {:dispatch-id dispatch-id :phase :run-end :duration-ms 4}})

(defn fx-ev
  [id dispatch-id fx-id]
  {:id id :op-type :fx :operation :rf.fx/handled
   :tags {:dispatch-id dispatch-id :fx-id fx-id}})

(defn render-ev
  [id dispatch-id render-key]
  {:id id :op-type :view :operation :view/render
   :tags {:dispatch-id dispatch-id :render-key render-key}})

(defn error-ev
  [id dispatch-id]
  {:id id :op-type :error :operation :rf.error/handler-threw
   :tags {:dispatch-id dispatch-id :reason "handler threw"}})

(defn warning-ev
  [id dispatch-id]
  {:id id :op-type :warning :operation :rf.warning/handler-replaced
   :tags {:dispatch-id dispatch-id :reason "replaced under reload"}})

(defn cascade-events
  "Build a minimal four-row cascade — dispatched + handler-run-end +
  one fx + one render. Optionally a parent / origin override."
  ([id-base dispatch-id event-vec]
   (cascade-events id-base dispatch-id event-vec nil :app))
  ([id-base dispatch-id event-vec parent]
   (cascade-events id-base dispatch-id event-vec parent :app))
  ([id-base dispatch-id event-vec parent origin]
   [(dispatched-ev (+ id-base 1) dispatch-id event-vec parent origin)
    (handler-ev    (+ id-base 2) dispatch-id)
    (fx-ev         (+ id-base 3) dispatch-id :db)
    (render-ev     (+ id-base 4) dispatch-id [:app/root nil])]))

;; ---- buffer builders ----------------------------------------------------

(defn empty-buffer
  "No cascades — panel renders the empty-state copy ('No cascades
  yet')."
  []
  [])

(defn one-node-buffer
  "Single root cascade. Graph renders one node, zero arrows."
  []
  (cascade-events 0 100 [:counter/inc]))

(defn five-node-buffer
  "Five cascades — one root + four children in a small tree.

      100 ── 101
           ├── 102
           └── 103 ── 104

  Demonstrates the panel's layered layout + arrow rendering."
  []
  (vec
    (concat
      (cascade-events  0   100 [:auth/login :ada])
      (cascade-events 10   101 [:auth/load-profile :ada]   100)
      (cascade-events 20   102 [:auth/load-permissions :ada] 100)
      (cascade-events 30   103 [:profile/decorate :ada]     100)
      (cascade-events 40   104 [:audit/note-login :ada]    103))))

(defn fifty-node-buffer
  "Fifty cascades — a fan-out tree two levels deep. Exercises the
  panel's layout under realistic dashboard load."
  []
  (vec
    (concat
      (cascade-events 0 100 [:dashboard/refresh-all])
      ;; Five sibling children at level 1.
      (apply concat
        (for [i (range 5)]
          (cascade-events (+ 100 (* i 10))
                          (+ 200 i)
                          [:dashboard/widget-refresh i] 100)))
      ;; Each level-1 child spawns ~9 grandchildren so total ≈ 50.
      (apply concat
        (for [i (range 5)
              j (range 9)]
          (cascade-events (+ 1000 (* (+ (* i 9) j) 10))
                          (+ 300 (+ (* i 10) j))
                          [:widget/fetch-row i j]
                          (+ 200 i)))))))

(defn cyclic-buffer
  "Two cascades that look cyclic by event-vector but ARE NOT — true
  cycles can't exist in a monotonic dispatch-id stream (per spec
  §What this doesn't do). This variant exercises 'two cascades with
  identical event-vectors as distinct nodes' — the dispatch-id is
  the node identity. Roots both."
  []
  (vec
    (concat
      (cascade-events  0 100 [:counter/cycle])
      (cascade-events 10 101 [:counter/cycle]))))

(defn collapsed-buffer
  "A single root cascade with three children — the panel's resting
  state when nothing is selected. The label 'collapsed' refers to
  the unfiltered, unselected, panel-wide view (no cascade-family
  filter active)."
  []
  (vec
    (concat
      (cascade-events  0 100 [:cart/transition])
      (cascade-events 10 101 [:cart/recompute-total] 100)
      (cascade-events 20 102 [:cart/persist] 100)
      (cascade-events 30 103 [:cart/track-event] 100))))

(defn expanded-buffer
  "A two-level deep tree with the root selected — the panel's
  highlighted-node rendering surfaces the root in the selected-
  stroke colour. Three children, each with two grandchildren."
  []
  (vec
    (concat
      (cascade-events   0 100 [:checkout/submit])
      (cascade-events  10 101 [:checkout/validate] 100)
      (cascade-events  20 102 [:checkout/charge]   100)
      (cascade-events  30 103 [:checkout/email]    100)
      (cascade-events  40 104 [:validate/inventory] 101)
      (cascade-events  50 105 [:validate/promo]     101)
      (cascade-events  60 106 [:charge/process]     102)
      (cascade-events  70 107 [:charge/receipt]     102)
      (cascade-events  80 108 [:email/render]       103)
      (cascade-events  90 109 [:email/send]         103))))

(defn layout-stable-buffer
  "A fan-out where the layout's deterministic BFS encounter order is
  load-bearing — sibling cascades are added out-of-order but the
  layout assigns stable columns. Verifies the panel doesn't reorder
  on each render."
  []
  (vec
    (concat
      (cascade-events  0 100 [:root/dispatch])
      (cascade-events 10 105 [:child/c] 100)
      (cascade-events 20 102 [:child/a] 100)
      (cascade-events 30 104 [:child/d] 100)
      (cascade-events 40 103 [:child/b] 100))))

;; ---- panel-specific axes -----------------------------------------------

(defn cross-origin-buffer
  "Panel-specific axis A: the node-fill colour ladders by `:origin`.
  Five cascades each with a distinct origin (:app / :pair / :story /
  :test / :causa) surface every fill colour the panel renders."
  []
  (vec
    (concat
      (cascade-events  0 100 [:app/dispatch]   nil :app)
      (cascade-events 10 101 [:pair/inspect]   nil :pair)
      (cascade-events 20 102 [:story/replay]   nil :story)
      (cascade-events 30 103 [:test/scenario]  nil :test)
      (cascade-events 40 104 [:causa/debug]    nil :causa))))

(defn error-and-warning-buffer
  "Panel-specific axis B: the node border tints red for cascades
  carrying an `:op-type :error` row + amber for `:warning`. Three
  cascades — one error, one warning, one clean — pin all three
  border treatments side-by-side."
  []
  (vec
    (concat
      (cascade-events  0 100 [:cart/add :bad])
      [(error-ev   5 100)]
      (cascade-events 10 101 [:auth/refresh])
      [(warning-ev 15 101)]
      (cascade-events 20 102 [:counter/inc]))))

(defn orphan-children-buffer
  "Panel-specific axis C: orphan child cascades (whose parent is
  absent from the buffer) render as ROOTS per spec §What this
  doesn't do — 'no retroactive correlation'. Three orphans + one
  true root."
  []
  (vec
    (concat
      ;; True root.
      (cascade-events  0 100 [:session/start])
      ;; Three orphans — parent-dispatch-ids 999/998/997 don't exist
      ;; in the buffer.
      (cascade-events 10 101 [:profile/fetch]  999)
      (cascade-events 20 102 [:perms/load]     998)
      (cascade-events 30 103 [:audit/note]     997))))
