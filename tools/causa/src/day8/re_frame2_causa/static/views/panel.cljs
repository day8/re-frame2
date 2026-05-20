(ns day8.re-frame2-causa.static.views.panel
  "Top-level Views sub-tab for Causa's Static surface (rf2-o5f5f.5).

  ## Pure-browse verb

  Per Lock #15 (two-verbs-two-homes — browse-all lives in Static) the
  Views sub-tab is a flat catalogue of every view registered via
  `re-frame.core/reg-view*`. Views are pure-browse per the bead's
  §Verb taxonomy — there is no simulate-input affordance (component
  rendering with synthetic props is Storybook territory).

      ┌───────────────────────────────────────────────────┐
      │ Views — header + descriptive prose                │
      ├───────────────────────────────────────────────────┤
      │ Search: [_______________]            42 views     │
      ├───────────────────────────────────────────────────┤
      │ ▸ :rf.causa.static.shell/surface  [open]          │
      │ ▸ :route/link                     [open]          │
      │ ▸ user.profile/avatar             [open]          │
      └───────────────────────────────────────────────────┘

  ## Data source

  Reads `(re-frame.registrar/registrations :view)` — every entry
  registered through `reg-view` / `reg-view*` carries source-coord
  metadata (`:file` / `:line` / `:ns`) per Spec 004. The
  Fiber-walker (rf2-mxkq7) is the runtime mount-tree consumer; Static
  is event-INDEPENDENT, so this sub-tab works off the registry
  catalogue only — every view that COULD render appears here, not
  just those currently mounted.

  ## Jump-to-source

  Each row carries an `open` chip (when the metadata surfaces a
  source-coord). Click dispatches `:rf.causa/open-in-editor` per the
  rf2-evgf5 / rf2-g5q8d wiring.

  ## State slots (all under `:rf.causa.static.views/*`)

    - `:rf.causa.static.views/query`    — search input value.

  ## Pure hiccup

  Same contract as every Causa view — pure hiccup. Frame isolation
  comes from the enclosing `[rf/frame-provider {:frame :rf/causa}]`
  in `static/shell.cljs`.

  ## Distinct from `panels/reactive_panel.cljs`

  The Runtime-side Reactive panel (`panels/reactive_panel.cljs` ·
  rf2-wyvf2) renders the focused cascade's sub-recompute + view-
  re-render activity from the trace stream. This Static-side panel
  is the registry catalogue browser — pure-browse, event-independent."
  (:require [clojure.string :as str]
            [re-frame.core :as rf]
            [re-frame.registrar :as registrar]
            [day8.re-frame2-causa.open-in-editor :as open-in-editor]
            [day8.re-frame2-causa.panel-registry :as panel-registry]
            [day8.re-frame2-causa.theme.tokens
             :as t
             :refer [tokens type-scale mono-stack sans-stack]]))

;; ---- pure helpers --------------------------------------------------------

(defn project-rows
  "Project the registrar's `:view` `{id meta}` map into row maps sorted
  by id ascending. Each row carries `:id`, the source-coord trio
  (`:file`/`:line`/`:ns`), and the doc-string."
  [registrations-map]
  (->> registrations-map
       (map (fn [[id meta]]
              {:id           id
               :ns           (:ns meta)
               :doc          (:doc meta)
               :source-coord (select-keys meta [:file :line :ns])}))
       (sort-by (fn [{:keys [id]}] (pr-str id)))
       vec))

(defn- row-haystack [{:keys [id ns doc source-coord]}]
  (str/lower-case
    (str (pr-str id) " "
         (or ns "") " "
         (or doc "") " "
         (or (:file source-coord) ""))))

(defn filter-rows
  [rows query]
  (let [q (some-> query str/trim)]
    (if (or (nil? q) (= "" q))
      rows
      (let [needle (str/lower-case q)]
        (filterv #(str/includes? (row-haystack %) needle) rows)))))

(defn project-data
  [registrations-map query]
  (let [rows     (project-rows registrations-map)
        silent?  (empty? rows)
        filtered (filter-rows rows query)]
    {:silent?   silent?
     :views     filtered
     :total     (count rows)
     :filtered? (not= (count rows) (count filtered))
     :query     query}))

;; ---- header --------------------------------------------------------------

(defn- header
  []
  ;; rf2-6xezz — Mike-direction 2026-05-21: panel-name heading scrubbed.
  [:div {:data-testid "rf-causa-static-views-header"
         :style       {:padding "4px 16px"}}])

;; ---- search box ----------------------------------------------------------

(defn- search-box
  [query total filtered?]
  [:div {:data-testid "rf-causa-static-views-search"
         :style       {:display       "flex"
                       :align-items   "center"
                       :gap           "8px"
                       :padding       "8px 16px"
                       :border-bottom (str "1px solid " (:border-subtle tokens))
                       :font-family   sans-stack}}
   [:label {:style {:color          (:text-tertiary tokens)
                    :font-size      "10px"
                    :text-transform "uppercase"
                    :letter-spacing "0.5px"
                    :min-width      "60px"}}
    "Search"]
   [:input {:type        "text"
            :data-testid "rf-causa-static-views-search-input"
            :placeholder "view-id, ns, file, or doc…"
            :value       (or query "")
            :on-change   (fn [e]
                           (rf/dispatch [:rf.causa.static.views/set-query
                                         (-> e .-target .-value)]
                                        {:frame :rf/causa}))
            :style       {:flex          1
                          :background    (:bg-3 tokens)
                          :color         (:text-primary tokens)
                          :border        (str "1px solid " (:border-default tokens))
                          :border-radius "3px"
                          :padding       "4px 8px"
                          :font-family   mono-stack
                          :font-size     "12px"}}]
   [:span {:data-testid "rf-causa-static-views-search-count"
           :style       {:color       (:text-tertiary tokens)
                         :font-family mono-stack
                         :font-size   "11px"
                         :min-width   "80px"
                         :text-align  "right"}}
    (cond
      filtered?     "match"
      (= 1 total)   "1 view"
      :else         (str total " views"))]])

;; ---- row -----------------------------------------------------------------

(defn- view-row
  [{:keys [id ns doc source-coord] :as _row}]
  (let [id-text (pr-str id)
        row-id  (subs id-text 1)]
    [:li {:data-testid (str "rf-causa-static-views-row-" row-id)
          :style       {:display       "block"
                        :padding       "6px 12px"
                        :font-family   mono-stack
                        :font-size     "12px"
                        :color         (:text-primary tokens)
                        :background    "transparent"
                        :border-left   "2px solid transparent"
                        :border-radius "2px"
                        :line-height   "18px"}}
     [:div {:style {:display     "flex"
                    :align-items "baseline"
                    :gap         "8px"}}
      [:span {:style {:color       (:accent-violet tokens)
                      :font-weight 500
                      :min-width   "260px"}}
       id-text]
      (when ns
        [:span {:data-testid (str "rf-causa-static-views-ns-" row-id)
                :style       {:color     (:text-tertiary tokens)
                              :font-size "10px"}}
         (str ns)])
      (when (and source-coord (:file source-coord))
        [open-in-editor/open-chip source-coord])]
     (when doc
       [:div {:style {:margin-left "12px"
                      :margin-top  "2px"
                      :color       (:text-secondary tokens)
                      :font-family sans-stack
                      :font-style  "italic"
                      :font-size   "11px"}}
        doc])]))

;; ---- empty states --------------------------------------------------------

(defn- empty-state
  []
  [:div {:data-testid "rf-causa-static-views-empty"
         :style       {:padding     "16px"
                       :color       (:text-tertiary tokens)
                       :font-family sans-stack
                       :font-size   "11px"
                       :font-style  "italic"}}
   "No views registered."])

(defn- empty-filtered
  [query]
  [:div {:data-testid "rf-causa-static-views-empty-filtered"
         :style       {:padding     "16px"
                       :color       (:text-tertiary tokens)
                       :font-family sans-stack
                       :font-size   "11px"
                       :font-style  "italic"}}
   (str "No views match " (pr-str query) ".")])

;; ---- root view -----------------------------------------------------------

(rf/reg-view Panel
  "Static Views panel root view. Subscribes to the registered-views
  composite + the search-query slot and composes the header + search +
  flat list.

  Per rf2-in6l2 `reg-view`-registered so subscribes resolve to
  `:rf/causa`."
  []
  (let [data @(rf/subscribe [:rf.causa.static.views/tab-data])
        {:keys [silent? views total filtered? query]} data]
    [:section {:data-testid "rf-causa-static-views"
               :style       {:height         "100%"
                             :display        "flex"
                             :flex-direction "column"
                             :background     (:bg-2 tokens)
                             :color          (:text-primary tokens)
                             :font-family    sans-stack
                             :font-size      (:body type-scale)}}
     (header)
     (cond
       silent?
       (empty-state)

       :else
       [:<>
        (search-box query total filtered?)
        (if (empty? views)
          (empty-filtered query)
          (into [:ul {:data-testid "rf-causa-static-views-list"
                      :style       {:list-style     "none"
                                    :margin         "8px 0 0 0"
                                    :padding        "0 8px"
                                    :flex           1
                                    :overflow       "auto"
                                    :display        "flex"
                                    :flex-direction "column"
                                    :gap            "2px"}}]
                (for [row views]
                  ^{:key (pr-str (:id row))}
                  [view-row row])))])]))

;; ---- registrations -------------------------------------------------------

(defn install!
  "Idempotent install for the Static Views panel's subs + events.

  Registers:

    - `:rf.causa.static.views/query`             — search input slot.
    - `:rf.causa.static.views/set-query`         — search input setter.
    - `:rf.causa.static.views/registry-override` — test seam.
    - `:rf.causa.static.views/set-registry-override-for-test`
        — test seam setter.
    - `:rf.causa.static.views/registry`          — production data sub
                                                   reading the live
                                                   `:view` registrations
                                                   (or override).
    - `:rf.causa.static.views/tab-data`          — view-facing composite."
  []

  ;; ---- UI state ---------------------------------------------------------

  (rf/reg-event-db :rf.causa.static.views/set-query
    (fn [db [_ q]]
      (if (or (nil? q) (= "" q))
        (dissoc db :rf.causa.static.views/query)
        (assoc db :rf.causa.static.views/query q))))

  (rf/reg-sub :rf.causa.static.views/query
    (fn [db _]
      (get db :rf.causa.static.views/query)))

  ;; ---- test-only override ----------------------------------------------

  (rf/reg-event-db :rf.causa.static.views/set-registry-override-for-test
    (fn [db [_ ov]]
      (if (nil? ov)
        (dissoc db :rf.causa.static.views/registry-override)
        (assoc db :rf.causa.static.views/registry-override ov))))

  (rf/reg-sub :rf.causa.static.views/registry-override
    (fn [db _]
      (get db :rf.causa.static.views/registry-override)))

  ;; ---- production data sub ---------------------------------------------

  ;; Walks the registrar's `:view` slot once per re-fire. `:<-`-composes
  ;; against the trace buffer so newly-registered views surface as soon
  ;; as the trace-buffer fires its next reactive pulse.
  (rf/reg-sub :rf.causa.static.views/registry
    :<- [:rf.causa/trace-buffer]
    :<- [:rf.causa.static.views/registry-override]
    (fn [[_buffer override] _query]
      (or override
          (try (registrar/registrations :view)
               (catch :default _ {})))))

  ;; ---- view-facing composite -------------------------------------------

  (rf/reg-sub :rf.causa.static.views/tab-data
    :<- [:rf.causa.static.views/registry]
    :<- [:rf.causa.static.views/query]
    (fn [[registrations-map query] _query]
      (project-data registrations-map query)))

  ;; rf2-2moh1 — register the Static Views tab with the internal L4
  ;; tab registry.
  (panel-registry/reg-l4-tab!
    {:id    :views
     :label "Views"
     :mnem  "v"
     :modes #{:static}
     :order 3
     :panel Panel
     :placeholder-bead "rf2-o5f5f.5"})

  nil)
