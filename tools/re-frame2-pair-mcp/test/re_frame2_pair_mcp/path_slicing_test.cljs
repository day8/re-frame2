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

(deftest parse-path-arg-nil-is-nil
  (is (nil? (args/parse-path-arg nil))))

(deftest parse-path-arg-blank-string-is-nil
  (is (nil? (args/parse-path-arg "")))
  (is (nil? (args/parse-path-arg "   "))))

(deftest parse-path-arg-edn-vector-string
  (is (= [:cart :items 0] (args/parse-path-arg "[:cart :items 0]")))
  (is (= [:a :b :c] (args/parse-path-arg "[:a :b :c]"))))

(deftest parse-path-arg-edn-empty-vector-is-root
  (is (= [] (args/parse-path-arg "[]"))))

(deftest parse-path-arg-cljs-vector-passes-through
  (is (= [:a :b :c] (args/parse-path-arg [:a :b :c])))
  (is (= [] (args/parse-path-arg []))))

(deftest parse-path-arg-cljs-sequential-coerces-to-vector
  (is (= [:a :b :c] (args/parse-path-arg (list :a :b :c)))))

(deftest parse-path-arg-js-array-of-edn-strings
  ;; Each segment is parsed as EDN: keywords, integers, etc.
  (is (= [:cart :items 0]
         (args/parse-path-arg #js [":cart" ":items" "0"]))))

(deftest parse-path-arg-js-array-with-bare-strings-stays-strings
  ;; Non-EDN segments (bare strings) pass through as map keys.
  (is (= [:a "bare-key" :b]
         (args/parse-path-arg #js [":a" "bare-key" ":b"]))))

(deftest parse-path-arg-non-vector-edn-wraps-as-single-segment
  ;; A lone keyword string becomes a 1-segment path.
  (is (= [:foo] (args/parse-path-arg ":foo")))
  ;; A bare integer string also becomes a 1-segment path.
  (is (= [42] (args/parse-path-arg "42"))))

(deftest parse-path-arg-unparseable-string-is-single-string-segment
  ;; Pathological — EDN parser barfs; fall back to treating as one
  ;; map-key string segment rather than raising.
  (is (= ["((("] (args/parse-path-arg "(((")))
  (is (= ["[" "]"] (args/parse-path-arg #js ["[" "]"]))))

;; ---------------------------------------------------------------------------
;; tree-summary — the {:rf.mcp/summary ...} marker shape.
;; ---------------------------------------------------------------------------

(deftest tree-summary-map-records-top-level-keys-and-bytes-approx
  ;; `:bytes` is a cheap approximation, not a precise count. It is
  ;; `count × per-entry constant`, never `(count (pr-str v))` — a
  ;; deep walk would contradict the marker's "no-deep-walk" contract
  ;; (a 54MB app-db slice would burn a 54MB string allocation per
  ;; summary). Pin the contract: the estimate scales linearly with
  ;; entry count, is non-negative, and is order-of-magnitude
  ;; reasonable for tiny maps.
  (let [v {:a 1 :b 2 :nested {:deep {:value 42}}}
        s (summary/tree-summary v)
        marker (:rf.mcp/summary s)]
    (is (= :map (:type marker)))
    (is (= #{:a :b :nested} (set (:keys marker))))
    (is (= 3 (:count marker)))
    (is (integer? (:bytes marker)))
    (is (pos? (:bytes marker)))
    ;; The estimate is `:count × per-entry-constant` — for a 3-entry
    ;; map under the current 16-byte heuristic that's 48. Pin a
    ;; range, not the literal, so a future re-tune of the heuristic
    ;; (e.g. measuring against a representative corpus) doesn't churn
    ;; the test fixture by one byte.
    (is (<= 8 (:bytes marker) 512)
        "Per-entry estimate should be order-of-magnitude reasonable")))

(deftest tree-summary-bytes-scales-with-entry-count
  ;; The estimator is linear: a 10-entry map should report ~10x the
  ;; `:bytes` of a 1-entry map.
  (let [tiny  {:a 1}
        big   (zipmap (map #(keyword (str "k" %)) (range 10)) (range 10))
        tiny-bytes (-> tiny summary/tree-summary :rf.mcp/summary :bytes)
        big-bytes  (-> big  summary/tree-summary :rf.mcp/summary :bytes)]
    (is (= 10 (/ big-bytes tiny-bytes))
        "10-entry map's bytes estimate should be 10x a 1-entry map's")))

(deftest tree-summary-bytes-is-cheap-on-large-values
  ;; The load-bearing property. Computing `:bytes` on a 50K-entry map
  ;; MUST be effectively instant — the marker exists precisely to avoid
  ;; serialising the deep value. A `(count (pr-str v))` approach would
  ;; burn ~megabytes of string allocation here.
  ;;
  ;; We don't time the call (test environments vary too much) but we
  ;; pin the structural property: the result is the entry count
  ;; times a fixed constant, so the computation is independent of
  ;; value depth. A regression to deep `pr-str` would surface as a
  ;; non-multiple-of-the-constant byte count.
  (let [huge   (zipmap (map #(keyword (str "k" %)) (range 50000))
                       (repeat (zipmap (range 100) (range 100))))
        marker (:rf.mcp/summary (summary/tree-summary huge))
        bytes  (:bytes marker)]
    (is (= 50000 (:count marker)))
    (is (zero? (mod bytes 50000))
        "Bytes must be entry-count × constant, not pr-str byte count")))

(deftest tree-summary-vector-records-count
  (let [v [1 2 3 4 5]
        marker (:rf.mcp/summary (summary/tree-summary v))]
    (is (= :vector (:type marker)))
    (is (= 5 (:count marker)))
    (is (integer? (:bytes marker)))
    (is (pos? (:bytes marker)))))

(deftest tree-summary-set-and-seq
  (is (= :set (-> (summary/tree-summary #{1 2 3}) :rf.mcp/summary :type)))
  (is (= :seq (-> (summary/tree-summary (list 1 2 3)) :rf.mcp/summary :type))))

;; ---------------------------------------------------------------------------
;; rf2-2rtt6.132 - the marker's `:bytes` slot counts UTF-8 BYTES.
;;
;; `sample-entry-bytes` was `(count (pr-str sample))` - UTF-16 CODE UNITS -
;; multiplied up into a slot the cross-MCP wire vocabulary names `:bytes`,
;; the same slot `{:rf.size/large-elided ...}` carries. The two rulers agree
;; EXACTLY on ASCII, which is why the defect survived a green suite: every
;; fixture above is ASCII, so the wrong expression printed the right number.
;;
;; The fixture below is DISCRIMINATING by construction - code units, code
;; points and UTF-8 bytes are three different numbers - and is asserted so
;; BEFORE it is used, so a future editor that ASCII-fies the source reds the
;; anti-vacuity assertions rather than silently neutering the pin. It is
;; written as \uXXXX escapes so this file stays pure ASCII.
;;
;; Both directions are pinned: the non-ASCII entry DIVERGES (the estimate
;; nearly doubles), and an ASCII entry of the SAME code-unit length AGREES
;; with what the old expression produced.
;; ---------------------------------------------------------------------------

(def ^:private utf8-discriminating-entry
  "Three U+2014 EM DASH (1 code unit / 1 code point / 3 UTF-8 bytes each)
  followed by one ASTRAL U+1D11E (2 code units / 1 code point / 4 bytes)."
  "\u2014\u2014\u2014\uD834\uDD1E")

(def ^:private ascii-control-entry
  "Same CODE-UNIT length as `utf8-discriminating-entry`, pure ASCII - so
  the two rulers must agree on it exactly."
  "aaaaa")

(defn- utf8-len [s]
  (let [^js enc (js/TextEncoder.)]
    (.-length (.encode enc s))))

(deftest tree-summary-bytes-fixture-is-discriminating
  ;; Anti-vacuity FIRST: if these stop holding, every assertion in
  ;; `tree-summary-bytes-counts-utf8-bytes-not-code-units` measures nothing.
  (let [e utf8-discriminating-entry]
    (is (= 5 (count e)) "5 UTF-16 code units")
    (is (= 4 (count (js/Array.from e))) "4 code points")
    (is (= 13 (utf8-len e)) "13 UTF-8 bytes - three different numbers")
    (is (= 5 (count ascii-control-entry)))
    (is (= 5 (utf8-len ascii-control-entry))
        "the ASCII control's rulers agree exactly - that is the fail-open condition"))
  ;; And the printed form the estimator actually samples (quotes included).
  (is (= 7 (count (pr-str utf8-discriminating-entry))))
  (is (= 15 (utf8-len (pr-str utf8-discriminating-entry))))
  (is (= 7 (count (pr-str ascii-control-entry))))
  (is (= 7 (utf8-len (pr-str ascii-control-entry)))))

(deftest tree-summary-bytes-counts-utf8-bytes-not-code-units
  ;; All FOUR emit sites in `summary.cljs` - map / vector / set / seq.
  ;;
  ;; Sampled per-entry size is `(max 8 (utf8-bytes (pr-str sample)))`:
  ;;   non-ASCII entry -> max(8, 15) = 15   (code units gave max(8, 7) = 8)
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
    (testing "the correction can only ever RAISE the estimate"
      ;; UTF-8 bytes are never fewer than UTF-16 code units, so no slice
      ;; that read as small before reads as smaller now. Nothing in this
      ;; server gates on the figure, so no budget's direction moves.
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

(deftest deepest-valid-prefix-walks-map-keys
  (let [db {:user {:auth {:token "abc"}}}]
    (is (= [:user :auth :token]
           (summary/deepest-valid-prefix db [:user :auth :token])))
    (is (= [:user :auth]
           (summary/deepest-valid-prefix db [:user :auth :missing])))
    (is (= []
           (summary/deepest-valid-prefix db [:missing])))))

(deftest deepest-valid-prefix-walks-vector-indices
  (let [db {:items [:apple :banana :cherry]}]
    (is (= [:items 1]
           (summary/deepest-valid-prefix db [:items 1])))
    (is (= [:items]
           (summary/deepest-valid-prefix db [:items 99])))
    (is (= [:items]
           ;; non-integer key on a vector terminates the walk
           (summary/deepest-valid-prefix db [:items :nope])))))

(deftest deepest-valid-prefix-stops-at-scalar
  (let [db {:user {:name "alice"}}]
    ;; Walking past a string scalar stops at the scalar's parent.
    (is (= [:user :name]
           (summary/deepest-valid-prefix db [:user :name :char])))))

(deftest deepest-valid-prefix-handles-nil-leaf
  ;; nil is a legitimate map value; the path that points at it is
  ;; "valid" up to the nil, but a further step terminates.
  (let [db {:k nil}]
    (is (= [:k] (summary/deepest-valid-prefix db [:k])))
    (is (= [:k] (summary/deepest-valid-prefix db [:k :anything])))))

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

;; ---------------------------------------------------------------------------
;; Wire-cap interaction: tree-summary keeps a 5MB app-db inside the cap.
;; ---------------------------------------------------------------------------

(deftest summary-mode-bounds-the-5mb-scenario
  ;; A 5MB app-db pr-strs to ~5.6M chars ⇒ ~1.4M tokens, 290× the
  ;; 5,000-token cap. With path slicing's :summary default, the call
  ;; replaces the slice with a small marker — fits the cap by
  ;; construction. The wire-cap remains the backstop for the remaining
  ;; slices, but :app-db alone never blows the budget.
  (let [big-app-db (apply hash-map
                          (mapcat (fn [i] [(keyword (str "k" i))
                                           (apply str (repeat 1024 "x"))])
                                  (range 5120)))   ;; ~5MB worth of map
        snap {:rf/default {:app-db big-app-db
                            :sub-cache {} :machines {} :epochs [] :traces []}}
        [out _] (pipeline/slice-app-db-in-snapshot snap nil :summary)
        wire    (pr-str (-> out :rf/default :app-db))]
    (is (contains? (-> out :rf/default :app-db) :rf.mcp/summary))
    (is (< (tu/token-estimate wire) 5000)
        (str "Summary marker MUST be under the 5k cap. Got "
             (tu/token-estimate wire) " tokens for serialised marker"))))
