(ns panel-gallery.fixtures-causality
  "Pure fixture builders for the Causality popover gallery (rf2-pt1e1).

  The popover payload (`:rf.causa/causality-popover-payload`) derives
  from three reactive inputs:

    - `:rf.causa/cascades`       — projected cascade list
    - `:rf.causa/trace-buffer`   — raw trace events (for
                                   `panel-h/enrich-cascades`)
    - `:rf.causa/focus`          — the spine-focused dispatch-id

  The popover walks ancestor / descendant chains rooted at the focused
  cascade via `:parent-dispatch-id` tags carried on each cascade's
  `:event/dispatched` trace event. So fixtures here build trace
  buffers whose dispatched events carry a `:parent-dispatch-id` tag
  pointing at the parent cascade's dispatch-id — exactly mirroring the
  production shape `re-frame.dispatch/dispatch` emits.

  Cascade shape (per `re-frame.trace.projection/group-cascades`):

      {:id <int>
       :op-type :event
       :operation :event/dispatched
       :tags {:dispatch-id          <int>
              :parent-dispatch-id   <int or nil>      ; cascade lineage
              :event                <event-vec>
              :origin               :app | :causa | ...}}")

;; ---- per-cascade builders -----------------------------------------------

(defn- cascade-evs
  "Synthesize the canonical 4-row spine for a single cascade with a
  `:parent-dispatch-id` tag carried on the `:event/dispatched` row so
  the popover's graph-helper can reconstruct the lineage. Caller
  supplies the dispatch-id, event vector, base trace-id, and (optional)
  parent dispatch-id."
  [dispatch-id event-vec id-base parent-id]
  (let [base-tag {:dispatch-id dispatch-id}
        disp-tag (cond-> (assoc base-tag :event event-vec :origin :app)
                   parent-id (assoc :parent-dispatch-id parent-id))]
    [{:id (+ id-base 1) :op-type :event :operation :event/dispatched
      :tags disp-tag}
     {:id (+ id-base 2) :op-type :event :operation :event
      :tags (assoc base-tag :phase :run-start)}
     {:id (+ id-base 3) :op-type :event :operation :event
      :tags (assoc base-tag :phase :run-end :duration-ms 4)}
     {:id (+ id-base 4) :op-type :event :operation :event/do-fx
      :tags base-tag}
     {:id (+ id-base 5) :op-type :fx :operation :rf.fx/handled
      :tags (assoc base-tag :fx-id :db)}]))

;; ---- buffer builders ----------------------------------------------------

(defn five-node-buffer
  "Five-cascade chain: A → B → C → D (under A) plus E (sibling of B,
  child of A). Focused-id = 200 (cascade C), so the popover renders
  one ancestor (B → A) and one descendant (D), with E surfaced only
  via its sibling relationship to B if visited.

  Returns the trace-buffer vector ready to pass verbatim to
  `:rf.causa/sync-trace-buffer`."
  []
  (let [;; A (root: 100) — no parent.
        a  (cascade-evs 100 [:auth/login {:user :ada}]            100  nil)
        ;; B (199) — child of A. C (200) — child of B. D (300) — child of C.
        b  (cascade-evs 199 [:auth/load-profile :ada]            199  100)
        c  (cascade-evs 200 [:profile/fetch-permissions :ada]    250  199)
        d  (cascade-evs 300 [:permissions/cache-load :ada]       350  200)
        ;; E (400) — sibling of B (also a child of A) so the popover's
        ;; descendant walk from A surfaces both B and E. Focused = C
        ;; though, so E only renders if the helper widens past
        ;; ancestor-chain (it does not by default).
        e  (cascade-evs 400 [:audit/note-login :ada]             450  100)]
    (vec (concat a b c d e))))

(defn twenty-node-buffer
  "Twenty-cascade deep+wide graph rooted at A. Tests popover scrolling
  + truncation (per-level breadth cap 8, ancestor cap 8). Focused-id
  = 200 (mid-tree) so the popover renders ancestors AND descendants
  in both directions.

  Tree shape:

      A (100)
      ├── B  (199) ──── (focused parent)
      │   └── C  (200) ── focused
      │       ├── D1..D8 (300..307)  ; 8 children = breadth boundary
      │       └── D9 (308)           ; 9th child triggers '… N more'
      └── E1..E9 (400..408)          ; siblings of B (also clipped)

  Twenty cascades total — exercises the popover's overflow + scroll
  + per-level breadth-cap disclosure."
  []
  (let [a   (cascade-evs 100 [:auth/login {:user :ada}]              100  nil)
        b   (cascade-evs 199 [:auth/load-profile :ada]               150  100)
        c   (cascade-evs 200 [:profile/fetch-permissions :ada]       200  199)
        ds  (vec (for [i (range 9)]
                   (cascade-evs (+ 300 i)
                                [(keyword "perm" (str "load-" i)) :ada]
                                (+ 300 (* i 10))
                                200)))
        es  (vec (for [i (range 9)]
                   (cascade-evs (+ 400 i)
                                [(keyword "audit" (str "note-" i)) :ada]
                                (+ 500 (* i 10))
                                100)))]
    (vec (concat a b c
                 (mapcat identity ds)
                 (mapcat identity es)))))

(defn focused-dispatch-id-for-five
  "The cascade-id to focus the spine on for `five-node-buffer`. Cascade
  C — sits mid-chain so the popover shows ancestors AND descendants."
  []
  200)

(defn focused-dispatch-id-for-twenty
  "The cascade-id to focus the spine on for `twenty-node-buffer`. C
  again — same mid-chain position so ancestor + 9-child descendants
  both render (the 9th child exercises the '… more' disclosure)."
  []
  200)
