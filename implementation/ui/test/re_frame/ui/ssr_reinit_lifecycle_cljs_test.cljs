(ns re-frame.ui.ssr-reinit-lifecycle-cljs-test
  "rf2-vxgfnd.204 — the public CLJS `re-frame.ui/adapter` SSR lifecycle is
  REPEATABLE across destroy → re-init.

  The React-shaped adapter's `:render-to-string` slot reads a per-generation
  `emitter-cell` that the spine's `dispose-active-roots-and-caches!` CLEARS on
  `dispose-adapter!`. The SSR hiccup emitter used to be published into that cell
  ONLY as a one-time `re-frame.ssr` ns-load side effect (the chained
  `:reagent/set-hiccup-emitter!` hook), so a public destroy → re-init cycle left
  `render-to-string` unarmed and the next render threw
  `:rf.error/no-hiccup-emitter-bound`.

  The regression loads the REAL `re-frame.ssr` (no private/test-only setter is
  called to establish the post-reinit condition) and drives the closed public
  lifecycle: init → render → destroy → re-init → render. It reaches the emitter-
  cell through `re-frame.substrate.adapter/render-to-string`, which delegates to
  the INSTALLED adapter's `:render-to-string` slot (Spec 006). Before the fix the
  second render throws `:rf.error/no-hiccup-emitter-bound`; after it — the SSR
  emitter is retained durably in `:ssr/current-hiccup-emitter` and replayed at
  every `install-adapter!` — every generation renders.

  Node-only (`re-frame.ui/adapter` + `re-frame.ssr` on the all-artefact
  `:node-test`/`:node-test-ui` classpath); the JVM/plain-atom retention twin is
  `re-frame.ssr-emitter-reinit-lifecycle-jvm-test`."
  (:require [cljs.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.late-bind :as late-bind]
            [re-frame.ssr]
            [re-frame.substrate.adapter :as substrate-adapter]
            [re-frame.ui :as ui]))

(use-fixtures :each
  (fn [f]
    (substrate-adapter/reset-lifecycle-state-for-tests!)
    (try
      (f)
      (finally
        (rf/destroy-adapter!)
        (substrate-adapter/reset-lifecycle-state-for-tests!)))))

(defn- render! []
  ;; Reaches the installed adapter's `:render-to-string` slot — the emitter-cell
  ;; path — via the public delegation fn, NOT the private `set-hiccup-emitter!`.
  (substrate-adapter/render-to-string [:div "ok"] {}))

(deftest ssr-loaded-so-the-emitter-is-published-durably
  (testing "the precondition: requiring re-frame.ssr retains the emitter durably"
    (is (fn? (late-bind/get-fn :ssr/current-hiccup-emitter))
        "re-frame.ssr ns-load published :ssr/current-hiccup-emitter")))

(deftest public-adapter-lifecycle-is-repeatable-across-destroy-and-reinit
  (is (nil? (rf/init! ui/adapter)))
  (testing "first install renders through the SSR emitter"
    (is (re-find #"<div>ok</div>" (render!))
        "render-to-string is armed on the first install"))

  (is (nil? (rf/destroy-adapter!)))
  (is (true? (substrate-adapter/adapter-disposed?))
      "disposal cleared the adapter-owned emitter slot")

  (is (nil? (rf/init! ui/adapter)) "a fresh init is legal after disposal")
  (testing "the RE-INSTALLED generation renders — the emitter was replayed"
    ;; Before the fix this threw :rf.error/no-hiccup-emitter-bound because the
    ;; cleared emitter-cell had no replay source; after the fix install replays
    ;; the retained :ssr/current-hiccup-emitter.
    (is (re-find #"<div>ok</div>" (render!))
        "re-init re-armed render-to-string via install-time emitter replay")))

(deftest repeated-destroy-reinit-cycles-stay-armed-without-accumulation
  ;; Many cycles must all render, and must not accumulate callbacks / retain
  ;; disposed generations / duplicate publication: the emitter is published ONCE
  ;; (at ssr-load) and only READ at install, so the durable slot never grows.
  (let [emitter-before (late-bind/get-fn :ssr/current-hiccup-emitter)]
    (dotimes [_ 5]
      (is (nil? (rf/init! ui/adapter)))
      (is (re-find #"<div>ok</div>" (render!)))
      (is (nil? (rf/destroy-adapter!))))
    (is (identical? emitter-before (late-bind/get-fn :ssr/current-hiccup-emitter))
        "the durable emitter slot holds the SAME single publication after every cycle — no duplication")))

(deftest a-stale-generation-cannot-strand-a-replacement-generations-emitter
  ;; Install gen1, render, dispose (clears gen1's slot). Install gen2. gen2 must
  ;; render: the replay applies the current emitter, and gen1's terminal dispose
  ;; cannot clear gen2's freshly-armed slot.
  (is (nil? (rf/init! ui/adapter)))
  (is (re-find #"<div>ok</div>" (render!)) "gen1 renders")
  (is (nil? (rf/destroy-adapter!)))
  (is (nil? (rf/init! ui/adapter)))
  (is (re-find #"<div>ok</div>" (render!))
      "gen2 renders — the replacement generation's emitter is armed, not stranded by gen1's dispose"))

(deftest a-pre-init-explicit-custom-emitter-survives-the-install-replay
  ;; rf2-h9szm PRECEDENCE — under the real ui adapter on an SSR-loaded classpath,
  ;; an EXPLICIT custom emitter armed before init is NOT silently overwritten by
  ;; install-replay of the retained default. The replay arms only an otherwise-
  ;; unarmed slot, so the explicit direct override remains authoritative. Before
  ;; the fix the replay's unconditional broadcast clobbered the custom emitter
  ;; with the retained default.
  ;;
  ;; The public last-call-wins setter chain (`:reagent/set-hiccup-emitter!`) arms
  ;; every loaded adapter's cell, so the finally restores them all to the retained
  ;; default — no sibling adapter test observes the custom emitter.
  (let [default   (late-bind/get-fn :ssr/current-hiccup-emitter)
        set-chain (late-bind/get-fn :reagent/set-hiccup-emitter!)
        custom    (fn [_tree _opts] "CUSTOM-EMITTER-MARKER")]
    (try
      (set-chain custom)                        ;; explicit override, pre-init
      (is (nil? (rf/init! ui/adapter)))
      (is (= "CUSTOM-EMITTER-MARKER" (render!))
          "install-replay left the explicit custom emitter authoritative — the retained default did not win")
      (finally
        ;; Restore the retained default into every loaded adapter's slot.
        (set-chain default)))))
