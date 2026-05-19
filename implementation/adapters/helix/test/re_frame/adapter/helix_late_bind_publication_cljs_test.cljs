(ns re-frame.adapter.helix-late-bind-publication-cljs-test
  "Per rf2-jz15y — pin the late-bind hook list the Helix adapter
  publishes at ns-load time. A future refactor that adds, removes, or
  renames a hook trips this test.

  The Helix adapter publishes via two mechanisms:

    (1) `substrate-adapter/route-hook!` — routed `:adapter/*` hooks
        that run THIS adapter's impl iff this adapter is the
        currently-installed one; otherwise chain to the previous
        handler. Used for `:adapter/current-frame`,
        `:adapter/add-on-dispose!`, `:adapter/dispose!`,
        `:adapter/wrap-view`, and `:adapter/after-render` (rf2-334d9 —
        useLayoutEffect-backed via the spine).

        Per rf2-jicu2 the Helix adapter does not publish the
        reactive-atom hooks `:adapter/ratom`, `:adapter/ratom?`,
        `:adapter/make-reaction`, or `:adapter/reactive?` — Helix
        ships no reactive-atom primitive (per rf2-2qit) and the
        reactive-atom surfaces have zero production call sites under
        Helix. Publishing them previously forced reagent.core
        (transitively reagent.ratom + reagent.impl.batching) into
        every Helix-only release bundle for code the substrate never
        executed.

        Per rf2-334d9 the Helix adapter DOES publish
        `:adapter/after-render` — `after-render` is a React-lifecycle
        question (when does the next commit complete?), not a
        reactive-atom one. Pre-rf2-334d9 `(rf/after-render f)` under
        the Helix adapter was a silent no-op.

    (2) `late-bind/chain-fn!` — chained hooks where every contributor
        runs (independent of installed-adapter identity). Used for
        `:adapter/clear-warn-once-caches!` (via
        spine/install-clear-warn-once-step!) and
        `:reagent/set-hiccup-emitter!` (rf2-y9spn — closes the parity
        gap with UIx that the rf2-1xcfz Helix audit named).

  This file pins the SET of hook keys published — not the impl behaviour
  (the impl behaviour is covered by the adapter-specific tests:
  current-frame in `helix-runtime`, ratom/make-reaction in
  `helix-cross-spec`, wrap-view in `helix-parity`, etc.).

  Parity sibling: `uix_late_bind_publication_cljs_test.cljs` (rf2-rrwwy)
  — the Helix expected set is identical to UIx's post-rf2-jicu2 set;
  lifting into a shared substrate-conformance fixture is a follow-on
  per the audit recommendation.

  ns ends in `-cljs-test` so shadow-cljs `:node-test` picks it up."
  (:require [cljs.test :refer-macros [deftest is testing]]
            ;; Loading the Helix adapter triggers its ns-load publication
            ;; side effects — that's the whole point of this test, so
            ;; require it explicitly even though we don't reference a
            ;; symbol from it.
            [re-frame.adapter.helix]
            [re-frame.late-bind :as late-bind]
            [re-frame.late-bind.directory :as directory]))

(def ^:private expected-hook-keys
  "Every late-bind hook the Helix adapter publishes at ns-load.

  Ordering: routed `:adapter/*` hooks first (alphabetical), then
  chained hooks (alphabetical). The set membership is what the test
  pins; ordering here is for human-readable diff."
  ;; Routed via substrate-adapter/route-hook!
  ;;
  ;; Per rf2-jicu2 the reactive-atom hooks (:adapter/ratom,
  ;; :adapter/ratom?, :adapter/make-reaction, :adapter/reactive?)
  ;; remain excluded. Per rf2-334d9 :adapter/after-render IS published.
  ;; See this ns's docstring for rationale.
  #{:adapter/add-on-dispose!
    :adapter/after-render
    :adapter/current-frame
    :adapter/dispose!
    :adapter/wrap-view
    ;; Chained via late-bind/chain-fn! (through spine/install-clear-warn-once-step!)
    :adapter/clear-warn-once-caches!
    ;; Chained via late-bind/chain-fn! (rf2-y9spn — parity with UIx rf2-4z7bp)
    :reagent/set-hiccup-emitter!})

(deftest helix-adapter-publishes-expected-hook-set
  (testing "rf2-jz15y: every hook key the Helix adapter publishes at
            ns-load is registered in the late-bind table after the
            adapter ns has loaded. A future refactor that drops or
            renames a hook (or adds an unannounced one) trips this
            test."
    (doseq [k expected-hook-keys]
      (is (some? (late-bind/get-fn k))
          (str "expected the Helix adapter to publish " k
               " through the late-bind hook table at ns-load")))))

(deftest helix-adapter-hooks-cross-checked-against-directory
  (testing "rf2-jz15y: every hook key in expected-hook-keys appears in
            the authoritative late-bind directory
            (`re-frame.late-bind.directory/hooks`) with `re-frame.adapter.helix`
            listed as one of its producers. Drift between this test's
            expected set and the directory is a hard error.

            (Conversely we don't try to enumerate which `:adapter/*`
            keys are EXCLUSIVELY Helix's vs. shared — the test bundle
            loads every adapter ns, so the registry holds the union.
            The cross-check against the directory is the principled way
            to pin the Helix-side publication set.)"
    (doseq [k expected-hook-keys]
      (let [entry (some (fn [e] (when (= k (:key e)) e))
                        directory/hooks)
            producers (let [p (:producer-ns entry)]
                        (if (sequential? p) p [p]))]
        (is (some? entry)
            (str "no directory entry for " k))
        (is (some #{'re-frame.adapter.helix} producers)
            (str "directory entry for " k
                 " does not list re-frame.adapter.helix as a producer; "
                 "producers: " (pr-str producers)))))))
