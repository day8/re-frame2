(ns re-frame.ssr.payload-policy-cljs-test
  "Per-leaf smoke tests for `re-frame.ssr.payload-policy` (rf2-gtgf9,
  rf2-pffil single-opt consolidation).

  Pins the explicit, fail-closed hydration-payload policy contract, now
  carried by a SINGLE `:payload` opt (rf2-pffil folded the prior two-opt
  `:payload-keys` + `:payload-policy` surface into one — pre-alpha, no
  back-compat shim):

    - `apply-policy` returns a `select-keys` slice when the caller
      passes `:payload [<kws>]` (vector → allowlist).
    - `apply-policy` returns the whole `app-db` verbatim when the
      caller passes `:payload :rf.ssr.payload/whole-app-db` (keyword →
      whole-app-db opt-in).
    - `apply-policy` THROWS `:rf.error/ssr-missing-payload-policy`
      when `:payload` is absent / empty / nil (the **fail-closed proof**).
    - `apply-policy` THROWS `:rf.error/ssr-unknown-payload-policy`
      when `:payload` is a non-recognised keyword (typo).
    - `validate-policy-opts!` mirrors the same throw contract at
      construction time + returns opts unchanged on success.

  The two-opt surface's precedence rule (allowlist wins over whole-app-db)
  and silent-ignore branch are GONE: one opt holds exactly one value, so
  there is nothing to arbitrate — the allowlist-vs-whole choice is the
  value's SHAPE (vector vs keyword).

  These tests run on both JVM and Node — the policy logic is
  platform-neutral .cljc."
  (:require [clojure.test :refer [deftest is testing]]
            [re-frame.ssr.payload-policy :as payload-policy]))

(def sample-app-db
  {:public/articles  [:a :b :c]
   :public/user-id   "u-42"
   :server-only/auth "SECRET_TOKEN"
   :server-only/flag true
   :rf/runtime       {:routing {:current {:id :route/home}}}})

;; ---- apply-policy: allowlist branch (:payload vector) --------------------

(deftest apply-policy-allowlist-slices-app-db
  (testing ":payload [<kws>] ships only the listed keys"
    (let [slice (payload-policy/apply-policy
                  sample-app-db
                  {:payload [:public/articles :public/user-id]})]
      (is (= {:public/articles [:a :b :c]
              :public/user-id  "u-42"}
             slice)
          "exactly the keys in the allowlist; everything else dropped")
      (is (not (contains? slice :server-only/auth)))
      (is (not (contains? slice :server-only/flag))))))

(deftest apply-policy-allowlist-missing-keys-omitted
  (testing "allowlist keys absent from app-db → omitted from the slice
            (the policy is a permission, not a guarantee)"
    (let [slice (payload-policy/apply-policy
                  sample-app-db
                  {:payload [:public/articles :public/no-such-key]})]
      (is (= {:public/articles [:a :b :c]} slice)
          "missing keys silently absent; matches `select-keys` semantics"))))

(deftest apply-policy-allowlist-as-vector-or-list
  (testing "allowlist accepts any sequential coll shape"
    (doseq [coll-shape [[:public/articles]
                        '(:public/articles)
                        ;; Sets are NOT sequential — but `select-keys`
                        ;; accepts them. We chose `sequential?` for the
                        ;; allowlist guard to keep the contract narrow
                        ;; (programmer-intent: ordered allowlist; a set
                        ;; would imply unordered which doesn't match how
                        ;; allowlists are used in practice). Sets fail-
                        ;; closed instead — asserted in the next test so
                        ;; the contract surface is pinned.
                        ]]
      (let [slice (payload-policy/apply-policy
                    sample-app-db
                    {:payload coll-shape})]
        (is (= {:public/articles [:a :b :c]} slice)
            (str "allowlist as " (pr-str coll-shape)
                 " produces the expected slice"))))))

(deftest apply-policy-set-payload-fails-closed
  (testing "a SET :payload is not sequential → fails closed (the contract
            is a narrow ordered-allowlist; a set is rejected rather than
            silently accepted)"
    (is (thrown-with-msg?
          #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core/ExceptionInfo)
          #":rf\.error/ssr-missing-payload-policy"
          (payload-policy/apply-policy
            sample-app-db
            {:payload #{:public/articles}})))))

;; ---- apply-policy: whole-app-db branch (:payload keyword) ----------------

(deftest apply-policy-whole-app-db-policy-ships-everything
  (testing ":payload :rf.ssr.payload/whole-app-db ships app-db verbatim"
    (let [slice (payload-policy/apply-policy
                  sample-app-db
                  {:payload :rf.ssr.payload/whole-app-db})]
      (is (= sample-app-db slice)
          "whole-app-db opt-in → identity over app-db"))))

(deftest apply-policy-whole-app-db-policy-keyword-is-public-constant
  (testing "the policy keyword is exposed as a public def for callers"
    (is (= :rf.ssr.payload/whole-app-db
           payload-policy/whole-app-db-policy)
        "`whole-app-db-policy` constant matches the literal keyword
         documented in the contract")))

;; ---- apply-policy: fail-closed (the rf2-gtgf9 lock) ----------------------

(deftest apply-policy-throws-when-no-payload-supplied
  (testing "rf2-gtgf9 fail-closed: absence of :payload throws
            :rf.error/ssr-missing-payload-policy"
    (is (thrown-with-msg?
          #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core/ExceptionInfo)
          #":rf\.error/ssr-missing-payload-policy"
          (payload-policy/apply-policy sample-app-db {})))
    (is (thrown-with-msg?
          #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core/ExceptionInfo)
          #":rf\.error/ssr-missing-payload-policy"
          (payload-policy/apply-policy sample-app-db nil))
        "nil opts also throws — same contract")))

(deftest apply-policy-throws-when-allowlist-empty
  (testing "rf2-gtgf9: an empty :payload vector is treated as no-allowlist
            (shipping zero keys is almost certainly a programmer error,
            not intent) — fail-closed still fires"
    (is (thrown-with-msg?
          #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core/ExceptionInfo)
          #":rf\.error/ssr-missing-payload-policy"
          (payload-policy/apply-policy sample-app-db {:payload []})))
    (is (thrown-with-msg?
          #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core/ExceptionInfo)
          #":rf\.error/ssr-missing-payload-policy"
          (payload-policy/apply-policy sample-app-db {:payload nil})))))

(deftest apply-policy-throws-on-unknown-policy-keyword
  (testing "rf2-gtgf9: a typo'd :payload keyword surfaces as
            :rf.error/ssr-unknown-payload-policy — distinct from the
            missing-policy bucket so a typo doesn't silently land in
            the `nothing-supplied` arm"
    (is (thrown-with-msg?
          #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core/ExceptionInfo)
          #":rf\.error/ssr-unknown-payload-policy"
          (payload-policy/apply-policy
            sample-app-db
            {:payload :rf.ssr.payload/whole-db})) ; typo
        "typo'd policy keyword throws unknown-policy")
    (is (thrown-with-msg?
          #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core/ExceptionInfo)
          #":rf\.error/ssr-unknown-payload-policy"
          (payload-policy/apply-policy
            sample-app-db
            {:payload :myapp/custom-policy})))))

;; ---- validate-policy-opts!: construction-time arm ------------------------

(deftest validate-policy-opts-passes-allowlist
  (testing "valid :payload vector passes validation + returns opts unchanged"
    (let [opts {:on-create [:init] :payload [:public/articles]}]
      (is (= opts (payload-policy/validate-policy-opts! opts))
          "returns opts unchanged on success — composes cleanly into
           threading/let positions"))))

(deftest validate-policy-opts-passes-whole-app-db
  (testing "valid :payload whole-app-db keyword passes validation"
    (let [opts {:on-create [:init] :payload :rf.ssr.payload/whole-app-db}]
      (is (= opts (payload-policy/validate-policy-opts! opts))))))

(deftest validate-policy-opts-fails-closed
  (testing "rf2-gtgf9 fail-closed: validation throws on absence —
            handler-construction time arm of the same contract as
            apply-policy"
    (is (thrown-with-msg?
          #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core/ExceptionInfo)
          #":rf\.error/ssr-missing-payload-policy"
          (payload-policy/validate-policy-opts! {:on-create [:init]})))))

(deftest validate-policy-opts-throws-on-unknown-policy
  (testing "construction-time arm also catches typo'd :payload keywords"
    (is (thrown-with-msg?
          #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core/ExceptionInfo)
          #":rf\.error/ssr-unknown-payload-policy"
          (payload-policy/validate-policy-opts!
            {:on-create [:init] :payload :rf.ssr.payload/whole-db})))))

(deftest error-ex-data-carries-recovery-tag
  (testing "rf2-gtgf9: the structured error carries `:recovery
            :declare-payload-policy` so trace tooling can suggest the
            fix — Spec 009 error catalogue convention"
    (try
      (payload-policy/validate-policy-opts! {:on-create [:init]})
      (is false "should have thrown")
      (catch #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core/ExceptionInfo) e
        (is (= :declare-payload-policy
               (:recovery (ex-data e)))
            "error ex-data names the recovery action")))))
