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

(deftest variant-share-url-inserts-query-before-hash-route
  (testing "hash-routed Story links keep query params in location.search"
    (let [url (share/variant-share-url
                :story.foo/bar
                "https://example.test/counter-with-stories/#/stories"
                {:active-modes [:Mode.app/dark]})]
      (is (str/starts-with?
            url
            "https://example.test/counter-with-stories/?"))
      (is (str/includes? url "#/stories"))
      (is (re-find #"variant=" url))
      (is (re-find #"modes=" url)))))

(deftest parse-share-url-params
  (testing "share URL parser reconstructs variant, modes, substrate, and overrides"
    (is (= :story.counter/loaded
           (share/parse-keyword-token "story.counter/loaded")))
    (is (= [:Mode.app/dark :Mode.app/mobile]
           (share/parse-modes-param "Mode.app/dark,Mode.app/mobile")))
    (is (= :uix (share/parse-substrate-param "uix")))
    (is (= {:label "Shared Label" :count 9}
           (share/parse-overrides-param "label:\"Shared Label\",count:9")))))

;; ---- rf2-9jthx: parse-overrides-param* surfaces dropped entries ----------

(deftest parse-overrides-param*-clean-input
  (testing "rf2-9jthx — parse-overrides-param* returns the same :overrides
            map as parse-overrides-param plus an empty :dropped vec for
            clean input"
    (let [{:keys [overrides dropped]}
          (share/parse-overrides-param* "label:\"Hi\",count:9")]
      (is (= {:label "Hi" :count 9} overrides))
      (is (= [] dropped) "no entries dropped for a clean input"))))

(deftest parse-overrides-param*-mixed-input
  (testing "rf2-9jthx — parse-overrides-param* reports the SET of dropped
            entries alongside the surviving overrides — the share-import
            hint reads :dropped to count + name what failed.

            'bogus' has no separator → no key/value split → dropped.
            'count:[unclosed' has an unparseable EDN value → dropped.
            'label:\"OK\"' and 'size:7' parse cleanly → kept."
    (let [{:keys [overrides dropped]}
          (share/parse-overrides-param*
            "label:\"OK\",bogus,count:[unclosed,size:7")]
      (is (= {:label "OK" :size 7} overrides)
          "well-formed entries survive")
      (is (= 2 (count dropped))
          "two malformed entries — 'bogus' (no separator) and
           'count:[unclosed' (bad EDN)")
      (is (some #(= "bogus" %) dropped))
      (is (some #(= "count:[unclosed" %) dropped)))))

(deftest parse-overrides-param*-all-dropped
  (testing "rf2-9jthx — when every entry is malformed :overrides is nil and
            :dropped names them all"
    (let [{:keys [overrides dropped]}
          (share/parse-overrides-param* "bogus,also-bogus")]
      (is (nil? overrides))
      (is (= 2 (count dropped))))))

(deftest parse-overrides-param*-blank-input
  (testing "rf2-9jthx — blank/nil input returns the empty-shape map so
            callers don't have to nil-check before destructuring"
    (is (= {:overrides nil :dropped []}
           (share/parse-overrides-param* nil)))
    (is (= {:overrides nil :dropped []}
           (share/parse-overrides-param* "")))
    (is (= {:overrides nil :dropped []}
           (share/parse-overrides-param* "   ")))))

(deftest parse-overrides-param*-back-compat
  (testing "rf2-9jthx — parse-overrides-param (legacy silent-drop) keeps
            its signature. The share UI hydrator switches to
            parse-overrides-param* so the dropped count surfaces; other
            call sites that don't care about it keep working"
    (is (= {:label "OK"}
           (share/parse-overrides-param "label:\"OK\",bogus")))))

(deftest variant-share-url-preserves-hash-route
  (testing "variant-share-url inserts params before # so the Story route survives"
    (let [url (share/variant-share-url
                :story.counter/loaded
                "https://example.test/counter-with-stories/#/stories"
                {:active-modes   [:Mode.app/dark]
                 :cell-overrides {:label "Share Slice"}
                 :substrate      :reagent})]
      (is (str/starts-with?
            url
            "https://example.test/counter-with-stories/?variant=story.counter%2Floaded"))
      (is (str/includes? url "&modes=Mode.app%2Fdark"))
      (is (str/includes? url "overrides=label%3A%22Share+Slice%22"))
      (is (str/ends-with? url "#/stories")))))

(deftest variant-share-url-public-export
  (testing "story/variant-share-url is exported"
    (let [url (story/variant-share-url
                :story.foo/bar
                "https://x.test/"
                {:active-modes [:Mode.x/y]})]
      (is (str/starts-with? url "https://x.test/?"))
      (is (re-find #"variant=" url))
      (is (re-find #"modes=" url)))))

;; ---- No QR endpoint --------------------------------------------------------
;;
;; Per rf2-20w5i (security audit): the QR is rendered locally via the
;; vendored `qrcode-generator` npm package (see `re-frame.story.qr`,
;; CLJS-only). Pre-fix this ns exposed `qr-image-url` + `qr-endpoint`
;; which built a URL pointing at api.qrserver.com; both have been
;; removed. The contract test below pins the removal: no Var, no
;; `https://api.qrserver.com` literal anywhere in the share module.

(deftest no-third-party-qr-endpoint
  (testing "share namespace no longer exposes a remote-QR endpoint Var
            (pre-fix `qr-endpoint` pointed at api.qrserver.com; the
            audit eliminated it in favour of local SVG generation)"
    (is (nil? (resolve 'share/qr-endpoint)))
    (is (nil? (resolve 'share/qr-image-url)))))

(deftest no-qrserver-literal-in-share-source
  (testing "share.cljc carries no `api.qrserver.com` URL literal — the
            string must not appear in the source so a future regression
            (someone copy-pasting the old endpoint back in) is caught."
    (let [src (slurp (clojure.java.io/resource "re_frame/story/share.cljc"))]
      (is (not (str/includes? src "api.qrserver.com"))
          "share.cljc must not reference api.qrserver.com"))))
