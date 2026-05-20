(ns day8.re-frame2-causa.static.events.panel
  "Top-level Events sub-tab for Causa's Static surface (rf2-o5f5f.6).

  ## Browse-all verb

  Per Lock #15 (two-verbs-two-homes — browse-all lives in Static) the
  Events sub-tab is a flat catalogue of every event handler registered
  via `reg-event-db` / `reg-event-fx` / `reg-event-ctx`. Per row:
  event-id, handler-type (db/fx/ctx), source-coord chip, and a small
  interceptor-count badge.

      ┌───────────────────────────────────────────────────┐
      │ Events — header + descriptive prose               │
      ├───────────────────────────────────────────────────┤
      │ Search: [_______________]            42 events    │
      ├───────────────────────────────────────────────────┤
      │ ▸ :user/login   [fx]  ic:3  [open]                │
      │ ▸ :counter/inc  [db]  ic:1                        │
      └───────────────────────────────────────────────────┘

  ## Detail view + hermetic simulate

  Clicking a row selects it; the detail card surfaces:

    - Handler source-coord + jump-to-source chip.
    - Interceptor chain rendered top-to-bottom — each entry shows the
      `:id`, before/after presence, and the `:rf/default?` framework-
      wrapper marker so user-attached interceptors stand out.
    - Single-step simulate-input — the user types an EDN payload
      (the event vector after the event-id); the simulate verb invokes
      the registered `:handler-fn` against a synthetic coeffects map
      ({:db <empty-map> :event <constructed-vector>}) and surfaces the
      return — for `:db` it's the new db; for `:fx` it's the closed
      `{:db ... :fx ...}` shape; for `:ctx` it's the full context.

  The simulate is fully hermetic: it invokes the handler-fn directly
  (NOT `dispatch`), so no real `:fx` walk, no app-db swap, no host
  side-effects. The handler-fn is the user's raw fn — interceptor
  chain composition is documented but NOT executed during simulate
  (interceptor stacks involve coeffect injection that would defeat
  hermeticity).

  ## State slots (all under `:rf.causa.static.events/*`)

    - `:rf.causa.static.events/query`             — search input.
    - `:rf.causa.static.events/selected-id`       — focused row.
    - `:rf.causa.static.events/sim-input`         — pending EDN payload.
    - `:rf.causa.static.events/sim-result`        — last simulate-output.

  ## Pure hiccup

  Same contract as every Causa view — pure hiccup, no Reagent / UIx
  / Helix references. Frame isolation comes from the enclosing
  `[rf/frame-provider {:frame :rf/causa}]` in `static/shell.cljs`."
  (:require [clojure.string :as str]
            [cljs.reader :as reader]
            [re-frame.core :as rf]
            [re-frame.registrar :as registrar]
            [day8.re-frame2-causa.open-in-editor :as open-in-editor]
            [day8.re-frame2-causa.panel-registry :as panel-registry]
            [day8.re-frame2-causa.static.events.helpers :as helpers]
            [day8.re-frame2-causa.theme.tokens
             :as t
             :refer [tokens type-scale mono-stack sans-stack]]))

;; ---- pure helpers --------------------------------------------------------

(defn project-rows
  "Project the registrar's `:event` `{id meta}` map into row maps sorted
  by id ascending. Each row carries `:id`, `:kind` (`:db`/`:fx`/`:ctx`),
  `:doc`, `:interceptor-count`, and the source-coord trio."
  [registrations-map]
  (->> registrations-map
       (map (fn [[id meta]]
              {:id                id
               :kind              (:event/kind meta)
               :doc               (:doc meta)
               :interceptor-count (count (or (:interceptors meta) []))
               :source-coord      (select-keys meta [:file :line :ns])}))
       (sort-by (fn [{:keys [id]}] (pr-str id)))
       vec))

(defn- row-haystack [{:keys [id kind doc source-coord]}]
  (str/lower-case
    (str (pr-str id) " "
         (or (some-> kind name) "") " "
         (or doc "") " "
         (or (:ns source-coord) "") " "
         (or (:file source-coord) ""))))

(defn filter-rows
  [rows query]
  (let [q (some-> query str/trim)]
    (if (or (nil? q) (= "" q))
      rows
      (let [needle (str/lower-case q)]
        (filterv #(str/includes? (row-haystack %) needle) rows)))))

(defn project-data
  [registrations-map query selected-id]
  (let [rows     (project-rows registrations-map)
        silent?  (empty? rows)
        filtered (filter-rows rows query)
        selected (when selected-id
                   (some #(when (= selected-id (:id %)) %) rows))]
    {:silent?     silent?
     :events      filtered
     :total       (count rows)
     :filtered?   (not= (count rows) (count filtered))
     :query       query
     :selected-id selected-id
     :selected    selected}))

;; ---- header --------------------------------------------------------------

(defn- header
  []
  ;; rf2-6xezz — Mike-direction 2026-05-21: the panel-name heading is
  ;; scrubbed; the L4 tab strip is the panel-name source-of-truth. The
  ;; testid `rf-causa-static-events-header` is preserved as an empty
  ;; spacer to keep the static-panel tests' shell-shape assertions
  ;; working — replace with nil when those tests update.
  [:div {:data-testid "rf-causa-static-events-header"
         :style       {:padding "4px 16px"}}])

;; ---- search box ----------------------------------------------------------

(defn- search-box
  [query total filtered?]
  [:div {:data-testid "rf-causa-static-events-search"
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
            :data-testid "rf-causa-static-events-search-input"
            :placeholder "event-id, kind, ns, file, or doc…"
            :value       (or query "")
            :on-change   (fn [e]
                           (rf/dispatch [:rf.causa.static.events/set-query
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
   [:span {:data-testid "rf-causa-static-events-search-count"
           :style       {:color       (:text-tertiary tokens)
                         :font-family mono-stack
                         :font-size   "11px"
                         :min-width   "80px"
                         :text-align  "right"}}
    (cond
      filtered?     "match"
      (= 1 total)   "1 event"
      :else         (str total " events"))]])

;; ---- row -----------------------------------------------------------------

(defn- kind-badge
  "Small letter-tile naming the handler kind. Mirrors the schemas
  panel's `kind-badge` shape so the Static surface reads as one
  family."
  [kind]
  (let [{:keys [letter colour]}
        (case kind
          :db  {:letter "D" :colour (:cyan tokens)}
          :fx  {:letter "F" :colour (:magenta tokens)}
          :ctx {:letter "C" :colour (:yellow tokens)}
          {:letter "?" :colour (:text-tertiary tokens)})]
    [:span {:data-testid (str "rf-causa-static-events-badge-" (some-> kind name))
            :title       (str "reg-event-" (some-> kind name))
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

(defn- ic-chip
  "Interceptor-count chip — small `ic:N` badge on each row so the user
  can sort visually for chain depth at a glance."
  [n]
  [:span {:data-testid "rf-causa-static-events-ic-chip"
          :title       (str n " interceptor(s) on the chain")
          :style       {:color       (:text-tertiary tokens)
                        :font-family mono-stack
                        :font-size   "10px"
                        :padding     "0 4px"
                        :background  (:bg-3 tokens)
                        :border      (str "1px solid " (:border-subtle tokens))
                        :border-radius "3px"}}
   (str "ic:" n)])

(defn- event-row
  [{:keys [id kind doc interceptor-count source-coord] :as _row}
   selected?]
  (let [id-text (pr-str id)
        row-id  (helpers/sanitize-row-id id)]
    [:li {:data-testid (str "rf-causa-static-events-row-" row-id)
          :on-click    (fn [_]
                         (rf/dispatch [:rf.causa.static.events/select id]
                                      {:frame :rf/causa}))
          :aria-selected (if selected? "true" "false")
          :style       {:display       "block"
                        :padding       "6px 12px"
                        :font-family   mono-stack
                        :font-size     "12px"
                        :color         (:text-primary tokens)
                        :background    (if selected?
                                         (:bg-1 tokens)
                                         "transparent")
                        :border-left   (str "2px solid "
                                            (if selected?
                                              (:cyan tokens)
                                              "transparent"))
                        :border-radius "2px"
                        :cursor        "pointer"
                        :line-height   "18px"}}
     [:div {:style {:display     "flex"
                    :align-items "baseline"
                    :gap         "8px"}}
      (kind-badge kind)
      [:span {:style {:color       (:accent-violet tokens)
                      :font-weight 500
                      :flex        1}}
       id-text]
      (ic-chip interceptor-count)
      (when (and source-coord (:file source-coord))
        [open-in-editor/open-chip source-coord])]
     (when doc
       [:div {:style {:margin-left "20px"
                      :margin-top  "2px"
                      :color       (:text-secondary tokens)
                      :font-family sans-stack
                      :font-style  "italic"
                      :font-size   "11px"}}
        doc])]))

;; ---- detail (right rail) -------------------------------------------------

(defn- interceptor-chain
  "Render the registered interceptor chain top-to-bottom. Each entry's
  `:id` is the headline; `:rf/default?` marker tags framework-emitted
  auto-wrappers (per spec/004 + rf2-twt7m) so user-attached
  interceptors stand out."
  [interceptors]
  (if (empty? interceptors)
    [:div {:data-testid "rf-causa-static-events-chain-empty"
           :style       {:color       (:text-tertiary tokens)
                         :font-family sans-stack
                         :font-size   "11px"
                         :font-style  "italic"
                         :padding     "8px 0"}}
     "No interceptors on the chain."]
    [:ol {:data-testid "rf-causa-static-events-chain-list"
          :style       {:list-style "none"
                        :padding    0
                        :margin     0
                        :display    "flex"
                        :flex-direction "column"
                        :gap        "4px"}}
     (for [[idx ic] (map-indexed vector interceptors)]
       (let [icpt-id (:id ic)]
         ^{:key idx}
         [:li {:data-testid (str "rf-causa-static-events-chain-row-" idx)
               :style       {:padding "4px 8px"
                             :border  (str "1px solid " (:border-subtle tokens))
                             :border-radius "3px"
                             :background (:bg-1 tokens)
                             :font-family mono-stack
                             :font-size   "11px"
                             :display "flex"
                             :align-items "center"
                             :gap "6px"}}
          [:span {:style {:color (:text-tertiary tokens) :min-width "20px"}}
           (str "#" idx)]
          [:span {:style {:color (:accent-violet tokens) :font-weight 600}}
           (pr-str icpt-id)]
          (when (:rf/default? ic)
            [:span {:data-testid (str "rf-causa-static-events-chain-default-" idx)
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
          [:span {:style {:color (:text-tertiary tokens)
                          :margin-left "auto"
                          :font-family sans-stack
                          :font-size "10px"}}
           (str (when (:before ic) "before ")
                (when (:after ic) "after"))]]))]))

(defn- simulate-form
  [event-id sim-input]
  [:div {:data-testid "rf-causa-static-events-sim-form"
         :style       {:display "flex"
                       :flex-direction "column"
                       :gap "4px"
                       :margin-top "8px"}}
   [:label {:style {:color (:text-tertiary tokens)
                    :font-family sans-stack
                    :font-size "10px"
                    :text-transform "uppercase"
                    :letter-spacing "0.5px"}}
    "Simulate-input (EDN payload — appended after the event-id)"]
   [:textarea
    {:data-testid "rf-causa-static-events-sim-input"
     :placeholder (str (pr-str event-id) " " "{:foo 1}    ;; or just :bar")
     :value       (or sim-input "")
     :rows        2
     :on-change   (fn [e]
                    (rf/dispatch [:rf.causa.static.events/set-sim-input
                                  (-> e .-target .-value)]
                                 {:frame :rf/causa}))
     :style       {:background (:bg-3 tokens)
                   :color (:text-primary tokens)
                   :border (str "1px solid " (:border-default tokens))
                   :border-radius "4px"
                   :padding "5px 8px"
                   :font-family mono-stack
                   :font-size "11px"
                   :resize "vertical"}}]
   [:div {:style {:display "flex" :gap "8px" :align-items "center"}}
    [:button
     {:data-testid "rf-causa-static-events-sim-run"
      :on-click    (fn [_]
                     (rf/dispatch [:rf.causa.static.events/run-simulate
                                   {:event-id event-id
                                    :payload-edn sim-input}]
                                  {:frame :rf/causa}))
      :style       {:background (:yellow tokens)
                    :color (:bg-1 tokens)
                    :border "none"
                    :border-radius "4px"
                    :padding "5px 12px"
                    :cursor "pointer"
                    :font-family sans-stack
                    :font-size "11px"
                    :font-weight 600}}
     "Simulate ▶︎"]
    [:span {:style {:color (:text-tertiary tokens)
                    :font-family sans-stack
                    :font-size "10px"
                    :font-style "italic"}}
     "hermetic — no dispatch, no fx walk, no app-db swap"]]])

(defn- simulate-result
  [{:keys [ok? kind value reason] :as sim-result}]
  (when sim-result
    [:div {:data-testid "rf-causa-static-events-sim-result"
           :style {:margin-top "8px"
                   :padding "8px"
                   :border (str "1px solid "
                                (if ok? (:cyan tokens) (:red tokens)))
                   :background (if ok? (:bg-1 tokens) "rgba(248, 113, 113, 0.08)")
                   :border-radius "4px"
                   :font-family mono-stack
                   :font-size "11px"
                   :color (:text-primary tokens)
                   :white-space "pre-wrap"
                   :word-break "break-all"}}
     [:div {:style {:color (if ok? (:cyan tokens) (:red tokens))
                    :font-family sans-stack
                    :font-weight 600
                    :font-size "10px"
                    :text-transform "uppercase"
                    :letter-spacing "0.5px"
                    :margin-bottom "4px"}}
      (if ok?
        (str "Result (" (some-> kind name) " handler)")
        "Simulate failed")]
     [:code (if ok? (pr-str value) (str reason))]]))

(defn- detail-card
  "Right-rail / inline detail card for the selected event. Shows
  the source-coord, the interceptor chain, and the simulate form +
  most recent result."
  [{:keys [selected] :as _data} sim-input sim-result registrations-map]
  (when selected
    (let [{:keys [id kind doc source-coord]} selected
          meta (get registrations-map id)
          interceptors (or (:interceptors meta) [])]
      [:section {:data-testid "rf-causa-static-events-detail"
                 :style {:padding "12px 16px"
                         :border-top (str "1px solid " (:cyan tokens))
                         :background (:bg-2 tokens)
                         :font-family sans-stack
                         :font-size "12px"
                         :color (:text-primary tokens)
                         :display "flex"
                         :flex-direction "column"
                         :gap "8px"}}
       [:div {:style {:display "flex" :align-items "baseline" :gap "8px"}}
        (kind-badge kind)
        [:code {:style {:color (:accent-violet tokens)
                        :font-family mono-stack
                        :font-size "13px"
                        :font-weight 600}}
         (pr-str id)]
        (when (and source-coord (:file source-coord))
          [open-in-editor/open-chip source-coord])]
       (when doc
         [:div {:data-testid "rf-causa-static-events-detail-doc"
                :style {:color (:text-secondary tokens)
                        :font-style "italic"
                        :font-size "11px"}}
          doc])
       [:div {:style {:margin-top "4px"}}
        [:label {:style {:color (:text-tertiary tokens)
                         :font-size "10px"
                         :text-transform "uppercase"
                         :letter-spacing "0.5px"
                         :display "block"
                         :margin-bottom "4px"}}
         (str "Interceptor chain (" (count interceptors) ")")]
        (interceptor-chain interceptors)]
       (simulate-form id sim-input)
       (simulate-result sim-result)])))

;; ---- empty states --------------------------------------------------------

(defn- empty-state
  []
  [:div {:data-testid "rf-causa-static-events-empty"
         :style       {:padding     "16px"
                       :color       (:text-tertiary tokens)
                       :font-family sans-stack
                       :font-size   "11px"
                       :font-style  "italic"}}
   "No event handlers registered."])

(defn- empty-filtered
  [query]
  [:div {:data-testid "rf-causa-static-events-empty-filtered"
         :style       {:padding     "16px"
                       :color       (:text-tertiary tokens)
                       :font-family sans-stack
                       :font-size   "11px"
                       :font-style  "italic"}}
   (str "No events match " (pr-str query) ".")])

;; ---- root view -----------------------------------------------------------

(rf/reg-view Panel
  "Static Events panel root view. Subscribes to the registered-events
  composite + the search-query slot + the selected-id slot + the
  simulate state, then composes the header / search / list / detail
  card.

  Per rf2-in6l2 `reg-view`-registered so subscribes resolve to
  `:rf/causa`."
  []
  (let [data         @(rf/subscribe [:rf.causa.static.events/tab-data])
        registry-map @(rf/subscribe [:rf.causa.static.events/registry])
        sim-input    @(rf/subscribe [:rf.causa.static.events/sim-input])
        sim-result   @(rf/subscribe [:rf.causa.static.events/sim-result])
        {:keys [silent? events total filtered? query]} data]
    [:section {:data-testid "rf-causa-static-events"
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
        [:div {:style {:flex 1
                       :min-height 0
                       :display "flex"
                       :flex-direction "column"
                       :overflow "auto"}}
         (if (empty? events)
           (empty-filtered query)
           (into [:ul {:data-testid "rf-causa-static-events-list"
                       :style       {:list-style     "none"
                                     :margin         "8px 0 0 0"
                                     :padding        "0 8px"
                                     :display        "flex"
                                     :flex-direction "column"
                                     :gap            "2px"}}]
                 (for [row events]
                   ^{:key (pr-str (:id row))}
                   [event-row row (= (:id row) (:selected-id data))])))
         (detail-card data sim-input sim-result registry-map)]])]))

;; ---- registrations -------------------------------------------------------

(defn install!
  "Idempotent install for the Static Events panel's subs + events.

  Registers:

    - `:rf.causa.static.events/query`              — search input slot.
    - `:rf.causa.static.events/set-query`          — search setter.
    - `:rf.causa.static.events/selected-id`        — focused row id.
    - `:rf.causa.static.events/select`             — row-click event.
    - `:rf.causa.static.events/sim-input`          — pending EDN payload.
    - `:rf.causa.static.events/set-sim-input`      — payload setter.
    - `:rf.causa.static.events/sim-result`         — last simulate-output.
    - `:rf.causa.static.events/run-simulate`       — hermetic simulate.
    - `:rf.causa.static.events/registry-override`  — test-only override.
    - `:rf.causa.static.events/set-registry-override-for-test`
        — test-only setter.
    - `:rf.causa.static.events/registry`           — production data sub.
    - `:rf.causa.static.events/tab-data`           — view composite."
  []

  ;; ---- UI state ---------------------------------------------------------

  (rf/reg-event-db :rf.causa.static.events/set-query
    (fn [db [_ q]]
      (if (or (nil? q) (= "" q))
        (dissoc db :rf.causa.static.events/query)
        (assoc db :rf.causa.static.events/query q))))

  (rf/reg-sub :rf.causa.static.events/query
    (fn [db _]
      (get db :rf.causa.static.events/query)))

  (rf/reg-event-db :rf.causa.static.events/select
    (fn [db [_ id]]
      ;; Clicking the already-selected row clears the selection so the
      ;; detail card collapses. Clicking a new row also clears any prior
      ;; sim-input/result so the user isn't confused by stale output.
      (let [cur (get db :rf.causa.static.events/selected-id)]
        (if (= cur id)
          (-> db
              (dissoc :rf.causa.static.events/selected-id)
              (dissoc :rf.causa.static.events/sim-input)
              (dissoc :rf.causa.static.events/sim-result))
          (-> db
              (assoc :rf.causa.static.events/selected-id id)
              (dissoc :rf.causa.static.events/sim-input)
              (dissoc :rf.causa.static.events/sim-result))))))

  (rf/reg-sub :rf.causa.static.events/selected-id
    (fn [db _]
      (get db :rf.causa.static.events/selected-id)))

  (rf/reg-event-db :rf.causa.static.events/set-sim-input
    (fn [db [_ s]]
      (if (or (nil? s) (= "" s))
        (dissoc db :rf.causa.static.events/sim-input)
        (assoc db :rf.causa.static.events/sim-input s))))

  (rf/reg-sub :rf.causa.static.events/sim-input
    (fn [db _]
      (get db :rf.causa.static.events/sim-input)))

  (rf/reg-sub :rf.causa.static.events/sim-result
    (fn [db _]
      (get db :rf.causa.static.events/sim-result)))

  ;; ---- run-simulate ----------------------------------------------------
  ;;
  ;; Hermetic: look up the registered meta, parse the EDN payload (if
  ;; any), construct the event vector `[event-id & payload-args]`, and
  ;; invoke the handler-fn directly with a synthetic input. NO real
  ;; dispatch — the interceptor chain is documented in the detail card
  ;; but NOT executed (interceptor stacks involve cofx injection that
  ;; would defeat hermeticity).

  (rf/reg-event-db :rf.causa.static.events/run-simulate
    (fn [db [_ {:keys [event-id payload-edn]}]]
      (let [registrations
            (or (get db :rf.causa.static.events/registry-override)
                (try (registrar/registrations :event)
                     (catch :default _ {})))
            result (helpers/run-simulate
                     registrations event-id payload-edn
                     reader/read-string)]
        (assoc db :rf.causa.static.events/sim-result result))))

  ;; ---- test-only override ----------------------------------------------

  (rf/reg-event-db :rf.causa.static.events/set-registry-override-for-test
    (fn [db [_ ov]]
      (if (nil? ov)
        (dissoc db :rf.causa.static.events/registry-override)
        (assoc db :rf.causa.static.events/registry-override ov))))

  (rf/reg-sub :rf.causa.static.events/registry-override
    (fn [db _]
      (get db :rf.causa.static.events/registry-override)))

  ;; ---- production data sub ---------------------------------------------

  ;; Walks the registrar's `:event` slot. `:<-`-composes against the
  ;; trace buffer so newly-registered events surface as soon as the
  ;; trace-buffer's next reactive pulse fires.
  (rf/reg-sub :rf.causa.static.events/registry
    :<- [:rf.causa/trace-buffer]
    :<- [:rf.causa.static.events/registry-override]
    (fn [[_buffer override] _query]
      (or override
          (try (registrar/registrations :event)
               (catch :default _ {})))))

  ;; ---- view-facing composite -------------------------------------------

  (rf/reg-sub :rf.causa.static.events/tab-data
    :<- [:rf.causa.static.events/registry]
    :<- [:rf.causa.static.events/query]
    :<- [:rf.causa.static.events/selected-id]
    (fn [[registrations-map query selected-id] _query]
      (project-data registrations-map query selected-id)))

  ;; rf2-2moh1 — register the Static Events tab. Order 5 — sits after
  ;; :flows (4) per the parent-epic findings doc §2.4.
  (panel-registry/reg-l4-tab!
    {:id    :events
     :label "Events"
     :mnem  "e"
     :modes #{:static}
     :order 5
     :panel Panel
     :placeholder-bead "rf2-o5f5f.6"})

  nil)
