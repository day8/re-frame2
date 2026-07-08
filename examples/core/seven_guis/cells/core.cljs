(ns seven-guis.cells.core
  "7GUIs #7 — Cells: a tiny spreadsheet.

   A 26×100 grid (A1..Z100). Type a number into a cell, or a formula like
   `=(+ A1 B2)` that refers to other cells. Edit a cell anyone depends on and
   the change ripples outward, the way it does in every spreadsheet you've ever
   used. That ripple is the whole exercise.

   Here's the trick, and it's a good one: there is no propagation code. We
   never track who-depends-on-whom or push updates along edges. Each cell's
   display value is just a subscription — `:cells/value` parameterised by id —
   that reads the cell map out of app-db and computes a number. The derivation
   graph does the rippling for us.

   How? Every `:cells/value` reads the *whole* cell map (`:cells/all-cells`) as
   its one input. Commit any edit and that map gets a fresh identity, so every
   cell on screen recomputes — dependents and bystanders alike. Sounds
   wasteful; isn't. The evaluator is a pure function over a small grid, and the
   subscription layer `=`-dedups every result, so a cell whose value didn't
   actually move doesn't re-render. We trade a little recompute (cheap) for
   zero bookkeeping (priceless). The
   [derivation graph](../../../../docs/core/glossary.md#the-derivation-graph)
   has the full story.

   What this example shows off:
   - Every cell derived purely from one shared input sub — no per-cell wiring
   - `=`-dedup keeping unchanged cells from re-rendering
   - Cycles (A1 → B1 → A1) caught by a visited-set walk, not a stack overflow
   - Bad arithmetic turned into typed error markers — the evaluator never throws
   - A sparse cell registry: only edited cells take up space
   - A pure parser + evaluator — no `eval`, no DOM, easy to unit-test

   The formula language is a tiny parenthesised calculator: numbers, cell refs
   (A1..Z100), and `+ - * /`, all written prefix inside `()`."
  (:require [reagent.dom.client :as rdc]
            [re-frame.core :as rf]
            ;; Pulled in for its side effect: loading it wires up the hooks that
            ;; make `rf/reg-app-schema` actually do something.
            [re-frame.schemas]
            [re-frame.adapter.reagent :as reagent-adapter]
            [clojure.set]
            [clojure.string :as str]))

(def cols
  "Number of spreadsheet columns (A..Z)."
  26)

(def rows
  "Number of spreadsheet rows (1..100)."
  100)

(def cell-re
  "A cheap *syntactic* gate for a cell id: one capital letter, then 1-3 digits
   (A1, Z99, A100). The three-digit allowance is load-bearing. Row 100 really
   exists, so its ids (A100..Z100) need three digits — leave those out and a
   formula pointing at a perfectly valid row-100 cell would be rejected as
   #PARSE. Off-by-one errors love a good fencepost.

   Note this shape alone is *too permissive*: it also admits refs like A0, A101,
   or Z999 that are outside the 26×100 grid. Range is not a job a regex should
   do; `in-grid?` (via `parse-cell-id` / `cell-ref?`) is the authority that pins
   a ref to a real cell, so out-of-range refs fail loud at parse time rather
   than silently evaluating as an empty cell."
  #"^[A-Z]\d{1,3}$")

;; The two directions between a grid coordinate and its name. 65 is the
;; ASCII code for "A", so column 0 reads as A, column 1 as B, and so on.
(defn cell-id
  "Coordinate → name: `(cell-id 0 1)` => \"A1\"."
  [col row] (str (char (+ 65 col)) row))

(defn in-grid?
  "True iff coordinate `[col row]` names a real cell: column 0..25, row 1..100.
   This — not `cell-re` — is what actually bounds a ref to the grid."
  [col row]
  (and (<= 0 col (dec cols))
       (<= 1 row rows)))

(defn parse-cell-id
  "Name → coordinate: \"A1\" => `[0 1]`. Returns nil for anything that isn't a
   real, in-grid cell id — including refs that are *shaped* like a cell id but
   point outside the 26×100 grid (A0, A101, Z999). The regex is only a cheap
   syntactic gate; the `in-grid?` check is what rejects out-of-range refs."
  [s]
  (when (re-matches cell-re s)
    ;; `.charCodeAt` gives the letter's numeric code (A=65), the exact inverse
    ;; of `cell-id`'s `(char (+ 65 col))`. (`.charAt` would hand back a
    ;; one-char *string*, not a code.)
    (let [col (- (.charCodeAt s 0) 65)
          row (js/parseInt (subs s 1) 10)]
      (when (in-grid? col row) [col row]))))

(defn cell-ref?
  "True iff `s` names a real, in-grid cell (A1..Z100). The single authority the
   parser consults when deciding whether a token is a cell reference."
  [s]
  (some? (parse-cell-id s)))

;; ============================================================================
;; SCHEMA
;; ============================================================================

;; What one cell remembers. We keep the raw text alongside the parsed form so
;; editing shows you exactly what you typed, while the evaluator works from the
;; pre-parsed AST.
(def CellEntry
  [:map
   [:raw     :string]                  ;; what the user typed
   [:formula? :boolean]                 ;; true if raw starts with '='
   [:deps    [:set :string]]            ;; cell ids referenced (static dep set)
   [:ast     [:maybe :any]]])           ;; parsed AST for formulas; nil otherwise

(def CellsState
  [:map
   [:cells      [:map-of :string CellEntry]]       ;; sparse: only edited cells exist
   [:editing-id [:maybe :string]]])

;; Hand the schema to the frame so every commit under `[:cells]` gets validated.
;; `reg-app-schema` needs a frame in scope, and `with-frame` supplies one by id
;; — `:rf/default`, the same name `run` mounts down below. The id is all that's
;; needed: we can register the schema here at load time, before the frame
;; actually exists, and it snaps into place once the frame is created.
(rf/with-frame :rf/default
  (rf/reg-app-schema [:cells] CellsState))

;; ============================================================================
;; PARSER + EVALUATOR
;; ============================================================================
;;
;; A formula is a string like '=(+ A1 (* B2 3))'. Turning that into an answer
;; is two steps: parse the text into an AST, then walk the AST computing a
;; number. Everything here is a pure function — no `eval`, no DOM, no surprises
;; — so it's trivial to unit-test in isolation. (The numeric parse leans on
;; `js/parseFloat` / `js/isNaN`, so this particular file is CLJS-only; the
;; parse/eval shape itself is host-agnostic.)

(def num-re
  "Matches a string that is *entirely* a number: optional sign, an integer
   and/or fractional part, optional exponent. The anchors (`^`…`$`) matter.
   Without them `js/parseFloat` is too forgiving — it reads \"1abc\" as 1 and
   \"1.2.3\" as 1.2, happily swallowing the leading numeric run. That would let
   junk masquerade as a number instead of surfacing as a parse error or staying
   plain text, so we insist the whole string be numeric or nothing."
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
  ;; Chop a formula body into tokens. Tokens are whitespace-separated, and we
  ;; pad the parens with spaces first so "(+" becomes "( +" — that way "(" and
  ;; ")" fall out as tokens of their own without anyone needing to write spaces
  ;; around them.
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
  ;; A little recursive-descent parser. Reads one expression off the front of
  ;; the token list and returns [ast remaining-tokens]; a "(" recurses to gobble
  ;; a nested list until its ")". Malformed input throws, and each thrown
  ;; ex-info tucks the offending token into its data so `parse-formula` can point
  ;; the user right at it.
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
        ;; Anything else is a single atom: a number, a cell ref, an operator,
        ;; or — if it's none of those — a typo we explain.
        (let [num (parse-num t)]
          (cond
            (some? num)                    [num                             more]
            (cell-ref? t)                  [{:cell t}                       more]
            ;; Shaped like a cell ref but off the grid (A0, A101, Z999). Reject
            ;; it here with a targeted message instead of letting it slip
            ;; through as a `{:cell …}` node that evaluates as an empty cell.
            (re-matches cell-re t)         (throw (ex-info (str "'" t "' is shaped like a cell ref but "
                                                                "falls outside the grid (A1..Z100)")
                                                           {:token t}))
            (#{"+" "-" "*" "/"} t)         [(symbol t)                      more]
            :else                          (throw (ex-info (str "'" t "' is not a number, a cell ref "
                                                                "(A1..Z100), or one of + - * /")
                                                           {:token t}))))))))

(defn parse-error-message
  "Turn a caught parse exception into a sentence a human can act on. Names the
   cell `id` (may be nil), echoes the formula `raw`, points at the offending
   token when we know it, and reminds the user what a good formula looks like.
   A blank \"#PARSE\" tells you nothing; \"can't parse … token '%'\" tells you
   where to look."
  [id raw e]
  (let [reason (or (ex-message e) "could not be parsed")
        token  (:token (ex-data e))
        ;; Where in the formula the bad token sits, 1-based after the '='. We
        ;; just find the token's first occurrence — good enough to nudge the
        ;; eye, and nil if we can't locate it.
        pos    (when (and token (string? raw))
                 (let [i (str/index-of raw token)]
                   (when i (inc i))))]
    (str "Cell " (or id "?") ": can't parse formula \"" raw "\""
         (when pos (str " (near position " pos
                        (when token (str ", token \"" token "\"")) ")"))
         " — " reason ". Expected " formula-shape-hint)))

(defn parse-formula
  "Parse a \"=...\" formula into an AST. `id` is only along for the ride so a
   failure message can name the cell. Note the return contract: success gives
   you the AST, failure gives you a `[:error/parse msg]` pair. We don't throw
   across this boundary — the error becomes a value that rides all the way
   through evaluation and out to the cell as a tooltip."
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
  ;; Walk the AST and gather every cell id it mentions. We stash this set on the
  ;; cell when we commit it — handy for tooling and debugging, even though
  ;; evaluation doesn't lean on it (the derivation graph already knows the
  ;; dependencies). A parse-error pair isn't an AST, so it contributes nothing.
  (cond
    (parse-error? ast)    #{}
    (vector? ast)         (apply clojure.set/union (map collect-deps ast))
    (and (map? ast)
         (:cell ast))     #{(:cell ast)}
    :else                 #{}))

(declare evaluate-cell)

(defn evaluate-ast
  "Walk an AST to a value. Numbers are themselves, a cell ref defers to
   `evaluate-cell`, and a list applies its operator to evaluated args. Errors
   are values here, not exceptions — they flow upward instead of unwinding the
   stack."
  [ast cells visited]
  (cond
    (number? ast) ast
    (nil? ast)    nil

    (and (map? ast) (:cell ast))
    (evaluate-cell (:cell ast) cells visited)

    (vector? ast)
    (let [[op & args] ast
          vals        (mapv #(evaluate-ast % cells visited) args)]
      ;; Only do arithmetic if every argument actually came back a number.
      ;; Feeding text or an error marker to `+` would throw, and so would `-`
      ;; or `/` with no args at all — hence the `seq` guard too.
      (if (and (seq vals) (every? number? vals))
        (case (str op)
          "+" (apply + vals)
          "-" (apply - vals)
          "*" (apply * vals)
          "/" (if (some zero? (rest vals)) :error/div-by-zero (apply / vals))
          :error/unknown-op)
        ;; Something upstream wasn't a number. If a real error reached us — a
        ;; referenced cell's parse-error pair, or a keyword marker like
        ;; :error/cycle — pass the first one along unchanged so the original
        ;; cause survives. Otherwise it's plain text in a sum: :error/type.
        (or (first (filter parse-error? vals))
            (first (filter keyword? vals))
            :error/type)))

    :else :error/eval))

(defn evaluate-cell
  "Compute one cell's display value. `visited` is the set of cell ids already on
   the current evaluation path — our cycle guard. If we're asked to evaluate a
   cell that's already on the path, A1 → B1 → A1 has come back to bite us, so we
   bail with :error/cycle instead of recursing forever. A formula that failed to
   parse returns its stored `[:error/parse msg]` pair as-is, so the actionable
   message rides through to the view; a well-formed formula recurses with itself
   added to the set; a literal parses to a number or stays text; an untouched
   cell is simply 0."
  [id cells visited]
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
    {:db (assoc db :cells {:cells {} :editing-id nil})}))

(rf/reg-event :cells/start-editing
  {:doc "Open the inline editor for cell `id`."}
  (fn handler-cells-start-editing [{:keys [db]} [_ id]]
    {:db (assoc-in db [:cells :editing-id] id)}))

(rf/reg-event :cells/commit
  {:doc "Save the user's edit. Parses any formula up front and stashes the AST
         and its deps on the cell — so evaluation later is just a walk, never a
         re-parse. Blank input deletes the cell, keeping the map sparse."
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
;; This is where the propagation magic actually lives — which is to say,
;; nowhere special. `:cells/value` is parameterised by id, so a view asks for a
;; specific cell with `(subscribe [:cells/value "A1"])`. Every such sub reads
;; the one shared input below and computes from scratch, and `=`-dedup makes
;; that cheap. No edges, no listeners, no propagation engine.

(rf/reg-sub :cells/all-cells
  {:doc "The sparse cell map — only edited cells are present. Every cell's value
         and raw sub hangs off this one input."}
  (fn sub-cells-all-cells [db _] (get-in db [:cells :cells])))

(rf/reg-sub :cells/raw
  {:doc "Raw text the user typed into cell `id` (empty string if untouched)."}
  :<- [:cells/all-cells]
  (fn sub-cells-raw [cells [_ id]] (get-in cells [id :raw] "")))

(rf/reg-sub :cells/value
  {:doc "Display value of cell `id` — a pure derivation over the whole cell map.
         The evaluator already turns bad input into typed error markers, so this
         try/catch is belt-and-suspenders: if some unforeseen input ever slips
         through and throws, one cell shows #EVAL instead of taking the entire
         grid's render down with it."}
  :<- [:cells/all-cells]
  (fn sub-cells-value [cells [_ id]]
    (try
      (evaluate-cell id cells #{})
      (catch :default _ :error/eval))))

(rf/reg-sub :cells/editing-id
  {:doc "Id of the cell whose inline editor is open, or nil."}
  (fn sub-cells-editing-id [db _] (get-in db [:cells :editing-id])))

;; ============================================================================
;; VIEW
;; ============================================================================

;; One cell. It has two faces: while you're editing it shows an <input> holding
;; the raw text; otherwise it shows the computed value. Which face we wear comes
;; straight from subscriptions, so any edit anywhere just flows back in.
(rf/reg-view cell-view [id]
  (let [editing-id @(subscribe [:cells/editing-id])
        editing?   (= editing-id id)
        raw        @(subscribe [:cells/raw   id])
        value      @(subscribe [:cells/value id])
        ;; A parse error arrives as `[:error/parse msg]`; pull the message out so
        ;; we can show it on hover.
        parse-err  (parse-error-text value)
        ;; Pick what the cell shows. The error markers map to the familiar
        ;; spreadsheet hash-codes (#CYCLE, #DIV/0, …); anything else is just the
        ;; value, stringified.
        display    (cond
                     editing?                  raw
                     parse-err                 "#PARSE"
                     (= value :error/cycle)    "#CYCLE"
                     (= value :error/eval)     "#EVAL"
                     (= value :error/type)     "#TYPE"
                     (= value :error/div-by-zero) "#DIV/0"
                     :else                     (str value))]
    ;; The `data-*` attributes are hooks for tests and tooling: `data-cell`
    ;; tags the coordinate, and `data-testid` follows the same naming the other
    ;; seven-GUIs examples use, so one selector convention covers them all. When
    ;; the cell holds a parse error we also stash the message three ways — in
    ;; `title` for a native hover tooltip, in `data-parse-error` for inspection,
    ;; and as a class to style it.
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

(rf/reg-view cells-grid []
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

;; We hold the React root in an atom and create it lazily inside `run`, never at
;; load time. Loading a namespace must not touch the DOM — otherwise several
;; example namespaces sharing this page would all race to call `create-root` on
;; the same `#app` element. Once is plenty.
(defonce react-root (atom nil))

;; The frame's whole life happens in one place: the `frame-provider` down in
;; `mount!`. First mount creates the frame, applies its config, and fires
;; `:initial-events` once to seed app-db. After that every `dispatch` and
;; `subscribe` in the tree finds *this* frame. On hot reload the provider reuses
;; the frame it already made and skips the seeding, so your grid keeps the
;; values you typed. (`init!` below doesn't make the frame — the provider does.)
;; The [frame-provider](../../../../docs/core/glossary.md#frame-provider)
;; glossary entry has the longer version.
;;
;; `app-frame` is just an id we chose. `:rf/default` is an ordinary frame id —
;; no special powers, despite the official-looking name.
(def app-frame :rf/default)

;; `mount!` is browser setup: create the root lazily, then render the view tree
;; inside the frame-provider. `^:dev/after-load` is shadow's cue to re-run it on
;; each reload so your edited views re-render into the same root and same frame.
;; This is the canonical mount/boot shape, spelled the same in the counter and
;; todomvc examples. See `docs/core/how-to/boot-and-mount-an-app.md`.
(defn ^:dev/after-load mount! []
  (when-let [el (and (exists? js/document)
                     (js/document.getElementById "app"))]
    (when-not @react-root
      (reset! react-root (rdc/create-root el)))
    (rdc/render @react-root
                [rf/frame-provider {:id app-frame
                                    :initial-events [[:cells/initialise]]}
                 [cells-grid]])))

(defn run []
  ;; `init!` tells re-frame2 which reactive substrate to render through — here,
  ;; Reagent. Every adapter namespace exports an `adapter` var; you require the
  ;; namespace and hand that var straight in.
  (rf/init! reagent-adapter/adapter)
  (mount!))
