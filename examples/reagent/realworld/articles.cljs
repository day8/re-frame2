(ns realworld.articles
  "Home-page article feeds for the RealWorld (Conduit) example.

   This sketch demonstrates:
   - Pattern-NineStates — one parallel state machine with three orthogonal
     regions (`:feed` × `:filter` × `:data`) replacing the prior multi-axis
     state-in-separate-slices shape (per rf2-qbau).
   - Pattern-RemoteData lifecycle folded into the `:data` region; the
     region's state-keyword IS the status. The actual article items still
     live in app-db slices (`:articles`, `:feed`) so optimistic-update
     paths in favorites.cljs continue to find articles across slices.
   - route-query driven loading (`?tag=` and `?feed=your`) — every
     navigation broadcasts the corresponding feed-region transition.
   - home-page tabs expressed as feed-region state transitions.
   - The home view's root is a `case` over `:articles.home/render`, a
     selector sub that consults a render-priority table against the
     machine's tag union (per Pattern-NineStates §4).
   - view reuse across the home page and profile pages."
  (:require [re-frame.core :as rf]
            ;; Per rf2-xbtj, the Spec 005 state-machine ns lives in the
            ;; day8/re-frame2-machines artefact. Loading the ns here
            ;; registers its late-bind hooks so rf/reg-machine (called
            ;; below at ns-load) and the `:rf/machine` framework subs
            ;; resolve.
            [re-frame.machines]
            [realworld.schema :as schema]
            [realworld.http :as rh]
            [realworld.routing :as routing])
  (:require-macros [re-frame.views-macros :refer [reg-view]]))

(defn current-time-ms [] (.getTime (js/Date.)))

(defn request-slice [data]
  {:status :idle :data data :error nil :loaded-at nil :attempt 0})

;; ============================================================================
;; THE MACHINE — :realworld/articles-home  (one machine, three regions)
;; ============================================================================
;;
;; The home page has three orthogonal axes:
;;
;;   :feed   — which feed is rendered (global / your-feed / tag-filtered
;;             global). The state-keyword tells the view which app-db
;;             slice's items to read from (`:articles` for :global and
;;             :tag-feed; `:feed` for :user-feed). Driven by the
;;             `:home/show-*` events which navigate the route's `?feed=`
;;             and `?tag=` query params.
;;
;;   :filter — whether a tag filter is active. Always `:tagged` whenever
;;             :feed is :tag-feed; tracked as a separate region so views
;;             that render the filter chip can ask a tag-shaped question
;;             without inspecting the feed region.
;;
;;   :data   — Pattern-NineStates' data lifecycle for whichever feed is
;;             active. The slice's `:status` field is gone — the region's
;;             state-keyword IS the lifecycle phase. The :resolving
;;             eventless microstep picks `:empty` or `:some` from the
;;             count stamped into the machine's shared `:data`.
;;
;; Per Spec 005 §Transition broadcast: every event delivered to the
;; machine is broadcast to every region. Region-distinct event names
;; below avoid collisions; the `:reset` event is handled by every
;; region as a self-target.

(def home-machine
  {:type :parallel

   ;; The machine carries the active feed's item-count (drives the
   ;; cardinality bucket) plus the latest error map. The items
   ;; themselves still live in app-db slices (`:articles`, `:feed`) so
   ;; the optimistic-update paths in favorites.cljs continue to find
   ;; them across slices.
   :data {:count 0 :error nil}

   :guards
   {:empty?
    (fn guard-empty? [data _event]
      (zero? (:count data 0)))}

   :actions
   {:set-count
    ;; :fetch-succeeded carries the resolved count under :items.
    (fn action-set-count [data [_ {:keys [items]}]]
      {:data (-> data
                 (assoc :count (count items))
                 (assoc :error nil))})

    :set-error
    (fn action-set-error [data [_ {:keys [failure]}]]
      {:data (assoc data :error failure)})

    :clear-count
    (fn action-clear-count [data _event]
      {:data (assoc data :count 0 :error nil)})}

   :regions
   {;; ---- :data region — Pattern-NineStates lifecycle ----
    :data
    {:initial :nothing
     :states
     {:nothing
      {:tags #{:data/nothing}
       :on   {:fetch-started :loading
              :reset         :nothing}}

      :loading
      ;; First fetch in flight; no prior items.
      {:tags #{:data/loading :data/transient}
       :on   {:fetch-succeeded {:target :resolving :action :set-count}
              :fetch-failed    {:target :error     :action :set-error}
              :reset           :nothing}}

      :refreshing
      ;; Reload while prior items remain visible. Tagged :data/some so
      ;; the render-priority resolves to the `:some` view; the
      ;; :data/refreshing tag drives the inline refresh indicator.
      {:tags #{:data/some :data/refreshing :data/transient}
       :on   {:fetch-succeeded {:target :resolving :action :set-count}
              :fetch-failed    {:target :error     :action :set-error}
              :reset           :nothing}}

      :resolving
      ;; Eventless microstep: after :set-count writes the new count,
      ;; pick the cardinality bucket. First match wins.
      {:always [{:guard :empty? :target :empty}
                {:target :some}]}

      :empty
      {:tags #{:data/empty}
       :on   {:fetch-started :loading
              :reset         :nothing}}

      :some
      {:tags #{:data/some}
       :on   {:fetch-started :refreshing
              :reset         :nothing}}

      :error
      {:tags #{:data/error}
       :on   {:fetch-started :loading
              :reset         :nothing}}}}

    ;; ---- :feed region — which feed the view renders ----
    :feed
    {:initial :global
     :states
     {:global
      ;; The default landing. Reads from the :articles slice.
      {:tags #{:feed/global}
       :on   {:show-user-feed {:target :user-feed :action :clear-count}
              :show-tag-feed  {:target :tag-feed  :action :clear-count}
              :show-global    :global
              :reset          :global}}

      :user-feed
      ;; `?feed=your`. Reads from the :feed slice. Authenticated only.
      {:tags #{:feed/user-feed}
       :on   {:show-global   {:target :global   :action :clear-count}
              :show-tag-feed {:target :tag-feed :action :clear-count}
              :show-user-feed :user-feed
              :reset         :global}}

      :tag-feed
      ;; `?tag=X`. Still reads from the :articles slice; the tag
      ;; modifies the request URL upstream.
      {:tags #{:feed/tag-feed}
       :on   {:show-global    {:target :global    :action :clear-count}
              :show-user-feed {:target :user-feed :action :clear-count}
              :show-tag-feed  :tag-feed
              :reset          :global}}}}

    ;; ---- :filter region — chip / sidebar state ----
    :filter
    {:initial :none
     :states
     {:none
      {:tags #{:filter/none}
       :on   {:apply-filter :tagged
              :clear-filter :none
              :reset        :none}}

      :tagged
      {:tags #{:filter/tagged}
       :on   {:apply-filter :tagged
              :clear-filter :none
              :reset        :none}}}}}})

(rf/reg-machine :realworld/articles-home home-machine)

;; ============================================================================
;; INITIALISATION
;; ============================================================================

(rf/reg-event-fx :articles/initialise
  {:doc "Seed the global-articles slice to the standard idle shape and
         reset the home machine to its initial configuration."}
  (fn [{:keys [db]} _]
    {:db (assoc db :articles (request-slice []))
     :fx [[:dispatch [:realworld/articles-home [:reset]]]]}))

;; ============================================================================
;; GLOBAL FEED
;; ============================================================================

(rf/reg-event-fx :articles/load
  {:doc "Fetch the global articles list, optionally filtered by the route's
         `?tag=` query parameter. Uses :rf.http/managed (Spec 014) with
         a Malli-decoded response and the standard data-fetch retry policy.
         The request is tagged with `:request-id :articles/load` so a
         re-issue (e.g., user changes tag mid-load) supersedes the prior
         in-flight request and an `:articles/cancel` fx aborts cleanly.

         Also broadcasts `:fetch-started` into the home machine so the
         `:data` region advances to `:loading` (or `:refreshing` from
         `:some`)."
   :rf.http/decode-schemas [schema/ArticlesResponse]}
  (fn [{:keys [db]} _]
    (let [tag       (get-in db [:rf/route :query :tag])
          path      (if tag
                      (str "/articles?tag=" tag)
                      "/articles")
          has-data? (seq (get-in db [:articles :data]))]
      {:db (-> db
               (assoc-in [:articles :status] (if has-data? :fetching :loading))
               (assoc-in [:articles :error] nil)
               (update-in [:articles :attempt] (fnil inc 0)))
       :fx [[:dispatch [:realworld/articles-home [:fetch-started]]]
            [:rf.http/managed
             (rh/request {:method     :get
                          :path       path
                          :auth?      false
                          :decode     schema/ArticlesResponse
                          :retry      rh/data-fetch-retry
                          :request-id :articles/load
                          :on-success [:articles/loaded]
                          :on-failure [:articles/load-failed]})]]})))

(rf/reg-event-fx :articles/loaded
  {:doc "Successful fetch. Replace the list and clear any prior error.
         Receives `{:kind :success :value <ArticlesResponse>}` from
         Spec 014's reply-addressing. Folds the new count into the home
         machine via `:fetch-succeeded`; the `:data` region's
         `:resolving` `:always`-cascade picks `:empty` or `:some`."}
  (fn [{:keys [db]} [_ {:keys [value]}]]
    (let [items (vec (:articles value))]
      {:db (-> db
               (assoc-in [:articles :status] :loaded)
               (assoc-in [:articles :data] items)
               (assoc-in [:articles :error] nil)
               (assoc-in [:articles :loaded-at] (current-time-ms)))
       :fx [[:dispatch [:realworld/articles-home
                        [:fetch-succeeded {:items items}]]]]})))

(rf/reg-event-fx :articles/load-failed
  {:doc "Failed fetch. Keep prior data when present and surface a
         human-readable error message (projected from the Spec 014
         failure map). Folds the failure into the home machine via
         `:fetch-failed`; the `:data` region advances to `:error`."}
  (fn [{:keys [db]} [_ {:keys [failure]}]]
    (let [message (rh/failure->message failure)]
      {:db (-> db
               (assoc-in [:articles :status] :error)
               (assoc-in [:articles :error] message))
       :fx [[:dispatch [:realworld/articles-home
                        [:fetch-failed {:failure message}]]]]})))

(rf/reg-event-fx :articles/cancel
  {:doc "Abort an in-flight :articles/load. Useful when the user navigates
         away from the home page mid-fetch (Spec 014 §Aborts)."}
  (fn [_ _]
    {:fx [[:rf.http/managed-abort :articles/load]]}))

(rf/reg-event-fx :articles/reset
  (fn [{:keys [db]} _]
    {:db (assoc db :articles (request-slice []))
     :fx [[:dispatch [:realworld/articles-home [:reset]]]]}))

;; ============================================================================
;; SUBSCRIPTIONS
;; ============================================================================

(rf/reg-sub :articles            (fn [db _] (:articles db)))
(rf/reg-sub :articles/data       :<- [:articles] (fn [s _] (:data s)))
(rf/reg-sub :articles/error      :<- [:articles] (fn [s _] (:error s)))

;; The `:tags/data` sub is defined in `realworld.tags`. Per rf2-0i4y
;; the popular-tags lifecycle is the :data-region machine variant of
;; Pattern-RemoteData — the slice is gone, items are projected off
;; the `:realworld/tags` machine's `:data`. The home view's sidebar
;; below consumes `:tags/data` as before; only the source changed.

;; ---- render-priority + :articles.home/render selector ----
;;
;; The render-priority table is plain data: a vector of {:tag :render}
;; pairs consulted in order. The `:articles.home/render` sub reads the
;; machine's tag union and returns the first :render whose :tag is
;; present. The home view's `case` over the resolved keyword is the
;; only branch site; everything else reads tags directly.
;;
;; Priority rationale: the data region's lifecycle wins outright —
;; `:loading` (first-load spinner) above `:error` above the cardinality
;; buckets. `:refreshing` resolves to `:some` (the prior list stays
;; visible, with an inline refresh indicator the `:some` view renders
;; via the `:data/refreshing` tag).

(def render-priority
  [{:tag :data/loading :render :loading}
   {:tag :data/error   :render :error}
   {:tag :data/empty   :render :empty}
   {:tag :data/some    :render :some}
   {:tag :data/nothing :render :empty}])

(rf/reg-sub :articles.home/render
  {:doc "Resolve the home page's render-model keyword by consulting the
         render-priority table against the machine's tag union. The
         root view's `case` is the only branch site."}
  :<- [:rf/machine :realworld/articles-home]
  (fn sub-articles-home-render [snap _]
    (let [tags (:tags snap)]
      (some (fn [{:keys [tag render]}]
              (when (contains? tags tag) render))
            render-priority))))

(rf/reg-sub :articles.home/active-articles
  {:doc "The article list currently rendered by the home view: the
         `:feed` region's active state-keyword picks which app-db slice
         to read from."}
  :<- [:rf/machine :realworld/articles-home]
  :<- [:articles/data]
  :<- [:feed/data]
  (fn sub-active-articles [[snap global-items feed-items] _]
    (case (get-in snap [:state :feed])
      :user-feed (or feed-items [])
      (or global-items []))))

;; ============================================================================
;; VIEWS
;; ============================================================================

(reg-view ^{:doc "A single article card used across the home page and profile pages."}
          article-preview [{:keys [article]}]
  (let [{:keys [slug title description createdAt favoritesCount author tagList]} article]
    [:div.article-preview
     [:div.article-meta
      [routing/route-link {:to     :route/profile
                           :params {:username (:username author)}}
       [:img {:src (:image author)}]]
      [:div.info
       [routing/route-link {:to     :route/profile
                            :params {:username (:username author)}
                            :class  "author"}
        (:username author)]
       [:span.date createdAt]]
      [:button.btn.btn-outline-primary.btn-sm.pull-xs-right
       {:type "button"
        :on-click #(dispatch [:article/toggle-favorite slug])}
       [:i.ion-heart] " " favoritesCount]]
     [routing/route-link {:to :route/article :params {:slug slug} :class "preview-link"}
      [:h1 title]
      [:p description]
      [:span "Read more..."]
      [:ul.tag-list
       (for [tag tagList]
         ^{:key tag}
         [:li.tag-default.tag-pill.tag-outline tag])]]]))

;; ---- per-render-state subviews ----

(reg-view ^{:doc "Data region :loading — first fetch in flight, no prior items."}
          articles-loading []
  [:div.article-preview "Loading articles…"])

(reg-view ^{:doc "Data region :error — fetch failed."}
          articles-error []
  (let [err @(subscribe [:articles/error])
        feed-err @(subscribe [:feed/error])]
    [:div.article-preview.error
     (str "Couldn't load articles: " (pr-str (or err feed-err)))]))

(reg-view ^{:doc "Data region :empty / :nothing — no articles to show."}
          articles-empty []
  [:div.article-preview "No articles are here… yet."])

(reg-view ^{:doc "Data region :some — articles to show. Inline refresh
                  indicator overlays when the :data/refreshing tag is
                  set (a same-feed reload in flight)."}
          articles-some []
  (let [articles    @(subscribe [:articles.home/active-articles])
        refreshing? @(rf/has-tag? :realworld/articles-home :data/refreshing)]
    [:<>
     (when refreshing? [:div.refresh-indicator "Refreshing…"])
     (for [article articles]
       ^{:key (:slug article)}
       [article-preview {:article article}])]))

(reg-view ^{:doc "Global feed / your feed / tag-filtered home page."}
          home-page []
  (let [authed?      @(subscribe [:auth/authenticated?])
        selected-tag @(subscribe [:home/selected-tag])
        on-user-feed? @(rf/has-tag? :realworld/articles-home :feed/user-feed)
        on-global?    @(rf/has-tag? :realworld/articles-home :feed/global)
        tag-filtered? @(rf/has-tag? :realworld/articles-home :filter/tagged)
        tags          @(subscribe [:tags/data])
        render-mode   @(subscribe [:articles.home/render])]
    [:div.home-page
     [:div.banner
      [:div.container
       [:h1.logo-font "conduit"]
       [:p "A place to share your knowledge."]]]
     [:div.container.page
      [:div.row
       [:div.col-md-9
        [:div.feed-toggle
         [:ul.nav.nav-pills.outline-active
          (when authed?
            [:li.nav-item
             [:a.nav-link
              {:href "#"
               :class (when on-user-feed? "active")
               :on-click #(do (.preventDefault %)
                              (dispatch [:home/show-your-feed]))}
              "Your Feed"]])
          [:li.nav-item
           [:a.nav-link
            {:href "#"
             :class (when on-global? "active")
             :on-click #(do (.preventDefault %)
                            (dispatch [:home/show-global-feed]))}
            "Global Feed"]]
          (when tag-filtered?
            [:li.nav-item
             [:a.nav-link.active
              {:href "#"
               :on-click #(do (.preventDefault %)
                              (dispatch [:tags/clear-filter]))}
              [:i.ion-pound] " " selected-tag]])]]
        (case render-mode
          :loading [articles-loading]
          :error   [articles-error]
          :empty   [articles-empty]
          :some    [articles-some]
          [articles-empty])]
       [:div.col-md-3
        [:div.sidebar
         [:p "Popular Tags"]
         [:div.tag-list
          (for [tag tags]
            ^{:key tag}
            [:a.tag-pill.tag-default
             {:href "#"
              :on-click #(do (.preventDefault %)
                             (dispatch [:tags/apply-filter tag]))}
             tag])]]]]]]))
