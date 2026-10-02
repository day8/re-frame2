(ns re-frame.story-share-test
  "JVM tests for the per-variant share URL builder.

  The URL-building logic lives in `re-frame.story.share` (.cljc) so
  the same encoding works on JVM and CLJS. JVM tests round-trip the
  expected shape per `005-SOTA-Features.md` §Share URL (retired QR popover)."
  (:require [clojure.test :refer [are deftest is testing]]
            [clojure.string :as str]
            [re-frame.story        :as rf.story]
            [re-frame.story.share  :as rf.story.share]))

;; ---- owned keys REPLACE stale base-url values ----------------------------
;;
;; The browser hydrator (`re-frame.story.ui.url-state/params->getter`)
;; reads each Story key with `URLSearchParams.get`, whose FIRST-value
;; semantics select the oldest occurrence in the query string. An
;; append-only merge over a base-url that already carries `variant=` /
;; `modes=` would therefore hydrate the STALE cell — violating the share
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

;; ---- OMITTED slots clear their stale values ------------------------------
;;
;; `build-params` deliberately omits empty / nil / default slots, so a
;; builder clearing only the keys a call EMITS would let a base-url's
;; `modes=` / `overrides=` / `substrate=` survive a call that requested
;; `[]` / `{}` / `:reagent` — the hydrator would then restore state the
;; caller never asked for, breaking the exact-cell invariant by OMISSION
;; rather than by order. The builder is therefore authoritative over the
;; whole `rf.story.share/story-query-keys` vocabulary, including the slots
;; it declines to emit.

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
  (testing "the clear set is only correct while it equals what
            build-params can emit. A slot added to build-params without a
            matching story-query-keys entry would silently leave that slot's
            stale value standing, so pin the two against each other."
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
  (testing "a base-url carrying stale values for keys this
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

;; ---- ownership compares DECODED key names --------------------------------
;;
;; The consumer is `URLSearchParams`, which compares key names after
;; percent-decoding, so `%76ariant=` — a valid spelling of `variant=` — is
;; the SAME key to the browser. Were the clear set matched against the
;; fragment's RAW key text, it would be a different string to the builder:
;; the stale entry would survive, the generated `variant=` would be
;; appended behind it, and `.get` (first-value) would hand the hydrator the
;; stale value — the stale-cell failure above, reached through the key
;; half instead of the value half.

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

(deftest variant-share-url-owns-percent-encoded-key-spellings
  (testing "a base-url spelling the Story keys with
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
  (testing "decoding is an ownership TEST, never a
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

;; ---- overrides codec round-trip ------------------------------------------
;;
;; The codec prints one EDN map (delimiter-safe) and reads it back as one
;; map, so an EDN value containing the list separator round-trips faithfully.

(defn- url-decode [t] (java.net.URLDecoder/decode (str t) "UTF-8"))

(defn- overrides-round-trip
  "Encode `ov` to the wire token, URL-decode it (as URLSearchParams.get
  would), and parse it back. Returns the reconstructed overrides map."
  [ov]
  (rf.story.share/parse-overrides-param (url-decode (rf.story.share/build-overrides-token ov))))

;; ---- substrate id round-trips namespace ----------------------------------

(deftest substrate-round-trips-qualified
  (testing "a qualified substrate id round-trips through
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

(deftest overrides-codec-round-trips-comma-value
  (testing "a string override value containing the list
            separator (comma) round-trips faithfully instead of being
            shredded into malformed entries and dropped"
    (let [ov {:label "Save, continue"}]
      (is (= ov (overrides-round-trip ov))
          "comma-containing string value survives the round-trip"))
    (testing "comma value alongside other entries"
      (let [ov {:label "Save, continue" :count 3 :title "A, B, C"}]
        (is (= ov (overrides-round-trip ov)))))))

(deftest overrides-codec-round-trips-collection-values
  (testing "vector / map / set / nested EDN values (which all
            carry internal separators) round-trip"
    (let [ov {:items [1 2 3]
              :opts  {:a 1 :b 2}
              :tags  #{:x :y}
              :pair  [:k "v, with comma"]}]
      (is (= ov (overrides-round-trip ov))))))

(deftest overrides-codec-empty-and-nil
  (testing "empty/nil overrides produce no token, and blank
            input parses to nil"
    (is (nil? (rf.story.share/build-overrides-token {})))
    (is (nil? (rf.story.share/build-overrides-token nil)))
    (is (nil? (rf.story.share/parse-overrides-param nil)))
    (is (nil? (rf.story.share/parse-overrides-param "")))))

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

(deftest parse-overrides-param-silent-drop
  (testing "parse-overrides-param is the silent-drop form, returning the
            overrides map alone. The share UI hydrator uses
            parse-overrides-param* so the dropped count surfaces"
    (is (= {:label "OK"}
           (rf.story.share/parse-overrides-param "{:label \"OK\", 5 :bad-key}")))))

;; ---- stale-key overrides are dropped + reported --------------------------
;;
;; `parse-overrides-param*` only drops UNPARSEABLE entries; a perfectly
;; well-formed override for an arg the variant RENAMED / REMOVED parses fine
;; and — without the second-stage `drop-stale-overrides` filter — would be
;; installed as a live arg and merged by `args/resolve-args`, hiding the
;; share-import drift. This filter splits parsed overrides against the
;; variant's declared-key contract.

(deftest drop-stale-overrides-splits-by-declared-keys
  (testing "drop-stale-overrides keeps overrides whose key the
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

(deftest drop-stale-overrides-nil-declared-keeps-all
  (testing "a nil declared-key set (unregistered / uncompilable
            variant: no contract known) keeps every parsed override verbatim
            rather than dropping all — degrades to the parser's behaviour"
    (let [parsed {:overrides {:a 1 :b 2} :dropped ["bad"]}
          out    (rf.story.share/drop-stale-overrides parsed nil)]
      (is (= {:a 1 :b 2} (:overrides out)))
      (is (= ["bad"] (:dropped out))))))

(deftest drop-stale-overrides-empty-declared-drops-all
  (testing "an EMPTY (but non-nil) declared-key set means the
            variant declares NO args, so every override is stale (distinct
            from the nil keep-all case)"
    (let [out (rf.story.share/drop-stale-overrides
                {:overrides {:a 1} :dropped []}
                #{})]
      (is (nil? (:overrides out)))
      (is (= 1 (count (:dropped out)))))))

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
  (testing "rf.story/variant-share-url is exported"
    (is (= "https://x.test/?variant=story.foo%2Fbar&modes=Mode.x%2Fy"
           (rf.story/variant-share-url
             :story.foo/bar
             "https://x.test/"
             {:active-modes [:Mode.x/y]})))))

(deftest variant-share-url-public-export-opts-arity
  (testing "the facade carries the documented (variant-id opts)
            arm: a no-base query fragment, with no leading ?"
    (is (= "variant=story.foo%2Fbar&modes=Mode.x%2Fy"
           (rf.story/variant-share-url :story.foo/bar {:active-modes [:Mode.x/y]})))))

;; ---- No QR endpoint --------------------------------------------------------
;;
;; There is no per-variant Share button or QR popover — the variant URL
;; is the browser's live address-bar URL (`url-state` pushState). So
;; `rf.story.share/` exposes no QR endpoint Var and no QR encoder Var, and
;; there is no QR encoder ns. The literal api.qrserver.com, a third-party
;; QR-image service that would receive the full share URL, must not
;; appear in the share module; a third-party QR fetch in it trips here.

(deftest no-third-party-qr-endpoint
  (testing "share namespace exposes no QR-endpoint Var — no
            `qr-endpoint` / `qr-image-url` building URLs against
            api.qrserver.com."
    ;; `ns-resolve` against the share ns itself: a bare `resolve` reads
    ;; `*ns*`, which at run time is the runner's namespace, where the
    ;; `rf.story.share` alias does not exist, so it returns nil whether
    ;; or not the Var is defined.
    (is (nil? (ns-resolve 're-frame.story.share 'qr-endpoint)))
    (is (nil? (ns-resolve 're-frame.story.share 'qr-image-url)))))

(deftest no-qrserver-literal-in-share-source
  (testing "share.cljc carries no `api.qrserver.com` URL literal — the
            string must not appear in the source, so a pasted-in
            third-party endpoint is caught."
    (let [src (slurp (clojure.java.io/resource "re_frame/story/share.cljc"))]
      (is (not (str/includes? src "api.qrserver.com"))
          "share.cljc must not reference api.qrserver.com"))))
