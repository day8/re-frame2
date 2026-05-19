(ns day8.re-frame2-causa.panels.views-view
  "Root view composition for the Views panel (rf2-21ob3).

  Pure hiccup per the Causa convention; consumed by `views/Panel`
  inside the `:rf/causa` frame-provider in `shell.cljs`. Per spec
  §Renderer the panel uses the cljs-devtools-shaped renderers from
  `theme/data_inspector.cljs` (rf2-x9fzk) — `inspect-inline` for
  one-line list cells; `inspect` for the click-expand hero inside
  the inline drilldown.

  ## What ships v1

  - Three-group rendering (mounted / re-rendered / unmounted) per
    spec §Three-group layout.
  - Re-rendered group two-column 'Rerendered because' layout (spec
    §R3-D) — developer-framed: the column answers \"why did this
    component re-render?\" rather than describing the substrate's
    invalidation mechanism.
  - Grid-explosion clustering (spec §Grid-explosion clustering) with
    `[Expand cluster ▾]` affordance for ≥ 50-render clusters.
  - Per-row inline expansion (spec §R3-F / spec §Per-component
    drilldown) — single-column block under the row with the raw
    `:rf/epoch-record` `:renders` entry rendered via `inspect`.

  ## What's stubbed v1

  - Per-render props-diff (spec §Per-component drilldown → Headline
    content) — the runtime does not yet capture per-render
    `:props-before` / `:props-after`. The drilldown surfaces the
    raw render entry via `inspect` until that capture lands.
  - Render-tracker `:owning-frame` filter (spec §Isolation invariant
    I3) — render entries do not carry the tag yet (P17 pending);
    the spine's frame selection still propagates so the panel is
    forward-compatible.
  - Per-component sub-row sub-status decoration (spec §Sub-status
    legibility) — uses a single rendering for all statuses; the
    detailed taxonomy comes back online once `sub-cache` per-frame
    metadata threads through the new spine.
  - React Fiber metadata (spec §Per-component drilldown → React
    Fiber block) — out of scope v1; collapse-by-default placeholder."
  (:require [re-frame.core :as rf]
            [day8.re-frame2-causa.panels.views-helpers :as h]
            ;; rf2-x9fzk landed on origin/main during this bead's worktree
            ;; lifetime. The data inspector replaces v1's pr-str
            ;; placeholders for sub return values + cluster instance
            ;; payloads. Per spec §Renderer the panel uses
            ;; `inspect-inline` for the one-line list cells and `inspect`
            ;; for the click-expand hero in the inline drilldown.
            [day8.re-frame2-causa.panels.views-sub-diff :as sub-diff]
            [day8.re-frame2-causa.theme.data-inspector :as inspector]
            ;; rf2-mxkq7 — Group-by-tree toggle. Reads the host
            ;; application's view-hierarchy via `views/fiber_walker.cljs`
            ;; (Fiber parent/child slots) and renders parent ⊃ children
            ;; indentation. Production DCE via `goog.DEBUG`; the
            ;; renderer + walker are dev-only (gated by
            ;; `interop/debug-enabled?` at every entry point).
            [day8.re-frame2-causa.views.group-by-tree :as gbt]
            ;; rf2-i39w2 Phase 3 — hiccup-diff micro-engine + renderer.
            ;; Lit up in the Re-rendered row drilldown when the render
            ;; entry carries `:hiccup-before` + `:hiccup-after`. The
            ;; render-tracker capture for these slots is a follow-on
            ;; (P17 wave); the drilldown is forward-compatible — when
            ;; the slots appear, the diff renders without further code.
            [day8.re-frame2-causa.diff.hiccup :as hd]
            [day8.re-frame2-causa.diff.hiccup-render :as hd-render]
            [day8.re-frame2-causa.theme.tokens
             :as t
             :refer [tokens mono-stack sans-stack]]))

;; ---- styling primitives -------------------------------------------------

(def ^:private group-section-style
  {:padding "12px 16px 4px 16px"
   :font-size "11px"
   :font-weight 600
   :text-transform "uppercase"
   :letter-spacing "0.05em"
   :color (:text-tertiary tokens)
   :border-bottom (str "1px solid " (:border-subtle tokens))})

(def ^:private row-style
  {:padding "8px 16px"
   :border-bottom (str "1px solid " (:border-subtle tokens))
   :font-family sans-stack
   :font-size "13px"
   :color (:text-primary tokens)
   :display "flex"
   :flex-direction "column"
   :gap "4px"})

(def ^:private row-clickable-style
  (assoc row-style :cursor "pointer"))

(def ^:private mono-style
  {:font-family mono-stack
   :font-size "12px"
   :color (:text-secondary tokens)})

;; ---- ✱ / ≈ / · marker chrome (rf2-87lkf — Delta 2 polish ·
;;                               rf2-r2s2l — cache-miss-equal) -------------
;;
;; The three markers in the Re-rendered group's "Rerendered because" column
;; answer the developer's first question — "for each sub the view
;; consumed, what happened to it this cascade?" The visual hierarchy:
;;
;;   `✱` `:cache-miss-trigger` — value changed → likely cause of the
;;        re-render (amber, bold).
;;   `≈` `:cache-miss-equal`   — sub recomputed but new value structurally
;;        equals previous (React skipped re-render). Muted grey.
;;   `·` `:cache-hit`          — sub was consumed but did NOT recompute
;;        (cache hit). Muted grey.
;;
;; The `≈` shape distinguishes recomputed-but-equal from cache-hit at a
;; glance without colliding with the spec's reserved `○` (cached-no-
;; watcher) / `◐` (re-running) glyph vocabulary. Inline-block + fixed
;; width keeps the marker column scannable when dense.

(def ^:private trigger-glyph-style
  {:color         (:yellow tokens)       ; amber → "this changed; look here"
   :font-weight   700
   :font-size     "14px"                 ; slightly larger than 12px body so the glyph reads
   :line-height   1                      ; tight so vertical rhythm matches `·`
   :display       "inline-block"
   :width         "12px"
   :text-align    "center"
   :margin-right  "6px"})

(def ^:private equal-glyph-style
  ;; Same visual weight as the cache-hit `·` (muted) — both are
  ;; informational, neither is the cause of the re-render. The
  ;; shape distinction is what carries the signal.
  {:color         (:text-tertiary tokens)
   :font-weight   400
   :font-size     "14px"
   :line-height   1
   :display       "inline-block"
   :width         "12px"
   :text-align    "center"
   :margin-right  "6px"})

(def ^:private non-trigger-glyph-style
  {:color         (:text-tertiary tokens)  ; muted-grey — recedes from `✱`
   :font-weight   400
   :font-size     "14px"
   :line-height   1
   :display       "inline-block"
   :width         "12px"
   :text-align    "center"
   :margin-right  "6px"})

(def ^:private trigger-glyph-tooltip
  "Hover text for `✱` — explains the marker means \"value changed since last
  cascade\" (the substrate term \"invalidated\" is hidden; the developer-
  facing framing matches the section header \"Rerendered because\")."
  "Value changed since last cascade — this sub's recompute returned a value that differs from the previous one. (Likely cause of the re-render.)")

(def ^:private equal-glyph-tooltip
  "Hover text for `≈` — explains the marker means \"sub recomputed but
  value unchanged\". React skipped the re-render of any view subscribed
  only to this sub, so the `≈` is informational — not a cause. Per
  spec §0ter.1 R3 — cache-miss-equal completes the three-status
  taxonomy alongside `✱` and `·`."
  "Sub recomputed, value unchanged — the substrate re-ran this sub during the cascade but the new value structurally equals the previous one. (React skipped re-render of any view reading only this sub.)")

(def ^:private non-trigger-glyph-tooltip
  "Hover text for `·` — explains the marker means \"sub did NOT
  recompute this cascade\" (cache hit). The view read the cached
  value; no work."
  "Cache hit — the sub did not recompute this cascade; the view read the cached value. (Not a cause of the re-render.)")

(defn- sub-status-chrome
  "Per spec §0ter.1 R3 — three-status decoration. Returns the glyph
  string + style map + tooltip + data-marker attribute value for a
  row's classified status. Encapsulated so the test ns can verify
  every status renders its respective chrome (`rf2-r2s2l`)."
  [status]
  (case status
    :cache-miss-trigger
    {:glyph "✱" :style trigger-glyph-style
     :tooltip trigger-glyph-tooltip
     :data-marker "trigger"
     :aria-label "value changed"}
    :cache-miss-equal
    {:glyph "≈" :style equal-glyph-style
     :tooltip equal-glyph-tooltip
     :data-marker "cache-miss-equal"
     :aria-label "value unchanged"}
    :cache-hit
    {:glyph "·" :style non-trigger-glyph-style
     :tooltip non-trigger-glyph-tooltip
     :data-marker "cache-hit"
     :aria-label "cache hit"}))

;; ---- small helpers ------------------------------------------------------

(defn- inspect-inline
  "Per spec §Renderer — one-line tail-elided rendering for compact
  list cells. Delegates to the rf2-x9fzk data inspector's
  `inspect-inline` so sub return values render with the same
  coloured + sentinel-aware chrome the Event tab uses."
  [v]
  (inspector/inspect-inline v))

(defn- format-ms
  [ms]
  (cond
    (nil? ms)       "—"
    (< ms 1)        (str (.toFixed (* ms 1000) 0) "µs")
    (< ms 10)       (str (.toFixed ms 2) "ms")
    :else           (str (.toFixed ms 1) "ms")))

(defn- format-sub-id
  [sub-id]
  (cond
    (= ::h/parent-forced sub-id) "<parent re-rendered>"
    (keyword? sub-id) (str sub-id)
    :else (pr-str sub-id)))

(defn- row-key
  "Stable React key for a row. Cluster rows use the (view-id,
  triggered-by) tuple printed; singles use the full render-key."
  [item]
  (case (:kind item)
    :single  (pr-str (:render-key (:render item)))
    :cluster (str "cluster:" (pr-str [(:view-id item) (:triggered-by item)]))))

;; ---- 'Rerendered because' list (Re-rendered) ---------------------------
;;
;; Per spec §Per-row content (Re-rendered) + spec §Sub-status legibility
;; the right-column lists every sub the component consumed this cascade
;; with a per-sub status marker (rf2-r2s2l three-status taxonomy):
;;
;;   `✱` (amber, bold) — `:cache-miss-trigger`. Sub's recomputed value
;;        DIFFERS from the previous cascade's value (likely cause of
;;        the re-render).
;;   `≈` (muted grey)  — `:cache-miss-equal`. Sub recomputed but the
;;        new value structurally equals the previous (React skipped
;;        re-render of any view reading only this sub).
;;   `·` (muted grey)  — `:cache-hit`. Sub was consumed but did NOT
;;        recompute this cascade; the view read the cached value.
;;
;; Classifier lives in `views_helpers.cljc` (`sub-status`); per-status
;; chrome lives in `sub-status-chrome` (above).
;;
;; The kw `:invalidated-by` and the testid `rf-causa-views-invalidated-by`
;; are stable internal-data + test-contract surfaces (rf2-87lkf bead
;; out-of-scope rename); the UI text developer-frames as "Rerendered
;; because" instead.

(defn- rerendered-because-list
  [invalidated-by]
  [:div {:data-testid "rf-causa-views-invalidated-by"
         :role "list"
         :aria-label "Rerendered because"
         :style {:display "flex"
                 :flex-direction "column"
                 :gap "2px"}}
   (for [[i row] (map-indexed vector invalidated-by)
         :let [status (h/sub-status row)
               chrome (sub-status-chrome status)]]
     ^{:key i}
     [:div {:role "listitem"
            :style mono-style}
      [:span {:style       (:style chrome)
              ;; Hover tooltip per rf2-87lkf — Delta 2 + rf2-r2s2l
              ;; cache-miss-equal. `title` ships the explanation on
              ;; every browser without a dispatch round-trip; ARIA
              ;; labels mirror it for screen readers.
              :title       (:tooltip chrome)
              :aria-label  (:aria-label chrome)
              :data-marker (:data-marker chrome)}
       (:glyph chrome)]
      [:span (format-sub-id (:sub-id row))]
      (when (:clustered? row)
        [:span {:style {:margin-left "6px"
                        :color (:text-tertiary tokens)
                        :font-size "11px"}}
         "(args vary per instance)"])])])

;; ---- per-row body builders ---------------------------------------------

(defn- relevant-sub-diff-records
  "Filter the cascade-wide sub-diff records to subs in this row's
  `invalidated-by` list. Sub-id match — args (`:query-v`) might
  differ across consumers but the most common case is identical args
  per row. Phase 2 v1 ships the cascade-wide records pre-filtered to
  the row's invalidating subs; per-row sub-attribution lands when the
  render-tracker emits `:owning-frame` (P17 wave)."
  [records invalidated-by]
  (let [wanted (set (keep :sub-id invalidated-by))]
    (filterv (fn [rec] (contains? wanted (:sub-id rec))) records)))

(defn- hiccup-diff-block
  "rf2-i39w2 Phase 3 — render the view-hiccup diff drilldown when the
  render entry carries `:hiccup-before` + `:hiccup-after`. Reads the
  `:rf.causa/diff-opts` sub for the opt-in `:highlight-fn-ref-changes?`
  toggle. The render-tracker capture for these slots is a follow-on
  (P17 wave); until then this block returns nil and the drilldown
  shows only the raw render entry. Forward-compatible — when the
  slots appear, the diff lights up without further code."
  [r]
  (when (and (contains? r :hiccup-before)
             (contains? r :hiccup-after))
    (let [opts  @(rf/subscribe [:rf.causa/diff-opts])
          before (:hiccup-before r)
          after  (:hiccup-after r)
          node  (hd/diff-hiccup-node before after opts)]
      [:div {:data-testid "rf-causa-views-row-hiccup-diff"
             :style {:margin-top "4px"
                     :padding    "6px 8px"
                     :background (:bg-2 tokens)
                     :border-left (str "3px solid " (:accent-violet tokens))
                     :border-radius "2px"
                     :font-family mono-stack
                     :font-size "12px"}}
       [:div {:style {:color (:text-tertiary tokens)
                      :font-family sans-stack
                      :font-size "11px"
                      :margin-bottom "4px"}}
        (if (hd/changed? node)
          "View hiccup diff"
          "Hiccup unchanged (wasted re-render — view returned identical hiccup)")]
       (when (hd/changed? node)
         (hd-render/render-root
           node
           (str "views/" (pr-str (:render-key r)))))])))

(defn- expanded-block
  "Inline expansion content per spec §Per-component drilldown — single-
  column block placed BELOW the row. v1 ships the structural
  scaffolding (props-diff section header, subs-consumed list,
  reason); the props-diff and React-fiber slots stay placeholder
  until `theme/data_inspector.cljc` lands.

  ## Sub-output diff (rf2-xjhhp Phase 2)

  Below the structural scaffolding we mount the sub-output structural
  diff for the row's invalidating subs. Records come from
  `:rf.causa/views-sub-diff-for-focused-event`; rendering goes
  through the Phase 1 sections-per-cluster engine
  (`day8.re-frame2-causa.diff.render/render-sections`) via the
  thin `views-sub-diff/drilldown` wrapper.

  ## View hiccup diff (rf2-i39w2 Phase 3)

  `hiccup-diff-block` renders below the raw entry when the render-
  tracker captures `:hiccup-before` + `:hiccup-after` on the entry.
  Forward-compatible — the block returns nil until that capture
  lands."
  [item]
  (let [r (:render item)
        invalidated-by (:invalidated-by item)
        sub-diff-data @(rf/subscribe [:rf.causa/views-sub-diff-for-focused-event])
        sub-records   (relevant-sub-diff-records (:records sub-diff-data)
                                                 invalidated-by)]
    [:div {:data-testid "rf-causa-views-row-expanded"
           :style {:padding "8px 16px 12px 32px"
                   :background (:bg-1 tokens)
                   :border-bottom (str "1px solid " (:border-subtle tokens))
                   :font-family sans-stack
                   :font-size "12px"
                   :color (:text-secondary tokens)
                   :display "flex"
                   :flex-direction "column"
                   :gap "8px"}}
     [:div [:strong "Render entry (raw)"]
      ;; Per spec §Per-component drilldown §Headline content the
      ;; expansion's headline is the props diff — but per-render props
      ;; capture is a render-tracker extension that hasn't landed yet
      ;; (P17 wave per `ai/findings/2026-05-17-causa-consolidated-design.md`
      ;; §14). Until then we expose the raw `:rf/epoch-record` `:renders`
      ;; entry for the row via the rf2-x9fzk inspector so the user can
      ;; click through to the render-key, triggered-by, elapsed-ms.
      ;; Props-diff renders as a structured tree the moment the runtime
      ;; emits per-render `:props-before` / `:props-after` slots.
      (inspector/inspect r (str "views/" (pr-str (:render-key r))))
      (hiccup-diff-block r)]
     (when (seq invalidated-by)
       [:div [:strong "Subs consumed"]
        (rerendered-because-list invalidated-by)])
     ;; rf2-xjhhp Phase 2 — sub-output structural diff for the row's
     ;; invalidating subs. The renderer ships an empty-state chip when
     ;; no records remain after filtering (e.g. for parent-forced
     ;; re-renders where no sub invalidated the row).
     [:div [:strong "Sub-output diff"]
      (sub-diff/drilldown sub-records)]
     [:div [:strong "Reason"]
      [:p {:style {:margin "4px 0"}}
       (cond
         (:triggered-by r)
         (str "Re-render triggered by " (format-sub-id (:triggered-by r)))
         :else
         "Parent re-rendered → forced child re-render.")]]
     [:div [:strong "Render timing"]
      [:p {:style {:margin "4px 0"}}
       (str "elapsed " (format-ms (:elapsed-ms r))
            " · (mount/commit phase data ships when React Profiler available)")]]]))

(defn- right-click-filter-handler
  "Per spec §0ter.1 R3-E + bead rf2-r2s2l — right-click any Views row
  applies the panel-local `:rf.causa/views-set-component-filter` to
  the row's view-id. Mirrors `shell.cljs`'s event-row pattern (direct
  dispatch on context-menu; no separate context-menu UI surface).
  `preventDefault` suppresses the browser's native menu so the
  developer's right-click lands on Causa's affordance, not the host
  page's menu. The dispatch resolves to `:rf/causa` via React-context
  (the panel mounts inside Causa's frame-provider — the same way
  every other in-panel `rf/dispatch` here resolves)."
  [view-id]
  (fn [^js e]
    (when view-id
      (.preventDefault e)
      (rf/dispatch [:rf.causa/views-set-component-filter view-id]))))

(defn- single-row
  "One render row in either Mounted, Re-rendered, or Unmounted. The
  two-column layout for Re-rendered (spec §Per-row content (Re-
  rendered)) renders the `invalidated-by` list in the right column."
  [item group expanded?]
  (let [r          (:render item)
        view-id    (h/render-key->view-id (:render-key r))
        invalidated-by (:invalidated-by item)
        struck?    (= group :unmounted)]
    [:div {:data-testid (str "rf-causa-views-row-" (name group))
           :on-click    #(rf/dispatch [:rf.causa/views-toggle-row
                                       (pr-str (:render-key r))])
           :on-context-menu (right-click-filter-handler view-id)
           :title       (when view-id
                          (str "Right-click → filter Views panel to "
                               "<" (h/format-view-id view-id) ">"))
           :style       (cond-> row-clickable-style
                          struck? (assoc :text-decoration "line-through"
                                         :color (:text-tertiary tokens)))}
     [:div {:style {:display "flex"
                    :flex-direction "row"
                    :gap "12px"
                    :align-items "flex-start"}}
      [:span {:style {:color (:accent-violet tokens) :font-weight 700}}
       (get h/group-glyph group "●")]
      [:div {:style {:flex (if (= :rendered group) "0 0 40%" "1 1 auto")
                     :min-width 0}}
       [:div {:style {:display "flex" :align-items "center" :gap "8px"}}
        [:span {:style {:font-weight 600}}
         (str "<" (h/format-view-id view-id) ">")]
        [:span {:style {:color (:text-tertiary tokens) :font-size "11px"}}
         (format-ms (:elapsed-ms r))]
        [:span {:style {:color (:text-tertiary tokens) :font-size "11px"}}
         (str "▾")]]]
      (when (= :rendered group)
        [:div {:style {:flex "1 1 60%" :min-width 0
                       :border-left (str "1px solid " (:border-subtle tokens))
                       :padding-left "12px"}}
         ;; rf2-87lkf Delta 1 — developer-framed section header. Same
         ;; data as "Invalidated by"; answers the developer's
         ;; question ("why did this view re-render?") rather than
         ;; the substrate's view of the world ("which subs
         ;; invalidated"). Underlying kw + testid unchanged (stable).
         [:div {:style {:color (:text-tertiary tokens)
                        :font-size "11px"
                        :margin-bottom "4px"}}
          "Rerendered because"]
         (rerendered-because-list invalidated-by)])]
     (when expanded?
       (expanded-block item))]))

(defn- cluster-row
  [item group expanded-cluster?]
  (let [{:keys [view-id triggered-by count total-ms avg-ms p95-ms]} item
        ckey   (str "cluster:" (pr-str [view-id triggered-by]))]
    [:div {:data-testid (str "rf-causa-views-cluster-" (name group))
           :on-context-menu (right-click-filter-handler view-id)
           :title (when view-id
                    (str "Right-click → filter Views panel to "
                         "<" (h/format-view-id view-id) ">"))
           :style row-style}
     [:div {:style {:display "flex" :gap "12px" :align-items "flex-start"}}
      [:span {:style {:color (:yellow tokens) :font-weight 700}}
       (get h/group-glyph group "◐")]
      [:div {:style {:flex "1 1 auto" :min-width 0}}
       [:div {:style {:display "flex" :align-items "center" :gap "8px"
                      :flex-wrap "wrap"}}
        [:span {:style {:font-weight 600}}
         (str "<" (h/format-view-id view-id) "> × " count " (clustered)")]
        [:span {:style {:color (:text-tertiary tokens) :font-size "11px"}}
         (str (format-ms total-ms) " total · "
              (format-ms avg-ms) " avg · "
              (format-ms p95-ms) " p95")]]
       [:div {:style mono-style}
        [:span {:style       trigger-glyph-style
                :title       trigger-glyph-tooltip
                :aria-label  "value changed"
                :data-marker "trigger"}
         "✱"]
        [:span (format-sub-id triggered-by)]
        [:span {:style {:margin-left "8px" :color (:text-tertiary tokens)
                        :font-size "11px"}}
         "(args vary per instance)"]]
       [:button {:data-testid (str "rf-causa-views-cluster-toggle-"
                                   (h/format-view-id view-id))
                 :on-click #(rf/dispatch
                             [:rf.causa/views-toggle-cluster ckey])
                 :style {:background "transparent"
                         :border (str "1px solid " (:border-default tokens))
                         :color (:text-secondary tokens)
                         :padding "2px 8px"
                         :font-size "11px"
                         :cursor "pointer"
                         :margin-top "6px"
                         :border-radius "3px"}}
        (if expanded-cluster? "Collapse cluster ▴" "Expand cluster ▾")]
       (when expanded-cluster?
         [:div {:data-testid "rf-causa-views-cluster-instances"
                :style {:margin-top "8px"
                        :padding "8px"
                        :background (:bg-1 tokens)
                        :max-height "240px"
                        :overflow-y "auto"
                        :font-family mono-stack
                        :font-size "11px"}}
          (for [r (take 200 (:renders item))]
            ^{:key (pr-str (:render-key r))}
            [:div {:style {:padding "2px 0"}}
             (str (h/format-view-id (h/render-key->view-id (:render-key r)))
                  " " (pr-str (h/render-key->instance-token (:render-key r)))
                  "  → "
                  (inspect-inline (:triggered-by r))
                  "  · "
                  (format-ms (:elapsed-ms r)))])
          (when (> (count (:renders item)) 200)
            [:div {:style {:color (:text-tertiary tokens) :margin-top "4px"}}
             (str "… (" (count (:renders item))
                  " total; first 200 shown — virtualisation pending)")])])]]]))

(defn- group-section
  "One of the three named groups (mounted / re-rendered / unmounted).
  Hides itself when the group has zero items, per spec §Empty
  states the parent renders a panel-wide message in that case."
  [group items expanded-rows expanded-clusters]
  (when (seq items)
    [:section {:data-testid (str "rf-causa-views-group-" (name group))
               :style {:display "flex" :flex-direction "column"}}
     [:header {:style group-section-style}
      (str (name group) " this cascade (" (count items) ")")]
     ;; `^{:key rk}` reader meta on the `(case …)` form below would be
     ;; attached to the source list and lost when `case` returns the
     ;; branch's vector value — Reagent's `get-react-key` only reads
     ;; `:key` meta from vectors (see reagent2.impl.template). Apply the
     ;; key meta to the returned vector directly via `with-meta`.
     ;; `single-row` and `cluster-row` always return a `[:div …]` vector,
     ;; so `with-meta` is safe (never nil). (rf2-gphsi)
     (for [item items]
       (let [rk (row-key item)]
         (with-meta
           (case (:kind item)
             :single  (single-row item group (contains? expanded-rows rk))
             :cluster (cluster-row item group (contains? expanded-clusters rk)))
           {:key rk})))]))

;; ---- sub-grouped renderer (spec §Group-by toggle / rf2-r2s2l) ----------
;;
;; Inverted hierarchy: top-level rows are subs that ran this cascade;
;; under each sub-row, the components that consumed it. Answers
;; "which sub caused all this rendering?" The data is shaped by
;; `views_helpers.cljc` `build-sub-grouped` over the Re-rendered
;; group's annotated items (the only group with per-render sub
;; attribution under v1 instrumentation).

(defn- sub-row
  "One row in the sub-grouped layout. The sub-id is the top-level
  identifier; the consumed view-ids list under it. The trigger glyph
  follows the same chrome the component-mode uses so the marker
  vocabulary is consistent across both modes."
  [{:keys [sub-id trigger? recomputed? views view-count]}]
  (let [status (cond
                 trigger?    :cache-miss-trigger
                 recomputed? :cache-miss-equal
                 :else       :cache-hit)
        chrome (sub-status-chrome status)]
    [:div {:data-testid "rf-causa-views-sub-row"
           :data-sub-id (pr-str sub-id)
           :style row-style}
     [:div {:style {:display "flex"
                    :align-items "center"
                    :gap "8px"
                    :flex-wrap "wrap"}}
      [:span {:style (:style chrome)
              :title (:tooltip chrome)
              :aria-label (:aria-label chrome)
              :data-marker (:data-marker chrome)}
       (:glyph chrome)]
      [:span {:style {:font-weight 600 :font-family mono-stack}}
       (format-sub-id sub-id)]
      [:span {:style {:color (:text-tertiary tokens) :font-size "11px"}}
       (str "→ " view-count " view" (when (not= 1 view-count) "s"))]]
     [:div {:style {:padding-left "24px"
                    :margin-top "4px"
                    :display "flex"
                    :flex-direction "column"
                    :gap "2px"}}
      (for [v views]
        ^{:key (pr-str (:render-key v))}
        [:div {:data-testid "rf-causa-views-sub-row-consumer"
               :on-context-menu (right-click-filter-handler (:view-id v))
               :title (when (:view-id v)
                        (str "Right-click → filter Views panel to "
                             "<" (h/format-view-id (:view-id v)) ">"))
               :style (assoc mono-style :cursor "default")}
         [:span {:style {:display "inline-block" :width "12px"
                         :text-align "center" :margin-right "6px"
                         :color (if (:trigger? v)
                                  (:yellow tokens)
                                  (:text-tertiary tokens))}}
          (if (:trigger? v) "✱" "·")]
         [:span (str "<" (h/format-view-id (:view-id v)) ">")]
         (when (:clustered? v)
           [:span {:style {:margin-left "6px"
                           :color (:text-tertiary tokens)
                           :font-size "11px"}}
            (str "× " (or (:clustered-n v) 1) " (clustered)")])
         [:span {:style {:margin-left "8px"
                         :color (:text-tertiary tokens)
                         :font-size "11px"}}
          (format-ms (:elapsed-ms v))]])]]))

(defn- sub-grouped-section
  "Top-level container for the sub-grouped renderer. Mirrors
  `group-section`'s header + listing shape so the panel feels
  consistent across toggles. Renders an empty-state when there are
  no subs with consumers (e.g. a parent-forced cascade with no
  sub invalidations)."
  [sub-rows]
  [:section {:data-testid "rf-causa-views-sub-grouped"
             :style {:display "flex" :flex-direction "column"}}
   [:header {:style group-section-style}
    (str "subs that ran this cascade (" (count sub-rows) ")")]
   (if (seq sub-rows)
     (for [s sub-rows]
       (with-meta (sub-row s) {:key (pr-str (:sub-id s))}))
     [:div {:data-testid "rf-causa-views-sub-grouped-empty"
            :style {:padding "16px"
                    :color (:text-tertiary tokens)
                    :font-family sans-stack
                    :font-size "13px"}}
      [:p "No sub invalidations this cascade. (Re-renders were "
       "parent-forced, or no subs were consumed by the views that "
       "ran.)"]])])

;; ---- chrome -------------------------------------------------------------

(defn- header-block
  "rf2-y8bik — the cascade-metadata `:p` (`Frame: … · cascade … ·
  cascade ms: …`) ONLY renders when a cascade is actually focused.
  Pre-fix the line surfaced whenever `(:frame data)` was truthy, but
  `:frame` is populated from `:rf.causa/focus` (the spine's frame
  slot) independent of focus state — so a frame-picker selection with
  no focused cascade left the header advertising `:cart-frame ·
  cascade 35 · cascade ms: 0µs` while the body read `No event
  focused.` Silent-by-default: empty body → empty chrome header,
  except the panel title which is part of the L4 tab affordance."
  [data]
  [:header {:style {:padding "16px 16px 8px 16px"}}
   ;; rf2-5kfxe.8 — domain-coloured accent stripe (:cyan for Views,
   ;; peer of App-db; both read state hence the shared hue).
   [:h1 {:style (merge {:font-size "16px" :font-weight 600 :margin 0
                        :color (:text-primary tokens)}
                       (t/accent-stripe-style :views))}
    "Views"]
   (when (and (:has-cascade? data) (:frame data))
     [:p {:data-testid "rf-causa-views-header-meta"
          :style {:font-size "11px" :color (:text-tertiary tokens)
                  :margin "2px 0 0 0"}}
      (str "Frame: " (:frame data)
           (when-let [did (:dispatch-id data)]
             (str " · cascade " (pr-str did)))
           " · cascade ms: " (format-ms (:cascade-ms (:totals data))))])])

;; ---- group-by toggle (spec §Group-by toggle / rf2-r2s2l) ---------------
;;
;; Bottom-controls pill `[◉ component] [○ sub]`. Switches the Views
;; hierarchy between the default three-group (Mounted / Re-rendered
;; / Unmounted) layout and the inverted sub-rows-first layout (a
;; sub → views-consumed map). The two views are mathematically
;; symmetric per spec; the toggle lets the user pick their entry
;; point ("which views ran?" vs "which sub caused all this
;; rendering?").

(def ^:private group-by-pill-base-style
  {:padding       "2px 8px"
   :font-size     "11px"
   :cursor        "pointer"
   :background    "transparent"
   :border        (str "1px solid " (:border-default tokens))
   :color         (:text-secondary tokens)
   :font-family   sans-stack})

(defn- group-by-pill
  "One pill in the bottom-controls group-by toggle. `selected?` drives
  the filled `◉` vs hollow `○` glyph + foreground colour so the active
  pill reads at a glance."
  [{:keys [value selected? label]}]
  [:button {:data-testid (str "rf-causa-views-group-by-" (name value))
            :on-click #(rf/dispatch [:rf.causa/views-set-group-by value])
            :aria-pressed (if selected? "true" "false")
            :style (cond-> group-by-pill-base-style
                     selected?
                     (assoc :color       (:text-primary tokens)
                            :font-weight 600
                            :border      (str "1px solid "
                                              (:accent-violet tokens))))}
   (str (if selected? "◉" "○") " " label)])

(defn- group-by-toggle
  [group-by]
  (let [active (or group-by :component)]
    [:div {:data-testid "rf-causa-views-group-by-toggle"
           :role "radiogroup"
           :aria-label "Group by"
           :style {:display "flex"
                   :gap "6px"
                   :align-items "center"}}
     [:span {:style {:color (:text-tertiary tokens) :font-size "11px"
                     :margin-right "4px"}}
      "Group by"]
     [group-by-pill {:value :component
                     :selected? (= active :component)
                     :label "component"}]
     [group-by-pill {:value :sub
                     :selected? (= active :sub)
                     :label "sub"}]
     ;; rf2-mxkq7 — third toggle. The tree view groups renders by
     ;; the React Fiber parent ⊃ children hierarchy, surfacing
     ;; parent-cascade attribution (e.g. "parent X (47 descendants
     ;; re-rendered)") as a single collapsed row.
     [group-by-pill {:value :tree
                     :selected? (= active :tree)
                     :label "tree"}]]))

;; ---- filter chip (spec §0ter.1 R3-E — promote above section header) ----
;;
;; The component-filter chip appears ABOVE the three-group section
;; header (per §0ter.1 R3-E refinement) — the chip is panel-scoped
;; (NOT ribbon-scoped — ribbon carries event-list filters). The
;; `bottom-controls` clear button at the foot of the panel is the
;; secondary affordance; the chip is the primary surface so the user
;; sees the filter applied from anywhere in the panel.

(defn- filter-chip
  [view-id]
  (when view-id
    [:div {:data-testid "rf-causa-views-filter-chip"
           :style {:padding "8px 16px"
                   :display "flex"
                   :align-items "center"
                   :gap "6px"
                   :border-bottom (str "1px solid " (:border-subtle tokens))
                   :background (:bg-1 tokens)}}
     [:span {:style {:color (:text-tertiary tokens) :font-size "11px"}}
      "Filtered to:"]
     [:button {:data-testid "rf-causa-views-filter-chip-clear"
               :on-click #(rf/dispatch
                            [:rf.causa/views-set-component-filter nil])
               :aria-label (str "Clear filter on "
                                (h/format-view-id view-id))
               :title "Clear filter"
               :style {:display "inline-flex"
                       :align-items "center"
                       :gap "6px"
                       :padding "2px 8px"
                       :background "transparent"
                       :border (str "1px solid " (:accent-violet tokens))
                       :color (:text-primary tokens)
                       :border-radius "10px"
                       :font-family mono-stack
                       :font-size "12px"
                       :cursor "pointer"}}
      [:span {:style {:font-weight 600}}
       (str "<" (h/format-view-id view-id) ">")]
      [:span {:style {:opacity 0.7
                      :border-left (str "1px solid " (:accent-violet tokens))
                      :padding-left "6px"}}
       "×"]]]))

(defn- bottom-controls
  [data]
  (let [cf      (:component-filter data)
        groupby (:group-by data)]
    [:footer {:data-testid "rf-causa-views-controls"
              :style {:padding "8px 16px"
                      :border-top (str "1px solid " (:border-subtle tokens))
                      :display "flex"
                      :gap "16px"
                      :align-items "center"
                      :font-size "11px"
                      :color (:text-secondary tokens)}}
     [group-by-toggle groupby]
     (when cf
       [:button {:data-testid "rf-causa-views-clear-filter"
                 :on-click #(rf/dispatch
                             [:rf.causa/views-set-component-filter nil])
                 :style {:background "transparent"
                         :border (str "1px solid " (:border-default tokens))
                         :color (:text-secondary tokens)
                         :padding "2px 8px"
                         :font-size "11px"
                         :cursor "pointer"
                         :border-radius "3px"}}
        (str "Filtered: " (h/format-view-id cf) " ×")])]))

(defn- empty-state
  [data]
  [:div {:data-testid "rf-causa-views-empty"
         :style {:padding "16px"
                 :color (:text-tertiary tokens)
                 :font-family sans-stack
                 :font-size "13px"}}
   (cond
     (nil? (:current (:focus data)))
     [:p "No event focused."]

     :else
     [:p "No views rendered this cascade."])])

;; ---- panel root --------------------------------------------------------

(defn views-panel
  "Plain Reagent fn — invoked from `views/Panel` (the public facade
  reg-view) via a function call so the React-context frame tier
  resolves to `:rf/causa` inside the leaf's subscribes (per the same
  facade convention `subscriptions.cljs` documents)."
  []
  (let [data @(rf/subscribe [:rf.causa/views-data])
        groups (:groups data)
        sub-grouped (:sub-grouped data)
        group-by (or (:group-by data) :component)
        component-filter (:component-filter data)
        expanded-rows (or (:expanded-rows data) #{})
        expanded-clusters (or (:expanded-clusters data) #{})]
    [:section {:data-testid "rf-causa-views"
               :style {:height "100%"
                       :display "flex"
                       :flex-direction "column"
                       :background (:bg-2 tokens)
                       :color (:text-primary tokens)
                       :font-family sans-stack
                       :font-size "14px"}}
     (header-block data)
     ;; rf2-r2s2l — Filter chip lives ABOVE the body content (per
     ;; §0ter.1 R3-E refinement). Panel-scoped affordance; the
     ;; secondary clear button lives in `bottom-controls` for the
     ;; user who scrolled past the chip.
     (filter-chip component-filter)
     [:div {:style {:flex 1 :overflow "auto"}}
      (cond
        (not (:has-cascade? data))
        (empty-state data)

        (= group-by :sub)
        (sub-grouped-section sub-grouped)

        ;; rf2-mxkq7 — Group-by-tree: walk Fiber for the host app's
        ;; view hierarchy and render indented rows with parent-cascade
        ;; rollup chips. `gbt/read-host-tree` returns nil under DCE
        ;; (production) or in headless tests; `build-tree-rows`
        ;; degrades to a flat depth-0 projection in that case.
        (= group-by :tree)
        (gbt/tree-section (gbt/build-tree-rows (gbt/read-host-tree) groups))

        (zero? (+ (count (:mounted groups))
                  (count (:rendered groups))
                  (count (:unmounted groups))))
        (empty-state data)

        :else
        [:div {:data-testid "rf-causa-views-groups"
               :style {:display "flex" :flex-direction "column"}}
         ;; `^{:key g}` reader meta on the `(group-section …)` call would
         ;; be attached to the source list and lost — Reagent's
         ;; `get-react-key` only reads `:key` meta from vectors. Apply
         ;; the key directly to the returned `[:section …]` vector via
         ;; `with-meta`, guarding for nil (empty groups → no section).
         ;; (rf2-gphsi)
         (for [g h/group-order
               :let [section (group-section g (get groups g)
                                            expanded-rows expanded-clusters)]
               :when section]
           (with-meta section {:key g}))])]
     (bottom-controls data)]))
