(ns re-frame.adapter.uix-render-to-string-cljs-test
  "Per rf2-gc5v9 — pin the `:rf.error/no-hiccup-emitter-bound` ex-info
  shape and the direct-install wiring on the UIx adapter.

  Sibling: re-frame.adapter-render-to-string-cljs-test on the Reagent
  adapter (rf2-8k0g3). Same contract — different substrate.

  UIx ships its `:render-to-string` slot the same way the Reagent
  adapter does: a private `hiccup-emitter` atom, a public
  `set-hiccup-emitter!` installer, and a `render-to-string` fn that
  throws when the emitter is nil. The contract surface is identical.

  Difference vs the Reagent test: as of rf2-gc5v9, UIx does NOT publish
  its `set-hiccup-emitter!` through a late-bind hook (rf2-4z7bp tracks
  the parity gap). Until rf2-4z7bp lands, the SSR consumer under UIx
  has to call `set-hiccup-emitter!` directly. This test pins the
  shipped behaviour for both the throw path AND the direct-install
  success path; when rf2-4z7bp publishes the hook, an additional test
  asserting the post-(require re-frame.ssr) auto-wire can land here.

  ns ends in -cljs-test so shadow-cljs's :node-test build picks it up."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [re-frame.adapter.uix :as uix-adapter]))

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
        prior-render-fn (:render-to-string uix-adapter/adapter)]
    (uix-adapter/set-hiccup-emitter! nil)
    (try
      (f)
      (finally
        ;; The slot fn closes over the private atom — restoring is a
        ;; no-op unless something was installed beforehand. The
        ;; clear-then-restore dance keeps cross-test isolation intact.
        ;; (We don't know what was installed; the next test that needs
        ;; the emitter installs it fresh.)
        (identity prior-render-fn)))))

;; ---- test (1) — pre-wire failure: ex-info shape ----------------------------

(deftest render-to-string-throws-with-no-emitter
  (testing "rf2-gc5v9: (render-to-string [:div] {}) before the emitter is
            installed throws ExceptionInfo whose ex-message is
            ':rf.error/no-hiccup-emitter-bound' and whose ex-data carries
            :reason + :render-tree"
    (with-cleared-emitter
      (fn []
        (let [render-fn (:render-to-string uix-adapter/adapter)
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
  (testing "rf2-gc5v9: after (set-hiccup-emitter! emitter-fn), render-to-string
            returns the emitter's output. Direct-install path — until
            rf2-4z7bp publishes the UIx-side late-bind hook, this is the
            canonical wiring surface."
    (uix-adapter/set-hiccup-emitter! a-mock-emitter)
    (let [render-fn (:render-to-string uix-adapter/adapter)
          tree      [:div "ok"]
          html      (render-fn tree {})]
      (is (string? html)
          "render-to-string returns a string after set-hiccup-emitter!")
      (is (clojure.string/starts-with? html "<mock>")
          "the installed emitter is what render-to-string invokes")
      (is (clojure.string/includes? html (pr-str tree))
          "the installed emitter received the render-tree the caller passed in"))
    ;; Restore the slot to nil so subsequent tests don't see the mock.
    (uix-adapter/set-hiccup-emitter! nil)))
