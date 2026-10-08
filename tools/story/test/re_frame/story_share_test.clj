(ns re-frame.story-share-test
  "JVM tests for the per-variant share URL builder in `re-frame.story.share`
  (.cljc, so JVM and CLJS share one encoding), per `005-SOTA-Features.md`
  §Share URL."
  (:require [clojure.test :refer [are deftest is testing]]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [re-frame.story        :as rf.story]
            [re-frame.story.share  :as rf.story.share]))

(defn- query-part
  "The query-string portion of `url` — between `?` and any `#`."
  [url]
  (second (str/split (first (str/split url #"#" 2)) #"\?" 2)))

(defn- escape-first-char
  "Spell `k` with its leading character percent-encoded (variant becomes
  %76ariant): a browser-equivalent spelling that shares no prefix with the
  literal name, so a raw-text ownership test cannot match it."
  [k]
  (str "%" (format "%02X" (int (first k))) (subs k 1)))

(defn- decoded-key-count
  "How many query fragments of `url` carry key `k` once the key half is
  percent-decoded — what `URLSearchParams.getAll` would report. An
  undecodable key half counts for no key, as the builder treats it."
  [url k]
  (->> (str/split (or (query-part url) "") #"&")
       (filter #(= k (try (java.net.URLDecoder/decode
                            (first (str/split % #"=" 2)) "UTF-8")
                          (catch IllegalArgumentException _ nil))))
       count))

(defn- key-counts
  "`{key decoded-count}` over the whole Story vocabulary for `url`."
  [url]
  (into {} (map (juxt name #(decoded-key-count url (name %)))) rf.story.share/story-query-keys))

(defn- first-value-getter
  "Standards-faithful emulation of the browser hydrator's
  `URLSearchParams.get` reads over `url`'s query string: FIRST
  occurrence per key wins, values form-urlencoded-decoded (`+` → space,
  `%XX` → byte) — the same getter map
  `re-frame.story.ui.url-state/params->getter` hands to
  `rf.story.share/parse-params`."
  [url]
  (let [decode #(java.net.URLDecoder/decode (str %) "UTF-8")]
    (reduce (fn [m fragment]
              (let [[k v] (str/split fragment #"=" 2)
                    k     (decode k)]
                (if (contains? m k) m (assoc m k (decode (or v ""))))))
            {}
            (str/split (or (query-part url) "") #"&"))))

;; `build-params` omits empty / nil / default slots, so a builder clearing
;; only the keys a call emits would let a base-url's stale `modes=` or
;; `substrate=` survive a call that asked for `[]` or `:reagent`. The builder
;; is therefore authoritative over the whole `story-query-keys` vocabulary,
;; including the slots it declines to emit.

(def ^:private stale-story-params
  "A stale, parseable wire value for every key in the Story vocabulary, so a
  surviving value is visible to `parse-params` as restored state."
  {"variant"    "story.old%2Fa"
   "workspace"  "story.old%2Fws"
   "mode-tab"   "docs"
   "modes"      "Mode.app%2Fstale"
   "viewport"   "tablet"
   "background" "dark"
   "tag-filter" "stale"
   "overrides"  "%7B%3Afoo%201%7D"
   "substrate"  "uix"})

(defn- stale-base-url
  "Base URL carrying every stale Story key, spelled by `spell`, in
  vocabulary order, plus two unrelated params and a hash route."
  [spell]
  (str "https://example.test/?"
       (str/join "&" (map #(str (spell (name %)) "=" (get stale-story-params (name %)))
                          rf.story.share/story-query-keys))
       "&from=index&embed=1#/stories"))

(def ^:private parsed-slots
  [:variant-id :workspace-id :mode-tab :active-modes :viewport
   :background :tag-filter :cell-overrides :substrate])

(defn- restored
  "The non-nil slots `parse-params` restores from the browser's first-value read of `url`."
  [url]
  (into {} (remove (comp nil? val))
        (select-keys (rf.story.share/parse-params (first-value-getter url)) parsed-slots)))

(deftest story-query-keys-is-the-whole-build-params-vocabulary
  (testing "the clear set is correct only while it equals what build-params can
            emit, so a slot added to one without the other fails here"
    (is (= (set (map name rf.story.share/story-query-keys))
           (->> (rf.story.share/build-params
                  {:variant-id     :story.a/b
                   :workspace-id   :story.a/ws
                   :mode-tab       :docs          ; :dev is the omitted default
                   :active-modes   [:Mode.app/dark]
                   :viewport       :tablet
                   :background     :dark
                   :tag-filter     [:slow]
                   :cell-overrides {:label "x"}
                   :substrate      :my.lib/uix})  ; :reagent is the omitted default
                (map #(first (str/split % #"=" 2)))
                set)))
    (is (= (set (map name rf.story.share/story-query-keys)) (set (keys stale-story-params)))
        "the stale fixture covers the whole vocabulary")))

;; The browser hydrator reads each Story key with `URLSearchParams.get`, whose
;; first-value semantics pick the oldest occurrence, so the builder emits
;; exactly one effective value per key it owns — or a pasted URL lands on a
;; stale cell — and leaves unrelated entries and the hash route untouched.
;; URLSearchParams compares DECODED key names, so `%76ariant=` is `variant=`
;; to the browser and the builder must own it too.

(deftest variant-share-url-clears-stale-omitted-keys
  (testing "stale values for keys this call omits are cleared: one variant= the
            browser can see, no stale optional state, and unrelated params,
            their order and the hash survive"
    (let [url (rf.story.share/variant-share-url
                :story.new/b (stale-base-url identity)
                {:active-modes [] :cell-overrides {} :substrate :reagent})]
      (is (= (assoc (zipmap (keys stale-story-params) (repeat 0)) "variant" 1) (key-counts url)))
      (is (= {:variant-id :story.new/b} (restored url)))
      (is (= ["story.new/b" "index" "1"] (map (first-value-getter url) ["variant" "from" "embed"])))
      (is (str/starts-with? url "https://example.test/?from=index&embed=1&variant="))
      (is (str/ends-with? url "#/stories")))))

(deftest variant-share-url-owns-percent-encoded-key-spellings
  (testing "Story keys spelled with escapes are cleared, not merely outranked,
            leaving one effective value per owned key"
    (let [url (rf.story.share/variant-share-url
                :story.new/b (stale-base-url escape-first-char)
                {:active-modes [:Mode.app/dark]})]
      (is (= (assoc (zipmap (keys stale-story-params) (repeat 0)) "variant" 1 "modes" 1)
             (key-counts url)))
      (is (= {:variant-id :story.new/b :active-modes [:Mode.app/dark]} (restored url)))
      (is (= ["story.new/b" "Mode.app/dark" "index" "1"]
             (map (first-value-getter url) ["variant" "modes" "from" "embed"])))
      (is (str/starts-with? url "https://example.test/?from=index&embed=1&variant="))
      (is (str/ends-with? url "#/stories")))))

(deftest apply-story-params-preserves-undecodable-and-unowned-keys
  (testing "decoding is an ownership test, never a rewrite: an undecodable key
            half, or one that decodes to a key Story does not own, survives
            verbatim and in order. `mode+tab` reads as `mode tab` (+ is a space
            in a query component), which is not `mode-tab`"
    (let [url (rf.story.share/variant-share-url
                :story.new/b
                "https://example.test/?%zz=keepme&100%=raw&mode+tab=notmine&from=index"
                nil)]
      (is (str/starts-with?
            url
            "https://example.test/?%zz=keepme&100%=raw&mode+tab=notmine&from=index&variant="))
      (is (= [1 0] [(decoded-key-count url "variant") (decoded-key-count url "mode-tab")])))))

(defn- url-decode [t] (java.net.URLDecoder/decode (str t) "UTF-8"))

;; The overrides codec prints one EDN map, so a value containing the list
;; separator round-trips.
(defn- overrides-round-trip
  "Encode `ov` to the wire token, URL-decode it (as URLSearchParams.get
  would), and parse it back. Returns the reconstructed overrides map."
  [ov]
  (rf.story.share/parse-overrides-param (url-decode (rf.story.share/build-overrides-token ov))))

(deftest substrate-round-trips-qualified
  (testing "a qualified substrate id round-trips through build-params, URL
            decoding and parse-params as the same id, not a bare :uix"
    (let [sp      (some #(when (str/starts-with? % "substrate=") %)
                        (rf.story.share/build-params {:variant-id :story.foo/bar
                                                      :substrate  :my.lib/uix}))
          decoded (url-decode (subs sp (count "substrate=")))]
      (is (= :my.lib/uix
             (rf.story.share/parse-substrate-param decoded)
             (:substrate (rf.story.share/parse-params {"substrate" decoded})))))))

(deftest overrides-codec-round-trips-collection-values
  (testing "vector / map / set / nested values, which carry internal
            separators, round-trip"
    (let [ov {:items [1 2 3]
              :opts  {:a 1 :b 2}
              :tags  #{:x :y}
              :pair  [:k "v, with comma"]}]
      (is (= ov (overrides-round-trip ov)))))
  (testing "empty or nil overrides produce no token, and blank input parses to nil"
    (is (= [nil nil nil nil]
           [(rf.story.share/build-overrides-token {}) (rf.story.share/build-overrides-token nil)
            (rf.story.share/parse-overrides-param nil) (rf.story.share/parse-overrides-param "")]))))

;; ---- sorted output: one selection, one URL -------------------------------

(deftest overrides-token-sorts-keys
  (testing "the overrides token prints its keys sorted, so two insertion
            orders of the same map produce the same URL"
    (is (= "{:alpha 2, :zeta 1}"
           (url-decode (rf.story.share/build-overrides-token (array-map :zeta 1 :alpha 2)))))))

(deftest build-params-sorts-active-modes
  (testing "active modes are emitted sorted, so the order the modes were
            switched on does not change the URL"
    (is (= ["modes=Mode.app%2Fdark%2CMode.app%2Fmobile"]
           (rf.story.share/build-params {:active-modes [:Mode.app/mobile :Mode.app/dark]})))))

;; ---- parse-overrides-param* surfaces dropped entries ---------------------

(deftest parse-overrides-param*-splits-kept-and-dropped-entries
  (testing "parse-overrides-param* returns the overrides that survive and the
            entries it dropped, both keys always present so callers can
            destructure without nil-checking. In the EDN-map wire form a
            per-entry drop is a key that cannot coerce to a keyword, and an
            unreadable payload is dropped whole; the share-import hint reads
            :dropped to count and name what failed."
    (are [s expected] (= expected (rf.story.share/parse-overrides-param* s))
      ;; clean input: nothing dropped
      "{:label \"Hi\", :count 9}"
      {:overrides {:label "Hi" :count 9} :dropped []}

      ;; the non-keywordable key `5` is dropped; the well-formed entries survive
      "{:label \"OK\", :size 7, 5 :bad-key}"
      {:overrides {:label "OK" :size 7} :dropped ["5 :bad-key"]}

      ;; not a readable EDN map: no overrides, the whole token dropped
      "{:label \"unterminated"
      {:overrides nil :dropped ["{:label \"unterminated"]}

      ;; blank input returns the empty shape
      nil   {:overrides nil :dropped []}
      ""    {:overrides nil :dropped []}
      "   " {:overrides nil :dropped []})))

;; `parse-overrides-param*` drops only unparseable entries; a well-formed
;; override for an arg the variant renamed or removed would otherwise be
;; installed and merged by `args/resolve-args`, hiding the share-import drift.

(deftest drop-stale-overrides-splits-by-declared-keys
  (testing "overrides whose key the variant declares are kept, the rest move to
            :dropped beside the parser's own malformed drops; a nil declared
            set (no contract known) keeps everything, an empty one drops all"
    (let [out (rf.story.share/drop-stale-overrides
                {:overrides {:label "Hi" :gone 9 :count 3} :dropped ["bogus"]}
                #{:label :count})]
      (is (= {:label "Hi" :count 3} (:overrides out)))
      (is (= [2 true true]
             [(count (:dropped out)) (boolean (some #(= "bogus" %) (:dropped out)))
              (boolean (some #(str/includes? % ":gone") (:dropped out)))])))
    (is (= {:overrides {:a 1 :b 2} :dropped ["bad"]}
           (select-keys (rf.story.share/drop-stale-overrides
                          {:overrides {:a 1 :b 2} :dropped ["bad"]} nil)
                        [:overrides :dropped])))
    (let [out (rf.story.share/drop-stale-overrides {:overrides {:a 1} :dropped []} #{})]
      (is (= [nil 1] [(:overrides out) (count (:dropped out))])))))

(deftest variant-share-url-preserves-hash-route
  (testing "variant-share-url inserts params before # so the Story route survives"
    ;; Overrides encode as one pr-str EDN map (delimiter-safe),
    ;; URL-encoded: {:label "Share Slice"} → %7B%3Alabel+%22Share+Slice%22%7D.
    ;; The :reagent default substrate is omitted.
    (is (= (str "https://example.test/counter-with-stories/"
                "?variant=story.counter%2Floaded"
                "&modes=Mode.app%2Fdark"
                "&overrides=%7B%3Alabel+%22Share+Slice%22%7D"
                "#/stories")
           (rf.story.share/variant-share-url
             :story.counter/loaded
             "https://example.test/counter-with-stories/#/stories"
             {:active-modes   [:Mode.app/dark]
              :cell-overrides {:label "Share Slice"}
              :substrate      :reagent})))))

(deftest variant-share-url-public-export
  (testing "rf.story/variant-share-url is exported with both documented arms;
            the (variant-id opts) arm is a no-base query fragment with no
            leading ?"
    (is (= "https://x.test/?variant=story.foo%2Fbar&modes=Mode.x%2Fy"
           (rf.story/variant-share-url :story.foo/bar "https://x.test/" {:active-modes [:Mode.x/y]})))
    (is (= "variant=story.foo%2Fbar&modes=Mode.x%2Fy"
           (rf.story/variant-share-url :story.foo/bar {:active-modes [:Mode.x/y]})))))

;; There is no QR popover: the variant URL is the browser's live address-bar
;; URL. A third-party QR-image service would receive the full share URL, so
;; its endpoint must not appear in the share module.
(deftest no-qrserver-literal-in-share-source
  (is (not (str/includes? (slurp (io/resource "re_frame/story/share.cljc")) "api.qrserver.com"))))
