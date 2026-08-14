(ns re-frame.bench.hicasso.arm1.disposed-cell-cljs-test
  "THE REGISTRY-EPOCH AND NODE-KEY AXES (rf2-2rtt6.44).

  `generation_fence_coverage_cljs_test` proved that a `:sub`
  re-registration moves neither term of
  [[re-frame.bench.hicasso.arm1.runtime/commit-basis]], and stated the
  same of a same-id frame reincarnation. Both were filed as *design
  decisions wanting costing*: closing them by giving every key a registry
  term looked like the only move, and it would have re-rendered every
  boundary on every re-registration.

  **The costing found the premise wrong, and this file is the
  measurement.** The axes are not blind spots in an arithmetic. Both
  events *dispose the reaction a cell holds*:

  - a `:sub` re-registration evicts the query's sub-cache entry and
    disposes its reaction (`re-frame.subs.cache/invalidate-sub-on-replace!`);
  - a frame destruction disposes the frame's cached reactions, including
    when a same-id successor immediately replaces it.

  The spine's derived container clears its own watcher set in `-dispose`,
  so from that instant the arm's cell is **deaf** — no watch, so no
  `mark-dirty!`, so no flush, so no notification ever again — and its
  deref answers the RETIRED computation, or the destroyed incarnation's
  app-db, for as long as the boundary lives. The four `deliberately-*`
  rows below pin exactly that failure, and they are the reason a registry
  term was declined: a moved number buys one extra render, and the extra
  render reads back through the same dead cell.

  What closes both axes is the substrate's own disposal event, armed once
  per unique key at `wire-cell!` time. It costs no React hook, no
  per-boundary object, and nothing at all in the epoch sum — the two
  rows at the bottom are that bill, checked rather than claimed.

  Everything here runs against Arm 1's own runtime over the React spine's
  adapter: on an unwatchable host a subscription never notifies and the
  `notified` half of every row would pass by never firing."
  (:require [cljs.test :refer-macros [async deftest is testing use-fixtures]]
            [re-frame.adapter.uix :as uix-adapter]
            [re-frame.bench.hicasso.arm1.runtime :as rt]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.live-frame :as live-frame]
            [re-frame.test-support :as test-support]))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter       uix-adapter/adapter
     ;; The rebuild rows resume on a later tick, and `cljs.test` refuses a
     ;; fn-form fixture for an `async` test — the fn-form's ambient scope is
     ;; a dynamic binding that would be unwound before the body resumes.
     :async?        true
     ;; The carried-invariant chain resolves the dynamic-var frame tier
     ;; BEFORE React context, so a fixture-installed ambient frame would
     ;; answer reads for a frame this file never made.
     :ambient-frame nil
     :init-fn       (fn [] (rt/reset-runtime!))}))

;; This file's OWN queries and OWN frames: a re-registration is a global
;; act, and re-registering a query some other suite reads would be a test
;; writing on a neighbour.
(def ^:private q-reg [:disposedcell/reg])
(def ^:private q-node [:disposedcell/node])

(defn- make-frame! [id db]
  (live-frame/make-frame {:id id})
  (frame/replace-app-db! id db)
  id)

(defn- reader
  "A boundary whose whole body is one read, so the read set is one key
  and the snapshot arithmetic is one term."
  [q seen]
  (fn [_] (let [v (rt/sub q)] (vreset! seen v) [:li (str v)])))

(defn- settle!
  "Run the macrotask queue once. `invalidate-cell!`'s rebuild is deferred
  — it fires inside the registrar's replacement hook and inside frame
  teardown, and neither is a place to subscribe — so a row that asserts
  about the REBUILT attachment has to wait for it.

  Callers pair this with `cljs.test/async`. Without that the callback
  runs after the run has been reported and its assertions are counted by
  nobody, which is a green that proves nothing — the state this file was
  in before the assertion count was reconciled against the rows."
  [k]
  (js/setTimeout k 0))

;; ---------------------------------------------------------------------------
;; The registry-epoch axis
;; ---------------------------------------------------------------------------

(deftest a-re-registered-sub-reaches-a-boundary-that-already-holds-the-key
  (testing "The predecessor's `:registry-epoch` field, and the axis
            rf2-2rtt6.42 deliberately left open. A boundary is mounted and
            holds a cell for the key; the handler behind that query is
            then REPLACED, which is what an HMR save does. Every later
            render must compute against the new registration, and every
            later write must still notify."
    (rf/reg-sub (first q-reg) (fn [db _] (:v db)))
    (async done
    (let [seen (volatile! nil)
          f    (make-frame! ::registry {:v 1})]
      (rt/render-body f (reader q-reg seen) {})
      (let [entry    (rt/last-reads)
            hits     (volatile! 0)
            release! (rt/commit-boundary! entry (fn [] (vswap! hits inc)))]
        (is (= 1 (:cells (rt/stats)))
            "RETAINED: the commit acquired a cell, so the key's reaction is
             held for the life of this boundary — which is what makes the
             warm read a pure deref, and what exposes it to a disposal")

        (testing "the CONTROL — the watch is live before the re-registration"
          (frame/replace-app-db! f {:v 2})
          (is (= 1 @hits) "a write notified the boundary")
          (rt/render-body f (reader q-reg seen) {})
          (is (= 2 @seen) "and the re-render read the new value"))

        ;; THE RE-REGISTRATION.
        (rf/reg-sub (first q-reg) (fn [db _] (* 10 (:v db))))

        (testing "the next render computes against the NEW registration"
          (rt/render-body f (reader q-reg seen) {})
          (is (= 20 @seen)
              "20, not 2: the cell's reaction was disposed by the sub-cache
               eviction, so the read falls through to `subscribe-once`,
               which resolves the handler that is registered NOW"))

        (settle!
          (fn []
            (testing "and the durable attachment is rebuilt, so later writes
                      notify again"
              (let [before @hits]
                (frame/replace-app-db! f {:v 3})
                (is (pos? (- @hits before))
                    "the boundary was notified — without the rebuild the cell
                     is deaf from the disposal onwards, because `-dispose`
                     cleared the watcher set this arm's `add-watch` was in")))
            (rt/render-body f (reader q-reg seen) {})
            (is (= 30 @seen) "and it reads the new handler over the new db")
            (release!)
            (done))))))))

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
      (rt/render-body f (reader q-reg (volatile! nil)) {})
      (let [entry    (rt/last-reads)
            release! (rt/commit-boundary! entry (fn []))
            ;; Reach past the repair: hold the reaction the cell held, then
            ;; re-register. This is exactly the object the cell kept before
            ;; `invalidate-cell!` existed.
            held     (rt/cell-reaction [f q-reg])]
        (is (some? held) "precondition: the cell holds a reaction")
        (rf/reg-sub (first q-reg) (fn [db _] (* 10 (:v db))))
        (frame/replace-app-db! f {:v 7})
        (is (= 7 @held)
            "the disposed container answers 7 — the RETIRED `(:v db)` over
             the new db — where the live registration answers 70. A term in
             `getSnapshot` would have scheduled a re-render that read
             exactly this")
        (is (nil? (rt/cell-reaction [f q-reg]))
            "which is why the cell drops the reference instead: the repair
             is to stop reading through it, not to re-read it")
        (release!)))))

;; ---------------------------------------------------------------------------
;; The node-key axis
;; ---------------------------------------------------------------------------

(deftest a-same-id-frame-reincarnation-reaches-a-boundary-that-holds-the-key
  (testing "The predecessor's third field. `frame-commit-epoch` is cleared
            by `dissoc-frame!`, so a same-id successor restarts at 0 and
            the basis is monotone only WITHIN one incarnation — which is
            precisely why Spec 006's observation port carries `:node-key`
            rather than trusting the two epochs. The arm observes node
            identity nowhere, and does not need to: the destruction
            disposes the cell's reaction, and that is the same event the
            registry axis rides."
    (rf/reg-sub (first q-node) (fn [db _] (:v db)))
    (async done
    (let [seen (volatile! nil)
          f    (make-frame! ::node {:v 1})]
      (rt/render-body f (reader q-node seen) {})
      (let [entry    (rt/last-reads)
            hits     (volatile! 0)
            release! (rt/commit-boundary! entry (fn [] (vswap! hits inc)))
            token-a  (frame/frame-incarnation-token f)
            basis-a  (rt/commit-basis f)]
        (is (= 1 @seen) "incarnation A's value")

        ;; THE REINCARNATION.
        (frame/destroy-frame! f)
        (make-frame! f {:v 99})

        (testing "the precondition this axis exists for — the basis TIES"
          (is (not (identical? token-a (frame/frame-incarnation-token f)))
              "a distinct incarnation, and `frame-incarnation-token` is the
               public reader that says so")
          (is (= basis-a (rt/commit-basis f))
              "and yet the basis is the number it was: the epoch restarted at
               0 and climbed back to exactly where it stood. A tie, not a
               near-miss — so no arithmetic over these two terms could have
               distinguished the incarnations"))

        (testing "the boundary reads the SUCCESSOR's app-db"
          (rt/render-body f (reader q-node seen) {})
          (is (= 99 @seen)
              "99, not 1: the destroyed incarnation's reaction was disposed
               with its frame, so the read resolves against the frame that is
               live now"))

        (settle!
          (fn []
            (testing "and the rebuilt attachment tracks the successor"
              (let [before @hits]
                (frame/replace-app-db! f {:v 100})
                (is (pos? (- @hits before)) "a write on B notified the boundary"))
              (rt/render-body f (reader q-node seen) {})
              (is (= 100 @seen)))
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
      (rt/render-body f (reader q-node (volatile! nil)) {})
      (let [entry    (rt/last-reads)
            release! (rt/commit-boundary! entry (fn []))
            held     (rt/cell-reaction [f q-node])]
        (is (some? held) "precondition: the cell holds a reaction")
        (frame/destroy-frame! f)
        (make-frame! f {:v 99})
        (is (= 1 @held)
            "the pinned container answers incarnation A's 1 where the live
             frame holds 99")
        (is (nil? (rt/cell-reaction [f q-node]))
            "so the cell drops it")
        (release!)))))

;; ---------------------------------------------------------------------------
;; What closing the two axes cost
;; ---------------------------------------------------------------------------

(deftest closing-the-axes-cost-no-hook-and-nothing-in-the-snapshot
  (testing "the tripwire. The alternative on the table was a registry term
            in every key's contribution to `getSnapshot`; what landed is
            an event armed once per unique key. So the shell is unchanged,
            the snapshot arithmetic is unchanged, and `subscribe` still
            closes over the read set alone."
    (is (= 2 (count rt/shell-hook-ledger))
        "still two hooks — the disposal hook is not a React hook")
    (is (= [:use-context/frame :use-sync-external-store/subscription-epoch]
           rt/shell-hook-ledger)
        "and the same two, in the same order")
    (let [inv (rt/retained-inventory)]
      (is (= #{:use-ref :use-state :view-cell :candidate-ledger}
             (into #{} (map :token) (:absent inv)))
          "the enumerated absences are unchanged: no per-boundary object was
           added, and nothing is keyed by a render or an attempt")))

  (testing "a clean mount is undisturbed — the repair must not become
            `re-render always`, which is what a registry term would have
            risked on every boundary in the application"
    (rf/reg-sub (first q-reg) (fn [db _] (:v db)))
    (let [f     (make-frame! ::registry-clean {:v 1})
          _     (rt/render-body f (reader q-reg (volatile! nil)) {})
          entry (rt/last-reads)
          at-render (rt/snapshot-of entry)
          release!  (rt/commit-boundary! entry (fn []))]
      (is (= at-render (rt/snapshot-of entry))
          "acquisition still moves nothing: the cell is born at the same
           basis the staged term reported, and arming a disposal hook is not
           a term")
      (release!))))
