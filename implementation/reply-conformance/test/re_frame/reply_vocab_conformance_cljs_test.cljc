(ns re-frame.reply-vocab-conformance-cljs-test
  "Uniform managed-async reply envelope across family builders.
  Family suites own the full reply shapes (Managed-Effects)."
  (:require [clojure.test :refer [deftest is testing]]
            [re-frame.reply :as rf.reply]
            [re-frame.http.reply :as rf.http.reply]
            [re-frame.resources.reply :as rf.resources.reply]
            [re-frame.machines.reply :as rf.machines.reply]
            [re-frame.routing.reply :as rf.routing.reply]))

(def ^:private completion-time-ms 1781078400456)

(def ^:private http-ctx
  {:request-id   :article/by-id
   :origin-event [:article/load {:id 42}]
   :attempt      1
   :frame        :app/main})

(def ^:private resource-vp
  {:work/id      [:rf.work/resource [:rf.scope/global :article/by-slug {:slug "w"}] 4]
   :resource/key [:rf.scope/global :article/by-slug {:slug "w"}]
   :scope        :rf.scope/global
   :generation   4
   :rf.frame/id  :app/main})

(def ^:private mutation-vp
  {:work/id     [:rf.work/resource [:rf.mutation :form/save-1] 2]
   :instance-id :form/save-1
   :mutation-id :article/save
   :scope       :rf.scope/global
   :generation  2
   :rf.frame/id :app/main})

(def ^:private machine-ctx
  {:actor-id          :auth/flow#1
   :parent-id         :auth/main
   :work-bearing-path [:authenticating]
   :frame             :app/main})

(def ^:private timer-ctx
  {:actor-id  :a/multi
   :state     :loading
   :delay     30000
   :decl-path [:loading]
   :frame     :app/main})

(def ^:private route-ctx
  {:route-id  :route/article
   :nav-token "nav-1"
   :loader-id :article/loaded
   :frame     :app/main})

(def ^:private a-failure {:kind :rf.http/http-5xx :status 503 :body "down"})
(def ^:private an-abort {:kind :rf.http/aborted :reason :user})

(defn- resource-stale-out [scoped-key carried current kind reason t]
  (rf.resources.reply/stale-reply
    {:carried {:work/id [:rf.work/resource scoped-key carried] :generation carried}
     :current {:work/id [:rf.work/resource scoped-key current] :generation current}
     :extra   (merge {:rf.reply/work-id      [:rf.work/resource scoped-key carried]
                      :rf.reply/work-kind    kind
                      :rf.frame/id           :app/main
                      :rf.reply/stale-reason reason}
                     t)}))

;; Missing situations are not produced by that family; :untimed builders take
;; no completion time. :stale-out builders return a suppression outcome.
(def ^:private families
  [{:family    :http
    :work-head :rf.work/http
    :untimed   #{:stale}
    :success   #(rf.http.reply/success-reply (merge http-ctx %) {:title "Welcome"})
    :error     #(rf.http.reply/failure-reply (merge http-ctx %) a-failure)
    :cancel    #(rf.http.reply/failure-reply (merge http-ctx %) an-abort)
    :stale-out #(rf.http.reply/suppress (merge http-ctx %) [:rf.work/http :article/by-id 2 1])}

   {:family    :resource
    :work-head :rf.work/resource
    :success   #(rf.resources.reply/success-reply resource-vp {:title "Welcome"}
                  (merge {:work-kind rf.resources.reply/work-kind-resource} %))
    :error     #(rf.resources.reply/failure-reply resource-vp a-failure
                  (merge {:work-kind rf.resources.reply/work-kind-resource} %))
    :cancel    #(rf.resources.reply/failure-reply resource-vp an-abort
                  (merge {:work-kind rf.resources.reply/work-kind-resource} %))
    :stale-out #(resource-stale-out [:rf.scope/global :r {}] 4 5 :resource
                                    :resource/generation-mismatch %)}

   {:family    :mutation
    :work-head :rf.work/resource
    :success   #(rf.resources.reply/success-reply mutation-vp {:slug "w" :title "Welcome"}
                  (merge {:work-kind rf.resources.reply/work-kind-mutation} %))
    :error     #(rf.resources.reply/failure-reply mutation-vp a-failure
                  (merge {:work-kind rf.resources.reply/work-kind-mutation} %))
    :cancel    #(rf.resources.reply/failure-reply mutation-vp an-abort
                  (merge {:work-kind rf.resources.reply/work-kind-mutation} %))
    :stale-out #(resource-stale-out [:rf.mutation :form/save-1] 2 3 :mutation
                                    :mutation/superseded %)}

   {:family    :machine
    :work-head :rf.work/machine
    :untimed   #{:cancel}
    :success   #(rf.machines.reply/success-reply (merge machine-ctx %) {:user-id "u-42"})
    :error     #(rf.machines.reply/error-reply (merge machine-ctx %) {:reason :bad-creds})
    :cancel    #(rf.machines.reply/cancelled-actor-reply (merge machine-ctx {:reason :explicit} %))
    :stale     #(rf.machines.reply/stale-spawn-reply (merge machine-ctx {:current-generation nil} %))}

   {:family    :timer
    :work-head :rf.work/timer
    :untimed   #{:cancel}
    :success   #(rf.machines.reply/after-fired-reply (merge timer-ctx {:epoch 1} %))
    :cancel    #(rf.machines.reply/cancelled-timer-reply (merge timer-ctx {:epoch 1 :reason :on-exit} %))
    :stale     #(rf.machines.reply/after-stale-reply
                  (merge timer-ctx {:scheduled-epoch 1 :current-epoch 2} %))}

   {:family    :route
    :work-head :rf.work/route
    :success   #(rf.routing.reply/live-reply (merge route-ctx %) {:title "Welcome"})
    :stale-out #(rf.routing.reply/suppress (merge route-ctx %) "nav-2")}])

(defn- cells
  []
  (for [{:keys [family stale-out] :as descriptor} families
        situation [:success :error :cancel :stale]
        :let [build (or (get descriptor situation)
                        (when (and (= :stale situation) stale-out)
                          (comp :reply stale-out)))]
        :when build]
    [family situation build descriptor]))

(def ^:private situation-statuses
  {:success [:ok :completed]
   :error   [:error :failed]
   :cancel  [:cancelled :cancelled]
   :stale   [:stale :suppressed]})

(deftest every-reply-is-a-canonical-envelope-for-its-situation
  (doseq [[family situation build {:keys [work-head]}] (cells)
          :let [reply (build {})
                wid   (:rf.reply/work-id reply)]]
    (testing (str family " / " situation)
      (is (rf.reply/valid-reply? reply) (pr-str (rf.reply/validate-reply reply)))
      (is (= (situation-statuses situation)
             ((juxt :status :rf.reply/work-status) reply)))
      (is (= [true work-head] [(vector? wid) (first wid)])))))

;; Missing causal evidence must stay absent rather than become a nil timestamp.
(deftest completion-time-propagates-when-supplied-and-is-omitted-when-absent
  (doseq [[family situation build {:keys [untimed]}] (cells)
          :when (not (contains? untimed situation))]
    (is (= [completion-time-ms false]
           [(:completed-at (build {:completed-at completion-time-ms}))
            (contains? (build {}) :completed-at)])
        (str family " " situation))))

(deftest stale-replies-carry-a-carried-and-current-correlation-gate
  (doseq [{:keys [family stale-out]} families
          :when stale-out
          :let [outcome (stale-out {})]]
    (is (= [false :suppressed true true]
           [(:deliver? outcome)
            (:rf.reply/work-status outcome)
            (some? (get-in outcome [:trace :rf.reply/carried]))
            (some? (get-in outcome [:trace :rf.reply/current]))])
        (str family " stale suppression delivers nothing and traces both correlation facts"))))
