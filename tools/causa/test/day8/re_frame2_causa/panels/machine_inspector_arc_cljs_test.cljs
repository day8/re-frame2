(ns day8.re-frame2-causa.panels.machine-inspector-arc-cljs-test
  "CLJS-side wiring tests for Causa's Machine Inspector per-instance
  state-arc (rf2-nqw0v, Phase 5).

  Covers:

    1. Registry wires the arc sub family + the scrubber event family.
    2. `:rf.causa/machine-arc-data` composes trace buffer + selected
       machine + definitions into an arc.
    3. `:rf.causa/machine-arc-trimmed` reacts to scrubber position.
    4. `:rf.causa/machine-arc-highlight-state` returns the historic
       state at the scrubber position.
    5. The set-scrubber-position event writes to the Causa frame.
    6. The set-arc-hover event writes and clears the slot."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.registrar :as registrar]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]
            [day8.re-frame2-causa.preload :as preload]
            [day8.re-frame2-causa.registry :as registry]
            [day8.re-frame2-causa.test-support :as causa-test-support]
            [day8.re-frame2-causa.trace-bus :as trace-bus]
            [day8.re-frame2-causa.panels.machine-inspector :as machine-inspector]
            [day8.re-frame2-causa.panels.machine-inspector-arc :as arc]))

;; ---- fixtures -----------------------------------------------------------

(defn- causa-init! []
  (causa-test-support/reset-all!)
  (trace-bus/clear-buffer!))

(use-fixtures :each
  (test-support/reset-runtime-fixture
    {:adapter plain-atom/adapter
     :init-fn causa-init!}))

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

;; ---- (1) registry wiring -----------------------------------------------

(deftest registry-installs-arc-handlers
  (testing "register-causa-handlers! installs the arc sub + event family"
    (registry/register-causa-handlers!)
    (is (some? (registrar/handler :sub :rf.causa/machine-arc-data)))
    (is (some? (registrar/handler :sub :rf.causa/machine-scrubber-position)))
    (is (some? (registrar/handler :sub :rf.causa/machine-arc-trimmed)))
    (is (some? (registrar/handler :sub :rf.causa/machine-arc-highlight-state)))
    (is (some? (registrar/handler :sub :rf.causa/machine-arc-hover)))
    (is (some? (registrar/handler :event :rf.causa/set-scrubber-position)))
    (is (some? (registrar/handler :event :rf.causa/set-arc-hover)))))

;; ---- (2) arc-data composite -------------------------------------------

(deftest arc-data-empty-when-no-machine-selected
  (setup-causa-frame!)
  (rf/with-frame :rf/causa
    (override-machines! [])
    (is (= [] @(rf/subscribe [:rf.causa/machine-arc-data])))))

(deftest arc-data-includes-origin-from-definition
  (setup-causa-frame!)
  (rf/with-frame :rf/causa
    (override-machines!    [:auth/login])
    (override-definitions! {:auth/login fixture-definition})
    (let [arc @(rf/subscribe [:rf.causa/machine-arc-data])]
      (is (= 1 (count arc)) "origin-only when no transitions in buffer")
      (is (= :idle (-> arc first :state))))))

(deftest arc-data-folds-transitions
  (setup-causa-frame!)
  (rf/with-frame :rf/causa
    (override-machines!    [:auth/login])
    (override-definitions! {:auth/login fixture-definition})
    (push-transition! 1 :idle    :authing [:auth/start])
    (push-transition! 2 :authing :done    [:auth/ok])
    (let [arc @(rf/subscribe [:rf.causa/machine-arc-data])]
      (is (= 3 (count arc)) "origin + two transitions"))))

;; ---- (3) trimmed arc reacts to scrubber --------------------------------

(deftest arc-trimmed-defaults-to-full
  (setup-causa-frame!)
  (rf/with-frame :rf/causa
    (override-machines!    [:auth/login])
    (override-definitions! {:auth/login fixture-definition})
    (push-transition! 1 :idle    :authing [:auth/start])
    (push-transition! 2 :authing :done    [:auth/ok])
    (let [arc     @(rf/subscribe [:rf.causa/machine-arc-data])
          trimmed @(rf/subscribe [:rf.causa/machine-arc-trimmed])]
      (is (= (count arc) (count trimmed))
          "default :present position renders the full arc"))))

(deftest arc-trimmed-slices-on-scrubber-position
  (setup-causa-frame!)
  (rf/with-frame :rf/causa
    (override-machines!    [:auth/login])
    (override-definitions! {:auth/login fixture-definition})
    (push-transition! 1 :idle    :authing [:auth/start])
    (push-transition! 2 :authing :done    [:auth/ok])
    (rf/dispatch-sync [:rf.causa/set-scrubber-position 1])
    (let [trimmed @(rf/subscribe [:rf.causa/machine-arc-trimmed])]
      (is (= 2 (count trimmed))
          "scrubber at idx=1 → origin + first transition"))))

;; ---- (4) highlight-state-at -------------------------------------------

(deftest highlight-state-tracks-scrubber
  (setup-causa-frame!)
  (rf/with-frame :rf/causa
    (override-machines!    [:auth/login])
    (override-definitions! {:auth/login fixture-definition})
    (push-transition! 1 :idle    :authing [:auth/start])
    (push-transition! 2 :authing :done    [:auth/ok])
    (rf/dispatch-sync [:rf.causa/set-scrubber-position 0])
    (is (= :idle
           @(rf/subscribe [:rf.causa/machine-arc-highlight-state]))
        "scrub to origin → highlight :idle")
    (rf/dispatch-sync [:rf.causa/set-scrubber-position 1])
    (is (= :authing
           @(rf/subscribe [:rf.causa/machine-arc-highlight-state])))
    (rf/dispatch-sync [:rf.causa/set-scrubber-position :present])
    (is (nil? @(rf/subscribe [:rf.causa/machine-arc-highlight-state]))
        "scrub at :present → nil so the chart shows the live snapshot")))

;; ---- (5) set-scrubber-position event ----------------------------------

(deftest set-scrubber-position-writes-int
  (setup-causa-frame!)
  (rf/with-frame :rf/causa
    (rf/dispatch-sync [:rf.causa/set-scrubber-position 5])
    (is (= 5 @(rf/subscribe [:rf.causa/machine-scrubber-position])))))

(deftest set-scrubber-position-writes-present
  (setup-causa-frame!)
  (rf/with-frame :rf/causa
    (rf/dispatch-sync [:rf.causa/set-scrubber-position 5])
    (rf/dispatch-sync [:rf.causa/set-scrubber-position :present])
    (is (= :present @(rf/subscribe [:rf.causa/machine-scrubber-position])))))

(deftest set-scrubber-position-nil-flips-to-present
  (setup-causa-frame!)
  (rf/with-frame :rf/causa
    (rf/dispatch-sync [:rf.causa/set-scrubber-position 3])
    (rf/dispatch-sync [:rf.causa/set-scrubber-position nil])
    (is (= :present @(rf/subscribe [:rf.causa/machine-scrubber-position])))))

;; ---- (6) set-arc-hover ------------------------------------------------

(deftest set-arc-hover-writes-and-clears
  (setup-causa-frame!)
  (rf/with-frame :rf/causa
    (is (nil? @(rf/subscribe [:rf.causa/machine-arc-hover]))
        "no hover by default")
    (rf/dispatch-sync [:rf.causa/set-arc-hover 2])
    (is (= 2 @(rf/subscribe [:rf.causa/machine-arc-hover])))
    (rf/dispatch-sync [:rf.causa/set-arc-hover nil])
    (is (nil? @(rf/subscribe [:rf.causa/machine-arc-hover])))))

;; ---- frame isolation --------------------------------------------------

(deftest scrubber-position-lives-on-causa-frame
  (setup-causa-frame!)
  (rf/with-frame :rf/causa
    (rf/dispatch-sync [:rf.causa/set-scrubber-position 3]))
  (let [causa-db   (frame/frame-app-db-value :rf/causa)
        default-db (frame/frame-app-db-value :rf/default)]
    (is (= 3 (:machine-inspector/scrubber-position causa-db)))
    (is (nil? (:machine-inspector/scrubber-position default-db))
        "host frame is untouched")))
