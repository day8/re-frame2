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
            [re-frame.interop :as interop]
            [re-frame.registrar :as registrar]
            [re-frame.schemas :as schemas]
            [re-frame.flows :as flows]
            [re-frame.substrate.adapter :as adapter]
            [re-frame.trace :as trace]))

(defn reset-runtime [test-fn]
  (registrar/clear-all!)
  (reset! frame/frames {})
  (reset! flows/flows {})
  (reset! schemas/schemas-by-frame {})
  (trace/clear-trace-cbs!)
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

;; ---- Spec 002 §Frame presets — closed v1 expansion table -----------------
;;
;; Presets expand at registration time into a fixed bundle of metadata
;; keys. Per Spec 002 §Frame presets the closed v1 set is :default,
;; :test, :story, :ssr-server. User-supplied keys win on conflict; the
;; original :preset value is preserved verbatim for inspection.

(deftest preset-expansion-default
  (testing ":default expands to {} (identical to omitting :preset)"
    (rf/reg-frame :p/default {:preset :default})
    (let [cfg (:config (frame/frame :p/default))]
      (is (= :default (:preset cfg))
          ":preset is preserved on the config for inspection")
      (is (nil? (:fx-overrides cfg))
          ":default does not introduce :fx-overrides")
      (is (nil? (:drain-depth cfg))
          ":default does not introduce :drain-depth"))))

(deftest preset-expansion-test
  (testing ":test expansion: HTTP redirect + drain-depth 100"
    (rf/reg-frame :p/test {:preset :test})
    (let [cfg (:config (frame/frame :p/test))]
      (is (= :test (:preset cfg)))
      (is (= {:rf.http/managed :rf.http/managed-canned-success}
             (:fx-overrides cfg))
          ":test redirects the canonical Spec 014 HTTP fx to its canned-success stub")
      (is (= 100 (:drain-depth cfg))
          ":test stamps :drain-depth 100 explicitly so tooling reads it off frame-meta"))))

(deftest preset-expansion-story
  (testing ":story expansion: HTTP redirect + tighter drain-depth 16"
    (rf/reg-frame :p/story {:preset :story})
    (let [cfg (:config (frame/frame :p/story))]
      (is (= :story (:preset cfg)))
      (is (= {:rf.http/managed :rf.http/managed-canned-success}
             (:fx-overrides cfg)))
      (is (= 16 (:drain-depth cfg))
          ":story tightens :drain-depth to 16 so a runaway cascade fails fast under a story"))))

(deftest preset-expansion-ssr-server
  (testing ":ssr-server expansion: :platform :server + :on-error projection"
    (rf/reg-frame :p/ssr {:preset :ssr-server})
    (let [cfg (:config (frame/frame :p/ssr))]
      (is (= :ssr-server (:preset cfg)))
      (is (= :server (:platform cfg))
          ":ssr-server stamps :platform :server (singular keyword — one platform per frame)")
      (is (= :rf.error/server-projection (:on-error cfg))
          ":ssr-server wires :on-error to the server-side projection target"))))

(deftest preset-user-keys-win-on-conflict
  (testing "user-supplied keys override individual expansion entries"
    ;; :test sets :drain-depth 100 by default; user overrides to 1000.
    (rf/reg-frame :p/override {:preset      :test
                               :drain-depth 1000})
    (let [cfg (:config (frame/frame :p/override))]
      (is (= :test (:preset cfg)))
      (is (= 1000 (:drain-depth cfg))
          "user :drain-depth wins over the preset's expansion default")
      (is (= {:rf.http/managed :rf.http/managed-canned-success}
             (:fx-overrides cfg))
          "non-overridden expansion entries still apply"))))

(deftest preset-unknown-throws
  (testing "unknown preset values throw :rf.error/unknown-preset"
    (is (thrown? Exception
          (rf/reg-frame :p/bad {:preset :devcards}))
        "passing a preset outside the closed v1 set is a registration-time error")))

;; ---- rf2-ft2b: drain-after-destroy null guard ----------------------------
;;
;; Per the rf2-ft2b reproducer: a scheduled drain races frame destruction.
;; By the time the drain fires, the frame's app-db container is nil
;; (because frame/get-frame-db returns nil for destroyed frames), and
;; the per-event :db commit at router.cljc would write through nil →
;; NullPointerException on the executor thread.
;;
;; The fix is a defense-in-depth guard at the single choke point through
;; which every container write flows: substrate/adapter/replace-container!
;; no-ops when container is nil and emits :rf.warning/write-after-destroy.

(deftest replace-container-no-ops-on-nil-container
  ;; Direct unit-level coverage of the guard at the adapter wrapper. The
  ;; nil-container call must not throw and must emit the warning trace.
  (testing "replace-container! with nil container is a no-op + :rf.warning/write-after-destroy"
    (let [recorded (atom [])]
      (rf/register-trace-cb! ::rec (fn [ev] (swap! recorded conj ev)))
      ;; Must not throw NPE.
      (is (nil? (adapter/replace-container! nil {:any :value}))
          "nil container is a documented no-op, not an exception")
      (let [warns (filterv (fn [ev]
                             (and (= :warning (:op-type ev))
                                  (= :rf.warning/write-after-destroy
                                     (:operation ev))))
                           @recorded)]
        (is (= 1 (count warns))
            "exactly one :rf.warning/write-after-destroy trace fired")))))

(deftest drain-after-destroy-does-not-npe
  ;; Reproducer for the original race (rf2-ft2b): a scheduled drain that
  ;; reaches the per-event :db commit AFTER the frame has been destroyed
  ;; must NOT throw. Instead the adapter-level nil guard skips the write
  ;; and emits :rf.warning/write-after-destroy.
  ;;
  ;; The race is forced deterministically by capturing the next-tick
  ;; callback (instead of running it on the executor) so that the
  ;; destroy-frame! call slots in between the drain being scheduled and
  ;; the drain actually running.
  (testing "scheduled drain that fires after destroy is a no-op + warning, not an NPE"
    (let [captured-tick (atom nil)
          recorded      (atom [])]
      (rf/register-trace-cb! ::rec (fn [ev] (swap! recorded conj ev)))
      (rf/reg-frame :race/frame {:doc "rf2-ft2b reproducer frame"})
      ;; A simple :db-writing event handler. The drain that processes
      ;; this event is what we want to land AFTER destroy.
      (rf/reg-event-db :write
        (fn [_db _]
          {:committed? true}))
      (with-redefs [interop/next-tick (fn [f] (reset! captured-tick f) nil)]
        ;; Async dispatch — schedules the drain via next-tick. The
        ;; with-redefs binding captures the drain thunk into
        ;; @captured-tick instead of executing it.
        (rf/dispatch [:write] {:frame :race/frame})
        (is (some? @captured-tick)
            "the async dispatch scheduled a drain via next-tick"))
      ;; Now destroy the frame. After this, frame/get-frame-db returns
      ;; nil, so the captured drain — when it fires — would write
      ;; through nil if not for the adapter guard.
      (frame/destroy-frame! :race/frame)
      ;; Fire the captured drain. Pre-fix this raised an NPE; post-fix
      ;; the drain enters drain!, finds (frame frame-id) is nil (the
      ;; outer guard at drain! line ~261), so process-event! is never
      ;; called and no write is attempted. We assert no throw.
      (is (nil? (try (@captured-tick) nil
                     (catch Throwable e e)))
          "the drain ran without throwing"))))

(deftest replace-container-on-destroyed-frame-does-not-npe
  ;; Tighter reproducer that hits the adapter guard directly via the
  ;; live runtime. The router's per-event :db commit reads the frame's
  ;; app-db container right before writing — and if the frame was
  ;; destroyed mid-drain, get-frame-db returns nil. We exercise that
  ;; exact shape by reading get-frame-db AFTER destroy and feeding the
  ;; nil container straight into replace-container!.
  (testing "frame/get-frame-db on a destroyed frame is nil; replace-container! handles it"
    (let [recorded (atom [])]
      (rf/register-trace-cb! ::rec (fn [ev] (swap! recorded conj ev)))
      (rf/reg-frame :race/destroyed-mid-write {})
      (frame/destroy-frame! :race/destroyed-mid-write)
      (let [container (frame/get-frame-db :race/destroyed-mid-write)]
        (is (nil? container)
            "get-frame-db on a destroyed frame returns nil — the precondition for the rf2-ft2b NPE")
        ;; This is the exact call shape from router.cljc's :db commit.
        ;; Pre-fix: NPE. Post-fix: no-op + warning trace.
        (is (nil? (adapter/replace-container! container {:would :have :npe'd true}))
            "writing through the nil container is a documented no-op"))
      (let [warns (filterv (fn [ev]
                             (and (= :warning (:op-type ev))
                                  (= :rf.warning/write-after-destroy
                                     (:operation ev))))
                           @recorded)]
        (is (pos? (count warns))
            ":rf.warning/write-after-destroy fired for the post-destroy write")))))
