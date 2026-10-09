(ns re-frame.schemas-record-type-tag-cljs-test
  "CLJS half of the closed type-tag contract, where an unclosed tag would be a
  disclosure: `cljs.core/type` is `(.-constructor x)`, an ordinary writable
  property, so `(str (type v))` on a foreign JS value carrying its own
  `constructor` field returns caller text, which the always-on `:errors`
  record must never carry."
  (:require [clojure.string :as str]
            [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.error-emit :as rf.error-emit]
            [re-frame.interop :as rf.interop]
            [re-frame.schemas :as rf.schemas]
            [re-frame.schemas.validate :as rf.schemas.validate]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.test-support :as rf.test-support]))

;; `reg-app-schema` needs an established frame scope, and the reset gives the
;; end-to-end test a clean schema slate.
(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter rf.substrate.plain-atom/adapter}))

(def ^:private tag #'rf.schemas.validate/record-type-tag)

(def ^:private sentinel "rf2-xpd8-secret-from-value")

(defn- planted-constructor-obj
  "A foreign JS object whose own `constructor` field is the sentinel."
  []
  (let [o (js-obj)]
    (aset o "constructor" sentinel)
    o))

(defn- hostile-proxy
  "A Proxy whose `get` trap THROWS. `map?` / `vector?` are protocol lookups,
  i.e. property GETs, so a classifier that reaches them can invoke this."
  []
  (js/Proxy. (js-obj)
             #js {:get (fn [_ k]
                         (throw (js/Error. (str "hostile accessor fired: " k))))}))

(deftest tag-survives-a-hostile-accessor
  (testing "a diagnostic must not explode while explaining a rejection —
            the throwing Proxy classifies rather than propagating"
    (is (= "object" (tag (hostile-proxy))))))

(deftest ^:requires-debug rejection-record-never-carries-the-planted-constructor
  (testing "a rejected candidate whose failing leaf carries a planted
            `constructor` emits a record with the constant tag and the
            sentinel in no slot"
    (when rf.interop/debug-enabled?
      (let [records (atom [])]
        (rf.error-emit/register-error-listener! ::rec (fn [r] (swap! records conj r)))
        (try
          (rf/reg-app-schema [:tenant] [:map [:id :int]])
          (rf.schemas/validate-app-schema!
            {:tenant {:id (planted-constructor-obj)}}
            :tenant/set-bad)
          (finally
            (rf.error-emit/unregister-error-listener! ::rec)))
        (let [rejections (filterv
                           #(and (= :rf.error/schema-validation-failure (:error %))
                                 (= :app-db (:where %)))
                           @records)
              r          (first rejections)]
          (is (= 1 (count rejections)))
          (is (str/includes? (:reason r) "got object") (pr-str (:reason r)))
          (is (not (str/includes? (pr-str r) sentinel)) (pr-str r)))))))
