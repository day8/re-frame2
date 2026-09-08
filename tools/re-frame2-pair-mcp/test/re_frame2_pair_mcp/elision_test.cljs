(ns re-frame2-pair-mcp.elision-test
  "Unit tests for the size-elision wire-marker integration.

  Per `tools/re-frame2-pair-mcp/spec/Principles.md` §\"Size-elision wire markers\",
  the `snapshot` and `get-path` tools call
  `re-frame.core/project-egress` server-side inside the
  CLJS eval form before any payload crosses the wire. A declared
  `:large?` slot or an over-threshold leaf is substituted with a
  `{:rf.size/large-elided {:path [...] :handle [:rf.elision/at <path>]
  ...}}` marker; the agent re-fetches via `get-path` with the handle's
  path.

  Tests pin `egress-opts-edn` directly from
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
            [re-frame.mcp-base.egress :as rf.mcp-base.egress]
            [re-frame2-pair-mcp.tools.elision :as elision]
            [re-frame2-pair-mcp.tools.wire-pipeline :as wp]))

;; ---------------------------------------------------------------------------
;; egress-opts-edn — EDN-render the walker's opts map for inlining.
;;
;; The helper takes walker-aligned `include-large?` /
;; `include-sensitive?` polarities (both pass-through booleans — true
;; ⇒ walker leaves the slot alone). Call sites convert from the MCP
;; arg `elision` (operator-facing on/off) via `(not elision?)`.
;; ---------------------------------------------------------------------------

(deftest egress-opts-edn-include-large-false-names-the-bare-profile
  ;; `:include-large?` false ⇒ no overlay at all: the rendered map is the
  ;; bare named boundary, and the door resolves off-box-tool's floor
  ;; (large elides, so the marker fires). rf2-kuky.88 — the server ships a
  ;; NAME, never the resolved booleans.
  (let [edn (elision/egress-opts-edn false)
        parsed (cljs.reader/read-string edn)]
    (is (= {:rf.egress/profile :rf.egress/off-box-tool} parsed)
        "bare off-box-tool profile, no :rf.egress/* overlay")))

(deftest egress-opts-edn-include-large-true-overlays-the-inclusion
  ;; `:include-large?` true ⇒ the EP-0015 §10 explicit override rides ON
  ;; TOP of the profile floor (the override wins), so large content passes
  ;; while the boundary named is still off-box-tool — sensitive still
  ;; redacts. This is the whole point of composing rather than swapping
  ;; profiles.
  (let [edn (elision/egress-opts-edn true)
        parsed (cljs.reader/read-string edn)]
    (is (= :rf.egress/off-box-tool (:rf.egress/profile parsed))
        "the boundary is still the off-box tool wire")
    (is (true? (:rf.egress/include-large? parsed))
        ":elision false overlays include-large? true")))

(deftest egress-opts-edn-names-local-raw-under-the-sensitive-opt-in
  ;; The trusted-local opt-in names a DIFFERENT boundary rather than
  ;; overlaying a sensitive inclusion — that is the posture→profile
  ;; mapping mcp-base owns.
  (let [parsed (cljs.reader/read-string (elision/egress-opts-edn false true))]
    (is (= :rf.egress/local-raw (:rf.egress/profile parsed))))
  (let [parsed (cljs.reader/read-string (elision/egress-opts-edn true true))]
    (is (= :rf.egress/local-raw (:rf.egress/profile parsed)))
    (is (true? (:rf.egress/include-large? parsed)))))

(deftest egress-opts-edn-round-trips
  ;; The EDN we ship over nREPL must be readable on the other side.
  ;; pr-str + read-string round-trips for the structure we emit.
  (doseq [include-large? [true false]]
    (let [edn (elision/egress-opts-edn include-large?)
          parsed (cljs.reader/read-string edn)]
      (is (map? parsed))
      (is (contains? parsed :rf.egress/profile)))))

;; ---------------------------------------------------------------------------
;; Eval-form composition for snapshot-tool.
;;
;; The snapshot-tool builds a CLJS eval form sent over nREPL. With
;; elision enabled, the form wraps `(re-frame2-pair.runtime/snapshot-state
;; ...)` with a `reduce-kv` that walks each frame's :app-db slice through
;; `re-frame.core/project-egress`. With elision disabled, the form is
;; the plain snapshot call.
;;
;; We can't `(require '[re-frame2-pair-mcp.tools])` from a node-test
;; build (the namespace `:require`s `re-frame2-pair-mcp.nrepl`, which
;; opens a TCP socket on load via shadow-cljs's preload contract) so we
;; mirror the form construction here. A rename of `snapshot-state` or
;; `elide-wire-value` breaks here as well as in production.
;; ---------------------------------------------------------------------------

(defn- build-snapshot-form
  "Mirror of the snapshot-tool's eval-form composition. ONE arm since
  rf2-kuky.88: the `:app-db` / `:sub-cache` slices ALWAYS route through
  `re-frame.core/project-egress`, and the NAMED `:rf.egress/*` profile
  decides the floor. Keep in lockstep with `snapshot-tool` in
  `tools/snapshot.cljs`. The form wraps the snapshot in
  `{:value <snap> :elided-count N}` so the count piggybacks on the same
  nREPL round-trip — no separate client-side walk.

  The projection fires on BOTH `:app-db` and `:sub-cache` slices;
  `include-sensitive?` selects the boundary (off-box-tool vs local-raw)
  rather than being threaded as a boolean.

  Per EP-0001 ruling #14 the form ALSO default-redacts the `:machines`
  slice (runtime-db-partition state) to `:rf/redacted` unless the
  operator opted in (`include-sensitive?` true here mirrors the
  production `incl?` opt-in axis)."
  ([opts elision?] (build-snapshot-form opts elision? false))
  ([opts elision? include-sensitive?]
   ;; Helper takes walker-aligned `include-large?`; flip
   ;; from the MCP-arg `elision?` polarity here, mirroring the
   ;; production call site in `tools/snapshot.cljs`.
   (let [egress-opts-form (elision/egress-opts-edn (not elision?) include-sensitive?)
         ;; Runtime-db (`:machines`) is redacted off-box by
         ;; default; the opt-in axis is `incl?` (here `include-sensitive?`).
         redact-runtime-db? (not include-sensitive?)
         ;; On the `:app` default scope the form piggybacks
         ;; the excluded reserved tool frames; off that path it is `[]`.
         tool-frames-form (if (= :app (:frames opts))
                            "(filterv re-frame2-pair.runtime/reserved-tool-frame? (re-frame.core/frame-ids))"
                            "[]")]
     (str "(let [snap (re-frame2-pair.runtime/snapshot-state "
          (pr-str opts) ")"
          "      walked (reduce-kv"
          "               (fn [m fid fmap]"
          "                 (if (map? fmap)"
          "                   (let ["
          (str "                         opts (merge {:frame fid} " egress-opts-form ")"
               "                         f    (fn [v] (re-frame.core/project-egress v opts))"
               "                         fmap (if (contains? fmap :app-db)"
               "                                (update fmap :app-db f) fmap)"
               ;; rf2-mtzv5m: the :sub-cache slice is walked PER ENTRY,
               ;; threading each entry's query-v as :query-v so a route
               ;; read sub re-seeds at its storage position (mirror of the
               ;; production slice-walk-src in tools/snapshot.cljs).
               "                         fmap (if (and (contains? fmap :sub-cache) (map? (:sub-cache fmap)))"
               "                                (update fmap :sub-cache"
               "                                  (fn [sc] (reduce-kv"
               "                                    (fn [m qv entry]"
               "                                      (assoc m qv"
               "                                        (if (and (map? entry) (contains? entry :value))"
               "                                          (update entry :value"
               "                                            (fn [v] (re-frame.core/project-egress v (assoc opts :query-v qv))))"
               "                                          entry)))"
               "                                    {} sc))) fmap)")
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
          "   :tool-frames-excluded " tool-frames-form "})"))))

(deftest snapshot-form-full-raw-opt-in-names-local-raw
  ;; Even with no transforms active, the form returns the
  ;; `{:value v :elided-count N}` envelope so the wire-pipeline doesn't
  ;; need a branched response shape.
  ;;
  ;; rf2-kuky.88 — there is no bare-snap arm any more. The full-raw
  ;; opt-in (`:elision false` AND `:include-sensitive true`) NAMES
  ;; `:rf.egress/local-raw`, under which the projection is the identity,
  ;; so the door is still called and the marker count is naturally zero.
  ;; Always calling the boundary is the safer shape on a privacy surface;
  ;; the only thing the old short-circuit bought was one traversal.
  (let [form (build-snapshot-form {:frames :all
                                   :include [:app-db]}
                                  false  ; elision off (include-large? true)
                                  true)] ; include-sensitive opt-in
    (is (re-find #"re-frame\.core/project-egress" form)
        "the door is called even under the full-raw opt-in")
    (is (re-find #":rf\.egress/profile :rf\.egress/local-raw" form)
        "the full-raw opt-in names the trusted-local boundary")
    (is (not (re-find #"elide-wire-value" form))
        "and never the walker export directly")
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
    (is (re-find #"re-frame\.core/project-egress" form)
        "bare :elision false MUST still project — no sensitive bypass")
    (is (re-find #":rf\.egress/profile :rf\.egress/off-box-tool" form)
        "the boundary stays the off-box tool wire, so sensitive slots redact")
    (is (re-find #":rf\.egress/include-large\? true" form)
        ":elision false overlays include-large? true — large content passes")
    (is (re-find #"contains\? fmap :app-db" form))
    (is (re-find #"contains\? fmap :sub-cache" form))))

(deftest snapshot-form-elision-on-wraps-with-walker
  ;; Elision on = the door wrap. The form should reference both
  ;; `snapshot-state` and `project-egress` so a typo in either name
  ;; breaks here.
  (let [form (build-snapshot-form {:frames :all
                                   :include [:app-db]}
                                  true)]
    (is (re-find #"re-frame2-pair\.runtime/snapshot-state" form))
    (is (re-find #"re-frame\.core/project-egress" form))
    ;; Per-frame walking, not whole-snapshot walking — the walker is
    ;; applied to each slice with that frame's id, so the
    ;; `[:rf.runtime/elision]` runtime-db registry lookup hits the right frame.
    (is (re-find #":frame fid" form))
    ;; No large-inclusion overlay, so the off-box-tool floor stands and
    ;; markers actually fire.
    (is (not (re-find #":rf\.egress/include-large\?" form)))
    (is (re-find #":rf\.egress/profile :rf\.egress/off-box-tool" form))))

(deftest snapshot-form-walks-both-app-db-and-sub-cache
  ;; The snapshot eval form walks BOTH `:app-db` AND
  ;; `:sub-cache` slices through `project-egress`. Per Tool-Pair
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
    ;; The door (`project-egress`) is NOT applied to :machines — its
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
  ;; `:include-sensitive?` selects the named BOUNDARY, so the same MCP
  ;; arg that opts in to forwarding sensitive traces / epochs also opts
  ;; in to seeing the raw value at sensitive paths in the :app-db /
  ;; :sub-cache slices — expressed as "which boundary is this", not as a
  ;; hand-rolled boolean (rf2-kuky.88).
  (let [form-default   (build-snapshot-form {:frames :all :include [:app-db]} true false)
        form-opted-in  (build-snapshot-form {:frames :all :include [:app-db]} true true)]
    (is (re-find #":rf\.egress/profile :rf\.egress/off-box-tool" form-default)
        "default ⇒ the off-box tool boundary, so sensitive slots redact")
    (is (re-find #":rf\.egress/profile :rf\.egress/local-raw" form-opted-in)
        "include-sensitive? true ⇒ the trusted-local boundary passes them through")))

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

  The four-arity form takes `include-sensitive?` and lets it select the
  named `:rf.egress/*` BOUNDARY (rf2-kuky.88), rather than threading a
  `:rf.egress/include-sensitive?` boolean."
  ([path frame elision?] (build-get-path-form path frame elision? false))
  ([path frame elision? include-sensitive?]
   (let [path-edn      (pr-str path)
         ;; Since rf2-q17a the frame is resolved ONCE into `fid` by the
         ;; wrapper below; the read and the projection both address it, so
         ;; there is no longer a `(current-frame)` call in the egress
         ;; opts and no no-arg `(snapshot)` call at all.
         snapshot-call "(re-frame2-pair.runtime/snapshot fid)"
         frame-edn     "fid"
         resolve-call  (if frame
                         (str "(re-frame2-pair.runtime/current-frame " (pr-str frame) ")")
                         "(re-frame2-pair.runtime/current-frame)")
         ;; Helper takes walker-aligned `include-large?`;
         ;; flip from the MCP-arg `elision?` polarity here, mirroring
         ;; the production call site in `tools/get_path.cljs`.
         egress-opts   (elision/egress-opts-edn (not elision?) include-sensitive?)
         ;; rf2-kuky.88 — the door fires UNCONDITIONALLY; the NAMED
         ;; profile decides the floor. Mirrors `project-call-src` in
         ;; tools/get_path.cljs.
         elide-call    (str "(re-frame.core/project-egress v"
                            "  (merge {:path path :frame " frame-edn "}"
                            "         " egress-opts "))")
         count-expr    (str "(count (filter #(and (map? %) (contains? % :rf.size/large-elided))"
                            "               (tree-seq coll? seq elided-v)))")]
     (str "(let [fid " resolve-call "]"
          "  (if (nil? fid)"
          "    (re-frame2-pair.runtime/ambiguous-frame-error :get-path)"
          "    (let [db " snapshot-call
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
          "      {:ok? true :exists? true :path path :value elided-v :elided-count n}))))"))))

(deftest get-path-form-full-raw-opt-in-names-local-raw
  ;; rf2-kuky.88 — the full-raw opt-in (`:elision false` AND
  ;; `:include-sensitive true`) NAMES `:rf.egress/local-raw` rather than
  ;; skipping the door. Under that boundary the projection is the
  ;; identity, so the raw value still rides the wire and the marker count
  ;; is naturally zero — but the call is there, which is what makes this
  ;; surface auditable.
  (let [form (build-get-path-form [:user :uploaded-pdf] :rf/default false true)]
    (is (not (re-find #"elide-wire-value" form)))
    (is (re-find #"re-frame\.core/project-egress v" form)
        "the door is called even under the full-raw opt-in")
    (is (re-find #":rf\.egress/profile :rf\.egress/local-raw" form))
    (is (re-find #":elided-count n" form))))

(deftest get-path-form-bare-elision-false-still-walks
  ;; Fail-CLOSED. A BARE `:elision false` (no sensitive
  ;; opt-in) STILL walks: large content passes (`include-large? true`) but
  ;; a declared-sensitive slot redacts (`include-sensitive? false`).
  ;; This guards the EP-0015 sensitive-bypass surface.
  (let [form (build-get-path-form [:user :token] :rf/default false false)]
    (is (re-find #"re-frame\.core/project-egress v" form)
        "bare :elision false MUST still project — no sensitive bypass")
    (is (re-find #":rf\.egress/profile :rf\.egress/off-box-tool" form)
        "the boundary stays the off-box tool wire, so a sensitive path redacts")
    (is (re-find #":rf\.egress/include-large\? true" form)
        ":elision false overlays include-large? true — large content passes")))

(deftest get-path-form-elision-on-wraps-value
  ;; Elision on = the value is walked. The walker call inherits the
  ;; `:path` so the marker's `:handle` slot is `[:rf.elision/at
  ;; [:user :uploaded-pdf]]`.
  (let [form (build-get-path-form [:user :uploaded-pdf] :rf/default true)]
    (is (re-find #"re-frame\.core/project-egress v" form))
    ;; The walker's `:path` opt is the supplied path so the marker's
    ;; handle carries `[:rf.elision/at <path>]`.
    (is (re-find #":path path" form))
    ;; The walker addresses the RESOLVED id. An explicit frame reaches
    ;; it through tier 1 of the one resolve (rf2-q17a), rather than
    ;; being spliced in a second time as a literal.
    (is (re-find #":frame fid" form))
    (is (re-find #"current-frame :rf/default" form))
    ;; No large-inclusion overlay, so the off-box-tool floor stands and
    ;; markers actually fire.
    (is (not (re-find #":rf\.egress/include-large\?" form)))
    (is (re-find #":rf\.egress/profile :rf\.egress/off-box-tool" form))))

(deftest get-path-form-defaults-to-current-frame
  ;; No `:frame` arg = the form resolves the operating frame ONCE into
  ;; `fid` and the walker addresses THAT, so the registry lookup and
  ;; the value read can never name different frames (rf2-q17a). The
  ;; walker no longer issues a `(current-frame)` call of its own.
  (let [form (build-get-path-form [:cart :items] nil true)]
    (is (re-find #"let \[fid \(re-frame2-pair\.runtime/current-frame\)\]" form))
    (is (re-find #":frame fid" form))
    (is (not (re-find #":frame \(re-frame2-pair\.runtime/current-frame\)" form))
        "the walker addresses the resolved id, not a second resolve")))

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
  ;; `include-sensitive?` selects the named BOUNDARY. Default is the
  ;; off-box tool wire (sensitive paths redact); the opt-in names the
  ;; trusted-local boundary.
  (let [form-default  (build-get-path-form [:user :token] :rf/default true false)
        form-opted-in (build-get-path-form [:user :token] :rf/default true true)]
    (is (re-find #":rf\.egress/profile :rf\.egress/off-box-tool" form-default)
        "default ⇒ sensitive paths redact")
    (is (re-find #":rf\.egress/profile :rf\.egress/local-raw" form-opted-in)
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
  ;; AND `:include-sensitive true`) names `:rf.egress/local-raw`, under
  ;; which the projection is the identity — so no marker rides out and
  ;; the cap measures the raw bytes (the wire-cap fallback). The marker
  ;; emission is entirely the profile floor's job, never a shape this
  ;; form hand-rolls.
  (let [form (build-snapshot-form {:frames :all :include [:app-db]} false true)]
    (is (re-find #":rf\.egress/profile :rf\.egress/local-raw" form))
    (is (not (re-find #"elide-wire-value" form)))))

(deftest wire-cap-composes-with-elision-on
  ;; With elision on, the projection substitutes the large slot BEFORE
  ;; the payload crosses the wire — the cap then measures the
  ;; already-shrunk payload. The form references the door; the marker
  ;; emission happens runtime-side, decided by the named profile's floor.
  (let [form (build-snapshot-form {:frames :all :include [:app-db]} true)]
    (is (re-find #"re-frame\.core/project-egress" form))
    (is (re-find #":rf\.egress/profile :rf\.egress/off-box-tool" form))))

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

(deftest cross-mcp-vocabulary-rf-egress-profile
  ;; rf2-kuky.88 — what rides the wire is the PROFILE NAME, not the
  ;; resolved `:rf.egress/*` booleans. `:rf.egress/profile` is the key the
  ;; framework door reads, and `:rf.egress/include-large?` is the ONE
  ;; boolean this renderer still emits, as the EP-0015 §10 explicit
  ;; override. Assert against the parsed form so map-key-order doesn't
  ;; matter. Helper params are walker-aligned: `include-large? false` =
  ;; emit markers (the elision-ON call-site posture).
  (let [parsed-elide-on  (cljs.reader/read-string (elision/egress-opts-edn false))
        parsed-elide-off (cljs.reader/read-string (elision/egress-opts-edn true))]
    (is (= :rf.egress/off-box-tool (:rf.egress/profile parsed-elide-on)))
    (is (= :rf.egress/off-box-tool (:rf.egress/profile parsed-elide-off)))
    (is (not (contains? parsed-elide-on :rf.egress/include-large?))
        "elision ON leaves the profile floor alone — no overlay at all")
    (is (true? (:rf.egress/include-large? parsed-elide-off))
        "elision OFF overlays the large inclusion over the floor")
    (is (not (contains? parsed-elide-on :rf.egress/include-sensitive?))
        "the sensitive axis is the profile's to decide, never hand-rolled here")
    (is (not (contains? parsed-elide-off :rf.egress/include-sensitive?)))))

;; ---------------------------------------------------------------------------
;; `:include-sensitive?` selects the BOUNDARY `egress-opts-edn` names.
;;
;; Per Tool-Pair §Direct-read privacy posture, the `snapshot` and
;; `get-path` direct-read surfaces MUST honour the sensitive opt-in.
;; EP-0015 §10 frames the two-arity form as named-profile adoption: the
;; `include-sensitive?` posture NAMES a `:rf.egress/*` profile
;; (`off-box-tool` default / `local-raw` opt-in) whose `:rf.egress/*` floor
;; the FRAMEWORK resolves app-side; the `include-large?` arg composes on
;; top as the §10 explicit override. rf2-kuky.88 deleted the server-side
;; resolution, so what is pinned here is the NAME and the overlay — the
;; floors each name resolves to are pinned in `implementation/core`.
;; ---------------------------------------------------------------------------

(deftest egress-opts-edn-single-arity-is-the-off-box-safe-default
  ;; The single-arity form preserves the off-box-safe default: the
  ;; off-box tool boundary, under which sensitive slots redact unless the
  ;; caller opts in explicitly.
  (let [parsed (cljs.reader/read-string (elision/egress-opts-edn false))]
    (is (= :rf.egress/off-box-tool (:rf.egress/profile parsed))
        "single-arity ⇒ off-box-tool (the default per Tool-Pair §Direct-read privacy posture)")))

(deftest egress-opts-edn-explicit-false-matches-the-default
  ;; Explicit `false` matches the single-arity default (both ⇒ off-box-tool).
  (let [parsed-explicit (cljs.reader/read-string (elision/egress-opts-edn false false))
        parsed-default  (cljs.reader/read-string (elision/egress-opts-edn false))]
    (is (= parsed-explicit parsed-default))))

(deftest egress-opts-edn-names-a-profile-the-framework-door-accepts
  ;; The whole contract this renderer carries: whatever it names must be
  ;; a member of the closed enum, or `project-egress` throws
  ;; `:rf.error/unknown-egress-profile` at eval time on a live off-box
  ;; read. The name set is pinned equal to the framework's by the
  ;; mcp-conformance wire-vocab gate; this pins that the renderer only
  ;; ever emits members of it.
  (doseq [large? [true false]
          incl?  [true false]]
    (let [parsed (cljs.reader/read-string (elision/egress-opts-edn large? incl?))]
      (is (contains? rf.mcp-base.egress/profiles (:rf.egress/profile parsed))
          (str "egress-opts-edn " large? " " incl? " named "
               (:rf.egress/profile parsed) ", which is not in the closed enum")))))

;; NOTE the pure posture→profile mapping (`false ⇒ :rf.egress/off-box-tool`,
;; `true ⇒ :rf.egress/local-raw`) now lives in the shared
;; `re-frame.mcp-base.egress/mcp-tool-profile` and is pinned once in
;; `re-frame.mcp-base.egress-test` (rf2-54y369). The
;; `egress-opts-edn-names-a-profile-the-framework-door-accepts` test above
;; is the LOCAL integration test that this server's `egress-opts-edn` threads
;; that mapping (via the gate-produced boolean) through to the correct
;; `:rf.egress/*` floor + `:elision` overlay.
