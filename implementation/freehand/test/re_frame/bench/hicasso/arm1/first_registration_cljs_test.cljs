(ns re-frame.bench.hicasso.arm1.first-registration-cljs-test
  "THE OTHER REGISTRY TRANSITION (rf2-2rtt6.44).

  `disposed_cell_cljs_test` closed the registry axis for a **replacement**
  — an id that already had a handler and got another one — because that
  transition arrives at the arm as a disposal: the sub-cache evicts the
  query's entry, the reaction is disposed, and
  `re-frame.bench.hicasso.arm1.runtime/invalidate-cell!` rides the event.

  A **first** registration arrives as nothing at all. `registrar`'s
  replacement hook fires only when a previous handler existed, so the
  sub-cache evicts nothing and disposes nothing, and an arm that rode the
  disposal alone would never hear about it.

  That would matter to no one if a boundary could not hold the miss —
  and the substrate is careful that it cannot. A subscribe to an
  unregistered query emits `:rf.error/no-such-sub`, recovers to a
  nil-yielding reaction, and **deliberately does not cache it**, so that
  \"a later registration is observed by the next subscribe\"
  (`re-frame.subs/build-and-cache!*`). Arm 1 breaks that assumption in
  the one way it can: a cell holds its reaction for the life of every
  boundary reading the key, and it never subscribes again. The recovery
  the substrate declined to cache is cached anyway, in the arm's own
  cell, where nothing evicts it.

  Measured before the repair: the boundary read `nil`, the first
  `reg-sub` for the query changed nothing, and no later write notified
  it — **for the life of the mount**, on a query that was by then
  perfectly well registered. That is the shape a lazily loaded module
  hits, and it is the same failure mode `disposed_cell_cljs_test` pins
  for the replacement transition: not a stale value but a retired
  computation, which looks alive.

  The rows below are the direct witness the merged-PR audit asked for.
  The bottom row is the bill: a first registration of an id **no cell
  holds** must still disturb nothing, because the alternative this
  programme declined was a registry term in every key's contribution to
  `getSnapshot`."
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

;; This file's OWN queries. Every row here turns on a query being
;; UNREGISTERED when the boundary mounts, so these ids must be ones no
;; other suite registers — and the fixture's registrar snapshot/restore
;; is what keeps them unregistered at the top of each row.
(def ^:private q-first [:firstreg/pending])
(def ^:private q-held  [:firstreg/held])
(def ^:private q-live  [:firstreg/live])

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
  "Run the macrotask queue once. The repair's rebuild is deferred — it
  fires inside a registrar hook, which is not a place to subscribe — so a
  row that asserts about the REBUILT attachment has to wait for it.
  Paired with `cljs.test/async`, without which the callback runs after the
  run is reported and its assertions are counted by nobody."
  [k]
  (js/setTimeout k 0))

;; ---------------------------------------------------------------------------
;; The direct witness
;; ---------------------------------------------------------------------------

(deftest a-first-registration-reaches-a-boundary-that-already-holds-the-key
  (testing "The transition `add-replacement-hook!` cannot see. A boundary
            renders and COMMITS against a query nobody has registered, so
            its cell holds the substrate's nil-recovery — the one object
            core takes care never to cache. The query is then registered
            for the FIRST time. Every later render must compute against
            that registration, and every later write must notify."
    (async done
      (let [seen (volatile! :unread)
            f    (make-frame! ::first {:v 1})]
        ;; NO `reg-sub` yet. This is the boot-order / lazy-load shape: a
        ;; boundary mounts, reads a query whose module has not registered
        ;; its subs, and stays mounted while it does.
        (rt/render-body f (reader q-first seen) {})
        (is (nil? @seen)
            "the recovery contract, unchanged: an unregistered read emits
             `:rf.error/no-such-sub` and derefs to nil")
        (let [entry    (rt/last-reads)
              hits     (volatile! 0)
              release! (rt/commit-boundary! entry (fn [] (vswap! hits inc)))]
          (is (= 1 (:cells (rt/stats)))
              "and the COMMIT is what makes it a problem: the boundary now
               holds a cell for the key, so the read is a pure deref of
               whatever that cell caught — and what it caught is the
               recovery")

          ;; THE FIRST REGISTRATION. No previous handler, so no replacement
          ;; hook fires, no sub-cache entry is evicted, and no reaction is
          ;; disposed anywhere in the substrate.
          (rf/reg-sub (first q-first) (fn [db _] (:v db)))

          (testing "the next render computes against the registration that
                    now exists"
            (rt/render-body f (reader q-first seen) {})
            (is (= 1 @seen)
                "1, not nil: the cell dropped the recovery, so the read falls
                 through to `subscribe-once`, which resolves the handler that
                 is registered NOW"))

          (settle!
            (fn []
              (testing "and the durable attachment is built against it, so
                        later writes notify"
                (let [before @hits]
                  (frame/replace-app-db! f {:v 2})
                  (is (pos? (- @hits before))
                      "the boundary was notified — without the repair the cell
                       is deaf for the life of the mount, because the watch it
                       installed is on a reaction the registration never
                       reaches")))
              (rt/render-body f (reader q-first seen) {})
              (is (= 2 @seen) "and it reads the registered handler over the new db")
              (release!)
              (done))))))))

(deftest deliberately-a-cell-that-keeps-the-recovery-answers-nil-forever
  (testing "the failure the row above repairs, stated as its own assertion
            so the repair cannot quietly stop being needed. The recovery
            reaction is a constant nil — it is not wired to the frame, it
            is not in any cache, and no registration can ever reach it. A
            cell that keeps it answers nil for as long as the boundary
            lives."
    (let [f (make-frame! ::held {:v 1})]
      (rt/render-body f (reader q-held (volatile! nil)) {})
      (let [entry    (rt/last-reads)
            release! (rt/commit-boundary! entry (fn []))
            ;; Reach past the repair and hold the object the cell caught.
            ;; This is exactly what the cell kept before the first
            ;; registration was made an event.
            held     (rt/cell-reaction [f q-held])]
        (is (some? held)
            "precondition: the commit acquired a reaction, and it is the
             substrate's uncached nil-recovery")
        (rf/reg-sub (first q-held) (fn [db _] (:v db)))
        (is (nil? @held)
            "the held recovery answers nil where the live registration answers
             1 — and it will answer nil after every later write too, because
             it derives from nothing")
        (is (nil? (rt/cell-reaction [f q-held]))
            "which is why the cell drops the reference instead: the repair is
             to stop reading through it, not to re-read it")
        (release!)))))

;; ---------------------------------------------------------------------------
;; What closing the transition cost
;; ---------------------------------------------------------------------------

(deftest a-first-registration-of-an-id-no-cell-holds-disturbs-nothing
  (testing "the bill, and the tripwire on the alternative this programme
            declined (a `:registry-epoch` term in every key's contribution
            to `getSnapshot`, which would have moved every mounted
            boundary's snapshot on every `reg-sub` in the application).
            A first registration is TARGETED: it reaches the cells holding
            that query and nothing else. An unrelated one — which is what
            a boot, a lazy module load and every one of an HMR save's
            first-time ids look like — must leave a mounted boundary's
            snapshot exactly where it was."
    (rf/reg-sub (first q-live) (fn [db _] (:v db)))
    (let [seen  (volatile! nil)
          f     (make-frame! ::unrelated {:v 1})
          _     (rt/render-body f (reader q-live seen) {})
          entry (rt/last-reads)
          hits  (volatile! 0)
          release! (rt/commit-boundary! entry (fn [] (vswap! hits inc)))
          before   (rt/snapshot-of entry)
          held     (rt/cell-reaction [f q-live])]
      (is (= 1 @seen) "the control: a registered key, read and committed")
      (is (some? held) "and holding its reaction")
      (rf/reg-sub :firstreg/nobody-reads-this (fn [db _] (:v db)))
      (is (= before (rt/snapshot-of entry))
          "the snapshot did not move: nothing about an unrelated first
           registration is in this key's contribution")
      (is (zero? @hits) "and nothing notified the boundary")
      (is (identical? held (rt/cell-reaction [f q-live]))
          "and the cell still holds the SAME reaction — an unrelated
           registration is not a reason to rebuild an attachment")
      (release!))))

(deftest closing-the-first-registration-cost-no-hook-and-nothing-in-the-snapshot
  (testing "the same fences `disposed_cell_cljs_test` holds for the
            disposal half. What repairs a first registration is an event,
            not a term, so the shell is unchanged and the snapshot
            arithmetic is unchanged."
    (is (= 2 (count rt/shell-hook-ledger))
        "still two hooks — a registrar hook is not a React hook")
    (is (= [:use-context/frame :use-sync-external-store/subscription-epoch]
           rt/shell-hook-ledger)
        "and the same two, in the same order")
    (let [inv (rt/retained-inventory)]
      (is (= #{:use-ref :use-state :view-cell :candidate-ledger}
             (into #{} (map :token) (:absent inv)))
          "the enumerated absences are unchanged: no per-boundary object was
           added, and nothing is keyed by a render or an attempt"))))
