(ns day8.re-frame2-template.repo-split-spec-test
  "Executable checks for the deps-new grammar documented by 005-Repo-Split."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [day8.re-frame2-template.test-support :refer [repo-root]]
            [org.corfield.new.impl :as deps-new.impl]))

(defn- repo-split-spec-text
  []
  (slurp (io/file (repo-root) "tools/template/spec/005-Repo-Split.md")))

(defn- documented-local-overrides
  "Return the concrete local-root override spellings from the split spec."
  []
  (map (fn [[_ separator-and-template]]
         (str "day8/re-frame2-template" separator-and-template))
       (re-seq #"(%+io\.github\.day8/re-frame2-template)"
               (repo-split-spec-text))))

(deftest local-root-override-grammar-test
  (testing "all four documented overrides parse to the canonical template symbol"
    (let [overrides (documented-local-overrides)]
      (is (= 4 (count overrides))
          "the split procedure must keep its four concrete override examples under test")
      (doseq [override overrides]
        ;; Parsing a local-root override must not resolve or load a git lib. Stub only
        ;; that side effect while exercising deps-new's production option parser.
        (let [parsed (with-redefs [deps-new.impl/get-git-sha
                                   (constantly [nil nil])]
                       (deps-new.impl/preprocess-options
                         {:template (symbol override)
                          :name     'acme/example}))]
          (is (= "io.github.day8/re-frame2-template" (:template parsed))
              (str "deps-new must parse " override
                   " as the canonical post-split template symbol")))))))
