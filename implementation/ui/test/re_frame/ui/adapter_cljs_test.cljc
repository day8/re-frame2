(ns re-frame.ui.adapter-cljs-test
  "S2 public-adapter smoke + focused artifact-isolation proof.

  The CLJC init assertions pin the frozen `ui/adapter` row on both hosts.
  The CLJS arms additionally prove the retained browser implementation is
  watchable and that the focused `:node-test-ui` dependency closure loads no
  retiring Reagent-family/UIx/Helix adapter namespace."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.substrate.adapter :as substrate-adapter]
            [re-frame.ui :as ui]))

(use-fixtures
  :each
  (fn [f]
    (substrate-adapter/reset-lifecycle-state-for-tests!)
    (try
      (f)
      (finally
        (rf/destroy-adapter!)
        (substrate-adapter/reset-lifecycle-state-for-tests!)))))

(def ^:private required-entries
  #{:make-state-container
    :read-container
    :replace-container!
    :make-derived-value
    :render
    :render-to-string
    :dispose-adapter!})

(deftest public-adapter-installs-with-the-canonical-identity
  (testing "the frozen public var is a complete installable adapter map"
    (is (= :rf.adapter/ui (:kind ui/adapter)))
    (is (every? #(fn? (get ui/adapter %)) required-entries)))
  (testing "rf/init! installs the exact public value and introspection names it"
    (is (nil? (rf/init! ui/adapter)))
    (is (identical? ui/adapter (rf/current-adapter-spec)))
    (is (= :rf.adapter/ui (rf/current-adapter)))))

#?(:cljs
   (deftest cljs-adapter-derived-values-are-watchable
     (let [make-state (:make-state-container ui/adapter)
           replace!   (:replace-container! ui/adapter)
           derive     (:make-derived-value ui/adapter)
           source     (make-state 1)
           value      (derive [source] identity)
           seen       (atom [])]
       (is (= 1 @value) "first deref establishes the lazy derived baseline")
       (add-watch value ::probe
                  (fn [_ _ old-value new-value]
                    (swap! seen conj [old-value new-value])))
       (replace! source 2)
       (is (= [[1 2]] @seen)
           "a real source move fires the derived value's IWatchable fan-out")
       (remove-watch value ::probe))))

#?(:cljs
   (deftest focused-ui-build-excludes-retiring-adapter-artifacts
     ;; This is intentionally a runtime dependency-closure assertion, not a
     ;; source-string grep. `:node-test-ui` selects only re-frame.ui suites; if
     ;; production UI code or one of its retained gates requires an old adapter,
     ;; Shadow loads that namespace and goog.getObjectByName returns its object.
     (is (some? (.getObjectByName js/goog "re_frame.ui.substrate"))
         "positive control: the lookup sees a namespace in this bundle")
     (doseq [namespace-name ["re_frame.adapter.reagent"
                             "re_frame.adapter.reagent_slim"
                             "re_frame.adapter.uix"
                             "re_frame.adapter.helix"]]
       (is (nil? (.getObjectByName js/goog namespace-name))
           (str namespace-name " must be absent from the focused UI bundle")))))
