(ns flight-booker.flight-booker
  "7GUIs #3 — Flight Booker.

   A combo box (one-way / return), two date inputs, a Book button.
   Rules:
     - 'one-way': second date input is disabled.
     - 'return': both date inputs are enabled; return-date must be ≥ start-date.
     - The Book button is enabled only when all visible dates parse and the
       (return ≥ start) constraint holds.
     - Clicking Book pops up a confirmation message.

   This task tests *constrained UI input*: the Book button's enabled state is
   a derived property of three other fields. The classic trap is to compute
   it imperatively after every change and forget a path. The re-frame2
   approach: enabled-state is a sub.

   Demonstrates:
   - Schema-bound app-db slice                            (CP-8)
   - Multiple events for distinct user intents            (CP-1)
   - Layered subs with :<- chains                          (CP-2)
   - Conditional UI driven by sub return values           (CP-4)
   - Smoke test exercising the constraint surface         (CP-1 checklist)"
  (:require [reagent.dom.client :as rdc]
            [re-frame.core :as rf]
            ;; Per rf2-p7va, re-frame.schemas ships in
            ;; day8/re-frame2-schemas. Loading the ns here registers
            ;; its late-bind hooks so rf/reg-app-schema resolves.
            [re-frame.schemas]
            [re-frame.views]
            [re-frame.adapter.reagent :as reagent-adapter])
  (:require-macros [re-frame.views-macros :refer [reg-view with-frame]]))

;; ============================================================================
;; SCHEMA
;; ============================================================================

(def date-pattern  #"^\d{4}-\d{2}-\d{2}$")    ;; ISO yyyy-mm-dd

(def FlightState
  [:map
   [:trip-type   [:enum :one-way :return]]
   [:start-text  :string]                    ;; raw text the user typed; we don't parse until validation
   [:return-text :string]])

(rf/reg-app-schema [:flight] FlightState)

;; ============================================================================
;; EVENTS
;; ============================================================================

(rf/reg-event-db :flight/initialise
  {:doc "Seed the flight slice."}
  (fn handler-flight-initialise [db _]
    (assoc db :flight {:trip-type   :one-way
                       :start-text  "2026-05-06"     ;; today, in the original 7GUIs spec
                       :return-text "2026-05-06"})))

(rf/reg-event-db :flight/set-trip-type
  {:doc "User changed the trip-type combo."
   :spec [:cat [:= :flight/set-trip-type] [:enum :one-way :return]]}
  (fn handler-flight-set-trip-type [db [_ trip-type]]
    (assoc-in db [:flight :trip-type] trip-type)))

(rf/reg-event-db :flight/set-start
  {:doc "User edited the start-date input."
   :spec [:cat [:= :flight/set-start] :string]}
  (fn handler-flight-set-start [db [_ raw]]
    (assoc-in db [:flight :start-text] raw)))

(rf/reg-event-db :flight/set-return
  {:doc "User edited the return-date input."
   :spec [:cat [:= :flight/set-return] :string]}
  (fn handler-flight-set-return [db [_ raw]]
    (assoc-in db [:flight :return-text] raw)))

(rf/reg-event-fx :flight/book
  {:doc "User clicked Book. Emits a confirmation effect."}
  (fn handler-flight-book [{:keys [db]} _]
    (let [{:keys [trip-type start-text return-text]} (:flight db)]
      {:fx [[:notify {:message
                      (case trip-type
                        :one-way (str "You have booked a one-way flight on " start-text ".")
                        :return  (str "You have booked a return flight, departing " start-text
                                      " and returning " return-text "."))}]]})))

;; ============================================================================
;; FX
;; ============================================================================

(rf/reg-fx :notify
  {:doc       "Show a confirmation alert."
   :platforms #{:client}}
  (fn fx-notify [_m {:keys [message]}]
    (js/alert message)))

;; ============================================================================
;; SUBSCRIPTIONS
;; ============================================================================

(rf/reg-sub :flight/trip-type   (fn [db _] (get-in db [:flight :trip-type])))
(rf/reg-sub :flight/start-text  (fn [db _] (get-in db [:flight :start-text])))
(rf/reg-sub :flight/return-text (fn [db _] (get-in db [:flight :return-text])))

(defn valid-date? [s] (boolean (re-matches date-pattern (or s ""))))

(rf/reg-sub :flight/return-enabled?
  :<- [:flight/trip-type]
  (fn sub-flight-return-enabled? [trip-type _]
    (= trip-type :return)))

(rf/reg-sub :flight/start-valid?
  :<- [:flight/start-text]
  (fn [s _] (valid-date? s)))

(rf/reg-sub :flight/return-valid?
  :<- [:flight/return-text]
  :<- [:flight/return-enabled?]
  (fn [[s enabled?] _]
    ;; If return isn't enabled, it doesn't need to be valid.
    (or (not enabled?) (valid-date? s))))

(rf/reg-sub :flight/dates-coherent?
  :<- [:flight/trip-type]
  :<- [:flight/start-text]
  :<- [:flight/return-text]
  (fn [[trip-type s r] _]
    (case trip-type
      :one-way true
      :return  (and (valid-date? s)
                    (valid-date? r)
                    (<= (compare s r) 0)))))     ;; ISO dates compare lexicographically

(rf/reg-sub :flight/book-enabled?
  {:doc "True when the Book button should be enabled."}
  :<- [:flight/start-valid?]
  :<- [:flight/return-valid?]
  :<- [:flight/dates-coherent?]
  (fn [[ok-s ok-r ok-c] _]
    (and ok-s ok-r ok-c)))

;; ============================================================================
;; VIEW
;; ============================================================================

(reg-view flight-booker []
  (let [trip-type        @(subscribe [:flight/trip-type])
        start-text       @(subscribe [:flight/start-text])
        return-text      @(subscribe [:flight/return-text])
        return-enabled?  @(subscribe [:flight/return-enabled?])
        start-valid?     @(subscribe [:flight/start-valid?])
        return-valid?    @(subscribe [:flight/return-valid?])
        book-enabled?    @(subscribe [:flight/book-enabled?])
        invalid-style    {:background "#fdd"}]
    [:div.flight-booker
     [:select {:value     (name trip-type)
               :on-change #(dispatch [:flight/set-trip-type
                                      (keyword (.. % -target -value))])}
      [:option {:value "one-way"} "one-way flight"]
      [:option {:value "return"}  "return flight"]]

     [:input {:type      "text"
              :value     start-text
              :style     (when-not start-valid? invalid-style)
              :on-change #(dispatch [:flight/set-start (.. % -target -value)])}]

     [:input {:type      "text"
              :value     return-text
              :disabled  (not return-enabled?)
              :style     (when (and return-enabled? (not return-valid?)) invalid-style)
              :on-change #(dispatch [:flight/set-return (.. % -target -value)])}]

     [:button {:disabled (not book-enabled?)
               :on-click #(dispatch [:flight/book])}
      "Book"]]))

;; ============================================================================
;; HEADLESS TESTS
;; ============================================================================

(defn flight-booker-tests []
  (with-frame [f (rf/make-frame {:on-create [:flight/initialise]})]
    ;; one-way: book is enabled when start parses
    (rf/dispatch-sync [:flight/set-trip-type :one-way] {:frame f})
    (rf/dispatch-sync [:flight/set-start "2026-05-06"] {:frame f})
    (assert       (rf/compute-sub [:flight/book-enabled?] (rf/get-frame-db f)))

    ;; one-way: bad start disables book
    (rf/dispatch-sync [:flight/set-start "not-a-date"] {:frame f})
    (assert (not (rf/compute-sub [:flight/book-enabled?] (rf/get-frame-db f))))

    ;; return: book disabled when return < start
    (rf/dispatch-sync [:flight/set-trip-type :return]   {:frame f})
    (rf/dispatch-sync [:flight/set-start  "2026-05-06"] {:frame f})
    (rf/dispatch-sync [:flight/set-return "2026-05-01"] {:frame f})
    (assert (not (rf/compute-sub [:flight/book-enabled?] (rf/get-frame-db f))))

    ;; return: book enabled when return ≥ start
    (rf/dispatch-sync [:flight/set-return "2026-05-10"] {:frame f})
    (assert (rf/compute-sub [:flight/book-enabled?] (rf/get-frame-db f)))))

;; ============================================================================
;; MOUNT
;; ============================================================================

(defonce root
  (rdc/create-root (js/document.getElementById "app")))

(defn ^:export run []
  ;; rf2-agql: pass the adapter spec map directly — no registry.
  (rf/init! reagent-adapter/adapter)
  (rf/dispatch-sync [:flight/initialise])
  (rdc/render root [flight-booker]))
