(ns re-frame.frame-config-keys-test
  "`:rf.warning/unknown-frame-config-key` (Spec 009 §Error event catalogue):
  a bare frame-config key outside the recognised vocabulary warns once per
  `make-frame` in dev, naming every such key. Namespaced keys, and a loaded
  artefact's keys, pass silently."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.routing]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.test-support :as rf.test-support]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter rf.substrate.plain-atom/adapter}))

(defn- config-warnings
  "Run `f`, returning the `:tags` of every `:rf.warning/unknown-frame-config-key`
  it emits."
  [f]
  (let [seen (atom [])]
    (rf/register-listener! :trace ::probe
                           (fn [ev]
                             (when (= :rf.warning/unknown-frame-config-key (:operation ev))
                               (swap! seen conj (:tags ev)))))
    (try (f) (finally (rf/unregister-listener! :trace ::probe)))
    @seen))

(deftest ^:requires-debug a-typo-warns-once-naming-every-unknown-key
  (let [warnings (config-warnings
                   #(rf/make-frame {:id ::typo :fx-overides {} :drain-depht 4}))
        {:keys [frame unknown-keys known]} (first warnings)]
    (is (= 1 (count warnings)))
    (is (= ::typo frame))
    (is (= #{:fx-overides :drain-depht} (set unknown-keys)))
    (is (contains? (set known) :fx-overrides))))

(deftest ^:requires-debug recognised-and-namespaced-keys-pass-silently
  (is (= [] (config-warnings
              #(rf/make-frame {:id            ::ok
                               :doc           "every key here is recognised"
                               :fx-overrides  {}
                               :drain-depth   4
                               :url-bound?    false
                               :myapp/feature true})))))
