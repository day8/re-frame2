(ns re-frame.story-a11y-source-test
  "JVM-side contract test: the a11y panel's axe-core load must be
  gated behind an explicit dev opt-in. The panel injects
  `https://cdn.jsdelivr.net/npm/axe-core@4.10.0/axe.min.js` only after
  the dev has clicked 'enable axe-core + scan' (persisted in
  `localStorage` under `:rf.story.a11y/cdn-opt-in`), and the injected
  `<script>` carries SRI `integrity` + `crossorigin=\"anonymous\"` for
  tamper-detection.

  The load is gated behind a flag rather than bundled with a static
  `:require [\"axe-core\" ...]`, because a Closure :advanced parser
  issue with axe-core's UMD wrapper blocks the static require.

  The opt-in gate itself is behaviour, asserted on the node lane by
  `re-frame.story-a11y-cljs-test` (`cdn-opt-in-roundtrips`,
  `run-axe-surfaces-no-consent-without-opt-in`). What stays here is
  what only the source text can show — the SRI and `crossorigin`
  attributes and the version pin — and these assertions are textual
  because CLJS doesn't expose source bytes at runtime. The .cljs file ships in this artefact's `src/`
  tree on the resource path, so `clojure.java.io/resource` resolves
  it without parsing CLJS forms."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.java.io :as io]))

(defn- a11y-source []
  (slurp (io/resource "re_frame/story/ui/a11y.cljs")))

(deftest cdn-script-carries-sri-integrity
  (testing "the injected `<script>` element carries a Subresource
            Integrity (SRI) hash + `crossorigin` so a compromised CDN
            mirror serving altered JS fails closed at the browser."
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
            floating tag (`axe-core@4.10.0/axe.min.js`), and SRI
            prevents tag-rewriting attacks."
    (let [src (a11y-source)]
      (is (re-find #"axe-core@4\.\d+\.\d+" src)
          "axe-core URL must include an explicit X.Y.Z version pin"))))
