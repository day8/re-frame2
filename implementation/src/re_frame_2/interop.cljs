(ns re-frame-2.interop
  "CLJS host primitives. Everything platform-specific lives here.

  See Spec 002 §Interop layer and Spec 005 §Interop layer.

  All other namespaces depend only on these abstractions, not on
  goog/reagent/js* directly. A non-CLJS port replaces this file; the rest
  of the runtime is host-agnostic in shape."
  (:require [goog.async.nextTick]
            [reagent.core :as r]
            [reagent.ratom :as ratom]))

;; ---- next-tick scheduling -------------------------------------------------

(def next-tick goog.async.nextTick)

;; ---- after-render hook ----------------------------------------------------

(def after-render r/after-render)

;; ---- mutable cells (used by the runtime, opaque to user code) -------------

(defn ratom [v]
  (r/atom v))

(defn ratom?
  "True if x is a Reagent reactive atom."
  [x]
  (satisfies? ratom/IReactiveAtom ^js x))

;; ---- reactions ------------------------------------------------------------

(defn make-reaction [f]
  (ratom/make-reaction f))

(defn add-on-dispose! [a-ratom f]
  (ratom/add-on-dispose! a-ratom f))

(defn dispose! [a-ratom]
  (ratom/dispose! a-ratom))

(defn reactive?
  "True when called from inside a reactive context (e.g. a reagent render)."
  []
  (ratom/reactive?))

;; ---- timers ---------------------------------------------------------------

(defn set-timeout!
  "Schedule f to run after ms milliseconds. Returns an opaque handle."
  [f ms]
  (js/setTimeout f ms))

(defn clear-timeout!
  "Cancel a previously-scheduled timer."
  [handle]
  (js/clearTimeout handle))

;; ---- clock ----------------------------------------------------------------

(defn now-ms []
  (if (and (exists? js/performance) (exists? js/performance.now))
    (js/performance.now)
    (js/Date.now)))

;; ---- queue primitives -----------------------------------------------------

(def empty-queue #queue [])

;; ---- platform marker ------------------------------------------------------

(def platform :client)

;; ---- compile-time constants -----------------------------------------------

(def ^boolean debug-enabled? "@define {boolean}" ^boolean goog/DEBUG)
