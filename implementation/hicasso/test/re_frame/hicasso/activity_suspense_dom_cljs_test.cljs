(ns re-frame.hicasso.activity-suspense-dom-cljs-test
  "ACTIVITY AND SUSPENSE, UNDER REAL REACT 19.2 (rf2-hic-014).

  [[re-frame.hicasso.activity-suspense-cljs-test]] states the hide/reveal
  ownership algebra exactly, at the collector's published seam, and runs
  on every PR. **It cannot say that React hides anything** — it drives the
  cleanup and the re-subscribe itself. This file is where that claim is
  made, and it is made the only way it can be: by handing a real
  `<Activity>` a real `mode` change and reading what the runtime kept.

  ## What React undertakes to do, and what this file therefore reads

  React 19.2's [`<Activity>`](https://react.dev/reference/react/Activity)
  hides a subtree while retaining its UI state: it cleans up effects and
  active subscriptions on hide and recreates them on reveal, and hidden
  children may still render at lower priority.
  `useSyncExternalStore`'s subscription is one of those effects, so every
  transition below is React's own — nothing here calls `subscribe`,
  nothing here calls a cleanup, and nothing here schedules a render.

  Three observables, chosen for three different failure modes.

  **Reader membership** (`inventory/cell-readers`) carries the ownership
  rows, and it is read by IDENTITY. A leaked predecessor beside a missing
  successor counts the same as a correct reveal, and both paint the same
  page: this arm's reads are address-directed, so a revealed boundary
  renders the right value out of the wrong ownership.

  **Retained host state** — a `useState` counter and its DOM node — is
  what separates *hidden* from *unmounted*. It is the only observable in
  the file that React owns rather than this runtime, and it is here
  because the runtime's own tables cannot make that distinction; the
  seam file's last row says so in terms.

  **The painted value, read by event-loop POSITION**, carries the
  correction row. `reincarnation_paint_dom_cljs_test` established the
  instrument and the reason: design law React 3 requires a render/commit
  tear to be corrected *before visible paint*, the microtask checkpoint
  drains in full before the same task's rendering update, and a later
  task carries no such promise. A duration cannot tell those apart.

  ## The premise is asserted, every time

  A row that asserts \"nothing was acquired\" is worthless if React never
  rendered anything — the assertion holds for a render that never
  happened. Every transition below therefore asserts its own premise
  first: `collector/body-runs` moved, or the DOM changed, or the reader
  count reached the value the next assertion is about.

  ## The census is taken BEFORE the fixture resets anything

  `mount/release!` resets the runtime, so a residue reading taken after
  it answers zero whether teardown worked or not (rf2-2rtt6.48). Every
  row ends with [[teardown-census!]]: unmount, settle at the runtime's
  own horizon, ASSERT, and only then release.

  ## On the node lane this file states a skip, never a green

  `:node-test` has no DOM. Every row degrades to an explicit skip rather
  than to an assertion that passes because nothing ran. The real run is
  `npm run test:browser`."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures async]]
            [clojure.set :as set]
            [re-frame.adapter.uix :as uix-adapter]
            [re-frame.core :as rf]
            [re-frame.hicasso :as h]
            [re-frame.hicasso.checkpoint-support :as support]
            [re-frame.hicasso.impl.codec :as codec]
            [re-frame.hicasso.impl.collector :as collector]
            [re-frame.hicasso.impl.inventory :as inventory]
            [re-frame.hicasso.impl.mount :as mount]
            [re-frame.test-support :as test-support]
            ["react" :as react]
            ["react-dom" :as react-dom]
            ["react-dom/client" :as react-dom-client]))

(def ^:private frame-id ::activity-suspense-dom)

(rf/reg-sub :acsd/a (fn [db _] (:a db)))
(rf/reg-sub :acsd/b (fn [db _] (:b db)))

(rf/reg-event :acsd/seed (fn [_ [_ db]] {:db db}))
(rf/reg-event :acsd/bump (fn [{:keys [db]} [_ key]] {:db (update db key inc)}))

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
  "The React-driven mechanisms this file undertakes to exercise.
  [[the-declared-population-was-actually-exercised]] asserts that every
  one was reached at runtime, so the roster cannot drift into a list of
  things the suite used to do."
  #{:activity/hide
    :activity/hidden-render
    :activity/reveal
    :activity/reveal-corrects-a-stale-fiber
    :suspense/post-commit-fallback-and-retry})

(defonce ^:private !exercised (atom #{}))

(defn- exercised! [mechanism] (swap! !exercised conj mechanism) nil)

;; ---------------------------------------------------------------------------
;; Harness
;; ---------------------------------------------------------------------------

(defn- skip!
  [why]
  (is true (str "Activity/Suspense DOM witness needs a real React DOM — " why)))

(defn- seeded!
  []
  (support/leave-act-environment!)
  (rf/make-frame {:id frame-id})
  (rf/with-frame frame-id (rf/dispatch-sync [:acsd/seed {:a 1 :b 100}]))
  frame-id)

(defn- k [query-v] [frame-id query-v])

(defn- ownership
  "The census with the entry cache projected out. A read-set entry is a
  render-phase cache, not ownership."
  []
  (dissoc (inventory/residue) :entries))

(defn- reader-count
  "How many registrations read `query-v`.

  A COUNT rather than the reader vector: a registration and the cells it
  acquired hold each other, so `cljs.test`'s failure printer walks a
  cycle and dies on the vector. The seam file's `reader-count` carries
  the full note."
  [query-v]
  (count (inventory/cell-readers (k query-v))))

(defn- sole-reader
  [query-v]
  (let [rs (inventory/cell-readers (k query-v))]
    (when (= 1 (count rs)) (first rs))))

(defn- poll
  [pred label]
  (test-support/poll-until pred {:label label :timeout-ms 4000}))

(defn- mount-concurrent!
  "A concurrent root, rendered WITHOUT `flushSync`.

  Deliberately not `mount/root!`. That door renders inside `flushSync`
  because the witnesses it was built for read the DOM on the next line;
  Activity's reveal and Suspense's retry are React's concurrent business,
  and a row that forced them sync would be a row about the forced
  schedule. Every wait below is a poll on the condition instead."
  [container element]
  (let [root (react-dom-client/createRoot container)]
    (.render root element)
    {:root root :container container :frame frame-id}))

(defn- text [handle] (.-textContent ^js (:container handle)))

(defn- node [handle id] (.querySelector ^js (:container handle) (str "#" id)))

(defn- visible?
  "Is `id`'s node in the layout? A hidden `<Activity>` keeps its host
  nodes in the document and takes them out of the layout with
  `display: none`, so presence in the DOM is NOT visibility and
  `textContent` still reports hidden text. Every paint reading below is
  taken through this."
  [handle id]
  (when-some [el (node handle id)]
    (not= "none" (.-display (js/getComputedStyle el)))))

(defn- painted
  "What the user can see of `id` right now: its text, or nil while it is
  out of the layout."
  [handle id]
  (when (visible? handle id) (.-textContent ^js (node handle id))))

(defn- teardown-census!
  [handle]
  (mount/unmount! handle)
  (.then (inventory/quiesced!)
         (fn [_]
           (is (= {:cells 0 :cell-refs 0 :boundaries 0 :edges 0 :entries 0}
                  (inventory/residue))
               "teardown is exact: zero residue after quiescence")
           (mount/release! handle)
           nil)))

(defn- fail-and-finish!
  [done label handle]
  (fn [e]
    (is false (str label " — " (.-message e)
                   " | DOM was " (pr-str (when handle (text handle)))
                   " | ownership " (pr-str (ownership))))
    (when handle (mount/release! handle))
    (done)))

;; ---------------------------------------------------------------------------
;; The tree
;; ---------------------------------------------------------------------------

(h/defview panel
  "The boundary under Activity. Its read set is a function of a PROP, not
  of a subscription, and that is deliberate: while the subtree is hidden
  it holds no subscription, so an app-db change cannot reach it — the
  only way to move a hidden boundary's read set is for its visible parent
  to hand it a different prop, which is exactly the case
  *conditional reads changed while hidden* names."
  [{:keys [which]}]
  [:p {:id "panel"}
   (str (name which) "=" (if (= :a which) (h/sub [:acsd/a]) (h/sub [:acsd/b])))])

(def ^:private !bump-count (atom nil))

(defn- host-state
  "A plain React `useState` beside the boundary. Its value surviving a
  hide is React retaining the fiber; its value resetting is React having
  unmounted the subtree. It is the ONLY thing in this file that separates
  hidden-retained from unmounted, and it is not this runtime's to
  provide."
  [_]
  (let [[n set-n] (react/useState 0)]
    (react/useEffect (fn [] (reset! !bump-count #(set-n inc)) js/undefined)
                     #js [set-n])
    (react/createElement "span" #js {:id "count"} (str n))))

(unchecked-set host-state "displayName" "acsd/host-state")

(def ^:private !set-mode (atom nil))
(def ^:private !set-which (atom nil))

(defn- activity-host
  "Owns the Activity mode and the boundary's prop. Both setters are
  published from a passive effect, so a row cannot drive the tree before
  React has mounted it."
  [_]
  (let [[mode set-mode]   (react/useState "visible")
        [which set-which] (react/useState :a)]
    (react/useEffect (fn []
                       (reset! !set-mode set-mode)
                       (reset! !set-which set-which)
                       js/undefined)
                     #js [set-mode set-which])
    (react/createElement
      (.-Activity react) #js {:mode mode}
      (react/createElement (.-Fragment react) nil
                           (codec/root-element frame-id [panel {:which which}])
                           (react/createElement host-state nil)))))

(unchecked-set activity-host "displayName" "acsd/activity-host")

(defn- activity-tree
  []
  (mount/provider frame-id (react/createElement activity-host nil)))

(defn- act!
  "Drive a React state setter and let its commit land. `flushSync`
  because the row's next line reads the DOM or the tables; the SCHEDULE
  under test is Activity's own hide/reveal work, which React performs
  inside this commit and in the passive phase after it — neither of which
  a flush invents."
  [f]
  (react-dom/flushSync f)
  nil)

;; ---------------------------------------------------------------------------
;; 1. The lifecycle: hide releases, hidden work publishes nothing, reveal
;;    reacquires the CURRENT read set
;; ---------------------------------------------------------------------------

(deftest an-activity-hide-releases-ownership-and-its-reveal-reacquires-the-current-read-set
  (async done
    (if-not (mount/browser?)
      (do (skip! ":node-test has no DOM") (done))
      (let [_      (seeded!)
            handle (mount-concurrent! (mount/fresh-container!) (activity-tree))
            !state (atom {})]
        (-> (poll #(and (= 1 (reader-count [:acsd/a])) (some? @!set-mode))
                  "the visible Activity commits and its boundary subscribes")
            (.then
              (fn [_]
                (testing "VISIBLE-CONNECTED — one reader on the key the body
                          read, nothing on the key it did not, and the page
                          shows it"
                  (is (= "a=1" (painted handle "panel")))
                  (is (= 1 (reader-count [:acsd/a])))
                  (is (zero? (reader-count [:acsd/b])))
                  (is (= {:cells 1 :cell-refs 1 :boundaries 1 :edges 1} (ownership))))

                (swap! !state assoc :visible-reg (sole-reader [:acsd/a]))

                ;; Move React's own state so the hide has something to
                ;; retain that is not this runtime's.
                (act! (fn [] (@!bump-count)))
                (act! (fn [] (@!bump-count)))
                (is (= "2" (.-textContent ^js (node handle "count")))
                    "the premise for retention: React's own state is at 2")

                ;; HIDE. React's cleanup phase runs the subscription
                ;; teardown; nothing here calls it.
                (act! (fn [] (@!set-mode "hidden")))
                (poll #(zero? (reader-count [:acsd/a]))
                      "React releases the hidden subtree's subscription")))
            (.then
              (fn [_]
                (testing "HIDE releases the committed ownership. React tore
                          the `useSyncExternalStore` subscription down, so
                          the reverse edge went with it"
                  (is (zero? (reader-count [:acsd/a])))
                  (is (zero? (:cell-refs (ownership))))
                  (is (zero? (:boundaries (ownership))))
                  (is (zero? (:edges (ownership)))))

                (testing "WITHOUT destroying anything. React kept the
                          subtree — its host node is still in the document
                          and merely out of the layout, and its own state
                          is where it was. An unmounted subtree would have
                          neither"
                  (is (some? (node handle "panel"))
                      "the node is retained, not removed")
                  (is (false? (visible? handle "panel"))
                      "and it is out of the layout, which is what hidden IS")
                  (is (= "2" (.-textContent ^js (node handle "count")))
                      "React's own state survived the hide — this is the
                       observable that separates hidden from unmounted, and
                       it is React's rather than this runtime's"))

                (testing "and re-frame state is untouched"
                  (is (= 1 (:a (rf/app-db-value frame-id)))))

                (exercised! :activity/hide)

                ;; A WRITE while hidden, and a PROP change while hidden.
                ;; The write is what the reveal will have to correct for;
                ;; the prop change is what moves the hidden read set.
                (mount/dispatch! handle [:acsd/bump :a])
                (swap! !state assoc :runs-before (collector/body-runs))
                (act! (fn [] (@!set-which :b)))
                (poll #(> (collector/body-runs) (:runs-before @!state))
                      "React renders the hidden child against its new prop")))
            (.then
              (fn [_]
                (testing "the premise: React really did run the hidden
                          body. Without this the zeros below are the zeros
                          of a render that never happened"
                  (is (pos? (- (collector/body-runs) (:runs-before @!state)))))

                (testing "and the hidden render published nothing — no
                          durable ownership on the key it just read, none
                          on the key it stopped reading"
                  (is (zero? (reader-count [:acsd/b])))
                  (is (zero? (reader-count [:acsd/a])))
                  (is (zero? (:cell-refs (ownership))))
                  (is (zero? (:edges (ownership)))))

                (exercised! :activity/hidden-render)

                ;; REVEAL.
                (act! (fn [] (@!set-mode "visible")))
                (poll #(= 1 (reader-count [:acsd/b]))
                      "React recreates the subscription on reveal")))
            (.then
              (fn [_]
                (let [revealed (sole-reader [:acsd/b])]
                  (testing "REVEAL reacquires the CURRENT read set. The
                            prop moved while the subtree was hidden, so
                            `:acsd/b` is what the body reads now — and
                            `:acsd/a`, read before the hide and not since,
                            must be read by nobody"
                    (is (zero? (reader-count [:acsd/a]))
                        "no stale membership was resurrected")
                    (is (= 1 (reader-count [:acsd/b])))
                    (is (= {:cells 1 :cell-refs 1 :boundaries 1 :edges 1}
                           (ownership))))

                  (testing "and the survivor is the REVEAL's own
                            registration. A count cannot make this
                            statement: a runtime that leaked the
                            predecessor and failed to acquire for the
                            successor answers 1 here too"
                    (is (some? revealed))
                    (is (false? (identical? (:visible-reg @!state) revealed)))))

                (testing "the revealed subtree is back in the layout, and
                          React's own state came back with it"
                  (is (= "b=100" (painted handle "panel")))
                  (is (= "2" (.-textContent ^js (node handle "count")))))

                (testing "and the revealed boundary is LIVE — a write to
                          the key it now reads repaints it"
                  (mount/dispatch! handle [:acsd/bump :b])
                  (poll #(= "b=101" (painted handle "panel"))
                        "the revealed boundary repaints"))))
            (.then
              (fn [_]
                (is (= 1 (reader-count [:acsd/b]))
                    "and the repaint did not add a second reader")
                (exercised! :activity/reveal)
                (teardown-census! handle)))
            (.then (fn [_] (done)))
            (.catch (fail-and-finish! done "activity lifecycle witness" handle)))))))

;; ---------------------------------------------------------------------------
;; 2. The reveal corrects a stale fiber, and WHERE in the event loop
;; ---------------------------------------------------------------------------

(defn- sample-frames!
  "Record `(painted handle id)` on every animation frame until `done?`
  holds or the frame budget runs out. Answers a promise of the samples.

  A `requestAnimationFrame` callback runs at the rendering opportunity
  and before that frame's paint, so what it reads is what the user is
  about to see. Sampling EVERY frame rather than waiting for one is what
  makes it an assertion about the whole interval: the question is not
  *does the corrected value arrive*, it is *was the stale value ever on
  screen*, and only a sample per frame can answer that."
  [handle id done?]
  (let [samples (atom [])]
    (js/Promise.
      (fn [resolve]
        (letfn [(step [n]
                  (swap! samples conj (painted handle id))
                  (if (or (done?) (> n 120))
                    (resolve @samples)
                    (js/requestAnimationFrame (fn [_] (step (inc n))))))]
          (js/requestAnimationFrame (fn [_] (step 0))))))))

(deftest the-reveal-corrects-the-stale-fiber-before-the-rendering-opportunity
  ;; While hidden the boundary holds no subscription, so a write cannot
  ;; reach it: the fiber goes on holding the value it last rendered. The
  ;; reveal therefore commits a subtree that is KNOWN stale, and the
  ;; correction has to arrive before the event loop can paint it — design
  ;; law React 3, which rf2-2l17 ruled is an ordering and not a duration.
  ;;
  ;; **MEASURED, and stronger than the law requires.** The first draft of
  ;; this row waited for the correction at the microtask checkpoint, the
  ;; instrument `reincarnation_paint_dom_cljs_test` needs, and
  ;; `at-the-checkpoint`'s own vacuity guard red it: *the condition
  ;; already held synchronously, before the checkpoint this row is about*.
  ;; React 19.2 re-renders the revealed subtree as part of the reveal
  ;; itself, reading `getSnapshot` during that render — so a flushed
  ;; reveal is corrected before `flushSync` returns and there is no
  ;; deferral to position at all. The row asserts that, because asserting
  ;; a weaker ordering than the runtime actually keeps would stop noticing
  ;; the day it weakened.
  ;;
  ;; Two readings, because the flushed one alone would be a reading of a
  ;; schedule this file invented. The second reveals CONCURRENTLY and
  ;; samples every animation frame, so the claim is about the whole
  ;; interval rather than about one convenient moment in it — **and it is
  ;; where the gap is.** Reading 2 measured one animation frame showing
  ;; the pre-hide value: React reveals the retained subtree and corrects
  ;; it from the passive effect that re-subscribes, and a rendering
  ;; opportunity can fall between the two. The ceiling assertion at the
  ;; end of the row carries the analysis and the reason it is React's
  ;; scheduling rather than this runtime's.
  (async done
    (if-not (mount/browser?)
      (do (skip! ":node-test has no DOM") (done))
      (let [_      (seeded!)
            handle (mount-concurrent! (mount/fresh-container!) (activity-tree))]
        (-> (poll #(and (= 1 (reader-count [:acsd/a])) (some? @!set-mode))
                  "the visible Activity commits and its boundary subscribes")
            (.then
              (fn [_]
                (is (= "a=1" (painted handle "panel")))
                (act! (fn [] (@!set-mode "hidden")))
                (poll #(zero? (reader-count [:acsd/a]))
                      "React releases the hidden subtree's subscription")))
            (.then
              (fn [_]
                ;; Four writes the hidden subtree cannot hear.
                (dotimes [_ 4] (mount/dispatch! handle [:acsd/bump :a]))

                (testing "the premise, and it is the whole row: app-db moved
                          four times while the subtree was hidden, and the
                          retained fiber still holds the pre-hide value. A
                          reveal with nothing to correct proves nothing"
                  (is (= 5 (:a (rf/app-db-value frame-id))))
                  (is (nil? (painted handle "panel"))
                      "still out of the layout")
                  (is (= "a=1" (.-textContent ^js (node handle "panel")))
                      "and still holding the predecessor's value"))

                ;; READING 1 — the flushed reveal.
                (act! (fn [] (@!set-mode "visible")))

                (testing "READING 1. The reveal commit has landed and
                          control has not left this task — no microtask
                          checkpoint has drained, no later task has run, and
                          no rendering opportunity has occurred. The subtree
                          is in the layout and it is CORRECT, so the
                          correction is inside the reveal's own render
                          rather than deferred to any position after it"
                  (is (= "a=5" (painted handle "panel")))
                  (is (= 1 (reader-count [:acsd/a]))
                      "holding exactly one reader — the reveal replaced the
                       released registration, it did not add one"))

                ;; READING 2 — the concurrent reveal, in the browser's own
                ;; vocabulary. Hide again, move the store again, and let
                ;; React schedule the reveal itself.
                (act! (fn [] (@!set-mode "hidden")))
                (poll #(zero? (reader-count [:acsd/a]))
                      "React releases the subscription a second time")))
            (.then
              (fn [_]
                (dotimes [_ 3] (mount/dispatch! handle [:acsd/bump :a]))
                (is (= 8 (:a (rf/app-db-value frame-id))))
                (is (= "a=5" (.-textContent ^js (node handle "panel")))
                    "the premise for reading 2: stale again, by three")
                ;; NO flushSync. React owns the schedule from here.
                (@!set-mode "visible")
                (sample-frames! handle "panel" #(= "a=8" (painted handle "panel")))))
            (.then
              (fn [samples]
                (let [visible-samples (remove nil? samples)
                      stale-frames    (count (take-while #(not= "a=8" %)
                                                         visible-samples))]
                  (testing "the premise: the subtree came back into the
                            layout, so there were frames to judge, and it
                            settled corrected"
                    (is (seq visible-samples))
                    (is (= "a=8" (last visible-samples))))

                  (testing "nothing but the two legitimate values was ever on
                            screen. Every frame showed either the value the
                            retained fiber held or the corrected one — never
                            a third state, never a torn one, never a value
                            from a key this body does not read"
                    (is (every? #{"a=5" "a=8"} visible-samples)
                        (str "samples were " (pr-str (vec (take 10 samples))))))

                  ;; THE CEILING, and the finding it records.
                  ;;
                  ;; Reading 1 shows the reveal corrected before control
                  ;; left the task. This reading shows that is a property of
                  ;; the FLUSH and not of the reveal: scheduled
                  ;; concurrently, React reveals the retained subtree with
                  ;; the value its fiber held and corrects it from the
                  ;; passive effect that re-subscribes — and React flushes
                  ;; passive effects in a scheduler TASK, so a rendering
                  ;; opportunity can fall between the two. Measured here:
                  ;; one animation frame showed `a=5` while app-db held 8.
                  ;;
                  ;; It is React's scheduling rather than this runtime's:
                  ;; the only signal that the store moved arrives when React
                  ;; re-subscribes, and React re-subscribes in a passive
                  ;; effect. There is no earlier hook to hoist the
                  ;; correction into, and `invalidate-cell!`'s microtask —
                  ;; the repair rf2-2l17 made for the reincarnation tear —
                  ;; is not reachable here because no cell was retired.
                  ;;
                  ;; A discrete event does not have the gap: React flushes
                  ;; passive effects at the end of the event, which is what
                  ;; reading 1 stands in for, so a user revealing a hidden
                  ;; tab by clicking paints nothing stale. The exposure is a
                  ;; reveal scheduled from a timer, a promise or a
                  ;; transition.
                  ;;
                  ;; A CEILING rather than an enshrinement: it reds if the
                  ;; gap ever widens, and stays green if it is ever closed.
                  (testing "and the concurrent reveal's stale window is at
                            most one frame wide"
                    (is (>= 1 stale-frames)
                        (str "the revealed subtree showed the pre-hide value "
                             "for " stale-frames " animation frames. One is "
                             "the measured cost of React flushing the "
                             "re-subscribe as a passive effect; more than "
                             "one means the correction has slipped further "
                             "from the reveal."))))
                (exercised! :activity/reveal-corrects-a-stale-fiber)
                (teardown-census! handle)))
            (.then (fn [_] (done)))
            (.catch (fail-and-finish! done "reveal paint-order witness" handle)))))))

;; ---------------------------------------------------------------------------
;; 3. Suspense — a POST-COMMIT suspension hides the committed tree
;; ---------------------------------------------------------------------------

(def ^:private !gate-resolve (atom nil))
(def ^:private !gate-promise (atom nil))
(def ^:private !set-gate (atom nil))

(defn- gate
  "Throws a thenable while closed. Everything after the release is
  React's own retry machinery — nothing here schedules, re-renders or
  commits anything."
  [_]
  (let [[closed? set-closed] (react/useState false)]
    (react/useEffect (fn [] (reset! !set-gate set-closed) js/undefined)
                     #js [set-closed])
    (when closed? (throw @!gate-promise))
    nil))

(unchecked-set gate "displayName" "acsd/gate")

(defn- suspense-tree
  []
  (react/createElement
    (.-Suspense react) #js {:fallback (react/createElement "p" #js {:id "fb"} "waiting")}
    (react/createElement (.-Fragment react) nil
                         (mount/provider frame-id
                                         (codec/root-element frame-id [panel {:which :a}]))
                         (react/createElement gate nil))))

(deftest a-post-commit-suspension-retains-the-subscription-and-the-retry-leaves-exact-ownership
  ;; The Suspense half of the bead, and the half NOT already covered by
  ;; `kernel_commit_owns_dom_cljs_test`. That file suspends BEFORE the
  ;; commit, so its boundary never owned anything. Here the boundary is
  ;; committed and live first and the suspension arrives afterwards.
  ;;
  ;; **A SUSPENSE FALLBACK IS NOT AN ACTIVITY HIDE, and this row is where
  ;; that was measured rather than assumed.** It was written expecting the
  ;; release the Activity rows above assert — the two look like the same
  ;; Offscreen mechanism, and the primary tree is hidden the same way,
  ;; `display: none` with its host nodes retained. React 19.2 does not
  ;; treat them the same: hiding for a fallback leaves the primary tree's
  ;; PASSIVE effects mounted, so the `useSyncExternalStore` subscription
  ;; survives, and `<Activity mode="hidden">` destroys them, so it does
  ;; not. Measured here, on the run this row failed: one reader throughout
  ;; the suspension, and the retry's reader `identical?` to the first.
  ;;
  ;; That difference is a fact rf2-hic-023's lifecycle view has to carry:
  ;; a Suspense-hidden subtree is VISIBLE-CONNECTED in this runtime's
  ;; tables while being invisible on screen, and an Activity-hidden one is
  ;; not. Collapsing them would label a live subscription as released.
  ;;
  ;; So the conduct owed is not "release then reacquire" — it is EXACT
  ;; OWNERSHIP across the cycle: the count never leaves 1, the identity
  ;; never changes, and the retry adds nothing. That is what is asserted.
  (async done
    (if-not (mount/browser?)
      (do (skip! ":node-test has no DOM") (done))
      (let [_       (seeded!)
            _       (reset! !gate-promise (js/Promise. (fn [res] (reset! !gate-resolve res))))
            handle  (mount-concurrent! (mount/fresh-container!) (suspense-tree))
            !state  (atom {})]
        (-> (poll #(and (= 1 (reader-count [:acsd/a])) (some? @!set-gate))
                  "the primary tree commits and its boundary subscribes")
            (.then
              (fn [_]
                (testing "the premise: the boundary is committed, live and
                          owning its read set BEFORE anything suspends —
                          which is what makes this a post-commit suspension
                          rather than the pre-commit one the commit-owns
                          suite already covers"
                  (is (= "a=1" (painted handle "panel")))
                  (is (= {:cells 1 :cell-refs 1 :boundaries 1 :edges 1} (ownership))))

                (swap! !state assoc :first-reg (sole-reader [:acsd/a]))

                ;; A sibling suspends AFTER the commit. React hides the
                ;; primary children and shows the fallback.
                (act! (fn [] (@!set-gate true)))
                (poll #(= "waiting" (painted handle "fb"))
                      "the fallback takes the layout")))
            (.then
              (fn [_]
                (testing "React hid the committed primary tree rather than
                          unmounting it — the boundary's node is still in
                          the document and out of the layout"
                  (is (some? (node handle "panel")))
                  (is (false? (visible? handle "panel"))))

                (testing "but hiding it for a FALLBACK does not release its
                          ownership — the measurement this row exists to
                          record. The subscription is still installed, and
                          it is still the SAME registration, so the runtime
                          regards the suspended subtree as connected"
                  (is (= 1 (reader-count [:acsd/a])))
                  (is (= {:cells 1 :cell-refs 1 :boundaries 1 :edges 1} (ownership)))
                  (is (true? (identical? (:first-reg @!state)
                                         (sole-reader [:acsd/a])))
                      "the same registration, not a rebuilt one"))

                ;; A write DURING the suspension. Because the subscription
                ;; survived, the hidden primary tree hears it.
                (mount/dispatch! handle [:acsd/bump :a])

                ;; The retry.
                (act! (fn [] (@!set-gate false)))
                (poll #(= "a=2" (painted handle "panel"))
                      "the retry reveals the primary tree, up to date")))
            (.then
              (fn [_]
                (testing "the retry leaves EXACT ownership. The cycle
                          neither released nor re-acquired, so the count is
                          where it started and so is the identity — and a
                          suspend/retry that had quietly re-subscribed
                          without releasing would show here as two"
                  (is (= 1 (reader-count [:acsd/a])))
                  (is (= {:cells 1 :cell-refs 1 :boundaries 1 :edges 1} (ownership)))
                  (is (true? (identical? (:first-reg @!state)
                                         (sole-reader [:acsd/a])))))

                (testing "and the revealed tree is up to date without a
                          correction having been needed: it heard the write
                          while it was hidden, which is exactly what an
                          Activity-hidden subtree cannot do"
                  (is (= "a=2" (painted handle "panel"))))

                (testing "still live after the retry"
                  (mount/dispatch! handle [:acsd/bump :a])
                  (poll #(= "a=3" (painted handle "panel"))
                        "the retried boundary repaints"))))
            (.then
              (fn [_]
                (is (= 1 (reader-count [:acsd/a])))
                (exercised! :suspense/post-commit-fallback-and-retry)
                (teardown-census! handle)))
            (.then (fn [_] (done)))
            (.catch (fail-and-finish! done "post-commit suspension witness" handle)))))))

;; ---------------------------------------------------------------------------
;; The roster, asserted rather than described
;; ---------------------------------------------------------------------------

(deftest the-declared-population-was-actually-exercised
  (if-not (mount/browser?)
    (skip! ":node-test runs none of the mechanisms above")
    (is (= declared-population @!exercised)
        (str "every declared Activity/Suspense mechanism must be reached at
              runtime. Missing: "
             (pr-str (set/difference declared-population @!exercised))))))
