(ns re-frame.resources-clear-artefact-missing-cljs-test
  "The documented missing-artefact contract for the RESOURCES arm of
  `(rf/clear kind id)` (rf2-kuky.80).

  `rf/clear` dispatches each kind to its OWNING lifecycle fn, and for the
  resources trio (`:resource` / `:mutation` / `:resource-scope`) that route
  goes THROUGH the ordinary optional-capability wrappers in
  `re-frame.core-resources`. So an app that calls `(rf/clear :resource id)`
  without the resources artefact on the classpath must get the documented
  `:rf.error/resources-artefact-missing` — the same contract every other
  re-exported resources surface honours — rather than a nil-hook no-op, a
  NullPointerException, or a silent success.

  That is the property this file pins, and nothing else covered it. The
  flows and routing artefacts each carry a `re-frame.late-bind-missing-test`
  making the equivalent assertion for their own hooks; the resources
  artefact had no sibling. The only `:rf.error/resources-artefact-missing`
  assertion in the tree before this file was in
  `re-frame.resources-revalidation-cljs-test`, which exercises the
  `:resources/on-frame-registered!` hook from `rf/make-frame` — a different
  hook reached through a different surface, and nothing to do with clearing.

  ## Strategy

  Identical to the flows/routing siblings, and to the revalidation test's
  own artefact-missing case: the resources artefact IS on the classpath here
  (this ns requires it, which fires the late-bind hook registrations at
  ns-load), so the absent-artefact state is re-established by flipping the
  relevant hook to nil for the duration of the assertion and restoring it in
  `finally`. Restoration matters — a leaked nil hook would fail unrelated
  suites in whatever order they happen to run.

  `*-cljs-test.cljc` so the shadow-cljs `:node-test` build discovers it AND
  the cognitect JVM runner runs it: the wrapper under test is `.cljc`, so
  the contract is pinned on both hosts.

  Per Spec 002 §The late-bind seam, Spec 016 §Registration, and the prose
  at the `re-frame.core-resources` call sites."
  (:require
   #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
      :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
   [re-frame.core :as rf]
   [re-frame.late-bind :as rf.late-bind]
   ;; load-bearing side-effecting require: loading the façade publishes the
   ;; resources late-bind hooks that `with-hook-as-nil` then flips.
   [re-frame.resources]
   [re-frame.test-support :as rf.test-support]
   #?@(:clj  [[re-frame.substrate.plain-atom :as rf.substrate.plain-atom]]
       :cljs [[re-frame.adapter.reagent :as rf.adapter.reagent]])))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    #?(:clj  {:adapter rf.substrate.plain-atom/adapter}
       :cljs {:adapter rf.adapter.reagent/adapter})))

(defn- with-hook-as-nil
  "Run `f` with the named late-bind hook set to nil, restoring the original
  value afterwards whether `f` returns or throws."
  [hook-key f]
  (let [original (rf.late-bind/get-fn hook-key)]
    (try
      (rf.late-bind/set-fn! hook-key nil)
      (f)
      (finally
        (rf.late-bind/set-fn! hook-key original)))))

(defn- clear-raised
  "Call `(rf/clear kind id)` with `hook-key` absent; return the thrown
  ExceptionInfo's ex-data, or nil if the call returned normally."
  [hook-key kind id]
  (with-hook-as-nil hook-key
    (fn []
      (try
        (rf/clear kind id)
        nil
        (catch #?(:clj clojure.lang.ExceptionInfo :cljs :default) e
          (ex-data e))))))

(deftest clear-resource-raises-when-resources-artefact-missing
  (testing "(rf/clear :resource id) with the :resources/clear-resource hook
            absent raises the documented :rf.error/resources-artefact-missing"
    (let [data (clear-raised :resources/clear-resource :resource :probe/feed)]
      (is (some? data)
          "the call must THROW — an absent artefact is not a silent no-op")
      (is (= :rf.error/resources-artefact-missing (:rf.error/id data))
          "the canonical machine discriminator, per the artefact-missing
           convention every optional capability shares")
      (is (some? (:reason data))
          "a human diagnostic rides along, so the message is actionable"))))

(deftest clear-mutation-raises-when-resources-artefact-missing
  (testing "(rf/clear :mutation id) honours the same contract through its own
            hook — the trio is not covered by testing one member"
    (let [data (clear-raised :resources/clear-mutation :mutation :probe/save)]
      (is (some? data))
      (is (= :rf.error/resources-artefact-missing (:rf.error/id data))))))

(deftest clear-resource-scope-raises-when-resources-artefact-missing
  (testing "(rf/clear :resource-scope id) honours the same contract"
    (let [data (clear-raised :resources/clear-resource-scope
                             :resource-scope :probe/tenant)]
      (is (some? data))
      (is (= :rf.error/resources-artefact-missing (:rf.error/id data))))))

(deftest hooks-are-restored-and-clear-works-with-the-artefact-present
  (testing "positive control — with the hooks published (the ordinary state),
            (rf/clear :resource id) returns the id rather than throwing. This
            is what distinguishes the three refusals above from a `clear` that
            throws unconditionally, and it also proves `with-hook-as-nil`
            restored what it flipped"
    (doseq [hook [:resources/clear-resource
                  :resources/clear-mutation
                  :resources/clear-resource-scope]]
      (is (some? (rf.late-bind/get-fn hook))
          (str hook " was restored after the flip")))
    (is (= :probe/unregistered (rf/clear :resource :probe/unregistered))
        "clearing an unregistered id is a no-op that still returns the id")))
