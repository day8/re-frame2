(ns day8.re-frame2-xray.static.routes.row-expand
  "Per-row inline expand renderer for the Static Routes flat list.

  ## Shape

  Click a row in the flat list → the expand surface unfolds below it
  in-place (no master-detail split). Surfaces:

    - pattern (the registered URL pattern)
    - matched-keys (the pattern's capture names — segment, splat and
      optional-group params — as the registrar compiled them)
    - handler chip (the registered `:on-match` event vector)
    - schema (when `:params` / `:query` Malli schemas are registered)
    - source-coord chip (Xray's `open-in-editor` chip)
    - the hermetic 'Simulate navigation' button — clicking it toggles
      the per-row `simulate_nav/preview` surface (no real dispatch).

  ## Frame discipline

  This ns is the view layer; dispatches target the
  `:rf.xray.static.routes/*` slots. The hermetic preview itself is
  pure data (lives in `routing_helpers`) so JVM tests cover the
  contract.

  ## What the registrar writes

  Both registrar-derived sections read what `reg-route` actually stores
  (rf2-y8doi.22): the capture names are the compiled form's `:names`
  (`(:names (:rf.route/compiled meta))`, the key the framework's own
  readers use), and the source coord is the standard `:file` / `:line`
  pair `reg-route` merges into the metadata (Spec 001), rendered through
  `open-in-editor/open-chip` exactly as the Static Schemas panel renders
  it. Absent → no section / no chip (silent-by-default). The expand used
  to read `:keys` and `:rf.route/registered-at`, neither of which the
  registrar writes, so both were dead against a real host."
  (:require [re-frame.core :as rf]
            [day8.re-frame2-xray.open-in-editor :as open-in-editor]
            [day8.re-frame2-xray.static.routes.simulate-nav :as sim-nav]
            [day8.re-frame2-xray.theme.tokens
             :refer [tokens mono-stack sans-stack]]
            [day8.re-frame2-xray.views.edn-widget :as edn]))

(defn- section-label
  [label]
  [:div {:style {:color          (:text-tertiary tokens)
                 :font-family    sans-stack
                 :font-size      "10px"
                 :text-transform "uppercase"
                 :letter-spacing "0.5px"
                 :margin         "8px 0 2px 0"}}
   label])

(defn- value-block
  "Render an EDN value through the shared cljs-devtools EDN widget
  (spec 007:119 'all values rendered via the cljs-devtools-shaped
  renderer'). Schema / meta values gain expand/collapse, syntax-colouring
  parity, and a per-node copy host. The `testid` wrapper lets per-block
  selectors resolve; the `node-key` is stable per block so expand state
  survives reloads."
  [testid node-key value]
  [:div {:data-testid testid
         :style       {:margin        "0"
                       :padding       "6px 8px"
                       :background    (:bg-1 tokens)
                       :border-left   (str "2px solid " (:border-default tokens))
                       :color         (:text-primary tokens)
                       :font-family   mono-stack
                       :font-size     "11px"
                       :max-height    "200px"
                       :overflow-y    "auto"
                       :overflow-x    "auto"}}
   (edn/inspect value node-key)])

(defn- chip
  [text colour]
  [:span {:style {:display       "inline-block"
                  :padding       "1px 6px"
                  :background    (:bg-3 tokens)
                  :color         colour
                  :border        (str "1px solid " colour)
                  :border-radius "3px"
                  :font-family   mono-stack
                  :font-size     "10px"
                  :margin-right  "4px"}}
   text])

(defn- on-match-summary
  "Compact chip for the `:on-match` event vector (event-id keyword
  only). Returns nil when absent."
  [on-match]
  (when (vector? on-match)
    (let [ev-id (first on-match)]
      [:span {:data-testid "rf-xray-static-routes-on-match-chip"}
       (chip (str ev-id) (:accent tokens))])))

(defn- segment-keys-from-meta
  "The pattern's capture names off the registrar's compiled form —
  `parse-pattern`'s `:names`, as keywords, the keys a match's `:params`
  carries. nil when the compiled form isn't seeded (test fixtures that
  pass bare `{:path ...}` maps), so the view shows no entry."
  [meta]
  (some->> (:rf.route/compiled meta) :names (mapv keyword)))

(defn jump-button
  "Cross-link chip `→ Dynamic Routing` per the parent-epic findings
  §4.4 — fires the cross-link event the registry installs, which flips
  Xray to Dynamic mode on the Routing lens. It does not scope the lens to
  this route (the handler ignores the id), so the title promises only
  the flip (rf2-y8doi.22).

  `dispatch` is threaded from the routes `Panel` boundary
  (this button renders inside the Reagent island and cannot recover the
  frame itself)."
  [dispatch route-id]
   [:button {:data-testid (str "rf-xray-static-routes-jump-runtime-"
                              (subs (pr-str route-id) 1))
            :on-click    (fn [e]
                           ;; Prevent the row-toggle click from
                           ;; bubbling up — the jump is its own action.
                           (.stopPropagation e)
                           (dispatch [:rf.xray.static.routes/jump-to-dynamic
                                      route-id]))
            :title       "Open the Dynamic Routing lens"
            :style       {:background    "transparent"
                          :border        (str "1px solid " (:accent tokens))
                          :border-radius "3px"
                          :color         (:accent tokens)
                          :padding       "1px 6px"
                          :margin-left   "8px"
                          :font-family   sans-stack
                          :font-size     "10px"
                          :cursor        "pointer"
                          :white-space   "nowrap"}}
   "→ Dynamic"])

(defn- sim-nav-toggle
  "The hermetic 'Simulate navigation' button. Toggles the
  `simulate_nav/preview` surface below it. State lives in the
  per-row preview-open set on `:rf.xray.static.routes/sim-nav-open`."
  [dispatch route-id sim-open?]
   [:button {:data-testid (str "rf-xray-static-routes-sim-nav-toggle-"
                              (subs (pr-str route-id) 1))
            :on-click    (fn [e]
                           (.stopPropagation e)
                           (dispatch [:rf.xray.static.routes/toggle-sim-nav
                                      route-id]))
            :style       {:background    (if sim-open? (:bg-active tokens) "transparent")
                          :border        (str "1px solid " (:accent tokens))
                          :border-radius "3px"
                          :color         (:accent tokens)
                          :padding       "2px 8px"
                          :font-family   sans-stack
                          :font-size     "11px"
                          :font-weight   500
                          :cursor        "pointer"
                          :white-space   "nowrap"}}
   (if sim-open? "Hide preview" "Simulate navigation")])

(defn- source-coord-chip
  "The registration's source coord — the `:file` / `:line` / `:ns`
  `reg-route` merges into the metadata — as Xray's `open-in-editor` chip,
  the same one the Static Schemas rows carry. nil when the metadata has
  no `:file` (a programmatic registration, or a production build that
  strips coords)."
  [meta]
  (let [coord (select-keys meta [:file :line :column :ns])]
    (when (:file coord)
      (open-in-editor/open-chip coord))))

(defn render
  "Render the per-row expand surface for `row` (a routing-helpers
  catalogue row). `sim-open?` is true when the hermetic preview is
  toggled open; `routes-map` is threaded down for the preview
  projection."
  [dispatch row {:keys [sim-open? routes-map]}]
  (let [{:keys [route-id path doc meta on-match-event]} row
        on-match (or on-match-event (:on-match meta))
        params   (:params meta)
        query    (:query meta)
        keys     (segment-keys-from-meta meta)]
    [:div {:data-testid (str "rf-xray-static-routes-expand-"
                             (subs (pr-str route-id) 1))
           :style       {:margin       "0 0 8px 24px"
                         :padding      "10px 12px"
                         :background   (:bg-1 tokens)
                         :border-left  (str "2px solid " (:border-default tokens))
                         :font-family  sans-stack
                         :font-size    "12px"
                         :color        (:text-primary tokens)}}
     [:div {:style {:display       "flex"
                    :align-items   "center"
                    :flex-wrap     "wrap"
                    :gap           "6px"
                    :margin-bottom "6px"}}
      (chip (str route-id) (:accent tokens))
      (when path (chip path (:accent tokens)))
      (on-match-summary on-match)
      (source-coord-chip meta)
      [sim-nav-toggle dispatch route-id sim-open?]
      [jump-button dispatch route-id]]
     (when doc
       [:p {:data-testid (str "rf-xray-static-routes-doc-"
                              (subs (pr-str route-id) 1))
            :style       {:margin     "0 0 6px 0"
                          :color      (:text-secondary tokens)
                          :font-size  "11px"
                          :font-style "italic"}}
        doc])
     (when (seq keys)
       [:div {:data-testid (str "rf-xray-static-routes-keys-"
                                (subs (pr-str route-id) 1))
              :style       {:margin "4px 0"}}
        (section-label "Matched keys")
        ;; Matched-keys vector through the shared widget's
        ;; inline current-state renderer (cljs-devtools one-liner).
        [:div {:style {:font-family mono-stack
                       :font-size   "11px"
                       :color       (:text-secondary tokens)}}
         (edn/inspect-inline (vec keys))]])
     (when params
       [:div {:data-testid (str "rf-xray-static-routes-params-schema-"
                                (subs (pr-str route-id) 1))
              :style       {:margin "4px 0"}}
        (section-label ":params schema")
        (value-block (str "rf-xray-static-routes-params-schema-block-"
                          (subs (pr-str route-id) 1))
                     (str "static-routes/" (subs (pr-str route-id) 1) "/params")
                     params)])
     (when query
       [:div {:data-testid (str "rf-xray-static-routes-query-schema-"
                                (subs (pr-str route-id) 1))
              :style       {:margin "4px 0"}}
        (section-label ":query schema")
        (value-block (str "rf-xray-static-routes-query-schema-block-"
                          (subs (pr-str route-id) 1))
                     (str "static-routes/" (subs (pr-str route-id) 1) "/query")
                     query)])
     (section-label "Registrar meta")
     (value-block (str "rf-xray-static-routes-meta-"
                       (subs (pr-str route-id) 1))
                  (str "static-routes/" (subs (pr-str route-id) 1) "/meta")
                  meta)
     (when sim-open?
       [sim-nav/preview routes-map route-id nil])]))
