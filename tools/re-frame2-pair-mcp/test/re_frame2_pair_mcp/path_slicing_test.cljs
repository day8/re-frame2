(ns re-frame2-pair-mcp.path-slicing-test
  "The path vocabulary `snapshot`'s `:path` arg shares with `get-path`, the
  `{:rf.mcp/summary ...}` marker that replaces an unsliced `:app-db` slice,
  and the `:path-not-found` breadcrumb."
  (:require [cljs.test :refer-macros [deftest is]]
            [re-frame2-pair-mcp.tools.args :as args]
            [re-frame2-pair-mcp.tools.snapshot-pipeline :as pipeline]
            [re-frame2-pair-mcp.tools.summary :as summary]))

(deftest parse-path-arg-input-shapes
  ;; JS-array segments parse one at a time as EDN, so a bare word stays a
  ;; string map key; anything the reader rejects becomes a string segment.
  (doseq [[input expected why]
          [[nil                           nil                  "nil ⇒ no path"]
           ["   "                         nil                  "a blank string ⇒ no path"]
           ["[:cart :items 0]"            [:cart :items 0]     "an EDN vector string with an index"]
           ["[]"                          []                   "an empty EDN vector is the root"]
           [[:a :b :c]                    [:a :b :c]           "a CLJS vector passes through"]
           [(list :a :b :c)               [:a :b :c]           "a CLJS sequential coerces to a vector"]
           [#js [":cart" "bare-key" "0"]  [:cart "bare-key" 0] "JS-array segments parse as EDN; a bare word stays a string"]
           [#js ["[" "]"]                 ["[" "]"]            "unparseable JS-array segments stay strings"]
           [":foo"                        [:foo]               "a lone EDN scalar is a 1-segment path"]
           ["((("                         ["((("]              "an unparseable string is one string segment"]]]
    (is (= expected (args/parse-path-arg input)) why)))

;; ---------------------------------------------------------------------------
;; tree-summary
;; ---------------------------------------------------------------------------

(def ^:private utf8-discriminating-entry
  "Three U+2014 EM DASH (1 code unit, 3 UTF-8 bytes each) and one astral
  U+1D11E (2 code units, 4 bytes): code units, code points and UTF-8 bytes
  are three different numbers."
  "\u2014\u2014\u2014\uD834\uDD1E")

(deftest tree-summary-bytes-counts-utf8-bytes-not-code-units
  ;; `:bytes` shares the cross-MCP vocabulary with `:rf.size/large-elided`.
  ;; A sampled entry is max(8, UTF-8 bytes of its pr-str) = 15 here, where
  ;; code units would give 8; a map adds 16 bytes of key overhead. The
  ;; expectations are literals, so ASCII-fying the fixture reds them.
  (let [e utf8-discriminating-entry]
    (doseq [[v marker] [[[e e e e] {:type :vector :count 4 :bytes 60}]
                        [(list e)  {:type :seq :count 1 :bytes 15}]
                        [{:k e}    {:type :map :keys [:k] :count 1 :bytes 31}]]]
      (is (= {:rf.mcp/summary marker} (summary/tree-summary v))))))

(deftest tree-summary-map-truncates-huge-key-lists
  ;; A 5k-entry map's key list alone would blow the wire cap.
  (let [marker (:rf.mcp/summary (summary/tree-summary
                                  (zipmap (map #(keyword (str "k" %)) (range 5000))
                                          (repeat :_))))]
    (is (= {:type :map :count 5000 :keys-truncated? true}
           (select-keys marker [:type :count :keys-truncated?])))
    (is (= summary/summary-keys-cap (count (:keys marker))))))

(deftest tree-summary-scalar-returns-value-unchanged
  ;; A scalar already fits the cap; a marker would only add tokens.
  (is (= 42 (summary/tree-summary 42))))

(deftest deepest-valid-prefix-walks-to-the-last-resolvable-step
  (let [auth  {:user {:auth {:token "abc"}}}
        items {:items [:apple :banana :cherry]}]
    (doseq [[db path expected note]
            [[auth [:user :auth :missing] [:user :auth] "a missing map key stops at its parent"]
             [auth [:missing] [] "a missing first key resolves nothing"]
             [items [:items 1] [:items 1] "a vector index in range"]
             [items [:items 99] [:items] "an out-of-range index stops at the vector"]
             [items [:items :nope] [:items] "a non-integer key on a vector stops at the vector"]
             [{:k nil} [:k] [:k] "a path to a nil leaf is valid"]]]
      (is (= expected (summary/deepest-valid-prefix db path)) note))))

;; ---------------------------------------------------------------------------
;; slice-app-db-in-snapshot — snapshot's `:app-db` post-processing.
;; ---------------------------------------------------------------------------

(def ^:private fixture-snapshot
  {:rf/default {:app-db    {:user {:profile {:name "alice"}}
                            :cart {:items [{:sku "A1"} {:sku "A2"}]
                                   :total 42}}
                :sub-cache {[:user/email] {:value "a@b" :ref-count 1}}
                :machines  {:ids [] :state {}}
                :epochs    []
                :traces    []}
   :stories    {:app-db    {:story-id 7}
                :sub-cache {}
                :machines  {:ids [] :state {}}
                :epochs    []
                :traces    []}})

(deftest snapshot-without-path-summarises-app-db
  ;; Which slices get summarised is pinned here; the marker itself above.
  (is (= [(-> fixture-snapshot
              (update-in [:rf/default :app-db] summary/tree-summary)
              (update-in [:stories :app-db] summary/tree-summary))
          {}]
         (pipeline/slice-app-db-in-snapshot fixture-snapshot nil :summary))))

(deftest snapshot-with-path-returns-subtree
  ;; A frame the path misses gets a nil slice and a `:path-not-found` breadcrumb.
  (is (= [(-> fixture-snapshot
              (assoc-in [:rf/default :app-db] {:name "alice"})
              (assoc-in [:stories :app-db] nil))
          {:stories {:exists? false :deepest-valid-prefix []}}]
         (pipeline/slice-app-db-in-snapshot fixture-snapshot [:user :profile] :summary)))
  (is (= {:exists? false :deepest-valid-prefix [:user]}
         (:rf/default (second (pipeline/slice-app-db-in-snapshot
                                fixture-snapshot [:user :auth :token] :summary))))))

(deftest snapshot-with-empty-path-returns-full-app-db
  ;; The root path is the agent opting in to the full slice.
  (is (= [fixture-snapshot {}]
         (pipeline/slice-app-db-in-snapshot fixture-snapshot [] :summary))))

(deftest snapshot-skips-frames-without-app-db
  ;; An `:include` that leaves out `:app-db` must not gain one.
  (let [snap {:f1 {:sub-cache {} :epochs []}}]
    (is (= [snap {}] (pipeline/slice-app-db-in-snapshot snap nil :summary)))
    (is (= [snap {}] (pipeline/slice-app-db-in-snapshot snap [:foo] :summary)))))
