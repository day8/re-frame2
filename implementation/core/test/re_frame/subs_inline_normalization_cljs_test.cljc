(ns re-frame.subs-inline-normalization-cljs-test
  "rf2-vxgfnd.219 — inline `:reg-sub` metadata is normalized through the SAME
  registrar contract as public `reg-sub` (EP-0026 — neither looser nor stricter).

  `re-frame.subs/lower-inline-sub` (the `:image/lower-inline-sub` lowering an
  image's inline `:reg-sub` descriptor runs) previously projected the raw
  metadata map straight onto the runnable descriptor, bypassing the retired-key
  guard, the classification validator, and the production `:doc` strip that
  public `reg-sub` runs. This suite pins the parity: a retired `:spec` key
  hard-errors on BOTH paths, a malformed `:sensitive` / `:large` declaration
  raises on BOTH, a valid declaration survives identically, production strips
  `:doc`, and the runtime-owned runnable slots win over any metadata that names
  them. A mutation restoring the direct `(assoc metadata …)` fails these rows.

  `.cljc` — the normalizer is host-agnostic, so this runs under both
  `clojure -M:test` (JVM) and `npm run test:cljs` (node CLJS)."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.subs :as subs]
            [re-frame.registrar :as registrar]
            [re-frame.interop :as interop]
            [re-frame.test-support :as test-support]))

(defn- reset-registry [test-fn]
  (let [snapshot (test-support/snapshot-registrar)]
    (registrar/clear-all!)
    (try (test-fn)
         (finally (test-support/restore-registrar! snapshot)))))

(use-fixtures :each reset-registry)

(defn- caught-ex-data
  "Run `f`; return the ex-data of an ExceptionInfo it throws, or nil if it
  returns normally."
  [f]
  (try (f) nil
       (catch #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core/ExceptionInfo) e
         (ex-data e))))

(def ^:private body (fn [_db _q] :ok))

;; ---- retired bare key (`:spec`) — HARD ERROR on BOTH paths ----------------

(deftest retired-spec-key-hard-errors-on-inline-and-public
  (testing "public reg-sub and inline lower-inline-sub BOTH reject the retired
            `:spec` bare key with the same discriminator + named replacement"
    (let [pub    (caught-ex-data #(subs/reg-sub :norm/pub-retired {:spec [:map]} body))
          inline (caught-ex-data #(subs/lower-inline-sub {:spec [:map]} body))]
      (doseq [[label ed] [["public" pub] ["inline" inline]]]
        (testing label
          (is (some? ed) "must throw")
          (is (= :rf.error/retired-registration-key (:rf.error/id ed)))
          (is (= :spec (:retired-key ed)))
          (is (= :schema (:replacement ed)) "names the v2 key `:schema`"))))))

;; ---- malformed classification — HARD ERROR on BOTH paths ------------------

(deftest bad-classification-hard-errors-on-inline-and-public
  (testing "public and inline BOTH reject a malformed `:sensitive` declaration"
    (let [pub    (caught-ex-data #(subs/reg-sub :norm/pub-badcls {:sensitive :wrong} body))
          inline (caught-ex-data #(subs/lower-inline-sub {:sensitive :wrong} body))]
      (doseq [[label ed] [["public" pub] ["inline" inline]]]
        (testing label
          (is (some? ed) "must throw")
          (is (= :rf.error/bad-classification (:rf.error/id ed))))))))

(deftest valid-classification-survives-identically-on-both-paths
  (testing "a valid `:sensitive` declaration survives on BOTH paths, at the same
            top-level slot — the descriptor and registrar entry agree"
    (subs/reg-sub :norm/pub-cls {:sensitive [[:token]]} body)
    (let [pub-meta   (registrar/handler-meta :sub :norm/pub-cls)
          inline-desc (subs/lower-inline-sub {:sensitive [[:token]]} body)]
      (is (= [[:token]] (:sensitive pub-meta)))
      (is (= [[:token]] (:sensitive inline-desc))
          "inline descriptor carries the SAME classification declaration"))))

;; ---- production `:doc` strip parity ---------------------------------------

(deftest doc-stripped-in-production-retained-in-dev-on-inline-path
  (testing "dev (default gate on) retains `:doc` for tooling / agent inspection"
    (let [desc (subs/lower-inline-sub {:doc "kept in dev"} body)]
      (is (= "kept in dev" (:doc desc)))))
  (testing "production (gate off) strips `:doc` from the inline runnable
            descriptor, exactly as the public registrar does"
    (with-redefs [interop/debug-enabled? false]
      (let [desc (subs/lower-inline-sub {:doc "elided in prod" :schema :int} body)]
        (is (not (contains? desc :doc)) ":doc absent from the inline descriptor in prod")
        (is (= :int (:schema desc)) "a load-bearing key like :schema is retained")))))

;; ---- runtime-owned slots win + extension keys preserved -------------------

(deftest runtime-owned-slots-win-over-metadata
  (testing "the runnable slots are installed AFTER normalization, so metadata
            naming them cannot override the runtime-owned values"
    (let [desc (subs/lower-inline-sub {:input-kind :parametric :input-signals [:x]} body)]
      (is (= body (:handler-fn desc)) ":handler-fn is the lowered impl")
      (is (= :db (:input-kind desc)) ":input-kind is the layer-1 :db, not the metadata's")
      (is (= [] (:input-signals desc)) ":input-signals is empty, not the metadata's"))))

(deftest namespaced-extension-keys-survive-on-inline-path
  (testing "namespaced extension keys pass normalization untouched (the open-map
            carve-out), matching public reg-sub"
    (let [desc (subs/lower-inline-sub {:myapp/analytics-id 7} body)]
      (is (= 7 (:myapp/analytics-id desc))))))
