(ns re-frame.bench.hicasso.topo.model
  "THE TOPOLOGY TOURNAMENT'S STATE LAYER — one table, four read
  topologies, four operations (rf2-hic-036).

  The tournament asks which read topology fits which workload. That is
  only answerable if the four arms are four *readings of one
  application*: four hand-written models would make every arm-to-arm
  difference unattributable, which is the argument
  [[re-frame.bench.hicasso.shapes.model]] already makes for the tier-1
  shape roster and `jsfb-model` makes for the js-framework-benchmark
  pair. So the rows, the markup, the seed and the four events live here,
  once, and [[re-frame.bench.hicasso.topo.arms]] cuts them four ways.

  ## The row's TWO reads, and why exactly two

  [The counterfactual worksheet](../../../../../../../docs/design/hicasso/product/counterfactual-topology-prediction.md)
  froze its predictions on `R = 2`, and closed by naming the way this
  page could invalidate them: *\"if the tournament's table reads more
  than 2 keys per row, every `B·R` figure on this page is low.\"* It
  reads exactly two, and they are the worksheet's own pair — one
  per-instance DATA read and one per-instance UI read:

      [:topo/row i]     the row's data     {:id :label :n}
      [:topo/draft i]   the row's own edit buffer, a string

  Both are unconditional, so the set has ONE form and cannot oscillate —
  which matters, because set stability is a **gate** on the coarse arm
  rather than a tiebreak (`hot-path-architecture.md#read-topology-guidance`:
  *\"Large oscillating read sets are suspect\"*). The worksheet's §2 found
  the row family the most stable thing on its page; this family is stable
  by construction, and `census_dom_cljs_test` asserts it rather than
  claiming it.

  ## The four operations, and why they are four rather than two

  Each moves a DIFFERENT cell, which is the only reason `sparse` and
  `edit` are two rows instead of one:

  | op | event | cell moved | rows whose reads move |
  |---|---|---|---|
  | `sparse` | `[:topo/bump i]` | `[:topo/row i]` | 1 |
  | `bulk` | `[:topo/bump-all]` | every `[:topo/row i]` | `B` |
  | `reorder` | `[:topo/rotate]` | `[:topo/order]` **only** | 0 |
  | `edit` | `[:topo/edit i s]` | `[:topo/draft i]` | 1 |

  **`reorder` is the interesting one and the easiest to get wrong.** It
  permutes `:order` and touches no row's data, so no row's reads move at
  all: every re-render it causes is React reconciling a keyed list whose
  children are unchanged values in a new sequence. An implementation that
  rebuilt its children would produce the same DOM and a completely
  different body count, which is exactly the distinction
  `shapes/bulk_dom_cljs_test` was written to make.

  ## `edit` is the COMMIT a keystroke causes, not the keystroke

  Stated here so no reader takes the row for more than it is. `edit`
  writes the row's draft cell — the write a controlled field's change
  intent performs — and the tournament measures the commit that follows.
  The pipeline in front of it (keystroke → state write → recomputation →
  boundary run → commit → visible echo) is **`rf2-hic-045`'s published
  per-keystroke mechanics**, which owns exactly that ladder for the
  editor and the grid. Duplicating it here would mint a second,
  unreconciled witness for a quantity another bead already owns.

  ## Determinism

  Every string is a pure function of an index — no `rand`, no clock — so
  two seedings of the same `B` build byte-identical DOM and a witness can
  compare a page against itself across a commit."
  (:require [re-frame.core :as rf]))

;; ---------------------------------------------------------------------------
;; The seed
;; ---------------------------------------------------------------------------

(def row-counts
  "The tournament's three sizes, pre-registered."
  [100 300 1000])

(def chunk-size
  "`k` for the chunked arm — a STATED CONSTANT, not a derived optimum.

  The counterfactual worksheet refuses to pin a chunk width (its N1): the
  naive `k* = √B` prices one membership slot as one row of markup, which
  is certainly false, and the committed evidence carries no value for the
  true ratio. So this is a setting of the arm, chosen before any run and
  held fixed at every row count and every operation. It is not a result,
  and no row below may be read as evidence about it."
  25)

(def window-size
  "How many rows the windowed arm keeps in the DOM. Fixed, like
  [[chunk-size]], and for the same reason."
  20)

(defn row
  "One row, from its index. `:n` is the field the data writes move."
  [i]
  {:id    i
   :label (str "Row " i)
   :n     (mod (* 7 i) 23)})

(defn seed-db
  "`b` rows, in index order, with empty drafts.

  `w` is the windowed arm's window, seeded rather than read from
  [[window-size]] so that a control can scale the ONE quantity that arm's
  cost is proportional to. It defaults to [[window-size]], so every
  caller that does not care reads exactly the numbers it read before."
  ([b] (seed-db b window-size))
  ([b w]
   {:rows   (into {} (map (fn [i] [i (row i)])) (range b))
    :order  (vec (range b))
    :draft  {}
    :b      b
    :w      w}))

;; ---------------------------------------------------------------------------
;; Subscriptions — the four topologies' reads
;; ---------------------------------------------------------------------------

;; The list boundary's read, shared by the fine, chunked and windowed arms.
(rf/reg-sub :topo/order (fn [db _] (:order db)))

;; The row family's two reads. Both parametric, both per instance.
(rf/reg-sub :topo/row (fn [db [_ i]] (get-in db [:rows i])))
(rf/reg-sub :topo/draft (fn [db [_ i]] (get-in db [:draft i] "")))

(defn- merged
  "One row of the coarse arm's view-model: the two per-instance reads,
  already joined. This is the whole of what the coarse arm buys — the
  join moves from `B` boundary read-sets into ONE subscription, and the
  worksheet's §1 is the statement that the family then contributes zero
  memberships instead of `B·R`."
  [db i]
  (assoc (get-in db [:rows i]) :draft (get-in db [:draft i] "")))

;; **THE COARSE ARM'S ONE READ.** It recomputes whenever anything the rows
;; hold moves, which is the price; it holds one membership instead of
;; `B·R`, which is the purchase.
(rf/reg-sub :topo/view-model
  (fn [db _] (mapv (partial merged db) (:order db))))

;; **THE CHUNKED ARM.** `⌈B/k⌉` boundaries, each reading its own slice.
(rf/reg-sub :topo/chunk-count
  (fn [db _] (long (Math/ceil (/ (count (:order db)) chunk-size)))))

(rf/reg-sub :topo/chunk
  (fn [db [_ c]]
    (mapv (partial merged db)
          (subvec (:order db)
                  (min (count (:order db)) (* c chunk-size))
                  (min (count (:order db)) (* (inc c) chunk-size))))))

;; **THE WINDOWED ARM.** The visible slice of `:order` — the ids that
;; exist in the DOM at all. Every other row is absent, which is a
;; different lever from how many read edges exist (the worksheet's N2).
(rf/reg-sub :topo/visible
  (fn [db _]
    (subvec (:order db) 0 (min (count (:order db)) (:w db window-size)))))

;; ---------------------------------------------------------------------------
;; Events — the four operations, plus the seed and the control's write
;; ---------------------------------------------------------------------------

(rf/reg-event :topo/seed (fn [_ [_ b w]] {:db (seed-db b (or w window-size))}))

(rf/reg-event :topo/bump
  ;; **SPARSE.** One row's data moves. Nothing else in app-db is touched,
  ;; including `:order` — which is what lets a witness assert the list
  ;; boundary did NOT re-run.
  (fn [{:keys [db]} [_ i]] {:db (update-in db [:rows i :n] inc)}))

(rf/reg-event :topo/bump-all
  ;; **BULK.** Every row's data moves, in one commit. `:order` does not,
  ;; so a fine arm's `B` re-renders are `B` rows answering for themselves
  ;; and not a parent rebuilding its children.
  (fn [{:keys [db]} _]
    {:db (update db :rows (fn [rs] (reduce-kv (fn [m i r] (assoc m i (update r :n inc))) {} rs)))}))

(rf/reg-event :topo/rotate
  ;; **REORDER.** `:order` rotates by one. Same identities, same keys,
  ;; same values — only the sequence changes.
  (fn [{:keys [db]} _]
    {:db (update db :order (fn [o] (if (empty? o) o (conj (subvec o 1) (nth o 0)))))}))

(rf/reg-event :topo/edit
  ;; **EDIT.** The write a controlled field's change intent performs.
  (fn [{:keys [db]} [_ i s]] {:db (assoc-in db [:draft i] s)}))

(rf/reg-event :topo/bump-stride
  ;; **THE POSITIVE CONTROL'S WRITE** (rf2-7iqb5's prescribed repair).
  ;;
  ;; Every `stride`-th row moves, in one commit, on a table whose SIZE
  ;; does not change. Element count, layout and paint are therefore
  ;; constant between `stride 10` and `stride 5`, and the only thing that
  ;; doubles is the changed set — so the prediction on the commit-side
  ;; work is 2.00x.
  ;;
  ;; This is the control this lane did not have. Every earlier positive
  ;; control for an update row scaled the PAGE, and rf2-7iqb5 records why
  ;; that cannot certify one: the work does not scale with the page, so
  ;; even a perfect instrument reads below the predicted factor.
  (fn [{:keys [db]} [_ stride]]
    {:db (update db :rows
                 (fn [rs]
                   (reduce-kv (fn [m i r] (assoc m i (cond-> r (zero? (mod i stride)) (update :n inc))))
                              {} rs)))}))

(rf/reg-event :topo/bump-indexed
  ;; **THE RENDERED-SCALE CONTROL'S WRITE** (rf2-m6i0).
  ;;
  ;; Every `stride`-th row below `limit` moves, written by INDEX rather
  ;; than by rebuilding the table. That is the whole difference from
  ;; [[:topo/bump-stride]] above, and it is the difference the tournament's
  ;; refused control turned on: `bump-stride` `reduce-kv`s all `B` rows
  ;; whichever stride runs, so its handler costs the same at both halves of
  ;; the manipulation and contributes a shared constant that compresses the
  ;; measured ratio toward 1. This handler costs `limit/stride` — it is
  ;; proportional to the CHANGED SET, which is the first of the two
  ;; conditions rf2-m6i0's diagnosis named.
  ;;
  ;; `limit` is the arm's own RENDERED row count, never `B`: on the
  ;; windowed arm a write striding over all `B` rows would again be a
  ;; constant term, because that arm renders `w` rows however large the
  ;; table is.
  (fn [{:keys [db]} [_ limit stride]]
    {:db (reduce (fn [d i] (update-in d [:rows i :n] inc))
                 db
                 (range 0 limit stride))}))

(rf/reg-event :topo/noop-write
  ;; **THE NEGATIVE CONTROL.** Moves a key no arm reads, so a witness that
  ;; counts zero bodies here is counting rather than failing to look.
  (fn [{:keys [db]} _] {:db (update db :unread (fnil inc 0))}))

;; ---------------------------------------------------------------------------
;; Frame lifecycle
;; ---------------------------------------------------------------------------

(defn make-frame!
  "Create (or idempotently replace) `frame-id`, seeded with `b` rows and
  a window of `w` (default [[window-size]])."
  ([frame-id b] (make-frame! frame-id b window-size))
  ([frame-id b w]
   (rf/make-frame {:id frame-id :initial-events [[:topo/seed b w]]})
   frame-id))

(defn reseed!
  "Return the frame to its seeded state. `make-frame!` is idempotent and
  will NOT replay its initial events for an id that already exists, so
  both halves are needed."
  ([frame-id b] (reseed! frame-id b window-size))
  ([frame-id b w]
   (rf/with-frame frame-id (rf/dispatch-sync [:topo/seed b w]))
   nil))

;; ---------------------------------------------------------------------------
;; The arithmetic a witness predicts rather than accepts
;; ---------------------------------------------------------------------------

(def elements-per-row
  "`li` + `span.label` + `em.n` + `span.draft` + `button` = 5.

  Written down so a page's element total is arithmetic a witness can
  PREDICT — `1 + 5B` for the un-windowed arms — rather than a number it
  has to accept from the DOM it is supposed to be checking. A markup edit
  that forgets to update it fails a row instead of silently
  re-baselining the tournament."
  5)

(defn elements-for
  "Predicted element count: the `ul` plus five per RENDERED row."
  [rendered-rows]
  (+ 1 (* elements-per-row rendered-rows)))

(defn rendered-rows
  "How many rows an arm puts in the DOM at `b`. Three arms render every
  row; the windowed arm renders at most `w` — which is the arm's entire
  purpose and the reason it is not DOM-comparable with the other three.

  **This function is also the control's scale parameter** (rf2-m6i0). A
  positive control has to double the quantity the arm's commit cost is
  proportional to, and this is that quantity: `B` for three arms, `w` for
  the fourth. Doubling `B` on the windowed arm moves nothing it renders,
  which is why one page-size control cannot serve all four."
  ([arm b] (rendered-rows arm b window-size))
  ([arm b w] (if (= :virtual arm) (min b w) b)))

(defn memberships
  "The read edges the arm's row family contributes at `b` — the
  worksheet's `B·R` column, computed from the same arithmetic so the two
  can be compared rather than asserted equal."
  ([arm b] (memberships arm b window-size))
  ([arm b w]
   (case arm
     :fine    (* 2 b)
     :coarse  0
     :chunked (long (Math/ceil (/ b chunk-size)))
     :virtual (* 2 (min b w)))))
