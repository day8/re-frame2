;; POSITIVE FIXTURE — A DISCARD CONSUMES EXACTLY THE NEXT FORM, NEVER THE ONE
;; AFTER IT.
;;
;; This is the OVER-CONSUMPTION guard, and it is the direction that fails
;; silently.  Under-consuming a discard is a false RED: loud, blocking,
;; impossible to miss.  Over-consuming blanks live code and reports a confident
;; clean run over it — the gate goes green by going blind.
;;
;; It is not hypothetical.  Every code-position `#_` in the tree today is a
;; clj-kondo suppression — `#_:clj-kondo/ignore` or `#_{:clj-kondo/ignore
;; [...]}` — sitting IMMEDIATELY BEFORE live code, which is precisely the shape
;; a greedy discard pass eats two forms of.  Both requires below are LIVE and
;; must be NAMED.
(ns re-frame.fixtures.discard-consumes-exactly-one-form
  (:require #_:clj-kondo/ignore
            [re-frame.machines :as machines]
            [re-frame.core :as rf]))

#_{:clj-kondo/ignore [:unresolved-namespace]}
(require '[re-frame.routing :as routing])

(defn boot []
  (rf/dispatch [(machines/start) (routing/navigate)]))
