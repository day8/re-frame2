(ns re-frame.story.ui.sidebar
  "Story-tree sidebar. Per Stage 4 (rf2-ekai) IMPL-SPEC §4.

  Lays out the registered stories and their variants as a tree, with
  inline tag filtering. Each variant is a clickable row that updates
  the shell state's `:selected-variant`. Workspaces register below the
  story tree.

  ## Faceted tag-filter (rf2-7ncf9 — SB9 parity)

  Registered tags with an `:axis` slot (e.g. `:status/alpha` carrying
  `:axis :status`) render in per-axis chip rows. The filter applies
  **AND across axes** + **OR within an axis** — activating
  `:status/stable` AND `:role/design` narrows to variants carrying
  BOTH a `:status` match AND a `:role` match. Tags with no `:axis`
  render in a trailing un-grouped row.

  ## Layout

      ┌──────────────────────┐
      │ filter: [tag chips ] │
      ├──────────────────────┤
      │ ▾ :story.counter     │
      │ ● /default           │   ← variant rows w/ status dot
      │ ● /at-five           │
      │ ▾ :story.login       │
      │ ○ /empty             │
      ├──────────────────────┤
      │ Workspaces           │
      │ ◦ :Workspace.X/y     │
      ├──────────────────────┤
      │ Tests N · ✓P · ✗F   │   ← chrome-level test widget (rf2-q0irb)
      │ [ Run all ]          │
      └──────────────────────┘

  Tag filter: every distinct tag registered on a variant becomes a
  toggle. Selecting tags constrains the tree to variants whose `:tags`
  intersects the active set; empty set means 'show all'.

  Status dots + chrome-level test widget (rf2-q0irb, Vitest-reporter
  parity per spec/009 §Foundational status): every `:test`-tagged
  variant carries a small coloured dot in its sidebar row that reads
  its last `run-variant` outcome (green pass, red fail, yellow running,
  grey pending). The chrome widget at the foot of the sidebar
  aggregates those outcomes across every `:test`-tagged variant and
  exposes a 'Run all' button that drives `run-variant` over each in
  sequence.

  Tag-as-badge affordance (rf2-nwiwr, SB9 badges-addon parity per
  spec/005 §v1.1 ship list): each variant row renders the variant's
  `:tags` as a row of small colour-coded badges to the right of the
  variant id. The palette keys on the canonical seven tags (`:dev`,
  `:docs`, `:test`, `:screenshot`, `:experimental`, `:internal`,
  `:agent`); unknown tags fall back to a neutral grey. The badges are
  inert (the filter row owns interaction) — purely a scan affordance."
  (:require [clojure.string                   :as str]
            [reagent.core                     :as r]
            [re-frame.story.runtime           :as runtime]
            [re-frame.story.async             :as async]
            [re-frame.story.registrar         :as registrar]
            [re-frame.story.theme.colors      :as colors]
            [re-frame.story.theme.glyphs      :as glyphs]
            [re-frame.story.ui.sidebar-search :as search]
            [re-frame.story.ui.sidebar-styles :refer [styles]]
            [re-frame.story.ui.state          :as state]))

;; Styles live in `re-frame.story.ui.sidebar-styles` (pure-data leaf,
;; no Reagent dep). Required as `styles` above so the in-file call
;; sites (`(:wrap styles)` etc.) stay textually identical.

;; ---- pure: collect tags from registered variants ------------------------

(defn collect-tags
  "Return the sorted set of tags present across the registered variants.
  Pure data → data; JVM-testable."
  [variants]
  (->> (vals variants)
       (mapcat (fn [body] (or (:tags body) #{})))
       set
       sort
       vec))

;; `group-tags-by-axis` and `ordered-axes` are pure data → data leaves
;; — they live in `re-frame.story.ui.state` so the JVM test corpus can
;; exercise them without booting Reagent (rf2-7ncf9).

;; ---- pure: per-variant status dot (rf2-q0irb) ---------------------------

(def status->dot-style-key
  "Pure data → data: map a `[:tests :runs]` status keyword to the
  styles map key that renders its dot colour. The render-only mapping
  lives next to the canonical statuses so both surfaces (sidebar dot,
  chrome widget) can JVM-test the projection without booting Reagent."
  {:pass    :dot-pass
   :fail    :dot-fail
   :running :dot-running
   :pending :dot-pending})

(defn dot-aria-label
  "Pure data → data: render the status keyword as an accessible label
  for the per-variant sidebar dot. Used both by the sidebar dot and
  (for parity) by the JVM test corpus."
  [status]
  (case status
    :pass    "tests passing"
    :fail    "tests failing"
    :running "tests running"
    :pending "tests not yet run"
    "tests not yet run"))

;; ---- pure: per-tag badge styling (rf2-nwiwr) ----------------------------

(def tag->badge-style-key
  "Pure data → data: map a tag keyword to the per-tag styles map key
  that supplies its colour-coded palette. Keys on the seven canonical
  tags from spec/007 §Inclusion tags; unknown tags map to `nil` and
  fall through to the neutral `:tag-badge` style.

  Public so the JVM + CLJS test corpus can exercise the projection
  without booting Reagent."
  {:dev          :tag-badge-dev
   :docs         :tag-badge-docs
   :test         :tag-badge-test
   :screenshot   :tag-badge-screenshot
   :experimental :tag-badge-experimental
   :internal     :tag-badge-internal
   :agent        :tag-badge-agent})

(defn sorted-tags
  "Pure data → data: deterministic ordering for a variant's tag set.
  Stable display order matters for visual scanning across rows; sorting
  on `name` keeps the canonical seven in a predictable line-up."
  [tags]
  (->> (or tags #{})
       (sort-by name)
       vec))

;; ---- components ----------------------------------------------------------

(defn- axis-label-text
  "Pure data → data: render an axis keyword as the upper-case label
  text shown above its chip row. The `:re-frame.story.registrar/no-axis`
  sentinel renders as `OTHER`."
  [axis]
  (if (= axis :re-frame.story.registrar/no-axis)
    "OTHER"
    (str/upper-case (name axis))))

(defn- tag-chip
  [tag active?]
  ^{:key tag}
  [:span {:style       (merge (:tag styles)
                              (when active? (:tag-active styles)))
          :data-test   "story-sidebar-tag-chip"
          :data-tag    (str tag)
          :data-active (str (boolean active?))
          :on-click    (fn [_] (state/swap-state!
                                 state/toggle-tag-filter tag))}
   (str tag)])

(defn- axis-row
  "Render one axis's labelled chip row. The label is rendered above
  the chips so axes wrap onto multiple lines on narrow viewports."
  [axis tags tag-filter]
  ^{:key axis}
  [:div {:style       (:axis-row styles)
         :data-test   "story-sidebar-axis-row"
         :data-axis   (name axis)}
   [:span {:style     (:axis-label styles)
           :data-test "story-sidebar-axis-label"}
    (axis-label-text axis)]
   [:div {:style (:axis-chips styles)}
    (for [tag tags]
      (tag-chip tag (contains? tag-filter tag)))]])

(defn- tag-filter-row
  "Faceted filter (rf2-7ncf9). Tags are grouped by their registered
  `:axis` slot — one labelled chip row per axis (`:status`, `:role`,
  `:team`, `:feature`, then any project-defined axes alphabetically,
  with un-axis-grouped tags trailing in an `OTHER` row).

  Active chips highlight; the AND-across / OR-within rule lives in
  `state/variant-tag-match?`.

  `tag->axis` is computed once at the sidebar top and threaded in;
  per rf2-0z8e2 we previously walked the tag registrar twice per
  render (once here, once at the top for the variant filter)."
  [variants tag-filter tag->axis]
  (let [all-tags (collect-tags variants)]
    (if (empty? all-tags)
      [:div {:style (:tag-row styles)}
       [:span {:style (:empty styles)} "no tags"]]
      (let [by-axis (state/group-tags-by-axis all-tags tag->axis)
            axes    (state/ordered-axes by-axis)]
        [:div {:style       (:tag-row styles)
               :data-test   "story-sidebar-tag-filter"}
         (for [axis axes]
           (axis-row axis (get by-axis axis) tag-filter))]))))

(defn status-dot
  "Render the per-variant status glyph. Variants not in `[:tests :runs]`
  (no recorded run AND not tagged `:test`/empty `:play`) skip the dot
  entirely — the row layout shifts left a few pixels but stays
  readable. Variants whose status is `:pending` show an empty-ring dot
  so the user can see the slot is reserved.

  Public so the JVM + CLJS test corpus can render it directly without
  walking a Reagent tree.

  rf2-k3y92 — uses `role=\"img\"` rather than `role=\"status\"`. The
  status-dot is a static decoration painted alongside the row label,
  not an out-of-band update channel. `role=\"status\"` adds an implicit
  `aria-live=\"polite\"`, which means every mounted dot is announced
  by assistive tech — with ~50–200 variant rows in a typical Story
  registry the noise is real. `role=\"img\"` keeps the `aria-label`
  exposed as the accessible name without the live-region behaviour."
  [status]
  (let [k     (status->dot-style-key status)
        label (dot-aria-label status)]
    [:span {:style       (merge (:dot styles) (k styles))
            :data-test   "story-sidebar-dot"
            :data-status (name (or status :pending))
            :role        "img"
            :aria-label  label
            :title       label}]))

(defn tag-badges
  "Render the variant's `:tags` set as a row of small colour-coded
  pills, inline to the right of the variant id. Each badge carries a
  `data-tag` attribute keyed on the tag's `name` so test corpora can
  locate it without walking style maps. Returns `nil` (no container)
  when the variant has no tags — keeps the row layout tight.

  Public so the test corpus can render and inspect the hiccup directly."
  [tags]
  (let [ordered (sorted-tags tags)]
    (when (seq ordered)
      [:span {:style       (:tag-badges styles)
              :data-test   "story-sidebar-tag-badges"}
       (for [tag ordered]
         (let [k     (tag->badge-style-key tag)
               extra (when k (k styles))]
           ^{:key tag}
           [:span {:style     (merge (:tag-badge styles) extra)
                   :data-test "story-sidebar-tag-badge"
                   :data-tag  (name tag)
                   :title     (str tag)}
            (name tag)]))])))

(defn- highlighted-label
  "rf2-yngai — render a variant / story label with the matched
  substring wrapped in an amber-tint span. Returns a hiccup fragment
  suitable for splatting inside the row `<span>`. No-search shortcut
  returns the raw string unwrapped so the no-search path stays quiet."
  [label query]
  (let [segments (search/highlight-segments label query)]
    (if (and (= 1 (count segments)) (not (:match? (first segments))))
      [(:text (first segments))]
      (vec
        (for [[i {:keys [text match?]}] (map-indexed vector segments)]
          (if match?
            ^{:key (str "hit-" i)}
            [:span {:style (:search-hit styles)
                    :data-test "story-sidebar-search-hit"} text]
            ^{:key (str "txt-" i)}
            [:span text]))))))

;; rf2-k3y92 — Enter/Space activation for sidebar rows. The
;; variant-row + workspace-row + story-block headers render as `<div>`
;; with `on-click`; without a key handler keyboard-only users can't
;; activate them even with `role="button"` + `tabindex="0"`. The
;; pattern mirrors the WAI-ARIA "Button" practice: Enter activates,
;; Space activates (and we `preventDefault` on Space so the page
;; doesn't scroll). Returns a handler that wraps the click `(f)` form.
(defn- on-row-key-down
  "Build an `:on-key-down` handler that fires `f` for Enter or Space.
  `f` is a 0-arity fn (the same shape as the row's `on-click` closure
  but with the event ignored — call sites already discard it). The
  Space key gets `preventDefault` so the page doesn't scroll."
  [f]
  (fn [^js evt]
    (let [k (.-key evt)]
      (cond
        (= k "Enter") (do (.preventDefault evt) (f))
        (= k " ")     (do (.preventDefault evt) (f))))))

(defn- variant-row
  [variant-id selected? testable? status tags query]
  (let [activate (fn []
                   ;; rf2-hscut — symmetric escape from workspace mode.
                   ;; Selecting a variant clears any previously-selected
                   ;; workspace so the canvas's ws-id short-circuit
                   ;; (`shell.cljs` `main-pane`) yields to the variant
                   ;; branch. Mirror of the workspace-row click below,
                   ;; which clears `:selected-variant`.
                   (state/swap-state!
                     (fn [s] (-> s
                                 (state/select-variant variant-id)
                                 (state/select-workspace nil)))))]
  [:div {:style       (merge (:variant-row styles)
                             (when selected? (:variant-row-active styles)))
         :data-test   "story-sidebar-variant-row"
         :data-variant (str variant-id)
         ;; rf2-k3y92 — sidebar rows are clickable `<div>`s; expose them
         ;; as keyboard-operable buttons (role + tabindex + Enter/Space
         ;; key handler) so keyboard-only users can navigate into and
         ;; activate them. The visual treatment stays unchanged (the
         ;; focus ring is the global Story `:focus-visible` 2px amber
         ;; outline scoped to `[data-rf-story-root]`).
         :role         "button"
         :tab-index    "0"
         :aria-pressed (if selected? "true" "false")
         :aria-label   (str "Open variant " (name variant-id))
         :on-key-down  (on-row-key-down activate)
         :on-click     (fn [_] (activate))}
   ;; rf2-p0wur — per-row glyph affordance. Testable variants keep the
   ;; status dot (it carries pass/fail/running colour); non-testable
   ;; variants get a refined variant glyph so every row carries an
   ;; iconographic prefix at a uniform indent.
   (if testable?
     [status-dot status]
     [:span {:style (:variant-glyph styles)}
      [glyphs/variant-glyph 10]])
   ;; rf2-yngai — wrap label so matched substrings render with the
   ;; amber-tint highlight when a search query is in flight.
   (into [:span] (highlighted-label (str "/" (name variant-id)) query))
   [tag-badges tags]]))

(defn- story-block
  "Render one story header + its variants. `entry` shape is
  `{:story-id ... :variants [[variant-id body] ...]}` (the shape
  produced by `state/group-variants-by-story`).

  rf2-p0wur: story rows lead with an amber diamond glyph + carry an
  inter-story spacer.
  rf2-yngai: story labels carry the amber-tint match highlight when
  a search query is in flight.
  rf2-8j7wg (audit C-4): the story header row is itself clickable —
  it opens the rollup docs page that aggregates every variant's docs
  sections. Mirrors Storybook's `Component.docs` parent-level page."
  [{:keys [story-id variants]} selected-variant selected-story testable-set test-runs query]
  (let [registered? (some? story-id)
        selected?   (and registered? (= story-id selected-story))
        activate    (fn []
                      (when registered?
                        (state/swap-state! state/select-story story-id)))]
    [:div {:style (:story-block styles)}
     [:div (cond-> {:style       (merge (:story-row styles)
                                        (when selected?
                                          (:story-row-active styles)))
                    :data-test   "story-sidebar-story-row"
                    :data-story  (str story-id)}
             registered?
             (merge {:role         "button"
                     :tab-index    "0"
                     :aria-pressed (if selected? "true" "false")
                     :aria-label   (str "Open " (name story-id) " docs rollup")
                     :on-key-down  (on-row-key-down activate)
                     :on-click     (fn [_] (activate))}))
      [:span {:style (:story-glyph styles)}
       [glyphs/story-glyph 13]]
      (into [:span] (highlighted-label (str (or story-id "(no story)")) query))]
     (for [[vid body] variants]
       (let [testable? (contains? testable-set vid)
             status    (or (get-in test-runs [vid :status]) :pending)
             tags      (:tags body)]
         ^{:key vid}
         [variant-row vid (= vid selected-variant) testable? status tags query]))]))

(defn- workspace-row
  [workspace-id selected?]
  (let [activate (fn []
                   (state/swap-state!
                     (fn [s] (-> s
                                 (state/select-workspace workspace-id)
                                 (state/select-variant nil)))))]
  [:div {:style    (merge (:workspace-row styles)
                          (when selected? (:workspace-row-active styles)))
         :data-test   "story-sidebar-workspace-row"
         :data-workspace (str workspace-id)
         ;; rf2-k3y92 — keyboard-operable button semantics. See
         ;; `variant-row` for the parallel role/tabindex/key-handler
         ;; pattern.
         :role         "button"
         :tab-index    "0"
         :aria-pressed (if selected? "true" "false")
         :aria-label   (str "Open workspace " (name workspace-id))
         :on-key-down  (on-row-key-down activate)
         :on-click     (fn [_] (activate))}
   [:span {:style (:workspace-glyph styles)}
    [glyphs/workspace-glyph 12]]
   [:span (str workspace-id)]]))

;; ---- chrome-level test widget (rf2-q0irb) -------------------------------

(defn run-opts-for-variant
  "Build the `run-variant` opts map for `vid` from the current shell
  state, threading the per-variant cell-overrides through so a Run-all
  reproduces the same effective-args as the canvas / docs / share /
  workspace surfaces (rf2-zq6sn).

  The cell-overrides slot is `{variant-id → override-map}` (state.cljc
  §49); each variant must look up its OWN entry — passing a single
  blanket map (or `nil`) for every variant drops the per-variant
  controls the user set in the controls panel."
  [shell vid]
  {:active-modes   (:active-modes shell)
   :cell-overrides (get-in shell [:cell-overrides vid])
   :substrate      (:substrate shell)})

(defn- run-one-test!
  "Dispatch a single `run-variant` against `vid` and fold the result
  into the shell-state `[:tests :runs]` slot. Marks `:running` up front,
  records pass/fail/skip counts on resolve, and clears the slot on
  rejection. Shared between the chrome widget's 'Run all' button and
  the watch-mode auto-re-run (rf2-z1h0f).

  Per rf2-zq6sn each variant's run threads its OWN cell-overrides
  entry from shell state — same lookup the canvas / pane / share-url
  paths perform. The shell-state snapshot is taken once per variant
  so concurrent runs don't race against a swap-state! between the
  read and the dispatch."
  [vid]
  (state/swap-state! state/mark-test-running vid)
  (let [opts (run-opts-for-variant (state/get-state) vid)]
    (-> (runtime/run-variant vid opts)
        (async/then  (fn [result]
                       ;; `state/aggregate-summary` is the canonical fold
                       ;; (rf2-khmon); it lives in shell-state so both
                       ;; this widget AND the `:test` mode pane share one
                       ;; impl without a require cycle.
                       (let [summary (-> (state/aggregate-summary
                                           (:assertions result))
                                         (assoc :elapsed-ms (:elapsed-ms result)
                                                :ran-at-ms  nil))]
                         (state/swap-state! state/record-test-run vid summary))
                       nil))
        (async/catch* (fn [_]
                        ;; A rejection drops the running stamp so the
                        ;; dot doesn't get stuck yellow.
                        (state/swap-state! state/clear-test-run vid)
                        nil)))))

(defn- run-all-tests!
  "Drive `run-variant` over every testable variant. Marks each variant
  `:running` up front so the sidebar dots flip yellow in unison, then
  dispatches the runs in parallel and records each as it resolves.

  Variant runs are scheduled in parallel via `run-variant` (each
  resolves a fresh promise / future). Per Spec 002 §Programmatic API
  + IMPL-SPEC §3.2 each variant runs in its own frame, so concurrent
  runs do not cross-contaminate app-db."
  [variant-ids]
  (doseq [vid variant-ids]
    (run-one-test! vid)))

(defn watch-rerun!
  "Public entry point for the watch-mode detector (rf2-z1h0f). Drives
  `run-variant` for the given seq of variant-ids whose snapshot-identity
  drifted since the last observation. Shares the same per-variant
  pipeline as 'Run all' — marks running, folds the result into
  `[:tests :runs]` — so the sidebar dots and chrome widget headline
  transit through `:running` to the new `:pass` / `:fail` exactly as
  if the user had clicked the button.

  Called from `re-frame.story.ui.shell/detect-and-tick!` when watch
  mode is on and a drift is detected."
  [variant-ids]
  (doseq [vid variant-ids]
    (run-one-test! vid)))

(defn test-widget
  "Chrome-level test widget. Aggregates `run-variant` outcomes across
  every testable variant. `:test`-tagged + `:play`-bearing variants
  contribute; variants without `:play` are excluded by `testable-
  variant-ids` so the headline counts don't mislead.

  Renders nothing when no variants are testable — the widget is the
  Vitest-reporter parity (rf2-q0irb) per spec/009 §Foundational
  status; a Story project with zero `:test` variants has nothing for
  it to report.

  Per rf2-z1h0f the widget also carries an eye-icon watch-mode toggle
  beneath the count chips. When on, the shell auto-re-runs testable
  variants whose snapshot-identity drifted since the last observation
  (the detection signal is wired in `re-frame.story.ui.shell`).

  HOT PATH (rf2-dtj61): `testable-variant-ids` is the seq the parent
  sidebar already derives for its per-variant status dots. Callers
  may thread the precomputed seq through as `variant-ids` to avoid a
  second registry walk; the no-arg form keeps the canonical surface
  for tests and standalone consumers."
  ([shell registry]
   (test-widget shell registry (state/testable-variant-ids
                                 (:variants registry))))
  ([shell _registry variant-ids]
   (let [summary     (state/test-summary shell variant-ids)
         {:keys [total passed failed running pending all-green?]} summary
         any-run?    (pos? running)
         watch-on?   (state/test-watch-mode? shell)
         headline    (cond
                       (zero? total)  "Tests"
                       all-green?     (str "Tests · ✓ " passed)
                       :else          (str "Tests · " passed "/" total))]
    [:div {:style     (:widget styles)
           :data-test "story-test-widget"}
     [:div {:style (:widget-h styles)
            :data-test "story-test-widget-headline"}
      headline]
     (cond
       (zero? total)
       [:div {:style     (:widget-empty styles)
              :data-test "story-test-widget-empty"}
        "no :test variants"]

       :else
       [:div
        [:div {:style     (:widget-counts styles)
               :data-test "story-test-widget-counts"}
         [:span {:style (:widget-pass styles)} (str "✓ " passed)]
         [:span {:style (:widget-fail styles)} (str "✗ " failed)]
         (when (pos? running)
           [:span {:style (:widget-run styles)} (str "▸ " running)])
         (when (pos? pending)
           [:span {:style (:widget-pend styles)} (str "○ " pending)])]
        [:button
         {:style       (merge (:widget-btn styles)
                              (when any-run? (:widget-btn-disabled styles)))
          :data-test   "story-test-widget-run-all"
          :disabled    any-run?
          :aria-label  "Run every :test-tagged variant"
          :on-click    (fn [_]
                         (when-not any-run?
                           (run-all-tests! variant-ids)))}
         (if any-run? "Running…" "Run all")]
        ;; rf2-z1h0f — watch-mode toggle. Eye glyph reads on/off; the
        ;; chip's aria-pressed reflects the boolean. Toggle-on seeds
        ;; `[:tests :content-hashes]` so the first detector tick after
        ;; toggle-on doesn't fire a spurious re-run for every variant.
        [:div {:style (:watch-row styles)}
         [:button
          {:style         (merge (:watch-btn styles)
                                 (when watch-on? (:watch-btn-on styles)))
           :data-test     "story-test-widget-watch-toggle"
           :data-state    (if watch-on? "on" "off")
           :aria-pressed  (if watch-on? "true" "false")
           :aria-label    (if watch-on?
                            "Disable watch-mode auto-re-run"
                            "Enable watch-mode auto-re-run")
           :title         (if watch-on?
                            "Watching — variants re-run on content change"
                            "Watch mode off — re-run is explicit")
           :on-click      (fn [_]
                            (state/swap-state! state/set-test-watch-mode
                                               (not watch-on?)))}
          (if watch-on? "● watching" "○ watch")]]])])))

(defn- search-input
  "rf2-yngai — search-as-you-type input row. Filters the tree in-place
  on every keystroke. Esc clears the query AND blurs the input."
  [query-ratom]
  [:div {:style     (:search-row styles)
         :data-test "story-sidebar-search-row"}
   [:input {:type        "search"
            :style       (:search-input styles)
            :placeholder "Search stories…"
            :value       @query-ratom
            :data-test   "story-sidebar-search-input"
            :aria-label  "Filter stories and variants"
            :on-change   (fn [^js evt]
                           (reset! query-ratom (.. evt -target -value)))
            :on-key-down (fn [^js evt]
                           (when (= "Escape" (.-key evt))
                             (.preventDefault evt)
                             (reset! query-ratom "")
                             (some-> (.-target evt) .blur)))}]
   (when (seq @query-ratom)
     [:button {:style       (:search-clear styles)
               :data-test   "story-sidebar-search-clear"
               :aria-label  "Clear search"
               :title       "Clear search"
               :on-click    (fn [_] (reset! query-ratom ""))}
      "×"])])

(defn sidebar
  "Top-level sidebar component. Reads the registry snapshot + shell
  state, builds the filtered tree, and renders.

  Per rf2-xc65 the sidebar renders as a `<nav>` landmark.
  Per rf2-q0irb carries per-variant status dots + chrome test widget.
  Per rf2-yngai carries a search-as-you-type input above the tree
  (ephemeral local state; not persisted across reloads)."
  ([] (sidebar nil))
  ([opts]
   (let [query-ratom (r/atom "")]
     (fn [opts]
       (let [shell           @state/shell-state-atom
             registry        (state/registry-snapshot)
             tag-filter      (:tag-filter shell)
             sel-variant     (:selected-variant shell)
             sel-ws          (:selected-workspace shell)
             sel-story       (:selected-story shell)
             tag->axis       (registrar/tag->axis-index)
             visible         (state/filter-variants (:variants registry)
                                                    tag-filter
                                                    tag->axis)
             grouped-all     (state/group-variants-by-story visible)
             query           @query-ratom
             grouped         (search/filter-grouped-tree grouped-all query)
             workspaces      (search/filter-workspaces
                               (:workspaces registry) query)
             test-runs       (get-in shell [:tests :runs])
             testable-vec    (state/testable-variant-ids (:variants registry))
             testable-set    (set testable-vec)
             searching?      (seq (str/trim query))]
         [:nav {:style      (merge (:wrap styles) (:style opts))
                :data-test  "story-sidebar"
                :aria-label "Stories and workspaces"
                :tab-index  "0"}
          [:div {:style (:tree styles)}
           ;; rf2-vxpq1 — sidebar landmarks the two top-level sections
           ;; ("Stories" / "Workspaces") with `role="heading"` +
           ;; `aria-level="2"`. The visible `<div>` keeps its existing
           ;; styling unchanged; the role + level expose the section
           ;; structure to AT users navigating by heading. Pure
           ;; visual hiccup: no `<h2>` rewrite (would shift layout
           ;; via UA stylesheet defaults), no test-id change.
           [:div {:style       (:header styles)
                  :role        "heading"
                  :aria-level  "2"}
            [:span {:aria-hidden "true"
                    :style {:display "inline-flex" :align-items "center"
                            :color (:accent-amber colors/tokens)}}
             [glyphs/story-glyph 12]]
            [:span "Stories"]]
           [search-input query-ratom]
           [tag-filter-row (:variants registry) tag-filter tag->axis]
           (if (empty? grouped)
             [:div {:style     (:empty styles)
                    :data-test (if searching?
                                 "story-sidebar-search-empty"
                                 "story-sidebar-empty")}
              (cond
                searching?
                (str "no matches for '" query "'")

                (empty? (:variants registry))
                "no variants registered"

                :else
                "no variants match the active tag filter")]
             (for [{:keys [story-id] :as entry} grouped]
               ^{:key (or story-id :nostory)}
               [story-block entry sel-variant sel-story testable-set test-runs query]))
           (when (seq workspaces)
             [:div
              ;; rf2-vxpq1 — matching heading semantics on the
              ;; Workspaces section so AT users can land on either
              ;; section via heading navigation.
              [:div {:style       (:section styles)
                     :role        "heading"
                     :aria-level  "2"}
               [:span {:aria-hidden "true"
                       :style {:display "inline-flex" :align-items "center"
                               :color (:info colors/tokens)
                               :margin-right "6px"
                               :vertical-align "-2px"}}
                [glyphs/workspace-glyph 11]]
               "Workspaces"]
              (for [[wid _body] (sort-by key workspaces)]
                ^{:key wid}
                [workspace-row wid (= wid sel-ws)])])]
          [test-widget shell registry testable-vec]])))))
