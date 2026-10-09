(ns re-frame.schemas.printer-seam-test
  "The `:print` key of `set-schema-fns!`, the door a non-Malli port uses to
  plug its own serialiser into the digest pipeline: the digest hashes whatever
  the installed printer returns, and `{:print nil}` or `default-schema-fns`
  brings back the default printer the parity literals were taken over."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.frame :as rf.frame]
            [re-frame.schemas :as rf.schemas]))

(defn- reset [test-fn]
  ;; The printer and the schema registry are process-global, and
  ;; reg-app-schema needs an established frame scope.
  (rf.schemas/set-schema-fns! rf.schemas/default-schema-fns)
  (rf.schemas/clear-schemas-by-frame!)
  (try (binding [rf.frame/*current-frame* :rf/default]
         (test-fn))
       (finally (rf.schemas/set-schema-fns! rf.schemas/default-schema-fns)
                (rf.schemas/clear-schemas-by-frame!))))

(use-fixtures :each reset)

(deftest schema-print-swap-flips-the-digest-bytes
  (testing "a swapped printer moves the digest, and each way of restoring the
            default brings back the single-prim parity literal"
    (rf.schemas/reg-app-schema [:n] :int)
    (doseq [[label restore] [["{:print nil}" {:print nil}]
                             ["default-schema-fns" rf.schemas/default-schema-fns]]]
      (rf.schemas/set-schema-fns! {:print (fn [_schema] "::DIFFERENT::")})
      (is (not= "sha256:e7939756d704eaab"
                (rf.schemas/app-schemas-digest {:frame :rf/default}))
          label)
      (rf.schemas/set-schema-fns! restore)
      (is (= "sha256:e7939756d704eaab"
             (rf.schemas/app-schemas-digest {:frame :rf/default}))
          label))))

(deftest a-single-key-install-still-returns-the-whole-bundle
  (testing "the return is the live bundle: the keys a call did not touch, and
            the default printer a nil `:print` is coerced to"
    (let [v-fn (fn [_ _] true)
          e-fn (fn [_ _] {:e true})
          p-fn (fn [_] "::P::")]
      (rf.schemas/set-schema-fns! {:validate v-fn})
      (rf.schemas/set-schema-fns! {:explain e-fn})
      (is (= {:validate v-fn :explain e-fn :print p-fn}
             (rf.schemas/set-schema-fns! {:print p-fn})))
      (is (= ":int" ((:print (rf.schemas/set-schema-fns! {:print nil})) :int))))))
