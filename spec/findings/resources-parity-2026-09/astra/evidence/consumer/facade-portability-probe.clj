(require '[re-frame.core :as rf])
(println "consumer:" (System/getProperty "user.dir"))
(let [public-names (into {} (for [sym '[clear clear-resource clear-mutation resource-state mutation-state resource-meta mutation-meta]]
                             [sym (boolean (ns-resolve 're-frame.core sym))]))]
  (prn public-names)
  (assert (true? (get public-names 'clear)))
  (assert (false? (get public-names 'clear-resource)))
  (assert (false? (get public-names 'clear-mutation)))
  (assert (true? (get public-names 'resource-state)))
  (assert (true? (get public-names 'mutation-state))))
;; The tutorial's actual JS interop form, isolated to test the claimed
;; rename-only portability. This does not pretend to compile the full tutorial.
(let [failure (try
                (load-string "(defn tutorial-token [] (some-> (.-localStorage js/globalThis) (.getItem \"jwtToken\")))")
                nil
                (catch Throwable t t))]
  (assert failure "Unguarded browser interop must not be described as JVM-portable by renaming a file.")
  (println "Rename-only portability counterexample:" (.getMessage ^Throwable (or (.getCause ^Throwable failure) failure))))
(println "Facade exports and portability counterexample verified.")
