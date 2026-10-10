(ns re-frame.bench.fresco.arm1.first-registration-cljs-test
  "THE OTHER REGISTRY TRANSITION.

  `disposed_cell_cljs_test` closes the registry axis for a **replacement**
  — an id that already has a handler and gets another one — because that
  transition arrives at the arm as a disposal: the sub-cache evicts the
  query's entry, the reaction is disposed, and
  `re-frame.bench.fresco.arm1.runtime/invalidate-cell!` rides the event.

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
  boundary reading the key, and it never subscribes again. Unguarded, the
  recovery the substrate declines to cache would be cached anyway, in the
  arm's own cell, where nothing evicts it.

  Unguarded, the boundary reads `nil`, the first `reg-sub` for the query
  changes nothing, and no later write notifies it — **for the life of the
  mount**, on a query that is by then perfectly well registered. That is
  the shape a lazily loaded module hits. `disposed_cell_cljs_test`'s cell
  answers a RETIRED computation; this one answers a computation that
  never arrived. Both look alive — the boundary rendered, it painted,
  nothing errored, and it will never change again.

  The rows below are the direct witness. The transition has **two
  halves**, and they are repaired by two different mechanisms because
  they are two different situations.

  A boundary that already HOLDS a cell is repaired by the registration
  event: `first-registration!` scans the cells for the id and drops the
  reference, so the next read falls through to the cold probe and the
  rebuilt attachment notifies later writes. That is the mounted case, and
  the first two rows.

  A boundary inside the **render→commit gap** holds no cell, so that scan
  reaches nothing on its behalf. It is repaired by the `registry-epoch`
  term of `commit-basis`: a key with no cell contributes a
  LIVE basis reading, the cell the commit creates is stamped with the
  basis as it stands then, and a `reg-sub` between the two makes the two
  numbers differ — so React's own post-`subscribe` tear check schedules
  the re-render. This is not the registry term `disposed_cell_cljs_test`
  rules out: that one would sit in every key's *live* contribution to
  `getSnapshot`, move every mounted boundary in the application on every
  `reg-sub`, and buy each one a render that reads back through a dead
  reference. A term in the *basis* is read live by the staged branch
  alone — so it reaches exactly the keys that have the defect, and the
  extra render it buys reads back through a cell that is alive and
  correct.

  The bottom row is the bill, and it is what makes that distinction
  checkable rather than argued: a first registration of an id **no cell
  holds** must disturb a mounted boundary by nothing at all. Its
  assertions hold with the term in place, which is the cleanest available
  proof that the basis term is not the per-key live term."
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

;; This file's OWN queries. Every row here turns on a query being
;; UNREGISTERED when the boundary mounts, so these ids must be ones no
;; other suite registers — and the fixture's registrar snapshot/restore
;; is what keeps them unregistered at the top of each row.
(def ^:private q-first [:firstreg/pending])
(def ^:private q-held  [:firstreg/held])
(def ^:private q-gap   [:firstreg/gap])
(def ^:private q-live  [:firstreg/live])

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
        (rf.bench.fresco.arm1.runtime/render-body f (reader q-first seen) {})
        (let [first-read @seen
              entry      (rf.bench.fresco.arm1.runtime/last-reads)
              hits       (volatile! 0)
              release!   (rf.bench.fresco.arm1.runtime/commit-boundary! entry (fn [] (vswap! hits inc)))]
          (is (= [nil 1] [first-read (:cells (rf.bench.fresco.arm1.runtime/stats))])
              "the recovery contract: an unregistered read derefs to nil — and
               the COMMIT is what makes it a problem: the boundary now holds a
               cell for the key, so the read is a pure deref of whatever that
               cell caught, and what it caught is the recovery")

          ;; THE FIRST REGISTRATION. No previous handler, so no replacement
          ;; hook fires, no sub-cache entry is evicted, and no reaction is
          ;; disposed anywhere in the substrate.
          (rf/reg-sub (first q-first) (fn [db _] (:v db)))
          (rf.bench.fresco.arm1.runtime/render-body f (reader q-first seen) {})
          (let [next-read @seen]
            (settle!
              (fn []
                (let [before @hits]
                  (rf.frame/replace-app-db! f {:v 2})
                  (rf.bench.fresco.arm1.runtime/render-body f (reader q-first seen) {})
                  (is (= [1 true 2] [next-read (pos? (- @hits before)) @seen])
                      "1, not nil: the cell dropped the recovery, so the next
                       render falls through to `subscribe-once` and resolves
                       the handler registered NOW; and the durable attachment
                       is built against it, so a later write notifies —
                       without the repair the cell is deaf for the life of
                       the mount"))
                (release!)
                (done)))))))))

(deftest deliberately-a-cell-that-keeps-the-recovery-answers-nil-forever
  (testing "the failure the row above repairs, stated as its own assertion
            so the repair cannot quietly stop being needed. The recovery
            reaction is a constant nil — it is not wired to the frame, it
            is not in any cache, and no registration can ever reach it. A
            cell that keeps it answers nil for as long as the boundary
            lives."
    (let [f (make-frame! ::held {:v 1})]
      (rf.bench.fresco.arm1.runtime/render-body f (reader q-held (volatile! nil)) {})
      (let [entry    (rf.bench.fresco.arm1.runtime/last-reads)
            release! (rf.bench.fresco.arm1.runtime/commit-boundary! entry (fn []))
            ;; Reach past the repair and hold the object the cell caught.
            ;; This is exactly what the cell would keep if the first
            ;; registration were not an event.
            held     (rf.bench.fresco.arm1.runtime/cell-reaction [f q-held])]
        (rf/reg-sub (first q-held) (fn [db _] (:v db)))
        (is (= [nil nil] [@held (rf.bench.fresco.arm1.runtime/cell-reaction [f q-held])])
            "the held recovery answers nil where the live registration answers
             1, and will after every later write too, because it derives from
             nothing — which is why the cell drops the reference instead")
        (release!)))))

(deftest a-first-registration-in-the-render-commit-gap-moves-the-snapshot
  (testing "the OTHER window. The rows above are the
            mounted case — a boundary that already holds a cell. The
            render→commit gap is the case where it does not: the body has
            returned `nil`, and the registration lands before React runs
            the effect that acquires the edge. There is no cell for
            [[first-registration!]] to reach, so the repair those rows
            witness reaches nothing here.

            What reaches it is the `registry-epoch` term of
            `commit-basis`. A key with no cell contributes a LIVE basis
            reading to `getSnapshot`; the cell the commit creates is
            stamped with the basis as it stands THEN. A `reg-sub` between
            the two moves the term, so the two numbers differ, so React's
            post-`subscribe` re-check sees a tear and schedules the
            re-render — which reads back through a cell that is alive and
            holds the real handler. That is what distinguishes this from a
            per-key live term, where the extra render would read back
            through a dead reference.

            The correction is React's tear check, NOT a notification: the
            arm's own notify path runs off `flush!`, and a registration
            is not a value change on an acquired reaction. `hits` staying
            zero is that distinction on the board."
    (let [seen (volatile! :unread)
          f    (make-frame! ::gap {:v 1})]
      (rf.bench.fresco.arm1.runtime/render-body f (reader q-gap seen) {})
      (let [first-read @seen
            entry      (rf.bench.fresco.arm1.runtime/last-reads)
            at-render  (rf.bench.fresco.arm1.runtime/snapshot-of entry)
            epoch      (rf.bench.fresco.arm1.runtime/registry-epoch)
            hits       (volatile! 0)]
        ;; THE REGISTRATION, inside the gap: after the body returned and
        ;; before React's effect acquires the edge.
        (rf/reg-sub (first q-gap) (fn [db _] (:v db)))
        (let [moved?   (> (rf.bench.fresco.arm1.runtime/registry-epoch) epoch)
              release! (rf.bench.fresco.arm1.runtime/commit-boundary! entry (fn [] (vswap! hits inc)))
              tear?    (not= at-render (rf.bench.fresco.arm1.runtime/snapshot-of entry))
              held     @(rf.bench.fresco.arm1.runtime/cell-reaction [f q-gap])
              notified @hits]
          (rf.bench.fresco.arm1.runtime/render-body f (reader q-gap seen) {})
          (is (= {:first-read nil :epoch-moved true :tear true :hits 0 :held 1 :re-render 1}
                 {:first-read first-read :epoch-moved moved? :tear tear?
                  :hits notified :held held :re-render @seen})
              "the body ran against no registration; the arm counted the
               registration; the snapshot MOVED across the commit, so React's
               re-check sees a tear and schedules the re-render — the tear
               check and not a notification, since a `reg-sub` reaches
               `flush!` by no route; the cell the commit acquired holds the
               REAL handler; and the re-render reads 1, not nil, at once
               rather than at the next write")
          (release!))))))

;; ---------------------------------------------------------------------------
;; What closing the transition costs
;; ---------------------------------------------------------------------------

(deftest a-first-registration-of-an-id-no-cell-holds-disturbs-nothing
  (testing "the bill, and the tripwire against a `:registry-epoch` term in
            every key's contribution to `getSnapshot`, which would move
            every mounted boundary's snapshot on every `reg-sub` in the
            application.
            A first registration is TARGETED: it reaches the cells holding
            that query and nothing else. An unrelated one — which is what
            a boot, a lazy module load and every one of an HMR save's
            first-time ids look like — must leave a mounted boundary's
            snapshot exactly where it was."
    (rf/reg-sub (first q-live) (fn [db _] (:v db)))
    (let [seen  (volatile! nil)
          f     (make-frame! ::unrelated {:v 1})
          _     (rf.bench.fresco.arm1.runtime/render-body f (reader q-live seen) {})
          entry (rf.bench.fresco.arm1.runtime/last-reads)
          hits  (volatile! 0)
          release! (rf.bench.fresco.arm1.runtime/commit-boundary! entry (fn [] (vswap! hits inc)))
          before   (rf.bench.fresco.arm1.runtime/snapshot-of entry)
          held     (rf.bench.fresco.arm1.runtime/cell-reaction [f q-live])
          control  [@seen (some? held)]]
      (rf/reg-sub :firstreg/nobody-reads-this (fn [db _] (:v db)))
      (is (= [[1 true] before 0 true]
             [control
              (rf.bench.fresco.arm1.runtime/snapshot-of entry)
              @hits
              (identical? held (rf.bench.fresco.arm1.runtime/cell-reaction [f q-live]))])
          "the control is a registered key, read, committed and holding its
           reaction; the unrelated first registration then leaves its
           snapshot where it was, notifies nothing, and leaves the cell
           holding the SAME reaction — an unrelated registration is not a
           reason to rebuild an attachment")
      (release!))))

;; Neither half buys a React hook or a per-boundary object: a registrar hook
;; is not a React hook.
