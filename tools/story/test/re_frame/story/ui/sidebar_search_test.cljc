(ns re-frame.story.ui.sidebar-search-test
  "JVM-portable regression net for the sidebar search-as-you-type filter
  (rf2-yngai). Every fn under test is `.cljc`-pure so this corpus runs
  on both JVM (`clojure -M:test`) and CLJS (`npm run test:cljs`) — see
  the sibling discriminator pattern in `viewport_test.cljc` /
  `backgrounds_test.cljc`.

  Surface covered:

  - `tokenise`             — split / lowercase / blank-drop
  - `match-variant?`       — token-AND substring discrimination
  - `match-story?`         — story-id substring match
  - `filter-grouped-tree`  — story-keeps-children / variant-narrow /
                              prune-empty-stories
  - `filter-workspaces`    — workspace map narrowing
  - `highlight-segments`   — match / non-match segmentation"
  (:require [clojure.test :refer [deftest is testing]]
            [re-frame.story.ui.sidebar-search :as search]))

;; ---- tokenise ------------------------------------------------------------

(deftest tokenise-shape
  (testing "blank / nil input returns empty vector"
    (is (= [] (search/tokenise nil)))
    (is (= [] (search/tokenise "")))
    (is (= [] (search/tokenise "   "))))
  (testing "single token"
    (is (= ["foo"] (search/tokenise "foo")))
    (is (= ["foo"] (search/tokenise "  Foo  "))))
  (testing "multi-token split on whitespace + lowercase"
    (is (= ["counter" "five"] (search/tokenise "Counter Five")))
    (is (= ["a" "b" "c"]      (search/tokenise "a   b\tc")))))

;; ---- match-variant? ------------------------------------------------------

(deftest match-variant-shape
  (testing "empty tokens → match-all"
    (is (true? (search/match-variant? [] :story.x/y {}))))

  (testing "token in variant id matches"
    (is (true? (search/match-variant? ["five"] :story.counter/at-five {}))))

  (testing "token-AND: every token must hit"
    (is (true? (search/match-variant? ["counter" "five"]
                                       :story.counter/at-five {})))
    (is (false? (search/match-variant? ["counter" "missing"]
                                        :story.counter/at-five {}))))

  (testing "case-insensitive"
    (is (true? (search/match-variant? ["FIVE"] :story.counter/at-five {})))
    (is (true? (search/match-variant? ["Five"] :story.counter/at-five {}))))

  (testing "token matches against variant's :doc + :tags"
    (is (true? (search/match-variant? ["pending"]
                                       :story.foo/bar
                                       {:doc "pending stamp"})))
    (is (true? (search/match-variant? ["screenshot"]
                                       :story.foo/bar
                                       {:tags #{:screenshot}})))))

;; ---- match-story? --------------------------------------------------------

(deftest match-story-shape
  (testing "empty tokens → true"
    (is (true? (search/match-story? [] :story.x))))
  (testing "token-AND on story id"
    (is (true? (search/match-story? ["counter"] :story.counter)))
    (is (true? (search/match-story? ["story"] :story.counter)))
    (is (false? (search/match-story? ["missing"] :story.counter)))))

;; ---- filter-grouped-tree -------------------------------------------------

(def ^:private fixture-grouped
  [{:story-id :story.counter
    :variants [[:story.counter/default {}]
               [:story.counter/at-five {}]]}
   {:story-id :story.login
    :variants [[:story.login/empty {}]
               [:story.login/error {}]]}])

(deftest filter-grouped-tree-empty-query
  (testing "blank / empty query → unchanged"
    (is (= fixture-grouped (search/filter-grouped-tree fixture-grouped nil)))
    (is (= fixture-grouped (search/filter-grouped-tree fixture-grouped "")))
    (is (= fixture-grouped (search/filter-grouped-tree fixture-grouped "   ")))))

(deftest filter-grouped-tree-story-match-keeps-children
  (testing "story id matches → all variants survive (ancestor-keeps-children)"
    (let [out (search/filter-grouped-tree fixture-grouped "counter")]
      (is (= 1 (count out)))
      (is (= :story.counter (-> out first :story-id)))
      ;; both variants kept
      (is (= 2 (count (-> out first :variants)))))))

(deftest filter-grouped-tree-variant-narrow
  (testing "story id NO match, only matching variants survive"
    (let [out (search/filter-grouped-tree fixture-grouped "five")]
      (is (= 1 (count out)))
      (is (= :story.counter (-> out first :story-id)))
      (is (= 1 (count (-> out first :variants))))
      (is (= :story.counter/at-five (ffirst (-> out first :variants)))))))

(deftest filter-grouped-tree-prunes-empty
  (testing "story with zero surviving variants drops out"
    (let [out (search/filter-grouped-tree fixture-grouped "completely-unknown-token")]
      (is (= [] out)))))

(deftest filter-grouped-tree-token-and
  (testing "token-AND: tokens must all hit (story-match keeps children;
            else variant haystack must carry every token)"
    ;; "counter five" — story matches only "counter", not "five", so
    ;; story-match is false. Variant haystack filter requires BOTH
    ;; tokens: only :at-five carries "five".
    (let [out (search/filter-grouped-tree fixture-grouped "counter five")]
      (is (= 1 (count out)))
      (is (= :story.counter (-> out first :story-id)))
      (is (= 1 (count (-> out first :variants))))
      (is (= :story.counter/at-five (ffirst (-> out first :variants)))))
    (let [out (search/filter-grouped-tree fixture-grouped "login error")]
      (is (= 1 (count out)))
      (is (= :story.login (-> out first :story-id)))
      ;; story-id matches only "login"; only :error survives the variant
      ;; filter
      (is (= 1 (count (-> out first :variants)))))))

;; ---- filter-workspaces ---------------------------------------------------

(deftest filter-workspaces-empty-query
  (let [ws {:Workspace.dashboard {}
            :Workspace.demo {}}]
    (is (= ws (search/filter-workspaces ws "")))
    (is (= ws (search/filter-workspaces ws nil)))))

(deftest filter-workspaces-narrow
  (let [ws {:Workspace.dashboard {}
            :Workspace.demo {}}]
    (let [out (search/filter-workspaces ws "dash")]
      (is (= [:Workspace.dashboard] (vec (keys out)))))
    (let [out (search/filter-workspaces ws "missing")]
      (is (= {} out)))))

;; ---- highlight-segments --------------------------------------------------

(deftest highlight-segments-empty-query
  (testing "empty query → one non-match segment"
    (is (= [{:text "/at-five" :match? false}]
           (search/highlight-segments "/at-five" "")))))

(deftest highlight-segments-single-match
  (testing "single token, single match in middle"
    (let [out (search/highlight-segments "/at-five" "five")]
      (is (= [{:text "/at-" :match? false}
              {:text "five" :match? true}]
             out)))))

(deftest highlight-segments-case-insensitive-preserves-case
  (testing "case-insensitive match preserves original label case"
    (let [out (search/highlight-segments "AtFive" "five")]
      (is (= [{:text "At" :match? false}
              {:text "Five" :match? true}]
             out)))))

(deftest highlight-segments-multi-token
  (testing "multi-token: first hit becomes the highlighted segment"
    ;; "five" hits first in /at-five, recursive remainder has no more
    ;; hits → single highlighted segment
    (let [out (search/highlight-segments "/at-five" "five at")]
      (is (some :match? out)))))

(deftest highlight-segments-no-match
  (testing "tokens don't hit → single non-match segment"
    (let [out (search/highlight-segments "/at-five" "missing")]
      (is (= [{:text "/at-five" :match? false}] out)))))
