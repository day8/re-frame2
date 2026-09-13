(ns re-frame.adapter.reagent-slim-subscribe-container-activation-cljs-test
  "reagent-slim adapter — `subscribe-container` on a DERIVED container
  activates it (rf2-59cz; the slim twin of
  `re-frame.adapter-subscribe-container-activation-cljs-test`).

  THE FIX IS SHARED; ONLY THE ASSERTION IS PER-ADAPTER. rf2-gwye.47 / PR
  #9768 landed one repair, `re-frame.substrate.spine/activating-subscribe-
  container`, wired in `make-ratom-adapter` rather than in
  `make-ratom-spine` — that is where the substrate's `:activate-reaction!`
  op arrives, so stock Reagent and reagent-slim share one subscription
  algorithm with no edit in either adapter namespace. reagent-slim injects
  `:activate-reaction! reagent2.ratom/activate!` and was therefore fixed by
  that PR without a line of slim-side code. Nothing in this tree PINNED it,
  so a change to the shared helper could regress reagent-slim silently while
  the stock-Reagent suite stayed green. This namespace closes that.

  Spec 006 §`make-derived-value` requires PUSH: the derived container updates
  automatically when a source changes, and `subscribe-container` \"works as on
  a base container\". §*Watchable is necessary, not sufficient* then makes the
  placement normative — a substrate on a demand-driven host activates the node
  when an observer ATTACHES, immediately before installing that observer's
  watch and taking its baseline read.

  reagent2's `Reaction` is demand-driven exactly as stock's is: it learns its
  sources only through `deref-capture`, and `-deref` outside `*ratom-context*`
  with no `auto-run` runs the body RAW and leaves `watching` nil, so `_queued-
  run`'s `(some? watching)` guard means `flush!` moves nothing and a bare
  `add-watch` is registered and can never fire. `reagent2.ratom/activate!` is
  the rewrite's name for the capture run (stock spells it `reagent.ratom/run`
  behind `IRunnable`). Component-owned subscriptions never meet the hole
  because a render IS the capture context — which is why the slim mounted-view
  suites cannot pin this contract. These tests therefore use the adapter's
  container slots DIRECTLY: no component, no ratom context, and no manual
  `ratom/activate!` in any test body.

  Divergence from the stock twin, and it is only this: the settle step is
  `reagent2.ratom/flush!` — the rewrite's synchronous reaction-queue drain —
  in place of `reagent.core/flush`. Same six pins, same values.

  Pins:

    * direct listener on a fresh derived container fires (no baseline read)
    * direct listener fires when a baseline read preceded the attach
    * a second observer over an already-active node forces no extra recompute,
      and removing one observer leaves the other live
    * final detach releases the source watch (the node stops recomputing)
    * base-container listeners keep working; cancellation is idempotent
    * a multi-input derived keeps native batching (one notification per
      flush, not one per source write)"
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [reagent2.ratom :as ratom]
            [re-frame.adapter.reagent-slim :as rf.adapter.reagent-slim]))

(def ^:private A rf.adapter.reagent-slim/adapter)

(defn- source [v] ((:make-state-container A) v))
(defn- derive* [sources f] ((:make-derived-value A) sources f))
(defn- write! [c v] ((:replace-container! A) c v))
(defn- read* [c] ((:read-container A) c))
(defn- observe [c on-change] ((:subscribe-container A) c on-change))

;; reagent2's Reaction flush queue is process-global. Drain it after each test
;; so a source write that enqueued a watching Reaction cannot leak a stale
;; recompute into a later suite sharing this node process — the same hygiene
;; the sibling `reagent-slim-derived-container-replaced` suite keeps.
(use-fixtures :each (fn [test-fn]
                      (try (test-fn)
                           (finally (ratom/flush!)))))

(defn- recorder
  "Return `[events-atom on-change]`; `on-change` conjes `[prev new]`."
  []
  (let [events (atom [])]
    [events (fn [prev nu] (swap! events conj [prev nu]))]))

(deftest slim-direct-derived-listener-fires-without-baseline-read-cljs-test
  (testing "attach-then-write on a never-read derived container notifies"
    (let [s              (source 1)
          d              (derive* [s] (fn [v] (* 10 v)))
          [events notify] (recorder)
          unsub          (observe d notify)]
      (write! s 2)
      (ratom/flush!)
      (is (= [[10 20]] @events)
          "observer attached to a fresh derived container hears the source change")
      (unsub))))

(deftest slim-direct-derived-listener-fires-after-baseline-read-cljs-test
  (testing "a read before the attach does not suppress the notification"
    (let [s              (source 1)
          d              (derive* [s] (fn [v] (* 10 v)))
          _              (is (= 10 (read* d)) "baseline read")
          [events notify] (recorder)
          unsub          (observe d notify)]
      (is (= [] @events) "attaching alone notifies nobody")
      (write! s 2)
      (ratom/flush!)
      (is (= [[10 20]] @events) "the baseline-read node still hears the change")
      (unsub))))

(deftest slim-direct-derived-two-observers-share-one-activation-cljs-test
  (testing "the second observer forces no extra recompute; detaching one leaves the other live"
    (let [computes       (atom 0)
          s              (source 1)
          d              (derive* [s] (fn [v] (swap! computes inc) (* 10 v)))
          [ev-a notify-a] (recorder)
          [ev-b notify-b] (recorder)
          unsub-a        (observe d notify-a)
          after-first    @computes
          unsub-b        (observe d notify-b)]
      (is (= 1 after-first) "attaching the first observer activates once")
      (is (= 1 @computes) "attaching a second observer over a live node recomputes nothing")
      (write! s 2)
      (ratom/flush!)
      (is (= 2 @computes) "one source write, one recompute")
      (is (= [[10 20]] @ev-a))
      (is (= [[10 20]] @ev-b) "both observers hear the same change")
      (unsub-a)
      (write! s 3)
      (ratom/flush!)
      (is (= [[10 20]] @ev-a) "the cancelled observer hears nothing further")
      (is (= [[10 20] [20 30]] @ev-b) "the surviving observer is still live")
      (unsub-b))))

(deftest slim-direct-derived-final-detach-releases-source-watch-cljs-test
  (testing "after the last observer cancels, source writes no longer drive the node"
    (let [computes       (atom 0)
          s              (source 1)
          d              (derive* [s] (fn [v] (swap! computes inc) (* 10 v)))
          [events notify] (recorder)
          unsub          (observe d notify)]
      (write! s 2)
      (ratom/flush!)
      (is (= [[10 20]] @events))
      (let [before @computes]
        (unsub)
        (unsub)                                        ; idempotent
        (write! s 3)
        (ratom/flush!)
        (is (= before @computes)
            "the detached node is off the push path — no recompute on a source write")
        (is (= [[10 20]] @events) "and no notification")))))

(deftest slim-direct-base-container-listener-unaffected-cljs-test
  (testing "base containers keep their plain watch semantics (the control)"
    (let [s              (source 1)
          [events notify] (recorder)
          unsub          (observe s notify)]
      (write! s 2)
      (is (= [[1 2]] @events) "base container notifies synchronously on write")
      (unsub)
      (unsub)                                          ; idempotent
      (write! s 3)
      (is (= [[1 2]] @events) "cancelled base-container observer hears nothing"))))

(deftest slim-direct-derived-multi-input-retains-batching-cljs-test
  (testing "two source writes before one flush coalesce into a single notification"
    (let [computes       (atom 0)
          a              (source 1)
          b              (source 2)
          d              (derive* [a b] (fn [x y] (swap! computes inc) (+ x y)))
          [events notify] (recorder)
          unsub          (observe d notify)
          after-attach   @computes]
      (write! a 10)
      (write! b 20)
      (ratom/flush!)
      (is (= [[3 30]] @events)
          "one coalesced notification carrying the settled value, not one per write")
      (is (= (inc after-attach) @computes)
          "one recompute for the pair — native batching is preserved")
      (unsub))))
