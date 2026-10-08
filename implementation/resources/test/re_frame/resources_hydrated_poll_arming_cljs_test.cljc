(ns re-frame.resources-hydrated-poll-arming-cljs-test
  "A hydrated resource entry that is owned once :rf/hydrate commits starts
  polling on the client (Spec 016 §Polling). The client's ensure onto an
  already-owned entry is a fresh-skip that arms nothing, and on the ordinary
  SSR boot no ensure runs at all, so the hydration rearm arms the poll timer of
  each owned hydrated entry whose resource declares :poll-interval-ms. The
  instruments match `resources-hydrated-gc-arming-cljs-test`; the interval is
  long, and a fire is driven explicitly."
  (:require
   #?(:clj  [clojure.test :refer [deftest is use-fixtures]]
      :cljs [cljs.test :refer-macros [deftest is use-fixtures]])
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

(defn- runtime-db [] (:rf.db/runtime (rf/frame-state-value :rf/default)))
(defn- entry [] (get-in (runtime-db) (rf.resources.state/entry-path k)))

(defn- slot
  "The host timer slot of `kind` for `k`, or nil when none is armed."
  [kind]
  (get @rf.resources.timers/timer-table [:rf/default (rf.resources.state/key-id k) kind]))

(defn- poll-slot [] (slot rf.resources.timers/poll-kind))
(defn- gc-slot [] (slot rf.resources.timers/gc-kind))

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

(defn- hydrate! [owners]
  (let [e (merge (rf.resources.state/empty-entry :hg/article k)
                 {:status :loaded :data {:title "Intro"} :loaded-at 1000
                  :generation 1 :active-owners owners})]
    (rf/dispatch-sync
      [:rf/hydrate {:rf/frame-id   :rf/default
                    :rf/app-db     {}
                    :rf/runtime-db {rf.resources.state/resources-key
                                    {:entries {(rf.resources.state/key-id k) e}}}}])))

(defn- ensure! [owner]
  (rf/dispatch-sync [:rf.resource/ensure
                     {:resource :hg/article :scope :rf.scope/global
                      :params {:slug "intro"} :owner owner}]))

(defn- release! [owner]
  (rf/dispatch-sync [:rf.resource/release-owner {:owner owner}]))

(defn- fire-armed-poll!
  "What the host clock does: run the poll re-check IF a poll timer is armed."
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
  (is (= [true true] [(:rearm-requested? at-hydrate) (some? (:poll at-hydrate))])
      (str "hydration requested the rearm and armed a real poll timer: " (pr-str (observed)))))

(deftest s1-same-route-owner-rides-through-polls
  (reg-article!)
  (let [route-owner [:route :route/article "nav-1"]]
    (hydrate! #{[:ssr "req-1" "nav-1"] route-owner})
    (let [at-hydrate (armed-by-hydration)
          token      (:token (:poll at-hydrate))]
      (assert-hydration-armed-poll! at-hydrate)
      (ensure! route-owner)
      (is (= token (:token (poll-slot))) "the fresh-skip onto the owned entry leaves that poll timer alone")
      (fire-armed-poll!)
      (let [re-armed (poll-slot)]
        (is (and (some? re-armed) (not= token (:token re-armed)))
            (str "the owned poll fire re-armed the next interval: " (pr-str (observed))))
        (release! route-owner)
        (is (nil? (poll-slot)) (str "the last owner's release stopped polling: " (pr-str (observed))))))))

(deftest s2-different-owner-on-client-ensure-polls
  (reg-article!)
  (let [server-owner [:route :route/article "nav-1"]
        client-owner [:route :route/article "nav-2"]]
    (hydrate! #{[:ssr "req-1" "nav-1"] server-owner})
    (assert-hydration-armed-poll! (armed-by-hydration))
    (ensure! client-owner)
    (release! server-owner)
    (is (some? (poll-slot)) (str "still owned by the client owner, so it keeps polling: " (pr-str (observed))))
    (release! client-owner)
    (is (nil? (poll-slot)) (str "the last owner's release stopped polling: " (pr-str (observed))))))

(deftest s3-owner-free-hydrated-entry-polls-only-once-owned
  ;; control: an owner-free hydrated entry arms no poll at hydration; a client
  ;; ensure reviving it arms one through the ordinary fresh-skip path
  (reg-article!)
  (hydrate! #{[:ssr "req-1" "nav-1"]})
  (is (= [true [:rf/default] true nil]
         [(empty? (:active-owners (entry))) @rearms (some? (gc-slot)) (poll-slot)])
      "the SSR owner orphaned; the rearm ran and armed GC, but no poll")
  (ensure! [:route :route/article "nav-2"])
  (is (= [[interval-ms] true] [(mapv #(get-in % [:timers :poll]) (poll-arm-emissions)) (some? (poll-slot))])
      (str "the fresh-skip armed a real poll timer at the interval: " (pr-str (observed)))))

(deftest s4-routing-round-trip-polls
  ;; the ordinary SSR boot: no client ensure runs, the hydrated route owner
  ;; keeps the entry polling, and leaving the route stops it
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
        ;; the token's value is host-dependent, so pin the one that rode the wire
        server-token (get-in payload [:rf/runtime-db :rf.runtime/routing :current :nav-token])]
    (is (some? server-token) "precondition: the route and its nav-token ride the wire")
    ;; "client": fresh counters, timers and captures, then hydrate
    (rf.routing/reset-counters!)
    (rf.resources.timers/reset-cache!)
    (reset! scheduled [])
    (reset! rearms [])
    (rf/dispatch-sync [:rf/hydrate payload])
    (is (= #{[:route :route/article server-token]} (:active-owners (entry)))
        "precondition: the hydrated entry is owned by the ride-through route owner")
    (assert-hydration-armed-poll! (armed-by-hydration))
    (rf/dispatch-sync [:rf.route/handle-url-change "/articles/intro"])
    (is (= [server-token true]
           [(get-in (runtime-db) [:rf.runtime/routing :current :nav-token]) (some? (poll-slot))])
        (str "the initial URL sync is a no-op and the entry still polls: " (pr-str (observed))))
    (rf/dispatch-sync [:rf.route/navigate {:to :route/home}])
    (is (nil? (poll-slot))
        (str "leaving the route released the only owner and stopped polling: " (pr-str (observed))))))

(deftest owned-hydrated-entry-without-a-poll-interval-arms-no-poll
  (reg-article! {})
  (hydrate! #{[:route :route/article "nav-1"]})
  (is (= [[:rf/default] true nil] [@rearms (some? (gc-slot)) (poll-slot)])
      "the rearm ran and armed GC, but no poll without a poll policy"))
