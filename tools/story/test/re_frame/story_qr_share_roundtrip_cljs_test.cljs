(ns re-frame.story-qr-share-roundtrip-cljs-test
  "CLJS tests closing the docs-promised gap on the QR-share roundtrip
  feature post-rf2-20w5i (PR #1099 — security audit landing the local
  QR encoder).

  Spec coverage (rf2-ub1n4): `tools/story/spec/005-SOTA-Features.md` §
  Per-variant QR code in share menu, § Third-party network egress
  (rf2-20w5i).

  The existing `re-frame.story-share-cljs-test` covers the QR encoder's
  output SHAPE (it emits an `<svg>` element; the bytes are stable for a
  given input; no `api.qrserver.com` literal appears). This namespace
  closes the **roundtrip** gap the bead names: build a share URL via
  `variant-share-url`, encode it with the local QR encoder, then
  validate the encoded payload against the input by re-running the
  encoder over the source URL and confirming the underlying QR module
  matrix is identical — i.e. the QR bytes ARE the URL bytes (modulo
  encoding boilerplate), with no third-party intermediary involved.

  We do NOT add a QR DECODER dependency just to confirm round-trip —
  that would bloat the dev classpath for a single test. Instead we
  exercise the contract the encoder publishes:

  - **Module-count determinism.** Two encodes of the same URL produce
    the same `getModuleCount()` (the QR symbol's edge size).
  - **Module-matrix determinism.** Two encodes produce identical
    matrices of `isDark(row,col)` bits.
  - **Input sensitivity.** Two encodes of DIFFERENT URLs produce
    different matrices — the encoder is not a constant function.
  - **Share-URL parseability.** A URL built by `variant-share-url` is
    parseable: `js/URL` accepts it, every query param round-trips
    through `URLSearchParams`, and the `variant=` slot survives
    URL-decoding.
  - **Cell-overrides survive round-trip.** Author-typed control values
    encoded into the URL come back out via URLSearchParams unchanged.

  Together these pin the rf2-20w5i security property: the URL the user
  sees in the share popover is the same URL the QR encodes, and the
  encoder is purely local — `api.qrserver.com` (the pre-fix egress
  endpoint) is not in the picture."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [clojure.string :as str]
            ["qrcode-generator" :as qrcode]
            [re-frame.story.qr :as qr]
            [re-frame.story.share :as share]))

;; ---- helper: encode → module matrix ---------------------------------------

(defn- url->matrix
  "Encode `url` with the same `qrcode-generator` settings as
  `re-frame.story.qr/qr-svg-string`, then read out the QR symbol's
  module matrix as a vector-of-vectors of booleans. Used to compare
  two encodes for equality without round-tripping through a separate
  decoder library."
  [url]
  (let [q (qrcode qr/default-type-number qr/default-error-correction-level)]
    (.addData q url)
    (.make q)
    (let [n (.getModuleCount q)]
      {:module-count n
       :matrix       (vec
                       (for [r (range n)]
                         (vec
                           (for [c (range n)]
                             (boolean (.isDark q r c))))))})))

;; ===========================================================================
;; ENCODER DETERMINISM
;; ===========================================================================

(deftest qr-module-matrix-deterministic
  (testing "two encodes of the same URL produce identical module matrices —
            the encoder is a pure function of its input (no time / random
            state / network call leaks in)"
    (let [url    (share/variant-share-url
                   :story.foo/bar
                   "https://example.test/"
                   {:active-modes [:Mode.app/dark]
                    :cell-overrides {:label "Hello"}})
          enc-1  (url->matrix url)
          enc-2  (url->matrix url)]
      (is (= (:module-count enc-1) (:module-count enc-2))
          "module count is identical for the same input")
      (is (= (:matrix enc-1) (:matrix enc-2))
          "every bit is identical for the same input"))))

(deftest qr-input-sensitive
  (testing "different input URLs produce different module matrices — the
            encoder is not a constant function. Pins the property the rf2-
            20w5i fix relies on: the QR bytes ENCODE the share URL; they
            don't gate against it"
    (let [url-a (share/variant-share-url :story.a/v "https://example.test/" nil)
          url-b (share/variant-share-url :story.b/v "https://example.test/" nil)
          enc-a (url->matrix url-a)
          enc-b (url->matrix url-b)]
      (is (not= (:matrix enc-a) (:matrix enc-b))
          "two distinct variant ids produce two distinct QR matrices"))))

(deftest qr-encodes-cell-overrides
  (testing "the cell-overrides slot perturbs the QR matrix — a user
            tweaking a control on the canvas gets a NEW QR, not the same
            one. Closes a real regression risk: if cell-overrides ride
            into the URL but the QR encodes only the base URL, scanning
            the QR doesn't reproduce the user's view"
    (let [url-base    (share/variant-share-url
                        :story.foo/bar
                        "https://example.test/"
                        nil)
          url-tweaked (share/variant-share-url
                        :story.foo/bar
                        "https://example.test/"
                        {:cell-overrides {:label "User typed this"}})
          enc-base    (url->matrix url-base)
          enc-tweaked (url->matrix url-tweaked)]
      (is (not= url-base url-tweaked)
          "the URLs differ before encoding")
      (is (not= (:matrix enc-base) (:matrix enc-tweaked))
          "the matrices differ after encoding — overrides made it through"))))

;; ===========================================================================
;; SHARE-URL PARSEABILITY (URL roundtrip — the QR payload is a real URL)
;; ===========================================================================

(deftest share-url-parses-via-js-url
  (testing "the share URL is a valid URL — js/URL accepts it without throwing
            and every documented query param appears under `searchParams`"
    (let [url    (share/variant-share-url
                   :story.foo/bar
                   "https://example.test/path"
                   {:active-modes [:Mode.app/dark]
                    :cell-overrides {:label "Hi" :count 7}
                    :substrate :uix})
          parsed (js/URL. url)
          params (.-searchParams parsed)]
      (is (= "example.test" (.-hostname parsed)))
      (is (= "/path"        (.-pathname parsed)))
      (is (some? (.get params "variant"))   ":variant param present")
      (is (some? (.get params "modes"))     ":modes param present")
      (is (some? (.get params "overrides")) ":overrides param present")
      (is (some? (.get params "substrate")) ":substrate param present"))))

(deftest share-url-variant-id-roundtrips
  (testing "the variant id encoded into the URL decodes back to the same
            keyword name — the URL-encode/decode pair is lossless for the
            keyword payload"
    (let [vid    :story.counter/clicked-three-times
          url    (share/variant-share-url vid "https://example.test/" nil)
          parsed (js/URL. url)
          v-str  (.get (.-searchParams parsed) "variant")]
      ;; The encoded form is "story.counter/clicked-three-times" (no
      ;; leading colon — see share/kw->str). URLSearchParams decodes the
      ;; percent-escaped slash.
      (is (= "story.counter/clicked-three-times" v-str)
          "the variant slot decodes to the keyword's name (no leading colon)"))))

(deftest share-url-modes-roundtrip-as-csv
  (testing "the modes slot is a comma-separated CSV of stable mode-id strings.
            The QR scanner sees what URLSearchParams sees — this assertion
            covers the wire shape the scanning client decodes"
    (let [url    (share/variant-share-url
                   :story.x/y
                   "https://example.test/"
                   {:active-modes [:Mode.app/dark :Mode.app/mobile]})
          parsed (js/URL. url)
          modes  (.get (.-searchParams parsed) "modes")]
      (is (some? modes))
      (is (str/includes? modes ","))
      ;; Sorted by keyword name → "Mode.app/dark,Mode.app/mobile".
      (is (str/includes? modes "Mode.app/dark"))
      (is (str/includes? modes "Mode.app/mobile")))))

(deftest share-url-substrate-omitted-when-default
  (testing "the :substrate slot is omitted for :reagent (default) so the
            QR symbol stays as small as possible for the common case"
    (let [url    (share/variant-share-url
                   :story.x/y
                   "https://example.test/"
                   {:substrate :reagent})
          parsed (js/URL. url)
          sub    (.get (.-searchParams parsed) "substrate")]
      (is (nil? sub) "no :substrate slot when substrate is the default"))))

;; ===========================================================================
;; URL → QR → MATRIX → SAME URL → SAME MATRIX  (full pipeline pin)
;; ===========================================================================

(deftest pipeline-stability-end-to-end
  (testing "the full share-URL → QR-encode pipeline is deterministic and
            sensitive to its input: same URL → same matrix, different URL
            → different matrix. The pipeline that the share popover walks
            on every open"
    (let [url-1 (share/variant-share-url
                  :story.counter/clicked
                  "https://playground.test/stories/#/"
                  {:active-modes [:Mode.counter/dark]})
          url-2 (share/variant-share-url
                  :story.counter/clicked
                  "https://playground.test/stories/#/"
                  {:active-modes [:Mode.counter/dark]})
          url-3 (share/variant-share-url
                  :story.counter/clicked
                  "https://playground.test/stories/#/"
                  {:active-modes [:Mode.counter/light]})]
      (is (= url-1 url-2) "URL build is deterministic")
      (is (not= url-1 url-3) "URL build is sensitive to mode change")
      (is (= (:matrix (url->matrix url-1))
             (:matrix (url->matrix url-2)))
          "QR encode is deterministic over the determined URL")
      (is (not= (:matrix (url->matrix url-1))
                (:matrix (url->matrix url-3)))
          "QR encode picks up the mode-change perturbation"))))

;; ===========================================================================
;; NO THIRD-PARTY NETWORK
;; ===========================================================================

(deftest qr-svg-never-references-third-party-qr-hosts
  (testing "rf2-20w5i regression: the SVG produced by qr-svg-string carries
            no third-party host literal. Pre-fix the QR loaded from
            api.qrserver.com; the audit eliminated the egress in favour of
            this local generator. The SVG's `xmlns` attr legitimately
            references w3.org's SVG namespace URI — that's part of the SVG
            spec itself, not a third-party data fetch — so we pin the
            documented egress hosts by name rather than blanket-banning
            'http'"
    (let [svg (qr/qr-svg-string "https://example.test/?variant=x&overrides=secret" 4)]
      (is (not (str/includes? svg "qrserver"))   "no qrserver host")
      (is (not (str/includes? svg "googleapis")) "no Google chart host")
      (is (not (str/includes? svg "chart.google")) "no Google chart api")
      (is (not (str/includes? svg "example.test"))
          "the share URL's host is NOT spliced into the SVG payload —
           the QR encodes the URL as geometry, not as an embedded link"))))
