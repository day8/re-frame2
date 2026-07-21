(ns re-frame.routing-boundary-totality-cljs-test
  "Cross-host parity for the exact/total navigate + route-url map boundaries
  (rf2-oq0ld). This file is `*-cljs-test.cljc` so the shadow-cljs `:node-test`
  build exercises the boundary on the CLJS host too, alongside the JVM
  `clojure -M:test` runner.

  The case that MATTERS cross-host is HETEROGENEOUS EDN-key reporting: a plain
  `sort` over a mixed-kind key set (a keyword beside a string / number) throws a
  `compare` exception, so both boundaries order the offending-key report by the
  shared CEDN-1 identity (`re-frame.identity/canonical-bytes`) instead — a total,
  host-symmetric order. The non-map / missing-`:to` route-url guards are pinned
  here too so a future host divergence would fail the CLJS runner. The
  JVM-rich behavioural cases (slice-unchanged, no-push, the other `:reason`
  discriminators) live in routing_navigation_test.clj + routing_registry_test.clj."
  (:require
   #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
      :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
   [re-frame.core :as rf]
   [re-frame.identity :as identity]
   [re-frame.routing :as routing]
   [re-frame.test-support :as test-support]
   #?(:clj  [re-frame.substrate.plain-atom :as substrate]
      :cljs [re-frame.adapter.reagent :as substrate])))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter substrate/adapter
     :init-fn routing/reset-counters!}))

(defn- thrown
  "Call `f` and return the thrown ExceptionInfo (or nil)."
  [f]
  (try (f) nil (catch #?(:clj clojure.lang.ExceptionInfo :cljs ExceptionInfo) e e)))

;; ---- route-url address-shape boundary is total on both hosts -------------

(deftest route-url-non-map-address-rejects-cross-host
  (testing "a non-map address rejects with :rf.error/route-url-validation
            (:reason :not-a-map) on both hosts — not a raw `(keys …)` throw"
    (let [ex (thrown #(routing/route-url "/dest"))]
      (is (some? ex))
      (is (= :rf.error/route-url-validation (:rf.error/id (ex-data ex))))
      (is (= :not-a-map (:reason (ex-data ex)))))))

(deftest route-url-missing-to-rejects-cross-host
  (testing "a missing-:to address rejects with :rf.error/route-url-validation
            (:reason :missing-to) on both hosts — not the misleading
            :rf.error/no-such-route 'id nil'"
    (let [ex (thrown #(routing/route-url {:params {:id "x"}}))]
      (is (some? ex))
      (is (= :rf.error/route-url-validation (:rf.error/id (ex-data ex))))
      (is (= :missing-to (:reason (ex-data ex)))))))

(deftest route-url-heterogeneous-bad-keys-total-cross-host
  (testing "mixed-kind bad address keys report :bad-address-keys in total
            canonical order (no raw compare throw) on both hosts"
    ;; :url is address-rejected; "s" and 3 are unknown keys of DIFFERENT
    ;; kinds — a plain `(sort #{:url \"s\" 3})` throws on the JVM.
    (let [ex (thrown #(routing/route-url {:to :route/x :url "/x" "s" 1 3 2}))]
      (is (some? ex))
      (is (= :rf.error/route-url-validation (:rf.error/id (ex-data ex))))
      (is (= :bad-address-keys (:reason (ex-data ex))))
      (is (= (vec (sort-by identity/canonical-bytes #{:url "s" 3}))
             (:keys (ex-data ex)))))))

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
  (testing "a navigate request with mixed-kind unknown keys reports
            :unknown-keys in total canonical order (no raw compare throw)
            on both hosts"
    ;; :a/b, "s", and 3 are unknown keys of DIFFERENT kinds — a plain
    ;; `(sort #{:a/b \"s\" 3})` throws a ClassCastException on the JVM.
    (let [err (navigate-error {:to :route/gate :a/b 1 "s" 2 3 4})]
      (is (some? err) ":rf.error/navigate-bad-request emitted (no raw compare throw)")
      (is (= :unknown-keys (-> err :tags :reason)))
      (is (= (vec (sort-by identity/canonical-bytes #{:a/b "s" 3}))
             (-> err :tags :keys))))))
