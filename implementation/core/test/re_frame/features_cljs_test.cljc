(ns re-frame.features-cljs-test
  "`(rf/features)` — every optional feature with its coordinate data and live
  `:loaded?` status. The in-tree test build loads every per-feature artefact,
  so an absent feature is simulated by setting its late-bind probe key to nil."
  (:require #?(:clj  [clojure.test :refer [deftest is]]
               :cljs [cljs.test :refer-macros [deftest is]])
            [re-frame.features :as rf.features]
            [re-frame.late-bind :as rf.late-bind]))

(defn- with-probe-absent
  "Run `f` with `feature`'s late-bind probe key set to nil, restoring it after."
  [feature f]
  (let [probe-key (get-in rf.features/feature-registry [feature :probe-key])
        original  (rf.late-bind/get-fn probe-key)]
    (try
      (rf.late-bind/set-fn! probe-key nil)
      (f)
      (finally
        (rf.late-bind/set-fn! probe-key original)))))

(deftest features-lists-every-optional-feature-with-status
  (let [m (rf.features/features)]
    (is (= (set (keys rf.features/feature-registry)) (set (keys m))))
    (doseq [[feature entry] m]
      (is (= #{:maven :require :spec :loaded?} (set (keys entry)))
          (str feature " entry shape: coordinate data + :loaded?, no :probe-key leak"))
      (is (true? (:loaded? entry))
          (str feature " probe key should be populated in the test build")))
    (is (= {:maven "day8/re-frame2-epoch" :require "re-frame.epoch"}
           (select-keys (:epoch m) [:maven :require])))))

(deftest features-not-loaded-arm
  (with-probe-absent :routing
    (fn []
      (let [m (rf.features/features)]
        (is (false? (get-in m [:routing :loaded?])))
        (is (= "day8/re-frame2-routing" (get-in m [:routing :maven]))
            "the coordinate is present regardless of :loaded?")
        (is (true? (get-in m [:schemas :loaded?]))
            "the flip is isolated to the one feature")))))
