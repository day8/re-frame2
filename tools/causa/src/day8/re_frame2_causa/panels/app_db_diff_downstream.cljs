(ns day8.re-frame2-causa.panels.app-db-diff-downstream
  "App-DB panel — downstream-subs overlay (rf2-op9v2 → rf2-gblq6).

  Per `tools/causa/spec/021-Dynamic-Panel-Designs.md` §4.4 — hover (or
  click) any changed app-db path → popover lists subs and views
  downstream of that path (subs that recomputed/skipped and views that
  rendered in this cascade). Each downstream sub has a ⤴ button that
  navigates to the Reactive panel (tab key `:views`, rebadged
  'Reactive' per rf2-wyvf2 / PR #1741).

  ## Path-filtering (rf2-gblq6)

  rf2-op9v2 (PR #1747) shipped the popover as MVP — full cascade, no
  path filter. rf2-gblq6 (this rev) adds the registry-side walk
  (`panels.shared.sub-input-paths`) and filters the popover to subs
  whose static `:<-` chain depends on the hovered path. Subs with
  unknown attribution (cycle, missing upstream) fall through the
  conservative-include branch so the popover never lies by omission.

  See `panels.shared.sub-input-paths` for the resolver contract — the
  walk reports layer-1 leaves the sub composes over; the path-match
  heuristic compares keyword-segment overlap of those leaves against
  the hovered path-vec (matches the spec §4.4 example `[:cart :state]`
  ↔ `:cart/state`).

  Data source: the focused epoch's `:sub-runs` + `:renders` projections
  on the epoch record (the same slots the Reactive panel reads —
  `views_subs.cljs` §:rf.causa/views-focused-cascade-pair). These
  exist on every epoch record per `spec/Spec-Schemas.md
  §:rf/epoch-record`; reading them does NOT require the cascade-
  captured aggregate to be focus-gated (the aggregate adds bounded
  capture + view-render entries which we don't strictly need for the
  popover list).

  ## Navigation event

  `:rf.causa/navigate-to-panel` is registered here as a thin handler
  that:

    1. Writes the target `:sub-id` + `:query-v` into a `:rf.causa.app-
       db/nav-cursor` slot. The Reactive panel (rf2-wyvf2 / PR #1741)
       reads this slot to auto-scroll/highlight on mount.
    2. Dispatches `:rf.causa/select-tab` with the target tab id.

  When rf2-wyvf2 lands without auto-scroll wiring, the tab swap still
  works and the cursor sits in app-db harmlessly (a follow-on PR can
  hook the Reactive panel to it).

  ## Popover lifecycle

  Open/close state lives on `:rf.causa.app-db/popover` in Causa's
  app-db — `nil` when closed, `{:path <vec>}` when open at a path.
  Hover-in opens, hover-out (after the dispatch round-trip) closes.
  The popover body is mounted inside the hover trigger so its hover
  area connects to the trigger (no flicker between them).

  Per the §4.4 'Causa-owned component' guidance the popover is not a
  browser `title=` tooltip; it's a real DOM panel with keyboard
  dismissal via Esc (handled by the trigger's `:on-key-down`)."
  (:require [re-frame.core :as rf]
            [day8.re-frame2-causa.panels.app-db-diff-format :as f]
            [day8.re-frame2-causa.panels.shared.sub-input-paths :as sip]
            [day8.re-frame2-causa.theme.tokens
             :refer [tokens mono-stack sans-stack]]))

;; ---- subs --------------------------------------------------------------

(defn- focused-cascade-sub-runs+renders
  "Read the focused epoch's `:sub-runs` + `:renders` slots, falling
  back to head when no focus resolved. Mirrors `views-focused-cascade-
  pair` but only needs the current cascade (not the prior). Returns
  `{:sub-runs [...] :renders [...]}`; both vectors default to `[]`."
  [history focus]
  (let [history       (vec history)
        focus-eid     (:epoch-id focus)
        idx           (or (when focus-eid
                            (some (fn [[i r]]
                                    (when (= focus-eid (:epoch-id r)) i))
                                  (map-indexed vector history)))
                          (when (seq history) (dec (count history))))
        current       (when idx (nth history idx nil))]
    {:sub-runs (or (:sub-runs current) [])
     :renders  (or (:renders  current) [])}))

(defn install-subs!
  []
  ;; Popover open/close state — nil when closed, `{:path <vec>}` open.
  (rf/reg-sub :rf.causa.app-db/popover-slot
    (fn [db _]
      (get-in db [:app-db :popover])))

  (rf/reg-sub :rf.causa.app-db/popover-open?
    :<- [:rf.causa.app-db/popover-slot]
    (fn [slot _] (some? slot)))

  (rf/reg-sub :rf.causa.app-db/popover-path
    :<- [:rf.causa.app-db/popover-slot]
    (fn [slot _] (:path slot)))

  ;; Downstream subs / views for the focused cascade — path-filtered
  ;; via the registry-side input-paths walk (rf2-gblq6).
  ;;
  ;; Algorithm:
  ;;   1. Read every sub-id that ran in the cascade (recomputed +
  ;;      skipped).
  ;;   2. For each sub-id, walk the registry via
  ;;      `sip/resolve-input-paths` to get its layer-1 leaves.
  ;;   3. Keep the sub in the popover when `sip/sub-touches-path?`
  ;;      returns truthy — nil (unknown) ⇒ include, empty ⇒ exclude,
  ;;      keyword-segment overlap ⇒ include.
  ;;
  ;; Views are filtered transitively: a view appears when its
  ;; `:triggered-by` sub passes the same filter (or when the view's
  ;; render-key includes a sub-id segment overlapping the path).
  (rf/reg-sub :rf.causa/app-db-downstream-for-path
    :<- [:rf.causa/epoch-history]
    :<- [:rf.causa/focus]
    (fn [[history focus] [_query path]]
      (let [{:keys [sub-runs renders]}
            (focused-cascade-sub-runs+renders history focus)
            registry      (sip/sub-meta-map)
            touches-path? (fn [sub-id]
                            (sip/sub-touches-path?
                              (sip/resolve-input-paths sub-id registry)
                              path))
            kept-sub-runs (filter #(touches-path? (:sub-id %)) sub-runs)
            kept-sub-ids  (into #{} (map :sub-id) kept-sub-runs)
            kept-renders  (filter
                            (fn [{:keys [triggered-by]}]
                              (or (nil? triggered-by)
                                  (contains? kept-sub-ids triggered-by)
                                  (touches-path? triggered-by)))
                            renders)]
        {:subs-recomputed (vec (filter :recomputed? kept-sub-runs))
         :subs-skipped    (vec (remove :recomputed? kept-sub-runs))
         :views-rendered  (vec kept-renders)
         ;; rf2-gblq6 — popover is now truly path-filtered. The MVP-
         ;; note affordance (`rf-causa-app-db-popover-mvp-note`) is
         ;; removed downstream in `popover-body`.
         :path-filtered?  true}))))

;; ---- events ------------------------------------------------------------

(defn install-events!
  []
  (rf/reg-event-db :rf.causa.app-db/show-downstream
    (fn [db [_ path]]
      (assoc-in db [:app-db :popover] {:path (vec path)})))

  (rf/reg-event-db :rf.causa.app-db/hide-downstream
    (fn [db _]
      (update db :app-db dissoc :popover)))

  ;; `:rf.causa/navigate-to-panel` — fire-and-forget cross-panel
  ;; navigation. Per rf2-op9v2 the immediate caller is the popover's
  ;; ⤴ button; the handler also accepts a free-form `:sub-id` /
  ;; `:query-v` payload that lands in `:rf.causa.app-db/nav-cursor`
  ;; for the Reactive panel (rf2-wyvf2 / PR #1741) to auto-scroll to.
  ;;
  ;; When rf2-wyvf2 lands the cursor slot is consumed there; today
  ;; the cursor sits in app-db harmlessly.
  (rf/reg-event-fx :rf.causa/navigate-to-panel
    (fn [{:keys [db]} [_ {:keys [target sub-id query-v]}]]
      (let [target (or target :views)]
        {:db (-> db
                 (update :app-db dissoc :popover)
                 (cond->
                   (or sub-id query-v)
                   (assoc-in [:app-db :nav-cursor]
                             {:target  target
                              :sub-id  sub-id
                              :query-v query-v})))
         :fx [[:dispatch [:rf.causa/select-tab target]]]}))))

;; ---- view --------------------------------------------------------------

(defn- sub-row
  "One row in the popover's subs list. Carries the ⤴ jump button."
  [{:keys [sub-id query-v recomputed?]}]
  (let [label-suffix (if recomputed? "(recomputed)" "(skipped)")]
    [:li {:data-testid (str "rf-causa-app-db-popover-sub-"
                            (pr-str sub-id))
          :style {:display         "flex"
                  :align-items     "center"
                  :justify-content "space-between"
                  :gap             "8px"
                  :padding         "3px 8px"
                  :font-family     mono-stack
                  :font-size       "11px"
                  :color           (:text-primary tokens)
                  :border-bottom   (str "1px solid "
                                        (:border-subtle tokens))}}
     [:span {:style {:overflow      "hidden"
                     :text-overflow "ellipsis"
                     :white-space   "nowrap"
                     :flex          1}}
      [:span {:style {:color (:accent-violet tokens) :font-weight 600}}
       (pr-str sub-id)]
      (when (seq query-v)
        [:span {:style {:color (:text-tertiary tokens) :margin-left "4px"}}
         (f/truncate (f/format-edn (vec query-v)) 24)])
      [:span {:style {:color (:text-tertiary tokens)
                      :margin-left "6px"
                      :font-family sans-stack
                      :font-size "10px"}}
       label-suffix]]
     [:button
      {:data-testid (str "rf-causa-app-db-popover-jump-"
                         (pr-str sub-id))
       :title       "Jump to this sub in the Reactive panel"
       :aria-label  "Jump to this sub in the Reactive panel"
       :on-click    (fn [^js e]
                      (.preventDefault e)
                      (.stopPropagation e)
                      (rf/dispatch
                        [:rf.causa/navigate-to-panel
                         {:target  :views
                          :sub-id  sub-id
                          :query-v query-v}]
                        {:frame :rf/causa}))
       :style       {:flex          "0 0 auto"
                     :background    "transparent"
                     :border        (str "1px solid "
                                         (:border-default tokens))
                     :border-radius "3px"
                     :color         (:accent-violet tokens)
                     :font-family   mono-stack
                     :font-size     "11px"
                     :cursor        "pointer"
                     :padding       "0 6px"
                     :line-height   "16px"}}
      "⤴"]]))

(defn- view-row
  "One row in the popover's views list. No ⤴ button (the Reactive
  panel ⤴ jump is per-sub, since the popover's primary affordance is
  'jump to the sub that drives this render'). Views are listed for
  context — operator sees 'rendering CheckoutButton because of this
  path's cascade'."
  [{:keys [render-key triggered-by] :as render}]
  (let [view-id    (when (vector? render-key) (first render-key))
        view-label (or view-id render-key)]
    [:li {:data-testid (str "rf-causa-app-db-popover-view-"
                            (pr-str view-label))
          :style {:padding       "3px 8px"
                  :font-family   mono-stack
                  :font-size     "11px"
                  :color         (:text-primary tokens)
                  :border-bottom (str "1px solid "
                                      (:border-subtle tokens))}}
     [:span {:style {:color (:accent-violet tokens) :font-weight 600}}
      (pr-str view-label)]
     (when triggered-by
       [:span {:style {:color (:text-tertiary tokens)
                       :margin-left "6px"
                       :font-family sans-stack
                       :font-size "10px"}}
        (str "← " (pr-str triggered-by))])]))

(defn- popover-body
  "Render the popover contents — subs list (recomputed first, then
  skipped) + views list + ⤴ footer affordance."
  [path]
  (let [{:keys [subs-recomputed subs-skipped views-rendered]}
        @(rf/subscribe [:rf.causa/app-db-downstream-for-path path])
        any-content? (or (seq subs-recomputed)
                         (seq subs-skipped)
                         (seq views-rendered))]
    [:div {:data-testid "rf-causa-app-db-popover-body"
           :role        "dialog"
           :aria-label  (str "Downstream subs for path " (pr-str path))
           :on-click    #(.stopPropagation %)
           :style {:position       "absolute"
                   :left           0
                   :top            "100%"
                   :margin-top     "4px"
                   :z-index        20
                   :min-width      "280px"
                   :max-width      "420px"
                   :background     (:bg-2 tokens)
                   :border         (str "1px solid "
                                        (:border-default tokens))
                   :border-radius  "4px"
                   :box-shadow     "0 4px 12px rgba(0,0,0,0.4)"
                   :overflow       "hidden"
                   :font-family    sans-stack}}
     [:header {:style {:padding "6px 8px"
                       :background (:bg-3 tokens)
                       :border-bottom (str "1px solid "
                                           (:border-subtle tokens))
                       :font-size "11px"
                       :color (:text-secondary tokens)
                       :font-family sans-stack
                       :text-transform "uppercase"
                       :letter-spacing "0.5px"}}
      "Downstream of "
      [:code {:style {:color (:accent-violet tokens)
                      :font-family mono-stack
                      :text-transform "none"}}
       (f/format-edn (vec path))]]
     (cond
       (not any-content?)
       [:div {:data-testid "rf-causa-app-db-popover-empty"
              :style {:padding "8px"
                      :font-size "11px"
                      :color (:text-tertiary tokens)
                      :font-style "italic"
                      :font-family sans-stack}}
        "No subs ran in this cascade."]

       :else
       [:<>
        (when (seq subs-recomputed)
          [:section {:data-testid "rf-causa-app-db-popover-subs-recomputed"}
           [:h4 {:style {:margin "0"
                         :padding "4px 8px"
                         :font-size "10px"
                         :font-family sans-stack
                         :text-transform "uppercase"
                         :letter-spacing "0.5px"
                         :color (:text-secondary tokens)
                         :background (:bg-3 tokens)
                         :font-weight 600}}
            "Subs depending on this path"]
           (into [:ul {:style {:margin 0 :padding 0 :list-style "none"}}]
                 (for [sr subs-recomputed]
                   (with-meta (sub-row (assoc sr :recomputed? true))
                              {:key (pr-str [(:sub-id sr) (:query-v sr)])})))])
        (when (seq subs-skipped)
          [:section {:data-testid "rf-causa-app-db-popover-subs-skipped"}
           [:h4 {:style {:margin "0"
                         :padding "4px 8px"
                         :font-size "10px"
                         :font-family sans-stack
                         :text-transform "uppercase"
                         :letter-spacing "0.5px"
                         :color (:text-secondary tokens)
                         :background (:bg-3 tokens)
                         :font-weight 600}}
            "Subs skipped (cache hit)"]
           (into [:ul {:style {:margin 0 :padding 0 :list-style "none"}}]
                 (for [sr subs-skipped]
                   (with-meta (sub-row (assoc sr :recomputed? false))
                              {:key (pr-str [(:sub-id sr) (:query-v sr)])})))])
        (when (seq views-rendered)
          [:section {:data-testid "rf-causa-app-db-popover-views"}
           [:h4 {:style {:margin "0"
                         :padding "4px 8px"
                         :font-size "10px"
                         :font-family sans-stack
                         :text-transform "uppercase"
                         :letter-spacing "0.5px"
                         :color (:text-secondary tokens)
                         :background (:bg-3 tokens)
                         :font-weight 600}}
            "Views rendered"]
           (into [:ul {:style {:margin 0 :padding 0 :list-style "none"}}]
                 (for [r views-rendered]
                   (with-meta (view-row r)
                              {:key (pr-str (:render-key r))})))])])
     ;; ⤴ footer affordance — jumps to the Reactive panel without a
     ;; specific sub focus (the operator can scrub from there). Mirrors
     ;; the spec §4.4 footer line '⤴ jump to Reactive panel'.
     [:div {:style {:padding "4px 8px"
                    :border-top (str "1px solid "
                                     (:border-subtle tokens))
                    :background (:bg-3 tokens)
                    :text-align "right"}}
      [:button
       {:data-testid "rf-causa-app-db-popover-jump-reactive"
        :on-click    (fn [^js e]
                       (.preventDefault e)
                       (.stopPropagation e)
                       (rf/dispatch
                         [:rf.causa/navigate-to-panel {:target :views}]
                         {:frame :rf/causa}))
        :style       {:background    "transparent"
                      :border        (str "1px solid "
                                          (:border-default tokens))
                      :border-radius "3px"
                      :color         (:accent-violet tokens)
                      :font-family   sans-stack
                      :font-size     "10px"
                      :cursor        "pointer"
                      :padding       "1px 6px"}}
       "⤴ jump to Reactive panel"]]]))

(defn hover-trigger
  "Inline trigger element placed in a section's breadcrumb. On hover
  (or click / keyboard activation) opens the downstream-subs popover
  anchored to this trigger. The trigger reads the popover slot to
  decide whether to render the body — only the popover whose path
  matches this trigger renders, so multiple section triggers don't
  spawn overlapping popovers.

  `path` is the section's app-db path."
  [path]
  (let [open-path @(rf/subscribe [:rf.causa.app-db/popover-path])
        active?   (= (vec path) (vec (or open-path [])))
        open      (fn [^js e]
                    (.preventDefault e)
                    (.stopPropagation e)
                    (rf/dispatch [:rf.causa.app-db/show-downstream path]
                                 {:frame :rf/causa}))
        close     (fn [_e]
                    (rf/dispatch [:rf.causa.app-db/hide-downstream]
                                 {:frame :rf/causa}))]
    [:span {:data-testid (str "rf-causa-app-db-downstream-trigger-"
                              (pr-str path))
            :tab-index   0
            :role        "button"
            :aria-haspopup "dialog"
            :aria-expanded (if active? "true" "false")
            :aria-label  (str "Show subs and views downstream of "
                              (pr-str path))
            :title       (str "Hover or click to see subs/views downstream of "
                              (pr-str path))
            :on-mouse-enter open
            :on-mouse-leave close
            :on-focus       open
            :on-blur        close
            :on-click       open
            :on-key-down    (fn [^js e]
                              (case (.-key e)
                                ("Escape" "Esc")
                                (do (.preventDefault e) (close e))
                                ("Enter" " ")
                                (do (.preventDefault e) (open e))
                                nil))
            :style {:position      "relative"
                    :display       "inline-flex"
                    :align-items   "center"
                    :gap           "2px"
                    :padding       "1px 6px"
                    :margin-left   "4px"
                    :background    (if active?
                                     (:bg-active tokens)
                                     (:bg-2 tokens))
                    :border        (str "1px solid "
                                        (:border-default tokens))
                    :border-radius "3px"
                    :color         (:accent-violet tokens)
                    :font-family   mono-stack
                    :font-size     "10px"
                    :cursor        "pointer"
                    :user-select   "none"
                    :line-height   "14px"}}
     "⤴ subs"
     (when active?
       (popover-body path))]))

;; ---- install! ----------------------------------------------------------

(defn install!
  "Idempotent install — subs + events for the downstream-subs overlay.
  The trigger view is mounted by the App-DB panel directly from
  `app-db-diff.cljs`."
  []
  (install-subs!)
  (install-events!)
  nil)
