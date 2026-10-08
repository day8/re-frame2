(ns re-frame.ssr-runtime-db-explicit-frame-test
  "`re-frame.ssr.streaming/build-final-payload` projects BOTH partitions under
  the EXPLICIT frame it is built for, never the ambient one, and fails closed
  when that frame is destroyed or re-registered between state capture and
  projection.

  An ambient-resolving projector would ship classified route / machine state
  raw outside `rf/with-frame` (no frame, no walk) and under another frame's
  policy inside a mismatched one. The route and machine projection suites
  cannot see this: they classify and project under the same ambient frame."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.elision :as rf.elision]
            [re-frame.frame :as rf.frame]
            [re-frame.late-bind :as rf.late-bind]
            ;; Loading routing / machines publishes the route classification
            ;; machinery and the `:machines/project-ssr-runtime-db` hook; the
            ;; reset fixture reloads both.
            [re-frame.machines]
            [re-frame.routing]
            [re-frame.routing.classification :as rf.routing.classification]
            [re-frame.schemas]
            [re-frame.schemas.malli]
            [re-frame.ssr.payload-policy :as rf.ssr.payload-policy]
            [re-frame.ssr.streaming :as rf.ssr.streaming]
            [re-frame.ssr.test-fixture :as rf.ssr.test-fixture]))

(use-fixtures :each rf.ssr.test-fixture/reset-runtime)

;; Frame A is the explicit target. The fixture makes `:rf/default` (frame B)
;; ambient, and B declares nothing, so a projection run under B leaks.
(def ^:private server-frame :review/ssr-target)
(def ^:private route-id      :route/oauth-callback)
(def ^:private machine-id    :review.ssr/auth)

(defn- frame-a-runtime-db
  "Frame A's runtime-db: the route's lowered `:current` declarations plus effect
  declarations for a machine `:data` token and an app-db `[:secret]`, a
  `:current` route slice and a machine snapshot each holding a secret."
  []
  (-> (rf.routing.classification/apply-route-classification
        {} (rf.routing.classification/validate+extract
             route-id {:sensitive [[:query :token]] :large [[:params :payload]]}))
      (rf.elision/apply-classification-effects
        {:sensitive [[:secret]
                     [:rf.runtime/machines :snapshots machine-id :data :token]]
         :large     [[:rf.runtime/machines :snapshots machine-id :data :blob]]})
      (assoc :rf.runtime/routing
             {:current            {:route-id  route-id
                                   :query     {:token     "secret-oauth-token"
                                               :return-to "/dashboard"}
                                   :params    {:payload "huge-callback-blob-value"}}
              :pending-navigation {:id "pn-1" :reason :can-leave}})
      (assoc :rf.runtime/machines
             {:snapshots  {machine-id {:state :authed
                                       :data  {:retries 2
                                               :token   "secret-jwt-snapshot"
                                               :blob    "huge-blob-value"}}}
              :spawned    {}})))

(defn- setup-frame-a! []
  (rf/reg-event :review.ssr/seed-db (fn [_ [_ db]] {:db db}))
  (rf/make-frame {:id             server-frame
                  :platform       :server
                  :initial-events [[:review.ssr/seed-db {:public "ok" :secret "app-db-secret"}]]})
  (rf/reg-machine machine-id
    {:initial :anon
     :data    {:retries 0 :token nil :blob nil}
     :schemas {:data [:map [:retries :int] [:token [:maybe :string]] [:blob [:maybe :string]]]}
     :states  {:anon {:on {:login :authed}} :authed {}}})
  (rf.frame/swap-runtime-db! server-frame (constantly (frame-a-runtime-db))))

(def ^:private frame-a-payload
  "Frame A's payload: sensitive values redacted, large values whole (the
  hydration wire applies no size elision), the rest verbatim."
  {:rf/version     1
   :rf/render-hash "hash"
   :rf/app-db      {:public "ok" :secret :rf/redacted}
   :rf/runtime-db  {:rf.runtime/routing  {:current {:route-id route-id
                                                    :query    {:token :rf/redacted :return-to "/dashboard"}
                                                    :params   {:payload "huge-callback-blob-value"}}}
                    :rf.runtime/machines {:snapshots {machine-id {:state :authed
                                                                  :data  {:retries 2
                                                                          :token   :rf/redacted
                                                                          :blob    "huge-blob-value"}}}
                                          :spawned   {}}}})

(defn- build-for-frame-a []
  (rf.ssr.streaming/build-final-payload
    server-frame "hash" {:payload :rf.ssr.payload/whole-app-db}))

(deftest build-final-payload-honours-explicit-frame-outside-with-frame
  (setup-frame-a!)
  (is (= frame-a-payload
         (binding [rf.frame/*current-frame* nil]
           (build-for-frame-a)))))

(deftest build-final-payload-explicit-frame-wins-over-ambient
  (setup-frame-a!)
  (let [hook-saw  (atom nil)
        orig-hook (rf.late-bind/get-fn :ssr/extend-runtime-db-projection)]
    (try
      ;; Resources is not on this classpath, so stub its hook: it must be handed
      ;; frame A as an argument while the ambient frame stays B.
      (rf.late-bind/set-fn! :ssr/extend-runtime-db-projection
                            (fn [_runtime-db frame-id]
                              (reset! hook-saw [frame-id (rf.frame/resolve-current-frame)])
                              {}))
      (is (= frame-a-payload (build-for-frame-a)))
      (is (= [server-frame :rf/default] @hook-saw))
      (finally
        (rf.late-bind/set-fn! :ssr/extend-runtime-db-projection orig-hook)))))

(deftest project-runtime-db-fails-closed-on-destroyed-frame
  ;; The machines hook classifies precisely only under a live frame, so a stale
  ;; explicit target redacts the machines slice whole; routing fails closed
  ;; through `project-egress`.
  (setup-frame-a!)
  (let [rt (rf.frame/frame-runtime-db-value server-frame)]
    (rf/destroy-frame! server-frame)
    (is (= {:rf.runtime/machines :rf/redacted :rf.runtime/routing :rf/redacted}
           (rf.ssr.payload-policy/project-runtime-db rt server-frame)))))

(defn- build-final-payload-losing-frame-a
  "Build the payload, running `lose!` after frame A's runtime-db is captured and
  before it is projected — an async host's teardown racing the build."
  [lose!]
  (let [orig rf.frame/frame-runtime-db-value]
    (with-redefs [rf.frame/frame-runtime-db-value (fn [fid] (let [v (orig fid)] (lose!) v))]
      (build-for-frame-a))))

(deftest build-final-payload-fails-closed-on-teardown-race
  (setup-frame-a!)
  (is (= {:rf/version 1 :rf/render-hash "hash" :rf/app-db :rf/redacted}
         (build-final-payload-losing-frame-a #(rf/destroy-frame! server-frame)))))

(deftest build-final-payload-fails-closed-on-frame-reregistration
  ;; A fresh frame A declares nothing; its open policy must not be substituted
  ;; for the old incarnation's.
  (setup-frame-a!)
  (is (= {:rf/version 1 :rf/render-hash "hash" :rf/app-db :rf/redacted}
         (build-final-payload-losing-frame-a
           #(do (rf/destroy-frame! server-frame)
                (rf/make-frame {:id server-frame :platform :server}))))))
