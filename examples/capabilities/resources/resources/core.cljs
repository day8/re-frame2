(ns resources.core
  "A worked example of resources — named, cached server-state reads. See the
   [resources guide](../../../docs/resources/concepts.md) and its
   [glossary](../../../docs/resources/glossary.md).

   The app is a small articles list plus an article detail. Nothing exotic —
   it's ordinary re-frame2: app-db, events, subs, passive views. The one habit
   to notice is that views never fetch. A view just reads; something else
   always causes the fetch on its behalf.

   So who does the causing? Four different things, and the example wires up all
   four so you can watch each one:

   - ROUTE-DRIVEN page load — a route declares `:resources`; entering the
     route ensures them, owned by the route for as long as you stay on it.
   - EVENT-DRIVEN ensure     — an event ensures a resource under an app-minted
     `[:lease …]` owner, with a matching release event.
   - MANUAL refresh          — a button refetches with a `:cause` and no owner
     (a refresh wants fresh data but keeps nothing alive).
   - MACHINE-OWNED resource  — a machine ensures a resource owned for the
     actor's lifetime, released when the actor is destroyed.

   Owner and cause are the two ideas to keep straight: an owner keeps a cached
   entry alive, a cause just records why a fetch happened. Owner = lifetime,
   cause = explanation. See
   [owner & cause](../../../docs/resources/glossary.md#owner--cause).

   Every resource declares `:scope :rf.scope/global` — this data is public and
   the same for everyone. Scope is a required, fail-closed leak boundary, so
   one user's data can't leak into another's cache; a per-user read would carry
   a scope resolver instead. There's no default on purpose — forgetting it is
   an error, not a silent global. See
   [scope](../../../docs/resources/glossary.md#scope).

   There's no real backend here. Instead the example overrides the
   `:rf.http/managed` effect with a per-URL stub that returns canned articles —
   but everything downstream of the wire runs for real: real in-flight dedupe,
   the real status flow, the works. A small 120 ms reply delay buys us time to
   actually see the loading skeleton and the refresh-in-flight state, which
   would otherwise flash past too fast to notice. Re-ensuring an entry that's
   still inside its `:stale-after-ms` window skips the refetch entirely; the
   manual Refresh forces one anyway.

   This example is reads only. The other half of the story — writes, and how a
   mutation invalidates reads by tag — lives in the
   [resources guide](../../../docs/resources/concepts.md)."
  (:require [reagent.dom.client :as rdc]
            [re-frame.core :as rf]
            [re-frame.registrar :as registrar]
            ;; Managed HTTP is the transport a resource fetch rides on.
            ;; Requiring it registers the `:rf.http/managed` effect that every
            ;; ensure ultimately runs onto. See the managed-HTTP glossary entry:
            ;; ../../../docs/resources/glossary.md#managed-http
            [re-frame.http.managed]
            ;; Canned-reply test effects. With no real server, the stub further
            ;; down stands in for one — and it delegates to the
            ;; `:rf.http/managed-canned-success` effect this require registers.
            [re-frame.http.test-support]
            ;; Resources are an optional artefact, so they only exist if you ask
            ;; for them. This require is the asking — without it, the
            ;; `rf/reg-resource` calls below would have nothing to register
            ;; against and would throw.
            [re-frame.resources]
            ;; Routing — and, just as importantly, the bridge between the two.
            ;; The resources artefact teaches routing about the `:resources`
            ;; route key, so it's loading *both* that makes a route allowed to
            ;; declare `:resources`.
            [re-frame.routing]
            [re-frame.adapter.reagent :as reagent-adapter]))

;; ============================================================================
;; RESOURCES — named, cached server-state reads
;; ============================================================================
;;
;; Boiled down, a resource is just three things: an identity, a scope, and a
;; request. All three are required — `:params-schema`, `:scope`, and the request
;; function at the bottom. `:scope :rf.scope/global` is us promising this read
;; is the same for every user. Scope has no default; leave it off and you get an
;; error at registration, not a quietly-global cache.
;; See ../../../docs/resources/glossary.md#scope

(rf/reg-resource :articles/list
  {:doc            "The recent-articles list (public, same for everyone)."
   :params-schema  [:map]
   :scope          :rf.scope/global
   :stale-after-ms 60000
   :gc-after-ms    (* 5 60 1000)
   ;; Tags are how a future write says "this list is now stale" without naming
   ;; the list directly. See ../../../docs/resources/glossary.md#cache-tag
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
   ;; Two tags, on purpose: the article's own identity AND the list it belongs
   ;; to. That way saving this article can invalidate both the detail view and
   ;; the list that mentions it, in one stroke.
   :tags           (fn [{:keys [slug]} _data] #{[:article slug] [:article-list]})}
  (fn [{:keys [slug]} _ctx]
    {:request {:method :get :url (str "/api/articles/" slug)}
     :decode  :json}))

;; ============================================================================
;; DEMO BACKEND — a per-URL canned :rf.http/managed override
;; ============================================================================
;;
;; No server, so we fake one — at the lowest honest layer. This overrides
;; `:rf.http/managed` (the effect every ensure runs onto) with a stub that
;; builds a canned reply per URL. There are only two URLs in play:
;; `GET /api/articles` (the list) and `GET /api/articles/:slug` (a detail). The
;; stub looks at the URL shape, picks the right payload, and hands off to the
;; shipped `:rf.http/managed-canned-success`, which returns the exact reply
;; shape a real server would. Because we mock at the wire and not above it, the
;; whole resource lifecycle — in-flight tracking, dedupe, reply addressing,
;; status flow — runs for real on top.

(def ^:private demo-articles
  [{:slug "resources-101"  :title "Resources 101: server-state as cached reads"
    :body "A resource is identity + scope + a request. Views read it passively."}
   {:slug "owners-vs-causes" :title "Owners keep alive; causes explain why"
    :body "A route or lease OWNS a read for its lifetime; a refresh is a CAUSE."}
   {:slug "fresh-skip"      :title "Fresh-skip: re-ensure within the stale window is free"
    :body "Ensure an entry still inside :stale-after-ms and the runtime skips the refetch."}])

(def ^:private demo-reply-delay-ms
  "How long the demo stub sits on each canned reply before handing it back. The
   delay rides the canned-success effect's `:after-ms` (dispatched via
   `:dispatch-later`), so it shows up in the tape and survives time-travel.
   Small but deliberately non-zero — long enough that the loading skeleton and
   the refresh-in-flight state get a moment on screen. Purely a demo knob; a
   real app has no business injecting latency."
  120)

(defn- demo-payload-for-url [url]
  (let [u (str url)]
    (if-let [slug (second (re-find #"/api/articles/([^/?#]+)" u))]
      ;; Looks like a detail read — /api/articles/:slug. Find that article, or
      ;; fall back to a polite "(no such article)" rather than blowing up.
      (or (first (filter #(= slug (:slug %)) demo-articles))
          {:slug slug :title slug :body "(no such article)"})
      ;; Otherwise it's the bare list endpoint — /api/articles.
      demo-articles)))

;; This is the override itself — our stand-in backend. It builds the payload for
;; the URL, looks up the real `:rf.http/managed-canned-success` effect from the
;; registrar, and calls it with `:after-ms` so the reply rides `:dispatch-later`
;; (visible in the tape, survives time-travel). The crucial bit: it passes the
;; args-map through untouched, so the reply still knows which resource asked and
;; addresses itself back to it.
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
;; This is the cleanest of the four causers. A route's `:resources` metadata
;; declares, right next to the URL, what server-state that page needs. Arrive on
;; the route and the runtime ensures each one, owned by the route itself. Leave,
;; and it releases that ownership and quietly drops any reply still in flight.
;; The view does nothing but read — the route does the causing.
;; See ../../../docs/routing/glossary.md#route

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
     ;; This is where route params become resource params: the slug in the URL
     ;; is exactly what identifies the article to read.
     :params    (fn [route] {:slug (get-in route [:params :slug])})
     :blocking? true}]} "/articles/:slug")

(rf/reg-route :rf.route/not-found
  {:doc  "Fallback for unmatched URLs."} "/_404")

;; ============================================================================
;; EVENT-DRIVEN ENSURE + MANUAL REFRESH
;; ============================================================================
;;
;; Routes aren't the only thing that can cause a fetch — an event can too. Here
;; the event mints its own `[:lease …]` owner to keep the entry alive. But with
;; ownership comes responsibility: whatever mints a lease must also, somewhere,
;; fire a matching `:rf.resource/release-owner`. Forget that, and the lease pins
;; the entry alive forever — the cache equivalent of a memory leak.
;; See ../../../docs/resources/glossary.md#owner--cause

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

;; A manual refresh is a cause, not an owner — and the distinction is the whole
;; point. Clicking "Refresh" means "I'd like fresher data", not "keep this alive
;; for me"; keeping it alive is the route's job, not the button's. So it refetches
;; with a `:cause` and pointedly no `:owner`.
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
;; Sometimes a whole workflow owns a read for as long as it lives. When that's
;; the case, model the workflow as a machine and let the machine own the
;; resource. The read is tied to the actor's lifetime: born when the actor is,
;; released the moment it's destroyed — no manual bookkeeping. The machine
;; expresses the workflow; the resource runtime quietly handles the cached-read
;; mechanics underneath. What follows is about the smallest such machine you
;; could write: a reader that ensures the one article it's reading, and lets go
;; of it when it stops. See the machines glossary:
;; ../../../docs/machines/glossary.md#machine

;; Starting the reader takes two steps, and the split is on purpose. First we
;; birth the actor with a bare `[:rf.machine/start]` marker; then we dispatch a
;; real `[:reader/load slug instance-id]` event into it. Why not do it in one?
;; Because the birth cascade fires `:entry` actions with no event attached — so
;; an `:entry` action that reached for the slug would find nil. A genuine event
;; dispatched *after* birth carries its args just fine, which is how the slug and
;; instance-id this workflow owns get to ride in on `:reader/load`.
;;
;; The `:reading` state handles that `:reader/load` with the `:ensure-article`
;; action. The action pulls slug + instance-id out of the event, records them in
;; the snapshot `:data` as the reader's domain state, and ensures the article
;; under the owner `[:machine :resources.app/reader]` — the runtime ACTOR-ID
;; owner (a singleton actor's id IS its machine-id). That two-part key is the ONE
;; machine owner the framework auto-releases on actor destroy (Spec 016 §Release
;; authority is per owner kind): destroying the actor releases exactly this owner
;; for us, so the read is not left pinned.
;;
;; The owner is the actor-id, deliberately NOT `[:machine machine-id
;; instance-id]`. A three-part owner that folds a DOMAIN instance-id into the key
;; is an *app-authoritative* lease (like any `[:lease …]`) — the framework does
;; NOT auto-release it, so leaning on actor-destroy to free it would leak the
;; entry (it would pin the read alive, refetching forever). The reader keeps its
;; instance-id in `:data`, never in the owner. See Spec 016 §Release authority.
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
                             :owner    [:machine :resources.app/reader]
                             :cause    [:machine-action :resources.app/reader.reading]}]]]}))}
   :states
   ;; `:reader/load` is a targetless transition — it runs `:ensure-article` and
   ;; stays put in `:reading` (no `:target`). One gotcha worth flagging: the
   ;; action has to ride inside an `{:action …}` map. Write the tempting shorthand
   ;; `{:reader/load :ensure-article}` and the machine reads `:ensure-article` as
   ;; the name of a target *state*, then fails to find one.
   ;; See ../../../docs/machines/glossary.md#transition
   {:reading {:on {:reader/load {:action :ensure-article}}}}})

;; These two events are the UI's start and stop buttons for the reader. Start
;; births the actor, then dispatches `[:reader/load slug instance-id]` into it —
;; the two-step dance explained on the machine above: birth cascade first, then
;; the load drives the ensure. It also stashes the instance-id in app-db so the
;; view knows to show the article panel and a stop button. Stop destroys the
;; actor — which releases its resource owner, letting the entry GC — and clears
;; the slice back out of app-db.
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
;; The resource subs themselves are passive — they read the runtime-managed
;; cache and nothing more. Below them sits one small derived sub that pulls a
;; single slug out of the list to feed the "open preview" button.

;; Want to compute something *over* a resource's data? There's no resource-local
;; select — you just layer an ordinary sub on top of the passive
;; `[:rf.resource/data …]` sub, exactly as you'd layer any sub on any other.
;; The input-fn is a pure `(fn [query-v])` returning a vector of query vectors
;; (never a deref'd subscribe), and the compute fn then gets the resolved inputs
;; back positionally: `[[articles] _]`.
;; See ../../../docs/core/glossary.md#subscription
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

(rf/reg-view home-page []
  [:div
   [:h1 "Resources demo"]
   [:p "Server-state as named, cached reads. Views are passive; route entry,
        events, and machines cause the fetch."]
   [:p [rf/route-link {:to :resources.app/articles
                       :data-testid "route-link-articles"}
        "See the articles →"]]])

;; The EVENT-LEASE preview panel. It only exists on screen while a preview is
;; open. The detail it shows was ensured under an app lease over in
;; `:resources.app/preview-opened`, and will be released by
;; `:resources.app/preview-closed`. The panel's whole job is to read and render.
(rf/reg-view preview-panel [slug]
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

;; The MACHINE-OWNED reader panel. Here the live reader actor owns this detail
;; for its whole lifetime; the panel just reads it passively. Hit stop and the
;; actor is destroyed, which is what releases the owner.
(rf/reg-view reader-panel [slug]
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

(rf/reg-view articles-page []
  ;; Getting here already ensured the list — that's what this route's
  ;; `:resources` metadata bought us. All the view has to do now is read the
  ;; resource's full state, passively.
  (let [state        @(subscribe [:rf.resource/state {:resource :articles/list :params {}}])
        preview-slug @(subscribe [:resources.app/preview-slug])
        reader       @(subscribe [:resources.app/reader])
        ;; The top article's slug, for the quick-preview button — courtesy of the
        ;; little sub we layered over the list resource above.
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
       ;; First load, nothing usable to show yet → skeleton.
       (:loading? state)
       [:p {:data-testid "articles-skeleton"} "Loading articles…"]

       ;; First load fell over and left us with nothing → error.
       (and (:error state) (not (:has-data? state)))
       [:p.error {:data-testid "articles-error"} "Could not load articles."]

       :else
       [:<>
        ;; A failed *background* refresh is gentler: we keep the data we have and
        ;; just flag that the refresh didn't take.
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
                 ;; EVENT-LEASE causer: opens a preview held alive by an app lease.
                 " "
                 [:button {:data-testid (str "preview-" slug)
                           :on-click    #(dispatch [:resources.app/preview-opened slug])}
                  "Preview"]
                 ;; MACHINE-OWNED causer: spawns a reader actor that owns this article.
                 " "
                 [:button {:data-testid (str "read-" slug)
                           :on-click    #(dispatch [:resources.app/start-reader slug])}
                  "Open in reader"]]))
        ;; The lease and machine panels only appear when their owner is active —
        ;; passive read-models, mounted on demand.
        (when preview-slug
          [preview-panel preview-slug])
        (when reader
          [reader-panel (:slug reader)])])]))

(rf/reg-view article-detail-page []
  ;; Same deal as the list page: arriving here already ensured :article/by-slug
  ;; for the slug in the URL. The view just reads the result.
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

(rf/reg-view not-found-page []
  [:div
   [:h1 "Not found"]
   [:p [rf/route-link {:to :resources.app/home :data-testid "route-link-home"} "Home"]]])

(rf/reg-view root-view []
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
;; `run` is the boot sequence. It creates the React root on the first call, then
;; renders. The interesting piece is the `frame-provider {:id …}` at the root: it
;; both creates and configures the app frame in one go — `:url-bound? true` so
;; this frame owns the browser URL, plus the HTTP-stub override. Every dispatch
;; and subscribe anywhere in the tree resolves to this one frame, and a hot
;; reload reuses it rather than spinning up a fresh one.
;; `:url-bound? true` is also what syncs the initial URL and wires up the
;; browser back/forward buttons — the frame's creation installs the listener
;; automatically. See the frame glossary entry:
;; ../../../docs/core/glossary.md#frame

(defonce react-root (atom nil))

;; The id this app's frame is created under. Despite the name, `:rf/default`
;; carries no special privilege — it's just an ordinary id. The URL ownership
;; comes from `:url-bound? true` on the `frame-provider` below, never from what
;; the frame happens to be called.
(def app-frame :rf/default)

(defn run []
  (rf/init! reagent-adapter/adapter)
  (when (exists? js/document)
    (when-not @react-root
      (reset! react-root (rdc/create-root (js/document.getElementById "app"))))
    ;; Here's the frame, created and configured by `frame-provider {:id …}`.
    ;; `:fx-overrides` is the trick that lets the demo stand alone: it reroutes
    ;; every `:rf.http/managed` ensure to the per-URL canned stub up above. The
    ;; override is frame-wide, which is fine here precisely because this example
    ;; never makes a request we'd actually want to reach the network — a blanket
    ;; override is the right grain.
    (rdc/render @react-root
                [rf/frame-provider {:id           app-frame
                                    :doc          "Resources demo frame."
                                    :url-bound?   true
                                    :fx-overrides {:rf.http/managed :resources.demo/http-stub}}
                 [root-view]])))
