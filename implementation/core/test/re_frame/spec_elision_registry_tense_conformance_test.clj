(ns re-frame.spec-elision-registry-tense-conformance-test
  "`spec/Tool-Pair.md` and `spec/Security.md` carry the direct-read privacy
  contract that pair and off-box tools follow. The elision registry is
  runtime-db state at `[:rf.runtime/elision …]`; a spec teaching the app-db path
  `[:rf/runtime :elision …]` in current tense sends a walker to a dead registry,
  where it emits raw values. A mention framed as retired history is allowed."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]))

(def ^:private repo-root
  "The first candidate base holding `spec/Tool-Pair.md`. The core `:test` alias
  runs from `implementation/core/`, so `../..` is the usual answer."
  (->> ["../.." ".." "." "../../.."]
       (map io/file)
       (filter #(.isFile (io/file % "spec" "Tool-Pair.md")))
       first))

(defn- current-tense-app-db-elision-lines [md]
  (->> (str/split-lines md)
       (filter #(re-find #"\[:rf/runtime\s+:elision" %))
       (remove #(re-find #"(?i)retired|no longer|legacy|formerly|used to|briefly sat" %))))

(deftest guarded-specs-teach-the-runtime-db-elision-registry
  (doseq [file-name ["Tool-Pair.md" "Security.md"]]
    ;; A nil root or unreadable file throws here, which reds the test.
    (let [body (slurp (io/file repo-root "spec" file-name))]
      ;; Without the live form the section has moved or been gutted, and the
      ;; absence check below would pass vacuously.
      (is (str/includes? body "[:rf.runtime/elision")
          (str "spec/" file-name " no longer mentions `[:rf.runtime/elision` — "
               "re-home this pin with the direct-read privacy section"))
      (is (empty? (current-tense-app-db-elision-lines body))
          (str "spec/" file-name " teaches the retired app-db "
               "`[:rf/runtime :elision …]` registry in current tense: "
               (pr-str (current-tense-app-db-elision-lines body)))))))
