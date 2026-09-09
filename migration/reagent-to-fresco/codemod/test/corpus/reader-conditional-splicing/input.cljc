(ns app.reader-conditional-splicing
  "A Reagent require behind a SPLICING reader conditional, beside an
  ordinary one (rf2-m4hm, and the merged-PR-audit #7979 edge on it).

  `#?@(:cljs [[reagent.core :as r]])` splices a COLLECTION of specs where
  `#?(…)` carries one, so the branch value has to be lifted a level. Both
  shapes are legal and both were invisible.

  This file is also the MIXED case, which is the edge that made a
  whole-form fallback the wrong repair: `reagent.dom.client` arrives
  ordinarily and binds, so the alias set was never empty and the census
  read the file as fully resolved — while the spliced `r` stayed unbound
  and every `r/…` site in it vanished with NO diagnostic at all, neither
  `:unresolved-reagent-require` nor `:unresolved-alias`. One require
  resolving must not vouch for another."
  (:require [reagent.dom.client :as rdc]
            #?@(:cljs [[reagent.core :as r]])))

(defn w4-behind-the-splice []
  [:> Btn {:on-pick (r/partial handler @cart)}])

(defn w5-behind-the-splice []
  [(r/adapt-react-class Foo) {:variant :big}])

(defn the-ordinary-require-still-binds []
  ;; `rdc` bound before this fix and must still bind after it.
  (rdc/render root [:div]))
