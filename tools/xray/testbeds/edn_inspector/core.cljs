(ns edn-inspector.core
  "EDN-INSPECTOR testbed (rf2-74u2s) — a standard-epochs-style driving
  surface for the Xray edn-inspector WIDGET, extracted from the
  edn-inspector test(s) that were mixed into `standard_epochs`.

  ## Shape

  ONE tall column of NUMBERED buttons down the left, top to bottom.
  Beside each button a one-line caption says WHAT the case exercises.
  Pressing a button seeds that case into the single frame's app-db;
  the edn-inspector widget mounted BELOW the column renders it live.
  Each button is ONE rung of a progressive feature ladder — rung 1 is
  the trivial resting render; each later rung layers one more
  edn-inspector capability.

  This is the WIDGET counterpart of `standard_epochs` (which drives the
  Xray PANELS). Where standard-epochs presses buttons to make Xray's
  Epoch / App-db / Views / Trace / Issues panels light up, this deck
  presses buttons to drive the single CLJS-value renderer
  (`day8.re-frame2-xray.views.edn-inspector`) DIRECTLY — the renderer
  behind every one of those panels.

  ## North star (acceptance)

  Click the buttons top to bottom: every edn-inspector capability — a
  resting render, elision past threshold, deep nesting + collapse /
  expand, the three diff ops (added / removed incl. remove-to-empty /
  changed), redaction + large-elided sentinels, zoom, the popup
  affordance, tagged literals, and the card / header chrome — is
  exercised in turn, each in isolation.

  ## Direct-widget mount (no Xray shell)

  Unlike standard-epochs (which auto-mounts the full Xray shell inline
  via the `day8.re-frame2-xray.preload`), this deck mounts the
  edn-inspector widget DIRECTLY — the bead's `press-button → the
  edn-inspector below renders that case` shape. So we mirror the
  `panel_gallery` boot for a bare widget mount: register Xray's
  handlers (the widget reads its own expansion / zoom slots through
  them) and install the Xray theme CSS variables on `:root` so the
  widget paints with the themed palette — but we do NOT mount the
  shell. The widget resolves dispatch / subscribe against the single
  default frame (it is `reg-view`-registered, so it inherits whatever
  frame is in scope; here that is the plainly-mounted default frame).

  ## Fixture reuse

  Per rf2-74u2s the value shapes are REUSED from the `panel_gallery`
  edn-inspector + diff-mode-3 fixtures (the pure builder fns under
  `panel-gallery.fixtures-edn-inspector` /
  `panel-gallery.fixtures-diff-mode-3`) rather than re-authored. The
  ladder drives them LIVE: a button seeds the chosen fixture's
  `{:value :before :opts}` into app-db; the widget renders it with diff
  mode engaged when a `:before` is present.

  ## Test surface, not tutorial

  Per `feedback_testbeds_are_test_surfaces`: NO deliberate bugs, NO
  teaching layers, NO anti-pattern demos. Each rung exercises a REAL
  edn-inspector capability cleanly. Captions are guidance, not lessons.

  ## Test-free + self-contained

  Per rf2-8cevm this testbed carries no spec.cjs; the edn-inspector's
  regression coverage lives in the `panel_gallery` Story gallery
  (`story.xray.edn-inspector` + `story.xray.diff-mode-3`) gated by the
  Xray feature-matrix gate. This deck is the manual LIVE-driver for the
  same widget — the human-eye companion to the automated gate."
  (:require [reagent.dom.client :as rdc]
            [re-frame.core :as rf]
            [re-frame.views]
            [re-frame.adapter.reagent :as reagent-adapter]
            ;; The widget under test — the single CLJS-value renderer.
            [day8.re-frame2-xray.views.edn-inspector :as edn-inspector]
            ;; Xray's `:rf.xray/*` handlers — the widget reads its own
            ;; expansion / zoom slots through them.
            [day8.re-frame2-xray.registry :as xray-registry]
            ;; Xray's `:root` CSS-variable installer — required for a bare
            ;; widget mount (the shell normally installs these from its
            ;; own `shell-view` body; we bypass the shell, so we call it).
            [day8.re-frame2-xray.theme.global-styles :as global-styles]
            [day8.re-frame2-xray.config :as xray-config]
            ;; REUSED fixtures (rf2-74u2s) — pure builder fns; no
            ;; re-authoring of the value shapes.
            [panel-gallery.fixtures-edn-inspector :as fx]
            [panel-gallery.fixtures-diff-mode-3 :as fx3]
            ;; Shared testbed-config helper (rf2-5dphw): derives the
            ;; open-in-editor project-root from the build env.
            [re-frame.testbed.config :as testbed-config])
  (:require-macros [re-frame.core :refer [reg-view]]))

;; ============================================================================
;; APP-DB SEED
;; ============================================================================
;;
;; The deck holds exactly one slot: `:fixture`, the active fixture map
;; `{:value :before :opts}`. A button seeds it; the widget reads it. No
;; baseline counter (this deck drives the WIDGET, not the panels — there
;; is no Epoch / App-db delta to make visible).

(def fixture-slot :edn-inspector/fixture)

(def initial-db
  {fixture-slot nil})

(rf/reg-event-db :edn-inspector/reset
  {:doc "Button 0 — clear the active fixture; the widget shows its empty
         resting state."}
  (fn handler-reset [_db _ev]
    initial-db))

(rf/reg-event-db :edn-inspector/seed!
  {:doc "Seed the active fixture map `{:value :before :opts}`. Every
         ladder button dispatches this with one fixture builder's
         result; the widget below renders it (diff mode engages when a
         `:before` is present)."}
  (fn handler-seed! [db [_ fixture]]
    (assoc db fixture-slot fixture)))

(rf/reg-sub fixture-slot
  (fn [db _] (get db fixture-slot)))

;; ============================================================================
;; THE LADDER — 13 rungs (rf2-74u2s)
;; ============================================================================
;;
;; Each row: [n label caption fixture-fn]. `fixture-fn` is a 0-arg fn
;; returning `{:value :before :opts}` (the REUSED panel_gallery
;; fixtures, occasionally composed with extra opts for a rung). `:section`
;; rows are separators (label only).
;;
;; The progression mirrors the bead's enumerated ladder exactly:
;;
;;   1  resting render (tiny scalar / small map)
;;   2  elision past threshold (the extracted standard-epochs button-9 case)
;;   3  deep nesting → path rendering + collapse / expand
;;   4  collapse / expand toggle (a wide map; click triangles)
;;   5  diff: ADDED (green +)
;;   6  diff: REMOVED incl. remove-only-key → empty (the rf2-8pfkk case)
;;   7  diff: CHANGED in place (diff-mode-3 grammar)
;;   8  sensitive paths → :rf/redacted markers
;;   9  large-elided sentinels → :rf.size/large-elided
;;   10 zoom (re-root into a subtree) + back
;;   11 popup affordance (the ↗ open-in-new-pane)
;;   12 tagged literals / mixed types (nil / boolean / keyword / inst / uuid)
;;   13 card / header section chrome

;; Rung 6's remove-to-empty case (rf2-8pfkk): dissoc the only key so the
;; after-tree is `{}`. A struck-through removal — NOT a `:added ::missing`
;; leak (the bug rf2-8pfkk fixed).
(defn- remove-to-empty []
  {:before {:only-key {:label "removed" :n 42}}
   :value  {}
   :opts   {:full-with-diff? true}})

(def ^:private ladder
  [[:section "Resting / elision / nesting"]
   [1  "Tiny scalar + small map"
    "Resting render — a 3-key map renders inline; the syntax palette is visible"
    fx/map-small]
   [2  "Large collection → elision"
    "Elision past threshold — a 50-key map; the expanded body scrolls (the extracted standard-epochs button-9 case)"
    fx/map-large]
   [3  "Deeply nested → path + collapse"
    "Six-level nested map — path rendering; deep nodes render `▸ {…N keys}` summaries"
    fx/map-nested]
   [4  "Collapse / expand toggle"
    "A twelve-key map past `:max-inline-width` — click the triangles to collapse / expand"
    fx/map-medium]

   [:section "Diff ops — added / removed / changed"]
   [5  "Diff: ADDED (green +)"
    "Wholly-new `:flash` subtree — the `+` glyph + green wash (diff-mode-3 R5)"
    fx3/r5-wholly-new-subtree]
   [6  "Diff: REMOVED → empty"
    "Remove the ONLY key → after-tree is `{}` — a struck-through `−` removal, NOT a `:added ::missing` leak (rf2-8pfkk)"
    remove-to-empty]
   [7  "Diff: CHANGED in place"
    "Scalar bump `{:counter 5} → {:counter 6}` — the value-side `~` glyph + `← was 5` (diff-mode-3 R1)"
    fx3/r1-modified-scalar]

   [:section "Sentinels — redaction / size-elision"]
   [8  "Sensitive → :rf/redacted"
    "A `:rf/redacted` sentinel three levels deep renders as a `redacted` chip, never the raw value"
    fx/diff-redacted-context]
   [9  "Large-elided sentinel"
    "A `:rf.size/large-elided` sentinel (Spec 015) routes through the size-chip renderer, not the regular map path"
    fx/map-large-elided]

   [:section "Affordances — zoom / popup"]
   [10 "Zoomable (re-root into a subtree)"
    "`:zoomable? true` — double-click a container (or Enter while focused) to re-root the inspector onto it; breadcrumb back"
    fx/opts-zoomable]
   [11 "Popup affordance (↗)"
    "`:popup-affordance? true` — the `↗` open-in-new-pane button sits at the widget's top-right corner"
    fx/opts-popup-affordance]

   [:section "Tagged literals / chrome"]
   [12 "Tagged literals / mixed types"
    "Every scalar kind — nil / boolean / int / float / string / keyword / symbol — plus uuid + inst tagged literals"
    fx/scalar-mix]
   [13 "Card + header chrome"
    "`:card?` + `:header` — the inspector-card surface chrome with a labelled ribbon (rf2-63ie5 / rf2-okq7p)"
    fx/opts-header]])

;; ============================================================================
;; VIEWS — the button ladder + the live widget below
;; ============================================================================

(reg-view ladder-button
  "One numbered ladder row: a numbered button on the left, its caption on
  the right. Pressing it seeds the row's fixture into the widget."
  [n label caption fixture-fn]
  [:div {:style {:display "grid" :grid-template-columns "auto 1fr"
                 :gap "0.75em" :align-items "center" :margin "0.35em 0"}}
   [:button {:data-testid (str "edn-inspector-button-" n)
             :on-click    #(dispatch [:edn-inspector/seed! (fixture-fn)])
             :style {:min-width "18em" :text-align "left"
                     :padding "0.4em 0.6em" :cursor "pointer"
                     :border "1px solid #cfc8ff" :border-radius "6px"
                     :background "#fff"}}
    [:span {:style {:font-weight "bold" :color "#7C5CFF" :margin-right "0.5em"}}
     (str n ".")]
    label]
   [:span {:style {:color "#666" :font-size "12px"}} caption]])

(reg-view section-heading
  "A section separator inside the ladder."
  [label]
  [:div {:style {:margin "1em 0 0.25em 0" :font-size "11px" :font-weight "bold"
                 :color "#7C5CFF" :text-transform "uppercase"
                 :letter-spacing "0.04em" :border-top "1px dashed #ddd"
                 :padding-top "0.5em"}}
   label])

(reg-view inspector-stage
  "The live widget below the ladder. Reads the active fixture slot,
  destructures `{:value :before :opts}`, and renders the edn-inspector
  with diff mode engaged when `:before` is present. Empty until a button
  is pressed."
  []
  (let [fixture @(subscribe [:edn-inspector/fixture])
        {:keys [value before opts]} fixture
        merged-opts (cond-> (or opts {})
                      (some? before) (assoc :before before))]
    [:div {:data-testid "edn-inspector-stage"
           :style {:margin-top "1em" :border-top "2px solid #7C5CFF"
                   :padding-top "1em"}}
     [:div {:style {:font-size "11px" :font-weight "bold" :color "#7C5CFF"
                    :text-transform "uppercase" :letter-spacing "0.04em"
                    :margin-bottom "0.5em"}}
      "edn-inspector — live"]
     (if (some? fixture)
       [edn-inspector/edn-inspector value merged-opts]
       [:div {:style {:color "#888" :font-size "13px" :font-style "italic"}}
        "Press a numbered button above — the chosen case renders here."])]))

(reg-view root []
  [:div {:data-testid "edn-inspector-root"
         :style {:font-family "system-ui, sans-serif" :padding "1em"
                 :max-width "760px"}}
   [:header {:style {:margin-bottom "0.5em"}}
    [:h2 {:style {:margin 0}} "edn-inspector"]
    [:p {:style {:color "#444" :margin "0.5em 0 0 0"}}
     "One frame, one tall column of test buttons. Each button seeds one "
     "case into the "
     [:strong "edn-inspector"]
     " widget below — the single CLJS-value renderer behind every Xray "
     "panel. Click top to bottom and every renderer capability is "
     "exercised: resting render, elision, nesting + collapse / expand, "
     "the three diff ops, redaction + size sentinels, zoom, the popup "
     "affordance, tagged literals, and card / header chrome."]]
   ;; Button 0 — Reset.
   [:button {:data-testid "edn-inspector-reset"
             :on-click #(dispatch [:edn-inspector/reset])
             :style {:padding "0.4em 0.8em" :cursor "pointer"
                     :border "1px solid #cfc8ff" :border-radius "6px"
                     :background "#f4f1ff" :margin "0.5em 0"}}
    "0. Reset — clear the active fixture"]
   ;; The ladder.
   (for [row ladder]
     (if (= :section (first row))
       ^{:key (second row)} [section-heading (second row)]
       (let [[n label caption fixture-fn] row]
         ^{:key n} [ladder-button n label caption fixture-fn])))
   ;; The live widget.
   [inspector-stage]])

;; ============================================================================
;; MOUNT
;; ============================================================================

(defonce react-root
  (rdc/create-root (js/document.getElementById "app")))

;; rf2-5dphw — open-in-editor project-root is derived from the build
;; environment, not a hardcoded personal path (mirrors standard_epochs).
(defn- resolve-project-root []
  (testbed-config/resolve-project-root "tools/xray/testbeds"))

(defn ^:export run []
  ;; Configure Xray BEFORE `rf/init!` so any source-coord chip the widget
  ;; surfaces resolves its classpath-relative `:file` to an on-disk URI.
  (xray-config/configure! {:rf.xray/project-root (resolve-project-root)})
  (rf/init! reagent-adapter/adapter)
  ;; Register Xray's `:rf.xray/*` handlers once — the widget reads its
  ;; own expansion / zoom slots through them. We do NOT mount the Xray
  ;; shell (this deck drives the widget directly).
  (xray-registry/register-xray-handlers!)
  ;; Install the Xray theme CSS variables on `:root` so the bare widget
  ;; paints with the themed palette (the shell normally does this; we
  ;; bypass the shell). Idempotent (defonce-guarded + DOM-probed).
  (global-styles/install!)
  ;; Single, plain frame — no URL machinery, no shell.
  (rf/dispatch-sync [:edn-inspector/reset])
  (rdc/render react-root [root]))
