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
  `re-frame2-pair-mcp.tools.elision`. The snapshot eval form's slice
  arms are pinned against the form the real `snapshot-tool` ships, in
  `re-frame2-pair-mcp.egress-elision-test`.

  `:elision` MCP-arg normalisation lives on the shared table-driven
  parser (`re-frame2-pair-mcp.tools.args/parse-bool-arg`);
  see `re-frame2-pair-mcp.args-test` for the coverage.

  Live end-to-end coverage runs against a real shadow-cljs build via
  the `test/stdio-roundtrip.js` harness — that's where the
  walker actually fires and we verify the marker comes back as EDN.
  The CLJS layer here just pins the wiring."
  (:require [cljs.test :refer-macros [deftest is]]
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
