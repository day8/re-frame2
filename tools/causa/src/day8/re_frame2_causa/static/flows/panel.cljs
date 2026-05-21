(ns day8.re-frame2-causa.static.flows.panel
  "Top-level Flows sub-tab for Causa's Static surface (rf2-uhsqb).

  ## Browse-all verb

  Per Lock #15 (two-verbs-two-homes — browse-all lives in Static) the
  Flows sub-tab is a flat catalogue of every flow registered via
  `re-frame.flows/reg-flow`. Each row surfaces the flow-id, its
  `:inputs` paths, its `:output` `:path`, the owning frame (flows are
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

  Reads the live `re-frame.flows.registry/flows` atom shape
  `{frame-id {flow-id flow-map}}` via the
  `:rf.causa.static.flows/registered-flows` sub. The sub flattens the
  per-frame nesting into a vector of rows so the view never reasons
  about the registry's two-level shape directly.

  Optional test override slot: `:rf.causa.static.flows/registered-
  flows-override` lets the CLJS test suite inject deterministic
  fixtures without poking the live atom.

  ## State slots (all under `:rf.causa.static.flows/*`)

    - `:rf.causa.static.flows/query`    — search input value.

  ## Pure hiccup

  Same contract as every Causa view — pure hiccup, no Reagent / UIx
  / Helix references. Frame isolation comes from the enclosing
  `[rf/frame-provider {:frame :rf/causa}]` in `static/shell.cljs`."
  (:require [clojure.string :as str]
            [re-frame.core :as rf]
            [re-frame.flows.registry :as flows-registry]
            [day8.re-frame2-causa.panel-registry :as panel-registry]
            [day8.re-frame2-causa.theme.tokens
             :as t
             :refer [tokens type-scale mono-stack sans-stack]]))

;; ---- pure helpers --------------------------------------------------------

(defn project-rows
  "Flatten `{frame-id {flow-id flow-map}}` into a flat vector of rows,
  sorted by flow-id ascending. Pure data so the JVM unit-test target
  can cover the shape without a CLJS runtime."
  [registry-snapshot]
  (->> registry-snapshot
       (mapcat (fn [[frame-id by-id]]
                 (map (fn [[flow-id flow-map]]
                        {:flow-id flow-id
                         :frame   frame-id
                         :inputs  (vec (:inputs flow-map))
                         :path    (vec (:path flow-map))
                         :doc     (:doc flow-map)})
                      by-id)))
       (sort-by (fn [{:keys [flow-id]}] (str flow-id)))
       vec))

(defn- row-haystack [{:keys [flow-id frame inputs path doc]}]
  (str/lower-case
    (str (pr-str flow-id) " "
         (pr-str frame) " "
         (pr-str inputs) " "
         (pr-str path) " "
         (or doc ""))))

(defn filter-rows
  "Substring filter against flow-id + frame + inputs + path + doc.
  Empty / blank query returns rows verbatim."
  [rows query]
  (let [q (some-> query str/trim)]
    (if (or (nil? q) (= "" q))
      rows
      (let [needle (str/lower-case q)]
        (filterv #(str/includes? (row-haystack %) needle) rows)))))

(defn project-data
  "View-facing composite. Folds the registered-flows map + UI controls
  into the shape `panel/Panel` consumes:

      {:silent?     <bool>
       :flows       [<row> ...]
       :total       <pre-filter count>
       :filtered?   <bool>
       :query       <string-or-nil>}"
  [registry-snapshot query]
  (let [rows     (project-rows registry-snapshot)
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
  ;; rf2-6xezz — Mike-direction 2026-05-21: panel-name heading scrubbed.
  ;; The L4 tab strip is the panel-name source-of-truth.
  [:div {:data-testid "rf-causa-static-flows-header"
         :style       {:padding "4px 16px"}}])

;; ---- search box ----------------------------------------------------------

(defn- search-box
  [query total filtered?]
  [:div {:data-testid "rf-causa-static-flows-search"
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
            :data-testid "rf-causa-static-flows-search-input"
            :placeholder "flow-id, path, or doc…"
            :value       (or query "")
            :on-change   (fn [e]
                           (rf/dispatch [:rf.causa.static.flows/set-query
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
   [:span {:data-testid "rf-causa-static-flows-search-count"
           :style       {:color       (:text-tertiary tokens)
                         :font-family mono-stack
                         :font-size   "11px"
                         :min-width   "80px"
                         :text-align  "right"}}
    (cond
      filtered?     "match"
      (= 1 total)   "1 flow"
      :else         (str total " flows"))]])

;; ---- row -----------------------------------------------------------------

(defn- flow-row
  [{:keys [flow-id frame inputs path doc] :as _row}]
  [:li {:data-testid (str "rf-causa-static-flows-row-"
                          (subs (pr-str flow-id) 1))
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
                    :min-width   "180px"}}
     (pr-str flow-id)]
    [:span {:data-testid (str "rf-causa-static-flows-frame-"
                              (subs (pr-str flow-id) 1))
            :style {:color     (:text-tertiary tokens)
                    :font-size "10px"}}
     (pr-str frame)]]
   [:div {:style {:margin-left  "12px"
                  :color        (:text-secondary tokens)
                  :font-size    "11px"
                  :line-height  1.4}}
    [:div
     [:span {:style {:color (:text-tertiary tokens)
                     :margin-right "6px"}}
      "inputs:"]
     (for [[i input-path] (map-indexed vector inputs)]
       ^{:key (str "in-" i)}
       [:code {:style {:color (:cyan tokens)
                       :margin-right "6px"}}
        (pr-str input-path)])]
    [:div
     [:span {:style {:color (:text-tertiary tokens)
                     :margin-right "6px"}}
      "output →"]
     [:code {:style {:color (:yellow tokens)}}
      (pr-str path)]]
    (when doc
      [:div {:style {:margin-top  "4px"
                     :color       (:text-secondary tokens)
                     :font-family sans-stack
                     :font-style  "italic"}}
       doc])]])

;; ---- empty states --------------------------------------------------------

(defn- empty-state
  []
  [:div {:data-testid "rf-causa-static-flows-empty"
         :style       {:padding     "16px"
                       :color       (:text-tertiary tokens)
                       :font-family sans-stack
                       :font-size   "11px"
                       :font-style  "italic"}}
   "No flows registered."])

(defn- empty-filtered
  [query]
  [:div {:data-testid "rf-causa-static-flows-empty-filtered"
         :style       {:padding     "16px"
                       :color       (:text-tertiary tokens)
                       :font-family sans-stack
                       :font-size   "11px"
                       :font-style  "italic"}}
   (str "No flows match " (pr-str query) ".")])

;; ---- root view -----------------------------------------------------------

(rf/reg-view Panel
  "Static Flows panel root view. Subscribes to the registered-flows
  composite + the search-query slot and composes the header + search +
  flat list.

  Per rf2-in6l2 `reg-view`-registered so subscribes resolve to
  `:rf/causa`."
  []
  (let [data @(rf/subscribe [:rf.causa.static.flows/tab-data])
        {:keys [silent? flows total filtered? query]} data]
    [:section {:data-testid "rf-causa-static-flows"
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
        (if (empty? flows)
          (empty-filtered query)
          (into [:ul {:data-testid "rf-causa-static-flows-list"
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

;; ---- registrations -------------------------------------------------------

(defn install!
  "Idempotent install for the Static Flows panel's subs + events.

  Registers:

    - `:rf.causa.static.flows/query`            — search input slot.
    - `:rf.causa.static.flows/set-query`        — search input setter.
    - `:rf.causa.static.flows/registered-flows-override` — test-only
                                                  override slot.
    - `:rf.causa.static.flows/set-registered-flows-override-for-test`
        — test-only override setter.
    - `:rf.causa.static.flows/registered-flows` — production data sub
                                                  reading the live
                                                  flows registry atom
                                                  (or override).
    - `:rf.causa.static.flows/tab-data`         — view-facing composite."
  []

  ;; ---- UI state ---------------------------------------------------------

  (rf/reg-event-db :rf.causa.static.flows/set-query
    (fn [db [_ q]]
      (if (or (nil? q) (= "" q))
        (dissoc db :rf.causa.static.flows/query)
        (assoc db :rf.causa.static.flows/query q))))

  (rf/reg-sub :rf.causa.static.flows/query
    (fn [db _]
      (get db :rf.causa.static.flows/query)))

  ;; ---- test-only override ----------------------------------------------

  (rf/reg-event-db :rf.causa.static.flows/set-registered-flows-override-for-test
    (fn [db [_ ov]]
      (if (nil? ov)
        (dissoc db :rf.causa.static.flows/registered-flows-override)
        (assoc db :rf.causa.static.flows/registered-flows-override ov))))

  (rf/reg-sub :rf.causa.static.flows/registered-flows-override
    (fn [db _]
      (get db :rf.causa.static.flows/registered-flows-override)))

  ;; ---- production data sub ---------------------------------------------

  ;; The flows registry is a top-level atom (per-frame) in the flows
  ;; artefact; deref it once per sub re-fire. `:<-`-composing against
  ;; `:rf.causa/trace-buffer` keeps the sub reactive against the same
  ;; "something changed" pulse the other static-mode subs ride —
  ;; without it, a fresh `reg-flow!` wouldn't surface until the next
  ;; subscribe re-render.
  (rf/reg-sub :rf.causa.static.flows/registered-flows
    :<- [:rf.causa/trace-buffer]
    :<- [:rf.causa.static.flows/registered-flows-override]
    (fn [[_buffer override] _query]
      (or override
          (try @flows-registry/flows
               (catch :default _ {})))))

  ;; ---- view-facing composite -------------------------------------------

  (rf/reg-sub :rf.causa.static.flows/tab-data
    :<- [:rf.causa.static.flows/registered-flows]
    :<- [:rf.causa.static.flows/query]
    (fn [[registry-snapshot query] _query]
      (project-data registry-snapshot query)))

  ;; rf2-2moh1 — register the Static Flows tab with the internal L4
  ;; tab registry. Contiguous order: machines 0 · routes 1 · schemas 2
  ;; · flows 3 · interceptors 4 (the standalone :views / :events tabs
  ;; rf2-b2fif removed previously left orders 3 + 5 as gaps).
  (panel-registry/reg-l4-tab!
    {:id    :flows
     :label "Flows"
     :mnem  "l"
     :modes #{:static}
     :order 3
     :panel Panel
     :placeholder-bead "rf2-uhsqb"})

  nil)
