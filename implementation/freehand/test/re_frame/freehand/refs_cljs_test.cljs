(ns re-frame.freehand.refs-cljs-test
  "FH-BEHAVIOR-007 — the ORDER half of ref composition.

  When more than one Freehand mechanism needs a ref on one element the
  refs compose. Composition without a defined order is not a contract: it
  is whichever participant happened to be installed first, which is a fact
  about implementation sequence rather than about the declaration, and it
  would move the day a mechanism changed where it installs itself. So the
  order is pinned HERE, on the composition itself, as an exact SEQUENCE.

  This is the tier that can see the order at all. The mounted tier proves
  the combination reaches a real node (see the `-dom-` sibling), but both
  of today's participants merely REGISTER at ref time — one enqueues into
  the commit batch, the other stores the node for its effect — so neither
  leaves a per-participant trace a browser could read the order off.

  Node-runnable and DOM-free: a ref is a function React calls with a node,
  and this file calls it with whatever it likes."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [re-frame.freehand.conformance :as conf]
            [re-frame.freehand.refs :as refs]))

(def ^:private fx (conf/fixture :FH-BEHAVIOR-007))

(def ^:private node
  "A stand-in for the live node. Composition never looks inside one — it
  hands the same value to every participant — so any identity will do,
  and one that is obviously not a DOM node keeps that honest."
  #js {:tag "stand-in"})

(defn- participant
  "A ref participant under React 19's protocol: it takes the node and
  answers the cleanup function that releases it."
  [log name*]
  (fn recording-ref [n]
    (if (some? n)
      (do (swap! log conj [name* :attach])
          (fn [] (swap! log conj [name* :release]) nil))
      ;; The older callback-ref protocol signals a detach with nil. It is
      ;; still a release, and it is still recorded as one.
      (do (swap! log conj [name* :release]) js/undefined))))

(defn- cleanup-returning
  [log name*]
  (fn cleanup-returning-ref [_]
    (swap! log conj [name* :attach])
    (fn [] (swap! log conj [name* :cleanup-called]) nil)))

(defn- nil-detaching
  [log name*]
  (fn nil-detaching-ref [n]
    (if (some? n)
      (swap! log conj [name* :attach])
      (swap! log conj [name* :released-with-nil]))
    js/undefined))

;; ===========================================================================
;; The order
;; ===========================================================================

(deftest fh-behavior-007-participants-take-the-node-inside-out
  (testing "Per FH-BEHAVIOR-007: an intrinsic is a property OF the element
            and a decoration is applied OVER it, so the element's own ref
            takes the node first and the decorating boundary's takes it
            second. Release is the exact reverse, as a stack unwinds — the
            outermost participant, the last to learn about the node, is
            the first to let go, so nobody is left holding a node a
            participant chained before it has already released."
    (let [log      (atom [])
          composed (refs/chain (participant log :intrinsic)
                               (participant log :decoration))
          cleanup  (composed node)]
      (is (= (:attach (:pair fx)) @log)
          "the intrinsic took the node, then the decoration")
      (is (fn? cleanup)
          "and the chain answered a cleanup, so React 19 releases through it")
      (cleanup)
      (is (= (into (:attach (:pair fx)) (:release (:pair fx))) @log)
          "the decoration released, then the intrinsic"))))

(deftest fh-behavior-007-a-third-participant-composes-the-same-way
  (testing "Per FH-BEHAVIOR-007: composition is the reason a new mechanism
            needing a node costs nothing — it chains onto whatever the
            element already carries and the order still falls out of the
            containment. Refusing a combination would scale as the number
            of PAIRS; this scales as one rule."
    (let [log      (atom [])
          composed (-> (refs/chain (participant log :intrinsic)
                                   (participant log :inner))
                       (refs/chain (participant log :outer)))
          cleanup  (composed node)]
      (is (= (:attach (:nested fx)) @log)
          "inside out: the intrinsic, the inner boundary, then the outer one")
      (cleanup)
      (is (= (into (:attach (:nested fx)) (:release (:nested fx))) @log)
          "and outside in on the way back"))))

(deftest fh-behavior-007-release-honours-each-participants-own-protocol
  (testing "Per FH-BEHAVIOR-007: release must be TOTAL, and a participant
            is released the way React itself would have released it — by
            CALLING the cleanup it answered with, or, when it answered
            with anything else, with nil. Honouring only one protocol
            would leak whichever participants used the other, and the
            substrate's two current participants use one each."
    (let [log      (atom [])
          composed (refs/chain (cleanup-returning log :cleanup-returning)
                               (nil-detaching log :nil-detaching))
          cleanup  (composed node)]
      (is (= (:attach (:protocols fx)) @log))
      (cleanup)
      (is (= (into (:attach (:protocols fx)) (:release (:protocols fx))) @log)
          "each participant released under the protocol it answered with"))))

(deftest fh-behavior-007-the-older-detach-protocol-releases-in-reverse-too
  (testing "Per FH-BEHAVIOR-007: a caller that signals a detach with nil
            rather than through a cleanup is still performing a release,
            so it still runs in release order. Forwarding nil in ATTACH
            order would be a second, contradictory answer to the one
            question this namespace exists to settle."
    (let [log      (atom [])
          composed (refs/chain (participant log :intrinsic)
                               (participant log :decoration))]
      (composed nil)
      (is (= (:release (:pair fx)) @log)))))

;; ===========================================================================
;; Identity — what composition must NOT change
;; ===========================================================================

(deftest fh-behavior-007-a-chain-of-one-is-that-one-participant
  (testing "Per FH-BEHAVIOR-007: composing must not change a participant's
            attach FREQUENCY, and React re-attaches whenever a ref's
            identity moves. A behavior's ref is created once precisely so
            React does not tear its node's lifecycle down every commit, so
            an element with only that one mechanism must carry that very
            function — not a wrapper around it, however cheap."
    (let [only (participant (atom []) :only)]
      (is (identical? only (refs/chain nil only))
          "nothing before it")
      (is (identical? only (refs/chain only nil))
          "nothing after it")
      (is (identical? only (refs/chain js/undefined only))
          "and an absent prop reads as nothing, not as a participant"))))

(deftest fh-behavior-007-a-composed-ref-is-fresh-when-a-participant-is
  (testing "Per FH-BEHAVIOR-007: the other half of the frequency rule. The
            top layer's ref is deliberately fresh at each commit — that
            re-entry into the commit batch is what makes the diff against
            the live node cheap — so a memoised composition would silently
            switch the mechanism off. Two calls, two chains."
    (let [log (atom [])
          a   (participant log :intrinsic)
          b   (participant log :decoration)]
      (is (not (identical? (refs/chain a b) (refs/chain a b)))
          "the composition follows its participants rather than caching over them"))))
