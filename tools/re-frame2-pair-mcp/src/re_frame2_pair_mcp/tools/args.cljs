(ns re-frame2-pair-mcp.tools.args
  "Argument-coercion helpers for the snapshot / get-path / subscribe
  family — path vectors, frame ids, slice include lists, summary modes,
  per-slice mode maps, and the streaming filter map.

  Path-arg parsing: two tools take a `:path` argument:
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
;; Boolean-arg table.
;;
;; The four boolean MCP args shared across re-frame2-pair-mcp tools each have one
;; load-bearing knob: their default posture. Their accept-shapes —
;; `true`/`false`, `"true"`/`"false"`/`"yes"`/`"no"`/`"1"`/`"0"` (case-
;; insensitive), `:true`/`:false`, unrecognised ⇒ default — are
;; identical and come from `re-frame.mcp-base.args/parse-boolean`.
;; This table is the single source of truth for both the default posture
;; AND the accept-shape contract: every boolean arg shares one parser, so
;; an agent that learns `:dedup "yes"` knows `:cache "yes"` works too.
;;
;; Defaults:
;;
;;   :dedup              true   — structural dedup wins shrink-by-default
;;   :elision            true   — size-elision wins shrink-by-default
;;   :cache              false  — per-call cache is opt-in until agent
;;                                hosts have been taught the marker
;;   :include-sensitive  false  — spec/009 MUST default-suppress.
;;                                The wire-key has no trailing `?`:
;;                                Anthropic's tool-input-schema regex
;;                                `^[a-zA-Z0-9_.-]{1,64}$` rejects `?`.
;;                                The predicate FUNCTION name retains `?`
;;                                (idiom on predicates, not on data keys).
;;
;; Callers reach in via `parse-bool-arg`
;; (`(args/parse-bool-arg raw-args :dedup)`). The dispatcher and per-
;; tool bodies thread the raw JS args object through; nil-safety on
;; the args object is centralised here.

(def bool-args
  "Cross-tool boolean MCP args + their default postures."
  {:dedup             {:default true}
   :elision           {:default true}
   :cache             {:default false}
   :include-sensitive {:default false}
   ;; dispatch-dry-run's :would-fire-effects[*].args are
   ;; RAW fx-handler arguments (HTTP request bodies, dispatched event
   ;; vectors, payment maps). They are NOT rooted at the frame's app-db,
   ;; so the schema-path-keyed `elide-wire-value` walker cannot prove them
   ;; safe — the same leak class as an epoch record's :effects[].args,
   ;; which `projected-record` fails closed (EP-0015 §13). Off-box
   ;; egress therefore FAILS CLOSED: :args redacts to :rf/redacted by
   ;; default. This knob is the trusted-local opt-in that keeps the raw
   ;; args, honoured only under --allow-sensitive-reads (same gate as
   ;; :include-sensitive). The wire-key has no trailing `?`.
   :include-fx-args   {:default false}
   ;; list-subscriptions toggles its per-entry shape.
   ;; Default false: only the query-vectors ride the wire (the cheap
   ;; "what's subscribed" read); true also ships :value + :ref-count.
   :include-values    {:default false}
   ;; read-recording's two toggles. :drain consumes the
   ;; buffered change-entries (the live-watch poll→consume→repeat idiom);
   ;; :stop tears the recording down after reading (read-and-close).
   ;; Both default false: a bare read is non-destructive.
   :drain             {:default false}
   :stop              {:default false}})

(defn parse-bool-arg
  "Resolve a boolean MCP arg by name. Returns the per-arg default from
  `bool-args` when the slot is absent / nil / unrecognised; delegates
  recognised-value parsing to
  `re-frame.mcp-base.args/parse-boolean` — the cross-MCP
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

   Delegates to `re-frame.mcp-base.args/fresh-keyword` so
   the slice-key / frame-key coercion is single-sourced across the
   re-frame2-pair-mcp wire surface — same helper underpins both
   `parse-frames-arg` and the per-slice-mode key coercion in
   `parse-modes-arg`. On CLJS keywords are not interned in the JVM
   never-shrinking-table sense, so the intern-DoS concern that gates
   `fresh-keyword` on the JVM side doesn't apply here; the call is
   straight string-to-keyword coercion."
  [x]
  (base-args/fresh-keyword x))

(defn parse-fx-overrides
  "Coerce the `fx-overrides` MCP arg (a JSON object) into the CLJS map
  core's `:fx-overrides` seam expects (Spec 002 §Per-frame and per-call
  overrides), returning `[:ok m]` or `[:err msg]`.

  Over the JSON-MCP wire a caller can only send a JSON object, so BOTH
  the override KEY and the override VALUE arrive as strings (`{\":http\":
  \":stub-http\"}` ⇒ key `\":http\"`, value `\":stub-http\"`). The naive
  `(js->clj o :keywordize-keys true)` is doubly wrong here:
  it leaves the value the string `\":stub-http\"`, AND it mints a
  MALFORMED key by calling `(keyword \":http\")` ⇒ `::http` (namespace
  literally `\"\"`), which matches no registered fx-id — the same colon-
  literal footgun `->frame-keyword` exists to avoid. Core's
  `resolve-fx-with-overrides` honours only KEYWORD redirect targets, fn
  values, and nil; a string value hits the `:else` branch and SILENTLY
  falls through to the original fx — the real http/navigate/persist
  effect fires despite the recipe saying it was stubbed, and for
  `dispatch-dry-run` a string override can defeat the no-fx-execute
  guarantee.

  So this is the single AI-facing wire shape: both the fx-id key and the
  override target are colon-tolerant strings (`\":http\"` / `\":stub-http\"`),
  coerced here via `fresh-keyword` (the shared colon-stripping path) to
  the keywords `:http` / `:stub-http`. `null`/absent value ⇒ nil (the
  documented no-op placeholder). Anything else for the target — a bare
  non-colon string, a number, a nested object/array — is REJECTED with a
  structured error rather than passed through to fall silently through to
  the original fx."
  [o]
  (if (or (nil? o) (undefined? o))
    [:ok nil]
    ;; Use string-keyed `js->clj` (NOT :keywordize-keys, which mints the
    ;; malformed `::http` key) and coerce both halves ourselves.
    (let [m (js->clj o)]
      (if-not (map? m)
        [:err {:ok?    false
               :reason :rf.error/invalid-fx-overrides
               :hint   (str "fx-overrides must be an object mapping fx-id to a "
                            "colon-prefixed stub id, e.g. {\":http\": \":stub-http\"}.")}]
        (reduce-kv
          (fn [acc k v]
            (if (= :err (first acc))
              acc
              (let [fx-key  (base-args/fresh-keyword k)
                    coerced (cond
                              (nil? v)     nil
                              (and (string? v) (str/starts-with? v ":")) (base-args/fresh-keyword v)
                              :else        ::reject)]
                (if (= ::reject coerced)
                  [:err {:ok?    false
                         :reason :rf.error/invalid-fx-overrides
                         :target fx-key
                         :value  v
                         :hint   (str "fx-overrides target for `" fx-key "` must be a colon-prefixed "
                                      "stub id string (e.g. \":stub-http\") or null, got "
                                      (pr-str v) ". A bare string or non-string value would "
                                      "silently fall through to the real fx instead of stubbing it.")}]
                  [:ok (assoc (second acc) fx-key coerced)]))))
          [:ok {}]
          m)))))

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
  the batch-read shape `get-path` consumes. Each element is
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
              reserved `:rf/*` TOOL frames removed. This is
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
  default. Unrecognised values default to `:summary`
  (budget-sensitive default).

  Delegates to `re-frame.mcp-base.args/parse-mode`."
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
  dropped rather than coerced to the global default."
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
  shape the write/introspection tools branch on.

  Trims, then `read-string`s the value; an absent / blank value yields
  `[:err missing]`, an unreadable one `[:err invalid]`. `missing` /
  `invalid` are the per-tool reason keywords (e.g. `:missing-db` /
  `:invalid-db-edn`) so each tool's error envelope stays specific.

  Factors out the trim+read+sentinel core shared verbatim by
  `replace-app-db` (`:db`), `restore-epoch` (`:epoch-id`), and
  `handler-meta` (`:id`). The richer `dispatch` / `dispatch-dry-run`
  event parsers are deliberately NOT routed through here — they layer a
  vector-shape contract + parsed-type classification on top, and their
  ns docstrings pin them as separate surfaces."
  [raw missing invalid]
  (let [trimmed (some-> raw str/trim)]
    (if (or (nil? trimmed) (str/blank? trimmed))
      [:err missing]
      (let [parsed (try (cljs.reader/read-string trimmed)
                        (catch :default _ ::reader-fail))]
        (if (= ::reader-fail parsed)
          [:err invalid]
          [:ok parsed])))))

;; ---------------------------------------------------------------------------
;; Numeric subscribe-control args.
;;
;; `subscribe` forwards five integer knobs straight from the MCP args to
;; the runtime queue budget / poll loop / termination caps. The
;; descriptor schemas said `type: integer` with NO lower bound, so a
;; malformed or LLM-generated value reached the runtime un-vetted:
;;
;;   - a negative `max-buffered-events` / `max-buffered-bytes` is truthy
;;     and collapses the queue budget so EVERY event evicts (an apparently
;;     healthy but永-empty probe);
;;   - a zero / negative `poll-ms` collapses the poll loop into a
;;     near-tight spin, burning local CPU + nREPL bandwidth;
;;   - a negative termination cap (`max-ms` / `max-events`) is non-zero so
;;     the `(pos? …)` guard fires, but `>=` against a negative bound is
;;     immediately true — silently disabling the intended bound or, for
;;     `max-ms`, scheduling a `setTimeout` with a negative delay.
;;
;; Two positivity contracts cover the five knobs:
;;   - buffer caps + `poll-ms` must be POSITIVE integers (>= 1) — a zero
;;     budget / cadence is nonsensical.
;;   - `max-ms` / `max-events` must be NON-NEGATIVE integers (>= 0) where
;;     0 means "unbounded" (the documented sentinel).
;;
;; Both return the tagged `[:ok n|nil]` / `[:err {…}]` shape so
;; `subscribe-tool` short-circuits a bad value to an honest `:ok? false`
;; envelope (the same one-cond-branch pattern as `:unknown-topic` /
;; `:invalid-filter-edn`) WITHOUT touching the nREPL socket. An ABSENT
;; arg is `[:ok nil]` — the caller falls back to the runtime / descriptor
;; default.

(defn- coerce-int
  "Coerce an MCP numeric arg to an integer, or `::bad` if it isn't a
  whole number. Accepts a JS/CLJS number (must be integral) or a numeric
  string. Fractional numbers and non-numeric junk are `::bad`.

  Cross-runtime safe-integer guard: the numeric core goes through
  `base-args/coerce-finite-long`, which returns `nil` for `##NaN` /
  `##Inf` / `##-Inf` and for any finite magnitude outside the JS
  safe-integer window `[-(2^53−1), 2^53−1]`. A JS
  `Number.isInteger` value above that ceiling (e.g. `9007199254740993`,
  representable as a JS integral double but NOT round-trippable through
  a CLJS `long` / a JVM long without loss) is therefore `::bad` here —
  the same value `mcp-base` rejects, so the two runtimes agree. The
  tagged `[:ok]`/`[:err]` wrappers (`parse-positive-int-arg` etc.) stay
  local; only the numeric core is shared.

  A whole numeric below the safe-integer ceiling but outside the window
  on the string arm is caught by routing the parsed string back through
  the SAME guard. Fractional numbers are still `::bad` (the integer
  knobs reject non-whole values; we do NOT adopt mcp-base's benign-floor
  posture here)."
  [raw]
  (cond
    (and (number? raw) (js/Number.isInteger raw))
    (if-let [n (base-args/coerce-finite-long raw)] n ::bad)

    (number? raw)
    ::bad

    (string? raw)
    (let [parsed (parse-long (str/trim raw))]
      (if (and parsed (base-args/coerce-finite-long parsed)) parsed ::bad))

    :else
    ::bad))

(defn- err-int
  "Build the `[:err {…}]` envelope for an invalid numeric arg. `arg-name`
  is a plain string (the wire key)."
  [arg-name raw bound-hint]
  [:err {:ok?    false
         :reason :invalid-numeric-arg
         :arg    arg-name
         :given  raw
         :hint   (str arg-name " must be " bound-hint
                      " (got " (pr-str raw) ").")}])

(defn parse-positive-int-arg
  "Validate an OPTIONAL positive-integer MCP arg value.
  `raw` is the already-extracted arg value (via `wire/arg`); `arg-name`
  is its wire key for the error message. Returns `[:ok nil]` when absent
  (`raw` nil), `[:ok n]` for an integer `>= 1`, and `[:err {…}]` (with
  `:reason :invalid-numeric-arg`) for a zero / negative / fractional /
  non-numeric value."
  [arg-name raw]
  (cond
    (nil? raw) [:ok nil]
    :else
    (let [n (coerce-int raw)]
      (if (and (not= ::bad n) (pos? n))
        [:ok n]
        (err-int arg-name raw "a positive integer (>= 1)")))))

(defn parse-non-negative-int-arg
  "Validate an OPTIONAL non-negative-integer MCP arg value
  where 0 is the documented unbounded sentinel. `raw` is the already-
  extracted arg value; `arg-name` is its wire key. Returns `[:ok nil]`
  when absent, `[:ok n]` for an integer `>= 0`, and `[:err {…}]` for a
  negative / fractional / non-numeric value."
  [arg-name raw]
  (cond
    (nil? raw) [:ok nil]
    :else
    (let [n (coerce-int raw)]
      (if (and (not= ::bad n) (not (neg? n)))
        [:ok n]
        (err-int arg-name raw "a non-negative integer (>= 0; 0 = unbounded)")))))

;; ---------------------------------------------------------------------------
;; Timeout / wait millisecond args.
;;
;; Three NON-streaming tools thread a millisecond deadline straight from
;; the MCP args into an `(>= elapsed deadline)` poll comparison with NO
;; coercion or lower-bound check:
;;
;;   - `tail-build`  → `:wait-ms`   (probe-change poll loop);
;;   - `eval-cljs :await` / `dispatch :await-render` → `:timeout-ms`
;;     (both thread into `await-promise/poll-mailbox!`'s
;;     `(>= elapsed timeout-ms)`).
;;
;; A non-numeric value (`"never"`) makes the elapsed comparison
;; (`(>= number NaN)`) NEVER true, so a stuck probe / pending mailbox
;; polls FOREVER — unbounded background nREPL traffic, the exact failure
;; the timeout knob exists to prevent. A zero / negative value times out
;; immediately (or, for `tail-build`, schedules a negative `setTimeout`
;; delay). These deadlines must be POSITIVE millisecond integers (>= 1);
;; 0 is not a meaningful "wait/await for no time" sentinel here (unlike
;; subscribe's `max-ms`, where 0 means UNBOUNDED — the opposite polarity).
;; So this reuses the POSITIVE-int contract, not the non-negative one.
;;
;; Returns the same tagged `[:ok n|nil]` / `[:err {…}]` shape as the
;; subscribe validators so each tool short-circuits a bad value to an
;; honest `:ok? false` / isError envelope (the masterpiece-CORRECTNESS
;; posture: an unbounded-poll arg is an agent error worth telling the
;; agent about, not a value to silently paper over). An ABSENT arg is
;; `[:ok nil]` — the caller falls back to its documented default.

(defn parse-timeout-arg
  "Validate an OPTIONAL positive-millisecond MCP timeout/wait arg value.
  A thin alias over `parse-positive-int-arg` — the
  millisecond deadline knobs (`tail-build :wait-ms`, `eval-cljs` /
  `dispatch` `:timeout-ms`) share the positive-integer contract (>= 1).
  Named distinctly so the call sites read as a timeout check and a future
  divergence in the timeout contract lands here, not on the shared
  positive-int helper. Returns `[:ok nil]` when absent, `[:ok n]` for an
  integer `>= 1`, and `[:err {…}]` (`:reason :invalid-numeric-arg`) for a
  zero / negative / fractional / non-numeric value — the value that would
  otherwise spin or collapse the poll loop."
  [arg-name raw]
  (parse-positive-int-arg arg-name raw))

;; ---------------------------------------------------------------------------
;; Recordable coeffects (EP-0010 + EP-0017 agent-replay-determinism).
;;
;; `:rf.cofx` is an optional key of the PUBLIC `:rf/dispatch-opts` schema
;; (spec/Spec-Schemas.md §:rf.cofx) — a flat map of recordable coeffects
;; keyed by owner-qualified keywords, the framework-required one being
;; `:rf/time-ms`. The router preserves a caller-supplied map VERBATIM and
;; fills only the framework-required `:rf/time-ms` when absent — so an AI
;; agent that scripts a `:rf.cofx` map gets a REPRODUCIBLE resulting state (a
;; fixed `:rf/time-ms`, named owner-qualified recordable facts). This is the
;; agent-replay-determinism affordance EP-0010 calls for from the
;; tool-dispatch helpers.
;;
;; The MCP wire shape is an EDN STRING (the same data-not-source posture as
;; the `event` arg) — the agent passes `cofx
;; "{:rf/time-ms 1781078400123}"` and we parse + shape-check it. The schema
;; requires `:rf/time-ms` to be an integer when present; we surface a bad
;; `:rf/time-ms` as a structured error rather than threading a value that the
;; runtime's `:rf/dispatch-opts` validation would reject deep inside the
;; dispatch eval. Absent ⇒ `[:ok nil]` (the caller omits the key and the
;; runtime stamps `:rf/time-ms` itself, the ordinary live-dispatch path).

(defn parse-cofx
  "Parse the OPTIONAL `cofx` MCP arg (EP-0017) into the CLJS map
  threaded into the dispatch opts under `:rf.cofx`. Returns `[:ok nil]` when
  absent, `[:ok m]` for a well-shaped map, or `[:err {…}]` for a malformed
  one.

  The arg is an EDN STRING (data, not host source — same gate as `event`).
  Shape contract, mirroring the `:rf.cofx` schema:

    - MUST read as a map (a vector / scalar / unreadable string is rejected
      with `:reason :invalid-cofx`).
    - `:rf/time-ms`, when present, MUST be an integer (the framework's lone
      provided fact; a non-integer is rejected with
      `:reason :invalid-cofx-time-ms`).

  Other leaves (owner-qualified app / subsystem recordable facts) ride
  through verbatim — the runtime's `:rf/dispatch-opts` validation is the
  deeper gate; this is the cheap wire-shape check so an obvious typo gets a
  corrective hint without a round-trip."
  [raw]
  (let [trimmed (some-> raw str/trim)]
    (cond
      (or (nil? trimmed) (str/blank? trimmed))
      [:ok nil]

      :else
      (let [parsed (try (cljs.reader/read-string trimmed)
                        (catch :default _ ::reader-fail))]
        (cond
          (= ::reader-fail parsed)
          [:err {:ok?    false
                 :reason :invalid-cofx
                 :given  raw
                 :hint   (str "cofx must be an EDN-readable map of recordable coeffects, "
                              "e.g. \"{:rf/time-ms 1781078400123}\" or "
                              "\"{:rf/time-ms 1781078400123 :counter/delta 4}\".")}]

          (not (map? parsed))
          [:err {:ok?    false
                 :reason :invalid-cofx
                 :given  raw
                 :hint   (str "cofx must be a MAP (got "
                              (cond (vector? parsed) "a vector"
                                    (keyword? parsed) "a keyword"
                                    (sequential? parsed) "a list"
                                    :else "a scalar")
                              "), e.g. \"{:rf/time-ms 1781078400123}\".")}]

          (and (contains? parsed :rf/time-ms) (not (int? (:rf/time-ms parsed))))
          [:err {:ok?    false
                 :reason :invalid-cofx-time-ms
                 :time-ms (:rf/time-ms parsed)
                 :hint   (str "cofx :rf/time-ms must be an integer (epoch milliseconds), got "
                              (pr-str (:rf/time-ms parsed)) ".")}]

          :else
          [:ok parsed])))))

(defn parse-filter-arg
  "MCP-side filter arg can be either a JS object or an EDN string. We
  accept both for ergonomic parity with the bash-shim chain (`pred` is
  a JSON object there).

  Returns the tagged `[:ok m]` / `[:err :invalid-filter-edn]` shape
  (mirroring `read-edn-arg`) so the caller can surface a bad
  filter EDN as an honest `:ok? false` error rather than subscribing
  with a nonsense filter. The two success shapes:

    - `[:ok nil]`  — absent filter (no filtering).
    - `[:ok m]`    — a parsed EDN string, a CLJS map passed through, or
                     a JS object keywordised.

  The lone failure shape `[:err :invalid-filter-edn]` is returned when
  an EDN STRING fails to `read-string`. The tag lets `subscribe-tool`
  short-circuit to the same honest-error envelope `:unknown-topic`
  already uses, rather than swallowing the parse failure into a
  broken-success path: a typo'd filter that flowed straight into the
  runtime `subscribe!` `:filter` slot would silently become a nonsense
  filter streaming the wrong (likely empty) event set with no
  corrective signal."
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
