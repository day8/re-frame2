;;;; tests/runtime/dom_readback_redaction_test.clj
;;;;
;;;; Structural (AST) pin that the DOM / readback derived-value egress
;;;; redaction in `preload/re_frame2_pair/runtime.cljs` stays WIRED to the
;;;; framework's single derived-tree projection boundary.
;;;;
;;;; THE CONTRACT (EP-0025 fail-open). Spec 015 / Tool-Pair: a DERIVED tree
;;;; (rendered DOM text / attrs / a focus descriptor) is PATH-projected against
;;;; the frame's classification before off-box egress via
;;;; `re-frame.core/project-egress` (the `:rf.observe/derived-tree` boundary).
;;;; The value-match (taint-by-equality) engine was REMOVED, so a secret
;;;; RE-KEYED into a non-app-db DOM position ships RAW (fail-open); only a value
;;;; still occupying a CLASSIFIED path within the tree redacts.
;;;;
;;;; WHY THIS IS AST-ONLY NOW (rf2-etsj8p). The redaction SEMANTICS are
;;;; framework-owned — they live in `re-frame.core/project-egress` and are
;;;; covered by core's own tests. The pair runtime's contribution is the
;;;; WIRING: `maybe-redact-derived` delegates to `project-egress`, and every
;;;; derived-output arm (`dom-read` / `ui-read` / the `:dom` / `:focus` sample
;;;; arms / the recorder + watch paths) routes through it AND fails closed on
;;;; an ambiguous frame under the off-box gate. This file pins that wiring so a
;;;; regression (someone drops the `maybe-redact-derived` call, or
;;;; re-introduces value-match) trips RED. The prior Babashka MIRROR of the
;;;; path-walk (which re-derived `project-egress`'s framework algorithm and was
;;;; a copied-implementation drift risk) was retired; framework redaction
;;;; behaviour is core's to test.
;;;;
;;;; Run: bb tests/runtime/dom_readback_redaction_test.clj
;;;; Exit: 0 = pass, non-zero = fail.

(load-file (str (.getParent (java.io.File. *file*)) "/_support.clj"))

(ns dom-readback-redaction-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is run-tests]]
            [clojure.walk :as walk]
            [runtime-support :as rt]))

;; Shared locate+parse+walk scaffold lives in tests/runtime/_support.clj.
;; Alias the vars the assertions below use.
(def ^:private defn-form rt/defn-named)

(defn- mentions? [form needle]
  (let [hit? (atom false)]
    (walk/postwalk (fn [x] (when (= x needle) (reset! hit? true)) x) form)
    @hit?))

(deftest runtime-defines-the-derived-redaction-helper
  (let [f (defn-form 'maybe-redact-derived)]
    (is (some? f) "runtime must define maybe-redact-derived")
    ;; EP-0025 B4 (rf2-ojp8pi): the SINGLE public boundary a derived tree
    ;; projects through is re-frame.core/project-egress — the
    ;; :rf.observe/derived-tree record kind. project-egress reads the frame's
    ;; live app-db itself (the derived-tree record's default :source-db), so
    ;; the helper no longer hand-reads app-db-value.
    (is (mentions? f 'rf/project-egress)
        "maybe-redact-derived must delegate to re-frame.core/project-egress")
    (is (mentions? f :rf.observe/derived-tree)
        "maybe-redact-derived must project a :rf.observe/derived-tree record")))

(deftest dom-read-routes-content-through-redaction
  (let [f (defn-form 'dom-read)]
    (is (some? f))
    (is (mentions? f 'maybe-redact-derived)
        "dom-read must PATH-project its matched nodes through maybe-redact-derived")
    (is (mentions? f 'ambiguous-frame-error)
        "dom-read must fail closed on an ambiguous frame under the off-box gate")))

(deftest ui-read-projects-whole-content-not-just-text
  (let [f (defn-form 'ui-read)
        s (pr-str f)]
    (is (some? f))
    (is (mentions? f 'maybe-redact-derived)
        "ui-read must PATH-project the whole :content (not just :text)")
    ;; A path-based elide over JUST the :text string is the wrong shape.
    ;; Assert it is absent — ui-read must not call elide-wire-value on :text.
    (is (not (str/includes? s "(rf/elide-wire-value (:text base)"))
        "ui-read must NOT path-elide only :text (the rf2-p9scds shape)")
    (is (mentions? f 'ambiguous-frame-error)
        "ui-read must fail closed on an ambiguous frame under the off-box gate")))

(deftest dom-and-focus-sample-arms-project
  (let [f (defn-form 'sample-one-signal)]
    (is (some? f))
    ;; Both DERIVED arms must route through the path-projector.
    (is (<= 2 (count (re-seq #"maybe-redact-derived" (pr-str f))))
        "both the :dom and :focus arms must PATH-project their derived output")))

(deftest recorder-and-watch-fail-closed-on-ambiguous-frame-for-derived-signals
  (let [start (defn-form 'start-recording!)
        samp  (defn-form 'sample-signals)]
    (is (mentions? start 'ambiguous-frame-error)
        "start-recording! must refuse an unresolvable frame")
    ;; needs-frame? must extend to :dom / :focus under the off-box gate.
    (is (and (mentions? start :dom) (mentions? start :focus))
        "start-recording! needs-frame? must cover :dom / :focus (off-box gate)")
    (is (mentions? samp 'ambiguous-frame-error)
        "sample-signals must fail closed on an ambiguous frame (watch-until path)")
    (is (and (mentions? samp :dom) (mentions? samp :focus))
        "sample-signals needs-frame? must cover :dom / :focus (off-box gate)")))

(let [{:keys [fail error]} (run-tests 'dom-readback-redaction-test)]
  (System/exit (if (pos? (+ fail error)) 1 0)))
