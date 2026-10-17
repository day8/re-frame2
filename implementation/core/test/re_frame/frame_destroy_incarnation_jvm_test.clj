(ns re-frame.frame-destroy-incarnation-jvm-test
  "Deterministic JVM barriers for incarnation-owned frame teardown: a destroyed
  incarnation A never leaks into, mutates, or lends authority to a same-id
  successor B.

  A destroy requested while A's drain is on the stack is PENDING (Spec 002
  §Destroy): A's event finishes as an ordinary event, and the teardown runs at
  its boundary while A still holds its id's reservation, so no same-id B can be
  constructed until A's teardown has dissociated A. These barriers pause A after
  that dissoc and seat B there.

  Which token is live, whose db write commits, whose child dispatch runs and
  which corpus-wide `:errors` record ships are production behaviour and
  asserted always-on. The epoch subsystem is fed by the dev trace,
  so under `-Dre-frame.debug=false` no capture buffer, epoch record, ring or
  `:rf.epoch*` fact exists: every read of those stores sits in a
  `(when rf.interop/debug-enabled? ...)` arm together with the precondition
  that licenses it, because a leak negative over two empty stores certifies
  nothing. Three scenarios are dev-only end to end, because their driver is a
  trace fact or `epoch/settle!`, neither of which runs in that posture."
  (:require [clojure.set :as set]
            [clojure.test :refer [deftest is use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.event-emit :as rf.event-emit]
            [re-frame.error-emit :as rf.error-emit]
            [re-frame.epoch :as rf.epoch]
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

(deftest stale-destroy-revalidates-after-candidate-capture
  ;; Pause the expected-token destroy AFTER it captures incarnation A. Another
  ;; actor replaces the id with B before the destroy enters drain serialization.
  ;; On resume, core must revalidate the token under the lifecycle gate and no-op
  ;; rather than applying A's stale authority to B.
  (rf/make-frame {:id :destroy/race})
  (let [token-a   (rf.frame/frame-incarnation-token :destroy/race)
        captured  (CountDownLatch. 1)
        release   (CountDownLatch. 1)
        probe-var (ns-resolve 're-frame.frame '*destroy-claim-probe*)
        stale     (with-bindings
                    {probe-var
                     (fn [id token]
                       (when (and (= :destroy/race id)
                                  (identical? token-a token))
                         (.countDown captured)
                         (.await release 10 TimeUnit/SECONDS)))}
                    (future
                      (rf.frame/destroy-frame! :destroy/race token-a)))]
    (is (.await captured 10 TimeUnit/SECONDS)
        "the stale destroy captured incarnation A before the replacement")
    (rf.frame/destroy-frame! :destroy/race)
    (rf/make-frame {:id :destroy/race})
    (let [token-b (rf.frame/frame-incarnation-token :destroy/race)]
      (is (and (some? token-b) (not (identical? token-a token-b)))
          "the actor installed a distinct incarnation B")
      (.countDown release)
      (is (= [nil true]
             [@stale (identical? token-b (rf.frame/frame-incarnation-token :destroy/race))])
          "the stale expected-token destroy is a silent no-op and B survives"))))

(deftest fresh-same-id-destroy-replaces-stale-marker-token-safely
  ;; Pause A after its registry dissoc but before its terminal finally. Install
  ;; B under the reused id, claim B's destroy, then let A's finally run while B
  ;; is paused pre-liveness-flip. B must be destroyable despite A's stale marker,
  ;; and A's finally must not erase B's replacement marker.
  (let [id              :destroy/marker-overlap
        a-after-dissoc  (CountDownLatch. 1)
        release-a       (CountDownLatch. 1)
        b-claimed       (CountDownLatch. 1)
        release-b       (CountDownLatch. 1)
        first-epoch?    (atom true)
        b-installed?    (atom false)
        cleanup-runs    (atom 0)
        original-epoch  (rf.late-bind/get-fn :epoch/on-frame-destroyed)
        original-machines     (rf.late-bind/get-fn :machines/teardown-on-frame-destroy!)]
    (rf/make-frame {:id id})
    (try
      (rf.late-bind/set-fn!
        :epoch/on-frame-destroyed
        (fn [& args]
          ;; The epoch hook is after dissoc-frame!. Hold only A's first call.
          (when (and (= id (first args))
                     (compare-and-set! first-epoch? true false))
            (.countDown a-after-dissoc)
            (.await release-a 10 TimeUnit/SECONDS))
          (when original-epoch
            (apply original-epoch args))))
      (rf.late-bind/set-fn!
        :machines/teardown-on-frame-destroy!
        (fn [frame-id]
          (when original-machines (original-machines frame-id))
          ;; The machines teardown hook is after claim publication and before B's
          ;; lifecycle flip. Hold every B teardown attempt here: an erroneously-authorised
          ;; duplicate will block, making A-finally marker erasure observable.
          (when (and (= id frame-id) @b-installed?)
            (.countDown b-claimed)
            (.await release-b 10 TimeUnit/SECONDS))))

      (let [destroy-a (future (rf.frame/destroy-frame! id))]
        (is (.await a-after-dissoc 10 TimeUnit/SECONDS)
            "incarnation A is paused post-dissoc with its claim marker live")
        (rf/reg-event :destroy/marker-overlap-cleanup
          (fn [_ _]
            (swap! cleanup-runs inc)
            {}))
        (rf/reg-event :destroy/marker-overlap-epoch
          (fn [{:keys [db]} _]
            {:db (assoc db :replacement :b)}))
        (rf/make-frame {:id id
                        :on-destroy [:destroy/marker-overlap-cleanup]})
        ;; Give B real recorded history before its destroy claims the
        ;; incarnation.
        (rf/dispatch-sync [:destroy/marker-overlap-epoch] {:frame id})
        (let [token-b        (rf.frame/frame-incarnation-token id)
              b-buffer-event {:operation :destroy/replacement-b-buffer
                              :tags {:frame id}}
              b-render-key   ::replacement-b-render
              b-sub-id       ::replacement-b-sub]
          (reset! b-installed? true)
          (let [destroy-b (future (rf.frame/destroy-frame! id token-b))]
            (is (.await b-claimed 10 TimeUnit/SECONDS)
                "fresh B replaces A's stale marker and claims its own destroy")

            ;; B's cleanup legitimately clears epoch state before the pause, so
            ;; B's fixtures go in after it; A's stale post-dissoc cleanup may
            ;; alter none of these three id-keyed stores.
            (let [b-epoch-id (:epoch-id (last (rf.epoch/epoch-history id)))]
              (rf.epoch.state/buffer-event! id b-buffer-event)
              (rf.epoch.state/record-render-deps! id b-render-key b-sub-id)

              (.countDown release-a)
              (is (= [nil b-epoch-id [b-buffer-event] #{b-sub-id}]
                     [(deref destroy-a 5000 ::timeout)
                      (:epoch-id (last (rf.epoch/epoch-history id)))
                      (rf.epoch.state/buffer-for id)
                      (rf.epoch.state/render-deps-for id b-render-key)])
                  "A finishes under B's claim, leaving B's history, buffer and render deps"))
            (let [duplicate-b (future (rf.frame/destroy-frame! id token-b))]
              (is (nil? (deref duplicate-b 2000 ::timeout))
                  "A's finally did not erase B's marker; duplicate B is a prompt no-op"))

            (.countDown release-b)
            (is (= [nil 1 nil]
                   [(deref destroy-b 5000 ::timeout) @cleanup-runs (rf.frame/frame id)])
                "B's owning destroy completes, running its cleanup once"))))
      (finally
        (.countDown release-a)
        (.countDown release-b)
        (rf.late-bind/set-fn! :epoch/on-frame-destroyed original-epoch)
        (rf.late-bind/set-fn! :machines/teardown-on-frame-destroy! original-machines)))))

(deftest destroyed-incarnation-commits-its-tail-in-itself-never-in-the-successor
  ;; A handler destroys its own incarnation and returns a db effect and a child
  ;; dispatch. Both land in A: the db commits (A's :on-destroy reads it) and the
  ;; child joins A's queue, where the boundary drops it. A then pauses after its
  ;; registry dissoc while same-id B is installed; nothing of A's tail reaches B.
  (let [id             :destroy/event-tail-overlap
        a-after-dissoc (CountDownLatch. 1)
        release-a      (CountDownLatch. 1)
        first-epoch?   (atom true)
        child-runs     (atom 0)
        cleanup-saw    (atom nil)
        traces         (atom [])
        original-epoch (rf.late-bind/get-fn :epoch/on-frame-destroyed)]
    (rf/reg-event :destroy/event-tail-child
      (fn [{:keys [db]} _]
        (swap! child-runs inc)
        {:db (assoc db :child :from-a)}))
    (rf/reg-event :destroy/event-tail-a
      (fn [_ _]
        (rf.frame/destroy-frame! id)
        {:db {:owner :a-tail}
         :fx [[:dispatch [:destroy/event-tail-child]]]}))
    (rf/reg-event :destroy/event-tail-cleanup
      (fn [{:keys [db]} _] (reset! cleanup-saw db) {}))
    (rf/reg-event :destroy/event-tail-b
      (fn [_ _]
        {:db {:owner :b}}))
    (rf/make-frame {:id id :on-destroy [:destroy/event-tail-cleanup]})
    (rf/register-listener! :trace ::event-tail-overlap
      (fn [ev]
        (when (and (= id (get-in ev [:tags :frame]))
                   (contains? #{:rf.frame/drain-interrupted
                                :rf.epoch/snapshotted
                                :rf.epoch/outcome}
                              (:operation ev)))
          (swap! traces conj ev))))
    (try
      (rf.late-bind/set-fn!
        :epoch/on-frame-destroyed
        (fn [& args]
          (when (and (= id (first args))
                     (compare-and-set! first-epoch? true false))
            (.countDown a-after-dissoc)
            (.await release-a 10 TimeUnit/SECONDS))
          (when original-epoch
            (apply original-epoch args))))
      (let [dispatch-a (future
                         (rf/dispatch-sync [:destroy/event-tail-a]
                                           {:frame id}))]
        (is (.await a-after-dissoc 10 TimeUnit/SECONDS)
            "A's event settled, and its boundary teardown is paused post-dissoc")
        ;; B deliberately suppresses ordinary frame-tagged traces. A's stale
        ;; structural drain-interrupted fact must bypass B's policy without
        ;; entering B's epoch capture buffer.
        (rf/make-frame {:id id :rf.trace/frame-no-emit? true})
        (let [token-b (rf.frame/frame-incarnation-token id)]
          (.countDown release-a)
          (is (not= ::timeout (deref dispatch-a 5000 ::timeout))
              "A's teardown completes")
          (executor-barrier!)
          (is (= [true {} {:owner :a-tail} 0]
                 [(identical? token-b (rf.frame/frame-incarnation-token id))
                  (rf.frame/frame-app-db-value id)
                  @cleanup-saw
                  @child-runs])
              "A's db committed in A and its child was dropped in A; B saw neither")
          (when rf.interop/debug-enabled?
            ;; A's own event settled :ok; its boundary drop is one structural
            ;; report and one :halted-destroy record, neither entering B
            (let [by-op    (group-by :operation @traces)
                  outcomes #(mapv (fn [ev] (get-in ev [:tags :outcome])) (get by-op %))]
              (is (= [[] true [1]
                      [:ok :ok :halted-destroy] [:ok :ok :blocked]]
                     [(vec (rf.epoch/epoch-history id))
                      (empty? (rf.epoch.state/buffer-for id))
                      (mapv #(get-in % [:tags :dropped-count])
                            (get by-op :rf.frame/drain-interrupted))
                      (outcomes :rf.epoch/snapshotted)
                      (outcomes :rf.epoch/outcome)]))))
          (rf/dispatch-sync [:destroy/event-tail-b] {:frame id})
          (is (= {:owner :b} (rf.frame/frame-app-db-value id))
              "B remains independently usable after A's stale tail returns")))
      (finally
        (.countDown release-a)
        (rf/unregister-listener! :trace ::event-tail-overlap)
        (rf.late-bind/set-fn! :epoch/on-frame-destroyed original-epoch)))))

(deftest predecessor-halted-destroy-evidence-survives-successor-epoch-claim
  ;; A is destroyed mid-drain and paused at its POST-DISSOC epoch hook.
  ;; Same-id B is then created and SETTLES an ordinary
  ;; event, claiming the id-keyed epoch stores (which drops A's buffer).
  ;; When A resumes it must STILL publish its terminal
  ;; halted-destroy evidence: the record was snapshotted BEFORE dissoc, and its
  ;; publication is decoupled from winning the compare-cleanup. B's stores stay
  ;; byte-identical.
  (let [id             :destroy/halted-evidence-overlap
        a-after-dissoc (CountDownLatch. 1)
        release-a      (CountDownLatch. 1)
        first-epoch?   (atom true)
        a-records      (atom [])
        traces         (atom [])
        original-epoch (rf.late-bind/get-fn :epoch/on-frame-destroyed)]
    (rf/reg-event :destroy/halted-a
      (fn [_ _]
        (rf.frame/destroy-frame! id)
        {:db {:owner :a-tail}
         :fx [[:dispatch [:destroy/halted-a-child]]]}))
    (rf/reg-event :destroy/halted-a-child (fn [_ _] {}))
    (rf/reg-event :destroy/halted-b-settle
      (fn [{:keys [db]} _]
        {:db (assoc db :owner :b)}))
    (rf/make-frame {:id id})
    ;; Capture A's terminal epoch record + its structural trailer pair.
    (rf/register-listener! :epoch ::a-epoch-watch
      (fn [r] (when (= :halted-destroy (:outcome r)) (swap! a-records conj r))))
    (rf/register-listener! :trace ::halted-evidence-overlap
      (fn [ev]
        (when (and (= id (get-in ev [:tags :frame]))
                   (contains? #{:rf.epoch/snapshotted :rf.epoch/outcome}
                              (:operation ev)))
          (swap! traces conj ev))))
    (try
      (rf.late-bind/set-fn! :epoch/on-frame-destroyed
        (fn [& args]
          ;; The post-dissoc publish hook. Hold only A's first (halted) call.
          (when (and (= id (first args))
                     (compare-and-set! first-epoch? true false))
            (.countDown a-after-dissoc)
            (.await release-a 10 TimeUnit/SECONDS))
          (when original-epoch (apply original-epoch args))))
      (let [dispatch-a (future
                         (rf/dispatch-sync [:destroy/halted-a] {:frame id}))]
        (is (.await a-after-dissoc 10 TimeUnit/SECONDS)
            "A is paused at its post-dissoc epoch hook (evidence already
             snapshotted before dissoc)")
        ;; Same-id B SETTLES an ordinary event — its first captured event
        ;; claims the id-keyed epoch stores, dropping A's buffer.
        (rf/make-frame {:id id})
        (rf/dispatch-sync [:destroy/halted-b-settle] {:frame id})
        (let [token-b      (rf.frame/frame-incarnation-token id)
              b-history    (rf.epoch/epoch-history id)
              b-buffer     (rf.epoch.state/buffer-for id)
              b-last-epoch (rf.epoch.state/last-settled-epoch-id id)
              b-db         (rf.frame/frame-app-db-value id)]
          (when rf.interop/debug-enabled?
            (is (= 1 (count b-history))
                "precondition: B owns the id-keyed stores"))

          ;; A resumes and reaches its terminal publish while B owns the stores.
          (.countDown release-a)
          (is (not= ::timeout (deref dispatch-a 5000 ::timeout))
              "A's terminal recipe completes")
          (executor-barrier!)

          (is (= [true {:owner :b} {:owner :b}]
                 [(identical? token-b (rf.frame/frame-incarnation-token id))
                  b-db
                  (rf.frame/frame-app-db-value id)])
              "B stays current and A's committed tail never became B's state")
          (when rf.interop/debug-enabled?
            ;; A's terminal evidence publishes although B owns the stores (B's
            ;; own trailers are :ok, A's :halted-destroy / :blocked), and A's
            ;; compare-cleanup leaves B's stores byte-identical
            (let [by-op   (group-by :operation @traces)
                  n-outcome (fn [op outcome]
                              (count (filter #(= outcome (get-in % [:tags :outcome]))
                                             (get by-op op))))]
              (is (= [[:destroy/halted-a-child] 1 1 b-history b-buffer b-last-epoch]
                     [(mapv :event-id @a-records)
                      (n-outcome :rf.epoch/snapshotted :halted-destroy)
                      (n-outcome :rf.epoch/outcome :blocked)
                      (rf.epoch/epoch-history id)
                      (rf.epoch.state/buffer-for id)
                      (rf.epoch.state/last-settled-epoch-id id)]))))
          (rf/dispatch-sync [:destroy/halted-b-settle] {:frame id})
          (is (= {:owner :b} (rf.frame/frame-app-db-value id))
              "B keeps committing its own events after A resumes")
          (when rf.interop/debug-enabled?
            (is (= 2 (count (rf.epoch/epoch-history id)))))))
      (finally
        (.countDown release-a)
        (rf/unregister-listener! :epoch ::a-epoch-watch)
        (rf/unregister-listener! :trace ::halted-evidence-overlap)
        (rf.late-bind/set-fn! :epoch/on-frame-destroyed original-epoch)))))

;; ---- predecessor terminal diagnostics across B no-emit -----

(defn- run-terminal-listener-exception-scenario
  "Destroy A mid-drain and pause at its post-dissoc epoch hook; install same-id
  B (with the given `no-emit?` policy) during the pause; on resume a terminal
  epoch listener throws while receiving A's halted record. Returns the captured
  `:rf.epoch.cb/listener-exception` traces plus B state. A's terminal
  listener-exception must be delivered structurally regardless of B's policy.

  B is installed on the main thread during the pause (not re-entrantly from the
  listener) so a same-id B is unambiguously live when the diagnostic emits."
  [id no-emit?]
  (let [a-after-dissoc (CountDownLatch. 1)
        release-a      (CountDownLatch. 1)
        first-epoch?   (atom true)
        exceptions     (atom [])
        thrower        (keyword "rf2-152" (str "lx-thrower-" (name id)))
        trace-key      (keyword "rf2-152" (str "lx-trace-" (name id)))
        original-epoch (rf.late-bind/get-fn :epoch/on-frame-destroyed)]
    (rf/reg-event :rf2-152/lx-destroy-self
      (fn [_ _]
        (rf.frame/destroy-frame! id)
        {:fx [[:dispatch [:rf2-152/lx-dropped]]]}))
    (rf/reg-event :rf2-152/lx-dropped (fn [_ _] {}))
    (rf/make-frame {:id id})
    ;; Terminal listener that throws while receiving A's halted record.
    (rf/register-listener! :epoch thrower
      (fn [r]
        (when (= :halted-destroy (:outcome r))
          (throw (ex-info "terminal listener blew" {})))))
    (rf/register-listener! :trace trace-key
      (fn [ev]
        (when (and (= id (get-in ev [:tags :frame]))
                   (= :rf.epoch.cb/listener-exception (:operation ev)))
          (swap! exceptions conj ev))))
    (try
      (rf.late-bind/set-fn! :epoch/on-frame-destroyed
        (fn [& args]
          (when (and (= id (first args))
                     (compare-and-set! first-epoch? true false))
            (.countDown a-after-dissoc)
            (.await release-a 10 TimeUnit/SECONDS))
          (when original-epoch (apply original-epoch args))))
      (let [dispatch-a (future
                         (rf/dispatch-sync [:rf2-152/lx-destroy-self] {:frame id}))]
        (is (.await a-after-dissoc 10 TimeUnit/SECONDS)
            "A paused at its post-dissoc epoch hook")
        (rf/make-frame {:id id :rf.trace/frame-no-emit? no-emit?})
        (let [token-b (rf.frame/frame-incarnation-token id)]
          (.countDown release-a)
          (is (not= ::timeout (deref dispatch-a 5000 ::timeout))
              "A's terminal recipe completes")
          (executor-barrier!)
          {:exceptions   @exceptions
           :thrower      thrower
           :b-token-ok?  (identical? token-b (rf.frame/frame-incarnation-token id))
           :b-buffer     (rf.epoch.state/buffer-for id)
           :b-history    (rf.epoch/epoch-history id)}))
      (finally
        (.countDown release-a)
        (rf/unregister-listener! :epoch thrower)
        (rf/unregister-listener! :trace trace-key)
        (when (rf.frame/frame id) (rf.frame/destroy-frame! id))
        (rf.late-bind/set-fn! :epoch/on-frame-destroyed original-epoch)))))

(deftest terminal-listener-exception-crosses-successor-no-emit
  ;; A terminal halted-destroy listener that throws while a same-id B is live
  ;; must still yield exactly one A :rf.epoch.cb/listener-exception — whether
  ;; or not B enabled no-emit — and that diagnostic must not enter B's epoch
  ;; capture. Resolved through B's bare-id policy, the diagnostic would be
  ;; suppressed to zero by a no-emit B.
  (let [suppressed (run-terminal-listener-exception-scenario
                     :rf2-152/lx-noemit true)
        control    (run-terminal-listener-exception-scenario
                     :rf2-152/lx-plain false)]
    (is (= [true true] [(:b-token-ok? suppressed) (:b-token-ok? control)])
        "same-id B stays the live incarnation through A's terminal fan-out")
    ;; the diagnostic and the epoch record that triggers it are both trace-fed
    (when rf.interop/debug-enabled?
      (is (= [[(:thrower suppressed)] 1 true true]
             [(mapv #(get-in % [:tags :cb-id]) (:exceptions suppressed))
              (count (:exceptions control))
              (empty? (filter #(= :rf.epoch.cb/listener-exception (:operation %))
                              (:b-buffer suppressed)))
              (empty? (:b-history suppressed))])
          "one diagnostic naming the listener, despite B no-emit, kept out of B's capture"))))

(defn- run-terminal-teardown-hook-exception-scenario
  "Destroy A; its post-dissoc epoch teardown hook installs same-id B (with the
  given `no-emit?` policy) then throws. Returns the captured
  `:rf.warning/teardown-hook-exception` traces plus B state. A's post-dissoc
  teardown-hook warning must be delivered structurally regardless of B's
  policy."
  [id no-emit?]
  (let [warnings    (atom [])
        reports     (atom [])
        trace-key   (keyword "rf2-152" (str "tdtrace-" (name id)))
        original-ep (rf.late-bind/get-fn :epoch/on-frame-destroyed)]
    (rf/make-frame {:id id})
    (rf/register-listener! :trace trace-key
      (fn [ev]
        (when (and (= id (get-in ev [:tags :frame]))
                   (= :rf.warning/teardown-hook-exception (:operation ev)))
          (swap! warnings conj ev))))
    (rf.error-emit/register-error-listener! trace-key
      (fn [r]
        (when (and (= :rf.error/frame-teardown-failed (:error r))
                   (= id (:frame r)))
          (swap! reports conj r))))
    (try
      (rf.late-bind/set-fn! :epoch/on-frame-destroyed
        (fn [& args]
          (if (= id (first args))
            (do
              ;; Install same-id B, then fail the post-dissoc epoch hook: B's
              ;; no-emit policy must not suppress A's teardown-hook warning.
              (rf/make-frame {:id id :rf.trace/frame-no-emit? no-emit?})
              (throw (ex-info "epoch teardown blew" {})))
            (when original-ep (apply original-ep args)))))
      (rf.frame/destroy-frame! id)
      (executor-barrier!)
      {:warnings  @warnings
       :reports   @reports
       :b-live?   (some? (rf.frame/frame-incarnation-token id))
       :b-buffer  (rf.epoch.state/buffer-for id)}
      (finally
        ;; Restore the real hook BEFORE destroying B so B's teardown does not
        ;; re-enter the throwing wrapper.
        (rf.late-bind/set-fn! :epoch/on-frame-destroyed original-ep)
        (rf/unregister-listener! :trace trace-key)
        (rf.error-emit/unregister-error-listener! trace-key)
        (when (rf.frame/frame id) (rf.frame/destroy-frame! id))))))

(deftest terminal-teardown-hook-exception-crosses-successor-no-emit
  ;; A throwing POST-DISSOC epoch teardown hook must still yield exactly one A
  ;; :rf.warning/teardown-hook-exception — whether or not the same-id
  ;; successor B enabled no-emit — and it must not enter B's epoch capture.
  ;; Through the ordinary bare-id policy path, a no-emit B would suppress the
  ;; warning to zero.
  (let [suppressed (run-terminal-teardown-hook-exception-scenario
                     :rf2-152/td-noemit true)
        control    (run-terminal-teardown-hook-exception-scenario
                     :rf2-152/td-plain false)]
    ;; the always-on report is the shipping channel for this failure, and B's
    ;; no-emit is a trace policy that cannot suppress it
    (is (= [[[:epoch/on-frame-destroyed]] 1 true]
           [(mapv #(mapv :hook (:hook-failures %)) (:reports suppressed))
            (count (:reports control))
            (:b-live? suppressed)])
        "one report naming the epoch hook ships despite B no-emit, and B stays live")
    (when rf.interop/debug-enabled?
      (is (= [[:epoch/on-frame-destroyed] 1 true]
             [(mapv #(get-in % [:tags :hook]) (:warnings suppressed))
              (count (:warnings control))
              (empty? (filter #(= :rf.warning/teardown-hook-exception (:operation %))
                              (:b-buffer suppressed)))])
          "one warning naming the hook, despite B no-emit, kept out of B's capture"))))

;; ---- predecessor terminal facts out of successor RING ------
;;
;; Structural delivery makes A's terminal facts bypass B's epoch CAPTURE and
;; B's no-emit POLICY; the fixtures above assert B's epoch buffer / history.
;; These fixtures assert B's per-frame trace RING. A's terminal facts carry
;; A's inherited dispatch-id and A's bare frame id, so were
;; `trace/tooling.cljc` to push them into the CURRENT ring for that id they
;; would land in B's, once B is installed. Structural delivery is therefore
;; RETENTIONLESS: global listeners get each terminal fact exactly once, but no
;; ring retains it.

(deftest predecessor-terminal-facts-never-retained-in-successor-ring
  ;; A, paused at its post-dissoc epoch hook, publishes its terminal facts
  ;; after same-id B has settled an event into its ring. B's flat buffer stays
  ;; byte-identical, and every A terminal fact still reaches the global
  ;; listener once.
  (let [id             :destroy/ring-evidence-overlap
        a-after-dissoc (CountDownLatch. 1)
        release-a      (CountDownLatch. 1)
        first-epoch?   (atom true)
        global-facts   (atom [])
        original-epoch (rf.late-bind/get-fn :epoch/on-frame-destroyed)]
    (rf/reg-event :destroy/ring-a
      (fn [_ _]
        (rf.frame/destroy-frame! id)
        {:db {:owner :a-tail} :fx [[:dispatch [:destroy/ring-a-dropped]]]}))
    (rf/reg-event :destroy/ring-a-dropped (fn [_ _] {}))
    (rf/reg-event :destroy/ring-b-settle
      (fn [{:keys [db]} _] {:db (assoc db :owner :b)}))
    (rf/make-frame {:id id})
    ;; Global live trace listener — captures A's frame-tagged terminal facts.
    (rf/register-listener! :trace ::ring-global-facts
      (fn [ev]
        (when (and (= id (get-in ev [:tags :frame]))
                   (contains? #{:rf.epoch/snapshotted :rf.epoch/outcome}
                              (:operation ev)))
          (swap! global-facts conj ev))))
    (try
      (rf.late-bind/set-fn! :epoch/on-frame-destroyed
        (fn [& args]
          (when (and (= id (first args))
                     (compare-and-set! first-epoch? true false))
            (.countDown a-after-dissoc)
            (.await release-a 10 TimeUnit/SECONDS))
          (when original-epoch (apply original-epoch args))))
      (let [dispatch-a (future
                         (rf/dispatch-sync [:destroy/ring-a] {:frame id}))]
        (is (.await a-after-dissoc 10 TimeUnit/SECONDS)
            "A is paused at its post-dissoc epoch hook")
        ;; Same-id B: install and settle an ordinary event so B's RING holds a
        ;; legitimate sentinel run before A publishes its terminal facts.
        (rf/make-frame {:id id})
        (rf/dispatch-sync [:destroy/ring-b-settle] {:frame id})
        (let [token-b       (rf.frame/frame-incarnation-token id)
              b-ring-before (rf/trace-buffer id {:flat true})
              b-bundles-before (rf/trace-buffer id)]
          ;; A resumes and publishes its terminal facts while B owns the id.
          (.countDown release-a)
          (is (not= ::timeout (deref dispatch-a 5000 ::timeout))
              "A's terminal recipe completes")
          (executor-barrier!)

          (is (= [true {:owner :b}]
                 [(identical? token-b (rf.frame/frame-incarnation-token id))
                  (rf.frame/frame-app-db-value id)])
              "B stays current and A's terminal fan-out never mutated its state")
          ;; under the gate B has no ring, so the precondition rides with the audit
          (when rf.interop/debug-enabled?
            (let [dispatch-ids #(into #{} (keep (fn [ev] (get-in ev [:tags :rf.trace/dispatch-id]))) %)
                  n-global     (fn [op outcome]
                                 (count (filter #(and (= op (:operation %))
                                                      (= outcome (get-in % [:tags :outcome])))
                                                @global-facts)))]
              (is (= [true b-ring-before b-bundles-before #{} 1 1]
                     [(boolean (seq b-ring-before))
                      (rf/trace-buffer id {:flat true})
                      (rf/trace-buffer id)
                      (set/intersection (dispatch-ids @global-facts)
                                        (dispatch-ids (rf/trace-buffer id {:flat true})))
                      (n-global :rf.epoch/snapshotted :halted-destroy)
                      (n-global :rf.epoch/outcome :blocked)])
                  "B's ring is byte-identical; A's facts reach only the global listener"))
            (rf/dispatch-sync [:destroy/ring-b-settle] {:frame id})
            (is (< (count b-ring-before) (count (rf/trace-buffer id {:flat true})))
                "B's own next event grows its ring (live, not frozen)"))))
      (finally
        (.countDown release-a)
        (rf/unregister-listener! :trace ::ring-global-facts)
        (when (rf.frame/frame id) (rf.frame/destroy-frame! id))
        (rf.late-bind/set-fn! :epoch/on-frame-destroyed original-epoch)))))

(deftest snapshot-hook-failure-leaves-no-residual-ring
  ;; A throwing snapshot hook's warning is an ordinary ring push under A's bare
  ;; id. The snapshot runs before A's ring release, so the release clears it;
  ;; run after, the warning would recreate a residual ring for destroyed A.
  (let [id           :destroy/ring-snapshot-fail
        warnings     (atom [])
        reports      (atom [])
        original-snap (rf.late-bind/get-fn :epoch/snapshot-frame-destroyed)]
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
        (when (and (= :rf.error/frame-teardown-failed (:error r))
                   (= id (:frame r)))
          (swap! reports conj r))))
    (try
      ;; Fail the pre-dissoc snapshot hook for this pending destroy.
      (rf.late-bind/set-fn! :epoch/snapshot-frame-destroyed
        (fn [& args]
          (if (= id (first args))
            (throw (ex-info "snapshot blew" {}))
            (when original-snap (apply original-snap args)))))
      (rf/dispatch-sync [:destroy/ring-snap-a] {:frame id})
      (executor-barrier!)
      (is (= [[[:epoch/snapshot-frame-destroyed]] nil]
             [(mapv #(mapv :hook (:hook-failures %)) @reports)
              (rf.frame/frame-incarnation-token id)])
          "one always-on report names the snapshot hook, and the destroy completes")
      (when rf.interop/debug-enabled?
        (is (= [[:epoch/snapshot-frame-destroyed] true true]
               [(mapv #(get-in % [:tags :hook]) @warnings)
                (empty? (rf/trace-buffer id {:flat true}))
                (empty? (rf/trace-buffer id))])
            "the warning reaches the global listener and no residual ring survives A"))
      (finally
        (rf/unregister-listener! :trace ::ring-snap-warn)
        (rf.error-emit/unregister-error-listener! ::ring-snap-warn)
        (when (rf.frame/frame id) (rf.frame/destroy-frame! id))
        (rf.late-bind/set-fn! :epoch/snapshot-frame-destroyed original-snap)))))

(deftest snapshot-hook-failure-still-publishes-reports-and-spares-successor
  ;; A throwing pre-dissoc snapshot hook does not abort teardown: the
  ;; post-dissoc publish still runs (with a nil bundle, so it fabricates no
  ;; evidence), one always-on report names the hook, and a same-id B installed
  ;; in the publish window is left pristine. A is destroyed directly, not
  ;; mid-drain, so installing B in that window is safe.
  (let [id             :destroy/snap-fail-integrated
        report-key     ::snap-fail-reports
        reports        (atom [])                       ; :rf.error/frame-teardown-failed
        publish-calls  (atom 0)
        a-token        (atom nil)
        b-token        (atom nil)
        original-snap  (rf.late-bind/get-fn :epoch/snapshot-frame-destroyed)
        original-epoch (rf.late-bind/get-fn :epoch/on-frame-destroyed)]
    (rf/reg-event :snap-fail/seed (fn [_ _] {:db {:n 0}}))
    (rf/make-frame {:id id})
    ;; A settles one event, so it owns real epoch state when it is destroyed.
    (rf/dispatch-sync [:snap-fail/seed] {:frame id})
    (reset! a-token (rf.frame/frame-incarnation-token id))
    (rf.error-emit/register-error-listener! report-key
      (fn [r] (when (= :rf.error/frame-teardown-failed (:error r))
                (swap! reports conj r))))
    (try
      ;; Fail the PRE-dissoc snapshot for this id (delegate every other id).
      (rf.late-bind/set-fn! :epoch/snapshot-frame-destroyed
        (fn [& args]
          (if (= id (first args))
            (throw (ex-info "snapshot blew" {}))
            (when original-snap (apply original-snap args)))))
      ;; Spy the POST-dissoc publish: prove it ran, install same-id B inside its
      ;; window, then delegate to the real (nil-bundle → no-op) publish.
      (rf.late-bind/set-fn! :epoch/on-frame-destroyed
        (fn [& args]
          (when (= id (first args))
            (swap! publish-calls inc)
            (rf/make-frame {:id id})
            (reset! b-token (rf.frame/frame-incarnation-token id)))
          (when original-epoch (apply original-epoch args))))
      (rf.frame/destroy-frame! id)
      (executor-barrier!)

      (is (= [1 [{:frame id :hooks [:epoch/snapshot-frame-destroyed] :recovery :ignored}]]
             [@publish-calls
              (mapv (fn [r] {:frame    (:frame r)
                             :hooks    (mapv :hook (:hook-failures r))
                             :recovery (:recovery r)})
                    @reports)])
          "the publish still ran, and one report names only the snapshot hook")
      (is (= [true true true]
             [(some? @b-token)
              (= @b-token (rf.frame/frame-incarnation-token id))
              (not= @a-token @b-token)])
          "the fresh same-id B is live")
      (finally
        ;; Restore the real hooks BEFORE destroying B so B's teardown runs clean.
        (rf.late-bind/set-fn! :epoch/snapshot-frame-destroyed original-snap)
        (rf.late-bind/set-fn! :epoch/on-frame-destroyed original-epoch)
        (rf.error-emit/unregister-error-listener! report-key)
        (when (rf.frame/frame id) (rf.frame/destroy-frame! id))))))

;; A's :halted-destroy record fans to the listener registry snapshotted before
;; dissoc, so a same-id replacement registered in the post-dissoc gap never
;; receives A's terminal record.

(deftest predecessor-halted-record-reaches-only-the-pre-dissoc-listener
  ;; cb observes A; A's event destroys A and queues a child, and A's boundary
  ;; teardown pauses post-dissoc; during the pause cb is REPLACED and same-id B
  ;; settles. A's halted record goes to the callback registered when A was
  ;; destroyed; the replacement sees only B's settle.
  (let [id             :vxgfnd245/regen
        cb             ::vxgfnd245-regen-cb
        a-after-dissoc (CountDownLatch. 1)
        release-a      (CountDownLatch. 1)
        first-epoch?   (atom true)
        old-received   (atom 0)
        new-received   (atom 0)
        original-epoch (rf.late-bind/get-fn :epoch/on-frame-destroyed)]
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
      (rf.late-bind/set-fn! :epoch/on-frame-destroyed
        (fn [& args]
          (when (and (= id (first args))
                     (compare-and-set! first-epoch? true false))
            (.countDown a-after-dissoc)
            (.await release-a 10 TimeUnit/SECONDS))
          (when original-epoch (apply original-epoch args))))
      (let [dispatch-a (future
                         (rf/dispatch-sync [:vxgfnd245/regen-a-destroy] {:frame id}))]
        (is (.await a-after-dissoc 10 TimeUnit/SECONDS)
            "A paused post-dissoc")
        ;; Replace cb in the gap, then B settles.
        (rf/register-listener! :epoch cb (fn [_] (swap! new-received inc)))
        (rf/make-frame {:id id})
        (rf/dispatch-sync [:vxgfnd245/regen-b-settle] {:frame id})
        (.countDown release-a)
        (is (not= ::timeout (deref dispatch-a 5000 ::timeout))
            "A's terminal recipe completes")
        (executor-barrier!)
        (is (= {:owner :b} (rf.frame/frame-app-db-value id))
            "A's tail never mutated B's state across the gap re-register")
        (when rf.interop/debug-enabled?
          ;; the replaced callback got A's two settles and A's halted record;
          ;; the replacement saw only B's settle
          (is (= [1 3] [@new-received @old-received])
              "A's halted record reaches the callback registered when A was destroyed")))
      (finally
        (.countDown release-a)
        (rf/unregister-listener! :epoch cb)
        (when (rf.frame/frame id) (rf.frame/destroy-frame! id))
        (rf.late-bind/set-fn! :epoch/on-frame-destroyed original-epoch)))))

;; Structural delivery turns epoch capture, frame policy and ring retention off
;; around its emit, and restores ordinary defaults around the listener fan-out,
;; so work a listener dispatches into another frame is captured, retained and
;; policed as that frame's own. Only a dispatching listener can see the scope.

(defn- with-failing-epoch-publish
  "Run `f` with `:epoch/on-frame-destroyed` throwing for `frame-id` (every other
  id delegates), so that frame's top-level destroy fans a real structural
  terminal fact: the `:rf.warning/teardown-hook-exception` naming the hook."
  [frame-id f]
  (let [original (rf.late-bind/get-fn :epoch/on-frame-destroyed)]
    (rf.late-bind/set-fn! :epoch/on-frame-destroyed
      (fn [& args]
        (if (= frame-id (first args))
          (throw (ex-info "epoch publish blew" {}))
          (when original (apply original args)))))
    (try
      (f)
      (finally
        (rf.late-bind/set-fn! :epoch/on-frame-destroyed original)))))

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
      (fn [record] (when (= id (:frame record)) (swap! errors conj (:error record)))))
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
    (rf/reg-cofx :destroy/cofx-killer
      (fn []
        (rf.frame/destroy-frame! id)
        (throw (ex-info "cofx supplier failed after asking for its destroy" {}))))
    (rf/reg-event :destroy/cofx-event
      {:rf.cofx/requires [:destroy/cofx-killer]}
      (fn [_ _] (swap! handler-runs inc) {:db {:forbidden true}}))
    (rf.error-emit/register-error-listener! ::cofx-loss
      (fn [record] (when (= id (:frame record)) (swap! errors conj (:error record)))))
    (try
      (is (= [nil 0 [:rf.error/coeffect-exception] nil]
             [(rf/dispatch-sync [:destroy/cofx-event] {:frame id})
              @handler-runs @errors (rf.frame/frame id)]))
      (finally
        (rf.error-emit/unregister-error-listener! ::cofx-loss)))))

(deftest stale-teardown-report-is-corpus-only-after-successor-install
  ;; Mutation tooth: the bounded report flushes after A's registry dissoc. Its
  ;; corpus-wide fact remains required, but a bare-id frame route at that seam
  ;; would resolve B and feed A's failure into B's declared sinks.
  (let [id             :destroy/teardown-report-overlap
        a-after-dissoc (CountDownLatch. 1)
        release-a      (CountDownLatch. 1)
        b-claimed      (CountDownLatch. 1)
        release-b      (CountDownLatch. 1)
        b-installed?   (atom false)
        first-epoch?   (atom true)
        corpus         (atom [])
        traces         (atom [])
        frame-routes   (atom 0)
        original-machines    (rf.late-bind/get-fn :machines/teardown-on-frame-destroy!)
        original-epoch (rf.late-bind/get-fn :epoch/on-frame-destroyed)
        original-route (rf.late-bind/get-fn :observability/route-error-record)]
    (rf/make-frame {:id id})
    (rf/reg-event :destroy/teardown-overlap-a
      (fn [_ _]
        (rf.frame/destroy-frame! id)
        {:fx [[:dispatch [:destroy/teardown-overlap-dropped]]]}))
    (rf/reg-event :destroy/teardown-overlap-dropped (fn [_ _] {}))
    (rf.error-emit/register-error-listener! ::teardown-overlap-corpus
      (fn [record]
        (when (= :rf.error/frame-teardown-failed (:error record))
          (swap! corpus conj record))))
    (rf/register-listener! :trace ::teardown-overlap-traces
      (fn [event]
        (when (and (= id (get-in event [:tags :frame]))
                   (contains? #{:rf.epoch/snapshotted :rf.epoch/outcome}
                              (:operation event)))
          (swap! traces conj event))))
    (try
      (rf.late-bind/set-fn!
        :machines/teardown-on-frame-destroy!
        (fn [frame-id]
          (when original-machines (original-machines frame-id))
          (when (= id frame-id)
            (if @b-installed?
              (do
                (.countDown b-claimed)
                (.await release-b 10 TimeUnit/SECONDS))
              (throw (ex-info "forced A teardown hook failure" {}))))))
      (rf.late-bind/set-fn!
        :epoch/on-frame-destroyed
        (fn [& args]
          (when (and (= id (first args))
                     (compare-and-set! first-epoch? true false))
            (.countDown a-after-dissoc)
            (.await release-a 10 TimeUnit/SECONDS))
          (when original-epoch (apply original-epoch args))))
      (rf.late-bind/set-fn!
        :observability/route-error-record
        ;; A dissociated incarnation still routes, with frame-authority? false,
        ;; to the process default; only a call claiming frame authority would
        ;; resolve through B's frame-owned route. An absent arg means authority.
        (fn [record & [authority-arg]]
          (let [frame-authority? (if (nil? authority-arg) true authority-arg)]
            (when (and frame-authority?
                       (= :rf.error/frame-teardown-failed (:error record)))
              (swap! frame-routes inc))
            (when original-route (original-route record frame-authority?)))))
      (let [destroy-a (future
                        (rf/dispatch-sync [:destroy/teardown-overlap-a]
                                          {:frame id}))]
        (is (.await a-after-dissoc 10 TimeUnit/SECONDS)
            "A paused after dissoc and before its terminal report flush")
        (rf/make-frame {:id id
                        :rf.trace/frame-no-emit? true
                        :observability {:errors [{:sink ::replacement-b}]}})
        (reset! b-installed? true)
        (let [token-b  (rf.frame/frame-incarnation-token id)
              destroy-b (future (rf.frame/destroy-frame! id))]
          (is (.await b-claimed 10 TimeUnit/SECONDS)
              "B replaced the bare-id marker with its own destroy claim")
          (.countDown release-a)
          (is (= [true true {} 1 0]
                 [(not= ::timeout (deref destroy-a 5000 ::timeout))
                  (identical? token-b (rf.frame/frame-incarnation-token id))
                  (rf.frame/frame-app-db-value id)
                  (count @corpus)
                  @frame-routes])
              "A's report reaches the corpus once and never B's frame-owned route")
          (when rf.interop/debug-enabled?
            ;; B's claim revokes neither of A's terminal facts, and neither enters B
            (let [by-op    (group-by :operation @traces)
                  outcomes #(mapv (fn [ev] (get-in ev [:tags :outcome])) (get by-op %))]
              (is (= [[:ok :halted-destroy] [:ok :blocked] true true]
                     [(outcomes :rf.epoch/snapshotted)
                      (outcomes :rf.epoch/outcome)
                      (empty? (rf.epoch/epoch-history id))
                      (empty? (rf.epoch.state/buffer-for id))]))))
          (let [duplicate-b (future (rf.frame/destroy-frame! id token-b))]
            (is (nil? (deref duplicate-b 2000 ::timeout))
                "A's finally preserved B's distinct claim marker"))
          (.countDown release-b)
          (is (not= ::timeout (deref destroy-b 5000 ::timeout))
              "B's independently-owned teardown completes")))
      (finally
        (.countDown release-a)
        (.countDown release-b)
        (rf.error-emit/unregister-error-listener! ::teardown-overlap-corpus)
        (rf/unregister-listener! :trace ::teardown-overlap-traces)
        (rf.late-bind/set-fn! :machines/teardown-on-frame-destroy! original-machines)
        (rf.late-bind/set-fn! :epoch/on-frame-destroyed original-epoch)
        (rf.late-bind/set-fn! :observability/route-error-record original-route)))))

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
        (when (= :rf.error/test-union (:error record))
          (rf.frame/destroy-frame! id)
          (throw (ex-info "union listener failed" {})))))
    (rf.error-emit/register-error-listener! ::union-sibling
      (fn [record]
        (when (= :rf.error/test-union (:error record))
          (swap! sibling-runs inc))))
    (rf/reg-event :destroy/union-error-event
      (fn [_ _]
        (rf.error-emit/dispatch-error-record!
          {:error :rf.error/test-union :frame id :time 0})
        {}))
    (try
      (rf.late-bind/set-fn! :observability/route-error-record
        (fn [record & _]
          (when (= :rf.error/test-union (:error record))
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
          (when (= :rf.error/drain-depth-exceeded (:error record))
            (swap! frame-routes inc))
          (when original-route (apply original-route record more))))
      ;; registered first, so the array-map corpus registry fans it first
      (rf.error-emit/register-error-listener! ::depth-destroyer
        (fn [record]
          (when (= :rf.error/drain-depth-exceeded (:error record))
            (swap! depth-records conj record)
            (rf.frame/destroy-frame! id))))
      (rf.error-emit/register-error-listener! ::depth-sibling
        (fn [record]
          (when (= :rf.error/drain-depth-exceeded (:error record))
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
  ;; the control: with A live, an always-false predicate would silence both
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
          (when (= :rf.error/drain-depth-exceeded (:error record))
            (swap! depth-records conj record))))
      (rf.error-emit/register-error-listener! ::live-b
        (fn [record]
          (when (= :rf.error/drain-depth-exceeded (:error record))
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
