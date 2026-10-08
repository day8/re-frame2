(ns re-frame.routing-trace-emit-elision-prod-test
  "Per Spec 009 §Production builds — RUNTIME prod-elision
  contract for the `re-frame.routing` trace surface. Companion to the
  string-grep sentinel sweep in `scripts/check-elision.cjs`: the grep
  catches keyword-literal survival in the bundle blob; this file pins
  the BEHAVIOUR — under `:advanced` + `goog.DEBUG=false`, a registered
  trace listener observes NO events when a routing entry point fires,
  while the routing slice is still committed.

  The gating contract sits inside `re-frame.trace/emit!` itself (the
  whole body is wrapped in `(when interop/debug-enabled? ...)` per
  Spec 009 §Production-elision verification). The routing call sites
  invoke `trace/emit!` unconditionally — Closure constant-folds the
  emit body to a no-op under prod-mode, so the host call (e.g.
  `(.pushState js/window.history ...)`) still runs and the slice is
  still updated, but the trace fan-out elides. One navigation exercises
  `:rf.route/registered`, `:rf.route.nav-token/allocated` and
  `:rf.route/activated`; every other routing emit site goes through the
  same gate.

  Naming convention: files ending in `-elision-prod-test.cljs` are
  picked up ONLY by the `:browser-test-prod-elision` build. The default
  `:browser-test` / `:node-test` runners use regexes that do NOT match
  this suffix, so these tests run only under prod-mode compilation.
  Running this file under `goog.DEBUG=true` would FAIL — the trace
  surface delivers under dev-mode, which is the dev contract documented
  in `re-frame.routing-history-cljs-test`."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.adapter.reagent :as rf.adapter.reagent]
            [re-frame.schemas :as rf.schemas]
            [re-frame.test-support :as rf.test-support]
            ;; Touch the routing namespace directly so its ns body
            ;; (including the gated `trace/emit!` call sites) is in
            ;; the reachability graph for the closure compiler. Per
            ;; the elision-probe pattern: requiring forces compilation;
            ;; the gates do the elision work inside the closed body.
            [re-frame.routing]
            ;; The listener surface lives in `re-frame.trace.tooling`.
            [re-frame.trace.tooling :as rf.trace.tooling]))

;; The routing entry point exercised below runs the RECORDABLE
;; `:rf.route/nav-allocation` cofx generator, whose registration declares a
;; real Malli `:schema` (`[:map [:token :string] [:counter :int]]`). That
;; `:schema` check is ALWAYS-ON (it validates durable causal-token state in
;; prod as well as dev — Spec 009 `:rf.error/cofx-value-invalid` row) and
;; routes through the registered `set-schema-fns!` seam. Under the
;; SHARED `:browser-test-prod-elision` bundle `re-frame.schemas` is loaded,
;; installing the default Malli validator — but Malli's validation BODY is
;; production-elided (Closure DCE under `goog.DEBUG=false`, Spec 010
;; §Production builds: "the validator fn must be production-elidable"), so a
;; well-formed `{:token "nav-1" :counter 1}` is spuriously REJECTED in the
;; prod bundle. Disable the schema validator for this suite (a nil validator
;; is a documented no-op for the recordable check) so the generator runs and
;; the trace-elision contract under test is exercised cleanly. Snapshot +
;; restore so the suite leaves no cross-test residue.
(defn- disable-schema-validation-fixture [f]
  (let [snapshot (rf.schemas/schema-fns)]
    (rf.schemas/set-schema-fns! {:validate nil})
    (try (f)
         (finally (rf.schemas/set-schema-fns! snapshot)))))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter rf.adapter.reagent/adapter})
  disable-schema-validation-fixture)

(defn- listener-fixture
  "Install a recording trace listener, run `body-fn`, and return the
  captured events vector. Records EVERY trace event so the test asserts
  on `empty?` without filtering — any leak surfaces."
  [body-fn]
  (let [seen   (atom [])
        cb-key (keyword (str "elision-prod-" (gensym)))]
    (rf.trace.tooling/register-listener!
      cb-key
      (fn [ev] (swap! seen conj ev)))
    (try
      (body-fn)
      @seen
      (finally
        (rf.trace.tooling/unregister-listener! cb-key)
        (reset! seen [])))))

(deftest handle-url-change-emits-no-trace-under-prod
  (testing "dispatching `:rf.route/handle-url-change` under `:advanced` +
            `goog.DEBUG=false` commits the routing slice but delivers NO
            trace events"
    (let [seen (listener-fixture
                 (fn []
                   (rf/reg-route :prod-elision/landing {} "/")
                   (rf/dispatch-sync
                     [:rf.route/handle-url-change "/"])))]
      (is (empty? seen)
          "no trace events delivered under :advanced + goog.DEBUG=false"))
    (is (= :prod-elision/landing
           (:route-id (get-in (:rf.db/runtime (rf/frame-state-value :rf/default)) [:rf.runtime/routing :current])))
        "routing slice was populated — only the trace surface elided")))
