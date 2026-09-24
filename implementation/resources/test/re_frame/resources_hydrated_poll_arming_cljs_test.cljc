(ns re-frame.resources-hydrated-poll-arming-cljs-test
  "A hydrated resource entry that is OWNED once `:rf/hydrate`
  commits starts polling on the client.

  WHY THE REARM MUST ARM IT. On the client a `:poll` timer arms after a load
  settle while the entry is owned, or on a fresh-skip that attaches an owner to
  a previously OWNER-FREE entry. A hydrated entry whose route owner rode the
  wire is already owned, so the client's ensure is a fresh-skip onto an owned
  entry and arms nothing, and on the ordinary SSR boot no client ensure runs
  at all (S4). A hydration rearm that armed only each hydrated entry's GC
  timer would leave a server-rendered `:poll-interval-ms` resource never
  polling on the client, although it is actively owned.

  THE RULE (Spec 016 §Polling). While an entry has at least one active owner,
  the runtime re-runs its load on the interval; the `:poll` timer is armed
  lazily client-side and never rides the wire. After the client commits
  `:rf/hydrate`, the resources rearm arms the `:poll` timer of each hydrated
  entry that is owned and whose resource declares `:poll-interval-ms`, beside
  its GC timer. An owner-free hydrated entry arms no poll (S3), a release of
  the last owner stops polling, and a server-side hydrate arms nothing.

  TWO INSTRUMENTS, as in `resources-hydrated-gc-arming-cljs-test`: the
  captured `:rf.resource/hydrate-rearm` / `:rf.resource/schedule-timers`
  emissions, each forwarding to production, and the real host timer side table
  (`rf.resources.timers/timer-table`). S3 is the positive control for both: an
  owner-free hydrated entry that a client ensure revives arms its poll through
  the ordinary fresh-skip path.

  The interval is long so a real host timer never fires mid-test; a fire is
  driven explicitly, as the host clock would drive it. Frames are
  `:platform :client`: on the JVM the host-wide platform default is `:server`,
  and the hydrate handler requests host work only on a client."
  (:require
   #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
      :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
   [re-frame.core :as rf]
   [re-frame.fx :as rf.fx]
   [re-frame.late-bind :as rf.late-bind]
   [re-frame.resources]
   [re-frame.resources.route :as rf.resources.route]
   [re-frame.resources.state :as rf.resources.state]
   [re-frame.resources.test-support]
   [re-frame.resources.timers :as rf.resources.timers]
   [re-frame.routing :as rf.routing]
   [re-frame.http.managed]
   [re-frame.ssr]
   [re-frame.ssr.payload-policy :as rf.ssr.payload-policy]
   [re-frame.schemas]
   [re-frame.test-support :as rf.test-support]
   #?(:clj  [re-frame.substrate.plain-atom :as substrate]
      :cljs [re-frame.adapter.reagent :as substrate])))

(def ^:private interval-ms 600000)

(def ^:private scheduled
  "Captured `:rf.resource/schedule-timers` args, in emission order."
  (atom []))

(def ^:private rearms
  "Captured `:rf.resource/hydrate-rearm` frame ids, in emission order."
  (atom []))

(defn- init! []
  (rf/make-frame {:id :rf/default :url-bound? true :platform :client
                  :doc "Hydrated poll arming regression frame."})
  (rf.routing/reset-counters!)
  (rf.resources.route/install-routing-integration!)
  (reset! scheduled [])
  (reset! rearms [])
  (rf.fx/reg-fx :rf.http/managed (fn [_ctx _args] nil))
  (rf.fx/reg-fx :rf.nav/push-url {:platforms #{:server :client}} (fn [_ _] nil))
  (rf.fx/reg-fx :rf.resource/schedule-timers
    (fn [ctx args]
      (swap! scheduled conj args)
      (rf.resources.timers/schedule-timers-handler ctx args)))
  (rf.fx/reg-fx :rf.resource/hydrate-rearm
    (fn [{frame-id :frame} _args]
      (swap! rearms conj frame-id)
      (when-let [rearm! (rf.late-bind/get-fn :resources/rearm-after-hydration!)]
        (rearm! frame-id)))))

(defn- cancel-real-timers [f]
  (try (f) (finally (rf.resources.timers/reset-cache!))))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter substrate/adapter
     :init-fn init!})
  cancel-real-timers)

(def ^:private k
  (rf.resources.state/scoped-resource-key :rf.scope/global :hg/article {:slug "intro"}))

(defn- runtime-db
  ([] (runtime-db :rf/default))
  ([fid] (:rf.db/runtime (rf/frame-state-value fid))))

(defn- entry
  ([] (entry :rf/default))
  ([fid] (get-in (runtime-db fid) (rf.resources.state/entry-path k))))

(defn- slot
  "The host timer slot of `kind` for `k` in `fid`, or nil when none is armed."
  [fid kind]
  (get @rf.resources.timers/timer-table [fid (rf.resources.state/key-id k) kind]))

(defn- poll-slot
  ([] (poll-slot :rf/default))
  ([fid] (slot fid rf.resources.timers/poll-kind)))

(defn- gc-slot [] (slot :rf/default rf.resources.timers/gc-kind))

(defn- poll-arm-emissions []
  (filterv #(and (= k (:resource/key %))
                 (some? (get-in % [:timers :poll])))
           @scheduled))

(defn- reg-article!
  ([] (reg-article! {:poll-interval-ms interval-ms}))
  ([policy]
   (rf/reg-resource :hg/article
                    (merge {:scope         :rf.scope/global
                            :params-schema [:map [:slug :string]]}
                           policy)
                    (fn [{:keys [slug]} _ctx]
                      {:request {:method :get :url (str "/api/articles/" slug)}}))))

(defn- hydrate!
  ([owners] (hydrate! :rf/default owners))
  ([fid owners]
   (let [e (merge (rf.resources.state/empty-entry :hg/article k)
                  {:status :loaded :data {:title "Intro"} :loaded-at 1000
                   :generation 1 :active-owners owners})]
     (rf/dispatch-sync
       [:rf/hydrate {:rf/frame-id   fid
                     :rf/app-db     {}
                     :rf/runtime-db {rf.resources.state/resources-key
                                     {:entries {(rf.resources.state/key-id k) e}}}}]
       {:frame fid}))))

(defn- ensure! [owner]
  (rf/dispatch-sync [:rf.resource/ensure
                     {:resource :hg/article :scope :rf.scope/global
                      :params {:slug "intro"} :owner owner}]))

(defn- release! [owner]
  (rf/dispatch-sync [:rf.resource/release-owner {:owner owner}]))

(defn- fire-armed-poll!
  "What the host clock does: run the poll re-check IF a poll timer is armed,
  with the document visible."
  []
  (when (poll-slot)
    (rf/dispatch-sync [:rf.resource.internal/poll-fired {:resource/key k :hidden? false}])))

(defn- observed []
  {:owners     (:active-owners (entry))
   :status     (:status (entry))
   :poll-slot? (some? (poll-slot))
   :gc-slot?   (some? (gc-slot))
   :rearms     @rearms
   :poll-emits (mapv #(get-in % [:timers :poll]) (poll-arm-emissions))})

(defn- armed-by-hydration
  "Both instruments, read the moment `:rf/hydrate` returns."
  []
  {:rearm-requested? (= [:rf/default] @rearms)
   :poll             (poll-slot)})

(defn- assert-hydration-armed-poll! [at-hydrate]
  (is (:rearm-requested? at-hydrate)
      (str "the client's :rf/hydrate commit requested the rearm — " (pr-str (observed))))
  (is (some? (:poll at-hydrate))
      (str "…and a real poll timer was armed by the time hydration returned — "
           (pr-str (observed)))))

;; ---- S1: the same route owner rides through; the client ensures with it -----

(deftest s1-same-route-owner-rides-through-polls
  (testing "S1 — a fresh-skip onto an owned entry arms nothing, so only the
            hydration rearm can start polling; a fire keeps polling, and the
            last owner's release stops it"
    (reg-article!)
    (let [route-owner [:route :route/article "nav-1"]]
      (hydrate! #{[:ssr "req-1" "nav-1"] route-owner})
      (let [at-hydrate (armed-by-hydration)]
        (assert-hydration-armed-poll! at-hydrate)
        (ensure! route-owner)
        (is (and (some? (:poll at-hydrate))
                 (= (:token (:poll at-hydrate)) (:token (poll-slot))))
            "the client's fresh-skip onto the owned entry leaves that poll timer alone")
        (fire-armed-poll!)
        (let [re-armed (poll-slot)]
          (is (and (some? (:poll at-hydrate)) (some? re-armed)
                   (not= (:token (:poll at-hydrate)) (:token re-armed)))
              (str "the owned poll fire RE-ARMED the next interval — " (pr-str (observed))))
          (release! route-owner)
          (is (and (some? re-armed) (nil? (poll-slot)))
              (str "the last owner's release stopped polling — " (pr-str (observed)))))))))

;; ---- S2: a DIFFERENT owner on the client ensure ----------------------------

(deftest s2-different-owner-on-client-ensure-polls
  (testing "S2 — a newly attached owner onto an already-owned entry arms
            nothing either, so the hydration rearm is what starts polling"
    (reg-article!)
    (let [server-owner [:route :route/article "nav-1"]
          client-owner [:route :route/article "nav-2"]]
      (hydrate! #{[:ssr "req-1" "nav-1"] server-owner})
      (let [at-hydrate (armed-by-hydration)]
        (assert-hydration-armed-poll! at-hydrate)
        (ensure! client-owner)
        (release! server-owner)
        (is (and (some? (:poll at-hydrate)) (some? (poll-slot)))
            (str "still owned by the client owner, so it keeps polling — " (pr-str (observed))))
        (release! client-owner)
        (is (nil? (poll-slot))
            (str "the last owner's release stopped polling — " (pr-str (observed))))))))

;; ---- S3: positive instrument control — hydrated OWNER-FREE -----------------

(deftest s3-owner-free-hydrated-entry-polls-only-once-owned
  (testing "S3 — control: an owner-free hydrated entry arms no poll at
            hydration, and a client ensure that revives it arms the poll
            through the ordinary fresh-skip path, so both instruments see it"
    (reg-article!)
    (let [client-owner [:route :route/article "nav-2"]]
      (hydrate! #{[:ssr "req-1" "nav-1"]})
      (is (empty? (:active-owners (entry)))
          "precondition: the SSR owner orphaned, so the entry is owner-free")
      (is (= [:rf/default] @rearms) "the rearm was still requested")
      (is (some? (gc-slot)) "…and armed the GC timer, so the timer table is live")
      (is (nil? (poll-slot)) "an owner-free hydrated entry arms no poll")
      (ensure! client-owner)
      (is (= [interval-ms] (mapv #(get-in % [:timers :poll]) (poll-arm-emissions)))
          (str "the fresh-skip emitted the poll arm at the interval — " (pr-str (observed))))
      (is (some? (poll-slot))
          (str "…and a real poll timer is armed — " (pr-str (observed)))))))

;; ---- S4: a real routing round trip — project, hydrate, initial sync, leave --

(deftest s4-routing-round-trip-polls
  (testing "S4 — the ordinary SSR boot: no client ensure runs, the hydrated
            route owner keeps the entry polling, and leaving the route stops it"
    (reg-article!)
    (rf/reg-route :route/article
                  {:params    [:map [:slug :string]]
                   :resources [{:resource :hg/article
                                :params   (fn [route] {:slug (get-in route [:params :slug])})}]}
                  "/articles/:slug")
    (rf/reg-route :route/home {} "/")
    ;; "server": navigate + settle, then project the payload
    (rf/dispatch-sync [:rf.route/navigate {:to :route/article :params {:slug "intro"}}])
    (let [e (entry)]
      (rf/dispatch-sync [:rf.resource.internal/succeeded
                         {:resource/key k :work/id (:current-work e)
                          :generation (:generation e) :data {:title "Intro"}}]))
    (let [payload      {:rf/frame-id   :rf/default
                        :rf/app-db     {}
                        :rf/runtime-db (rf.ssr.payload-policy/project-runtime-db (runtime-db))}
          ;; the token's VALUE is host-dependent, so pin the one that rode the wire
          server-token (get-in payload [:rf/runtime-db :rf.runtime/routing :current :nav-token])]
      (is (some? server-token)
          "precondition: the route and its nav-token ride the wire")
      ;; "client": fresh counters, timers and captures, then hydrate
      (rf.routing/reset-counters!)
      (rf.resources.timers/reset-cache!)
      (reset! scheduled [])
      (reset! rearms [])
      (rf/dispatch-sync [:rf/hydrate payload])
      (let [at-hydrate (armed-by-hydration)]
        (is (= #{[:route :route/article server-token]} (:active-owners (entry)))
            "precondition: the hydrated entry is owned by the ride-through route owner")
        (assert-hydration-armed-poll! at-hydrate)
        (rf/dispatch-sync [:rf.route/handle-url-change "/articles/intro"])
        (is (= server-token (get-in (runtime-db) [:rf.runtime/routing :current :nav-token]))
            "precondition: the initial URL sync is an exact no-op")
        (is (and (some? (:poll at-hydrate)) (some? (poll-slot)))
            (str "the entry is still polling after the initial URL sync — " (pr-str (observed))))
        (rf/dispatch-sync [:rf.route/navigate {:to :route/home}])
        (is (and (some? (:poll at-hydrate)) (nil? (poll-slot)))
            (str "leaving the route released the only owner and stopped polling — "
                 (pr-str (observed))))))))

;; ---- no policy, no poll ----------------------------------------------------

(deftest owned-hydrated-entry-without-a-poll-interval-arms-no-poll
  (testing "a resource declaring no `:poll-interval-ms` arms no poll at
            hydration, while its GC timer is still armed"
    (reg-article! {})
    (hydrate! #{[:route :route/article "nav-1"]})
    (is (= [:rf/default] @rearms) "the rearm was requested")
    (is (some? (gc-slot)) "…and armed the GC timer, so the timer table is live")
    (is (nil? (poll-slot)) "no poll timer without a poll policy")))

;; ---- nothing arms on a server-side hydrate ---------------------------------

(deftest server-side-hydrate-arms-no-poll-timer
  (testing "hydrating onto a `:platform :server` frame — the isomorphic
            loopback shape — requests no rearm and arms no poll timer"
    (reg-article!)
    (let [sfid :hg/server]
      (rf/make-frame {:id sfid :platform :server})
      (hydrate! sfid #{[:route :route/article "nav-1"]})
      (is (some? (entry sfid))
          "the payload DID install, so the empty table below is the gate working")
      (is (empty? @rearms) "no rearm requested by a server-side hydrate")
      (is (nil? (poll-slot sfid)) "no poll timer on a server-side hydrate"))))
