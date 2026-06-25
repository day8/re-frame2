(ns re-frame.machine-doc-opts-prod-elision-test
  "Per rf2-tfiutq: `reg-machine`'s LITERAL opts-map pure-documentation
  (`:doc`) production-elision contract — the machines-artefact counterpart of
  `re-frame.doc-metadata-prod-elision-test` (which pins the splice-through
  reg-* surfaces but cannot exercise `reg-machine`, since that needs
  `day8/re-frame2-machines` on the classpath, absent from core's `:test` deps).

  Two halves of the contract:

   1. SEMANTIC handler-meta strip — `(reg-machine :id {:doc \"…\"} spec)`
      registers under `:event`, funnelling through the single
      `re-frame.registrar/register!` `strip-pure-documentation` chokepoint, so
      under the production posture (`interop/debug-enabled?` rebound to false,
      semantically equivalent to CLJS `:advanced` + `goog.DEBUG=false`)
      `(rf/handler-meta :event id)` carries no `:doc`; in dev it is retained.

   2. CLJS BUNDLE-string DCE — the macro's `expand-reg-machine` routes a literal
      doc-bearing opts map through `gate-doc-arg`, emitting an
      `(if interop/debug-enabled? <full-opts> <opts-without-:doc>)` gate Closure
      constant-folds, so the user-authored `:doc` STRING bytes DCE from the
      :advanced bundle (a runtime strip cannot DCE a call-site string). That
      half is pinned by the elision probe (`scripts/check-elision.cjs`, the
      `rf2-tfiutq-machine-opts-doc-sentinel` sentinel), not this JVM suite.

  This file pins the SEMANTIC half for the machine surface."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.interop :as interop]
            ;; Side-effect require: loads the machines artefact and publishes the
            ;; machine-registration late-bind hook `rf/reg-machine` resolves.
            [re-frame.machines]
            [re-frame.machines.test-support :as mtest]
            [re-frame.substrate.plain-atom :as plain-atom]))

(use-fixtures :each
  (mtest/make-reset-runtime-fixture {:adapter plain-atom/adapter}))

(deftest reg-machine-opts-doc-stripped-under-disabled-debug-gate
  (testing "Per rf2-tfiutq: a `(reg-machine :id {:doc \"…\"} spec)` literal
            opts-map `:doc` is stripped from the stored registration metadata
            in prod, while a load-bearing opts key (`:schema`, the event-vector
            validator) is retained."
    (with-redefs [interop/debug-enabled? false]
      (rf/reg-machine :rf2-tfiutq/prod-machine
        {:doc "machine opts doc elided" :schema [:tuple :keyword]}
        {:initial :idle
         :states  {:idle {}}})
      (let [meta (rf/handler-meta :event :rf2-tfiutq/prod-machine)]
        (is (some? meta))
        (is (not (contains? meta :doc))
            ":doc absent from machine (event) handler-meta in prod")
        (is (= [:tuple :keyword] (:schema meta))
            ":schema retained in prod (load-bearing event-vector validation)")))))

(deftest reg-machine-opts-doc-retained-under-enabled-debug-gate
  (testing "Per rf2-tfiutq: the dev posture (default gate on) retains the
            `reg-machine` opts-map `:doc` for tooling / agent inspection."
    (rf/reg-machine :rf2-tfiutq/dev-machine
      {:doc "machine opts doc kept in dev"}
      {:initial :idle
       :states  {:idle {}}})
    (let [meta (rf/handler-meta :event :rf2-tfiutq/dev-machine)]
      (is (= "machine opts doc kept in dev" (:doc meta))
          ":doc retained in dev for tooling / agent inspection"))))
