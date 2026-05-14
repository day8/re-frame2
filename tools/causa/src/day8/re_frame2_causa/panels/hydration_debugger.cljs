(ns day8.re-frame2-causa.panels.hydration-debugger
  "Hydration Debugger panel — Phase 5 (rf2-pzxsr, parent rf2-5aw5v).

  Per `tools/causa/spec/006-Hydration-Debugger.md` this panel is
  **dormant** until at least one `:rf.ssr/hydration-mismatch` trace
  lands. Until then, the sidebar entry shows `◌` (dormant marker) and
  clicking it surfaces the 'No SSR in this app' empty state. On first
  mismatch the panel surfaces a side-by-side server vs client render-
  tree view with the divergent node flagged + the hash-bisector path
  highlighted.

  SSR hydration mismatches are structurally hard to debug: the failing
  client render and the failing server render disagree on a tree, the
  console error is one line, and the divergent node is rarely the one
  the error points to. No other JS devtool surfaces this; re-frame2's
  Spec 011 emits structured data, and this panel renders it.

  ## What this panel shows

  Three states, driven by `:rf.causa/hydration-debugger-data`:

    1. **No SSR in this app** — the buffer carries no
       `:rf.ssr/hydration-mismatch` events. The panel renders an
       empty-state explaining the panel surfaces when hydration
       mismatches fire (per spec §Empty states).

    2. **SSR present, no mismatches** — Phase 5 inherits Schema-
       Timeline's positive-result framing — 'Hydration ran cleanly.
       No mismatches detected on the last hydration.' Per
       Spec 006 §Empty states the second message is deliberately a
       positive result.

       Phase 5 ships *only* the no-mismatches inference based on the
       buffer carrying no mismatch traces; the explicit 'hydration
       happened' signal (a `:rf.ssr/hydration-complete` trace, or the
       `[:rf/hydration :server-hash]` slot on app-db) lands when the
       SSR artefact integration deepens. Until then the
       'No SSR in this app' state is the default; the
       'SSR present, no mismatches' state is reached by the user via
       the panel's `show clean state` affordance (a checkbox in the
       header).

    3. **Mismatches present** — the panel surfaces:
       - A list of mismatches at the top (per spec §Multi-mismatch).
       - A side-by-side server vs client render-tree view.
       - The divergent node flagged with `⚠`; the bisector path
         highlighted root → first differing node.
       - A one-line hypothesis below the side-by-side.
       - The divergent view's source coord (with `(?)` fallback per
         Lock #11).

  ## Pure hiccup (rf2-tijr)

  Same contract as every other Causa panel — the view is pure hiccup,
  no Reagent / UIx / Helix references. Frame isolation comes from the
  enclosing `[rf/frame-provider {:frame :rf/causa}]` in `shell.cljs`.

  ## Helpers

  All pure-data logic — `classify-divergence`, `first-divergence-path`,
  `mismatch-detail`, `re-root` — lives in
  `hydration_debugger_helpers.cljc` so the algebra runs under the JVM
  unit-test target."
  (:require [clojure.string :as str]
            [re-frame.core :as rf]
            [day8.re-frame2-causa.panels.hydration-debugger-helpers :as h]
            [day8.re-frame2-causa.theme.tokens
             :refer [tokens mono-stack sans-stack]]))

;; ---- pure-data render helpers -------------------------------------------

(defn- format-edn
  "Best-effort EDN-like format. Used to render the path + hash + tree
  scalars in the mono columns."
  [v]
  (try
    (pr-str v)
    (catch :default _ (str v))))

(defn- path-segments
  "Render a path vector as `:a > :b > :c` — used in the mismatch list
  to give a human-readable lineage cue."
  [path]
  (if (empty? path)
    "(root)"
    (str/join " > "
                         (map (fn [seg]
                                (cond
                                  (keyword? seg) (str seg)
                                  :else          (str seg)))
                              path))))

;; ---- empty states --------------------------------------------------------

(defn- no-ssr-empty-state
  "Per spec §Empty states — the dormant empty state when no
  hydration-mismatch traces have landed in this session."
  []
  [:div {:data-testid "rf-causa-hydration-debugger-empty-no-ssr"
         :style       {:padding "16px"
                       :color   (:text-tertiary tokens)
                       :font-family sans-stack
                       :font-size "13px"}}
   [:p {:style {:margin "0 0 8px 0" :color (:text-secondary tokens)}}
    "This app does not appear to use SSR hydration."]
   [:p {:style {:margin 0 :font-size "12px"}}
    "The Hydration panel surfaces when "
    [:code {:style {:font-family mono-stack
                    :color       (:accent-violet tokens)}}
     ":rf.ssr/hydration-mismatch"]
    " events fire — see Spec 011."]])

(defn- clean-empty-state
  "Per spec §Empty states — the deliberately-positive 'SSR ran
  cleanly' empty state. Same framing as Schema-Timeline's clean
  message — a clean hydration is the goal, not the absence of a
  result to display."
  []
  [:div {:data-testid "rf-causa-hydration-debugger-empty-clean"
         :style       {:padding "16px"
                       :color   (:text-secondary tokens)
                       :font-family sans-stack
                       :font-size "13px"}}
   [:p {:style {:margin "0 0 8px 0" :color (:green tokens)}}
    "Hydration ran cleanly."]
   [:p {:style {:margin 0 :font-size "12px" :color (:text-tertiary tokens)}}
    "No mismatches detected on the last hydration."]])

;; ---- mismatch list (multi-mismatch case) --------------------------------

(defn- mismatch-list-row
  "One row in the mismatch list. Per spec §Multi-mismatch case clicking
  the row fires `:rf.causa/select-mismatch` so the side-by-side rebases
  to that path."
  [{:keys [id path summary view-id] :as _entry} selected?]
  [:li {:key       id
        :data-testid (str "rf-causa-hydration-mismatch-row-" (str id))
        :on-click  #(rf/dispatch [:rf.causa/select-mismatch id])
        :style     {:padding      "6px 12px"
                    :border-bottom (str "1px solid " (:border-subtle tokens))
                    :background   (if selected? (:bg-active tokens) "transparent")
                    :cursor       "pointer"
                    :font-family  mono-stack
                    :font-size    "12px"
                    :color        (if selected?
                                    (:text-primary tokens)
                                    (:text-secondary tokens))}}
   [:span {:style {:color (:yellow tokens) :margin-right "8px"}} "⚠"]
   [:span {:style {:color (:accent-violet tokens) :margin-right "8px"}}
    (path-segments path)]
   [:span {:style {:color (:text-tertiary tokens)}} summary]
   (when view-id
     [:span {:style {:margin-left "8px"
                     :color (:cyan tokens)}}
      (str "[" view-id "]")])])

(defn- mismatch-list
  "Per spec §Multi-mismatch case — the list at the panel top when the
  buffer carries more than one mismatch. Single-mismatch case still
  renders the list (one entry) for consistency."
  [mismatch-summary selected-id]
  [:div {:data-testid "rf-causa-hydration-mismatch-list"
         :style       {:border-bottom (str "1px solid " (:border-default tokens))
                       :background (:bg-3 tokens)}}
   [:div {:style {:padding "8px 12px"
                  :font-family sans-stack
                  :font-size "12px"
                  :color (:text-tertiary tokens)
                  :border-bottom (str "1px solid " (:border-subtle tokens))}}
    (let [n (count mismatch-summary)]
      (str n " mismatch" (if (= 1 n) "" "es") " in this hydration"))]
   (into [:ul {:style {:list-style "none"
                       :margin     0
                       :padding    0
                       :max-height "160px"
                       :overflow-y "auto"}}]
         (map (fn [entry]
                (mismatch-list-row entry (= (:id entry) selected-id))))
         mismatch-summary)])

;; ---- side-by-side render-tree view --------------------------------------

(defn- hash-chip
  "Render one hash chip — a faint pill the user can click to re-root
  the panel at that node. Per spec §Render-tree hash bisector the
  divergence path is highlighted root → first differing node."
  [{:keys [path hash]} divergent?]
  [:span {:data-testid (str "rf-causa-hydration-hash-chip-"
                            (str/join "-" path))
          :on-click    #(rf/dispatch [:rf.causa/reroot-tree-view path])
          :style       {:display       "inline-block"
                        :padding       "1px 6px"
                        :margin-left   "6px"
                        :border-radius "8px"
                        :background    (if divergent?
                                         (:red tokens)
                                         (:border-subtle tokens))
                        :color         (if divergent?
                                         "#fff"
                                         (:text-tertiary tokens))
                        :font-family   mono-stack
                        :font-size     "10px"
                        :cursor        "pointer"
                        :opacity       (if divergent? 1.0 0.6)}}
   (str "#" (subs (str hash) 0 (min 6 (count (str hash)))))])

(defn- tree-pane
  "Render one side of the side-by-side view. `chips` is the bisector
  chip list for this side; `divergent-path` is the bisection path
  (used to mark the chip at that path as divergent). `tree` is the
  full hiccup subtree to display in EDN form."
  [{:keys [side label tree chips divergent-path]}]
  [:section {:data-testid (str "rf-causa-hydration-tree-pane-" (name side))
             :style       {:flex   1
                           :min-width 0
                           :background (:bg-2 tokens)
                           :border-right (when (= side :server)
                                           (str "1px solid " (:border-default tokens)))
                           :overflow "auto"}}
   [:header {:style {:padding "8px 12px"
                     :border-bottom (str "1px solid " (:border-subtle tokens))
                     :font-family sans-stack
                     :font-size "12px"
                     :font-weight 600
                     :color (:text-secondary tokens)}}
    [:span (str (name side) " render")]
    (into [:span {:style {:margin-left "8px"}}]
          (map (fn [chip]
                 (hash-chip chip (= (:path chip) divergent-path)))
               chips))]
   [:pre {:data-testid (str "rf-causa-hydration-tree-content-" (name side))
          :style       {:margin       0
                        :padding      "12px"
                        :font-family  mono-stack
                        :font-size    "12px"
                        :color        (:text-primary tokens)
                        :white-space  "pre-wrap"
                        :word-break   "break-word"}}
    (format-edn tree)]])

(defn- divergent-marker
  "Per spec §Layout the divergent node is flagged with `⚠`. Rendered
  between the two panes so it sits visually between server / client
  trees."
  []
  [:div {:data-testid "rf-causa-hydration-divergent-marker"
         :style       {:padding "8px 12px"
                       :background (:bg-3 tokens)
                       :color (:yellow tokens)
                       :font-family sans-stack
                       :font-size "12px"
                       :text-align "center"
                       :border-bottom (str "1px solid " (:border-default tokens))}}
   [:span {:style {:font-size "16px" :margin-right "8px"}} "⚠"]
   [:span "Divergent node"]])

;; ---- hypothesis + source-coord -------------------------------------------

(defn- hypothesis-row
  "Per spec §Cause hypothesis — one-line hypothesis based on the
  divergence kind. The hypothesis is a hint, not an answer."
  [{:keys [divergence-kind hypothesis]}]
  [:div {:data-testid "rf-causa-hydration-hypothesis"
         :style       {:padding "12px"
                       :background (:bg-3 tokens)
                       :border-bottom (str "1px solid " (:border-default tokens))
                       :font-family sans-stack
                       :font-size "13px"
                       :color (:text-primary tokens)}}
   [:span {:style {:color (:cyan tokens)
                   :font-family mono-stack
                   :margin-right "8px"
                   :font-size "11px"
                   :text-transform "uppercase"}}
    "hypothesis"]
   [:span hypothesis]
   [:span {:style {:margin-left "8px"
                   :color (:text-tertiary tokens)
                   :font-family mono-stack
                   :font-size "11px"}}
    (str "(" (name divergence-kind) ")")]])

(defn- source-coord-row
  "Per spec §Source-coord drilldown — surface the divergent view's
  registration coord. Lock #11 fallback uses `(?)` annotation when the
  exact coord is unavailable."
  [coord-info view-id]
  (let [{:keys [coord annotation]} coord-info]
    (when (or coord view-id)
      [:div {:data-testid "rf-causa-hydration-source-coord"
             :style       {:padding "8px 12px"
                           :background (:bg-2 tokens)
                           :font-family sans-stack
                           :font-size "12px"
                           :color (:text-secondary tokens)
                           :border-bottom (str "1px solid " (:border-subtle tokens))}}
       [:span "Divergent view: "]
       [:code {:style {:color (:accent-violet tokens)
                       :font-family mono-stack}}
        (str view-id)]
       (when coord
         [:span {:style {:margin-left "12px"}}
          "Registered at: "
          [:code {:on-click   (fn [_]
                                (rf/dispatch [:rf.causa/open-in-editor coord]))
                  :style      {:color       (:cyan tokens)
                               :font-family mono-stack
                               :cursor      "pointer"
                               :text-decoration "underline"}}
           coord]
          (when (= :fallback annotation)
            [:span {:style {:margin-left "4px"
                            :color (:text-tertiary tokens)
                            :font-family mono-stack
                            :title "Source-coord fallback — exact view coord unavailable; surfaced from handler-coord per Lock #11."}}
             "(?)"])])])))

;; ---- mismatch detail composite view -------------------------------------

(defn- mismatch-detail
  "Per spec §Layout — the side-by-side server / client render-tree
  view with the bisector path highlighted + the hypothesis below."
  [{:keys [detail source-coord re-root-path] :as _data}]
  (let [{:keys [server-tree client-tree
                server-chips client-chips
                bisector-path view-id]} detail
        ;; If the user has re-rooted (clicked a hash chip), apply the
        ;; re-root to both trees; otherwise render the full subtree.
        server-tree' (if (seq re-root-path)
                       (h/re-root server-tree re-root-path)
                       server-tree)
        client-tree' (if (seq re-root-path)
                       (h/re-root client-tree re-root-path)
                       client-tree)]
    [:div {:data-testid "rf-causa-hydration-mismatch-detail"
           :style       {:flex          1
                         :display       "flex"
                         :flex-direction "column"
                         :overflow      "hidden"}}
     (hypothesis-row detail)
     (source-coord-row source-coord view-id)
     (divergent-marker)
     [:div {:style {:flex 1
                    :display "flex"
                    :flex-direction "row"
                    :overflow "hidden"}}
      (tree-pane {:side           :server
                  :label          "server render"
                  :tree           server-tree'
                  :chips          server-chips
                  :divergent-path bisector-path})
      (tree-pane {:side           :client
                  :label          "client render"
                  :tree           client-tree'
                  :chips          client-chips
                  :divergent-path bisector-path})]]))

;; ---- public view --------------------------------------------------------

(defn hydration-debugger-view
  "The Hydration Debugger panel's root view. Subscribes to
  `:rf.causa/hydration-debugger-data` and renders either:

    - 'No SSR in this app' empty state (per spec §Empty states), OR
    - Side-by-side mismatch detail when at least one mismatch is in
      the buffer.

  Per spec §Visibility the panel is dormant until first mismatch —
  the sidebar entry's visibility gate (rendered in shell.cljs) shows
  the `◌` marker until the buffer has at least one mismatch."
  []
  (let [{:keys [has-mismatch? mismatch-summary
                selected-mismatch-id detail
                source-coord re-root-path target-frame]
         :as data}
        @(rf/subscribe [:rf.causa/hydration-debugger-data])]
    [:section {:data-testid "rf-causa-hydration-debugger"
               :style       {:height         "100%"
                             :display        "flex"
                             :flex-direction "column"
                             :background     (:bg-2 tokens)
                             :color          (:text-primary tokens)
                             :font-family    sans-stack
                             :font-size      "14px"}}
     [:header {:style {:padding "16px 16px 8px 16px"
                       :border-bottom (str "1px solid " (:border-subtle tokens))}}
      [:h1 {:style {:font-size   "16px"
                    :font-weight 600
                    :margin      0
                    :color       (:text-primary tokens)}}
       "Hydration"]
      [:p {:style {:font-size "12px"
                   :color     (:text-tertiary tokens)
                   :margin    "4px 0 0 0"}}
       (if has-mismatch?
         [:span
          (str (count mismatch-summary) " mismatch"
               (if (= 1 (count mismatch-summary)) "" "es") " — showing mismatches for ")
          [:code {:style {:color (:accent-violet tokens)
                          :font-family mono-stack}}
           (str target-frame)]]
         "Phase 5 (rf2-pzxsr) — dormant until first :rf.ssr/hydration-mismatch")]]
     (cond
       (and has-mismatch? detail)
       [:div {:style {:flex 1 :display "flex" :flex-direction "column" :overflow "hidden"}}
        (mismatch-list mismatch-summary selected-mismatch-id)
        (mismatch-detail {:detail        detail
                          :source-coord  source-coord
                          :re-root-path  re-root-path})]

       has-mismatch?
       ;; Edge case — mismatches exist but no detail (selection
       ;; orphaned). Render the list only.
       [:div {:style {:flex 1 :overflow "auto"}}
        (mismatch-list mismatch-summary selected-mismatch-id)]

       :else
       (no-ssr-empty-state))]))
