;;;; FIXTURE (check_egress_walker_residue) — reader-equivalent FORMATTINGS of a
;;;; call the gate already refuses tight. Every one of these MUST be refused.
;;;; Five findings expected. Not compiled; not on any classpath.
;;;;
;;;; `residue_calls.cljs` is the tight-call control these discriminate against:
;;;; it spells every shape with the callee hard against the paren, which is the
;;;; blind spot merged-PR audit #9491 found (rf2-kuky.90). The gate refused only
;;;; the tight spelling, and its own fixtures only ever wrote the tight
;;;; spelling, so gate and fixtures agreed while both were wrong. A Clojure
;;;; reader proof on the audit shows the variants below read as IDENTICAL call
;;;; forms — the whitespace is formatting, not meaning.

(ns fixture.residue-calls-formatted
  (:require [re-frame.elision :as rf.elision]))

;; 1. a SPACE between the paren and the callee
(defn spaced [v frame-id]
  ( rf.elision/elide-wire-value v {:frame frame-id}))

;; 2. a NEWLINE between the paren and the callee — invisible to any
;;    line-at-a-time search, whatever the pattern
(defn broken-line [v frame-id]
  (
    rf.elision/elide-wire-value v {:frame frame-id}))

;; 3. a `;` COMMENT plus a newline between the paren and the callee
(defn behind-comment [v frame-id]
  ( ;; project the slot before it leaves the box
    rf.elision/elide-wire-value v {:frame frame-id}))

;; 4. behind a `#` reader macro, head broken across lines
(defn walk-all [vs frame-id]
  (mapv #(
           elide-wire-value % {:frame frame-id})
        vs))

;; 5. a RENDERED EVAL FORM string carrying the newline spelling. This is where
;;    the gate's standing promise about string literals lands: it is a real
;;    call at the far end, in the inspected app.
(def ^:private snapshot-script
  "(
     rf.elision/elide-wire-value (rf/app-db-value :rf/default)
     {:frame :rf/default})")
