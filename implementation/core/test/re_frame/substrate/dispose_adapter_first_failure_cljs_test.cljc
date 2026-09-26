(ns re-frame.substrate.dispose-adapter-first-failure-cljs-test
  "Spec 006 §Adapter disposal lifecycle: on a failed teardown the core
  attempts all remaining cleanup, rethrows the FIRST failure, and attaches
  later failures as secondary evidence.

  `re-frame.substrate.adapter/dispose-adapter!` runs two cleanup steps in
  order: the Fresco client-root drain (the `:fresco/drain-client-roots!`
  late-bind hook), then the installed adapter's own `:dispose-adapter!`.
  When both throw, the drain's failure is the first and is the one the
  caller receives; the disposer's failure rides on it — through
  `.getSuppressed` on the JVM, and through the
  `rfAdapterTeardownSecondaryErrors` array on CLJS, the same property the
  spine's own teardown uses."
  (:require #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
               :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
            [re-frame.late-bind :as rf.late-bind]
            [re-frame.substrate.adapter :as rf.substrate.adapter]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.test-support :as rf.test-support]))

(use-fixtures :each (rf.test-support/make-reset-runtime-fixture {}))

(defn- secondary-failures
  "The later cleanup failures attached to the rethrown primary."
  [thrown]
  #?(:clj  (vec (.getSuppressed ^Throwable thrown))
     :cljs (vec (or (unchecked-get thrown "rfAdapterTeardownSecondaryErrors") []))))

(defn- with-drain-hook
  "Publish `drain!` as the Fresco client-root drain for the extent of `body-fn`,
  then put back whatever was published before, or nothing."
  [drain! body-fn]
  (let [prior (rf.late-bind/get-fn :fresco/drain-client-roots!)]
    (try
      (rf.late-bind/set-fn! :fresco/drain-client-roots! drain!)
      (body-fn)
      (finally
        (if prior
          (rf.late-bind/set-fn! :fresco/drain-client-roots! prior)
          (do (swap! rf.late-bind/hooks dissoc :fresco/drain-client-roots!)
              (rf.late-bind/invalidate-cache! :fresco/drain-client-roots!)))))))

(deftest a-failing-drain-stays-primary-over-a-failing-disposer
  (testing "both cleanup steps throw: the first failure is rethrown, the later one attached"
    (let [drain-boom    (ex-info "client-root drain failed" {:step ::drain})
          dispose-boom  (ex-info "adapter disposer failed" {:step ::dispose})
          disposer-ran? (atom false)
          adapter       (assoc rf.substrate.plain-atom/adapter
                               :dispose-adapter! (fn []
                                                   (reset! disposer-ran? true)
                                                   (throw dispose-boom)))]
      (rf.substrate.adapter/dispose-adapter!)
      (rf.substrate.adapter/install-adapter! adapter)
      (with-drain-hook
        (fn [] (throw drain-boom))
        (fn []
          (let [thrown (try (rf.substrate.adapter/dispose-adapter!) nil
                            (catch #?(:clj Throwable :cljs :default) e e))]
            (is (true? @disposer-ran?)
                "a throwing drain does not skip the adapter's own disposer")
            (is (identical? drain-boom thrown)
                "the drain's failure, the first, is the one the caller receives")
            (is (= [dispose-boom] (secondary-failures thrown))
                "the disposer's later failure rides on the primary as secondary evidence")
            (is (nil? (rf.substrate.adapter/current-adapter))
                "the install slot is cleared all the same")
            (is (true? (rf.substrate.adapter/adapter-disposed?))
                "and the disposed breadcrumb is set")))))))

(deftest a-lone-failure-carries-no-secondary-evidence
  (testing "only the disposer throws: it is rethrown unchanged, with nothing attached"
    (let [dispose-boom (ex-info "adapter disposer failed" {:step ::dispose})
          adapter      (assoc rf.substrate.plain-atom/adapter
                              :dispose-adapter! (fn [] (throw dispose-boom)))]
      (rf.substrate.adapter/dispose-adapter!)
      (rf.substrate.adapter/install-adapter! adapter)
      (with-drain-hook
        (fn [] nil)
        (fn []
          (let [thrown (try (rf.substrate.adapter/dispose-adapter!) nil
                            (catch #?(:clj Throwable :cljs :default) e e))]
            (is (identical? dispose-boom thrown))
            (is (= [] (secondary-failures thrown)))))))))
