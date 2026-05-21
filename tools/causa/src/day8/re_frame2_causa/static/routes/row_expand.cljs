(ns day8.re-frame2-causa.static.routes.row-expand
  "Per-row inline expand renderer for the Static Routes flat list
  (rf2-o5f5f.3).

  ## Shape

  Click a row in the flat list → the expand surface unfolds below it
  in-place (no master-detail split). Surfaces:

    - pattern (the registered URL pattern)
    - matched-keys (segment / wildcard / catch-all / optional keys
      derived from the pattern at registration time)
    - handler chip (the registered `:on-match` event vector)
    - schema (when `:params` / `:query` Malli schemas are registered)
    - source-coord chip (`open-in-editor`-style coord)
    - the hermetic 'Simulate navigation' button — clicking it toggles
      the per-row `simulate_nav/preview` surface (no real dispatch).

  ## Frame discipline

  This ns is the view layer; dispatches target the
  `:rf.causa.static.routes/*` slots. The hermetic preview itself is
  pure data (lives in `routing_helpers`) so JVM tests cover the
  contract.

  ## Source coords

  Source-coord rendering reuses Causa's `open-in-editor` chip
  rendering when the registrar meta carries the optional
  `:rf.route/registered-at` slot. Absent → no chip (silent-by-
  default per rf2-g3ghh / rf2-yn86j)."
  (:require [re-frame.core :as rf]
            [day8.re-frame2-causa.static.routes.simulate-nav :as sim-nav]
            [day8.re-frame2-causa.theme.tokens
             :as t
             :refer [tokens mono-stack sans-stack]]
            [day8.re-frame2-causa.views.edn-widget.widget :as edn]))

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
  (rf2-2kwhw — spec 007:119 'all values rendered via the cljs-devtools-
  shaped renderer'). Replaces the prior raw-`pr-str` `[:pre]` block so
  the schema / meta values gain expand/collapse, syntax-colouring
  parity, and the per-node copy host (rf2-f026h). The `testid` wrapper
  is preserved so existing per-block selectors still resolve; the
  `node-key` is stable per block so expand state survives reloads."
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
      [:span {:data-testid "rf-causa-static-routes-on-match-chip"}
       (chip (str ev-id) (:cyan tokens))])))

(defn- segment-keys-from-meta
  "Extract segment keys from the route meta's `:rf.route/compiled`
  parser output when present. Falls back to `nil` so the view shows
  no entry when the compiled form isn't seeded (test fixtures that
  pass bare `{:path ...}` maps)."
  [meta]
  (when-let [compiled (:rf.route/compiled meta)]
    (:keys compiled)))

(defn jump-button
  "Cross-link chip `→ Dynamic Routing` per the parent-epic findings
  §4.4 — fires the cross-link event the registry installs so the
  user lands on the Dynamic Routing lens scoped to this route."
  [route-id]
  [:button {:data-testid (str "rf-causa-static-routes-jump-runtime-"
                              (subs (pr-str route-id) 1))
            :on-click    (fn [e]
                           ;; Prevent the row-toggle click from
                           ;; bubbling up — the jump is its own action.
                           (.stopPropagation e)
                           (rf/dispatch [:rf.causa.static.routes/jump-to-dynamic
                                         route-id]
                                        {:frame :rf/causa}))
            :title       "Open Dynamic Routing scoped to this route"
            :style       {:background    "transparent"
                          :border        (str "1px solid " (:accent-violet tokens))
                          :border-radius "3px"
                          :color         (:accent-violet tokens)
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
  per-row preview-open set on `:rf.causa.static.routes/sim-nav-open`."
  [route-id sim-open?]
  [:button {:data-testid (str "rf-causa-static-routes-sim-nav-toggle-"
                              (subs (pr-str route-id) 1))
            :on-click    (fn [e]
                           (.stopPropagation e)
                           (rf/dispatch [:rf.causa.static.routes/toggle-sim-nav
                                         route-id]
                                        {:frame :rf/causa}))
            :style       {:background    (if sim-open? (:bg-active tokens) "transparent")
                          :border        (str "1px solid " (:cyan tokens))
                          :border-radius "3px"
                          :color         (:cyan tokens)
                          :padding       "2px 8px"
                          :font-family   sans-stack
                          :font-size     "11px"
                          :font-weight   500
                          :cursor        "pointer"
                          :white-space   "nowrap"}}
   (if sim-open? "Hide preview" "Simulate navigation")])

(defn- source-coord-chip
  "Render the `:rf.route/registered-at` source coord when present."
  [meta]
  (when-let [coord (:rf.route/registered-at meta)]
    [:span {:data-testid "rf-causa-static-routes-source-coord"
            :style       {:font-family mono-stack
                          :font-size   "10px"
                          :color       (:text-tertiary tokens)
                          :margin-left "8px"}}
     (str (:file coord) ":" (:line coord))]))

(defn render
  "Render the per-row expand surface for `row` (a routing-helpers
  catalogue row). `sim-open?` is true when the hermetic preview is
  toggled open; `routes-map` is threaded down for the preview
  projection."
  [row {:keys [sim-open? routes-map]}]
  (let [{:keys [route-id path doc meta on-match-event]} row
        on-match (or on-match-event (:on-match meta))
        params   (:params meta)
        query    (:query meta)
        keys     (segment-keys-from-meta meta)]
    [:div {:data-testid (str "rf-causa-static-routes-expand-"
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
      (chip (str route-id) (:accent-violet tokens))
      (when path (chip path (:cyan tokens)))
      (on-match-summary on-match)
      (source-coord-chip meta)
      [sim-nav-toggle route-id sim-open?]
      [jump-button route-id]]
     (when doc
       [:p {:data-testid (str "rf-causa-static-routes-doc-"
                              (subs (pr-str route-id) 1))
            :style       {:margin     "0 0 6px 0"
                          :color      (:text-secondary tokens)
                          :font-size  "11px"
                          :font-style "italic"}}
        doc])
     (when (seq keys)
       [:div {:data-testid (str "rf-causa-static-routes-keys-"
                                (subs (pr-str route-id) 1))
              :style       {:margin "4px 0"}}
        (section-label "Matched keys")
        ;; rf2-2kwhw — matched-keys vector through the shared widget's
        ;; inline current-state renderer (cljs-devtools one-liner).
        [:div {:style {:font-family mono-stack
                       :font-size   "11px"
                       :color       (:text-secondary tokens)}}
         (edn/inspect-inline (vec keys))]])
     (when params
       [:div {:data-testid (str "rf-causa-static-routes-params-schema-"
                                (subs (pr-str route-id) 1))
              :style       {:margin "4px 0"}}
        (section-label ":params schema")
        (value-block (str "rf-causa-static-routes-params-schema-block-"
                          (subs (pr-str route-id) 1))
                     (str "static-routes/" (subs (pr-str route-id) 1) "/params")
                     params)])
     (when query
       [:div {:data-testid (str "rf-causa-static-routes-query-schema-"
                                (subs (pr-str route-id) 1))
              :style       {:margin "4px 0"}}
        (section-label ":query schema")
        (value-block (str "rf-causa-static-routes-query-schema-block-"
                          (subs (pr-str route-id) 1))
                     (str "static-routes/" (subs (pr-str route-id) 1) "/query")
                     query)])
     (section-label "Registrar meta")
     (value-block (str "rf-causa-static-routes-meta-"
                       (subs (pr-str route-id) 1))
                  (str "static-routes/" (subs (pr-str route-id) 1) "/meta")
                  meta)
     (when sim-open?
       [sim-nav/preview routes-map route-id nil])]))
