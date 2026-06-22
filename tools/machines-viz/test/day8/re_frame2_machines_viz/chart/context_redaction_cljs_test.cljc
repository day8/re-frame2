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
