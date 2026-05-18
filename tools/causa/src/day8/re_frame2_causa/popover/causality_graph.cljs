(ns day8.re-frame2-causa.popover.causality-graph
  "View body for the Causality popover graph (rf2-dqnuu).

  Renders the focused-event's ancestor chain (LR) + descendants tree
  (TB) via ELK + SVG. ELK.js is **lazy-loaded** on first popover open
  via a dynamic import; bundling ELK into the base shell would pull
  ~250kB into every Causa dev session whether the user opens the
  popover or not.

  ## Fallback when ELK is unavailable

  If the dynamic import fails (offline test env, CSP block, no
  network) the view drops into a `fallback-list` render — a flat
  `<ul>` listing the focused event + its ancestors and descendants
  with edges as `parent → child` lines. The fallback is fully
  functional (the user can read the cascade lineage) but loses the
  visual graph affordance — surfaced with a footer status hint
  'Causality graph unavailable (ELK.js failed to load)'.

  The fallback is what tests assert against — node-test has no
  bundler-resolvable `elkjs` package and the import will reject
  immediately. The browser-test build also defaults to the fallback
  unless ELK has been pre-loaded into `js/window.ELK` (the live
  browser session relies on shadow's classpath + the dev runtime
  resolving ELK from node_modules via :npm-module).

  ## Pure hiccup

  The view is pure hiccup per rf2-tijr — the substrate's `rf/render`
  walks the tree. The reactive surface is one subscribe to
  `:rf.causa/causality-popover-payload` + one to `-layout`.

  ## Why dispatches carry `{:frame :rf/causa}` (rf2-w8lxg)

  Same root cause as the popover facade — `:on-click` handlers on the
  SVG nodes + fallback-list rows fire AFTER React has popped
  `_currentValue` for the `frame-context` back to `:rf/default`. At
  click time the 3-tier frame resolution chain falls through to
  `:rf/default`'s router, the dispatch lands on the wrong frame, and
  the spine-rebind / popover-close pair silently no-op. Every
  dispatch from a deferred handler passes `{:frame :rf/causa}`."
  (:require [re-frame.core :as rf]
            [day8.re-frame2-causa.popover.causality-graph-helpers :as h]
            [day8.re-frame2-causa.theme.tokens
             :refer [tokens mono-stack sans-stack]]))

;; ---- ELK lazy-load state -------------------------------------------------

(defonce ^:private elk-state
  ;; Holds one of:
  ;;   nil             — not yet attempted
  ;;   :loading        — import in flight
  ;;   {:elk <obj>}    — loaded; obj is an ELK instance
  ;;   {:failed err}   — load attempt rejected; fallback path engaged
  ;;
  ;; defonce so shadow-cljs `:after-load` reloads don't re-trigger the
  ;; import.
  (atom nil))

(defn elk-status
  "Public read-accessor for the ELK loader state. Returns one of
  `:idle | :loading | :ready | :failed`. Used by tests + by the view
  to surface the right state without exposing the internal atom."
  []
  (let [s @elk-state]
    (cond
      (nil? s)                :idle
      (= s :loading)          :loading
      (and (map? s) (:elk s)) :ready
      (and (map? s) (:failed s)) :failed
      :else                   :idle)))

(defn- elk-instance
  "Return the loaded ELK instance, or nil when not ready."
  []
  (when-let [s @elk-state]
    (and (map? s) (:elk s))))

(defn- maybe-window-elk
  "Some test rigs pre-stub `js/window.ELK` so a synchronous load path
  works without triggering a dynamic import. Returns that constructor
  when present, nil otherwise."
  []
  (when (and (exists? js/window))
    (let [w js/window]
      (or (.-ELK w)
          (.-elkjs w)))))

(defn ensure-elk!
  "Idempotent lazy load. Returns immediately. When loading completes
  (sync stub OR async import resolved), `done-fn` fires with the ELK
  instance (or nil on failure). Subsequent calls after success are
  cheap no-ops that re-fire `done-fn` with the cached instance.

  Three load paths:

    1. Pre-stubbed `js/window.ELK` — sync wrap, no import. Used by
       tests that want the loaded path without bundling.
    2. `js/import` resolves a `\"elkjs/lib/elk.bundled.js\"` package —
       production browser session.
    3. Both fail → state flips to `{:failed ...}` and fallback
       renders. The view stays usable; the footer reports the
       unavailable status."
  [done-fn]
  (let [cur @elk-state]
    (cond
      ;; Already loaded — fast-path callback.
      (and (map? cur) (:elk cur))
      (done-fn (:elk cur))

      ;; Already failed — fast-path callback with nil.
      (and (map? cur) (:failed cur))
      (done-fn nil)

      ;; Loading — caller waits for the in-flight completion. v1 does
      ;; not maintain a wait-queue; the second caller just polls via
      ;; rf-sub recomputation on the next tick. (Tests inject the
      ;; state directly so this branch isn't asserted.)
      (= cur :loading)
      nil

      :else
      (do
        (reset! elk-state :loading)
        ;; Path 1 — sync stub.
        (if-let [Ctor (maybe-window-elk)]
          (try
            (let [inst (Ctor.)]
              (reset! elk-state {:elk inst})
              (done-fn inst))
            (catch :default e
              (reset! elk-state {:failed (.-message e)})
              (done-fn nil)))
          ;; Path 2 — dynamic import. js/import is not available in
          ;; every CLJS host; we wrap in a try/catch so the missing-
          ;; api case folds into the failed state.
          (try
            ;; `js/import` is the standard dynamic-import expression
            ;; in modern JS hosts. shadow-cljs `:browser` builds
            ;; resolve npm packages at build time when listed in
            ;; package.json; absent that resolution, the import
            ;; rejects with a module-not-found error and we drop into
            ;; the fallback path.
            (let [p (try
                      (js/import "elkjs/lib/elk.bundled.js")
                      (catch :default _ nil))]
              (if (and p (.-then p))
                (-> p
                    (.then (fn [mod]
                             (let [Ctor (or (.-default mod) (.-ELK mod) (.-elk mod))]
                               (if Ctor
                                 (let [inst (Ctor.)]
                                   (reset! elk-state {:elk inst})
                                   (done-fn inst))
                                 (do
                                   (reset! elk-state {:failed "no ELK constructor in module"})
                                   (done-fn nil))))))
                    (.catch (fn [e]
                              (reset! elk-state {:failed (or (.-message e) "import failed")})
                              (done-fn nil))))
                (do
                  (reset! elk-state {:failed "js/import unavailable"})
                  (done-fn nil))))
            (catch :default e
              (reset! elk-state {:failed (or (.-message e) "import threw")})
              (done-fn nil))))))))

(defn reset-elk-state-for-test!
  "Reset the loader state — test-only. Lets the test suite drive a
  fresh load attempt with a stubbed `js/window.ELK`."
  []
  (reset! elk-state nil)
  nil)

(defn force-elk-failed-for-test!
  "Force the loader state into the `:failed` shape — test / gallery
  only. Pins the popover's render path to the failed-fallback branch
  (flat list + 'Causality graph unavailable' footer) without running
  a real import. Idempotent."
  ([] (force-elk-failed-for-test! "forced fallback"))
  ([reason]
   (reset! elk-state {:failed reason})
   nil))

;; ---- ELK layout sub-cache -----------------------------------------------

(defonce ^:private layout-cache
  ;; `{:key [payload-id direction] :positions {<id> {:x :y}} :width :height}`.
  ;; The popover re-renders on every payload tick; ELK layout is
  ;; expensive so we cache positions keyed on the payload's
  ;; identity-id (we use the `:nodes` vec's identity as the topology
  ;; key) + the chosen direction. defonce survives hot-reload.
  (atom nil))

(defn- compute-layout-sync
  "Synchronous layout when ELK is ready. ELK's `layout` returns a
  Promise; we re-render on its resolution by updating the cache and
  setting a render-tick. The first render after open mirrors this
  path: ELK isn't ready yet → fallback renders → on `.then` resolution
  the cache flips and a forced re-render lands."
  [payload direction]
  (when-let [elk (elk-instance)]
    (let [graph    (h/->elk-graph payload direction)
          graph-js (clj->js graph)
          p        (.layout elk graph-js)]
      (-> p
          (.then (fn [result]
                   (let [children (or (.-children result) #js [])
                         positions
                         (reduce (fn [m child]
                                   (assoc m
                                          (js/parseInt (.-id child))
                                          {:x (or (.-x child) 0)
                                           :y (or (.-y child) 0)}))
                                 {}
                                 (array-seq children))
                         w (or (.-width result) 640)
                         h (or (.-height result) 480)]
                     (reset! layout-cache
                             {:key       [(:nodes payload) direction]
                              :positions positions
                              :width     w
                              :height    h})
                     ;; Force the popover to recompute on next subscribe
                     ;; tick. We dispatch a no-op event the framework
                     ;; sees as an app-db write through the popover's
                     ;; toggle-layout-noop slot — or rather, we re-use
                     ;; the existing layout slot's nil → same write to
                     ;; pulse the reactive graph. `{:frame :rf/causa}`
                     ;; explicit (rf2-w8lxg) — this Promise `.then`
                     ;; resolves AFTER render so the React-context tier
                     ;; would otherwise route the pulse to `:rf/default`.
                     (rf/dispatch [:rf.causa/causality-popover-layout-pulse]
                                  {:frame :rf/causa}))))
          (.catch (fn [_e]
                    ;; Treat layout failure as a transient — the
                    ;; fallback render is still active. The next
                    ;; payload tick triggers another attempt.
                    nil))))))

(defn- cached-layout
  "Return the cached positions for `payload`+`direction`, or nil when
  the cache is stale / empty."
  [payload direction]
  (let [c @layout-cache]
    (when (and c (= (:key c) [(:nodes payload) direction]))
      c)))

;; ---- SVG render ----------------------------------------------------------

(def ^:private node-w 140)
(def ^:private node-h 44)

(defn- node-rect
  "One node — rect + glyph + label. Clicking the rect fires
  `:rf.causa/focus-cascade <id>` + closes the popover (per spec §10
  §Interaction)."
  [node {:keys [x y]}]
  (let [{:keys [dispatch-id event fill stroke role glyph]} node
        sw (if (= role :focused) 2 1)
        label (let [s (try (pr-str (or event :ungrouped))
                           (catch :default _ (str event)))
                    n 22]
                (if (<= (count s) n)
                  s
                  (str (subs s 0 (max 0 (dec n))) "…")))]
    [:g {:data-testid (str "rf-causa-popover-node-" dispatch-id)
         :on-click    (fn []
                        (rf/dispatch [:rf.causa/focus-cascade dispatch-id]
                                     {:frame :rf/causa})
                        (rf/dispatch [:rf.causa/causality-popover-close]
                                     {:frame :rf/causa}))
         :style       {:cursor "pointer"}}
     [:rect {:x            x
             :y            y
             :width        node-w
             :height       node-h
             :rx           8
             :ry           8
             :fill         fill
             :fill-opacity (if (= role :focused) 1.0 0.85)
             :stroke       stroke
             :stroke-width sw}]
     [:text {:x           (+ x 10)
             :y           (+ y 26)
             :fill        "#fff"
             :font-family mono-stack
             :font-size   14
             :font-weight 700
             :pointer-events "none"}
      glyph]
     [:text {:x           (+ x 28)
             :y           (+ y 19)
             :fill        "#fff"
             :font-family mono-stack
             :font-size   11
             :pointer-events "none"}
      label]
     [:text {:x           (+ x 28)
             :y           (+ y 34)
             :fill        "rgba(255,255,255,0.7)"
             :font-family mono-stack
             :font-size   9
             :pointer-events "none"}
      (str "#" dispatch-id)]]))

(defn- arrow
  [[from-id to-id] positions]
  (let [from (get positions from-id)
        to   (get positions to-id)]
    (when (and from to)
      (let [fx (+ (:x from) (/ node-w 2))
            fy (+ (:y from) node-h)
            tx (+ (:x to)   (/ node-w 2))
            ty (:y to)]
        [:g {:data-testid (str "rf-causa-popover-arrow-" from-id "-" to-id)}
         [:path {:d            (str "M " fx " " fy " L " tx " " ty)
                 :stroke       (:text-tertiary tokens)
                 :stroke-width 1.5
                 :fill         "none"
                 :marker-end   "url(#rf-causa-popover-arrowhead)"}]]))))

(defn- defs []
  [:defs
   [:marker {:id           "rf-causa-popover-arrowhead"
             :viewBox      "0 0 10 10"
             :refX         9
             :refY         5
             :markerWidth  6
             :markerHeight 6
             :orient       "auto-start-reverse"}
    [:path {:d    "M 0 0 L 10 5 L 0 10 z"
            :fill (:text-tertiary tokens)}]]])

(defn- graph-svg
  "SVG canvas with ELK-laid-out nodes + edges. Caller has verified the
  layout cache is populated."
  [payload {:keys [positions width height] :as _layout}]
  [:svg {:data-testid "rf-causa-popover-svg"
         :width   width
         :height  height
         :viewBox (str "0 0 " width " " height)
         :style   {:display "block" :background (:bg-2 tokens)}}
   (defs)
   (into [:g {:data-testid "rf-causa-popover-edges"}]
         (for [e (:edges payload)]
           ^{:key (str (first e) "->" (second e))}
           (arrow e positions)))
   (into [:g {:data-testid "rf-causa-popover-nodes"}]
         (for [n (:nodes payload)
               :let [pos (get positions (:dispatch-id n))]
               :when pos]
           ^{:key (:dispatch-id n)}
           (node-rect n pos)))])

;; ---- fallback (list) render ---------------------------------------------

(defn- list-row
  "One row in the fallback list. Same on-click semantics as the SVG
  node — click rebinds the spine + closes the popover."
  [node]
  (let [{:keys [dispatch-id event role glyph]} node
        label (try (pr-str (or event :ungrouped))
                   (catch :default _ (str event)))]
    [:li {:data-testid (str "rf-causa-popover-list-row-" dispatch-id)
          :on-click    (fn []
                         (rf/dispatch [:rf.causa/focus-cascade dispatch-id]
                                      {:frame :rf/causa})
                         (rf/dispatch [:rf.causa/causality-popover-close]
                                      {:frame :rf/causa}))
          :style       {:padding         "6px 12px"
                        :cursor          "pointer"
                        :display         "flex"
                        :align-items     "center"
                        :gap             "8px"
                        :background      (if (= role :focused)
                                           (:bg-active tokens)
                                           "transparent")
                        :border-bottom   (str "1px solid " (:border-subtle tokens))
                        :color           (:text-primary tokens)
                        :font-family     mono-stack
                        :font-size       "12px"}}
     [:span {:style {:width        "20px"
                     :text-align   "center"
                     :color        (case role
                                     :focused    "#43C3D0"
                                     :ancestor   (:accent-violet tokens)
                                     :descendant (:text-secondary tokens))}}
      glyph]
     [:span {:style {:color (:text-tertiary tokens)
                     :font-size "10px"}}
      (str "#" dispatch-id)]
     [:span {:style {:flex 1
                     :overflow      "hidden"
                     :text-overflow "ellipsis"
                     :white-space   "nowrap"}}
      label]
     [:span {:style {:color (:text-tertiary tokens) :font-size "10px"}}
      (name role)]]))

(defn fallback-list
  "Fallback render — flat list of nodes. Used when ELK is unavailable
  (load failed, or initial render before the async import resolves).
  Same on-click contract as the SVG nodes."
  [payload]
  (if (:empty? payload)
    [:div {:data-testid "rf-causa-popover-empty"
           :style       {:padding "24px"
                         :color   (:text-secondary tokens)
                         :font-family sans-stack
                         :font-size "13px"}}
     "No focused event."]
    (let [{:keys [ancestors focused descendants]} payload
          desc-nodes (:nodes descendants)
          all (concat ancestors [focused] desc-nodes)]
      [:ul {:data-testid "rf-causa-popover-list"
            :style       {:list-style "none"
                          :margin     0
                          :padding    0}}
       (for [n all]
         ^{:key (:dispatch-id n)}
         (list-row n))])))

;; ---- main view ----------------------------------------------------------

(defn body
  "Render the popover graph body. Tries the ELK path first; falls back
  to the list when ELK is unavailable or the layout cache hasn't
  resolved yet.

  The caller (`popover.causality/Popover`) has already gated on
  `:rf.causa/causality-popover-open?` and rendered the modal chrome
  (backdrop / header / footer). This fn is just the inner content."
  [payload direction]
  ;; Kick the loader (idempotent). The first call triggers the import;
  ;; subsequent calls during the same session are constant-time.
  (ensure-elk! (fn [_inst]
                 ;; Once loaded, kick a layout. The layout's `.then`
                 ;; populates the cache + dispatches the pulse event
                 ;; that re-renders this view.
                 (when (and (elk-instance) (not (:empty? payload)))
                   (compute-layout-sync payload direction))))
  (let [status (elk-status)
        cache  (cached-layout payload direction)]
    (cond
      ;; ELK ready + cache populated for this payload+direction → SVG.
      (and (= status :ready) (some? cache) (not (:empty? payload)))
      (graph-svg payload cache)

      ;; ELK ready but cache not yet for this combination → trigger a
      ;; layout (the ensure-elk! callback above will do that) and show
      ;; the fallback in the meantime. The pulse event re-renders us.
      (= status :ready)
      (do
        (when-not (:empty? payload)
          (compute-layout-sync payload direction))
        (fallback-list payload))

      ;; ELK failed → fallback + footer status hint.
      (= status :failed)
      [:div
       (fallback-list payload)
       [:div {:data-testid "rf-causa-popover-elk-unavailable"
              :style       {:padding "8px 16px"
                            :color (:yellow tokens)
                            :font-family sans-stack
                            :font-size "11px"
                            :border-top (str "1px solid " (:border-subtle tokens))}}
        "Causality graph unavailable (ELK.js failed to load) — showing
         flat causal lineage instead."]]

      ;; idle / loading → fallback while we wait.
      :else
      (fallback-list payload))))
