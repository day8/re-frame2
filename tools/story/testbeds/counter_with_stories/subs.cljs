(ns counter-with-stories.subs
  "Counter subscriptions. Three layer-2 subs derived from the single
  `:count` slot in `app-db`. The Story playground's
  `:rf.assert/sub-equals` assertion fires against these — the
  variant body says 'after this play sequence, `@(subscribe [:count])`
  should equal N' and the assertion records pass/fail without throw."
  (:require [re-frame.core :as rf]))

(rf/reg-sub :count
  (fn [db _query]
    (:count db)))

(rf/reg-sub :count-doubled
  :<- [:count]
  (fn [c _query]
    (* 2 c)))

(rf/reg-sub :count-parity
  :<- [:count]
  (fn [c _query]
    (if (even? c) :even :odd)))

(rf/reg-sub :saving?
  (fn [db _query]
    (boolean (:saving? db))))
