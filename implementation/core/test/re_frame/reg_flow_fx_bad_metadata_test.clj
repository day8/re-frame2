(ns re-frame.reg-flow-fx-bad-metadata-test
  "`[:rf.fx/reg-flow [flow-id metadata derive-fn]]` whose metadata is not a
  map is refused with `:rf.error/invalid-flow-metadata` carrying the offending
  value — the same named error the public `reg-flow` raises — and the refusal
  reaches the always-on error channel rather than escaping the drain as a raw
  host exception."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.error-emit :as rf.error-emit]
            [re-frame.flows :as rf.flows]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.test-support :as rf.test-support]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter}))

(defn- register-through-fx
  "Dispatch one event whose only effect is `:rf.fx/reg-flow` with `metadata`.
  Return what escaped the dispatch, if anything, and the error records seen."
  [metadata]
  (let [errors (atom [])]
    (rf.error-emit/register-error-listener! ::recorder #(swap! errors conj %))
    (try
      (rf/reg-event ::enter
        (fn [_ _] {:fx [[:rf.fx/reg-flow [::bad metadata (fn [x] x)]]]}))
      {:escaped (try (rf/dispatch-sync [::enter]) nil
                     (catch Throwable e e))
       :records (filterv #(= :rf.error/invalid-flow-metadata (:error %)) @errors)}
      (finally
        (rf.error-emit/unregister-error-listener! ::recorder)))))

(deftest non-map-metadata-is-a-named-error
  (doseq [metadata [[:inputs [[:a]]] "not a map" nil]]
    (testing (str "metadata " (pr-str metadata))
      (let [{:keys [escaped records]} (register-through-fx metadata)]
        (is (nil? escaped) "nothing raw escapes the dispatch")
        (is (= 1 (count records)) "one :rf.error/invalid-flow-metadata record")
        (is (= metadata (:value (ex-data (:exception (first records)))))
            "the record's exception carries the offending metadata value")
        (is (not (contains? (get (rf.flows/flows-snapshot) :rf/default) ::bad))
            "no flow was registered")))))

(deftest map-metadata-still-registers
  (testing "control: a metadata map registers against the dispatching frame"
    (let [{:keys [escaped records]} (register-through-fx {:inputs      [[:a]]
                                                          :output-path [:b]})]
      (is (nil? escaped))
      (is (empty? records))
      (is (contains? (get (rf.flows/flows-snapshot) :rf/default) ::bad)))))
