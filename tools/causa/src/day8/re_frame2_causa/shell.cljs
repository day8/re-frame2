(ns day8.re-frame2-causa.shell
  "The Causa shell — 4-layer chrome per `tools/causa/spec/018-Event-Spine.md`
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
      │ L3  Tab bar (40px) — 8 tabs                           │  projection selector
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
  the `[◀ ▶ ⏭]` cluster + the `:rf.causa/focus` sub. Space / L / G
  keybindings preserve the toggle access the pill used to surface):

  - **Nav** (`◀ ▶ ⏭`) — back / forward / fast-forward through the
    spine. Dispatches `:rf.causa/focus-cascade-prev` / `-next` /
    `:rf.causa/follow-head`. Pressing `⏭` (or `Space` in paused-LIVE,
    or `L` in RETRO) snaps focus back to head — the operations the
    mode pill used to host.
  - **Frame picker** — single-select dropdown over the cascade list's
    distinct frames. Excludes `:rf/causa` by default per §8 I1.
  - **Filter pills** — IN (green, `+`) and OUT (magenta, `×`) pills
    + trailing `[+]` add-pill. Click any pill → edit popup.
  - **Right icons** — `⚙` settings · `✕` close. (Pop-out is a
    programmatic API only — `(causa/popout!)` — no ribbon affordance
    until the second-window UX lands per spec/011-Launch-Modes.md.)

  The REDACTED indicator (`[● REDACTED N]`) renders inline next to
  the right-icons cluster when the suppressed-sensitive count is
  positive, surfacing privacy state without a permanent ribbon slot.

  ## Event list (L2)

  Single-line rows, latest-on-bottom, 8 visible by default. Each row:
  gutter glyph (`● ◉ x ▥`) + event-id + right-aligned badge cluster
  (`⚠ 🌐 🤖`) + trailing redaction marker (`[● REDACTED N]`). Click
  a row → `:rf.causa/focus-cascade <id>` flips spine to RETRO and
  rebinds every dependent surface in one frame.

  ## Tab bar (L3)

  Eight tabs, mnemonic letters per spec/018 §11:

      Event (e) · App-db (a) · Views (v) · Trace (t) · Machines (m) · Machines Canvas (c) · Routing (r) · Issues (i)

  Selection lives on `:rf.causa/selected-tab` and drives the L4
  detail panel's case switch. Routing was promoted to its own tab
  per rf2-nrbs9 (Mike's design call, 2026-05-18); Machines Canvas
  was promoted per rf2-mkpnb — both follow the cohesive-sub-domain
  rule (sub-domains earn their own lens tab rather than overloading
  the parent tab).

  ## Detail panel (L4)

  Renders the active tab's projection of the focused event. Tabs
  reuse the existing per-panel views where possible (Event tab →
  `event-detail/Panel`, App-db → `app-db-diff/Panel`, Trace →
  `trace/Panel`, Machines → `machine-inspector/Panel`, Machines
  Canvas → `panels.machines-canvas.panel/Panel`, Routing →
  `routing/Panel`, Issues → `issues-ribbon/Panel`). The Views tab
  is a stub pending the §5.3 per-view content design.

  ## Frame isolation (rf2-tijr Option C + rf2-in6l2)

  The shell is wrapped in `[rf/frame-provider {:frame :rf/causa}]`.
  Every `subscribe` / `dispatch` inside the shell resolves to the
  `:rf/causa` frame; the host's `:rf/default` is untouched. Causa's
  own registrations under `:rf.causa/*` operate against `:rf/causa`'s
  db when called from inside the shell.

  Per rf2-in6l2 every subscribing region of the shell is `reg-view`-
  registered so its rendered React component carries `:contextType
  frame-context` — the closest enclosing Provider's `:rf/causa`
  flows through React-context and `(rf/subscribe …)` inside the body
  resolves to the registered frame. With plain `defn`s the
  React-context tier would be skipped (Spec 004 §Plain Reagent fns
  do not pick up the surrounding frame) and subscribe would fall
  through to `:rf/default` — silently routing every Causa panel
  query into the host's app-db.

  ## Pure hiccup

  Per rf2-tijr the view code is pure hiccup. The substrate adapter's
  render fn (`rf/render`) handles the substrate-specific mount in
  `mount.cljs`. No per-substrate switches in view code."
  (:require [clojure.string :as str]
            [re-frame.core :as rf]
            [re-frame.interop :as interop]
            [day8.re-frame2-causa.filters :as filters]
            [day8.re-frame2-causa.filters.pills :as filter-pills]
            [day8.re-frame2-causa.focus-helpers :as fh]
            [day8.re-frame2-causa.frame-switcher :as frame-switcher]
            [day8.re-frame2-causa.panel-registry :as panel-registry]
            [day8.re-frame2-causa.panels.app-db-segment-inspector
             :as app-db-segment-inspector]
            [day8.re-frame2-causa.panels.cancellation-cascade :as cancellation-cascade]
            ;; Panel views (Event / App DB / Views / Trace / Machines /
            ;; Routing / Issues) are pulled in via the L4 tab registry —
            ;; each panel's `install!` registers `{:panel <view-fn>}`
            ;; with `panel-registry/reg-l4-tab!` (rf2-2moh1) and
            ;; `detail-panel` reaches the entry through
            ;; `panel-registry/tab-by-id`. The shell no longer requires
            ;; those panel nses directly; the event-detail ns is the
            ;; one exception (kept for the inline cascade-outcome helpers
            ;; the ribbon's :rf.causa/event-detail subscribers consume).
            [day8.re-frame2-causa.panels.event-detail :as event-detail]
            [day8.re-frame2-causa.panels.event.event-status-colour :as event-status]
            ;; rf2-gf58j — L2 epoch-timeline dispatch-origin prefix +
            ;; activity badges. Pure-fn helpers live in `panels/l2-
            ;; timeline.cljc` so the shape is JVM-testable; the shell
            ;; consumes them in `event-row` (single insertion site).
            [day8.re-frame2-causa.panels.l2-timeline :as l2-timeline]
            [day8.re-frame2-causa.palette :as palette]
            [day8.re-frame2-causa.resize-handle :as resize-handle]
            [day8.re-frame2-causa.settings.popup :as settings-popup]
            [day8.re-frame2-causa.share-modal :as share-modal]
            [day8.re-frame2-causa.spine-filters :as spine-filters]
            [day8.re-frame2-causa.static.mode-pill :as mode-pill]
            [day8.re-frame2-causa.static.shell :as static-shell]
            [day8.re-frame2-causa.theme.global-styles :as global-styles]
            [day8.re-frame2-causa.theme.tokens
             :as t
             :refer [tokens type-scale layout sans-stack mono-stack]]))

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
;; Labels use spaces (`App DB` not `App-db`) so the rendered text
;; carries no `-` glyphs — Playwright's `getByRole('button', {name:
;; '-'})` accessible-name matching is substring-based and would
;; otherwise lasso our tab buttons in any host whose app exposes
;; `-`-named buttons (counter `+ / -`).
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
  Event tab detail (which has plenty of room).

  - `event-id` is the first element of the event vector.
  - Renders in the accent-violet colour so it pops out of the row.
  - When the cascade carries no event vector, falls back to a
    `<no event>` chip in the secondary text colour. (Per rf2-639lc
    the L2 event list filters those cascades out via
    `cascade-has-event?`; the fallback is defence-in-depth.)"
  [event-vec]
  (if (vector? event-vec)
    [:span {:style {:color       (:accent-violet tokens)
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
  Event tab detail panel on row click.

  Pure data — JVM-runnable. nil-safe per cascade slot. Returns a
  newline-joined string suitable for an HTML `:title` attribute.

  Slot ordering (most useful first):
    1. Full event vector (untruncated)
    2. `#<dispatch-id>` (the sequence number)
    3. `frame: <id>`
    4. Source coordinate `<file>:<line>:<col>` (when `:rf.trace/call-site`
       rode the `:event/dispatched` emit per rf2-twt7m Change 1)
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
  produced by `re-frame.trace.projection/group-cascades` for registry-
  time emits / frame lifecycle outside a drain / REPL evals — those
  carry no event vector. Per rf2-639lc the L2 event list filters this
  bucket out so the user never sees a `<no event>` placeholder row."
  [cascade]
  (some? (event-id-of-cascade cascade)))

(defn ungrouped-cascade?
  "True iff `cascade` is the `:ungrouped` bucket produced by
  `re-frame.trace.projection/group-cascades`. Used to give the
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

(defn gutter-glyph
  "Pick the gutter glyph per spec/018 §4 Row anatomy. The selected row
  gets `◉`; an errored row gets `x`; a wholly-redacted row gets `▥`;
  default is `●`."
  [cascade focused-id]
  (cond
    (= (:dispatch-id cascade) focused-id) "◉"
    (boolean (seq (:errors cascade)))     "x"
    (:whole-redacted? cascade)            "▥"
    :else                                 "●"))

(defn row-badges
  "Per spec/018 §4 Row badges. Returns a vector of present badges in
  the fixed `[:warn :http :machine]` order. Stub heuristic until the
  per-row classifier lands — looks at the cascade's `:other` bucket
  for op-types we recognise."
  [cascade]
  (let [others (:other cascade)
        warn?  (some (fn [e] (or (= :error (:op-type e))
                                 (= :warning (:op-type e))))
                     others)
        http?  (some (fn [e] (when-let [op (:operation e)]
                               (let [n (str op)]
                                 (or (re-find #":http/" n)
                                     (re-find #":rf\.http" n)))))
                     others)
        machine? (some (fn [e] (when-let [op (:operation e)]
                                 (re-find #":machine" (str op))))
                       others)]
    (cond-> []
      warn?    (conj "⚠")
      http?    (conj "🌐")
      machine? (conj "🤖"))))

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
;; `:rf.causa/cascades`) so no timer / no internal trace pollution.
;;
;; The view subscribes to `:rf.causa/relative-time-now-ms` (sub
;; composed off `:rf.causa/cascades` — see `registry.cljs`).

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

(defn format-absolute-time
  "CLJS-side helper. Given an epoch-ms (the cascade's dispatched
  `:time`), returns an absolute-time tooltip string for the chip's
  `:title` attribute. Used as the power-user reveal that complements
  the relative chip — clicking the row still opens the Event lens, but
  a hover shows the precise walltime + epoch-ms.

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
  "Render the L2 row's right-aligned relative-time chip. `now-ms` is the
  anchor supplied by the L2 view — sourced from the
  `:rf.causa/relative-time-now-ms` sub (dispatched-time of the most
  recent cascade, per rf2-0s2at; flips on event arrival, not on a
  per-second tick). Renders nothing when the cascade carries no
  dispatched-time stamp."
  [cascade now-ms]
  (when-let [then-ms (cascade-dispatched-time-ms cascade)]
    (let [now      (or now-ms (interop/now-ms))
          label    (format-relative-time now then-ms)
          tooltip  (format-absolute-time then-ms)]
      [:span {:data-testid     "rf-causa-row-time-chip"
              :data-then-ms    (str then-ms)
              :title           tooltip
              :style {:color         (:text-tertiary tokens)
                      :flex-shrink   0
                      :font-family   mono-stack
                      :font-size     (:caption type-scale)
                      :margin-left   "4px"
                      :min-width     "30px"
                      :text-align    "right"
                      :white-space   "nowrap"}}
       label])))

;; ---- Relative-time anchor (rf2-0s2at) ------------------------------------
;;
;; No timer. The anchor is the dispatched-time of the most recent
;; cascade — see the `:rf.causa/relative-time-now-ms` sub in
;; `registry.cljs`. It re-fires on the standard reactive path when a
;; new cascade lands in `:rf.causa/cascades`, so old rows recompute
;; their relative-time exactly when fresh context arrives. (Earlier
;; rf2-vbbq0 design used a `setInterval`-driven tick; rf2-0s2at
;; replaced it after Mike observed constant L2 flicker watching the
;; parallel-frames testbed live.)

;; ---- L1 ribbon -----------------------------------------------------------

(defn- ribbon-nav-cluster
  "Nav cluster — `◀ ▶ ⏭` per spec/018 §3. Buttons dispatch
  `:rf.causa/focus-cascade-prev` / `-next` / `:rf.causa/follow-head`.

  `at-head?` (focus = most recent event) and `at-tail?` (focus = first
  event in buffer) come from the spine sub so the buttons can disable
  themselves at the boundary:

  - `◀` (back / prev) — disabled when `at-tail?` (no older event to
    step to).
  - `▶` (forward / next) — disabled when `at-head?` (already at the
    most recent event).
  - `⏭` (live / fast-forward) — never disabled; pressing it always
    snaps focus to head + resumes LIVE.

  (rf2-htik0 P1 — earlier wiring had the head/tail boundaries flipped
  so the disabled glyph dimmed the wrong button.)"
  [{:keys [at-head? at-tail?]}]
  (let [btn-style {:background     "transparent"
                   :border         (str "1px solid " (:border-default tokens))
                   :color          (:text-primary tokens)
                   :cursor         "pointer"
                   :padding        "2px 8px"
                   :border-radius  "4px"
                   :font-family    sans-stack
                   :font-size      (:body type-scale)}
        ;; Per rf2-fzbrw the visual + a11y signal must match the
        ;; functional signal: `cursor: not-allowed` so the mouse
        ;; pointer telegraphs the no-op, plus `aria-disabled` for
        ;; screen readers (the native `:disabled` attribute already
        ;; blocks click events at the DOM layer).
        dim       {:color (:text-tertiary tokens) :cursor "not-allowed"}]
    [:div {:data-testid "rf-causa-ribbon-nav"
           :style {:display "flex" :align-items "center" :gap "4px"}}
     [:button {:data-testid   "rf-causa-nav-prev"
               :on-click      (when-not at-tail?
                                #(rf/dispatch [:rf.causa/focus-cascade-prev] {:frame :rf/causa}))
               :disabled      (boolean at-tail?)
               :aria-disabled (boolean at-tail?)
               :title         "Step to previous event (j)"
               :style         (merge btn-style (when at-tail? dim))}
      "◀"]
     [:button {:data-testid   "rf-causa-nav-next"
               :on-click      (when-not at-head?
                                #(rf/dispatch [:rf.causa/focus-cascade-next] {:frame :rf/causa}))
               :disabled      (boolean at-head?)
               :aria-disabled (boolean at-head?)
               :title         "Step to next event (k)"
               :style         (merge btn-style (when at-head? dim))}
      "▶"]
     [:button {:data-testid "rf-causa-nav-head"
               :on-click    #(rf/dispatch [:rf.causa/follow-head] {:frame :rf/causa})
               :title       "Fast-forward to latest (G)"
               :style       btn-style}
      "⏭"]]))

;; The L1 frame-switcher slot lives in `frame_switcher.cljs` per rf2-iwwou
;; — the ribbon mounts `[frame-switcher/frame-switcher-view]` and reaches
;; the picker's contract surface through `:rf.causa/current-frame` /
;; `:rf.causa/available-frames` / `:rf.causa/select-frame`. Cmd-K's
;; `:palette/select-frame` verb dispatches through the same canonical
;; event so the ribbon picker + the palette + any future frame-aware
;; feature flows through one source of truth.

(defn- ribbon-filter-pills
  "Filter pills cluster per spec/018 §3 + §7 Ribbon pills. Thin
  delegate to `filters.pills/pills-view` — the proper pill UI lives in
  the filters ns, which also owns the edit popup mount. Mounted here
  inside the ribbon's `reg-view` so subscribes still resolve through
  React context to `:rf/causa`.

  Per rf2-ak4ms the legacy `js/window.prompt` stub is gone — the add-
  pill affordance now opens the rich edit popup. Per spec/018 §7 the
  popup pre-populates from existing pills (edit) or from right-click
  event-row context (add)."
  [{:keys [filters]}]
  [filter-pills/pills-view {:filters filters}])

(defn- ribbon-redacted-indicator
  "REDACTED indicator (rf2-azls9) — preserved next to the mode pill for
  back-compat with the existing redacted-counter assertion surface.
  Only renders when the counter is positive."
  [redacted-count]
  (when (pos? redacted-count)
    [:span {:data-testid "rf-causa-redacted-indicator"
            :title       (str "Spec 009 §Privacy: " redacted-count
                              " sensitive trace event"
                              (when (not= 1 redacted-count) "s")
                              " suppressed by default. Set "
                              ":rf.privacy/show-sensitive? true via "
                              "(causa-config/configure! ...) to "
                              "surface them.")
            :style       {:color       (:magenta tokens)
                          :font-weight 600
                          :font-size   (:caption type-scale)}}
     ;; rf2-vxpq1 — the leading `●` glyph is decorative (the count
     ;; + "REDACTED" word carry the meaning). `aria-hidden` on the
     ;; glyph suppresses the unicode-name announcement ("black
     ;; circle") while keeping the text + title accessible.
     [:span {:aria-hidden "true"} "● "]
     (str "REDACTED " redacted-count)]))

;; Forward declaration — the L2 scroll-into-view machinery is defined
;; alongside the event-list renderer further down (it lives next to its
;; sibling `scroll-focused-row-into-view!`), but the L1 focus chip below
;; reaches it for the rf2-w738i "reveal pivot row" gesture.
(declare scroll-row-into-view-by-id!)

(defn- ribbon-focus-chip
  "Focus chip (rf2-a1z3b) — surfaces the active focus-set as
  `🎯 <pivot-label> ✕`. Renders nothing when no focus-set is active.

  Two interactive surfaces:

    - **Chip body** (rf2-w738i) — clicking (or Enter / Space on the
      keyboard) scrolls the pivot row back into view in L2. The pivot
      is the anchor row that established the focus (`:pivot-id`); after
      stepping through the focus subset with `[◀][▶]` the user can lose
      sight of it, so the chip body is the 'take me back to the anchor'
      gesture. Wired to `scroll-row-into-view-by-id!` — the row is
      located by the same `data-testid` the L2 renderer stamps.
    - **`✕` clear button** — clears focus (`:rf.causa/clear-focus`).
      `stopPropagation` keeps the body's scroll gesture from also firing.

  Placement: directly after the nav cluster so the user's eye picks
  it up next to `[◀][▶]` — those buttons step ONLY through the focus
  subset while the chip is present, so the cluster reads naturally
  as 'stepping inside this focus'."
  [{:keys [focus-set]}]
  (when focus-set
    (let [label    (fh/pivot-label focus-set)
          pivot-id (:pivot-id focus-set)
          scroll!  (fn [^js e]
                     (.stopPropagation e)
                     (scroll-row-into-view-by-id! pivot-id))
          tip      (str "Focus: " (fh/dimension-label focus-set)
                        " — click to reveal the pivot row (Esc to clear)")]
      [:div {:data-testid "rf-causa-focus-chip"
             :title       tip
             :role        "button"
             :tab-index   "0"
             :aria-label  (str "Reveal pivot row for focus " label)
             :on-click    scroll!
             :on-key-down (fn [^js e]
                            (let [k (.-key e)]
                              (when (or (= k "Enter") (= k " "))
                                (.preventDefault e)
                                (scroll! e))))
             :style       {:display        "inline-flex"
                           :align-items    "center"
                           :gap            "4px"
                           :padding        "2px 6px 2px 8px"
                           :background     (:bg-active tokens)
                           :border         (str "1px solid " (:accent-violet tokens))
                           :border-radius  "10px"
                           :font-family    sans-stack
                           :font-size      (:body type-scale)
                           :color          (:text-primary tokens)
                           :cursor         "pointer"
                           :max-width      "240px"}}
       ;; rf2-vxpq1 — `🎯` is a decorative glyph; the chip's
       ;; "Focus: <label>" tooltip + the label span carry the
       ;; accessible meaning. `aria-hidden` suppresses the unicode-
       ;; name announcement ("direct hit").
       [:span {:aria-hidden "true"
               :style {:color (:accent-violet tokens)
                       :font-weight 600}}
        "🎯"]                ;; 🎯 (UTF-16 surrogate pair for portability)
       [:span {:data-testid "rf-causa-focus-chip-label"
               :style {:overflow      "hidden"
                       :text-overflow "ellipsis"
                       :white-space   "nowrap"
                       :max-width     "180px"
                       :font-family   mono-stack
                       :font-size     (:caption type-scale)}}
        label]
       [:button {:data-testid "rf-causa-focus-chip-clear"
                 :on-click    (fn [^js e]
                                (.stopPropagation e)
                                (rf/dispatch [:rf.causa/clear-focus]
                                             {:frame :rf/causa}))
                 :title       "Clear focus (Esc)"
                 :aria-label  "Clear focus"
                 :style       {:background    "transparent"
                               :border        "none"
                               :color         (:text-secondary tokens)
                               :cursor        "pointer"
                               :padding       "0 2px"
                               :font-size     (:body type-scale)
                               :line-height   "1"}}
        "✕"]])))                  ;; ✕

(defn- ribbon-right-icons
  "Right-icons cluster — `⚙` settings · `✕` close. Per spec/018 §3
  Right-icon behaviour the pop-out (`⛶`) slot is reserved for the
  second-window UX (spec/011-Launch-Modes.md); the ribbon affordance
  is omitted until that lands rather than showing a broken-claim
  button (silent-by-default — rf2-g3ghh / rf2-yn86j). The programmatic
  `(causa/popout!)` API remains the supported pop-out path.

  Settings opens the Settings popup modal (rf2-9poxq) via
  `:rf.causa/settings-open`; close dispatches `:rf.causa/close-shell`
  (handled by mount.cljs in production)."
  []
  (let [icon-style {:background     "transparent"
                    :border         "none"
                    :color          (:text-secondary tokens)
                    :cursor         "pointer"
                    :font-size      (:body type-scale)
                    :padding        "2px 6px"}]
    [:div {:data-testid "rf-causa-ribbon-icons"
           :style {:display "flex" :align-items "center" :gap "4px"}}
     [:button {:data-testid "rf-causa-icon-settings"
               :title       "Settings (,)"
               :aria-label  "Open Causa settings"
               :on-click    #(rf/dispatch [:rf.causa/settings-open] {:frame :rf/causa})
               :style       icon-style}
      "⚙"]
     [:button {:data-testid "rf-causa-icon-close"
               :title       "Close (Ctrl+Shift+C)"
               :aria-label  "Close Causa"
               :on-click    #(rf/dispatch [:rf.causa/close-shell] {:frame :rf/causa})
               :style       icon-style}
      "✕"]]))

(rf/reg-view ribbon
  "L1 ribbon — per spec/018 §3 Top ribbon anatomy. Four clusters left
  to right: nav · frame · filters · right icons. The REDACTED
  indicator surfaces on-demand next to the right-icons cluster when
  the suppressed-sensitive count is positive.

  Per Round-3 rf2-g9pee the explicit `● LIVE` / `◐ RETRO` mode pill
  was dropped — the spine mode is already derivable from sticky-row
  selection + the `[◀ ▶ ⏭]` nav cluster. Space / L / G keybindings
  preserve the toggle access the pill used to surface.

  Per rf2-in6l2 `reg-view`-registered so subscribes resolve to
  `:rf/causa`."
  [_props]
  (let [focus           @(rf/subscribe [:rf.causa/focus])
        cascades        @(rf/subscribe [:rf.causa/cascades])
        focus-set       @(rf/subscribe [:rf.causa/focus-set])
        ;; rf2-r9lyy — opt-in for the `:ungrouped` pseudo-cascade
        ;; bucket. Default OFF preserves silent-by-default; ON
        ;; includes the bucket in L2 + the ribbon's boundary walk
        ;; so the nav cluster's `[◀ ▶ ⏭]` agrees with what the user
        ;; sees in L2.
        show-ungrouped? @(rf/subscribe [:rf.causa/show-ungrouped?])
        redacted-count  @(rf/subscribe [:rf.causa/suppressed-sensitive-count])
        ;; rf2-ikuwt — mute-count drives the L1 ribbon indicator next
        ;; to the REDACTED indicator. Reading the count sub (not the
        ;; raw set) means the ribbon re-renders only when the count
        ;; changes; the indicator's click opens the unmute manager.
        muted-count     @(rf/subscribe [:rf.causa/muted-event-ids-count])
        filters         @(rf/subscribe [:rf.causa/active-filters])
        focused-id      (:dispatch-id focus)
        ;; Per rf2-fzbrw: the boundary predicates must align with the
        ;; user-visible event list. The L2 list filters `:ungrouped`
        ;; (registry-time emits / lifecycle / REPL evals) by default;
        ;; when the rf2-r9lyy opt-in is on the bucket appears in L2
        ;; and the ribbon's walk MUST include it too, otherwise the
        ;; user could click a visible bucket row in L2 that the
        ;; `[<]` boundary refuses to step past. The `l2-cascade-
        ;; visible?` predicate is the single source of truth — both
        ;; surfaces compose against it.
        event-cascades  (filterv #(l2-cascade-visible? % show-ungrouped?) cascades)
        ;; rf2-a1z3b — when a focus-set is active the nav buttons walk
        ;; ONLY the in-focus subset. Boundary predicates honour that
        ;; so `[◀]` greys at the first in-focus row and `[▶]` greys at
        ;; the last. When no focus-set is active, fall through to the
        ;; existing full-stream boundary semantics.
        ids             (if focus-set
                          (fh/in-focus-ids event-cascades focus-set)
                          (mapv :dispatch-id event-cascades))
        at-head?        (or (empty? ids)
                            (= focused-id (last ids))
                            (and (nil? focused-id) (not focus-set)))
        at-tail?        (or (empty? ids)
                            (= focused-id (first ids)))]
    [:div {:data-testid "rf-causa-ribbon"
           :style {:display          "flex"
                   :align-items      "center"
                   :justify-content  "space-between"
                   :gap              "12px"
                   :height           (:top-strip-height layout)
                   :padding          "0 12px"
                   :background       (:bg-1 tokens)
                   :border-bottom    (str "1px solid " (:border-subtle tokens))
                   ;; rf2-o5f5f.1 — mode-signal mechanism #2: a 2-px
                   ;; left-edge stripe in the active mode's accent
                   ;; (violet in Dynamic, cyan in Static). Painted on
                   ;; the ribbon so it lines up with the top-of-shell
                   ;; edge regardless of the L2 resize-handle. The
                   ;; stripe-hex helper closes over the current
                   ;; palette (light vs dark theme via CSS custom
                   ;; properties); cyan is already in the palette so
                   ;; zero new tokens introduced.
                   :border-left      (str "2px solid "
                                          (static-shell/stripe-hex-for-mode :dynamic))
                   :font-family      sans-stack
                   :font-size        (:body type-scale)}}
     ;; rf2-o5f5f.1 — mode pill at ribbon-left. Always rendered
     ;; (the `:rf.causa/static-mode?` feature gate was removed per
     ;; rf2-8l3uk — Static mode is unconditionally available).
     [mode-pill/mode-pill]
     [ribbon-nav-cluster {:at-head? at-head? :at-tail? at-tail?}]
     [ribbon-focus-chip {:focus-set focus-set}]
     ;; L1 frame-switcher slot (rf2-iwwou) — single contractually-
     ;; anchored surface. The view itself reads `:rf.causa/current-
     ;; frame` + `:rf.causa/available-frames` and writes via
     ;; `:rf.causa/select-frame` — no ad-hoc frame access from the
     ;; ribbon. See `frame_switcher.cljs` for the contract.
     [frame-switcher/frame-switcher-view]
     [ribbon-filter-pills {:filters filters}]
     [:div {:style {:display "flex" :align-items "center" :gap "8px"}}
      ;; rf2-ikuwt — mute indicator (🔇 N) renders inline next to the
      ;; REDACTED indicator. Both are silent-by-default surfaces that
      ;; only paint when their count is positive. Click → unmute
      ;; manager modal.
      [spine-filters/ribbon-mute-indicator muted-count]
      [ribbon-redacted-indicator redacted-count]]
     [ribbon-right-icons]]))

;; ---- L2 event list -------------------------------------------------------

;; ---- L2 scrollbar styling (rf2-ieg6d Bug 2) ------------------------------
;;
;; The default browser scrollbar is chunky (16-17px) and stylistically loud
;; — wrong rhythm for an info-dense devtools panel. Firefox accepts the
;; standardised `scrollbar-width` / `scrollbar-color` inline (set on the
;; container's `:style`). WebKit/Blink still ship the legacy `::-webkit-
;; scrollbar` pseudo-elements which can ONLY be reached via a real CSS
;; stylesheet (no React inline-style equivalent), so we inject a one-shot
;; `<style>` tag scoped to `[data-testid="rf-causa-event-list"]` at
;; namespace load. The scope keeps the slim chrome confined to the L2
;; list — no global page-level webkit-scrollbar override (would conflict
;; with host-app stylesheets).
;;
;; `defonce` + idempotent DOM probe keeps shadow-cljs `:after-load` from
;; double-injecting; the guard skips the side-effect entirely under node-
;; test where `js/document` does not exist.

(def ^:private scrollbar-style-id
  "rf-causa-event-list-scrollbar")

(def ^:private scrollbar-css
  "Webkit/Blink slim-scrollbar rules scoped to the L2 event-list container.
  Mirror of the inline Firefox `scrollbar-width`/`-color` props so all
  browsers land on the same visual rhythm.

  Colours echo `(:border-subtle tokens)` / `(:text-tertiary tokens)` —
  hardcoded as hex/rgba here because the rule lives in a string outside
  the hiccup tree where the tokens map isn't directly available."
  (str "[data-testid=\"rf-causa-event-list\"]::-webkit-scrollbar"
       " { width: 6px; height: 6px; }"
       "[data-testid=\"rf-causa-event-list\"]::-webkit-scrollbar-track"
       " { background: transparent; }"
       "[data-testid=\"rf-causa-event-list\"]::-webkit-scrollbar-thumb"
       " { background: rgba(107, 112, 128, 0.4); border-radius: 3px; }"
       "[data-testid=\"rf-causa-event-list\"]::-webkit-scrollbar-thumb:hover"
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

(defn- scroll-row-into-view-by-id!
  "Scroll the L2 row whose `:dispatch-id` is `id` into view. Locates the
  row by its `data-testid` (`rf-causa-event-row-<id>`) — the same id the
  `event-row` renderer stamps — and delegates to
  `scroll-focused-row-into-view!`. No-op when `js/document` is absent
  (node-test) or the row isn't currently mounted (e.g. scrolled far out
  of the virtual window). Returns the located element (or nil) so
  callers / tests can assert the lookup."
  [id]
  (when (and (exists? js/document) (.-querySelector js/document) id)
    (let [sel (str "[data-testid=\"rf-causa-event-row-" (str id) "\"]")
          el  (.querySelector js/document sel)]
      (when el
        (scroll-focused-row-into-view! el))
      el)))

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

(defn- focus-gesture-handler
  "rf2-a1z3b — return the on-click handler for the L2 row's left
  GUTTER. The gutter is the FOCUS surface (vs. the body, which is the
  SELECTION surface). Click semantics:

  - Out-of-focus row's gutter → set focus on the row's inferred
    dimension (`fh/infer-dimension` picks first applicable from
    machine / http / event-id / source-coord).
  - Pivot row's gutter (same id, same dimension) → toggle focus OFF
    (the `set-focus-reducer` recognises the duplicate and dissocs).
  - In-focus non-pivot row's gutter → rebuild focus around this row
    as the new pivot (dimension may stay the same or change).

  Returns nil when no dimension can be inferred (`:ungrouped` /
  unrouted cascade) — the gutter renders inert."
  [cascade]
  (when-let [{:keys [dimension value]} (fh/infer-dimension cascade)]
    (fn [^js e]
      (.stopPropagation e)
      (rf/dispatch [:rf.causa/set-focus dimension value (:dispatch-id cascade)]
                   {:frame :rf/causa}))))

(defn- event-row
  "One row in the L2 event list. Single line per spec/018 §4 Row
  anatomy + Round-3 rf2-cmtkw — minimal default render.

  ## Round-3 rf2-cmtkw — minimal default row

  Default row content (ONE line only):

      [gutter] :event-id  [⚠] [🌐] [🤖]

  - **`:event-id`** — the bare event-id keyword (not the full event
    vector). Args / payload move to the tooltip + Event tab detail.
  - **`⚠`** — exception/error glyph (from `:other` carrying
    `:op-type :error` / `:warning`, or `:errors` slot populated).
  - **`🌐`** — managed-HTTP marker (from `:other` carrying an
    `:operation` matching `:http/*` or `:rf.http/*`).
  - **`🤖`** — state-machine marker (from `:other` carrying an
    `:operation` matching `:machine`).

  Dropped from the default view (moved to hover tooltip + Event
  detail tab): the full event vector with args, the sequence
  number (`#<dispatch-id>`), the duration tier, the source
  coordinate.

  The row's `:title` attribute carries those dropped fields so a
  hover surfaces them without leaving L2. Clicking the row opens
  the Event tab in L4 with the full untruncated content.

  Right-click (`on-context-menu`) lowers per spec/018 §7 'Right-click
  event-row → context menu' into `:rf.causa/open-row-context-menu`
  (rf2-ikuwt) — a small floating context menu with two items:

    - 'Mute <event-id>' — one-step mute via
      `:rf.causa/mute-event-id`; the row disappears from the spine
      and the L1 ribbon's mute-count indicator increments.
    - 'Always hide this event-type…' — opens the rich OUT-filter
      popup via `:rf.causa/hide-event-type` (the existing flow).

  The menu state lives in app-db (`:row-context-menu`) so the menu
  renders at the shell-view root and floats above the L2 list's
  overflow-hidden clipping. preventDefault on the right-click
  suppresses the browser's native menu.

  ## Focus gutter (rf2-a1z3b)

  The leftmost ~14px is the FOCUS surface (gutter); the rest is the
  SELECTION surface (body). Gutter click sets/toggles focus on the
  row's inferred dimension; body click selects the cascade (and
  CLEARS focus when the clicked row is out-of-focus). When a
  focus-set is active, out-of-focus rows render at ~40% opacity and
  the gutter renders an OPEN `⦿` marker; the pivot row renders a
  FILLED `⦿` marker."
  [{:keys [cascade focused-id auto-track? focus-set in-focus? focus now-ms]}]
  (let [id          (:dispatch-id cascade)
        focused?    (= id focused-id)
        pivot?      (and focus-set (= id (:pivot-id focus-set)))
        focus-active? (some? focus-set)
        out-of-focus? (and focus-active? (not in-focus?))
        ;; rf2-r9lyy — the `:ungrouped` pseudo-cascade bucket renders
        ;; with a distinct muted treatment when the opt-in is on (the
        ;; bucket only reaches this row when the user has flipped the
        ;; Settings → Power user → 'Show :ungrouped' toggle ON, per
        ;; `l2-cascade-visible?`). Muted background + zero event vector
        ;; signal "this is a pseudo-cascade outside any dispatch".
        ungrouped?  (ungrouped-cascade? cascade)
        glyph       (gutter-glyph cascade focused-id)
        ;; rf2-gf58j — L2 row chrome: per-origin prefix + per-cascade
        ;; activity badges. Both pulled from the cascade record;
        ;; helpers live in `panels.l2-timeline` so the projection is
        ;; pure-data and JVM-testable.
        origin         (l2-timeline/dispatch-origin-of cascade)
        origin-prefix  (l2-timeline/origin-prefix-glyph origin)
        origin-title   (l2-timeline/origin-prefix-title origin)
        badges         (l2-timeline/activity-badges cascade)
        badges-tooltip (l2-timeline/activity-badges-tooltip cascade)
        ev-id       (event-id-of-cascade cascade)
        event-vec   (:event cascade)
        ;; rf2-b76v4 — canonical event-lifecycle status colour. ONE
        ;; helper drives the row's status accent (a 2px inset stripe on
        ;; the trailing edge — sibling to the gutter's causal-chain
        ;; thread on the leading edge). Replaces the pre-rf2-b76v4
        ;; rolled-on-the-fly bg/border colour decisions. The status fn
        ;; reads the cascade's terminal outcome (error / warning / ok)
        ;; via `event-detail/cascade-outcome` so the L2 row, the L4
        ;; Event header and the Trace row all consume ONE vocabulary.
        status-state (event-status/cascade->state
                       cascade focus event-detail/cascade-outcome)
        status-kw    (event-status/classify-status status-state)
        status-hex   (event-status/event-status-colour status-state)
        glyph-col   (cond
                      focused?                                (:cyan tokens)
                      (= "x" glyph)                           (:red tokens)
                      (= "▥" glyph)                           (:magenta tokens)
                      :else                                   (:text-tertiary tokens))
        bg          (cond
                      focused?   (:bg-active tokens)
                      ;; Muted background for the :ungrouped bucket so it
                      ;; reads as visually distinct from the real-event
                      ;; rows (rf2-r9lyy). Falls back to the same bg-2
                      ;; the list uses if `:bg-2` is the only neutral
                      ;; muted token available; the ribbon's own canvas
                      ;; is bg-1 so this still reads as a recessed row.
                      ungrouped? (:bg-2 tokens)
                      :else      "transparent")
        border      (cond
                      focused?   (str "1px solid " (:cyan tokens))
                      ungrouped? (str "1px dashed " (:border-subtle tokens))
                      :else      "1px solid transparent")
        ;; rf2-b76v4 — the lifecycle-status accent rides as a 2px inset
        ;; box-shadow on the row's TRAILING edge so it doesn't compete
        ;; with the focused-row solid border (which paints the row
        ;; outline) or the gutter's leading-edge causal-chain thread.
        ;; The `:ungrouped` row suppresses the accent because the bucket
        ;; has no lifecycle (no event vector, no cascade outcome).
        status-shadow (when (and (not ungrouped?) status-hex)
                        (str "inset -2px 0 0 0 " status-hex))
        ;; rf2-ieg6d Bug 1 — only the focused row in the LIVE-at-head
        ;; auto-tracking branch carries a ref. RETRO and non-focused rows
        ;; get nil (no DOM-side scroll work, no per-render cost).
        ref-fn      (when focused? (focused-row-ref id auto-track?))
        gutter-click (focus-gesture-handler cascade)
        ;; rf2-a1z3b — clicking a row's body clears focus when the row
        ;; is OUT-of-focus (the gesture model: body-click on dim row
        ;; says 'I want to select this; abandon the focus lens'). When
        ;; the row is IN-focus or no focus-set is active, body-click is
        ;; pure selection per the existing contract.
        body-click  (fn [_e]
                      (when out-of-focus?
                        (rf/dispatch [:rf.causa/clear-focus] {:frame :rf/causa}))
                      (rf/dispatch [:rf.causa/focus-cascade id (:frame cascade)]
                                   {:frame :rf/causa}))
        ;; Focus-aware gutter marker per rf2-a1z3b. The existing glyph
        ;; (●/◉/x/▥) keeps semantics for the spine's selection +
        ;; error/redacted state; an additional ⦿ marker rides ON TOP
        ;; in the gutter when a focus-set is active to signal in-focus
        ;; (open) vs pivot (filled). When no focus-set is active the
        ;; gutter renders the legacy glyph only.
        focus-marker (cond
                       pivot?     "⦿"          ;; filled (pivot anchor)
                       in-focus?  "◌"          ;; open marker for in-focus non-pivot rows
                       :else      nil)
        focus-marker-col (cond
                           pivot?    (:accent-violet tokens)
                           in-focus? (:accent-violet tokens)
                           :else     (:text-tertiary tokens))]
    ;; Density (rf2-htik0 Bug 2): height 22px + padding "1px 6px" tightens
    ;; the row from the earlier 28px / "4px 8px" spec-baseline. Causa is
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
    [:li (cond-> {:data-testid (str "rf-causa-event-row-" (str id))
                  :role        "button"
                  :tab-index   "0"
                  :aria-label  (cond
                                 ungrouped?
                                 ":ungrouped pseudo-cascade — events outside any dispatch"

                                 ev-id
                                 (str "Event " (str ev-id)
                                      (when focused? " (focused)")
                                      (when in-focus? " (in focus set)"))

                                 :else
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
                                         (rf/dispatch
                                           [:rf.causa/open-row-context-menu
                                            {:event-id ev-id
                                             :x        x
                                             :y        y}]
                                           {:frame :rf/causa}))))))
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
                                       (rf/dispatch
                                         [:rf.causa/open-row-context-menu
                                          {:event-id ev-id
                                           :x        (.-clientX e)
                                           :y        (.-clientY e)}]
                                         {:frame :rf/causa})))
                  ;; Round-3 rf2-cmtkw — dropped fields (full event
                  ;; vector with args, sequence number, frame, source
                  ;; coord, handler duration) surface in this hover
                  ;; tooltip + the L4 Event tab on click. Default row
                  ;; body shows only `event-id + ⚠/🌐/🤖`.
                  :title (if ungrouped?
                           ":ungrouped — events outside any dispatch (:rf.ssr/*, registry-time emits, REPL evals, frame lifecycle). Click to focus the bucket."
                           (row-tooltip-text cascade))
                  :data-rf-causa-in-focus (cond
                                            (not focus-active?) "n/a"
                                            in-focus?           "true"
                                            :else               "false")
                  :data-rf-causa-pivot    (if pivot? "true" "false")
                  :data-rf-causa-ungrouped (if ungrouped? "true" "false")
                  ;; rf2-b76v4 — the resolved lifecycle status keyword
                  ;; rides on a data attribute so tests + the
                  ;; pure-hiccup walker can assert the row picked up
                  ;; the right vocabulary without parsing inline
                  ;; styles.
                  :data-rf-causa-status   (name status-kw)
                  :style {:display       "flex"
                          :align-items   "center"
                          :gap           "6px"
                          :padding       "1px 6px"
                          :height        "22px"
                          :line-height   "20px"
                          :cursor        "pointer"
                          :background    bg
                          :border        border
                          ;; rf2-b76v4 — the lifecycle-status accent is
                          ;; a 2px inset shadow on the row's trailing
                          ;; edge. Sibling to the gutter's leading-edge
                          ;; causal-chain thread (rf2-5kfxe.10), and
                          ;; non-overlapping with the focused-row solid
                          ;; border.
                          :box-shadow    (or status-shadow "none")
                          :border-radius "2px"
                          :font-family   mono-stack
                          :font-size     (:mono-body type-scale)
                          :color         (if ungrouped?
                                           (:text-secondary tokens)
                                           (:text-primary tokens))
                          :font-style    (if ungrouped? "italic" "normal")
                          :white-space   "nowrap"
                          :overflow      "hidden"
                          :text-overflow "ellipsis"
                          ;; rf2-a1z3b — out-of-focus rows dim to
                          ;; `--rf-causa-row-dim-opacity` (default 0.4)
                          ;; so the lens reads at a glance. Inline so
                          ;; we don't need a stylesheet round-trip; the
                          ;; CSS-var fallback keeps host overrides
                          ;; possible.
                          ;; rf2-r9lyy — :ungrouped rows render at 0.7
                          ;; opacity even when in-focus so the bucket
                          ;; reads as visually recessed against the real
                          ;; cascade rows. Out-of-focus still wins (the
                          ;; 0.4 dim is more aggressive).
                          :opacity       (cond
                                           out-of-focus?
                                           "var(--rf-causa-row-dim-opacity, 0.4)"
                                           ungrouped? 0.7
                                           :else      1)}}
           ref-fn (assoc :ref ref-fn))
     ;; Gutter — FOCUS surface (rf2-a1z3b). Click sets/toggles focus
     ;; on the row's inferred dimension. The hit-area is the 14px
     ;; gutter cell; stopPropagation prevents the body-click handler
     ;; from also firing.
     ;;
     ;; rf2-5kfxe.10 — cascade-chain timeline gutter. The gutter cell
     ;; carries a 1px violet inset border on its LEFT EDGE so stacked
     ;; rows render as a continuous vertical thread — visually
     ;; expressing the spine's timeline rather than reading as a flat
     ;; list. `:ungrouped` rows break the thread (the bucket isn't on
     ;; the cascade timeline). The thread is `align-self: stretch` so
     ;; it spans the full row height edge-to-edge.
     [:span (cond-> {:data-testid (str "rf-causa-row-gutter-" (str id))
                     :style       {:width        "14px"
                                   :flex-shrink  0
                                   :display      "inline-flex"
                                   :align-items  "center"
                                   :justify-content "center"
                                   :align-self   "stretch"
                                   :cursor       (if gutter-click "pointer" "default")
                                   :color        focus-marker-col
                                   :font-size    "11px"
                                   ;; rf2-5kfxe.10 — the timeline
                                   ;; thread. `box-shadow inset` paints
                                   ;; a 1px violet line on the left
                                   ;; edge of the gutter without
                                   ;; consuming any layout width
                                   ;; (border-left would shift the
                                   ;; glyph). Ungrouped rows break the
                                   ;; thread because they're off-
                                   ;; timeline.
                                   :box-shadow   (if ungrouped?
                                                   "none"
                                                   (str "inset 1px 0 0 0 "
                                                        (:accent-violet tokens)))}
                     :title       (cond
                                    pivot?
                                    (str "Clear focus on " (fh/dimension-label focus-set))

                                    (and focus-active? in-focus?)
                                    (str "Re-anchor focus on this row ("
                                         (fh/dimension-label focus-set) ")")

                                    gutter-click
                                    (str "Focus on " (fh/dimension-label (fh/infer-dimension cascade)))

                                    :else
                                    "")}
              gutter-click (assoc :on-click gutter-click))
      (or focus-marker
          [:span {:style {:color glyph-col}} glyph])]
     ;; rf2-gf58j — dispatch-origin prefix. Renders a per-origin
     ;; glyph (`R` / `🌐` / `💧` / `⚡` / `⏲` / `T` / `🔧` / `i` /
     ;; `🌊`) before the event-id when the origin is non-`:user`;
     ;; `:user` stays silent so the common case doesn't clutter the
     ;; row. The `:title` carries the closed-enum value as a hover
     ;; affordance. Suppressed for the `:ungrouped` pseudo-cascade
     ;; (no real dispatch envelope, no origin tag to surface).
     (when (and origin-prefix (not ungrouped?))
       [:span {:data-testid (str "rf-causa-row-origin-" (name origin))
               :data-rf-causa-origin (name origin)
               :title origin-title
               :style {:flex-shrink 0
                       :color (:accent-violet tokens)
                       :font-size (:caption type-scale)
                       :min-width "12px"
                       :text-align "center"}}
        origin-prefix])
     ;; Round-3 rf2-cmtkw — minimal default row renders ONLY the
     ;; bare event-id keyword (e.g. `:cart/add-item`). The full event
     ;; vector with args moves to the row's hover tooltip + the L4
     ;; Event tab detail (the panel that owns the room for it).
     ;; Earlier rf2-htik0 Bug 3 inline-rendered the truncated full
     ;; vector here — superseded by the Round-3 minimal-row contract.
     [:span {:data-testid "rf-causa-row-event-id"
             :style {:flex "1 1 auto" :overflow "hidden"
                     :text-overflow "ellipsis"
                     :min-width "0"}}
      (if ungrouped?
        ;; rf2-r9lyy — the :ungrouped pseudo-cascade has no event
        ;; vector by construction. Render a clear muted label instead
        ;; of the defence-in-depth `<no event>` fallback so the user
        ;; understands they're looking at the bucket of events outside
        ;; any dispatch.
        [:span {:style {:color      (:text-tertiary tokens)
                        :font-style "italic"}}
         ":ungrouped (pseudo-cascade)"]
        (render-event-id-only event-vec))]
     (when (seq badges)
       ;; rf2-gf58j — activity-badge cluster sourced from
       ;; `panels.l2-timeline/activity-badges`. Render order matches
       ;; the spec/021 §17.1.5 canonical sequence (⚠ ◆ 🌐 ⚡ ⏲);
       ;; the tooltip names the present classes so the operator can
       ;; read what the cluster means without leaving L2.
       [:span (cond-> {:data-testid "rf-causa-row-badges"
                       :style {:display "flex" :gap "4px" :flex-shrink 0
                               :color (:yellow tokens)
                               :font-size (:caption type-scale)}}
                badges-tooltip (assoc :title badges-tooltip))
        (for [b badges]
          ^{:key b} [:span b])])
     ;; Relative-time chip (rf2-vbbq0). Right-aligned per Mike's design
     ;; (2026-05-19 Q10) — "active-cascade-visibility" calls for the chip
     ;; to ride on the row body rather than hide behind a hover tooltip.
     ;; The chip itself carries an absolute-time `:title` tooltip as the
     ;; power-user reveal. Rendered LAST so it sits flush right against
     ;; the row's trailing edge.
     (relative-time-chip cascade now-ms)]))

(rf/reg-view event-list
  "L2 event list — per spec/018 §4 Event list. Single-line rows,
  latest-on-bottom, ~8 visible at the tightened 22px row height
  (rf2-htik0 Bug 2 — was 28px row × 224px container; Causa is
  info-dense and the earlier rhythm wasted vertical canvas).

  Container height: 8 rows × 22px + 7 × 2px gap + 8px outer padding
  ≈ 200px. `min-height` drops to 48px (2 rows + chrome) so the
  native vertical-resize handle can still squeeze the list down to
  the L2/L3 drag spec minimum.

  Per spec/018 §6 sub-graph + rf2-ak4ms: reads `:rf.causa/filtered-
  cascades` (NOT raw `:rf.causa/cascades`) so the L1 ribbon's IN/OUT
  pills drive the list at the data layer — virtualisation budgets
  the post-filter row count, and the ribbon's `[◀ ▶ ⏭]` nav walks
  the same filtered list (per spec/018 §6 'Atomicity contract').

  Per rf2-in6l2 `reg-view`-registered so subscribes resolve to
  `:rf/causa`.

  Per rf2-639lc the list filters out `:ungrouped` cascades (those
  with no `:event` vector — registry-time emits / frame lifecycle
  outside a drain / REPL evals). Without the filter the L2 list
  rendered a leading `<no event>` placeholder row that leaked the
  projection's internal bucket into the user-facing event timeline.
  Other panels (Performance, etc.) keep reading
  `:rf.causa/cascades` directly so the bucket remains available where
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
  (let [cascades       @(rf/subscribe [:rf.causa/filtered-cascades])
        focus          @(rf/subscribe [:rf.causa/focus])
        focus-set      @(rf/subscribe [:rf.causa/focus-set])
        ;; rf2-r9lyy — opt-in for the `:ungrouped` pseudo-cascade
        ;; bucket. Default OFF preserves silent-by-default; ON
        ;; surfaces the bucket as a muted L2 row that focuses the
        ;; bucket on click so downstream panels populate.
        show-ungrouped? @(rf/subscribe [:rf.causa/show-ungrouped?])
        ;; rf2-0s2at — one subscribe per render drives every chip's
        ;; relative-time text. The sub returns the dispatched-time of
        ;; the most recent cascade (the anchor flips on event arrival,
        ;; not on a per-second tick). Falls back to `(interop/now-ms)`
        ;; when the buffer is empty / no cascade carries a stamp — at
        ;; that point there are no rows to render against the anchor
        ;; anyway, but the chip's render-time guard keeps the bucket
        ;; computation defined.
        now-ms         (or @(rf/subscribe [:rf.causa/relative-time-now-ms])
                           (interop/now-ms))
        focused-id     (:dispatch-id focus)
        ;; LIVE+head+not-paused = the auto-tracking branch from
        ;; spine/compose-focus. Only here do we want scroll-into-view
        ;; to fire on focus change; RETRO + paused-LIVE leave the
        ;; user's scroll position alone.
        auto-track?    (and (= :live (:mode focus))
                            (:head? focus)
                            (not (:paused? focus)))
        event-cascades (filterv #(l2-cascade-visible? % show-ungrouped?) cascades)
        ;; rf2-a1z3b — precompute the focus predicate ONCE per render
        ;; so the per-row work is a single fn call rather than a
        ;; predicate rebuild per row.
        focus-pred     (fh/build-focus-predicate focus-set)]
    [:div {:data-testid "rf-causa-event-list"
           :style {:height        "200px"   ; 8 rows × 22px + gaps + padding (rf2-htik0)
                   :min-height    "48px"    ; 2 rows minimum
                   :overflow-y    "auto"
                   :overflow-x    "hidden"
                   :background    (:bg-2 tokens)
                   :border-bottom (str "1px solid " (:border-subtle tokens))
                   :resize        "vertical"   ; native vertical resize for the L2/L3 drag handle
                   :padding       "4px"
                   ;; rf2-ieg6d Bug 2 — Firefox standardised props for the
                   ;; slim scrollbar. WebKit/Blink pseudo-element rules ship
                   ;; via the `inject-scrollbar-style!` <style> tag above —
                   ;; pseudo-elements can't be set via React inline-style.
                   :scrollbar-width "thin"
                   :scrollbar-color "rgba(107, 112, 128, 0.4) transparent"}}
     (if (empty? event-cascades)
       [:div {:data-testid "rf-causa-event-list-empty"
              :style {:padding   "16px"
                      :color     (:text-secondary tokens)
                      :font-family sans-stack
                      :font-size (:body type-scale)}}
        "No events."]
       (into [:ul {:style {:list-style "none" :margin 0 :padding 0
                           :display "flex" :flex-direction "column"
                           :gap "2px"}}]
             (for [cascade event-cascades]
               ^{:key (str (:dispatch-id cascade))}
               [event-row {:cascade     cascade
                           :focused-id  focused-id
                           :auto-track? auto-track?
                           :focus-set   focus-set
                           :in-focus?   (focus-pred cascade)
                           ;; rf2-b76v4 — pass the spine focus through
                           ;; so the row's lifecycle-status classifier
                           ;; can read `:mode` (RETRO → :stale) and
                           ;; `:paused?` (paused-by-tool → :cyan).
                           :focus       focus
                           :now-ms      now-ms}])))]))

;; ---- L3 tab bar ----------------------------------------------------------

(defn- tab-button
  "One tab in the L3 tab bar. `●` for active per spec/018 §5 Tab
  strip rendering; `○` for inactive. The mnemonic letter is exposed
  via the `title` attribute.

  `aria-label` doubles the visible label as `<tab-label> tab` so the
  button's accessible name never collides with host-app role queries
  (Playwright's `getByRole('button', {name: '-'})` matched our
  `App-db` tab when only `title` was set).

  Per rf2-lvf8t (rf2-q7who Thread B) each button carries `role='tab'`
  and `aria-selected={active?}` so the tab strip exposes the proper
  ARIA tab pattern. Assistive tech announces the buttons as tabs
  rather than generic buttons and reads the selected state correctly;
  `getByRole('tab')` lookups in host integration tests resolve here."
  [{:keys [id label mnem active?]}]
  (let [glyph    (if active? "◉" "○")
        color    (if active? (:text-primary tokens) (:text-secondary tokens))
        ;; rf2-plajx — stable per-tab id so the controlled L4 panel's
        ;; `aria-labelledby` resolves to this button's accessible name.
        tab-id   (str "rf-causa-tab-button-" (name id))
        panel-id (str "rf-causa-tabpanel-" (name id))]
    [:button {:data-testid   (str "rf-causa-tab-" (name id))
              :id            tab-id
              :role          "tab"
              :aria-selected (if active? "true" "false")
              :aria-controls panel-id
              :on-click      #(rf/dispatch [:rf.causa/select-tab id] {:frame :rf/causa})
              :title         (str label " (" mnem ")")
              :aria-label    (str "Causa " label " tab")
              :style {:background    "transparent"
                      :border        "none"
                      :border-bottom (if active?
                                       (str "2px solid " (:accent-violet tokens))
                                       "2px solid transparent")
                      :color         color
                      :cursor        "pointer"
                      :padding       "6px 12px"
                      :font-family   sans-stack
                      :font-size     (:body type-scale)
                      :font-weight   (if active? 600 400)
                      :white-space   "nowrap"}}
     ;; rf2-vxpq1 — the ●/○ glyph is decorative; the visible label
     ;; carries the tab's accessible name. `aria-hidden="true"`
     ;; suppresses the unicode-name announcement ("heavy black
     ;; circle" / "white circle").
     [:span {:aria-hidden "true"
             :style {:color (if active?
                              (:accent-violet tokens)
                              (:text-tertiary tokens))
                     :margin-right "4px"}}
      glyph]
     label]))

(rf/reg-view tab-bar
  "L3 tab bar — eight tabs per spec/018 §5 The 8 tabs (Routing
  promoted per rf2-nrbs9; Machines Canvas promoted per rf2-mkpnb —
  both follow the cohesive-sub-domain rule).

  Per rf2-in6l2 `reg-view`-registered so subscribes resolve to
  `:rf/causa`.

  Per rf2-lvf8t (rf2-q7who Thread B) the wrapping element is a
  generic `<div>` carrying `role='tablist'` — the proper ARIA pattern
  for a tab strip. The earlier `<nav>` was both semantically wrong
  (tabs aren't site navigation) and a strict-mode hazard for host
  apps that also expose a `<nav>` landmark: Playwright's
  `getByRole('navigation')` lookup became ambiguous when Causa was
  mounted alongside a host nav, every Story integration test using
  the role failed (rf2-q7who Thread B — discovered via rf2-drprn).
  Per-tab buttons carry `role='tab'` + `aria-selected` (see
  `tab-button`). `data-testid='rf-causa-tab-bar'` is unchanged."
  []
  (let [selected @(rf/subscribe [:rf.causa/selected-tab])]
    [:div {:data-testid "rf-causa-tab-bar"
           :role        "tablist"
           :aria-label  "Causa panel tabs"
           :style {:display       "flex"
                   :align-items   "center"
                   :gap           "4px"
                   :height        "40px"
                   :padding       "0 8px"
                   :background    (:bg-1 tokens)
                   :border-top    (str "1px solid " (:border-subtle tokens))
                   :border-bottom (str "1px solid " (:border-subtle tokens))}}
     ;; rf2-2moh1 — iterate `dynamic-tabs` (registry-derived) rather
     ;; than a literal vector. Tab order follows each entry's `:order`.
     (for [{:keys [id] :as tab} (dynamic-tabs)]
       ^{:key id}
       [tab-button (assoc tab :active? (= id selected))])]))

;; ---- L4 detail panel -----------------------------------------------------

(defn- unknown-tab-stub
  [selected]
  [:div {:data-testid "rf-causa-tab-unknown"
         :style {:padding "16px"
                 :color   (:text-secondary tokens)
                 :font-family sans-stack}}
   "Unknown tab: " [:code (pr-str selected)]])

(rf/reg-view detail-panel
  "L4 detail panel — case-switch on `:rf.causa/selected-tab`. Reuses
  existing Panel views for Event / App-db / Trace / Machines /
  Routing / Issues; Views is a stub pending follow-on impl.

  Per rf2-in6l2 `reg-view`-registered so subscribes resolve to
  `:rf/causa`. The wrapping `<div>` paints `bg-2` as a contrast
  safety net (rf2-q8154 — defence-in-depth for panels that fail to
  set their own background).

  ## rf2-5kfxe.3 — 180ms cross-fade on tab switch

  Spec/007 §Motion + animation calls for a 180ms cross-fade when the
  user switches L4 tabs. The case-switch above is otherwise an instant
  DOM swap. The trick: wrap the chosen panel in an inner `<div>`
  *keyed on `selected`*. When the key changes Reagent unmounts the
  previous wrapper + mounts a new one, which auto-plays the
  `rf-causa-fade-in` CSS animation declared in
  `theme/global-styles/motion-css`. Duration is interpolated through
  the `--rf-causa-motion-scale` seam (rf2-5kfxe.5) so the fade
  collapses to 0ms under `prefers-reduced-motion: reduce`.

  The outer `<div>` keeps its `data-testid` stable across tab swaps so
  existing tests + `getByTestId` lookups still resolve — the cross-fade
  wrapper is purely internal."
  []
  (let [selected (or @(rf/subscribe [:rf.causa/selected-tab])
                     default-tab)]
    [:div {:data-testid (str "rf-causa-detail-panel-" (name selected))
           ;; rf2-plajx — L4 closes the tab/tabpanel loop. The L3
           ;; tablist owns `role="tablist"` + per-tab `role="tab"` +
           ;; `aria-selected`; the panel completes the WAI-ARIA APG
           ;; tabs pattern with `role="tabpanel"` + `aria-labelledby`
           ;; pointing at the active tab button (per `tab-button`
           ;; the id is `rf-causa-tab-button-<tab-id>`).
           :id              (str "rf-causa-tabpanel-" (name selected))
           :role            "tabpanel"
           :aria-labelledby (str "rf-causa-tab-button-" (name selected))
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
     [:div {:data-testid (str "rf-causa-detail-panel-fade-"
                              (name selected))
            :style {:height     "100%"
                    ;; Keyframes named in `global-styles/motion-css`.
                    ;; Duration interpolated through the
                    ;; `--rf-causa-motion-scale` seam (rf2-5kfxe.5)
                    ;; via `theme.tokens/duration-css` so the
                    ;; 180ms constant + the seam-var name both live
                    ;; in tokens.cljc — one source of truth.
                    ;; `forwards` pins the end state (opacity 1) so
                    ;; the panel stays visible after the fade settles.
                    :animation  (str "rf-causa-fade-in "
                                     (t/duration-css (:fade-duration-ms t/motion))
                                     " ease-out forwards")}}
      ;; rf2-2moh1 — registry-driven panel mount. Each tab's per-panel
      ;; `install!` declares `:panel <view-fn>` via
      ;; `panel-registry/reg-l4-tab!`; the previous case-switch over
      ;; `{:event :app-db :views :trace :machines :routing :issues}` is
      ;; replaced by a lookup against `tab-by-id :dynamic`. The seven
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
;; `static/shell.cljs`). The composer reads `:rf.causa/mode` and
;; renders either Dynamic or Static. Per rf2-8l3uk the
;; `:rf.causa/static-mode?` feature gate was removed — Static mode
;; is unconditionally available.
;;
;; The composer is `reg-view`-registered so the subscribe inside its
;; body resolves through React-context to `:rf/causa` — same
;; discipline as the rest of the shell.

(rf/reg-view dynamic-chrome
  "The Dynamic 4-layer chrome (L1 ribbon · L2 event list · L3 tab bar ·
  L4 detail panel) wrapped as a single component. Extracted from the
  inline composition in `shell-view` so the Static surface can swap in
  alongside it via the mode composer (rf2-o5f5f.1).

  Per rf2-in6l2 `reg-view`-registered for parity with every other
  shell region."
  []
  [:<>
   [ribbon {}]
   [event-list]
   [tab-bar]
   [detail-panel]])

(rf/reg-view surface-composer
  "Mode-aware composer (rf2-o5f5f.1). Reads `:rf.causa/mode` and renders
  either the Dynamic 4-layer chrome OR the Static 3-layer surface.

  Per rf2-8l3uk the `:rf.causa/static-mode?` feature gate was removed
  — Static mode is unconditionally available; the active mode drives
  the swap.

  Per rf2-in6l2 `reg-view`-registered so the subscribe resolves to
  `:rf/causa` via React-context."
  []
  (let [mode @(rf/subscribe [:rf.causa/mode])]
    (case mode
      :static  [static-shell/surface]
      [dynamic-chrome])))

;; ---- shell view ----------------------------------------------------------

(rf/reg-view shell-view
  "The full Causa shell — wraps the 4-layer chrome in a `:rf/causa`
  frame-provider so descendant `subscribe` / `dispatch` resolve to
  the isolated frame. Default `:inline` mode renders in normal
  document flow inside the app-provided right layout host. `:overlay`
  and `:popout` remain available debug/manual modes.

  Per rf2-in6l2 `reg-view`-registered for parity with every other
  shell region. The shell-view itself sits OUTSIDE its own frame-
  provider (it's the mount root) so React-context inside `shell-view`'s
  body still resolves to the default — every subscribing child is its
  own reg-view component so the surrounding `:rf/causa` Provider
  reaches them via React context.

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
  per-cell, but the open-state flags (`:rf.causa/<modal>-open?`)
  still live in the process-global `:rf/causa` frame. Opening Settings
  in one cell opens Settings in every cell that mounts the shell
  against the same frame. Frame-scoping is the follow-on fix —
  see the bead trail."
  [& [{:keys [mode modal-positioning]
       :or   {mode :inline modal-positioning :fixed}}]]
  ;; rf2-5kfxe.1 — wire Inter + JetBrains Mono once on first paint of
  ;; the shell. Idempotent (`defonce` + id-keyed DOM probe inside) so
  ;; shadow-cljs `:after-load` and repeated mounts are no-ops. Future
  ;; cluster commits extend this install with `@keyframes` + the
  ;; reduced-motion seam so all global stylesheet writes converge on
  ;; one entry point.
  (global-styles/install!)
  ;; Idempotent app-db write so every modal can read the positioning
  ;; via the `:rf.causa/modal-positioning` sub. Guarded against
  ;; re-dispatch by comparing the current slot to the prop — once the
  ;; slot matches the prop, the `when` short-circuits and the render
  ;; quiesces. `dispatch-sync` so the slot lands BEFORE the modal
  ;; children mount and read the sub on this same render pass; without
  ;; sync the first paint of a fresh shell would render every modal's
  ;; backdrop at the default `:fixed` before the async router drains.
  ;; Sub + dispatch route via `:rf/causa` so the read/write lands on
  ;; Causa's app-db (`shell-view` itself sits OUTSIDE the
  ;; `frame-provider` in the tree below — the React-context tier
  ;; doesn't reach this call site, hence the explicit frame arg).
  (let [current-positioning @(rf/subscribe :rf/causa [:rf.causa/modal-positioning])]
    (when (not= current-positioning modal-positioning)
      (rf/dispatch-sync [:rf.causa/set-modal-positioning modal-positioning]
                        {:frame :rf/causa})))
  [rf/frame-provider {:frame :rf/causa}
   [:div {:data-testid "rf-causa-shell"
          ;; rf2-plajx — Causa shell root is a landmark. A 40%-
          ;; viewport overlay rendered as a bare `<div>` is invisible
          ;; to screen-reader landmark navigation (JAWS R-key /
          ;; NVDA D-key). `role="region"` + `aria-label` exposes the
          ;; shell as a labelled landmark so AT users can jump to it.
          ;; "region" (rather than "complementary" / "aside") matches
          ;; the audit's Q3 disposition: Causa is a global chrome
          ;; surface with its own internal landmark structure (L1
          ;; ribbon = toolbar, L3 = tablist, L4 = tabpanel) rather
          ;; than content complementary to the host's main.
          :role        "region"
          :aria-label  "Causa devtools"
          ;; Per rf2-zkfiz Q1-9 the spec-published mode axis is
          ;; `data-rf-causa-mode` (mount.cljs writes it on both the
          ;; root and the shell node). The previous `data-mode` echo
          ;; was a duplicate axis and is gone — tests + testbeds read
          ;; the rf-causa-prefixed name everywhere.
          :data-rf-causa-mode (name mode)
          ;; rf2-om6fa — the positioning attribute is published on the
          ;; shell root for testbed assertions; the modals read the
          ;; sub directly rather than via DOM lookup.
          :data-rf-causa-modal-positioning (name modal-positioning)
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
    ;; Left-edge horizontal resize handle (rf2-x8h9y) — only renders
    ;; in `:inline` (right-rail) mode. Position-absolute pins it to
    ;; the LEFT edge of this flex container; the outer div is
    ;; `position: relative` in :inline so the handle's anchor
    ;; resolves correctly. The handle's drag math writes through
    ;; `:rf.causa/set-panel-width-px`, which clamps + persists +
    ;; pushes `--rf-causa-inline-width` onto the layout host so the
    ;; host's `flex-basis` re-evaluates this paint.
    [resize-handle/Handle mode]
    ;; Mode-aware surface (rf2-o5f5f.1). The composer reads
    ;; `:rf.causa/mode` and renders either the Dynamic 4-layer
    ;; chrome or the Static 3-layer surface. Per rf2-8l3uk the
    ;; `:rf.causa/static-mode?` feature gate was removed — Static
    ;; mode is unconditionally available.
    [surface-composer]
    ;; Command palette (rf2-wm7z4) — mounted at shell root so it
    ;; overlays the chrome. Modal short-circuits to nil when
    ;; `:rf.causa/palette-open?` is false; closed-state cost is one
    ;; subscribe + when-gate.
    [palette/Modal]
    ;; Filter edit popup (rf2-ak4ms) — mounted at shell root so it
    ;; overlays the chrome AND the palette modal (the popup's z-index
    ;; is one above the palette so an edit opened from a palette
    ;; context wins focus). Modal short-circuits to nil when
    ;; `:rf.causa/edit-popup-open?` is false; closed-state cost is
    ;; one subscribe + when-gate.
    [filters/Modal]
    ;; Settings popup (rf2-9poxq) — same mount discipline as the
    ;; palette + edit popup: shell-root mount so subscribes resolve
    ;; through the shell's `:rf/causa` frame-provider, and the modal
    ;; short-circuits to nil when `:rf.causa/settings-open?` is false.
    [settings-popup/Modal]
    ;; Cancellation-cascade popover (rf2-59e7k) — single waterfall view
    ;; of the rf2-wvkn cancellation contract. Opened from the Trace tab
    ;; (right-click a destroy-event row → 'Show cancellation cascade')
    ;; or imperatively via `:rf.causa/cancellation-cascade-open`. Same
    ;; mount discipline as the other popovers: shell-root mount so
    ;; subscribes resolve through the `:rf/causa` frame-provider;
    ;; closed-state cost is one subscribe + a when-gate.
    [cancellation-cascade/Popover]
    ;; Share modal (rf2-nqw0v Phase 5) — encodes the current Causa
    ;; state (focused machine + mode + scrubber + tab) into a URL the
    ;; user can paste anywhere. Same mount discipline as the other
    ;; modals: shell-root mount so subscribes resolve through the
    ;; `:rf/causa` frame-provider; closed-state cost is one subscribe
    ;; + a when-gate.
    [share-modal/Modal]
    ;; Mute manager modal (rf2-ikuwt) — lists every muted event-id
    ;; with per-row unmute buttons + a 'Unmute all' affordance. Same
    ;; mount discipline as the other modals: shell-root mount so the
    ;; subscribes resolve through the `:rf/causa` frame-provider;
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
    ;; popup's subscribes resolve through the shell's `:rf/causa`
    ;; frame-provider; closed-state cost is one subscribe + a when-
    ;; gate.
    [app-db-segment-inspector/Popup]]])
