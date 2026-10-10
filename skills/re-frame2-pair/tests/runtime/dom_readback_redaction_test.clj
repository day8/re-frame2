;;;; tests/runtime/dom_readback_redaction_test.clj — every DERIVED output the
;;;; preload ships off-box (DOM text/attrs, a focus descriptor) is
;;;; PATH-projected through `re-frame.core/project-egress` as a
;;;; `:rf.observe/derived-tree` record, and fails closed on an ambiguous frame.
;;;; The projection semantics are core's to test; this pins the preload's
;;;; WIRING to that one boundary, so dropping a `maybe-redact-derived` call
;;;; turns it red.
;;;;
;;;; Run: bb tests/runtime/dom_readback_redaction_test.clj

(load-file (str (.getParent (java.io.File. *file*)) "/_support.clj"))

(ns dom-readback-redaction-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is run-tests]]
            [runtime-support :as rt]))

(deftest derived-redaction-helper-delegates-to-project-egress
  (let [f (rt/defn-named 'maybe-redact-derived)]
    (is (rt/mentions? 'rf/project-egress f)
        "maybe-redact-derived must delegate to re-frame.core/project-egress")
    (is (rt/mentions? :rf.observe/derived-tree f)
        "maybe-redact-derived must project a :rf.observe/derived-tree record")))

(deftest every-derived-output-arm-projects-and-fails-closed
  (let [dom-read (rt/defn-named 'dom-read)
        ui-read  (rt/defn-named 'ui-read)
        start    (rt/defn-named 'start-recording!)
        samp     (rt/defn-named 'sample-signals)]
    (is (rt/mentions? 'maybe-redact-derived dom-read) "dom-read must project its matched nodes")
    (is (rt/mentions? 'maybe-redact-derived ui-read) "ui-read must project its whole :content")
    (is (not (str/includes? (pr-str ui-read) "(rf/project-egress (:text base)"))
        "ui-read must not project only :text")
    (is (<= 2 (count (re-seq #"maybe-redact-derived" (pr-str (rt/defn-named 'sample-one-signal)))))
        "both the :dom and :focus sample arms must project their derived output")
    (doseq [[nm f] [["dom-read" dom-read] ["ui-read" ui-read]
                    ["start-recording!" start] ["sample-signals" samp]]]
      (is (rt/mentions? 'ambiguous-frame-error f)
          (str nm " must fail closed on an ambiguous frame under the off-box gate")))
    (doseq [[nm f] [["start-recording!" start] ["sample-signals" samp]]]
      (is (and (rt/mentions? :dom f) (rt/mentions? :focus f))
          (str nm "'s needs-frame? must cover :dom and :focus")))))

(let [{:keys [fail error]} (run-tests 'dom-readback-redaction-test)]
  (System/exit (if (zero? (+ fail error)) 0 1)))
