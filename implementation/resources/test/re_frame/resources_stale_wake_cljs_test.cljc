(ns re-frame.resources-stale-wake-cljs-test
  "The stale timer is the freshness wake (Spec 016 §Freshness clock contract,
  §Stale and GC scheduling). A re-check that finds the entry past `:stale-at`
  records the crossed deadline on the entry, so held and newly created
  freshness reads flip with no `:revision` bump, no status change and no
  fetch. A still-fresh re-check, a revival and a hydration each arm the stale
  timer for the window remaining until `:stale-at`. The subs' clock is pinned
  with `with-redefs` on `rf.interop/epoch-now-ms`, and every dispatch carries
  the same instant as its `:rf/time-ms`. The frame is `:platform :client`,
  because the JVM host default is `:server`."
  (:require
   #?(:clj  [clojure.test :refer [deftest is use-fixtures]]
      :cljs [cljs.test :refer-macros [deftest is use-fixtures]])
   [re-frame.core :as rf]
   [re-frame.fx :as rf.fx]
   [re-frame.interop :as rf.interop]
   [re-frame.late-bind :as rf.late-bind]
   [re-frame.resources]
   [re-frame.resources.state :as rf.resources.state]
   [re-frame.resources.test-support]
   [re-frame.resources.timers :as rf.resources.timers]
   [re-frame.http.managed]
   [re-frame.ssr]
   [re-frame.schemas]
   [re-frame.test-support :as rf.test-support]
   [re-frame.trace.tooling :as rf.trace.tooling]
   #?(:clj  [re-frame.substrate.plain-atom :as substrate]
      :cljs [re-frame.adapter.reagent :as substrate])))

(def ^:private t0 1000000)

(def ^:private clock
  "The pinned wall clock, read by the subs and stamped onto every dispatch."
  (atom t0))

(def ^:private http-requests
  "Captured `:rf.http/managed` args, in emission order."
  (atom []))

(def ^:private scheduled
  "Captured `:rf.resource/schedule-timers` args, in emission order. The capture
  arms no host timer, so nothing fires on its own."
  (atom []))

(defn- init! []
  (rf/make-frame {:id :rf/default :platform :client
                  :doc "Stale wake regression frame."})
  (reset! clock t0)
  (reset! http-requests [])
  (reset! scheduled [])
  (rf.fx/reg-fx :rf.http/managed (fn [_ctx args] (swap! http-requests conj args) nil))
  (rf.fx/reg-fx :rf.resource/schedule-timers (fn [_ctx args] (swap! scheduled conj args) nil))
  ;; forwards through the late-bind hook, as the production fx does
  (rf.fx/reg-fx :rf.resource/hydrate-rearm
    (fn [{frame-id :frame} _args]
      (when-let [rearm! (rf.late-bind/get-fn :resources/rearm-after-hydration!)]
        (rearm! frame-id)))))

(defn- pinned-clock [f]
  (with-redefs [rf.interop/epoch-now-ms (fn [] @clock)]
    (try (f) (finally (rf.resources.timers/reset-cache!)))))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter substrate/adapter
     :init-fn init!})
  pinned-clock)

(def ^:private k
  (rf.resources.state/scoped-resource-key :rf.scope/global :sw/article {:slug "w"}))

(def ^:private q {:resource :sw/article :scope :rf.scope/global :params {:slug "w"}})

(defn- reg-article! [stale-after-ms]
  (rf/reg-resource :sw/article
                   {:scope          :rf.scope/global
                    :params-schema  [:map [:slug :string]]
                    :stale-after-ms stale-after-ms
                    :tags           (fn [_params _data] #{[:sw]})}
                   (fn [{:keys [slug]} _ctx]
                     {:request {:method :get :url (str "/a/" slug)}})))

(defn- runtime-db [] (:rf.db/runtime (rf/frame-state-value :rf/default)))
(defn- entry [] (get-in (runtime-db) (rf.resources.state/entry-path k)))

(defn- at! [ms] (reset! clock ms))

(defn- dispatch-at!
  "Dispatch `event` with the pinned clock as its causal `:rf/time-ms`."
  [event]
  (rf/dispatch-sync event {:rf.cofx {:rf/time-ms @clock}}))

(defn- ensure! [owner] (dispatch-at! [:rf.resource/ensure (assoc q :owner owner)]))

(defn- succeed! [data]
  (let [e (entry)]
    (dispatch-at! [:rf.resource.internal/succeeded
                   {:resource/key k :work/id (:current-work e)
                    :generation (:generation e) :data data}])))

(defn- stale-fired! [] (dispatch-at! [:rf.resource.internal/stale-fired {:resource/key k}]))

(defn- loaded-at-t0!
  "Load the article under `owner` at t0, so its `:stale-at` is t0 plus the
  resource's `:stale-after-ms`."
  [owner]
  (ensure! owner)
  (succeed! {:title "W"}))

(deftest wake-flips-held-and-new-freshness-reads-at-the-deadline
  (reg-article! 300)
  (loaded-at-t0! [:app :sw 1])
  (let [held-stale (rf/subscribe [:rf.resource/stale? q])
        held-vm    (rf/subscribe [:rf/resource q])
        before     (entry)]
    (is (= [false false] [@held-stale (:stale? @held-vm)]) "precondition: fresh once loaded")
    (reset! http-requests [])
    (at! (+ t0 600))
    (stale-fired!)
    (is (= [true true true]
           [@held-stale (:stale? @held-vm) @(rf/subscribe [:rf.resource/stale? q])])
        "the held reads and a newly created read report stale")
    (is (= [:loaded (:revision before) []]
           [(:status (entry)) (:revision (entry)) @http-requests])
        "no status change, no :revision bump, no fetch")
    (is (= (+ t0 300) (:stale-wake-at (entry))) "the wake records the deadline it crossed")))

(deftest obsolete-wake-writes-nothing-and-re-arms-for-the-remaining-window
  (reg-article! 300)
  (loaded-at-t0! [:app :sw 1])
  ;; reloaded before the t0 timer's event runs, so the deadline moves to t0+550
  (at! (+ t0 250))
  (dispatch-at! [:rf.resource/refetch q])
  (succeed! {:title "W2"})
  (let [held   (rf/subscribe [:rf.resource/stale? q])
        before (entry)]
    (at! (+ t0 400))
    (reset! scheduled [])
    (stale-fired!)
    (is (false? @held) "the reloaded entry reads fresh")
    (is (= before (entry)) "the durable entry is unchanged")
    (is (= [{:stale 150}] (mapv :timers @scheduled))
        "one :stale-only re-arm, for the window remaining until the new deadline")))

(deftest duplicate-wake-is-a-no-op-commit
  (reg-article! 300)
  (loaded-at-t0! [:app :sw 1])
  (at! (+ t0 600))
  (stale-fired!)
  (let [woken (runtime-db)]
    (at! (+ t0 700))
    (reset! scheduled [])
    (stale-fired!)
    (is (identical? woken (runtime-db))
        "a second wake for the same deadline leaves runtime-db identical")
    (is (= [] @scheduled) "and arms nothing")))

(deftest invalidated-entry-wake-writes-nothing
  ;; an entry stale only by :invalidated-at already committed its invalidation
  (reg-article! 300)
  (loaded-at-t0! [:app :sw 1])
  (dispatch-at! [:rf.resource/release-owner {:owner [:app :sw 1]}])
  (dispatch-at! [:rf.resource/invalidate-tags {:scope :rf.scope/global :tags #{[:sw]}}])
  (let [before (runtime-db)]
    (stale-fired!)
    (is (identical? before (runtime-db)) "the time clause guards the wake")))

(defn- stale-scheduled-delays
  "Run `body-fn`; return the `:delay-ms` of every `:rf.resource/stale-scheduled`
  trace for `k`, in emission order."
  [body-fn]
  (let [seen (atom [])
        id   ::stale-scheduled]
    (rf.trace.tooling/register-listener!
      id (fn [ev] (when (and (= :rf.resource/stale-scheduled (:operation ev))
                             (= k (:resource/key (:tags ev))))
                    (swap! seen conj (:delay-ms (:tags ev))))))
    (try (body-fn) (finally (rf.trace.tooling/unregister-listener! id)))
    @seen))

(defn- hydrate! [stale-at]
  (let [e (merge (rf.resources.state/empty-entry :sw/article k)
                 {:status :loaded :data {:title "W"} :loaded-at (- t0 1000)
                  :stale-at stale-at :generation 1 :active-owners #{}})]
    (rf/dispatch-sync
      [:rf/hydrate {:rf/frame-id   :rf/default
                    :rf/app-db     {}
                    :rf/runtime-db {rf.resources.state/resources-key
                                    {:entries {(rf.resources.state/key-id k) e}}}}])))

(defn- stale-slot []
  (get @rf.resources.timers/timer-table
       [:rf/default (rf.resources.state/key-id k) rf.resources.timers/stale-kind]))

(deftest hydration-arms-the-stale-timer-for-the-remaining-window
  (reg-article! 600000)
  (is (= [400000] (stale-scheduled-delays #(hydrate! (+ t0 400000))))
      "an owner-free hydrated entry arms for the window remaining until :stale-at")
  (is (some? (stale-slot)) "a real host timer is armed"))

(deftest hydration-arms-no-stale-timer-for-an-already-stale-entry
  ;; hydration's own commit renders it stale, so it needs no wake
  (reg-article! 600000)
  (is (= [] (stale-scheduled-delays #(hydrate! (- t0 500)))))
  (is (nil? (stale-slot))))

(deftest revival-re-arms-stale-for-the-remaining-window
  (reg-article! 60000)
  (loaded-at-t0! [:app :sw 1])
  (dispatch-at! [:rf.resource/release-owner {:owner [:app :sw 1]}])
  (at! (+ t0 100))
  (reset! scheduled [])
  ;; a fresh-skip that attaches a new owner to the owner-free entry
  (ensure! [:app :sw 2])
  (is (= [59900] (keep (comp :stale :timers) @scheduled))
      "the revival arms :stale for :stale-at minus now, not :stale-after-ms"))

(deftest optimistic-patch-moves-the-deadline-and-the-wake-follows-it
  (reg-article! 300)
  (rf/reg-mutation :sw/favorite
    {:scope         :rf.scope/global
     :params-schema [:map [:slug :string]]
     :optimistic    (fn [{:keys [slug]}]
                      {{:resource :sw/article :scope :rf.scope/global :params {:slug slug}}
                       (fn [a] (assoc a :favorited true))})}
    (fn [{:keys [slug]} _ctx] {:request {:method :post :url (str "/a/" slug "/fav")}}))
  (loaded-at-t0! [:app :sw 1])
  ;; the apply re-stamps the deadline to t0+500 and arms no timer
  (at! (+ t0 200))
  (reset! http-requests [])
  (dispatch-at! [:rf.mutation/execute {:mutation :sw/favorite :params {:slug "w"} :instance :f1}])
  (let [held          (rf/subscribe [:rf.resource/stale? q])
        applied       (:revision (entry))
        mutation-args (last @http-requests)]
    (is (= (+ t0 500) (:stale-at (entry))) "precondition: the apply moved the deadline")
    ;; the load's own timer fires at the old deadline
    (at! (+ t0 300))
    (reset! scheduled [])
    (stale-fired!)
    (is (= [false [{:stale 200}]] [@held (mapv :timers @scheduled)])
        "a still-fresh re-check re-arms for the patched deadline")
    (at! (+ t0 600))
    (stale-fired!)
    (is (= [true applied] [@held (:revision (entry))])
        "the re-armed timer wakes the held read and leaves the post-apply :revision")
    ;; the wake is no competing write, so a failed mutation restores cleanly
    (reset! http-requests [])
    (dispatch-at! (conj (:on-failure mutation-args)
                        {:status :error :error {:kind :rf.http/http-5xx :status 500}}))
    (is (= [{:title "W"} []] [(:data (entry)) @http-requests])
        "the rollback restores the pre-apply data with no conflict refetch")))
