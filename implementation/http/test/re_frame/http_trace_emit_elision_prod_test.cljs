(ns re-frame.http-trace-emit-elision-prod-test
  "Per Spec 009 §Production builds (bead rf2-xxd6z) — RUNTIME prod-elision
  contract for the `re-frame.http-managed` trace surface. Companion to
  the string-grep sentinel sweep in `scripts/check-elision.cjs`: the
  grep catches keyword-literal survival in the bundle blob; this file
  pins the BEHAVIOUR — under `:advanced` + `goog.DEBUG=false`, a
  registered trace listener observes NO `:rf.http/*` /
  `:rf.warning/*` events from the managed-HTTP surface.

  Gating contract: each emit site sits inside `(when interop/debug-
  enabled? ...)` AND the body of `trace/emit!` itself is gated. Under
  prod-mode both gates constant-fold to false and the emit is a no-op.
  The host call (e.g. `record-in-flight!`, `abort-on-actor-destroy`'s
  swap!) still runs; only the trace fan-out elides.

  Surfaces exercised:

  - `:rf.http/aborted-on-actor-destroy`  (rf2-wvkn — emitted by
                                          `abort-on-actor-destroy`
                                          when handles exist)
  - `:rf.http/retry-attempt`              (Spec 014 §Retry — emit site
                                          gated; we do not actually run
                                          a request here, but require
                                          `http-transport` so the
                                          reachability graph includes
                                          the gated body)
  - `:rf.warning/decode-defaulted`        (Spec 014 §`:auto` decode —
                                          ditto reachability via
                                          `http-decode` require)

  Naming convention: files ending in `-elision-prod-test.cljs` are
  picked up ONLY by the `:browser-test-prod-elision` build. Running
  under `goog.DEBUG=true` would FAIL — the trace surface delivers under
  dev-mode (which is the dev contract pinned in the existing JVM
  `http-managed` tests)."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.adapter.reagent :as reagent-adapter]
            [re-frame.test-support :as test-support]
            ;; Require every gated emit-site host ns so the reachability
            ;; graph includes their compiled bodies — DCE only proves the
            ;; gated branches dead from a reachable module.
            [re-frame.http-managed :as http-managed]
            [re-frame.http-registry :as http-registry]
            [re-frame.http-transport]
            [re-frame.http-decode]
            ;; rf2-qwm0a — listener surface lives in `re-frame.trace.tooling`.
            [re-frame.trace.tooling :as trace-tooling]))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter reagent-adapter/adapter
     :init-fn (fn []
                ;; Defonce'd indexes need to be clean between tests so a
                ;; leaked handle from one test doesn't influence another.
                (http-managed/clear-all-in-flight!))}))

;; ---- helpers --------------------------------------------------------------

(defn- listener-fixture
  "Install a recording trace listener, run `body-fn`, and return the
  captured events vector. Records EVERY trace event so the test asserts
  on `empty?` without filtering — any leak surfaces."
  [body-fn]
  (let [seen   (atom [])
        cb-key (keyword (str "elision-prod-" (gensym)))]
    (trace-tooling/register-listener!
      cb-key
      (fn [ev] (swap! seen conj ev)))
    (try
      (body-fn)
      @seen
      (finally
        (trace-tooling/unregister-listener! cb-key)))))

;; ---- :rf.http/aborted-on-actor-destroy elides under prod ----------------

(deftest abort-on-actor-destroy-emits-no-trace-under-prod
  (testing "Per Spec 009 §Production-elision (rf2-xxd6z): calling
            `abort-on-actor-destroy` against an actor with in-flight
            handles fires every handle's `:abort-fn` but emits NO
            `:rf.http/aborted-on-actor-destroy` trace under `:advanced`
            + `goog.DEBUG=false`. The host work (the abort-fn callback)
            still runs; only the trace fan-out elides."
    (let [actor-id    :prod-elision/actor
          aborts-seen (atom 0)
          handle      {:abort-fn    (fn [_reason] (swap! aborts-seen inc))
                       :url         "https://prod-elision.example/test"
                       :sensitive?  false}
          _           (http-registry/record-in-flight!
                        :prod-elision/req-id actor-id handle)
          seen        (listener-fixture
                        (fn []
                          (http-managed/abort-on-actor-destroy actor-id)))]
      (is (= 1 @aborts-seen)
          ":abort-fn ran exactly once — host side-effect not elided")
      (is (empty? seen)
          "no trace events delivered under :advanced + goog.DEBUG=false
           — the :rf.http/aborted-on-actor-destroy emit body elided
           while the abort callback still ran"))))

(deftest abort-on-actor-destroy-empty-registry-emits-nothing-under-prod
  (testing "Defensive cross-check: when no handles are recorded for the
            actor, the no-op path runs and emits nothing — both under
            dev (no handles → no emit) and prod (gate elides). Locks
            the idempotency contract under prod-mode."
    (let [seen (listener-fixture
                 (fn []
                   (http-managed/abort-on-actor-destroy
                     :prod-elision/never-spawned)))]
      (is (empty? seen)
          "no trace events for the idempotent no-handle path under prod"))))

(deftest http-managed-abort-fx-emits-no-trace-under-prod
  (testing "Per Spec 009 §Production-elision: dispatching the public
            `:rf.http/managed-abort` fx against a request-id with no
            in-flight handle is a tolerated no-op (Spec 014 §Aborts) —
            and emits NO trace events under prod. Exercises the fx
            handler's load-bearing reachability through the public
            dispatch surface."
    (let [seen (listener-fixture
                 (fn []
                   (rf/reg-event-fx :prod-elision/abort-touch
                     (fn [_ _]
                       {:fx [[:rf.http/managed-abort
                              :prod-elision/no-such-req]]}))
                   (rf/dispatch-sync [:prod-elision/abort-touch])))]
      (is (empty? seen)
          "no trace events delivered for the abort-fx under prod"))))
