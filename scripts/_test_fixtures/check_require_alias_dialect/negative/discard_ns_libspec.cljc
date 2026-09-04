;; NEGATIVE FIXTURE — a `#_` DISCARDED LIBSPEC IS NOT A REQUIRE EDGE.
;;
;; The reader throws the next form away, so neither bare alias below binds
;; anything and neither may be reported.  Before the #9135 audit's repair the
;; mask called itself reader-level while not consuming `#_`, and this file
;; would have been reported RED on two aliases the reader never sees.
;;
;; A NEGATIVE FIXTURE ALONE CANNOT TELL "correctly ignored" FROM "the scanner
;; stopped seeing anything", so `run_self_tests` also strips the `#_` markers
;; from THIS FILE'S OWN TEXT and asserts the result fires on `machines` and
;; `schemas`.  The twin is the same bytes minus two characters, so it cannot
;; drift away from what it is a control for.
(ns re-frame.fixtures.discard-ns-libspec
  (:require #_[re-frame.machines :as machines]
            ;; A `#_` may be separated from the form it discards by a newline.
            ;; That is legal Clojure and is invisible to any line-oriented
            ;; search for `#_[re-frame`, which is why it is pinned here.
            #_
            [re-frame.schemas :as schemas]
            [re-frame.core :as rf]
            [re-frame.flows :as rf.flows]))

(defn boot []
  (rf/dispatch [:rf.flows/register (rf.flows/flow {:id :demo})]))
