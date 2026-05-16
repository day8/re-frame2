(ns re-frame.with-redacted-test
  "Per rf2-461sp — `(rf/with-redacted paths)` positional interceptor.

  The third composition site for `:sensitive?` (per [Security.md
  §Behavioural MUSTs across the privacy surface](spec/Security.md)):

    1. Handler body sees the UNREDACTED payload via `:event` coeffect.
    2. Trace surface (`:run-start` / `:run-end` / `:event/db-changed` /
       `:rf.error/handler-exception`) sees `:rf/redacted` at the named
       payload keys.
    3. Composes orthogonally with registration-meta `:sensitive? true`
       (which stamps `:sensitive? true` on every emitted trace event).
    4. Composes additively with schema-derived redaction
       (`:rf/schema-redaction` interceptor; the user-installed
       interceptor extends `:rf/redacted-event` rather than overwriting).
    5. Composes independently with epoch `:redact-fn` (the per-record
       hook reads already-scrubbed trace events).

  Negative coverage: handlers without `with-redacted` see no redaction;
  unrelated keys pass through; non-map payload shapes pass through; an
  empty path scrubs the entire payload."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.privacy :as privacy]
            [re-frame.registrar :as registrar]
            [re-frame.schemas :as schemas]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.trace :as trace]))

(defn- reset-runtime [test-fn]
  (registrar/clear-all!)
  (reset! frame/frames {})
  (reset! schemas/schemas-by-frame {})
  (trace/clear-trace-cbs!)
  (privacy/clear-suppression-cache!)
  (rf/init! plain-atom/adapter)
  (require 're-frame.elision :reload)
  (require 're-frame.schemas :reload)
  (test-fn))

(use-fixtures :each reset-runtime)

(defn- record-traces
  [body-fn]
  (let [seen (atom [])]
    (rf/register-trace-cb! ::rec (fn [ev] (swap! seen conj ev)))
    (try (body-fn)
         (finally (rf/remove-trace-cb! ::rec)))
    @seen))

(defn- events-of [evs op]
  (filterv #(= op (:operation %)) evs))

(defn- run-start-of [evs]
  (first (filterv #(and (= :event (:operation %))
                        (= :run-start (get-in % [:tags :phase])))
                  evs)))

;; ---- public-API + interceptor-shape sanity --------------------------------

(deftest with-redacted-is-exported
  (is (= rf/with-redacted privacy/with-redacted)
      "rf/with-redacted is the rf.core alias of privacy/with-redacted"))

(deftest with-redacted-returns-interceptor-with-paths
  (testing "the returned interceptor map exposes its paths on `:paths` so the
            router can fold them into the pre-chain trace projection"
    (let [paths [[:password] [:token]]
          icpt  (rf/with-redacted paths)]
      (is (map? icpt))
      (is (= :rf/with-redacted (:id icpt)))
      (is (= paths (:paths icpt)))
      (is (fn? (:before icpt))))))

;; ---- scope-only redaction: handler sees raw, trace sees scrubbed ----------

(deftest handler-sees-unredacted-trace-sees-redacted
  (testing "the handler's `:event` coeffect is the raw payload; every trace
            surface that uses `redacted-event-from-ctx` sees the scrub"
    (let [seen (atom nil)]
      (rf/reg-event-db :auth/login
        [(rf/with-redacted [[:password] [:token]])]
        (fn [db [_ payload]]
          (reset! seen payload)
          (assoc db :last-login payload)))
      (let [evs        (record-traces
                         #(rf/dispatch-sync
                            [:auth/login {:username "ada"
                                          :password "shh"
                                          :token    "abc123"}]))
            run-start  (run-start-of evs)
            db-changed (first (events-of evs :event/db-changed))]
        (is (= {:username "ada" :password "shh" :token "abc123"} @seen)
            "handler body sees the raw payload — `with-redacted` is a
             trace-surface scrub, not a handler-input rewrite")
        (is (= :rf/redacted (get-in run-start [:tags :event 1 :password])))
        (is (= :rf/redacted (get-in run-start [:tags :event 1 :token])))
        (is (= "ada" (get-in run-start [:tags :event 1 :username]))
            "non-declared keys pass through to the trace surface")
        (is (= :rf/redacted (get-in db-changed [:tags :event 1 :password])))
        (is (= :rf/redacted (get-in db-changed [:tags :event 1 :token])))
        (is (= "ada" (get-in db-changed [:tags :event 1 :username])))))))

(deftest declared-key-is-sentineled-even-when-absent
  (testing "the redaction is explicit: a top-level declared key is always
            written as `:rf/redacted`, even when absent from the source
            payload. Opt-in privacy is additive, not conditional;
            consistent with the schema-redaction helper's `redact-path`."
    (rf/reg-event-db :neutral/save
      [(rf/with-redacted [[:declared]])]
      (fn [db [_ payload]] (assoc db :saved payload)))
    (let [evs        (record-traces
                       #(rf/dispatch-sync [:neutral/save {:keep "me"}]))
          db-changed (first (events-of evs :event/db-changed))]
      (is (= "me" (get-in db-changed [:tags :event 1 :keep]))
          "unrelated keys flow through to the trace surface")
      (is (= :rf/redacted (get-in db-changed [:tags :event 1 :declared]))
          "declared key is sentineled even when absent in the source map")
      (is (not (contains? (get-in db-changed [:tags :event 1]) :other))
          "keys neither declared nor in the source remain absent"))))

(deftest handler-without-with-redacted-sees-no-redaction
  (testing "negative — a plain handler emits trace events with the raw payload"
    (rf/reg-event-db :plain/save
      (fn [db [_ payload]] (assoc db :saved payload)))
    (let [evs        (record-traces
                       #(rf/dispatch-sync [:plain/save {:password "shh"}]))
          db-changed (first (events-of evs :event/db-changed))]
      (is (= "shh" (get-in db-changed [:tags :event 1 :password]))
          "no `:with-redacted` → trace surface carries the raw value"))))

(deftest empty-path-scrubs-entire-payload
  (testing "an empty path is the documented 'scrub everything' form"
    (rf/reg-event-db :whole/payload
      [(rf/with-redacted [[]])]
      (fn [db _] (assoc db :ran? true)))
    (let [evs        (record-traces
                       #(rf/dispatch-sync [:whole/payload {:any "thing"}]))
          db-changed (first (events-of evs :event/db-changed))]
      (is (= :rf/redacted (get-in db-changed [:tags :event 1]))))))

(deftest non-map-payload-passes-through
  (testing "non-map payload shapes are out of scope (the canonical M-19 form
            is `[id payload-map ...]`); the interceptor must not throw or
            mangle a non-conforming event"
    (rf/reg-event-db :raw/vec-payload
      [(rf/with-redacted [[:password]])]
      (fn [db _] (assoc db :ran? true)))
    (let [evs        (record-traces
                       #(rf/dispatch-sync [:raw/vec-payload "scalar"]))
          db-changed (first (events-of evs :event/db-changed))]
      (is (= "scalar" (get-in db-changed [:tags :event 1]))))))

;; ---- composition with handler-meta `:sensitive?` --------------------------

(deftest composes-with-handler-meta-sensitive
  (testing "the two privacy sites are orthogonal: `:sensitive? true` meta
            stamps `:sensitive? true` on every emitted trace event;
            `with-redacted` scrubs the payload slot. Both apply."
    (rf/reg-event-fx :auth/cross-cutting
      {:sensitive? true}
      [(rf/with-redacted [[:password]])]
      (fn [{:keys [db]} [_ payload]]
        {:db (assoc db :payload payload)}))
    (let [evs        (record-traces
                       #(rf/dispatch-sync
                          [:auth/cross-cutting {:username "ada"
                                                :password "shh"}]))
          run-start  (run-start-of evs)
          db-changed (first (events-of evs :event/db-changed))]
      ;; Both stampings present on the same event:
      (is (true? (:sensitive? run-start))
          "handler-meta `:sensitive?` stamps every event in the scope")
      (is (= :rf/redacted (get-in run-start [:tags :event 1 :password]))
          "with-redacted scrubs the payload slot on the same event")
      (is (= "ada" (get-in run-start [:tags :event 1 :username]))
          "non-declared keys still flow through")
      ;; Other emit sites in the cascade carry both as well:
      (is (true? (:sensitive? db-changed)))
      (is (= :rf/redacted (get-in db-changed [:tags :event 1 :password]))))))

;; ---- composition with schema-derived redaction (additive) -----------------

(deftest composes-additively-with-schema-redaction
  (testing "when both a schema-declared sensitive slot AND a user
            `with-redacted` apply, the trace surface scrubs the UNION of
            paths. The user interceptor's `:before` reads the schema
            interceptor's already-stashed `:rf/redacted-event` and extends
            it, rather than overwriting it."
    (rf/reg-app-schema [:auth]
                       [:map
                        [:username :string]
                        [:password {:sensitive? true} :string]])
    (let [seen (atom nil)]
      (rf/reg-event-db :auth/login+token
        ;; `path` focuses on `:auth`, which makes the schema-redaction
        ;; auto-install for `:password` (schema-declared sensitive). The
        ;; user `with-redacted` adds `:token` (NOT schema-declared).
        [(rf/path :auth)
         (rf/with-redacted [[:token]])]
        (fn [auth [_ payload]]
          (reset! seen payload)
          (assoc auth :last payload)))
      (let [evs        (record-traces
                         #(rf/dispatch-sync
                            [:auth/login+token {:username "ada"
                                                :password "shh"
                                                :token    "abc"}]))
            run-start  (run-start-of evs)
            db-changed (first (events-of evs :event/db-changed))]
        (is (= {:username "ada" :password "shh" :token "abc"} @seen)
            "handler still receives the raw payload")
        ;; Both keys scrubbed on the trace surface (union):
        (is (= :rf/redacted (get-in run-start [:tags :event 1 :password]))
            "schema-declared key scrubbed")
        (is (= :rf/redacted (get-in run-start [:tags :event 1 :token]))
            "user-declared key scrubbed")
        (is (= "ada" (get-in run-start [:tags :event 1 :username]))
            "unrelated key flows through")
        ;; And the schema-sensitive scope-stamp still fires (the schema
        ;; path drove it; the user interceptor does NOT stamp):
        (is (true? (:sensitive? run-start))
            "schema-sensitive scope-stamp still fires (driven by the
             schema-declared sensitive slot, not by `with-redacted`)")
        ;; And the in-chain `:event/db-changed` also carries both:
        (is (= :rf/redacted (get-in db-changed [:tags :event 1 :password])))
        (is (= :rf/redacted (get-in db-changed [:tags :event 1 :token])))))))

(deftest with-redacted-alone-does-not-stamp-sensitive-scope
  (testing "regression — `with-redacted` is a payload-scrub, NOT a scope
            stamper. The `:sensitive?` boolean on emitted events is the
            registration-meta / schema-derived signal only."
    (rf/reg-event-db :plain/scrub
      [(rf/with-redacted [[:password]])]
      (fn [db _] db))
    (let [evs       (record-traces
                      #(rf/dispatch-sync [:plain/scrub {:password "shh"}]))
          run-start (run-start-of evs)]
      (is (not (true? (:sensitive? run-start)))
          "no schema overlap, no handler-meta — no `:sensitive?` stamp"))))

;; ---- composition: handler exception path picks up the scrub ---------------

(deftest handler-exception-trace-sees-redacted-payload
  (testing "the always-on error path also reads
            `privacy/redacted-event-from-ctx`, so a throwing handler that
            had a `with-redacted` interceptor surfaces the scrub in the
            `:rf.error/handler-exception` trace event"
    (rf/reg-event-db :auth/explode
      [(rf/with-redacted [[:password] [:token]])]
      (fn [_ _] (throw (ex-info "boom" {}))))
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

;; ---- multiple with-redacted interceptors in one chain ---------------------

(deftest multiple-with-redacted-interceptors-union
  (testing "stacking two `with-redacted` interceptors in one chain applies
            the union of their paths. Useful when an interceptor library
            ships its own privacy interceptor and the registration also
            wants per-call scrubs."
    (rf/reg-event-db :auth/dual
      [(rf/with-redacted [[:password]])
       (rf/with-redacted [[:token]])]
      (fn [db _] (assoc db :ran? true)))
    (let [evs        (record-traces
                       #(rf/dispatch-sync
                          [:auth/dual {:username "ada"
                                       :password "shh"
                                       :token    "abc"}]))
          db-changed (first (events-of evs :event/db-changed))]
      (is (= :rf/redacted (get-in db-changed [:tags :event 1 :password])))
      (is (= :rf/redacted (get-in db-changed [:tags :event 1 :token])))
      (is (= "ada" (get-in db-changed [:tags :event 1 :username]))))))
