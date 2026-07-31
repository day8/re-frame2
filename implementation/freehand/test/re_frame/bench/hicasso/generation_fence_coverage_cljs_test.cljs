(ns re-frame.bench.hicasso.generation-fence-coverage-cljs-test
  "WHAT THE GENERATION FENCE COVERS, AND WHAT ESCAPES IT (rf2-2rtt6.33).

  `docs/design/hicasso/hd-002-adjudication.md` §6.1 leaves one question
  **[OPEN]**, and calls it the substitution everything else in §2 rests
  on: the predecessor's commit-side re-read compared **three** things —
  node identity (`:node-key`), version, and the frame/registry epochs —
  between the render that produced an element and the commit about to
  publish it; Hicasso replaces the whole of it with one generation
  comparison per boundary. Nobody had shown that the one covers the
  three.

  This file settles it, and the answer is **no**. It is not a near miss
  and it is not an arithmetic gap — the fence and the re-read do not
  guard the same window, so the question 'which of the three does it
  cover' has the same answer for all three.

  ## The two windows

      predecessor    render probes a site … COMMIT re-reads that site
                     |<-------- the render->commit gap -------->|

      generation     capture gen … BODY RUNS … compare gen
      fence          |<---- one body run ---->|      … then the body returns

  `render-body` (Arm 1's `arm1/runtime.cljs`, the `loop` at its
  `(let [before (generation)] …)`) captures the generation, runs the
  body, compares, and — on a match — RETURNS. Nothing compares anything
  again. The commit that follows (React calling the read-set entry's
  `subscribe`, which is where `acquire-cell!` installs the edge and the
  watch) performs no comparison at all, by design: `#no commit-phase
  deref` is the arm's stated fence.

  So the fence covers a hazard the predecessor's three fields never
  addressed — a commit landing BETWEEN TWO READS OF ONE BODY, which the
  predecessor got for free because it probed per site and re-read per
  site — and it does not reach the render->commit gap, which is the only
  window the three fields were ever about. One-for-one on a different
  axis, not one-for-three.

  ## What does reach the gap, and how far

  Arm 1 is not defenceless there. Its shell hands
  `useSyncExternalStore` a `getSnapshot` that returns the **sum of the
  read set's epochs**, and React's own commit-time snapshot re-check
  forces a re-render when that number moves. That is a real second
  channel and this file's first row proves it works. But it is driven by
  the same counter the fence is: an epoch moves only when `flush!`
  bumps it, `flush!` runs only from `mark-dirty!`, and `mark-dirty!` has
  exactly one caller — the value-change watch that `acquire-cell!`
  installs **at commit**. Therefore:

  - **version axis** — covered for a **retained** key (some boundary
    already committed it, so the watch pre-dates the move). NOT covered
    for a **staged** key: a key nothing holds yet has no cell, no watch
    and no epoch, so a move in the gap marks nothing, bumps nothing, and
    leaves `getSnapshot` returning the same number before and after the
    commit. Row 2.
  - **frame/registry-epoch axis** — no counterpart at all. A registry
    epoch move is not a value change on an acquired reaction, so it
    cannot reach `mark-dirty!`. Row 3.
  - **node-key axis** — no counterpart at all, by the same argument:
    nothing in the runtime observes node identity, and the sole writer of
    the generation is the value watch. Stated rather than staged, because
    a third row would re-prove row 3's arithmetic with a longer fixture.

  Spec 006 §The six frozen invariants, invariant 5 already names the
  staged case as the worse one, in the same words: 'a **staged** site
  publishes stale and **nothing ever corrects it**, because a
  dependency's *first* render has no earlier channel to heal through.'
  Row 2 is that sentence, executed against Arm 1's counter.

  ## Why this file transcribes rather than requires

  Arm 1's runtime is on a spike branch (HD-017 keeps runtime skeletons
  off main until the P2 ruling), and the arm owns that surface. The
  three functions the answer turns on are therefore **transcribed** here
  — `flush!`/`mark-dirty!` (the generation and the epoch bump),
  `acquire-cell!` (commit-only acquisition, baseline deref, watch), and
  the epoch-sum `getSnapshot` — over the REAL sub layer, the REAL frames
  and REAL watches. What is deliberately dropped is everything that
  cannot change the answer: the index, the read-set entry cache, the
  scratch array, React. Each transcribed function names its original in
  a comment; `rf2-2rtt6.34` re-points these rows at the arm's own
  runtime once it is on main.

  The host is the React spine (`re-frame.adapter.uix`), not the
  plain-atom substrate, and that is load-bearing for the same reason
  `shell_tear_dom_cljs_test.cljs` gives: on an unwatchable host row 2's
  clean counter is trivially clean and says nothing. Row 1 establishes
  that this host really does notify a committed key, and only then does
  row 2 read a still counter as evidence."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.adapter.uix :as react-substrate]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.live-frame :as live-frame]
            [re-frame.subs :as subs]
            [re-frame.test-support :as test-support]))

;; ---------------------------------------------------------------------------
;; The transcribed generation machinery
;; ---------------------------------------------------------------------------
;;
;; Arm 1 keeps these on JS objects because they are what its heap ladder
;; prices. Nothing here measures heap, so they are ordinary atoms — the
;; algebra is identical and the file carries no externs risk.

(defonce ^:private !cells
  ;; sub-key -> {:reaction r :watch-key k}. Arm 1's `!cells`: one cell per
  ;; unique (frame, query), created and acquired ONLY at commit.
  (atom {}))

(defonce ^:private !epochs
  ;; sub-key -> int. Arm 1 keeps this on the cell as `.-epoch`; a key with
  ;; no cell has no epoch, which is the whole of row 2.
  (atom {}))

(defonce ^:private !dirty (atom #{}))
(defonce ^:private !generation (atom 0))
(defonce ^:private !watch-counter (atom 0))

(defn- flush!
  "Arm 1's `flush!`: bump each dirty key's epoch and bump the generation.
  The ONLY writer of the generation, and it does nothing when the dirty
  set is empty."
  []
  (let [dirty @!dirty]
    (when (seq dirty)
      (reset! !dirty #{})
      (swap! !generation inc)
      (swap! !epochs (fn [m] (reduce (fn [m k] (update m k (fnil inc 0))) m dirty)))))
  nil)

(defn- mark-dirty!
  "Arm 1's `mark-dirty!`. Its ONLY caller is the value-change watch below,
  which is the fact rows 2 and 3 turn on."
  [sub-key]
  (swap! !dirty conj sub-key)
  (flush!)
  nil)

(defn- render-read!
  "Arm 1's `read-key!`, render phase. Warm — a committed cell holds the key
  — is a pure deref. Cold is `subscribe-once`, which subscribes, derefs and
  unsubscribes inside this tick and retains nothing, so an abandoned render
  leaves the world as it found it."
  [frame-kw query-v]
  (let [sub-key [frame-kw query-v]]
    (if-some [cell (get @!cells sub-key)]
      @(:reaction cell)
      (subs/subscribe-once query-v {:frame frame-kw}))))

(defn- commit-acquire!
  "Arm 1's `acquire-cell!` — **commit-phase only**, and the seam React
  reaches through the read-set entry's `subscribe`. One baseline deref at
  construction, then the watch that turns the sub layer's equality cutoff
  into the dirty set. No tear check: the arm acquires without comparing
  what it read against what the render read."
  [frame-kw query-v]
  (let [sub-key [frame-kw query-v]]
    (or (get @!cells sub-key)
        (let [reaction (subs/subscribe query-v {:frame frame-kw})
              watch-key (keyword "rf-genfence-witness" (str "w" (swap! !watch-counter inc)))]
          @reaction
          (add-watch reaction watch-key
                     (fn [_ _ old nu] (when-not (= old nu) (mark-dirty! sub-key))))
          (swap! !epochs assoc sub-key 0)
          (swap! !cells assoc sub-key {:reaction reaction :watch-key watch-key})
          (get @!cells sub-key)))))

(defn- get-snapshot
  "Arm 1's `getSnapshot` — the sum of the read set's epochs. Monotone, so
  React's `Object.is` on it is a correct change test; a key with no cell
  contributes nothing, which is exactly what row 2 exploits."
  [sub-keys]
  (reduce (fn [acc k] (+ acc (get @!epochs k 0))) 0 sub-keys))

(defn- reset-model! []
  (doseq [[sub-key cell] @!cells]
    (remove-watch (:reaction cell) (:watch-key cell))
    (subs/unsubscribe (nth sub-key 0) (nth sub-key 1)))
  (reset! !cells {})
  (reset! !epochs {})
  (reset! !dirty #{})
  (reset! !generation 0)
  nil)

;; ---------------------------------------------------------------------------
;; Fixture and seams
;; ---------------------------------------------------------------------------

(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter       react-substrate/adapter
     :ambient-frame nil
     :init-fn       reset-model!}))

(def ^:private q [:genfence/v])

(defn- make-frame!
  "Each row builds its OWN frame: two rows sharing one frame id share its
  app-db and so share each other's order."
  [id db]
  (live-frame/make-frame {:id id})
  (frame/replace-app-db! id db)
  id)

;; ---------------------------------------------------------------------------
;; Row 1 — host honesty. A RETAINED key moving in the gap does move the counter
;; ---------------------------------------------------------------------------

(deftest a-retained-key-moving-in-the-gap-moves-the-generation-and-the-epoch-sum
  (testing "The precondition for row 2, and the honest half of the answer.
            A key some earlier boundary already committed has a cell, and
            that cell's watch was installed by that EARLIER commit — before
            the move. So a move in this boundary's render->commit gap does
            reach `mark-dirty!`, does bump the generation and the key's
            epoch, and therefore does move the number React re-checks at
            commit. The version axis IS covered here.

            Without this row on the board, row 2's still counter would be
            trivially still and would say nothing at all."
    (rf/reg-sub (first q) (fn [db _] (:v db)))
    (let [f       (make-frame! ::retained {:v 1})
          sub-key [f q]]
      ;; An earlier boundary's commit. This is the whole difference from row 2.
      (commit-acquire! f q)
      (let [published   (render-read! f q)
            gen-before  @!generation
            snap-before (get-snapshot [sub-key])]
        (is (= 1 published) "the render read the value that was true when it ran")
        ;; THE GAP: the source moves after this render read it and before the
        ;; commit that would install its edge.
        (frame/replace-app-db! f {:v 2})
        (is (= (inc gen-before) @!generation)
            "the generation moved — the watch this key's EARLIER commit
             installed fired, so this host notifies")
        (is (not= snap-before (get-snapshot [sub-key]))
            "and the epoch sum moved with it, so React's own commit-time
             getSnapshot re-check sees a changed store and re-renders")
        (is (= 2 (render-read! f q))
            "a re-run therefore reads the value that is now true")))))

;; ---------------------------------------------------------------------------
;; Row 2 — the tear. A STAGED key moving in the gap moves NOTHING
;; ---------------------------------------------------------------------------

(deftest invariant-5-a-staged-key-moving-in-the-gap-moves-neither-the-generation-nor-the-epoch-sum
  (testing "hd-002-adjudication.md §6.1's adversarial witness, and the
            answer to it. The subscription's VALUE changes between a
            boundary's render and its commit, and it changes WITHIN ONE
            GENERATION — because the generation cannot move.

            The key is STAGED: nothing holds it yet, so there is no cell,
            no watch and no epoch. The move therefore marks nothing. The
            commit then acquires, and installs the watch one move too late
            to have reported it. Both counters read exactly what they read
            at render, before and after the commit, so neither the fence
            nor React's own getSnapshot re-check has anything to see — and
            the value the render published is not the value that is true.

            Spec 006 invariant 5 names this as the case with no second
            channel. This row is that case, executed."
    (rf/reg-sub (first q) (fn [db _] (:v db)))
    (let [f       (make-frame! ::staged {:v 1})
          sub-key [f q]]
      (is (nil? (get @!cells sub-key))
          "STAGED, not retained: nothing holds this key, so `commit-acquire!`
           must run inside the commit below")
      (let [published   (render-read! f q)
            gen-before  @!generation
            snap-before (get-snapshot [sub-key])]
        (is (= 1 published) "the render read, and this is what it put on screen")
        (is (zero? snap-before) "a key with no cell contributes no epoch")
        ;; THE GAP.
        (frame/replace-app-db! f {:v 2})
        (is (empty? @!dirty)
            "the move marked NOTHING — there was no watch yet to fire, so the
             ordinary invalidation path is not going to correct this")
        (is (= gen-before @!generation)
            "so the generation did not move: the change happened WITHIN ONE
             GENERATION, which is precisely the witness §6.1 asks for")
        (is (= snap-before (get-snapshot [sub-key]))
            "and the epoch sum did not move either")
        ;; THE COMMIT: React calls `subscribe`, the edge is installed, the
        ;; watch is armed — after the move it would have reported.
        (commit-acquire! f q)
        (is (= gen-before @!generation)
            "the commit compares nothing, so the generation is still the
             render's")
        (is (= snap-before (get-snapshot [sub-key]))
            "and getSnapshot answers the SAME number it answered at render, so
             React's commit-time re-check finds no tear and schedules no
             re-render")
        (is (= 2 (render-read! f q))
            "meanwhile the value that is true has moved")
        (is (not= published (render-read! f q))
            "so `published` — what this boundary put on screen — is STALE")
        (is (empty? @!dirty)
            "and nothing is pending: the watch's baseline is the NEW value, so
             no later notification is coming for a change that already
             happened. Invariant 5's 'nothing ever corrects it'.")))))

;; ---------------------------------------------------------------------------
;; Row 3 — the epoch axis has no counterpart in the generation at all
;; ---------------------------------------------------------------------------

(deftest the-generation-has-no-registry-epoch-axis
  (testing "The predecessor's third field. `obs/read` reports the registry
            epoch on every live node and `moved?` compares it, so a handler
            re-registration between a render and its commit corrects before
            paint there.

            Here there is nothing to compare it against. The sole writer of
            the generation is `flush!`, its sole caller is `mark-dirty!`,
            and `mark-dirty!`'s sole caller is the per-cell VALUE watch. A
            registry epoch move is not a value change on an acquired
            reaction, so it cannot reach any of them — and the same
            argument, unchanged, covers the `:node-key` axis, whose mover
            (a same-id frame reincarnation) is likewise not a value change
            on an acquired reaction."
    (rf/reg-sub (first q) (fn [db _] (:v db)))
    (let [f (make-frame! ::registry {:v 1})]
      (commit-acquire! f q)
      (let [published  (render-read! f q)
            gen-before @!generation]
        (is (= 1 published))
        ;; A registry-epoch move: the same query, a different computation.
        (rf/reg-sub (first q) (fn [db _] (* 10 (:v db))))
        (is (= gen-before @!generation)
            "the generation did not move — it has no registry-epoch axis, and
             no epoch bumped, so React's getSnapshot re-check is equally blind")
        (is (empty? @!dirty)
            "and nothing was marked dirty either")))))
