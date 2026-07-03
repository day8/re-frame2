(ns seven-guis.temperature.core
  "7GUIs #2 — Temperature Converter: two text fields, where typing in either
   one updates the other through the conversion C = (F - 32) × 5/9.

   The 7GUIs page poses this as a test of *bidirectional dataflow*, and the
   obvious approach is the trap: wire the two fields straight to each other.
   Do that and they chase each other in circles — an update loop, or a race
   to show a stale value.

   re-frame2 sidesteps the whole thing. Keep *one* canonical temperature in
   app-db and *one* event that writes it. Both inputs read from that single
   value through subscriptions and write back through that single event. The
   fields never speak to each other, so they can't fight. The trap simply
   never opens.

   That gives us a tidy tour of the basics: a single source of truth, one
   event and one subscription chain over one value, pure Celsius ↔ Fahrenheit
   derivation in the subs, and a schema watching the slice. The guide walks
   subscriptions slowly in `docs/core/concepts/subscriptions.md`; event,
   subscription, and app-db each have a glossary entry in
   `docs/core/glossary.md`."
  (:require [clojure.string :as str]
            [reagent.dom.client :as rdc]
            [re-frame.core :as rf]
            ;; `re-frame.schemas` ships in day8/re-frame2-schemas. Required
            ;; for its side effect: loading this ns installs the late-bind
            ;; hooks that make `rf/reg-app-schema` resolve. No var from it is
            ;; used directly — it just has to be on the page.
            [re-frame.schemas]
            [re-frame.adapter.reagent :as reagent-adapter]))

;; ============================================================================
;; SCHEMA
;; ============================================================================
;;
;; One canonical temperature, in Celsius. Fahrenheit is always derived from
;; it, never stored. The other two fields are about being kind to the person
;; at the keyboard. `:input-source` remembers which box they're editing, and
;; `:typing` keeps the exact characters they've typed so far. The field in
;; focus echoes that raw text back verbatim; the *other* field shows the
;; derived number. Without this, typing "1." would round-trip through Celsius
;; and reformat itself to "1.00" while your finger is still on the dot — the
;; kind of jitter that makes an input feel possessed.

(def TempState
  [:map
   [:celsius      [:maybe :double]]      ;; canonical value; nil when the box is empty
   [:input-source [:enum :celsius :fahrenheit]]
   [:typing       :string]])              ;; the raw characters the user is typing

;; Schemas are frame-local, so registration happens inside a frame. The
;; `frame-provider` at the render root runs this app in `:rf/default`, so we
;; name that same frame here and bind the schema to it. From then on, every
;; commit to the `[:temp]` slice is checked against `TempState`.
(rf/with-frame :rf/default
  (rf/reg-app-schema [:temp] {:schema TempState}))

;; ============================================================================
;; EVENTS
;; ============================================================================

(rf/reg-event :temp/initialise
  {:doc "Seed the slice with a starting value: 0 °C, focus on Celsius."}
  (fn handler-temp-initialise [{:keys [db]} _]
    {:db (assoc db :temp {:celsius 0.0 :input-source :celsius :typing "0"})}))

;; A strict numeric grammar for the inputs: optional sign, an integer and/or
;; fractional part, optional exponent — anchored end to end. The anchoring is
;; the point. `js/parseFloat` is famously lax: hand it "1abc" and it shrugs
;; and returns 1, having quietly ignored the rest. Matching the whole string
;; means "1abc" and "1.2.3" are rejected outright, so half-garbage never
;; reaches the canonical value.
(def num-re
  "Full-string numeric grammar: optional sign, integer and/or fractional
   part, optional exponent, anchored end to end (so `js/parseFloat`'s lax
   prefix-matching can't sneak \"1abc\" through as 1)."
  #"^[+-]?(\d+\.?\d*|\.\d+)([eE][+-]?\d+)?$")

(defn parse-num
  "Parse a field's text into a number, or nil if it isn't a clean one. Trim
   the whitespace, insist on a full numeric match, then let `parseFloat`
   finish the job — with a NaN backstop, belt and braces."
  [s]
  (let [trimmed (str/trim s)]
    (when (re-matches num-re trimmed)
      (let [n (js/parseFloat trimmed)]
        (when-not (js/isNaN n) n)))))

(rf/reg-event :temp/edit-celsius
  {:doc "User typed in the Celsius box. It's already our canonical unit, so we
         store the parsed number straight, and remember the raw keystrokes."}
  (fn handler-temp-edit-celsius [{:keys [db]} [_ raw]]
    {:db (assoc db :temp {:celsius      (parse-num raw)
                     :input-source :celsius
                     :typing       raw})}))

(rf/reg-event :temp/edit-fahrenheit
  {:doc "User typed in the Fahrenheit box. We convert to Celsius right here, on
         the way in, so everything downstream reads from the one canonical
         value and never has to know Fahrenheit existed."}
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
;; A little two-layer graph. Three tiny *extractors* (Layer 1) pluck the raw
;; fields straight out of the slice: :temp/active, :temp/canonical-celsius,
;; :temp/typing. Then two *derivations* (Layer 2) chain off all three with
;; `:<-` to compute what each box should actually display. The view reads only
;; those two `-text` subs and stays blissfully unaware of the rest.
;;
;; The cleverness all lives in the two `-text` subs, and it's one rule: for
;; the box you're *currently editing*, hand back your raw keystrokes; for the
;; *other* box, show the converted value. Each field gets one sub that decides
;; pass-through vs. derive — which is the whole reason typing "1." doesn't
;; reformat itself out from under you mid-keystroke.
;;
;; For the layers, the equality gate, and the `:<-` arrow in full, see
;; `docs/core/concepts/subscriptions.md`.

(rf/reg-sub :temp/active
  (fn sub-temp-active [db _] (get-in db [:temp :input-source])))

(rf/reg-sub :temp/canonical-celsius
  (fn sub-temp-canonical-celsius [db _] (get-in db [:temp :celsius])))

(rf/reg-sub :temp/typing
  (fn sub-temp-typing [db _] (get-in db [:temp :typing])))

(rf/reg-sub :temp/celsius-text
  {:doc "What the Celsius box shows: raw keystrokes while it's the active box,
         otherwise the canonical value formatted to two places."}
  :<- [:temp/active]
  :<- [:temp/typing]
  :<- [:temp/canonical-celsius]
  (fn sub-temp-celsius-text [[active typing c] _]
    (case active
      :celsius     typing
      :fahrenheit  (when c (.toFixed (double c) 2)))))

(rf/reg-sub :temp/fahrenheit-text
  {:doc "What the Fahrenheit box shows: raw keystrokes while it's the active
         box, otherwise the canonical Celsius converted to °F, two places."}
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
;;
;; And here's the payoff. Two inputs, each reading its `-text` sub for what to
;; show and dispatching an edit event for what was typed. That's the entire
;; view — no conversion, no cross-wiring, no peeking at the other field. The
;; bidirectional "trap" the 7GUIs page warns about never even comes up, because
;; the fields don't know each other exists.

(rf/reg-view ^{:doc "Two text inputs, °C and °F, kept in sync through app-db."}
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

;; The React root lives in an atom and is created lazily inside `run`, never at
;; ns-load. The rule: loading a namespace must touch no DOM. Otherwise two
;; example namespaces required together would both grab for the shared `#app`
;; element and race to `create-root` on it — and a race in your test harness is
;; precisely the bug you don't want. See examples/TESTING.md, "mount-isolation".
(defonce react-root (atom nil))

;; The frame's whole life story fits in one place: the `frame-provider {:id
;; app-frame …}` in `run` below. First mount creates the frame and runs its
;; `:initial-events` once to seed app-db. After that, every `dispatch` and
;; `subscribe` in the tree finds its way home to that frame. Mount again under
;; the same `:id` — a hot reload, say — and the provider hands back the frame
;; that's already alive instead of re-seeding, so your half-typed temperature
;; survives the save. See `docs/core/concepts/frames.md`.
;;
;; `app-frame` is just an id we picked. `:rf/default` looks special but isn't —
;; it's an ordinary frame id. The runtime never guesses which frame you mean,
;; so we name one here and hand it over explicitly, the same way the counter
;; example does (`examples/core/counter/core.cljs`). See
;; `docs/core/glossary.md#frame-identity-is-carried-not-found`.
(def app-frame :rf/default)

(defn run []
  ;; `init!` tells re-frame2 which reactive substrate to render through — here,
  ;; Reagent. Each adapter ns exports an `adapter` var; you require the ns and
  ;; pass that var. One call, at startup. Note it installs the adapter, not a
  ;; frame — the frame comes later, via the provider below.
  ;; See `docs/core/glossary.md#init`.
  (rf/init! reagent-adapter/adapter)
  (when (exists? js/document)
    (when-not @react-root
      (reset! react-root (rdc/create-root (js/document.getElementById "app"))))
    (rdc/render @react-root
                [rf/frame-provider {:id app-frame
                                    :initial-events [[:temp/initialise]]}
                 [temperature-converter]])))
