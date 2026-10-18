(ns re-frame.ssr-hydration-payload-test
  "`re-frame.ssr/hydration-payload` — the payload a host that runs its own
  request lifecycle ships. These pin what it reads (the live frame's state,
  addressed by id or value) and that each of `ssr-handler`'s payload options
  reaches the payload. The projection rules themselves are
  `re-frame.ssr.payload-policy`'s, and its own tests pin them."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.privacy :as rf.privacy]
            [re-frame.ssr :as rf.ssr]
            [re-frame.ssr.payload-policy :as rf.ssr.payload-policy]
            [re-frame.ssr.test-fixture :as rf.ssr.test-fixture]))

(use-fixtures :each rf.ssr.test-fixture/reset-runtime)

(def ^:private sframe :rf.hydration-payload-test/server)

(def ^:private redacted rf.privacy/redacted-sentinel)

(def ^:private seed-db
  {:articles [{:id "a" :title "Article A"}]
   :session  {:csrf "csrf-abc-123" :user "alice"}
   :scratch  {:server-only true}})

(defn- make-server-frame!
  "A server frame whose init event seeds `seed-db` and classifies the CSRF
  token `:sensitive`. Returns the frame value."
  []
  (rf/reg-event :rf.hydration-payload-test/seed
    (fn [_ _] {:db seed-db :sensitive [[:session :csrf]]}))
  (rf/make-frame {:id sframe :platform :server
                  :initial-events [[:rf.hydration-payload-test/seed]]}))

(defn- thrown-id [f]
  (try (f) nil
       (catch clojure.lang.ExceptionInfo e (:rf.error/id (ex-data e)))))

(deftest the-payload-is-the-projected-frame-state
  (make-server-frame!)
  (testing "the allowlist selects, the classification redacts, and a per-request
            frame's payload carries no :rf/frame-id"
    (is (= {:rf/version rf.ssr.payload-policy/pattern-protocol-version
            :rf/app-db  {:articles (:articles seed-db)
                         :session  {:csrf redacted :user "alice"}}}
           (rf.ssr/hydration-payload sframe {:payload [:articles :session]}))))
  (testing "[] ships an empty app-db"
    (is (= {} (:rf/app-db (rf.ssr/hydration-payload sframe {:payload []}))))))

(deftest every-ssr-handler-payload-option-applies
  (make-server-frame!)
  (is (= {:rf/version       rf.ssr.payload-policy/pattern-protocol-version
          :rf/app-db        {:session {:csrf "csrf-abc-123" :user "alice"}}
          :rf/frame-id      :rf/default
          :rf/schema-digest "digest-1"}
         (rf.ssr/hydration-payload sframe {:payload                   [:session]
                                           :payload-include-sensitive [[:session :csrf]]
                                           :client-frame-id           :rf/default
                                           :schema-digest             "digest-1"}))))

(deftest a-frame-value-and-its-id-build-the-same-payload
  (let [f (make-server-frame!)]
    (is (= (rf.ssr/hydration-payload sframe {:payload [:articles :session]})
           (rf.ssr/hydration-payload f {:payload [:articles :session]})))))

(deftest the-payload-policy-fails-closed
  (make-server-frame!)
  (is (= [:rf.error/ssr-missing-payload-policy
          :rf.error/ssr-unknown-payload-policy
          :rf.error/ssr-malformed-payload-allowlist]
         [(thrown-id #(rf.ssr/hydration-payload sframe {}))
          (thrown-id #(rf.ssr/hydration-payload sframe {:payload :rf.ssr.payload/whole-db}))
          (thrown-id #(rf.ssr/hydration-payload sframe {:payload ["articles"]}))])))

(deftest a-frame-that-is-not-live-ships-nothing-readable
  ;; sframe is never made.
  (is (= {:rf/version rf.ssr.payload-policy/pattern-protocol-version
          :rf/app-db  redacted}
         (rf.ssr/hydration-payload sframe {:payload [:articles]}))))
