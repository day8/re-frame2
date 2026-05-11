(ns re-frame.story-share-cljs-test
  "CLJS smoke tests for Stage 6 (rf2-zhwd) — share URL builder. The
  pure URL logic in .cljc is JVM-tested in
  `re-frame.story-share-test`; this ns covers CLJS-specific paths
  (the `js/encodeURIComponent` path)."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [clojure.string :as str]
            [re-frame.story :as story]
            [re-frame.story.share :as share]))

(deftest variant-share-url-cljs-encoding
  (testing "CLJS url-encode uses js/encodeURIComponent under the hood"
    (let [url (share/variant-share-url
                :story.foo/bar
                "https://example.test/"
                {:active-modes [:Mode.app/dark]
                 :cell-overrides {}})]
      (is (str/starts-with? url "https://example.test/?"))
      (is (re-find #"variant=" url))
      (is (re-find #"modes=" url)))))

(deftest qr-image-url-shape
  (testing "qr-image-url builds an https URL with size + data"
    (let [share-url "https://example.test/?variant=story.x%2Fy"
          qr        (share/qr-image-url share-url 200)]
      (is (str/starts-with? qr share/qr-endpoint))
      (is (re-find #"size=200x200" qr))
      (is (re-find #"data=" qr)))))

(deftest public-export-cljs
  (testing "story/variant-share-url resolves on CLJS"
    (let [url (story/variant-share-url :story.x/y "" nil)]
      (is (re-find #"variant=" url)))))
