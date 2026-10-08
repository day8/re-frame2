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
  (:require #?(:clj  [clojure.test :refer [deftest is use-fixtures]]
               :cljs [cljs.test :refer-macros [deftest is use-fixtures]])
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

(defn- dispose-thrown [adapter drain!]
  (rf.substrate.adapter/dispose-adapter!)
  (rf.substrate.adapter/install-adapter! adapter)
  (with-drain-hook drain!
    #(try (rf.substrate.adapter/dispose-adapter!) nil
          (catch #?(:clj Throwable :cljs :default) e e))))

(deftest a-failing-drain-stays-primary-over-a-failing-disposer
  (let [drain-boom    (ex-info "client-root drain failed" {:step ::drain})
        dispose-boom  (ex-info "adapter disposer failed" {:step ::dispose})
        disposer-ran? (atom false)
        thrown        (dispose-thrown (assoc rf.substrate.plain-atom/adapter
                                             :dispose-adapter! (fn []
                                                                 (reset! disposer-ran? true)
                                                                 (throw dispose-boom)))
                                      (fn [] (throw drain-boom)))]
    ;; The install slot is cleared and the disposed breadcrumb set all the same.
    (is (= [true true [dispose-boom] nil true]
           [@disposer-ran? (identical? drain-boom thrown) (secondary-failures thrown)
            (rf.substrate.adapter/current-adapter) (rf.substrate.adapter/adapter-disposed?)]))))

(deftest a-lone-failure-carries-no-secondary-evidence
  (let [dispose-boom (ex-info "adapter disposer failed" {:step ::dispose})
        thrown       (dispose-thrown (assoc rf.substrate.plain-atom/adapter
                                            :dispose-adapter! (fn [] (throw dispose-boom)))
                                     (fn [] nil))]
    (is (= [true []] [(identical? dispose-boom thrown) (secondary-failures thrown)]))))
