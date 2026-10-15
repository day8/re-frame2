(ns day8.re-frame2-xray.panel-enum-guard-cljs-test
  "Keep the live mount facade and API spec aligned with the panel enum."
  (:require-macros [re-frame.api-manifest.cljs-publics :refer [emit-ns-publics]]
                   [day8.re-frame2-xray.panel-enum-spec-refs
                    :refer [emit-spec-mount-fn-names]])
  (:require [cljs.test :refer-macros [deftest is]]
            [day8.re-frame2-xray.panel-enum :as panel-enum]
            ;; The analyzer must see the facade before emit-ns-publics expands.
            [day8.re-frame2-xray.panels]))

(def ^:private enum-mount-names
  panel-enum/mount-fn-names)

(def ^:private facade-mount-names
  (into #{}
        (comp (map first)
              (filter #(re-matches #"mount-[a-z][a-z0-9-]*!" %)))
        (emit-ns-publics day8.re-frame2-xray.panels)))

;; The macro records spec dependencies so spec edits recompile this projection.
(def ^:private spec-mount-names
  (emit-spec-mount-fn-names))

(deftest facade-matches-enum
  (is (= enum-mount-names facade-mount-names)
      "The live mount facade and enum declare the same panels."))

(deftest spec-matches-enum
  (is (= enum-mount-names spec-mount-names)
      "The API spec and enum declare the same panels."))
