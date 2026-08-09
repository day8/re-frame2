(ns app.refusals
  "The refusal set (design §5). A refused site still WORKS — `[:>]` is
  legal, so leaving it alone is a valid output — and the report is what
  turns a refusal from silence into an instruction."
  (:require [reagent.core :as r]))

(defn an-intent-vector-at-an-event-slot []
  ;; Silently dead under Reagent; throws at render under Hicasso.
  [:> C {:on-click [:save!]}])

(defn a-key-map-at-an-event-slot []
  [:> C {:on-key-down {"Enter" [:save!]}}])

(defn dangerously-set-inner-html-is-resurrected []
  [:> C {:dangerouslySetInnerHTML {:__html markup}}])

(defn the-ampersand-key []
  [:> C {:& {:a 1}}])

(defn an-as-element-island []
  [:> Grid {:on-render-cell (fn [row] (r/as-element [:span (:name row)]))}])

(defn reagent-api-residue []
  [:> C {:value @(r/atom 1)}])

(defn a-computed-props-slot []
  [:> C (merge base extra) "kid"])

(defn a-computed-prop-value []
  [:> C {:variant (pick-variant x) :other (compute y)}])

(defn the-r-arrow-site []
  [:r> C {:a 1}])

(defn the-f-arrow-site []
  [:f> C {:a 1}])
