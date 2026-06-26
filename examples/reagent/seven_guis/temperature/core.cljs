(ns seven-guis.temperature.core
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
            [reagent.dom.client :as rdc]
            [re-frame.core :as rf]
            ;; `re-frame.schemas` ships in day8/re-frame2-schemas.
            ;; Loading the ns here registers its late-bind hooks so
            ;; rf/reg-app-schema resolves.
            [re-frame.schemas]
            [re-frame.views]
            [re-frame.adapter.reagent :as reagent-adapter])
  (:require-macros [re-frame.core :refer [reg-view with-frame]]))

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

;; EP-0002: reg-app-schema is context-required frame-local; a
;; bare ns-load call raises :rf.error/no-frame-context. This example runs in
;; :rf/default (see `run`/`reg-frame app-frame`), so name it explicitly so the
;; schema binds to the app frame whose commits it validates.
(with-frame :rf/default
  (rf/reg-app-schema [:temp] {:schema TempState}))

;; ============================================================================
;; EVENTS
;; ============================================================================

(rf/reg-event :temp/initialise
  {:doc "Seed the temperature slice."}
  (fn handler-temp-initialise [{:keys [db]} _]
    {:db (assoc db :temp {:celsius 0.0 :input-source :celsius :typing "0"})}))

(def num-re
  "Full-string numeric grammar: optional sign, integer and/or fractional
   part, optional exponent. Anchored end-to-end so lax prefixes like
   \"1abc\" or \"1.2.3\" are rejected — `js/parseFloat` would otherwise
   silently accept their leading numeric run (\"1abc\" → 1)."
  #"^[+-]?(\d+\.?\d*|\.\d+)([eE][+-]?\d+)?$")

(defn parse-num [s]
  (let [trimmed (str/trim s)]
    (when (re-matches num-re trimmed)
      (let [n (js/parseFloat trimmed)]
        (when-not (js/isNaN n) n)))))

(rf/reg-event :temp/edit-celsius
  {:doc "User edited the Celsius input."}
  (fn handler-temp-edit-celsius [{:keys [db]} [_ raw]]
    {:db (assoc db :temp {:celsius      (parse-num raw)
                     :input-source :celsius
                     :typing       raw})}))

(rf/reg-event :temp/edit-fahrenheit
  {:doc "User edited the Fahrenheit input. We store Celsius canonically;
         conversion happens here so the rest of the app reads from one path."}
  (fn handler-temp-edit-fahrenheit [{:keys [db]} [_ raw]]
    {:db (let [f (parse-num raw)
          c (when f (* (- f 32) (/ 5.0 9.0)))]
      (assoc db :temp {:celsius      c
                       :input-source :fahrenheit
                       :typing       raw}))}))

;; ============================================================================
;; SUBSCRIPTIONS
;; ============================================================================
;;
;; Two layers. Three small Layer-2 subs read the slice directly
;; (:temp/active, :temp/canonical-celsius, :temp/typing); two Layer-3
;; `-text` subs chain off all three via `:<-` to produce what each input
;; shows. The view only ever reads the two `-text` subs.
;;
;; The key insight lives in those `-text` subs: for the input the user is
;; *currently editing* they pass the raw typing straight through; for the
;; *other* input they show the derived value. One sub per field that knows
;; whether to passthrough or derive — which is why partial input like "1."
;; doesn't round-trip into jitter while your finger's still on the key.

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

;; The React root is held in an atom and materialised lazily inside `run`
;; (not at ns-load) per examples/TESTING.md §Example mount-isolation
;; convention: ns-load must produce no DOM side effects so co-required
;; example namespaces don't race `create-root` onto the shared `#app`.
(defonce react-root (atom nil))

;; EP-0002: under the carried invariant the runtime never
;; synthesises a frame from absence — an app must establish its frame
;; explicitly. `init!` installs the adapter (it does NOT create the frame),
;; `reg-frame` registers the app frame, the boot dispatch runs under
;; `with-frame`, and the render is wrapped in a `frame-provider` so every
;; in-tree `dispatch`/`subscribe` resolves to the app frame. Matches the
;; canonical mount in examples/reagent/counter/core.cljs.
(def app-frame :rf/default)

(defn run []
  ;; Pass the adapter spec map directly — no registry.
  (rf/init! reagent-adapter/adapter)
  (rf/reg-frame app-frame {})
  (rf/with-frame app-frame
    (rf/dispatch-sync [:temp/initialise]))
  (when (exists? js/document)
    (when-not @react-root
      (reset! react-root (rdc/create-root (js/document.getElementById "app"))))
    (rdc/render @react-root
                [rf/frame-provider-existing {:frame app-frame}
                 [temperature-converter]])))
