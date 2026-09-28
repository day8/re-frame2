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
  `re-frame2-pair-mcp.tools.elision`. The snapshot eval-form composer
  (`build-snapshot-form`) is a local copy, because its source
  counterpart lives inlined in `tools.snapshot` and isn't surfaced as a
  standalone public fn.

  `:elision` MCP-arg normalisation lives on the shared table-driven
  parser (`re-frame2-pair-mcp.tools.args/parse-bool-arg`);
  see `re-frame2-pair-mcp.args-test` for the coverage.

  Live end-to-end coverage runs against a real shadow-cljs build via
  the `test/stdio-roundtrip.js` harness — that's where the
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
  ;; (large elides, so the marker fires). The server ships a NAME, never
  ;; the resolved booleans.
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

;; ---------------------------------------------------------------------------
;; Eval-form composition for snapshot-tool.
;;
;; The snapshot-tool builds a CLJS eval form sent over nREPL. The form
;; wraps `(re-frame2-pair.runtime/snapshot-state ...)` with a `reduce-kv`
;; that walks each frame's :app-db / :sub-cache slices through
;; `re-frame.core/project-egress`, whatever the elision posture.
;;
;; That composition is inlined in `snapshot-tool` rather than exposed as
;; a standalone public fn, so the tests below build a COPY of it. A
;; production change does not reach the copy: the real form's egress
;; boundary is asserted by the conformance corpus's `:raw-state/snapshot-*`
;; fixtures and by egress-elision-test.
;; ---------------------------------------------------------------------------

(defn- build-snapshot-form
  "Mirror of the snapshot-tool's eval-form composition. ONE arm: the
  `:app-db` / `:sub-cache` slices ALWAYS route through
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
               ;; The :sub-cache slice is walked PER ENTRY,
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
  (testing "full-raw opt-in, :app scope ⇒ same piggyback under the local-raw boundary"
    ;; The full-raw opt-in (`:elision false` AND `:include-sensitive
    ;; true`) still walks, under `:rf.egress/local-raw`, and carries the
    ;; same tool-frame piggyback.
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
;; Wire-pipeline `:server-elided` opt.
;;
;; The wire-pipeline's `:scalar-value` arm reads the elision count from
;; the `:server-elided` opt instead of re-walking the payload — get-path's
;; eval form counts over exactly the value it returns. The `:snapshot-map`
;; arm IGNORES it: the snapshot eval form counts over the
;; whole walked state, which the path slice and summary pass then shrink,
;; so the arm counts what it ships (pinned in `wire-pipeline-test`).
;;
;; The `:epoch-vector` arm walks locally — its payload
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

;; ---------------------------------------------------------------------------
;; `:include-sensitive?` selects the BOUNDARY `egress-opts-edn` names.
;;
;; Per Tool-Pair §Direct-read privacy posture, the `snapshot` and
;; `get-path` direct-read surfaces MUST honour the sensitive opt-in.
;; EP-0015 §10 frames the two-arity form as named-profile adoption: the
;; `include-sensitive?` posture NAMES a `:rf.egress/*` profile
;; (`off-box-tool` default / `local-raw` opt-in) whose `:rf.egress/*` floor
;; the FRAMEWORK resolves app-side; the `include-large?` arg composes on
;; top as the §10 explicit override. The server does no resolution of its
;; own, so what is pinned here is the NAME and the overlay — the
;; floors each name resolves to are pinned in `implementation/core`.
;; ---------------------------------------------------------------------------

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
;; `true ⇒ :rf.egress/local-raw`) lives in the shared
;; `re-frame.mcp-base.egress/mcp-tool-profile` and is pinned once in
;; `re-frame.mcp-base.egress-test`. The
;; `egress-opts-edn-names-a-profile-the-framework-door-accepts` test above
;; is the LOCAL integration test that this server's `egress-opts-edn` threads
;; that mapping (via the gate-produced boolean) through to the correct
;; `:rf.egress/*` floor + `:elision` overlay.
