(ns re-frame.hicasso.kernel-commit-owns-cljs-test
  "INVARIANT I5, AT THE SEAM — render probes, commit owns (rf2-hic-010).

  > Render is speculative: it probes reads, acquires no durable ownership,
  > and publishes no committed evidence. The selected commit reconciles
  > exactly its read and host set; disconnect releases it. Abandoned
  > renders leave no subscriptions and no diagnostic records; teardown is
  > exact and testable, with zero residue after quiescence.
  >
  > — `docs/design/hicasso/product/invariants.md`, I5

  This is the **node half** of the bead's witnesses, and it exists because
  of where the gates are. The five *real* React abandonment mechanisms the
  bead names — Suspense retry, StrictMode double-invoke, transition abort,
  error-then-retry, delayed commit — need a real concurrent root, so they
  live in [[re-frame.hicasso.kernel-commit-owns-dom-cljs-test]] and run on
  the browser lane. **That lane is not in the fast-PR spine.** This file is
  therefore the only thing standing over the commit-owns kernel on an
  ordinary PR, so it takes the properties that can be stated exactly
  without a DOM and states them where they will actually be run.

  ## The two doors it drives, and why neither is a simulation

  1. **`react-dom/server`.** A server render runs bodies for real — the
     shell's hooks, the read collection, the codec's element emission —
     and then React throws the whole tree away: it calls
     `getServerSnapshot` and never `subscribe`. That is a genuine
     never-committed render performed by React itself, not a stand-in for
     one.
  2. **The published seam.**
     [[re-frame.hicasso.impl.collector/render-body]] and
     [[re-frame.hicasso.impl.collector/commit-boundary!]] are the render
     and the commit React drives, exposed deliberately so the acquisition
     laws can be stated per-key and per-reader rather than inferred from a
     page. `commit-boundary!`'s own docstring is the warrant: it hands a
     caller *the same `subscribe` closure `useSyncExternalStore` would
     call* and *the same cleanup React would hold*.

     Driving the seam is how a witness says **which** key acquired
     **which** reader. It is not how a witness says React abandoned
     anything — that claim is the DOM file's, and this file does not make
     it.

  ## The observable, and why a value assertion would not do

  Every acquisition law here is asserted on **reader membership** —
  `inventory/cell-readers`, `inventory/residue` — and never on the value a
  body rendered. That is deliberate, and it is the lesson a sibling
  witness paid for on the controlled-input surface the same night: a value
  assertion stays green under a real leak, because a leaked subscription
  does not change what is painted. It changes what is **retained** and
  what will be **notified**. One extra reader slot on a cell is invisible
  to the markup and fatal to the invariant, so the reader slot is what is
  read.

  ## No clock, and no hand-rolled reaper delay

  Where a property is only true after the reapers run, the settle is
  [[re-frame.hicasso.impl.inventory/quiesced!]] — the runtime's own
  horizon — and never a `setTimeout` of this file's choosing. A copy of
  the horizon is what drifted in rf2-981nt, and
  `collector/entry-reap-horizon-ms` is explicitly a margin no caller may
  rely on. Nothing here reads it, waits a multiple of it, or assumes a
  reaper has run at any point the runtime has not said it has.

  ## The negative control is in the suite, not only in the report

  [[the-residue-census-can-answer-false]] performs, by hand, the exact
  mutation the bead's sabotage control describes — it leaks one
  abandoned-read registration — and asserts the census reports it. Without
  that row every zero above could be a zero the instrument is incapable of
  making non-zero, which is the failure mode this repo has been bitten by
  twice."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures async]]
            [re-frame.adapter.uix :as uix-adapter]
            [re-frame.core :as rf]
            [re-frame.hicasso :as h]
            [re-frame.hicasso.impl.codec :as codec]
            [re-frame.hicasso.impl.collector :as collector]
            [re-frame.hicasso.impl.inventory :as inventory]
            [re-frame.hicasso.impl.mount :as mount]
            [re-frame.test-support :as test-support]
            ["react-dom/server" :as react-dom-server]))

(def ^:private frame-id ::kernel-commit-owns)

(rf/reg-sub :kco/left  (fn [db _] (:left db)))
(rf/reg-sub :kco/right (fn [db _] (:right db)))
(rf/reg-sub :kco/flag  (fn [db _] (:flag db)))

(rf/reg-event :kco/seed      (fn [_ [_ db]] {:db db}))
(rf/reg-event :kco/bump-left (fn [{:keys [db]} _] {:db (update db :left inc)}))
(rf/reg-event :kco/raise     (fn [{:keys [db]} _] {:db (assoc db :flag true)}))

;; The UIx adapter rather than plain-atom, for the reason the package smoke
;; gives: plain-atom has no reactivity layer, so a subscription under it
;; never notifies and every commit assertion would pass vacuously by never
;; firing.
;;
;; `:async? true` selects the MAP form. It is not a preference: the rows
;; that settle against `inventory/quiesced!` are `(async done …)` tests,
;; and `cljs.test` hard-errors on a fn-form fixture for one — the fn-form
;; unwinds its ambient scope the instant it returns, which is before an
;; async body has resumed. `:ambient-frame nil` because this suite seats
;; its own top-level frame.
(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter       uix-adapter/adapter
     :ambient-frame nil
     :async?        true
     :init-fn       (fn [] (collector/reset-runtime!))}))

;; ---------------------------------------------------------------------------
;; Harness
;; ---------------------------------------------------------------------------

(defn- seeded!
  "A frame with a known db. `IS_REACT_ACT_ENVIRONMENT` is set outright
  because a server render is not inside React's `act` queue and the helper
  that carries this line in the prototype lives in the bench tree, which
  the freeze gate forbids this package from importing."
  []
  (set! (.-IS_REACT_ACT_ENVIRONMENT js/globalThis) false)
  (rf/make-frame {:id frame-id})
  (rf/with-frame frame-id (rf/dispatch-sync [:kco/seed {:left 1 :right 2 :flag false}]))
  frame-id)

(defn- k
  "A sub-key: the runtime keys cells by `[frame-kw query-v]`, because two
  frames are isolated contexts holding two different app-dbs."
  [query-v]
  [frame-id query-v])

(def ^:private nothing-owned
  "The four OWNERSHIP counters at rest. `:entries` is deliberately absent —
  a read-set entry is a CACHE minted during the render, so it is not
  ownership and the rows that care about it name it separately."
  {:cells 0 :cell-refs 0 :boundaries 0 :edges 0})

(defn- ownership
  "The census with the entry cache projected out."
  []
  (dissoc (inventory/residue) :entries))

(defn- probe!
  "Run ONE boundary body under the real generation fence — the same call
  [[re-frame.hicasso.impl.collector/shell]] makes between its two hooks —
  and return the read-set entry the render resolved. Commits nothing:
  `subscribe` is React's to call, and nothing here calls it."
  [body-fn]
  (collector/render-body frame-id body-fn {})
  (collector/last-reads))

;; ---------------------------------------------------------------------------
;; A real React render that never commits
;; ---------------------------------------------------------------------------

(h/defview left-line  [_] [:p.left (h/sub [:kco/left])])
(h/defview right-line [_] [:p.right (h/sub [:kco/right])])

(h/defview both-lines
  [_]
  [:div [left-line {}] [right-line {}]])

(defn- server-html
  [hiccup]
  (react-dom-server/renderToString
    (mount/provider frame-id (codec/root-element frame-id hiccup))))

(deftest a-server-render-runs-every-body-and-acquires-nothing-from-any-of-them
  (async done
    (seeded!)
    (collector/reset-body-runs!)
    (let [markup (server-html [both-lines {}])]

      (testing "the premise: React really did run all three bodies. Without
                this the zeros below would be the zeros of a render that
                never happened"
        (is (= 3 (collector/body-runs)))
        (is (re-find #"1" markup))
        (is (re-find #"2" markup)))

      (testing "and it committed none of them, so the runtime owns nothing:
                no cell was built, no reference taken, no edge recorded"
        (is (= nothing-owned (ownership))))

      (testing "the individually-named keys confirm it per key, which a
                summed census could hide"
        (is (= [] (inventory/cell-readers (k [:kco/left]))))
        (is (= [] (inventory/cell-readers (k [:kco/right])))))

      (testing "the read-set entries ARE there — a render-phase cache is the
                one thing a probing render leaves, and calling it out is
                what keeps the zero above honest"
        (is (pos? (:entries (inventory/residue)))))

      (.then (inventory/quiesced!)
             (fn [_]
               (testing "and the cache evaporates at the runtime's own
                         horizon: zero residue after quiescence, which is
                         I5's last clause"
                 (is (= {:cells 0 :cell-refs 0 :boundaries 0 :edges 0 :entries 0}
                        (inventory/residue))))
               (done))))))

;; ---------------------------------------------------------------------------
;; The commit acquires EXACTLY the read set
;; ---------------------------------------------------------------------------

(deftest the-commit-acquires-exactly-the-current-read-set
  (async done
    (seeded!)
    (let [entry     (probe! (fn [_] [:p (h/sub [:kco/left])]))
          !notified (atom 0)]

      (testing "the render resolved the read set without owning it"
        (is (= #{(k [:kco/left])} (collector/reads-of entry)))
        (is (= nothing-owned (ownership))))

      (let [cleanup (collector/commit-boundary! entry (fn [] (swap! !notified inc)))]

        (testing "the commit takes exactly one cell, one reference, one edge"
          (is (= {:cells 1 :cell-refs 1 :boundaries 1 :edges 1} (ownership)))
          (is (= 1 (count (inventory/cell-readers (k [:kco/left]))))))

        (testing "and NOTHING for a key this body did not read — the
                  acquisition is the read set, not the registrar"
          (is (= [] (inventory/cell-readers (k [:kco/right]))))
          (is (nil? (inventory/cell-reaction (k [:kco/right])))))

        (testing "the acquired boundary is a live reader: a write to its key
                  notifies it exactly once"
          (rf/with-frame frame-id (rf/dispatch-sync [:kco/bump-left]))
          (is (= 1 @!notified)))

        (testing "and a write to a key it does NOT read notifies it not at all
                  — the dirty set is the dirty CELLS' readers, so an unread
                  key contributes no phantom reader"
          (rf/with-frame frame-id (rf/dispatch-sync [:kco/raise]))
          (is (= 1 @!notified)))

        (cleanup)

        (testing "disconnect releases it: the membership goes immediately,
                  and the cell survives only until its reaper"
          (is (= {:cells 1 :cell-refs 0 :boundaries 0 :edges 0} (ownership)))))

      (.then (inventory/quiesced!)
             (fn [_]
               (testing "and at the runtime's own horizon — which is strictly
                         past every reaper armed before it, the cell reapers
                         included — teardown is exact: total zero"
                 (is (= {:cells 0 :cell-refs 0 :boundaries 0 :edges 0 :entries 0}
                        (inventory/residue))))
               (done))))))

(deftest a-branch-not-taken-contributes-no-edge
  (seeded!)
  ;; The ambient collector's whole claim: the edge is recorded WHERE THE
  ;; READ HAPPENS, so a boundary's edge set is a function of the control
  ;; flow the render actually took.
  (let [body    (fn [_] [:p (when (h/sub [:kco/flag]) (h/sub [:kco/right]))])
        cold    (probe! body)
        cleanup (collector/commit-boundary! cold (fn []))]

    (testing "flag is false, so the guarded read never ran and its key is
              not in the set"
      (is (= #{(k [:kco/flag])} (collector/reads-of cold)))
      (is (= {:cells 1 :cell-refs 1 :boundaries 1 :edges 1} (ownership)))
      (is (= [] (inventory/cell-readers (k [:kco/right])))))

    (cleanup)
    (rf/with-frame frame-id (rf/dispatch-sync [:kco/raise]))

    (let [warm     (probe! body)
          cleanup2 (collector/commit-boundary! warm (fn []))]
      (testing "flag is true, so the same body reads two keys and the commit
                acquires both — the edge set followed the branch"
        (is (= #{(k [:kco/flag]) (k [:kco/right])} (collector/reads-of warm)))
        (is (= 1 (count (inventory/cell-readers (k [:kco/right]))))))
      (cleanup2))))

;; ---------------------------------------------------------------------------
;; Rollback — a body that throws after its reads
;; ---------------------------------------------------------------------------

(deftest a-body-that-throws-after-its-reads-leaves-nothing-and-contaminates-nothing
  (seeded!)
  (let [before (ownership)]

    (is (thrown-with-msg? js/Error #"planted"
          (collector/render-body
            frame-id
            (fn [_]
              (h/sub [:kco/left])
              (h/sub [:kco/right])
              (throw (js/Error. "planted")))
            {}))
        "the body must actually throw — otherwise this row proves nothing")

    (testing "the reads it took before it threw acquired nothing, because a
              render-phase read acquires nothing to roll back"
      (is (= before (ownership)))
      (is (= nothing-owned (ownership))))

    ;; The half that would be a SILENT wrong answer rather than a leak. The
    ;; scratch is one module-level array reset by overwrite at the top of
    ;; every body; if a throwing run's reads survived into the next body,
    ;; the next boundary would commit edges it never read — correct on
    ;; screen, wrong forever after.
    (let [entry   (probe! (fn [_] [:p (h/sub [:kco/flag])]))
          cleanup (collector/commit-boundary! entry (fn []))]

      (testing "the next body's read set is ITS OWN, not the throwing run's
                concatenated with it"
        (is (= #{(k [:kco/flag])} (collector/reads-of entry)))
        (is (= {:cells 1 :cell-refs 1 :boundaries 1 :edges 1} (ownership))))

      (testing "named per key, because `:cells 1` alone could be the right
                count of the wrong keys"
        (is (= 1 (count (inventory/cell-readers (k [:kco/flag])))))
        (is (= [] (inventory/cell-readers (k [:kco/left]))))
        (is (= [] (inventory/cell-readers (k [:kco/right])))))

      (cleanup))))

;; ---------------------------------------------------------------------------
;; A re-render before the commit — the discarded attempt is discarded
;; ---------------------------------------------------------------------------

(deftest a-re-render-before-the-commit-owns-the-second-read-set-and-only-that
  (seeded!)
  (probe! (fn [_] [:p (h/sub [:kco/left]) (h/sub [:kco/right])]))

  (testing "the first attempt owns nothing, so there is nothing for the
            second to undo"
    (is (= nothing-owned (ownership))))

  (let [second-entry (probe! (fn [_] [:p (h/sub [:kco/flag])]))
        cleanup      (collector/commit-boundary! second-entry (fn []))]

    (testing "React selected the second attempt, so the second attempt's set
              is what got acquired — in full and in isolation"
      (is (= #{(k [:kco/flag])} (collector/reads-of second-entry)))
      (is (= {:cells 1 :cell-refs 1 :boundaries 1 :edges 1} (ownership)))
      (is (= [] (inventory/cell-readers (k [:kco/left]))))
      (is (= [] (inventory/cell-readers (k [:kco/right])))))

    (cleanup)))

;; ---------------------------------------------------------------------------
;; Two fibers on one read set — StrictMode's SHAPE, at the seam
;; ---------------------------------------------------------------------------

(deftest two-subscribes-on-one-entry-are-two-readers-and-two-cleanups-leave-none
  (async done
    (seeded!)
    ;; StrictMode's double-invoke and its double-mount produce exactly this
    ;; at the seam: one entry, two `subscribe` calls, two cleanups. The
    ;; REAL StrictMode witness is the DOM file's; this row states what the
    ;; seam owes it.
    (let [entry (probe! (fn [_] [:p (h/sub [:kco/left])]))
          c1    (collector/commit-boundary! entry (fn []))
          c2    (collector/commit-boundary! entry (fn []))]

      (testing "two boundaries, two memberships, still ONE cell — the cell
                is shared per unique key and the reader list is the count"
        (is (= {:cells 1 :cell-refs 2 :boundaries 2 :edges 2} (ownership)))
        (is (= 2 (count (inventory/cell-readers (k [:kco/left]))))))

      (testing "and the two registrations are distinct objects: the
                registration IS the boundary id, so a second subscribe
                cannot be mistaken for the first"
        (let [[a b] (inventory/cell-readers (k [:kco/left]))]
          (is (not (identical? a b)))))

      (c1)
      (testing "one cleanup removes exactly one membership"
        (is (= {:cells 1 :cell-refs 1 :boundaries 1 :edges 1} (ownership))))

      (c2)
      (.then (inventory/quiesced!)
             (fn [_]
               (testing "and the pair leaves zero residue"
                 (is (= {:cells 0 :cell-refs 0 :boundaries 0 :edges 0 :entries 0}
                        (inventory/residue))))
               (done))))))

;; ---------------------------------------------------------------------------
;; The render→commit gap — delayed commit, at the seam
;; ---------------------------------------------------------------------------

(deftest a-quiet-render-to-commit-gap-moves-the-snapshot-by-nothing
  (seeded!)
  ;; The control for the row below, and the reason that row means anything:
  ;; if the number moved on every commit, a difference would prove nothing.
  (let [entry     (probe! (fn [_] [:p (h/sub [:kco/left])]))
        at-render (collector/snapshot-of entry)
        cleanup   (collector/commit-boundary! entry (fn []))
        at-commit (collector/snapshot-of entry)]
    (testing "nothing landed in the gap, so React's post-subscribe re-read
              sees the number the fiber captured at render and schedules no
              correcting re-render"
      (is (= at-render at-commit)))
    (cleanup)))

(deftest a-commit-landing-in-the-render-to-commit-gap-moves-the-snapshot
  (seeded!)
  ;; A STAGED key — nothing holds `:kco/left`, so it has no cell, no watch
  ;; and no epoch, and the flush generation is structurally blind to it.
  ;; `commit-basis` is what is not blind, and this is the row that says so.
  (let [entry     (probe! (fn [_] [:p (h/sub [:kco/left])]))
        at-render (collector/snapshot-of entry)]

    (testing "the key really is staged: no cell holds it at render time"
      (is (nil? (get @collector/!cells (k [:kco/left])))))

    ;; The write lands AFTER the body returned and BEFORE React acquires.
    (rf/with-frame frame-id (rf/dispatch-sync [:kco/bump-left]))

    (let [cleanup   (collector/commit-boundary! entry (fn []))
          at-commit (collector/snapshot-of entry)]
      (testing "so the number React re-reads after `subscribe` differs from
                the one the fiber captured at render, and the boundary is
                corrected instead of painting a value that moved under it"
        (is (not= at-render at-commit)))
      (cleanup))))

;; ---------------------------------------------------------------------------
;; The negative control — the census can answer false
;; ---------------------------------------------------------------------------

(deftest the-residue-census-can-answer-false
  ;; THE SABOTAGE CONTROL, performed from the test rather than from the
  ;; source. Every zero in this file is only worth what this row is worth:
  ;; a census that cannot be made non-zero is a gate that cannot go red,
  ;; and the assertions above would then be proving the instrument's
  ;; silence rather than the runtime's correctness.
  ;;
  ;; What it leaks is exactly one abandoned-read registration — the entry
  ;; of a render that was never selected, committed by hand and its cleanup
  ;; discarded. That is the mutation the bead's sabotage clause describes,
  ;; and it is what a render-phase acquisition would produce on every
  ;; abandoned attempt.
  (async done
    (seeded!)
    (let [entry (probe! (fn [_] [:p (h/sub [:kco/left])]))]

      (testing "the abandoned render, as every row above finds it"
        (is (= nothing-owned (ownership))))

      (collector/commit-boundary! entry (fn []))

      (testing "one leaked registration, and the census says so — on the
                summed counters"
        (is (not= nothing-owned (ownership)))
        (is (= {:cells 1 :cell-refs 1 :boundaries 1 :edges 1} (ownership))))

      (testing "and on the per-key reader list, which is the observable the
                acquisition rows actually assert on"
        (is (= 1 (count (inventory/cell-readers (k [:kco/left]))))))

      (.then (inventory/quiesced!)
             (fn [_]
               (testing "quiescence does NOT launder it: the reapers drop
                         what nothing holds, and this is held"
                 (is (= 1 (count (inventory/cell-readers (k [:kco/left])))))
                 (is (not= 0 (:cell-refs (inventory/residue)))))
               (done))))))
