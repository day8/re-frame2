(ns counter.core
  "A minimal counter against the re-frame2 API.

   Demonstrates: `reg-event-db`, `reg-sub`, `reg-view` with frame-bound
   `dispatch`/`subscribe` injection.

   NOTE on frame-provider: an earlier shape of this example wrapped
   `:counter-buttons` in `[rf/frame-provider {:frame :counter} ...]`
   so the subtree resolved its current frame to `:counter` rather
   than `:rf/default`. That depends on React-context resolution
   working under Reagent 1.2 + React 18, which is currently broken
   (rf2-kdwc: class-component-style :context-type metadata isn't
   wired up; the resolution falls back to :rf/default and the
   subscription misses the :counter app-db). The example below uses
   the default frame so the smoke test passes today; revisit once
   rf2-kdwc lands or once the Reagent v2 migration (rf2-25aq)
   replaces contextType with useContext."
  (:require [reagent.dom.client :as rdc]
            [re-frame.core    :as rf]
            [re-frame.views]
            [re-frame.substrate.reagent :as reagent-adapter])
  (:require-macros [re-frame.views-macros :refer [reg-view]]))

;; -- Events / subs (handler registry is app-global) --------------------------

(rf/reg-event-db :counter/initialise
  (fn [_db _event] {:count 5}))

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
;; frame-provider variant parked behind rf2-kdwc).
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
  (rf/init! reagent-adapter/adapter)
  (rf/dispatch-sync [:counter/initialise])
  (rdc/render root [counter-app]))
