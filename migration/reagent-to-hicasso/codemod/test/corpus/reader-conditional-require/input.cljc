(ns app.reader-conditional
  "A Reagent require behind a NON-SPLICING reader conditional (rf2-m4hm).

  `#?(:cljs [reagent.core :as r])` is the ONLY legal way to require
  Reagent from a `.cljc` file, and `ns-context` used to read the `ns` form
  through one `sexpr` — which throws on a reader-conditional node. So the
  whole form answered `::unreadable`, every alias set came back `#{}`, and
  W4, W5, `:as-element-island` and `:reagent-api-residue` were all dead
  here. Nothing looked wrong: a `:>` head needs no alias, so the report
  stayed non-empty.

  Every site below is a site this file could not see before."
  (:require [re-frame.core :as rf]
            #?(:cljs [reagent.core :as r])))

(defn w4-partial-capture []
  ;; W4 — the non-fn `IFn` literal. Dead in this file before rf2-m4hm.
  [:> Btn {:on-pick (r/partial handler @cart)}])

(defn w5-adapt-react-class-head []
  ;; W5 — the inline `adapt-react-class` head. Also dead before rf2-m4hm.
  [(r/adapt-react-class Foo) {:variant :big} "kid"])

(defn as-element-island []
  [:> Grid {:on-render-cell (fn [row] (r/as-element [:span (:name row)]))}])

(defn reagent-api-residue []
  [:> C {:value @(r/atom 1)}])

(defn clojure-cores-partial-is-still-not-reagents []
  ;; The alias resolving must not make `clojure.core/partial` Reagent's.
  [:> Btn {:on-pick (partial handler 1)}])
