(ns re-frame.story.ui.test-mode.view
  "The `:test` mode pane — in-canvas aggregated test-runner view.
  Per spec/009, presents the unified run-result UX per
  tools/story/spec/021-Story-UI-Test-And-Evidence.md §1.

  The pane runs the variant's `:script` sequence via `run-variant`,
  consumes the ONE unified run-result the runtime returns
  (`re-frame.story.result/run-result` merged with the lifecycle slots),
  and renders the result/proof surface inside the canvas:

      ┌──────────────────────────────────────────────────────┐
      │  Header — variant id · parent story · Re-run button   │
      │           · last-run timestamp + elapsed              │
      │           · runner selected vs required               │
      ├──────────────────────────────────────────────────────┤
      │  Summary badge — status pill (pass/fail/error/        │
      │           cannot-run/pending) + ✓/✗/⊘ counts          │
      ├──────────────────────────────────────────────────────┤
      │  Checks — grouped by check id, each disclosing its    │
      │           underlying assertion records                │
      ├──────────────────────────────────────────────────────┤
      │  Assertions — one row per assertion record (terminal  │
      │           + script-checkpoint), failed-only filter,   │
      │           collapsible :expected/:actual/:reason +     │
      │           source-coord 'open in editor'               │
      ├──────────────────────────────────────────────────────┤
      │  Schema violations — consumed (expected) vs           │
      │           unconsumed (agreement-floor failure)        │
      ├──────────────────────────────────────────────────────┤
      │  Cannot run — runner refusals: required vs available  │
      ├──────────────────────────────────────────────────────┤
      │  Evidence — result→spine link (graceful pending)      │
      └──────────────────────────────────────────────────────┘

  ## Result reading (spec/021 §1)

  The pane reads status from the unified run-result, not a split
  `:lifecycle` / `:assertions` reading. It reads the unified run-level `:status`, the
  `:checks` groups, the `:schema-violations` evidence, and the
  `:cannot-run` refusal rows through the pure projection helpers in
  `re-frame.story.ui.test-mode.pure` (`run-status` / `check-rows` /
  `schema-rows` / `cannot-run-rows` / `filter-rows` / `evidence-available?`).
  Slots the substrate does not yet populate render an honest empty/dash
  state — never a fabricated row.

  When the variant body's `:script` slot is empty / absent the pane
  short-circuits and renders an empty-state placeholder pointing at
  the canonical testing-recipes leaf (Story doesn't auto-allocate a
  frame in this branch).

  Read-only — no input mutates args / overrides / modes. The Re-run
  button mutates **runtime state** (re-allocates the variant frame)
  but not the variant's authoring shape. Switching from `:test` back
  to `:dev` restores the canvas exactly as the user left it.

  ## Layout

  This namespace owns styles, the per-section renderers
  (`header` / `summary-section` / `scrubber-section` / `rows-section` /
  `empty-state`), and the top-level `test-view` component. Companion
  namespaces:

  - `re-frame.story.ui.test-mode.pure`  — JVM-testable pure data → data
    helpers (`variant-has-tests?`, `assertion-row`, `play-step-statuses`,
    `epoch-id-slice`, `format-elapsed-ms`, `format-timestamp-ms`). The
    `aggregate-summary` fold lives in `re-frame.story.ui.state` so the
    sidebar / chrome-level test widget can share it.
  - `re-frame.story.ui.test-mode.state` — CLJS-only `results-atom` +
    the `begin-run!` / `store-result!` / `select-step!` /
    `toggle-expanded!` / `run-variant-pane!` mutators.

  ## Elision

  CLJS-only. The shell's `main-pane` only reaches `test-view` via the
  `(when config/enabled? ...)`-gated mount call, so production builds
  never invoke it — closure DCEs the lot."
  (:require [reagent.core                              :as r]
            [re-frame.story.predicates                 :as pred]
            [re-frame.story.ui.open-in-editor          :as open-in-editor]
            [re-frame.story.ui.promotion               :as promotion]
            [re-frame.story.ui.state                   :as shell-state]
            [re-frame.story.ui.test-mode.pure          :as pure]
            [re-frame.story.ui.test-mode.state         :as state]
            [re-frame.story.ui.test-mode.stepper-view  :as stepper-view]
            [re-frame.story.ui.test-mode.visual-a11y-view :as visual-a11y-view]
            [re-frame.story.ui.test-mode.view-styles   :refer [styles]]
            [re-frame.story.theme.typography :as typography :refer [mono-stack]]
            [re-frame.story.theme.colors :as colors]))

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
        story-id   (pred/parent-story-id variant-id)]
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
        :aria-label     "Re-run the variant's :script sequence"
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
          (pure/format-elapsed-ms elapsed)])]]
     ;; runner selected vs required (spec/021 §1). The unified result
     ;; threads `:runner` / `:required-runner` verbatim; render an honest
     ;; "—" when the host did not stamp them.
     (when result
       (let [runner   (:runner result)
             required (:required-runner result)]
         [:div {:style     (:runner-row styles)
                :data-test "story-test-runner-row"}
          [:span {:style (:runner-key styles)} "runner"]
          [:span {:style     (:runner-badge styles)
                  :data-test "story-test-runner-selected"}
           (if runner (str runner) "—")]
          [:span {:style (:runner-key styles)} "required"]
          [:span {:style     (:runner-badge styles)
                  :data-test "story-test-runner-required"}
           (if (seq required) (pr-str required) "—")]]))]))

(defn- summary-section
  "Top-level status pill + ✓ / ✗ / ⊘ counts (spec/021 §1 — overall status,
  including the distinct `:cannot-run` THIRD state + a `:pending`
  never-run/no-signal state). Renders nothing until at least one run has
  executed; the renderer composes its own placeholder in that interim
  state."
  [variant-id]
  (let [slot   (get @state/results-atom variant-id)
        result (:result slot)]
    (when result
      (let [summary (shell-state/aggregate-summary (:assertions result))
            {:keys [passed failed cannot-run total]} summary
            cannot-run (or cannot-run 0)
            ;; the unified run-level verdict (spec/021 §1), via the pure
            ;; projection. A tape-floor `:fail` / `:cannot-run`
            ;; refusal / `:error` surfaces even when assertion counts alone
            ;; would read green.
            status     (pure/run-status result summary)
            pill-style (case status
                         :pass       (:pill-pass styles)
                         :error      (:pill-error styles)
                         :cannot-run (:pill-cannot styles)
                         :pending    (:pill-pending styles)
                         (:pill-fail styles))
            pill-text  (case status
                         :pass       (str passed " passed")
                         :error      "error"
                         :cannot-run (str cannot-run " cannot-run of " total)
                         :pending    "no assertions recorded"
                         (str failed " failed of " total))]
        [:div {:style     (:section styles)
               :data-test "story-test-summary-section"}
         [:div {:style (:section-h styles)} "Summary"]
         [:div {:style (:pill-row styles)}
          [:span {:style       (merge (:pill styles) pill-style)
                  :data-test   "story-test-status-pill"
                  :data-status (name status)}
           pill-text]
          [:span {:style     (:counts styles)
                  :data-test "story-test-counts"}
           [:span {:style (:count-pass styles)} (str "✓ " passed " passed")]
           "  "
           [:span {:style (:count-fail styles)} (str "✗ " failed " failed")]
           "  "
           ;; the distinct THIRD status, rendered (spec/017
           ;; §`:cannot-run` — Story UI MUST render `:cannot-run` distinctly).
           [:span {:style (:count-skip styles)} (str "⊘ " cannot-run " cannot-run")]]]]))))

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
  "Compact glyph for a tick: ✓ / ✗ / ⊘ for assertion outcomes (the shared
  `predicates/assertion-glyph` vocabulary), · for plain events / unknown.
  Pure-data; doesn't reach into ratoms."
  [status]
  (get pred/assertion-glyph status "·"))

(defn- scrubber-section
  "Step-through scrubber. One tick per play event with
  pass/fail/event/skip status colouring. Click a tick (or drag the
  slider below) to restore the variant's app-db to that step's
  epoch — the canvas re-renders against it.

  Renders nothing until a run has captured an epoch slice. When
  the `:script` ran but no epochs were captured (production elision, or
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
          [:span {:style {:color (:text-tertiary colors/tokens) :font-weight "normal"}}
           (cond
             (not has-epochs?)        (str n " steps · no epoch buffer")
             (some? selected-step)    (str "step " (inc selected-step) "/" n)
             :else                    (str n " steps"))]]
         (if-not has-epochs?
           [:div {:style     {:color       (:text-tertiary colors/tokens)
                              :font-style  "italic"
                              :font-size   (:caption typography/type-scale)
                              :font-family mono-stack}
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
      ;; per-assertion 'Open in editor' chip — surfaces the
      ;; assertion's source-coord (the play-step site, captured by the
      ;; assertion's record builder per spec/004).
      (open-in-editor/open-chip source :test-detail)])])

(defn- status-glyph
  [status]
  (case status
    :pass       [:span {:style (:status-pass styles)} "●"]
    :fail       [:span {:style (:status-fail styles)} "●"]
    :error      [:span {:style (:status-fail styles)} "✖"]
    :cannot-run [:span {:style (:status-skip styles)} "⊘"]
    :skip       [:span {:style (:status-skip styles)} "○"]
    [:span {:style (:status-skip styles)} "○"]))

(defn- assertion-table
  "Render the assertion `rows` as the per-test table. `expanded` is the
  set of expanded row-keys (`row-key`). Factored out so both the
  assertions section and a check group can render the same table shape."
  [variant-id rows expanded]
  [:table {:style     (:table styles)
           :data-test "story-test-table"}
   [:thead
    [:tr
     [:th {:style (merge (:th styles) (:td-status styles))} ""]
     [:th {:style (:th styles)} "assertion"]
     [:th {:style (:th styles)} "detail"]]]
   [:tbody
    ;; :expanded is keyed by the row's stable identity
    ;; (:row-key from assertion-row, the rendered label string) rather
    ;; than positional index. A re-run that reorders or inserts
    ;; assertions would otherwise open the wrong row.
    (for [[i row] (map-indexed vector rows)]
      (let [rk    (:row-key row)
            open? (contains? expanded rk)
            ;; :error / :cannot-run disclose detail too.
            bad?  (#{:fail :error :cannot-run} (:status row))]
        ^{:key (str rk "#" i)}
        [:tr {:data-test      "story-test-row"
              :data-status    (name (:status row))
              :data-assertion (str (:assertion row))
              :style          (when bad? (:row-fail styles))}
         [:td {:style (merge (:td styles) (:td-status styles))}
          (status-glyph (:status row))]
         [:td {:style (:td styles)}
          (:label row)]
         [:td {:style (:td styles)}
          (cond
            bad?
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
            "")]]))]])

(defn- filter-toggle
  "The failed-only filter toggle (spec/021 §1). Hides passing rows so the
  user lands on the actionable ones. `failed-only?` is the current flag;
  `hidden` is the count of passing rows the filter would hide (rendered as
  a hint)."
  [variant-id failed-only? hidden]
  [:div {:style (:filter-row styles)}
   [:button
    {:style          (merge (:filter-toggle styles)
                            (when failed-only? (:filter-on styles)))
     :data-test      "story-test-failed-only"
     :aria-pressed   (if failed-only? "true" "false")
     :on-click       (fn [_] (state/set-failed-only! variant-id (not failed-only?)))}
    (if failed-only? "✓ failed only" "failed only")]
   (when (and failed-only? (pos? hidden))
     [:span {:style     (:filter-hint styles)
             :data-test "story-test-filter-hint"}
      (str hidden " passing row" (when (not= 1 hidden) "s") " hidden")])])

(defn- rows-section
  "Per-test assertion rows (spec/021 §1 — terminal + script-checkpoint
  assertions; the substrate folds both onto one `:assertions` slot today,
  so the pane renders them as one ordered list). Carries the failed-only
  filter. Empty when no run has executed; renders nothing when the run
  recorded zero assertions (the summary pill already told the user)."
  [variant-id]
  (let [slot         (get @state/results-atom variant-id)
        result       (:result slot)
        expanded     (or (:expanded slot) #{})
        failed-only? (boolean (:failed-only? slot))]
    (when result
      (let [all-rows (mapv pure/assertion-row (:assertions result))]
        (when (seq all-rows)
          (let [shown  (pure/filter-rows all-rows failed-only?)
                hidden (- (count all-rows) (count shown))]
            [:div {:style     (:section styles)
                   :data-test "story-test-rows-section"}
             [:div {:style (:section-h styles)} "Assertions"]
             (filter-toggle variant-id failed-only? hidden)
             (if (seq shown)
               (assertion-table variant-id shown expanded)
               [:div {:style     (:filter-hint styles)
                      :data-test "story-test-rows-all-passed"}
                "all assertions passed — nothing to show"])]))))))

(defn- checks-section
  "Checks grouped by check id (spec/021 §1; spec/017 §Checks — a failed
  check shows BOTH the check id AND its underlying assertion records).
  Renders nothing when the plan declared no checks (the common case
  today — an honest empty state, never a fabricated check group)."
  [variant-id]
  (let [slot     (get @state/results-atom variant-id)
        result   (:result slot)
        expanded (or (:expanded slot) #{})
        checks   (some-> result pure/check-rows)
        expanded-checks (or (:expanded-checks slot) #{})]
    (when (seq checks)
      [:div {:style     (:section styles)
             :data-test "story-test-checks-section"}
       [:div {:style (:section-h styles)} "Checks"]
       (for [{:keys [check status passed failed total rows]} checks]
         (let [open? (contains? expanded-checks check)]
           ^{:key (str check)}
           [:div {:style     (:check-box styles)
                  :data-test "story-test-check"
                  :data-status (name status)}
            [:div {:style    (:check-head styles)
                   :on-click (fn [_] (state/toggle-check! variant-id check))
                   :aria-expanded (if open? "true" "false")}
             (status-glyph status)
             [:span {:style (:check-id styles)} (str check)]
             [:span {:style (:check-counts styles)}
              (str passed "/" total " passed"
                   (when (pos? failed) (str " · " failed " failed")))]]
            (when open?
              [:div {:style (:check-body styles)}
               (assertion-table variant-id rows expanded)])]))])))

(defn- schema-section
  "Schema violations, marking consumed expected violations distinctly from
  unconsumed ones (spec/021 §1). Renders nothing when the tape carried no
  schema violations."
  [variant-id]
  (let [slot   (get @state/results-atom variant-id)
        result (:result slot)
        rows   (some-> result pure/schema-rows)]
    (when (seq rows)
      [:div {:style     (:section styles)
             :data-test "story-test-schema-section"}
       [:div {:style (:section-h styles)} "Schema violations"]
       (for [[i {:keys [selector where failing-id consumed? reason epoch-id]}]
             (map-indexed vector rows)]
         ^{:key (str selector "#" i)}
         [:div {:style       (merge (:schema-row styles)
                                    (if consumed?
                                      (:schema-consumed styles)
                                      (:schema-unconsumed styles)))
                :data-test   "story-test-schema-row"
                :data-consumed (str consumed?)}
          [:span {:style (merge (:schema-tag styles)
                                (if consumed?
                                  (:schema-tag-ok styles)
                                  (:schema-tag-bad styles)))}
           (if consumed? "consumed" "unconsumed")]
          [:span {:style (:schema-sel styles)} (pr-str selector)]
          [:div {:style (:schema-meta styles)}
           (str (when where (str (name where) " "))
                (when failing-id (str (pr-str failing-id) " "))
                (when epoch-id (str "· epoch " epoch-id)))
           (when reason [:div (str reason)])]])])))

(defn- cannot-run-section
  "Cannot-run rows — the runner refusals (spec/021 §1; spec/018 §12.6 — the
  distinct THIRD state). Each row says what evidence/runner was REQUIRED vs
  what was AVAILABLE, so it reads as a refusal rather than a failure.
  Renders nothing when the chosen runner satisfied every requirement."
  [variant-id]
  (let [slot   (get @state/results-atom variant-id)
        result (:result slot)
        rows   (some-> result pure/cannot-run-rows)]
    (when (seq rows)
      [:div {:style     (:section styles)
             :data-test "story-test-cannot-run-section"}
       [:div {:style (:section-h styles)} "Cannot run"]
       (for [[i {:keys [required available missing reason runner]}]
             (map-indexed vector rows)]
         ^{:key (str i)}
         [:div {:style     (:cannot-row styles)
                :data-test "story-test-cannot-run-row"}
          [:div
           [:span {:style (:cannot-key styles)} "required"]
           [:span {:style (:cannot-val styles)} (pr-str required)]]
          [:div
           [:span {:style (:cannot-key styles)} "available"]
           [:span {:style (:cannot-val styles)} (pr-str available)]]
          (when (seq missing)
            [:div
             [:span {:style (:cannot-key styles)} "missing"]
             [:span {:style (:cannot-val styles)} (pr-str missing)]])
          [:div {:style (:schema-meta styles)}
           (str (when reason (name reason))
                (when runner (str " · runner " runner)))]])])))

(defn- evidence-section
  "Result → evidence-spine link (spec/021 §2). The evidence-spine DISPLAY
  (ba86n.10) is not built yet, and the Story→Xray focus API (rf2-crtmq) is
  not wired here, so this renders a graceful affordance honestly: when the
  run retained an epoch tape the link target exists but the surface is
  pending; otherwise there is no evidence to link. No fabricated link."
  [variant-id]
  (let [slot   (get @state/results-atom variant-id)
        result (:result slot)]
    (when result
      (let [evidence? (pure/evidence-available? result)]
        [:div {:style     (:evidence-row styles)
               :data-test "story-test-evidence-row"
               :data-evidence (str evidence?)}
         (if evidence?
           [:span {:style (:evidence-pending styles)}
            "evidence spine pending — failures will link here once the "
            "evidence panel lands"]
           [:span {:style (:evidence-pending styles)}
            "no retained evidence for this run"])]))))

(defn- promotion-section
  "Generated-failure promotion entry point (spec/021 §3).
  DISTINCT from save-current-state (spec/019 §3): the source here is the
  CAPTURED RUN ARTIFACT this run produced, not the live canvas args. The
  button captures the current run as an artifact (a replay back-link when
  present, else synthesized from the run's play-events + result) and opens
  the promotion dialog where the user inspects, names, edits, tags, and
  registers a curated regression variant. Non-destructive — the captured
  artifact stays as evidence in the sidebar's 'Captured artifacts'
  affordance after promoting.

  Renders nothing until a run has produced a capturable program — promotion
  needs a replayable artifact, so a bare pending slot offers no button."
  [variant-id]
  (let [slot        (get @state/results-atom variant-id)
        result      (:result slot)
        play-events (or (:play-events slot) [])]
    (when (and result
               (some? (promotion/result->artifact result play-events)))
      [:div {:style     (:evidence-row styles)
             :data-test "story-test-promotion-row"}
       [:button
        {:style     {:padding       "4px 10px"
                     :background    (:bg-3 colors/tokens)
                     :color         (:text-secondary colors/tokens)
                     :border        (str "1px solid " (:border-default colors/tokens))
                     :border-radius "6px"
                     :cursor        "pointer"
                     :font-family   mono-stack
                     :font-size     (:caption typography/type-scale)}
         :data-test "story-test-promote-button"
         :title     "Capture this run as an artifact and promote it to a curated regression variant"
         :on-click  (fn [_]
                      (when-let [id (promotion/capture-from-result!
                                      result play-events variant-id)]
                        (promotion/open! id)))}
        "promote run → regression variant…"]
       [:span {:style {:margin-left "8px"
                       :color (:text-tertiary colors/tokens)
                       :font-style "italic"
                       :font-size (:micro typography/type-scale)}}
        "captures this run as evidence; distinct from save-current-state"]])))

(defn- empty-state
  "Placeholder when the variant has no `:script` slot."
  [variant-id]
  [:div {:style     (:empty styles)
         :data-test "story-test-empty"}
   [:div {:style {:font-weight "bold" :margin-bottom "8px"
                  :color (:text-primary colors/tokens)}}
    "No tests registered for this variant"]
   [:div
    "Add a "
    [:code ":script"]
    " slot to "
    [:code (str variant-id)]
    " to register assertions."]
   [:a {:href       "https://github.com/day8/re-frame2/blob/main/skills/re-frame2/references/cross-cutting/testing.md"
        :style      (:empty-link styles)
        :data-test  "story-test-empty-link"
        :target     "_blank"
        :rel        "noopener noreferrer"}
    "skills/re-frame2/references/cross-cutting/testing.md"]])

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
            ;; the unified run-result surfaces (spec/021 §1):
            ;; checks grouped by id, the per-test assertions (terminal +
            ;; script-checkpoint, with the failed-only filter), schema
            ;; violations (consumed + unconsumed), and cannot-run refusals.
            [checks-section variant-id]
            [stepper-view/stepper-section variant-id]
            [scrubber-section variant-id]
            [rows-section variant-id]
            [schema-section variant-id]
            [cannot-run-section variant-id]
            ;; visual + a11y check RESULTS (spec/021 §4).
            ;; Reads the browser-tier oracle records (structural-a11y /
            ;; axe-a11y / visual-snapshot) off the SAME unified `:assertions`
            ;; slot and presents them readably (violations as a
            ;; finding+locus list; screenshot identity; honest cannot-run),
            ;; rather than as the raw `:actual` EDN blob the generic
            ;; assertions table would show.
            [visual-a11y-view/visual-a11y-section variant-id]
            ;; result → evidence-spine link (spec/021 §2),
            ;; a graceful "evidence pending" until the spine display lands.
            [evidence-section variant-id]
            ;; generated-failure promotion entry point
            ;; (spec/021 §3). Captures THIS run as an artifact and opens the
            ;; promotion dialog. Distinct from save-current-state.
            [promotion-section variant-id]]))})))
