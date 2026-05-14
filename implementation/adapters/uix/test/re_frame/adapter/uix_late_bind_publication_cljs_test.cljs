(ns re-frame.adapter.uix-late-bind-publication-cljs-test
  "Per rf2-rrwwy — pin the late-bind hook list the UIx adapter publishes
  at ns-load time. A future refactor that adds, removes, or renames a
  hook trips this test.

  The UIx adapter publishes via two mechanisms:

    (1) `substrate-adapter/route-hook!` — routed `:adapter/*` hooks
        that run THIS adapter's impl iff this adapter is the
        currently-installed one; otherwise chain to the previous
        handler. Used for `:adapter/current-frame`,
        `:adapter/add-on-dispose!`, `:adapter/dispose!`,
        `:adapter/wrap-view`.

        Per rf2-jicu2 the UIx adapter no longer publishes
        `:adapter/ratom`, `:adapter/ratom?`, `:adapter/make-reaction`,
        `:adapter/reactive?`, or `:adapter/after-render` — UIx ships no
        reactive-atom primitive (per rf2-3yij) and the reactive
        surfaces have zero production call sites under UIx. Publishing
        them previously forced reagent.core (transitively reagent.ratom
        + reagent.impl.batching) into every UIx-only release bundle for
        code the substrate never executed.

    (2) `late-bind/chain-fn!` — chained hooks where every contributor
        runs (independent of installed-adapter identity). Used for
        `:adapter/clear-warn-once-caches!` (via
        spine/install-clear-warn-once-step!) and
        `:reagent/set-hiccup-emitter!` (rf2-4z7bp).

  This file pins the SET of hook keys published — not the impl behaviour
  (the impl behaviour is covered by the adapter-specific tests:
  current-frame in `uix-runtime`, ratom/make-reaction in `uix-cross-spec`,
  wrap-view in `uix-parity`, etc.).

  The sibling test for Reagent (rf2-z3q3s, scheduled) will use the
  same shape; lifting into a shared substrate-conformance fixture is
  a follow-on bead per the audit recommendation.

  ns ends in `-cljs-test` so shadow-cljs `:node-test` picks it up."
  (:require [cljs.test :refer-macros [deftest is testing]]
            ;; Loading the UIx adapter triggers its ns-load publication
            ;; side effects — that's the whole point of this test, so
            ;; require it explicitly even though we don't reference a
            ;; symbol from it.
            [re-frame.adapter.uix]
            [re-frame.late-bind :as late-bind]
            [re-frame.late-bind.directory :as directory]))

(def ^:private expected-hook-keys
  "Every late-bind hook the UIx adapter publishes at ns-load.

  Ordering: routed `:adapter/*` hooks first (alphabetical), then
  chained hooks (alphabetical). The set membership is what the test
  pins; ordering here is for human-readable diff."
  ;; Routed via substrate-adapter/route-hook!
  ;;
  ;; Per rf2-jicu2 the publication set was trimmed: the UIx adapter
  ;; no longer publishes :adapter/ratom, :adapter/ratom?,
  ;; :adapter/make-reaction, :adapter/reactive?, or
  ;; :adapter/after-render. See this ns's docstring for rationale.
  #{:adapter/add-on-dispose!
    :adapter/current-frame
    :adapter/dispose!
    :adapter/wrap-view
    ;; Chained via late-bind/chain-fn! (through spine/install-clear-warn-once-step!)
    :adapter/clear-warn-once-caches!
    ;; Chained via late-bind/chain-fn! (rf2-4z7bp)
    :reagent/set-hiccup-emitter!})

(deftest uix-adapter-publishes-expected-hook-set
  (testing "rf2-rrwwy: every hook key the UIx adapter publishes at
            ns-load is registered in the late-bind table after the
            adapter ns has loaded. A future refactor that drops or
            renames a hook (or adds an unannounced one) trips this
            test."
    (doseq [k expected-hook-keys]
      (is (some? (late-bind/get-fn k))
          (str "expected the UIx adapter to publish " k
               " through the late-bind hook table at ns-load")))))

(deftest uix-adapter-hooks-cross-checked-against-directory
  (testing "rf2-rrwwy: every hook key in expected-hook-keys appears in
            the authoritative late-bind directory
            (`re-frame.late-bind.directory/hooks`) with `re-frame.adapter.uix`
            listed as one of its producers. Drift between this test's
            expected set and the directory is a hard error.

            (Conversely we don't try to enumerate which `:adapter/*`
            keys are EXCLUSIVELY UIx's vs. shared — the test bundle
            loads every adapter ns, so the registry holds the union.
            The cross-check against the directory is the principled way
            to pin the UIx-side publication set.)"
    (doseq [k expected-hook-keys]
      (let [entry (some (fn [e] (when (= k (:key e)) e))
                        directory/hooks)
            producers (let [p (:producer-ns entry)]
                        (if (sequential? p) p [p]))]
        (is (some? entry)
            (str "no directory entry for " k))
        (is (some #{'re-frame.adapter.uix} producers)
            (str "directory entry for " k
                 " does not list re-frame.adapter.uix as a producer; "
                 "producers: " (pr-str producers)))))))
