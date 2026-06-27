(ns resources.core
  "A worked example of resources — named, cached server-state reads. See the
   [resources guide](../../../docs/resources/concepts.md) and its
   [glossary](../../../docs/resources/glossary.md).

   The app is a small articles list plus an article detail. It is ordinary
   re-frame2: app-db, events, subs, passive views. Views never fetch — the
   fetch is always caused for them.

   It shows four ways a fetch gets caused, all wired into the UI:

   - ROUTE-DRIVEN page load — a route declares `:resources`; entering the
     route ensures them, owned by the route for as long as you stay on it.
   - EVENT-DRIVEN ensure     — an event ensures a resource under an app-minted
     `[:lease …]` owner, with a matching release event.
   - MANUAL refresh          — a button refetches with a `:cause` and no owner
     (a refresh wants fresh data but keeps nothing alive).
   - MACHINE-OWNED resource  — a machine ensures a resource owned for the
     actor's lifetime, released when the actor is destroyed.

   An owner keeps a cached entry alive; a cause records why a fetch happened.
   See [owner & cause](../../../docs/resources/glossary.md#owner--cause).

   Every resource declares `:scope :rf.scope/global` — this data is public and
   the same for everyone. Scope is a required, fail-closed leak boundary; a
   per-user read would carry a scope resolver instead. See
   [scope](../../../docs/resources/glossary.md#scope).

   The example ships no backend. It overrides the `:rf.http/managed` effect
   with a per-URL stub that returns canned articles, so every ensure runs a
   real fetch with real in-flight dedupe and the real status flow. A 120 ms
   reply delay lets the loading skeleton render before data lands, so
   first-load and refresh-in-flight are visible. Re-ensuring an entry that is
   still inside its `:stale-after-ms` window skips the refetch; the manual
   Refresh forces one regardless.

   This example covers reads only. Writes — mutations that invalidate reads by
   tag — are covered in the [resources guide](../../../docs/resources/concepts.md)."
  (:require [reagent.dom.client :as rdc]
            [re-frame.core :as rf]
            [re-frame.registrar :as registrar]
            [re-frame.views]
            ;; Managed HTTP is the transport for resource fetches. Requiring
            ;; it registers the `:rf.http/managed` effect that each ensure
            ;; runs onto. See the managed-HTTP glossary entry:
            ;; ../../../docs/resources/glossary.md#managed-http
            [re-frame.http.managed]
            ;; The canned-reply stub effects. This example has no backend, so
            ;; the stub below stands in; requiring this ns registers
            ;; `:rf.http/managed-canned-success`, which it delegates to.
            [re-frame.http.test-support]
            ;; Boots the optional resources artefact. Requiring it at app boot
            ;; is what makes `rf/reg-resource` work; without it the calls below
            ;; throw.
            [re-frame.resources]
            ;; Routing. The resources artefact adds its `:resources` route
            ;; metadata onto routing, so loading both is what makes a route's
            ;; `:resources` key accepted.
            [re-frame.routing]
            [re-frame.adapter.reagent :as reagent-adapter])
  (:require-macros [re-frame.core :refer [reg-view]]))

;; ============================================================================
;; RESOURCES — named, cached server-state reads
;; ============================================================================
;;
;; A resource is identity + scope + a request. `:params-schema`, `:scope`, and
;; the request function are all required. `:scope :rf.scope/global` claims this
;; read is the same for every user. Scope has no default — a missing scope is
;; an error at registration. See ../../../docs/resources/glossary.md#scope

(rf/reg-resource :articles/list
  {:doc            "The recent-articles list (public, same for everyone)."
   :params-schema  [:map]
   :scope          :rf.scope/global
   :stale-after-ms 60000
   :gc-after-ms    (* 5 60 1000)
   ;; Tags let a write invalidate this list by tag.
   ;; See ../../../docs/resources/glossary.md#cache-tag
   :tags           (fn [_params _data] #{[:article-list]})}
  (fn [_params _ctx]
    {:request {:method :get :url "/api/articles"}
     :decode  :json}))

(rf/reg-resource :article/by-slug
  {:doc            "Article detail by slug (public)."
   :params-schema  [:map [:slug :string]]
   :scope          :rf.scope/global
   :stale-after-ms 60000
   :gc-after-ms    (* 5 60 1000)
   ;; Tag BOTH the per-article identity and the list identity so a save
   ;; can invalidate the detail and the list relationship together.
   :tags           (fn [{:keys [slug]} _data] #{[:article slug] [:article-list]})}
  (fn [{:keys [slug]} _ctx]
    {:request {:method :get :url (str "/api/articles/" slug)}
     :decode  :json}))

;; ============================================================================
;; DEMO BACKEND — a per-URL canned :rf.http/managed override
;; ============================================================================
;;
;; There is no server, so this overrides `:rf.http/managed` (the effect every
;; ensure runs onto) with a stub that builds a canned reply per URL. The two
;; requests are `GET /api/articles` (the list) and `GET /api/articles/:slug`
;; (a detail). The stub routes by URL shape and delegates to the shipped
;; `:rf.http/managed-canned-success`, which returns the same reply shape a real
;; server would — so the full resource lifecycle (in-flight tracking, dedupe,
;; reply addressing, status flow) runs for real.

(def ^:private demo-articles
  [{:slug "resources-101"  :title "Resources 101: server-state as cached reads"
    :body "A resource is identity + scope + a request. Views read it passively."}
   {:slug "owners-vs-causes" :title "Owners keep alive; causes explain why"
    :body "A route or lease OWNS a read for its lifetime; a refresh is a CAUSE."}
   {:slug "fresh-skip"      :title "Fresh-skip: re-ensure within the stale window is free"
    :body "Ensure an entry still inside :stale-after-ms and the runtime skips the refetch."}])

(def ^:private demo-reply-delay-ms
  "How long the demo stub defers each canned reply. The delay rides the
   canned-success effect's `:after-ms` (dispatched via `:dispatch-later`, so it
   is visible in the tape and survives time-travel). Small but non-zero, so the
   loading skeleton and the refresh-in-flight state are visible. A demo knob,
   not a production value."
  120)

(defn- demo-payload-for-url [url]
  (let [u (str url)]
    (if-let [slug (second (re-find #"/api/articles/([^/?#]+)" u))]
      ;; A detail read — /api/articles/:slug.
      (or (first (filter #(= slug (:slug %)) demo-articles))
          {:slug slug :title slug :body "(no such article)"})
      ;; The bare list endpoint — /api/articles.
      demo-articles)))

;; This override stands in for the backend. It builds the payload for the URL
;; and hands off to `:rf.http/managed-canned-success`, looked up from the
;; registrar and called with `:after-ms` so the reply rides `:dispatch-later`
;; (visible in the tape, survives time-travel). It keeps the args-map intact so
;; the reply still addresses back to the resource that asked.
(rf/reg-fx :resources.demo/http-stub
  {:doc       "Demo override for `:rf.http/managed`: routes by URL to the canned
               article payloads so the example runs standalone, then delegates
               to `:rf.http/managed-canned-success` with `:after-ms`."
   :platforms #{:client}}
  (fn fx-managed-resources-demo [frame-ctx args-map]
    (let [payload (demo-payload-for-url (-> args-map :request :url))
          stub    (registrar/handler :fx :rf.http/managed-canned-success)]
      (stub frame-ctx (assoc args-map :after-ms demo-reply-delay-ms :value payload)))))

;; ============================================================================
;; ROUTES — entering a route causes its resources to load
;; ============================================================================
;;
;; `:resources` route metadata declares what server-state a page needs. On
;; route entry the runtime ensures each one, owned by the route. On route
;; leave it releases that owner and drops any late reply. The view only reads;
;; the fetch is caused for it. See ../../../docs/routing/glossary.md#route

(rf/reg-route :resources.app/home
  {:doc  "Landing page."} "/")

(rf/reg-route :resources.app/articles
  {:doc   "Articles list — loads the :articles/list resource on entry."
   :resources
   [{:resource  :articles/list
     :params    (fn [_route] {})
     :blocking? true}]} "/articles")

(rf/reg-route :resources.app/article-detail
  {:doc    "Article detail — loads :article/by-slug for the URL slug."
   :params [:map [:slug :string]]
   :resources
   [{:resource  :article/by-slug
     ;; Route params → resource params: the URL slug identifies the read.
     :params    (fn [route] {:slug (get-in route [:params :slug])})
     :blocking? true}]} "/articles/:slug")

(rf/reg-route :rf.route/not-found
  {:doc  "Fallback for unmatched URLs."} "/_404")

;; ============================================================================
;; EVENT-DRIVEN ENSURE + MANUAL REFRESH
;; ============================================================================
;;
;; Not every fetch is route-driven. An event can cause an ensure too — here
;; under an app-minted `[:lease …]` owner. The app owns that lease: the event
;; that mints it must have a matching `:rf.resource/release-owner` event, or
;; the lease pins the entry alive forever. See
;; ../../../docs/resources/glossary.md#owner--cause

(rf/reg-event :resources.app/preview-opened
  {:doc "Open a lightweight article preview from the list — ensure the
         detail under a releaseable lease, and record the open slug in
         app-db so the view can render the preview panel."}
  (fn [{:keys [db]} [_ slug]]
    {:db (assoc db :resources.app/preview-slug slug)
     :fx [[:dispatch [:rf.resource/ensure
                      {:resource :article/by-slug
                       :params   {:slug slug}
                       :owner    [:lease :resources.app/preview slug]
                       :cause    [:event :resources.app/preview-opened]}]]]}))

(rf/reg-event :resources.app/preview-closed
  {:doc "Close the preview — release the lease so the entry can GC, and
         clear the open slug from app-db."}
  (fn [{:keys [db]} [_ slug]]
    {:db (dissoc db :resources.app/preview-slug)
     :fx [[:dispatch [:rf.resource/release-owner
                      {:owner [:lease :resources.app/preview slug]}]]]}))

;; A manual refresh is a cause, not an owner. Clicking "Refresh" wants fresh
;; data but does not intend to keep the resource alive — that is the route's
;; job. So it refetches with a `:cause` and no `:owner`.
(rf/reg-event :resources.app/refresh-articles
  {:doc "Manual refresh of the articles list."}
  (fn [_ _]
    {:fx [[:dispatch [:rf.resource/refetch
                      {:resource :articles/list
                       :params   {}
                       :cause    [:manual :resources.app/refresh-articles]}]]]}))

;; ============================================================================
;; MACHINE-OWNED RESOURCE
;; ============================================================================
;;
;; When a workflow owns a read for its lifetime, model the workflow as a
;; machine and let it own the resource. The resource is owned by the actor and
;; released when the actor is destroyed. The machine is the workflow; the
;; resource runtime handles the cached-read mechanics. This is a tiny reader
;; machine: it ensures the article it is reading, and that read is released
;; when the reader stops. See the machines glossary:
;; ../../../docs/machines/glossary.md#machine

;; The UI starts the reader in two steps: birth the actor with the bare
;; `[:rf.machine/start]` marker, then dispatch a real `[:reader/load slug
;; instance-id]` event into it. The split is deliberate. The birth cascade runs
;; `:entry` actions with no event, so an `:entry` action cannot read the slug —
;; it would arrive nil. A real event dispatched after birth does carry its
;; args, so the slug and instance-id this workflow owns ride on `:reader/load`.
;;
;; The `:reading` state handles `:reader/load` with the `:ensure-article`
;; action. It reads slug + instance-id from the event, stores them in the
;; snapshot `:data` (so the owner is self-describing for tools and SSR), and
;; ensures the article owned by `[:machine machine-id instance-id]`. The
;; runtime releases that owner when the actor is destroyed.
(rf/reg-machine :resources.app/reader
  {:doc     "A reader workflow that owns the article it is reading."
   :initial :reading
   :data    {:slug nil :instance-id nil}
   :actions
   {:ensure-article
    (fn [{:keys [event]}]
      (let [[_ slug instance-id] event]
        {:data {:slug slug :instance-id instance-id}
         :fx   [[:dispatch [:rf.resource/ensure
                            {:resource :article/by-slug
                             :params   {:slug slug}
                             :owner    [:machine :resources.app/reader instance-id]
                             :cause    [:machine-action :resources.app/reader.reading]}]]]}))}
   :states
   ;; `:reader/load` is a targetless transition: it runs `:ensure-article` and
   ;; stays in `:reading` (no `:target`). The action must ride in an `{:action
   ;; …}` map — a bare `{:reader/load :ensure-article}` would read
   ;; `:ensure-article` as a target state and fail to resolve. See
   ;; ../../../docs/machines/glossary.md#transition
   {:reading {:on {:reader/load {:action :ensure-article}}}}})

;; The UI starts and stops the reader through these two events. Starting births
;; the actor and then dispatches `[:reader/load slug instance-id]` into it (the
;; two-step pattern explained on the machine above); the birth cascade runs
;; first, then the load drives the ensure. It also records the instance-id in
;; app-db so the view can show the article and a stop button. Stopping destroys
;; the actor, which releases its resource owner so the entry can GC, and clears
;; the app-db slice.
(rf/reg-event :resources.app/start-reader
  {:doc "Start the reader workflow that owns the given article for its lifetime."}
  (fn [{:keys [db]} [_ slug]]
    (let [instance-id (str "reader-" slug)]
      {:db (assoc db :resources.app/reader {:slug slug :instance-id instance-id})
       :fx [[:dispatch [:resources.app/reader [:rf.machine/start]]]
            [:dispatch [:resources.app/reader [:reader/load slug instance-id]]]]})))

(rf/reg-event :resources.app/stop-reader
  {:doc "Destroy the reader actor — releases its machine-owned resource."}
  (fn [{:keys [db]} _]
    {:db (dissoc db :resources.app/reader)
     :fx [[:rf.machine/destroy :resources.app/reader]]}))

;; ============================================================================
;; APP DATA + READ-MODEL SUBS
;; ============================================================================
;;
;; The passive resource subs read the runtime-managed cache. A small derived
;; sub picks a slug from the list for the "open preview" button.

;; To project over a resource's data, layer an ordinary sub on top of the
;; passive `[:rf.resource/data …]` sub — there is no resource-local select. The
;; input-fn is a pure `(fn [query-v])` that returns a vector of query vectors,
;; never a deref'd subscribe. The compute fn then receives the resolved inputs
;; positionally: `[[articles] _]`. See
;; ../../../docs/guide/glossary.md#subscription
(rf/reg-sub :resources.app/first-slug
  (fn [_query-v]
    [[:rf.resource/data {:resource :articles/list :params {}}]])
  (fn [[articles] _query-v]
    (:slug (first articles))))

;; Which article's preview lease is currently open (nil when none).
(rf/reg-sub :resources.app/preview-slug
  (fn [db _] (:resources.app/preview-slug db)))

;; The active reader workflow's {:slug :instance-id} (nil when stopped).
(rf/reg-sub :resources.app/reader
  (fn [db _] (:resources.app/reader db)))

;; ============================================================================
;; PAGES — passive reads; the runtime owns the state
;; ============================================================================

(reg-view home-page []
  [:div
   [:h1 "Resources demo"]
   [:p "Server-state as named, cached reads. Views are passive; route entry,
        events, and machines cause the fetch."]
   [:p [rf/route-link {:to :resources.app/articles
                       :data-testid "route-link-articles"}
        "See the articles →"]]])

;; EVENT-LEASE preview panel. Mounted only while a preview is open. The detail
;; it reads was ensured under an app lease by `:resources.app/preview-opened`
;; and released by `:resources.app/preview-closed`. The panel only reads.
(reg-view preview-panel [slug]
  (let [state @(subscribe [:rf.resource/state {:resource :article/by-slug
                                               :params  {:slug slug}}])]
    [:div.preview {:data-testid "preview-panel"}
     (cond
       (:loading? state)
       [:p {:data-testid "preview-skeleton"} "Loading preview…"]

       (and (:error state) (not (:has-data? state)))
       [:p.error {:data-testid "preview-error"} "Could not load preview."]

       :else
       [:p {:data-testid "preview-body"} (:body (:data state))])
     [:button {:data-testid "close-preview"
               :on-click    #(dispatch [:resources.app/preview-closed slug])}
      "Close preview"]]))

;; MACHINE-OWNED reader panel — the running reader actor owns this detail for
;; its lifetime; the panel reads it passively. Stopping destroys the actor and
;; releases the owner.
(reg-view reader-panel [slug]
  (let [state @(subscribe [:rf.resource/state {:resource :article/by-slug
                                               :params  {:slug slug}}])]
    [:div.reader {:data-testid "reader-panel"}
     [:strong "Reader (machine-owned): "]
     (cond
       (:loading? state)
       [:span {:data-testid "reader-skeleton"} "Loading…"]

       (and (:error state) (not (:has-data? state)))
       [:span.error {:data-testid "reader-error"} "Could not load."]

       :else
       [:span {:data-testid "reader-title"} (:title (:data state))])
     [:button {:data-testid "stop-reader"
               :on-click    #(dispatch [:resources.app/stop-reader])}
      "Stop reader"]]))

(reg-view articles-page []
  ;; This route's `:resources` metadata ensured the list on entry. The view
  ;; reads its full state passively.
  (let [state        @(subscribe [:rf.resource/state {:resource :articles/list :params {}}])
        preview-slug @(subscribe [:resources.app/preview-slug])
        reader       @(subscribe [:resources.app/reader])
        ;; The top article's slug, for the quick-preview button — a sub layered
        ;; over the list resource.
        top-slug     @(subscribe [:resources.app/first-slug])]
    [:div
     [:h1 "Articles"]
     [:button {:data-testid "refresh-articles"
               :on-click    #(dispatch [:resources.app/refresh-articles])}
      (if (:fetching? state) "Refreshing…" "Refresh")]
     (when top-slug
       [:button {:data-testid "preview-top"
                 :on-click    #(dispatch [:resources.app/preview-opened top-slug])}
        "Quick-preview top article"])
     (cond
       ;; First load, no usable data yet → skeleton.
       (:loading? state)
       [:p {:data-testid "articles-skeleton"} "Loading articles…"]

       ;; First load failed, no usable data → error.
       (and (:error state) (not (:has-data? state)))
       [:p.error {:data-testid "articles-error"} "Could not load articles."]

       :else
       [:<>
        ;; A background-refresh failure keeps the data and surfaces a warning.
        (when (:refresh-error state)
          [:p.warn {:data-testid "articles-refresh-warn"} "Refresh failed; showing last-known data."])
        (into [:ul {:data-testid "articles-list"}]
              (for [{:keys [slug title]} (:data state)]
                ^{:key slug}
                [:li
                 [rf/route-link {:to :resources.app/article-detail
                                 :params {:slug slug}
                                 :data-testid (str "route-link-article-" slug)}
                  title]
                 ;; EVENT-LEASE: open/close a preview under an app lease.
                 " "
                 [:button {:data-testid (str "preview-" slug)
                           :on-click    #(dispatch [:resources.app/preview-opened slug])}
                  "Preview"]
                 ;; MACHINE-OWNED: spawn a reader that owns this article.
                 " "
                 [:button {:data-testid (str "read-" slug)
                           :on-click    #(dispatch [:resources.app/start-reader slug])}
                  "Open in reader"]]))
        ;; The lease + machine read-models render passively when active.
        (when preview-slug
          [preview-panel preview-slug])
        (when reader
          [reader-panel (:slug reader)])])]))

(reg-view article-detail-page []
  ;; This route's `:resources` ensured :article/by-slug for the URL slug.
  (let [slug  (:slug @(subscribe [:rf.route/params]))
        state @(subscribe [:rf.resource/state {:resource :article/by-slug
                                               :params  {:slug slug}}])]
    [:div
     (cond
       (:loading? state)
       [:p {:data-testid "article-skeleton"} "Loading article…"]

       (and (:error state) (not (:has-data? state)))
       [:p.error {:data-testid "article-error"} "Could not load this article."]

       :else
       (let [{:keys [title body]} (:data state)]
         [:<>
          [:h1 {:data-testid "article-title"} title]
          (when (:fetching? state)
            [:span {:data-testid "article-refreshing"} " (refreshing…)"])
          [:p {:data-testid "article-body"} body]]))
     [:p [rf/route-link {:to :resources.app/articles
                         :data-testid "route-link-back"}
          "← Back"]]]))

(reg-view not-found-page []
  [:div
   [:h1 "Not found"]
   [:p [rf/route-link {:to :resources.app/home :data-testid "route-link-home"} "Home"]]])

(reg-view root-view []
  (case @(subscribe [:rf.route/id])
    :resources.app/home           [home-page]
    :resources.app/articles       [articles-page]
    :resources.app/article-detail [article-detail-page]
    :rf.route/not-found           [not-found-page]
    [home-page]))

;; ============================================================================
;; MOUNT
;; ============================================================================
;;
;; `run` creates the React root on first call. The `frame-provider {:id …}` at
;; the render root both creates and configures the app frame: `:url-bound? true`
;; so the frame owns the browser URL, plus the HTTP-stub override. Every
;; dispatch and subscribe in the tree resolves to this frame. On hot reload the
;; same frame is reused. `install-history-listener!` syncs the initial URL and
;; handles back/forward. See the frame glossary entry:
;; ../../../docs/guide/glossary.md#frame

(defonce react-root (atom nil))

;; The id this app's frame is created under. `:rf/default` is an ordinary id
;; with no special privilege; URL ownership comes from `:url-bound? true` on the
;; `frame-provider` below, not from the id.
(def app-frame :rf/default)

(defn run []
  (rf/init! reagent-adapter/adapter)
  (rf/install-history-listener!)
  (when (exists? js/document)
    (when-not @react-root
      (reset! react-root (rdc/create-root (js/document.getElementById "app"))))
    ;; The `frame-provider {:id …}` creates and configures the app frame.
    ;; `:fx-overrides` routes every `:rf.http/managed` resource ensure to the
    ;; per-URL canned stub above, so the example runs standalone with no backend.
    ;; The override applies frame-wide; this example issues no non-mocked
    ;; requests, so a blanket override is the right grain.
    (rdc/render @react-root
                [rf/frame-provider {:id           app-frame
                                    :doc          "Resources demo frame."
                                    :url-bound?   true
                                    :fx-overrides {:rf.http/managed :resources.demo/http-stub}}
                 [root-view]])))
