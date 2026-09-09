;; NEGATIVE fixture (rule g) — the CURRENT spelling, everywhere.
;;
;; Nothing here may fire. The rule carries no token boundary and no shape
;; scoping, so this fixture is what says the needle cannot over-fire on the
;; name that replaced it, on the alias that deliberately did NOT change, or on
;; the Picasso derivation the superseded rationale turned on.
;;
;; The self-test also scans these same bytes attributed to a path under a
;; retired directory name, where it must report exactly one finding — the PATH
;; carrier, which no line of content can express.
(ns re-frame.example.view
  (:require [re-frame.fresco :as h]))

(def config {:rf/adapter :rf.adapter/fresco})

(h/defview panel []
  [:div "Hiccup and Picasso are one letter apart; fresco is the name now."])
