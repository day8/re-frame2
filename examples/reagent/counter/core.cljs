(ns counter.core
  "A minimal counter against the re-frame2 API.

   Demonstrates: `reg-event-db`, `reg-sub`, `reg-view` with frame-bound
   `dispatch`/`subscribe` injection.

   NOTE on frame-provider: an earlier shape of this example wrapped
   `:counter-buttons` in `[rf/frame-provider {:frame :counter} ...]`
   so the subtree resolved its current frame to `:counter` rather
   than `:rf/default`. The example still uses the default frame to
   keep the smoke test focused; the frame-provider variant is
   exercised by examples/reagent/login and the cross-spec test suite."
  (:require [reagent.dom.client :as rdc]
            [re-frame.core    :as rf]
            [re-frame.views]
            [re-frame.adapter.reagent :as reagent-adapter])
  (:require-macros [re-frame.core :refer [reg-view]]))

;; -- Events / subs (handler registry is app-global) --------------------------

;; A user-registered fx so the perf-instrumented variant of this
;; example (out/examples/counter-perf — see shadow-cljs.edn)
;; exercises the `rf:fx:<id>` perf bucket too. The default counter spec
;; doesn't observe any user fx; this fx fires on init and records its
;; calls into a process-local atom so the regular counter spec is
;; unaffected. The perf-on counter spec
;; (examples/reagent/counter/counter-perf.spec.cjs) reads
;; `performance.getEntriesByType('measure')` and asserts an entry under
;; `rf:fx:counter/log` is present.

(defonce counter-log (atom []))

(rf/reg-fx :counter/log
  (fn [_ctx args]
    (swap! counter-log conj args)))

;; ----------------------------------------------------------------------------
;; DELIBERATE PERF-BUCKET EXCEPTION — NOT THE CANONICAL :initialise SHAPE
;;
;; The canonical re-frame2 :initialise shape is `reg-event-db` returning a
;; plain seeded db — no `:fx`. This handler is INTENTIONALLY widened to
;; `reg-event-fx` with both `:db` and `:fx` so the counter-perf
;; instrumented variant (out/examples/counter-perf — see shadow-cljs.edn)
;; exercises every perf bucket (event handler, sub recompute, fx walk,
;; render) on a single dispatch. The browser smoke test
;; (examples/reagent/counter/counter-perf.spec.cjs) asserts a
;; `rf:fx:counter/log` performance measure entry exists; the only way
;; to produce that on init without a user click is to attach the `:fx`
;; walk to the initialise dispatch itself.
;;
;; Per Spec 002 §`:fx` ordering: :db commits first, then :fx walks in
;; source order.
;;
;; READER NOTE — do NOT propagate this `:db + :fx` mix to your own
;; :initialise handlers. The idiomatic shape is:
;;
;;     (rf/reg-event-db :app/initialise
;;       (fn [_ _] {:count 5}))                            ;; just the seed db
;;
;; The widening here exists ONLY so the perf-instrumented build has a
;; non-empty :fx walk to measure on init. Every other example in the
;; repo (login, realworld, boot, ...) uses plain `reg-event-db` for
;; its initialiser.
;; ----------------------------------------------------------------------------

(rf/reg-event-fx :counter/initialise
  (fn [_ctx _event]
    {:db {:count 5}
     :fx [[:counter/log :initialised]]}))

(rf/reg-event-db :counter/inc
  (fn [db _event] (update db :count inc)))

(rf/reg-event-db :counter/dec
  (fn [db _event] (update db :count dec)))

(rf/reg-sub :count
  (fn [db _query] (:count db)))

;; -- Views -------------------------------------------------------------------
;;
;; The `:counter-buttons` view holds the UI. Per Spec 004 §reg-view, the
;; defn-shape macro auto-injects `dispatch` and `subscribe` as lexical
;; bindings; both resolve at render time to the frame in scope (the
;; default frame here, see the namespace docstring for the
;; frame-provider variant).
;;
;; reg-view auto-defs a Var named after the supplied symbol and
;; registers the view under (keyword *ns* sym).

(reg-view counter-buttons []
  [:div
   [:button {:on-click #(dispatch [:counter/dec])} "-"]
   [:span {:style {:margin "0 1em"}} @(subscribe [:count])]
   [:button {:on-click #(dispatch [:counter/inc])} "+"]])

;; The root `counter-app` view renders `counter-buttons` against the
;; default frame. Initialisation runs in `run` via `dispatch-sync`.

(reg-view counter-app []
  [counter-buttons])

;; -- Mount -------------------------------------------------------------------

(defonce root
  (rdc/create-root (js/document.getElementById "app")))

(defn ^:export run []
  ;; rf/init! takes the adapter spec map directly. Each adapter
  ;; ns exports an `adapter` var; consumers require the ns and pass the
  ;; var explicitly. There is no default-adapter registry.
  (rf/init! reagent-adapter/adapter)
  (rf/dispatch-sync [:counter/initialise])
  (rdc/render root [counter-app]))
