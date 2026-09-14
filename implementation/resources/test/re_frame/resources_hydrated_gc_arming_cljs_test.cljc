(ns re-frame.resources-hydrated-gc-arming-cljs-test
  "PROBE for rf2-omahf — does a hydrated entry whose route owner rides through
  hydration ever arm a GC timer on the client, and is it ever collected?

  Observed through BOTH instruments: the `:rf.resource/schedule-timers`
  emissions (captured) AND the real host timer side table (the captured fx
  forwards to the production handler, so an arming lands in
  `rf.resources.timers/timer-table` exactly as it would in an app)."
  (:require
   #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
      :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
   [re-frame.core :as rf]
   [re-frame.fx :as rf.fx]
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

(def ^:private scheduled (atom []))

(defn- init! []
  (rf/make-frame {:id :rf/default :url-bound? true
                  :doc "Hydrated GC arming probe frame."})
  (rf.routing/reset-counters!)
  (rf.resources.route/install-routing-integration!)
  (reset! scheduled [])
  (rf.fx/reg-fx :rf.http/managed (fn [_ctx _args] nil))
  (rf.fx/reg-fx :rf.nav/push-url {:platforms #{:server :client}} (fn [_ _] nil))
  (rf.fx/reg-fx :rf.resource/schedule-timers
    (fn [ctx args]
      (swap! scheduled conj args)
      (rf.resources.timers/schedule-timers-handler ctx args))))

(defn- cancel-real-timers [f]
  (try (f) (finally (rf.resources.timers/reset-cache!))))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter substrate/adapter
     :init-fn init!})
  cancel-real-timers)

(defn- runtime-db [] (:rf.db/runtime (rf/frame-state-value :rf/default)))

(defn- entry [k] (get-in (runtime-db) (rf.resources.state/entry-path k)))

(def ^:private k
  (rf.resources.state/scoped-resource-key :rf.scope/global :hg/article {:slug "intro"}))

(defn- reg-article! []
  (rf/reg-resource :hg/article
                   {:scope         :rf.scope/global
                    :params-schema [:map [:slug :string]]}
                   (fn [{:keys [slug]} _ctx]
                     {:request {:method :get :url (str "/api/articles/" slug)}})))

(defn- gc-armed? []
  (contains? @rf.resources.timers/timer-table
             [:rf/default (rf.resources.state/key-id k) rf.resources.timers/gc-kind]))

(defn- gc-arm-emissions []
  (filterv #(and (= k (:resource/key %))
                 (some? (get-in % [:timers :gc])))
           @scheduled))

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

(defn- fire-armed-gc!
  "What the host clock would do: fire the GC timer IF one is armed."
  []
  (when (gc-armed?)
    (rf/dispatch-sync [:rf.resource.internal/gc-fired {:resource/key k}])))

(defn- obs [label]
  (println "OBS" label
           {:owners    (:active-owners (entry k))
            :status    (:status (entry k))
            :present?  (some? (entry k))
            :gc-armed? (gc-armed?)
            :gc-emits  (mapv #(get-in % [:timers :gc]) (gc-arm-emissions))}))

;; ---- S1: THE CLAIM — same route owner rides through, client ensures with it

(deftest s1-same-owner-rides-through
  (reg-article!)
  (let [route-owner [:route :route/article "nav-1"]]
    (hydrate! #{[:ssr "req-1" "nav-1"] route-owner})
    (obs "S1 after-hydrate")
    (ensure! route-owner)
    (obs "S1 after-client-ensure-same-owner")
    (rf/dispatch-sync [:rf.resource/release-owner {:owner route-owner}])
    (obs "S1 after-release")
    (fire-armed-gc!)
    (obs "S1 after-fire-armed-gc")
    (testing "spec 016: GC arms on client ownership; the owner-free entry is collected"
      (is (seq (gc-arm-emissions)) "a GC timer was armed at some point")
      (is (nil? (entry k)) "the owner-free entry was collected"))))

;; ---- S2: the brief's control — a DIFFERENT owner on the client ensure

(deftest s2-different-owner-on-client-ensure
  (reg-article!)
  (let [server-owner [:route :route/article "nav-1"]
        client-owner [:route :route/article "nav-2"]]
    (hydrate! #{[:ssr "req-1" "nav-1"] server-owner})
    (obs "S2 after-hydrate")
    (ensure! client-owner)
    (obs "S2 after-client-ensure-different-owner")
    (rf/dispatch-sync [:rf.resource/release-owner {:owner server-owner}])
    (obs "S2 after-release-server-owner")
    (rf/dispatch-sync [:rf.resource/release-owner {:owner client-owner}])
    (obs "S2 after-release-client-owner")
    (fire-armed-gc!)
    (obs "S2 after-fire-armed-gc")
    (testing "spec 016: GC arms on client ownership; the owner-free entry is collected"
      (is (seq (gc-arm-emissions)) "a GC timer was armed at some point")
      (is (nil? (entry k)) "the owner-free entry was collected"))))

;; ---- S3: positive instrument control — hydrated OWNER-FREE (SSR owner only)

(deftest s3-owner-free-hydrated-entry-arms-and-collects
  (reg-article!)
  (let [client-owner [:route :route/article "nav-2"]]
    (hydrate! #{[:ssr "req-1" "nav-1"]})
    (obs "S3 after-hydrate")
    (ensure! client-owner)
    (obs "S3 after-client-ensure")
    (rf/dispatch-sync [:rf.resource/release-owner {:owner client-owner}])
    (obs "S3 after-release")
    (fire-armed-gc!)
    (obs "S3 after-fire-armed-gc")
    (testing "the gate is satisfied, so the instruments must see arm + collect"
      (is (seq (gc-arm-emissions)) "a GC timer was armed")
      (is (nil? (entry k)) "the owner-free entry was collected"))))

;; ---- S4: a real routing round trip — project, hydrate, initial sync, leave

(deftest s4-routing-round-trip
  (reg-article!)
  (rf/reg-route :route/article
                {:params    [:map [:slug :string]]
                 :resources [{:resource :hg/article
                              :params   (fn [route] {:slug (get-in route [:params :slug])})}]}
                "/articles/:slug")
  (rf/reg-route :route/home {} "/")
  ;; "server": navigate + settle, then project the payload
  (rf/dispatch-sync [:rf.route/navigate {:to :route/article :params {:slug "intro"}}])
  (let [e (entry k)]
    (rf/dispatch-sync [:rf.resource.internal/succeeded
                       {:resource/key k :work/id (:current-work e)
                        :generation (:generation e) :data {:title "Intro"}}]))
  (let [server-token (get-in (runtime-db) [:rf.runtime/routing :current :nav-token])
        payload      {:rf/frame-id   :rf/default
                      :rf/app-db     {}
                      :rf/runtime-db (rf.ssr.payload-policy/project-runtime-db (runtime-db))}]
    (println "OBS S4 server-token" server-token
             "wire-routing" (get-in payload [:rf/runtime-db :rf.runtime/routing]))
    ;; "client": fresh counters + timers, then hydrate
    (rf.routing/reset-counters!)
    (rf.resources.timers/reset-cache!)
    (reset! scheduled [])
    (rf/dispatch-sync [:rf/hydrate payload])
    (println "OBS S4 client-current-after-hydrate"
             (get-in (runtime-db) [:rf.runtime/routing :current]))
    (obs "S4 after-hydrate")
    (rf/dispatch-sync [:rf.route/handle-url-change "/articles/intro"])
    (println "OBS S4 current-after-initial-sync"
             (get-in (runtime-db) [:rf.runtime/routing :current :nav-token]))
    (obs "S4 after-initial-url-sync")
    (rf/dispatch-sync [:rf.route/navigate {:to :route/home}])
    (obs "S4 after-navigate-away")
    (fire-armed-gc!)
    (obs "S4 after-fire-armed-gc")
    (testing "spec 016: the owner-free hydrated entry is eventually collected"
      (is (seq (gc-arm-emissions)) "a GC timer was armed at some point")
      (is (nil? (entry k)) "the owner-free entry was collected"))))
