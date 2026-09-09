(ns app.reader-conditional-not-reagent
  "THE ADVERSARIAL CASE for rf2-m4hm: a `.cljc` whose reader conditionals
  require things that are NOT Reagent.

  Seeing through a reader conditional is only correct if it also declines
  to bind. The failure mode this pins is a fix that reads every branch as
  Reagent's because a branch is where Reagent usually lives — which would
  wrap a `clojure.core/partial`, respell a head that is not
  `adapt-react-class`, and rewrite a file with no Reagent in it at all.

  A namespace that merely SPELLS a Reagent name is the same class and is
  here too: re-frame-10x's vendored copy is not `reagent.core`, and
  guessing that it is would be worse than not seeing it. Only the exact
  roster binds, so this file's `r` is `10x`'s and must stay unbound.

  Nothing below may be rewritten."
  (:require [clojure.string :as str]
            #?(:cljs [goog.dom :as gdom])
            #?@(:cljs [[day8.re-frame-10x.inlined-deps.reagent.v1v2v0.reagent.core :as r]])))

(defn a-partial-that-is-not-reagents []
  ;; `r` here is the VENDORED reagent, not `reagent.core`. W4 must not fire.
  [:> Btn {:on-pick (r/partial handler @cart)}])

(defn a-head-that-is-not-adapt-react-class []
  [(gdom/adapt-react-class Foo) {:variant :big}])

(defn no-reagent-anywhere []
  [:> Btn {:label (str/upper-case "go")}])
