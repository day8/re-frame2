(ns day8.re-frame2-causa.static.machines.definition-detail
  "Right pane of the Static Machines sub-tab — header + 4-mode sub-strip
  + per-mode body (rf2-o5f5f.2).

  ## Header

  Per the bead's §Definition detail header:

      <machine-id> · <source-coord ↗> · <N> states · <M> live (→ Dynamic)

  ## 4-mode sub-strip

  Per the bead's §4-mode sub-strip:

      [Topology][Sim][Instances][Cascade]

  Topology is the default; Sim renders the hermetic 'what-if'
  simulator (rf2-r4nao rehost — engine originally rf2-v869p);
  Instances is a JUMP to the Dynamic Machines tab (`instances_jump`);
  Cascade is GREYED with a 'Dynamic-only' tooltip. Mnemonics
  `t`/`s`/`i`/`c` are surfaced in each pill's `title` — the keybinding
  wiring is follow-on per the bead.

  ## Per-mode body

  Topology mounts the `chart/svg` SVG renderer (no live highlight —
  Static is event-INDEPENDENT). Sim mounts the rehosted Sim rail
  (rf2-r4nao). Instances doesn't render a body (the click is the
  surface). Cascade doesn't render a body either (the pill itself is
  the surface).

  ## Empty state (no machine selected)

  When `(rf/machines)` returns nothing the browse-list renders the
  empty-state hint; the right pane mounts a matching empty surface
  to keep the visual balance."
  (:require [re-frame.core :as rf]
            [day8.re-frame2-causa.open-in-editor :as open-in-editor]
            [day8.re-frame2-causa.static.machines.cascade-dimmed
             :as cascade-dimmed]
            [day8.re-frame2-causa.static.machines.helpers :as h]
            [day8.re-frame2-causa.static.machines.instances-jump
             :as instances-jump]
            [day8.re-frame2-causa.static.machines.sim :as sim]
            [day8.re-frame2-causa.static.machines.topology :as topology]
            [day8.re-frame2-causa.theme.tokens
             :as t
             :refer [tokens sans-stack mono-stack display-stack type-scale]]))

;; ---- header -------------------------------------------------------------

(defn- header
  "Render the definition-detail header per the bead's §Definition
  detail header. Sticks at the top of the right pane."
  [{:keys [machine-id source-coord state-count live-count]}]
  [:header {:data-testid "rf-causa-static-machines-detail-header"
            :data-machine-id (str machine-id)
            :style {:display       "flex"
                    :align-items   "center"
                    :gap           "10px"
                    :padding       "12px 16px"
                    :background    (:bg-2 tokens)
                    :border-bottom (str "1px solid " (:border-subtle tokens))
                    :font-family   sans-stack
                    :font-size     (:body type-scale)}}
   ;; Per rf2-rb6js / rf2-6xezz — definition-detail title renders at
   ;; body type-scale (not `:display`) so the focused machine-id chip
   ;; reads as in-panel chrome rather than a top-level heading. The
   ;; testid is preserved so existing tests still locate the element.
   [:div {:data-testid "rf-causa-static-machines-detail-title"
          :style (merge {:margin    0
                         :font-size (:body type-scale)
                         :font-family display-stack
                         :font-weight 600
                         :letter-spacing "-0.01em"
                         :color (:text-primary tokens)}
                        (try (t/accent-stripe-style :machines) (catch :default _ {})))}
    [:span {:style {:font-family mono-stack
                    :color (:accent-violet tokens)}}
     (str machine-id)]]
   (when (some? source-coord)
     [:span {:data-testid "rf-causa-static-machines-detail-source-coord"
             :style {:font-family mono-stack
                     :font-size (:caption type-scale)
                     :color (:text-tertiary tokens)}}
      (h/format-source-coord source-coord)
      " "
      (open-in-editor/open-chip source-coord)])
   [:span {:data-testid "rf-causa-static-machines-detail-state-count"
           :style {:font-family mono-stack
                   :font-size (:caption type-scale)
                   :color (:text-secondary tokens)}}
    (str state-count " state" (when-not (= 1 state-count) "s"))]
   [:span {:data-testid "rf-causa-static-machines-detail-live-count"
           :style {:font-family mono-stack
                   :font-size (:caption type-scale)
                   :color (if (and (number? live-count) (pos? live-count))
                            (:cyan tokens)
                            (:text-tertiary tokens))
                   :margin-left "auto"}}
    (str (or live-count 0) " live")]])

;; ---- sub-strip ----------------------------------------------------------

(defn- topology-pill
  [{:keys [active? on-click]}]
  [:button
   {:data-testid    "rf-causa-static-machines-pill-topology"
    :role           "tab"
    :aria-selected  (if active? "true" "false")
    :on-click       on-click
    :title          "Topology (mnemonic: t)"
    :aria-label     "Topology mode"
    :style {:background    "transparent"
            :border        (str "1px solid "
                                (if active?
                                  (:cyan tokens)
                                  (:border-default tokens)))
            :border-radius "10px"
            :color         (if active? (:cyan tokens) (:text-secondary tokens))
            :cursor        "pointer"
            :font-family   sans-stack
            :font-size     (:caption type-scale)
            :font-weight   (if active? 600 400)
            :padding       "3px 12px"
            :white-space   "nowrap"}}
   "Topology"])

(defn- sub-strip
  "Render the 4-mode pill row. Per the bead's §4-mode sub-strip — the
  strip is the same DOM as the Dynamic sub-strip (muscle-memory
  consistency), but Cascade is dimmed + Sim is a placeholder."
  [{:keys [machine-id sub-mode live-count]}]
  (let [set-mode! (fn [mode]
                    (rf/dispatch
                      [:rf.causa.static.machines/set-sub-mode machine-id mode]
                      {:frame :rf/causa}))]
    [:div {:data-testid "rf-causa-static-machines-sub-strip"
           :role        "tablist"
           :aria-label  "Machine inspection mode"
           :style {:display     "flex"
                   :align-items "center"
                   :gap         "6px"
                   :padding     "8px 16px"
                   :background  (:bg-2 tokens)
                   :border-bottom (str "1px solid " (:border-subtle tokens))}}
     [topology-pill {:active?  (= sub-mode :topology)
                     :on-click (fn [_] (set-mode! :topology))}]
     [sim/pill {:active?  (= sub-mode :sim)
                :on-click (fn [_] (set-mode! :sim))}]
     [instances-jump/pill {:machine-id machine-id
                           :live-count live-count
                           :active?    false}]
     [cascade-dimmed/pill]]))

;; ---- body dispatch ------------------------------------------------------

(defn- body
  "Dispatch to the per-mode body renderer. Topology + Sim render a
  body; Instances + Cascade do not (Instances is a JUMP affordance,
  Cascade is dimmed)."
  [{:keys [sub-mode machine-id definition source-coord]}]
  (case sub-mode
    :topology
    [topology/body {:machine-id   machine-id
                    :definition   definition
                    :source-coord source-coord}]

    :sim
    [sim/body {:machine-id machine-id
               :definition definition}]

    ;; :instances + :cascade — no body. Render an explanatory placeholder
    ;; so the user understands the strip click landed.
    :instances
    [:section {:data-testid "rf-causa-static-machines-instances-body"
               :style {:padding "16px"
                       :color (:text-tertiary tokens)
                       :font-family sans-stack
                       :font-size (:caption type-scale)}}
     "Instances mode JUMPs to the Dynamic Machines tab — Static stays "
     "event-INDEPENDENT. Click the pill again to re-fire the JUMP."]

    :cascade
    [:section {:data-testid "rf-causa-static-machines-cascade-body"
               :style {:padding "16px"
                       :color (:text-tertiary tokens)
                       :font-family sans-stack
                       :font-size (:caption type-scale)}}
     cascade-dimmed/tooltip-text]))

;; ---- empty surface ------------------------------------------------------

(defn- empty-detail []
  [:div {:data-testid "rf-causa-static-machines-detail-empty"
         :style {:padding "24px"
                 :color (:text-tertiary tokens)
                 :font-family sans-stack
                 :font-size (:body type-scale)}}
   "Select a machine on the left to inspect its definition."])

;; ---- main view ----------------------------------------------------------

(rf/reg-view detail
  "L4-right pane — definition detail. Reads:

    - `:rf.causa.static.machines/data` for the selected row +
      enrichment (rows, total, visible, selected-id)
    - `:rf.causa/machine-definitions` for the registrar spec map of
      the selected machine
    - `:rf.causa.static.machines/sub-mode` for the per-machine sub-
      mode (defaults to :topology)

  Per rf2-in6l2 `reg-view`-registered so subscribes resolve to
  `:rf/causa`."
  []
  (let [{:keys [rows selected-id]}
        @(rf/subscribe [:rf.causa.static.machines/data])
        row (some #(when (= selected-id (:machine-id %)) %) rows)
        definitions @(rf/subscribe [:rf.causa/machine-definitions])
        sub-mode @(rf/subscribe [:rf.causa.static.machines/sub-mode selected-id])]
    (if (nil? row)
      (empty-detail)
      (let [{:keys [machine-id state-count live-count source-coord]} row
            definition (get definitions machine-id)]
        [:div {:data-testid "rf-causa-static-machines-detail"
               :data-machine-id (str machine-id)
               :data-sub-mode (str sub-mode)
               :style {:display        "flex"
                       :flex-direction "column"
                       :height         "100%"
                       :background     (:bg-2 tokens)
                       :color          (:text-primary tokens)}}
         [header {:machine-id machine-id
                  :source-coord source-coord
                  :state-count state-count
                  :live-count live-count}]
         [sub-strip {:machine-id machine-id
                     :sub-mode   sub-mode
                     :live-count live-count}]
         [:div {:data-testid "rf-causa-static-machines-detail-body"
                :style {:flex     "1 1 auto"
                        :min-height "0"
                        :overflow "auto"}}
          [body {:sub-mode     sub-mode
                 :machine-id   machine-id
                 :definition   definition
                 :source-coord source-coord}]]]))))
