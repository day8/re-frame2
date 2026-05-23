(ns re-frame.adapter.reagent-slim-derived-container-replaced-cljs-test
  "Spec 006 §`make-derived-value` — `replace-container!` is NOT supported
  on a derived container, exercised against the reagent-slim adapter
  (rf2-8wrzz.3).

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
  is exactly the regression the prior atom-marker-only implementation
  carried.

  Per bead rf2-8wrzz.3."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [reagent2.ratom :as ratom]
            [re-frame.adapter.reagent-slim :as reagent-slim]
            [re-frame.interop :as interop]
            [re-frame.late-bind :as late-bind]
            [re-frame.substrate.adapter :as adapter]
            [re-frame.test-support :as test-support]
            [re-frame.trace :as trace]
            ;; Load the tooling sibling so the listener API's late-bind
            ;; hooks resolve (mirrors the plain-atom suite + trace-listener-test).
            [re-frame.trace.tooling]))

;; ---- fixture --------------------------------------------------------------
;; Cold-start each test with the reagent-slim adapter installed so the
;; choke point's `:else` happy-path branch resolves and the routed
;; `:adapter/derived-container?` hook answers for this adapter.
;;
;; Uses `make-reset-runtime-fixture` (NOT a hand-rolled `registrar/clear-all!`)
;; — it snapshots and RESTORES the registrar around the test so ns-load-time
;; global registrations other test files depend on (the machines spawn
;; wrapper, routing handlers, …) survive. A `clear-all!` would wipe them and
;; strand every subsequent machine / routing test in the shared node process.
;; The reagent2 process-global Reaction flush queue is drained in :after so a
;; source-write that enqueued a watching Reaction doesn't leak a stale
;; recompute into a later React-adapter test.

(def ^:private reset-runtime
  (test-support/make-reset-runtime-fixture {:adapter reagent-slim/adapter}))

(defn with-reagent-slim [test-fn]
  ;; Drain reagent2's process-global Reaction flush queue around the reset
  ;; fixture so a source-write that enqueued a watching Reaction doesn't leak
  ;; a stale recompute into a later React-adapter test.
  (try
    (reset-runtime test-fn)
    (finally (ratom/flush!))))

(use-fixtures :each with-reagent-slim)

;; ---- helpers --------------------------------------------------------------

(defn- capture-errors [body-fn]
  (let [seen (atom [])
        k    ::reagent-slim-derived-replaced-capture]
    (trace/register-listener! k (fn [ev]
                                  (when (= :error (:op-type ev))
                                    (swap! seen conj ev))))
    (try (body-fn)
         (finally (trace/unregister-listener! k)))
    @seen))

(defn- with-derived
  "Build a derived Reaction over `src` projecting `(:n …)`, run `(body src
  derived)`, then dispose the Reaction so its source watch is torn down and
  it can never be re-enqueued onto reagent2's global flush queue after this
  test. Disposal routes through `interop/dispose!` → the slim adapter's
  `:adapter/dispose!` hook, the same path the sub-cache uses."
  [src body]
  (let [derived (adapter/make-derived-value [src] (fn [v] (:n v)))]
    (try (body derived)
         (finally (interop/dispose! derived)))))

;; ---- tests ----------------------------------------------------------------

(deftest derived-container-hook-separates-reaction-from-base-ratom
  (testing "the routed :adapter/derived-container? hook flags a Reaction (derived) but not an r/atom (base)"
    (let [hook (late-bind/get-fn :adapter/derived-container?)]
      (is (some? hook) "the :adapter/derived-container? hook is published")
      (let [src (adapter/make-state-container {:n 1})]
        (with-derived src
          (fn [derived]
            ;; Read the derived value once so the reaction baseline is seeded.
            (is (= 1 (adapter/read-container derived)) "precondition: derived reads its computed value")
            (is (false? (boolean (hook src)))
                "a base r/atom is NOT a derived container (even though it reifies IAtom)")
            (is (true? (boolean (hook derived)))
                "a Reaction IS a derived container (the case the atom-marker heuristic misses)")))))))

(deftest replace-on-base-ratom-succeeds
  (testing "the happy path is untouched: writing to a base r/atom still works under reagent-slim"
    (let [c (adapter/make-state-container {:n 0})]
      (is (= {:n 0} (adapter/read-container c)) "precondition")
      (is (nil? (adapter/replace-container! c {:n 1}))
          "replace-container! on a base container returns nil")
      (is (= {:n 1} (adapter/read-container c))
          "the base container holds the new value"))))

(deftest replace-on-reaction-throws
  (testing "replace-container! on a reagent-slim Reaction throws the canonical ex-info"
    (let [src (adapter/make-state-container {:n 7})]
      (with-derived src
        (fn [derived]
          (is (= 7 (adapter/read-container derived))
              "precondition: the derived container reads its computed value")
          (let [thrown (is (thrown? js/Error (adapter/replace-container! derived 42))
                           "writing to a Reaction throws")]
            (is (= :rf.error/derived-container-replaced
                   (:rf.error/id (ex-data thrown)))
                "the thrown ex-info carries the canonical :rf.error/id discriminator")
            (is (= 'rf/replace-container! (:where (ex-data thrown)))
                "the :where slot names the user-facing surface fn")
            (is (= :no-recovery (:recovery (ex-data thrown)))
                "the :recovery slot is :no-recovery")
            (is (= ":rf.error/derived-container-replaced" (ex-message thrown))
                "the ex-message stringifies the discriminator keyword")))))))

(deftest replace-on-reaction-emits-error-trace
  (testing "replace-container! on a Reaction emits the :rf.error/derived-container-replaced trace"
    (let [src (adapter/make-state-container {:n 1})]
      (with-derived src
        (fn [derived]
          (let [errs (capture-errors
                       (fn []
                         (try (adapter/replace-container! derived 99)
                              (catch :default _ nil))))
                ev   (first (filter #(= :rf.error/derived-container-replaced (:operation %)) errs))]
            (is (some? ev) "a :rf.error/derived-container-replaced error trace was emitted")
            (is (= :error (:op-type ev)) "the trace's op-type is :error")
            (is (= :rf.error/derived-container-replaced (get-in ev [:tags :category]))
                "the :category tag mirrors :operation")
            (is (= :no-recovery (:recovery ev)) "the :recovery field is :no-recovery")
            (is (string? (get-in ev [:tags :reason])) "the :reason tag is a sentence")))))))

(deftest reaction-value-unchanged-after-rejected-write
  (testing "the rejected write does NOT mutate the derived value — the adapter replace-container! is never invoked"
    (let [src (adapter/make-state-container {:n 5})]
      (with-derived src
        (fn [derived]
          (is (= 5 (adapter/read-container derived)) "seed the reaction baseline")
          (try (adapter/replace-container! derived 1000)
               (catch :default _ nil))
          (is (= 5 (adapter/read-container derived))
              "the derived value still reflects its source — the write was rejected")
          ;; Writing to the SOURCE flows through to the derived value, proving the
          ;; source remains a normal writable container and the guard fires only
          ;; on the Reaction shape.
          (adapter/replace-container! src {:n 6})
          (is (= 6 (adapter/read-container derived))
              "writing to the source recomputes the derived value normally"))))))
