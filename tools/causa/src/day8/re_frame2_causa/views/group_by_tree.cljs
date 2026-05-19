(ns day8.re-frame2-causa.views.group-by-tree
  "Tree-rendering for Causa's Views panel — Group-by-tree toggle
  (rf2-mxkq7).

  Consumes the cascade's render projection (mounted / rendered /
  unmounted items per `views_helpers.cljc`) PLUS the Fiber-walker
  output (`views/fiber_walker.cljs`) and emits indented rows in
  parent ⊃ children document order. Each row carries a `:descendant-
  rerender-count` so the panel can collapse a parent-cascade into a
  single 'parent X (47 descendants re-rendered)' row.

  This namespace is pure-data + view code, both gated upstream by the
  panel-wide `interop/debug-enabled?` posture — the consumer of the
  Fiber walker. Nothing in `implementation/` should `:require` this
  ns (tools/ → implementation/ bundle-isolation contract).

  ## Hierarchy projection algebra

  - **Step 1** — read the Fiber tree from the host's root DOM node
    via `fiber-walker/read-tree-from`. Returns a vector of
    `{:view-id :depth :fiber-key}` in document order.
  - **Step 2** — index the panel's render projection by `:view-id`
    so a tree-row can look up its render entry (status, elapsed-ms,
    invalidated-by) in O(1).
  - **Step 3** — for each tree node, compute the descendant re-
    render count by scanning the subtree that follows (contiguous
    range of higher-depth rows).
  - **Step 4** — emit one `{:view-id :depth :status :item :descendant-
    rerender-count}` per tree node.

  When the Fiber walker returns nil (no DOM node available — common
  in headless test runtimes), the renderer ships a flat fallback
  using the render projection alone with depth 0 for every row, so
  the toggle still surfaces something useful."
  (:require [re-frame.core :as rf]
            [re-frame.interop :as interop]
            [day8.re-frame2-causa.panels.views-helpers :as h]
            [day8.re-frame2-causa.views.fiber-walker :as fiber]
            [day8.re-frame2-causa.theme.tokens
             :refer [tokens sans-stack]]))

;; ---- pure-data tree-row builder ----------------------------------------

(defn- index-items-by-view-id
  "Index the panel's projected items (mounted / rendered / unmounted)
  by `:view-id` — the first slot of each item's `:render-key`. When
  multiple instances of the same view-id rendered in one cascade, the
  first wins; per-instance drilldown stays inside the existing
  Group-by-component flow."
  [groups]
  (let [all (concat (:mounted groups) (:rendered groups) (:unmounted groups))]
    (persistent!
      (reduce
        (fn [acc item]
          (let [vid (case (:kind item)
                      :single  (h/render-key->view-id
                                 (:render-key (:render item)))
                      :cluster (:view-id item))]
            (if (and vid (not (contains? acc vid)))
              (assoc! acc vid item)
              acc)))
        (transient {}) all))))

(defn- item->status
  "Classify a row by which group it came from. The Group-by-tree
  surface only carries a coarse status (mounted / rendered /
  unmounted / unchanged) — the per-row detail stays in the
  Group-by-component drilldown."
  [groups item]
  (cond
    (contains? (set (:mounted groups)) item)   :mounted
    (contains? (set (:rendered groups)) item)  :rendered
    (contains? (set (:unmounted groups)) item) :unmounted
    :else :unchanged))

(defn build-tree-rows
  "Compose the tree-row sequence consumed by `tree-section`. Inputs:

  - `tree`     — vector of `{:view-id :depth :fiber-key}` from
                 `fiber-walker/read-tree-from`. May be nil.
  - `groups`   — the panel's three-group projection.

  Output: vector of `{:view-id :depth :fiber-key :status :item
  :descendant-rerender-count}` rows, depth-first, with a descendant
  re-render rollup attached to every internal node.

  When `tree` is nil, falls back to depth-0 rows from the projection
  alone."
  {:added "rf2-mxkq7"}
  [tree groups]
  (let [idx (index-items-by-view-id groups)]
    (cond
      (seq tree)
      (let [n  (count tree)
            tv (vec tree)]
        (vec
          (for [i (range n)
                :let [node (nth tv i)
                      d    (:depth node)
                      vid  (:view-id node)
                      item (get idx vid)
                      ;; descendants = contiguous span of rows whose
                      ;; depth is strictly greater than this row's,
                      ;; starting immediately after `i`.
                      desc-count
                      (loop [j (inc i) cnt 0]
                        (cond
                          (>= j n) cnt
                          (<= (:depth (nth tv j)) d) cnt
                          :else
                          (let [child-vid (:view-id (nth tv j))
                                child-it  (get idx child-vid)]
                            (recur (inc j)
                                   (if (and child-it
                                            (= :rendered
                                               (item->status groups child-it)))
                                     (inc cnt) cnt)))))]]
            {:view-id    vid
             :depth      d
             :fiber-key  (:fiber-key node)
             :item       item
             :status     (if item (item->status groups item) :unchanged)
             :descendant-rerender-count desc-count})))

      ;; Fallback: no Fiber tree available (headless test, walker
      ;; elided, host not React). Emit one depth-0 row per indexed
      ;; view-id so the toggle still surfaces the cascade.
      :else
      (vec
        (for [[vid item] idx]
          {:view-id vid
           :depth   0
           :fiber-key (hash vid)
           :item    item
           :status  (item->status groups item)
           :descendant-rerender-count 0})))))

;; ---- rendering ---------------------------------------------------------

(def ^:private row-base-style
  {:padding "6px 16px"
   :border-bottom (str "1px solid " (:border-subtle tokens))
   :font-family sans-stack
   :font-size "13px"
   :color (:text-primary tokens)
   :display "flex"
   :align-items "center"
   :gap "8px"})

(def ^:private status-glyph
  {:mounted   "◆"
   :rendered  "◐"
   :unmounted "◇"
   :unchanged "·"})

(def ^:private status-colour
  {:mounted   :accent-violet
   :rendered  :yellow
   :unmounted :text-tertiary
   :unchanged :text-tertiary})

(defn- collapsible-summary
  "Render the '(N descendants re-rendered)' chip — visible only when
  the row has at least one re-rendered descendant. Pure cosmetic;
  click-to-collapse semantics are owned by the toggle event keyed on
  `:fiber-key`."
  [count]
  (when (pos? count)
    [:span {:data-testid "rf-causa-views-tree-row-descendant-count"
            :style {:color (:text-tertiary tokens)
                    :font-size "11px"}}
     (str "(" count " descendant" (when (not= 1 count) "s") " re-rendered)")]))

(defn- format-vid-label
  [vid]
  (cond
    (keyword? vid) (h/format-view-id vid)
    (nil? vid)     "<host>"
    :else          (str vid)))

(defn- tree-row
  "One row in the Group-by-tree rendering. `:depth` drives the
  indentation; `:status` drives the glyph + colour; the descendant
  re-render count surfaces inline as a roll-up chip. Right-click
  filters the panel to the row's view-id (mirrors single-row +
  cluster-row right-click semantics in `views_view.cljs`)."
  [{:keys [view-id depth status fiber-key descendant-rerender-count]}]
  [:div {:data-testid (str "rf-causa-views-tree-row-" (name status))
         :data-fiber-key (str fiber-key)
         :on-context-menu
         (fn [^js e]
           (when (keyword? view-id)
             (.preventDefault e)
             ;; rf2-83d4x — explicit :frame opt; click-time React
             ;; `_currentValue` pop falls through to `:rf/default`
             ;; otherwise (same root cause as rf2-w8lxg/rf2-smvvz).
             (rf/dispatch [:rf.causa/views-set-component-filter view-id]
                          {:frame :rf/causa})))
         :style (assoc row-base-style
                       :padding-left (str (+ 16 (* depth 16)) "px"))}
   [:span {:style {:color (get tokens (get status-colour status))
                   :font-weight 700
                   :width "12px"
                   :text-align "center"}}
    (get status-glyph status "·")]
   [:span {:style {:font-weight 600
                   :text-decoration (when (= status :unmounted) "line-through")
                   :color (if (= status :unmounted)
                            (:text-tertiary tokens)
                            (:text-primary tokens))}}
    (str "<" (format-vid-label view-id) ">")]
   (collapsible-summary descendant-rerender-count)])

(defn tree-section
  "Top-level container for the Group-by-tree rendering. Mirrors
  `group-section` / `sub-grouped-section` (in `views_view.cljs`) so
  the panel feels consistent across toggles. Renders an empty-state
  when the projection has no rows (e.g. a parent-forced cascade with
  no host-app renders).

  `rows` is the output of `build-tree-rows`."
  [rows]
  [:section {:data-testid "rf-causa-views-tree"
             :style {:display "flex" :flex-direction "column"}}
   [:header {:style {:padding "12px 16px 4px 16px"
                     :font-size "11px"
                     :font-weight 600
                     :text-transform "uppercase"
                     :letter-spacing "0.05em"
                     :color (:text-tertiary tokens)
                     :border-bottom (str "1px solid "
                                         (:border-subtle tokens))}}
    (str "view hierarchy this cascade (" (count rows) ")")]
   (if (seq rows)
     (for [row rows]
       (with-meta (tree-row row) {:key (:fiber-key row)}))
     [:div {:data-testid "rf-causa-views-tree-empty"
            :style {:padding "16px"
                    :color (:text-tertiary tokens)
                    :font-family sans-stack
                    :font-size "13px"}}
      [:p "No view hierarchy captured this cascade. (The host's mount "
       "node hasn't been registered, or no views rendered.)"]])])

;; ---- host-root resolution ---------------------------------------------
;;
;; The Fiber walker reads from a DOM node; we look for the host app's
;; mount root. Two heuristics, in order of confidence:
;;
;;   1. `document.body.firstElementChild` that is NOT Causa's own
;;      `#rf-causa-root` — most apps mount to a single root sibling
;;      (`<div id="app">`, `<div id="root">`, etc.).
;;   2. `document.body` as a last resort (we will index by view-id, so
;;      walking a wider tree is correct but slower).

(defn host-root-dom-node
  "Best-effort: return the host application's mount-root DOM node, or
  nil under DCE / no document. The walker degrades to an empty tree
  when nil; the renderer then falls back to the depth-0 flat
  projection."
  []
  (when (and ^boolean interop/debug-enabled?
             (exists? js/document))
    (let [body (.-body js/document)]
      (when body
        (or (loop [n (.-firstElementChild body)]
              (cond
                (nil? n) nil
                (not= "rf-causa-root" (.-id n)) n
                :else (recur (.-nextElementSibling n))))
            body)))))

(defn read-host-tree
  "Convenience: walk from the resolved host root and return the
  Fiber-tree projection (or nil)."
  []
  (when ^boolean interop/debug-enabled?
    (when-let [root (host-root-dom-node)]
      (fiber/read-tree-from root))))
