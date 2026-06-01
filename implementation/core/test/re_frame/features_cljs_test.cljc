(ns re-frame.features-cljs-test
  "Regression coverage for the feature-inspection front-porch
  (rf2-3nbl5.5, API-governance G5):

    (rf/features)               — lists every optional feature + status
    (rf/feature-loaded? :epoch) — true/false per loaded / absent feature
    (rf/require-feature! :epoch) — no-op when present; throws the exact
                                  copy-pasteable coordinate when absent

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
            [clojure.string :as str]
            [re-frame.features :as features]
            [re-frame.late-bind :as late-bind]))

(defn- with-probe-absent
  "Run `f` with `feature`'s representative late-bind probe key
  temporarily set to nil (simulating an artefact that was never
  required). Restores the original value afterwards (success or throw)."
  [feature f]
  (let [probe-key (get-in features/feature-registry [feature :probe-key])
        original  (late-bind/get-fn probe-key)]
    (try
      (late-bind/set-fn! probe-key nil)
      (f)
      (finally
        (late-bind/set-fn! probe-key original)))))

;; ---- feature-loaded? ------------------------------------------------------

(deftest feature-loaded?-true-when-artefact-present
  (testing "every per-feature artefact is loaded in the in-tree test build"
    (doseq [feature (keys features/feature-registry)]
      (is (true? (features/feature-loaded? feature))
          (str feature " probe key should be populated in the test build")))))

(deftest feature-loaded?-false-when-artefact-absent
  (testing "flipping a feature's probe key to nil reports it as not loaded"
    (with-probe-absent :epoch
      (fn []
        (is (false? (features/feature-loaded? :epoch))
            "absent probe key => feature-loaded? false")))
    (testing "the flip is isolated — other features stay loaded"
      (is (true? (features/feature-loaded? :schemas))))))

(deftest feature-loaded?-false-for-unknown-feature
  (testing "an unknown feature keyword is reported as not loaded, no throw"
    (is (false? (features/feature-loaded? :not-a-feature)))
    (is (false? (features/feature-loaded? nil)))))

;; ---- features -------------------------------------------------------------

(deftest features-lists-every-optional-feature-with-status
  (testing "features returns one entry per registry feature, carrying the
            static coordinate data + live :loaded? status"
    (let [m (features/features)]
      (is (= (set (keys features/feature-registry)) (set (keys m)))
          "exactly the registry's feature keys")
      (doseq [[feature entry] m]
        (is (= #{:maven :require :spec :loaded?} (set (keys entry)))
            (str feature " entry shape — coordinate data + :loaded?, no :probe-key leak"))
        (is (string? (:maven entry)))
        (is (string? (:require entry)))
        (is (boolean? (:loaded? entry))))
      (is (true? (get-in m [:epoch :loaded?]))
          "epoch loaded in the in-tree build")
      (is (= "day8/re-frame2-epoch" (get-in m [:epoch :maven])))
      (is (= "re-frame.epoch" (get-in m [:epoch :require]))))))

(deftest features-reflects-absent-feature-status
  (testing "features :loaded? tracks the live probe state"
    (with-probe-absent :routing
      (fn []
        (is (false? (get-in (features/features) [:routing :loaded?]))
            "routing reports :loaded? false while its probe is nil")
        (is (= "day8/re-frame2-routing" (get-in (features/features) [:routing :maven]))
            "coordinate is static — present regardless of loaded? status")))))

;; ---- require-feature! -----------------------------------------------------

(deftest require-feature!-no-op-when-present
  (testing "require-feature! returns true when the feature is loaded"
    (is (true? (features/require-feature! :epoch)))
    (is (true? (features/require-feature! :schemas)))))

(deftest require-feature!-throws-exact-coordinate-when-absent
  (testing "require-feature! throws :rf.error/feature-not-loaded with the
            exact copy-pasteable Maven coordinate + require form"
    (with-probe-absent :epoch
      (fn []
        (let [ex   (is (thrown? #?(:clj clojure.lang.ExceptionInfo :cljs js/Error)
                                (features/require-feature! :epoch)))
              data (ex-data ex)]
          (is (= :rf.error/feature-not-loaded (:rf.error/id data)))
          (is (= :epoch (:feature data)))
          (is (= "day8/re-frame2-epoch" (:maven data))
              "exact Maven coordinate to add to deps")
          (is (= "re-frame.epoch" (:require-ns data))
              "exact namespace to require at boot")
          (is (= :no-recovery (:recovery data)))
          (let [reason (:reason data)]
            (is (str/includes? reason "day8/re-frame2-epoch")
                "reason carries the copy-pasteable Maven coordinate")
            (is (str/includes? reason "re-frame.epoch")
                "reason carries the copy-pasteable require namespace")))))))

(deftest require-feature!-throws-unknown-feature
  (testing "require-feature! on an unknown keyword throws :rf.error/unknown-feature
            listing the known set"
    (let [ex   (is (thrown? #?(:clj clojure.lang.ExceptionInfo :cljs js/Error)
                            (features/require-feature! :not-a-feature)))
          data (ex-data ex)]
      (is (= :rf.error/unknown-feature (:rf.error/id data)))
      (is (= :not-a-feature (:feature data)))
      (is (contains? (set (:known data)) :epoch)
          ":known lists the registry features"))))

;; ---- bundle-isolation invariant (static-data, not a live require) ---------

(deftest feature-registry-is-static-data
  (testing "the coordinate table is plain data — every value is a static
            map of strings + a probe keyword, no fn / artefact reach-in"
    (doseq [[feature entry] features/feature-registry]
      (is (string? (:maven entry)) (str feature " :maven is a static string"))
      (is (string? (:require entry)) (str feature " :require is a static string"))
      (is (keyword? (:probe-key entry)) (str feature " :probe-key is a static keyword")))))
