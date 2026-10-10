(ns re-frame.ssr-flush-response-result-test
  "`ssr/flush-response-result!` returns the resolved response AND the
  projected `:public-error`, so a host adapter classifies the drain-time
  outcome without re-inferring it from `(:status resp)`; and a projector's
  output must be the closed four-key `:rf/public-error` with a 400..599
  status, or the locked generic 500 replaces it."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.error-emit :as rf.error-emit]
            [re-frame.frame :as rf.frame]
            [re-frame.interop :as rf.interop]
            [re-frame.ssr :as rf.ssr]
            [re-frame.ssr.test-fixture :as rf.ssr.test-fixture]
            [re-frame.trace :as rf.trace]))

;; Clear listeners BEFORE the reset: the reset re-installs the façade's
;; always-on projection listener, which is the production projection path.
(use-fixtures :each
  (fn [t]
    (rf.error-emit/clear-error-listeners!)
    (rf.ssr.test-fixture/reset-runtime t)))

(defn- make-server-frame [id]
  (rf/make-frame {:id id :platform :server
                  :ssr      {:public-error-id   :rf.ssr/default-error-projector
                             :dev-error-detail? false}})
  id)

(defn- buffer-error!
  "Buffer a projecting error against `frame-id` the way a drain-time error
  does. Emits on both the dev bus and the always-on axis, so the namespace
  holds under the production gate. `extra-tags` carries the discriminator a
  projector arm is gated on (the 404 arm needs `:kind :route`)."
  ([frame-id operation] (buffer-error! frame-id operation nil))
  ([frame-id operation extra-tags]
   (let [tags (merge {:frame frame-id :recovery :for-test} extra-tags)]
     (rf.trace/emit-error! operation tags)
     (rf.error-emit/dispatch-error-record!
       (merge {:error operation :time (rf.interop/now-ms)} tags)))))

(deftest two-frames-return-own-public-error-no-bleed
  (let [f500 (make-server-frame :ssr/frr-a-500)
        f404 (make-server-frame :ssr/frr-b-404)]
    (buffer-error! f500 :rf.error/handler-exception)
    (buffer-error! f404 :rf.error/no-such-handler {:kind :route})
    (let [a (rf.ssr/flush-response-result! f500)
          b (rf.ssr/flush-response-result! f404)]
      (is (= [[500 500] [404 404]]
             (for [r [a b]] [(-> r :public-error :status) (-> r :response :status)]))
          "each frame's [public-error status, response status]"))))

(deftest a-buffered-server-fault-outranks-a-client-fault-in-either-order
  (testing "a handler 500 and a route 404 in one request settle at 500,
            whichever buffered first"
    (let [fault-first (make-server-frame :ssr/frr-500-then-404)
          miss-first  (make-server-frame :ssr/frr-404-then-500)]
      (buffer-error! fault-first :rf.error/handler-exception)
      (buffer-error! fault-first :rf.error/no-such-handler {:kind :route})
      (buffer-error! miss-first :rf.error/no-such-handler {:kind :route})
      (buffer-error! miss-first :rf.error/handler-exception)
      (is (= [[500 500] [500 500]]
             (for [f [fault-first miss-first]
                   :let [r (rf.ssr/flush-response-result! f)]]
               [(-> r :public-error :status) (-> r :response :status)]))
          "each frame's [public-error status, response status]"))))

(deftest reading-the-response-before-the-settle-leaves-it-intact
  (let [f (make-server-frame :ssr/frr-midpoint-read)]
    (buffer-error! f :rf.error/handler-exception)
    (rf.ssr/peek-response f)
    (rf.ssr/get-response f)
    (rf.ssr/flush-response! f)
    (let [{:keys [response public-error]} (rf.ssr/flush-response-result! f)]
      (is (= [500 500] [(:status public-error) (:status response)])
          "the settle still sees the buffered fault"))))

(deftest redirect-precedence-suppresses-status-stamp-still-returns-error
  (testing "a redirect keeps its 302 over the projected 500, and the
            projected map is still returned"
    (let [fid (make-server-frame :ssr/frr-redirect)]
      ((requiring-resolve 're-frame.ssr.response/redirect-fx)
       {:frame fid} {:location "/login"})
      (buffer-error! fid :rf.error/handler-exception)
      (let [{:keys [response public-error]} (rf.ssr/flush-response-result! fid)]
        (is (= [{:location "/login" :status 302} 302 500]
               [(:redirect response) (:status response) (:status public-error)]))))))

(defn- project-with
  "Register `projector-fn` and project a `:rf.error/handler-exception` through
  a server frame configured to use it. Returns the public-error map."
  [projector-fn]
  (rf/reg-error-projector :test/shape-projector projector-fn)
  (let [f (rf.frame/make-anon-frame-record!
            {:platform :server
             :ssr      {:public-error-id   :test/shape-projector
                        :dev-error-detail? false}})]
    (rf.ssr/project-error f {:op-type   :error
                             :operation :rf.error/handler-exception
                             :tags      {:frame f}})))

(def ^:private locked-500
  {:status 500 :code :internal-error :message "Something went wrong" :retryable? false})

(deftest conforming-4xx-and-5xx-projections-pass
  (let [conforming [{:status 401 :code :unauthorised :message "Sign in" :retryable? false}
                    {:status 503 :code :unavailable :message "Try later" :retryable? true}]]
    (is (= conforming (map #(project-with (constantly %)) conforming)))))

(deftest out-of-range-status-falls-back-to-locked-500
  (is (= locked-500 (project-with (constantly {:status 200 :code :ok
                                               :message "fine" :retryable? false})))))

(deftest extra-key-including-details-falls-back-to-locked-500
  (testing "a projector cannot smuggle its own :details, or any stray key,
            past the four-key gate"
    (is (= [locked-500 locked-500]
           [(project-with (constantly {:status 500 :code :internal-error
                                       :message "boom" :retryable? false
                                       :details {:secret "leak"}}))
            (project-with (constantly {:status 400 :code :bad-request
                                       :message "bad" :retryable? false
                                       :internal-note "x"}))]))))
