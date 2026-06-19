(ns day8.re-frame2-xray.static.schemas.panel
  "Top-level Schemas sub-tab for Xray's Static surface (rf2-o5f5f.4).

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
  `:rf.xray/open-in-editor` per the rf2-evgf5 / rf2-g5q8d wiring —
  same affordance the Trace + Issues panels use.

  ## State slots (all under `:rf.xray.static.schemas/*`)

    - `:rf.xray.static.schemas/query`    — search input value.

  ## Pure hiccup

  Same contract as every Xray view — pure hiccup. Frame isolation
  comes from the enclosing `[rf/frame-provider-existing {:frame :rf/xray}]`
  in `static/shell.cljs`."
  (:require [clojure.string :as str]
            [re-frame.core :as rf]
            [re-frame.schemas :as schemas]
            [day8.re-frame2-xray.host-registry :as host-registry]
            [day8.re-frame2-xray.open-in-editor :as open-in-editor]
            [day8.re-frame2-xray.panel-registry :as panel-registry]
            [day8.re-frame2-xray.static.shared.search-box :as search-box]
            [day8.re-frame2-xray.theme.tokens
             :refer [tokens type-scale mono-stack sans-stack]]
            [day8.re-frame2-xray.views.edn-widget :as edn]))

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
  (search-box/filter-rows row-haystack rows query))

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
  [:div {:data-testid "rf-xray-static-schemas-header"
         :style       {:padding "4px 16px"}}])

;; ---- search box ----------------------------------------------------------

(defn- search-box
  [query total filtered?]
  ;; rf2-nesy9 — render-time frame capture (rendered inside the schemas
  ;; Panel reg-view), not a `:rf/xray` literal. rf2-1keg3 — the flex-row
  ;; markup lives in the shared `search-box` component.
  (let [frame (rf/current-frame-id)]
    [search-box/search-box
     {:testid-prefix   "rf-xray-static-schemas"
      :dispatch        (fn [ev] (rf/dispatch ev {:frame frame}))
      :set-query-event :rf.xray.static.schemas/set-query
      :placeholder     "kind, id, frame, or doc…"
      :value           query
      :count-noun      "schema"
      :total           total
      :filtered?       filtered?}]))

;; ---- row -----------------------------------------------------------------

(defn- kind-badge
  [kind]
  (let [{:keys [letter colour]}
        (case kind
          :app-db {:letter "A" :colour (:info tokens)}
          :event  {:letter "E" :colour (:magenta tokens)}
          :sub    {:letter "S" :colour (:yellow tokens)}
          {:letter "?" :colour (:text-tertiary tokens)})]
    [:span {:data-testid (str "rf-xray-static-schemas-badge-" (name kind))
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
    [:li {:data-testid (str "rf-xray-static-schemas-row-" row-id)
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
      [:span {:style {:color       (:accent tokens)
                      :font-weight 500
                      :min-width   "200px"}}
       id-text]
      (when frame
        [:span {:data-testid (str "rf-xray-static-schemas-frame-" row-id)
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
     [:div {:data-testid (str "rf-xray-static-schemas-schema-" row-id)
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

;; ---- root view -----------------------------------------------------------

(rf/reg-view Panel
  "Static Schemas panel root view. Subscribes to the schemas composite
  + the search-query slot and composes the header + search + flat list.

  Per rf2-in6l2 `reg-view`-registered so subscribes resolve to
  `:rf/xray`."
  []
  (let [data @(rf/subscribe [:rf.xray.static.schemas/tab-data])
        {:keys [silent? schemas total filtered? query]} data]
    [:section {:data-testid "rf-xray-static-schemas"
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
       (search-box/empty-state "rf-xray-static-schemas" "schema")

       :else
       [:<>
        (search-box query total filtered?)
        (if (empty? schemas)
          (search-box/empty-filtered "rf-xray-static-schemas" "schema" query)
          (into [:ul {:data-testid "rf-xray-static-schemas-list"
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

;; ---- production value source ---------------------------------------------
;;
;; The raw value the production data sub reads. Shared with the
;; test-override seam (`install-test-overrides!` below) so the override
;; branch lives in ONE place (the seam), not duplicated (rf2-e8330v).

(defn- registry-value
  "The three input registries assembled from public surfaces: app-db
  schemas via the `re-frame.schemas` façade, event / sub specs via the HOST
  app's `:event` / `:sub` registrar.

  The event / sub reads go through `host-registry/registrations` (the
  generation-bypassing default-realm form), NOT a bare `(rf/registrations
  <kind>)`: this runs inside the `:rf.xray.static.schemas/registry` sub
  COMPUTATION, and Xray seats in its OWN image-loaded `:rf/xray` frame, so the
  sub build binds the registrar to Xray's image generation — a bare read would
  resolve through Xray's OWN image and the schemas catalogue would lose the host
  app's event/sub specs. (App-db schemas live in a dedicated side-table keyed by
  frame, not the registrar, so `read-app-schemas-by-frame` is unaffected.) See
  `day8.re-frame2-xray.host-registry`."
  []
  {:schemas-by-frame
   (try (read-app-schemas-by-frame)
        (catch :default _ {}))
   :events
   (try (host-registry/registrations :event)
        (catch :default _ {}))
   :subs
   (try (host-registry/registrations :sub)
        (catch :default _ {}))})

;; ---- registrations -------------------------------------------------------

(defn install!
  "Idempotent install for the Static Schemas panel's subs + events.

  Registers:

    - `:rf.xray.static.schemas/query`              — search slot.
    - `:rf.xray.static.schemas/set-query`          — search setter.
    - `:rf.xray.static.schemas/registry-override`  — test seam.
    - `:rf.xray.static.schemas/set-registry-override-for-test`
        — test seam setter; payload shape
          `{:schemas-by-frame ... :events ... :subs ...}`.
    - `:rf.xray.static.schemas/registry`           — production data
                                                      sub reading the
                                                      three live
                                                      registries (or
                                                      override).
    - `:rf.xray.static.schemas/tab-data`           — view composite."
  []

  ;; ---- UI state ---------------------------------------------------------

  (rf/reg-event :rf.xray.static.schemas/set-query
    (fn [{:keys [db]} [_ q]]
      {:db (if (or (nil? q) (= "" q))
        (dissoc db :rf.xray.static.schemas/query)
        (assoc db :rf.xray.static.schemas/query q))}))

  (rf/reg-sub :rf.xray.static.schemas/query
    (fn [db _]
      (get db :rf.xray.static.schemas/query)))

  ;; The test-only override seam (`:rf.xray.static.schemas/set-registry-
  ;; override-for-test` + the `*-override` sub) is NOT installed here —
  ;; production registration carries no `-for-test` ids. Tests opt into
  ;; it via `install-test-overrides!` (rf2-e8330v / xxo3zz F3).

  ;; ---- production data sub ---------------------------------------------

  ;; Assembles the three input registries from public surfaces once per
  ;; re-fire: app-db schemas via the `re-frame.schemas` façade
  ;; (`rf/frame-ids` + `schemas/app-schemas` + `schemas/app-schema-meta-
  ;; at`) and event / sub specs via `(rf/registrations <kind>)`.
  ;; `:<-`-composes against the trace buffer so the sub is reactive
  ;; against the same "something changed" pulse the other Static-mode
  ;; subs ride.
  (rf/reg-sub :rf.xray.static.schemas/registry
    :<- [:rf.xray/trace-buffer]
    (fn [_buffer _query]
      (registry-value)))

  ;; ---- view-facing composite -------------------------------------------

  ;; `:rf.xray/observed-frame` is the L1 frame picker's current
  ;; selection. App-db schemas are per-frame (the `schemas-by-frame`
  ;; side-table is keyed by frame-id), so the picker scopes the app-db
  ;; rows — switching frames changes which frame's app-db schemas
  ;; list. Event + sub specs are process-global (Spec 001) and stay
  ;; cross-frame regardless of the picker.
  (rf/reg-sub :rf.xray.static.schemas/tab-data
    :<- [:rf.xray.static.schemas/registry]
    :<- [:rf.xray/observed-frame]
    :<- [:rf.xray.static.schemas/query]
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
     :panel Panel})

  nil)

;; ---- test-only override seam (rf2-e8330v / xxo3zz F3) ---------------------

(defn install-test-overrides!
  "Install the Static Schemas panel's test-only override seam — the
  `:rf.xray.static.schemas/set-registry-override-for-test` event + the
  `*-override` sub, then RE-register the production
  `:rf.xray.static.schemas/registry` sub to layer the override read on
  top. Tests opt in via `test-support/install-test-overrides!` AFTER
  `register-xray-handlers!`. **Test-only — never call from production.**"
  []
  (rf/reg-event :rf.xray.static.schemas/set-registry-override-for-test
    (fn [{:keys [db]} [_ ov]]
      {:db (if (nil? ov)
        (dissoc db :rf.xray.static.schemas/registry-override)
        (assoc db :rf.xray.static.schemas/registry-override ov))}))
  (rf/reg-sub :rf.xray.static.schemas/registry-override
    (fn [db _]
      (get db :rf.xray.static.schemas/registry-override)))

  (rf/reg-sub :rf.xray.static.schemas/registry
    :<- [:rf.xray/trace-buffer]
    :<- [:rf.xray.static.schemas/registry-override]
    (fn [[_buffer override] _query]
      (or override (registry-value))))
  nil)
