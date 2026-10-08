(ns re-frame.story.ui.sidebar-search-cljs-test
  "The sidebar search-as-you-type filter. Every fn under test is `.cljc`-pure,
  so this runs on the JVM and on the CLJS node-test build (the `-cljs-test`
  suffix is what the `:node-test` ns-regexp selects)."
  (:require [clojure.test :refer [are deftest is testing]]
            [re-frame.story.ui.sidebar-search :as rf.story.ui.sidebar-search]))

;; ---- match-variant? ------------------------------------------------------

(deftest tokenise-shape
  (testing "blank / nil input returns empty vector"
    (is (= [] (rf.story.ui.sidebar-search/tokenise nil)))
    (is (= [] (rf.story.ui.sidebar-search/tokenise "")))
    (is (= [] (rf.story.ui.sidebar-search/tokenise "   "))))
  (testing "single token"
    (is (= ["foo"] (rf.story.ui.sidebar-search/tokenise "foo")))
    (is (= ["foo"] (rf.story.ui.sidebar-search/tokenise "  Foo  "))))
  (testing "multi-token split on whitespace + lowercase"
    (is (= ["counter" "five"] (rf.story.ui.sidebar-search/tokenise "Counter Five")))
    (is (= ["a" "b" "c"]      (rf.story.ui.sidebar-search/tokenise "a   b\tc")))))

(deftest match-variant-shape
  ;; the haystack carries the variant's :doc and :tags as well as its id
  (are [token body] (true? (rf.story.ui.sidebar-search/match-variant? [token] :story.foo/bar body))
    "pending"    {:doc "pending stamp"}
    "screenshot" {:tags #{:screenshot}}))

;; ---- filter-grouped-tree -------------------------------------------------

(def ^:private fixture-grouped
  [{:story-id :story.counter
    :variants [[:story.counter/default {}]
               [:story.counter/at-five {}]]}
   {:story-id :story.login
    :variants [[:story.login/empty {}]
               [:story.login/error {}]]}])

(deftest filter-grouped-tree-token-and
  ;; Token-AND, case-insensitive. A story whose id matches every token keeps
  ;; all its variants; otherwise only variants matching every token survive,
  ;; and a story left with none drops out.
  (are [query out] (= out (rf.story.ui.sidebar-search/filter-grouped-tree fixture-grouped query))
    nil            fixture-grouped
    "   "          fixture-grouped
    "Counter"      [(first fixture-grouped)]
    ;; the story id matches "counter" but not "five"
    "counter five" [{:story-id :story.counter
                     :variants [[:story.counter/at-five {}]]}]
    "completely-unknown-token" []))

;; ---- filter-workspaces ---------------------------------------------------

(deftest filter-workspaces-narrow
  (let [ws {:Workspace.dashboard {}
            :Workspace.demo {}}]
    (are [query out] (= out (rf.story.ui.sidebar-search/filter-workspaces ws query))
      ""        ws
      "dash"    {:Workspace.dashboard {}}
      "missing" {})))

;; ---- highlight-segments --------------------------------------------------

(deftest highlight-segments-splits-the-label-around-matches
  (are [label query segments] (= segments (rf.story.ui.sidebar-search/highlight-segments label query))
    ;; an empty query is one non-match segment
    "/at-five" ""        [{:text "/at-five" :match? false}]
    ;; a single token matching mid-label
    "/at-five" "five"    [{:text "/at-" :match? false} {:text "five" :match? true}]
    ;; the match is case-insensitive and keeps the label's own case
    "AtFive"   "five"    [{:text "At" :match? false} {:text "Five" :match? true}]
    ;; a token that does not hit is one non-match segment
    "/at-five" "missing" [{:text "/at-five" :match? false}]))
