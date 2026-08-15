(ns re-frame.story.play.substrate-boundary-cljs-test
  "rf2-ek9qb — the `:settled-boundary-hooks` PRODUCER, and the settle it
  put in place of a timer.

  The defect these tests witness was not that the settle was slow. It was
  that Story had NO settle signal from the substrate at all: the
  `:settled-boundary-hooks` slot had a consumer and no producer, so every
  host ran at `:provides :headless`, whose only flush is a no-op, and the
  real settle after an interaction was the run-loop's `setTimeout` 0 —
  which drains the microtask queue but never asks a rAF-scheduled
  substrate to commit.

  So the witness cannot be a timing measurement (a fast machine proves
  nothing, and a slow one proves nothing either). It is a COUNT: with a
  substrate whose commit is observable, does the runner call it? Before
  this bead it did not, once, ever — `substrate-commits-before-a-dom-step`
  and `settle-is-synchronous-not-scheduled` both read 0.

  Runner note, and it is why this file is named and extensioned the way
  it is: `.cljc` so the JVM lane (`clojure -M:test` from `tools/story`)
  loads it, and `*_cljs_test` so the `:node-test` build's `cljs-test$`
  ns-regexp DISCOVERS it too. Both lanes therefore execute every assertion
  here — which matters because the production change is entirely `.cljc`
  and a reader-conditional mistake in the `:cljs` branch would be
  invisible to a JVM-only run. Nothing in this file needs a DOM: the
  settle under test happens BEFORE the DOM executor runs, which is exactly
  why the JVM can witness it."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.story.assertions :as assertions]
            [re-frame.story.late-bind :as late-bind]
            [re-frame.story.play.runner-events :as runner-events]
            [re-frame.story.play.settled-boundary :as boundary]
            [re-frame.story.play.substrate-boundary :as substrate-boundary]
            [re-frame.substrate.plain-atom :as plain-atom]))

;; ---- harness -------------------------------------------------------------

(def ^:private commits
  "Count of substrate commits the fake adapter's `:flush-render!` has been
  asked for. The whole witness reduces to whether this moves."
  (atom 0))

(defn- committing-adapter
  "`plain-atom` plus the ONE optional contract fn this bead is about. The
  real Reagent adapter's is `(fn [f] (f) (r/flush))`; this one keeps the
  same shape and counts instead of flushing, so a JVM run can observe the
  call the browser would make."
  []
  (assoc plain-atom/adapter
         :flush-render! (fn [f] (f) (swap! commits inc) nil)))

;; NOT `late-bind/clear!` — that wipes the canonical shims every sibling
;; test ns registers at load time (`:run-play-step`, `:clear-step-boundaries`,
;; `:ensure-canonical-installed`), and the wipe outlives this ns in a shared
;; JVM. Snapshot the whole map and restore it, so exactly the one slot this
;; bead is about is under test and nothing else moves.

(use-fixtures :each
  (fn [t]
    (let [saved @late-bind/hooks]
      (reset! commits 0)
      (swap! late-bind/hooks dissoc :settled-boundary-hooks)
      (try (t)
           (finally
             (reset! commits 0)
             (reset! late-bind/hooks saved)
             (try (rf/destroy-adapter!)
                  (catch #?(:clj Throwable :cljs :default) _ nil)))))))

(defn- dom-assert-step
  "A folded in-script DOM checkpoint — the shape a shipping
  `[:assert-dom sel :text \"x\"]` has by the time the executor sees it."
  []
  [:assert [assertions/id-dom-text "[data-test=x]" "42"]])

;; ---- the producer --------------------------------------------------------

(deftest headless-when-no-adapter-is-seated
  (testing "with no adapter installed the producer yields the headless
            default — the JVM floor is unchanged, which is what makes
            installing it unconditionally safe"
    (is (nil? (substrate-boundary/adapter-flush-render)))
    (let [hooks (substrate-boundary/substrate-flush-hooks :any-frame)]
      (is (identical? boundary/headless-flush-hooks hooks))
      (is (= :headless (boundary/hooks-provided-boundary hooks))))))

(deftest headless-when-adapter-ships-no-flush-render
  (testing "an adapter with no live commit (plain-atom, SSR) ships no
            :flush-render!, so the producer stays at the headless floor
            rather than claiming a :dom boundary it cannot honour"
    (rf/init! plain-atom/adapter)
    (is (nil? (:flush-render! (rf/current-adapter-spec)))
        "precondition: plain-atom ships no :flush-render!")
    (is (nil? (substrate-boundary/adapter-flush-render)))
    (is (= :headless
           (boundary/hooks-provided-boundary
             (substrate-boundary/substrate-flush-hooks :any-frame))))))

(deftest provides-dom-when-the-live-adapter-can-commit
  (testing "an adapter shipping :flush-render! lifts the declared boundary
            to :dom and registers the commit at both richer rungs"
    (rf/init! (committing-adapter))
    (let [hooks (substrate-boundary/substrate-flush-hooks :f)]
      (is (= :dom (boundary/hooks-provided-boundary hooks)))
      (is (fn? (get-in hooks [:flush! :cljs-reactive])))
      (is (fn? (get-in hooks [:flush! :dom])))
      (is (zero? @commits) "building the hooks must not commit anything")
      ((get-in hooks [:flush! :dom]) :f)
      (is (= 1 @commits)))))

(deftest producer-names-no-substrate
  (testing "the hooks are built from the LIVE adapter, so swapping the
            adapter swaps the settle with no Story-side entry per
            substrate (spec/Tool-Pair.md §Driving the render: a tool that
            names reagent.core/flush! is non-conforming)"
    (rf/init! plain-atom/adapter)
    (is (= :headless (boundary/hooks-provided-boundary
                       (substrate-boundary/substrate-flush-hooks :f))))
    (rf/destroy-adapter!)
    (rf/init! (committing-adapter))
    (is (= :dom (boundary/hooks-provided-boundary
                  (substrate-boundary/substrate-flush-hooks :f))))))

;; ---- the slot ------------------------------------------------------------

(deftest install-registers-the-late-bind-slot
  (testing "before install! the slot is empty and the runner falls back to
            headless — the state every host shipped in before rf2-ek9qb"
    (is (nil? (late-bind/get-fn :settled-boundary-hooks)))
    (rf/init! (committing-adapter))
    (is (= :headless
           (boundary/hooks-provided-boundary
             (runner-events/current-flush-hooks :f)))
        "WITNESS: with no producer the live shell plays at :provides :headless"))
  (testing "after install! the runner resolves the substrate hooks"
    (substrate-boundary/install!)
    (is (some? (late-bind/get-fn :settled-boundary-hooks)))
    (is (= :dom
           (boundary/hooks-provided-boundary
             (runner-events/current-flush-hooks :f))))))

;; ---- the settle, which is the point --------------------------------------

(deftest substrate-commits-before-a-dom-step
  (testing "WITNESS (rf2-ek9qb): a step that reads the DOM now runs against
            a COMMITTED substrate. Without the producer + the pre-step
            settle this count stays 0 — the runner never asked the
            substrate to commit, and the only thing between an event and a
            DOM read was a setTimeout 0"
    (rf/init! (committing-adapter))
    (substrate-boundary/install!)
    (is (zero? @commits))
    (runner-events/exec-step! :f 0 (dom-assert-step))
    (is (pos? @commits)
        "a DOM-family checkpoint must settle the substrate before reading")))

(deftest settle-is-synchronous-not-scheduled
  (testing "the commit lands INSIDE the exec-step! call — no tick, no
            timer, nothing to race. This is the property a longer
            setTimeout could never buy"
    (rf/init! (committing-adapter))
    (substrate-boundary/install!)
    (let [before @commits
          _      (runner-events/exec-step! :f 0 (dom-assert-step))
          after  @commits]
      (is (> after before)
          "already committed by the time exec-step! returned"))))

(deftest headless-steps-do-not-commit
  (testing "a step that requires only :headless does not drag the
            substrate through a commit — the ladder still means what it
            says, and the cheap path stays cheap"
    (rf/init! (committing-adapter))
    (substrate-boundary/install!)
    (runner-events/exec-step! :f 0 [:wait-until [:queue-empty]])
    (is (zero? @commits))))

(deftest no-producer-means-no-commit
  (testing "the fix is the PRODUCER, not the pre-step settle alone: with
            the slot empty, the same DOM step commits nothing even though
            the adapter could have"
    (rf/init! (committing-adapter))
    (runner-events/exec-step! :f 0 (dom-assert-step))
    (is (zero? @commits))))

;; ---- the shared flush loop ----------------------------------------------

(deftest settle-to-runs-registered-rungs-in-ladder-order
  (testing "settle-to! walks the registered flushes up to `required`, in
            order, and settles without dispatching anything"
    (let [seen  (atom [])
          hooks {:provides :dom
                 :flush!   {:headless      (fn [_] (swap! seen conj :headless))
                            :cljs-reactive (fn [_] (swap! seen conj :cljs-reactive))
                            :dom           (fn [_] (swap! seen conj :dom))}}]
      (is (= {:status :settled :boundary :dom}
             (boundary/settle-to! :f hooks :dom)))
      (is (= [:headless :cljs-reactive :dom] @seen)))))

(deftest settle-to-is-inert-below-its-rung
  (testing "a cheaper `required` stops the ladder where it should"
    (let [seen  (atom [])
          hooks {:provides :dom
                 :flush!   {:cljs-reactive (fn [_] (swap! seen conj :cljs-reactive))
                            :dom           (fn [_] (swap! seen conj :dom))}}]
      (boundary/settle-to! :f hooks :headless)
      (is (= [] @seen)))))

(deftest settle-to-reports-a-throwing-flush
  (testing "a flush that throws is reported as :error — never swallowed,
            never a silent pass"
    (let [hooks {:provides :dom
                 :flush!   {:dom (fn [_] (throw (ex-info "boom" {})))}}
          res   (boundary/settle-to! :f hooks :dom)]
      (is (= :error (:status res))))))

(deftest settle-to-honours-the-timeout-budget
  (testing "an over-budget flush phase refuses fail-closed with
            :flush-timeout rather than reporting a settle it did not earn"
    (let [hooks {:provides   :dom
                 :timeout-ms -1
                 :flush!     {:dom (fn [_] nil)}}
          res   (boundary/settle-to! :f hooks :dom)]
      (is (= :cannot-run (:status res)))
      (is (= :flush-timeout (:reason res))))))
