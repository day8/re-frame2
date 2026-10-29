(ns re-frame.routing-nav-fx-outcome-test
  "Every `:rf.nav/*` fx entry reports exactly one fx outcome, and the fx
  runner reports it: on `:server` the `:platforms #{:client}` gate skips the
  entry before its handler runs, and on `:client` a handler that returns is
  `:rf.fx/handled` — a non-owner frame's no-op and the JVM's missing `window`
  included. Per Spec 012 §Trace events and Spec 011 §`:platforms` metadata on
  `reg-fx`."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.routing-test-support :as rf.routing-test-support]
            [re-frame.test-support :refer [with-trace-recorder!]]))

(use-fixtures :each rf.routing-test-support/reset-runtime)

(def ^:private outcome-ops
  "The operations an fx entry's outcome rides (Spec-Schemas §`:rf/epoch-record`
  `:effects`)."
  #{:rf.fx/handled :rf.fx/skipped-on-platform :rf.error/fx-handler-exception})

(def ^:private nav-fx
  "Every `:rf.nav/*` fx the routing artefact registers, each with args its
  `:schema` accepts."
  [[:rf.nav/push-url "/cart"]
   [:rf.nav/replace-url "/cart"]
   [:rf.nav/scroll {:strategy :top}]
   [:rf.nav/capture-scroll {:url "/cart"}]])

(defn- outcomes
  "Dispatch one event emitting `fx` on `frame-id`, and return the
  `[op-type operation]` of every outcome trace naming that fx."
  [frame-id [fx-id :as fx]]
  (rf/reg-event :nav-fx-outcome/emit (fn [_ [_ fx]] {:fx [fx]}))
  (with-trace-recorder! [traces]
    (rf/with-frame frame-id
      (rf/dispatch-sync [:nav-fx-outcome/emit fx]))
    (into []
          (comp (filter #(and (outcome-ops (:operation %))
                              (= fx-id (get-in % [:tags :rf.fx/id]))))
                (map (juxt :op-type :operation)))
          @traces)))

(deftest nav-fx-on-a-server-frame-are-skipped-by-the-platform-gate
  (testing "the JVM default frame is :server, so the fx runner skips each
            :client-only entry and its handler never runs"
    (doseq [[fx-id :as fx] nav-fx]
      (is (= [[:warning :rf.fx/skipped-on-platform]]
             (outcomes :rf/default fx))
          (str fx-id " reports one outcome: the platform skip")))))

(deftest nav-fx-on-a-client-frame-report-handled-once
  (testing "a :client frame on the JVM runs each handler, which has no window
            to drive and returns: one :rf.fx/handled and no skip, for the URL
            owner and for a frame that does not own the URL"
    (rf/make-frame {:id :rf/default :url-bound? true :platform :client})
    (rf/make-frame {:id :nav-fx-outcome/story :platform :client})
    (doseq [frame-id      [:rf/default :nav-fx-outcome/story]
            [fx-id :as fx] nav-fx]
      (is (= [[:rf.fx :rf.fx/handled]]
             (outcomes frame-id fx))
          (str fx-id " on " frame-id " reports one outcome: handled")))))
