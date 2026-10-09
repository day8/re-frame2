(ns re-frame.ssr.ring.app-db-egress-projection-test
  "The hydration app-db slice runs through the frame's `:rf.egress/ssr-hydration`
  projection AFTER the `:payload` allowlist (EP-0015 §14): a frame-classified
  sensitive child of a shipped key redacts, a `:large` one ships whole, and only
  the paths the host names in `:payload-include-sensitive` ride raw."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest is use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as rf.frame]
            [re-frame.privacy :as rf.privacy]
            [re-frame.ssr :as rf.ssr]
            [re-frame.ssr.ring :as rf.ssr.ring]
            [re-frame.ssr.ring.payload :as rf.ssr.ring.payload]
            [re-frame.ssr.ring.test-support :as rf.ssr.ring.test-support]))

(use-fixtures :each rf.ssr.ring.test-support/reset-runtime)

(def ^:private redacted rf.privacy/redacted-sentinel)

(deftest build-payload-redacts-sensitive-and-ships-large-under-both-policies
  ;; `:large` protects tool budgets, not the page's own browser: the payload is
  ;; installed as live client state, so a size marker would replace real data.
  (rf/reg-event :rf.bt9kct/classify
    (fn [_ _] {:sensitive [[:session :token]] :large [[:session :items]]}))
  (rf/make-frame {:id :rf.bt9kct/server :platform :server
                  :initial-events [[:rf.bt9kct/classify]]})
  (let [db      {:session {:token "secret-jwt" :user "alice" :items [1 2 3]}
                 :secrets {:api-key "internal-only"}}
        session {:token redacted :user "alice" :items [1 2 3]}]
    (doseq [[policy expected] [[:rf.ssr.payload/whole-app-db {:session session
                                                              :secrets {:api-key "internal-only"}}]
                               [[:session]                   {:session session}]]]
      (let [out (rf.ssr.ring.payload/build-payload :rf.bt9kct/server db nil {:payload policy})]
        (is (= expected (:rf/app-db out)) (pr-str policy))
        (is (not (str/includes? (pr-str out) "secret-jwt")) (pr-str policy))))))

;; A CSRF synchronizer token is sensitive (kept out of logs and tools) yet the
;; page must send it back, so the host permits it; the rest stays redacted.

(defn- reg-session-app! []
  (rf/reg-event :rf.hjz4r/seed-session
    (fn [_ _]
      {:db        {:session {:csrf "csrf-abc-123" :upstream-key "sk-server-only" :user "alice"}
                   :secrets {:api-key "internal-only"}}
       :sensitive [[:session :csrf] [:session :upstream-key]]}))
  (rf/reg-sub :rf.hjz4r/csrf (fn [db _] (get-in db [:session :csrf])))
  (rf/reg-view* :rf.hjz4r/root
    (fn [] [:input {:type "hidden" :name "csrf" :value (rf/subscribe-once [:rf.hjz4r/csrf])}])))

(def ^:private handler-opts
  {:initial-events            [[:rf.hjz4r/seed-session]]
   :payload                   [:session]
   :payload-include-sensitive [[:session :csrf]]})

(def ^:private permitted-session {:session {:csrf "csrf-abc-123" :upstream-key redacted :user "alice"}})

(defn- payload-of [body]
  (some-> (re-find #"<script id=\"__rf_payload\"[^>]*>(.*?)</script>" body)
          second
          edn/read-string))

(deftest a-permitted-sensitive-value-rides-the-payload-raw
  (reg-session-app!)
  (let [handler (rf.ssr.ring/ssr-handler
                  (assoc handler-opts :root-view (fn [] ((rf/view :rf.hjz4r/root)))))
        payload (payload-of (:body (handler {:uri "/login" :request-method :get})))
        client  (rf.frame/make-anon-frame-record! {:doc      "hash client frame"
                                                   :platform :client
                                                   :ssr      {:on-mismatch :hard-error}})]
    (is (= permitted-session (:rf/app-db payload)))
    (is (not (str/includes? (pr-str payload) "sk-server-only")))
    ;; The server HTML carries the raw token, so the client's first render
    ;; hashes the same only because the payload does too.
    (is (some? (:rf/render-hash payload)) "the hash channel the hydrate check reads")
    (is (= payload (rf.ssr/hydrate! {:frame          client
                                     :payload        payload
                                     :render-tree-fn #((rf/view :rf.hjz4r/root))})))))

(deftest the-streaming-final-payload-honours-the-permit
  (reg-session-app!)
  (let [handler (rf.ssr.ring/stream-handler
                  (assoc handler-opts :root-view [(rf/view :rf.hjz4r/root)]))
        body    (with-open [in ^java.io.InputStream (:body (handler {:uri "/login" :request-method :get}))]
                  (slurp in))]
    (is (= permitted-session (:rf/app-db (payload-of body))))))

(deftest a-malformed-permit-fails-at-handler-construction
  (let [data (try (rf.ssr.ring/ssr-handler
                    (assoc handler-opts
                           :root-view (fn [] [:p "x"])
                           :payload-include-sensitive [:session :csrf]))
                  nil
                  (catch clojure.lang.ExceptionInfo e (ex-data e)))]
    (is (= {:rf.error/id :rf.error/ssr-malformed-payload-allowlist
            :opt         :payload-include-sensitive
            :bad-entries [:session :csrf]}
           (select-keys data [:rf.error/id :opt :bad-entries])))))
