(ns re-frame.ssr.ring.payload-explicit-frame-test
  "`re-frame.ssr.ring.payload/build-payload` projects the runtime-db under the
  EXPLICIT frame it is handed, never the ambient scope: projected under no
  frame or the wrong one, a classified route `:query :token` would ride the
  hydration blob raw. A frame destroyed before projection fails closed."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.elision :as rf.elision]
            [re-frame.frame :as rf.frame]
            [re-frame.ssr.ring.payload :as rf.ssr.ring.payload]
            [re-frame.ssr.ring.test-support :as rf.ssr.ring.test-support]))

(use-fixtures :each rf.ssr.ring.test-support/reset-runtime)

(def ^:private server-frame :review/ssr-ring-target)

(defn- setup-frames!
  "Frame A classifies its route slice (as route activation lowers it); frame
  B, the would-be ambient, classifies nothing."
  []
  (rf/make-frame {:id server-frame :platform :server})
  (rf/make-frame {:id :review/other-ambient :platform :server})
  (rf.frame/swap-runtime-db! server-frame
    (constantly
      (-> {}
          (rf.elision/apply-classification-effects
            {:sensitive [[:rf.runtime/routing :current :query :token]]
             :large     [[:rf.runtime/routing :current :params :payload]]})
          (assoc :rf.runtime/routing
                 {:current {:route-id :route/oauth-callback
                            :query    {:token "secret-oauth-token" :return-to "/dashboard"}
                            :params   {:payload "huge-callback-blob-value"}}})))))

(defn- build-a [app-db runtime-db]
  (rf.ssr.ring.payload/build-payload server-frame app-db runtime-db "hash"
                                     {:payload :rf.ssr.payload/whole-app-db}))

(deftest build-payload-explicit-frame-wins-over-ambient
  (setup-frames!)
  (doseq [ambient [nil :review/other-ambient]]
    (binding [rf.frame/*current-frame* ambient]
      (let [payload (build-a {} (rf.frame/frame-runtime-db-value server-frame))]
        ;; The large blob rides whole: the hydration wire applies no size elision.
        (is (= {:route-id :route/oauth-callback
                :query    {:token :rf/redacted :return-to "/dashboard"}
                :params   {:payload "huge-callback-blob-value"}}
               (get-in payload [:rf/runtime-db :rf.runtime/routing :current]))
            (str "ambient " ambient))
        (is (not (re-find #"secret-oauth-token" (pr-str payload))) (str "ambient " ambient))))))

(deftest build-payload-fails-closed-on-destroyed-frame
  (setup-frames!)
  (let [runtime-db (rf.frame/frame-runtime-db-value server-frame)]
    (rf/destroy-frame! server-frame)
    (let [payload (build-a {:secret "app-db-secret" :public "ok"} runtime-db)]
      (is (= :rf/redacted (:rf/app-db payload)))
      (is (= :rf/redacted (get-in payload [:rf/runtime-db :rf.runtime/routing])))
      (is (not (re-find #"secret-oauth-token|huge-callback-blob-value|app-db-secret"
                         (pr-str payload)))))))
