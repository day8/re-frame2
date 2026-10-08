(ns re-frame.frame-lifecycle-test
  "JVM lifecycle edge cases of `make-frame`, `destroy-frame!`, presets and the
  surgical re-registration update (Spec 002 §Frames, §Destroy, §make-frame is
  atomic, §Re-registration, §Per-instance frames).

  What a destroy did is always-on: the frame left the registry, the sub-cache
  disposed, queued events never ran, teardown continued past a throwing
  `:on-destroy`. The trace that announces it is dev-only, so trace reads sit
  in `(when rf.interop/debug-enabled? ...)` arms beside an always-on witness."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as rf.frame]
            [re-frame.interop :as rf.interop]
            [re-frame.late-bind :as rf.late-bind]
            [re-frame.registrar :as rf.registrar]
            [re-frame.schemas :as rf.schemas]
            [re-frame.source-store :as rf.source-store]
            [re-frame.flows :as rf.flows]
            [re-frame.substrate.adapter :as rf.substrate.adapter]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.trace.tooling :as rf.trace.tooling]
            [re-frame.trace :as rf.trace])
  (:import [java.util.concurrent CountDownLatch TimeUnit]))

(defn reset-runtime [test-fn]
  (rf.registrar/clear-all!)
  (reset! rf.frame/frames {})
  (rf.flows/reset-flows!)
  (rf.schemas/clear-schemas-by-frame!)
  (rf.trace.tooling/clear-listeners!)
  (rf/init! rf.substrate.plain-atom/adapter)
  ;; restore the ns-load framework registrations clear-all! wiped
  (require 're-frame.routing :reload)
  (require 're-frame.ssr     :reload)
  (require 're-frame.machines :reload)
  (test-fn))

(use-fixtures :each reset-runtime)

(defn- executor-barrier!
  "Wait until work already submitted through `rf.interop/next-tick` has run.
  The JVM executor is single-threaded FIFO; the timeout is only a hang guard."
  []
  (let [latch (CountDownLatch. 1)]
    (rf.interop/next-tick #(.countDown latch))
    (is (.await latch 5 TimeUnit/SECONDS)
        "the executor reached the deterministic barrier")))

(deftest make-frame-surgical-update-preserves-runtime-state
  ;; re-registering a live frame replaces its config but keeps app-db, the
  ;; sub-cache, the router and the lifecycle as the same objects
  (rf/make-frame {:id :tenant :doc "v1 metadata" :preset :default})
  (rf/reg-event :seed (fn [{:keys [db]} [_ n]] {:db {:n n :payload "live"}}))
  (rf/reg-sub :n (fn [db _] (:n db)))
  (rf/dispatch-sync [:seed 42] {:frame :tenant})
  (let [pinned      (rf/subscribe [:n] {:frame :tenant})
        orig-record (rf.frame/frame :tenant)]
    (is (= [42 true] [@pinned (contains? @(:sub-cache orig-record) [:n])]))
    (rf/make-frame {:id :tenant :doc          "v2 metadata"
                    :fx-overrides {:http :stub.v2}})
    (let [new-record (rf.frame/frame :tenant)]
      (is (= ["v2 metadata" {:http :stub.v2} true true true true]
             [(get-in new-record [:config :doc])
              (get-in new-record [:config :fx-overrides])
              (identical? (:app-db orig-record)    (:app-db new-record))
              (identical? (:sub-cache orig-record) (:sub-cache new-record))
              (identical? (:router orig-record)    (:router new-record))
              (identical? (:lifecycle orig-record) (:lifecycle new-record))])))
    (is (= [{:n 42 :payload "live"} 42] [(rf/app-db-value :tenant) @pinned])
        "app-db and the pinned subscription survive the update")))

(deftest destroy-frame-cascade-emits-per-active-machine
  (rf/make-frame {:id :ten :doc "tenant"})
  ;; machine snapshots are durable runtime-db state, seeded directly
  (rf/reg-event :seed-machines
    (fn [{rt :rf.db/runtime} _]
      {:rf.db/runtime
       (assoc-in (or rt {}) [:rf.runtime/machines :snapshots]
                 {:flow/login    {:state :authed     :data {:user "a"}}
                  :flow/checkout {:state :reviewing  :data {:cart [1 2]}}
                  :flow/billing  {:state :collected  :data {}}})}))
  (rf/dispatch-sync [:seed-machines] {:frame :ten})
  (is (= #{:flow/login :flow/checkout :flow/billing}
         (set (keys (get-in (:rf.db/runtime (rf/frame-state-value :ten))
                            [:rf.runtime/machines :snapshots]))))
      "precondition: three machine snapshots are active")
  (let [traces (atom [])]
    (rf/register-listener! :trace ::cascade (fn [ev] (swap! traces conj ev)))
    (rf/destroy-frame! :ten)
    (rf/unregister-listener! :trace ::cascade)
    (is (nil? (rf.frame/frame :ten)))
    (when rf.interop/debug-enabled?
      ;; one signal per active actor, carrying its last state and the unified
      ;; :parent-frame-destroyed reason, then the frame's own destroyed trace
      (is (= [#{[:ten :flow/login :authed :parent-frame-destroyed]
                [:ten :flow/checkout :reviewing :parent-frame-destroyed]
                [:ten :flow/billing :collected :parent-frame-destroyed]}
              3
              true]
             (let [cascade (filter #(= :rf.machine.lifecycle/destroyed (:operation %)) @traces)]
               [(set (map (comp (juxt :frame :actor-id :last-state :reason) :tags) cascade))
                (count cascade)
                (boolean (some #(= :rf.frame/destroyed (:operation %)) @traces))]))))))

(deftest destroy-frame-runs-exit-cascades-in-reverse-creation-order
  ;; Spec 005 §Cross-Spec Interactions §1, through real spawned actors
  (rf/make-frame {:id :rf2-vsigt/auth :doc "cross-slice test frame"})
  (let [exit-log (atom [])
        child    {:initial :running
                  :data    {}
                  :states  {:running {:exit (fn [{data :data}]
                                               (swap! exit-log
                                                      conj (:rf/self-id data))
                                               {})}}}
        spawn    [:rf.machine/spawn {:machine-id :rf2-vsigt/child
                                     :id-prefix  :rf2-vsigt/child}]
        boot     {:initial :idle
                  :data    {}
                  :states
                  {:idle {:on {:go {:action (fn [_] {:fx [spawn spawn spawn]})}}}}}
        children [:rf2-vsigt/child#1 :rf2-vsigt/child#2 :rf2-vsigt/child#3]]
    (rf/reg-machine :rf2-vsigt/child child)
    (rf/reg-machine :rf2-vsigt/boot boot)
    (rf/dispatch-sync [:rf2-vsigt/boot [:go]] {:frame :rf2-vsigt/auth})
    (is (= 3 (count (filter (set children)
                            (keys (get-in (:rf.db/runtime (rf/frame-state-value :rf2-vsigt/auth))
                                          [:rf.runtime/machines :snapshots]))))))
    (rf/destroy-frame! :rf2-vsigt/auth)
    (is (= (reverse children) @exit-log) ":exit ran newest-first")
    ;; the spawned actors' handlers go; the singleton machines stay registered
    (is (= [nil nil nil true true]
           [(rf.registrar/lookup :event :rf2-vsigt/child#1)
            (rf.registrar/lookup :event :rf2-vsigt/child#2)
            (rf.registrar/lookup :event :rf2-vsigt/child#3)
            (some? (rf.registrar/lookup :event :rf2-vsigt/child))
            (some? (rf.registrar/lookup :event :rf2-vsigt/boot))]))))

(deftest destroy-frame-with-live-subscribers
  (rf/make-frame {:id :live :doc "live"})
  (rf/reg-event :seed (fn [{:keys [db]} _] {:db {:answer 7}}))
  (rf/reg-sub :answer (fn [db _] (:answer db)))
  (rf/dispatch-sync [:seed] {:frame :live})
  (let [r1            (rf/subscribe [:answer] {:frame :live})
        r2            (rf/subscribe [:answer] {:frame :live})
        dispose-fired (atom 0)
        traces        (atom [])]
    (re-frame.interop/add-on-dispose! r1 (fn [] (swap! dispose-fired inc)))
    (is (= [7 true] [@r1 (identical? r1 r2)]) "precondition: one cached reaction")
    (rf/register-listener! :trace ::sub-destroy (fn [ev] (swap! traces conj ev)))
    (rf/destroy-frame! :live)
    ;; the walk disposed the reaction once, and a later subscribe returns nil
    (is (= [1 nil] [@dispose-fired (rf/subscribe [:answer] {:frame :live})]))
    (rf/unregister-listener! :trace ::sub-destroy)
    (when rf.interop/debug-enabled?
      (is (some (fn [ev]
                  (and (= :rf.error/frame-destroyed (:operation ev))
                       (= :replaced-with-default (:recovery ev))))
                @traces)
          "the post-destroy subscribe traces :replaced-with-default"))))

(deftest make-frame-gensyms-id-and-isolates-fx-overrides
  (let [calls (atom [])]
    (doseq [fx [:http :http.stub-A :http.stub-B]]
      (rf/reg-fx fx {:platforms #{:server :client}}
                 (fn [_ args] (swap! calls conj [fx args]))))
    (rf/reg-event :go
      (fn [_ [_ payload]]
        {:fx [[:http payload]]}))
    (let [a (rf.frame/make-anon-frame-record! {:fx-overrides {:http :http.stub-A}})
          b (rf.frame/make-anon-frame-record! {:fx-overrides {:http :http.stub-B}})]
      (is (= ["rf.frame" "rf.frame" true] [(namespace a) (namespace b) (not= a b)])
          "distinct gensym'd ids in the :rf.frame namespace")
      (rf/dispatch-sync [:go {:url "/A"}] {:frame a})
      (rf/dispatch-sync [:go {:url "/B"}] {:frame b})
      (is (= [[:http.stub-A {:url "/A"}] [:http.stub-B {:url "/B"}]] @calls)
          "each frame's override routed :http to its own stub, never the canonical one"))))

(deftest make-frame-initial-events-runs-synchronously
  ;; the setup commits before make-frame returns, and before :rf.frame/created
  (rf/reg-event :boot
    (fn [{:keys [db]} [_ payload]]
      {:db {:booted? true :payload payload :seq [:a :b :c]}}))
  (let [traces (atom [])]
    (rf/register-listener! :trace ::oc (fn [ev] (swap! traces conj ev)))
    (rf/make-frame {:id :booted :doc            "frame with initial-events"
                    :initial-events [[:boot {:hello "world"}]]})
    (rf/unregister-listener! :trace ::oc)
    (is (= {:booted? true :payload {:hello "world"} :seq [:a :b :c]}
           (rf/app-db-value :booted)))
    (when rf.interop/debug-enabled?
      (let [index-of (fn [pred] (first (keep-indexed (fn [i ev] (when (pred ev) i)) @traces)))]
        (is (< (index-of #(and (= :rf.event/run-end (:operation %))
                               (= :boot (:rf.trace/event-id (:tags %)))))
               (index-of #(= :rf.frame/created (:operation %))))
            "listeners observe the frame only once it is fully booted")))))

(deftest destroy-from-handler-interrupts-drain-and-emits-interrupted
  ;; A handler that destroys its own frame stops the drain and drops the rest of
  ;; the queue. next-tick is captured so the four ticks are still queued when
  ;; dispatch-sync seeds the self-destruct at the front; the captured drains are
  ;; discarded (destroy-frame-pending-drain-pins-no-op-contract pins them).
  (rf/make-frame {:id :drain-int/worker :doc "drain-interrupt frame"})
  (let [ran           (atom [])
        traces        (atom [])
        captured-tick (atom [])]
    (rf/reg-event :drain-int/tick
      (fn [{:keys [db]} _]
        (swap! ran conj :tick)
        {:db (update db :n (fnil inc 0))}))
    (rf/reg-event :drain-int/self-destruct
      (fn [_ _]
        (swap! ran conj :self-destruct)
        (rf.frame/destroy-frame! :drain-int/worker)
        {}))
    (rf/register-listener! :trace ::drain-int (fn [ev] (swap! traces conj ev)))
    (with-redefs [rf.interop/next-tick (fn [f] (swap! captured-tick conj f) nil)]
      (dotimes [_ 4] (rf/dispatch [:drain-int/tick] {:frame :drain-int/worker}))
      (rf/dispatch-sync [:drain-int/self-destruct] {:frame :drain-int/worker}))
    (rf/unregister-listener! :trace ::drain-int)
    (is (= [[:self-destruct] nil] [@ran (rf.frame/frame :drain-int/worker)]))
    (when rf.interop/debug-enabled?
      (is (= [[:rf.frame :drain-int/worker 4]]
             (keep #(when (= :rf.frame/drain-interrupted (:operation %))
                      [(:op-type %) (:frame (:tags %)) (:dropped-count (:tags %))])
                   @traces))
          "one lifecycle-family interruption trace carrying the dropped count"))))

(deftest destroy-from-handler-allows-authored-callback-return
  ;; destroy does not interrupt authored code already on the stack
  (rf/make-frame {:id :rtc/worker})
  (let [completed (atom false)]
    (rf/reg-event :rtc/work-then-destroy
      (fn [_ _]
        (rf.frame/destroy-frame! :rtc/worker)
        (reset! completed true)
        {}))
    (rf/dispatch-sync [:rtc/work-then-destroy] {:frame :rtc/worker})
    (is (true? @completed))))

;; Constructing a frame inside an event handler fails loud (EP-0027
;; §Construction). The make-frame route is pinned on both hosts by
;; frame_initial_events_cljs_test; this file pins the anon-record route.

(deftest make-frame-record-in-handler-fails-loud
  ;; the anon-record entry shares make-frame's handler-time construction guard
  (rf/make-frame {:id :rf/default})
  (let [caught (atom nil)
        before (rf/frame-ids)]
    (rf/reg-event :sub-actor/boot
      (fn [{:keys [db]} _] {:db (assoc db :booted? true)}))
    (rf/reg-event :parent/spawn-sub-actor
      (fn [{:keys [db]} _]
        (reset! caught
                (try (rf.frame/make-anon-frame-record!
                       {:initial-events [[:sub-actor/boot]]})
                     nil
                     (catch clojure.lang.ExceptionInfo e
                       (:rf.error/id (ex-data e)))))
        {:db db}))
    (rf/dispatch-sync [:parent/spawn-sub-actor] {:frame :rf/default})
    ;; the anon id is minted inside the refused call, so the whole set is read
    (is (= [:rf.error/frame-construction-in-handler before] [@caught (rf/frame-ids)]))))

(deftest make-frame-construction-failure-leaves-no-registrar-or-trace-residue
  ;; make-frame before rf/init!: the record is built before any trace-policy
  ;; write, so the throw escapes before process-global state is touched
  (let [fid :test/no-adapter-frame]
    (rf.substrate.adapter/reset-lifecycle-state-for-tests!)
    (is (= [:rf.error/no-adapter-installed nil nil false]
           [(try (rf/make-frame {:id fid :rf.trace/frame-no-emit? true})
                 nil
                 (catch clojure.lang.ExceptionInfo e
                   (:rf.error/id (ex-data e))))
            (rf.frame/frame fid)
            (rf.frame/frame-meta fid)
            (rf.trace/frame-trace-disabled? fid)]))
    (rf/init! rf.substrate.plain-atom/adapter)))

(deftest frame-seating-writes-no-registrar-row-and-no-source-store-descriptor
  ;; A frame is a live runtime object, not a registration: a :frame registrar
  ;; row would also record a source-store descriptor and bump the store
  ;; generation, invalidating the image-generation cache on every seat.
  (let [gen-before (rf.source-store/store-generation)]
    (rf/make-frame {:id :h1vqa4/owned :doc "v1"})
    (rf/make-frame {:id :h1vqa4/owned :doc "v2 (reseat refreshes config)"})
    (is (= ["v2 (reseat refreshes config)" nil true gen-before]
           [(:doc (rf/frame-meta :h1vqa4/owned))
            (rf.registrar/lookup :frame :h1vqa4/owned)
            (empty? (rf.source-store/descriptors-for :frame :h1vqa4/owned))
            (rf.source-store/store-generation)]))
    (rf/destroy-frame! :h1vqa4/owned)
    (is (= [nil gen-before]
           [(rf/frame-meta :h1vqa4/owned) (rf.source-store/store-generation)]))))

(deftest each-preset-expands-to-its-closed-v1-bundle
  ;; [preset fx-overrides drain-depth mint-policy]; :default is {}, :test stubs
  ;; managed HTTP with a strict mint policy, :story fails a runaway cascade fast
  (doseq [[preset fx-overrides drain-depth mint-policy]
          [[:default nil nil nil]
           [:test {:rf.http/managed :rf.http/managed-canned-success} 100 :strict]
           [:story {:rf.http/managed :rf.http/managed-canned-success} 16 nil]]]
    (let [id (keyword "p" (name preset))]
      (rf/make-frame {:id id :preset preset})
      (is (= [preset fx-overrides drain-depth mint-policy]
             ((juxt :preset :fx-overrides :drain-depth :rf.cofx/mint-policy)
              (:config (rf.frame/frame id))))
          (str preset)))))

(deftest preset-user-keys-win-on-conflict
  (rf/make-frame {:id :p/override :preset      :test
                  :drain-depth 1000})
  (is (= [:test 1000 {:rf.http/managed :rf.http/managed-canned-success}]
         ((juxt :preset :drain-depth :fx-overrides) (:config (rf.frame/frame :p/override))))
      "the user's :drain-depth wins and the other expansion entries still apply"))

(deftest preset-unknown-throws
  (is (thrown? Exception (rf/make-frame {:id :p/bad :preset :devcards}))
      "a preset outside the closed v1 set is a registration-time error"))

;; A drain racing a destroy reads a nil app-db container; replace-container!,
;; the one choke point every container write flows through, no-ops on it.

(deftest replace-container-no-ops-on-nil-container
  ;; Direct unit-level coverage of the guard at the adapter wrapper. The
  ;; nil-container call must not throw and must emit the error trace.
  (testing "replace-container! with nil container is a no-op + :rf.error/write-after-destroy"
    (let [recorded (atom [])]
      (rf/register-listener! :trace ::rec (fn [ev] (swap! recorded conj ev)))
      ;; Must not throw NPE.
      (is (nil? (rf.substrate.adapter/replace-container! nil {:any :value}))
          "nil container is a documented no-op, not an exception")
      ;; Dev-instrumentation arm (see ns docstring). The guard's
      ;; production-real half is the no-op-instead-of-NPE assertion above,
      ;; which is the whole point of the guard and runs in both
      ;; postures; the REPORT of the skipped write is a dev diagnostic.
      (when rf.interop/debug-enabled?
        (let [errs (filterv (fn [ev]
                              (and (= :error (:op-type ev))
                                   (= :rf.error/write-after-destroy
                                      (:operation ev))))
                            @recorded)]
          (is (= 1 (count errs))
              "exactly one :rf.error/write-after-destroy trace fired"))))))

(deftest destroy-frame-pending-drain-pins-no-op-contract
  ;; events queued before a destroy, drained after it, neither run nor throw
  (rf/make-frame {:id :rf2-dpny/worker :doc "race-pin frame"})
  (let [side-effects (atom 0)
        traces       (atom [])
        captured     (atom [])]
    (rf/reg-event :rf2-dpny/tick
      (fn [{:keys [db]} _] (swap! side-effects inc) {:db db}))
    (rf/register-listener! :trace ::rf2-dpny (fn [ev] (swap! traces conj ev)))
    (with-redefs [rf.interop/next-tick (fn [f] (swap! captured conj f) nil)]
      (rf/dispatch [:rf2-dpny/tick] {:frame :rf2-dpny/worker})
      (rf/dispatch [:rf2-dpny/tick] {:frame :rf2-dpny/worker})
      (is (= 2 (count (:queue @(:router (rf.frame/frame :rf2-dpny/worker)))))
          "precondition: two events queued, un-drained"))
    (rf/destroy-frame! :rf2-dpny/worker)
    (is (= [] (keep #(try (%) nil (catch Throwable e e)) @captured))
        "the captured drains ran without throwing")
    (rf/unregister-listener! :trace ::rf2-dpny)
    ;; the drain emits neither frame-destroyed (a dispatch-time trace) nor
    ;; drain-interrupted (it never started)
    (is (= [0 0 0]
           [@side-effects
            (count (filter #(= :rf.error/frame-destroyed (:operation %)) @traces))
            (count (filter #(= :rf.frame/drain-interrupted (:operation %)) @traces))]))
    (let [after-traces (atom [])]
      (rf/register-listener! :trace ::rf2-dpny-after (fn [ev] (swap! after-traces conj ev)))
      (rf/dispatch-sync [:rf2-dpny/tick] {:frame :rf2-dpny/worker})
      (rf/unregister-listener! :trace ::rf2-dpny-after)
      (when rf.interop/debug-enabled?
        (is (some #(= :rf.error/frame-destroyed (:operation %)) @after-traces)
            "a post-destroy dispatch traces :rf.error/frame-destroyed")))
    (is (= [0 nil] [@side-effects (rf.frame/frame :rf2-dpny/worker)])
        "no handler ran and the frame did not re-materialise")))

(deftest destroy-claim-prevents-cold-release-from-running-queued-handlers
  (testing "the destroy claim is the queued-work cutoff, before lifecycle-dead publication"
    (let [frame-id       :destroy-claim/cold-release
          parent-runs    (atom 0)
          child-runs     (atom 0)
          user-effects   (atom 0)
          captured-ticks (atom [])
          gap-observation (atom nil)
          original-hook  (rf.late-bind/get-fn :machines/teardown-on-frame-destroy!)]
      (rf/make-frame {:id frame-id})
      (rf/reg-fx :destroy-claim/effect
        (fn [_ _] (swap! user-effects inc)))
      (rf/reg-event :destroy-claim/child
        (fn [{:keys [db]} _]
          (swap! child-runs inc)
          {:db (assoc db :child-ran? true)}))
      (rf/reg-event :destroy-claim/queued
        (fn [{:keys [db]} _]
          (swap! parent-runs inc)
          {:db (assoc db :queued-handler-ran? true)
           :fx [[:destroy-claim/effect :payload]
                [:dispatch [:destroy-claim/child]]]}))

      ;; Queue two events while suppressing their first scheduled drain. The
      ;; destroy claim must both suppress a replacement kick from its COLD
      ;; serialized release and fence this not-yet-fired original attempt.
      (with-redefs [rf.interop/next-tick
                    (fn [f]
                      (swap! captured-ticks conj f)
                      nil)]
        (rf/dispatch [:destroy-claim/queued] {:frame frame-id})
        (rf/dispatch [:destroy-claim/queued] {:frame frame-id}))
      (is (= [1 2] [(count @captured-ticks) (count (:queue @(:router (rf.frame/frame frame-id))))])
          "precondition: one scheduled drain attempt and two queued events")

      ;; `:machines/teardown-on-frame-destroy!` runs after claim-frame-destroy! has returned
      ;; (and therefore after the cold lock release) but before
      ;; mark-frame-destroyed!. Its FIFO executor barrier forces the captured
      ;; drain to finish inside that exact claim -> lifecycle-dead window.
      (try
        (rf.late-bind/set-fn!
          :machines/teardown-on-frame-destroy!
          (fn [id]
            (when original-hook
              (original-hook id))
            (when (= frame-id id)
              ;; Submit the drain attempt captured at enqueue time only AFTER
              ;; claim-frame-destroy! has released the cold lock. This covers
              ;; the path where that attempt had not yet CAS-lost when claim
              ;; completed; the claim predicate, not scheduling luck, must
              ;; prevent it from invoking either queued handler.
              ;; `next-tick` deliberately conveys dynamic bindings through
              ;; `bound-fn`. Scheduling from this teardown hook therefore also
              ;; proves that owner privilege is ACTUAL host-thread identity,
              ;; not the conveyed `*destroying-frame-id*` value.
              (rf.interop/next-tick (first @captured-ticks))
              (executor-barrier!)
              (let [record (get @rf.frame/frames frame-id)]
                (reset! gap-observation
                        {:destroyed? (-> record :lifecycle :destroyed?)
                         :queued     (count (:queue @(:router record)))})))))
        (rf/destroy-frame! frame-id)
        (finally
          (rf.late-bind/set-fn! :machines/teardown-on-frame-destroy! original-hook)))

      ;; no queued handler, user effect or child ran once the destroy was
      ;; claimed; the queue emptied while the frame was still lifecycle-live,
      ;; and the destroy completed
      (is (= [0 0 0 {:destroyed? false :queued 0} nil]
             [@parent-runs @user-effects @child-runs @gap-observation
              (rf.frame/frame frame-id)])))))

(deftest post-claim-dispatch-enters-real-queue-but-never-executes
  (testing "ordinary work submitted after claim may queue but cannot run in the claim-to-dead gap"
    (let [frame-id        :destroy-claim/post-claim-dispatch
          parent-runs     (atom 0)
          child-runs      (atom 0)
          user-effects    (atom 0)
          captured-ticks  (atom [])
          traces          (atom [])
          gap-observation (atom nil)
          original-hook   (rf.late-bind/get-fn :machines/teardown-on-frame-destroy!)]
      (rf/make-frame {:id frame-id})
      (rf/reg-fx :destroy-claim/post-claim-effect
        (fn [_ _] (swap! user-effects inc)))
      (rf/reg-event :destroy-claim/post-claim-child
        (fn [{:keys [db]} _]
          (swap! child-runs inc)
          {:db (assoc db :post-claim-child-ran? true)}))
      (rf/reg-event :destroy-claim/post-claim-parent
        (fn [{:keys [db]} _]
          (swap! parent-runs inc)
          {:db (assoc db :post-claim-parent-ran? true)
           :fx [[:destroy-claim/post-claim-effect :payload]
                [:dispatch [:destroy-claim/post-claim-child]]]}))

      (rf/register-listener! :trace ::post-claim-combined-count
                             (fn [ev] (swap! traces conj ev)))
      ;; One ordinary envelope exists before the claim. Capturing its scheduled
      ;; drain keeps it pending until `claim-frame-destroy!` atomically cuts it.
      (with-redefs [rf.interop/next-tick
                    (fn [f]
                      (swap! captured-ticks conj f)
                      nil)]
        (rf/dispatch [:destroy-claim/post-claim-parent] {:frame frame-id}))

      (try
        (rf.late-bind/set-fn!
          :machines/teardown-on-frame-destroy!
          (fn [id]
            (when original-hook
              (original-hook id))
            (when (= frame-id id)
              ;; This hook runs strictly AFTER `claim-frame-destroy!` returns
              ;; and BEFORE `mark-frame-destroyed!` flips lifecycle liveness.
              ;; Submit genuinely NEW ordinary work here, capture its scheduled
              ;; drain, then force that drain through the real bound-fn executor
              ;; before allowing teardown to advance.
              (with-redefs [rf.interop/next-tick
                            (fn [f]
                              (swap! captured-ticks conj f)
                              nil)]
                (rf/dispatch [:destroy-claim/post-claim-parent]
                             {:frame frame-id}))
              (is (= [2 1] [(count @captured-ticks)
                            (count (:queue @(:router (get @rf.frame/frames frame-id))))])
                  "a drain per submit, and the ordinary envelope enqueued after the cutoff")
              ;; Execute BOTH already-captured scheduler callbacks separately.
              ;; Claim cleared :scheduled?, so the post-claim submit captured a
              ;; second callback. The first callback atomically consumes the
              ;; claim-time count and compare-marks this router generation; the
              ;; second still clears rejected work but cannot emit again. These
              ;; internal-state assertions pin the algorithm shown in Spec 002
              ;; §Drain-loop pseudocode, not merely its eventual trace count.
              (let [[first-tick second-tick] @captured-ticks]
                (rf.interop/next-tick first-tick)
                (executor-barrier!)
                (let [router-state @(:router (get @rf.frame/frames frame-id))
                      interrupts   (filterv
                                     #(= :rf.frame/drain-interrupted
                                         (:operation %))
                                     @traces)]
                  ;; These two are ROUTER STATE — production-real, and they are
                  ;; the algorithm this deftest pins (Spec 002 §Drain-loop
                  ;; pseudocode). They stay outside the arm.
                  (is (= [true false] [(:destroy-claim-report-emitted? router-state)
                                       (contains? router-state :destroy-claim-dropped-count)])
                      "the first callback compare-marks the router and consumes the claim-time count")
                  ;; Dev-instrumentation arm (see ns docstring).
                  (when rf.interop/debug-enabled?
                    (is (= 1 (count interrupts))
                        "the compare/mark winner emits the one combined report")))
                (rf.interop/next-tick second-tick)
                (executor-barrier!)
                ;; Dev-instrumentation arm (see ns docstring).
                (when rf.interop/debug-enabled?
                  (is (= 1 (count (filter
                                    #(= :rf.frame/drain-interrupted
                                        (:operation %))
                                    @traces)))
                      "the second captured callback observes the mark and emits nothing")))
              (let [record (get @rf.frame/frames frame-id)]
                (reset! gap-observation
                        {:destroyed? (-> record :lifecycle :destroyed?)
                         :queued     (count (:queue @(:router record)))})))))
        (rf/destroy-frame! frame-id)
        (finally
          (rf.late-bind/set-fn! :machines/teardown-on-frame-destroy! original-hook)
          (rf/unregister-listener! :trace ::post-claim-combined-count)))

      (is (= [0 0 0 {:destroyed? false :queued 0} nil]
             [@parent-runs @user-effects @child-runs @gap-observation
              (rf.frame/frame frame-id)])
          "the post-claim handler never ran and the claim emptied the live queue")
      (when rf.interop/debug-enabled?
        (is (= [2] (keep #(when (= :rf.frame/drain-interrupted (:operation %))
                            (get-in % [:tags :dropped-count]))
                         @traces))
            "one interruption trace, combining a claim-time and a check-time removal")))))

(deftest on-destroy-cascade-is-isolated-from-preclaim-and-bound-work
  (testing "only the cleanup seed and its queued descendants run after claim"
    (let [frame-id        :destroy-claim/isolated-cleanup
          ordinary-runs   (atom 0)
          cleanup-runs    (atom 0)
          child-runs      (atom 0)
          effect-runs     (atom 0)
          escaped-runs    (atom 0)
          nested-sync-runs (atom 0)
          captured-ticks  (atom [])]
      (rf/make-frame {:id frame-id
                      :on-destroy [:destroy-claim/cleanup]})
      (rf/reg-event :destroy-claim/ordinary
        (fn [{:keys [db]} _]
          (swap! ordinary-runs inc)
          {:db (assoc db :ordinary-ran? true)}))
      (rf/reg-event :destroy-claim/escaped
        (fn [{:keys [db]} _]
          (swap! escaped-runs inc)
          {:db (assoc db :escaped-ran? true)}))
      (rf/reg-event :destroy-claim/nested-sync
        (fn [{:keys [db]} _]
          (swap! nested-sync-runs inc)
          {:db (assoc db :nested-sync-ran? true)}))
      (rf/reg-event :destroy-claim/cleanup-child
        (fn [{:keys [db]} _]
          (swap! child-runs inc)
          {:db (assoc db :cleanup-child-ran? true)}))
      (rf/reg-fx :destroy-claim/cleanup-effect
        (fn [_ _] (swap! effect-runs inc)))
      (rf/reg-event :destroy-claim/cleanup
        (fn [{:keys [db]} _]
          (swap! cleanup-runs inc)
          ;; `next-tick` captures dynamic bindings with `bound-fn` on JVM.
          ;; Force the callback to run while the claim is still live: actual
          ;; host-thread identity must keep it out of the private cleanup queue.
          (rf.interop/next-tick
            #(rf/dispatch [:destroy-claim/escaped] {:frame frame-id}))
          (executor-barrier!)
          ;; The internal teardown entry is narrow; it must not turn generic
          ;; nested dispatch-sync into a legal handler operation.
          (rf/dispatch-sync [:destroy-claim/nested-sync] {:frame frame-id})
          {:db db
           :fx [[:destroy-claim/cleanup-effect :payload]
                [:dispatch [:destroy-claim/cleanup-child]]]}))

      ;; Hold one ordinary event in the real router. It linearizes before the
      ;; destroy claim but must never sit behind the cleanup seed.
      (with-redefs [rf.interop/next-tick
                    (fn [f]
                      (swap! captured-ticks conj f)
                      nil)]
        (rf/dispatch [:destroy-claim/ordinary] {:frame frame-id}))
      (is (= 1 (count (:queue @(:router (rf.frame/frame frame-id)))))
          "ordinary pre-claim work is waiting on the real router")

      (rf/destroy-frame! frame-id)
      ;; Flush both the bound callback's real-router drain and the original
      ;; pre-claim drain attempt. Neither may invoke application work.
      (doseq [tick @captured-ticks] (tick))
      (executor-barrier!)

      ;; the seed, its queued child and its effect run once; pre-claim work,
      ;; executor work bound inside cleanup, and nested dispatch-sync do not
      (is (= [1 1 1 0 0 0 nil]
             [@cleanup-runs @child-runs @effect-runs
              @ordinary-runs @escaped-runs @nested-sync-runs
              (rf.frame/frame frame-id)])))))

(deftest destroy-from-active-handler-runs-cleanup-and-cuts-off-old-queue
  (testing "self-destroy has one private cleanup cascade inside the active drain"
    (let [frame-id      :destroy-claim/inside-handler
          cleanup-runs  (atom 0)
          ordinary-runs (atom 0)
          captured-ticks (atom [])]
      (rf/make-frame {:id frame-id
                      :on-destroy [:destroy-claim/inside-cleanup]})
      (rf/reg-event :destroy-claim/inside-cleanup
        (fn [_ _]
          (swap! cleanup-runs inc)
          {}))
      (rf/reg-event :destroy-claim/inside-ordinary
        (fn [_ _]
          (swap! ordinary-runs inc)
          {}))
      (rf/reg-event :destroy-claim/self-destroy
        (fn [_ _]
          (rf.frame/destroy-frame! frame-id)
          {}))

      ;; Queue old work without letting its async drain start, then prepend a
      ;; synchronous destroying event. The handler already owns the real
      ;; router's drain serialization when destroy-frame! enters.
      (with-redefs [rf.interop/next-tick
                    (fn [f]
                      (swap! captured-ticks conj f)
                      nil)]
        (rf/dispatch [:destroy-claim/inside-ordinary] {:frame frame-id}))
      (rf/dispatch-sync [:destroy-claim/self-destroy] {:frame frame-id})
      (doseq [tick @captured-ticks] (tick))

      ;; the cleanup seed runs despite nested-sync rejection, and the
      ;; pre-existing queue is cut off by the handler's destroy claim
      (is (= [1 0 nil] [@cleanup-runs @ordinary-runs (rf.frame/frame frame-id)])))))

(deftest replace-container-on-destroyed-frame-does-not-npe
  (let [recorded (atom [])]
    (rf/register-listener! :trace ::rec (fn [ev] (swap! recorded conj ev)))
    (rf/make-frame {:id :race/destroyed-mid-write})
    (rf.frame/destroy-frame! :race/destroyed-mid-write)
    (let [container (rf.frame/app-db-container :race/destroyed-mid-write)]
      (is (= [nil nil]
             [container (rf.substrate.adapter/replace-container! container {:would :have :npe'd true})])
          "a destroyed frame's container is nil, and writing through it is a no-op"))
    (when rf.interop/debug-enabled?
      (is (some #(and (= :error (:op-type %))
                      (= :rf.error/write-after-destroy (:operation %)))
                @recorded)))))

(deftest frame-ids-round-trip
  ;; frame-ids reports exactly the registered frames; :rf/default is an
  ;; ordinary id (EP-0002) that init! never synthesises
  (rf/make-frame {:id :rf/default :doc "explicitly-registered ordinary default frame"})
  (rf/make-frame {:id :tenants/acme :doc "acme tenant"  :preset :default})
  (is (= #{:rf/default :tenants/acme} (rf/frame-ids)))
  (reset! rf.frame/frames {})
  (rf/init! rf.substrate.plain-atom/adapter)
  (rf/make-frame {:id :tenants/solo :doc "sole user frame"})
  (is (= #{:tenants/solo} (rf/frame-ids))))

(deftest frame-meta-round-trip
  ;; the flat :rf/frame-meta shape (Spec-Schemas): user metadata, preset keys and
  ;; lifecycle fields at the top level, no internal grouping
  (rf/make-frame {:id :tenants/acme :doc          "acme tenant"
                  :preset       :default
                  :fx-overrides {:rf.http/managed
                                 :rf.http/managed-canned-success}})
  (let [m (rf/frame-meta :tenants/acme)]
    (is (= [{:id           :tenants/acme
             :doc          "acme tenant"
             :preset       :default
             :fx-overrides {:rf.http/managed :rf.http/managed-canned-success}
             :destroyed?   false}
            true false false]
           [(select-keys m [:id :doc :preset :fx-overrides :destroyed?])
            (number? (:created-at m))
            (contains? m :config)
            (contains? m :lifecycle)]))))

(deftest on-destroy-throw-does-not-abort-teardown
  (rf/make-frame {:id :throwy/worker :doc       "throwy on-destroy frame"
                  :on-destroy [:throwy/blow-up]})
  (rf/reg-event :throwy/blow-up
    (fn [{:keys [db]} _]
      {:db (throw (ex-info ":throwy/intentional" {:purpose :test-fixture}))}))
  (rf/reg-event :throwy/seed (fn [{:keys [db]} _] {:db {:n 42}}))
  (rf/reg-sub :throwy/n (fn [db _] (:n db)))
  (rf/dispatch-sync [:throwy/seed] {:frame :throwy/worker})
  (let [pinned        (rf/subscribe [:throwy/n] {:frame :throwy/worker})
        dispose-fired (atom 0)
        traces        (atom [])]
    (is (= 42 @pinned))
    (re-frame.interop/add-on-dispose! pinned (fn [] (swap! dispose-fired inc)))
    (rf/register-listener! :trace ::rf2-r1ciy (fn [ev] (swap! traces conj ev)))
    ;; destroy returns normally, dissocs the frame and still walks the sub-cache
    (is (= [nil false 1]
           [(try (rf/destroy-frame! :throwy/worker)
                 (catch Throwable e e))
            (contains? @rf.frame/frames :throwy/worker)
            @dispose-fired]))
    (rf/unregister-listener! :trace ::rf2-r1ciy)
    (when rf.interop/debug-enabled?
      (is (= [[[:throwy/worker [:throwy/blow-up] true :fire-on-destroy-event!]] true]
             [(keep #(when (and (= :error (:op-type %))
                                (= :rf.error/on-destroy-handler-exception (:operation %)))
                       (let [tags (:tags %)]
                         [(:frame tags) (:event tags) (some? (:exception tags)) (:where tags)]))
                    @traces)
              (boolean (some #(= :rf.frame/destroyed (:operation %)) @traces))])
          "one on-destroy exception trace, and teardown continued to the destroyed trace"))))

(deftest re-entrant-destroy-from-on-destroy-is-silent-noop
  (let [on-destroy-count (atom 0)
        traces           (atom [])]
    (rf/make-frame {:id :reent/worker :doc        "re-entrancy frame"
                    :on-destroy [:reent/cleanup]})
    (rf/reg-event :reent/cleanup
      (fn [_ _]
        (swap! on-destroy-count inc)
        (rf.frame/destroy-frame! :reent/worker)
        {}))
    (rf/register-listener! :trace ::reent (fn [ev] (swap! traces conj ev)))
    (rf/destroy-frame! :reent/worker)
    (rf/unregister-listener! :trace ::reent)
    (is (= [1 nil] [@on-destroy-count (rf.frame/frame :reent/worker)])
        ":on-destroy fired once and teardown completed")
    (when rf.interop/debug-enabled?
      (is (= [1 0]
             [(count (filter #(= :rf.frame/destroyed (:operation %)) @traces))
              (count (filter #(= :rf.error/on-destroy-handler-exception (:operation %)) @traces))])
          "teardown ran once, and the re-entrant no-op is silent"))))

;; A make-frame VALUE carries its incarnation's token, so destroying the value
;; is incarnation-exact; a frame-id keyword destroys the current incarnation.

(deftest make-frame-value-carries-exact-incarnation-token
  (let [va (rf/make-frame {:id :inc/tok :doc "A"})]
    (is (= [true true]
           [(rf.frame/frame-value? va)
            (identical? (rf.frame/frame-value-incarnation-token va)
                        (rf.frame/frame-incarnation-token :inc/tok))])
        "make-frame returns a frame value carrying the live incarnation's token"))
  (let [va (rf/make-frame {:id :inc/rereg :doc "A"})
        vb (rf/make-frame {:id :inc/rereg :doc "A-refreshed"})]
    (is (identical? (rf.frame/frame-value-incarnation-token va)
                    (rf.frame/frame-value-incarnation-token vb))
        "re-registration preserves the incarnation")))

(deftest destroy-value-N-does-not-tear-down-successor-N+1
  (let [va      (rf/make-frame {:id :inc/x :doc "A"})
        a-token (rf.frame/frame-value-incarnation-token va)]
    (rf/destroy-frame! :inc/x)
    (is (nil? (rf.frame/frame :inc/x)))
    (let [vb      (rf/make-frame {:id :inc/x :doc "B"})
          b-token (rf.frame/frame-value-incarnation-token vb)]
      (rf/destroy-frame! va)
      (is (= [false true] [(identical? a-token b-token)
                           (identical? b-token (rf.frame/frame-incarnation-token :inc/x))])
          "a stale destroy of value A leaves the distinct successor B live")
      (rf/destroy-frame! vb)
      (is (nil? (rf.frame/frame :inc/x)) "B's own value releases it"))))

(deftest with-new-frame-exit-teardown-is-incarnation-exact
  ;; a body that destroys A and reseats B under the same id leaves B alive on exit
  (let [b-token (atom nil)]
    (rf/with-new-frame [f (rf/make-frame {:id :wnf/x :doc "A"})]
      (rf/destroy-frame! :wnf/x)
      (rf/make-frame {:id :wnf/x :doc "B"})
      (reset! b-token (rf.frame/frame-incarnation-token :wnf/x)))
    (is (identical? @b-token (rf.frame/frame-incarnation-token :wnf/x)))
    (rf/destroy-frame! :wnf/x))
  (rf/with-new-frame [f (rf/make-frame {:id :wnf/y :doc "solo"})]
    nil)
  (is (nil? (rf.frame/frame :wnf/y))
      "ordinary exit destroys exactly the incarnation it created"))

;; require-frame-provider-target! accepts a frame-id keyword or a live frame value.

(deftest require-frame-provider-target-accepts-both-target-spellings
  (rf/make-frame {:id :target/kw :doc "kw target"})
  (let [v (rf/make-frame {:id :target/val :doc "value target"})]
    (is (= [:target/kw :target/val]
           [(rf.frame/require-frame-provider-target! :target/kw 'test/where)
            (rf.frame/require-frame-provider-target! v 'test/where)])
        "a keyword and a frame value normalize to the frame id")))

(deftest require-frame-provider-target-rejects-neither-shape-with-supply-frame-target
  ;; anything that is neither a keyword nor a frame value takes the same path
  (is (= {:rf.error/id :rf.error/bad-frame-provider-arg
          :recovery    :supply-frame-target
          :received    "app"
          :where       'test/where}
         (select-keys (try
                        (rf.frame/require-frame-provider-target! "app" 'test/where)
                        ::no-throw
                        (catch clojure.lang.ExceptionInfo e (ex-data e)))
                      [:rf.error/id :recovery :received :where]))))

(deftest require-frame-provider-target-nil-remains-no-frame-context
  ;; nil is absence, a distinct category and recovery from a bad argument
  (is (= {:rf.error/id :rf.error/no-frame-context :recovery :supply-frame}
         (select-keys (try
                        (rf.frame/require-frame-provider-target! nil 'test/where)
                        ::no-throw
                        (catch clojure.lang.ExceptionInfo e (ex-data e)))
                      [:rf.error/id :recovery]))))

