(ns re-frame.interceptor-overrides-test
  "Per rf2-4jci1.2 — Spec/002 §`:interceptor-overrides` (lines 1108-1139)
  + §Per-frame and per-call overrides §merge. REFERENCE-ONLY since the
  EP-0022 flip (rf2-0adhqs.9): chains carry interceptor REFS, and override
  replacements are a REF (or `nil` to remove) — value-valued overrides are
  retired.

  Per-call `{:interceptor-overrides {:my-app/logging nil}}` AND
  per-frame `(reg-frame :f {:interceptor-overrides {...}})` MUST walk
  the assembled interceptor chain and substitute entries by canonical
  reference:

    * `ref -> nil`   removes the interceptor.
    * `ref -> <ref>` replaces the entry with another registered ref.
    * ref not present in chain  leaves entry untouched.

  Per-call wins over per-frame on key conflict (matches `:fx-overrides`
  precedence per Spec 002 §Per-frame and per-call overrides §merge).

  Pre-fix the `:interceptor-overrides` key was accepted by
  `build-envelope` and stored on the envelope but no code path read
  it back. This test pins the consume-side wiring."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.identity :as identity]
            [re-frame.interceptor :as interceptor]
            [re-frame.interceptor-registry :as icpt-reg]
            [re-frame.registrar :as registrar]
            [re-frame.schemas :as schemas]
            [re-frame.flows :as flows]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.trace :as trace]))

;; ---- fixtures -------------------------------------------------------------

(defn reset-runtime [test-fn]
  (registrar/clear-all!)
  (reset! frame/frames {})
  (flows/reset-flows!)
  (schemas/clear-schemas-by-frame!)
  (trace/clear-listeners!)
  (rf/init! plain-atom/adapter)
  (require 're-frame.routing :reload)
  ;; EP-0002 (rf2-9o48ih): `init!` no longer synthesises `:rf/default`;
  ;; framework operation surfaces require a carried frame stamp. Register
  ;; `:rf/default` + pin it as the body's ambient scope (the carried-
  ;; invariant equivalent of `(with-frame :rf/default …)`); explicit
  ;; `{:frame …}` opts in the test bodies still win.
  (rf/reg-frame :rf/default {})
  (rf/with-frame :rf/default
    (test-fn)))

(use-fixtures :each reset-runtime)

;; ---- helpers --------------------------------------------------------------
;;
;; EP-0022 reference-only: register a logging interceptor under `id` and return
;; `id` (the chain REF). Chains carry refs; override keys/replacements are refs.

(defn- reg-logger! [log id]
  (rf/reg-interceptor id
    {:before (fn [ctx] (swap! log conj [id :before]) ctx)
     :after  (fn [ctx] (swap! log conj [id :after]) ctx)})
  id)

;; ---- per-call :interceptor-overrides ---------------------------------------

(deftest per-call-interceptor-override-removes-by-ref
  (testing "per-call {:ref nil} drops the interceptor from the chain"
    (let [log (atom [])]
      (reg-logger! log ::log-a)
      (reg-logger! log ::log-b)
      (rf/reg-event :test/run
        {:interceptors [::log-a ::log-b]}
        (fn [{:keys [db]} _] {:db (assoc db :ran? true)}))

      (rf/dispatch-sync [:test/run]
                        {:interceptor-overrides {::log-a nil}})

      (is (= [[::log-b :before]
              [::log-b :after]]
             @log)
          "::log-a was removed; ::log-b's before/after still fired"))))

(deftest per-call-interceptor-override-replaces-by-ref
  (testing "per-call {:ref <other-ref>} replaces the interceptor"
    (let [log (atom [])]
      (reg-logger! log ::log-x)
      (rf/reg-interceptor ::stub-x
        {:before (fn [ctx] (swap! log conj [::stub :fired]) ctx)})
      (rf/reg-event :test/run
        {:interceptors [::log-x]}
        (fn [{:keys [db]} _] {:db db}))

      (rf/dispatch-sync [:test/run]
                        {:interceptor-overrides {::log-x ::stub-x}})

      (is (= [[::stub :fired]] @log)
          "the ::stub-x ref fired in place of the original ::log-x interceptor"))))

(deftest value-valued-override-replacement-rejected
  (testing "an inline interceptor VALUE as an override replacement is rejected (value-valued overrides retired)"
    (let [log (atom [])]
      (reg-logger! log ::log-y)
      (rf/reg-event :test/run
        {:interceptors [::log-y]}
        (fn [{:keys [db]} _] {:db db}))
      (is (thrown-with-msg?
            clojure.lang.ExceptionInfo
            #":rf\.error/interceptor-override-invalid"
            (rf/dispatch-sync [:test/run]
                              {:interceptor-overrides
                               {::log-y (interceptor/->interceptor*
                                          :id ::inline :before identity)}}))))))

;; ---- per-frame :interceptor-overrides --------------------------------------

(deftest per-frame-interceptor-override-applies-to-every-dispatch
  (testing "per-frame :interceptor-overrides walks the chain on every dispatch"
    (let [log (atom [])]
      (reg-logger! log ::log-a)
      (reg-logger! log ::log-b)
      (rf/reg-frame :test/silent
        {:interceptor-overrides {::log-a nil}})
      (rf/reg-event :test/run
        {:interceptors [::log-a ::log-b]}
        (fn [{:keys [db]} _] {:db db}))

      (rf/dispatch-sync [:test/run] {:frame :test/silent})

      (is (= [[::log-b :before]
              [::log-b :after]]
             @log)
          "::log-a was removed by the frame-config override"))))

;; ---- merge order: per-call wins over per-frame -----------------------------

(deftest per-call-overrides-per-frame-on-key-conflict
  (testing "when the same ref appears in per-call AND per-frame overrides, per-call wins"
    (let [log (atom [])]
      (reg-logger! log ::log)
      (rf/reg-interceptor ::frame-stub
        {:before (fn [ctx] (swap! log conj :frame-stub) ctx)})
      (rf/reg-interceptor ::call-stub
        {:before (fn [ctx] (swap! log conj :call-stub) ctx)})
      (rf/reg-frame :test/scoped
        {:interceptor-overrides {::log ::frame-stub}})
      (rf/reg-event :test/run
        {:interceptors [::log]}
        (fn [{:keys [db]} _] {:db db}))

      (rf/dispatch-sync [:test/run]
                        {:frame :test/scoped
                         :interceptor-overrides {::log ::call-stub}})

      (is (= [:call-stub] @log)
          "per-call override won over per-frame override on key conflict"))))

;; ---- ids absent from override map pass through unchanged -------------------

(deftest unmatched-ids-pass-through-unchanged
  (testing "interceptors whose ref is not a key in the override map fire normally"
    (let [log (atom [])]
      (reg-logger! log ::log-a)
      (reg-logger! log ::log-b)
      (rf/reg-event :test/run
        {:interceptors [::log-a ::log-b]}
        (fn [{:keys [db]} _] {:db db}))

      (rf/dispatch-sync [:test/run]
                        {:interceptor-overrides {::log-c nil}})    ;; ::log-c not in chain

      (is (= [[::log-a :before]
              [::log-b :before]
              [::log-b :after]
              [::log-a :after]]
             @log)
          "both interceptors fired in standard before/after sandwich"))))

;; ---- rf2-nnpimw — per-FRAME value-rejection (the per-call arm is covered above) --
;;
;; `override-replacement` (router.cljc) rejects an inline interceptor VALUE
;; replacement (non-ref, non-nil) with `:rf.error/interceptor-override-invalid`
;; — value-valued overrides retired (EP-0022). The per-call arm is pinned by
;; `value-valued-override-replacement-rejected` above; the per-FRAME override
;; path runs the SAME `override-replacement`, but the per-frame value-rejection
;; arm was not pinned. This pins it.

(deftest per-frame-value-valued-override-replacement-rejected
  (testing "an inline interceptor VALUE as a per-FRAME override replacement is rejected
            (value-valued overrides retired — same as the per-call arm)"
    (let [log (atom [])]
      (reg-logger! log ::log-pf)
      (rf/reg-frame :test/bad-frame-override
        {:interceptor-overrides
         {::log-pf (interceptor/->interceptor*
                     :id ::pf-inline :before identity)}})
      (rf/reg-event :test/run
        {:interceptors [::log-pf]}
        (fn [{:keys [db]} _] {:db db}))
      (is (thrown-with-msg?
            clojure.lang.ExceptionInfo
            #":rf\.error/interceptor-override-invalid"
            (rf/dispatch-sync [:test/run] {:frame :test/bad-frame-override}))
          "the per-frame value-valued replacement is rejected at chain assembly"))))

;; ---- rf2-nnpimw — override-key-matches? direct unit (all arms) ---------------
;;
;; `override-key-matches?` (interceptor_registry.cljc) keys an
;; `:interceptor-overrides` map entry against a resolved chain entry per Spec
;; 002 §`:interceptor-overrides` exact-reference matching. The override walk
;; exercises it indirectly; this pins every arm directly. The entry carries its
;; AUTHORED ref under `icpt-reg/authored-ref-key` (`:rf/interceptor-ref`).

(deftest override-key-matches?-all-arms
  (let [K icpt-reg/authored-ref-key]
    (testing "Per rf2-nnpimw — override-key-matches? for every documented arm"

      (testing "bare-keyword key"
        (is (true? (icpt-reg/override-key-matches? :my/ic {:id :my/ic K :my/ic}))
            "matches a bare-keyword AUTHORED ref")
        (is (true? (icpt-reg/override-key-matches? :my/ic {:id :my/ic}))
            "matches the entry :id when there is no authored ref (inline / resolver-stamped)")
        (is (true? (icpt-reg/override-key-matches? :my/ic {:id :my/ic K [:my/ic [:cart]]}))
            "matches the :id even when the authored ref is a vector (factory-built)")
        (is (false? (icpt-reg/override-key-matches? :my/ic {:id :other K :other}))
            "does not match a different id / authored ref"))

      (testing "[id arg] 2-vector key"
        (is (true? (icpt-reg/override-key-matches? [:my/ic [:cart]]
                                                   {:id :my/ic K [:my/ic [:cart]]}))
            "matches the entry whose authored ref is ref= to the exact [id arg]")
        (is (false? (icpt-reg/override-key-matches? [:my/ic [:cart]]
                                                    {:id :my/ic K [:my/ic [:cart :items]]}))
            "does NOT match a sibling [id arg] with a different arg")
        (is (true? (icpt-reg/override-key-matches? [:my/ic {:a 1 :z 2}]
                                                   {:id :my/ic K [:my/ic {:z 2 :a 1}]}))
            "matches canonically — key-reordered map args are ref=")
        (is (false? (icpt-reg/override-key-matches? [:my/ic [:cart]] {:id :my/ic}))
            "does NOT match an entry with no authored ref (a vector key needs one)"))

      (testing ":else arm — a structurally-invalid key never matches"
        (is (false? (icpt-reg/override-key-matches? "not-a-ref" {:id :my/ic}))
            "a non-keyword non-2-vector key falls through to false")
        (is (false? (icpt-reg/override-key-matches? [:my/ic :a :b] {:id :my/ic}))
            "a 3-vector key is not an [id arg] ref and falls through to false")))))

;; ---- rf2-iz3v0r — ref= canonical-bytes fallback + fail-soft catch (unit) -----
;;
;; `ref=` (interceptor_registry.cljc) has two undertested arms: (1) the
;; canonical-bytes fallback (via `identity/identical-identity?`) that makes two
;; arg spellings differing only in map-key order match — exercised end-to-end by
;; the override walk but not pinned at the unit level; (2) the fail-soft catch
;; that returns false (not throws) when canonicalization throws on a non-EDN
;; arg.

(deftest ref=-fast-structural-and-canonical-fallback
  (testing "Per rf2-iz3v0r — the fast `=` path and the canonical-bytes fallback"
    (testing "fast structural `=` short-circuit"
      (is (true? (icpt-reg/ref= :my/ic :my/ic))
          "two identical bare-keyword refs are =")
      (is (true? (icpt-reg/ref= [:my/ic [:cart]] [:my/ic [:cart]]))
          "two identical [id arg] vectors are ="))

    (testing "canonical-bytes fallback — map-key order is irrelevant"
      (is (true? (icpt-reg/ref= [:my/ic {:a 1 :z 2}] [:my/ic {:z 2 :a 1}]))
          "two [id arg] refs differing ONLY in map-key order are ref= via canonical bytes"))

    (testing "genuinely different args are NOT equal"
      (is (false? (icpt-reg/ref= [:my/ic {:a 1}] [:my/ic {:a 2}]))
          "different arg values are not ref=")
      (is (false? (icpt-reg/ref= :my/ic :other/ic))
          "different bare keywords are not ref="))))

(deftest ref=-fail-soft-on-non-edn-arg
  (testing "Per rf2-iz3v0r — when canonicalization throws on a non-EDN arg,
            ref= fail-softs to false rather than letting the throw escape the
            override walk."
    (let [f1 (fn [] :a)
          f2 (fn [] :b)]
      ;; Sanity: the raw canonical-identity check DOES throw on a function arg
      ;; (an unsupported host value), so the catch arm is genuinely exercised.
      (is (thrown? clojure.lang.ExceptionInfo
            (identity/identical-identity? f1 f2))
          "identical-identity? throws on a non-EDN (function) arg")
      ;; ref= must NOT propagate that throw — it fail-softs to false.
      (is (false? (icpt-reg/ref= [:my/ic f1] [:my/ic f2]))
          "ref= returns false (fail-soft) rather than propagating the canonicalization throw"))))
