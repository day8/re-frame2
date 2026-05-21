(ns re-frame.story.ui.assertion-strip
  "Structured assertion-row treatment for inline canvas / workspace
  strips.

  The canvas + workspace cells previously `pr-str`-d raw assertion maps
  into one verbose row per assertion — hard-to-parse EDN. The `:test`
  mode pane (`re-frame.story.ui.test-mode.view`) already lifts the
  Storybook-inspired shape — status glyph + label + collapsible
  detail — but the inline strip never adopted it. This namespace lifts
  that shape into a small, dependency-free leaf the inline strip + the
  workspace cell share.

  ## What 'Storybook-inspired' means here

  Five patterns from the Storybook addon-tests interactions panel:

  1. **Structured row** — status glyph (✓/✗/⊘) + assertion label +
     one-line summary, not raw EDN.
  2. **Auto-collapse pass · auto-expand fail** — failed assertions
     surface their full detail; passed ones stay collapsed (the user
     drills only when something demands attention). A click toggles.
  3. **Token-coloured left border** — green / red / grey by status.
  4. **Truncate long values** — the inline summary clamps to one line
     with ellipsis; the expanded panel renders the full content.
  5. **Group by dispatching event** — assertions cluster under the
     `:event` slot they were dispatched from. Multiple `:rf.assert/*`
     dispatches against the same play-step land together visually.

  ## Why a shared namespace

  The canvas inline strip (`re-frame.story.ui.canvas/render-assertions`)
  and the workspace cell's trailing strip (`re-frame.story.ui.workspace`)
  both `pr-str`-ed raw records into a divs-of-one-line shape. Two call
  sites · one component = lift, don't duplicate. The `:test` mode pane
  keeps its richer per-row table (it has space for `:expected` /
  `:actual` / `:reason` columns); the inline strip is the compact
  version of the same shape.

  ## Pure where possible

  `truncate`, `summary-line`, and `group-by-event` are pure-data
  helpers — they project the record vector into the rendered shape.
  Reagent state lives in a per-mount `r/atom` for the expand/collapse
  set; the rendered hiccup is read off the projected data + the atom.

  CLJS-only. The inline strip never lands in production (Story is a
  dev-only artefact); the test-mode pane already lives at
  `tools/story/src/re_frame/story/ui/test_mode/view.cljs` and is
  CLJS-only for the same reason."
  (:require [clojure.string :as str]
            [reagent.core :as r]
            [re-frame.story.ui.test-mode.pure :as tm-pure]
            [re-frame.story.theme.typography :as typography :refer [mono-stack]]
            [re-frame.story.theme.colors :as colors]))

;; ---- pure helpers --------------------------------------------------------

(def ^:private truncate-len
  "Maximum character length for an inline summary line. Beyond this the
  summary truncates with an ellipsis; the user clicks to expand for the
  full value. Tuned against the densest realistic case (a
  `:rf.assert/sub-equals` carrying a moderate map shape) — readable in
  one line of a narrow canvas while still surfacing the assertion's
  shape at a glance."
  72)

(defn truncate
  "Clamp `s` to at most `n` chars, suffixing `\"…\"` when truncated.
  Pure data → string. Returns `\"\"` for nil input."
  ([s] (truncate s truncate-len))
  ([s n]
   (cond
     (nil? s)                   ""
     (not (string? s))          (truncate (str s) n)
     (<= (count s) n)           s
     :else                      (str (subs s 0 (max 0 (dec n))) "…"))))

(defn summary-line
  "Render the one-line inline summary for one assertion-row projection.
  Pure data → string. Empty string when no useful summary is present.

  For failures: surfaces `:reason`, else a compact `expected vs actual`.
  For passes: leaves blank — the label already names the assertion.
  For skips: surfaces `:reason` when present.

  Always truncated to `truncate-len` characters."
  [row]
  (let [{:keys [status detail]} row
        {:keys [reason expected actual]} detail]
    (truncate
      (case status
        :fail (cond
                (and (some? reason) (string? reason)) reason
                (some? reason)                        (pr-str reason)
                (or (some? expected) (some? actual))  (str "expected " (pr-str expected)
                                                           " · actual " (pr-str actual))
                :else                                 "")
        :skip (cond
                (string? reason) reason
                (some? reason)   (pr-str reason)
                :else            "skipped")
        :pass ""
        ""))))

(defn group-by-event
  "Pure: cluster assertion records by their dispatching `:event` slot.
  Returns an ordered vector of group maps:

      [{:event <event-vec-or-nil>
        :records [<record> ...]}
       ...]

  Records that carry no `:event` (e.g. `:rf.assert/*` dispatched outside
  a play step — phase-0 setup assertions, decorator-throws) cluster
  under a leading `nil`-event group. Order within each group preserves
  the original record order.

  Pure data → data; JVM-testable shape (though this namespace itself is
  CLJS — the pure helpers move to `pure.cljc` once a second consumer
  appears)."
  [records]
  (let [recs (vec (or records []))]
    (->> recs
         (reduce
           (fn [{:keys [order groups]} rec]
             (let [k (or (:event rec) ::no-event)
                   seen? (contains? groups k)]
               {:order  (if seen? order (conj order k))
                :groups (update groups k (fnil conj []) rec)}))
           {:order [] :groups {}})
         ((fn [{:keys [order groups]}]
            (mapv (fn [k]
                    {:event   (when (not= k ::no-event) k)
                     :records (get groups k)})
                  order))))))

;; ---- styling --------------------------------------------------------------
;;
;; The strip's style map. Sized small enough to read as a compact band
;; below the variant render; the per-row left-border carries the status
;; colour so the eye sweeps the colour band before reading any text.

(def ^:private styles
  {:wrap        {:margin-top    "8px"
                 :display       "flex"
                 :flex-direction "column"
                 :gap           "4px"}
   :group       {:display       "flex"
                 :flex-direction "column"
                 :gap           "2px"}
   :group-head  {:font-family   mono-stack
                 :font-size     (:micro typography/type-scale)
                 :color         (:text-tertiary colors/tokens)
                 :text-transform "uppercase"
                 :letter-spacing "0.04em"
                 :padding       "2px 4px"
                 :margin-top    "4px"}
   :row         {:display       "flex"
                 :align-items   "baseline"
                 :gap           "8px"
                 :padding       "4px 8px"
                 :border-left   "3px solid transparent"
                 :background    (:bg-2 colors/tokens)
                 :border-radius "0 3px 3px 0"
                 :font-family   mono-stack
                 :font-size     (:caption typography/type-scale)
                 :cursor        "pointer"
                 :user-select   "none"}
   :row-pass    {:border-left-color (:success colors/tokens)}
   :row-fail    {:border-left-color (:danger colors/tokens)
                 :background        (:danger-bg colors/tokens)}
   :row-skip    {:border-left-color (:text-tertiary colors/tokens)}
   :glyph       {:flex          "0 0 auto"
                 :width         "1em"
                 :text-align    "center"
                 :font-weight   "bold"}
   :glyph-pass  {:color         (:success colors/tokens)}
   :glyph-fail  {:color         (:danger colors/tokens)}
   :glyph-skip  {:color         (:text-tertiary colors/tokens)}
   :label       {:flex          "0 0 auto"
                 :color         (:text-primary colors/tokens)
                 :white-space   "nowrap"
                 :overflow      "hidden"
                 :text-overflow "ellipsis"
                 :max-width     "50%"}
   :summary     {:flex          "1 1 auto"
                 :color         (:text-secondary colors/tokens)
                 :white-space   "nowrap"
                 :overflow      "hidden"
                 :text-overflow "ellipsis"
                 :min-width     "0"}
   :summary-fail {:color        (:danger colors/tokens)}
   :tog         {:flex          "0 0 auto"
                 :color         (:text-tertiary colors/tokens)
                 :font-size     (:micro typography/type-scale)
                 :background    "transparent"
                 :border        "none"
                 :padding       "0"
                 :cursor        "pointer"}
   :detail      {:background    (:bg-2 colors/tokens)
                 :border-left   (str "3px solid " (:danger colors/tokens))
                 :padding       "6px 12px"
                 :margin        "0 0 0 0"
                 :font-family   mono-stack
                 :font-size     (:caption typography/type-scale)
                 :color         (:text-primary colors/tokens)
                 :white-space   "pre-wrap"
                 :border-radius "0 3px 3px 0"}
   :detail-key  {:color         (:info colors/tokens)
                 :margin-right  "4px"}
   :detail-line {:margin        "2px 0"}})

(def ^:private status->row-style
  {:pass (:row-pass styles)
   :fail (:row-fail styles)
   :skip (:row-skip styles)})

(def ^:private status->glyph-style
  {:pass (:glyph-pass styles)
   :fail (:glyph-fail styles)
   :skip (:glyph-skip styles)})

(def status-glyph
  "Status-glyph map. Public so tests can pin the shape."
  {:pass "✓"
   :fail "✗"
   :skip "⊘"})

;; ---- rendering ------------------------------------------------------------

(defn- row-detail
  "Render the expanded detail for one row. Surfaces `:reason` /
  `:expected` / `:actual` / `:event` / `:phase` / `:predicate` and the
  source-coord stamp. Mirrors the test-mode pane's `row-detail` but
  inline-sized (no headings; one line per key)."
  [detail]
  (let [{:keys [reason expected actual event phase predicate source]} detail]
    [:div {:style     (:detail styles)
           :data-test "story-canvas-assertion-detail"}
     (when (some? reason)
       [:div {:style (:detail-line styles)}
        [:span {:style (:detail-key styles)} "reason:"]
        (if (string? reason) reason (pr-str reason))])
     (when (or (some? expected) (false? expected))
       [:div {:style (:detail-line styles)}
        [:span {:style (:detail-key styles)} "expected:"]
        (pr-str expected)])
     (when (or (some? actual) (false? actual))
       [:div {:style (:detail-line styles)}
        [:span {:style (:detail-key styles)} "actual:"]
        (pr-str actual)])
     (when (some? event)
       [:div {:style (:detail-line styles)}
        [:span {:style (:detail-key styles)} "event:"]
        (pr-str event)])
     (when (some? phase)
       [:div {:style (:detail-line styles)}
        [:span {:style (:detail-key styles)} "phase:"]
        (pr-str phase)])
     (when (some? predicate)
       [:div {:style (:detail-line styles)}
        [:span {:style (:detail-key styles)} "predicate:"]
        (pr-str predicate)])
     (when (and (map? source) (or (:file source) (:line source)))
       [:div {:style (:detail-line styles)}
        [:span {:style (:detail-key styles)} "at:"]
        (str (:file source)
             (when (:line source) (str ":" (:line source))))])]))

(defn render-row
  "Render one assertion row. `row` is the `assertion-row` projection;
  `open?` controls whether the detail panel is expanded; `on-toggle` is
  fired on click. Pure shape: takes the projection + boolean, returns
  hiccup. CLJS-only (Reagent is `:require`d at the ns top), but the
  return value is a plain hiccup vector — JVM tests CAN walk it via
  `cljs.test`'s shape assertions running under CLJS.

  Per pattern #2 the click toggles the detail visibility for ANY row
  (pass / fail / skip); per pattern #2's default-arm, `open?` starts as
  `(= :fail status)` so failures land already-open and passes stay
  collapsed."
  [row open? on-toggle]
  (let [{:keys [status label]} row
        glyph       (get status-glyph status "·")
        row-style   (merge (:row styles)
                           (get status->row-style status))
        glyph-style (merge (:glyph styles)
                           (get status->glyph-style status))
        summary     (summary-line row)
        fail?       (= :fail status)]
    [:div {:data-test     "story-canvas-assertion-row"
           :data-status   (name status)
           :data-row-key  (:row-key row)}
     [:div {:style       row-style
            :on-click    (fn [_] (on-toggle (:row-key row)))
            :role        "button"
            :tabIndex    "0"
            :aria-expanded (if open? "true" "false")
            :aria-label  (str (name status) ": " label)}
      [:span {:style       glyph-style
              :data-test   "story-canvas-assertion-glyph"
              :aria-hidden "true"}
       glyph]
      [:span {:style     (:label styles)
              :data-test "story-canvas-assertion-label"}
       label]
      (when (seq summary)
        [:span {:style     (merge (:summary styles)
                                  (when fail? (:summary-fail styles)))
                :data-test "story-canvas-assertion-summary"
                :title     summary}
         summary])
      [:span {:style     (:tog styles)
              :data-test "story-canvas-assertion-tog"
              :aria-hidden "true"}
       (if open? "−" "+")]]
     (when open?
       (row-detail (:detail row)))]))

(defn- group-head
  "Render the group head label. `event` is the dispatching event vector
  (e.g. `[:counter/inc]`) or nil for assertions outside a play step."
  [event]
  (let [text (if (nil? event)
               "setup"
               (let [evt-id (first event)
                     args   (rest event)]
                 (str (pr-str evt-id)
                      (when (seq args)
                        (let [args-str (str/join " " (map pr-str args))]
                          (str " " (truncate args-str 40)))))))]
    [:div {:style     (:group-head styles)
           :data-test "story-canvas-assertion-group-head"
           :data-event (pr-str event)}
     text]))

(defn assertion-strip
  "Reagent component rendering a compact structured assertion strip for
  inline canvas / workspace use.

  `assertions` is the variant frame's accumulated `:rf.story/assertions`
  vector (the runtime's record shape — `{:assertion :passed?
  :payload :expected :actual :reason :event :phase :source ...}`).
  Each record is projected through `tm-pure/assertion-row` to derive
  status / label / row-key / detail.

  Renders nothing when `assertions` is empty. Otherwise renders one
  group per dispatching `:event`, with one row per assertion under the
  group. Failed rows auto-expand; passed/skipped rows stay collapsed.
  Clicking any row toggles its detail panel.

  Local-state: a per-mount `r/atom` carries the expanded-row-keys set.
  Failed rows are seeded into the set on first render so the user lands
  on already-disclosed failures."
  [_assertions]
  (let [expanded (r/atom nil)]            ;; nil until first render seeds
    (fn [assertions]
      (when (seq assertions)
        (let [rows    (mapv tm-pure/assertion-row assertions)
              ;; Seed the expanded set on first render: every failed
              ;; row's :row-key lands open so the user doesn't have to
              ;; click to disclose. After first render the atom is the
              ;; canonical state.
              _seed   (when (nil? @expanded)
                        (reset! expanded
                                (into #{}
                                      (comp (filter #(= :fail (:status %)))
                                            (map :row-key))
                                      rows)))
              ;; Re-projection: group the original records (carrying
              ;; :event), then for each record look up the matching row
              ;; by stable identity. This avoids re-doing the projection
              ;; twice while keeping group-by data-shape pure.
              groups  (group-by-event assertions)
              row-for (fn [rec]
                        (tm-pure/assertion-row rec))
              toggle  (fn [row-key]
                        (swap! expanded
                               (fn [s]
                                 (if (contains? s row-key)
                                   (disj s row-key)
                                   (conj s row-key)))))
              ;; Only render group heads when we have >1 group; a
              ;; single group reads cleanly without a header (the
              ;; canvas surface is space-constrained).
              multi-group? (> (count groups) 1)]
          [:div {:style     (:wrap styles)
                 :data-test "story-canvas-assertion-strip"}
           (for [[gi {:keys [event records]}] (map-indexed vector groups)]
             ^{:key (str "group-" gi)}
             [:div {:style     (:group styles)
                    :data-test "story-canvas-assertion-group"}
              (when multi-group?
                (group-head event))
              (for [[ri rec] (map-indexed vector records)]
                (let [row   (row-for rec)
                      rkey  (:row-key row)
                      open? (contains? @expanded rkey)]
                  ^{:key (str gi "/" ri "/" rkey)}
                  (render-row row open? toggle)))])])))))
