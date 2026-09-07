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
            [re-frame.frame :as rf.frame]
            [re-frame.story :as rf.story]
            [re-frame.story.assertions :as rf.story.assertions]
            [re-frame.story.late-bind :as rf.story.late-bind]
            [re-frame.story.play.runner-events :as rf.story.play.runner-events]
            [re-frame.story.play.settled-boundary :as rf.story.play.settled-boundary]
            [re-frame.story.play.substrate-boundary :as rf.story.play.substrate-boundary]
            [re-frame.story.requirements :as rf.story.requirements]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]))

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
  (assoc rf.substrate.plain-atom/adapter
         :flush-render! (fn [f] (f) (swap! commits inc) nil)))

;; NOT `rf.story.late-bind/clear!` — that wipes the canonical shims every sibling
;; test ns registers at load time (`:run-play-step`, `:clear-step-boundaries`,
;; `:ensure-canonical-installed`), and the wipe outlives this ns in a shared
;; JVM. Snapshot the whole map and restore it, so exactly the one slot this
;; bead is about is under test and nothing else moves.

(use-fixtures :each
  (fn [t]
    (let [saved @rf.story.late-bind/hooks]
      (reset! commits 0)
      (swap! rf.story.late-bind/hooks dissoc :settled-boundary-hooks)
      ;; Cold-start the adapter slot in the PROLOGUE, not only the epilogue.
      ;; `committing-adapter` is `plain-atom` plus one optional contract fn, so
      ;; it carries plain-atom's canonical `:rf.adapter/plain-atom` kind — and
      ;; `init!` is idempotent for the seated adapter (rf2-kuky.1). If a sibling
      ;; suite in this shared runtime has already seated plain-atom, the `init!`
      ;; below is a same-adapter no-op, `:flush-render!` is never installed, and
      ;; the witness silently measures nothing. Destroying first means the first
      ;; test in this ns cannot depend on process order.
      (try (rf/destroy-adapter!)
           (catch #?(:clj Throwable :cljs :default) _ nil))
      (try (t)
           (finally
             (reset! commits 0)
             (reset! rf.story.late-bind/hooks saved)
             (try (rf/destroy-adapter!)
                  (catch #?(:clj Throwable :cljs :default) _ nil)))))))

(defn- dom-assert-step
  "A folded in-script DOM checkpoint — the shape a shipping
  `[:assert-dom sel :text \"x\"]` has by the time the executor sees it."
  []
  [:assert [rf.story.assertions/id-dom-text "[data-test=x]" "42"]])

;; ---- the producer --------------------------------------------------------

(deftest headless-when-no-adapter-is-seated
  (testing "with no adapter installed the producer yields the headless
            default — the JVM floor is unchanged, which is what makes
            installing it unconditionally safe"
    (is (nil? (rf.story.play.substrate-boundary/adapter-flush-render)))
    (let [hooks (rf.story.play.substrate-boundary/substrate-flush-hooks :any-frame)]
      (is (identical? rf.story.play.settled-boundary/headless-flush-hooks hooks))
      (is (= :headless (rf.story.play.settled-boundary/hooks-provided-boundary hooks))))))

(deftest headless-when-adapter-ships-no-flush-render
  (testing "an adapter with no live commit (plain-atom, SSR) ships no
            :flush-render!, so the producer stays at the headless floor
            rather than claiming a :dom boundary it cannot honour"
    (rf/init! rf.substrate.plain-atom/adapter)
    (is (nil? (:flush-render! (rf/current-adapter-spec)))
        "precondition: plain-atom ships no :flush-render!")
    (is (nil? (rf.story.play.substrate-boundary/adapter-flush-render)))
    (is (= :headless
           (rf.story.play.settled-boundary/hooks-provided-boundary
             (rf.story.play.substrate-boundary/substrate-flush-hooks :any-frame))))))

(deftest provides-dom-when-the-live-adapter-can-commit
  (testing "an adapter shipping :flush-render! lifts the declared boundary
            to :dom and registers the commit at both richer rungs"
    (rf/init! (committing-adapter))
    (let [hooks (rf.story.play.substrate-boundary/substrate-flush-hooks :f)]
      (is (= :dom (rf.story.play.settled-boundary/hooks-provided-boundary hooks)))
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
    (rf/init! rf.substrate.plain-atom/adapter)
    (is (= :headless (rf.story.play.settled-boundary/hooks-provided-boundary
                       (rf.story.play.substrate-boundary/substrate-flush-hooks :f))))
    (rf/destroy-adapter!)
    (rf/init! (committing-adapter))
    (is (= :dom (rf.story.play.settled-boundary/hooks-provided-boundary
                  (rf.story.play.substrate-boundary/substrate-flush-hooks :f))))))

;; ---- the slot ------------------------------------------------------------

(deftest install-registers-the-late-bind-slot
  (testing "before install! the slot is empty and the runner falls back to
            headless — the state every host shipped in before rf2-ek9qb"
    (is (nil? (rf.story.late-bind/get-fn :settled-boundary-hooks)))
    (rf/init! (committing-adapter))
    (is (= :headless
           (rf.story.play.settled-boundary/hooks-provided-boundary
             (rf.story.play.runner-events/current-flush-hooks :f)))
        "WITNESS: with no producer the live shell plays at :provides :headless"))
  (testing "after install! the runner resolves the substrate hooks"
    (rf.story.play.substrate-boundary/install!)
    (is (some? (rf.story.late-bind/get-fn :settled-boundary-hooks)))
    (is (= :dom
           (rf.story.play.settled-boundary/hooks-provided-boundary
             (rf.story.play.runner-events/current-flush-hooks :f))))))

;; ---- the settle, which is the point --------------------------------------

(deftest substrate-commits-before-a-dom-step
  (testing "WITNESS (rf2-ek9qb): a step that reads the DOM now runs against
            a COMMITTED substrate. Without the producer + the pre-step
            settle this count stays 0 — the runner never asked the
            substrate to commit, and the only thing between an event and a
            DOM read was a setTimeout 0"
    (rf/init! (committing-adapter))
    (rf.story.play.substrate-boundary/install!)
    (is (zero? @commits))
    (rf.story.play.runner-events/exec-step! :f 0 (dom-assert-step))
    (is (pos? @commits)
        "a DOM-family checkpoint must settle the substrate before reading")))

(deftest settle-is-synchronous-not-scheduled
  (testing "the commit lands INSIDE the exec-step! call — no tick, no
            timer, nothing to race. This is the property a longer
            setTimeout could never buy"
    (rf/init! (committing-adapter))
    (rf.story.play.substrate-boundary/install!)
    (let [before @commits
          _      (rf.story.play.runner-events/exec-step! :f 0 (dom-assert-step))
          after  @commits]
      (is (> after before)
          "already committed by the time exec-step! returned"))))

(deftest headless-steps-do-not-commit
  (testing "a step that requires only :headless does not drag the
            substrate through a commit — the ladder still means what it
            says, and the cheap path stays cheap"
    (rf/init! (committing-adapter))
    (rf.story.play.substrate-boundary/install!)
    (rf.story.play.runner-events/exec-step! :f 0 [:wait-until [:queue-empty]])
    (is (zero? @commits))))

(deftest no-producer-means-no-commit
  (testing "the fix is the PRODUCER, not the pre-step settle alone: with
            the slot empty, the same DOM step commits nothing even though
            the adapter could have"
    (rf/init! (committing-adapter))
    (rf.story.play.runner-events/exec-step! :f 0 (dom-assert-step))
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
             (rf.story.play.settled-boundary/settle-to! :f hooks :dom)))
      (is (= [:headless :cljs-reactive :dom] @seen)))))

(deftest settle-to-is-inert-below-its-rung
  (testing "a cheaper `required` stops the ladder where it should"
    (let [seen  (atom [])
          hooks {:provides :dom
                 :flush!   {:cljs-reactive (fn [_] (swap! seen conj :cljs-reactive))
                            :dom           (fn [_] (swap! seen conj :dom))}}]
      (rf.story.play.settled-boundary/settle-to! :f hooks :headless)
      (is (= [] @seen)))))

(deftest settle-to-reports-a-throwing-flush
  (testing "a flush that throws is reported as :error — never swallowed,
            never a silent pass"
    (let [hooks {:provides :dom
                 :flush!   {:dom (fn [_] (throw (ex-info "boom" {})))}}
          res   (rf.story.play.settled-boundary/settle-to! :f hooks :dom)]
      (is (= :error (:status res))))))

(deftest settle-to-honours-the-timeout-budget
  (testing "an over-budget flush phase refuses fail-closed with
            :flush-timeout rather than reporting a settle it did not earn"
    (let [hooks {:provides   :dom
                 :timeout-ms -1
                 :flush!     {:dom (fn [_] nil)}}
          res   (rf.story.play.settled-boundary/settle-to! :f hooks :dom)]
      (is (= :cannot-run (:status res)))
      (is (= :flush-timeout (:reason res))))))

;; ===========================================================================
;; THE TERMINAL PATH — where the settle used to be asked and then ignored
;; ===========================================================================
;;
;; Merged-PR audit #8313 reopened this bead on one missed path.
;; `exec-step!` short-circuits correctly — `(or (settle-substrate-for-step!
;; …) (case stype …))`, so an in-script DOM checkpoint cannot run against a
;; substrate that failed to commit. `run-terminal-assertions!` called the
;; SAME settle, DISCARDED its result, and then called `exec-assert!`
;; unconditionally. The audit's control redefined the settle to error and
;; the executor to record its invocation, and got `[:settle-error
;; :assert-ran]` back: both ran.
;;
;; Two consequences, and the second is the one that bites. The assertion
;; READ a substrate whose commit had just thrown or timed out — so it could
;; record a PASS it had not earned. And the settle failure itself
;; disappeared: terminal assertions are not a runner step stream, so the
;; discarded step-result had nowhere to land, and the ONE accumulator the
;; terminal verdict is folded from (`:rf.story/assertions`) never heard
;; about it. A DOM-family atom records NOTHING under a headless runner (the
;; executor's own `{:skipped? true}` branch declines to mint a vacuous
;; record), which is precisely why the witnesses below can count: before
;; the repair the accumulator held ZERO records for a failed settle, after
;; it holds exactly one carrying the refusal.

(def ^:private terminal-frame
  "A real registered frame — `rf.story.assertions/record!` lands its record by
  dispatching into the frame's app-db, and swallows the dispatch when the
  frame is gone. `:f` (which every test above uses) is never registered:
  fine for the settle-count witnesses, useless for a record witness."
  :story.substrate-boundary/terminal)

(defn- terminal-frame!
  "Seat the adapter, (re-)register `terminal-frame`, and install the
  canonical assertion vocabulary so `::append` has a handler. Called per
  test rather than from the fixture: the fixture is shared with the
  settle-count witnesses above, which deliberately run with no frame."
  []
  (rf/init! (committing-adapter))
  (rf.story/install-canonical-vocabulary!)
  (rf.frame/ensure-default-frame!)
  (swap! rf.frame/frames dissoc terminal-frame)
  (rf/make-frame {:id terminal-frame :doc "terminal-assertion settle witness"}))

(defn- install-hooks!
  "Register `hooks` as the active flush-hooks for every frame."
  [hooks]
  (rf.story.late-bind/set-fn! :settled-boundary-hooks (fn [_frame-id] hooks)))

(defn- terminal-records []
  (vec (:rf.story/assertions (rf/app-db-value terminal-frame))))

(def ^:private terminal-dom-atom
  "The terminal counterpart of `dom-assert-step` — a bare DOM-family atom,
  the shape `[:expect :assertions]` carries."
  [rf.story.assertions/id-dom-text "[data-test=x]" "42"])

(deftest terminal-assertion-refuses-when-the-commit-throws
  (testing "WITNESS (rf2-ek9qb, audit #8313): a terminal DOM assertion whose
            pre-read settle THREW must not be evaluated, and the failure
            must reach the unified verdict. Before the repair the throw was
            discarded and the accumulator held nothing at all — the run
            reported on a substrate that had blown up mid-commit as though
            nothing had happened"
    (terminal-frame!)
    (install-hooks! {:provides :dom
                     :flush!   {:dom (fn [_] (throw (ex-info "commit blew up" {})))}})
    (rf.story.play.runner-events/run-terminal-assertions! terminal-frame [terminal-dom-atom])
    (let [recs (terminal-records)]
      (is (= 1 (count recs))
          "the settle failure reached the ONE terminal accumulator")
      (is (= rf.story.assertions/id-dom-text (:assertion (first recs)))
          "recorded against the atom it refused, not a synthetic id")
      (is (= :error (:status (first recs))))
      (is (false? (:passed? (first recs))))
      (is (= :error (rf.story.requirements/aggregate-status recs nil))
          "and folds to :error through the ONE aggregation rule — a
           substrate that threw mid-commit is a fault, not a refusal"))))

(deftest terminal-assertion-refuses-when-the-commit-times-out
  (testing "WITNESS (rf2-ek9qb, audit #8313): the same for the other way a
            settle declines — an over-budget flush phase. `settle-to!`
            returns the fail-closed :flush-timeout refusal, so the terminal
            verdict must read :cannot-run: the runner could not establish
            the boundary the assertion needed, and therefore proved nothing"
    (terminal-frame!)
    (install-hooks! {:provides   :dom
                     :timeout-ms -1
                     :flush!     {:dom (fn [_] nil)}})
    (rf.story.play.runner-events/run-terminal-assertions! terminal-frame [terminal-dom-atom])
    (let [recs (terminal-records)]
      (is (= 1 (count recs)))
      (is (= :cannot-run (:status (first recs))))
      (is (true? (:cannot-run? (first recs))))
      (is (= :cannot-run (rf.story.requirements/aggregate-status recs nil))
          "the distinct THIRD status — never a silent pass"))))

(deftest a-settled-terminal-assertion-still-evaluates
  (testing "the repair must not over-fire. With a commit that SUCCEEDS the
            terminal path is exactly what it was: the substrate is asked to
            commit, and the executor — not a refusal record — owns the
            verdict. Under a headless runner that DOM executor declines to
            mint a record at all, so an empty accumulator here is the proof
            that nothing was refused"
    (terminal-frame!)
    (install-hooks! {:provides :dom :flush! {:dom (fn [_] nil)}})
    (reset! commits 0)
    (rf.story.play.runner-events/run-terminal-assertions! terminal-frame [terminal-dom-atom])
    (is (empty? (terminal-records))
        "no refusal record was minted for a settle that succeeded")))

(deftest a-headless-terminal-assertion-is-untouched
  (testing "the JVM / node-runtime path is unchanged: below :cljs-reactive
            `settle-substrate-for-step!` returns nil without consulting a
            hook, so a handler-backed terminal atom takes the same
            `exec-assert!` arm it always did — even under hooks whose :dom
            flush would throw if it were ever reached"
    (terminal-frame!)
    (install-hooks! {:provides :dom
                     :flush!   {:dom (fn [_] (throw (ex-info "must not run" {})))}})
    (rf/reg-event ::seed (fn [{:keys [db]} [_ m]] {:db (merge db m)}))
    (rf/dispatch-sync [::seed {:status :loaded}] {:frame terminal-frame})
    (rf.story.play.runner-events/run-terminal-assertions!
      terminal-frame [[:rf.assert/path-equals [:status] :loaded]])
    (let [recs (filterv #(= :rf.assert/path-equals (:assertion %)) (terminal-records))]
      (is (= 1 (count recs)) "the handler-backed atom recorded exactly once")
      (is (true? (:passed? (first recs)))
          "and it PASSED — the :dom flush was never consulted"))))

#?(:clj
   (deftest jvm-only-a-refused-terminal-settle-never-reaches-the-executor
     ;; JVM-ONLY, and the name says so. The audit's control is the only
     ;; direct read of "did `exec-assert!` run?": a DOM-family atom records
     ;; nothing under a headless runner either way, so the count witnesses
     ;; above prove the failure ARRIVES without proving the evaluation was
     ;; PREVENTED. Var redefinition answers that — and only on the JVM.
     ;; `with-redefs` in CLJS mutates a var whose call sites the compiler
     ;; may already have inlined (`:static-fns`), so a spy that never fires
     ;; would read as a PASS there: a false green, which is worse than no
     ;; witness at all.
     (testing "WITNESS (rf2-ek9qb, audit #8313): the refusal REPLACES the
               evaluation. The audit's control got [:settle-error
               :assert-ran] from the landed fn — both ran"
       (terminal-frame!)
       (install-hooks! {:provides :dom
                        :flush!   {:dom (fn [_] (throw (ex-info "commit blew up" {})))}})
       (let [ran (atom [])]
         (with-redefs-fn {#'rf.story.play.runner-events/exec-assert!
                          (fn [_frame-id _idx _step] (swap! ran conj :assert-ran) nil)}
           (fn []
             (rf.story.play.runner-events/run-terminal-assertions! terminal-frame [terminal-dom-atom])))
         (is (= [] @ran)
             "the assertion executor was NOT invoked behind a failed settle")
         (is (= [:error] (mapv :status (terminal-records)))
             "and the refusal is what the terminal verdict folds")))))
