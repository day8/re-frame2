(ns re-frame.bench.hicasso.jsfb-model
  "THE ONE MODEL BOTH ARMS RENDER — krausest/js-framework-benchmark's app
  as re-frame2 state (rf2-rguy1).

  ## Why a shared model namespace exists at all

  This lane's cross-check asks whether an instrument nobody here wrote
  agrees with ours about `hicasso / reagent`. A ratio only answers that
  if the two arms differ in EXACTLY ONE thing — the view substrate. So
  the app-db shape, every event handler, every subscription and the
  pseudo-random data are defined once, here, and both arms require this
  namespace. Neither arm may hold state of its own; if one did, the ratio
  would be a ratio of two applications rather than of two renderers.

  That is the same discipline `p0-reagent-views` states for the M1
  witness, applied to a page whose shape someone else specified.

  ## The data is SEEDED, and that is a deliberate deviation

  Every upstream implementation builds labels with `Math.random()`. Two
  arms doing that render different text, so they lay out differently, and
  a 1,000-row table's text is a real share of its layout cost. The
  benchmark tolerates that because it never compares two implementations
  on one page — it compares medians across separate runs, where the
  distributions coincide.

  This lane DOES compare two arms directly, so it removes the variance
  instead of averaging it away: [[next-random]] is a 32-bit LCG with a
  fixed seed, reset by [[reset-seed!]] whenever a run starts. Both arms
  therefore build BYTE-IDENTICAL text from the identical id sequence, and
  the DOM comparison in `jsfb_ours_run.cjs` proves it rather than this
  docstring.

  The distribution is unchanged — same three word lists, same
  concatenation, same length profile — so the work per row is the work
  the benchmark specifies. What changes is only that the two arms get the
  same draw.

  ## The app-db shape, and why it is not the reference's vector

  The reference implementations keep a vector of `{id, label}` and index
  into it. That is right for a substrate whose list boundary re-renders
  wholesale. It is wrong for re-frame2, where the point is that a row
  boundary reads its OWN row and re-renders only when that row moves — an
  index-keyed vector would make every row's query recompute after a swap
  or a remove, which is a strawman denominator of exactly the kind
  `p0-reagent-views` warns about.

  So: `:order` is the keyed id sequence the table renders, `:by-id` holds
  the rows, `:selected` holds at most one id.

      {:order    [1 2 3 …]
       :by-id    {1 {:id 1 :label \"pretty red table\"} …}
       :selected 3}

  Both arms pay this identically, so it cancels in the ratio; it is
  described because a reader comparing this page to an upstream entry
  will see a different data structure and should know it was chosen
  rather than inherited.

  ## The subscription graph — three queries, two reads per row

  | query | who reads it | how many live |
  |---|---|---|
  | `[:jsfb/order]` | the table boundary, once | 1 |
  | `[:jsfb/row id]` | each row boundary | one per row |
  | `[:jsfb/selected? id]` | each row boundary | one per row |

  At 1,000 rows that is **2,001 live queries and 2,001 reads per mount**,
  fan-out 1 on the row queries. Stated because cache cardinality is part
  of a witness by this lane's ruling — two arms at different
  cardinalities are not the same experiment, and both arms here are at
  this one.

  Splitting selection into its own per-row query is what makes `select
  row` and `remove row` localise: a selection change recomputes 1,000
  cheap queries and re-renders the two boundaries whose value actually
  moved. Folding it into `[:jsfb/row id]` would re-render every row on
  every click and would be measuring a worse application, not a slower
  substrate.

  ## Event semantics are the reference's, operation for operation

  `swap-rows` exchanges positions 1 and 998, `update-some` appends
  ` !!!` to every 10th label, `add` appends 1,000 — these are
  `frameworks/keyed/reagent/src/demo/utils.cljs`'s definitions, verified
  against that file rather than recalled, because the driver asserts on
  the resulting text at specific row positions and an off-by-one makes
  every number here incomparable.

  Owner: rf2-rguy1."
  (:require [re-frame.core :as rf]))

;; ---------------------------------------------------------------------------
;; The seeded generator
;; ---------------------------------------------------------------------------

(def adjectives
  ["pretty" "large" "big" "small" "tall" "short" "long" "handsome" "plain"
   "quaint" "clean" "elegant" "easy" "angry" "crazy" "helpful" "mushy" "odd"
   "unsightly" "adorable" "important" "inexpensive" "cheap" "expensive" "fancy"])

(def colours
  ["red" "yellow" "blue" "green" "pink" "brown" "purple" "brown" "white"
   "black" "orange"])

(def nouns
  ["table" "chair" "house" "bbq" "desk" "car" "pony" "cookie" "sandwich"
   "burger" "pizza" "mouse" "keyboard"])

(def ^:private seed-0
  "A fixed, arbitrary, non-zero seed. Non-zero because the LCG below has
  0 as a fixed point."
  123456789)

(defonce ^:private !seed (atom seed-0))

(defonce ^:private !next-id (atom 1))

(defn reset-seed!
  "Return the generator and the id counter to their starting state.

  Called by the arm's `-main` before the first render, so a page reloaded
  by the driver between iterations builds the same table it built last
  time. The driver reloads per iteration on some benchmarks, so without
  this the two arms would drift apart over a run in exactly the way
  seeding was meant to prevent."
  []
  (reset! !seed seed-0)
  (reset! !next-id 1)
  nil)

(defn- next-random
  "A 32-bit linear congruential step — Numerical Recipes' constants,
  masked to 31 bits so the result is a non-negative CLJS integer and the
  arithmetic stays in the fixnum range on every platform.

  Not cryptographic and not trying to be: it has to be identical across
  two bundles compiled from this one file, and that is the whole
  requirement."
  [n]
  (let [s (bit-and (unchecked-add (unchecked-multiply @!seed 1103515245) 12345)
                   0x7FFFFFFF)]
    (reset! !seed s)
    (mod s n)))

(defn- label
  "`\"pretty red table\"` — the reference's three-word concatenation, in
  the reference's order."
  []
  (str (nth adjectives (next-random (count adjectives))) " "
       (nth colours (next-random (count colours))) " "
       (nth nouns (next-random (count nouns)))))

(defn build-rows
  "`n` fresh rows with consecutive ids. Returns `[order by-id]` so the
  caller merges once rather than reducing twice over 10,000 rows."
  [n]
  (loop [i 0, order (transient []), by-id (transient {})]
    (if (= i n)
      [(persistent! order) (persistent! by-id)]
      (let [id (swap! !next-id inc)
            ;; `swap!` returns the INCREMENTED value, so the first id is
            ;; 2 unless the counter starts at 0. The driver asserts the
            ;; 1000th row's first cell contains "1000" after the first
            ;; `#run`, so ids must be 1..1000 and this subtracts back.
            id (dec id)]
        (recur (inc i)
               (conj! order id)
               (assoc! by-id id {:id id :label (label)}))))))

;; ---------------------------------------------------------------------------
;; The subscriptions
;; ---------------------------------------------------------------------------

(defn register!
  "Register the three queries and the eight events.

  A function as well as a load-time call, for `p0-reagent-views/register!`'s
  reason: an arm may install and destroy an adapter more than once in a
  process, and a re-register overwrites with the identical handler."
  []
  (rf/reg-sub :jsfb/order (fn [db _] (:order db)))
  (rf/reg-sub :jsfb/row (fn [db [_ id]] (get-in db [:by-id id])))
  (rf/reg-sub :jsfb/selected? (fn [db [_ id]] (= id (:selected db))))

  (rf/reg-event :jsfb/run
    (fn [{:keys [db]} _]
      (let [[order by-id] (build-rows 1000)]
        {:db (assoc db :order order :by-id by-id :selected nil)})))

  (rf/reg-event :jsfb/runlots
    (fn [{:keys [db]} _]
      (let [[order by-id] (build-rows 10000)]
        {:db (assoc db :order order :by-id by-id :selected nil)})))

  (rf/reg-event :jsfb/add
    (fn [{:keys [db]} _]
      (let [[order by-id] (build-rows 1000)]
        {:db (-> db
                 (update :order into order)
                 (update :by-id merge by-id))})))

  ;; Every 10th row by POSITION, which is what the reference's
  ;; `(range 0 (count data) 10)` means, and what the driver's assertion
  ;; on `tr:nth-of-type(991)` requires.
  (rf/reg-event :jsfb/update10th
    (fn [{:keys [db]} _]
      (let [order (:order db)]
        {:db (assoc db :by-id
                    (reduce (fn [by-id i]
                              (let [id (nth order i)]
                                (update-in by-id [id :label] str " !!!")))
                            (:by-id db)
                            (range 0 (count order) 10)))})))

  (rf/reg-event :jsfb/clear
    (fn [{:keys [db]} _]
      {:db (assoc db :order [] :by-id {} :selected nil)}))

  ;; Positions 1 and 998, the reference's indices, and only when the
  ;; table is long enough to have them.
  (rf/reg-event :jsfb/swaprows
    (fn [{:keys [db]} _]
      (let [order (:order db)]
        (if (> (count order) 998)
          {:db (assoc db :order (-> order
                                    (assoc 1 (nth order 998))
                                    (assoc 998 (nth order 1))))}
          {:db db}))))

  (rf/reg-event :jsfb/select
    (fn [{:keys [db]} [_ id]] {:db (assoc db :selected id)}))

  (rf/reg-event :jsfb/remove
    (fn [{:keys [db]} [_ id]]
      {:db (-> db
               (update :order (fn [order] (into [] (remove #(= id %)) order)))
               (update :by-id dissoc id))}))
  nil)

(register!)

(def frame-id
  "One frame, created once at boot. A frame is application state, not
  mount state — `p0-reagent-views/subs-frame` says why at length."
  :jsfb/app)

(defn make-frame!
  "Create the frame with an empty table. The driver always clicks `#run`
  before it measures anything, so an empty initial table is the
  reference's behaviour and not a shortcut.

  Seeded through `:jsfb/clear` rather than an `:initial-db`, so the
  starting app-db is produced by a handler both arms share — there is no
  second definition of `empty` that one arm could drift from."
  []
  (rf/make-frame {:id frame-id :initial-events [[:jsfb/clear]]})
  frame-id)
