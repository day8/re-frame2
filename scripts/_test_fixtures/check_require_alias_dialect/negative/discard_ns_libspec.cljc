;; NEGATIVE FIXTURE — a `#_` DISCARDED LIBSPEC IS NOT A REQUIRE EDGE.
;;
;; The reader throws the next form away, so neither bare alias below binds
;; anything and neither may be reported.  A mask that calls itself
;; reader-level while not consuming `#_` would report this file RED on
;; two aliases the reader never sees.
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
