(ns re-frame.story-share-url-state-test
  "JVM tests for the URL-state slot extensions (rf2-o4u18).

  Pairs with `re-frame.story-share-test` (variant + modes + overrides +
  substrate). This ns pins the new sharability slots — workspace,
  mode-tab, viewport, background, tag-filter — and the
  `share/parse-params` round-trip.

  Pure CLJC — every encoder + parser lives in `re-frame.story.share`,
  no CLJS deps."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [re-frame.story.share :as share]))

;; ---- workspace -----------------------------------------------------------

(deftest build-params-workspace
  (testing "workspace id encodes as workspace=<ns>/<name>"
    (let [ps (share/build-params {:workspace-id :story.foo/grid})
          wp (some #(when (str/starts-with? % "workspace=") %) ps)]
      (is (some? wp))
      (is (re-find #"story.foo" wp))
      (is (re-find #"grid" wp)))))

(deftest parse-workspace-param-round-trip
  (testing "parse-workspace-param round-trips a kw->str token"
    (is (= :story.foo/grid
           (share/parse-workspace-param "story.foo/grid")))
    (is (nil? (share/parse-workspace-param "")))
    (is (nil? (share/parse-workspace-param nil)))))

;; ---- mode-tab ------------------------------------------------------------

(deftest build-params-mode-tab-non-default
  (testing "mode-tab encodes when non-default (anything other than :dev)"
    (let [ps (share/build-params {:variant-id :story.foo/bar
                                  :mode-tab   :docs})]
      (is (some #(str/starts-with? % "mode-tab=docs") ps)))))

(deftest build-params-mode-tab-default-omitted
  (testing ":dev (the default) is omitted so the canonical URL stays minimal"
    (let [ps (share/build-params {:variant-id :story.foo/bar
                                  :mode-tab   :dev})]
      (is (not (some #(str/starts-with? % "mode-tab=") ps))))))

(deftest build-params-mode-tab-unknown-dropped
  (testing "unknown mode-tab values are dropped (a stale URL degrades silently)"
    (let [ps (share/build-params {:variant-id :story.foo/bar
                                  :mode-tab   :bogus})]
      (is (not (some #(str/starts-with? % "mode-tab=") ps))))))

(deftest parse-mode-tab-param
  (testing "parse-mode-tab-param recognises :dev/:docs/:test, drops anything else"
    (is (= :dev  (share/parse-mode-tab-param "dev")))
    (is (= :docs (share/parse-mode-tab-param "docs")))
    (is (= :test (share/parse-mode-tab-param "test")))
    (is (nil?    (share/parse-mode-tab-param "bogus")))
    (is (nil?    (share/parse-mode-tab-param "")))
    (is (nil?    (share/parse-mode-tab-param nil)))))

;; ---- viewport ------------------------------------------------------------

(deftest build-params-viewport-preset
  (testing "viewport preset keyword encodes as viewport=<id>"
    (let [ps (share/build-params {:viewport :tablet})]
      (is (some #(= "viewport=tablet" %) ps)))))

(deftest build-params-viewport-custom
  (testing "viewport custom map encodes as viewport=WxH"
    (let [ps (share/build-params {:viewport {:width 800 :height 600}})]
      (is (some #(= "viewport=800x600" %) ps)))))

(deftest build-params-viewport-omitted-when-nil
  (testing "nil viewport is omitted from the canonical URL"
    (let [ps (share/build-params {:viewport nil})]
      (is (not (some #(str/starts-with? % "viewport=") ps))))))

(deftest parse-viewport-param-preset
  (is (= :tablet (share/parse-viewport-param "tablet"))))

(deftest parse-viewport-param-custom
  (testing "custom WxH parses into {:width :height}"
    (is (= {:width 800 :height 600} (share/parse-viewport-param "800x600")))
    (is (= {:width 1024 :height 768} (share/parse-viewport-param "1024x768")))))

(deftest parse-viewport-param-malformed
  (testing "malformed values degrade — empty / nil → nil; zero dim → nil;
            unknown keywords are returned as-is (the hydrator validates
            against the preset table via apply-parsed-to-state)"
    (is (nil? (share/parse-viewport-param "0x600")) "zero dim → nil")
    (is (nil? (share/parse-viewport-param "")))
    (is (nil? (share/parse-viewport-param nil)))
    ;; Bare tokens parse to keywords here; the hydrator's :viewport?
    ;; validator drops anything that isn't a registered preset / valid custom.
    (is (= :x (share/parse-viewport-param "x")))))

;; ---- background ----------------------------------------------------------

(deftest build-params-background-preset
  (testing "background preset keyword encodes as background=<id>"
    (let [ps (share/build-params {:background :dark})]
      (is (some #(= "background=dark" %) ps)))))

(deftest build-params-background-hex
  (testing "hex colour string encodes as background=%23rrggbb"
    (let [ps (share/build-params {:background "#abc123"})]
      (is (some #(= "background=%23abc123" %) ps)
          "# is percent-encoded to %23 so it doesn't collide with hash routes"))))

(deftest parse-background-param-preset
  (is (= :dark (share/parse-background-param "dark"))))

(deftest parse-background-param-hex
  (testing "hex colour parses back to itself"
    (is (= "#abc123" (share/parse-background-param "#abc123")))
    (is (= "#ABC" (share/parse-background-param "#ABC")))
    (is (= "#aabbccdd" (share/parse-background-param "#aabbccdd")))))

(deftest parse-background-param-malformed
  (testing "malformed values degrade to nil"
    (is (nil? (share/parse-background-param "")))
    (is (nil? (share/parse-background-param nil)))))

;; ---- tag-filter ----------------------------------------------------------

(deftest build-params-tag-filter
  (testing "tag-filter set encodes as a sorted comma-separated list"
    (let [ps (share/build-params {:tag-filter #{:tag/b :tag/a}})
          tf (some #(when (str/starts-with? % "tag-filter=") %) ps)]
      (is (some? tf))
      ;; The wire form percent-encodes `/` to %2F and `,` to %2C.
      (is (re-find #"tag%2Fa" tf))
      (is (re-find #"tag%2Fb" tf))
      (is (< (str/index-of tf "tag%2Fa")
             (str/index-of tf "tag%2Fb"))
          "sorted alphabetically — :a appears before :b"))))

(deftest parse-tag-filter-param
  (testing "tag-filter parses into a set"
    (is (= #{:tag/a :tag/b}
           (share/parse-tag-filter-param "tag/a,tag/b")))
    (is (nil? (share/parse-tag-filter-param "")))
    (is (nil? (share/parse-tag-filter-param nil)))))

;; ---- parse-params round-trip --------------------------------------------

(deftest parse-params-full-round-trip
  (testing "rf2-o4u18 — full encode → URLSearchParams-shaped getter → parse
            round-trips every slot"
    (let [in    {:variant-id     :story.counter/loaded
                 :workspace-id   nil
                 :mode-tab       :docs
                 :active-modes   [:Mode.app/dark :Mode.app/mobile]
                 :viewport       :tablet
                 :background     "#abc123"
                 :tag-filter     #{:tag/a :tag/b}
                 :cell-overrides {:label "Hi"}
                 :substrate      :uix}
          ps    (share/build-params in)
          ;; Simulate URLSearchParams by parsing the param vector back.
          getter (into {}
                       (for [kv ps
                             :let [[k v] (str/split kv #"=" 2)]]
                         [k (java.net.URLDecoder/decode v "UTF-8")]))
          out   (share/parse-params getter)]
      (is (= :story.counter/loaded (:variant-id   out)))
      (is (= :docs                 (:mode-tab     out)))
      (is (= [:Mode.app/dark :Mode.app/mobile]
                                   (:active-modes out)))
      (is (= :tablet               (:viewport     out)))
      (is (= "#abc123"             (:background   out)))
      (is (= #{:tag/a :tag/b}      (:tag-filter   out)))
      (is (= {:label "Hi"}         (:cell-overrides out)))
      (is (= :uix                  (:substrate    out))))))

(deftest parse-params-handles-missing-keys
  (testing "parse-params returns nil for absent slots — caller decides defaults"
    (let [out (share/parse-params {})]
      (is (every? nil? (vals out))))))

(deftest parse-params-with-workspace
  (testing "workspace round-trips when present"
    (let [in    {:workspace-id :story.foo/grid}
          ps    (share/build-params in)
          getter (into {}
                       (for [kv ps
                             :let [[k v] (str/split kv #"=" 2)]]
                         [k (java.net.URLDecoder/decode v "UTF-8")]))
          out   (share/parse-params getter)]
      (is (= :story.foo/grid (:workspace-id out))))))

(deftest parse-params-with-viewport-custom
  (testing "viewport custom WxH round-trips"
    (let [in    {:viewport {:width 800 :height 600}}
          ps    (share/build-params in)
          getter (into {}
                       (for [kv ps
                             :let [[k v] (str/split kv #"=" 2)]]
                         [k (java.net.URLDecoder/decode v "UTF-8")]))
          out   (share/parse-params getter)]
      (is (= {:width 800 :height 600} (:viewport out))))))
