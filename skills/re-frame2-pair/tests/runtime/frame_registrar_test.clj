;;;; tests/runtime/frame_registrar_test.clj — the MCP `handler-meta` /
;;;; `list-handlers` / `describe-image` tools resolve registrations through the
;;;; OPERATING FRAME's image generation, so the preload must route through the
;;;; public `:frame` facade reads (`rf/handler-meta`, `rf/registrations`,
;;;; `rf/frame-generation`), and `describe-image` must degrade gracefully on a
;;;; live imageless frame while still failing loud on an unknown one.
;;;;
;;;; Run: bb tests/runtime/frame_registrar_test.clj

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

(deftest describe-image-reads-the-generation-and-guards-no-generation
  (let [f (rt/defn-named 'describe-image)]
    (is (rt/calls? 'rf/frame-generation f) "describe-image must read (rf/frame-generation frame)")
    (is (rt/calls? 'catch f) "describe-image must catch the no-generation throw")
    (is (rt/mentions? :rf.error/frame-no-generation f)
        "only :rf.error/frame-no-generation is softened")
    (is (rt/mentions? :live-frame-ids f)
        "a LIVE imageless frame degrades; a target naming no live frame still fails loud")
    (is (rt/mentions? :no-generation? f) "the graceful result is :no-generation?")
    (is (rt/calls? 'throw f) "any other throw is re-raised")))

(deftest orient-rebased-on-frame-registry-view
  (let [orient (rt/defn-named 'orient)]
    (is (rt/calls? 'rf/frame-generation (rt/defn-named 'frame-registry-view))
        "frame-registry-view must resolve through rf/frame-generation")
    (is (rt/calls? 'frame-registry-view orient)
        "orient must base its :registry on the operating frame's generation")
    (is (rt/calls? 'process-registry-view orient)
        "orient must keep the process-wide view as the fallback")))

(let [{:keys [fail error]} (run-tests 'frame-registrar-test)]
  (System/exit (if (zero? (+ fail error)) 0 1)))
