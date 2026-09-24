(ns re-frame.resources-hydrated-gc-arming-cljs-test
  "A hydrated resource entry stays GC-collectable when its route
  owner rides through hydration.

  THE HAZARD. Besides hydration itself, the only client paths that arm GC on
  a settled entry are a load settle, a
  mutation write, and a fresh-skip ONTO AN OWNER-FREE entry. A hydrated entry
  whose route owner rode the wire is already owned, so the client's ensure is
  a fresh-skip onto an owned entry (it arms nothing), and releasing that owner
  arms nothing either (a release never starts a timer). Were hydration to arm
  no timer, no GC re-check would ever run and the entry would live for the
  session. S4 is the ordinary SSR boot: the
  route and its nav-token ride the payload, the client's initial URL sync is
  an exact no-op, and no client ensure runs at all — so arming on the
  fresh-skip path could not reach it.

  THE RULE (Spec 016 §Freshness clock contract). After the client commits
  `:rf/hydrate`, the resources artefact arms the GC timer of each hydrated
  entry — no stale timer; the poll timer an OWNED entry of a polling resource
  also gets is pinned by `resources-hydrated-poll-arming-cljs-test`
  — through the late-bound
  `:rf.resource/hydrate-rearm` fx, on the `:rf.machine/hydrate-rearm`
  precedent. The GC re-check does the rest: a fire while the entry is owned
  or in flight RE-ARMS, a later release never restarts the armed timer, and
  a fire on an owner-free, idle entry collects it. A server-side hydrate arms
  nothing, and no timer rides the wire.

  TWO INSTRUMENTS, each forwarding to production so an arming lands exactly
  as it would in an app: the captured fx emissions (`:rf.resource/hydrate-rearm`
  and `:rf.resource/schedule-timers`) and the real host timer side table
  (`rf.resources.timers/timer-table`). S3 is the positive control for both:
  an owner-free hydrated entry that a client ensure revives arms through the
  ordinary fresh-skip path and is collected.

  Frames are `:platform :client`: on the JVM the host-wide platform default is
  `:server`, and the hydrate handler requests host work only on a client."
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
  ;; Forwards through the late-bind hook rather than a var, so the capture
  ;; compiles against a runtime with no rearm body — where the hydrate handler
  ;; requests no rearm in the first place.
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
  {:rearm-requested? (= [:rf/default] @rearms)
   :gc-armed?        (some? (gc-slot))})

(defn- assert-hydration-armed-and-collected! [at-hydrate]
  (is (:rearm-requested? at-hydrate)
      (str "the client's :rf/hydrate commit requested the GC rearm — " (pr-str (observed))))
  (is (:gc-armed? at-hydrate)
      "…and a real GC timer was armed by the time hydration returned")
  (is (nil? (entry))
      (str "the owner-free entry was collected — " (pr-str (observed)))))

;; ---- S1: the same route owner rides through; the client ensures with it -----

(deftest s1-same-route-owner-rides-through
  (testing "S1 — a fresh-skip onto an owned entry arms nothing, so only the
            hydration rearm can make the entry collectable"
    (reg-article!)
    (let [route-owner [:route :route/article "nav-1"]]
      (hydrate! #{[:ssr "req-1" "nav-1"] route-owner})
      (let [at-hydrate (armed-by-hydration)]
        (ensure! route-owner)
        (release! route-owner)
        (fire-armed-gc!)
        (assert-hydration-armed-and-collected! at-hydrate)))))

;; ---- S2: a DIFFERENT owner on the client ensure ----------------------------

(deftest s2-different-owner-on-client-ensure
  (testing "S2 — a newly attached owner onto an already-owned entry arms
            nothing either (the fresh-skip gate needs a previously owner-free
            entry)"
    (reg-article!)
    (let [server-owner [:route :route/article "nav-1"]
          client-owner [:route :route/article "nav-2"]]
      (hydrate! #{[:ssr "req-1" "nav-1"] server-owner})
      (let [at-hydrate (armed-by-hydration)]
        (ensure! client-owner)
        (release! server-owner)
        (release! client-owner)
        (fire-armed-gc!)
        (assert-hydration-armed-and-collected! at-hydrate)))))

;; ---- S3: positive instrument control — hydrated OWNER-FREE -----------------

(deftest s3-owner-free-hydrated-entry-arms-and-collects
  (testing "S3 — control: an owner-free hydrated entry revived by a client
            ensure arms through the ordinary fresh-skip path, so both
            instruments see an arm and a collect"
    (reg-article!)
    (let [client-owner [:route :route/article "nav-2"]]
      (hydrate! #{[:ssr "req-1" "nav-1"]})
      (ensure! client-owner)
      (release! client-owner)
      (fire-armed-gc!)
      (is (seq (gc-arm-emissions))
          (str "the fresh-skip emitted a GC arm — " (pr-str (observed))))
      (is (nil? (entry))
          (str "the owner-free entry was collected — " (pr-str (observed)))))))

;; ---- S4: a real routing round trip — project, hydrate, initial sync, leave --

(deftest s4-routing-round-trip
  (testing "S4 — the ordinary SSR boot: no client ensure runs, and leaving the
            route releases the only owner the entry ever had"
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
          ;; The token's VALUE is host-dependent (the counter a host has
          ;; consumed before this navigate), so the preconditions pin the
          ;; token that rode the wire, never a literal.
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
        (rf/dispatch-sync [:rf.route/handle-url-change "/articles/intro"])
        (is (= server-token (get-in (runtime-db) [:rf.runtime/routing :current :nav-token]))
            "precondition: the initial URL sync is an exact no-op")
        (rf/dispatch-sync [:rf.route/navigate {:to :route/home}])
        (fire-armed-gc!)
        (assert-hydration-armed-and-collected! at-hydrate)))))

;; ---- D: an owned fire re-arms; release does not restart; the next fire collects

(deftest owned-fire-re-arms-release-keeps-the-timer-next-fire-collects
  (testing "the GC re-check is what makes arming at hydration safe: a fire
            while owned keeps the entry and re-arms, a release leaves that
            armed timer alone, and the next fire collects the now owner-free
            entry"
    (reg-article!)
    (let [route-owner [:route :route/article "nav-1"]]
      (hydrate! #{route-owner})
      (let [armed (gc-slot)]
        (is (some? armed) (str "hydration armed the GC timer — " (pr-str (observed))))
        (fire-armed-gc!)
        (is (= #{route-owner} (:active-owners (entry)))
            "an owned entry is NOT collected when its GC timer fires")
        (let [re-armed (gc-slot)]
          (is (and (some? armed) (some? re-armed)
                   (not= (:token armed) (:token re-armed)))
              (str "the owned fire RE-ARMED a fresh GC timer — " (pr-str (observed))))
          (is (= [300000] (mapv #(get-in % [:timers :gc]) (gc-arm-emissions)))
              "…through the reschedule-on-skip emission, at the resource's :gc-after-ms")
          (release! route-owner)
          (is (and (some? re-armed) (= (:token re-armed) (:token (gc-slot))))
              "a later release does not restart the armed timer")
          (fire-armed-gc!)
          (is (and (some? re-armed) (nil? (entry)))
              (str "the next fire collects the owner-free, idle entry — " (pr-str (observed))))
          (is (nil? (gc-slot)) "…and releases its timer handle"))))))

;; ---- nothing arms on a server-side hydrate ---------------------------------

(deftest server-side-hydrate-arms-no-gc-timer
  (testing "hydrating onto a `:platform :server` frame — the isomorphic
            loopback shape — requests no rearm and arms no host timer"
    (reg-article!)
    (let [sfid :hg/server]
      (rf/make-frame {:id sfid :platform :server})
      (hydrate! sfid #{[:route :route/article "nav-1"]})
      (is (some? (entry sfid))
          "the payload DID install, so the empty table below is the gate working")
      (is (empty? @rearms) "no rearm requested by a server-side hydrate")
      (is (nil? (gc-slot sfid)) "no GC timer on a server-side hydrate"))))
