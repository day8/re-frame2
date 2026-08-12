(ns re-frame.hicasso.reincarnation-paint-dom-cljs-test
  "PAINT ORDER ACROSS A SAME-ID REINCARNATION — the witness design law
  React 3 was owed, in a real browser, where the paint is (rf2-2l17).

  > A render/commit tear is detected and corrected **before visible
  > paint**.
  >
  > — `docs/design/hicasso/product/lanes/design-laws.md`, React 3

  `reincarnation_cells_cljs_test` states the transition exactly at the
  commit seam and runs on every lane. **It cannot say anything about
  paint**: it drives the commit itself, there is no rendering opportunity
  in a Node process, and the merged-PR audit of PR #7749 said so in as
  many words — *the non-browser commit-seam harness cannot prove React
  law 3 before-visible-paint ordering*. This file is where that claim is
  made, and it is made in Chromium with React 19 driving its own
  schedule.

  ## The claim, and why it is about the event loop rather than the clock

  After a same-id reincarnation `commit-basis` ties — the frame term
  restarts and neither other term is a frame fact — so `getSnapshot`
  ties, React schedules nothing, and the committed fiber goes on holding
  the PREDECESSOR's value. The correction is
  `re-frame.hicasso.impl.collector/invalidate-cell!`'s deferred phase,
  and rf2-2l17 moved it from `setTimeout 0` to `queueMicrotask` for one
  reason: the HTML event loop drains the microtask checkpoint in full
  before the *update the rendering* step that may follow the same task,
  and grants a later task no such promise. \"Corrected before paint\" is
  therefore an event-loop position, not a small number of milliseconds,
  and every reading below is taken by position.

  ## Two readings, and each answers a different objection

  **The checkpoint drain** ([[re-frame.hicasso.checkpoint-support/drain-checkpoint]])
  chains promise turns without ever letting the task end, so it observes
  the DOM at a point the event loop provably has not reached a rendering
  opportunity. It is the DISCRIMINATING reading: a `setTimeout`
  correction is unreachable from inside a checkpoint at any budget, so
  the sabotage below is red categorically rather than probabilistically.

  **The animation frame** is the reading in the browser's own vocabulary.
  A `requestAnimationFrame` callback runs at the rendering opportunity
  and before the paint of that frame, so what it reads is what the user
  is about to see. It is not discriminating on its own — Chromium will
  often run a pending `setTimeout 0` before the next frame, which is
  precisely why a macrotask correction is a *race* and not a guarantee —
  and it is asserted only where the drain has already settled the
  ordering.

  ## What each row is for

  | row | what it establishes |
  |---|---|
  | [[the-first-render-opportunity-after-a-reincarnation-observes-the-successor]] | **W1.** A mounted boundary; the tear is real and asserted while it exists; the correction lands inside the checkpoint and the first frame paints the successor. |
  | [[restoring-the-macrotask-deferral-makes-the-paint-order-witness-fail]] | **W1's sabotage** (Evidence law 3). The same row with `queueMicrotask` routed through `setTimeout 0` — red at the checkpoint, and green again a task later, so the perturbation is a delay rather than a break. |
  | [[a-reincarnation-inside-the-staged-render-to-commit-gap-corrects-the-boundary]] | **W2.** Point 4's cold/staged path: a boundary rendered under A and committed after B seats, forced through the render→commit gap by a sibling's layout effect. |
  | [[the-declared-population-was-actually-exercised]] | the roster, asserted rather than described. |

  W3 — the no-successor cleanup, proving the new scheduling still
  disposes exactly — stays where it already is, at the commit seam, in
  `reincarnation_cells_cljs_test`'s section 2.

  ## The lane

  `:node-test` compiles this namespace too and has no DOM, so every row
  degrades to an explicit skip there rather than to an assertion that
  passes because nothing ran. The real run is `npm run test:browser`."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures async]]
            [clojure.set :as set]
            [re-frame.adapter.uix :as uix-adapter]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.hicasso :as h]
            [re-frame.hicasso.impl.codec :as codec]
            [re-frame.hicasso.impl.collector :as collector]
            [re-frame.hicasso.impl.inventory :as inventory]
            [re-frame.hicasso.impl.mount :as mount]
            [re-frame.hicasso.checkpoint-support :as support]
            [re-frame.test-support :as test-support]
            ["react" :as react]
            ["react-dom/client" :as react-dom-client]))

(def ^:private frame-id ::reincarnation-paint)

(rf/reg-event :reinc-paint/seed (fn [_ [_ who]] {:db {:who who}}))
(rf/reg-sub   :reinc-paint/who  (fn [db _] (:who db)))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter       uix-adapter/adapter
     :ambient-frame nil
     :async?        true
     :init-fn       (fn [] (collector/reset-runtime!))}))

;; ---------------------------------------------------------------------------
;; The exercised population — a MEASUREMENT, not a claim
;; ---------------------------------------------------------------------------

(def ^:private declared-population
  "The three things this file undertakes to reach at runtime. A row that
  starts returning early, or a sabotage that stops perturbing anything,
  fails the last deftest instead of quietly shrinking the evidence."
  #{:paint-order/mounted-reincarnation
    :paint-order/macrotask-sabotage
    :paint-order/staged-gap})

(defonce ^:private !exercised (atom #{}))

(defn- exercised! [mechanism] (swap! !exercised conj mechanism) nil)

;; ---------------------------------------------------------------------------
;; Harness
;; ---------------------------------------------------------------------------

(defn- skip!
  [why]
  (is true (str "a paint-order claim needs a real React DOM — " why)))

(h/defview who-line [_] [:p {:id "who"} (h/sub [:reinc-paint/who])])

(defn- seat!
  "Create the frame under `frame-id` and seed it. Called once to mount the
  predecessor and once, after a destruction, to seat the successor — the
  same three synchronous forms both times, because the transition under
  test is the ordinary one an account or tenant switch performs."
  [who]
  (rf/make-frame {:id frame-id})
  (rf/with-frame frame-id (rf/dispatch-sync [:reinc-paint/seed who]))
  (frame/frame-incarnation-token frame-id))

(defn- reincarnate!
  "Destroy and re-seat under the same public id, synchronously. Answers
  the successor's token so a row can assert the incarnation really
  changed rather than trusting the call."
  [who]
  (rf/destroy-frame! frame-id)
  (seat! who))

(defn- text [handle] (.-textContent ^js (:container handle)))

(defn- poll
  [pred label]
  (test-support/poll-until pred {:label label :timeout-ms 4000}))

(defonce ^:private !minted
  ;; Every root a row has minted through `mount-live!`, oldest first.
  ;; Emptied by `release-minted!` on the row's single trailing step.
  ;; `defonce` takes no docstring, hence the comment.
  (atom []))

(defn- release-minted!
  "Release every root this row minted, and forget them. Rides the single
  trailing step, which BOTH arms reach, so the teardown is written once
  and runs once per path; `mount/release!` is idempotent, so a row whose
  success path already tore its root down pays nothing here.

  **Why the rejection arms cannot do this themselves (rf2-fqof).**
  [[mount-live!]] mints its root SYNCHRONOUSLY and hands it over only
  once the polls succeed, so the OUTER arm below — the one that reports
  with no handle at all — is reachable with a live root on the page: its
  own poll can time out, and a throw anywhere in the body above the inner
  chain unwinds straight past it. Reporting and finishing there leaves a
  mounted React root and its container standing in the document for the
  NEXT namespace to inherit, which is the very contamination rf2-d3tc's
  repair is about, arriving by a second door (rf2-o0n1)."
  []
  (run! mount/release! @!minted)
  (reset! !minted [])
  nil)

(defn- mount-live!
  "Mount, and return only once the boundary is provably SUBSCRIBED.

  `useSyncExternalStore` calls `subscribe` in a passive effect React
  flushes after the commit, and a boundary that has not reached it cannot
  be notified by anything — so a paint-order row that started there would
  be measuring an unsubscribed component and would stay green through a
  runtime that never corrected anything. The wait is therefore a write
  round-trip, exactly as `kernel_commit_owns_dom_cljs_test`'s
  `prove-live!` is: dispatch, and poll until the boundary repaints. Only
  a live subscription can produce that repaint.

  Enrols the root in [[!minted]] the instant it exists, because from that
  instant until [[release-minted!]] runs there is a live root on the page
  and this promise is the only thing that could ever name it."
  []
  (let [container (mount/fresh-container!)
        handle    (mount/root! container frame-id [who-line {}])]
    (swap! !minted conj handle)
    (-> (poll #(= "A" (text handle)) "the predecessor's value is committed")
        (.then (fn [_]
                 (mount/dispatch! handle [:reinc-paint/seed "A-live"])
                 (poll #(= "A-live" (text handle))
                       "the boundary repaints, so its subscription is live")))
        (.then (fn [_] handle)))))

(defn- next-animation-frame
  "A promise of `(f)` evaluated inside the next `requestAnimationFrame`
  callback — at the rendering opportunity, before that frame's paint."
  [f]
  (js/Promise. (fn [resolve] (js/requestAnimationFrame (fn [_] (resolve (f)))))))

(defn- next-task
  "A promise of `(f)` evaluated in a later task, after any `setTimeout 0`
  the runtime may be holding. Used only by the sabotage row, to show the
  perturbation delays the correction rather than destroying it."
  [f]
  (js/Promise. (fn [resolve] (js/setTimeout (fn [] (resolve (f))) 30))))

(defn- report-failure!
  "Record `label` against THIS row and DELIBERATELY DO NOT finish it —
  the chain's single trailing step does that, and owns the teardown too
  ([[release-minted!]]), so this handler reads the DOM for its diagnostic
  and releases nothing. `handle` is therefore a witness here rather than
  a possession: an arm that has one can say what was on screen, and an
  arm that has none is no longer an arm that strands a root.

  **A rejection handler may not sit downstream of the `.then` that calls
  `done` (rf2-d3tc).** `cljs.test/run-block` hands `done` a continuation
  that runs the WHOLE REMAINDER of the suite synchronously — this file's
  later rows, then every namespace after it — so anything out there that
  throws unwinds all the way back into the callback that called `done`.
  A `.catch` placed after that callback then claims a foreign failure as
  its own, and every part of what it prints is wrong: this row's label,
  against whichever test is current by then, reading a DOM its own
  teardown already emptied (`DOM was \"\"`, `residue {:cells 0 …}`) and
  a message that is blank because what cljs.test threw was a bare
  String. It then calls `done` a second time — which does not merely
  warn, it re-forces `run-block`'s delay, since a delay whose body threw
  is still unrealized, and runs the offending namespace again.

  So the repair is positional, and it is the shape
  [[re-frame.hicasso.checkpoint-support/at-the-checkpoint]] already
  uses: report here, fall through to ONE `done` at the tail of the
  chain, and leave nothing after that `done` to catch what the rest of
  the run throws. A failure out there is not this row's to report."
  [label handle]
  (fn [e]
    (is false (str label " — " (.-message e)
                   " | DOM was " (pr-str (when handle (text handle)))
                   " | residue " (pr-str (dissoc (inventory/residue) :entries))))
    nil))

;; ---------------------------------------------------------------------------
;; W1. The mounted boundary, and the first render opportunity after the
;;     successor seats
;; ---------------------------------------------------------------------------

(deftest the-first-render-opportunity-after-a-reincarnation-observes-the-successor
  (async done
    (if-not (mount/browser?)
      (do (skip! ":node-test has no rendering opportunity") (done))
      (do
        (support/leave-act-environment!)
        (seat! "A")
        (-> (mount-live!)
            (.then
              (fn [handle]
                (let [token-a (frame/frame-incarnation-token frame-id)
                      ;; Register the frame observation BEFORE the transition,
                      ;; so the callback is queued against the rendering
                      ;; opportunity that follows this very task.
                      !frame  (atom nil)
                      framed  (next-animation-frame #(reset! !frame (text handle)))
                      token-b (reincarnate! "B")]

                  (testing "the premise: this is a reincarnation, not a write"
                    (is (not (identical? token-a token-b))
                        "the successor is a different incarnation of the same
                         public id")
                    (is (= "B" (rf/with-frame frame-id
                                 @(rf/subscribe [:reinc-paint/who])))
                        "and the successor holds the new value"))

                  (testing "the tear is REAL and is asserted while it exists —
                            synchronously after the successor seats the
                            committed DOM still shows the predecessor's value,
                            because `commit-basis` tied and React was told
                            nothing"
                    (is (= "A-live" (text handle))))

                  (-> (support/drain-checkpoint #(= "B" (text handle)))
                      (.then
                        (fn [turns]
                          (testing "and it is corrected INSIDE the microtask
                                    checkpoint of the task that seated the
                                    successor — the collector's rebuild, its
                                    notification, and React's own sync-lane
                                    flush, render and commit, all before the
                                    event loop reaches a rendering opportunity"
                            (is (some? turns)
                                (str "not corrected within the checkpoint ("
                                     support/checkpoint-turn-budget
                                     " promise turns); the DOM read "
                                     (pr-str (text handle)))))
                          framed))
                      (.then
                        (fn [_]
                          (testing "so the FIRST rendering opportunity after the
                                    reincarnation observes the successor: what
                                    Chromium is about to paint is B, and the
                                    predecessor's value never reached a frame"
                            (is (= "B" @!frame)))

                          (testing "the repaired boundary holds exactly one
                                    reader on one cell — the correction
                                    replaced the attachment, it did not add a
                                    second"
                            (is (= {:cells 1 :cell-refs 1 :boundaries 1 :edges 1}
                                   (dissoc (inventory/residue) :entries))))

                          (exercised! :paint-order/mounted-reincarnation)
                          (mount/unmount! handle)
                          (.then (inventory/quiesced!)
                                 (fn [_] (mount/release! handle) nil))))
                      (.catch (report-failure! "W1 paint-order witness" handle))))))
            (.catch (report-failure! "W1 paint-order witness" nil))
            ;; The single trailing step, which BOTH arms reach: this row's
            ;; roots go down first, and the single `done` is the last act.
            (.then (fn [_] (release-minted!) (done))))))))

;; ---------------------------------------------------------------------------
;; W1's sabotage — Evidence law 3
;; ---------------------------------------------------------------------------

(deftest restoring-the-macrotask-deferral-makes-the-paint-order-witness-fail
  ;; The same transition as W1, with `queueMicrotask` routed through
  ;; `setTimeout 0` for the width of the transition — the scheduling
  ;; `invalidate-cell!` had before rf2-2l17, restored for real rather than
  ;; simulated: the collector is unmodified and unaware, and React keeps the
  ;; original function because `react-dom` bound it by value at module
  ;; evaluation.
  ;;
  ;; The red asserted here is CATEGORICAL, which is why it is worth
  ;; committing rather than running by hand once. A task cannot be dequeued
  ;; during a microtask checkpoint, so no budget, machine or engine makes a
  ;; `setTimeout` correction reachable from inside one. The row then shows the
  ;; correction arriving a task later, so what the sabotage removed is the
  ;; ORDERING GUARANTEE and not the repair — which is exactly the finding the
  ;; bead's ruling rests on.
  (async done
    (if-not (mount/browser?)
      (do (skip! ":node-test has no rendering opportunity") (done))
      (do
        (support/leave-act-environment!)
        (seat! "A")
        (-> (mount-live!)
            (.then
              (fn [handle]
                (support/with-macrotask-deferral #(reincarnate! "B"))
                (-> (support/drain-checkpoint #(= "B" (text handle)))
                    (.then
                      (fn [turns]
                        (testing "with the macrotask deferral restored the
                                  correction is NOT reachable before the event
                                  loop's next rendering opportunity — the
                                  checkpoint drains to exhaustion with the
                                  predecessor's value still committed"
                          (is (nil? turns)
                              (str "the sabotage did not perturb anything: the "
                                   "DOM reached B inside the checkpoint after "
                                   turns " turns, which is the unsabotaged "
                                   "result"))
                          (is (= "A-live" (text handle))
                              "the committed DOM still shows the predecessor"))
                        (next-task #(text handle))))
                    (.then
                      (fn [later]
                        (testing "and a task later it arrives after all — so
                                  the perturbation delayed the correction past
                                  the paint rather than breaking it, and W1's
                                  green is the scheduling and nothing else"
                          (is (= "B" later)))
                        (exercised! :paint-order/macrotask-sabotage)
                        (mount/unmount! handle)
                        (.then (inventory/quiesced!)
                               (fn [_] (mount/release! handle) nil))))
                    (.catch (report-failure! "W1 sabotage control" handle)))))
            (.catch (report-failure! "W1 sabotage control" nil))
            ;; The single trailing step, which BOTH arms reach: this row's
            ;; roots go down first, and the single `done` is the last act.
            (.then (fn [_] (release-minted!) (done))))))))

;; ---------------------------------------------------------------------------
;; W2. The staged render→commit gap — the bead's point 4
;; ---------------------------------------------------------------------------

(defn- gap-reincarnator
  "Reincarnates the frame from a LAYOUT effect: after every body in the
  tree has returned, and before React runs the passive effect in which
  `useSyncExternalStore` acquires. That interval is the render→commit
  gap, and this is a real reincarnation landing inside it — the
  interleaving point 4 names, reached through nothing but public React.

  Once, on mount: the transition under test is a single A→B switch, and
  a layout effect that re-ran would reincarnate the frame under every
  correcting render it caused."
  [_]
  (react/useLayoutEffect (fn [] (reincarnate! "B") js/undefined) #js [])
  nil)

(unchecked-set gap-reincarnator "displayName" "hicasso/gap-reincarnator")

(deftest a-reincarnation-inside-the-staged-render-to-commit-gap-corrects-the-boundary
  ;; Point 4's question, asked of the runtime rather than of the design.
  ;;
  ;; The boundary's key is STAGED at render — no cell holds it, so the
  ;; teardown has no cell to invalidate and schedules no repair at all. The
  ;; only thing that can correct it is `make-snapshot`'s staged branch, which
  ;; contributes a LIVE `commit-basis` read for a key no cell holds: React
  ;; compares the number the fiber captured at render against a fresh
  ;; `getSnapshot` immediately after `subscribe` returns, and re-renders on a
  ;; difference. The worry point 4 records is that the difference is zero — a
  ;; reincarnation restarts the frame's install epoch, so basis@render and
  ;; basis@commit can be the same number across it.
  (async done
    (if-not (mount/browser?)
      (do (skip! ":node-test has no rendering opportunity") (done))
      (do
        (support/leave-act-environment!)
        (seat! "A")
        (let [container (mount/fresh-container!)
              root      (react-dom-client/createRoot container)
              handle    {:root root :container container :frame frame-id}]
          ;; A concurrent root, deliberately not `mount/root!`: that door
          ;; renders inside `flushSync`, and forcing the schedule is the one
          ;; thing a row about scheduling may not do.
          (.render root
                   (react/createElement
                     (.-Fragment react) nil
                     (mount/provider frame-id (codec/root-element frame-id [who-line {}]))
                     (react/createElement gap-reincarnator nil)))
          (-> (poll #(seq (text handle)) "the boundary commits")
              (.then
                (fn [_]
                  (testing "the premise: the reincarnation really did land in
                            the gap — the body rendered under the predecessor,
                            so the committed markup is what a stale commit
                            would leave behind"
                    (is (= "B" (rf/with-frame frame-id
                                 @(rf/subscribe [:reinc-paint/who])))
                        "the successor is seated and holds the new value"))
                  (poll #(= "B" (text handle))
                        "the staged boundary reaches the successor's value")))
              (.then
                (fn [_]
                  (testing "a boundary rendered under the predecessor and
                            committed after the successor seated is corrected
                            rather than left holding A — the staged term in
                            `make-snapshot` is a live `commit-basis` read, and
                            React's post-subscribe re-read is what spends it"
                    (is (= "B" (text handle))))

                  (testing "and it is corrected ONCE: one cell, one reader, no
                            second registration left by the correcting render"
                    (is (= {:cells 1 :cell-refs 1 :boundaries 1 :edges 1}
                           (dissoc (inventory/residue) :entries))))

                  (exercised! :paint-order/staged-gap)
                  (mount/unmount! handle)
                  (.then (inventory/quiesced!)
                         (fn [_] (mount/release! handle) nil))))
              (.catch (report-failure! "W2 staged-gap witness" handle))
              ;; The single trailing step, which BOTH arms reach: this row's
              ;; roots go down first, and the single `done` is the last act.
              (.then (fn [_] (release-minted!) (done)))))))))

;; ---------------------------------------------------------------------------
;; The population, asserted rather than described
;; ---------------------------------------------------------------------------

(deftest the-declared-population-was-actually-exercised
  (if-not (mount/browser?)
    (skip! ":node-test has no rendering opportunity, so nothing is exercised")
    (is (= declared-population @!exercised)
        (str "every declared paint-order mechanism must be reached; missing: "
             (pr-str (set/difference declared-population @!exercised))))))
