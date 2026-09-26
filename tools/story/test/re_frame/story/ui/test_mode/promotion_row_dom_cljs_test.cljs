(ns re-frame.story.ui.test-mode.promotion-row-dom-cljs-test
  "DOM-mount acceptance: the Test pane offers promotion for a variant whose
  `:script` dispatches nothing.

  Every variant of the login_form testbed — the Story tutorial's flagship
  demo — is a `:setup` precondition plus a `:script` of `[:assert …]`
  checkpoints. Test mode records a run's DISPATCH-ONLY projection of its
  program (`:play-events`), which for that shape is empty, and the promotion
  row is gated on a capturable artifact, so a capture that needed dispatched
  events would never render the row and the demo could not be promoted from
  Test mode. Whether the row renders is a React commit fact, so this mounts
  the real pane with its canvas, whose run fills the pane, after preparing
  the variant the way the shell's selection edge does. The same row must
  survive a checkpoint that reads an input only the run's `:cell-overrides`
  supply: the gate compiles the source with the inputs the run received.

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
            [re-frame.story.runtime :as rf.story.runtime]
            [re-frame.story.ui.canvas :as rf.story.ui.canvas]
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
  (rf.story.runtime/reset-run-owner!)
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

(defn- render-pane!
  "Select `vid`, prepare its run the way the shell's selection edge does,
  and render the pane with its canvas into `root`. The canvas resumes the
  run once it has mounted, and the pane stores the result."
  [root vid]
  (rf.story.ui.state/swap-state! rf.story.ui.state/select-variant vid)
  (rf.story.runtime/prepare-run!
    vid (rf.story.ui.canvas/run-opts
          (rf.story.ui.canvas/run-key (rf.story.ui.state/get-state) vid)))
  (react-dom/flushSync
    (fn [] (rdc/render root [rf.story.ui.test-mode.view/test-view vid
                             [rf.story.ui.canvas/canvas]]))))

;; ---- the acceptance -------------------------------------------------------

(deftest checkpoint-only-variant-offers-promotion
  (testing "after a Test-mode run of :story.login-form/idle — a :setup
            precondition and one [:assert …] checkpoint, nothing dispatched
            by the script — the pane renders the promotion row"
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
          (render-pane! root variant-id)
          (poll-until
            #(:result (get @rf.story.ui.test-mode.state/results-atom variant-id))
            10000
            (fn [result]
              (is (some? result) "precondition: the canvas's run reached the pane")
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
            inputs the run received"
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
          (render-pane! root run-input-variant-id)
          (poll-until
            #(:result (get @rf.story.ui.test-mode.state/results-atom run-input-variant-id))
            10000
            (fn [result]
              (is (= :pass (:status result))
                  "precondition: the canvas's run received its input")
              (is (= [] (get-in @rf.story.ui.test-mode.state/results-atom
                                [run-input-variant-id :play-events]))
                  "precondition: the script dispatches nothing")
              (poll-until
                #(.querySelector node "[data-test=\"story-test-promotion-row\"]")
                3000
                (fn [row]
                  (is (some? row) "the promotion row is offered for this run")
                  (finish))))))))))
