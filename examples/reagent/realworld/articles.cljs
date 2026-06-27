(ns realworld.articles
  "Home-page article feeds for the RealWorld (Conduit) example.

   This namespace shows:
   - One parallel state machine with three orthogonal regions
     (`:feed` x `:filter` x `:data`). See the machines guide on parallel
     regions: ../../../docs/machines/concepts.md#when-the-machine-grows.
   - A remote-data lifecycle tracked by the `:data` region. The region's
     state-keyword drives the home-page render decision. The `:articles`
     and `:feed` slices keep the plain 5-key slice shape so their items
     live in app-db and favorites.cljs's optimistic updates can scan
     across slices. The machine's `:data` region tracks the same lifecycle
     and owns the render decision. See the `:data` region note below.
   - Route-driven loading: the `/tag/:tag` route filters the list and
     `?feed=following` switches to the authenticated feed (the official
     RealWorld contract shapes). Each navigation broadcasts the matching
     feed-region transition.
   - Home-page tabs expressed as feed-region transitions.
   - The home view's root is a `case` over `:articles.home/render`, a
     selector sub that reads a render-priority table against the machine's
     tag union.
   - View reuse across the home page and profile pages."
  (:require [re-frame.core :as rf]
            ;; State machines ship in the re-frame2-machines artefact.
            ;; Requiring the ns registers its hooks so `rf/reg-machine`
            ;; (called below) and the `:rf/machine` subs resolve. See the
            ;; machines guide: ../../../docs/machines/index.md
            [re-frame.machines]
            [realworld-shared.avatar :as avatar]
            [realworld.schema :as schema]
            [realworld.http :as rh])
  (:require-macros [re-frame.core :refer [reg-view]]))

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
;;             home navigation events: the following toggle rides the
;;             `?feed=following` query, and the tag filter is the
;;             `/tag/:tag` PATH route (`:realworld/home-tag`).
;;
;;   :filter — whether a tag filter is active. Always `:tagged` whenever
;;             :feed is :tag-feed; tracked as a separate region so views
;;             that render the filter chip can ask a tag-shaped question
;;             without inspecting the feed region.
;;
;;   :data   — the data lifecycle for whichever feed is active. The
;;             region's state-keyword drives the home-page render decision
;;             (via the render-priority table + the `:articles.home/render`
;;             selector sub); the view never branches on the slice's
;;             `:status`. The :resolving eventless microstep picks `:empty`
;;             or `:some` from the count stored in the machine's `:data`.
;;
;;             The `:articles` slice keeps the plain 5-key slice shape: its
;;             article items live in app-db so favorites.cljs's optimistic
;;             updates can scan across slices, and the slice keeps its
;;             `:status` field. The machine's `:data` region tracks the
;;             same lifecycle and owns the render decision. tags.cljs and
;;             settings.cljs show the other shape, where the lifecycle
;;             lives entirely in the machine and there is no slice.
;;
;; Every event delivered to the machine reaches every region (the parallel
;; region broadcast). Region-distinct event names avoid collisions; each
;; region handles `:reset` as a self-target.

(def home-machine
  {:type :parallel

   ;; The machine carries the active feed's item-count (drives the
   ;; cardinality bucket) plus the latest error map. The items live in
   ;; app-db slices (`:articles`, `:feed`) so favorites.cljs's optimistic
   ;; updates can find them across slices.
   :data {:count 0 :error nil}

   :guards
   {:empty?
    (fn guard-empty? [{data :data}]
      (zero? (:count data 0)))}

   :actions
   {:set-count
    ;; :fetch-succeeded carries the resolved count under :items.
    (fn action-set-count [{data :data [_ {:keys [items]}] :event}]
      {:data (-> data
                 (assoc :count (count items))
                 (assoc :error nil))})

    :set-error
    (fn action-set-error [{data :data [_ {:keys [failure]}] :event}]
      {:data (assoc data :error failure)})

    :clear-count
    (fn action-clear-count [{data :data}]
      {:data (assoc data :count 0 :error nil)})}

   :regions
   {;; ---- :data region — the data lifecycle ----
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
      ;; `?feed=following`. Reads from the :feed slice. Authenticated only.
      {:tags #{:feed/user-feed}
       :on   {:show-global   {:target :global   :action :clear-count}
              :show-tag-feed {:target :tag-feed :action :clear-count}
              :show-user-feed :user-feed
              :reset         :global}}

      :tag-feed
      ;; The `/tag/:tag` PATH route. Still reads from the :articles slice;
      ;; the tag modifies the request URL upstream (the WIRE stays
      ;; `/articles?tag=…`).
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

(rf/reg-event :articles/initialise
  {:doc "Seed the global-articles slice to the standard idle shape and
         reset the home machine to its initial configuration."}
  (fn [{:keys [db]} _]
    {:db (assoc db :articles (request-slice []))
     :fx [[:dispatch [:realworld/articles-home [:reset]]]]}))

;; ============================================================================
;; GLOBAL FEED
;; ============================================================================

(rf/reg-event :articles/load
  {:doc "Fetch the global articles list, optionally filtered by the active
         tag (the `/tag/:tag` route param; the wire query stays
         `/articles?tag=…`). Goes via `:rf.http/managed` with a
         Malli-decoded response and the standard data-fetch retry policy.
         The request carries `:request-id :articles/load` so re-issuing it
         (e.g. the user changes tag mid-load) supersedes the in-flight
         request and `:articles/cancel` aborts it. See the HTTP guide:
         ../../../docs/resources/http.md

         Also broadcasts `:fetch-started` into the home machine so the
         `:data` region advances to `:loading` (or `:refreshing` from
         `:some`)."
   :rf.http/decode-schemas [schema/ArticlesResponse]}
  ;; The route lives in runtime-db. `?page=` (1-indexed, on the route
  ;; query) becomes the wire's limit/offset window via `rh/paginate-path`.
  ;; The active tag is a `/tag/:tag` route param; the page is on the query.
  ;; The wire stays `/articles?tag=…` — the frontend route shape and the
  ;; API query string are independent.
  (fn [{:keys [db] rt :rf.db/runtime} _]
    (let [current   (get-in rt [:rf.runtime/routing :current])
          tag       (get-in current [:params :tag])
          page      (or (get-in current [:query :page]) 1)
          base      (if tag
                      (str "/articles?tag=" tag)
                      "/articles")
          path      (rh/paginate-path base page)
          has-data? (seq (get-in db [:articles :data]))]
      {:db (-> db
               (assoc-in [:articles :status] (if has-data? :fetching :loading))
               (assoc-in [:articles :error] nil)
               (update-in [:articles :attempt] (fnil inc 0)))
       :fx [[:dispatch [:realworld/articles-home [:fetch-started]]]
            [:rf.http/managed
             (rh/request {:method     :get
                          :path       path
                          :decode     schema/ArticlesResponse
                          :retry      rh/data-fetch-retry
                          :request-id :articles/load
                          :on-success [:articles/loaded]
                          :on-failure [:articles/load-failed]})]]})))

(rf/reg-event :articles/loaded
  {:doc "Successful fetch. Replace the list and clear any prior error.
         Receives `{:kind :success :value <ArticlesResponse>}` (the
         uniform reply map). Folds the new count into the home machine via
         `:fetch-succeeded`; the `:data` region's `:resolving` `:always`
         cascade then picks `:empty` or `:some`."
   :rf.cofx/requires [:rf/time-ms]}
  (fn [{:keys [db rf/time-ms]} [_ {:keys [value]}]]
    (let [items (vec (:articles value))
          ;; `articlesCount` is the grand total (all matching articles),
          ;; not this page's size — it drives the page count. Fall back to
          ;; the page size when the server omits it.
          total (or (:articlesCount value) (count items))]
      {:db (-> db
               (assoc-in [:articles :status] :loaded)
               (assoc-in [:articles :data] items)
               (assoc-in [:articles :articles-count] total)
               (assoc-in [:articles :error] nil)
               (assoc-in [:articles :loaded-at] time-ms))
       :fx [[:dispatch [:realworld/articles-home
                        [:fetch-succeeded {:items items}]]]]})))

(rf/reg-event :articles/load-failed
  {:doc "Failed fetch. Keep prior data when present and surface a
         human-readable error message (projected from the failure map).
         Folds the failure into the home machine via `:fetch-failed`; the
         `:data` region advances to `:error`."}
  (fn [{:keys [db]} [_ {:keys [failure]}]]
    (let [message (rh/failure->message failure)]
      {:db (-> db
               (assoc-in [:articles :status] :error)
               (assoc-in [:articles :error] message))
       :fx [[:dispatch [:realworld/articles-home
                        [:fetch-failed {:failure message}]]]]})))

(rf/reg-event :articles/cancel
  {:doc "Abort an in-flight :articles/load — e.g. when the user navigates
         away from the home page mid-fetch. See the HTTP guide on aborts:
         ../../../docs/resources/http.md#the-search-box-race-cured"}
  (fn [_ _]
    {:fx [[:rf.http/managed-abort :articles/load]]}))

(rf/reg-event :articles/reset
  (fn [{:keys [db]} _]
    {:db (assoc db :articles (request-slice []))
     :fx [[:dispatch [:realworld/articles-home [:reset]]]]}))

;; ============================================================================
;; SUBSCRIPTIONS
;; ============================================================================

(rf/reg-sub :articles/slice      (fn [db _] (:articles db)))
(rf/reg-sub :articles/data       :<- [:articles/slice] (fn [s _] (:data s)))
(rf/reg-sub :articles/error      :<- [:articles/slice] (fn [s _] (:error s)))
(rf/reg-sub :articles/count      :<- [:articles/slice] (fn [s _] (:articles-count s 0)))

;; The `:tags/data` sub is defined in `realworld.tags`, where the
;; popular-tags lifecycle lives entirely in a machine: items are read off
;; the `:realworld/tags` machine's `:data`, with no app-db slice. The home
;; view's sidebar below consumes `:tags/data`.

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

;; ---- pagination (official RealWorld limit/offset) ----
;;
;; The page-number control + articles-count read from the SAME `:feed` region
;; the article list reads from: the active feed's grand `articlesCount` drives
;; the page count, and the 1-indexed current page rides the route query.

(rf/reg-sub :articles.home/articles-count
  {:doc "Grand article count for the active home feed (global / user-feed),
         from whichever slice the `:feed` region is rendering. Drives the
         page count."}
  :<- [:rf/machine :realworld/articles-home]
  :<- [:articles/count]
  :<- [:feed/count]
  (fn sub-home-count [[snap global-count feed-count] _]
    (case (get-in snap [:state :feed])
      :user-feed (or feed-count 0)
      (or global-count 0))))

(rf/reg-sub :articles.home/current-page
  {:doc "The 1-indexed current page for the home feed, read off the route
         query (`?page=`; `:query-defaults {:page 1}` fills it when absent).
         Composes off `:home/page` (tags.cljs)."}
  :<- [:home/page]
  (fn sub-home-page [page _]
    (or page 1)))

(rf/reg-sub :articles.home/page-count
  {:doc "Total number of pages for the active home feed —
         `(ceil articles-count / page-size)`, never below 1."}
  :<- [:articles.home/articles-count]
  (fn sub-home-page-count [total _]
    (rh/page-count total)))

;; ============================================================================
;; VIEWS
;; ============================================================================

(reg-view ^{:doc "A single article card used across the home page and profile pages."}
          article-preview [{:keys [article]}]
  (let [{:keys [slug title description createdAt favoritesCount author tagList]} article]
    [:div.article-preview
     [:div.article-meta
      [rf/route-link {:to     :realworld.profile/show
                           :params {:username (:username author)}}
       [:img.user-pic {:src (avatar/avatar-src (:image author))}]]
      [:div.info
       [rf/route-link {:to     :realworld.profile/show
                            :params {:username (:username author)}
                            :class  "author"}
        (:username author)]
       [:span.date createdAt]]
      [:button.btn.btn-outline-primary.btn-sm.pull-xs-right
       {:type "button"
        :on-click #(dispatch [:article/toggle-favorite slug])}
       [:i.ion-heart] " " favoritesCount]]
     [rf/route-link {:to          :realworld.article/show
                          :params      {:slug slug}
                          :class       "preview-link"
                          :data-testid (str "article-preview-link-" slug)}
      [:h1 title]
      [:p description]
      [:span "Read more..."]
      [:ul.tag-list
       (for [tag tagList]
         ^{:key tag}
         [:li.tag-default.tag-pill.tag-outline tag])]]]))

(reg-view ^{:doc "Official RealWorld page-number control. Renders the
                  Conduit `.pagination` / `.page-item` / `.page-link` markup
                  with the active page marked `.active`. Reused by the home
                  feeds and the profile lists — `on-select` is a 1-arg fn
                  (page-number → navigation) so each call site routes to its
                  own `?page=`. A single page renders nothing (the official
                  client hides the control when there is nothing to page)."}
          pagination [{:keys [current-page page-count on-select]}]
  (when (> page-count 1)
    [:nav
     [:ul.pagination
      (for [page (range 1 (inc page-count))]
        ^{:key page}
        [:li.page-item {:class (when (= page current-page) "active")}
         [:a.page-link
          {:href        "#"
           :data-testid (str "pagination-page-" page)
           :on-click    #(do (.preventDefault %) (on-select page))}
          page]])]]))

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

(reg-view ^{:doc "Data region :empty / :nothing — no articles to show. Renders
                  the official RealWorld `.article-preview.empty-feed-message`
                  marker the E2E contract asserts on for empty
                  list states."}
          articles-empty []
  [:div.article-preview.empty-feed-message "No articles are here… yet."])

(reg-view ^{:doc "Data region :some — articles to show. Inline refresh
                  indicator overlays when the :data/refreshing tag is
                  set (a same-feed reload in flight)."}
          articles-some []
  (let [articles    @(subscribe [:articles.home/active-articles])
        refreshing? @(rf/machine-has-tag? :realworld/articles-home :data/refreshing)]
    [:<>
     (when refreshing? [:div.refresh-indicator "Refreshing…"])
     (for [article articles]
       ^{:key (:slug article)}
       [article-preview {:article article}])]))

(reg-view ^{:doc "Global feed / your feed / tag-filtered home page."}
          home-page []
  (let [authed?      @(subscribe [:auth/authenticated?])
        selected-tag @(subscribe [:home/selected-tag])
        on-user-feed? @(rf/machine-has-tag? :realworld/articles-home :feed/user-feed)
        on-global?    @(rf/machine-has-tag? :realworld/articles-home :feed/global)
        tag-filtered? @(rf/machine-has-tag? :realworld/articles-home :filter/tagged)
        tags          @(subscribe [:tags/data])
        render-mode   @(subscribe [:articles.home/render])
        current-page  @(subscribe [:articles.home/current-page])
        page-count    @(subscribe [:articles.home/page-count])]
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
          [articles-empty])
        [pagination {:current-page current-page
                     :page-count   page-count
                     :on-select    #(dispatch [:home/show-page %])}]]
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
