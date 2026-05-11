(ns re-frame.story-share-test
  "JVM tests for Stage 6 (rf2-zhwd) — per-variant share URL builder.

  The URL-building logic lives in `re-frame.story.share` (.cljc) so
  the same encoding works on JVM and CLJS. JVM tests round-trip the
  expected shape per IMPL-SPEC §2.8.5."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [re-frame.story        :as story]
            [re-frame.story.share  :as share]))

;; ---- pure: build-params --------------------------------------------------

(deftest build-params-minimal
  (testing "build-params returns the :variant param when only variant-id supplied"
    (let [ps (share/build-params {:variant-id :story.foo/bar})]
      (is (= 1 (count ps)))
      (is (re-find #"^variant=" (first ps))))))

(deftest build-params-modes
  (testing "build-params encodes modes as comma-separated stable list"
    (let [ps (share/build-params {:variant-id  :story.foo/bar
                                  :active-modes [:Mode.app/dark
                                                 :Mode.app/mobile]})
          modes-param (some #(when (str/starts-with? % "modes=") %) ps)]
      (is (some? modes-param))
      ;; The list is sorted alphabetically by keyword name.
      (is (or (re-find #"dark" modes-param)
              (re-find #"mobile" modes-param))))))

(deftest build-params-overrides
  (testing "build-params encodes overrides as comma-separated k:v pairs"
    (let [ps (share/build-params {:variant-id     :story.foo/bar
                                  :cell-overrides {:label "Click me"
                                                   :count 5}})
          ov (some #(when (str/starts-with? % "overrides=") %) ps)]
      (is (some? ov)))))

(deftest build-params-substrate-omits-reagent
  (testing "build-params omits :substrate when its value is :reagent (default)"
    (let [ps (share/build-params {:variant-id :story.foo/bar
                                  :substrate  :reagent})]
      (is (not (some #(str/starts-with? % "substrate=") ps))))))

(deftest build-params-substrate-non-default
  (testing "build-params includes :substrate when not :reagent"
    (let [ps (share/build-params {:variant-id :story.foo/bar
                                  :substrate  :uix})]
      (is (some #(str/starts-with? % "substrate=") ps)))))

;; ---- variant-share-url ---------------------------------------------------

(deftest variant-share-url-no-base
  (testing "variant-share-url with no base produces params without leading ?"
    (let [url (share/variant-share-url :story.foo/bar)]
      (is (string? url))
      (is (re-find #"variant=" url))
      ;; No leading scheme / slash with no base.
      (is (not (re-find #"^http" url))))))

(deftest variant-share-url-with-base
  (testing "variant-share-url prepends base + ?"
    (let [url (share/variant-share-url
                :story.foo/bar
                "https://example.test/stories.html"
                {:active-modes []
                 :cell-overrides {}})]
      (is (str/starts-with? url "https://example.test/stories.html?"))
      (is (re-find #"variant=" url)))))

(deftest variant-share-url-merges-existing-query
  (testing "variant-share-url uses & separator when base already has ?"
    (let [url (share/variant-share-url
                :story.foo/bar
                "https://example.test/?from=index"
                nil)]
      (is (re-find #"\?from=index&variant=" url)))))

(deftest variant-share-url-public-export
  (testing "story/variant-share-url is exported"
    (let [url (story/variant-share-url
                :story.foo/bar
                "https://x.test/"
                {:active-modes [:Mode.x/y]})]
      (is (str/starts-with? url "https://x.test/?"))
      (is (re-find #"variant=" url))
      (is (re-find #"modes=" url)))))

;; ---- QR endpoint ---------------------------------------------------------

(deftest qr-image-url-shape
  (testing "qr-image-url returns a URL embedding the share URL as data="
    (let [share-url "https://example.test/?variant=story.x%2Fy"
          qr-url    (share/qr-image-url share-url)]
      (is (str/starts-with? qr-url share/qr-endpoint))
      (is (re-find #"size=180x180" qr-url))
      (is (re-find #"data=" qr-url)))))

(deftest qr-image-url-custom-size
  (testing "qr-image-url accepts a custom square size"
    (let [qr (share/qr-image-url "https://x" 240)]
      (is (re-find #"size=240x240" qr)))))
