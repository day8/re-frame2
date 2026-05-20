(ns re-frame.story.ui.docs-markdown-cljs-test
  "CLJS-side coverage of the docs prose section's markdown integration
  (rf2-wl7yr, audit C-2).

  Pure-data coverage of the markdown parser lives in
  `re-frame.story.ui.markdown-test`. This namespace asserts that the
  docs pane's renderer now PASSES prose bodies through `md/parse`
  rather than rendering them as raw `pre-wrap` text — pinning the
  integration so a future refactor that drops the parse call breaks
  the test loudly."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core             :as rf]
            [re-frame.frame            :as frame]
            [re-frame.machines         :as machines]
            [re-frame.registrar        :as registrar]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.story            :as story]
            [re-frame.story.assertions :as assertions]
            [re-frame.story.loaders    :as loaders]
            [re-frame.story.ui.docs    :as docs]
            [re-frame.story.ui.markdown :as md]
            [re-frame.story.ui.state   :as state]))

;; ---- fixtures ------------------------------------------------------------

(defn reset-all! []
  (story/clear-all!)
  (registrar/clear-all!)
  (reset! frame/frames {})
  (try (rf/init! plain-atom/adapter)
       (catch :default _ nil))
  (rf/reg-sub :rf/machine
              (fn [db [_ machine-id]]
                (get-in db [:rf/machines machine-id])))
  (machines/reset-timers!)
  (loaders/clear-watchers!)
  (reset! assertions/trace-accumulators {})
  (state/reset-shell-state!)
  (story/install-canonical-vocabulary!)
  (frame/ensure-default-frame!))

(use-fixtures :each {:before reset-all!})

;; ---- markdown smoke tests ------------------------------------------------

(deftest markdown-parse-is-pure-data
  (testing "md/parse returns a hiccup vector with no JS interop —
            JVM-and-CLJS-symmetric (the parser itself is JVM-tested in
            re-frame.story.ui.markdown-test; this test confirms the
            same parser runs cleanly under cljs.test)"
    (let [out (md/parse "# Title\n\nbody **bold** end")]
      (is (vector? out))
      (is (= :div.rf-story-md (first out)))
      (let [[_ h1 p] out]
        (is (= :h1 (first h1)))
        (is (= :p  (first p)))))))

(deftest prose-body-walked-by-renderer-feeds-md-parser
  (testing "the prose-for-variant data the renderer iterates over
            preserves `:body` strings verbatim — the renderer then
            passes each body through md/parse. Pinning the data side
            of the integration plus the parse round-trip covers the
            full surface (the renderer itself is a pure projection)."
    (story/reg-story :story.md-prose {:doc "" :tags #{:dev}})
    (story/reg-variant :story.md-prose/v
      {:doc "" :events []})
    (story/reg-workspace :Workspace.md-prose/notes
      {:layout  :prose
       :content [{:type :variant :id :story.md-prose/v}
                 {:type :prose
                  :body "# Heading\n\nA paragraph with `code`."}]})
    (let [entries (docs/prose-for-variant :story.md-prose/v)]
      (is (= 1 (count entries)))
      (let [body (-> entries first :body)
            out  (md/parse body)]
        (is (string? body))
        (is (vector? out))
        (let [blocks (rest out)]
          (is (some #(= :h1 (first %)) blocks))
          (is (some #(= :p  (first %)) blocks)))))))

(deftest prose-with-bullet-list-roundtrips-to-ul
  (testing "a workspace prose body with markdown bullets parses to a
            <ul> block — proves the data → md/parse contract for the
            common docs shape (bulleted lists are the most-used
            markdown affordance in Story prose)"
    (story/reg-story :story.md-list {:doc "" :tags #{:dev}})
    (story/reg-variant :story.md-list/v {:doc "" :events []})
    (story/reg-workspace :Workspace.md-list/notes
      {:layout  :prose
       :content [{:type :variant :id :story.md-list/v}
                 {:type :prose
                  :body "Steps:\n\n- one\n- two\n- three"}]})
    (let [body  (-> (docs/prose-for-variant :story.md-list/v) first :body)
          out   (md/parse body)
          ul    (some #(when (= :ul (first %)) %) (rest out))]
      (is (some? ul))
      (is (= 3 (count (drop 2 ul)))
          "three <li> children for the three bullet lines"))))
