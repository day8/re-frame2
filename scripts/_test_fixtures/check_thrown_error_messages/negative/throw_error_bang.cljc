(ns fixture.negative.throw-error-bang
  "NEGATIVE fixture: the canonical builder call — error/throw-error! — never
  touches `ex-info` at the call site, so there is nothing to flag. MUST stay
  green."
  (:require [re-frame.error :as error]))

(defn boom [x]
  (error/throw-error!
    :rf.error/no-adapter-installed
    'rf/init!
    "rf/init! cannot continue because no adapter is installed; require an adapter ns and install it before boot."
    {:extra {:bad x}}))
