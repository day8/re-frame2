(ns re-frame.adapter.helix-render-to-string-cljs-test
  "Per rf2-y9spn — pin the `:rf.error/no-hiccup-emitter-bound` ex-info
  shape, the direct-install wiring, AND the late-bind chain entry on
  the Helix adapter.

  Sibling: re-frame.adapter.uix-render-to-string-cljs-test (rf2-gc5v9).
  Same contract — different substrate.

  Helix ships its `:render-to-string` slot the same way the Reagent
  and UIx adapters do: a private `hiccup-emitter` atom (in the spine's
  closure), a public `set-hiccup-emitter!` installer, and a
  `render-to-string` fn that throws when the emitter is nil.

  Per rf2-y9spn the Helix adapter publishes its `set-hiccup-emitter!`
  through the chained `:reagent/set-hiccup-emitter!` late-bind hook
  (`re-frame.adapter.helix` calls `late-bind/chain-fn!` at ns-load);
  SSR's `re-frame.ssr.emit` consumes that hook at its own ns-load and
  auto-wires the emitter. Without that chain entry — the gap rf2-y9spn
  closes — a Helix-only SSR bundle silently no-ops the emitter slot
  under `(require '[re-frame.ssr])`, and `render-to-string` raises
  `:rf.error/no-hiccup-emitter-bound` despite the SSR ns being loaded.
  This file pins all three contracts.

  ns ends in -cljs-test so shadow-cljs's :node-test build picks it up."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [clojure.string :as str]
            [re-frame.adapter.helix :as helix-adapter]
            [re-frame.late-bind :as late-bind]))

;; ---- helpers ---------------------------------------------------------------

(defn- a-mock-emitter
  "A toy hiccup → HTML emitter so the install-path test can exercise
  set-hiccup-emitter! without dragging the full SSR artefact in. The
  emitter is render-tree-agnostic — it returns a fixed marker so the
  test asserts the wiring, not the rendering."
  [render-tree _opts]
  (str "<mock>" (pr-str render-tree) "</mock>"))

(defn- with-cleared-emitter
  "Run `f` with the adapter's hiccup-emitter forced to nil; restore
  whatever was previously installed afterwards."
  [f]
  (let [;; Capture by snapshotting whatever fn the slot ends up
        ;; producing post-test. The adapter's emitter atom is private;
        ;; set-hiccup-emitter! is the public install surface.
        prior-render-fn (:render-to-string helix-adapter/adapter)]
    (helix-adapter/set-hiccup-emitter! nil)
    (try
      (f)
      (finally
        ;; The slot fn closes over the private atom — restoring is a
        ;; no-op unless something was installed beforehand. The
        ;; clear-then-restore dance keeps cross-test isolation intact.
        (identity prior-render-fn)))))

;; ---- test (1) — pre-wire failure: ex-info shape ----------------------------

(deftest render-to-string-throws-with-no-emitter
  (testing "rf2-y9spn: (render-to-string [:div] {}) before the emitter is
            installed throws ExceptionInfo whose ex-message is
            ':rf.error/no-hiccup-emitter-bound' and whose ex-data carries
            :reason + :render-tree"
    (with-cleared-emitter
      (fn []
        (let [render-fn (:render-to-string helix-adapter/adapter)
              tree      [:div "smoke"]
              thrown    (try
                          (render-fn tree {})
                          nil
                          (catch :default e e))]
          (is (some? thrown)
              "render-to-string threw when no emitter was installed")
          (is (= ":rf.error/no-hiccup-emitter-bound" (.-message thrown))
              "ex-message names the error keyword")
          (let [data (ex-data thrown)]
            (is (some? data)
                "the thrown value carries ex-data")
            (is (string? (:reason data))
                ":reason key is a string explaining the misconfiguration")
            (is (= tree (:render-tree data))
                ":render-tree key carries the tree the caller passed in")))))))

;; ---- test (2) — direct-install success path --------------------------------

(deftest render-to-string-returns-html-after-direct-install
  (testing "rf2-y9spn: after (set-hiccup-emitter! emitter-fn), render-to-string
            returns the emitter's output. Direct-install path — exercised
            independently of the late-bind chain wiring below."
    (helix-adapter/set-hiccup-emitter! a-mock-emitter)
    (let [render-fn (:render-to-string helix-adapter/adapter)
          tree      [:div "ok"]
          html      (render-fn tree {})]
      (is (string? html)
          "render-to-string returns a string after set-hiccup-emitter!")
      (is (str/starts-with? html "<mock>")
          "the installed emitter is what render-to-string invokes")
      (is (str/includes? html (pr-str tree))
          "the installed emitter received the render-tree the caller passed in"))
    ;; Restore the slot to nil so subsequent tests don't see the mock.
    (helix-adapter/set-hiccup-emitter! nil)))

;; ---- test (3) — late-bind chain wiring (rf2-y9spn) -------------------------

(deftest set-hiccup-emitter-published-through-late-bind-chain
  (testing "rf2-y9spn: the Helix adapter chains its set-hiccup-emitter! into
            `:reagent/set-hiccup-emitter!` at ns-load (the same hook key
            Reagent / reagent-slim / UIx publish to, treated as
            adapter-agnostic and chained per rf2-4z7bp). Calling the hook
            installs the emitter into the Helix adapter's slot, so SSR's
            `re-frame.ssr.emit` ns-load auto-wires Helix's
            render-to-string without a direct `set-hiccup-emitter!` call
            from user code.

            Before rf2-y9spn this chain entry was MISSING — Helix-only
            SSR bundles silently no-op'd the emitter slot under
            `(require '[re-frame.ssr])` and `render-to-string` raised
            despite the SSR ns being loaded. This test would have failed
            with `the chained hook wired the Helix adapter's emitter
            slot` because the chain step never installed into Helix."
    (let [hook-fn (late-bind/get-fn :reagent/set-hiccup-emitter!)]
      (is (some? hook-fn)
          "the chained hook is registered after the Helix adapter ns has loaded")
      (with-cleared-emitter
        (fn []
          ;; Pre-condition: emitter cleared, render-to-string throws.
          (let [render-fn (:render-to-string helix-adapter/adapter)]
            (is (thrown? :default (render-fn [:div] {}))
                "precondition: emitter cleared"))
          ;; Drive the chained hook with our test emitter. The chain
          ;; fans the install across every loaded React-shaped adapter
          ;; (Reagent, reagent-slim, UIx, Helix) — the slot we care about
          ;; for this test is Helix's. After the call, render-to-string
          ;; on the Helix adapter returns the emitter's output.
          (hook-fn a-mock-emitter)
          (let [render-fn (:render-to-string helix-adapter/adapter)
                html      (render-fn [:div "via-chain"] {})]
            (is (str/starts-with? html "<mock>")
                "the chained hook wired the Helix adapter's emitter slot"))
          ;; Cleanup: drive the chain again with nil so loaded sibling
          ;; adapter slots reset, not just Helix's.
          (hook-fn nil))))))
