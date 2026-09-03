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
  discriminators) live in routing_navigation_test.clj + routing_registry_test.clj.

  ## Posture split (rf2-o5dbf)

  Both boundaries are ALWAYS-ON and production-surviving, and both are
  asserted here WITHOUT a posture guard. `route-url` THROWS, so its three
  tests were already posture-independent. `navigate` rejects by returning
  `{}` from the handler after the always-on structural gate
  (`re-frame.routing.address/classify`) — a distinct channel from the
  dev-only schemas validation one (navigate.cljc §236-250) — so the
  rejection, the canonical `:keys` ordering and the unchanged slice are all
  readable in production. The total-order property in particular is a
  property of `classify` itself, a pure always-on function, and is now
  asserted on it directly.

  What is dev-only is the `:rf.error/navigate-bad-request` TRACE the gate
  emits: `trace/emit-error!` sits behind `rf.interop/debug-enabled?`, read once
  at load time, so under `-Dre-frame.debug=false` the framework emits nothing
  BY DESIGN. Those assertions are kept VERBATIM inside a
  `(when rf.interop/debug-enabled? …)` arm marked `rf2-o5dbf`. Nothing was
  deleted or weakened."
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

(defn- thrown
  "Call `f` and return the thrown ExceptionInfo (or nil)."
  [f]
  (try (f) nil (catch #?(:clj clojure.lang.ExceptionInfo :cljs ExceptionInfo) e e)))

;; ---- route-url address-shape boundary is total on both hosts -------------

(deftest route-url-non-map-address-rejects-cross-host
  (testing "a non-map address rejects with :rf.error/route-url-validation
            (:reason :not-a-map) on both hosts — not a raw `(keys …)` throw"
    (let [ex (thrown #(rf.routing/route-url "/dest"))]
      (is (some? ex))
      (is (= :rf.error/route-url-validation (:rf.error/id (ex-data ex))))
      (is (= :not-a-map (:reason (ex-data ex)))))))

(deftest route-url-missing-to-rejects-cross-host
  (testing "a missing-:to address rejects with :rf.error/route-url-validation
            (:reason :missing-to) on both hosts — not the misleading
            :rf.error/no-such-route 'id nil'"
    (let [ex (thrown #(rf.routing/route-url {:params {:id "x"}}))]
      (is (some? ex))
      (is (= :rf.error/route-url-validation (:rf.error/id (ex-data ex))))
      (is (= :missing-to (:reason (ex-data ex)))))))

(deftest route-url-heterogeneous-bad-keys-total-cross-host
  (testing "mixed-kind bad address keys report :bad-address-keys in total
            canonical order (no raw compare throw) on both hosts"
    ;; :url is address-rejected; "s" and 3 are unknown keys of DIFFERENT
    ;; kinds — a plain `(sort #{:url \"s\" 3})` throws on the JVM.
    (let [ex (thrown #(rf.routing/route-url {:to :route/x :url "/x" "s" 1 3 2}))]
      (is (some? ex))
      (is (= :rf.error/route-url-validation (:rf.error/id (ex-data ex))))
      (is (= :bad-address-keys (:reason (ex-data ex))))
      (is (= (vec (sort-by rf.identity/canonical-bytes #{:url "s" 3}))
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
    (let [request {:to :route/gate :a/b 1 "s" 2 3 4}
          ;; SEMANTIC, posture-independent (rf2-o5dbf): the total order is a
          ;; property of the ALWAYS-ON structural gate, not of the diagnostic.
          ;; Assert it on `classify` directly — this is the assertion that
          ;; would have caught the raw-`compare` throw, and it survives
          ;; -Dre-frame.debug=false because `classify` does.
          bad     (rf.routing.address/classify request nil)
          err     (navigate-error request)]
      (is (= :unknown-keys (:reason bad))
          "the always-on structural gate classifies the request :unknown-keys")
      (is (= (vec (sort-by rf.identity/canonical-bytes #{:a/b "s" 3}))
             (:keys bad))
          "…and orders the offending keys by CEDN-1 identity (no raw compare throw)")
      ;; …and the REJECTION really held: the dispatch above completed without
      ;; a host throw and left no route slice behind.
      (is (nil? (get-in (:rf.db/runtime (rf/frame-state-value :rf/default))
                        [:rf.runtime/routing :current]))
          "the rejected navigate left the route slice unchanged (no commit)")
      ;; rf2-o5dbf — dev-instrumentation arm (see ns docstring).
      (when rf.interop/debug-enabled?
        (is (some? err) ":rf.error/navigate-bad-request emitted (no raw compare throw)")
        (is (= :unknown-keys (-> err :tags :reason)))
        (is (= (vec (sort-by rf.identity/canonical-bytes #{:a/b "s" 3}))
               (-> err :tags :keys)))))))
