(ns app.w4
  "W4 — the non-fn `IFn` literal (design §4.4), as corrected by
  AMENDMENT (A).

  Reagent's `convert-prop-value` ends with an `ifn?` arm returning
  `(fn [& args] (apply x args))`, and `r/partial` builds a `PartialFn`
  deftype that reaches it. Hicasso has no such arm, so the object crosses
  opaque and a working handler stops firing with nothing thrown.

  The design's stated rewrite re-evaluates the callee and every argument
  on EACH invocation. `make-partial-fn` evaluated them ONCE, at
  construction. The first two sites below are the witnesses."
  (:require [reagent.core :as r]))

(defn a-dereferenced-snapshot []
  ;; @cart must be read ONCE, when the prop is built — not on every click,
  ;; which would silently turn a snapshot into a live read.
  [:> Btn {:on-pick (let [f__rf2 handler a0__rf2 @cart] (fn [& args__rf2] (apply f__rf2 a0__rf2 args__rf2)))}])

(defn effect-count-and-ordering []
  ;; Each of these ran exactly once, left to right, when the prop was
  ;; built. The wrapper must not re-run them per invocation.
  [:> Btn {:on-pick (let [f__rf2 (make-handler!) a0__rf2 (next-id!) a1__rf2 (log! "x")] (fn [& args__rf2] (apply f__rf2 a0__rf2 a1__rf2 args__rf2)))}])

(defn literals-are-inlined-not-bound []
  [:> Btn {:on-pick (let [f__rf2 handler] (fn [& args__rf2] (apply f__rf2 :id 7 "s" args__rf2)))}])

(defn a-symbol-callee-with-no-arguments []
  [:> Btn {:on-pick (let [f__rf2 handler] (fn [& args__rf2] (apply f__rf2 args__rf2)))}])

(defn a-plain-function-is-not-wrapped []
  ;; `js-val?` catches a function first, so the donor never wrapped one.
  [:> Btn {:on-pick handler}])

(defn clojure-cores-partial-is-not-reagents []
  ;; `(partial f a)` IS a function, so the donor did not wrap it either.
  [:> Btn {:on-pick (partial handler 1)}])
