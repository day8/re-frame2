(ns re-frame.ssr-streaming-hydration-egress-test
  "Streaming hydration deltas and the streaming final payload obey the
  allowlist-first-then-`:rf.egress/ssr-hydration`-project boundary
  (EP-0015 §14): a delta is browser-delivered hydration state too.
  `project-delta` is the pure guard the Ring adapter runs before it
  serialises a delta script."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.privacy :as rf.privacy]
            [re-frame.ssr.streaming :as rf.ssr.streaming]
            [re-frame.ssr.test-fixture :as rf.ssr.test-fixture]))

(use-fixtures :each rf.ssr.test-fixture/reset-runtime)

(def ^:private sframe :rf.uc3cs4/server)

(defn- reg-sensitive-server-frame!
  "A server frame that classifies `[:session :token]` sensitive through the
  commit-plane `:sensitive` effect (EP-0025), seeded with `db`."
  [db]
  (rf/reg-event :rf.uc3cs4/seed
    (fn [_ [_ v]]
      {:db        v
       :sensitive [[:session :token]]}))
  (rf/make-frame {:id sframe :platform       :server
                  :initial-events [[:rf.uc3cs4/seed db]]}))

(deftest whole-app-db-delta-still-redacts-sensitive-child
  (testing ":rf.ssr.payload/whole-app-db keeps every changed key in the delta
            but STILL redacts a frame-sensitive child"
    (reg-sensitive-server-frame! {})
    (is (= {:session {:token rf.privacy/redacted-sentinel :user "bob"}
            :secret  {:api-key "x"}}
           (rf/with-frame sframe
             (rf.ssr.streaming/project-delta
               {:session {:token "secret-jwt" :user "bob"}
                :secret  {:api-key "x"}}
               sframe {:payload :rf.ssr.payload/whole-app-db}))))))

(deftest empty-and-all-dropped-deltas-short-circuit
  (testing "an empty delta, and a delta whose every changed key is
            off-allowlist, both project to {} so the host emits no delta script"
    (reg-sensitive-server-frame! {})
    (rf/with-frame sframe
      (is (= {} (rf.ssr.streaming/project-delta {} sframe {:payload [:public]})))
      (is (= {} (rf.ssr.streaming/project-delta {:secret {:k 1}} sframe {:payload [:public]}))
          "no :rf/redacted scalar for an all-dropped delta"))))

(deftest streaming-final-payload-redacts-sensitive-app-db-child
  (testing "build-final-payload allowlists :rf/app-db, then projects it, so a
            frame-sensitive child redacts in the final __rf_payload"
    (reg-sensitive-server-frame!
      {:session {:token "secret-jwt-final" :user "carol"}
       :public  {:page :home}
       :secrets {:api-key "internal"}})
    (let [payload (rf/with-frame sframe
                    (rf.ssr.streaming/build-final-payload
                      sframe "h1" {:version 1 :payload [:session :public]}))]
      (is (= {:session {:token rf.privacy/redacted-sentinel :user "carol"}
              :public  {:page :home}}
             (:rf/app-db payload)))
      (is (not (.contains (pr-str payload) "secret-jwt-final"))
          "no raw token survives anywhere in the payload"))))
