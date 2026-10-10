(ns re-frame.ssr-runtime-db-explicit-frame-test
  "`re-frame.ssr.payload-policy/project-runtime-db` takes its frame EXPLICITLY
  and fails closed when that frame is destroyed: the machines and routing
  slices redact whole rather than projecting under no policy.

  An ambient-resolving projector would ship classified route / machine state
  raw outside `rf/with-frame` (no frame, no walk) and under another frame's
  policy inside a mismatched one. The route and machine projection suites
  cannot see this: they classify and project under the same ambient frame."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.elision :as rf.elision]
            [re-frame.frame :as rf.frame]
            ;; Loading routing / machines publishes the route classification
            ;; machinery and the `:machines/project-ssr-runtime-db` hook; the
            ;; reset fixture reloads both.
            [re-frame.machines]
            [re-frame.routing]
            [re-frame.routing.classification :as rf.routing.classification]
            [re-frame.schemas]
            [re-frame.schemas.malli]
            [re-frame.ssr.payload-policy :as rf.ssr.payload-policy]
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

(deftest project-runtime-db-fails-closed-on-destroyed-frame
  ;; The machines hook classifies precisely only under a live frame, so a stale
  ;; explicit target redacts the machines slice whole; routing fails closed
  ;; through `project-egress`.
  (setup-frame-a!)
  (let [rt (rf.frame/frame-runtime-db-value server-frame)]
    (rf/destroy-frame! server-frame)
    (is (= {:rf.runtime/machines :rf/redacted :rf.runtime/routing :rf/redacted}
           (rf.ssr.payload-policy/project-runtime-db rt server-frame)))))
