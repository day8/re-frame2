(ns user
  "JVM-side REPL entry. `(user/refresh)` reloads changed namespaces
   via clojure.tools.namespace; useful when iterating on event /
   subscription handlers without restarting the shadow-cljs build."
  (:require [clojure.tools.namespace.repl :as tn]))

(defn refresh []
  (tn/refresh))
