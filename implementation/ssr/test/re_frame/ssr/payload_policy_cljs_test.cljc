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

(deftest apply-policy-allowlist-as-vector
  (testing "rf2-d8vs9x — the allowlist is a VECTOR; the vector shape
            produces the expected slice"
    (let [slice (payload-policy/apply-policy
                  sample-app-db
                  {:payload [:public/articles]})]
      (is (= {:public/articles [:a :b :c]} slice)
          "vector allowlist produces the expected slice"))))

(deftest apply-policy-list-payload-fails-closed
  (testing "rf2-d8vs9x — a LIST / seq :payload is NOT an accepted allowlist
            spelling (the contract is shape-selected: a vector is the
            allowlist, a keyword is the whole-app-db opt-in). The prior
            guard accepted any `sequential?` coll, which silently admitted
            lists and seqs — two spellings for one security-boundary
            policy. A list now falls into the missing-policy bucket
            (fail-closed), not the allowlist branch."
    (is (thrown-with-msg?
          #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core/ExceptionInfo)
          #":rf\.error/ssr-missing-payload-policy"
          (payload-policy/apply-policy
            sample-app-db
            {:payload '(:public/articles)}))
        "a list allowlist fails closed")
    (is (thrown-with-msg?
          #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core/ExceptionInfo)
          #":rf\.error/ssr-missing-payload-policy"
          (payload-policy/apply-policy
            sample-app-db
            {:payload (seq [:public/articles :public/user-id])}))
        "a lazy seq allowlist fails closed too")
    (testing "construction-time arm agrees — a list :payload fails closed"
      (is (thrown-with-msg?
            #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core/ExceptionInfo)
            #":rf\.error/ssr-missing-payload-policy"
            (payload-policy/validate-policy-opts!
              {:on-create [:init] :payload '(:public/articles)}))))))

(deftest apply-policy-set-payload-fails-closed
  (testing "a SET :payload is not a vector → fails closed (the contract
            is a narrow vector allowlist; a set is rejected rather than
            silently accepted)"
    (is (thrown-with-msg?
          #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core/ExceptionInfo)
          #":rf\.error/ssr-missing-payload-policy"
          (payload-policy/apply-policy
            sample-app-db
            {:payload #{:public/articles}})))))

;; ---- apply-policy: malformed allowlist (rf2-hzttr finding 2) -------------

(deftest apply-policy-rejects-string-allowlist-entries
  (testing "rf2-hzttr finding 2 — a non-empty sequential :payload carrying a
            STRING element (the classic typo `[\"public/articles\"]` for the
            keyword `[:public/articles]`) is a malformed allowlist. The
            prior `(and sequential? seq)` check accepted it and `select-keys`
            then shipped an empty/wrong slice silently. It now fails loud
            with `:rf.error/ssr-malformed-payload-allowlist`."
    (is (thrown-with-msg?
          #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core/ExceptionInfo)
          #":rf\.error/ssr-malformed-payload-allowlist"
          (payload-policy/apply-policy
            sample-app-db
            {:payload ["public/articles"]})))
    (is (thrown-with-msg?
          #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core/ExceptionInfo)
          #":rf\.error/ssr-malformed-payload-allowlist"
          (payload-policy/apply-policy
            sample-app-db
            {:payload [:public/articles "public/user-id"]}))
        "a MIXED allowlist (one keyword, one string) is still malformed —
         every entry must be a keyword")))

(deftest apply-policy-rejects-nil-allowlist-entries
  (testing "rf2-hzttr finding 2 — a stray `nil` element is malformed"
    (is (thrown-with-msg?
          #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core/ExceptionInfo)
          #":rf\.error/ssr-malformed-payload-allowlist"
          (payload-policy/apply-policy
            sample-app-db
            {:payload [nil]})))
    (is (thrown-with-msg?
          #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core/ExceptionInfo)
          #":rf\.error/ssr-malformed-payload-allowlist"
          (payload-policy/apply-policy
            sample-app-db
            {:payload [:public/articles nil]})))))

(deftest apply-policy-rejects-nested-allowlist-entries
  (testing "rf2-hzttr finding 2 — a nested coll element is malformed
            (`[[:a :b]]` is not an allowlist of top-level keys)"
    (is (thrown-with-msg?
          #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core/ExceptionInfo)
          #":rf\.error/ssr-malformed-payload-allowlist"
          (payload-policy/apply-policy
            sample-app-db
            {:payload [[:public/articles :public/user-id]]})))))

(deftest malformed-allowlist-error-names-bad-entries
  (testing "rf2-hzttr finding 2 — the structured error carries the offending
            non-keyword entries under `:bad-entries` so the developer can
            see exactly what to fix"
    (try
      (payload-policy/validate-policy-opts!
        {:on-create [:init] :payload [:public/articles "user-id" nil]})
      (is false "should have thrown")
      (catch #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core/ExceptionInfo) e
        (let [data (ex-data e)]
          (is (= :rf.error/ssr-malformed-payload-allowlist
                 (:rf.error/id data)))
          (is (= ["user-id" nil] (:bad-entries data))
              ":bad-entries lists exactly the non-keyword elements")
          (is (= :declare-payload-policy (:recovery data))))))))

(deftest valid-keyword-allowlist-still-accepted
  (testing "rf2-hzttr finding 2 — the tightened validator does NOT regress
            valid all-keyword VECTOR allowlists (rf2-d8vs9x — the vector is
            the one accepted allowlist shape; a list spelling fails closed,
            asserted by apply-policy-list-payload-fails-closed)"
    (let [slice (payload-policy/apply-policy
                  sample-app-db
                  {:payload [:public/articles :public/user-id]})]
      (is (= {:public/articles [:a :b :c] :public/user-id "u-42"} slice)
          "all-keyword vector allowlist accepted"))
    (testing "construction-time arm agrees"
      (let [opts {:on-create [:init] :payload [:public/articles]}]
        (is (= opts (payload-policy/validate-policy-opts! opts)))))))

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

;; ---- runtime-db projection (EP-0001 rf2-30kzz2) --------------------------

(def sample-runtime-db
  {:rf.runtime/machines {:snapshots {:auth.session/abc {:state :authenticated}}}
   :rf.runtime/routing  {:current          {:id :route/home}
                         ;; transient client-local caches — must NOT ride the wire
                         :scroll-positions {"/" {:x 0 :y 240}}
                         :nav-token-counter 7}
   :rf.runtime/elision  {:declarations {[:auth :token] {:sensitive? true}}}
   :rf.runtime/ssr      {:hydration {:server-hash "h1"}}})

(deftest project-runtime-db-ships-durable-omits-transient
  (testing "project-runtime-db ships machines / route :current / elision / ssr
            and drops the transient scroll / nav-token routing caches"
    (let [slice (payload-policy/project-runtime-db sample-runtime-db)]
      (is (= {:snapshots {:auth.session/abc {:state :authenticated}}}
             (:rf.runtime/machines slice))
          "machine snapshots ride the wire whole")
      (is (= {:current {:id :route/home}} (:rf.runtime/routing slice))
          "only the durable :current route slice rides; scroll / nav-token caches are dropped")
      (is (= {:declarations {[:auth :token] {:sensitive? true}}}
             (:rf.runtime/elision slice))
          "elision declarations ride the wire")
      (is (= {:hydration {:server-hash "h1"}} (:rf.runtime/ssr slice))
          "SSR hydration metadata rides the wire")
      (is (not (contains? (:rf.runtime/routing slice) :scroll-positions)))
      (is (not (contains? (:rf.runtime/routing slice) :nav-token-counter))))))

(deftest project-runtime-db-nil-and-empty
  (testing "nil / empty / non-map runtime-db projects to nil so build-payload
            omits the optional :rf/runtime-db key"
    (is (nil? (payload-policy/project-runtime-db nil)))
    (is (nil? (payload-policy/project-runtime-db {})))
    (is (nil? (payload-policy/project-runtime-db "not-a-map")))
    (is (nil? (payload-policy/project-runtime-db {:rf.runtime/routing {:scroll-positions {}}}))
        "a runtime-db with ONLY transient routing keys projects to nil")))

(deftest build-payload-emits-runtime-db-when-present
  (testing "build-payload rides a non-nil :runtime-db opt as :rf/runtime-db,
            and omits the key when nil (client-only / no-server-runtime shape)"
    (let [rt-slice (payload-policy/project-runtime-db sample-runtime-db)
          with-rt  (payload-policy/build-payload
                     :rf/default {:public/page :dashboard} "h1"
                     {:version "1.0" :runtime-db rt-slice})
          without  (payload-policy/build-payload
                     :rf/default {:public/page :dashboard} "h1"
                     {:version "1.0" :runtime-db nil})]
      (is (= rt-slice (:rf/runtime-db with-rt))
          "the projected runtime-db slice rides as :rf/runtime-db")
      (is (= {:public/page :dashboard} (:rf/app-db with-rt)))
      (is (not (contains? without :rf/runtime-db))
          "a nil runtime-db omits the optional key"))))
