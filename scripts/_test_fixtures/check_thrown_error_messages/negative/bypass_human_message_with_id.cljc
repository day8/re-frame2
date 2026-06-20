(ns fixture.negative.bypass-human-message-with-id
  "NEGATIVE fixture (rf2-krrv87 gate widen): a SHARED-payload throw that
  routes its message through error/human-message (the central derivation the
  builders use) while its ex-data carries :rf.error/id. This is the
  conformant ex-info-from-data-style shape — MUST stay green."
  (:require [re-frame.error :as error]))

(defn boom [payload]
  (throw (ex-info (error/human-message (:rf.error/id payload) (:reason payload))
                  (assoc payload :rf.error/id :rf.error/shared-payload))))
