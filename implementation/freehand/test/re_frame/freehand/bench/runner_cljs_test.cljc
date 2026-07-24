(ns re-frame.freehand.bench.runner-cljs-test
  "The stdout contract: everything the runner writes is EDN a PLAIN reader
  can read.

  [[re-frame.freehand.bench.runner]]'s docstring promises the same bytes
  are both the thing a reviewer reads and the artefact a release cites —
  `clojure -M:bench > out/freehand-bench.edn`, read back with
  `clojure.edn/read-string`. A fixture that published a host value with no
  EDN print form keeps that promise SYNTACTICALLY — a view descriptor
  prints as a `#re-frame.freehand/view` tagged literal, and a tagged
  literal is EDN — while breaking it operationally: the reader must
  register a tag handler before the file will open at all, which no reader
  of a benchmark artefact expects to.

  So this suite reads the artefact the only way a consumer should have to:
  with no `:default` handler and no tag registry. A future fixture that
  smuggles an opaque host value is caught here, at the door, rather than by
  the first reader who slurps the file. The discrimination test proves the
  guard is real by putting a descriptor back and watching the same read
  throw."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [re-frame.freehand.bench.b2 :as b2]
            [re-frame.freehand.bench.runner :as runner]))

(def ^:private provenance
  "The revision a TEST supplies. A ClojureScript test process is not a
  build pipeline: no environment variable, no git, and nothing it could
  truthfully detect. The published run detects its own."
  {:revision "test-fixture-revision"})

;; ---------------------------------------------------------------------------
;; The artefact reads back with a plain reader
;; ---------------------------------------------------------------------------

(deftest the-suite-artefact-reads-back-with-a-plain-edn-reader
  (testing "the whole `clojure -M:bench` artefact — the summary as leading
            `;;` comments and the report map beneath — reads back with
            `clojure.edn/read-string`, no `:default` tag handler and no
            reader registry, and to exactly the value it published."
    (let [answer (runner/run-cli [] provenance)
          stdout (runner/render answer)]
      (is (not (str/includes? stdout "#re-frame.freehand/view"))
          "no view descriptor rode the artefact as a tagged literal")
      (let [parsed (edn/read-string stdout)]   ;; deliberately no {:default …}
        (is (= (:report answer) parsed)
            "the artefact reads back to exactly the report it published")))))

;; ---------------------------------------------------------------------------
;; …and the guard discriminates
;; ---------------------------------------------------------------------------

(deftest a-descriptor-in-a-fixture-would-red-this-very-guard
  (testing "put a view DESCRIPTOR back into a published fixture — exactly
            what B2 used to publish — and the same plain read throws. So the
            first test passing is a real absence, not a reader quietly
            tolerating a tag no one registered."
    (let [descriptor (first b2/free-views)
          leaked     (assoc-in (runner/run-cli [] provenance)
                               [:report :results 0 :fixture :free-plan]
                               [[descriptor 1]])
          stdout     (runner/render leaked)]
      (is (str/includes? stdout "#re-frame.freehand/view")
          "a descriptor prints as a tagged literal — the print form the fix removes")
      (is (thrown? #?(:clj Exception :cljs js/Error)
                   (edn/read-string stdout))
          "and a plain reader cannot read it back — which is the whole bug"))))
