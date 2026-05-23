(ns re-frame.substrate.derived-container-replaced-cljs-test
  "Spec 006 §`make-derived-value` — `replace-container!` is NOT supported
  on a derived container (rf2-8wrzz.3).

  A derived container is a value computed from its source container(s)
  (the result of `make-derived-value`). It supports `read-container` but
  has no writable slot, so calling `replace-container!` on one is a
  programmer error. The core's `replace-container!` choke point (the
  single point every frame app-db write flows through —
  `re-frame.substrate.adapter/replace-container!`) detects the
  non-writable container, emits a `:rf.error/derived-container-replaced`
  trace (op-type `:error`, per Spec 009 §The error event shape), AND
  throws the canonical thrown-error ex-info carrying
  `:rf.error/id :rf.error/derived-container-replaced` (per Spec 009 §The
  thrown-error shape). The underlying adapter `replace-container!` is NOT
  invoked.

  This suite runs against the plain-atom adapter so it exercises both
  host predicate branches in `replaceable-container?`: the `.cljc`
  shape runs under the `:node-test` build (ns ends in `cljs-test` → the
  `:cljs` `(satisfies? ISwap …)` branch) AND the JVM cognitect runner
  (the `:clj` `(instance? clojure.lang.IAtom …)` branch).

  Per bead rf2-8wrzz.3."
  (:require #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
               :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
            [re-frame.substrate.adapter :as adapter]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]
            [re-frame.trace :as trace]
            ;; Load the tooling sibling so the late-bind hooks behind the
            ;; listener API resolve on both runtimes (mirrors
            ;; trace-listener-test).
            [re-frame.trace.tooling]))

;; ---- fixture --------------------------------------------------------------
;; Cold-start each test with the plain-atom adapter installed: the choke
;; point's `:else` branch calls `require-adapter!`, so an adapter must be
;; seated for the base-container happy path to resolve.
;;
;; Uses `make-reset-runtime-fixture` (NOT a hand-rolled `registrar/clear-all!`)
;; — it snapshots and RESTORES the registrar around the test so ns-load-time
;; global registrations other test files depend on (the machines spawn
;; wrapper, routing handlers, …) survive across the shared node-test process.
;; A `clear-all!` would wipe them and strand every subsequent machine /
;; routing test that relies on ns-load registration.

(use-fixtures :each
  (test-support/make-reset-runtime-fixture {:adapter plain-atom/adapter}))

;; ---- helpers --------------------------------------------------------------

(defn- capture-errors
  "Register a trace listener that records every `:error` op-type event,
  run `body-fn`, then unregister and return the captured vector."
  [body-fn]
  (let [seen (atom [])
        k    ::derived-replaced-capture]
    (trace/register-listener! k (fn [ev]
                                  (when (= :error (:op-type ev))
                                    (swap! seen conj ev))))
    (try (body-fn)
         (finally (trace/unregister-listener! k)))
    @seen))

;; ---- tests ----------------------------------------------------------------

(deftest replace-on-base-container-succeeds
  (testing "the happy path is untouched: writing to a base container still works"
    (let [c (adapter/make-state-container {:n 0})]
      (is (= {:n 0} (adapter/read-container c)) "precondition")
      (is (nil? (adapter/replace-container! c {:n 1}))
          "replace-container! on a base container returns nil")
      (is (= {:n 1} (adapter/read-container c))
          "the base container holds the new value"))))

(deftest replace-on-derived-container-throws
  (testing "replace-container! on a derived container throws the canonical ex-info"
    (let [src     (adapter/make-state-container {:n 7})
          derived (adapter/make-derived-value [src] (fn [v] (:n v)))]
      (is (= 7 (adapter/read-container derived))
          "precondition: the derived container reads its computed value")
      (let [thrown (is (thrown? #?(:clj clojure.lang.ExceptionInfo :cljs js/Error)
                                (adapter/replace-container! derived 42))
                       "writing to a derived container throws")]
        (is (= :rf.error/derived-container-replaced
               (:rf.error/id (ex-data thrown)))
            "the thrown ex-info carries the canonical :rf.error/id discriminator")
        (is (= 'rf/replace-container! (:where (ex-data thrown)))
            "the :where slot names the user-facing surface fn")
        (is (= :no-recovery (:recovery (ex-data thrown)))
            "the :recovery slot is :no-recovery")
        (is (string? (:reason (ex-data thrown)))
            "the :reason slot is a human-readable sentence")
        (is (= ":rf.error/derived-container-replaced"
               (ex-message thrown))
            "the ex-message stringifies the discriminator keyword")))))

(deftest replace-on-derived-container-emits-error-trace
  (testing "replace-container! on a derived container emits the :rf.error/derived-container-replaced trace"
    (let [src     (adapter/make-state-container {:n 1})
          derived (adapter/make-derived-value [src] (fn [v] (:n v)))
          errs    (capture-errors
                    (fn []
                      ;; The choke point emits the trace BEFORE it throws;
                      ;; swallow the throw so we can inspect the captured
                      ;; trace event.
                      (try (adapter/replace-container! derived 99)
                           (catch #?(:clj Throwable :cljs :default) _ nil))))
          ev      (first (filter #(= :rf.error/derived-container-replaced (:operation %)) errs))]
      (is (some? ev)
          "a :rf.error/derived-container-replaced error trace was emitted")
      (is (= :error (:op-type ev))
          "the trace's op-type is :error")
      (is (= :rf.error/derived-container-replaced (:operation ev))
          "the trace's :operation is the error category keyword")
      (is (= :rf.error/derived-container-replaced (get-in ev [:tags :category]))
          "the :category tag mirrors :operation for consumer convenience")
      (is (= :no-recovery (:recovery ev))
          "the :recovery field is hoisted to top level as :no-recovery")
      (is (string? (get-in ev [:tags :reason]))
          "the :reason tag is a human-readable sentence"))))

(deftest derived-container-value-unchanged-after-rejected-write
  (testing "the rejected write does NOT mutate the derived container — the adapter replace-container! is never invoked"
    (let [src     (adapter/make-state-container {:n 5})
          derived (adapter/make-derived-value [src] (fn [v] (:n v)))]
      (try (adapter/replace-container! derived 1000)
           (catch #?(:clj Throwable :cljs :default) _ nil))
      (is (= 5 (adapter/read-container derived))
          "the derived value still reflects its source — the write was rejected")
      ;; Writing to the SOURCE still flows through to the derived value,
      ;; proving the source container itself remains a normal writable
      ;; container and the guard fires only on the derived shape.
      (adapter/replace-container! src {:n 6})
      (is (= 6 (adapter/read-container derived))
          "writing to the source recomputes the derived value normally"))))
