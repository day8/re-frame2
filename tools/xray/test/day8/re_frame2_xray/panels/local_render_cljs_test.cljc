(ns day8.re-frame2-xray.panels.local-render-cljs-test
  "Real frame-policy coverage for Xray's on-box local-render egress."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.elision :as rf.elision]
            [re-frame.frame :as rf.frame]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.test-support :as rf.test-support]
            ;; Publishes routing's seed table and classification hook; otherwise
            ;; route projections walk the root unclassified.
            [re-frame.routing]
            [day8.re-frame2-xray.panels.local-render :as local-render]))

(def secure-frame :app/secure)
(def plain-frame :app/plain)
(def route-frame :app/routed)

(defn- init-fn []
  (doseq [frame-id [plain-frame secure-frame route-frame]]
    (rf/make-frame {:id frame-id}))
  (rf.frame/swap-runtime-db! secure-frame
    (fn [rt] (rf.elision/apply-classification-effects rt
               {:sensitive [[:auth :token]]
                :large [[:catalog :rows]]})))
  ;; A real route-owned declaration, classifying the frame's current slice,
  ;; rather than hand-written absolute paths.
  (rf/reg-route :route/user {:sensitive [[:params :token] [:query :token]]} "/user/:token")
  (rf.frame/swap-runtime-db! route-frame
    (fn [rt] (assoc-in rt [:rf.runtime/routing :current] {:route-id :route/user}))))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter rf.substrate.plain-atom/adapter
     :ambient-frame nil
     :init-fn init-fn}))

(def ^:private app-db-value
  {:auth {:token "secret-session-jwt-abc123" :user-id 42}
   :catalog {:rows (vec (range 300))}
   :ui {:open? true :tab :home}})

(deftest local-render-default-redacts-sensitive-keeps-large
  (is (= (assoc-in app-db-value [:auth :token] :rf/redacted)
         (local-render/local-render-value app-db-value secure-frame))))

(deftest local-render-applies-the-observed-frames-policy
  (is (= app-db-value
         (local-render/local-render-value app-db-value plain-frame))))

(deftest local-raw-opt-in-reveals-sensitive
  (is (= "secret-session-jwt-abc123"
         (get-in (local-render/local-render-value app-db-value secure-frame true)
                 [:auth :token]))))

(deftest local-render-fails-closed-under-bound-ambient-frame
  ;; Borrowing this frame's empty policy would expose the token.
  (rf/with-frame plain-frame
    (doseq [observed-frame [nil :app/does-not-exist]]
      (is (= :rf/redacted
             (local-render/local-render-value app-db-value observed-frame))))))

(def ^:private route-slice
  {:route-id :route/user
   :params {:token "secret-abc123" :tab "posts"}
   :query {:token "secret-abc123" :tab "posts"}
   :fragment "step-3"
   :transition :settled})

(deftest local-render-route-slice-projects-both-covered-keys
  (is (= (-> route-slice
             (assoc-in [:params :token] :rf/redacted)
             (assoc-in [:query :token] :rf/redacted))
         (local-render/local-render-route-slice route-slice route-frame)))
  (is (= route-slice
         (local-render/local-render-route-slice route-slice plain-frame))))

(deftest local-render-route-slice-touches-only-keys-the-router-wrote
  (is (= (-> route-slice (dissoc :query) (assoc-in [:params :token] :rf/redacted))
         (local-render/local-render-route-slice (dissoc route-slice :query) route-frame))))

(deftest local-render-route-slice-fails-closed-on-an-unreachable-frame
  (is (= (assoc route-slice :params :rf/redacted :query :rf/redacted)
         (local-render/local-render-route-slice route-slice :app/does-not-exist))))
