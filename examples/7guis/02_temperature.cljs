(ns seven-guis.temperature
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
  (:require [reagent.dom.client :as rdc]
            [re-frame-2.core :as rf])
  (:require-macros [re-frame-2.views-macros :refer [reg-view with-frame]]))

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
  (let [trimmed (clojure.string/trim s)]
    (when-not (clojure.string/blank? trimmed)
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
          c (when f (* (- f 32) 5/9))]
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
      :celsius     (when c (.toFixed (+ (* c 9/5) 32) 2)))))

;; ============================================================================
;; VIEW
;; ============================================================================

(def temperature-converter
  (reg-view :temp/converter
    {:doc "Two-input temperature converter."}
    (fn render-temp-converter []
      (let [d      (rf/dispatcher)
            s      (rf/subscriber)
            c-text @(s [:temp/celsius-text])
            f-text @(s [:temp/fahrenheit-text])]
        [:div.temperature
         [:input {:type      "text"
                  :value     (or c-text "")
                  :on-change #(d [:temp/edit-celsius (.. % -target -value)])}]
         [:label " °C  =  "]
         [:input {:type      "text"
                  :value     (or f-text "")
                  :on-change #(d [:temp/edit-fahrenheit (.. % -target -value)])}]
         [:label " °F"]]))))

;; ============================================================================
;; HEADLESS TESTS
;; ============================================================================

(defn temperature-converter-tests []
  (with-frame [f (rf/make-frame {:on-create [:temp/initialise]})]
    ;; Edit Celsius → Fahrenheit derives.
    (rf/dispatch-sync [:temp/edit-celsius "100"] {:frame f})
    (assert (= "100"   (rf/compute-sub [:temp/celsius-text]    (rf/get-frame-db f))))
    (assert (= "212.00" (rf/compute-sub [:temp/fahrenheit-text] (rf/get-frame-db f))))

    ;; Edit Fahrenheit → Celsius derives.
    (rf/dispatch-sync [:temp/edit-fahrenheit "32"] {:frame f})
    (assert (= "32"   (rf/compute-sub [:temp/fahrenheit-text] (rf/get-frame-db f))))
    (assert (= "0.00" (rf/compute-sub [:temp/celsius-text]    (rf/get-frame-db f))))

    ;; Partial input doesn't jitter — typing "1." in Celsius shows literally
    ;; "1." in the active field, not "1.00".
    (rf/dispatch-sync [:temp/edit-celsius "1."] {:frame f})
    (assert (= "1." (rf/compute-sub [:temp/celsius-text] (rf/get-frame-db f))))

    ;; Garbage input clears the conversion but preserves the literal text.
    (rf/dispatch-sync [:temp/edit-celsius "abc"] {:frame f})
    (assert (= "abc" (rf/compute-sub [:temp/celsius-text] (rf/get-frame-db f))))
    (assert (nil?    (rf/compute-sub [:temp/fahrenheit-text] (rf/get-frame-db f))))))

;; ============================================================================
;; MOUNT
;; ============================================================================

(defonce root
  (rdc/create-root (js/document.getElementById "app")))

(defn ^:export run []
  (rdc/render root [temperature-converter]))
