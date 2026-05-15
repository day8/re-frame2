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
            [re-frame.story.runtime           :as runtime]
            [re-frame.story.async             :as async]
            [re-frame.story.registrar         :as registrar]
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
  walking a Reagent tree."
  [status]
  (let [k     (status->dot-style-key status)
        label (dot-aria-label status)]
    [:span {:style       (merge (:dot styles) (k styles))
            :data-test   "story-sidebar-dot"
            :data-status (name (or status :pending))
            :role        "status"
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

(defn- variant-row
  [variant-id selected? testable? status tags]
  [:div {:style       (merge (:variant-row styles)
                             (when selected? (:variant-row-active styles)))
         :data-test   "story-sidebar-variant-row"
         :data-variant (str variant-id)
         :on-click    (fn [_] (state/swap-state!
                                state/select-variant variant-id))}
   (when testable? [status-dot status])
   [:span (str "/" (name variant-id))]
   [tag-badges tags]])

(defn- story-block
  "Render one story header + its variants. `entry` shape is
  `{:story-id ... :variants [[variant-id body] ...]}` (the shape
  produced by `state/group-variants-by-story`)."
  [{:keys [story-id variants]} selected-variant testable-set test-runs]
  [:div
   [:div {:style (:story-row styles)}
    (str (or story-id "(no story)"))]
   (for [[vid body] variants]
     (let [testable? (contains? testable-set vid)
           status    (or (get-in test-runs [vid :status]) :pending)
           tags      (:tags body)]
       ^{:key vid}
       [variant-row vid (= vid selected-variant) testable? status tags]))])

(defn- workspace-row
  [workspace-id selected?]
  [:div {:style    (merge (:variant-row styles)
                          (when selected? (:variant-row-active styles)))
         :on-click (fn [_]
                     (state/swap-state!
                       (fn [s] (-> s
                                   (state/select-workspace workspace-id)
                                   (state/select-variant nil)))))}
   (str workspace-id)])

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

(defn sidebar
  "Top-level sidebar component. Reads the registry snapshot + shell
  state, builds the filtered tree, and renders.

  Per rf2-xc65 the sidebar renders as a `<nav>` landmark with
  `tabindex=\"0\"` so axe-core's `region` rule passes and keyboard
  users can focus the scrollable tree.

  Per rf2-q0irb the sidebar carries two extra surfaces — the per-
  variant status dots (rendered inside each variant row when the
  variant is `:test`-tagged + `:play`-bearing) and the chrome-level
  test widget at the foot. Both read from `[:tests :runs]`; the widget
  drives `run-variant` over the testable set on click.

  HOT PATH (rf2-dtj61): every shell-state ratom change re-renders this
  component, which re-walks the registry to build the view model. We
  compute `testable-variant-ids` ONCE here — both the per-row
  `testable?` check (set membership) and the chrome-level `test-widget`
  share the same derivation. Deeper memoisation keyed on
  `(registrar-tick, tag-filter)` can wait until corpus size justifies."
  ([] (sidebar nil))
  ([opts]
  (let [shell           @state/shell-state-atom
        registry        (state/registry-snapshot)
        tag-filter      (:tag-filter shell)
        sel-variant     (:selected-variant shell)
        sel-ws          (:selected-workspace shell)
        ;; rf2-7ncf9 — faceted filter: AND across axes, OR within.
        ;; The axis-index reads from the tag registrar; passing it to
        ;; `filter-variants` activates the 3-arity facet-aware form.
        tag->axis       (registrar/tag->axis-index)
        visible         (state/filter-variants (:variants registry)
                                               tag-filter
                                               tag->axis)
        grouped         (state/group-variants-by-story visible)
        workspaces      (:workspaces registry)
        test-runs       (get-in shell [:tests :runs])
        testable-vec    (state/testable-variant-ids (:variants registry))
        testable-set    (set testable-vec)]
    [:nav {:style      (merge (:wrap styles) (:style opts))
           :data-test  "story-sidebar"
           :aria-label "Stories and workspaces"
           :tab-index  "0"}
     [:div {:style (:tree styles)}
      [:div {:style (:header styles)} "Stories"]
      [tag-filter-row (:variants registry) tag-filter tag->axis]
      (if (empty? grouped)
        [:div {:style (:empty styles)}
         (if (empty? (:variants registry))
           "no variants registered"
           "no variants match the active tag filter")]
        (for [{:keys [story-id] :as entry} grouped]
          ^{:key (or story-id :nostory)}
          [story-block entry sel-variant testable-set test-runs]))
      (when (seq workspaces)
        [:div
         [:div {:style (:section styles)} "Workspaces"]
         (for [[wid _body] (sort-by key workspaces)]
           ^{:key wid}
           [workspace-row wid (= wid sel-ws)])])]
     [test-widget shell registry testable-vec]])))
