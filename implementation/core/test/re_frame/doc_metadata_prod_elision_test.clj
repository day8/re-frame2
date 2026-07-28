(ns re-frame.doc-metadata-prod-elision-test
  "Per rf2-9wwkcm: pure-documentation registration metadata (`:doc`)
  production-elision contract (Spec 001 §Production elision contract).

  `:doc` is the one PURE-documentation registration-metadata key — zero
  production runtime use, zero production observability use. Under the
  JVM debug-gate-off posture (`interop/debug-enabled?` rebound to `false`,
  semantically equivalent to CLJS `:advanced` + `goog.DEBUG=false`),
  `(rf/handler-meta kind id)` MUST NOT carry `:doc`; in dev (default gate
  on) `:doc` is retained for tooling / agent inspection.

  Load-bearing keys (`:tags`, `:schema`, `:sensitive?` / `:large?`, the
  resource-mutation runtime keys, `:rf/id`, the handler fn) MUST be
  retained in prod — they drive runtime behaviour / redaction / egress
  projection. This suite pins both halves of the elidable-vs-retained
  classification.

  Naming convention: `_test.clj` (JVM-only) by design — the JVM is where
  we can `with-redefs` the gate to model the production posture. The
  canonical CLJS-prod bundle-string absence rides the elision probe
  (`scripts/check-elision.cjs`, the `rf2-9wwkcm-doc-elision-sentinel`
  sentinel): a runtime strip cannot DCE a user-authored call-site string,
  so the reg-* macros gate any LITERAL doc-bearing metadata-map under
  `interop/debug-enabled?` and the bundle grep pins that the dev `:doc`
  string DCEs. This file pins the SEMANTIC handler-meta contract.

  ## Posture split (rf2-d2841)

  The PROD half of this contract — `:doc` stripped, load-bearing keys
  retained, the elidable set closed to `#{:doc}` — is posture-independent
  and runs in BOTH `clojure -M:test` and `scripts/test-core-prod-gate.sh`
  (the `-Dre-frame.debug=false` lane). Under the real gate those assertions
  get STRONGER, not weaker: the `with-redefs` models the gate in the dev
  lane, while the prod lane has the load-time flag genuinely off, so the
  same assertion is re-run against the real thing. Note this is NOT the
  vacuous class-4 shape (a negative about a wholesale-elided key): `:doc`
  is only absent here because `register!` stripped it from a map the caller
  really did supply, and `doc-retained-in-handler-meta-under-enabled-debug-
  gate` in the dev lane proves the same call site retains it when the gate
  is on. The subject exists in both postures; only the strip differs.

  The DEV half — `:doc` RETAINED for tooling / agent inspection, and
  `strip-pure-documentation`'s dev identity — is a claim about the gate
  being ON. Under `-Dre-frame.debug=false` there is nothing to retain, by
  design, so those assertions are kept verbatim inside a
  `(when interop/debug-enabled? …)` arm marked `rf2-d2841`."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.interop :as interop]
            [re-frame.registrar :as registrar]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter plain-atom/adapter}))

;; ---- runtime strip: :doc absent from handler-meta in prod ---------------

(deftest doc-stripped-from-handler-meta-under-disabled-debug-gate
  (testing "Per rf2-9wwkcm: under `:advanced + goog.DEBUG=false` (modelled
            as `with-redefs [interop/debug-enabled? false]`) `:doc` is
            stripped from the stored registration metadata, so
            `(rf/handler-meta ...)` carries no `:doc` in production."
    (with-redefs [interop/debug-enabled? false]
      (rf/reg-event :rf2-9wwkcm/prod-event
                       {:doc "elided in prod" :schema :int :tags #{:probe}}
                       (fn [{:keys [db]} _] {:db db}))
      (let [meta (rf/handler-meta :event :rf2-9wwkcm/prod-event)]
        (is (some? meta))
        (is (not (contains? meta :doc))
            ":doc absent from handler-meta in prod")
        ;; Load-bearing keys remain — only the pure-documentation key goes.
        (is (= :int (:schema meta))
            ":schema retained in prod (source for precomputed marks + dev introspection)")
        (is (= #{:probe} (:tags meta))
            ":tags retained in prod (runtime: containment subs / invalidation)")))))

(deftest doc-retained-in-handler-meta-under-enabled-debug-gate
  (testing "Per rf2-9wwkcm: the dev posture (default `interop/debug-enabled?`
            = true) retains `:doc` for tooling / agent inspection."
    (rf/reg-event :rf2-9wwkcm/dev-event
                     {:doc "kept in dev"}
                     (fn [{:keys [db]} _] {:db db}))
    (let [meta (rf/handler-meta :event :rf2-9wwkcm/dev-event)]
      ;; PRODUCTION WITNESS (rf2-d2841). The dev claim below is about a key
      ;; the gate elides, so guarded alone it would leave this deftest
      ;; executing NOTHING under `-Dre-frame.debug=false`. The always-on
      ;; half is that the doc-bearing metadata-map ARITY still registers a
      ;; live handler: the reg-* macro rewrites a literal doc-map under the
      ;; gate (`core_reg_macros/gate-doc-arg`), so a rewrite that dropped
      ;; the registration — or mis-shaped the residual map — would surface
      ;; here and nowhere else in this file.
      (is (some? meta)
          "the {:doc …} metadata-map arity registers in BOTH postures")
      (is (ifn? (:handler-fn meta))
          "the handler-fn survives the doc-map rewrite in BOTH postures")
      (is (= {:db {:seen true}}
             ((:handler-fn meta) {:db {:seen true}} [:rf2-9wwkcm/dev-event]))
          "the registered handler is the one the call site supplied")
      ;; rf2-d2841 — dev-instrumentation arm (see ns docstring §Posture
      ;; split). `:doc` is pure-documentation metadata, stripped at the
      ;; `register!` chokepoint under the production gate.
      (when interop/debug-enabled?
        (is (= "kept in dev" (:doc meta))
            ":doc retained in dev for tooling / agent inspection")))))

;; ---- universality: every reg-* surface funnels through register! --------

(deftest doc-stripped-uniformly-across-reg-surfaces
  (testing "Per rf2-9wwkcm: the strip lives at the single `register!`
            chokepoint, so it covers EVERY reg-* surface uniformly — subs,
            fx, cofx, … not just events."
    (with-redefs [interop/debug-enabled? false]
      (rf/reg-sub :rf2-9wwkcm/prod-sub
                  {:doc "sub doc elided"}
                  (fn [db _] db))
      (rf/reg-fx :rf2-9wwkcm/prod-fx
                 {:doc "fx doc elided"}
                 (fn [_] nil))
      (rf/reg-cofx :rf2-9wwkcm/prod-cofx
                   {:doc "cofx doc elided"}
                   (fn [ctx] ctx))
      (is (not (contains? (rf/handler-meta :sub  :rf2-9wwkcm/prod-sub)  :doc))
          ":doc absent from sub handler-meta in prod")
      (is (not (contains? (rf/handler-meta :fx   :rf2-9wwkcm/prod-fx)   :doc))
          ":doc absent from fx handler-meta in prod")
      (is (not (contains? (rf/handler-meta :cofx :rf2-9wwkcm/prod-cofx) :doc))
          ":doc absent from cofx handler-meta in prod"))))

;; ---- the strip helper, in isolation -------------------------------------

(deftest strip-pure-documentation-helper-semantics
  (testing "Per rf2-9wwkcm: `strip-pure-documentation` drops `:doc` (and
            only the pure-documentation keys) in prod, and is an identity
            in dev. A non-map passes through untouched."
    (with-redefs [interop/debug-enabled? false]
      (is (= {:schema :int :tags #{:a}}
             (registrar/strip-pure-documentation
               {:doc "x" :schema :int :tags #{:a}}))
          "prod: only :doc stripped; load-bearing keys retained")
      (is (= {} (registrar/strip-pure-documentation {:doc "x"}))
          "prod: doc-only map strips to empty")
      (is (nil? (registrar/strip-pure-documentation nil))
          "non-map (nil) passes through"))
    ;; rf2-d2841 — dev-instrumentation arm (see ns docstring §Posture
    ;; split). "Identity in dev" is a claim about the gate being ON;
    ;; under `-Dre-frame.debug=false` the helper is the strip, by design.
    (when interop/debug-enabled?
      (is (= {:doc "x" :schema :int}
             (registrar/strip-pure-documentation {:doc "x" :schema :int}))
          "dev: full map retained (identity)"))))

;; Note (rf2-tfiutq): the `reg-machine` LITERAL opts-map `:doc` elision lands
;; here too — but the runtime handler-meta strip rides the SAME single
;; `register!` chokepoint exercised by `doc-stripped-uniformly-across-reg-
;; surfaces` above, and the machine surface needs `day8/re-frame2-machines` on
;; the classpath (absent from this core artefact's `:test` deps). The semantic
;; strip for `reg-machine` opts therefore lives in the machines artefact
;; (`re-frame.machine-doc-opts-prod-elision-test`), and the CLJS-bundle `:doc`
;; STRING absence — the bytes the macro's new `gate-doc-arg` routing DCEs — is
;; pinned by the elision probe (`rf2-tfiutq-machine-opts-doc-sentinel`).

;; ---- classification: the elidable set is closed to :doc -----------------

(deftest pure-documentation-keys-is-closed-to-doc
  (testing "Per rf2-9wwkcm / Spec 001 §Production elision contract: the
            elidable (pure-documentation) set is exactly `#{:doc}`. Every
            other standard registration-metadata key is load-bearing in
            production and MUST NOT be in this set."
    (is (= #{:doc} registrar/pure-documentation-keys)
        "the elidable set is exactly #{:doc}")
    (doseq [load-bearing [:schema :data-schema :tags :interceptors :sensitive?
                          :large? :rf/id :handler-fn :request :transport
                          :scope :params-schema :invalidates :populates]]
      (is (not (contains? registrar/pure-documentation-keys load-bearing))
          (str load-bearing " is load-bearing — must NOT be elidable")))))
