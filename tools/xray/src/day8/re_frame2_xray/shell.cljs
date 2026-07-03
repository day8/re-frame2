(ns day8.re-frame2-xray.shell
  "The Xray shell — 4-layer chrome per `tools/xray/spec/018-Event-Spine.md`
  §2 The 4-layer chrome.

  ## Layout

  Per spec/018 the chrome is four stacked layers — the legacy
  16-panel sidebar + bottom rail (spec/007's original §The five
  regions) is dead.

      ┌───────────────────────────────────────────────────────┐
      │ L1  Top ribbon (56px)                                 │  scope controls
      ├───────────────────────────────────────────────────────┤
      │ L2  Event list (8 rows default; resizable; min 2)     │  the spine / timeline
      ├───────────────────────────────────────────────────────┤
      │ L3  Tab bar (40px) — 6 tabs                           │  projection selector
      ├───────────────────────────────────────────────────────┤
      │ L4  Detail panel (fills remaining canvas)             │  per-tab content
      └───────────────────────────────────────────────────────┘

  L1 / L3 are fixed-height; L2 takes a default 8-row height and is
  user-resizable via the L2/L3 boundary drag handle; L4 takes the
  remainder. Only L2/L3 carries a drag handle.

  ## Ribbon clusters (L1)

  Four clusters, fixed order left → right per spec/018 §3 (Round-3
  rf2-g9pee — the explicit `● LIVE` / `◐ RETRO` mode pill was dropped;
  the spine's mode is already derivable from sticky-row selection +
  the `[◀ ▶ ⏭]` cluster + the `:rf.xray/focus` sub. Space / L / G
  keybindings preserve the toggle access the pill used to surface):

  - **Nav** (`◀ ▶ ⏭`) — back / forward / fast-forward through the
    spine. Dispatches `:rf.xray/focus-event-prev` / `-next` /
    `:rf.xray/follow-head`. Pressing `⏭` (or `Space` in paused-LIVE,
    or `L` in RETRO) snaps focus back to head — the operations the
    mode pill used to host.
  - **Frame picker** — single-select dropdown over the cascade list's
    distinct frames. Excludes `:rf/xray` by default per §8 I1.
  - **Filter pills** — IN (green, `+`) and OUT (magenta, `×`) pills
    + trailing `[+]` add-pill. Click any pill → edit popup.
  - **Right icons** — `⛶` pop-out · `⚙` settings · `✕` close. The
    `⛶` button is the canonical chrome launch for the second-window
    pop-out mode (rf2-czcg5, spec/011-Launch-Modes.md); `(xray/popout!)`
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

  Six tabs, mnemonic letters per spec/018 §11:

      Event (e) · App-db (a) · Views (v) · Trace (t) · Machines (m) · Routing (r)

  Selection lives on `:rf.xray/selected-tab` and drives the L4
  detail panel's case switch. Routing was promoted to its own tab
  per rf2-nrbs9 (Mike's design call, 2026-05-18) — it follows the
  cohesive-sub-domain rule (sub-domains earn their own lens tab
  rather than overloading the parent tab).

  The Issues tab was REMOVED per rf2-gbz39 (Mike RULED Option (c),
  2026-05-31). Issues no longer have a dedicated aggregate tab — they
  surface inline in the Epoch panel (per-step pass/fail + exception
  block, rf2-ahhgn; `:db` schema-fail in the SIDE EFFECTS step,
  rf2-kt6js; slow-fx amber), via the L2 event-row pink-wash
  (rf2-b8guz — rows whose epoch has an issue), and via the always-on
  issues ribbon signal (the auto-open-on-error watcher reading the
  surviving `:rf.xray/issues-ribbon` composite). The session-wide
  aggregate / triage list the tab used to provide was consciously
  dropped under (c).

  ## Detail panel (L4)

  Renders the active tab's projection of the focused event. The L4
  panel is registry-driven (rf2-2moh1): each tab registers its
  `:panel` via `panel-registry/reg-l4-tab!` and the shell mounts the
  active tab through `panel-registry/tab-by-id`. Post rf2-5gl5r +
  rf2-gbz39 the six Dynamic tabs all mount real panels — Epoch →
  `epoch-panel/Panel` (the canonical numbered cascade per 021 §9.1;
  supersedes the retired Event/Handler panel), App-db →
  `app-db-diff/Panel`, Views → `reactive-panel/Panel` (the 021 §3
  three-stacked-tables design, rf2-8ve8z), Trace → `trace/Panel`,
  Machines → `machine-inspector/Panel`, Routing → `routing/Panel`.

  ## Frame isolation (rf2-tijr Option C + rf2-in6l2)

  The shell is wrapped in `[rf/frame-provider {:frame :rf/xray}]`.
  Every `subscribe` / `dispatch` inside the shell resolves to the
  `:rf/xray` frame; the host's `:rf/default` is untouched. Xray's
  own registrations under `:rf.xray/*` operate against `:rf/xray`'s
  db when called from inside the shell.

  Per rf2-in6l2 every subscribing region of the shell is `reg-view`-
  registered so its rendered React component carries `:contextType
  frame-context` — the closest enclosing Provider's `:rf/xray`
  flows through React-context and `(rf/subscribe …)` inside the body
  resolves to the registered frame. With plain `defn`s the
  React-context tier would be skipped (Spec 004 §Plain Reagent fns
  do not pick up the surrounding frame) and subscribe would fall
  through to `:rf/default` — silently routing every Xray panel
  query into the host's app-db.

  ## Pure hiccup

  Per rf2-tijr the view code is pure hiccup. The substrate adapter's
  render fn (`rf/render`) handles the substrate-specific mount in
  `mount.cljs`. No per-substrate switches in view code."
  (:require [clojure.string :as str]
            [re-frame.core :as rf]
            [re-frame.interop :as interop]
            [day8.re-frame2-xray.config :as config]
            [day8.re-frame2-xray.defaults :as defaults]
            [day8.re-frame2-xray.filters :as filters]
            [day8.re-frame2-xray.filters.pills :as filter-pills]
            [day8.re-frame2-xray.frame-switcher :as frame-switcher]
            [day8.re-frame2-xray.panel-registry :as panel-registry]
            [day8.re-frame2-xray.panels.common-helpers :as common]
            [day8.re-frame2-xray.panels.app-db-segment-inspector
             :as app-db-segment-inspector]
            [day8.re-frame2-xray.panels.cancellation-cascade :as cancellation-cascade]
            ;; Panel views (Event / App DB / Views / Trace / Machines /
            ;; Routing / Issues) are pulled in via the L4 tab registry —
            ;; each panel's `install!` registers `{:panel <view-fn>}`
            ;; with `panel-registry/reg-l4-tab!` (rf2-2moh1) and
            ;; `detail-panel` reaches the entry through
            ;; `panel-registry/tab-by-id`. The shell no longer requires
            ;; those panel nses directly.
            ;; rf2-ad7zx.12 — L2 `source` column dispatch-origin tag.
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
            [day8.re-frame2-xray.spine-filters :as spine-filters]
            [day8.re-frame2-xray.static.mode-pill :as mode-pill]
            [day8.re-frame2-xray.static.shell :as static-shell]
            [day8.re-frame2-xray.theme.global-styles :as global-styles]
            [day8.re-frame2-xray.theme.tokens
             :refer [tokens type-scale layout sans-stack mono-stack
                     duration-css motion]]))

;; ---- shell frame-id (rf2-lnluk) -----------------------------------------
;;
;; The shell's app-db lives in a frame. The PRODUCTION singleton mounts
;; against `:rf/xray` (the sibling of the host's `:rf/default`); that
;; keyword is the ONLY permitted bare `:rf/xray` literal in the
;; render-tree per the rf2-1w07r EPIC — every other affordance resolves
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
  render-tree literal (rf2-1w07r)."
  defaults/default-frame-id)

;; ---- tab inventory ------------------------------------------------------
;;
;; Per rf2-2moh1 the L3 tab inventory now lives in the internal
;; `panel-registry` — each panel's `install!` registers its own tab
;; metadata declaratively (`reg-l4-tab!`), and the L3 tab-bar +
;; L4 detail-panel read the registry via `tabs-for-mode :dynamic`
;; (driven by the `:modes` set on each entry).
;;
;; The seven Dynamic tabs registered against `#{:dynamic}` retain the
;; canonical left-to-right order via `:order` (0..6) — spec/018 §5
;; ordering is preserved as registration metadata rather than a literal
;; vector in this ns.
;;
;; Most labels use spaces so the rendered text carries no `-` glyphs.
;; The app-db tab's label is the lowercase library term `app-db`
;; (rf2-okvit) and DOES carry a `-`; the accessible-name collision the
;; old all-spaces convention guarded against (Playwright's
;; `getByRole('button', {name: '-'})` lassoing a host counter's `-`
;; button) is now handled by `tab-button`'s wrapped `aria-label`
;; (`Xray <label> tab`, rf2-plajx / rf2-vxpq1) — the accessible name
;; is no longer the bare label, so a `-` query can't match it.
;;
;; Frame-switcher concerns (the internal-frames filter set, distinct-
;; frames helper, ribbon picker view) live in `frame_switcher.cljs` per
;; rf2-iwwou — the L1 ribbon's frame slot is a single contractually-
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

(def ^:private default-tab
  "Default landing tab for Dynamic mode per spec/018 §5 — the Event
  lens. Registry-derived defaults (the first tab in `dynamic-tabs`)
  would land here too, but pinning the keyword keeps the documented
  default explicit (spec/018 §5 is normative on the landing tab)."
  :event)

;; ---- helpers (pure, exported for tests) ---------------------------------

(defn event-id-of-cascade
  "Best-effort pluck of the event-id from a cascade's `:event` slot.
  The slot is the raw event vector ([:foo/bar …]); the first element
  is the event id. nil when the cascade is unrouted or the event slot
  is empty."
  [cascade]
  (let [ev (:event cascade)]
    (when (vector? ev)
      (first ev))))

(defn render-event-id-only
  "Render JUST the event-id keyword for the L2 row per Round-3
  rf2-cmtkw — one-line minimal rows. The full event vector (args +
  payload) and all dropped fields (datetime, sequence number, duration
  tier, source coordinates) move to the row's hover tooltip + the
  Epoch panel detail (which has plenty of room).

  - `event-id` is the first element of the event vector.
  - Renders in the mode `accent` colour so it pops out of the row.
  - When the cascade carries no event vector, falls back to a
    `<no event>` chip in the secondary text colour. (Per rf2-639lc
    the L2 event list filters those cascades out via
    `cascade-has-event?`; the fallback is defence-in-depth.)"
  [event-vec]
  (if (vector? event-vec)
    [:span {:style {:color       (:accent tokens)
                    :font-weight 500}}
     (pr-str (first event-vec))]
    [:span {:style {:color      (:text-secondary tokens)
                    :font-style "italic"}}
     "<no event>"]))

(defn row-tooltip-text
  "Build the L2 row's hover tooltip per Round-3 rf2-cmtkw. The
  minimal one-line row surfaces only `event-id + ⚠/🌐/🤖`; the
  dropped fields (full event vector, sequence number, frame, source
  coordinates, handler duration) appear in this tooltip + in the
  Epoch panel detail on row click.

  Pure data — JVM-runnable. nil-safe per cascade slot. Returns a
  newline-joined string suitable for an HTML `:title` attribute.

  Slot ordering (most useful first):
    1. Full event vector (untruncated)
    2. `#<dispatch-id>` (the sequence number)
    3. `frame: <id>`
    4. Source coordinate `<file>:<line>:<col>` (when `:rf.trace/call-site`
       rode the `:rf.event/dispatched` emit per rf2-twt7m Change 1)
    5. `handler: <ms>ms` (when the cascade carried a `:handler` emit
       with `:elapsed-ms`)
    6. Trailing hint: `Click → open Event detail`"
  [cascade]
  (let [event-vec     (:event cascade)
        dispatch-id   (:dispatch-id cascade)
        frame-id      (:frame cascade)
        dispatched    (:dispatched cascade)
        call-site     (:rf.trace/call-site dispatched)
        coord-str     (when (map? call-site)
                        (let [{:keys [file line column]} call-site]
                          (when file
                            (cond-> file
                              line   (str ":" line)
                              column (str ":" column)))))
        handler       (:handler cascade)
        handler-ms    (or (:elapsed-ms handler)
                          (get-in handler [:tags :elapsed-ms]))
        lines (cond-> []
                (vector? event-vec) (conj (pr-str event-vec))
                (some? dispatch-id) (conj (str "#" dispatch-id))
                (some? frame-id)    (conj (str "frame: " frame-id))
                (some? coord-str)   (conj (str "source: " coord-str))
                (some? handler-ms)  (conj (str "handler: " handler-ms "ms"))
                true                (conj "Click → open Event detail"))]
    (str/join "\n" lines)))

(defn cascade-has-event?
  "True iff `cascade` carries a real `:event` vector (`(first :event)`
  resolves to a non-nil event-id). False for the `:ungrouped` bucket
  produced by `re-frame.trace.projection/group-by-event` for registry-
  time emits / frame lifecycle outside a drain / REPL evals — those
  carry no event vector. Per rf2-639lc the L2 event list filters this
  bucket out so the user never sees a `<no event>` placeholder row."
  [cascade]
  (some? (event-id-of-cascade cascade)))

(defn ungrouped-cascade?
  "True iff `cascade` is the `:ungrouped` bucket produced by
  `re-frame.trace.projection/group-by-event`. Used to give the
  bucket a distinct muted treatment in L2 when the rf2-r9lyy
  opt-in (`:settings/show-ungrouped?`) is on."
  [cascade]
  (= :ungrouped (:dispatch-id cascade)))

(defn l2-cascade-visible?
  "Pure helper. Should `cascade` render as a row in the L2 event
  list? Always true for cascades carrying a real `:event` vector;
  for the `:ungrouped` bucket, only true when the user has opted
  in via Settings → General → Power user → 'Show :ungrouped pseudo-
  cascade events in L2' (rf2-r9lyy). The ribbon nav (`◀ ▶ ⏭`) and
  L2 walk both compose against this predicate so the visible row
  set, the boundary detection, and the focus walk all agree."
  [cascade show-ungrouped?]
  (or (cascade-has-event? cascade)
      (and show-ungrouped? (ungrouped-cascade? cascade))))

;; rf2-ad7zx.15 — ONE shared column layout for the L2 event list, so the
;; column-header row (`l2-column-header`) and every data row (`event-row`)
;; reference the SAME constants and align column-for-column under the
;; Figma EventList (the `event-list` component in
;; `design-reference/xray_devtools_reference.cljs`). The
;; header was hand-authored with copied numbers in rf2-ad7zx.12 and drifted
;; out of alignment (Mike, live step-deck verify); these defs make the
;; two surfaces literally share the same column structure.
;;
;; Layout (left → right), matching the Figma EventList columns exactly
;; (rf2-pjjwh retired the leading focus gutter — a Xray affordance the mock
;; didn't carry):
;;
;;   gap [event id flex-1] gap [source 52px] gap [time →right] gap [dur →right]
;;   (rf2-xawwb — event-id leads, source follows, per the Figma-Make surface)
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
;; rf2-6ni62 — the three trailing columns (`source`, `timestamp`,
;; `duration`) are USER-RESIZABLE via drag dividers between cells.
;; Per-column widths flow from the `:rf.xray/event-list-col-widths` sub
;; — `l2-column-header` subscribes once and threads the resolved map
;; through to every row; `event-row` reads its props rather than
;; subscribing per-row (one subscribe per L2 paint, not N). The pre-
;; rf2-6ni62 fixed-px constants below are retained as DEFAULTS in
;; `config/event-list-col-default-widths` — a fresh install lays out
;; identically to the pre-feature shell.

(def ^:private l2-col-gap
  "Inter-column gap for both the header row and every data row. ONE
  value keeps the columns lined up across the two surfaces."
  "6px")

(def ^:private l2-row-h-padding
  "Horizontal padding for both the header row and every data row — the
  left value sets where the gutter starts, the right where the time
  column ends. Shared so neither surface drifts."
  "6px")

;; ---- column-divider drag state (rf2-6ni62) -----------------------------
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
  (when-let [{:keys [on-move on-up on-cancel prev-cursor]} @col-divider-drag-state]
    (when (and (exists? js/document) (.-removeEventListener js/document))
      (try (.removeEventListener js/document "pointermove" on-move)
           (catch :default _ nil))
      (try (.removeEventListener js/document "pointerup" on-up)
           (catch :default _ nil))
      (try (.removeEventListener js/document "pointercancel" on-cancel)
           (catch :default _ nil)))
    (when (and (exists? js/document) (.-body js/document))
      (set! (-> js/document .-body .-style .-cursor)
            (or prev-cursor "")))
    (reset! col-divider-drag-state nil)))

(defn- col-divider-on-move [^js e]
  ;; rf2-r0o63 — `dispatch-fn` (the captured frame-aware dispatcher) is
  ;; stashed in the drag-state at `col-divider-start-drag!` time; this
  ;; document-level move handler fires after render unwinds, so it reads
  ;; the closure back rather than dispatching to a `:rf/xray` literal.
  ;; The dispatch lands on the instance frame the divider was rendered
  ;; under. Falls back to `rf/dispatch*` defensively (test-driven drags
  ;; that bypassed `start-drag!`).
  (when-let [{:keys [col-id start-x start-width dispatch-fn]} @col-divider-drag-state]
    ;; Divider sits to the RIGHT of the column it sizes; dragging right
    ;; widens, dragging left narrows. dx = (now-x - start-x).
    (let [dx        (- (.-pageX e) start-x)
          new-width (+ start-width dx)]
      ((or dispatch-fn rf/dispatch*)
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

  `dispatch-fn` (rf2-r0o63) is the frame-aware dispatcher captured by
  the surrounding `reg-view` body — stashed in the drag-state so the
  document-level move handler (which fires after render unwinds) lands
  its width writes on the instance frame, not a `:rf/xray` literal.
  Defaults to `rf/dispatch*` for the test-driven lifecycle."
  ([^js e col-id current-width]
   (col-divider-start-drag! e col-id current-width rf/dispatch*))
  ([^js e col-id current-width dispatch-fn]
  (col-divider-detach-listeners!)
  (let [start-x     (.-pageX e)
        pointer-id  (.-pointerId e)
        prev-cursor (when (and (exists? js/document)
                               (.-body js/document))
                      (-> js/document .-body .-style .-cursor))
        on-move     col-divider-on-move
        on-up       col-divider-on-up
        on-cancel   col-divider-on-cancel]
    (reset! col-divider-drag-state
            {:col-id      col-id
             :start-x     start-x
             :start-width (or current-width 0)
             :pointer-id  pointer-id
             :dispatch-fn dispatch-fn
             :on-move     on-move
             :on-up       on-up
             :on-cancel   on-cancel
             :prev-cursor prev-cursor})
    (when (and (exists? js/document) (.-body js/document))
      (set! (-> js/document .-body .-style .-cursor) "col-resize"))
    (when (and (exists? js/document) (.-addEventListener js/document))
      (try (.addEventListener js/document "pointermove" on-move)
           (catch :default _ nil))
      (try (.addEventListener js/document "pointerup" on-up)
           (catch :default _ nil))
      (try (.addEventListener js/document "pointercancel" on-cancel)
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

    ArrowRight        +<step>px (widen the column to the LEFT)
    ArrowLeft         -<step>px (narrow)
    Shift+ArrowRight  +<coarse> (10 × 3 = 30px)
    Shift+ArrowLeft   -<coarse>
    Enter / Space     reset this column to its default

  The clamp lives in the registry handler — we dispatch the desired
  width and let `:rf.xray/set-event-list-col-width` apply the per-
  column floor. Returns true iff the keypress was handled.

  `dispatch-fn` (rf2-r0o63) is the frame-aware dispatcher captured by
  the surrounding `reg-view` body so the keyboard resize lands on the
  instance frame; defaults to `rf/dispatch*` for the test lifecycle."
  ([^js e col-id current-width]
   (col-divider-handle-keydown! e col-id current-width rf/dispatch*))
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
  "Render a draggable divider sitting to the RIGHT of the column with
  `col-id`. The divider is a 6px-wide vertical strip carrying the
  `col-resize` cursor + a hover affordance defined in
  `theme/global_styles/motion-css` (the rule paints a 1px accent stripe
  on hover so authors can find the affordance without a hidden hit-test
  game; the cursor change is the always-visible signal).

  rf2-6ni62. The divider participates in the row's flex layout as a
  zero-content cell with explicit width — placed BETWEEN the column
  it sizes (to its left) and the next column. Per the alignment
  contract the same divider widths apply to header + every row, so the
  flex layout stays consistent across surfaces.

  `:dispatch-fn` (rf2-r0o63) is the frame-aware dispatcher captured by
  the surrounding `reg-view` body (the L2 event-list views) — threaded
  through the drag / keyboard / double-click affordances so every
  width write lands on the instance frame, not a `:rf/xray` literal."
  [{:keys [col-id col-px row-height dispatch-fn]}]
  (let [floor    (get config/event-list-col-min-widths col-id)
        dispatch-fn (or dispatch-fn rf/dispatch*)
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
  legacy default that snuck through as a string)."
  [v]
  (cond
    (string? v) v
    (number? v) (str v "px")
    :else       nil))

;; ---- Relative-time chip (rf2-vbbq0 / rf2-0s2at) --------------------------
;;
;; Each L2 row carries a small right-aligned chip showing how long ago the
;; cascade was dispatched ("5s", "2m", "1h", "3d"). Mike's design call
;; (2026-05-19 Q10): bring datetime BACK to the default row, but as a
;; dynamic relative chip — NOT an absolute timestamp, NOT the sequence
;; number (dropped in R3-C), NOT the duration (not interesting).
;;
;; Bucketing keeps the chip silent-by-default — an old row that reads "5m"
;; does not jitter second-by-second because the same minute-bucket maps
;; back to "5m" regardless of the exact second inside the bucket. Buckets:
;;
;;   diff <   1s            → "now"
;;   diff < 60s              → "Ns"
;;   diff < 60min            → "Nm"
;;   diff < 24h              → "Nh"
;;   diff ≥ 24h              → "Nd"
;;
;; Anchor (rf2-0s2at): the "now" each row computes against is the
;; dispatched-time of the MOST RECENT cascade in the spine, not a
;; wall-clock tick. The earlier design (rf2-vbbq0 original) drove
;; the anchor with a per-second `setInterval` so old rows rolled
;; into their next bucket on time. Watching the parallel-frames
;; testbed live (2026-05-19) Mike saw the per-second re-render
;; flicker the L2 list constantly — relative time is meaningful
;; BETWEEN events, not between seconds. Each new event re-establishes
;; "now"; between events the list stays frozen. Anchor flips arrive
;; on the existing reactive path (a new cascade appears in
;; `:rf.xray/cascades`) so no timer / no internal trace pollution.
;;
;; The view subscribes to `:rf.xray/relative-time-now-ms` (sub
;; composed off `:rf.xray/cascades` — see `registry.cljs`).

(defn format-relative-time
  "Pure helper. Given two epoch-ms values (current time + the cascade's
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
  "CLJS-side helper (rf2-3f2di A8). Given an epoch-ms (the cascade's
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
  "CLJS-side helper. Given an epoch-ms (the cascade's dispatched
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

(defn cascade-dispatched-time-ms
  "Pluck the cascade's dispatched-time from `:dispatched :time` (every
  trace event carries `:time (interop/now-ms)` per `re-frame.trace.cljc
  build-event`). Returns nil when the cascade has no `:dispatched`
  slot or the slot's `:time` is not a number — defence-in-depth for
  cascades synthesised by tests that omit the field."
  [cascade]
  (let [t (get-in cascade [:dispatched :time])]
    (when (number? t) t)))

(defn relative-time-chip
  "Render the L2 row's right-aligned `timestamp` column. Per rf2-3f2di
  A8 this renders the ABSOLUTE wall-clock time (`HH:MM:SS.mmm`, e.g.
  `12:30:05.123`) per the authoritative reference event-list — NOT a
  relative `1s`/`now` chip. The `now-ms` anchor is no longer consumed for
  the label (absolute time needs no anchor); the parameter is retained
  for call-site stability and ignored. The chip's `:title` carries the
  full ISO walltime + epoch-ms as the power-user reveal.

  rf2-6ni62 — `col-px` is the user-resizable `timestamp` column width
  (pixels). The header + every row read from the same
  `:rf.xray/event-list-col-widths` sub so the two surfaces never drift
  out of column alignment.

  Renders nothing when the cascade carries no dispatched-time stamp."
  [cascade _now-ms col-px]
  (when-let [then-ms (cascade-dispatched-time-ms cascade)]
    (let [label   (format-clock-time then-ms)
          tooltip (format-absolute-time then-ms)]
      [:span {:data-testid     "rf-xray-row-time-chip"
              :data-then-ms    (str then-ms)
              :title           tooltip
              ;; rf2-ad7zx.15 / rf2-6ni62 — the trailing time column.
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
  "Render the L2 row's trailing `duration` column (rf2-lnod7) — the
  Figma EventList's fourth column. Right-aligned handler wall-time
  (`1.2 ms`), sourced from the cascade's `:handler` trace event via
  `l2-timeline/cascade-duration-label`. Shares the header `duration`
  column's right-aligned width with the same `col-px` source so the
  value right-aligns under the header label; spacing from the
  preceding timestamp column comes from the row's shared flex `gap`.

  rf2-6ni62 — `col-px` is the user-resizable `duration` column width
  (pixels). Header + every row read from the same
  `:rf.xray/event-list-col-widths` sub so the two surfaces never drift
  out of column alignment.

  ALWAYS renders the cell span (occupying its column width) so the
  columns stay aligned row-to-row; when the cascade carries no measured
  handler duration the cell is simply blank rather than collapsing the
  column."
  [cascade col-px]
  (let [label (l2-timeline/cascade-duration-label cascade)]
    [:span {:data-testid   "rf-xray-row-duration"
            :data-duration (str (l2-timeline/cascade-duration-ms cascade))
            :style {:color         (:text-tertiary tokens)
                    :flex-shrink   0
                    :font-family   mono-stack
                    :font-size     (:caption type-scale)
                    :width         (->px col-px)
                    :text-align    "right"
                    :white-space   "nowrap"}}
     label]))

;; ---- Relative-time anchor (rf2-0s2at) ------------------------------------
;;
;; No timer. The anchor is the dispatched-time of the most recent
;; cascade — see the `:rf.xray/relative-time-now-ms` sub in
;; `registry.cljs`. It re-fires on the standard reactive path when a
;; new cascade lands in `:rf.xray/cascades`, so old rows recompute
;; their relative-time exactly when fresh context arrives. (Earlier
;; rf2-vbbq0 design used a `setInterval`-driven tick; rf2-0s2at
;; replaced it after Mike observed constant L2 flicker watching the
;; parallel-frames testbed live.)

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
    `at-head? AND live?` (rf2-x5tro): already tracking head live, so the
    snap is a true no-op. When at head but PAUSED (frozen inspection)
    `»` stays enabled — pressing it resumes LIVE, which is not a no-op.

  ## rf2-3f2di A2 — blue-filled chevron buttons

  Per the authoritative reference chrome-ribbon
  (`tools/xray/design-reference/xray_devtools_reference.cljs`) the nav
  cluster is three FILLED `:accent` buttons (blue bg, white icon,
  `p-1 rounded`, hover lifts opacity 0.9 → 1) carrying the
  ChevronLeft/Right/ChevronsRight glyphs — NOT borderless icon-buttons,
  NOT bordered unicode triangles. Inline SVG is not idiomatic in this
  pure-hiccup view, so the buttons keep glyphs but swap the chunky
  filled triangles (`◀ ▶ ⏭`) for the angle-quotation chevrons
  (`‹ › »`, U+2039 / U+203A / U+00BB) which read as the reference's thin
  strokes. (rf2-3f2di supersedes the rf2-cplj8 borderless treatment.)

  ## Disabled appearance (rf2-x5tro / rf2-3f2di)

  A disabled button must READ as inert, not merely block clicks. With
  the blue-filled treatment the inert signal is a strong opacity drop
  (the filled blue fades) + `cursor: not-allowed`. The native
  `:disabled` attribute (plus the dropped `:on-click` per rf2-fzbrw)
  blocks interaction; the inline style + `aria-disabled` carry the
  visual + a11y signal.

  (rf2-htik0 P1 — earlier wiring had the head/tail boundaries flipped
  so the disabled glyph dimmed the wrong button.)

  `:dispatch-fn` (rf2-r0o63) is the frame-aware dispatcher captured by
  the `ribbon` `reg-view` body — the nav `on-click` handlers fire after
  render unwinds, so they dispatch through the captured closure to land
  on the surrounding instance frame, not a `:rf/xray` literal."
  [{:keys [at-head? at-tail? live? dispatch-fn]}]
  (let [head-disabled? (boolean (and at-head? live?))
        ;; rf2-3f2di A2 / rf2-xawwb — filled nav buttons (Figma-Make chrome-
        ;; ribbon): blue `active-bg` fill, white `active-text` icon, 4px
        ;; radius, hover opacity lift (the `:hover` rule lives in
        ;; `theme/global-styles/motion-css`).
        btn-style {:background      (:active-bg tokens)
                   :border          "none"
                   :color           (:active-text tokens)
                   :cursor          "pointer"
                   :opacity         1
                   :padding         "0"
                   ;; rf2-xrn3l — Figma authority specifies 21px square nav
                   ;; buttons (chevron-left / chevron-right / chevrons-right
                   ;; in the chrome ribbon's left cluster). The prior 22px
                   ;; was a 1-px drift from the authority — corrected here
                   ;; to match the Figma export.
                   :width           "21px"
                   :height          "21px"
                   :display         "inline-flex"
                   :align-items     "center"
                   :justify-content "center"
                   :border-radius   "4px"
                   :font-family     sans-stack
                   :font-size       "15px"
                   :line-height     "1"}
        ;; rf2-3f2di — filled inert state: the blue fill fades + the
        ;; `not-allowed` cursor carries the signal. The native
        ;; `:disabled` attribute already blocks clicks (rf2-fzbrw).
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

;; The L1 frame-switcher slot lives in `frame_switcher.cljs` per rf2-iwwou
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
  inside the ribbon's `reg-view` so subscribes still resolve through
  React context to `:rf/xray`.

  Per rf2-ak4ms the legacy `js/window.prompt` stub is gone — the add-
  pill affordance now opens the rich edit popup. Per spec/018 §7 the
  popup pre-populates from existing pills (edit) or from right-click
  event-row context (add).

  `dispatch-fn` (rf2-nesy9) is the frame-aware dispatcher captured by
  the `events-ribbon` `reg-view` body so each pill's edit / remove
  click lands on the surrounding instance frame, not a `{:frame
  :rf/xray}` literal."
  [dispatch-fn {:keys [filters]}]
  [filter-pills/pills-view dispatch-fn {:filters filters}])

(defn- ribbon-redacted-indicator
  "REDACTED indicator (rf2-azls9) — preserved next to the mode pill for
  back-compat with the existing redacted-counter assertion surface.
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
     ;; rf2-vxpq1 — the leading `●` glyph is decorative (the count
     ;; + "REDACTED" word carry the meaning). `aria-hidden` on the
     ;; glyph suppresses the unicode-name announcement ("black
     ;; circle") while keeping the text + title accessible.
     [:span {:aria-hidden "true"} "● "]
     (str "REDACTED " redacted-count)]))

(rf/reg-view ribbon-theme-toggle
  "rf2-xawwb — theme toggle (Figma-Make surface). A sun/moon icon-button
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

  ## rf2-uu3lp — `reg-view` so the subscribe routes to `:rf/xray`

  The `:theme` setting lives in Xray's `:rf/xray` frame (the
  `:rf.xray/setting` sub + `:rf.xray/settings-update` event are
  registered against `:rf/xray` via `registry/register-xray-handlers!`).
  A plain `defn` rendered inside the shell's `:rf/xray` frame-provider
  would not pick up the surrounding frame (Spec 004 §Plain Reagent fns
  / Spec 006 §Plain-fn-under-non-default-frame warning) — the subscribe
  here would route to `:rf/default` and read the host app's app-db.
  The dispatch ALREADY carries an explicit `{:frame :rf/xray}` arg, but
  the subscribe was relying on React-context which the plain fn does
  not consult. `reg-view`-registration makes the component
  `:contextType frame-context`-aware so the subscribe resolves to
  `:rf/xray` through the enclosing Provider — same shape as every other
  Xray shell region (rf2-in6l2)."
  []
  (let [theme @(rf/subscribe [:rf.xray/setting :theme nil])
        dark? (= theme :dark)
        next  (if dark? :light :dark)]
    [:button {:data-testid "rf-xray-theme-toggle"
              :title       (if dark? "Switch to light theme" "Switch to dark theme")
              :aria-label  (if dark? "Switch to light theme" "Switch to dark theme")
              ;; rf2-r0o63 — dispatch through the reg-view-injected
              ;; frame-aware dispatcher so the theme write lands on the
              ;; surrounding instance frame (captured at render time),
              ;; not a `:rf/xray` literal.
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

(defn- ribbon-right-icons
  "Right-icons cluster — `⛶` pop-out · `⚙` settings · `✕` close. Per
  spec/018 §3 Right-icon behaviour + spec/011-Launch-Modes.md §Pop-out:
  the second-window UX has landed (rf2-czcg5), so the reserved pop-out
  (`⛶`) slot now carries a VISIBLE button — the canonical chrome launch
  affordance for the pop-out window (Mike ruled: a visible top-bar
  button, not the prior right-click-only path). It dispatches
  `:rf.xray/popout-shell`, which fires the DOM-side
  `:rf.xray.fx/popout-shell` effect (mount/install-fx!) → `mount/popout!`.
  The programmatic `(xray/popout!)` API remains the secondary path.

  Settings opens the Settings popup modal (rf2-9poxq) via
  `:rf.xray/settings-open`; close dispatches `:rf.xray/close-shell`
  (handled by mount.cljs in production).

  ## rf2-cplj8 — lucide-style icon buttons

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

  `:dispatch-fn` (rf2-r0o63) is the frame-aware dispatcher captured by
  the `ribbon` `reg-view` body so the settings / close clicks land on
  the surrounding instance frame, not a `:rf/xray` literal."
  [{:keys [dispatch-fn]}]
  (let [icon-style {:background      "transparent"
                    :border          "none"
                    :border-radius   "4px"
                    ;; rf2-xawwb — muted-white ink on the dark chrome band
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
     ;; rf2-czcg5 — VISIBLE pop-out (`⛶`) button, the canonical chrome
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

(defn nav-boundary-state
  "Pure helper (rf2-3f2di A5) — compute the `{:at-head? :at-tail? :live?}`
  state the nav cluster consumes, from the already-resolved sub values.
  Extracted so the chrome ribbon (which hosts the nav cluster per the
  authority reference) and any test can derive the boundary state without
  re-subscribing. Mirrors the boundary walk the events ribbon previously
  did inline.

  - `focus` — `:rf.xray/focus` (carries `:dispatch-id` + `:mode` +
    `:paused?`).
  - `cascades` — `:rf.xray/filtered-cascades` (frame view-scope + pills
    + mutes already applied).
  - `show-ungrouped?` — `:rf.xray/show-ungrouped?` (the L2 visibility
    predicate must agree with what the user sees).

  Pure-data → map; JVM-runnable so the boundary logic is testable
  without a CLJS runtime."
  [{:keys [focus cascades show-ungrouped?]}]
  (let [focused-id      (:dispatch-id focus)
        event-cascades  (filterv #(l2-cascade-visible? % show-ungrouped?) cascades)
        ids             (mapv :dispatch-id event-cascades)
        at-head?        (or (empty? ids)
                            (= focused-id (last ids))
                            (nil? focused-id))
        at-tail?        (or (empty? ids)
                            (= focused-id (first ids)))
        live?           (and (= :live (:mode focus))
                             (not (:paused? focus)))]
    {:at-head? at-head?
     :at-tail? at-tail?
     :live?    live?}))

(rf/reg-view ribbon
  "L1 **chrome ribbon** (bar-1) — reconciled to the authoritative
  reference chrome-ribbon (`tools/xray/design-reference/xray_devtools_
  reference.cljs`, rf2-3f2di A4/A5). 34px tall (`:top-strip-height`).

    - **LEFT** — the `Events` label (the reference leads with it; the
      `❖ Xray` wordmark was DROPPED per A4), the `[◀ ▶ ⏭]` blue-filled
      nav cluster (A2), then the `+ filter` add-pill (A5). (rf2-pjjwh
      retired the `focus` button + focus-chip with the focus feature.)
      The chrome `+ filter` is mutually-exclusive with the events-ribbon
      (rf2-8zd80): when ≥1 filter is committed the events-ribbon owns the
      `[+]` add affordance and the chrome `+ filter` collapses to zero
      width via the `.rf-xray-filters-collapse-h` horizontal-grid track
      (250ms, same cadence + reduced-motion seam as the events-ribbon
      vertical collapse).
    - **RIGHT** — the Frame dropdown (`frame-switcher/frame-switcher-
      view`) + the Dynamic/Static mode dropdown (`mode-pill/mode-pill`)
      + the mute (🔇 N) / REDACTED (● N) silent-by-default indicators +
      the `⚙` settings · `✕` close icon-buttons.

  The committed filter pills (green/red) live on bar-2 (the events
  ribbon, `events-ribbon`); only the add(+) sits up here, matching the
  reference's chrome-ribbon (add) / events-ribbon (pills) split.

  The 2-px left-edge accent stripe (rf2-o5f5f.1 mode-signal mechanism
  #2 — the single GitHub-blue accent in both modes, rf2-ad7zx.13) stays
  as the chrome-edge accent; the understated mode dropdown carries the
  mode state via its active option + `data-active-mode`.

  Per rf2-in6l2 `reg-view`-registered so subscribes resolve to
  `:rf/xray`."
  [_props]
  (let [redacted-count @(rf/subscribe [:rf.xray/suppressed-sensitive-count])
        ;; rf2-ikuwt — mute-count drives the chrome ribbon's silent-by-
        ;; default indicator next to the REDACTED indicator. Reading the
        ;; count sub (not the raw set) means the ribbon re-renders only
        ;; when the count changes; the indicator's click opens the
        ;; unmute manager.
        muted-count    @(rf/subscribe [:rf.xray/muted-event-ids-count])
        ;; rf2-3f2di A5 — the nav cluster moved UP to the chrome ribbon
        ;; per the authority reference, so the chrome ribbon subscribes to
        ;; the spine state the events ribbon used to own.
        focus          @(rf/subscribe [:rf.xray/focus])
        cascades       @(rf/subscribe [:rf.xray/filtered-cascades])
        show-ungrouped? @(rf/subscribe [:rf.xray/show-ungrouped?])
        ;; rf2-8zd80 — the chrome `+ filter` and the events-ribbon are
        ;; mutually-exclusive add affordances. Hide the chrome button
        ;; when ≥1 filter is committed (the events-ribbon's own `[+]`
        ;; takes over). Open when zero filters, closed otherwise.
        filters        @(rf/subscribe [:rf.xray/active-filters])
        no-filters?    (zero? (+ (count (:in filters)) (count (:out filters))))
        {:keys [at-head? at-tail? live?]}
        (nav-boundary-state {:focus           focus
                             :cascades        cascades
                             :show-ungrouped? show-ungrouped?})]
    [:div {:data-testid "rf-xray-ribbon"
           :style {:display          "flex"
                   :align-items      "center"
                   :justify-content  "space-between"
                   :gap              "12px"
                   :height           (:top-strip-height layout)
                   :padding          "0 12px"
                   ;; rf2-xawwb — DARK chrome band (Figma-Make surface).
                   ;; The chrome ribbon paints the dedicated dark-chrome
                   ;; token in BOTH themes; chrome text reads the white
                   ;; `chrome-ribbon-text` token so it stays legible on the
                   ;; near-black band.
                   :background       (:chrome-ribbon-bg tokens)
                   :color            (:chrome-ribbon-text tokens)
                   :border-bottom    (str "1px solid " (:border-subtle tokens))
                   ;; rf2-4yemd — the rf2-o5f5f.1 mode-signal mechanism #2
                   ;; (a 2-px `:accent` left-edge stripe) was retired. The
                   ;; Figma authority chrome ribbon has NO left-edge accent
                   ;; — Dynamic mode signals through the mode-pill alone.
                   ;; Removing the stripe also resolves the visual `blue
                   ;; left edge on the chrome ribbon` reported by Mike
                   ;; 2026-05-24.
                   :font-family      sans-stack
                   :font-size        (:body type-scale)}}
     ;; LEFT cluster — `Events` label · nav · add(+). Per the authority
     ;; reference chrome-ribbon (rf2-3f2di A4/A5). The `❖ Xray` wordmark
     ;; was dropped (A4); the cluster now leads with the `Events` label.
     ;; rf2-pjjwh retired the focus button + focus-chip with the focus
     ;; feature.
     ;; rf2-axpq2 — `:flex-wrap "nowrap"` on the LEFT cluster. The prior
     ;; `wrap` caused the [+] add-pill (the cluster's last child) to wrap
     ;; onto a second line at ~420px viewports, where it overflowed the
     ;; fixed 34px chrome-ribbon height and got vertically occluded by the
     ;; events-ribbon below — click-blocked. The Figma authority chrome-
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
      ;; rf2-xawwb — `Event History` label leads the left cluster (Figma-
      ;; Make surface renamed `Events` → `Event History`). White ink on the
      ;; dark chrome band (`chrome-ribbon-text`).
      [:span {:data-testid "rf-xray-ribbon-events-label"
              :style {:color       (:chrome-ribbon-text tokens)
                      :font-family sans-stack
                      :font-weight 500
                      :white-space "nowrap"}}
       "Event History"]
      ;; rf2-3f2di A2/A5 — blue-filled nav cluster, promoted to bar-1.
      ;; rf2-r0o63 — thread the captured frame-aware dispatcher so the
      ;; nav on-clicks land on the surrounding instance frame.
      [ribbon-nav-cluster {:at-head? at-head? :at-tail? at-tail? :live? live?
                           :dispatch-fn dispatch}]
      ;; rf2-xawwb — `+ filter` text button (Figma-Make chrome-ribbon).
      ;; Replaces the prior `Filters:` label + plus-icon affordance with a
      ;; single outlined `+ filter` text button. Opens the same edit
      ;; popup (`filter-pills/chrome-add-filter-button` delegates to the
      ;; canonical `:rf.xray/open-edit-popup` flow), so the add behaviour
      ;; is RETAINED — only the surface changes. Muted-outline on the dark
      ;; band so it reads as a secondary chrome affordance.
      ;;
      ;; rf2-8zd80 — wrapped in a horizontal collapse track so the
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
       [:div [filter-pills/chrome-add-filter-button dispatch]]]]
     ;; RIGHT cluster — scope selectors (Frame + Dynamic/Static) then the
     ;; silent-by-default indicators + chrome actions. Per the authority
     ;; reference chrome-ribbon right side (rf2-3f2di A5).
     [:div {:style {:display "flex" :align-items "center" :gap "8px"}}
      ;; L1 frame-switcher slot (rf2-iwwou) — single contractually-
      ;; anchored surface. The view itself reads `:rf.xray/current-
      ;; frame` + `:rf.xray/available-frames` and writes via
      ;; `:rf.xray/select-frame`. The frame is a view SCOPE, not a
      ;; filter (rf2-4vp5j Workstream C).
      [frame-switcher/frame-switcher-view]
      ;; Dynamic/Static dropdown (rf2-4vp5j) — compact, understated; the
      ;; accent stripe carries the mode signal so the control stays
      ;; quiet. Always rendered (the `:rf.xray/static-mode?` feature
      ;; gate was removed per rf2-8l3uk).
      [mode-pill/mode-pill]
      ;; rf2-ikuwt — mute indicator (🔇 N) renders inline next to the
      ;; REDACTED indicator. Both are silent-by-default surfaces that
      ;; only paint when their count is positive. Click → unmute
      ;; manager modal.
      [spine-filters/ribbon-mute-indicator dispatch muted-count]
      [ribbon-redacted-indicator redacted-count]
      ;; rf2-xawwb — theme toggle (sun/moon) sits before the settings/close
      ;; icons per the Figma-Make chrome-ribbon right cluster.
      [ribbon-theme-toggle]
      ;; rf2-r0o63 — thread the captured frame-aware dispatcher so the
      ;; settings / close icon clicks land on the instance frame.
      [ribbon-right-icons {:dispatch-fn dispatch}]]]))

;; ---- L2 event list -------------------------------------------------------

;; ---- L2 scrollbar styling (rf2-ieg6d Bug 2) ------------------------------
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

;; ---- L2 auto-scroll on focus change (rf2-ieg6d Bug 1) --------------------
;;
;; When a new event arrives + mode=:live + focus auto-advances to head,
;; the focused row may render below the L2 list's visible window. The
;; LIVE pill says "tracking head" but the user can't see the head row
;; — defeats the LIVE UX. Fix: a `:ref` callback on the focused row
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
  id will scroll again (covers the toggle-off/on case)."
  [id auto-track?]
  (when auto-track?
    (fn [el]
      (cond
        (nil? el)
        (reset! last-scrolled-focus-id ::never)

        (not= id @last-scrolled-focus-id)
        (do (reset! last-scrolled-focus-id id)
            (scroll-focused-row-into-view! el))))))

(defn- event-row
  "One row in the L2 event list. Single line per the Figma-Make
  EventList (the `event-list` component in
  `design-reference/xray_devtools_reference.cljs`) + rf2-pjjwh.

  ## rf2-pjjwh — clean mock layout

  Default row content, four columns matching the mock exactly:

      :event-id   source   timestamp   duration

  - **`:event-id`** — the bare event-id keyword (not the full event
    vector). Args / payload move to the tooltip + Epoch panel detail.
  - **`source`** — the closed-enum `:source` axis (`ui` / `fx-dispatch` / `after-timer` / …), per rf2-1ve9h.
  - **`timestamp`** — the absolute wall-clock `HH:MM:SS.mmm`.
  - **`duration`** — the handler wall-time (`1.2 ms`).

  The active (selected) row is shown by BACKGROUND ONLY (the mock's
  `isActive` → `bg-[var(--devtools-hover)]`). rf2-pjjwh removed the
  leading focus gutter, the origin-prefix glyph, the activity badges
  (⚠ 🌐 🤖), the trailing lifecycle status stripe, the out-of-focus
  dimming, and the `:ungrouped` muted pseudo-row — none of those are
  in the mock.

  The row's `:title` attribute carries the dropped fields (full event
  vector with args, sequence number, frame, source coord, handler
  duration) so a hover surfaces them without leaving L2. Clicking the
  row opens the Epoch panel in L4 with the full untruncated content.

  Right-click (`on-context-menu`) lowers per spec/018 §7 'Right-click
  event-row → context menu' into `:rf.xray/open-row-context-menu`
  (rf2-ikuwt) — a small floating context menu with two items:

    - 'Mute <event-id>' — one-step mute via
      `:rf.xray/mute-event-id`; the row disappears from the spine
      and the L1 ribbon's mute-count indicator increments.
    - 'Always hide this event-type…' — opens the rich OUT-filter
      popup via `:rf.xray/hide-event-type` (the existing flow).

  The menu state lives in app-db (`:row-context-menu`) so the menu
  renders at the shell-view root and floats above the L2 list's
  overflow-hidden clipping. preventDefault on the right-click
  suppresses the browser's native menu.

  rf2-6ni62 — `col-widths` carries the resolved
  `{:source N :timestamp N :duration N}` map every row reads its
  inline column widths from. The parent (`event-list`) subscribes
  ONCE per paint and threads the resolved map through props so each
  row doesn't re-subscribe per render."
  [{:keys [cascade focused-id auto-track? now-ms col-widths dispatch-fn]}]
  (let [dispatch-fn (or dispatch-fn rf/dispatch*)
        id          (:dispatch-id cascade)
        focused?    (= id focused-id)
        ;; rf2-ad7zx.12 — the Figma `source` column tag (source name as
        ;; text; `ui` for the default app-code source). Per rf2-1ve9h
        ;; the prior `:rf/dispatch-origin` axis was collapsed into
        ;; `:source` — the single closed-enum functional-origin axis.
        source         (l2-timeline/source-of cascade)
        source-tag     (l2-timeline/origin-source-tag source)
        ev-id       (event-id-of-cascade cascade)
        event-vec   (:event cascade)
        ;; rf2-pjjwh — the active row is marked by background (Figma
        ;; EventList's `isActive` → `bg-[var(--devtools-hover)]`). No
        ;; border ring, no trailing status stripe — those are not in the
        ;; mock.
        ;;
        ;; rf2-hga49 — the selected background is the dedicated
        ;; `:selected-row-bg` (a step DARKER than `:hover`) rather than
        ;; `:hover` itself. Two reasons: selection now reads as a state
        ;; distinct from mere hover, AND the darker grey survives UNDER
        ;; the issue-row pink wash (a low-opacity rose painted as a
        ;; `:background-image` layer over this `:background-color`). With
        ;; the old `:hover` grey + the prior heavier wash, a SELECTED
        ;; ERROR row was indistinguishable from an unselected one — the
        ;; wash drowned the selection. The leading ">" caret (below) is
        ;; the background-independent belt-and-braces selection signal.
        bg          (if focused? (:selected-row-bg tokens) "transparent")
        ;; rf2-b8guz — light-pink WASH when this event's epoch CONTAINS
        ;; AN ISSUE (any error / warning / schema-violation / … — the
        ;; SAME set the Issues ribbon/feed aggregates, via the canonical
        ;; `l2-timeline/cascade-has-issue?` predicate). The cross-epoch
        ;; "this event had a problem" cue at the spine. Painted as a flat
        ;; `:background-image` gradient layer (the `:bg-issue-row` token,
        ;; a low-opacity rose wash) so it COMPOSES OVER the focused-row /
        ;; hover `:background-color` rather than clobbering it — an issue
        ;; row reads pink whether focused or not, and the focus highlight
        ;; survives underneath. Nil when no issue, so a clean row paints
        ;; only its base background.
        has-issue?  (l2-timeline/cascade-has-issue? cascade)
        issue-wash  (when has-issue?
                      (str "linear-gradient(" (:bg-issue-row tokens) ", "
                           (:bg-issue-row tokens) ")"))
        ;; rf2-ieg6d Bug 1 — only the focused row in the LIVE-at-head
        ;; auto-tracking branch carries a ref. RETRO and non-focused rows
        ;; get nil (no DOM-side scroll work, no per-render cost).
        ref-fn      (when focused? (focused-row-ref id auto-track?))
        ;; rf2-pjjwh — body-click is pure SELECTION (drives the L3 tabs).
        ;; The focus-set lens (and its body-click-clears-focus branch)
        ;; was retired; row click selects the cascade, full stop.
        body-click  (fn [_e]
                      ;; rf2-r0o63 — dispatch through the captured
                      ;; instance-frame dispatcher (threaded from the
                      ;; `event-list` reg-view) so the focus-cascade
                      ;; write lands on this shell's frame.
                      (dispatch-fn [:rf.xray/focus-event id (:frame cascade)]))]
    ;; Density (rf2-htik0 Bug 2): height 22px + padding "1px 6px" tightens
    ;; the row from the earlier 28px / "4px 8px" spec-baseline. Xray is
    ;; info-dense; keeps clickable hit-area while letting ~10 rows fit in
    ;; the same vertical budget the old 8 rows used.
    ;;
    ;; rf2-6gstp — keyboard a11y. Rows expose `role="button"` +
    ;; `tab-index="0"` + `aria-label` so keyboard-only users can Tab
    ;; into the list and operate it. Enter / Space activates the body
    ;; (select cascade); Shift+F10 + ContextMenu key open the row's
    ;; context menu (Mute / Hide event-type) — the same affordance
    ;; right-click users get. The audit (2026-05-20) flagged this
    ;; surface as P1 because the menu's actions had no keyboard path.
    [:li (cond-> {:data-testid (str "rf-xray-event-row-" (str id))
                  ;; rf2-b8guz — machine-readable issue-row flag so the
                  ;; light-pink-wash contract is pinnable from a CLJS unit
                  ;; test (issue epoch → "true"; clean row → absent) without
                  ;; parsing the inline gradient string.
                  :data-rf-xray-issue-row (when has-issue? "true")
                  :role        "button"
                  :tab-index   "0"
                  :aria-label  (if ev-id
                                 (str "Event " (str ev-id)
                                      (when focused? " (focused)"))
                                 "Event row")
                  :aria-pressed (if focused? "true" "false")
                  :on-click    body-click
                  :on-key-down (fn [^js e]
                                 ;; rf2-6gstp — keyboard activation +
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
                                     ;; rf2-ikuwt — open the row's
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
                  ;; rf2-cmtkw — dropped fields (full event vector with
                  ;; args, sequence number, frame, source coord, handler
                  ;; duration) surface in this hover tooltip + the L4
                  ;; Epoch panel on click.
                  :title (row-tooltip-text cascade)
                  :style {:display       "flex"
                          :align-items   "center"
                          ;; rf2-ad7zx.15 — shared column gap + horizontal
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
                          ;; rf2-pjjwh — active row marked by background
                          ;; ONLY. The `1px solid transparent` border
                          ;; keeps border-box alignment with the header so
                          ;; columns never drift.
                          ;;
                          ;; rf2-b8guz — split into `:background-color`
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
     ;; rf2-hga49 — leading SELECTION CARET gutter. A small ">" glyph
     ;; sits in a FIXED-WIDTH leading gutter when the row is focused, and
     ;; the gutter renders empty (same width) otherwise — so selecting a
     ;; row never shifts the columns. This is the background-INDEPENDENT
     ;; selection signal: it reads on any row state (clean / issue) where
     ;; the grey-vs-pink background channels can fight (the selected error
     ;; row that prompted the bead). It consciously reintroduces the
     ;; leading gutter glyph rf2-pjjwh removed for the Figma
     ;; background-only mock — that mock is exactly what failed on an
     ;; error row, so the override is deliberate.
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
     ;; rf2-pjjwh — column order: `event id` · `source` · `timestamp` ·
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
     ;; rf2-6ni62 — divider sits to the RIGHT of `event id` and resizes
     ;; the `source` column to its right. The divider widths participate
     ;; in the flex layout on rows + header identically so the cells
     ;; stay column-for-column aligned.
     [col-divider {:col-id    :source
                   :col-px    (:source col-widths)
                   :row-height "22px"
                   :dispatch-fn dispatch-fn}]
     ;; rf2-ad7zx.12 + rf2-lnod7 — the `source` COLUMN (Figma EventList).
     ;; A user-resizable cell (rf2-6ni62) aligned under the header's
     ;; `source` label, carrying the dispatch-origin as a short text tag.
     ;; The reference tags EVERY row, so the default app-code origin
     ;; (`:user`, plus nil/unknown synthetic cascades) renders `ui` rather
     ;; than a blank cell. rf2-pjjwh dropped the leading origin-prefix
     ;; glyph — the mock carries the source tag as plain text only.
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
     ;; rf2-6ni62 — divider sits between `source` and `timestamp`.
     [col-divider {:col-id    :timestamp
                   :col-px    (:timestamp col-widths)
                   :row-height "22px"
                   :dispatch-fn dispatch-fn}]
     ;; Timestamp column (rf2-3f2di A8) — absolute wall-clock
     ;; `HH:MM:SS.mmm`, right-aligned. The chip carries an absolute-time
     ;; `:title` tooltip as the power-user reveal.
     (relative-time-chip cascade now-ms (:timestamp col-widths))
     ;; rf2-6ni62 — divider sits between `timestamp` and `duration`.
     [col-divider {:col-id    :duration
                   :col-px    (:duration col-widths)
                   :row-height "22px"
                   :dispatch-fn dispatch-fn}]
     ;; Duration cell (rf2-lnod7) — the trailing `duration` column,
     ;; restoring the Figma EventList's fourth column. Handler wall-time
     ;; (`1.2 ms`), right-aligned, flush against the row's trailing edge.
     (duration-cell cascade (:duration col-widths))]))

;; ---- events ribbon (rf2-4vp5j) -----------------------------------------
;;
;; The SECOND stratum below the chrome ribbon. LEFT → RIGHT:
;;
;;   Events:  [◀ ▶ ⏭]  [🎯 focus-chip]  [+pill ×pill +]   …   N hidden  [Clear Filters]
;;
;; Left = spine state + navigation; right = filter actions. The
;; `N events hidden by filters` message + Clear Filters appear ONLY when
;; filters are active (per rf2-4vp5j Decision 3):
;;
;;   - both absent when no pills + no mutes (clean default → empty right);
;;   - Clear Filters shows when ANY filter is active;
;;   - the message shows beside it ONLY when N > 0.
;;
;; The hidden COUNT reflects pill/mute suppression ONLY — the frame is a
;; view SCOPE, not a filter (rf2-4vp5j Workstream C), so switching frames
;; never inflates "hidden" and Clear Filters never touches the frame.
;; The message keeps rf2-jvghz's prominent yellow accent (Mike values
;; it). Persisted filters survive reload via localStorage and used to
;; silently suppress L2 rows; this ribbon makes them a VISIBLE cause +
;; one-click reset (`:rf.xray/clear-all-filters`).

(defn filters-hidden-message
  "Pure hiccup. The `N events filtered out` warning count — the LEADING
  signal of the events ribbon (bar-2), sitting before the committed
  filter pills. Renders nil unless the hidden count is positive
  (`:visible?`). Counts pill/mute suppression ONLY — the frame is a view
  scope, never counted as hidden (rf2-4vp5j Workstream C).

  rf2-3f2di A5 — reconciled to the authoritative reference events-ribbon
  (`tools/xray/design-reference/xray_devtools_reference.cljs`): the
  warning reads `\"N events filtered out\"` in the `:warning` colour
  (reference `--devtools-warning`, `font-medium`), leading the bar-2
  cluster ahead of the green/red pills. (Supersedes the prior rf2-jvghz
  `N events hidden by filters` yellow chip on the right.)"
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

(rf/reg-view events-ribbon
  "L1.5 **events ribbon** (bar-2) — reconciled to the Figma-Make surface
  (rf2-xawwb) + rf2-pjjwh. The second stratum below the chrome ribbon.
  LEFT → RIGHT:

    - the `↳ filters:` contextual label (corner-down-right glyph);
    - the add-filter `+` ICON button
      (`filter-pills/events-add-filter-button`) — opens the edit popup;
    - the committed green-bordered IN pills + red-bordered OUT pills
      (`filter-pills/pills-view`), each with a vertical divider before
      its `✕`;
    - pushed to the FAR RIGHT (via `margin-left: auto`): the `N events
      filtered out` warning text (when N > 0, `:warning` colour).

  ## rf2-pjjwh — conditional + animated

  The whole `filters:` ribbon is HIDDEN when there are zero filters and
  appears only after the user creates the first filter via `[+ filter]`.
  It animates OPEN when the first filter is added and animates CLOSED when
  the last filter is removed. The collapse uses a CSS
  `grid-template-rows: 0fr ⇄ 1fr` transition (the modern jank-free
  height-collapse technique) keyed off the `data-open` attribute — see
  `theme/global-styles/motion-css` for the rule. The outer collapse track
  stays mounted so the transition can run in both directions; the inner
  content is the actual bar-2 surface.

  rf2-pjjwh also REMOVED the `Clear Filters` button from the trailing
  edge — pills are removed individually via each pill's `✕` (the
  `[+ filter]` add affordance lives up on the chrome ribbon).

  Per rf2-in6l2 `reg-view`-registered so subscribes resolve to
  `:rf/xray`. A distinct `bg-2` background + `border-subtle` hairline
  separate it from the chrome ribbon's `bg-1` as a distinct layer."
  []
  (let [filters        @(rf/subscribe [:rf.xray/active-filters])
        hidden-summary @(rf/subscribe [:rf.xray/hidden-by-filters])
        filter-count   (+ (count (:in filters)) (count (:out filters)))
        open?          (pos? filter-count)]
    ;; rf2-pjjwh — collapse track. Always mounted so the height/opacity
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
      ;; rf2-xawwb — `↳ filters:` contextual label leads the events ribbon
      ;; (Figma-Make surface), followed by the add-filter (+) ICON button,
      ;; then the committed pills. The corner-down-right glyph mirrors the
      ;; tabs ribbon's `for selected event` label idiom.
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
      [filter-pills/events-add-filter-button dispatch]
      ;; rf2-3f2di A6 — the committed green/red filter pills.
      [ribbon-filter-pills dispatch {:filters filters}]
      ;; rf2-xawwb — the `N events filtered out` warning is pushed to the
      ;; RIGHT end (Figma-Make surface). The `margin-left: auto` shoves it
      ;; to the trailing edge regardless of pill count. Renders only when
      ;; N > 0. rf2-pjjwh removed the trailing `Clear Filters` button.
      (when (:visible? hidden-summary)
        [:div {:data-testid "rf-xray-events-ribbon-actions"
               :style {:display "flex" :align-items "center" :gap "12px"
                       :margin-left "auto"}}
         (filters-hidden-message hidden-summary)])]]))

;; rf2-ad7zx.12 + rf2-lnod7 + rf2-xawwb + rf2-pjjwh — the L2 list's
;; column-header row, reconciled to the Figma-Make EventList. The header
;; names the FOUR columns the rows align to, in Figma-Make order:
;; `event id` · `source` · `timestamp` · `duration` (event-id leads;
;; source follows). The column widths mirror the row layout below: the
;; flexible `event id` column, a fixed `source` tag column, then the
;; right-aligned `timestamp` chip and `duration` cells. (rf2-pjjwh
;; retired the leading focus gutter the mock didn't carry.)

(defn- l2-column-header
  "Sticky column-header row for the L2 event list (rf2-ad7zx.12 + rf2-
  lnod7 + rf2-xawwb, Figma-Make EventList). Names the FOUR columns the
  rows align to, in Figma-Make order — `event id` · `source` ·
  `timestamp` · `duration`. Caption-weight, muted, on the chrome surface
  so it reads as chrome rather than data.

  rf2-6ni62 — accepts `col-widths` (the resolved
  `{:source N :timestamp N :duration N}` map) so the header column
  widths read from the SAME source the rows do. Dividers between
  columns carry the drag affordance — pointerdown begins a drag,
  arrow keys do a fine resize, double-click resets to default.

  rf2-r0o63 — `dispatch-fn` is the frame-aware dispatcher captured by
  the `event-list` reg-view body, threaded to each divider so resize
  writes land on the instance frame."
  [col-widths dispatch-fn]
  (let [dispatch-fn (or dispatch-fn rf/dispatch*)
        cell {:color       (:text-tertiary tokens)
              :font-family sans-stack
              :font-size   (:caption type-scale)
              :font-weight 500
              :text-transform "lowercase"
              :white-space "nowrap"}]
    [:div {:data-testid "rf-xray-event-list-header"
           :role        "row"
           ;; rf2-ad7zx.15 / rf2-6ni62 — the header shares the EXACT
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
     ;; rf2-hga49 — empty leading-gutter spacer matching the data rows'
     ;; 10px selection-caret gutter so the `event id` header label sits
     ;; flush over the row keywords (no 10px column drift). Shares the
     ;; row's gutter width + flex-shrink:0 exactly.
     [:span {:data-testid "rf-xray-event-list-col-caret-gutter"
             :aria-hidden "true"
             :style {:flex-shrink 0 :width "10px"}}]
     ;; rf2-xawwb — column order is `event id` FIRST, then `source`
     ;; (Figma-Make surface). The event-id is the primary read; source is
     ;; secondary context, so the id leads. The `event id` column is
     ;; LEFT-aligned (the row's keyword sits flush under this label).
     [:span {:data-testid "rf-xray-event-list-col-event-id"
             :style (merge cell {:flex "1 1 auto" :min-width "0"
                                 :text-align "left"})}
      "event id"]
     ;; rf2-6ni62 — divider between `event id` (flex) and `source`.
     [col-divider {:col-id    :source
                   :col-px    (:source col-widths)
                   :row-height "100%"
                   :dispatch-fn dispatch-fn}]
     [:span {:data-testid "rf-xray-event-list-col-source"
             :style (merge cell {:width (->px (:source col-widths))
                                 :flex-shrink 0})}
      "source"]
     ;; rf2-6ni62 — divider between `source` and `timestamp`.
     [col-divider {:col-id    :timestamp
                   :col-px    (:timestamp col-widths)
                   :row-height "100%"
                   :dispatch-fn dispatch-fn}]
     [:span {:data-testid "rf-xray-event-list-col-timestamp"
             :style (merge cell {:flex-shrink 0 :text-align "right"
                                 :width (->px (:timestamp col-widths))})}
      "timestamp"]
     ;; rf2-6ni62 — divider between `timestamp` and `duration`.
     [col-divider {:col-id    :duration
                   :col-px    (:duration col-widths)
                   :row-height "100%"
                   :dispatch-fn dispatch-fn}]
     ;; rf2-lnod7 — the fourth Figma column. Restored after the gap
     ;; audit (rf2-4297k) found the live header carried only three
     ;; columns and the duration was clipped off the right edge.
     [:span {:data-testid "rf-xray-event-list-col-duration"
             :style (merge cell {:flex-shrink 0 :text-align "right"
                                 :width (->px (:duration col-widths))})}
      "duration"]]))

(rf/reg-view event-list
  "L2 event list — per spec/018 §4 Event list. Single-line rows,
  latest-on-bottom, ~8 visible at the tightened 22px row height
  (rf2-htik0 Bug 2 — was 28px row × 224px container; Xray is
  info-dense and the earlier rhythm wasted vertical canvas).

  Container default height: 8 rows × 22px + 7 × 2px gap + 8px outer
  padding ≈ 200px. The live height reads from
  `:rf.xray/events-list-height-px` (rf2-t2dsh) so the L2/L3 seam
  handle's drag writes lift the list reactively. `min-height` drops
  to `config/min-events-list-height-px` (48px == 2 rows + chrome) —
  the same floor the seam-handle clamp enforces.

  Per rf2-t2dsh the bottom-right browser-native `:resize \"vertical\"`
  corner-grip was retired — the seam handle that sits on the L2/L3
  boundary is the single resize affordance now, carrying persistence
  + keyboard + reset that the corner-grip lacked.

  Per spec/018 §6 sub-graph + rf2-ak4ms: reads `:rf.xray/filtered-
  cascades` (NOT raw `:rf.xray/cascades`) so the L1 ribbon's IN/OUT
  pills drive the list at the data layer — virtualisation budgets
  the post-filter row count, and the ribbon's `[◀ ▶ ⏭]` nav walks
  the same filtered list (per spec/018 §6 'Atomicity contract').

  Per rf2-in6l2 `reg-view`-registered so subscribes resolve to
  `:rf/xray`.

  Per rf2-639lc the list filters out `:ungrouped` cascades (those
  with no `:event` vector — registry-time emits / frame lifecycle
  outside a drain / REPL evals). Without the filter the L2 list
  rendered a leading `<no event>` placeholder row that leaked the
  projection's internal bucket into the user-facing event timeline.
  Other panels (Performance, etc.) keep reading
  `:rf.xray/cascades` directly so the bucket remains available where
  it is meaningful.

  Per rf2-ieg6d Bug 1 the focused row carries a `:ref` callback that
  scrolls it into view when (a) focus has just moved to a new id AND
  (b) the spine is in LIVE+head mode (i.e. the auto-tracking branch
  from `spine/compose-focus`). RETRO clicks place the row where the
  user clicked, so the scroll-into-view is suppressed there to avoid
  stealing the cursor. Per rf2-ieg6d Bug 2 the container carries
  Firefox's standardised `scrollbar-width`/`-color`; WebKit/Blink
  rules ship via a one-shot `<style>` injection (see
  `inject-scrollbar-style!`)."
  []
  ;; rf2-ieg6d Bug 2 — idempotent stylesheet injection. Lives in the
  ;; reg-view body so it runs on first paint of the L2 list (which is
  ;; mounted by the shell-view); defonce + DOM guards keep it a
  ;; no-op everywhere it matters.
  (inject-scrollbar-style!)
  (let [;; rf2-6ni62 — subscribe ONCE per L2 paint; thread the resolved
        ;; widths map through to the header + every row so the two
        ;; surfaces never drift out of column alignment.
        col-widths     @(rf/subscribe [:rf.xray/event-list-col-widths])
        ;; rf2-t2dsh — list height is driven by the L2/L3 seam handle.
        ;; The sub returns a clamped px value; default == 200 px.
        list-height-px @(rf/subscribe [:rf.xray/events-list-height-px])
        cascades       @(rf/subscribe [:rf.xray/filtered-cascades])
        ;; rf2-4vp5j — the hidden-by-filters message moved UP to the
        ;; events ribbon (`events-ribbon`); the L2 list no longer renders
        ;; the banner itself. The events ribbon is the always-present
        ;; second stratum so the count surfaces above the list rather
        ;; than as an inline banner inside it.
        focus          @(rf/subscribe [:rf.xray/focus])
        ;; rf2-r9lyy — opt-in for the `:ungrouped` pseudo-cascade
        ;; bucket. Default OFF preserves silent-by-default; ON
        ;; surfaces the bucket as a muted L2 row that focuses the
        ;; bucket on click so downstream panels populate.
        show-ungrouped? @(rf/subscribe [:rf.xray/show-ungrouped?])
        ;; rf2-0s2at — one subscribe per render drives every chip's
        ;; relative-time text. The sub returns the dispatched-time of
        ;; the most recent cascade (the anchor flips on event arrival,
        ;; not on a per-second tick). Falls back to `(interop/now-ms)`
        ;; when the buffer is empty / no cascade carries a stamp — at
        ;; that point there are no rows to render against the anchor
        ;; anyway, but the chip's render-time guard keeps the bucket
        ;; computation defined.
        now-ms         (or @(rf/subscribe [:rf.xray/relative-time-now-ms])
                           (interop/now-ms))
        focused-id     (:dispatch-id focus)
        ;; LIVE+head+not-paused = the auto-tracking branch from
        ;; spine/compose-focus. Only here do we want scroll-into-view
        ;; to fire on focus change; RETRO + paused-LIVE leave the
        ;; user's scroll position alone.
        auto-track?    (and (= :live (:mode focus))
                            (:head? focus)
                            (not (:paused? focus)))
        event-cascades (filterv #(l2-cascade-visible? % show-ungrouped?) cascades)]
    [:div {:data-testid "rf-xray-event-list-wrap"
           :style {:display "flex" :flex-direction "column"}}
     ;; rf2-4vp5j — the hidden-by-filters message now lives in the
     ;; events ribbon (above this list) rather than as an inline banner
     ;; here; the list is just the scroll container.
     [:div {:data-testid "rf-xray-event-list"
            :style {;; rf2-t2dsh — live height from the seam handle's
                    ;; sub; default 200 px (8 rows × 22 px + gaps +
                    ;; padding, per rf2-htik0). The `:resize` CSS rule
                    ;; that used to ride here was retired in favour of
                    ;; the seam-handle (Spec 007 §Splitter affordance).
                    :height        (->px list-height-px)
                    :min-height    (->px config/min-events-list-height-px)
                    :overflow-y    "auto"
                    :overflow-x    "hidden"
                    :background    (:bg-2 tokens)
                    :border-bottom (str "1px solid " (:border-subtle tokens))
                    :padding       "4px"
                    ;; rf2-ieg6d Bug 2 — Firefox standardised props for the
                    ;; slim scrollbar. WebKit/Blink pseudo-element rules ship
                    ;; via the `inject-scrollbar-style!` <style> tag above —
                    ;; pseudo-elements can't be set via React inline-style.
                    :scrollbar-width "thin"
                    :scrollbar-color "rgba(107, 112, 128, 0.4) transparent"}}
      (if (empty? event-cascades)
        [:div {:data-testid "rf-xray-event-list-empty"
               :style {:padding   "16px"
                       :color     (:text-secondary tokens)
                       :font-family sans-stack
                       :font-size (:body type-scale)}}
         "No events."]
        ;; rf2-ad7zx.12 — the Figma column-header row above the row
        ;; stack. Rendered only with rows present so the empty state
        ;; stays a clean "No events." message.
        ;; rf2-r0o63 — thread the captured frame-aware dispatcher into
        ;; the header dividers + every row's out-of-render dispatches
        ;; (body-click focus, context menu, col resize) so they land on
        ;; the surrounding instance frame.
        (list
         ^{:key "header"} [l2-column-header col-widths dispatch]
         (into ^{:key "rows"}
               [:ul {:style {:list-style "none" :margin 0 :padding 0
                            :display "flex" :flex-direction "column"
                            :gap "2px"}}]
              (for [cascade event-cascades]
                ^{:key (str (:dispatch-id cascade))}
                [event-row {:cascade     cascade
                            :focused-id  focused-id
                            :auto-track? auto-track?
                            :now-ms      now-ms
                            :col-widths  col-widths
                            :dispatch-fn dispatch}]))))]]))

;; ---- L3 tab bar ----------------------------------------------------------

(defn- tab-button
  "One tab in the L3 tab bar — a ROUNDED-TOP folder tab on the DARK tabs
  ribbon (rf2-xawwb · Figma-Make surface). Each tab is a borderless
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

  rf2-xawwb — supersedes the rf2-3f2di underline treatment (a 2px
  `:accent` bottom border on a transparent bar) with the Figma-Make
  rounded-top tabs on the dark band.

  `aria-label` wraps the visible label as `Xray <tab-label> tab` so the
  button's accessible name never collides with host-app role queries
  (Playwright's `getByRole('button', {name: '-'})` matched the old
  `App-db` tab when only `title` was set). This wrapping is why the
  app-db tab can safely carry the lowercase library label `app-db`
  (rf2-okvit) — the accessible name is `Xray app-db tab`, not the bare
  `app-db`.

  Per rf2-lvf8t (rf2-q7who Thread B) each button carries `role='tab'`
  and `aria-selected={active?}` so the tab strip exposes the proper
  ARIA tab pattern. Assistive tech announces the buttons as tabs
  rather than generic buttons and reads the selected state correctly;
  `getByRole('tab')` lookups in host integration tests resolve here.

  `:dispatch-fn` (rf2-r0o63) is the frame-aware dispatcher captured by
  the `tab-bar` reg-view so the select-tab click lands on the instance
  frame, not a `:rf/xray` literal."
  [{:keys [id label mnem active? dispatch-fn]}]
  (let [;; rf2-plajx — stable per-tab id so the controlled L4 panel's
        ;; `aria-labelledby` resolves to this button's accessible name.
        dispatch-fn (or dispatch-fn rf/dispatch*)
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
              :style {;; rf2-xawwb — ROUNDED-TOP tab on the dark tabs
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

(rf/reg-view tab-bar
  "L3 tab bar — six tabs per spec/018 §5 The 6 tabs (Routing
  promoted per rf2-nrbs9 — follows the cohesive-sub-domain rule;
  the Issues tab was removed per rf2-gbz39 — issues surface inline
  in the Epoch panel + the L2 event-row pink-wash + the ribbon).

  Per rf2-in6l2 `reg-view`-registered so subscribes resolve to
  `:rf/xray`.

  Per rf2-lvf8t (rf2-q7who Thread B) the wrapping element is a
  generic `<div>` carrying `role='tablist'` — the proper ARIA pattern
  for a tab strip. The earlier `<nav>` was both semantically wrong
  (tabs aren't site navigation) and a strict-mode hazard for host
  apps that also expose a `<nav>` landmark: Playwright's
  `getByRole('navigation')` lookup became ambiguous when Xray was
  mounted alongside a host nav, every Story integration test using
  the role failed (rf2-q7who Thread B — discovered via rf2-drprn).
  Per-tab buttons carry `role='tab'` + `aria-selected` (see
  `tab-button`). `data-testid='rf-xray-tab-bar'` is unchanged.

  ## rf2-hga49 — `Reset` rewind button (far right)

  The ribbon's far-right (after a `margin-left:auto` spacer) carries
  the `Reset` button — the UI half of the inspect-vs-rewind principle.
  It dispatches `:rf.xray/reset-to-epoch` with the OBSERVED frame
  (`:rf.xray/observed-frame` — the frame-switcher selection, NOT
  `:rf/xray`) and the currently-focused epoch-id
  (`:rf.xray/focus-epoch-id`), rewinding that frame's live `app-db` to
  the epoch's `:db-after`. No dialog, no confirmation. Disabled when
  no epoch is focused. On the rare framework failure (epoch aged out /
  restore-during-drain) the effect sets `:rf.xray/reset-flash` — a
  brief inline message, never a modal, never a silent lie."
  []
  (let [selected     @(rf/subscribe [:rf.xray/selected-tab])
        ;; rf2-hga49 — rewind target: the OBSERVED app frame + the
        ;; focused epoch. `:subscribe` (the reg-view-injected handle)
        ;; resolves both off `:rf/xray`'s own app-db where the spine
        ;; lives, but the values it returns point at the OBSERVED frame.
        observed     @(subscribe [:rf.xray/observed-frame])
        focus-epoch  @(subscribe [:rf.xray/focus-epoch-id])
        reset-flash  @(subscribe [:rf.xray/reset-flash])
        can-reset?   (some? focus-epoch)]
    [:div {:data-testid "rf-xray-tab-bar"
           :role        "tablist"
           :aria-label  "Xray panel tabs"
           ;; rf2-xawwb — DARK tabs ribbon (Figma-Make surface). The tab
           ;; strip becomes a dark band carrying rounded-top tab buttons.
           ;; `align-items: flex-end` so each rounded-top tab sits flush
           ;; on the bar's bottom edge (the active tab's light fill reads
           ;; as a folder-tab lifting onto the panel below). `gap: 3px`
           ;; gives the rounded tabs breathing room. Prefixed with the
           ;; `for selected event` contextual label.
           :style {:display       "flex"
                   :align-items   "flex-end"
                   :gap           "3px"
                   :height        "34px"
                   :padding       "0 12px"
                   :background    (:chrome-ribbon-bg tokens)
                   :border-top    (str "1px solid " (:border-subtle tokens))
                   :border-bottom (str "1px solid " (:border-subtle tokens))}}
     ;; rf2-xawwb — `↳ for selected event` contextual label (Figma-Make
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
      ;; rf2-hga49 — relabelled from `for selected event` to the terser
      ;; `selected` (the glyph + styling already carry the "this is the
      ;; selected event" sense; the long copy was redundant).
      "selected"]
     ;; rf2-2moh1 — iterate `dynamic-tabs` (registry-derived) rather
     ;; than a literal vector. Tab order follows each entry's `:order`.
     (for [{:keys [id] :as tab} (dynamic-tabs)]
       ^{:key id}
       ;; rf2-r0o63 — thread the captured frame-aware dispatcher so the
       ;; tab click's select-tab write lands on the instance frame.
       [tab-button (assoc tab :active? (= id selected) :dispatch-fn dispatch)])
     ;; rf2-hga49 — `margin-left:auto` spacer pushes the Reset cluster to
     ;; the FAR RIGHT of the ribbon, past the tab buttons.
     [:span {:data-testid "rf-xray-tab-bar-spacer"
             :style {:margin-left "auto"}}]
     ;; rf2-hga49 — the inline failure flash (rendered ONLY on failure).
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
     ;; rf2-hga49 — `Reset` rewind button. Dispatches
     ;; `:rf.xray/reset-to-epoch` against the OBSERVED frame + focused
     ;; epoch. Disabled (and visibly dimmed) when no epoch is focused.
     ;; The icon is a unicode anticlockwise-arrow (↺) matching the
     ;; lucide `RotateCcw` rewind glyph used elsewhere; inline SVG isn't
     ;; idiomatic in this pure-hiccup view (see `ribbon-icons`).
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

;; ---- L4 detail panel -----------------------------------------------------

(defn- unknown-tab-stub
  [selected]
  [:div {:data-testid "rf-xray-tab-unknown"
         :style {:padding "16px"
                 :color   (:text-secondary tokens)
                 :font-family sans-stack}}
   "Unknown tab: " [:code (pr-str selected)]])

(rf/reg-view detail-panel
  "L4 detail panel — mounts the active `:rf.xray/selected-tab`'s panel
  via the registry-driven `panel-registry/tab-by-id :dynamic` lookup
  (rf2-2moh1). All six Dynamic tabs mount real panels (Event /
  App-db / Views / Trace / Machines / Routing); the former
  literal case-switch is gone, and the Issues tab was removed per
  rf2-gbz39 (Option (c) — issues surface inline + event-row + ribbon).
  An unrecognised tab falls back to `unknown-tab-stub`.

  Per rf2-in6l2 `reg-view`-registered so subscribes resolve to
  `:rf/xray`. The wrapping `<div>` paints `bg-2` as a contrast
  safety net (rf2-q8154 — defence-in-depth for panels that fail to
  set their own background).

  ## rf2-5kfxe.3 — 180ms cross-fade on tab switch

  Spec/007 §Motion + animation calls for a 180ms cross-fade when the
  user switches L4 tabs. The registry mount below is otherwise an
  instant DOM swap. The trick: wrap the chosen panel in an inner `<div>`
  *keyed on `selected`*. When the key changes Reagent unmounts the
  previous wrapper + mounts a new one, which auto-plays the
  `rf-xray-fade-in` CSS animation declared in
  `theme/global-styles/motion-css`. Duration is interpolated through
  the `--rf-xray-motion-scale` seam (rf2-5kfxe.5) so the fade
  collapses to 0ms under `prefers-reduced-motion: reduce`.

  The outer `<div>` keeps its `data-testid` stable across tab swaps so
  existing tests + `getByTestId` lookups still resolve — the cross-fade
  wrapper is purely internal."
  []
  (let [selected (or @(rf/subscribe [:rf.xray/selected-tab])
                     default-tab)]
    [:div {:data-testid (str "rf-xray-detail-panel-" (name selected))
           ;; rf2-plajx — L4 closes the tab/tabpanel loop. The L3
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
     ;; rf2-5kfxe.3 — re-mount on selected-tab change so the fade-in
     ;; keyframes auto-play. The `^{:key selected}` reader-meta is on a
     ;; *vector literal* (the wrapper `[:div ...]`), so Reagent's
     ;; `get-react-key` picks it up via the vector's meta (no
     ;; `with-meta` needed here — different from the function-call
     ;; case in `render-sections`).
     ^{:key selected}
     [:div {:data-testid (str "rf-xray-detail-panel-fade-"
                              (name selected))
            :style {:height     "100%"
                    ;; Keyframes named in `global-styles/motion-css`.
                    ;; Duration interpolated through the
                    ;; `--rf-xray-motion-scale` seam (rf2-5kfxe.5)
                    ;; via `theme.tokens/duration-css` so the
                    ;; 180ms constant + the seam-var name both live
                    ;; in tokens.cljc — one source of truth.
                    ;; `forwards` pins the end state (opacity 1) so
                    ;; the panel stays visible after the fade settles.
                    :animation  (str "rf-xray-fade-in "
                                     (duration-css (:fade-duration-ms motion))
                                     " ease-out forwards")}}
      ;; rf2-2moh1 — registry-driven panel mount. Each tab's per-panel
      ;; `install!` declares `:panel <view-fn>` via
      ;; `panel-registry/reg-l4-tab!`; the previous case-switch over
      ;; `{:event :app-db :views :trace :machines :routing}` is
      ;; replaced by a lookup against `tab-by-id :dynamic`. The six
      ;; tabs and their per-panel view fns each live colocated with
      ;; the panel's own subs / events / fxs in `panels/<panel>.cljs`
      ;; rather than the panel-cum-shell coupling the literal case-
      ;; switch encoded.
      (if-let [tab (panel-registry/tab-by-id :dynamic selected)]
        [(:panel tab)]
        [unknown-tab-stub selected])]]))

;; ---- Dynamic / Static surface composer (rf2-o5f5f.1) --------------------
;;
;; The shell exposes TWO modes (Dynamic — the 4-layer chrome below,
;; Static — the 3-layer registry-browse surface owned by
;; `static/shell.cljs`). The composer reads `:rf.xray/mode` and
;; renders either Dynamic or Static. Per rf2-8l3uk the
;; `:rf.xray/static-mode?` feature gate was removed — Static mode
;; is unconditionally available.
;;
;; The composer is `reg-view`-registered so the subscribe inside its
;; body resolves through React-context to `:rf/xray` — same
;; discipline as the rest of the shell.

(rf/reg-view dynamic-chrome
  "The Dynamic chrome wrapped as a single component. Per rf2-3f2di the
  top splits into two strata reconciled to the authority reference — the
  **chrome ribbon** (`ribbon`, bar-1: `Events` label + blue-filled nav +
  focus button + focus-chip + `Filters:` + add(+) on the left; Frame +
  Dynamic/Static dropdowns + indicators + `⚙`/`✕` on the right) and the
  **events ribbon** (`events-ribbon`, bar-2: the `N events filtered out`
  warning + the green/red committed pills, with Clear Filters on the
  right when active) — above the L2 event list, L3 tab bar, and L4
  detail panel. Extracted from the inline composition in `shell-view` so
  the Static surface can swap in alongside it via the mode composer
  (rf2-o5f5f.1).

  Per rf2-in6l2 `reg-view`-registered for parity with every other
  shell region.

  ## rf2-uu3lp — DOM-rooted via `display: contents`

  The five children are stacked flex items of `shell-view`'s flex
  column (L1 ribbon · events ribbon · L2 list · L3 tab bar · L4 detail
  panel). A bare React Fragment root would skip the source-coord DOM
  annotation (Spec 006 §Documented exemption: non-DOM roots) and emit
  a one-shot warning. A wrapper `<div>` with `display: contents`
  participates in the DOM tree (so `data-rf2-source-coord` has a home
  and click-to-source works) while being neutralised for layout — the
  children render as if they were direct flex items of the
  shell-view's column. The `data-rf-xray-dynamic-chrome` attr is a
  test-friendly handle if a future selector needs it; no production
  code reads it today."
  []
  [:div {:data-rf-xray-dynamic-chrome ""
         :style {:display "contents"}}
   [ribbon {}]
   [events-ribbon]
   [event-list]
   ;; rf2-t2dsh — L2/L3 seam handle. Click-and-drag anywhere along the
   ;; horizontal seam between the event list and the tab bar resizes
   ;; the events list. Replaces the previous browser-native
   ;; `:resize \"vertical\"` corner-grip per spec/007-UX-IA.md
   ;; §Splitter affordance.
   [resize-handle/SeamHandle]
   [tab-bar]
   [detail-panel]])

(rf/reg-view surface-composer
  "Mode-aware composer (rf2-o5f5f.1). Reads `:rf.xray/mode` and renders
  either the Dynamic 4-layer chrome OR the Static 3-layer surface.

  Per rf2-8l3uk the `:rf.xray/static-mode?` feature gate was removed
  — Static mode is unconditionally available; the active mode drives
  the swap.

  Per rf2-in6l2 `reg-view`-registered so the subscribe resolves to
  `:rf/xray` via React-context.

  ## rf2-uu3lp — DOM-rooted via `display: contents`

  Returning the inner component head directly (`[dynamic-chrome]` /
  `[static-shell/surface]`) would skip the source-coord DOM
  annotation (Spec 006 §Documented exemption: component head) and
  emit a one-shot warning. A `display: contents` wrapper lets
  `data-rf2-source-coord` land on a real DOM node while keeping the
  inner surface as the effective layout child of `shell-view`'s flex
  column."
  []
  (let [mode @(rf/subscribe [:rf.xray/mode])]
    [:div {:data-rf-xray-surface-composer ""
           :style {:display "contents"}}
     (case mode
       :static  [static-shell/surface]
       [dynamic-chrome])]))

;; ---- shell view ----------------------------------------------------------

(rf/reg-view shell-view
  "The full Xray shell — wraps the 4-layer chrome in a frame-provider
  so descendant `subscribe` / `dispatch` resolve to the isolated
  frame. Default `:inline` mode renders in normal document flow inside
  the app-provided right layout host. `:overlay` and `:popout` remain
  available debug/manual modes.

  ## `:frame-id` opt (rf2-lnluk) — de-singletoned shell frame

  The shell's app-db lives in a frame. The PRODUCTION singleton mounts
  against `default-frame-id` (`:rf/xray`) — pass no `:frame-id` and the
  production behaviour is unchanged. Testbeds that mount N shells
  side-by-side (the panel-gallery `:variants-grid`, a Story workspace)
  pass DISTINCT `:frame-id`s so each cell's state (focused epoch,
  selected tab, theme) is fully isolated — driving one shell does not
  move the others.

  The frame-id flows two ways: it parameterizes the wrapping
  `[frame-provider {:frame frame-id}]` (so every reg-view descendant
  resolves to it through React-context), AND it backs the few
  out-of-render subscribes/dispatches `shell-view` itself issues from
  OUTSIDE its own provider (the modal-positioning + mode reads below).
  Handlers register GLOBALLY once under `:rf.xray/*`; only the frame-id
  for app-db isolation threads through (no per-instance registration).

  Per rf2-in6l2 `reg-view`-registered for parity with every other
  shell region. The shell-view itself sits OUTSIDE its own frame-
  provider (it's the mount root) so React-context inside `shell-view`'s
  body still resolves to the default — every subscribing child is its
  own reg-view component so the surrounding Provider reaches them via
  React context.

  ## `:modal-positioning` opt (rf2-om6fa)

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
  per-cell. Per rf2-lnluk the open-state flags (`:rf.xray/<modal>-
  open?`) are now also per-instance — pass a distinct `:frame-id` per
  cell and opening Settings in one cell opens Settings in that cell
  only. (Cells that share a frame-id still share state — that's the
  contract: one frame, one app-db.)"
  [& [{:keys [mode modal-positioning frame-id]
       :or   {mode :inline modal-positioning :fixed
              frame-id default-frame-id}}]]
  ;; rf2-5kfxe.1 — wire Inter + JetBrains Mono once on first paint of
  ;; the shell. Idempotent (`defonce` + id-keyed DOM probe inside) so
  ;; shadow-cljs `:after-load` and repeated mounts are no-ops. Future
  ;; cluster commits extend this install with `@keyframes` + the
  ;; reduced-motion seam so all global stylesheet writes converge on
  ;; one entry point.
  (global-styles/install!)
  ;; Idempotent app-db write so every modal can read the positioning
  ;; via the `:rf.xray/modal-positioning` sub. Guarded against
  ;; re-dispatch by comparing the current slot to the prop — once the
  ;; slot matches the prop, the `when` short-circuits and the render
  ;; quiesces. `dispatch-sync` so the slot lands BEFORE the modal
  ;; children mount and read the sub on this same render pass; without
  ;; sync the first paint of a fresh shell would render every modal's
  ;; backdrop at the default `:fixed` before the async router drains.
  ;; Sub + dispatch route via the instance `frame-id` so the read/write
  ;; lands on THIS shell's app-db (`shell-view` itself sits OUTSIDE the
  ;; `frame-provider` in the tree below — the React-context tier
  ;; doesn't reach this call site, hence the explicit frame arg). Per
  ;; rf2-lnluk the explicit frame is the instance `frame-id`, not a
  ;; `:rf/xray` literal — N shells stay isolated.
  (let [current-positioning @(rf/subscribe frame-id [:rf.xray/modal-positioning])]
    (when (not= current-positioning modal-positioning)
      (rf/dispatch-sync [:rf.xray/set-modal-positioning modal-positioning]
                        {:frame frame-id})))
  ;; rf2-ad7zx.13 / spec/022 — the lens mode (`:rf.xray/mode` =
  ;; :dynamic | :static) drives the `mode-dynamic` / `mode-static`
  ;; root class, which still gates functional behaviour (motion / pulse
  ;; dampening in Static). Post rf2-ad7zx.13 the Figma export carries a
  ;; SINGLE accent (GitHub blue) — the mode class no longer re-points
  ;; `--rf-xray-accent`, so the chrome accent is the same blue in both
  ;; modes. Subscribed via the explicit instance `frame-id` (same shape
  ;; as the modal-positioning read above — `shell-view` sits outside
  ;; its own frame-provider; rf2-lnluk threads the instance frame, not
  ;; a `:rf/xray` literal).
  (let [lens-mode @(rf/subscribe frame-id [:rf.xray/mode])]
   ;; rf2-uu3lp — the outer `<div>` IS the shell-view's root so the
   ;; source-coord walk has a DOM node to annotate (Spec 006
   ;; §Source-coord annotation; would otherwise warn-once because the
   ;; previous root was the non-DOM `frame-provider` component head).
   ;; The frame-provider sits one level inside, wrapping every
   ;; subscribing child — React-context discipline is preserved. The
   ;; outer `<div>` carries attrs/styles only; it never subscribes,
   ;; so its position outside the frame is immaterial.
   [:div {:data-testid "rf-xray-shell"
          :class (str "mode-" (name (or lens-mode :dynamic)))
          ;; rf2-plajx — Xray shell root is a landmark. A 40%-
          ;; viewport overlay rendered as a bare `<div>` is invisible
          ;; to screen-reader landmark navigation (JAWS R-key /
          ;; NVDA D-key). `role="region"` + `aria-label` exposes the
          ;; shell as a labelled landmark so AT users can jump to it.
          ;; "region" (rather than "complementary" / "aside") matches
          ;; the audit's Q3 disposition: Xray is a global chrome
          ;; surface with its own internal landmark structure (L1
          ;; ribbon = toolbar, L3 = tablist, L4 = tabpanel) rather
          ;; than content complementary to the host's main.
          :role        "region"
          :aria-label  "Xray devtools"
          ;; Per rf2-zkfiz Q1-9 the spec-published mode axis is
          ;; `data-rf-xray-mode` (mount.cljs writes it on both the
          ;; root and the shell node). The previous `data-mode` echo
          ;; was a duplicate axis and is gone — tests + testbeds read
          ;; the rf-xray-prefixed name everywhere.
          :data-rf-xray-mode (name mode)
          ;; rf2-om6fa — the positioning attribute is published on the
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
    ;; rf2-uu3lp — frame-provider sits INSIDE the outer `<div>` (the
    ;; `<div>` carries the source-coord annotation as the DOM root).
    ;; Every subscribing child below is wrapped so the instance
    ;; `frame-id` flows through React-context (rf2-lnluk — the provider
    ;; frame is the parameterized instance frame, default `:rf/xray`).
    [rf/frame-provider {:frame frame-id}
    ;; Left-edge horizontal resize handle (rf2-x8h9y) — only renders
    ;; in `:inline` (right-rail) mode. Position-absolute pins it to
    ;; the LEFT edge of this flex container; the outer div is
    ;; `position: relative` in :inline so the handle's anchor
    ;; resolves correctly. The handle's drag math writes through
    ;; `:rf.xray/set-panel-width-px`, which clamps + persists +
    ;; pushes `--rf-xray-inline-width` onto the layout host so the
    ;; host's `flex-basis` re-evaluates this paint.
    [resize-handle/Handle mode]
    ;; Mode-aware surface (rf2-o5f5f.1). The composer reads
    ;; `:rf.xray/mode` and renders either the Dynamic 4-layer
    ;; chrome or the Static 3-layer surface. Per rf2-8l3uk the
    ;; `:rf.xray/static-mode?` feature gate was removed — Static
    ;; mode is unconditionally available.
    [surface-composer]
    ;; Command palette (rf2-wm7z4) — mounted at shell root so it
    ;; overlays the chrome. Modal short-circuits to nil when
    ;; `:rf.xray/palette-open?` is false; closed-state cost is one
    ;; subscribe + when-gate.
    [palette/Modal]
    ;; Filter edit popup (rf2-ak4ms) — mounted at shell root so it
    ;; overlays the chrome AND the palette modal (the popup's z-index
    ;; is one above the palette so an edit opened from a palette
    ;; context wins focus). Modal short-circuits to nil when
    ;; `:rf.xray/edit-popup-open?` is false; closed-state cost is
    ;; one subscribe + when-gate.
    [filters/Modal]
    ;; Settings popup (rf2-9poxq) — same mount discipline as the
    ;; palette + edit popup: shell-root mount so subscribes resolve
    ;; through the shell's `:rf/xray` frame-provider, and the modal
    ;; short-circuits to nil when `:rf.xray/settings-open?` is false.
    [settings-popup/Modal]
    ;; Open-in-editor 'pick an editor in Settings' hint toast
    ;; (rf2-4s08ov). Same shell-root mount discipline as the modals so
    ;; its subscribe resolves through the `:rf/xray` frame-provider; the
    ;; toast is `position: absolute` in the bottom-right corner and
    ;; short-circuits to nil when `:rf.xray/editor-hint-open?` is false.
    ;; Shown when an open-in-editor chip is clicked but no editor is
    ;; effectively configured (host never set `:rf.xray/editor`, no
    ;; operator override) — instead of the silent `vscode:` no-op.
    [editor-hint/Toast]
    ;; Cancellation-cascade popover (rf2-59e7k) — single waterfall view
    ;; of the rf2-wvkn cancellation contract. Opened from the Trace tab
    ;; (right-click a destroy-event row → 'Show cancellation cascade')
    ;; or imperatively via `:rf.xray/cancellation-cascade-open`. Same
    ;; mount discipline as the other popovers: shell-root mount so
    ;; subscribes resolve through the `:rf/xray` frame-provider;
    ;; closed-state cost is one subscribe + a when-gate.
    [cancellation-cascade/Popover]
    ;; rf2-nugvv (2026-06-04) — the Share modal (rf2-nqw0v Phase 5) is
    ;; removed. The Machine panel's Share button was its sole UI entry
    ;; point, so the modal, its shell mount, and the share-URL infra
    ;; all go with it.
    ;; Mute manager modal (rf2-ikuwt) — lists every muted event-id
    ;; with per-row unmute buttons + a 'Unmute all' affordance. Same
    ;; mount discipline as the other modals: shell-root mount so the
    ;; subscribes resolve through the `:rf/xray` frame-provider;
    ;; closed-state cost is one subscribe + a when-gate.
    [spine-filters/Modal]
    ;; Row context menu (rf2-ikuwt) — small floating popover opened
    ;; by right-click on an L2 event row. Carries 'Mute <event-id>'
    ;; + 'Always hide this event-type…'. Mounted at shell-view root
    ;; so the menu floats above the L2 list's overflow:hidden
    ;; clipping. Closed-state cost is one subscribe + a when-gate.
    [spine-filters/RowContextMenu]
    ;; App-DB segment-inspector popup (rf2-e9tb0) — opens when any
    ;; path-segment in the App-DB Diff breadcrumb is clicked. Same
    ;; mount discipline as the other modals: shell-root mount so the
    ;; popup's subscribes resolve through the shell's `:rf/xray`
    ;; frame-provider; closed-state cost is one subscribe + a when-
    ;; gate.
    [app-db-segment-inspector/Popup]
    ;; Data-display popup stack (rf2-l4625) — overlay surface for the
    ;; "open in popup" affordance on per-panel `[ei/edn-inspector]`
    ;; mounts. Reads `:rf.xray.edn-inspector-popup/stack` + `/entries`;
    ;; renders nothing when the stack is empty (closed-state cost is
    ;; one subscribe + a when-gate). Mount discipline matches the
    ;; other modal stacks: shell-root mount so the stack's subscribes
    ;; resolve through the shell's `:rf/xray` frame-provider.
    [edn-inspector-popup/edn-inspector-popup-stack]]]))
