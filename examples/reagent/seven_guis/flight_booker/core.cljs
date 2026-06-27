(ns seven-guis.flight-booker.core
  "7GUIs #3 — Flight Booker.

   A combo box (one-way / return), two date inputs, a Book button.
   Rules:
     - 'one-way': second date input is disabled.
     - 'return': both date inputs are enabled; return-date must be ≥ start-date.
     - The Book button is enabled only when all visible dates parse and the
       (return ≥ start) constraint holds.
     - Clicking Book pops up a confirmation message.

   This task is really about *derived UI state*. The Book button's
   enabled-ness depends on three other fields, and the imperative way to keep
   it correct — recompute it after every keystroke, in every handler — is the
   kind of thing you get right four times and wrong the fifth. So we don't.
   We make it a subscription. It recomputes itself whenever its inputs change,
   and there is no fifth path to forget.

   Demonstrates:
   - A schema-bound app-db slice
   - One event per distinct user intent
   - Layered subs built with :<- chains
   - UI state driven by sub return values"
  (:require [reagent.dom.client :as rdc]
            [re-frame.core :as rf]
            ;; Loading re-frame.schemas registers its late-bind hooks, which
            ;; is what makes rf/reg-app-schema resolve below.
            [re-frame.schemas]
            [re-frame.views]
            [re-frame.adapter.reagent :as reagent-adapter])
  (:require-macros [re-frame.core :refer [reg-view with-frame]]))

;; ============================================================================
;; SCHEMA
;; ============================================================================

(def date-pattern  #"^\d{4}-\d{2}-\d{2}$")    ;; ISO yyyy-mm-dd

;; The slice stores dates as the raw text the user typed, not as parsed dates.
;; That's deliberate: someone mid-keystroke at "2026-05-" hasn't typed a date
;; yet, and we don't want app-db to lie about that. Parsing and validity are
;; questions we ask later, in the subscriptions.
(def FlightState
  [:map
   [:trip-type   [:enum :one-way :return]]
   [:start-text  :string]
   [:return-text :string]])

;; A schema is frame-local, so it binds inside a frame. The render root runs
;; this app in `:rf/default` (see the `frame-provider {:id …}` below), so we
;; name that frame here. Binding is by id, which is why this works even though
;; it runs at ns-load — before the provider has actually created the frame.
;; From here on, every commit to the `[:flight]` slice is validated.
(with-frame :rf/default
  (rf/reg-app-schema [:flight] {:schema FlightState}))

;; ============================================================================
;; EVENTS
;; ============================================================================

(rf/reg-event :flight/initialise
  {:doc "Seed the flight slice."}
  (fn handler-flight-initialise [{:keys [db]} _]
    ;; Both date fields start on the same valid ISO date. The 7GUIs task seeds
    ;; "today", but a constant keeps the example deterministic — and keeps the
    ;; tests from failing at midnight.
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
;;
;; This is where the example earns its keep. The slice holds three raw fields;
;; the UI needs answers — is this date valid? should Book be clickable? — and
;; we compute those as a small chain of subscriptions.
;;
;; The base layer (`:flight/trip-type`, `:flight/start-text`,
;; `:flight/return-text`) just reads the slice. Each sub above it asks one
;; sharper question by `:<-` chaining off the answers below: is the start date
;; valid, does return need validating at all, do the two dates make sense
;; together. `:flight/book-enabled?` sits at the top and ANDs the lot.
;;
;; The payoff: the button's enabled-ness is a pure function of state that
;; recomputes itself. Nothing in the event handlers has to remember to keep it
;; in sync, because nothing in the event handlers ever touches it.

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
                    ;; ISO dates sort correctly as plain strings — the whole
                    ;; reason the format is yyyy-mm-dd — so a string compare is
                    ;; all we need to check return ≥ start.
                    (<= (compare s r) 0)))))

(rf/reg-sub :flight/book-enabled?
  {:doc "The keystone sub: Book is clickable only when the start date is
         valid, the return date is valid (or unused), and the two are in a
         sensible order. Three smaller answers, ANDed into one."}
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

;; The React root lives in an atom and is created lazily inside `run`, never at
;; ns-load. Loading a namespace must do no DOM side effects, so that co-loaded
;; example namespaces don't race each other to `create-root` onto the shared
;; `#app`. See examples/TESTING.md, the Example mount-isolation convention.
(defonce react-root (atom nil))

;; The whole frame lifecycle lives in one place: the `frame-provider {:id
;; app-frame …}` at the render root below. On first mount it creates the app
;; frame, applies its config, and runs `:initial-events` once to seed app-db.
;; On hot reload it reuses the same frame and skips re-seeding, so your
;; half-filled form survives a save. `app-frame` is just an id we pick and hand
;; to the provider — the runtime won't guess it for us. Same mount shape as the
;; counter: examples/reagent/counter/core.cljs.
(def app-frame :rf/default)

(defn run []
  ;; `init!` installs the reactive adapter for the process. The adapter ns
  ;; exports an `adapter` var; pass it straight in.
  (rf/init! reagent-adapter/adapter)
  (when (exists? js/document)
    (when-not @react-root
      (reset! react-root (rdc/create-root (js/document.getElementById "app"))))
    (rdc/render @react-root
                [rf/frame-provider {:id app-frame
                                    :initial-events [[:flight/initialise]]}
                 [flight-booker]])))
