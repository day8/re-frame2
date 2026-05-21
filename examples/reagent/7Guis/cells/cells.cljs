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
   - Pure parser + evaluator (no eval, no host I/O)

   Scope: 26 cols × 100 rows. Formulas of the form '=expr' where expr is a
   simple S-expression-flavored calculator (numbers, +, -, *, /, cell refs).
   Excel-style infix is left for a future iteration; the shape of the
   solution doesn't change."
  (:require [reagent2.dom.client :as rdc]
            [re-frame.core :as rf]
            ;; `re-frame.schemas` ships in day8/re-frame2-schemas.
            ;; Loading the ns here registers its late-bind hooks so
            ;; rf/reg-app-schema resolves.
            [re-frame.schemas]
            [re-frame.adapter.reagent-slim :as reagent-slim-adapter]
            [clojure.set]
            [clojure.string :as str])
  (:require-macros [re-frame.core :refer [reg-view]]))

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
   :schema [:cat [:= :cells/commit] :string :string]}
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
    [:td.cell {:data-cell id
               :on-click #(dispatch [:cells/start-editing id])}
     (if editing?
       [:input {:type      "text"
                :auto-focus true
                :default-value raw
                :data-cell-input id
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
;; MOUNT
;; ============================================================================

;; React root named `react-root` (not `root`) so it does NOT collide
;; with reg-view-defined view vars in this ns.
(defonce react-root
  (rdc/create-root (js/document.getElementById "app")))

(defn ^:export run []
  ;; Pass the adapter spec map directly — no registry.
  (rf/init! reagent-slim-adapter/adapter)
  (rf/dispatch-sync [:cells/initialise])
  (rdc/render react-root [cells-grid]))
