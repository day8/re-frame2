(ns fixture.arrow.positive.arrow-in-metadata-form
  "POSITIVE fixture (rule f): the retired chain behind a metadata map. The
  registration is over-specified as well as retired; either way the token is
  the violation."
  (:require [re-frame.core :as rf]))

(rf/reg-sub :cart/total {:doc "Cart total."} :<- [:cart/items] (fn [items _] items))
