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
