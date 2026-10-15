(load-file (str (.getParent (java.io.File. *file*)) "/_support.clj"))

(ns frame-registrar-test
  (:require [clojure.test :refer [deftest is run-tests]]
            [runtime-support :as rt]))

(deftest frame-registrar-fns-route-through-the-facade-frame-reads
  (doseq [[sym facade] '[[frame-registrar-describe      rf/handler-meta]
                         [frame-registrar-list          rf/registrations]
                         [frame-registrar-registrations rf/registrations]]]
    (is (rt/calls? facade (rt/defn-named sym))
        (str sym " must route through (" facade " {:frame …})")))
  (is (rt/mentions? :frame (rt/defn-named 'frame-registrar-describe))
      "frame-registrar-describe must pass a :frame-keyed query map"))

(deftest orient-rebased-on-frame-registry-view
  (let [orient (rt/defn-named 'orient)
]
    (is (rt/calls? 'frame-registry-view orient)
        "orient must base its :registry on the operating frame's generation")
    (is (rt/calls? 'process-registry-view orient)
        "orient must keep the process-wide view as the fallback")))

(let [{:keys [fail error]} (run-tests 'frame-registrar-test)]
  (System/exit (if (zero? (+ fail error)) 0 1)))
