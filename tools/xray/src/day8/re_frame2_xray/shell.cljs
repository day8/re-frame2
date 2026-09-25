(ns day8.re-frame2-xray.shell
  "The Xray shell — 4-layer chrome per `tools/xray/spec/018-Event-Spine.md`
  §2 The 4-layer chrome.

  ## Layout

  Per spec/018 the chrome is four stacked layers.

      ┌───────────────────────────────────────────────────────┐
      │ L1  Top ribbon (56px)                                 │  scope controls
      ├───────────────────────────────────────────────────────┤
      │ L2  Event list (8 rows default; resizable; min 2)     │  the spine / timeline
      ├───────────────────────────────────────────────────────┤
      │ L3  Tab bar (40px) — one tab per registered lens      │  projection selector
      ├───────────────────────────────────────────────────────┤
      │ L4  Detail panel (fills remaining canvas)             │  per-tab content
      └───────────────────────────────────────────────────────┘

  L1 / L3 are fixed-height; L2 takes a default 8-row height and is
  user-resizable via the L2/L3 boundary drag handle; L4 takes the
  remainder. Only L2/L3 carries a drag handle.

  ## Ribbon clusters (L1)

  Four clusters, fixed order left → right per spec/018 §3 (there is no
  explicit `● LIVE` / `◐ RETRO` mode pill: the spine's mode is
  derivable from sticky-row selection + the `[◀ ▶ ⏭]` cluster + the
  `:rf.xray/focus` sub, and the Space / l / G keybindings give the
  toggle access):

  - **Nav** (`◀ ▶ ⏭`) — back / forward / fast-forward through the
    spine. Dispatches `:rf.xray/focus-event-prev` / `-next` /
    `:rf.xray/follow-head`. Pressing `⏭` (or `Space` in paused-LIVE,
    or `l` in RETRO) snaps focus back to head.
  - **Frame picker** — single-select dropdown over the event-bundle list's
    distinct frames. Excludes `:rf/xray` by default per §8 I1.
  - **Filter pills** — IN (green, `+`) and OUT (magenta, `×`) pills
    + trailing `[+]` add-pill. Click any pill → edit popup.
  - **Right icons** — `⛶` pop-out · `⚙` settings · `✕` close. The
    `⛶` button is the canonical chrome launch for the second-window
    pop-out mode (spec/011-Launch-Modes.md); `(xray/popout!)`
    remains the secondary programmatic path.

  The REDACTED indicator (`[● REDACTED N]`) renders inline next to
  the right-icons cluster when the suppressed-sensitive count is
  positive, surfacing privacy state without a permanent ribbon slot.

  ## Event list (L2)

  Single-line rows, latest-on-bottom, 8 visible by default. Each row:
  gutter glyph (`● ◉ x ▥`) + event-id + right-aligned badge cluster
  (`⚠ 🌐 🤖`) + trailing redaction marker (`[● REDACTED N]`). Click
  a row → `:rf.xray/focus-event <id>` flips spine to RETRO and
  rebinds every dependent surface in one frame.

  ## Tab bar (L3)

  The strip is whatever `panel-registry/tabs-for-mode :dynamic` has
  registered, ordered by `:order` — it is not a literal in this ns, so
  adding a tab is one `reg-l4-tab!` call and nothing here changes. As
  registered today, with mnemonic letters per spec/018 §11:

      Epoch (e) · app-db (a) · Views (v) · Trace (t) · Machine (m) ·
      Routes (r) · Resources (s) · Graph (g) · Frames (u) · Fresco (h)

  Selection lives on `:rf.xray/selected-tab` and drives the L4
  detail panel's registry lookup. Routing has its own tab by the
  cohesive-sub-domain rule (sub-domains earn their own lens tab
  rather than overloading the parent tab).

  There is no Issues tab. Issues surface inline in the Epoch panel
  (per-step pass/fail + exception block; `:db` schema-fail in the SIDE
  EFFECTS step; slow-fx amber), via the L2 event-row pink-wash (rows
  whose epoch has an issue), and via the always-on issues ribbon
  signal (the auto-open-on-error watcher reading the
  `:rf.xray/issues-ribbon` composite). There is deliberately no
  session-wide aggregate / triage list.

  ## Detail panel (L4)

  Renders the active tab's projection of the focused event. The L4
  panel is registry-driven: each tab registers its
  `:panel` via `panel-registry/reg-l4-tab!` and the shell mounts the
  active tab through `panel-registry/tab-by-id`. Every registered
  Dynamic tab mounts a real panel — Epoch →
  `epoch-panel/Panel` (the canonical numbered event-bundle per
  021 §9.1), app-db →
  `app-db-diff/Panel`, Views → `reactive-panel/Panel` (the 021 §3
  three-stacked-tables design), Trace → `trace/Panel`,
  Machine → `machine-inspector/Panel`, Routes → `routing/Panel`,
  Resources → `resources/Panel`, Graph → `derivation-graph/Panel`,
  Frames → `module-view/Panel`, Fresco → `fresco/Panel`.

  ## Frame isolation

  The shell is wrapped in `[rf.fresco/frame-provider {:frame :rf/xray}]`.
  Every `subscribe` / `dispatch` inside the shell resolves to the
  `:rf/xray` frame; the host's `:rf/default` is untouched. Xray's
  own registrations under `:rf.xray/*` operate against `:rf/xray`'s
  db when called from inside the shell.

  Every reading region of the shell carries the frame by construction
  rather than by ambient lookup — a Fresco boundary declares it, and the
  one `reg-view` region ([[event-list]]) takes it through `:contextType
  frame-context` from the closest enclosing Provider. With plain `defn`s
  the React-context tier would be skipped (Spec 000 §Plain Reagent fns do not pick up the
  surrounding frame) and the ambient subscribe would RAISE
  `:rf.error/no-frame-context` — under EP-0002 there is no `:rf/default`
  floor for it to fall through to, so an unregistered reading region is
  a loud refusal rather than a silent query into the host's app-db
  (Spec 006 §Plain-fn footgun).

  ## The Dynamic chrome is a FRESCO TREE

  Seven regions are `rf.fresco/defview` BOUNDARIES —
  [[ribbon-theme-toggle]], [[ribbon]], [[events-ribbon]], [[tab-bar]],
  [[detail-panel]], [[dynamic-chrome]] and [[surface-composer]].
  [[event-list]] is the ONE exception and its docstring carries the
  measured reason. A boundary declares its frame, so the guarantee
  above is stronger rather than different: reads are `rf.fresco/sub`
  (plain calls the shipped collector records an edge for) and writes go
  through `(:dispatch (rf/capture-frame))`, core's own door, which
  answers the boundary's DECLARED frame. An AMBIENT read or dispatch
  inside a boundary body is a LOUD REFUSAL rather than a silent
  fall-through to `:rf/default`, which is the whole point.

  [[ShellView]] IS ONE TOO, and it is the eighth: the shell does not
  paint through the installed adapter's `:render` at all — `mount.cljs`
  owns a Fresco root and heads [[ShellView]] under its own
  `rf.fresco/frame-provider`. [[shell-view]] is the public CALLABLE
  every Reagent caller holds, and answers the element [[ShellView]]
  lowers to; there is no `as-component` bridge, because there is no
  crossing.

  Each boundary's body is thin: it reads, and calls a pure `*-tree` fn
  that owns the hiccup. That is `defview`'s own documented
  extract-a-helper spelling, and it is what gives the node lane a door —
  `test-helpers.dynamic-shell-tree` drives the same `*-tree` fns with
  `rf/subscribe` in place of `rf.fresco/sub`, so a node-lane row walks
  the shipped tree rather than a parallel fixture.

  ## Pure hiccup

  The view code is pure hiccup. `mount.cljs` paints it through the
  `re-frame.fresco` client root Xray owns (`rf.fresco/render!`), not
  through the installed substrate adapter's `:render` — see the
  Fresco-tree section above. No per-substrate switches in view code."
  (:require [clojure.string :as str]
            [re-frame.core :as rf]
            [re-frame.fresco :as rf.fresco]
            [re-frame.interop :as rf.interop]
            [day8.re-frame2-xray.substrate :as substrate]
            [day8.re-frame2-xray.config :as config]
            [day8.re-frame2-xray.defaults :as defaults]
            [day8.re-frame2-xray.filters :as filters]
            [day8.re-frame2-xray.filters.pills :as filter-pills]
            [day8.re-frame2-xray.frame-switcher :as frame-switcher]
            [day8.re-frame2-xray.panel-registry :as panel-registry]
            [day8.re-frame2-xray.panels.common-helpers :as common]
            [day8.re-frame2-xray.panels.cancellation-cascade :as cancellation-cascade]
            ;; Panel views are pulled in via the L4 tab registry —
            ;; each panel's `install!` registers `{:panel <view-fn>}`
            ;; with `panel-registry/reg-l4-tab!` and
            ;; `detail-panel` reaches the entry through
            ;; `panel-registry/tab-by-id`. The shell does not require
            ;; those panel nses directly.
            ;; L2 `source` column dispatch-origin tag.
            ;; Pure-fn helpers live in `panels/l2-timeline.cljc` so the
            ;; shape is JVM-testable; the shell consumes them in
            ;; `event-row` (single insertion site).
            [day8.re-frame2-xray.panels.l2-timeline :as l2-timeline]
            [day8.re-frame2-xray.palette :as palette]
            [day8.re-frame2-xray.resize-handle :as resize-handle]
            [day8.re-frame2-xray.settings.editor-hint :as editor-hint]
            [day8.re-frame2-xray.settings.popup :as settings-popup]
            [day8.re-frame2-xray.views.edn-inspector-popup
             :as edn-inspector-popup]
            ;; The L2 newer-events marker counts over the
            ;; SPINE's own focusable vector (`focusable-event-bundles`),
            ;; the very vector `spine/compose-focus` derives `:head?`
            ;; from, so "newer" and "head" agree by construction. Reading
            ;; the shipped fn rather than restating its predicate here is
            ;; the whole point of the require.
            [day8.re-frame2-xray.spine :as spine]
            [day8.re-frame2-xray.spine-filters :as spine-filters]
            [day8.re-frame2-xray.static.mode-pill :as mode-pill]
            [day8.re-frame2-xray.static.shell :as static-shell]
            [day8.re-frame2-xray.theme.global-styles :as global-styles]
            [day8.re-frame2-xray.theme.tokens
             :refer [tokens type-scale layout sans-stack mono-stack
                     duration-css motion]]))

;; ---- shell frame-id ------------------------------------------------------
;;
;; The shell's app-db lives in a frame. The PRODUCTION singleton mounts
;; against `:rf/xray` (the sibling of the host's `:rf/default`); that
;; keyword is the ONLY permitted bare `:rf/xray` literal in the
;; render-tree — every other affordance resolves
;; its frame from React-context or a captured dispatcher.
;;
;; Testbeds that mount N shells side-by-side (the panel-gallery
;; `:variants-grid`, a Story workspace) pass DISTINCT frame-ids so each
;; cell's app-db (focused epoch, selected tab, theme) is isolated.
;; `shell-view` takes the frame-id as an opt (default `default-frame-id`)
;; and wraps the chrome in `[frame-provider {:frame that-id}]`. Handlers
;; register GLOBALLY once under `:rf.xray/*` (the registry is process-
;; global, not per-frame), so only the frame-id for app-db isolation
;; threads through — no per-instance handler re-registration.
;; The Var lives in the dependency-free `defaults` seam (so the
;; low-level `views/resizable-table` widget can read it without a
;; require cycle); re-exported here as `shell/default-frame-id` for the
;; mount + testbed call sites that thread the shell's frame.
(def default-frame-id
  "Production singleton frame-id for the Xray shell — re-export of
  `defaults/default-frame-id`. The single permitted bare `:rf/xray`
  render-tree literal."
  defaults/default-frame-id)

;; ---- tab inventory ------------------------------------------------------
;;
;; The L3 tab inventory lives in the internal
;; `panel-registry` — each panel's `install!` registers its own tab
;; metadata declaratively (`reg-l4-tab!`), and the L3 tab-bar +
;; L4 detail-panel read the registry via `tabs-for-mode :dynamic`
;; (driven by the `:modes` set on each entry).
;;
;; The Dynamic tabs registered against `#{:dynamic}` retain the
;; canonical left-to-right order via `:order` — spec/018 §5 ordering is
;; preserved as registration metadata rather than a literal vector in
;; this ns, so the inventory and its span are whatever the registry
;; holds (Epoch sits leftmost at `:order -1`; the values are sparse,
;; leaving room between tabs).
;;
;; Most labels use spaces so the rendered text carries no `-` glyphs.
;; The app-db tab's label is the lowercase library term `app-db`
;; and DOES carry a `-`; the accessible-name collision all-spaces
;; labels guard against (Playwright's
;; `getByRole('button', {name: '-'})` lassoing a host counter's `-`
;; button) is handled by `tab-button`'s wrapped `aria-label`
;; (`Xray <label> tab`) — the accessible name
;; is not the bare label, so a `-` query can't match it.
;;
;; Frame-switcher concerns (the internal-frames filter set, distinct-
;; frames helper, ribbon picker view) live in `frame_switcher.cljs`
;; — the L1 ribbon's frame slot is a single contractually-
;; anchored surface every frame-aware feature reaches through. The
;; ribbon's `[frame-switcher/frame-switcher-view]` is the only call
;; site here.

(defn- dynamic-tabs
  "Ordered Dynamic tab entries — reads the panel registry. Pulled
  through a fn (not a def) so re-`install!`-driven registrations
  during shadow-cljs `:after-load` are picked up by the tab bar
  without a manual reload of this ns."
  []
  (panel-registry/tabs-for-mode :dynamic))

;; There is no Dynamic `default-tab`: `:rf.xray/selected-tab` is total —
;; `registry.cljs`'s reg-sub reads `(get db :selected-tab :epoch)` — so a
;; fallback here would be unreachable. `static/shell.cljs` keeps its OWN
;; public `default-tab` (`:machines`), which `registry.cljs` reads.

;; ---- helpers (pure, exported for tests) ---------------------------------

(defn event-id-of-event-bundle
  "Best-effort pluck of the event-id from an event-bundle's `:event` slot.
  The slot is the raw event vector ([:foo/bar …]); the first element
  is the event id. nil when the event-bundle is unrouted or the event slot
  is empty."
  [event-bundle]
  (let [ev (:event event-bundle)]
    (when (vector? ev)
      (first ev))))

(defn render-event-id-only
  "Render JUST the event-id keyword for the L2 row — one-line minimal
  rows. The full event vector (args + payload) and the other fields
  (datetime, sequence number, duration tier, source coordinates) live
  in the row's hover tooltip + the Epoch panel detail (which has plenty
  of room).

  - `event-id` is the first element of the event vector.
  - Renders in the mode `accent` colour so it pops out of the row.
  - When the event-bundle carries no event vector, falls back to a
    `<no event>` chip in the secondary text colour. (The L2 event
    list filters those event-bundles out via
    `event-bundle-has-event?`; the fallback is defence-in-depth.)"
  [event-vec]
  (if (vector? event-vec)
    [:span {:style {:color       (:accent tokens)
                    :font-weight 500}}
     (pr-str (first event-vec))]
    [:span {:style {:color      (:text-secondary tokens)
                    :font-style "italic"}}
     "<no event>"]))

(defn row-tooltip-text
  "Build the L2 row's hover tooltip. The
  minimal one-line row surfaces only `event-id + ⚠/🌐/🤖`; the
  other fields (full event vector, sequence number, frame, source
  coordinates, handler duration) appear in this tooltip + in the
  Epoch panel detail on row click.

  Pure data — JVM-runnable. nil-safe per event-bundle slot. Returns a
  newline-joined string suitable for an HTML `:title` attribute.

  Slot ordering (most useful first):
    1. Full event vector (untruncated)
    2. `#<dispatch-id>` (the sequence number)
    3. `frame: <id>`
    4. Source coordinate `<file>:<line>:<col>` (when `:rf.trace/call-site`
       rode the `:rf.event/dispatched` emit)
    5. `handler: <ms>ms` (when the event-bundle carried a `:handler` emit
       with `:elapsed-ms`)
    6. Trailing hint: `Click → open Event detail`"
  [event-bundle]
  (let [event-vec     (:event event-bundle)
        dispatch-id   (:dispatch-id event-bundle)
        frame-id      (:frame event-bundle)
        dispatched    (:dispatched event-bundle)
        call-site     (:rf.trace/call-site dispatched)
        coord-str     (when (map? call-site)
                        (let [{:keys [file line column]} call-site]
                          (when file
                            (cond-> file
                              line   (str ":" line)
                              column (str ":" column)))))
        handler       (:handler event-bundle)
        handler-ms    (or (:elapsed-ms handler)
                          (get-in handler [:tags :elapsed-ms]))
        ;; The filter-bypass cue. An errored event a filter would
        ;; hide is surfaced anyway (spec/018 §7 Error overrides); the tooltip
        ;; tells the operator WHY it is visible so a bypassed row never reads
        ;; as an escaped filter.
        bypassed?     (:rf.xray/filter-bypassed? event-bundle)
        lines (cond-> []
                (vector? event-vec) (conj (pr-str event-vec))
                (some? dispatch-id) (conj (str "#" dispatch-id))
                (some? frame-id)    (conj (str "frame: " frame-id))
                (some? coord-str)   (conj (str "source: " coord-str))
                (some? handler-ms)  (conj (str "handler: " handler-ms "ms"))
                bypassed?           (conj "⚠ shown because it errored — a filter would normally hide it")
                true                (conj "Click → open Event detail"))]
    (str/join "\n" lines)))

(defn event-bundle-has-event?
  "True iff `event-bundle` carries a real `:event` vector (`(first :event)`
  resolves to a non-nil event-id). False for the `:ungrouped` bucket
  produced by `re-frame.trace.projection/group-by-event` for registry-
  time emits / frame lifecycle outside a drain / REPL evals — those
  carry no event vector. The L2 event list filters this
  bucket out so the user never sees a `<no event>` placeholder row."
  [event-bundle]
  (some? (event-id-of-event-bundle event-bundle)))

(defn ungrouped-event-bundle?
  "True iff `event-bundle` is the `:ungrouped` bucket produced by
  `re-frame.trace.projection/group-by-event`. Used to give the
  bucket a distinct muted treatment in L2 when the
  opt-in (`:settings/show-ungrouped?`) is on."
  [event-bundle]
  (= :ungrouped (:dispatch-id event-bundle)))

(defn l2-event-bundle-visible?
  "Pure helper. Should `event-bundle` render as a row in the L2 event
  list? Always true for event-bundles carrying a real `:event` vector;
  for the `:ungrouped` bucket, only true when the user has opted
  in via Settings → General → Power user → 'Show :ungrouped pseudo-
  event-bundle events in L2'. The ribbon nav (`◀ ▶ ⏭`) and
  L2 walk both compose against this predicate so the visible row
  set, the boundary detection, and the focus walk all agree."
  [event-bundle show-ungrouped?]
  (or (event-bundle-has-event? event-bundle)
      (and show-ungrouped? (ungrouped-event-bundle? event-bundle))))

;; ONE shared column layout for the L2 event list, so the
;; column-header row (`l2-column-header`) and every data row (`event-row`)
;; reference the SAME constants and align column-for-column under the
;; Figma EventList (the `event-list` component in
;; `design-reference/xray_devtools_reference.cljs`). These defs make the
;; two surfaces literally share the same column structure, so
;; hand-copied numbers cannot drift out of alignment.
;;
;; Layout (left → right), matching the Figma EventList columns exactly
;; (there is no leading focus gutter — the mock doesn't carry one):
;;
;;   gap [event id flex-1] gap [source 52px] gap [time →right] gap [dur →right]
;;   (event-id leads, source follows, per the Figma-Make surface)
;;
;; Both surfaces use the SAME flex container gap + horizontal padding, and
;; a matching `1px solid transparent` border so the row's active-row border
;; (which paints 1px) never shifts the data 1px right of the header
;; (default content-box would otherwise offset every bordered row).
;;
;; Declared here — above the relative-time chip (which references the time
;; column width) and above `event-row` / `l2-column-header`, all later in
;; the file — so the symbols resolve at every use site (CLJS top-level
;; defs must precede use).
;;
;; The three trailing columns (`source`, `timestamp`,
;; `duration`) are USER-RESIZABLE via drag dividers between cells.
;; Per-column widths flow from the `:rf.xray/event-list-col-widths` sub
;; — `l2-column-header` subscribes once and threads the resolved map
;; through to every row; `event-row` reads its props rather than
;; subscribing per-row (one subscribe per L2 paint, not N). The default
;; widths live in `config/event-list-col-default-widths`.

(def ^:private l2-col-gap
  "Inter-column gap for both the header row and every data row. ONE
  value keeps the columns lined up across the two surfaces."
  "6px")

(def ^:private l2-row-h-padding
  "Horizontal padding for both the header row and every data row — the
  left value sets where the first column starts, the right where the time
  column ends. Shared so neither surface drifts."
  "6px")

;; ---- column-divider drag state -----------------------------------------
;;
;; Mirrors `resize-handle.cljs` §drag-state. Each divider's pointerdown
;; records the start-x + start-width snapshot, then attaches document-
;; level pointermove / pointerup / pointercancel listeners so a drag
;; faster than the per-element hit-test cadence keeps tracking the
;; pointer (the standard split-pane recipe — without document-level
;; capture a fast drag stalls). The `col-id` rides on the drag-state so
;; the dispatch path knows which column to write back into.

(defonce ^:private col-divider-drag-state
  (atom nil))

(defn col-divider-dragging?
  "Test seam — true iff a column-divider drag is in progress."
  []
  (some? @col-divider-drag-state))

(defn- col-divider-detach-listeners! []
  (when-let [{:keys [doc on-move on-up on-cancel prev-cursor]} @col-divider-drag-state]
    ;; The SAME document the drag attached to.
    (let [^js doc doc]
      (when (and doc (.-removeEventListener doc))
        (try (.removeEventListener doc "pointermove" on-move)
             (catch :default _ nil))
        (try (.removeEventListener doc "pointerup" on-up)
             (catch :default _ nil))
        (try (.removeEventListener doc "pointercancel" on-cancel)
             (catch :default _ nil)))
      (when (and doc (.-body doc))
        (set! (-> doc .-body .-style .-cursor)
              (or prev-cursor ""))))
    (reset! col-divider-drag-state nil)))

(defn- col-divider-on-move [^js e]
  ;; `dispatch-fn` (the captured frame-aware dispatcher) is
  ;; stashed in the drag-state at `col-divider-start-drag!` time; this
  ;; document-level move handler fires after render unwinds, so it reads
  ;; the closure back rather than dispatching to a `:rf/xray` literal.
  ;; The dispatch lands on the instance frame the divider was rendered
  ;; under. Falls back to `rf/dispatch` defensively (test-driven drags
  ;; that bypassed `start-drag!`).
  (when-let [{:keys [col-id start-x start-width dispatch-fn]} @col-divider-drag-state]
    ;; Every divider sits to the LEFT of the column named
    ;; by `col-id` (between `event id` and `source`, between `source`
    ;; and `timestamp`, between `timestamp` and `duration`); `event id`
    ;; is the row's SOLE `flex 1 1 auto` column, far to the left of
    ;; every divider, and it silently absorbs whatever width any
    ;; resizable column gives up or takes (the row's total width is
    ;; fixed). So GROWING `col-id` by `dx` steals `dx` from `event id`
    ;; — which moves the divider itself (sitting at `event id`'s
    ;; trailing edge, transitively, however many fixed columns sit
    ;; between them) LEFT by `dx` while the pointer moved RIGHT by
    ;; `dx`: a 2×dx divergence per drag-frame, and the classic
    ;; "handle recedes from the cursor" bug. Correct split-pane
    ;; semantics: dragging the divider TOWARD a column shrinks that
    ;; column (the boundary is encroaching on it) and grows whatever
    ;; is on the far side (ultimately `event id`, the shared elastic
    ;; pool) — i.e. SHRINK `col-id` by `dx` so the divider's own
    ;; position moves by exactly `+dx`, matching the pointer 1:1.
    (let [dx        (- (.-pageX e) start-x)
          new-width (- start-width dx)]
      ((or dispatch-fn rf/dispatch)
       [:rf.xray/set-event-list-col-width col-id new-width]))))

(defn- col-divider-on-up [^js _e]
  (col-divider-detach-listeners!))

(defn- col-divider-on-cancel [^js _e]
  (col-divider-detach-listeners!))

(defn col-divider-start-drag!
  "Begin a column-divider drag for `col-id` (`:source` /
  `:timestamp` / `:duration`). Records the snapshot + attaches the
  document-level move/up/cancel listeners. Exposed for the divider
  view's `:on-pointer-down` handler AND for the test suite, which
  drives the lifecycle without a real DOM.

  `dispatch-fn` is the frame-aware dispatcher captured by
  the surrounding boundary body — stashed in the drag-state so the
  document-level move handler (which fires after render unwinds) lands
  its width writes on the instance frame, not a `:rf/xray` literal.
  Defaults to `rf/dispatch` for the test-driven lifecycle."
  ([^js e col-id current-width]
   (col-divider-start-drag! e col-id current-width rf/dispatch))
  ([^js e col-id current-width dispatch-fn]
  (col-divider-detach-listeners!)
  ;; The divider's own document, not `js/document`: `ShellView` is also
  ;; the pop-out's body, and there `js/document` names the OPENER's
  ;; document, which the pop-out's pointer events never reach.
  ;; Kept in the state so the detach removes the
  ;; listeners from the same document.
  (let [^js doc     (resize-handle/pointer-document e)
        start-x     (.-pageX e)
        pointer-id  (.-pointerId e)
        prev-cursor (when (and doc (.-body doc))
                      (-> doc .-body .-style .-cursor))
        on-move     col-divider-on-move
        on-up       col-divider-on-up
        on-cancel   col-divider-on-cancel]
    (reset! col-divider-drag-state
            {:col-id      col-id
             :doc         doc
             :start-x     start-x
             :start-width (or current-width 0)
             :pointer-id  pointer-id
             :dispatch-fn dispatch-fn
             :on-move     on-move
             :on-up       on-up
             :on-cancel   on-cancel
             :prev-cursor prev-cursor})
    (when (and doc (.-body doc))
      (set! (-> doc .-body .-style .-cursor) "col-resize"))
    (when (and doc (.-addEventListener doc))
      (try (.addEventListener doc "pointermove" on-move)
           (catch :default _ nil))
      (try (.addEventListener doc "pointerup" on-up)
           (catch :default _ nil))
      (try (.addEventListener doc "pointercancel" on-cancel)
           (catch :default _ nil)))
    (try (.preventDefault e) (catch :default _ nil)))))

(defn col-divider-simulate-move!
  "Test-only: drive the document-level pointermove handler. No-op when
  no drag is in progress."
  [page-x]
  (when @col-divider-drag-state
    (col-divider-on-move #js {:pageX page-x})))

(defn col-divider-simulate-up!
  "Test-only: drive the document-level pointerup handler."
  []
  (when @col-divider-drag-state
    (col-divider-on-up nil)))

(defn col-divider-simulate-cancel!
  "Test-only: drive the document-level pointercancel handler."
  []
  (when @col-divider-drag-state
    (col-divider-on-cancel nil)))

(defn col-divider-handle-keydown!
  "Keyboard-navigable column-divider resize. Per spec/007-UX-IA.md
  §Resize affordance every drag handle MUST be operable without a
  pointer device. Mirrors the panel resize handle's bindings:

    ArrowRight        +<step>px (widen THIS column, `col-id`)
    ArrowLeft         -<step>px (narrow this column)
    Shift+ArrowRight  +<coarse> (10 × 3 = 30px)
    Shift+ArrowLeft   -<coarse>
    Enter / Space     reset this column to its default

  Deliberately NOT inverted the way `col-divider-on-move` inverts the
  pointer-drag delta — a keypress has no continuous
  cursor position to track (no divider-recedes-from-the-pointer failure
  mode is possible), so ArrowRight does the obvious, discoverable
  thing: it grows the column named by `col-id` directly.

  The clamp lives in the registry handler — we dispatch the desired
  width and let `:rf.xray/set-event-list-col-width` apply the per-
  column floor. Returns true iff the keypress was handled.

  `dispatch-fn` is the frame-aware dispatcher captured by
  the surrounding boundary body so the keyboard resize lands on the
  instance frame; defaults to `rf/dispatch` for the test lifecycle."
  ([^js e col-id current-width]
   (col-divider-handle-keydown! e col-id current-width rf/dispatch))
  ([^js e col-id current-width dispatch-fn]
   (let [key      (.-key e)
         shift?   (.-shiftKey e)
         step     (if shift?
                    (* config/event-list-col-keyboard-step-px
                       config/event-list-col-keyboard-coarse-multiplier)
                    config/event-list-col-keyboard-step-px)
         dispatch (fn [px]
                    (dispatch-fn [:rf.xray/set-event-list-col-width col-id px]))]
     (case key
       "ArrowRight"   (do (dispatch (+ current-width step)) true)
       "ArrowLeft"    (do (dispatch (- current-width step)) true)
       ("Enter" " ")  (do (dispatch-fn
                            [:rf.xray/reset-event-list-col-width col-id])
                          true)
       false))))

(defn- col-divider
  "Render a draggable divider sitting to the LEFT of the column with
  `col-id` (i.e. BETWEEN the previous column and `col-id`'s column, as
  the call sites below place it). The divider is
  a 6px-wide vertical strip carrying the `col-resize` cursor + a hover
  affordance defined in `theme/global_styles/motion-css` (the rule
  paints a 1px accent stripe on hover so authors can find the
  affordance without a hidden hit-test game; the cursor change is the
  always-visible signal).

  The divider participates in the row's flex layout as a
  zero-content cell with explicit width. Per the alignment contract
  the same divider widths apply to header + every row, so the flex
  layout stays consistent across surfaces.

  `:dispatch-fn` is the frame-aware dispatcher captured by
  the surrounding boundary body (the L2 event-list views) — threaded
  through the drag / keyboard / double-click affordances so every
  width write lands on the instance frame, not a `:rf/xray` literal."
  [{:keys [col-id col-px row-height dispatch-fn]}]
  (let [floor    (get config/event-list-col-min-widths col-id)
        dispatch-fn (or dispatch-fn rf/dispatch)
        col-label (name col-id)]
    [:div {:data-testid           (str "rf-xray-event-list-col-divider-" col-label)
           :data-rf-xray-col-id   col-label
           :role                  "separator"
           :aria-orientation      "vertical"
           :aria-label            (str "Resize " col-label " column")
           :aria-valuemin         floor
           :aria-valuemax         600
           :aria-valuenow         col-px
           :tab-index             0
           :title                 (str "Drag to resize " col-label
                                       " column · double-click to reset · "
                                       "arrow keys (Shift = coarse)")
           :on-pointer-down       (fn [^js e]
                                    (col-divider-start-drag! e col-id col-px dispatch-fn))
           :on-key-down           (fn [^js e]
                                    (when (col-divider-handle-keydown! e col-id col-px dispatch-fn)
                                      (try (.preventDefault e)
                                           (catch :default _ nil))))
           :on-double-click       (fn [^js _e]
                                    (dispatch-fn
                                      [:rf.xray/reset-event-list-col-width col-id]))
           :style {:flex          "0 0 auto"
                   :width         "5px"
                   :align-self    "stretch"
                   :height        (or row-height "100%")
                   :cursor        "col-resize"
                   :background    "transparent"
                   ;; Disable native gestures during drag (text-select
                   ;; on mouse, page-pan on touch).
                   :touch-action  "none"
                   :user-select   "none"}}]))

(defn- ->px
  "Coerce a plain number to a `\"<n>px\"` string for inline styles.
  Accepts an already-formatted string verbatim (defence-in-depth on a
  default that arrives as a string)."
  [v]
  (cond
    (string? v) v
    (number? v) (str v "px")
    :else       nil))

;; ---- Relative-time helper + the timestamp column --------------------------
;;
;; `format-relative-time` labels how long before an anchor an event-bundle
;; was dispatched ("5s", "2m", "1h", "3d"). The L2 `timestamp` column
;; itself ([[relative-time-chip]]) renders the ABSOLUTE wall-clock time
;; (`HH:MM:SS.mmm`) per the authoritative reference event-list — see its
;; docstring.
;;
;; Bucketing keeps a relative label silent-by-default — an old row that
;; reads "5m" does not jitter second-by-second because the same
;; minute-bucket maps back to "5m" regardless of the exact second inside
;; the bucket. Buckets:
;;
;;   diff <   1s            → "now"
;;   diff < 60s              → "Ns"
;;   diff < 60min            → "Nm"
;;   diff < 24h              → "Nh"
;;   diff ≥ 24h              → "Nd"
;;
;; Anchor: the "now" a relative label computes against is the
;; dispatched-time of the MOST RECENT event-bundle in the spine, not a
;; wall-clock tick. A per-second `setInterval` anchor would re-render
;; and flicker the L2 list constantly — relative time is meaningful
;; BETWEEN events, not between seconds. Each new event re-establishes
;; "now"; between events the list stays frozen. Anchor flips arrive
;; on the existing reactive path (a new event-bundle appears in
;; `:rf.xray/event-bundles`) so no timer / no internal trace pollution.
;;
;; That anchor is the `:rf.xray/relative-time-now-ms` sub in
;; `registry.cljs`. The L2 list does not read it: the `timestamp` column
;; renders absolute time, which needs no anchor.

(defn format-relative-time
  "Pure helper. Given two epoch-ms values (current time + the event-bundle's
  dispatched-time), returns the chip display string per the bucket
  contract in the section comment above. Nil-safe on `then-ms` (returns
  the empty string so the caller can decide whether to render anything).

  Pure-data, JVM-runnable so callers can spec-test it without a CLJS
  runtime."
  [now-ms then-ms]
  (if (or (nil? then-ms) (nil? now-ms))
    ""
    (let [diff-ms (max 0 (- now-ms then-ms))
          s      (quot diff-ms 1000)]
      (cond
        (< diff-ms 1000)  "now"
        (< s 60)          (str s "s")
        (< s 3600)        (str (quot s 60) "m")
        (< s 86400)       (str (quot s 3600) "h")
        :else             (str (quot s 86400) "d")))))

(defn- pad2
  "Left-pad an integer to two digits with a leading zero. Pure-data;
  JVM-portable."
  [n]
  (if (< n 10) (str "0" n) (str n)))

(defn- pad3
  "Left-pad an integer to three digits with leading zeros (for the
  millisecond field). Pure-data; JVM-portable."
  [n]
  (cond
    (< n 10)  (str "00" n)
    (< n 100) (str "0" n)
    :else     (str n)))

(defn format-clock-time
  "CLJS-side helper. Given an epoch-ms (the event-bundle's
  dispatched `:time`), returns the ABSOLUTE wall-clock string the L2
  `timestamp` column renders — `HH:MM:SS.mmm` (e.g. `12:30:05.123`) per
  the authoritative reference event-list (`tools/xray/design-reference/
  xray_devtools_reference.cljs`, which renders absolute timestamps like
  `12:30:05.123`, NOT relative `1s`/`now` chips).

  Uses the LOCAL-time components (`getHours` / `getMinutes` /
  `getSeconds` / `getMilliseconds`) so the column reads in the operator's
  timezone. Returns the empty string when `then-ms` is nil or `js/Date`
  is unavailable so the caller can decide whether to render anything."
  [then-ms]
  (if (or (nil? then-ms) (not (exists? js/Date)))
    ""
    (let [d (js/Date. then-ms)]
      (str (pad2 (.getHours d)) ":"
           (pad2 (.getMinutes d)) ":"
           (pad2 (.getSeconds d)) "."
           (pad3 (.getMilliseconds d))))))

(defn format-absolute-time
  "CLJS-side helper. Given an epoch-ms (the event-bundle's dispatched
  `:time`), returns an absolute-time tooltip string for the chip's
  `:title` attribute. Used as the power-user reveal that complements
  the `HH:MM:SS.mmm` clock column — clicking the row still opens the
  Epoch panel, but a hover shows the full ISO walltime + epoch-ms.

  Returns the empty string when `then-ms` is nil so the caller can
  decide whether to attach the tooltip."
  [then-ms]
  (if (or (nil? then-ms) (not (exists? js/Date)))
    ""
    (let [d   (js/Date. then-ms)
          iso (.toISOString d)
          loc (.toLocaleTimeString d)]
      (str loc " · " iso " (epoch-ms " then-ms ")"))))

(defn event-bundle-dispatched-time-ms
  "Pluck the event-bundle's dispatched-time from `:dispatched :time` (every
  trace event carries `:time (rf.interop/now-ms)` per `re-frame.trace.cljc
  build-event`). Returns nil when the event-bundle has no `:dispatched`
  slot or the slot's `:time` is not a number — defence-in-depth for
  event-bundles synthesised by tests that omit the field."
  [event-bundle]
  (let [t (get-in event-bundle [:dispatched :time])]
    (when (number? t) t)))

(defn relative-time-chip
  "Render the L2 row's right-aligned `timestamp` column. This renders
  the ABSOLUTE wall-clock time (`HH:MM:SS.mmm`, e.g.
  `12:30:05.123`) per the authoritative reference event-list — NOT a
  relative `1s`/`now` chip, so it takes no `now` anchor. The chip's
  `:title` carries the full ISO walltime + epoch-ms as the power-user
  reveal.

  `col-px` is the user-resizable `timestamp` column width
  (pixels). The header + every row read from the same
  `:rf.xray/event-list-col-widths` sub so the two surfaces never drift
  out of column alignment.

  Renders nothing when the event-bundle carries no dispatched-time stamp."
  [event-bundle col-px]
  (when-let [then-ms (event-bundle-dispatched-time-ms event-bundle)]
    (let [label   (format-clock-time then-ms)
          tooltip (format-absolute-time then-ms)]
      [:span {:data-testid     "rf-xray-row-time-chip"
              :data-then-ms    (str then-ms)
              :title           tooltip
              ;; The trailing time column.
              ;; Shares the header `timestamp` column's right-aligned
              ;; width with the same `col-px` source so the value
              ;; right-aligns under the header label. Spacing from the
              ;; preceding column comes from the row's shared flex `gap`.
              :style {:color         (:text-tertiary tokens)
                      :flex-shrink   0
                      :font-family   mono-stack
                      :font-size     (:caption type-scale)
                      :width         (->px col-px)
                      :text-align    "right"
                      :white-space   "nowrap"}}
       label])))

(defn duration-cell
  "Render the L2 row's trailing `duration` column — the
  Figma EventList's fourth column. Right-aligned handler wall-time
  (`1.2 ms`), sourced from the event-bundle's `:handler` trace event via
  `l2-timeline/event-bundle-duration-label`. Shares the header `duration`
  column's right-aligned width with the same `col-px` source so the
  value right-aligns under the header label; spacing from the
  preceding timestamp column comes from the row's shared flex `gap`.

  `col-px` is the user-resizable `duration` column width
  (pixels). Header + every row read from the same
  `:rf.xray/event-list-col-widths` sub so the two surfaces never drift
  out of column alignment.

  ALWAYS renders the cell span (occupying its column width) so the
  columns stay aligned row-to-row; when the event-bundle carries no measured
  handler duration the cell is simply blank rather than collapsing the
  column."
  [event-bundle col-px]
  (let [label (l2-timeline/event-bundle-duration-label event-bundle)]
    [:span {:data-testid   "rf-xray-row-duration"
            :data-duration (str (l2-timeline/event-bundle-duration-ms event-bundle))
            :style {:color         (:text-tertiary tokens)
                    :flex-shrink   0
                    :font-family   mono-stack
                    :font-size     (:caption type-scale)
                    :width         (->px col-px)
                    :text-align    "right"
                    :white-space   "nowrap"}}
     label]))

;; ---- L1 ribbon -----------------------------------------------------------

(defn- ribbon-nav-cluster
  "Nav cluster — thin chevrons `‹ › »` per spec/018 §3 + the Figma
  EventsRibbon (the `events-ribbon` component in
  `design-reference/xray_devtools_reference.cljs`). Buttons
  dispatch `:rf.xray/focus-event-prev` / `-next` /
  `:rf.xray/follow-head`.

  `at-head?` (focus = most recent event), `at-tail?` (focus = first
  event in buffer) and `live?` (spine is `:live` + unpaused, already
  auto-tracking head) come from the spine sub so the buttons can
  disable themselves at the boundary:

  - `‹` (back / prev, lucide `ChevronLeft`) — disabled when `at-tail?`
    (no older event to step to).
  - `›` (forward / next, lucide `ChevronRight`) — disabled when
    `at-head?` (already at the most recent event).
  - `»` (live / fast-forward, lucide `ChevronsRight`) — disabled when
    `at-head? AND live?`: already tracking head live, so the
    snap is a true no-op. When at head but PAUSED (frozen inspection)
    `»` stays enabled — pressing it resumes LIVE, which is not a no-op.

  ## Blue-filled chevron buttons

  Per the authoritative reference chrome-ribbon
  (`tools/xray/design-reference/xray_devtools_reference.cljs`) the nav
  cluster is three FILLED `:accent` buttons (blue bg, white icon,
  `p-1 rounded`, hover lifts opacity 0.9 → 1) carrying the
  ChevronLeft/Right/ChevronsRight glyphs — NOT borderless icon-buttons,
  NOT bordered unicode triangles. Inline SVG is not idiomatic in this
  pure-hiccup view, so the buttons keep glyphs but swap the chunky
  filled triangles (`◀ ▶ ⏭`) for the angle-quotation chevrons
  (`‹ › »`, U+2039 / U+203A / U+00BB) which read as the reference's thin
  strokes.

  ## Disabled appearance

  A disabled button must READ as inert, not merely block clicks. With
  the blue-filled treatment the inert signal is a strong opacity drop
  (the filled blue fades) + `cursor: not-allowed`. The native
  `:disabled` attribute (plus the dropped `:on-click`)
  blocks interaction; the inline style + `aria-disabled` carry the
  visual + a11y signal.

  `:dispatch-fn` is the frame-aware dispatcher captured by
  the [[ribbon]] boundary's body — the nav `on-click` handlers fire after
  render unwinds, so they dispatch through the captured closure to land
  on the surrounding instance frame, not a `:rf/xray` literal."
  [{:keys [at-head? at-tail? live? dispatch-fn]}]
  (let [head-disabled? (boolean (and at-head? live?))
        ;; Filled nav buttons (Figma-Make chrome-
        ;; ribbon): blue `active-bg` fill, white `active-text` icon, 4px
        ;; radius, hover opacity lift (the `:hover` rule lives in
        ;; `theme/global-styles/motion-css`).
        btn-style {:background      (:active-bg tokens)
                   :border          "none"
                   :color           (:active-text tokens)
                   :cursor          "pointer"
                   :opacity         1
                   :padding         "0"
                   ;; Figma authority specifies 21px square nav
                   ;; buttons (chevron-left / chevron-right / chevrons-right
                   ;; in the chrome ribbon's left cluster).
                   :width           "21px"
                   :height          "21px"
                   :display         "inline-flex"
                   :align-items     "center"
                   :justify-content "center"
                   :border-radius   "4px"
                   :font-family     sans-stack
                   :font-size       "15px"
                   :line-height     "1"}
        ;; Filled inert state: the blue fill fades + the
        ;; `not-allowed` cursor carries the signal. The native
        ;; `:disabled` attribute already blocks clicks.
        disabled-style {:opacity 0.4
                        :cursor  "not-allowed"}]
    [:div {:data-testid "rf-xray-ribbon-nav"
           :style {:display "flex" :align-items "center" :gap "2px"}}
     [:button {:data-testid   "rf-xray-nav-prev"
               :on-click      (when-not at-tail?
                                #(dispatch-fn [:rf.xray/focus-event-prev]))
               :disabled      (boolean at-tail?)
               :aria-disabled (boolean at-tail?)
               :title         "Step to previous event (j)"
               :style         (merge btn-style (when at-tail? disabled-style))}
      [:span {:aria-hidden "true"} "‹"]]
     [:button {:data-testid   "rf-xray-nav-next"
               :on-click      (when-not at-head?
                                #(dispatch-fn [:rf.xray/focus-event-next]))
               :disabled      (boolean at-head?)
               :aria-disabled (boolean at-head?)
               :title         "Step to next event (k)"
               :style         (merge btn-style (when at-head? disabled-style))}
      [:span {:aria-hidden "true"} "›"]]
     [:button {:data-testid   "rf-xray-nav-head"
               :on-click      (when-not head-disabled?
                                #(dispatch-fn [:rf.xray/follow-head]))
               :disabled      head-disabled?
               :aria-disabled head-disabled?
               :title         "Fast-forward to latest (G)"
               :style         (merge btn-style (when head-disabled? disabled-style))}
      [:span {:aria-hidden "true"} "»"]]]))

;; The L1 frame-switcher slot lives in `frame_switcher.cljs`
;; — the ribbon mounts `[frame-switcher/frame-switcher-view]` and reaches
;; the picker's contract surface through `:rf.xray/current-frame` /
;; `:rf.xray/available-frames` / `:rf.xray/select-frame`. Cmd-K's
;; `:palette/select-frame` verb dispatches through the same canonical
;; event so the ribbon picker + the palette + any future frame-aware
;; feature flows through one source of truth.

(defn- ribbon-filter-pills
  "Filter pills cluster per spec/018 §3 + §7 Ribbon pills. Thin
  delegate to `filters.pills/pills-view` — the proper pill UI lives in
  the filters ns, which also owns the edit popup mount. Mounted here
  inside the ribbon's boundary so reads still resolve through
  React context to `:rf/xray`.

  The add-pill affordance opens the rich edit popup. Per spec/018 §7
  the popup pre-populates from existing pills (edit) or from right-click
  event-row context (add).

  `dispatch-fn` is the frame-aware dispatcher captured by
  the [[events-ribbon]] boundary's body so each pill's edit / remove
  click lands on the surrounding instance frame, not a `{:frame
  :rf/xray}` literal.

  `pills-view` is a plain fn answering hiccup, so it is
  CALLED rather than headed: a plain function in head position is a loud
  error under Fresco, and this delegate renders inside a boundary."
  [dispatch-fn {:keys [filters]}]
  (filter-pills/pills-view dispatch-fn {:filters filters}))

(defn- ribbon-redacted-indicator
  "REDACTED indicator — renders next to the mode pill and carries the
  redacted-counter assertion surface.
  Only renders when the counter is positive."
  [redacted-count]
  (when (pos? redacted-count)
    [:span {:data-testid "rf-xray-redacted-indicator"
            :title       (str "Spec 009 §Privacy: " redacted-count
                              " " (common/pluralize redacted-count "sensitive trace event")
                              " suppressed by default. Set "
                              ":rf.xray/egress-profile :rf.egress/local-raw "
                              "via (xray-config/configure! ...) to "
                              "reveal them on this trusted-local machine.")
            :style       {:color       (:magenta tokens)
                          :font-weight 600
                          :font-size   (:caption type-scale)}}
     ;; The leading `●` glyph is decorative (the count
     ;; + "REDACTED" word carry the meaning). `aria-hidden` on the
     ;; glyph suppresses the unicode-name announcement ("black
     ;; circle") while keeping the text + title accessible.
     [:span {:aria-hidden "true"} "● "]
     (str "REDACTED " redacted-count)]))

(defn theme-toggle-tree
  "The theme toggle's whole hiccup, as a pure function of the frame-bound
  `dispatch` and the resolved `:theme` setting.

  SPLIT OUT OF [[ribbon-theme-toggle]] because a boundary's body may only
  run inside a React render window, so `(ribbon-theme-toggle)` is not a
  callable that answers hiccup. This is `defview`'s OWN documented
  extract-a-helper spelling, not an invention."
  [dispatch theme]
  (let [dark? (= theme :dark)
        next  (if dark? :light :dark)]
    [:button {:data-testid "rf-xray-theme-toggle"
              :title       (if dark? "Switch to light theme" "Switch to dark theme")
              :aria-label  (if dark? "Switch to light theme" "Switch to dark theme")
              ;; Dispatch through the frame-bound
              ;; dispatcher the boundary captured, so the theme write lands
              ;; on the surrounding instance frame, not a `:rf/xray`
              ;; literal.
              :on-click    #(dispatch [:rf.xray/settings-update :theme nil next])
              :style       {:background      "transparent"
                            :border          "none"
                            :border-radius   "4px"
                            :color           (:chrome-ribbon-text-muted tokens)
                            :cursor          "pointer"
                            :font-size       "14px"
                            :line-height     "1"
                            :display         "inline-flex"
                            :align-items     "center"
                            :justify-content "center"
                            :width           "22px"
                            :height          "22px"
                            :padding         "0"}}
     [:span {:aria-hidden "true"} (if dark? "☀" "☾")]]))

(rf.fresco/defview ribbon-theme-toggle
  "Theme toggle (Figma-Make surface). A sun/moon icon-button
  on the chrome ribbon's right cluster that flips light ⇄ dark.

  ## Integrates the EXISTING theme mechanism (no parallel state)

  Xray already owns a `:theme` setting (`config.cljc`, default `:light`)
  read via `[:rf.xray/setting :theme nil]` and written via
  `[:rf.xray/settings-update :theme nil <kw>]`, whose handler calls
  `settings/effects/apply-theme!` to toggle the `rf-xray-theme-light` /
  `rf-xray-theme-dark` class on the shell + `<html>` root. This button
  dispatches through that SAME event — it does NOT toggle a bare `dark`
  class on `document.documentElement` (the Figma stub's approach) nor
  introduce a second theme atom. The settings popup's theme radio and
  this toggle therefore stay in lockstep.

  The glyph follows the convention NEXT action: in DARK mode it shows
  the sun `☀` (click → go light); in LIGHT mode the moon `☾` (click →
  go dark). Muted `chrome-ribbon-text-muted` ink to sit quietly beside
  the `⚙`/`✕` icons on the dark band.

  ## A FRESCO BOUNDARY, not an `rf/reg-view`

  The `:theme` setting lives in Xray's `:rf/xray` frame (the
  `:rf.xray/setting` sub + `:rf.xray/settings-update` event are
  registered against `:rf/xray` via `registry/register-xray-handlers!`).
  A plain `defn` rendered inside the shell's `:rf/xray` frame-provider
  would not pick up the surrounding frame (Spec 000 §Plain Reagent fns
  / Spec 006 §Plain-fn footgun) — so the read here would RAISE
  `:rf.error/no-frame-context` rather than route anywhere.

  The READ is `rf.fresco/sub`, a plain call the shipped collector records
  an edge for — no deref, no reaction owned by the installed adapter, and
  a re-wire that NOTIFIES when the substrate disposes the underlying
  derived value. A first-paint smoke test cannot see that coupling.

  The DISPATCHER is `(:dispatch (rf/capture-frame))` — core's own door,
  which answers the boundary's DECLARED frame inside a body.

  It is its OWN boundary rather than folded into [[ribbon]] because the
  two read different things at different rates: hoisting the `:theme`
  read into the ribbon would repaint the whole L1 chrome — nav cluster,
  frame picker and filter add-button included — on every theme flip.
  Boundary count tracks reads.

  The argument is the ordinary one-props-map vector every `defview`
  takes. [[ribbon]] mounts it with none, so it is destructured away."
  [_props]
  (theme-toggle-tree (:dispatch (rf/capture-frame))
                     (rf.fresco/sub [:rf.xray/setting :theme nil])))

(defn- ribbon-right-icons
  "Right-icons cluster — `⛶` pop-out · `⚙` settings · `✕` close. Per
  spec/018 §3 Right-icon behaviour + spec/011-Launch-Modes.md §Pop-out:
  the pop-out (`⛶`) slot carries a VISIBLE button — the canonical chrome
  launch affordance for the pop-out window (a visible top-bar button,
  not a right-click-only path). It dispatches
  `:rf.xray/popout-shell`, which fires the DOM-side
  `:rf.xray.fx/popout-shell` effect (mount/install-fx!) → `mount/popout!`.
  The programmatic `(xray/popout!)` API remains the secondary path.

  Settings opens the Settings popup modal via
  `:rf.xray/settings-open`; close dispatches `:rf.xray/close-shell`
  (handled by mount.cljs in production).

  ## Lucide-style icon buttons

  Per the Figma ChromeRibbon (the `chrome-ribbon` component in
  `design-reference/xray_devtools_reference.cljs`) the settings + close affordances are clean
  lucide `Settings` / `X` icons (`w-3.5 h-3.5`, `text-muted`) inside
  `p-1 rounded hover:bg` square hit-areas — NOT bare unicode glyphs in
  bordered/padded text-buttons. Inline SVG is not idiomatic in this
  pure-hiccup view, so the buttons keep the unicode glyphs but
  size/weight-match the lucide icons: muted `:text-tertiary` ink, a
  square `p-1` rounded hit-area, and a testid-keyed `hover:bg` fill
  (the `:hover` lift lives in `theme/global-styles/motion-css` since
  inline styles can't carry a pseudo-class). The `✕` uses the U+2715
  multiplication X glyph (thinner than the dialog-cross) to read like
  lucide's `X`.

  `:dispatch-fn` is the frame-aware dispatcher captured by
  the [[ribbon]] boundary's body so the settings / close clicks land on
  the surrounding instance frame, not a `:rf/xray` literal."
  [{:keys [dispatch-fn]}]
  (let [icon-style {:background      "transparent"
                    :border          "none"
                    :border-radius   "4px"
                    ;; Muted-white ink on the dark chrome band
                    ;; (Figma-Make surface) so the settings/close glyphs
                    ;; read against the near-black ribbon in both themes.
                    :color           (:chrome-ribbon-text-muted tokens)
                    :cursor          "pointer"
                    :font-size       "14px"
                    :line-height     "1"
                    :display         "inline-flex"
                    :align-items     "center"
                    :justify-content "center"
                    :width           "22px"
                    :height          "22px"
                    :padding         "0"}]
    [:div {:data-testid "rf-xray-ribbon-icons"
           :style {:display "flex" :align-items "center" :gap "4px"}}
     ;; VISIBLE pop-out (`⛶`) button, the canonical chrome
     ;; launch affordance for the second-window mode (spec/011 §Pop-out).
     ;; Dispatches through the captured frame-aware dispatcher so the
     ;; event lands on the surrounding instance frame, then the event/fx
     ;; bridge lowers it to `mount/popout!`.
     [:button {:data-testid "rf-xray-icon-popout"
               :title       "Pop out to a second window"
               :aria-label  "Pop out Xray to a second window"
               :on-click    #(dispatch-fn [:rf.xray/popout-shell])
               :style       icon-style}
      [:span {:aria-hidden "true"} "⛶"]]
     [:button {:data-testid "rf-xray-icon-settings"
               :title       "Settings (,)"
               :aria-label  "Open Xray settings"
               :on-click    #(dispatch-fn [:rf.xray/settings-open])
               :style       icon-style}
      [:span {:aria-hidden "true"} "⚙"]]
     [:button {:data-testid "rf-xray-icon-close"
               :title       "Close (Ctrl+Shift+C)"
               :aria-label  "Close Xray"
               :on-click    #(dispatch-fn [:rf.xray/close-shell])
               :style       icon-style}
      [:span {:aria-hidden "true"} "✕"]]]))

(defn spine-focusable-bundles
  "THE RAW FOCUSABLE SPINE DOMAIN — the one vector every head-aware
  selector in this ns walks, and the reason it has a name.

  `spine-event-bundles` is raw `:rf.xray/event-bundles`, narrowed to the
  rows the spine may actually focus: `spine/focusable-event-bundles`
  under the same `show-ungrouped?` opt-in, then scoped to `frame-scope`
  when one is stored. That is the walk `spine/compose-focus` derives
  `:head?` from and the walk `spine/focus-step-reducer` steps over, so a
  boundary or a count taken here agrees BY CONSTRUCTION with what `j` /
  `k` and the `»` snap actually do.

  `frame-scope` IS THE STORED `[:focus :frame]` RESTRICTION — what the
  frame picker wrote — and never the `:frame` off the COMPOSED
  `:rf.xray/focus`. The two read alike whenever a
  restriction is stored, which makes the wrong one easy to take: with
  NOTHING stored the spine walks every frame, while `compose-focus`
  still resolves its `:frame` to the current ROW's frame, so passing
  that here would narrow this domain to one frame and both selectors
  built on it would report a boundary and a count the spine does not
  have. nil
  means UNSCOPED — the walk spans frames — and that is a real state,
  not a missing value to be filled in from the row.

  NEVER hand this `:rf.xray/filtered-event-bundles`. That vector has the
  view-scope frame, the ribbon's IN/OUT pills and the mutes already
  applied (`filters.cljs`), so a filter that hides every row empties it
  while the spine is unmoved — and both selectors reading it would report
  a boundary the spine does not have. It has a name so the next reader has
  to choose the wrong vector on purpose.

  Pure data → data."
  [spine-event-bundles show-ungrouped? frame-scope]
  (cond->> (spine/focusable-event-bundles spine-event-bundles show-ungrouped?)
    frame-scope (filterv #(= frame-scope (:frame %)))))

(defn nav-boundary-state
  "Pure helper — compute the `{:at-head? :at-tail? :live?}`
  state the nav cluster consumes, from the already-resolved sub values.
  Extracted so the chrome ribbon (which hosts the nav cluster per the
  authority reference) and any test can derive the boundary state without
  re-subscribing.

  ## TWO FRAMES ARRIVE AND THEY ARE NOT THE SAME FRAME

  - `focus` — `:rf.xray/focus`, the COMPOSED map (`:dispatch-id` +
    `:mode` + `:paused?`, and the `:frame` the spine RESOLVED the pin
    in). Its `[:frame :dispatch-id]` pair is the COORDINATE of the row
    focus is on — the identity half, and the half the UI displays.
  - `frame-scope` — the STORED `[:focus :frame]` restriction, read
    through `:rf.xray/focus-slot`. The DOMAIN half: which rows the walk
    may visit at all. nil is a real value meaning UNSCOPED.

  `compose-focus` fills its `:frame` in from the current ROW when
  nothing is stored, so the two agree whenever a restriction exists and
  diverge exactly when one does not. Taking the domain off the composed
  map would therefore look right, pass a scoped test, and be wrong in
  the one case it can be wrong in: with no picker restriction
  `spine/focus-step-reducer` walks EVERY frame, while the boundary would
  see a single frame's rows, call the focus at-head AND at-tail, and grey
  out a `‹` whose event moves focus to the previous frame's row. Scoping
  the reducer to the resolved frame instead is no repair — that narrows
  navigation to hide the disagreement.

  - `spine-event-bundles` — RAW `:rf.xray/event-bundles`, read through
    [[spine-focusable-bundles]]. NOT the filtered vector L2 renders:
    `‹` and `›` gate `:rf.xray/focus-event-prev` / `-next`, which walk
    the raw projection, so a boundary taken off the rendered rows would
    disable BOTH chevrons whenever a pill or a mute hid every row —
    while `j` / `k`, bound to those same two events, go on stepping.
  - `show-ungrouped?` — `:rf.xray/show-ungrouped?` (the `:ungrouped`
    bucket is a step target only under the opt-in).

  `at-head?` / `at-tail?` are `spine/step-noop?` — the reducer's OWN
  edge predicate, asked here rather than restated, which is what makes
  the button's `:disabled` and the event's no-op one decision. It is
  frame-strict on the coordinate, so a same-id row in another frame is
  a reachable step rather than a false edge.

  Pure-data → map; JVM-runnable so the boundary logic is testable
  without a CLJS runtime."
  [{:keys [focus frame-scope spine-event-bundles show-ungrouped?]}]
  (let [focusable     (spine-focusable-bundles spine-event-bundles
                                               show-ungrouped?
                                               frame-scope)
        current-id    (:dispatch-id focus)
        current-frame (:frame focus)]
    {:at-head? (spine/step-noop? focusable current-frame current-id +1)
     :at-tail? (spine/step-noop? focusable current-frame current-id -1)
     :live?    (and (= :live (:mode focus))
                    (not (:paused? focus)))}))

(defn ribbon-tree
  "The L1 chrome ribbon's WHOLE hiccup, as a pure function of the
  frame-bound `dispatch`, the seven values [[ribbon]] reads, and its
  three already-composed boundary children.

  SPLIT OUT OF [[ribbon]] because a boundary's body may only run inside
  a React render window, so `(ribbon nil)` is not a callable that answers
  hiccup.

  IT TAKES THE RAW READ VALUES, not derived ones — `nav-boundary-state`
  and the `no-filters?` gate are computed HERE. That keeps the node lane's
  door (`test-helpers.dynamic-shell-tree`) a reproduction of the READS
  alone, with no second copy of the derivation to drift.

  TWO FOCUS READS ARRIVE, AND BOTH ARE NEEDED. `focus` is
  the COMPOSED `:rf.xray/focus` — the resolved coordinate the nav
  cluster displays and steps from. `focus-slot` is the STORED
  `:rf.xray/focus-slot`, and the only thing taken from it is `:frame`:
  the picker's restriction, which is what bounds the spine's walk. The
  composer fills its own `:frame` in from the current row when nothing
  is stored, so the stored slot is the ONLY place that distinction
  survives — see [[nav-boundary-state]] for what conflating them costs.

  PURE HEADS: `ribbon-nav-cluster`, `ribbon-redacted-indicator`,
  `ribbon-right-icons`, `filter-pills/chrome-add-filter-button` and
  `spine-filters/ribbon-mute-indicator` are all plain fns answering
  hiccup, so they are CALLED rather than headed — Fresco grades a plain
  function in head position a loud error.

  THREE BOUNDARY CHILDREN — `frame-switcher*`, `mode-pill*` and
  `theme-toggle*` — ARRIVE ALREADY COMPOSED rather than as heads this fn
  writes, because a boundary head cannot be walked by a hiccup walker:
  its body only runs inside a React render window. [[ribbon]] passes the
  three BOUNDARY-headed vectors and `test-helpers.dynamic-shell-tree`
  passes each one's already-expanded plain hiccup, so a node-lane row
  that walks this ribbon walks the real thing. Same shape as
  [[dynamic-chrome-tree]]'s six layers, and for the same reason.

  THERE IS NO `as-child` PARAMETER. `frame-switcher/frame-switcher-view`
  and `mode-pill/mode-pill` are boundaries, so they are headed directly
  and no Reagent-island seam is needed to reach them.
  [[event-list-tree]]'s L4 seam stands for its own recorded reason."
  [dispatch
   {:keys [redacted-count muted-count focus focus-slot spine-event-bundles
           show-ungrouped? filters]}
   frame-switcher*
   mode-pill*
   theme-toggle*]
  (let [no-filters? (zero? (+ (count (:in filters)) (count (:out filters))))
        {:keys [at-head? at-tail? live?]}
        (nav-boundary-state {:focus               focus
                             :frame-scope         (:frame focus-slot)
                             :spine-event-bundles spine-event-bundles
                             :show-ungrouped?     show-ungrouped?})]
    [:div {:data-testid "rf-xray-ribbon"
           :style {:display          "flex"
                   :align-items      "center"
                   :justify-content  "space-between"
                   :gap              "12px"
                   :height           (:top-strip-height layout)
                   :padding          "0 12px"
                   ;; DARK chrome band (Figma-Make surface).
                   ;; The chrome ribbon paints the dedicated dark-chrome
                   ;; token in BOTH themes; chrome text reads the white
                   ;; `chrome-ribbon-text` token so it stays legible on the
                   ;; near-black band.
                   :background       (:chrome-ribbon-bg tokens)
                   :color            (:chrome-ribbon-text tokens)
                   :border-bottom    (str "1px solid " (:border-subtle tokens))
                   ;; No `:accent` left-edge stripe: the Figma authority
                   ;; chrome ribbon has NO left-edge accent — Dynamic mode
                   ;; signals through the mode-pill alone.
                   :font-family      sans-stack
                   :font-size        (:body type-scale)}}
     ;; LEFT cluster — `Event History` label · nav · add(+), per the
     ;; authority reference chrome-ribbon. There is no `❖ Xray` wordmark
     ;; and no focus button or focus-chip; the cluster leads with the
     ;; label.
     ;; `:flex-wrap "nowrap"` on the LEFT cluster. Under `wrap` the [+]
     ;; add-pill (the cluster's last child) would wrap onto a second line
     ;; at ~420px viewports, overflow the fixed 34px chrome-ribbon height
     ;; and be vertically occluded by the events-ribbon below —
     ;; click-blocked. The Figma authority chrome-
     ;; ribbon does NOT wrap (`design-reference/xray_devtools_reference
     ;; .cljs` `chrome-ribbon` uses plain non-wrapping flex). Keeping
     ;; nowrap lets the cluster overflow horizontally instead — the [+]
     ;; stays inline at y=ribbon-centre and remains hit-testable until it
     ;; runs past the viewport edge.
     [:div {:data-testid "rf-xray-ribbon-selectors"
            :style {:display "flex" :align-items "center" :gap "13px"
                    :flex-wrap "nowrap"
                    ;; The cluster is allowed to shrink but its children
                    ;; carry `white-space: nowrap` so they stay legible;
                    ;; horizontal overflow goes off-screen rather than
                    ;; wrapping into the row below.
                    :min-width "0"}}
      ;; `Event History` label leads the left cluster (Figma-Make
      ;; surface). White ink on the dark chrome band
      ;; (`chrome-ribbon-text`).
      [:span {:data-testid "rf-xray-ribbon-events-label"
              :style {:color       (:chrome-ribbon-text tokens)
                      :font-family sans-stack
                      :font-weight 500
                      :white-space "nowrap"}}
       "Event History"]
      ;; Blue-filled nav cluster, on bar-1.
      ;; Thread the captured frame-aware dispatcher so the
      ;; nav on-clicks land on the surrounding instance frame.
      (ribbon-nav-cluster {:at-head? at-head? :at-tail? at-tail? :live? live?
                           :dispatch-fn dispatch})
      ;; `+ filter` text button (Figma-Make chrome-ribbon): a single
      ;; outlined text button that opens the edit popup
      ;; (`filter-pills/chrome-add-filter-button` delegates to the
      ;; canonical `:rf.xray/open-edit-popup` flow). Muted-outline on the
      ;; dark band so it reads as a secondary chrome affordance.
      ;;
      ;; Wrapped in a horizontal collapse track so the
      ;; button retracts to zero width (with the same 250ms / motion-
      ;; scale cadence as the events-ribbon vertical collapse) once the
      ;; events-ribbon owns the add affordance via its `[+]` icon.
      ;; `data-open` flips on the active-filters count: open when zero,
      ;; closed when one or more. Stays mounted so the transition runs
      ;; in both directions. The button keeps its own `data-testid` so
      ;; tests + Playwright lassos targeting it still resolve while it
      ;; is visible.
      [:div {:data-testid "rf-xray-filter-add-collapse"
             :class       "rf-xray-filters-collapse-h"
             :data-open   (if no-filters? "true" "false")
             :aria-hidden (if no-filters? "false" "true")}
       [:div (filter-pills/chrome-add-filter-button dispatch)]]]
     ;; RIGHT cluster — scope selectors (Frame + Dynamic/Static) then the
     ;; silent-by-default indicators + chrome actions. Per the authority
     ;; reference chrome-ribbon right side.
     [:div {:style {:display "flex" :align-items "center" :gap "8px"}}
      ;; L1 frame-switcher slot — single contractually-
      ;; anchored surface. The view itself reads `:rf.xray/current-
      ;; frame` + `:rf.xray/available-frames` and writes via
      ;; `:rf.xray/select-frame`. The frame is a view SCOPE, not a
      ;; filter.
      ;; A FRESCO BOUNDARY, so it arrives here ALREADY COMPOSED rather
      ;; than as a head this fn writes. See [[ribbon-tree]]'s docstring.
      frame-switcher*
      ;; Dynamic/Static dropdown — compact, understated; its
      ;; active option + `data-active-mode` carry the mode signal, so the
      ;; control stays quiet without help from chrome (there is no
      ;; left-edge stripe, per [[ribbon]]'s docstring below). Always
      ;; rendered, ungated.
      ;; The second boundary child.
      mode-pill*
      ;; Mute indicator (🔇 N) renders inline next to the
      ;; REDACTED indicator. Both are silent-by-default surfaces that
      ;; only paint when their count is positive. Click → unmute
      ;; manager modal.
      (spine-filters/ribbon-mute-indicator dispatch muted-count)
      (ribbon-redacted-indicator redacted-count)
      ;; Theme toggle (sun/moon) sits before the settings/close
      ;; icons per the Figma-Make chrome-ribbon right cluster.
      ;; It is its OWN boundary, so it arrives here as an
      ;; ALREADY-COMPOSED NODE rather than as a head this fn writes:
      ;; [[ribbon]] passes `[ribbon-theme-toggle {}]` and the node lane
      ;; passes the expanded `theme-toggle-tree`. Same shape as
      ;; `static/shell.cljs`'s `surface-tree`, and for the same reason —
      ;; a boundary head cannot be walked by a hiccup walker, because its
      ;; body only runs inside a React render window.
      theme-toggle*
      ;; Thread the captured frame-aware dispatcher so the
      ;; settings / close icon clicks land on the instance frame.
      (ribbon-right-icons {:dispatch-fn dispatch})]]))

(rf.fresco/defview ribbon
  "L1 **chrome ribbon** (bar-1) — reconciled to the authoritative
  reference chrome-ribbon (`tools/xray/design-reference/xray_devtools_
  reference.cljs`). 34px tall (`:top-strip-height`).

    - **LEFT** — the `Event History` label (the reference leads with it;
      there is no `❖ Xray` wordmark), the `[‹ › »]` blue-filled nav
      cluster, then the `+ filter` add-pill.
      The chrome `+ filter` gives way to the events-ribbon's `[+]`:
      when ≥1 filter is committed the events-ribbon owns the
      add affordance and the chrome `+ filter` collapses to zero
      width via the `.rf-xray-filters-collapse-h` horizontal-grid track
      (250ms, same cadence + reduced-motion seam as the events-ribbon
      vertical collapse).
    - **RIGHT** — the Frame dropdown (`frame-switcher/frame-switcher-
      view`) + the Dynamic/Static mode dropdown (`mode-pill/mode-pill`)
      + the mute (🔇 N) / REDACTED (● N) silent-by-default indicators +
      the theme toggle + the `⛶` pop-out · `⚙` settings · `✕` close
      icon-buttons.

  The committed filter pills (green/red) live on bar-2 (the events
  ribbon, `events-ribbon`); only the add(+) sits up here, matching the
  reference's chrome-ribbon (add) / events-ribbon (pills) split.

  THERE IS NO LEFT-EDGE STRIPE — no `:accent` `border-left` on this
  ribbon's root; the Figma authority has none, and
  `chrome-ribbon-has-no-left-edge-stripe` pins that absence. The
  understated mode dropdown carries the Dynamic/Static state on its own,
  via its active option + `data-active-mode`.

  ## A FRESCO BOUNDARY, not an `rf/reg-view`

  SIX READS, ONE BOUNDARY. They are read together and rendered together
  — the nav cluster's boundary state alone needs three of them — so
  splitting them would buy nothing and cost component types. Boundary
  count tracks reads and head-position use, not file size.

  The READS are `rf.fresco/sub`, plain calls the shipped collector
  records an edge for — no deref, no reaction owned by the installed
  adapter, and a re-wire that NOTIFIES when the substrate disposes the
  underlying derived value. Their ORDER is the order the node lane
  reproduces.

  The DISPATCHER is `(:dispatch (rf/capture-frame))` — core's own door,
  which answers the boundary's DECLARED frame inside a body.
  `defview` binds NO name inside the body, so a bare `dispatch` would be
  a LOUD compile error.

  ITS THREE COMPONENT CHILDREN ARE BOUNDARY HEADS, not islands: the
  frame switcher, the mode pill and the theme toggle are headed
  directly, so no `as-child` seam is needed — [[ribbon-tree]] records
  why.

  The argument is the ordinary one-props-map vector every `defview`
  takes. [[dynamic-chrome]] mounts it with none, so it is destructured
  away."
  [_props]
  (ribbon-tree
    (:dispatch (rf/capture-frame))
    {:redacted-count  (rf.fresco/sub [:rf.xray/suppressed-sensitive-count])
     ;; Mute-count drives the chrome ribbon's silent-by-
     ;; default indicator next to the REDACTED indicator. Reading the
     ;; count sub (not the raw set) means the ribbon re-renders only
     ;; when the count changes; the indicator's click opens the
     ;; unmute manager.
     :muted-count     (rf.fresco/sub [:rf.xray/muted-event-ids-count])
     ;; The nav cluster lives on the chrome ribbon per the authority
     ;; reference, so the chrome ribbon reads the spine state.
     :focus           (rf.fresco/sub [:rf.xray/focus])
     ;; The STORED slot beside the composed map. Only its
     ;; `:frame` is used: the picker's restriction, which is the domain
     ;; `spine/focus-step-reducer` walks. The composed `:frame` above is
     ;; the RESOLVED row's frame — it reads the same whenever a
     ;; restriction exists, and differs exactly when none does, which is
     ;; why the boundary cannot derive one from the other.
     :focus-slot      (rf.fresco/sub [:rf.xray/focus-slot])
     ;; The RAW spine vector, not the filtered one. The nav
     ;; cluster's boundary is the only thing this read feeds, and
     ;; `nav-boundary-state`'s domain is the spine's focusable walk — the
     ;; same walk `:rf.xray/focus-event-prev` / `-next` step over. Reading
     ;; `:rf.xray/filtered-event-bundles` here would disable `‹` and `›`
     ;; whenever a pill or a mute hid every row, while `j` / `k` keep
     ;; stepping: one action, two affordances, opposite availability.
     :spine-event-bundles (rf.fresco/sub [:rf.xray/event-bundles])
     :show-ungrouped? (rf.fresco/sub [:rf.xray/show-ungrouped?])
     ;; The chrome `+ filter` and the events-ribbon are
     ;; mutually-exclusive add affordances. Hide the chrome button
     ;; when ≥1 filter is committed (the events-ribbon's own `[+]`
     ;; takes over). Open when zero filters, closed otherwise.
     :filters         (rf.fresco/sub [:rf.xray/active-filters])}
    [frame-switcher/frame-switcher-view {}]
    [mode-pill/mode-pill {}]
    [ribbon-theme-toggle {}]))

;; ---- L2 event list -------------------------------------------------------

;; ---- L2 scrollbar styling -------------------------------------------------
;;
;; The default browser scrollbar is chunky (16-17px) and stylistically loud
;; — wrong rhythm for an info-dense devtools panel. Firefox accepts the
;; standardised `scrollbar-width` / `scrollbar-color` inline (set on the
;; container's `:style`). WebKit/Blink still ship the legacy `::-webkit-
;; scrollbar` pseudo-elements which can ONLY be reached via a real CSS
;; stylesheet (no React inline-style equivalent), so we inject a one-shot
;; `<style>` tag scoped to `[data-testid="rf-xray-event-list"]` at
;; namespace load. The scope keeps the slim chrome confined to the L2
;; list — no global page-level webkit-scrollbar override (would conflict
;; with host-app stylesheets).
;;
;; `defonce` + idempotent DOM probe keeps shadow-cljs `:after-load` from
;; double-injecting; the guard skips the side-effect entirely under node-
;; test where `js/document` does not exist.

(def ^:private scrollbar-style-id
  "rf-xray-event-list-scrollbar")

(def ^:private scrollbar-css
  "Webkit/Blink slim-scrollbar rules scoped to the L2 event-list container.
  Mirror of the inline Firefox `scrollbar-width`/`-color` props so all
  browsers land on the same visual rhythm.

  Colours echo `(:border-subtle tokens)` / `(:text-tertiary tokens)` —
  hardcoded as hex/rgba here because the rule lives in a string outside
  the hiccup tree where the tokens map isn't directly available."
  (str "[data-testid=\"rf-xray-event-list\"]::-webkit-scrollbar"
       " { width: 6px; height: 6px; }"
       "[data-testid=\"rf-xray-event-list\"]::-webkit-scrollbar-track"
       " { background: transparent; }"
       "[data-testid=\"rf-xray-event-list\"]::-webkit-scrollbar-thumb"
       " { background: rgba(107, 112, 128, 0.4); border-radius: 3px; }"
       "[data-testid=\"rf-xray-event-list\"]::-webkit-scrollbar-thumb:hover"
       " { background: rgba(107, 112, 128, 0.7); }"))

(defonce ^:private scrollbar-style-injected?
  ;; defonce so shadow-cljs `:after-load` doesn't re-inject; the `<style>`
  ;; node itself is identified by `id` so even a fresh load reusing this
  ;; symbol would not double-inject.
  (atom false))

(defn- inject-scrollbar-style!
  "Idempotent one-shot injection of the slim-scrollbar CSS into
  `<head>`. No-op when `js/document` is absent (node-test) or the
  style node is already present."
  []
  (when (and (not @scrollbar-style-injected?)
             (exists? js/document)
             (.-head js/document)
             (.-createElement js/document))
    (let [existing (when (.-getElementById js/document)
                     (.getElementById js/document scrollbar-style-id))]
      (when-not existing
        (let [node (.createElement js/document "style")]
          (set! (.-id node) scrollbar-style-id)
          (.appendChild node (.createTextNode js/document scrollbar-css))
          (.appendChild (.-head js/document) node))))
    (reset! scrollbar-style-injected? true)))

;; ---- L2 auto-scroll on focus change --------------------------------------
;;
;; When a new event arrives + mode=:live + focus auto-advances to head,
;; the focused row may render below the L2 list's visible window. The
;; LIVE pill says "tracking head" but the user can't see the head row
;; — defeats the LIVE UX. Hence a `:ref` callback on the focused row
;; that calls `scrollIntoView` when (a) we just landed on a new id and
;; (b) the spine is in :live mode at head (the auto-tracking branch).
;;
;; RETRO clicks already place the focused row where the user clicked
;; (it's already visible — they just clicked it), so we deliberately
;; skip scroll-into-view in RETRO to avoid stealing the cursor.

(defonce ^:private last-scrolled-focus-id
  ;; Closure atom keyed by focused dispatch-id. The ref callback fires
  ;; once per attached DOM element; we scroll only when the id changes
  ;; relative to this atom (otherwise React's normal re-renders would
  ;; re-trigger scroll on every parent rerender).
  (atom ::never))

(defonce ^:private focused-row-ref-cache
  ;; Single-slot memo `{:id <dispatch-id> :ref-fn <fn>}`.
  ;; `event-row` is a plain fn re-invoked on every parent re-render,
  ;; not a stateful component, so a FRESH closure per call would hand
  ;; React a CHANGED callback ref on every render of the focused row.
  ;; React treats a CHANGED callback ref as detach(nil)
  ;; then attach on the VERY NEXT commit, regardless of whether the
  ;; underlying DOM node actually changed — so every render would fire
  ;; `(fn [nil])` (resetting `last-scrolled-focus-id` to `::never`)
  ;; immediately followed by `(fn [el])`, and the `not=` dedup guard
  ;; below would always see a fresh `::never` baseline and re-scroll
  ;; every time — the exact opposite of its "scroll once per
  ;; focus change" purpose. Memoizing on `id` (the only thing that
  ;; should trigger a re-attach) keeps the SAME fn object across
  ;; re-renders of the same focused row, so React's ref reconciliation
  ;; sees no change and skips the detach/reattach cycle entirely; only
  ;; an ACTUAL focus-id change (or auto-track? flipping off and back
  ;; on) produces a new closure and a genuine detach→attach pair.
  (atom nil))

(defn- scroll-focused-row-into-view!
  "Imperative scroll. Called from the focused row's `:ref` callback
  when a new focus id lands in LIVE+head. Guarded against test
  environments where the DOM element is a hand-rolled stub without
  `.scrollIntoView`."
  [^js el]
  (when (and el (.-scrollIntoView el))
    (.scrollIntoView el #js {:behavior "auto" :block "nearest"})))

(defn- focused-row-ref
  "Build the `:ref` callback for a row that is BOTH focused AND in the
  auto-tracking branch (LIVE + head). Returns nil when not in the
  auto-tracking branch — non-nil rows always get a ref attachment
  cycle on first mount, which would otherwise scroll on every initial
  RETRO render too.

  The callback compares `id` against `last-scrolled-focus-id` and
  scrolls + updates the atom only on transition. nil-element calls
  (React's unmount signal) reset the atom so a re-mount of the same
  id will scroll again (covers the toggle-off/on case).

  Memoized on `id` via `focused-row-ref-cache` so REPEAT
  calls for the SAME focused row (every re-render while focus doesn't
  change) return the IDENTICAL fn object rather than a fresh closure.
  Without this, React's callback-ref reconciliation would detach +
  reattach on every render (a changed fn reference looks like a
  changed ref to React), which resets `last-scrolled-focus-id` right
  before re-checking it — permanently defeating the dedup guard above."
  [id auto-track?]
  (when auto-track?
    (let [{cached-id :id cached-fn :ref-fn} @focused-row-ref-cache]
      (if (and cached-fn (= cached-id id))
        cached-fn
        (let [f (fn [el]
                  (cond
                    (nil? el)
                    (reset! last-scrolled-focus-id ::never)

                    (not= id @last-scrolled-focus-id)
                    (do (reset! last-scrolled-focus-id id)
                        (scroll-focused-row-into-view! el))))]
          (reset! focused-row-ref-cache {:id id :ref-fn f})
          f)))))

(defn- event-row
  "One row in the L2 event list. Single line per the Figma-Make
  EventList (the `event-list` component in
  `design-reference/xray_devtools_reference.cljs`).

  ## Clean mock layout

  Default row content, four columns matching the mock exactly:

      :event-id   source   timestamp   duration

  - **`:event-id`** — the bare event-id keyword (not the full event
    vector). Args / payload move to the tooltip + Epoch panel detail.
  - **`source`** — the closed-enum `:source` axis (`ui` / `fx-dispatch` / `after-timer` / …).
  - **`timestamp`** — the absolute wall-clock `HH:MM:SS.mmm`.
  - **`duration`** — the handler wall-time (`1.2 ms`).

  The active (selected) row is shown by BACKGROUND (the mock's
  `isActive` → `bg-[var(--devtools-hover)]`) plus a leading `>`
  selection caret — see the caret's comment below. The row carries no
  origin-prefix glyph, activity badges (⚠ 🌐 🤖), trailing lifecycle
  status stripe, out-of-focus dimming or `:ungrouped` muted pseudo-row
  — none of those are in the mock.

  The row's `:title` attribute carries the dropped fields (full event
  vector with args, sequence number, frame, source coord, handler
  duration) so a hover surfaces them without leaving L2. Clicking the
  row opens the Epoch panel in L4 with the full untruncated content.

  Right-click (`on-context-menu`) lowers per spec/018 §7 'Right-click
  event-row → context menu' into `:rf.xray/open-row-context-menu`
  — a small floating context menu with two items:

    - 'Mute <event-id>' — one-step mute via
      `:rf.xray/mute-event-id`; the row disappears from the spine
      and the L1 ribbon's mute-count indicator increments.
    - 'Always hide this event-type…' — opens the rich OUT-filter
      popup via `:rf.xray/hide-event-type` (the existing flow).

  The menu state lives in app-db (`:row-context-menu`) so the menu
  renders at the shell-view root and floats above the L2 list's
  overflow-hidden clipping. preventDefault on the right-click
  suppresses the browser's native menu.

  `col-widths` carries the resolved
  `{:source N :timestamp N :duration N}` map every row reads its
  inline column widths from. The parent (`event-list`) subscribes
  ONCE per paint and threads the resolved map through props so each
  row doesn't re-subscribe per render."
  [{:keys [event-bundle focused-id auto-track? col-widths dispatch-fn]}]
  (let [dispatch-fn (or dispatch-fn rf/dispatch)
        id          (:dispatch-id event-bundle)
        focused?    (= id focused-id)
        ;; The Figma `source` column tag (source name as
        ;; text; `ui` for the default app-code source). `:source` is
        ;; the single closed-enum functional-origin axis.
        source         (l2-timeline/source-of event-bundle)
        source-tag     (l2-timeline/origin-source-tag source)
        ev-id       (event-id-of-event-bundle event-bundle)
        event-vec   (:event event-bundle)
        ;; The active row is marked by background (Figma
        ;; EventList's `isActive` → `bg-[var(--devtools-hover)]`). No
        ;; border ring, no trailing status stripe — those are not in the
        ;; mock.
        ;;
        ;; The selected background is the dedicated
        ;; `:selected-row-bg` (a step DARKER than `:hover`) rather than
        ;; `:hover` itself. Two reasons: selection reads as a state
        ;; distinct from mere hover, AND the darker grey survives UNDER
        ;; the issue-row pink wash (a low-opacity rose painted as a
        ;; `:background-image` layer over this `:background-color`). With
        ;; the `:hover` grey, a SELECTED ERROR row would be hard to tell
        ;; from an unselected one — the wash would drown the selection.
        ;; The leading ">" caret (below) is
        ;; the background-independent belt-and-braces selection signal.
        bg          (if focused? (:selected-row-bg tokens) "transparent")
        ;; Light-pink WASH when this event's epoch CONTAINS
        ;; AN ISSUE (any error / warning / schema-violation / … — the
        ;; SAME set the Issues ribbon/feed aggregates, via the canonical
        ;; `l2-timeline/event-bundle-has-issue?` predicate). The cross-epoch
        ;; "this event had a problem" cue at the spine. Painted as a flat
        ;; `:background-image` gradient layer (the `:bg-issue-row` token,
        ;; a low-opacity rose wash) so it COMPOSES OVER the focused-row /
        ;; hover `:background-color` rather than clobbering it — an issue
        ;; row reads pink whether focused or not, and the focus highlight
        ;; survives underneath. Nil when no issue, so a clean row paints
        ;; only its base background.
        has-issue?  (l2-timeline/event-bundle-has-issue? event-bundle)
        issue-wash  (when has-issue?
                      (str "linear-gradient(" (:bg-issue-row tokens) ", "
                           (:bg-issue-row tokens) ")"))
        ;; Only the focused row in the LIVE-at-head
        ;; auto-tracking branch carries a ref. RETRO and non-focused rows
        ;; get nil (no DOM-side scroll work, no per-render cost).
        ref-fn      (when focused? (focused-row-ref id auto-track?))
        ;; Body-click is pure SELECTION (drives the L3 tabs):
        ;; row click selects the event-bundle, full stop.
        body-click  (fn [_e]
                      ;; Dispatch through the captured
                      ;; instance-frame dispatcher (threaded from the
                      ;; `event-list` boundary) so the focus-event
                      ;; write lands on this shell's frame.
                      (dispatch-fn [:rf.xray/focus-event id (:frame event-bundle)]))]
    ;; Density: height 22px + 1px vertical padding. Xray is
    ;; info-dense; this keeps a clickable hit-area while fitting ~10 rows
    ;; in the vertical budget a 28px row spends on 8.
    ;;
    ;; Keyboard a11y. Rows expose `role="button"` +
    ;; `tab-index="0"` + `aria-label` so keyboard-only users can Tab
    ;; into the list and operate it. Enter / Space activates the body
    ;; (select event-bundle); Shift+F10 + ContextMenu key open the row's
    ;; context menu (Mute / Hide event-type) — the same affordance
    ;; right-click users get, so the menu's actions have a keyboard path.
    [:li (cond-> {;; The React `:key` rides THIS row's own
                  ;; attribute map. `event-row` is CALLED — Fresco
                  ;; grades a plain fn in head position a loud error — so
                  ;; its opts map is an ordinary argument React never
                  ;; sees, and the seq element that needs the key is the
                  ;; `<li>` this returns.
                  :key         (str id)
                  :data-testid (str "rf-xray-event-row-" (str id))
                  ;; Machine-readable issue-row flag so the
                  ;; light-pink-wash contract is pinnable from a CLJS unit
                  ;; test (issue epoch → "true"; clean row → absent) without
                  ;; parsing the inline gradient string.
                  :data-rf-xray-issue-row (when has-issue? "true")
                  ;; Machine-readable filter-bypass flag. An
                  ;; errored event a filter would hide is surfaced anyway
                  ;; (spec/018 §7 Error overrides), tagged in the data layer
                  ;; by `filters.error-override`; the errored row already
                  ;; carries the pink issue-wash above, and this flag makes
                  ;; the "shown despite a filter" reason pinnable from a unit
                  ;; test + queryable by tooling.
                  :data-rf-xray-filter-bypassed (when (:rf.xray/filter-bypassed? event-bundle) "true")
                  :role        "button"
                  :tab-index   "0"
                  :aria-label  (if ev-id
                                 (str "Event " (str ev-id)
                                      (when focused? " (focused)"))
                                 "Event row")
                  :aria-pressed (if focused? "true" "false")
                  :on-click    body-click
                  :on-key-down (fn [^js e]
                                 ;; Keyboard activation +
                                 ;; menu fallback. Enter / Space fires
                                 ;; the body-click selection; Shift+F10
                                 ;; (Windows / Linux platform standard)
                                 ;; and the dedicated ContextMenu key
                                 ;; open the row's context menu so the
                                 ;; Mute / Hide affordances are reachable
                                 ;; without right-click. The menu opens
                                 ;; at the row's bounding-box top-left
                                 ;; (the click-coords path has no
                                 ;; equivalent for keyboard activation
                                 ;; — anchoring on the row itself is
                                 ;; the standard WAI-ARIA recipe).
                                 (let [k       (.-key e)
                                       shift?  (.-shiftKey e)
                                       target  (.-currentTarget e)]
                                   (cond
                                     (or (= k "Enter") (= k " "))
                                     (do (.preventDefault e)
                                         (body-click e))

                                     (or (= k "ContextMenu")
                                         (and shift? (= k "F10")))
                                     (when ev-id
                                       (.preventDefault e)
                                       (let [rect (when target (.getBoundingClientRect target))
                                             x    (if rect (.-left rect) 0)
                                             y    (if rect (.-bottom rect) 0)]
                                         (dispatch-fn
                                           [:rf.xray/open-row-context-menu
                                            {:event-id ev-id
                                             :x        x
                                             :y        y}]))))))
                  :on-context-menu (fn [^js e]
                                     ;; Open the row's
                                     ;; floating context menu at the
                                     ;; click coords. The menu (mounted
                                     ;; at shell-view root via
                                     ;; `spine-filters/RowContextMenu`)
                                     ;; carries both 'Mute' (one-step)
                                     ;; and 'Always hide…' (rich
                                     ;; OUT-pill popup) items.
                                     (when ev-id
                                       (.preventDefault e)
                                       (dispatch-fn
                                         [:rf.xray/open-row-context-menu
                                          {:event-id ev-id
                                           :x        (.-clientX e)
                                           :y        (.-clientY e)}])))
                  ;; The fields the row omits (full event vector with
                  ;; args, sequence number, frame, source coord, handler
                  ;; duration) surface in this hover tooltip + the L4
                  ;; Epoch panel on click.
                  :title (row-tooltip-text event-bundle)
                  :style {:display       "flex"
                          :align-items   "center"
                          ;; Shared column gap + horizontal
                          ;; padding so the data columns line up under the
                          ;; header's columns. Vertical padding stays 1px
                          ;; (row density); only the column-defining axes
                          ;; (gap + h-padding) are shared with the header.
                          ;; `border-box` matches the header so the 1px
                          ;; transparent border resolves identically on
                          ;; both surfaces (no 1px column drift).
                          :box-sizing    "border-box"
                          :gap           l2-col-gap
                          :padding       (str "1px " l2-row-h-padding)
                          :height        "22px"
                          :line-height   "20px"
                          :cursor        "pointer"
                          ;; Active row marked by background (plus the
                          ;; leading caret). The `1px solid transparent` border
                          ;; keeps border-box alignment with the header so
                          ;; columns never drift.
                          ;;
                          ;; Split into `:background-color`
                          ;; (the focus / hover highlight) + a flat
                          ;; `:background-image` wash layer (the issue-row
                          ;; rose, nil when no issue). The wash COMPOSITES
                          ;; over the highlight so an issue row reads pink
                          ;; with the focus state intact underneath.
                          :background-color bg
                          :background-image issue-wash
                          :border        "1px solid transparent"
                          :border-radius "2px"
                          :font-family   mono-stack
                          :font-size     (:mono-body type-scale)
                          :color         (:text-primary tokens)
                          :white-space   "nowrap"
                          :overflow      "hidden"
                          :text-overflow "ellipsis"}}
           ref-fn (assoc :ref ref-fn))
     ;; Leading SELECTION CARET gutter. A small ">" glyph
     ;; sits in a FIXED-WIDTH leading gutter when the row is focused, and
     ;; the gutter renders empty (same width) otherwise — so selecting a
     ;; row never shifts the columns. This is the background-INDEPENDENT
     ;; selection signal: it reads on any row state (clean / issue) where
     ;; the grey-vs-pink background channels can fight (a selected error
     ;; row). It departs deliberately from the Figma background-only
     ;; mock, which cannot mark selection on an error row.
     [:span {:data-testid "rf-xray-row-selection-caret"
             :aria-hidden "true"
             :style {:flex-shrink 0
                     :width "10px"
                     :display "inline-flex"
                     :align-items "center"
                     :justify-content "center"
                     :color (:accent tokens)
                     :font-weight 700}}
      (when focused? ">")]
     ;; Column order: `event id` · `source` · `timestamp` ·
     ;; `duration` (Figma-Make EventList). The bare event-id keyword leads
     ;; the data columns as the primary read; the source tag follows as
     ;; secondary context. The full event vector with args moves to the
     ;; row's hover tooltip + the L4 Epoch panel detail. The event-id
     ;; column is LEFT-aligned (Figma `text-left`) so the keyword sits
     ;; flush under the header's `event id` label.
     [:span {:data-testid "rf-xray-row-event-id"
             :style {:flex "1 1 auto" :overflow "hidden"
                     :text-overflow "ellipsis"
                     :text-align "left"
                     :min-width "0"}}
      (render-event-id-only event-vec)]
     ;; Divider sits between `event id` and `source`
     ;; (to the LEFT of `source`, per `col-divider`'s docstring) and
     ;; resizes `source`. The divider widths participate in the flex
     ;; layout on rows + header identically so the cells stay
     ;; column-for-column aligned. `event id` is the row's sole
     ;; `flex 1 1 auto` column — `col-divider-on-move` inverts the drag
     ;; delta so dragging this handle tracks the
     ;; pointer instead of receding from it.
     (col-divider {:col-id    :source
                   :col-px    (:source col-widths)
                   :row-height "22px"
                   :dispatch-fn dispatch-fn})
     ;; The `source` COLUMN (Figma EventList).
     ;; A user-resizable cell aligned under the header's
     ;; `source` label, carrying the dispatch-origin as a short text tag.
     ;; The reference tags EVERY row, so the default app-code origin
     ;; (`:user`, plus nil/unknown synthetic event-bundles) renders `ui` rather
     ;; than a blank cell. No origin-prefix glyph — the mock carries the
     ;; source tag as plain text only.
     [:span {:data-testid (when source-tag (str "rf-xray-row-origin-" source-tag))
             :data-rf-xray-origin source-tag
             :style {:flex-shrink 0
                     :width (->px (:source col-widths))
                     :display "inline-flex"
                     :align-items "center"
                     :overflow "hidden"
                     :text-overflow "ellipsis"
                     :white-space "nowrap"
                     :color (:text-secondary tokens)
                     :font-family sans-stack
                     :font-size (:caption type-scale)}}
      (when source-tag source-tag)]
     ;; Divider sits between `source` and `timestamp`.
     (col-divider {:col-id    :timestamp
                   :col-px    (:timestamp col-widths)
                   :row-height "22px"
                   :dispatch-fn dispatch-fn})
     ;; Timestamp column — absolute wall-clock
     ;; `HH:MM:SS.mmm`, right-aligned. The chip carries an absolute-time
     ;; `:title` tooltip as the power-user reveal.
     (relative-time-chip event-bundle (:timestamp col-widths))
     ;; Divider sits between `timestamp` and `duration`.
     (col-divider {:col-id    :duration
                   :col-px    (:duration col-widths)
                   :row-height "22px"
                   :dispatch-fn dispatch-fn})
     ;; Duration cell — the trailing `duration` column,
     ;; the Figma EventList's fourth column. Handler wall-time
     ;; (`1.2 ms`), right-aligned, flush against the row's trailing edge.
     (duration-cell event-bundle (:duration col-widths))]))

;; ---- events ribbon -----------------------------------------------------
;;
;; The SECOND stratum below the chrome ribbon. LEFT → RIGHT:
;;
;;   ↳ filters:  [+]  [+pill ✕][×pill ✕]   …   N events filtered out
;;
;; Left = the contextual label + add-filter icon + committed IN/OUT
;; pills; far right = the `N events filtered out` warning, rendered
;; ONLY when N > 0. (There is no focus-chip or `Clear Filters` button;
;; the nav cluster lives on the chrome ribbon.)
;;
;; The hidden COUNT reflects pill/mute suppression ONLY — the frame is a
;; view SCOPE, not a filter, so switching frames
;; never inflates the count. Active filters (transient — reset on
;; reload) can still silently suppress L2 rows within a
;; session; this ribbon makes them a VISIBLE cause. Recovery is per
;; surface: each pill's trailing `✕` removes that pill; muted event-ids
;; are managed through the chrome ribbon's `🔇 N` chip → mute manager.

(defn filters-hidden-message
  "Pure hiccup. The `N events filtered out` warning count — the TRAILING
  signal of the events ribbon (bar-2), pushed to the far right after the
  committed filter pills. Renders nil unless the hidden count is positive
  (`:visible?`). Counts pill/mute suppression ONLY — the frame is a view
  scope, never counted as hidden.

  Reconciled to the authoritative reference events-ribbon
  (`tools/xray/design-reference/xray_devtools_reference.cljs`): the
  warning reads `\"N events filtered out\"` in the `:warning` colour
  (reference `--devtools-warning`, `font-medium`)."
  [{:keys [hidden visible?] :as _summary}]
  (when visible?
    [:div {:data-testid "rf-xray-filters-hidden-indicator"
           :role        "status"
           :style {:display     "inline-flex"
                   :align-items "center"
                   :font-family sans-stack
                   :font-size   (:caption type-scale)
                   :color       (:text-primary tokens)}}
     [:span {:data-testid "rf-xray-filters-hidden-count"
             :style {:font-weight 500 :color (:warning tokens) :white-space "nowrap"}}
      (str hidden " " (common/pluralize hidden "event") " filtered out")]]))

(defn events-ribbon-tree
  "The L1.5 events ribbon's WHOLE hiccup, as a pure function of the
  frame-bound `dispatch` and the two values [[events-ribbon]] reads.

  SPLIT OUT OF [[events-ribbon]] because a boundary's body may only run
  inside a React render window.

  PURE HEADS: `filter-pills/events-add-filter-button`,
  `ribbon-filter-pills` and `filters-hidden-message` all answer hiccup,
  so all three are CALLED rather than headed."
  [dispatch {:keys [filters hidden-summary]}]
  (let [filter-count (+ (count (:in filters)) (count (:out filters)))
        ;; A mute hides rows with no pill to show for it, so the count
        ;; alone opens the ribbon too: the `N events filtered out` warning
        ;; never renders inside a closed track.
        open?        (or (pos? filter-count) (boolean (:visible? hidden-summary)))]
    ;; Collapse track. Always mounted so the height/opacity
    ;; transition runs in BOTH directions (open when the first filter is
    ;; added, closed when the last is removed). `data-open` drives the
    ;; `grid-template-rows: 0fr ⇄ 1fr` + opacity rule in motion-css.
    [:div {:data-testid "rf-xray-events-ribbon-collapse"
           :class       "rf-xray-filters-collapse"
           :data-open   (if open? "true" "false")}
     [:div {:data-testid "rf-xray-events-ribbon"
            :role        "toolbar"
            :aria-label  "Xray filters"
            ;; `aria-hidden` + the CSS collapse keep the closed bar out of
            ;; the a11y tree and the tab order when there are no filters.
            :aria-hidden (if open? "false" "true")
            :style {:display          "flex"
                    :align-items      "center"
                    :gap              "13px"
                    :min-height       (:events-ribbon-height layout)
                    :flex-wrap        "wrap"
                    :padding          "0 12px"
                    :background       (:bg-2 tokens)
                    :border-bottom    (str "1px solid " (:border-subtle tokens))
                    :font-family      sans-stack
                    :font-size        (:body type-scale)}}
      ;; `↳ filters:` contextual label leads the events ribbon
      ;; (Figma-Make surface), followed by the add-filter (+) ICON button,
      ;; then the committed pills. The corner-down-right glyph mirrors the
      ;; tabs ribbon's `↳ selected` label idiom.
      [:span {:data-testid "rf-xray-events-ribbon-filters-label"
              :style {:display      "inline-flex"
                      :align-items  "center"
                      :gap          "6px"
                      :color        (:text-secondary tokens)
                      :font-family  sans-stack
                      :font-size    (:caption type-scale)
                      :white-space  "nowrap"}}
       [:span {:aria-hidden "true"} "↳"]
       "filters:"]
      (filter-pills/events-add-filter-button dispatch)
      ;; The committed green/red filter pills.
      (ribbon-filter-pills dispatch {:filters filters})
      ;; The `N events filtered out` warning is pushed to the
      ;; RIGHT end (Figma-Make surface). The `margin-left: auto` shoves it
      ;; to the trailing edge regardless of pill count. Renders only when
      ;; N > 0.
      (when (:visible? hidden-summary)
        [:div {:data-testid "rf-xray-events-ribbon-actions"
               :style {:display "flex" :align-items "center" :gap "12px"
                       :margin-left "auto"}}
         (filters-hidden-message hidden-summary)])]]))

(rf.fresco/defview events-ribbon
  "L1.5 **events ribbon** (bar-2) — reconciled to the Figma-Make surface.
  The second stratum below the chrome ribbon.
  LEFT → RIGHT:

    - the `↳ filters:` contextual label (corner-down-right glyph);
    - the add-filter `+` ICON button
      (`filter-pills/events-add-filter-button`) — opens the edit popup;
    - the committed green-bordered IN pills + red-bordered OUT pills
      (`filter-pills/pills-view`), each with a vertical divider before
      its `✕`;
    - pushed to the FAR RIGHT (via `margin-left: auto`): the `N events
      filtered out` warning text (when N > 0, `:warning` colour).

  ## Conditional + animated

  The whole `filters:` ribbon is HIDDEN when there are zero filters and
  appears only after the user creates the first filter via `[+ filter]`,
  or when mutes alone hide rows, so their `N events filtered out` count
  shows. It animates OPEN when the first filter is added and animates
  CLOSED when the last filter is removed. The collapse uses a CSS
  `grid-template-rows: 0fr ⇄ 1fr` transition (the modern jank-free
  height-collapse technique) keyed off the `data-open` attribute — see
  `theme/global-styles/motion-css` for the rule. The outer collapse track
  stays mounted so the transition can run in both directions; the inner
  content is the actual bar-2 surface.

  There is no `Clear Filters` button — pills are removed individually
  via each pill's `✕`.

  ## A FRESCO BOUNDARY, not an `rf/reg-view`

  TWO READS, ONE BOUNDARY — the pill inventory and the hidden-count
  summary are read together and rendered together. The READS are
  `rf.fresco/sub`, plain calls the shipped collector records an edge for;
  the DISPATCHER is `(:dispatch (rf/capture-frame))`.

  A distinct `bg-2` background + `border-subtle` hairline separate it
  from the chrome ribbon's dark `chrome-ribbon-bg` band as a distinct
  layer.

  The argument is the ordinary one-props-map vector every `defview`
  takes. [[dynamic-chrome]] mounts it with none, so it is destructured
  away."
  [_props]
  (events-ribbon-tree
    (:dispatch (rf/capture-frame))
    {:filters        (rf.fresco/sub [:rf.xray/active-filters])
     :hidden-summary (rf.fresco/sub [:rf.xray/hidden-by-filters])}))

;; The L2 list's
;; column-header row, reconciled to the Figma-Make EventList. The header
;; names the FOUR columns the rows align to, in Figma-Make order:
;; `event id` · `source` · `timestamp` · `duration` (event-id leads;
;; source follows). The column widths mirror the row layout below: a
;; leading spacer matching the rows' selection-caret gutter, the
;; flexible `event id` column, a fixed `source` tag column, then the
;; right-aligned `timestamp` chip and `duration` cells.

(defn- l2-column-header
  "Sticky column-header row for the L2 event list (Figma-Make
  EventList). Names the FOUR columns the
  rows align to, in Figma-Make order — `event id` · `source` ·
  `timestamp` · `duration`. Caption-weight, muted, on the chrome surface
  so it reads as chrome rather than data.

  Accepts `col-widths` (the resolved
  `{:source N :timestamp N :duration N}` map) so the header column
  widths read from the SAME source the rows do. Dividers between
  columns carry the drag affordance — pointerdown begins a drag,
  arrow keys do a fine resize, double-click resets to default.

  `dispatch-fn` is the frame-aware dispatcher captured by
  the `event-list` boundary body, threaded to each divider so resize
  writes land on the instance frame."
  [col-widths dispatch-fn]
  (let [dispatch-fn (or dispatch-fn rf/dispatch)
        cell {:color       (:text-tertiary tokens)
              :font-family sans-stack
              :font-size   (:caption type-scale)
              :font-weight 500
              :text-transform "lowercase"
              :white-space "nowrap"}]
    [:div {:data-testid "rf-xray-event-list-header"
           :role        "row"
           ;; The header shares the EXACT
           ;; column structure of the data rows (`event-row`): same flex
           ;; `gap`, same horizontal `padding`, the SAME per-column
           ;; widths via the shared `:rf.xray/event-list-col-widths` sub,
           ;; and the SAME dividers between cells. It also carries a
           ;; matching `1px solid transparent` border so the rows'
           ;; active-row 1px border never offsets the data columns 1px
           ;; right of the header. Result: event id / source / timestamp /
           ;; duration sit directly above their data columns (Figma
           ;; EventList).
           :style {:position      "sticky"
                   :top           0
                   :z-index       1
                   :display       "flex"
                   :align-items   "center"
                   :box-sizing    "border-box"
                   :gap           l2-col-gap
                   :padding       (str "2px " l2-row-h-padding)
                   :border        "1px solid transparent"
                   :background    (:bg-1 tokens)
                   :border-bottom (str "1px solid " (:border-subtle tokens))}}
     ;; Empty leading-gutter spacer matching the data rows'
     ;; 10px selection-caret gutter so the `event id` header label sits
     ;; flush over the row keywords (no 10px column drift). Shares the
     ;; row's gutter width + flex-shrink:0 exactly.
     [:span {:data-testid "rf-xray-event-list-col-caret-gutter"
             :aria-hidden "true"
             :style {:flex-shrink 0 :width "10px"}}]
     ;; Column order is `event id` FIRST, then `source`
     ;; (Figma-Make surface). The event-id is the primary read; source is
     ;; secondary context, so the id leads. The `event id` column is
     ;; LEFT-aligned (the row's keyword sits flush under this label).
     [:span {:data-testid "rf-xray-event-list-col-event-id"
             :style (merge cell {:flex "1 1 auto" :min-width "0"
                                 :text-align "left"})}
      "event id"]
     ;; Divider between `event id` (flex) and `source`.
     (col-divider {:col-id    :source
                   :col-px    (:source col-widths)
                   :row-height "100%"
                   :dispatch-fn dispatch-fn})
     [:span {:data-testid "rf-xray-event-list-col-source"
             :style (merge cell {:width (->px (:source col-widths))
                                 :flex-shrink 0})}
      "source"]
     ;; Divider between `source` and `timestamp`.
     (col-divider {:col-id    :timestamp
                   :col-px    (:timestamp col-widths)
                   :row-height "100%"
                   :dispatch-fn dispatch-fn})
     [:span {:data-testid "rf-xray-event-list-col-timestamp"
             :style (merge cell {:flex-shrink 0 :text-align "right"
                                 :width (->px (:timestamp col-widths))})}
      "timestamp"]
     ;; Divider between `timestamp` and `duration`.
     (col-divider {:col-id    :duration
                   :col-px    (:duration col-widths)
                   :row-height "100%"
                   :dispatch-fn dispatch-fn})
     ;; The fourth Figma column.
     [:span {:data-testid "rf-xray-event-list-col-duration"
             :style (merge cell {:flex-shrink 0 :text-align "right"
                                 :width (->px (:duration col-widths))})}
      "duration"]]))

(defn newer-event-count
  "Pure helper. How many spine-focusable event-bundles sit
  AFTER the focused one — the `N` the L2 newer-events marker reports.

  ## Its DOMAIN is the spine's vector, and that is the whole point

  Counted over [[spine-focusable-bundles]] — `spine/focusable-event-
  bundles` under the same `show-ungrouped?` opt-in and the same STORED
  frame scope `spine/compose-focus` walks — and NEVER over the vector
  [[event-list-tree]] renders. That vector is
  `:rf.xray/filtered-event-bundles` — the view-scope frame, the ribbon's
  IN/OUT pills and the mutes have already been applied to it
  (`filters.cljs`) — so index arithmetic over it can read ZERO while
  newer events genuinely exist, and the pinned row can be absent from it
  altogether. Counting where `compose-focus` counts is what makes
  \"newer\" agree with \"head\" by construction, which spec/018 §Spine
  binding requires of every head-aware selector. The domain sits behind
  the shared helper so this selector and [[nav-boundary-state]] cannot
  drift apart.

  `frame-scope` IS THE STORED RESTRICTION, never the composed focus's
  resolved `:frame`. `»` is `:rf.xray/follow-head`, which
  clears the pinned id and leaves `[:focus :frame]` alone — so the head
  it lands on is the head of the STORED scope, and `N` has to be counted
  over that same domain or the marker promises a jump of a different
  size from the one `»` makes. nil means unscoped: with no picker
  restriction the spine's head is the newest row in ANY frame.

  The focused row is located by `spine/focused-index` — the `[frame
  dispatch-id]` COORDINATE off the composed `focus`, not a bare id. An
  unscoped domain spans frames, and ids repeat across them, so an
  id-only scan can land on an earlier frame's namesake and count every
  row after THAT one.

  Returns nil when the focused id is not in that vector — an evicted
  RETRO pin. The marker then renders WITHOUT a number rather than with a
  wrong one."
  [event-bundles show-ungrouped? frame-scope focus]
  (let [focusable (spine-focusable-bundles event-bundles show-ungrouped?
                                           frame-scope)
        idx       (spine/focused-index focusable
                                       (:frame focus)
                                       (:dispatch-id focus))]
    (when idx
      (- (count focusable) idx 1))))

(defn newer-events-text
  "The marker's copy. spec/018 §LIVE-tracking + sticky rules writes
  `↓ N new events — press ⏭ to follow`; the chrome paints `»`
  ([[ribbon-nav-cluster]]'s `rf-xray-nav-head`, title \"Fast-forward to
  latest (G)\"), so the marker names the control the user can actually
  see. Singular at one. A nil or zero count drops the digit rather than
  printing a number the spine could not stand behind — see
  [[newer-event-count]]."
  [n]
  (str "↓ "
       (when (and n (pos? n)) (str n " "))
       (if (= 1 n) "newer event" "newer events")
       " — » to follow"))

(defn- newer-events-marker
  "The sticky one-line strip pinned to the bottom edge of the L2 scroll
  box (spec/018 §LIVE-tracking + sticky rules, rows 2 and 3). Clicking it
  dispatches `:rf.xray/follow-head`, the same event the `»` control and
  the `G` / `l` keys fire.

  INLINE STYLE ONLY, and deliberately: no theme rule, no new token, no
  colour, no animation. The shell carries no chrome that paints while
  nothing is wrong — no LIVE/RETRO mode pill, gutter glyph, status
  stripe, left-edge accent or continuous pulse. This paints ONLY
  while the panel is reporting a stale epoch as current, and nothing at
  all while the spine is following.

  `dispatch` is the frame-aware dispatcher [[event-list-tree]] receives,
  so the write lands on the surrounding instance frame."
  [n dispatch]
  [:div {:data-testid "rf-xray-newer-events"
         :role        "button"
         :title       "Fast-forward to latest (G)"
         :on-click    (fn [_e] (dispatch [:rf.xray/follow-head]))
         :style       {:position    "sticky"
                       :bottom      0
                       :width       "100%"
                       :box-sizing  "border-box"
                       :cursor      "pointer"
                       :padding     "2px 6px"
                       :background  (:bg-2 tokens)
                       :border-top  (str "1px solid " (:border-subtle tokens))
                       :color       (:text-secondary tokens)
                       :font-family sans-stack
                       :font-size   (:body-tight type-scale)
                       :white-space "nowrap"
                       :overflow    "hidden"
                       :text-overflow "ellipsis"}}
   (newer-events-text n)])

(defn event-list-tree
  "The L2 event list's WHOLE hiccup, as a pure function of the frame-bound
  `dispatch` and the seven values [[event-list]] reads.

  SPLIT OUT OF [[event-list]] so the view's body is the thin
  read-and-call shape every Fresco boundary has, and the node lane's door
  can drive the pure tree.

  PURE HEADS: `l2-column-header` and `event-row` both answer hiccup, so
  both are CALLED rather than headed. `event-row`'s React key rides the
  `<li>` it returns — see its attribute map.

  THE VISIBILITY FILTER LIVES HERE, not in the boundary, so the node
  lane's door reproduces the READS alone and there is no second copy of
  the derivation to drift.

  TWO VECTORS ARRIVE, AND THEY ARE NOT INTERCHANGEABLE. `event-bundles`
  is `:rf.xray/filtered-event-bundles` — what the user SEES, after the
  view-scope frame, the ribbon pills and the mutes. `spine-event-bundles`
  is raw `:rf.xray/event-bundles` — what the SPINE walks, and the vector
  `spine/compose-focus` derives `:head?` from. Rows render from the
  first; the newer-events marker's presence and count come from the
  second (see [[newer-event-count]]).

  TWO FOCUS READS ARRIVE FOR THE SAME REASON. `focus` is the
  composed `:rf.xray/focus` — the resolved `[frame dispatch-id]`
  coordinate. `focus-slot` is `:rf.xray/focus-slot`, and only its
  `:frame` is read: the stored picker restriction that bounds the
  spine's walk. They read alike whenever a restriction is stored and
  differ exactly when none is, so the count cannot derive one from the
  other — see [[nav-boundary-state]], which draws the same distinction."
  [dispatch {:keys [col-widths list-height-px event-bundles spine-event-bundles
                    focus focus-slot show-ungrouped?]}]
  (let [focused-id    (:dispatch-id focus)
        ;; LIVE+head+not-paused = the auto-tracking branch from
        ;; spine/compose-focus. Only here do we want scroll-into-view
        ;; to fire on focus change; RETRO + paused-LIVE leave the
        ;; user's scroll position alone.
        auto-track?   (and (= :live (:mode focus))
                           (:head? focus)
                           (not (:paused? focus)))
        ;; spec/018 §LIVE-tracking + sticky rules. The
        ;; marker's presence is `(not (:head? focus))` and nothing else.
        ;; That ONE predicate is exactly "following is suspended AND
        ;; something newer exists": `spine/compose-focus`'s LIVE+unpaused
        ;; branch resolves the effective id to the head record, so
        ;; `:head?` is unconditionally true while the spine is tracking;
        ;; it can only read false when a RETRO pin or a LIVE-paused pin
        ;; has been overtaken. Paused-but-still-current therefore paints
        ;; nothing, which is right — nothing is stale at that instant,
        ;; and `»` has already lit (`nav-boundary-state`'s `live?`
        ;; excludes paused).
        stale-read?   (not (:head? focus))
        ;; The STORED restriction bounds the count's domain;
        ;; the COMPOSED focus locates the row inside it. Passing the
        ;; composed `:frame` as the scope would count one frame's rows
        ;; while `»` jumps to the spine's head across all of them.
        newer-count   (when stale-read?
                        (newer-event-count spine-event-bundles show-ungrouped?
                                           (:frame focus-slot) focus))
        event-bundles (filterv #(l2-event-bundle-visible? % show-ungrouped?) event-bundles)]
    [:div {:data-testid "rf-xray-event-list-wrap"
           :style {:display "flex" :flex-direction "column"}}
     ;; The hidden-by-filters message lives in the
     ;; events ribbon (above this list); the list is just the scroll
     ;; container.
     [:div {:data-testid "rf-xray-event-list"
            :style {;; Live height from the seam handle's
                    ;; sub; default 200 px (8 rows × 22 px + gaps +
                    ;; padding). The seam handle is the resize affordance
                    ;; (Spec 007 §Splitter affordance), not a CSS
                    ;; `:resize` rule.
                    :height        (->px list-height-px)
                    :min-height    (->px config/min-events-list-height-px)
                    :overflow-y    "auto"
                    :overflow-x    "hidden"
                    :background    (:bg-2 tokens)
                    :border-bottom (str "1px solid " (:border-subtle tokens))
                    :padding       "4px"
                    ;; Firefox standardised props for the
                    ;; slim scrollbar. WebKit/Blink pseudo-element rules ship
                    ;; via the `inject-scrollbar-style!` <style> tag above —
                    ;; pseudo-elements can't be set via React inline-style.
                    :scrollbar-width "thin"
                    :scrollbar-color "rgba(107, 112, 128, 0.4) transparent"}}
      (if (empty? event-bundles)
        [:div {:data-testid "rf-xray-event-list-empty"
               :style {:padding   "16px"
                       :color     (:text-secondary tokens)
                       :font-family sans-stack
                       :font-size (:body type-scale)}}
         "No events."]
        ;; The Figma column-header row above the row
        ;; stack. Rendered only with rows present so the empty state
        ;; stays a clean "No events." message.
        ;; Thread the captured frame-aware dispatcher into
        ;; the header dividers + every row's out-of-render dispatches
        ;; (body-click focus, context menu, col resize) so they land on
        ;; the surrounding instance frame.
        ;; These two sibling keys ride an ATTRIBUTE MAP, never
        ;; `^{:key …}` reader meta. Meta on a vector literal reaches React
        ;; under Reagent and reaches it NOWHERE under Fresco, whose codec
        ;; reads a literal `:key` from the attribute map and Clojure
        ;; metadata not at all. `l2-column-header` is a CALL, so its
        ;; key rides a KEYED FRAGMENT — a fragment
        ;; carries the key and adds no DOM node.
        (list
         [:<> {:key "header"} (l2-column-header col-widths dispatch)]
         (into [:ul {:key "rows"
                     :style {:list-style "none" :margin 0 :padding 0
                             :display "flex" :flex-direction "column"
                             :gap "2px"}}]
               (for [event-bundle event-bundles]
                 (event-row {:event-bundle event-bundle
                             :focused-id   focused-id
                             :auto-track?  auto-track?
                             :col-widths   col-widths
                             :dispatch-fn  dispatch})))))
      ;; LAST child of the scroll container so
      ;; `position: sticky; bottom: 0` pins it to the box's bottom edge
      ;; while the rows scroll under it. It adds no height to
      ;; `list-height-px` and does not scroll the list.
      ;;
      ;; PRESENCE HAS THE SPINE'S DOMAIN, exactly as the count
      ;; does. A guard on `(seq event-bundles)` — the locally rebound
      ;; FILTERED vector — is empty whenever a pill or a mute hides
      ;; every row, so the marker would vanish in the one case it exists
      ;; for: `newer-count` non-zero, L4 still reporting the older pinned
      ;; epoch, and nothing on screen saying so. `spine-event-bundles` is
      ;; the raw vector, where empty means the buffer is genuinely empty —
      ;; the only emptiness worth guarding, and one `stale-read?` already
      ;; covers, since `compose-focus` reports `:head?` true when there is
      ;; no head to miss.
      ;;
      ;; So the marker MAY ride the empty state, and should: `No
      ;; events.` is what the FILTERS show, not what the spine holds, and
      ;; the marker is the only thing that can tell the two apart.
      (when (and stale-read? (seq spine-event-bundles))
        (newer-events-marker newer-count dispatch))]]))

(when rf.interop/debug-enabled?
  (rf/reg-view event-list
    "L2 event list — per spec/018 §4 Event list. Single-line rows,
    latest-on-bottom, ~8 visible at the 22px row height (Xray is
    info-dense; a taller rhythm wastes vertical canvas).

    Container default height: 8 rows × 22px + 7 × 2px gap + 8px outer
    padding ≈ 200px. The live height reads from
    `:rf.xray/events-list-height-px` so the L2/L3 seam
    handle's drag writes lift the list reactively. `min-height` drops
    to `config/min-events-list-height-px` (48px == 2 rows + chrome) —
    the same floor the seam-handle clamp enforces.

    There is no browser-native `:resize \"vertical\"` corner-grip — the
    seam handle that sits on the L2/L3 boundary is the single resize
    affordance, carrying persistence + keyboard + reset that a
    corner-grip lacks.

    Per spec/018 §6 sub-graph: rows render from `:rf.xray/filtered-
    event-bundles` (NOT raw `:rf.xray/event-bundles`) so the events
    ribbon's IN/OUT pills drive the list at the data layer —
    virtualisation budgets the post-filter row count. The `[‹ › »]` nav
    and the newer-events marker walk the RAW spine instead — see
    [[nav-boundary-state]] and [[newer-event-count]].

    ## THE ONE REGION THAT IS AN `rf/reg-view`

    The other five chrome regions are Fresco boundaries. This one is also
    mounted from OUTSIDE any Fresco body: `panels.cljs`'s
    `mount-event-spine!` — a SHIPPED public embed, the L2 spine Story
    mounts beside a focus-keyed panel (`tools/xray/spec/
    008-Embedding-Contract.md` §Embeddable event spine) — passes
    [[event-list-bridge]] to `panels/render-panel!`, which builds
    `[rf/frame-provider … [view]]` and hands it to the installed
    adapter's `:render`. `defview`'s own contract is that a boundary is
    mounted as `[head props]` inside a Fresco body or through
    `as-component` from OUTSIDE, never as a hiccup render fn in a Reagent
    tree — which is exactly what `render-panel!` builds. So the embed
    reaches this view by the bridge name, and `panel_enum.cljc`'s
    `:event-spine` row names `shell/event-list` as a string: that column
    records the VIEW, exactly as the bridged rows `trace/Panel` and
    `resources/Panel` do.

    Making it a boundary is local to this file: swap `rf/reg-view` for
    `rf.fresco/defview`, swap the seven `@(rf/subscribe …)` for
    `rf.fresco/sub`, and make [[event-list-bridge]] the `as-component`
    bridge. The body below is already the thin read-and-call shape every
    boundary has, [[event-list-tree]] is the pure fn, and the node lane's
    door already drives it.

    [[dynamic-chrome]] therefore mounts it as the Dynamic chrome's ONE
    Reagent island, through `substrate/as-element` — unlike the L2/L3
    seam handle, `resize-handle/seam-handle-view`, which is a boundary
    [[dynamic-chrome]] heads directly.

    The list filters out `:ungrouped` event-bundles (those
    with no `:event` vector — registry-time emits / frame lifecycle
    outside a drain / REPL evals) unless `:rf.xray/show-ungrouped?` opts
    in. Without the filter the L2 list would render a leading `<no event>`
    placeholder row that leaks the projection's internal bucket into the
    user-facing event timeline.
    Every other reader of `:rf.xray/event-bundles` itself still gets the
    unfiltered vector — the sub's comment in `registry.cljs` says who
    those readers are — so the bucket remains available where it is
    meaningful.

    The focused row carries a `:ref` callback that
    scrolls it into view when (a) focus has just moved to a new id AND
    (b) the spine is in LIVE+head mode (i.e. the auto-tracking branch
    from `spine/compose-focus`). RETRO clicks place the row where the
    user clicked, so the scroll-into-view is suppressed there to avoid
    stealing the cursor. The container carries
    Firefox's standardised `scrollbar-width`/`-color`; WebKit/Blink
    rules ship via a one-shot `<style>` injection (see
    `inject-scrollbar-style!`).

    The DISPATCHER is `(:dispatch (rf/capture-frame))` — core's own door,
    documented for exactly this position (`re-frame.core/capture-frame`'s
    own example is a `reg-view` body) — rather than the lexically injected
    `dispatch`, so this body is the same shape as its five boundary
    siblings."
    []
    ;; Idempotent stylesheet injection. Lives in the
    ;; view body so it runs on first paint of the L2 list (which is
    ;; mounted by the shell-view); defonce + DOM guards keep it a
    ;; no-op everywhere it matters.
    (inject-scrollbar-style!)
    (event-list-tree
      (:dispatch (rf/capture-frame))
      {;; Read ONCE per L2 paint; thread the resolved
       ;; widths map through to the header + every row so the two
       ;; surfaces never drift out of column alignment.
       :col-widths      @(rf/subscribe [:rf.xray/event-list-col-widths])
       ;; List height is driven by the L2/L3 seam handle.
       ;; The sub returns a clamped px value; default == 200 px.
       :list-height-px  @(rf/subscribe [:rf.xray/events-list-height-px])
       :event-bundles   @(rf/subscribe [:rf.xray/filtered-event-bundles])
       ;; The RAW spine vector, beside the filtered one.
       ;; The newer-events marker's presence and count are derived from
       ;; this; the rows are rendered from the filtered vector above. The
       ;; two must not be conflated — see [[newer-event-count]].
       :spine-event-bundles @(rf/subscribe [:rf.xray/event-bundles])
       ;; The hidden-by-filters message lives in the
       ;; events ribbon (`events-ribbon`), not in the L2 list. The events
       ;; ribbon is the second stratum, so the count surfaces above the
       ;; list rather than as an inline banner inside it.
       :focus           @(rf/subscribe [:rf.xray/focus])
       ;; The STORED slot beside the composed map. Only its
       ;; `:frame` is read: the newer-count's domain is the spine's walk,
       ;; which the picker's stored restriction bounds — never the frame
       ;; the composer resolved the current row in.
       :focus-slot      @(rf/subscribe [:rf.xray/focus-slot])
       ;; Opt-in for the `:ungrouped` pseudo-event-bundle
       ;; bucket. Default OFF keeps silent-by-default; ON
       ;; surfaces the bucket as a muted L2 row that focuses the
       ;; bucket on click so downstream panels populate.
       :show-ungrouped? @(rf/subscribe [:rf.xray/show-ungrouped?])})))

;; ---- the event-spine bridge ----------------------------------------------
;;
;; `panels/mount-event-spine!` mounts the L2 spine BY NAME through
;; `render-panel!`, and passes this name rather than [[event-list]] so the
;; call site never changes with the view's kind: as a `rf.fresco/defview`
;; boundary, [[event-list]] would sit on the natural name and this def
;; would be the real `rf.fresco/as-component` bridge. It is the shape
;; `resources/Panel-bridge` ships — boundary on the natural name, public
;; bridge passed by the caller.
;;
;; WHILE [[event-list]] IS A `reg-view` IT IS A NO-OP. `rf/reg-view` expands to `(def event-list
;; (re-frame.core/view :id))`, so [[event-list]] is a VALUE and this def
;; binds the SAME OBJECT — nothing downstream can tell the two names apart,
;; and `dynamic-chrome`'s Reagent-island mount is unaffected. `render-panel!`
;; always builds the component VECTOR `[panel-view]`, so head position is
;; the only position either name is used in.
(def event-list-bridge
  "The name `panels/mount-event-spine!` mounts the L2 spine through — a
  plain alias of [[event-list]] while that view is a `reg-view`; were it
  a Fresco boundary this would be the `as-component` bridge, with the
  mount facade unmoved. THE NAME STAYS EITHER WAY: `mount-event-spine!`
  reaches it through `render-panel!`, which stays ratom-family, so the
  mount facade needs a name it can pass from a Reagent parent. See the
  comment above."
  event-list)

;; ---- L3 tab bar ----------------------------------------------------------

(defn- tab-button
  "One tab in the L3 tab bar — a ROUNDED-TOP folder tab on the DARK tabs
  ribbon (Figma-Make surface). Each tab is a borderless
  button with `border-radius: 4px 4px 0 0`:

  - the ACTIVE tab carries a LIGHT `chrome-ribbon-tab-active` fill with
    dark `chrome-ribbon-tab-active-text` ink — the lit fill reads as a
    folder tab lifting out of the dark band onto the panel below;
  - INACTIVE tabs carry a faint translucent-white fill
    (`rgba(255,255,255,0.12)`) with muted-white `chrome-ribbon-text-muted`
    ink, so they recede into the dark band.

  The subtle `:hover` lift for inactive tabs lives in
  `theme/global-styles/motion-css` (keyed off the `rf-xray-tab-*`
  testid, since inline styles can't carry a `:hover` pseudo-class). The
  mnemonic letter is exposed via the `title` attribute.

  `aria-label` wraps the visible label as `Xray <tab-label> tab` so the
  button's accessible name never collides with host-app role queries
  (with only `title` set, a query such as Playwright's
  `getByRole('button', {name: '-'})` can match an Xray tab). This
  wrapping is why the app-db tab can safely carry the lowercase library
  label `app-db` — the accessible name is `Xray app-db tab`, not the
  bare `app-db`.

  Each button carries `role='tab'`
  and `aria-selected={active?}` so the tab strip exposes the proper
  ARIA tab pattern. Assistive tech announces the buttons as tabs
  rather than generic buttons and reads the selected state correctly;
  `getByRole('tab')` lookups in host integration tests resolve here.

  `:dispatch-fn` is the frame-aware dispatcher captured by
  the [[tab-bar]] boundary so the select-tab click lands on the instance
  frame, not a `:rf/xray` literal."
  [{:keys [id label mnem active? dispatch-fn]}]
  (let [;; Stable per-tab id so the controlled L4 panel's
        ;; `aria-labelledby` resolves to this button's accessible name.
        dispatch-fn (or dispatch-fn rf/dispatch)
        tab-id   (str "rf-xray-tab-button-" (name id))
        panel-id (str "rf-xray-tabpanel-" (name id))]
    [:button {:data-testid   (str "rf-xray-tab-" (name id))
              :id            tab-id
              :role          "tab"
              :aria-selected (if active? "true" "false")
              :aria-controls panel-id
              :on-click      #(dispatch-fn [:rf.xray/select-tab id])
              :title         (str label " (" mnem ")")
              :aria-label    (str "Xray " label " tab")
              :style {;; ROUNDED-TOP tab on the dark tabs
                      ;; ribbon (Figma-Make surface). ACTIVE → light
                      ;; `chrome-ribbon-tab-active` fill + dark
                      ;; `chrome-ribbon-tab-active-text` ink (the tab
                      ;; "lifts" onto the panel below); INACTIVE →
                      ;; translucent white fill + muted-white ink. Both
                      ;; carry `border-radius: 4px 4px 0 0` so the top
                      ;; corners round like folder tabs. The `:hover` lift
                      ;; for inactive tabs is the scoped rule in motion-css.
                      :background    (if active?
                                       (:chrome-ribbon-tab-active tokens)
                                       "rgba(255,255,255,0.12)")
                      :border        "none"
                      :border-radius "4px 4px 0 0"
                      :color         (if active?
                                       (:chrome-ribbon-tab-active-text tokens)
                                       (:chrome-ribbon-text-muted tokens))
                      :cursor        "pointer"
                      :padding       "4px 16px"     ; rounded-top tab pad
                      :font-family   sans-stack
                      :font-size     (:body type-scale)
                      :font-weight   (if active? 600 400)
                      :white-space   "nowrap"
                      :transition    "background-color 120ms ease-out, color 120ms ease-out"}}
     label]))

(defn tab-bar-tree
  "The L3 tab bar's WHOLE chrome, as a pure function of the frame-bound
  `dispatch` and the four values [[tab-bar]] reads.

  SPLIT OUT OF [[tab-bar]] because a boundary's body may only run inside
  a React render window.

  PURE: `tab-button` is CALLED rather than headed, and answers keyword
  hiccup. The tab INVENTORY comes from `(dynamic-tabs)` — a
  registry read, process-global rather than
  frame-scoped, so it is not one of the boundary's reads."
  [dispatch {:keys [selected observed focus-epoch reset-flash]}]
  (let [can-reset? (some? focus-epoch)]
    [:div {:data-testid "rf-xray-tab-bar"
           :role        "tablist"
           :aria-label  "Xray panel tabs"
           ;; DARK tabs ribbon (Figma-Make surface). The tab
           ;; strip is a dark band carrying rounded-top tab buttons.
           ;; `align-items: flex-end` so each rounded-top tab sits flush
           ;; on the bar's bottom edge (the active tab's light fill reads
           ;; as a folder-tab lifting onto the panel below). `gap: 3px`
           ;; gives the rounded tabs breathing room. Prefixed with the
           ;; `↳ selected` contextual label.
           :style {:display       "flex"
                   :align-items   "flex-end"
                   :gap           "3px"
                   :height        "34px"
                   :padding       "0 12px"
                   :background    (:chrome-ribbon-bg tokens)
                   :border-top    (str "1px solid " (:border-subtle tokens))
                   :border-bottom (str "1px solid " (:border-subtle tokens))}}
     ;; `↳ selected` contextual label (Figma-Make
     ;; tabs ribbon): the corner-down-right glyph + muted-white text on
     ;; the dark band, signalling that the tabs below project the
     ;; CURRENTLY-SELECTED L2 event.
     [:span {:data-testid "rf-xray-tab-bar-context-label"
             :style {:display      "inline-flex"
                     :align-items  "center"
                     :gap          "6px"
                     :align-self   "center"
                     :margin-right "10px"
                     :color        (:chrome-ribbon-text-muted tokens)
                     :font-family  sans-stack
                     :font-size    (:caption type-scale)
                     :white-space  "nowrap"}}
      [:span {:aria-hidden "true"} "↳"]
      ;; The terse `selected` — the glyph + styling already carry the
      ;; "this is the selected event" sense.
      "selected"]
     ;; Iterate `dynamic-tabs` (registry-derived). Tab order follows each
     ;; entry's `:order`.
     ;; KEYED FRAGMENT rather than `^{:key …}` reader meta, which
     ;; Reagent honours and Fresco's codec reads nowhere. `tab-button`'s one
     ;; argument is the tab map itself, so the key does NOT go there: it is
     ;; registry-derived domain data the body destructures, and `:key` is
     ;; React's. The fragment keeps the two apart, and since `tab-button`
     ;; is a CALL there is no head for the key to ride anyway.
     (for [{:keys [id] :as tab} (dynamic-tabs)]
       [:<> {:key id}
        ;; Thread the captured frame-aware dispatcher so the
        ;; tab click's select-tab write lands on the instance frame.
        (tab-button (assoc tab :active? (= id selected) :dispatch-fn dispatch))])
     ;; `margin-left:auto` spacer pushes the Reset cluster to
     ;; the FAR RIGHT of the ribbon, past the tab buttons.
     [:span {:data-testid "rf-xray-tab-bar-spacer"
             :style {:margin-left "auto"}}]
     ;; The inline failure flash (rendered ONLY on failure).
     ;; Sits just LEFT of the Reset button so the operator sees it where
     ;; they clicked. `role=status` so AT announces it; never a modal.
     (when reset-flash
       [:span {:data-testid "rf-xray-reset-flash"
               :role        "status"
               :style {:align-self  "center"
                       :margin-right "8px"
                       :color       (:error tokens)
                       :font-family sans-stack
                       :font-size   (:caption type-scale)
                       :white-space "nowrap"}}
        reset-flash])
     ;; `Reset` rewind button. Dispatches
     ;; `:rf.xray/reset-to-epoch` against the OBSERVED frame + focused
     ;; epoch. Disabled (and visibly dimmed) when no epoch is focused.
     ;; The icon is a unicode anticlockwise-arrow (↺) matching the
     ;; lucide `RotateCcw` rewind glyph used elsewhere; inline SVG isn't
     ;; idiomatic in this pure-hiccup view (see `ribbon-right-icons`).
     [:button {:data-testid "rf-xray-tab-bar-reset"
               :type        "button"
               :disabled    (not can-reset?)
               :title       (if can-reset?
                              "revert the app to this state"
                              "select an event to enable Reset")
               :aria-label  "Reset the app to the selected event's state"
               :on-click    (when can-reset?
                              #(dispatch [:rf.xray/reset-to-epoch observed focus-epoch]))
               :style {:display       "inline-flex"
                       :align-items   "center"
                       :gap           "4px"
                       :align-self    "center"
                       :background    "rgba(255,255,255,0.12)"
                       :border        "none"
                       :border-radius "4px"
                       :color         (:chrome-ribbon-text-muted tokens)
                       :cursor        (if can-reset? "pointer" "not-allowed")
                       :opacity       (if can-reset? 1 0.4)
                       :padding       "3px 10px"
                       :font-family   sans-stack
                       :font-size     (:caption type-scale)
                       :white-space   "nowrap"}}
      "Reset"
      [:span {:aria-hidden "true"} "↺"]]]))

(rf.fresco/defview tab-bar
  "L3 tab bar — renders `panel-registry/tabs-for-mode :dynamic` in
  `:order`, per spec/018 §5 (Routing is a tab per the
  cohesive-sub-domain rule; there is no Issues tab — issues surface
  inline in the Epoch panel + the L2 event-row pink-wash + the
  ribbon).

  ## A FRESCO BOUNDARY, not an `rf/reg-view`

  FOUR READS, ONE BOUNDARY — the selected tab plus the three slots the
  Reset cluster needs. The READS are `rf.fresco/sub`, plain calls the
  shipped collector records an edge for, and their ORDER is the order
  the node lane reproduces. `defview` binds no `subscribe` name, so a
  bare spelling would be a LOUD compile error.

  The wrapping element is a
  generic `<div>` carrying `role='tablist'` — the proper ARIA pattern
  for a tab strip. A `<nav>` would be both semantically wrong
  (tabs aren't site navigation) and a strict-mode hazard for host
  apps that also expose a `<nav>` landmark: Playwright's
  `getByRole('navigation')` lookup would be ambiguous whenever Xray
  mounts alongside a host nav.
  Per-tab buttons carry `role='tab'` + `aria-selected` (see
  `tab-button`). The testid is `data-testid='rf-xray-tab-bar'`.

  ## `Reset` rewind button (far right)

  The ribbon's far-right (after a `margin-left:auto` spacer) carries
  the `Reset` button — the UI half of the inspect-vs-rewind principle.
  It dispatches `:rf.xray/reset-to-epoch` with the OBSERVED frame
  (`:rf.xray/observed-frame` — the frame-switcher selection, NOT
  `:rf/xray`) and the currently-focused epoch-id
  (`:rf.xray/focus-epoch-id`), rewinding that frame's live `app-db` to
  the epoch's `:db-after`. No dialog, no confirmation. Disabled when
  no epoch is focused. On the rare framework failure (epoch aged out /
  restore-during-drain) the effect sets `:rf.xray/reset-flash` — a
  brief inline message, never a modal, never a silent lie.

  The argument is the ordinary one-props-map vector every `defview`
  takes. [[dynamic-chrome]] mounts it with none, so it is destructured
  away."
  [_props]
  (tab-bar-tree
    (:dispatch (rf/capture-frame))
    {:selected    (rf.fresco/sub [:rf.xray/selected-tab])
     ;; Rewind target: the OBSERVED app frame + the
     ;; focused epoch. These resolve off `:rf/xray`'s own app-db where
     ;; the spine lives, but the values they return point at the
     ;; OBSERVED frame.
     :observed    (rf.fresco/sub [:rf.xray/observed-frame])
     :focus-epoch (rf.fresco/sub [:rf.xray/focus-epoch-id])
     :reset-flash (rf.fresco/sub [:rf.xray/reset-flash])}))

;; ---- L4 detail panel -----------------------------------------------------

(defn- unknown-tab-stub
  [selected]
  [:div {:data-testid "rf-xray-tab-unknown"
         :style {:padding "16px"
                 :color   (:text-secondary tokens)
                 :font-family sans-stack}}
   "Unknown tab: " [:code (pr-str selected)]])

(defn detail-panel-tree
  "The Dynamic L4 detail panel's WHOLE chrome, as a pure function of the
  selected tab id, that tab's registry entry (or `nil`) and the
  `as-child` spelling for the L4 REAGENT ISLAND.

  SPLIT OUT OF [[detail-panel]] because a boundary's body may only run
  inside a React render window.

  THE MOUNT IS AN ISLAND. `reg-l4-tab!`'s `:pre` requires `:panel` to be
  CALLABLE, and every Dynamic entry registers a BRIDGE, not the
  boundary behind it: a plain fn answering `[:> Component {}]`, named
  `Panel-bridge` everywhere except `panels/routing.cljs`, whose bridge
  is `Panel` and whose boundary stays private. Every one of them grades
  `:invalid` as a Fresco head, down the same arm. `as-child` is
  `identity` for a hiccup caller and the node lane, which leaves
  `[(:panel tab)]` a plain vector, and `substrate/as-element` for the
  boundary, which answers a React element — a legal child anywhere per
  Fresco's component ABI.

  THE SEAM STAYS — IT IS NOT SCAFFOLDING AWAITING A DELETION. Every
  Dynamic panel is a boundary and the `[:>]` bridges stand beside them,
  so the island is the standing shape and not a stage on the way out of
  one."
  [selected tab as-child]
  [:div {:data-testid (str "rf-xray-detail-panel-" (name selected))
         ;; L4 closes the tab/tabpanel loop. The L3
         ;; tablist owns `role="tablist"` + per-tab `role="tab"` +
         ;; `aria-selected`; the panel completes the WAI-ARIA APG
         ;; tabs pattern with `role="tabpanel"` + `aria-labelledby`
         ;; pointing at the active tab button (per `tab-button`
         ;; the id is `rf-xray-tab-button-<tab-id>`).
         :id              (str "rf-xray-tabpanel-" (name selected))
         :role            "tabpanel"
         :aria-labelledby (str "rf-xray-tab-button-" (name selected))
         :style {:flex        "1 1 auto"
                 :min-height  "0"
                 :overflow    "auto"
                 :background  (:bg-2 tokens)
                 :color       (:text-primary tokens)}}
   ;; Re-mount on selected-tab change so the fade-in
   ;; keyframes auto-play.
   ;;
   ;; The remount key rides the ATTRIBUTE MAP below, not
   ;; `^{:key selected}` reader meta on the vector literal. Reagent's
   ;; `get-react-key` reads that meta — but Fresco's codec takes a
   ;; literal `:key` from the attribute map and reads Clojure metadata
   ;; nowhere, so under a boundary the wrapper would keep ONE identity
   ;; across every tab change and the fade would simply stop playing:
   ;; no error, no warning, just an animation that never re-triggers.
   ;; The attribute map is honoured by both substrates.
   [:div {:key         selected
          :data-testid (str "rf-xray-detail-panel-fade-"
                            (name selected))
          :style {:height     "100%"
                  ;; Keyframes named in `global-styles/motion-css`.
                  ;; Duration interpolated through the
                  ;; `--rf-xray-motion-scale` seam
                  ;; via `theme.tokens/duration-css` so the
                  ;; 180ms constant + the seam-var name both live
                  ;; in tokens.cljc — one source of truth.
                  ;; `forwards` pins the end state (opacity 1) so
                  ;; the panel stays visible after the fade settles.
                  :animation  (str "rf-xray-fade-in "
                                   (duration-css (:fade-duration-ms motion))
                                   " ease-out forwards")}}
    ;; Registry-driven panel mount. Each tab's per-panel
    ;; `install!` declares `:panel <view-fn>` via
    ;; `panel-registry/reg-l4-tab!`. A lookup against
    ;; `tab-by-id :dynamic` has no inventory to keep in step (a literal
    ;; case-switch here would go stale on every tab added or retired):
    ;; EVERY registered tab's view fn lives colocated with
    ;; the panel's own subs / events / fxs in `panels/<panel>.cljs`.
    (if tab
      (as-child [(:panel tab)])
      (unknown-tab-stub selected))]])

(rf.fresco/defview detail-panel
  "L4 detail panel — mounts the active `:rf.xray/selected-tab`'s panel
  via the registry-driven `panel-registry/tab-by-id :dynamic` lookup.
  Every registered Dynamic tab mounts a real panel — the
  inventory is the registry's, not a list here. There is no Issues tab
  — issues surface inline + event-row + ribbon.
  An unrecognised tab falls back to `unknown-tab-stub`.

  ## A FRESCO BOUNDARY, not an `rf/reg-view`

  ONE READ, ONE BOUNDARY. The READ is `rf.fresco/sub`. It is its OWN
  boundary rather than folded into [[dynamic-chrome]] because the L4
  mount is the most expensive subtree in the surface, and hoisting the
  tab read above it would re-render the ribbon, the events ribbon, the
  L2 list and the tab bar with it. Boundary count tracks reads.

  `substrate/as-element` is the `as-child` spelling for the L4 island;
  [[detail-panel-tree]] records why it is still an island.

  The wrapping `<div>` paints `bg-2` as a contrast safety net
  (defence-in-depth for panels that fail to set their own
  background).

  ## 180ms cross-fade on tab switch

  Spec/007 §Motion + animation calls for a 180ms cross-fade when the
  user switches L4 tabs. The registry mount below is otherwise an
  instant DOM swap. The trick: wrap the chosen panel in an inner `<div>`
  *keyed on `selected`*. When the key changes React unmounts the
  previous wrapper + mounts a new one, which auto-plays the
  `rf-xray-fade-in` CSS animation declared in
  `theme/global-styles/motion-css`. Duration is interpolated through
  the `--rf-xray-motion-scale` seam so the fade
  collapses to 0ms under `prefers-reduced-motion: reduce`.

  The outer `<div>` keeps its `data-testid` stable across tab swaps so
  tests + `getByTestId` lookups resolve — the cross-fade
  wrapper is purely internal.

  The argument is the ordinary one-props-map vector every `defview`
  takes. [[dynamic-chrome]] mounts it with none, so it is destructured
  away."
  [_props]
  ;; No fallback: the sub is total (`registry.cljs` reads
  ;; `(get db :selected-tab :epoch)`).
  (let [selected (rf.fresco/sub [:rf.xray/selected-tab])]
    (detail-panel-tree selected
                       (panel-registry/tab-by-id :dynamic selected)
                       substrate/as-element)))

;; ---- Dynamic / Static surface composer ----------------------------------
;;
;; The shell exposes TWO modes (Dynamic — the 4-layer chrome below,
;; Static — the 3-layer registry-browse surface owned by
;; `static/shell.cljs`). The composer reads `:rf.xray/mode` and
;; renders either Dynamic or Static. Static mode is unconditionally
;; available, ungated.
;;
;; The composer is a Fresco BOUNDARY; its read is
;; `rf.fresco/sub` and both arms are boundary heads.

(defn dynamic-chrome-tree
  "The Dynamic chrome's outer envelope, as a pure function of its five
  already-composed layers plus the L2/L3 seam handle. Genuinely shared
  between the two lanes rather than reproduced for them:
  [[dynamic-chrome]] passes the five BOUNDARY-headed vectors and
  `test-helpers.dynamic-shell-tree` passes the layers' already-expanded
  plain hiccup, so a node-lane row that walks this envelope walks the
  real thing.

  SPLIT OUT OF [[dynamic-chrome]]."
  [ribbon* events-ribbon* event-list* seam-handle* tab-bar* detail-panel*]
  [:div {:data-rf-xray-dynamic-chrome ""
         :style {:display "contents"}}
   ribbon*
   events-ribbon*
   event-list*
   ;; L2/L3 seam handle. Click-and-drag anywhere along the
   ;; horizontal seam between the event list and the tab bar resizes
   ;; the events list, per spec/007-UX-IA.md §Splitter affordance.
   seam-handle*
   tab-bar*
   detail-panel*])

(rf.fresco/defview dynamic-chrome
  "The Dynamic chrome wrapped as a single component. The
  top splits into two strata reconciled to the authority reference — the
  **chrome ribbon** (`ribbon`, bar-1: `Event History` label +
  blue-filled nav + add(+) on the left; Frame + Dynamic/Static
  dropdowns + indicators + theme toggle + `⛶`/`⚙`/`✕` on the right) and
  the **events ribbon** (`events-ribbon`, bar-2: the
  `↳ filters:` label + add(+) + the green/red committed pills, with the
  `N events filtered out` warning on the right when N > 0) — above the
  L2 event list, L3 tab bar, and L4 detail panel. A component of its own,
  apart from `shell-view`, so the Static surface can swap in alongside it
  via the mode composer.

  ## A FRESCO BOUNDARY, not an `rf/reg-view`

  IT READS NOTHING and is a boundary anyway, for the reason
  `static/shell.cljs`'s `surface` is: a boundary is the only legal
  hiccup head for the five region boundaries under it, so this is where
  the composition has to live.

  ONE REAGENT ISLAND, an `rf/reg-view`, reached through
  `substrate/as-element` (the node lane's door passes `identity`, so
  it stays the fn-headed vector a hiccup walker expands):

    * [[event-list]] — the shipped embed reaches it through
      [[event-list-bridge]], which `panels.cljs`'s `mount-event-spine!`
      passes; [[event-list]]'s own docstring says what making it a
      boundary involves.

  THE SEAM IS NOT AN ISLAND: `resize-handle/seam-handle-view` is an
  ordinary BOUNDARY HEAD here, with no `as-element` crossing. `Handle`
  keeps a bridge in its own file because a Reagent parent heads it on
  purpose; the seam needs none, because this view heads it directly.

  The argument is the ordinary one-props-map vector every `defview`
  takes. [[surface-composer]] mounts it with none, so it is destructured
  away.

  ## DOM-rooted via `display: contents`

  The five children are stacked flex items of `shell-view`'s flex
  column (L1 ribbon · events ribbon · L2 list · L3 tab bar · L4 detail
  panel). A bare React Fragment root would skip the source-coord DOM
  annotation (Spec 006 §Documented exemption: non-DOM roots) and emit
  a one-shot warning. A wrapper `<div>` with `display: contents`
  participates in the DOM tree (so `data-rf2-source-coord` has a home
  and click-to-source works) while being neutralised for layout — the
  children render as if they were direct flex items of the
  shell-view's column. The `data-rf-xray-dynamic-chrome` attr is a
  test-friendly handle if a selector needs it; no production
  code reads it."
  [_props]
  (dynamic-chrome-tree [ribbon {}]
                       [events-ribbon {}]
                       (substrate/as-element [event-list])
                       [resize-handle/seam-handle-view {}]
                       [tab-bar {}]
                       [detail-panel {}]))

(defn surface-composer-tree
  "The mode composer's envelope, as a pure function of the mode and its
  two already-composed arms.

  SPLIT OUT OF [[surface-composer]]."
  [mode static* dynamic*]
  [:div {:data-rf-xray-surface-composer ""
         :style {:display "contents"}}
   (case mode
     :static static*
     dynamic*)])

(rf.fresco/defview surface-composer
  "Mode-aware composer. Reads `:rf.xray/mode` and renders
  either the Dynamic 4-layer chrome OR the Static 3-layer surface.

  Static mode is unconditionally available; the active mode drives
  the swap.

  ## DOM-rooted via `display: contents`

  Returning the inner component head directly (`[dynamic-chrome]` /
  `[static-shell/surface]`) would skip the source-coord DOM
  annotation (Spec 006 §Documented exemption: component head) and
  emit a one-shot warning. A `display: contents` wrapper lets
  `data-rf2-source-coord` land on a real DOM node while keeping the
  inner surface as the effective layout child of `shell-view`'s flex
  column.

  ## BOTH ARMS ARE BOUNDARY HEADS

  This composer is itself a `rf.fresco/defview`, so
  `static-shell/surface` is a legal hiccup head here and no
  `as-component` bridge sits between them. One crossing remains for the
  whole Dynamic tree and it sits one level UP, at [[shell-view]], the
  public callable Reagent callers mount through — Xray's own mount
  heads [[ShellView]] through a Fresco root it owns and crosses nothing.

  ONE READ, ONE BOUNDARY, and it is the right place for it: the mode
  read swaps the entire surface, so nothing below needs to see it.

  The argument is the ordinary one-props-map vector every `defview`
  takes. [[ShellView]] heads it with none, so it is destructured away."
  [_props]
  (surface-composer-tree (rf.fresco/sub [:rf.xray/mode])
                         [static-shell/surface {}]
                         [dynamic-chrome {}]))

;; ---- shell view ----------------------------------------------------------

(defn shell-view-tree
  "The shell's outer envelope — the root `<div>`, the
  `rf.fresco/frame-provider`, the left-edge resize handle and the seven
  shell-root modal / popover mounts — as a pure function of its resolved
  opts, the live lens mode and its ONE already-composed surface node.

  SPLIT OUT OF [[shell-view]]. Genuinely shared between the
  two lanes rather than reproduced for them: [[ShellView]] passes
  `[surface-composer {}]` and `test-helpers.dynamic-shell-tree` passes the
  chrome's already-expanded plain hiccup, so a node-lane row that walks
  this envelope walks the shipped definitions of the testids, the flex
  column, the landmark role and every modal mount.

  ## THIS IS A FRESCO TREE, AND THAT IS WHY THE MOUNTS
  ## BELOW ARE CALLS RATHER THAN HEADS

  [[ShellView]] is a `rf.fresco/defview`, so everything this fn returns is
  lowered by Fresco's codec. A PLAIN FUNCTION IN HEAD POSITION IS A LOUD
  ERROR THERE (`:rf.error/fresco-bad-head`, HD-016), and every name below
  is exactly that: each modal's public `Modal` / `Popup` / `Toast` /
  `Popover` is the bridge its own file keeps — a plain fn
  answering `[:> …]` over `rf.fresco/as-component` — because a Reagent
  parent heads it elsewhere (`panels.cljs`'s `render-panel!`, and
  each file's own boundary witness suite). So this file CALLS them
  rather than heading them: `(palette/Modal)` answers the `[:> …]`
  escape, which IS a legal Fresco head form. That is the call-site rule
  for a shared plain fn — CALL it, which is correct on both
  substrates — and it is what Fresco's own refusal message
  prescribes: *call it, or make it a view*.

  A REG-VIEW CENSUS CANNOT SEE THIS. None of these names is an
  `rf/reg-view` — each is a plain-fn bridge, which grades `:invalid` as
  a head down the identical arm. So an absence of reg-views is not
  evidence that the heads are legal; only the head kind is.

  `resize-handle/Handle` keeps its positional `mode` argument for the
  reason its own docstring gives — `as-component` round-trips prop names
  but not prop VALUES, and `mode` is a keyword — so it is called with it,
  and answers nil outside `:inline`."
  [{:keys [mode modal-positioning lens-mode frame-id]} surface*]
   ;; The outer `<div>` IS the shell-view's root so the
   ;; source-coord walk has a DOM node to annotate (Spec 006
   ;; §Source-coord annotation; a non-DOM `frame-provider`
   ;; component-head root would warn-once).
   ;; The frame-provider sits one level inside, wrapping every
   ;; subscribing child — React-context discipline is preserved. The
   ;; outer `<div>` carries attrs/styles only; it never subscribes,
   ;; so its position outside the frame is immaterial.
   [:div {:data-testid "rf-xray-shell"
          :class (str "mode-" (name (or lens-mode :dynamic)))
          ;; Xray shell root is a landmark. A 40%-
          ;; viewport overlay rendered as a bare `<div>` is invisible
          ;; to screen-reader landmark navigation (JAWS R-key /
          ;; NVDA D-key). `role="region"` + `aria-label` exposes the
          ;; shell as a labelled landmark so AT users can jump to it.
          ;; "region" (rather than "complementary" / "aside") because
          ;; Xray is a global chrome
          ;; surface with its own internal landmark structure (L1
          ;; ribbon = toolbar, L3 = tablist, L4 = tabpanel) rather
          ;; than content complementary to the host's main.
          :role        "region"
          :aria-label  "Xray devtools"
          ;; The spec-published mode axis is
          ;; `data-rf-xray-mode` (mount.cljs writes it on both the
          ;; root and the shell node). There is no unprefixed
          ;; `data-mode` echo — tests + testbeds read
          ;; the rf-xray-prefixed name everywhere.
          :data-rf-xray-mode (name mode)
          ;; The positioning attribute is published on the
          ;; shell root for testbed assertions; the modals read the
          ;; sub directly rather than via DOM lookup.
          :data-rf-xray-modal-positioning (name modal-positioning)
          :style       (merge
                         {:width            "100%"
                          :height           "100%"
                          :min-height       "100vh"
                          :display          "flex"
                          :flex-direction   "column"
                          :background       (:bg-0 tokens)
                          :color            (:text-primary tokens)
                          :font-family      sans-stack
                          :font-size        (:body type-scale)
                          :line-height      (:line-height-tight type-scale)}
                         (case mode
                           :inline
                           {:position   "relative"
                            :min-width  "320px"
                            :box-shadow "rgba(0, 0, 0, 0.28) 8px 0 20px"}

                           :popout
                           {:position "relative"}

                           {:position   "fixed"
                            :top        0
                            :right      0
                            :bottom     0
                            :width      "40%"
                            :min-width  "560px"
                            :z-index    2147483000
                            :box-shadow "rgba(0, 0, 0, 0.4) -8px 0 24px"}))}
    ;; Frame-provider sits INSIDE the outer `<div>` (the
    ;; `<div>` carries the source-coord annotation as the DOM root).
    ;; Every subscribing child below is wrapped so the instance
    ;; `frame-id` flows through React-context (the provider
    ;; frame is the parameterized instance frame, default `:rf/xray`).
    [rf.fresco/frame-provider {:frame frame-id}
    ;; Left-edge horizontal resize handle — only renders
    ;; in `:inline` (right-rail) mode. Position-absolute pins it to
    ;; the LEFT edge of this flex container; the outer div is
    ;; `position: relative` in :inline so the handle's anchor
    ;; resolves correctly. The handle's drag math writes through
    ;; `:rf.xray/set-panel-width-px`, which clamps + persists +
    ;; pushes `--rf-xray-inline-width` onto the layout host so the
    ;; host's `flex-basis` re-evaluates this paint.
    (resize-handle/Handle mode)
    ;; Mode-aware surface. The composer reads
    ;; `:rf.xray/mode` and renders either the Dynamic 4-layer
    ;; chrome or the Static 3-layer surface; Static mode is
    ;; unconditionally available.
    ;;
    ;; `surface-composer` is a Fresco BOUNDARY and so is
    ;; this tree's own head, [[ShellView]], which passes
    ;; `[surface-composer {}]` in already-composed. Nothing crosses out
    ;; of Fresco and back in here, so there is no `[:>]` head for a
    ;; hiccup walk to stop at. What this view owes is that the surface
    ;; mounts; the chrome's own composition is
    ;; `test-helpers.dynamic-shell-tree`'s subject in the node lane and
    ;; `shell_fresco_boundary_dom_cljs_test`'s in the browser.
    surface*
    ;; Command palette — mounted at shell root so it
    ;; overlays the chrome. Modal short-circuits to nil when
    ;; `:rf.xray/palette-open?` is false; closed-state cost is one
    ;; subscribe + when-gate.
    (palette/Modal)
    ;; Filter edit popup — mounted at shell root so it
    ;; overlays the chrome AND the palette modal (the popup's z-index
    ;; is one above the palette so an edit opened from a palette
    ;; context wins focus). Modal short-circuits to nil when
    ;; `:rf.xray/edit-popup-open?` is false; closed-state cost is
    ;; one subscribe + when-gate.
    (filters/Modal)
    ;; Settings popup — same mount discipline as the
    ;; palette + edit popup: shell-root mount so subscribes resolve
    ;; through the shell's `:rf/xray` frame-provider, and the modal
    ;; short-circuits to nil when `:rf.xray/settings-open?` is false.
    (settings-popup/Modal)
    ;; Open-in-editor 'pick an editor in Settings' hint toast.
    ;; Same shell-root mount discipline as the modals so
    ;; its subscribe resolves through the `:rf/xray` frame-provider; the
    ;; toast is `position: absolute` in the bottom-right corner and
    ;; short-circuits to nil when `:rf.xray/editor-hint-open?` is false.
    ;; Shown when an open-in-editor chip is clicked but no editor is
    ;; effectively configured (host never set `:rf.xray/editor`, no
    ;; operator override) — instead of the silent `vscode:` no-op.
    (editor-hint/Toast)
    ;; Cancellation-cascade popover — single waterfall view
    ;; of the cancellation contract. Opened from the Trace tab
    ;; (right-click a destroy-event row → 'Show cancellation event-bundle')
    ;; or imperatively via `:rf.xray/cancellation-cascade-open`. Same
    ;; mount discipline as the other popovers: shell-root mount so
    ;; subscribes resolve through the `:rf/xray` frame-provider;
    ;; closed-state cost is one subscribe + a when-gate.
    (cancellation-cascade/Popover)
    ;; Mute manager modal — lists every muted event-id
    ;; with per-row unmute buttons + a 'Unmute all' affordance. Same
    ;; mount discipline as the other modals: shell-root mount so the
    ;; subscribes resolve through the `:rf/xray` frame-provider;
    ;; closed-state cost is one subscribe + a when-gate.
    (spine-filters/Modal)
    ;; Row context menu — small floating popover opened
    ;; by right-click on an L2 event row. Carries 'Mute <event-id>'
    ;; + 'Always hide this event-type…'. Mounted at shell-view root
    ;; so the menu floats above the L2 list's overflow:hidden
    ;; clipping. Closed-state cost is one subscribe + a when-gate.
    (spine-filters/RowContextMenu)
    ;; Data-display popup stack — overlay surface for the
    ;; "open in popup" affordance on per-panel `[ei/edn-inspector]`
    ;; mounts. Reads `:rf.xray.edn-inspector-popup/stack` + `/entries`;
    ;; renders nothing when the stack is empty (closed-state cost is
    ;; one subscribe + a when-gate). Mount discipline matches the
    ;; other modal stacks: shell-root mount so the stack's subscribes
    ;; resolve through the shell's `:rf/xray` frame-provider.
    (edn-inspector-popup/edn-inspector-popup-stack)]])

(rf.fresco/defview ShellView
  "The full Xray shell as a FRESCO BOUNDARY — the head `mount.cljs`
  renders through Xray's OWN React root. Wraps the 4-layer chrome in a
  frame-provider so descendant reads
  and dispatches resolve to the isolated frame. Default `:inline` mode
  renders in normal document flow inside the app-provided right layout
  host; `:overlay` and `:popout` remain available debug/manual modes.

  ## A boundary on Xray's own root

  Fresco paints this boundary through its own root rather than the
  INSTALLED ADAPTER's `:render`, so Xray does not need the host to own a
  renderer that accepts hiccup. [[shell-view]] below keeps the public
  NAME as a callable bridge for every Reagent caller (`panels.cljs`'s
  `mount-shell!`, the panel-gallery testbed).

  ## THE PROVIDER ABOVE THIS HEAD IS PART OF THE CONTRACT

  Both reads below are `rf.fresco/sub`, so they take their frame from
  REACT CONTEXT rather than from an argument, and they must resolve to
  the SAME frame as `:frame-id`. Both doors honour that by construction
  and neither may drop it:

    * `mount.cljs` renders `[rf.fresco/frame-provider {:frame
      shell/default-frame-id} [ShellView {:mode …}]]`, and passes no
      `:frame-id`, so the default IS that frame.
    * [[shell-view]] wraps this head in a provider naming the very
      `:frame-id` it forwards.

  AN EXPLICITLY-FRAMED `rf/subscribe` IS NOT AN ALTERNATIVE HERE, and the
  failure is silent. `(rf/subscribe q {:frame frame-id})` is admitted
  inside a body and answers the right value, but it contributes ZERO
  collector edges (HD-002 clause (a)'s forbidden class) — so the shell
  would paint correctly on first render and then NEVER RE-RENDER when
  `:rf.xray/mode` moved. Nothing errors. Dropping the provider instead is
  LOUD (`:rf.error/no-frame-context`), which is why the provider is the
  contract and the ambient read is the mechanism.

  ## `:frame-id` opt — per-instance shell frame

  The shell's app-db lives in a frame. The PRODUCTION singleton mounts
  against `default-frame-id` (`:rf/xray`) — pass no `:frame-id` and the
  shell uses it. Testbeds that mount N shells
  side-by-side (the panel-gallery `:variants-grid`, a Story workspace)
  pass DISTINCT `:frame-id`s so each cell's state (focused epoch,
  selected tab, theme) is fully isolated — driving one shell does not
  move the others. Handlers register GLOBALLY once under `:rf.xray/*`;
  only the frame-id for app-db isolation threads through (no
  per-instance registration).

  ## `:modal-positioning` opt

  Default `:fixed` — modal backdrops use `position: fixed; inset: 0`
  with max-int z-indexes so they cover the entire host viewport. The
  right shape for production where the shell IS the global overlay.

  Story testbeds that mount N shell cells side-by-side pass
  `:modal-positioning :absolute` so each cell's modals stay confined
  to the cell (backdrop becomes `position: absolute; inset: 0` with
  a sane z-index of 100). The cell wrapper must establish a
  positioning context (`position: relative`) for the absolute backdrop
  to be contained — `:inline` mode already sets that on the shell's
  outer `<div>`, so the contract is satisfied out of the box.

  Note: with `:absolute` positioning the modals are visually contained
  per-cell. The open-state flags (`:rf.xray/<modal>-
  open?`) are also per-instance — pass a distinct `:frame-id` per
  cell and opening Settings in one cell opens Settings in that cell
  only. (Cells that share a frame-id still share state — that's the
  contract: one frame, one app-db.)

  ## The two render-phase side effects, both deliberate

  `global-styles/install!` runs during render, which two testbeds rely
  on; it is idempotent (`defonce` plus an id-keyed
  DOM probe). The `dispatch-sync` is safe under a boundary for a reason
  that is the collector's rather than this view's: `flush!` defers
  notification to a macrotask while a body is running
  (`impl/collector.cljs`), so the write cannot re-enter this render. The
  `when` guard is what makes it quiesce — once the slot matches the prop
  it short-circuits."
  [{:keys [mode modal-positioning frame-id]
    :or   {mode :inline modal-positioning :fixed
           frame-id default-frame-id}}]
  ;; Wire Inter + JetBrains Mono once on first paint of
  ;; the shell. Idempotent (`defonce` + id-keyed DOM probe inside) so
  ;; shadow-cljs `:after-load` and repeated mounts are no-ops.
  (global-styles/install!)
  ;; Idempotent app-db write so every modal can read the positioning
  ;; via the `:rf.xray/modal-positioning` sub. Guarded against
  ;; re-dispatch by comparing the current slot to the prop — once the
  ;; slot matches the prop, the `when` short-circuits and the render
  ;; quiesces. `dispatch-sync` so the slot lands BEFORE the modal
  ;; children mount and read the sub on this same render pass; without
  ;; sync the first paint of a fresh shell would render every modal's
  ;; backdrop at the default `:fixed` before the async router drains.
  ;; The READ is ambient (the enclosing provider's frame, which is
  ;; `frame-id` — see the docstring); the WRITE names `frame-id`
  ;; explicitly because `dispatch-sync` is not a collector read and has
  ;; no frame of its own. That is the instance frame-id,
  ;; never a `:rf/xray` literal — N shells stay isolated.
  (let [current-positioning (rf.fresco/sub [:rf.xray/modal-positioning])]
    (when (not= current-positioning modal-positioning)
      (rf/dispatch-sync [:rf.xray/set-modal-positioning modal-positioning]
                        {:frame frame-id})))
  (shell-view-tree
    {:mode              mode
     :modal-positioning modal-positioning
     :frame-id          frame-id
     ;; spec/022 — the lens mode (`:rf.xray/mode` =
     ;; :dynamic | :static) drives the `mode-dynamic` / `mode-static`
     ;; root class, which gates functional behaviour (motion /
     ;; pulse dampening in Static). The Figma export
     ;; carries a SINGLE accent (GitHub blue) — the mode class does not
     ;; re-point `--rf-xray-accent`, so the chrome accent is the same
     ;; blue in both modes. An AMBIENT collector read, so the shell
     ;; actually re-renders when the mode moves.
     :lens-mode         (rf.fresco/sub [:rf.xray/mode])}
    [surface-composer {}]))

(defn shell-view
  "The shell's public callable — the name every REAGENT caller
  holds, and the one door `panels.cljs`'s `mount-shell!` and
  `testbeds/panel_gallery` mount through.

  It is the bridge rather than the view:
  [[ShellView]] is the boundary, and this answers the React element it
  lowers to, already scoped to its frame.

  ## Why `rf.fresco/as-element` and not `rf.fresco/as-component`

  `as-component` is the tree's usual outward door and it is the WRONG one
  here, for a reason its own contract states: a Reagent parent's `[:>]`
  runs Reagent's `convert-prop-value` first, so NAMES round-trip across
  that crossing and VALUES do not. Every opt this view takes is a
  KEYWORD — `:inline`, `:fixed`, `:rf/xray` — and each would arrive as a
  bare string, which for `:frame-id` means naming a frame that does not
  exist. `resize-handle/Handle` met the same wall and answered it by
  deciding in CLJS and crossing with no props; this view cannot, because
  the opts are what it is parameterised BY.

  `as-element` is Fresco's own explicit hiccup-to-ReactNode conversion,
  so the props map crosses as a CLJS value by identity
  (`codec/boundary-element` hands it over as `rfProps`) and the keywords
  arrive as keywords. It mints nothing — the component type is
  [[ShellView]]'s own, minted once by `defview` — so the once-at-top-level
  rule `as-component` carries does not apply and no parent render can
  remount the shell.

  ## The provider is HERE rather than in the caller

  `mount-shell!` adds no outer provider of its own — `shell-view` opens
  one around `frame-id`. Opening it here means every
  Reagent embed gets the instance frame for free, including [[ShellView]]'s
  own two ambient reads, whatever frame the host happens to have in
  scope. `shell-view-tree` opens a second provider at the same frame one
  level down; that one is what the node lane walks, and a re-scope to the
  frame already in scope costs a context read."
  [& [{:keys [mode modal-positioning frame-id]
       :or   {mode :inline modal-positioning :fixed
              frame-id default-frame-id}}]]
  (rf.fresco/as-element
    [rf.fresco/frame-provider {:frame frame-id}
     [ShellView {:mode              mode
                 :modal-positioning modal-positioning
                 :frame-id          frame-id}]]))
