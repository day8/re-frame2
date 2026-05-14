(ns re-frame.story-a11y-source-test
  "JVM-side contract test for rf2-20w5i: the a11y panel's axe-core
  load must be gated behind an explicit dev opt-in. Pre-fix the
  panel unconditionally injected
  `https://cdn.jsdelivr.net/npm/axe-core@4.10.0/axe.min.js` on first
  open; post-fix the load only fires after the dev has clicked
  'enable axe-core + scan' (persisted in `localStorage` under
  `:rf.story.a11y/cdn-opt-in`), and the injected `<script>` carries
  SRI `integrity` + `crossorigin=\"anonymous\"` for tamper-detection.

  The audit's preferred fix (static `:require [\"axe-core\" ...]`)
  is blocked by a Closure :advanced parser issue with axe-core's UMD
  wrapper. The gate-behind-flag fallback the audit allows lands here;
  the High-severity finding (share-URL → api.qrserver.com leak) is
  fixed separately via local QR generation.

  These assertions are textual because CLJS doesn't expose source
  bytes at runtime. The .cljs file ships in this artefact's `src/`
  tree on the resource path, so `clojure.java.io/resource` resolves
  it without parsing CLJS forms."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [clojure.java.io :as io]))

(defn- a11y-source []
  (slurp (io/resource "re_frame/story/ui/a11y.cljs")))

(deftest cdn-load-is-gated-by-opt-in
  (testing "the loader reads `cdn-opt-in?` before injecting a
            `<script>`. Pre-fix the load ran unconditionally on first
            panel open; post-fix it short-circuits to a `:no-consent`
            state unless the dev has explicitly approved."
    (let [src (a11y-source)]
      (is (str/includes? src "cdn-opt-in?")
          "a11y.cljs must reference the opt-in predicate")
      (is (str/includes? src "set-cdn-opt-in!")
          "a11y.cljs must expose the opt-in setter so the consent
           prompt can persist the dev's approval"))))

(deftest cdn-script-carries-sri-integrity
  (testing "the injected `<script>` element carries a Subresource
            Integrity (SRI) hash + `crossorigin` so a compromised CDN
            mirror serving altered JS fails closed at the browser.
            Pre-fix the script tag had no integrity attribute."
    (let [src (a11y-source)]
      (is (re-find #"axe-cdn-integrity" src)
          "a11y.cljs must bind an `axe-cdn-integrity` constant")
      (is (re-find #"\"sha384-" src)
          "the SRI value must be present as a sha384 literal")
      (is (re-find #"\"integrity\"" src)
          "the loader must set `integrity` on the script tag")
      (is (re-find #"\"crossorigin\"" src)
          "the loader must set `crossorigin` on the script tag —
           required for SRI to apply to cross-origin requests"))))

(deftest cdn-url-is-version-pinned
  (testing "the axe-core URL is pinned to a specific version, not a
            floating tag. Pre-fix the URL pointed at
            `axe-core@4.10.0/axe.min.js`; post-fix the same pin is
            preserved (and SRI prevents tag-rewriting attacks)."
    (let [src (a11y-source)]
      (is (re-find #"axe-core@4\.\d+\.\d+" src)
          "axe-core URL must include an explicit X.Y.Z version pin"))))

(deftest no-consent-state-surfaced
  (testing "the panel surfaces a distinct `:no-consent` run-state so
            the consent prompt can render instead of silently
            proceeding to the load. The state value is the canonical
            handle the UI dispatches on."
    (let [src (a11y-source)]
      (is (str/includes? src ":no-consent")
          "a11y.cljs must surface the :no-consent run-state"))))

(deftest consent-prompt-text-mentions-cdn
  (testing "the consent prompt UI text mentions the CDN domain in
            plain words so the dev understands what the egress means.
            This is the user-facing trust signal — without it the
            opt-in is a rubber-stamp."
    (let [src (a11y-source)]
      (is (str/includes? src "cdn.jsdelivr.net")
          "the consent prompt must name the CDN domain")
      (is (str/includes? src "axe-core")
          "the consent prompt must name the library being loaded"))))
