(ns re-frame.cross-adapter-chained-emitter-cljs-test
  "Cross-adapter chained-install fan-out test for the
  `:reagent/set-hiccup-emitter!` late-bind hook (rf2-cl1qv).

  ## Why this test exists

  The hook `:reagent/set-hiccup-emitter!` is declared `:chained? true`
  in `re-frame.late-bind.directory` and lists FOUR producer namespaces:

      re-frame.adapter.reagent
      re-frame.adapter.reagent-slim
      re-frame.adapter.uix
      re-frame.adapter.helix

  The contract for chained hooks: every producer publishes via
  `late-bind/chain-fn!`, so a single consumer call (one
  `(require '[re-frame.ssr])`) fans out and runs every producer's
  install step. Any producer that mistakenly publishes via
  `late-bind/set-fn!` clobbers the chain — every producer that
  loaded before it is wiped from the chain, and a subsequent
  consumer call only fans out to the producers that loaded AFTER
  the offender (plus the offender itself).

  The pre-rf2-cl1qv bug was exactly that: `re-frame.adapter.reagent`
  used `set-fn!` for this hook. The current shadow-cljs ns-load
  ordering happens to load reagent before uix/helix — so uix's and
  helix's later `chain-fn!` rebuilt a chain on top of reagent and the
  hidden bug was invisible. A future ns-load reshuffle that loaded
  reagent LAST would have silently dropped uix's, helix's, and
  reagent-slim's emitter installs.

  ## What this test pins

  After loading every producer adapter ns:

    1. The hook resolves to a non-nil fn.
    2. Calling that fn with a sentinel emitter installs the sentinel
       into EVERY adapter's emitter cell — proving every producer's
       chain step ran. A regression that swaps any producer back to
       `set-fn!` causes at least one of the four assertions to fail.

  Verification is end-to-end: we don't peek at the chained-fn's
  internal step list. We invoke the hook and observe each adapter's
  `:render-to-string` slot routes through the sentinel. That's the
  observable contract `re-frame.ssr.emit` actually depends on.

  ## ns-load ordering

  Shadow-cljs `:node-test` loads test nses lexically. This file's ns
  name (`re-frame.cross-adapter-chained-emitter-cljs-test`) sits ahead
  of `re-frame.late-bind-hooks-cljs-test` and the per-adapter
  publication tests in collation order, so by the time this test
  runs every adapter has already published to the hook table — the
  state under test is the state every other test sees too.

  ns ends in `-cljs-test` so shadow-cljs `:node-test` picks it up."
  (:require [cljs.test :refer-macros [deftest is testing]]
            ;; Loading every adapter ns here forces all four to publish
            ;; their chain steps before this test's deftest runs. Every
            ;; require is load-bearing — do not trim.
            [re-frame.adapter.reagent       :as reagent-adapter]
            [re-frame.adapter.reagent-slim  :as reagent-slim-adapter]
            [re-frame.adapter.uix           :as uix-adapter]
            [re-frame.adapter.helix         :as helix-adapter]
            [re-frame.late-bind             :as late-bind]
            [re-frame.late-bind.directory   :as directory]))

(def ^:private hook-key :reagent/set-hiccup-emitter!)

(defn- adapter-render-to-string
  "Pull the `:render-to-string` slot from an adapter map and call it
  with a stable hiccup tree + empty opts. Returns the emitter's
  return value so the test can assert it equals the sentinel marker."
  [adapter-map]
  (let [r2s (:render-to-string adapter-map)]
    (r2s [:div "rf2-cl1qv"] {})))

(defn- sentinel-emitter
  "A hiccup-emitter that returns the unique marker passed in. Lets the
  test prove a SPECIFIC install reached an adapter's slot — we don't
  just check 'something is wired'; we check 'this exact something is
  wired'."
  [marker]
  (fn [_render-tree _opts] marker))

(deftest directory-entry-pins-the-chained-contract
  (testing "rf2-cl1qv: the directory entry for :reagent/set-hiccup-emitter!
            stays `:chained? true` and lists every React-shaped adapter as
            a producer. If a future change drops one or flips the flag,
            this test trips before the silent-clobber bug ships."
    (let [entry (some (fn [e] (when (= hook-key (:key e)) e))
                      directory/hooks)
          producers (set (let [p (:producer-ns entry)]
                           (if (sequential? p) p [p])))]
      (is (some? entry)
          "directory entry for :reagent/set-hiccup-emitter! must exist")
      (is (true? (:chained? entry))
          (str ":reagent/set-hiccup-emitter! must be `:chained? true` — "
               "it is published by four producers and a `set-fn!` "
               "regression in any of them silently clobbers the chain. "
               "Directory entry: " (pr-str entry)))
      (is (= producers
             '#{re-frame.adapter.reagent
                re-frame.adapter.reagent-slim
                re-frame.adapter.uix
                re-frame.adapter.helix})
          (str "every React-shaped adapter must be listed as a "
               ":reagent/set-hiccup-emitter! producer; got " (pr-str producers))))))

(deftest chained-install-fans-out-to-every-adapter
  (testing "rf2-cl1qv: invoking the chained `:reagent/set-hiccup-emitter!`
            hook installs the sentinel emitter into every adapter's
            render-to-string slot. Regressing any producer to `set-fn!`
            clobbers the chain and at least one adapter will not see
            the install."
    ;; Step 1: clear every adapter's emitter cell so we observe ONLY
    ;; the install driven through the chained hook below.
    (reagent-adapter/set-hiccup-emitter! nil)
    (reagent-slim-adapter/set-hiccup-emitter! nil)
    (uix-adapter/set-hiccup-emitter! nil)
    (helix-adapter/set-hiccup-emitter! nil)
    ;; Step 2: confirm the cleared state — render-to-string must throw
    ;; on every adapter. This sanity-check rules out a stale install
    ;; from a prior test masking a fan-out failure below.
    (doseq [[adapter-name adapter-map] [["reagent"      reagent-adapter/adapter]
                                        ["reagent-slim" reagent-slim-adapter/adapter]
                                        ["uix"          uix-adapter/adapter]
                                        ["helix"        helix-adapter/adapter]]]
      (is (thrown-with-msg?
            js/Error
            #":rf\.error/no-hiccup-emitter-bound"
            (adapter-render-to-string adapter-map))
          (str "after clearing, " adapter-name
               " adapter's render-to-string must throw "
               ":rf.error/no-hiccup-emitter-bound — if it doesn't, a "
               "stale emitter is masking the fan-out test")))
    ;; Step 3: resolve the chained hook and install the sentinel.
    ;; Every producer's chain step should run and write the sentinel
    ;; into its own adapter's cell.
    (let [chained-install! (late-bind/get-fn hook-key)
          marker           ::fan-out-marker
          sentinel         (sentinel-emitter marker)]
      (is (some? chained-install!)
          (str hook-key " is unbound — at least one adapter ns failed "
               "to publish at load. Without this, render-to-string is "
               "broken everywhere."))
      (chained-install! sentinel)
      ;; Step 4: each adapter's render-to-string must now route through
      ;; the sentinel. If any one returns ≠ marker (or throws), that
      ;; adapter's chain step did NOT run — the chain was clobbered.
      (doseq [[adapter-name adapter-map] [["reagent"      reagent-adapter/adapter]
                                          ["reagent-slim" reagent-slim-adapter/adapter]
                                          ["uix"          uix-adapter/adapter]
                                          ["helix"        helix-adapter/adapter]]]
        (is (= marker (adapter-render-to-string adapter-map))
            (str "fan-out failure: " adapter-name
                 " adapter's render-to-string did NOT route to the "
                 "sentinel installed via the chained hook — its "
                 "chain-fn! step was either never registered or was "
                 "clobbered by a sibling producer that used set-fn! "
                 "instead of chain-fn!. Verify all four adapter nses "
                 "use `(late-bind/chain-fn! :reagent/set-hiccup-emitter! ...)` "
                 "at the bottom of their src file."))))
    ;; Step 5: leave the test bundle's emitter slots in a sane state
    ;; for downstream tests — re-clear so anything that needed an
    ;; explicit emitter install does so itself, and the SSR ns-load
    ;; (if it loaded earlier in the bundle) is the only thing relied
    ;; upon to wire it for ssr-routed tests.
    (reagent-adapter/set-hiccup-emitter! nil)
    (reagent-slim-adapter/set-hiccup-emitter! nil)
    (uix-adapter/set-hiccup-emitter! nil)
    (helix-adapter/set-hiccup-emitter! nil)))
