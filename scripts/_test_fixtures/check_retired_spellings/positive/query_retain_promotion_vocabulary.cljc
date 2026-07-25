(ns fixture.positive.query-retain-promotion-vocabulary
  "POSITIVE fixture: the retired :query-retain key back in the query-key
  PROMOTION vocabulary (EP-0037 R5, rf2-jlmgt). R5 shrank the vocabulary from
  three sources to two — :query and :query-defaults — so a third source
  widening it (and thereby suppressing the promotion advisory) is drift.")

(defn- promoted-query-keys [metadata]
  (into #{}
        (mapcat (fn [slot] (keys (get metadata slot))))
        [:query :query-defaults :query-retain]))
