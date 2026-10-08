(ns re-frame.adapter.reagent-slim-subscribe-container-activation-cljs-test
  "`subscribe-container` on a DERIVED container activates it on reagent-slim
  (Spec 006 §`make-derived-value`, §*Watchable is necessary, not
  sufficient*). A reagent2 `Reaction` learns its sources only through
  deref-capture, so a bare `add-watch` on one read outside a ratom context can
  never fire; the shared `spine/activating-subscribe-container` runs the
  injected `reagent2.ratom/activate!` as an observer attaches. A render is
  itself a capture context, so mounted-view suites cannot pin this; these
  tests drive the adapter's container slots directly, with no component and
  no manual `activate!`. The stock twin is
  `re-frame.adapter-subscribe-container-activation-cljs-test`, settling with
  `reagent.core/flush` where this uses `reagent2.ratom/flush!`."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [reagent2.ratom :as ratom]
            [re-frame.adapter.reagent-slim :as rf.adapter.reagent-slim]))

(def ^:private A rf.adapter.reagent-slim/adapter)

(defn- source [v] ((:make-state-container A) v))
(defn- derive* [sources f] ((:make-derived-value A) sources f))
(defn- write! [c v] ((:replace-container! A) c v))
(defn- read* [c] ((:read-container A) c))
(defn- observe [c on-change] ((:subscribe-container A) c on-change))

;; reagent2's Reaction flush queue is process-global: drain it so a source
;; write cannot leak a stale recompute into a later suite.
(use-fixtures :each (fn [test-fn]
                      (try (test-fn)
                           (finally (ratom/flush!)))))

(defn- recorder
  "Return `[events-atom on-change]`; `on-change` conjes `[prev new]`."
  []
  (let [events (atom [])]
    [events (fn [prev nu] (swap! events conj [prev nu]))]))

(deftest slim-direct-derived-listener-fires-after-baseline-read-cljs-test
  (testing "a read before the attach does not suppress the notification"
    (let [s              (source 1)
          d              (derive* [s] (fn [v] (* 10 v)))
          _              (is (= 10 (read* d)) "baseline read")
          [events notify] (recorder)
          unsub          (observe d notify)]
      (write! s 2)
      (ratom/flush!)
      (is (= [[10 20]] @events)
          "the baseline-read node hears the change, once, and attaching alone notified nobody")
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
      (is (= [1 1] [after-first @computes])
          "the first observer activates once; a second over the live node recomputes nothing")
      (write! s 2)
      (ratom/flush!)
      (is (= [2 [[10 20]] [[10 20]]] [@computes @ev-a @ev-b])
          "one source write, one recompute, heard by both observers")
      (unsub-a)
      (write! s 3)
      (ratom/flush!)
      (is (= [[[10 20]] [[10 20] [20 30]]] [@ev-a @ev-b])
          "the cancelled observer hears nothing further; the survivor is still live")
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
        (is (= [before [[10 20]]] [@computes @events])
            "the detached node is off the push path — no recompute and no notification")))))

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
      (is (= [[[3 30]] (inc after-attach)] [@events @computes])
          "one coalesced notification and one recompute for the pair, not one per write")
      (unsub))))
