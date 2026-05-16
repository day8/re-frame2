(ns day8.re-frame2-causa-mcp.elision-test
  "Unit tests for the W-6 size-elision walker integration at the
  Causa-MCP boundary (rf2-8xzoe.10). Pins:

    - MUST 15 — every tree-typed tool declares the `:include-large?`
      slot and the `:elided-large` indicator field. `apply-to-result`
      is the boundary wrapper those tools call; the slot + counter
      are pinned here.
    - MUST 17 — `re-frame.core/elide-wire-value` is the single
      normative emission site. This ns NEVER emits the marker — it
      inlines the walker call into the eval form (via
      `elision-opts-edn`) and downstream only counts markers the
      walker produced. The wrapper's no-marker-emission posture is
      pinned by the count-then-stamp-only contract.
    - MUST 18 — sensitive-wins composition. The cascade itself lives
      in the framework walker (we ship opts, not policy); we pin the
      knob shapes both arms receive.
    - MUST 19, `:include-large?` half — cross-MCP opt-in slot name
      (parallel to `:include-sensitive?`).
    - Result-envelope counter shape — `:elided-large` stamped iff
      at least one marker is present; the zero-elision common path
      carries no counter (cross-MCP indicator-field convention,
      parallel to `:dropped-sensitive`).
    - Interaction with B-1 (privacy filter) — `apply-to-result`'s
      envelope is additive, so the two boundary wrappers compose;
      `:dropped-sensitive` + `:elided-large` ride together on the
      same envelope when both axes fired.

  These tests are the load-bearing pin for the downstream
  tree-typed tool beads — every dispatcher that emits a tree-typed
  payload calls `elision/apply-to-result` once at the boundary; the
  contract here is the one those tools inherit."
  (:require [cljs.reader]
            [cljs.test :refer-macros [deftest is testing]]
            [day8.re-frame2-causa-mcp.elision :as elision]
            [day8.re-frame2-causa-mcp.privacy :as privacy]))

;; A canonical `:rf.size/large-elided` marker, as the framework's
;; `elide-wire-value` walker would emit. Reused across the corpus so a
;; vocabulary drift surfaces once.
(def ^:private marker
  {:rf.size/large-elided
   {:path   [:user :uploaded-pdf]
    :bytes  102400
    :type   :string
    :reason :declared
    :handle [:rf.elision/at [:user :uploaded-pdf]]}})

;; ---------------------------------------------------------------------------
;; Public surface — vars exist and are callable.
;; ---------------------------------------------------------------------------

(deftest public-surface-resolvable
  (testing "W-6 lands the elision-boundary helpers downstream
            tree-typed tool dispatchers will require"
    (is (fn? elision/count-elided-markers))
    (is (fn? elision/parse-include-large))
    (is (fn? elision/elision-opts-edn))
    (is (fn? elision/stamp-elided-large))
    (is (fn? elision/apply-to-result))
    (is (false? elision/include-large-default)
        "spec/004 §6 default posture: include-large? defaults false ⇒ markers emit")))

;; ---------------------------------------------------------------------------
;; count-elided-markers — re-export of the cross-MCP base walker.
;; ---------------------------------------------------------------------------

(deftest count-elided-markers-re-export-walks-payload
  ;; Cross-MCP re-export of `re-frame.mcp-base.elision/count-elided-markers`
  ;; (rf2-9fz64). The full walker corpus lives in the base ns; here
  ;; we pin that the re-export wires through and surfaces the same
  ;; behaviour at every depth and shape.
  (is (= 0 (elision/count-elided-markers nil)))
  (is (= 0 (elision/count-elided-markers {})))
  (is (= 0 (elision/count-elided-markers [])))
  (is (= 0 (elision/count-elided-markers "string")))
  (is (= 0 (elision/count-elided-markers 42)))
  (is (= 0 (elision/count-elided-markers {:ok? true :payload {:a 1 :b [2 3]}})))
  (is (= 1 (elision/count-elided-markers marker)))
  (is (= 1 (elision/count-elided-markers {:value marker})))
  (is (= 1 (elision/count-elided-markers [marker])))
  (is (= 2 (elision/count-elided-markers {:a marker :b marker})))
  (is (= 3 (elision/count-elided-markers
            {:slice1 marker :slice2 {:nested marker} :slice3 [{:deep marker}]}))))

(deftest count-elided-markers-marker-body-opaque
  ;; The walker is shallow at the marker boundary — once a marker is
  ;; found, its body is NOT recursed into. Pathological marker-shape
  ;; lodged inside the body still counts the OUTER marker once.
  (let [pathological {:rf.size/large-elided
                      {:path   [:a :b]
                       :bytes  100
                       :type   :string
                       :reason :declared
                       :handle [:rf.elision/at [:a :b]]
                       :extra  {:rf.size/large-elided {:bytes 1}}}}]
    (is (= 1 (elision/count-elided-markers pathological)))))

;; ---------------------------------------------------------------------------
;; parse-include-large — cross-MCP fixed arg name (MUST 19 half).
;; ---------------------------------------------------------------------------

(deftest parse-include-large-default-false-when-absent
  ;; spec/004 §6 MUST: the default is markers-on. Every shape of "no
  ;; input" collapses to the default-false posture.
  (is (false? (elision/parse-include-large nil)))
  (is (false? (elision/parse-include-large js/undefined)))
  (is (false? (elision/parse-include-large {})))
  (is (false? (elision/parse-include-large #js {})))
  (is (false? (elision/parse-include-large #js {:other "value"}))))

(deftest parse-include-large-true-from-js-args-object
  ;; The MCP SDK hands the dispatcher a JS args object; the helper
  ;; reads the cross-server slot `include-large?` from it. Accept-
  ;; shapes mirror `parse-include-sensitive` so an agent sees the same
  ;; permissive parsing on both args.
  (is (true? (elision/parse-include-large #js {"include-large?" true})))
  (is (true? (elision/parse-include-large #js {"include-large?" "true"})))
  (is (true? (elision/parse-include-large #js {"include-large?" "yes"})))
  (is (true? (elision/parse-include-large #js {"include-large?" "1"}))))

(deftest parse-include-large-false-from-js-args-object
  (is (false? (elision/parse-include-large #js {"include-large?" false})))
  (is (false? (elision/parse-include-large #js {"include-large?" "false"})))
  (is (false? (elision/parse-include-large #js {"include-large?" "no"})))
  (is (false? (elision/parse-include-large #js {"include-large?" "0"}))))

(deftest parse-include-large-from-cljs-map
  ;; Some downstream paths may hand the helper a CLJS map (already
  ;; coerced from the MCP wire). Both keyword and stringified keys
  ;; resolve so the helper is robust across call shapes.
  (is (true?  (elision/parse-include-large {:include-large? true})))
  (is (true?  (elision/parse-include-large {"include-large?" true})))
  (is (false? (elision/parse-include-large {:include-large? false}))))

(deftest parse-include-large-unrecognised-value-defaults-suppress
  ;; Unrecognised raw values (e.g. a number, a random keyword, an
  ;; arbitrary object) collapse to the default-on posture per the
  ;; cross-MCP parse-boolean contract.
  (is (false? (elision/parse-include-large #js {"include-large?" "maybe"})))
  (is (false? (elision/parse-include-large #js {"include-large?" 42})))
  (is (false? (elision/parse-include-large {:include-large? :perhaps}))))

;; ---------------------------------------------------------------------------
;; elision-opts-edn — render walker opts as EDN for nREPL inline.
;;
;; The polarity is inverted from pair2-mcp's `elision-opts-edn` —
;; pair2-mcp accepts `enabled?` (the high-level "is elision on?"
;; boolean) and INVERTS it before rendering, because pair2-mcp's MCP
;; arg is `:elision` (truthy = walk). Causa-mcp accepts the
;; cross-server `:include-large?` boolean DIRECTLY (truthy = pass
;; through) and renders it WITHOUT inversion — same key polarity as
;; the walker's `:rf.size/include-large?` opt itself. This is a
;; deliberate divergence: the cross-MCP arg vocabulary (per
;; spec/004 §6) is `:include-large?`, not `:elision`, so causa-mcp's
;; arg names match the walker opt names directly. One fewer flip on
;; the call-site.
;; ---------------------------------------------------------------------------

(deftest elision-opts-edn-include-large-false-emits-markers
  ;; The default off-box-safe posture: include-large? false ⇒ walker
  ;; opt `:rf.size/include-large? false` ⇒ markers emit. Pins the
  ;; pass-through-polarity contract.
  (let [parsed (cljs.reader/read-string (elision/elision-opts-edn false))]
    (is (false? (:rf.size/include-large? parsed))
        "include-large? false ⇒ walker emits markers")))

(deftest elision-opts-edn-include-large-true-bypasses-walker
  ;; Opt-in: include-large? true ⇒ walker opt
  ;; `:rf.size/include-large? true` ⇒ large values pass through. The
  ;; documented escape hatch (per spec/004 §6 "Per-call opt-out").
  (let [parsed (cljs.reader/read-string (elision/elision-opts-edn true))]
    (is (true? (:rf.size/include-large? parsed))
        "include-large? true ⇒ walker passes large values through")))

(deftest elision-opts-edn-round-trips
  ;; The EDN we ship over nREPL must be readable on the other side.
  ;; pr-str + read-string round-trips for the structure we emit.
  (doseq [include-large? [true false]]
    (let [edn    (elision/elision-opts-edn include-large?)
          parsed (cljs.reader/read-string edn)]
      (is (map? parsed))
      (is (contains? parsed :rf.size/include-large?)))))

(deftest elision-opts-edn-include-sensitive-defaults-false
  ;; Single-arity legacy form preserves the off-box-safe default for
  ;; the sibling sensitive knob. spec/Tool-Pair §Direct-read privacy
  ;; posture: sensitive slots redact unless the caller opts in
  ;; explicitly. (The arg parser is `privacy/parse-include-sensitive`;
  ;; this knob is the walker-opt half of the same flag.)
  (let [parsed (cljs.reader/read-string (elision/elision-opts-edn false))]
    (is (false? (:rf.size/include-sensitive? parsed))
        "single-arity ⇒ include-sensitive? false (default per Tool-Pair §Direct-read)")))

(deftest elision-opts-edn-include-sensitive-true-opts-in
  ;; The documented opt-in flow: `:include-sensitive? true` flows into
  ;; the walker opt of the same shape. Sensitive slots then pass
  ;; through unmodified.
  (let [parsed (cljs.reader/read-string (elision/elision-opts-edn false true))]
    (is (true? (:rf.size/include-sensitive? parsed))
        "two-arity true ⇒ walker passes sensitive values through")))

(deftest elision-opts-edn-two-knobs-orthogonal
  ;; The two knobs compose orthogonally — include-large? false +
  ;; include-sensitive? true is a valid combo (the agent wants raw
  ;; sensitive values but still wants large leaves elided). Every
  ;; combination round-trips with the polarity preserved.
  (doseq [il? [true false]
          is? [true false]]
    (let [parsed (cljs.reader/read-string (elision/elision-opts-edn il? is?))]
      (is (= il? (:rf.size/include-large? parsed)))
      (is (= is? (:rf.size/include-sensitive? parsed))))))

(deftest elision-opts-edn-cross-mcp-vocabulary
  ;; Pin the cross-MCP walker-opt vocabulary verbatim. A rename in the
  ;; framework surface needs a co-ordinated update; the test pins the
  ;; literals so drift surfaces here.
  (let [edn (elision/elision-opts-edn false false)]
    ;; Both keys appear in the EDN text (the test pins the LITERAL
    ;; keyword names, not just the parsed form, so a vocabulary
    ;; rename surfaces at the source level).
    (is (not= -1 (.indexOf edn ":rf.size/include-large?")))
    (is (not= -1 (.indexOf edn ":rf.size/include-sensitive?")))))

;; ---------------------------------------------------------------------------
;; stamp-elided-large — counter shape on the envelope.
;; ---------------------------------------------------------------------------

(deftest stamp-elided-large-omits-slot-when-zero
  ;; Zero-elision common path carries no counter. A missing slot
  ;; reads as zero per the cross-MCP indicator-field convention; this
  ;; keeps the agent surface minimal on the hot path.
  (is (= {:db {:k :v}}
         (elision/stamp-elided-large {:db {:k :v}} 0)))
  (is (not (contains? (elision/stamp-elided-large {:db {:k :v}} 0)
                      :elided-large))))

(deftest stamp-elided-large-adds-slot-when-positive
  (is (= {:db {} :elided-large 2}
         (elision/stamp-elided-large {:db {}} 2)))
  (is (= {:db {:k :v} :elided-large 7}
         (elision/stamp-elided-large {:db {:k :v}} 7))))

(deftest stamp-elided-large-non-positive-no-stamp
  ;; Defensive: negative or non-number inputs (shouldn't happen on the
  ;; happy path; defensive against future refactors) leave the
  ;; envelope untouched rather than stamping a nonsense value.
  (is (not (contains? (elision/stamp-elided-large {:db {}} -1)
                      :elided-large)))
  (is (not (contains? (elision/stamp-elided-large {:db {}} nil)
                      :elided-large))))

;; ---------------------------------------------------------------------------
;; apply-to-result — the per-tool boundary wrapper (the MUST 15 site).
;; ---------------------------------------------------------------------------

(deftest apply-to-result-counts-markers-and-stamps-counter
  ;; MUST 15: the boundary wrapper counts markers in the already-
  ;; walked payload and stamps the `:elided-large` envelope counter.
  ;; The wrapper does NOT emit markers (MUST 17 — single normative
  ;; emission site is the walker, which already ran server-side).
  (let [payload {:user {:uploaded-pdf marker}
                 :cart {:items [{:sku "abc"} {:sku "def"}]}}
        out     (elision/apply-to-result {} :db payload)]
    (is (= payload (:db out))
        "the already-walked value rides verbatim under :db")
    (is (= 1 (:elided-large out))
        ":elided-large counter stamped when ≥1 marker present")))

(deftest apply-to-result-no-markers-no-counter
  ;; Counter is absent when zero markers present — the cross-MCP
  ;; indicator-field convention (zero-elision common path is minimal).
  (let [payload {:cart {:items [{:sku "abc"} {:sku "def"}]}}
        out     (elision/apply-to-result {} :db payload)]
    (is (= payload (:db out)))
    (is (not (contains? out :elided-large))
        "clean payload MUST NOT stamp the counter slot")))

(deftest apply-to-result-multiple-markers-counted
  ;; Markers at mixed depths all count. The walker's marker-body-
  ;; opaque rule still holds (the OUTER marker counts once, nested
  ;; marker-shape inside the body does not).
  (let [payload {:slice1 marker
                 :slice2 {:nested marker}
                 :slice3 [{:deep marker}]}
        out     (elision/apply-to-result {} :db payload)]
    (is (= 3 (:elided-large out)))))

(deftest apply-to-result-preserves-existing-envelope-keys
  ;; The wrapper is additive — pre-existing slots on the envelope
  ;; (cursor, next-cursor, mode, etc.) pass through. Same shape
  ;; `privacy/apply-to-result` provides; downstream tools compose the
  ;; two wrappers freely.
  (let [out (elision/apply-to-result
             {:next-cursor "abc" :remaining 42 :mode :summary}
             :db {:user {:uploaded-pdf marker}})]
    (is (= 1 (:elided-large out)))
    (is (= "abc"    (:next-cursor out)))
    (is (= 42       (:remaining out)))
    (is (= :summary (:mode out)))))

(deftest apply-to-result-server-elided-used-verbatim
  ;; Sibling to pair2-mcp's rf2-e35a5 optimisation: when the eval
  ;; form pre-counts markers server-side and ships the integer back
  ;; on the same nREPL round-trip, the wrapper uses it verbatim and
  ;; skips the local walk.
  (let [payload {:db {:k :v}}                      ; no actual markers
        out     (elision/apply-to-result {} :db payload {:server-elided 7})]
    (is (= 7 (:elided-large out))
        "server-side count flows through verbatim, no local re-walk")))

(deftest apply-to-result-server-elided-zero-honoured
  ;; A `0` server-side count must be honoured (not treated as
  ;; "absent ⇒ walk"). Pin the `some?` semantics — zero is a valid
  ;; count, not a sentinel. Same shape pair2-mcp's wire-pipeline
  ;; pins (rf2-e35a5).
  (let [payload {:user {:uploaded-pdf marker}}     ; has a marker
        out     (elision/apply-to-result {} :db payload {:server-elided 0})]
    (is (not (contains? out :elided-large))
        "zero is a valid server-side count, not a fall-back trigger")))

(deftest apply-to-result-missing-server-elided-falls-back-to-walk
  ;; Defensive: when opts omits :server-elided, the wrapper walks the
  ;; payload locally. The counter is correct either way; the
  ;; optimisation is the only thing dropped.
  (let [payload {:user {:uploaded-pdf marker}}
        out     (elision/apply-to-result {} :db payload {})]
    (is (= 1 (:elided-large out))
        "missing :server-elided ⇒ local walk picks up the marker")))

(deftest apply-to-result-shape-for-tree-typed-tools
  ;; Pins the canonical shape every tree-typed tool uses:
  ;;   `:db`    for `get-app-db`
  ;;   `:diff`  for `get-app-db-diff`
  ;;   `:state` for `get-machine-state`
  ;;   `:events` for `subscribe` drain batches
  ;; The same wrapper services all — uniform boundary site.
  (testing "get-app-db-shape (:db)"
    (let [out (elision/apply-to-result
               {} :db {:user {:uploaded-pdf marker}})]
      (is (contains? out :db))
      (is (= 1 (:elided-large out)))))
  (testing "get-app-db-diff-shape (:diff)"
    (let [out (elision/apply-to-result
               {} :diff {:added {:k marker} :removed {}})]
      (is (contains? out :diff))
      (is (= 1 (:elided-large out)))))
  (testing "get-machine-state-shape (:state)"
    (let [out (elision/apply-to-result
               {} :state {:current :running :context {:huge marker}} {})]
      (is (contains? out :state))
      (is (= 1 (:elided-large out)))))
  (testing "subscribe-drain-batch-shape (:events)"
    (let [out (elision/apply-to-result
               {:tick 17} :events
               [{:op :a :payload marker} {:op :b}])]
      (is (= [{:op :a :payload marker} {:op :b}] (:events out)))
      (is (= 1 (:elided-large out)))
      (is (= 17 (:tick out))))))

(deftest apply-to-result-three-arity-omits-opts
  ;; The three-arity convenience shape skips the opts map for the
  ;; common case (no server-side count). Identical behaviour to
  ;; `(apply-to-result envelope key value nil)`.
  (let [payload {:user {:uploaded-pdf marker}}
        out-3   (elision/apply-to-result {} :db payload)
        out-4   (elision/apply-to-result {} :db payload nil)]
    (is (= out-3 out-4))
    (is (= 1 (:elided-large out-3)))))

;; ---------------------------------------------------------------------------
;; Composition with B-1 (privacy filter).
;;
;; The two boundary wrappers ride the same egress codepath. They are
;; designed to compose: both produce additive envelopes, both use
;; unqualified envelope-slot keys (`:dropped-sensitive` /
;; `:elided-large`) per Conventions §Cross-MCP indicator-field
;; vocabulary, and the cross-MCP convention is to surface BOTH counts
;; on the same response when both axes fired. This block pins the
;; interaction explicitly so a future refactor that splits the two
;; surfaces accidentally surfaces here.
;; ---------------------------------------------------------------------------

(deftest both-wrappers-compose-on-same-envelope
  ;; The canonical case: a `subscribe` drain batch containing both
  ;; sensitive events (B-1 drops them) and tree-typed coeffect /
  ;; effect slots with large values (W-6 substitutes markers
  ;; server-side; we count them here). Both indicator counters
  ;; surface on the same envelope.
  (let [events  [{:op :a :payload marker}
                 {:op :b :sensitive? true}
                 {:op :c :payload marker}
                 {:op :d :sensitive? true}]
        ;; First apply B-1 (privacy strip), then W-6 (elision count).
        ;; Order is the spec/004 §6 cascade — sensitive WINS, then
        ;; the size walker runs on the survivors. In practice the
        ;; walker ran server-side and the privacy strip happens at
        ;; MCP boundary; here we test the wrapper composition order.
        envelope (-> {:tick 17}
                     (privacy/apply-to-result :events events false))
        kept     (:events envelope)
        out      (elision/apply-to-result envelope :events kept)]
    (is (= 2 (:dropped-sensitive out))
        "B-1 surfaces dropped-sensitive count")
    (is (= 2 (:elided-large out))
        "W-6 surfaces elided-large count for the survivors")
    (is (= 17 (:tick out))
        "pre-existing envelope slots survive both wrappers")
    (is (= [{:op :a :payload marker} {:op :c :payload marker}]
           (:events out))
        "survivors are the non-sensitive events with markers in place")))

(deftest both-counters-omitted-when-clean-batch
  ;; Both wrappers omit their counter on the zero-drop / zero-elision
  ;; common path. A clean batch through both produces a minimal
  ;; envelope — no indicator slots at all.
  (let [events  [{:op :a} {:op :b}]
        envelope (-> {}
                     (privacy/apply-to-result :events events false))
        kept     (:events envelope)
        out      (elision/apply-to-result envelope :events kept)]
    (is (= events (:events out)))
    (is (not (contains? out :dropped-sensitive))
        "clean batch: no dropped-sensitive counter")
    (is (not (contains? out :elided-large))
        "clean batch: no elided-large counter")))

(deftest only-elision-axis-fired
  ;; W-6 surfaces its counter; B-1 omits its counter. The two slots
  ;; are independent — a tool that walks markers but has no sensitive
  ;; events stamps only `:elided-large`.
  (let [events  [{:op :a :payload marker} {:op :b}]
        envelope (-> {}
                     (privacy/apply-to-result :events events false))
        kept     (:events envelope)
        out      (elision/apply-to-result envelope :events kept)]
    (is (= 1 (:elided-large out)))
    (is (not (contains? out :dropped-sensitive)))))

(deftest only-privacy-axis-fired
  ;; B-1 surfaces its counter; W-6 omits its counter. The two slots
  ;; are independent — a trace-stream tool that drops sensitive
  ;; events but carries no markers stamps only `:dropped-sensitive`.
  (let [events  [{:op :a :sensitive? true} {:op :b}]
        envelope (-> {}
                     (privacy/apply-to-result :events events false))
        kept     (:events envelope)
        out      (elision/apply-to-result envelope :events kept)]
    (is (= 1 (:dropped-sensitive out)))
    (is (not (contains? out :elided-large)))))

(deftest opt-in-on-both-axes-passes-everything-no-counters
  ;; Both opt-in flags ON: B-1 passes sensitive events through, the
  ;; walker (server-side) would pass large values through. Both
  ;; counters absent. The agent has explicitly opted into the full
  ;; payload on both axes.
  (let [events  [{:op :a :sensitive? true} {:op :b}]
        envelope (-> {}
                     ;; B-1 with include-sensitive? true
                     (privacy/apply-to-result :events events true))
        kept     (:events envelope)
        ;; W-6 sees no markers (walker would have been bypassed too)
        out      (elision/apply-to-result envelope :events kept)]
    (is (= events (:events out)))
    (is (not (contains? out :dropped-sensitive)))
    (is (not (contains? out :elided-large)))))

;; ---------------------------------------------------------------------------
;; The load-bearing spec/004 §6 assertion.
;; ---------------------------------------------------------------------------

(deftest spec-004-default-posture-is-elision-on-at-mcp-boundary
  (testing "default (no include-large? arg) emits markers at the
            MCP boundary; the counter surfaces on the envelope"
    (let [;; Default args: include-large? not supplied, walker emits markers.
          ;; (The walker actually fires server-side inside the eval form;
          ;; here we model the post-walk payload — the marker is in place.)
          opts   (cljs.reader/read-string (elision/elision-opts-edn
                                           (elision/parse-include-large nil)))
          ;; Post-walk payload with a marker substituted at the elided slot.
          walked {:user {:uploaded-pdf marker}}
          out    (elision/apply-to-result {} :db walked)]
      (is (false? (:rf.size/include-large? opts))
          "default arg ⇒ walker opt false ⇒ markers emit")
      (is (= 1 (:elided-large out))
          "counter MUST surface the marker count")))
  (testing "explicit `:include-large? true` is the documented
            cross-MCP opt-in (MUST 19 half)"
    (let [opts (cljs.reader/read-string
                (elision/elision-opts-edn
                 (elision/parse-include-large #js {"include-large?" true})))]
      (is (true? (:rf.size/include-large? opts))
          "opt-in path ⇒ walker passes large values through unchanged"))))
