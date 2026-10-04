(ns re-frame2-pair-mcp.path-slicing-test
  "Unit tests for the path-slicing surfaces.

  Two MCP surfaces share the same path vocabulary:

    - The `snapshot` tool carries a `:path` arg that slices the
      `:app-db` slice. Without `:path`, the `:app-db` slice is
      replaced by a `{:rf.mcp/summary ...}` marker (default mode
      `:summary`) so the response stays under the wire cap.
    - The `get-path` tool returns the value at a single path — a
      minimal primitive for targeted reads.

  Tests pin the public helpers directly from their owning namespaces:
  `tools.args/parse-path-arg`, `tools.summary/tree-summary`,
  `tools.summary/deepest-valid-prefix`,
  `tools.snapshot-pipeline/slice-app-db-in-snapshot`,
  `test-utils/token-estimate`. A rename or signature change surfaces
  as a failing test rather than a silent contract drift.

  Live end-to-end coverage of `get-path-tool` and the snapshot
  `:path` arg lives in `test/stdio-roundtrip.js` (degraded-mode
  dispatch) and the manual live-nREPL integration test."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [re-frame2-pair-mcp.test-utils :as tu]
            [re-frame2-pair-mcp.tools.args :as args]
            [re-frame2-pair-mcp.tools.snapshot-pipeline :as pipeline]
            [re-frame2-pair-mcp.tools.summary :as summary]))

;; ---------------------------------------------------------------------------
;; parse-path-arg — the input-shape contract.
;; ---------------------------------------------------------------------------

(deftest parse-path-arg-input-shapes
  ;; Each row: the input, the path it parses to, and why. JS-array
  ;; segments are parsed one at a time as EDN, so a bare string stays a
  ;; string map key; anything the EDN reader rejects falls back to a
  ;; single string segment rather than raising.
  (doseq [[input expected why]
          [[nil                         nil              "nil ⇒ no path"]
           [""                          nil              "a blank string ⇒ no path"]
           ["   "                       nil              "a whitespace-only string ⇒ no path"]
           ["[:cart :items 0]"          [:cart :items 0] "an EDN vector string with an index"]
           ["[:a :b :c]"                [:a :b :c]       "an EDN vector string of keywords"]
           ["[]"                        []               "an empty EDN vector is the root"]
           [[:a :b :c]                  [:a :b :c]       "a CLJS vector passes through"]
           [[]                          []               "an empty CLJS vector passes through"]
           [(list :a :b :c)             [:a :b :c]       "a CLJS sequential coerces to a vector"]
           [#js [":cart" ":items" "0"]  [:cart :items 0] "each JS-array segment parses as EDN"]
           [#js [":a" "bare-key" ":b"]  [:a "bare-key" :b] "a non-EDN JS-array segment stays a string map key"]
           [":foo"                      [:foo]           "a lone keyword string is a 1-segment path"]
           ["42"                        [42]             "a bare integer string is a 1-segment path"]
           ["((("                       ["((("]          "an unparseable string is one string segment"]
           [#js ["[" "]"]               ["[" "]"]        "unparseable JS-array segments stay strings"]]]
    (is (= expected (args/parse-path-arg input)) why)))

;; ---------------------------------------------------------------------------
;; tree-summary — the {:rf.mcp/summary ...} marker shape.
;; ---------------------------------------------------------------------------

(deftest tree-summary-classifies-a-seq
  ;; The set case is pinned with its count and bytes in
  ;; set_valued_app_db_test.
  (is (= :seq (-> (summary/tree-summary (list 1 2 3)) :rf.mcp/summary :type))))

;; ---------------------------------------------------------------------------
;; The marker's `:bytes` slot counts UTF-8 BYTES.
;;
;; `:bytes` is the slot the cross-MCP wire vocabulary shares with
;; `{:rf.size/large-elided ...}`. A `sample-entry-bytes` of
;; `(count (pr-str sample))` would multiply UTF-16 CODE UNITS up into it
;; instead. The two rulers agree EXACTLY on ASCII, so an ASCII-only suite
;; would stay green over that defect: every fixture above is ASCII, so the
;; wrong expression would print the right number.
;;
;; The fixture below is DISCRIMINATING by construction - code units, code
;; points and UTF-8 bytes are three different numbers - and the expected
;; byte counts are literals, so a future editor that ASCII-fies the source
;; reds them rather than silently neutering the pin. It is written as
;; \uXXXX escapes so this file stays pure ASCII.
;;
;; Both directions are pinned: the non-ASCII entry DIVERGES (the estimate
;; nearly doubles), and an ASCII entry of the SAME code-unit length AGREES
;; with what a code-unit ruler would produce.
;; ---------------------------------------------------------------------------

(def ^:private utf8-discriminating-entry
  "Three U+2014 EM DASH (1 code unit / 1 code point / 3 UTF-8 bytes each)
  followed by one ASTRAL U+1D11E (2 code units / 1 code point / 4 bytes)."
  "\u2014\u2014\u2014\uD834\uDD1E")

(def ^:private ascii-control-entry
  "Same CODE-UNIT length as `utf8-discriminating-entry`, pure ASCII - so
  the two rulers must agree on it exactly."
  "aaaaa")

(deftest tree-summary-bytes-counts-utf8-bytes-not-code-units
  ;; All FOUR emit sites in `summary.cljs` - map / vector / set / seq.
  ;;
  ;; Sampled per-entry size is `(max 8 (utf8-bytes (pr-str sample)))`:
  ;;   non-ASCII entry -> max(8, 15) = 15   (code units would give max(8, 7) = 8)
  ;;   ASCII control   -> max(8,  7) =  8   (identical under either ruler)
  ;; Maps add the 16-byte key overhead on top.
  (let [bytes-of #(-> % summary/tree-summary :rf.mcp/summary :bytes)
        e        utf8-discriminating-entry
        a        ascii-control-entry]
    (testing "vector"
      (is (= 60 (bytes-of [e e e e])) "4 x 15 UTF-8 bytes")
      (is (= 32 (bytes-of [a a a a])) "4 x the 8-byte floor - ASCII agrees"))
    (testing "set"
      (is (= 15 (bytes-of #{e})))
      (is (= 8  (bytes-of #{a}))))
    (testing "seq"
      (is (= 15 (bytes-of (list e))))
      (is (= 8  (bytes-of (list a)))))
    (testing "map - the 16-byte key overhead plus the sampled value"
      (is (= 31 (bytes-of {:k e})) "16 + 15")
      (is (= 24 (bytes-of {:k a})) "16 + 8"))
    (testing "UTF-8 bytes can only ever RAISE the estimate over code units"
      ;; UTF-8 bytes are never fewer than UTF-16 code units, so the byte
      ;; ruler never reports a slice as smaller than a code-unit ruler
      ;; would. Nothing in this server gates on the figure.
      (is (> (bytes-of [e e e e]) (bytes-of [a a a a]))
          "same code-unit length, larger byte estimate"))))

(deftest tree-summary-map-truncates-huge-key-lists
  ;; A 5k-entry map's key list alone would blow the cap. The summary
  ;; marker MUST stay bounded.
  (let [big-map (zipmap (map #(keyword (str "k" %)) (range 5000))
                        (repeat :_))
        marker  (:rf.mcp/summary (summary/tree-summary big-map))]
    (is (= :map (:type marker)))
    (is (= 5000 (:count marker)))
    (is (= summary/summary-keys-cap (count (:keys marker))))
    (is (true? (:keys-truncated? marker)))
    (is (< (tu/token-estimate (pr-str marker)) 5000)
        "Marker for a 5k-entry map MUST still fit the wire cap")))

(deftest tree-summary-scalar-returns-value-unchanged
  ;; Scalars already fit the wire cap by definition, so wrapping them in
  ;; a summary marker would add tokens without saving any. The fn returns
  ;; the scalar unchanged — no `:rf.mcp/summary` wrapper, no
  ;; `:type :scalar` marker.
  (is (= 42 (summary/tree-summary 42)))
  (is (= "hello" (summary/tree-summary "hello")))
  (is (= :a-keyword (summary/tree-summary :a-keyword)))
  (is (nil? (summary/tree-summary nil))))

;; ---------------------------------------------------------------------------
;; deepest-valid-prefix — the error-recovery breadcrumb.
;; ---------------------------------------------------------------------------

(deftest deepest-valid-prefix-walks-to-the-last-resolvable-step
  ;; nil is a legitimate map value; the path that points at it is
  ;; "valid" up to the nil, but a further step terminates.
  (let [auth  {:user {:auth {:token "abc"}}}
        items {:items [:apple :banana :cherry]}]
    (doseq [[db path expected note]
            [[auth [:user :auth :token] [:user :auth :token] "a fully resolvable map path"]
             [auth [:user :auth :missing] [:user :auth] "a missing map key stops at its parent"]
             [auth [:missing] [] "a missing first key resolves nothing"]
             [items [:items 1] [:items 1] "a vector index in range"]
             [items [:items 99] [:items] "an out-of-range index stops at the vector"]
             [items [:items :nope] [:items] "a non-integer key on a vector terminates the walk"]
             [{:user {:name "alice"}} [:user :name :char] [:user :name]
              "walking past a string scalar stops at the scalar"]
             [{:k nil} [:k] [:k] "a path pointing at a nil leaf is valid"]
             [{:k nil} [:k :anything] [:k] "a step past a nil leaf terminates"]]]
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
  (let [[out status] (pipeline/slice-app-db-in-snapshot fixture-snapshot nil :summary)]
    (is (empty? status) "No path-not-found entries when path is nil")
    (testing "every frame's :app-db is replaced with a summary marker"
      (let [marker-default (-> out :rf/default :app-db :rf.mcp/summary)]
        (is (some? marker-default))
        (is (= :map (:type marker-default)))
        (is (= #{:user :cart} (set (:keys marker-default))))
        (is (= 2 (:count marker-default))))
      (let [marker-stories (-> out :stories :app-db :rf.mcp/summary)]
        (is (some? marker-stories))
        (is (= [:story-id] (:keys marker-stories)))))
    (testing "other slices pass through unchanged"
      (is (= {[:user/email] {:value "a@b" :ref-count 1}}
             (-> out :rf/default :sub-cache)))
      (is (= [] (-> out :rf/default :epochs))))))

(deftest snapshot-with-path-returns-subtree
  (let [[out status] (pipeline/slice-app-db-in-snapshot
                       fixture-snapshot
                       [:user :profile]
                       :summary)]
    (is (= {:name "alice"}
           (-> out :rf/default :app-db))
        "Subtree replaces the :app-db slice on the matching frame")
    (testing "non-matching frames record :path-not-found"
      ;; :stories has no :user key. The same path call records the
      ;; miss in `path-not-found` and zeroes the slice.
      (is (contains? status :stories))
      (is (= false (-> status :stories :exists?)))
      (is (= [] (-> status :stories :deepest-valid-prefix)))
      (is (nil? (-> out :stories :app-db))
          "Missing-path slice becomes nil — agent reads :path-not-found")
      (is (not (contains? status :rf/default))
          "Matching frame doesn't appear in the path-not-found map"))))

(deftest snapshot-with-vector-index-path
  (let [[out _] (pipeline/slice-app-db-in-snapshot
                  fixture-snapshot
                  [:cart :items 1 :sku]
                  :summary)]
    (is (= "A2" (-> out :rf/default :app-db)))))

(deftest snapshot-with-empty-path-returns-full-app-db
  ;; Root path is the agent opting in to the full slice.
  (let [[out _] (pipeline/slice-app-db-in-snapshot fixture-snapshot [] :summary)]
    (is (= {:user {:profile {:name "alice"}}
            :cart {:items [{:sku "A1"} {:sku "A2"}]
                   :total 42}}
           (-> out :rf/default :app-db)))))

(deftest snapshot-path-not-found-attaches-deepest-prefix
  (let [[_ status] (pipeline/slice-app-db-in-snapshot
                     fixture-snapshot
                     [:user :auth :token]
                     :summary)]
    (is (= false (-> status :rf/default :exists?)))
    (is (= [:user] (-> status :rf/default :deepest-valid-prefix)))))

(deftest snapshot-summarise-handles-empty-map
  (let [snap {:f1 {:app-db {} :sub-cache {} :machines {} :epochs [] :traces []}}
        [out _] (pipeline/slice-app-db-in-snapshot snap nil :summary)
        marker (-> out :f1 :app-db :rf.mcp/summary)]
    (is (= :map (:type marker)))
    (is (= 0 (:count marker)))
    (is (= [] (:keys marker)))))

(deftest snapshot-skips-frames-without-app-db
  ;; The :include filter may exclude :app-db from the slice list.
  ;; In that case the frame map has no :app-db key at all; the
  ;; post-processor MUST NOT add one.
  (let [snap {:f1 {:sub-cache {} :epochs []}}
        [out _] (pipeline/slice-app-db-in-snapshot snap nil :summary)
        [out2 _] (pipeline/slice-app-db-in-snapshot snap [:foo] :summary)]
    (is (not (contains? (:f1 out) :app-db)))
    (is (not (contains? (:f1 out2) :app-db)))))
