(ns fixture.arrow.negative.arrow-lookalikes
  "NEGATIVE fixture (rule f): tokens that merely CONTAIN the retired
  characters. The keyword-token boundary is what keeps the rule off them, and
  it is the whole prose defence on the Markdown surface where nothing is
  masked."
  (:require [re-frame.core :as rf]))

(def threading-arrows [:<-- :<-> :<-foo :x/<- ::<-])
(def a-longer-keyword :<-chain)
(rf/reg-sub :arrow/names (fn [db _] (:<-- db)))
