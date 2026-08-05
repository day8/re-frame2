(ns day8.re-frame2-machines-viz.chart.context-redaction-cljs-test
  "EP-0015 local-redacted Context-band projection (rf2-27e38h).

  Pins the contract that a host feeding LIVE machine `:data` into the
  Context band cannot leak a schema-marked sensitive or large slot into
  the SVG / PNG / clipboard export: the band defaults to a local-redacted
  projection, and the redacted display text is content-FREE. Pure `.cljc`
  → the JVM corpus pins it without a browser."
  (:require #?(:clj  [clojure.test :refer [deftest is testing]]
               :cljs [cljs.test    :refer-macros [deftest is testing]])
            [clojure.string :as str]
            [day8.re-frame2-machines-viz.chart.context-redaction :as r]))

;; ---------------------------------------------------------------------------
;; derive-classification — reads :sensitive? / :large? slot props off a
;; machine's [:schemas :data] schema (EP-0005 / EP-0029 A3 plain-data walk).

(deftest derive-classification-reads-slot-props
  (testing "sensitive? / large? slot props become the classification sets"
    (let [schema [:map
                  [:user/email {:sensitive? true} :string]
                  [:auth/token {:sensitive? true} :string]
                  [:receipt    {:large? true}     :string]
                  [:count      :int]]
          {:keys [sensitive large]} (r/derive-classification schema)]
      (is (= #{:user/email :auth/token} sensitive))
      (is (= #{:receipt} large)))))

(deftest derive-classification-unwraps-refinement
  (testing "a :map nested under :and is still walked (declared-but-wrapped)"
    (let [schema [:and [:map [:secret {:sensitive? true} :string]] [:fn 'map?]]
          {:keys [sensitive]} (r/derive-classification schema)]
      (is (= #{:secret} sensitive)))))

(deftest derive-classification-absent-schema-is-empty
  (testing "no schema / non-map schema → empty sets (band passes through)"
    (is (= {:sensitive #{} :large #{}} (r/derive-classification nil)))
    (is (= {:sensitive #{} :large #{}} (r/derive-classification :int)))))

;; ---------------------------------------------------------------------------
;; redact-value / redact-context — the projection itself

(deftest redact-value-sensitive-becomes-sentinel
  (testing "a sensitive key projects to :rf/redacted regardless of value"
    (is (= :rf/redacted (r/redact-value :tok "hunter2" {:sensitive #{:tok}})))))

(deftest redact-value-large-becomes-elided-no-head
  (testing "a large key elides to the canonical :rf.size/large-elided marker with NO content head"
    (let [big (apply str (repeat 50 "x"))
          out (r/redact-value :blob big {:large #{:blob}})]
      (is (map? out))
      (is (contains? out :rf.size/large-elided))
      (let [body (:rf.size/large-elided out)]
        (is (number? (:bytes body)))
        (is (= [:blob] (:path body)) "path is the single context key")
        (is (= :string (:type body)))
        (is (not (contains? body :head)) "no content head leaks")))))

(deftest redact-value-large-heuristic-fires-for-unmarked-big-value
  (testing "an unmarked value over the char cap is still elided as large"
    (let [big (apply str (repeat 1000 "y"))
          out (r/redact-value :unmarked big {})]
      (is (contains? out :rf.size/large-elided) "defensive size guard fires"))))

(deftest redact-value-sensitive-wins-over-large
  (testing "a key that is BOTH sensitive and large redacts as sensitive"
    (is (= :rf/redacted
           (r/redact-value :k (apply str (repeat 1000 "z"))
                           {:sensitive #{:k} :large #{:k}})))))

(deftest redact-value-passes-ordinary-through
  (testing "an unclassified small value is unchanged"
    (is (= 42 (r/redact-value :count 42 {})))
    (is (= [:a :b] (r/redact-value :seen [:a :b] {})))))

(deftest redact-context-projects-the-whole-band
  (testing "the band map is redacted slot-by-slot, order preserved"
    (let [band  (array-map :name "Alice" :token "secret-tok" :count 3)
          out   (r/redact-context band {:sensitive #{:token}})]
      (is (= "Alice"      (:name out)))
      (is (= :rf/redacted (:token out)))
      (is (= 3            (:count out)))
      (is (= [:name :token :count] (keys out)) "order preserved"))))

(deftest redact-context-empty-is-nil
  (testing "nil / empty band → nil (band hidden)"
    (is (nil? (r/redact-context nil {})))
    (is (nil? (r/redact-context {} {})))))

;; ---------------------------------------------------------------------------
;; rf2-2rtt6.132 - TWO units, side by side, each honest about what it bounds.
;;
;; The marker's `:bytes` slot is the framework's wire vocabulary and now
;; carries UTF-8 BYTES; the large heuristic's `large-char-cap` is what the
;; band would PAINT and stays in CHARACTERS. Before this bead both were
;; `(count (pr-str v))` - UTF-16 code units - so the published figure lied
;; by up to 3x (4x on astral code points) while the cap was fine.
;;
;; ASCII is the fail-open condition: the two rulers agree there EXACTLY,
;; so every ASCII fixture above measured the right number by accident.
;; The fixture here is DISCRIMINATING - code units, code points and bytes
;; are three different numbers - and is asserted so BEFORE it is used.
;; Written as \uXXXX escapes so the source stays pure ASCII, and as a
;; `.cljc` so BOTH hosts are proven: the JVM arm is `String.getBytes`,
;; the CLJS arm is `TextEncoder`.

(def ^:private utf8-discriminating-value
  "Twenty U+2014 EM DASH (1 code unit / 1 code point / 3 UTF-8 bytes each)
  followed by one ASTRAL U+1D11E (2 code units / 1 code point / 4 bytes)."
  (str (apply str (repeat 20 "\u2014")) "\uD834\uDD1E"))

(def ^:private ascii-control-value
  "Same CODE-UNIT length as `utf8-discriminating-value`, pure ASCII."
  (apply str (repeat 22 "x")))

(defn- utf8-len [s]
  #?(:clj  (alength (.getBytes ^String s "UTF-8"))
     :cljs (let [^js enc (js/TextEncoder.)]
             (.-length (.encode enc s)))))

(defn- code-points [s]
  #?(:clj  (.codePointCount ^String s 0 (count s))
     :cljs (count (js/Array.from s))))

(deftest redaction-byte-fixture-is-discriminating
  (testing "code units, code points and UTF-8 bytes are three different numbers"
    (is (= 22 (count utf8-discriminating-value)))
    (is (= 21 (code-points utf8-discriminating-value)))
    (is (= 64 (utf8-len utf8-discriminating-value))))
  (testing "the ASCII control's two rulers agree exactly - the fail-open condition"
    (is (= 22 (count ascii-control-value)))
    (is (= 22 (utf8-len ascii-control-value)))))

(deftest large-marker-bytes-counts-utf8-bytes-not-code-units
  (testing "a schema-marked large value publishes UTF-8 bytes"
    ;; `pr-str` wraps the string in two quote characters, so the printed
    ;; form is 24 code units / 66 UTF-8 bytes. The old expression
    ;; published 24; the slot means bytes, so it must publish 66.
    (let [body (-> (r/redact-value :blob utf8-discriminating-value {:large #{:blob}})
                   :rf.size/large-elided)]
      (is (= 66 (:bytes body)) "UTF-8 bytes of the pr-str form")
      (is (not= 24 (:bytes body)) "NOT the 24 UTF-16 code units of the same form")))
  (testing "an ASCII value of the same code-unit length is unchanged by the correction"
    (let [body (-> (r/redact-value :blob ascii-control-value {:large #{:blob}})
                   :rf.size/large-elided)]
      (is (= 24 (:bytes body)) "both rulers agree on ASCII, so this number never moved"))))

(deftest large-char-cap-is-characters-and-did-not-move
  ;; The correction is to the PUBLISHED figure only. Had the cap been
  ;; converted too, this 400-character value (1,200 UTF-8 bytes) would
  ;; have started eliding where it used to render inline - a live
  ;; behaviour change on non-ASCII context. It does not.
  (let [four-hundred-dashes (apply str (repeat 400 "\u2014"))]
    (is (= 400 (count four-hundred-dashes)))
    (is (= 1200 (utf8-len four-hundred-dashes)) "well over the 512 cap in BYTES")
    (is (= four-hundred-dashes (r/redact-value :ctx four-hundred-dashes {}))
        "under the 512-CHARACTER cap, so it still renders inline"))
  (testing "the cap still fires on a genuinely long value"
    (let [six-hundred (apply str (repeat 600 "x"))
          out         (r/redact-value :ctx six-hundred {})]
      (is (contains? out :rf.size/large-elided))
      (is (= 602 (:bytes (:rf.size/large-elided out)))
          "602 = 600 + two pr-str quotes, identical under either ruler on ASCII"))))

;; ---------------------------------------------------------------------------
;; display-string — the content-FREE export-safe text

(deftest display-string-redacted-is-content-free
  (testing ":rf/redacted renders a content-free sentinel"
    (let [s (r/display-string :rf/redacted)]
      (is (str/includes? s ":rf/redacted")))))

(deftest display-string-large-shows-size-not-content
  (testing ":rf.size/large-elided renders size only, never the content"
    (let [s (r/display-string {:rf.size/large-elided {:bytes 4096 :path [:blob]
                                                      :type :string :reason :schema}})]
      (is (str/includes? s ":rf.size/large-elided"))
      (is (str/includes? s "4096"))
      (is (not (str/includes? s "xxxx")) "no content head"))))

(deftest display-string-ordinary-is-pr-str
  (testing "ordinary values still render via pr-str (unchanged behaviour)"
    (is (= "3"      (r/display-string 3)))
    (is (= "[:a :b]" (r/display-string [:a :b])))))

;; ---------------------------------------------------------------------------
;; The end-to-end leak guard: a secret-bearing live value, projected with
;; its schema's sensitivity, never produces display text carrying the
;; secret — so it cannot reach the serialised SVG/PNG/clipboard.

(deftest secret-never-survives-into-display-text
  (testing "a sensitive live :data slot never appears in the band display text"
    (let [secret "card-4111-1111-1111-1111"
          schema [:map [:card {:sensitive? true} :string] [:count :int]]
          cls    (r/derive-classification schema)
          band   (array-map :card secret :count 7)
          rows   (->> (r/redact-context band cls)
                      (mapv (fn [[k v]] [(str (symbol k)) (r/display-string v)])))
          texts  (mapcat identity rows)]
      (is (not (some #(str/includes? % secret) texts))
          "the secret must not appear in any display row")
      ;; the non-sensitive slot still renders its value
      (is (some #(= "7" %) texts) ":count still rendered"))))
