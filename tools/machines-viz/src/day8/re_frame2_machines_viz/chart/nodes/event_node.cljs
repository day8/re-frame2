(ns day8.re-frame2-machines-viz.chart.nodes.event-node
  "Custom xyflow node for an EVENT — the central artefact of the
  events-as-nodes paradigm.

  ## Why this exists

  Following Stately's graph view, the chart paints each transition as
  its OWN box — `source-state → event-node → (optional) target-state` —
  rather than as an edge LABEL floating on the line. Painting
  transitions as line labels degrades legibility quickly with multiple
  candidates, long action names, or several stacked siblings, and makes
  action attribution a second-class citizen of the line text.

  The event box carries the event name (header), an optional `[guard]`
  chip, and `+ action` pills for action attribution. Internal
  transitions (no `:target`) get the event box but no outgoing edge —
  they read as 'this dispatches an action and hangs here'.

  Under this paradigm every spec transition emits exactly one xyflow
  node of `type \"rf2-event\"` plus one or two edges.

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
  (:require [clojure.string :as str]
            [reagent.core :as r]
            [day8.re-frame2-machines-viz.chart.nodes.xyflow-node
             :refer [four-cardinal-handles chart-constants palette-of]]
            [day8.re-frame2-machines-viz.theme.tokens
             :as tokens
             :refer [chart-label-stack]]))

(defn- variant-glyph
  "Convention-glyph variants for the event-node header. The three
  xstate/Stately conventions:

    - `:after`  → `⌚` clock glyph + `<ms>` (e.g. `⌚ 1000ms`)
    - `:always` → `∞` continuation glyph
    - `:on`     → the event keyword's name (with namespace when present)"
  [{:keys [variant after-ms event-label]}]
  (case variant
    :after  (str "⌚ " after-ms "ms")
    :always "∞"
    event-label))

(defn- fork-badge
  "The numbered PRIORITY badge for one branch of a guarded multi-branch
  fork. Stately renders a guarded fork (the gate machine's
  `:gate/check` 3-way) with a small circled number (①②③) on each branch
  chip, communicating the DETERMINISTIC ORDER the engine evaluates the
  guards in (first-pass-wins). The projector
  (`chart.projection/fork-order-by-edge-id`) derives the 1-based index
  from the source candidate vector's order and threads it onto the
  event-node `:data {:forkOrder}`; this renders it.

  A small rounded/circular chip leading the header line — neutral and
  unobtrusive (the chip border colour, quiet fill), so it reads as an
  order annotation, not a second event. Sized off the resolved density's
  `:event-chip-px` so the badge scales with the chip's typography across
  the compact / regular / cosy densities. Returns nil when `order` is
  nil (a non-fork event carries NO badge) so a single transition is
  unchanged.

  `id` is the host node id (for the `data-testid`); `ct` is the resolved
  theme token map; `event-chip-px` the density font-size."
  [id order ct event-chip-px]
  (when order
    (let [diam (+ event-chip-px 5)]
      [:span {:data-testid    (str "rf-mv-chart-event-fork-badge-" id)
              :data-fork-order (str order)
              :title          (str "guard priority " order
                                   " (evaluated in order, first pass wins)")
              :style {:display          "inline-flex"
                      :align-items      "center"
                      :justify-content  "center"
                      :flex             "0 0 auto"
                      :width            (str diam "px")
                      :height           (str diam "px")
                      :margin-right     "5px"
                      :border-radius    "50%"
                      :background       (:event-chip-border ct)
                      :color            (:text-primary ct)
                      :font-size        (str (max 8 (- event-chip-px 2)) "px")
                      :font-weight      700
                      :line-height      "1"}}
       order])))

(defn requires-chip
  "A compact `needs <id>, <id>` chip surfacing a guard / action
  consumer's declared `:rf.cofx/requires` (EP-0017 consumer
  attachment): the replay-critical host facts the named callback reads
  before it runs. The QUIETEST tier (tertiary colour, smallest type) — a
  subordinate annotation on the event chip, never competing with the event
  name. Carries a `data-*` pin (`data-guard-requires` / `data-action-requires`)
  so tests + hosts read the requirements off the DOM. Returns nil for an
  empty / nil requires vec so an undeclared callback paints nothing.

  `kind` is `:guard` / `:action` (drives the `data-testid` + pin attr);
  `reqs` the CLJS vec of compact id strings; `id` the host node id; `ct`
  the resolved theme tokens; `px` the density font-size."
  [kind reqs id ct px]
  (when (seq reqs)
    (let [label (str "needs " (str/join ", " reqs))
          pin   (case kind :guard :data-guard-requires
                           :action :data-action-requires)]
      [:span {:data-testid (str "rf-mv-chart-event-" (name kind) "-requires-" id)
              pin (str/join " " reqs)
              :title (str (name kind) " consumes recordable coeffects: " label)
              :style {:display       "inline-flex"
                      :align-items   "center"
                      :align-self    "flex-start"
                      :margin-top    "2px"
                      :font-size     (str (max 8 (- px 2)) "px")
                      :font-weight   400
                      :font-style    "italic"
                      :color         (:text-tertiary ct)
                      :line-height   "1"
                      :white-space   "nowrap"}}
       label])))

(defn event-node
  "Reagent component for an event-node. The xyflow chart projector
  emits ONE event-node per spec transition (event-as-node paradigm):
  state → event-node → (optional) target state.

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
    :guardBlocked  — this transition's guard REJECTED the event this
                     epoch (a guard-blocked no-op: the runtime emitted
                     `:rf.machine/guard-evaluated` fail/threw but NO
                     transition). PINK guard-blocked treatment: pink
                     border + an EMPHASISED pink `IF <guard>` chip so the
                     node reads as the guard that rejected it. Wins over
                     fired/focused on this node.
    :clickable     — host wired an on-click for this event (the
                     on-chart sim path).
    :eventId       — raw fireable event keyword the host receives on
                     click; nil for `:after` / `:always` (inert).
    :machineLevel  — top-level :on fallback transition every state
                     inherits.
    :internal      — internal self-transition (no :target). Visual
                     hint: dashed border ring under the header (the
                     'this action runs and we hang here' affordance).
    :reenter       — the EXTERNAL restart axis (`:reenter? true`, Spec
                     005 §Self-transitions / XState v5). A targeted
                     transition is internal by DEFAULT; only
                     `:reenter?` re-runs the target's `:exit`/`:entry` +
                     restarts its `:after` timers / `:spawn` children.
                     Visual hint: a `↻` reenter chip on the header so a
                     `:reenter? true` transition reads DISTINCTLY from its
                     internal default (which is otherwise topologically
                     identical).
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
        ;; The guard / action consumer's declared `:rf.cofx/requires`
        ;; (EP-0017 consumer attachment), a JS array of compact id strings
        ;; (nil when the named callback declares no facts). `js->clj` back
        ;; to a CLJS vec for rendering; nil stays nil so an undeclared
        ;; callback is visually unchanged.
        guard-reqs  (some-> (.-guardRequires d) js->clj)
        action-reqs (some-> (.-actionRequires d) js->clj)
        focused?    (boolean (.-focused d))
        fired?      (boolean (.-fired d))
        ;; This transition's guard rejected the event this epoch (guard-
        ;; blocked no-op). Drives the PINK guard-blocked treatment (pink
        ;; border + emphasised pink `IF <guard>` chip), winning over
        ;; fired/focused on this node so the rejected transition stands
        ;; out.
        blocked?    (boolean (.-guardBlocked d))
        fork-order  (.-forkOrder d)
        internal?   (boolean (.-internal d))
        reenter?    (boolean (.-reenter d))
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
                event-chip-action-px action-pill-height action-pill-pad-x
                action-pill-radius stroke-width stroke-width-emphasis]} vc
        emphasised? (or focused? fired? blocked?)
        stroke-w    (if emphasised? stroke-width-emphasis stroke-width)
        ;; The route chip is SUBORDINATE to states: a quiet neutral fill +
        ;; neutral border by default. Runtime state (focused lens / fired-
        ;; this-epoch) swaps the border to the runtime accent; a clickable
        ;; (sim) chip gets a faint amber wash. A guard-blocked no-op swaps
        ;; the border to the PINK guard-blocked hue (winning over
        ;; fired/focused) so the rejected transition stands out.
        stroke-col  (cond
                      blocked?  (:edge-guard-blocked ct)
                      fired?    (:edge-fired ct)
                      focused?  (:edge-active ct)
                      :else     (:event-chip-border ct))
        fill        (cond
                      fired?    (:glow-fired ct)
                      focused?  (:glow ct)
                      clickable? (:sim-wash ct)
                      :else      (:event-chip-bg ct))]
    (r/as-element
      ;; Route chip / card grammar. NO title bar: event nodes must NOT
      ;; read as peer state boxes. The event label + guard ride the FIRST
      ;; line; the action row appears only when an action exists; an
      ;; internal / action-only transition (no outgoing target segment —
      ;; the projector omits the `__out` edge) keeps a dashed border ring
      ;; so it reads as "runs an action and hangs here". Machine-level is
      ;; muted: NO loud label, only the `data-machine-level` attr for host
      ;; introspection.
      [:div {:data-testid (str "rf-mv-chart-event-" (.-id props))
             :data-node-id (.-id props)
             :data-event-id (when event-id (str event-id))
             :data-variant (name variant)
             :data-variant-after (str (= :after variant))
             :data-variant-always (str (= :always variant))
             :data-internal (str internal?)
             :data-reenter (str reenter?)
             :data-fork-order (when fork-order (str fork-order))
             :data-machine-level (str machine-level?)
             :data-fired (str fired?)
             ;; DOM pin for the guard-blocked no-op event treatment; tests
             ;; + hosts read it to find the attempted-and-rejected
             ;; transition (the pink chip whose guard declined).
             :data-guard-blocked (str blocked?)
             :data-focused (str focused?)
             :data-clickable (str clickable?)
             :data-after-ms (when after-ms (str after-ms))
             :data-guard (when guard (str guard))
             :data-action (when action (str action))
             ;; DOM pins for the guard / action consumer's declared
             ;; `:rf.cofx/requires` (space-joined id strings) so tests +
             ;; hosts read the replay-critical facts off the node.
             :data-guard-requires (when (seq guard-reqs) (str/join " " guard-reqs))
             :data-action-requires (when (seq action-reqs) (str/join " " action-reqs))
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
                                            (cond
                                              blocked? (:edge-guard-blocked ct)
                                              fired?   (:glow-fired ct)
                                              :else    (:glow ct))))
                     ;; Event-driven + FINITE glow: ONE motion-scaled
                     ;; iteration that settles to a stable end-state (no
                     ;; `infinite` strobe) and collapses to a settle frame
                     ;; under `prefers-reduced-motion`. The static fired
                     ;; affordance (the `:glow-fired` box-shadow above)
                     ;; persists after it completes.
                     :animation      (when fired?
                                       (tokens/glow-animation-css))
                     :transition     "border-color 120ms ease, background 120ms ease"}}
       ;; First line: optional fork priority-badge + event name / ⌚ <ms> /
       ;; ∞ + optional `IF <guard>`. The numbered badge LEADS the line
       ;; (Stately convention) so a guarded-fork branch reads as "branch
       ;; N: event IF guard"; nil for non-fork events.
       [:span {:data-testid (str "rf-mv-chart-event-header-" (.-id props))
               :data-event-line (cond-> header
                                  guard (str " IF " guard))
               :style {:display "inline-flex"
                       :align-items "center"
                       :white-space "nowrap"
                       :line-height "1.1"
                       :color (:text-primary ct)}}
        (fork-badge (.-id props) fork-order ct event-chip-px)
        header
        (when guard
          ;; A guard-blocked no-op EMPHASISES the guard chip in the pink
          ;; guard-blocked hue (bold pink `IF <guard>`) so the node reads
          ;; "this guard rejected it"; the resting guard chip stays quiet
          ;; tertiary.
          [:span {:data-testid (str "rf-mv-chart-event-guard-" (.-id props))
                  :data-guard guard
                  :data-guard-blocked (str blocked?)
                  :style {:margin-left "5px"
                          :color (if blocked?
                                   (:edge-guard-blocked ct)
                                   (:text-tertiary ct))
                          :font-weight (if blocked? 700 600)}}
           (str "IF " guard)])
        ;; The EXTERNAL restart marker. A targeted transition is internal
        ;; by default (XState v5 / Spec 005); a `↻` reenter chip makes a
        ;; `:reenter? true` transition read DISTINCTLY from its internal
        ;; default (re-runs `:exit`/`:entry`, restarts `:after` /
        ;; `:spawn`), which is otherwise topologically identical.
        (when reenter?
          [:span {:data-testid (str "rf-mv-chart-event-reenter-" (.-id props))
                  :data-reenter "true"
                  :title "reenter (external): re-runs exit/entry, restarts timers + spawned children"
                  :style {:margin-left "5px"
                          :color (:text-tertiary ct)
                          :font-weight 600}}
           "↻"])]
       ;; Action row — appears ONLY when an action exists. Render the bolt
       ;; + action name as a subdued ENCLOSED action CHIP (subtle fill +
       ;; border + padding + rounding), matching the state-node entry/exit
       ;; action chip (`nodes/action-row`) and Stately's quiet action
       ;; treatment, so it reads as a contained annotation subordinate to
       ;; the event line — NOT loose free-floating text inside the trigger
       ;; box. Geometry reads off the density's
       ;; `:action-pill-*` constants (shared with the state-node chip);
       ;; font-size keeps the density-aware `:event-chip-action-px`. Colour
       ;; is the neutral container-header fill + structural border + tertiary
       ;; text (the quietest tier — subordinate to the event name's primary).
       (when action
         [:span {:data-testid (str "rf-mv-chart-event-action-" (.-id props))
                 :data-action action
                 :style {:display       "inline-flex"
                         :align-items   "center"
                         :gap           "3px"
                         :align-self    "flex-start"
                         :height        (str action-pill-height "px")
                         :padding       (str "0 " action-pill-pad-x "px")
                         :background    (:container-header-bg ct)
                         :border        (str "1px solid " (:state-border ct))
                         :border-radius (str action-pill-radius "px")
                         :font-size     (str event-chip-action-px "px")
                         :font-weight   500
                         :color         (:text-tertiary ct)
                         :line-height   "1"
                         :white-space   "nowrap"}}
          [:span {:style {:opacity 0.7}} "⚡"]
          action])
       ;; The guard / action consumer-attachment requirement rows: a quiet
       ;; `needs <id>` annotation surfacing the replay-critical host facts
       ;; the named guard / action declares via `:rf.cofx/requires`
       ;; (EP-0017). Each renders ONLY when its callback declared a non-
       ;; empty diet, so an ordinary fact-free transition is visually
       ;; unchanged.
       (requires-chip :guard guard-reqs (.-id props) ct event-chip-action-px)
       (requires-chip :action action-reqs (.-id props) ct event-chip-action-px)
       ;; xyflow attachment points. Handles on every side so elkjs can
       ;; pick the cleanest anchor based on the routed direction.
       (four-cardinal-handles)])))
