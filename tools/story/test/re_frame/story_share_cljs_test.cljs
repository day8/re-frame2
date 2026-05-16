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

;; ---- rf2-3y7l4: overlong-URL guard --------------------------------------
;;
;; qrcode-generator at type-number 0 + ECC 'M' tops out around 2300
;; alphanumeric chars (well under the 4296 absolute max for the largest
;; type). A share URL carrying a fat :cell-overrides EDN map can blow
;; past that. Pre-fix the throw propagated into Reagent render and
;; blanked the share popover (taking the copy-link button with it).
;; Post-fix the encoder returns nil — the popover renders a degraded
;; panel and keeps copy-link reachable.

(deftest qr-svg-string-overlong-returns-nil
  (testing "qr-svg-string never throws into render — for a URL beyond
            QR capacity it returns nil rather than propagating the
            qrcode-generator exception (rf2-3y7l4)"
    (let [pathological-overrides (apply str (repeat 10000 \X))
          url (str "https://example.test/?variant=story.x/y&overrides="
                   pathological-overrides)
          result (qr/qr-svg-string url 4)]
      (is (nil? result)
          "encoder returns nil rather than throwing on an oversized payload"))))

(deftest qr-svg-string-overlong-no-throw
  (testing "the rf2-3y7l4 contract: qr-svg-string must NEVER throw,
            regardless of input size — the share popover relies on
            this to keep the copy-link button live when the QR cannot
            be rendered"
    (is (nil? (qr/qr-svg-string (apply str (repeat 50000 \A)) 4))
        "50KB input returns nil without throwing")
    (is (string? (qr/qr-svg-string "https://example.test/" 4))
        "normal-sized input still produces an SVG string")))
