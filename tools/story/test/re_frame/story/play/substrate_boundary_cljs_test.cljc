(ns re-frame.story.play.substrate-boundary-cljs-test
  "The `:settled-boundary-hooks` producer, and the substrate settle it
  provides in place of a timer. The witness is a COUNT of substrate
  commits, read on the statement after `exec-step!` returns, so it also
  pins that the commit is synchronous. Nothing here needs a DOM: the settle
  happens before the DOM executor runs. `.cljc` with a `-cljs-test` name, so
  the JVM and `:node-test` lanes both run it, since the production code's
  reader conditionals are invisible to a JVM-only run."
  (:require [clojure.test :refer [deftest is use-fixtures]]
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
  asked for."
  (atom 0))

(defn- committing-adapter
  "`plain-atom` plus the optional `:flush-render!`, counting instead of
  flushing (the Reagent adapter's is `(fn [f] (f) (r/flush))`)."
  []
  (assoc rf.substrate.plain-atom/adapter
         :flush-render! (fn [f] (f) (swap! commits inc) nil)))

;; Snapshot and restore the late-bind map rather than `clear!` it: sibling
;; namespaces register shims at load time that must outlive this one. The
;; adapter is destroyed first because `init!` is idempotent for a seated
;; plain-atom, which would leave `:flush-render!` uninstalled.
(use-fixtures :each
  (fn [t]
    (let [saved @rf.story.late-bind/hooks]
      (reset! commits 0)
      (swap! rf.story.late-bind/hooks dissoc :settled-boundary-hooks)
      (try (rf/destroy-adapter!)
           (catch #?(:clj Throwable :cljs :default) _ nil))
      (try (t)
           (finally
             (reset! commits 0)
             (reset! rf.story.late-bind/hooks saved)
             (try (rf/destroy-adapter!)
                  (catch #?(:clj Throwable :cljs :default) _ nil)))))))

(defn- dom-assert-step
  "A folded in-script DOM checkpoint, as the executor sees an `:assert-dom` step."
  []
  [:assert [rf.story.assertions/id-dom-text "[data-test=x]" "42"]])

;; ---- the producer --------------------------------------------------------

(deftest producer-names-no-substrate
  ;; Built from the LIVE adapter, so swapping the adapter swaps the settle
  ;; with no per-substrate entry (spec/Tool-Pair.md §Driving the render).
  ;; With no adapter seated it stays headless, which makes installing it
  ;; unconditionally safe.
  (is (identical? rf.story.play.settled-boundary/headless-flush-hooks
                  (rf.story.play.substrate-boundary/substrate-flush-hooks :f)))
  (rf/init! rf.substrate.plain-atom/adapter)
  (is (= :headless (rf.story.play.settled-boundary/hooks-provided-boundary
                     (rf.story.play.substrate-boundary/substrate-flush-hooks :f))))
  (rf/destroy-adapter!)
  (rf/init! (committing-adapter))
  (is (= :dom (rf.story.play.settled-boundary/hooks-provided-boundary
                (rf.story.play.substrate-boundary/substrate-flush-hooks :f)))))

(deftest provides-dom-when-the-live-adapter-can-commit
  ;; the commit is registered at both richer rungs, and building the hooks
  ;; commits nothing
  (rf/init! (committing-adapter))
  (let [hooks (rf.story.play.substrate-boundary/substrate-flush-hooks :f)]
    (is (zero? @commits))
    ((get-in hooks [:flush! :cljs-reactive]) :f)
    (is (= 1 @commits))
    ((get-in hooks [:flush! :dom]) :f)
    (is (= 2 @commits))))

;; ---- the slot ------------------------------------------------------------

(deftest install-registers-the-late-bind-slot
  (rf/init! (committing-adapter))
  (is (= :headless
         (rf.story.play.settled-boundary/hooks-provided-boundary
           (rf.story.play.runner-events/current-flush-hooks :f)))
      "WITNESS: with no producer the live shell plays at :provides :headless")
  (rf.story.play.substrate-boundary/install!)
  (is (= :dom
         (rf.story.play.settled-boundary/hooks-provided-boundary
           (rf.story.play.runner-events/current-flush-hooks :f)))))

;; ---- the settle, which is the point --------------------------------------

(deftest substrate-commits-before-a-dom-step
  ;; WITNESS: without the producer and the pre-step settle, nothing would ask
  ;; the substrate to commit between an event and a DOM read
  (rf/init! (committing-adapter))
  (rf.story.play.substrate-boundary/install!)
  (rf.story.play.runner-events/exec-step! :f 0 (dom-assert-step))
  (is (pos? @commits)))

(deftest headless-steps-do-not-commit
  (rf/init! (committing-adapter))
  (rf.story.play.substrate-boundary/install!)
  (rf.story.play.runner-events/exec-step! :f 0 [:wait-until [:queue-empty]])
  (is (zero? @commits)))

;; ---- the shared flush loop ----------------------------------------------

(deftest settle-to-is-inert-below-its-rung
  (let [seen  (atom [])
        hooks {:provides :dom
               :flush!   {:cljs-reactive (fn [_] (swap! seen conj :cljs-reactive))
                          :dom           (fn [_] (swap! seen conj :dom))}}]
    (rf.story.play.settled-boundary/settle-to! :f hooks :headless)
    (is (= [] @seen))))

;; ===========================================================================
;; THE TERMINAL PATH — where the settle's result must gate the read
;; ===========================================================================
;;
;; `run-terminal-assertions!` asks the same pre-read settle as an in-script
;; checkpoint and must honour its result: a terminal DOM atom must not read a
;; substrate whose commit threw or timed out, and the settle failure must
;; reach `:rf.story/assertions`, the one accumulator the terminal verdict
;; folds. A DOM atom records nothing under a headless runner, so the
;; accumulator holds zero records when the failure is dropped and exactly one
;; when it is honoured.

(def ^:private terminal-frame
  ;; a registered frame: `record!` dispatches into its app-db
  :story.substrate-boundary/terminal)

(defn- terminal-frame!
  "Seat the adapter, (re-)register `terminal-frame`, and install the
  canonical assertion vocabulary."
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
  [rf.story.assertions/id-dom-text "[data-test=x]" "42"])

(deftest terminal-assertion-refuses-when-the-commit-throws
  ;; recorded against the atom it refused, and a substrate that threw
  ;; mid-commit folds to :error, a fault rather than a refusal
  (terminal-frame!)
  (install-hooks! {:provides :dom
                   :flush!   {:dom (fn [_] (throw (ex-info "commit blew up" {})))}})
  (rf.story.play.runner-events/run-terminal-assertions! terminal-frame [terminal-dom-atom])
  (let [recs (terminal-records)]
    (is (= [[rf.story.assertions/id-dom-text :error false]]
           (mapv (juxt :assertion :status :passed?) recs)))
    (is (= :error (rf.story.requirements/aggregate-status recs nil)))))

(deftest terminal-assertion-refuses-when-the-commit-times-out
  (terminal-frame!)
  (install-hooks! {:provides   :dom
                   :timeout-ms -1
                   :flush!     {:dom (fn [_] nil)}})
  (rf.story.play.runner-events/run-terminal-assertions! terminal-frame [terminal-dom-atom])
  (let [recs (terminal-records)]
    (is (= [[:cannot-run true]] (mapv (juxt :status :cannot-run?) recs)))
    (is (= :cannot-run (rf.story.requirements/aggregate-status recs nil)))))

(deftest a-settled-terminal-assertion-still-evaluates
  ;; a commit that succeeds mints no refusal record
  (terminal-frame!)
  (install-hooks! {:provides :dom :flush! {:dom (fn [_] nil)}})
  (rf.story.play.runner-events/run-terminal-assertions! terminal-frame [terminal-dom-atom])
  (is (empty? (terminal-records))))

(deftest a-headless-terminal-assertion-is-untouched
  ;; below :cljs-reactive no hook is consulted, even one whose :dom flush throws
  (terminal-frame!)
  (install-hooks! {:provides :dom
                   :flush!   {:dom (fn [_] (throw (ex-info "must not run" {})))}})
  (rf/reg-event ::seed (fn [{:keys [db]} [_ m]] {:db (merge db m)}))
  (rf/dispatch-sync [::seed {:status :loaded}] {:frame terminal-frame})
  (rf.story.play.runner-events/run-terminal-assertions!
    terminal-frame [[:rf.assert/path-equals [:status] :loaded]])
  (is (= [true] (mapv :passed? (filterv #(= :rf.assert/path-equals (:assertion %)) (terminal-records))))))

#?(:clj
   (deftest jvm-only-a-refused-terminal-settle-never-reaches-the-executor
     ;; The counts above prove the failure arrives, not that the evaluation
     ;; was prevented; only a spy on the executor reads that. JVM-only:
     ;; `with-redefs` in CLJS may miss call sites the compiler inlined, so a
     ;; spy that never fires would read as a pass.
     (terminal-frame!)
     (install-hooks! {:provides :dom
                      :flush!   {:dom (fn [_] (throw (ex-info "commit blew up" {})))}})
     (let [ran (atom [])]
       (with-redefs-fn {#'rf.story.play.runner-events/exec-assert!
                        (fn [_frame-id _idx _step] (swap! ran conj :assert-ran) nil)}
         (fn []
           (rf.story.play.runner-events/run-terminal-assertions! terminal-frame [terminal-dom-atom])))
       (is (= [] @ran) "the refusal replaces the evaluation"))))
