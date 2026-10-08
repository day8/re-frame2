(ns re-frame.adapter-render-to-string-cljs-test
  "The Reagent adapter's `:render-to-string` slot throws
  `:rf.error/no-hiccup-emitter-bound` while no hiccup emitter is installed,
  carrying a `:reason` and an EP-0015-safe `:render-tree/summary` rather than
  the tree; once `re-frame.ssr` has installed its emitter through the
  `:reagent/set-hiccup-emitter!` late-bind hook at ns-load, it returns HTML."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [re-frame.adapter.reagent :as rf.adapter.reagent]
            ;; Loading `re-frame.ssr` here is the canonical wiring path
            ;; — its ns-load resolves the `:reagent/set-hiccup-emitter!`
            ;; hook and installs the emitter (Spec 011).
            [re-frame.ssr]))

;; ---- helpers ---------------------------------------------------------------

(defn- with-cleared-emitter
  "Run f with the adapter's hiccup-emitter forced to nil; restore
  whatever was previously installed afterwards. The adapter exports
  `set-hiccup-emitter!` (public per the adapter ns docstring); we use
  it both to clear the slot and to restore it."
  [f]
  (let [;; The installed emitter cannot be read back, so restore the one
        ;; re-frame.ssr installs.
        ssr-emitter (resolve 're-frame.ssr/render-to-string)]
    (rf.adapter.reagent/set-hiccup-emitter! nil)
    (try
      (f)
      (finally
        (when ssr-emitter
          (rf.adapter.reagent/set-hiccup-emitter! @ssr-emitter))))))

;; ---- test (1) — pre-wire failure: ex-info shape ----------------------------

(deftest render-to-string-throws-with-no-emitter
  (testing "(render-to-string [:div] {}) before the
            emitter is installed throws ExceptionInfo whose ex-message is
            ':rf.error/no-hiccup-emitter-bound' and whose ex-data carries
            :reason + an EP-0015-safe :render-tree/summary (never the raw
            tree)"
    (with-cleared-emitter
      (fn []
        (let [render-fn (:render-to-string rf.adapter.reagent/adapter)
              ;; The body carries a recognisable token; per EP-0015 it
              ;; MUST NOT survive into the thrown diagnostic.
              tree      [:div "smoke-secret-xyzzy"]
              thrown    (try
                          (render-fn tree {})
                          nil
                          (catch :default e e))
              data      (ex-data thrown)]
          (is (= {:id :rf.error/no-hiccup-emitter-bound :reason-string? true
                  :raw-tree nil :summary {:type :vector :count 2}
                  :leaked-into-data? false :leaked-into-message? false}
                 {:id                   (:rf.error/id data)
                  :reason-string?       (string? (:reason data))
                  :raw-tree             (:render-tree data)
                  :summary              (select-keys (:render-tree/summary data) [:type :count])
                  :leaked-into-data?    (boolean (re-find #"xyzzy" (pr-str data)))
                  :leaked-into-message? (boolean (re-find #"xyzzy" (.-message thrown)))})
              "the canonical id and a :reason, with only the tree's SHAPE (EP-0015) — no raw tree, and no child content in the data or the message"))))))

;; ---- test (2) — post-wire success ------------------------------------------

(deftest render-to-string-returns-html-after-ssr-require
  (testing "with re-frame.ssr loaded (the canonical wiring path
            via the :reagent/set-hiccup-emitter! late-bind hook), calling
            render-to-string returns a non-throwing HTML string"
    (let [render-fn (:render-to-string rf.adapter.reagent/adapter)
          html      (render-fn [:div "ok"] {})]
      (is (and (string? html)
               (clojure.string/starts-with? html "<div")
               (clojure.string/includes? html "ok"))
          (str "render-to-string returns HTML with the root tag and the body; got " (pr-str html))))))
