(ns re-frame.story-share-cljs-test
  "CLJS smoke tests for the per-variant share URL builder. The pure URL
  logic in .cljc is JVM-tested in `re-frame.story-share-test`; this
  ns covers CLJS-specific paths (the `js/encodeURIComponent` path).

  The share URL is the same URL the browser's address bar carries —
  there is no separate Share button or QR popover (rf2-ymnfx Issue B
  removed the redundant affordance). The URL builder remains because
  `re-frame.story.ui.url-state` writes it via `pushState` and the
  public `rf.story/variant-share-url` facade is exported for embed code
  (per `tools/story/spec/Tutorial-Embed.md`)."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [clojure.string :as str]
            [re-frame.story :as rf.story]
            [re-frame.story.share :as rf.story.share]))

(deftest variant-share-url-cljs-encoding
  (testing "CLJS url-encode uses js/encodeURIComponent under the hood"
    (let [url (rf.story.share/variant-share-url
                :story.foo/bar
                "https://example.test/"
                {:active-modes [:Mode.app/dark]
                 :cell-overrides {}})]
      (is (str/starts-with? url "https://example.test/?"))
      (is (re-find #"variant=" url))
      (is (re-find #"modes=" url)))))

(deftest public-export-cljs
  (testing "rf.story/variant-share-url resolves on CLJS"
    (let [url (rf.story/variant-share-url :story.x/y "" nil)]
      (is (re-find #"variant=" url)))))

(deftest hash-routed-share-url-keeps-query-before-fragment
  (testing "CLJS builder emits ?variant= before #/stories so the shell can hydrate"
    (let [url (rf.story.share/variant-share-url
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
    (let [url    (rf.story.share/variant-share-url
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
                                         rf.story.share/story-query-keys))
                      "&from=index&embed=1#/stories")
          url    (rf.story.share/variant-share-url
                   :story.new/b
                   base
                   {:active-modes [] :cell-overrides {} :substrate :reagent})
          search (second (str/split (first (str/split url #"#" 2)) #"\?" 2))
          usp    (js/URLSearchParams. search)]
      (is (= (set (map name rf.story.share/story-query-keys)) (set (keys stale)))
          "the fixture carries a stale value for every key in the vocabulary")
      (is (= "story.new/b" (.get usp "variant"))
          "URLSearchParams.get returns the requested variant")
      (is (= 1 (count (.getAll usp "variant")))
          "exactly one variant value")
      (doseq [k (map name rf.story.share/story-query-keys)
              :when (not= k "variant")]
        (is (zero? (count (.getAll usp k)))
            (str "URLSearchParams sees no stale " k "= at all")))
      ;; The hydrator's own read path: getter map -> rf.story.share/parse-params.
      (let [parsed (rf.story.share/parse-params
                     (into {} (map (fn [k] [k (.get usp k)]))
                           (map name rf.story.share/story-query-keys)))]
        (is (= :story.new/b (:variant-id parsed))
            "parse-params over real URLSearchParams reads the requested variant")
        (doseq [slot [:workspace-id :mode-tab :active-modes :viewport
                      :background :tag-filter :cell-overrides :substrate]]
          (is (nil? (get parsed slot))
              (str "parse-params restores no stale " slot))))
      (is (= "index" (.get usp "from")) "unrelated from= survives")
      (is (= "1" (.get usp "embed")) "unrelated embed= survives")
      (is (str/ends-with? url "#/stories") "the hash route survives"))))

;; ---- rf2-b7je1 (audit reopen 2): ownership compares DECODED key names ----
;;
;; This is the arm that matters, because the disagreement is with a real
;; browser API rather than with an emulation of one. Ownership was
;; matched on the fragment's RAW key text; `URLSearchParams` compares key
;; names after percent-decoding. So `%76ariant=` — a valid spelling of
;; `variant=` — was a different string to the builder and the SAME key to
;; the browser: the stale entry survived, the generated one was appended
;; behind it, and `.get` (first-value) handed the hydrator the stale
;; value. Asserted through `js/URLSearchParams` itself, so the pin cannot
;; drift from what the shell will actually read.

(defn- escape-first-char
  "Spell `k` with its leading character percent-encoded — \"variant\" →
  \"%76ariant\". Browser-equivalent to `k`, and sharing no prefix with
  it, so a raw-text ownership test cannot match it."
  [k]
  (str "%"
       (.toUpperCase (.toString (.charCodeAt k 0) 16))
       (subs k 1)))

(defn- search-of
  "The query-string portion of `url` — between `?` and any `#`."
  [url]
  (second (str/split (first (str/split url #"#" 2)) #"\?" 2)))

(deftest escaped-story-keys-are-the-same-keys-to-urlsearchparams
  (testing "rf2-b7je1 — the fixture below is only a regression if the real
            URLSearchParams reads the escaped spellings as the owned keys.
            Pin that against the browser API before relying on it."
    (doseq [k (map name rf.story.share/story-query-keys)]
      (let [esc (escape-first-char k)
            usp (js/URLSearchParams. (str esc "=x"))]
        (is (not= esc k)
            (str k " is genuinely respelled, so raw matching cannot see it"))
        (is (= "x" (.get usp k))
            (str "URLSearchParams reads " esc " as the key " k))))))

(deftest variant-share-url-owns-percent-encoded-keys-cljs
  (testing "rf2-b7je1 audit — a base-url spelling every Story key with an
            escape is carrying those keys as far as the browser is
            concerned. Read the result back through the REAL
            URLSearchParams the hydrator uses: one value for each key this
            call emits, none at all for the ones it omits, unrelated
            params untouched."
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
                      (str/join "&" (map #(str (escape-first-char (name %))
                                               "="
                                               (get stale (name %)))
                                         rf.story.share/story-query-keys))
                      "&from=index&embed=1#/stories")
          url    (rf.story.share/variant-share-url
                   :story.new/b
                   base
                   {:active-modes [:Mode.app/dark]})
          usp    (js/URLSearchParams. (search-of url))]
      (is (= (set (map name rf.story.share/story-query-keys)) (set (keys stale)))
          "the fixture carries a stale value for every key in the vocabulary")
      (is (= "story.new/b" (.get usp "variant"))
          "URLSearchParams.get returns the requested variant, not the stale one")
      (is (= "Mode.app/dark" (.get usp "modes"))
          "URLSearchParams.get returns the requested modes")
      (is (= 1 (count (.getAll usp "variant")))
          "exactly one variant value the browser can see")
      (is (= 1 (count (.getAll usp "modes")))
          "exactly one modes value the browser can see")
      (doseq [k (map name rf.story.share/story-query-keys)
              :when (not (#{"variant" "modes"} k))]
        (is (zero? (count (.getAll usp k)))
            (str "URLSearchParams sees no stale " k "= at all")))
      (let [parsed (rf.story.share/parse-params
                     (into {} (map (fn [k] [k (.get usp k)]))
                           (map name rf.story.share/story-query-keys)))]
        (is (= :story.new/b (:variant-id parsed))
            "the hydrator's own read path recovers the requested variant")
        (is (= [:Mode.app/dark] (:active-modes parsed))
            "and the requested modes")
        (doseq [slot [:workspace-id :mode-tab :viewport :background
                      :tag-filter :cell-overrides :substrate]]
          (is (nil? (get parsed slot))
              (str "parse-params restores no stale " slot))))
      (is (= "index" (.get usp "from")) "unrelated from= survives")
      (is (= "1" (.get usp "embed")) "unrelated embed= survives")
      (is (str/ends-with? url "#/stories") "the hash route survives"))))

(deftest undecodable-and-unowned-keys-survive-cljs
  (testing "rf2-b7je1 audit — decoding is an ownership TEST, not a rewrite.
            A key half js/decodeURIComponent throws on is preserved
            byte-for-byte, and `mode+tab` reads as `mode tab` to the
            browser, so it is not Story's `mode-tab` and must stay."
    (let [url (rf.story.share/variant-share-url
                :story.new/b
                "https://example.test/?%zz=keepme&100%=raw&mode+tab=notmine&from=index"
                nil)
          usp (js/URLSearchParams. (search-of url))]
      (is (str/starts-with?
            url
            "https://example.test/?%zz=keepme&100%=raw&mode+tab=notmine&from=index&variant=")
          "every undecodable / unowned entry survives verbatim and in order")
      (is (= 1 (count (.getAll usp "variant")))
          "the generated variant= is still the only one")
      (is (= "notmine" (.get usp "mode tab"))
          "the browser reads mode+tab as `mode tab` — not Story's key")
      (is (zero? (count (.getAll usp "mode-tab")))
          "and nothing of Story's was cleared on its account"))))

(deftest parse-share-url-params-cljs
  (testing "CLJS parser reconstructs the share URL tokens used by the shell hydrator"
    (is (= :story.counter/loaded
           (rf.story.share/parse-keyword-token "story.counter/loaded")))
    (is (= [:Mode.app/dark :Mode.app/mobile]
           (rf.story.share/parse-modes-param "Mode.app/dark,Mode.app/mobile")))
    (is (= {:label "Shared" :count 7}
           (rf.story.share/parse-overrides-param "{:label \"Shared\", :count 7}")))))

(deftest scenario-overrides-token-roundtrips-with-spaced-string-value
  (testing "the browser-decoded `overrides=` token the share-url-hydrates
            scenario drives (`{:label \"Shared Label\"}`) parses as a kept
            override, not a dropped one. A space-bearing string value is the
            case that exposed the stale pre-rf2-j0hwf `label:\"...\"` wire
            form (it was classified :dropped), so this pins the EDN-map form."
    (let [decoded "{:label \"Shared Label\"}"
          parsed  (rf.story.share/parse-overrides-param* decoded)]
      (is (= {:label "Shared Label"} (:overrides parsed))
          "the spaced-string override is kept")
      (is (= [] (:dropped parsed))
          "nothing is dropped — the EDN-map wire form reads cleanly")
      ;; The URL the scenario sends carries the js/encodeURIComponent form
      ;; (space → %20, not +); URLSearchParams.get decodes it back to the
      ;; EDN-map string above. Round-trip the encoder to lock the contract.
      (let [token (rf.story.share/build-overrides-token {:label "Shared Label"})]
        (is (string? token))
        (is (= {:label "Shared Label"}
               (rf.story.share/parse-overrides-param
                 (js/decodeURIComponent token)))
            "the CLJS-encoded token round-trips through URLSearchParams-style decode")))))
