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
             :refer [four-cardinal-handles chart-constants palette-of]]
            [day8.re-frame2-machines-viz.theme.tokens
             :refer [chart-label-stack]]))

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
        ct          (palette-of d)
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
        {:keys [event-chip-min-w event-chip-min-h event-chip-pad-x
                event-chip-pad-y event-chip-radius event-chip-px
                event-chip-action-px stroke-width stroke-width-emphasis]} vc
        emphasised? (or focused? fired?)
        stroke-w    (if emphasised? stroke-width-emphasis stroke-width)
        ;; rf2-az6e2 — the route chip is SUBORDINATE to states: a quiet
        ;; neutral fill + neutral border by default. Runtime state
        ;; (focused lens / fired-this-epoch) swaps the border to the
        ;; runtime accent; a clickable (sim) chip gets a faint amber wash.
        stroke-col  (cond
                      fired?    (:edge-fired ct)
                      focused?  (:edge-active ct)
                      :else     (:event-chip-border ct))
        fill        (cond
                      fired?    (:glow-fired ct)
                      focused?  (:glow ct)
                      clickable? (:sim-wash ct)
                      :else      (:event-chip-bg ct))]
    (r/as-element
      ;; rf2-az6e2 — route chip / card grammar. NO title bar (the bead is
      ;; explicit: event nodes must NOT read as peer state boxes). The
      ;; event label + guard ride the FIRST line; the action row appears
      ;; only when an action exists; an internal / action-only transition
      ;; (no outgoing target segment — the projector omits the `__out`
      ;; edge) keeps a dashed border ring so it reads as "runs an action
      ;; and hangs here". Machine-level is muted: NO loud label, only the
      ;; `data-machine-level` attr for host introspection.
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
                     :align-items    "flex-start"
                     :justify-content "center"
                     :gap            (str event-chip-pad-y "px")
                     :min-width      (str event-chip-min-w "px")
                     :min-height     (str event-chip-min-h "px")
                     :padding        (str event-chip-pad-y "px "
                                          event-chip-pad-x "px")
                     :background     fill
                     :border         (str stroke-w "px "
                                          (if internal? "dashed" "solid")
                                          " " stroke-col)
                     :border-radius  (str event-chip-radius "px")
                     :font-family    chart-label-stack
                     :font-size      (str event-chip-px "px")
                     :font-weight    (if emphasised? 600 500)
                     :color          (:text-secondary ct)
                     :cursor         (if clickable? "pointer" "default")
                     :user-select    "none"
                     :box-shadow     (when emphasised?
                                       (str "0 0 0 2px "
                                            (if fired? (:glow-fired ct) (:glow ct))))
                     :animation      (when fired?
                                       "mv-chart-transition-glow 720ms ease-out infinite")
                     :transition     "border-color 120ms ease, background 120ms ease"}}
       ;; First line: event name / ⌚ <ms> / ∞ + optional `IF <guard>`.
       [:span {:data-testid (str "rf-mv-chart-event-header-" (.-id props))
               :data-event-line (cond-> header
                                  guard (str " IF " guard))
               :style {:white-space "nowrap"
                       :line-height "1.1"
                       :color (:text-primary ct)}}
        header
        (when guard
          [:span {:data-testid (str "rf-mv-chart-event-guard-" (.-id props))
                  :data-guard guard
                  :style {:margin-left "5px"
                          :color (:text-tertiary ct)
                          :font-weight 600}}
           (str "IF " guard)])]
       ;; Action row — appears ONLY when an action exists. Subdued bolt
       ;; chip, subordinate to the event line.
       (when action
         [:span {:data-testid (str "rf-mv-chart-event-action-" (.-id props))
                 :data-action action
                 :style {:display      "inline-flex"
                         :align-items  "center"
                         :gap          "3px"
                         :font-size    (str event-chip-action-px "px")
                         :font-weight  500
                         :color        (:text-tertiary ct)
                         :line-height  "1"
                         :white-space  "nowrap"}}
          [:span {:style {:opacity 0.7}} "⚡"]
          action])
       ;; xyflow attachment points. Handles on every side so elkjs can
       ;; pick the cleanest anchor based on the routed direction.
       (four-cardinal-handles)])))
