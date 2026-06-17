(ns fixture.positive.str-id-of-payload
  "POSITIVE fixture: (str (:rf.error/id payload)) as the ex-info message —
  the frame.cljc payload-throw shape that pulls the discriminator off a
  pre-built payload map and stringifies it.")

(defn boom [payload]
  (throw (ex-info (str (:rf.error/id payload)) payload)))
