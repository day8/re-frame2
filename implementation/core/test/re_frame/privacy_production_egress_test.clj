(ns re-frame.privacy-production-egress-test
  "rf2-r9bra — the PRODUCTION-SURVIVING half of the privacy surface.

  ## Why this namespace exists

  `re-frame.redact-interceptor-test` and `re-frame.sensitive-stamping-test`
  are the repo's two privacy suites, and both capture EXCLUSIVELY through
  `(rf/register-listener! :trace …)`. That listener is dev-only: under the
  documented production posture (`-Dre-frame.debug=false` / `goog.DEBUG=false`)
  the framework emits no trace events at all, so every assertion in both suites
  reads `nil` and the suites go red in the `jvm-core-prod-gate` lane.

  The redness is test SPELLING — but it left a genuine hole. Between them
  those suites covered ZERO of the redaction that actually ships: the
  always-on error-emit substrate (`re-frame.error-emit`, Spec 009 §What IS
  available in production) survives both gates and carries an `:event` slot
  to off-box shippers (Sentry / Datadog / Honeybadger). THAT is the surface a
  production build can leak from, and nothing asserted against it in either
  posture.

  ## What is pinned here

  Every assertion below is POSTURE-INDEPENDENT and must hold in dev AND under
  the real gate — that is the whole point, so this namespace runs in the
  ordinary `clojure -M:test` suite and in `scripts/test-core-prod-gate.sh`
  (which is an exclusion roster, so a new namespace joins it by default).

  The three independent producers that must all keep feeding the always-on
  record:

    1. EP-0025 commit-plane classification — a frame-declared `:sensitive`
       app-db path overlapping a path-scoped handler's slice. The router
       installs `privacy/schema-redaction-interceptor` UNCONDITIONALLY in
       `prepare-handler-ctx` (no `debug-enabled?` gate); its output rides
       `:rf/redacted-event`, which `privacy/redacted-event-from-ctx` hands to
       `error-emit/emit-error-both!`.
    2. The user-installed `privacy/redact-interceptor`, whose `:before`
       extends the same `:rf/redacted-event` slot.
    3. `elision/elide-wire-value`, run unconditionally inside
       `error-emit/dispatch-on-error!` over the per-frame
       `[:rf.runtime/elision :sensitive-declarations]` registry — the
       belt-and-braces path that redacts even for emission sites that never
       ran an interceptor chain (`:rf.error/no-such-handler` below).

  A regression in ANY of the three is a production data-exposure defect, and
  before this namespace existed none of the three had a test that could
  observe it under the posture where it matters."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.elision :as elision]
            [re-frame.error-emit :as error-emit]
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
  (error-emit/clear-error-listeners!)
  (rf/init! plain-atom/adapter)
  (require 're-frame.elision :reload)
  (require 're-frame.schemas :reload)
  (rf/make-frame {:id :rf/default})
  (rf/with-frame :rf/default
    (test-fn)))

(use-fixtures :each reset-runtime)

(defn- install-sensitive!
  "Seed the frame's sensitive app-db classification through the EP-0025
  commit-plane classification effect path — the same registry write a
  `reg-event` returning `:sensitive [[…]]` performs."
  [frame-id paths]
  (frame/swap-runtime-db! frame-id
    (fn [rt] (elision/apply-classification-effects rt {:sensitive (mapv vec paths)}))))

(defn- record-always-on-errors
  "Capture through the ALWAYS-ON registry — NOT `register-listener! :trace`.
  This is the distinction the whole namespace turns on: the trace listener is
  dev-only and sees nothing under the production gate, whereas these records
  are exactly what an off-box shipper receives from a production build."
  [body-fn]
  (let [seen (atom [])]
    (error-emit/register-error-listener! ::rec (fn [r] (swap! seen conj r)))
    (try (body-fn)
         (finally (error-emit/unregister-error-listener! ::rec)))
    @seen))

(defn- record-of [records error-kw]
  (first (filter #(= error-kw (:error %)) records)))

(deftest always-on-record-honours-frame-classification
  (testing "producer 1 — an EP-0025-classified sensitive app-db path overlapping
            a path-scoped handler's slice redacts the always-on error record's
            `:event`, in EVERY posture. The handler body still sees the raw
            payload via the `:event` coeffect; only the egress copy is scrubbed."
    (install-sensitive! :rf/default [[:auth :password]])
    (let [seen (atom nil)]
      (rf/reg-event :auth/throws
        {:interceptors [[:rf.interceptor/path [:auth]]]}
        (fn [_ [_ payload]]
          (reset! seen payload)
          (throw (ex-info "boom" {}))))
      (let [err (-> (record-always-on-errors
                      #(rf/dispatch-sync [:auth/throws {:username "ada"
                                                        :password "shh"}]))
                    (record-of :rf.error/handler-exception))]
        (is (some? err)
            "the always-on error record fired (it is NOT debug-gated)")
        (is (= {:username "ada" :password "shh"} @seen)
            "the handler body received the UNREDACTED payload")
        (is (= :rf/redacted (get-in err [:event 1 :password]))
            "the classified path is redacted on the production egress record")
        (is (= "ada" (get-in err [:event 1 :username]))
            "an unclassified key is not over-redacted")))))

(deftest always-on-record-honours-user-redact-interceptor
  (testing "producer 2 — a user-installed `redact-interceptor` extends
            `:rf/redacted-event`, and that extension reaches the always-on
            record. Neither the interceptor's installation nor its `:before`
            is `debug-enabled?`-gated."
    (rf/reg-interceptor :rf/redact-interceptor
      (privacy/redact-interceptor [[:password] [:token]]))
    (rf/reg-event :auth/explode
      {:interceptors [:rf/redact-interceptor]}
      (fn [_ _] (throw (ex-info "boom" {}))))
    (let [err (-> (record-always-on-errors
                    #(rf/dispatch-sync [:auth/explode {:username "ada"
                                                       :password "shh"
                                                       :token    "abc"}]))
                  (record-of :rf.error/handler-exception))]
      (is (some? err) "the always-on error record fired")
      (is (= :rf/redacted (get-in err [:event 1 :password])))
      (is (= :rf/redacted (get-in err [:event 1 :token]))
          "BOTH declared paths scrub — the union, not just the first")
      (is (= "ada" (get-in err [:event 1 :username]))))))

(deftest always-on-record-honours-wire-walker-without-a-chain
  (testing "producer 3 — `:rf.error/no-such-handler` is emitted before any
            interceptor chain is assembled, so `:rf/redacted-event` was never
            stashed. The record is nonetheless redacted, because
            `dispatch-on-error!` runs `elision/elide-wire-value` over the
            per-frame declarations unconditionally. This is the belt-and-braces
            arm and the one that covers emission sites with no chain at all."
    (install-sensitive! :rf/default [[:password]])
    (let [err (-> (record-always-on-errors
                    #(try (rf/dispatch-sync [:auth/missing {:password "shh"}])
                          (catch #?(:clj Throwable :cljs :default) _ nil)))
                  (record-of :rf.error/no-such-handler))]
      (is (some? err) "the always-on record fired for the chainless category")
      (is (= :rf/redacted (get-in err [:event 1 :password]))
          "the wire-walker redacted without any interceptor having run"))))

(deftest wire-walker-redacts-in-every-posture
  (testing "`elide-wire-value` — the shared redactor under every off-box egress
            surface — is not `debug-enabled?`-gated."
    (install-sensitive! :rf/default [[:auth :password]])
    (let [out (elision/elide-wire-value {:auth {:password "shh" :user "ada"}}
                                        {:frame :rf/default})]
      (is (= :rf/redacted (get-in out [:auth :password])))
      (is (= "ada" (get-in out [:auth :user]))))))
