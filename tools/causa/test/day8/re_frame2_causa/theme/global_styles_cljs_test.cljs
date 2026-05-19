(ns day8.re-frame2-causa.theme.global-styles-cljs-test
  "Tests for the Causa global-styles injection — fonts today; motion +
  reduced-motion seam + grain + display-face land in later cluster
  commits and add cases here.

  The injection paths are guarded against `js/document` being absent
  (node-test runs without a DOM). Under shadow-cljs `:node-test` the
  `exists? js/document` probe is `false` so `install!` is a no-op and
  every test here is a smoke probe over the *string* surface — the
  pure-data parts of the injection (`fonts-href`, `global-css`)."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [day8.re-frame2-causa.theme.global-styles :as gs]))

;; ---- font href ----------------------------------------------------------

(deftest fonts-href-loads-inter-and-jetbrains-mono
  (testing "the Google Fonts URL requests both brand faces"
    (let [href @#'gs/fonts-href]
      (is (string? href))
      (is (re-find #"family=Inter" href)
          "Inter is requested")
      (is (re-find #"family=JetBrains\+Mono" href)
          "JetBrains Mono is requested"))))

(deftest fonts-href-includes-all-spec-weights
  (testing "spec/007 §Typography lists 400/500/600/700 across both
            stacks (the variable WOFF2 weights). The Google Fonts URL
            requests every weight so none silently fall back to the
            nearest-installed weight."
    (let [href @#'gs/fonts-href]
      (doseq [w ["400" "500" "600" "700"]]
        (is (re-find (re-pattern w) href)
            (str "weight " w " requested"))))))

(deftest fonts-href-uses-display-swap
  (testing "`display=swap` keeps the fallback rendering immediately
            and swaps to the brand face when the WOFF2 lands (no FOIT,
            no perceived layout shift). Spec/007 §Typography is silent
            on the loading-display policy; `swap` is the lowest-UX-
            disruption default for an info-dense devtool."
    (let [href @#'gs/fonts-href]
      (is (re-find #"display=swap" href)))))

;; ---- install! idempotence ----------------------------------------------

(deftest install-bang-is-safe-without-document
  (testing "under node-test `js/document` is absent; install! must
            no-op rather than throw. The defonce guard is the surface
            for repeated calls — confirms install! returns nil for
            the caller-chained idiom."
    (is (nil? (gs/install!)))
    (is (nil? (gs/install!))
        "second call is also a no-op")))
