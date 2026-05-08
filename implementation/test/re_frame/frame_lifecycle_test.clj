(ns re-frame.frame-lifecycle-test
  "JVM lifecycle tests for re-frame.frame. The smoke suite covers the
  basics (frame-lifecycle, frame-multi-instance, destroy-frame-signals-
  active-machines); this file exercises the edge cases of reg-frame,
  make-frame, destroy-frame!, ensure-default-frame!, and the surgical-
  update path. Per Spec 002 §Frames, §Destroy, §reg-frame is atomic,
  §Re-registration — surgical update, §Per-instance frames."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.registrar :as registrar]
            [re-frame.flows :as flows]))

(defn reset-runtime [test-fn]
  (registrar/clear-all!)
  (reset! frame/frames {})
  (reset! flows/flows {})
  (rf/init!)
  ;; Framework events / fx / subs are registered at namespace-load time;
  ;; clear-all! wiped them. Reload to resurrect the framework registrations.
  (require 're-frame.routing :reload)
  (require 're-frame.ssr     :reload)
  (require 're-frame.machines :reload)
  (test-fn))

(use-fixtures :each reset-runtime)

;; ---- Spec 002 §Re-registration — surgical update -------------------------

(deftest reg-frame-surgical-update-preserves-runtime-state
  (testing "re-registering a live frame replaces metadata but preserves app-db, sub-cache, and queue"
    ;; Set up a frame with live state.
    (rf/reg-frame :tenant {:doc "v1 metadata" :preset :default})
    (rf/reg-event-db :seed (fn [_ [_ n]] {:n n :payload "live"}))
    (rf/reg-sub :n (fn [db _] (:n db)))
    ;; Populate app-db.
    (rf/dispatch-sync [:seed 42] {:frame :tenant})
    ;; Pin a sub in the cache.
    (let [pinned         (rf/subscribe :tenant [:n])
          orig-record    (frame/frame :tenant)
          orig-app-db    (:app-db orig-record)
          orig-sub-cache (:sub-cache orig-record)
          orig-router    (:router orig-record)
          orig-lifecycle (:lifecycle orig-record)]
      (is (= 42 @pinned) "sub returns the seeded value before re-registration")
      (is (contains? @orig-sub-cache [:n]) "sub-cache holds the pinned entry")

      ;; Surgical update: re-register with new metadata.
      (rf/reg-frame :tenant {:doc          "v2 metadata"
                             :fx-overrides {:http :stub.v2}})

      (let [new-record (frame/frame :tenant)]
        ;; Replaceable slot — config — is the new value.
        (is (= "v2 metadata" (get-in new-record [:config :doc]))
            ":config slot was replaced")
        (is (= {:http :stub.v2} (get-in new-record [:config :fx-overrides]))
            "new :fx-overrides are visible")
        ;; Preserved slots — app-db, sub-cache, router, lifecycle — are
        ;; the SAME mutable objects (identical?).
        (is (identical? orig-app-db    (:app-db new-record))
            "app-db container was preserved across re-registration")
        (is (identical? orig-sub-cache (:sub-cache new-record))
            "sub-cache atom was preserved")
        (is (identical? orig-router    (:router new-record))
            "router atom was preserved")
        (is (identical? orig-lifecycle (:lifecycle new-record))
            ":lifecycle map was preserved (not bumped to a new :created-at)"))

      ;; The seeded app-db value survives the re-registration.
      (is (= {:n 42 :payload "live"} (rf/get-frame-db :tenant))
          "app-db value survived the surgical update")
      ;; The pinned reaction still resolves to the same value.
      (is (= 42 @pinned)
          "pinned subscription continues to read the live app-db"))))

;; ---- Spec 002 §Destroy — pending events ----------------------------------

(deftest destroy-frame-discards-pending-events
  (testing "events queued via async dispatch are not processed once the frame is destroyed"
    (rf/reg-frame :worker {:doc "worker frame"})
    (let [side-effects (atom 0)
          traces       (atom [])]
      (rf/reg-event-db :tick (fn [db _] (swap! side-effects inc) db))
      ;; Async dispatch enqueues without draining synchronously — the
      ;; drain runs on the next tick. Destroy before that tick fires.
      (rf/register-trace-cb! ::pending (fn [ev] (swap! traces conj ev)))
      (rf/dispatch [:tick] {:frame :worker})
      (rf/dispatch [:tick] {:frame :worker})
      ;; Without draining, the queue holds the pending events.
      (let [router (:router (frame/frame :worker))
            queue  (:queue @router)]
        (is (= 2 (count queue))
            "two events are queued and have NOT yet been processed"))
      ;; Destroy the frame. Per Spec 002 §Destroy: pending events drain
      ;; or get discarded; either way they MUST NOT corrupt state on a
      ;; gone frame, and any later attempt to dispatch traces
      ;; :rf.error/frame-destroyed.
      (rf/destroy-frame :worker)
      (rf/remove-trace-cb! ::pending)

      ;; The frame is gone.
      (is (nil? (frame/frame :worker))
          "destroy-frame! removed the frame from the registry")
      ;; A subsequent dispatch on the destroyed frame must trace
      ;; :rf.error/frame-destroyed (the recovery path; events do not
      ;; silently land in a void).
      (let [t-after (atom [])]
        (rf/register-trace-cb! ::after (fn [ev] (swap! t-after conj ev)))
        (rf/dispatch-sync [:tick] {:frame :worker})
        (rf/remove-trace-cb! ::after)
        (is (some #(= :rf.error/frame-destroyed (:operation %)) @t-after)
            "post-destroy dispatch traces :rf.error/frame-destroyed")
        ;; The handler did NOT run for the post-destroy attempt — the
        ;; counter stays bounded by what (if anything) drained.
        (is (<= @side-effects 2)
            "the handler did not run on the destroyed frame")))))

;; ---- Spec 002 §Destroy — machine cascade ---------------------------------

(deftest destroy-frame-cascade-emits-per-active-machine
  (testing "destroy emits :rf.machine/destroyed-on-frame-exit for EACH active machine snapshot"
    (rf/reg-frame :ten {:doc "tenant"})
    ;; Seed three machine snapshots directly into app-db so we don't
    ;; depend on a full machine-runtime invocation.
    (rf/reg-event-db :seed-machines
      (fn [db _]
        (assoc db :rf/machines
               {:flow/login    {:state :authed     :data {:user "a"}}
                :flow/checkout {:state :reviewing  :data {:cart [1 2]}}
                :flow/billing  {:state :collected  :data {}}})))
    (rf/dispatch-sync [:seed-machines] {:frame :ten})
    (let [traces (atom [])]
      (rf/register-trace-cb! ::cascade (fn [ev] (swap! traces conj ev)))
      (rf/destroy-frame :ten)
      (rf/remove-trace-cb! ::cascade)
      (let [cascade (filter #(= :rf.machine/destroyed-on-frame-exit (:operation %))
                            @traces)]
        (is (= 3 (count cascade))
            "one trace event per active machine snapshot")
        (is (every? #(= :ten (:frame (:tags %))) cascade)
            "all events carry the destroyed frame's id")
        (is (= #{:flow/login :flow/checkout :flow/billing}
               (set (map #(:machine-id (:tags %)) cascade)))
            "every machine-id is signalled exactly once")
        (is (= #{:authed :reviewing :collected}
               (set (map #(:last-state (:tags %)) cascade)))
            "each event carries that machine's last-state from the snapshot"))
      ;; A :frame/destroyed trace is also emitted (after the cascade).
      (is (some #(= :frame/destroyed (:operation %)) @traces)
          "expected a :frame/destroyed trace"))))

;; ---- Spec 002 §Destroy — live subscribers --------------------------------

(deftest destroy-frame-with-live-subscribers
  (testing "destroy clears the sub-cache, fires on-dispose, and post-destroy subs return nil"
    (rf/reg-frame :live {:doc "live"})
    (rf/reg-event-db :seed (fn [_ _] {:answer 7}))
    (rf/reg-sub :answer (fn [db _] (:answer db)))
    (rf/dispatch-sync [:seed] {:frame :live})
    (let [r1            (rf/subscribe :live [:answer])
          r2            (rf/subscribe :live [:answer])
          dispose-fired (atom 0)]
      ;; Pin the cache entry and attach an on-dispose hook so we can
      ;; observe destroy clearing it.
      (re-frame.interop/add-on-dispose! r1 (fn [] (swap! dispose-fired inc)))
      (is (= 7 @r1) "sub reads the seeded value before destroy")
      (is (identical? r1 r2) "the cache returns the same reaction on repeat subscribe")
      (let [cache (:sub-cache (frame/frame :live))]
        (is (contains? @cache [:answer]) "cache holds the entry"))

      ;; Capture traces and destroy.
      (let [traces (atom [])]
        (rf/register-trace-cb! ::sub-destroy (fn [ev] (swap! traces conj ev)))
        (rf/destroy-frame :live)
        (rf/remove-trace-cb! ::sub-destroy)
        (is (= 1 @dispose-fired)
            "on-dispose hook fired exactly once when destroy walked the sub-cache")
        (is (some #(= :frame/destroyed (:operation %)) @traces)
            "expected :frame/destroyed trace"))

      ;; Post-destroy subscribe returns nil with :replaced-with-default.
      (let [t-after (atom [])]
        (rf/register-trace-cb! ::post (fn [ev] (swap! t-after conj ev)))
        (let [r3 (rf/subscribe :live [:answer])]
          (is (nil? r3)
              "subscribe against a destroyed frame returns nil"))
        (rf/remove-trace-cb! ::post)
        (is (some (fn [ev]
                    (and (= :rf.error/frame-destroyed (:operation ev))
                         (= :replaced-with-default (:recovery ev))))
                  @t-after)
            "expected :rf.error/frame-destroyed trace with :replaced-with-default recovery")))))

;; ---- Spec 002 §:rf/default — ensure-default-frame! idempotence -----------

(deftest ensure-default-frame-is-idempotent
  (testing "calling ensure-default-frame! more than once does not duplicate or replace the default frame"
    ;; reset-runtime already called rf/init! → ensure-default-frame!.
    (let [original (frame/frame :rf/default)]
      (is (some? original) ":rf/default exists after init!")
      ;; Repeated calls should be no-ops on the already-registered frame.
      (frame/ensure-default-frame!)
      (frame/ensure-default-frame!)
      (frame/ensure-default-frame!)
      (let [now (frame/frame :rf/default)]
        (is (identical? (:app-db    original) (:app-db    now))
            ":rf/default's app-db container was NOT replaced")
        (is (identical? (:sub-cache original) (:sub-cache now))
            ":rf/default's sub-cache was NOT replaced")
        (is (identical? (:router    original) (:router    now))
            ":rf/default's router was NOT replaced")
        (is (= (:created-at (:lifecycle original))
               (:created-at (:lifecycle now)))
            ":rf/default's :created-at is unchanged across repeat calls"))
      ;; Only one entry for :rf/default lives in the frames atom.
      (is (= 1 (count (filter #{:rf/default} (keys @frame/frames))))
          "the frames map has a single :rf/default entry"))))

;; ---- Spec 002 §Per-instance frames --------------------------------------

(deftest make-frame-gensyms-id-and-isolates-fx-overrides
  (testing "make-frame returns gensym'd ids; per-frame :fx-overrides isolate effect handling"
    (let [calls-real (atom [])
          calls-A    (atom [])
          calls-B    (atom [])]
      ;; Register the canonical fx and two stubs. The override map lets
      ;; each frame redirect :http to its own stub independently.
      (rf/reg-fx :http
                 {:platforms #{:server :client}}
                 (fn [_ args] (swap! calls-real conj args)))
      (rf/reg-fx :http.stub-A
                 {:platforms #{:server :client}}
                 (fn [_ args] (swap! calls-A conj args)))
      (rf/reg-fx :http.stub-B
                 {:platforms #{:server :client}}
                 (fn [_ args] (swap! calls-B conj args)))
      (rf/reg-event-fx :go
        (fn [_ [_ payload]]
          {:fx [[:http payload]]}))

      (let [a (rf/make-frame {:fx-overrides {:http :http.stub-A}})
            b (rf/make-frame {:fx-overrides {:http :http.stub-B}})]
        ;; Gensym contract: id is a keyword in the :rf.frame namespace,
        ;; the two ids differ, and neither collides with :rf/default.
        (is (keyword? a))
        (is (keyword? b))
        (is (= "rf.frame" (namespace a)))
        (is (= "rf.frame" (namespace b)))
        (is (not= a b)
            "two make-frame calls produce distinct gensym'd ids")
        (is (not= a :rf/default))
        (is (not= b :rf/default))

        ;; Drive the same event into each frame; per-frame overrides
        ;; route the :http effect to that frame's stub.
        (rf/dispatch-sync [:go {:url "/A"}] {:frame a})
        (rf/dispatch-sync [:go {:url "/B"}] {:frame b})

        (is (= [{:url "/A"}] @calls-A)
            "frame A's override sent :http to stub-A")
        (is (= [{:url "/B"}] @calls-B)
            "frame B's override sent :http to stub-B")
        (is (empty? @calls-real)
            "the canonical :http handler was not invoked under either override")))))

;; ---- Spec 002 §reg-frame is atomic — :on-create runs synchronously -------

(deftest reg-frame-on-create-runs-synchronously
  (testing ":on-create completes inside reg-frame; app-db is fully populated by the time it returns"
    (let [observed (atom nil)]
      (rf/reg-event-db :boot
        (fn [_ [_ payload]]
          {:booted? true :payload payload :seq [:a :b :c]}))
      ;; Hook a trace listener so we can assert the ordering between
      ;; :on-create dispatch and :frame/created emission. Per Spec 002
      ;; §reg-frame is atomic — :on-create runs first, then :frame/created
      ;; is emitted (the frame becomes observable to listeners).
      (let [traces (atom [])]
        (rf/register-trace-cb! ::oc (fn [ev] (swap! traces conj ev)))
        (rf/reg-frame :booted
                      {:doc       "frame with on-create"
                       :on-create [:boot {:hello "world"}]})
        (rf/remove-trace-cb! ::oc)

        ;; The moment reg-frame returned, app-db must already reflect
        ;; the :on-create event's commit.
        (reset! observed (rf/get-frame-db :booted))
        (is (true? (:booted? @observed))
            ":on-create ran synchronously — app-db reflects its commit")
        (is (= {:hello "world"} (:payload @observed))
            ":on-create payload landed in app-db")
        (is (= [:a :b :c] (:seq @observed))
            "full :on-create handler body completed before reg-frame returned")

        ;; Ordering: the :event/run-end for :boot precedes :frame/created
        ;; (reg-frame emits :frame/created AFTER on-create dispatch-syncs).
        (let [run-end-idx (->> @traces
                               (keep-indexed
                                 (fn [i ev]
                                   (when (and (= :event (:op-type ev))
                                              (= :event (:operation ev))
                                              (= :boot (:event-id (:tags ev)))
                                              (= :run-end (:phase (:tags ev))))
                                     i)))
                               first)
              created-idx (->> @traces
                               (keep-indexed
                                 (fn [i ev]
                                   (when (= :frame/created (:operation ev))
                                     i)))
                               first)]
          (is (some? run-end-idx)
              "expected an :event :run-end trace for the :on-create event")
          (is (some? created-idx)
              "expected a :frame/created trace")
          (is (< run-end-idx created-idx)
              ":on-create's :run-end precedes :frame/created — frame is fully booted before listeners observe it"))))))
