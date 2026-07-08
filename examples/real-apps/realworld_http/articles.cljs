(ns realworld-http.articles
  "Home-page article feeds for the RealWorld (Conduit) example.

   The home page looks simple — a list of articles — but it's juggling three
   independent questions at once: which feed are you looking at, is a tag
   filter on, and where is the data in its load. That's a textbook fit for a
   parallel state machine, and this namespace is the worked example. Worth a
   read for:

   - One parallel state machine, three orthogonal regions
     (`:feed` x `:filter` x `:data`). See the machines guide on parallel
     regions: ../../../docs/machines/concepts.md#when-the-machine-grows.
   - A remote-data lifecycle tracked by the `:data` region, whose
     state-keyword drives the whole home-page render decision. The `:articles`
     and `:feed` slices keep the plain 5-key slice shape so their items live
     in app-db where favorites.cljs's optimistic updates can reach across and
     patch them. (More on why the machine and the slice coexist in the `:data`
     region note below.)
   - Route-driven loading: `/tag/:tag` filters the list and `?feed=following`
     switches to the authenticated feed — both the official RealWorld URL
     shapes. Each navigation broadcasts the matching feed-region transition.
   - Home-page tabs, expressed as feed-region transitions rather than ad-hoc
     flags.
   - The home view's root: a single `case` over `:articles.home/render`, a
     selector sub that reads a render-priority table against the machine's tag
     union.
   - One article card reused across the home page and the profile pages."
  (:require [re-frame.core :as rf]
            ;; State machines live in their own artefact; we require it just to
            ;; load it, which registers the hooks that make `rf/reg-machine`
            ;; (below) and the `:rf/machine` subs resolve. See the machines
            ;; guide: ../../../docs/machines/index.md
            [re-frame.machines]
            [realworld-shared.avatar :as avatar]
            [realworld-http.schema :as schema]
            [realworld-http.http :as rh])
  (:require-macros [re-frame.core :refer [reg-view]]))

(defn request-slice [data]
  {:status :idle :data data :error nil :loaded-at nil :attempt 0})

;; ============================================================================
;; THE MACHINE — :realworld/articles-home  (one machine, three regions)
;; ============================================================================
;;
;; Three independent questions, three regions. The trick of a parallel machine
;; is that each axis gets to evolve on its own, instead of being mashed into
;; one combinatorial blob of states:
;;
;;   :feed   — which feed you're looking at (global / your-feed / tag-filtered
;;             global). Its state-keyword tells the view which app-db slice to
;;             read items from (`:articles` for :global and :tag-feed, `:feed`
;;             for :user-feed). Driven by home navigation: the following toggle
;;             rides the `?feed=following` query, and the tag filter is the
;;             `/tag/:tag` PATH route (`:realworld/home-tag`).
;;
;;   :filter — is a tag filter on? It's always `:tagged` whenever :feed is
;;             :tag-feed, but it earns its own region so a view rendering the
;;             filter chip can ask a tag-shaped question without poking at the
;;             feed region to infer the answer.
;;
;;   :data   — where the active feed is in its load. This region's
;;             state-keyword drives the entire home-page render decision (via
;;             the render-priority table + the `:articles.home/render`
;;             selector sub), so the view never has to branch on a slice's
;;             `:status`. The `:resolving` eventless microstep then peeks at
;;             the count in the machine's `:data` to decide between `:empty`
;;             and `:some`.
;;
;;             So why keep BOTH a machine region and an `:articles` slice? The
;;             slice keeps the plain 5-key shape because its article items have
;;             to live in app-db, where favorites.cljs can reach across slices
;;             and patch them. The machine's `:data` region tracks the same
;;             lifecycle and owns the render call. (For the other shape — where
;;             the lifecycle lives entirely in the machine and there's no slice
;;             at all — see tags.cljs and settings.cljs.)
;;
;; One thing to keep in mind: every event delivered to a parallel machine
;; reaches every region — that's the broadcast. So the region event names are
;; kept distinct to avoid crosstalk, and each region treats `:reset` as a
;; self-target.

(def home-machine
  {:type :parallel

   ;; The machine holds just two facts: the active feed's item-count (which
   ;; picks the empty-vs-some bucket) and the latest error. The articles
   ;; themselves stay in the app-db slices (`:articles`, `:feed`), where
   ;; favorites.cljs can find and patch them across slices.
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
      ;; A reload while the previous list is still on screen. Tagged
      ;; :data/some so render-priority keeps showing the `:some` view (no
      ;; flicker to a spinner); the :data/refreshing tag lights up the
      ;; little inline "refreshing" indicator.
      {:tags #{:data/some :data/refreshing :data/transient}
       :on   {:fetch-succeeded {:target :resolving :action :set-count}
              :fetch-failed    {:target :error     :action :set-error}
              :reset           :nothing}}

      :resolving
      ;; An eventless microstep — it transitions on its own the instant it's
      ;; entered. Once :set-count has written the new count, this picks the
      ;; bucket: empty or not. First matching branch wins.
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
      ;; The `/tag/:tag` PATH route. Still reads from the :articles slice —
      ;; the tag only changes the request URL upstream. (The frontend route
      ;; is `/tag/:tag`, but the WIRE stays `/articles?tag=…`; the two shapes
      ;; are independent.)
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
  {:doc "Fetch the global articles list, optionally narrowed to the active tag
         (the `/tag/:tag` route param; on the wire that's still
         `/articles?tag=…`). Goes out via `:rf.http/managed` with a
         Malli-decoded response and the house data-fetch retry policy. It
         carries `:request-id :articles/load`, which buys two things: re-issue
         it (the user flips to a different tag mid-load) and the new request
         supersedes the in-flight one, and `:articles/cancel` can abort it
         outright. See the HTTP guide: ../../../docs/async/http.md

         It also broadcasts `:fetch-started` into the home machine, nudging the
         `:data` region to `:loading` (or `:refreshing`, if a list is already
         showing)."
   :rf.http/decode-schemas [schema/ArticlesResponse]}
  ;; The route lives in runtime-db. The 1-indexed `?page=` off the route query
  ;; becomes the wire's limit/offset window via `rh/paginate-path`, which also
  ;; URL-encodes the `:tag` filter. The active tag is a path param; the page is
  ;; a query param; and on the wire it all collapses to
  ;; `/articles?tag=…&limit=…&offset=…`. Frontend route shape and API query
  ;; string are deliberately independent.
  (fn [{:keys [db] rt :rf.db/runtime} _]
    (let [current   (get-in rt [:rf.runtime/routing :current])
          tag       (get-in current [:params :tag])
          page      (or (get-in current [:query :page]) 1)
          path      (rh/paginate-path "/articles" (when tag {:tag tag}) page)
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
  {:doc "The fetch came back happy. Swap in the new list and clear any stale
         error. It arrives as `{:status :ok :value <ArticlesResponse>}` —
         the same uniform reply shape every managed request returns. Folds the
         fresh count into the home machine via `:fetch-succeeded`, and the
         `:data` region's `:resolving` `:always` cascade takes it from there,
         choosing `:empty` or `:some`."
   :rf.cofx/requires [:rf/time-ms]}
  (fn [{:keys [db rf/time-ms]} [_ {:keys [value]}]]
    (let [items (vec (:articles value))
          ;; `articlesCount` is the GRAND total across all matching articles,
          ;; not the size of this page — it's what the page count is computed
          ;; from. If the server leaves it out, fall back to this page's size.
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
  {:doc "The fetch failed. Hold on to whatever list was already showing (no
         point blanking the page over a hiccup) and surface a readable error
         message, projected from the failure map. Folds the failure into the
         home machine via `:fetch-failed`, sending the `:data` region to
         `:error`."}
  (fn [{:keys [db]} [_ {:keys [error]}]]
    (let [message (rh/failure->message error)]
      {:db (-> db
               (assoc-in [:articles :status] :error)
               (assoc-in [:articles :error] message))
       :fx [[:dispatch [:realworld/articles-home
                        [:fetch-failed {:failure message}]]]]})))

(rf/reg-event :articles/cancel
  {:doc "Abort an in-flight :articles/load — say the user wanders off the home
         page before it lands. No sense letting a reply arrive for a screen
         nobody's looking at. See the HTTP guide on aborts:
         ../../../docs/async/http.md#the-search-box-race-cured"}
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

;; The `:tags/data` sub is defined in `realworld-http.tags`, where the
;; popular-tags lifecycle lives entirely in a machine: items are read off
;; the `:realworld/tags` machine's `:data`, with no app-db slice. The home
;; view's sidebar below consumes `:tags/data`.

;; ---- render-priority + :articles.home/render selector ----
;;
;; "Which view do I show?" is data, not code. The render-priority table is a
;; plain vector of {:tag :render} pairs, read top to bottom. The
;; `:articles.home/render` sub looks at the machine's set of active tags and
;; returns the first :render whose :tag is present. The home view's `case` over
;; that one resolved keyword is the only place anything branches on render
;; state — everywhere else just reads tags directly. Want to reorder the
;; precedence? Reorder the vector; nobody touches the view.
;;
;; The order encodes the priorities: the data lifecycle wins outright —
;; `:loading` (the first-load spinner) beats `:error` beats the count buckets.
;; `:refreshing` resolves to `:some` on purpose, so a reload leaves the
;; existing list up, with just an inline refresh indicator (rendered by the
;; `:some` view off the `:data/refreshing` tag).

(def render-priority
  [{:tag :data/loading :render :loading}
   {:tag :data/error   :render :error}
   {:tag :data/empty   :render :empty}
   {:tag :data/some    :render :some}
   {:tag :data/nothing :render :empty}])

(rf/reg-sub :articles.home/render
  {:doc "Boil the machine's active tags down to a single render keyword, by
         running them past the render-priority table. The home view's `case`
         on this keyword is the one and only branch site."}
  :<- [:rf/machine :realworld/articles-home]
  (fn sub-articles-home-render [snap _]
    (let [tags (:tags snap)]
      (some (fn [{:keys [tag render]}]
              (when (contains? tags tag) render))
            render-priority))))

(rf/reg-sub :articles.home/active-articles
  {:doc "Whichever article list the home view should be showing right now. The
         `:feed` region's active state decides which app-db slice to pull from
         — `:feed` for your-feed, `:articles` for everything else."}
  :<- [:rf/machine :realworld/articles-home]
  :<- [:articles/data]
  :<- [:feed/data]
  (fn sub-active-articles [[snap global-items feed-items] _]
    (case (get-in snap [:state :feed])
      :user-feed (or feed-items [])
      (or global-items []))))

;; ---- pagination (official RealWorld limit/offset) ----
;;
;; The page-number control reads from the SAME `:feed` region the article list
;; does, so the two never disagree: the active feed's grand `articlesCount`
;; sets how many pages there are, and the 1-indexed current page rides along
;; in the route query.

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

(reg-view ^{:doc "One article card — the little preview tile, reused on both the
                  home page and the profile pages. Write it once, render it
                  everywhere."}
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

(reg-view ^{:doc "The page-number control, Conduit-style. Renders the official
                  `.pagination` / `.page-item` / `.page-link` markup with the
                  current page marked `.active`. Shared by the home feeds and
                  the profile lists: `on-select` is a 1-arg fn (page-number →
                  navigation), so each caller decides where its own `?page=`
                  lands. If there's only one page, it draws nothing — same as
                  the official client, which hides the control when there's
                  nothing to page through."}
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

(reg-view ^{:doc "The :loading view — first fetch in flight, nothing to show
                  yet. So we show a spinner-ish placeholder."}
          articles-loading []
  [:div.article-preview "Loading articles…"])

(reg-view ^{:doc "The :error view — the fetch didn't make it."}
          articles-error []
  (let [err @(subscribe [:articles/error])
        feed-err @(subscribe [:feed/error])]
    [:div.article-preview.error
     (str "Couldn't load articles: " (pr-str (or err feed-err)))]))

(reg-view ^{:doc "The :empty / :nothing view — no articles to show. Renders the
                  official `.article-preview.empty-feed-message` marker that
                  the RealWorld E2E suite looks for on an empty list."}
          articles-empty []
  [:div.article-preview.empty-feed-message "No articles are here… yet."])

(reg-view ^{:doc "The :some view — we have articles, so list them. If a
                  same-feed reload is in flight (the :data/refreshing tag),
                  an inline refresh indicator rides along on top."}
          articles-some []
  (let [articles    @(subscribe [:articles.home/active-articles])
        refreshing? @(rf/subscribe [:rf.machine/has-tag? :realworld/articles-home :data/refreshing])]
    [:<>
     (when refreshing? [:div.refresh-indicator "Refreshing…"])
     (for [article articles]
       ^{:key (:slug article)}
       [article-preview {:article article}])]))

(reg-view ^{:doc "The home page itself — global feed, your feed, or a
                  tag-filtered list, depending on the machine. The banner and
                  the feed tabs are always here; the middle swaps based on
                  render-mode."}
          home-page []
  (let [authed?      @(subscribe [:auth/authenticated?])
        selected-tag @(subscribe [:home/selected-tag])
        on-user-feed? @(rf/subscribe [:rf.machine/has-tag? :realworld/articles-home :feed/user-feed])
        on-global?    @(rf/subscribe [:rf.machine/has-tag? :realworld/articles-home :feed/global])
        tag-filtered? @(rf/subscribe [:rf.machine/has-tag? :realworld/articles-home :filter/tagged])
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
