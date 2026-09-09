(ns app.shared
  "§5.1 `:cljc-site` — a `[:>]` inside a `.cljc` file. The crossing is
  ClojureScript-only at that node: the JVM side of this file has no React
  to cross into."
  (:require [reagent.core :as r]))

(defn a-crossing-in-shared-source []
  [:> Btn {:variant :contained}])
