(ns re-frame.ssr.payload-policy-cljs-test
  "Per-leaf smoke tests for `re-frame.ssr.payload-policy`.

  Pins the explicit, fail-closed hydration-payload policy contract,
  carried by a SINGLE `:payload` opt:

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

  One opt holds exactly one value, so there is no precedence rule to
  arbitrate — the allowlist-vs-whole choice is the value's SHAPE
  (sequential collection vs keyword).

  These tests run on both JVM and Node — the policy logic is
  platform-neutral .cljc.

  ## Posture split

  This namespace runs in `scripts/test-ssr-prod-gate.sh`, under
  `-Dre-frame.debug=false`, as well as in the dev posture. Because it
  guards `project-routing-egress` — what leaves the server inside a
  hydration payload — which assertion sits behind the posture guard
  matters. Exactly one does: the `:rf.ssr/invalid-version` WARNING trace in
  `resolve-version-coerces-and-rejects-to-integer`, the dev half. The
  REJECTION it accompanies — a semver string never reaches `:rf/version`,
  which falls back to the integer v1 — is asserted immediately above it and
  passes in both postures, so nothing that decides what egresses is
  posture-gated. The always-on privacy witness for the egress itself is
  `re-frame.ssr-routing-egress-production-test`.

  The trace assertions sit inside a `(when interop/debug-enabled? …)` arm.
  Everything else in this namespace is pure policy logic and
  posture-independent."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [malli.core :as m]
            [re-frame.interop :as rf.interop]
            [re-frame.ssr.payload-policy :as rf.ssr.payload-policy]
            #?(:clj  [re-frame.test-support :refer [with-trace-recorder!]]
               :cljs [re-frame.test-support :refer-macros [with-trace-recorder!]])))

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
    (let [slice (rf.ssr.payload-policy/apply-policy
                  sample-app-db
                  {:payload [:public/articles :public/user-id]})]
      (is (= {:public/articles [:a :b :c]
              :public/user-id  "u-42"}
             slice)
          "exactly the keys in the allowlist; everything else dropped"))))

(deftest apply-policy-allowlist-missing-keys-omitted
  (testing "allowlist keys absent from app-db → omitted from the slice
            (the policy is a permission, not a guarantee)"
    (let [slice (rf.ssr.payload-policy/apply-policy
                  sample-app-db
                  {:payload [:public/articles :public/no-such-key]})]
      (is (= {:public/articles [:a :b :c]} slice)
          "missing keys silently absent; matches `select-keys` semantics"))))

(deftest apply-policy-list-payload-is-a-valid-allowlist
  (testing "a LIST / lazy-seq of keywords IS an accepted allowlist spelling —
            the policy selector is COLLECTION-vs-KEYWORD, not
            vector-vs-keyword. A sequential of keywords can never be confused
            with the :rf.ssr.payload/whole-app-db keyword opt-in, so a
            computed (keep ...) / (filterv ...) / (mapv ...) result (a list
            or lazy-seq) projects the same slice a vector does. Vectors stay
            the documented canonical spelling; the (every? keyword?) element
            guard and the empty-allowlist fail-closed apply to every
            sequential spelling."
    (let [slice (rf.ssr.payload-policy/apply-policy
                  sample-app-db
                  {:payload '(:public/articles)})]
      (is (= {:public/articles [:a :b :c]} slice)
          "a list allowlist projects the expected slice"))
    (let [slice (rf.ssr.payload-policy/apply-policy
                  sample-app-db
                  {:payload (seq [:public/articles :public/user-id])})]
      (is (= {:public/articles [:a :b :c] :public/user-id "u-42"} slice)
          "a lazy seq allowlist projects the expected slice"))
    (testing "construction-time arm agrees — a list :payload validates OK"
      (is (= {:initial-events [[:init]] :payload '(:public/articles)}
             (rf.ssr.payload-policy/validate-policy-opts!
               {:initial-events [[:init]] :payload '(:public/articles)}))
          "validate-policy-opts! returns opts unchanged for a list allowlist"))))

;; ---- apply-policy: malformed allowlist ------------------------------------

(deftest apply-policy-rejects-malformed-allowlists
  (testing "a non-empty sequential :payload carrying any NON-KEYWORD entry
            is a malformed allowlist. An outer-shape `(and sequential? seq)`
            check alone would accept it and `select-keys` would then ship an
            empty or wrong slice silently, so each fails loud with
            `:rf.error/ssr-malformed-payload-allowlist` — never the generic
            missing-policy bucket"
    (doseq [[label payload]
            [["a string entry (the classic typo for a keyword)"
              ["public/articles"]]
             ["a MIXED allowlist — every entry must be a keyword"
              [:public/articles "public/user-id"]]
             ["a string entry in a LIST — the element check applies to every
               sequential spelling"
              '("public/articles")]
             ["a stray nil entry"
              [nil]]
             ["a stray nil beside a valid keyword"
              [:public/articles nil]]
             ["a nested collection — not an allowlist of top-level keys"
              [[:public/articles :public/user-id]]]]]
      (is (thrown-with-msg?
            #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core/ExceptionInfo)
            #":rf\.error/ssr-malformed-payload-allowlist"
            (rf.ssr.payload-policy/apply-policy sample-app-db {:payload payload}))
          label))))

(deftest malformed-allowlist-error-names-bad-entries
  (testing "the structured error carries the offending
            non-keyword entries under `:bad-entries` so the developer can
            see exactly what to fix"
    (try
      (rf.ssr.payload-policy/validate-policy-opts!
        {:initial-events [[:init]] :payload [:public/articles "user-id" nil]})
      (is false "should have thrown")
      (catch #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core/ExceptionInfo) e
        (let [data (ex-data e)]
          (is (= :rf.error/ssr-malformed-payload-allowlist
                 (:rf.error/id data)))
          (is (= ["user-id" nil] (:bad-entries data))
              ":bad-entries lists exactly the non-keyword elements")
          (is (= :declare-payload-policy (:recovery data))))))))

;; ---- apply-policy: whole-app-db branch (:payload keyword) ----------------

(deftest apply-policy-whole-app-db-policy-ships-everything
  (testing ":payload :rf.ssr.payload/whole-app-db ships app-db verbatim"
    (let [slice (rf.ssr.payload-policy/apply-policy
                  sample-app-db
                  {:payload :rf.ssr.payload/whole-app-db})]
      (is (= sample-app-db slice)
          "whole-app-db opt-in → identity over app-db"))))

;; ---- apply-policy: fail-closed --------------------------------------------

(deftest apply-policy-fails-closed-without-a-usable-policy
  (testing "fail-closed: every opts shape that names no usable policy throws
            :rf.error/ssr-missing-payload-policy"
    (doseq [[label opts]
            [["no :payload key"
              {}]
             ["nil opts"
              nil]
             ["an empty allowlist — shipping zero keys is almost certainly a
               programmer error, not intent"
              {:payload []}]
             ["a nil :payload"
              {:payload nil}]
             ["a SET :payload — the contract is an ORDERED key selection, so
               a set is rejected rather than silently accepted"
              {:payload #{:public/articles}}]]]
      (is (thrown-with-msg?
            #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core/ExceptionInfo)
            #":rf\.error/ssr-missing-payload-policy"
            (rf.ssr.payload-policy/apply-policy sample-app-db opts))
          label))))

(deftest apply-policy-throws-on-unknown-policy-keyword
  (testing "a typo'd :payload keyword surfaces as
            :rf.error/ssr-unknown-payload-policy — distinct from the
            missing-policy bucket so a typo doesn't silently land in
            the `nothing-supplied` arm"
    (is (thrown-with-msg?
          #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core/ExceptionInfo)
          #":rf\.error/ssr-unknown-payload-policy"
          (rf.ssr.payload-policy/apply-policy
            sample-app-db
            {:payload :rf.ssr.payload/whole-db})) ; typo
        "typo'd policy keyword throws unknown-policy")
    (is (thrown-with-msg?
          #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core/ExceptionInfo)
          #":rf\.error/ssr-unknown-payload-policy"
          (rf.ssr.payload-policy/apply-policy
            sample-app-db
            {:payload :myapp/custom-policy})))))

;; ---- validate-policy-opts!: construction-time arm ------------------------

(deftest validate-policy-opts-passes-whole-app-db
  (testing "valid :payload whole-app-db keyword passes validation"
    (let [opts {:initial-events [[:init]] :payload :rf.ssr.payload/whole-app-db}]
      (is (= opts (rf.ssr.payload-policy/validate-policy-opts! opts))))))

(deftest validate-policy-opts-fails-closed
  (testing "fail-closed: validation throws on absence — the
            handler-construction-time arm of the same contract as
            apply-policy — and the structured error carries `:recovery
            :declare-payload-policy` so trace tooling can suggest the fix
            (Spec 009 error catalogue convention)"
    (let [data (try (rf.ssr.payload-policy/validate-policy-opts! {:initial-events [[:init]]})
                    nil
                    (catch #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core/ExceptionInfo) e
                      (ex-data e)))]
      (is (= :rf.error/ssr-missing-payload-policy (:rf.error/id data))
          "a missing policy throws the missing-policy error")
      (is (= :declare-payload-policy (:recovery data))
          "error ex-data names the recovery action"))))

;; ---- runtime-db projection (EP-0001) --------------------------------------

(def sample-runtime-db
  {:rf.runtime/machines {:snapshots {:auth.session/abc {:state :authenticated}}}
   :rf.runtime/routing  {:current            {:route-id :route/home}
                         ;; :pending-navigation is a local-subscribable
                         ;; runtime-db key but MUST NOT ride the SSR wire
                         ;; (fail-closed allowlist ships only :current).
                         :pending-navigation {:id "pn-1" :reason :can-leave}
                         ;; The nav-token / pending-nav counters are NOT
                         ;; runtime-db keys (host-side transient cache). A
                         ;; snapshot that carries one anyway has it stripped
                         ;; by the fail-closed allowlist, which this sample
                         ;; proves.
                         :nav-token-counter  7}
   ;; The per-frame elision DECLARATION registry. Its keys are
   ;; classified PATHS, and a key can embed a sensitive id
   ;; (`[:by-id "user-secret-id" :token]`) — so shipping it raw leaks both the
   ;; sensitive path STRUCTURE and the embedded id off-box. The SSR projection
   ;; OMITS this slot entirely (the client rebuilds its own registry on mount).
   :rf.runtime/elision  {:declarations           {[:auth :token] #{{:source :effect}}}
                         :sensitive-declarations {[:by-id "user-secret-id" :token]
                                                  #{{:source :effect}}}}
   :rf.runtime/ssr      {:hydration {:server-hash "h1"}}})

(deftest project-runtime-db-ships-durable-omits-transient
  (testing "project-runtime-db ships machines / route :current / ssr, OMITS the
            elision declaration registry, and drops the non-durable
            routing keys (:pending-navigation + any stale counter)"
    (let [slice (rf.ssr.payload-policy/project-runtime-db sample-runtime-db)]
      (is (= {:snapshots {:auth.session/abc {:state :authenticated}}}
             (:rf.runtime/machines slice))
          "machine snapshots ride the wire whole")
      (is (= {:current {:route-id :route/home}} (:rf.runtime/routing slice))
          "only the durable :current route slice rides; :pending-navigation + any counter are dropped")
      (is (= {:hydration {:server-hash "h1"}} (:rf.runtime/ssr slice))
          "SSR hydration metadata rides the wire"))))

;; ---- the elision declaration registry is OMITTED off-box ------------------
;;
;; EP-0025 / Spec 015 §SSR: "the per-frame registry is itself projected before
;; any view of it crosses the hydration wire (a classified path can embed a
;; sensitive id)". Shipping the registry RAW would leak both the sensitive PATH
;; STRUCTURE (which app-db paths the app classifies sensitive / large, and
;; where) and any sensitive id embedded in a declaration key. So
;; `project-runtime-db` OMITS the registry: the client rebuilds its own
;; identical registry from its registrations on mount, and the egress walk
;; reads the LIVE registry, never the wire copy. Confirm-by-mutation: add a
;; `(contains? … :rf.runtime/elision) (assoc :rf.runtime/elision …)` clause to
;; `project-runtime-db` and these turn red.

(deftest project-runtime-db-omits-elision-registry
  (testing "the :rf.runtime/elision declaration registry is NOT in
            the projected runtime-db slice (the raw registry never crosses the
            hydration wire — its keys are classified paths)"
    (let [slice (rf.ssr.payload-policy/project-runtime-db sample-runtime-db)]
      (is (not (contains? slice :rf.runtime/elision))
          "the elision declaration registry is omitted from the SSR slice")
      (is (not (str/includes? (pr-str slice) "sensitive-declarations"))
          "no sensitive-declarations registry sub-map survives in the slice")
      (is (not (str/includes? (pr-str slice) "user-secret-id"))
          "a sensitive id embedded in a classified declaration key does not leak")
      (is (not (str/includes? (pr-str slice) ":source :effect"))
          "the registry declaration records (the {:source :effect} marks) do not
           leak — the classified PATH STRUCTURE never crosses the wire"))))

(deftest project-runtime-db-nil-and-empty
  (testing "nil / empty / non-map runtime-db projects to nil so build-payload
            omits the optional :rf/runtime-db key"
    (is (nil? (rf.ssr.payload-policy/project-runtime-db nil)))
    (is (nil? (rf.ssr.payload-policy/project-runtime-db {})))
    (is (nil? (rf.ssr.payload-policy/project-runtime-db "not-a-map")))
    (is (nil? (rf.ssr.payload-policy/project-runtime-db {:rf.runtime/routing {:scroll-positions {}}}))
        "a runtime-db with ONLY transient routing keys projects to nil")))

(deftest build-payload-emits-runtime-db-when-present
  (testing "build-payload rides a non-nil :runtime-db opt as :rf/runtime-db,
            and omits the key when nil (client-only / no-server-runtime shape)"
    (let [rt-slice (rf.ssr.payload-policy/project-runtime-db sample-runtime-db)
          with-rt  (rf.ssr.payload-policy/build-payload
                     :rf/default {:public/page :dashboard} "h1"
                     {:version 1 :runtime-db rt-slice})
          without  (rf.ssr.payload-policy/build-payload
                     :rf/default {:public/page :dashboard} "h1"
                     {:version 1 :runtime-db nil})]
      (is (= rt-slice (:rf/runtime-db with-rt))
          "the projected runtime-db slice rides as :rf/runtime-db")
      (is (= {:public/page :dashboard} (:rf/app-db with-rt)))
      (is (not (contains? without :rf/runtime-db))
          "a nil runtime-db omits the optional key"))))

(deftest build-payload-wire-frame-id-decoupled
  (testing "the first arg is the WIRE :rf/frame-id, decoupled from
            the projection frame. A non-nil stable id is stamped; a nil id
            OMITS :rf/frame-id (the documented no-conflict shape for an
            anonymous per-request server frame — never a per-request gensym)"
    (let [stamped (rf.ssr.payload-policy/build-payload
                    :app/main {:public/page :dashboard} "h1" {:version 1})
          omitted (rf.ssr.payload-policy/build-payload
                    nil {:public/page :dashboard} "h1" {:version 1})]
      (is (= :app/main (:rf/frame-id stamped))
          "a stable wire id is stamped as :rf/frame-id")
      (is (not (contains? omitted :rf/frame-id))
          "a nil wire id omits :rf/frame-id (anonymous per-request frame)")
      ;; The rest of the canonical payload is unaffected by the wire-id choice.
      (is (= {:public/page :dashboard} (:rf/app-db omitted)))
      (is (= "h1" (:rf/render-hash omitted)))
      (is (= 1 (:rf/version omitted))))))

;; ---- :rf/version is canonically an INTEGER --------------------------------
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
               (rf.ssr.payload-policy/build-payload :rf/default {} "h" {:version 7})))))

  (testing "a whole-number STRING :version is tolerantly coerced to an int"
    (is (= 7 (:rf/version
               (rf.ssr.payload-policy/build-payload :rf/default {} "h" {:version "7"})))))

  (testing "a SEMVER-string :version is rejected → falls back to the v1 = 1 default,
            emitting exactly one :rf.ssr/invalid-version warning"
    (with-trace-recorder! [traces]
      (let [v (:rf/version
                (rf.ssr.payload-policy/build-payload :rf/default {} "h" {:version "1.0.0"}))]
        (is (= 1 v) "non-integer semver string is rejected, not shipped")
        (is (int? v))
        ;; The rejection is not silent: coerce-version emits a
        ;; :rf.ssr/invalid-version warning carrying the rejected source value and
        ;; a top-level :recovery :rejected-and-fell-back, so a stray non-integer
        ;; :version surfaces in dev/CI rather than defaulting quietly.
        ;; Dev-instrumentation arm (see ns docstring). The
        ;; REJECTION is pinned above and is what governs the wire; this is
        ;; the developer-facing announcement of it.
        (when rf.interop/debug-enabled?
          (let [hits (filterv #(= :rf.ssr/invalid-version (:operation %)) @traces)]
            (is (= 1 (count hits))
                (str "expected exactly one :rf.ssr/invalid-version trace; saw: "
                     (pr-str (mapv :operation @traces))))
            (when (seq hits)
              (let [ev (first hits)]
                (is (= :warning (:op-type ev)))
                (is (= "1.0.0" (-> ev :tags :value))
                    "the rejected source value rides the trace")
                (is (= :rejected-and-fell-back (:recovery ev))
                    ":recovery rides at top-level per Spec 009"))))))))

  (testing "no :version → the SSR-owned pattern-protocol constant (v1 = 1)"
    ;; There is no late-bind version hook — with no explicit
    ;; :version opt, resolve-version falls back to the SSR artefact's
    ;; compiled-in constant (the SAME value the client reads).
    (let [v (:rf/version (rf.ssr.payload-policy/build-payload :rf/default {} "h" {}))]
      (is (= rf.ssr.payload-policy/pattern-protocol-version v)
          "absent :version opt → the SSR-owned constant")
      (is (int? v)))))

;; ---- build-payload output conforms to the HydrationPayload schema --------
;;
;; The canonical v1 HydrationPayload schema (Spec-Schemas
;; §:rf/hydration-payload). Pinned inline here so a build-payload output
;; that drifts from the published schema (e.g. a non-integer :rf/version,
;; a missing required key) turns this test red. The schema is an OPEN map
;; (additive optional keys are tolerated per the v1 contract).
;;
;; This def is the ONLY place in the corpus that types the
;; payload's slots: `build-payload`'s emitted shape is otherwise unvalidated
;; (the spec section's `> Conformance:` pointer, `ssr_hydration_test.clj`,
;; exercises the CONSUMER — `:rf/hydrate` installing / failing closed — never
;; the producer's slot types). Calling it canonical is therefore a claim worth
;; keeping true, and every slot below is spelled exactly as Spec-Schemas
;; spells it, with ONE documented deviation: Spec-Schemas types
;; `:rf/runtime-db` as the registry ref `:rf/runtime-db` (resolving to
;; `RuntimeDb`, a map of optional `:rf.runtime/*` sub-containers), and
;; resolving a registry ref here would need a malli registry in this test ns.
;; The base type `:map` is carried instead: weaker than the ref, but it holds
;; the cardinality the ref holds — a present-and-nil key is not a spelling of
;; absence.

(def HydrationPayload
  [:map
   [:rf/version         :int]
   ;; `:rf/frame-id` is OPTIONAL: an anonymous per-request server
   ;; frame omits it (the documented no-conflict shape), and it is stamped only
   ;; when the deployment names a stable wire id.
   [:rf/frame-id        {:optional true} :keyword]
   [:rf/app-db          :any]
   ;; `:map`, NOT `[:maybe :map]` (see the deviation note above for
   ;; why the base type rather than the `:rf/runtime-db` ref). `build-payload`
   ;; guards this arm with `(some? runtime-db)`, so a frame that hydrates no
   ;; framework runtime state OMITS the key — the shape Spec-Schemas describes
   ;; as "absent on a frame that hydrates no framework runtime state". A
   ;; `[:maybe :map]` slot would also admit a present-and-nil key, which is a
   ;; second spelling of absence the canonical schema does not have.
   [:rf/runtime-db      {:optional true} :map]
   [:rf/ssr-rendered-at {:optional true} :int]
   ;; `:string`, NOT `[:maybe :string]`. Spec-Schemas types the
   ;; slot `{:optional true} :string`, so a present-and-nil `:rf/render-hash`
   ;; is not a legal spelling of absence: an ADOPTION-TIER root (compiled
   ;; native UIx, Fresco) carries no hash at either end and
   ;; needs the key OMITTED. A `[:maybe :string]` slot would admit the
   ;; forbidden shape, and this schema could not then prove the contract it
   ;; calls canonical.
   [:rf/render-hash     {:optional true} :string]
   ;; `build-payload` emits `:rf/head-hash`, and the map is OPEN, so a key
   ;; missing from this def would validate by never being looked at: not
   ;; loose, SILENT. It is the SEPARATE head-model channel —
   ;; `:string` like its `:rf/render-hash` sibling, and omitted
   ;; on nil for the explicit-`:head`-STRING shape where the server knows the
   ;; head is not client-reconstructible.
   [:rf/head-hash       {:optional true} :string]
   ;; `:string`, NOT `[:maybe :string]`, for the same reason as
   ;; `:rf/render-hash`: `build-payload` omits the key when the caller's app
   ;; does not participate in the schema-digest check, so present-and-nil is
   ;; not a shape the builder can produce and not one Spec-Schemas admits.
   [:rf/schema-digest   {:optional true} :string]])

(deftest build-payload-conforms-to-hydration-payload-schema
  (testing "build-payload output validates against the canonical
            HydrationPayload schema, with :rf/version as a true integer"
    (let [rt-slice (rf.ssr.payload-policy/project-runtime-db sample-runtime-db)]
      (testing "full two-partition payload (integer version)"
        (let [payload (rf.ssr.payload-policy/build-payload
                        :rf/default {:public/page :dashboard} "deadbeef"
                        {:version 7 :schema-digest "digest-abc" :runtime-db rt-slice})]
          (is (m/validate HydrationPayload payload)
              (str "payload must conform to HydrationPayload; explain: "
                   (pr-str (m/explain HydrationPayload payload))))
          (is (int? (:rf/version payload)) ":rf/version is a true integer")))

      (testing "a SEMVER-string :version still yields a schema-conforming payload
                (rejected + coerced to the integer default)"
        (let [payload (rf.ssr.payload-policy/build-payload
                        :rf/default {:public/page :dashboard} "deadbeef"
                        {:version "1.0.0" :runtime-db rt-slice})]
          (is (m/validate HydrationPayload payload)
              (str "even with a bad :version source, the assembled payload "
                   "conforms (integer fallback); explain: "
                   (pr-str (m/explain HydrationPayload payload))))
          (is (int? (:rf/version payload)))))

      (testing "minimal client-only payload (no runtime-db / schema-digest)"
        (let [payload (rf.ssr.payload-policy/build-payload
                        :rf/default {} "h" {})]
          (is (m/validate HydrationPayload payload)
              (str "minimal payload must conform; explain: "
                   (pr-str (m/explain HydrationPayload payload)))))))))

;; ---- the hash channel is OPTIONAL at the shared builder -------------------
;;
;; `build-payload` is shared verbatim by the non-streaming and streaming SSR
;; paths, so the adoption-tier contract belongs here and not only at the one
;; Fresco caller that exercises it today. Per Spec 011 §Hydration-mismatch
;; detection the hash channel is the HICCUP tier's: a hiccup-tier host hands a
;; real hash and it rides the wire, while a native UIx
;; or Fresco root verifies by React adoption and hands nil. Nil must leave
;; the key ABSENT — a present-and-nil key is a degenerate value wearing the
;; shape of evidence, and the `:string` slot does not admit it.

(deftest build-payload-render-hash-is-optional
  (testing "a real hash is preserved verbatim (hiccup tier)"
    (let [payload (rf.ssr.payload-policy/build-payload
                    :rf/default {:public/page :dashboard} "deadbeef" {})]
      (is (= "deadbeef" (:rf/render-hash payload))
          "a supplied hash rides the payload unchanged")))

  (testing "a nil hash OMITS the key rather than stamping nil (adoption tier)"
    (let [payload (rf.ssr.payload-policy/build-payload
                    :rf/default {:public/page :dashboard} nil {})]
      (is (not (contains? payload :rf/render-hash))
          "a nil render-hash omits :rf/render-hash entirely")
      (is (= {:public/page :dashboard} (:rf/app-db payload))
          "the rest of the payload is unaffected by the omission")
      (is (m/validate HydrationPayload payload)
          (str "a hashless adoption-tier payload conforms; explain: "
               (pr-str (m/explain HydrationPayload payload))))))

  (testing "a present-and-nil :rf/render-hash is REJECTED by the schema — what
            makes the omission load-bearing rather than cosmetic"
    (is (not (m/validate HydrationPayload
                         (assoc (rf.ssr.payload-policy/build-payload :rf/default {} nil {})
                                :rf/render-hash nil)))
        "a hand-stamped nil :rf/render-hash must not validate")))

;; ---- the other three optional slots, same contract ------------------------
;;
;; `:rf/render-hash` above is one of four optional slots `build-payload`
;; assembles through `cond->` arms. Each of the three below meets the same
;; three-part case the hash channel does — real value preserved, nil OMITS the
;; key, hand-stamped nil REJECTED (for `:rf/runtime-db` the first two parts are
;; `build-payload-emits-runtime-db-when-present`) — because a typed slot with
;; no assertion against it is a claim, not a guard. A conforming payload
;; carrying each real value is `build-payload-conforms-to-hydration-payload-schema`.

(deftest build-payload-head-hash-is-optional
  ;; `build-payload` emits
  ;; `:rf/head-hash` (the SEPARATE client-reconstructible
  ;; head-model channel, not covered by `:rf/render-hash`), and because the
  ;; schema is an OPEN map an untyped key would validate by never being
  ;; examined: any value — nil, an int, a map — would ride through. These
  ;; assertions look at it.
  (testing "a real head hash is preserved verbatim"
    (let [payload (rf.ssr.payload-policy/build-payload
                    :rf/default {:public/page :dashboard} "body-h"
                    {:head-hash "head-h"})]
      (is (= "head-h" (:rf/head-hash payload))
          "a supplied head hash rides the payload unchanged")
      (is (= "body-h" (:rf/render-hash payload))
          "the head channel is SEPARATE from the body render-hash channel")
      (is (m/validate HydrationPayload payload)
          (str "a head-hashed payload conforms; explain: "
               (pr-str (m/explain HydrationPayload payload))))))

  (testing "a nil head hash OMITS the key (the explicit-:head-STRING shape,
            where the head is not client-reconstructible)"
    (let [payload (rf.ssr.payload-policy/build-payload
                    :rf/default {:public/page :dashboard} "body-h"
                    {:head-hash nil})]
      (is (not (contains? payload :rf/head-hash))
          "a nil head-hash omits :rf/head-hash entirely")
      (is (= "body-h" (:rf/render-hash payload))
          "omitting the head channel leaves the body channel intact")
      (is (m/validate HydrationPayload payload)
          (str "a head-hashless payload conforms; explain: "
               (pr-str (m/explain HydrationPayload payload))))))

  (testing "a present-and-nil :rf/head-hash is REJECTED by the schema"
    (is (not (m/validate HydrationPayload
                         (assoc (rf.ssr.payload-policy/build-payload :rf/default {} "h" {})
                                :rf/head-hash nil)))
        "a hand-stamped nil :rf/head-hash must not validate"))

  (testing "a non-string :rf/head-hash is REJECTED — the point of typing a slot
            the OPEN map would otherwise let through unexamined"
    (is (not (m/validate HydrationPayload
                         (assoc (rf.ssr.payload-policy/build-payload :rf/default {} "h" {})
                                :rf/head-hash 42)))
        "an integer head hash must not validate")))

(deftest build-payload-schema-digest-is-optional
  (testing "a real digest is preserved verbatim"
    (let [payload (rf.ssr.payload-policy/build-payload
                    :rf/default {:public/page :dashboard} "h"
                    {:schema-digest "digest-abc"})]
      (is (= "digest-abc" (:rf/schema-digest payload))
          "a supplied digest rides the payload unchanged")))

  (testing "a nil digest OMITS the key (the app does not participate in the
            schema-digest check)"
    (let [payload (rf.ssr.payload-policy/build-payload
                    :rf/default {:public/page :dashboard} "h"
                    {:schema-digest nil})]
      (is (not (contains? payload :rf/schema-digest))
          "a nil schema-digest omits :rf/schema-digest entirely")
      (is (m/validate HydrationPayload payload)
          (str "a digestless payload conforms; explain: "
               (pr-str (m/explain HydrationPayload payload))))))

  (testing "a present-and-nil :rf/schema-digest is REJECTED by the schema"
    (is (not (m/validate HydrationPayload
                         (assoc (rf.ssr.payload-policy/build-payload :rf/default {} "h" {})
                                :rf/schema-digest nil)))
        "a hand-stamped nil :rf/schema-digest must not validate")))
