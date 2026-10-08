(ns re-frame.adapter.reagent-slim-derived-container-replaced-cljs-test
  "Spec 006 §`make-derived-value` — `replace-container!` is NOT supported
  on a derived container, exercised against the reagent-slim adapter.

  This is the Reagent-family counterpart to the plain-atom suite at
  `re-frame.substrate.derived-container-replaced-cljs-test`. It pins the
  case the atom-marker heuristic alone CANNOT catch: a reagent-slim
  `Reaction` (the `make-derived-value` result) reifies `IAtom`
  exactly like a base `r/atom`, so the choke point's host-protocol
  fall-back would never fire on it. The ratom adapters therefore publish an
  `:adapter/derived-container?` late-bind hook (keyed on the substrate
  disposal protocol — a `Reaction` is disposable, a base `r/atom` / `RAtom`
  is not), which `re-frame.substrate.adapter/replace-container!` consults
  first.

  Without the adapter-published hook this suite's
  `replace-on-reaction-throws` test FAILS (the guard does not fire and the
  reset! flows through to the Reaction's read-only `-reset!` assert), which
  is exactly the gap an atom-marker-only guard would leave."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [reagent2.ratom :as ratom]
            [re-frame.adapter.reagent-slim :as rf.adapter.reagent-slim]
            [re-frame.interop :as rf.interop]
            [re-frame.late-bind :as rf.late-bind]
            [re-frame.substrate.adapter :as rf.substrate.adapter]
            [re-frame.test-support :as rf.test-support]
            ;; Load the tooling sibling so the listener API's late-bind
            ;; hooks resolve (mirrors the plain-atom suite + trace-listener-test).
            [re-frame.trace.tooling :as rf.trace.tooling]))

(def ^:private reset-runtime
  (rf.test-support/make-reset-runtime-fixture {:adapter rf.adapter.reagent-slim/adapter}))

(defn with-reagent-slim [test-fn]
  ;; Drain reagent2's process-global Reaction flush queue so a source-write
  ;; that enqueued a watching Reaction cannot leak a stale recompute into a
  ;; later test.
  (try
    (reset-runtime test-fn)
    (finally (ratom/flush!))))

(use-fixtures :each with-reagent-slim)

;; ---- helpers --------------------------------------------------------------

(defn- capture-errors [body-fn]
  (let [seen (atom [])
        k    ::reagent-slim-derived-replaced-capture]
    (rf.trace.tooling/register-listener! k (fn [ev]
                                  (when (= :error (:op-type ev))
                                    (swap! seen conj ev))))
    (try (body-fn)
         (finally (rf.trace.tooling/unregister-listener! k)))
    @seen))

(defn- with-derived
  "Build a derived Reaction over `src` projecting `(:n …)`, run `(body src
  derived)`, then dispose the Reaction so its source watch is torn down and
  it can never be re-enqueued onto reagent2's global flush queue after this
  test. Disposal routes through `interop/dispose!` → the slim adapter's
  `:adapter/dispose!` hook, the same path the sub-cache uses."
  [src body]
  (let [derived (rf.substrate.adapter/make-derived-value [src] (fn [v] (:n v)))]
    (try (body derived)
         (finally (rf.interop/dispose! derived)))))

;; ---- tests ----------------------------------------------------------------

(deftest replace-on-base-ratom-succeeds
  (testing "the happy path: writing to a base r/atom works under reagent-slim"
    (let [c (rf.substrate.adapter/make-state-container {:n 0})]
      (is (= [nil {:n 1}]
             [(rf.substrate.adapter/replace-container! c {:n 1})
              (rf.substrate.adapter/read-container c)])
          "replace-container! returns nil and the base container holds the new value"))))

(deftest replace-on-reaction-throws
  (testing "replace-container! on a reagent-slim Reaction throws the canonical ex-info"
    (let [src (rf.substrate.adapter/make-state-container {:n 7})]
      (with-derived src
        (fn [derived]
          (let [thrown (is (thrown? js/Error (rf.substrate.adapter/replace-container! derived 42))
                           "writing to a Reaction throws")]
            (is (= :rf.error/derived-container-replaced
                   (:rf.error/id (ex-data thrown)))
                "the thrown ex-info carries the canonical :rf.error/id discriminator")))))))

(deftest reaction-value-unchanged-after-rejected-write
  (testing "the rejected write does NOT mutate the derived value — the adapter replace-container! is never invoked"
    (let [src (rf.substrate.adapter/make-state-container {:n 5})]
      (with-derived src
        (fn [derived]
          (let [seeded   (rf.substrate.adapter/read-container derived)
                rejected (do (try (rf.substrate.adapter/replace-container! derived 1000)
                                  (catch :default _ nil))
                             (rf.substrate.adapter/read-container derived))]
            (rf.substrate.adapter/replace-container! src {:n 6})
            (is (= [5 5 6] [seeded rejected (rf.substrate.adapter/read-container derived)])
                "[seeded after-rejected-write after-source-write]: the rejected write left the value alone, and the source stays writable")))))))

;; `route-hook!` routes by the adapter's `:kind` token, not object identity,
;; so a copied or wrapped adapter map still drives the live
;; `:adapter/derived-container?` hook; routing by identity would leave a
;; Reaction writable under the copy.

(deftest copied-adapter-map-routes-to-live-derived-container-hook
  (testing "a copied reagent-slim adapter map still drives the live :adapter/derived-container? hook"
    (let [original (rf.substrate.adapter/current-adapter)
          copied   (assoc rf.adapter.reagent-slim/adapter :rf.test/instrumentation-wrapper true)]
      (try
        (rf.substrate.adapter/dispose-adapter!)
        (rf.substrate.adapter/install-adapter! copied)
        (is (= [false :rf.adapter/reagent-slim]
               [(identical? rf.adapter.reagent-slim/adapter (rf.substrate.adapter/current-adapter))
                (:kind (rf.substrate.adapter/current-adapter))])
            "precondition: the installed copy is a distinct map with the canonical :kind")
        (let [hook (rf.late-bind/get-fn :adapter/derived-container?)
              src  (rf.substrate.adapter/make-state-container {:n 1})]
          (with-derived src
            (fn [derived]
              (is (= [false true] [(boolean (hook src)) (boolean (hook derived))])
                  "under the copied map the live hook still tells a base r/atom from a Reaction")
              (is (thrown? js/Error (rf.substrate.adapter/replace-container! derived 42))
                  "replace-container! on the Reaction STILL throws under the copied map"))))
        (finally
          (rf.substrate.adapter/dispose-adapter!)
          (rf.substrate.adapter/install-adapter! original))))))
