(ns re-frame.resources-runtime-cljs-test
  "Runtime behaviour for the Resources artefact (rf2-pbxj48, Spec 016
  §EP-0003 slice 4 — the resource RUNTIME).

  These JVM+CLJS unit tests pin the cache-entry runtime this slice
  implements:

    1. canonical params + scope identity (key-order-independent;
       serializable-EDN-only; nil-vs-missing schema-defined);
    2. fail-closed scope policy (no `[:rf.scope/global]` fallthrough;
       missing scope policy → loud error; from-caller required);
    3. the compact lifecycle status transition fn (:idle/:loading/
       :fetching/:loaded/:error + :refresh-error) — a pure fn, NOT a
       spawned machine;
    4. structural sharing (preserve the old :data value when the newly-
       decoded value equals the previous);
    5. durable entries store FACTS, not derived booleans (the booleans
       are computed in subs);
    6. the passive subscriptions (none fetch);
    7. per-frame ISOLATION (resources in frame A invisible to frame B);
    8. stale suppression on the entry (a superseded reply never mutates a
       newer entry);
    9. owner / tag indexes, exact tag invalidation, scope clear, remove.

  HTTP transport execution, the serializable work-ledger records, host
  side tables, abort, GC timers, route/SSR/Xray are LATER slices; this
  test does not exercise them. The transport is decoupled by overriding
  the `:rf.http/managed` fx with a capturing no-op so ensure's entry write
  and reply-handler semantics are tested deterministically without a live
  fetch."
  (:require
   #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
      :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
   [re-frame.core :as rf]
   [re-frame.frame :as frame]
   [re-frame.identity :as identity]
   [re-frame.late-bind :as late-bind]
   [re-frame.registrar :as registrar]
   ;; load-bearing side-effecting require: the façade registers the
   ;; :rf.resource/* events + subs + the generation cofx/fx these tests
   ;; dispatch / subscribe to.
   [re-frame.resources]
   [re-frame.resources.registry :as registry]
   [re-frame.resources.mutation-registry :as mreg]
   [re-frame.resources.state :as state]
   [re-frame.resources.subs :as subs]
   [re-frame.resources.test-support :as resources-test-support]
   [re-frame.resources.timers :as timers]
   [re-frame.resources.work-ledger :as work-ledger]
   [re-frame.resources.revalidate-listeners :as revalidate-listeners]
   ;; production HTTP fx surface (so the transport feature probe resolves);
   ;; the actual fetch is overridden by the capturing no-op below.
   [re-frame.http.managed]
   [re-frame.schemas]
   [re-frame.test-support :as core-test-support]
   [re-frame.trace.tooling :as trace-tooling]
   #?@(:clj  [[re-frame.substrate.plain-atom :as plain-atom]]
       :cljs [[re-frame.adapter.reagent :as reagent-adapter]])))

;; ---- capturing transport (decouples entry-state tests from HTTP) ----------

(def ^:private last-managed-args (atom nil))

(defn- capturing-transport-fixture
  "Override the real :rf.http/managed fx with a capturing no-op so ensure's
  :loading entry write + lower-fx are deterministic and no real fetch fires.
  Composed INSIDE the reset-runtime fixture (one `use-fixtures` call — a
  second `use-fixtures :each` would REPLACE, not accumulate)."
  [f]
  (reset! last-managed-args nil)
  ;; rf2-yuc8o0: the shared `make-reset-runtime-fixture` reset-hook-table now
  ;; fires `:resources/reset-resources!` (which clears the host-side
  ;; generation high-water marks) in its `:post-dispose` phase before each
  ;; test body, so no per-suite generation-cache reset is needed here.
  ;; fx handlers are BINARY `(fn [ctx args] …)` (Spec 002 §binary
  ;; fx-handler signature) — capture the args (second arg).
  (rf/reg-fx :rf.http/managed (fn [_ctx args] (reset! last-managed-args args) nil))
  (f))

(use-fixtures :each
  (core-test-support/make-reset-runtime-fixture
    #?(:clj  {:adapter plain-atom/adapter}
       :cljs {:adapter reagent-adapter/adapter}))
  capturing-transport-fixture)

;; ---- helpers --------------------------------------------------------------

(defn- runtime-db
  ([] (runtime-db :rf/default))
  ([frame-id] (:rf.db/runtime (rf/frame-state-value frame-id))))

(defn- entry
  "The durable cache entry for a scoped key in a frame (default
  :rf/default)."
  ([scoped-key] (entry :rf/default scoped-key))
  ([frame-id scoped-key]
   (get-in (runtime-db frame-id) (state/entry-path scoped-key))))

(defn- article-spec
  "A minimal valid resource spec (global scope, a slug param)."
  ([] (article-spec {}))
  ([overrides]
   (merge {:scope         :rf.scope/global
           :params-schema [:map [:slug :string]]
           :tags          (fn [{:keys [slug]} _data] #{[:article slug]})}
          overrides)))

(def ^:private article-spec-request
  (fn [{:keys [slug]} _ctx]
    {:request {:method :get :url (str "/api/articles/" slug)}}))

;; ===========================================================================
;; 1. Canonical params + scope identity
;; ===========================================================================

(deftest canonicalization-is-key-order-independent
  (testing "scoped key is identical regardless of map key order (Spec 016
            §Canonicalization rule)"
    (let [k1 (state/scoped-resource-key {:tenant "acme" :user "u-42"}
                                        :article/by-slug {:slug "x" :rev 1})
          k2 (state/scoped-resource-key {:user "u-42" :tenant "acme"}
                                        :article/by-slug {:rev 1 :slug "x"})]
      (is (= k1 k2) "two spellings of the same scope + params collapse to one key")))
  (testing "nested maps recurse; sets / vectors keep value semantics"
    (is (= (state/canonicalize {:a {:c 3 :b 2} :z #{2 1}})
           (state/canonicalize {:z #{1 2} :a {:b 2 :c 3}})))))

(deftest host-values-rejected-at-the-cache-key-boundary
  (testing "a host / opaque param value is rejected loudly (Spec 016
            §Resource identity)"
    (is (false? (state/serializable-edn? {:f (fn [])})))
    (is (thrown-with-msg?
          #?(:clj Throwable :cljs js/Error) #"resource-non-edn-params"
          (state/reject-non-edn! {:f (fn [])} 'test :params :r/x)))))

;; rf2-rplgkw — the redundant scope/params re-canonicalization on the resource
;; sub re-key hot path is collapsed: a trusted `scoped-resource-key*` for
;; already-canonical inputs (the sub / event / route resolution paths feed it
;; values that already passed canonicalize-scope + validate+canonicalize-params)
;; and a single-walk `canonicalize-or-rethrow` folding the reject-non-edn! +
;; canonicalize pair inside canonicalize-scope. Behaviour MUST be preserved.
(deftest trusted-scoped-key-matches-defensive-on-canonical-input
  (testing "scoped-resource-key* (no re-canonicalize) yields the SAME key as
            the defensive scoped-resource-key when handed ALREADY-canonical
            scope + params — the dedup is behaviour-preserving"
    (let [cscope  (state/canonicalize {:tenant "acme" :user "u-42"})
          cparams (state/canonicalize {:slug "x" :rev 1})
          trusted (state/scoped-resource-key* cscope :article/by-slug cparams)
          defens  (state/scoped-resource-key cscope :article/by-slug cparams)]
      (is (= defens trusted) "same key vector")
      (is (= (state/key-id defens) (state/key-id trusted)) "same byte key-id")))
  (testing "the defensive constructor still canonicalizes RAW (key-order-
            varying) input to the same identity scoped-resource-key* produces
            from the canonical value — so RAW direct/test/mutation callers keep
            working"
    (let [raw-a (state/scoped-resource-key {:user "u-42" :tenant "acme"}
                                           :article/by-slug {:rev 1 :slug "x"})
          raw-b (state/scoped-resource-key {:tenant "acme" :user "u-42"}
                                           :article/by-slug {:slug "x" :rev 1})]
      (is (= raw-a raw-b) "key-order spellings collapse via the defensive path")
      (is (= raw-a (state/scoped-resource-key*
                     (state/canonicalize {:tenant "acme" :user "u-42"})
                     :article/by-slug
                     (state/canonicalize {:rev 1 :slug "x"})))
          "the trusted path on the canonical value matches the defensive path"))))

(deftest canonicalize-or-rethrow-folds-validate-and-canonicalize
  (testing "on conforming input it returns the SAME value as canonicalize"
    (is (= (state/canonicalize {:b 2 :a 1})
           (state/canonicalize-or-rethrow {:a 1 :b 2} 'test :scope :r/x))
        "canonical result is identical to the two-step (reject + canonicalize)")
    (is (= :rf.scope/global
           (state/canonicalize-or-rethrow :rf.scope/global 'test :scope :r/x))
        "the bare global scope keyword canonicalizes to itself"))
  (testing "a host / opaque value re-throws the SAME public
            :rf.error/resource-non-edn-params category reject-non-edn! threw"
    (is (thrown-with-msg?
          #?(:clj Throwable :cljs js/Error) #"resource-non-edn-params"
          (state/canonicalize-or-rethrow {:f (fn [])} 'test :scope :r/x)))
    (is (thrown-with-msg?
          #?(:clj Throwable :cljs js/Error) #"resource-non-edn-params"
          (state/canonicalize-or-rethrow {:ratio 1.5} 'test :params :r/x)))))

;; rf2-wgutc2 (EP-0012 correctness review item 1): resource params + scopes
;; use the SHARED CEDN-1 identity rule (`re-frame.identity/canonical`), not a
;; resource-local dialect. This closes three divergences the prior
;; resource-local canonicalizer carried:
;;   - it accepted broad `number?` (floats / ratios / decimals / out-of-safe-
;;     range integers) — CEDN-1 admits only portable safe-range integers;
;;   - it collapsed lists → vectors, erasing the list-vs-vector EDN distinction;
;;   - it sorted keys under a bespoke comparator the CEDN-1 byte order subsumes.
(deftest resource-identity-uses-shared-cedn1-rule
  (testing "non-portable NUMBERS are rejected at the cache-key boundary
            (CEDN-1 admits only portable safe-range integers)"
    ;; floats / doubles
    (is (false? (state/serializable-edn? {:ratio 1.5}))
        "a float param is not a portable CEDN-1 identity")
    (is (thrown-with-msg?
          #?(:clj Throwable :cljs js/Error) #"resource-non-edn-params"
          (state/reject-non-edn! {:ratio 1.5} 'test :params :r/x))
        "a float param is rejected loudly")
    ;; out-of-safe-range integer (> 2^53 - 1) — diverges across hosts, so
    ;; CEDN-1 fails it closed.
    (is (false? (state/serializable-edn? {:n 9007199254740993}))
        "an integer beyond the ECMAScript safe range is rejected")
    #?(:clj
       ;; ratios + bigdecimals only exist on the JVM
       (do
         (is (false? (state/serializable-edn? {:r 1/3}))
             "a ratio param is rejected")
         (is (false? (state/serializable-edn? {:d 1.5M}))
             "a bigdecimal param is rejected"))))
  (testing "a plain safe-range integer param is STILL accepted (the common case)"
    (is (true? (state/serializable-edn? {:rev 1 :page 42})))
    (is (= {:rev 1 :page 42} (state/canonicalize {:page 42 :rev 1}))))
  (testing "list vs vector are DISTINCT EDN facts — never collapsed
            (Conventions §Sequences and sets)"
    ;; The prior resource-local canonicalize ACTIVELY COERCED lists to vectors
    ;; (`(sequential? x) (mapv …)`), erasing the kind entirely — a vector and a
    ;; list params value became byte-identical, silently sharing one cache
    ;; entry. CEDN-1 PRESERVES the kind, so the two stay distinct EDN identities.
    ;;
    ;; Note: Clojure value `=` does NOT discriminate a vector from a list
    ;; (`(= [1 2 3] '(1 2 3))` is true), so the distinction is at the
    ;; AUTHORITATIVE CEDN-1 identity level (EP-0012 disposition 5: the canonical
    ;; EDN value IS the identity, and equality-as-identity is CEDN-1 byte
    ;; equality, `identity/identical-identity?`) — the vector encodes `v[…]`
    ;; and the list encodes `l(…)`, distinct token streams.
    (is (not (identity/identical-identity?
               (state/canonicalize {:xs [1 2 3]})
               (state/canonicalize {:xs '(1 2 3)})))
        "a vector value and a list value canonicalize to DISTINCT CEDN-1 identities")
    (is (not (identity/identical-identity?
               (state/scoped-resource-key :rf.scope/global :r/x {:xs [1 2 3]})
               (state/scoped-resource-key :rf.scope/global :r/x {:xs '(1 2 3)})))
        "the scoped keys are distinct identities — list and vector params are
         not the same cache fact")
    (is (vector? (:xs (state/canonicalize {:xs [1 2 3]})))
        "a vector value stays a vector")
    (is (seq? (:xs (state/canonicalize {:xs '(1 2 3)})))
        "a list value stays a list (not coerced to a vector — the prior
         resource-local canonicalizer's silent collapse is gone)"))
  (testing "key-order independence is preserved through the shared rule"
    (is (= (state/canonicalize {:a 1 :b 2})
           (state/canonicalize {:b 2 :a 1})))))

;; ===========================================================================
;; rf2-du585y — resource :params present-nil vs missing boundary
;; ===========================================================================
;;
;; EP-0012 §Missing vs present nil: an absent key differs from a key present
;; with value `nil`, and canonical identity preserves the distinction —
;; `(canonical {})` is NOT `(canonical {:k nil})`. Spec 016: nil-vs-missing is
;; SCHEMA-DEFINED, not accidental — `validate+canonicalize-params` defers to
;; the `:params-schema`, it does not impose its own nil policy. These pin that
;; a present-nil param value is preserved through to the cache key (distinct
;; identity from the absent case) under a schema that accepts nil, and is
;; rejected with nil reported under a schema that rejects nil.

(defn- accepts-nil-spec []
  {:scope         :rf.scope/global
   ;; :x is optional and may be present-with-nil
   :params-schema [:map [:x {:optional true} [:maybe :string]]]
   :request       (fn [_ _ctx] {:request {:method :get :url "/x"}})})

(deftest resource-present-nil-param-distinct-from-missing
  (testing "under a schema that ACCEPTS nil, an explicit {:x nil} param is
            preserved (NOT coerced to {}) and produces a DISTINCT canonical
            identity from the absent-key case (EP-0012 §Missing vs present nil)"
    (let [spec (accepts-nil-spec)
          ;; explicit present-nil value vs the key absent entirely
          present-nil (registry/validate+canonicalize-params :r/x spec {:x nil} 'test)
          absent      (registry/validate+canonicalize-params :r/x spec {} 'test)]
      (is (= {:x nil} present-nil)
          "the present-nil param survives validation/canonicalization — not dropped to {}")
      (is (= {} absent) "the absent-key case canonicalizes to the empty map")
      (is (not (identity/identical-identity? present-nil absent))
          "present-nil and absent are DISTINCT canonical identities")
      ;; and therefore distinct cache keys
      (is (not (identity/identical-identity?
                 (state/scoped-resource-key :rf.scope/global :r/x present-nil)
                 (state/scoped-resource-key :rf.scope/global :r/x absent)))
          "the scoped resource keys differ — present-nil is its own cache fact"))))

;; rf2-hgy5kf (EP-0012) — the WHOLE-slot case the prior `(or params {})` at the
;; validation boundary silently collapsed: a PAYLOAD with `:params` PRESENT and
;; explicitly `nil` (`{:params nil}`) vs `:params` ABSENT (`{}`). Spec 016:70 +
;; EP-0012:1316 say present-nil and absent are DISTINCT unless explicitly
;; elided; the schema (not a blanket boundary default) decides whether the
;; whole-slot nil conforms. `state/params-present?` threads the presence, and
;; `state/default-omitted-params` applies the omitted-→`{}` policy ONLY to an
;; absent slot — an explicit whole-slot nil reaches the schema unchanged.

(deftest resource-explicit-nil-params-slot-distinct-from-omitted
  (testing "the presence helper distinguishes an explicit nil :params slot from
            an absent one (the distinction the old (or params {}) destroyed)"
    (is (= nil (state/params-present? {:params nil}))
        "a PRESENT explicit nil slot threads nil")
    (is (= state/missing-params (state/params-present? {}))
        "an ABSENT slot threads the missing sentinel")
    (is (= {} (state/default-omitted-params (state/params-present? {})))
        "an absent slot defaults to {} at the boundary")
    (is (= nil (state/default-omitted-params (state/params-present? {:params nil})))
        "an explicit nil slot passes THROUGH the default unchanged (not {})"))
  (testing "under a schema that ACCEPTS a nil params value ([:maybe :map]), an
            explicit whole-slot nil is preserved to the cache key and is a
            DISTINCT canonical identity from the omitted-→{} case"
    (let [spec {:scope         :rf.scope/global
                :params-schema [:maybe :map]   ;; the whole params value may be nil
                :request       (fn [_ _ctx] {:request {:method :get :url "/x"}})}
          explicit-nil (registry/validate+canonicalize-params
                         :r/x spec (state/params-present? {:params nil}) 'test)
          omitted      (registry/validate+canonicalize-params
                         :r/x spec (state/params-present? {}) 'test)]
      (is (nil? explicit-nil)
          "explicit whole-slot nil survives validation — NOT coerced to {}")
      (is (= {} omitted) "the omitted slot defaults to {}")
      (is (not (identity/identical-identity? explicit-nil omitted))
          "explicit-nil and omitted are DISTINCT canonical identities")
      (is (not (identity/identical-identity?
                 (state/scoped-resource-key :rf.scope/global :r/x explicit-nil)
                 (state/scoped-resource-key :rf.scope/global :r/x omitted)))
          "the scoped resource keys differ — explicit-nil params is its own fact"))))

(deftest resource-explicit-nil-params-slot-rejected-when-schema-rejects-nil
  (testing "rf2-hgy5kf — under a schema that REJECTS a nil params value
            ([:map], a non-nilable map), an explicit whole-slot {:params nil}
            fails closed with :rf.error/resource-invalid-params and the
            structured error reports the offending nil (NOT a coerced {})"
    (let [spec {:scope         :rf.scope/global
                :params-schema [:map [:slug :string]]   ;; requires a map, rejects nil
                :request       (fn [_ _] {:request {:method :get :url "/x"}})}
          ex   (try (registry/validate+canonicalize-params
                      :r/x spec (state/params-present? {:params nil}) 'test)
                    nil
                    (catch #?(:clj clojure.lang.ExceptionInfo :cljs ExceptionInfo) e e))]
      (is (some? ex)
          "an explicit whole-slot nil against a non-nilable schema throws — NOT
           silently coerced to {} (which would have PASSED a [:map] schema)")
      (is (= :rf.error/resource-invalid-params (:rf.error/id (ex-data ex))))
      (is (nil? (:params (ex-data ex)))
          "the reported params preserve the offending nil (not coerced away)"))))

(deftest mutation-explicit-nil-params-slot-rejected-when-schema-rejects-nil
  (testing "rf2-hgy5kf — the mutation registry applies the SAME presence-aware
            policy: an explicit whole-slot {:params nil} reaches a non-nilable
            :params-schema and fails closed (not coerced to {})"
    (let [spec {:scope         :rf.scope/global
                :params-schema [:map [:slug :string]]
                :request       (fn [_ _] {:request {:method :post :url "/x"}})}
          ex   (try (mreg/validate+canonicalize-params
                      :m/x spec (state/params-present? {:params nil}) 'test)
                    nil
                    (catch #?(:clj clojure.lang.ExceptionInfo :cljs ExceptionInfo) e e))]
      (is (some? ex) "an explicit nil mutation params slot against [:map] throws")
      (is (= :rf.error/mutation-invalid-params (:rf.error/id (ex-data ex))))
      (is (nil? (:params (ex-data ex)))
          "the reported params preserve the offending nil"))
    (testing "an OMITTED mutation params slot still defaults to {} (API policy)"
      (let [spec {:scope :rf.scope/global :params-schema [:map]
                  :request (fn [_ _] {:request {:method :post :url "/x"}})}]
        (is (= {} (mreg/validate+canonicalize-params
                    :m/x spec (state/params-present? {}) 'test)))))))

(deftest resource-present-nil-rejected-when-schema-rejects-nil
  (testing "under a schema that REJECTS nil ([:map [:x :string]]), an explicit
            {:x nil} fails closed with :rf.error/resource-invalid-params and
            the structured error data reports the offending params (rf2-du585y)"
    (let [spec {:scope         :rf.scope/global
                :params-schema [:map [:x :string]]   ;; :x is required + non-nil
                :request       (fn [_ _] {:request {:method :get :url "/x"}})}
          ex   (try (registry/validate+canonicalize-params :r/x spec {:x nil} 'test)
                    nil
                    (catch #?(:clj clojure.lang.ExceptionInfo :cljs ExceptionInfo) e e))]
      (is (some? ex) "a nil value against a non-nil schema throws")
      (is (= :rf.error/resource-invalid-params (:rf.error/id (ex-data ex)))
          "structured :rf.error/id (Spec 009 discriminator)")
      ;; the structured error data must carry the offending params (with the nil)
      (let [data (ex-data ex)]
        (is (= :r/x (:resource-id data)))
        (is (contains? (:params data) :x)
            "the params slot reports the offending map, nil value included")
        (is (nil? (get (:params data) :x))
            "the reported params preserve the nil value (not coerced away)")))))

;; rf2-9e0tyq (full fix; was the rf2-o84qq2 deferred-edge pin): the scoped
;; resource key carries a kind-PRESERVING canonical VECTOR `[scope rid params]`
;; (a list value stays a list, distinct from a vector — rf2-wgutc2), but it is
;; NO LONGER used directly as a Clojure map key. The `:entries` map, the
;; reverse indexes, and the work-ledger map are keyed on the CEDN-1 byte
;; `key-id` (`state/key-id` / `work-ledger/work-id-id`), so the map-key
;; comparison is EXACTLY the CEDN-1 byte identity. Clojure `=` collapses
;; `(= [1 2 3] '(1 2 3))` to TRUE, but two distinct `canonical-bytes` strings
;; never collapse — so a list-params key and a vector-params key get DISTINCT
;; entries AND distinct work-ids. This flips the former rf2-o84qq2 pin from
;; documenting-the-collapse to asserting-distinctness (the bead's acceptance
;; criterion). The fix is NOT a list→vector normalize (that would re-erase the
;; CEDN-1 kind distinction rf2-wgutc2 introduced — the test above).
(deftest list-vs-vector-params-get-distinct-entries-rf2-9e0tyq
  (testing "list-vs-vector params keys carry DISTINCT CEDN-1 identities..."
    (let [kv (state/scoped-resource-key :rf.scope/global :r/x {:xs [1 2 3]})
          kl (state/scoped-resource-key :rf.scope/global :r/x {:xs '(1 2 3)})]
      (is (not (identity/identical-identity? kv kl))
          "the authoritative identities differ (v[...] vs l(...))")
      ;; Clojure `=` still treats the VECTORS as equal — but the cache no longer
      ;; keys on the vector; it keys on the byte `key-id`, which is DISTINCT.
      (is (= kv kl)
          "Clojure value = treats the vector- and list-params VECTORS as equal …")
      (testing "...and now resolve to DISTINCT :entries entries (the byte key-id
                comparison is exactly the CEDN-1 identity — the fix)"
        (is (not= (state/key-id kv) (state/key-id kl))
            "their byte key-ids differ (v[…] vs l(…)) — the map-key identity")
        (is (= 2 (count (-> {}
                            (assoc (state/key-id kv) :v)
                            (assoc (state/key-id kl) :l))))
            "both keys map to TWO distinct entries — the =-collapse is closed")
        (is (= :v (get (-> {}
                           (assoc (state/key-id kv) :v)
                           (assoc (state/key-id kl) :l))
                       (state/key-id kv)))
            "the vector-params entry is NOT overwritten by the list-params write"))
      ;; carrier 2 — the embedded work-id is keyed on its OWN byte id too, so
      ;; the two work-ledger slots stay distinct (stale suppression keys on the
      ;; entry's :current-work + the entry lookup, both byte-disambiguated).
      (testing "...and the embedded work-id keys on its own byte id (carrier 2)"
        (let [wv (work-ledger/resource-work-id kv 1)
              wl (work-ledger/resource-work-id kl 1)]
          (is (= wv wl) "the work-id VECTORS are still `=` (they embed the key vector) …")
          (is (not= (work-ledger/work-id-id wv) (work-ledger/work-id-id wl))
              "…but their byte work-id-ids differ — distinct ledger slots")
          (is (= 2 (count (-> {}
                              (assoc (work-ledger/work-id-id wv) :v)
                              (assoc (work-ledger/work-id-id wl) :l))))
              "both work-ids map to TWO distinct work-ledger slots")))))
  ;; The other CEDN-distinct params kinds were already `=`-distinct and stay
  ;; keyed correctly (the fix never regresses them).
  (testing "every other CEDN-distinct params kind also keys distinctly"
    (let [k (fn [params] (state/key-id (state/scoped-resource-key :rf.scope/global :r/x params)))]
      (is (= 3 (count (-> {} (assoc (k {:v "1"}) :s) (assoc (k {:v 1}) :i) (assoc (k {:v :a}) :k))))
          "string / integer / keyword params keep distinct cache entries")
      (is (= 2 (count (-> {} (assoc (k {:v #{1 2}}) :set) (assoc (k {:v [1 2]}) :vec))))
          "set-params and vector-params keep distinct cache entries")
      (is (= 1 (count (-> {} (assoc (k {:a 1 :b 2}) :ab) (assoc (k {:b 2 :a 1}) :ba))))
          "key-order-only-different maps are ONE entry (canonical, not the edge)"))))

;; ===========================================================================
;; 2. Fail-closed scope policy (no global fallthrough)
;; ===========================================================================

(deftest scope-resolution-fail-closed
  (rf/reg-resource :sr/from-caller (article-spec {:scope :rf.scope/from-caller}) article-spec-request)
  (testing "from-caller with no payload scope is a loud use-time error
            (Spec 016 §Scope resolution — no global fallthrough)"
    (is (thrown-with-msg?
          #?(:clj Throwable :cljs js/Error) #"resource-scope-required-from-caller"
          (registry/resolve-scope-for-event
            :sr/from-caller (registry/resource-meta :sr/from-caller)
            {:payload-scope nil} 'test))))
  (testing "from-caller WITH a payload scope resolves to that scope"
    (is (= {:user "u-1"}
           (registry/resolve-scope-for-event
             :sr/from-caller (registry/resource-meta :sr/from-caller)
             {:payload-scope {:user "u-1"}} 'test))))
  (testing "an explicit :rf.scope/global policy resolves to global (its
            declared policy, not a fallthrough)"
    (rf/reg-resource :sr/global (article-spec) article-spec-request)
    (is (= :rf.scope/global
           (registry/resolve-scope-for-event
             :sr/global (registry/resource-meta :sr/global) {} 'test)))))

(deftest sub-side-scope-fail-closed
  (rf/reg-resource :ss/from-caller (article-spec {:scope :rf.scope/from-caller}) article-spec-request)
  (testing "a sub that cannot resolve a scope raises
            :rf.error/resource-sub-unresolved-scope (never a silent global
            read / :idle) — Spec 016 §Subscription-side scope resolution"
    (is (thrown-with-msg?
          #?(:clj Throwable :cljs js/Error) #"resource-sub-unresolved-scope"
          (subs/resolve-scoped-key {:resource :ss/from-caller :params {:slug "x"}} {})))))

;; ===========================================================================
;; 3. Pure lifecycle status transition fn (NOT a spawned machine)
;; ===========================================================================

(deftest status-transition-fn-is-pure
  (testing "Spec 016 §Lifecycle is an FSM — a pure transition fn over the
            five states"
    (is (= :loading  (state/next-status :idle    :start-load false)))
    (is (= :fetching (state/next-status :loaded  :start-load true)))
    (is (= :loaded   (state/next-status :loading :success   false)))
    (is (= :error    (state/next-status :loading :failure   false)))
    ;; background-refresh failure returns to :loaded (data kept)
    (is (= :loaded   (state/next-status :fetching :failure  true)))
    (is (= :loading  (state/next-status :error    :start-load false)))))

;; ===========================================================================
;; 4. ensure → :loading → succeeded → :loaded  +  structural sharing
;; ===========================================================================

(deftest ensure-then-success-loaded
  (rf/reg-resource :a/article (article-spec) article-spec-request)
  (let [scoped-key (state/scoped-resource-key :rf.scope/global :a/article {:slug "welcome"})]
    (rf/dispatch-sync [:rf.resource/ensure
                       {:resource :a/article :scope :rf.scope/global
                        :params {:slug "welcome"} :owner [:lease :test 1]}])
    (testing "ensure transitions the (no-data) entry to :loading and mints
              a generation + current-work pointer"
      (let [e (entry scoped-key)]
        (is (= :loading (:status e)))
        (is (= 1 (:generation e)))
        (is (some? (:current-work e)))
        (is (contains? (:active-owners e) [:lease :test 1]))))
    (testing "the transport was lowered (the capturing :rf.http/managed fx
              saw the runtime-owned request-id + reply addressing)"
      (is (some? @last-managed-args))
      (is (= [:rf.resource.internal/succeeded] (subvec (:on-success @last-managed-args) 0 1)))
      (is (some? (:request-id @last-managed-args))))
    ;; simulate the transport reply (the managed-HTTP slice will dispatch
    ;; this for real; here we feed the internal reply directly)
    (let [work-id (:current-work (entry scoped-key))]
      (rf/dispatch-sync [:rf.resource.internal/succeeded
                         {:resource/key scoped-key :work/id work-id :generation 1
                          :data {:title "Welcome"}}])
      (testing "succeeded settles :loaded with the decoded data + produced tags"
        (let [e (entry scoped-key)]
          (is (= :loaded (:status e)))
          (is (= {:title "Welcome"} (:data e)))
          (is (nil? (:current-work e)))
          (is (= #{[:article "welcome"]} (:tags e)))))
      (testing "durable entry stores FACTS, not derived booleans (Spec 016
                §Status semantics)"
        (let [e (entry scoped-key)]
          (is (not (contains? e :loading?)))
          (is (not (contains? e :stale?)))
          (is (not (contains? e :has-data?))))))))

(deftest structural-sharing-preserves-identical-data
  (rf/reg-resource :ss/article (article-spec) article-spec-request)
  (let [scoped-key (state/scoped-resource-key :rf.scope/global :ss/article {:slug "w"})
        data1      {:title "Welcome" :body [1 2 3]}]
    (rf/dispatch-sync [:rf.resource/ensure {:resource :ss/article :scope :rf.scope/global
                                            :params {:slug "w"} :owner [:lease :ss 1]}])
    (let [wid1 (:current-work (entry scoped-key))]
      (rf/dispatch-sync [:rf.resource.internal/succeeded
                         {:resource/key scoped-key :work/id wid1 :generation 1 :data data1}]))
    (let [first-data (:data (entry scoped-key))]
      ;; a refetch returns an EQUAL but freshly-constructed value
      (rf/dispatch-sync [:rf.resource/refetch {:resource :ss/article :scope :rf.scope/global
                                               :params {:slug "w"}}])
      (let [wid2 (:current-work (entry scoped-key))]
        (rf/dispatch-sync [:rf.resource.internal/succeeded
                           {:resource/key scoped-key :work/id wid2 :generation 2
                            :data {:title "Welcome" :body [1 2 3]}}]))
      (testing "Spec 016 §Structural sharing — the old :data value is
                preserved (identity) when the newly-decoded value is ="
        (is (identical? first-data (:data (entry scoped-key)))
            "equal new data keeps the OLD value identity (quiet downstream)")))))

;; ===========================================================================
;; 5. background-refresh failure keeps prior data (:refresh-error)
;; ===========================================================================

(deftest refresh-failure-keeps-data
  (rf/reg-resource :rf2/article (article-spec) article-spec-request)
  (let [scoped-key (state/scoped-resource-key :rf.scope/global :rf2/article {:slug "w"})]
    (rf/dispatch-sync [:rf.resource/ensure {:resource :rf2/article :scope :rf.scope/global
                                            :params {:slug "w"} :owner [:lease :rf2 1]}])
    (let [wid1 (:current-work (entry scoped-key))]
      (rf/dispatch-sync [:rf.resource.internal/succeeded
                         {:resource/key scoped-key :work/id wid1 :generation 1
                          :data {:title "Welcome"}}]))
    (rf/dispatch-sync [:rf.resource/refetch {:resource :rf2/article :scope :rf.scope/global
                                             :params {:slug "w"}}])
    (testing "refetch with existing data → :fetching (prior data visible)"
      (is (= :fetching (:status (entry scoped-key))))
      (is (= {:title "Welcome"} (:data (entry scoped-key)))))
    (let [wid2 (:current-work (entry scoped-key))]
      (rf/dispatch-sync [:rf.resource.internal/failed
                         {:resource/key scoped-key :work/id wid2 :generation 2
                          :error {:kind :rf.http/http-5xx :status 503}}]))
    (testing "Spec 016 §Status semantics — a background-refresh failure
              returns to :loaded, keeps prior :data, records :refresh-error"
      (let [e (entry scoped-key)]
        (is (= :loaded (:status e)))
        (is (= {:title "Welcome"} (:data e)))
        (is (= {:kind :rf.http/http-5xx :status 503} (:refresh-error e)))
        (is (nil? (:error e)))))))

(deftest first-load-failure-error
  (rf/reg-resource :fl/article (article-spec) article-spec-request)
  (let [scoped-key (state/scoped-resource-key :rf.scope/global :fl/article {:slug "w"})]
    (rf/dispatch-sync [:rf.resource/ensure {:resource :fl/article :scope :rf.scope/global
                                            :params {:slug "w"} :owner [:lease :fl 1]}])
    (let [wid (:current-work (entry scoped-key))]
      (rf/dispatch-sync [:rf.resource.internal/failed
                         {:resource/key scoped-key :work/id wid :generation 1
                          :error {:kind :rf.http/http-5xx :status 503}}]))
    (testing "first-load failure → :error, no usable data"
      (let [e (entry scoped-key)]
        (is (= :error (:status e)))
        (is (nil? (:data e)))
        (is (= {:kind :rf.http/http-5xx :status 503} (:error e)))))))

;; ===========================================================================
;; 6. Stale suppression on the entry (superseded reply never mutates)
;; ===========================================================================

(deftest stale-reply-is-suppressed
  (rf/reg-resource :st/article (article-spec) article-spec-request)
  (let [scoped-key (state/scoped-resource-key :rf.scope/global :st/article {:slug "w"})]
    (rf/dispatch-sync [:rf.resource/ensure {:resource :st/article :scope :rf.scope/global
                                            :params {:slug "w"} :owner [:lease :st 1]}])
    (let [wid1 (:current-work (entry scoped-key))]
      ;; a newer refetch supersedes (generation 2)
      (rf/dispatch-sync [:rf.resource/refetch {:resource :st/article :scope :rf.scope/global
                                               :params {:slug "w"}}])
      (is (= 2 (:generation (entry scoped-key))))
      (testing "Spec 016 §stale suppression — a late reply carrying the OLD
                generation/work-id is suppressed (never mutates the newer
                entry)"
        (rf/dispatch-sync [:rf.resource.internal/succeeded
                           {:resource/key scoped-key :work/id wid1 :generation 1
                            :data {:stale "data"}}])
        (let [e (entry scoped-key)]
          (is (not= {:stale "data"} (:data e)) "stale reply did not write")
          (is (= 2 (:generation e)) "entry generation unchanged by the stale reply")
          (is (= :loading (:status e)) "entry still in flight on its current gen"))))))

;; ===========================================================================
;; 7. ensure dedupe (join in-flight)
;; ===========================================================================

(deftest ensure-dedupes-in-flight
  (rf/reg-resource :dd/article (article-spec) article-spec-request)
  (let [scoped-key (state/scoped-resource-key :rf.scope/global :dd/article {:slug "w"})]
    (rf/dispatch-sync [:rf.resource/ensure {:resource :dd/article :scope :rf.scope/global
                                            :params {:slug "w"} :owner [:route :r 1]}])
    (let [gen1 (:generation (entry scoped-key))]
      (rf/dispatch-sync [:rf.resource/ensure {:resource :dd/article :scope :rf.scope/global
                                              :params {:slug "w"} :owner [:lease :x 2]}])
      (testing "Spec 016 §Race — a second ensure while in flight JOINS (no
                new generation), attaching the new owner"
        (let [e (entry scoped-key)]
          (is (= gen1 (:generation e)) "no new generation on dedupe")
          (is (contains? (:active-owners e) [:route :r 1]))
          (is (contains? (:active-owners e) [:lease :x 2])))))))

;; ===========================================================================
;; 8. Per-frame ISOLATION (frame A invisible to frame B)
;; ===========================================================================

(deftest per-frame-isolation
  (rf/reg-resource :iso/article (article-spec) article-spec-request)
  (let [fa :iso/frame-a
        fb :iso/frame-b
        scoped-key (state/scoped-resource-key :rf.scope/global :iso/article {:slug "w"})]
    (rf/reg-frame fa {:doc "isolation frame A"})
    (rf/reg-frame fb {:doc "isolation frame B"})
    (testing "Spec 016 — resources are per-frame isolated; a resource
              loaded in frame A is invisible in frame B"
      (rf/dispatch-sync [:rf.resource/ensure
                         {:resource :iso/article :scope :rf.scope/global
                          :params {:slug "w"} :owner [:lease :a 1]}]
                        {:frame fa})
      (let [wid (:current-work (entry fa scoped-key))]
        (rf/dispatch-sync [:rf.resource.internal/succeeded
                           {:resource/key scoped-key :work/id wid :generation 1
                            :data {:title "A"}}]
                          {:frame fa}))
      (is (= {:title "A"} (:data (entry fa scoped-key))) "frame A has the entry")
      (is (nil? (entry fb scoped-key)) "frame B has NO entry (isolated)"))
    (frame/destroy-frame! fa)
    (frame/destroy-frame! fb)))

;; ===========================================================================
;; 9. Passive subscriptions (none fetch)
;; ===========================================================================

(deftest passive-subs-project-the-entry
  (rf/reg-resource :sub/article (article-spec) article-spec-request)
  (let [scoped-key (state/scoped-resource-key :rf.scope/global :sub/article {:slug "w"})
        q          {:resource :sub/article :scope :rf.scope/global :params {:slug "w"}}]
    (testing "before any load the state projection is the idle empty-state
              (a sub NEVER fetches — Spec 016 §Subscriptions)"
      (is (= {:status :idle :data nil :error nil :refresh-error nil
              :loading? false :fetching? false :stale? false :has-data? false
              :previous? false}
             @(rf/subscribe [:rf/resource q])))
      (is (nil? (entry scoped-key)) "subscribing did not cause a fetch / entry"))
    (rf/dispatch-sync [:rf.resource/ensure {:resource :sub/article :scope :rf.scope/global
                                            :params {:slug "w"} :owner [:lease :s 1]}])
    (let [wid (:current-work (entry scoped-key))]
      (rf/dispatch-sync [:rf.resource.internal/succeeded
                         {:resource/key scoped-key :work/id wid :generation 1
                          :data {:title "Welcome"}}]))
    (testing "after load the derived booleans are computed in the sub
              (loaded / has-data?), not stored on the entry"
      (is (= :loaded (:status @(rf/subscribe [:rf/resource q]))))
      (is (true?  @(rf/subscribe [:rf.resource/has-data? q])))
      (is (false? @(rf/subscribe [:rf.resource/loading? q])))
      (is (= {:title "Welcome"} @(rf/subscribe [:rf.resource/data q]))))))

(deftest stale-sub-derives-from-facts
  (rf/reg-resource :stl/article (article-spec {:stale-after-ms 60000}) article-spec-request)
  (let [scoped-key (state/scoped-resource-key :rf.scope/global :stl/article {:slug "w"})
        q          {:resource :stl/article :scope :rf.scope/global :params {:slug "w"}}]
    (rf/dispatch-sync [:rf.resource/ensure {:resource :stl/article :scope :rf.scope/global
                                            :params {:slug "w"} :owner [:lease :st 1]}])
    (let [wid (:current-work (entry scoped-key))]
      (rf/dispatch-sync [:rf.resource.internal/succeeded
                         {:resource/key scoped-key :work/id wid :generation 1
                          :data {:title "W"}}]))
    (testing "a freshly loaded entry (stale-after 60s) is not stale"
      (is (false? @(rf/subscribe [:rf.resource/stale? q]))))
    ;; release the owner so invalidation marks the entry stale WITHOUT
    ;; auto-refetching it (a refetch would satisfy + clear the invalidation)
    (rf/dispatch-sync [:rf.resource/release-owner {:owner [:lease :st 1]}])
    (rf/dispatch-sync [:rf.resource/invalidate-tags
                       {:scope :rf.scope/global :tags #{[:article "w"]}}])
    (testing "after exact-tag invalidation of an INACTIVE entry, :stale?
              derives true from the durable :invalidated-at fact (Spec 016
              §Invalidation / §Status semantics)"
      (is (some? (:invalidated-at (entry scoped-key))) "durable fact set")
      (is (true? @(rf/subscribe [:rf.resource/stale? q]))))))

(deftest succeeded-loaded-at-stale-at-from-reply-completed-at
  ;; rf2-n1rh0f / EP-0010 §Resources, Mutations, And Work-Ledger Timestamps:
  ;; the resource :loaded-at IS the successful reply's completion time
  ;; (carried on the reply token as the host :completed-at, which the managed
  ;; transport threads onto the reply event's :rf.cofx :time-ms), and
  ;; :stale-at = :loaded-at + :stale-after-ms — NOT an ambient clock read in
  ;; the reply handler. Scripting the reply dispatch's :rf.cofx pins
  ;; both; the same reply token rewrites the same durable timestamps.
  (rf/reg-resource :lra/article (article-spec {:stale-after-ms 60000}) article-spec-request)
  (let [scoped-key (state/scoped-resource-key :rf.scope/global :lra/article {:slug "w"})
        completed-at 1781078400456]
    (rf/dispatch-sync [:rf.resource/ensure {:resource :lra/article :scope :rf.scope/global
                                            :params {:slug "w"} :owner [:lease :lr 1]}])
    (let [e (entry scoped-key)]
      (rf/dispatch-sync [:rf.resource.internal/succeeded
                         {:resource/key scoped-key :work/id (:current-work e)
                          :generation (:generation e) :data {:title "W"}}]
                        ;; the managed transport stamps the host :completed-at
                        ;; here; a fixture scripts it directly on the reply
                        ;; token's world inputs.
                        {:rf.cofx {:rf/time-ms completed-at}}))
    (testing ":loaded-at is EXACTLY the reply completion time (not now)"
      (is (= completed-at (:loaded-at (entry scoped-key)))))
    (testing ":stale-at = :loaded-at + the :stale-after-ms policy"
      (is (= (+ completed-at 60000) (:stale-at (entry scoped-key)))))))

;; rf2-95b0lc / EP-0010 §The World-Input Rule: the ensure FRESH-SKIP gate is a
;; freshness DECISION that gates a durable runtime-db write (serve-cache vs
;; start-new-work). Its basis MUST be the ensure token's causal
;; `(:time-ms (:rf.cofx cofx))`, NOT an ambient host-clock read — so a
;; replayed ensure under a LATER live clock takes the SAME branch the recorded
;; `:time-ms` dictated. Reading `(now-ms)` instead (= the live host clock,
;; ~1.78e12 epoch-ms / `System/currentTimeMillis`) would dwarf any scripted
;; `:stale-at`, so a replay would re-read the live clock and could flip
;; serve-cache → refetch, minting a DIVERGENT work-ledger row — which this
;; pins against.
(deftest fresh-skip-decision-reads-causal-time-ms-not-ambient-clock
  (rf/reg-resource :fsd/article (article-spec {:stale-after-ms 60000}) article-spec-request)
  (let [scoped-key  (state/scoped-resource-key :rf.scope/global :fsd/article {:slug "w"})
        t0          1000000          ;; a SMALL scripted epoch-ms (a "recorded
        ;; run" basis far below the live host clock). The entry loads at t0 and
        ;; is fresh until t0 + 60000.
        completed-at t0]
    ;; --- first ensure + scripted reply: entry loads, fresh until t0+60s ------
    (rf/dispatch-sync [:rf.resource/ensure {:resource :fsd/article :scope :rf.scope/global
                                            :params {:slug "w"} :owner [:lease :fsd 1]}])
    (let [e (entry scoped-key)]
      (rf/dispatch-sync [:rf.resource.internal/succeeded
                         {:resource/key scoped-key :work/id (:current-work e)
                          :generation (:generation e) :data {:title "W"}}]
                        {:rf.cofx {:rf/time-ms completed-at}}))
    (is (= :loaded (:status (entry scoped-key))) "entry loaded")
    (is (= (+ t0 60000) (:stale-at (entry scoped-key))) "durable :stale-at = t0 + policy")
    (let [gen0 (:generation (entry scoped-key))]
      ;; --- replay the ensure under a LATER causal clock, still WITHIN the
      ;; freshness window — MUST fresh-skip (serve cache), even though the LIVE
      ;; host clock is decades past t0+60s. -------------------------------------
      (reset! last-managed-args nil)
      (rf/dispatch-sync [:rf.resource/ensure {:resource :fsd/article :scope :rf.scope/global
                                              :params {:slug "w"} :owner [:lease :fsd 1]}]
                        ;; scripted causal "now": t0 + 30s — fresh by policy.
                        {:rf.cofx {:rf/time-ms (+ t0 30000)}})
      (testing "a within-window causal :time-ms takes the FRESH-SKIP branch
                — no new work, no transport call, generation unchanged"
        (is (nil? @last-managed-args)
            "no managed-HTTP fetch fired — the ensure served cache (fresh-skip),
             NOT a new load (a pre-fix ambient (now-ms) read would have seen the
             live clock far past :stale-at and started a divergent fetch)")
        (is (= gen0 (:generation (entry scoped-key)))
            "generation unchanged — no new work-ledger generation was minted")
        (is (= :loaded (:status (entry scoped-key)))
            "entry stayed :loaded (served cache); did NOT transition to :fetching"))
      ;; --- the SAME entry, replayed under a causal :time-ms PAST the window,
      ;; takes the opposite (refetch) branch — proving the gate genuinely reads
      ;; the causal token, not a constant. ------------------------------------
      (reset! last-managed-args nil)
      (rf/dispatch-sync [:rf.resource/ensure {:resource :fsd/article :scope :rf.scope/global
                                              :params {:slug "w"} :owner [:lease :fsd 1]}]
                        ;; scripted causal "now": t0 + 90s — STALE by policy.
                        {:rf.cofx {:rf/time-ms (+ t0 90000)}})
      (testing "a past-window causal :time-ms takes the REFETCH branch — a new
                load fires (the decision tracks the causal token both ways)"
        (is (some? @last-managed-args)
            "a managed-HTTP fetch fired — the stale-by-causal-time ensure started
             a fresh load")
        (is (= (inc gen0) (:generation (entry scoped-key)))
            "a new generation was minted — the stale ensure forced new work")))))

;; ===========================================================================
;; 10. owner release / clear-scope / remove / tag invalidation
;; ===========================================================================

(deftest release-owner-drops-the-lease
  (rf/reg-resource :ro/article (article-spec) article-spec-request)
  (let [scoped-key (state/scoped-resource-key :rf.scope/global :ro/article {:slug "w"})]
    (rf/dispatch-sync [:rf.resource/ensure {:resource :ro/article :scope :rf.scope/global
                                            :params {:slug "w"} :owner [:lease :ro 1]}])
    (is (contains? (:active-owners (entry scoped-key)) [:lease :ro 1]))
    (rf/dispatch-sync [:rf.resource/release-owner {:owner [:lease :ro 1]}])
    (testing "Spec 016 §Active owners — release drops the owner from the
              entry + the owner-index"
      (is (not (contains? (:active-owners (entry scoped-key)) [:lease :ro 1])))
      (is (nil? (get-in (runtime-db) (conj (state/owner-index-path) [:lease :ro 1])))))))

(deftest clear-scope-removes-scoped-entries
  (rf/reg-resource :cs/article (article-spec {:scope :rf.scope/from-caller}) article-spec-request)
  (let [scope-a {:user "a"}
        scope-b {:user "b"}
        ka (state/scoped-resource-key scope-a :cs/article {:slug "w"})
        kb (state/scoped-resource-key scope-b :cs/article {:slug "w"})]
    (rf/dispatch-sync [:rf.resource/ensure {:resource :cs/article :scope scope-a
                                            :params {:slug "w"} :owner [:lease :a 1]}])
    (rf/dispatch-sync [:rf.resource/ensure {:resource :cs/article :scope scope-b
                                            :params {:slug "w"} :owner [:lease :b 1]}])
    (is (some? (entry ka)))
    (is (some? (entry kb)))
    (rf/dispatch-sync [:rf.resource/clear-scope {:scope scope-a :cause :logout}])
    (testing "Spec 016 §clear-scope — only the cleared scope's entries are
              removed; other scopes untouched (no cross-scope leak)"
      (is (nil?  (entry ka)) "scope A cleared")
      (is (some? (entry kb)) "scope B untouched"))))

(deftest invalidate-tags-marks-stale-and-refetches-active
  (rf/reg-resource :it/article (article-spec) article-spec-request)
  (let [scoped-key (state/scoped-resource-key :rf.scope/global :it/article {:slug "w"})]
    (rf/dispatch-sync [:rf.resource/ensure {:resource :it/article :scope :rf.scope/global
                                            :params {:slug "w"} :owner [:route :r 1]}])
    (let [wid (:current-work (entry scoped-key))]
      (rf/dispatch-sync [:rf.resource.internal/succeeded
                         {:resource/key scoped-key :work/id wid :generation 1
                          :data {:title "W"}}]))
    (is (= :loaded (:status (entry scoped-key))))
    (rf/dispatch-sync [:rf.resource/invalidate-tags
                       {:scope :rf.scope/global :tags #{[:article "w"]}}])
    (testing "Spec 016 §Invalidation — a matched active-owner entry is
              marked stale (durable :invalidated-at) and refetched
              (→ :fetching, prior data kept)"
      (let [e (entry scoped-key)]
        ;; the refetch dispatch (active owner) bumps it to :fetching
        (is (= :fetching (:status e)))
        (is (= {:title "W"} (:data e)) "prior data kept during refetch")))))

(deftest remove-evicts-the-entry
  (rf/reg-resource :rm/article (article-spec) article-spec-request)
  (let [scoped-key (state/scoped-resource-key :rf.scope/global :rm/article {:slug "w"})]
    (rf/dispatch-sync [:rf.resource/ensure {:resource :rm/article :scope :rf.scope/global
                                            :params {:slug "w"} :owner [:lease :rm 1]}])
    (is (some? (entry scoped-key)))
    (rf/dispatch-sync [:rf.resource/remove {:resource :rm/article :scope :rf.scope/global
                                            :params {:slug "w"}}])
    (testing "Spec 016 §Events — remove evicts the single instance"
      (is (nil? (entry scoped-key))))))

;; ===========================================================================
;; 11. reverse-index recompute is derivable from entries
;; ===========================================================================

(deftest indexes-recompute-from-entries
  (testing "Spec 016 §Restore and replay part 5 — :tag-index / :owner-index
            are recomputable-from-:entries (never trusted from a snapshot)"
    (let [k1 [:rf.scope/global :r/a {:id 1}]
          k2 [:rf.scope/global :r/b {:id 2}]
          subtree {:entries {k1 {:tags #{:t1 :shared} :active-owners #{[:lease 1]}}
                             k2 {:tags #{:t2 :shared} :active-owners #{[:lease 1]}}}}
          rebuilt (state/recompute-indexes subtree)]
      (is (= #{k1 k2} (get-in rebuilt [:tag-index :shared])))
      (is (= #{k1}    (get-in rebuilt [:tag-index :t1])))
      (is (= #{k1 k2} (get-in rebuilt [:owner-index [:lease 1]]))))))

;; ---- rf2-2c2mkh: incremental reindex == full rebuild (the safety net) ------
;;
;; `reindex-keys` maintains :tag-index / :owner-index INCREMENTALLY at the hot
;; mutation sites (a settle / release / remove / GC / clear / optimistic
;; apply-rollback touches a small known key set) instead of full-rebuilding via
;; `recompute-indexes` on every op. The indexes are a PURE projection of
;; :entries, so the incremental result MUST equal the full rebuild after every
;; op — these tests are the round-trip pin that stops incremental drift creeping
;; in (the audit's CORRECTNESS GUARD).

(deftest reindex-keys-equals-full-rebuild-on-crafted-transitions
  (testing "rf2-2c2mkh — reindex-keys over the touched keys yields EXACTLY what
            recompute-indexes would produce, across create / tag-change /
            owner-change / multi-key / remove transitions"
    (let [ka "id-a" kb "id-b" kc "id-c"
          ;; helper: assert reindex(touched) == full-rebuild for an old→new
          ;; entries transition (subtree carries the OLD indexes built by a
          ;; prior full rebuild — the realistic pre-mutation shape).
          check (fn [old-entries new-entries touched]
                  (let [old-subtree (state/recompute-indexes {:entries old-entries})
                        new-subtree (assoc old-subtree :entries new-entries)
                        incremental (state/reindex-keys new-subtree old-entries touched)
                        full        (state/recompute-indexes new-subtree)]
                    (is (= (:tag-index full)   (:tag-index incremental))
                        (str "tag-index drift on touched " (vec touched)))
                    (is (= (:owner-index full) (:owner-index incremental))
                        (str "owner-index drift on touched " (vec touched)))))]
      (testing "create one entry from empty"
        (check {} {ka {:tags #{:t1 :shared} :active-owners #{[:lease 1]}}} [ka]))
      (testing "tag replacement on a settle (owners unchanged)"
        (check {ka {:tags #{:t1 :shared} :active-owners #{[:lease 1]}}}
               {ka {:tags #{:t2 :shared} :active-owners #{[:lease 1]}}}
               [ka]))
      (testing "owner attach + release on one key"
        (check {ka {:tags #{:t1} :active-owners #{[:lease 1]}}}
               {ka {:tags #{:t1} :active-owners #{[:lease 1] [:route :r 7]}}}
               [ka])
        (check {ka {:tags #{:t1} :active-owners #{[:lease 1] [:route :r 7]}}}
               {ka {:tags #{:t1} :active-owners #{[:lease 1]}}}
               [ka]))
      (testing "removal (entry vanishes) drops its members + empties the bucket"
        (check {ka {:tags #{:only} :active-owners #{[:lease 9]}}
                kb {:tags #{:shared} :active-owners #{}}}
               {kb {:tags #{:shared} :active-owners #{}}}
               [ka]))
      (testing "multi-key clear-scope (two keys removed at once)"
        (check {ka {:tags #{:a :shared} :active-owners #{[:lease 1]}}
                kb {:tags #{:b :shared} :active-owners #{[:lease 1]}}
                kc {:tags #{:c} :active-owners #{}}}
               {kc {:tags #{:c} :active-owners #{}}}
               [ka kb]))
      (testing "reindexing an UNCHANGED key in the touched set is a no-op"
        (check {ka {:tags #{:t1} :active-owners #{[:lease 1]}}
                kb {:tags #{:t1} :active-owners #{[:lease 1]}}}
               {ka {:tags #{:t1} :active-owners #{[:lease 1]}}
                kb {:tags #{:t2} :active-owners #{[:lease 1]}}}
               ;; ka did not change but is in the touched set anyway
               [ka kb])))))

(deftest reindex-keys-equals-full-rebuild-under-random-mutation-sequence
  (testing "rf2-2c2mkh — across a long randomised sequence of single-key
            mutations (create / retag / re-own / remove), the incrementally
            maintained indexes equal the full rebuild after EVERY step"
    (let [;; small deterministic LCG so the property runs identically on every
          ;; platform / CI machine (no test.check dependency in the PR gate).
          seed     (atom 2463534242)
          nextint  (fn [n]
                     (let [x (-> (* @seed 1103515245) (+ 12345) (bit-and 0x7fffffff))]
                       (reset! seed x)
                       (mod x n)))
          key-ids  (mapv #(str "k" %) (range 8))
          tags     [:t0 :t1 :t2 :shared]
          owners   [[:lease 1] [:lease 2] [:route :r 1] [:route :r 2]]
          rand-set (fn [pool]
                     (set (keep (fn [x] (when (zero? (nextint 2)) x)) pool)))]
      (loop [step 0
             entries {}
             ;; the incrementally maintained subtree (indexes derived from the
             ;; SAME entries via reindex-keys after each step).
             subtree (state/recompute-indexes {:entries {}})]
        (if (= step 600)
          (is true "600 randomised mutations stayed in lock-step with the rebuild")
          (let [k        (nth key-ids (nextint (count key-ids)))
                remove?  (and (contains? entries k) (zero? (nextint 4)))
                new-entries (if remove?
                              (dissoc entries k)
                              (assoc entries k {:tags          (rand-set tags)
                                                :active-owners (rand-set owners)}))
                incremental (state/reindex-keys (assoc subtree :entries new-entries)
                                                entries [k])
                full        (state/recompute-indexes {:entries new-entries})]
            (is (= (:tag-index full) (:tag-index incremental))
                (str "tag-index drift at step " step " key " k))
            (is (= (:owner-index full) (:owner-index incremental))
                (str "owner-index drift at step " step " key " k))
            ;; also pin: no empty membership buckets survive (matches the
            ;; recompute-indexes shape — an emptied set is dropped entirely).
            (is (every? seq (vals (:tag-index incremental)))   "no empty tag buckets")
            (is (every? seq (vals (:owner-index incremental))) "no empty owner buckets")
            (recur (inc step) new-entries incremental)))))))

;; ===========================================================================
;; 12. resource registrar sanity (re-affirm the registrar kind is closed)
;; ===========================================================================

(deftest resource-kind-registered
  (testing "the :resource registrar kind is valid (skeleton invariant held)"
    (is (registrar/valid-kind? :resource))
    (is (not (registrar/valid-kind? :query)))))

;; ===========================================================================
;; 13. Concrete-scope typo rejection at resolution boundaries (rf2-pd7akw)
;; ===========================================================================

(deftest reserved-scope-typo-rejected-at-concrete-boundaries
  (rf/reg-resource :tp/article (article-spec {:scope :rf.scope/from-caller}) article-spec-request)
  (let [spec (registry/resource-meta :tp/article)]
    (testing "rf2-pd7akw — a misspelled reserved :rf.scope/* keyword on an
              EVENT payload is rejected fail-closed (never a silent wrong
              cache scope)"
      (is (thrown-with-msg?
            #?(:clj Throwable :cljs js/Error) #"resource-invalid-scope"
            (registry/resolve-scope-for-event
              :tp/article spec {:payload-scope :rf.scope/glabal} 'test))))
    (testing "rf2-pd7akw — a misspelled reserved :rf.scope/* keyword on a
              SUBSCRIPTION payload is rejected fail-closed too"
      (is (thrown-with-msg?
            #?(:clj Throwable :cljs js/Error) #"resource-invalid-scope"
            (registry/resolve-scope-for-sub
              :tp/article spec :rf.scope/sesssion 'test {}))))
    (testing "rf2-pd7akw — :rf.scope/from-caller reaching a CONCRETE boundary
              as a payload value (not a policy) is a typo-class rejection"
      (is (thrown-with-msg?
            #?(:clj Throwable :cljs js/Error) #"resource-invalid-scope"
            (registry/resolve-scope-for-event
              :tp/article spec {:payload-scope :rf.scope/from-caller} 'test))))
    (testing "an APP-namespaced keyword scope is a legitimate literal scope
              (only the framework-reserved namespace is fail-closed)"
      (is (= :my.app/tenant
             (registry/resolve-scope-for-event
               :tp/article spec {:payload-scope :my.app/tenant} 'test))))
    (testing "a vector-tuple scope [:rf.scope/session {…}] is a value, not a
              bare-keyword typo — accepted"
      (is (= [:rf.scope/session {:tenant "acme"}]
             (registry/resolve-scope-for-event
               :tp/article spec {:payload-scope [:rf.scope/session {:tenant "acme"}]}
               'test))))))

;; ===========================================================================
;; 14. Singleton-vector [:rf.scope/global] is rejected fail-closed — the
;;     global scope IS the bare keyword (rf2-bwwk6l; was rf2-vv87xz's
;;     back-compat normalize alias, removed pre-alpha)
;; ===========================================================================

(deftest singleton-vector-global-rejected-fail-closed
  (testing "rf2-bwwk6l — the bare :rf.scope/global is the canonical concrete
            global scope and canonicalizes unchanged"
    (is (= :rf.scope/global (state/canonicalize-scope :rf.scope/global 'test :r/x))))
  (testing "rf2-bwwk6l — the singleton-vector [:rf.scope/global] spelling is
            NO LONGER accepted as a global alias; it fails closed at the shared
            canonicalize boundary, naming the canonical bare spelling"
    (is (thrown-with-msg?
          #?(:clj Throwable :cljs js/Error) #"resource-invalid-scope"
          (state/canonicalize-scope [:rf.scope/global] 'test :r/x))))
  (testing "rf2-bwwk6l — a sub payload carrying the wrapped [:rf.scope/global]
            spelling fails closed too (never a silent read of the bare global
            entry)"
    (rf/reg-resource :gv/article (article-spec) article-spec-request)
    (is (thrown-with-msg?
          #?(:clj Throwable :cljs js/Error) #"resource-invalid-scope"
          (subs/resolve-scoped-key {:resource :gv/article
                                    :scope [:rf.scope/global]
                                    :params {:slug "w"}}
                                   {})))))

;; ===========================================================================
;; 15. clear-resource disposes live runtime state (rf2-m9h5iq)
;; ===========================================================================

(deftest clear-resource-disposes-runtime-state
  (rf/reg-resource :cr/article (article-spec) article-spec-request)
  (let [loaded-key  (state/scoped-resource-key :rf.scope/global :cr/article {:slug "loaded"})
        inflight-key (state/scoped-resource-key :rf.scope/global :cr/article {:slug "inflight"})]
    ;; one LOADED entry (with tags + an active owner → indexed)
    (rf/dispatch-sync [:rf.resource/ensure {:resource :cr/article :scope :rf.scope/global
                                            :params {:slug "loaded"} :owner [:lease :cr 1]}])
    (let [wid (:current-work (entry loaded-key))]
      (rf/dispatch-sync [:rf.resource.internal/succeeded
                         {:resource/key loaded-key :work/id wid :generation 1
                          :data {:title "L"} :tags #{[:article "loaded"]}}]))
    ;; one IN-FLIGHT entry (still :loading, has :current-work)
    (rf/dispatch-sync [:rf.resource/ensure {:resource :cr/article :scope :rf.scope/global
                                            :params {:slug "inflight"} :owner [:lease :cr 2]}])
    (is (= :loaded (:status (entry loaded-key))))
    (is (some? (:current-work (entry inflight-key))) "in-flight entry has live work")
    (is (seq (get-in (runtime-db) (state/tag-index-path))) "tag index populated")
    (is (seq (get-in (runtime-db) (state/owner-index-path))) "owner index populated")
    (let [inflight-wid (:current-work (entry inflight-key))]
      ;; CLEAR the resource (registration-lifecycle + runtime disposal)
      (registry/clear-resource :cr/article)
      (testing "rf2-m9h5iq — clear-resource removes the registrar entry"
        (is (nil? (registry/resource-meta :cr/article))))
      (testing "rf2-m9h5iq — every live entry for the id is removed from
                :rf.runtime/resources :entries"
        (is (nil? (entry loaded-key)))
        (is (nil? (entry inflight-key))))
      (testing "rf2-m9h5iq — reverse indexes are recomputed/pruned"
        (is (empty? (get-in (runtime-db) (state/tag-index-path))))
        (is (empty? (get-in (runtime-db) (state/owner-index-path)))))
      (testing "rf2-m9h5iq — the in-flight work record is settled terminal
                (:suppressed) so the ledger row is not left in-flight"
        ;; Read through the byte-keyed work-ledger API (rf2-hgy5kf): the row is
        ;; addressed by `work-ledger/work-id-id`, NOT the stale vector key. The
        ;; assertion is NON-vacuous — a present post-clear row MUST be terminal
        ;; (`:suppressed`), and a present-but-NON-terminal row FAILS the test
        ;; (the exact drift EP-0012 closes; the old `(when rec …)` made it pass
        ;; silently when the byte-keyed row was read through a dead address).
        (let [rec (work-ledger/get-record (runtime-db) inflight-wid)]
          (is (some? rec)
              "the post-clear work record MUST be readable through the byte-keyed
               address — a nil here means the contract is asserted against a dead
               address, the exact vacuity EP-0012 closes")
          (is (work-ledger/terminal? (:status rec))
              "a present post-clear work record must be terminal, never in-flight")
          (is (= :suppressed (:status rec)))))
      (testing "rf2-m9h5iq — a LATE reply for a cleared in-flight entry cannot
                recreate it (its existence check finds the entry gone)"
        (rf/dispatch-sync [:rf.resource.internal/succeeded
                           {:resource/key inflight-key :work/id inflight-wid
                            :generation 1 :data {:title "late"}}])
        (is (nil? (entry inflight-key))
            "late reply suppressed — no resurrected entry")))))

;; ===========================================================================
;; 16. mutation scope routes through shared validation (rf2-lzv9xc)
;; ===========================================================================

(deftest mutation-scope-routes-through-shared-validation
  (testing "rf2-lzv9xc — a mutation execute-payload scope that is a reserved
            :rf.scope/* typo is rejected through the same path resources use"
    (is (thrown-with-msg?
          #?(:clj Throwable :cljs js/Error) #"resource-invalid-scope"
          (mreg/resolve-scope :m/x {} :rf.scope/glabal))))
  (testing "rf2-lzv9xc — a host / opaque mutation scope value is rejected"
    (is (thrown-with-msg?
          #?(:clj Throwable :cljs js/Error) #"resource-non-edn-params"
          (mreg/resolve-scope :m/x {} {:fn (fn [])}))))
  (testing "rf2-lzv9xc — the default global scope still resolves to the bare
            :rf.scope/global"
    (is (= :rf.scope/global (mreg/resolve-scope :m/x {} nil))))
  (testing "rf2-bwwk6l — the wrapped [:rf.scope/global] singleton is rejected
            fail-closed (no back-compat alias); supply the bare keyword"
    (is (thrown-with-msg?
          #?(:clj Throwable :cljs js/Error) #"resource-invalid-scope"
          (mreg/resolve-scope :m/x {} [:rf.scope/global])))))

;; ===========================================================================
;; 17. resource-state fails closed without an explicit frame (rf2-c8lgy3)
;; ===========================================================================

(deftest resource-state-fails-closed-without-frame
  (rf/reg-resource :rs/article (article-spec) article-spec-request)
  (testing "rf2-c8lgy3 — a frameless resource-state call raises
            :rf.error/no-frame-context (never a silent nil that is
            indistinguishable from an absent entry)"
    (is (thrown-with-msg?
          #?(:clj Throwable :cljs js/Error) #"no-frame-context"
          (re-frame.resources/resource-state
            {:resource :rs/article :scope :rf.scope/global :params {:slug "w"}}))))
  (testing "rf2-c8lgy3 — a valid explicit frame returns nil ONLY for a
            genuinely absent entry"
    (is (nil? (re-frame.resources/resource-state
                {:resource :rs/article :scope :rf.scope/global
                 :params {:slug "absent"} :frame :rf/default}))))
  (testing "rf2-c8lgy3 — a valid explicit frame returns the entry when present"
    (let [k (state/scoped-resource-key :rf.scope/global :rs/article {:slug "w"})]
      (rf/dispatch-sync [:rf.resource/ensure {:resource :rs/article :scope :rf.scope/global
                                              :params {:slug "w"} :owner [:lease 1]}])
      (let [wid (:current-work (entry k))]
        (rf/dispatch-sync [:rf.resource.internal/succeeded
                           {:resource/key k :work/id wid :generation 1
                            :data {:title "W"}}]))
      (is (some? (re-frame.resources/resource-state
                   {:resource :rs/article :scope :rf.scope/global
                    :params {:slug "w"} :frame :rf/default}))))))

;; ===========================================================================
;; 17b. `resources` introspection keys `:entries` on the CEDN-1 byte `key-id`
;;      (rf2-jtlq7l intent, rf2-ka2nkx correction) — callers destructure /
;;      filter via each entry's kind-preserving `:resource/key` vector
;; ===========================================================================

(deftest resources-introspection-keys-by-byte-key-id
  (testing "rf2-ka2nkx (supersedes the rf2-jtlq7l vector-rekey): `(resources
            {:frame f})` returns `:entries` keyed on the CEDN-1 byte `key-id`
            STRING (the same key the runtime storage / SSR wire / indexes use,
            which CANNOT collapse CEDN-distinct sequential-params entries); each
            entry carries its kind-preserving `:resource/key` VECTOR `[scope
            resource-id params]` for destructure / filter. Rekeying onto the
            `=`-colliding scoped-key vector (the original rf2-jtlq7l approach)
            collapsed a list-params and a vector-params entry onto one map key"
    (rf/reg-resource :intro/article (article-spec) article-spec-request)
    (let [k1 (state/scoped-resource-key :rf.scope/global :intro/article {:slug "one"})
          k2 (state/scoped-resource-key :rf.scope/global :intro/article {:slug "two"})]
      ;; Install two entries with DISTINCT scoped keys (distinct byte key-ids).
      (rf/dispatch-sync [:rf.resource/ensure {:resource :intro/article :scope :rf.scope/global
                                              :params {:slug "one"} :owner [:lease :intro 1]}])
      (rf/dispatch-sync [:rf.resource/ensure {:resource :intro/article :scope :rf.scope/global
                                              :params {:slug "two"} :owner [:lease :intro 2]}])
      (let [{:keys [resource-ids entries]} (re-frame.resources/resources {:frame :rf/default})]
        (testing "the static registry still lists the registered id"
          (is (contains? (set resource-ids) :intro/article)))
        (testing "entry keys are the CEDN-1 byte key-id STRINGS (collapse-proof)"
          (is (= #{(state/key-id k1) (state/key-id k2)} (set (keys entries)))
              "the public accessor keys by the byte key-id, matching internal storage")
          (is (every? string? (keys entries))
              "every entry key is the byte key-id string")
          (is (contains? entries (state/key-id k1))
              "the byte key-id IS the public entry key"))
        (testing "each entry carries its kind-preserving :resource/key VECTOR for
                  destructure + scope/resource filtering (the rf2-jtlq7l goal,
                  served via the entry not the map key)"
          (is (= #{k1 k2} (set (map :resource/key (vals entries))))
              "every entry exposes its scoped-key vector")
          (is (every? vector? (map :resource/key (vals entries)))
              "every :resource/key is a [scope resource-id params] vector")
          (let [[scope rid params] (some #(when (= k1 (:resource/key %)) (:resource/key %))
                                         (vals entries))]
            (is (= :rf.scope/global scope))
            (is (= :intro/article rid))
            (is (= {:slug "one"} params)))
          (is (= #{k1 k2}
                 (set (for [e (vals entries)
                            :let [[scope rid params] (:resource/key e)]
                            :when (and (= :rf.scope/global scope)
                                       (= :intro/article rid))]
                        [scope rid params])))
              "every entry filters cleanly by scope + resource-id"))
        (testing "the entry value is carried through unchanged (byte-keyed
                  in internal storage too)"
          (is (= :intro/article (-> entries (get (state/key-id k1)) :resource/key second)))
          (let [raw (get-in (:rf.db/runtime (rf/frame-state-value :rf/default)) (state/entries-path))]
            (is (every? string? (keys raw))
                "internal runtime storage remains byte-keyed (string key-ids)")))))))

(deftest resources-introspection-keeps-cedn-distinct-scoped-keys-distinct
  (testing "rf2-ka2nkx ADVERSARIAL: `(resources {:frame f})` preserves ONE
            returned entry per byte-keyed runtime entry when two entries have
            CEDN-distinct but Clojure-= scoped-key vectors (vector params vs
            list params). The former vector-rekey `assoc`'d one entry OVER the
            other (the `=`-collapse), reporting ONE entry for TWO live ones"
    (rf/reg-resource :intro/article (article-spec) article-spec-request)
    (let [kv (state/scoped-resource-key :rf.scope/global :intro/article {:xs [1 2 3]})
          kl (state/scoped-resource-key :rf.scope/global :intro/article {:xs '(1 2 3)})
          ev (assoc (state/empty-entry :intro/article kv) :status :loaded :data {:v 1})
          el (assoc (state/empty-entry :intro/article kl) :status :loaded :data {:l 1})]
      (is (= kv kl) "the scoped-key VECTORS are Clojure-= (the collapse routed around)")
      (is (not= (state/key-id kv) (state/key-id kl)) "their byte key-ids differ")
      ;; write both entries the runtime way (byte-keyed paths) — a `{kv .. kl ..}`
      ;; literal would itself throw `Duplicate key` (kv and kl are Clojure-=).
      (frame/swap-runtime-db! :rf/default
        (fn [rdb] (-> (or rdb {})
                      (assoc-in (state/entry-path kv) ev)
                      (assoc-in (state/entry-path kl) el))))
      (let [{:keys [entries]} (re-frame.resources/resources {:frame :rf/default})]
        (is (= 2 (count entries))
            "TWO distinct returned entries — no =-collapse onto one map key")
        (is (= #{(state/key-id kv) (state/key-id kl)} (set (keys entries)))
            "keyed on the byte key-ids of BOTH entries")
        (is (= {:v 1} (:data (get entries (state/key-id kv)))) "vector entry intact")
        (is (= {:l 1} (:data (get entries (state/key-id kl)))) "list entry NOT overwritten")
        (testing "each entry carries its kind-preserving :resource/key"
          (is (vector? (-> (get entries (state/key-id kv)) :resource/key (nth 2) :xs))
              "vector-params entry preserves vector kind on :resource/key")
          (is (seq? (-> (get entries (state/key-id kl)) :resource/key (nth 2) :xs))
              "list-params entry preserves list kind on :resource/key"))))))

(deftest resources-introspection-without-frame-returns-empty-entries
  (testing "rf2-jtlq7l — `(resources)` (no frame) still returns
            `{:resource-ids [...] :entries {}}` (no ambient fallback)"
    (rf/reg-resource :introf/article (article-spec) article-spec-request)
    (let [{:keys [resource-ids entries]} (re-frame.resources/resources)]
      (is (contains? (set resource-ids) :introf/article))
      (is (= {} entries) "frameless call yields an empty entries map"))))

;; ===========================================================================
;; 18. param canonicalization is total over mixed EDN key types (rf2-ptz7z8)
;; ===========================================================================

(deftest param-canonicalization-total-over-mixed-keys
  (testing "rf2-ptz7z8 — a params map mixing keyword and string keys
            canonicalizes deterministically (no raw ClassCastException)"
    (let [c1 (state/canonicalize {:b 1 "a" 2 :a 3 "z" 4})
          c2 (state/canonicalize {"z" 4 :a 3 "a" 2 :b 1})]
      (is (= c1 c2) "key-order-independent over mixed key types")
      (is (= {:b 1 "a" 2 :a 3 "z" 4} c1) "values preserved")))
  (testing "rf2-ptz7z8 — mixed nil / number / boolean / keyword / string keys
            all order deterministically"
    (let [m {nil 0 1 :one true :t :kw :k "s" :str}]
      (is (= (state/canonicalize m) (state/canonicalize (into {} (shuffle (seq m))))))))
  (testing "rf2-ptz7z8 — a scoped key built from mixed-key params is stable"
    (is (= (state/scoped-resource-key :rf.scope/global :r/x {:a 1 "b" 2})
           (state/scoped-resource-key :rf.scope/global :r/x {"b" 2 :a 1})))))

;; ===========================================================================
;; 19. Shared reset contract (rf2-784223) — `make-reset-runtime-fixture` plus
;;     `re-frame.resources.test-support` clears the resource + mutation
;;     registrars AND every resources host-side side table.
;; ===========================================================================

(deftest reset-resources-clears-registrars-and-host-side-tables
  ;; This is the contract the per-suite fixtures now RELY on (rf2-784223):
  ;; instead of each fixture redundantly re-resetting these caches, the
  ;; shared `make-reset-runtime-fixture` fires `:resources/reset-resources!`
  ;; (published at `re-frame.resources.test-support` ns-load). Prove that one
  ;; thunk clears the whole surface, so the redundant per-suite resets were
  ;; safe to remove.
  (testing "the late-bind reset hook IS published (the fixture's mechanism)"
    (is (some? (late-bind/get-fn :resources/reset-resources!))
        ":resources/reset-resources! hook published at test-support ns-load")
    (is (identical? resources-test-support/reset-resources!
                    (late-bind/get-fn :resources/reset-resources!))
        "the published hook IS test-support/reset-resources!"))
  ;; Seed every surface the reset must clear.
  (rf/reg-resource :rst/article (article-spec) article-spec-request)
  (rf/reg-mutation :rst/save
                   {:params-schema [:map [:slug :string]]}
                   (fn [{:keys [slug]} _ctx]
                     {:request {:method :post :url (str "/api/articles/" slug)}}))
  (state/commit-generation! :rf/default 7)
  (let [k       (state/scoped-resource-key :rf.scope/global :rst/article {:slug "w"})
        work-id (work-ledger/resource-work-id k 1)]
    (work-ledger/put-handle! :rf/default work-id {:abort-fn (fn [] nil)})
    (timers/schedule! :rf/default k timers/gc-kind 1000000)
    (swap! revalidate-listeners/listener-table assoc :rf/default
           {:focus :h :visibility :h :online :h})
    ;; precondition: everything is populated
    (is (contains? (registrar/registrations registry/resource-kind) :rst/article))
    (is (contains? (registrar/registrations mreg/mutation-kind) :rst/save))
    (is (= 7 (state/generation-snapshot :rf/default)))
    (is (some? (work-ledger/get-handle :rf/default work-id)))
    ;; rf2-9e0tyq — the timer side-table key's resource-key element is the byte key-id.
    (is (contains? @timers/timer-table [:rf/default (state/key-id k) timers/gc-kind]))
    (is (contains? @revalidate-listeners/listener-table :rf/default))
    (testing "rf2-784223 — `reset-resources!` clears the resource + mutation
              registrars AND the generation / work-ledger-handle / timer /
              revalidate-listener host side tables in ONE call"
      (resources-test-support/reset-resources!)
      (is (empty? (registrar/registrations registry/resource-kind))
          "resource registrar cleared")
      (is (empty? (registrar/registrations mreg/mutation-kind))
          "mutation registrar cleared")
      (is (= 0 (state/generation-snapshot :rf/default))
          "generation high-water cache cleared")
      (is (nil? (work-ledger/get-handle :rf/default work-id))
          "work-ledger host handle table cleared")
      (is (not (contains? @timers/timer-table [:rf/default (state/key-id k) timers/gc-kind]))
          "stale/GC timer table cleared (timer cancelled)")
      (is (not (contains? @revalidate-listeners/listener-table :rf/default))
          "revalidation-listener side table cleared"))))

;; ===========================================================================
;; 20. Sub-side dev-warning for a session-scope subscription mismatch
;;     (rf2-rsmiru) — the read-side complement of the Xray write-side
;;     scope-mismatch lint. A `:rf.scope/from-caller` resource subscribed at
;;     a scope with ZERO active owners while a DIFFERENT scope for the SAME
;;     resource IS active is the silent-permanent-skeleton footgun; dev-mode
;;     warns. DEV-only + production-elided (Closure DCE on debug-enabled?).
;; ===========================================================================

(defn- record-scope-mismatch-warnings!
  "Run `body-fn` with a trace listener installed; return the vector of every
  `:rf.warning/resource-sub-scope-mismatch` event emitted during it (in
  capture order). The listener is unregistered in a `finally`."
  [body-fn]
  (let [seen (atom [])
        k    ::scope-mismatch-recorder]
    (trace-tooling/register-listener!
      k (fn [ev]
          (when (= :rf.warning/resource-sub-scope-mismatch (:operation ev))
            (swap! seen conj ev))))
    (try (body-fn)
         (finally (trace-tooling/unregister-listener! k)))
    @seen))

(defn- load-under!
  "Ensure + settle a `:sm/article` resource under `scope` with an active
  owner so its entry is :loaded and ACTIVE (≥1 active owner)."
  [scope owner]
  (let [k (state/scoped-resource-key scope :sm/article {:slug "w"})]
    (rf/dispatch-sync [:rf.resource/ensure {:resource :sm/article :scope scope
                                            :params {:slug "w"} :owner owner}])
    (let [wid (:current-work (entry k))]
      (rf/dispatch-sync [:rf.resource.internal/succeeded
                         {:resource/key k :work/id wid :generation 1
                          :data {:title "W"}}]))
    k))

(deftest scope-mismatch-warning-fires-on-genuine-mismatch
  (rf/reg-resource :sm/article (article-spec {:scope :rf.scope/from-caller}) article-spec-request)
  (testing "rf2-rsmiru — a from-caller sub at a scope with ZERO active owners,
            while a DIFFERENT scope for the same resource IS active, emits the
            dev-only :rf.warning/resource-sub-scope-mismatch warning"
    ;; scope A is ensured + active (a real route lease); the view mistakenly
    ;; subscribes under scope B (no owner ever attached) — the footgun.
    (load-under! {:user "a"} [:route :r 1])
    (let [warns (record-scope-mismatch-warnings!
                  (fn []
                    (subs/state-sub-fn (runtime-db)
                                       [:rf/resource
                                        {:resource :sm/article :scope {:user "b"}
                                         :params {:slug "w"}}])))]
      (is (= 1 (count warns)) "exactly one warning fired")
      (let [w (first warns)]
        (is (= :rf.warning/resource-sub-scope-mismatch (:operation w)))
        (is (= :sm/article  (-> w :tags :resource-id)))
        (is (= {:user "b"}  (-> w :tags :sub-scope)) "the mismatched sub scope")
        (is (= {:user "a"}  (-> w :tags :active-scope)) "the active scope it likely meant"))))
  (testing "rf2-rsmiru — the warning is ONE-SHOT (a reactively re-running sub
            warns once per genuine mismatch, never floods)"
    (let [warns (record-scope-mismatch-warnings!
                  (fn []
                    (dotimes [_ 5]
                      (subs/state-sub-fn (runtime-db)
                                         [:rf/resource
                                          {:resource :sm/article :scope {:user "b"}
                                           :params {:slug "w"}}]))))]
      (is (zero? (count warns)) "already-warned mismatch is not re-emitted")))
  (testing "rf2-rsmiru — the sub STILL reads the documented :idle empty-state
            projection (fail-closed is preserved; the warning is advisory)"
    (is (= :idle (:status (subs/state-sub-fn (runtime-db)
                                             [:rf/resource
                                              {:resource :sm/article :scope {:user "b"}
                                               :params {:slug "w"}}]))))))

(deftest scope-mismatch-warning-does-not-fire-when-scopes-match
  (rf/reg-resource :sm/article (article-spec {:scope :rf.scope/from-caller}) article-spec-request)
  (testing "rf2-rsmiru — NO warning when the sub scope MATCHES the active
            ensure scope (the correct, ergonomic case)"
    (load-under! {:user "a"} [:route :r 1])
    (let [warns (record-scope-mismatch-warnings!
                  (fn []
                    (subs/state-sub-fn (runtime-db)
                                       [:rf/resource
                                        {:resource :sm/article :scope {:user "a"}
                                         :params {:slug "w"}}])))]
      (is (zero? (count warns)) "matching scope reads the live entry — no warning")))
  (testing "rf2-rsmiru — NO warning when only a SINGLE scope is active and the
            sub reads it (a bare un-ensured resource is not a mismatch)"
    ;; release the only owner so NO scope is active for the resource …
    (rf/dispatch-sync [:rf.resource/release-owner {:owner [:route :r 1]}])
    (let [warns (record-scope-mismatch-warnings!
                  (fn []
                    ;; a sub under a never-ensured scope C: no DIFFERENT scope
                    ;; is active, so this is an un-ensured resource (the empty
                    ;; :idle projection), not a likely mismatch.
                    (subs/state-sub-fn (runtime-db)
                                       [:rf/resource
                                        {:resource :sm/article :scope {:user "c"}
                                         :params {:slug "w"}}])))]
      (is (zero? (count warns))
          "no OTHER active scope → not a mismatch (just an un-ensured read)"))))

(deftest scope-mismatch-warning-does-not-fire-for-non-from-caller-policies
  (testing "rf2-rsmiru — a :rf.scope/global resource never trips the heuristic
            (global resolves the SAME scope sub-side and ensure-side, so a sub
            cannot land on a different key than the ensure did)"
    (rf/reg-resource :smg/article (article-spec) article-spec-request) ; :rf.scope/global
    (let [k (state/scoped-resource-key :rf.scope/global :smg/article {:slug "w"})]
      (rf/dispatch-sync [:rf.resource/ensure {:resource :smg/article :scope :rf.scope/global
                                              :params {:slug "w"} :owner [:route :g 1]}])
      (let [wid (:current-work (entry k))]
        (rf/dispatch-sync [:rf.resource.internal/succeeded
                           {:resource/key k :work/id wid :generation 1 :data {:title "W"}}])))
    (let [warns (record-scope-mismatch-warnings!
                  (fn []
                    (subs/state-sub-fn (runtime-db)
                                       [:rf/resource
                                        {:resource :smg/article :scope :rf.scope/global
                                         :params {:slug "w"}}])))]
      (is (zero? (count warns)) "global-scope resources are out of scope for the heuristic"))))
