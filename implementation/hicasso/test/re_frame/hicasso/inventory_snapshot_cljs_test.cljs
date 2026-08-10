(ns re-frame.hicasso.inventory-snapshot-cljs-test
  "THE INSTRUMENT'S OWN CONTRACT — `cell-readers` answers a SNAPSHOT
  (rf2-0oy4).

  Every reader-counting row in this package captures
  [[re-frame.hicasso.impl.inventory/cell-readers]], performs an action,
  and compares. That shape is only a witness if the captured value is a
  value. If it is a live view of the cell's reader array, the baseline
  mutates into the result and the comparison is between a thing and
  itself — a correct runtime reads exactly like a leak, and, by symmetry,
  a leaking one reads clean. That is not hypothetical: rf2-vsgq's HMR
  baseline was read that way, and this file is the bead that came of it.

  ## Why `vec` was not enough

  `cljs.core/vec` says so itself: *\"JavaScript arrays will be aliased and
  should not be modified.\"* On an array it calls
  `PersistentVector.fromArray` with `no-clone` true, and for a length
  under 32 — which is every fan-out this table sees — the vector's TAIL
  **is** the array handed in. The collector then `.push`es and `.splice`s
  that same array in place (`acquire-cell!` and `release-cell!` in
  [[re-frame.hicasso.impl.collector]]), so a caller holding the
  \"snapshot\" watches it change.

  ## What an aliased snapshot actually does — it is INCOHERENT, not stale

  Measured rather than reasoned, because the reasoning was wrong the first
  time. `cnt` is fixed when the vector is built; the tail is not. The two
  families of vector operation disagree about which bound they trust, so
  they disagree about the same value:

  - `count`, `nth` and `=` are bounded by `cnt` alone. They keep the
    length the snapshot was born with, and they read whatever now sits at
    those indices. An arrival is invisible to them; a departure shifts a
    later reader into an earlier slot under their feet.
  - `reduce` — and so `mapv`, `into`, `set`, everything built on it —
    bounds its OUTER walk by `cnt` but walks each chunk by the tail's
    LIVE `alength`. It sees an arrival that `count` cannot, and after a
    departure it re-reads the shrunken array and yields the survivor
    TWICE.

  So the pre-fix `(cell-readers k)` could answer a two-element vector
  whose `count` was 2, whose `nth 0` and `nth 1` were the same object, and
  whose `mapv` was `[:b :b]` — a value that does not agree with itself.
  Every row below is red against that implementation.

  ## Registrations are NAMED, never printed

  Every assertion here runs through [[named]]. A registration holds the
  cells it acquired and every cell holds its readers, so the object graph
  is cyclic and `cljs.test`'s failure report walks it: the first draft of
  this file answered `RangeError: Maximum call stack size exceeded` where
  a diff should have been. A witness whose red is unreadable is half a
  witness, so the rows compare rosters of keywords.

  ## The control

  [[the-live-list-really-does-move]] is what stops the three rows above
  from passing vacuously. Each would be green under a runtime that
  registered nothing at all — an empty snapshot never changes either — so
  the control reads the LIVE list at the same three moments and requires
  it to have moved every time."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.adapter.uix :as uix-adapter]
            [re-frame.core :as rf]
            [re-frame.hicasso :as h]
            [re-frame.hicasso.impl.collector :as collector]
            [re-frame.hicasso.impl.inventory :as inventory]
            [re-frame.test-support :as test-support]))

(def ^:private frame-id ::inventory-snapshot)

(rf/reg-sub :inv/n (fn [db _] (:n db)))

(rf/reg-event :inv/seed (fn [_ [_ db]] {:db db}))

;; The UIx adapter for the reason the package smoke gives: plain-atom has
;; no reactivity layer, so a subscription under it never notifies and the
;; cell wiring these rows read would be built against nothing.
;; `:ambient-frame nil` because this suite seats its own top-level frame.
(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter       uix-adapter/adapter
     :ambient-frame nil
     :async?        true
     :init-fn       (fn [] (collector/reset-runtime!))}))

;; ---------------------------------------------------------------------------
;; Harness — the published commit seam, no DOM
;; ---------------------------------------------------------------------------

(defn- seeded!
  []
  (set! (.-IS_REACT_ACT_ENVIRONMENT js/globalThis) false)
  (rf/make-frame {:id frame-id})
  (rf/with-frame frame-id (rf/dispatch-sync [:inv/seed {:n 1}]))
  frame-id)

(def ^:private the-key
  "The one sub-key every row acquires. Cells are keyed by `[frame-kw
  query-v]`, because two frames are isolated contexts holding two
  different app-dbs."
  [frame-id [:inv/n]])

(defn- mount-reader!
  "Commit one boundary that reads [[the-key]], the way React would, and
  answer `{:reg … :cleanup …}`. `:reg` is read off the live list at the
  moment of the commit — `peek` is eager, so it hands back the object
  rather than a view of it."
  []
  (collector/render-body frame-id (fn [_] [:p (h/sub [:inv/n])]) {})
  (let [cleanup (collector/commit-boundary! (collector/last-reads) (fn []))]
    {:reg (peek (inventory/cell-readers the-key)) :cleanup cleanup}))

(defn- named
  "`readers` with each registration replaced by its name in `roster` — a
  reader list stated rather than printed, for the reason the ns docstring
  gives. A keyword passes through (a row appends a sentinel), a slot the
  live array no longer has reads `:absent`, and anything else `:unknown`."
  [roster readers]
  (mapv (fn [r]
          (cond
            (nil? r)     :absent
            (keyword? r) r
            :else        (get roster r :unknown)))
        readers))

;; ---------------------------------------------------------------------------
;; A departure must not rewrite a snapshot taken before it
;; ---------------------------------------------------------------------------

(deftest a-snapshot-keeps-the-reader-it-captured-across-a-remount
  (seeded!)
  ;; The rf2-vsgq shape exactly: capture the sole reader, unmount it,
  ;; mount a fresh one. `.splice` empties the array and `.push` refills
  ;; it, so an aliasing snapshot silently BECOMES the post-remount list —
  ;; and a runtime that correctly replaced its reader reads as one that
  ;; leaked.
  (let [{reg-1 :reg cleanup-1 :cleanup} (mount-reader!)
        snapshot                        (inventory/cell-readers the-key)]

    (cleanup-1)
    (let [{reg-2 :reg} (mount-reader!)
          roster       {reg-1 :first-reader reg-2 :second-reader}]

      (testing "the premise: the remount really did install a different
                registration"
        (is (false? (identical? reg-1 reg-2))))

      (testing "and the snapshot still names the reader it was taken of"
        (is (= [:first-reader] (named roster snapshot)))))))

(deftest a-snapshot-keeps-both-readers-when-one-leaves
  (seeded!)
  (let [{reg-a :reg cleanup-a :cleanup} (mount-reader!)
        {reg-b :reg}                    (mount-reader!)
        snapshot                        (inventory/cell-readers the-key)
        roster                          {reg-a :a reg-b :b}]

    (testing "the premise: two distinct readers, in mount order"
      (is (= [:a :b] (named roster snapshot))))

    (cleanup-a)

    (testing "and after the first leaves, the snapshot is unmoved — an
              aliasing one keeps the length 2 that `cnt` froze while its
              `mapv` re-reads the one-element array twice and answers
              `[:b :b]`"
      (is (= 2 (count snapshot)))
      (is (= [:a :b] (named roster snapshot))))))

;; ---------------------------------------------------------------------------
;; An arrival must not enlarge a snapshot taken before it
;; ---------------------------------------------------------------------------

(deftest a-snapshot-does-not-gain-a-reader-that-joined-after-it
  (seeded!)
  (let [{reg-a :reg} (mount-reader!)
        snapshot     (inventory/cell-readers the-key)
        {reg-b :reg} (mount-reader!)
        roster       {reg-a :a reg-b :b}]

    (testing "`count` is bounded by the frozen `cnt`, so it answers 1 over
              a live array too — stated as the property, never offered as
              the proof"
      (is (= 1 (count snapshot))))

    (testing "the proof is the walk that consults the tail's CURRENT
              length: over a live array `mapv` yields the reader that
              arrived after the capture, and disagrees with the `count`
              directly above it"
      (is (= [:a] (named roster snapshot))))))

;; ---------------------------------------------------------------------------
;; The control
;; ---------------------------------------------------------------------------

(deftest the-live-list-really-does-move
  (seeded!)
  ;; Without this row, every assertion above is green under a runtime that
  ;; registers nothing: an empty snapshot never changes either.
  (let [{cleanup-a :cleanup} (mount-reader!)]
    (is (= 1 (count (inventory/cell-readers the-key))))
    (mount-reader!)
    (is (= 2 (count (inventory/cell-readers the-key))))
    (cleanup-a)
    (is (= 1 (count (inventory/cell-readers the-key))))))
