(ns re-frame.features-cljs-test
  "Regression coverage for the feature-inspection front-porch
  (rf2-3nbl5.5, API-governance G5; pruned to ONE door by rf2-kuky.4 /
  rf2-kuky.75):

    (rf/features)   — every optional feature + its coordinate data and
                      live :loaded? status. The boolean is a lookup:
                      (get-in (rf/features) [:epoch :loaded?]); an
                      UNKNOWN feature keyword is ABSENT from the map and
                      so reads nil (where the deleted feature-loaded?
                      read false — the lookup is taught, not claimed
                      contract-identical).

  The in-tree test build loads all seven per-feature artefacts (see
  implementation/core/deps.edn `:test` extra-deps), so every probe key is
  populated at test time. We simulate an ABSENT feature by flipping its
  representative late-bind probe key to nil in a try/finally — the same
  technique re-frame.interop-late-bind-cljs-test uses.

  Named `*-cljs-test` so the shadow-cljs `:node-test` build (ns-regexp
  `cljs-test$`) discovers it; the `-test` suffix also satisfies the JVM
  cognitect test-runner, so this one `.cljc` file runs on both runtimes."
  (:require #?(:clj  [clojure.test :refer [deftest is testing]]
               :cljs [cljs.test :refer-macros [deftest is testing]])
            [re-frame.features :as rf.features]
            [re-frame.late-bind :as rf.late-bind]))

(defn- with-probe-absent
  "Run `f` with `feature`'s representative late-bind probe key
  temporarily set to nil (simulating an artefact that was never
  required). Restores the original value afterwards (success or throw)."
  [feature f]
  (let [probe-key (get-in rf.features/feature-registry [feature :probe-key])
        original  (rf.late-bind/get-fn probe-key)]
    (try
      (rf.late-bind/set-fn! probe-key nil)
      (f)
      (finally
        (rf.late-bind/set-fn! probe-key original)))))

;; ---- features — the one inventory door ------------------------------------

(deftest features-lists-every-optional-feature-with-status
  (testing "features returns one entry per registry feature, carrying the
            static coordinate data + live :loaded? status"
    (let [m (rf.features/features)]
      (is (= (set (keys rf.features/feature-registry)) (set (keys m)))
          "exactly the registry's feature keys")
      (doseq [[feature entry] m]
        (is (= #{:maven :require :spec :loaded?} (set (keys entry)))
            (str feature " entry shape — coordinate data + :loaded?, no :probe-key leak"))
        (is (string? (:maven entry)))
        (is (string? (:require entry)))
        (is (boolean? (:loaded? entry))))
      (is (= "day8/re-frame2-epoch" (get-in m [:epoch :maven])))
      (is (= "re-frame.epoch" (get-in m [:epoch :require]))))))

(deftest features-loaded-arm
  (testing "every per-feature artefact is loaded in the in-tree test build,
            read through the map lookup that replaced feature-loaded?"
    (let [m (rf.features/features)]
      (doseq [feature (keys rf.features/feature-registry)]
        (is (true? (get-in m [feature :loaded?]))
            (str feature " probe key should be populated in the test build"))))))

(deftest features-not-loaded-arm
  (testing "flipping a feature's probe key to nil reports it as not loaded,
            and the flip is isolated to that feature"
    (with-probe-absent :routing
      (fn []
        (let [m (rf.features/features)]
          (is (false? (get-in m [:routing :loaded?]))
              "routing reports :loaded? false while its probe is nil")
          (is (= "day8/re-frame2-routing" (get-in m [:routing :maven]))
              "coordinate is static — present regardless of :loaded? status")
          (is (true? (get-in m [:schemas :loaded?]))
              "the flip is isolated — other features stay loaded"))))))

(deftest features-omits-unknown-feature
  (testing "an unknown feature keyword has NO entry — the lookup reads nil,
            not false; no throw"
    (let [m (rf.features/features)]
      (is (not (contains? m :not-a-feature)))
      (is (nil? (get-in m [:not-a-feature :loaded?])))
      (is (nil? (get m nil))))))

(deftest features-supports-the-boot-time-guard
  (testing "the documented replacement for the deleted require-feature! —
            an explicit (when-not … (throw (ex-info …))) carrying the
            inventory entry as its data, NOT an elidable assert"
    (with-probe-absent :epoch
      (fn []
        (let [guard (fn []
                      (when-not (get-in (rf.features/features) [:epoch :loaded?])
                        (throw (ex-info "re-frame.epoch is not on the classpath"
                                        (get (rf.features/features) :epoch)))))
              ex    (is (thrown? #?(:clj clojure.lang.ExceptionInfo :cljs js/Error)
                                 (guard)))
              data  (ex-data ex)]
          (is (= "day8/re-frame2-epoch" (:maven data))
              "the guard's data IS the inventory entry — exact Maven coordinate")
          (is (= "re-frame.epoch" (:require data))
              "…and the exact namespace to require at boot")
          (is (false? (:loaded? data))))))
    (testing "the guard is a no-op when the feature is loaded"
      (is (nil? (when-not (get-in (rf.features/features) [:epoch :loaded?])
                  (throw (ex-info "unreachable" {}))))))))

;; ---- bundle-isolation invariant (static-data, not a live require) ---------

(deftest feature-registry-is-static-data
  (testing "the coordinate table is plain data — every value is a static
            map of strings + a probe keyword, no fn / artefact reach-in"
    (doseq [[feature entry] rf.features/feature-registry]
      (is (string? (:maven entry)) (str feature " :maven is a static string"))
      (is (string? (:require entry)) (str feature " :require is a static string"))
      (is (keyword? (:probe-key entry)) (str feature " :probe-key is a static keyword")))))
