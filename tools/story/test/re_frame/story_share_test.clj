(ns re-frame.story-share-test
  "JVM tests for Stage 6 (rf2-zhwd) — per-variant share URL builder.

  The URL-building logic lives in `re-frame.story.share` (.cljc) so
  the same encoding works on JVM and CLJS. JVM tests round-trip the
  expected shape per `005-SOTA-Features.md` §Share URL (retired QR popover)."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [re-frame.story        :as rf.story]
            [re-frame.story.share  :as rf.story.share]))

;; ---- pure: build-params --------------------------------------------------

(deftest build-params-minimal
  (testing "build-params returns the :variant param when only variant-id supplied"
    (let [ps (rf.story.share/build-params {:variant-id :story.foo/bar})]
      (is (= 1 (count ps)))
      (is (re-find #"^variant=" (first ps))))))

(deftest build-params-modes
  (testing "build-params encodes modes as comma-separated stable list"
    (let [ps (rf.story.share/build-params {:variant-id  :story.foo/bar
                                  :active-modes [:Mode.app/dark
                                                 :Mode.app/mobile]})
          modes-param (some #(when (str/starts-with? % "modes=") %) ps)]
      (is (some? modes-param))
      ;; The list is sorted alphabetically by keyword name.
      (is (or (re-find #"dark" modes-param)
              (re-find #"mobile" modes-param))))))

(deftest build-params-overrides
  (testing "build-params encodes overrides as comma-separated k:v pairs"
    (let [ps (rf.story.share/build-params {:variant-id     :story.foo/bar
                                  :cell-overrides {:label "Click me"
                                                   :count 5}})
          ov (some #(when (str/starts-with? % "overrides=") %) ps)]
      (is (some? ov)))))

(deftest build-params-substrate-omits-reagent
  (testing "build-params omits :substrate when its value is :reagent (default)"
    (let [ps (rf.story.share/build-params {:variant-id :story.foo/bar
                                  :substrate  :reagent})]
      (is (not (some #(str/starts-with? % "substrate=") ps))))))

(deftest build-params-substrate-non-default
  (testing "build-params includes :substrate when not :reagent"
    (let [ps (rf.story.share/build-params {:variant-id :story.foo/bar
                                  :substrate  :uix})]
      (is (some #(str/starts-with? % "substrate=") ps)))))

(deftest build-params-substrate-preserves-namespace
  (testing "rf2-j5yv6y — a qualified substrate id (e.g. a host app's
            :my.lib/uix) keeps its namespace on the wire instead of being
            collapsed to its bare name. Mirrors the namespace-preserving
            encoding used for :variant / :workspace."
    (let [ps (rf.story.share/build-params {:variant-id :story.foo/bar
                                  :substrate  :my.lib/uix})
          sp (some #(when (str/starts-with? % "substrate=") %) ps)]
      (is (some? sp))
      ;; The `/` is percent-encoded to %2F on the wire.
      (is (str/includes? sp "my.lib%2Fuix")
          "the namespace survives encoding (no bare `uix`)"))))

;; ---- variant-share-url ---------------------------------------------------

(deftest variant-share-url-no-base
  (testing "variant-share-url with no base produces params without leading ?"
    (let [url (rf.story.share/variant-share-url :story.foo/bar)]
      (is (string? url))
      (is (re-find #"variant=" url))
      ;; No leading scheme / slash with no base.
      (is (not (re-find #"^http" url))))))

(deftest variant-share-url-with-base
  (testing "variant-share-url prepends base + ?"
    (let [url (rf.story.share/variant-share-url
                :story.foo/bar
                "https://example.test/stories.html"
                {:active-modes []
                 :cell-overrides {}})]
      (is (str/starts-with? url "https://example.test/stories.html?"))
      (is (re-find #"variant=" url)))))

(deftest variant-share-url-merges-existing-query
  (testing "variant-share-url uses & separator when base already has ?"
    (let [url (rf.story.share/variant-share-url
                :story.foo/bar
                "https://example.test/?from=index"
                nil)]
      (is (re-find #"\?from=index&variant=" url)))))

;; ---- rf2-b7je1: owned keys REPLACE stale base-url values -----------------
;;
;; The browser hydrator (`re-frame.story.ui.url-state/params->getter`)
;; reads each Story key with `URLSearchParams.get`, whose FIRST-value
;; semantics select the oldest occurrence in the query string. An
;; append-only merge over a base-url that already carries `variant=` /
;; `modes=` therefore hydrates the STALE cell — violating the share
;; invariant that a pasted URL lands on the exact same cell. The builder
;; must emit exactly one effective value per key it owns, while leaving
;; unrelated query entries and the hash route untouched.

(defn- query-part
  "The query-string portion of `url` — between `?` and any `#`."
  [url]
  (second (str/split (first (str/split url #"#" 2)) #"\?" 2)))

(defn- query-key-count
  "How many query fragments of `url` carry key `k`."
  [url k]
  (->> (str/split (or (query-part url) "") #"&")
       (filter #(= k (first (str/split % #"=" 2))))
       count))

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

(deftest variant-share-url-replaces-stale-owned-keys
  (testing "rf2-b7je1 — a base-url already carrying variant= and modes=
            gets those values REPLACED, not appended after; browser
            first-value reads and parse-params both recover the newly
            requested cell, and unrelated params + hash route survive"
    (let [url    (rf.story.share/variant-share-url
                   :story.new/b
                   "https://example.test/?variant=story.old%2Fa&modes=Mode.app%2Fstale&from=index&embed=1#/stories"
                   {:active-modes [:Mode.app/dark]})
          getter (first-value-getter url)
          parsed (rf.story.share/parse-params getter)]
      (is (= 1 (query-key-count url "variant"))
          "exactly one variant= in the query string")
      (is (= 1 (query-key-count url "modes"))
          "exactly one modes= in the query string")
      (is (= "story.new/b" (get getter "variant"))
          "URLSearchParams.get-faithful read sees the requested variant")
      (is (= "Mode.app/dark" (get getter "modes"))
          "URLSearchParams.get-faithful read sees the requested modes")
      (is (= :story.new/b (:variant-id parsed))
          "parse-params reconstructs the requested variant")
      (is (= [:Mode.app/dark] (:active-modes parsed))
          "parse-params reconstructs the requested modes")
      (is (= "index" (get getter "from"))
          "unrelated from= survives with its value")
      (is (= "1" (get getter "embed"))
          "unrelated embed= survives with its value")
      (is (re-find #"\?from=index&embed=1&" url)
          "unrelated entries keep their order ahead of generated params")
      (is (str/ends-with? url "#/stories")
          "the hash route survives, after the query"))))

;; ---- rf2-b7je1 (audit reopen): OMITTED slots clear their stale values ----
;;
;; The first repair cleared only the keys the call EMITTED. `build-params`
;; deliberately omits empty / nil / default slots, so a base-url carrying
;; `modes=` / `overrides=` / `substrate=` survived a call that requested
;; `[]` / `{}` / `:reagent` — the hydrator then restored state the caller
;; never asked for, breaking the exact-cell invariant by OMISSION rather
;; than by order. The builder is authoritative over the whole
;; `rf.story.share/story-query-keys` vocabulary, including the slots it declines
;; to emit.

(def ^:private stale-story-params
  "A stale, PARSEABLE wire value for every key in the Story vocabulary.
  Parseable on purpose: a surviving value must be visible to
  `parse-params` as restored state, not merely as an extra fragment."
  {"variant"    "story.old%2Fa"
   "workspace"  "story.old%2Fws"
   "mode-tab"   "docs"
   "modes"      "Mode.app%2Fstale"
   "viewport"   "tablet"
   "background" "dark"
   "tag-filter" "stale"
   "overrides"  "%7B%3Afoo%201%7D"
   "substrate"  "uix"})

(def ^:private stale-base-url
  "Base URL carrying every stale Story key, in vocabulary order, plus two
  unrelated params and a hash route."
  (str "https://example.test/?"
       (str/join "&" (map #(str (name %) "=" (get stale-story-params (name %)))
                          rf.story.share/story-query-keys))
       "&from=index&embed=1#/stories"))

(deftest story-query-keys-is-the-whole-build-params-vocabulary
  (testing "rf2-b7je1 — the clear set is only correct while it equals what
            build-params can emit. A slot added to build-params without a
            matching story-query-keys entry silently reopens the stale-value
            hole, so pin the two against each other."
    (let [emitted (->> (rf.story.share/build-params
                         {:variant-id     :story.a/b
                          :workspace-id   :story.a/ws
                          :mode-tab       :docs        ; :dev is the omitted default
                          :active-modes   [:Mode.app/dark]
                          :viewport       :tablet
                          :background     :dark
                          :tag-filter     [:slow]
                          :cell-overrides {:label "x"}
                          :substrate      :my.lib/uix}) ; :reagent is the omitted default
                       (map #(first (str/split % #"=" 2)))
                       set)]
      (is (= (set (map name rf.story.share/story-query-keys)) emitted)
          "build-params with every slot populated emits exactly the vocabulary"))))

(deftest variant-share-url-clears-stale-omitted-keys
  (testing "rf2-b7je1 audit — a base-url carrying stale values for keys this
            call OMITS comes back carrying none of them; the browser
            first-value read and parse-params see the requested cell with no
            stale optional state; unrelated params and the hash survive."
    (is (= (set (map name rf.story.share/story-query-keys))
           (set (keys stale-story-params)))
        "the fixture carries a stale value for every key in the vocabulary")
    (let [url    (rf.story.share/variant-share-url
                   :story.new/b
                   stale-base-url
                   ;; Every optional slot empty / default — so build-params
                   ;; emits `variant=` and nothing else.
                   {:active-modes [] :cell-overrides {} :substrate :reagent})
          getter (first-value-getter url)
          parsed (rf.story.share/parse-params getter)]
      (is (= 1 (query-key-count url "variant"))
          "exactly one variant= — the requested one")
      (doseq [k (map name rf.story.share/story-query-keys)
              :when (not= k "variant")]
        (is (zero? (query-key-count url k))
            (str "stale " k "= is cleared when the call omits that slot")))
      (is (= "story.new/b" (get getter "variant"))
          "browser first-value read sees the requested variant")
      (is (= :story.new/b (:variant-id parsed))
          "parse-params reconstructs the requested variant")
      (doseq [slot [:workspace-id :mode-tab :active-modes :viewport
                    :background :tag-filter :cell-overrides :substrate]]
        (is (nil? (get parsed slot))
            (str "parse-params restores no stale " slot)))
      (is (= "index" (get getter "from"))
          "unrelated from= survives with its value")
      (is (= "1" (get getter "embed"))
          "unrelated embed= survives — it is chrome state, not shell state")
      (is (str/starts-with? url "https://example.test/?from=index&embed=1&variant=")
          "unrelated entries keep their order ahead of the generated params")
      (is (str/ends-with? url "#/stories")
          "the hash route survives, after the query"))))

;; ---- rf2-b7je1 (audit reopen 2): ownership compares DECODED key names ----
;;
;; The clear set was matched against the fragment's RAW key text. The
;; consumer is `URLSearchParams`, which compares key names after percent-
;; decoding, so `%76ariant=` — a valid spelling of `variant=` — is the
;; SAME key to the browser and a different string to the builder. The
;; stale entry therefore survived, the generated `variant=` was appended
;; behind it, and `.get` (first-value) handed the hydrator the stale
;; value: the original bug, reached through the key half instead of the
;; value half.

(defn- escape-first-char
  "Spell `k` with its leading character percent-encoded — `\"variant\"` →
  `\"%76ariant\"`. A valid, browser-equivalent spelling of the same key
  that shares no prefix with the literal name, so a raw-text ownership
  test cannot match it."
  [k]
  (str "%" (format "%02X" (int (first k))) (subs k 1)))

(defn- decoded-key-count
  "How many query fragments of `url` carry key `k` once the key half is
  percent-decoded — i.e. how many values `URLSearchParams.getAll` would
  report for `k`. An undecodable key half counts for no key at all, the
  same fall-through the builder applies to it."
  [url k]
  (->> (str/split (or (query-part url) "") #"&")
       (filter #(= k (try (java.net.URLDecoder/decode
                            (first (str/split % #"=" 2)) "UTF-8")
                          (catch IllegalArgumentException _ nil))))
       count))

(def ^:private escaped-stale-base-url
  "Base URL carrying a stale value for every Story key, each key spelled
  with its first character percent-encoded, plus two unrelated params
  and a hash route."
  (str "https://example.test/?"
       (str/join "&" (map #(str (escape-first-char (name %))
                                "="
                                (get stale-story-params (name %)))
                          rf.story.share/story-query-keys))
       "&from=index&embed=1#/stories"))

(deftest escaped-story-keys-are-the-same-keys-to-the-browser
  (testing "rf2-b7je1 — the fixture is only a regression if the escaped
            spellings really are the owned keys after decoding; pin that
            before asserting anything about them"
    (doseq [k (map name rf.story.share/story-query-keys)]
      (let [esc (escape-first-char k)]
        (is (not= esc k)
            (str k " is genuinely respelled, so raw matching cannot see it"))
        (is (= k (java.net.URLDecoder/decode esc "UTF-8"))
            (str esc " decodes to " k))))))

(deftest variant-share-url-owns-percent-encoded-key-spellings
  (testing "rf2-b7je1 audit — a base-url spelling the Story keys with
            escapes (%76ariant=) is carrying those keys as far as the
            browser is concerned. The builder must clear them, leaving one
            effective value per owned key, while unrelated params, their
            order, and the hash route survive."
    (let [url    (rf.story.share/variant-share-url
                   :story.new/b
                   escaped-stale-base-url
                   {:active-modes [:Mode.app/dark]})
          getter (first-value-getter url)
          parsed (rf.story.share/parse-params getter)]
      (is (= 1 (decoded-key-count url "variant"))
          "exactly one variant= the browser can see — the requested one")
      (is (= 1 (decoded-key-count url "modes"))
          "exactly one modes= the browser can see — the requested one")
      (doseq [k (map name rf.story.share/story-query-keys)
              :when (not (#{"variant" "modes"} k))]
        (is (zero? (decoded-key-count url k))
            (str "stale escaped " k "= is cleared, not merely outranked")))
      (is (= "story.new/b" (get getter "variant"))
          "browser first-value read sees the requested variant")
      (is (= "Mode.app/dark" (get getter "modes"))
          "browser first-value read sees the requested modes")
      (is (= :story.new/b (:variant-id parsed))
          "parse-params reconstructs the requested variant")
      (is (= [:Mode.app/dark] (:active-modes parsed))
          "parse-params reconstructs the requested modes")
      (doseq [slot [:workspace-id :mode-tab :viewport :background
                    :tag-filter :cell-overrides :substrate]]
        (is (nil? (get parsed slot))
            (str "parse-params restores no stale " slot)))
      (is (= "index" (get getter "from"))
          "unrelated from= survives with its value")
      (is (= "1" (get getter "embed"))
          "unrelated embed= survives — chrome state, never Story's")
      (is (str/starts-with? url "https://example.test/?from=index&embed=1&variant=")
          "unrelated entries keep their order ahead of the generated params")
      (is (str/ends-with? url "#/stories")
          "the hash route survives, after the query"))))

(deftest apply-story-params-preserves-undecodable-and-unowned-keys
  (testing "rf2-b7je1 audit — decoding is an ownership TEST, never a
            rewrite. A key half that does not decode at all falls through
            and is preserved byte-for-byte; so is a key that decodes to
            something Story does not own. `mode+tab` is the sharp case:
            `+` is a space in a query component, so the browser reads it
            as `mode tab`, which is NOT `mode-tab`."
    (let [url (rf.story.share/variant-share-url
                :story.new/b
                "https://example.test/?%zz=keepme&100%=raw&mode+tab=notmine&from=index"
                nil)]
      (is (str/starts-with?
            url
            "https://example.test/?%zz=keepme&100%=raw&mode+tab=notmine&from=index&variant=")
          "every undecodable / unowned entry survives verbatim and in order")
      (is (= 1 (decoded-key-count url "variant"))
          "the generated variant= is still the only one")
      (is (zero? (decoded-key-count url "mode-tab"))
          "mode+tab is not mode-tab, so nothing of Story's was cleared"))))

(deftest variant-share-url-inserts-query-before-hash-route
  (testing "hash-routed Story links keep query params in location.search"
    (let [url (rf.story.share/variant-share-url
                :story.foo/bar
                "https://example.test/counter-with-stories/#/stories"
                {:active-modes [:Mode.app/dark]})]
      (is (str/starts-with?
            url
            "https://example.test/counter-with-stories/?"))
      (is (str/includes? url "#/stories"))
      (is (re-find #"variant=" url))
      (is (re-find #"modes=" url)))))

(deftest parse-share-url-params
  (testing "share URL parser reconstructs variant, modes, substrate, and overrides"
    (is (= :story.counter/loaded
           (rf.story.share/parse-keyword-token "story.counter/loaded")))
    (is (= [:Mode.app/dark :Mode.app/mobile]
           (rf.story.share/parse-modes-param "Mode.app/dark,Mode.app/mobile")))
    (is (= :uix (rf.story.share/parse-substrate-param "uix")))
    (is (= {:label "Shared Label" :count 9}
           (rf.story.share/parse-overrides-param "{:label \"Shared Label\", :count 9}")))))

;; ---- rf2-j0hwf: overrides codec round-trip -------------------------------
;;
;; The codec prints one EDN map (delimiter-safe) and reads it back as one
;; map, so an EDN value containing the list separator round-trips faithfully.

(defn- url-decode [t] (java.net.URLDecoder/decode (str t) "UTF-8"))

(defn- overrides-round-trip
  "Encode `ov` to the wire token, URL-decode it (as URLSearchParams.get
  would), and parse it back. Returns the reconstructed overrides map."
  [ov]
  (rf.story.share/parse-overrides-param (url-decode (rf.story.share/build-overrides-token ov))))

;; ---- rf2-j5yv6y: substrate id round-trips namespace ----------------------

(deftest substrate-round-trips-qualified
  (testing "rf2-j5yv6y — a qualified substrate id round-trips through
            build-params → URL decoding → parse-params without losing its
            namespace. A registered custom substrate like :my.lib/uix must
            hydrate back to the SAME id, not a different bare :uix."
    (let [substrate :my.lib/uix
          ps        (rf.story.share/build-params {:variant-id :story.foo/bar
                                         :substrate  substrate})
          sp        (some #(when (str/starts-with? % "substrate=") %) ps)
          ;; URLSearchParams.get returns the decoded value; emulate it.
          decoded   (url-decode (subs sp (count "substrate=")))]
      (is (= substrate (rf.story.share/parse-substrate-param decoded))
          "qualified substrate id survives the full encode → decode → parse")
      (is (= substrate (:substrate (rf.story.share/parse-params {"substrate" decoded})))
          "and through the full parse-params inverse"))))

(deftest overrides-codec-round-trips-simple
  (testing "rf2-j0hwf — simple overrides round-trip through build/parse"
    (let [ov {:label "Click me" :count 5}]
      (is (= ov (overrides-round-trip ov))))))

(deftest overrides-codec-round-trips-comma-value
  (testing "rf2-j0hwf — a string override value containing the list
            separator (comma) round-trips faithfully instead of being
            shredded into malformed entries and dropped"
    (let [ov {:label "Save, continue"}]
      (is (= ov (overrides-round-trip ov))
          "comma-containing string value survives the round-trip"))
    (testing "comma value alongside other entries"
      (let [ov {:label "Save, continue" :count 3 :title "A, B, C"}]
        (is (= ov (overrides-round-trip ov)))))))

(deftest overrides-codec-round-trips-collection-values
  (testing "rf2-j0hwf — vector / map / set / nested EDN values (which all
            carry internal separators) round-trip"
    (let [ov {:items [1 2 3]
              :opts  {:a 1 :b 2}
              :tags  #{:x :y}
              :pair  [:k "v, with comma"]}]
      (is (= ov (overrides-round-trip ov))))))

(deftest overrides-codec-deterministic-order
  (testing "rf2-j0hwf — the encoded token is stable across calls (keys
            sorted) so the URL is canonical and idempotent pushes no-op"
    (let [ov {:zed 1 :alpha 2 :mid 3}]
      (is (= (rf.story.share/build-overrides-token ov)
             (rf.story.share/build-overrides-token ov))))))

(deftest overrides-codec-empty-and-nil
  (testing "rf2-j0hwf — empty/nil overrides produce no token, and blank
            input parses to nil"
    (is (nil? (rf.story.share/build-overrides-token {})))
    (is (nil? (rf.story.share/build-overrides-token nil)))
    (is (nil? (rf.story.share/parse-overrides-param nil)))
    (is (nil? (rf.story.share/parse-overrides-param "")))))

;; ---- rf2-9jthx: parse-overrides-param* surfaces dropped entries ----------

(deftest parse-overrides-param*-clean-input
  (testing "rf2-9jthx — parse-overrides-param* returns the same :overrides
            map as parse-overrides-param plus an empty :dropped vec for
            clean input"
    (let [{:keys [overrides dropped]}
          (rf.story.share/parse-overrides-param* "{:label \"Hi\", :count 9}")]
      (is (= {:label "Hi" :count 9} overrides))
      (is (= [] dropped) "no entries dropped for a clean input"))))

(deftest parse-overrides-param*-mixed-input
  (testing "rf2-9jthx — parse-overrides-param* reports the SET of dropped
            entries alongside the surviving overrides — the share-import
            hint reads :dropped to count + name what failed. In the EDN-map
            wire form a per-entry drop is a key that cannot coerce to a
            keyword; the surviving entries are kept."
    (let [{:keys [overrides dropped]}
          (rf.story.share/parse-overrides-param*
            "{:label \"OK\", :size 7, 5 :bad-key}")]
      (is (= {:label "OK" :size 7} overrides)
          "well-formed entries survive")
      (is (= 1 (count dropped))
          "one malformed entry — the non-keywordable key `5`")
      (is (some #(re-find #"^5 " %) dropped)))))

(deftest parse-overrides-param*-all-dropped
  (testing "rf2-9jthx — when the payload is not a readable EDN map :overrides
            is nil and the whole token is dropped"
    (let [{:keys [overrides dropped]}
          (rf.story.share/parse-overrides-param* "{:label \"unterminated")]
      (is (nil? overrides))
      (is (= ["{:label \"unterminated"] dropped)))))

(deftest parse-overrides-param*-blank-input
  (testing "rf2-9jthx — blank/nil input returns the empty-shape map so
            callers don't have to nil-check before destructuring"
    (is (= {:overrides nil :dropped []}
           (rf.story.share/parse-overrides-param* nil)))
    (is (= {:overrides nil :dropped []}
           (rf.story.share/parse-overrides-param* "")))
    (is (= {:overrides nil :dropped []}
           (rf.story.share/parse-overrides-param* "   ")))))

(deftest parse-overrides-param*-silent-drop
  (testing "rf2-9jthx — parse-overrides-param (silent-drop) keeps its
            signature. The share UI hydrator switches to
            parse-overrides-param* so the dropped count surfaces; other
            call sites that don't care about it keep working"
    (is (= {:label "OK"}
           (rf.story.share/parse-overrides-param "{:label \"OK\", 5 :bad-key}")))))

(deftest parse-overrides-param*-edn-map-form
  (testing "rf2-j0hwf — parse-overrides-param* reads the EDN-map wire form
            (one printed map) and reports keys it cannot coerce to a
            keyword as :dropped, so the share-import hint still surfaces
            on the new delimiter-safe encoding"
    (let [{:keys [overrides dropped]}
          (rf.story.share/parse-overrides-param* "{:label \"OK\", :size 7}")]
      (is (= {:label "OK" :size 7} overrides))
      (is (= [] dropped) "clean EDN map drops nothing"))
    (let [{:keys [overrides dropped]}
          (rf.story.share/parse-overrides-param* "{:label \"OK\", 5 :bad-key}")]
      (is (= {:label "OK"} overrides) "non-keyword key dropped, rest kept")
      (is (= 1 (count dropped))))
    (let [{:keys [overrides dropped]}
          (rf.story.share/parse-overrides-param* "{:label \"unterminated")]
      (is (nil? overrides) "unreadable EDN payload yields no overrides")
      (is (= 1 (count dropped)) "whole token reported dropped"))))

;; ---- rf2-76l69l: stale-key overrides are dropped + reported --------------
;;
;; `parse-overrides-param*` only drops UNPARSEABLE entries; a perfectly
;; well-formed override for an arg the variant RENAMED / REMOVED parses fine
;; and — without the second-stage `drop-stale-overrides` filter — would be
;; installed as a live arg and merged by `args/resolve-args`, hiding the
;; share-import drift. This filter splits parsed overrides against the
;; variant's declared-key contract.

(deftest drop-stale-overrides-splits-by-declared-keys
  (testing "rf2-76l69l — drop-stale-overrides keeps overrides whose key the
            variant still declares and moves the rest (renamed/removed args)
            into :dropped, preserving the parser's own malformed drops"
    (let [parsed {:overrides {:label "Hi" :gone 9 :count 3}
                  :dropped   ["bogus"]}
          out    (rf.story.share/drop-stale-overrides parsed #{:label :count})]
      (is (= {:label "Hi" :count 3} (:overrides out))
          "only declared keys survive")
      (is (= 2 (count (:dropped out)))
          "the parser's malformed drop PLUS the one stale-key drop")
      (is (some #(= "bogus" %) (:dropped out))
          "the parser's malformed token is preserved")
      (is (some #(re-find #":gone" %) (:dropped out))
          "the stale :gone override is reported as dropped, not installed"))))

(deftest drop-stale-overrides-all-stale-nils-overrides
  (testing "rf2-76l69l — when every parsed override is stale, :overrides is
            nil (not an empty map) and each stale key is reported"
    (let [out (rf.story.share/drop-stale-overrides
                {:overrides {:old-a 1 :old-b 2} :dropped []}
                #{:current})]
      (is (nil? (:overrides out)) "all-stale collapses to nil overrides")
      (is (= 2 (count (:dropped out)))))))

(deftest drop-stale-overrides-nil-declared-keeps-all
  (testing "rf2-76l69l — a nil declared-key set (unregistered / uncompilable
            variant: no contract known) keeps every parsed override verbatim
            rather than dropping all — degrades to the parser's behaviour"
    (let [parsed {:overrides {:a 1 :b 2} :dropped ["bad"]}
          out    (rf.story.share/drop-stale-overrides parsed nil)]
      (is (= {:a 1 :b 2} (:overrides out)))
      (is (= ["bad"] (:dropped out))))))

(deftest drop-stale-overrides-empty-declared-drops-all
  (testing "rf2-76l69l — an EMPTY (but non-nil) declared-key set means the
            variant declares NO args, so every override is stale (distinct
            from the nil keep-all case)"
    (let [out (rf.story.share/drop-stale-overrides
                {:overrides {:a 1} :dropped []}
                #{})]
      (is (nil? (:overrides out)))
      (is (= 1 (count (:dropped out)))))))

(deftest variant-share-url-preserves-hash-route
  (testing "variant-share-url inserts params before # so the Story route survives"
    (let [url (rf.story.share/variant-share-url
                :story.counter/loaded
                "https://example.test/counter-with-stories/#/stories"
                {:active-modes   [:Mode.app/dark]
                 :cell-overrides {:label "Share Slice"}
                 :substrate      :reagent})]
      (is (str/starts-with?
            url
            "https://example.test/counter-with-stories/?variant=story.counter%2Floaded"))
      (is (str/includes? url "&modes=Mode.app%2Fdark"))
      ;; rf2-j0hwf: overrides encode as one pr-str EDN map (delimiter-safe),
      ;; URL-encoded: {:label "Share Slice"} → %7B%3Alabel+%22Share+Slice%22%7D.
      (is (str/includes? url "overrides=%7B%3Alabel+%22Share+Slice%22%7D"))
      (is (str/ends-with? url "#/stories")))))

(deftest variant-share-url-public-export
  (testing "rf.story/variant-share-url is exported"
    (let [url (rf.story/variant-share-url
                :story.foo/bar
                "https://x.test/"
                {:active-modes [:Mode.x/y]})]
      (is (str/starts-with? url "https://x.test/?"))
      (is (re-find #"variant=" url))
      (is (re-find #"modes=" url)))))

;; ---- No QR endpoint --------------------------------------------------------
;;
;; rf2-20w5i (security audit) + rf2-ymnfx Issue B: the per-variant
;; Share button + QR popover were retired outright — the variant URL
;; is the browser's live address-bar URL (`url-state` pushState).
;; This ns must therefore expose neither the legacy third-party QR
;; endpoint Vars nor the vendored local encoder Vars from `rf.story.share/`
;; (the encoder ns `re-frame.story.qr` itself is gone). The literal
;; api.qrserver.com must not appear in the share module — a future
;; regression that re-introduced a third-party QR fetch trips here.

(deftest no-third-party-qr-endpoint
  (testing "share namespace exposes no QR-endpoint Var. Pre-rf2-20w5i
            `qr-endpoint` / `qr-image-url` built URLs against
            api.qrserver.com; rf2-20w5i eliminated those; rf2-ymnfx
            Issue B then retired the QR popover entirely. The
            assertions remain as a regression gate."
    (is (nil? (resolve 'rf.story.share/qr-endpoint)))
    (is (nil? (resolve 'rf.story.share/qr-image-url)))))

(deftest no-qrserver-literal-in-share-source
  (testing "share.cljc carries no `api.qrserver.com` URL literal — the
            string must not appear in the source so a future regression
            (someone copy-pasting the old endpoint back in) is caught."
    (let [src (slurp (clojure.java.io/resource "re_frame/story/share.cljc"))]
      (is (not (str/includes? src "api.qrserver.com"))
          "share.cljc must not reference api.qrserver.com"))))
