(ns re-frame.resources-route-cljs-test
  "Route ↔ resource integration (Spec 016 §Route integration), cross-host so the
  routing/resources seam behaves identically server- and client-side:

    - route entry ensures each `:resources` entry under owner
      `[:route route-id nav-token]` and cause `[:route-entry …]`;
    - a `:blocking?` resource holds the route transition `:loading` until it
      settles, and a blocking FIRST-load failure projects `:error`;
    - leave / supersession releases the prior owner and its blocking slot;
    - `:when`, `:after`, scope precedence and every planning failure fail
      closed on the route slice;
    - EP-0037 R1/R2: the one readiness projector, effective parent-chain plans,
      byte-exact identity membership and retained-entry adoption.

  Named `*-cljs-test.cljc` so it is discovered by BOTH the JVM runner
  (`.*-test$`) and the shadow-cljs `:node-test` build (`cljs-test$`). The
  managed-HTTP fx is stubbed (capturing no-op) so ensure's entry write + the
  reply-driven blocking drain are deterministic without a live fetch."
  (:require
   #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
      :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
   [re-frame.core :as rf]
   [re-frame.fx :as rf.fx]
   [re-frame.error :as rf.error]
   [re-frame.interop :as rf.interop]
   ;; load-bearing side-effecting requires: register the routing + resources
   ;; events / subs and resources' late-bound :routing/* integration hooks.
   [re-frame.resources]
   [re-frame.resources.route :as rf.resources.route]
   [re-frame.resources.ssr :as rf.resources.ssr]
   [re-frame.resources.state :as rf.resources.state]
   [re-frame.resources.work-ledger :as rf.resources.work-ledger]
   [re-frame.resources.test-support]
   [re-frame.routing :as rf.routing]
   [re-frame.schemas]
   [re-frame.http.managed]
   [re-frame.test-support :as rf.test-support]
   [re-frame.trace.tooling :as rf.trace.tooling]
   #?(:clj  [re-frame.substrate.plain-atom :as substrate]
      :cljs [re-frame.adapter.reagent :as substrate])))

;; ---- fixture --------------------------------------------------------------

(defn- init!
  "Per-test setup, after the shared reset fixture has cleared the resources
  host caches: re-register `:rf/default` as the URL-owning app frame, reset
  the routing counters, re-publish the late-bound routing integration, and
  stub the managed-HTTP + push-url fx."
  []
  (rf/make-frame {:id :rf/default :url-bound? true
                  :doc "Route-resource suite default app frame."})
  (rf.routing/reset-counters!)
  (rf.resources.route/install-routing-integration!)
  (rf.fx/reg-fx :rf.http/managed (fn [_ctx _args] nil))
  (rf.fx/reg-fx :rf.nav/push-url {:platforms #{:server :client}} (fn [_ _] nil))
  ;; A NAMED scope resolver over an app-db slot this suite never writes, so it
  ;; resolves nil and a call that supplies no `:scope` of its own fails closed.
  (rf/reg-resource-scope :t/caller-scope
    {:inputs {:scope [:db [:t/scope]]}}
    (fn [{:keys [scope]} _ctx] scope)))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter substrate/adapter
     :init-fn init!}))

;; ---- helpers --------------------------------------------------------------

(defn- rdb [] (:rf.db/runtime (rf/frame-state-value :rf/default)))

(defn- slice [] (get-in (rdb) [:rf.runtime/routing :current]))

(defn- entry [scoped-key] (get-in (rdb) (rf.resources.state/entry-path scoped-key)))

(defn- entries [] (get-in (rdb) (rf.resources.state/entries-path)))

(defn- blocking-slot
  "The live blocking slot for `nav-token`, projected to the SET of its scoped
  keys. The slot itself is the byte-keyed `{<key-id> <scoped-key>}` carrier,
  pinned directly by `r2-a-plan-holding-both-byte-distinct-twins-*`."
  [nav-token]
  (set (vals (get-in (rdb) (rf.resources.route/blocking-path nav-token)))))

(defn- blocking-map
  "The byte-keyed blocking / plan-identity carrier `{<key-id> <scoped-key>}`
  over `ks` — the shape both routing slots hold."
  [& ks]
  (into {} (map (juxt rf.resources.state/key-id identity)) ks))

(defn- article-spec [overrides]
  (merge {:scope         :rf.scope/global
          :params-schema [:map [:slug :string]]
          :tags          (fn [{:keys [slug]} _data] #{[:article slug]})}
         overrides))

(def ^:private article-spec-request
  (fn [{:keys [slug]} _ctx]
    {:request {:method :get :url (str "/api/articles/" slug)}}))

(defn- slug-param [route] {:slug (get-in route [:params :slug])})

(defn- article-key [slug]
  (rf.resources.state/scoped-resource-key :rf.scope/global :article/by-slug {:slug slug}))

(defn- reg-article-route!
  "`:route/article` at /articles/:slug with ONE `:resources` entry — by default
  `:article/by-slug` keyed on the slug — merged with `entry-overrides`."
  [entry-overrides]
  (rf/reg-route :route/article
                {:params    [:map [:slug :string]]
                 :resources [(merge {:resource :article/by-slug :params slug-param}
                                    entry-overrides)]}
                "/articles/:slug"))

(defn- navigate-article! [slug]
  (rf/dispatch-sync [:rf.route/navigate {:to :route/article :params {:slug slug}}]))

(defn- settle-success! [scoped-key data]
  (let [e (entry scoped-key)]
    (rf/dispatch-sync [:rf.resource.internal/succeeded
                       {:resource/key scoped-key
                        :work/id      (:current-work e)
                        :generation   (:generation e)
                        :data         data}])))

(defn- settle-failure! [scoped-key error]
  (let [e (entry scoped-key)]
    (rf/dispatch-sync [:rf.resource.internal/failed
                       {:resource/key scoped-key
                        :work/id      (:current-work e)
                        :generation   (:generation e)
                        :error        error}])))

(defn- record-traces!
  "Run `body-fn` with a trace listener installed; return every trace event
  `pred` accepts, in capture order."
  [pred body-fn]
  (let [seen (atom [])
        k    ::route-trace-recorder]
    (rf.trace.tooling/register-listener!
      k (fn [ev] (when (pred ev) (swap! seen conj ev))))
    (try (body-fn) (finally (rf.trace.tooling/unregister-listener! k)))
    @seen))

(defn- record-error-traces! [body-fn]
  (record-traces! #(= :error (:op-type %)) body-fn))

(defn- errors-of [traces op]
  (filterv #(= op (:operation %)) traces))

(defn- route-plan-tags
  "The tags of the first `:rf.resource/route-plan` row `body-fn` emits."
  [body-fn]
  (:tags (first (record-traces! #(= :rf.resource/route-plan (:operation %)) body-fn))))

(defn- work-record-for [scoped-key]
  (rf.resources.work-ledger/get-record (rdb) (:current-work (entry scoped-key))))

(defn- plan-dispatches
  "The event vectors dispatched by a plan's fx, in fx order."
  [plan]
  (into [] (keep (fn [[fx-id ev]] (when (= :dispatch fx-id) ev))) (:fx plan)))

(defn- of-event [dispatches event-id]
  (filterv (fn [ev] (= event-id (first ev))) dispatches))

(defn- resources-of [dispatches event-id]
  (mapv #(:resource (second %)) (of-event dispatches event-id)))

(defn- ids [ks] (mapv rf.resources.state/key-id ks))

;; ===========================================================================
;; Route entry, blocking readiness and leave
;; ===========================================================================

(deftest route-entry-ensures-with-route-owner
  (rf/reg-resource :article/by-slug (article-spec {}) article-spec-request)
  (reg-article-route! {})
  (navigate-article! "intro")
  (let [nav-token (:nav-token (slice))
        e         (entry (article-key "intro"))]
    (is (= [:loading true] [(:status e) (contains? (:active-owners e) [:route :route/article nav-token])])
        "ensured on entry, owned by [:route route-id nav-token]")
    (is (= [[:route-entry :route/article nav-token]] (:causes (work-record-for (article-key "intro"))))
        "the load's work record carries the route-entry activation cause")))

(deftest blocking-resource-holds-route-transition-until-it-settles
  (rf/reg-resource :article/by-slug (article-spec {}) article-spec-request)
  (reg-article-route! {:blocking? true})
  (navigate-article! "intro")
  (let [nav-token (:nav-token (slice))
        k         (article-key "intro")]
    (is (= [:loading #{k}] [(:transition (slice)) (blocking-slot nav-token)])
        "the blocking key is the one requirement tracked under the nav-token")
    (settle-success! k {:title "Intro"})
    (is (= [:idle #{}] [(:transition (slice)) (blocking-slot nav-token)])
        "the route lands :idle on settle and the slot drains")))

(deftest non-blocking-resource-does-not-hold-the-transition
  (rf/reg-resource :comments/list (article-spec {}) article-spec-request)
  (reg-article-route! {:resource :comments/list :blocking? false})
  (navigate-article! "intro")
  (is (= [:idle :loading]
         [(:transition (slice))
          (:status (entry (rf.resources.state/scoped-resource-key :rf.scope/global :comments/list {:slug "intro"})))])
      "the route is :idle while the resource still fetches in the background"))

(deftest blocking-resource-already-fresh-settles-route-immediately
  ;; A fresh re-entry is a cache-hit with no fetch, so no reply will ever drain
  ;; the new blocking slot: the cache-hit itself must, or the route hangs.
  (rf/reg-resource :article/by-slug (article-spec {}) article-spec-request)
  (reg-article-route! {:blocking? true})
  (rf/reg-route :route/home {} "/")
  (let [k (article-key "intro")]
    (navigate-article! "intro")
    (settle-success! k {:title "Intro"})
    (rf/dispatch-sync [:rf.route/navigate {:to :route/home}])
    (navigate-article! "intro")
    (is (= [:loaded nil :idle #{}]
           [(:status (entry k)) (:current-work (entry k))
            (:transition (slice)) (blocking-slot (:nav-token (slice)))])
        "no refetch, no in-flight work, and the route lands :idle at once")))

(deftest route-leave-releases-prior-route-owner
  (rf/reg-resource :article/by-slug (article-spec {}) article-spec-request)
  (reg-article-route! {})
  (rf/reg-route :route/home {} "/")
  (navigate-article! "intro")
  (let [owner [:route :route/article (:nav-token (slice))]
        k     (article-key "intro")]
    (is (contains? (:active-owners (entry k)) owner))
    (rf/dispatch-sync [:rf.route/navigate {:to :route/home}])
    (is (not (contains? (:active-owners (entry k)) owner))
        "the prior route owner was released on leave")))

(deftest route-resupersede-same-key-blocking-transition-drains
  ;; Leaving marks the in-flight attempt :abort-requested while the entry still
  ;; points at it. An immediate re-entry of the SAME key must start a fresh
  ;; attempt rather than join doomed work, and drain on the FRESH reply.
  (rf/reg-resource :article/by-slug (article-spec {}) article-spec-request)
  (reg-article-route! {:blocking? true})
  (rf/reg-route :route/home {} "/")
  (let [k (article-key "intro")]
    (navigate-article! "intro")
    (let [wid1 (:current-work (entry k))
          gen1 (:generation (entry k))]
      (rf/dispatch-sync [:rf.route/navigate {:to :route/home}])
      (is (= :abort-requested (:status (rf.resources.work-ledger/get-record (rdb) wid1))))
      (navigate-article! "intro")
      (let [token-2 (:nav-token (slice))]
        (is (= [:loading (inc gen1) :running true true]
               [(:transition (slice)) (:generation (entry k)) (:status (work-record-for k))
                (contains? (:active-owners (entry k)) [:route :route/article token-2])
                (contains? (blocking-slot token-2) k)])
            "a fresh, live attempt owned and tracked by the re-entry token")
        (settle-success! k {:title "Intro"})
        (is (= [:idle #{}] [(:transition (slice)) (blocking-slot token-2)])
            "the fresh reply drains the slot — no hang on the aborted work")))))

(deftest when-false-gates-the-resource-out
  (rf/reg-resource :comments/list (article-spec {}) article-spec-request)
  (reg-article-route! {:resource :comments/list :when (fn [_route _ctx] false)})
  (navigate-article! "intro")
  (is (empty? (entries)) ":when false admits no resource (NOT sentinel nil params)"))

(deftest blocking-first-load-failure-emits-error-trace
  ;; The route slice carries the structured :error AND the same failure is on the
  ;; trace/error stream with the ResourceRouteBlockingTags shape.
  (rf/reg-resource :article/by-slug (article-spec {}) article-spec-request)
  (reg-article-route! {:blocking? true})
  (navigate-article! "intro")
  (let [nav-token (:nav-token (slice))
        evs       (errors-of (record-error-traces!
                               #(settle-failure! (article-key "intro") {:status 503 :message "upstream down"}))
                             :rf.error/resource-route-blocking)]
    (is (= [{:category    :rf.error/resource-route-blocking
             :resource-id :article/by-slug
             :nav-token   nav-token
             :error       {:status 503 :message "upstream down"}}]
           (mapv #(select-keys (:tags %) [:category :resource-id :nav-token :error]) evs))
        "exactly one blocking-failure trace, carrying the first-load envelope")
    (is (string? (:reason (:tags (first evs)))))
    (is (= [:error :rf.error/resource-route-blocking]
           [(:transition (slice)) (:rf.error/id (:error (slice)))]))))

(deftest superseded-blocking-slot-does-not-block-future-navigation
  ;; A blocking resource that NEVER settles would leave its slot forever if only
  ;; the reply-driven drain cleared it. Leaving releases the prior owner, which
  ;; must clear the stale slot so it cannot gate a later navigation.
  (rf/reg-resource :article/by-slug (article-spec {}) article-spec-request)
  (reg-article-route! {:blocking? true})
  (rf/reg-route :route/home {} "/")
  (navigate-article! "a")
  (let [token-1 (:nav-token (slice))]
    (rf/dispatch-sync [:rf.route/navigate {:to :route/home}])
    (let [token-2 (:nav-token (slice))]
      (is (= [true :idle #{} #{}]
             [(not= token-1 token-2) (:transition (slice))
              (blocking-slot token-1) (blocking-slot token-2)])))))

;; ===========================================================================
;; Fail-closed planning
;; ===========================================================================

(deftest route-planning-failures-fail-closed-on-the-route-slice
  ;; Each is a route PLANNING error — never a silent cache miss, a silent
  ;; fallback to the spec scope, an escape that crashes the commit, or a hang —
  ;; carrying its specific :recovery, on the slice AND the error stream, with
  ;; nothing ensured.
  (rf/reg-resource :secret/doc (article-spec {:scope {:from-db :t/caller-scope}}) article-spec-request)
  (rf/reg-resource :article/by-slug (article-spec {}) article-spec-request)
  (rf/reg-resource :a/res (article-spec {}) article-spec-request)
  (rf/reg-resource :b/res (article-spec {}) article-spec-request)
  (doseq [[n label resources recovery]
          [[1 "an unresolved {:from-db …} scope with no route resolver"
            [{:resource :secret/doc :params slug-param}] :fix-registration]
           [2 "a PRESENT :params resolver returning nil (conditional reads use :when)"
            [{:resource :article/by-slug :params (fn [_route] nil)}] :fix-params]
           [3 "the retired anonymous fn :scope tier, refused loud"
            [{:resource :secret/doc :params slug-param
              :scope    (fn [_route _ctx] [:rf.scope/session {:user 7}])}] :fix-scope]
           [4 "a PRESENT :scope of nil (absence is (contains? entry :scope))"
            [{:resource :secret/doc :params slug-param :scope nil}] :fix-scope]
           [5 "a misspelt reserved scope keyword"
            [{:resource :article/by-slug :params slug-param :scope :rf.scope/glabal}] :fix-scope]
           [6 "a :when predicate that throws"
            [{:resource :article/by-slug :params slug-param
              :when     (fn [_route _ctx] (throw (ex-info "boom" {})))}] :fix-when]
           [7 "an :after naming an id no entry declares"
            [{:resource :article/by-slug :id :comments :params slug-param :after #{:nope}}] :fix-after]
           [8 "a cyclic :after"
            [{:resource :a/res :id :a :params (fn [_] {:slug "a"}) :after #{:b}}
             {:resource :b/res :id :b :params (fn [_] {:slug "b"}) :after #{:a}}] :fix-after]]]
    (let [route-id (keyword "route" (str "failing-" n))]
      (rf/reg-route route-id {:params [:map [:slug :string]] :resources resources}
                    (str "/failing-" n "/:slug"))
      (let [traces (record-error-traces!
                     #(rf/dispatch-sync [:rf.route/navigate {:to route-id :params {:slug "x"}}]))
            err    (:error (slice))]
        (is (= [:rf.error/resource-route-plan recovery true false true true]
               [(:rf.error/id err) (:recovery err) (string? (:reason err))
                (contains? err :operation)
                (boolean (seq (errors-of traces :rf.error/resource-route-plan)))
                (empty? (entries))])
            label)
        (when (= 5 n)
          (is (= :rf.error/resource-invalid-scope (:rf.error/id (:cause err)))
              "the shared concrete-scope guard refused it — no new error id"))))))

(deftest route-entry-scope-precedence
  ;; A CONCRETE route-entry :scope is the route tier of the precedence ladder and
  ;; wins over the registration policy — here a policy that would fail closed on
  ;; its own; an entry with no :scope key at all inherits the policy.
  (rf/reg-resource :secret/doc (article-spec {:scope {:from-db :t/caller-scope}}) article-spec-request)
  (rf/reg-resource :article/by-slug (article-spec {}) article-spec-request)
  (doseq [[label route-entry expected-scope]
          [["the concrete route scope is threaded into the ensure"
            {:resource :secret/doc :params slug-param :scope [:rf.scope/session {:tenant "acme" :user 7}]}
            [:rf.scope/session {:tenant "acme" :user 7}]]
           ["an entry with no :scope key resolves the registration's global claim"
            {:resource :article/by-slug :params slug-param}
            :rf.scope/global]]]
    (let [plan   (rf.resources.route/route-resource-plan
                   {:id :route/x :params {:slug "x"} :resources [route-entry]}
                   {}
                   {:nav-token 1 :prev-id nil :prev-nav-token nil})
          ensure (first (of-event (plan-dispatches plan) :rf.resource/ensure))]
      (is (= [nil :rf.resource/ensure expected-scope]
             [(:plan-error plan) (first ensure) (:scope (second ensure))])
          label))))

(deftest nil-ctx-fails-closed
  ;; A nil ctx is a routing↔resources seam bug: it must throw, not proceed with
  ;; an empty ctx a session-scope resolver would read as nil. The throw has the
  ;; canonical thrown-error shape (rf.error/thrown-ex-info).
  (rf/reg-resource :secret/doc (article-spec {:scope {:from-db :t/caller-scope}}) article-spec-request)
  (let [thrown (try (rf.resources.route/route-resource-plan
                      {:id :route/secret :resources []} nil {:nav-token 1})
                    nil
                    (catch #?(:clj clojure.lang.ExceptionInfo
                              :cljs cljs.core/ExceptionInfo) e e))
        data   (ex-data thrown)
        msg    (ex-message thrown)]
    (is (= {:rf.error/id :rf.error/resource-route-plan
            :where       'rf/route-resource-plan
            :recovery    :fix-route-integration}
           (select-keys data [:rf.error/id :where :recovery])))
    (is (not (contains? data :operation)) "no dead :operation slot duplicating :rf.error/id")
    (is (rf.error/message-has-id-token? msg) "the message trails the [:rf.error/…] token")
    (is (not (rf.error/keyword-only-message? msg)) "the message is a human sentence")))

(deftest missing-nav-token-fails-closed
  ;; The nav-token IS the route owner identity; planning without one would mint
  ;; an unreleasable owner.
  (is (thrown? #?(:clj Throwable :cljs :default)
               (rf.resources.route/route-resource-plan
                 {:id :route/x :resources []}
                 {}
                 {:nav-token nil}))))

(deftest after-orders-multiple-deps-by-local-id
  ;; A dependent's ensure is dispatched AFTER every id it names (a 3-node chain
  ;; declared c, a, b).
  (let [order (atom [])
        reg!  (fn [id tag]
                (rf/reg-resource id (article-spec {})
                                 (fn [_ _] (swap! order conj tag) {:request {:method :get :url "/x"}})))]
    (reg! :a/res :a)
    (reg! :b/res :b)
    (reg! :c/res :c)
    (rf/reg-route :route/chain
                  {:resources [{:resource :c/res :id :c :params (fn [_] {:slug "c"}) :after #{:b}}
                               {:resource :a/res :id :a :params (fn [_] {:slug "a"})}
                               {:resource :b/res :id :b :params (fn [_] {:slug "b"}) :after #{:a}}]}
                  "/chain")
    (rf/dispatch-sync [:rf.route/navigate {:to :route/chain}])
    (is (= [nil [:a :b :c]] [(:error (slice)) @order]))))

;; ===========================================================================
;; EP-0037 R2 — effective parent-chain resource plans (Spec 016 §Effective
;; parent-chain resource plans)
;; ===========================================================================

(deftest r2-branch-composes-parent-to-leaf
  ;; A declared :parent composes the ancestor :resources with the leaf's,
  ;; parent-most first — the child never restates the shell read.
  (rf/reg-resource :shell/viewer (article-spec {}) article-spec-request)
  (rf/reg-resource :leaf/settings (article-spec {}) article-spec-request)
  (let [branch [{:route-id   :route/account
                 :route-meta {:resources [{:resource :shell/viewer :params (fn [_] {:slug "v"}) :blocking? true}]}}
                {:route-id   :route/account.settings
                 :route-meta {:resources [{:resource :leaf/settings :params (fn [_] {:slug "s"}) :blocking? true}]}}]
        plan (rf.resources.route/route-resource-plan
               {:id :route/account.settings :params {} :query {}} {} {:nav-token 1 :branch branch})]
    (is (= [nil [:shell/viewer :leaf/settings] 2]
           [(:plan-error plan) (resources-of (plan-dispatches plan) :rf.resource/ensure) (count (:blocking plan))]))))

(deftest r2-identity-dedupe-and-redundant-child-advisory
  ;; Parent and child declare the SAME identity: ONE ensure at the earliest
  ;; (parent) position, blocking? OR'd across contributors, and the redundant
  ;; child copy surfaces as an advisory.
  (rf/reg-resource :shell/banner (article-spec {}) article-spec-request)
  (rf/reg-resource :leaf/list (article-spec {}) article-spec-request)
  (let [banner     {:resource :shell/banner :params (fn [_] {:slug "u"})}
        branch     [{:route-id   :route/profile
                     :route-meta {:resources [(assoc banner :blocking? true)]}}
                    {:route-id   :route/profile.favorites
                     :route-meta {:resources [(assoc banner :blocking? false)
                                              {:resource :leaf/list :params (fn [_] {:slug "f"})}]}}]
        plan       (rf.resources.route/route-resource-plan
                     {:id :route/profile.favorites :params {} :query {}} {} {:nav-token 1 :branch branch})
        banner-key (rf.resources.state/scoped-resource-key* :rf.scope/global :shell/banner {:slug "u"})]
    (is (= [nil [:shell/banner :leaf/list] true]
           [(:plan-error plan) (resources-of (plan-dispatches plan) :rf.resource/ensure)
            (contains? (:blocking plan) (rf.resources.state/key-id banner-key))]))
    (is (= [[:route/profile :route/profile.favorites :shell/banner]]
           (mapv (juxt #(get-in % [:ancestor :route-id]) #(get-in % [:child :route-id]) :resource)
                 (:advisories plan))))))

(deftest r2-collapse-cycle-fails-the-whole-plan
  ;; A and C resolve to one identity; B :after A, C :after B — the collapse makes
  ;; a cycle, so the plan fails and dispatches no ensures.
  (rf/reg-resource :cyc/shared (article-spec {}) article-spec-request)
  (rf/reg-resource :cyc/mid (article-spec {}) article-spec-request)
  (let [branch [{:route-id   :route/cyc
                 :route-meta {:resources [{:resource :cyc/shared :id :a :params (fn [_] {:slug "k"})}
                                          {:resource :cyc/mid    :id :b :params (fn [_] {:slug "m"}) :after #{:a}}
                                          {:resource :cyc/shared :id :c :params (fn [_] {:slug "k"}) :after #{:b}}]}}]
        plan   (rf.resources.route/route-resource-plan {:id :route/cyc :params {} :query {}} {}
                                                       {:nav-token 1 :branch branch})]
    (is (= [:rf.error/resource-route-plan []]
           [(:rf.error/id (:plan-error plan)) (of-event (plan-dispatches plan) :rf.resource/ensure)]))))

;; The `:rf.resource/route-plan` row is emitted behind `rf.interop/debug-enabled?`,
;; so the trace assertions below run in the dev posture only.

(deftest r2-plan-diff-trace-carries-the-identity-partition
  ;; One row answers which identity was ensured / kept / removed on a
  ;; sibling-leaf navigation, without diffing two rows.
  (rf/reg-resource :sh/v (article-spec {}) article-spec-request)
  (rf/reg-resource :lf/a (article-spec {}) article-spec-request)
  (rf/reg-resource :lf/b (article-spec {}) article-spec-request)
  (let [parent-meta {:resources [{:resource :sh/v :params (fn [_] {:slug "v"}) :blocking? true}]}
        shared-key  (rf.resources.state/scoped-resource-key* :rf.scope/global :sh/v {:slug "v"})
        a-key       (rf.resources.state/scoped-resource-key* :rf.scope/global :lf/a {:slug "a"})
        b-key       (rf.resources.state/scoped-resource-key* :rf.scope/global :lf/b {:slug "b"})
        branch1     [{:route-id :route/p   :route-meta parent-meta}
                     {:route-id :route/p.a :route-meta {:resources [{:resource :lf/a :params (fn [_] {:slug "a"})}]}}]
        branch2     [{:route-id :route/p   :route-meta parent-meta}
                     {:route-id :route/p.b :route-meta {:resources [{:resource :lf/b :params (fn [_] {:slug "b"})}]}}]
        plan1       (rf.resources.route/route-resource-plan {:id :route/p.a :params {} :query {}} {}
                                                            {:nav-token 1 :branch branch1})
        ;; plan1's shared identity has since LOADED — the reusable kept case.
        rdb         {:rf.runtime/resources
                     {:entries {(rf.resources.state/key-id shared-key)
                                {:resource/id :sh/v :resource/key shared-key
                                 :status :loaded :data {:n 1} :attempt 1}}}}
        tags        (route-plan-tags
                      #(rf.resources.route/route-resource-plan
                         {:id :route/p.b :params {} :query {}} {}
                         {:nav-token 2 :prev-id :route/p.a :prev-nav-token 1
                          :prev-identities (:identities plan1) :branch branch2
                          :runtime-db rdb}))]
    (when rf.interop/debug-enabled?
      (is (= {:ensured            1
              :kept               1
              :removed            1
              :ensured-identities [b-key]
              :kept-identities    [shared-key]
              :removed-identities [a-key]
              :identities         [shared-key b-key]}
             (select-keys tags [:ensured :kept :removed :ensured-identities :kept-identities
                                :removed-identities :identities]))
          ":identities is the planner's grouped plan order, parent-most first"))))

(deftest r2-removed-identities-is-membership-not-caller-order
  ;; `:removed-identities` answers WHICH prior identities were dropped and
  ;; promises no order: the routing handoff hands back an UNORDERED map, and a
  ;; small CLJS set iterates in insertion order where the JVM's hashes. The row
  ;; is sorted by CEDN-1 key-id, so it is a pure function of the membership on
  ;; both hosts — tested with THREE removals, which a one-element vector cannot.
  (doseq [id [:rm/v :rm/a :rm/b :rm/c :rm/n]]
    (rf/reg-resource id (article-spec {}) article-spec-request))
  (let [key-of      (fn [id slug] (rf.resources.state/scoped-resource-key* :rf.scope/global id {:slug slug}))
        v-key       (key-of :rm/v "v")
        a-key       (key-of :rm/a "a")
        b-key       (key-of :rm/b "b")
        c-key       (key-of :rm/c "c")
        branch      [{:route-id :route/q
                      :route-meta {:resources [{:resource :rm/v :params (fn [_] {:slug "v"}) :blocking? true}]}}
                     {:route-id :route/q.n :route-meta {:resources [{:resource :rm/n :params (fn [_] {:slug "n"})}]}}]
        ;; the shared ancestor has LOADED, so it is kept — leaving a / b / c dropped.
        rdb         {:rf.runtime/resources
                     {:entries {(rf.resources.state/key-id v-key)
                                {:resource/id :rm/v :resource/key v-key
                                 :status :loaded :data {:n 1} :attempt 1}}}}
        tags-for    (fn [prev-identities]
                      (route-plan-tags
                        #(rf.resources.route/route-resource-plan
                           {:id :route/q.n :params {} :query {}} {}
                           {:nav-token 2 :prev-id :route/q.a :prev-nav-token 1
                            :prev-identities prev-identities :branch branch :runtime-db rdb})))
        ;; the SAME prior identities supplied four ways: a vector, its reverse,
        ;; the SET the live handoff threads, and a duplicate-bearing vector
        variants    (mapv tags-for [[v-key a-key b-key c-key]
                                    [c-key b-key a-key v-key]
                                    #{v-key a-key b-key c-key}
                                    [a-key a-key v-key b-key b-key c-key c-key]])]
    (when rf.interop/debug-enabled?
      (is (= [[3 #{a-key b-key c-key}]]
             (distinct (map (juxt :removed (comp set :removed-identities)) variants)))
          "every variant drops exactly the three")
      (is (apply = (map :removed-identities variants))
          "the row is byte-identical however the caller ordered or duplicated it")
      (is (= 3 (count (:removed-identities (peek variants))))
          "a duplicate can neither duplicate an entry nor put :removed out of step"))))

(deftest r2-identity-membership-is-byte-exact-not-clojure-equal
  ;; Resource identity is the CEDN-1 key-id, which is collection-KIND sensitive:
  ;; `{:tags ["a"]}` and `{:tags '("a")}` are two cache entries, yet `=` (and
  ;; hashing) collapse them. So every claim is made on key-ids.
  (doseq [id [:bx/v :bx/n :bx/p]]
    (rf/reg-resource id (article-spec {}) article-spec-request))
  (let [vec-key   (rf.resources.state/scoped-resource-key* :rf.scope/global :bx/p {:slug "p" :tags ["a"]})
        list-key  (rf.resources.state/scoped-resource-key* :rf.scope/global :bx/p {:slug "p" :tags '("a")})
        v-key     (rf.resources.state/scoped-resource-key* :rf.scope/global :bx/v {:slug "v"})
        n-key     (rf.resources.state/scoped-resource-key* :rf.scope/global :bx/n {:slug "n"})
        branch    (fn [leaf-resources]
                    [{:route-id :route/b
                      :route-meta {:resources [{:resource :bx/v :params (fn [_] {:slug "v"}) :blocking? true}]}}
                     {:route-id :route/b.n :route-meta {:resources leaf-resources}}])
        n-entry   {:resource :bx/n :params (fn [_] {:slug "n"})}
        branch+p  (branch [n-entry {:resource :bx/p :params (fn [_] {:slug "p" :tags ["a"]})}])
        branch-p  (branch [n-entry])
        loaded    (fn [k rid] [(rf.resources.state/key-id k)
                               {:resource/id rid :resource/key k :status :loaded :data {:n 1} :attempt 1}])
        ;; the ancestor is adoptable; in rdb+p so is the VECTOR-bearing twin
        rdb       {:rf.runtime/resources {:entries (into {} [(loaded v-key :bx/v)])}}
        rdb+p     {:rf.runtime/resources {:entries (into {} [(loaded v-key :bx/v) (loaded vec-key :bx/p)])}}
        tags-for  (fn [branch runtime-db prev-identities]
                    (route-plan-tags
                      #(rf.resources.route/route-resource-plan
                         {:id :route/b.n :params {} :query {}} {}
                         {:nav-token 2 :prev-id :route/b.a :prev-nav-token 1
                          :prev-identities prev-identities :branch branch :runtime-db runtime-db})))
        split-of  (fn [tags] (mapv #(ids (% tags)) [:ensured-identities :kept-identities :removed-identities]))]
    (is (not= (rf.resources.state/key-id vec-key) (rf.resources.state/key-id list-key))
        "premise: ONE value to `=`, TWO byte identities to the cache")
    (when rf.interop/debug-enabled?
      (is (= [(ids [n-key vec-key]) (ids [v-key]) (ids [list-key])]
             (split-of (tags-for branch+p rdb [v-key list-key])))
          "the LIST twin is REMOVED when the next plan holds only its VECTOR twin, which is ensured")
      (is (= [(ids [n-key vec-key]) (ids [v-key]) (ids [list-key])]
             (split-of (tags-for branch+p rdb+p [v-key list-key])))
          "adoption does not cross the pair, even when the twin's own entry is adoptable")
      (is (= [(ids [n-key]) (ids [v-key vec-key]) []]
             (split-of (tags-for branch+p rdb+p [v-key vec-key])))
          "control: a prior identity that IS the planned one is adopted")
      (let [gone (tags-for branch-p rdb [list-key vec-key])]
        (is (= [2 (sort (ids [list-key vec-key]))] [(:removed gone) (sort (ids (:removed-identities gone)))])
            "both are dropped together when neither is planned — a set-backed carrier reports one")
        (is (= (:removed-identities gone)
               (:removed-identities (tags-for branch-p rdb [vec-key list-key])))
            "and the row is still independent of the caller's order")))))

(deftest r2-a-plan-holding-both-byte-distinct-twins-plans-blocks-and-drains-both
  ;; ONE plan requiring BOTH members of an `=`-equal, byte-DISTINCT pair, through
  ;; the real navigate path. It survives only if `collapse-and-order` groups by
  ;; key-id (else one ensure, the twin never fetched) and the :resource-plan /
  ;; :resource-blocking slots are `{key-id scoped-key}` maps (a set holds one).
  (rf/reg-resource :tw2/feed (article-spec {}) article-spec-request)
  (rf/reg-route :route/tw2-both
                {:resources [{:id :vec-entry :resource :tw2/feed :blocking? true
                              :params (fn [_] {:slug "f" :tags ["a"]})}
                             {:id :list-entry :resource :tw2/feed :blocking? true
                              :params (fn [_] {:slug "f" :tags (list "a")})}]}
                "/tw2/both")
  (let [vec-key  (rf.resources.state/scoped-resource-key* :rf.scope/global :tw2/feed {:slug "f" :tags ["a"]})
        list-key (rf.resources.state/scoped-resource-key* :rf.scope/global :tw2/feed {:slug "f" :tags (list "a")})
        sorted   (fn [ks] (vec (sort (ids ks))))]
    (is (not= (rf.resources.state/key-id vec-key) (rf.resources.state/key-id list-key))
        "premise: ONE value to `=`, TWO byte identities to the cache")
    (let [tags      (route-plan-tags #(rf/dispatch-sync [:rf.route/navigate {:to :route/tw2-both}]))
          token     (:nav-token (slice))
          ;; the blocking carrier AS COMMITTED — exactly what the SSR drain reads
          blocking0 (get-in (rdb) (rf.resources.route/blocking-path token))
          drain     (fn [] [(rf.resources.ssr/blocking-settled? (entries) blocking0)
                            (sorted (vals (rf.resources.ssr/unsettled-blocking-keys (entries) blocking0)))])]
      (is (= [true true] [(some? (entry vec-key)) (some? (entry list-key))])
          "TWO ensures — the second identity is really fetched")
      (is (= [(blocking-map vec-key list-key) (blocking-map vec-key list-key) :loading]
             [(get-in (rdb) (rf.resources.route/plan-path token)) blocking0 (:transition (slice))])
          "the handoff and blocking slots both hold the pair, byte-keyed")
      (is (= [false (sorted [vec-key list-key])] (drain)) "the SSR drain sees both as unsettled")
      (settle-success! vec-key [{:id 1}])
      (is (= [(blocking-map list-key) :loading [false (sorted [list-key])]]
             [(get-in (rdb) (rf.resources.route/blocking-path token)) (:transition (slice)) (drain)])
          "each twin settles INDEPENDENTLY — one settle prunes exactly one wait point")
      (settle-success! list-key [{:id 2}])
      (is (= [true :idle [true []]]
             [(empty? (get-in (rdb) (rf.resources.route/blocking-path token))) (:transition (slice)) (drain)]))
      (when rf.interop/debug-enabled?
        (is (= {:ensured 2 :kept 0 :removed 0} (select-keys tags [:ensured :kept :removed])))
        (is (= (repeat 3 (sorted [vec-key list-key]))
               (map #(sorted (% tags)) [:identities :ensured-identities :blocking])))))))

(deftest r2-a-transition-keeping-one-twin-and-removing-the-other-reports-both
  ;; Away from the both-twins plan to one keeping the VECTOR twin: the prior
  ;; handoff slot carries both, so the diff can report the LIST twin's removal.
  (rf/reg-resource :tw3/feed (article-spec {}) article-spec-request)
  (rf/reg-route :route/tw3-both
                {:resources [{:id :vec-entry  :resource :tw3/feed :params (fn [_] {:slug "g" :tags ["a"]})}
                             {:id :list-entry :resource :tw3/feed :params (fn [_] {:slug "g" :tags (list "a")})}]}
                "/tw3/both")
  (rf/reg-route :route/tw3-vec
                {:resources [{:resource :tw3/feed :params (fn [_] {:slug "g" :tags ["a"]})}]}
                "/tw3/vec")
  (let [vec-key  (rf.resources.state/scoped-resource-key* :rf.scope/global :tw3/feed {:slug "g" :tags ["a"]})
        list-key (rf.resources.state/scoped-resource-key* :rf.scope/global :tw3/feed {:slug "g" :tags (list "a")})]
    (rf/dispatch-sync [:rf.route/navigate {:to :route/tw3-both}])
    ;; both twins LOAD, so the retained one is genuinely adoptable at commit
    (settle-success! vec-key [{:id 1}])
    (settle-success! list-key [{:id 2}])
    (is (= (blocking-map vec-key list-key)
           (get-in (rdb) (rf.resources.route/plan-path (:nav-token (slice)))))
        "premise: the first plan owns BOTH byte identities")
    (let [tags (route-plan-tags #(rf/dispatch-sync [:rf.route/navigate {:to :route/tw3-vec}]))]
      (is (= (blocking-map vec-key) (get-in (rdb) (rf.resources.route/plan-path (:nav-token (slice)))))
          "the next handoff carries exactly the surviving identity")
      (when rf.interop/debug-enabled?
        (is (= [1 0 1 (ids [vec-key]) (ids [list-key]) []]
               [(:kept tags) (:ensured tags) (:removed tags)
                (ids (:kept-identities tags)) (ids (:removed-identities tags)) (ids (:ensured-identities tags))])
            "one KEPT, one REMOVED, nothing ensured")))))

(deftest r2-plan-order-is-witnessed-not-merely-membership
  ;; The identity vectors carry GROUPED PLAN ORDER. The leaf declares zulu
  ;; before alpha and the parent comes first, an order neither alphabetical nor
  ;; key-id sorting would produce.
  (doseq [id [:po/mid :po/zulu :po/alpha]]
    (rf/reg-resource id (article-spec {}) article-spec-request))
  (let [key-of (fn [id slug] (rf.resources.state/scoped-resource-key* :rf.scope/global id {:slug slug}))
        branch [{:route-id :route/p
                 :route-meta {:resources [{:resource :po/mid :params (fn [_] {:slug "m"})}]}}
                {:route-id :route/p.leaf
                 :route-meta {:resources [{:resource :po/zulu  :params (fn [_] {:slug "z"})}
                                          {:resource :po/alpha :params (fn [_] {:slug "a"})}]}}]
        tags   (route-plan-tags
                 #(rf.resources.route/route-resource-plan
                    {:id :route/p.leaf :params {} :query {}} {}
                    {:nav-token 2 :branch branch :runtime-db {}}))
        order  [(key-of :po/mid "m") (key-of :po/zulu "z") (key-of :po/alpha "a")]]
    (when rf.interop/debug-enabled?
      (is (= [order order] [(:identities tags) (:ensured-identities tags)])
          "nothing is adoptable, so the ensured vector is the whole plan, in plan order"))))

(deftest r2-branch-resolve-fails-loud
  ;; A :parent naming an unregistered route aborts the plan: empty next
  ;; ownership, no partial ensure/adopt, and the prior owner released.
  (let [plan (rf.resources.route/route-resource-plan
               {:id :route/leaf :params {} :query {}} {}
               {:nav-token 2 :prev-id :route/prev :prev-nav-token 1
                :prev-identities #{[:rf.scope/global :old/res {}]}
                :branch-error {:kind :unknown-parent :route-id* :route/ghost}})
        ds   (plan-dispatches plan)]
    (is (= [:rf.error/resource-route-plan 0 0 1 true]
           [(:rf.error/id (:plan-error plan))
            (count (of-event ds :rf.resource/ensure))
            (count (of-event ds :rf.resource.internal/adopt-owner))
            (count (of-event ds :rf.resource/release-owner))
            (empty? (:identities plan))]))))

;; ===========================================================================
;; EP-0037 R1 — the ONE readiness projector (Spec 012 §Route readiness is a
;; resource projection): the table, then the paths that project through it.
;; ===========================================================================

(deftest requirement-state-reads-spec-016-facts-not-a-settle-signal
  (doseq [[label e expected]
          [["an absent entry: its ensure has not been applied yet" nil :pending]
           ["own usable data" {:status :loaded :data {:a 1}} :ready]
           ["a BACKGROUND-refresh failure keeps its data — never a failed first load"
            {:status :loaded :data {:a 1} :refresh-error {:kind :rf.http/server :status 503}} :ready]
           ["a FIRST-load failure"
            {:status :error :data nil :attempt 1 :error {:kind :rf.http/server :status 503}} :failed]
           ["work in flight" {:status :loading :data nil :attempt 1} :pending]
           ["enqueued but never attempted: its load is coming" {:status :idle :data nil :attempt 0} :pending]
           ["settled with no data and nothing left to settle it (an ABORTED first load)"
            {:status :idle :data nil :attempt 1} :inert]]]
    (is (= expected (rf.resources.route/requirement-state e)) label)))

(defn- runtime-db-with
  "A route slice at `nav-token` with `transition`, a blocking slot naming every
  key in `entries-by-key`, and those durable entries — the pure-projection fixture."
  [nav-token transition entries-by-key]
  {:rf.runtime/routing   {:current           {:route-id   :route/article
                                              :nav-token  nav-token
                                              :transition transition
                                              :error      nil}
                          :resource-blocking {nav-token (apply blocking-map (keys entries-by-key))}}
   :rf.runtime/resources {:entries (into {} (map (fn [[k e]] [(rf.resources.state/key-id k) e]))
                                         entries-by-key)}})

(def ^:private req-a (rf.resources.state/scoped-resource-key* :rf.scope/global :article/by-slug {:slug "a"}))
(def ^:private req-b (rf.resources.state/scoped-resource-key* :rf.scope/global :article/by-slug {:slug "b"}))

(defn- readiness
  "[transition error-id resource-id blocking-slot] of `rdb` at nav-1."
  [rdb]
  (let [cur (get-in rdb [:rf.runtime/routing :current])]
    [(:transition cur) (:rf.error/id (:error cur)) (:resource-id (:error cur))
     (into {} (get-in rdb (rf.resources.route/blocking-path "nav-1")))]))

(deftest reconcile-readiness-projects-the-spec-012-table
  (let [project #(readiness (rf.resources.route/reconcile-readiness (runtime-db-with "nav-1" %1 %2)))]
    (is (= [:idle nil nil {}] (project :loading {req-a {:status :loaded :data {:x 1}}}))
        "all ready → :idle, and the resolved requirement is pruned so a later invalidation cannot re-block")
    (is (= [:loading nil nil (blocking-map req-b)]
           (project :idle {req-a {:status :loaded :data {:x 1}}
                           req-b {:status :loading :data nil :attempt 1}}))
        "one still pending → :loading, and only it remains")
    (is (= [:error :rf.error/resource-route-blocking :article/by-slug (blocking-map req-a)]
           (project :loading {req-a {:resource/id :article/by-slug :status :error :data nil :attempt 1
                                     :error {:kind :rf.http/server :status 503}}}))
        "a failed blocking first load → :error, NOT pruned so a later success re-projects :idle"))
  ;; What keeps a committed PLANNING error (no blocking slot written) from being
  ;; clobbered back to :idle.
  (let [rdb {:rf.runtime/routing
             {:current {:nav-token  "nav-1"
                        :transition :error
                        :error      {:rf.error/id :rf.error/resource-route-plan}}}}]
    (is (identical? rdb (rf.resources.route/reconcile-readiness rdb))
        "no blocking slot for the live token is a structural no-op")))

(def ^:private pending-req
  {:resource/id :article/by-slug :status :loading :data nil :attempt 1})

(defn- failed-req
  "A blocking FIRST-load failure whose envelope `:status` identifies it."
  [http-status]
  {:resource/id :article/by-slug :status :error :data nil :attempt 1
   :error       {:kind :rf.http/server :status http-status}})

(defn- blocking-error-count [traces]
  (count (errors-of traces :rf.error/resource-route-blocking)))

(deftest reconcile-readiness-emits-the-blocking-error-once-per-edge-into-error
  ;; :error is re-picked over the CURRENT outstanding set on every settle, so a
  ;; second failure that sorts earlier legitimately moves it — but the route never
  ;; left :error, so the trace is gated on the transition EDGE, not on the value.
  (let [[early late] (sort-by rf.resources.state/key-id [req-a req-b])
        final  (volatile! nil)
        traces (record-error-traces!
                 (fn []
                   (let [rdb1 (rf.resources.route/reconcile-readiness
                                (runtime-db-with "nav-1" :loading {early pending-req late (failed-req 503)}))]
                     (vreset! final (rf.resources.route/reconcile-readiness
                                      (assoc-in rdb1 (rf.resources.state/entry-path early) (failed-req 500)))))))]
    (is (= [:error 500 (blocking-map early late) 1]
           [(get-in @final [:rf.runtime/routing :current :transition])
            (get-in @final [:rf.runtime/routing :current :error :error :status])
            (get-in @final (rf.resources.route/blocking-path "nav-1"))
            (blocking-error-count traces)])
        "the slice reports the CURRENT first failure, neither is pruned, and ONE trace fired"))
  ;; A GENUINE re-entry into :error still emits: the failed identity refetches
  ;; (→ :idle), then a fresh activation re-blocks on it and it fails again.
  (let [traces (record-error-traces!
                 (fn []
                   (let [rdb1 (rf.resources.route/reconcile-readiness
                                (runtime-db-with "nav-1" :loading {req-a (failed-req 503)}))
                         rdb2 (rf.resources.route/reconcile-readiness
                                (assoc-in rdb1 (rf.resources.state/entry-path req-a)
                                          {:resource/id :article/by-slug :status :loaded :data {:x 1}}))]
                     (is (= :idle (get-in rdb2 [:rf.runtime/routing :current :transition]))
                         "a successful load re-projects :idle — no stale error survives")
                     (rf.resources.route/reconcile-readiness
                       (-> rdb2
                           (assoc-in (rf.resources.route/blocking-path "nav-1") (blocking-map req-a))
                           (assoc-in (rf.resources.state/entry-path req-a) (failed-req 500)))))))]
    (is (= 2 (blocking-error-count traces)) "two transitions INTO :error are two traces")))

(deftest keep-previous-projection-does-not-complete-the-new-first-load
  ;; :keep-previous? projects the previous key's data while the new key
  ;; first-loads, but never inserts it into the new entry (data or tags), and
  ;; previous pixels are not a completed load: the route stays :loading.
  (rf/reg-resource :article/by-slug (article-spec {}) article-spec-request)
  (reg-article-route! {:blocking? true :keep-previous? true})
  (navigate-article! "a")
  (settle-success! (article-key "a") {:title "A"})
  (is (= :idle (:transition (slice))) "precondition: slug a landed")
  (navigate-article! "b")
  (let [b    (entry (article-key "b"))
        view @(rf/subscribe [:rf/resource {:resource :article/by-slug
                                           :scope    :rf.scope/global
                                           :params   {:slug "b"}}])]
    (is (= [true (article-key "a") {:title "A"} nil]
           ((juxt :previous? :previous-key :previous-data :data) view))
        "the state view projects the prior key's data")
    (is (= [(article-key "a") nil true :pending]
           [(:previous-key b) (:data b) (empty? (:tags b)) (rf.resources.route/requirement-state b)])
        "the new entry holds only the projection pointer")
    (is (= [:loading true]
           [(:transition (slice)) (contains? (blocking-slot (:nav-token (slice))) (article-key "b"))]))))

(deftest restore-and-hydration-recompute-a-readiness-the-cache-contradicts
  ;; A snapshot's (or payload's) :loading must not survive resource state that
  ;; contradicts it — and a mid-load capture, whose attempt did not survive the
  ;; restore, must not leave a :loading nothing can ever settle.
  (rf/reg-resource :article/by-slug (article-spec {}) article-spec-request)
  (let [req (fn [m] (merge {:resource/id :article/by-slug :resource/key req-a :attempt 1} m))]
    (doseq [[label reconcile e expected]
            [["restore: the requirement came back WITH data"
              rf.resources.ssr/reconcile-on-restore (req {:status :loaded :data {:x 1}})
              [:idle nil nil {}]]
             ["restore: captured MID-LOAD"
              rf.resources.ssr/reconcile-on-restore (req {:status :loading :data nil :current-work "work-1"})
              [:idle nil nil {}]]
             ["restore: a FAILED blocking first load"
              rf.resources.ssr/reconcile-on-restore (req {:status :error :data nil
                                                         :error {:kind :rf.http/server :status 503}})
              [:error :rf.error/resource-route-blocking :article/by-slug (blocking-map req-a)]]
             ["hydration: the requirement arrived WITH data"
              rf.resources.ssr/hydrate-runtime-db (req {:status :loaded :data {:x 1}})
              [:idle nil nil {}]]]]
      (is (= expected (readiness (reconcile (runtime-db-with "nav-1" :loading {req-a e})))) label))))

;; ===========================================================================
;; EP-0037 R2 — retained-entry adoption and contributor attribution
;; ===========================================================================

(defn- ledger-with
  "A `:rf.runtime/work-ledger` carrying `work-id` at `status`, keyed by the
  CEDN-1 byte identity as the runtime keys it."
  [work-id status]
  {(rf.resources.work-ledger/work-id-id work-id) {:work/id work-id :status status}})

(defn- rdb-with-entries
  "A runtime-db carrying only durable cache `entries-by-key` (plus an optional
  work ledger) — the AT-COMMIT facts routing threads into the plan hook."
  ([entries-by-key] (rdb-with-entries entries-by-key nil))
  ([entries-by-key ledger]
   (cond-> {:rf.runtime/resources {:entries (into {} (map (fn [[k e]] [(rf.resources.state/key-id k) e]))
                                                  entries-by-key)}}
     ledger (assoc :rf.runtime/work-ledger ledger))))

(deftest r2-retained-identity-is-adopted-only-when-genuinely-reusable
  ;; Prior-plan MEMBERSHIP alone does not make an identity adoptable:
  ;; adopt-owner issues no fetch, so adopting an entry that vanished or cannot
  ;; progress commits a blocking slot nothing can drain. A retained identity is
  ;; adopted only with own usable data or genuinely live work — `:current-work`
  ;; alone is not proof of work, the linked record's status is.
  (rf/reg-resource :sh/v (article-spec {}) article-spec-request)
  (rf/reg-resource :lf/b (article-spec {}) article-spec-request)
  (let [branch     [{:route-id :route/p
                     :route-meta {:resources [{:resource :sh/v :params (fn [_] {:slug "v"}) :blocking? true}]}}
                    {:route-id :route/p.b :route-meta {:resources [{:resource :lf/b :params (fn [_] {:slug "b"})}]}}]
        shared-key (rf.resources.state/scoped-resource-key* :rf.scope/global :sh/v {:slug "v"})
        plan-for   (fn [runtime-db]
                     (rf.resources.route/route-resource-plan
                       {:id :route/p.b :params {} :query {}} {}
                       {:nav-token 2 :prev-id :route/p.a :prev-nav-token 1
                        :prev-identities #{shared-key} :branch branch
                        :runtime-db runtime-db}))
        shape      (fn [plan]
                     (let [ds (plan-dispatches plan)]
                       [(resources-of ds :rf.resource.internal/adopt-owner)
                        (resources-of ds :rf.resource/ensure)
                        (contains? (:blocking plan) (rf.resources.state/key-id shared-key))]))
        shared     (fn [m] {shared-key (merge {:resource/id :sh/v :data nil :attempt 1} m)})
        loaded     (rdb-with-entries (shared {:status :loaded :data {:n 1}}))]
    (testing "a reusable retained identity is adopted"
      (is (= [[:sh/v] [:lf/b] false] (shape (plan-for loaded)))
          "LOADED — the partial-revalidation law; nothing left to wait for")
      (is (= [[:sh/v] [:lf/b] true]
             (shape (plan-for (rdb-with-entries (shared {:status :loading :current-work "w-1"})
                                                (ledger-with "w-1" :running)))))
          "IN-FLIGHT — its own settle drains the slot"))
    (testing "an unusable retained identity takes the ordinary ensure path, and holds the route"
      (doseq [[label entries ledger]
              [["missing (adopt-owner would be a no-op on it)" {} nil]
               ["settled with no data and no work" (shared {:status :idle}) nil]
               ["enqueued but never attempted" (shared {:status :idle :attempt 0}) nil]
               ["a failed first load" (shared {:status :error :error {:kind :rf.http/server}}) nil]
               ["its work is abort-requested" (shared {:status :loading :current-work "w-doomed"})
                (ledger-with "w-doomed" :abort-requested)]
               ["its work is cancelled" (shared {:status :loading :current-work "w-dead"})
                (ledger-with "w-dead" :cancelled)]
               ["a work pointer with no record at all" (shared {:status :loading :current-work "w-pruned"}) nil]]]
        (is (= [[] [:sh/v :lf/b] true] (shape (plan-for (rdb-with-entries entries ledger)))) label)))
    (testing "attach-before-release on every route — the release is LAST"
      (doseq [runtime-db [loaded (rdb-with-entries {})]]
        (let [release (last (plan-dispatches (plan-for runtime-db)))]
          (is (= [:rf.resource/release-owner [:route :route/p.a 1]]
                 [(first release) (:owner (second release))])))))))

(defn- reg-shell-branch! []
  (rf/reg-resource :prof/banner (article-spec {}) article-spec-request)
  (rf/reg-resource :prof/tab-one (article-spec {}) article-spec-request)
  (rf/reg-resource :prof/tab-two (article-spec {}) article-spec-request)
  (rf/reg-route :route/prof
                {:resources [{:resource :prof/banner :params (fn [_] {:slug "b"}) :blocking? true}]}
                "/prof")
  (rf/reg-route :route/prof.one
                {:parent :route/prof
                 :resources [{:resource :prof/tab-one :params (fn [_] {:slug "one"})}]}
                "/prof/one")
  (rf/reg-route :route/prof.two
                {:parent :route/prof
                 :resources [{:resource :prof/tab-two :params (fn [_] {:slug "two"})}]}
                "/prof/two"))

(def ^:private banner-key
  (rf.resources.state/scoped-resource-key* :rf.scope/global :prof/banner {:slug "b"}))

(deftest r2-sibling-nav-recovers-a-retained-identity-that-vanished
  ;; LIVENESS through the real navigate path: the shared banner is removed out
  ;; from under the plan diff, yet stays in the previous plan's identity set.
  (reg-shell-branch!)
  (rf/dispatch-sync [:rf.route/navigate {:to :route/prof.one}])
  (settle-success! banner-key {:name "Ada"})
  (settle-success! (rf.resources.state/scoped-resource-key* :rf.scope/global :prof/tab-one {:slug "one"}) [{:id 1}])
  (rf/dispatch-sync [:rf.resource/remove {:resource :prof/banner :params {:slug "b"}}])
  (is (nil? (entry banner-key)) "precondition: the retained identity is gone")
  (rf/dispatch-sync [:rf.route/navigate {:to :route/prof.two}])
  (let [nav-token (:nav-token (slice))]
    (is (= [:loading true :loading true]
           [(:status (entry banner-key)) (some? (:current-work (entry banner-key)))
            (:transition (slice)) (contains? (blocking-slot nav-token) banner-key)])
        "re-ensured with live work, so the committed blocking slot can drain")
    (settle-success! banner-key {:name "Ada"})
    (is (= [:idle #{} true]
           [(:transition (slice)) (blocking-slot nav-token)
            (rf.resources.state/has-data? (entry banner-key))])
        "no permanent :loading")))

(deftest r2-adoption-of-in-flight-work-neither-revalidates-nor-aborts
  ;; The counterweight to the liveness test: a genuinely reusable retained
  ;; identity is adopted WITHOUT revalidation, and releasing the prior owner
  ;; cannot abort work the next plan still needs.
  (reg-shell-branch!)
  (rf/dispatch-sync [:rf.route/navigate {:to :route/prof.one}])
  (let [before (entry banner-key)
        work   (:current-work before)]
    (is (= :loading (:status before)) "precondition: the banner is in flight")
    (rf/dispatch-sync [:rf.route/navigate {:to :route/prof.two}])
    (let [after (entry banner-key)]
      (is (= [(:generation before) work false]
             [(:generation after) (:current-work after)
              (rf.resources.work-ledger/terminal? (:status (rf.resources.work-ledger/get-record (rdb) work)))])
          "the same generation and work record, still live — adopted, not restarted or aborted"))
    (settle-success! banner-key {:name "Ada"})
    (is (= :idle (:transition (slice))) "the adopted work's own settle lands the route")
    (let [gen (:generation (entry banner-key))]
      (rf/dispatch-sync [:rf.route/navigate {:to :route/prof.one}])
      (is (= [gen :idle] [(:generation (entry banner-key)) (:transition (slice))])
          "a LOADED retained identity is likewise never revalidated"))))

(defn- ancestor-branch
  "A two-segment branch whose ANCESTOR carries `anc-entry` and whose leaf
  carries a plain resource. The leaf is the plan target."
  [anc-entry]
  [{:route-id :route/ancestor :route-meta {:resources [anc-entry]}}
   {:route-id :route/leaf
    :route-meta {:resources [{:resource :audit/leaf :id :lf :params (fn [_] {:slug "l"})}]}}])

(defn- ancestor-plan-error
  "Plan the leaf over `branch`; return `[plan error-traces]`."
  [branch]
  (let [plan   (atom nil)
        traces (record-error-traces!
                 (fn [] (reset! plan (rf.resources.route/route-resource-plan
                                       {:id :route/leaf :params {} :query {}} {}
                                       {:nav-token 1 :branch branch}))))]
    [@plan traces]))

(deftest r2-ancestor-planning-failure-names-the-contributing-declaration
  ;; A parent-chain plan resolves every contributor against the LEAF target, so
  ;; the leaf :route-id alone cannot say which declaration failed. Spec 016
  ;; §Effective parent-chain resource plans rule 3: the error names the
  ;; contributor route and declaration too.
  (rf/reg-resource :audit/ancestor (article-spec {}) article-spec-request)
  (rf/reg-resource :audit/leaf (article-spec {}) article-spec-request)
  (let [anc        {:route-id :route/ancestor :local-id :anc}
        anc-entry  (fn [m] (merge {:resource :audit/ancestor :id :anc :params (fn [_] {:slug "a"})} m))
        trace-tags (fn [traces] (:tags (first (errors-of traces :rf.error/resource-route-plan))))]
    (testing "on the slice error AND its trace, fail-closed"
      (let [[plan traces] (ancestor-plan-error (ancestor-branch (anc-entry {:params (fn [_] nil)})))
            ds            (plan-dispatches plan)]
        (is (= [:rf.error/resource-route-plan :route/leaf :audit/ancestor anc]
               ((juxt :rf.error/id :route-id :resource-id :contributor) (:plan-error plan))))
        (is (= [:route/leaf :audit/ancestor anc]
               ((juxt :route-id :resource-id :contributor) (trace-tags traces))))
        (is (= [[] [] true]
               [(of-event ds :rf.resource/ensure) (of-event ds :rf.resource.internal/adopt-owner)
                (empty? (:identities plan))])
            "no partial ensures or adoptions")))
    (testing "every failure kind is attributed, keeping its specific recovery"
      (doseq [[label branch contributor recovery]
              [["an ancestor :scope in the retired fn tier"
                (ancestor-branch (anc-entry {:scope (fn [_ _] :rf.scope/global)})) anc :fix-scope]
               ["an ancestor :when that throws"
                (ancestor-branch (anc-entry {:when (fn [_ _] (throw (ex-info "boom" {})))})) anc :fix-when]
               ;; the local :after validation runs before :when filters, so it
               ;; attributes the contributing ROUTE
               ["an ancestor :after naming an id no contributor declares"
                (ancestor-branch (anc-entry {:after #{:not-a-local-id}})) {:route-id :route/ancestor} :fix-after]
               ["a LEAF failure is attributed to the leaf"
                [{:route-id :route/ancestor :route-meta {:resources [(anc-entry {})]}}
                 {:route-id :route/leaf
                  :route-meta {:resources [{:resource :audit/leaf :id :lf :params (fn [_] nil)}]}}]
                {:route-id :route/leaf :local-id :lf} :fix-params]]]
        (let [err (:plan-error (first (ancestor-plan-error branch)))]
          (is (= [contributor recovery]
                 [(select-keys (:contributor err) (keys contributor)) (:recovery err)])
              label))))
    (testing "a resolver throwing its OWN :contributor cannot publish a false one"
      ;; :contributor is the PLANNER's key; resolver code may throw any ex-info
      (let [[plan traces] (ancestor-plan-error
                            (ancestor-branch
                              (anc-entry {:params (fn [_]
                                                    (throw (ex-info "boom"
                                                             {:contributor {:route-id :wrong
                                                                            :local-id :wrong}})))})))]
        (is (= [anc anc :route/leaf]
               [(:contributor (:plan-error plan)) (:contributor (trace-tags traces))
                (:route-id (:plan-error plan))]))))))

(deftest r2-warm-prefetch-planning-failure-is-attributed-too
  ;; The warm plan shares `materialize-occurrences`, so the same attribution
  ;; rides its planning error (with :plan-cause :prefetch and no nav-token).
  (rf/reg-resource :audit/ancestor (article-spec {}) article-spec-request)
  (rf/reg-resource :audit/leaf (article-spec {}) article-spec-request)
  (let [plan (rf.resources.route/route-resource-warm-plan
               {:id :route/leaf :params {} :query {}}
               {:branch (ancestor-branch {:resource :audit/ancestor :id :anc :params (fn [_] nil)})})]
    (is (= [:prefetch {:route-id :route/ancestor :local-id :anc} true]
           [(:plan-cause (:plan-error plan)) (:contributor (:plan-error plan)) (empty? (:fx plan))])
        "fail-closed — no partial warm ensures")))
