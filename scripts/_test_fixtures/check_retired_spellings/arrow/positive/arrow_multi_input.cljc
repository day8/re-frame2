(ns fixture.arrow.positive.arrow-multi-input
  "POSITIVE fixture (rule f): a two-input retired chain on ONE line, so the
  fixture's expected count stays a per-LINE assertion rather than a per-token
  one — the gate reports a line, and two arrows on one line are one finding."
  (:require [re-frame.core :as rf]))

(rf/reg-sub :cart/visible :<- [:cart/by-price] :<- [:cart/filter]
  (fn [[items f] _] (filter f items)))
