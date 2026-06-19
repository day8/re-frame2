(ns adapter-testbed-reagent-slim.core
  "Tiny standalone counter app — the reagent-slim adapter's CLIENT-RUNTIME
   smoke fixture (rf2-xsgu8a).

   Per TESTING.md §Test surface ownership: examples/ are for humans;
   per-adapter smoke lives with the adapter. This testbed proves the
   day8/reagent-slim substrate wires up end-to-end in a real browser —
   mount through `reagent2.dom.client`, subscribe, dispatch, re-render —
   without depending on the `examples/reagent-slim/counter_slim_and_fast`
   teaching example.

   It closes the gap the rf2-xsgu8a review found: the slim example's only
   browser-level coverage was compile (check-examples-compile.cjs) +
   release-bundle sentinel grep (check-reagent-slim-bundle-isolation.cjs).
   A client-runtime regression in the mount / inject / click path could
   slip through both. This fixture exercises that exact path.

   Idiomatic re-frame2: app-db + events + subs (no raw atoms through
   views). The only intentional divergence from the stock Reagent adapter
   testbed (adapters/reagent/testbed/) is the substrate beneath — the
   `reagent2.*` imports and the slim adapter Var. Initial value is 5 and
   both inc and dec are wired, matching the slim example's dataflow so the
   smoke and the headless SSR substrate test
   (reagent2.dom.server-subscribe-ssr-cljs-test) assert the same shape.

   Minimal by design. Don't grow it. Real coverage is the framework's
   CLJS tests, the slim headless substrate tests, the bundle-isolation
   grep, and the Xray feature gate."
  (:require [reagent2.dom.client           :as rdc]
            [re-frame.core                 :as rf]
            [re-frame.views]
            [re-frame.adapter.reagent-slim :as reagent-slim-adapter])
  (:require-macros [re-frame.core :refer [reg-view]]))

;; -- Events / subs ----------------------------------------------------------

(rf/reg-event :counter/init
  (fn [{:keys [db]} _event] {:db {:counter/value 5}}))

(rf/reg-event :counter/inc
  (fn [{:keys [db]} _event] {:db (update db :counter/value inc)}))

(rf/reg-event :counter/dec
  (fn [{:keys [db]} _event] {:db (update db :counter/value dec)}))

(rf/reg-sub :counter/value
  (fn [db _query] (:counter/value db)))

;; -- View -------------------------------------------------------------------

;; EP-0002 (rf2-9o48ih): `root` is a `reg-view` so it carries the
;; `:contextType` wiring the slim substrate needs to read the enclosing
;; frame-provider's frame from React context; the frame-bound `dispatch`
;; is captured at RENDER time via `(rf/frame-handle)` so the click
;; handlers (which fire outside any render scope) still route to
;; `:rf/default`. Mirrors the stock Reagent / UIx / Helix testbeds.
(reg-view root []
  (let [n        @(rf/subscribe [:counter/value])
        dispatch (:dispatch (rf/frame-handle))]
    [:div
     [:h1 {:data-testid "rf-adapter-testbed-reagent-slim"}
      "reagent-slim adapter testbed"]
     [:button {:data-testid "rf-adapter-dec"
               :on-click     #(dispatch [:counter/dec])}
      "-1"]
     [:span {:data-testid "rf-adapter-counter"} n]
     [:button {:data-testid "rf-adapter-inc"
               :on-click     #(dispatch [:counter/inc])}
      "+1"]]))

;; -- Mount ------------------------------------------------------------------

(defonce app-root
  (rdc/create-root (js/document.getElementById "app")))

(defn ^:export init []
  ;; EP-0002 (rf2-9o48ih): the runtime never synthesises a frame from
  ;; absence — `:rf/default` is this testbed's app frame, registered
  ;; explicitly here (init! installs only the slim adapter). The boot
  ;; dispatch runs under the frame scope and the render is wrapped in a
  ;; `frame-provider` so in-tree dispatch/subscribe resolve to it.
  (rf/init! reagent-slim-adapter/adapter)
  (rf/reg-frame :rf/default {})
  (rf/with-frame :rf/default
    (rf/dispatch-sync [:counter/init]))
  (rdc/render app-root [rf/frame-provider-existing {:frame :rf/default} [root]]))
