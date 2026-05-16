(ns day8.re-frame2-causa-mcp.summary-test
  "Unit tests for the W-4 lazy-summary wire-pipeline mechanism at
  the Causa-MCP boundary (rf2-8xzoe.8). Pins:

    - MUST 12 — every tree-typed tool exposes a `:mode` argument
      with at least `:summary` (default), `:sample`, and `:full`
      (spec/004 §4 L303-306).
    - Causa-specific `:rf.mcp/summary` shape — `:type`, `:keys`,
      `:counts` (per-key cardinality map; causa divergence from
      pair2-mcp's scalar `:count`), `:bytes` (cheap approximation).
    - Cheap `:bytes` estimator (rf2-qta8j shape) — `count × per-
      entry constant`, NOT deep `pr-str`. Pinned by the structural
      property `(zero? (mod bytes count))`.
    - Mode-arg accept-shape — strings, keywords, nil; out-of-vocab
      bounded-allowlist-guarded (rf2-ih7g4).
    - Composition with W-1 (token-cap), W-2 (path slicing), W-6
      (elision), B-1 (privacy) — additive wrappers.

  These tests are the load-bearing pin for the downstream
  tree-typed direct-read tools — every dispatcher that returns a
  rich nested value calls `summary/apply-to-result` once at the
  boundary, with the resolved `:mode` from `mode-arg`."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [day8.re-frame2-causa-mcp.elision :as elision]
            [day8.re-frame2-causa-mcp.path-slice :as path-slice]
            [day8.re-frame2-causa-mcp.privacy :as privacy]
            [day8.re-frame2-causa-mcp.summary :as summary]
            [day8.re-frame2-causa-mcp.token-cap :as token-cap]))

;; A canonical elided marker, as the framework walker would emit.
;; Reused across composition tests so the W-6 surface stays visible
;; inside W-4's summary / sample / full payloads.
(def ^:private elided-marker
  {:rf.size/large-elided
   {:path   [:user :uploaded-pdf]
    :bytes  102400
    :type   :string
    :reason :schema
    :handle [:rf.elision/at [:user :uploaded-pdf]]}})

;; ---------------------------------------------------------------------------
;; Public surface — vars exist and are callable.
;; ---------------------------------------------------------------------------

(deftest public-surface-resolvable
  (testing "W-4 lands the public surface downstream tree-typed
            direct-read tool dispatchers will require"
    (is (fn? summary/tree-summary))
    (is (fn? summary/sample-value))
    (is (fn? summary/parse-mode-arg))
    (is (fn? summary/mode-arg))
    (is (fn? summary/parse-sample-size-arg))
    (is (fn? summary/sample-size-arg))
    (is (fn? summary/apply-to-result))
    (is (= :summary summary/default-mode))
    (is (= #{:summary :sample :full} summary/mode-vocabulary)
        "spec/004 §4 closed mode vocabulary (MUST 12)")
    (is (= 64 summary/summary-keys-cap))
    (is (= 8 summary/default-sample-size))))

;; ---------------------------------------------------------------------------
;; tree-summary — the marker shape (causa-spec divergence).
;; ---------------------------------------------------------------------------

(deftest tree-summary-map-records-keys-and-counts
  ;; Causa divergence from pair2-mcp: `:counts` is a per-key
  ;; cardinality map. Agents prioritise drill-down without a
  ;; second call.
  (let [v {:cart {:items [{:sku "A"} {:sku "B"}]} :user 1 :ui "loading"}
        s (summary/tree-summary v)
        marker (:rf.mcp/summary s)]
    (is (= :map (:type marker)))
    (is (= #{:cart :user :ui} (set (:keys marker))))
    (is (map? (:counts marker)))
    (is (= 1 (get (:counts marker) :cart))
        ":cart's value is a 1-entry map (the :items slot) — counts=1")
    (is (= 1 (get (:counts marker) :user))
        "scalar value contributes 1")
    (is (= 7 (get (:counts marker) :ui))
        "string :ui counts as its length")
    (is (integer? (:bytes marker)))
    (is (pos? (:bytes marker)))))

(deftest tree-summary-counts-tracks-vector-cardinality
  ;; A :map slot whose value is a vector reports its length.
  (let [v {:items [:a :b :c :d :e] :tags #{:x :y}}
        marker (:rf.mcp/summary (summary/tree-summary v))]
    (is (= 5 (get (:counts marker) :items)))
    (is (= 2 (get (:counts marker) :tags)))))

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
  ;; rf2-qta8j (cross-MCP): the load-bearing property. The marker
  ;; exists precisely to avoid deep `pr-str`. A regression to
  ;; deep `pr-str` would surface as a non-multiple-of-the-
  ;; constant byte count.
  (let [huge   (zipmap (map #(keyword (str "k" %)) (range 50000))
                       (repeat (zipmap (range 100) (range 100))))
        marker (:rf.mcp/summary (summary/tree-summary huge))
        bytes  (:bytes marker)]
    (is (= 50000 (count huge)) "test setup pin: 50K-entry map")
    (is (zero? (mod bytes 50000))
        "Bytes must be entry-count × constant, not pr-str byte count")))

(deftest tree-summary-vector-records-count-scalar
  ;; Non-map collections: scalar `:count`, parallel to pair2-mcp.
  (let [v [1 2 3 4 5]
        marker (:rf.mcp/summary (summary/tree-summary v))]
    (is (= :vector (:type marker)))
    (is (= 5 (:count marker)))
    (is (integer? (:bytes marker)))
    (is (pos? (:bytes marker)))
    (is (not (contains? marker :counts))
        ":counts is map-only (causa divergence); vectors carry :count")))

(deftest tree-summary-set-and-seq
  (is (= :set (-> (summary/tree-summary #{1 2 3}) :rf.mcp/summary :type)))
  (is (= 3   (-> (summary/tree-summary #{1 2 3}) :rf.mcp/summary :count)))
  (is (= :seq (-> (summary/tree-summary (list 1 2 3)) :rf.mcp/summary :type))))

(deftest tree-summary-map-truncates-huge-key-lists
  ;; A 5k-entry map's key list alone would blow the cap. The summary
  ;; marker MUST stay bounded.
  (let [big-map (zipmap (map #(keyword (str "k" %)) (range 5000))
                        (repeat :_))
        marker  (:rf.mcp/summary (summary/tree-summary big-map))]
    (is (= :map (:type marker)))
    (is (= summary/summary-keys-cap (count (:keys marker))))
    (is (true? (:keys-truncated? marker)))
    ;; :counts also truncates to the shown keys — parity with :keys
    (is (= summary/summary-keys-cap (count (:counts marker)))
        ":counts truncates to the shown keys")))

(deftest tree-summary-truncated-marker-fits-cap
  ;; The truncated marker MUST still fit the W-1 5K-token cap.
  (let [big-map (zipmap (map #(keyword (str "k" %)) (range 10000))
                        (repeat :_))
        marker  (summary/tree-summary big-map)
        rendered (pr-str marker)
        ;; quot/4 token estimate.
        tokens   (quot (count rendered) 4)]
    (is (< tokens token-cap/default-max-tokens)
        "Marker for a 10K-entry map MUST fit the W-1 cap")))

(deftest tree-summary-scalar-returns-value-unchanged
  ;; Scalars pass through; wrapping adds tokens without saving any.
  (is (= 42 (summary/tree-summary 42)))
  (is (= "hello" (summary/tree-summary "hello")))
  (is (= :keyword (summary/tree-summary :keyword)))
  (is (= true (summary/tree-summary true))))

(deftest tree-summary-marker-key-is-cross-mcp-vocab
  ;; Pin the cross-MCP vocab: `:rf.mcp/summary` is the canonical
  ;; marker key (per re-frame.mcp-base.vocab/summary-key).
  (let [s (summary/tree-summary {:a 1 :b 2})]
    (is (contains? s :rf.mcp/summary))
    (is (= [:rf.mcp/summary] (vec (keys s)))
        "summary marker is the SOLE top-level key on the result")))

;; ---------------------------------------------------------------------------
;; sample-value — :mode :sample bounded prefix.
;; ---------------------------------------------------------------------------

(deftest sample-value-vector-prefix
  (is (= [:a :b :c] (summary/sample-value [:a :b :c :d :e :f] 3))))

(deftest sample-value-vector-shorter-than-n
  (is (= [:a :b] (summary/sample-value [:a :b] 5))))

(deftest sample-value-map-prefix-is-sorted-for-stability
  (let [v {:c 3 :a 1 :b 2 :d 4 :e 5}
        sampled (summary/sample-value v 2)]
    (is (= 2 (count sampled)))
    (is (= {:a 1 :b 2} sampled)
        "sort-by key for stability: same input ⇒ same prefix
         regardless of map iteration order")))

(deftest sample-value-set-bounded
  (let [v #{:a :b :c :d :e}
        sampled (summary/sample-value v 3)]
    (is (= 3 (count sampled)))
    (is (set? sampled))))

(deftest sample-value-seq-prefix
  (is (= '(0 1 2) (summary/sample-value (range 10) 3))))

(deftest sample-value-scalar-passes-through
  (is (= 42 (summary/sample-value 42 5)))
  (is (= "hello" (summary/sample-value "hello" 5))))

(deftest sample-value-clamps-size
  ;; Below min-sample-size clamps up; above max clamps down.
  (let [v (vec (range 100))]
    (is (= 1 (count (summary/sample-value v 0))))
    (is (= 1 (count (summary/sample-value v -5))))
    (is (= summary/max-sample-size (count (summary/sample-value (vec (range 1000)) 999))))))

;; ---------------------------------------------------------------------------
;; Mode-arg parser — bounded-allowlist gate (MUST 12).
;; ---------------------------------------------------------------------------

(deftest parse-mode-arg-default-when-absent
  (is (= :summary (summary/parse-mode-arg nil)))
  (is (= :summary (summary/parse-mode-arg ""))))

(deftest parse-mode-arg-keyword-pass-through
  (is (= :summary (summary/parse-mode-arg :summary)))
  (is (= :sample  (summary/parse-mode-arg :sample)))
  (is (= :full    (summary/parse-mode-arg :full))))

(deftest parse-mode-arg-string-coerces
  (is (= :summary (summary/parse-mode-arg "summary")))
  (is (= :sample  (summary/parse-mode-arg "sample")))
  (is (= :full    (summary/parse-mode-arg "full"))))

(deftest parse-mode-arg-edn-string-coerces
  (is (= :summary (summary/parse-mode-arg ":summary")))
  (is (= :full    (summary/parse-mode-arg ":full"))))

(deftest parse-mode-arg-unknown-falls-back-to-default
  ;; rf2-ih7g4 bounded-allowlist: unrecognised inputs collapse to
  ;; default rather than interning a fresh keyword.
  (is (= :summary (summary/parse-mode-arg "weird")))
  (is (= :summary (summary/parse-mode-arg :weird-mode)))
  (is (= :summary (summary/parse-mode-arg 42)))
  (is (= :summary (summary/parse-mode-arg {:not-a-mode true}))))

(deftest mode-arg-from-js-args-object
  (is (= :full   (summary/mode-arg #js {"mode" "full"})))
  (is (= :sample (summary/mode-arg #js {"mode" "sample"})))
  (is (= :summary (summary/mode-arg #js {}))))

(deftest mode-arg-from-cljs-map
  (is (= :full (summary/mode-arg {:mode :full})))
  (is (= :full (summary/mode-arg {"mode" "full"}))))

(deftest mode-arg-absent-returns-default
  (is (= :summary (summary/mode-arg nil)))
  (is (= :summary (summary/mode-arg js/undefined))))

;; ---------------------------------------------------------------------------
;; sample-size-arg.
;; ---------------------------------------------------------------------------

(deftest parse-sample-size-default
  (is (= summary/default-sample-size (summary/parse-sample-size-arg nil))))

(deftest parse-sample-size-pass-through
  (is (= 5 (summary/parse-sample-size-arg 5))))

(deftest parse-sample-size-clamps
  (is (= summary/min-sample-size (summary/parse-sample-size-arg 0)))
  (is (= summary/max-sample-size (summary/parse-sample-size-arg 9999))))

(deftest sample-size-arg-from-js
  (is (= 25 (summary/sample-size-arg #js {"sample-size" 25}))))

;; ---------------------------------------------------------------------------
;; apply-to-result — per-tool boundary wrapper.
;; ---------------------------------------------------------------------------

(deftest apply-to-result-summary-mode-emits-marker
  (let [tree {:cart {:items []} :user 1}
        out  (summary/apply-to-result {} :db tree {:mode :summary})]
    (is (contains? (:db out) :rf.mcp/summary))
    (is (= :summary (:mode out))
        ":mode slot stamps the chosen mode for agent accounting")))

(deftest apply-to-result-default-mode-is-summary
  ;; MUST 12: `:summary` is the default. The boundary wrapper
  ;; defaults to summary when no mode is supplied.
  (let [tree {:a 1 :b 2}
        out  (summary/apply-to-result {} :db tree {})]
    (is (contains? (:db out) :rf.mcp/summary))
    (is (= :summary (:mode out)))))

(deftest apply-to-result-sample-mode-bounded-prefix
  (let [tree (vec (range 100))
        out  (summary/apply-to-result {} :db tree
                                      {:mode :sample :sample-size 5})]
    (is (= [0 1 2 3 4] (:db out)))
    (is (= :sample (:mode out)))))

(deftest apply-to-result-full-mode-pass-through
  (let [tree {:a 1 :b 2 :c 3}
        out  (summary/apply-to-result {} :db tree {:mode :full})]
    (is (= tree (:db out)))
    (is (= :full (:mode out)))))

(deftest apply-to-result-preserves-existing-envelope-keys
  ;; Additive — pre-existing slots survive. Same shape as the
  ;; other W-* boundary wrappers.
  (let [tree {:a 1}
        out  (summary/apply-to-result
               {:tool "get-app-db"} :db tree {:mode :full})]
    (is (= "get-app-db" (:tool out)))
    (is (= 1 (-> out :db :a)))))

(deftest apply-to-result-shape-for-tree-typed-tools
  ;; Canonical shape every tree-typed tool uses.
  (testing "get-app-db-shape (:db)"
    (let [out (summary/apply-to-result
                {} :db {:cart {} :user {}} {:mode :summary})]
      (is (contains? out :db))))
  (testing "get-machine-state-shape (:state)"
    (let [out (summary/apply-to-result
                {} :state {:current :running} {:mode :summary})]
      (is (contains? out :state)))))

;; ---------------------------------------------------------------------------
;; Composition with W-2 (path slicing), W-6 (elision), W-1 (cap), B-1.
;; ---------------------------------------------------------------------------

(deftest summary-composes-with-w6-elision
  ;; The walked tree carries markers in place; the summary describes
  ;; the SHAPE — including the elided slot as a 1-entry value (the
  ;; marker map). The :counts entry for that slot is 1 (single-key
  ;; marker map).
  (let [tree   {:user {:uploaded-pdf elided-marker} :name "alice"}
        out    (summary/apply-to-result {} :db tree {:mode :summary})
        marker (-> out :db :rf.mcp/summary)]
    (is (= #{:user :name} (set (:keys marker))))
    ;; :user's value is a 1-entry map (the marker map sat at
    ;; :uploaded-pdf); :counts reflects the post-walk cardinality.
    (is (= 1 (get (:counts marker) :user)))
    (is (= 5 (get (:counts marker) :name))
        "string \"alice\" counts as 5")))

(deftest summary-composes-with-w2-path-slice
  ;; Tools that take BOTH `:path` and `:mode`: the dispatcher
  ;; checks `:path` first (MUST 9); when non-nil, slices first then
  ;; routes the addressed subtree through summary. When nil, summary
  ;; describes the full tree.
  (testing "with-path: summary describes the sliced subtree"
    (let [tree    {:cart {:items [{:sku "A"} {:sku "B"}] :total 30}}
          sliced  (path-slice/apply-to-result {} :db tree [:cart])
          ;; The dispatcher walks the resolved subtree (under :db)
          ;; through summary's apply-to-result.
          out     (summary/apply-to-result sliced :db (:db sliced)
                                           {:mode :summary})
          marker  (-> out :db :rf.mcp/summary)]
      (is (= #{:items :total} (set (:keys marker))))
      (is (= 2 (get (:counts marker) :items)))
      (is (= 1 (get (:counts marker) :total))
          "scalar :total counts as 1")))
  (testing "no-path: summary describes the root"
    (let [tree {:cart {} :user {} :ui {}}
          out  (summary/apply-to-result {} :db tree {:mode :summary})
          marker (-> out :db :rf.mcp/summary)]
      (is (= #{:cart :user :ui} (set (:keys marker)))))))

(deftest summary-composes-with-b1-orthogonally
  ;; Privacy + summary are on orthogonal surfaces but the envelope
  ;; shapes compose — additive wrappers.
  (let [events  [{:op :a} {:op :b :sensitive? true}]
        tree    {:cart {} :user {}}
        out     (-> {}
                    (privacy/apply-to-result :events events false)
                    (summary/apply-to-result :db tree {:mode :summary}))]
    (is (= 1 (:dropped-sensitive out)))
    (is (= [{:op :a}] (:events out)))
    (is (contains? (:db out) :rf.mcp/summary))))

(deftest summary-marker-fits-under-w1-cap
  ;; The load-bearing property: a summary marker for a HUGE app-db
  ;; root MUST fit the W-1 cap. This is mechanism W-4's primary
  ;; raison-d'etre — `:summary` is the cap defence for tree-typed
  ;; tools. Synthesise a pathologically wide and deep root.
  (let [huge   (zipmap (map #(keyword (str "k" %)) (range 10000))
                       (repeat (zipmap (range 100) (range 100))))
        out    (summary/apply-to-result {} :db huge {:mode :summary})
        rendered (pr-str (:db out))
        tokens   (quot (count rendered) 4)]
    (is (< tokens token-cap/default-max-tokens)
        "summary marker for a 10K-key root MUST fit W-1's 5K cap")))

;; ---------------------------------------------------------------------------
;; Load-bearing spec/004 §4 assertion.
;; ---------------------------------------------------------------------------

(deftest spec-004-mode-vocabulary-end-to-end
  (testing "MUST 12 — :mode arg exposes at least :summary :sample
            :full, default is :summary"
    (is (contains? summary/mode-vocabulary :summary))
    (is (contains? summary/mode-vocabulary :sample))
    (is (contains? summary/mode-vocabulary :full))
    (is (= :summary summary/default-mode)))
  (testing "the per-mode behaviour"
    (let [tree {:a 1 :b 2 :c 3 :d 4 :e 5}]
      (is (contains? (:db (summary/apply-to-result {} :db tree {:mode :summary}))
                     :rf.mcp/summary)
          ":summary ⇒ marker")
      (is (map? (:db (summary/apply-to-result {} :db tree {:mode :sample
                                                            :sample-size 2})))
          ":sample ⇒ bounded prefix of same shape")
      (is (= tree (:db (summary/apply-to-result {} :db tree {:mode :full})))
          ":full ⇒ pass-through"))))

(deftest spec-004-summary-marker-shape-end-to-end
  (testing "spec/004 §4 marker shape: :type, :keys, :counts
            (causa-divergence per-key cardinality map), :bytes"
    (let [v {:cart {:items [:a :b]} :user 1}
          marker (:rf.mcp/summary (summary/tree-summary v))]
      (is (= :map (:type marker)))
      (is (= #{:cart :user} (set (:keys marker))))
      (is (= 1 (get (:counts marker) :cart)))
      (is (= 1 (get (:counts marker) :user)))
      (is (integer? (:bytes marker))))))
