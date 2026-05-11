(ns re-frame.trace-listener-elision-prod-test
  "Per Spec 009 §Production-elision contract (bead rf2-2zdu): under
  `:advanced` + `goog.DEBUG=false`, the entire trace surface elides via
  the `re-frame.interop/debug-enabled?` gate. No listener callback should
  fire; no event-map should be allocated; no buffer push should happen.

  The JVM tests use `with-redefs` against the JVM-side
  `debug-enabled?` symbol — useful but not load-bearing for the genuine
  closure-fold contract. This file compiles under
  `:browser-test-prod-elision` (a dedicated shadow-cljs build with
  `goog.DEBUG=false` + `:advanced`) so the gate is constant-folded by the
  closure compiler. Under that compile:

    - `(rf/register-trace-cb! ...)` returns a key; the callback registry
      atom still exists at the value layer, but
    - `(rf/dispatch-sync [...])` runs handlers normally yet
      `trace/emit!` becomes a no-op (its body sits inside the gate);
      hence the callback never fires.
    - `(rf/emit-trace! ...)` is not a public surface — but
      `re-frame.trace/emit!` IS. Calling it directly under prod-mode
      must also be a no-op.

  Companion to `re-frame.schemas-boundary-prod-test` (Spec 010 prod
  smoke). Naming convention: files ending in `-prod-test.cljs` are
  picked up ONLY by the `:browser-test-prod-elision` build. The default
  `:browser-test` and `:node-test` builds use regexes `-cljs-test$` and
  `cljs-test$` and therefore do NOT pick up these files."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.adapter.reagent :as reagent-adapter]
            [re-frame.test-support :as test-support]
            [re-frame.trace :as trace]))

(use-fixtures :each
  (test-support/reset-runtime-fixture
    {:adapter reagent-adapter/adapter}))

;; ---- callback NEVER fires under prod-mode --------------------------------

(deftest registered-listener-does-not-fire-under-prod
  (testing "Per Spec 009 §Production builds: under `:advanced` +
            `goog.DEBUG=false`, trace/emit!'s body is DCE'd. A registered
            listener observes NO events when dispatch runs, because the
            emit call sites have been elided."
    (let [seen (atom [])]
      (rf/register-trace-cb! ::prod-no-trace
        (fn [ev] (swap! seen conj ev)))
      (rf/reg-event-db :prod/ping
                       (fn [db _] (assoc db :pinged? true)))
      ;; The handler still runs (router is not elision-gated; only the
      ;; trace surface is).
      (rf/dispatch-sync [:prod/ping])
      (is (= true (:pinged? (rf/get-frame-db :rf/default)))
          "the handler ran — only the trace surface is gated, not dispatch")
      (is (empty? @seen)
          "no events observed — Spec 009 §Production builds elision contract holds")
      (rf/remove-trace-cb! ::prod-no-trace))))

(deftest emit-direct-call-is-noop-under-prod
  (testing "Direct invocation of trace/emit! is a no-op under prod-mode.
            The gate sits inside the fn body, so even bypassing the
            runtime's normal dispatch path doesn't escape elision."
    (let [seen (atom [])]
      (rf/register-trace-cb! ::direct-emit (fn [ev] (swap! seen conj ev)))
      ;; Direct emit — would deliver under dev-mode.
      (trace/emit! :info :rf.prod-test/direct-emit {:should "never appear"})
      (is (empty? @seen)
          "trace/emit! is a no-op under :advanced + goog.DEBUG=false")
      (rf/remove-trace-cb! ::direct-emit))))

(deftest clear-trace-cbs-returns-nil-under-prod
  (testing "clear-trace-cbs! still returns nil under prod-mode — the
            listener registry side of the surface is not gated; only
            the emit/deliver path is. clear-trace-cbs! must work the
            same way as it does under dev so test fixtures that call
            it in prod-mode CI runs don't error."
    (rf/register-trace-cb! ::prod-clear (fn [_ev] nil))
    (is (nil? (trace/clear-trace-cbs!))
        "clear-trace-cbs! returns nil consistently across dev and prod")))
