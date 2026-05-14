(ns re-frame.adapter-render-to-string-cljs-test
  "Per rf2-8k0g3 — pin the `:rf.error/no-hiccup-emitter-bound` ex-info
  shape and the post-(require re-frame.ssr) wiring.

  The Reagent adapter's `:render-to-string` slot throws when no hiccup
  emitter has been installed via `set-hiccup-emitter!`. The SSR
  artefact (`re-frame.ssr`) registers itself through the
  `:reagent/set-hiccup-emitter!` late-bind hook at ns-load, so the
  common case — `(require '[re-frame.ssr])` then call
  `(render-to-string ...)` — Just Works. This test pins two contracts:

    1. The pre-wire failure: when the emitter is absent, calling
       `render-to-string` throws an `ExceptionInfo` whose
       `ex-message` is `:rf.error/no-hiccup-emitter-bound` and whose
       `ex-data` carries `:reason` (string) and `:render-tree` (the
       tree passed in).

    2. The post-wire success: after `re-frame.ssr` has resolved the
       late-bind hook and installed the emitter, `render-to-string`
       returns an HTML string.

  Sibling: re-frame.adapter.uix-render-to-string-cljs-test on the UIx
  adapter (rf2-gc5v9) — same contract, different adapter.

  ns ends in -cljs-test so shadow-cljs's :node-test build picks it up."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [re-frame.adapter.reagent :as reagent-adapter]
            ;; Loading `re-frame.ssr` here is the canonical wiring path
            ;; — its ns-load resolves the `:reagent/set-hiccup-emitter!`
            ;; hook and installs the emitter (Spec 011 + rf2-uo7v).
            [re-frame.ssr]))

;; ---- helpers ---------------------------------------------------------------

(defn- with-cleared-emitter
  "Run f with the adapter's hiccup-emitter forced to nil; restore
  whatever was previously installed afterwards. The adapter exports
  `set-hiccup-emitter!` (public per the adapter ns docstring); we use
  it both to clear the slot and to restore it."
  [f]
  (let [;; Capture the currently-installed emitter by exercising
        ;; render-to-string indirectly: the only public read is to call
        ;; it. We can't peek; what we CAN do is snapshot the SSR-side
        ;; render-to-string fn (which is what was installed) and put it
        ;; back via set-hiccup-emitter! after the test. The fn lives in
        ;; re-frame.ssr/render-to-string (re-exported from
        ;; re-frame.ssr.emit/render-to-string).
        ssr-emitter (resolve 're-frame.ssr/render-to-string)]
    (reagent-adapter/set-hiccup-emitter! nil)
    (try
      (f)
      (finally
        (when ssr-emitter
          (reagent-adapter/set-hiccup-emitter! @ssr-emitter))))))

;; ---- test (1) — pre-wire failure: ex-info shape ----------------------------

(deftest render-to-string-throws-with-no-emitter
  (testing "rf2-8k0g3: (render-to-string [:div] {}) before the emitter is
            installed throws ExceptionInfo whose ex-message is
            ':rf.error/no-hiccup-emitter-bound' and whose ex-data carries
            :reason + :render-tree"
    (with-cleared-emitter
      (fn []
        (let [render-fn (:render-to-string reagent-adapter/adapter)
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

;; ---- test (2) — post-wire success ------------------------------------------

(deftest render-to-string-returns-html-after-ssr-require
  (testing "rf2-8k0g3: with re-frame.ssr loaded (the canonical wiring path
            via the :reagent/set-hiccup-emitter! late-bind hook), calling
            render-to-string returns a non-throwing HTML string"
    (let [render-fn (:render-to-string reagent-adapter/adapter)
          html      (render-fn [:div "ok"] {})]
      (is (string? html)
          "render-to-string returns a string")
      (is (clojure.string/includes? html "ok")
          "the hiccup body reaches the rendered HTML")
      (is (clojure.string/starts-with? html "<div")
          "rendered HTML starts with the expected root tag"))))
