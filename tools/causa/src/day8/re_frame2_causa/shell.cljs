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
      │ L3  Tab bar (40px) — 6 tabs                           │  projection selector
      ├───────────────────────────────────────────────────────┤
      │ L4  Detail panel (fills remaining canvas)             │  per-tab content
      └───────────────────────────────────────────────────────┘

  L1 / L3 are fixed-height; L2 takes a default 8-row height and is
  user-resizable via the L2/L3 boundary drag handle; L4 takes the
  remainder. Only L2/L3 carries a drag handle.

  ## Ribbon clusters (L1)

  Five clusters, fixed order left → right per spec/018 §3:

  - **Nav** (`◀ ▶ ⏭`) — back / forward / fast-forward through the
    spine. Dispatches `:rf.causa/focus-cascade-prev` / `-next` /
    `:rf.causa/follow-head`.
  - **Frame picker** — single-select dropdown over the cascade list's
    distinct frames. Excludes `:rf/causa` by default per §8 I1.
  - **Filter pills** — IN (green, `+`) and OUT (magenta, `×`) pills
    + trailing `[+]` add-pill. Click any pill → edit popup.
  - **Mode pill** — `● LIVE` / `◐ RETRO` / `● LIVE (paused)` dual-
    purpose indicator + toggle. Carries the REDACTED-count tooltip.
  - **Right icons** — `⚙` settings · `✕` close. (Pop-out is a
    programmatic API only — `(causa/popout!)` — no ribbon affordance
    until the second-window UX lands per spec/011-Launch-Modes.md.)

  ## Event list (L2)

  Single-line rows, latest-on-bottom, 8 visible by default. Each row:
  gutter glyph (`● ◉ x ▥`) + event-id + right-aligned badge cluster
  (`⚠ 🌐 🤖`) + trailing redaction marker (`[● REDACTED N]`). Click
  a row → `:rf.causa/focus-cascade <id>` flips spine to RETRO and
  rebinds every dependent surface in one frame.

  ## Tab bar (L3)

  Six tabs, mnemonic letters per spec/018 §11:

      Event (e) · App-db (a) · Views (v) · Trace (t) · Machines (m) · Issues (i)

  Selection lives on `:rf.causa/selected-tab` and drives the L4
  detail panel's case switch.

  ## Detail panel (L4)

  Renders the active tab's projection of the focused event. Tabs
  reuse the existing per-panel views where possible (Event tab →
  `event-detail/Panel`, App-db → `app-db-diff/Panel`, Trace →
  `trace/Panel`, Machines → `machine-inspector/Panel`, Issues →
  `issues-ribbon/Panel`). The Views tab is a stub pending the §5.3
  per-view content design.

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
  (:require [re-frame.core :as rf]
            [day8.re-frame2-causa.filters :as filters]
            [day8.re-frame2-causa.filters.pills :as filter-pills]
            [day8.re-frame2-causa.panels.app-db-diff :as app-db-diff]
            [day8.re-frame2-causa.panels.cancellation-cascade :as cancellation-cascade]
            [day8.re-frame2-causa.panels.event-detail :as event-detail]
            [day8.re-frame2-causa.panels.issues-ribbon :as issues-ribbon]
            [day8.re-frame2-causa.panels.machine-inspector :as machine-inspector]
            [day8.re-frame2-causa.panels.views :as views]
            [day8.re-frame2-causa.panels.trace :as trace]
            [day8.re-frame2-causa.palette :as palette]
            [day8.re-frame2-causa.popover.causality :as causality-popover]
            [day8.re-frame2-causa.resize-handle :as resize-handle]
            [day8.re-frame2-causa.settings.popup :as settings-popup]
            [day8.re-frame2-causa.share-modal :as share-modal]
            [day8.re-frame2-causa.theme.tokens :refer [tokens type-scale layout sans-stack mono-stack]]))

;; ---- internal frames + tab inventory ------------------------------------

(def ^:private internal-frames
  "Frames Causa filters out of the picker by default per spec/018 §8 I1.
  `:rf/causa` is Causa's own state; `:rf/pair2` is the future MCP-pair
  frame. A future Settings 'Show tool frames in picker' toggle will
  re-include them under a `── Power user ──` divider; the toggle UI
  is not built yet, so the picker is hardcoded to exclude them."
  #{:rf/causa :rf/pair2})

(def ^:private tabs
  "The six L3 tabs per spec/018 §5 The 6 tabs. Order is the canonical
  left-to-right ribbon order; the `:mnem` letter is the keyboard
  mnemonic (spec/018 §11).

  Labels use spaces (`App DB` not `App-db`) so the rendered text
  carries no `-` glyphs — Playwright's `getByRole('button', {name:
  '-'})` accessible-name matching is substring-based and would
  otherwise lasso our tab buttons in any host whose app exposes
  `-`-named buttons (counter `+ / -`)."
  [{:id :event    :label "Event"    :mnem "e"}
   {:id :app-db   :label "App DB"   :mnem "a"}
   {:id :views    :label "Views"    :mnem "v"}
   {:id :trace    :label "Trace"    :mnem "t"}
   {:id :machines :label "Machines" :mnem "m"}
   {:id :issues   :label "Issues"   :mnem "i"}])

(def ^:private default-tab :event)

;; ---- helpers (pure, exported for tests) ---------------------------------

(defn distinct-frames
  "Pure helper. Returns the distinct frames present in `cascades` in
  first-seen order. Used by the ribbon frame dropdown to populate
  selectable options.

  Filters `:rf/causa` (and other tool frames) out by default per
  spec/018 §8 I1 — passing `show-tool-frames?` true reincludes them.
  nil-frame cascades are dropped (an `:ungrouped` cascade carries
  nil `:frame`)."
  [cascades show-tool-frames?]
  (let [seen (volatile! #{})]
    (reduce
      (fn [acc cascade]
        (let [f (:frame cascade)]
          (cond
            (nil? f)                              acc
            (contains? @seen f)                   acc
            (and (not show-tool-frames?)
                 (contains? internal-frames f))   acc
            :else (do (vswap! seen conj f)
                      (conj acc f)))))
      []
      cascades)))

(defn mode-pill-text
  "Render text for the L1 mode pill per spec/018 §3 Mode pill click
  behaviour. `focus` is the `:rf.causa/focus` sub value."
  [{:keys [mode paused? head?]}]
  (case mode
    :live  (if paused? "● LIVE (paused)" "● LIVE")
    :retro (if head? "● LIVE" "◐ RETRO")
    "● LIVE"))

(defn mode-pill-on-click
  "Dispatcher for the mode-pill click — Space-equivalent when in LIVE,
  L-equivalent when in RETRO. Per spec/018 §3 table 'Mode pill click
  behaviour'."
  [{:keys [mode]}]
  (if (= mode :retro)
    #(rf/dispatch [:rf.causa/follow-head] {:frame :rf/causa})
    #(rf/dispatch [:rf.causa/toggle-live-pause] {:frame :rf/causa})))

(defn event-id-of-cascade
  "Best-effort pluck of the event-id from a cascade's `:event` slot.
  The slot is the raw event vector ([:foo/bar …]); the first element
  is the event id. nil when the cascade is unrouted or the event slot
  is empty."
  [cascade]
  (let [ev (:event cascade)]
    (when (vector? ev)
      (first ev))))

(def event-vector-inline-cap
  "Max character count for the L2 row's inline event-vector rendering
  (rf2-htik0). Cascades that pr-str longer than this collapse to
  `<head>…]` so the row stays single-line. Click the row → the L4
  Event tab shows the full vector (no truncation there).

  ~80 chars is enough for `[:cart/add-item {:item-id \"apple\" :qty 2}]`
  plus a touch of slack for typical event-handler signatures, and
  short enough to keep the L2 list scannable in a stack of rows."
  80)

(defn truncate-event-vector
  "Truncate a pr-str'd event vector to `cap` chars with `…]` suffix
  when over the cap. Pure string utility — caller already has the
  pr-str result.

  Always preserves the trailing `]` so the rendered form still reads
  as a vector at a glance (e.g. `[:foo/bar {:a 1 :b 2 :c…]`)."
  [s cap]
  (if (<= (count s) cap)
    s
    (str (subs s 0 (max 0 (- cap 2))) "…]")))

(defn render-event-vector-inline
  "Render an event vector as `[event-id payload…]` hiccup for the L2
  event-list row (rf2-htik0).

  - Empty payload (1-element vector) renders as `[:counter/inc]` — no
    map, no `{}` placeholder.
  - Non-empty payload pr-str's the full vector then truncates to
    `event-vector-inline-cap` with `…]` suffix so the row stays
    single-line.
  - `event-id` (the first element) gets the keyword accent colour so
    it pops out of the row; the rest renders in the row's default
    text colour.

  Returns hiccup (a `[:span … ]` tree). When the cascade carries no
  event vector, falls back to a `<no event>` chip in the secondary
  text colour so unrouted cascades stay visible. (Note: per rf2-639lc
  the L2 event list filters those cascades out via `cascade-has-event?`
  before they reach this renderer, so the fallback is defence-in-depth
  for any caller that does not pre-filter.)"
  [event-vec]
  (cond
    (not (vector? event-vec))
    [:span {:style {:color (:text-secondary tokens)
                    :font-style "italic"}}
     "<no event>"]

    (= 1 (count event-vec))
    [:span {:style {:display "inline-flex" :align-items "baseline"}}
     [:span {:style {:color (:text-tertiary tokens)}} "["]
     [:span {:style {:color (:accent-violet tokens)}}
      (pr-str (first event-vec))]
     [:span {:style {:color (:text-tertiary tokens)}} "]"]]

    :else
    (let [event-id   (first event-vec)
          id-str     (pr-str event-id)
          full-str   (pr-str event-vec)
          truncated  (truncate-event-vector full-str event-vector-inline-cap)
          ;; Strip the leading `[id-str ` from the truncated body so we
          ;; can colour the event-id separately. When truncation chopped
          ;; into the id (shouldn't happen with cap >> typical id), fall
          ;; back to rendering the whole truncated string in default colour.
          id-prefix  (str "[" id-str " ")
          tail       (when (and (> (count truncated) (count id-prefix))
                                (= id-prefix (subs truncated 0 (count id-prefix))))
                       (subs truncated (count id-prefix)))]
      (if tail
        [:span {:style {:display "inline-flex" :align-items "baseline"
                        :overflow "hidden" :text-overflow "ellipsis"}}
         [:span {:style {:color (:text-tertiary tokens)}} "["]
         [:span {:style {:color (:accent-violet tokens)}} id-str]
         [:span {:style {:color (:text-primary tokens)
                         :margin-left "4px"}}
          tail]]
        ;; Defensive fallback — render the whole truncated string in one
        ;; span so a pathological event-id (with embedded space?) still
        ;; surfaces something useful.
        [:span {:style {:color (:text-primary tokens)}} truncated]))))

(defn cascade-has-event?
  "True iff `cascade` carries a real `:event` vector (`(first :event)`
  resolves to a non-nil event-id). False for the `:ungrouped` bucket
  produced by `re-frame.trace.projection/group-cascades` for registry-
  time emits / frame lifecycle outside a drain / REPL evals — those
  carry no event vector. Per rf2-639lc the L2 event list filters this
  bucket out so the user never sees a `<no event>` placeholder row."
  [cascade]
  (some? (event-id-of-cascade cascade)))

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
        dim       {:color (:text-tertiary tokens) :cursor "default"}]
    [:div {:data-testid "rf-causa-ribbon-nav"
           :style {:display "flex" :align-items "center" :gap "4px"}}
     [:button {:data-testid "rf-causa-nav-prev"
               :on-click    #(rf/dispatch [:rf.causa/focus-cascade-prev] {:frame :rf/causa})
               :disabled    (boolean at-tail?)
               :title       "Step to previous event (j)"
               :style       (merge btn-style (when at-tail? dim))}
      "◀"]
     [:button {:data-testid "rf-causa-nav-next"
               :on-click    #(rf/dispatch [:rf.causa/focus-cascade-next] {:frame :rf/causa})
               :disabled    (boolean at-head?)
               :title       "Step to next event (k)"
               :style       (merge btn-style (when at-head? dim))}
      "▶"]
     [:button {:data-testid "rf-causa-nav-head"
               :on-click    #(rf/dispatch [:rf.causa/follow-head] {:frame :rf/causa})
               :title       "Fast-forward to latest (G)"
               :style       btn-style}
      "⏭"]]))

(defn- ribbon-frame-picker
  "Frame dropdown — single-select per spec/018 §3 Frame dropdown.
  Excludes `:rf/causa` by default per §8 I1. When the only available
  frame is the current selection, the dropdown collapses to a flat
  label (no chevron — no click target).

  Writes via `:rf.causa/set-frame <frame-id>`."
  [{:keys [selected-frame frames]}]
  (let [label-style {:color       (:text-primary tokens)
                     :font-family sans-stack
                     :font-size   (:body type-scale)}]
    (if (<= (count frames) 1)
      [:span {:data-testid "rf-causa-ribbon-frame"
              :style (merge label-style {:color (:text-secondary tokens)})}
       (str "Frame: " (or selected-frame (first frames) ":rf/default"))]
      [:select {:data-testid "rf-causa-ribbon-frame-picker"
                :value       (str (or selected-frame (first frames)))
                :on-change   (fn [^js e]
                               (let [v   (.. e -target -value)
                                     kw  (when (and v (.startsWith v ":"))
                                           (keyword (subs v 1)))]
                                 (when kw
                                   (rf/dispatch [:rf.causa/set-frame kw] {:frame :rf/causa}))))
                :style       (merge label-style
                               {:background    (:bg-2 tokens)
                                :border        (str "1px solid " (:border-default tokens))
                                :border-radius "4px"
                                :padding       "2px 6px"})}
       (for [f frames]
         ^{:key (str f)}
         [:option {:value (str f)} (str "Frame: " f)])])))

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

(defn- ribbon-mode-pill
  "Mode pill — `● LIVE` / `◐ RETRO` / `● LIVE (paused)` per spec/018
  §3. Tooltip surfaces the REDACTED total. Click toggles per the
  mode-pill click behaviour table."
  [{:keys [focus redacted-count]}]
  (let [pill-tone (case (:mode focus)
                    :live  (if (:paused? focus)
                             (:text-secondary tokens)
                             (:green tokens))
                    :retro (if (:head? focus)
                             (:green tokens)
                             (:cyan tokens))
                    (:green tokens))
        red-suffix (when (pos? redacted-count)
                     (str " · ● REDACTED " redacted-count " (default-suppressed)"))
        base-title (case (:mode focus)
                     :live  (if (:paused? focus)
                              "Resume LIVE feed (Space)"
                              "Pause LIVE feed (Space)")
                     :retro (if (:head? focus)
                              "Pause LIVE feed (Space)"
                              "Snap to LIVE (L)")
                     "")
        full-title (str base-title (or red-suffix ""))]
    [:span {:data-testid "rf-causa-mode-pill"
            :on-click    (mode-pill-on-click focus)
            :title       full-title
            :style       {:color         pill-tone
                          :cursor        "pointer"
                          :font-weight   600
                          :padding       "2px 8px"
                          :border        (str "1px solid " pill-tone)
                          :border-radius "10px"
                          :font-family   sans-stack
                          :font-size     (:body type-scale)
                          :white-space   "nowrap"}}
     (mode-pill-text focus)]))

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
                              ":trace/show-sensitive? true via "
                              "(causa-config/configure! ...) to "
                              "surface them.")
            :style       {:color       (:magenta tokens)
                          :font-weight 600
                          :font-size   (:caption type-scale)}}
     (str "● REDACTED " redacted-count)]))

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
               :on-click    #(rf/dispatch [:rf.causa/close-shell] {:frame :rf/causa})
               :style       icon-style}
      "✕"]]))

(rf/reg-view ribbon
  "L1 ribbon — per spec/018 §3 Top ribbon anatomy. Five clusters left
  to right: nav · frame · filters · mode pill · right icons.

  Per rf2-in6l2 `reg-view`-registered so subscribes resolve to
  `:rf/causa`."
  [_props]
  (let [focus           @(rf/subscribe [:rf.causa/focus])
        cascades        @(rf/subscribe [:rf.causa/cascades])
        show-tool?      false   ; Hardcoded — Power-user toggle UI not built yet
        frames          (distinct-frames cascades show-tool?)
        redacted-count  @(rf/subscribe [:rf.causa/suppressed-sensitive-count])
        filters         @(rf/subscribe [:rf.causa/active-filters])
        focused-id      (:dispatch-id focus)
        ids             (mapv :dispatch-id cascades)
        at-head?        (or (empty? ids)
                            (= focused-id (last ids))
                            (nil? focused-id))
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
                   :font-family      sans-stack
                   :font-size        (:body type-scale)}}
     [ribbon-nav-cluster {:at-head? at-head? :at-tail? at-tail?}]
     [ribbon-frame-picker {:selected-frame (:frame focus)
                           :frames frames}]
     [ribbon-filter-pills {:filters filters}]
     [:div {:style {:display "flex" :align-items "center" :gap "8px"}}
      [ribbon-redacted-indicator redacted-count]
      [ribbon-mode-pill {:focus focus :redacted-count redacted-count}]]
     [ribbon-right-icons]]))

;; ---- L2 event list -------------------------------------------------------

(defn- event-row
  "One row in the L2 event list. Single line per spec/018 §4 Row
  anatomy. Gutter glyph + event-id + right-anchored badge cluster +
  trailing redaction marker (stub — uses spine's `:focus.dispatch-id`
  to drive the focused-row gutter).

  Right-click (`on-context-menu`) lowers per spec/018 §7 'Right-click
  event-row → context menu' into `:rf.causa/hide-event-type <event-
  id>` — a one-step 'always hide this event-type' that opens the
  edit popup pre-populated with the row's event-id + OUT default.
  Pre-alpha the popup is the only context-menu surface (the rich
  multi-item menu lands behind a follow-on bead); preventing the
  browser context menu keeps the affordance discoverable on first
  right-click."
  [{:keys [cascade focused-id]}]
  (let [id        (:dispatch-id cascade)
        focused?  (= id focused-id)
        glyph     (gutter-glyph cascade focused-id)
        badges    (row-badges cascade)
        ev-id     (event-id-of-cascade cascade)
        event-vec (:event cascade)
        glyph-col (cond
                    focused?                                (:cyan tokens)
                    (= "x" glyph)                           (:red tokens)
                    (= "▥" glyph)                           (:magenta tokens)
                    :else                                   (:text-tertiary tokens))
        bg        (if focused? (:bg-active tokens) "transparent")
        border    (if focused?
                    (str "1px solid " (:cyan tokens))
                    "1px solid transparent")]
    ;; Density (rf2-htik0 Bug 2): height 22px + padding "1px 6px" tightens
    ;; the row from the earlier 28px / "4px 8px" spec-baseline. Causa is
    ;; info-dense; keeps clickable hit-area while letting ~10 rows fit in
    ;; the same vertical budget the old 8 rows used.
    [:li {:data-testid (str "rf-causa-event-row-" (str id))
          :on-click    #(rf/dispatch [:rf.causa/focus-cascade id (:frame cascade)]
                                     {:frame :rf/causa})
          :on-context-menu (fn [^js e]
                             (when ev-id
                               (.preventDefault e)
                               (rf/dispatch
                                 [:rf.causa/hide-event-type ev-id]
                                 {:frame :rf/causa})))
          :style {:display       "flex"
                  :align-items   "center"
                  :gap           "6px"
                  :padding       "1px 6px"
                  :height        "22px"
                  :line-height   "20px"
                  :cursor        "pointer"
                  :background    bg
                  :border        border
                  :border-radius "2px"
                  :font-family   mono-stack
                  :font-size     (:mono-body type-scale)
                  :color         (:text-primary tokens)
                  :white-space   "nowrap"
                  :overflow      "hidden"
                  :text-overflow "ellipsis"}}
     [:span {:style {:width "14px" :color glyph-col :flex-shrink 0
                     :text-align "center"}}
      glyph]
     ;; Bug 3 (rf2-htik0): render the real event vector inline —
     ;; `[:cart/add-item {:item-id "apple"}]`, NOT just `:cart/add-item`.
     ;; Truncates at ~80 chars with `…]` suffix to keep the row single-line.
     [:span {:data-testid "rf-causa-row-event-vector"
             :style {:flex "1 1 auto" :overflow "hidden"
                     :text-overflow "ellipsis"
                     :min-width "0"}}
      (render-event-vector-inline event-vec)]
     (when (seq badges)
       [:span {:data-testid "rf-causa-row-badges"
               :style {:display "flex" :gap "4px" :flex-shrink 0
                       :color (:yellow tokens)
                       :font-size (:caption type-scale)}}
        (for [b badges]
          ^{:key b} [:span b])])]))

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
  Other panels (Causality Graph, Performance, etc.) keep reading
  `:rf.causa/cascades` directly so the bucket remains available where
  it is meaningful."
  []
  (let [cascades       @(rf/subscribe [:rf.causa/filtered-cascades])
        focus          @(rf/subscribe [:rf.causa/focus])
        focused-id     (:dispatch-id focus)
        event-cascades (filterv cascade-has-event? cascades)]
    [:div {:data-testid "rf-causa-event-list"
           :style {:height        "200px"   ; 8 rows × 22px + gaps + padding (rf2-htik0)
                   :min-height    "48px"    ; 2 rows minimum
                   :overflow-y    "auto"
                   :overflow-x    "hidden"
                   :background    (:bg-2 tokens)
                   :border-bottom (str "1px solid " (:border-subtle tokens))
                   :resize        "vertical"   ; native vertical resize for the L2/L3 drag handle
                   :padding       "4px"}}
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
               [event-row {:cascade cascade :focused-id focused-id}])))]))

;; ---- L3 tab bar ----------------------------------------------------------

(defn- tab-button
  "One tab in the L3 tab bar. `●` for active per spec/018 §5 Tab
  strip rendering; `○` for inactive. The mnemonic letter is exposed
  via the `title` attribute.

  `aria-label` doubles the visible label as `<tab-label> tab` so the
  button's accessible name never collides with host-app role queries
  (Playwright's `getByRole('button', {name: '-'})` matched our
  `App-db` tab when only `title` was set)."
  [{:keys [id label mnem active?]}]
  (let [glyph (if active? "◉" "○")
        color (if active? (:text-primary tokens) (:text-secondary tokens))]
    [:button {:data-testid (str "rf-causa-tab-" (name id))
              :on-click    #(rf/dispatch [:rf.causa/select-tab id] {:frame :rf/causa})
              :title       (str label " (" mnem ")")
              :aria-label  (str "Causa " label " tab")
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
     [:span {:style {:color (if active?
                              (:accent-violet tokens)
                              (:text-tertiary tokens))
                     :margin-right "4px"}}
      glyph]
     label]))

(rf/reg-view tab-bar
  "L3 tab bar — six tabs per spec/018 §5 The 6 tabs.

  Per rf2-in6l2 `reg-view`-registered so subscribes resolve to
  `:rf/causa`."
  []
  (let [selected @(rf/subscribe [:rf.causa/selected-tab])]
    [:nav {:data-testid "rf-causa-tab-bar"
           :style {:display       "flex"
                   :align-items   "center"
                   :gap           "4px"
                   :height        "40px"
                   :padding       "0 8px"
                   :background    (:bg-1 tokens)
                   :border-top    (str "1px solid " (:border-subtle tokens))
                   :border-bottom (str "1px solid " (:border-subtle tokens))}}
     (for [{:keys [id] :as tab} tabs]
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
  Issues; Views is a stub pending follow-on impl.

  Per rf2-in6l2 `reg-view`-registered so subscribes resolve to
  `:rf/causa`. The wrapping `<div>` paints `bg-2` as a contrast
  safety net (rf2-q8154 — defence-in-depth for panels that fail to
  set their own background)."
  []
  (let [selected (or @(rf/subscribe [:rf.causa/selected-tab])
                     default-tab)]
    [:div {:data-testid (str "rf-causa-detail-panel-" (name selected))
           :style {:flex        "1 1 auto"
                   :min-height  "0"
                   :overflow    "auto"
                   :background  (:bg-2 tokens)
                   :color       (:text-primary tokens)}}
     (case selected
       :event    [event-detail/Panel]
       :app-db   [app-db-diff/Panel]
       ;; Views tab — full Views panel per spec/012-Views.md (rf2-21ob3
       ;; replaced the legacy Subscriptions panel with Views; the 4-
       ;; layer chrome surfaces it as the L3 `:views` tab rather than a
       ;; sidebar entry).
       :views    [views/Panel]
       :trace    [trace/Panel]
       :machines [machine-inspector/Panel]
       :issues   [issues-ribbon/Panel]
       [unknown-tab-stub selected])]))

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
    ;; L1 — top ribbon (scope controls)
    [ribbon {}]
    ;; L2 — event list (the spine timeline)
    [event-list]
    ;; L3 — tab bar (projection selector)
    [tab-bar]
    ;; L4 — detail panel (per-tab content)
    [detail-panel]
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
    ;; Causality popover (rf2-dqnuu) — c-key triggered overlay per
    ;; spec/018-Event-Spine.md §10. Replaces the dropped Causality
    ;; tab; the event-list cold-start hint already advertises it
    ;; ("Press [c] for the causality graph"). Same mount semantics as
    ;; the palette Modal: closed-state is one subscribe + when-gate,
    ;; so the dormant cost is negligible.
    [causality-popover/Popover]
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
    [share-modal/Modal]]])
