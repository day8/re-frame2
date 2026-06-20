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
  (:require [reagent.dom.client :as rdc]
            [re-frame.core :as rf]
            ;; `re-frame.schemas` ships in day8/re-frame2-schemas.
            ;; Loading the ns here registers its late-bind hooks so
            ;; rf/reg-app-schema resolves.
            [re-frame.schemas]
            [re-frame.views]
            [re-frame.adapter.reagent :as reagent-adapter]
            [clojure.set]
            [clojure.string :as str])
  (:require-macros [re-frame.core :refer [reg-view with-frame]]))

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

;; EP-0002 (rf2-5q7um6): reg-app-schema is context-required frame-local; a
;; bare ns-load call raises :rf.error/no-frame-context. This example runs in
;; :rf/default (see `run`/`reg-frame app-frame`), so name it explicitly so the
;; schema binds to the app frame whose commits it validates.
(with-frame :rf/default
  (rf/reg-app-schema [:cells] {:schema CellsState}))

;; ============================================================================
;; PARSER + EVALUATOR
;; ============================================================================
;;
;; Tiny S-expression flavor: '=(+ A1 (* B2 3))'. Pure functions, JVM-runnable.

(def num-re
  "Full-string numeric grammar: optional sign, integer and/or fractional
   part, optional exponent. Anchored end-to-end so lax prefixes like
   \"1abc\" or \"1.2.3\" are rejected — `js/parseFloat` would otherwise
   silently accept their leading numeric run (\"1abc\" → 1), letting an
   invalid token/literal evaluate as a number instead of surfacing a
   parse error / staying text."
  #"^[+-]?(\d+\.?\d*|\.\d+)([eE][+-]?\d+)?$")

(defn parse-num
  "Strict numeric parse: returns the number iff `s` (trimmed) is wholly
   numeric, else nil."
  [s]
  (let [trimmed (str/trim s)]
    (when (re-matches num-re trimmed)
      (let [n (js/parseFloat trimmed)]
        (when-not (js/isNaN n) n)))))

(defn tokenise [s]
  ;; Splits a formula body into tokens. Whitespace-separated; '(' and ')'
  ;; split out as their own tokens.
  (->> (-> s
           (str/replace #"\(" " ( ")
           (str/replace #"\)" " ) ")
           (str/split #"\s+"))
       (remove str/blank?)
       (vec)))

;; A formula's expected shape, in user terms — reused across every parse
;; error message so the user always learns what a valid formula looks like.
(def formula-shape-hint
  "=(+ A1 B2) — start with '=', then numbers, cell refs (A1..Z100), and +-*/ inside ().")

(defn parse-tokens [tokens]
  ;; Returns [ast remaining-tokens] or throws on malformed input. Each
  ;; ex-info carries the offending token in its data so `parse-formula` can
  ;; build an actionable, position-aware message (rf2-5tim8h).
  (if (empty? tokens)
    [nil tokens]
    (let [[t & more] tokens]
      (case t
        "(" (loop [acc [] toks more]
              (cond
                (empty? toks)        (throw (ex-info "a '(' is never closed — add a matching ')'"
                                                     {:token "("}))
                (= ")" (first toks)) [acc (rest toks)]
                :else                (let [[child rest-toks] (parse-tokens toks)]
                                       (recur (conj acc child) rest-toks))))
        ")" (throw (ex-info "an extra ')' has no matching '(' — remove it or add a '('"
                            {:token ")"}))
        ;; Atom: number, cell ref, or operator symbol.
        (let [num (parse-num t)]
          (cond
            (some? num)                    [num                             more]
            (re-matches cell-re t)         [{:cell t}                       more]
            (#{"+" "-" "*" "/"} t)         [(symbol t)                      more]
            :else                          (throw (ex-info (str "'" t "' is not a number, a cell ref "
                                                                "(A1..Z100), or one of + - * /")
                                                           {:token t}))))))))

(defn parse-error-message
  "Build an actionable parse-error message for cell `id` (may be nil) whose
   formula text is `raw`, given the caught exception `e`. Names the cell, shows
   the formula, points at the offending token where known, and states the
   expected shape in user terms (rf2-5tim8h)."
  [id raw e]
  (let [reason (or (ex-message e) "could not be parsed")
        token  (:token (ex-data e))
        ;; Token position in the formula body (1-based, after the '='), where
        ;; the offending token can be located. Best-effort: nil when unknown.
        pos    (when (and token (string? raw))
                 (let [i (str/index-of raw token)]
                   (when i (inc i))))]
    (str "Cell " (or id "?") ": can't parse formula \"" raw "\""
         (when pos (str " (near position " pos
                        (when token (str ", token \"" token "\"")) ")"))
         " — " reason ". Expected " formula-shape-hint)))

(defn parse-formula
  "Parse `raw` (a \"=...\" formula) for cell `id` (used only to make the error
   message name the cell). Returns the AST on success, or a `[:error/parse msg]`
   pair carrying an actionable message on failure (rf2-5tim8h)."
  ([raw] (parse-formula nil raw))
  ([id raw]
   (let [body (subs raw 1)]
     (try
       (let [[ast leftover] (parse-tokens (tokenise body))]
         (when (seq leftover)
           (throw (ex-info (str "extra tokens after the formula: "
                                (str/join " " leftover))
                           {:token (first leftover)})))
         ast)
       (catch :default e
         [:error/parse (parse-error-message id raw e)])))))

(defn parse-error?
  "True iff `ast` is the `[:error/parse msg]` failure pair."
  [ast]
  (and (vector? ast) (= :error/parse (first ast))))

(defn parse-error-text
  "The actionable message from a `[:error/parse msg]` pair (nil otherwise)."
  [ast]
  (when (parse-error? ast) (second ast)))

(defn collect-deps [ast]
  ;; Returns the set of cell ids referenced anywhere in the AST. A parse-error
  ;; pair (`[:error/parse msg]`) is not an AST and carries no deps.
  (cond
    (parse-error? ast)    #{}
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
        ;; Surface the first upstream error marker that reached us — a
        ;; referenced cell's parse-error pair or a keyword error marker — else
        ;; :error/type for text-in-arithmetic.
        (or (first (filter parse-error? vals))
            (first (filter keyword? vals))
            :error/type)))

    :else :error/eval))

(defn evaluate-cell [id cells visited]
  ;; Returns the cell's display value, :error/cycle, or the parse-error pair
  ;; `[:error/parse msg]` (the actionable message rides through to the view).
  (cond
    (visited id)  :error/cycle
    :else
    (if-let [{:keys [raw formula? ast]} (get cells id)]
      (cond
        (parse-error? ast)   ast
        formula?             (evaluate-ast ast cells (conj visited id))
        :else                (let [n (parse-num raw)]
                               (if (some? n) n raw)))
      0)))                                       ;; empty cells are 0

;; ============================================================================
;; EVENTS
;; ============================================================================

(rf/reg-event :cells/initialise
  {:doc "Seed an empty spreadsheet."}
  (fn handler-cells-initialise [{:keys [db]} _]
    {:db (assoc db :cells {:cells {} :selected-id "A1" :editing-id nil})}))

(rf/reg-event :cells/select
  {:doc "User clicked a cell. Marks it selected (without opening the editor)."}
  (fn handler-cells-select [{:keys [db]} [_ id]]
    {:db (assoc-in db [:cells :selected-id] id)}))

(rf/reg-event :cells/start-editing
  {:doc "Open the inline editor for cell `id` (also selects it)."}
  (fn handler-cells-start-editing [{:keys [db]} [_ id]]
    {:db (-> db
        (assoc-in [:cells :selected-id] id)
        (assoc-in [:cells :editing-id]  id))}))

(rf/reg-event :cells/commit
  {:doc "Commit the user's edit. Parses formulas and stores deps."
   :schema [:cat [:= :cells/commit] :string :string]}
  (fn handler-cells-commit [{:keys [db]} [_ id raw]]
    {:db (let [formula? (and (string? raw) (str/starts-with? raw "="))
          ast      (when formula? (parse-formula id raw))
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
                               (dissoc m id))))))}))

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
        ;; A parse error rides through as `[:error/parse msg]`; pull out the
        ;; actionable message to surface on hover (rf2-5tim8h).
        parse-err  (parse-error-text value)
        display    (cond
                     editing?                  raw
                     parse-err                 "#PARSE"
                     (= value :error/cycle)    "#CYCLE"
                     (= value :error/eval)     "#EVAL"
                     (= value :error/type)     "#TYPE"
                     (= value :error/div-by-zero) "#DIV/0"
                     :else                     (str value))]
    ;; `data-cell`/`data-cell-input` carry the grid coordinate as a domain
    ;; attribute; `data-testid` mirrors the cluster's test-hook scheme so the
    ;; six examples share one selector convention. On a parse error the
    ;; actionable message rides in `title` (native hover tooltip) and is
    ;; mirrored to `data-parse-error` so it is inspectable, with an
    ;; `cell--parse-error` class for styling.
    [:td.cell (cond-> {:data-cell   id
                       :data-testid (str "cells-cell-" id)
                       :on-click    #(dispatch [:cells/start-editing id])}
                parse-err (assoc :title parse-err
                                 :data-parse-error parse-err
                                 :class "cell--parse-error"))
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

;; EP-0002 (rf2-9o48ih): under the carried invariant the runtime never
;; synthesises a frame from absence — an app must establish its frame
;; explicitly. `init!` installs the adapter (it does NOT create the frame),
;; `reg-frame` registers the app frame, the boot dispatch runs under
;; `with-frame`, and the render is wrapped in a `frame-provider` so every
;; in-tree `dispatch`/`subscribe` resolves to the app frame. Matches the
;; canonical mount in examples/reagent/counter/core.cljs.
(def app-frame :rf/default)

(defn run []
  ;; Pass the adapter spec map directly — no registry.
  (rf/init! reagent-adapter/adapter)
  (rf/reg-frame app-frame {})
  (rf/with-frame app-frame
    (rf/dispatch-sync [:cells/initialise]))
  (when (exists? js/document)
    (when-not @react-root
      (reset! react-root (rdc/create-root (js/document.getElementById "app"))))
    (rdc/render @react-root
                [rf/frame-provider-existing {:frame app-frame}
                 [cells-grid]])))
