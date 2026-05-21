(ns day8.re-frame2-causa.static.interceptors.panel
  "Top-level Interceptors sub-tab for Causa's Static surface (rf2-o5f5f.6).

  ## Pure-browse verb

  Per Lock #15 (two-verbs-two-homes — browse-all lives in Static) the
  Interceptors sub-tab is a flat catalogue of every interceptor
  surfaced through registered events. Per spec/004 the interceptor
  shape is a map carrying `:id`, optional `:before`/`:after` fns, and
  the framework `:rf/default?` marker for auto-wrappers (rf2-twt7m).

      ┌───────────────────────────────────────────────────┐
      │ Interceptors — header + descriptive prose         │
      ├───────────────────────────────────────────────────┤
      │ Search: [_______________]           14 interceptors│
      ├───────────────────────────────────────────────────┤
      │ ▸ :rf/db-handler   before  [default]              │
      │ ▸ :my/logging      before/after                   │
      └───────────────────────────────────────────────────┘

  ## Data source

  Walks `(rf/registrations :event)` and harvests the
  `:interceptors` chain from each entry; collapses by `:id` so an
  interceptor that appears on many chains shows up once with the count
  of chains it appears on. Interceptors with no `:id` (rare —
  `->interceptor` requires one) fall under `::unnamed`.

  ## Pure-browse — no simulate

  Per the bead body: 'Pure-browse (no simulate-input — interceptors
  are composition; simulate fires through the handler-level simulate
  above).' The Events panel's hermetic simulate is the only simulate
  affordance in the Static surface.

  ## State slots (all under `:rf.causa.static.interceptors/*`)

    - `:rf.causa.static.interceptors/query`    — search input value."
  (:require [clojure.string :as str]
            [re-frame.core :as rf]
            [day8.re-frame2-causa.panel-registry :as panel-registry]
            [day8.re-frame2-causa.theme.tokens
             :as t
             :refer [tokens type-scale mono-stack sans-stack]]))

;; ---- pure helpers --------------------------------------------------------

(defn collect-interceptors
  "Walk every registered event's `:interceptors` chain and return a
  vector of row maps. Collapses by `:id` so an interceptor that appears
  on many chains lands as one row with `:chain-count` set."
  [registrations-map]
  (let [pairs (for [[event-id meta] registrations-map
                    icpt           (or (:interceptors meta) [])]
                [(or (:id icpt) ::unnamed) event-id icpt])
        by-id (reduce
                (fn [acc [icpt-id event-id icpt]]
                  (-> acc
                      (update-in [icpt-id :sample]
                                 #(or % icpt))
                      (update-in [icpt-id :event-ids]
                                 (fnil conj #{}) event-id)))
                {}
                pairs)]
    (->> by-id
         (map (fn [[icpt-id {:keys [sample event-ids]}]]
                {:id          icpt-id
                 :before?     (boolean (:before sample))
                 :after?      (boolean (:after sample))
                 :default?    (boolean (:rf/default? sample))
                 :chain-count (count event-ids)
                 :doc         (:doc sample)
                 :comment     (:comment sample)}))
         (sort-by (fn [{:keys [id]}] (pr-str id)))
         vec)))

(defn- row-haystack [{:keys [id doc comment]}]
  (str/lower-case
    (str (pr-str id) " "
         (or doc "") " "
         (or comment ""))))

(defn filter-rows
  [rows query]
  (let [q (some-> query str/trim)]
    (if (or (nil? q) (= "" q))
      rows
      (let [needle (str/lower-case q)]
        (filterv #(str/includes? (row-haystack %) needle) rows)))))

(defn project-data
  [registrations-map query]
  (let [rows     (collect-interceptors registrations-map)
        silent?  (empty? rows)
        filtered (filter-rows rows query)]
    {:silent?      silent?
     :interceptors filtered
     :total        (count rows)
     :filtered?    (not= (count rows) (count filtered))
     :query        query}))

;; ---- header --------------------------------------------------------------

(defn- header
  []
  ;; rf2-6xezz — Mike-direction 2026-05-21: panel-name heading scrubbed.
  [:div {:data-testid "rf-causa-static-interceptors-header"
         :style       {:padding "4px 16px"}}])

;; ---- search box ----------------------------------------------------------

(defn- search-box
  [query total filtered?]
  [:div {:data-testid "rf-causa-static-interceptors-search"
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
            :data-testid "rf-causa-static-interceptors-search-input"
            :placeholder "interceptor-id or doc…"
            :value       (or query "")
            :on-change   (fn [e]
                           (rf/dispatch [:rf.causa.static.interceptors/set-query
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
   [:span {:data-testid "rf-causa-static-interceptors-search-count"
           :style       {:color       (:text-tertiary tokens)
                         :font-family mono-stack
                         :font-size   "11px"
                         :min-width   "90px"
                         :text-align  "right"}}
    (cond
      filtered?     "match"
      (= 1 total)   "1 interceptor"
      :else         (str total " interceptors"))]])

;; ---- row -----------------------------------------------------------------

(defn- interceptor-row
  [{:keys [id before? after? default? chain-count doc] :as _row}]
  (let [id-text (pr-str id)
        row-id  (if (and (> (count id-text) 0) (= \: (first id-text)))
                  (subs id-text 1)
                  id-text)]
    ;; rf2-mq8wk — list semantics. Interceptor rows are non-interactive
    ;; catalogue entries (no row-level dispatch), so `role=listitem` is
    ;; the right shape rather than `role=button`.
    [:li {:data-testid (str "rf-causa-static-interceptors-row-" row-id)
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
      [:span {:style {:color       (:accent-violet tokens)
                      :font-weight 500
                      :flex        1}}
       id-text]
      [:span {:title (str (cond
                            (and before? after?) "both before AND after"
                            before?              "before-only"
                            after?               "after-only"
                            :else                "neither hook")
                          " hook")
              :style {:color (:text-tertiary tokens)
                      :font-family sans-stack
                      :font-size "10px"}}
       (cond
         (and before? after?) "before/after"
         before?              "before"
         after?               "after"
         :else                "—")]
      (when default?
        [:span {:data-testid (str "rf-causa-static-interceptors-default-" row-id)
                :title "Framework-emitted auto-wrapper (rf2-twt7m)"
                :style {:color (:text-tertiary tokens)
                        :font-family sans-stack
                        :font-size "9px"
                        :padding "1px 4px"
                        :border (str "1px solid " (:text-tertiary tokens))
                        :border-radius "2px"
                        :text-transform "uppercase"
                        :letter-spacing "0.5px"}}
         "default"])
      [:span {:title (str "appears on " chain-count " chain(s)")
              :style {:color (:text-tertiary tokens)
                      :font-family mono-stack
                      :font-size "10px"
                      :padding "0 4px"
                      :background (:bg-3 tokens)
                      :border (str "1px solid " (:border-subtle tokens))
                      :border-radius "3px"}}
       (str "x" chain-count)]]
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
  [:div {:data-testid "rf-causa-static-interceptors-empty"
         :style       {:padding     "16px"
                       :color       (:text-tertiary tokens)
                       :font-family sans-stack
                       :font-size   "11px"
                       :font-style  "italic"}}
   "No interceptors registered."])

(defn- empty-filtered
  [query]
  [:div {:data-testid "rf-causa-static-interceptors-empty-filtered"
         :style       {:padding     "16px"
                       :color       (:text-tertiary tokens)
                       :font-family sans-stack
                       :font-size   "11px"
                       :font-style  "italic"}}
   (str "No interceptors match " (pr-str query) ".")])

;; ---- root view -----------------------------------------------------------

(rf/reg-view Panel
  "Static Interceptors panel root view. Subscribes to the
  interceptors composite + search slot.

  Per rf2-in6l2 `reg-view`-registered so subscribes resolve to
  `:rf/causa`."
  []
  (let [data @(rf/subscribe [:rf.causa.static.interceptors/tab-data])
        {:keys [silent? interceptors total filtered? query]} data]
    [:section {:data-testid "rf-causa-static-interceptors"
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
        (if (empty? interceptors)
          (empty-filtered query)
          (into [:ul {:data-testid "rf-causa-static-interceptors-list"
                      :role        "list"
                      :style       {:list-style     "none"
                                    :margin         "8px 0 0 0"
                                    :padding        "0 8px"
                                    :flex           1
                                    :overflow       "auto"
                                    :display        "flex"
                                    :flex-direction "column"
                                    :gap            "2px"}}]
                (for [row interceptors]
                  ^{:key (pr-str (:id row))}
                  [interceptor-row row])))])]))

;; ---- registrations -------------------------------------------------------

(defn install!
  "Idempotent install for the Static Interceptors panel's subs + events.

  Registers:

    - `:rf.causa.static.interceptors/query`             — search input slot.
    - `:rf.causa.static.interceptors/set-query`         — search setter.
    - `:rf.causa.static.interceptors/registry-override` — test seam.
    - `:rf.causa.static.interceptors/set-registry-override-for-test`
        — test seam setter (payload shape: registrations map).
    - `:rf.causa.static.interceptors/registry`          — production data sub.
    - `:rf.causa.static.interceptors/tab-data`          — view-facing composite."
  []

  ;; ---- UI state ---------------------------------------------------------

  (rf/reg-event-db :rf.causa.static.interceptors/set-query
    (fn [db [_ q]]
      (if (or (nil? q) (= "" q))
        (dissoc db :rf.causa.static.interceptors/query)
        (assoc db :rf.causa.static.interceptors/query q))))

  (rf/reg-sub :rf.causa.static.interceptors/query
    (fn [db _]
      (get db :rf.causa.static.interceptors/query)))

  ;; ---- test-only override ----------------------------------------------

  (rf/reg-event-db :rf.causa.static.interceptors/set-registry-override-for-test
    (fn [db [_ ov]]
      (if (nil? ov)
        (dissoc db :rf.causa.static.interceptors/registry-override)
        (assoc db :rf.causa.static.interceptors/registry-override ov))))

  (rf/reg-sub :rf.causa.static.interceptors/registry-override
    (fn [db _]
      (get db :rf.causa.static.interceptors/registry-override)))

  ;; ---- production data sub ---------------------------------------------

  (rf/reg-sub :rf.causa.static.interceptors/registry
    :<- [:rf.causa/trace-buffer]
    :<- [:rf.causa.static.interceptors/registry-override]
    (fn [[_buffer override] _query]
      (or override
          (try (rf/registrations :event)
               (catch :default _ {})))))

  ;; ---- view-facing composite -------------------------------------------

  (rf/reg-sub :rf.causa.static.interceptors/tab-data
    :<- [:rf.causa.static.interceptors/registry]
    :<- [:rf.causa.static.interceptors/query]
    (fn [[registrations-map query] _query]
      (project-data registrations-map query)))

  ;; rf2-2moh1 — register the Static Interceptors tab. Contiguous order:
  ;; machines 0 · routes 1 · schemas 2 · flows 3 · interceptors 4.
  (panel-registry/reg-l4-tab!
    {:id    :interceptors
     :label "Interceptors"
     :mnem  "i"
     :modes #{:static}
     :order 4
     :panel Panel
     :placeholder-bead "rf2-o5f5f.6"})

  nil)
