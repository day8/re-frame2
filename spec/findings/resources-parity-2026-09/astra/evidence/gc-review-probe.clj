(require '[re-frame.core :as rf]
         '[re-frame.fx :as fx]
         '[re-frame.resources]
         '[re-frame.http.managed]
         '[re-frame.schemas]
         '[re-frame.test-support :as ts]
         '[re-frame.substrate.plain-atom :as plain])
(println "gate root: C:/Users/miket/code/re-frame2")
(let [clock (atom 0) schedules (atom []) requests (atom [])
      fixture (ts/make-reset-runtime-fixture {:adapter plain/adapter})]
  (fixture
   (fn []
     (fx/reg-fx :rf.http/managed (fn [_ args] (swap! requests conj args)))
     (fx/reg-fx :rf.resource/schedule-timers
       (fn [_ args]
         (when-let [delay (get-in args [:timers :gc])]
           (swap! schedules conj {:at @clock :due (+ @clock delay) :key (:resource/key args)}))))
     (rf/reg-resource :research/gc
       {:scope :rf.scope/global :params-schema [:map] :gc-after-ms 1000}
       (fn [_ _] {:request {:method :get :url "/gc"}}))
     (rf/dispatch-sync [:rf.resource/ensure {:resource :research/gc :params {} :owner [:app :research/gc]}])
     (rf/dispatch-sync (conj (:on-success (last @requests)) {:status :ok :value {:answer 42}}))
     (let [read-state #(rf/resource-state {:frame :rf/default :resource :research/gc :params {} :scope :rf.scope/global})
           first-timer (last @schedules)]
       (assert (= 1000 (:due first-timer)))
       ;; Fire the scheduled check while still owned: it keeps data and re-arms.
       (reset! clock 1000)
       (rf/dispatch-sync [:rf.resource.internal/gc-fired {:resource/key (:key first-timer)}])
       (assert (some? (read-state)))
       (assert (= 2000 (:due (last @schedules))))
       ;; Release just before the pending check. Release does not restart its interval.
       (reset! clock 1990)
       (let [before (count @schedules)]
         (rf/dispatch-sync [:rf.resource/release-owner {:owner [:app :research/gc]}])
         (assert (= before (count @schedules)))
         (assert (some? (read-state)))
         (reset! clock 2000)
         (rf/dispatch-sync [:rf.resource.internal/gc-fired {:resource/key (:key first-timer)}])
         (assert (nil? (read-state)))
         (prn {:gc-policy-ms 1000 :controlled-scheduler @schedules :owner-released-at 1990
               :collected-at 2000 :interval-after-release 10 :release-rearmed? false
               :limit "Captured timer requests and explicit scheduled wake events; not a wall-clock browser timing measurement."}))))))
(println "GC review assertions passed.")
