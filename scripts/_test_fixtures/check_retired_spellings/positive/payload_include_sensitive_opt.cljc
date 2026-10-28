(ns fixture.positive.payload-include-sensitive-opt
  "POSITIVE fixture: the retired :payload-include-sensitive option on an
  ssr-handler. A :payload path permits the classified leaf it names.")

(def handler
  ;; RETIRED: the permit is a path in :payload.
  (ssr.ring/ssr-handler {:payload [:session]
                         :payload-include-sensitive [[:session :csrf]]}))
