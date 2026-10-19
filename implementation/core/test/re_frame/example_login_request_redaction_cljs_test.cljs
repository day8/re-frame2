(ns re-frame.example-login-stub-fx-args-redaction-cljs-test
  "The login examples remap `:rf.http/managed` to the demo stub
   `:auth.login.demo/managed-stub`, so the stub's `:rf.fx/handled` trace
   carries the raw login request. A `:sensitive? true` request redacts there
   because the redirect stamps `:rf.fx/from` and the projector composes the
   original fx's per-call classification; an unflagged request relies on the
   stub's own `:sensitive [[:request :body :password]]`."
  (:require [cljs.test :refer-macros [deftest use-fixtures is]]
            [re-frame.core :as rf]
            [re-frame.classification :as rf.classification]
            [re-frame.privacy :as rf.privacy]
            [re-frame.frame :as rf.frame]
            [re-frame.adapter.reagent :as rf.adapter.reagent]
            [re-frame.test-support :as rf.test-support]
            [re-frame.schemas]
            [re-frame.machines]
            [re-frame.http.managed]
            [re-frame.http.test-support]
            [login.model])
  (:require-macros [re-frame.core :refer [with-new-frame]]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter rf.adapter.reagent/adapter}))

;; A valid Credentials password that appears nowhere else.
(def sentinel "PW-STUB-ARGS-SENTINEL-4c1f9e")

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
  "Type a valid draft carrying the sentinel, then submit through the same
   `:fx-overrides` remap the shared `frame-config` installs."
  [f]
  (rf/dispatch-sync [:auth.login/initialise-form] {:frame f})
  (rf/dispatch-sync [:auth.login/edit-field :email "alice@example.com"] {:frame f})
  (rf/dispatch-sync [:auth.login/edit-password {:value sentinel}] {:frame f})
  (rf/dispatch-sync [:auth.login/submit-form]
                    {:frame        f
                     :fx-overrides {:rf.http/managed :auth.login.demo/managed-stub}}))

(deftest stub-declares-its-own-sensitive-request-body-password
  (is (= {:sensitive [[:request :body :password]]}
         (rf.classification/registration-classification :fx :auth.login.demo/managed-stub))))

(deftest stub-fx-handled-trace-redacts-request-body-password
  (with-new-frame [f (rf.frame/make-anon-frame-record! {})]
    (let [traces (record-traces! ::probe)]
      (seed+submit! f)
      (rf/unregister-listener! :trace ::probe)
      (let [handled (->> @traces
                         (filter #(= :auth.login.demo/managed-stub
                                     (get-in % [:tags :rf.fx/id]))))]
        (is (seq handled)
            "the demo stub ran and emitted a :rf.fx/handled trace")
        (doseq [ev handled]
          (is (= rf.privacy/redacted-sentinel
                 (get-in ev [:tags :rf.fx/args :request :body]))
              "the :sensitive? true request's whole body reads :rf/redacted")
          (is (not (contains-sentinel? (:tags ev)))
              "the password appears nowhere raw in the stub's trace tags"))))))
