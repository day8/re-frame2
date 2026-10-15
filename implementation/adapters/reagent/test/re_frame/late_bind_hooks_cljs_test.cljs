(ns re-frame.late-bind-hooks-cljs-test
  "The documented Reagent hook set is installed and attributed to this adapter in the directory."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [re-frame.adapter.reagent]
            [re-frame.late-bind :as rf.late-bind]
            [re-frame.late-bind.directory :as rf.late-bind.directory]))

(def ^:private reagent-adapter-published-hooks
  #{:reagent/set-hiccup-emitter!
    :adapter/current-frame
    :adapter/current-component
    :adapter/as-element
    :adapter/ratom
    :adapter/ratom?
    :adapter/make-reaction
    :adapter/activate-derived-value!
    :adapter/add-on-dispose!
    :adapter/dispose!
    :adapter/reactive?
    :adapter/read-container-untracked
    :adapter/after-render
    :adapter/derived-container?})

(deftest reagent-adapter-publishes-its-documented-hooks
  (testing "every hook re-frame.adapter.reagent is documented to publish is
            present in the in-process late-bind hook table after the
            adapter ns is loaded"
    (let [installed-keys (set (keys @rf.late-bind/hooks))
          missing        (clojure.set/difference reagent-adapter-published-hooks
                                                  installed-keys)]
      (is (empty? missing)
          (str "Reagent adapter is missing hook registrations for: " missing
               " — every key in this test's expected-set MUST be wired by"
               " (late-bind/set-fn! ...) or (late-bind/chain-fn! ...) at the"
               " bottom of re-frame.adapter.reagent.cljs"
               " (`:reagent/set-hiccup-emitter!` is chained —"
               " see the directory entry's `:chained? true`)")))))

(deftest reagent-adapter-hooks-match-directory-listing
  (testing "every Reagent-adapter hook this test pins is also listed in
            re-frame.late-bind.directory with re-frame.adapter.reagent
            as a producer"
    (doseq [hook reagent-adapter-published-hooks]
      (let [entry      (rf.late-bind.directory/entry hook)
            producer   (:producer-ns entry)
            producers  (if (sequential? producer) (set producer) #{producer})]

        (is (contains? producers 're-frame.adapter.reagent)
            (str hook " is published by re-frame.adapter.reagent but the"
                 " directory entry's :producer-ns " (pr-str producer)
                 " does not list it — drift between source and directory"))))))
