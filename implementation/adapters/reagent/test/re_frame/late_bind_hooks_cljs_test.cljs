(ns re-frame.late-bind-hooks-cljs-test
  "Pin the closed set of late-bind hooks the Reagent adapter publishes
  at ns-load time (rf2-z3q3s).

  Spec 006 §The reactive-substrate adapter contract treats the adapter
  surface as closed-set; the late-bind hook table is the documented
  side-channel through which `re-frame.interop` (and the views ns)
  reach this adapter's substrate-specific impls. The repo-wide drift
  test
  `implementation/core/test/re_frame/late_bind_drift_test.clj` already
  asserts the directory and the `set-fn!` call sites stay in sync, but
  that walks every artefact's source files — it does NOT pin which keys
  the Reagent adapter alone is responsible for. A future refactor that
  silently drops a hook from this adapter (or adds one without
  documenting it) goes unobserved by the directory drift test if the
  hook is also published by a sibling adapter (every `:adapter/*` hook
  on the directory is `:chained? true` and lists every adapter as a
  producer).

  This test plants the line in the sand at the runtime tier: after
  loading `re-frame.adapter.reagent`, the in-process hook table MUST
  contain bindings for every hook this adapter is documented to
  publish. The set is locked here verbatim; adding or removing a hook
  forces a deliberate update to this expected-set, which forces a
  review of the directory entry and the contract Spec 006 documents.

  Mechanism: read `@re-frame.late-bind/hooks` (the runtime atom holding
  hook → fn entries) and check the expected key-set is a subset of the
  installed keys. Subset rather than equality because other adapter
  ns'es are loaded in the same test bundle (the late-bind set-fn!s for
  uix / helix / reagent-slim hooks also fire at ns-load time per the
  drift-test rules), so the installed keys are a superset of any one
  adapter's contribution.

  ns ends in -cljs-test so shadow-cljs's :node-test build picks it up."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [re-frame.adapter.reagent]
            [re-frame.late-bind :as late-bind]
            [re-frame.late-bind.directory :as directory]))

;; The closed set of hooks `re-frame.adapter.reagent` is documented (in
;; the late-bind directory + the adapter ns) to publish at ns-load
;; time. Locked verbatim; any change here MUST be paired with a
;; deliberate directory + spec update.
(def ^:private reagent-adapter-published-hooks
  #{:reagent/set-hiccup-emitter!
    :adapter/current-frame
    :adapter/current-component
    :adapter/ratom
    :adapter/ratom?
    :adapter/make-reaction
    :adapter/add-on-dispose!
    :adapter/dispose!
    :adapter/reactive?
    :adapter/after-render})

(deftest reagent-adapter-publishes-its-documented-hooks
  (testing "every hook re-frame.adapter.reagent is documented to publish is
            present in the in-process late-bind hook table after the
            adapter ns is loaded (rf2-z3q3s)"
    (let [installed-keys (set (keys @late-bind/hooks))
          missing        (clojure.set/difference reagent-adapter-published-hooks
                                                  installed-keys)]
      (is (empty? missing)
          (str "Reagent adapter is missing hook registrations for: " missing
               " — every key in this test's expected-set MUST be wired by"
               " (late-bind/set-fn! ...) or (late-bind/chain-fn! ...) at the"
               " bottom of re-frame.adapter.reagent.cljs"
               " (`:reagent/set-hiccup-emitter!` is chained per rf2-cl1qv —"
               " see the directory entry's `:chained? true`)")))))

(deftest reagent-adapter-hooks-match-directory-listing
  (testing "every Reagent-adapter hook this test pins is also listed in
            re-frame.late-bind.directory with re-frame.adapter.reagent
            as a producer (rf2-z3q3s)"
    (doseq [hook reagent-adapter-published-hooks]
      (let [entry      (directory/entry hook)
            producer   (:producer-ns entry)
            producers  (if (sequential? producer) (set producer) #{producer})]
        (is (some? entry)
            (str hook " has no entry in re-frame.late-bind.directory/hooks —"
                 " every published key must be documented"))
        (is (contains? producers 're-frame.adapter.reagent)
            (str hook " is published by re-frame.adapter.reagent but the"
                 " directory entry's :producer-ns " (pr-str producer)
                 " does not list it — drift between source and directory"))))))
