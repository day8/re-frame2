(ns re-frame.story-share-cljs-test
  "CLJS smoke tests for Stage 6 (rf2-zhwd) — share URL builder + QR
  encoder. The pure URL logic in .cljc is JVM-tested in
  `re-frame.story-share-test`; this ns covers CLJS-specific paths
  (the `js/encodeURIComponent` path) plus the local QR encoder
  (CLJS-only — see `re-frame.story.qr` per rf2-20w5i)."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [clojure.string :as str]
            [re-frame.story :as story]
            [re-frame.story.qr :as qr]
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

(deftest public-export-cljs
  (testing "story/variant-share-url resolves on CLJS"
    (let [url (story/variant-share-url :story.x/y "" nil)]
      (is (re-find #"variant=" url)))))

(deftest hash-routed-share-url-keeps-query-before-fragment
  (testing "CLJS builder emits ?variant= before #/stories so the shell can hydrate"
    (let [url (share/variant-share-url
                :story.counter/loaded
                "https://example.test/counter-with-stories/#/stories"
                {:cell-overrides {:label "Shared"}})]
      (is (str/starts-with? url "https://example.test/counter-with-stories/?"))
      (is (str/includes? url "#/stories"))
      (is (str/includes? url "variant="))
      (is (str/includes? url "overrides=")))))

(deftest parse-share-url-params-cljs
  (testing "CLJS parser reconstructs the share URL tokens used by the shell hydrator"
    (is (= :story.counter/loaded
           (share/parse-keyword-token "story.counter/loaded")))
    (is (= [:Mode.app/dark :Mode.app/mobile]
           (share/parse-modes-param "Mode.app/dark,Mode.app/mobile")))
    (is (= {:label "Shared" :count 7}
           (share/parse-overrides-param "label:\"Shared\",count:7")))))

;; ---- local QR encoder (rf2-20w5i) ---------------------------------------
;;
;; Per the security audit, the QR is now generated locally via the
;; vendored `qrcode-generator` npm package. These tests pin the output
;; shape — a `<svg>` element with width/height attributes — so a
;; future change to the encoder API surfaces here.

(deftest qr-svg-string-shape
  (testing "qr-svg-string returns an inline SVG element string"
    (let [svg (qr/qr-svg-string "https://example.test/?variant=story.x%2Fy" 4)]
      (is (string? svg))
      (is (str/starts-with? svg "<svg"))
      (is (str/includes? svg "</svg>"))
      ;; The encoder emits a width / height attribute.
      (is (re-find #"width=" svg))
      (is (re-find #"height=" svg)))))

(deftest qr-svg-string-deterministic
  (testing "qr-svg-string is deterministic for a given input — golden-shape
            assertion that the same URL renders to the same SVG bytes.
            Tied to the qrcode-generator package version pin in
            implementation/package.json; an upgrade that changes the
            output (e.g. a different mask-pattern selection) trips this."
    (let [a (qr/qr-svg-string "https://example.test/?variant=story.x%2Fy" 4)
          b (qr/qr-svg-string "https://example.test/?variant=story.x%2Fy" 4)]
      (is (= a b)))))

(deftest qr-svg-string-different-urls-distinct
  (testing "different input URLs produce different SVG bytes"
    (let [a (qr/qr-svg-string "https://example.test/?variant=story.a/a" 4)
          b (qr/qr-svg-string "https://example.test/?variant=story.b/b" 4)]
      (is (not= a b)))))

(deftest qr-svg-string-no-network-literal
  (testing "the encoder is purely local — no third-party host appears
            in the generated SVG. Pre-fix the share popover sourced its
            QR from api.qrserver.com; that endpoint must not surface
            inside the new SVG output."
    (let [svg (qr/qr-svg-string "https://example.test/" 4)]
      (is (not (str/includes? svg "api.qrserver.com")))
      (is (not (str/includes? svg "qrserver"))))))
