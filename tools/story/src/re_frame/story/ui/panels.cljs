(ns re-frame.story.ui.panels
  "Built-in story panels — the registry-resolved chrome slots Stage 6
  (rf2-zhwd) ships with v1.0.

  ## Panel registration contract

  Per IMPL-SPEC §3.1 + §4.5 (Stage 6 addition) — a story panel is
  registered via:

      (story/reg-story-panel <panel-id>
        {:doc          \"...\"
         :title        \"...\"             ;; sidebar / tab label
         :placement    :right|:left|:bottom|:top|:modal
         :render       <view-id>          ;; registered view that renders the panel
         :enabled-when (optional)         ;; predicate fn from registry → boolean
         :for          #{<context-id>}})  ;; optional: contexts the panel belongs to

  The shell's panel-host component reads `(story/registrations :story-panel)`
  + the shell's `:panel-visibility` map and renders every visible
  panel into its `:placement` slot. The `:render` view receives the
  current variant-id as its single arg.

  Stage 6 wires this for the v1.0 panels:

  - `:rf.story.panel/a11y`   — axe-core scanner scoped to the VARIANT
                                tree (see `re-frame.story.ui.a11y`).
  - `:rf.story.panel/chrome-a11y` — axe-core scanner scoped to the
                                Story CHROME root, `[data-rf-story-root]`
                                (rf2-18t6p; see
                                `re-frame.story.ui.chrome-a11y`). Sibling
                                of `:a11y`; shares the engine + opt-in.
  - `:rf.story.panel/epoch`  — re-frame-10x epoch panel STUB (this ns;
                                placeholder until Causa ships v1.0).
  - `:rf.story.panel/layout-debug` — the three layout-debug decorator
                                toggles, hosted as a controls-style chrome.

  ## 10x epoch panel — STUB

  Per IMPL-SPEC §2.7 + §2.8.9 the 10x epoch panel ships in v1.0 as a
  `reg-story-panel` registration. The actual successor
  (Causa — `tools/causa/`, bd rf2-buor) is in design. Stage 6 ships a
  stub view that documents the contract; the live embed lands when
  Causa publishes its panel-render view id.

  When Causa is ready, the stub registration replaces with:

      (story/reg-story-panel :rf.story.panel/epoch
        {:title \"Epochs (10x)\"
         :placement :bottom
         :render :rf.epoch-10x/panel-view})

  …and the late-bind look-up in `re-frame.core/view` finds the panel
  view registered by the Causa library when present, otherwise the
  stub view registered here."
  (:require [reagent.core :as r]
            [re-frame.core :as rf]
            [re-frame.story.config :as config]
            [re-frame.story.args :as args]
            [re-frame.story.layout-debug :as layout-debug]
            [re-frame.story.registrar :as story-registrar]
            [re-frame.story.ui.a11y :as a11y]
            [re-frame.story.ui.canvas :as canvas]
            [re-frame.story.ui.chrome-a11y :as chrome-a11y]
            [re-frame.story.ui.schema-validation :as schema-validation]
            [re-frame.story.theme.typography :as typography :refer [mono-stack]]
            [re-frame.story.theme.colors :as colors]))

;; ---- styling -------------------------------------------------------------

(def ^:private styles
  {:stub-wrap    {:padding "12px"
                  :background (:bg-2 colors/tokens)
                  :color (:text-primary colors/tokens)
                  :font-family mono-stack
                  :font-size (:caption typography/type-scale)
                  :border-top "1px solid #444"}
   :stub-title   {:color (:info colors/tokens)
                  :font-weight "bold"
                  :margin-bottom "8px"
                  :text-transform "uppercase"
                  :font-size (:micro typography/type-scale)
                  :letter-spacing "0.5px"}
   :stub-body    {:color (:text-secondary colors/tokens)
                  :line-height "1.5"}
   :stub-code    {:padding "8px"
                  :background (:bg-canvas colors/tokens)
                  :border "1px solid #444"
                  :border-radius "3px"
                  :margin-top "8px"
                  :font-size (:micro typography/type-scale)
                  :overflow-x "auto"}
   :layout-wrap  {:padding "8px"
                  :background (:bg-2 colors/tokens)
                  :color (:text-primary colors/tokens)
                  :font-family mono-stack
                  :font-size (:caption typography/type-scale)
                  :border-top "1px solid #444"}
   :layout-title {:font-weight "bold"
                  :color (:text-secondary colors/tokens)
                  :text-transform "uppercase"
                  :font-size (:micro typography/type-scale)
                  :letter-spacing "0.5px"
                  :margin-bottom "6px"}
   :toggle       {:display "flex"
                  :align-items "center"
                  :gap "8px"
                  :padding "4px 0"
                  :cursor "pointer"
                  :user-select "none"}
   :decor-id     {:color (:info colors/tokens)}
   :hint         {:color (:text-tertiary colors/tokens)
                  :font-style "italic"
                  :font-size (:micro typography/type-scale)
                  :margin-top "6px"}})

;; ---- 10x epoch panel stub ----------------------------------------------

(def ^:const epoch-panel-id
  "Story-panel id for the 10x epoch panel embed. Per IMPL-SPEC §2.7."
  :rf.story.panel/epoch)

(def ^:const epoch-panel-render-id
  "View id the panel registration points at. The actual view (this ns
  ships a stub; Causa replaces with the live panel via the same id
  later) is registered as a re-frame view so the late-bind lookup in
  `re-frame.core/view` finds whichever ships."
  :rf.story.panel/epoch-view)

(defn epoch-stub-view
  "STUB view for the 10x epoch panel. Per IMPL-SPEC §2.7 + Stage 6
  (rf2-zhwd) — displays a contract description until Causa
  (`tools/causa/`, rf2-buor) ships its panel view.

  The stub registration is the deliverable for Stage 6; the live view
  replaces the same registered view id when Causa publishes."
  [_variant-id]
  [:div {:style (:stub-wrap styles)}
   [:div {:style (:stub-title styles)} "Epochs (10x) — stub"]
   [:div {:style (:stub-body styles)}
    "The 10x epoch panel will be embedded here once Causa ships v1.0."]
   [:div {:style (:stub-body styles)}
    "Stage 6 (rf2-zhwd) ships the panel-registration contract; the live "
    "view will register under the same id (" (pr-str epoch-panel-render-id)
    ") when " [:code "day8/re-frame2-10x"] " is on the classpath."]
   [:pre {:style (:stub-code styles)}
    (str "(story/reg-story-panel " epoch-panel-id "\n"
         "  {:title     \"Epochs (10x)\"\n"
         "   :placement :bottom\n"
         "   :render    " epoch-panel-render-id "})")]])

;; ---- layout-debug controls panel ---------------------------------------

(def ^:const layout-debug-panel-id
  :rf.story.panel/layout-debug)

(def ^:const layout-debug-render-id
  :rf.story.panel/layout-debug-view)

(defonce
  ^{:doc "Per-variant layout-debug toggle state. `{variant-id →
         #{decorator-id ...}}`. Reagent atom for reactive renders.

         The state is informational at Stage 6 — the toggle records
         which decorators the author *wants* applied at the variant
         level. The Stage-6-or-later canvas can read this and merge
         it into the resolved decorator stack on the fly."}
  layout-debug-toggles
  (r/atom {}))

(defn toggle-layout-debug!
  [variant-id decorator-id]
  (swap! layout-debug-toggles update variant-id
         (fn [s] (let [s (or s #{})]
                   (if (contains? s decorator-id)
                     (disj s decorator-id)
                     (conj s decorator-id))))))

(defn active-layout-debug-decorators
  "Return the set of layout-debug decorator ids the user has toggled
  on for `variant-id`. Pure read."
  [variant-id]
  (get @layout-debug-toggles variant-id #{}))

(defn layout-debug-view
  "Layout-debug controls panel. Lists the three layout-debug decorators
  with toggles so the user can enable / disable each at runtime without
  touching variant body.

  Form-2 (per rf2-4t5u): the render fn returned by the outer fn derefs
  `layout-debug-toggles` directly inside the body. The form-2 shape
  ensures Reagent's reaction-tracking observes the deref at render
  time — under the registered-view path the user fn is wrapped by
  `reg-view*`'s `frame-aware-view`, and the panel-host additionally
  wraps it in `frame-provider-ns-safe` (which calls `r/as-element`
  on the child). The form-1 shape worked in isolation but didn't
  re-render through the wrapper chain; the form-2 inner-fn shape is
  the canonical Reagent idiom that survives nested wrapping.

  Also (rf2-4t5u): the toggle event is wired via `:on-change` on the
  `<input>` (not `:on-click` on the surrounding `<label>`). Clicking
  the label propagates an implicit click to the contained input, and
  a label-side `:on-click` ALSO sees that bubbled click — so the
  state was being toggled twice per user click and the rendered DOM
  reverted to its prior shape (the bug's outerHTML-byte-identical
  symptom). Wiring `:on-change` on the input is the controlled-
  checkbox idiom and fires exactly once per user click."
  [_variant-id]
  (fn [variant-id]
    (let [active @layout-debug-toggles
          on-for (get active variant-id #{})]
      [:div {:style (:layout-wrap styles)}
       [:div {:style (:layout-title styles)} "Layout-debug"]
       (for [id [layout-debug/id-measure
                 layout-debug/id-outline
                 layout-debug/id-pseudo]]
         ^{:key id}
         [:label {:style (:toggle styles)}
          [:input {:type      "checkbox"
                   :checked   (boolean (contains? on-for id))
                   :on-change (fn [_] (when variant-id
                                        (toggle-layout-debug! variant-id id)))}]
          [:span {:style (:decor-id styles)} (str id)]])
       [:div {:style (:hint styles)}
        "toggle adds the decorator at variant render time (per IMPL-SPEC §2.8.6)"]])))

;; ---- registration -------------------------------------------------------

(defn- reg-view!
  "Register a panel-render view. Idempotent."
  [view-id render-fn]
  (rf/reg-view* view-id render-fn))

(defn install-canonical-panels!
  "Register the Stage 6 (rf2-zhwd) v1.0 panel set:

  - `:rf.story.panel/epoch` — the 10x epoch panel embed STUB.
  - `:rf.story.panel/layout-debug` — the layout-debug toggle panel.

  The a11y panel registers separately via
  `re-frame.story.ui.a11y/install-canonical-a11y!` (so axe-core's
  lazy-load contract stays in its own module). The chrome-a11y panel
  (rf2-18t6p) registers via
  `re-frame.story.ui.chrome-a11y/install-canonical-chrome-a11y!`
  alongside it — same engine, distinct scope.

  Idempotent. Production builds with `:rf.story/enabled?` false skip
  registration."
  []
  (when config/enabled?
    ;; 10x epoch panel STUB.
    (reg-view! epoch-panel-render-id epoch-stub-view)
    (story-registrar/reg-story-panel*
      epoch-panel-id
      {:doc       "re-frame-10x epoch panel embed (stub until Causa v1.0)."
       :title     "Epochs (10x)"
       :placement :bottom
       :render    epoch-panel-render-id})
    ;; Layout-debug controls panel.
    (reg-view! layout-debug-render-id layout-debug-view)
    (story-registrar/reg-story-panel*
      layout-debug-panel-id
      {:doc       "Layout-debug decorator toggles (measure / outline / pseudo)."
       :title     "Layout-debug"
       :placement :right
       :render    layout-debug-render-id})
    ;; A11y panel — registers in its own ns.
    (a11y/install-canonical-a11y!)
    ;; Chrome-a11y panel (rf2-18t6p) — sibling of the variant a11y
    ;; panel scoped to `[data-rf-story-root]` so Story dogfoods axe-
    ;; core against its OWN chrome (the variant a11y panel scopes to
    ;; the variant tree only, per rf2-qgms1).
    (chrome-a11y/install-canonical-chrome-a11y!)
    ;; Schema-validation panel (rf2-dvue) — registers in its own ns
    ;; so the late-bind validator lookup stays isolated.
    (schema-validation/install!)))

;; ---- panel host ---------------------------------------------------------

(def ^:private host-styles
  {:right-host  {:display "flex" :flex-direction "column"}
   :bottom-host {:border-top "1px solid #444"
                 :background (:bg-2 colors/tokens)}
   :panel-head  {:display "flex"
                 :justify-content "space-between"
                 :align-items "center"
                 :padding "4px 10px"
                 :background (:bg-2 colors/tokens)
                 :border-bottom "1px solid #444"
                 :color (:text-secondary colors/tokens)
                 :font-family mono-stack
                 :font-size (:micro typography/type-scale)
                 :text-transform "uppercase"
                 :letter-spacing "0.5px"}})

(defn- panel-applies-to-variant?
  "True when a panel's optional `:for` set matches the focused variant
  or its parent story. Panels with no `:for` are global. Without this
  filter, project-specific panels leak into unrelated stories and their
  frame-scoped subscriptions can crash against missing app-db shape."
  [body variant-id]
  (let [targets (:for body)]
    (or (empty? targets)
        (contains? targets variant-id)
        (contains? targets (args/parent-story-id variant-id)))))

(defn render-panels-at-placement
  "Render every registered `:story-panel` whose `:placement` matches
  `placement` and whose visibility flag in the shell state is truthy.
  Stage 6 (rf2-zhwd).

  Per rf2-zme7: a panel's `:render` view often subscribes against the
  active variant's app-db (e.g. `:counter-with-stories.views/parity-
  badge` reads `:count-parity` from the variant's frame). The panel
  view runs OUTSIDE the canvas's React subtree, so it needs its own
  `frame-provider` scoped to `variant-id` — otherwise `(subscriber)`
  captures `:rf/default` and the deref returns nil, throwing in the
  view. We wrap each panel in `frame-provider {:frame variant-id}`.

  Returns a hiccup vector wrapping the resolved panels."
  [placement variant-id panel-visibility]
  (let [panels (story-registrar/registrations :story-panel)
        slots  (->> panels
                    (filter (fn [[pid body]]
                              (and (= placement (:placement body))
                                   (panel-applies-to-variant? body variant-id)
                                   ;; default visible unless explicit false
                                   (let [vis (get panel-visibility pid)]
                                     (or (nil? vis) (true? vis))))))
                    (sort-by (fn [[pid _]] (str pid))))]
    [:div
     (for [[pid body] slots]
       (let [view-id (:render body)
             view-fn (rf/view view-id)]
         ^{:key pid}
         [:div
          [:div {:style (:panel-head host-styles)}
           [:span (:title body)]
           [:span {:style {:color (:text-tertiary colors/tokens)}} (str pid)]]
          (if view-fn
            ;; rf2-zme7: scope the panel view's subscribe / dispatch to
            ;; the active variant's frame. The namespace-preserving
            ;; provider (canvas/frame-provider-ns-safe) keeps the
            ;; `:story.x/y`-shaped variant-id intact across the React
            ;; context boundary (rf2-c5jz).
            [canvas/frame-provider-ns-safe {:frame variant-id}
             [view-fn variant-id]]
            [:div {:style {:padding "8px" :color (:text-secondary colors/tokens)
                           :font-style "italic" :font-size (:micro typography/type-scale)}}
             (str "panel " (pr-str pid)
                  " has no registered :render view (" (pr-str view-id) ")")])]))]))
