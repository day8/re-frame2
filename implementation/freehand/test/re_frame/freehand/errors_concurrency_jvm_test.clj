(ns re-frame.freehand.errors-concurrency-jvm-test
  "FH-ERROR-003 under OVERLAPPING failures — attribution is failure-local.

  The structural host renders on whatever thread asked it to, and a server
  rendering two responses runs two walks at once. Each walk may contain its
  own failure, and the two are unrelated: they have different throwers,
  different boundaries, and different readers. Attribution that lived in one
  process-wide slot cannot represent both — the second thrower's identity is
  dropped, stolen, or cleared by the first, and a report that named the wrong
  view would send its reader to the wrong file while the failure it was
  actually about went out as `unknown-view`.

  This suite is JVM-only because the defect it guards is only reachable where
  two renders genuinely overlap. The single-threaded shape of the same law —
  an abandoned throw, a later unrelated boundary — is cross-host and lives in
  `errors-tree-cljs-test`."
  (:require [clojure.test :refer [deftest is testing]]
            [re-frame.freehand.descriptor :as descriptor]
            [re-frame.freehand.errors :as eb]
            [re-frame.freehand.tree :as tree])
  (:import [java.util.concurrent BrokenBarrierException CyclicBarrier TimeUnit]))

(def ^:private thrower-a
  (descriptor/declare-view
    {:view-id :audit/a :lowering :interpreted :children-policy :optional
     :render  (fn [_] (throw (ex-info "A's render threw" {})))}))

(def ^:private thrower-b
  (descriptor/declare-view
    {:view-id :audit/b :lowering :interpreted :children-policy :optional
     :render  (fn [_] (throw (ex-info "B's render threw" {})))}))

(defn- rendezvous!
  "Wait for the other walk to reach the same point, or fail loudly. A
  deadlock here is a test bug, never a silent pass."
  [^CyclicBarrier barrier]
  (try
    (.await barrier 10 TimeUnit/SECONDS)
    (catch BrokenBarrierException _ nil)))

(defn- contained-summary-in-lockstep
  "Walk `child` under a fresh boundary on THIS thread, rendezvousing with the
  peer walk twice: once inside the guarded region before anything has thrown,
  and once after the occurrence seam has seen the throw but before the
  boundary catches it. Both walks are therefore mid-failure at the same
  instant — the overlap a process-wide relay cannot represent."
  [child ^CyclicBarrier barrier]
  (let [b (eb/boundary eb/boundary-view-id :rk)]
    (:summary
      (eb/contain b
                  (fn []
                    (rendezvous! barrier)
                    (try
                      (tree/render child)
                      (catch Throwable e
                        ;; The seam noted this walk's thrower a frame ago.
                        (rendezvous! barrier)
                        (throw e))))
                  {:phase :render :frame-id nil}))))

(deftest fh-error-003-two-overlapping-walks-keep-their-own-attribution
  (testing "Per FH-ERROR-003: two boundaries containing two different
            failures at the same instant each name their OWN thrower. One
            relay slot cannot: the first note wins and the second walk's
            thrower is lost, so one report names a view that did not fail
            this failure and the other reports `unknown-view` for a thrower
            that was plainly observed."
    (let [barrier (CyclicBarrier. 2)
          a       (future (contained-summary-in-lockstep [thrower-a {}] barrier))
          bb      (future (contained-summary-in-lockstep [thrower-b {}] barrier))
          summaries [(deref a 15000 ::timeout) (deref bb 15000 ::timeout)]]
      (is (not-any? #{::timeout} summaries) "both walks finished")
      (is (= #{:audit/a :audit/b} (set (map :view-id summaries)))
          "each overlapping failure names its own thrower")
      (is (not-any? #(= eb/unknown-view-id (:view-id %)) summaries)
          "and neither is suppressed into unknown by the other")
      (is (= 2 (count (set (map :fingerprint summaries))))
          "so the two failures carry two correlation tokens")
      (is (every? #(true? (:complete? (:evidence %))) summaries)
          "with complete evidence on both — nothing was lost to the overlap"))))

(def ^:private healthy
  (descriptor/declare-view
    {:view-id :audit/healthy :lowering :interpreted :children-policy :optional
     :render  (fn [_] [:span "ok"])}))

(deftest fh-error-003-an-overlapping-walk-does-not-clear-a-peers-attribution
  (testing "Per FH-ERROR-003: the reverse hazard. A peer walk that ENTERS a
            containment while this walk is already mid-failure must not wipe
            what this walk's seam observed. Only ONE walk here fails; the
            other is a bystander that never throws at all, and it opens its
            guarded region in the window between the failing walk's note and
            the failing walk's catch. A boundary that scrubbed shared
            attribution state on entry would erase a peer's observed thrower
            and turn a perfectly attributable failure into `unknown-view` —
            with no failure of its own to show for it."
    (let [barrier (CyclicBarrier. 2)
          failing (future
                    (let [b (eb/boundary eb/boundary-view-id :rk)]
                      (:summary
                        (eb/contain b
                                    (fn []
                                      (rendezvous! barrier)      ; 1: both inside
                                      (try
                                        (tree/render [thrower-a {}])
                                        (catch Throwable e
                                          (rendezvous! barrier)  ; 2: the note is made
                                          (rendezvous! barrier)  ; 3: the peer has entered
                                          (throw e))))
                                    {:phase :render :frame-id nil}))))
          quiet   (future
                    (do
                      (rendezvous! barrier)                      ; 1
                      (rendezvous! barrier)                      ; 2
                      ;; Enter a containment NOW — after the peer's note, before
                      ;; the peer's catch.
                      (let [b (eb/boundary eb/boundary-view-id :rk)]
                        (eb/contain b
                                    (fn []
                                      (rendezvous! barrier)      ; 3
                                      (tree/render [healthy {}]))
                                    {:phase :render :frame-id nil}))))
          summary (deref failing 15000 ::timeout)
          outcome (deref quiet 15000 ::timeout)]
      (is (not= ::timeout summary) "the failing walk finished")
      (is (not= ::timeout outcome) "and so did the bystander")
      (is (= :ok (:status outcome)) "the bystander rendered — it never failed")
      (is (= :audit/a (:view-id summary))
          "and the failing walk still names the view that threw")
      (is (true? (:complete? (:evidence summary)))
          "with complete evidence — the bystander took nothing from it"))))
