(ns re-frame.machine-hostile-keys-cljs-test
  "`validate-machine!` must REJECT a non-`Named` key, not throw a
  host exception at it.

  A machine definition is not always hand-written. It can be merged from
  config, decoded from JSON or transit, or emitted by a generator, so its keys
  are whatever the producer put there — a string, a number, a vector, an opaque
  host object. `reg-machine`'s registration gate is the boundary that promises
  a structured `:rf.error/machine-*` rejection for anything malformed, and that
  promise is only worth something if the validator is TOTAL over the keys it is
  handed.

  Two operations the key checks perform are PARTIAL on the host:

    - `namespace`, which is defined only on `Named` and THROWS otherwise, so
      unguarded it would raise a bare `ClassCastException` (a `js/Error` on
      CLJS) out of `validate-machine!` for `{:initial :a :states {:a {}} \"x\" 1}`;
    - `pr-str` of the offending keys in the diagnostic MESSAGE, which reaches
      an arbitrary object's `toString` — the same defect one level down, where
      a key that refuses to print would destroy the very failure being described.

  Either way the caller would get a host exception carrying no `:rf.error/id`
  instead of the documented `:rf.error/machine-unknown-node-key`, so every
  consumer that pivots on that discriminator (Xray's error widget, the pair-tool
  overlay, `:on-error` policies) would see nothing it could read.

  The suite is deliberately CROSS-PLATFORM (`*_cljs_test.cljc`, discovered by
  both the JVM runner and shadow-cljs). A key's TYPE is exactly the axis that
  differs per host — the JVM's `Named` cast and CLJS's `INamed` protocol check
  fail in different ways — so a single-platform test would pin only half the
  contract. `opaque-key` below is forged per host for that reason."
  (:require
   #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
      :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
   [re-frame.core :as rf]
   ;; Load the machines facade so `rf/reg-machine` routes through its
   ;; late-bind hook (`:machines/reg-machine`).
   [re-frame.machines]
   [re-frame.machines.test-support :as rf.machines.test-support]
   #?@(:clj  [[re-frame.substrate.plain-atom :as rf.substrate.plain-atom]]
       :cljs [[re-frame.adapter.reagent :as rf.adapter.reagent]])))

(use-fixtures :each
  (rf.machines.test-support/make-reset-runtime-fixture
    #?(:clj  {:adapter rf.substrate.plain-atom/adapter}
       :cljs {:adapter rf.adapter.reagent/adapter})))

;; ---------------------------------------------------------------------------
;; The forged key.

(defn- forge-opaque-key
  "A key of a type that exists only on THIS host and whose `toString` THROWS.

  One key exercises BOTH partial operations at once: it is not `Named`, so
  `namespace` rejects it, and it cannot be printed, so `pr-str` of the offending
  set does too. Forging it rather than modelling it is the point — a string key
  proves only the first half, and a hand-written fixture cannot reproduce a host
  object whose printing is hostile.

  JVM: a `reify` of `Object`. CLJS: a plain JS object with its `toString`
  replaced (CLJS's `namespace` interpolates the value into its own error
  message, so on that host a throwing `toString` is reached even earlier)."
  []
  #?(:clj  (reify Object
             (toString [_] (throw (ex-info "toString on this key is denied" {}))))
     :cljs (let [o #js {}]
             (set! (.-toString o) (fn [] (throw (js/Error. "toString on this key is denied"))))
             o)))

;; ---------------------------------------------------------------------------
;; Outcome classification.

(defn- reg-outcome
  "Register `machine` and CLASSIFY the outcome:

    nil                    — accepted;
    :rf.error/machine-*    — the documented structured rejection;
    [:host-throw <msg>]    — anything carrying no `:rf.error/id`, i.e. the
                             defect this suite exists to make impossible.

  Catching broadly and then discriminating on `ex-data` is deliberate: a test
  that caught only `ExceptionInfo` would report a host throw as an ERROR whose
  message names a cast, not the contract. This reports it as a FAILURE whose
  actual value literally reads `[:host-throw …]`."
  [machine]
  (try
    (rf/reg-machine (keyword "hk" (str (gensym))) machine)
    nil
    (catch #?(:clj Throwable :cljs :default) t
      (or (:rf.error/id (ex-data t))
          [:host-throw (ex-message t)]))))

;; ---------------------------------------------------------------------------
;; (1) Non-Named keys are REJECTED, at the root and at a node.

(deftest non-named-root-key-rejected
  (testing "a String or an opaque-host key on the machine ROOT earns
            :rf.error/machine-unknown-node-key — not a host throw"
    (is (= :rf.error/machine-unknown-node-key
           (reg-outcome {:initial :a :states {:a {}} "x" 1}))
        "String root key")
    (is (= :rf.error/machine-unknown-node-key
           (reg-outcome {:initial :a :states {:a {}} (forge-opaque-key) 1}))
        "opaque host key whose toString throws")))

(deftest non-named-node-key-rejected
  (is (= :rf.error/machine-unknown-node-key
         (reg-outcome {:initial :a :states {:a {"x" 1}}}))
      "String node key"))

(deftest non-named-spawn-spec-key-rejected
  (is (= :rf.error/machine-unknown-spawn-key
         (reg-outcome {:initial :a
                       :states {:a {:spawn {:machine-id :m (forge-opaque-key) 1}}}}))
      "opaque host :spawn key"))

(deftest non-named-spawn-all-block-key-rejected
  (testing ":spawn-all's block-key check carries its own copy of the walk and is
            equally total"
    (is (= :rf.error/machine-spawn-all-bad-shape
           (reg-outcome {:initial :a
                         :states {:a {:spawn-all {:children        [{:id :c1 :machine-id :m}]
                                                  :on-all-complete [:done]
                                                  (forge-opaque-key) 1}}
                                  :done {}}}))
        "opaque host :spawn-all block key")))

;; ---------------------------------------------------------------------------
;; (2) The diagnostic MESSAGE is total — and says something useful.

;; ---------------------------------------------------------------------------
;; (3) The namespaced-key carve-out.

(deftest namespaced-carve-out-unchanged
  (testing "a namespaced SYMBOL is carved out too — `namespace` is defined on
            Named, not only on keywords, and the Named-ness test must keep both arms"
    (is (nil? (reg-outcome {:initial :a :states {:a {'my.app/note "x"}}}))))
  (testing "a BARE symbol is not carved out — it is Named but unnamespaced, the
            same position a bare keyword typo is in"
    (is (= :rf.error/machine-unknown-node-key
           (reg-outcome {:initial :a :states {:a {'note "x"}}})))))
