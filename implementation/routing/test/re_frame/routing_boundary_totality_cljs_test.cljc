(ns re-frame.routing-boundary-totality-cljs-test
  "Cross-host parity for the exact, total `navigate` and `route-url` map
  boundaries. Named `*-cljs-test.cljc` so the shadow-cljs `:node-test` build
  runs it alongside the JVM runner.

  The case that matters cross-host is HETEROGENEOUS key reporting: a plain
  `sort` over a mixed-kind key set (a keyword beside a string or a number)
  throws a `compare` exception, so both boundaries order the offending keys by
  the shared CEDN-1 identity (`re-frame.identity/canonical-bytes`), a total,
  host-symmetric order. The non-map and missing-`:to` `route-url` guards and
  the `:query-merge` value shape are pinned here too. The JVM-rich door
  behaviour (slice unchanged, no push, the other `:reason` discriminators)
  lives in routing_navigation_test.clj and routing_address_extraction_test.clj.

  ## Posture split

  Both boundaries are always-on and production-surviving. `route-url` THROWS,
  so its three tests are posture-independent. `navigate`'s verdict comes from
  the always-on structural gate (`re-frame.routing.address/classify`), a pure
  function asserted directly in both postures. The `:rf.error/navigate-bad-request`
  TRACE the gate emits is dev-only (`trace/emit-error!` sits behind
  `rf.interop/debug-enabled?`), so that read sits inside a
  `(when rf.interop/debug-enabled? …)` arm."
  (:require
   #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
      :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
   [re-frame.core :as rf]
   [re-frame.identity :as rf.identity]
   [re-frame.interop :as rf.interop]
   [re-frame.routing :as rf.routing]
   [re-frame.routing.address :as rf.routing.address]
   [re-frame.test-support :as rf.test-support]
   #?(:clj  [re-frame.substrate.plain-atom :as substrate]
      :cljs [re-frame.adapter.reagent :as substrate])))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter substrate/adapter
     :init-fn rf.routing/reset-counters!}))

(defn- route-url-error
  "The identifying slots of the error `route-url` throws for `address`."
  [address]
  (select-keys (ex-data (try (rf.routing/route-url address)
                             nil
                             (catch #?(:clj clojure.lang.ExceptionInfo :cljs ExceptionInfo) e e)))
               [:rf.error/id :reason :keys]))

;; ---- route-url address-shape boundary is total on both hosts -------------

(deftest route-url-non-map-address-rejects-cross-host
  (is (= {:rf.error/id :rf.error/route-url-validation :reason :not-a-map}
         (route-url-error "/dest"))
      "a structured rejection, not a raw `(keys …)` host throw"))

(deftest route-url-missing-to-rejects-cross-host
  (is (= {:rf.error/id :rf.error/route-url-validation :reason :missing-to}
         (route-url-error {:params {:id "x"}}))
      "not the misleading :rf.error/no-such-route for id nil"))

(deftest route-url-heterogeneous-bad-keys-total-cross-host
  (testing "mixed-kind bad address keys are reported in canonical order; a
            plain sort of a keyword, a string and a number throws on the JVM"
    (is (= {:rf.error/id :rf.error/route-url-validation
            :reason      :bad-address-keys
            :keys        (vec (sort-by rf.identity/canonical-bytes #{:url "s" 3}))}
           (route-url-error {:to :route/x :url "/x" "s" 1 3 2})))))

;; ---- navigate unknown-key reporting is total on both hosts ---------------

(defn- navigate-error
  "Dispatch `[:rf.route/navigate request]` and return the first
  :rf.error/navigate-bad-request trace (or nil)."
  [request]
  (let [errors (atom [])]
    (rf/register-listener! :trace ::boundary
                           (fn [ev] (when (= :error (:op-type ev))
                                      (swap! errors conj ev))))
    (rf/dispatch-sync [:rf.route/navigate request])
    (rf/unregister-listener! :trace ::boundary)
    (first (filter #(= :rf.error/navigate-bad-request (:operation %)) @errors))))

(deftest navigate-heterogeneous-unknown-keys-total-cross-host
  (testing "mixed-kind unknown keys are reported in canonical order, and the
            dispatch completes without a host compare throw"
    (let [request  {:to :route/gate :a/b 1 "s" 2 3 4}
          expected {:reason :unknown-keys
                    :keys   (vec (sort-by rf.identity/canonical-bytes #{:a/b "s" 3}))}
          err      (navigate-error request)]
      (is (= expected (rf.routing.address/classify request nil)))
      ;; Dev-instrumentation arm (see ns docstring).
      (when rf.interop/debug-enabled?
        (is (= expected (select-keys (:tags err) [:reason :keys])))))))

;; ---- navigate `:query-merge` VALUE shape is total on both hosts ----------

(deftest navigate-non-map-query-merge-rejects-cross-host
  (testing "the always-on gate rejects any present non-map :query-merge with a
            stable reason, so neither host's collection semantics decide it;
            a map delta, empty included, passes"
    (let [current {:route-id :route/search :query {:q "x"}}
          verdict #(rf.routing.address/classify {:query-merge %} current)]
      (is (= (repeat 4 {:reason :query-merge-not-map :keys [:query-merge]})
             (map verdict [[:page 2] "oops" nil [[:page 2]]])))
      (is (= [nil nil] (map verdict [{} {:page 2}]))))))
