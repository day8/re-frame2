(ns re-frame.frame-destroy-incarnation-jvm-test
  "Deterministic JVM barriers for incarnation-owned frame teardown: a destroyed
  incarnation A never leaks into, mutates, or lends authority to a same-id
  successor B.

  The incarnation fence (which token is live, whose db write commits, whose
  child dispatch runs, which corpus-wide `:errors` record ships) is production
  behaviour and asserted always-on. The epoch subsystem is fed by the dev trace,
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
            [re-frame.epoch]
            [re-frame.epoch.state :as rf.epoch.state]
            [re-frame.frame :as rf.frame]
            [re-frame.interceptor :as rf.interceptor]
            [re-frame.interop :as rf.interop]
            [re-frame.late-bind :as rf.late-bind]
            [re-frame.registrar :as rf.registrar]
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
        b-observer      ::replacement-b-observer
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
        ;; Give B real recorded history + listener observation before its
        ;; destroy claims the incarnation.
        (rf.epoch.state/put-listener! b-observer (fn [_] nil))
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
            ;; alter none of these four id-keyed stores.
            (let [b-epoch-id   (:epoch-id (last (rf/epoch-history id)))
                  b-generation (get-in (rf.epoch.state/listeners-snapshot)
                                       [b-observer :generation])]
              (rf.epoch.state/buffer-event! id b-buffer-event)
              (rf.epoch.state/record-render-deps! id b-render-key b-sub-id)
              (rf.epoch.state/record-observation! b-observer b-generation id)
              (is (some? (get-in (rf.epoch.state/observations-snapshot)
                                 [b-observer id]))
                  "B's listener observation fixture is armed before A resumes")

              (.countDown release-a)
              (is (= [nil b-epoch-id [b-buffer-event] #{b-sub-id} true]
                     [(deref destroy-a 5000 ::timeout)
                      (:epoch-id (last (rf/epoch-history id)))
                      (rf.epoch.state/buffer-for id)
                      (rf.epoch.state/render-deps-for id b-render-key)
                      (some? (get-in (rf.epoch.state/observations-snapshot)
                                     [b-observer id]))])
                  "A finishes under B's claim, leaving B's history, buffer, render deps and observation"))
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
        (rf.epoch.state/drop-listener! b-observer)
        (rf.late-bind/set-fn! :epoch/on-frame-destroyed original-epoch)
        (rf.late-bind/set-fn! :machines/teardown-on-frame-destroy! original-machines)))))

(deftest destroyed-incarnation-cannot-commit-its-returned-tail-into-replacement
  ;; A handler destroys its own incarnation and pauses after the registry
  ;; dissoc. Install same-id B while that handler is still on the stack, then
  ;; let A return a db effect and a child dispatch. Every part of that returned
  ;; tail belongs to A and must be discarded rather than redirected into B.
  (let [id             :destroy/event-tail-overlap
        a-after-dissoc (CountDownLatch. 1)
        release-a      (CountDownLatch. 1)
        first-epoch?   (atom true)
        child-runs     (atom 0)
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
    (rf/reg-event :destroy/event-tail-b
      (fn [_ _]
        {:db {:owner :b}}))
    (rf/make-frame {:id id})
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
            "A is paused post-dissoc before its handler returns")
        ;; B deliberately suppresses ordinary frame-tagged traces. A's stale
        ;; structural drain-interrupted fact must bypass B's policy without
        ;; entering B's epoch capture buffer.
        (rf/make-frame {:id id :rf.trace/frame-no-emit? true})
        (let [token-b   (rf.frame/frame-incarnation-token id)
              history-b (rf/epoch-history id)]
          (.countDown release-a)
          (is (not= ::timeout (deref dispatch-a 5000 ::timeout))
              "A's already-dequeued handler is allowed to return")
          (executor-barrier!)
          (is (= [true {} 0]
                 [(identical? token-b (rf.frame/frame-incarnation-token id))
                  (rf.frame/frame-app-db-value id)
                  @child-runs])
              "B stays current; A's returned db effect and child dispatch are discarded")
          (when rf.interop/debug-enabled?
            ;; A's structural facts bypass B's no-emit policy and B's capture
            (let [by-op    (group-by :operation @traces)
                  outcomes #(mapv (fn [ev] (get-in ev [:tags :outcome])) (get by-op %))]
              (is (= [history-b true 1 [:halted-destroy] [:blocked]]
                     [(rf/epoch-history id)
                      (empty? (rf.epoch.state/buffer-for id))
                      (count (get by-op :rf.frame/drain-interrupted))
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
  ;; event, claiming the id-keyed epoch stores (which drops A's buffer and
  ;; observation ledger). When A resumes it must STILL publish its terminal
  ;; halted-destroy evidence: the record was snapshotted BEFORE dissoc, and its
  ;; publication is decoupled from winning the compare-cleanup. B's stores stay
  ;; byte-identical.
  (let [id             :destroy/halted-evidence-overlap
        a-after-dissoc (CountDownLatch. 1)
        release-a      (CountDownLatch. 1)
        first-epoch?   (atom true)
        a-records      (atom [])
        traces         (atom [])
        b-observer     ::b-halted-observer
        original-epoch (rf.late-bind/get-fn :epoch/on-frame-destroyed)]
    (rf/reg-event :destroy/halted-a
      (fn [_ _]
        (rf.frame/destroy-frame! id)
        {:db {:owner :a-tail}}))
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
        ;; Same-id B, with its own observer, SETTLES an ordinary event — its
        ;; first captured event claims the id-keyed epoch stores, dropping A's
        ;; buffer + observation ledger.
        (rf/make-frame {:id id})
        (rf.epoch.state/put-listener! b-observer (fn [_] nil))
        (rf/dispatch-sync [:destroy/halted-b-settle] {:frame id})
        (let [token-b      (rf.frame/frame-incarnation-token id)
              b-history    (rf/epoch-history id)
              b-buffer     (rf.epoch.state/buffer-for id)
              b-last-epoch (rf.epoch.state/last-settled-epoch-id id)
              b-db         (rf.frame/frame-app-db-value id)]
          (when rf.interop/debug-enabled?
            (is (= [1 true]
                   [(count b-history)
                    (some? (get-in (rf.epoch.state/observations-snapshot) [b-observer id]))])
                "precondition: B owns the id-keyed stores and its observer is armed"))

          ;; A resumes and reaches its terminal publish while B owns the stores.
          (.countDown release-a)
          (is (not= ::timeout (deref dispatch-a 5000 ::timeout))
              "A's terminal recipe completes")
          (executor-barrier!)

          (is (= [true {:owner :b} {:owner :b}]
                 [(identical? token-b (rf.frame/frame-incarnation-token id))
                  b-db
                  (rf.frame/frame-app-db-value id)])
              "B stays current and A's inert returned tail never became B's state")
          (when rf.interop/debug-enabled?
            ;; A's terminal evidence publishes although B owns the stores (B's
            ;; own trailers are :ok, A's :halted-destroy / :blocked), and A's
            ;; compare-cleanup leaves B's stores byte-identical
            (let [by-op   (group-by :operation @traces)
                  n-outcome (fn [op outcome]
                              (count (filter #(= outcome (get-in % [:tags :outcome]))
                                             (get by-op op))))]
              (is (= [[:destroy/halted-a] 1 1 b-history b-buffer b-last-epoch true]
                     [(mapv :event-id @a-records)
                      (n-outcome :rf.epoch/snapshotted :halted-destroy)
                      (n-outcome :rf.epoch/outcome :blocked)
                      (rf/epoch-history id)
                      (rf.epoch.state/buffer-for id)
                      (rf.epoch.state/last-settled-epoch-id id)
                      (some? (get-in (rf.epoch.state/observations-snapshot) [b-observer id]))]))))
          (rf/dispatch-sync [:destroy/halted-b-settle] {:frame id})
          (is (= {:owner :b} (rf.frame/frame-app-db-value id))
              "B keeps committing its own events after A resumes")
          (when rf.interop/debug-enabled?
            (is (= 2 (count (rf/epoch-history id)))))))
      (finally
        (.countDown release-a)
        (rf.epoch.state/drop-listener! b-observer)
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
      (fn [_ _] (rf.frame/destroy-frame! id) {}))
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
           :b-history    (rf/epoch-history id)}))
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
        a-observer     ::ring-a-observer
        global-facts   (atom [])
        original-epoch (rf.late-bind/get-fn :epoch/on-frame-destroyed)]
    (rf/reg-event :destroy/ring-a-settle
      (fn [{:keys [db]} _] {:db (assoc db :a-settled true)}))
    (rf/reg-event :destroy/ring-a
      (fn [_ _] (rf.frame/destroy-frame! id) {:db {:owner :a-tail}}))
    (rf/reg-event :destroy/ring-b-settle
      (fn [{:keys [db]} _] {:db (assoc db :owner :b)}))
    (rf/make-frame {:id id})
    ;; A's observer is re-armed by B's settle, so A's silencing fan for it is
    ;; suppressed; A's snapshotted/outcome trailers still fire
    (rf/register-listener! :epoch a-observer (fn [_] nil))
    (rf/dispatch-sync [:destroy/ring-a-settle] {:frame id})
    ;; Global live trace listener — captures A's frame-tagged terminal facts.
    (rf/register-listener! :trace ::ring-global-facts
      (fn [ev]
        (when (and (= id (get-in ev [:tags :frame]))
                   (contains? #{:rf.epoch/snapshotted :rf.epoch/outcome
                                :rf.epoch.cb/silenced-on-frame-destroy}
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
              (is (= [true b-ring-before b-bundles-before #{} 1 1 []]
                     [(boolean (seq b-ring-before))
                      (rf/trace-buffer id {:flat true})
                      (rf/trace-buffer id)
                      (set/intersection (dispatch-ids @global-facts)
                                        (dispatch-ids (rf/trace-buffer id {:flat true})))
                      (n-global :rf.epoch/snapshotted :halted-destroy)
                      (n-global :rf.epoch/outcome :blocked)
                      (filterv #(and (= :rf.epoch.cb/silenced-on-frame-destroy (:operation %))
                                     (= a-observer (get-in % [:tags :cb-id])))
                               @global-facts)])
                  "B's ring is byte-identical; A's facts reach only the global listener"))
            (rf/dispatch-sync [:destroy/ring-b-settle] {:frame id})
            (is (< (count b-ring-before) (count (rf/trace-buffer id {:flat true})))
                "B's own next event grows its ring (live, not frozen)"))))
      (finally
        (.countDown release-a)
        (rf/unregister-listener! :epoch a-observer)
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
      ;; Fail the pre-dissoc snapshot hook for this mid-run destroy.
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
  ;; silence for the cb that observed A), one always-on report names the hook,
  ;; and a same-id B installed in the publish window is left pristine. A is
  ;; destroyed directly, not mid-drain, so installing B in that window is safe.
  (let [id             :destroy/snap-fail-integrated
        cb             ::snap-fail-observer
        reports        (atom [])                       ; :rf.error/frame-teardown-failed
        silencings     (atom [])                       ; :rf.epoch.cb/silenced-on-frame-destroy
        publish-calls  (atom 0)
        a-token        (atom nil)
        b-token        (atom nil)
        original-snap  (rf.late-bind/get-fn :epoch/snapshot-frame-destroyed)
        original-epoch (rf.late-bind/get-fn :epoch/on-frame-destroyed)]
    (rf/reg-event :snap-fail/seed (fn [_ _] {:db {:n 0}}))
    (rf/make-frame {:id id})
    ;; An epoch cb OBSERVES A: delivery of the settled :seed record stamps its
    ;; observation of the frame, so a live snapshot would owe it a silence.
    (rf/register-listener! :epoch cb (fn [_] nil))
    (rf/dispatch-sync [:snap-fail/seed] {:frame id})
    (reset! a-token (rf.frame/frame-incarnation-token id))
    (rf.error-emit/register-error-listener! cb
      (fn [r] (when (= :rf.error/frame-teardown-failed (:error r))
                (swap! reports conj r))))
    (rf/register-listener! :trace cb
      (fn [ev] (when (= :rf.epoch.cb/silenced-on-frame-destroy (:operation ev))
                 (swap! silencings conj ev))))
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
      (is (= [true true true []]
             [(some? @b-token)
              (= @b-token (rf.frame/frame-incarnation-token id))
              (not= @a-token @b-token)
              @silencings])
          "the fresh same-id B is live and the observing cb owes no silence")
      (finally
        ;; Restore the real hooks BEFORE destroying B so B's teardown runs clean.
        (rf.late-bind/set-fn! :epoch/snapshot-frame-destroyed original-snap)
        (rf.late-bind/set-fn! :epoch/on-frame-destroyed original-epoch)
        (rf/unregister-listener! :epoch cb)
        (rf.error-emit/unregister-error-listener! cb)
        (rf/unregister-listener! :trace cb)
        (when (rf.frame/frame id) (rf.frame/destroy-frame! id))))))

;; A's silenced-cbs are snapshotted before dissoc and published later, gated
;; on A still winning its compare-owned cleanup: once a same-id B re-arms the
;; observation state, a bare silencing from A would falsely report a callback
;; that is live on B. A's halted record and trailers stay unconditional.

(deftest predecessor-silencing-fan-suppressed-after-successor-rearm
  ;; cb observes A; A pauses post-dissoc; same-id B settles and re-arms cb.
  ;; A's silencing for cb is suppressed, and B's own destroy fires exactly one.
  (let [id             :vxgfnd245/rearm
        cb             ::vxgfnd245-cb
        a-after-dissoc (CountDownLatch. 1)
        release-a      (CountDownLatch. 1)
        first-epoch?   (atom true)
        received       (atom [])
        silencings     (atom [])
        original-epoch (rf.late-bind/get-fn :epoch/on-frame-destroyed)]
    (rf/reg-event :vxgfnd245/a-settle
      (fn [{:keys [db]} _] {:db (assoc db :a true)}))
    (rf/reg-event :vxgfnd245/a-destroy
      (fn [_ _] (rf.frame/destroy-frame! id) {:db {:owner :a-tail}}))
    (rf/reg-event :vxgfnd245/b-settle
      (fn [{:keys [db]} _] {:db (assoc db :owner :b)}))
    (rf/make-frame {:id id})
    ;; cb observes A by settling one ordinary event; A claims the id-keyed stores.
    (rf/register-listener! :epoch cb (fn [r] (swap! received conj r)))
    (rf/dispatch-sync [:vxgfnd245/a-settle] {:frame id})
    (rf/register-listener! :trace ::vxgfnd245-silencing
      (fn [ev]
        (when (= :rf.epoch.cb/silenced-on-frame-destroy (:operation ev))
          (swap! silencings conj ev))))
    (try
      (rf.late-bind/set-fn! :epoch/on-frame-destroyed
        (fn [& args]
          (when (and (= id (first args))
                     (compare-and-set! first-epoch? true false))
            (.countDown a-after-dissoc)
            (.await release-a 10 TimeUnit/SECONDS))
          (when original-epoch (apply original-epoch args))))
      (let [dispatch-a (future
                         (rf/dispatch-sync [:vxgfnd245/a-destroy] {:frame id}))]
        (is (.await a-after-dissoc 10 TimeUnit/SECONDS)
            "A is paused at its post-dissoc epoch hook (silenced-cbs already
             snapshotted incl cb)")
        ;; Same-id B settles: claims stores + re-arms cb for the reused id.
        (rf/make-frame {:id id})
        (rf/dispatch-sync [:vxgfnd245/b-settle] {:frame id})
        (when rf.interop/debug-enabled?
          (is (= 2 (count @received))
              "cb received A's settle and B's settle — re-armed for the reused id"))
        ;; A resumes and reaches its terminal publish while B owns the stores.
        (.countDown release-a)
        (is (not= ::timeout (deref dispatch-a 5000 ::timeout))
            "A's terminal recipe completes")
        (executor-barrier!)
        (is (= {:owner :b} (rf.frame/frame-app-db-value id))
            "A's inert tail never mutated B's state")
        (when rf.interop/debug-enabled?
          ;; no A silencing for cb, and cb still receives B's records
          (let [before-extra (count @received)]
            (rf/dispatch-sync [:vxgfnd245/b-settle] {:frame id})
            (is (= [[] (inc before-extra)]
                   [(filterv #(= cb (:cb-id (:tags %))) @silencings) (count @received)]))))
        (rf/destroy-frame! id)
        (is (nil? (rf.frame/frame-incarnation-token id))
            "B's own destroy takes effect")
        (when rf.interop/debug-enabled?
          (is (= 1 (count (filter #(= cb (:cb-id (:tags %))) @silencings)))
              "exactly one truthful silencing for cb — fired by B's own destroy")))
      (finally
        (.countDown release-a)
        (rf/unregister-listener! :epoch cb)
        (rf/unregister-listener! :trace ::vxgfnd245-silencing)
        (when (rf.frame/frame id) (rf.frame/destroy-frame! id))
        (rf.late-bind/set-fn! :epoch/on-frame-destroyed original-epoch)))))

(deftest predecessor-silencing-honours-new-generation-registered-in-gap
  ;; The re-register-in-the-gap case: cb observes
  ;; A, A self-destroys mid-drain and pauses; during the pause cb is REPLACED by a
  ;; NEW generation and same-id B settles (the new cb generation receives B's
  ;; record and is re-armed). A's stale snapshot silencing must not fire against
  ;; the reused id: cb is live (new generation) on B. B's later destroy emits
  ;; exactly one truthful silencing for the new generation.
  (let [id             :vxgfnd245/regen
        cb             ::vxgfnd245-regen-cb
        a-after-dissoc (CountDownLatch. 1)
        release-a      (CountDownLatch. 1)
        first-epoch?   (atom true)
        old-received   (atom 0)
        new-received   (atom 0)
        silencings     (atom [])
        original-epoch (rf.late-bind/get-fn :epoch/on-frame-destroyed)]
    (rf/reg-event :vxgfnd245/regen-a-settle
      (fn [{:keys [db]} _] {:db (assoc db :a true)}))
    (rf/reg-event :vxgfnd245/regen-a-destroy
      (fn [_ _] (rf.frame/destroy-frame! id) {:db {:owner :a-tail}}))
    (rf/reg-event :vxgfnd245/regen-b-settle
      (fn [{:keys [db]} _] {:db (assoc db :owner :b)}))
    (rf/make-frame {:id id})
    (rf/register-listener! :epoch cb (fn [_] (swap! old-received inc)))
    (rf/dispatch-sync [:vxgfnd245/regen-a-settle] {:frame id})
    (rf/register-listener! :trace ::vxgfnd245-regen-silencing
      (fn [ev]
        (when (= :rf.epoch.cb/silenced-on-frame-destroy (:operation ev))
          (swap! silencings conj ev))))
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
        ;; Replace cb with a NEW generation in the gap, then B settles.
        (rf/register-listener! :epoch cb (fn [_] (swap! new-received inc)))
        (rf/make-frame {:id id})
        (rf/dispatch-sync [:vxgfnd245/regen-b-settle] {:frame id})
        (.countDown release-a)
        (is (not= ::timeout (deref dispatch-a 5000 ::timeout))
            "A's terminal recipe completes")
        (executor-barrier!)
        (is (= {:owner :b} (rf.frame/frame-app-db-value id))
            "A's inert tail never mutated B's state across the gap re-register")
        (when rf.interop/debug-enabled?
          ;; A delivers its halted record to the generation it snapshotted (the
          ;; old one: A's settle + that record); the new one saw only B's settle
          (is (= [1 2 []]
                 [@new-received @old-received
                  (filterv #(= cb (:cb-id (:tags %))) @silencings)])
              "A's stale snapshot never silences the reused id after a gap re-register"))
        ;; B destroys → exactly one truthful silencing for the live new generation.
        (rf/destroy-frame! id)
        (is (nil? (rf.frame/frame-incarnation-token id))
            "B's own destroy takes effect")
        (when rf.interop/debug-enabled?
          (is (= 1 (count (filter #(= cb (:cb-id (:tags %))) @silencings)))
              "exactly one truthful silencing for the new generation on B's destroy")))
      (finally
        (.countDown release-a)
        (rf/unregister-listener! :epoch cb)
        (rf/unregister-listener! :trace ::vxgfnd245-regen-silencing)
        (when (rf.frame/frame id) (rf.frame/destroy-frame! id))
        (rf.late-bind/set-fn! :epoch/on-frame-destroyed original-epoch)))))

;; Structural delivery turns epoch capture, frame policy and ring retention off
;; around its emit, and restores ordinary defaults around the listener fan-out,
;; so work a listener dispatches into another frame is captured, retained and
;; policed as that frame's own. Only a dispatching listener can see the scope.

(deftest listener-dispatch-during-structural-terminal-fact-runs-under-normal-scope
  ;; A's top-level destroy fans a structural silencing fact; a listener reacting
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
    (rf/reg-event :vf2qke/seed (fn [_ _] {:db {:n 0}}))
    ;; An epoch observer so A's destroy fans a real structural terminal fact.
    (rf/register-listener! :epoch ::vf2qke-a-obs (fn [_] nil))
    (rf/dispatch-sync [:vf2qke/seed] {:frame a-id})
    ;; The dispatching public listener: on A's structural terminal fact, run
    ;; ordinary nested work into unrelated C exactly once.
    (rf/register-listener! :trace ::vf2qke-dispatcher
      (fn [ev]
        (when (and (= a-id (get-in ev [:tags :frame]))
                   (= :rf.epoch.cb/silenced-on-frame-destroy (:operation ev))
                   (compare-and-set! fired? false true))
          (rf/dispatch-sync [:vf2qke/c-event] {:frame c-id}))))
    ;; A raw trace listener capturing C's own run events (proves live delivery).
    (rf/register-listener! :trace ::vf2qke-c-live
      (fn [ev]
        (when (= c-id (get-in ev [:tags :frame]))
          (swap! c-live conj ev))))
    (try
      (rf/destroy-frame! a-id)
      (executor-barrier!)
      ;; dev-only end to end: the driver is a trace fact the gate never emits
      (when rf.interop/debug-enabled?
        (is (= [true 1 {:c :ran} true true]
               [@fired?
                (count (rf/epoch-history c-id))
                (rf.frame/frame-app-db-value c-id)
                (boolean (seq (rf/trace-buffer c-id {:flat true})))
                (boolean (seq @c-live))])
            "C's listener-triggered work ran, was captured and retained, and streamed live"))
      (finally
        (rf/unregister-listener! :epoch ::vf2qke-a-obs)
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
    (rf/reg-event :vf2qke/policy-seed (fn [_ _] {:db {:n 0}}))
    (rf/register-listener! :epoch ::vf2qke-policy-obs (fn [_] nil))
    (rf/dispatch-sync [:vf2qke/policy-seed] {:frame a-id})
    (rf/register-listener! :trace ::vf2qke-policy-dispatcher
      (fn [ev]
        (when (and (= a-id (get-in ev [:tags :frame]))
                   (= :rf.epoch.cb/silenced-on-frame-destroy (:operation ev))
                   (compare-and-set! fired? false true))
          (rf/dispatch-sync [:vf2qke/d-event] {:frame d-id}))))
    (rf/register-listener! :trace ::vf2qke-d-live
      (fn [ev]
        (when (= d-id (get-in ev [:tags :frame]))
          (swap! d-live conj ev))))
    (try
      (rf/destroy-frame! a-id)
      (executor-barrier!)
      (when rf.interop/debug-enabled?
        (is (= [true {:d :ran} [] true]
               [@fired?
                (rf.frame/frame-app-db-value d-id)
                @d-live
                (empty? (rf/trace-buffer d-id {:flat true}))])
            "D's work ran and committed, and its no-emit policy suppressed every trace"))
      (finally
        (rf/unregister-listener! :epoch ::vf2qke-policy-obs)
        (rf/unregister-listener! :trace ::vf2qke-policy-dispatcher)
        (rf/unregister-listener! :trace ::vf2qke-d-live)
        (when (rf.frame/frame a-id) (rf.frame/destroy-frame! a-id))
        (when (rf.frame/frame d-id) (rf.frame/destroy-frame! d-id))))))

(deftest owner-loss-unwinds-only-entered-authored-afters
  ;; Mutation tooth: removing execute-chain's continuation predicate would run
  ;; :never/before and the handler after :killer/before destroys A. Removing
  ;; the entered-stack unwind would lose the two cleanup assertions.
  (let [id           :destroy/authored-unwind
        outer-before (atom 0)
        outer-after  (atom 0)
        killer-after (atom 0)
        never-before (atom 0)
        handler-runs (atom 0)]
    (rf/make-frame {:id id})
    (rf/reg-interceptor :destroy/outer
      {:before #(do (swap! outer-before inc) %)
       :after  #(do (swap! outer-after inc) %)})
    (rf/reg-interceptor :destroy/killer
      {:before #(do (rf.frame/destroy-frame! id) %)
       :after  #(do (swap! killer-after inc) %)})
    (rf/reg-interceptor :destroy/never
      {:before #(do (swap! never-before inc) %)})
    (rf/reg-event :destroy/unwind
      {:interceptors [:destroy/outer :destroy/killer :destroy/never]}
      (fn [_ _] (swap! handler-runs inc) {:db {:forbidden true}}))
    (rf/dispatch-sync [:destroy/unwind] {:frame id})
    ;; [outer-before killer-after outer-after never-before handler]
    (is (= [1 1 1 0 0]
           [@outer-before @killer-after @outer-after @never-before @handler-runs])
        "entered afters unwind; no later before or handler enters after owner loss")))

(deftest destroy-then-throw-is-inert-and-skips-normal-settlement
  ;; Mutation tooth: reordering interceptor-error ahead of exact-owner loss
  ;; leaks the authored throw; removing the settle fence invokes the normal
  ;; epoch hook after the terminal destroy hook already owned A's snapshot.
  (let [id              :destroy/throw-before
        after-runs      (atom 0)
        handler-runs    (atom 0)
        normal-settles  (atom 0)
        original-settle (rf.late-bind/get-fn :epoch/settle!)]
    (rf/make-frame {:id id})
    (rf/reg-interceptor :destroy/throwing
      {:before (fn [_]
                 (rf.frame/destroy-frame! id)
                 (throw (ex-info "obsolete A failure" {})))
       :after  #(do (swap! after-runs inc) %)})
    (rf/reg-event :destroy/throwing-event
      {:interceptors [:destroy/throwing]}
      (fn [_ _] (swap! handler-runs inc) {}))
    (try
      (rf.late-bind/set-fn!
        :epoch/settle!
        (fn [& args]
          (swap! normal-settles inc)
          (when original-settle (apply original-settle args))))
      (is (nil? (rf/dispatch-sync [:destroy/throwing-event] {:frame id}))
          "destroy+throw does not escape the obsolete continuation")
      (finally
        (rf.late-bind/set-fn! :epoch/settle! original-settle)))
    (is (= [1 0 0] [@after-runs @handler-runs @normal-settles])
        "the entered after unwound; no handler and no normal settlement followed")))

(deftest first-fx-owner-loss-fences-later-fx-and-resolution-throws
  ;; Mutation teeth: removing the per-entry do-fx owner check runs :second;
  ;; removing the registrar callback wrapper lets the second dispatch escape.
  (let [id          :destroy/fx-tail
        second-runs (atom 0)
        original    rf.registrar/lookup]
    (rf/make-frame {:id id})
    (rf/reg-fx :destroy/first
      (fn [_ _] (rf.frame/destroy-frame! id)))
    (rf/reg-fx :destroy/second
      (fn [_ _] (swap! second-runs inc)))
    (rf/reg-event :destroy/fx-event
      (fn [_ _] {:fx [[:destroy/first nil] [:destroy/second nil]]}))
    (rf/dispatch-sync [:destroy/fx-event] {:frame id})
    (is (zero? @second-runs) "later fx entries are framework-owned stale tail")

    (rf/make-frame {:id id})
    (rf/reg-event :destroy/fx-resolver-event
      (fn [_ _] {:fx [[:destroy/second nil]]}))
    (with-redefs [rf.registrar/lookup
                  (fn [kind key]
                    (if (and (= kind :fx) (= key :destroy/second))
                      (do (rf.frame/destroy-frame! id)
                          (throw (ex-info "resolver lost A" {})))
                      (original kind key)))]
      (is (= [nil 0]
             [(rf/dispatch-sync [:destroy/fx-resolver-event] {:frame id}) @second-runs])
          "fx resolver destroy+throw is inert and never invokes the fx body"))))

(deftest adapter-read-destroy-throw-stops-before-handler
  ;; Mutation tooth: removing the run-one-pass callback fence leaks this throw
  ;; and/or invokes the handler after the pre-event snapshot read lost A.
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
                          (throw (ex-info "read lost A" {})))
                      (original container)))]
      (is (= [nil 0]
             [(rf/dispatch-sync [:destroy/adapter-read-event] {:frame id}) @handler-runs])
          "adapter read destroy+throw is inert and no handler starts"))))

(deftest adapter-replace-watch-loss-keeps-only-the-linearized-a-write
  ;; Mutation teeth: the adapter callback performs the physical A install and
  ;; synchronously destroys A. The exact post-callback check must suppress the
  ;; id-keyed commit epoch, change trailers, fx, and normal epoch settlement;
  ;; none may be redirected into B while the obsolete callback is paused.
  (let [id              :destroy/adapter-replace-watch
        replaced-a      (CountDownLatch. 1)
        release-a       (CountDownLatch. 1)
        armed?          (atom true)
        physical-a      (atom nil)
        fx-runs         (atom 0)
        normal-settles  (atom 0)
        traces          (atom [])
        base-read       (:read-container rf.substrate.plain-atom/adapter)
        base-replace    (:replace-container! rf.substrate.plain-atom/adapter)
        original-settle (rf.late-bind/get-fn :epoch/settle!)
        watching-adapter
        (assoc rf.substrate.plain-atom/adapter
               :kind :custom
               :replace-container!
               (fn [container value]
                 (base-replace container value)
                 (when (compare-and-set! armed? true false)
                   (reset! physical-a (base-read container))
                   (rf.frame/destroy-frame! id)
                   (.countDown replaced-a)
                   (.await release-a 10 TimeUnit/SECONDS))))]
    (rf.substrate.adapter/dispose-adapter!)
    (reset! rf.frame/frames {})
    (rf.substrate.adapter/install-adapter! watching-adapter)
    (rf/make-frame {:id id})
    (rf/reg-fx :destroy/adapter-tail-fx (fn [_ _] (swap! fx-runs inc)))
    (rf/reg-event :destroy/adapter-replace-event
      (fn [_ _]
        {:db {:owner :a-physical}
         :fx [[:destroy/adapter-tail-fx nil]]}))
    (rf/register-listener! :trace ::adapter-replace-watch
      (fn [ev]
        (when (and (= id (get-in ev [:tags :frame]))
                   (contains? #{:rf.event/db-changed
                                :rf.event/frame-state-changed
                                :rf.event/run-end}
                              (:operation ev)))
          (swap! traces conj ev))))
    (try
      (rf.late-bind/set-fn!
        :epoch/settle!
        (fn [& args]
          (swap! normal-settles inc)
          (when original-settle (apply original-settle args))))
      (let [dispatch-a (future
                         (rf/dispatch-sync [:destroy/adapter-replace-event]
                                           {:frame id}))]
        (is (.await replaced-a 10 TimeUnit/SECONDS)
            "the adapter installed A's value and its synchronous watch destroyed A")
        (rf/make-frame {:id id})
        (let [token-b (rf.frame/frame-incarnation-token id)]
          (.countDown release-a)
          ;; A's physical install stands; B's token, app-db and commit epoch,
          ;; the fx tail, normal settlement and the trailers are all untouched
          (is (= [true {:rf.db/app {:owner :a-physical} :rf.db/runtime {}} true {} 0 0 0 []]
                 [(not= ::timeout (deref dispatch-a 5000 ::timeout))
                  @physical-a
                  (identical? token-b (rf.frame/frame-incarnation-token id))
                  (rf.frame/frame-app-db-value id)
                  (rf.frame/frame-commit-epoch id)
                  @fx-runs
                  @normal-settles
                  @traces]))))
      (finally
        (.countDown release-a)
        (rf.late-bind/set-fn! :epoch/settle! original-settle)
        (rf/unregister-listener! :trace ::adapter-replace-watch)
        (when (rf.frame/frame id) (rf.frame/destroy-frame! id))
        (reset! rf.frame/frames {})
        (rf.substrate.adapter/dispose-adapter!)
        (rf.substrate.adapter/install-adapter! rf.substrate.plain-atom/adapter)))))

(deftest cofx-supplier-loss-stops-diagnostics-later-resolution-and-handler
  ;; Mutation teeth: the first supplier destroys A and throws. The throw is
  ;; inert, no coeffect-exception diagnostic is emitted against absence/B, the
  ;; second supplier is never resolved, and the handler never starts.
  (let [id           :destroy/cofx-tail
        later-runs   (atom 0)
        handler-runs (atom 0)
        diagnostics  (atom [])]
    (rf/make-frame {:id id})
    (rf/reg-cofx :destroy/cofx-killer
      (fn []
        (rf.frame/destroy-frame! id)
        (throw (ex-info "cofx supplier lost A" {}))))
    (rf/reg-cofx :destroy/cofx-later (fn [] (swap! later-runs inc)))
    (rf/reg-event :destroy/cofx-event
      {:rf.cofx/requires [:destroy/cofx-killer :destroy/cofx-later]}
      (fn [_ _] (swap! handler-runs inc) {:db {:forbidden true}}))
    (rf/register-listener! :trace ::cofx-loss
      (fn [ev]
        (when (= :rf.error/coeffect-exception (:operation ev))
          (swap! diagnostics conj ev))))
    (try
      (is (= [nil 0 0 []]
             [(rf/dispatch-sync [:destroy/cofx-event] {:frame id})
              @later-runs @handler-runs @diagnostics]))
      (finally
        (rf/unregister-listener! :trace ::cofx-loss)))))

(deftest epoch-digest-owner-loss-cannot-publish-into-successor
  ;; Mutation teeth: the normal settle digest callback destroys A, pauses after
  ;; A's private teardown completes, then throws after B is installed. No
  ;; normal record, last-settled anchor, cascade aggregation, or B-era listener
  ;; notification may follow.
  (let [id               :destroy/epoch-digest
        digest-lost-a    (CountDownLatch. 1)
        release-digest   (CountDownLatch. 1)
        b-listener-runs  (atom 0)
        cascade-captures (atom 0)
        original-digest  (rf.late-bind/get-fn :schemas/app-schemas-digest)
        original-capture (rf.late-bind/get-fn :trace.cascade/capture-for-epoch!)]
    (rf/make-frame {:id id})
    (rf/reg-event :destroy/epoch-digest-event
      (fn [_ _] {:db {:owner :a-committed}}))
    (try
      (rf.late-bind/set-fn!
        :schemas/app-schemas-digest
        ;; The hook takes ONE opts map with a REQUIRED :frame.
        (fn [{:keys [frame] :as opts}]
          (if (= id frame)
            (do
              (rf.frame/destroy-frame! id)
              (.countDown digest-lost-a)
              (.await release-digest 10 TimeUnit/SECONDS)
              (throw (ex-info "digest lost A" {})))
            (when original-digest (original-digest opts)))))
      (rf.late-bind/set-fn!
        :trace.cascade/capture-for-epoch!
        (fn [& args]
          (swap! cascade-captures inc)
          (when original-capture (apply original-capture args))))
      ;; dev-only end to end: the digest hook is reached only from
      ;; epoch/settle!, which the gate's empty capture buffer never calls
      (when rf.interop/debug-enabled?
        (let [dispatch-a (future
                           (rf/dispatch-sync [:destroy/epoch-digest-event]
                                             {:frame id}))]
          (is (.await digest-lost-a 10 TimeUnit/SECONDS)
              "the digest callback destroyed A before normal publication")
          (rf/make-frame {:id id})
          (rf/register-listener! :epoch ::epoch-digest-b
            (fn [_] (swap! b-listener-runs inc)))
          (let [token-b (rf.frame/frame-incarnation-token id)]
            (.countDown release-digest)
            (is (= [true true {} [] nil 0 0]
                   [(not= ::timeout (deref dispatch-a 5000 ::timeout))
                    (identical? token-b (rf.frame/frame-incarnation-token id))
                    (rf.frame/frame-app-db-value id)
                    (vec (rf/epoch-history id))
                    (rf.epoch.state/last-settled-epoch-id id)
                    @cascade-captures
                    @b-listener-runs])
                "the digest throw is inert: no A record, anchor, cascade or listener reaches B"))))
      (finally
        (.countDown release-digest)
        (rf/unregister-listener! :epoch ::epoch-digest-b)
        (rf.late-bind/set-fn! :schemas/app-schemas-digest original-digest)
        (rf.late-bind/set-fn! :trace.cascade/capture-for-epoch! original-capture)))))

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
        {}))
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
              (is (= [[:halted-destroy] [:blocked] true true]
                     [(outcomes :rf.epoch/snapshotted)
                      (outcomes :rf.epoch/outcome)
                      (empty? (rf/epoch-history id))
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

(deftest ambient-frame-scope-cannot-replace-the-dequeued-event-owner
  ;; Mutation tooth: dispatch origin authority is the exact dequeue owner A,
  ;; not the ambient `with-frame` target. The same B dispatch is accepted while
  ;; A is live and becomes inert after A destroys itself.
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
          (rf/dispatch [:destroy/ambient-b-event :before-loss]))
        (rf.frame/destroy-frame! a-id)
        (rf/with-frame b-id
          (rf/dispatch [:destroy/ambient-b-event :after-loss]))
        {}))
    (rf/dispatch-sync [:destroy/ambient-a-event] {:frame a-id})
    (executor-barrier!)
    (is (= {:seen [:before-loss]} (rf.frame/frame-app-db-value b-id))
        "ambient B accepts the pre-loss dispatch but cannot revive A's post-loss tail")))

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

(deftest owner-loss-stops-later-trace-and-always-on-listeners
  ;; Mutation teeth: replacing the per-listener continuation loops with doseq
  ;; invokes listener #2; removing the trailer callback fence invokes the
  ;; frame-owned observation route after corpus listener #1 destroyed A.
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
          (throw (ex-info "trace listener lost A" {})))))
    (rf/register-listener! :trace ::trace-sibling
      (fn [ev]
        (when (and (= :rf.event/run-start (:operation ev))
                   (= trace-id (get-in ev [:tags :frame])))
          (swap! trace-sibling inc))))
    (rf/dispatch-sync [:destroy/trace-fanout-event] {:frame trace-id})
    ;; A drain-owned trace fan-out is deferred to the post-drain boundary (no
    ;; listener code runs under a frame's :drain-lock), so the handler has
    ;; already run; within the fan-out, listener #1 losing A stops the rest.
    (is (= [0 1] [@trace-sibling @handler-runs])
        "later trace listeners stop after listener #1 loses A")

    (rf/make-frame {:id event-id})
    (rf/reg-event :destroy/event-fanout-event (fn [_ _] {}))
    (rf.event-emit/register-event-listener! ::event-destroyer
      (fn [_]
        (rf.frame/destroy-frame! event-id)
        (throw (ex-info "event listener lost A" {}))))
    (rf.event-emit/register-event-listener! ::event-sibling
      (fn [_] (swap! event-sibling inc)))
    (try
      (rf.late-bind/set-fn! :observability/route-handled-event
        (fn [& _] (swap! routed inc)))
      (rf/dispatch-sync [:destroy/event-fanout-event] {:frame event-id})
      (finally
        (rf.late-bind/set-fn! :observability/route-handled-event original-route)))
    (is (= [0 0] [@event-sibling @routed])
        "later corpus listeners and the frame-owned observation route stay inert")))

(deftest union-error-fanout-loss-skips-siblings-and-frame-route
  ;; Mutation tooth: an unconditional route-error-record! after corpus fanout
  ;; routes A's union record against a replacement/absent frame.
  (let [id             :destroy/union-error-fanout
        sibling-runs   (atom 0)
        route-runs     (atom 0)
        original-route (rf.late-bind/get-fn :observability/route-error-record)]
    (rf/make-frame {:id id})
    (rf.error-emit/register-error-listener! ::union-destroyer
      (fn [_]
        (rf.frame/destroy-frame! id)
        (throw (ex-info "union listener lost A" {}))))
    (rf.error-emit/register-error-listener! ::union-sibling
      (fn [_] (swap! sibling-runs inc)))
    (rf/reg-event :destroy/union-error-event
      (fn [_ _]
        (rf.error-emit/dispatch-error-record!
          {:error :rf.error/test-union :frame id :time 0})
        {}))
    (try
      (rf.late-bind/set-fn! :observability/route-error-record
        (fn [& _] (swap! route-runs inc)))
      (rf/dispatch-sync [:destroy/union-error-event] {:frame id})
      (finally
        (rf.late-bind/set-fn! :observability/route-error-record original-route)))
    (is (= [0 0] [@sibling-runs @route-runs])
        "later union listeners and the frame-owned union route stay inert")))

(deftest depth-halt-fanout-and-commit-fenced-when-first-listener-loses-a
  ;; The depth halt fans its always-on record to the corpus and the frame
  ;; route, then commits A's :halted-depth epoch record. It runs outside the
  ;; event pipeline, so it binds A's exact-owner continuation predicate and
  ;; threads A's token into the commit: once the first listener destroys A and
  ;; seats same-id B, no later sibling, frame route or halt commit touches B.
  (let [id             :drain.incarnation/depth-loss
        depth-records  (atom [])
        sibling-runs   (atom 0)
        frame-routes   (atom 0)
        b-token        (atom nil)
        b-sentinel     {:operation :drain.incarnation/b-buffer :tags {:frame id}}
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
            (rf.frame/destroy-frame! id)
            (rf/make-frame {:id id})
            (reset! b-token (rf.frame/frame-incarnation-token id))
            (rf.epoch.state/buffer-event! id b-sentinel))))
      (rf.error-emit/register-error-listener! ::depth-sibling
        (fn [record]
          (when (= :rf.error/drain-depth-exceeded (:error record))
            (swap! sibling-runs inc))))
      (rf/dispatch-sync [:drain.incarnation/loop] {:frame id})
      (executor-barrier!)
      ;; [records B-seated siblings routes B-current B-db history anchor buffer]
      (is (= [1 true 0 0 true {} true nil [b-sentinel]]
             [(count @depth-records)
              (some? @b-token)
              @sibling-runs
              @frame-routes
              (identical? @b-token (rf.frame/frame-incarnation-token id))
              (rf.frame/frame-app-db-value id)
              (empty? (rf/epoch-history id))
              (rf.epoch.state/last-settled-epoch-id id)
              (rf.epoch.state/buffer-for id)])
          "the first listener's record stands once, and nothing after it touches B")
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
        (is (some #(= :halted-depth (:outcome %)) (rf/epoch-history id))
            "A's terminal halted-depth record is committed into A's own history"))
      (finally
        (rf.error-emit/unregister-error-listener! ::live-a)
        (rf.error-emit/unregister-error-listener! ::live-b)
        (rf.error-emit/clear-error-listeners!)
        (when (rf.frame/frame id) (rf.frame/destroy-frame! id))))))
