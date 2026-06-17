(ns fixture.negative.human-message-builder
  "NEGATIVE fixture: the conformant shape — (ex-info (human-message …) …).
  The message is DERIVED from a human reason + the token, so it is never a
  bare keyword. MUST stay green."
  (:require [re-frame.error :as error]))

(defn boom [error-id reason x]
  (throw (ex-info (error/human-message error-id reason)
                  {:rf.error/id error-id
                   :reason      reason
                   :bad         x})))
