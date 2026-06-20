(ns re-frame2-pair-mcp.tools.record
  "Tools: record + read-recording — first-class signal recorder.

  ## Why a recorder primitive

  Intermittent / human-in-the-loop bugs (a render-timing race only
  reproducible under real mouse input) need a recorder: install an
  observer, let the human interact, read back a change-log. `record`
  first-classes that move so it needn't be hand-built each session as a
  bespoke, footgun-prone `requestAnimationFrame` loop pushing focus-slot
  + DOM snapshots into a global (rAF timing, change-dedup, teardown all
  solved once here).

  ## The op pair

  - `record` installs a read-only observer over one or more SIGNALS with
    a STOP condition (N ms, N changes, or a predicate) and returns
    immediately with a `:recording-id`. The recording runs in the
    background while the human interacts; the runtime samples each signal
    once per animation frame, records each CHANGE with a timestamp, and
    tears itself down at the stop condition.
  - `read-recording` reads the accumulated change-log back. Pass
    `:drain true` for the live-watch idiom (poll → consume → repeat) or
    `:stop true` to read-and-close in one round-trip.

  The heavy lifting — rAF/dedup/teardown, the ring cap, the stop
  evaluation — lives in `re-frame2-pair.runtime` (`start-recording!` /
  `read-recording` / `stop-recording!` / `sample-signals`), so the footguns
  are solved once. This namespace owns only the wire shape: arg coercion,
  the data→predicate compile for `:stop {:pred ...}`, and forwarding the
  runtime envelope.

  ## Naming

  `record` is a bare-verb mega-op (a recorder spanning multiple signal
  kinds), catalogued in NAMING.md §The verb table; `read-recording` rides
  the `read-` prefix (diagnostic re-read of recorded state). Both are
  pinned by the verb-vocab conformance gate.

  ## Read-only by construction

  Every signal sampler in the runtime only READS (get-in / subscribe
  deref / querySelector text+attr / activeElement). A recording never
  dispatches, never mutates app-db, never writes the DOM. The descriptors
  carry the read-only annotation (with `:openWorldHint` — the recording
  reaches the browser runtime and has an observable side-effect on the
  runtime's recording registry, but no app-state mutation).

  ## Off-box-egress redaction

  Read-only addresses no-MUTATION, NOT sensitive-VALUE egress. The
  `{:app-db [path]}` / `{:sub [query-v]}` signals sample raw app-db-derived
  values that `read-recording` ships back to the model — a declared-
  sensitive slot would cross the AI/off-box boundary verbatim, exactly the
  leak class `get-path` / `read-sub` / `snapshot` / `list-subscriptions`
  close. `record` mirrors their posture: the `--allow-sensitive-reads`
  boot gate (`raw-state/raw-state-allowed?`) + the per-call
  `:include-sensitive` / `:elision` args resolve an elision-opts map that
  is threaded into the runtime's `start-recording!` as `:elide-opts`, so
  every `:app-db` / `:sub` sample is walked through
  `re-frame.core/elide-wire-value` server-side (app-side, where the
  per-frame elision registry lives) BEFORE it lands in the change-log.
  Gate OFF (the published default) forces `:include-sensitive false` ⇒
  declared-sensitive slots redact to `:rf/redacted`. The tool also issues
  `raw-state/signal-runtime!` before `start-recording!` so the runtime's
  own gate is flipped to the OFF posture (a belt-and-suspenders backstop
  the background sampler tick re-consults each frame)."
  (:require [cljs.reader]
            [clojure.string :as str]
            [re-frame2-pair-mcp.tools.args :as args]
            [re-frame2-pair-mcp.tools.eval-form :as ef]
            [re-frame2-pair-mcp.tools.wire :as wire]
            [re-frame2-pair-mcp.tools.probe :as probe]
            [re-frame2-pair-mcp.tools.elision :as elision]
            [re-frame2-pair-mcp.tools.raw-state :as raw-state]))

;; ---------------------------------------------------------------------------
;; Signal-arg parsing — shared with watch-until.
;; ---------------------------------------------------------------------------

(defn parse-signals-arg
  "Normalise the `signals` MCP arg into a vector of signal maps the
  runtime understands. Accepts:

    - a JS array / CLJS vector of signal objects, OR
    - an EDN-encoded string (`\"[{:focus true} {:dom \\\"#c\\\"}]\"`).

  Each signal map is keywordised. Returns `nil` when the arg is absent /
  blank / not a collection so the caller can emit the `:no-signals`
  refusal. Recognised signal shapes (validated runtime-side):
  `{:app-db [path]}`, `{:sub [query-v]}`, `{:dom \"sel\" :attr \"name\"}`,
  `{:focus true}`."
  [raw]
  (cond
    (nil? raw) nil

    (array? raw)
    (let [v (js->clj raw :keywordize-keys true)]
      (when (sequential? v) (vec v)))

    (vector? raw) raw
    (sequential? raw) (vec raw)

    (map? raw) [raw] ;; a single bare signal map — sugar for [signal]

    (string? raw)
    (let [trimmed (str/trim raw)]
      (when-not (str/blank? trimmed)
        (let [parsed (try (cljs.reader/read-string trimmed)
                          (catch :default _ ::reader-fail))]
          (cond
            (= ::reader-fail parsed) nil
            (map? parsed)            [parsed]
            (sequential? parsed)     (vec parsed)
            :else                    nil))))

    (object? raw)
    (let [m (try (js->clj raw :keywordize-keys true) (catch :default _ nil))]
      (when (map? m) [m]))

    :else nil))

(defn parse-stop-arg
  "Normalise the `stop` MCP arg into the runtime stop-condition map.
  Accepts a JS object, a CLJS map, or an EDN string. Recognised keys:
  `:ms` (integer), `:changes` (integer), `:pred` (a filter map — compiled
  into the runtime predicate below). Unknown keys are dropped.

  Returns the tagged `[:ok m]` / `[:err :invalid-stop-edn]` shape
  (mirroring `parse-filter-arg`) so the caller can surface a malformed
  `stop` EDN as an honest `:ok? false` error rather than recording with
  the silent default window. The success shapes:

    - `[:ok {}]`   — absent `stop` (runtime applies its default
                     wall-clock window).
    - `[:ok m]`    — a parsed EDN string, a CLJS map passed through, or
                     a JS object keywordised — with only the recognised
                     keys retained.

  The failure shape `[:err :invalid-stop-edn]` is returned when a `stop`
  EDN STRING fails to `read-string`, OR reads cleanly but is not a map
  (e.g. `\"[:ms 5000]\"`). Surfacing the error rather than silently
  collapsing to `{}` means a typo'd `stop {:ms 5000}` (wanting a 5s
  window) gets a corrective signal instead of the default window. The
  tag lets `record-tool` short-circuit to the same honest-error envelope
  `:no-signals` already uses, before touching the nREPL socket."
  [raw]
  (cond
    (nil? raw)    [:ok {}]
    (map? raw)    [:ok (select-keys raw [:ms :changes :pred])]
    (object? raw) (let [m (try (js->clj raw :keywordize-keys true)
                               (catch :default _ ::reader-fail))]
                    (if (map? m)
                      [:ok (select-keys m [:ms :changes :pred])]
                      [:err :invalid-stop-edn]))
    (string? raw) (let [parsed (try (cljs.reader/read-string raw)
                                    (catch :default _ ::reader-fail))]
                    (if (map? parsed)
                      [:ok (select-keys parsed [:ms :changes :pred])]
                      [:err :invalid-stop-edn]))
    :else         [:err :invalid-stop-edn]))

;; ---------------------------------------------------------------------------
;; Stop / watch predicate compile (data → runtime fn).
;; ---------------------------------------------------------------------------
;;
;; The MCP surface takes a DATA predicate (EDN), never host source — the
;; same injection-closing posture `dispatch` / `replace-app-db` adopt.
;; We compile the data predicate into a CLJS fn server-side-
;; emitted-into-the-form so the runtime's `start-recording!` / `watch-until`
;; receives a real `:pred-fn`. The predicate map is matched against the
;; positional sample map `{<signal-index> <value>}`:
;;
;;   {:signal 0 :equals <v>}        — signal 0's value = <v>
;;   {:signal 0 :changed true}      — signal 0 is non-nil (took any value)
;;   {:signal 0 :path [...] :equals <v>}  — (get-in (sample 0) path) = <v>
;;   {:signal 0 :contains <substr>} — (str (sample 0)) includes <substr>
;;
;; The compiled form is pure data-comparison — no arbitrary code crosses
;; the wire. `pred-source` returns the CLJS source string for the fn; the
;; runtime form splices it into the `:pred-fn` slot.

(defn pred-source
  "Build the CLJS source for the `:pred-fn` over a data predicate map, or
  nil when no `:pred` was supplied. The fn takes the positional sample map
  and returns a boolean — pure value comparison, no host evaluation of
  caller input. Returns the source string (a `(fn [sample] ...)` literal)
  or nil.

  Every caller-supplied comparand rides inside `(quote ...)` so it is
  always DATA, never host source: a hostile `:equals (js/alert \"x\")`
  becomes `(quote (js/alert \"x\"))` — compared as a literal list, never
  evaluated. Same injection-closing posture as `dispatch` /
  `replace-app-db`. The `:path` vector and the `:signal` index
  are quoted / integer-coerced for the same reason."
  [pred]
  (when (map? pred)
    (let [{:keys [signal path equals changed contains]} pred
          idx (if (integer? signal) signal 0)
          val-src (if (seq path)
                    (str "(get-in (get sample " idx ") (quote " (pr-str (vec path)) "))")
                    (str "(get sample " idx ")"))]
      (cond
        (some? changed)
        (str "(fn [sample] (some? " val-src "))")

        (contains? pred :equals)
        (str "(fn [sample] (= " val-src " (quote " (pr-str equals) ")))")

        (some? contains)
        (str "(fn [sample] (clojure.string/includes? (str " val-src ") " (pr-str (str contains)) "))")

        :else
        ;; A bare {:signal n} with no comparator means "any non-nil value
        ;; of that signal" — the most common watch-until intent.
        (str "(fn [sample] (some? " val-src "))")))))

;; ---------------------------------------------------------------------------
;; record — start a recording.
;; ---------------------------------------------------------------------------

(defn- stop-map-src
  "Build the CLJS source for the runtime `:stop` map. The data keys
  (`:ms` / `:changes`) ride as `pr-str`'d EDN literals; the `:pred` data
  predicate is compiled into a `:pred-fn` raw-source slot via
  `pred-source` (no host source from the caller crosses the wire — only
  the value-comparison fn this server synthesised). Built as an explicit
  source string because the `:pred-fn` value is live CLJS source, not
  EDN data — `eval-form`'s `pr-str` arg path would render the fn source
  as a string literal."
  [stop]
  (let [pred-src (pred-source (:pred stop))
        pairs    (cond-> []
                   (some? (:ms stop))      (conj (str ":ms " (pr-str (:ms stop))))
                   (some? (:changes stop)) (conj (str ":changes " (pr-str (:changes stop))))
                   pred-src                (conj (str ":pred-fn " pred-src)))]
    (str "{" (str/join " " pairs) "}")))

(defn- start-recording-form
  "Build the runtime `(start-recording! opts)` eval form. The opts map is
  emitted as explicit source: `:signals` / `:frame` / `:max-entries` are
  EDN data literals; `:stop` is built by `stop-map-src` (its `:pred-fn`
  slot carries synthesised CLJS source, which can't ride the EDN-literal
  arg path).

  `elision-opts` is the rendered `elision-opts-edn` walker
  map (the `--allow-sensitive-reads` gate + per-call `:include-sensitive`
  posture). It rides as `:elide-opts` so the runtime's sampler elides
  each `:app-db` / `:sub` value for off-box egress before it lands in the
  change-log. The map is a plain EDN literal (no synthesised source), so
  it inlines verbatim."
  [signals stop frame max-entries elision-opts]
  (let [opts-pairs (cond-> [(str ":signals " (pr-str (vec signals)))
                            (str ":stop " (stop-map-src stop))
                            (str ":elide-opts " elision-opts)]
                     frame       (conj (str ":frame " (pr-str frame)))
                     max-entries (conj (str ":max-entries " (pr-str max-entries))))]
    (ef/emit
      (ef/rt-call 'start-recording!
                  (ef/rt-raw (str "{" (str/join " " opts-pairs) "}"))))))

(defn record-tool
  "MCP `record` handler. Installs a read-only recorder over `signals`
  with a `stop` condition and returns a `:recording-id` immediately. See
  the ns docstring for the wire contract."
  [conn raw-args]
  (let [build-id    (wire/arg-build conn raw-args)
        frame       (some-> (wire/arg raw-args :frame) args/->frame-keyword)
        signals     (parse-signals-arg (wire/arg raw-args :signals))
        ;; `parse-stop-arg` returns the tagged
        ;; `[:ok m]` / `[:err :invalid-stop-edn]` shape (mirroring
        ;; `parse-filter-arg`). A malformed `stop` EDN short-circuits to
        ;; an honest `:ok? false` envelope below rather than silently
        ;; collapsing to `{}` (the default wall-clock window) — a typo'd
        ;; `stop {:ms 5000}` would otherwise record with the wrong stop
        ;; condition and no corrective signal.
        [stop-tag stop] (parse-stop-arg (wire/arg raw-args :stop))
        max-entries (let [m (wire/arg raw-args :max-entries)]
                      (when (and (number? m) (pos? m)) (long m)))
        ;; The `:app-db` / `:sub` samples this recorder ships back via
        ;; `read-recording` must be elided for off-box egress. Same gate
        ;; posture as snapshot / get-path / trace-window: the
        ;; `--allow-sensitive-reads` boot gate forces `:include-sensitive
        ;; false` + `:elision true` when OFF (the published default);
        ;; gate ON honours the per-call args.
        elision?    (if (raw-state/raw-state-allowed?)
                      (args/parse-bool-arg raw-args :elision)
                      true)
        incl?       (if (raw-state/raw-state-allowed?)
                      (args/parse-bool-arg raw-args :include-sensitive)
                      false)
        ;; Polarity — MCP `elision` true = emit markers =
        ;; `:include-large?` false, hence `(not elision?)`.
        elision-opts (elision/elision-opts-edn (not elision?) incl?)]
    (cond
      (or (nil? signals) (empty? signals))
      (js/Promise.resolve
        (wire/err-text
          {:ok? false :reason :no-signals
           :hint (str "usage: record {signals '[{:focus true} {:dom \"#count\"} "
                      "{:app-db [:cart :items]}]' [stop {:ms 30000}] [frame :rf/default]}")}))

      ;; The `stop` EDN failed to parse (or read clean but non-map).
      ;; Surface an honest `:ok? false` error (same one-cond-branch shape
      ;; as `:no-signals` above) instead of recording with the silent
      ;; default window. No nREPL socket touched.
      (= :err stop-tag)
      (js/Promise.resolve
        (wire/err-text
          {:ok? false :reason :invalid-stop-edn
           :given (wire/arg raw-args :stop)
           :hint  (str "stop must be an EDN-readable map (or a JSON object), "
                       "e.g. \"{:ms 5000}\" / \"{:changes 10}\" / "
                       "\"{:pred {:signal 0 :equals :done}}\".")}))

      :else
      (let [form (start-recording-form signals stop frame max-entries elision-opts)]
        ;; The signalled prelude inserts `signal-runtime!` between
        ;; `ensure-runtime!` and the eval (the raw-state tap gate) so the
        ;; runtime is in the OFF posture before the background sampler
        ;; ticks. The plain `eval-after-runtime!` skips the signal step,
        ;; so this uses the `-signalled!` sibling.
        (probe/eval-after-runtime-signalled!
          conn build-id form :record-failed
          (fn [envelope] (wire/ok-text envelope)))))))

;; ---------------------------------------------------------------------------
;; read-recording — read back the change-log.
;; ---------------------------------------------------------------------------

(defn read-recording-tool
  "MCP `read-recording` handler. Reads back a recording's change-log,
  optionally draining (`drain true`) or closing it (`stop true`). See the
  ns docstring for the wire contract."
  [conn raw-args]
  (let [build-id     (wire/arg-build conn raw-args)
        recording-id (wire/arg raw-args :recording-id)
        drain?       (args/parse-bool-arg raw-args :drain)
        stop?        (args/parse-bool-arg raw-args :stop)]
    (cond
      (or (nil? recording-id) (and (string? recording-id) (str/blank? recording-id)))
      (js/Promise.resolve
        (wire/err-text
          {:ok? false :reason :missing-recording-id
           :hint "usage: read-recording {recording-id \"rec-<uuid>\" [drain true] [stop true]}"}))

      :else
      (let [form (ef/emit
                   (ef/rt-call 'read-recording
                               (str recording-id)
                               {:drain drain? :stop stop?}))]
        (probe/eval-after-runtime!
          conn build-id form :read-recording-failed
          (fn [envelope] (wire/ok-text envelope)))))))
