(ns re-frame.story.ui.test-mode.view
  "The `:test` mode pane — in-canvas aggregated test-runner view.
  Per rf2-qmjo + spec/009.

  Replaces the `mode-tabs/tests-placeholder` stub that landed with the
  mode-tabs primitive (rf2-9hc8). The pane runs the variant's `:play`
  sequence via `run-variant`, reads the `:assertions` vector off the
  result, and renders an aggregated pass/fail summary inside the
  canvas:

      ┌──────────────────────────────────────────────────────┐
      │  Header — variant id · parent story · Re-run button   │
      │           · last-run timestamp + elapsed              │
      ├──────────────────────────────────────────────────────┤
      │  Summary badge — status pill (pass/fail/empty) +      │
      │           ✓ N passed · ✗ N failed · ⊘ N skipped       │
      ├──────────────────────────────────────────────────────┤
      │  Per-test table — one row per assertion record, in    │
      │           execution order; collapsible failure detail │
      │           surfaces :expected / :actual / :reason +    │
      │           the source-coord stamping                   │
      └──────────────────────────────────────────────────────┘

  When the variant body's `:play` slot is empty / absent the pane
  short-circuits and renders an empty-state placeholder pointing at
  the canonical testing-recipes leaf (Story doesn't auto-allocate a
  frame in this branch).

  Read-only — no input mutates args / overrides / modes. The Re-run
  button mutates **runtime state** (re-allocates the variant frame)
  but not the variant's authoring shape. Switching from `:test` back
  to `:dev` restores the canvas exactly as the user left it.

  ## Split (rf2-8n2fz)

  This namespace owns styles, the per-section renderers
  (`header` / `summary-section` / `scrubber-section` / `rows-section` /
  `empty-state`), and the top-level `test-view` component. Companion
  namespaces:

  - `re-frame.story.ui.test-mode.pure`  — JVM-testable pure data → data
    helpers (`variant-has-tests?`, `assertion-row`, `play-step-statuses`,
    `epoch-id-slice`, `format-elapsed-ms`, `format-timestamp-ms`). The
    `aggregate-summary` fold lives in `re-frame.story.ui.state` so the
    sidebar / chrome-level test widget can share it (rf2-khmon).
  - `re-frame.story.ui.test-mode.state` — CLJS-only `results-atom` +
    the `begin-run!` / `store-result!` / `select-step!` /
    `toggle-expanded!` / `run-variant-pane!` mutators.

  ## Elision

  CLJS-only. The shell's `main-pane` only reaches `test-view` via the
  `(when config/enabled? ...)`-gated mount call, so production builds
  never invoke it — closure DCEs the lot."
  (:require [reagent.core                              :as r]
            [re-frame.story.ui.open-in-editor          :as open-in-editor]
            [re-frame.story.ui.state                   :as shell-state]
            [re-frame.story.ui.test-mode.pure          :as pure]
            [re-frame.story.ui.test-mode.state         :as state]
            [re-frame.story.ui.test-mode.stepper-view  :as stepper-view]
            [re-frame.story.ui.test-mode.view-styles   :refer [styles]]))

;; Styles live in `re-frame.story.ui.test-mode.view-styles` (pure-data
;; leaf, no Reagent dep). Required as `styles` above so the in-file
;; call sites (`(:wrap styles)` etc.) stay textually identical.

;; ---- section renderers ---------------------------------------------------

(defn- header
  "Variant id + parent story id + Re-run button + last-run badge."
  [variant-id]
  (let [slot       (get @state/results-atom variant-id)
        running?   (:running? slot)
        ran-at-ms  (:ran-at-ms slot)
        result     (:result slot)
        elapsed    (:elapsed-ms result)
        story-id   (pure/parent-story-id variant-id)]
    [:div
     [:h1 {:style (:h1 styles)} (str variant-id)]
     [:div {:style     (:sub styles)
            :data-test "story-test-parent-story"}
      (if story-id
        (str "parent story: " story-id)
        "no parent story registered")]
     [:div {:style (:header-row styles)}
      [:button
       {:style          (merge (:rerun-btn styles)
                               (when running? (:rerun-running styles)))
        :data-test      "story-test-rerun"
        :disabled       running?
        :aria-label     "Re-run the variant's :play sequence"
        :on-click       (fn [_] (when-not running? (state/run-variant-pane! variant-id)))}
       (if running? "Running…" "Re-run")]
      [:div {:style (:last-run styles)}
       (when ran-at-ms
         [:span {:data-test "story-test-timestamp"}
          (str "ran " (pure/format-timestamp-ms ran-at-ms))])
       (when (and ran-at-ms elapsed)
         [:span " · "])
       (when (number? elapsed)
         [:span {:data-test "story-test-elapsed"}
          (pure/format-elapsed-ms elapsed)])]]]))

(defn- summary-section
  "Status pill + ✓ / ✗ / ⊘ counts. Renders nothing until at least
  one run has executed; the renderer composes its own placeholder
  in that interim state."
  [variant-id]
  (let [slot   (get @state/results-atom variant-id)
        result (:result slot)]
    (when result
      (let [summary (shell-state/aggregate-summary (:assertions result))
            {:keys [total passed failed skipped all-passed?]} summary
            pill-style (cond
                         (zero? total)  (:pill-empty styles)
                         all-passed?    (:pill-pass styles)
                         :else          (:pill-fail styles))
            pill-text  (cond
                         (zero? total) "no assertions recorded"
                         all-passed?   (str passed " passed")
                         :else         (str failed " failed of " total))]
        [:div {:style     (:section styles)
               :data-test "story-test-summary-section"}
         [:div {:style (:section-h styles)} "Summary"]
         [:div {:style (:pill-row styles)}
          [:span {:style     (merge (:pill styles) pill-style)
                  :data-test "story-test-status-pill"}
           pill-text]
          [:span {:style     (:counts styles)
                  :data-test "story-test-counts"}
           [:span {:style (:count-pass styles)} (str "✓ " passed " passed")]
           "  "
           [:span {:style (:count-fail styles)} (str "✗ " failed " failed")]
           "  "
           [:span {:style (:count-skip styles)} (str "⊘ " skipped " skipped")]]]]))))

(defn- tick-style-for
  "Pick the style map for a single tick based on `status`."
  [status]
  (case status
    :pass  (:tick-pass styles)
    :fail  (:tick-fail styles)
    :skip  (:tick-skip styles)
    :event (:tick-event styles)
    (:tick-event styles)))

(defn- step-glyph
  "Compact glyph for a tick: ✓ / ✗ / ⊘ for assertion outcomes, · for
  plain events. Pure-data; doesn't reach into ratoms."
  [status]
  (case status
    :pass  "✓"
    :fail  "✗"
    :skip  "⊘"
    :event "·"
    "·"))

(defn- scrubber-section
  "Step-through scrubber (rf2-lc36w). One tick per `:play` event with
  pass/fail/event/skip status colouring. Click a tick (or drag the
  slider below) to restore the variant's app-db to that step's
  epoch — the canvas re-renders against it.

  Renders nothing until a run has captured an epoch slice. When
  `:play` ran but no epochs were captured (production elision, or
  the ring buffer was disabled), the section short-circuits to a
  muted hint rather than a broken scrubber."
  [variant-id]
  (let [slot          (get @state/results-atom variant-id)
        result        (:result slot)
        play-events   (or (:play-events slot) [])
        epoch-ids     (or (:epoch-ids slot) [])
        selected-step (:selected-step slot)
        n             (count play-events)]
    (when (and result (pos? n))
      (let [statuses (pure/play-step-statuses play-events (:assertions result))
            has-epochs? (= n (count epoch-ids))]
        [:div {:style     (merge (:section styles) (:scrub-wrap styles))
               :data-test "story-test-scrubber-section"}
         [:div {:style (:scrub-h styles)}
          [:span "Step-through"]
          [:span {:style {:color "#9a9a9a" :font-weight "normal"}}
           (cond
             (not has-epochs?)        (str n " steps · no epoch buffer")
             (some? selected-step)    (str "step " (inc selected-step) "/" n)
             :else                    (str n " steps"))]]
         (if-not has-epochs?
           [:div {:style     {:color       "#9a9a9a"
                              :font-style  "italic"
                              :font-size   "11px"
                              :font-family "monospace"}
                  :data-test "story-test-scrubber-no-epochs"}
            "epoch buffer empty — scrubber unavailable (run is non-elided?)"]
           [:div
            [:div {:style     (:scrub-ticks styles)
                   :data-test "story-test-scrubber-ticks"}
             (for [{:keys [index status label]} statuses]
               ^{:key index}
               [:span {:style       (merge (:scrub-tick styles)
                                           (tick-style-for status)
                                           (when (= index selected-step)
                                             (:tick-selected styles)))
                       :data-test   "story-test-scrubber-tick"
                       :data-index  (str index)
                       :data-status (name status)
                       :title       (str "step " (inc index) " · " label)
                       :on-click    (fn [_] (state/select-step! variant-id index))}
                (step-glyph status)])]
            [:input {:type        "range"
                     :min         0
                     :max         (max 0 (dec n))
                     :value       (or selected-step (dec n))
                     :style       (:scrub-slider styles)
                     :data-test   "story-test-scrubber-slider"
                     :aria-label  "Step through play sequence"
                     :on-change   (fn [e]
                                    (let [idx (js/parseInt
                                                (.. e -target -value))]
                                      (state/select-step! variant-id idx)))}]
            [:div {:style (:scrub-detail styles)}
             (when (some? selected-step)
               (let [step (nth statuses selected-step nil)]
                 [:span {:data-test "story-test-scrubber-selected-label"}
                  (str "→ " (:label step))]))
             (when (some? selected-step)
               [:button {:style     (:scrub-release styles)
                         :data-test "story-test-scrubber-release"
                         :on-click  (fn [_] (state/select-step! variant-id nil))}
                "release"])]])]))))

(defn- row-detail
  "The collapsible failure detail — surfaces assertion and runtime
  error context with enough data to diagnose failures from shell output."
  [{:keys [expected actual reason variant-id phase event predicate error source]}]
  [:div {:style     (:detail-box styles)
         :data-test "story-test-row-detail"}
   (when variant-id
     [:div
      [:span {:style (:detail-key styles)} "variant: "]
      (pr-str variant-id)])
   (when phase
     [:div
      [:span {:style (:detail-key styles)} "phase: "]
      (pr-str phase)])
   (when event
     [:div
      [:span {:style (:detail-key styles)} "source: "]
      (pr-str event)])
   (when predicate
     [:div
      [:span {:style (:detail-key styles)} "predicate: "]
      (pr-str predicate)])
   (when (some? reason)
     [:div
      [:span {:style (:detail-key styles)} "reason: "]
      (str reason)])
   (when (map? error)
     [:div
      [:div
       [:span {:style (:detail-key styles)} "error: "]
       (str (:message error))]
      (when-let [data (:data error)]
        [:div
         [:span {:style (:detail-key styles)} "error data: "]
         (pr-str data)])
      (when-let [stack (:stack error)]
        [:pre {:style {:white-space "pre-wrap"
                       :max-height "10em"
                       :overflow "auto"}}
         (str stack)])])
   (when (or (some? expected) (contains? #{0 false} expected))
     [:div
      [:span {:style (:detail-key styles)} "expected: "]
      (pr-str expected)])
   (when (or (some? actual) (contains? #{0 false} actual))
     [:div
      [:span {:style (:detail-key styles)} "actual:   "]
      (pr-str actual)])
   (when (and (map? source) (or (:file source) (:line source)))
     [:div {:style (:detail-source styles)}
      (str "at " (:file source)
           (when (:line source) (str ":" (:line source))))
      ;; rf2-evgf5: per-assertion 'Open in editor' chip — surfaces the
      ;; assertion's source-coord (the play-step site, captured by the
      ;; assertion's record builder per spec/004).
      (open-in-editor/open-chip source :test-detail)])])

(defn- status-glyph
  [status]
  (case status
    :pass [:span {:style (:status-pass styles)} "●"]
    :fail [:span {:style (:status-fail styles)} "●"]
    :skip [:span {:style (:status-skip styles)} "○"]
    [:span {:style (:status-skip styles)} "○"]))

(defn- rows-section
  "Per-test rows. Empty when no run has executed; renders nothing
  when the run recorded zero assertions (the summary pill already
  told the user)."
  [variant-id]
  (let [slot     (get @state/results-atom variant-id)
        result   (:result slot)
        expanded (or (:expanded slot) #{})]
    (when result
      (let [rows (mapv pure/assertion-row (:assertions result))]
        (when (seq rows)
          [:div {:style     (:section styles)
                 :data-test "story-test-rows-section"}
           [:div {:style (:section-h styles)} "Per-test"]
           [:table {:style     (:table styles)
                    :data-test "story-test-table"}
            [:thead
             [:tr
              [:th {:style (merge (:th styles) (:td-status styles))} ""]
              [:th {:style (:th styles)} "assertion"]
              [:th {:style (:th styles)} "detail"]]]
            [:tbody
             ;; rf2-tistm — :expanded is keyed by the row's stable
             ;; identity (:row-key from assertion-row, the rendered
             ;; label string) rather than positional index. A re-run
             ;; that reorders or inserts assertions (e.g. a new :play
             ;; step lands between two existing ones) would otherwise
             ;; open the wrong row.
             (for [[i row] (map-indexed vector rows)]
               (let [rk    (:row-key row)
                     open? (contains? expanded rk)
                     fail? (= :fail (:status row))]
                 ^{:key (str rk "#" i)}
                 [:tr {:data-test     "story-test-row"
                       :data-status   (name (:status row))
                       :data-assertion (str (:assertion row))
                       :style         (when fail? (:row-fail styles))}
                  [:td {:style (merge (:td styles) (:td-status styles))}
                   (status-glyph (:status row))]
                  [:td {:style (:td styles)}
                   (:label row)]
                  [:td {:style (:td styles)}
                   (cond
                     fail?
                     [:div
                      [:button
                       {:style    (:details-tog styles)
                        :on-click (fn [_] (state/toggle-expanded! variant-id rk))
                        :aria-expanded (if open? "true" "false")}
                       (if open? "hide detail" "show detail")]
                      (when open? (row-detail (:detail row)))]

                     (= :skip (:status row))
                     [:span {:style (:status-skip styles)} "skipped"]

                     :else
                     "")]]))]]])))))

(defn- empty-state
  "Placeholder when the variant has no `:play` slot."
  [variant-id]
  [:div {:style     (:empty styles)
         :data-test "story-test-empty"}
   [:div {:style {:font-weight "bold" :margin-bottom "8px"
                  :color "#cccccc"}}
    "No tests registered for this variant"]
   [:div
    "Add a "
    [:code ":play"]
    " slot to "
    [:code (str variant-id)]
    " to register assertions."]
   [:a {:href       "https://github.com/day8/re-frame2/blob/main/skills/re-frame2/reference/cross-cutting/testing.md"
        :style      (:empty-link styles)
        :data-test  "story-test-empty-link"
        :target     "_blank"
        :rel        "noopener noreferrer"}
    "skills/re-frame2/reference/cross-cutting/testing.md"]])

;; ---- top-level component -------------------------------------------------

(defn test-view
  "Top-level `:test` mode pane for `variant-id`.

  On first mount for a variant the pane auto-runs the variant once
  so the user lands on the result; subsequent visits to the tab
  show the most recent result until the Re-run button is clicked.

  Returns nil when given no variant id (the shell already gates
  this, but the helper guards itself too).

  Per spec/009 the pane is read-only — no inputs mutate args /
  overrides / modes. The Re-run button mutates **runtime state**
  (re-allocates the variant frame via `reset-variant`) but not
  the variant's authoring shape."
  [variant-id]
  (when variant-id
    (r/create-class
      {:display-name "rf-story-test-view"
       :component-did-mount
       (fn [_this]
         ;; Auto-run on first mount per variant. If a slot already
         ;; exists (returning to the tab without re-mount) we don't
         ;; re-fire — the user clicks Re-run to refresh.
         (when (pure/variant-has-tests? variant-id)
           (when-not (get @state/results-atom variant-id)
             (state/run-variant-pane! variant-id))))
       :reagent-render
       (fn [variant-id]
         (cond
           (not (pure/variant-has-tests? variant-id))
           [:section {:style     (:wrap styles)
                      :data-test "story-test-view"
                      :aria-label "Variant tests"}
            [empty-state variant-id]]

           :else
           [:section {:style     (:wrap styles)
                      :data-test "story-test-view"
                      :aria-label "Variant tests"}
            [header variant-id]
            [summary-section variant-id]
            [stepper-view/stepper-section variant-id]
            [scrubber-section variant-id]
            [rows-section variant-id]]))})))
