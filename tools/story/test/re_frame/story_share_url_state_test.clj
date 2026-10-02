(ns re-frame.story-share-url-state-test
  "JVM tests for the URL-state sharability slots.

  Pairs with `re-frame.story-share-test` (variant + modes + overrides +
  substrate). This ns pins the remaining sharability slots — workspace,
  mode-tab, viewport, background, tag-filter — and the
  `rf.story.share/parse-params` round-trip.

  Pure CLJC — every encoder + parser lives in `re-frame.story.share`,
  no CLJS deps."
  (:require [clojure.test :refer [are deftest is testing]]
            [clojure.string :as str]
            [re-frame.story.share :as rf.story.share]))

;; ---- workspace -----------------------------------------------------------

(deftest parse-workspace-param-reads-wire-token
  (testing "parse-workspace-param reads a `ns/name` wire token back to a keyword"
    (is (= :story.foo/grid
           (rf.story.share/parse-workspace-param "story.foo/grid")))
    (is (nil? (rf.story.share/parse-workspace-param "")))
    (is (nil? (rf.story.share/parse-workspace-param nil)))))

;; ---- mode-tab ------------------------------------------------------------

(deftest build-params-emits-only-a-known-non-default-mode-tab
  (testing ":dev is the default and is omitted so the canonical URL stays
            minimal; an unknown value is dropped, so a stale URL degrades
            silently"
    (are [mode-tab expected] (= expected (rf.story.share/build-params {:mode-tab mode-tab}))
      :docs  ["mode-tab=docs"]
      :dev   []
      :bogus [])))

(deftest parse-mode-tab-param
  (testing "parse-mode-tab-param recognises :dev/:docs/:test, drops anything else"
    (is (= :dev  (rf.story.share/parse-mode-tab-param "dev")))
    (is (= :docs (rf.story.share/parse-mode-tab-param "docs")))
    (is (= :test (rf.story.share/parse-mode-tab-param "test")))
    (is (nil?    (rf.story.share/parse-mode-tab-param "bogus")))
    (is (nil?    (rf.story.share/parse-mode-tab-param "")))
    (is (nil?    (rf.story.share/parse-mode-tab-param nil)))))

;; ---- viewport ------------------------------------------------------------

(deftest build-params-encodes-viewport-as-a-preset-or-wxh
  (are [viewport expected] (= expected (rf.story.share/build-params {:viewport viewport}))
    :tablet                  ["viewport=tablet"]
    {:width 800 :height 600} ["viewport=800x600"]
    ;; nil is omitted from the canonical URL
    nil                      []))

(deftest parse-viewport-param-reads-a-preset-or-wxh
  (testing "a preset token reads as a keyword and WxH as {:width :height};
            empty, nil and zero-dimension values degrade to nil. A bare
            unknown token still reads as a keyword: the hydrator's :viewport?
            validator drops anything that isn't a registered preset or a
            valid custom size"
    (are [s expected] (= expected (rf.story.share/parse-viewport-param s))
      "tablet"   :tablet
      "800x600"  {:width 800 :height 600}
      "1024x768" {:width 1024 :height 768}
      "0x600"    nil
      ""         nil
      nil        nil
      "x"        :x)))

;; ---- background ----------------------------------------------------------

(deftest build-params-encodes-background-as-a-preset-or-hex
  (are [background expected] (= expected (rf.story.share/build-params {:background background}))
    :dark     ["background=dark"]
    ;; # is percent-encoded to %23 so it doesn't collide with hash routes
    "#abc123" ["background=%23abc123"]))

(deftest parse-background-param-reads-a-preset-or-hex
  (testing "a preset token reads as a keyword, a hex colour reads back as
            itself, and blank values degrade to nil"
    (are [s expected] (= expected (rf.story.share/parse-background-param s))
      "dark"      :dark
      "#abc123"   "#abc123"
      "#ABC"      "#ABC"
      "#aabbccdd" "#aabbccdd"
      ""          nil
      nil         nil)))

;; ---- tag-filter ----------------------------------------------------------

(deftest build-params-tag-filter
  (testing "tag-filter set encodes as a sorted comma-separated list; the
            wire form percent-encodes `/` to %2F and `,` to %2C"
    (is (= ["tag-filter=tag%2Fa%2Ctag%2Fb"]
           (rf.story.share/build-params {:tag-filter #{:tag/b :tag/a}})))))

(deftest parse-tag-filter-param
  (testing "tag-filter parses into a set"
    (is (= #{:tag/a :tag/b}
           (rf.story.share/parse-tag-filter-param "tag/a,tag/b")))
    (is (nil? (rf.story.share/parse-tag-filter-param "")))
    (is (nil? (rf.story.share/parse-tag-filter-param nil)))))

;; ---- parse-params round-trip --------------------------------------------

(def ^:private all-slots-absent
  "What parse-params returns for a getter carrying no Story key."
  {:variant-id nil :workspace-id nil :mode-tab nil :active-modes nil
   :viewport nil :background nil :tag-filter nil :cell-overrides nil
   :substrate nil})

(defn- round-trip
  "build-params `in`, decode each value as URLSearchParams.get would, and
  parse the resulting getter back."
  [in]
  (rf.story.share/parse-params
    (into {}
          (for [kv   (rf.story.share/build-params in)
                :let [[k v] (str/split kv #"=" 2)]]
            [k (java.net.URLDecoder/decode v "UTF-8")]))))

(deftest parse-params-full-round-trip
  (testing "encode → URLSearchParams-shaped getter → parse returns exactly
            the slots that went in, every other slot nil"
    (are [in] (= (merge all-slots-absent in) (round-trip in))
      ;; every slot but the workspace
      {:variant-id     :story.counter/loaded
       :mode-tab       :docs
       :active-modes   [:Mode.app/dark :Mode.app/mobile]
       :viewport       :tablet
       :background     "#abc123"
       :tag-filter     #{:tag/a :tag/b}
       :cell-overrides {:label "Hi"}
       :substrate      :uix}

      ;; a workspace focus
      {:workspace-id :story.foo/grid}

      ;; a custom WxH viewport
      {:viewport {:width 800 :height 600}})))

(deftest parse-params-handles-missing-keys
  (testing "parse-params returns every slot, nil when absent — caller decides defaults"
    (is (= all-slots-absent (rf.story.share/parse-params {})))))
