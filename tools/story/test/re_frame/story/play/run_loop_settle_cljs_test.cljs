(ns re-frame.story.play.run-loop-settle-cljs-test
  "The run-loop's precondition POLL, driven end to end through `run!`. The
  sibling `step-settle-cljs-test` reads the poll's DECISION; these read what
  the LOOP records with it.

  `.cljs` because the precondition mechanism is CLJS-only: the JVM runner has
  no DOM, so every precondition reads as met there. The fixture installs a
  `document` whose every query MISSES, so `dom-available?` reads true and a
  selector precondition is genuinely, permanently unmet — the node runtime's
  only other unmet precondition, the event queue, clears within a tick.

  A budget-exhausted poll takes 2 seconds, so a red run is slow; nothing
  asserts on elapsed time, only on the recorded results."
  (:require [cljs.test :refer [async deftest is use-fixtures]]
            [goog.object :as gobj]
            [re-frame.core :as rf]
            [re-frame.frame :as rf.frame]
            [re-frame.registrar :as rf.registrar]
            [re-frame.story :as rf.story]
            [re-frame.story.late-bind :as rf.story.late-bind]
            [re-frame.story.play.runner-events :as rf.story.play.runner-events]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]))

(def ^:private run-frame :story.run-loop-settle/frame)

(def ^:private absent-selector "[data-test=gone]")

(defn- missing-everything-document
  "Enough for `dom/dom-available?` (it probes for `.querySelector`) and for
  `dom/query` / `dom/query-all`."
  []
  #js {:querySelector    (fn [_] nil)
       :querySelectorAll (fn [_] #js [])})

(defn- setup! []
  (rf.story/clear-all!)
  (rf.registrar/clear-all!)
  (reset! rf.frame/frames {})
  (try (rf/init! rf.substrate.plain-atom/adapter) (catch :default _ nil))
  (reset! rf.story.play.runner-events/run-state {})
  (rf.story.play.runner-events/clear-all-runs!)
  (reset! rf.story.play.runner-events/step-boundaries {})
  (rf.story/install-canonical-vocabulary!)
  (rf.frame/ensure-default-frame!)
  (rf/make-frame {:id run-frame :doc "run-loop settle witness frame"})
  (rf/reg-event :run-loop-settle/inc
                (fn [{:keys [db]} _] {:db (update db :n (fnil inc 0))})))

;; Snapshot the whole late-bind map rather than `clear!`-ing it: the wipe
;; would take the canonical shims every sibling ns registers at load time
;; with it, and outlive this ns in a shared runtime.
(def ^:private saved-hooks (atom nil))

(use-fixtures :each
  {:before (fn []
             (reset! saved-hooks @rf.story.late-bind/hooks)
             ;; The `document` goes in AFTER the runtime is seated: Xray probes
             ;; for its layout host as the runtime installs, and a document
             ;; that misses everything makes it report a `console.error`.
             (setup!)
             (gobj/set js/globalThis "document" (missing-everything-document)))
   :after  (fn []
             (js-delete js/globalThis "document")
             (reset! rf.story.late-bind/hooks @saved-hooks)
             (rf.story.play.runner-events/clear-all-runs!)
             (try (rf/destroy-adapter!) (catch :default _ nil)))})

(defn- install-hooks!
  "Seat `hooks` as the active flush-hooks for every frame."
  [hooks]
  (rf.story.late-bind/set-fn! :settled-boundary-hooks (fn [_frame-id] hooks)))

(defn- run-script!
  "Drive `script` through the explicit-spec arity of `run!`, the entry path
  that does not fold, so a raw `:assert-dom` reaches the loop as written."
  [script done-cb]
  (rf.story.play.runner-events/run! run-frame "witness" {:name "witness" :script script} done-cb))

(defn- message-of [result]
  (str (:message result)))

(deftest raw-assert-dom-hidden-passes-on-absence
  ;; Read as a bare selector, a raw `[:assert-dom sel :hidden]` would demand
  ;; the node be PRESENT, wait out the settle budget and fail; the folded
  ;; entry path passes it at once.
  (async done
    (run-script!
      [[:assert-dom absent-selector :hidden]]
      (fn [final]
        (let [r (first (:results final))]
          (is (= [1 true :pass] [(count (:results final)) (:passed? r) (:status final)])
              (message-of r)))
        (done)))))

(deftest a-failing-commit-while-the-selector-is-absent-is-recorded-verbatim
  ;; A parked step never reaches `exec-step!`, so a failing commit under an
  ;; unmet precondition is recorded once, as itself, not replaced by the
  ;; generic precondition timeout.
  (async done
    (install-hooks!
      {:provides :dom
       :flush!   {:dom (fn [_] (throw (ex-info "commit blew up" {})))}})
    (run-script!
      [[:assert-dom absent-selector :text "42"]]
      (fn [final]
        (let [r (first (:results final))]
          (is (= [1 true] [(count (:results final)) (:exception r)]))
          (is (re-find #"commit blew up" (message-of r))))
        (done)))))

(deftest a-refusing-commit-while-the-selector-is-absent-is-recorded-verbatim
  ;; an over-budget flush is the fail-closed `:flush-timeout` refusal
  (async done
    (install-hooks!
      {:provides :dom :timeout-ms -1 :flush! {:dom (fn [_] nil)}})
    (run-script!
      [[:assert-dom absent-selector :text "42"]]
      (fn [final]
        (let [r (first (:results final))]
          (is (= [1 true :flush-timeout]
                 [(count (:results final)) (:cannot-run? r) (:reason r)])))
        (done)))))

(deftest a-genuine-precondition-timeout-advances-exactly-once
  ;; With a commit that succeeds, an absent selector is a real un-settleable
  ;; precondition: the step fails naming it, records once rather than once
  ;; per poll tick, and the FOLLOWING step still runs.
  (async done
    (install-hooks! {:provides :dom :flush! {:dom (fn [_] nil)}})
    (run-script!
      [[:assert-dom absent-selector :text "42"]
       [:dispatch-sync [:run-loop-settle/inc]]]
      (fn [final]
        (let [r (first (:results final))]
          (is (= [2 2 false 1]
                 [(count (:results final)) (:step-idx final) (:passed? r)
                  (:n (rf/app-db-value run-frame))]))
          (is (re-find #"never settled" (message-of r)))
          (is (re-find (re-pattern absent-selector) (message-of r))))
        (done)))))
