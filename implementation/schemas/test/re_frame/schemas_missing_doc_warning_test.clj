(ns re-frame.schemas-missing-doc-warning-test
  "JVM tests for `:rf.warning/missing-doc` on app-db schema registrations.

  Spec 001 §`:doc` is dev-warned when absent puts `reg-app-schema` in scope
  even though it writes the schemas artefact's per-frame side table rather
  than the registrar. The warning is the registrar's shared emitter, with its
  kind `:app-schema` and its id the canonical path, so it keeps the same
  carve-out and suppression:

    * only the public macro path warns: its captured source coordinates
      carry `:ns`, while the programmatic fn path carries none;
    * it fires at most once per `(:app-schema, path)`, so a re-registration
      does not re-fire it;
    * a usable `:doc` in the metadata map silences it.

  Every assertion about the warning itself sits under
  `rf.interop/debug-enabled?`, because production builds erase the whole
  emit. The registration landing is asserted in every posture."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.interop :as rf.interop]
            [re-frame.schemas :as rf.schemas]
            [re-frame.schemas.test-fixture :as rf.schemas.test-fixture]
            [re-frame.test-support :refer [with-trace-recorder!]]))

(use-fixtures :each rf.schemas.test-fixture/reset-runtime)

(defn- missing-doc-warnings
  [recorded]
  (filterv (fn [ev]
             (and (= :warning (:op-type ev))
                  (= :rf.warning/missing-doc (:operation ev))))
           @recorded))

(defn- assert-registered
  [path]
  (is (contains? (rf.schemas/app-schemas {:frame :rf/default}) path)
      (str "the app-db schema at " path " is registered")))

(deftest missing-doc-fires-once-for-an-undocumented-macro-registration
  (testing "a public reg-app-schema without :doc emits the shared warning once,
            keyed by :app-schema and the canonical path, with source coords"
    (with-trace-recorder! [recorded]
      (rf/reg-app-schema [:user] [:map [:name :string]])
      (assert-registered [:user])
      (when rf.interop/debug-enabled?
        (let [warns (missing-doc-warnings recorded)
              tags  (:tags (first warns))]
          (is (= 1 (count warns)))
          (is (= :app-schema (:kind tags)))
          (is (= [:user] (:id tags)))
          (is (= 're-frame.schemas-missing-doc-warning-test
                 (get-in tags [:source-coords :ns]))
              "the coords name the registering call site"))))))

(deftest missing-doc-fires-for-nil-and-empty-doc
  (testing "a nil or empty-string :doc is as undocumented as an absent one"
    (with-trace-recorder! [recorded]
      (rf/reg-app-schema [:a] {:doc nil} :int)
      (rf/reg-app-schema [:b] {:doc ""} :int)
      (assert-registered [:a])
      (assert-registered [:b])
      (when rf.interop/debug-enabled?
        (is (= #{[:a] [:b]}
               (set (map #(get-in % [:tags :id]) (missing-doc-warnings recorded)))))))))

(deftest missing-doc-silent-for-a-documented-registration
  (testing "a :doc in the metadata map produces no warning"
    (with-trace-recorder! [recorded]
      (rf/reg-app-schema [:user] {:doc "The signed-in user."} [:map [:name :string]])
      (assert-registered [:user])
      (when rf.interop/debug-enabled?
        (is (empty? (missing-doc-warnings recorded)))))))

(deftest missing-doc-suppressed-on-re-registration
  (testing "re-registering the same undocumented path does not re-fire"
    (with-trace-recorder! [recorded]
      (rf/reg-app-schema [:user] :map)
      (rf/reg-app-schema [:user] :map)
      (rf/reg-app-schema [:user] {:frame :rf/default} :map)
      (assert-registered [:user])
      (when rf.interop/debug-enabled?
        (is (= 1 (count (missing-doc-warnings recorded))))))))

(deftest missing-doc-fires-once-per-path-from-the-bulk-form
  (testing "reg-app-schemas routes each entry through reg-app-schema, so each
            undocumented path warns once"
    (with-trace-recorder! [recorded]
      (rf/reg-app-schemas {[:auth] :map
                           [:cart] :map})
      (assert-registered [:auth])
      (assert-registered [:cart])
      (when rf.interop/debug-enabled?
        (let [warns (missing-doc-warnings recorded)]
          (is (= 2 (count warns)))
          (is (= #{[:auth] [:cart]} (set (map #(get-in % [:tags :id]) warns))))
          (is (every? #(= :app-schema (get-in % [:tags :kind])) warns)))))))

(deftest missing-doc-silent-on-the-programmatic-path
  (testing "the fn form called without macro-captured coords is out of scope"
    (with-trace-recorder! [recorded]
      (rf.schemas/reg-app-schema [:programmatic] :map)
      (assert-registered [:programmatic])
      (when rf.interop/debug-enabled?
        (is (empty? (missing-doc-warnings recorded)))))))
