(ns re-frame.hicasso.erasure-sentinels-cljs-test
  "THE LIVE HALF OF THE PRODUCTION-ERASURE PROOF.

  `implementation/hicasso/scripts/check_production_erasure.cjs` scans the
  `:advanced` / `goog.DEBUG=false` bundle and requires eight strings to be
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

  Eight sentinels and two of the scan's three positive controls. The third
  control — the release entry's own view name — is not asserted here
  because this namespace is not that entry; what it depends on is the
  MECHANISM, that `mint-view!` stamps `\"<ns>/<sym>\"` unconditionally, and
  that is asserted on the probe below.

  `defview` / `defhost` SOURCE COORDINATES are not on this roster. The
  scan's docstring says why in full: their sentinel would be the
  declaring file's name, and core's own production registration
  coordinates already put the only such name in the `:hicasso-release`
  bundle. That surface is witnessed by two gates of its own —
  `check_source_coord_elision.cjs` and
  `re-frame.hicasso.error-source-coord-elision-prod-test` — against the
  `:browser-test-prod-elision` bundle, where the sentinel discriminates."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [clojure.string :as str]
            [re-frame.core :as rf]
            [re-frame.hicasso :as rf.hicasso]
            [re-frame.hicasso.evidence :as rf.hicasso.evidence]
            [re-frame.hicasso.impl.codec :as rf.hicasso.impl.codec]
            [re-frame.hicasso.impl.collector :as rf.hicasso.impl.collector]
            [re-frame.hicasso.test :as rf.hicasso.test]))

(def sentinels
  "The eight strings the release-bundle scan requires to be ABSENT, spelled
  exactly as `check_production_erasure.cjs` spells them."
  {:body-slot        "hicassoBody"
   :views-slot       "hicassoViews"
   :view-attrs-slot  "hicassoViewAttrs"
   :source-coord     "data-rf2-source-coord"
   :view-id          "data-rf-view"
   :test-kit-refusal "rf.error/hicasso-test-"
   :evidence-schema  "re-frame.hicasso.evidence"
   :console-prefix   "[hicasso]"})

(def controls
  "The scan's positive controls that this namespace can produce itself."
  {:boundary-marker "hicassoBoundary"
   :shipped-refusal "rf.error/hicasso-empty-vector"})

(rf.hicasso/defview probe
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
           (rf.hicasso.impl.codec/retained-body probe))
        "and `retained-body` reads that same slot — the kit's only route
         from a minted head back to the body its author wrote")))

(deftest the-view-stamp-writes-the-slot-the-scan-names
  (testing "`hicassoViews` is the own property a dev build keeps on the read-set entry"
    (rf/make-frame {:id ::erasure-views})
    (try
      (rf.hicasso.impl.collector/render-body ::erasure-views (rf.hicasso.impl.codec/retained-body probe) {})
      (let [^js entry (rf.hicasso.impl.collector/last-reads)]
        (is (some? (unchecked-get entry (:views-slot sentinels)))
            "the render minted the slot under this exact property name — the
             named `subscribe` React is about to be handed lives there")
        (is (nil? (rf.hicasso.impl.collector/entry-views entry))
            "but a render alone names nothing: the name is counted at the commit")
        (let [release (rf.hicasso.impl.collector/commit-boundary! entry (fn []))]
          (is (= #{"re-frame.hicasso.erasure-sentinels-cljs-test/probe"}
                 (rf.hicasso.impl.collector/entry-views entry))
              "the commit counts the declared view's name, and `entry-views`
               reads it off that same slot — the tool tier's only route from
               an edge set back to a view name")
          (release)
          (is (nil? (rf.hicasso.impl.collector/entry-views entry))
              "and the cleanup uncounts it")))
      (finally
        (rf/destroy-frame! ::erasure-views)))))

(deftest the-spec-006-annotations-write-the-slot-and-the-keys-the-scan-names
  (testing "`hicassoViewAttrs` is the own property a dev-built body carries,
            and the two attribute names are the keys inside it"
    (let [body  (rf.hicasso.impl.codec/retained-body probe)
          attrs (unchecked-get body (:view-attrs-slot sentinels))]
      (is (some? attrs)
          "the mint writes Spec 006's attrs map under this exact property name")
      (is (= #{(:source-coord sentinels) (:view-id sentinels)}
             (into #{} (map name) (keys attrs)))
          "and the map's two keys ARE the two scanned literals — which is what
           makes their absence from a release bundle a statement about Spec
           006 §Production elision rather than about some other string")
      (testing "the values are the contract's formats, built by core's own
                cross-host formatters so they read identically to Reagent's"
        (is (= ":re-frame.hicasso.erasure-sentinels-cljs-test/probe"
               (:data-rf-view attrs))
            "`data-rf-view` is (str id), leading colon preserved")
        (is (str/starts-with?
              (:data-rf2-source-coord attrs)
              "re-frame.hicasso.erasure-sentinels-cljs-test:probe:")
            "`data-rf2-source-coord` is <ns>:<sym>:<line>:<col>")
        (is (re-find #":\d+:\d+$" (:data-rf2-source-coord attrs))
            "and the line/col segments are REAL — a `?:?` here would mean the
             declaration extent was closed before the mint read it")))))

(deftest the-test-kits-refusal-ids-carry-the-family-the-scan-names
  (testing "`rf.error/hicasso-test-` prefixes the ids the kit mints"
    (let [id (:rf.error/id (refusal #(rf.hicasso.test/tree [42 {}])))]
      (is (= :rf.error/hicasso-test-not-a-body id))
      (is (str/includes? (str id) (:test-kit-refusal sentinels))))))

(deftest the-evidence-schema-pin-is-the-slug-the-scan-names
  (testing "`re-frame.hicasso.evidence` is the projection's identity slug"
    (is (= :re-frame.hicasso.evidence/v3 rf.hicasso.evidence/schema))
    (is (= (:evidence-schema sentinels) (namespace rf.hicasso.evidence/schema))
        "every envelope carries it, so a projection in a consumer bundle
         cannot hide")))

(deftest the-console-diagnostics-print-the-prefix-the-scan-names
  (testing "`[hicasso]` opens the codec's dev console messages"
    (let [said     (atom [])
          original (.-warn js/console)
          row      (fn [_js-props] nil)]
      (unchecked-set row "displayName" "erasure/row")
      (rf.hicasso.impl.codec/mark-boundary! row)
      (try
        (set! (.-warn js/console) (fn [& args] (swap! said conj (str/join " " args))))
        (rf.hicasso.impl.codec/as-element [:ul (list [row {:key {:id 1}}])])
        (finally
          (set! (.-warn js/console) original)))
      (is (some #(str/starts-with? % (:console-prefix sentinels)) @said)
          "the prefix the scan requires the bundle not to carry — the
           entity-key warning is the member reached from here, and every
           member is written with it"))))

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
    (let [data (refusal #(rf.hicasso.impl.codec/vector-kind []))]
      (is (= :rf.error/hicasso-empty-vector (:rf.error/id data)))
      (is (str/includes? (str (:rf.error/id data)) (:shipped-refusal controls))))))
