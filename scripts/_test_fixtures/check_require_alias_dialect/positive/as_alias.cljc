(ns fixtures.as-alias
  "POSITIVE fixture: `:as-alias` binds an alias without loading the namespace,
  and the dialect governs it exactly as it governs `:as` — the alias is what a
  reader sees at the use site and in every `::alias/key` it resolves. A checker
  that reads only `:as` waves this one through (1 finding: bare `schemas`)."
  (:require [re-frame.core :as rf]
            [re-frame.schemas :as-alias schemas]))

(defn tag [m]
  (assoc m ::schemas/kind (rf/frame-id)))
