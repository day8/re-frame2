(ns re-frame.machine-hostile-keys-cljs-test
  "rf2-dhl4d — `validate-machine!` must REJECT a non-`Named` key, not throw a
  host exception at it.

  A machine definition is not always hand-written. It can be merged from
  config, decoded from JSON or transit, or emitted by a generator, so its keys
  are whatever the producer put there — a string, a number, a vector, an opaque
  host object. `reg-machine`'s registration gate is the boundary that promises
  a structured `:rf.error/machine-*` rejection for anything malformed, and that
  promise is only worth something if the validator is TOTAL over the keys it is
  handed.

  It was not. Two operations the key checks perform are PARTIAL on the host:

    - `namespace`, which is defined only on `Named` and THROWS otherwise, so
      `{:initial :a :states {:a {}} \"x\" 1}` raised a bare
      `ClassCastException` (a `js/Error` on CLJS) out of `validate-machine!`;
    - `pr-str` of the offending keys in the diagnostic MESSAGE, which reaches
      an arbitrary object's `toString` — the same defect one level down, where
      a key that refuses to print destroys the very failure being described.

  Either way the caller got a host exception carrying no `:rf.error/id` instead
  of the documented `:rf.error/machine-unknown-node-key`, so every consumer that
  pivots on that discriminator (Xray's error widget, the pair-tool overlay,
  `:on-error` policies) saw nothing it could read.

  The suite is deliberately CROSS-PLATFORM (`*_cljs_test.cljc`, discovered by
  both the JVM runner and shadow-cljs). A key's TYPE is exactly the axis that
  differs per host — the JVM's `Named` cast and CLJS's `INamed` protocol check
  fail in different ways — so a single-platform test would pin only half the
  contract. `opaque-key` below is forged per host for that reason."
  (:require
   #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
      :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
   [clojure.string :as str]
   [re-frame.core :as rf]
   ;; Load the machines facade so `rf/reg-machine` routes through its
   ;; late-bind hook (`:machines/reg-machine`).
   [re-frame.machines]
   [re-frame.machines.test-support :as mtest]
   #?@(:clj  [[re-frame.substrate.plain-atom :as plain-atom]]
       :cljs [[re-frame.adapter.reagent :as reagent-adapter]])))

(use-fixtures :each
  (mtest/make-reset-runtime-fixture
    #?(:clj  {:adapter plain-atom/adapter}
       :cljs {:adapter reagent-adapter/adapter})))

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

(defn- reg-message
  "The `ex-message` of the error `machine` raises, or nil when it registers.
  Reading the message at all is half the assertion — the message is built
  EAGERLY inside `validation-error`, so a renderer that is not total throws
  before this ever returns."
  [machine]
  (try
    (rf/reg-machine (keyword "hk" (str (gensym))) machine)
    nil
    (catch #?(:clj Throwable :cljs :default) t
      (ex-message t))))

;; ---------------------------------------------------------------------------
;; (1) Non-Named keys are REJECTED, at the root and at a node.

(deftest non-named-root-key-rejected
  (testing "a String / number / vector / opaque-host key on the machine ROOT
            earns :rf.error/machine-unknown-node-key — not a host throw"
    (is (= :rf.error/machine-unknown-node-key
           (reg-outcome {:initial :a :states {:a {}} "x" 1}))
        "String root key")
    (is (= :rf.error/machine-unknown-node-key
           (reg-outcome {:initial :a :states {:a {}} 7 1}))
        "number root key")
    (is (= :rf.error/machine-unknown-node-key
           (reg-outcome {:initial :a :states {:a {}} [1 2] 1}))
        "vector root key")
    (is (= :rf.error/machine-unknown-node-key
           (reg-outcome {:initial :a :states {:a {}} (forge-opaque-key) 1}))
        "opaque host key whose toString throws")))

(deftest non-named-node-key-rejected
  (testing "the same key classes on a STATE NODE earn the same rejection —
            the walk reaches every node, not just the root"
    (is (= :rf.error/machine-unknown-node-key
           (reg-outcome {:initial :a :states {:a {"x" 1}}}))
        "String node key")
    (is (= :rf.error/machine-unknown-node-key
           (reg-outcome {:initial :a :states {:a {42 1}}}))
        "number node key")
    (is (= :rf.error/machine-unknown-node-key
           (reg-outcome {:initial :a :states {:a {[1 2] 1}}}))
        "vector node key")
    (is (= :rf.error/machine-unknown-node-key
           (reg-outcome {:initial :a :states {:a {(forge-opaque-key) 1}}}))
        "opaque host node key whose toString throws"))

  (testing "a non-Named key on a NESTED compound's child node is reached too"
    (is (= :rf.error/machine-unknown-node-key
           (reg-outcome {:initial :o
                         :states {:o {:initial :i :states {:i {"x" 1}}}}}))
        "String key two levels down")))

(deftest non-named-spawn-spec-key-rejected
  (testing "the :spawn spec's key check is the same walk and is equally total"
    (is (= :rf.error/machine-unknown-spawn-key
           (reg-outcome {:initial :a :states {:a {:spawn {:machine-id :m "x" 1}}}}))
        "String :spawn key")
    (is (= :rf.error/machine-unknown-spawn-key
           (reg-outcome {:initial :a
                         :states {:a {:spawn {:machine-id :m (forge-opaque-key) 1}}}}))
        "opaque host :spawn key")))

(deftest non-named-spawn-all-block-key-rejected
  (testing ":spawn-all's block-key check carries its own copy of the walk and is
            equally total"
    (let [block {:children        [{:id :c1 :machine-id :m}]
                 :on-child-done   :cd
                 :on-child-error  :ce
                 :on-all-complete [:done]}]
      (is (= :rf.error/machine-spawn-all-bad-shape
             (reg-outcome {:initial :a
                           :states {:a {:spawn-all (assoc block "x" 1)}
                                    :done {}}}))
          "String :spawn-all block key")
      (is (= :rf.error/machine-spawn-all-bad-shape
             (reg-outcome {:initial :a
                           :states {:a {:spawn-all (assoc block (forge-opaque-key) 1)}
                                    :done {}}}))
          "opaque host :spawn-all block key"))))

;; ---------------------------------------------------------------------------
;; (2) The diagnostic MESSAGE is total — and says something useful.

(deftest diagnostic-message-is-total-over-any-key
  (testing "an unprintable key renders as its shape TAG, so the message can be
            built at all — `<scalar>` for an opaque host object, `<vector>` for
            a collection that might contain one"
    (let [msg (reg-message {:initial :a :states {:a {}} (forge-opaque-key) 1})]
      (is (string? msg) "the message was built without reaching the hostile toString")
      (is (str/includes? msg "<scalar>")
          "the opaque key is named by SHAPE, from the error/diag-value-summary vocabulary"))
    (let [msg (reg-message {:initial :a :states {:a {}} [1 2] 1})]
      (is (str/includes? msg "<vector>")
          "a collection key renders by shape too — pr-str would descend into it")))

  (testing "an EDN scalar still prints LITERALLY — naming the key IS the
            diagnostic, and reg-machine's caller is holding the definition
            already (the deliberate divergence from machines-viz, whose caller
            is handed a definition decoded from a share URL)"
    (let [msg (reg-message {:initial :a :states {:a {:on-entry :oops}}})]
      (is (str/includes? msg "[:on-entry]")
          "the ordinary typo diagnostic is unchanged, byte for byte"))
    (let [msg (reg-message {:initial :a :states {:a {"x" 1}}})]
      (is (str/includes? msg "[\"x\"]")
          "a String key prints as a String — it is legible and it is safe to print"))))

(deftest ex-data-still-names-the-offending-keys
  (testing "the structured rejection still carries the offending key in ex-data
            for an ordinary typo — the fix must not gut the diagnostic"
    (let [d (try (rf/reg-machine :hk/diag {:initial :a :states {:a {:on-entry :oops}}})
                 nil
                 (catch #?(:clj Throwable :cljs :default) t (ex-data t)))]
      (is (= :rf.error/machine-unknown-node-key (:rf.error/id d)))
      (is (= [:on-entry] (:offending-keys d))))))

;; ---------------------------------------------------------------------------
;; (3) The carve-out is UNCHANGED.

(deftest namespaced-carve-out-unchanged
  (testing "a namespaced KEYWORD is still the open extension carve-out"
    (is (nil? (reg-outcome {:initial :a :states {:a {:my.app/note "x"}}}))))

  (testing "a namespaced SYMBOL is still carved out — `namespace` was defined on
            Named, not on keywords, and the Named-ness test must keep both arms"
    (is (nil? (reg-outcome {:initial :a :states {:a {'my.app/note "x"}}}))))

  (testing "a BARE symbol is not carved out — it is Named but unnamespaced, the
            same position a bare keyword typo is in"
    (is (= :rf.error/machine-unknown-node-key
           (reg-outcome {:initial :a :states {:a {'note "x"}}}))))

  (testing "an ordinary valid machine still registers cleanly"
    (is (nil? (reg-outcome {:initial :idle
                            :states {:idle {:on {:go :done}}
                                     :done {:final? true}}})))))
