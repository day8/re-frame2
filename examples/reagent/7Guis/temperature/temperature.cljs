(ns temperature.temperature
  "7GUIs #2 — Temperature Converter.

   Two text fields; entering a value in either updates the other via the
   conversion C = (F - 32) × 5/9.

   The 7GUIs page calls this out as a test of *bidirectional dataflow* — the
   classic trap is to wire the two fields to each other directly, which
   creates an update loop or a stale-value race.

   The re-frame2 solution: there is *one source of truth* in app-db, plus
   *one event* that updates it. Both inputs read from the same source through
   subscriptions; both write to it through the same event. No bidirectional
   wiring; the architecture eliminates the trap.

   Demonstrates:
   - Single source of truth in app-db                    (Goal 7 / application-state)
   - Event + sub composition over a single value          (CP-1, CP-2)
   - Pure derivation in subs (Celsius ↔ Fahrenheit)        (CP-2)
   - Schema-bound app-db slice                            (CP-8)"
  (:require [clojure.string :as str]
            [reagent2.dom.client :as rdc]
            [re-frame.core :as rf]
            ;; `re-frame.schemas` ships in day8/re-frame2-schemas.
            ;; Loading the ns here registers its late-bind hooks so
            ;; rf/reg-app-schema resolves.
            [re-frame.schemas]
            [re-frame.views]
            [re-frame.adapter.reagent-slim :as reagent-slim-adapter])
  (:require-macros [re-frame.core :refer [reg-view]]))

;; ============================================================================
;; SCHEMA
;; ============================================================================
;;
;; The slice holds a single canonical temperature in Celsius. Fahrenheit is
;; derived. The `:input-source` field tracks which input the user is currently
;; editing — it lets the *other* input show a derived value while the active
;; input shows exactly what the user typed (so partial input like "1." doesn't
;; round-trip into "1°C → 33.8°F → 33.8°F → 1.0°C" jitter).

(def TempState
  [:map
   [:celsius      [:maybe :double]]      ;; canonical value; nil for empty input
   [:input-source [:enum :celsius :fahrenheit]]
   [:typing       :string]])              ;; the literal text the user is typing

(rf/reg-app-schema [:temp] TempState)

;; ============================================================================
;; EVENTS
;; ============================================================================

(rf/reg-event-db :temp/initialise
  {:doc "Seed the temperature slice."}
  (fn handler-temp-initialise [db _]
    (assoc db :temp {:celsius 0.0 :input-source :celsius :typing "0"})))

(defn parse-num [s]
  (let [trimmed (str/trim s)]
    (when-not (str/blank? trimmed)
      (let [n (js/parseFloat trimmed)]
        (when-not (js/isNaN n) n)))))

(rf/reg-event-db :temp/edit-celsius
  {:doc "User edited the Celsius input."}
  (fn handler-temp-edit-celsius [db [_ raw]]
    (assoc db :temp {:celsius      (parse-num raw)
                     :input-source :celsius
                     :typing       raw})))

(rf/reg-event-db :temp/edit-fahrenheit
  {:doc "User edited the Fahrenheit input. We store Celsius canonically;
         conversion happens here so the rest of the app reads from one path."}
  (fn handler-temp-edit-fahrenheit [db [_ raw]]
    (let [f (parse-num raw)
          c (when f (* (- f 32) (/ 5.0 9.0)))]
      (assoc db :temp {:celsius      c
                       :input-source :fahrenheit
                       :typing       raw}))))

;; ============================================================================
;; SUBSCRIPTIONS
;; ============================================================================
;;
;; Three subs:
;;   :temp/active        — which input is the user editing
;;   :temp/celsius-text  — what to show in the Celsius input
;;   :temp/fahrenheit-text — what to show in the Fahrenheit input
;;
;; The `-text` subs return the user's raw typing for the active input and a
;; derived value for the inactive one. This is the key insight: a single sub
;; per field that knows whether to passthrough or derive.

(rf/reg-sub :temp/active
  (fn sub-temp-active [db _] (get-in db [:temp :input-source])))

(rf/reg-sub :temp/canonical-celsius
  (fn sub-temp-canonical-celsius [db _] (get-in db [:temp :celsius])))

(rf/reg-sub :temp/typing
  (fn sub-temp-typing [db _] (get-in db [:temp :typing])))

(rf/reg-sub :temp/celsius-text
  {:doc "Text to display in the Celsius input."}
  :<- [:temp/active]
  :<- [:temp/typing]
  :<- [:temp/canonical-celsius]
  (fn sub-temp-celsius-text [[active typing c] _]
    (case active
      :celsius     typing
      :fahrenheit  (when c (.toFixed (double c) 2)))))

(rf/reg-sub :temp/fahrenheit-text
  {:doc "Text to display in the Fahrenheit input."}
  :<- [:temp/active]
  :<- [:temp/typing]
  :<- [:temp/canonical-celsius]
  (fn sub-temp-fahrenheit-text [[active typing c] _]
    (case active
      :fahrenheit  typing
      :celsius     (when c (.toFixed (+ (* c (/ 9.0 5.0)) 32) 2)))))

;; ============================================================================
;; VIEW
;; ============================================================================

(reg-view ^{:doc "Two-input temperature converter."}
          temperature-converter []
  (let [c-text @(subscribe [:temp/celsius-text])
        f-text @(subscribe [:temp/fahrenheit-text])]
    [:div.temperature
     [:input {:type      "text"
              :value     (or c-text "")
              :data-testid "temp-celsius"
              :on-change #(dispatch [:temp/edit-celsius (.. % -target -value)])}]
     [:label " °C  =  "]
     [:input {:type      "text"
              :value     (or f-text "")
              :data-testid "temp-fahrenheit"
              :on-change #(dispatch [:temp/edit-fahrenheit (.. % -target -value)])}]
     [:label " °F"]]))

;; ============================================================================
;; MOUNT
;; ============================================================================

(defonce root
  (rdc/create-root (js/document.getElementById "app")))

(defn ^:export run []
  ;; Pass the adapter spec map directly — no registry.
  (rf/init! reagent-slim-adapter/adapter)
  (rf/dispatch-sync [:temp/initialise])
  (rdc/render root [temperature-converter]))
