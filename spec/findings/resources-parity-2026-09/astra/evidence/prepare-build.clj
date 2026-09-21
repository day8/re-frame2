(require '[clojure.edn :as edn] '[clojure.java.io :as io])
(let [root (io/file "<HOME>/code/re-frame2")
      implementation (io/file root "implementation")
      out (io/file root "ai/findings/Resources/astra/evidence")
      cfg (edn/read-string (slurp (io/file implementation "shadow-cljs.edn")))
      path #(.getCanonicalPath (io/file implementation %))
      new-config (-> (select-keys cfg [:dependencies :compiler-options])
                     (assoc :source-paths (conj (mapv path (:source-paths cfg)) (.getCanonicalPath (io/file out "cljs")))
                            :js-options {:js-package-dirs [(.getCanonicalPath (io/file implementation "node_modules"))]}
                            :nrepl {:port 0}
                            :cache-root (.getCanonicalPath (io/file out "shadow-cache"))
                            :builds {:conduit {:target :browser
                                              :output-dir (.getCanonicalPath (io/file out "browser/js"))
                                              :asset-path "/js"
                                              :modules {:main {:init-fn 'realworld-resources.core/run}}
                                              :devtools {:enabled false}}
                                     :research-tests {:target :node-script
                                                      :main 'resources-research.runner/main
                                                      :output-to (.getCanonicalPath (io/file out "research-tests.js"))
                                                      :devtools {:enabled false}}}))]
  (.mkdirs (io/file out "cljs"))
  (.mkdirs (io/file out "browser"))
  (spit (io/file out "shadow-cljs.edn") (pr-str new-config))
  (spit (io/file out "package.json") "{\"private\":true}")
  (spit (io/file out "browser/index.html") "<!doctype html><html><head><meta charset='utf-8'><title>Resources Conduit research</title></head><body><div id='app'></div><script src='/js/main.js'></script></body></html>")
  (println "Prepared isolated browser build with absolute source paths at" (.getCanonicalPath out)))
