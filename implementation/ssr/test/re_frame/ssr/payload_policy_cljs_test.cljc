(ns re-frame.ssr.payload-policy-cljs-test
  "Per-leaf smoke tests for `re-frame.ssr.payload-policy` (rf2-gtgf9,
  rf2-pffil single-opt consolidation).

  Pins the explicit, fail-closed hydration-payload policy contract, now
  carried by a SINGLE `:payload` opt (rf2-pffil folded the prior two-opt
  `:payload-keys` + `:payload-policy` surface into one — pre-alpha, no
  back-compat shim):

    - `apply-policy` returns a `select-keys` slice when the caller
      passes `:payload [<kws>]` (a non-empty SEQUENTIAL of keywords —
      vector / list / lazy-seq → allowlist).
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
  value's SHAPE (sequential collection vs keyword).

  These tests run on both JVM and Node — the policy logic is
  platform-neutral .cljc."
  (:require [clojure.test :refer [deftest is testing]]
            [malli.core :as m]
            [re-frame.late-bind :as late-bind]
            [re-frame.ssr.payload-policy :as payload-policy]))

(def sample-app-db
  ;; Plain app-db keys only — framework durable state lives in the
  ;; runtime-db partition (see `sample-runtime-db` below), not in app-db,
  ;; so this fixture carries no `:rf/*` framework slot. `:app/session`
  ;; is an ordinary app-owned key exercising the drop-unlisted-keys path.
  {:public/articles  [:a :b :c]
   :public/user-id   "u-42"
   :server-only/auth "SECRET_TOKEN"
   :server-only/flag true
   :app/session      {:route {:id :route/home}}})

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

(deftest apply-policy-list-payload-is-a-valid-allowlist
  (testing "a LIST / lazy-seq of keywords IS an accepted allowlist spelling —
            the policy selector is COLLECTION-vs-KEYWORD, not
            vector-vs-keyword. A sequential of keywords can never be confused
            with the :rf.ssr.payload/whole-app-db keyword opt-in, so a
            computed (keep ...) / (filterv ...) / (mapv ...) result (a list
            or lazy-seq) projects the same slice a vector does. Vectors stay
            the documented canonical spelling; the (every? keyword?) element
            guard and the empty-allowlist fail-closed are unchanged."
    (let [slice (payload-policy/apply-policy
                  sample-app-db
                  {:payload '(:public/articles)})]
      (is (= {:public/articles [:a :b :c]} slice)
          "a list allowlist projects the expected slice"))
    (let [slice (payload-policy/apply-policy
                  sample-app-db
                  {:payload (seq [:public/articles :public/user-id])})]
      (is (= {:public/articles [:a :b :c] :public/user-id "u-42"} slice)
          "a lazy seq allowlist projects the expected slice"))
    (testing "construction-time arm agrees — a list :payload validates OK"
      (is (= {:initial-events [[:init]] :payload '(:public/articles)}
             (payload-policy/validate-policy-opts!
               {:initial-events [[:init]] :payload '(:public/articles)}))
          "validate-policy-opts! returns opts unchanged for a list allowlist"))))

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

(deftest apply-policy-rejects-string-list-allowlist-entries
  (testing "the (every? keyword?) footgun-catcher applies to LISTS too — a
            list `'(\"public/articles\")` string-typo is malformed, not
            silently a missing-policy: widening the outer shape to sequential
            must not let a list typo slip into the generic bucket"
    (is (thrown-with-msg?
          #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core/ExceptionInfo)
          #":rf\.error/ssr-malformed-payload-allowlist"
          (payload-policy/apply-policy
            sample-app-db
            {:payload '("public/articles")})))))

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
        {:initial-events [[:init]] :payload [:public/articles "user-id" nil]})
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
      (let [opts {:initial-events [[:init]] :payload [:public/articles]}]
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
    (let [opts {:initial-events [[:init]] :payload [:public/articles]}]
      (is (= opts (payload-policy/validate-policy-opts! opts))
          "returns opts unchanged on success — composes cleanly into
           threading/let positions"))))

(deftest validate-policy-opts-passes-whole-app-db
  (testing "valid :payload whole-app-db keyword passes validation"
    (let [opts {:initial-events [[:init]] :payload :rf.ssr.payload/whole-app-db}]
      (is (= opts (payload-policy/validate-policy-opts! opts))))))

(deftest validate-policy-opts-fails-closed
  (testing "rf2-gtgf9 fail-closed: validation throws on absence —
            handler-construction time arm of the same contract as
            apply-policy"
    (is (thrown-with-msg?
          #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core/ExceptionInfo)
          #":rf\.error/ssr-missing-payload-policy"
          (payload-policy/validate-policy-opts! {:initial-events [[:init]]})))))

(deftest validate-policy-opts-throws-on-unknown-policy
  (testing "construction-time arm also catches typo'd :payload keywords"
    (is (thrown-with-msg?
          #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core/ExceptionInfo)
          #":rf\.error/ssr-unknown-payload-policy"
          (payload-policy/validate-policy-opts!
            {:initial-events [[:init]] :payload :rf.ssr.payload/whole-db})))))

(deftest error-ex-data-carries-recovery-tag
  (testing "rf2-gtgf9: the structured error carries `:recovery
            :declare-payload-policy` so trace tooling can suggest the
            fix — Spec 009 error catalogue convention"
    (try
      (payload-policy/validate-policy-opts! {:initial-events [[:init]]})
      (is false "should have thrown")
      (catch #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core/ExceptionInfo) e
        (is (= :declare-payload-policy
               (:recovery (ex-data e)))
            "error ex-data names the recovery action")))))

;; ---- runtime-db projection (EP-0001 rf2-30kzz2) --------------------------

(def sample-runtime-db
  {:rf.runtime/machines {:snapshots {:auth.session/abc {:state :authenticated}}}
   :rf.runtime/routing  {:current            {:route-id :route/home}
                         ;; :pending-navigation is a local-subscribable
                         ;; runtime-db key but MUST NOT ride the SSR wire
                         ;; (fail-closed allowlist ships only :current).
                         :pending-navigation {:id "pn-1" :reason :can-leave}
                         ;; rf2-oosjmh: the nav-token / pending-nav counters
                         ;; are NOT runtime-db keys any more (host-side
                         ;; transient cache). A stale v1-shaped snapshot might
                         ;; still carry one — the fail-closed allowlist strips
                         ;; it regardless, which this sample proves.
                         :nav-token-counter  7}
   :rf.runtime/elision  {:declarations {[:auth :token] {:sensitive? true}}}
   :rf.runtime/ssr      {:hydration {:server-hash "h1"}}})

(deftest project-runtime-db-ships-durable-omits-transient
  (testing "project-runtime-db ships machines / route :current / elision / ssr
            and drops the non-durable routing keys (:pending-navigation +
            any stale counter)"
    (let [slice (payload-policy/project-runtime-db sample-runtime-db)]
      (is (= {:snapshots {:auth.session/abc {:state :authenticated}}}
             (:rf.runtime/machines slice))
          "machine snapshots ride the wire whole")
      (is (= {:current {:route-id :route/home}} (:rf.runtime/routing slice))
          "only the durable :current route slice rides; :pending-navigation + any counter are dropped")
      (is (= {:declarations {[:auth :token] {:sensitive? true}}}
             (:rf.runtime/elision slice))
          "elision declarations ride the wire")
      (is (= {:hydration {:server-hash "h1"}} (:rf.runtime/ssr slice))
          "SSR hydration metadata rides the wire")
      (is (not (contains? (:rf.runtime/routing slice) :pending-navigation)))
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
                     {:version 1 :runtime-db rt-slice})
          without  (payload-policy/build-payload
                     :rf/default {:public/page :dashboard} "h1"
                     {:version 1 :runtime-db nil})]
      (is (= rt-slice (:rf/runtime-db with-rt))
          "the projected runtime-db slice rides as :rf/runtime-db")
      (is (= {:public/page :dashboard} (:rf/app-db with-rt)))
      (is (not (contains? without :rf/runtime-db))
          "a nil runtime-db omits the optional key"))))

;; ---- :rf/version is canonically an INTEGER (rf2-g00l2t) -------------------
;;
;; Per Spec-Schemas §:rf/hydration-payload `:rf/version` is `:int` (a
;; pattern-protocol version; v1 = 1), EXPLICITLY not a semver-style string.
;; `resolve-version` coerces/validates each version source to an integer:
;; an int is taken verbatim, a whole-number string is parsed, anything else
;; (a semver string, a float, a keyword) is rejected so resolution falls
;; through to the next source — the payload always carries an integer.

(deftest resolve-version-coerces-and-rejects-to-integer
  (testing "explicit integer :version is taken verbatim"
    (is (= 7 (:rf/version
               (payload-policy/build-payload :rf/default {} "h" {:version 7})))))

  (testing "a whole-number STRING :version is tolerantly coerced to an int"
    (is (= 7 (:rf/version
               (payload-policy/build-payload :rf/default {} "h" {:version "7"})))))

  (testing "a SEMVER-string :version is rejected → falls back to the v1 = 1 default"
    (let [v (:rf/version
              (payload-policy/build-payload :rf/default {} "h" {:version "1.0.0"}))]
      (is (= 1 v) "non-integer semver string is rejected, not shipped")
      (is (int? v))))

  (testing "no :version + a SEMVER-string :rf2/runtime-version hook → rejected → v1 = 1 default"
    (late-bind/set-fn! :rf2/runtime-version (constantly "9.9.9"))
    (try
      (let [v (:rf/version (payload-policy/build-payload :rf/default {} "h" {}))]
        (is (= 1 v) "a non-integer hook value is rejected; the integer default wins")
        (is (int? v)))
      (finally (swap! late-bind/hooks dissoc :rf2/runtime-version))))

  (testing "no :version + an INTEGER :rf2/runtime-version hook → hook value wins"
    (late-bind/set-fn! :rf2/runtime-version (constantly 42))
    (try
      (is (= 42 (:rf/version (payload-policy/build-payload :rf/default {} "h" {}))))
      (finally (swap! late-bind/hooks dissoc :rf2/runtime-version))))

  (testing "no source at all → terminal integer v1 = 1 fallback"
    (is (= 1 (:rf/version (payload-policy/build-payload :rf/default {} "h" {}))))))

;; ---- build-payload output conforms to the HydrationPayload schema --------
;;
;; The canonical v1 HydrationPayload schema (Spec-Schemas
;; §:rf/hydration-payload). Pinned inline here so a build-payload output
;; that drifts from the published schema (e.g. a non-integer :rf/version,
;; a missing required key) turns this test red. The schema is an OPEN map
;; (additive optional keys are tolerated per the v1 contract).

(def HydrationPayload
  [:map
   [:rf/version         :int]
   [:rf/frame-id        :keyword]
   [:rf/app-db          :any]
   [:rf/runtime-db      {:optional true} [:maybe :map]]
   [:rf/ssr-rendered-at {:optional true} :int]
   [:rf/render-hash     {:optional true} [:maybe :string]]
   [:rf/schema-digest   {:optional true} [:maybe :string]]])

(deftest build-payload-conforms-to-hydration-payload-schema
  (testing "rf2-g00l2t — build-payload output validates against the canonical
            HydrationPayload schema, with :rf/version as a true integer"
    (let [rt-slice (payload-policy/project-runtime-db sample-runtime-db)]
      (testing "full two-partition payload (integer version)"
        (let [payload (payload-policy/build-payload
                        :rf/default {:public/page :dashboard} "deadbeef"
                        {:version 7 :schema-digest "digest-abc" :runtime-db rt-slice})]
          (is (m/validate HydrationPayload payload)
              (str "payload must conform to HydrationPayload; explain: "
                   (pr-str (m/explain HydrationPayload payload))))
          (is (int? (:rf/version payload)) ":rf/version is a true integer")))

      (testing "a SEMVER-string :version still yields a schema-conforming payload
                (rejected + coerced to the integer default)"
        (let [payload (payload-policy/build-payload
                        :rf/default {:public/page :dashboard} "deadbeef"
                        {:version "1.0.0" :runtime-db rt-slice})]
          (is (m/validate HydrationPayload payload)
              (str "even with a bad :version source, the assembled payload "
                   "conforms (integer fallback); explain: "
                   (pr-str (m/explain HydrationPayload payload))))
          (is (int? (:rf/version payload)))))

      (testing "minimal client-only payload (no runtime-db / schema-digest)"
        (let [payload (payload-policy/build-payload
                        :rf/default {} "h" {})]
          (is (m/validate HydrationPayload payload)
              (str "minimal payload must conform; explain: "
                   (pr-str (m/explain HydrationPayload payload)))))))))
