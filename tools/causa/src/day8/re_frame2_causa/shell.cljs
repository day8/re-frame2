(ns day8.re-frame2-causa.shell
  "The Causa shell — empty-pane layout per tools/causa/spec/007-UX-IA.md.

  Phase 1 ships the *structure* without the panels. Each panel slot
  renders a 'Coming soon — rf2-xxx' stub; the live panel views land in
  subsequent beads under rf2-5aw5v.

  ## Layout

  Per spec/007-UX-IA.md §The five regions:

      ┌─────────────────────────────────────────────────────────┐
      │ ◆ Top strip (56px)                          ⌘K  ?  ✕    │
      ├──────────────┬──────────────────────────────────────────┤
      │              │                                          │
      │  Sidebar     │  Canvas                                  │
      │  (192px)     │  (active panel)                          │
      │              │                                          │
      │              │                                          │
      ├──────────────┴──────────────────────────────────────────┤
      │ Bottom rail (40px)                                      │
      └─────────────────────────────────────────────────────────┘

  ## Frame isolation (rf2-tijr Option C)

  The whole shell is wrapped in `[rf/frame-provider {:frame :rf/causa}
  ...]`. Every `subscribe` / `dispatch` inside the shell resolves to
  the `:rf/causa` frame; the host's `:rf/default` is untouched. Causa
  registrations under `:rf.causa/*` (see registry.cljs) operate
  against `:rf/causa`'s db when called from inside the shell.

  ## Pure hiccup

  Per rf2-tijr the view code is pure hiccup. The substrate adapter's
  render fn (`rf/render`) handles the substrate-specific mount in
  `mount.cljs`. No per-substrate switches in view code."
  (:require [re-frame.core :as rf]))

;; ---- design tokens (dark theme per spec/007-UX-IA.md) --------------------

(def ^:private tokens
  "Colour + size tokens lifted from spec/007-UX-IA.md §Dark theme
  tokens. Phase 1 uses inline styles so the foundation ships without
  a CSS asset pipeline; the v1.0 styling pass replaces these with
  CSS variables when the per-panel beads land."
  {:bg-0          "#0E0F12"
   :bg-1          "#15171B"
   :bg-2          "#1B1E24"
   :bg-active     "#2A2F3D"
   :border-subtle "#232730"
   :border-default "#2F3441"
   :text-primary  "#E8EAF0"
   :text-secondary "#A8AEC0"
   :text-tertiary "#6B7080"
   :accent-violet "#7C5CFF"
   :magenta       "#E879F9"})

;; ---- regions -------------------------------------------------------------

(defn- top-strip
  "Top strip (56px). Per spec/007-UX-IA.md §The five regions item 1:
  causality strip + frame picker + global actions (Issues badge,
  epoch counter, command palette, help, close).

  Phase 1 stub: brand mark + version label + close-affordance text.
  Live causality strip / frame picker / Issues badge land with their
  respective panel beads (rf2-xxx)."
  [_props]
  [:div {:style {:display          "flex"
                 :align-items      "center"
                 :justify-content  "space-between"
                 :height           "56px"
                 :padding          "0 16px"
                 :background       (:bg-1 tokens)
                 :border-bottom    (str "1px solid " (:border-subtle tokens))
                 :color            (:text-primary tokens)
                 :font-family      "Inter, system-ui, -apple-system, Segoe UI, sans-serif"
                 :font-size        "14px"
                 :font-weight      600}}
   [:div {:style {:display "flex" :align-items "center" :gap "12px"}}
    [:span {:style {:color (:accent-violet tokens)}} "◆"]
    [:span "Causa"]
    [:span {:style {:color       (:text-tertiary tokens)
                    :font-size   "12px"
                    :font-weight 400}}
     "Phase 1 (rf2-n6x4q)"]]
   [:div {:style {:display "flex" :align-items "center" :gap "12px"
                  :color    (:text-secondary tokens)
                  :font-size "12px"
                  :font-weight 400}}
    [:span "Ctrl+Shift+C to toggle"]]])

(defn- sidebar
  "Sidebar (192px) — panel navigation + density toggle. Per spec/007-
  UX-IA.md §Sidebar groups three groups (events/app-db/causality/...,
  conditional-with-activity, dormant) divider-separated.

  Phase 1 stub: panel labels with 'Coming soon — rf2-xxx' annotation.
  The hero panel (`:event-detail`, lock #7) is highlighted as active."
  []
  (let [items [{:id :events       :label "Events"        :active? true  :bead "rf2-xxx"}
               {:id :app-db       :label "App-db"        :bead "rf2-xxx"}
               {:id :causality    :label "Causality"     :bead "rf2-xxx"}
               {:id :subs         :label "Subscriptions" :bead "rf2-xxx"}
               {:id :fx           :label "Effects"       :bead "rf2-xxx"}
               {:id :trace        :label "Trace"         :bead "rf2-xxx"}
               {:id :machines     :label "Machines"      :bead "rf2-xxx"}
               {:id :flows        :label "Flows"         :bead "rf2-xxx"}
               {:id :performance  :label "Performance"   :bead "rf2-xxx"}
               {:id :issues       :label "Issues"        :bead "rf2-xxx"}
               {:id :schemas      :label "Schemas"       :bead "rf2-xxx"}
               {:id :hydration    :label "Hydration"     :bead "rf2-xxx"}
               {:id :copilot      :label "Co-pilot"      :bead "rf2-xxx"}]]
    [:nav {:style {:width            "192px"
                   :flex-shrink      0
                   :background       (:bg-1 tokens)
                   :border-right     (str "1px solid " (:border-subtle tokens))
                   :overflow-y       "auto"
                   :font-family      "Inter, system-ui, -apple-system, Segoe UI, sans-serif"
                   :font-size        "13px"
                   :color            (:text-primary tokens)}}
     (into [:ul {:style {:list-style    "none"
                         :margin        0
                         :padding       "8px 0"}}]
           (for [{:keys [id label active?]} items]
             ^{:key id}
             [:li {:style {:padding         "6px 16px"
                           :cursor          "default"
                           :background      (if active? (:bg-active tokens) "transparent")
                           :color           (if active?
                                              (:text-primary tokens)
                                              (:text-secondary tokens))
                           :font-weight     (if active? 600 400)}}
              [:span {:style {:margin-right "8px"
                              :color        (if active?
                                              (:accent-violet tokens)
                                              (:text-tertiary tokens))}}
               (if active? "◉" "○")]
              label]))]))

(defn- canvas
  "Canvas — the active panel's content. Per spec/007-UX-IA.md §The
  default landing view: Events (event-detail hero, lock #7) is the
  default landing.

  Phase 1 stub: the canvas renders the 'Coming soon' message for the
  hero panel slot."
  []
  [:main {:style {:flex             1
                  :overflow         "auto"
                  :padding          "24px"
                  :background       (:bg-2 tokens)
                  :color            (:text-primary tokens)
                  :font-family      "Inter, system-ui, -apple-system, Segoe UI, sans-serif"}}
   [:div {:style {:max-width "640px"}}
    [:h1 {:style {:font-size "16px" :font-weight 600 :margin "0 0 12px 0"
                  :color     (:text-primary tokens)}}
     "Event detail (hero)"]
    [:p {:style {:font-size "14px" :line-height 1.5
                 :color     (:text-secondary tokens)
                 :margin    "0 0 16px 0"}}
     "Coming soon — the event-detail panel is Phase 2 work."
     " Per "
     [:code {:style {:font-family "JetBrains Mono, ui-monospace, SF Mono, Menlo, monospace"
                     :font-size   "13px"
                     :color       (:accent-violet tokens)}}
      "tools/causa/spec/000-Vision.md"]
     ", this panel lands the event vector, the db-diff, the inline"
     " mini-graph, fx fired, subs recomputed, renders, duration."]
    [:p {:style {:font-size "13px" :line-height 1.5
                 :color     (:text-tertiary tokens)
                 :margin    "0 0 16px 0"}}
     "Frame: "
     [:code {:style {:color       (:accent-violet tokens)
                     :font-family "JetBrains Mono, ui-monospace, SF Mono, Menlo, monospace"}}
      ":rf/causa"]
     " (host's "
     [:code {:style {:color       (:text-tertiary tokens)
                     :font-family "JetBrains Mono, ui-monospace, SF Mono, Menlo, monospace"}}
      ":rf/default"]
     " is unaffected)."]
    (let [buf-count (count @(rf/subscribe [:rf.causa/trace-buffer]))]
      [:p {:style {:font-size "13px"
                   :color     (:text-tertiary tokens)
                   :margin    0}}
       "Trace buffer: "
       [:span {:style {:color (:accent-violet tokens)}} buf-count]
       " events collected."])]])

(defn- bottom-rail
  "Bottom rail (40px) — time-travel scrubber + frame info + issues
  badge. Per spec/007-UX-IA.md §The five regions item 5.

  Phase 1 stub: minimal frame-info text. Live scrubber lands with the
  time-travel panel bead."
  []
  [:footer {:style {:height           "40px"
                    :display          "flex"
                    :align-items      "center"
                    :justify-content  "space-between"
                    :padding          "0 16px"
                    :background       (:bg-1 tokens)
                    :border-top       (str "1px solid " (:border-subtle tokens))
                    :color            (:text-tertiary tokens)
                    :font-family      "Inter, system-ui, -apple-system, Segoe UI, sans-serif"
                    :font-size        "12px"}}
   [:span "◀◀  ────●────  ▶▶  (scrubber — rf2-xxx)"]
   [:span "epoch — / —"]])

;; ---- shell view ----------------------------------------------------------

(defn shell-view
  "The full Causa shell. Wraps every panel region in a `:rf/causa`
  frame-provider so descendant `subscribe` / `dispatch` resolve to
  the isolated frame. The shell's outer container is a fixed-position
  overlay along the right edge of the viewport (40% width per
  spec/007-UX-IA.md §Layout)."
  []
  [rf/frame-provider {:frame :rf/causa}
   [:div {:data-testid "rf-causa-shell"
          :style       {:position         "fixed"
                        :top              0
                        :right            0
                        :bottom           0
                        :width            "40%"
                        :min-width        "560px"
                        :display          "flex"
                        :flex-direction   "column"
                        :background       (:bg-0 tokens)
                        :color            (:text-primary tokens)
                        :z-index          2147483000
                        :box-shadow       "rgba(0, 0, 0, 0.4) -8px 0 24px"
                        :font-family      "Inter, system-ui, -apple-system, Segoe UI, sans-serif"
                        :font-size        "14px"
                        :line-height      1.5}}
    [top-strip {}]
    [:div {:style {:flex          1
                   :display       "flex"
                   :flex-direction "row"
                   :overflow      "hidden"}}
     [sidebar]
     [canvas]]
    [bottom-rail]]])
