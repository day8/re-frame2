(ns fixture.positive.query-retain-accepted-key-roster
  "POSITIVE fixture: the retired :query-retain key back in the accepted
  route-metadata key roster (EP-0037 R5, rf2-jlmgt). Mirrors
  re-frame.routing.registry's `reserved-route-keys` set — reinstating the key
  there is what would let `reg-route` accept it again as a bare key.")

(def ^:private reserved-route-keys
  "Route-metadata keys reg-route accepts as bare (unqualified) keys."
  #{:doc :path :params :query :query-defaults :query-retain
    :tags :parent :on-match :scroll :can-leave :can-enter
    :sensitive :large
    :head})
