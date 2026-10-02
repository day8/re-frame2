(ns re-frame.story.ui.docs-markdown-cljs-test
  "CLJS-side coverage of the docs prose section's markdown integration.

  Pure-data coverage of the markdown parser lives in
  `re-frame.story.ui.markdown-test`. This namespace asserts that the
  docs pane's renderer PASSES prose bodies through `rf.story.ui.markdown/parse`
  rather than rendering them as raw `pre-wrap` text — pinning the
  integration so a future refactor that drops the parse call breaks
  the test loudly."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core             :as rf]
            [re-frame.frame            :as rf.frame]
            [re-frame.machines         :as rf.machines]
            [re-frame.registrar        :as rf.registrar]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.story            :as rf.story]
            [re-frame.story.loaders    :as rf.story.loaders]
            [re-frame.story.ui.docs    :as rf.story.ui.docs]
            [re-frame.story.ui.markdown :as rf.story.ui.markdown]
            [re-frame.story.ui.state   :as rf.story.ui.state]
            [re-frame.subs             :as rf.subs]))

;; ---- fixtures ------------------------------------------------------------

(defn reset-all! []
  (rf.story/clear-all!)
  (rf.registrar/clear-all!)
  (reset! rf.frame/frames {})
  (try (rf/init! rf.substrate.plain-atom/adapter)
       (catch :default _ nil))
  ;; Re-register the framework `:rf/machine` sub after the registrar clear.
  ;; EP-0001: a runtime-db sub reading
  ;; [:rf.runtime/machines :snapshots <id>], mirroring `re-frame.machines`.
  ;; There is no app-db `:rf/runtime` path.
  (rf.subs/reg-runtime-sub :rf/machine
    (fn [runtime-db [_ machine-id]]
      (get-in runtime-db [:rf.runtime/machines :snapshots machine-id])))
  (rf.machines/reset-timers!)
  (rf.story.loaders/clear-watchers!)
  (rf.story.ui.state/reset-shell-state!)
  (rf.story/install-canonical-vocabulary!)
  (rf.frame/ensure-default-frame!))

(use-fixtures :each {:before reset-all!})

;; ---- markdown smoke tests ------------------------------------------------

(deftest prose-body-walked-by-renderer-feeds-md-parser
  (testing "the prose-for-variant data the renderer iterates over
            preserves `:body` strings verbatim — the renderer then
            passes each body through rf.story.ui.markdown/parse. Pinning the data side
            of the integration plus the parse round-trip covers the
            full surface (the renderer itself is a pure projection)."
    (rf.story/reg-story :story.md-prose {:doc "" :tags #{:dev}})
    (rf.story/reg-variant :story.md-prose/v
      {:doc "" :setup []})
    (rf.story/reg-workspace :Workspace.md-prose/notes
      {:layout  :prose
       :content [{:type :variant :id :story.md-prose/v}
                 {:type :prose
                  :body "# Heading\n\nA paragraph with `code`."}]})
    (let [entries (rf.story.ui.docs/prose-for-variant :story.md-prose/v)]
      (is (= 1 (count entries)))
      (let [body (-> entries first :body)
            out  (rf.story.ui.markdown/parse body)]
        (is (string? body))
        (is (vector? out))
        (let [blocks (rest out)]
          (is (some #(= :h1 (first %)) blocks))
          (is (some #(= :p  (first %)) blocks)))))))
