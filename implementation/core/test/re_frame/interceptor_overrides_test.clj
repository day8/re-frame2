(ns re-frame.interceptor-overrides-test
  "Spec/002 §`:interceptor-overrides` (lines 1108-1139)
  + §Per-frame and per-call overrides §merge. REFERENCE-ONLY (EP-0022):
  chains carry interceptor REFS, and override replacements are a REF (or
  `nil` to remove) — a value-valued override is rejected.

  Per-call `{:interceptor-overrides {:my-app/logging nil}}` AND
  per-frame `(make-frame {:id :f :interceptor-overrides {...}})` MUST walk
  the assembled interceptor chain and substitute entries by canonical
  reference:

    * `ref -> nil`   removes the interceptor.
    * `ref -> <ref>` replaces the entry with another registered ref.
    * ref not present in chain  leaves entry untouched.

  Per-call wins over per-frame on key conflict (matches `:fx-overrides`
  precedence per Spec 002 §Per-frame and per-call overrides §merge).

  This test pins the consume-side wiring: an `:interceptor-overrides` key
  accepted on the envelope must actually act on the assembled chain."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as rf.frame]
            [re-frame.identity :as rf.identity]
            [re-frame.interceptor :as rf.interceptor]
            [re-frame.interceptor-registry :as rf.interceptor-registry]
            [re-frame.registrar :as rf.registrar]
            [re-frame.schemas :as rf.schemas]
            [re-frame.flows :as rf.flows]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.trace.tooling :as rf.trace.tooling]))

;; ---- fixtures -------------------------------------------------------------

(defn reset-runtime [test-fn]
  (rf.registrar/clear-all!)
  (reset! rf.frame/frames {})
  (rf.flows/reset-flows!)
  (rf.schemas/clear-schemas-by-frame!)
  (rf.trace.tooling/clear-listeners!)
  (rf/init! rf.substrate.plain-atom/adapter)
  (require 're-frame.routing :reload)
  ;; `init!` does not synthesise `:rf/default`, and
  ;; framework operation surfaces require a carried frame stamp. Register
  ;; `:rf/default` + pin it as the body's ambient scope (the carried-
  ;; invariant equivalent of `(with-frame :rf/default …)`); explicit
  ;; `{:frame …}` opts in the test bodies still win.
  (rf/make-frame {:id :rf/default})
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
;;
;; Per-call removal (`ref -> nil`) and replacement (`ref -> <ref>`), and the
;; per-frame tier, are pinned by the always-on chain witnesses in
;; `re-frame.interceptor-override-summary-trace-test`
;; (`summary-classifies-each-override`, `per-frame-override-surfaces-on-summary`).

(deftest value-valued-override-replacement-rejected
  (testing "an inline interceptor VALUE as an override replacement is rejected (overrides are reference-only)"
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
                               {::log-y (rf.interceptor/->interceptor*
                                          :id ::inline :before identity)}}))))))

;; ---- merge order: per-call wins over per-frame -----------------------------

(deftest per-call-overrides-per-frame-on-key-conflict
  (testing "when the same ref appears in per-call AND per-frame overrides, per-call wins"
    (let [log (atom [])]
      (reg-logger! log ::log)
      (rf/reg-interceptor ::frame-stub
        {:before (fn [ctx] (swap! log conj :frame-stub) ctx)})
      (rf/reg-interceptor ::call-stub
        {:before (fn [ctx] (swap! log conj :call-stub) ctx)})
      (rf/make-frame {:id :test/scoped :interceptor-overrides {::log ::frame-stub}})
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

;; ---- override-key-matches? direct unit (all arms) ----------------------------
;;
;; `override-key-matches?` (interceptor_registry.cljc) keys an
;; `:interceptor-overrides` map entry against a resolved chain entry per Spec
;; 002 §`:interceptor-overrides` exact-reference matching. The override walk
;; exercises it indirectly; this pins every arm directly. The entry carries its
;; AUTHORED ref under `rf.interceptor-registry/authored-ref-key` (`:rf/interceptor-ref`).

(deftest override-key-matches?-all-arms
  (let [K rf.interceptor-registry/authored-ref-key]
    (testing "override-key-matches? for every documented arm"

      (testing "bare-keyword key"
        (is (true? (rf.interceptor-registry/override-key-matches? :my/ic {:id :my/ic K :my/ic}))
            "matches a bare-keyword AUTHORED ref")
        (is (true? (rf.interceptor-registry/override-key-matches? :my/ic {:id :my/ic}))
            "matches the entry :id when there is no authored ref (inline / resolver-stamped)")
        (is (true? (rf.interceptor-registry/override-key-matches? :my/ic {:id :my/ic K [:my/ic [:cart]]}))
            "matches the :id even when the authored ref is a vector (factory-built)")
        (is (false? (rf.interceptor-registry/override-key-matches? :my/ic {:id :other K :other}))
            "does not match a different id / authored ref"))

      (testing "[id arg] 2-vector key"
        (is (true? (rf.interceptor-registry/override-key-matches? [:my/ic [:cart]]
                                                   {:id :my/ic K [:my/ic [:cart]]}))
            "matches the entry whose authored ref is ref= to the exact [id arg]")
        (is (false? (rf.interceptor-registry/override-key-matches? [:my/ic [:cart]]
                                                    {:id :my/ic K [:my/ic [:cart :items]]}))
            "does NOT match a sibling [id arg] with a different arg")
        (is (false? (rf.interceptor-registry/override-key-matches? [:my/ic [:cart]] {:id :my/ic}))
            "does NOT match an entry with no authored ref (a vector key needs one)"))

      (testing ":else arm — a structurally-invalid key never matches"
        (is (false? (rf.interceptor-registry/override-key-matches? "not-a-ref" {:id :my/ic}))
            "a non-keyword non-2-vector key falls through to false")
        (is (false? (rf.interceptor-registry/override-key-matches? [:my/ic :a :b] {:id :my/ic}))
            "a 3-vector key is not an [id arg] ref and falls through to false")))))

;; ---- ref= fail-soft catch (unit) ------------------------------------------
;;
;; `ref=` (interceptor_registry.cljc) returns false, rather than throwing, when
;; canonicalization throws on a non-EDN arg. A serializable override key never
;; reaches that arm through the override walk, so it is pinned at the unit level.

(deftest ref=-fail-soft-on-non-edn-arg
  (testing "When canonicalization throws on a non-EDN arg,
            ref= fail-softs to false rather than letting the throw escape the
            override walk."
    (let [f1 (fn [] :a)
          f2 (fn [] :b)]
      ;; Sanity: the raw canonical-identity check DOES throw on a function arg
      ;; (an unsupported host value), so the catch arm is genuinely exercised.
      (is (thrown? clojure.lang.ExceptionInfo
            (rf.identity/identical-identity? f1 f2))
          "identical-identity? throws on a non-EDN (function) arg")
      ;; ref= must NOT propagate that throw — it fail-softs to false.
      (is (false? (rf.interceptor-registry/ref= [:my/ic f1] [:my/ic f2]))
          "ref= returns false (fail-soft) rather than propagating the canonicalization throw"))))
