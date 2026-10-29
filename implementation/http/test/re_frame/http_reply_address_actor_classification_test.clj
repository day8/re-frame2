(ns re-frame.http-reply-address-actor-classification-test
  "A managed request's reply address to a spawned machine actor redacts by the
  actor's machine TYPE classification on the generic fx-arg trace slots, as an
  address to the type's singleton does, because the trace's frame reaches the
  address projection (Spec 005 §Privacy, Spec 014 §Privacy)."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is use-fixtures]]
            [re-frame.classification :as rf.classification]
            [re-frame.core :as rf]
            [re-frame.http.managed]
            [re-frame.machines]
            [re-frame.privacy :as rf.privacy]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.test-support :as rf.test-support]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter}))

(def ^:private pw "PW-SENTINEL-http-actor-reply-address")

(def ^:private frame-id ::app)

(defn- register! []
  (rf/make-frame {:id frame-id})
  (rf/reg-machine ::child {:sensitive [[1 :password]]}
    {:initial :idle
     :states  {:idle {:on {:login {:target :done}}} :done {}}})
  (rf/reg-machine ::parent
    {:initial :idle
     :states  {:idle    {:on {:start :working}}
               :working {:spawn {:machine-id ::child :fixed-actor-id ::kid}}}})
  (rf/dispatch-sync [::parent [:start]] {:frame frame-id}))

(defn- handled-row-addresses
  "The reply addresses of a `:rf.fx/handled` row for a managed request
  addressing `actor`, after core's trace projection."
  [actor]
  (let [address [actor [:login {:user "u" :password pw}]]
        row     (rf.classification/project-trace-event
                  {:operation :rf.fx/handled
                   :tags      {:frame      frame-id
                               :rf.fx/id   :rf.http/managed
                               :rf.fx/args {:request    {:method :post :url "https://api.example.test/login"}
                                            :reply-to   address
                                            :on-success address
                                            :on-failure address}}})]
    (mapv #(get-in row [:tags :rf.fx/args %]) [:reply-to :on-success :on-failure])))

(deftest a-reply-address-to-a-spawned-actor-takes-its-type-classification
  (register!)
  (let [redacted (fn [actor] [actor [:login {:user "u" :password rf.privacy/redacted-sentinel}]])]
    (is (= (repeat 3 (redacted ::child)) (handled-row-addresses ::child))
        "control: an address to the type's singleton redacts by its registration")
    (is (= (repeat 3 (redacted ::kid)) (handled-row-addresses ::kid))
        "every reply address to the spawned instance takes the type's classification")
    (is (not (str/includes? (pr-str (handled-row-addresses ::kid)) pw)))))
