(ns re-frame.api-manifest.doc-api-check-test
  "Tests for the human-doc API-reference projection check (spec/Privacy.md,
  docs/api/**, docs/story/api/**): call-position references must resolve to
  a manifest row in the namespace their alias names, and docs/api must give
  every eligible manifest var a page and a member heading."
  (:require [clojure.test :refer [deftest is]]
            [re-frame.api-manifest.doc-api-check :as rf.api-manifest.doc-api-check]
            [re-frame.api-manifest.projection :as rf.api-manifest.projection]))

(deftest reconcile-flags-unknown-names-and-scoped-names-outside-their-file
  (is (= [["docs/api/re-frame.core.md" 85] ["docs/api/re-frame.core.md" 50]]
         (map (juxt :file :line)
              (rf.api-manifest.doc-api-check/reconcile
                {:rows          []
                 :scoped-allow  {"reg-sub-raw" #{"migration/from-re-frame-v1/README.md"}}
                 :references
                 [{:alias "rf" :var "path" :line 85 :raw "rf/path"
                   :file "docs/api/re-frame.core.md"}
                  ;; the approved tombstone file: silenced
                  {:alias "rf" :var "reg-sub-raw" :line 26 :raw "rf/reg-sub-raw"
                   :file "migration/from-re-frame-v1/README.md"}
                  {:alias "rf" :var "reg-sub-raw" :line 50 :raw "rf/reg-sub-raw"
                   :file "docs/api/re-frame.core.md"}]})))))

(deftest reconcile-resolves-each-alias-in-its-own-namespace
  ;; `rf` names re-frame.core and `rf.story` names re-frame.story, so a call
  ;; left behind after its var moved to another namespace is red although
  ;; the var still exists there.
  (let [problems (rf.api-manifest.doc-api-check/reconcile
                   {:rows         [{:namespace "re-frame.core" :var "dispatch"}
                                   {:namespace "re-frame.machines" :var "machine-transition"}
                                   {:namespace "re-frame.story" :var "reg-story"}]
                    :scoped-allow {}
                    :references
                    (for [[line alias var] [[1 "rf" "dispatch"]
                                            [2 "rf" "machine-transition"]
                                            [3 "rf.story" "reg-story"]
                                            [4 "rf.story" "dispatch"]]]
                      {:alias alias :var var :line line :raw (str alias "/" var)
                       :file "docs/api/re-frame.core.md"})})]
    (is (= [2 4] (map :line problems)))
    (is (re-find #"\bre-frame\.core\b" (str (:detail (first problems)))))
    (is (re-find #"\bre-frame\.story\b" (str (:detail (second problems)))))))

(deftest story-api-references-reach-the-check-under-rf-story
  ;; The aggregate floor sits far below the live count, so dropping the
  ;; `rf.story` alias from the extraction would narrow the gate without
  ;; turning it red.
  (let [files (rf.api-manifest.projection/require-markdown-files
                "docs/story/api/"
                (rf.api-manifest.projection/repo-file "docs" "story" "api"))]
    (is (seq (filter #(and (= "rf.story" (:alias %)) (re-find #"^rf\.story/" (:raw %)))
                     (rf.api-manifest.doc-api-check/references-in-files files))))))

(deftest coverage-flags-missing-pages-and-member-headings
  ;; A missing page is ONE :page-missing problem that subsumes its members.
  (let [rows     [{:namespace "re-frame.fresco.overlay" :var "modal"}
                  {:namespace "re-frame.fresco.overlay" :var "popover"}
                  {:namespace "re-frame.fresco.forms"   :var "buffered-field"}
                  {:namespace "re-frame.fresco.forms"   :var "drafts"}]
        problems #(rf.api-manifest.doc-api-check/coverage-problems
                    {:eligible-rows rows
                     :members       {"re-frame.fresco.overlay" #{"modal"}}
                     :exempt        %})]
    (is (= [{:kind :page-missing :namespace "re-frame.fresco.forms"}
            {:kind :member-missing :namespace "re-frame.fresco.overlay" :var "popover"}]
           (problems #{})))
    (is (= [{:kind :page-missing :namespace "re-frame.fresco.forms"}]
           (problems #{["re-frame.fresco.overlay" "popover"]}))
        "an explicit [namespace var] exemption silences a facade-pointer member")))
