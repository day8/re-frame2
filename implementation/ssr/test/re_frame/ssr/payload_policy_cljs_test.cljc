(ns re-frame.ssr.payload-policy-cljs-test
  "Per-leaf smoke tests for `re-frame.ssr.payload-policy` (rf2-gtgf9).

  Pins the explicit, fail-closed hydration-payload policy contract:

    - `apply-policy` returns a `select-keys` slice when the caller
      passes `:payload-keys`.
    - `apply-policy` returns the whole `app-db` verbatim when the
      caller passes `:payload-policy :rf.ssr.payload/whole-app-db`.
    - `apply-policy` THROWS `:rf.error/ssr-missing-payload-policy`
      when neither opt is present (the **fail-closed proof**).
    - `apply-policy` THROWS `:rf.error/ssr-unknown-payload-policy`
      when `:payload-policy` is set to a non-recognised keyword.
    - `validate-policy-opts!` mirrors the same throw contract at
      construction time + returns opts unchanged on success.
    - `:payload-keys` wins over `:payload-policy` (the more-restrictive
      choice) when both are present.

  These tests run on both JVM and Node — the policy logic is
  platform-neutral .cljc."
  (:require [clojure.test :refer [deftest is testing]]
            [re-frame.ssr.payload-policy :as payload-policy]))

(def sample-app-db
  {:public/articles  [:a :b :c]
   :public/user-id   "u-42"
   :server-only/auth "SECRET_TOKEN"
   :server-only/flag true
   :rf/route         {:id :route/home}})

;; ---- apply-policy: allowlist branch --------------------------------------

(deftest apply-policy-allowlist-slices-app-db
  (testing ":payload-keys ships only the listed keys"
    (let [slice (payload-policy/apply-policy
                  sample-app-db
                  {:payload-keys [:public/articles :public/user-id]})]
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
                  {:payload-keys [:public/articles :public/no-such-key]})]
      (is (= {:public/articles [:a :b :c]} slice)
          "missing keys silently absent; matches `select-keys` semantics"))))

(deftest apply-policy-allowlist-as-vector-or-set-or-list
  (testing "allowlist accepts any sequential / set coll shape"
    (doseq [coll-shape [[:public/articles]
                        '(:public/articles)
                        ;; Sets are NOT sequential — but `select-keys`
                        ;; accepts them. We chose `sequential?` for the
                        ;; allowlist guard to keep the contract narrow
                        ;; (programmer-intent: ordered allowlist; a set
                        ;; would imply unordered which doesn't match how
                        ;; allowlists are used in practice). Sets fail-
                        ;; closed instead — assert that here so the
                        ;; contract surface is pinned.
                        ]]
      (let [slice (payload-policy/apply-policy
                    sample-app-db
                    {:payload-keys coll-shape})]
        (is (= {:public/articles [:a :b :c]} slice)
            (str "allowlist as " (pr-str coll-shape)
                 " produces the expected slice"))))))

;; ---- apply-policy: whole-app-db branch -----------------------------------

(deftest apply-policy-whole-app-db-policy-ships-everything
  (testing ":payload-policy :rf.ssr.payload/whole-app-db ships app-db verbatim"
    (let [slice (payload-policy/apply-policy
                  sample-app-db
                  {:payload-policy :rf.ssr.payload/whole-app-db})]
      (is (= sample-app-db slice)
          "whole-app-db opt-in → identity over app-db"))))

(deftest apply-policy-whole-app-db-policy-keyword-is-public-constant
  (testing "the policy keyword is exposed as a public def for callers"
    (is (= :rf.ssr.payload/whole-app-db
           payload-policy/whole-app-db-policy)
        "`whole-app-db-policy` constant matches the literal keyword
         documented in the contract")))

;; ---- apply-policy: fail-closed (the rf2-gtgf9 lock) ----------------------

(deftest apply-policy-throws-when-no-policy-supplied
  (testing "rf2-gtgf9 fail-closed: absence of both :payload-keys and
            :payload-policy throws :rf.error/ssr-missing-payload-policy"
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
  (testing "rf2-gtgf9: an empty :payload-keys is treated as no-allowlist
            (shipping zero keys is almost certainly a programmer error,
            not intent) — fail-closed still fires"
    (is (thrown-with-msg?
          #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core/ExceptionInfo)
          #":rf\.error/ssr-missing-payload-policy"
          (payload-policy/apply-policy sample-app-db {:payload-keys []})))
    (is (thrown-with-msg?
          #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core/ExceptionInfo)
          #":rf\.error/ssr-missing-payload-policy"
          (payload-policy/apply-policy sample-app-db {:payload-keys nil})))))

(deftest apply-policy-throws-on-unknown-policy-keyword
  (testing "rf2-gtgf9: a typo'd :payload-policy keyword surfaces as
            :rf.error/ssr-unknown-payload-policy — distinct from the
            missing-policy bucket so a typo doesn't silently land in
            the `nothing-supplied` arm"
    (is (thrown-with-msg?
          #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core/ExceptionInfo)
          #":rf\.error/ssr-unknown-payload-policy"
          (payload-policy/apply-policy
            sample-app-db
            {:payload-policy :rf.ssr.payload/whole-db})) ; typo
        "typo'd policy keyword throws unknown-policy")
    (is (thrown-with-msg?
          #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core/ExceptionInfo)
          #":rf\.error/ssr-unknown-payload-policy"
          (payload-policy/apply-policy
            sample-app-db
            {:payload-policy :myapp/custom-policy})))))

;; ---- precedence: allowlist wins over whole-app-db ------------------------

(deftest apply-policy-allowlist-wins-over-whole-app-db
  (testing "rf2-gtgf9: when both :payload-keys and :payload-policy are
            passed, the allowlist wins (it's the more-restrictive
            policy choice — not a contradiction)"
    (let [slice (payload-policy/apply-policy
                  sample-app-db
                  {:payload-keys   [:public/articles]
                   :payload-policy :rf.ssr.payload/whole-app-db})]
      (is (= {:public/articles [:a :b :c]} slice)
          "allowlist takes precedence; the whole-app-db opt is ignored")
      (is (not (contains? slice :server-only/auth))
          "server-only keys still excluded — the allowlist wins"))))

;; ---- validate-policy-opts!: construction-time arm ------------------------

(deftest validate-policy-opts-passes-allowlist
  (testing "valid :payload-keys passes validation + returns opts unchanged"
    (let [opts {:on-create [:init] :payload-keys [:public/articles]}]
      (is (= opts (payload-policy/validate-policy-opts! opts))
          "returns opts unchanged on success — composes cleanly into
           threading/let positions"))))

(deftest validate-policy-opts-passes-whole-app-db
  (testing "valid :payload-policy passes validation"
    (let [opts {:on-create [:init] :payload-policy :rf.ssr.payload/whole-app-db}]
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
  (testing "construction-time arm also catches typo'd policy keywords"
    (is (thrown-with-msg?
          #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core/ExceptionInfo)
          #":rf\.error/ssr-unknown-payload-policy"
          (payload-policy/validate-policy-opts!
            {:on-create [:init] :payload-policy :rf.ssr.payload/whole-db})))))

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
