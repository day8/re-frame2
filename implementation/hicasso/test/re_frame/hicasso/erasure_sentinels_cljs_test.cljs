(ns re-frame.hicasso.erasure-sentinels-cljs-test
  "THE LIVE HALF OF THE PRODUCTION-ERASURE PROOF (rf2-hic-024).

  `implementation/hicasso/scripts/check_production_erasure.cjs` scans the
  `:advanced` / `goog.DEBUG=false` bundle and requires five strings to be
  ABSENT. An absence is only evidence about erasure if the string is
  something a live surface really emits — a sentinel that quietly stopped
  being produced is absent from every bundle for a reason that has nothing
  to do with the law being proved, and the scan would report green
  forever.

  So this namespace is the other half of the A/B, and it runs in the
  ordinary `goog.DEBUG` node lane: **surface on, string present**. The
  scan's claim is *surface off, string gone*. Neither is evidence alone,
  and neither can rot silently while the other holds — rename a refusal
  id and the assertion below goes red; rename it in the roster and the
  scan's own premise check goes red.

  The strings are written out here as literals rather than read from the
  surface that emits them. That is deliberate and it is the whole point:
  a test that asked the code what it emits and then asserted it emits
  that would pass through any rename, which is exactly the drift the
  roster has to notice.

  ## What is asserted, and what is not

  Five sentinels and two of the scan's three positive controls. The third
  control — the release entry's own view name — is not asserted here
  because this namespace is not that entry; what it depends on is the
  MECHANISM, that `mint-view!` stamps `\"<ns>/<sym>\"` unconditionally, and
  that is asserted on the probe below.

  `defview` / `defhost` SOURCE COORDINATES are not on this roster. The
  scan's docstring says why in full: their sentinel would be the
  declaring file's name, and core's own production registration
  coordinates already put the only such name in the `:hicasso-release`
  bundle. That surface is witnessed by rf2-hic-007's own two gates —
  `check_source_coord_elision.cjs` and
  `re-frame.hicasso.error-source-coord-elision-prod-test` — against the
  `:browser-test-prod-elision` bundle, where the sentinel discriminates."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [clojure.string :as str]
            [re-frame.hicasso :as h]
            [re-frame.hicasso.evidence :as evidence]
            [re-frame.hicasso.impl.codec :as codec]
            [re-frame.hicasso.impl.error :as error]
            [re-frame.hicasso.test :as ht]))

(def sentinels
  "The five strings the release-bundle scan requires to be ABSENT, spelled
  exactly as `check_production_erasure.cjs` spells them."
  {:body-slot        "hicassoBody"
   :test-kit-refusal "rf.error/hicasso-test-"
   :complaint-guard  "hicasso-refusal-incomplete"
   :evidence-schema  "re-frame.hicasso.evidence"
   :key-warning      "A boundary body began lowering while"})

(def controls
  "The scan's positive controls that this namespace can produce itself."
  {:boundary-marker "hicassoBoundary"
   :shipped-refusal "rf.error/hicasso-empty-vector"})

(h/defview probe
  "A declared boundary, minted at namespace load, for its head alone."
  [_]
  [:p "the erasure roster's probe"])

(defn- refusal
  "The ex-data `thunk` refuses with, or nil."
  [thunk]
  (try (thunk) nil (catch :default e (ex-data e))))

;; ---------------------------------------------------------------------------
;; The sentinels
;; ---------------------------------------------------------------------------

(deftest the-test-kits-body-retention-door-writes-the-slot-the-scan-names
  (testing "`hicassoBody` is the own property a dev-built head carries"
    (is (some? (unchecked-get probe (:body-slot sentinels)))
        "the mint writes the body under this exact property name")
    (is (= (unchecked-get probe (:body-slot sentinels))
           (codec/retained-body probe))
        "and `retained-body` reads that same slot — the kit's only route
         from a minted head back to the body its author wrote")))

(deftest the-test-kits-refusal-ids-carry-the-family-the-scan-names
  (testing "`rf.error/hicasso-test-` prefixes the ids the kit mints"
    (let [id (:rf.error/id (refusal #(ht/tree [42 {}])))]
      (is (= :rf.error/hicasso-test-not-a-body id))
      (is (str/includes? (str id) (:test-kit-refusal sentinels))))))

(deftest the-completeness-guard-mints-the-id-the-scan-names
  (testing "`hicasso-refusal-incomplete` is what `fail!`'s dev guard raises"
    (let [data (refusal #(error/fail! :rf.error/erasure-probe
                                      're-frame.hicasso.erasure-sentinels-cljs-test/probe
                                      "A refusal with no recovery."
                                      nil
                                      {}))]
      (is (= :rf.error/hicasso-refusal-incomplete (:rf.error/id data)))
      (is (str/includes? (str (:rf.error/id data)) (:complaint-guard sentinels))))))

(deftest the-evidence-schema-pin-is-the-slug-the-scan-names
  (testing "`re-frame.hicasso.evidence` is the projection's identity slug"
    (is (= :re-frame.hicasso.evidence/v2 evidence/schema))
    (is (= (:evidence-schema sentinels) (namespace evidence/schema))
        "every envelope carries it, so a projection in a consumer bundle
         cannot hide")))

(deftest the-key-warning-prints-the-sentence-the-scan-names
  (testing "the codec's unbalanced set/clear warning carries its text"
    (let [said     (atom [])
          original (.-warn js/console)]
      (try
        (set! (.-warn js/console) (fn [& args] (swap! said conj (str/join " " args))))
        (codec/set-lowering-owner! "erasure/outer")
        (codec/set-lowering-owner! "erasure/inner")
        (finally
          (set! (.-warn js/console) original)
          (codec/set-lowering-owner! nil)))
      (is (some #(str/includes? % (:key-warning sentinels)) @said)
          "the message string the scan requires the bundle not to carry"))))

;; ---------------------------------------------------------------------------
;; The positive controls
;; ---------------------------------------------------------------------------

(deftest the-boundary-marker-is-the-ungated-sibling-of-the-body-slot
  (testing "`hicassoBoundary` is written on the same head, without a gate"
    (is (true? (unchecked-get probe (:boundary-marker controls)))))
  (testing "and the mint stamps the view name unconditionally"
    (is (= "re-frame.hicasso.erasure-sentinels-cljs-test/probe"
           (.-displayName probe))
        "the mechanism the scan's third control — the release entry's own
         view name — depends on")))

(deftest a-shipped-refusal-id-is-minted-on-the-path-every-build-keeps
  (testing "`rf.error/hicasso-empty-vector` comes out of `fail!` ungated"
    (let [data (refusal #(codec/vector-kind []))]
      (is (= :rf.error/hicasso-empty-vector (:rf.error/id data)))
      (is (str/includes? (str (:rf.error/id data)) (:shipped-refusal controls))))))
