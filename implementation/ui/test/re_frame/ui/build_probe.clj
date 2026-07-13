(ns re-frame.ui.build-probe
  "Macro-expansion probe for the real shadow compiler-env build identity."
  (:require [re-frame.ui.compiler.build :as build]))

(defmacro ambient-shadow-build-id []
  (build/current-build-id))
