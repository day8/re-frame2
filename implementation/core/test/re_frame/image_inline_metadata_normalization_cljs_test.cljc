(ns re-frame.image-inline-metadata-normalization-cljs-test
  "rf2-mt1cvi — inline image metadata is DOC-normalized UNIFORMLY across every
  supported inline registration kind: event, sub, fx, and cofx.

  The registrar contract makes `:doc` PURE documentation stripped in production
  for every `reg-*` surface (`registrar/strip-pure-documentation`). Inline image
  assembly previously applied that contract to `:reg-sub` ONLY: a production-mode
  reproduction through the REAL `re-frame.image` + `image-assembly/lower-inline-
  descriptor` yielded `:reg-event` with `:doc` at BOTH the descriptor top level
  AND its nested `:metadata`, and `:reg-fx` / `:reg-cofx` with `:doc` under
  nested `:metadata` — only `:reg-sub` was clean. That paid unwanted production
  bytes and made inline registration semantics kind-dependent (bead rf2-mt1cvi,
  the cross-kind follow-up exposed by PR #5902).

  This suite is the CROSS-KIND matrix + the production-elision SENTINEL. For each
  supported inline kind it drives the LIVE image + assembly-side lowering (the
  runnable descriptor a frame actually resolves — NOT a direct lowerer call) with
  an adversarial `:doc` + a load-bearing witness key, and asserts:

    * DEV (gate on): authored `:doc` survives — under the nested `:metadata`
      (every kind), and at the top level for the kinds whose lowering spreads
      authored metadata there (event, sub);
    * PRODUCTION (gate off): `:doc` is absent at the descriptor top level AND
      under nested `:metadata` for EVERY kind, while the load-bearing witness key
      and the runnable slots are retained.

  A mutation that reintroduces the raw metadata merge for any kind (dropping the
  uniform `strip-descriptor-documentation` boundary) fails the production rows.

  `.cljc` — the normalization is host-agnostic, so this runs under both
  `clojure -M:test` (JVM) and `npm run test:cljs` (node CLJS). The four core kind
  namespaces are required so their `:image/lower-inline-<kind>` publishers are
  installed (they `set-fn!` at ns load); image-assembly cannot static-require
  them (a cycle), so the test requires them directly to exercise the LIVE
  lowering.

  ## Posture split (rf2-d2841)

  Section 1's SUBJECT is the dev half of the contract — authored `:doc`
  surviving for tooling — and it is unreachable under
  `scripts/test-core-prod-gate.sh`, where `rf.interop/debug-enabled?` is already
  false at load and `registrar/strip-pure-documentation` has removed `:doc`
  before the descriptor is built. The two `:doc` rows are therefore kept
  verbatim inside a `(when rf.interop/debug-enabled? …)` arm.

  Everything else in section 1 is posture-independent and STAYS OUTSIDE it —
  the `:kind` echo, the load-bearing `[:audit]` witness key nested and (for the
  spreading kinds) hoisted, and the runnable `:handler-fn` slot. Those are what
  make sections 2 and 3 mean something: without them \"no `:doc` anywhere\"
  would also be satisfied by a lowering that produced nothing at all.

  Sections 2 and 3 run in BOTH postures unchanged. Their
  `with-redefs [rf.interop/debug-enabled? false]` is a no-op under the real gate
  (the var is already false) and the assertions are the production claim
  itself, so they are load-bearing on both sides."
  (:require [clojure.test :refer [deftest is testing]]
            [re-frame.image          :as rf.image]
            [re-frame.image-assembly :as rf.image-assembly]
            [re-frame.interop        :as rf.interop]
            ;; Required so the late-bind lowering publishers are installed
            ;; (each ns calls (late-bind/set-fn! :image/lower-inline-<kind> …) at
            ;; load); exercised through image-assembly/lower-inline-descriptor.
            [re-frame.events]
            [re-frame.subs]
            [re-frame.fx]
            [re-frame.cofx]))

;; ---------------------------------------------------------------------------
;; The supported-kind matrix. `:spreads-meta?` marks the kinds whose lowering
;; hoists authored metadata onto the descriptor TOP LEVEL (event via
;; `event-handler-meta`, sub via the normalized-metadata spread) — those kinds
;; must be clean of `:doc` at the top level too, not just under `:metadata`.
;; ---------------------------------------------------------------------------

(def ^:private supported-kinds
  [{:label "event" :kind :event :section :reg-event :spreads-meta? true
    :body (fn [_cofx _event] {})}
   {:label "sub"   :kind :sub   :section :reg-sub   :spreads-meta? true
    :body (fn [_db _query] :ok)}
   {:label "fx"    :kind :fx    :section :reg-fx    :spreads-meta? false
    :body (fn [_args] nil)}
   {:label "cofx"  :kind :cofx  :section :reg-cofx  :spreads-meta? false
    :body (fn [] :v)}])

(defn- assemble
  "Assemble ONE inline registration of `section` through the LIVE `rf/image` +
  assembly-side lowering and return the runnable descriptor a frame resolves.
  `metadata` nil uses the 2-tuple `[id body]` form."
  [section id metadata body]
  (-> (rf.image/image
        {:id            :mt1cvi/inline-image
         :registrations {section [(if (nil? metadata) [id body] [id metadata body])]}})
      :rf.image/inline
      first
      rf.image-assembly/lower-inline-descriptor))

;; ===========================================================================
;; 1. DEV — authored documentation survives for every kind (tooling / agent
;;    inspection). The default gate is ON.
;; ===========================================================================

(deftest dev-retains-authored-doc-across-every-kind
  (doseq [{:keys [label kind section spreads-meta? body]} supported-kinds]
    (testing (str "inline " label " — dev retains authored :doc")
      (let [d (assemble section (keyword "counter" (str label "-doc"))
                        {:doc "author note" :tags [:audit]} body)]
        (is (= kind (:kind d)) "kind is preserved")
        ;; rf2-d2841 — `:doc` retention is the DEV half of the contract, elided
        ;; at source under -Dre-frame.debug=false. Kept verbatim.
        (when rf.interop/debug-enabled?
          (is (= "author note" (get-in d [:metadata :doc]))
              "nested [:metadata :doc] retained in dev"))
        (is (= [:audit] (get-in d [:metadata :tags]))
            "the load-bearing witness key is nested")
        (is (fn? (:handler-fn d)) "the runnable :handler-fn slot is installed")
        (when spreads-meta?
          (when rf.interop/debug-enabled?
            (is (= "author note" (:doc d))
                "kinds that spread metadata carry :doc at the top level too in dev"))
          (is (= [:audit] (:tags d))
              "and the load-bearing witness key at the top level"))))))

;; ===========================================================================
;; 2. PRODUCTION SENTINEL — `:doc` is elided at BOTH the top level and under
;;    nested `:metadata` for EVERY kind; load-bearing keys + runnable slots
;;    survive. A mutation reintroducing the raw-metadata merge fails here.
;; ===========================================================================

(deftest production-strips-doc-everywhere-across-every-kind
  (with-redefs [rf.interop/debug-enabled? false]
    (doseq [{:keys [label kind section spreads-meta? body]} supported-kinds]
      (testing (str "inline " label " — production carries NO :doc anywhere")
        (let [d (assemble section (keyword "counter" (str label "-prod"))
                          {:doc "elided in prod" :tags [:audit]} body)]
          (is (= kind (:kind d)) "kind is preserved")
          (is (not (contains? d :doc))
              "no top-level :doc in a production image, for any kind")
          (is (not (contains? (:metadata d) :doc))
              "no dev-only :doc under nested [:metadata …] in a production image")
          (is (= [:audit] (get-in d [:metadata :tags]))
              "the load-bearing witness key is retained under nested :metadata")
          (is (fn? (:handler-fn d)) "the runnable :handler-fn slot survives")
          (when spreads-meta?
            (is (not (contains? d :doc))
                "no top-level :doc even where the lowering spreads metadata")
            (is (= [:audit] (:tags d))
                "the load-bearing key is retained at the top level too")))))))

;; ===========================================================================
;; 3. PRODUCTION — a doc-ONLY inline entry resurrects no stale nested map for
;;    ANY kind (the nested :metadata reduces to empty and is dropped).
;; ===========================================================================

(deftest production-doc-only-metadata-drops-the-nested-map-across-every-kind
  (with-redefs [rf.interop/debug-enabled? false]
    (doseq [{:keys [label section body]} supported-kinds]
      (testing (str "inline " label " — a doc-ONLY inline entry drops :metadata")
        (let [d (assemble section (keyword "counter" (str label "-doc-only"))
                          {:doc "only a note"} body)]
          (is (not (contains? d :doc))
              "no top-level :doc for a doc-only inline entry in production")
          (is (not (contains? (:metadata d) :doc))
              "no nested [:metadata :doc] — the doc-only map is not resurrected")
          (is (nil? (:metadata d))
              "the nested :metadata map is dropped once it reduces to empty")
          (is (fn? (:handler-fn d))
              "the runnable slot is still installed for a doc-only entry"))))))
