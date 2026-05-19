(ns day8.re-frame2-causa.panels.routing
  "Routes tab — the 7th L4 tab (rf2-nrbs9, reshaped per rf2-lq0ef).

  Per Mike's design call (2026-05-18 16:35): Routes earns its own
  top-level tab in the Causa shell rather than piling into App-db
  (`app-db panel gets busy`). Routing slices are a cohesive sub-domain
  — the route catalogue + the current match + nav transitions — and
  the bd memory `cohesive-subdomains-get-their-own-tab` codifies the
  posture: cohesive sub-domains earn their own lens tab.

  ## Lens model (post-rf2-lq0ef reshape)

  Routes are FLAT in the spec + impl. The previous tree projection
  indented rows by URL-path-segment depth — purely decorative, no
  semantic load (audit verdict B,
  `ai/findings/2026-05-19-routing-inheritance-audit.md`). The new
  lens mirrors the actual contract:

  - **Flat catalogue** sorted by `:path` (lexicographic). No
    indentation, no depth derivation.
  - **Per-row chips**: route-id + path + doc, with small letter
    badges (`M` / `L` / `T` / `P`) for routes carrying `:on-match` /
    `:can-leave` / `:tags` / `:parent`. Click the row to expand the
    full registrar meta inline.
  - **Substring search** across route-id + path + doc.
  - **Simulate-URL** — paste a URL, see every route that matches
    plus its 6-rule `:rf.route/rank` tuple; the winner is the
    first row by rank-descending (mirrors `match-url`).
  - **`:parent` annotation** — when a row carries `:parent`, render
    a compact `↑ → :route/account` inline pointer; expanding the row
    surfaces the full `:rf.route/chain`.

  Per-focused-event highlighting (unchanged from rf2-nrbs9):

  - `◆ HERE` on the current matched route (always — orientation).
  - `◆ FROM` / `◆ TO` arrow when the focused cascade caused
    navigation (the cascade carries a
    `:rf.route.nav-token/allocated` trace event).
  - Params + query for the active route surface below the catalogue.
  - When the app has no routing registered: tab content silent
    (no `(none)` placeholder per rf2-g3ghh silent-by-default).

  ## Pure hiccup (rf2-tijr)

  Same contract as every other Causa panel — the view is pure
  hiccup, no Reagent / UIx / Helix references. Frame isolation
  comes from the enclosing `[rf/frame-provider {:frame :rf/causa}]`
  in `shell.cljs`. Every `subscribe` / `dispatch` here resolves to
  `:rf/causa`.

  ## Helpers

  Pure-data projection (`project-routes`, `project-data`,
  `from-to-from-cascade`, `assign-markers`, `filter-rows`,
  `simulate-url`) lives in `routing_helpers.cljc` so the algebra runs
  under the JVM unit-test target."
  (:require [re-frame.core :as rf]
            [day8.re-frame2-causa.panels.routing-helpers :as h]
            [day8.re-frame2-causa.theme.tokens
             :as t
             :refer [tokens mono-stack sans-stack]]))

;; ---- visual primitives --------------------------------------------------

(defn- marker-chip
  "Render the `◆ HERE` / `◆ FROM` / `◆ TO` marker chip for a route row.
  Returns nil when no marker — the row aligns naturally with its
  siblings (the chip column is empty)."
  [marker]
  (when marker
    (let [{:keys [label colour]}
          (case marker
            :here {:label "◆ HERE" :colour (:accent-violet tokens)}
            :from {:label "◆ FROM" :colour (:cyan tokens)}
            :to   {:label "◆ TO"   :colour (:green tokens)}
            nil)]
      (when label
        [:span {:data-testid (str "rf-causa-routing-marker-" (name marker))
                :style       {:color         colour
                              :font-weight   600
                              :font-family   sans-stack
                              :font-size     "11px"
                              :letter-spacing "0.3px"
                              :margin-left   "8px"
                              :white-space   "nowrap"}}
         label]))))

(defn- meta-badge
  "Single-letter badge surfacing presence of a metadata key. Letters
  are deliberate one-glyph hints — `M` = `:on-match`, `L` =
  `:can-leave`, `T` = `:tags`, `P` = `:parent`. The colour scheme
  maps to the semantic palette: `:can-leave` is yellow (guard),
  `:on-match` is cyan (event), `:tags` is magenta (user labels),
  `:parent` is violet (hierarchy hint)."
  [kind]
  (let [{:keys [letter colour title]}
        (case kind
          :on-match  {:letter "M" :colour (:cyan tokens)         :title ":on-match"}
          :can-leave {:letter "L" :colour (:yellow tokens)       :title ":can-leave"}
          :tags      {:letter "T" :colour (:magenta tokens)      :title ":tags"}
          :parent    {:letter "P" :colour (:accent-violet tokens) :title ":parent"}
          nil)]
    (when letter
      [:span {:data-testid (str "rf-causa-routing-badge-" (name kind))
              :title       title
              :style       {:display         "inline-block"
                            :min-width       "14px"
                            :height          "14px"
                            :line-height     "14px"
                            :padding         "0 3px"
                            :margin-right    "4px"
                            :background      (:bg-3 tokens)
                            :color           colour
                            :border          (str "1px solid " colour)
                            :border-radius   "3px"
                            :font-family     mono-stack
                            :font-size       "9px"
                            :font-weight     700
                            :text-align      "center"}}
       letter])))

(defn- parent-annotation
  "Compact inline `↑ :route/account` pointer rendered next to a row
  with `:parent`. The audit found this is the ONLY semantically
  load-bearing `:parent` axis (`:rf.route/chain` for view-shell
  composition); the lens surfaces it as a per-row hint rather than
  a tree-defining structure."
  [parent-id]
  (when parent-id
    [:span {:data-testid "rf-causa-routing-parent-ptr"
            :style       {:color       (:text-tertiary tokens)
                          :font-family mono-stack
                          :font-size   "11px"
                          :margin-left "8px"
                          :white-space "nowrap"}}
     (str "↑ " parent-id)]))

(defn- meta-expander
  "Click-to-expand registrar meta map. Renders the full meta as
  `pr-str` text, indented under the row. Used when the user has
  clicked the row's chevron to drill in."
  [row]
  [:pre {:data-testid (str "rf-causa-routing-meta-"
                           (subs (pr-str (:route-id row)) 1))
         :style       {:margin "4px 0 8px 24px"
                       :padding "8px 12px"
                       :background (:bg-1 tokens)
                       :border-left (str "2px solid " (:border-default tokens))
                       :color (:text-secondary tokens)
                       :font-family mono-stack
                       :font-size "11px"
                       :white-space "pre-wrap"
                       :overflow-x "auto"
                       :max-height "200px"
                       :overflow-y "auto"}}
   (with-out-str
     (println "; route" (:route-id row))
     (println (pr-str (:meta row))))])

(defn- route-row
  "One row in the catalogue. No indentation — routes are flat.
  Each row carries: chevron (expand toggle), marker chip column,
  badges, route-id (mono), path (highlighted), doc (italic), and
  the parent pointer when present."
  [{:keys [route-id path marker doc parent has-on-match? has-can-leave? tags]
    :as row}
   {:keys [expanded? on-toggle]}]
  ;; testid uses the fully-qualified keyword (namespace + name) so two
  ;; routes whose simple names collide (e.g. `:cart/edit` and
  ;; `:admin/edit`) render with distinct testids.
  [:li {:data-testid (str "rf-causa-routing-row-" (subs (pr-str route-id) 1))
        :data-marker (when marker (name marker))
        :style       {:display        "block"
                      :padding        "0"
                      :font-family    mono-stack
                      :font-size      "12px"
                      :color          (:text-primary tokens)
                      :background     (case marker
                                        :to   (:bg-active tokens)
                                        :from (:bg-2 tokens)
                                        :here (:bg-2 tokens)
                                        "transparent")
                      :border-left    (case marker
                                        :to   (str "2px solid " (:green tokens))
                                        :from (str "2px solid " (:cyan tokens))
                                        :here (str "2px solid " (:accent-violet tokens))
                                        "2px solid transparent")
                      :border-radius  "2px"
                      :line-height    "20px"}}
   [:div {:style {:display "flex"
                  :align-items "center"
                  :gap "8px"
                  :padding "3px 8px"
                  :cursor "pointer"
                  :white-space "nowrap"
                  :overflow "hidden"}
          :on-click on-toggle}
    [:span {:data-testid (str "rf-causa-routing-chevron-"
                              (subs (pr-str route-id) 1))
            :style {:color (:text-tertiary tokens)
                    :font-size "10px"
                    :min-width "12px"
                    :user-select "none"}}
     (if expanded? "▾" "▸")]
    [:span {:style {:color (:accent-violet tokens)
                    :font-weight 500
                    :min-width "120px"}}
     path]
    [:span {:style {:color (:text-tertiary tokens)
                    :font-size "11px"
                    :min-width "140px"}}
     (str route-id)]
    [:span {:style {:display "inline-flex"
                    :align-items "center"
                    :margin-left "4px"}}
     (when has-on-match?  (meta-badge :on-match))
     (when has-can-leave? (meta-badge :can-leave))
     (when (seq tags)     (meta-badge :tags))
     (when parent         (meta-badge :parent))]
    (when doc
      [:span {:style {:color (:text-secondary tokens)
                      :font-size "11px"
                      :font-style "italic"
                      :font-family sans-stack
                      :overflow "hidden"
                      :text-overflow "ellipsis"
                      :flex 1}}
       doc])
    (parent-annotation parent)
    (marker-chip marker)]
   (when expanded?
     (meta-expander row))])

;; ---- search box ---------------------------------------------------------

(defn- search-box
  "Substring filter input. Matches against route-id + path + doc per
  `routing_helpers/filter-rows`."
  [query total-routes filtered?]
  [:div {:data-testid "rf-causa-routing-search"
         :style       {:display "flex"
                       :align-items "center"
                       :gap "8px"
                       :padding "8px 16px"
                       :border-bottom (str "1px solid " (:border-subtle tokens))
                       :font-family sans-stack}}
   [:label {:style {:color (:text-tertiary tokens)
                    :font-size "10px"
                    :text-transform "uppercase"
                    :letter-spacing "0.5px"
                    :min-width "60px"}}
    "Search"]
   [:input {:type        "text"
            :data-testid "rf-causa-routing-search-input"
            :placeholder "route-id, path, or doc…"
            :value       (or query "")
            :on-change   (fn [e]
                           (rf/dispatch [:rf.causa.routing/set-query
                                         (-> e .-target .-value)]
                                        {:frame :rf/causa}))
            :style       {:flex 1
                          :background (:bg-3 tokens)
                          :color (:text-primary tokens)
                          :border (str "1px solid " (:border-default tokens))
                          :border-radius "3px"
                          :padding "4px 8px"
                          :font-family mono-stack
                          :font-size "12px"}}]
   [:span {:data-testid "rf-causa-routing-search-count"
           :style {:color (:text-tertiary tokens)
                   :font-family mono-stack
                   :font-size "11px"
                   :min-width "60px"
                   :text-align "right"}}
    (cond
      filtered?              (str "match")
      (= 1 total-routes)     "1 route"
      :else                  (str total-routes " routes"))]])

;; ---- Simulate-URL -------------------------------------------------------

(defn- rank-cell
  "Render a single rank tuple as compact mono text. The 6-tuple is
  `[static total -splat catch-all? -optional -reg-index]` per
  `parse-pattern`; we surface it verbatim — the lens is about exposing
  the cascade, not interpreting it."
  [rank]
  [:span {:style {:font-family mono-stack
                  :font-size "11px"
                  :color (:text-secondary tokens)}}
   (pr-str rank)])

(defn- sim-candidate-row
  "One row in the Simulate-URL result table — winner highlighted."
  [{:keys [route-id rank params winner? path]}]
  [:li {:data-testid (str "rf-causa-routing-sim-candidate-"
                          (subs (pr-str route-id) 1))
        :data-winner (when winner? "true")
        :style       {:display "flex"
                      :align-items "center"
                      :gap "8px"
                      :padding "3px 8px"
                      :background (if winner? (:bg-active tokens) "transparent")
                      :border-left (if winner?
                                     (str "2px solid " (:green tokens))
                                     "2px solid transparent")
                      :border-radius "2px"
                      :font-family mono-stack
                      :font-size "12px"
                      :white-space "nowrap"}}
   [:span {:style {:color (if winner? (:green tokens) (:text-tertiary tokens))
                   :font-weight 600
                   :font-size "10px"
                   :min-width "50px"}}
    (if winner? "WINNER" "")]
   [:span {:style {:color (:accent-violet tokens)
                   :min-width "140px"}}
    path]
   [:span {:style {:color (:text-tertiary tokens)
                   :font-size "11px"
                   :min-width "160px"}}
    (str route-id)]
   (rank-cell rank)
   (when (seq params)
     [:span {:style {:color (:text-secondary tokens)
                     :font-size "11px"
                     :margin-left "8px"}}
      (pr-str params)])])

(defn- simulate-url-section
  "URL simulator surface — paste a URL, see every matching route and
  its rank tuple, winner highlighted."
  [sim-url sim-result]
  [:div {:data-testid "rf-causa-routing-sim"
         :style       {:padding "10px 16px"
                       :border-top (str "1px solid " (:border-subtle tokens))
                       :border-bottom (str "1px solid " (:border-subtle tokens))
                       :background (:bg-1 tokens)}}
   [:div {:style {:display "flex"
                  :align-items "center"
                  :gap "8px"
                  :font-family sans-stack}}
    [:label {:style {:color (:text-tertiary tokens)
                     :font-size "10px"
                     :text-transform "uppercase"
                     :letter-spacing "0.5px"
                     :min-width "60px"}}
     "Try URL"]
    [:input {:type        "text"
             :data-testid "rf-causa-routing-sim-input"
             :placeholder "/cart  or  /checkout/payment?step=2"
             :value       (or sim-url "")
             :on-change   (fn [e]
                            (rf/dispatch [:rf.causa.routing/set-sim-url
                                          (-> e .-target .-value)]
                                         {:frame :rf/causa}))
             :style       {:flex 1
                           :background (:bg-3 tokens)
                           :color (:text-primary tokens)
                           :border (str "1px solid " (:border-default tokens))
                           :border-radius "3px"
                           :padding "4px 8px"
                           :font-family mono-stack
                           :font-size "12px"}}]
    (when (and sim-url (not= "" sim-url))
      [:button {:data-testid "rf-causa-routing-sim-clear"
                :on-click (fn [_]
                            (rf/dispatch [:rf.causa.routing/set-sim-url ""]
                                         {:frame :rf/causa}))
                :style {:background "transparent"
                        :border (str "1px solid " (:border-default tokens))
                        :border-radius "3px"
                        :color (:text-tertiary tokens)
                        :padding "3px 8px"
                        :font-family sans-stack
                        :font-size "11px"
                        :cursor "pointer"}}
       "clear"])]
   (when sim-result
     (let [{:keys [path candidates winner]} sim-result]
       [:div {:data-testid "rf-causa-routing-sim-result"
              :style {:margin-top "8px"}}
        [:div {:style {:font-family mono-stack
                       :font-size "11px"
                       :color (:text-tertiary tokens)
                       :padding "0 8px 4px 8px"}}
         (str "matched against path " (pr-str path) " — "
              (cond
                (empty? candidates) "no route matches (match-url → nil)"
                :else (str (count candidates)
                           (if (= 1 (count candidates)) " candidate" " candidates")
                           "; winner = " winner)))]
        (when (seq candidates)
          [:div {:style {:display "grid"
                         :grid-template-columns "50px 140px 160px 1fr"
                         :column-gap "8px"
                         :padding "0 8px 2px 8px"
                         :font-family sans-stack
                         :font-size "10px"
                         :color (:text-tertiary tokens)
                         :text-transform "uppercase"
                         :letter-spacing "0.4px"}}
           [:span ""] [:span "path"] [:span "route-id"] [:span "rank · params"]])
        (into [:ul {:style {:list-style "none"
                            :margin "0"
                            :padding "0"
                            :display "flex"
                            :flex-direction "column"
                            :gap "1px"}}]
              (for [c candidates]
                ^{:key (str (:route-id c))}
                [sim-candidate-row c]))]))])

;; ---- header / slice-detail / empty-state --------------------------------

(defn- header
  [{:keys [navigated? to-id]}]
  [:div {:data-testid "rf-causa-routing-header"
         :style       {:display       "flex"
                       :align-items   "baseline"
                       :justify-content "space-between"
                       :padding       "12px 16px 8px 16px"
                       :border-bottom (str "1px solid " (:border-subtle tokens))
                       :font-family   sans-stack}}
   [:div
    ;; rf2-5kfxe.8 — domain-coloured accent stripe (:yellow for
    ;; Routing — side-channel attention tone, distinguished from
    ;; app-db's main colour).
    [:h2 {:style (merge {:margin     "0"
                         :font-size  "13px"
                         :font-weight 600
                         :text-transform "uppercase"
                         :letter-spacing "0.5px"
                         :color      (:text-primary tokens)}
                        (t/accent-stripe-style :routing))}
     "Routes"]
    [:p {:style {:margin "4px 0 0 0"
                 :color  (:text-tertiary tokens)
                 :font-size "11px"
                 :line-height 1.4}}
     "Flat catalogue of registered routes — search above, paste a URL "
     "into Try URL to see the 6-rule rank cascade. "
     [:span {:style {:color (:accent-violet tokens) :font-weight 600}} "◆ HERE"]
     " marks the active route"
     (when navigated?
       [:span "; "
        [:span {:style {:color (:cyan tokens) :font-weight 600}} "◆ FROM"]
        " / "
        [:span {:style {:color (:green tokens) :font-weight 600}} "◆ TO"]
        " mark the navigation transition"])
     "."]]
   (when (and navigated? to-id)
     [:span {:data-testid "rf-causa-routing-nav-summary"
             :style {:color (:green tokens)
                     :font-size "11px"
                     :font-family mono-stack}}
      (str "→ " to-id)])])

(defn- slice-detail
  "Params / query / fragment breakdown for the current route slice."
  [{:keys [params query fragment] :as _current}]
  [:div {:data-testid "rf-causa-routing-slice-detail"
         :style       {:padding     "10px 16px"
                       :border-top  (str "1px dotted " (:border-subtle tokens))
                       :font-family mono-stack
                       :font-size   "12px"
                       :color       (:text-primary tokens)
                       :display     "grid"
                       :grid-template-columns "80px 1fr"
                       :row-gap     "4px"
                       :column-gap  "12px"}}
   [:span {:style {:color (:text-tertiary tokens)}} "Params:"]
   [:span (if (seq params) (pr-str params) "—")]
   [:span {:style {:color (:text-tertiary tokens)}} "Query:"]
   [:span (if (seq query) (pr-str query) "—")]
   (when fragment
     [:<>
      [:span {:style {:color (:text-tertiary tokens)}} "Fragment:"]
      [:span (pr-str fragment)]])])

(defn- empty-state
  []
  [:div {:data-testid "rf-causa-routing-empty"
         :style       {:padding "16px"
                       :color   (:text-tertiary tokens)
                       :font-family sans-stack
                       :font-size "11px"
                       :font-style "italic"}}
   "No routes registered."])

;; ---- public view --------------------------------------------------------

(rf/reg-view Panel
  "The Routes tab's root view. Subscribes to
  `:rf.causa/routing-tab-data` and renders the flat catalogue +
  Simulate-URL + slice detail (or the silent state when no routes
  are registered)."
  []
  (let [{:keys [silent? routes total-routes filtered? current to-id navigated?
                query sim-url sim-result]
         :as _data}
        @(rf/subscribe [:rf.causa/routing-tab-data])
        expanded @(rf/subscribe [:rf.causa.routing/expanded])]
    [:section {:data-testid "rf-causa-routing"
               :style       {:height         "100%"
                             :display        "flex"
                             :flex-direction "column"
                             :background     (:bg-2 tokens)
                             :color          (:text-primary tokens)
                             :font-family    sans-stack
                             :font-size      "14px"}}
     (header {:navigated? navigated? :to-id to-id})
     (if silent?
       (empty-state)
       [:<>
        (search-box query total-routes filtered?)
        (simulate-url-section sim-url sim-result)
        [:div {:style {:flex 1 :overflow "auto"}}
         (into [:ul {:data-testid "rf-causa-routing-list"
                     :style       {:list-style "none"
                                   :margin     "8px 0 0 0"
                                   :padding    "0 8px"
                                   :display    "flex"
                                   :flex-direction "column"
                                   :gap        "1px"}}]
               (for [row routes]
                 ^{:key (str (:route-id row))}
                 [route-row row
                  {:expanded? (contains? expanded (:route-id row))
                   :on-toggle (fn [_]
                                (rf/dispatch [:rf.causa.routing/toggle-row
                                              (:route-id row)]
                                             {:frame :rf/causa}))}]))
         (when current
           (slice-detail current))]])]))

;; ---- registration entry --------------------------------------------------

(defn install!
  "Idempotent install for the Routes panel's Causa-side registrations
  (rf2-nrbs9, reshaped per rf2-lq0ef). Registers:

    - `:rf.causa/registered-routes` — flat `{<route-id> <meta>}` map
      sourced from `(rf/registrations :route)`. Falls back to a
      test-only override slot so JVM / node-test fixtures can drive
      the projection without booting a host that registers routes.
    - `:rf.causa/current-route-slice` — composite over the spine's
      target-frame app-db reading the `:rf/route` slice.
    - `:rf.causa/routing-tab-data` — view-facing composite folding
      registered-routes + current slice + focused cascade + search
      query + Simulate-URL into the shape the view consumes (see
      `routing_helpers/project-data`).
    - `:rf.causa.routing/query` + `:rf.causa.routing/sim-url` +
      `:rf.causa.routing/expanded` — UI state slots (search input,
      Simulate-URL input, expanded-row set).
    - `:rf.causa.routing/set-query`,
      `:rf.causa.routing/set-sim-url`,
      `:rf.causa.routing/toggle-row` — dispatch hooks the view fires.
    - `:rf.causa/set-registered-routes-override-for-test` — test-only
      override hook the gallery fixtures + JVM tests use to seed the
      registered-routes slot without registering real routes.
    - `:rf.causa/set-current-route-slice-override-for-test` — same
      pattern for the current slice."
  []

  ;; Test-only override slots --------------------------------------------

  (rf/reg-event-db :rf.causa/set-registered-routes-override-for-test
    (fn [db [_ ov]]
      (if (nil? ov)
        (dissoc db :registered-routes-override)
        (assoc db :registered-routes-override ov))))

  (rf/reg-sub :rf.causa/registered-routes-override
    (fn [db _query]
      (get db :registered-routes-override)))

  (rf/reg-event-db :rf.causa/set-current-route-slice-override-for-test
    (fn [db [_ ov]]
      (if (nil? ov)
        (dissoc db :current-route-slice-override)
        (assoc db :current-route-slice-override ov))))

  (rf/reg-sub :rf.causa/current-route-slice-override
    (fn [db _query]
      (get db :current-route-slice-override)))

  ;; UI state (search query, simulate-URL input, expanded rows) ----------

  (rf/reg-event-db :rf.causa.routing/set-query
    (fn [db [_ q]]
      (if (or (nil? q) (= "" q))
        (dissoc db :rf.causa.routing/query)
        (assoc db :rf.causa.routing/query q))))

  (rf/reg-sub :rf.causa.routing/query
    (fn [db _]
      (get db :rf.causa.routing/query)))

  (rf/reg-event-db :rf.causa.routing/set-sim-url
    (fn [db [_ url]]
      (if (or (nil? url) (= "" url))
        (dissoc db :rf.causa.routing/sim-url)
        (assoc db :rf.causa.routing/sim-url url))))

  (rf/reg-sub :rf.causa.routing/sim-url
    (fn [db _]
      (get db :rf.causa.routing/sim-url)))

  (rf/reg-event-db :rf.causa.routing/toggle-row
    (fn [db [_ route-id]]
      (let [expanded (or (:rf.causa.routing/expanded db) #{})]
        (assoc db :rf.causa.routing/expanded
               (if (contains? expanded route-id)
                 (disj expanded route-id)
                 (conj expanded route-id))))))

  (rf/reg-sub :rf.causa.routing/expanded
    (fn [db _]
      (or (:rf.causa.routing/expanded db) #{})))

  ;; Production data subs -------------------------------------------------

  (rf/reg-sub :rf.causa/registered-routes
    :<- [:rf.causa/trace-buffer]
    :<- [:rf.causa/registered-routes-override]
    (fn [[_buffer override] _query]
      (or override (rf/registrations :route))))

  (rf/reg-sub :rf.causa/current-route-slice
    :<- [:rf.causa/target-frame-db]
    :<- [:rf.causa/current-route-slice-override]
    (fn [[target-db override] _query]
      (cond
        (some? override) override
        (map? target-db) (:rf/route target-db)
        :else            nil)))

  ;; View-facing composite -----------------------------------------------

  (rf/reg-sub :rf.causa/routing-tab-data
    :<- [:rf.causa/registered-routes]
    :<- [:rf.causa/current-route-slice]
    :<- [:rf.causa/cascades]
    :<- [:rf.causa/focus]
    :<- [:rf.causa.routing/query]
    :<- [:rf.causa.routing/sim-url]
    (fn [[routes-map slice cascades focus query sim-url] _query]
      (let [focused-cascade (h/focused-cascade cascades
                                               (:dispatch-id focus))]
        (h/project-data routes-map slice focused-cascade query sim-url))))

  nil)
