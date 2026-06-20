(ns day8.re-frame2-xray.static.flows.panel
  "Top-level Flows sub-tab for Xray's Static surface.

  ## Browse-all verb

  Per Lock #15 (two-verbs-two-homes — browse-all lives in Static) the
  Flows sub-tab is a flat catalogue of every flow registered via
  `re-frame.flows/reg-flow`. Each row surfaces the flow-id, its
  `:inputs` paths, its `:output-path`, the owning frame (flows are
  frame-scoped per Spec 013), and the doc-string (when present).

      ┌───────────────────────────────────────────────────┐
      │ Flows — header + descriptive prose                │
      ├───────────────────────────────────────────────────┤
      │ Search: [_______________]            7 flows      │
      ├───────────────────────────────────────────────────┤
      │ ▸ :user/full-name   [:user :first] [:user :last]  │
      │     → [:derived :full-name]    [:rf/default]      │
      │ ▸ :cart/total       …                             │
      └───────────────────────────────────────────────────┘

  ## Data source

  Reads the registered flows through the public introspection surface
  `(rf/registrations :flow)` (Tool-Pair.md §public APIs; spec/014
  catalogues `:rf.xray/registered-flows` as `(rf/registrations :flow)`)
  rather than reaching the private `re-frame.flows.registry/flows`
  atom. The registrar slot keys on flow-id with `:frame` stamped per
  entry (registry.cljc — `reg-flow` stamps `:frame` into the metadata),
  so the sub groups the flat `{flow-id meta}` map by `:frame` back into
  the `{frame-id {flow-id flow-map}}` shape the per-frame projection +
  picker-scoping helpers consume. The view never reasons about the
  registry's two-level shape directly.

  Optional test override slot: `:rf.xray.static.flows/registered-
  flows-override` lets the CLJS test suite inject deterministic
  fixtures without poking the live atom.

  ## State slots (all under `:rf.xray.static.flows/*`)

    - `:rf.xray.static.flows/query`    — search input value.

  ## Pure hiccup

  Same contract as every Xray view — pure hiccup, no Reagent / UIx
  / Helix references. Frame isolation comes from the enclosing
  `[rf/frame-provider-existing {:frame :rf/xray}]` in `static/shell.cljs`."
  (:require [clojure.string :as str]
            [re-frame.core :as rf]
            [day8.re-frame2-xray.host-registry :as host-registry]
            [day8.re-frame2-xray.panel-registry :as panel-registry]
            [day8.re-frame2-xray.static.shared.search-box :as search-box]
            [day8.re-frame2-xray.theme.tokens
             :refer [tokens type-scale mono-stack sans-stack]]
            [day8.re-frame2-xray.views.edn-widget :as edn]))

;; ---- pure helpers --------------------------------------------------------

(defn scope-to-frame
  "Narrow a `{frame-id {flow-id flow-map}}` registry snapshot to a
  single `frame-id`, returning the same two-level shape carrying only
  that frame's entry. The flows registry is genuinely per-frame (Spec
  013 — `re-frame.flows.registry/flows` is keyed by frame-id), so the
  L1 frame picker's selection MUST scope the catalogue: switching the
  picker changes which frame's flows the Static Flows tab lists.

  A nil `frame-id` (no frame resolved yet) returns the snapshot
  verbatim — the cold-start empty-state stays useful rather than
  blanking the list. Pure data — JVM-runnable."
  [registry-snapshot frame-id]
  (if (nil? frame-id)
    registry-snapshot
    (select-keys registry-snapshot [frame-id])))

(defn registrations->by-frame
  "Group the flat `(rf/registrations :flow)` shape — `{flow-id meta}`
  where each `meta` carries a `:frame` stamped at `reg-flow`-time — back
  into the per-frame `{frame-id {flow-id flow-map}}` shape the
  projection + picker-scoping helpers consume.

  An entry whose `:frame` slot is absent (defensive — every flow
  registration stamps `:frame`) buckets under the distinct
  `:rf.xray/no-frame-stamp` sentinel. Per EP-0002, a missing frame
  stamp is NOT bucketed under `:rf/default` (an ordinary id that a real
  flow may legitimately register in): conflating the two would
  mis-attribute a stamp-less registration to a real frame's group.
  Pure data — JVM-runnable."
  [registrations]
  (reduce-kv
    (fn [acc flow-id meta]
      (let [frame-id (get meta :frame :rf.xray/no-frame-stamp)]
        (assoc-in acc [frame-id flow-id] meta)))
    {}
    registrations))

(defn project-rows
  "Flatten `{frame-id {flow-id flow-map}}` into a flat vector of rows,
  sorted by flow-id ascending. Pure data so the JVM unit-test target
  can cover the shape without a CLJS runtime."
  [registry-snapshot]
  (->> registry-snapshot
       (mapcat (fn [[frame-id by-id]]
                 (map (fn [[flow-id flow-map]]
                        {:flow-id     flow-id
                         :frame       frame-id
                         :inputs      (vec (:inputs flow-map))
                         :output-path (vec (:output-path flow-map))
                         :doc         (:doc flow-map)})
                      by-id)))
       (sort-by (fn [{:keys [flow-id]}] (str flow-id)))
       vec))

(defn- row-haystack [{:keys [flow-id frame inputs output-path doc]}]
  (str/lower-case
    (str (pr-str flow-id) " "
         (pr-str frame) " "
         (pr-str inputs) " "
         (pr-str output-path) " "
         (or doc ""))))

(defn filter-rows
  "Substring filter against flow-id + frame + inputs + output-path + doc.
  Empty / blank query returns rows verbatim."
  [rows query]
  (search-box/filter-rows row-haystack rows query))

(defn project-data
  "View-facing composite. Folds the registered-flows map + UI controls
  into the shape `panel/Panel` consumes:

      {:silent?     <bool>
       :flows       [<row> ...]
       :total       <pre-filter count>
       :filtered?   <bool>
       :query       <string-or-nil>}

  `frame-id` scopes the per-frame registry to the picker's observed
  frame before projecting (nil = no frame resolved → list every
  frame's flows; see `scope-to-frame`)."
  [registry-snapshot frame-id query]
  (let [scoped   (scope-to-frame registry-snapshot frame-id)
        rows     (project-rows scoped)
        silent?  (empty? rows)
        filtered (filter-rows rows query)]
    {:silent?   silent?
     :flows     filtered
     :total     (count rows)
     :filtered? (not= (count rows) (count filtered))
     :query     query}))

;; ---- header --------------------------------------------------------------

(defn- header
  []
  ;; The header carries no panel-name heading — the L4 tab strip is the
  ;; panel-name source-of-truth.
  [:div {:data-testid "rf-xray-static-flows-header"
         :style       {:padding "4px 16px"}}])

;; ---- search box ----------------------------------------------------------

(defn- search-box
  [query total filtered?]
  ;; Render-time frame capture so the deferred search input dispatches
  ;; into the surrounding instance frame (rendered inside the flows Panel
  ;; reg-view), not a `:rf/xray` literal. The flex-row markup lives in
  ;; the shared `search-box` component.
  (let [frame (rf/current-frame-id)]
    [search-box/search-box
     {:testid-prefix   "rf-xray-static-flows"
      :dispatch        (fn [ev] (rf/dispatch ev {:frame frame}))
      :set-query-event :rf.xray.static.flows/set-query
      :placeholder     "flow-id, path, or doc…"
      :value           query
      :count-noun      "flow"
      :total           total
      :filtered?       filtered?}]))

;; ---- row -----------------------------------------------------------------

(defn- flow-row
  [{:keys [flow-id frame inputs output-path doc] :as _row}]
  ;; List semantics. Flow rows are non-interactive (no row-level
  ;; dispatch), so `role=listitem` is the right shape — they are
  ;; catalogue entries, not buttons. The interactive Static surface that
  ;; earns keyboard activation is the Routes list (whose rows toggle an
  ;; expand surface — see static/routes/browse_list.cljs).
  [:li {:data-testid (str "rf-xray-static-flows-row-"
                          (subs (pr-str flow-id) 1))
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
    [:span {:style {:color       (:accent tokens)
                    :font-weight 500
                    :min-width   "180px"}}
     (pr-str flow-id)]
    [:span {:data-testid (str "rf-xray-static-flows-frame-"
                              (subs (pr-str flow-id) 1))
            :style {:color     (:text-tertiary tokens)
                    :font-size "10px"}}
     (pr-str frame)]]
   ;; Input + output path values render through the shared cljs-devtools
   ;; EDN widget (spec 007:119 — "all values rendered via the
   ;; cljs-devtools-shaped renderer") rather than raw `pr-str` +
   ;; `[:code]`. `edn/inspect` is the canonical L4 renderer (same call
   ;; the App-DB segment-inspector uses); each value gets a stable
   ;; per-flow `node-key` so the widget's per-node expand state + copy
   ;; affordance ride the same way they do in the App-DB / Trace / Event
   ;; surfaces.
   (let [flow-key (subs (pr-str flow-id) 1)]
     [:div {:style {:margin-left  "12px"
                    :color        (:text-secondary tokens)
                    :font-size    "11px"
                    :line-height  1.4}}
      [:div {:style {:display "flex" :align-items "baseline" :gap "6px"}}
       [:span {:style {:color (:text-tertiary tokens)
                       :flex  "0 0 auto"}}
        "inputs:"]
       (into [:span {:style {:display     "inline-flex"
                             :flex-wrap   "wrap"
                             :gap         "6px"}}]
             (for [[i input-path] (map-indexed vector inputs)]
               ^{:key (str "in-" i)}
               (edn/inspect input-path
                            (str "static-flows/" flow-key "/input/" i))))]
      [:div {:style {:display "flex" :align-items "baseline" :gap "6px"}}
       [:span {:style {:color (:text-tertiary tokens)
                       :flex  "0 0 auto"}}
        "output →"]
       (edn/inspect output-path (str "static-flows/" flow-key "/output"))]
      (when doc
        [:div {:style {:margin-top  "4px"
                       :color       (:text-secondary tokens)
                       :font-family sans-stack
                       :font-style  "italic"}}
         doc])])])

;; ---- root view -----------------------------------------------------------

(rf/reg-view Panel
  "Static Flows panel root view. Subscribes to the registered-flows
  composite + the search-query slot and composes the header + search +
  flat list.

  `reg-view`-registered so subscribes resolve to `:rf/xray`."
  []
  (let [data @(rf/subscribe [:rf.xray.static.flows/tab-data])
        {:keys [silent? flows total filtered? query]} data]
    [:section {:data-testid "rf-xray-static-flows"
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
       (search-box/empty-state "rf-xray-static-flows" "flow")

       :else
       [:<>
        (search-box query total filtered?)
        (if (empty? flows)
          (search-box/empty-filtered "rf-xray-static-flows" "flow" query)
          (into [:ul {:data-testid "rf-xray-static-flows-list"
                      :role        "list"
                      :style       {:list-style     "none"
                                    :margin         "8px 0 0 0"
                                    :padding        "0 8px"
                                    :flex           1
                                    :overflow       "auto"
                                    :display        "flex"
                                    :flex-direction "column"
                                    :gap            "2px"}}]
                (for [row flows]
                  ^{:key (str (:frame row) "/" (:flow-id row))}
                  [flow-row row])))])]))

;; ---- production value source ---------------------------------------------
;;
;; The raw value the production data sub reads. Shared with the
;; test-override seam (`install-test-overrides!` below) so the override
;; branch lives in ONE place (the seam), not duplicated.

(defn- registered-flows-value
  "The registered flows regrouped into the per-frame
  `{frame-id {flow-id flow-map}}` shape from the HOST app's `:flow` registrar.

  Read via `host-registry/registrations` (the generation-bypassing default-realm
  form), NOT a bare `(rf/registrations :flow)`: this runs inside the
  `:rf.xray.static.flows/registered-flows` sub COMPUTATION, and Xray seats in
  its OWN image-loaded `:rf/xray` frame, so the sub build binds the registrar to
  Xray's image generation — a bare read would resolve through Xray's OWN image
  (no host `:flow` ids) and the panel would show an empty flow catalogue. See
  `day8.re-frame2-xray.host-registry`."
  []
  (try (registrations->by-frame (host-registry/registrations :flow))
       (catch :default _ {})))

;; ---- registrations -------------------------------------------------------

(defn install!
  "Idempotent install for the Static Flows panel's subs + events.

  Registers:

    - `:rf.xray.static.flows/query`            — search input slot.
    - `:rf.xray.static.flows/set-query`        — search input setter.
    - `:rf.xray.static.flows/registered-flows-override` — test-only
                                                  override slot.
    - `:rf.xray.static.flows/set-registered-flows-override-for-test`
        — test-only override setter.
    - `:rf.xray.static.flows/registered-flows` — production data sub
                                                  reading the public
                                                  `(rf/registrations
                                                  :flow)` surface
                                                  (or override).
    - `:rf.xray.static.flows/tab-data`         — view-facing composite."
  []

  ;; ---- UI state ---------------------------------------------------------

  (rf/reg-event :rf.xray.static.flows/set-query
    (fn [{:keys [db]} [_ q]]
      {:db (if (or (nil? q) (= "" q))
        (dissoc db :rf.xray.static.flows/query)
        (assoc db :rf.xray.static.flows/query q))}))

  (rf/reg-sub :rf.xray.static.flows/query
    (fn [db _]
      (get db :rf.xray.static.flows/query)))

  ;; The test-only override seam (`:rf.xray.static.flows/set-registered-
  ;; flows-override-for-test` + the `*-override` sub) is NOT installed
  ;; here — production registration carries no `-for-test` ids. Tests opt
  ;; into it via `install-test-overrides!`.

  ;; ---- production data sub ---------------------------------------------

  ;; Read the registered flows through the public `(rf/registrations
  ;; :flow)` introspection surface (Tool-Pair.md §public APIs) once per
  ;; sub re-fire, then regroup the flat `{flow-id meta}` shape into the
  ;; per-frame `{frame-id {flow-id flow-map}}` shape the projection +
  ;; picker-scoping helpers consume. `:<-`-composing against
  ;; `:rf.xray/trace-buffer` keeps the sub reactive against the same
  ;; "something changed" pulse the other static-mode subs ride —
  ;; without it, a fresh `reg-flow!` wouldn't surface until the next
  ;; subscribe re-render.
  (rf/reg-sub :rf.xray.static.flows/registered-flows
    :<- [:rf.xray/trace-buffer]
    (fn [_buffer _query]
      (registered-flows-value)))

  ;; ---- view-facing composite -------------------------------------------

  ;; `:rf.xray/observed-frame` is the L1 frame picker's current
  ;; selection (installed by `app-db-diff-subs/install!`). The flows
  ;; registry is per-frame (Spec 013), so the picker scopes the
  ;; catalogue — switching frames changes which frame's flows list.
  (rf/reg-sub :rf.xray.static.flows/tab-data
    :<- [:rf.xray.static.flows/registered-flows]
    :<- [:rf.xray/observed-frame]
    :<- [:rf.xray.static.flows/query]
    (fn [[registry-snapshot observed-frame query] _query]
      (project-data registry-snapshot observed-frame query)))

  ;; Register the Static Flows tab with the internal L4 tab registry.
  ;; Contiguous order: machines 0 · routes 1 · schemas 2 · flows 3 ·
  ;; interceptors 4.
  ;; Mnemonic is "f" (first-letter-of-label, the Static-mode convention:
  ;; Machines→m, Routes→r, Interceptors→i). "f" is free in the Static
  ;; mnemonic set (Schemas uses "c" because "s" is the Settings key;
  ;; Flows has no such collision), and the Static shell's own canonical
  ;; IA listing (`static/shell.cljs` "Flows (f)") documents "f" as the
  ;; intended binding.
  (panel-registry/reg-l4-tab!
    {:id    :flows
     :label "Flows"
     :mnem  "f"
     :modes #{:static}
     :order 3
     :panel Panel})

  nil)

;; ---- test-only override seam --------------------------------------------

(defn install-test-overrides!
  "Install the Static Flows panel's test-only override seam — the
  `:rf.xray.static.flows/set-registered-flows-override-for-test` event +
  the `*-override` sub, then RE-register the production
  `:rf.xray.static.flows/registered-flows` sub to layer the override read
  on top. Tests opt in via `test-support/install-test-overrides!` AFTER
  `register-xray-handlers!`. **Test-only — never call from production.**"
  []
  (rf/reg-event :rf.xray.static.flows/set-registered-flows-override-for-test
    (fn [{:keys [db]} [_ ov]]
      {:db (if (nil? ov)
        (dissoc db :rf.xray.static.flows/registered-flows-override)
        (assoc db :rf.xray.static.flows/registered-flows-override ov))}))
  (rf/reg-sub :rf.xray.static.flows/registered-flows-override
    (fn [db _]
      (get db :rf.xray.static.flows/registered-flows-override)))

  (rf/reg-sub :rf.xray.static.flows/registered-flows
    :<- [:rf.xray/trace-buffer]
    :<- [:rf.xray.static.flows/registered-flows-override]
    (fn [[_buffer override] _query]
      (or override (registered-flows-value))))
  nil)
