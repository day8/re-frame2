(ns re-frame.hicasso.read-extent-dom-cljs-test
  "INVARIANT I7, IN THE CARRIERS A NODE LANE CANNOT DRIVE (rf2-ouus).

  > `sub` is legal during the direct synchronous execution of the active
  > body, helpers, branches, and loops included. A read deferred through a
  > callback, promise, timer, lazy sequence, or other escaped extent
  > refuses with source and recovery.
  >
  > — `docs/design/hicasso/product/invariants.md`, I7

  [[re-frame.hicasso.read-extent-cljs-test]] is the legality matrix:
  fourteen rows, three legal shapes, seven refusals asserted as exact
  error maps, the reconciliation clause and one declared limit. It states
  in its own docstring what it cannot reach. The package's lane is Node —
  no DOM, and `react-dom/server` never commits — so two of I7's deferral
  carriers have no witness there, and it named them rather than implying
  measurement:

  1. a read deferred into a **React effect**;
  2. a read deferred across a **Suspense retry** or an **Activity
     reveal**.

  This file is those rows, driven by real React 19 against a real DOM.
  Nothing here re-states the matrix; it adds the carriers and stops.

  ## Why these carriers are not the ones already banked

  Every escape the Node matrix witnesses lands in a runtime that is
  *idle*: a timer fires, a microtask drains, a lazy sequence is forced —
  React is between commits and has no opinion about any of it. These
  three land while React is **in the middle of its own work**, on the
  boundary's own fiber, with the boundary mounted and its subscription
  live:

  | row | the moment the deferred read lands |
  |---|---|
  | [[a-read-deferred-into-a-react-effect-refuses]] | the mutation phase and then the passive phase of the very commit that mounted the boundary |
  | [[a-read-deferred-across-a-suspense-retry-refuses]] | the resolution of the thenable the body threw, between React discarding the attempt and retrying it |
  | [[a-read-deferred-across-an-activity-reveal-refuses]] | the effect mount React had withheld for as long as the subtree was hidden |

  Each is a plausible author move — *read it in the effect*, *read it
  once the data arrives*, *read it when the panel opens* — and each is a
  moment at which it is easy to believe a body is still on the stack.
  None is. [[re-frame.hicasso.impl.collector/run-once]] clears the render
  frame slot in the `finally` that closes the body, and it clears it for
  a thrown Suspense thenable exactly as it does for an ordinary return.
  The rows below are that sentence, measured.

  ## The observables, and why they can see the fault

  **Not the painted value, and not the text.** rf2-hic-016 established on
  the controlled-input surface that React's own end-of-event restore
  repairs value-level faults inside the same discrete event, so a text
  assertion in a real browser goes green over a real regression. Text
  appears here only as a *poll condition* — a way to know a commit
  happened before a reading is taken — and never as the thing a row
  asserts about.

  What the rows assert is two things React cannot forge or repair:

  1. **The exact refusal identity, as a map.** `:rf.error/id`, the
     emitting function, the recovery keyword, and the query — compared
     whole. This is the one the bead insists on and it is not
     fastidiousness. rf2-hic-011's own P1 control removed `read-key!`'s
     nil-frame guard and the escaped reads *still threw*, from
     `subscribe-once`, carrying `:rf.error/no-frame-context`. A row
     written `(is (thrown? ...))` would have stayed **green under the
     bead's own named sabotage**. In a layered runtime there is nearly
     always a second defence, and a bare throw assertion buys the second
     defence's silence rather than the first one's conduct.
  2. **Reader membership on the refused key** (`inventory/cell-readers`),
     against membership on the key the body legally read. A refusal that
     also *recorded* — a key pushed on the scratch before the guard, an
     edge acquired at the commit that followed — paints an identical page
     and leaks a registration nothing will release. `0` and `1` are
     different numbers and no re-render makes them agree.

  ## Every row proves its own premise, and none can pass by not running

  A deferred read that never fires refuses nothing, and an assertion
  about it holds vacuously. So the deferral bumps a counter before it
  reads, the counter is the poll condition wherever a row waits, and the
  count is asserted. The Suspense row additionally asserts that React
  really did abandon the attempt (the fallback is on the page, the body
  ran) before it says anything about the retry, and the Activity row
  asserts the deferral had **not** fired while the subtree was hidden.

  Liveness is proved the way
  [[re-frame.hicasso.kernel-commit-owns-dom-cljs-test]] proves it: a
  write round-trip. A boundary React has not finished subscribing cannot
  repaint, so a repaint is proof the passive phase is done — and proof
  the surviving registration is live rather than merely present. That
  matters here more than it does there, because every `0` this file
  asserts would also be the `0` of a mount that never happened.

  ## The complaint prose is deliberately not asserted here

  rf2-hic-007 will replace the `:where` symbol with a real file/line
  coordinate and rf2-hic-021 will re-route the message through
  `re-frame.error`. The Node matrix owns the prose contract — it asserts
  that the message names the recovery route — and it is the pre-image
  those two beads will re-point. Copying that assertion into a second
  file would double the surface they have to move for no new information
  about the browser lane, so `:reason` is projected out of every
  comparison below and the message is never matched. The `:recovery`
  keyword in the exact map is the machine-readable half, and it is the
  half this file needs.

  ## Self-contained harness, on purpose

  `roots-frames-support` is the multi-root harness and says in its own
  docstring that two named suites read it; a third reader would need an
  edit to another bead's file to stay true. This file follows
  `kernel-commit-owns-dom-cljs-test` instead, which is the shape that
  fits: one invariant, one browser suite, its own small harness.

  ## On the node lane every row states a skip, never a green

  `:node-test-hicasso` compiles this namespace too — its `ns-regexp`
  matches — and it has no DOM. Each row degrades to an explicit skip
  rather than to an assertion that passes because nothing ran. The two
  controls that need no DOM run in both lanes."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures async]]
            [clojure.set :as set]
            [re-frame.adapter.uix :as uix-adapter]
            [re-frame.core :as rf]
            [re-frame.hicasso :as h]
            [re-frame.hicasso.impl.codec :as codec]
            [re-frame.hicasso.impl.collector :as collector]
            [re-frame.hicasso.impl.inventory :as inventory]
            [re-frame.hicasso.impl.mount :as mount]
            [re-frame.test-support :as test-support]
            ["react" :as react]
            ["react-dom/client" :as react-dom-client]))

(def ^:private frame-id ::read-extent-dom)

;; The four keys are named for the job each does, because the whole
;; discriminating power of this file is in which key ends up on which
;; reader list. `:red/escaped` and `:red/escaped-2` are read ONLY from an
;; escaped extent — no mounted body ever reads them — so their reader
;; count must stay zero while `:red/painted` and `:red/sibling` each hold
;; exactly one. A row whose deferral and whose body shared a key could not
;; tell a refusal from an accumulation: both would resolve to the same
;; set. rf2-hic-011's timer row was bitten by precisely that.
(rf/reg-sub :red/painted   (fn [db _] (:painted db)))
(rf/reg-sub :red/sibling   (fn [db _] (:sibling db)))
(rf/reg-sub :red/escaped   (fn [db _] (:escaped db)))
(rf/reg-sub :red/escaped-2 (fn [db _] (:escaped-2 db)))

(rf/reg-event :red/seed (fn [_ [_ db]] {:db db}))
(rf/reg-event :red/bump (fn [{:keys [db]} _] {:db (update db :painted inc)}))

;; The UIx adapter, for the reason the package smoke gives and
;; rf2-hic-010 repeats: plain-atom has no reactivity layer, so a
;; subscription under it never notifies and every commit assertion would
;; pass vacuously by never firing. `:ambient-frame nil` because this suite
;; seats its own.
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
  "The deferral carriers this file undertakes to drive.
  [[the-declared-population-was-actually-exercised]] asserts every one was
  reached at runtime, so the roster cannot decay into a list of things the
  suite used to do — a row deleted, a row returning early, a row whose
  poll quietly degrades."
  #{:effect/layout :effect/passive :suspense/retry :activity/reveal})

(defonce ^:private !exercised (atom #{}))

(defn- exercised! [carrier] (swap! !exercised conj carrier) nil)

;; ---------------------------------------------------------------------------
;; Harness
;; ---------------------------------------------------------------------------

(defn- skip!
  [why]
  (is true (str "an I7 browser carrier needs a real React DOM — " why)))

(defn- seeded!
  []
  (set! (.-IS_REACT_ACT_ENVIRONMENT js/globalThis) false)
  (rf/make-frame {:id frame-id})
  (rf/with-frame frame-id
    (rf/dispatch-sync [:red/seed {:painted 1 :sibling 9 :escaped 41 :escaped-2 42}]))
  frame-id)

(defn- k
  "A sub-key: the runtime keys cells by `[frame-kw query-v]`."
  [query-v]
  [frame-id query-v])

(defn- outcome
  "**The one discriminator.** Run `thunk` and report which of the two
  things happened, distinguishably: `{:returned v}` when the read was
  allowed through, `{:refused <ex-data>}` when it was refused.

  A map with two possible keys rather than a predicate, because the two
  failure modes a refusal witness actually has are *the thunk never ran*
  and *something other than the refusal threw* — and both of those look
  like success to a bare `thrown?`. The callers compare the whole ex-data
  map, so a refusal from a different guard fails on the compare instead
  of passing as one.

  [[the-refusal-witness-answers-both-ways]] drives it in both directions,
  so the `:refused` assertions are not a helper that only knows one verb."
  [thunk]
  (try {:returned (thunk)}
       (catch :default e {:refused (ex-data e)})))

(defn- escaped-extent-refusal
  "The exact shape every escape past the render frame must carry: the
  stable id, the emitting function, the actionable recovery, and the query
  that was refused.

  `:reason` is not here and is projected out of every comparison — see the
  namespace docstring. It is prose, rf2-hic-021 owns re-routing it, and
  the Node matrix is where it is pinned."
  [query-v]
  {:rf.error/id :rf.error/hicasso-sub-outside-render
   :where       're-frame.hicasso.impl.collector/read-key!
   :recovery    :read-inside-a-boundary-body
   :query-v     query-v})

(defn- refusal-shape
  "An outcome's refusal, with the unfrozen prose projected out."
  [o]
  (dissoc (:refused o) :reason))

(defn- readers-of [query-v] (count (inventory/cell-readers (k query-v))))

(def ^:private nothing-owned {:cells 0 :cell-refs 0 :boundaries 0 :edges 0})

(defn- ownership [] (dissoc (inventory/residue) :entries))

(defn- app
  "The hicasso subtree: the frame provider over a root element."
  [hiccup]
  (mount/provider frame-id (codec/root-element frame-id hiccup)))

(defn- mount-concurrent!
  "A concurrent root, rendered WITHOUT `flushSync`.

  Deliberately not `mount/root!`, for
  [[re-frame.hicasso.kernel-commit-owns-dom-cljs-test]]'s reason: Suspense
  and Activity are React's concurrent business, and a row that forced them
  synchronous would be a row about the forced schedule. Every wait below
  is a poll on a condition instead."
  [container element]
  (let [root (react-dom-client/createRoot container)]
    (.render root element)
    {:root root :container container :frame frame-id}))

(defn- text-at
  "The text of one node, selected by id. Not the container's whole
  `textContent`: a hidden `Activity` subtree is committed to the DOM and
  contributes to it, so the aggregate would be reading two trees and
  calling it one."
  [handle sel]
  (some-> ^js (.querySelector ^js (:container handle) sel) .-textContent))

(defn- poll
  [pred label]
  (test-support/poll-until pred {:label label :timeout-ms 4000}))

(defn- prove-live!
  "Settle the commit's PASSIVE phase on a condition, never on a duration,
  and prove the acquisition is real in the same act.

  `useSyncExternalStore` calls `subscribe` in a passive effect React
  flushes after the DOM text appears, so a count read the moment the text
  arrives is read before the acquisition it is about. Waiting a chosen
  number of milliseconds would fix that by assumption. So the wait is a
  write round-trip: dispatch, and poll until the boundary repaints. A
  boundary React has not finished subscribing cannot repaint.

  It cannot deadlock on the race it closes — a write that lands before
  `subscribe` reaches nobody, and the boundary heals anyway because the
  cell is then born at a later commit basis than the render's snapshot and
  React's post-subscribe re-read schedules the correcting render. It
  cannot mask a leak either: the condition is orthogonal to the quantity
  under test, since a second reader changes a count without changing any
  text."
  [handle expected label]
  (collector/dispatch! frame-id [:red/bump])
  (poll #(= expected (text-at handle "#painted")) label))

(defn- teardown-census!
  "Unmount, settle at the runtime's own horizon, ASSERT, and only then
  release. `mount/release!` resets the runtime, so a census taken after it
  reads zeros whether teardown released anything or not — the shape of
  gate that cannot go red (`impl.mount/unmount!`, rf2-2rtt6.48)."
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
                   " | ownership " (pr-str (ownership))))
    (when handle (mount/release! handle))
    (done)))

;; ---------------------------------------------------------------------------
;; The views
;; ---------------------------------------------------------------------------

(h/defview sibling-line
  "A second boundary reading a DIFFERENT key, and it is load-bearing
  rather than scenery.

  With one boundary reading one key, a scratch that failed to reset
  between bodies is invisible: `[painted painted]` still resolves to the
  read SET `#{painted}` and the acquired edges are identical. With two
  boundaries the second body's scratch accumulates the first's key, it
  acquires an edge it never read, and `:red/painted` gains a reader
  nothing will ever release. That is a number, and it is the smallest tree
  in which it is one."
  [_]
  [:p {:id "sibling"} (str "sibling=" (h/sub [:red/sibling]))])

(def ^:private !effect-runs (atom 0))
(def ^:private !layout-read (atom nil))
(def ^:private !passive-read (atom nil))

(h/defview effect-deferring-line
  "A boundary that reads legally and then defers two reads into React's
  own post-body phases — the mutation phase (`useLayoutEffect`) and the
  passive phase (`useEffect`).

  The layout effect is the interesting half. It runs *inside* the commit,
  before the browser paints, and it is the moment at which it is most
  tempting to believe the render is still in progress. It is not:
  `run-once`'s `finally` cleared the frame slot when the body returned,
  and the commit is a different extent from the render that proposed it.

  The passive effect also **dispatches**, after its read was refused. That
  is the control that makes this row a statement about reads rather than
  about effects: the same escaped extent, the same instant, and the write
  goes through."
  [_]
  (let [v (h/sub [:red/painted])]
    (react/useLayoutEffect
      (fn []
        (swap! !effect-runs inc)
        (reset! !layout-read (outcome (fn [] (h/sub [:red/escaped-2]))))
        js/undefined)
      #js [])
    (react/useEffect
      (fn []
        (swap! !effect-runs inc)
        (reset! !passive-read (outcome (fn [] (h/sub [:red/escaped]))))
        (collector/dispatch! frame-id [:red/bump])
        js/undefined)
      #js [])
    [:p {:id "painted"} (str "painted=" v)]))

(def ^:private !released?   (atom false))
(def ^:private !resolve-gate (atom nil))
(def ^:private !gate-promise (atom nil))
(def ^:private !attached?   (atom false))
(def ^:private !resume-runs (atom 0))
(def ^:private !resume-read (atom nil))

(defn- arm-gate!
  "A REAL React suspension, armed. Everything after [[release-gate!]] is
  React's own retry machinery — nothing here schedules, re-renders or
  commits anything."
  []
  (reset! !released? false)
  (reset! !attached? false)
  (reset! !resume-runs 0)
  (reset! !resume-read nil)
  (reset! !gate-promise (js/Promise. (fn [res] (reset! !resolve-gate res))))
  nil)

(defn- release-gate! [] (reset! !released? true) (@!resolve-gate nil) nil)

(h/defview suspending-line
  "A boundary that reads, defers a read onto the very thenable it is about
  to throw, and suspends.

  This is the author move the row exists for — *kick the work off, and
  read the subscription once it arrives* — and the continuation is
  attached at the one place where an author would attach it. React
  discards this attempt whole. When the thenable resolves it schedules a
  retry; the continuation runs first, in the microtask, with no body
  anywhere on the stack.

  The attach is guarded by an atom because a body is not a place for a
  side effect and React may run one more than once per commit. The guard
  is what keeps the deferral single, so `1` is an assertable count rather
  than a schedule-dependent one."
  [_]
  (let [v (h/sub [:red/painted])]
    (when-not @!released?
      (when-not @!attached?
        (reset! !attached? true)
        (-> ^js @!gate-promise
            (.then (fn [_]
                     (swap! !resume-runs inc)
                     (reset! !resume-read
                             (outcome (fn [] (h/sub [:red/escaped]))))))))
      (throw @!gate-promise))
    [:p {:id "painted"} (str "painted=" v)]))

(def ^:private !activity-runs (atom 0))
(def ^:private !activity-read (atom nil))
(def ^:private !set-visible (atom nil))

(h/defview activity-deferring-line
  "A boundary inside an `Activity` subtree, deferring a read into the
  effect React withholds for as long as the subtree is hidden."
  [_]
  (let [v (h/sub [:red/painted])]
    (react/useEffect
      (fn []
        (swap! !activity-runs inc)
        (reset! !activity-read (outcome (fn [] (h/sub [:red/escaped]))))
        js/undefined)
      #js [])
    [:p {:id "painted"} (str "painted=" v)]))

(defn- activity-host
  "React 19.2's `<Activity>`, with the mode in state so a test can reveal
  it. Hidden renders the subtree but does not mount its effects; the
  reveal is what mounts them, and the reveal is the deferral's carrier."
  [^js props]
  (let [[visible? set-visible] (react/useState false)]
    (react/useEffect (fn [] (reset! !set-visible set-visible) js/undefined)
                     #js [set-visible])
    (react/createElement (.-Activity react)
                         #js {:mode (if visible? "visible" "hidden")}
                         (unchecked-get props "child"))))

(unchecked-set activity-host "displayName" "red/activity-host")

;; ---------------------------------------------------------------------------
;; 1. A read deferred into a React effect
;; ---------------------------------------------------------------------------

(deftest a-read-deferred-into-a-react-effect-refuses
  (async done
    (if-not (mount/browser?)
      (do (skip! ":node-test has no DOM, so React commits nothing and no effect runs")
          (done))
      (let [_ (seeded!)
            _ (do (reset! !effect-runs 0)
                  (reset! !layout-read nil)
                  (reset! !passive-read nil))
            handle (mount-concurrent!
                     (mount/fresh-container!)
                     (app [:div [effect-deferring-line {}] [sibling-line {}]]))]
        ;; The poll condition is three premises at once: the tree
        ;; committed, the passive effect ran to completion PAST its refused
        ;; read, and the dispatch it made from that same escaped extent
        ;; reached the boundary. `painted=2` cannot appear otherwise.
        (-> (poll #(= "painted=2" (text-at handle "#painted"))
                  "the effect's dispatch lands and the boundary repaints")
            (.then
              (fn [_]
                (testing "the premise: BOTH effects ran. Without it every
                          zero below is the zero of a deferral that never
                          fired"
                  (is (= 2 @!effect-runs)))

                (testing "the layout effect's read refused — inside the
                          commit, before paint, and still outside the body"
                  (is (= (escaped-extent-refusal [:red/escaped-2])
                         (refusal-shape @!layout-read))))

                (testing "and so did the passive effect's"
                  (is (= (escaped-extent-refusal [:red/escaped])
                         (refusal-shape @!passive-read))))

                (testing "the control, and it is what makes this a row about
                          READS: the same escaped extent DISPATCHED, and the
                          write landed. The machinery was demonstrably alive
                          at the instant the reads were refused"
                  (is (= "painted=2" (text-at handle "#painted"))))

                (testing "neither refused key was acquired: no reader, no
                          reaction. A refusal that recorded would paint this
                          identical page and leak a registration for the life
                          of the mount"
                  (is (zero? (readers-of [:red/escaped])))
                  (is (zero? (readers-of [:red/escaped-2])))
                  (is (nil? (inventory/cell-reaction (k [:red/escaped])))))

                (testing "while each body's own read is acquired exactly
                          once, and the runtime holds the two boundaries and
                          nothing else"
                  (is (= 1 (readers-of [:red/painted])))
                  (is (= 1 (readers-of [:red/sibling])))
                  (is (= {:cells 2 :cell-refs 2 :boundaries 2 :edges 2}
                         (ownership))))

                ;; The control that says the refusals are about the EXTENT
                ;; and not about two queries that were broken anyway. The
                ;; identical reads, inside a body's window, resolve to their
                ;; values and record their edges.
                (let [_     (collector/render-body
                              frame-id
                              (fn [_] [:p (h/sub [:red/escaped])
                                       (h/sub [:red/escaped-2])])
                              {})
                      entry (collector/last-reads)]
                  (testing "the identical reads are legal in a window"
                    (is (= #{(k [:red/escaped]) (k [:red/escaped-2])}
                           (collector/reads-of entry)))))

                (exercised! :effect/layout)
                (exercised! :effect/passive)
                (teardown-census! handle)))
            (.then (fn [_] (done)))
            (.catch (fail-and-finish! done "react-effect witness" handle)))))))

;; ---------------------------------------------------------------------------
;; 2. A read deferred across a Suspense retry
;; ---------------------------------------------------------------------------

(deftest a-read-deferred-across-a-suspense-retry-refuses
  (async done
    (if-not (mount/browser?)
      (do (skip! ":node-test has no DOM, so nothing suspends and nothing retries")
          (done))
      (let [_      (seeded!)
            _      (arm-gate!)
            before (collector/body-runs)
            handle (mount-concurrent!
                     (mount/fresh-container!)
                     (react/createElement
                       (.-Suspense react)
                       #js {:fallback (react/createElement
                                        "p" #js {:id "waiting"} "waiting")}
                       (app [:div [suspending-line {}] [sibling-line {}]])))]
        (-> (poll #(some? (text-at handle "#waiting")) "the fallback commits")
            (.then
              (fn [_]
                (testing "the premise: React RAN the body — it took its read
                          and then threw the thenable — and then threw the
                          attempt away"
                  (is (pos? (- (collector/body-runs) before)))
                  (is (nil? (text-at handle "#painted"))))

                (testing "and the abandoned attempt acquired nothing, so the
                          counts asserted after the retry are the retry's own"
                  (is (= nothing-owned (ownership))))

                (release-gate!)
                (poll #(and (= 1 @!resume-runs)
                            (= "painted=1" (text-at handle "#painted")))
                      "the deferred continuation runs and the retry commits")))
            (.then
              (fn [_]
                (prove-live! handle "painted=2"
                             "the retried boundary is subscribed and live")))
            (.then
              (fn [_]
                (testing "the premise: the continuation ran exactly once"
                  (is (= 1 @!resume-runs)))

                (testing "and its read refused. React was mid-retry, the
                          boundary it belongs to is mounted and live, and
                          there is still no body on the stack"
                  (is (= (escaped-extent-refusal [:red/escaped])
                         (refusal-shape @!resume-read))))

                (testing "the refused key was not acquired by the retry that
                          followed it"
                  (is (zero? (readers-of [:red/escaped])))
                  (is (nil? (inventory/cell-reaction (k [:red/escaped])))))

                (testing "and the retry acquired exactly its own read set:
                          one reader per key, with no second registration
                          from the attempt React discarded"
                  (is (= 1 (readers-of [:red/painted])))
                  (is (= 1 (readers-of [:red/sibling])))
                  (is (= {:cells 2 :cell-refs 2 :boundaries 2 :edges 2}
                         (ownership))))

                (exercised! :suspense/retry)
                (teardown-census! handle)))
            (.then (fn [_] (done)))
            (.catch (fail-and-finish! done "suspense-retry witness" handle)))))))

;; ---------------------------------------------------------------------------
;; 3. A read deferred across an Activity reveal
;; ---------------------------------------------------------------------------

(deftest a-read-deferred-across-an-activity-reveal-refuses
  (async done
    (if-not (mount/browser?)
      (do (skip! ":node-test has no DOM, so nothing is hidden and nothing is revealed")
          (done))
      (let [_ (seeded!)
            _ (do (reset! !activity-runs 0)
                  (reset! !activity-read nil)
                  (reset! !set-visible nil))
            handle (mount-concurrent!
                     (mount/fresh-container!)
                     (react/createElement activity-host
                                          #js {:child (app [activity-deferring-line {}])}))]
        ;; The host's own effect is the sync point rather than any text:
        ;; a hidden Activity subtree IS committed to the DOM, so reading
        ;; text here would not distinguish hidden from revealed.
        (-> (poll #(some? @!set-visible) "the host commits with the subtree hidden")
            (.then
              (fn [_]
                (testing "hidden: the deferral has NOT fired. React withholds
                          the effect mount for as long as the subtree is
                          hidden, and this is the half of the row that says
                          the reveal is a real carrier and not a relabelled
                          mount"
                  (is (zero? @!activity-runs)))

                (@!set-visible true)
                (poll #(= 1 @!activity-runs) "the reveal mounts the effect")))
            (.then
              (fn [_]
                (prove-live! handle "painted=2"
                             "the revealed boundary is subscribed and live")))
            (.then
              (fn [_]
                (testing "the premise: the reveal ran the deferral exactly
                          once"
                  (is (= 1 @!activity-runs)))

                (testing "and its read refused, with the same identity every
                          other escape carries"
                  (is (= (escaped-extent-refusal [:red/escaped])
                         (refusal-shape @!activity-read))))

                (testing "the refused key was not acquired at the reveal"
                  (is (zero? (readers-of [:red/escaped])))
                  (is (nil? (inventory/cell-reaction (k [:red/escaped])))))

                (testing "while the revealed boundary holds exactly its own
                          read, once"
                  (is (= 1 (readers-of [:red/painted]))))

                (exercised! :activity/reveal)
                (teardown-census! handle)))
            (.then (fn [_] (done)))
            (.catch (fail-and-finish! done "activity-reveal witness" handle)))))))

;; ---------------------------------------------------------------------------
;; The controls that make the greens worth having
;; ---------------------------------------------------------------------------

(deftest the-refusal-witness-answers-both-ways
  ;; No DOM needed and none taken: this is about the instrument, and it
  ;; runs on both lanes so the node lane also proves the helper can report
  ;; something other than a refusal. If [[outcome]] and
  ;; [[escaped-extent-refusal]] could only ever describe a refusal, every
  ;; green above would be the instrument's silence rather than the
  ;; runtime's conduct.
  (seeded!)
  (let [!seen (atom nil)]
    (collector/render-body
      frame-id
      (fn [_]
        (reset! !seen (outcome (fn [] (h/sub [:red/escaped]))))
        [:p "x"])
      {})

    (testing "the IDENTICAL read, inside a body's window, is reported as
              ALLOWED and carries the value — so the helper discriminates,
              and every refusal above is about the extent rather than
              about a query that was broken anyway"
      (is (contains? @!seen :returned))
      (is (= 41 (:returned @!seen)))
      (is (not (contains? @!seen :refused)))))

  (testing "and the shape constructor discriminates on the query, so no row
            can pass on somebody else's refusal"
    (is (not= (escaped-extent-refusal [:red/escaped])
              (escaped-extent-refusal [:red/escaped-2])))))

(deftest the-declared-population-was-actually-exercised
  ;; Declared LAST so every row above has run.
  (if-not (mount/browser?)
    (skip! ":node-test has no DOM, so no carrier is driven there")
    (is (= declared-population @!exercised)
        (str "every declared deferral carrier must be reached; missing: "
             (pr-str (set/difference declared-population @!exercised))))))
