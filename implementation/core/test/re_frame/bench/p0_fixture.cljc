(ns re-frame.bench.p0-fixture
  "EP-0038 P0 — the FIXTURE every arm shares: the witness shapes, the
  element arithmetic, and the re-frame2 SUB GRAPH.

  This namespace is the like-for-like contract. The P0 bar (HD-012) is
  `mount AND bulk <= 1.0x Reagent, LIKE-FOR-LIKE — both sides reading
  re-frame2 subscriptions`, and the only way a reader can check that two
  arms were like-for-like is if the shapes and the subscriptions are one
  declaration that both arms consume rather than two declarations someone
  has to diff. Everything substrate-specific — `defui` + `use-subscribe`
  for UIx, `reg-view` + `@(rf/subscribe …)` for Reagent — lives in the arm
  namespaces; nothing about WHAT is read lives there.

  ## The witnesses

  Deliberately the shapes the predecessor's cross-substrate report already
  measured, so the P0 table slots into that record rather than starting a
  second, incomparable one — but with the sub graph added on BOTH sides,
  which is the thing the predecessor row never had.

    - **W1-list** — a large template: 300 rows under one boundary each,
      multi-class sugar, a style map, a `data-*` passthrough, and one
      subscription read per row boundary. 1,203 elements.
    - **W3-form** — an ordinary 12-field form: label, controlled input,
      error line, one subscription read per field boundary. 51 elements.
    - **U-grid** — 300 independently-subscribed cells; the surface the two
      bulk rows drive. 301 elements.

  ## The sub graph

  Three layer-1 subscriptions, keyed by index, ONE read per boundary — the
  first rung of the HD-002 1/3/7/20 ladder. Every arm reads exactly these,
  so a difference between arms is a difference in how a substrate delivers
  a subscription value to a boundary and cannot be a difference in what
  was subscribed to.

  Layer-1 and index-keyed rather than a single whole-db read, because a
  whole-db read makes every boundary re-render on every write and would
  make the NARROW row unmeasurable by construction — the row that prices
  localisation would price nothing.

  ## The fan-out key space (rf2-5prok)

  `:p0/fan` is a fourth layer-1 subscription that exists so a heap arm can
  set the number of UNIQUE live query keys INDEPENDENTLY of the number of
  boundaries and the number of reads. It answers the same cell values
  `:p0/cell` answers, folded round the seeded range, so a rung holding
  2,400 keys renders exactly the DOM a rung holding 150 does and the
  difference between them is cache cardinality and nothing else. See
  [[fan-key]].

  ## Sub-key identity

  `(query-id, args)` under value equality, args a bare long. A map would
  be equally safe — a freshly allocated but =-equal map is one cache key;
  only value-unstable args thrash the index — but a bare long is
  value-stable by construction, so key churn cannot enter what the arms
  measure.

  Owner: the operator-owned standard bead rf2-2rtt6.1; this arm rf2-2rtt6.4."
  (:require [re-frame.core :as rf]))

;; ---------------------------------------------------------------------------
;; Sizes — one place, so the arms cannot drift
;; ---------------------------------------------------------------------------

(def w1-rows
  "Rows in the large template. 300 boundaries, which is the
  `>= ~100 boundaries on one commit` shape the K2 kill criterion names."
  300)

(def w3-fields "Fields in the ordinary form." 12)

(def cells-n
  "Cells in the update grid. The same 300 as the mount storm, so a bulk
  ratio and a mount ratio describe pages of the same size."
  300)

(defn w1-elements
  "Three for the skeleton — section, heading, list — then four per row:
  the row, its image, its label and its number. ARITHMETIC, so the parity
  gate is against a written expectation rather than against whatever the
  mount happened to produce. An arm that renders an empty page is the
  cheapest arm in any table, and this is the line that catches it."
  [rows]
  (+ 3 (* 4 rows)))

(defn w3-elements
  "Three for the skeleton — form, fieldset, submit — then four per field:
  the wrapper, the label, the input and the error line."
  [fields]
  (+ 3 (* 4 fields)))

(defn u-elements
  "The grid wrapper plus one span per cell."
  [n]
  (+ 1 n))

;; ---------------------------------------------------------------------------
;; The fan-out key space — B, E and Q as three numbers, not three shapes
;; ---------------------------------------------------------------------------

(defonce ^:private fan-q
  ;; Q for the arm currently mounted. A `volatile!` and not an `atom`: it is
  ;; set ONCE per arm, outside every measured window, and nothing derefs it
  ;; reactively — a ratom here would put a watchable in the middle of the
  ;; thing being weighed.
  (volatile! 1))

(defn set-fan-keys!
  "Set Q — the number of UNIQUE live query keys the arm about to mount will
  hold — before mounting it. The heap driver states B, E/B and Q; this is
  the only one of the three the page cannot read off the DOM, which is why
  `p0-heap/mount!` asks the sub-cache to prove it afterwards."
  [q]
  (vreset! fan-q (max 1 (long q)))
  nil)

(defn fan-key
  "The query INDEX that boundary `n` reads at slot `j` of its `r` reads:
  `(n·r + j) mod Q`.

  ONE rule, so the whole witness stamp falls out of three numbers the
  driver chose. B boundaries × r reads is E; the modulus is Q; mean fan-out
  is E/Q. Every key in `0 … Q-1` is used whenever `B·r >= Q`, which every
  rung of the published sweep satisfies, so `Q` is the exact live
  cardinality and not an upper bound on it.

  `n` is the GLOBAL boundary index — root index × boundaries-per-root plus
  the cell's own index — because the roots of one arm share one frame and
  therefore one subscription cache. That sharing is the mechanism the whole
  sweep is about (rf2-2rtt6.16): four roots rendering the same query
  vectors hold one reaction at ref-count 4, not four reactions."
  [n r j]
  (mod (+ (* n r) j) @fan-q))

;; ---------------------------------------------------------------------------
;; The data
;; ---------------------------------------------------------------------------

(defn row-value
  "The value the `:p0/row` subscription answers for row `i`. A plain
  string: every arm renders it as text with no conversion of its own, so
  the canonical-DOM gate compares pages rather than marshalling."
  [i]
  (str "row " i))

(defn field-value [i] (str "value " i))

(defn field-error [i] (if (even? i) "" (str "field " i " is required")))

(defn seed-db
  "The app-db every arm starts from. One map, so the two adapter segments
  cannot begin from different data."
  []
  {:rows   (mapv row-value (range w1-rows))
   :fields (mapv (fn [i] {:value (field-value i) :error (field-error i)})
                 (range w3-fields))
   :cells  (vec (repeat cells-n 0))})

;; ---------------------------------------------------------------------------
;; The registrations
;; ---------------------------------------------------------------------------

(defn register!
  "Register the sub graph and the seed event. Idempotent — a re-register
  overwrites with the identical handler — which matters because the clock
  run installs and destroys an adapter once per segment and re-seeds
  around each one."
  []
  (rf/reg-sub :p0/row   (fn [db [_ i]] (get-in db [:rows i])))
  (rf/reg-sub :p0/field (fn [db [_ i]] (get-in db [:fields i])))
  (rf/reg-sub :p0/cell  (fn [db [_ i]] (get-in db [:cells i])))
  ;; The fan-out arm's key space. The index is folded back into the seeded
  ;; range, so EVERY key — 0 or 2,399 — answers the same `0` the `:p0/cell`
  ;; grid answers and every rung of the sweep renders identical DOM. A rung
  ;; whose text differed with Q would be moving the floor it is differenced
  ;; against while claiming to move only the cache.
  (rf/reg-sub :p0/fan   (fn [db [_ i]] (get-in db [:cells (mod i cells-n)])))
  (rf/reg-event :p0/seed (fn [_ _] {:db (seed-db)}))
  ;; The two BULK writes, as ordinary re-frame events. Both arms drive
  ;; their bulk row through `dispatch-sync` on these, because in re-frame2
  ;; the commit IS the write clock and an arm that reached past it — a
  ;; bare `reagent.core/atom`, a direct app-db replace — would be skipping
  ;; the event pipeline and the signal graph that a real application pays
  ;; for on every write. The floor arm has neither and says so.
  (rf/reg-event :p0/write-all
    (fn [{:keys [db]} [_ v]] {:db (assoc db :cells (vec (repeat cells-n v)))}))
  (rf/reg-event :p0/write-one
    (fn [{:keys [db]} [_ i v]] {:db (update db :cells assoc i v)}))
  nil)
