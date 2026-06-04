(ns seven-guis.cells.core
  "7GUIs #7 — Cells.

   A small spreadsheet with cells A1..Z100. Each cell holds either a literal
   value or a formula. Formulas reference other cells. Changes propagate.

   The 7GUIs page calls this out as a test of *change propagation through
   a dependency graph*. The classic trap is hand-rolled per-cell observers
   that go stale (correctness bugs). The re-frame2 approach leans on the
   reaction layer instead: each cell's display value is a registered
   subscription (`:cells/value`) parameterised by id, derived purely from
   `app-db`'s cell map.

   How propagation actually works here: every `:cells/value` sub takes the
   whole cell map (`:cells/all-cells`) as its single input, so committing
   ANY cell produces a fresh map identity and recomputes EVERY mounted
   value reaction — dependent or not. This is the recompute-everything
   shape, but it stays cheap because (a) the evaluator is a pure function
   and the grid is small (26×100), and (b) the reaction layer `=`-dedups
   each computed value, so a cell whose result is unchanged does not
   re-render. The win is correctness for free: there are no hand-maintained
   per-cell dependency edges to keep in sync. (True per-cell propagation —
   a signal keyed on each referenced id rather than the whole map — is a
   larger change and not warranted at this scale.)

   Demonstrates:
   - Pure derivation of every cell from a single shared input sub
   - Reaction-layer `=`-dedup avoiding re-render of unchanged cells
   - Cycles detected via a visited-set walk during evaluation
   - Typed error markers propagated through arithmetic (never throws)
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
            [re-frame.views]
            [re-frame.adapter.reagent-slim :as reagent-slim-adapter]
            [clojure.set]
            [clojure.string :as str])
  (:require-macros [re-frame.core :refer [reg-view]]))

(def cols
  "Number of spreadsheet columns (A..Z)."
  26)

(def rows
  "Number of spreadsheet rows (1..100)."
  100)

(def cell-re
  "Cell-id regex — one capital letter + 1-3 digits (e.g. A1, Z99, A100).
   The grid is 26 cols × 100 rows, so row 100 cell-ids (A100..Z100) carry
   three digits and MUST match here, otherwise a formula referencing a
   row-100 cell parses as #PARSE even though the cell exists in the grid."
  #"^[A-Z]\d{1,3}$")

(defn cell-id [col row] (str (char (+ 65 col)) row))
(defn parse-cell-id [s]
  (when (re-matches cell-re s)
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
            (re-matches cell-re t)         [{:cell t}                       more]
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
      ;; Propagate errors/non-numbers rather than feeding them to the op
      ;; (which would throw): surface an upstream error marker if one
      ;; reached us, else :error/type for text-in-arithmetic. `-` and `/`
      ;; with no args throw an arity error, so guard empty `vals` too.
      (if (and (seq vals) (every? number? vals))
        (case (str op)
          "+" (apply + vals)
          "-" (apply - vals)
          "*" (apply * vals)
          "/" (if (some zero? (rest vals)) :error/div-by-zero (apply / vals))
          :error/unknown-op)
        (or (first (filter keyword? vals)) :error/type)))

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
  (fn handler-cells-initialise [db _]
    (assoc db :cells {:cells {} :selected-id "A1" :editing-id nil})))

(rf/reg-event-db :cells/select
  {:doc "User clicked a cell. Marks it selected (without opening the editor)."}
  (fn handler-cells-select [db [_ id]]
    (assoc-in db [:cells :selected-id] id)))

(rf/reg-event-db :cells/start-editing
  {:doc "Open the inline editor for cell `id` (also selects it)."}
  (fn handler-cells-start-editing [db [_ id]]
    (-> db
        (assoc-in [:cells :selected-id] id)
        (assoc-in [:cells :editing-id]  id))))

(rf/reg-event-db :cells/commit
  {:doc "Commit the user's edit. Parses formulas and stores deps."
   :schema [:cat [:= :cells/commit] :string :string]}
  (fn handler-cells-commit [db [_ id raw]]
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
;; "A1"])`. Each cell's display value derives from the full cells map, so
;; every value reaction recomputes on any edit; the reaction layer =-dedups
;; the result so only cells whose value actually changed re-render.

(rf/reg-sub :cells/all-cells
  {:doc "The sparse cell map (only edited cells are present). Single input to
         every cell's value/raw sub."}
  (fn sub-cells-all-cells [db _] (get-in db [:cells :cells])))

(rf/reg-sub :cells/raw
  {:doc "Raw text the user typed into cell `id` (empty string if untouched)."}
  :<- [:cells/all-cells]
  (fn sub-cells-raw [cells [_ id]] (get-in cells [id :raw] "")))

(rf/reg-sub :cells/value
  {:doc "Display value of cell `id`. Pure derivation against the full cells map.
         The evaluator already turns bad input into typed error markers; the
         try/catch is a defence-in-depth backstop so a value reaction can never
         throw out of its compute (which would break render of the whole grid)."}
  :<- [:cells/all-cells]
  (fn sub-cells-value [cells [_ id]]
    (try
      (evaluate-cell id cells #{})
      (catch :default _ :error/eval))))

(rf/reg-sub :cells/selected-id
  {:doc "Id of the currently selected cell, or nil."}
  (fn sub-cells-selected-id [db _] (get-in db [:cells :selected-id])))

(rf/reg-sub :cells/editing-id
  {:doc "Id of the cell whose inline editor is open, or nil."}
  (fn sub-cells-editing-id [db _] (get-in db [:cells :editing-id])))

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
                     (= value :error/type)     "#TYPE"
                     (= value :error/div-by-zero) "#DIV/0"
                     :else                     (str value))]
    ;; `data-cell`/`data-cell-input` carry the grid coordinate as a domain
    ;; attribute; `data-testid` mirrors the cluster's test-hook scheme so the
    ;; six examples share one selector convention.
    [:td.cell {:data-cell   id
               :data-testid (str "cells-cell-" id)
               :on-click    #(dispatch [:cells/start-editing id])}
     (if editing?
       [:input {:type      "text"
                :auto-focus true
                :default-value raw
                :data-cell-input id
                :data-testid     (str "cells-cell-input-" id)
                :on-blur    #(dispatch [:cells/commit id (.. % -target -value)])
                :on-key-down #(when (= "Enter" (.-key %))
                                (dispatch [:cells/commit id (.. % -target -value)]))}]
       display)]))

(reg-view cells-grid []
  [:table.cells-grid
   [:thead [:tr [:th] (for [c (range cols)] ^{:key c} [:th (char (+ 65 c))])]]
   [:tbody
    (for [r (range 1 (inc rows))]
      ^{:key r}
      [:tr [:th r]
       (for [c (range cols)]
         ^{:key c}
         [cell-view (cell-id c r)])])]])

;; ============================================================================
;; MOUNT
;; ============================================================================

;; The React root is held in an atom and materialised lazily inside `run`
;; (not at ns-load) per examples/TESTING.md §Example mount-isolation
;; convention: ns-load must produce no DOM side effects so co-required
;; example namespaces don't race `create-root` onto the shared `#app`.
(defonce react-root (atom nil))

(defn run []
  ;; Pass the adapter spec map directly — no registry.
  (rf/init! reagent-slim-adapter/adapter)
  (rf/dispatch-sync [:cells/initialise])
  (when (exists? js/document)
    (when-not @react-root
      (reset! react-root (rdc/create-root (js/document.getElementById "app"))))
    (rdc/render @react-root [cells-grid])))
