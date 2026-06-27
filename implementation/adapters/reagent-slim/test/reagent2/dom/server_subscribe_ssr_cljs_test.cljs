(ns reagent2.dom.server-subscribe-ssr-cljs-test
  "Substrate contract test: render a SUBSCRIBING reg-view through the
  SLIM `reagent2.dom.server/render-to-static-markup` (rf2-uacxd.1).

  ## Why this file exists — the rf2-s36l regression locus

  rf2-s36l was a P2 first-subscribe IDisposable miss: when SSR walks a
  user-fn view head whose body derefs `@(subscribe [...])`,
  `re-frame.subs/subscribe` builds a Reaction on first build and calls
  `interop/add-on-dispose!` on it (core/src/re_frame/subs.cljc — the
  exact line s36l fixed by moving interop's reactive surfaces to
  late-bind hooks). Pre-fix, that first-subscribe call THREW under the
  slim substrate during boot-time SSR.

  The existing slim SSR corpus exercises ONLY:
    - raw hiccup (reagent2.dom.parity-cljs-test, server-cljs-test escaping
      / attrs / void / fragment / nested cases), and
    - a PLAIN user-fn head with NO subscribe
      (server-cljs-test/user-fn-head-invoked: `(fn [x] [:li x])`).

  None drives a frame `subscribe` deref through `render-to-static-markup`.
  So a regression that re-broke `add-on-dispose!` routing under the slim
  substrate — or the non-reactive `-deref` branch the SSR deref hits
  (reagent2/ratom.cljs IDeref: `*ratom-context*` nil → `flush!` +
  on-demand recompute, returns `state`) — would pass every current gate.
  This file closes that runtime-output gap.

  ## What it asserts (the s36l path, value-5)

  Models the `examples/substrates/reagent_slim/counter` dataflow
  exactly (the boot path the parent uacxd review found uncovered):
  `:counter/initialise → {:counter/value 5}`, a `:counter/value` sub, and
  a `counter-app → counter-buttons` reg-view tree that derefs
  `@(subscribe [:counter/value])`. Then:

    1. `render-to-static-markup [counter-app]` MUST NOT throw (the s36l
       failure was a throw on first subscribe).
    2. The returned markup MUST contain the subscribed value `5` — proving
       it rode through the non-reactive deref branch into the output.
    3. A second render reuses the sub cache without throwing (idempotent
       SSR).
    4. After `dispatch [:counter/inc]`, a re-render shows `6` — proving the
       SSR deref re-reads LIVE app-db through the subscription, not a
       value frozen at first build.

  Respects rf2-8cevm (examples/ is TEST-FREE): this is a CLJS substrate
  contract test in the slim adapter's own test tree, NOT a per-example
  spec and NOT under examples/. The dir is already on the consolidated
  :node-test classpath (shadow-cljs.edn). No DOM needed — same headless
  reg-* / dispatch-sync / subscribe / render pattern the other slim
  node-tests use.

  ns ends in -cljs-test (and NOT -dom-cljs-test) so shadow-cljs's
  :node-test build's `cljs-test$` ns-regexp picks it up under
  `npm run test:cljs`."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.adapter.reagent-slim :as reagent-slim-adapter]
            [re-frame.test-support :as test-support]
            [re-frame.views]
            [reagent2.dom.server :as server])
  (:require-macros [re-frame.core :refer [reg-view]]))

;; Cold-start the slim adapter + the :rf/default frame around each test,
;; rolling back user registrations on the way out (the same fixture the
;; other reg-view-driven slim adapter tests use).
(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter reagent-slim-adapter/adapter}))

;; ---- the counter dataflow, mirroring examples/substrates/reagent_slim ---------------
;;
;; reg-view auto-injects `dispatch` and `subscribe` as lexical bindings in
;; the body (the defn-shape macro), exactly as the example's views use them.

(defn- register-counter! []
  (rf/reg-event :counter/initialise
    (fn [{:keys [db]} _event] {:db {:counter/value 5}}))
  (rf/reg-event :counter/inc
    (fn [{:keys [db]} _event] {:db (update db :counter/value inc)}))
  (rf/reg-sub :counter/value
    (fn [db _query] (:counter/value db)))
  (reg-view counter-buttons []
    [:div
     [:button {:on-click #(dispatch [:counter/dec])} "-"]
     [:span {:data-testid "counter-value"} @(subscribe [:counter/value])]
     [:button {:on-click #(dispatch [:counter/inc])} "+"]])
  (reg-view counter-app []
    [counter-buttons]))

;; A FORM-2 subscribing reg-view (rf2-o3hqr): the body's last form is a
;; literal `(fn ...)`, so `reg-view` classifies it Form-2 — the outer body
;; runs once as setup, the inner closure is the live render. The inner
;; closure derefs `@(subscribe ...)`. Before the fix the static renderer
;; called the view head once, got the inner render fn back, and recursed
;; on that bare fn — throwing `:rf.error/static-markup-bad-element` before
;; the subscribe ever ran. Now the inner closure is recalled and its
;; subscribing hiccup rides into the markup.
(defn- register-form2-counter! []
  (rf/reg-event :counter/initialise
    (fn [{:keys [db]} _event] {:db {:counter/value 5}}))
  (rf/reg-event :counter/inc
    (fn [{:keys [db]} _event] {:db (update db :counter/value inc)}))
  (rf/reg-sub :counter/value
    (fn [db _query] (:counter/value db)))
  ;; Form-2: outer setup returns the inner render closure.
  (reg-view counter-buttons-f2 []
    (fn []
      [:div
       [:span {:data-testid "counter-value"} @(subscribe [:counter/value])]]))
  (reg-view counter-app-f2 []
    [counter-buttons-f2]))

;; ---- tests ----------------------------------------------------------------

(deftest subscribing-reg-view-renders-through-slim-ssr-without-throwing
  (testing "render-to-static-markup over a reg-view that derefs
            @(subscribe ...) does NOT throw and the subscribed value rides
            into the markup — the rf2-s36l first-subscribe IDisposable path"
    (register-counter!)
    ;; Populate app-db FIRST so the sub has a live value to read.
    (rf/dispatch-sync [:counter/initialise])
    (let [markup (server/render-to-static-markup [counter-app])]
      ;; (a) it did not throw — reaching here at all means the first
      ;;     subscribe + add-on-dispose! routing survived under SSR.
      (is (string? markup)
          "render-to-static-markup returned a string (no throw on first subscribe)")
      ;; (b) the subscribed value 5 rode through the non-reactive deref
      ;;     branch into the output. The value sits as the lone text child
      ;;     of the counter-value <span>, so it appears between '>' and '<'.
      (is (re-find #">5<" markup)
          (str "subscribed :counter/value (5) appears in the rendered markup — got: "
               (pr-str markup))))))

(deftest second-render-reuses-sub-cache-without-throwing
  (testing "a second render-to-static-markup of the same subscribing view
            reuses the per-frame sub cache and still renders 5 (idempotent
            SSR — the cached Reaction's dispose routing is not re-broken on
            reuse)"
    (register-counter!)
    (rf/dispatch-sync [:counter/initialise])
    (let [first-markup  (server/render-to-static-markup [counter-app])
          second-markup (server/render-to-static-markup [counter-app])]
      (is (re-find #">5<" first-markup))
      (is (re-find #">5<" second-markup)
          "second render still produces 5 from the (now cached) subscription"))))

(deftest ssr-deref-reads-live-app-db-not-a-frozen-value
  (testing "after dispatch [:counter/inc], a re-render shows 6 — the SSR
            deref re-reads live app-db through the subscription rather than
            a value frozen at first build"
    (register-counter!)
    (rf/dispatch-sync [:counter/initialise])
    (let [before (server/render-to-static-markup [counter-app])]
      (is (re-find #">5<" before) "precondition: first render shows 5")
      (rf/dispatch-sync [:counter/inc])
      (let [after (server/render-to-static-markup [counter-app])]
        (is (re-find #">6<" after)
            (str "after [:counter/inc] the SSR re-render reflects live app-db (6) — got: "
                 (pr-str after)))
        (is (not (re-find #">5<" after))
            "the stale value 5 no longer appears — the subscription re-read, not froze")))))

;; ---- Form-2 reg-view SSR (rf2-o3hqr) --------------------------------------

(deftest form2-subscribing-reg-view-renders-through-slim-ssr
  (testing "rf2-o3hqr: a FORM-2 reg-view (outer setup returns an inner
            render closure that derefs @(subscribe ...)) renders through
            render-to-static-markup without throwing, and the subscribed
            value rides into the markup"
    (register-form2-counter!)
    (rf/dispatch-sync [:counter/initialise])
    (let [markup (server/render-to-static-markup [counter-app-f2])]
      (is (string? markup)
          "Form-2 reg-view rendered to a string (no static-markup-bad-element throw)")
      (is (re-find #">5<" markup)
          (str "subscribed :counter/value (5) from the Form-2 inner closure "
               "appears in the markup — got: " (pr-str markup))))))

(deftest form2-ssr-deref-reads-live-app-db
  (testing "rf2-o3hqr: after [:counter/inc], the Form-2 SSR re-render
            reflects live app-db (6), proving the inner closure re-ran and
            re-read the subscription"
    (register-form2-counter!)
    (rf/dispatch-sync [:counter/initialise])
    (is (re-find #">5<" (server/render-to-static-markup [counter-app-f2]))
        "precondition: first Form-2 render shows 5")
    (rf/dispatch-sync [:counter/inc])
    (let [after (server/render-to-static-markup [counter-app-f2])]
      (is (re-find #">6<" after)
          (str "Form-2 SSR re-render reflects live app-db (6) — got: " (pr-str after)))
      (is (not (re-find #">5<" after))
          "stale 5 no longer appears — the inner closure re-read, not froze"))))
