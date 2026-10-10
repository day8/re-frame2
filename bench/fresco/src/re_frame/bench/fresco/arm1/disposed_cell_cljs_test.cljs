(ns re-frame.bench.fresco.arm1.disposed-cell-cljs-test
  "THE REGISTRY-EPOCH AND NODE-KEY AXES.

  A `:sub` re-registration moves neither term of
  [[re-frame.bench.fresco.arm1.runtime/commit-basis]]
  (`generation_fence_coverage_cljs_test` pins that), and neither does a
  same-id frame reincarnation. Giving every key a registry term would
  re-render every boundary on every re-registration.

  **The axes are not blind spots in an arithmetic, and this file is the
  measurement.** Both events *dispose the reaction a cell holds*:

  - a `:sub` re-registration evicts the query's sub-cache entry and
    disposes its reaction (`re-frame.subs.cache/invalidate-sub-on-replace!`);
  - a frame destruction disposes the frame's cached reactions, including
    when a same-id successor immediately replaces it.

  The spine's derived container clears its own watcher set in `-dispose`,
  so from that instant an unrepaired cell is **deaf** — no watch, so no
  `mark-dirty!`, so no flush, so no notification ever again — and its
  deref answers the RETIRED computation, or the destroyed incarnation's
  app-db, for as long as the boundary lives. The two `deliberately-*`
  rows below pin exactly that failure, and they are the reason there is
  no registry term: a moved number buys one extra render, and the extra
  render reads back through the same dead cell.

  What closes both axes is the substrate's own disposal event, armed once
  per unique key at `wire-cell!` time. It costs no React hook, no
  per-boundary object, and nothing at all in the epoch sum;
  `staged_read_tear_cljs_test` checks the last of those, a clean mount
  leaving the snapshot where the render put it.

  Everything here runs against Arm 1's own runtime over the React spine's
  adapter: on an unwatchable host a subscription never notifies and the
  `notified` half of every row would pass by never firing."
  (:require [cljs.test :refer-macros [async deftest is testing use-fixtures]]
            [re-frame.adapter.uix :as rf.adapter.uix]
            [re-frame.bench.fresco.arm1.runtime :as rf.bench.fresco.arm1.runtime]
            [re-frame.core :as rf]
            [re-frame.frame :as rf.frame]
            [re-frame.live-frame :as rf.live-frame]
            [re-frame.test-support :as rf.test-support]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter       rf.adapter.uix/adapter
     ;; The rebuild rows resume on a later tick, and `cljs.test` refuses a
     ;; fn-form fixture for an `async` test — the fn-form's ambient scope is
     ;; a dynamic binding that would be unwound before the body resumes.
     :async?        true
     ;; The carried-invariant chain resolves the dynamic-var frame tier
     ;; BEFORE React context, so a fixture-installed ambient frame would
     ;; answer reads for a frame this file never made.
     :ambient-frame nil
     :init-fn       (fn [] (rf.bench.fresco.arm1.runtime/reset-runtime!))}))

;; This file's OWN queries and OWN frames: a re-registration is a global
;; act, and re-registering a query some other suite reads would be a test
;; writing on a neighbour.
(def ^:private q-reg [:disposedcell/reg])
(def ^:private q-node [:disposedcell/node])

(defn- make-frame! [id db]
  (rf.live-frame/make-frame {:id id})
  (rf.frame/replace-app-db! id db)
  id)

(defn- reader
  "A boundary whose whole body is one read, so the read set is one key
  and the snapshot arithmetic is one term."
  [q seen]
  (fn [_] (let [v (rf.bench.fresco.arm1.runtime/sub q)] (vreset! seen v) [:li (str v)])))

(defn- settle!
  "Run the macrotask queue once. `invalidate-cell!`'s rebuild is deferred
  — it fires inside the registrar's replacement hook and inside frame
  teardown, and neither is a place to subscribe — so a row that asserts
  about the REBUILT attachment has to wait for it.

  Callers pair this with `cljs.test/async`. Without that the callback
  runs after the run has been reported and its assertions are counted by
  nobody, which is a green that proves nothing."
  [k]
  (js/setTimeout k 0))

(defn- write-then-read
  "Write `db` to frame `f`, then re-render: how many notifications the
  write bought the boundary, and what the re-render read."
  [f q seen hits db]
  (let [before @hits]
    (rf.frame/replace-app-db! f db)
    (rf.bench.fresco.arm1.runtime/render-body f (reader q seen) {})
    [(- @hits before) @seen]))

;; ---------------------------------------------------------------------------
;; The registry-epoch axis
;; ---------------------------------------------------------------------------

(deftest a-re-registered-sub-reaches-a-boundary-that-already-holds-the-key
  (testing "The `:registry-epoch` axis. A boundary is mounted and
            holds a cell for the key; the handler behind that query is
            then REPLACED, which is what an HMR save does. Every later
            render must compute against the new registration, and every
            later write must still notify — without the rebuild the cell
            is deaf from the disposal onwards, because `-dispose` cleared
            the watcher set this arm's `add-watch` was in."
    (rf/reg-sub (first q-reg) (fn [db _] (:v db)))
    (async done
    (let [seen (volatile! nil)
          f    (make-frame! ::registry {:v 1})]
      (rf.bench.fresco.arm1.runtime/render-body f (reader q-reg seen) {})
      (let [entry    (rf.bench.fresco.arm1.runtime/last-reads)
            hits     (volatile! 0)
            release! (rf.bench.fresco.arm1.runtime/commit-boundary! entry (fn [] (vswap! hits inc)))]
        (is (= [1 [1 2]]
               [(:cells (rf.bench.fresco.arm1.runtime/stats))
                (write-then-read f q-reg seen hits {:v 2})])
            "the CONTROL: the commit retained a cell, and its watch is live
             before the re-registration")

        ;; THE RE-REGISTRATION.
        (rf/reg-sub (first q-reg) (fn [db _] (* 10 (:v db))))
        (rf.bench.fresco.arm1.runtime/render-body f (reader q-reg seen) {})
        (let [next-render @seen]
          (settle!
            (fn []
              (is (= [20 true 30]
                     (let [[n v] (write-then-read f q-reg seen hits {:v 3})]
                       [next-render (pos? n) v]))
                  "20, not 2: the cell's reaction was disposed by the sub-cache
                   eviction, so the read falls through to `subscribe-once`,
                   which resolves the handler registered NOW — and the
                   rebuilt attachment notifies again")
              (release!)
              (done)))))))))

(deftest deliberately-a-disposed-cell-derefs-the-retired-computation
  (testing "the failure the row above repairs, stated as its own
            assertion so the repair cannot quietly stop being needed. A
            cell that is NOT invalidated keeps deriving through the
            container the sub-cache disposed, and that container still
            computes — with the handler it captured. It is not a stale
            VALUE (it tracks the db) but a stale COMPUTATION, which is
            strictly worse: it looks alive."
    (rf/reg-sub (first q-reg) (fn [db _] (:v db)))
    (let [f (make-frame! ::registry-retired {:v 1})]
      (rf.bench.fresco.arm1.runtime/render-body f (reader q-reg (volatile! nil)) {})
      (let [entry    (rf.bench.fresco.arm1.runtime/last-reads)
            release! (rf.bench.fresco.arm1.runtime/commit-boundary! entry (fn []))
            ;; Reach past the repair: hold the reaction the cell held, then
            ;; re-register. This is exactly the object the cell would keep
            ;; without `invalidate-cell!`.
            held     (rf.bench.fresco.arm1.runtime/cell-reaction [f q-reg])]
        (rf/reg-sub (first q-reg) (fn [db _] (* 10 (:v db))))
        (rf.frame/replace-app-db! f {:v 7})
        (is (= [7 nil] [@held (rf.bench.fresco.arm1.runtime/cell-reaction [f q-reg])])
            "the disposed container answers 7 — the RETIRED `(:v db)` over
             the new db — where the live registration answers 70, so the
             cell drops the reference: the repair is to stop reading
             through it, not to re-read it")
        (release!)))))

;; ---------------------------------------------------------------------------
;; The node-key axis
;; ---------------------------------------------------------------------------

(deftest a-same-id-frame-reincarnation-reaches-a-boundary-that-holds-the-key
  (testing "The node-key axis. `frame-commit-epoch` is cleared
            by `dissoc-frame!`, so a same-id successor restarts at 0 and
            the basis is monotone only WITHIN one incarnation — which is
            precisely why Spec 006 invariant 5 carries a `:node-key` axis
            rather than trusting the two epochs. The arm observes node
            identity nowhere, and does not need to: the destruction
            disposes the cell's reaction, and that is the same event the
            registry axis rides."
    (rf/reg-sub (first q-node) (fn [db _] (:v db)))
    (async done
    (let [seen (volatile! nil)
          f    (make-frame! ::node {:v 1})]
      (rf.bench.fresco.arm1.runtime/render-body f (reader q-node seen) {})
      (let [entry    (rf.bench.fresco.arm1.runtime/last-reads)
            hits     (volatile! 0)
            release! (rf.bench.fresco.arm1.runtime/commit-boundary! entry (fn [] (vswap! hits inc)))
            token-a  (rf.frame/frame-incarnation-token f)
            basis-a  (rf.bench.fresco.arm1.runtime/commit-basis f)
            read-a   @seen]

        ;; THE REINCARNATION.
        (rf.frame/destroy-frame! f)
        (make-frame! f {:v 99})
        (let [tie [(identical? token-a (rf.frame/frame-incarnation-token f))
                   (rf.bench.fresco.arm1.runtime/commit-basis f)]]
          (rf.bench.fresco.arm1.runtime/render-body f (reader q-node seen) {})
          (is (= [1 [false basis-a] 99] [read-a tie @seen])
            "a distinct incarnation, and yet the basis TIES — the epoch
             restarted at 0 and climbed back to exactly where it stood, so
             no arithmetic over these two terms could have distinguished the
             incarnations. The boundary reads the successor's 99, not 1:
             the destroyed incarnation's reaction was disposed with its
             frame"))

        (settle!
          (fn []
            (is (= [true 100]
                   (let [[n v] (write-then-read f q-node seen hits {:v 100})]
                     [(pos? n) v]))
                "and the rebuilt attachment tracks the successor")
            (release!)
            (done))))))))

(deftest deliberately-a-cell-pinned-to-a-destroyed-incarnation-answers-its-db
  (testing "the reincarnation half of the failure, pinned. The held
            container is wired to the frame that no longer exists, so it
            answers that frame's app-db forever — 1, whatever the
            successor holds — and the epoch sum cannot report it because
            the basis ties."
    (rf/reg-sub (first q-node) (fn [db _] (:v db)))
    (let [f (make-frame! ::node-pinned {:v 1})]
      (rf.bench.fresco.arm1.runtime/render-body f (reader q-node (volatile! nil)) {})
      (let [entry    (rf.bench.fresco.arm1.runtime/last-reads)
            release! (rf.bench.fresco.arm1.runtime/commit-boundary! entry (fn []))
            held     (rf.bench.fresco.arm1.runtime/cell-reaction [f q-node])]
        (rf.frame/destroy-frame! f)
        (make-frame! f {:v 99})
        (is (= [1 nil] [@held (rf.bench.fresco.arm1.runtime/cell-reaction [f q-node])])
            "the pinned container answers incarnation A's 1 where the live
             frame holds 99, so the cell drops it")
        (release!)))))
