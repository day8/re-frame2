(ns re-frame.hicasso.reincarnation-readset-dom-cljs-test
  "A MOUNTED READ SET ACROSS A LATER-TASK FRAME RECREATION — the value the
  cold probe recovers, and the ownership it does not.

  `reincarnation_cells_cljs_test` takes the SYNCHRONOUS transition at the
  commit seam, where `invalidate-cell!`'s deferred phase finds the
  successor already seated and re-wires the held cell. Its section 3
  records the other branch in a comment: *a successor seated in a later
  task now finds the cell disposed and recovers through `cold-read!`'s
  probe on the next render instead, which is the recovery a key that
  never had a cell already gets*.

  That recovery is real and it is not enough. A probe answers a VALUE; it
  takes no reference, records no edge and installs no watch. What the
  disposal left behind is a committed registration attached to a table
  slot that no longer exists — and React repairs none of it on its own,
  because `subscribe` is cached per read-set entry: a mounted boundary
  re-rendering the same read set is handed the same closure, React
  compares it by identity, and no `acquire-cell!` ever runs.

  So the boundary paints the successor on the render that recreated the
  frame, and is deaf to every write after it. **The paint is what makes
  this dangerous**: a value-only assertion on that first render reads
  green, which is why every row below asserts the painted value as a
  PREMISE and then goes on to ask what the runtime is holding (rf2-3awu).

  ## What each row is for

  | row | what it establishes |
  |---|---|
  | [[a-mounted-read-set-reacquires-after-a-later-task-recreation]] | the full sequence: mount, destroy, drain, recreate a task later, force a same-head props re-render, then write to the successor. The painted value, the reacquired membership, and the write that has to land. |
  | [[an-unrelated-re-render-does-not-churn-a-live-membership]] | the negative control. The retirement is reached by a DISPOSAL, not by rendering, so an ordinary re-render inside one incarnation keeps the entry, the cell and the reader it already had. |
  | [[the-declared-population-was-actually-exercised]] | the roster, asserted rather than described. |

  ## The native hook rides the same entry, and is asserted rather than assumed

  `n/use-sub` hands `useSyncExternalStore` the entry `collector/hook-entry`
  mints for its one key, and a boundary whose read set IS that one key
  resolves the same object. So the island in these rows is not a second
  implementation under test: it is the control that says the boundary's
  repair reaches the hook door because there is one mechanism, and the
  residue readings — one cell, TWO readers — are what say it.

  ## The lane

  `:node-test` compiles this namespace too (`cljs-test$` matches
  `-dom-cljs-test`) and has no document, so every row degrades to an
  explicit skip there rather than to an assertion that passes because
  nothing ran. The real run is `npm run test:browser`."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures async]]
            [clojure.set :as set]
            [re-frame.adapter.uix :as rf.adapter.uix]
            [re-frame.core :as rf]
            [re-frame.frame :as rf.frame]
            [re-frame.hicasso :as rf.hicasso]
            [re-frame.hicasso.checkpoint-support :as rf.hicasso.checkpoint-support]
            [re-frame.hicasso.impl.collector :as rf.hicasso.impl.collector]
            [re-frame.hicasso.impl.mount :as rf.hicasso.impl.mount]
            [re-frame.hicasso.native :as rf.hicasso.native]
            [re-frame.hicasso.test.runtime :as rf.hicasso.test.runtime]
            [re-frame.test-support :as rf.test-support]
            ["react" :as react]))

(def ^:private frame-id ::reincarnation-readset)

(rf/reg-event :readset/seed (fn [_ [_ who]] {:db {:who who}}))
(rf/reg-sub   :readset/who  (fn [db _] (:who db)))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter       rf.adapter.uix/adapter
     :ambient-frame nil
     :async?        true
     :init-fn       (fn [] (rf.hicasso.impl.collector/reset-runtime!))}))

;; ---------------------------------------------------------------------------
;; The exercised population — a MEASUREMENT, not a claim
;; ---------------------------------------------------------------------------

(def ^:private declared-population
  "The two transitions this file undertakes to reach at runtime. A row
  that starts returning early fails the last deftest instead of quietly
  shrinking the evidence."
  #{:readset/later-task-recreation
    :readset/unrelated-rerender})

(defonce ^:private !exercised (atom #{}))

(defn- exercised! [mechanism] (swap! !exercised conj mechanism) nil)

;; ---------------------------------------------------------------------------
;; The page — one boundary and one island, reading one key
;; ---------------------------------------------------------------------------

(def ^:private sub-key [frame-id [:readset/who]])

(defn- island
  "A raw React island reading the same key the boundary reads, through
  `n/use-sub` — the hook door onto the very entry the boundary resolves."
  [_props]
  (react/createElement "i" #js {"className" "island"}
                       (str (rf.hicasso.native/use-sub [:readset/who]))))

(rf.hicasso/defhost island-host island {:server :render})

(rf.hicasso/defview who-page
  "The mounted view. `:revision` is in the props and NOWHERE in the read
  set: it exists so a row can force a real re-render of the same head
  without changing what the body reads, which is the shape the defect
  needs (`boundary-props=` compares the whole props value, so a changed
  revision defeats the memo bail-out). It is written to the DOM as well,
  so a row can prove the re-render happened rather than trusting it."
  [{:keys [revision]}]
  [:div
   [:b.who {:data-revision (str revision)} (rf.hicasso/sub [:readset/who])]
   [island-host {}]])

;; ---------------------------------------------------------------------------
;; Harness
;; ---------------------------------------------------------------------------

(defn- skip! [why]
  (is true (str "a mounted-ownership claim needs a real React DOM — " why)))

(defn- seat!
  "Create the frame under `frame-id` and seed it — the same three
  synchronous forms that mount the predecessor and seat the successor,
  because the transition under test is the ordinary one a tenant or
  account switch performs."
  [who]
  (rf/make-frame {:id frame-id})
  (rf/with-frame frame-id (rf/dispatch-sync [:readset/seed who]))
  (rf.frame/frame-incarnation-token frame-id))

(defn- text [handle] (.-textContent ^js (:container handle)))

(defn- revision-attr [handle]
  (some-> ^js (.querySelector ^js (:container handle) "b.who")
          (.getAttribute "data-revision")))

(defn- readers-residue
  "The residue with `:entries` dropped — cells, memberships, boundaries
  and edges, which is what a reacquisition has to move."
  []
  (dissoc (rf.hicasso.test.runtime/residue) :entries))

(defn- poll [pred label]
  (rf.test-support/poll-until pred {:label label :timeout-ms 4000}))

(defn- later-task
  "A promise of `(f)` evaluated in a LATER TASK. The whole transition
  under test is that the successor arrives after the microtask checkpoint
  that disposed the predecessor's cell has already run — which a promise
  turn cannot express, because a task cannot be dequeued inside one."
  [f]
  (js/Promise. (fn [resolve] (js/setTimeout (fn [] (resolve (f))) 0))))

(defonce ^:private !minted
  ;; Every root a row has minted, oldest first; emptied by the single
  ;; trailing step. `defonce` takes no docstring, hence the comment.
  (atom []))

(defn- release-minted!
  "Release every root this row minted, and forget them. Rides the single
  trailing step, which BOTH arms reach, so a row whose poll timed out or
  whose body threw cannot leave a live root standing in the document for
  the next namespace to inherit. `mount/release!` is idempotent."
  []
  (run! rf.hicasso.impl.mount/release! @!minted)
  (reset! !minted [])
  nil)

(defn- mount-live!
  "Mount at `revision`, and return only once the read set is provably
  COMMITTED — two readers on one cell, the boundary's and the island's.
  `useSyncExternalStore` calls `subscribe` in a passive effect React
  flushes after the commit, and a row that started before it would be
  measuring an unsubscribed tree and would stay green through a runtime
  that reacquired nothing.

  Enrols the root in [[!minted]] the instant it exists, because from that
  instant until [[release-minted!]] runs there is a live root on the page
  and this promise is the only thing that could ever name it."
  [revision]
  (let [container (rf.hicasso.impl.mount/fresh-container!)
        handle    (rf.hicasso.impl.mount/root! container frame-id [who-page {:revision revision}])]
    (swap! !minted conj handle)
    (-> (poll #(= {:cells 1 :cell-refs 2 :boundaries 2 :edges 2} (readers-residue))
              "the boundary and the island are both committed on one cell")
        (.then (fn [_] handle)))))

(defn- report-failure!
  "Record `label` against THIS row and DELIBERATELY DO NOT finish it. The
  chain's single trailing step calls `done` and owns the teardown, for
  the reason `poll-until`'s own docstring gives: `cljs.test/run-block`
  hands `done` a continuation that runs the whole remainder of the run
  synchronously, so a `.catch` downstream of `done` claims a foreign
  failure as this row's and then calls `done` twice."
  [label handle]
  (fn [e]
    (is false (str label " — " (.-message e)
                   " | DOM was " (pr-str (when handle (text handle)))
                   " | residue " (pr-str (readers-residue))))
    nil))

;; ---------------------------------------------------------------------------
;; The row the bead is about
;; ---------------------------------------------------------------------------

(deftest a-mounted-read-set-reacquires-after-a-later-task-recreation
  (async done
    (if-not (rf.hicasso.impl.mount/browser?)
      (do (skip! ":node-test has no document to mount into") (done))
      (do
        (rf.hicasso.checkpoint-support/leave-act-environment!)
        (seat! "A")
        (-> (mount-live! 0)
            (.then
              (fn [handle]
                (testing "the premise: a committed read set, held by two
                          readers of one key — the boundary's registration and
                          the island's, which is the SAME entry because a
                          one-key read set is an ordinary read set"
                  (is (= "A" (rf/with-frame frame-id @(rf/subscribe [:readset/who])))
                      "the predecessor holds the value")
                  (is (some? (rf.hicasso.test.runtime/cell-reaction sub-key))
                      "and the runtime holds a live cell for the key"))

                ;; 1 — destroy, and let the invalidation microtask RUN. With
                ;; no successor to rebuild against, the deferred phase
                ;; disposes: the exact no-successor teardown, unchanged.
                (rf/destroy-frame! frame-id)
                (-> (rf.hicasso.checkpoint-support/drain-checkpoint
                      #(zero? (:cells (readers-residue))))
                    (.then
                      (fn [turns]
                        (testing "inside the checkpoint the cell is DISPOSED —
                                  the frame did not come back, so this is the
                                  no-successor branch and not a rewire"
                          (is (some? turns)
                              (str "the disposal did not land inside the "
                                   "microtask checkpoint; residue "
                                   (pr-str (readers-residue))))
                          (is (nil? (rf.hicasso.test.runtime/cell-reaction sub-key))))

                        ;; 2 — the successor arrives in a LATER TASK, which is
                        ;; the whole transition: the disposal has already run.
                        (later-task
                          (fn []
                            (seat! "B")
                            ;; 3 — a forced same-head props re-render. The read
                            ;; set is unchanged, so this is exactly the render
                            ;; React answers without re-subscribing.
                            (rf.hicasso.impl.mount/render! handle [who-page {:revision 1}])
                            handle))))
                    (.then
                      (fn [handle]
                        (testing "the premise, and the trap: the re-render
                                  really happened, and BOTH doors paint the
                                  successor — `cold-read!`'s probe answers the
                                  live incarnation, so a value-only assertion
                                  reads green right here"
                          (is (= "1" (revision-attr handle))
                              "the same head re-rendered under new props")
                          (is (= "BB" (text handle))
                              (str "the boundary and the island both show the "
                                   "successor's value; DOM was "
                                   (pr-str (text handle)))))

                        ;; 4 — what the probe did NOT do.
                        (poll #(= {:cells 1 :cell-refs 2 :boundaries 2 :edges 2}
                                  (readers-residue))
                              "the mounted read set reacquires a cell and both memberships")))
                    (.then
                      (fn [_]
                        (testing "the mounted boundary and island REGAINED
                                  reactive ownership: one cell under the
                                  successor, two reader memberships, two
                                  dependency edges — the probe took none of
                                  those, so this is the entry retirement being
                                  spent on React's own re-subscribe"
                          (is (= {:cells 1 :cell-refs 2 :boundaries 2 :edges 2}
                                 (readers-residue)))
                          (is (some? (rf.hicasso.test.runtime/cell-reaction sub-key))
                              "and the cell holds a live reaction, so a write
                               has an edge to travel"))

                        ;; 5 — the assertion the impact statement is about: a
                        ;; write to the SUCCESSOR has to reach the screen.
                        (rf/with-frame frame-id (rf/dispatch-sync [:readset/seed "C"]))
                        (rf.hicasso.impl.mount/settle!)
                        (poll #(= "CC" (text handle))
                              "a write to the successor repaints both doors")))
                    (.then
                      (fn [_]
                        (testing "so the boundary is LIVE under the successor
                                  rather than frozen at the value one render
                                  happened to probe"
                          (is (= "CC" (text handle))))
                        (exercised! :readset/later-task-recreation)
                        (rf.hicasso.impl.mount/unmount! handle)
                        (.then (rf.hicasso.test.runtime/quiesced!)
                               (fn [_]
                                 (testing "and teardown is still exact — the
                                           retirement evicted a cache, it did
                                           not leave a membership behind for
                                           somebody else to release"
                                   (is (= {:cells 0 :cell-refs 0 :boundaries 0 :edges 0}
                                          (readers-residue))))
                                 nil))))
                    (.catch (report-failure! "later-task reacquisition" handle)))))
            (.catch (report-failure! "later-task reacquisition" nil))
            ;; The single trailing step, which BOTH arms reach.
            (.then (fn [_] (release-minted!) (done))))))))

;; ---------------------------------------------------------------------------
;; The negative control — the repair is reached by a disposal, not by a render
;; ---------------------------------------------------------------------------

(deftest an-unrelated-re-render-does-not-churn-a-live-membership
  ;; Without this row the one above is satisfied by a runtime that retired
  ;; every entry on every render: the read set would be reacquired after the
  ;; recreation, and also rebuilt on every ordinary re-render in between —
  ;; a fresh cell, a fresh subscription and a fresh baseline deref for a
  ;; boundary whose reads did not move. The repair is a DISPOSAL's, and this
  ;; is what says so.
  (async done
    (if-not (rf.hicasso.impl.mount/browser?)
      (do (skip! ":node-test has no document to mount into") (done))
      (do
        (rf.hicasso.checkpoint-support/leave-act-environment!)
        (seat! "A")
        (-> (mount-live! 0)
            (.then
              (fn [handle]
                (let [reaction (rf.hicasso.test.runtime/cell-reaction sub-key)
                      entry    (rf.hicasso.impl.collector/last-reads)]
                  (rf.hicasso.impl.mount/render! handle [who-page {:revision 1}])
                  (testing "the premise: the same head really did re-render
                            under new props"
                    (is (= "1" (revision-attr handle))))

                  (testing "and the runtime held everything still: the same
                            cell, deriving through the SAME reaction, with the
                            same two memberships on it — no eviction, no
                            re-subscribe, no rebuilt baseline"
                    (is (= {:cells 1 :cell-refs 2 :boundaries 2 :edges 2}
                           (readers-residue)))
                    (is (identical? reaction
                                    (rf.hicasso.test.runtime/cell-reaction sub-key))
                        "the cell was not rebuilt")
                    (is (identical? entry (rf.hicasso.impl.collector/last-reads))
                        "and the read-set entry the render resolved is the one
                         already committed, so React was handed a `subscribe`
                         it has already subscribed through"))

                  (exercised! :readset/unrelated-rerender)
                  (rf.hicasso.impl.mount/unmount! handle)
                  (.then (rf.hicasso.test.runtime/quiesced!)
                         (fn [_]
                           (is (= {:cells 0 :cell-refs 0 :boundaries 0 :edges 0}
                                  (readers-residue))
                               "teardown is exact")
                           nil)))))
            (.catch (report-failure! "unrelated re-render control" nil))
            (.then (fn [_] (release-minted!) (done))))))))

;; ---------------------------------------------------------------------------
;; The population, asserted rather than described
;; ---------------------------------------------------------------------------

(deftest the-declared-population-was-actually-exercised
  (if-not (rf.hicasso.impl.mount/browser?)
    (skip! ":node-test has no document, so nothing is exercised")
    (is (= declared-population @!exercised)
        (str "every declared transition must be reached; missing: "
             (pr-str (set/difference declared-population @!exercised))))))
