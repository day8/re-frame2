(ns day8.re-frame2-machines-viz.chart.context-redaction-cljs-test
  "EP-0015 local-redacted Context-band projection: a host feeding LIVE
  machine `:data` into the band cannot leak a declared sensitive or large
  slot into the SVG / PNG / clipboard export, and the redacted display text
  is content-free. The wiring through `xyflow-graph` is pinned in
  `context-redaction-wiring-cljs-test`."
  (:require #?(:clj  [clojure.test :refer [deftest is testing]]
               :cljs [cljs.test    :refer-macros [deftest is testing]])
            [clojure.string :as str]
            [day8.re-frame2-machines-viz.chart.context-redaction :as r]))

(deftest derive-classification-reads-declared-data-paths
  (testing "`[:data k …]` names band key `k` (a deeper path its whole top-level
            slot); a path not rooted at :data, and the `[:schemas :data]`
            `:sensitive?` / `:large?` props (EP-0025), classify nothing"
    (doseq [[definition expected]
            [[{:sensitive [[:data :user/email] [:data :auth/token]]
               :large     [[:data :receipt]]}
              {:sensitive #{:user/email :auth/token} :large #{:receipt}}]
             [{:sensitive [[:data :payment :token]]}
              {:sensitive #{:payment} :large #{}}]
             [{:sensitive [[:state]]}
              {:sensitive #{} :large #{}}]
             [{:schemas {:data [:map [:token {:sensitive? true} :string]
                                [:blob {:large? true} :string]]}}
              {:sensitive #{} :large #{}}]
             [nil
              {:sensitive #{} :large #{}}]]]
      (is (= expected (r/derive-classification definition)) (pr-str definition)))))

(deftest derive-classification-whole-data-path-keeps-whole-data-scope
  (testing "a bare `[:data]` path, or the whole-snapshot `[]`, classifies EVERY
            band key, including one the live :data first gains at runtime"
    (doseq [path [[:data] []]]
      (is (= {:token :rf/redacted :user :rf/redacted}
             (r/redact-context (array-map :token "secret-at-runtime" :user "ann")
                               (r/derive-classification {:sensitive [path] :data {}})))
          (pr-str path)))))

(deftest redact-context-projects-the-whole-band
  (testing "slot by slot, order preserved: sensitive → `:rf/redacted`, winning
            over large; large → the content-free `:rf.size/large-elided`
            marker (no head); everything else unchanged"
    (let [out (r/redact-context
                (array-map :name  "Alice"
                           :token "secret-tok"
                           :blob  (apply str (repeat 50 "x"))
                           :count 3)
                {:sensitive #{:token} :large #{:token :blob}})]
      (is (= {:name  "Alice"
              :token :rf/redacted
              :blob  {:rf.size/large-elided {:path [:blob] :bytes 52 :type :string :reason :schema}}
              :count 3}
             out))
      (is (= [:name :token :blob :count] (keys out)) "order preserved")))
  (testing "an empty band → nil (band hidden)"
    (is (nil? (r/redact-context {} {})))))

;; The marker's `:bytes` is the framework's wire unit, UTF-8 BYTES; the large
;; cap bounds what the band PAINTS, in CHARACTERS. The two agree exactly on
;; ASCII, so only a non-ASCII value can tell a wrong ruler from a right one.

(deftest large-marker-bytes-counts-utf8-bytes-not-code-units
  (testing "twenty U+2014 (3 bytes each) and one astral U+1D11E (4 bytes, 2
            code units), plus pr-str's two quotes: 66 UTF-8 bytes, where a
            code-unit count would publish 24"
    (is (= 66 (-> (r/redact-value :blob (str (apply str (repeat 20 "—")) "𝄞")
                                  {:large #{:blob}})
                  :rf.size/large-elided
                  :bytes)))))

(deftest large-char-cap-counts-characters-not-bytes
  (testing "400 em-dashes (1,200 UTF-8 bytes) sit under the 512-CHARACTER cap,
            so they render inline"
    (let [four-hundred-dashes (apply str (repeat 400 "—"))]
      (is (= four-hundred-dashes (r/redact-value :ctx four-hundred-dashes {})))))
  (testing "the cap fires on a genuinely long value"
    (is (= 602 (-> (r/redact-value :ctx (apply str (repeat 600 "x")) {})
                   :rf.size/large-elided
                   :bytes))
        "602 = 600 + two pr-str quotes")))

(deftest display-string-large-shows-size-not-content
  (testing ":rf.size/large-elided renders size only, never the content"
    (let [s (r/display-string {:rf.size/large-elided {:bytes 4096 :path [:blob]
                                                      :type :string :reason :schema}})]
      (is (str/includes? s ":rf.size/large-elided"))
      (is (str/includes? s "4096")))))
