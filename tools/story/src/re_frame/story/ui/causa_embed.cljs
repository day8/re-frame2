(ns re-frame.story.ui.causa-embed
  "Causa-in-Story per-panel embed surface (rf2-v1ach).

  Replaces the pre-rf2-v1ach `[data-rf-causa-host]` whole-shell mount
  (the 320px column hosting Causa's 4-layer chrome). Per the audit
  in `ai/findings/2026-05-19-story-design-causa-integration.md` §Part
  B the per-panel embed is shape #3: Story's RHS hosts ONE Causa
  panel at a time, configurable per story via the `:causa-panel` slot
  (default `:event-detail`), with a chip-row picker letting the user
  swap (`Event` / `App-db` / `Views` / `Trace` / `Machines` /
  `Routing` / `Issues`) at runtime.

  ## Why per-panel beats whole-shell

  - **Width affordance.** Causa's 4-layer chrome (ribbon + L2 event
    list + tab-bar + detail panel) needs ~720px to render usefully;
    Story's RHS is 320px. Cramming the whole shell into the column
    means horizontally-clipped chrome on every render. One panel
    fits cleanly in the available column.
  - **Focus alignment.** Story is a workshop; the user is hovering
    over one component asking one diagnostic question. The full
    4-layer chrome is overkill — one panel = one diagnostic lens
    matches the cognitive load.
  - **Author intent.** `:causa-panel` lets the story author pick the
    most-likely-useful lens (`:app-db` for state-shape stories,
    `:routing` for routing demos, `:trace` for debugging side
    effects). Users can swap if they need a different lens.

  ## Public surface

  - `(causa-embed-panel)` — the RHS Causa-host component. Renders
    the chip-row picker + the mounted panel slot + the popout chip.
  - `(mount-fn-for panel-id)` — pure data → data: the Causa
    `mount-<panel>!` fn for a panel-id. Used by the panel-host
    React component to drive the mount on every panel-id change.
  - `(resolve-panel variant-id)` — pure-ish: read the resolved
    `:causa-panel` for `variant-id` (variant slot beats story
    slot beats default `:event-detail`).

  ## Frame isolation

  Every mount path goes through `panels/mount-<panel>!` which wraps
  the panel view in `[rf/frame-provider {:frame :rf/causa} …]` per
  the rf2-crhr8 embedding contract — the panel's Causa-state
  subscribes resolve to `:rf/causa` regardless of the host's
  React-context. Story authors who want a panel observing a
  specific app frame pass `{:frame :my-app/cart}` per the contract;
  the wrapper still resolves Causa's own UI state on `:rf/causa`.

  ## CLJS-only

  Token data + pure helpers are .cljc-friendly; the React
  component + mount-driving side effects are CLJS-only."
  (:require [reagent.core :as r]
            [day8.re-frame2-causa.mount :as causa-mount]
            [day8.re-frame2-causa.panels :as causa-panels]
            [re-frame.story.causa-preset :as causa-preset]
            [re-frame.story.config :as config]
            [re-frame.story.predicates :as pred]
            [re-frame.story.registrar :as registrar]
            [re-frame.story.theme.colors :as colors]
            [re-frame.story.theme.glyphs :as glyphs]
            [re-frame.story.theme.motion :as motion]
            [re-frame.story.theme.typography :as typography :refer [sans-stack]]
            [re-frame.story.ui.state :as state]))

;; ---- panel catalog -------------------------------------------------------

(def panel-catalog
  "Ordered vector of `{:panel <kw> :label <str> :title <str>}` maps
  for every Causa panel exposed in the Story RHS chip-row. Order
  matches the rough debugging-frequency rubric: event-detail first
  (the default), state-shape lenses next (app-db / views), trace
  next, then the specialised lenses (machines / routing / issues).

  Pure data — JVM-portable. Tests assert ordering + completeness
  against the rf2-crhr8 panel set."
  [{:panel :event-detail :label "Event"    :title "Six-domino cascade view for the focused event"}
   {:panel :app-db       :label "App-db"   :title "Structural diff of app-db across the focused cascade"}
   {:panel :views        :label "Views"    :title "Per-view sub-invalidation surface for the focused cascade"}
   {:panel :trace        :label "Trace"    :title "Trace-buffer feed for the focused cascade"}
   {:panel :machines     :label "Machines" :title "State-machine chart + arcs/rings for the focused machine"}
   {:panel :routing      :label "Routing"  :title "Registered-routes lens + simulate-URL surface"}
   {:panel :issues       :label "Issues"   :title "Cascade-scoped issues feed + ungrouped escape-hatch lane"}])

(def default-panel
  "Default panel when neither story nor variant declares one."
  :event-detail)

(def panel-ids
  "Set of valid `:panel` slot values. Pure data — used by the schema
  + sanity-check in `resolve-panel`."
  (set (map :panel panel-catalog)))

;; ---- pure: panel resolution ----------------------------------------------

(defn resolve-panel
  "Read the resolved `:causa-panel` for `variant-id` from the
  registrar. Variant body wins over story body wins over `default-panel`.

  Pure-ish (reads the registrar). Returns one of `panel-ids`; an
  unknown keyword in the slot falls back to `default-panel` so a
  typo doesn't blank the RHS."
  [variant-id]
  (let [variant-body (registrar/handler-meta :variant variant-id)
        story-id     (pred/parent-story-id variant-id)
        story-body   (when story-id
                       (registrar/handler-meta :story story-id))
        ;; rf2-v1ach: the `:causa-panel` slot lives directly on the
        ;; body for ergonomics (parallel to `:tags`, `:viewport`,
        ;; `:background`). The legacy `:causa :panel` nested form is
        ;; ALSO honoured so authors who already write a `:causa` map
        ;; for filters / focus don't have to split it. Variant slot
        ;; wins, then story slot, then default.
        slot         (or (:causa-panel variant-body)
                         (get-in variant-body [:causa :panel])
                         (:causa-panel story-body)
                         (get-in story-body [:causa :panel])
                         default-panel)]
    (if (contains? panel-ids slot)
      slot
      default-panel)))

;; ---- mount-fn dispatch ---------------------------------------------------
;;
;; rf2-senbl: previously this ns used `find-ns-obj` + `aget` to feature-
;; detect the Causa mount fns at runtime. That walk relied on top-level
;; def'd fns being exposed as properties of the parent namespace's JS
;; object — shadow-cljs's namespace organisation does not guarantee
;; that (only sub-namespace refs hang off the parent), so every lookup
;; returned nil and the panel-host never painted. The fix: direct
;; `:require` of `day8.re-frame2-causa.panels` + a `case` dispatch on
;; panel-id. Causa is on the same shadow-cljs source-path as Story (see
;; implementation/shadow-cljs.edn :source-paths), so the require is a
;; compile-time symbol resolution; bundle-isolation still holds because
;; the gate only forbids `implementation/` → `tools/` requires, not
;; `tools/story` → `tools/causa` (the inverse is explicitly fine — see
;; this fix's PR body for the dep-arrow analysis).

(defn mount-fn-for
  "Return the Causa `mount-<panel>!` fn for `panel-id`, or nil when
  `panel-id` is unknown. Compile-time symbol resolution via the
  `case` dispatch — no runtime namespace walk."
  [panel-id]
  (case panel-id
    :event-detail causa-panels/mount-event-detail!
    :app-db       causa-panels/mount-app-db-diff!
    :views        causa-panels/mount-views!
    :trace        causa-panels/mount-trace!
    :machines     causa-panels/mount-machine-inspector!
    :routing      causa-panels/mount-routing!
    :issues       causa-panels/mount-issues-ribbon!
    nil))

;; ---- popout escape hatch -------------------------------------------------

(defn popout-full-shell!
  "Pop out the full Causa 4-layer shell into a second window. Uses
  `day8.re-frame2-causa.mount/popout!` per rf2-zkfiz Q1-8. The
  popout carries the full chrome the per-panel embed elides so
  power users have one click to the whole-shell shape.

  Gated on `causa-available?` so the chip remains a graceful no-op
  when Causa's preload is not on the build (e.g. a pre-rf2-v1ach
  Story-only build); the direct `:require` of
  `day8.re-frame2-causa.mount` above pulls the popout symbol onto
  the compile classpath."
  []
  (when (and config/enabled? (causa-preset/causa-available?))
    (causa-mount/popout!)))

;; ---- styling -------------------------------------------------------------

(def ^:private styles
  {:wrap          {:display "flex"
                   :flex-direction "column"
                   :flex "1 1 auto"
                   :min-height "320px"
                   :overflow "hidden"
                   :gap "8px"}
   ;; rf2-v1ach — chip-row picker. Sits above the mounted panel slot;
   ;; the user clicks a chip to swap which panel renders. Uses Causa's
   ;; cool slate palette via a thin amber-tinted seam — the chip-row
   ;; is Story chrome (warm amber accents) but the chips themselves
   ;; visually point at the Causa panel below.
   :chip-row      {:display "flex"
                   :align-items "center"
                   :gap "4px"
                   :flex-wrap "wrap"
                   :padding-bottom "6px"
                   :border-bottom (str "1px solid " (:border-subtle colors/tokens))
                   :font-family sans-stack}
   :chip          {:padding "3px 10px"
                   :background (:bg-3 colors/tokens)
                   :color (:text-secondary colors/tokens)
                   :border (str "1px solid " (:border-subtle colors/tokens))
                   :border-radius "10px"
                   :cursor "pointer"
                   :font-family sans-stack
                   :font-size (:caption typography/type-scale)
                   :letter-spacing "0.01em"
                   :user-select "none"
                   :transition (:chip motion/transitions)}
   :chip-active   {:background (:accent-amber colors/tokens)
                   :color (:text-on-accent colors/tokens)
                   :border (str "1px solid " (:accent-amber-deep colors/tokens))
                   :font-weight (str (:semibold typography/weights))}
   :spacer        {:flex "1"}
   ;; rf2-v1ach — "pop out full Causa" escape hatch. Sits at the
   ;; right edge of the chip-row so the user reads it as 'leave the
   ;; embed for the full shell'. The chip wears the chevron-right /
   ;; external-link glyph so its action is iconographically obvious.
   :popout-chip   {:padding "3px 10px"
                   :background "transparent"
                   :color (:info colors/tokens)
                   :border (str "1px solid " (:border-default colors/tokens))
                   :border-radius "10px"
                   :cursor "pointer"
                   :font-family sans-stack
                   :font-size (:caption typography/type-scale)
                   :letter-spacing "0.01em"
                   :display "inline-flex"
                   :align-items "center"
                   :gap "4px"
                   :transition (:chip motion/transitions)}
   ;; rf2-v1ach — the mounted-panel slot. Causa's panel mounts into
   ;; this DOM element (a `<div>` with `data-rf-causa-panel-host`).
   ;; `position: relative` + `flex: 1 1 auto` so the panel
   ;; participates in normal flex flow.
   :panel-host    {:position "relative"
                   :display "flex"
                   :flex "1 1 auto"
                   :flex-direction "column"
                   :min-height "240px"
                   :overflow "hidden"
                   :border-radius "4px"
                   :background (:bg-canvas colors/tokens)}
   :empty         {:padding "16px 8px"
                   :color (:text-tertiary colors/tokens)
                   :font-family sans-stack
                   :font-size (:caption typography/type-scale)
                   :font-style "italic"}})

;; ---- chip rendering ------------------------------------------------------

(defn chip
  "Render a single chip for `panel-id`. Click handler dispatches the
  panel selection via `state/swap-state!` so the RHS slot re-renders.
  Public so tests can introspect the chip-level hiccup without
  driving the full host."
  [panel-id label title active?]
  [:button
   {:style (merge (:chip styles)
                  (when active? (:chip-active styles)))
    :role "button"
    :aria-pressed (str active?)
    :title title
    :data-test "story-causa-panel-chip"
    :data-causa-panel (name panel-id)
    :on-click (fn [_]
                (state/swap-state!
                  (fn [s] (assoc s :causa-panel panel-id))))}
   label])

;; ---- panel-id resolution + user-override ---------------------------------

(defn effective-panel
  "Pure data → data: resolve the panel-id to mount given the shell
  state + the focused variant. The user's `:causa-panel` override
  (set by clicking a chip) beats the story/variant slot; if no
  override is set OR the override is `:rf/auto`, fall back to
  `resolve-panel`.

  Public for the JVM test corpus."
  [shell variant-id]
  (let [override (:causa-panel shell)]
    (cond
      (contains? panel-ids override) override
      :else                           (resolve-panel variant-id))))

;; ---- React component: panel-host (drives mount on panel change) ----------

(defn- panel-host-component
  "Reagent class-3 component owning the DOM mount lifecycle for
  ONE Causa panel. On mount + on panel-id change, calls the
  Causa `mount-<panel>!` fn into our `<div>` ref; on unmount,
  calls the unmount fn the mount returned.

  Why a class-3 component: every panel swap is a full unmount →
  mount round-trip. Reagent's :component-did-update + lifecycle
  hooks give us a clean seam to drive this without a render-loop
  race. The `<div>` ref is stable across re-renders so the
  Causa adapter can find its mount-point reliably."
  [_panel-id]
  (let [host-ref    (atom nil)
        unmount-ref (atom nil)
        do-mount!   (fn [pid]
                      (when-let [unmount! @unmount-ref]
                        (try (unmount!) (catch :default _ nil))
                        (reset! unmount-ref nil))
                      (when-let [host @host-ref]
                        (when-let [mount-fn (mount-fn-for pid)]
                          (try
                            (let [unmount! (mount-fn host)]
                              (reset! unmount-ref unmount!))
                            (catch :default e
                              (when (and (exists? js/console)
                                         (.-warn js/console))
                                (.warn js/console
                                       (str "[story.causa-embed] mount " pid " threw: "
                                            (.-message e)))))))))]
    (r/create-class
      {:display-name "story-causa-embed-panel-host"
       :component-did-mount
       (fn [this]
         (do-mount! (-> this r/argv second)))
       :component-did-update
       (fn [this old-argv]
         (let [new-pid (-> this r/argv second)
               old-pid (-> old-argv second)]
           (when (not= new-pid old-pid)
             (do-mount! new-pid))))
       :component-will-unmount
       (fn [_this]
         (when-let [unmount! @unmount-ref]
           (try (unmount!) (catch :default _ nil))
           (reset! unmount-ref nil)))
       :reagent-render
       (fn [pid]
         [:div {:ref (fn [node] (reset! host-ref node))
                :data-rf-causa-panel-host (name pid)
                :data-test "story-causa-panel-host"
                :style (:panel-host styles)}])})))

;; ---- public surface: the embed panel -------------------------------------

(defn causa-embed-panel
  "RHS Causa-host component. Renders a chip-row picker letting the
  user swap between Causa panels at runtime, the mounted panel
  itself, and the 'pop out full Causa' escape hatch.

  Returns nil when no variant is focused — the panels are
  cascade-scoped; without a variant there's nothing to inspect.
  Returns a graceful empty state when Causa is not on the
  classpath (preload absent / production build)."
  []
  (let [shell      @state/shell-state-atom
        variant-id (:selected-variant shell)]
    (cond
      (not variant-id)
      [:div {:style (:empty styles)
             :data-test "story-causa-embed-empty"}
       "Select a variant to inspect via Causa."]

      (not (causa-preset/causa-available?))
      [:div {:style (:empty styles)
             :data-test "story-causa-embed-no-causa"}
       "Causa is not loaded in this build — embed surface unavailable."]

      :else
      (let [active-panel (effective-panel shell variant-id)]
        [:div {:style (:wrap styles)
               :data-test "story-causa-embed"
               :data-active-panel (name active-panel)}
         ;; chip-row + popout
         [:div {:style (:chip-row styles)
                :role "tablist"
                :aria-label "Causa panel picker"}
          (for [{:keys [panel label title]} panel-catalog]
            ^{:key panel}
            [chip panel label title (= panel active-panel)])
          [:span {:style (:spacer styles)}]
          [:button
           {:style (:popout-chip styles)
            :data-test "story-causa-popout"
            :title "Open the full Causa 4-layer shell in a new window"
            :on-click (fn [_] (popout-full-shell!))}
           [:span "Pop out"]
           [glyphs/external-link 11]]]
         ;; the panel-host — keyed by panel-id so a swap forces a
         ;; fresh mount through the lifecycle. The component
         ;; itself ALSO handles the swap via componentDidUpdate;
         ;; the React key is belt-and-braces so a Causa internal
         ;; bug (e.g. a panel that doesn't release its DOM)
         ;; can't carry state across panel-ids.
         ^{:key (str variant-id "::" active-panel)}
         [panel-host-component active-panel]]))))
