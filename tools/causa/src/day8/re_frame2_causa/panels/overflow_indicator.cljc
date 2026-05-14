(ns day8.re-frame2-causa.panels.overflow-indicator
  "Shared overflow-indicator row + capped-list builder for long-list
  panels (rf2-1k5r1).

  ## Why this lives in its own ns

  Every long-list panel (trace, issues-ribbon, mcp-server, performance,
  event-detail, subscriptions, effects, flows) caps row rendering at
  the 200-row budget pinned in `tools/causa/spec/007-UX-IA.md`
  §Performance budget. When the cap drops rows the view must surface
  a uniform 'N more hidden — narrow the filter to see more' affordance
  so the user knows their data is bigger than what's on screen.

  The indicator is pure hiccup; a `.cljc` ns lets the JVM test target
  assert against the rendered shape without a CLJS runtime.

  ## Shape

  The indicator is a `[:li]` so it slots into the same `[:ul]` the
  capped rows live in — keyboard / scroll behaviour stays consistent.
  The user-visible text matches the existing
  `machine_inspector.cljs` ribbon pattern (`N of M`) which already
  shipped in v1 for the transition-history surface.

  ## capped-list builder

  Eight panels share the same cap-list-then-append-overflow shape.
  `capped-list` folds the boilerplate into one fn so the per-panel
  view stays focused on the row hiccup."
  (:require [day8.re-frame2-causa.panels.common-helpers :as common]
            [day8.re-frame2-causa.theme.tokens
             :refer [tokens sans-stack mono-stack]]))

(defn overflow-row
  "Render the 'N rows hidden' affordance when the row-cap dropped at
  least one row. Returns nil when `over-cap?` is falsy so the caller
  can `(when over-cap? ...)` or unconditionally splat with no extra
  DOM.

  `panel-id` is a stable string used in the `data-testid` so per-panel
  tests can assert the indicator landed. `hidden-count` is the number
  of rows the cap dropped.

  Per `tools/causa/spec/007-UX-IA.md` §Performance budget the cap is
  a hard rendering ceiling; the indicator is the contract surface that
  tells the user their data is bigger than what's on screen."
  [{:keys [panel-id over-cap? hidden-count]}]
  (when over-cap?
    [:li {:data-testid (str "rf-causa-" panel-id "-overflow-indicator")
          :style       {:list-style    "none"
                        :padding       "10px 16px"
                        :border-top    (str "1px dashed "
                                            (:border-default tokens))
                        :background    (:bg-3 tokens)
                        :color         (:text-tertiary tokens)
                        :font-family   sans-stack
                        :font-size     "11px"
                        :line-height   1.4
                        :text-align    "center"
                        :font-style    "italic"}}
     [:span {:style {:font-family mono-stack
                     :color       (:text-secondary tokens)
                     :margin-right "4px"}}
      (str "+" hidden-count)]
     " row"
     (when (not= 1 hidden-count) "s")
     " hidden — narrow the filter or selection to see more."]))

(defn capped-list
  "Build a `[:ul ...]` of `rows` capped at the 200-row panel budget,
  appending the shared overflow indicator when the cap drops rows.

  `opts` is `{:panel-id <string> :ul-attrs <map> :row-fn <fn>}`.

    :panel-id — stable string used in both the overflow indicator's
                testid (`rf-causa-<panel-id>-overflow-indicator`) and
                the caller's own per-row testid scheme. Caller-owned.

    :ul-attrs — the attribute map for the `[:ul]` wrapper — testid,
                style, etc. Passed through verbatim so per-panel
                styling (background colour, padding) stays in the
                caller.

    :row-fn   — `(fn [row] hiccup)` rendering one row. Hiccup-only;
                meta-keys (^{:key …}) live in the caller's row-fn so
                each panel keeps its own keying convention.

  Returns a hiccup vector. Pure fn; JVM-runnable.

  Cap source-of-truth lives in `common-helpers/panel-row-cap` (200,
  per spec/007 §Performance budget). The overflow indicator is the
  `[:li]` rendered from `overflow-row` and is `conj`-ed onto the
  `[:ul]` only when the cap drops at least one row."
  [rows {:keys [panel-id ul-attrs row-fn]}]
  (let [[capped over-cap? hidden] (common/cap-rows rows)]
    (cond-> (into [:ul ul-attrs] (map row-fn capped))
      over-cap?
      (conj (overflow-row {:panel-id     panel-id
                           :over-cap?    over-cap?
                           :hidden-count hidden})))))
