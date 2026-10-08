(ns re-frame.redact-interceptor-test
  "The internal `rf.privacy/redact-interceptor` on the dev trace surface: the
  handler body sees the raw payload, the pre-chain `:run-start` projection and
  the in-chain `:rf.event/db-changed` see `:rf/redacted` at the named keys,
  the interceptor stamps no `:sensitive?` itself, and it composes additively
  with frame-classification redaction (Security.md §Behavioural MUSTs across
  the privacy surface).

  The always-on record's half is `re-frame.privacy-production-egress-test`."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.elision :as rf.elision]
            [re-frame.frame :as rf.frame]
            [re-frame.privacy :as rf.privacy]
            [re-frame.registrar :as rf.registrar]
            [re-frame.schemas :as rf.schemas]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.trace.tooling :as rf.trace.tooling]))

(defn- reset-runtime [test-fn]
  (rf.registrar/clear-all!)
  (reset! rf.frame/frames {})
  (rf.schemas/clear-schemas-by-frame!)
  (rf.trace.tooling/clear-listeners!)
  (rf/init! rf.substrate.plain-atom/adapter)
  (require 're-frame.elision :reload)
  (require 're-frame.schemas :reload)
  (rf/make-frame {:id :rf/default})
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

(defn- first-of [evs op]
  (first (filter #(= op (:operation %)) evs)))

(deftest handler-sees-unredacted-trace-sees-redacted
  (testing "the handler's `:event` coeffect is the raw payload; run-start and
            db-changed carry the scrub; the interceptor stamps no `:sensitive?`"
    (let [seen    (atom nil)
          payload {:username "ada" :password "shh" :token "abc123"}]
      (rf/reg-interceptor :rf/redact-interceptor
        (rf.privacy/redact-interceptor [[:password] [:token]]))
      (rf/reg-event :auth/login
        {:interceptors [:rf/redact-interceptor]}
        (fn [{:keys [db]} [_ p]]
          (reset! seen p)
          {:db (assoc db :last-login p)}))
      (let [evs       (record-traces #(rf/dispatch-sync [:auth/login payload]))
            run-start (first-of evs :rf.event/run-start)
            redacted  [:auth/login {:username "ada" :password :rf/redacted :token :rf/redacted}]]
        (is (= payload @seen))
        (is (= redacted (get-in run-start [:tags :rf.event/v])))
        (is (= redacted (get-in (first-of evs :rf.event/db-changed) [:tags :rf.event/v])))
        (is (not (true? (:sensitive? run-start)))
            "no classified overlap, so no `:sensitive?` stamp")))))

(deftest composes-additively-with-frame-class-redaction
  (testing "a frame-sensitive path under a path-scoped handler plus a user
            `redact-interceptor`: the trace scrubs the UNION, because the user
            `:before` extends the stashed `:rf/redacted-event` (EP-0015 §8)"
    (rf.frame/swap-runtime-db! :rf/default
      (fn [rt] (rf.elision/apply-classification-effects rt {:sensitive [[:auth :password]]})))
    (rf/reg-interceptor :rf/redact-interceptor
      (rf.privacy/redact-interceptor [[:token]]))
    (rf/reg-event :auth/login+token
      {:interceptors [[:rf.interceptor/path [:auth]]
                      :rf/redact-interceptor]}
      (fn [{:keys [db]} [_ payload]] {:db (assoc db :last payload)}))
    (let [evs      (record-traces
                     #(rf/dispatch-sync
                        [:auth/login+token {:username "ada" :password "shh" :token "abc"}]))
          redacted [:auth/login+token {:username "ada" :password :rf/redacted :token :rf/redacted}]]
      (is (= redacted (get-in (first-of evs :rf.event/run-start) [:tags :rf.event/v])))
      (is (= redacted (get-in (first-of evs :rf.event/db-changed) [:tags :rf.event/v]))))))
