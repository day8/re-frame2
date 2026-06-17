(ns re-frame.story.ui.test-mode.visual-a11y-view
  "The `:test` mode pane's VISUAL + A11Y check-results section (rf2-ba86n.15,
  tools/story/spec/021-Story-UI-Test-And-Evidence.md §4 — visual and a11y
  checks). Unblocked by #2484, which wired the structural-a11y executor into
  the run path so the run path now produces `:rf.assert/a11y-structural`
  verdicts + browser-tier visual results in the unified
  `re-frame.story.result` run-result.

  ## What this presents (spec/021 §4)

  Visual / a11y checks are runner-tiered assertions on the SAME run-result
  model (`re-frame.story.play.browser` — \"a finding is an assertion record
  like any other; no new run-result slot\"). The generic assertions table
  (`view/rows-section`) already lists every record, but it renders a
  browser-tier finding as a raw `:actual` EDN blob. This dedicated component
  presents those findings READABLY:

  - **structural a11y violations** — a `{:finding :locus}` list (the issue
    in words + the offending hiccup tag), pass/fail per the unified
    `:status`;
  - **axe-style a11y violations** — the `{:id :impact :help}` axe surface,
    likewise readable;
  - **visual checks** — the captured screenshot/snapshot identity (the
    reused `content-hash` regression key) and any baseline it diffed
    against;
  - **honest `:cannot-run`** — a browser-tier check the headless / hiccup
    runner could NOT attempt shows `:cannot-run` with its reason (reusing
    the warning-tinted `:cannot-run` vocabulary), never a false pass or a
    silent blank (spec/017 §`:cannot-run`).

  ## Reuse, not re-derivation

  The rows come from `re-frame.story.ui.test-mode.pure/browser-result-rows`,
  which READS the browser-tier records off the unified run-result's
  `:assertions` slot — the SAME source Test mode + docs already project
  (`re-frame.story.assertions/browser-assertion-ids` selects them). No
  parallel result vocabulary, no second accumulator.

  ## Elision

  CLJS-only. Reached only through `view/test-view`, which the shell mounts
  behind the `config/enabled?` gate, so production builds never invoke it —
  closure DCEs the lot."
  (:require [re-frame.story.ui.test-mode.pure        :as pure]
            [re-frame.story.ui.test-mode.state       :as state]
            [re-frame.story.theme.status :as status]
            [re-frame.story.theme.typography :as typography :refer [mono-stack]]
            [re-frame.story.theme.colors :as colors]))

;; ---- styles (theme tokens only — no shell restyle) -----------------------

(def ^:private styles
  {:section     {:margin-top "16px"}
   :section-h   {:font-weight    "bold"
                 :color          (:text-secondary colors/tokens)
                 :text-transform "uppercase"
                 :font-size      (:micro typography/type-scale)
                 :letter-spacing "0.5px"
                 :margin-bottom  "8px"
                 :border-bottom  (str "1px solid " (:border-default colors/tokens))
                 :padding-bottom "4px"}
   :card        {:border        (str "1px solid " (:border-default colors/tokens))
                 :border-radius "4px"
                 :margin-bottom "6px"
                 :background    (:bg-2 colors/tokens)
                 :font-family   mono-stack
                 :font-size     (:caption typography/type-scale)}
   :card-head   {:display       "flex"
                 :align-items   "center"
                 :gap           "8px"
                 :padding       "6px 10px"}
   :kind-label  {:color       (:text-primary colors/tokens)
                 :font-weight "bold"}
   :reason      {:color       (:text-secondary colors/tokens)
                 :font-size   (:micro typography/type-scale)
                 :margin-left "auto"
                 :text-align  "right"}
   :findings    {:list-style  "none"
                 :margin      "0"
                 :padding     "0 10px 8px 10px"}
   :finding-row {:padding       "4px 0"
                 :border-top    (str "1px solid " (:border-default colors/tokens))
                 :display       "flex"
                 :gap           "8px"
                 :align-items   "baseline"}
   :finding-txt {:color (:text-primary colors/tokens)}
   :locus       {:color         (:info colors/tokens)
                 :background    (:bg-3 colors/tokens)
                 :padding       "1px 6px"
                 :border-radius "3px"
                 :font-size     (:micro typography/type-scale)
                 :white-space   "nowrap"}
   :impact      {:color          (:warning colors/tokens)
                 :text-transform "uppercase"
                 :font-size      (:micro typography/type-scale)
                 :letter-spacing "0.4px"}
   :detail      {:color     (:text-tertiary colors/tokens)
                 :font-size (:micro typography/type-scale)}
   :all-clear   {:color      (:success colors/tokens)
                 :padding    "4px 10px 8px 10px"
                 :font-style "italic"}
   :snap-box    {:padding "4px 10px 8px 10px"}
   :snap-key    {:color          (:text-tertiary colors/tokens)
                 :text-transform "uppercase"
                 :font-size      (:micro typography/type-scale)
                 :letter-spacing "0.4px"
                 :margin-right   "6px"}
   :snap-val    {:color (:text-primary colors/tokens)}
   :cannot-box  {:padding    "4px 10px 8px 10px"
                 :color      (:warning colors/tokens)
                 :font-style "italic"}})

;; Deliberate glyph override: this pane marks `:error` with a HEAVY cross
;; (✖) rather than the shared `theme.status` descriptor's `!`, so a browser-
;; tier executor error reads as a hard failure-of-the-check (not a soft
;; warning) inline in the result row. Colour + the other three glyphs derive
;; from the shared status vocabulary (rf2-8fr3yd dedup).
(def ^:private error-glyph "✖")

(def ^:private kind-title
  {:visual         "Visual snapshot"
   :a11y           "Accessibility (axe)"
   :a11y-structural "Accessibility (structural)"
   :browser        "Browser check"})

(defn- pill
  "The status pill for one browser-tier row — pass / fail / error, plus the
  distinct THIRD `:cannot-run` state (spec/018 §12.6). Colour + glyph derive
  from the shared `theme.status` descriptor (the single status vocabulary),
  with a deliberate heavy-cross `:error` glyph override (see `error-glyph`).
  Unmapped statuses degrade to the descriptor's neutral fallback."
  [status]
  (let [{:keys [bg fg glyph]} (status/descriptor status)
        glyph (if (= status :error) error-glyph glyph)]
    [:span {:style       {:padding        "2px 8px"
                          :border-radius  "8px"
                          :background     bg
                          :color          fg
                          :font-size      (:micro typography/type-scale)
                          :font-weight    "bold"
                          :text-transform "uppercase"
                          :letter-spacing "0.4px"}
            :data-test   "story-va-status-pill"
            :data-status (name status)}
     (str glyph " " (name status))]))

(defn- findings-list
  "Render an a11y findings list (structural or axe) readably — the finding
  in words + its locus (the SOURCE LINK: the offending element's CSS selector
  for axe `:browser`-tier findings, or the hiccup tag for structural ones),
  the executor's detail line, and (axe) the impact level. Never a raw EDN
  blob.

  Per rf2-ffu8t the axe tier now carries a real `:selector` (recovered from
  the violation's `:nodes` → `:target`); when present the locus is tagged
  `data-selector` so the result UI exposes the source link the spec/021 §4
  MUST requires. A structural finding has no selector (the `:hiccup` tier
  walks an in-memory tree, not a DOM — see `pure/structural-a11y-findings`),
  so it surfaces only its hiccup-tag locus, honestly."
  [findings]
  [:ul {:style (:findings styles) :data-test "story-va-findings"}
   (for [[i {:keys [finding locus detail impact rule selector]}] (map-indexed vector findings)]
     ^{:key (str rule "#" i)}
     [:li {:style       (:finding-row styles)
           :data-test   "story-va-finding"
           :data-rule   (str rule)}
      (when locus
        [:span (cond-> {:style     (:locus styles)
                        :data-test "story-va-locus"}
                 selector (assoc :data-selector selector
                                 :title         (str "source: " selector)))
         locus])
      [:span {:style (:finding-txt styles)} finding]
      (when impact
        [:span {:style (:impact styles)} impact])
      (when (and detail (not= detail finding))
        [:span {:style (:detail styles)} detail])])])

(defn- a11y-card
  "One a11y check card (structural or axe). On `:cannot-run` shows the
  refusal reason; on a clean pass shows an all-clear line; on a fail shows
  the readable findings list."
  [{:keys [kind status reason findings]}]
  [:div {:style       (:card styles)
         :data-test   "story-va-card"
         :data-kind   (name kind)
         :data-status (name status)}
   [:div {:style (:card-head styles)}
    [pill status]
    [:span {:style (:kind-label styles)} (get kind-title kind "a11y check")]
    [:span {:style (:reason styles)} reason]]
   (cond
     (= status :cannot-run)
     [:div {:style (:cannot-box styles) :data-test "story-va-cannot-run"}
      "cannot run on this runner — this browser-tier check was not attempted"]

     (seq findings)
     (findings-list findings)

     :else
     [:div {:style (:all-clear styles) :data-test "story-va-all-clear"}
      "no violations"])])

(defn- visual-card
  "One visual-snapshot check card. On `:cannot-run` (the headless / no-pixel
  runner) shows the refusal honestly; otherwise presents the captured
  screenshot/snapshot identity (the reused `content-hash` regression key)
  and any baseline it diffed against. A real pixel-diff image lands with the
  `:pixels` browser runner; the identity is the regression artifact today."
  [{:keys [status reason snapshot baseline]}]
  [:div {:style       (:card styles)
         :data-test   "story-va-card"
         :data-kind   "visual"
         :data-status (name status)}
   [:div {:style (:card-head styles)}
    [pill status]
    [:span {:style (:kind-label styles)} (get kind-title :visual)]
    [:span {:style (:reason styles)} reason]]
   (if (= status :cannot-run)
     [:div {:style (:cannot-box styles) :data-test "story-va-cannot-run"}
      "cannot run on this runner — a screenshot/pixel diff needs a real browser (:pixels)"]
     [:div {:style (:snap-box styles) :data-test "story-va-snapshot"}
      [:div
       [:span {:style (:snap-key styles)} "snapshot"]
       [:span {:style (:snap-val styles)} (if (some? snapshot) (pr-str snapshot) "—")]]
      (when (some? baseline)
        [:div
         [:span {:style (:snap-key styles)} "baseline"]
         [:span {:style (:snap-val styles)} (pr-str baseline)]])])])

(defn visual-a11y-section
  "The visual + a11y check-results section for `variant-id` (spec/021 §4).
  Reads the browser-tier oracle records off the unified run-result's
  `:assertions` slot via `pure/browser-result-rows` and presents each
  readably (structural-a11y violations as a `{:finding :locus}` list, axe
  violations likewise, visual snapshot identity, honest `:cannot-run`).

  Renders NOTHING when the run recorded no browser-tier checks — the common
  headless case (an honest empty state, never a fabricated card)."
  [variant-id]
  (let [slot   (get @state/results-atom variant-id)
        result (:result slot)
        rows   (some-> result pure/browser-result-rows)]
    (when (seq rows)
      [:div {:style     (:section styles)
             :data-test "story-test-visual-a11y-section"}
       [:div {:style (:section-h styles)} "Visual & accessibility"]
       (for [[i {:keys [kind] :as row}] (map-indexed vector rows)]
         ^{:key (str kind "#" i)}
         (if (= kind :visual)
           [visual-card row]
           [a11y-card row]))])))
