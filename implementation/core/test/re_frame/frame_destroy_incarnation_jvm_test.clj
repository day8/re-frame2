(ns re-frame.frame-destroy-incarnation-jvm-test
  "Deterministic JVM barriers for incarnation-owned frame teardown: a destroyed
  incarnation A never leaks into, mutates, or lends authority to a same-id
  successor B.

  A destroy requested while A's drain is on the stack is PENDING (Spec 002
  §Destroy): A's event finishes as an ordinary event, and the teardown runs at
  its boundary. A destroy holds its id's reservation until its teardown, the
  epoch publication after the dissoc included, has finished, so a same-id B
  can be constructed only after that. These barriers pause A inside that
  publication; B is seated once A's teardown has finished.

  Which token is live, whose db write commits, whose child dispatch runs and
  which corpus-wide `:errors` record ships are production behaviour and
  asserted always-on. The epoch subsystem is fed by the dev trace,
  so under `-Dre-frame.debug=false` no capture buffer, epoch record, ring or
  `:rf.epoch*` fact exists: every read of those stores sits in a
  `(when rf.interop/debug-enabled? ...)` arm together with the precondition
  that licenses it, because a leak negative over two empty stores certifies
  nothing. Some scenarios are dev-only end to end, because their driver is a
  trace fact, `epoch/settle!` or epoch's lifecycle contribution, none of
  which exists in that posture."
  (:require [clojure.set :as set]
            [clojure.test :refer [deftest is use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.event-emit :as rf.event-emit]
            [re-frame.error-emit :as rf.error-emit]
            [re-frame.epoch :as rf.epoch]
            [re-frame.epoch.listeners :as rf.epoch.listeners]
            [re-frame.epoch.state :as rf.epoch.state]
            [re-frame.frame :as rf.frame]
            [re-frame.interceptor :as rf.interceptor]
            [re-frame.interop :as rf.interop]
            [re-frame.late-bind :as rf.late-bind]
            [re-frame.router :as rf.router]
            [re-frame.substrate.adapter :as rf.substrate.adapter]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.test-support :as rf.test-support])
  (:import [java.util.concurrent CountDownLatch TimeUnit]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter}))

(defn- executor-barrier!
  "Wait until work already submitted through `rf.interop/next-tick` has run."
  []
  (let [latch (CountDownLatch. 1)]
    (rf.interop/next-tick #(.countDown latch))
    (is (.await latch 5 TimeUnit/SECONDS)
        "the executor reached the deterministic barrier")))

(defn- wrap-epoch-member!
  "Replace the epoch contribution's destroy member with `(wrap original)`. The
  caller puts the saved contribution list back."
  [wrap]
  (let [original (:on-frame-destroyed (rf.frame/lifecycle-contribution :epoch))]
    (rf.frame/contribute-lifecycle! {:id :epoch :on-frame-destroyed (wrap original)})))

(defn- before-publish
  "A wrap whose member hands back epoch's after-dissoc publication preceded by
  `(pause! frame-id)`."
  [pause!]
  (fn [original]
    (fn [frame-id phase]
      (let [publish (original frame-id phase)]
        (fn []
          (pause! frame-id)
          (when publish (publish)))))))

(deftest ^:requires-debug a-same-id-make-frame-fails-fast-until-teardown-has-published
  ;; A's handler destroys A and returns a db and a child dispatch. Both land in
  ;; A: the db commits (A's :on-destroy reads it) and the child joins A's queue,
  ;; where the boundary drops it. A's teardown then pauses inside its epoch
  ;; notify, after the registry dissoc. A holds its id's reservation until that
  ;; notify has finished, so a same-id make-frame on another thread fails fast;
  ;; the retry after A's teardown seats B, which starts with none of A's state
  ;; and none of A's terminal facts.
  (let [id             :destroy/reservation-through-publish
        in-notify      (CountDownLatch. 1)
        release-a      (CountDownLatch. 1)
        first-epoch?   (atom true)
        child-runs     (atom 0)
        cleanup-saw    (atom nil)
        traces         (atom [])
        saved          @rf.frame/lifecycle-contributions
        make-b         (fn []
                         (deref (future
                                  (try (some? (rf/make-frame {:id id}))
                                       (catch clojure.lang.ExceptionInfo e
                                         (:rf.error/id (ex-data e)))))
                                5000 ::timeout))]
    (rf/reg-event :destroy/reservation-child
      (fn [{:keys [db]} _]
        (swap! child-runs inc)
        {:db (assoc db :child :from-a)}))
    (rf/reg-event :destroy/reservation-a
      (fn [_ _]
        (rf.frame/destroy-frame! id)
        {:db {:owner :a-tail}
         :fx [[:dispatch [:destroy/reservation-child]]]}))
    (rf/reg-event :destroy/reservation-cleanup
      (fn [{:keys [db]} _] (reset! cleanup-saw db) {}))
    (rf/reg-event :destroy/reservation-b
      (fn [_ _]
        {:db {:owner :b}}))
    (rf/make-frame {:id id :on-destroy [:destroy/reservation-cleanup]})
    (rf/register-listener! :trace ::reservation-through-publish
      (fn [ev]
        (when (and (= id (get-in ev [:tags :frame]))
                   (contains? #{:rf.frame/drain-interrupted
                                :rf.epoch/snapshotted}
                              (:operation ev)))
          (swap! traces conj ev))))
    (try
      (wrap-epoch-member!
        (before-publish
          (fn [frame-id]
            (when (and (= id frame-id)
                       (compare-and-set! first-epoch? true false))
              (.countDown in-notify)
              (.await release-a 10 TimeUnit/SECONDS)))))
      (let [dispatch-a (future
                         (rf/dispatch-sync [:destroy/reservation-a]
                                           {:frame id}))]
        (is (.await in-notify 10 TimeUnit/SECONDS)
            "A's event settled, and its boundary teardown is inside the epoch publication")
        (is (= [nil :rf.error/frame-construction-in-progress]
               [(rf.frame/frame id) (make-b)])
            "A is dissociated, yet a same-id make-frame on another thread fails fast")
        (.countDown release-a)
        (is (not= ::timeout (deref dispatch-a 5000 ::timeout))
            "A's teardown completes")
        (executor-barrier!)
        (let [a-facts @traces]
          (is (true? (make-b))
              "the retry after A's teardown has finished seats B")
          (rf/dispatch-sync [:destroy/reservation-b] {:frame id})
          (is (= [{:owner :a-tail} 0 {:owner :b}]
                 [@cleanup-saw @child-runs (rf.frame/frame-app-db-value id)])
              "A's db committed in A and its child was dropped in A; B starts clean")
          (when rf.interop/debug-enabled?
            ;; A's own event settled :ok; its boundary drop is one structural
            ;; report and one :halted-destroy record, all published before B
            ;; existed. B's history, buffer and ring hold only B's own event.
            (let [by-op        (group-by :operation a-facts)
                  snapshotted  #(mapv (fn [ev] (get-in ev [:tags %])) (get by-op :rf.epoch/snapshotted))
                  dispatch-ids #(into #{} (keep (fn [ev] (get-in ev [:tags :rf.trace/dispatch-id]))) %)
                  b-ring       (rf/trace-buffer id {:flat true})]
              (is (= [[1] [:ok :ok :halted-destroy] [:ok :ok :blocked]]
                     [(mapv #(get-in % [:tags :dropped-count])
                            (get by-op :rf.frame/drain-interrupted))
                      (snapshotted :outcome)
                      (snapshotted :summary)]))
              (is (= [[:destroy/reservation-b] true true #{}]
                     [(mapv :event-id (rf.epoch/epoch-history id))
                      (empty? (rf.epoch.state/buffer-for id))
                      (boolean (seq b-ring))
                      (set/intersection (dispatch-ids a-facts) (dispatch-ids b-ring))])
                  "B's history and ring hold only B's event")))))
      (finally
        (.countDown release-a)
        (rf/unregister-listener! :trace ::reservation-through-publish)
        (reset! rf.frame/lifecycle-contributions saved)))))

(deftest ^:requires-debug post-dead-member-failure-leaves-no-residual-ring
  ;; A throwing :post-dead member's warning is an ordinary ring push under A's
  ;; bare id. The post-dead phase runs before A's ring release, so the release
  ;; clears it; run after, the warning would recreate a residual ring for
  ;; destroyed A.
  (let [id           :destroy/ring-snapshot-fail
        warnings     (atom [])
        reports      (atom [])
        saved        @rf.frame/lifecycle-contributions]
    (rf/reg-event :destroy/ring-snap-a
      (fn [_ _] (rf.frame/destroy-frame! id) {:db {:owner :a-tail}}))
    (rf/make-frame {:id id})
    (rf/register-listener! :trace ::ring-snap-warn
      (fn [ev]
        (when (and (= id (get-in ev [:tags :frame]))
                   (= :rf.warning/teardown-hook-exception (:operation ev)))
          (swap! warnings conj ev))))
    (rf.error-emit/register-error-listener! ::ring-snap-warn
      (fn [r]
        (when (and (= :rf.error/frame-teardown-failed (:rf.error/id r))
                   (= id (:frame r)))
          (swap! reports conj r))))
    (try
      ;; Fail epoch's post-dead member for this pending destroy.
      (wrap-epoch-member!
        (fn [original]
          (fn [frame-id phase]
            (if (= id frame-id)
              (throw (ex-info "snapshot blew" {}))
              (original frame-id phase)))))
      (rf/dispatch-sync [:destroy/ring-snap-a] {:frame id})
      (executor-barrier!)
      (is (= [[[[:epoch :post-dead]]] nil]
             [(mapv #(mapv (juxt :hook :phase) (:hook-failures %)) @reports)
              (rf.frame/frame-incarnation-token id)])
          "one always-on report names the epoch member, and the destroy completes")
      (when rf.interop/debug-enabled?
        (is (= [[[:epoch :post-dead]] true true]
               [(mapv #(vector (get-in % [:tags :hook]) (get-in % [:tags :phase])) @warnings)
                (empty? (rf/trace-buffer id {:flat true}))
                (empty? (rf/trace-buffer id))])
            "the warning reaches the global listener and no residual ring survives A"))
      (finally
        (rf/unregister-listener! :trace ::ring-snap-warn)
        (rf.error-emit/unregister-error-listener! ::ring-snap-warn)
        (when (rf.frame/frame id) (rf.frame/destroy-frame! id))
        (reset! rf.frame/lifecycle-contributions saved)))))

(deftest ^:requires-debug snapshot-failure-still-publishes-and-reports
  ;; A throwing pre-dissoc snapshot does not abort teardown: the post-dissoc
  ;; publication still runs (with a nil bundle, so it fabricates no evidence
  ;; and still drops A's epoch stores), and one always-on report names epoch.
  (let [id             :destroy/snap-fail-integrated
        report-key     ::snap-fail-reports
        reports        (atom [])                       ; :rf.error/frame-teardown-failed
        publish-calls  (atom 0)
        original-snap  rf.epoch.listeners/snapshot-terminal-destroy-evidence!
        original-epoch rf.epoch.listeners/on-frame-destroyed!]
    (rf/reg-event :snap-fail/seed (fn [_ _] {:db {:n 0}}))
    (rf/make-frame {:id id})
    ;; A settles one event, so it owns real epoch state when it is destroyed.
    (rf/dispatch-sync [:snap-fail/seed] {:frame id})
    (rf.error-emit/register-error-listener! report-key
      (fn [r] (when (= :rf.error/frame-teardown-failed (:rf.error/id r))
                (swap! reports conj r))))
    (try
      ;; Fail the PRE-dissoc snapshot for this id (delegate every other id), and
      ;; spy the POST-dissoc publish to prove it ran, delegating to the real
      ;; (nil-bundle → no-op) publish.
      (with-redefs [rf.epoch.listeners/snapshot-terminal-destroy-evidence!
                    (fn [frame-id halt]
                      (if (= id frame-id)
                        (throw (ex-info "snapshot blew" {}))
                        (original-snap frame-id halt)))
                    rf.epoch.listeners/on-frame-destroyed!
                    (fn [frame-id evidence]
                      (when (= id frame-id)
                        (swap! publish-calls inc))
                      (original-epoch frame-id evidence))]
        (rf.frame/destroy-frame! id))
      (executor-barrier!)

      (is (= [1 [{:frame id :hooks [[:epoch :after-dissoc]] :recovery :ignored}] nil]
             [@publish-calls
              (mapv (fn [r] {:frame    (:frame r)
                             :hooks    (mapv (juxt :hook :phase) (:hook-failures r))
                             :recovery (:recovery r)})
                    @reports)
              (rf.frame/frame id)])
          "the publish still ran, one report names epoch once, and the frame is gone")
      (finally
        (rf.error-emit/unregister-error-listener! report-key)))))

;; A's :halted-destroy record fans to the listener registry snapshotted before
;; dissoc, so a listener registered while A's teardown is in its post-dissoc
;; publish never receives A's terminal record.

(deftest ^:requires-debug predecessor-halted-record-reaches-only-the-pre-dissoc-listener
  ;; cb observes A; A's event destroys A and queues a child, and A's boundary
  ;; teardown pauses post-dissoc; during the pause cb is REPLACED. A's halted
  ;; record goes to the callback registered when A was destroyed; the
  ;; replacement sees only the same-id B seated once A's teardown has finished.
  (let [id             :vxgfnd245/regen
        cb             ::vxgfnd245-regen-cb
        a-after-dissoc (CountDownLatch. 1)
        release-a      (CountDownLatch. 1)
        first-epoch?   (atom true)
        old-received   (atom 0)
        new-received   (atom 0)
        saved          @rf.frame/lifecycle-contributions]
    (rf/reg-event :vxgfnd245/regen-a-settle
      (fn [{:keys [db]} _] {:db (assoc db :a true)}))
    (rf/reg-event :vxgfnd245/regen-a-destroy
      (fn [_ _]
        (rf.frame/destroy-frame! id)
        {:db {:owner :a-tail} :fx [[:dispatch [:vxgfnd245/regen-a-dropped]]]}))
    (rf/reg-event :vxgfnd245/regen-a-dropped (fn [_ _] {}))
    (rf/reg-event :vxgfnd245/regen-b-settle
      (fn [{:keys [db]} _] {:db (assoc db :owner :b)}))
    (rf/make-frame {:id id})
    (rf/register-listener! :epoch cb (fn [_] (swap! old-received inc)))
    (rf/dispatch-sync [:vxgfnd245/regen-a-settle] {:frame id})
    (try
      (wrap-epoch-member!
        (before-publish
          (fn [frame-id]
            (when (and (= id frame-id)
                       (compare-and-set! first-epoch? true false))
              (.countDown a-after-dissoc)
              (.await release-a 10 TimeUnit/SECONDS)))))
      (let [dispatch-a (future
                         (rf/dispatch-sync [:vxgfnd245/regen-a-destroy] {:frame id}))]
        (is (.await a-after-dissoc 10 TimeUnit/SECONDS)
            "A paused post-dissoc")
        ;; Replace cb in the gap; B is seated once A's teardown has finished.
        (rf/register-listener! :epoch cb (fn [_] (swap! new-received inc)))
        (.countDown release-a)
        (is (not= ::timeout (deref dispatch-a 5000 ::timeout))
            "A's terminal recipe completes")
        (executor-barrier!)
        (rf/make-frame {:id id})
        (rf/dispatch-sync [:vxgfnd245/regen-b-settle] {:frame id})
        (is (= {:owner :b} (rf.frame/frame-app-db-value id))
            "B starts from its own state, not A's committed tail")
        (when rf.interop/debug-enabled?
          ;; the replaced callback got A's two settles and A's halted record;
          ;; the replacement saw only B's settle
          (is (= [1 3] [@new-received @old-received])
              "A's halted record reaches the callback registered when A was destroyed")))
      (finally
        (.countDown release-a)
        (rf/unregister-listener! :epoch cb)
        (when (rf.frame/frame id) (rf.frame/destroy-frame! id))
        (reset! rf.frame/lifecycle-contributions saved)))))

;; Structural delivery turns epoch capture, frame policy and ring retention off
;; around its emit, and restores ordinary defaults around the listener fan-out,
;; so work a listener dispatches into another frame is captured, retained and
;; policed as that frame's own. Only a dispatching listener can see the scope.

(defn- with-failing-epoch-publish
  "Run `f` with epoch's after-dissoc publication throwing for `frame-id` (every
  other id delegates), so that frame's top-level destroy fans a real structural
  terminal fact: the `:rf.warning/teardown-hook-exception` naming epoch."
  [frame-id f]
  (let [saved @rf.frame/lifecycle-contributions]
    (wrap-epoch-member!
      (fn [original]
        (fn [id phase]
          (let [publish (original id phase)]
            (fn []
              (if (= frame-id id)
                (throw (ex-info "epoch publish blew" {}))
                (when publish (publish))))))))
    (try
      (f)
      (finally
        (reset! rf.frame/lifecycle-contributions saved)))))

(deftest listener-dispatch-during-structural-terminal-fact-runs-under-normal-scope
  ;; A's top-level destroy fans a structural terminal fact; a listener reacting
  ;; to it dispatches into unrelated frame C, whose work is captured and
  ;; retained normally. Under A's inherited scope C's history and ring would be
  ;; empty while its db still committed.
  (let [a-id   :vf2qke/terminal-a
        c-id   :vf2qke/nested-c
        fired? (atom false)
        c-live (atom [])]
    (rf/make-frame {:id c-id})
    (rf/reg-event :vf2qke/c-event
      (fn [{:keys [db]} _] {:db (assoc db :c :ran)}))
    (rf/make-frame {:id a-id})
    ;; The dispatching public listener: on A's structural terminal fact, run
    ;; ordinary nested work into unrelated C exactly once.
    (rf/register-listener! :trace ::vf2qke-dispatcher
      (fn [ev]
        (when (and (= a-id (get-in ev [:tags :frame]))
                   (= :rf.warning/teardown-hook-exception (:operation ev))
                   (compare-and-set! fired? false true))
          (rf/dispatch-sync [:vf2qke/c-event] {:frame c-id}))))
    ;; A raw trace listener capturing C's own run events (proves live delivery).
    (rf/register-listener! :trace ::vf2qke-c-live
      (fn [ev]
        (when (= c-id (get-in ev [:tags :frame]))
          (swap! c-live conj ev))))
    (try
      (with-failing-epoch-publish a-id #(rf/destroy-frame! a-id))
      (executor-barrier!)
      ;; dev-only end to end: the driver is a trace fact the gate never emits
      (when rf.interop/debug-enabled?
        (is (= [true 1 {:c :ran} true true]
               [@fired?
                (count (rf.epoch/epoch-history c-id))
                (rf.frame/frame-app-db-value c-id)
                (boolean (seq (rf/trace-buffer c-id {:flat true})))
                (boolean (seq @c-live))])
            "C's listener-triggered work ran, was captured and retained, and streamed live"))
      (finally
        (rf/unregister-listener! :trace ::vf2qke-dispatcher)
        (rf/unregister-listener! :trace ::vf2qke-c-live)
        (when (rf.frame/frame a-id) (rf.frame/destroy-frame! a-id))
        (when (rf.frame/frame c-id) (rf.frame/destroy-frame! c-id))))))

(deftest listener-triggered-work-obeys-nested-frame-no-emit-policy
  ;; the listener dispatches into D, whose own no-emit policy governs that work
  (let [a-id   :vf2qke/policy-a
        d-id   :vf2qke/nested-noemit-d
        fired? (atom false)
        d-live (atom [])]
    (rf/make-frame {:id d-id :rf.trace/frame-no-emit? true})
    (rf/reg-event :vf2qke/d-event
      (fn [{:keys [db]} _] {:db (assoc db :d :ran)}))
    (rf/make-frame {:id a-id})
    (rf/register-listener! :trace ::vf2qke-policy-dispatcher
      (fn [ev]
        (when (and (= a-id (get-in ev [:tags :frame]))
                   (= :rf.warning/teardown-hook-exception (:operation ev))
                   (compare-and-set! fired? false true))
          (rf/dispatch-sync [:vf2qke/d-event] {:frame d-id}))))
    (rf/register-listener! :trace ::vf2qke-d-live
      (fn [ev]
        (when (= d-id (get-in ev [:tags :frame]))
          (swap! d-live conj ev))))
    (try
      (with-failing-epoch-publish a-id #(rf/destroy-frame! a-id))
      (executor-barrier!)
      (when rf.interop/debug-enabled?
        (is (= [true {:d :ran} [] true]
               [@fired?
                (rf.frame/frame-app-db-value d-id)
                @d-live
                (empty? (rf/trace-buffer d-id {:flat true}))])
            "D's work ran and committed, and its no-emit policy suppressed every trace"))
      (finally
        (rf/unregister-listener! :trace ::vf2qke-policy-dispatcher)
        (rf/unregister-listener! :trace ::vf2qke-d-live)
        (when (rf.frame/frame a-id) (rf.frame/destroy-frame! a-id))
        (when (rf.frame/frame d-id) (rf.frame/destroy-frame! d-id))))))

(deftest ^:requires-debug an-epoch-publish-failure-leaves-no-residual-ring
  ;; The after-dissoc publication runs after A's ring was released. A destroy
  ;; issued from another frame's event runs under that event's dispatch-id, so
  ;; its warning pushed under ordinary delivery would recreate a ring for A's
  ;; bare id.
  ;; Structural delivery streams it live and retains it nowhere.
  (let [a-id     :destroy/ring-publish-fail
        c-id     :destroy/ring-publish-caller
        warnings (atom [])
        reports  (atom [])]
    (rf/reg-event :destroy/ring-publish-from-c
      (fn [_ _] (rf.frame/destroy-frame! a-id) {}))
    (rf/make-frame {:id a-id})
    (rf/make-frame {:id c-id})
    (rf/register-listener! :trace ::ring-publish-warn
      (fn [ev]
        (when (and (= a-id (get-in ev [:tags :frame]))
                   (= :rf.warning/teardown-hook-exception (:operation ev)))
          (swap! warnings conj ev))))
    (rf.error-emit/register-error-listener! ::ring-publish-warn
      (fn [r]
        (when (and (= :rf.error/frame-teardown-failed (:rf.error/id r))
                   (= a-id (:frame r)))
          (swap! reports conj r))))
    (try
      (with-failing-epoch-publish a-id
        #(rf/dispatch-sync [:destroy/ring-publish-from-c] {:frame c-id}))
      (is (= [[[[:epoch :after-dissoc]]] nil]
             [(mapv #(mapv (juxt :hook :phase) (:hook-failures %)) @reports)
              (rf.frame/frame a-id)])
          "one always-on report names epoch's publication, and the destroy completes")
      (when rf.interop/debug-enabled?
        (is (= [[[:epoch :after-dissoc]] true true true]
               [(mapv #(vector (get-in % [:tags :hook]) (get-in % [:tags :phase])) @warnings)
                (some? (get-in (first @warnings) [:tags :rf.trace/dispatch-id]))
                (empty? (rf/trace-buffer a-id {:flat true}))
                (empty? (rf/trace-buffer a-id))])
            "the warning runs under C's dispatch-id, reaches the listener, and no ring survives A"))
      (finally
        (rf/unregister-listener! :trace ::ring-publish-warn)
        (rf.error-emit/unregister-error-listener! ::ring-publish-warn)
        (when (rf.frame/frame c-id) (rf.frame/destroy-frame! c-id))))))

(deftest a-destroy-from-an-interceptor-lets-the-whole-chain-run
  ;; An interceptor's :before requests its own frame's destroy. The destroy is
  ;; pending, so the rest of the chain — the next :before, the handler, every
  ;; :after — runs, the handler's db commits, and the frame is torn down at the
  ;; event's boundary, where :on-destroy sees that db.
  (let [id           :destroy/authored-unwind
        outer-before (atom 0)
        outer-after  (atom 0)
        killer-after (atom 0)
        later-before (atom 0)
        handler-runs (atom 0)
        cleanup-saw  (atom nil)]
    (rf/reg-interceptor :destroy/outer
      {:before #(do (swap! outer-before inc) %)
       :after  #(do (swap! outer-after inc) %)})
    (rf/reg-interceptor :destroy/killer
      {:before #(do (rf.frame/destroy-frame! id) %)
       :after  #(do (swap! killer-after inc) %)})
    (rf/reg-interceptor :destroy/later
      {:before #(do (swap! later-before inc) %)})
    (rf/reg-event :destroy/unwind
      {:interceptors [:destroy/outer :destroy/killer :destroy/later]}
      (fn [_ _] (swap! handler-runs inc) {:db {:committed true}}))
    (rf/reg-event :destroy/unwind-cleanup
      (fn [{:keys [db]} _] (reset! cleanup-saw db) {}))
    (rf/make-frame {:id id :on-destroy [:destroy/unwind-cleanup]})
    (rf/dispatch-sync [:destroy/unwind] {:frame id})
    ;; [outer-before killer-after outer-after later-before handler]
    (is (= [[1 1 1 1 1] {:committed true} nil]
           [[@outer-before @killer-after @outer-after @later-before @handler-runs]
            @cleanup-saw
            (rf.frame/frame id)])
        "the chain ran to its end, its db committed, and teardown followed")))

(deftest destroy-then-throw-from-an-interceptor-settles-and-tears-down
  ;; A :before requests the destroy and throws. The throw is captured and
  ;; reported as an interceptor exception, the entered :after still unwinds,
  ;; the handler is skipped, the event settles once through the normal epoch
  ;; hook, and the frame is torn down at the boundary.
  (let [id              :destroy/throw-before
        after-runs      (atom 0)
        handler-runs    (atom 0)
        normal-settles  (atom 0)
        errors          (atom [])
        original-settle (rf.late-bind/get-fn :epoch/settle!)]
    (rf/make-frame {:id id})
    (rf/reg-interceptor :destroy/throwing
      {:before (fn [_]
                 (rf.frame/destroy-frame! id)
                 (throw (ex-info "interceptor failed after asking for its destroy" {})))
       :after  #(do (swap! after-runs inc) %)})
    (rf/reg-event :destroy/throwing-event
      {:interceptors [:destroy/throwing]}
      (fn [_ _] (swap! handler-runs inc) {}))
    (rf.error-emit/register-error-listener! ::throw-before
      (fn [record] (when (= id (:frame record)) (swap! errors conj (:rf.error/id record)))))
    (try
      (rf.late-bind/set-fn!
        :epoch/settle!
        (fn [& args]
          (swap! normal-settles inc)
          (when original-settle (apply original-settle args))))
      (is (nil? (rf/dispatch-sync [:destroy/throwing-event] {:frame id}))
          "the throw is captured inside the event")
      (finally
        (rf.error-emit/unregister-error-listener! ::throw-before)
        (rf.late-bind/set-fn! :epoch/settle! original-settle)))
    (is (= [1 0 1 [:rf.error/interceptor-exception] nil]
           [@after-runs @handler-runs @normal-settles @errors (rf.frame/frame id)])
        "the after unwound, the event settled and reported, and the frame is gone")))

(deftest an-effect-that-destroys-its-frame-lets-the-later-effects-run
  ;; The first :fx entry requests the destroy; the walk carries on, so the
  ;; second entry runs, and the frame is torn down at the event's boundary.
  (let [id          :destroy/fx-tail
        second-runs (atom 0)]
    (rf/make-frame {:id id})
    (rf/reg-fx :destroy/first
      (fn [_ _] (rf.frame/destroy-frame! id)))
    (rf/reg-fx :destroy/second
      (fn [_ _] (swap! second-runs inc)))
    (rf/reg-event :destroy/fx-event
      (fn [_ _] {:fx [[:destroy/first nil] [:destroy/second nil]]}))
    (rf/dispatch-sync [:destroy/fx-event] {:frame id})
    (is (= [1 nil] [@second-runs (rf.frame/frame id)]))))

(deftest a-throw-that-escapes-the-drain-still-runs-the-pending-teardown
  ;; The pre-event frame-state read is outside the event's own error capture,
  ;; so a throw there escapes the drain to the dispatch-sync caller. It ends
  ;; the event too, and a destroy requested before it is torn down as the
  ;; drain unwinds: the frame is gone and its reservation released.
  (let [id           :destroy/adapter-read
        armed?       (atom true)
        handler-runs (atom 0)
        original     rf.substrate.adapter/read-container]
    (rf/make-frame {:id id})
    (rf/reg-event :destroy/adapter-read-event
      (fn [_ _] (swap! handler-runs inc) {:db {:forbidden true}}))
    (with-redefs [rf.substrate.adapter/read-container
                  (fn [container]
                    (if (compare-and-set! armed? true false)
                      (do (rf.frame/destroy-frame! id)
                          (throw (ex-info "read failed" {})))
                      (original container)))]
      (is (= "read failed"
             (try (rf/dispatch-sync [:destroy/adapter-read-event] {:frame id})
                  nil
                  (catch Throwable t (ex-message t))))
          "the escaping throw reaches the caller"))
    (is (= [0 nil] [@handler-runs (rf.frame/frame id)])
        "no handler ran, and the pending destroy still tore the frame down")
    (rf/make-frame {:id id})
    (is (some? (rf.frame/frame id)) "the reservation was released")))

(deftest a-same-id-make-frame-while-a-destroy-is-pending-fails-fast
  ;; A's handler requests A's destroy and then waits. While it waits, another
  ;; thread tries to construct the same id: the pending destroy already holds
  ;; the id's reservation, so construction fails fast with the in-progress
  ;; error. Once A's event settles and its teardown finishes, the id is free.
  (let [id         :destroy/pending-reservation
        requested  (CountDownLatch. 1)
        release-a  (CountDownLatch. 1)]
    (rf/reg-event :destroy/pending-wait
      (fn [_ _]
        (rf.frame/destroy-frame! id)
        (.countDown requested)
        (.await release-a 10 TimeUnit/SECONDS)
        {:db {:a :committed}}))
    (rf/make-frame {:id id})
    (let [dispatch-a (future (rf/dispatch-sync [:destroy/pending-wait] {:frame id}))]
      (try
        (is (.await requested 10 TimeUnit/SECONDS) "A's destroy is pending")
        (is (= :rf.error/frame-construction-in-progress
               (try (rf/make-frame {:id id}) nil
                    (catch Throwable t (:rf.error/id (ex-data t)))))
            "a same-id construction meets the pending destroy's reservation")
        (is (= {:a nil} {:a (:a (rf.frame/frame-app-db-value id))})
            "A is still live and its event has not committed yet")
        (finally
          (.countDown release-a)))
      (is (not= ::timeout (deref dispatch-a 5000 ::timeout)))
      (is (nil? (rf.frame/frame id)) "A was torn down at its event's boundary")
      (rf/make-frame {:id id})
      (is (some? (rf.frame/frame id)) "the id is free once the teardown finished"))))

(deftest a-cofx-supplier-that-destroys-its-frame-and-throws
  ;; The first supplier requests the destroy and throws. The failure is
  ;; reported as a coeffect exception, the handler is skipped, and the frame
  ;; is torn down at the event's boundary.
  (let [id           :destroy/cofx-tail
        handler-runs (atom 0)
        errors       (atom [])]
    (rf/make-frame {:id id})
    (rf/reg-cofx :destroy/cofx-killer {:recordable? false}
      (fn []
        (rf.frame/destroy-frame! id)
        (throw (ex-info "cofx supplier failed after asking for its destroy" {}))))
    (rf/reg-event :destroy/cofx-event
      {:rf.cofx/requires [:destroy/cofx-killer]}
      (fn [_ _] (swap! handler-runs inc) {:db {:forbidden true}}))
    (rf.error-emit/register-error-listener! ::cofx-loss
      (fn [record] (when (= id (:frame record)) (swap! errors conj (:rf.error/id record)))))
    (try
      (is (= [nil 0 [:rf.error/coeffect-exception] nil]
             [(rf/dispatch-sync [:destroy/cofx-event] {:frame id})
              @handler-runs @errors (rf.frame/frame id)]))
      (finally
        (rf.error-emit/unregister-error-listener! ::cofx-loss)))))

(deftest a-dispatch-to-another-frame-after-the-destroy-request-lands
  ;; A's handler dispatches into live frame B before and after requesting A's
  ;; destroy. A's event runs to its end, so both dispatches reach B.
  (let [a-id :destroy/ambient-owner-a
        b-id :destroy/ambient-owner-b]
    (rf/make-frame {:id a-id})
    (rf/make-frame {:id b-id})
    (rf/reg-event :destroy/ambient-b-event
      (fn [{:keys [db]} [_ marker]]
        {:db (update db :seen (fnil conj []) marker)}))
    (rf/reg-event :destroy/ambient-a-event
      (fn [_ _]
        (rf/with-frame b-id
          (rf/dispatch [:destroy/ambient-b-event :before-request]))
        (rf.frame/destroy-frame! a-id)
        (rf/with-frame b-id
          (rf/dispatch [:destroy/ambient-b-event :after-request]))
        {}))
    (rf/dispatch-sync [:destroy/ambient-a-event] {:frame a-id})
    (executor-barrier!)
    (is (= [{:seen [:before-request :after-request]} nil]
           [(rf.frame/frame-app-db-value b-id) (rf.frame/frame a-id)]))))

(deftest async-and-sync-drains-never-retarget-same-id-successor
  ;; Mutation teeth: making drain-try!/drain-block! resolve a bare frame id
  ;; causes the stale A callbacks below to drain B.
  (let [id             :destroy/drain-retarget
        runs           (atom 0)
        ticks          (atom [])
        original-block (var-get (ns-resolve 're-frame.router 'drain-block!))
        block-entered  (CountDownLatch. 1)
        release-block  (CountDownLatch. 1)]
    (rf/reg-event :destroy/drain-retarget-event
      (fn [{:keys [db]} _]
        (swap! runs inc)
        {:db (assoc db :ran true)}))

    ;; Async scheduler callback accepted A, then runs after B replaced it.
    (rf/make-frame {:id id})
    (with-redefs [rf.interop/next-tick #(swap! ticks conj %)]
      (rf/dispatch [:destroy/drain-retarget-event] {:frame id}))
    (rf.frame/destroy-frame! id)
    (rf/make-frame {:id id})
    ((first @ticks))
    (is (= [0 {}] [@runs (rf.frame/frame-app-db-value id)])
        "the obsolete async A callback did not drain B")

    ;; Pause dispatch-sync after it captured A but before its exact drain entry.
    (rf.frame/destroy-frame! id)
    (rf/make-frame {:id id})
    (let [dispatch-a
          (with-redefs-fn
            {(ns-resolve 're-frame.router 'drain-block!)
             (fn [& args]
               (.countDown block-entered)
               (.await release-block 10 TimeUnit/SECONDS)
               (apply original-block args))}
            #(let [f (future
                       (rf/dispatch-sync [:destroy/drain-retarget-event]
                                         {:frame id}))]
               (is (.await block-entered 10 TimeUnit/SECONDS)
                   "dispatch-sync captured A before replacement")
               f))]
      (rf.frame/destroy-frame! id)
      (rf/make-frame {:id id})
      (.countDown release-block)
      (is (= [true 0 {}]
             [(not= ::timeout (deref dispatch-a 5000 ::timeout))
              @runs
              (rf.frame/frame-app-db-value id)])
          "the stale synchronous drain returned without executing in B"))))

(deftest listeners-keep-running-after-one-destroys-the-frame
  ;; A trace listener and an always-on event listener each destroy the frame
  ;; they observe and throw. Every later listener still runs, and so does the
  ;; frame-owned observation route: a listener's destroy is a lifecycle
  ;; request, not a cutoff of the event that is being observed.
  (let [trace-id       :destroy/trace-fanout
        event-id       :destroy/event-fanout
        trace-sibling  (atom 0)
        event-sibling  (atom 0)
        handler-runs   (atom 0)
        routed         (atom 0)
        original-route (rf.late-bind/get-fn :observability/route-handled-event)]
    (rf/make-frame {:id trace-id})
    (rf/reg-event :destroy/trace-fanout-event
      (fn [_ _] (swap! handler-runs inc) {}))
    (rf/register-listener! :trace ::trace-destroyer
      (fn [ev]
        (when (and (= :rf.event/run-start (:operation ev))
                   (= trace-id (get-in ev [:tags :frame])))
          (rf.frame/destroy-frame! trace-id)
          (throw (ex-info "trace listener failed" {})))))
    (rf/register-listener! :trace ::trace-sibling
      (fn [ev]
        (when (and (= :rf.event/run-start (:operation ev))
                   (= trace-id (get-in ev [:tags :frame])))
          (swap! trace-sibling inc))))
    (try
      (rf/dispatch-sync [:destroy/trace-fanout-event] {:frame trace-id})
      ;; trace delivery is dev-only
      (when rf.interop/debug-enabled?
        (is (= [1 1 nil] [@trace-sibling @handler-runs (rf.frame/frame trace-id)])
            "the sibling trace listener ran, and the frame was destroyed"))
      (finally
        (rf/unregister-listener! :trace ::trace-destroyer)
        (rf/unregister-listener! :trace ::trace-sibling)))

    (rf/make-frame {:id event-id})
    (rf/reg-event :destroy/event-fanout-event (fn [_ _] {}))
    (rf.event-emit/register-event-listener! ::event-destroyer
      (fn [_]
        (rf.frame/destroy-frame! event-id)
        (throw (ex-info "event listener failed" {}))))
    (rf.event-emit/register-event-listener! ::event-sibling
      (fn [_] (swap! event-sibling inc)))
    (try
      (rf.late-bind/set-fn! :observability/route-handled-event
        (fn [& _] (swap! routed inc)))
      (rf/dispatch-sync [:destroy/event-fanout-event] {:frame event-id})
      (finally
        (rf.event-emit/unregister-event-listener! ::event-destroyer)
        (rf.event-emit/unregister-event-listener! ::event-sibling)
        (rf.late-bind/set-fn! :observability/route-handled-event original-route)))
    (is (= [1 1 nil] [@event-sibling @routed (rf.frame/frame event-id)])
        "the later corpus listener and the frame-owned route both ran")))

(deftest a-union-error-listener-that-destroys-the-frame-leaves-the-fanout-whole
  (let [id             :destroy/union-error-fanout
        sibling-runs   (atom 0)
        route-runs     (atom 0)
        original-route (rf.late-bind/get-fn :observability/route-error-record)]
    (rf/make-frame {:id id})
    (rf.error-emit/register-error-listener! ::union-destroyer
      (fn [record]
        (when (= :rf.error/test-union (:rf.error/id record))
          (rf.frame/destroy-frame! id)
          (throw (ex-info "union listener failed" {})))))
    (rf.error-emit/register-error-listener! ::union-sibling
      (fn [record]
        (when (= :rf.error/test-union (:rf.error/id record))
          (swap! sibling-runs inc))))
    (rf/reg-event :destroy/union-error-event
      (fn [_ _]
        (rf.error-emit/dispatch-error-record!
          {:rf.error/id :rf.error/test-union :frame id :time 0})
        {}))
    (try
      (rf.late-bind/set-fn! :observability/route-error-record
        (fn [record & _]
          (when (= :rf.error/test-union (:rf.error/id record))
            (swap! route-runs inc))))
      (rf/dispatch-sync [:destroy/union-error-event] {:frame id})
      (finally
        (rf.error-emit/unregister-error-listener! ::union-destroyer)
        (rf.error-emit/unregister-error-listener! ::union-sibling)
        (rf.late-bind/set-fn! :observability/route-error-record original-route)))
    (is (= [1 1 nil] [@sibling-runs @route-runs (rf.frame/frame id)])
        "the later union listener and the frame-owned union route both ran")))

(deftest a-depth-error-listener-that-destroys-the-frame-lets-the-halt-finish
  ;; The depth halt fans its always-on record to the corpus and the frame
  ;; route. The first listener requests A's destroy; the halt finishes — the
  ;; sibling listener and the frame route both see the record — and A is torn
  ;; down before the drain dequeues anything else.
  (let [id             :drain.incarnation/depth-loss
        depth-records  (atom [])
        sibling-runs   (atom 0)
        frame-routes   (atom 0)
        original-route (rf.late-bind/get-fn :observability/route-error-record)]
    (rf.error-emit/clear-error-listeners!)
    (rf/reg-event :drain.incarnation/loop
      (fn [_ _] {:fx [[:dispatch [:drain.incarnation/loop]]]}))
    (rf/make-frame {:id id :drain-depth 4})
    (try
      (rf.late-bind/set-fn! :observability/route-error-record
        (fn [record & more]
          (when (= :rf.error/drain-depth-exceeded (:rf.error/id record))
            (swap! frame-routes inc))
          (when original-route (apply original-route record more))))
      ;; registered first, so the array-map corpus registry fans it first
      (rf.error-emit/register-error-listener! ::depth-destroyer
        (fn [record]
          (when (= :rf.error/drain-depth-exceeded (:rf.error/id record))
            (swap! depth-records conj record)
            (rf.frame/destroy-frame! id))))
      (rf.error-emit/register-error-listener! ::depth-sibling
        (fn [record]
          (when (= :rf.error/drain-depth-exceeded (:rf.error/id record))
            (swap! sibling-runs inc))))
      (rf/dispatch-sync [:drain.incarnation/loop] {:frame id})
      (executor-barrier!)
      (is (= [[id] 1 1 nil]
             [(mapv :frame @depth-records)
              @sibling-runs
              @frame-routes
              (rf.frame/frame id)])
          "every listener and the frame route saw the halt, then A was torn down")
      (finally
        (rf.error-emit/unregister-error-listener! ::depth-destroyer)
        (rf.error-emit/unregister-error-listener! ::depth-sibling)
        (rf.error-emit/clear-error-listeners!)
        (rf.late-bind/set-fn! :observability/route-error-record original-route)
        (when (rf.frame/frame id) (rf.frame/destroy-frame! id))))))

(deftest depth-halt-fans-and-commits-normally-when-a-retains-ownership
  ;; the control: no listener destroys A, so every corpus listener sees the
  ;; depth record and A's :halted-depth record lands in A's own history
  (let [id            :drain.incarnation/depth-live
        depth-records (atom [])
        sibling-runs  (atom 0)]
    (rf.error-emit/clear-error-listeners!)
    (rf/reg-event :drain.incarnation/live-loop
      (fn [_ _] {:fx [[:dispatch [:drain.incarnation/live-loop]]]}))
    (rf/make-frame {:id id :drain-depth 4})
    (try
      (rf.error-emit/register-error-listener! ::live-a
        (fn [record]
          (when (= :rf.error/drain-depth-exceeded (:rf.error/id record))
            (swap! depth-records conj record))))
      (rf.error-emit/register-error-listener! ::live-b
        (fn [record]
          (when (= :rf.error/drain-depth-exceeded (:rf.error/id record))
            (swap! sibling-runs inc))))
      (rf/dispatch-sync [:drain.incarnation/live-loop] {:frame id})
      (executor-barrier!)
      (is (= [[id] 1] [(mapv :frame @depth-records) @sibling-runs])
          "every corpus error listener receives the depth record naming A")
      (when rf.interop/debug-enabled?
        (is (some #(= :halted-depth (:outcome %)) (rf.epoch/epoch-history id))
            "A's terminal halted-depth record is committed into A's own history"))
      (finally
        (rf.error-emit/unregister-error-listener! ::live-a)
        (rf.error-emit/unregister-error-listener! ::live-b)
        (rf.error-emit/clear-error-listeners!)
        (when (rf.frame/frame id) (rf.frame/destroy-frame! id))))))
