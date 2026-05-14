(ns re-frame.story.ui.workspace
  "Workspace rendering. Per Stage 4 (rf2-ekai) IMPL-SPEC §3.1 + spec/007
  §Workspaces.

  Five layouts ship in v1:

  - `:grid`          — vector of variant ids laid out N-up.
  - `:tabs`          — vector of variant ids; one shown at a time via tabs.
  - `:variants-grid` — devcards-style: every registered variant of one
                       story side-by-side. Enumerates from the registry.
  - `:prose`         — markdown / hiccup blocks interleaved with variants.
  - `:custom`        — caller-supplied view id.

  Stage 4 ships every layout's resolver as pure data (so JVM tests cover
  enumeration + filtering) and the Reagent renderer for `:grid`,
  `:variants-grid`, and `:prose`. `:tabs` and `:custom` ship the
  resolver; their renderers are simple variants of the `:grid`
  pipeline.

  ## Pure layout resolution

  - `resolve-layout` — given a workspace body + registered variants,
    return the ordered vector of cell descriptors. Each cell is one of:
    - `{:type :variant :variant-id ...}`
    - `{:type :prose :body \"...\"}`
    Used by `:grid` / `:variants-grid` / `:prose` / `:tabs`.

  Per IMPL-SPEC §2.8.4 the `:variants-grid` layout is the v1 devcards-
  style multi-variant pane."
  (:require [re-frame.story.registrar :as registrar]
            #?@(:cljs [[reagent.core :as r]
                       [re-frame.core :as rf]
                       [re-frame.story.args :as args]
                       [re-frame.story.config :as config]
                       [re-frame.story.decorators :as decorators]
                       [re-frame.story.runtime :as runtime]
                       ;; canvas/frame-provider-ns-safe is the
                       ;; namespace-preserving frame-provider variant the
                       ;; canvas uses to avoid Reagent's `:>` interop
                       ;; calling `(name kw)` and dropping the namespace
                       ;; off the variant frame keyword (rf2-c5jz path
                       ;; under the rf2-zme7 fix). Variant cells re-use
                       ;; it for the same reason: variant frames have
                       ;; namespaced ids of the form `:story.x/y`, and a
                       ;; namespace-dropping provider would scope the
                       ;; subtree to `:y` — a frame that does not exist.
                       [re-frame.story.ui.canvas :as canvas]
                       [re-frame.story.ui.state :as state]])))

;; ---- pure: layout resolution --------------------------------------------

(defn- variants-of-story
  "Return the variant ids whose parent story is `story-id`. Pure
  derivation against the registrar — used by `:variants-grid`."
  [story-id]
  (registrar/variants-of story-id))

(defn resolve-layout
  "Given a workspace body, return an ordered vector of cell descriptors.

  Cell shapes:
    `{:type :variant :variant-id <kw>}`
    `{:type :prose   :body <string>}`

  Cell ordering matches the workspace's declared `:variants` (for `:grid`
  / `:tabs`) or `:content` (for `:prose`). For `:variants-grid` the
  cells enumerate the registry's variants for the workspace's anchor
  story; the workspace body's `:story` slot names that anchor (defaults
  to the workspace id's namespace path stripped of `Workspace.`)."
  [workspace-id workspace-body]
  (case (:layout workspace-body)
    :grid
    (vec (for [vid (:variants workspace-body)]
           {:type :variant :variant-id vid}))

    :tabs
    (vec (for [vid (:variants workspace-body)]
           {:type :variant :variant-id vid}))

    :variants-grid
    (let [anchor (or (:story workspace-body)
                     ;; Derive `:story.<path>` from `:Workspace.<path>/<name>`.
                     (when-let [ns (namespace workspace-id)]
                       (when (and (>= (count ns) 10)
                                  (= (subs ns 0 10) "Workspace."))
                         (keyword (str "story." (subs ns 10))))))]
      (if anchor
        (->> (variants-of-story anchor)
             sort
             (mapv (fn [vid] {:type :variant :variant-id vid})))
        []))

    :prose
    (vec (for [item (:content workspace-body)]
           (case (:type item)
             :prose   {:type :prose :body (:body item)}
             :variant {:type :variant :variant-id (:id item)}
             nil)))

    :custom
    [{:type :custom :render (:render workspace-body)}]

    ;; unknown — degrade to empty
    []))

;; ---- styling -------------------------------------------------------------

#?(:cljs
   (def ^:private styles
     {:wrap          {:padding "16px"
                      :background "#1e1e1e"
                      :flex "1"
                      :overflow "auto"}
      :title         {:font-weight "bold"
                      :color "#9cdcfe"
                      :font-family "monospace"
                      :margin-bottom "12px"}
      :grid          {:display "grid"
                      :grid-template-columns "repeat(auto-fit, minmax(280px, 1fr))"
                      :gap "12px"}
      :cell          {:background "#252526"
                      :border "1px solid #3c3c3c"
                      :border-radius "4px"
                      :padding "8px"
                      :min-height "160px"
                      :color "#cccccc"
                      :font-family "monospace"
                      :font-size "11px"}
      :cell-title    {:color "#dcdcaa"
                      :font-weight "bold"
                      :margin-bottom "4px"}
      :prose-block   {:padding "12px"
                      :background "#252526"
                      :color "#cccccc"
                      :border-radius "4px"
                      :margin-bottom "12px"
                      :line-height "1.5"}
      :prose-flow    {:display "flex"
                      :flex-direction "column"}
      :empty         {:color "#9a9a9a"
                      :font-style "italic"
                      :padding "24px"
                      :text-align "center"}}))

;; ---- the Reagent renderer ------------------------------------------------

#?(:cljs
   (defn- variant-component-id
     "Resolve the variant's `:component` view id — variant body first,
     falling back to the parent story (per IMPL-SPEC §3.1, the parent
     story usually carries the `:component`)."
     [variant-id]
     (let [vb       (registrar/handler-meta :variant variant-id)
           story-id (args/parent-story-id variant-id)
           sb       (when story-id
                      (registrar/handler-meta :story story-id))]
       (or (:component vb) (:component sb)))))

#?(:cljs
   (defn- run-variant-with-shell-opts!
     "Drive `run-variant` for `variant-id` with the current shell
     state's modes / cell overrides / substrate. Mirrors
     `canvas/run-with-shell-opts!`; reproduced here so the workspace
     mounts each cell's frame independently of which variant the canvas
     happens to have last rendered."
     [variant-id]
     (let [shell @state/shell-state-atom
           opts  {:active-modes   (:active-modes shell)
                  :cell-overrides (get-in shell [:cell-overrides variant-id])
                  :substrate      (:substrate shell)
                  :render?        true}]
       (runtime/run-variant variant-id opts)
       nil)))

#?(:cljs
   (defn- variant-cell-inner
     "Read the variant's resolved view + decorator pack + effective
     args and render the variant body inside the cell. Mirrors
     `canvas/canvas-inner` at a smaller scale — single substrate, no
     share affordance, errors render inline.

     Per spec/007 §Relationship-with-frames + tools/story
     feature-set §4.2: each variant cell wraps the rendered view in a
     `frame-provider` scoped to the variant id, so the view's
     subscribe / dispatch (resolved via React context at render time)
     target the per-variant frame the runtime allocated. Without the
     wrap, subscriptions fall through to `:rf/default` and every cell
     reads the same app-db — which is why the four counter cards
     previously rendered identically (or empty when `:rf/default`
     carries no `:count`)."
     [variant-id]
     (let [view-id        (variant-component-id variant-id)
           shell          @state/shell-state-atom
           decorator-pack (decorators/resolve-decorators variant-id)
           eff-args       (args/resolve-args
                            variant-id
                            {:active-modes   (:active-modes shell)
                             :cell-overrides (get-in shell
                                                     [:cell-overrides
                                                      variant-id])})
           assertions     (runtime/read-assertions variant-id)
           errors         (:errors decorator-pack)]
       ;; Per rf2-9la06: stamp `data-test-variant` on each cell so
       ;; Playwright specs can disambiguate workspace cells from each
       ;; other and from the canvas's variant when both are mounted
       ;; (sidebar `:selected-variant` and `:selected-workspace` are
       ;; independent slots; one doesn't clear the other on selection).
       [:div {:style (:cell styles)
              :data-test-variant (pr-str variant-id)}
        [:div {:style (:cell-title styles)}
         [:span (pr-str variant-id)]
         (when view-id
           [:span {:style {:color "#b0b0b0" :margin-left "8px"
                           :font-weight "normal"}}
            (str "→ " (pr-str view-id))])]
        (cond
          (nil? view-id)
          [:div {:style {:color "#b0b0b0" :font-style "italic"
                         :padding "8px 0"}}
           "variant has no :component registered — register one on the story or variant body"]

          :else
          (let [resolved-view (rf/view view-id)]
            (if resolved-view
              ;; Scope the rendered view's subscribe / dispatch to the
              ;; variant's allocated frame via the namespace-preserving
              ;; provider exported by canvas. The cells in a workspace
              ;; share a parent React tree, so per-cell wraps are the
              ;; load-bearing isolation — without them every cell
              ;; subscribes against whichever frame happened to be the
              ;; React-context default at the workspace's mount site,
              ;; and the variant body's :counter/initialise dispatches
              ;; (which DID route to the variant's frame) become
              ;; invisible to the view. Plain `rf/frame-provider` goes
              ;; through Reagent's `:>` interop which calls `(name kw)`
              ;; on prop values and drops the namespace before React
              ;; sees it; `canvas/frame-provider-ns-safe` bypasses that
              ;; via a direct `React.createElement` call.
              ;; Per rf2-qgms1: stamp `data-rf-story-variant-root` on
              ;; the immediate wrapper around the decorated view (same
              ;; reason as canvas.cljs) so the a11y panel can scope
              ;; axe-core to ONLY the variant's rendered tree.
              [canvas/frame-provider-ns-safe {:frame variant-id}
               [:div {:data-rf-story-variant-root (pr-str variant-id)}
                (canvas/safe-decorated-view
                  [resolved-view eff-args]
                  (:hiccup decorator-pack)
                  eff-args)]]
              [:div {:style {:color "#b0b0b0" :font-style "italic"
                             :padding "8px 0"}}
               (str ":component " (pr-str view-id)
                    " is not registered as a view")])))
        (when (seq errors)
          [:div {:style {:background "#5a1d1d"
                         :border "1px solid #be4040"
                         :color "#fdd"
                         :padding "6px"
                         :margin-top "6px"
                         :font-size "10px"
                         :border-radius "3px"}}
           [:div "Decorator errors:"]
           (for [[i e] (map-indexed vector errors)]
             ^{:key i}
             [:div (pr-str e)])])
        (when (seq assertions)
          [:div {:style {:margin-top "6px"}}
           (for [[i a] (map-indexed vector assertions)]
             ^{:key i}
             [:div {:style {:padding "2px 6px"
                            :border-left "3px solid #be4040"
                            :margin "2px 0"
                            :background "#332"
                            :font-size "10px"}}
              (pr-str a)])])])))

#?(:cljs
   (defn- variant-cell
     "Reagent component for one variant cell. Pre-allocates the
     variant's frame and dispatches its `:events` slot SYNCHRONOUSLY
     before the first render, then re-runs on each
     `:hot-reload-tick` bump so the cell stays in sync with fingerprint
     drift.

     Per rf2-zme7: the pre-allocation must happen before any subscribe
     deref. A `:component-did-mount` hook fires AFTER React's first
     commit — by which point the view body has already called
     `(subscribe [:count])` against a non-existent frame and
     `(deref nil)` has thrown `IDeref.-deref defined for type null`,
     blanking the shell. `r/with-let` runs its bindings exactly once
     per mount, before the body — that's the right hook for the
     synchronous pre-allocation. `run-variant` allocates the frame and
     drains `:events` synchronously via `dispatch-sync`, so by the
     time the inner view renders the variant's app-db has its
     initialised state.

     For canvas-resident single-variant mode the equivalent pre-
     allocation lives on the selection-watcher in shell.cljs
     (`ensure-variant-frame!`); workspace mode has no shell-level edge
     to hang off (one selection drives N variants), so the cell itself
     owns the pre-allocation.

     The per-cell `last-tick` atom tracks the most-recent
     `:hot-reload-tick` value seen by THIS cell: re-running
     `run-variant` only when the tick advances avoids re-dispatching
     the variant's `:events` on every re-render — which would otherwise
     reset state on every user interaction (an inc click re-renders the
     cell, an unconditional re-run of `:events` would clobber the
     count back to its initial value)."
     [variant-id]
     (r/with-let [_init     (run-variant-with-shell-opts! variant-id)
                  last-tick (atom 0)]
       (let [shell @state/shell-state-atom
             tick  (or (:hot-reload-tick shell) 0)]
         (when (> tick @last-tick)
           (reset! last-tick tick)
           (run-variant-with-shell-opts! variant-id))
         [variant-cell-inner variant-id]))))

#?(:cljs
   (defn- prose-block
     [body]
     [:div {:style (:prose-block styles)}
      body]))

#?(:cljs
   (defn workspace-view
     "Render a workspace. Resolves the cells and dispatches per layout."
     [workspace-id]
     (let [body (registrar/handler-meta :workspace workspace-id)]
       (cond
         (nil? body)
         ;; Per rf2-xc65: workspace wrap is a scrollable container —
         ;; `tab-index "0"` + aria-label make it focusable and named so
         ;; axe-core's scrollable-region-focusable rule passes. The
         ;; `<section>` lives inside the shell's <main> landmark.
         [:section {:style      (:wrap styles)
                    :aria-label "Workspace"
                    :tab-index  "0"}
          [:div {:style (:empty styles)}
           "workspace " (pr-str workspace-id) " is not registered"]]

         :else
         (let [cells (resolve-layout workspace-id body)]
           [:section {:style      (:wrap styles)
                      :aria-label (str "Workspace " (pr-str workspace-id))
                      :tab-index  "0"}
            [:div {:style (:title styles)}
             (str (pr-str workspace-id)
                  " (" (name (:layout body)) ")")]
            (cond
              (empty? cells)
              [:div {:style (:empty styles)}
               "no cells resolved — check the workspace body"]

              (= :prose (:layout body))
              [:div {:style (:prose-flow styles)}
               (for [[i cell] (map-indexed vector cells)]
                 (case (:type cell)
                   :prose
                   ^{:key (str "p-" i)}
                   [prose-block (:body cell)]
                   :variant
                   ^{:key (str "v-" i)}
                   [variant-cell (:variant-id cell)]))]

              :else
              [:div {:style (:grid styles)}
               (for [[i cell] (map-indexed vector cells)]
                 (case (:type cell)
                   :variant
                   ^{:key (str "v-" i)}
                   [variant-cell (:variant-id cell)]
                   :prose
                   ^{:key (str "p-" i)}
                   [prose-block (:body cell)]
                   :custom
                   ^{:key (str "c-" i)}
                   [:div {:style (:cell styles)}
                    "custom render: " (pr-str (:render cell))]
                   nil))])])))))
