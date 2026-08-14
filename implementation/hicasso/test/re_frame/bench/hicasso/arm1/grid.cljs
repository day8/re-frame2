(ns re-frame.bench.hicasso.arm1.grid
  "THE 100-CELL CONTROLLED GRID, WRITTEN ON ARM 1 (K4, HD-019's full door
  — rf2-2rtt6.41).

  validation.md's `:controlled/grid-100` witness, on the arm's own
  runtime: 100 boundaries, one event, one parametric subscription, and a
  per-cell policy in `app-db`. `arm1_controlled_grid_dom_cljs_test` is
  what reads it.

  ## The model is Arm 2's, deliberately unchanged

  The retired PATCH arm's `:controlled/grid-100` model — four policies
  over one `[:agrid/cell i]` subscription — is the clearest statement in
  the repo of what a store-backed controlled input has to do, and it now
  drives two files: `bench/hicasso/controlled_restore_dom_cljs_test`
  measures the **UIx adapter's** two input implementations against it, and
  this one measures **Arm 1's own element path** against the same four
  policies. Two arms, one model, so a difference between them is a
  difference between the renderers rather than between two grids.

  The ids are `:agrid/*` rather than a second copy of `:cgrid/*` because
  image assembly refuses one id registered from two source namespaces
  with different implementations (`:rf.error/image-duplicate-id`) — and it
  is right to: the two files are two different grids on one page, and
  letting load order pick which `:cgrid/edit` a frame runs is exactly the
  silent selection that rule exists to prevent.

  | policy | what the model does with the typed value |
  |---|---|
  | `:plain` | takes it |
  | `:digits` | **refuses** it unless it is all digits — the model does not move |
  | `:upper` | normalises it to upper case |
  | `:group` | normalises `12345` to `12,345` — the length changes |

  ## The commit door exists for the composition gate

  `:agrid/commit` copies a cell's live value into `:committed`, and the
  cell's `:on-key-down` map binds `\"Enter\"` to it. That is the case
  authoring.md pins for the IME fence — **a composing Enter must commit
  nothing** — expressed as a value a witness can read out of `app-db`
  rather than as an absence it has to infer.

  ## What the views are, and what they are not

  One `defview` per cell, reading through the ambient collector, writing
  through the intent-vector surface with `::h/value`. No `h/fn`, no ref,
  no effect and no per-instance state — the claim under test is that the
  ordinary authoring surface meets HD-019's door, so anything the cell
  needed beyond `:value` / `:on-input` would be part of the finding."
  (:require [clojure.string :as str]
            [re-frame.bench.hicasso.arm1.runtime :refer [sub]]
            [re-frame.core :as rf])
  (:require-macros [re-frame.bench.hicasso.arm1.lang :refer [defview]]))

(def cells
  "The witness size validation.md names for the controlled grid."
  100)

(def policies
  "Which cells are not `:plain`. Three indices, so 97 of the 100 are the
  ordinary case and the interesting ones are addressable by number."
  {11 :digits 13 :upper 17 :group})

;; ---------------------------------------------------------------------------
;; The model
;; ---------------------------------------------------------------------------

(defn group-digits
  "`\"12345\"` → `\"12,345\"`. Non-digits are dropped, which is what makes
  this a normalisation rather than a rejection."
  [s]
  (let [ds (str/replace (str s) #"[^0-9]" "")
        n  (count ds)]
    (if (<= n 3)
      ds
      (->> (reverse ds)
           (partition-all 3)
           (map (comp str/join reverse))
           reverse
           (str/join ",")))))

(defn apply-policy
  "What the model does with a typed value. `old` is what it holds now,
  which is the whole of a rejection: the model returns itself."
  [policy old v]
  (case policy
    :digits (if (re-matches #"[0-9]*" v) v old)
    :upper  (str/upper-case v)
    :group  (group-digits v)
    v))

(rf/reg-sub :agrid/cell (fn [db [_ i]] (get-in db [:cells i] "")))

(rf/reg-sub :agrid/committed (fn [db [_ i]] (get-in db [:committed i])))

(rf/reg-event :agrid/seed
  (fn [_ [_ n]]
    {:db {:cells     (into {} (map (fn [i] [i ""])) (range n))
          :committed {}
          :policies  policies}}))

(rf/reg-event :agrid/edit
  (fn [{:keys [db]} [_ i v]]
    (let [policy (get-in db [:policies i] :plain)
          old    (get-in db [:cells i] "")]
      {:db (assoc-in db [:cells i] (apply-policy policy old v))})))

;; The door an out-of-band correction arrives through — a server
;; normalisation, a debounce, a validation that resolves late. Also the
;; setup door, because a witness that typed its own setup would be
;; measuring the keystroke it is about to take.
(rf/reg-event :agrid/set
  (fn [{:keys [db]} [_ i v]] {:db (assoc-in db [:cells i] v)}))

(rf/reg-event :agrid/commit
  (fn [{:keys [db]} [_ i]]
    {:db (assoc-in db [:committed i] (get-in db [:cells i] ""))}))

;; ---------------------------------------------------------------------------
;; The frame
;; ---------------------------------------------------------------------------

(defn make-frame!
  "Create (or idempotently replace) `frame-id`, seeded with `n` empty
  cells."
  [frame-id n]
  (rf/make-frame {:id frame-id :initial-events [[:agrid/seed n]]})
  frame-id)

(defn reseed!
  "Return the frame to its seeded state, so one row never reads a grid a
  previous one already typed into."
  [frame-id n]
  (rf/with-frame frame-id (rf/dispatch-sync [:agrid/seed n]))
  nil)

;; ---------------------------------------------------------------------------
;; The views
;; ---------------------------------------------------------------------------

(def !body-runs
  "How many cell bodies have run. The per-keystroke budget is COUNTED
  here rather than asserted about, and the rejection row's \"nothing
  re-rendered\" is the same counter reading zero."
  (atom 0))

(defview cell
  "One controlled cell. The whole of it: a `:value` read through the
  ambient collector, an `:on-input` intent carrying `::h/value`, and a
  key-map whose `\"Enter\"` branch is what the IME fence has to stop."
  [{:keys [i]}]
  (swap! !body-runs inc)
  [:input.cell {:id          (str "c" i)
                :type        "text"
                :value       (sub [:agrid/cell i])
                :on-input    [:agrid/edit i :re-frame.hicasso/value]
                :on-key-down {"Enter" [:agrid/commit i]}}])

(defview grid
  "`n` cells, keyed."
  [{:keys [n]}]
  [:div.grid {:role "group"}
   (for [i (range (or n cells))]
     [cell {:key i :i i}])])
