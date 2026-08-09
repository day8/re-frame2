(ns app.w5
  "W5 — the inline `adapt-react-class` head (design §4.5). `vec-to-elem`
  sends a `NativeWrapper` head and a `[:> C …]` head to the SAME
  `native-element`, with the props slot at a different index — two
  spellings of one path."
  (:require [reagent.core :as r]))

(defn the-inline-head []
  [:> Foo {:variant "big"} "kid"])

(defn the-inline-head-with-no-props []
  [:> Foo "kid"])

;; A def site cannot be rewritten by a fixer: rewriting the def changes
;; every call site's conversion regime at once, including call sites in
;; namespaces this tool was never pointed at.
(def Adapted (r/adapt-react-class Bar))

(defn a-call-site-of-the-def []
  [Adapted {:variant :big}])
