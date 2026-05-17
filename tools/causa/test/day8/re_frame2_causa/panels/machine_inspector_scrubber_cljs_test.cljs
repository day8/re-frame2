(ns day8.re-frame2-causa.panels.machine-inspector-scrubber-cljs-test
  "CLJS-side view tests for Causa's Machine Inspector mini-scrubber
  (rf2-nqw0v, Phase 5).

  Covers:

    1. The scrubber renders nothing when no arc data is present.
    2. With an arc, the slider + present-button + label all render.
    3. The slider's `data-position` reflects the scrubber sub.
    4. The slider's max attribute = arc-length - 1.
    5. Drag (`on-change`) dispatches `:rf.causa/set-scrubber-position`.
    6. The present-button is disabled at :present and enabled when
       scrubbed back."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]
            [day8.re-frame2-causa.preload :as preload]
            [day8.re-frame2-causa.registry :as registry]
            [day8.re-frame2-causa.test-support :as causa-test-support]
            [day8.re-frame2-causa.trace-bus :as trace-bus]
            [day8.re-frame2-causa.panels.machine-inspector :as machine-inspector]
            [day8.re-frame2-causa.panels.machine-inspector-scrubber
             :as scrubber]))

;; ---- fixtures -----------------------------------------------------------

(defn- causa-init! []
  (causa-test-support/reset-all!)
  (trace-bus/clear-buffer!))

(use-fixtures :each
  (test-support/reset-runtime-fixture
    {:adapter plain-atom/adapter
     :init-fn causa-init!}))

;; ---- hiccup walkers -----------------------------------------------------

(declare expand-fn-component)

(defn- expand-children [node]
  (cond
    (vector? node) (mapv expand-fn-component node)
    (seq? node)    (map  expand-fn-component node)
    :else          node))

(defn- expand-fn-component [node]
  (if (and (vector? node) (fn? (first node)))
    (expand-children (apply (first node) (rest node)))
    (expand-children node)))

(defn- hiccup-seq [tree]
  (let [expanded (expand-fn-component tree)]
    (tree-seq (some-fn vector? seq?) seq expanded)))

(defn- find-by-testid [tree testid]
  (some (fn [node]
          (when (and (vector? node)
                     (map? (second node))
                     (= testid (:data-testid (second node))))
            node))
        (hiccup-seq tree)))

(defn- setup-causa-frame! []
  (registry/register-causa-handlers!)
  (frame/reg-frame :rf/causa {}))

(defn- override-machines! [machines]
  (rf/dispatch-sync
    [:rf.causa/set-registered-machines-override-for-test machines]))

(defn- override-definitions! [definitions]
  (rf/dispatch-sync
    [:rf.causa/set-machine-definitions-override-for-test definitions]))

(def ^:private fixture-definition
  {:initial :idle
   :states  {:idle    {:on {:start :authing}}
             :authing {:on {:ok :done :err :failed}}
             :done    {:final? true}
             :failed  {:final? true}}})

(defn- push-transition!
  [id from to event]
  (trace-bus/collect-trace!
    {:id id :time id
     :operation :rf.machine/transition
     :tags {:machine-id :auth/login
            :from from
            :to to
            :event event
            :dispatch-id (str "d-" id)}}))

;; ---- (1) renders nothing when arc empty --------------------------------

(deftest scrubber-renders-nil-when-arc-empty
  (setup-causa-frame!)
  (rf/with-frame :rf/causa
    (override-machines! [])
    (let [tree (scrubber/ScrubberStrip)]
      (is (nil? tree)
          "no arc data → no scrubber"))))

;; ---- (2) renders chrome when arc present -------------------------------

(deftest scrubber-renders-when-arc-present
  (setup-causa-frame!)
  (rf/with-frame :rf/causa
    (override-machines!    [:auth/login])
    (override-definitions! {:auth/login fixture-definition})
    (push-transition! 1 :idle    :authing [:auth/start])
    (push-transition! 2 :authing :done    [:auth/ok])
    (let [tree (scrubber/ScrubberStrip)]
      (is (some? (find-by-testid tree "rf-causa-machine-inspector-scrubber"))
          "scrubber container present")
      (is (some? (find-by-testid
                   tree "rf-causa-machine-inspector-scrubber-input"))
          "slider input present")
      (is (some? (find-by-testid
                   tree "rf-causa-machine-inspector-scrubber-present"))
          "present-button present")
      (is (some? (find-by-testid
                   tree "rf-causa-machine-inspector-scrubber-label"))
          "position-label present"))))

;; ---- (3) slider data-position reflects scrubber sub --------------------

(deftest slider-data-position-is-present-by-default
  (setup-causa-frame!)
  (rf/with-frame :rf/causa
    (override-machines!    [:auth/login])
    (override-definitions! {:auth/login fixture-definition})
    (push-transition! 1 :idle :authing [:auth/start])
    (let [tree  (scrubber/ScrubberStrip)
          input (find-by-testid
                  tree "rf-causa-machine-inspector-scrubber-input")]
      (is (= "present" (:data-position (second input)))))))

(deftest slider-data-position-updates-on-scrub
  (setup-causa-frame!)
  (rf/with-frame :rf/causa
    (override-machines!    [:auth/login])
    (override-definitions! {:auth/login fixture-definition})
    (push-transition! 1 :idle    :authing [:auth/start])
    (push-transition! 2 :authing :done    [:auth/ok])
    (rf/dispatch-sync [:rf.causa/set-scrubber-position 1])
    (let [tree  (scrubber/ScrubberStrip)
          input (find-by-testid
                  tree "rf-causa-machine-inspector-scrubber-input")]
      (is (= "1" (:data-position (second input)))))))

;; ---- (4) slider max attr = arc-length - 1 ------------------------------

(deftest slider-max-is-arc-tail-index
  (setup-causa-frame!)
  (rf/with-frame :rf/causa
    (override-machines!    [:auth/login])
    (override-definitions! {:auth/login fixture-definition})
    (push-transition! 1 :idle    :authing [:auth/start])
    (push-transition! 2 :authing :done    [:auth/ok])
    (let [tree  (scrubber/ScrubberStrip)
          input (find-by-testid
                  tree "rf-causa-machine-inspector-scrubber-input")]
      ;; arc-length = 3 (origin + 2 transitions); max idx = 2
      (is (= 2 (:max (second input)))))))

;; ---- (5) on-change dispatches set-scrubber-position --------------------

(deftest slider-onchange-dispatches-scrubber-position
  (setup-causa-frame!)
  (rf/with-frame :rf/causa
    (override-machines!    [:auth/login])
    (override-definitions! {:auth/login fixture-definition})
    (push-transition! 1 :idle    :authing [:auth/start])
    (push-transition! 2 :authing :done    [:auth/ok])
    (let [dispatches (atom [])]
      (with-redefs [rf/dispatch* (fn
                                   ([ev]      (swap! dispatches conj ev) nil)
                                   ([ev _opts] (swap! dispatches conj ev) nil))]
        (let [tree    (scrubber/ScrubberStrip)
              input   (find-by-testid
                        tree "rf-causa-machine-inspector-scrubber-input")
              handler (:on-change (second input))
              fake-ev #js {:target #js {:value "1"}}]
          (is (some? handler))
          (when handler (handler fake-ev))))
      (is (some (fn [ev] (and (= :rf.causa/set-scrubber-position (first ev))
                              (= 1 (second ev))))
                @dispatches)
          "dragging the slider to 1 dispatches position=1"))))

(deftest slider-at-tail-position-flips-to-present
  (testing "when the user drags to the max-idx value, the dispatch sends
            :present rather than the literal max-idx int so the head-
            tracking semantics survive."
    (setup-causa-frame!)
    (rf/with-frame :rf/causa
      (override-machines!    [:auth/login])
      (override-definitions! {:auth/login fixture-definition})
      (push-transition! 1 :idle    :authing [:auth/start])
      (push-transition! 2 :authing :done    [:auth/ok])
      (let [dispatches (atom [])]
        (with-redefs [rf/dispatch* (fn
                                     ([ev]      (swap! dispatches conj ev) nil)
                                     ([ev _opts] (swap! dispatches conj ev) nil))]
          (let [tree    (scrubber/ScrubberStrip)
                input   (find-by-testid
                          tree "rf-causa-machine-inspector-scrubber-input")
                handler (:on-change (second input))
                fake-ev #js {:target #js {:value "2"}}]  ;; max idx = 2
            (when handler (handler fake-ev))))
        (is (some (fn [ev] (= [:rf.causa/set-scrubber-position :present] ev))
                  @dispatches)
            "dragging to max-idx dispatches :present")))))

;; ---- (6) present-button disabled at :present ---------------------------

(deftest present-button-disabled-at-present
  (setup-causa-frame!)
  (rf/with-frame :rf/causa
    (override-machines!    [:auth/login])
    (override-definitions! {:auth/login fixture-definition})
    (push-transition! 1 :idle :authing [:auth/start])
    (let [tree  (scrubber/ScrubberStrip)
          btn   (find-by-testid
                  tree "rf-causa-machine-inspector-scrubber-present")]
      (is (true? (:disabled (second btn)))))))

(deftest present-button-enabled-when-scrubbed-back
  (setup-causa-frame!)
  (rf/with-frame :rf/causa
    (override-machines!    [:auth/login])
    (override-definitions! {:auth/login fixture-definition})
    (push-transition! 1 :idle    :authing [:auth/start])
    (push-transition! 2 :authing :done    [:auth/ok])
    (rf/dispatch-sync [:rf.causa/set-scrubber-position 0])
    (let [tree (scrubber/ScrubberStrip)
          btn  (find-by-testid
                 tree "rf-causa-machine-inspector-scrubber-present")]
      (is (false? (:disabled (second btn)))))))
