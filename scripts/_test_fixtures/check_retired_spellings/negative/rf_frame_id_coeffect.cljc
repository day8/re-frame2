(ns fixture.negative.rf-frame-id-coeffect
  "NEGATIVE fixture: the CANONICAL :rf.frame/id event-context coeffect read
  in all three accessor forms. This is the CORRECT spelling that replaced
  the retired bare :frame coeffect — it must NEVER fire the gate."
  (:require [re-frame.interceptor :as interceptor]))

(defn handler-accessor [ctx]
  (interceptor/get-coeffect ctx :rf.frame/id))

(defn handler-kwfn [ctx]
  (:rf.frame/id (:coeffects ctx)))

(defn handler-getin [ctx]
  (get-in ctx [:coeffects :rf.frame/id]))
