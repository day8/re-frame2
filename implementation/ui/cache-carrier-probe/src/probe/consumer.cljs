(ns probe.consumer
  "The cache-blockable consumer: it USES the macro, so on a cold build the macro
   expands and stamps both carriers; on a warm disk-cache hit it must NOT
   recompile, yet the carriers must survive in the restored analyzer data."
  (:require-macros [probe.macros :refer [defprobe]]))

(defprobe alpha {:kind :view :props #{:a :b :c} :n 42 :label "carrier-A-alpha"})
(defprobe beta {:kind :element :props #{:x} :n 7 :nested {:deep [1 2 3]}})

(defn main []
  (js/console.log "probe consumer" (str alpha) (str beta)))
