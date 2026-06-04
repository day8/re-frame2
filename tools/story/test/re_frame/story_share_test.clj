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

;; ---- rf2-j0hwf: overrides codec round-trip -------------------------------
;;
;; The earlier comma-joined `k:v` wire form could not round-trip an EDN
;; value containing the list separator — the decoder split the whole
;; payload on every comma before reading each value. The codec now prints
;; one EDN map (delimiter-safe) and reads it back as one map.

(defn- url-decode [t] (java.net.URLDecoder/decode (str t) "UTF-8"))

(defn- overrides-round-trip
  "Encode `ov` to the wire token, URL-decode it (as URLSearchParams.get
  would), and parse it back. Returns the reconstructed overrides map."
  [ov]
  (share/parse-overrides-param (url-decode (share/build-overrides-token ov))))

(deftest overrides-codec-round-trips-simple
  (testing "rf2-j0hwf — simple overrides round-trip through build/parse"
    (let [ov {:label "Click me" :count 5}]
      (is (= ov (overrides-round-trip ov))))))

(deftest overrides-codec-round-trips-comma-value
  (testing "rf2-j0hwf — a string override value containing the list
            separator (comma) round-trips faithfully instead of being
            shredded into malformed entries and dropped"
    (let [ov {:label "Save, continue"}]
      (is (= ov (overrides-round-trip ov))
          "comma-containing string value survives the round-trip"))
    (testing "comma value alongside other entries"
      (let [ov {:label "Save, continue" :count 3 :title "A, B, C"}]
        (is (= ov (overrides-round-trip ov)))))))

(deftest overrides-codec-round-trips-collection-values
  (testing "rf2-j0hwf — vector / map / set / nested EDN values (which all
            carry internal separators) round-trip"
    (let [ov {:items [1 2 3]
              :opts  {:a 1 :b 2}
              :tags  #{:x :y}
              :pair  [:k "v, with comma"]}]
      (is (= ov (overrides-round-trip ov))))))

(deftest overrides-codec-deterministic-order
  (testing "rf2-j0hwf — the encoded token is stable across calls (keys
            sorted) so the URL is canonical and idempotent pushes no-op"
    (let [ov {:zed 1 :alpha 2 :mid 3}]
      (is (= (share/build-overrides-token ov)
             (share/build-overrides-token ov))))))

(deftest overrides-codec-legacy-comma-pair-still-decodes
  (testing "rf2-j0hwf — already-shared / bookmarked URLs carrying the
            legacy comma-pair wire form still decode (back-compat)"
    (is (= {:label "Shared Label" :count 9}
           (share/parse-overrides-param "label:\"Shared Label\",count:9")))
    (is (= {:label "OK"}
           (share/parse-overrides-param "label:\"OK\",bogus"))
        "legacy malformed-entry drop preserved")))

(deftest overrides-codec-empty-and-nil
  (testing "rf2-j0hwf — empty/nil overrides produce no token, and blank
            input parses to nil"
    (is (nil? (share/build-overrides-token {})))
    (is (nil? (share/build-overrides-token nil)))
    (is (nil? (share/parse-overrides-param nil)))
    (is (nil? (share/parse-overrides-param "")))))

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

(deftest parse-overrides-param*-edn-map-form
  (testing "rf2-j0hwf — parse-overrides-param* reads the EDN-map wire form
            (one printed map) and reports keys it cannot coerce to a
            keyword as :dropped, so the share-import hint still surfaces
            on the new delimiter-safe encoding"
    (let [{:keys [overrides dropped]}
          (share/parse-overrides-param* "{:label \"OK\", :size 7}")]
      (is (= {:label "OK" :size 7} overrides))
      (is (= [] dropped) "clean EDN map drops nothing"))
    (let [{:keys [overrides dropped]}
          (share/parse-overrides-param* "{:label \"OK\", 5 :bad-key}")]
      (is (= {:label "OK"} overrides) "non-keyword key dropped, rest kept")
      (is (= 1 (count dropped))))
    (let [{:keys [overrides dropped]}
          (share/parse-overrides-param* "{:label \"unterminated")]
      (is (nil? overrides) "unreadable EDN payload yields no overrides")
      (is (= 1 (count dropped)) "whole token reported dropped"))))

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
      ;; rf2-j0hwf: overrides encode as one pr-str EDN map (delimiter-safe),
      ;; URL-encoded: {:label "Share Slice"} → %7B%3Alabel+%22Share+Slice%22%7D.
      (is (str/includes? url "overrides=%7B%3Alabel+%22Share+Slice%22%7D"))
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
;; rf2-20w5i (security audit) + rf2-ymnfx Issue B: the per-variant
;; Share button + QR popover were retired outright — the variant URL
;; is the browser's live address-bar URL (`url-state` pushState).
;; This ns must therefore expose neither the legacy third-party QR
;; endpoint Vars nor the vendored local encoder Vars from `share/`
;; (the encoder ns `re-frame.story.qr` itself is gone). The literal
;; api.qrserver.com must not appear in the share module — a future
;; regression that re-introduced a third-party QR fetch trips here.

(deftest no-third-party-qr-endpoint
  (testing "share namespace exposes no QR-endpoint Var. Pre-rf2-20w5i
            `qr-endpoint` / `qr-image-url` built URLs against
            api.qrserver.com; rf2-20w5i eliminated those; rf2-ymnfx
            Issue B then retired the QR popover entirely. The
            assertions remain as a regression gate."
    (is (nil? (resolve 'share/qr-endpoint)))
    (is (nil? (resolve 'share/qr-image-url)))))

(deftest no-qrserver-literal-in-share-source
  (testing "share.cljc carries no `api.qrserver.com` URL literal — the
            string must not appear in the source so a future regression
            (someone copy-pasting the old endpoint back in) is caught."
    (let [src (slurp (clojure.java.io/resource "re_frame/story/share.cljc"))]
      (is (not (str/includes? src "api.qrserver.com"))
          "share.cljc must not reference api.qrserver.com"))))
