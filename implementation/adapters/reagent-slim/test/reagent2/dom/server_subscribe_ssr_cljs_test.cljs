(ns reagent2.dom.server-subscribe-ssr-cljs-test
  "Substrate contract test: render a SUBSCRIBING reg-view through the
  SLIM `reagent2.dom.server/render-to-static-markup`.

  ## Why this file exists — the first-subscribe IDisposable path

  When SSR walks a user-fn view head whose body derefs
  `@(subscribe [...])`, `re-frame.subs/subscribe` builds a Reaction on
  first build and calls `interop/add-on-dispose!` on it
  (core/src/re_frame/subs.cljc), which dispatches through interop's
  late-bind hooks to the installed substrate. If that first-subscribe
  call threw under the slim substrate, boot-time SSR would fail.

  The rest of the slim SSR corpus exercises ONLY:
    - raw hiccup (reagent2.dom.parity-cljs-test, server-cljs-test escaping
      / attrs / void / fragment / nested cases), and
    - a PLAIN user-fn head with NO subscribe
      (server-cljs-test/user-fn-head-invoked: `(fn [x] [:li x])`).

  None drives a frame `subscribe` deref through `render-to-static-markup`.
  So a regression that broke `add-on-dispose!` routing under the slim
  substrate — or the non-reactive `-deref` branch the SSR deref hits
  (reagent2/ratom.cljs IDeref: `*ratom-context*` nil → `flush!` +
  on-demand recompute, returns `state`) — would pass every other gate.
  This file covers that runtime output.

  ## What it asserts (the first-subscribe path, value-5)

  Models the `examples/substrates/reagent_slim/counter` dataflow
  exactly (its boot path):
  `:counter/initialise → {:counter/value 5}`, a `:counter/value` sub, and
  a `counter-app → counter-buttons` reg-view tree that derefs
  `@(subscribe [:counter/value])`. Then:

    1. `render-to-static-markup [counter-app]` renders the subscribed
       value `5` — so the first subscribe did not throw, and the value
       rode through the non-reactive deref branch into the output.
    2. After `dispatch [:counter/inc]`, a re-render shows `6` — proving the
       SSR deref re-reads LIVE app-db through the subscription, not a
       value frozen at first build.
    3. Rendering the same app again under the canonical frame-provider
       mount reuses the sub cache and produces the identical bytes.

  examples/ is TEST-FREE: this is a CLJS substrate
  contract test in the slim adapter's own test tree, NOT a per-example
  spec and NOT under examples/. The dir is on the consolidated
  :node-test classpath (shadow-cljs.edn). No DOM needed — same headless
  reg-* / dispatch-sync / subscribe / render pattern the other slim
  node-tests use.

  ns ends in -cljs-test (and NOT -dom-cljs-test) so shadow-cljs's
  :node-test build's `cljs-test$` ns-regexp picks it up under
  `npm run test:cljs`."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.adapter.reagent-slim :as rf.adapter.reagent-slim]
            [re-frame.test-support :as rf.test-support]
            [re-frame.views]
            [reagent2.dom.server :as server])
  (:require-macros [re-frame.core :refer [reg-view]]))

;; Cold-start the slim adapter + the :rf/default frame around each test,
;; rolling back user registrations on the way out (the same fixture the
;; other reg-view-driven slim adapter tests use).
(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter rf.adapter.reagent-slim/adapter}))

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

;; A FORM-2 subscribing reg-view: the body's last form is a
;; literal `(fn ...)`, so the view is Form-2 — the outer body
;; runs once as setup, the inner closure is the live render. The inner
;; closure derefs `@(subscribe ...)`. A static renderer that called the
;; view head once and recursed on the bare inner render fn it got back
;; would throw `:rf.error/static-markup-bad-element` before the subscribe
;; ever ran. The inner closure is recalled instead, and its subscribing
;; hiccup rides into the markup.
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

;; ---- the canonical slim mount under frame-provider -------------------------
;;
;; `[rf/frame-provider {:frame f} [app]]` is the mount the guides teach and
;; `docs/core/how-to/use-uix-or-slim.md` sells for HTML export. It expands
;; (via `re-frame.views.provider/frame-provider-component`) to
;; `[:r> (.-Provider frame-context) #js {:value f} & children]`.
;;
;; A static walker that treated that `:r>` head as opaque foreign content
;; would emit `<!--reagent-react-component-->` and NOTHING ELSE — the whole
;; app subtree silently gone, no error, for the documented mount on the
;; documented export path.
;;
;; These assert the PRECONDITION — that the subtree's CONTENT is actually
;; in the markup — rather than merely that nothing threw. An empty or
;; placeholder-only string fails every one of them.

(deftest frame-provider-mount-matches-the-unwrapped-render
  (testing "a frame-provider is a SCOPING wrapper — it renders no
            markup of its own, so wrapping the app in one must not change a
            single byte of the output"
    (register-counter!)
    (rf/dispatch-sync [:counter/initialise])
    (let [bare     (server/render-to-static-markup [counter-app])
          provided (server/render-to-static-markup
                    [rf/frame-provider {:frame :rf/default}
                     [counter-app]])]
      ;; Precondition: the bare render is non-trivial, so the equality below
      ;; cannot be satisfied by two empty strings.
      (is (re-find #">5<" bare) "precondition: the bare render has content")
      (is (= bare provided)
          (str "frame-provider contributed no markup of its own — bare: "
               (pr-str bare) " provided: " (pr-str provided))))))

(deftest frame-provider-mount-renders-multiple-children
  (testing "frame-provider takes VARIADIC children; every one of
            them must reach the markup, in order"
    (register-counter!)
    (rf/dispatch-sync [:counter/initialise])
    (let [markup (server/render-to-static-markup
                  [rf/frame-provider {:frame :rf/default}
                   [:h1 "header"]
                   [counter-app]
                   [:footer "foot"]])]
      (is (re-find #"<h1>header</h1>" markup)
          (str "first child present — got: " (pr-str markup)))
      (is (re-find #">5<" markup)
          (str "middle child (the subscribing app) present — got: " (pr-str markup)))
      (is (re-find #"<footer>foot</footer>" markup)
          (str "last child present — got: " (pr-str markup))))))

;; ---- Form-2 reg-view SSR --------------------------------------------------

(deftest form2-ssr-deref-reads-live-app-db
  (testing "after [:counter/inc], the Form-2 SSR re-render
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
