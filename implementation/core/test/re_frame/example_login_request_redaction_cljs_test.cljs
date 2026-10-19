(ns re-frame.example-login-request-redaction-cljs-test
  "The login examples answer `:rf.http/managed` from `login.model/demo-backend`,
   a stub transport, so the login request runs through the real
   `:rf.http/managed`. Its `:sensitive? true` keeps the password off every
   trace — the fx's `:rf.fx/handled` trace and every `:rf.http/*` trace the
   request emits — while the password still goes out on the wire."
  (:require [cljs.test :refer-macros [deftest use-fixtures is]]
            [re-frame.core :as rf]
            [re-frame.privacy :as rf.privacy]
            [re-frame.frame :as rf.frame]
            [re-frame.adapter.reagent :as rf.adapter.reagent]
            [re-frame.test-support :as rf.test-support]
            [re-frame.schemas]
            [re-frame.machines]
            [re-frame.http.managed]
            [re-frame.http.stub-transport :as rf.http.stub-transport]
            [login.model :as model])
  (:require-macros [re-frame.core :refer [with-new-frame]]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter rf.adapter.reagent/adapter}))

;; A valid Credentials password that appears nowhere else.
(def sentinel "PW-REQUEST-SENTINEL-4c1f9e")

(defn- contains-sentinel?
  [x]
  (cond
    (string? x) (not= -1 (.indexOf x sentinel))
    (map? x)    (boolean (some contains-sentinel? (concat (keys x) (vals x))))
    (coll? x)   (boolean (some contains-sentinel? x))
    :else       false))

(defn- record-traces! [id]
  (let [a (atom [])]
    (rf/register-listener! :trace id (fn [ev] (swap! a conj ev)))
    a))

(defn- seed+submit!
  "Type a valid draft carrying the sentinel, then submit it."
  [f]
  (rf/dispatch-sync [:auth.login/initialise-form] {:frame f})
  (rf/dispatch-sync [:auth.login/edit-field :email "alice@example.com"] {:frame f})
  (rf/dispatch-sync [:auth.login/edit-password {:value sentinel}] {:frame f})
  (rf/dispatch-sync [:auth.login/submit-form] {:frame f}))

(deftest login-request-traces-never-carry-the-password
  (with-new-frame [f (rf.frame/make-anon-frame-record! {})]
    (let [sent   (atom [])
          traces (record-traces! ::probe)]
      (rf.http.stub-transport/install-transport!
        f (fn [wire callbacks]
            (swap! sent conj (:body wire))
            (model/demo-backend wire callbacks)))
      (seed+submit! f)
      (rf/unregister-listener! :trace ::probe)
      (is (contains-sentinel? @sent)
          "control: the password went out on the wire to the demo backend")
      (let [handled (->> @traces
                         (filter #(= :rf.http/managed (get-in % [:tags :rf.fx/id]))))]
        (is (seq handled)
            "the real :rf.http/managed ran and emitted a :rf.fx/handled trace")
        (doseq [ev handled]
          (is (= rf.privacy/redacted-sentinel
                 (get-in ev [:tags :rf.fx/args :request :body]))
              "the :sensitive? true request's whole body reads :rf/redacted")))
      (is (= [] (mapv :operation (filter #(contains-sentinel? (:tags %)) @traces)))
          "the password appears raw in no trace tag"))))
