(ns re-frame.story-share-cljs-test
  "CLJS smoke tests for the per-variant share URL builder. The pure URL
  logic in .cljc is JVM-tested in `re-frame.story-share-test`; this
  ns covers CLJS-specific paths (the `js/encodeURIComponent` path).

  The share URL is the same URL the browser's address bar carries —
  there is no separate Share button or QR popover (rf2-ymnfx Issue B
  removed the redundant affordance). The URL builder remains because
  `re-frame.story.ui.url-state` writes it via `pushState` and the
  public `story/variant-share-url` facade is exported for embed code
  (per `tools/story/spec/Tutorial-Embed.md`)."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [clojure.string :as str]
            [re-frame.story :as story]
            [re-frame.story.share :as share]))

(deftest variant-share-url-cljs-encoding
  (testing "CLJS url-encode uses js/encodeURIComponent under the hood"
    (let [url (share/variant-share-url
                :story.foo/bar
                "https://example.test/"
                {:active-modes [:Mode.app/dark]
                 :cell-overrides {}})]
      (is (str/starts-with? url "https://example.test/?"))
      (is (re-find #"variant=" url))
      (is (re-find #"modes=" url)))))

(deftest public-export-cljs
  (testing "story/variant-share-url resolves on CLJS"
    (let [url (story/variant-share-url :story.x/y "" nil)]
      (is (re-find #"variant=" url)))))

(deftest hash-routed-share-url-keeps-query-before-fragment
  (testing "CLJS builder emits ?variant= before #/stories so the shell can hydrate"
    (let [url (share/variant-share-url
                :story.counter/loaded
                "https://example.test/counter-with-stories/#/stories"
                {:cell-overrides {:label "Shared"}})]
      (is (str/starts-with? url "https://example.test/counter-with-stories/?"))
      (is (str/includes? url "#/stories"))
      (is (str/includes? url "variant="))
      (is (str/includes? url "overrides=")))))

(deftest variant-share-url-replaces-stale-owned-keys-cljs
  (testing "rf2-b7je1 — the REAL URLSearchParams (the API the url-state
            hydrator reads with) sees the values THIS call generated, not
            stale ones already on the base-url. get is first-value, so an
            append-only merge would return the old variant here."
    (let [url    (share/variant-share-url
                   :story.new/b
                   "https://example.test/?variant=story.old%2Fa&modes=Mode.app%2Fstale&from=index&embed=1#/stories"
                   {:active-modes [:Mode.app/dark]})
          search (second (str/split (first (str/split url #"#" 2)) #"\?" 2))
          usp    (js/URLSearchParams. search)]
      (is (= "story.new/b" (.get usp "variant"))
          "URLSearchParams.get returns the requested variant")
      (is (= "Mode.app/dark" (.get usp "modes"))
          "URLSearchParams.get returns the requested modes")
      (is (= 1 (count (.getAll usp "variant")))
          "exactly one variant value")
      (is (= 1 (count (.getAll usp "modes")))
          "exactly one modes value")
      (is (= "index" (.get usp "from"))
          "unrelated from= survives")
      (is (= "1" (.get usp "embed"))
          "unrelated embed= survives")
      (is (str/ends-with? url "#/stories")
          "the hash route survives, after the query"))))

(deftest variant-share-url-clears-stale-omitted-keys-cljs
  (testing "rf2-b7je1 audit — build-params omits empty / default optional
            slots, so a base-url carrying stale modes / overrides /
            substrate survived a call requesting [] / {} / :reagent. Read
            through the REAL URLSearchParams the url-state hydrator uses:
            every omitted Story key must be absent, not merely later in the
            string, because .get would happily return the stale value."
    (let [stale  {"variant"    "story.old%2Fa"
                  "workspace"  "story.old%2Fws"
                  "mode-tab"   "docs"
                  "modes"      "Mode.app%2Fstale"
                  "viewport"   "tablet"
                  "background" "dark"
                  "tag-filter" "stale"
                  "overrides"  "%7B%3Afoo%201%7D"
                  "substrate"  "uix"}
          base   (str "https://example.test/?"
                      (str/join "&" (map #(str (name %) "=" (get stale (name %)))
                                         share/story-query-keys))
                      "&from=index&embed=1#/stories")
          url    (share/variant-share-url
                   :story.new/b
                   base
                   {:active-modes [] :cell-overrides {} :substrate :reagent})
          search (second (str/split (first (str/split url #"#" 2)) #"\?" 2))
          usp    (js/URLSearchParams. search)]
      (is (= (set (map name share/story-query-keys)) (set (keys stale)))
          "the fixture carries a stale value for every key in the vocabulary")
      (is (= "story.new/b" (.get usp "variant"))
          "URLSearchParams.get returns the requested variant")
      (is (= 1 (count (.getAll usp "variant")))
          "exactly one variant value")
      (doseq [k (map name share/story-query-keys)
              :when (not= k "variant")]
        (is (zero? (count (.getAll usp k)))
            (str "URLSearchParams sees no stale " k "= at all")))
      ;; The hydrator's own read path: getter map -> share/parse-params.
      (let [parsed (share/parse-params
                     (into {} (map (fn [k] [k (.get usp k)]))
                           (map name share/story-query-keys)))]
        (is (= :story.new/b (:variant-id parsed))
            "parse-params over real URLSearchParams reads the requested variant")
        (doseq [slot [:workspace-id :mode-tab :active-modes :viewport
                      :background :tag-filter :cell-overrides :substrate]]
          (is (nil? (get parsed slot))
              (str "parse-params restores no stale " slot))))
      (is (= "index" (.get usp "from")) "unrelated from= survives")
      (is (= "1" (.get usp "embed")) "unrelated embed= survives")
      (is (str/ends-with? url "#/stories") "the hash route survives"))))

(deftest parse-share-url-params-cljs
  (testing "CLJS parser reconstructs the share URL tokens used by the shell hydrator"
    (is (= :story.counter/loaded
           (share/parse-keyword-token "story.counter/loaded")))
    (is (= [:Mode.app/dark :Mode.app/mobile]
           (share/parse-modes-param "Mode.app/dark,Mode.app/mobile")))
    (is (= {:label "Shared" :count 7}
           (share/parse-overrides-param "{:label \"Shared\", :count 7}")))))

(deftest scenario-overrides-token-roundtrips-with-spaced-string-value
  (testing "the browser-decoded `overrides=` token the share-url-hydrates
            scenario drives (`{:label \"Shared Label\"}`) parses as a kept
            override, not a dropped one. A space-bearing string value is the
            case that exposed the stale pre-rf2-j0hwf `label:\"...\"` wire
            form (it was classified :dropped), so this pins the EDN-map form."
    (let [decoded "{:label \"Shared Label\"}"
          parsed  (share/parse-overrides-param* decoded)]
      (is (= {:label "Shared Label"} (:overrides parsed))
          "the spaced-string override is kept")
      (is (= [] (:dropped parsed))
          "nothing is dropped — the EDN-map wire form reads cleanly")
      ;; The URL the scenario sends carries the js/encodeURIComponent form
      ;; (space → %20, not +); URLSearchParams.get decodes it back to the
      ;; EDN-map string above. Round-trip the encoder to lock the contract.
      (let [token (share/build-overrides-token {:label "Shared Label"})]
        (is (string? token))
        (is (= {:label "Shared Label"}
               (share/parse-overrides-param
                 (js/decodeURIComponent token)))
            "the CLJS-encoded token round-trips through URLSearchParams-style decode")))))
