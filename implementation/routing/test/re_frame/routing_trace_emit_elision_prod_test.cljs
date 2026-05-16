(ns re-frame.routing-trace-emit-elision-prod-test
  "Per Spec 009 §Production builds (bead rf2-xxd6z) — RUNTIME prod-elision
  contract for the `re-frame.routing` trace surface. Companion to the
  string-grep sentinel sweep in `scripts/check-elision.cjs`: the grep
  catches keyword-literal survival in the bundle blob; this file pins
  the BEHAVIOUR — under `:advanced` + `goog.DEBUG=false`, a registered
  trace listener observes NO `:rf.route/*` / `:rf.warning/*` /
  `:rf.route.nav-token/*` events when the routing entry points fire.

  The gating contract sits inside `re-frame.trace/emit!` itself (the
  whole body is wrapped in `(when interop/debug-enabled? ...)` per
  Spec 009 §Production-elision verification). The routing call sites
  invoke `trace/emit!` unconditionally — Closure constant-folds the
  emit body to a no-op under prod-mode, so the host call (e.g.
  `(.pushState js/window.history ...)`) still runs and the slice is
  still updated, but the trace fan-out elides.

  Surfaces exercised:

  - `:rf.route/url-changed`              (emitted by `handle-url-change`)
  - `:rf.route.nav-token/allocated`      (emitted by `navigate` / `handle-url-change`)
  - `:rf.warning/malformed-url`          (emitted on URL parse failure)
  - `:rf.warning/no-not-found-route`     (emitted when unmatched and no fallback)
  - `:rf.route/navigation-blocked`       (emitted by the `:can-leave` guard)
  - `:rf.warning/route-shadowed-by-equal-score` (emitted at `reg-route`)
  - `:rf.warning/can-leave-guard-non-boolean`   (emitted by the `:can-leave` guard)
  - `:rf.warning/can-leave-subs-artefact-missing` (emitted by the guard)

  Naming convention: files ending in `-elision-prod-test.cljs` are
  picked up ONLY by the `:browser-test-prod-elision` build. The default
  `:browser-test` / `:node-test` runners use regexes that do NOT match
  this suffix, so these tests run only under prod-mode compilation.
  Running this file under `goog.DEBUG=true` would FAIL — the trace
  surface delivers under dev-mode, which is the dev contract documented
  in `re-frame.routing-history-cljs-test`."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.adapter.reagent :as reagent-adapter]
            [re-frame.test-support :as test-support]
            ;; Touch the routing namespace directly so its ns body
            ;; (including the gated `trace/emit!` call sites) is in
            ;; the reachability graph for the closure compiler. Per
            ;; the elision-probe pattern: requiring forces compilation;
            ;; the gates do the elision work inside the closed body.
            [re-frame.routing]
            ;; rf2-qwm0a — listener surface lives in `re-frame.trace.tooling`.
            [re-frame.trace.tooling :as trace-tooling]))

(use-fixtures :each
  (test-support/reset-runtime-fixture
    {:adapter reagent-adapter/adapter}))

;; ---- helpers --------------------------------------------------------------

(defn- listener-fixture
  "Install a recording trace listener, run `body-fn`, and return the
  captured events vector. Records EVERY trace event so the test asserts
  on `empty?` without filtering — any leak surfaces."
  [body-fn]
  (let [seen   (atom [])
        cb-key (keyword (str "elision-prod-" (gensym)))]
    (trace-tooling/register-trace-cb!
      cb-key
      (fn [ev] (swap! seen conj ev)))
    (try
      (body-fn)
      @seen
      (finally
        (trace-tooling/remove-trace-cb! cb-key)
        (reset! seen [])))))

;; ---- :rf.route/url-changed elides under prod ------------------------------

(deftest handle-url-change-emits-no-trace-under-prod
  (testing "Per Spec 009 §Production-elision (rf2-xxd6z): dispatching
            `:rf.route/handle-url-change` under `:advanced` +
            `goog.DEBUG=false` runs the routing slice update but emits
            NO trace events. The `:rf.route/url-changed` and
            `:rf.route.nav-token/allocated` emits are DCE'd by the
            gate inside `trace/emit!`."
    (let [seen (listener-fixture
                 (fn []
                   (rf/reg-route :prod-elision/landing {:path "/"})
                   (rf/dispatch-sync
                     [:rf.route/handle-url-change "/"])))]
      (is (empty? seen)
          "no trace events delivered under :advanced + goog.DEBUG=false
           — the routing entry point's trace/emit! body elides while the
           slice update still runs"))
    ;; Cross-check: the routing slice DID update (handler still runs;
    ;; only the trace surface elides).
    (is (= :prod-elision/landing
           (:id (:rf/route (rf/get-frame-db :rf/default))))
        "routing slice was populated — only the trace surface elided")))

;; ---- :rf.warning/malformed-url elides under prod -------------------------

(deftest malformed-url-warning-elides-under-prod
  (testing "Per Spec 009 §Production-elision: feeding a malformed URL
            to `:rf.route/handle-url-change` runs the fallback branch
            but emits NO `:rf.warning/malformed-url` trace under prod."
    (let [seen (listener-fixture
                 (fn []
                   ;; Register a not-found route so the malformed-url
                   ;; path lands somewhere; the warn trace must elide.
                   (rf/reg-route :rf.route/not-found {:path "/*splat"})
                   (rf/dispatch-sync
                     [:rf.route/handle-url-change "%E0%A4%A"])))]
      (is (empty? seen)
          "no :rf.warning/malformed-url delivered under prod"))))

;; ---- :rf.route/navigation-blocked elides under prod ----------------------

(deftest navigation-blocked-emits-no-trace-under-prod
  (testing "Per Spec 009 §Production-elision: a `:can-leave` guard that
            blocks navigation populates `:rf/pending-navigation` but
            emits NO `:rf.route/navigation-blocked` trace under
            `:advanced` + `goog.DEBUG=false`. The state side-effect
            still happens; only the trace fan-out elides."
    (let [seen (listener-fixture
                 (fn []
                   (rf/reg-sub :prod/leaver-can? (fn [_db _q] false))
                   (rf/reg-route :prod/leaver
                                 {:path      "/leaver"
                                  :can-leave :prod/leaver-can?})
                   (rf/reg-route :prod/dest {:path "/dest"})
                   ;; Settle on the leaver route first.
                   (rf/dispatch-sync
                     [:rf.route/handle-url-change "/leaver"])
                   ;; Now attempt to leave — guard blocks; trace elides.
                   (rf/dispatch-sync
                     [:rf.route/handle-url-change "/dest"])))]
      (is (empty? seen)
          "no trace events delivered for the blocked navigation under prod"))
    ;; Cross-check: the pending-navigation slot WAS populated — the
    ;; guard branch ran; only its trace emit elided.
    (is (some? (:rf/pending-navigation (rf/get-frame-db :rf/default)))
        ":rf/pending-navigation slot populated — handler ran, only trace elided")))

;; ---- :rf.warning/route-shadowed-by-equal-score elides under prod ---------

(deftest route-shadowed-warn-elides-under-prod
  (testing "Per Spec 009 §Production-elision: registering two routes
            whose rank scores collide emits NO
            `:rf.warning/route-shadowed-by-equal-score` trace under
            prod. The registration still succeeds (the warn is
            informational); the trace fan-out elides."
    (let [seen (listener-fixture
                 (fn []
                   (rf/reg-route :prod/shadow-a {:path "/x"})
                   (rf/reg-route :prod/shadow-b {:path "/x"})))]
      (is (empty? seen)
          "no :rf.warning/route-shadowed-by-equal-score under prod"))))
