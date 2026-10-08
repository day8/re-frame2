(ns re-frame.cross-adapter-chained-emitter-cljs-test
  "`:reagent/set-hiccup-emitter!` is a `:chained? true` late-bind hook: each
  React-shaped adapter (reagent, reagent-slim, uix) publishes an install
  step with `late-bind/chain-fn!`, so one consumer call fans out to all of
  them. A producer that used `set-fn!` would wipe every step loaded before
  it, and ns-load order can hide that until a reshuffle loads the offender
  last. So the fan-out is observed end-to-end: invoke the hook with a
  sentinel emitter and read each adapter's `:render-to-string`, the
  contract `re-frame.ssr.emit` depends on. The headless test-react adapter
  chains onto the hook too, so the React adapters are a required SUBSET of
  the producers rather than the exact set."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [clojure.set :as set]
            ;; Loading every adapter ns here forces all three to publish
            ;; their chain steps before this test's deftest runs. Every
            ;; require is load-bearing — do not trim.
            [re-frame.adapter.reagent       :as rf.adapter.reagent]
            [re-frame.adapter.reagent-slim  :as rf.adapter.reagent-slim]
            [re-frame.adapter.uix           :as rf.adapter.uix]
            [re-frame.late-bind             :as rf.late-bind]
            [re-frame.late-bind.directory   :as rf.late-bind.directory]))

(def ^:private hook-key :reagent/set-hiccup-emitter!)

(defn- adapter-render-to-string
  "Call `adapter-map`'s `:render-to-string` on a fixed tree, returning what
  its emitter returned."
  [adapter-map]
  (let [r2s (:render-to-string adapter-map)]
    (r2s [:div "probe"] {})))

(defn- sentinel-emitter
  "An emitter returning `marker`, so a read proves THIS install reached the slot."
  [marker]
  (fn [_render-tree _opts] marker))

(def ^:private adapter-maps
  [["reagent"      rf.adapter.reagent/adapter]
   ["reagent-slim" rf.adapter.reagent-slim/adapter]
   ["uix"          rf.adapter.uix/adapter]])

(deftest directory-entry-pins-the-chained-contract
  (testing "the directory entry for :reagent/set-hiccup-emitter!
            stays `:chained? true` and lists every React-shaped adapter as
            a producer"
    (let [entry (some (fn [e] (when (= hook-key (:key e)) e))
                      rf.late-bind.directory/hooks)
          producers (set (let [p (:producer-ns entry)]
                           (if (sequential? p) p [p])))
          react-adapters '#{re-frame.adapter.reagent
                            re-frame.adapter.reagent-slim
                            re-frame.adapter.uix}]
      (is (= [true true] [(:chained? entry) (set/subset? react-adapters producers)])
          (str ":reagent/set-hiccup-emitter! must be `:chained? true`, since a `set-fn!` "
               "in any of its producers clobbers the chain, and must list every "
               "React-shaped adapter as a producer. Directory entry: " (pr-str entry))))))

(deftest chained-install-fans-out-to-every-adapter
  (testing "invoking the chained `:reagent/set-hiccup-emitter!`
            hook installs the sentinel emitter into every adapter's
            render-to-string slot. Regressing any producer to `set-fn!`
            clobbers the chain and at least one adapter will not see
            the install."
    ;; Clear every cell, and confirm it, so a stale install cannot mask a
    ;; fan-out failure.
    (rf.adapter.reagent/set-hiccup-emitter! nil)
    (rf.adapter.reagent-slim/set-hiccup-emitter! nil)
    (rf.adapter.uix/set-hiccup-emitter! nil)
    (doseq [[adapter-name adapter-map] adapter-maps]
      (is (thrown-with-msg?
            js/Error
            #":rf\.error/no-hiccup-emitter-bound"
            (adapter-render-to-string adapter-map))
          (str "after clearing, " adapter-name
               " adapter's render-to-string must throw "
               ":rf.error/no-hiccup-emitter-bound — if it doesn't, a "
               "stale emitter is masking the fan-out test")))
    ;; An unbound hook throws here.
    (let [marker ::fan-out-marker]
      ((rf.late-bind/get-fn hook-key) (sentinel-emitter marker))
      (is (= {"reagent" marker "reagent-slim" marker "uix" marker}
             (into {} (map (fn [[adapter-name adapter-map]]
                             [adapter-name (adapter-render-to-string adapter-map)]))
                   adapter-maps))
          (str "every adapter's render-to-string routes to the sentinel installed via "
               "the chained hook; one that does not had its chain-fn! step never "
               "registered, or clobbered by a sibling producer's set-fn!")))
    ;; Re-clear, so later tests install their own emitter.
    (rf.adapter.reagent/set-hiccup-emitter! nil)
    (rf.adapter.reagent-slim/set-hiccup-emitter! nil)
    (rf.adapter.uix/set-hiccup-emitter! nil)))
