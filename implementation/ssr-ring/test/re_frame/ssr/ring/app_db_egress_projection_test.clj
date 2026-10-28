(ns re-frame.ssr.ring.app-db-egress-projection-test
  "The hydration app-db slice runs through the frame's `:rf.egress/ssr-hydration`
  projection AFTER the `:payload` allowlist (EP-0015 §14): a frame-classified
  sensitive child of a shipped key redacts, a `:large` one ships whole, and only
  the classified leaves a `:payload` path names ride raw."
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
  (rf/reg-classification :rf.bt9kct/session
    {:sensitive [[:session :token]] :large [[:session :items]]})
  (rf/make-frame {:id :rf.bt9kct/server :platform :server})
  (let [db      {:session {:token "secret-jwt" :user "alice" :items [1 2 3]}
                 :secrets {:api-key "internal-only"}}
        session {:token redacted :user "alice" :items [1 2 3]}]
    (doseq [[policy expected] [[:rf.ssr.payload/whole-app-db {:session session
                                                              :secrets {:api-key "internal-only"}}]
                               [[:session]                   {:session session}]]]
      (let [out (rf.ssr.ring.payload/build-payload :rf.bt9kct/server db {:payload policy})]
        (is (= expected (:rf/app-db out)) (pr-str policy))
        (is (not (str/includes? (pr-str out) "secret-jwt")) (pr-str policy))))))

;; A CSRF synchronizer token is sensitive (kept out of logs and tools) yet the
;; page must send it back, so the host permits it; the rest stays redacted.

(defn- reg-session-app! []
  (rf/reg-classification :rf.hjz4r/session
    {:sensitive [[:session :csrf] [:session :upstream-key]]})
  (rf/reg-event :rf.hjz4r/seed-session
    (fn [_ _]
      {:db {:session {:csrf "csrf-abc-123" :upstream-key "sk-server-only" :user "alice"}
            :secrets {:api-key "internal-only"}}}))
  (rf/reg-sub :rf.hjz4r/csrf (fn [db _] (get-in db [:session :csrf])))
  (rf/reg-view* :rf.hjz4r/root
    (fn [] [:input {:type "hidden" :name "csrf" :value (rf/subscribe-once [:rf.hjz4r/csrf])}])))

(def ^:private handler-opts
  {:frame   {:initial-events [[:rf.hjz4r/seed-session]]}
   :payload [:session [:session :csrf]]})

(def ^:private permitted-session {:session {:csrf "csrf-abc-123" :upstream-key redacted :user "alice"}})

(defn- payload-of [body]
  (some-> (re-find #"<script id=\"__rf_payload\"[^>]*>(.*?)</script>" body)
          second
          edn/read-string))

(deftest a-permitted-sensitive-value-rides-the-payload-raw
  (reg-session-app!)
  (let [handler (rf.ssr.ring/ssr-handler
                  (assoc handler-opts :root-view (fn [] ((rf/view :rf.hjz4r/root)))))
        body    (:body (handler {:uri "/login" :request-method :get}))
        payload (payload-of body)
        client  (rf.frame/make-anon-frame-record! {:doc      "hydrating client frame"
                                                   :platform :client})]
    (is (= permitted-session (:rf/app-db payload)))
    (is (not (str/includes? (pr-str payload) "sk-server-only")))
    (is (= payload (rf.ssr/hydrate! {:frame client :payload payload})))
    ;; The server HTML carries the raw token, so the client's first render
    ;; agrees with it only because the payload does too.
    (is (= (second (re-find #"(?s)<div id=\"app\">(.*?)</div><script id=\"__rf_payload\"" body))
           (rf/with-frame client (rf.ssr/render-to-string [(rf/view :rf.hjz4r/root)])))
        "the hydrated client frame renders the server's markup")))

(defn- construction-error [opts]
  (try (rf.ssr.ring/ssr-handler (assoc opts :root-view (fn [] [:p "x"])))
       nil
       (catch clojure.lang.ExceptionInfo e (ex-data e))))

(deftest a-malformed-path-fails-at-handler-construction
  (is (= {:rf.error/id :rf.error/ssr-malformed-payload-allowlist
          :bad-entries [[]]}
         (select-keys (construction-error (assoc handler-opts :payload [:session []]))
                      [:rf.error/id :opt :bad-entries]))))

(deftest the-separate-permit-list-fails-at-handler-construction
  (is (= {:rf.error/id :rf.error/ssr-malformed-payload-allowlist
          :opt         :payload-include-sensitive
          :replacement :payload}
         (select-keys (construction-error (assoc handler-opts
                                                 :payload                   [:session]
                                                 :payload-include-sensitive [[:session :csrf]]))
                      [:rf.error/id :opt :replacement]))))
