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

  Three input registries, all read through public surfaces:

    - app-db schemas — assembled from the public `re-frame.schemas`
      façade: `rf/frame-ids` enumerates the live frames, then per
      frame `schemas/app-schemas` lists the registered paths and
      `schemas/app-schema-meta-at` returns each path's full meta map
      (`:schema` Malli EDN, `:doc`, `:file`/`:line`/`:ns` source
      coords). This yields the `{frame-id {path schema-meta}}` shape
      the per-frame projection consumes — without reaching the private
      `re-frame.schemas.storage/schemas-by-frame` atom.
    - `(rf/registrations :event)` — events whose metadata carries a
      `:spec` slot.
    - `(rf/registrations :sub)` — subs whose metadata carries a `:spec`
      slot.

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
            [re-frame.schemas :as schemas]
            [day8.re-frame2-causa.open-in-editor :as open-in-editor]
            [day8.re-frame2-causa.panel-registry :as panel-registry]
            [day8.re-frame2-causa.theme.tokens
             :as t
             :refer [tokens type-scale mono-stack sans-stack]]
            [day8.re-frame2-causa.views.edn-widget.widget :as edn]))

;; ---- pure helpers --------------------------------------------------------

(defn scope-app-schemas-to-frame
  "Narrow a `{frame-id {path schema-meta}}` app-db-schema snapshot to a
  single `frame-id`. App-db schemas are genuinely per-frame (the
  schemas registry is keyed by frame-id — see `rf/app-schemas`), so the
  L1 frame picker scopes the app-db-schema rows.

  A nil `frame-id` (no frame resolved yet) returns the snapshot
  verbatim. Pure data — JVM-runnable.

  NOTE: only the app-db-schema rows are frame-scoped. Event-spec and
  sub-spec rows come from the process-global registrar (Spec 001 —
  the registrar is per-process; frames isolate state, not
  registrations), so they are unconditionally cross-frame and carry
  `:frame nil`."
  [schemas-by-frame frame-id]
  (if (nil? frame-id)
    schemas-by-frame
    (select-keys schemas-by-frame [frame-id])))

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
  "View-facing composite. `frame-id` scopes the per-frame app-db
  schemas to the picker's observed frame (nil = every frame; see
  `scope-app-schemas-to-frame`); event-spec + sub-spec rows are
  process-global and always included."
  [schemas-by-frame events-map subs-map frame-id query]
  (let [scoped   (scope-app-schemas-to-frame schemas-by-frame frame-id)
        rows     (project-rows scoped events-map subs-map)
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
  ;; rf2-6xezz — Mike-direction 2026-05-21: panel-name heading scrubbed.
  [:div {:data-testid "rf-causa-static-schemas-header"
         :style       {:padding "4px 16px"}}])

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
    ;; rf2-mq8wk — list semantics. Schema rows are non-interactive
    ;; catalogue entries (the only row-level affordance is the
    ;; `open-chip` jump-to-source, which is itself focusable), so
    ;; `role=listitem` is the correct shape rather than `role=button`.
    [:li {:data-testid (str "rf-causa-static-schemas-row-" row-id)
          :role        "listitem"
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
     ;; rf2-2kwhw — the Malli schema EDN renders through the shared
     ;; cljs-devtools EDN widget (spec 007:119 — "all values rendered
     ;; via the cljs-devtools-shaped renderer") rather than raw
     ;; `pr-str` + `[:code]`, so it gains expand/collapse, syntax-
     ;; colouring parity, and the per-node copy host (rf2-f026h). The
     ;; `node-key` is stable per (kind,id) so expand state survives
     ;; reloads and doesn't collide across rows.
     [:div {:data-testid (str "rf-causa-static-schemas-schema-" row-id)
            :style {:margin-left "20px"
                    :margin-top  "2px"
                    :color       (:text-secondary tokens)
                    :font-size   "11px"
                    :white-space "pre-wrap"
                    :word-break  "break-word"}}
      (edn/inspect schema (str "static-schemas/" row-id))]
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
                      :role        "list"
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

;; ---- public-surface registry read ----------------------------------------

(defn read-app-schemas-by-frame
  "Assemble the `{frame-id {path schema-meta}}` app-db-schema snapshot
  the per-frame projection consumes, using only public `re-frame.schemas`
  / `re-frame.core` surfaces (Tool-Pair.md §public APIs) — never the
  private `re-frame.schemas.storage/schemas-by-frame` atom.

  For each live frame (`rf/frame-ids`), `schemas/app-schemas` enumerates
  the registered `{path → schema}` and `schemas/app-schema-meta-at`
  returns each path's full meta map (`:schema`, `:doc`, `:file` /
  `:line` / `:ns` source coords). Frames with no app-db schemas are
  dropped so the snapshot mirrors the storage atom's shape (absent
  rather than empty-mapped). Returns `{}` when the schemas artefact is
  not on the classpath (`app-schemas` then yields `{}` per frame)."
  []
  (reduce
    (fn [acc frame-id]
      (let [paths (keys (schemas/app-schemas frame-id))
            by-path (reduce
                      (fn [m path]
                        (if-let [meta (schemas/app-schema-meta-at path frame-id)]
                          (assoc m path meta)
                          m))
                      {}
                      paths)]
        (if (seq by-path)
          (assoc acc frame-id by-path)
          acc)))
    {}
    (rf/frame-ids)))

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

  ;; Assembles the three input registries from public surfaces once per
  ;; re-fire: app-db schemas via the `re-frame.schemas` façade
  ;; (`rf/frame-ids` + `schemas/app-schemas` + `schemas/app-schema-meta-
  ;; at`) and event / sub specs via `(rf/registrations <kind>)`.
  ;; `:<-`-composes against the trace buffer so the sub is reactive
  ;; against the same "something changed" pulse the other Static-mode
  ;; subs ride.
  (rf/reg-sub :rf.causa.static.schemas/registry
    :<- [:rf.causa/trace-buffer]
    :<- [:rf.causa.static.schemas/registry-override]
    (fn [[_buffer override] _query]
      (or override
          {:schemas-by-frame
           (try (read-app-schemas-by-frame)
                (catch :default _ {}))
           :events
           (try (registrar/registrations :event)
                (catch :default _ {}))
           :subs
           (try (registrar/registrations :sub)
                (catch :default _ {}))})))

  ;; ---- view-facing composite -------------------------------------------

  ;; `:rf.causa/observed-frame` is the L1 frame picker's current
  ;; selection. App-db schemas are per-frame (the `schemas-by-frame`
  ;; side-table is keyed by frame-id), so the picker scopes the app-db
  ;; rows — switching frames changes which frame's app-db schemas
  ;; list. Event + sub specs are process-global (Spec 001) and stay
  ;; cross-frame regardless of the picker.
  (rf/reg-sub :rf.causa.static.schemas/tab-data
    :<- [:rf.causa.static.schemas/registry]
    :<- [:rf.causa/observed-frame]
    :<- [:rf.causa.static.schemas/query]
    (fn [[{:keys [schemas-by-frame events subs]} observed-frame query] _query]
      (project-data schemas-by-frame events subs observed-frame query)))

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
