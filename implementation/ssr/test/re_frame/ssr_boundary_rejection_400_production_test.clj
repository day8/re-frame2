(ns re-frame.ssr-boundary-rejection-400-production-test
  "An SSR request whose payload the `:boundary? true` check refuses answers
  400, under the real production gate, from an always-on record that carries
  nothing the client sent.

  The check is never elided (Spec 010 §Production builds) but its dev trace
  is, so the always-on `:rf.error/schema-validation-failure` record
  (`:source :boundary`, `:where :event`) is the only thing a production
  server can project from; without it `:status` would stay 200. Every
  assertion here holds in both postures, so the namespace runs under
  `scripts/test-ssr-prod-gate.sh` for real — a `with-redefs` of the
  load-time gate would prove nothing."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.error-emit :as rf.error-emit]
            [re-frame.frame :as rf.frame]
            [re-frame.ssr :as rf.ssr]
            [re-frame.ssr.error-listener :as rf.ssr.error-listener]
            [re-frame.ssr.test-fixture :as rf.ssr.test-fixture]))

;; The fixture must not clear the always-on registry: `re-frame.ssr`'s
;; `::error-projection` listener there IS the production projection path.
(use-fixtures :each rf.ssr.test-fixture/reset-runtime)

;; `:qty` fails the schema; `:note` rides an undeclared key, which no
;; schema-aware redactor could know to scrub — so the record must omit
;; payload data rather than redact it.
(def ^:private bad-payload  {:qty "s3cr3t-offending-value" :note "s3cr3t-undeclared-key"})
(def ^:private good-payload {:qty 7 :note "s3cr3t-undeclared-key"})

(defn- register-ingest! []
  (rf/reg-event :api/ingest
    {:schema [:cat [:= :api/ingest] [:map [:qty :int]]] :boundary? true}
    (fn [{:keys [db]} [_ payload]] {:db (assoc db :ingested payload)})))

(defn- server-frame []
  (rf.frame/make-anon-frame-record!
    {:platform :server
     :ssr      {:public-error-id :rf.ssr/default-error-projector :dev-error-detail? false}}))

(defn- ingest!
  "Dispatch `:api/ingest` on a fresh server frame, leaving the response
  unflushed -> `{:frame :records}`, the always-on records a shipper saw."
  [payload]
  (register-ingest!)
  (let [f    (server-frame)
        seen (atom [])]
    (rf.error-emit/register-error-listener! ::shipper #(swap! seen conj %))
    (rf/dispatch-sync [:api/ingest payload] {:frame f})
    (rf.error-emit/unregister-error-listener! ::shipper)
    {:frame f :records @seen}))

(deftest a-refused-payload-projects-400-under-the-production-gate
  (testing "the first drain projects the locked 400; one drain consumes the
            whole buffer (two entries in a dev build), so a second flush
            re-stamps nothing"
    (let [f      (:frame (ingest! bad-payload))
          flush! #(let [{:keys [response public-error]} (rf.ssr/flush-response-result! f)]
                    [(:status response) public-error])]
      (is (= [[400 {:status 400 :code :bad-request :message "Invalid input" :retryable? false}]
              [400 nil]]
             [(flush!) (flush!)])))))

(deftest the-record-carries-nothing-from-the-rejected-payload
  (testing "exactly one record, CLOSED to identifiers: `:where :event` is the
            default projector's 400 gate, `:source :boundary` separates it
            from the dev-only members of the category, and no slot carries
            the rejected value or the undeclared key"
    (let [{:keys [frame records]} (ingest! bad-payload)]
      (is (= [{:error      :rf.error/schema-validation-failure
               :where      :event
               :source     :boundary
               :event-id   :api/ingest
               :failing-id :api/ingest
               :schema-id  :api/ingest
               :frame      frame
               :recovery   :no-recovery
               :time       true}]
             (mapv #(update % :time number?) records))))))

(deftest the-400-lands-on-the-emitting-frame-only
  (register-ingest!)
  (let [refused  (server-frame)
        accepted (server-frame)]
    (rf/dispatch-sync [:api/ingest bad-payload]  {:frame refused})
    (rf/dispatch-sync [:api/ingest good-payload] {:frame accepted})
    (is (= [400 200] [(:status (:response (rf.ssr/flush-response-result! refused)))
                      (:status (:response (rf.ssr/flush-response-result! accepted)))]))))

(deftest every-buffered-entry-projects-the-same-400
  (testing "a dev build buffers the rejection on both buses and the drain
            projects the LAST entry, so every entry must agree; the count is
            the one thing that legitimately differs by posture"
    (let [f (:frame (ingest! bad-payload))]
      (is (= #{[:rf.error/schema-validation-failure 400]}
             (set (map (juxt :operation #(:status (rf.ssr/default-error-projector-fn %)))
                       (get @rf.ssr.error-listener/pending-error-traces
                            (rf.frame/frame-address f)))))))))
