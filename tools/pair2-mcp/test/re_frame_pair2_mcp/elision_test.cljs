(ns re-frame-pair2-mcp.elision-test
  "Unit tests for the size-elision wire-marker integration (rf2-urjnc).

  Per `tools/pair2-mcp/spec/Principles.md` §\"Size-elision wire markers\",
  the `snapshot` and `get-path` tools call
  `re-frame.core/elide-wire-value` (rf2-v9tw2) server-side inside the
  CLJS eval form before any payload crosses the wire. A declared
  `:large?` slot or an over-threshold leaf is substituted with a
  `{:rf.size/large-elided {:path [...] :handle [:rf.elision/at <path>]
  ...}}` marker; the agent re-fetches via `get-path` with the handle's
  path.

  Tests pin `elision-opts-edn` directly from
  `re-frame-pair2-mcp.tools.elision`. The downstream eval-form
  composers (`build-snapshot-form` / `build-get-path-form`) remain
  local fixtures because their source counterparts live inlined in
  the per-tool namespaces (`tools.snapshot` / `tools.get-path`) and
  aren't surfaced as standalone public fns.

  `:elision` MCP-arg normalisation lives on the shared table-driven
  parser (`re-frame-pair2-mcp.tools.args/parse-bool-arg`, rf2-c4fmh);
  see `re-frame-pair2-mcp.args-test` for the coverage.

  Live end-to-end coverage runs against a real shadow-cljs build via
  the existing `test/stdio-roundtrip.js` harness — that's where the
  walker actually fires and we verify the marker comes back as EDN.
  The CLJS layer here just pins the wiring."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [cljs.reader]
            [re-frame-pair2-mcp.tools.elision :as elision]
            [re-frame-pair2-mcp.tools.wire-pipeline :as wp]))

;; ---------------------------------------------------------------------------
;; elision-opts-edn — EDN-render the walker's opts map for inlining.
;; ---------------------------------------------------------------------------

(deftest elision-opts-edn-enabled-has-include-large-false
  ;; When elision is on, we want the walker to ACTUALLY elide — so
  ;; `:rf.size/include-large?` is `false`. The "include-large?" name
  ;; is the walker's own switch (a `true` means pass through, a
  ;; `false` means emit the marker — the keyword surfaces from the
  ;; walker's API, not from our boolean).
  (let [edn (elision/elision-opts-edn true)
        parsed (cljs.reader/read-string edn)]
    (is (false? (:rf.size/include-large? parsed)))))

(deftest elision-opts-edn-disabled-has-include-large-true
  ;; When elision is disabled, `:rf.size/include-large?` is true so
  ;; the walker passes the raw value through. (We also short-circuit
  ;; the walk entirely in the eval form when disabled — see the
  ;; snapshot-eval-form test below.)
  (let [edn (elision/elision-opts-edn false)
        parsed (cljs.reader/read-string edn)]
    (is (true? (:rf.size/include-large? parsed)))))

(deftest elision-opts-edn-round-trips
  ;; The EDN we ship over nREPL must be readable on the other side.
  ;; pr-str + read-string round-trips for the structure we emit.
  (doseq [enabled? [true false]]
    (let [edn (elision/elision-opts-edn enabled?)
          parsed (cljs.reader/read-string edn)]
      (is (map? parsed))
      (is (contains? parsed :rf.size/include-large?)))))

;; ---------------------------------------------------------------------------
;; Eval-form composition for snapshot-tool (rf2-urjnc).
;;
;; The snapshot-tool builds a CLJS eval form sent over nREPL. With
;; elision enabled, the form wraps `(re-frame-pair2.runtime/snapshot-state
;; ...)` with a `reduce-kv` that walks each frame's :app-db slice through
;; `re-frame.core/elide-wire-value`. With elision disabled, the form is
;; the plain snapshot call.
;;
;; We can't `(require '[re-frame-pair2-mcp.tools])` from a node-test
;; build (the namespace `:require`s `re-frame-pair2-mcp.nrepl`, which
;; opens a TCP socket on load via shadow-cljs's preload contract) so we
;; mirror the form construction here. A rename of `snapshot-state` or
;; `elide-wire-value` breaks here as well as in production.
;; ---------------------------------------------------------------------------

(defn- build-snapshot-form
  "Mirror of the snapshot-tool's eval-form composition. Two arms:
  elision on/off. Keep in lockstep with `snapshot-tool` in
  `tools/snapshot.cljs`. Post-rf2-e35a5 both arms wrap the snapshot
  in `{:value <snap> :elided-count N}` so the count piggybacks on
  the same nREPL round-trip — no separate client-side walk.

  Post-rf2-vflrg the walker fires on BOTH `:app-db` and `:sub-cache`
  slices and threads `include-sensitive?` into the walker's
  `:rf.size/include-sensitive?` opt."
  ([opts elision?] (build-snapshot-form opts elision? false))
  ([opts elision? include-sensitive?]
   (let [elision-opts-form (elision/elision-opts-edn elision? include-sensitive?)]
     (if elision?
       (str "(let [snap (re-frame-pair2.runtime/snapshot-state "
            (pr-str opts) ")"
            "      walked (reduce-kv"
            "               (fn [m fid fmap]"
            "                 (if (map? fmap)"
            "                   (let [opts (merge {:frame fid} " elision-opts-form ")"
            "                         f    (fn [v] (re-frame.core/elide-wire-value v opts))"
            "                         fmap (if (contains? fmap :app-db)"
            "                                (update fmap :app-db f) fmap)"
            "                         fmap (if (contains? fmap :sub-cache)"
            "                                (update fmap :sub-cache f) fmap)]"
            "                     (assoc m fid fmap))"
            "                   (assoc m fid fmap)))"
            "               {} snap)]"
            "  {:value walked"
            "   :elided-count (count (filter #(and (map? %) (contains? % :rf.size/large-elided))"
            "                                (tree-seq coll? seq walked)))})")
       (str "{:value (re-frame-pair2.runtime/snapshot-state "
            (pr-str opts) ") :elided-count 0}")))))

(deftest snapshot-form-elision-off-wraps-bare-snap-with-zero-count
  ;; Post-rf2-e35a5: even with elision off, the form returns the
  ;; `{:value v :elided-count N}` envelope so the wire-pipeline
  ;; doesn't need an elision-branched response shape.
  (let [form (build-snapshot-form {:frames :all
                                   :include [:app-db]}
                                  false)]
    (is (re-find #":value \(re-frame-pair2\.runtime/snapshot-state" form))
    (is (re-find #":elided-count 0" form))
    ;; No walker call — elision-off path skips elide-wire-value
    ;; entirely (a value pass-through is cheaper than walking with
    ;; `:rf.size/include-large? true`).
    (is (not (re-find #"elide-wire-value" form)))))

(deftest snapshot-form-elision-on-wraps-with-walker
  ;; Elision on = the walker wrap. The form should reference both
  ;; `snapshot-state` and `elide-wire-value` so a typo in either name
  ;; breaks here.
  (let [form (build-snapshot-form {:frames :all
                                   :include [:app-db]}
                                  true)]
    (is (re-find #"re-frame-pair2\.runtime/snapshot-state" form))
    (is (re-find #"re-frame\.core/elide-wire-value" form))
    ;; Per-frame walking, not whole-snapshot walking — the walker is
    ;; applied to each slice with that frame's id, so the
    ;; `[:rf/elision]` registry lookup hits the right frame.
    (is (re-find #":frame fid" form))
    ;; The walker is invoked with `:rf.size/include-large? false` so
    ;; markers actually fire.
    (is (re-find #":rf\.size/include-large\? false" form))))

(deftest snapshot-form-walks-both-app-db-and-sub-cache
  ;; rf2-vflrg — the snapshot eval form now walks BOTH `:app-db` AND
  ;; `:sub-cache` slices through `elide-wire-value`. Per Tool-Pair
  ;; §Direct-read privacy posture, the `sub-cache` direct-read surface
  ;; MUST route through the wire walker with off-box defaults.
  ;;
  ;; The other slices (:machines :epochs :traces) have their own
  ;; wire-protocol mechanisms (dedup, diff-encode, sensitive-strip);
  ;; the walker fires only on the two direct-read slices that need it.
  (let [form (build-snapshot-form {:frames :all
                                   :include [:app-db :sub-cache :machines :epochs]}
                                  true)]
    ;; The form's `f` binding is the walker call; we check both
    ;; `update` arms cite the two direct-read slices by key.
    (is (re-find #"contains\? fmap :app-db" form))
    (is (re-find #"contains\? fmap :sub-cache" form))
    ;; Machines / epochs / traces are not walked here — they have
    ;; their own wire mechanisms.
    (is (not (re-find #"contains\? fmap :machines" form)))
    (is (not (re-find #"contains\? fmap :epochs" form)))))

(deftest snapshot-form-threads-include-sensitive
  ;; rf2-vflrg — `:include-sensitive?` flows through to the walker's
  ;; `:rf.size/include-sensitive?` opt so the same MCP arg that opts
  ;; in to forwarding sensitive traces / epochs also opts in to seeing
  ;; the raw value at sensitive paths in the :app-db / :sub-cache
  ;; slices.
  (let [form-default   (build-snapshot-form {:frames :all :include [:app-db]} true false)
        form-opted-in  (build-snapshot-form {:frames :all :include [:app-db]} true true)]
    (is (re-find #":rf\.size/include-sensitive\? false" form-default)
        "default ⇒ sensitive slots redact")
    (is (re-find #":rf\.size/include-sensitive\? true" form-opted-in)
        "include-sensitive? true ⇒ sensitive slots pass through")))

(deftest snapshot-form-counts-elision-markers-server-side
  ;; rf2-e35a5: the eval form returns `{:value <snap> :elided-count N}`
  ;; so the elision count rides back on the same nREPL round-trip.
  ;; The wire-pipeline reads the count from opts instead of re-walking
  ;; client-side.
  (let [form (build-snapshot-form {:frames :all
                                   :include [:app-db]}
                                  true)]
    (is (re-find #":value walked" form))
    (is (re-find #":elided-count " form))
    ;; The count predicate matches the cross-MCP vocabulary
    ;; (`re-frame.mcp-base.vocab/large-elided-key`).
    (is (re-find #":rf\.size/large-elided" form))
    (is (re-find #"tree-seq coll\? seq walked" form))))

;; ---------------------------------------------------------------------------
;; Eval-form composition for get-path-tool (rf2-urjnc).
;;
;; The get-path-tool eval form does `(get-in db path)` then passes the
;; resolved value through the walker. The walker's `:path` opt is set
;; to the supplied path so the marker's `:handle` carries
;; `[:rf.elision/at <path>]`.
;; ---------------------------------------------------------------------------

(defn- build-get-path-form
  "Mirror of the get-path-tool's eval-form composition. Keep in
  lockstep with `get-path-tool` in tools.cljs. Post-rf2-e35a5: the
  happy-path envelope carries `:elided-count` so the wire-pipeline
  reads the count from opts instead of re-walking the scalar.

  Post-rf2-vflrg the four-arity form takes `include-sensitive?` and
  threads it into the walker's `:rf.size/include-sensitive?` opt."
  ([path frame elision?] (build-get-path-form path frame elision? false))
  ([path frame elision? include-sensitive?]
   (let [path-edn      (pr-str path)
         snapshot-call (if frame
                         (str "(re-frame-pair2.runtime/snapshot " (pr-str frame) ")")
                         "(re-frame-pair2.runtime/snapshot)")
         frame-edn     (if frame (pr-str frame) "(re-frame-pair2.runtime/current-frame)")
         elision-opts  (elision/elision-opts-edn elision? include-sensitive?)
         elide-call    (if elision?
                         (str "(re-frame.core/elide-wire-value v"
                              "  (merge {:path path :frame " frame-edn "}"
                              "         " elision-opts "))")
                         "v")
         count-expr    (if elision?
                         (str "(count (filter #(and (map? %) (contains? % :rf.size/large-elided))"
                              "               (tree-seq coll? seq elided-v)))")
                         "0")]
     (str "(let [db " snapshot-call
          "      path " path-edn
          "      missing #js {}"
          "      v (get-in db path missing)"
          "      elided-v " elide-call
          "      n " count-expr "]"
          "  (if (identical? v missing)"
          "    {:ok? false :reason :path-not-found"
          "     :path path"
          "     :deepest-valid-prefix"
          "     (loop [acc [] cur db rem path]"
          "       (cond"
          "         (empty? rem) acc"
          "         (and (map? cur) (contains? cur (first rem)))"
          "         (recur (conj acc (first rem)) (get cur (first rem)) (rest rem))"
          "         (and (sequential? cur) (integer? (first rem))"
          "              (<= 0 (first rem) (dec (count cur))))"
          "         (recur (conj acc (first rem)) (nth (vec cur) (first rem)) (rest rem))"
          "         :else acc))}"
          "    {:ok? true :exists? true :path path :value elided-v :elided-count n}))"))))

(deftest get-path-form-elision-off-bypasses-walker
  ;; Elision off = the raw value rides the wire. Backwards-compatible
  ;; with the pre-rf2-urjnc shape. Post-rf2-e35a5 the value flows
  ;; through the `elided-v` binding (a pass-through when elision is
  ;; off) and the count is unconditionally zero.
  (let [form (build-get-path-form [:user :uploaded-pdf] :rf/default false)]
    (is (not (re-find #"elide-wire-value" form)))
    (is (re-find #"elided-v v" form))
    (is (re-find #":elided-count n" form))
    (is (re-find #"n 0" form))))

(deftest get-path-form-elision-on-wraps-value
  ;; Elision on = the value is walked. The walker call inherits the
  ;; `:path` so the marker's `:handle` slot is `[:rf.elision/at
  ;; [:user :uploaded-pdf]]`.
  (let [form (build-get-path-form [:user :uploaded-pdf] :rf/default true)]
    (is (re-find #"re-frame\.core/elide-wire-value v" form))
    ;; The walker's `:path` opt is the supplied path so the marker's
    ;; handle carries `[:rf.elision/at <path>]`.
    (is (re-find #":path path" form))
    ;; Frame is explicit when supplied.
    (is (re-find #":frame :rf/default" form))
    ;; Markers actually fire (include-large? is false).
    (is (re-find #":rf\.size/include-large\? false" form))))

(deftest get-path-form-defaults-to-current-frame
  ;; No `:frame` arg = the walker uses
  ;; `(re-frame-pair2.runtime/current-frame)` so the registry lookup
  ;; hits the operating frame.
  (let [form (build-get-path-form [:cart :items] nil true)]
    (is (re-find #":frame \(re-frame-pair2\.runtime/current-frame\)" form))))

(deftest get-path-form-path-edn-quotes-correctly
  ;; The path is pr-str'd into the form. Mixed key types (keywords,
  ;; integers, strings) must round-trip through the EDN reader on the
  ;; runtime side. We just check that the EDN-rendered path appears
  ;; as a substring of the form — the regex-escape song-and-dance for
  ;; literal `[ ] : "` chars isn't worth the cost; substring suffices.
  (let [path  [:cart "items" 3 :sku]
        form  (build-get-path-form path nil true)
        edn   (pr-str path)]
    (is (not= -1 (.indexOf form edn)))))

(deftest get-path-form-threads-include-sensitive
  ;; rf2-vflrg — `include-sensitive?` threads through to the walker's
  ;; `:rf.size/include-sensitive?` opt. Default is off (sensitive paths
  ;; redact); opt in with `true`.
  (let [form-default  (build-get-path-form [:user :token] :rf/default true false)
        form-opted-in (build-get-path-form [:user :token] :rf/default true true)]
    (is (re-find #":rf\.size/include-sensitive\? false" form-default)
        "default ⇒ sensitive paths redact")
    (is (re-find #":rf\.size/include-sensitive\? true" form-opted-in)
        "include-sensitive? true ⇒ raw value at sensitive paths")))

;; ---------------------------------------------------------------------------
;; Composition × wire-cap (rf2-rvyzy fallback).
;;
;; Elision runs FIRST (server-side, inside the eval form). When elision
;; is on and a `:large?` path matches, the response shrinks to the
;; marker and the wire-cap stays a backstop. When elision is OFF, the
;; raw payload rides and the wire-cap may still trip — that fallback
;; is the existing rf2-rvyzy mechanism.
;;
;; The cap check itself is a pure function over the assembled MCP
;; result envelope; we test that elision-off still produces a payload
;; the cap can measure (no shape weirdness from a missing wrap).
;; ---------------------------------------------------------------------------

(deftest wire-cap-composes-with-elision-off
  ;; Sanity: the snapshot form with elision off is plain EDN — it has
  ;; no marker shape on the way out, so when the runtime returns a
  ;; raw value the cap measures the raw bytes (the rf2-rvyzy fallback).
  (let [form (build-snapshot-form {:frames :all :include [:app-db]} false)]
    ;; No `:rf.size/*` shapes in the form when elision is off; the
    ;; marker emission is entirely the walker's job.
    (is (not (re-find #":rf\.size/large-elided" form)))
    (is (not (re-find #"elide-wire-value" form)))))

(deftest wire-cap-composes-with-elision-on
  ;; With elision on, the walker substitutes the large slot BEFORE the
  ;; payload crosses the wire — the cap then measures the
  ;; already-shrunk payload. The form references the walker; the
  ;; marker emission happens runtime-side.
  (let [form (build-snapshot-form {:frames :all :include [:app-db]} true)]
    (is (re-find #"elide-wire-value" form))))

;; ---------------------------------------------------------------------------
;; Cross-MCP vocabulary pin.
;;
;; The cross-MCP vocabulary for the wire marker (`:rf.size/large-elided`
;; / `[:rf.elision/at <path>]`) is reserved per Conventions §Reserved
;; namespaces / app-db keys / fx-ids and Spec 009 §Size elision in
;; traces. Pin the literals so a vocabulary drift surfaces here.
;; ---------------------------------------------------------------------------

;; ---------------------------------------------------------------------------
;; Wire-pipeline `:server-elided` opt (rf2-e35a5).
;;
;; The wire-pipeline's `:snapshot-map` and `:scalar-value` arms read
;; the elision count from the `:server-elided` opt instead of re-walking
;; the payload. The server-side eval form pre-counts and ships the
;; integer back on the same nREPL round-trip; the client-side walk is
;; eliminated for these payload kinds.
;;
;; The `:epoch-vector` arm continues to walk locally — its payload
;; (runtime trace/epoch records) may carry markers from upstream
;; `event_emit/elide-wire-value`, and the runtime drain doesn't
;; pre-count them.
;; ---------------------------------------------------------------------------

(def ^:private marker
  "A standalone `:rf.size/large-elided` marker, as the framework
  `elide-wire-value` walker would emit."
  {:rf.size/large-elided
   {:path [:user :uploaded-pdf] :bytes 102400 :type :string
    :reason :schema :handle [:rf.elision/at [:user :uploaded-pdf]]}})

(deftest snapshot-map-arm-uses-server-elided-when-supplied
  ;; rf2-e35a5: when `:server-elided` is on opts, the arm uses it
  ;; directly. The payload might or might not actually contain
  ;; markers — we trust the server-side count because the walker
  ;; that inserted the markers was the one counting.
  (let [snap {:rf/default {:app-db {:k :v}}}
        {:keys [indicators]}
        (wp/run-wire-pipeline snap
                              {:kind          :snapshot-map
                               :incl?         false
                               :mode          :diff
                               :dedup?        false
                               :slice-mode    :full
                               :slice-modes   {}
                               :server-elided 7})]
    (is (= 7 (:elided indicators))
        "Server-side count flows through verbatim")))

(deftest snapshot-map-arm-falls-back-to-walk-when-missing
  ;; Defensive: a degraded eval-form / a test shape that doesn't
  ;; supply `:server-elided` falls back to a local walk. The arm
  ;; still produces a correct count.
  (let [snap {:rf/default {:app-db marker}}
        {:keys [indicators]}
        (wp/run-wire-pipeline snap
                              {:kind        :snapshot-map
                               :incl?       false
                               :mode        :diff
                               :dedup?      false
                               :slice-mode  :full
                               :slice-modes {}})]
    (is (= 1 (:elided indicators))
        "Missing :server-elided ⇒ local walk picks up the marker")))

(deftest scalar-value-arm-uses-server-elided-when-supplied
  ;; rf2-e35a5: `:scalar-value` arm reads `:server-elided` for the
  ;; common `get-path` path — the eval form pre-counts.
  (let [{:keys [indicators]}
        (wp/run-wire-pipeline marker
                              {:kind          :scalar-value
                               :server-elided 1})]
    (is (= 1 (:elided indicators)))))

(deftest scalar-value-arm-falls-back-to-walk-when-missing
  ;; Sanity: no `:server-elided` ⇒ walk the scalar locally.
  (let [{:keys [indicators]}
        (wp/run-wire-pipeline marker {:kind :scalar-value})]
    (is (= 1 (:elided indicators)))))

(deftest scalar-value-arm-server-elided-zero-respected
  ;; A `0` server-side count must be honoured (not treated as
  ;; "absent ⇒ walk"). Pin the `some?` semantics — zero is a valid
  ;; count, not a sentinel.
  (let [{:keys [indicators]}
        (wp/run-wire-pipeline marker
                              {:kind          :scalar-value
                               :server-elided 0})]
    (is (= 0 (:elided indicators))
        "Zero is a valid server-side count, not a fall-back trigger")))

(deftest cross-mcp-vocabulary-rf-size-include-large
  ;; The walker's switch keyword is `:rf.size/include-large?`. We
  ;; render this verbatim into the EDN sent over nREPL. The kw shape
  ;; is normative per the walker's docstring; a rename in the framework
  ;; surface needs a co-ordinated update here.
  ;; Post-rf2-vflrg both opts ride the same map; assert against the
  ;; parsed form so map-key-order doesn't matter.
  (let [parsed-on  (cljs.reader/read-string (elision/elision-opts-edn true))
        parsed-off (cljs.reader/read-string (elision/elision-opts-edn false))]
    (is (false? (:rf.size/include-large? parsed-on)))
    (is (true?  (:rf.size/include-large? parsed-off)))
    (is (false? (:rf.size/include-sensitive? parsed-on)))
    (is (false? (:rf.size/include-sensitive? parsed-off)))))

;; ---------------------------------------------------------------------------
;; rf2-vflrg — `:include-sensitive?` threads through `elision-opts-edn`.
;;
;; Per Tool-Pair §Direct-read privacy posture, the `snapshot` and
;; `get-path` direct-read surfaces MUST honour
;; `:rf.size/include-sensitive?`. The two-arity form lifts the MCP arg
;; `:include-sensitive?` into the walker's namespace-prefixed opt.
;; ---------------------------------------------------------------------------

(deftest elision-opts-edn-include-sensitive-defaults-false
  ;; The single-arity legacy form preserves the off-box-safe default:
  ;; sensitive slots redact unless the caller opts in explicitly.
  (let [parsed (cljs.reader/read-string (elision/elision-opts-edn true))]
    (is (false? (:rf.size/include-sensitive? parsed))
        "single-arity ⇒ include-sensitive? false (the default per Tool-Pair §Direct-read privacy posture)")))

(deftest elision-opts-edn-include-sensitive-true-opts-in
  ;; The documented opt-in flow: `:include-sensitive? true` flows into
  ;; the walker opt of the same shape. Sensitive slots then pass through
  ;; unmodified.
  (let [parsed (cljs.reader/read-string (elision/elision-opts-edn true true))]
    (is (true? (:rf.size/include-sensitive? parsed))
        "two-arity true ⇒ walker passes sensitive values through")))

(deftest elision-opts-edn-include-sensitive-false-explicit
  ;; Explicit `false` matches the single-arity default.
  (let [parsed-explicit (cljs.reader/read-string (elision/elision-opts-edn true false))
        parsed-default  (cljs.reader/read-string (elision/elision-opts-edn true))]
    (is (= parsed-explicit parsed-default))))

(deftest elision-opts-edn-include-sensitive-orthogonal-to-elision-switch
  ;; The two knobs are orthogonal: elision-off + sensitive-on is a
  ;; valid (if unusual) combo — the agent has explicitly opted into both
  ;; raw values (`elision false`) and raw sensitive values
  ;; (`include-sensitive? true`).
  (doseq [enabled?           [true false]
          include-sensitive? [true false]]
    (let [parsed (cljs.reader/read-string
                   (elision/elision-opts-edn enabled? include-sensitive?))]
      (is (= (not enabled?) (:rf.size/include-large? parsed)))
      (is (= include-sensitive? (:rf.size/include-sensitive? parsed))))))
