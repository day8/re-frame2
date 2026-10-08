(ns re-frame.resources-hydrated-gc-arming-cljs-test
  "A hydrated resource entry stays GC-collectable when its route owner rides
  through hydration (Spec 016 §Freshness clock contract). Such an entry is
  already owned, so the client's ensure is a fresh-skip that arms nothing (on
  the ordinary SSR boot no ensure runs at all), and a release never starts a
  timer; so a client :rf/hydrate arms each hydrated entry's GC timer, and the
  GC re-check keeps it while owned. Both instruments forward to production:
  the captured fx emissions and the real host timer table. Frames are
  :platform :client, because the JVM host default is :server."
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

(def ^:private scheduled
  "Captured `:rf.resource/schedule-timers` args, in emission order."
  (atom []))

(def ^:private rearms
  "Captured `:rf.resource/hydrate-rearm` frame ids, in emission order."
  (atom []))

(defn- init! []
  (rf/make-frame {:id :rf/default :url-bound? true :platform :client
                  :doc "Hydrated GC arming regression frame."})
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
  ;; forwards through the late-bind hook, so the capture compiles against a
  ;; runtime with no rearm body
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

(defn- gc-slot
  "The host GC timer slot for `k` in `fid`, or nil when none is armed."
  ([] (gc-slot :rf/default))
  ([fid] (get @rf.resources.timers/timer-table
              [fid (rf.resources.state/key-id k) rf.resources.timers/gc-kind])))

(defn- gc-arm-emissions []
  (filterv #(and (= k (:resource/key %))
                 (some? (get-in % [:timers :gc])))
           @scheduled))

(defn- reg-article! []
  (rf/reg-resource :hg/article
                   {:scope         :rf.scope/global
                    :params-schema [:map [:slug :string]]}
                   (fn [{:keys [slug]} _ctx]
                     {:request {:method :get :url (str "/api/articles/" slug)}})))

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

(defn- fire-armed-gc!
  "What the host clock does: run the GC re-check IF a GC timer is armed."
  []
  (when (gc-slot)
    (rf/dispatch-sync [:rf.resource.internal/gc-fired {:resource/key k}])))

(defn- observed []
  {:owners   (:active-owners (entry))
   :status   (:status (entry))
   :gc-slot? (some? (gc-slot))
   :rearms   @rearms
   :gc-emits (mapv #(get-in % [:timers :gc]) (gc-arm-emissions))})

(defn- armed-by-hydration
  "Both instruments, read the moment `:rf/hydrate` returns."
  []
  [(= [:rf/default] @rearms) (some? (gc-slot))])

(defn- assert-hydration-armed-and-collected! [at-hydrate]
  (is (= [[true true] nil] [at-hydrate (entry)])
      (str "hydration requested the rearm and armed a real GC timer, and the "
           "owner-free entry was collected: " (pr-str (observed)))))

(deftest s1-same-route-owner-rides-through
  (reg-article!)
  (let [route-owner [:route :route/article "nav-1"]]
    (hydrate! #{[:ssr "req-1" "nav-1"] route-owner})
    (let [at-hydrate (armed-by-hydration)]
      (ensure! route-owner)
      (release! route-owner)
      (fire-armed-gc!)
      (assert-hydration-armed-and-collected! at-hydrate))))

(deftest s3-owner-free-hydrated-entry-arms-and-collects
  ;; control: a client ensure reviving an owner-free hydrated entry arms
  ;; through the ordinary fresh-skip path
  (reg-article!)
  (let [client-owner [:route :route/article "nav-2"]]
    (hydrate! #{[:ssr "req-1" "nav-1"]})
    (ensure! client-owner)
    (release! client-owner)
    (fire-armed-gc!)
    (is (= [true nil] [(boolean (seq (gc-arm-emissions))) (entry)])
        (str "the fresh-skip armed GC and the entry was collected: " (pr-str (observed))))))

(deftest s4-routing-round-trip
  ;; the ordinary SSR boot: no client ensure runs, and leaving the route
  ;; releases the only owner the entry ever had
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
    (let [at-hydrate (armed-by-hydration)]
      (is (= #{[:route :route/article server-token]} (:active-owners (entry)))
          "precondition: the hydrated entry is owned by the ride-through route owner")
      (rf/dispatch-sync [:rf.route/handle-url-change "/articles/intro"])
      (is (= server-token (get-in (runtime-db) [:rf.runtime/routing :current :nav-token]))
          "precondition: the initial URL sync is an exact no-op")
      (rf/dispatch-sync [:rf.route/navigate {:to :route/home}])
      (fire-armed-gc!)
      (assert-hydration-armed-and-collected! at-hydrate))))

(deftest owned-fire-re-arms-release-keeps-the-timer-next-fire-collects
  ;; the GC re-check is what makes arming at hydration safe
  (reg-article!)
  (let [route-owner [:route :route/article "nav-1"]]
    (hydrate! #{route-owner})
    (let [armed (gc-slot)]
      (is (some? armed) (str "hydration armed the GC timer: " (pr-str (observed))))
      (fire-armed-gc!)
      (let [re-armed (gc-slot)]
        (is (= [#{route-owner} true [300000]]
               [(:active-owners (entry)) (and (some? re-armed) (not= (:token armed) (:token re-armed)))
                (mapv #(get-in % [:timers :gc]) (gc-arm-emissions))])
            (str "an owned fire keeps the entry and re-arms a fresh timer at :gc-after-ms: "
                 (pr-str (observed))))
        (release! route-owner)
        (is (and (some? re-armed) (= (:token re-armed) (:token (gc-slot))))
            "a later release does not restart the armed timer")
        (fire-armed-gc!)
        (is (= [nil nil] [(entry) (gc-slot)])
            (str "the next fire collects the owner-free entry and releases its handle: "
                 (pr-str (observed))))))))

(deftest server-side-hydrate-arms-no-gc-timer
  ;; the isomorphic-loopback shape
  (reg-article!)
  (let [sfid :hg/server]
    (rf/make-frame {:id sfid :platform :server})
    (hydrate! sfid #{[:route :route/article "nav-1"]})
    (is (= [true [] nil] [(some? (entry sfid)) @rearms (gc-slot sfid)])
        "the payload installed, yet no rearm was requested and no GC timer armed")))
