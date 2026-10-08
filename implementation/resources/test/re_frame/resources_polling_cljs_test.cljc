(ns re-frame.resources-polling-cljs-test
  "Active-owner polling (Spec 016 §Polling). While an entry of a resource
  declaring :poll-interval-ms has an active owner, the runtime refetches it on
  the interval whatever its freshness, records a :poll cause rather than an
  owner, pauses while the tab is hidden, coalesces with work in flight, and
  stops when the last owner leaves. Timer arming is captured and poll-fired
  dispatched directly, so no wall-clock timer runs."
  (:require
   #?(:clj  [clojure.test :refer [deftest is use-fixtures]]
      :cljs [cljs.test :refer-macros [deftest is use-fixtures]])
   [re-frame.core :as rf]
   [re-frame.fx :as rf.fx]
   [re-frame.identity :as rf.identity]
   [re-frame.resources]
   [re-frame.resources.state :as rf.resources.state]
   [re-frame.resources.test-support]
   [re-frame.resources.timers :as rf.resources.timers]
   [re-frame.resources.work-ledger :as rf.resources.work-ledger]
   [re-frame.http.managed]
   [re-frame.schemas]
   [re-frame.test-support :as rf.test-support]
   #?@(:clj  [[re-frame.substrate.plain-atom :as rf.substrate.plain-atom]]
       :cljs [[re-frame.adapter.reagent :as rf.adapter.reagent]])))

(def ^:private aborts (atom []))
(def ^:private scheduled-timers (atom []))
(def ^:private cancelled-poll (atom []))

(defn- capturing-fixture
  "Capture aborts, :rf.resource/schedule-timers and
  :rf.resource/cancel-poll-timers; the fetch itself is a no-op."
  [f]
  (reset! aborts [])
  (reset! scheduled-timers [])
  (reset! cancelled-poll [])
  (rf.fx/reg-fx :rf.http/managed (fn [_ctx _args] nil))
  (rf.fx/reg-fx :rf.http/managed-abort (fn [_ctx work-id] (swap! aborts conj work-id) nil))
  (rf.fx/reg-fx :rf.resource/schedule-timers (fn [_ctx args] (swap! scheduled-timers conj args) nil))
  (rf.fx/reg-fx :rf.resource/cancel-poll-timers (fn [_ctx args] (swap! cancelled-poll conj args) nil))
  ;; ensures here pass an explicit :scope; the resolver's slot stays unwritten
  (rf/reg-resource-scope :t/caller-scope
    {:inputs {:scope [:db [:t/scope]]}}
    (fn [{:keys [scope]} _ctx] scope))
  (f))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    #?(:clj  {:adapter rf.substrate.plain-atom/adapter}
       :cljs {:adapter rf.adapter.reagent/adapter}))
  capturing-fixture)

(defn- runtime-db [] (:rf.db/runtime (rf/frame-state-value :rf/default)))
(defn- entry [scoped-key] (get-in (runtime-db) (rf.resources.state/entry-path scoped-key)))

(def ^:private article-spec-request
  (fn [{:keys [slug]} _ctx]
    {:request {:method :get :url (str "/api/articles/" slug)}}))

(def ^:private scope {:user "u"})

(defn- reg!
  "Register `resource` with `policy`; return its scoped key for slug \"w\"."
  [resource policy]
  (rf/reg-resource resource
                   (merge {:scope         {:from-db :t/caller-scope}
                           :params-schema [:map [:slug :string]]
                           :tags          (fn [{:keys [slug]} _data] #{[:article slug]})}
                          policy)
                   article-spec-request)
  (rf.resources.state/scoped-resource-key scope resource {:slug "w"}))

(defn- ensure! [resource owner]
  (rf/dispatch-sync [:rf.resource/ensure
                     {:resource resource :scope scope :params {:slug "w"} :owner owner}]))

(defn- reply! [event-id scoped-key extra]
  (let [e (entry scoped-key)]
    (rf/dispatch-sync [event-id (merge {:resource/key scoped-key :work/id (:current-work e)
                                        :generation (:generation e)}
                                       extra)])))

(defn- succeed! [scoped-key data]
  (reply! :rf.resource.internal/succeeded scoped-key {:data data}))

(defn- loaded!
  "Register a polling `resource` (plus `policy`) and load it under `owner`."
  ([resource owner] (loaded! resource owner {}))
  ([resource owner policy]
   (let [k (reg! resource (merge {:poll-interval-ms 5000} policy))]
     (ensure! resource owner)
     (succeed! k {:title "W"})
     k)))

(defn- poll-fired!
  ([scoped-key] (poll-fired! scoped-key false))
  ([scoped-key hidden?]
   (rf/dispatch-sync [:rf.resource.internal/poll-fired
                      {:resource/key scoped-key :hidden? hidden?}])))

(defn- last-schedule-for [scoped-key]
  (last (filter #(= scoped-key (:resource/key %)) @scheduled-timers)))

(defn- poll-delay [scoped-key] (get-in (last-schedule-for scoped-key) [:timers :poll]))

(defn- scheduled-for? [scoped-key] (boolean (some #(= scoped-key (:resource/key %)) @scheduled-timers)))

(deftest poll-enabled-active-owner-arms-poll-timer-on-settle
  (let [k (loaded! :pl/poll [:route :r 1])]
    (is (= 5000 (poll-delay k)) "an owned settle arms :poll at :poll-interval-ms")))

(deftest no-poll-policy-arms-no-poll-timer
  (let [k (reg! :pl/nopoll {:stale-after-ms 1000})]
    (ensure! :pl/nopoll [:route :r 1])
    (succeed! k {:title "W"})
    (is (= [nil 1000] ((juxt :poll :stale) (:timers (last-schedule-for k))))
        "no poll delay without a poll policy; the stale delay still arms")))

(deftest fresh-skip-re-arms-polling-on-new-owner
  ;; A poll never pins an owner-free entry, so an entry that settles
  ;; owner-free arms no poll; a later ensure from a new owner is a fresh-skip
  ;; that must arm it, as the success path would.
  (let [k (reg! :fs/poll {:poll-interval-ms 5000 :gc-after-ms 9000})]
    (ensure! :fs/poll [:app :x 1])
    (rf/dispatch-sync [:rf.resource/release-owner {:owner [:app :x 1]}])
    (succeed! k {:title "W"})
    (is (= [true nil 9000] [(empty? (:active-owners (entry k))) (poll-delay k)
                            (get-in (last-schedule-for k) [:timers :gc])])
        "an owner-free settle arms GC but no poll")
    (reset! scheduled-timers [])
    (ensure! :fs/poll [:route :r 2])
    (is (= [:loaded nil #{[:route :r 2]}] ((juxt :status :current-work :active-owners) (entry k)))
        "served from cache with the new owner attached")
    (is (= 5000 (poll-delay k)) "the fresh-skip armed the poll")))

(deftest poll-tick-refetches-unconditionally-and-rearms
  ;; the entry is fresh (no stale policy): the interval, not :stale?, is the cadence
  (let [k          (loaded! :pt/poll [:route :r 1])
        gen-before (:generation (entry k))]
    (reset! scheduled-timers [])
    (poll-fired! k)
    (is (= [:fetching {:title "W"} (inc gen-before)] ((juxt :status :data :generation) (entry k)))
        "a background refetch on a new generation, keeping the data")
    (is (= 5000 (poll-delay k)) "the tick re-arms the next poll")))

(deftest poll-tick-records-poll-cause-never-owner
  (let [k (loaded! :pc/poll [:route :r 1])]
    (poll-fired! k)
    (let [e (entry k)]
      (is (= [#{[:route :r 1]} true]
             [(:active-owners e)
              (boolean (some #{:poll} (:causes (rf.resources.work-ledger/get-record (runtime-db) (:current-work e)))))])
          "the refetch records a :poll cause and attaches no owner"))))

(deftest last-owner-release-cancels-poll-timer
  (let [cancelled-all (atom [])
        _             (rf.fx/reg-fx :rf.resource/cancel-timers (fn [_ctx args] (swap! cancelled-all conj args) nil))
        k             (loaded! :or/poll [:app :x 1] {:gc-after-ms 9000})
        names-k?      (fn [args] (some #{k} (:resource/keys args)))]
    (reset! cancelled-poll [])
    (rf/dispatch-sync [:rf.resource/release-owner {:owner [:app :x 1]}])
    (is (= [true true false]
           [(empty? (:active-owners (entry k))) (boolean (some names-k? @cancelled-poll))
            (boolean (some names-k? @cancelled-all))])
        "the release cancels the poll timer only; the stale/GC timers stay armed")))

(deftest poll-tick-on-owner-free-entry-stops-no-refetch-no-rearm
  ;; the advisory re-check still closes a tick that races the proactive cancel
  (let [k (loaded! :os/poll [:app :x 1])]
    (rf/dispatch-sync [:rf.resource/release-owner {:owner [:app :x 1]}])
    (reset! scheduled-timers [])
    (poll-fired! k)
    (is (= [:loaded nil false] [(:status (entry k)) (:current-work (entry k)) (scheduled-for? k)])
        "no refetch and no re-arm")))

(deftest hidden-tab-pauses-tick-but-rearms
  (let [k (loaded! :hd/poll [:route :r 1])]
    (reset! scheduled-timers [])
    (poll-fired! k true)
    (is (= [:loaded nil 5000] [(:status (entry k)) (:current-work (entry k)) (poll-delay k)])
        "a hidden tick does not refetch, but re-arms so polling resumes")))

(deftest poll-tick-coalesces-with-live-in-flight-work
  ;; a slow endpoint never stacks overlapping requests
  (let [k (loaded! :if/poll [:route :r 1])]
    (reset! aborts [])
    (poll-fired! k)
    (let [gen-after-1 (:generation (entry k))
          wid-after-1 (:current-work (entry k))]
      (is (= :fetching (:status (entry k))) "precondition: the first tick is in flight")
      (reset! scheduled-timers [])
      (poll-fired! k)
      (is (= [gen-after-1 wid-after-1 [] 5000]
             [(:generation (entry k)) (:current-work (entry k)) @aborts (poll-delay k)])
          "the second tick joins the same work with no abort churn, and re-arms"))))

(deftest background-poll-failure-keeps-prior-data-and-keeps-polling
  (let [k (loaded! :bf/poll [:route :r 1])]
    (poll-fired! k)
    (reply! :rf.resource.internal/failed k {:error {:kind :rf.http/server-error :reason :transient-503}})
    (is (= [:loaded {:title "W"} true] [(:status (entry k)) (:data (entry k)) (some? (:refresh-error (entry k)))])
        "a failed poll keeps the prior data and records :refresh-error")
    (poll-fired! k)
    (is (= :fetching (:status (entry k))) "the next tick still refetches")))

(deftest poll-timer-kind-is-cancelled-with-the-key
  ;; so entry removal and clear-scope stop polling
  (let [slot [:pk/frame (rf.identity/canonical-bytes [:s :pk/r {}]) rf.resources.timers/poll-kind]]
    (rf.resources.timers/schedule! :pk/frame [:s :pk/r {}] rf.resources.timers/poll-kind 60000)
    (is (contains? @rf.resources.timers/timer-table slot) "precondition: the poll timer is armed")
    (rf.resources.timers/cancel-for-key! :pk/frame [:s :pk/r {}])
    (is (not (contains? @rf.resources.timers/timer-table slot)))))
