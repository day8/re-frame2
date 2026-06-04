(ns re-frame2-pair-mcp.tools.args
  "Argument-coercion helpers for the snapshot / get-path / subscribe
  family — path vectors, frame ids, slice include lists, summary modes,
  per-slice mode maps, and the streaming filter map.

  Path-arg parsing (rf2-tygdv): two tools take a `:path` argument:
  `snapshot` (slice the :app-db slice) and `get-path` (direct
  read-by-path). Same parser, same semantics so agents learn the shape
  once.

  Accepted shapes from the MCP host:
    - JS array of strings  ⇒ each entry parsed as EDN; non-EDN entries
                             stay as strings.
    - CLJS vector          ⇒ pass through.
    - EDN-encoded string   ⇒ read-string (e.g. `\"[:cart :items 3 :sku]\"`).
    - nil / missing        ⇒ nil (no path slicing).

  A path is an EDN-encoded vector of keys addressing a subtree.
  The vocabulary is shared across re-frame2-pair-mcp / story-mcp so
  agents recognise the surface once."
  (:require [applied-science.js-interop :as j]
            [cljs.reader]
            [clojure.string :as str]
            [re-frame.mcp-base.args :as base-args]))

(def valid-slices
  #{:app-db :sub-cache :machines :epochs :traces})

;; ---------------------------------------------------------------------------
;; Boolean-arg table (rf2-c4fmh).
;;
;; The four boolean MCP args shared across re-frame2-pair-mcp tools each have one
;; load-bearing knob: their default posture. Their accept-shapes —
;; `true`/`false`, `"true"`/`"false"`/`"yes"`/`"no"`/`"1"`/`"0"` (case-
;; insensitive), `:true`/`:false`, unrecognised ⇒ default — are
;; identical and come from `re-frame.mcp-base.args/parse-boolean`
;; (rf2-vw4sq). Per-arg micro-wrappers were redundant friction: an
;; agent that learned `:dedup "yes"` worked, but `:cache "yes"` had
;; previously default-falsed because cache.cljs hand-rolled a smaller
;; parser. This table is the single source of truth for both the
;; default posture AND the accept-shape contract.
;;
;; Defaults:
;;
;;   :dedup              true   — structural dedup wins shrink-by-default
;;   :elision            true   — size-elision wins shrink-by-default
;;   :cache              false  — per-call cache is opt-in until agent
;;                                hosts have been taught the marker
;;   :include-sensitive  false  — spec/009 MUST default-suppress.
;;                                Wire-key drops the trailing `?` per
;;                                rf2-y710n + rf2-ihq4d: Anthropic's
;;                                tool-input-schema regex
;;                                `^[a-zA-Z0-9_.-]{1,64}$` rejects `?`.
;;                                The predicate FUNCTION name retains `?`
;;                                (idiom on predicates, not on data keys).
;;
;; Callers reach in via `parse-bool-arg`
;; (`(args/parse-bool-arg raw-args :dedup)`). The dispatcher and per-
;; tool bodies thread the raw JS args object through; nil-safety on
;; the args object is centralised here.

(def bool-args
  "Cross-tool boolean MCP args + their default postures (rf2-c4fmh)."
  {:dedup             {:default true}
   :elision           {:default true}
   :cache             {:default false}
   :include-sensitive {:default false}
   ;; rf2-qicji — list-subscriptions toggles its per-entry shape.
   ;; Default false: only the query-vectors ride the wire (the cheap
   ;; "what's subscribed" read); true also ships :value + :ref-count.
   :include-values    {:default false}
   ;; rf2-zo4b9 — read-recording's two toggles. :drain consumes the
   ;; buffered change-entries (the live-watch poll→consume→repeat idiom);
   ;; :stop tears the recording down after reading (read-and-close).
   ;; Both default false: a bare read is non-destructive.
   :drain             {:default false}
   :stop              {:default false}})

(defn parse-bool-arg
  "Resolve a boolean MCP arg by name. Returns the per-arg default from
  `bool-args` when the slot is absent / nil / unrecognised; delegates
  recognised-value parsing to
  `re-frame.mcp-base.args/parse-boolean` (rf2-vw4sq) — the cross-MCP
  accept-shape contract.

  `args` may be a JS args object, `nil`, or `js/undefined` — all three
  collapse to the table default."
  [args k]
  (let [default (get-in bool-args [k :default])
        raw     (when (and args (not (undefined? args)))
                  (j/get args (name k)))]
    (base-args/parse-boolean raw default)))

(defn ->frame-keyword
  "Coerce a frame-id string into a keyword. Accepts both bare names
   (`\"rf/default\"`) and EDN-shaped strings (`\":rf/default\"`) — strips
   a leading colon when present so callers can pass either form.

   Delegates to `re-frame.mcp-base.args/fresh-keyword` (rf2-xxtrz) so
   the slice-key / frame-key coercion is single-sourced across the
   re-frame2-pair-mcp wire surface — same helper underpins both
   `parse-frames-arg` and the per-slice-mode key coercion in
   `parse-modes-arg`. On CLJS keywords are not interned in the JVM
   never-shrinking-table sense, so the intern-DoS concern that gates
   `fresh-keyword` on the JVM side doesn't apply here; the call is
   straight string-to-keyword coercion."
  [x]
  (base-args/fresh-keyword x))

(defn coerce-path-segment
  "Coerce one segment of a JS-array path argument.

  Try `read-string`; on any failure (the bare identifier case —
  `\"items\"` would otherwise read as a symbol, which is the wrong
  `get-in` key) fall through as the original string. `read-string`
  parses EDN literals (`\":cart\"` ⇒ `:cart`, `\"0\"` ⇒ `0`,
  `\"-1\"` ⇒ `-1`) and rejects anything else, so the catch-fallback
  IS the discriminator — no first-char heuristic needed."
  [s]
  (if-not (string? s)
    s
    (let [trimmed (str/trim s)
          parsed  (try (cljs.reader/read-string trimmed)
                       (catch :default _ ::reader-fail))]
      (cond
        (= ::reader-fail parsed) s
        ;; Symbols are the reader's "bare identifier" outcome — not a
        ;; valid `get-in` key on a map keyed by strings or keywords;
        ;; keep the original string instead so `{"items" ...}` works.
        (symbol? parsed)         s
        :else                    parsed))))

(defn parse-path-arg
  "Normalise the `path` MCP arg into a CLJS vector suitable for
   `get-in`. Returns `nil` when the path is absent. Returns `[]` for an
   explicit empty path (root). Unparsable strings fall through as
   strings — `get-in` will then treat them as map keys."
  [raw]
  (cond
    (nil? raw) nil
    (vector? raw) raw
    (sequential? raw) (vec raw)
    (array? raw) (mapv coerce-path-segment (js->clj raw))
    (string? raw)
    (let [trimmed (str/trim raw)]
      (cond
        (str/blank? trimmed) nil
        :else
        (try
          (let [parsed (cljs.reader/read-string trimmed)]
            (cond
              (vector? parsed)     parsed
              (sequential? parsed) (vec parsed)
              :else                [parsed]))
          (catch :default _
            ;; Unparseable; treat the whole string as a single segment.
            [trimmed]))))
    :else nil))

(defn parse-paths-arg
  "Normalise the plural `paths` MCP arg into a vector of path vectors —
  the batch-read shape `get-path` consumes (rf2-lbm21). Each element is
  itself a path (run through `parse-path-arg`), so a caller can read N
  app-db subtrees in ONE round-trip instead of N `get-path` calls.

  Accepted shapes from the MCP host:

    - EDN-encoded string  ⇒ `read-string`, then each element is a path.
                            `\"[[:a :b] [:c 0]]\"` ⇒ `[[:a :b] [:c 0]]`.
    - JS array            ⇒ each entry parsed as a path. Entries may be
                            EDN-string paths (`\"[:a :b]\"`) or JS arrays
                            of segment strings (`[\":a\" \":b\"]`).
    - CLJS sequential     ⇒ each entry parsed as a path.
    - nil / missing       ⇒ nil (no batch read; caller falls back to the
                            singular `:path` arg).

  Each path is coerced with the shared `parse-path-arg` so the segment
  vocabulary (EDN literals, bare-string map keys, integer indices) is
  identical to the singular surface — agents learn the shape once.
  Returns `nil` when the arg is absent / blank / not a collection so
  the caller can discriminate \"no batch requested\" from \"empty batch\"."
  [raw]
  (let [as-seq (cond
                 (nil? raw)        nil
                 ;; A JS array's entries may themselves be JS arrays of
                 ;; segment strings. `(vec raw)` is a SHALLOW conversion —
                 ;; the outer array becomes a CLJS vector while each inner
                 ;; element stays a raw JS array, so it reaches
                 ;; `parse-path-arg` in its raw shape and that fn's
                 ;; `(array? raw)` arm coerces the segments
                 ;; (`\":cart\"` ⇒ `:cart`). A deep `js->clj` here would
                 ;; pre-collapse the inner arrays to vectors-of-STRINGS,
                 ;; which `parse-path-arg`'s `(vector? raw)` pass-through
                 ;; arm would NOT coerce. `(vec #js [])` ⇒ `[]` (the
                 ;; explicit empty batch).
                 (array? raw)      (vec raw)
                 (sequential? raw) raw
                 (string? raw)
                 (let [trimmed (str/trim raw)]
                   (when-not (str/blank? trimmed)
                     (let [parsed (try (cljs.reader/read-string trimmed)
                                       (catch :default _ ::reader-fail))]
                       (when (and (not= ::reader-fail parsed)
                                  (sequential? parsed))
                         parsed))))
                 :else nil)]
    (when (some? as-seq)
      (mapv parse-path-arg as-seq))))

(defn parse-frames-arg
  "Normalise the `frames` MCP arg into the form the runtime expects.
   Returns one of three shapes the `snapshot-state` composer dispatches on:

     :app   — the APP frames only: every registered frame with the
              reserved `:rf/*` TOOL frames removed (rf2-3bu3d.6). This is
              the DEFAULT (absent / nil / unrecognised arg). It is what a
              first investigate-read wants: the app the operator is
              pairing against, NOT the Xray / Story / SSR tool-frame
              inspection state that would otherwise dominate (and
              frequently OVERFLOW) the snapshot. Tool frames are the
              common case (any Xray-instrumented app), so excluding them
              by default is the least-surprise scope.
     :all   — EVERY registered frame, including reserved `:rf/*` tool
              frames. The explicit opt-in: pass `frames \"all\"` (or the
              keyword `:all`) to see tool-frame state too.
     [..]   — an explicit vector of keyword frame-ids (a JS array of
              strings or a CLJS vector). Whatever the agent names — a
              specific tool frame like `[:rf/xray]` is honoured verbatim,
              so naming a tool frame is also a valid opt-in.

   Unrecognised input (anything other than the accepted shapes above —
   a typo'd `frames \"al\"`, a scalar `frames 42`) collapses to `:app`,
   the safe default scope: an agent gets the app frames back, not a
   silent overflow on tool-frame state nor an empty page."
  [raw]
  (cond
    (nil? raw) :app
    (or (= raw :all) (= raw "all")) :all
    (or (= raw :app) (= raw "app")) :app
    (array? raw)
    (->> (js->clj raw) (mapv ->frame-keyword))
    (sequential? raw)
    (mapv ->frame-keyword raw)
    :else :app))

(defn parse-include-arg
  "Normalise the `include` MCP arg into the slice vector the runtime
   expects. Filters to known slices; returns the full set when arg
   is nil / empty / all-unknown."
  [raw]
  (let [full [:app-db :sub-cache :machines :epochs :traces]
        coerce (fn [xs]
                 (->> xs
                      (map keyword)
                      (filter valid-slices)
                      vec))]
    (cond
      (nil? raw) full
      (array? raw)
      (let [v (coerce (js->clj raw))]
        (if (seq v) v full))
      (sequential? raw)
      (let [v (coerce raw)]
        (if (seq v) v full))
      :else full)))

(defn parse-mode-arg
  "Normalise the global `mode` MCP arg. Accepts strings (`\"summary\"`,
  `\"full\"`) or keywords. Defaults to `:summary` — the lazy-summary
  default per rf2-u2029. Unrecognised values default to `:summary`
  (budget-sensitive default).

  Delegates to `re-frame.mcp-base.args/parse-mode` (rf2-vw4sq)."
  [raw]
  (base-args/parse-mode raw :summary #{:summary :full}))

(defn parse-modes-arg
  "Normalise the per-slice `modes` MCP arg into a `{<slice-keyword>
  <mode-keyword>}` map. Accepts a JS object, a CLJS map, or nil.
  Unknown slices are dropped. Unknown mode values are dropped (the
  slice falls back to the global mode default). Slice keys may be
  bare strings (`\"app-db\"`), EDN-shaped strings (`\":app-db\"`),
  or keywords.

  Slice-key coercion delegates to `->frame-keyword` (which routes
  through `re-frame.mcp-base.args/fresh-keyword`); per-slice mode
  coercion delegates to `re-frame.mcp-base.args/parse-mode` with a
  sentinel default so unrecognised values can be detected and
  dropped rather than coerced to the global default (rf2-vw4sq)."
  [raw]
  (let [as-clj (cond
                 (nil? raw)   nil
                 (map? raw)   raw
                 ;; JS object from the MCP wire. `object?` is the
                 ;; cljs.core predicate — true for plain JS objects
                 ;; (`{}`-shaped wire input) and false for arrays /
                 ;; strings / booleans / numbers, which is exactly the
                 ;; discriminator we want here.
                 (object? raw) (try (js->clj raw) (catch :default _ nil))
                 :else nil)]
    (if-not (map? as-clj)
      {}
      (reduce-kv
        (fn [m k v]
          (let [k' (->frame-keyword k)
                v' (base-args/parse-mode v ::unknown #{:summary :full})]
            (if (and k'
                     (contains? valid-slices k')
                     (not= v' ::unknown))
              (assoc m k' v')
              m)))
        {} as-clj))))

(defn read-edn-arg
  "Read a single-value EDN MCP arg into the `[:ok parsed]` / `[:err reason]`
  shape the write/introspection tools branch on (rf2-jkake.19).

  Trims, then `read-string`s the value; an absent / blank value yields
  `[:err missing]`, an unreadable one `[:err invalid]`. `missing` /
  `invalid` are the per-tool reason keywords (e.g. `:missing-db` /
  `:invalid-db-edn`) so each tool's error envelope stays specific.

  Factors out the trim+read+sentinel core shared verbatim by
  `reset-frame-db` (`:db`), `restore-epoch` (`:epoch-id`), and
  `handler-meta` (`:id`). The richer `dispatch` / `dispatch-dry-run`
  event parsers are deliberately NOT routed through here — they layer a
  vector-shape contract + parsed-type classification on top, and their
  ns docstrings pin them as separately-evolving surfaces."
  [raw missing invalid]
  (let [trimmed (some-> raw str/trim)]
    (if (or (nil? trimmed) (str/blank? trimmed))
      [:err missing]
      (let [parsed (try (cljs.reader/read-string trimmed)
                        (catch :default _ ::reader-fail))]
        (if (= ::reader-fail parsed)
          [:err invalid]
          [:ok parsed])))))

(defn parse-filter-arg
  "MCP-side filter arg can be either a JS object or an EDN string. We
  accept both for ergonomic parity with the bash-shim chain (`pred`
  has been a JSON object there).

  Returns the tagged `[:ok m]` / `[:err :invalid-filter-edn]` shape
  (mirroring `read-edn-arg`, rf2-5kbkl) so the caller can surface a bad
  filter EDN as an honest `:ok? false` error rather than subscribing
  with a nonsense filter. The two success shapes:

    - `[:ok nil]`  — absent filter (no filtering).
    - `[:ok m]`    — a parsed EDN string, a CLJS map passed through, or
                     a JS object keywordised.

  The lone failure shape `[:err :invalid-filter-edn]` is returned when
  an EDN STRING fails to `read-string`. Pre-rf2-5kbkl this branch
  returned a `{:invalid-filter-edn raw}` MAP that flowed straight into
  the runtime `subscribe!` `:filter` slot — a typo'd filter silently
  became a nonsense filter that streamed the wrong (likely empty) event
  set with no corrective signal. The tag lets `subscribe-tool`
  short-circuit to the same honest-error envelope `:unknown-topic`
  already uses, rather than swallowing the parse failure into a
  broken-success path."
  [raw]
  (cond
    (nil? raw)        [:ok nil]
    (string? raw)     (let [parsed (try (cljs.reader/read-string raw)
                                        (catch :default _ ::reader-fail))]
                        (if (= ::reader-fail parsed)
                          [:err :invalid-filter-edn]
                          [:ok parsed]))
    (map? raw)        [:ok raw]
    :else             [:ok (js->clj raw :keywordize-keys true)]))
