(ns resources.core
  "Worked example for [Spec 016 Resources](../../../spec/016-Resources.md)
   (the read-resource surface). A small server-state app — an articles
   list and an article detail — demonstrating the resource surface
   composed as proper re-frame2: app-db + events + subs, views passive,
   fetches caused.

   It teaches, in one cohesive app, four causal patterns:

   - ROUTE-DRIVEN page load — a route declares `:resources`; route entry
     ensures them under a `[:route route-id nav-token]` owner.
   - EVENT-DRIVEN ensure     — an event ensures a resource under an
     app-minted `[:lease …]` owner with a matching release path.
   - MANUAL refresh          — a button dispatches `:rf.resource/refetch`
     with a `:cause` (NOT an owner — a refresh keeps nothing alive).
   - MACHINE-OWNED resource  — a machine action ensures under a
     `[:machine machine-id instance-id]` owner (released on actor destroy).

   Views read everything through the PASSIVE `[:rf.resource/*]` subs; no
   view fetches. Scope is the fail-closed leak boundary — this example
   reads public, non-user-specific data, so each resource declares the
   explicit, auditable `:rf.scope/global` claim (a user/tenant-scoped read
   would carry a scope resolver instead).

   All four patterns are WIRED INTO THE UI and run live. The articles page
   carries a Refresh button (manual cause), a per-row Preview toggle (event
   lease ensure/release), and an Open-in-reader button (machine-owned ensure);
   route entry drives the list + detail loads. The example ships no backend,
   so it overrides `:rf.http/managed` with a per-URL canned stub that
   delegates to the framework-shipped `:rf.http/managed-canned-success`
   (Spec 014 §Testing) — the same reply shape a live server would produce, so
   every ensure exercises a REAL fetch, in-flight dedupe, and the passive
   status flow (a 120 ms delay lets the loading skeleton render before the
   reply lands, so first-load and refresh-in-flight are observable). A repeat
   ensure of an entry still inside its `:stale-after-ms` window FRESH-SKIPS
   (no refetch — `:rf.resource/cache-hit`); the manual Refresh forces a
   refetch regardless.

   Resources is a POST-V1 optional artefact. The read-resource runtime
   provides `reg-resource`, the `:rf.resource/*` passive
   subs, route `:resources` metadata, and the causal `:rf.resource/ensure` /
   `:rf.resource/refetch` / `:rf.resource/invalidate-tags` /
   `:rf.resource/release-owner` event bodies. This example covers the READ
   side only; mutations (`reg-mutation` / `:rf.mutation/execute`) are covered
   in the guide (docs/resources/concepts.md §Writes invalidate by tag —
   causally) and the migration walkthrough. GraphQL is a deferred later phase. The
   example tree is test-free; resource-contract coverage lives in
   `implementation/resources/test/` and the conformance fixtures."
  (:require [reagent.dom.client :as rdc]
            [re-frame.core :as rf]
            [re-frame.registrar :as registrar]
            [re-frame.views]
            ;; Managed HTTP ships in day8/re-frame2-http — the single
            ;; built-in resource transport (Spec 016 §Transport). Loading
            ;; the ns registers the `:rf.http/managed` fx the resource
            ;; runtime lowers each ensure onto.
            [re-frame.http.managed]
            ;; This example ships no backend, so it overrides
            ;; `:rf.http/managed` with a per-URL canned stub that delegates
            ;; to the framework-shipped `:rf.http/managed-canned-success`
            ;; (Spec 014 §Testing — the same reply shape a live server would
            ;; produce). The canned-stub fx ids register from
            ;; re-frame.http.test-support, NOT from re-frame.http.managed;
            ;; requiring it is the explicit opt-in for a test/demo app.
            [re-frame.http.test-support]
            ;; Resources ship in day8/re-frame2-resources. Requiring the
            ;; ns at app boot wires the late-bind hooks + registrations;
            ;; without it, `rf/reg-resource` below throws
            ;; :rf.error/resources-artefact-missing.
            [re-frame.resources]
            ;; Routing ships in day8/re-frame2-routing. The resources
            ;; artefact LATE-BINDS its `:resources` route-metadata
            ;; extension into routing, so loading both is what makes a
            ;; route's `:resources` key accepted (Spec 016 §Route
            ;; integration).
            [re-frame.routing]
            [re-frame.adapter.reagent :as reagent-adapter])
  (:require-macros [re-frame.core :refer [reg-view]]))

;; ============================================================================
;; RESOURCES — the registry of named, cached server-state reads
;; ============================================================================
;;
;; A resource is identity + scope + a request. `:params-schema`, `:scope`,
;; and `:request` are REQUIRED (Spec 016 §Resource registration spec). The
;; `:scope :rf.scope/global` here is the explicit, AUDITABLE claim that this
;; read is the same for every user/tenant/locale — there is NO implicit
;; default; a missing scope policy is a loud
;; :rf.error/resource-missing-scope-policy at registration.

(rf/reg-resource :articles/list
  {:doc            "The recent-articles list (public, same for everyone)."
   :params-schema  [:map]
   :scope          :rf.scope/global
   :stale-after-ms 60000
   :gc-after-ms    (* 5 60 1000)
   ;; Tags let a write invalidate this list by tag
   ;; (`:rf.resource/invalidate-tags {:tags #{[:article-list]}}`).
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
;; DEMO BACKEND — per-URL canned :rf.http/managed override
;; ============================================================================
;;
;; This example ships no server, so it overrides `:rf.http/managed` (the fx the
;; resource runtime lowers every ensure onto) with a stub that synthesises the
;; canned reply per URL. The two resource requests are `GET /api/articles`
;; (the list) and `GET /api/articles/:slug` (a detail); the stub routes by URL
;; shape and delegates to the framework-shipped
;; `:rf.http/managed-canned-success` (Spec 014 §Testing) — the same reply shape
;; a live server would produce, so the resource lifecycle (in-flight tracking,
;; dedupe, reply addressing, status flow) is exercised end to end.

(def ^:private demo-articles
  [{:slug "resources-101"  :title "Resources 101: server-state as cached reads"
    :body "A resource is identity + scope + a request. Views read it passively."}
   {:slug "owners-vs-causes" :title "Owners keep alive; causes explain why"
    :body "A route or lease OWNS a read for its lifetime; a refresh is a CAUSE."}
   {:slug "fresh-skip"      :title "Fresh-skip: re-ensure within the stale window is free"
    :body "Ensure an entry still inside :stale-after-ms and the runtime skips the refetch."}])

(def ^:private demo-reply-delay-ms
  "How long the demo stub defers each canned reply (via the canned-success
   fx's `:after-ms`, dispatched through `:dispatch-later` — observable in the
   tape, time-travel-safe, NOT raw `js/setTimeout`). Small but non-zero so the
   `:loading` skeleton + `:fetching?` refresh states are observable. A
   demo-seam knob, not a production value."
  120)

(defn- demo-payload-for-url [url]
  (let [u (str url)]
    (if-let [slug (second (re-find #"/api/articles/([^/?#]+)" u))]
      ;; A detail read — /api/articles/:slug.
      (or (first (filter #(= slug (:slug %)) demo-articles))
          {:slug slug :title slug :body "(no such article)"})
      ;; The bare list endpoint — /api/articles.
      demo-articles)))

;; The resource runtime lowers every ensure onto `:rf.http/managed`; this
;; override stands in for the backend. It synthesises the per-URL payload and
;; delegates to the framework-shipped `:rf.http/managed-canned-success`
;; (Spec 014 §Testing) — calling it directly via the registrar with `:after-ms`
;; so the canned reply rides framework `:dispatch-later` (tape-visible,
;; time-travel-safe, NOT raw `js/setTimeout`) and the reply addressing the
;; resource runtime put on the args-map is preserved. Mirrors
;; `examples/reagent/realworld_resources/http.cljs`.
(rf/reg-fx :resources.demo/http-stub
  {:doc       "Demo override for `:rf.http/managed`: routes by URL to the
               canned article payloads so the example runs standalone, then
               delegates to `:rf.http/managed-canned-success` with `:after-ms`
               (the deferred reply rides `:dispatch-later`)."
   :platforms #{:client}}
  (fn fx-managed-resources-demo [frame-ctx args-map]
    (let [payload (demo-payload-for-url (-> args-map :request :url))
          stub    (registrar/handler :fx :rf.http/managed-canned-success)]
      (stub frame-ctx (assoc args-map :after-ms demo-reply-delay-ms :value payload)))))

;; ============================================================================
;; ROUTES — route entry CAUSES the page's resources to load
;; ============================================================================
;;
;; The `:resources` route metadata (Spec 016 §Route integration) is the
;; declarative, machine-readable answer to "what server-state does this
;; page need?" On route entry the runtime marks each resource active with
;; owner `[:route route-id nav-token]` and ensures it with cause
;; `[:route-entry route-id nav-token]`; on route leave it releases the
;; owner by token and suppresses any stale reply by generation. The view
;; never fetches — it only reads.

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
;; Not every fetch is route-driven. An event can CAUSE an ensure too — here
;; under an app-minted `[:lease …]` owner. App leases are app-authoritative
;; (Spec 016 §Release authority is per owner kind): the event that mints a
;; lease MUST have a matching `:rf.resource/release-owner` path, or the
;; lease pins the entry alive (Xray lints an orphaned lease).

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

;; A MANUAL refresh is a CAUSE, not an owner (Spec 016 §Causes explain why
;; work happened): clicking "Refresh" wants fresh data but does NOT intend
;; to keep the resource alive — that's the route's job. So it dispatches
;; `:rf.resource/refetch` with `:cause` and omits `:owner`.
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
;; When a workflow OWNS a read for its lifetime, model the workflow as a
;; machine and let it own the resource under `[:machine machine-id
;; instance-id]` (released on actor destroy — Spec 016 §Release authority).
;; The machine stays the semantic workflow; the resource runtime handles
;; the cached-read mechanics. A tiny reader machine: it ensures the article
;; on entry, and the resource is released when the instance is destroyed.

;; The reader is started by the UI in two steps: BIRTH the actor with the bare
;; `[:rf.machine/start]` creation marker, then dispatch a real first event
;; `[:reader/load slug instance-id]` INTO the live actor. The split is
;; deliberate and idiomatic: the framework's initial-entry cascade synthesises
;; the birth `:entry` actions with NO event (it threads only the bare start
;; marker), so an `:entry` action cannot read args off the start marker — they
;; would arrive nil. A real event dispatched after birth DOES thread its args,
;; so the slug + instance-id this workflow owns ride on `:reader/load`.
;;
;; The `:reading` state handles `:reader/load` with the `:ensure-article`
;; action (a targetless internal transition — no `:target`, so the actor stays
;; in `:reading`): it reads slug + instance-id from the load `:event`, assigns them into the
;; snapshot `:data` (so the owner is self-describing for tools / SSR), and
;; ensures the article under a `[:machine machine-id instance-id]` owner (Spec
;; 016 §Machine-owned resource). The runtime releases that owner when the actor
;; is destroyed.
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
   ;; `:reader/load` is a TARGETLESS internal transition (a candidate map with
   ;; an `:action` ref and no `:target`): the actor stays in `:reading` and
   ;; runs `:ensure-article`. A bare `{:reader/load :ensure-article}` would read
   ;; `:ensure-article` as a sibling-state TARGET (and fail to resolve), so the
   ;; action ref must ride inside the `{:action …}` candidate map.
   {:reading {:on {:reader/load {:action :ensure-article}}}}})

;; The UI starts/stops the reader through these events. Starting BIRTHS the
;; actor with the bare `[:rf.machine/start]` marker, then dispatches the real
;; `[:reader/load slug instance-id]` event into it — that event (unlike the
;; birth marker) threads its args, so the `:reading` `:reader/load` action
;; reads the slug + instance-id and ensures the article under the
;; `[:machine … instance-id]` owner. The two dispatches order naturally: the
;; start marker's birth cascade runs first, then the load event drives the
;; ensure on the now-live actor. The event also records the active instance-id
;; in app-db so the view can read the machine-owned article + offer a stop
;; affordance. Stopping emits the reserved `[:rf.machine/destroy …]` fx, which
;; runs the actor's `:exit` cascade and releases its `[:machine …]` resource
;; owner so the entry can GC, then clears the slice.
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
;; The PASSIVE resource subs read the runtime-managed cache. A small derived
;; app sub picks a slug from the list for the "open preview" affordance.

;; A derived read over the list resource's data — projections are ordinary
;; subs LAYERED over the passive `[:rf.resource/data …]` sub (Spec 016 §No
;; :select key), NOT a resource-local hook. The sub's input-fn is a pure
;; `(fn [query-v])` returning a VECTOR OF QUERY VECTORS — never a deref'd
;; subscribe (a deref'd-subscribe input-fn raises
;; :rf.error/sub-input-fn-bad-return). The compute fn then receives the
;; resolved input values positionally: `[[articles] _]`.
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

;; EVENT-LEASE preview panel — reads the lease-ensured detail passively.
;; Mounted only while a preview slug is open; the lease ensure was dispatched
;; by `:resources.app/preview-opened` under `[:lease :resources.app/preview
;; slug]` and released by `:resources.app/preview-closed`.
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
  ;; The articles list resource was ensured by THIS route's `:resources`
  ;; metadata on entry. The view reads its full view-model passively.
  (let [state        @(subscribe [:rf.resource/state {:resource :articles/list :params {}}])
        preview-slug @(subscribe [:resources.app/preview-slug])
        reader       @(subscribe [:resources.app/reader])
        ;; A LAYERED PROJECTION over the list resource (an ordinary input-fn
        ;; sub) — the top article's slug, used by the quick-preview button below.
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
;; The React root is materialised lazily inside `run` (not at ns-load) per
;; examples/TESTING.md §Example mount-isolation convention. The app
;; establishes its frame explicitly (`reg-frame`), declares `:url-bound?
;; true` so it owns the browser URL, and wraps the render in a
;; `frame-provider` so every in-tree dispatch/subscribe resolves to it. The
;; framework `install-history-listener!` does the initial URL→slice sync and
;; popstate handling, targeted at the URL owner.

(defonce react-root (atom nil))

;; `:rf/default` is an ORDINARY frame id with no framework privilege — `init!`
;; does not create it. This app earns URL ownership by DECLARING `:url-bound?
;; true` on the frame below, not by naming the frame anything special.
(def app-frame :rf/default)

(defn run []
  (rf/init! reagent-adapter/adapter)
  ;; Override `:rf.http/managed` on the app frame so every resource ensure
  ;; routes to the per-URL canned stub above — the example runs standalone
  ;; with no backend. The override applies frame-wide; this example issues no
  ;; non-mocked requests, so a blanket override is the right grain.
  (rf/reg-frame app-frame
    {:doc          "Resources demo frame."
     :url-bound?   true
     :fx-overrides {:rf.http/managed :resources.demo/http-stub}})
  (rf/install-history-listener!)
  (when (exists? js/document)
    (when-not @react-root
      (reset! react-root (rdc/create-root (js/document.getElementById "app"))))
    (rdc/render @react-root
                [rf/frame-provider {:frame app-frame}
                 [root-view]])))
