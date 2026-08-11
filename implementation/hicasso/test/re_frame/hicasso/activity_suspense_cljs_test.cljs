(ns re-frame.hicasso.activity-suspense-cljs-test
  "ACTIVITY HIDE/REVEAL, AT THE SEAM (rf2-hic-014).

  > - hide releases committed subscription ownership without destroying
  >   re-frame state;
  > - hidden work does not publish speculative read sets;
  > - reveal reacquires the current read set and corrects before visible
  >   paint;
  > - conditional reads changed while hidden do not resurrect stale
  >   membership;
  > - intents retained before hide obey the documented callback/frame-
  >   incarnation rule;
  > - Xray distinguishes hidden-retained, visible-connected, and
  >   unmounted rather than collapsing them.
  >
  > — `docs/design/hicasso/product/lanes/react-compatibility-notes.md`,
  >   *Activity lifecycle witness*

  This is the **node half**, and it exists because of where the gates
  are. React 19.2's `<Activity>` needs a real concurrent root, so the
  matrix driven by React itself lives in
  [[re-frame.hicasso.activity-suspense-dom-cljs-test]] and runs on the
  browser lane — which is not in the fast-PR spine. This file is
  therefore the only thing standing over the hide/reveal kernel on an
  ordinary PR, so it takes the properties that can be stated exactly
  without a DOM and states them where they will actually be run.

  ## What an Activity hide IS, at this seam

  React's contract for a hidden `<Activity>` is that it *cleans up
  effects and active subscriptions* while retaining the subtree's UI
  state, and *recreates* them on reveal.
  `useSyncExternalStore`'s subscription is one of those effects, so a
  hide runs the cleanup
  [[re-frame.hicasso.impl.collector/commit-boundary!]] hands back and a
  reveal calls that entry's `subscribe` again. Both are exposed
  deliberately, and `commit-boundary!`'s own docstring is the warrant: it
  hands a caller *the same `subscribe` closure `useSyncExternalStore`
  would call* and *the same cleanup React would hold*.

  So the transitions below are not a simulation of the Activity
  lifecycle. They are the lifecycle's two calls, made in the order React
  makes them, with the ownership tables read between each one — which is
  how a witness says **which** key is owned by **which** registration
  rather than inferring it from a page.

  Driving the seam is emphatically not how a witness says React hides
  anything. That claim is the DOM file's, and this file does not make it.

  ## Nor is a Suspense fallback this seam, and section 6 used to say it was

  The bead pairs Activity with Suspense because the two look like one
  Offscreen mechanism — the primary tree hidden with `display: none`, its
  host nodes retained. React 19.2 does not treat them alike: hiding for a
  **fallback** leaves the primary tree's PASSIVE effects mounted, so the
  `useSyncExternalStore` subscription survives the suspension and the
  retry's registration is `identical?` to the pre-suspension one. That was
  measured, on the browser lane, by
  [[re-frame.hicasso.activity-suspense-dom-cljs-test/a-post-commit-suspension-retains-the-subscription-and-the-retry-leaves-exact-ownership]]
  — on the run that row failed, having been written expecting the release
  the Activity rows assert.

  So **no row in this file speaks for Suspense**. Section 6 drives the
  detach/reacquire seam four times and once called itself a suspend/retry
  witness, which was false evidence against the DOM measurement rather
  than merely loose wording (merged-PR audit #7792): a reader taking it at
  its name would have believed a retained subscription was released and
  rebuilt four times over. The algebra was never the false part; the
  mechanism it was claimed for was.

  ## The observable is IDENTITY, never a count

  Every ownership assertion here names the surviving registration by
  `identical?`, not by `count`. `rf2-hic-011`'s ledger records why: a
  runtime that **leaks the predecessor and fails to acquire for the
  successor** still counts one. The two failures are opposite and the
  count is blind to both, so the count is asserted beside the identity
  and never instead of it.

  Nothing here asserts on rendered markup, for the reason
  `reincarnation_routing_cljs_test` states in full: this arm's read path
  is address-directed, so a boundary re-rendered after a botched reveal
  paints the correct value out of the wrong ownership. A value-level
  assertion is green in both directions.

  ## The negative controls are in the suite, not only in the report

  Two rows perform, by hand, the exact mutations that would make the
  matrix vacuous —
  [[NEGATIVE-CONTROL-skipping-the-release-on-hide-resurrects-the-stale-membership]]
  is the bead's own named sabotage (*skipping release-on-hide must turn
  the stale-membership witness red*), and
  [[NEGATIVE-CONTROL-a-reveal-that-reuses-the-pre-hide-read-set-reacquires-the-wrong-key]]
  is the reveal-side twin. Without them every zero above could be a zero
  the instrument is incapable of making non-zero, which is the failure
  mode this repo has been bitten by twice.

  ## No clock

  Where a property is only true after the reapers run, the settle is
  [[re-frame.hicasso.impl.inventory/quiesced!]] — the runtime's own
  horizon — and never a `setTimeout` of this file's choosing."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures async]]
            [clojure.set :as set]
            [re-frame.adapter.uix :as uix-adapter]
            [re-frame.core :as rf]
            [re-frame.error-emit :as error-emit]
            [re-frame.frame :as frame]
            [re-frame.hicasso :as h]
            [re-frame.hicasso.impl.collector :as collector]
            [re-frame.hicasso.impl.frames :as frames]
            [re-frame.hicasso.impl.inventory :as inventory]
            [re-frame.test-support :as test-support]))

(def ^:private frame-id ::activity-suspense)

;; Registered above `use-fixtures`, as this package's other suites are and
;; for the same reason: the reset fixture captures its source-store
;; baseline when the `use-fixtures` form is evaluated.
;;
;; `:acs/which` selects between `:acs/a` and `:acs/b`, so a body reading it
;; has a read set that MOVES — which is the whole of the conditional-read
;; row. `:acs/mark` writes a distinctive leaf, so a write that reached the
;; wrong incarnation is unmistakable in that incarnation's app-db rather
;; than merely absent from the right one.
(rf/reg-sub :acs/which (fn [db _] (:which db)))
(rf/reg-sub :acs/a     (fn [db _] (:a db)))
(rf/reg-sub :acs/b     (fn [db _] (:b db)))

(rf/reg-event :acs/seed  (fn [_ [_ db]] {:db db}))
(rf/reg-event :acs/flip  (fn [{:keys [db]} [_ which]] {:db (assoc db :which which)}))
(rf/reg-event :acs/bump  (fn [{:keys [db]} [_ k]] {:db (update db k inc)}))
(rf/reg-event :acs/mark  (fn [{:keys [db]} [_ tag]] {:db (assoc db :marked tag)}))

;; The UIx adapter rather than plain-atom, for the reason the package smoke
;; gives: plain-atom has no reactivity layer, so a subscription under it
;; never notifies and every commit assertion would pass vacuously by never
;; firing.
;;
;; `:async? true` selects the MAP form, which the rows settling against
;; `inventory/quiesced!` require — `cljs.test` hard-errors on a fn-form
;; fixture for an `(async done …)` test, because the fn form unwinds its
;; ambient scope the instant it returns. `:ambient-frame nil` because this
;; suite seats its own top-level frame.
(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter       uix-adapter/adapter
     :ambient-frame nil
     :async?        true
     :init-fn       (fn []
                      (collector/reset-runtime!)
                      (error-emit/clear-error-listeners!))}))

;; ---------------------------------------------------------------------------
;; The exercised population — a MEASUREMENT, not a claim
;; ---------------------------------------------------------------------------

(def ^:private declared-population
  "The Activity transitions this file undertakes to exercise.
  [[the-declared-population-was-actually-exercised]] asserts that every
  one of them was reached at runtime, so the roster cannot drift into a
  list of things the suite used to do."
  #{:activity/hide-releases
    :activity/hide-inside-a-deferred-notification-window
    :activity/hidden-render-publishes-nothing
    :activity/reveal-reacquires
    :activity/conditional-read-moved-while-hidden
    :activity/retained-intent-across-a-reincarnation
    :activity/repeated-hide-reveal-cycles
    :lifecycle/three-states-distinguished})

(defonce ^:private !exercised (atom #{}))

(defn- exercised! [transition] (swap! !exercised conj transition) nil)

;; ---------------------------------------------------------------------------
;; Harness
;; ---------------------------------------------------------------------------

(defn- seeded!
  "Seat a first incarnation under the fixed public id, with a known db.

  Any live predecessor is destroyed and the arm's frame memo emptied
  first: `commit-basis` is a sum of counters and a leftover frame would
  carry its installs into the next row's arithmetic. React's `act` queue
  is not the browser's scheduler and none of this runs inside one — set
  outright, as the package smoke does, rather than importing the bench
  tree's helper, which the freeze gate forbids this package to require."
  []
  (set! (.-IS_REACT_ACT_ENVIRONMENT js/globalThis) false)
  (when (frame/frame-incarnation-token frame-id) (rf/destroy-frame! frame-id))
  (frames/forget-frame-ops! frame-id)
  (rf/make-frame {:id frame-id})
  (rf/with-frame frame-id
    (rf/dispatch-sync [:acs/seed {:which :a :a 1 :b 100}]))
  frame-id)

(defn- k
  "A sub-key: the runtime keys cells by `[frame-kw query-v]`, because two
  frames are isolated contexts holding two different app-dbs."
  [query-v]
  [frame-id query-v])

(defn- ownership
  "The census with the entry cache projected out. A read-set entry is a
  render-phase CACHE, not ownership, so the rows that care about it name
  it separately."
  []
  (dissoc (inventory/residue) :entries))

(defn- readers
  "The registrations currently reading `query-v`, as a snapshot vector."
  [query-v]
  (inventory/cell-readers (k query-v)))

(defn- reader-count
  "How many registrations read `query-v`.

  **Assertions here take the COUNT and never the vector**, and that is a
  legibility repair measured on this file's own sabotage run rather than
  a preference. A registration holds the cells it acquired and each of
  those holds the registration back on its reader list, so the object
  graph is cyclic: `cljs.test`'s failure printer walks it and dies, and
  every `(= [] (readers …))` red arrived as
  `RangeError: Maximum call stack size exceeded`, naming nothing. A
  witness whose red output is a stack overflow is not a witness.

  Every positive identity claim below is spelled
  `(is (true? (identical? a b)))` rather than `(is (identical? a b))` for
  exactly the same reason: `cljs.test` reports a failing predicate's
  EVALUATED ARGUMENTS, so the first form reports `(not (true? false))`
  and the second reports the cyclic graph. A helper cannot fix that —
  `(is (same? a b))` would report the two registrations again — so the
  wrapping is written out at each site."
  [query-v]
  (count (readers query-v)))

(defn- sole-reader
  "The one registration reading `query-v`, or nil when that is not the
  count. Answering nil rather than throwing keeps a failing row's message
  about the identity it expected instead of about this helper."
  [query-v]
  (let [rs (readers query-v)]
    (when (= 1 (count rs)) (first rs))))

(defn- app-db [] (rf/app-db-value frame-id))

(defn- entry-refs
  "How many committed boundaries hold `entry`.

  Read with `unchecked-get` and the same string key the runtime writes
  (`entry-for`'s `#js {\"refs\" 0}`), so no new reader has to be exported
  from the collector for a witness to count claims on an entry — and so
  the read survives `:advanced` renaming exactly as the runtime's own
  writes do."
  [entry]
  (unchecked-get entry "refs"))

;; ---------------------------------------------------------------------------
;; The bodies
;; ---------------------------------------------------------------------------
;;
;; Plain functions rather than `h/defview` boundaries, because
;; `render-body` is the seam and a body-fn is what it takes. The views the
;; DOM file mounts are `defview`s over these same reads.

(defn- panel-body
  "One unconditional read."
  [_]
  [:p (str "a=" (h/sub [:acs/a]))])

(defn- conditional-body
  "A read set that MOVES. The selector is itself a read, so the boundary
  is notified when the selection changes — which is what makes the
  hidden-window flip below reach it at all — and the selected key is read
  through the ambient collector, so a branch not taken contributes no
  edge."
  [_]
  (let [which (h/sub [:acs/which])]
    [:p (str (name which) "="
             (if (= :a which) (h/sub [:acs/a]) (h/sub [:acs/b])))]))

(defn- render!
  "Run ONE body under the real generation fence — the same call
  [[re-frame.hicasso.impl.collector/shell]] makes between its two hooks —
  and answer the read-set entry the render resolved. Commits nothing:
  `subscribe` is React's to call, and nothing here calls it."
  [body-fn]
  (collector/render-body frame-id body-fn {})
  (collector/last-reads))

(defn- commit!
  "React's commit: call the entry's own `subscribe` and keep the cleanup.
  Answers `{:cleanup … :notified …}` where `:notified` counts the
  `onStoreChange` calls the registration has received."
  [entry]
  (let [!notified (atom 0)]
    {:cleanup   (collector/commit-boundary! entry (fn [] (swap! !notified inc)))
     :notified  !notified}))

(defn- hide!
  "React's hide: run the cleanup `useSyncExternalStore` holds. This is the
  whole of what a hidden `<Activity>` does to a subscription."
  [committed]
  ((:cleanup committed))
  nil)

;; ---------------------------------------------------------------------------
;; 1. Hide releases committed ownership, and leaves re-frame state standing
;; ---------------------------------------------------------------------------

(deftest hide-releases-committed-ownership-and-leaves-re-frame-state-standing
  (async done
    (seeded!)
    (let [entry     (render! panel-body)
          committed (commit! entry)
          visible   (sole-reader [:acs/a])
          token     (frame/frame-incarnation-token frame-id)]

      (testing "VISIBLE-CONNECTED — the commit acquired exactly the read
                set, and the registration holding it is the one this
                commit minted"
        (is (= #{(k [:acs/a])} (collector/reads-of entry)))
        (is (some? visible))
        (is (= {:cells 1 :cell-refs 1 :boundaries 1 :edges 1} (ownership)))
        (is (= #{(k [:acs/a])} (inventory/boundary-reads visible))
            "and its forward edge is the entry's own key set"))

      (testing "it is LIVE, not merely present — a write to its key
                notifies it exactly once"
        (rf/with-frame frame-id (rf/dispatch-sync [:acs/bump :a]))
        (is (= 1 @(:notified committed))))

      (hide! committed)

      (testing "HIDE releases the committed ownership: the membership goes
                the instant the cleanup runs, and the key is read by
                nobody. The cell survives only until its reaper, which is
                why `:cells` is 1 here and 0 after quiescence"
        (is (zero? (reader-count [:acs/a])))
        (is (= {:cells 1 :cell-refs 0 :boundaries 0 :edges 0} (ownership))))

      (testing "and the released registration is DEAF, which is the half a
                census cannot see: a write after the hide reaches it not at
                all"
        (rf/with-frame frame-id (rf/dispatch-sync [:acs/bump :a]))
        (is (= 1 @(:notified committed))
            "still 1 — the notifier was cleared by the cleanup, so a hidden
             subtree cannot be re-rendered by a write it no longer reads"))

      (testing "WITHOUT DESTROYING RE-FRAME STATE. The frame is the same
                incarnation, its app-db is intact, and the write above
                landed in it — a hide releases this arm's ownership and
                touches nothing the substrate owns"
        (is (true? (identical? token (frame/frame-incarnation-token frame-id)))
            "same incarnation — a hide is not a teardown")
        (is (= 3 (:a (app-db)))
            "seeded 1, bumped twice, and the second bump landed AFTER the
             hide: the state is live, not merely retained")
        (is (= :a (:which (app-db)))))

      (exercised! :activity/hide-releases)

      (.then (inventory/quiesced!)
             (fn [_]
               (testing "and at the runtime's own horizon the hidden
                         boundary's residue is exactly zero — a hide that
                         retained a cell would show here"
                 (is (= {:cells 0 :cell-refs 0 :boundaries 0 :edges 0 :entries 0}
                        (inventory/residue))))
               (done))))))

;; ---------------------------------------------------------------------------
;; 1b. A hide landing inside a DEFERRED notification window
;; ---------------------------------------------------------------------------

(defn- writing-body
  "A body that writes to `:acs/a` on its first run and on no later one.

  A write taken while a body is running is the one case
  [[re-frame.hicasso.impl.collector/flush!]] defers: an `onStoreChange`
  fired from inside somebody's render is a render-phase update on another
  component, which React rejects, so the readers are collected NOW and
  notified a macrotask later. That gap is a window a hide can land in,
  and an Activity hide is exactly the thing that lands in it — React
  hides a subtree while its siblings are still rendering.

  The flag is what keeps the generation fence terminating: the fence
  re-runs a body that observed a new commit, and a body that writes on
  every run is the exhaustion case `render-body` refuses outright."
  [!wrote?]
  (fn writer [_]
    (when-not @!wrote?
      (reset! !wrote? true)
      (rf/with-frame frame-id (rf/dispatch-sync [:acs/bump :a])))
    [:p "writer"]))

(deftest a-hide-landing-inside-a-deferred-notification-window-is-not-notified
  ;; **This row is the only place `unsubscribe`'s notifier clear is
  ;; load-bearing, and finding that out is what it is for.** Measured:
  ;; deleting `(set! (.-notify reg) nil)` reds nothing else in this file,
  ;; and deleting the membership release reds nothing that depends on
  ;; deafness — because on the synchronous path each mechanism alone is
  ;; sufficient. `flush!` reads the reader lists BEFORE the deferral, so
  ;; on this path the release has already happened and cannot help; the
  ;; nil notifier is the whole of the guard.
  (async done
    (seeded!)
    (let [visible (commit! (render! panel-body))
          !wrote? (atom false)]

      ;; A second boundary renders and writes from inside its own body, so
      ;; the notification `visible` has earned is DEFERRED rather than
      ;; delivered inline.
      (render! (writing-body !wrote?))

      (testing "the premise: the write landed inside a render, so the
                notification is owed and not yet delivered. An inline
                notification here would defeat the row by leaving no window
                for the hide to land in"
        (is (true? @!wrote?))
        (is (zero? @(:notified visible))))

      ;; The hide lands in the window — after the readers were collected
      ;; and before they are notified.
      (hide! visible)

      (exercised! :activity/hide-inside-a-deferred-notification-window)

      (.then (inventory/quiesced!)
             (fn [_]
               (testing "and the deferred notification is not delivered to
                         it. React has torn this subscription down; calling
                         its `onStoreChange` now is a store notification to
                         a boundary that no longer exists, which React
                         answers with a warning and a re-render of nothing"
                 (is (zero? @(:notified visible))))
               (done))))))

;; ---------------------------------------------------------------------------
;; 2. Hidden work publishes no read sets
;; ---------------------------------------------------------------------------

(deftest a-hidden-render-publishes-no-read-set
  (async done
    (seeded!)
    (let [committed (commit! (render! panel-body))]
      (hide! committed)

      (let [before (collector/body-runs)
            ;; React renders hidden children at lower priority. Three runs,
            ;; because a leak of one membership per hidden render is
            ;; invisible in a single one — the shape `rf2-hic-011` names.
            entries (doall (repeatedly 3 #(render! conditional-body)))
            delta   (- (collector/body-runs) before)]

        (testing "the premise: the bodies genuinely RAN. Without this the
                  zeros below are the zeros of a render that never
                  happened, which is the cheapest possible false green"
          (is (= 3 delta))
          (is (every? #(= #{(k [:acs/which]) (k [:acs/a])} (collector/reads-of %))
                      entries)
              "and each one resolved a real, non-empty read set"))

        (testing "and published none of it. A hidden render is a render:
                  it probes reads, acquires no durable ownership, and
                  installs no reverse edge — three of them, on two keys,
                  leave every reader list empty"
          (is (zero? (reader-count [:acs/which])))
          (is (zero? (reader-count [:acs/a])))
          (is (zero? (reader-count [:acs/b])))
          (is (= 0 (:cell-refs (ownership))))
          (is (= 0 (:boundaries (ownership))))
          (is (= 0 (:edges (ownership)))))

        (testing "the entries ARE minted — a render-phase cache is the one
                  thing a probing render leaves, and calling it out is what
                  keeps the zeros above honest — but not one of them is
                  CLAIMED"
          (is (pos? (:entries (inventory/residue))))
          (is (every? #(zero? (entry-refs %)) entries)
              "zero refs on every hidden render's entry: React never called
               `subscribe`, so nothing above counted a claim that was
               merely cached")))

      (exercised! :activity/hidden-render-publishes-nothing)

      (.then (inventory/quiesced!)
             (fn [_]
               (testing "and the speculative cache evaporates at the
                         runtime's own horizon"
                 (is (= {:cells 0 :cell-refs 0 :boundaries 0 :edges 0 :entries 0}
                        (inventory/residue))))
               (done))))))

;; ---------------------------------------------------------------------------
;; 3. Reveal reacquires — and the survivor is the NEW registration
;; ---------------------------------------------------------------------------

(deftest reveal-reacquires-and-the-surviving-registration-is-the-reveals-own
  (async done
    (seeded!)
    (let [before-hide (commit! (render! panel-body))
          pre-hide    (sole-reader [:acs/a])]

      ;; One write while it is VISIBLE, so its notifier has a non-zero
      ;; reading to stay at. A deafness assertion against a counter that
      ;; was never going to move is not a deafness assertion.
      (rf/with-frame frame-id (rf/dispatch-sync [:acs/bump :a]))
      (is (= 1 @(:notified before-hide))
          "the premise: the pre-hide registration was live and notified")

      (hide! before-hide)

      (let [after-reveal (commit! (render! panel-body))
            revealed     (sole-reader [:acs/a])]

        (testing "REVEAL reacquires: exactly one reader on exactly the key
                  the current render read"
          (is (some? revealed))
          (is (= {:cells 1 :cell-refs 1 :boundaries 1 :edges 1} (ownership))))

        (testing "and the survivor is the REVEAL's registration, not the
                  pre-hide one. This is the assertion a count cannot make:
                  a runtime that leaked the predecessor and failed to
                  acquire for the successor also answers 1 here"
          (is (not (identical? pre-hide revealed))
              "the reveal minted a fresh registration — a subscription
               rebuilt is a new boundary id by construction")
          (is (not (some #(identical? pre-hide %) (readers [:acs/a])))
              "and the pre-hide registration is nowhere in the reader list"))

        (testing "the pre-hide registration stays deaf and the revealed one
                  is live — one write, and it reaches exactly one of them"
          (rf/with-frame frame-id (rf/dispatch-sync [:acs/bump :a]))
          (is (= 1 @(:notified before-hide))
              "still 1 — the pre-hide notifier moved for the write it was
               live for and for nothing since the hide")
          (is (= 1 @(:notified after-reveal))
              "and the reveal's own registration took that same write"))

        (hide! after-reveal))

      (exercised! :activity/reveal-reacquires)

      (.then (inventory/quiesced!)
             (fn [_]
               (is (= {:cells 0 :cell-refs 0 :boundaries 0 :edges 0 :entries 0}
                      (inventory/residue))
                   "teardown after a hide/reveal/hide cycle is exact")
               (done))))))

;; ---------------------------------------------------------------------------
;; 4. A conditional read changed while hidden does not resurrect stale
;;    membership — and the bead's named sabotage for it
;; ---------------------------------------------------------------------------

(defn- hidden-window-flip!
  "The transition the row is about, with `release-on-hide?` a parameter so
  the sabotage is the SAME code path with one call removed rather than a
  second, differently-written scenario.

  Visible under `:which = :a`; hidden; flipped to `:b` while hidden;
  re-rendered while hidden; revealed. Answers the two registrations and
  the reveal's entry."
  [release-on-hide?]
  (let [entry-a   (render! conditional-body)
        committed (commit! entry-a)
        pre-hide  (sole-reader [:acs/a])]
    (when release-on-hide? (hide! committed))
    (rf/with-frame frame-id (rf/dispatch-sync [:acs/flip :b]))
    (let [entry-b  (render! conditional-body)
          revealed (commit! entry-b)]
      {:pre-hide      pre-hide
       :pre-committed committed
       :entry-a       entry-a
       :entry-b       entry-b
       :revealed      revealed})))

(deftest a-conditional-read-changed-while-hidden-does-not-resurrect-stale-membership
  (async done
    (seeded!)
    (let [{:keys [pre-hide entry-a entry-b revealed]} (hidden-window-flip! true)
          reveal-reg (sole-reader [:acs/b])]

      (testing "the premise: the read set really did MOVE across the hidden
                window. Without this the row is a hide/reveal of one
                unchanged set, which section 3 already covers"
        (is (= #{(k [:acs/which]) (k [:acs/a])} (collector/reads-of entry-a)))
        (is (= #{(k [:acs/which]) (k [:acs/b])} (collector/reads-of entry-b)))
        (is (some? pre-hide)))

      (testing "THE ROW. `:acs/a` was read before the hide and is not read
                now, so the reveal must leave it with no reader at all. A
                reveal that replayed the pre-hide read set would put the
                predecessor's membership back on a key the current body
                never touches — a subscription nothing will ever release,
                notifying a boundary that does not read it"
        (is (zero? (reader-count [:acs/a]))))

      (testing "and the CURRENT read set is owned, by the reveal's own
                registration, on both of its keys"
        (is (some? reveal-reg))
        (is (not (identical? pre-hide reveal-reg)))
        (is (true? (identical? reveal-reg (sole-reader [:acs/which])))
            "one registration per boundary, holding both its keys — not two
             registrations holding one each")
        (is (= #{(k [:acs/which]) (k [:acs/b])}
               (inventory/boundary-reads reveal-reg))))

      (testing "so the census is the reveal's read set and nothing else.
                `:cells` is 3 because `:acs/a`'s cell is still inside its
                one macrotask of reaper grace; `:cell-refs` and `:edges`
                are 2, which is the number that would move if the stale
                membership had come back"
        (is (= {:cells 3 :cell-refs 2 :boundaries 1 :edges 2} (ownership))))

      (testing "the revealed boundary is live on the key it now reads, and
                deaf on the one it dropped"
        (rf/with-frame frame-id (rf/dispatch-sync [:acs/bump :b]))
        (is (= 1 @(:notified revealed)))
        (rf/with-frame frame-id (rf/dispatch-sync [:acs/bump :a]))
        (is (= 1 @(:notified revealed))
            "still 1 — a write to the dropped key notifies nobody, because
             a key nothing holds has no cell and contributes no reader"))

      (hide! revealed)
      (exercised! :activity/conditional-read-moved-while-hidden)

      (.then (inventory/quiesced!)
             (fn [_]
               (testing "and the dropped key's cell is gone at the horizon,
                         so nothing survives the transition on `:acs/a`'s
                         behalf"
                 (is (= {:cells 0 :cell-refs 0 :boundaries 0 :edges 0 :entries 0}
                        (inventory/residue))))
               (done))))))

(deftest NEGATIVE-CONTROL-skipping-the-release-on-hide-resurrects-the-stale-membership
  ;; The bead's own sabotage, run as a row rather than described in a
  ;; report: *skipping release-on-hide must turn the stale-membership
  ;; witness red*. It is the same helper with `release-on-hide?` false, so
  ;; what changed between green and red is one call and not one scenario.
  (async done
    (seeded!)
    (let [{:keys [pre-hide pre-committed revealed]} (hidden-window-flip! false)]

      (testing "with the release skipped, the predecessor's membership on
                the dropped key SURVIVES the transition — and it is the
                pre-hide registration itself, by identity, not merely a
                non-zero count"
        (is (= 1 (count (readers [:acs/a])))
            "the assertion the real row makes — `(= [] (readers [:acs/a]))`
             — is FALSE here, which is what makes that row a witness")
        (is (true? (identical? pre-hide (first (readers [:acs/a]))))))

      (testing "and the census the real row pins moves with it: two
                boundaries where there should be one, four memberships
                where there should be two"
        (is (= {:cells 3 :cell-refs 4 :boundaries 2 :edges 4} (ownership))))

      (testing "the leaked registration is still LIVE, which is the cost.
                A write to a key NO visible boundary reads re-renders the
                subtree React has hidden, and leaves the visible one
                untouched — so the leak is not merely retention, it is
                work"
        (let [visible-before @(:notified revealed)]
          (rf/with-frame frame-id (rf/dispatch-sync [:acs/bump :a]))
          (is (pos? @(:notified pre-committed))
              "the hidden subtree is notified by a key it no longer shows")
          (is (= visible-before @(:notified revealed))
              "and the visible one is not")))

      (testing "and the stale membership is still there afterwards, because
                nothing will ever release it: the cleanup that would have
                was the one this control skipped"
        (is (= 1 (count (readers [:acs/a])))))

      ;; Release the visible one only. The leak is the point of the row, so
      ;; it is left for quiescence to fail to collect.
      (hide! revealed)
      (.then (inventory/quiesced!)
             (fn [_]
               (is (pos? (:cell-refs (inventory/residue)))
                   "and the leak OUTLIVES quiescence — a reaper cannot
                    collect a cell that still has a reader, which is
                    precisely why the release is the invariant")
               ;; Release it now, so the row leaves the runtime as it found
               ;; it rather than handing the next row a live registration.
               (hide! pre-committed)
               (done))))))

(deftest NEGATIVE-CONTROL-a-reveal-that-reuses-the-pre-hide-read-set-reacquires-the-wrong-key
  ;; The reveal-side twin. Release-on-hide is performed correctly; what is
  ;; sabotaged is WHICH entry the reveal subscribes — the pre-hide one
  ;; rather than the current render's. That is the failure a reveal has if
  ;; React re-mounts a stale effect, and no assertion in section 4 above
  ;; would catch it if the row asserted only `count`.
  (seeded!)
  (let [entry-a   (render! conditional-body)
        committed (commit! entry-a)]
    (hide! committed)
    (rf/with-frame frame-id (rf/dispatch-sync [:acs/flip :b]))
    (render! conditional-body)
    ;; The sabotage: subscribe the PRE-HIDE entry, which is exactly what a
    ;; reveal replaying a stale effect would do.
    (let [stale (commit! entry-a)]
      (testing "the stale reveal reacquires the key the body no longer
                reads, and does not acquire the key it does"
        (is (= 1 (count (readers [:acs/a])))
            "resurrected — the assertion the real row makes is FALSE here")
        (is (zero? (reader-count [:acs/b]))
            "and the current read is owned by nobody, so a write to it
             re-renders nothing")
        (is (true? (identical? (sole-reader [:acs/a]) (sole-reader [:acs/which])))
            "one registration, holding the PRE-HIDE pair"))
      (hide! stale))))

;; ---------------------------------------------------------------------------
;; 5. An intent retained before the hide obeys the incarnation rule
;; ---------------------------------------------------------------------------

(defn- with-refusals
  "Run `thunk` and collect the always-on corpus
  `:rf.error/frame-destroyed` records it fans.

  Axis 1 (`error-emit`) rather than the dev trace deliberately: it
  survives `-Dre-frame.debug=false`, so these assertions hold under the
  production posture too. Gensym'd listener key, unregistered on the way
  out."
  [thunk]
  (let [seen (atom [])
        key  (keyword "rf2-hic-014" (name (gensym "refusal")))]
    (error-emit/register-error-listener!
      key (fn [r] (when (= :rf.error/frame-destroyed (:error r)) (swap! seen conj r))))
    (try {:result (thunk) :refusals @seen}
         (finally (error-emit/unregister-error-listener! key)))))

(deftest an-intent-retained-across-a-hidden-window-obeys-the-incarnation-rule
  ;; The Activity form of `rf2-hic-013`'s rule. A hidden subtree keeps its
  ;; UI state, so it keeps every callback its last render lowered — and a
  ;; hidden window is exactly where a route change has time to destroy and
  ;; re-seat the frame under the same public id. The rule is that the
  ;; retained callback refuses and the reveal's fresh one routes; this row
  ;; is that pair, taken across the hide.
  (seeded!)
  (let [committed (commit! (render! panel-body))
        ;; The ambient dispatch this render bound — exactly what every
        ;; callback the body lowered retains.
        retained  (collector/frame-dispatch frame-id)]
    (hide! committed)

    ;; The reincarnation, inside the hidden window: same public id, new
    ;; incarnation, different seed so a write that reached the wrong one is
    ;; unmistakable.
    (rf/destroy-frame! frame-id)
    (rf/make-frame {:id frame-id})
    (rf/with-frame frame-id (rf/dispatch-sync [:acs/seed {:which :a :a 50 :b 500}]))

    (testing "SAFETY — the callback the hidden subtree retained refuses the
              successor, loudly"
      (let [{:keys [refusals]} (with-refusals #(retained [:acs/mark :retained]))]
        (is (nil? (:marked (app-db)))
            "the successor's app-db is UNTOUCHED by a callback lowered
             under the incarnation that was visible")
        (is (= 1 (count refusals))
            "exactly one always-on record — the only observable separating
             refused from silently delivered to the wrong frame")
        (is (= {:error    :rf.error/frame-destroyed
                :frame    frame-id
                :event-id :acs/mark
                :op       :dispatch-sync}
               (select-keys (first refusals) [:error :frame :event-id :op]))
            "asserted as a MAP — id, target frame, refused event head and
             operation realm together. A `thrown?`-shaped assertion would
             stay green for a refusal raised by a different layer for a
             different reason")))

    (testing "LIVENESS — the reveal lowers a fresh callback, and it routes
              into the incarnation that is live now"
      (let [revealed  (commit! (render! panel-body))
            on-reveal (collector/frame-dispatch frame-id)
            {:keys [refusals]} (with-refusals #(on-reveal [:acs/mark :revealed]))]
        (is (= :revealed (:marked (app-db)))
            "the revealed subtree's own control writes its own app-db")
        (is (empty? refusals))
        (is (not (identical? retained on-reveal))
            "and it is a different closure — the memo row was replaced when
             the successor seated, which is what makes safety and liveness
             the same fact seen twice")
        (hide! revealed)))

    (exercised! :activity/retained-intent-across-a-reincarnation)))

;; ---------------------------------------------------------------------------
;; 6. Repeated ACTIVITY hide/reveal cycles leave EXACT ownership
;; ---------------------------------------------------------------------------

(deftest repeated-hide-reveal-cycles-leave-exactly-one-commits-ownership
  ;; The property a single cycle cannot state is REPETITION: a leak of one
  ;; membership per cycle looks exactly like a correct single cycle at the
  ;; census, and a reveal that found its predecessor's subscription still
  ;; installed reads exactly like one that rebuilt its own.
  ;;
  ;; **This row was named for suspend/retry until merged-PR audit #7792,
  ;; and that name was false evidence.** The cycle below is the Activity
  ;; detach/reacquire seam driven four times, and a Suspense fallback is
  ;; not that mechanism — the DOM row named in this file's ns docstring
  ;; measured the retained subscription and the retry's `identical?`
  ;; registration. The arithmetic here was never the false part, so it is
  ;; unchanged; the mechanism it was claimed for is.
  (async done
    (seeded!)
    (let [!regs (atom [])]
      (dotimes [cycle 4]
        (let [committed (commit! (render! panel-body))
              reg       (sole-reader [:acs/a])]
          (swap! !regs conj reg)
          (is (= {:cells 1 :cell-refs 1 :boundaries 1 :edges 1} (ownership))
              (str "cycle " cycle ": the reveal owns exactly one membership"))
          (is (some? reg)
              (str "cycle " cycle ": and exactly one registration holds it"))
          (hide! committed)
          (is (zero? (reader-count [:acs/a]))
              (str "cycle " cycle ": the hide releases it whole"))))

      (testing "four cycles, four distinct registrations — no cycle reused a
                predecessor's, which is what says each reveal genuinely
                rebuilt the subscription rather than finding one still
                installed"
        (is (= 4 (count @!regs)))
        (is (= 4 (count (remove nil? @!regs))))
        ;; The set is built OUTSIDE the assertion so a failure reports a
        ;; number rather than a set of cyclic registrations. A JS object
        ;; carries no IEquiv, so `into #{}` de-duplicates by `identical?`
        ;; and this count IS the identity statement.
        (let [distinct-regs (count (into #{} @!regs))]
          (is (= 4 distinct-regs)
              "four reveals, four registration identities — no cycle found a
               subscription still installed")))

      (exercised! :activity/repeated-hide-reveal-cycles)

      (.then (inventory/quiesced!)
             (fn [_]
               (testing "and the census after four full cycles is the census
                         after none: exact ownership does not accumulate"
                 (is (= {:cells 0 :cell-refs 0 :boundaries 0 :edges 0 :entries 0}
                        (inventory/residue))))
               (done))))))

;; ---------------------------------------------------------------------------
;; 7. The three lifecycle states, and the one the runtime cannot tell apart
;;    on its own
;; ---------------------------------------------------------------------------

(deftest the-three-lifecycle-states-are-distinguishable-and-one-pair-needs-react
  ;; ASSERTION ONLY. The Xray view is `rf2-hic-023`'s; this row says what
  ;; that view has to read, and — the part worth writing down — what it
  ;; cannot read from these tables alone.
  (async done
    (seeded!)
    (let [entry     (render! panel-body)
          committed (commit! entry)
          connected (sole-reader [:acs/a])]

      (testing "VISIBLE-CONNECTED — the registration is in its keys' reader
                lists, its forward edge answers its read set, and the
                entry it was minted from is CLAIMED"
        (is (some? connected))
        (is (= #{(k [:acs/a])} (inventory/boundary-reads connected)))
        (is (= 1 (entry-refs entry))))

      (hide! committed)

      (testing "HIDDEN-RETAINED — the registration is out of every reader
                list and the entry's claim is dropped, but the entry
                OBJECT survives in React's retained fiber, and its
                `subscribe` is what a reveal calls"
        (is (zero? (reader-count [:acs/a])))
        (is (= 0 (entry-refs entry)))
        (is (= #{(k [:acs/a])} (inventory/boundary-reads connected))
            "the released registration still answers its read set — the
             forward edge is the entry's key set, shared by reference and
             never cleared, so a projection can still say WHAT a hidden
             boundary was reading"))

      (let [revealed (commit! entry)]
        (testing "and the retention is demonstrable rather than assumed:
                  the SAME entry object re-subscribes, which is the fact
                  that separates hidden-retained from unmounted"
          (is (= 1 (entry-refs entry)))
          (is (not (identical? connected (sole-reader [:acs/a])))))
        (hide! revealed))

      (exercised! :lifecycle/three-states-distinguished)

      (.then (inventory/quiesced!)
             (fn [_]
               (testing "UNMOUNTED — every table is empty, and the entry has
                         been evicted from the cache"
                 (is (= {:cells 0 :cell-refs 0 :boundaries 0 :edges 0 :entries 0}
                        (inventory/residue))))

               (testing "THE FINDING, stated where a reader of this file will
                         meet it. Between the hide above and the quiescence
                         here, these tables answer the SAME census — zero
                         memberships, zero boundaries, an unclaimed entry.
                         The runtime's ownership tables therefore do NOT
                         distinguish hidden-retained from unmounted, and no
                         projection over them alone can: the distinguishing
                         fact is React's retention of the fiber, and the
                         only evidence of it available here is the
                         re-subscribe against the same entry object that the
                         middle section just performed. rf2-hic-023's view
                         has to take its third state from that transition —
                         an entry whose refs go 1 → 0 → 1 was hidden; an
                         entry whose refs go 1 → 0 and is then reaped was
                         unmounted"
                 (is (= 0 (entry-refs entry))
                     "the same number the hidden state answered, which is
                      the whole of the finding"))
               (done))))))

;; ---------------------------------------------------------------------------
;; The roster, asserted rather than described
;; ---------------------------------------------------------------------------

(deftest the-declared-population-was-actually-exercised
  ;; Ordered last by `cljs.test`'s declaration order, which is what makes it
  ;; readable: every row above has run by the time this one does.
  (is (= declared-population @!exercised)
      (str "every declared Activity transition must be reached at
            runtime. Missing: "
           (pr-str (set/difference declared-population @!exercised)))))
