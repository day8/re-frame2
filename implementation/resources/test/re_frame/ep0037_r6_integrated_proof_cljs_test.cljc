(ns re-frame.ep0037-r6-integrated-proof-cljs-test
  "EP-0037 R6 — the INTEGRATED proof (rf2-kqxe6.10).

  Every other EP-0037 slice proved its own row in isolation. R6 asks a different
  question: does ONE routed application, wired the way the guides teach it,
  actually get all of it at once? So this namespace carries a small routed
  exercise app — `conduit`, a Conduit/RealWorld-shaped shell with a branch-wide
  viewer read, two leaf reads, an intent-prefetching link, an auth-guarded tab
  and a login route — and then proves the headline capabilities AGAINST THAT ONE
  APP:

    1. the parent shell reads through the BRANCH plan (declared once on the
       shell, ensured on every leaf activation, never duplicated per leaf);
    2. the leaf reads, and a sibling move keeps the shell read;
    3. intent warmup on a REAL link — the anchor the app's view body renders,
       through the handler that anchor actually carries;
    4. auth denial + fresh return, registered through the PUBLIC `rf/reg-event`
       door and sealed into a frame built AFTER the app's registrations — the
       exact order that used to fail with `:rf.error/image-duplicate-id`
       (rf2-0r6q4 / PR #6932). Proving the denial row through the internal
       `events/reg-event` back door would certify a door applications cannot
       use;
    5. SSR — the `403` floor when the app registers no arm, application
       redirect supersession, and hydration REUSE (no client double-fetch);
    6. no render-caused work — the app's shell render reads subs and projects
       hrefs and prefetch payloads, and causes NOTHING.

  Deliberately a small app, not a RealWorld clone: the proof needs exactly
  enough surface to exercise a parent/leaf branch, a guard, a link and an SSR
  round trip. Extra pages would add wall-clock, not evidence.

  Beyond the six, the file closes integration arms no per-slice suite reached,
  because they only exist BETWEEN slices:

    * DOOR PARITY over the effective plan (row 2). The per-slice suites prove
      cause-parametric targets for a resource-FREE route, and branch
      composition through one door. Nothing proved that five doors produce the
      same branch AND the same resource identity set. `every-door-plans-the-
      same-branch-and-the-same-reads` does.
    * `rf/route-link`'s CLJS intent arm (row 7). `v/route-link` and Freehand
      have real-DOM intent tests; `rf/route-link`'s composed
      `:on-mouse-enter` had none.
    * prefetch's ABSENCE list (row 7): no guard, no `:on-match`, no scroll/URL
      fx, no sibling frame.
    * `:on-match` suppression on a planning failure (row 6).
    * one integrated teardown (row 10): a frame holding a branch plan, route
      owners, warm prefetch work and a pending leave releases all of it.

  Named `*-cljs-test.cljc` so BOTH lanes run it: the JVM runner (`.*-test$`,
  `clojure -M:test` from `implementation/resources`) and the shadow-cljs
  `:node-test` build (`cljs-test$`, `npm run test:cljs`). It lives in the
  resources artefact's test tree because that is the only `:test` alias
  carrying routing + resources + ssr + http together — the exact classpath an
  integrated routed app needs.

  The managed-HTTP fx is stubbed to a capturing no-op so ensure's entry write
  and the reply-driven blocking drain are deterministic without a live fetch;
  the host nav fxs are captured so the URL/scroll side is observable without a
  browser."
  (:require
   #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
      :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
   [re-frame.core :as rf]
   [re-frame.fx :as fx]
   [re-frame.registrar :as registrar]
   ;; load-bearing side-effecting requires: register the routing + resources
   ;; events / subs and resources' late-bound `:routing/*` integration hooks.
   [re-frame.resources]
   [re-frame.resources.route :as res-route]
   [re-frame.resources.ssr :as res-ssr]
   [re-frame.resources.state :as state]
   [re-frame.resources.test-support]
   [re-frame.routing :as routing]
   [re-frame.routing.link :as link]
   [re-frame.schemas]
   [re-frame.http.managed]
   [re-frame.ssr :as ssr]
   [re-frame.test-support :as core-test-support]
   #?(:clj  [re-frame.substrate.plain-atom :as substrate]
      :cljs [re-frame.adapter.reagent :as substrate])))

;; ===========================================================================
;; fixture
;; ===========================================================================

(defn- init!
  "Per-test setup (runs after adapter install, registrar live): reset the
  routing counters and re-publish the late-bound routing integration. The
  exercise app's own frames are built by `boot-app!` in each test body, AFTER
  its registrations — see the ns docstring."
  []
  (routing/reset-counters!)
  (res-route/install-routing-integration!))

(use-fixtures :each
  (core-test-support/make-reset-runtime-fixture
    {:adapter substrate/adapter
     :init-fn init!}))

;; ===========================================================================
;; THE EXERCISE APP — `conduit`
;;
;;   /              :conduit/shell     branch-wide viewer read (blocking)
;;   /feed          :conduit/feed      parent shell; page-1 feed read (blocking)
;;   /article/:slug :conduit/article   parent shell; article read (blocking)
;;   /settings      :conduit/settings  parent shell; guarded by :conduit/signed-in?
;;   /login         :conduit/login     the auth landing route
;;
;; The shell declares the viewer ONCE. No leaf re-declares it: `:parent` IS the
;; opt-in to the parent's branch-wide requirements (EP-0037 governing law 5).
;; ===========================================================================

(def ^:private app-frame-id :conduit/app)

(def ^:private pushed    (atom []))
(def ^:private replaced  (atom []))
(def ^:private scrolled  (atom []))
(def ^:private page-views (atom []))

(defn- register-resources! []
  ;; `:params-schema` is required on every resource — it validates and
  ;; canonicalizes the params that ARE the resource identity (Spec 016
  ;; §Resource registration spec). A param-free read declares `[:map]`.
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
                   (fn [_params _ctx] {:request {:method :get :url "/api/user/settings"}})))

(defn- register-routes! []
  ;; The shell is an ordinary route that also happens to be a `:parent`. It
  ;; acquires no outlet, component, or view lifecycle (governing law 5) — only
  ;; its `:resources` compose into its descendants' plans.
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
                 ;; ordinary activation work — analytics. Fire-and-forget: it
                 ;; never moves route readiness, and it runs only after the
                 ;; effective plan formed.
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
  (rf/reg-route :conduit/login {} "/login"))

(defn- register-subs-and-events! []
  ;; The guard is an ordinary route-owned subscription over the resolved
  ;; target. It returns a BOOLEAN — entry is terminal.
  (rf/reg-sub :conduit/signed-in?
              (fn [db _] (boolean (get-in db [:conduit/session :signed-in?]))))
  (rf/reg-event :conduit/sign-in
                (fn [{:keys [db]} _] {:db (assoc-in db [:conduit/session :signed-in?] true)}))
  (rf/reg-event :conduit/page-viewed
                (fn [_ _] (swap! page-views conj :view) {})))

(defn- register-auth-arm!
  "Register the application's `:rf.route/entry-denied` arm through the PUBLIC
  `rf/reg-event` — the spelling every guide, example and skill teaches.

    :none    register nothing. The framework's shipped no-op default handles
             the denial: a hard client deny, and on a server frame the `403`
             floor stands.
    :client  the ratified fresh-return recipe — stash the denied
             `RouteDestination`, replace-navigate to login, and after sign-in
             dispatch a FRESH navigate with the stored destination.
    :server  the server entry point's arm — emit Spec 011's canonical
             `:rf.server/redirect`, whose redirect precedence supersedes the
             default `403`.

  A real app's client and server entry points register the arm appropriate to
  their host; splitting it here is that same split, not a test convenience.

  `:sensitive` is re-declared on the replacement, because trace classification
  rides the REGISTRATION and not the id: the framework's default declares
  `[[:requested-url] [:destination] [:target]]` so the denial's URL carriers
  are redacted in trace copies, and an app handler that drops the metadata
  drops the redaction with it (Spec 012 §Replaceable framework defaults)."
  [arm]
  (let [sensitive {:sensitive [[:requested-url] [:destination] [:target]]}]
    (case arm
      :none nil
      :client (rf/reg-event :rf.route/entry-denied sensitive
                            (fn [{:keys [db]} [_ {:keys [destination]}]]
                              {:db (assoc-in db [:conduit/session :return-to] destination)
                               :fx [[:dispatch [:rf.route/navigate
                                                {:to :conduit/login :replace? true}]]]}))
      :server (rf/reg-event :rf.route/entry-denied sensitive
                            (fn [_ _] {:fx [[:rf.server/redirect {:location "/login"}]]})))))

(defn- stub-host-fx! []
  (reset! pushed [])
  (reset! replaced [])
  (reset! scrolled [])
  (reset! page-views [])
  (fx/reg-fx :rf.http/managed (fn [_ctx _args] nil))
  (fx/reg-fx :rf.nav/push-url    {:platforms #{:server :client}}
             (fn [_ url] (swap! pushed conj url) nil))
  (fx/reg-fx :rf.nav/replace-url {:platforms #{:server :client}}
             (fn [_ url] (swap! replaced conj url) nil))
  (fx/reg-fx :rf.nav/scroll         {:platforms #{:server :client}}
             (fn [_ arg] (swap! scrolled conj arg) nil))
  (fx/reg-fx :rf.nav/capture-scroll {:platforms #{:server :client}} (fn [_ _] nil)))

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
  "Build one of the app's frames and return its id. Called AFTER
  `register-app!` — and that ORDER is the point. An application's namespaces
  load and register, and THEN its frames are built. Sealing last is what
  exercises rf2-0r6q4: before PR #6932 an app that registered
  `:rf.route/entry-denied` through the public door made the very next
  `rf/make-frame` throw `:rf.error/image-duplicate-id`, because the default
  image selected BOTH the app's provenanced registration and the framework's
  own no-provenance default."
  ([] (seal-frame! {}))
  ([{:keys [frame-id preset url-bound?]}]
   (let [id (or frame-id app-frame-id)]
     ;; NOT url-bound by default, deliberately. A url-bound frame is wired to
     ;; the HOST address bar: on a host that has one (the CLJS lane) claiming
     ;; ownership syncs the frame to the current location, and destroying the
     ;; owner transfers ownership and re-syncs the successor. Both are correct,
     ;; and both are proven where they belong (`routing_url_bound_test`,
     ;; `routing_history_cljs_test`). Letting them fire here would make this
     ;; proof's activations host-dependent and prove nothing about planning.
     ;; The exercise app drives its URL-driven doors explicitly instead.
     (rf/make-frame (cond-> {:id id :doc "conduit — the EP-0037 R6 exercise app"}
                      preset     (assoc :preset preset)
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
  "The exercise app's article link, exactly as its view body authors it: an
  address, plus one link-behaviour key, plus ordinary DOM attributes on the
  same flat map. `caller-intent` is an application-supplied `:on-mouse-enter`
  — the framework's intent handler must COMPOSE with it, not replace it."
  ([slug] (article-link-props slug nil))
  ([slug caller-intent]
   (cond-> {:to         :conduit/article
            :params     {:slug slug}
            :prefetch   :intent
            :class      "preview-link"
            :aria-label (str "Read " slug)}
     caller-intent (assoc :on-mouse-enter caller-intent))))

(defn- render-shell
  "What the exercise app's shell view body DOES at render time, and nothing
  more: read the route projection through subscriptions, and project one nav
  link's anchor. Returns the projection so a test can assert on it.

  On the JVM this is the SSR render fn (`route-link-render-ssr`, the registered
  `:route/link` JVM handler); on CLJS it is the client render fn
  (`route-link-render`, what `rf/route-link` is registered as). Both are pure
  projections of the address — computing an href is not travelling to it."
  [frame-id slug]
  (rf/with-frame frame-id
    {:route  @(rf/subscribe [:rf/route])
     :chain  @(rf/subscribe [:rf.route/chain])
     :anchor #?(:clj  (routing/route-link-render-ssr (article-link-props slug) "Read it")
                :cljs (routing/route-link-render     (article-link-props slug) "Read it"))}))

;; ===========================================================================
;; helpers over the app's observable state
;; ===========================================================================

(defn- rdb     [frame-id] (:rf.db/runtime (rf/frame-state-value frame-id)))
(defn- slice   [frame-id] (get-in (rdb frame-id) [:rf.runtime/routing :current]))
(defn- entries [frame-id] (get-in (rdb frame-id) (state/entries-path)))
(defn- entry   [frame-id k] (get-in (rdb frame-id) (state/entry-path k)))
(defn- pending [frame-id] (get-in (rdb frame-id) [:rf.runtime/routing :pending-navigation]))

(def ^:private viewer-key   (state/scoped-resource-key* :rf.scope/global :conduit/viewer {}))
(def ^:private feed-key     (state/scoped-resource-key* :rf.scope/global :conduit/feed {:page 1}))
(def ^:private settings-key (state/scoped-resource-key* :rf.scope/global :conduit/settings {}))
(defn- article-key [slug]
  (state/scoped-resource-key* :rf.scope/global :conduit/article {:slug slug}))

(defn- route-owners
  "The `[:route …]` owners currently attached to an entry."
  [e]
  (filterv (fn [o] (and (vector? o) (= :route (first o)))) (:active-owners e)))

(defn- identity-set
  "The set of scoped resource identities this frame's entries hold — the
  observable projection of the effective plan."
  [frame-id]
  (into #{} (map (fn [[_ e]] (:resource/key e))) (entries frame-id)))

(defn- settle! [frame-id k data]
  (let [e (entry frame-id k)]
    (rf/dispatch-sync [:rf.resource.internal/succeeded
                       {:resource/key k
                        :work/id      (:current-work e)
                        :generation   (:generation e)
                        :data         data}]
                      {:frame frame-id})))

(def ^:private listener-seq (atom 0))

(defn- capture-traces
  "Run `f` with a trace listener installed; return the collected trace events."
  [f]
  (let [seen (atom [])
        id   (keyword "ep0037-r6" (str "listener-" (swap! listener-seq inc)))]
    (rf/register-listener! :trace id (fn [ev] (swap! seen conj ev)))
    (try (f) (finally (rf/unregister-listener! :trace id)))
    @seen))

(defn- event-ids [traces]
  (into #{} (comp (filter #(= :rf.event/run-start (:operation %)))
                  (map #(-> % :tags :rf.trace/event-id)))
        traces))

;; ===========================================================================
;; 1 + 2. The parent shell reads through the BRANCH plan; the leaf reads too
;;
;;     EP conformance row 4 (branch composition) and row 5 (partial
;;     activation), exercised through the app rather than the planner.
;; ===========================================================================

(deftest shell-and-leaf-read-through-one-branch-plan
  (let [app (boot-app!)]
    (rf/dispatch-sync [:rf.route/navigate {:to :conduit/article :params {:slug "routing-as-data"}}]
                      {:frame app})
    (let [akey  (article-key "routing-as-data")
          token (:nav-token (slice app))]
      (testing "the shell's branch-wide viewer read is planned from the LEAF activation"
        (is (some? (entry app viewer-key))
            "the parent contributed :conduit/viewer without the leaf re-declaring it"))
      (testing "the leaf's own read is planned too"
        (is (some? (entry app akey))))
      (testing "no duplicated entries — one entry per identity, and only the two
                the branch actually declares"
        (is (= #{viewer-key akey} (identity-set app))
            "exactly viewer + article; the shell read is not duplicated per leaf")
        (is (= 1 (count (route-owners (entry app viewer-key))))
            "one route owner on the shared shell read, not one per contributor"))
      (testing "both reads are owned by THIS activation's plan"
        (is (= [[:route :conduit/article token]] (route-owners (entry app viewer-key))))
        (is (= [[:route :conduit/article token]] (route-owners (entry app akey)))))
      (testing "the route is :loading while the blocking branch first-loads, and
                :idle once every blocking requirement has data"
        (is (= :loading (:transition (slice app))))
        (settle! app viewer-key {:username "ada"})
        (settle! app akey {:title "Routing as data"})
        (is (= :idle (:transition (slice app))))
        (is (nil? (:error (slice app)))))
      (testing "activation work ran, exactly once, and never touched readiness"
        (is (= 1 (count @page-views)))))))

(deftest sibling-leaf-navigation-keeps-the-shell-read
  (testing "moving between leaves of the same shell does not turn the parent
            requirement into a new page load (the partial-revalidation law)"
    (let [app (boot-app!)]
      (rf/dispatch-sync [:rf.route/navigate {:to :conduit/article :params {:slug "a"}}]
                        {:frame app})
      (settle! app viewer-key {:username "ada"})
      (settle! app (article-key "a") {:title "A"})
      (let [gen-before (:generation (entry app viewer-key))]
        (rf/dispatch-sync [:rf.route/navigate {:to :conduit/feed}] {:frame app})
        (is (= gen-before (:generation (entry app viewer-key)))
            "the shell read was KEPT — same generation, no revalidation")
        (is (state/has-data? (entry app viewer-key))
            "…and keeps its data across the sibling move")
        (is (some? (entry app feed-key))
            "the newly added leaf identity was ensured")
        (is (empty? (route-owners (entry app (article-key "a"))))
            "the removed leaf's route owner was released")
        (is (= 1 (count (route-owners (entry app viewer-key))))
            "the kept shell read carries exactly one live route owner — the new
             plan's; attach-before-release left no duplicate and no gap")))))

;; ===========================================================================
;; Door parity over the EFFECTIVE plan (row 2)
;;
;;     The per-slice suites prove cause-parametric targets for a resource-free
;;     route (routing_plan_seam_test) and branch composition through ONE door
;;     (resources_route_cljs_test). Neither proves that FIVE doors agree on the
;;     branch AND on the resource identity set — the integration claim.
;; ===========================================================================

(defn- plan-footprint
  "The observable plan footprint of a frame that has just activated: the
  committed target facts, the URL those facts derive to, the parent-to-leaf
  chain, and the effective resource identity set. History and scroll EFFECTS
  are deliberately absent — row 2 allows exactly those to differ by cause."
  [f]
  (let [s (slice f)]
    {:target     (select-keys s [:route-id :params :query :fragment])
     :url        (routing/route-url (-> (select-keys s [:params :query :fragment])
                                   (assoc :to (:route-id s))))
     :chain      (rf/with-frame f @(rf/subscribe [:rf.route/chain]))
     :identities (identity-set f)}))

(defn- door-footprint
  "Activate `slug` through one door on its own freshly sealed frame, and return
  that frame's plan footprint."
  [frame-id door slug]
  (let [f    (seal-frame! {:frame-id frame-id})
        url  (str "/article/" slug)
        addr {:to :conduit/article :params {:slug slug}}]
    (case door
      :navigate (rf/dispatch-sync [:rf.route/navigate addr] {:frame f})
      :raw-url  (rf/dispatch-sync [:rf.route/navigate {:url url}] {:frame f})
      :link     (rf/dispatch-sync [:rf.route/url-requested (assoc addr :url url)] {:frame f})
      :url      (rf/dispatch-sync [:rf.route/handle-url-change url] {:frame f}))
    (plan-footprint f)))

(deftest every-door-plans-the-same-branch-and-the-same-reads
  (register-app!)
  (let [slug     "door-parity"
        akey     (article-key slug)
        expected {:target     {:route-id :conduit/article
                               :params   {:slug slug}
                               :query    {}
                               :fragment nil}
                  :url        (str "/article/" slug)
                  :chain      [:conduit/shell :conduit/article]
                  :identities #{viewer-key akey}}
        client   (into {} (map (fn [door]
                                 [door (door-footprint (keyword "conduit" (name door))
                                                       door slug)]))
                       [:navigate :raw-url :link :url])
        server   (let [srv (seal-frame! {:frame-id :conduit/ssr-door :preset :ssr-server})]
                   (rf/dispatch-sync [:rf.route/handle-url-change (str "/article/" slug)]
                                     {:frame srv})
                   (plan-footprint srv))]
    (doseq [[door footprint] (assoc client :ssr server)]
      (testing (str "the " (name door) " door resolves the same target, the same "
                    "parent-to-leaf branch, and the same effective reads")
        (is (= expected footprint) (str "door " door " diverged"))))))

;; ===========================================================================
;; 3. Intent warmup on a REAL link
;;
;;     EP conformance row 7 (prefetch isolation) + row 3's intent arm, driven
;;     through the anchor the app's view body actually renders. On CLJS this is
;;     the FIRST coverage of `rf/route-link`'s composed intent handler.
;; ===========================================================================

(deftest a-real-link-warms-the-whole-branch-on-intent
  (let [app  (boot-app!)
        slug "warm-me"
        akey (article-key slug)]
    (rf/dispatch-sync [:rf.route/navigate {:to :conduit/feed}] {:frame app})
    (settle! app viewer-key {:username "ada"})
    (settle! app feed-key [{:slug slug}])
    (reset! pushed []) (reset! replaced []) (reset! scrolled []) (reset! page-views [])
    (let [before-token (:nav-token (slice app))
          before-slice (slice app)
          viewer-gen   (:generation (entry app viewer-key))
          props        (article-link-props slug)
          anchor       (:anchor (render-shell app slug))
          attrs        (second anchor)]
      (testing "the rendered anchor is an href projection; :prefetch is a link
                behaviour key and never reaches the DOM"
        (is (= "/article/warm-me" (:href attrs)))
        (is (not (contains? attrs :prefetch)))
        (is (= "preview-link" (:class attrs)) "ordinary DOM props pass through"))

      (is (= [:rf.route/prefetch {:to :conduit/article :params {:slug slug}}]
             (link/prefetch-payload props))
          "the link's intent payload is the address ONLY — no policy, no fragment")
      #?(:cljs (is (fn? (:on-mouse-enter attrs))
                   "the real anchor carries the composed intent handler; that
                    handler's own behaviour is pinned by
                    `the-real-anchor-intent-handler-composes-and-dispatches`"))

      ;; Drive warmup through the payload the anchor's handler dispatches. The
      ;; handler itself enqueues ASYNCHRONOUSLY (it is a DOM event handler), so
      ;; the behavioural arm dispatches that exact payload synchronously — same
      ;; event, same frame, observable in one turn on both hosts.
      (let [traces (capture-traces
                     #(rf/dispatch-sync (link/prefetch-payload props) {:frame app}))]
        (testing "warm mode ran the FULL effective branch plan, ownerlessly"
          (is (some? (entry app viewer-key)) "the shell requirement is in the warm plan")
          (is (some? (entry app akey))       "…and so is the leaf requirement")
          (is (empty? (route-owners (entry app akey)))
              "the warmed leaf identity has NO route owner — it stays GC-eligible"))
        (testing "prefetch is not activation: no route state, token, guard,
                  activation work, URL or scroll effect"
          (is (= before-slice (slice app)) "the route slice is byte-identical")
          (is (= before-token (:nav-token (slice app))) "no nav-token was allocated")
          (is (nil? (pending app)) "no pending navigation")
          (is (empty? @pushed) "no history push")
          (is (empty? @replaced) "no history replace")
          (is (empty? @scrolled) "no scroll effect")
          (is (empty? @page-views) "the destination's :on-match did NOT run")
          (is (not-any? #(#{:rf.route/navigate :rf.route/entry-denied
                            :rf.route/navigation-blocked}
                          (-> % :tags :rf.trace/event-id))
                        traces)
              "no navigation or guard event ran during warmup"))
        (testing "the already-fresh shell requirement was not refetched"
          (is (= viewer-gen (:generation (entry app viewer-key))))))

      (testing "activation REUSES the warm work and attaches the real owner"
        (rf/dispatch-sync [:rf.route/navigate {:to :conduit/article :params {:slug slug}}]
                          {:frame app})
        (let [token (:nav-token (slice app))
              e     (entry app akey)]
          (is (= [[:route :conduit/article token]] (route-owners e))
              "the activation attached its owner to the warmed identity")
          (is (= 1 (:attempt e))
              "…and joined the warm work rather than starting a second attempt")
          (is (= 1 (count @page-views))
              "the activation ran :on-match once — the prefetch had not"))))))

#?(:cljs
   (deftest the-real-anchor-intent-handler-composes-and-dispatches
     (testing "row 7's `BOTH link surfaces` clause for `rf/route-link`. The
               compiled `v/route-link` and the Freehand descriptor have real-DOM
               intent tests; `rf/route-link`'s own composed handler had none. The
               handler must run the caller's `:on-mouse-enter` FIRST and then
               enqueue exactly the prefetch payload, stamped `:source :router`,
               to the frame that RENDERED the link — not an ambient frame
               resolved at event time."
       (let [app        (boot-app!)
             other      (seal-frame! {:frame-id :conduit/other})
             slug       "composed"
             caller-ran (atom 0)
             props      (article-link-props slug (fn [_e] (swap! caller-ran inc)))
             attrs      (second (rf/with-frame app
                                  (routing/route-link-render props "Read it")))]
         (is (fn? (:on-mouse-enter attrs)))
         ;; render scope has unwound by the time a real pointer arrives, and a
         ;; DIFFERENT frame is ambient — exactly the rf2-o3nam4 hazard.
         (let [traces (capture-traces
                        #(rf/with-frame other ((:on-mouse-enter attrs) #js {})))
               row    (first (filter #(= :rf.event/dispatched (:operation %)) traces))]
           (is (= 1 @caller-ran)
               "the caller's own intent handler ran — composed, not replaced")
           (is (some? row) "the handler enqueued exactly one dispatch")
           (is (= [:rf.route/prefetch {:to :conduit/article :params {:slug slug}}]
                  (-> row :tags :rf.event/v))
               "…and it is the address-only prefetch payload")
           (is (= app (-> row :tags :frame))
               "…targeting the RENDER-time frame, not the ambient one")
           ;; the trace projection lifts `:source` out of `:tags` onto the
           ;; event row itself, exactly as the ui DOM intent test reads it.
           (is (= :router (:source row))
               "…attributed to the routing substrate"))))))

;; ===========================================================================
;; 4. Auth denial + fresh return, through the PUBLIC door
;;
;;     EP conformance row 8 (guard parity). The registration order is the
;;     regression rf2-0r6q4 / PR #6932 fixed.
;; ===========================================================================

(deftest the-app-seals-a-frame-after-registering-the-public-denial-handler
  (testing "rf2-0r6q4 — an app that registers :rf.route/entry-denied through the
            PUBLIC rf/reg-event still assembles a frame. Before PR #6932 this
            boot order threw :rf.error/image-duplicate-id with colliding
            coordinates [{:ns nil} {:ns \"<app ns>\"}]."
    (let [app (boot-app! {:auth-arm :client})]
      (is (some? app) "the app frame sealed cleanly after the app registration")
      (is (= 1 (count (filter #(= :rf.route/entry-denied %)
                              (keys (registrar/registrations :event)))))
          "one :event registration for the id — the app's replaced the default")
      (is (some? (seal-frame! {:frame-id :conduit/second-frame}))
          "…and a second frame sealed later assembles too"))))

(deftest a-denial-handler-registered-after-the-frame-was-sealed-still-fires
  (testing "the complementary order — seal first, register second. `docs/routing/
            testing.md` writes its entry-denied spy this way (make-frame, then
            reg-event), so the recipe every reader copies must work: a re-eval'd
            registration resolves a fresh sealed generation and swaps it into
            the already-live frame (docs/core/images.md §re-eval)."
    (register-app! :none)
    (let [app (seal-frame! {:frame-id :conduit/late-arm})
          seen (atom [])]
      (rf/dispatch-sync [:rf.route/navigate {:to :conduit/feed}] {:frame app})
      (rf/reg-event :rf.route/entry-denied
                    {:sensitive [[:requested-url] [:destination] [:target]]}
                    (fn [_ [_ d]] (swap! seen conj d) {}))
      (rf/dispatch-sync [:rf.route/navigate {:to :conduit/settings}] {:frame app})
      (is (= 1 (count @seen))
          "the late registration received the denial on the already-sealed frame")
      (is (= {:to :conduit/settings} (:destination (first @seen))))
      (is (= :conduit/feed (:route-id (slice app))) "and the deny still held"))))

(deftest denial-redirects-to-login-and-a-fresh-navigate-returns
  (let [app (boot-app! {:auth-arm :client})]
    (rf/dispatch-sync [:rf.route/navigate {:to :conduit/feed}] {:frame app})
    (settle! app viewer-key {:username "ada"})
    (settle! app feed-key [])
    (testing "a signed-out visit to the guarded tab is DENIED, terminally"
      (rf/dispatch-sync [:rf.route/navigate {:to :conduit/settings}] {:frame app})
      (is (= :conduit/login (:route-id (slice app)))
          "the app's denial handler replace-navigated to login")
      (is (nil? (pending app))
          "a denial creates NO pending navigation — entry is terminal, not paused")
      (is (nil? (entry app settings-key))
          "no protected activation work ran — the guarded read was never ensured")
      (is (= {:to :conduit/settings}
             (get-in (rf/app-db-value app) [:conduit/session :return-to]))
          "the denied destination was stashed as a replayable RouteDestination"))
    (testing "after sign-in a FRESH navigate with the stored destination returns"
      (rf/dispatch-sync [:conduit/sign-in] {:frame app})
      (rf/dispatch-sync [:rf.route/navigate
                         (get-in (rf/app-db-value app) [:conduit/session :return-to])]
                        {:frame app})
      (is (= :conduit/settings (:route-id (slice app)))
          "the guard re-evaluated on the new attempt and allowed")
      (is (some? (entry app settings-key))
          "the guarded leaf read is now planned")
      (is (some? (entry app viewer-key))
          "…alongside the shell read the branch plan still contributes"))))

(deftest denial-with-no-application-arm-is-a-hard-client-deny
  (testing "the framework's shipped no-op default keeps a denial SAFE for an app
            that registers no arm: exactly one denial trace, no
            :rf.error/no-such-handler, and the current route stands"
    (let [app (boot-app! {:auth-arm :none})]
      (rf/dispatch-sync [:rf.route/navigate {:to :conduit/feed}] {:frame app})
      (let [traces (capture-traces
                     #(rf/dispatch-sync [:rf.route/navigate {:to :conduit/settings}]
                                        {:frame app}))]
        (is (= 1 (count (filter #(= :rf.route/entry-denied (:operation %)) traces))))
        (is (not-any? #(= :rf.error/no-such-handler (:operation %)) traces))
        (is (= :conduit/feed (:route-id (slice app))) "hard deny — the app stayed put")
        (is (nil? (pending app)))))))

;; ===========================================================================
;; 5. SSR — the 403 floor, redirect supersession, and hydration REUSE
;;
;;     EP conformance row 8's SSR arm + row 6's hydration arm, through the app.
;; ===========================================================================

(deftest ssr-hard-deny-stamps-the-403-floor
  (testing "on the app's SERVER frame, a guarded deep link with no application
            arm stamps the default 403 and commits nothing"
    (let [srv (boot-app! {:auth-arm :none :frame-id :conduit/server :preset :ssr-server})]
      (rf/dispatch-sync [:rf.route/handle-url-change "/settings"] {:frame srv})
      (is (= 403 (:status (ssr/get-response srv))))
      (is (nil? (slice srv)) "no route committed for the denied target")
      (is (nil? (entry srv settings-key))
          "and no resource or hydration data for it was produced"))))

(deftest ssr-application-redirect-supersedes-the-403
  (testing "the server entry point's arm emits Spec 011's canonical
            :rf.server/redirect, whose redirect precedence replaces the floor"
    (let [srv (boot-app! {:auth-arm :server :frame-id :conduit/server :preset :ssr-server})]
      (rf/dispatch-sync [:rf.route/handle-url-change "/settings"] {:frame srv})
      (let [resp (ssr/get-response srv)]
        (is (= "/login" (get-in resp [:redirect :location])))
        (is (= 302 (:status resp)) "the app redirect superseded the default 403")))))

(deftest ssr-hydration-reuses-the-servers-branch-reads
  (testing "the server activates through the same branch plan, the client
            hydrates it, and the fresh identities are REUSED — the client does
            not duplicate an SSR ensure merely because the branch was rebuilt"
    (let [srv  (boot-app! {:frame-id :conduit/server :preset :ssr-server})
          slug "hydrate-me"
          akey (article-key slug)]
      (rf/dispatch-sync [:rf.route/handle-url-change (str "/article/" slug)] {:frame srv})
      (is (= :conduit/article (:route-id (slice srv))))
      (is (= :loading (:transition (slice srv)))
          "the server waits on the branch's blocking requirements")
      (settle! srv viewer-key {:username "ada"})
      (settle! srv akey {:title "Hydrate me"})
      (is (= :idle (:transition (slice srv)))
          "…and renders once every blocking requirement of the plan has data")

      (let [projected (res-ssr/project-resources-runtime-db (rdb srv) srv)
            hydrated  (res-ssr/hydrate-runtime-db projected)
            plan      (res-ssr/hydrate-refetch-plan hydrated)
            planned   (into #{} (map :resource/key) plan)]
        (testing "both branch reads crossed the wire"
          (is (some? (get-in hydrated [state/resources-key :entries (state/key-id viewer-key)])))
          (is (some? (get-in hydrated [state/resources-key :entries (state/key-id akey)]))))
        (testing "and neither is refetched on the client — hydration REUSE"
          (is (not (contains? planned viewer-key))
              "the shell read was fresh with data; no double-fetch")
          (is (not (contains? planned akey))
              "the leaf read was fresh with data; no double-fetch")
          (is (empty? plan) "the whole hydrated branch is reused as-is"))))))

;; ===========================================================================
;; 6. No render-caused work
;;
;;     EP conformance row 3 (passive render) against the RUNNING app. The
;;     host-agnostic fixture pins the pure-data substrate of a render; this
;;     invokes the app's actual render body against live frame state.
;; ===========================================================================

(deftest rendering-the-app-shell-causes-nothing
  (let [app  (boot-app!)
        slug "passive"]
    (rf/dispatch-sync [:rf.route/navigate {:to :conduit/feed}] {:frame app})
    (settle! app viewer-key {:username "ada"})
    (settle! app feed-key [{:slug slug}])
    (reset! pushed []) (reset! replaced []) (reset! scrolled []) (reset! page-views [])
    (let [before-slice   (slice app)
          before-entries (entries app)
          traces (capture-traces #(dotimes [_ 3] (render-shell app slug)))
          shell  (render-shell app slug)]
      (testing "rendering READ the projections it is supposed to read"
        (is (= :conduit/feed (:route-id (:route shell))))
        (is (= [:conduit/shell :conduit/feed] (:chain shell))
            "the shell view composes the route chain from state, not an outlet")
        (is (= "/article/passive" (:href (second (:anchor shell))))))
      (testing "…and CAUSED nothing"
        (is (empty? (event-ids traces))
            "no event ran during render — no navigate, no ensure, no prefetch,
             no application event")
        (is (= before-slice (slice app)) "the route slice is untouched")
        (is (= before-entries (entries app))
            "no resource entry was created, refetched, or re-owned by rendering")
        (is (empty? @pushed) "no history entry")
        (is (empty? @replaced))
        (is (empty? @scrolled))
        (is (empty? @page-views))))))

;; ===========================================================================
;; A planning failure commits a failed activation and runs NO activation work
;;
;;     EP conformance row 6's last clause. `:on-match` "is not dispatched when
;;     planning fails" had no executable arm in any per-slice suite.
;; ===========================================================================

(deftest a-planning-failure-commits-the-target-and-suppresses-on-match
  (register-app!)
  ;; A route whose params resolver fails closed. Not app surface — the failure
  ;; arm of the app's own planner.
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
    (testing "the failed target COMMITS, so the error is addressable"
      (is (= :conduit/broken (:route-id (slice app))))
      (is (= "/broken" (routing/route-url {:to (:route-id (slice app))}))
          "the committed facts still derive the destination URL"))
    (testing "readiness projects :error with the structured planning error"
      (is (= :error (:transition (slice app))))
      (is (= :rf.error/resource-route-plan (:rf.error/id (:error (slice app))))))
    (testing "no partial plan executed, and NO activation work ran"
      (is (empty? (identity-set app))
          "not one ensure from the invalid plan was dispatched")
      (is (empty? @page-views)
          ":on-match is suppressed on a committed planning failure"))))

;; ===========================================================================
;; 10. Frame isolation and teardown — integrated
;;
;;     EP conformance row 10. The per-subsystem destroy tests each release one
;;     cache; nothing proved that ONE frame holding a branch plan, route
;;     owners, warm prefetch work AND a pending leave releases all of it.
;; ===========================================================================

(deftest two-frames-of-the-app-share-no-plan-and-no-warm-work
  (register-app!)
  (let [a    (seal-frame! {:frame-id :conduit/app-a})
        b    (seal-frame! {:frame-id :conduit/app-b})
        slug "isolated"
        akey (article-key slug)]
    (rf/dispatch-sync [:rf.route/navigate {:to :conduit/feed}] {:frame a})
    (testing "frame A activated; frame B has no route and no plan"
      (is (= :conduit/feed (:route-id (slice a))))
      (is (nil? (slice b)))
      (is (empty? (entries b)) "B ensured nothing — A's branch plan is A's"))
    (testing "a prefetch in A warms A only"
      (rf/dispatch-sync [:rf.route/prefetch {:to :conduit/article :params {:slug slug}}]
                        {:frame a})
      (is (some? (entry a akey)) "A warmed the destination")
      (is (nil? (entry b akey))
          "B was not warmed — the carried-frame invariant holds for cache entries too"))
    (testing "B activating the same address builds its OWN plan and owners"
      (rf/dispatch-sync [:rf.route/navigate {:to :conduit/article :params {:slug slug}}]
                        {:frame b})
      (is (= [[:route :conduit/article (:nav-token (slice b))]]
             (route-owners (entry b akey)))
          "B's entry carries B's own route owner")
      (is (empty? (route-owners (entry a akey)))
          "A's copy of the SAME identity is still the ownerless warm entry — the
           two frames' plans never touch each other's ownership")
      (is (= (:nav-token (slice a)) (:nav-token (slice b)))
          "each frame allocated its FIRST nav-token independently — the
           allocator is per-frame, not a shared global counter"))))

(deftest destroying-a-frame-releases-its-whole-routing-footprint
  (register-app!)
  ;; A leaveable route, so the frame also holds a pending-leave value at
  ;; destroy time — the one piece of route state that survives a blocked
  ;; navigation.
  (rf/reg-route :conduit/editor
                {:parent    :conduit/shell
                 :can-leave [:conduit/editor-clean?]}
                "/editor")
  (rf/reg-sub :conduit/editor-clean? (fn [_ _] false))
  (let [keep-alive (seal-frame! {:frame-id :conduit/keeper})
        doomed     (seal-frame! {:frame-id :conduit/doomed})
        slug       "teardown"
        akey       (article-key slug)]
    ;; the doomed frame accumulates: a branch plan + route owners, warm
    ;; prefetch work, and a pending leave.
    (rf/dispatch-sync [:rf.route/navigate {:to :conduit/editor}] {:frame doomed})
    (rf/dispatch-sync [:rf.route/prefetch {:to :conduit/article :params {:slug slug}}]
                      {:frame doomed})
    (rf/dispatch-sync [:rf.route/navigate {:to :conduit/feed}] {:frame doomed})
    (is (some? (pending doomed)) "the dirty editor blocked the leave")
    (is (some? (entry doomed akey)) "and the frame holds warm prefetch work")
    (is (seq (identity-set doomed)) "…and a live branch plan")
    ;; the keeper frame is doing its own work concurrently
    (rf/dispatch-sync [:rf.route/navigate {:to :conduit/feed}] {:frame keep-alive})
    (let [keeper-ids (identity-set keep-alive)]
      (rf/destroy-frame! doomed)
      (testing "the destroyed frame's whole routing footprint is gone"
        (is (nil? (rf/frame-state-value doomed))
            "no runtime-db survives — plans, owners, warm entries and the
             pending leave went with it"))
      (testing "and the surviving frame is untouched"
        (is (= :conduit/feed (:route-id (slice keep-alive))))
        (is (= keeper-ids (identity-set keep-alive))
            "the keeper's plan identities are exactly as they were")
        (is (nil? (pending keep-alive))
            "the destroyed frame's pending leave never leaked across")))))
