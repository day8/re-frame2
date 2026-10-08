(ns re-frame.ep0037-r6-integrated-proof-cljs-test
  "EP-0037 R6 — the INTEGRATED proof.

  Every other EP-0037 suite proves its own row in isolation. R6 asks whether ONE
  routed application, wired the way the guides teach it, gets all of it at once.
  So this namespace carries a small routed exercise app — `conduit`, a
  Conduit/RealWorld-shaped shell with a branch-wide viewer read, two leaf reads,
  an intent-prefetching link, an auth-guarded tab and a login route — and proves
  the headline capabilities AGAINST THAT ONE APP:

    1. the parent shell reads through the BRANCH plan (declared once on the
       shell, ensured on every leaf activation, never duplicated per leaf);
    2. the leaf reads, and a sibling move keeps the shell read;
    3. intent warmup on a REAL link — the anchor the app's view body renders,
       through the handler that anchor actually carries;
    4. auth denial + fresh return, registered through the PUBLIC `rf/reg-event`
       door and sealed into a frame built AFTER the app's registrations;
    5. SSR — the `403` floor when the app registers no arm, application
       redirect supersession, and hydration REUSE (no client double-fetch);
    6. no render-caused work — the app's shell render reads subs and projects
       hrefs and prefetch payloads, and causes NOTHING.

  It also closes the integration arms that only exist BETWEEN rows: door parity
  over the effective plan (row 2, `every-door-plans-the-same-branch-and-the-
  same-reads`), `rf/route-link`'s composed CLJS intent handler and prefetch's
  absence list (row 7), `:on-match` suppression on a planning failure (row 6),
  and one integrated teardown (row 10).

  Named `*-cljs-test.cljc` so BOTH lanes run it. It lives in the resources
  artefact's test tree because that is the only `:test` alias carrying routing
  + resources + ssr + http together. The managed-HTTP fx is stubbed to a
  capturing no-op and the host nav fxs are captured, so the URL/scroll side is
  observable without a browser."
  (:require
   #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
      :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
   [re-frame.core :as rf]
   [re-frame.fx :as rf.fx]
   [re-frame.registrar :as rf.registrar]
   ;; load-bearing side-effecting requires: register the routing + resources
   ;; events / subs and resources' late-bound `:routing/*` integration hooks.
   [re-frame.resources]
   [re-frame.resources.route :as rf.resources.route]
   [re-frame.resources.ssr :as rf.resources.ssr]
   [re-frame.resources.state :as rf.resources.state]
   [re-frame.resources.test-support]
   [re-frame.routing :as rf.routing]
   [re-frame.routing.link :as rf.routing.link]
   [re-frame.schemas]
   [re-frame.http.managed]
   [re-frame.ssr :as rf.ssr]
   [re-frame.test-support :as rf.test-support]
   #?(:clj  [re-frame.substrate.plain-atom :as substrate]
      :cljs [re-frame.adapter.reagent :as substrate])))

;; ===========================================================================
;; fixture
;; ===========================================================================

(defn- init!
  "Per-test setup: reset the routing counters and re-publish the late-bound
  routing integration. The app's own frames are built by `boot-app!` in each
  test body, AFTER its registrations."
  []
  (rf.routing/reset-counters!)
  (rf.resources.route/install-routing-integration!))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter substrate/adapter
     :init-fn init!}))

;; ===========================================================================
;; THE EXERCISE APP — `conduit`
;;
;;   /              :conduit/shell     branch-wide viewer read (blocking)
;;   /feed          :conduit/feed      parent shell; page-1 feed read (blocking)
;;   /article/:slug :conduit/article   parent shell; article read (blocking)
;;   /settings      :conduit/settings  parent shell; guarded by :conduit/signed-in?
;;   /profile/:handle :conduit/profile parent shell; `:query-defaults` {:tab :authored}
;;   /login         :conduit/login     the auth landing route
;;
;; The shell declares the viewer ONCE: `:parent` IS the opt-in to the parent's
;; branch-wide requirements (EP-0037 governing law 5).
;; ===========================================================================

(def ^:private app-frame-id :conduit/app)

(def ^:private pushed    (atom []))
(def ^:private replaced  (atom []))
(def ^:private scrolled  (atom []))
(def ^:private page-views (atom []))

(defn- register-resources! []
  ;; `:params-schema` is required on every resource — the params ARE the
  ;; resource identity. A param-free read declares `[:map]`.
  (rf/reg-resource :conduit/viewer
                   {:scope :rf.scope/global :params-schema [:map]}
                   (fn [_params _ctx] {:request {:method :get :url "/api/user"}}))
  (rf/reg-resource :conduit/feed
                   {:scope :rf.scope/global :params-schema [:map [:page :int]]}
                   (fn [{:keys [page]} _ctx]
                     {:request {:method :get :url (str "/api/articles?page=" page)}}))
  (rf/reg-resource :conduit/article
                   {:scope :rf.scope/global :params-schema [:map [:slug :string]]}
                   (fn [{:keys [slug]} _ctx]
                     {:request {:method :get :url (str "/api/articles/" slug)}}))
  (rf/reg-resource :conduit/settings
                   {:scope :rf.scope/global :params-schema [:map]}
                   (fn [_params _ctx] {:request {:method :get :url "/api/user/settings"}}))
  ;; The profile read's identity includes a ROUTE QUERY key with a declared
  ;; default. `:tab` admits nil DELIBERATELY: an unfilled default then shows up
  ;; as a second resource identity instead of hiding behind a planning failure.
  (rf/reg-resource :conduit/profile
                   {:scope         :rf.scope/global
                    :params-schema [:map [:handle :string] [:tab [:maybe :keyword]]]}
                   (fn [{:keys [handle tab]} _ctx]
                     {:request {:method :get
                                :url    (str "/api/profiles/" handle "?tab=" tab)}})))

(defn- register-routes! []
  ;; The shell is an ordinary route that also happens to be a `:parent`: only
  ;; its `:resources` compose into its descendants' plans (governing law 5).
  (rf/reg-route :conduit/shell
                {:resources [{:id :viewer :resource :conduit/viewer :blocking? true}]}
                "/")
  (rf/reg-route :conduit/feed
                {:parent    :conduit/shell
                 :resources [{:id        :feed
                              :resource  :conduit/feed
                              :params    (fn [_route] {:page 1})
                              :blocking? true}]}
                "/feed")
  (rf/reg-route :conduit/article
                {:parent    :conduit/shell
                 :params    [:map [:slug :string]]
                 ;; fire-and-forget activation work (analytics): it never moves
                 ;; readiness, and runs only after the effective plan formed
                 :on-match  [[:conduit/page-viewed]]
                 :resources [{:id        :article
                              :resource  :conduit/article
                              :params    (fn [route] {:slug (get-in route [:params :slug])})
                              :blocking? true}]}
                "/article/:slug")
  (rf/reg-route :conduit/settings
                {:parent    :conduit/shell
                 :can-enter [:conduit/signed-in?]
                 :resources [{:id :settings :resource :conduit/settings :blocking? true}]}
                "/settings")
  ;; The `:query-defaults` leaf — the shape `examples/real-apps/realworld_http/
  ;; routing.cljs` ships: a declared query key with a default, read by a
  ;; resource's `:params` fn so the default is part of the READ's identity.
  (rf/reg-route :conduit/profile
                {:parent         :conduit/shell
                 :params         [:map [:handle :string]]
                 :query          [:map [:tab {:optional true}
                                        [:enum :authored :favorited]]]
                 :query-defaults {:tab :authored}
                 :resources      [{:id        :profile
                                   :resource  :conduit/profile
                                   :params    (fn [route]
                                                {:handle (get-in route [:params :handle])
                                                 :tab    (get-in route [:query :tab])})
                                   :blocking? true}]}
                "/profile/:handle")
  (rf/reg-route :conduit/login {} "/login"))

(defn- register-subs-and-events! []
  ;; The guard is an ordinary route-owned subscription returning a BOOLEAN.
  (rf/reg-sub :conduit/signed-in?
              (fn [db _] (boolean (get-in db [:conduit/session :signed-in?]))))
  (rf/reg-event :conduit/sign-in
                (fn [{:keys [db]} _] {:db (assoc-in db [:conduit/session :signed-in?] true)}))
  (rf/reg-event :conduit/page-viewed
                (fn [_ _] (swap! page-views conj :view) {})))

(defn- register-auth-arm!
  "Register the application's `:rf.route/entry-denied` arm through the PUBLIC
  `rf/reg-event` — the spelling every guide, example and skill teaches.

    :none    register nothing: the framework's shipped no-op default handles the
             denial — a hard client deny, and the `403` floor on a server frame.
    :client  the fresh-return recipe — stash the denied `RouteDestination`,
             replace-navigate to login, and after sign-in navigate FRESH to it.
    :server  emit Spec 011's canonical `:rf.server/redirect`, whose redirect
             precedence supersedes the default `403`.

  BEHAVIOUR ONLY, with no metadata map: the denial payload's URL carriers keep
  their framework `:sensitive` classification across a behaviour override,
  which `re-frame.routing-egress-test/public-entry-denied-override-still-
  redacts-carriers-on-egress` proves at actual egress."
  [arm]
  (case arm
    :none nil
    :client (rf/reg-event :rf.route/entry-denied
                          (fn [{:keys [db]} [_ {:keys [destination]}]]
                            {:db (assoc-in db [:conduit/session :return-to] destination)
                             :fx [[:dispatch [:rf.route/navigate
                                              {:to :conduit/login :replace? true}]]]}))
    :server (rf/reg-event :rf.route/entry-denied
                          (fn [_ _] {:fx [[:rf.server/redirect {:location "/login"}]]}))))

(defn- stub-host-fx! []
  (reset! pushed [])
  (reset! replaced [])
  (reset! scrolled [])
  (reset! page-views [])
  (rf.fx/reg-fx :rf.http/managed (fn [_ctx _args] nil))
  (rf.fx/reg-fx :rf.nav/push-url    {:platforms #{:server :client}}
             (fn [_ url] (swap! pushed conj url) nil))
  (rf.fx/reg-fx :rf.nav/replace-url {:platforms #{:server :client}}
             (fn [_ url] (swap! replaced conj url) nil))
  (rf.fx/reg-fx :rf.nav/scroll         {:platforms #{:server :client}}
             (fn [_ arg] (swap! scrolled conj arg) nil))
  (rf.fx/reg-fx :rf.nav/capture-scroll {:platforms #{:server :client}} (fn [_ _] nil)))

(defn- register-app!
  "Load the exercise app: its resources, routes, subs, events and the requested
  `:rf.route/entry-denied` arm. This is the app's namespaces loading."
  ([] (register-app! :none))
  ([auth-arm]
   (register-resources!)
   (register-routes!)
   (register-subs-and-events!)
   (register-auth-arm! auth-arm)
   (stub-host-fx!)))

(defn- seal-frame!
  "Build one of the app's frames and return its id, AFTER `register-app!` — the
  order an application loads in. Were the default image to select both the
  app's provenanced `:rf.route/entry-denied` and the framework's no-provenance
  default, this `rf/make-frame` would throw `:rf.error/image-duplicate-id`."
  ([] (seal-frame! {}))
  ([{:keys [frame-id platform url-bound?]}]
   (let [id (or frame-id app-frame-id)]
     ;; NOT url-bound by default: a url-bound frame syncs to the HOST address
     ;; bar on a host that has one (the CLJS lane), which would make this
     ;; proof's activations host-dependent. The URL-driven doors are driven
     ;; explicitly instead.
     (rf/make-frame (cond-> {:id id :doc "conduit — the EP-0037 R6 exercise app"}
                      platform   (assoc :platform platform)
                      url-bound? (assoc :url-bound? true)))
     id)))

(defn- boot-app!
  "Load the app and seal one frame — the ordinary single-frame boot."
  ([] (boot-app! {}))
  ([{:keys [auth-arm] :as opts}]
   (register-app! (or auth-arm :none))
   (seal-frame! opts)))

;; ---- the app's view bodies (the render substrate) --------------------------

(defn- article-link-props
  "The app's article link as its view body authors it: an address, one
  link-behaviour key and ordinary DOM attributes on one flat map.
  `caller-intent` is an application `:on-mouse-enter` the framework's intent
  handler must COMPOSE with, not replace."
  ([slug] (article-link-props slug nil))
  ([slug caller-intent]
   (cond-> {:to         :conduit/article
            :params     {:slug slug}
            :prefetch   :intent
            :class      "preview-link"
            :aria-label (str "Read " slug)}
     caller-intent (assoc :on-mouse-enter caller-intent))))

(defn- render-shell
  "What the shell view body does at render time, and nothing more: read the
  route projection through subscriptions and project one nav link's anchor —
  `route-link-render-ssr` on the JVM, `route-link-render` on CLJS."
  [frame-id slug]
  (rf/with-frame frame-id
    {:route  @(rf/subscribe [:rf/route])
     :chain  @(rf/subscribe [:rf.route/chain])
     :anchor #?(:clj  (rf.routing/route-link-render-ssr (article-link-props slug) "Read it")
                :cljs (rf.routing.link/route-link-render     (article-link-props slug) "Read it"))}))

;; ===========================================================================
;; helpers over the app's observable state
;; ===========================================================================

(defn- rdb     [frame-id] (:rf.db/runtime (rf/frame-state-value frame-id)))
(defn- slice   [frame-id] (get-in (rdb frame-id) [:rf.runtime/routing :current]))
(defn- entries [frame-id] (get-in (rdb frame-id) (rf.resources.state/entries-path)))
(defn- entry   [frame-id k] (get-in (rdb frame-id) (rf.resources.state/entry-path k)))
(defn- pending [frame-id] (get-in (rdb frame-id) [:rf.runtime/routing :pending-navigation]))

(def ^:private viewer-key   (rf.resources.state/scoped-resource-key* :rf.scope/global :conduit/viewer {}))
(def ^:private feed-key     (rf.resources.state/scoped-resource-key* :rf.scope/global :conduit/feed {:page 1}))
(def ^:private settings-key (rf.resources.state/scoped-resource-key* :rf.scope/global :conduit/settings {}))
(defn- article-key [slug]
  (rf.resources.state/scoped-resource-key* :rf.scope/global :conduit/article {:slug slug}))
(defn- profile-key [handle tab]
  (rf.resources.state/scoped-resource-key* :rf.scope/global :conduit/profile {:handle handle :tab tab}))

(defn- route-owners
  "The `[:route …]` owners currently attached to an entry."
  [e]
  (filterv (fn [o] (and (vector? o) (= :route (first o)))) (:active-owners e)))

(defn- identity-set
  "The scoped resource identities this frame's entries hold — the observable
  projection of the effective plan."
  [frame-id]
  (into #{} (map (fn [[_ e]] (:resource/key e))) (entries frame-id)))

(defn- profile-identities
  "Every `:conduit/profile` identity this frame holds an entry for."
  [frame-id]
  (vec (sort-by str (filter #(= :conduit/profile (second %)) (identity-set frame-id)))))

(defn- settle! [frame-id k data]
  (let [e (entry frame-id k)]
    (rf/dispatch-sync [:rf.resource.internal/succeeded
                       {:resource/key k
                        :work/id      (:current-work e)
                        :generation   (:generation e)
                        :data         data}]
                      {:frame frame-id})))

(defn- reset-host-effects! []
  (reset! pushed []) (reset! replaced []) (reset! scrolled []) (reset! page-views []))

(def ^:private listener-seq (atom 0))

(defn- capture-traces
  "Run `f` with a trace listener installed; return the collected trace events.
  The 2-arity also hands each event to `on-event` AS IT ARRIVES (delivery is
  synchronous), which is what lets an ORDERING claim interleave trace events
  with milestones `f`'s own code reaches."
  ([f] (capture-traces f nil))
  ([f on-event]
   (let [seen (atom [])
         id   (keyword "ep0037-r6" (str "listener-" (swap! listener-seq inc)))]
     (rf/register-listener! :trace id (fn [ev]
                                        (swap! seen conj ev)
                                        (when on-event (on-event ev))))
     (try (f) (finally (rf/unregister-listener! :trace id)))
     @seen)))

(defn- event-ids [traces]
  (into #{} (comp (filter #(= :rf.event/run-start (:operation %)))
                  (map #(-> % :tags :rf.trace/event-id)))
        traces))

;; ===========================================================================
;; 1 + 2. The parent shell reads through the BRANCH plan; the leaf reads too
;;        (EP conformance rows 4 and 5, through the app rather than the planner)
;; ===========================================================================

(deftest shell-and-leaf-read-through-one-branch-plan
  (let [app (boot-app!)]
    (rf/dispatch-sync [:rf.route/navigate {:to :conduit/article :params {:slug "routing-as-data"}}]
                      {:frame app})
    (let [akey  (article-key "routing-as-data")
          owner [[:route :conduit/article (:nav-token (slice app))]]]
      (is (= [#{viewer-key akey} owner owner]
             [(identity-set app) (route-owners (entry app viewer-key)) (route-owners (entry app akey))])
          "the parent contributed the viewer without the leaf re-declaring it: one entry per
           identity, each owned once by THIS activation's plan")
      (is (= :loading (:transition (slice app))) "the route waits on the blocking branch")
      (settle! app viewer-key {:username "ada"})
      (settle! app akey {:title "Routing as data"})
      (is (= [:idle nil 1] [(:transition (slice app)) (:error (slice app)) (count @page-views)])
          ":idle once every blocking requirement has data; activation work ran exactly once"))))

(deftest sibling-leaf-navigation-keeps-the-shell-read
  ;; Moving between leaves of one shell does not turn the parent requirement
  ;; into a new page load (the partial-revalidation law).
  (let [app (boot-app!)]
    (rf/dispatch-sync [:rf.route/navigate {:to :conduit/article :params {:slug "a"}}] {:frame app})
    (settle! app viewer-key {:username "ada"})
    (settle! app (article-key "a") {:title "A"})
    (let [gen-before (:generation (entry app viewer-key))]
      (rf/dispatch-sync [:rf.route/navigate {:to :conduit/feed}] {:frame app})
      (is (= [gen-before true true [] 1]
             [(:generation (entry app viewer-key))
              (rf.resources.state/has-data? (entry app viewer-key))
              (some? (entry app feed-key))
              (route-owners (entry app (article-key "a")))
              (count (route-owners (entry app viewer-key)))])
          "the shell read is KEPT with its data, the added leaf is ensured, the removed leaf's
           owner is released, and attach-before-release left exactly one owner on the shell"))))

;; ===========================================================================
;; Door parity over the EFFECTIVE plan (row 2)
;; ===========================================================================

(defn- plan-footprint
  "The committed target facts, the URL they derive to, the parent-to-leaf chain
  and the effective resource identity set. History and scroll EFFECTS are
  absent — row 2 allows exactly those to differ by cause."
  [f]
  (let [s (slice f)]
    {:target     (select-keys s [:route-id :params :query :fragment])
     :url        (rf.routing/route-url (-> (select-keys s [:params :query :fragment])
                                        (assoc :to (:route-id s))))
     :chain      (rf/with-frame f @(rf/subscribe [:rf.route/chain]))
     :identities (identity-set f)}))

(defn- door-footprint
  "Activate one destination through one door on its own freshly sealed frame.
  `addr` is the NAMED address; `url` is the same destination as a URL."
  [frame-id door {:keys [addr url]}]
  (let [f (seal-frame! {:frame-id frame-id})]
    (case door
      :navigate (rf/dispatch-sync [:rf.route/navigate addr] {:frame f})
      :raw-url  (rf/dispatch-sync [:rf.route/navigate {:url url}] {:frame f})
      :link     (rf/dispatch-sync [:rf.route/url-requested (assoc addr :url url)] {:frame f})
      :url      (rf/dispatch-sync [:rf.route/handle-url-change url] {:frame f}))
    (plan-footprint f)))

(defn- door-footprints
  "The four client doors' footprints plus the SSR server door's, each on its own
  fresh frame, namespaced by `label`."
  [label destination]
  (let [client (into {} (map (fn [door]
                               [door (door-footprint
                                       (keyword "conduit" (str label "-" (name door)))
                                       door destination)]))
                     [:navigate :raw-url :link :url])
        server (let [srv (seal-frame! {:frame-id (keyword "conduit" (str label "-ssr"))
                                       :platform :server})]
                 (rf/dispatch-sync [:rf.route/handle-url-change (:url destination)]
                                   {:frame srv})
                 (plan-footprint srv))]
    (assoc client :ssr server)))

(deftest every-door-plans-the-same-branch-and-the-same-reads
  ;; Five doors agree on the target, its URL, the branch and the effective
  ;; reads. The `:query-defaults` route is the hard case: `match-url` fills the
  ;; default for the URL-bearing doors and the named-address door goes nowhere
  ;; near it, so the ONE ResolvedTarget seam must fill it there — else one
  ;; destination commits a different slice, history entry and cache identity
  ;; by door. The target carries the default; the canonical URL never spells it.
  (register-app!)
  (doseq [[label destination expected]
          [["article"
            {:addr {:to :conduit/article :params {:slug "door-parity"}} :url "/article/door-parity"}
            {:target     {:route-id :conduit/article :params {:slug "door-parity"} :query {} :fragment nil}
             :url        "/article/door-parity"
             :chain      [:conduit/shell :conduit/article]
             :identities #{viewer-key (article-key "door-parity")}}]
           ["profile"
            {:addr {:to :conduit/profile :params {:handle "ada"}} :url "/profile/ada"}
            {:target     {:route-id :conduit/profile :params {:handle "ada"} :query {:tab :authored} :fragment nil}
             :url        "/profile/ada"
             :chain      [:conduit/shell :conduit/profile]
             :identities #{viewer-key (profile-key "ada" :authored)}}]]]
    (doseq [[door footprint] (door-footprints label destination)]
      (is (= expected footprint) (str label ": door " door " diverged")))))

(deftest a-url-that-spells-the-default-resolves-the-same-target
  ;; `/profile/ada?tab=authored` is the non-canonical spelling of `/profile/ada`:
  ;; the same target and read, deriving the canonical URL back.
  (register-app!)
  (let [bare    (door-footprint :conduit/dflt-bare :url {:url "/profile/ada"})
        spelled (door-footprint :conduit/dflt-spelled :url {:url "/profile/ada?tab=authored"})]
    (is (= [bare "/profile/ada"] [spelled (:url spelled)]))))

;; ===========================================================================
;; 3. Intent warmup on a REAL link (EP conformance row 7 + row 3's intent arm)
;; ===========================================================================

(deftest a-real-link-warms-the-whole-branch-on-intent
  (let [app  (boot-app!)
        slug "warm-me"
        akey (article-key slug)]
    (rf/dispatch-sync [:rf.route/navigate {:to :conduit/feed}] {:frame app})
    (settle! app viewer-key {:username "ada"})
    (settle! app feed-key [{:slug slug}])
    (reset-host-effects!)
    (let [before-slice (slice app)
          viewer-gen   (:generation (entry app viewer-key))
          props        (article-link-props slug)
          attrs        (second (:anchor (render-shell app slug)))]
      (is (= ["/article/warm-me" false "preview-link"]
             [(:href attrs) (contains? attrs :prefetch) (:class attrs)])
          "the anchor is an href projection; :prefetch is a link behaviour key that never
           reaches the DOM, while ordinary DOM props pass through")
      (is (= [:rf.route/prefetch {:to :conduit/article :params {:slug slug}}]
             (rf.routing.link/prefetch-payload props))
          "the link's intent payload is the address ONLY — no policy, no fragment")
      #?(:cljs (is (fn? (:on-mouse-enter attrs))
                   "the real anchor carries the composed intent handler; that
                    handler's own behaviour is pinned by
                    `the-real-anchor-intent-handler-composes-and-dispatches`"))
      ;; The anchor's handler enqueues ASYNCHRONOUSLY (a DOM event handler), so
      ;; the exact payload it dispatches is dispatched synchronously here.
      (let [traces (capture-traces
                     #(rf/dispatch-sync (rf.routing.link/prefetch-payload props) {:frame app}))]
        (is (= [true true []]
               [(some? (entry app viewer-key)) (some? (entry app akey)) (route-owners (entry app akey))])
            "warm mode ran the FULL effective branch plan, ownerlessly — the warmed leaf stays GC-eligible")
        (is (= [before-slice nil [] [] [] [] false viewer-gen]
               [(slice app) (pending app) @pushed @replaced @scrolled @page-views
                (boolean (some #(#{:rf.route/navigate :rf.route/entry-denied :rf.route/navigation-blocked}
                                 (-> % :tags :rf.trace/event-id))
                               traces))
                (:generation (entry app viewer-key))])
            "prefetch is not activation: no route state or token, no pending navigation, no
             history or scroll effect, no :on-match, no navigation or guard event — and the
             already-fresh shell read was not refetched"))
      (rf/dispatch-sync [:rf.route/navigate {:to :conduit/article :params {:slug slug}}]
                        {:frame app})
      (let [e (entry app akey)]
        (is (= [[[:route :conduit/article (:nav-token (slice app))]] 1 1]
               [(route-owners e) (:attempt e) (count @page-views)])
            "activation attached its owner to the warmed identity, joined the warm work rather
             than starting a second attempt, and ran :on-match once")))))

(deftest hover-then-click-on-a-query-defaults-route-warms-one-identity
  ;; R3's headline capability on a `:query-defaults` route: hover the link, then
  ;; click THAT SAME link, and there is exactly ONE cache entry, carrying the
  ;; activation's owner on its FIRST attempt. Were the named-address door to
  ;; skip the defaults, warmup would resolve `{:tab nil}` and the click
  ;; `{:tab :authored}` — two entries, SILENTLY: the failure mode
  ;; `rf.routing.link/validate-prefetch!` argues for failing loud about.
  (let [app    (boot-app!)
        handle "ada"
        pkey   (profile-key handle :authored)
        props  {:to :conduit/profile :params {:handle handle} :prefetch :intent}
        ;; ONE link: `prefetch-payload` is what `:on-mouse-enter` dispatches and
        ;; `link-model`'s `:payload` is what `:on-click` dispatches
        model  (rf.routing.link/link-model props app)]
    (is (= [(str "/profile/" handle) [:rf.route/prefetch {:to :conduit/profile :params {:handle handle}}]]
           [(:href model) (rf.routing.link/prefetch-payload props)])
        "the href omits the defaulted key; the payload is the address only — the prefetch
         handler resolves the defaults through the seam the activation uses")
    (rf/dispatch-sync (rf.routing.link/prefetch-payload props) {:frame app})
    (is (= [[pkey] []] [(profile-identities app) (route-owners (entry app pkey))])
        "hover warmed exactly ONE identity — the default's, not `{:tab nil}` — ownerlessly")
    (rf/dispatch-sync (:payload model) {:frame app})
    (let [e (entry app pkey)]
      (is (= [[pkey] [[:route :conduit/profile (:nav-token (slice app))]] 1 {:tab :authored}]
             [(profile-identities app) (route-owners e) (:attempt e) (:query (slice app))])
          "the click joined the warm work on its first attempt and the committed slice carries
           the resolved default"))))

#?(:cljs
   (deftest the-real-anchor-intent-handler-composes-and-dispatches
     (testing "row 7's `BOTH link surfaces` clause for `rf/route-link`. The
               handler must run the caller's `:on-mouse-enter` FIRST and then
               enqueue EXACTLY ONE prefetch payload, stamped `:source :router`,
               to the frame that RENDERED the link — not an ambient frame
               resolved at event time.

               Read as an ORDERED MILESTONE SEQUENCE, because neither law is
               visible to an after-the-fact read. `(some? (first
               (filter …)))` over the captured traces is exactly as true for one
               dispatch as for five, so cardinality goes unasserted; and a
               caller-ran counter inspected once the composed handler has already
               RETURNED is exactly as true whether the dispatch preceded the
               caller or followed it, so ordering goes unasserted. Under such a
               read, a mutation that dispatched twice, and before the caller,
               would keep the whole CLJS lane green. So the caller pushes
               `:caller` and the trace listener pushes `:dispatch` into ONE
               atom as each happens, and the law IS the sequence."
       (let [app        (boot-app!)
             other      (seal-frame! {:frame-id :conduit/other})
             slug       "composed"
             ;; The expected payload, written out rather than read back through
             ;; `prefetch-payload`: an expectation that arrives through the seam
             ;; under test agrees with it by construction.
             expected   [:rf.route/prefetch {:to :conduit/article :params {:slug slug}}]
             milestones (atom [])
             props      (article-link-props slug (fn [_e] (swap! milestones conj :caller)))
             attrs      (second (rf/with-frame app
                                  (rf.routing.link/route-link-render props "Read it")))]
         (is (fn? (:on-mouse-enter attrs)))
         ;; render scope has unwound by the time a real pointer arrives, and a
         ;; DIFFERENT frame is ambient; the handler must still target the
         ;; render-time frame.
         (let [traces (capture-traces
                        #(rf/with-frame other ((:on-mouse-enter attrs) #js {}))
                        (fn [ev]
                          (when (and (= :rf.event/dispatched (:operation ev))
                                     (= expected (-> ev :tags :rf.event/v)))
                            (swap! milestones conj :dispatch))))
               rows   (filterv #(= :rf.event/dispatched (:operation %)) traces)
               row    (first rows)]
           (is (= [:caller :dispatch] @milestones)
               "the caller's own intent handler ran FIRST and exactly ONE
                prefetch dispatch followed it — composed, not replaced; once,
                not twice; after, not before")
           (is (= 1 (count rows))
               "…and the composed handler enqueued nothing else besides it")
           (is (= expected (-> row :tags :rf.event/v))
               "…the dispatch is the address-only prefetch payload")
           (is (= app (-> row :tags :frame))
               "…targeting the RENDER-time frame, not the ambient one")
           ;; the trace projection lifts `:source` out of `:tags` onto the
           ;; event row itself.
           (is (= :router (:source row))
               "…attributed to the routing substrate"))))))

;; ===========================================================================
;; 4. Auth denial + fresh return, through the PUBLIC door (EP row 8)
;; ===========================================================================

(deftest the-app-seals-a-frame-after-registering-the-public-denial-handler
  ;; Rather than throwing :rf.error/image-duplicate-id with colliding
  ;; coordinates [{:ns nil} {:ns "<app ns>"}].
  (let [app (boot-app! {:auth-arm :client})]
    (is (= [true 1 true]
           [(some? app)
            (count (filter #(= :rf.route/entry-denied %) (keys (rf.registrar/registrations :event))))
            (some? (seal-frame! {:frame-id :conduit/second-frame}))])
        "the frame sealed, the app's registration replaced the default, and a later frame seals too")))

(deftest a-denial-handler-registered-after-the-frame-was-sealed-still-fires
  ;; The complementary order — `docs/routing/testing.md` writes its entry-denied
  ;; spy this way: a re-eval'd registration resolves a fresh sealed generation
  ;; and swaps it into the live frame (docs/core/images.md §re-eval).
  (register-app! :none)
  (let [app  (seal-frame! {:frame-id :conduit/late-arm})
        seen (atom [])]
    (rf/dispatch-sync [:rf.route/navigate {:to :conduit/feed}] {:frame app})
    (rf/reg-event :rf.route/entry-denied (fn [_ [_ d]] (swap! seen conj d) {}))
    (rf/dispatch-sync [:rf.route/navigate {:to :conduit/settings}] {:frame app})
    (is (= [[{:to :conduit/settings}] :conduit/feed]
           [(mapv :destination @seen) (:route-id (slice app))])
        "the late registration received the one denial, and the deny still held")))

(deftest denial-redirects-to-login-and-a-fresh-navigate-returns
  (let [app (boot-app! {:auth-arm :client})]
    (rf/dispatch-sync [:rf.route/navigate {:to :conduit/feed}] {:frame app})
    (settle! app viewer-key {:username "ada"})
    (settle! app feed-key [])
    (rf/dispatch-sync [:rf.route/navigate {:to :conduit/settings}] {:frame app})
    (is (= [:conduit/login nil nil {:to :conduit/settings}]
           [(:route-id (slice app)) (pending app) (entry app settings-key)
            (get-in (rf/app-db-value app) [:conduit/session :return-to])])
        "denied TERMINALLY: replace-navigated to login, no pending navigation, the guarded read
         never ensured, the destination stashed as a replayable RouteDestination")
    (rf/dispatch-sync [:conduit/sign-in] {:frame app})
    (rf/dispatch-sync [:rf.route/navigate (get-in (rf/app-db-value app) [:conduit/session :return-to])]
                      {:frame app})
    (is (= [:conduit/settings true true]
           [(:route-id (slice app)) (some? (entry app settings-key)) (some? (entry app viewer-key))])
        "a FRESH navigate re-evaluates the guard and plans the leaf beside the shell read")))

(deftest denial-with-no-application-arm-is-a-hard-client-deny
  ;; The shipped no-op default keeps a denial SAFE for an app with no arm.
  (let [app (boot-app! {:auth-arm :none})]
    (rf/dispatch-sync [:rf.route/navigate {:to :conduit/feed}] {:frame app})
    (let [traces (capture-traces
                   #(rf/dispatch-sync [:rf.route/navigate {:to :conduit/settings}] {:frame app}))]
      (is (= [1 false :conduit/feed nil]
             [(count (filter #(= :rf.route/entry-denied (:operation %)) traces))
              (boolean (some #(= :rf.error/no-such-handler (:operation %)) traces))
              (:route-id (slice app))
              (pending app)])
          "exactly one denial trace, no :rf.error/no-such-handler, and the app stayed put"))))

;; ===========================================================================
;; 5. SSR — the 403 floor, redirect supersession, and hydration REUSE
;; ===========================================================================

(deftest ssr-hard-deny-stamps-the-403-floor
  (let [srv (boot-app! {:auth-arm :none :frame-id :conduit/server :platform :server})]
    (rf/dispatch-sync [:rf.route/handle-url-change "/settings"] {:frame srv})
    (is (= [403 nil nil] [(:status (rf.ssr/get-response srv)) (slice srv) (entry srv settings-key)])
        "a guarded deep link with no application arm stamps 403 and commits nothing")))

(deftest ssr-application-redirect-supersedes-the-403
  (let [srv (boot-app! {:auth-arm :server :frame-id :conduit/server :platform :server})]
    (rf/dispatch-sync [:rf.route/handle-url-change "/settings"] {:frame srv})
    (let [resp (rf.ssr/get-response srv)]
      (is (= ["/login" 302] [(get-in resp [:redirect :location]) (:status resp)])))))

(deftest ssr-hydration-reuses-the-servers-branch-reads
  ;; The client does not duplicate an SSR ensure merely because the branch was
  ;; rebuilt.
  (let [srv  (boot-app! {:frame-id :conduit/server :platform :server})
        akey (article-key "hydrate-me")]
    (rf/dispatch-sync [:rf.route/handle-url-change "/article/hydrate-me"] {:frame srv})
    (is (= [:conduit/article :loading] [(:route-id (slice srv)) (:transition (slice srv))])
        "the server waits on the branch's blocking requirements")
    (settle! srv viewer-key {:username "ada"})
    (settle! srv akey {:title "Hydrate me"})
    (is (= :idle (:transition (slice srv))) "…and renders once every one has data")
    (let [hydrated (rf.resources.ssr/hydrate-runtime-db
                     (rf.resources.ssr/project-resources-runtime-db (rdb srv) srv))
          hydrated-entry #(get-in hydrated [rf.resources.state/resources-key :entries
                                            (rf.resources.state/key-id %)])]
      (is (= [true true []]
             [(some? (hydrated-entry viewer-key)) (some? (hydrated-entry akey))
              (vec (rf.resources.ssr/hydrate-refetch-plan hydrated))])
          "both branch reads crossed the wire and the whole branch is reused — no double-fetch"))))

;; ===========================================================================
;; 6. No render-caused work (EP row 3, against the RUNNING app)
;; ===========================================================================

(deftest rendering-the-app-shell-causes-nothing
  (let [app  (boot-app!)
        slug "passive"]
    (rf/dispatch-sync [:rf.route/navigate {:to :conduit/feed}] {:frame app})
    (settle! app viewer-key {:username "ada"})
    (settle! app feed-key [{:slug slug}])
    (reset-host-effects!)
    (let [before-slice   (slice app)
          before-entries (entries app)
          traces         (capture-traces #(dotimes [_ 3] (render-shell app slug)))
          shell          (render-shell app slug)]
      (is (= [:conduit/feed [:conduit/shell :conduit/feed] "/article/passive"]
             [(:route-id (:route shell)) (:chain shell) (:href (second (:anchor shell)))])
          "rendering READ the projections — the chain composed from state, not an outlet")
      (is (= [#{} before-slice before-entries [] [] [] []]
             [(event-ids traces) (slice app) (entries app) @pushed @replaced @scrolled @page-views])
          "…and CAUSED nothing: no event, no slice or entry change, no history or scroll effect"))))

;; ===========================================================================
;; A planning failure commits a failed activation and runs NO activation work
;; (EP row 6: `:on-match` "is not dispatched when planning fails")
;; ===========================================================================

(deftest a-planning-failure-commits-the-target-and-suppresses-on-match
  (register-app!)
  ;; the failure arm of the app's own planner: a params resolver failing closed
  (rf/reg-route :conduit/broken
                {:parent    :conduit/shell
                 :on-match  [[:conduit/page-viewed]]
                 :resources [{:id        :article
                              :resource  :conduit/article
                              :params    (fn [_route] nil)
                              :blocking? true}]}
                "/broken")
  (let [app (seal-frame! {:frame-id :conduit/broken-app})]
    (rf/dispatch-sync [:rf.route/navigate {:to :conduit/broken}] {:frame app})
    (is (= [:conduit/broken "/broken" :error :rf.error/resource-route-plan #{} []]
           [(:route-id (slice app)) (rf.routing/route-url {:to (:route-id (slice app))})
            (:transition (slice app)) (:rf.error/id (:error (slice app)))
            (identity-set app) @page-views])
        "the failed target COMMITS (the error is addressable), readiness projects the
         structured planning error, no partial plan executed and :on-match was suppressed")))

;; ===========================================================================
;; 10. Frame isolation and teardown — integrated (EP row 10)
;; ===========================================================================

(deftest two-frames-of-the-app-share-no-plan-and-no-warm-work
  (register-app!)
  (let [a    (seal-frame! {:frame-id :conduit/app-a})
        b    (seal-frame! {:frame-id :conduit/app-b})
        akey (article-key "isolated")]
    (rf/dispatch-sync [:rf.route/navigate {:to :conduit/feed}] {:frame a})
    (is (= [:conduit/feed nil true] [(:route-id (slice a)) (slice b) (empty? (entries b))])
        "A activated; B has no route and ensured nothing")
    (rf/dispatch-sync [:rf.route/prefetch {:to :conduit/article :params {:slug "isolated"}}] {:frame a})
    (is (= [true nil] [(some? (entry a akey)) (entry b akey)])
        "a prefetch in A warms A only — the carried-frame invariant holds for cache entries")
    (rf/dispatch-sync [:rf.route/navigate {:to :conduit/article :params {:slug "isolated"}}] {:frame b})
    (is (= [[[:route :conduit/article (:nav-token (slice b))]] [] true]
           [(route-owners (entry b akey)) (route-owners (entry a akey))
            (= (:nav-token (slice a)) (:nav-token (slice b)))])
        "B builds its OWN plan and owners while A's copy stays the ownerless warm entry, and
         each frame allocated its first nav-token independently")))

(deftest destroying-a-frame-releases-its-whole-routing-footprint
  (register-app!)
  ;; a leaveable route, so the frame also holds a pending leave at destroy time
  (rf/reg-route :conduit/editor
                {:parent    :conduit/shell
                 :can-leave [:conduit/editor-clean?]}
                "/editor")
  (rf/reg-sub :conduit/editor-clean? (fn [_ _] false))
  (let [keep-alive (seal-frame! {:frame-id :conduit/keeper})
        doomed     (seal-frame! {:frame-id :conduit/doomed})
        akey       (article-key "teardown")]
    (rf/dispatch-sync [:rf.route/navigate {:to :conduit/editor}] {:frame doomed})
    (rf/dispatch-sync [:rf.route/prefetch {:to :conduit/article :params {:slug "teardown"}}]
                      {:frame doomed})
    (rf/dispatch-sync [:rf.route/navigate {:to :conduit/feed}] {:frame doomed})
    (is (= [true true true]
           [(some? (pending doomed)) (some? (entry doomed akey)) (boolean (seq (identity-set doomed)))])
        "premise: the doomed frame holds a blocked leave, warm prefetch work and a live plan")
    (rf/dispatch-sync [:rf.route/navigate {:to :conduit/feed}] {:frame keep-alive})
    (let [keeper-ids (identity-set keep-alive)]
      (rf/destroy-frame! doomed)
      (is (nil? (rf/frame-state-value doomed))
          "no runtime-db survives — plans, owners, warm entries and the pending leave went with it")
      (is (= [:conduit/feed keeper-ids nil]
             [(:route-id (slice keep-alive)) (identity-set keep-alive) (pending keep-alive)])
          "the surviving frame is untouched"))))
