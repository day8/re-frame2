(ns re-frame.http-test-support-absent-test
  "Negative-assertion test for rf2-cdmle: with `re-frame.http-test-support`
  ABSENT from the require closure, the two canonical canned-stub fxs
  (`:rf.http/managed-canned-success`, `:rf.http/managed-canned-failure`)
  MUST NOT be registered.

  This is the JVM/SSR companion to the CLJS production-bundle elision
  sentinels in `scripts/check-elision.cjs`. The CLJS contract pins
  absence in `:advanced + goog.DEBUG=false` bundles (the test-support
  module is unreferenced from any production module → DCE'd wholesale);
  this test pins the corresponding absence on the JVM, where DCE doesn't
  apply and the gate must come from classpath / require closure alone.

  ## Why this lives in its own file

  The assertion is load-order sensitive. Any `:require` of
  `re-frame.http-test-support` in this namespace's REQUIRE form OR
  in any sibling test file run before this one would seed the
  canned-stub fx registrations into the registrar — and the assertion
  here would false-pass / false-fail depending on test order.

  Two safeguards keep the test hermetic:

   1. The require form below intentionally omits
      `re-frame.http-test-support`. If a future maintainer adds it,
      this comment block is the load-bearing reminder that doing so
      defeats the test.
   2. The test body starts with `(registrar/clear-all!)` followed by
      `(require 're-frame.http-managed :reload)`. After clear-all! the
      registrar is empty; reloading http-managed re-fires its load-time
      `(fx/reg-fx :rf.http/managed ...)` and `(fx/reg-fx :rf.http/managed-abort ...)`
      forms, but does NOT re-fire any registration in
      `re-frame.http-test-support` — that namespace isn't loaded by
      this require closure at all.

  The methodology counterpart — \"with the test-support require in the
  closure, the canned stubs ARE registered\" — lives in
  `re-frame.http-managed-test/canned-stub-fxs-registered-when-test-support-required`."
  (:require [clojure.test :refer [deftest is testing]]
            [re-frame.registrar :as registrar]))

(deftest canned-stub-fxs-absent-without-test-support-require
  (testing "rf2-cdmle — with re-frame.http-test-support ABSENT from the
            require closure, neither canned-stub fx id is registered.
            The production-eligible :rf.http/managed and
            :rf.http/managed-abort fxs ARE registered (they live in
            re-frame.http-managed, which IS in the require closure via
            the reload below)."
    ;; Start from a known-clean registrar.
    (registrar/clear-all!)
    ;; Reload re-frame.http-managed so its load-time fx registrations
    ;; fire. This file does NOT require `re-frame.http-test-support`,
    ;; so the canned-stub fx ids are not registered as a side effect of
    ;; loading the prod artefact.
    (require 're-frame.http-managed :reload)
    ;; Production-eligible fxs MUST be registered — sanity / methodology
    ;; floor: a regression that broke re-frame.http-managed itself
    ;; (rather than merely re-introducing the canned-stub gate) would
    ;; show up as both these and the canned ones absent.
    (is (some? (registrar/lookup :fx :rf.http/managed))
        ":rf.http/managed is dev+prod — registered by re-frame.http-managed at load")
    (is (some? (registrar/lookup :fx :rf.http/managed-abort))
        ":rf.http/managed-abort is dev+prod — registered by re-frame.http-managed at load")
    ;; The load-bearing assertion: canned-stub fx ids MUST NOT be
    ;; registered when re-frame.http-test-support has not been required.
    ;; Production code paths reaching `:fx-overrides
    ;; {:rf.http/managed :rf.http/managed-canned-success}` would fail
    ;; with the framework's no-such-fx error, which is the intended
    ;; production posture per rf2-zk08x's audit decision.
    (is (nil? (registrar/lookup :fx :rf.http/managed-canned-success))
        ":rf.http/managed-canned-success MUST NOT register without re-frame.http-test-support require")
    (is (nil? (registrar/lookup :fx :rf.http/managed-canned-failure))
        ":rf.http/managed-canned-failure MUST NOT register without re-frame.http-test-support require")))
