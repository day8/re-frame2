(ns re-frame.bench.hicasso.arm1.generation-fence-dom-cljs-test
  "THE GENERATION FENCE, AND ITS STAGED-STALE WITNESS (rf2-2rtt6.9).

  architecture.md gives Arm 1 one line on this and it is the load-bearing
  one: \"A generation fence keeps all reads within one render pass on one
  commit (invariant-5 preservation; the staged-stale CI witness guards
  it).\" validation.md adds the stakes — \"a missed invalidation is a P0
  bug class: the staged-stale case is a CI witness for any asynchronous-
  host variant.\"

  ## What the fence replaces, and why that matters

  The predecessor preserved the same invariant by **re-reading every
  subscription at commit** — a second deref of every read, after the
  render had already read it, measured at 1.19 ms of a 4.0 ms write for
  300 reads against Reagent's entire layout-effect phase of 1.4
  microseconds. HD-002's adjudication makes that re-read a *forbidden*
  construct for this arm, which leaves the fence carrying the invariant
  on its own: one comparison per boundary, O(1), instead of one deref per
  read, O(reads).

  A fence that has never been watched refuse is not evidence, so these
  tests stage the case rather than describing it.

  ## The two staged cases

  1. **A commit lands inside a body.** The boundary reads, a write is
     staged mid-body, and the body reads again. Without the fence its two
     reads straddle two commits and the DOM publishes a value that was
     never simultaneously true. With it, the body re-runs and the
     committed DOM is the winning render's.
  2. **A commit lands between a body and its React commit** — the case
     hd-002-adjudication.md §6.1 leaves open, and the one the record does
     not demonstrate. It is staged here against a real React root, and
     the assertion is the honest one: the committed DOM is not stale.

  Runtime: `-dom-cljs-test`, so `:browser-test` runs it against a real
  React DOM; under `:node-test` every claim degrades to a stated skip.
  The fence's own algebra is proved without a browser in
  `arm1/runtime_cljs_test`."
  (:require [cljs.test :refer-macros [async deftest is testing use-fixtures]]
            [re-frame.adapter.uix :as uix-adapter]
            [re-frame.bench.hicasso.arm1.mount :as mount]
            [re-frame.bench.hicasso.arm1.runtime :as rt]
            [re-frame.bench.hicasso.front.dogfood :as dogfood]
            [re-frame.bench.hicasso.lane :as lane]
            [re-frame.core :as rf]
            [re-frame.test-support :as test-support])
  (:require-macros [re-frame.bench.hicasso.arm1.lang :refer [defview]]))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter uix-adapter/adapter
     ;; `:ambient-frame nil` is load-bearing, not tidiness. The fixture's
     ;; default leaves a dynamic-var frame stamp in scope, and the
     ;; carried-invariant chain resolves that tier BEFORE React context —
     ;; so the comparator's ambient `use-subscribe` would read the
     ;; ambient frame's app-db while `use-current-frame` reported the
     ;; provider's, and a parity miss would look like a rendering
     ;; difference. Caught by the frame probe below, which is why the
     ;; probe stays.
     :ambient-frame nil
     ;; The map shape, because the teardown claim is `async`: the cell and
     ;; entry reapers are macrotasks, so the residue a React unmount leaves
     ;; is not readable inside one synchronous test body.
     :async?  true
     :init-fn (fn [] (rt/reset-runtime!))}))

(def ^:private frame-id ::arm1-fence)

(defonce ^:private !runs (atom 0))
(defonce ^:private !stage (atom nil))

(defview staged-row
  "A boundary that reads, may stage a commit, and reads again. The stage
  is a fn in an atom rather than a prop so the test can arm it for
  exactly one run — a body that writes on every run is a write loop, and
  the fence fails that loudly rather than spinning (proved in the node
  suite)."
  [{:keys [id]}]
  ;; The filter read is what lets the test make React re-render this
  ;; boundary WITHOUT touching the subscription the body is about to
  ;; write — a boundary re-rendered by the very write it stages would not
  ;; separate the fence from ordinary invalidation. The read set is the
  ;; same on both runs, so the entry (and React's `subscribe`) is stable
  ;; across the re-run.
  (let [_filter (rt/sub [:dogfood/filter])
        before  (rt/sub [:dogfood/done? id])]
    (when-some [stage @!stage] (reset! !stage nil) (stage))
    (let [after (rt/sub [:dogfood/done? id])]
      (swap! !runs inc)
      [:li.row {:data-id id
                :data-before (str before)
                :data-after (str after)}
       (str after)])))

(defn- skip! [why] (is true (str "a staged-stale claim needs a real React DOM — " why)))

(defn- mounted! []
  (reset! !runs 0)
  (reset! !stage nil)
  (lane/leave-act-environment!)
  (dogfood/make-frame! frame-id 3)
  (dogfood/reseed! frame-id 3)
  (let [container (mount/fresh-container!)]
    (mount/root! container frame-id [staged-row {:id 0}])))

(defn- read-back [handle k]
  (some-> (.querySelector (:container handle) ".row") (.getAttribute k)))

;; ---------------------------------------------------------------------------
;; Case 1 — a commit lands inside the body
;; ---------------------------------------------------------------------------

(deftest a-commit-staged-inside-a-body-never-publishes-a-straddled-read
  (if-not (mount/browser?)
    (skip! ":node-test has no DOM")
    (let [handle (mounted!)]
      (try
        (is (= "false" (read-back handle "data-after")) "the seeded value is on screen")
        (let [runs-before @!runs]
          ;; Arm the stage, then make React re-render this boundary. The
          ;; body will write to the very subscription it is reading.
          (reset! !stage (fn [] (rt/dispatch! frame-id [:dogfood/toggle 0])))
          (mount/dispatch! handle [:dogfood/set-filter :done])
          (is (> @!runs runs-before) "the body ran"))
        (testing "the two reads of the winning run agree, so the DOM never
                 carries a value that was not simultaneously true"
          (is (= (read-back handle "data-before") (read-back handle "data-after"))))
        (testing "and the committed DOM is the POST-write value, not the
                 pre-write one the abandoned run started from"
          (is (= "true" (read-back handle "data-after"))))
        (finally (mount/release! handle))))))

;; ---------------------------------------------------------------------------
;; Case 2 — a commit lands between the body and React's commit (§6.1)
;; ---------------------------------------------------------------------------

(deftest a-commit-landing-after-the-body-does-not-leave-the-dom-stale
  (if-not (mount/browser?)
    (skip! ":node-test has no DOM")
    (let [handle (mounted!)]
      (try
        (is (= "false" (read-back handle "data-after")))
        ;; hd-002-adjudication.md §6.1 leaves open whether one epoch
        ;; comparison covers what the predecessor's three compared fields
        ;; covered. This is the adversarial half it names: move the value
        ;; AFTER the body has returned. The fence cannot see this one — its
        ;; window has closed — so what must catch it is the ordinary
        ;; invalidation path, and the claim is that between the two the DOM
        ;; is never left stale.
        (mount/dispatch! handle [:dogfood/toggle 0])
        (mount/settle!)
        (is (= "true" (read-back handle "data-after"))
            "the committed DOM caught up without a commit-phase re-read of
             every subscription")
        (is (= (read-back handle "data-before") (read-back handle "data-after")))
        (finally (mount/release! handle))))))

;; ---------------------------------------------------------------------------
;; Case 3 — a STAGED read moves in the render->commit gap (rf2-2rtt6.42)
;; ---------------------------------------------------------------------------
;;
;; Case 2 above is LABELLED as §6.1's case and is not it: it dispatches
;; after the mount has settled, so the key is already RETAINED — some
;; commit installed its watch before the move, and the ordinary
;; invalidation path carries it. The case §6.1 actually asks for is a key
;; **nothing holds yet**, moving before the commit that would acquire it.
;; Nothing marks, nothing bumps, and the boundary has already painted.
;;
;; Staged here the way the reachability argument says it happens in an
;; application: a SIBLING boundary writes during the same React render
;; pass, after the reading boundary's fence has closed. That needs no
;; guess about when React runs a passive effect — the write is strictly
;; inside the render, and `subscribe` runs strictly after it.

(defonce ^:private !sibling-writes (atom 0))

(defview staged-reader
  "Renders FIRST, and reads a key nothing else holds."
  [{:keys [id]}]
  (let [v (rt/sub [:dogfood/done? id])]
    [:li.row {:data-id id :data-after (str v) :data-before (str v)} (str v)]))

(defview sibling-writer
  "Renders SECOND, in the same pass, and writes to the key its sibling
  just read. Once — a body that writes on every run is a write loop, and
  the fence fails that loudly rather than spinning."
  [{:keys [id]}]
  ;; A read of its own, so this is an ordinary boundary rather than a
  ;; contrivance with no edges.
  (rt/sub [:dogfood/filter])
  (when (zero? @!sibling-writes)
    (swap! !sibling-writes inc)
    (rt/dispatch! frame-id [:dogfood/toggle id]))
  [:li.writer])

(deftest a-staged-read-that-moves-before-the-commit-is-corrected-in-the-dom
  (if-not (mount/browser?)
    (skip! ":node-test has no DOM")
    (do
      (reset! !sibling-writes 0)
      (lane/leave-act-environment!)
      (dogfood/make-frame! frame-id 3)
      (dogfood/reseed! frame-id 3)
      (let [handle (mount/root! (mount/fresh-container!) frame-id
                                [:ul [staged-reader {:id 0}] [sibling-writer {:id 0}]])]
        (try
          (is (= 1 @!sibling-writes) "the sibling wrote, exactly once")
          (mount/settle!)
          (mount/settle!)
          (testing "the reader's key had no cell, no watch and no epoch when
                   it moved, so nothing was marked and the generation did
                   not move — and the DOM must still not be stale"
            (is (= "true" (read-back handle "data-after"))
                "the boundary was corrected: React re-read the store after
                 `subscribe` and found a number that had moved"))
          (finally (mount/release! handle)))))))

;; ---------------------------------------------------------------------------
;; Case 4 — a first REGISTRATION lands in the gap (rf2-2rtt6.50)
;; ---------------------------------------------------------------------------
;;
;; Case 3 moves a staged read's VALUE in the gap. This one moves its
;; COMPUTATION: the reading boundary's query is not registered when its
;; body runs, so the read takes the substrate's nil-recovery, and the
;; registration lands before React acquires the edge. Neither the flush
;; generation nor the frame's install epoch moves for a `reg-sub`, and
;; the boundary has no cell for `first-registration!` to reach — so
;; without the basis's registry term React re-reads the number the fiber
;; captured at render, finds no tear, and the boundary paints the
;; recovery's `nil` until the next write to the frame.
;;
;; Staged the way Case 3 is, and for the same reason: a SIBLING boundary
;; acting during the same React render pass, after the reading boundary's
;; fence has closed. That needs no guess about when React runs a passive
;; effect — the registration is strictly inside the render, and
;; `subscribe` runs strictly after it. This is the lazily-loaded-module
;; shape, which is the one the reachability argument names.

(def ^:private gap-q [::lazily-registered])

(defonce ^:private !gap-registrations (atom 0))

(defview gap-reader
  "Renders FIRST, and reads a query NOTHING has registered."
  [_]
  (let [v (rt/sub gap-q)]
    [:li.row {:data-after (str v) :data-before (str v)} (str v)]))

(defview gap-registrar
  "Renders SECOND, in the same pass, and registers the sub its sibling
  just read. Once — a body that registers on every run moves the basis on
  every run, which is a write loop, and the fence fails that loudly rather
  than spinning."
  [_]
  ;; A read of its own, so this is an ordinary boundary rather than a
  ;; contrivance with no edges.
  (rt/sub [:dogfood/filter])
  (when (zero? @!gap-registrations)
    (swap! !gap-registrations inc)
    (rf/reg-sub (first gap-q) (fn [_ _] :arrived)))
  [:li.writer])

(deftest a-first-registration-landing-in-the-gap-is-corrected-in-the-dom
  (if-not (mount/browser?)
    (skip! ":node-test has no DOM")
    (do
      (reset! !gap-registrations 0)
      (lane/leave-act-environment!)
      (dogfood/make-frame! frame-id 3)
      (dogfood/reseed! frame-id 3)
      (let [handle (mount/root! (mount/fresh-container!) frame-id
                                [:ul [gap-reader {}] [gap-registrar {}]])]
        (try
          (is (= 1 @!gap-registrations) "the sibling registered, exactly once")
          (mount/settle!)
          (mount/settle!)
          (testing "the reader's query had no handler when its body ran and no
                   cell when the registration landed, so `first-registration!`
                   reached nothing on its behalf and neither the generation nor
                   the frame's install epoch moved — and the DOM must still not
                   be stale"
            (is (= ":arrived" (read-back handle "data-after"))
                "the boundary was corrected inside the mount: React re-read the
                 store after `subscribe`, found a number the registry term had
                 moved, and re-rendered through the cell the commit acquired
                 against the live registration"))
          (finally (mount/release! handle)))))))

;; ---------------------------------------------------------------------------
;; Teardown — **React's** teardown, read while the runtime still holds it
;; ---------------------------------------------------------------------------
;;
;; `mount/unmount!` rather than `mount/release!`, because `release!` resets
;; the runtime and a reading taken after that reset answers zero whatever
;; teardown did — the ordering that made this gate unable to fail
;; (rf2-2rtt6.48). The macrotask wait is the cell and entry reapers'
;; deliberate grace period, not slack.

(deftest the-fenced-boundary-leaves-no-residue
  (if-not (mount/browser?)
    (skip! ":node-test has no DOM")
    (async done
      (let [handle (mounted!)]
        (mount/dispatch! handle [:dogfood/toggle 0])
        (is (pos? (:cell-refs (rt/stats)))
            "the mounted boundary holds references, so the reading below is
             a reading of something")
        (mount/unmount! handle)
        (js/setTimeout (fn []
                         (is (= {:cells 0 :cell-refs 0 :boundaries 0 :edges 0 :entries 0}
                                (rt/residue))
                             "React's own cleanup released every edge and every
                              subscription reference")
                         (rt/reset-runtime!)
                         (done))
                       8)))))
