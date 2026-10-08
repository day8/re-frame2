(ns re-frame.story-share-cljs-test
  "CLJS tests for the per-variant share URL builder, read back through the
  real `js/URLSearchParams` the url-state hydrator uses — the browser API the
  JVM emulation in `re-frame.story-share-test` stands in for — plus the
  `js/encodeURIComponent` overrides path."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [clojure.string :as str]
            [re-frame.story.share :as rf.story.share]))

(def ^:private stale
  "A stale, parseable wire value for every key in the Story vocabulary."
  {"variant"    "story.old%2Fa"
   "workspace"  "story.old%2Fws"
   "mode-tab"   "docs"
   "modes"      "Mode.app%2Fstale"
   "viewport"   "tablet"
   "background" "dark"
   "tag-filter" "stale"
   "overrides"  "%7B%3Afoo%201%7D"
   "substrate"  "uix"})

(defn- escape-first-char
  "Spell `k` with its leading character percent-encoded (variant becomes
  %76ariant): browser-equivalent to `k`, and sharing no prefix with it."
  [k]
  (str "%"
       (.toUpperCase (.toString (.charCodeAt k 0) 16))
       (subs k 1)))

(defn- stale-base-url
  "A base URL carrying every stale Story key, spelled by `spell`, plus two
  unrelated params and a hash route."
  [spell]
  (str "https://example.test/?"
       (str/join "&" (map #(str (spell (name %)) "=" (get stale (name %)))
                          rf.story.share/story-query-keys))
       "&from=index&embed=1#/stories"))

(defn- search-of
  "The query-string portion of `url` — between `?` and any `#`."
  [url]
  (second (str/split (first (str/split url #"#" 2)) #"\?" 2)))

(defn- value-counts
  "`{key (count (.getAll usp key))}` over the Story vocabulary."
  [usp]
  (into {} (map (juxt identity #(count (.getAll usp %)))) (map name rf.story.share/story-query-keys)))

(defn- restored
  "The non-nil slots the hydrator's own read path (getter map ->
  `parse-params`) restores from `usp`."
  [usp]
  (into {} (remove (comp nil? val))
        (rf.story.share/parse-params
          (into {} (map (fn [k] [k (.get usp k)])) (map name rf.story.share/story-query-keys)))))

(deftest variant-share-url-clears-stale-omitted-keys-cljs
  (testing "build-params omits empty and default slots, so stale values for the
            keys a call omits must be absent to the real URLSearchParams — not
            merely later in the string, which .get would still return"
    (is (= (set (map name rf.story.share/story-query-keys)) (set (keys stale))))
    (let [url (rf.story.share/variant-share-url
                :story.new/b (stale-base-url identity)
                {:active-modes [] :cell-overrides {} :substrate :reagent})
          usp (js/URLSearchParams. (search-of url))]
      (is (= (assoc (zipmap (keys stale) (repeat 0)) "variant" 1) (value-counts usp)))
      (is (= {:variant-id :story.new/b} (restored usp)))
      (is (= ["story.new/b" "index" "1"] (map #(.get usp %) ["variant" "from" "embed"])))
      (is (str/ends-with? url "#/stories")))))

;; URLSearchParams compares key names after percent-decoding, so `%76ariant=`
;; is `variant=` to the browser. Asserted through the real API, so the pin
;; cannot drift from what the shell reads.
(deftest variant-share-url-owns-percent-encoded-keys-cljs
  (testing "escaped Story keys in the base-url are cleared: one value for each
            key this call emits, none for the ones it omits, unrelated params
            untouched"
    (let [url (rf.story.share/variant-share-url
                :story.new/b (stale-base-url escape-first-char)
                {:active-modes [:Mode.app/dark]})
          usp (js/URLSearchParams. (search-of url))]
      (is (= (assoc (zipmap (keys stale) (repeat 0)) "variant" 1 "modes" 1) (value-counts usp)))
      (is (= {:variant-id :story.new/b :active-modes [:Mode.app/dark]} (restored usp)))
      (is (= ["story.new/b" "Mode.app/dark" "index" "1"]
             (map #(.get usp %) ["variant" "modes" "from" "embed"])))
      (is (str/ends-with? url "#/stories")))))

(deftest undecodable-and-unowned-keys-survive-cljs
  (testing "decoding is an ownership test, not a rewrite: a key half
            js/decodeURIComponent throws on survives byte for byte, and
            `mode+tab` reads as `mode tab` to the browser, so it stays"
    (let [url (rf.story.share/variant-share-url
                :story.new/b
                "https://example.test/?%zz=keepme&100%=raw&mode+tab=notmine&from=index"
                nil)
          usp (js/URLSearchParams. (search-of url))]
      (is (str/starts-with?
            url
            "https://example.test/?%zz=keepme&100%=raw&mode+tab=notmine&from=index&variant="))
      (is (= [1 "notmine" 0]
             [(count (.getAll usp "variant")) (.get usp "mode tab") (count (.getAll usp "mode-tab"))])))))

(deftest scenario-overrides-token-roundtrips-with-spaced-string-value
  (testing "the browser-decoded `overrides=` token the share-url-hydrates
            scenario drives (`{:label \"Shared Label\"}`) parses as a kept
            override, not a dropped one. A space-bearing string value is the
            case a `label:\"...\"` list wire form would classify :dropped,
            so this pins the EDN-map form."
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
        (is (= {:label "Shared Label"}
               (rf.story.share/parse-overrides-param
                 (js/decodeURIComponent token)))
            "the CLJS-encoded token round-trips through URLSearchParams-style decode")))))
