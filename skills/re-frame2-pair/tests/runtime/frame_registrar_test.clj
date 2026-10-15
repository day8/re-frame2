;;;; tests/runtime/frame_registrar_test.clj — the MCP `handler-meta` /
;;;; `list-handlers` / `describe-image` tools and `orient`'s registry resolve
;;;; registrations as the OPERATING FRAME does, so the preload must route through
;;;; the public `:frame` facade reads (`rf/handler-meta`, `rf/registrations`,
;;;; `rf/frame-generation`), and `describe-image` must describe a frame that
;;;; resolves the live registration store while still failing loud on an
;;;; unknown one.
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

(deftest describe-image-reads-the-generation-and-describes-a-live-store-frame
  (let [f    (rt/defn-named 'describe-image)
        live (rt/defn-named 'describe-live-store-frame)]
    (is (rt/calls? 'rf/frame-generation f) "describe-image must read (rf/frame-generation frame)")
    (is (rt/calls? 'catch f) "describe-image must catch the no-generation throw")
    (is (rt/mentions? :rf.error/frame-no-generation f)
        "only :rf.error/frame-no-generation is softened")
    (is (rt/calls? 'describe-live-store-frame f)
        "a frame that seals no generation is described as resolving the live store")
    (is (rt/calls? 'throw f) "any other throw is re-raised")
    (is (rt/calls? 'rf/registrations live)
        "the live-store read routes through (rf/registrations {:frame …})")
    (is (not (rt/calls? 'catch live))
        "and catches nothing, so its query fails loud on a target naming no frame")
    (is (rt/mentions? :live-store? live) "the live-store result is marked :live-store?")
    (is (rt/mentions? :ambiguous live) "and names the ids the frame refuses as ambiguous")))

(deftest orient-rebased-on-frame-registry-view
  (let [orient (rt/defn-named 'orient)
        view   (rt/defn-named 'frame-registry-view)]
    (is (rt/calls? 'rf/registrations view)
        "frame-registry-view must resolve through (rf/registrations {:frame …})")
    (is (not (rt/calls? 'rf/frame-generation view))
        "not rf/frame-generation, which refuses a frame made without :images")
    (is (rt/calls? 'frame-registry-view orient)
        "orient must base its :registry on the operating frame's generation")
    (is (rt/calls? 'process-registry-view orient)
        "orient must keep the process-wide view as the fallback")))

(deftest validate-registered-names-an-ambiguous-id
  (let [f (rt/defn-named 'validate-registered)]
    (is (rt/calls? 'ambiguous-namespaces f)
        "an id the frame does not list is checked for ambiguity through rf/handler-meta {:frame …}")
    (is (rt/mentions? :ambiguous-id f)
        "and an ambiguous one is reported as :ambiguous-id, not :unknown-id")))

(let [{:keys [fail error]} (run-tests 'frame-registrar-test)]
  (System/exit (if (zero? (+ fail error)) 0 1)))
