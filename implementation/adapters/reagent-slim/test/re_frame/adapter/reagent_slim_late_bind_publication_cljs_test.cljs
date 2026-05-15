(ns re-frame.adapter.reagent-slim-late-bind-publication-cljs-test
  "Per rf2-swoks - pin the late-bind hook list the Reagent Slim adapter
  publishes at ns-load time. A future refactor that adds, removes, or
  renames a hook trips this test.

  The Reagent Slim adapter publishes via two mechanisms:

    (1) `substrate-adapter/route-hook!` - routed `:adapter/*` hooks
        that run THIS adapter's impl iff this adapter is the
        currently-installed one; otherwise chain to the previous
        handler. Used for Reagent-shaped adapter hooks:
        `:adapter/current-frame`, `:adapter/current-component`,
        `:adapter/ratom`, `:adapter/ratom?`,
        `:adapter/make-reaction`, `:adapter/add-on-dispose!`,
        `:adapter/dispose!`, `:adapter/reactive?`, and
        `:adapter/after-render`.

    (2) `late-bind/chain-fn!` - chained hooks where every contributor
        runs (independent of installed-adapter identity). Used for
        `:reagent/set-hiccup-emitter!` so one SSR ns-load installs the
        emitter into every loaded React-shaped adapter.

  This file pins the SET of hook keys published and cross-checks it
  against the authoritative late-bind directory.

  ns ends in `-cljs-test` so shadow-cljs `:node-test` picks it up."
  (:require [cljs.test :refer-macros [deftest is testing]]
            ;; Loading the Reagent Slim adapter triggers its ns-load
            ;; publication side effects - that's the point of this test.
            [re-frame.adapter.reagent-slim]
            [re-frame.late-bind :as late-bind]
            [re-frame.late-bind.directory :as directory]))

(def ^:private expected-hook-keys
  "Every late-bind hook the Reagent Slim adapter publishes at ns-load."
  #{:adapter/current-frame
    :adapter/current-component
    :adapter/ratom
    :adapter/ratom?
    :adapter/make-reaction
    :adapter/add-on-dispose!
    :adapter/dispose!
    :adapter/reactive?
    :adapter/after-render
    :reagent/set-hiccup-emitter!})

(defn- producers
  [entry]
  (let [p (:producer-ns entry)]
    (if (sequential? p) p [p])))

(defn- directory-entry
  [k]
  (some (fn [entry] (when (= k (:key entry)) entry))
        directory/hooks))

(defn- directory-hook-keys-for
  [producer-ns]
  (->> directory/hooks
       (filter #(some #{producer-ns} (producers %)))
       (map :key)
       set))

(deftest reagent-slim-adapter-publishes-expected-hook-set
  (testing "rf2-swoks: every hook key the Reagent Slim adapter publishes
            at ns-load is registered in the late-bind table after the
            adapter ns has loaded. A future refactor that drops or
            renames a hook trips this test."
    (doseq [k expected-hook-keys]
      (is (some? (late-bind/get-fn k))
          (str "expected the Reagent Slim adapter to publish " k
               " through the late-bind hook table at ns-load")))))

(deftest reagent-slim-adapter-hooks-cross-checked-against-directory
  (testing "rf2-swoks: the Reagent Slim adapter's expected hook set is
            exactly the set listed in the authoritative late-bind
            directory (`re-frame.late-bind.directory/hooks`)."
    (is (= expected-hook-keys
           (directory-hook-keys-for 're-frame.adapter.reagent-slim))
        "directory producer entries for re-frame.adapter.reagent-slim must match the pinned hook set")
    (doseq [k expected-hook-keys
            :let [entry (directory-entry k)]]
      (is (some? entry)
          (str "no directory entry for " k))
      (is (some #{'re-frame.adapter.reagent-slim} (producers entry))
          (str "directory entry for " k
               " does not list re-frame.adapter.reagent-slim as a producer; "
               "producers: " (pr-str (producers entry)))))))
