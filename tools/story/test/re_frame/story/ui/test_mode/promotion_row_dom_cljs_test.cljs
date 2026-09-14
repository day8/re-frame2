(ns re-frame.story.ui.test-mode.promotion-row-dom-cljs-test
  "DOM-mount acceptance for rf2-vgthk: the Test pane offers promotion for a
  variant whose `:script` dispatches nothing.

  Every variant of the login_form testbed — the Story tutorial's flagship
  demo — is a `:setup` precondition plus a `:script` of `[:assert …]`
  checkpoints. Test mode records a run's DISPATCH-ONLY projection of its
  program (`:play-events`), which for that shape is empty, and the promotion
  row is gated on a capturable artifact, so the row never rendered and the
  demo could not be promoted from Test mode. Whether the row renders is a
  React commit fact, so this mounts the real pane. The same row must survive
  a checkpoint that reads an input only the run's `:cell-overrides` supply
  (rf2-cml0h): the gate compiles the source with the inputs the run received.

  The fixture mirrors `login-form.stories-cljs-test`: the source-store
  baseline is captured ONCE at ns load, so the variant frames' `login-form.**`
  image resolves whatever a sibling test ns cleared first.

  `-dom-cljs-test$` opts the file into the `:browser-test` build.
  `:node-test` also loads it (its `cljs-test$` regex matches) and the body
  self-gates on `(browser?)`."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures async]]
            ["react-dom" :as react-dom]
            [reagent.dom.client :as rdc]
            [re-frame.core :as rf]
            [re-frame.frame :as rf.frame]
            [re-frame.machines :as rf.machines]
            [re-frame.source-store :as rf.source-store]
            [re-frame.test-support :as rf.test-support]
            [re-frame.adapter.reagent :as rf.adapter.reagent]
            [re-frame.story :as rf.story]
            [re-frame.story.loaders :as rf.story.loaders]
            [re-frame.story.registrar :as rf.story.registrar]
            [re-frame.story.ui.state :as rf.story.ui.state]
            [re-frame.story.ui.test-mode.state :as rf.story.ui.test-mode.state]
            [re-frame.story.ui.test-mode.view :as rf.story.ui.test-mode.view]
            [login-form.events]
            [login-form.subs]
            [login-form.stories :as lf-stories]))

;; ---- fixture --------------------------------------------------------------

(def ^:private variant-id :story.login-form/idle)

(def ^:private registrar-snapshot (atom nil))

(def ^:private source-store-baseline @rf.source-store/kind->id->ns->descriptor)

(defn- before! []
  (reset! registrar-snapshot (rf.test-support/snapshot-registrar))
  (reset! rf.frame/frames {})
  (try (rf/init! rf.adapter.reagent/adapter) (catch :default _ nil))
  (rf.frame/ensure-default-frame!)
  (rf.machines/reset-timers!)
  (rf.story.loaders/clear-watchers!)
  (rf.story/clear-all!)
  (reset! rf.source-store/kind->id->ns->descriptor source-store-baseline)
  (rf.machines/install-machine-runtime!)
  (reset! rf.story.ui.test-mode.state/results-atom {})
  (rf.story.ui.state/reset-shell-state!)
  (lf-stories/register-all!))

(defn- after! []
  (when-let [snap @registrar-snapshot]
    (rf.test-support/restore-registrar! snap)
    (reset! registrar-snapshot nil))
  (reset! rf.story.ui.test-mode.state/results-atom {})
  (reset! rf.frame/frames {}))

(use-fixtures :each {:before before! :after after!})

;; ---- helpers --------------------------------------------------------------

(defn- browser? []
  (and (exists? js/document)
       (some? (.-createElement js/document))))

(defn- poll-until
  "Call `(f value)` once `(probe)` returns a truthy value, or with nil once
  `timeout-ms` has passed. Polls on `setTimeout`, so Reagent's render queue
  gets to run between probes."
  [probe timeout-ms f]
  (let [deadline (+ (.now js/Date) timeout-ms)]
    (letfn [(poll []
              (let [v (probe)]
                (cond
                  v                           (f v)
                  (> (.now js/Date) deadline) (f nil)
                  :else                       (js/setTimeout poll 25))))]
      (poll))))

;; ---- the acceptance -------------------------------------------------------

(deftest checkpoint-only-variant-offers-promotion
  (testing "after a Test-mode run of :story.login-form/idle — a :setup
            precondition and one [:assert …] checkpoint, nothing dispatched
            by the script — the pane renders the promotion row (rf2-vgthk)"
    (if-not (browser?)
      (is true ":node-test — no DOM; :browser-test runs the real assertion")
      (async done
        (let [node   (js/document.createElement "div")
              _      (js/document.body.appendChild node)
              root   (rdc/create-root node)
              finish (fn []
                       (try (.unmount root) (catch :default _ nil))
                       (.remove node)
                       (done))]
          (react-dom/flushSync
            (fn [] (rdc/render root [rf.story.ui.test-mode.view/test-view variant-id])))
          (poll-until
            #(:result (get @rf.story.ui.test-mode.state/results-atom variant-id))
            10000
            (fn [result]
              (is (some? result) "precondition: the pane's auto-run stored a result")
              (is (= [] (get-in @rf.story.ui.test-mode.state/results-atom
                                [variant-id :play-events]))
                  "precondition: the script dispatches nothing, so the run's
                   dispatch-only projection is empty")
              (poll-until
                #(.querySelector node "[data-test=\"story-test-promotion-row\"]")
                3000
                (fn [row]
                  (is (some? row) "the promotion row is offered for this run")
                  (finish))))))))))

(def ^:private run-input-variant-id :story.login-form/idle-run-input)

(deftest run-input-checkpoint-variant-offers-promotion
  (testing "after a Test-mode run of a checkpoint-only variant whose [:arg]
            only the controls panel's :cell-overrides supply, the pane still
            renders the promotion row: capture compiles the source with the
            inputs the run received (rf2-cml0h)"
    (if-not (browser?)
      (is true ":node-test — no DOM; :browser-test runs the real assertion")
      (async done
        (rf.story.registrar/reg-variant* run-input-variant-id
          {:extends :story.login-form/idle
           :script  [[:assert [:rf.assert/state-is :login/flow [:arg :expected]]]]})
        (rf.story.ui.state/swap-state!
          assoc-in [:cell-overrides run-input-variant-id] {:expected :idle})
        (let [node   (js/document.createElement "div")
              _      (js/document.body.appendChild node)
              root   (rdc/create-root node)
              finish (fn []
                       (try (.unmount root) (catch :default _ nil))
                       (.remove node)
                       (done))]
          (react-dom/flushSync
            (fn [] (rdc/render root [rf.story.ui.test-mode.view/test-view run-input-variant-id])))
          (poll-until
            #(:result (get @rf.story.ui.test-mode.state/results-atom run-input-variant-id))
            10000
            (fn [result]
              (is (= :pass (:status result))
                  "precondition: the pane's auto-run received its input")
              (is (= [] (get-in @rf.story.ui.test-mode.state/results-atom
                                [run-input-variant-id :play-events]))
                  "precondition: the script dispatches nothing")
              (poll-until
                #(.querySelector node "[data-test=\"story-test-promotion-row\"]")
                3000
                (fn [row]
                  (is (some? row) "the promotion row is offered for this run")
                  (finish))))))))))
