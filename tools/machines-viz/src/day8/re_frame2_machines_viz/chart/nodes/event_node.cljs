(ns day8.re-frame2-machines-viz.chart.nodes.event-node
  "Custom xyflow node for an EVENT — the central artefact of the
  events-as-nodes paradigm (rf2-qo5xy).

  ## Why this exists

  Pre-rf2-qo5xy the chart painted transitions as edge LABELS between
  state boxes: `event [guard] / action` floated on the line. Multiple
  candidates, long action names, or several stacked siblings degraded
  legibility quickly, and action attribution was always a second-class
  citizen of the line text.

  Stately's graph view paints each transition as its OWN box —
  `source-state → event-node → (optional) target-state`. The event
  box carries the event name (header), an optional `[guard]` chip,
  and `+ action` pills for action attribution. Internal transitions
  (no `:target`) get the event box but no outgoing edge — they read
  as 'this dispatches an action and hangs here'.

  rf2-qo5xy adopts that paradigm: every spec transition emits exactly
  one xyflow node of `type \"rf2-event\"` plus one or two edges.

  ## What this owns

  Just the event-box chrome:

    - Header line with the event name (or `⌚ <ms>` for `:after`,
      or `∞` for `:always`).
    - Optional `[guard]` chip when the transition declares one.
    - `+ <action>` pill row when the transition declares one or more
      actions.
    - Source + target xyflow `Handle`s on the cardinal sides so the
      incoming (source-state → event) and outgoing (event → target-
      state) edges anchor cleanly.

  Geometry + typography read off the resolved density's
  `visual-constants` map threaded through `:data {:chart ...}` —
  same pattern the state-node + edge components use. No hex literals
  appear in this ns; all colour goes through `theme/tokens`."
  (:require [reagent.core :as r]
            [day8.re-frame2-machines-viz.chart.nodes.xyflow-node
             :refer [Handle pos-top pos-right pos-bottom pos-left
                     chart-constants]]
            [day8.re-frame2-machines-viz.theme.tokens
             :as tokens
             :refer [mono-stack sans-stack]]))

(defn- variant-glyph
  "rf2-qo5xy — convention-glyph variants for the event-node header. The
  three xstate/Stately conventions:

    - `:after`  → `⌚` clock glyph + `<ms>` (e.g. `⌚ 1000ms`)
    - `:always` → `∞` continuation glyph
    - `:on`     → the event keyword's name (with namespace when present)"
  [{:keys [variant after-ms event-label]}]
  (case variant
    :after  (str "⌚ " after-ms "ms")
    :always "∞"
    event-label))

(defn event-node
  "Reagent component for an event-node. The xyflow chart projector
  emits ONE event-node per spec transition (event-as-node paradigm,
  rf2-qo5xy): state → event-node → (optional) target state.

  Reads from `:data`:

    :eventLabel    — visible header text (already the event-segment
                     string from `chart.layout/event-segment` —
                     namespace/name handled there).
    :variant       — `:on` / `:after` / `:always`. Drives the header
                     glyph.
    :afterMs       — `:after`-delay in ms (`:after` variant only).
    :guard         — guard name as a string, or nil.
    :action        — action name as a string, or nil.
    :focused       — focused-event lens hit; emphasised border.
    :fired         — this event fired THIS epoch; FIRED treatment.
    :clickable     — host wired an on-click for this event (the
                     on-chart sim path).
    :eventId       — raw fireable event keyword the host receives on
                     click; nil for `:after` / `:always` (inert).
    :machineLevel  — top-level :on fallback transition every state
                     inherits.
    :internal      — internal self-transition (no :target). Visual
                     hint: dashed border ring under the header (the
                     'this action runs and we hang here' affordance).
    :chart         — resolved visual-constants for the active density."
  [^js props]
  (let [d           (.-data props)
        vc          (chart-constants d)
        event-label (or (.-eventLabel d) "")
        variant     (keyword (or (.-variant d) "on"))
        after-ms    (.-afterMs d)
        guard       (.-guard d)
        action      (.-action d)
        focused?    (boolean (.-focused d))
        fired?      (boolean (.-fired d))
        internal?   (boolean (.-internal d))
        machine-level? (boolean (.-machineLevel d))
        on-click    (.-onClick d)
        event-id    (.-eventId d)
        from-path   (.-fromPath d)
        to-path     (.-toPath d)
        clickable?  (and (fn? on-click) (some? event-id))
        header      (variant-glyph {:variant     variant
                                    :after-ms    after-ms
                                    :event-label event-label})
        {:keys [corner-radius stroke-width stroke-width-emphasis
                edge-label-px action-pill-height action-pill-pad-x
                action-pill-px action-pill-radius action-pill-row-gap]} vc
        emphasised? (or focused? fired?)
        stroke-w    (if emphasised? stroke-width-emphasis stroke-width)
        stroke-col  (cond
                      fired?    (:accent tokens/tokens)
                      focused?  (:info tokens/tokens)
                      :else     (tokens/with-alpha :border-default 0.7))
        fill        (cond
                      fired?    (tokens/with-alpha :accent 0.12)
                      focused?  (tokens/with-alpha :info 0.10)
                      clickable? (tokens/with-alpha :yellow 0.06)
                      :else      (tokens/with-alpha :bg-3 0.6))]
    (r/as-element
      [:div {:data-testid (str "rf-mv-chart-event-" (.-id props))
             :data-node-id (.-id props)
             :data-event-id (when event-id (str event-id))
             :data-variant (name variant)
             :data-variant-after (str (= :after variant))
             :data-variant-always (str (= :always variant))
             :data-internal (str internal?)
             :data-machine-level (str machine-level?)
             :data-fired (str fired?)
             :data-focused (str focused?)
             :data-clickable (str clickable?)
             :data-after-ms (when after-ms (str after-ms))
             :data-guard (when guard (str guard))
             :data-action (when action (str action))
             :data-from-path (when from-path (pr-str from-path))
             :data-to-path (when to-path (pr-str to-path))
             :role (when clickable? "button")
             :title (when clickable? (str "Send " event-id))
             :on-click (when clickable?
                         (fn [ev]
                           (.stopPropagation ev)
                           (on-click
                             #js {:eventId  event-id
                                  :fromPath from-path
                                  :toPath   to-path})))
             :style {:position       "relative"
                     :display        "inline-flex"
                     :flex-direction "column"
                     :align-items    "center"
                     :justify-content "center"
                     :gap            "3px"
                     :min-width      "84px"
                     :min-height     "30px"
                     :padding        "4px 10px"
                     :background     fill
                     :border         (str stroke-w "px "
                                          (if internal? "dashed" "solid")
                                          " " stroke-col)
                     :border-radius  (str (max 4 (- corner-radius 2)) "px")
                     :font-family    sans-stack
                     :font-size      (str edge-label-px "px")
                     :font-weight    (if emphasised? 600 500)
                     :color          (:text-primary tokens/tokens)
                     :cursor         (if clickable? "pointer" "default")
                     :user-select    "none"
                     :box-shadow     (when emphasised?
                                       (str "0 0 0 2px "
                                            (tokens/with-alpha
                                              (if fired? :accent :info) 0.15)))
                     :animation      (when fired?
                                       "mv-chart-transition-glow 720ms ease-out infinite")
                     :transition     "border-color 120ms ease, background 120ms ease"}}
       ;; Header line: event name / ⌚ <ms> / ∞ + optional [guard] chip.
       [:span {:data-testid (str "rf-mv-chart-event-header-" (.-id props))
               :data-event-line (cond-> header
                                  guard (str " [" guard "]"))
               :style {:font-family mono-stack
                       :white-space "nowrap"
                       :line-height "1.1"}}
        header
        (when guard
          [:span {:data-testid (str "rf-mv-chart-event-guard-" (.-id props))
                  :data-guard guard
                  :style {:margin-left "4px"
                          :color (:advisory tokens/tokens)
                          :font-family mono-stack}}
           (str "[" guard "]")])]
       ;; Action pill (`+ <action-name>`) — Stately graph view convention.
       (when action
         [:span {:data-testid (str "rf-mv-chart-event-action-" (.-id props))
                 :data-action action
                 :style {:display          "inline-flex"
                         :align-items      "center"
                         :height           (str action-pill-height "px")
                         :padding          (str "0 " action-pill-pad-x "px")
                         :margin-top       (str action-pill-row-gap "px")
                         :background       (tokens/with-alpha :advisory 0.18)
                         :border           (str "1px solid "
                                                (tokens/with-alpha :advisory 0.6))
                         :border-radius    (str action-pill-radius "px")
                         :font-family      mono-stack
                         :font-size        (str action-pill-px "px")
                         :font-weight      500
                         :color            (:advisory tokens/tokens)
                         :line-height      "1"
                         :white-space      "nowrap"}}
          (str "+ " action)])
       ;; Machine-level annotation — a small subscript chip so the
       ;; operator can see at a glance that this event-handler is
       ;; inherited from the machine's top-level :on (not declared on
       ;; the source state).
       (when machine-level?
         [:span {:data-testid (str "rf-mv-chart-event-machine-level-" (.-id props))
                 :style {:font-family sans-stack
                         :font-size   (str (max 8 (- action-pill-px 1)) "px")
                         :font-weight 600
                         :color       (tokens/with-alpha :text-tertiary 0.85)
                         :letter-spacing "0.04em"
                         :text-transform "uppercase"}}
          "machine-level"])
       ;; xyflow attachment points. Handles on every side so elkjs can
       ;; pick the cleanest anchor based on the routed direction.
       [:> Handle {:type "target" :position pos-top    :style {:opacity 0}}]
       [:> Handle {:type "target" :position pos-left   :id "left"
                   :style {:opacity 0}}]
       [:> Handle {:type "source" :position pos-bottom :style {:opacity 0}}]
       [:> Handle {:type "source" :position pos-right  :id "right"
                   :style {:opacity 0}}]])))
