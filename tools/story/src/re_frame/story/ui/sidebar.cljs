(ns re-frame.story.ui.sidebar
  "Story-tree sidebar. Per Stage 4 (rf2-ekai) IMPL-SPEC §4.

  Lays out the registered stories and their variants as a tree, with
  inline tag filtering. Each variant is a clickable row that updates
  the shell state's `:selected-variant`. Workspaces register below the
  story tree.

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
  (:require [re-frame.story.runtime  :as runtime]
            [re-frame.story.async    :as async]
            [re-frame.story.ui.state :as state]))

;; ---- styling -------------------------------------------------------------

(def ^:private styles
  {:wrap         {:width "260px"
                  :background "#252526"
                  :color "#cccccc"
                  :font-family "monospace"
                  :font-size "12px"
                  :border-right "1px solid #444"
                  :overflow "auto"
                  :padding "8px 0"
                  :display "flex"
                  :flex-direction "column"}
   :tree         {:flex "1"
                  :overflow "auto"
                  :display "flex"
                  :flex-direction "column"}
   :header       {:padding "8px 12px"
                  :font-weight "bold"
                  :color "#9cdcfe"
                  :border-bottom "1px solid #333"
                  :margin-bottom "4px"}
   :section      {:padding "12px 0 4px 12px"
                  :font-weight "bold"
                  :color "#b0b0b0"
                  :text-transform "uppercase"
                  :font-size "10px"
                  :letter-spacing "0.5px"}
   :tag-row      {:display "flex"
                  :flex-wrap "wrap"
                  :gap "4px"
                  :padding "8px 12px"
                  :border-bottom "1px solid #333"}
   :tag          {:padding "2px 6px"
                  :background "#37373d"
                  :color "#cccccc"
                  :border-radius "10px"
                  :cursor "pointer"
                  :font-size "10px"
                  :user-select "none"}
   :tag-active   {:background "#0e639c"
                  :color "white"}
   :story-row    {:padding "4px 12px"
                  :color "#dcdcaa"
                  :font-weight "bold"
                  :cursor "default"}
   :variant-row  {:padding "2px 12px 2px 24px"
                  :cursor "pointer"
                  :color "#cccccc"
                  :display "flex"
                  :align-items "center"
                  :gap "6px"}
   :variant-row-active {:background "#094771" :color "white"}
   :empty        {:color "#9a9a9a"
                  :font-style "italic"
                  :padding "8px 12px"}
   ;; rf2-q0irb — status dot + chrome-level test widget.
   :dot          {:width "8px"
                  :height "8px"
                  :border-radius "50%"
                  :flex-shrink "0"
                  :display "inline-block"}
   :dot-pass     {:background "#4ec9b0"}
   :dot-fail     {:background "#f48771"}
   :dot-running  {:background "#dcdcaa"
                  :opacity "0.7"}
   :dot-pending  {:background "transparent"
                  :border "1px solid #5a5a5a"}
   :widget       {:border-top "1px solid #333"
                  :margin-top "auto"
                  :padding "10px 12px"
                  :display "flex"
                  :flex-direction "column"
                  :gap "6px"
                  :background "#1f1f20"}
   :widget-h     {:font-weight "bold"
                  :color "#b0b0b0"
                  :text-transform "uppercase"
                  :font-size "10px"
                  :letter-spacing "0.5px"}
   :widget-counts {:display "flex"
                   :flex-wrap "wrap"
                   :gap "8px"
                   :font-family "monospace"
                   :font-size "11px"
                   :color "#cccccc"}
   :widget-pass  {:color "#4ec9b0"}
   :widget-fail  {:color "#f48771"}
   :widget-run   {:color "#dcdcaa"}
   :widget-pend  {:color "#9a9a9a"}
   :widget-btn   {:margin-top "4px"
                  :padding "4px 10px"
                  :background "#0e639c"
                  :color "white"
                  :border "none"
                  :border-radius "3px"
                  :cursor "pointer"
                  :font-family "monospace"
                  :font-size "11px"}
   :widget-btn-disabled {:background "#37373d"
                         :color "#9a9a9a"
                         :cursor "not-allowed"}
   :widget-empty {:color "#9a9a9a"
                  :font-style "italic"
                  :font-size "10px"}
   ;; rf2-z1h0f — watch-mode eye-icon toggle on the chrome widget.
   :watch-row    {:display     "flex"
                  :align-items "center"
                  :gap         "8px"
                  :margin-top  "2px"}
   :watch-btn    {:padding         "2px 8px"
                  :background      "transparent"
                  :color           "#9a9a9a"
                  :border          "1px solid #444"
                  :border-radius   "10px"
                  :cursor          "pointer"
                  :font-family     "monospace"
                  :font-size       "10px"
                  :letter-spacing  "0.3px"
                  :display         "inline-flex"
                  :align-items     "center"
                  :gap             "4px"}
   :watch-btn-on {:background "#1f4d3f"
                  :color      "#4ec9b0"
                  :border     "1px solid #4ec9b0"}
   ;; rf2-nwiwr — tag-as-badge affordance on variant rows.
   :tag-badges   {:display     "inline-flex"
                  :flex-wrap   "wrap"
                  :gap         "3px"
                  :margin-left "4px"}
   :tag-badge    {:padding       "0 5px"
                  :background    "#37373d"
                  :color         "#cccccc"
                  :border-radius "8px"
                  :font-family   "monospace"
                  :font-size     "9px"
                  :line-height   "14px"
                  :user-select   "none"
                  :flex-shrink   "0"}
   ;; Per-tag palette — keys on the canonical seven from
   ;; spec/007 §Inclusion tags; unknown tags fall through to
   ;; the neutral :tag-badge above.
   :tag-badge-dev          {:background "#264f78" :color "#9cdcfe"}
   :tag-badge-docs         {:background "#3a3a52" :color "#c586c0"}
   :tag-badge-test         {:background "#1f4d3f" :color "#4ec9b0"}
   :tag-badge-screenshot   {:background "#3a3a1f" :color "#dcdcaa"}
   :tag-badge-experimental {:background "#553a1f" :color "#ce9178"}
   :tag-badge-internal     {:background "#3a1f1f" :color "#f48771"}
   :tag-badge-agent        {:background "#1f3a3a" :color "#4ec9b0"}})

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

;; ---- pure: per-variant status dot (rf2-q0irb) ---------------------------

(def status->dot-style-key
  "Pure data → data: map a `:test-runs` status keyword to the styles
  map key that renders its dot colour. The render-only mapping lives
  next to the canonical statuses so both surfaces (sidebar dot, chrome
  widget) can JVM-test the projection without booting Reagent."
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

(defn- tag-filter-row
  [variants tag-filter]
  (let [all-tags (collect-tags variants)]
    [:div {:style (:tag-row styles)}
     (if (empty? all-tags)
       [:span {:style (:empty styles)} "no tags"]
       (for [tag all-tags]
         ^{:key tag}
         [:span {:style    (merge (:tag styles)
                                  (when (contains? tag-filter tag)
                                    (:tag-active styles)))
                 :on-click (fn [_] (state/swap-state!
                                     state/toggle-tag-filter tag))}
          (str tag)]))]))

(defn status-dot
  "Render the per-variant status glyph. Variants not in `:test-runs`
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

(defn- run-one-test!
  "Dispatch a single `run-variant` against `vid` and fold the result
  into the shell-state `:test-runs` slot. Marks `:running` up front,
  records pass/fail/skip counts on resolve, and clears the slot on
  rejection. Shared between the chrome widget's 'Run all' button and
  the watch-mode auto-re-run (rf2-z1h0f)."
  [vid]
  (state/swap-state! state/mark-test-running vid)
  (let [opts {:active-modes   (:active-modes (state/get-state))
              :cell-overrides nil
              :substrate      (:substrate (state/get-state))}]
    (-> (runtime/run-variant vid opts)
        (async/then  (fn [result]
                       ;; The aggregate-summary helper lives in
                       ;; `test-mode.pure` but we can't require that ns
                       ;; here (cyclic: test-mode.state requires this
                       ;; ns's parent shell-state, which would loop back
                       ;; through sidebar's requires). Inline the same
                       ;; six-line fold here — total / passed / failed /
                       ;; skipped — and let `record-test-run` do the
                       ;; rest. Two trivially-equal folds is cheaper
                       ;; than threading another module.
                       (let [assertions (or (:assertions result) [])
                             skipped?   (fn [r] (= :rf.assert/skipped
                                                   (:assertion r)))
                             n-skip     (count (filter skipped? assertions))
                             active     (remove skipped? assertions)
                             n-pass     (count (filter :passed? active))
                             n-fail     (- (count active) n-pass)
                             total      (count assertions)
                             summary    {:total       total
                                         :passed      n-pass
                                         :failed      n-fail
                                         :skipped     n-skip
                                         :all-passed? (and (pos? total)
                                                           (zero? n-fail)
                                                           (zero? n-skip))
                                         :elapsed-ms  (:elapsed-ms result)
                                         :ran-at-ms   nil}]
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
  `:test-runs` — so the sidebar dots and chrome widget headline transit
  through `:running` to the new `:pass` / `:fail` exactly as if the user
  had clicked the button.

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
  (the detection signal is wired in `re-frame.story.ui.shell`)."
  [shell registry]
  (let [variant-ids (state/testable-variant-ids (:variants registry))
        summary     (state/test-summary shell variant-ids)
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
        ;; `:test-content-hashes` so the first detector tick after
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
          (if watch-on? "● watching" "○ watch")]]])]))

(defn sidebar
  "Top-level sidebar component. Reads the registry snapshot + shell
  state, builds the filtered tree, and renders.

  Per rf2-xc65 the sidebar renders as a `<nav>` landmark with
  `tabindex=\"0\"` so axe-core's `region` rule passes and keyboard
  users can focus the scrollable tree.

  Per rf2-q0irb the sidebar carries two extra surfaces — the per-
  variant status dots (rendered inside each variant row when the
  variant is `:test`-tagged + `:play`-bearing) and the chrome-level
  test widget at the foot. Both read from `:test-runs`; the widget
  drives `run-variant` over the testable set on click."
  []
  (let [shell         @state/shell-state-atom
        registry      (state/registry-snapshot)
        tag-filter    (:tag-filter shell)
        sel-variant   (:selected-variant shell)
        sel-ws        (:selected-workspace shell)
        visible       (state/filter-variants (:variants registry) tag-filter)
        grouped       (state/group-variants-by-story visible)
        workspaces    (:workspaces registry)
        test-runs     (:test-runs shell)
        testable-set  (set (state/testable-variant-ids (:variants registry)))]
    [:nav {:style      (:wrap styles)
           :aria-label "Stories and workspaces"
           :tab-index  "0"}
     [:div {:style (:tree styles)}
      [:div {:style (:header styles)} "Stories"]
      [tag-filter-row (:variants registry) tag-filter]
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
     [test-widget shell registry]]))
