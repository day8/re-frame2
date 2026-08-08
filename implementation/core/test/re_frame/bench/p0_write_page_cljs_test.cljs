(ns re-frame.bench.p0-write-page-cljs-test
  "rf2-2rtt6.140 — the BOUNDARY-PROPORTIONAL WRITE, adjudicated hermetically.

  `:p0/write-page` exists because `:p0/write-all` rebuilds a 300-element
  vector and drives the whole event pipeline whether one boundary is
  mounted or 1,200. On this rig that fixed cost measured F ~ 24.4 KB per
  write (24,108 B on `reagent-subs`, 24,730 on `uix-subs`) and it does not
  shrink when the page does — which is what made the allocation ladder
  uncertifiable at any page size.

  WHY A CLJS SUITE AND NOT THE ALLOCATION ROW. The allocation row is an
  `:advanced` release build driven by hand behind an opt-in flag, in no
  gate at all, and rf2-2rtt6.140's criterion 5 freezes it until its
  validity witnesses are green. So the write's CONTRACT — as opposed to
  its cost — has to be checkable without a browser, on every PR. That is
  what this suite is: no DOM, no adapter beyond the plain atom, no
  measurement.

  WHAT IT PINS is the equivalence argument's checkable half. The claim the
  ladder makes is about steady-state allocation per boundary per warm
  read, so the stimulus has one job: for every mounted boundary it must
  invalidate all R of its subscriptions and deliver R CHANGED values.

    - the seeded grid is the width the caller stated, not `fx/cells-n`;
    - `:p0/fan` folds every key into THAT grid, so the key space and the
      db cannot disagree about how wide the page is;
    - one `:p0/write-page` changes EVERY key a mounted page reads, which
      is the invalidation set being identical rather than merely similar;
    - a boundary's rendered text stays `(str (* R v))`, which is what
      leaves the DOM read-back and the canonical-DOM gate unchanged;
    - and `:p0/write-all` is untouched, at the published 300, because the
      clock and bulk rows publish figures taken with it.

  NOTHING HERE MEASURES ANYTHING. The cost claim — that the fixed residue
  is no longer dominant — is validity witness V1's, and V1 needs a quiet
  box that criterion 5 has not granted.

  ns ends in `-cljs-test` so shadow-cljs `:node-test` picks it up via
  `:ns-regexp \"cljs-test$\"`; `core/test` is already on that build's
  source paths."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.bench.p0-fixture :as fx]
            [re-frame.core :as rf]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture {:adapter plain-atom/adapter}))

(def ^:private frame-id :p0.wp/frame)

;; THE IMAGE IS SCOPED TO THE FIXTURE'S OWN NAMESPACE, and it has to be.
;; `:p0/cell` is registered THREE times across this repo — here in
;; `p0-fixture`, and again in `hicasso/clock_views` and
;; `hicasso/p0_reagent_views`, each with its own implementation. The p0
;; release builds put exactly one of those namespaces on the classpath, so
;; the collision is unreachable there; the consolidated `:node-test` build
;; loads all three, and image assembly correctly refuses to let selection
;; order decide which one a frame runs (`:rf.error/image-duplicate-id`).
;;
;; So this suite says which it means. That is the error's own first remedy
;; and it is the right one: a bench fixture suite asserting the fixture's
;; contract should be running the fixture's registrations and no one else's.
(def ^:private fixture-image
  (rf/image {:id :p0.wp/app :select-ns {:include ["re-frame.bench.p0-fixture"]}}))

(defn- frame-at
  "Stand a frame up seeded at `width` cells, with `q` unique fan keys —
  the same two moves `p0-arms/enter-segment!` and `p0-heap/mount!` make,
  in the same order (the key space is set BEFORE the page reads it)."
  [width q]
  (fx/register!)
  (fx/set-fan-keys! q)
  (rf/make-frame {:id frame-id :images [fixture-image] :initial-events [[:p0/seed width]]}))

;; Read straight off the frame rather than through a whole-db subscription:
;; the fixture deliberately registers none, because a whole-db read would
;; re-render every boundary on every write and make the NARROW row
;; unmeasurable by construction.
(defn- db [] (rf/app-db-value frame-id))

(defn- fan [k] (rf/subscribe-once [:p0/fan k] {:frame frame-id}))

;; ---------------------------------------------------------------------------
;; The grid width is a property of the SEEDED DB
;; ---------------------------------------------------------------------------

(deftest the-seeded-grid-is-the-width-the-caller-stated
  (testing "rf2-2rtt6.140 — `seed-db` takes the width and `:p0/seed` carries
            it, so the page a frame is standing on is stated at the one place
            the frame is stood up. The brief's V2 configuration is B=4, and
            this is that page."
    (frame-at 4 1)
    (is (= 4 (count (:cells (db)))) "four cells, one per mounted boundary")
    (is (= [0 0 0 0] (:cells (db))) "seeded to zero, as the published grid is")
    ;; The other two seeded surfaces are untouched by the width — a width
    ;; parameter that quietly resized the list or the form would make the
    ;; mount rows incomparable across the change.
    (is (= fx/w1-rows (count (:rows (db)))))
    (is (= fx/w3-fields (count (:fields (db)))))))

(deftest an-unstated-width-is-the-published-page-to-the-byte
  (testing "every caller that passes nothing — the clock rows, the bulk rows,
            the fan-out sweep, the retention ladder — gets `fx/cells-n`. No
            published figure may move on the strength of this change."
    (is (= fx/cells-n (count (:cells (fx/seed-db)))))
    (is (= (fx/seed-db) (fx/seed-db fx/cells-n))
        "the default arity is the stated one at the published width")))

;; ---------------------------------------------------------------------------
;; The invalidation set is IDENTICAL — the crux of the equivalence argument
;; ---------------------------------------------------------------------------

(deftest write-page-rebuilds-at-the-db-s-own-width
  (testing "the handler reads its width off the db it is handed, so there is
            no second place for the width to live and drift."
    (frame-at 4 1)
    (rf/dispatch-sync [:p0/write-page 7] {:frame frame-id})
    (is (= [7 7 7 7] (:cells (db))) "four cells wide, every one of them changed")
    (rf/dispatch-sync [:p0/write-page 9] {:frame frame-id})
    (is (= [9 9 9 9] (:cells (db))) "and it is still four wide on the next write")))

(deftest every-key-a-mounted-page-reads-sees-a-changed-value
  (testing "THE EQUIVALENCE ARGUMENT'S CHECKABLE HALF. The ladder mounts at
            Q = E, so B boundaries x R reads is B·R distinct `:p0/fan` keys,
            and `(n·R + j) mod Q` is the identity over 0 … B·R−1. Each folds
            into the grid under `(mod k width)`. One `:p0/write-page` must
            move EVERY one of them — that is the invalidation set being
            identical to `:p0/write-all`'s, not merely similar to it."
    (let [b 4, r 20, q (* b r), width b]
      (frame-at width q)
      (is (every? zero? (map fan (range q))) "the seeded page answers 0 everywhere")
      (rf/dispatch-sync [:p0/write-page 3] {:frame frame-id})
      (is (= width (count (:cells (db)))) "the write did not resize the grid")
      (is (every? #(= 3 %) (map fan (range q)))
          "all 80 edges of the B=4, R=20 rung see the new value")
      ;; And the keys the ladder actually mounts, through the same rule the
      ;; arms use rather than through a restatement of it.
      (is (every? #(= 3 %)
                  (for [n (range b), j (range r)] (fan (fx/fan-key n r j))))
          "read through `fx/fan-key`, which is what the arms read through"))))

(deftest the-rendered-text-is-unchanged-so-the-read-back-gate-is
  (testing "a boundary's text is the sum of its R reads, so at a page written
            to `v` it is `R·v` under EITHER write. That is what leaves the DOM
            read-back, the canonical-DOM comparison and the floor subtraction
            unchanged — the driver states its expectation as `String(R * tick)`
            and this is the arithmetic behind it."
    (let [b 4, r 7, q (* b r)]
      (frame-at b q)
      (rf/dispatch-sync [:p0/write-page 5] {:frame frame-id})
      (doseq [n (range b)]
        (is (= (* r 5) (reduce + (map #(fan (fx/fan-key n r %)) (range r))))
            (str "boundary " n " renders R·v"))))))

(deftest two-keys-folding-onto-one-slot-is-not-new
  (testing "`:p0/fan` has folded `mod cells-n` since rf2-5prok, and at B=24,
            R=20 the published page already folds 480 keys onto 300 slots. What
            changes is only WHICH grid they fold into, and folding does not
            cost a key its invalidation: distinct keys sharing a slot all see
            the write."
    (frame-at 4 480)
    (rf/dispatch-sync [:p0/write-page 2] {:frame frame-id})
    (is (= [2 2 2 2] (:cells (db))))
    (is (every? #(= 2 %) (map fan (range 480)))
        "480 distinct query keys over a 4-cell grid, every one of them changed")))

;; ---------------------------------------------------------------------------
;; `:p0/write-all` is BYTE-IDENTICAL, because its rows are published
;; ---------------------------------------------------------------------------

(deftest write-all-still-rebuilds-the-published-grid-whatever-is-mounted
  (testing "rf2-2rtt6.140 leaves `:p0/write-all` exactly as it is, literal
            `cells-n` and all: it is the bulk clock row's write, its rows are
            published, and it stays byte-identical. On a 4-cell page it
            therefore RESIZES the grid back to 300 — which is precisely the
            cost the allocation row stopped paying, made visible."
    (frame-at 4 1)
    (is (= 4 (count (:cells (db)))))
    (rf/dispatch-sync [:p0/write-all 1] {:frame frame-id})
    (is (= fx/cells-n (count (:cells (db))))
        "300 cells rebuilt on a page that reads four of them")
    (is (every? #(= 1 %) (:cells (db))))))

(deftest write-all-and-write-page-agree-at-the-published-width
  (testing "the two writes are the SAME WRITE at the page `:p0/write-all` was
            written for. A difference here would mean the new event is not a
            width-parameterised form of the old one but a second thing."
    (frame-at fx/cells-n 300)
    (rf/dispatch-sync [:p0/write-all 4] {:frame frame-id})
    (let [after-all (:cells (db))]
      (rf/dispatch-sync [:p0/seed fx/cells-n] {:frame frame-id})
      (rf/dispatch-sync [:p0/write-page 4] {:frame frame-id})
      (is (= after-all (:cells (db)))
          "identical `:cells` at width 300, so the difference is the width alone"))))

;; ---------------------------------------------------------------------------
;; `:p0/write-one` — the point write, unmoved
;; ---------------------------------------------------------------------------

(deftest the-narrow-write-is-untouched
  (testing "the NARROW row's write changes one slot and must keep changing
            exactly one, whatever the grid width is. HD-002's law is stated
            about the CHANGE, so the narrow row and the bulk row have to stay
            different sizes of change."
    (frame-at 4 4)
    (rf/dispatch-sync [:p0/write-one 2 8] {:frame frame-id})
    (is (= [0 0 8 0] (:cells (db))) "one slot moved, three did not")))
