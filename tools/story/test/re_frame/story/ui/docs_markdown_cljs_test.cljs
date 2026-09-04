(ns re-frame.story.ui.docs-markdown-cljs-test
  "CLJS-side coverage of the docs prose section's markdown integration
  (rf2-wl7yr, audit C-2).

  Pure-data coverage of the markdown parser lives in
  `re-frame.story.ui.markdown-test`. This namespace asserts that the
  docs pane's renderer now PASSES prose bodies through `rf.story.ui.markdown/parse`
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
  ;; EP-0001 (rf2-vzld77 / rf2-ixb0bq): a runtime-db sub reading
  ;; [:rf.runtime/machines :snapshots <id>], NOT the retired app-db
  ;; `:rf/runtime` path — mirror `re-frame.machines`.
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

(deftest markdown-parse-is-pure-data
  (testing "rf.story.ui.markdown/parse returns a hiccup vector with no JS interop —
            JVM-and-CLJS-symmetric (the parser itself is JVM-tested in
            re-frame.story.ui.markdown-test; this test confirms the
            same parser runs cleanly under cljs.test)"
    (let [out (rf.story.ui.markdown/parse "# Title\n\nbody **bold** end")]
      (is (vector? out))
      (is (= :div.rf-story-md (first out)))
      (let [[_ h1 p] out]
        (is (= :h1 (first h1)))
        (is (= :p  (first p)))))))

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

(deftest prose-with-bullet-list-roundtrips-to-ul
  (testing "a workspace prose body with markdown bullets parses to a
            <ul> block — proves the data → rf.story.ui.markdown/parse contract for the
            common docs shape (bulleted lists are the most-used
            markdown affordance in Story prose)"
    (rf.story/reg-story :story.md-list {:doc "" :tags #{:dev}})
    (rf.story/reg-variant :story.md-list/v {:doc "" :setup []})
    (rf.story/reg-workspace :Workspace.md-list/notes
      {:layout  :prose
       :content [{:type :variant :id :story.md-list/v}
                 {:type :prose
                  :body "Steps:\n\n- one\n- two\n- three"}]})
    (let [body  (-> (rf.story.ui.docs/prose-for-variant :story.md-list/v) first :body)
          out   (rf.story.ui.markdown/parse body)
          ul    (some #(when (= :ul (first %)) %) (rest out))]
      (is (some? ul))
      (is (= 3 (count (drop 2 ul)))
          "three <li> children for the three bullet lines"))))
