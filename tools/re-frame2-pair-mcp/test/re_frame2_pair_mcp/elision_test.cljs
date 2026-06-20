(ns re-frame2-pair-mcp.elision-test
  "Unit tests for the size-elision wire-marker integration.

  Per `tools/re-frame2-pair-mcp/spec/Principles.md` §\"Size-elision wire markers\",
  the `snapshot` and `get-path` tools call
  `re-frame.core/elide-wire-value` server-side inside the
  CLJS eval form before any payload crosses the wire. A declared
  `:large?` slot or an over-threshold leaf is substituted with a
  `{:rf.size/large-elided {:path [...] :handle [:rf.elision/at <path>]
  ...}}` marker; the agent re-fetches via `get-path` with the handle's
  path.

  Tests pin `elision-opts-edn` directly from
  `re-frame2-pair-mcp.tools.elision`. The downstream eval-form
  composers (`build-snapshot-form` / `build-get-path-form`) remain
  local fixtures because their source counterparts live inlined in
  the per-tool namespaces (`tools.snapshot` / `tools.get-path`) and
  aren't surfaced as standalone public fns.

  `:elision` MCP-arg normalisation lives on the shared table-driven
  parser (`re-frame2-pair-mcp.tools.args/parse-bool-arg`);
  see `re-frame2-pair-mcp.args-test` for the coverage.

  Live end-to-end coverage runs against a real shadow-cljs build via
  the existing `test/stdio-roundtrip.js` harness — that's where the
  walker actually fires and we verify the marker comes back as EDN.
  The CLJS layer here just pins the wiring."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [cljs.reader]
            [re-frame2-pair-mcp.tools.elision :as elision]
            [re-frame2-pair-mcp.tools.wire-pipeline :as wp]))

;; ---------------------------------------------------------------------------
;; elision-opts-edn — EDN-render the walker's opts map for inlining.
;;
;; The helper takes walker-aligned `include-large?` /
;; `include-sensitive?` polarities (both pass-through booleans — true
;; ⇒ walker leaves the slot alone). Call sites convert from the MCP
;; arg `elision` (operator-facing on/off) via `(not elision?)`.
;; ---------------------------------------------------------------------------

;; ---------------------------------------------------------------------------
;; walk-required? — the fail-CLOSED predicate gating the per-slot walker
;; on every AI-facing read surface (EP-0015).
;;
;; The walker is the ONLY thing on the direct-read surfaces that redacts a
;; frame-declared-sensitive slot. It MUST run unless the caller opted into
;; BOTH raw axes — large (`:elision false` ⇒ include-large? true) AND
;; sensitive (`:include-sensitive true` ⇒ include-sensitive? true). Any
;; other combination (incl. a BARE `:elision false`) still walks.
;; ---------------------------------------------------------------------------

(deftest walk-required?-fail-closed-truth-table
  (testing "off-box default (both false) ⇒ walk (sensitive + large redact)"
    (is (true? (elision/walk-required? false false))))
  (testing "bare :elision false (large in, sensitive NOT opted in) ⇒ STILL walk"
    ;; Fail-closed: this combination walks the slot so sensitive content
    ;; never leaks off-box.
    (is (true? (elision/walk-required? true false))))
  (testing "sensitive opt-in but large elides (include-large? false) ⇒ walk"
    (is (true? (elision/walk-required? false true))))
  (testing "full-raw local opt-in (both true) ⇒ the ONLY no-walk case"
    (is (false? (elision/walk-required? true true)))))

(deftest elision-opts-edn-include-large-false-emits-markers
  ;; `:include-large?` false ⇒ walker emits the marker (the elision-ON
  ;; call-site posture). The keyword surfaces from the walker's API:
  ;; `true` means pass through, `false` means substitute the marker.
  (let [edn (elision/elision-opts-edn false)
        parsed (cljs.reader/read-string edn)]
    (is (false? (:rf.size/include-large? parsed)))))

(deftest elision-opts-edn-include-large-true-passes-through
  ;; `:include-large?` true ⇒ walker passes the raw value through (the
  ;; elision-OFF call-site posture). The eval form also short-circuits
  ;; the walk entirely when elision is disabled — see the snapshot-
  ;; eval-form test below.
  (let [edn (elision/elision-opts-edn true)
        parsed (cljs.reader/read-string edn)]
    (is (true? (:rf.size/include-large? parsed)))))

(deftest elision-opts-edn-round-trips
  ;; The EDN we ship over nREPL must be readable on the other side.
  ;; pr-str + read-string round-trips for the structure we emit.
  (doseq [include-large? [true false]]
    (let [edn (elision/elision-opts-edn include-large?)
          parsed (cljs.reader/read-string edn)]
      (is (map? parsed))
      (is (contains? parsed :rf.size/include-large?)))))

;; ---------------------------------------------------------------------------
;; Eval-form composition for snapshot-tool.
;;
;; The snapshot-tool builds a CLJS eval form sent over nREPL. With
;; elision enabled, the form wraps `(re-frame2-pair.runtime/snapshot-state
;; ...)` with a `reduce-kv` that walks each frame's :app-db slice through
;; `re-frame.core/elide-wire-value`. With elision disabled, the form is
;; the plain snapshot call.
;;
;; We can't `(require '[re-frame2-pair-mcp.tools])` from a node-test
;; build (the namespace `:require`s `re-frame2-pair-mcp.nrepl`, which
;; opens a TCP socket on load via shadow-cljs's preload contract) so we
;; mirror the form construction here. A rename of `snapshot-state` or
;; `elide-wire-value` breaks here as well as in production.
;; ---------------------------------------------------------------------------

(defn- build-snapshot-form
  "Mirror of the snapshot-tool's eval-form composition. Two arms:
  elision on/off. Keep in lockstep with `snapshot-tool` in
  `tools/snapshot.cljs`. Both arms wrap the snapshot
  in `{:value <snap> :elided-count N}` so the count piggybacks on
  the same nREPL round-trip — no separate client-side walk.

  The walker fires on BOTH `:app-db` and `:sub-cache`
  slices and threads `include-sensitive?` into the walker's
  `:rf.size/include-sensitive?` opt.

  Per EP-0001 ruling #14 the elision-on branch ALSO
  default-redacts the `:machines` slice (runtime-db-partition state) to
  `:rf/redacted` unless the operator opted in (`include-sensitive?` true
  here mirrors the production `incl?` opt-in axis)."
  ([opts elision?] (build-snapshot-form opts elision? false))
  ([opts elision? include-sensitive?]
   ;; Helper takes walker-aligned `include-large?`; flip
   ;; from the MCP-arg `elision?` polarity here, mirroring the
   ;; production call site in `tools/snapshot.cljs`.
   (let [elision-opts-form (elision/elision-opts-edn (not elision?) include-sensitive?)
         ;; Runtime-db (`:machines`) is redacted off-box by
         ;; default; the opt-in axis is `incl?` (here `include-sensitive?`).
         redact-runtime-db? (not include-sensitive?)
         ;; Fail-CLOSED: the per-slot walker over
         ;; `:app-db` / `:sub-cache` runs UNLESS the caller opted into BOTH
         ;; raw axes (`:elision false` ⇒ include-large? true AND
         ;; `:include-sensitive true`). A bare `:elision false` still walks
         ;; (sensitive redacts). Mirrors `walk-slices?` in tools/snapshot.cljs.
         walk-slices?       (elision/walk-required? (not elision?) include-sensitive?)
         ;; The whole reduction fires when ANY transform is active.
         walk-snapshot?     (or walk-slices? redact-runtime-db?)
         ;; On the `:app` default scope the form piggybacks
         ;; the excluded reserved tool frames; off that path it is `[]`.
         tool-frames-form (if (= :app (:frames opts))
                            "(filterv re-frame2-pair.runtime/reserved-tool-frame? (re-frame.core/frame-ids))"
                            "[]")]
     (if walk-snapshot?
       (str "(let [snap (re-frame2-pair.runtime/snapshot-state "
            (pr-str opts) ")"
            "      walked (reduce-kv"
            "               (fn [m fid fmap]"
            "                 (if (map? fmap)"
            "                   (let ["
            (if walk-slices?
              (str "                         opts (merge {:frame fid} " elision-opts-form ")"
                   "                         f    (fn [v] (re-frame.core/elide-wire-value v opts))"
                   "                         fmap (if (contains? fmap :app-db)"
                   "                                (update fmap :app-db f) fmap)"
                   "                         fmap (if (contains? fmap :sub-cache)"
                   "                                (update fmap :sub-cache f) fmap)")
              "")
            (if redact-runtime-db?
              (str "                         fmap (if (contains? fmap :machines)"
                   "                                (assoc fmap :machines :rf/redacted) fmap)")
              "")
            "]"
            "                     (assoc m fid fmap))"
            "                   (assoc m fid fmap)))"
            "               {} snap)]"
            "  {:value walked"
            "   :elided-count (count (filter #(and (map? %) (contains? % :rf.size/large-elided))"
            "                                (tree-seq coll? seq walked)))"
            "   :tool-frames-excluded " tool-frames-form "})")
       (str "{:value (re-frame2-pair.runtime/snapshot-state "
            (pr-str opts) ") :elided-count 0"
            " :tool-frames-excluded " tool-frames-form "}")))))

(deftest snapshot-form-full-raw-opt-in-wraps-bare-snap-with-zero-count
  ;; Even with no transforms active, the form returns the
  ;; `{:value v :elided-count N}` envelope so the wire-pipeline doesn't
  ;; need a branched response shape.
  ;;
  ;; The bare-snap (no walker) path is reached ONLY on the
  ;; full-raw opt-in: `:elision false` (include-large? true) AND
  ;; `:include-sensitive true`. A bare `:elision false` (the third arg
  ;; left false) STILL walks — see snapshot-form-bare-elision-false-still-walks.
  (let [form (build-snapshot-form {:frames :all
                                   :include [:app-db]}
                                  false  ; elision off (include-large? true)
                                  true)] ; include-sensitive opt-in
    (is (re-find #":value \(re-frame2-pair\.runtime/snapshot-state" form))
    (is (re-find #":elided-count 0" form))
    ;; No walker call — the full-raw opt-in skips elide-wire-value
    ;; entirely (a value pass-through is cheaper than walking).
    (is (not (re-find #"elide-wire-value" form)))
    ;; And no :machines redaction either — include-sensitive opts into
    ;; the runtime-db partition.
    (is (not (re-find #":machines :rf/redacted" form)))))

(deftest snapshot-form-bare-elision-false-still-walks
  ;; Fail-CLOSED. A BARE `:elision false` (no sensitive
  ;; opt-in) STILL routes `:app-db` / `:sub-cache` through the walker:
  ;; large content passes (`include-large? true`) but a declared-sensitive
  ;; slot redacts (`include-sensitive? false`). This guards the EP-0015
  ;; sensitive-bypass surface.
  (let [form (build-snapshot-form {:frames :all
                                   :include [:app-db :sub-cache]}
                                  false   ; elision off (include-large? true)
                                  false)] ; sensitive NOT opted in
    (is (re-find #"re-frame\.core/elide-wire-value" form)
        "bare :elision false MUST still walk — no sensitive bypass")
    (is (re-find #":rf\.size/include-sensitive\? false" form)
        "sensitive slots redact (caller didn't opt in)")
    (is (re-find #":rf\.size/include-large\? true" form)
        ":elision false overlays include-large? true — large content passes")
    (is (re-find #"contains\? fmap :app-db" form))
    (is (re-find #"contains\? fmap :sub-cache" form))))

(deftest snapshot-form-elision-on-wraps-with-walker
  ;; Elision on = the walker wrap. The form should reference both
  ;; `snapshot-state` and `elide-wire-value` so a typo in either name
  ;; breaks here.
  (let [form (build-snapshot-form {:frames :all
                                   :include [:app-db]}
                                  true)]
    (is (re-find #"re-frame2-pair\.runtime/snapshot-state" form))
    (is (re-find #"re-frame\.core/elide-wire-value" form))
    ;; Per-frame walking, not whole-snapshot walking — the walker is
    ;; applied to each slice with that frame's id, so the
    ;; `[:rf.runtime/elision]` runtime-db registry lookup hits the right frame.
    (is (re-find #":frame fid" form))
    ;; The walker is invoked with `:rf.size/include-large? false` so
    ;; markers actually fire.
    (is (re-find #":rf\.size/include-large\? false" form))))

(deftest snapshot-form-walks-both-app-db-and-sub-cache
  ;; The snapshot eval form walks BOTH `:app-db` AND
  ;; `:sub-cache` slices through `elide-wire-value`. Per Tool-Pair
  ;; §Direct-read privacy posture, the `sub-cache` direct-read surface
  ;; MUST route through the wire walker with off-box defaults.
  ;;
  ;; The `:epochs` / `:traces` slices have their own wire-protocol
  ;; mechanisms (dedup, diff-encode, sensitive-strip); the walker fires
  ;; only on the two direct-read slices that need it.
  (let [form (build-snapshot-form {:frames :all
                                   :include [:app-db :sub-cache :machines :epochs]}
                                  true)]
    ;; The form's `f` binding is the walker call; we check both
    ;; `update` arms cite the two direct-read slices by key.
    (is (re-find #"contains\? fmap :app-db" form))
    (is (re-find #"contains\? fmap :sub-cache" form))
    ;; The walker (`elide-wire-value`) is NOT applied to :machines — its
    ;; runtime-db redaction is a whole-slice `:rf/redacted` substitution,
    ;; not a per-slot walk (see snapshot-form-redacts-machines-runtime-db).
    (is (not (re-find #"update fmap :machines f" form)))
    (is (not (re-find #"contains\? fmap :epochs" form)))))

(deftest snapshot-form-redacts-machines-runtime-db-off-box-by-default
  ;; EP-0001 ruling #14 — the `:machines` slice is RUNTIME-DB
  ;; state (machine snapshots live in the runtime-db partition).
  ;; Per Spec 011 §Off-box redaction the runtime-db partition
  ;; is REDACTED/OMITTED off-box by default. So the elision-on form (the
  ;; default off-box posture, `include-sensitive?` false) substitutes the
  ;; `:machines` slice with `:rf/redacted`.
  (let [form-default  (build-snapshot-form {:frames :all
                                            :include [:app-db :machines]}
                                           true false)
        form-opted-in (build-snapshot-form {:frames :all
                                            :include [:app-db :machines]}
                                           true true)]
    (is (re-find #"contains\? fmap :machines" form-default)
        "default off-box ⇒ the form touches the :machines slice")
    (is (re-find #"assoc fmap :machines :rf/redacted" form-default)
        "default off-box ⇒ :machines redacts to :rf/redacted (runtime-db partition)")
    ;; Operator opted in to richer reads (the --allow-sensitive-reads gate,
    ;; surfaced as include-sensitive? here) ⇒ the runtime-db machine
    ;; snapshots ship; no :machines redaction arm.
    (is (not (re-find #"assoc fmap :machines :rf/redacted" form-opted-in))
        "trusted-local opt-in ⇒ :machines is NOT redacted (richer diagnostics)")))

(deftest snapshot-form-threads-include-sensitive
  ;; `:include-sensitive?` flows through to the walker's
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
  ;; The eval form returns `{:value <snap> :elided-count N}`
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

(deftest snapshot-form-app-scope-piggybacks-excluded-tool-frames
  ;; On the DEFAULT `:app` scope the eval form computes the
  ;; reserved :rf/* tool frames it excluded (via the runtime predicate)
  ;; and rides them back on the same round-trip under
  ;; `:tool-frames-excluded`, so the wire response can name them in a
  ;; :note. On the explicit `:all` / vector scopes the slot is `[]` —
  ;; no extra cost when the agent already chose the scope.
  (testing "elision-on, :app scope ⇒ form filters the registry through reserved-tool-frame?"
    (let [form (build-snapshot-form {:frames :app :include [:app-db]} true)]
      (is (re-find #":tool-frames-excluded \(filterv re-frame2-pair\.runtime/reserved-tool-frame\?"
                   form))
      (is (re-find #"re-frame\.core/frame-ids" form))))
  (testing "full-raw opt-in, :app scope ⇒ same piggyback on the bare-snap shape (rf2-t55hxg.13)"
    ;; The bare-snap (no-walk) shape is reached only on the full-raw
    ;; opt-in (`:elision false` AND `:include-sensitive true`); a bare
    ;; `:elision false` still walks.
    (let [form (build-snapshot-form {:frames :app :include [:app-db]} false true)]
      (is (re-find #":tool-frames-excluded \(filterv re-frame2-pair\.runtime/reserved-tool-frame\?"
                   form))))
  (testing ":all scope ⇒ empty piggyback (agent opted into tool frames)"
    (let [form (build-snapshot-form {:frames :all :include [:app-db]} true)]
      (is (re-find #":tool-frames-excluded \[\]" form))
      (is (not (re-find #"reserved-tool-frame\?" form)))))
  (testing "explicit vector scope ⇒ empty piggyback"
    (let [form (build-snapshot-form {:frames [:rf/xray] :include [:app-db]} true)]
      (is (re-find #":tool-frames-excluded \[\]" form)))))

;; ---------------------------------------------------------------------------
;; Eval-form composition for get-path-tool.
;;
;; The get-path-tool eval form does `(get-in db path)` then passes the
;; resolved value through the walker. The walker's `:path` opt is set
;; to the supplied path so the marker's `:handle` carries
;; `[:rf.elision/at <path>]`.
;; ---------------------------------------------------------------------------

(defn- build-get-path-form
  "Mirror of the get-path-tool's eval-form composition. Keep in
  lockstep with `get-path-tool` in tools.cljs. The
  happy-path envelope carries `:elided-count` so the wire-pipeline
  reads the count from opts instead of re-walking the scalar.

  The four-arity form takes `include-sensitive?` and
  threads it into the walker's `:rf.size/include-sensitive?` opt."
  ([path frame elision?] (build-get-path-form path frame elision? false))
  ([path frame elision? include-sensitive?]
   (let [path-edn      (pr-str path)
         snapshot-call (if frame
                         (str "(re-frame2-pair.runtime/snapshot " (pr-str frame) ")")
                         "(re-frame2-pair.runtime/snapshot)")
         frame-edn     (if frame (pr-str frame) "(re-frame2-pair.runtime/current-frame)")
         ;; Helper takes walker-aligned `include-large?`;
         ;; flip from the MCP-arg `elision?` polarity here, mirroring
         ;; the production call site in `tools/get_path.cljs`.
         elision-opts  (elision/elision-opts-edn (not elision?) include-sensitive?)
         ;; Fail-CLOSED: walk UNLESS the caller opted into
         ;; both raw axes (`:elision false` AND `:include-sensitive true`).
         ;; Mirrors `walk?` in tools/get_path.cljs.
         walk?         (elision/walk-required? (not elision?) include-sensitive?)
         elide-call    (if walk?
                         (str "(re-frame.core/elide-wire-value v"
                              "  (merge {:path path :frame " frame-edn "}"
                              "         " elision-opts "))")
                         "v")
         count-expr    (if walk?
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

(deftest get-path-form-full-raw-opt-in-bypasses-walker
  ;; The raw value rides the wire ONLY on the full-raw
  ;; opt-in: `:elision false` (include-large? true) AND
  ;; `:include-sensitive true`. The value flows through the
  ;; `elided-v` binding (a pass-through here) and the count is zero.
  (let [form (build-get-path-form [:user :uploaded-pdf] :rf/default false true)]
    (is (not (re-find #"elide-wire-value" form)))
    (is (re-find #"elided-v v" form))
    (is (re-find #":elided-count n" form))
    (is (re-find #"n 0" form))))

(deftest get-path-form-bare-elision-false-still-walks
  ;; Fail-CLOSED. A BARE `:elision false` (no sensitive
  ;; opt-in) STILL walks: large content passes (`include-large? true`) but
  ;; a declared-sensitive slot redacts (`include-sensitive? false`).
  ;; This guards the EP-0015 sensitive-bypass surface.
  (let [form (build-get-path-form [:user :token] :rf/default false false)]
    (is (re-find #"re-frame\.core/elide-wire-value v" form)
        "bare :elision false MUST still walk — no sensitive bypass")
    (is (re-find #":rf\.size/include-sensitive\? false" form)
        "sensitive path redacts (caller didn't opt in)")
    (is (re-find #":rf\.size/include-large\? true" form)
        ":elision false overlays include-large? true — large content passes")))

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
  ;; `(re-frame2-pair.runtime/current-frame)` so the registry lookup
  ;; hits the operating frame.
  (let [form (build-get-path-form [:cart :items] nil true)]
    (is (re-find #":frame \(re-frame2-pair\.runtime/current-frame\)" form))))

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
  ;; `include-sensitive?` threads through to the walker's
  ;; `:rf.size/include-sensitive?` opt. Default is off (sensitive paths
  ;; redact); opt in with `true`.
  (let [form-default  (build-get-path-form [:user :token] :rf/default true false)
        form-opted-in (build-get-path-form [:user :token] :rf/default true true)]
    (is (re-find #":rf\.size/include-sensitive\? false" form-default)
        "default ⇒ sensitive paths redact")
    (is (re-find #":rf\.size/include-sensitive\? true" form-opted-in)
        "include-sensitive? true ⇒ raw value at sensitive paths")))

;; ---------------------------------------------------------------------------
;; Composition × wire-cap fallback.
;;
;; Elision runs FIRST (server-side, inside the eval form). When elision
;; is on and a `:large?` path matches, the response shrinks to the
;; marker and the wire-cap stays a backstop. When elision is OFF, the
;; raw payload rides and the wire-cap may still trip — that is the
;; wire-cap fallback mechanism.
;;
;; The cap check itself is a pure function over the assembled MCP
;; result envelope; we test that elision-off still produces a payload
;; the cap can measure (no shape weirdness from a missing wrap).
;; ---------------------------------------------------------------------------

(deftest wire-cap-composes-with-full-raw-opt-in
  ;; Sanity: the snapshot form on the full-raw opt-in (`:elision false`
  ;; AND `:include-sensitive true`) is plain EDN — it has
  ;; no marker shape on the way out, so when the runtime returns a raw
  ;; value the cap measures the raw bytes (the wire-cap fallback).
  (let [form (build-snapshot-form {:frames :all :include [:app-db]} false true)]
    ;; No `:rf.size/*` shapes in the form when the walker is skipped; the
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
;; Wire-pipeline `:server-elided` opt.
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
  ;; When `:server-elided` is on opts, the arm uses it
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
  ;; The `:scalar-value` arm reads `:server-elided` for the
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
  ;; Both opts ride the same map; assert against the
  ;; parsed form so map-key-order doesn't matter.
  ;; Helper params are walker-aligned: `include-large?
  ;; false` = emit markers (the elision-ON call-site posture).
  (let [parsed-elide-on  (cljs.reader/read-string (elision/elision-opts-edn false))
        parsed-elide-off (cljs.reader/read-string (elision/elision-opts-edn true))]
    (is (false? (:rf.size/include-large? parsed-elide-on)))
    (is (true?  (:rf.size/include-large? parsed-elide-off)))
    (is (false? (:rf.size/include-sensitive? parsed-elide-on)))
    (is (false? (:rf.size/include-sensitive? parsed-elide-off)))))

;; ---------------------------------------------------------------------------
;; `:include-sensitive?` threads through `elision-opts-edn`.
;;
;; Per Tool-Pair §Direct-read privacy posture, the `snapshot` and
;; `get-path` direct-read surfaces MUST honour
;; `:rf.size/include-sensitive?`. EP-0015 §10 frames the
;; two-arity form as named-profile adoption: the `include-sensitive?`
;; posture names a `:rf.egress/*` profile (`off-box-tool` default /
;; `local-raw` opt-in) whose `:rf.size/*` floor the framework resolves;
;; the `include-large?` arg composes on top as the §10 explicit override.
;; ---------------------------------------------------------------------------

(deftest elision-opts-edn-include-sensitive-defaults-false
  ;; The single-arity legacy form preserves the off-box-safe default:
  ;; sensitive slots redact unless the caller opts in explicitly.
  (let [parsed (cljs.reader/read-string (elision/elision-opts-edn false))]
    (is (false? (:rf.size/include-sensitive? parsed))
        "single-arity ⇒ include-sensitive? false (the default per Tool-Pair §Direct-read privacy posture)")))

(deftest elision-opts-edn-include-sensitive-true-opts-in
  ;; The documented opt-in flow: `:include-sensitive? true` selects the
  ;; trusted-local `:rf.egress/local-raw` boundary (EP-0015 §10).
  ;; Sensitive slots then pass through unmodified.
  (let [parsed (cljs.reader/read-string (elision/elision-opts-edn false true))]
    (is (true? (:rf.size/include-sensitive? parsed))
        "two-arity true ⇒ local-raw ⇒ walker passes sensitive values through")))

(deftest elision-opts-edn-include-sensitive-false-explicit
  ;; Explicit `false` matches the single-arity default (both ⇒ off-box-tool).
  (let [parsed-explicit (cljs.reader/read-string (elision/elision-opts-edn false false))
        parsed-default  (cljs.reader/read-string (elision/elision-opts-edn false))]
    (is (= parsed-explicit parsed-default))))

(deftest elision-opts-edn-resolves-named-egress-profiles
  ;; EP-0015 §10 profile adoption: the posture
  ;; (`include-sensitive?`) names a `:rf.egress/*` profile, resolved to its
  ;; `:rf.size/*` floor via the cross-MCP `mcp-base.egress` mirror (pinned
  ;; byte-identical to the framework table by the mcp-conformance
  ;; wire-vocab gate). The `:elision` arg (`include-large?`) composes ON
  ;; TOP as the explicit override.
  ;;
  ;;   include-sensitive? false  ⇒ :rf.egress/off-box-tool
  ;;     {include-sensitive? false, include-large? <overlay>, include-digests? true}
  ;;   include-sensitive? true   ⇒ :rf.egress/local-raw
  ;;     {include-sensitive? true, include-large? true, include-digests? false}
  (testing "off-box-tool floor (the off-box default): sensitive redacts, structural digests on"
    (let [parsed (cljs.reader/read-string (elision/elision-opts-edn false false))]
      (is (false? (:rf.size/include-sensitive? parsed)) "sensitive redacts off-box")
      (is (false? (:rf.size/include-large? parsed)) "large elides under off-box-tool floor")
      (is (true? (:rf.size/include-digests? parsed))
          "off-box-tool carries structural digests (§10 structural indicators)")))
  (testing "off-box-tool + :elision false ⇒ include-large? overlaid true (the explicit override wins)"
    (let [parsed (cljs.reader/read-string (elision/elision-opts-edn true false))]
      (is (false? (:rf.size/include-sensitive? parsed)) "sensitive still redacts")
      (is (true? (:rf.size/include-large? parsed))
          "the :elision-false override opts large content back in over the floor")))
  (testing "local-raw floor (the --allow-sensitive-reads opt-in): sensitive AND large pass through"
    (let [parsed (cljs.reader/read-string (elision/elision-opts-edn false true))]
      (is (true? (:rf.size/include-sensitive? parsed)) "sensitive passes raw (operator opt-in)")
      (is (true? (:rf.size/include-large? parsed))
          "local-raw includes large too — the trusted-local boundary"))))

(deftest posture->profile-maps-posture-to-named-boundary
  ;; The posture→profile mapping is the explicit EP-0015 §10 naming:
  ;; off-box-tool is the off-box default; local-raw is the
  ;; trusted-local opt-in.
  (is (= :rf.egress/off-box-tool (elision/posture->profile false))
      "not-opted-in ⇒ the MCP/AI tool wire boundary")
  (is (= :rf.egress/local-raw (elision/posture->profile true))
      "trusted-local opt-in ⇒ the raw boundary"))
