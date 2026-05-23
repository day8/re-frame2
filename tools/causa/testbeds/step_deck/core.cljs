(ns step-deck.core
  "Single-frame STEP-DECK testbed (rf2-6qgbs.2) — a clean, step-by-step
  surface for exercising re-frame2 features one frame at a time, paired
  with a Causa instance on the right.

  ## Shape

  ONE page, two columns of attention:

    ┌──────────────────────────┬──────────────┐
    │  STEP-DECK (one frame)   │              │
    │  ┌────────────────────┐  │   Causa      │
    │  │ tabs: Counter …    │  │   (inline)   │
    │  ├────────────────────┤  │              │
    │  │ ACTION  → CHECK     │  │   one frame  │
    │  │ (do this) (observe) │  │   — no       │
    │  └────────────────────┘  │   frame      │
    └──────────────────────────┴──────────────┘   picker
                                                   needed

  Where the two-frame isolation testbed (`two-frame-isolation.core`,
  rf2-6qgbs.1) mounts the shared `testdeck.panel` TWICE to prove
  per-frame isolation, the step-deck mounts it ONCE — the single-frame
  counterpart. It is the surface you reach for when you want to drive a
  feature step-by-step and watch the result without the cognitive load
  of two diverging frames.

  ## Action → check cards

  The step-deck wraps the shared panel in an ACTION → CHECK framing.
  Each feature tab is presented as a step:

    - ACTION  — the panel's own controls (the buttons / inputs the
      testdeck modules already register). DO this.
    - CHECK   — a live readout of the canonical observable for that
      tab, derived from the SAME testdeck subs. OBSERVE this, and
      cross-check it against the matching Causa lens on the right.

  The CHECK strip is not a second copy of the tab logic — it reads the
  same per-frame subs the panel reads (`:counter/value`, `:ws/state`,
  `:testdeck/active-tab`, `:testdeck.async/status`) and states, in one
  line, what just changed and where to corroborate it in Causa. The
  ACTION surface IS the imported `testdeck.panel/panel`; nothing about
  the tab set, the controls, or the routing is reimplemented here.

  ## Single, URL-bound frame

  The step-deck registers ONE content frame (`:step-deck`) and makes it
  the sole owner of the browser URL. Per Spec 012 §Multi-frame routing a
  non-default frame is `:url-bound? false` by default, so this mount must
  do TWO things to claim the URL (rf2-6qgbs.3): register `:rf/default`
  `:url-bound? false` to release the auto-registered default frame's
  implicit ownership, then register `:step-deck` `:url-bound? true` so it
  becomes the one explicit URL owner. Because there is then exactly one
  routed, url-bound frame, tab navigation (`:rf.route/navigate`, fired by
  the panel's tab bar) syncs the address bar and the back button with no
  two-frame URL race (contrast the two-frame testbed, which keeps BOTH
  frames non-url-bound). Open `/machine` directly and the frame lands on
  the Machine tab.

  ## Shared testdeck modules — reused verbatim

  The app code path is the shared testdeck (`testdeck.*`, under
  `tools/causa/testbeds/testdeck/`). Loading those namespaces installs
  their late-bind hooks and registers every handler / sub / route /
  view ONCE globally; the step-deck supplies only the single-frame
  harness + the action→check framing:

    - `testdeck.routes`  — route table + tab inventory (tabs ARE routes)
    - `testdeck.counter` — L2 sub create/destroy, changeable-arg
                           show-greater-than-N view, `now` cofx, clean
                           handler-exception path
    - `testdeck.machine` — interactive websocket connection machine
    - `testdeck.async`   — async fx (~600ms slow request) + error surface
    - `testdeck.panel`   — the reusable tabbed app shell + per-frame
                           `:on-create` boot

  ## Test surface, not tutorial

  No deliberate bugs, no teaching layers, no anti-pattern demos
  (feedback_testbeds_are_test_surfaces). The slow async fetch and the
  clean handler-exception path on the Counter tab are FEATURES being
  exercised — they light up Causa's Issues lens legitimately. This
  testbed is test-free (rf2-8cevm); regression coverage lives in the
  substrate contract tests + the Causa feature-matrix gate."
  (:require [reagent.dom.client :as rdc]
            [re-frame.core :as rf]
            ;; Loading these namespaces installs their late-bind hooks
            ;; and registers every testdeck handler / sub / route / view
            ;; ONCE globally. The panel is the shared app code path the
            ;; step-deck mounts (the same one the two-frame testbed does).
            [re-frame.routing]
            [testdeck.routes]
            [testdeck.counter]
            [testdeck.machine]
            [testdeck.async]
            [testdeck.panel :as panel]
            [re-frame.adapter.reagent :as reagent-adapter]
            ;; rf2-6jyf6 — Causa's `configure!` to seed `:project-root`
            ;; so the Event lens 'open' chip resolves a classpath-relative
            ;; `:file` slot to an absolute on-disk URI.
            [day8.re-frame2-causa.config :as causa-config])
  (:require-macros [re-frame.core :refer [reg-view]]))

;; ============================================================================
;; FRAME ID — exactly one, URL-bound
;; ============================================================================

(def frame-id :step-deck)

;; The label the testdeck panel threads through every `:data-testid` and
;; through the per-tab CHECK readout below. One frame, one label.
(def ^:private frame-label "step")

;; ============================================================================
;; CHECK STRIP — the canonical observable per tab (live, per-frame subs)
;; ============================================================================
;;
;; Each step pairs an ACTION (the panel's controls) with a CHECK: a
;; one-line live readout of the tab's canonical observable, read from
;; the SAME testdeck subs the panel reads. The CHECK never recomputes
;; or duplicates tab logic — it states what to observe and where to
;; corroborate it in Causa.

(def ^:private mono
  {:font-family "ui-monospace, SFMono-Regular, Menlo, monospace"})

(defn- check-line
  "One CHECK row: an observable name, its live value, and the Causa lens
  that corroborates it. Pure presentation over already-subscribed data."
  [observable value lens]
  [:div {:style {:display "flex" :gap "8px" :align-items "baseline"
                 :margin "2px 0"}}
   [:span {:style {:font-size "11px" :color "#777" :min-width "11em"}}
    observable]
   [:span {:style (merge mono {:font-weight "bold" :color "#333"
                               :min-width "5em"})}
    (str value)]
   [:span {:style {:font-size "11px" :color "#aaa"}}
    "↔ Causa " lens]])

(reg-view check-strip
  "The CHECK half of the active step. Switches on the per-frame active
  tab and renders the canonical live observable(s) for that tab, drawn
  from the testdeck subs the panel itself reads. Reuse, not duplication:
  these are the same subscriptions, surfaced as a 'what to observe' card."
  []
  (let [active @(subscribe [:testdeck/active-tab])]
    [:div {:data-testid (str frame-label "-check-strip")
           :style {:border-top "1px dashed #ddd" :margin-top "0.75em"
                   :padding-top "0.5em"}}
     [:div {:style {:font-size "11px" :font-weight "bold" :color "#7C5CFF"
                    :text-transform "uppercase" :letter-spacing "0.04em"
                    :margin-bottom "0.25em"}}
      "Check"]
     (case active
       :testdeck/counter
       [:div
        (check-line ":counter/value" @(subscribe [:counter/value]) "App-db")
        (check-line ":counter/greater-than? 5"
                    @(subscribe [:counter/greater-than? 5]) "Views")
        (check-line ":counter/show-parity?"
                    @(subscribe [:counter/show-parity?]) "Views (L2 create/destroy)")]

       :testdeck/machine
       [:div
        (check-line ":ws/state" @(subscribe [:ws/state]) "Machines")
        (check-line ":ws/connected?" @(subscribe [:ws/connected?]) "Machines")
        (check-line ":ws/connections" @(subscribe [:ws/connections]) "App-db")]

       :testdeck/routing
       [:div
        (check-line ":testdeck/active-tab" active "Routing")]

       :testdeck/async
       [:div
        (check-line ":testdeck.async/status"
                    @(subscribe [:testdeck.async/status]) "Issues (slow fx)")
        (check-line ":testdeck.async/count"
                    @(subscribe [:testdeck.async/count]) "App-db")]

       [:div (check-line ":testdeck/active-tab" active "Routing")])]))

;; ============================================================================
;; ROOT VIEW — one step-card wrapping the shared panel
;; ============================================================================

(reg-view step-card
  "ACTION → CHECK card. The ACTION surface is the imported
  `testdeck.panel/panel` (the tab set + every control, registered once
  by the testdeck modules). The CHECK strip below reports the active
  tab's canonical observable. Nothing about the tabs is reimplemented."
  []
  [:section {:data-testid (str frame-label "-step-card")
             :style {:border        "1px solid #d8d2ff"
                     :border-radius "8px"
                     :padding       "0.75em 1em"
                     :background    "#fcfbff"
                     :margin        "0.5em 0"}}
   [:div {:style {:font-size "11px" :font-weight "bold" :color "#7C5CFF"
                  :text-transform "uppercase" :letter-spacing "0.04em"
                  :margin-bottom "0.25em"}}
    "Action"]
   ;; ACTION — the shared panel, mounted ONCE in this frame.
   [panel/panel frame-label]
   ;; CHECK — the canonical live observable for the active tab.
   [check-strip]])

(reg-view root []
  [:div {:data-testid "step-deck-root"
         :style {:font-family "system-ui, sans-serif"
                 :padding     "1em"
                 :max-width   "640px"}}
   [:header {:style {:margin-bottom "1em"}}
    [:h2 {:style {:margin 0}} "Step-deck"]
    [:p {:style {:color "#444" :margin "0.5em 0 0 0"}}
     "One frame, step by step. Each tab is a feature to drive: "
     [:strong "act"] " on the controls, then read the "
     [:strong "check"] " strip below and corroborate it against the "
     "matching Causa lens on the right. Tabs are routes — switching a "
     "tab updates the address bar (this frame owns the URL)."]]
   [:div {:style {:display "flex" :flex-direction "column"}}
    [rf/frame-provider {:frame frame-id}
     [step-card]]]])

;; ============================================================================
;; MOUNT
;; ============================================================================

(defonce react-root
  (rdc/create-root (js/document.getElementById "app")))

(def ^:private default-project-root
  "C:/Users/miket/code/re-frame2/tools/causa/testbeds")

(defn- query-param
  "Return the named URL query param as a string, or nil when absent /
  blank. Pure-data helper — kept private to this testbed since the
  query-string override is a per-host knob (not a Causa-API surface)."
  [param-name]
  (when (exists? js/window)
    (let [params (-> js/window .-location .-search (js/URLSearchParams.))
          v      (.get params param-name)]
      (when (and (string? v) (seq v)) v))))

(defn- resolve-project-root []
  (or (query-param "project-root") default-project-root))

(defn ^:export run []
  ;; Configure Causa BEFORE `rf/init!` so the preload's auto-open reads
  ;; the right project-root on its first paint of any chip.
  (causa-config/configure! {:rf.causa/project-root (resolve-project-root)})
  (rf/init! reagent-adapter/adapter)
  ;; URL ownership (Spec 012 §Multi-frame routing). The content lives in
  ;; the NON-DEFAULT frame :step-deck, but a non-default frame defaults to
  ;; `:url-bound? false` — its route changes would stay in-memory and the
  ;; address bar would never move (rf2-6qgbs.3). The step-deck is
  ;; single-frame and OWNS the URL, so it must be the one URL-bound frame:
  ;;
  ;;   - :rf/default is registered `:url-bound? false` to RELEASE the
  ;;     implicit ownership the auto-registered default frame holds. Without
  ;;     this opt-out `url-owner-frame-id` keeps awarding the URL to
  ;;     :rf/default (its missing `:url-bound?` reads as default-true), so a
  ;;     bare `:step-deck {:url-bound? true}` would lose the tie.
  ;;   - :step-deck is registered `:url-bound? true` so it becomes the sole
  ;;     URL owner: `:rf.nav/push-url` now fires for its tab nav and the
  ;;     address bar + back button track the active tab.
  ;;
  ;; With default opted out and exactly one explicit owner there is no
  ;; two-frame URL race (contrast `two-frame-isolation.core`, which keeps
  ;; BOTH frames non-url-bound for per-frame in-memory routing). The
  ;; `:on-create` boot (`:testdeck/initialise`, registered once globally in
  ;; `testdeck.panel`) seeds the frame's app-db for all four tabs and lands
  ;; it on the default tab — its `[:rf.route/navigate …]` now also pushes
  ;; the initial `/counter` URL.
  (rf/reg-frame :rf/default {:url-bound? false})
  (rf/reg-frame frame-id {:url-bound? true
                          :on-create  [:testdeck/initialise]})
  (rdc/render react-root [root]))
