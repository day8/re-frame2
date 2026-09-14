;; Probe 3: unstubbed vs stubbed fx with the CORRECT v2 reg-fx arity (fn [ctx args]).
(ns probe3
  (:require [re-frame.core :as rf]
            [re-frame.frame :as rf.frame]
            [re-frame.registrar :as rf.registrar]
            [re-frame.substrate.plain-atom :as pa]
            [re-frame.story :as story]
            [re-frame.story.play.runner-events :as re]
            [re-frame.epoch]))
(defn result [f] (let [r (deref f 15000 ::timeout)] (if (= r ::timeout) {:status :TIMEOUT} r)))
(defn line [& xs] (println (apply str xs)))
(story/clear-all!) (rf.registrar/clear-all!) (reset! rf.frame/frames {})
(rf/init! pa/adapter) (reset! re/run-state {}) (story/install-canonical-vocabulary!) (rf.frame/ensure-default-frame!)
(def http-calls (atom []))
(rf/reg-event :probe/fire (fn [_ _] {:fx [[:probe/http {:url "/x"}]]}))
(rf/reg-event :probe/stub-reply (fn [{:keys [db]} [_ payload]] {:db (assoc db :stubbed payload)}))
(rf/reg-fx :probe/http (fn [_ctx args] (swap! http-calls conj args)))
(story/reg-story* :story.probe {:doc "probe"})
;; V1: unstubbed, correct arity → real side effect runs headless?
(story/reg-variant* :story.probe/unstubbed {:script [[:dispatch-sync [:probe/fire]] [:assert [:rf.assert/effect-emitted :probe/http]]]})
(let [r (result (story/run :story.probe/unstubbed))]
  (line "V1 unstubbed status=" (:status r) " real-calls=" (count @http-calls) " effects=" (pr-str (mapv #(select-keys % [:fx-id :outcome]) (:effects r)))))
;; V2: force-fx-stub → no real call, stub event lands
(reset! http-calls [])
(story/reg-variant* :story.probe/stubbed {:decorators [[:rf.story/force-fx-stub :probe/http {:status 200}]]
                                          :script [[:dispatch-sync [:probe/fire]] [:assert [:rf.assert/effect-emitted :probe/http]] ]})
(let [r (result (story/run :story.probe/stubbed))]
  (line "V2 stubbed status=" (:status r) " real-calls=" (count @http-calls) " effects=" (pr-str (mapv #(select-keys % [:fx-id :outcome :stubbed? :redirected-to]) (:effects r)))
        " assertions=" (pr-str (mapv (juxt :assertion :status) (:assertions r)))))
;; V3: errored effect handler (wrong arity) → status?
(reset! http-calls [])
(rf/reg-fx :probe/boom (fn [_ctx _args] (throw (ex-info "boom" {}))))
(rf/reg-event :probe/fire-boom (fn [_ _] {:fx [[:probe/boom {}]]}))
(story/reg-variant* :story.probe/erroring-fx {:script [[:dispatch-sync [:probe/fire-boom]] [:assert [:rf.assert/effect-emitted :probe/boom]]]})
(let [r (result (story/run :story.probe/erroring-fx))]
  (line "V3 erroring-fx status=" (:status r) " (all assertions pass? " (every? :passed? (:assertions r)) ") effects=" (pr-str (mapv #(select-keys % [:fx-id :outcome]) (:effects r)))))
(line "DONE") (shutdown-agents) (System/exit 0)
