(ns fixture.positive.query-retain-reg-route-meta
  "POSITIVE fixture: the retired :query-retain key in a reg-route metadata
  map (EP-0037 R5, rf2-jlmgt). A destination address is taken literally;
  cross-route query carry is an application-side pure function.")

(defn register! [rf]
  ;; RETIRED: the router no longer folds ambient query into a destination.
  (rf/reg-route :route/cart
                {:query-retain #{:theme :locale}
                 :query        [:map [:sort {:optional true} :keyword]]}
                "/cart"))
