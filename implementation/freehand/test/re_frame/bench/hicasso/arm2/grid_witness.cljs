(ns re-frame.bench.hicasso.arm2.grid-witness
  "THE 100-CELL CONTROLLED GRID, on the PATCH runtime — the witness the
  hard gate is judged on (rf2-2rtt6.10; witness `:controlled/grid-100`,
  family `:controlled`, gates `K4` and the per-keystroke budget).

  The witness set names six assertions for this row, and the six are the
  cases where a store-backed controlled input actually breaks:

      :same-turn-echo                  the field shows the model before the event returns
      :mid-string-caret                editing in the middle does not jump to the end
      :selection-preserved             a converge does not collapse a selection
      :ime-composition-commits-nothing the renderer does not touch a composing field
      :unchanged-model-rejection       a refused keystroke disappears from the field
      :async-normalisation             a later correction converges, caret intact

  ## One event, one subscription, a per-cell policy in app-db

  Rejection and normalisation are not special *code paths* in a
  controlled input; they are ordinary model behaviour that the renderer
  has to survive. So the grid has exactly one event and one
  subscription, and each cell carries a policy in app-db:

  | policy | what the model does with the typed value |
  |---|---|
  | `:plain` | takes it |
  | `:digits` | **refuses** it unless it is all digits — the model does not move |
  | `:upper`  | normalises it to upper case |
  | `:group`  | normalises `12345` to `12,345` — the length changes |

  `:digits` is the one that fails an arm outright: the model is unchanged,
  so **nothing re-renders**, so a renderer that only writes what changed
  never writes at all, and the refused character stays on screen forever.
  `:group` is the one that catches a caret restored by absolute offset
  instead of distance-from-the-end.

  ## The shape

  100 boundaries, one controlled `<input>` each, one read each. That is
  also the per-keystroke budget's witness: a keystroke in cell 7 must run
  one body, not one hundred, and [[re-frame.bench.hicasso.arm2.runtime/stats]]
  is where that is counted rather than asserted."
  (:require [clojure.string :as str]
            [re-frame.bench.hicasso.arm2.runtime :as rt]
            [re-frame.core :as rf]))

(def cells
  "The witness size validation.md names."
  100)

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

(defn seed-db
  "`n` empty cells, with the four policies placed on named cells so the
  witness can address them by index rather than by setting up state."
  [n]
  {:cells    (into {} (map (fn [i] [i ""])) (range n))
   :policies {11 :digits 13 :upper 17 :group}})

(rf/reg-sub :grid/cell (fn [db [_ i]] (get-in db [:cells i] "")))

(rf/reg-event :grid/seed
  (fn [_ [_ n]] {:db (seed-db n)}))

(rf/reg-event :grid/edit
  (fn [{:keys [db]} [_ i v]]
    (let [policy (get-in db [:policies i] :plain)
          old    (get-in db [:cells i] "")]
      {:db (assoc-in db [:cells i] (apply-policy policy old v))})))

;; The door an out-of-band correction arrives through — a server
;; normalisation, a debounce, a validation that resolves late. It is a
;; plain event; what makes it the `:async-normalisation` witness is that
;; the witness dispatches it from a timer rather than from the field.
(rf/reg-event :grid/set
  (fn [{:keys [db]} [_ i v]] {:db (assoc-in db [:cells i] v)}))

;; ---------------------------------------------------------------------------
;; The views
;; ---------------------------------------------------------------------------

(def cell-view
  (rt/view ::cell
           (fn [{:keys [i]}]
             (let [v (rt/sub [:grid/cell i])]
               [:div.cell
                [:input.inp {:id       (str "c" i)
                             :type     "text"
                             :value    v
                             :on-input [:grid/edit i :re-frame.hicasso/value]}]]))))

(def grid-view
  (rt/view ::grid
           (fn [{:keys [n]}]
             [:div.grid {:role "group"}
              (for [i (range n)]
                [cell-view {:key i :i i}])])))

;; ---------------------------------------------------------------------------
;; Mounting the witness
;; ---------------------------------------------------------------------------

(def frame-id ::grid)

(defn mount!
  "Create the frame, seed it, and mount the grid into `container`.
  Returns the teardown."
  ([container] (mount! container cells))
  ([container n]
   (rf/make-frame {:id frame-id :initial-events [[:grid/seed n]]})
   (rt/mount-root! {:container container :frame frame-id :element [grid-view {:n n}]})))

(defn cell-input
  "The `<input>` for cell `i` inside `container`."
  [container i]
  (.querySelector container (str "#c" i)))

(defn model-value
  "What app-db says cell `i` holds — the other half of every agreement
  assertion, read from the frame rather than from the renderer."
  [i]
  (get-in (rf/app-db-value frame-id) [:cells i] ""))
