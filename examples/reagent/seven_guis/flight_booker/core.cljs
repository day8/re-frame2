(ns seven-guis.flight-booker.core
  "7GUIs #3 — Flight Booker.

   A combo box (one-way / return), two date inputs, a Book button.
   Rules:
     - 'one-way': second date input is disabled.
     - 'return': both date inputs are enabled; return-date must be ≥ start-date.
     - The Book button is enabled only when all visible dates parse and the
       (return ≥ start) constraint holds.
     - Clicking Book pops up a confirmation message.

   This task is about constrained UI input: the Book button's enabled state
   is derived from three other fields. Computing it imperatively after every
   change risks missing a path. Here it is a subscription, so it is always
   in sync.

   Demonstrates:
   - A schema-bound app-db slice
   - One event per distinct user intent
   - Layered subs built with :<- chains
   - UI state driven by sub return values"
  (:require [reagent.dom.client :as rdc]
            [re-frame.core :as rf]
            ;; Require re-frame.schemas to make rf/reg-app-schema available.
            [re-frame.schemas]
            [re-frame.views]
            [re-frame.adapter.reagent :as reagent-adapter])
  (:require-macros [re-frame.core :refer [reg-view with-frame]]))

;; ============================================================================
;; SCHEMA
;; ============================================================================

(def date-pattern  #"^\d{4}-\d{2}-\d{2}$")    ;; ISO yyyy-mm-dd

(def FlightState
  [:map
   [:trip-type   [:enum :one-way :return]]
   [:start-text  :string]                    ;; raw text the user typed; we don't parse until validation
   [:return-text :string]])

;; Bind the schema to the app frame so it validates that frame's commits.
;; `reg-app-schema` is frame-local, so `with-frame` names the target frame
;; by id. We use `:rf/default` to match the `frame-provider {:id …}` at the
;; render root. Binding is by id, so this works even though it runs at
;; ns-load, before the provider creates the frame.
(with-frame :rf/default
  (rf/reg-app-schema [:flight] {:schema FlightState}))

;; ============================================================================
;; EVENTS
;; ============================================================================

(rf/reg-event :flight/initialise
  {:doc "Seed the flight slice."}
  (fn handler-flight-initialise [{:keys [db]} _]
    ;; Seed both date fields to a fixed valid ISO date. The 7GUIs task seeds
    ;; "today"; a constant keeps the example deterministic instead.
    {:db (assoc db :flight {:trip-type   :one-way
                       :start-text  "2026-05-06"
                       :return-text "2026-05-06"})}))

(rf/reg-event :flight/set-trip-type
  {:doc "User changed the trip-type combo."
   :schema [:cat [:= :flight/set-trip-type] [:enum :one-way :return]]}
  (fn handler-flight-set-trip-type [{:keys [db]} [_ trip-type]]
    {:db (assoc-in db [:flight :trip-type] trip-type)}))

(rf/reg-event :flight/set-start
  {:doc "User edited the start-date input."
   :schema [:cat [:= :flight/set-start] :string]}
  (fn handler-flight-set-start [{:keys [db]} [_ raw]]
    {:db (assoc-in db [:flight :start-text] raw)}))

(rf/reg-event :flight/set-return
  {:doc "User edited the return-date input."
   :schema [:cat [:= :flight/set-return] :string]}
  (fn handler-flight-set-return [{:keys [db]} [_ raw]]
    {:db (assoc-in db [:flight :return-text] raw)}))

(rf/reg-event :flight/book
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

(defn valid-date?
  "True when `s` is a real ISO `yyyy-mm-dd` calendar date. The regex only
   checks shape, so it accepts impossible dates like `2026-99-99` or
   `2026-02-31`. To reject those, parse the fields, round-trip them through a
   UTC `js/Date`, and require every component to come back unchanged. This is
   leap-year-correct: `2025-02-29` overflows to March and fails, `2024-02-29`
   round-trips and passes."
  [s]
  (let [s (or s "")]
    (boolean
      (when (re-matches date-pattern s)
        (let [y  (js/parseInt (subs s 0 4) 10)
              m  (js/parseInt (subs s 5 7) 10)
              d  (js/parseInt (subs s 8 10) 10)
              dt (js/Date. 0)]
          ;; Use setUTCFullYear, not js/Date.UTC: the latter maps years
          ;; 0-99 to 1900-1999, which would corrupt four-digit years
          ;; like 0026.
          (.setUTCFullYear dt y (dec m) d)
          (and (= y (.getUTCFullYear dt))
               (= (dec m) (.getUTCMonth dt))
               (= d (.getUTCDate dt))))))))

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
               :data-testid "flight-trip-type"
               :on-change #(dispatch [:flight/set-trip-type
                                      (keyword (.. % -target -value))])}
      [:option {:value "one-way"} "one-way flight"]
      [:option {:value "return"}  "return flight"]]

     [:input {:type      "text"
              :value     start-text
              :data-testid "flight-start"
              :style     (when-not start-valid? invalid-style)
              :on-change #(dispatch [:flight/set-start (.. % -target -value)])}]

     [:input {:type      "text"
              :value     return-text
              :disabled  (not return-enabled?)
              :data-testid "flight-return"
              :style     (when (and return-enabled? (not return-valid?)) invalid-style)
              :on-change #(dispatch [:flight/set-return (.. % -target -value)])}]

     [:button {:disabled (not book-enabled?)
               :data-testid "flight-book"
               :on-click #(dispatch [:flight/book])}
      "Book"]]))

;; ============================================================================
;; MOUNT
;; ============================================================================

;; Hold the React root in an atom and create it lazily inside `run`, not at
;; ns-load. ns-load must have no DOM side effects so co-required example
;; namespaces don't race `create-root` onto the shared `#app`. See
;; examples/TESTING.md, the Example mount-isolation convention.
(defonce react-root (atom nil))

;; The frame lifecycle lives in one place: the `frame-provider {:id app-frame
;; …}` at the render root below. On first mount it creates the app frame,
;; applies its config, and runs `:initial-events` once to seed app-db. On hot
;; reload it reuses the same frame and skips re-seeding. `app-frame` is just
;; an id we pick and hand to the provider. See the counter example for the
;; same mount shape: examples/reagent/counter/core.cljs.
(def app-frame :rf/default)

(defn run []
  ;; `init!` installs the reactive adapter for the process. The adapter ns
  ;; exports an `adapter` var; pass it directly.
  (rf/init! reagent-adapter/adapter)
  (when (exists? js/document)
    (when-not @react-root
      (reset! react-root (rdc/create-root (js/document.getElementById "app"))))
    (rdc/render @react-root
                [rf/frame-provider {:id app-frame
                                    :initial-events [[:flight/initialise]]}
                 [flight-booker]])))
