(ns re-frame.ssr-doc-example-node-build-id-test
  "The \"Render on Node\" recipe in `docs/ssr/concepts.md` writes one build id
  three times — the bundle's `goog-define`, the adapter's `:build-id`, and
  the launcher's sample `ready` line — and the sidecar and adapter refuse
  every request on skew, so the three must agree. No docs gate reads inside a
  fence. The value itself is the page's to change; only agreement is pinned."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]))

(def ^:private concepts-page
  "Anchored to this file's classpath resource (five parents up is the repo root)."
  (let [res (io/resource "re_frame/ssr_doc_example_node_build_id_test.clj")]
    (assert res "the ssr test/ dir must be on the classpath to find the concepts page")
    (-> (io/file res)
        .getParentFile .getParentFile .getParentFile .getParentFile .getParentFile
        (io/file "docs" "ssr" "concepts.md")
        .getCanonicalFile)))

(defn- fence
  "The one ```<lang> fence on the page containing `needle`; hard-errors on
  zero or many. CRLF is normalised for a Windows checkout."
  [lang needle]
  (let [md   (str/replace (slurp concepts-page) "\r\n" "\n")
        hits (->> (re-seq (re-pattern (str "(?s)```" lang "\n(.*?)```")) md)
                  (map second)
                  (filter #(str/includes? % needle)))]
    (assert (= 1 (count hits))
            (str "expected EXACTLY ONE ```" lang " fence in " concepts-page
                 " containing " (pr-str needle) ", found " (count hits)))
    (first hits)))

(defn- only-capture
  "The one capture `re` finds in `text`; hard-errors on zero or many."
  [re what text]
  (let [hits (map second (re-seq re text))]
    (assert (= 1 (count hits))
            (str "expected EXACTLY ONE " what " in the fence, found " (count hits)))
    (first hits)))

(def ^:private bundle-build-id
  (delay
    (only-capture #"\(goog-define\s+build-id\s+\"([^\"]*)\"\)"
                  "(goog-define build-id \"…\") form"
                  (fence "clojure" "(goog-define build-id"))))

(def ^:private adapter-build-id
  (delay
    (only-capture #":build-id\s+\"([^\"]*)\""
                  ":build-id \"…\" renderer opt"
                  (fence "clojure" "node/renderer"))))

(def ^:private ready-line-build-id
  (delay
    (only-capture #"\"buildId\"\s*:\s*\"([^\"]*)\""
                  "\"buildId\" field on the ready line"
                  (fence "json" "\"rf.ssr-node\":\"ready\""))))

(deftest the-paired-build-id-literals-do-not-diverge
  (is (= @bundle-build-id @adapter-build-id)
      "the bundle's goog-define and the host's :build-id are one value written twice"))

(deftest the-ready-line-transcript-reports-the-bundles-build-id
  (is (= @bundle-build-id @ready-line-build-id)
      "the sample ready line reports the loaded bundle's id"))
