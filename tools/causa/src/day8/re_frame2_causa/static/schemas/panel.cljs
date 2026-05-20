(ns day8.re-frame2-causa.static.schemas.panel
  "Top-level Schemas sub-tab for Causa's Static surface (rf2-o5f5f.4).

  ## Browse-all verb

  Per Lock #15 (two-verbs-two-homes — browse-all lives in Static) the
  Schemas sub-tab is a flat catalogue of every registered schema —
  app-db slot schemas (via `re-frame.schemas/reg-app-schema`) plus
  event + sub schemas surfaced through the registrar's `:event` /
  `:sub` slot metadata `:spec` field.

      ┌───────────────────────────────────────────────────┐
      │ Schemas — header + descriptive prose              │
      ├───────────────────────────────────────────────────┤
      │ Search: [_______________]            12 schemas   │
      ├───────────────────────────────────────────────────┤
      │ ▸ app-db   [:user]      [:map ...]       [open]   │
      │ ▸ event    :user/login  [:tuple ...]              │
      │ ▸ sub      :user/full   [:map ...]                │
      └───────────────────────────────────────────────────┘

  ## Data sources

  Three input registries:

    - `re-frame.schemas/schemas-by-frame` — per-frame app-db schema
      entries shaped `{frame-id {path schema-meta}}` where
      `schema-meta` carries `:schema` (the Malli EDN), `:doc`, and
      source-coord slots.
    - `re-frame.registrar/registrations :event` — events whose
      metadata carries a `:spec` slot.
    - `re-frame.registrar/registrations :sub` — subs whose metadata
      carries a `:spec` slot.

  ## Jump-to-source

  Each row carries a source-coord chip (when the registered metadata
  surfaces `:file` / `:line`). Click dispatches
  `:rf.causa/open-in-editor` per the rf2-evgf5 / rf2-g5q8d wiring —
  same affordance the Trace + Issues panels use.

  ## State slots (all under `:rf.causa.static.schemas/*`)

    - `:rf.causa.static.schemas/query`    — search input value.

  ## Pure hiccup

  Same contract as every Causa view — pure hiccup. Frame isolation
  comes from the enclosing `[rf/frame-provider {:frame :rf/causa}]`
  in `static/shell.cljs`."
  (:require [clojure.string :as str]
            [re-frame.core :as rf]
            [re-frame.registrar :as registrar]
            [re-frame.schemas.storage :as schemas-storage]
            [day8.re-frame2-causa.open-in-editor :as open-in-editor]
            [day8.re-frame2-causa.panel-registry :as panel-registry]
            [day8.re-frame2-causa.theme.tokens
             :as t
             :refer [tokens type-scale mono-stack sans-stack]]))

;; ---- pure helpers --------------------------------------------------------

(defn project-app-schema-rows
  "Flatten `{frame-id {path schema-meta}}` into row maps.
  `schema-meta` carries `:schema`, `:doc`, plus `:file`/`:line`/`:ns`
  source-coord slots (Malli EDN + the rf2-5m5n2 source-coord stamp)."
  [schemas-by-frame]
  (->> schemas-by-frame
       (mapcat (fn [[frame-id by-path]]
                 (map (fn [[path schema-meta]]
                        {:kind         :app-db
                         :id           path
                         :frame        frame-id
                         :schema       (:schema schema-meta)
                         :doc          (:doc schema-meta)
                         :source-coord (select-keys schema-meta [:file :line :ns])})
                      by-path)))
       vec))

(defn- meta-row
  "Project one registrar `:event` / `:sub` entry to a row when it
  carries a `:spec` slot."
  [kind id meta]
  (when-some [spec (:spec meta)]
    {:kind         kind
     :id           id
     :frame        nil
     :schema       spec
     :doc          (:doc meta)
     :source-coord (select-keys meta [:file :line :ns])}))

(defn project-registrar-rows
  "Walk one kind's `{id meta}` map and return rows for every entry
  whose `:spec` slot is non-nil."
  [kind registrations-map]
  (vec (keep (fn [[id meta]] (meta-row kind id meta)) registrations-map)))

(defn project-rows
  "Combine app-db schema rows + event-spec rows + sub-spec rows into a
  single flat vector sorted by `(kind, id)`."
  [schemas-by-frame events-map subs-map]
  (let [rows (concat (project-app-schema-rows schemas-by-frame)
                     (project-registrar-rows :event events-map)
                     (project-registrar-rows :sub   subs-map))]
    (->> rows
         (sort-by (fn [{:keys [kind id]}] [(name kind) (pr-str id)]))
         vec)))

(defn- row-haystack [{:keys [kind id frame doc schema]}]
  (str/lower-case
    (str (name kind) " "
         (pr-str id) " "
         (pr-str frame) " "
         (or doc "") " "
         (pr-str schema))))

(defn filter-rows
  [rows query]
  (let [q (some-> query str/trim)]
    (if (or (nil? q) (= "" q))
      rows
      (let [needle (str/lower-case q)]
        (filterv #(str/includes? (row-haystack %) needle) rows)))))

(defn project-data
  [schemas-by-frame events-map subs-map query]
  (let [rows     (project-rows schemas-by-frame events-map subs-map)
        silent?  (empty? rows)
        filtered (filter-rows rows query)]
    {:silent?   silent?
     :schemas   filtered
     :total     (count rows)
     :filtered? (not= (count rows) (count filtered))
     :query     query}))

;; ---- header --------------------------------------------------------------

(defn- header
  []
  [:div {:data-testid "rf-causa-static-schemas-header"
         :style       {:padding       "12px 16px 8px 16px"
                       :border-bottom (str "1px solid " (:border-subtle tokens))
                       :font-family   sans-stack}}
   [:h1 {:style {:font-size      "13px"
                 :margin         "0"
                 :font-weight    600
                 :text-transform "uppercase"
                 :letter-spacing "0.5px"
                 :color          (:text-primary tokens)
                 :padding-left   "10px"
                 :border-left    (str "3px solid " (:cyan tokens))}}
    "Schemas"]
   [:p {:style {:margin    "4px 0 0 10px"
                :color     (:text-tertiary tokens)
                :font-size "11px"
                :line-height 1.4}}
    "Browse every registered Malli schema across app-db slots, events, "
    "and subscriptions. Click the "
    [:strong {:style {:color (:cyan tokens)}} "open"]
    " chip on any row to jump to its registration site."]])

;; ---- search box ----------------------------------------------------------

(defn- search-box
  [query total filtered?]
  [:div {:data-testid "rf-causa-static-schemas-search"
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
            :data-testid "rf-causa-static-schemas-search-input"
            :placeholder "kind, id, frame, or doc…"
            :value       (or query "")
            :on-change   (fn [e]
                           (rf/dispatch [:rf.causa.static.schemas/set-query
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
   [:span {:data-testid "rf-causa-static-schemas-search-count"
           :style       {:color       (:text-tertiary tokens)
                         :font-family mono-stack
                         :font-size   "11px"
                         :min-width   "80px"
                         :text-align  "right"}}
    (cond
      filtered?     "match"
      (= 1 total)   "1 schema"
      :else         (str total " schemas"))]])

;; ---- row -----------------------------------------------------------------

(defn- kind-badge
  [kind]
  (let [{:keys [letter colour]}
        (case kind
          :app-db {:letter "A" :colour (:cyan tokens)}
          :event  {:letter "E" :colour (:magenta tokens)}
          :sub    {:letter "S" :colour (:yellow tokens)}
          {:letter "?" :colour (:text-tertiary tokens)})]
    [:span {:data-testid (str "rf-causa-static-schemas-badge-" (name kind))
            :title       (str (name kind) " schema")
            :style       {:display       "inline-block"
                          :min-width     "14px"
                          :height        "14px"
                          :line-height   "14px"
                          :padding       "0 3px"
                          :margin-right  "6px"
                          :background    (:bg-3 tokens)
                          :color         colour
                          :border        (str "1px solid " colour)
                          :border-radius "3px"
                          :font-family   mono-stack
                          :font-size     "9px"
                          :font-weight   700
                          :text-align    "center"}}
     letter]))

(defn- schema-row
  [{:keys [kind id frame schema doc source-coord] :as _row}]
  (let [id-text (pr-str id)
        row-id  (str (name kind) "-" id-text)]
    [:li {:data-testid (str "rf-causa-static-schemas-row-" row-id)
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
      (kind-badge kind)
      [:span {:style {:color       (:accent-violet tokens)
                      :font-weight 500
                      :min-width   "200px"}}
       id-text]
      (when frame
        [:span {:data-testid (str "rf-causa-static-schemas-frame-" row-id)
                :style       {:color     (:text-tertiary tokens)
                              :font-size "10px"}}
         (pr-str frame)])
      (when (and source-coord (:file source-coord))
        [open-in-editor/open-chip source-coord])]
     [:div {:style {:margin-left "20px"
                    :margin-top  "2px"
                    :color       (:text-secondary tokens)
                    :font-size   "11px"
                    :white-space "pre-wrap"
                    :word-break  "break-word"}}
      [:code (pr-str schema)]]
     (when doc
       [:div {:style {:margin-left "20px"
                      :margin-top  "2px"
                      :color       (:text-secondary tokens)
                      :font-family sans-stack
                      :font-style  "italic"
                      :font-size   "11px"}}
        doc])]))

;; ---- empty states --------------------------------------------------------

(defn- empty-state
  []
  [:div {:data-testid "rf-causa-static-schemas-empty"
         :style       {:padding     "16px"
                       :color       (:text-tertiary tokens)
                       :font-family sans-stack
                       :font-size   "11px"
                       :font-style  "italic"}}
   "No schemas registered."])

(defn- empty-filtered
  [query]
  [:div {:data-testid "rf-causa-static-schemas-empty-filtered"
         :style       {:padding     "16px"
                       :color       (:text-tertiary tokens)
                       :font-family sans-stack
                       :font-size   "11px"
                       :font-style  "italic"}}
   (str "No schemas match " (pr-str query) ".")])

;; ---- root view -----------------------------------------------------------

(rf/reg-view Panel
  "Static Schemas panel root view. Subscribes to the schemas composite
  + the search-query slot and composes the header + search + flat list.

  Per rf2-in6l2 `reg-view`-registered so subscribes resolve to
  `:rf/causa`."
  []
  (let [data @(rf/subscribe [:rf.causa.static.schemas/tab-data])
        {:keys [silent? schemas total filtered? query]} data]
    [:section {:data-testid "rf-causa-static-schemas"
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
        (if (empty? schemas)
          (empty-filtered query)
          (into [:ul {:data-testid "rf-causa-static-schemas-list"
                      :style       {:list-style     "none"
                                    :margin         "8px 0 0 0"
                                    :padding        "0 8px"
                                    :flex           1
                                    :overflow       "auto"
                                    :display        "flex"
                                    :flex-direction "column"
                                    :gap            "4px"}}]
                (for [row schemas]
                  ^{:key (str (name (:kind row)) "/"
                              (pr-str (:frame row)) "/"
                              (pr-str (:id row)))}
                  [schema-row row])))])]))

;; ---- registrations -------------------------------------------------------

(defn install!
  "Idempotent install for the Static Schemas panel's subs + events.

  Registers:

    - `:rf.causa.static.schemas/query`              — search slot.
    - `:rf.causa.static.schemas/set-query`          — search setter.
    - `:rf.causa.static.schemas/registry-override`  — test seam.
    - `:rf.causa.static.schemas/set-registry-override-for-test`
        — test seam setter; payload shape
          `{:schemas-by-frame ... :events ... :subs ...}`.
    - `:rf.causa.static.schemas/registry`           — production data
                                                      sub reading the
                                                      three live
                                                      registries (or
                                                      override).
    - `:rf.causa.static.schemas/tab-data`           — view composite."
  []

  ;; ---- UI state ---------------------------------------------------------

  (rf/reg-event-db :rf.causa.static.schemas/set-query
    (fn [db [_ q]]
      (if (or (nil? q) (= "" q))
        (dissoc db :rf.causa.static.schemas/query)
        (assoc db :rf.causa.static.schemas/query q))))

  (rf/reg-sub :rf.causa.static.schemas/query
    (fn [db _]
      (get db :rf.causa.static.schemas/query)))

  ;; ---- test-only override ----------------------------------------------

  (rf/reg-event-db :rf.causa.static.schemas/set-registry-override-for-test
    (fn [db [_ ov]]
      (if (nil? ov)
        (dissoc db :rf.causa.static.schemas/registry-override)
        (assoc db :rf.causa.static.schemas/registry-override ov))))

  (rf/reg-sub :rf.causa.static.schemas/registry-override
    (fn [db _]
      (get db :rf.causa.static.schemas/registry-override)))

  ;; ---- production data sub ---------------------------------------------

  ;; Reads the schemas storage atom + walks the registrar's `:event` /
  ;; `:sub` slots once per re-fire. `:<-`-composes against the trace
  ;; buffer so the sub is reactive against the same "something
  ;; changed" pulse the other Static-mode subs ride.
  (rf/reg-sub :rf.causa.static.schemas/registry
    :<- [:rf.causa/trace-buffer]
    :<- [:rf.causa.static.schemas/registry-override]
    (fn [[_buffer override] _query]
      (or override
          {:schemas-by-frame
           (try @schemas-storage/schemas-by-frame
                (catch :default _ {}))
           :events
           (try (registrar/registrations :event)
                (catch :default _ {}))
           :subs
           (try (registrar/registrations :sub)
                (catch :default _ {}))})))

  ;; ---- view-facing composite -------------------------------------------

  (rf/reg-sub :rf.causa.static.schemas/tab-data
    :<- [:rf.causa.static.schemas/registry]
    :<- [:rf.causa.static.schemas/query]
    (fn [[{:keys [schemas-by-frame events subs]} query] _query]
      (project-data schemas-by-frame events subs query)))

  ;; rf2-2moh1 — register the Static Schemas tab with the internal L4
  ;; tab registry.
  (panel-registry/reg-l4-tab!
    {:id    :schemas
     :label "Schemas"
     :mnem  "c"
     :modes #{:static}
     :order 2
     :panel Panel
     :placeholder-bead "rf2-o5f5f.4"})

  nil)
