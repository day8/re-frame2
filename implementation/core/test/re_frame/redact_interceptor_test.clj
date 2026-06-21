(ns re-frame.redact-interceptor-test
  "Per rf2-461sp — `(privacy/redact-interceptor paths)` positional interceptor.

  The third composition site for `:sensitive?` (per [Security.md
  §Behavioural MUSTs across the privacy surface](spec/Security.md)):

    1. Handler body sees the UNREDACTED payload via `:event` coeffect.
    2. Trace surface (`:run-start` / `:run-end` / `:rf.event/db-changed` /
       `:rf.error/handler-exception`) sees `:rf/redacted` at the named
       payload keys.
    3. Composes orthogonally with registration-meta `:sensitive? true`
       (which stamps `:sensitive? true` on every emitted trace event).
    4. Composes additively with schema-derived redaction
       (`:rf/schema-redaction` interceptor; the user-installed
       interceptor extends `:rf/redacted-event` rather than overwriting).
    5. Composes independently with epoch `:redact-fn` (the per-record
       hook reads already-scrubbed trace events).

  Negative coverage: handlers without `redact-interceptor` see no redaction;
  unrelated keys pass through; non-map payload shapes pass through; an
  empty path scrubs the entire payload."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.elision :as elision]
            [re-frame.frame :as frame]
            [re-frame.privacy :as privacy]
            [re-frame.registrar :as registrar]
            [re-frame.schemas :as schemas]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.trace :as trace]))

(defn- reset-runtime [test-fn]
  (registrar/clear-all!)
  (reset! frame/frames {})
  (schemas/clear-schemas-by-frame!)
  (trace/clear-listeners!)
  (rf/init! plain-atom/adapter)
  (require 're-frame.elision :reload)
  (require 're-frame.schemas :reload)
  ;; EP-0002 (rf2-9o48ih): `init!` no longer synthesises `:rf/default`;
  ;; framework operation surfaces require a carried frame stamp. Register
  ;; `:rf/default` + pin it as the body's ambient scope (the carried-
  ;; invariant equivalent of `(with-frame :rf/default …)`); explicit
  ;; `{:frame …}` opts in the test bodies still win.
  (rf/reg-frame :rf/default {})
  (rf/with-frame :rf/default
    (test-fn)))

(use-fixtures :each reset-runtime)

(defn- record-traces
  [body-fn]
  (let [seen (atom [])]
    (rf/register-listener! :trace ::rec (fn [ev] (swap! seen conj ev)))
    (try (body-fn)
         (finally (rf/unregister-listener! :trace ::rec)))
    @seen))

(defn- events-of [evs op]
  (filterv #(= op (:operation %)) evs))

(defn- run-start-of [evs]
  (first (filterv #(= :rf.event/run-start (:operation %)) evs)))

;; ---- public-API + interceptor-shape sanity --------------------------------

(deftest redact-interceptor-is-not-on-the-public-facade
  ;; EP-0015 §7 (rf2-mngp4o): `redact-interceptor` is REMOVED from the
  ;; public `re-frame.core` façade. It survives only as the internal
  ;; `re-frame.privacy/redact-interceptor` helper (router plumbing + this
  ;; test). The negative assertion pins the demotion so a re-export would
  ;; fail loudly.
  (is (nil? (ns-resolve 're-frame.core 'redact-interceptor))
      "EP-0015 §7: redact-interceptor must NOT be published from re-frame.core")
  (is (fn? privacy/redact-interceptor)
      "the internal re-frame.privacy/redact-interceptor helper still exists"))

(deftest redact-interceptor-returns-interceptor-with-paths
  (testing "the returned interceptor map exposes its paths on `:paths` so the
            router can fold them into the pre-chain trace projection"
    (let [paths [[:password] [:token]]
          icpt  (privacy/redact-interceptor paths)]
      (is (map? icpt))
      (is (= :rf/redact-interceptor (:id icpt)))
      (is (= paths (:paths icpt)))
      (is (fn? (:before icpt))))))

;; ---- scope-only redaction: handler sees raw, trace sees scrubbed ----------

(deftest handler-sees-unredacted-trace-sees-redacted
  (testing "the handler's `:event` coeffect is the raw payload; every trace
            surface that uses `redacted-event-from-ctx` sees the scrub"
    (let [seen (atom nil)]
      (rf/reg-interceptor* :rf/redact-interceptor
        (privacy/redact-interceptor [[:password] [:token]]))
      (rf/reg-event :auth/login
        {:interceptors [:rf/redact-interceptor]}
        (fn [{:keys [db]} [_ payload]]
          (reset! seen payload)
          {:db (assoc db :last-login payload)}))
      (let [evs        (record-traces
                         #(rf/dispatch-sync
                            [:auth/login {:username "ada"
                                          :password "shh"
                                          :token    "abc123"}]))
            run-start  (run-start-of evs)
            db-changed (first (events-of evs :rf.event/db-changed))]
        (is (= {:username "ada" :password "shh" :token "abc123"} @seen)
            "handler body sees the raw payload — `redact-interceptor` is a
             trace-surface scrub, not a handler-input rewrite")
        (is (= :rf/redacted (get-in run-start [:tags :rf.event/v 1 :password])))
        (is (= :rf/redacted (get-in run-start [:tags :rf.event/v 1 :token])))
        (is (= "ada" (get-in run-start [:tags :rf.event/v 1 :username]))
            "non-declared keys pass through to the trace surface")
        (is (= :rf/redacted (get-in db-changed [:tags :rf.event/v 1 :password])))
        (is (= :rf/redacted (get-in db-changed [:tags :rf.event/v 1 :token])))
        (is (= "ada" (get-in db-changed [:tags :rf.event/v 1 :username])))))))

(deftest declared-key-is-sentineled-even-when-absent
  (testing "the redaction is explicit: a top-level declared key is always
            written as `:rf/redacted`, even when absent from the source
            payload. Opt-in privacy is additive, not conditional;
            consistent with the schema-redaction helper's `redact-path`."
    (rf/reg-interceptor* :rf/redact-interceptor
      (privacy/redact-interceptor [[:declared]]))
    (rf/reg-event :neutral/save
      {:interceptors [:rf/redact-interceptor]}
      (fn [{:keys [db]} [_ payload]] {:db (assoc db :saved payload)}))
    (let [evs        (record-traces
                       #(rf/dispatch-sync [:neutral/save {:keep "me"}]))
          db-changed (first (events-of evs :rf.event/db-changed))]
      (is (= "me" (get-in db-changed [:tags :rf.event/v 1 :keep]))
          "unrelated keys flow through to the trace surface")
      (is (= :rf/redacted (get-in db-changed [:tags :rf.event/v 1 :declared]))
          "declared key is sentineled even when absent in the source map")
      (is (not (contains? (get-in db-changed [:tags :rf.event/v 1]) :other))
          "keys neither declared nor in the source remain absent"))))

(deftest handler-without-redact-interceptor-sees-no-redaction
  (testing "negative — a plain handler emits trace events with the raw payload"
    (rf/reg-event :plain/save
      (fn [{:keys [db]} [_ payload]] {:db (assoc db :saved payload)}))
    (let [evs        (record-traces
                       #(rf/dispatch-sync [:plain/save {:password "shh"}]))
          db-changed (first (events-of evs :rf.event/db-changed))]
      (is (= "shh" (get-in db-changed [:tags :rf.event/v 1 :password]))
          "no `:redact-interceptor` → trace surface carries the raw value"))))

(deftest empty-path-scrubs-entire-payload
  (testing "an empty path is the documented 'scrub everything' form"
    (rf/reg-interceptor* :rf/redact-interceptor
      (privacy/redact-interceptor [[]]))
    (rf/reg-event :whole/payload
      {:interceptors [:rf/redact-interceptor]}
      (fn [{:keys [db]} _] {:db (assoc db :ran? true)}))
    (let [evs        (record-traces
                       #(rf/dispatch-sync [:whole/payload {:any "thing"}]))
          db-changed (first (events-of evs :rf.event/db-changed))]
      (is (= :rf/redacted (get-in db-changed [:tags :rf.event/v 1]))))))

(deftest non-map-payload-passes-through
  (testing "non-map payload shapes are out of scope (the canonical M-19 form
            is `[id payload-map ...]`); the interceptor must not throw or
            mangle a non-conforming event"
    (rf/reg-interceptor* :rf/redact-interceptor
      (privacy/redact-interceptor [[:password]]))
    (rf/reg-event :raw/vec-payload
      {:interceptors [:rf/redact-interceptor]}
      (fn [{:keys [db]} _] {:db (assoc db :ran? true)}))
    (let [evs        (record-traces
                       #(rf/dispatch-sync [:raw/vec-payload "scalar"]))
          db-changed (first (events-of evs :rf.event/db-changed))]
      (is (= "scalar" (get-in db-changed [:tags :rf.event/v 1]))))))

;; ---- rf2-agpv2.4: a non-associative parent value is a redaction no-op -----

(deftest redact-path-with-scalar-parent-does-not-abort-the-event
  (testing "a 2+-segment redact path whose intermediate is a non-associative
            scalar is a no-op — it must NOT throw inside the `:before` chain
            and abort the event. Pre-fix the `(some? …)` parent guard let a
            non-nil scalar through, and `assoc-in` recursed into it
            (\"cannot assoc onto a String\"), turning a privacy-redaction
            into a dropped event (classified :rf.error/interceptor-exception,
            no :db commit, no :fx). The fix guards with `associative?`."
    (let [seen (atom nil)]
      ;; Redact path [:auth :password] but the payload's :auth is a SCALAR
      ;; string, so the parent (get-in payload [:auth]) is non-nil but
      ;; non-associative — the exact mis-declaration the bead calls out.
      (rf/reg-interceptor* :rf/redact-interceptor
        (privacy/redact-interceptor [[:auth :password]]))
      (rf/reg-event :auth/scalar-parent
        {:interceptors [:rf/redact-interceptor]}
        (fn [{:keys [db]} [_ payload]]
          (reset! seen payload)
          {:db (assoc db :committed payload)}))
      (let [evs        (record-traces
                         #(rf/dispatch-sync
                            [:auth/scalar-parent {:auth "a-token-string"}]))
            db-changed (first (events-of evs :rf.event/db-changed))
            errors     (events-of evs :rf.error/interceptor-exception)]
        ;; (1) No throw escaped the `:before` chain → no interceptor-exception.
        (is (empty? errors)
            "the scalar-parent redact path did NOT abort the event")
        ;; (2) The handler ran and its :db commit landed (event not dropped).
        (is (= {:auth "a-token-string"} @seen)
            "handler body ran with the raw payload")
        (is (= {:auth "a-token-string"}
               (:committed (rf/app-db-value :rf/default)))
            ":db commit landed — the event was NOT aborted")
        ;; (3) The trace surface left the scalar untouched (no-op redaction).
        (is (some? db-changed) "a db-changed trace was emitted")
        (is (= "a-token-string" (get-in db-changed [:tags :rf.event/v 1 :auth]))
            "the non-associative parent passed through unredacted (no-op)")))))

;; ---- (removed) composition with handler-meta `:sensitive?` ---------------
;;
;; The handler-meta `:sensitive?` annotation has been removed. The trace-
;; surface `:sensitive?` stamp is now driven only by the schema-derived
;; overlap (see `composes-additively-with-schema-redaction` below).

;; ---- composition with schema-derived redaction (additive) -----------------

(deftest composes-additively-with-frame-class-redaction
  (testing "when both a frame-sensitive app-db path AND a user
            `redact-interceptor` apply, the trace surface scrubs the UNION of
            paths. The user interceptor's `:before` reads the frame-class
            interceptor's already-stashed `:rf/redacted-event` and extends
            it, rather than overwriting it (EP-0015 §8)."
    (frame/swap-runtime-db! :rf/default
      (fn [rt] (elision/apply-classification-effects rt {:sensitive [[:auth :password]]})))
    (let [seen (atom nil)]
      (rf/reg-interceptor* :rf/redact-interceptor
        (privacy/redact-interceptor [[:token]]))
      (rf/reg-event :auth/login+token
        ;; `path` focuses on `:auth`, which makes the auto-redaction
        ;; install for `:password` (frame-declared sensitive). The user
        ;; `redact-interceptor` adds `:token` (NOT frame-declared).
        {:interceptors [[:rf.interceptor/path [:auth]]
                        :rf/redact-interceptor]}
        (fn [{:keys [db]} [_ payload]]
          (reset! seen payload)
          {:db (assoc db :last payload)}))
      (let [evs        (record-traces
                         #(rf/dispatch-sync
                            [:auth/login+token {:username "ada"
                                                :password "shh"
                                                :token    "abc"}]))
            run-start  (run-start-of evs)
            db-changed (first (events-of evs :rf.event/db-changed))]
        (is (= {:username "ada" :password "shh" :token "abc"} @seen)
            "handler still receives the raw payload")
        ;; Both keys scrubbed on the trace surface (union):
        (is (= :rf/redacted (get-in run-start [:tags :rf.event/v 1 :password]))
            "frame-declared key scrubbed")
        (is (= :rf/redacted (get-in run-start [:tags :rf.event/v 1 :token]))
            "user-declared key scrubbed")
        (is (= "ada" (get-in run-start [:tags :rf.event/v 1 :username]))
            "unrelated key flows through")
        ;; And the frame-sensitive scope-stamp still fires (the frame
        ;; path drove it; the user interceptor does NOT stamp):
        (is (true? (:sensitive? run-start))
            "frame-sensitive scope-stamp still fires (driven by the
             frame-declared sensitive path, not by `redact-interceptor`)")
        ;; And the in-chain `:rf.event/db-changed` also carries both:
        (is (= :rf/redacted (get-in db-changed [:tags :rf.event/v 1 :password])))
        (is (= :rf/redacted (get-in db-changed [:tags :rf.event/v 1 :token])))))))

(deftest redact-interceptor-alone-does-not-stamp-sensitive-scope
  (testing "regression — `redact-interceptor` is a payload-scrub, NOT a scope
            stamper. The `:sensitive?` boolean on emitted events is the
            registration-meta / schema-derived signal only."
    (rf/reg-interceptor* :rf/redact-interceptor
      (privacy/redact-interceptor [[:password]]))
    (rf/reg-event :plain/scrub
      {:interceptors [:rf/redact-interceptor]}
      (fn [{:keys [db]} _] {:db db}))
    (let [evs       (record-traces
                      #(rf/dispatch-sync [:plain/scrub {:password "shh"}]))
          run-start (run-start-of evs)]
      (is (not (true? (:sensitive? run-start)))
          "no schema overlap, no handler-meta — no `:sensitive?` stamp"))))

;; ---- composition: handler exception path picks up the scrub ---------------

(deftest handler-exception-trace-sees-redacted-payload
  (testing "the always-on error path also reads
            `privacy/redacted-event-from-ctx`, so a throwing handler that
            had a `redact-interceptor` interceptor surfaces the scrub in the
            `:rf.error/handler-exception` trace event"
    (rf/reg-interceptor* :rf/redact-interceptor
      (privacy/redact-interceptor [[:password] [:token]]))
    (rf/reg-event :auth/explode
      {:interceptors [:rf/redact-interceptor]}
      (fn [{:keys [db]} _] {:db (throw (ex-info "boom" {}))}))
    (let [evs   (record-traces
                  #(rf/dispatch-sync
                     [:auth/explode {:username "ada"
                                     :password "shh"
                                     :token    "abc"}]))
          [err] (events-of evs :rf.error/handler-exception)]
      (is (some? err) "the exception path fired")
      (is (= :rf/redacted (get-in err [:tags :event 1 :password])))
      (is (= :rf/redacted (get-in err [:tags :event 1 :token])))
      (is (= "ada" (get-in err [:tags :event 1 :username]))))))

;; ---- multiple redact-interceptor interceptors in one chain ---------------------

(deftest multiple-redact-interceptor-interceptors-union
  (testing "stacking two `redact-interceptor` interceptors in one chain applies
            the union of their paths. Useful when an interceptor library
            ships its own privacy interceptor and the registration also
            wants per-call scrubs."
    ;; Two DISTINCT redact interceptors (different paths) in one chain.
    ;; Chains are reference-only (EP-0022) and two registrations cannot share
    ;; an id, so register ONE `:factory` under `:rf/redact-interceptor` and
    ;; reference it twice with different `[id arg]` args. The factory returns a
    ;; `redact-interceptor` value whose `:id` is already `:rf/redact-interceptor`
    ;; (matching the registration id the resolver re-stamps), so the router's
    ;; `redact-interceptor?` recognition (`:id = :rf/redact-interceptor`) and the
    ;; per-value `:paths` both survive resolution — the union is scrubbed.
    (rf/reg-interceptor* :rf/redact-interceptor
      {:factory (fn [paths] (privacy/redact-interceptor paths))})
    (rf/reg-event :auth/dual
      {:interceptors [[:rf/redact-interceptor [[:password]]]
                      [:rf/redact-interceptor [[:token]]]]}
      (fn [{:keys [db]} _] {:db (assoc db :ran? true)}))
    (let [evs        (record-traces
                       #(rf/dispatch-sync
                          [:auth/dual {:username "ada"
                                       :password "shh"
                                       :token    "abc"}]))
          db-changed (first (events-of evs :rf.event/db-changed))]
      (is (= :rf/redacted (get-in db-changed [:tags :rf.event/v 1 :password])))
      (is (= :rf/redacted (get-in db-changed [:tags :rf.event/v 1 :token])))
      (is (= "ada" (get-in db-changed [:tags :rf.event/v 1 :username]))))))
