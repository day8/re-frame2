(ns re-frame.movement-witness-cljs-test
  "rf2-gncxk.1 — the `re-frame.movement/IMovementWitness` contract, pinned on
  the ONE implementor in this repo: the React-hook spine's derived container.

  Spec 006 §Movement witness (optional) states three obligations. Two bind an
  implementor and are what these tests pin:

    W1 FRESHNESS  answer `no-witness` unless the container's own value has
                  completed a movement AND no input change has been observed
                  since. Without it, a PULL-based container's live value can
                  run ahead of its last movement.
    W2 SOUNDNESS  whenever it answers `f` other than `no-witness`, reading the
                  container at that instant yields `v` with `(not (rf= f v))`
                  — and therefore `(not (= f v))`.

  The third (C1, verdict-preserving) binds the CONSUMER, and its pins live
  where the consumer does: `sub_memo_flush_path_cljs_test.cljs` (both arms),
  `sub_memo_layer_1_test.clj`, and the body-run counts asserted throughout the
  subscription suite.

  Also pinned here: the structural fact `:frame-state` depends on. A raw
  physical container does NOT implement the protocol, so
  `subs.memo`'s `witness-src` resolves nil for a `:frame-state` sub and its
  guard expression is byte-for-byte the one that shipped. That is a property
  of the container, not a courtesy of the consumer, and it is what keeps the
  `:frame-state` flush-path memo hit alive.

  ns ends in -cljs-test so shadow-cljs's :node-test build picks it up."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [re-frame.disposable :as rf.disposable]
            [re-frame.movement :as rf.movement]
            [re-frame.substrate.spine :as rf.substrate.spine]))

;; A bare spine adapter — nothing is mounted and no frame is registered; these
;; tests drive `make-state-container` / `make-derived-value` /
;; `replace-container!` directly, which is the whole surface under test.
(def ^:private adapter
  (rf.substrate.spine/make-react-adapter
    (rf.substrate.spine/make-react-spine
      {:substrate-name        "movement-witness"
       :gensym-prefix-sub     "mw-sub-"
       :gensym-prefix-derived "mw-derived-"
       :gensym-prefix-use-sub "mw-use-sub-"
       :use-memo              (fn [t _] (t))
       :use-callback          (fn [t _] t)
       :use-context           (fn [_] nil)})
    {:kind :rf.adapter/movement-witness :frame-provider nil}))

(defn- make-state-container [v] ((:make-state-container adapter) v))
(defn- make-derived-value [sources f] ((:make-derived-value adapter) sources f))
(defn- replace-container! [c v] ((:replace-container! adapter) c v))

(deftest a-fresh-derived-container-answers-no-witness
  (testing "W1 — nothing has moved yet, so there is no departure value to
            publish and the container must say so"
    (let [src     (make-state-container {:n 1})
          derived (make-derived-value [src] :n)]
      (is (satisfies? rf.movement/IMovementWitness derived)
          "the spine's derived container publishes the capability — its
           fan-out is `rf=`-gated, which is W2's precondition")
      (is (identical? rf.movement/no-witness (rf.movement/-moved-from derived))
          "before any movement, `no-witness`")
      ;; A deref establishes the baseline but is NOT a movement.
      (is (= 1 @derived))
      (is (identical? rf.movement/no-witness (rf.movement/-moved-from derived))
          "a read is not a movement"))))

(deftest a-completed-movement-publishes-its-departure-value
  (testing "W2 — after a movement the witness is the value departed FROM, by
            IDENTITY, and the live value is not `=` to it"
    (let [v0      {:n 1}
          src     (make-state-container v0)
          derived (make-derived-value [src] identity)]
      (is (= v0 @derived))                      ;; seed the baseline
      (replace-container! src {:n 2})
      (let [f (rf.movement/-moved-from derived)]
        (is (identical? v0 f)
            "the witness IS the predecessor object — the consumer's whole
             short-circuit is an `identical?` test against it")
        (is (not (= f @derived))
            "W2: reading the container yields a value that is not `=` to the
             witness. This is the inference the memo wrapper skips its
             structural walk on.")))))

(deftest an-observed-input-change-retracts-the-witness
  (testing "W1 — a no-move flush leaves the container silent. `mark-dirty!`
            clears the witness on its 0->1 transition (the container's live
            value may now run ahead of its last notify), and a `rf=`-equal
            recompute does not re-arm it, so nothing stale is published."
    (let [v0      {:n 1}
          src     (make-state-container v0)
          ;; The projection shape: a change to `:other` moves the SOURCE but
          ;; leaves this derived value `rf=`-equal, so it must not notify.
          derived (make-derived-value [src] :n)]
      (is (= 1 @derived))
      (replace-container! src {:n 2 :other :a})
      (is (= 1 (rf.movement/-moved-from derived))
          "the real move arms the witness with the value DEPARTED FROM")
      ;; A source change that leaves THIS container's value `rf=`-equal.
      (replace-container! src {:n 2 :other :b})
      (is (identical? rf.movement/no-witness (rf.movement/-moved-from derived))
          "the input change was OBSERVED (mark-dirty) so the witness was
           retracted, and the `rf=` gate then declined to re-arm it — the
           container correctly publishes nothing rather than a stale claim")
      (is (= 2 @derived) "and its value genuinely did not move"))))

(deftest dispose-retracts-the-witness-and-releases-the-predecessor
  (testing "hygiene + the one memory cost. A disposed container will never
            complete another movement, so `no-witness` is the only correct
            answer — and dropping the reference releases the single
            predecessor generation the witness retained."
    (let [v0      {:n 1}
          src     (make-state-container v0)
          derived (make-derived-value [src] identity)]
      (is (= v0 @derived))
      (replace-container! src {:n 2})
      (is (identical? v0 (rf.movement/-moved-from derived)))
      (rf.disposable/-dispose derived)
      (is (identical? rf.movement/no-witness (rf.movement/-moved-from derived))
          "disposed containers stop answering, and the retained predecessor
           is released with them"))))

(deftest a-raw-physical-container-publishes-nothing
  (testing "the STRUCTURAL fact `:frame-state` rests on. Its signal source is
            the frame's one physical container, whose fan-out is not
            movement-gated — so it cannot satisfy W2 and does not implement
            the protocol. `subs.memo`'s `witness-src` is therefore nil there
            and the guard is unchanged."
    (let [src (make-state-container {:n 1})]
      (is (not (satisfies? rf.movement/IMovementWitness src))
          "a raw base container publishes no witness — absence is the correct
           answer for it, not an omission"))))
