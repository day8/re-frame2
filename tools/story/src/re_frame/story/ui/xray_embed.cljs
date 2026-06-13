(ns re-frame.story.ui.xray-embed
  "Xray-in-Story per-panel embed surface (rf2-v1ach).

  Replaces the pre-rf2-v1ach `[data-rf-xray-host]` whole-shell mount
  (the 320px column hosting Xray's 4-layer chrome). Per the audit
  in `ai/findings/2026-05-19-story-design-xray-integration.md` §Part
  B the per-panel embed is shape #3: Story's RHS hosts ONE Xray
  panel at a time, configurable per story via the `:xray-panel` slot
  (default `:epoch` — post rf2-5gl5r; previously `:event-detail`),
  with a chip-row picker letting the user swap (`Epoch` / `App-db` /
  `Views` / `Trace` / `Machines` / `Routing` / `Issues`) at runtime.

  ## Why per-panel beats whole-shell

  - **Width affordance.** Xray's 4-layer chrome (ribbon + L2 event
    list + tab-bar + detail panel) needs ~720px to render usefully;
    Story's RHS is 320px. Cramming the whole shell into the column
    means horizontally-clipped chrome on every render. One panel
    fits cleanly in the available column.
  - **Focus alignment.** Story is a workshop; the user is hovering
    over one component asking one diagnostic question. The full
    4-layer chrome is overkill — one panel = one diagnostic lens
    matches the cognitive load.
  - **Author intent.** `:xray-panel` lets the story author pick the
    most-likely-useful lens (`:app-db` for state-shape stories,
    `:routing` for routing demos, `:trace` for debugging side
    effects). Users can swap if they need a different lens.

  ## Public surface

  - `(xray-embed-panel)` — the RHS Xray-host component. Renders
    the chip-row picker + the mounted panel slot + the popout chip.
  - `(mount-fn-for panel-id)` — pure data → data: the Xray
    `mount-<panel>!` fn for a panel-id. Used by the panel-host
    React component to drive the mount on every panel-id change.
  - `(resolve-panel variant-id)` — pure-ish: read the resolved
    `:xray-panel` for `variant-id` (variant slot beats story
    slot beats default `:epoch` — post rf2-5gl5r the canonical
    default is the Epoch panel; the prior `:event-detail` default
    pointed at the now-retired Event/Handler panel).

  ## Frame isolation

  Every mount path goes through `panels/mount-<panel>!` which wraps
  the panel view in `[rf/frame-provider {:frame :rf/xray} …]` per
  the rf2-crhr8 embedding contract — the panel's Xray-state
  subscribes resolve to `:rf/xray` regardless of the host's
  React-context. Story authors who want a panel observing a
  specific app frame pass `{:frame :my-app/cart}` per the contract;
  the wrapper still resolves Xray's own UI state on `:rf/xray`.

  ## CLJS-only

  Token data + pure helpers are .cljc-friendly; the React
  component + mount-driving side effects are CLJS-only."
  (:require [reagent.core :as r]
            [day8.re-frame2-xray.mount :as xray-mount]
            [day8.re-frame2-xray.panels :as xray-panels]
            [re-frame.story.xray-preset :as xray-preset]
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
  for every Xray panel exposed in the Story RHS chip-row. Order
  matches the rough debugging-frequency rubric: epoch first (the
  default — post rf2-5gl5r supersedes the prior event-detail
  default), state-shape lenses next (app-db / views), trace next,
  then the specialised lenses (machines / routing).

  (rf2-gbz39 — the `:issues` panel was removed alongside the Xray
  Issues tab per Mike's Option (c) ruling; issues surface inline in
  the Epoch panel + the L2 event-row pink-wash + the always-on issues
  ribbon signal, so there is no standalone Issues panel to embed.)

  Pure data — JVM-portable. Tests assert ordering + completeness
  against the rf2-crhr8 panel set."
  [{:panel :epoch        :label "Epoch"    :title "Full computational timeline for the focused event"}
   {:panel :app-db       :label "App-db"   :title "Structural diff of app-db across the focused cascade"}
   {:panel :views        :label "Views"    :title "Per-view sub-invalidation surface for the focused cascade"}
   {:panel :trace        :label "Trace"    :title "Trace-buffer feed for the focused cascade"}
   {:panel :machines     :label "Machines" :title "State-machine chart + arcs/rings for the focused machine"}
   {:panel :routing      :label "Routing"  :title "Registered-routes lens + simulate-URL surface"}])

(def default-panel
  "Default panel when neither story nor variant declares one. Post
  rf2-5gl5r the canonical default is the Epoch panel (the retired
  Event/Handler panel was the prior default)."
  :epoch)

(def panel-ids
  "Set of valid `:panel` slot values. Pure data — used by the schema
  + sanity-check in `resolve-panel`."
  (set (map :panel panel-catalog)))

;; ---- pure: panel resolution ----------------------------------------------

(defn resolve-panel
  "Read the resolved `:xray-panel` for `variant-id` from the
  registrar. Variant body wins over story body wins over `default-panel`.

  Pure-ish (reads the registrar). Returns one of `panel-ids`; an
  unknown keyword in the slot falls back to `default-panel` so a
  typo doesn't blank the RHS."
  [variant-id]
  (let [variant-body (registrar/handler-meta :variant variant-id)
        story-id     (pred/parent-story-id variant-id)
        story-body   (when story-id
                       (registrar/handler-meta :story story-id))
        ;; rf2-v1ach: the `:xray-panel` slot lives directly on the
        ;; body for ergonomics (parallel to `:tags`, `:viewport`,
        ;; `:background`). Variant slot wins, then story slot, then
        ;; default.
        slot         (or (:xray-panel variant-body)
                         (:xray-panel story-body)
                         default-panel)]
    (if (contains? panel-ids slot)
      slot
      default-panel)))

;; ---- mount-fn dispatch ---------------------------------------------------
;;
;; rf2-senbl: previously this ns used `find-ns-obj` + `aget` to feature-
;; detect the Xray mount fns at runtime. That walk relied on top-level
;; def'd fns being exposed as properties of the parent namespace's JS
;; object — shadow-cljs's namespace organisation does not guarantee
;; that (only sub-namespace refs hang off the parent), so every lookup
;; returned nil and the panel-host never painted. The fix: direct
;; `:require` of `day8.re-frame2-xray.panels` + a `case` dispatch on
;; panel-id. Xray is on the same shadow-cljs source-path as Story (see
;; implementation/shadow-cljs.edn :source-paths), so the require is a
;; compile-time symbol resolution; bundle-isolation still holds because
;; the gate only forbids `implementation/` → `tools/` requires, not
;; `tools/story` → `tools/xray` (the inverse is explicitly fine — see
;; this fix's PR body for the dep-arrow analysis).

(defn mount-fn-for
  "Return the Xray `mount-<panel>!` fn for `panel-id`, or nil when
  `panel-id` is unknown. Compile-time symbol resolution via the
  `case` dispatch — no runtime namespace walk. (rf2-5gl5r retired
  `:event-detail` in favour of `:epoch`; rf2-gbz39 removed `:issues`
  alongside the Xray Issues tab per Mike's Option (c) ruling.)

  rf2-9k43e — `:event-spine` resolves to `mount-event-spine!`, the
  isolated L2 event list. It is NOT a chip-row panel (it's not in
  `panel-catalog`); the embed renders it as a persistent compact band
  ABOVE the chip-selected panel so past events are clickable in-place.
  The `panel-host-component` class drives its mount via the same
  contract every chip panel uses."
  [panel-id]
  (case panel-id
    :epoch        xray-panels/mount-epoch-panel!
    :app-db       xray-panels/mount-app-db-diff!
    :views        xray-panels/mount-reactive-panel!
    :trace        xray-panels/mount-trace!
    :machines     xray-panels/mount-machine-inspector!
    :routing      xray-panels/mount-routing!
    :event-spine  xray-panels/mount-event-spine!
    nil))

;; ---- popout escape hatch -------------------------------------------------

(defn popout-full-shell!
  "Pop out the full Xray 4-layer shell into a second window. Uses
  `day8.re-frame2-xray.mount/popout!` per rf2-zkfiz Q1-8. The
  popout carries the full chrome the per-panel embed elides so
  power users have one click to the whole-shell shape.

  Gated on `xray-available?` so the chip remains a graceful no-op
  when Xray's preload is not on the build (e.g. a pre-rf2-v1ach
  Story-only build); the direct `:require` of
  `day8.re-frame2-xray.mount` above pulls the popout symbol onto
  the compile classpath."
  []
  (when (and config/enabled? (xray-preset/xray-available?))
    (xray-mount/popout!)))

;; ---- styling -------------------------------------------------------------

(def ^:private styles
  {:wrap          {:display "flex"
                   :flex-direction "column"
                   :flex "1 1 auto"
                   :min-height "320px"
                   :overflow "hidden"
                   :gap "8px"}
   ;; rf2-v1ach — chip-row picker. Sits above the mounted panel slot;
   ;; the user clicks a chip to swap which panel renders. The chip-row
   ;; is Story-owned chrome but visually points at the Xray panel below.
   ;; rf2-ba86n.3 — the chip-row's underline wears the cool Story↔Xray
   ;; SEAM tint (spec/018 §12.9) so the whole Xray band — section header
   ;; AND chip row — reads as the diagnostic boundary, distinct from the
   ;; warm Story-owned sections stacked above it. One quiet border; Xray
   ;; still owns the panel interior below.
   :chip-row      {:display "flex"
                   :align-items "center"
                   :gap "4px"
                   :flex-wrap "wrap"
                   :padding-bottom "6px"
                   :border-bottom (str "1px solid " (:seam-xray-soft colors/tokens))
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
   ;; rf2-v1ach — "pop out full Xray" escape hatch. Sits at the
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
   ;; rf2-v1ach — the mounted-panel slot. Xray's panel mounts into
   ;; this DOM element (a `<div>` with `data-rf-xray-panel-host`).
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
   ;; rf2-9k43e — the compact in-place EVENT SPINE band. Sits BETWEEN
   ;; the chip-row and the chip-selected panel-host so the user reads:
   ;; pick a lens (chip-row) → scan the variant's recent events (spine)
   ;; → click a past event → the panel below re-renders against that
   ;; epoch. It hosts the SAME `shell/event-list` L2 component the full
   ;; Xray shell composes (via `mount-event-spine!`), not a parallel
   ;; spine. The band is height-capped (the full-shell L2 defaults to
   ;; ~200px; the embed is 320px-wide and stacks below the chip-row, so
   ;; a `max-height` keeps it compact and lets the list's own
   ;; `overflow-y:auto` scroll older events) — the host owns the
   ;; container size per spec/008 §Embed props inventory.
   :spine-host    {:position "relative"
                   :display "flex"
                   :flex "0 0 auto"
                   :flex-direction "column"
                   :max-height "140px"
                   :overflow "hidden"
                   :border-radius "4px"
                   :background (:bg-canvas colors/tokens)}
   ;; rf2-9k43e — caption above the spine band so the affordance reads
   ;; as 'this is the variant's recent-events timeline; click to focus'.
   :spine-caption {:padding "0 2px 2px"
                   :color (:text-tertiary colors/tokens)
                   :font-family sans-stack
                   :font-size (:caption typography/type-scale)
                   :letter-spacing "0.02em"
                   :text-transform "uppercase"
                   :user-select "none"}
   :empty         {:padding "16px 8px"
                   :color (:text-tertiary colors/tokens)
                   :font-family sans-stack
                   :font-size (:caption typography/type-scale)
                   :font-style "italic"}
   ;; rf2-ba86n.19 — lazy Xray-diff mounting. The disclosure toggle that
   ;; collapses / expands the embed. Sits at the LEFT edge of the chip-row
   ;; (before the panel chips) so it reads as 'this whole band folds'. The
   ;; chevron points right when collapsed, rotates down when expanded.
   :disclosure    {:display "inline-flex"
                   :align-items "center"
                   :justify-content "center"
                   :padding "2px"
                   :background "transparent"
                   :color (:text-secondary colors/tokens)
                   :border "none"
                   :cursor "pointer"
                   :line-height "0"
                   :transition (:chip motion/transitions)}
   :disclosure-glyph {:display "inline-flex"
                      :transition (:chip motion/transitions)
                      :transform "rotate(90deg)"}
   :disclosure-glyph-collapsed {:transform "rotate(0deg)"}
   ;; rf2-ba86n.19 — the collapsed placeholder. Replaces the panel-host
   ;; (and therefore the panel's expensive diff compute) while collapsed.
   ;; A single quiet line; clicking it expands.
   :collapsed-rest {:display "flex"
                    :align-items "center"
                    :flex "1 1 auto"
                    :min-height "0"
                    :padding "10px 8px"
                    :cursor "pointer"
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
    :data-test "story-xray-panel-chip"
    :data-xray-panel (name panel-id)
    :on-click (fn [_]
                (state/swap-state!
                  (fn [s] (assoc s :xray-panel panel-id))))}
   label])

;; ---- panel-id resolution + user-override ---------------------------------

(defn effective-panel
  "Pure data → data: resolve the panel-id to mount given the shell
  state + the focused variant. The user's `:xray-panel` override
  (set by clicking a chip) beats the story/variant slot; if no
  override is set OR the override is `:rf/auto`, fall back to
  `resolve-panel`.

  Public for the JVM test corpus."
  [shell variant-id]
  (let [override (:xray-panel shell)]
    (cond
      (contains? panel-ids override) override
      :else                           (resolve-panel variant-id))))

;; ---- React component: panel-host (drives mount on panel change) ----------
;;
;; rf2-4l7t2: React 18+ throws "Attempted to synchronously unmount a root
;; while React was already rendering" whenever a Xray-owned React root is
;; torn down inside the outer Story-Reagent render cascade. The previous
;; shape ("React key on the panel-host slot, sync unmount in
;; componentWillUnmount") cycled the host React class on every chip click,
;; which fired the inner root's unmount inside the parent's chip-click
;; re-render commit — exactly what React 18+ refuses.
;;
;; The fix is the option-(b) shape from rf2-4l7t2: one persistent host
;; class, panel-id drives an internal swap. The lifecycle invariants:
;;
;;   (1) The outer hiccup MUST NOT key `panel-host-component` on
;;       `active-panel`. The host React class stays alive across panel-id
;;       swaps; `:component-did-update` drives the internal mount/unmount
;;       round-trip after the parent's commit phase has completed.
;;
;;   (2) Each panel-id swap mounts the new Xray root into a FRESH child
;;       `<div>` inside the stable host element. The previous panel's
;;       child node and its React root are released on the next microtask
;;       (the child stays in the DOM until after `.unmount` so React's
;;       commit-phase DOM walk sees what it expects; then we remove the
;;       node).
;;
;;       Why a fresh child per mount and not the same host node:
;;       `ReactDOMClient.createRoot()` REFUSES a container that already
;;       has a root attached. With a single persistent container, even
;;       deferring the prior `.unmount` to a microtask would race the
;;       next `createRoot` call ("createRoot on a container that has
;;       already been passed to createRoot before"). Per-panel child
;;       containers sidestep that constraint entirely — each Xray
;;       `mount-<panel>!` sees a clean node it owns exclusively.
;;
;;   (3) Every `.unmount` (the internal panel swap AND the host's own
;;       `:component-will-unmount`) is deferred via `js/queueMicrotask`
;;       so the React 18+ root API never sees a synchronous unmount
;;       inside the outer render cycle.
;;
;; Pin: `tools/story/test/re_frame/story/panels_e2e/xray_embed_e2e_cljs_test.cljs`
;; (rf2-piucm CLJS e2e — replaced the retired `xray_rhs_smoke` Playwright
;; spec) asserts that the chip-click round-trip still flips
;; `data-active-panel` AND paints the new panel's root — both behaviours
;; survive because the new mount runs synchronously into the fresh child
;; container; only the prior root's release is queued.

(defn- panel-host-component
  "Reagent class-3 component owning the DOM mount lifecycle for an Xray
  mount-host `<div>` across an arbitrary number of panel-id swaps. On
  mount + on panel-id change, mounts the Xray `mount-<panel>!` fn into a
  fresh child `<div>` of the host; on unmount, releases every
  still-mounted Xray root via microtask so the React 18+ root API never
  sees a synchronous unmount inside the parent render cycle.

  Argv: `[<panel-id>]` or `[<panel-id> <opts>]`. `opts` (rf2-9k43e) lets
  the SAME class serve both the chip-selected panel host AND the compact
  event-spine band:

  - `:host-style`   — the host `<div>` inline style (defaults to the
    panel-host style).
  - `:host-test-id` — the host `<div>` `data-test` attr (defaults to
    `\"story-xray-panel-host\"`).

  Only the panel-id participates in the mount/swap diff; the opts are
  pure presentation so a change to them never re-fires the mount.

  Lifecycle invariants (rf2-4l7t2):

  - The host React class persists across panel-id swaps —
    `:component-did-update` drives the in-place mount/unmount
    round-trip. The host `<div>` ref is stable so the Xray
    adapter can rely on a known parent across swaps.
  - Each `mount-<panel>!` call gets its own fresh child container,
    so `ReactDOMClient.createRoot()` never sees a container that
    already owns a root.
  - Every Xray `unmount!` thunk + DOM node removal runs inside
    `js/queueMicrotask` so the React 18+ root API never sees a
    synchronous unmount inside the outer render cycle (the source
    of the 'Attempted to synchronously unmount a root while React
    was already rendering' warning that previously fired 17× per
    Story-Xray browser-gate run)."
  [_panel-id & _opts]
  (let [host-ref    (atom nil)
        ;; `mounted-ref` holds `{:unmount fn :container <div>}` for the
        ;; currently-mounted Xray root, or nil. Cleared eagerly when
        ;; swapping; the actual `.unmount` + DOM node removal is queued
        ;; on a microtask so React's outer commit phase has released
        ;; before the inner root unmounts.
        mounted-ref (atom nil)
        release!    (fn []
                      (when-let [{:keys [unmount container]} @mounted-ref]
                        (reset! mounted-ref nil)
                        (js/queueMicrotask
                          (fn []
                            (try (unmount) (catch :default _ nil))
                            (when-let [parent (some-> container .-parentNode)]
                              (try (.removeChild parent container)
                                   (catch :default _ nil)))))))
        do-mount!   (fn [pid]
                      (release!)
                      (when-let [host @host-ref]
                        (when-let [mount-fn (mount-fn-for pid)]
                          (try
                            (let [container (.createElement js/document "div")]
                              ;; Mark the container so DOM-level
                              ;; assertions / dev-tools can identify
                              ;; which Xray panel owns it. The
                              ;; outer host carries `data-test` /
                              ;; `data-rf-xray-panel-host` already;
                              ;; this attr is the inner counterpart
                              ;; for the per-mount child container.
                              (.setAttribute container
                                             "data-rf-xray-panel-mount"
                                             (name pid))
                              ;; Make the inner container a transparent
                              ;; flex passthrough — it inherits the host's
                              ;; flex-column behaviour so the Xray panel
                              ;; lays out as if it were a direct child of
                              ;; the host. `display: contents` would
                              ;; vanish from layout entirely but has
                              ;; accessibility-tree quirks across
                              ;; browsers; a plain flex-column passthrough
                              ;; is more robust.
                              (set! (.. container -style -display) "flex")
                              (set! (.. container -style -flexDirection) "column")
                              (set! (.. container -style -flex) "1 1 auto")
                              (set! (.. container -style -minHeight) "0")
                              (set! (.. container -style -overflow) "hidden")
                              (.appendChild host container)
                              (let [unmount! (mount-fn container)]
                                (reset! mounted-ref
                                        {:unmount unmount!
                                         :container container})))
                            (catch :default e
                              (when (and (exists? js/console)
                                         (.-warn js/console))
                                (.warn js/console
                                       (str "[story.xray-embed] mount " pid " threw: "
                                            (.-message e)))))))))]
    (r/create-class
      {:display-name "story-xray-embed-panel-host"
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
         (release!))
       :reagent-render
       (fn [pid & {:keys [host-style host-test-id]}]
         [:div {:ref (fn [node] (reset! host-ref node))
                :data-rf-xray-panel-host (name pid)
                :data-test (or host-test-id "story-xray-panel-host")
                :style (or host-style (:panel-host styles))}])})))

;; ---- disclosure toggle (rf2-ba86n.19) ------------------------------------

(defn disclosure-toggle
  "Render the collapse/expand disclosure button for the embed. Clicking
  flips `:xray-embed-collapsed?` on the shell ratom. The chevron points
  right when collapsed and rotates down when expanded.

  Public so tests can introspect the toggle's hiccup + click handler
  without driving the full host."
  [collapsed?]
  [:button
   {:style (:disclosure styles)
    :data-test "story-xray-disclosure"
    :aria-expanded (str (not collapsed?))
    :aria-label (if collapsed? "Expand Xray embed" "Collapse Xray embed")
    :title (if collapsed?
             "Expand the Xray panel (resume diff compute)"
             "Collapse the Xray panel (defer diff compute)")
    :on-click (fn [_]
                (state/swap-state! state/toggle-xray-embed-collapsed))}
   [:span {:style (merge (:disclosure-glyph styles)
                         (when collapsed? (:disclosure-glyph-collapsed styles)))}
    [glyphs/chevron-right 11]]])

;; ---- event-spine band (rf2-9k43e) ----------------------------------------

(defn spine-band
  "The compact, clickable recent-events SPINE for the Story RHS Xray
  embed (rf2-9k43e). Renders a labelled band hosting the SAME
  `shell/event-list` L2 component the full Xray shell composes — via
  `xray-panels/mount-event-spine!` — so the variant's event SEQUENCE is
  inspectable IN-PLACE, not only the final/focused event.

  Mike RULED scope = A (2026-06-01): add the inline spine so past
  events/epochs are focusable in-place; keep the Ctrl+Shift+C / `Pop
  out` full-shell escape hatch for deep history. Clicking a past event
  in the spine dispatches `:rf.xray/focus-cascade` (the row's own
  handler — reused verbatim from the full shell), which re-binds the
  spine sub `:rf.xray/focus`; the chip-selected panel below — mounted in
  the SAME `:rf/xray` frame — re-renders against the chosen epoch.

  The band mounts through the SAME `panel-host-component` class the
  chip-selected panel uses (with the spine host-style + test-id), so it
  inherits the rf2-4l7t2 React-18 deferred-unmount lifecycle. Its
  panel-id (`:event-spine`) never changes, so the host class mounts the
  spine once and leaves it; focus changes flow through the spine's own
  subs, not a remount.

  Public so the e2e hiccup test can assert the band + its mount slot."
  []
  [:div {:data-test "story-xray-spine-band"
         :style {:display "flex" :flex-direction "column" :flex "0 0 auto"}}
   [:div {:style (:spine-caption styles)
          :data-test "story-xray-spine-caption"}
    "Events — click to focus"]
   [panel-host-component :event-spine
    :host-style   (:spine-host styles)
    :host-test-id "story-xray-spine-host"]])

;; ---- public surface: the embed panel -------------------------------------

(defn xray-embed-panel
  "RHS Xray-host component. Renders a chip-row picker letting the
  user swap between Xray panels at runtime, the mounted panel
  itself, and the 'pop out full Xray' escape hatch.

  Returns nil when no variant is focused — the panels are
  cascade-scoped; without a variant there's nothing to inspect.
  Returns a graceful empty state when Xray is not on the
  classpath (preload absent / production build)."
  []
  (let [shell      @state/shell-state-atom
        variant-id (:selected-variant shell)]
    (cond
      (not variant-id)
      [:div {:style (:empty styles)
             :data-test "story-xray-embed-empty"}
       "Select a variant to inspect via Xray."]

      (not (xray-preset/xray-available?))
      [:div {:style (:empty styles)
             :data-test "story-xray-embed-no-xray"}
       "Xray is not loaded in this build — embed surface unavailable."]

      :else
      (let [active-panel (effective-panel shell variant-id)
            ;; rf2-ba86n.19 — lazy Xray-diff mounting (spec/018 §10). When
            ;; collapsed we DON'T render `panel-host-component`, so no
            ;; `mount-<panel>!` fires and the panel's expensive diff compute
            ;; (app-db structural diff, epoch timeline) is deferred until the
            ;; user expands. The chip-row picker still paints (cheap — it's
            ;; pure data + click handlers, no Xray symbol), so the author's
            ;; lens choice survives a collapse round-trip.
            collapsed?   (state/xray-embed-collapsed? shell)]
        [:div {:style (:wrap styles)
               :data-test "story-xray-embed"
               :data-active-panel (name active-panel)
               :data-xray-embed-collapsed (str collapsed?)}
         ;; chip-row + disclosure + popout
         [:div {:style (:chip-row styles)
                :role "tablist"
                :aria-label "Xray panel picker"}
          [disclosure-toggle collapsed?]
          (for [{:keys [panel label title]} panel-catalog]
            ^{:key panel}
            [chip panel label title (= panel active-panel)])
          [:span {:style (:spacer styles)}]
          [:button
           {:style (:popout-chip styles)
            :data-test "story-xray-popout"
            :title "Open the full Xray 4-layer shell in a new window"
            :on-click (fn [_] (popout-full-shell!))}
           [:span "Pop out"]
           [glyphs/external-link 11]]]
         ;; rf2-ba86n.19 — lazy Xray-diff mounting (spec/018 §10). When
         ;; collapsed a quiet placeholder stands in for the panel-host;
         ;; crucially it does NOT render `panel-host-component`, so the
         ;; Xray mount + its expensive diff compute never fire while
         ;; collapsed. Clicking the placeholder expands. When the embed was
         ;; previously expanded, dropping the panel-host from the tree
         ;; unmounts it → its `:component-will-unmount` releases the Xray
         ;; React root via the existing microtask path (rf2-4l7t2); no
         ;; teardown is duplicated here.
         ;;
         ;; The panel-host slot (when expanded). rf2-4l7t2: NO React key on
         ;; this slot — the host class persists across panel-id swaps so
         ;; `:component-did-update` handles the in-place mount round-trip.
         ;; Keying the slot on `active-panel` (the pre-fix "belt-and-braces"
         ;; shape) would force a full unmount/remount of the host on every
         ;; chip click, and the synchronous `.unmount` of the Xray-owned
         ;; React root would fire inside the parent's chip-click render
         ;; cycle — exactly the "Attempted to synchronously unmount a root
         ;; while React was already rendering" warning React 18+ guards
         ;; against. The host's `:component-did-update` lifecycle keyed by
         ;; panel-id argv-diff drives the swap; the inner unmount runs
         ;; deferred (see `release!` inside `panel-host-component`).
         ;;
         ;; Variant focus changes still re-render this hiccup via the outer
         ;; `effective-panel` resolution; the host's argv-diff in
         ;; `:component-did-update` re-runs the mount when the resolved
         ;; panel-id changes, and same-panel-id variant changes are absorbed
         ;; by the Xray panel's own subs (`:rf.xray/focus` tracks the
         ;; variant-driven cascade).
         (when collapsed?
           [:div {:style (:collapsed-rest styles)
                  :data-test "story-xray-embed-collapsed"
                  :on-click (fn [_]
                              (state/swap-state! state/set-xray-embed-collapsed false))}
            (str (or (some #(when (= active-panel (:panel %)) (:label %))
                           panel-catalog)
                     "Xray")
                 " panel collapsed — expand to compute diffs.")])
         ;; rf2-9k43e — when expanded, the compact event SPINE band
         ;; renders ABOVE the chip-selected panel so the variant's recent
         ;; events are clickable in-place. Clicking a past event re-binds
         ;; `:rf.xray/focus`; the panel below (same `:rf/xray` frame)
         ;; re-renders against the chosen epoch. The spine + panel are
         ;; SIBLINGS of the wrapper (not fragment-nested) so the panel-host
         ;; slot stays a direct wrapper child for the e2e hiccup walkers.
         ;; While collapsed neither the spine NOR the panel mounts —
         ;; lazy-diff compute deferral (rf2-ba86n.19) covers both.
         (when-not collapsed? [spine-band])
         (when-not collapsed? [panel-host-component active-panel])]))))
