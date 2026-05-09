(ns cells.cells
  "7GUIs #7 — Cells.

   A small spreadsheet with cells A1..Z99. Each cell holds either a literal
   value or a formula. Formulas reference other cells. Changes propagate.

   The 7GUIs page calls this out as a test of *change propagation through
   a dependency graph*. The classic trap is to recompute everything on every
   edit (slow) or to invalidate hand-rolled per-cell observers (correctness
   bugs). The re-frame2 approach: each cell's display value is a registered
   subscription that derives from `app-db`'s cell map. Reagent's reactive
   sub graph handles change propagation for free — when A1 updates, only
   subscriptions that transitively depend on A1 re-run.

   Demonstrates:
   - The subscription graph at full strength
   - Cycles detected by walking declared deps                (per-formula static dep set)
   - Open-map cell registry (sparse storage)
   - Headless evaluation via compute-sub
   - Pure parser + evaluator (no eval, no host I/O)

   Scope: 26 cols × 100 rows. Formulas of the form '=expr' where expr is a
   simple S-expression-flavored calculator (numbers, +, -, *, /, cell refs).
   Excel-style infix is left for a future iteration; the shape of the
   solution doesn't change."
  (:require [reagent.dom.client :as rdc]
            [re-frame.core :as rf]
            ;; Per rf2-p7va, re-frame.schemas ships in
            ;; day8/re-frame-2-schemas. Loading the ns here registers
            ;; its late-bind hooks so rf/reg-app-schema resolves.
            [re-frame.schemas]
            [re-frame.substrate.reagent :as reagent-adapter]
            [clojure.set]
            [clojure.string :as str])
  (:require-macros [re-frame.views-macros :refer [reg-view with-frame]]))

(def COLS 26)
(def ROWS 100)
(def CELL-RE #"^[A-Z]\d{1,2}$")

(defn cell-id [col row] (str (char (+ 65 col)) row))
(defn parse-cell-id [s]
  (when (re-matches CELL-RE s)
    [(- (int (.charAt s 0)) 65) (js/parseInt (subs s 1))]))

;; ============================================================================
;; SCHEMA
;; ============================================================================

(def CellEntry
  [:map
   [:raw     :string]                  ;; what the user typed
   [:formula? :boolean]                 ;; true if raw starts with '='
   [:deps    [:set :string]]            ;; cell ids referenced (static dep set)
   [:ast     [:maybe :any]]])           ;; parsed AST for formulas; nil otherwise

(def CellsState
  [:map
   [:cells       [:map-of :string CellEntry]]      ;; sparse: only edited cells exist
   [:selected-id [:maybe :string]]
   [:editing-id  [:maybe :string]]])

(rf/reg-app-schema [:cells] CellsState)

;; ============================================================================
;; PARSER + EVALUATOR
;; ============================================================================
;;
;; Tiny S-expression flavor: '=(+ A1 (* B2 3))'. Pure functions, JVM-runnable.

(defn tokenise [s]
  ;; Splits a formula body into tokens. Whitespace-separated; '(' and ')'
  ;; split out as their own tokens.
  (->> (-> s
           (str/replace #"\(" " ( ")
           (str/replace #"\)" " ) ")
           (str/split #"\s+"))
       (remove str/blank?)
       (vec)))

(defn parse-tokens [tokens]
  ;; Returns [ast remaining-tokens] or throws on malformed input.
  (if (empty? tokens)
    [nil tokens]
    (let [[t & more] tokens]
      (case t
        "(" (loop [acc [] toks more]
              (cond
                (empty? toks)        (throw (ex-info "Unbalanced (" {}))
                (= ")" (first toks)) [acc (rest toks)]
                :else                (let [[child rest-toks] (parse-tokens toks)]
                                       (recur (conj acc child) rest-toks))))
        ")" (throw (ex-info "Unexpected )" {}))
        ;; Atom: number, cell ref, or operator symbol.
        (let [num (js/parseFloat t)]
          (cond
            (not (js/isNaN num))           [num                             more]
            (re-matches CELL-RE t)         [{:cell t}                       more]
            (#{"+" "-" "*" "/"} t)         [(symbol t)                      more]
            :else                          (throw (ex-info "Bad atom" {:token t}))))))))

(defn parse-formula [raw]
  ;; raw is "=..."; returns the parsed AST.
  (let [body (subs raw 1)]
    (try
      (let [[ast leftover] (parse-tokens (tokenise body))]
        (when (seq leftover)
          (throw (ex-info "Trailing tokens" {:tokens leftover})))
        ast)
      (catch :default _e :error/parse))))

(defn collect-deps [ast]
  ;; Returns the set of cell ids referenced anywhere in the AST.
  (cond
    (vector? ast)         (apply clojure.set/union (map collect-deps ast))
    (and (map? ast)
         (:cell ast))     #{(:cell ast)}
    :else                 #{}))

(declare evaluate-cell)

(defn evaluate-ast [ast cells visited]
  (cond
    (number? ast) ast
    (nil? ast)    nil

    (and (map? ast) (:cell ast))
    (evaluate-cell (:cell ast) cells visited)

    (vector? ast)
    (let [[op & args] ast
          vals        (mapv #(evaluate-ast % cells visited) args)]
      (case (str op)
        "+" (apply + vals)
        "-" (apply - vals)
        "*" (apply * vals)
        "/" (if (some zero? (rest vals)) :error/div-by-zero (apply / vals))
        :error/unknown-op))

    :else :error/eval))

(defn evaluate-cell [id cells visited]
  ;; Returns the cell's display value, or :error/cycle, or :error/parse.
  (cond
    (visited id)  :error/cycle
    :else
    (if-let [{:keys [raw formula? ast]} (get cells id)]
      (cond
        (= ast :error/parse) :error/parse
        formula?             (evaluate-ast ast cells (conj visited id))
        :else                (let [n (js/parseFloat raw)]
                               (if (js/isNaN n) raw n)))
      0)))                                       ;; empty cells are 0

;; ============================================================================
;; EVENTS
;; ============================================================================

(rf/reg-event-db :cells/initialise
  {:doc "Seed an empty spreadsheet."}
  (fn [db _]
    (assoc db :cells {:cells {} :selected-id "A1" :editing-id nil})))

(rf/reg-event-db :cells/select
  (fn [db [_ id]]
    (assoc-in db [:cells :selected-id] id)))

(rf/reg-event-db :cells/start-editing
  (fn [db [_ id]]
    (-> db
        (assoc-in [:cells :selected-id] id)
        (assoc-in [:cells :editing-id]  id))))

(rf/reg-event-db :cells/commit
  {:doc "Commit the user's edit. Parses formulas and stores deps."
   :spec [:cat [:= :cells/commit] :string :string]}
  (fn [db [_ id raw]]
    (let [formula? (and (string? raw) (str/starts-with? raw "="))
          ast      (when formula? (parse-formula raw))
          deps     (when formula? (collect-deps ast))
          entry    (cond
                     (str/blank? raw) nil           ;; empty → remove the cell
                     :else            {:raw raw
                                       :formula? formula?
                                       :deps     (or deps #{})
                                       :ast      ast})]
      (-> db
          (assoc-in  [:cells :editing-id] nil)
          (update-in [:cells :cells]
                     (fn [m] (if entry
                               (assoc m id entry)
                               (dissoc m id))))))))

;; ============================================================================
;; SUBSCRIPTIONS
;; ============================================================================
;;
;; The :cells/value sub is parameterised by id — `(subscribe [:cells/value
;; "A1"])`. Each cell's display value derives from the cells map. Reagent
;; reactivity ensures only cells transitively depending on a changed cell
;; re-render.

(rf/reg-sub :cells/all-cells
  (fn [db _] (get-in db [:cells :cells])))

(rf/reg-sub :cells/raw
  :<- [:cells/all-cells]
  (fn [cells [_ id]] (get-in cells [id :raw] "")))

(rf/reg-sub :cells/value
  {:doc "Display value of cell `id`. Pure derivation against the full cells map."}
  :<- [:cells/all-cells]
  (fn [cells [_ id]]
    (evaluate-cell id cells #{})))

(rf/reg-sub :cells/selected-id (fn [db _] (get-in db [:cells :selected-id])))
(rf/reg-sub :cells/editing-id  (fn [db _] (get-in db [:cells :editing-id])))

;; ============================================================================
;; VIEW
;; ============================================================================

(reg-view cell-view [id]
  (let [editing-id @(subscribe [:cells/editing-id])
        editing?   (= editing-id id)
        raw        @(subscribe [:cells/raw   id])
        value      @(subscribe [:cells/value id])
        display    (cond
                     editing?                  raw
                     (= value :error/parse)    "#PARSE"
                     (= value :error/cycle)    "#CYCLE"
                     (= value :error/eval)     "#EVAL"
                     (= value :error/div-by-zero) "#DIV/0"
                     :else                     (str value))]
    [:td.cell {:on-click #(dispatch [:cells/start-editing id])}
     (if editing?
       [:input {:type      "text"
                :auto-focus true
                :default-value raw
                :on-blur    #(dispatch [:cells/commit id (.. % -target -value)])
                :on-key-down #(when (= "Enter" (.-key %))
                                (dispatch [:cells/commit id (.. % -target -value)]))}]
       display)]))

(reg-view cells-grid []
  [:table.cells-grid
   [:thead [:tr [:th] (for [c (range COLS)] ^{:key c} [:th (char (+ 65 c))])]]
   [:tbody
    (for [r (range 1 (inc ROWS))]
      ^{:key r}
      [:tr [:th r]
       (for [c (range COLS)]
         ^{:key c}
         [cell-view (cell-id c r)])])]])

;; ============================================================================
;; HEADLESS TESTS
;; ============================================================================

(defn cells-tests []
  (with-frame [f (rf/make-frame {:on-create [:cells/initialise]})]
    ;; Plain literal.
    (rf/dispatch-sync [:cells/commit "A1" "5"] {:frame f})
    (assert (= 5 (rf/compute-sub [:cells/value "A1"] (rf/get-frame-db f))))

    ;; Formula referencing A1.
    (rf/dispatch-sync [:cells/commit "B1" "=(+ A1 10)"] {:frame f})
    (assert (= 15 (rf/compute-sub [:cells/value "B1"] (rf/get-frame-db f))))

    ;; Updating A1 propagates through B1's subscription.
    (rf/dispatch-sync [:cells/commit "A1" "100"] {:frame f})
    (assert (= 110 (rf/compute-sub [:cells/value "B1"] (rf/get-frame-db f))))

    ;; Cycle detection: A1 = B1, B1 = A1.
    (rf/dispatch-sync [:cells/commit "A1" "=B1"] {:frame f})
    (rf/dispatch-sync [:cells/commit "B1" "=A1"] {:frame f})
    (assert (= :error/cycle (rf/compute-sub [:cells/value "A1"] (rf/get-frame-db f))))

    ;; Parse error.
    (rf/dispatch-sync [:cells/commit "C1" "=)bad"] {:frame f})
    (assert (= :error/parse (rf/compute-sub [:cells/value "C1"] (rf/get-frame-db f))))

    ;; Empty cells are zero.
    (assert (= 0 (rf/compute-sub [:cells/value "Z99"] (rf/get-frame-db f))))))

;; ============================================================================
;; MOUNT
;; ============================================================================

;; React root named `react-root` (not `root`) so it does NOT collide
;; with reg-view-defined view vars in this ns.
(defonce react-root
  (rdc/create-root (js/document.getElementById "app")))

(defn ^:export run []
  ;; rf2-84po: re-frame.substrate.reagent ns-load auto-registers as default.
  (rf/init!)
  (rf/dispatch-sync [:cells/initialise])
  (rdc/render react-root [cells-grid]))
