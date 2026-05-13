(ns re-frame-pair2-mcp.path-slicing-test
  "Unit tests for the path-slicing surfaces added under rf2-tygdv.

  Two MCP surfaces share the same path vocabulary:

    - The `snapshot` tool gained a `:path` arg that slices the
      `:app-db` slice. Without `:path`, the `:app-db` slice is
      replaced by a `{:rf.mcp/summary ...}` marker (default mode
      `:summary`) so the response stays under the wire cap.
    - The new `get-path` tool returns the value at a single path —
      a minimal primitive for targeted reads.

  These tests mirror the private helpers from `tools.cljs`
  (`parse-path-arg`, `coerce-path-segment`, `tree-summary`,
  `deepest-valid-prefix`, `slice-app-db-in-snapshot`). A rename or
  signature change surfaces as a failing test rather than a silent
  contract drift.

  Live end-to-end coverage of `get-path-tool` and the snapshot
  `:path` arg lives in `test/stdio-roundtrip.js` (degraded-mode
  dispatch) and the manual live-nREPL integration test."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [cljs.reader]
            [clojure.string :as str]
            [applied-science.js-interop :as j]))

;; ---------------------------------------------------------------------------
;; Mirrors of the private path-slicing helpers. Keep in lockstep with
;; `tools.cljs`. (Private vars aren't accessible across ns boundaries
;; in CLJS without `#'` and `:private false` — copying the surface
;; here is the convention the sibling tests use.)
;; ---------------------------------------------------------------------------

(defn- token-estimate
  "Mirror of `tools.cljs`'s `token-estimate` — `(quot (count s) 4)`."
  [s]
  (quot (count s) 4))

(defn- coerce-path-segment [s]
  (if-not (string? s)
    s
    (let [trimmed   (str/trim s)
          fc        (when (pos? (count trimmed)) (.charAt trimmed 0))
          edn-shape (and fc
                         (or (= ":" fc)
                             (= "-" fc)
                             (= "+" fc)
                             (boolean (re-matches #"\d" fc))))]
      (if edn-shape
        (try (cljs.reader/read-string trimmed)
             (catch :default _ s))
        s))))

(defn- parse-path-arg [raw]
  (cond
    (nil? raw) nil
    (vector? raw) raw
    (sequential? raw) (vec raw)
    (array? raw) (mapv coerce-path-segment (js->clj raw))
    (string? raw)
    (let [trimmed (str/trim raw)]
      (cond
        (str/blank? trimmed) nil
        :else
        (try
          (let [parsed (cljs.reader/read-string trimmed)]
            (cond
              (vector? parsed)     parsed
              (sequential? parsed) (vec parsed)
              :else                [parsed]))
          (catch :default _
            [trimmed]))))
    :else nil))

(def ^:private summary-keys-cap 64)

(defn- tree-summary [v]
  (cond
    (map? v)
    (let [ks    (keys v)
          n     (count ks)
          shown (if (> n summary-keys-cap)
                  (vec (take summary-keys-cap ks))
                  (vec ks))]
      {:rf.mcp/summary (cond-> {:type   :map
                                :keys   shown
                                :count  n
                                :bytes  (count (pr-str v))}
                         (> n summary-keys-cap)
                         (assoc :keys-truncated? true))})
    (vector? v)
    {:rf.mcp/summary {:type  :vector
                      :count (count v)
                      :bytes (count (pr-str v))}}
    (set? v)
    {:rf.mcp/summary {:type  :set
                      :count (count v)
                      :bytes (count (pr-str v))}}
    (sequential? v)
    {:rf.mcp/summary {:type  :seq
                      :count (count v)
                      :bytes (count (pr-str v))}}
    :else
    {:rf.mcp/summary {:type  :scalar
                      :value v
                      :bytes (count (pr-str v))}}))

(defn- deepest-valid-prefix [db path]
  (loop [acc [] cur db remaining path]
    (if (empty? remaining)
      acc
      (let [k (first remaining)]
        (cond
          (and (map? cur) (contains? cur k))
          (recur (conj acc k) (get cur k) (rest remaining))

          (and (sequential? cur) (integer? k) (<= 0 k (dec (count cur))))
          (recur (conj acc k) (nth (vec cur) k) (rest remaining))

          :else acc)))))

(defn- slice-app-db-in-snapshot [snapshot path]
  (if-not (map? snapshot)
    [snapshot {}]
    (let [status* (atom {})
          missing (js-obj)
          process-frame
          (fn [frame-id frame-map]
            (if-not (and (map? frame-map) (contains? frame-map :app-db))
              frame-map
              (let [db (:app-db frame-map)]
                (cond
                  (nil? path)
                  (update frame-map :app-db tree-summary)
                  (empty? path)
                  frame-map
                  :else
                  (let [v (get-in db path missing)]
                    (if (identical? v missing)
                      (do (swap! status* assoc frame-id
                                 {:exists? false
                                  :deepest-valid-prefix (deepest-valid-prefix db path)})
                          (assoc frame-map :app-db nil))
                      (assoc frame-map :app-db v)))))))
          processed (reduce-kv (fn [m fid fmap]
                                 (assoc m fid (process-frame fid fmap)))
                               {} snapshot)]
      [processed @status*])))

;; ---------------------------------------------------------------------------
;; parse-path-arg — the input-shape contract.
;; ---------------------------------------------------------------------------

(deftest parse-path-arg-nil-is-nil
  (is (nil? (parse-path-arg nil))))

(deftest parse-path-arg-blank-string-is-nil
  (is (nil? (parse-path-arg "")))
  (is (nil? (parse-path-arg "   "))))

(deftest parse-path-arg-edn-vector-string
  (is (= [:cart :items 0] (parse-path-arg "[:cart :items 0]")))
  (is (= [:a :b :c] (parse-path-arg "[:a :b :c]"))))

(deftest parse-path-arg-edn-empty-vector-is-root
  (is (= [] (parse-path-arg "[]"))))

(deftest parse-path-arg-cljs-vector-passes-through
  (is (= [:a :b :c] (parse-path-arg [:a :b :c])))
  (is (= [] (parse-path-arg []))))

(deftest parse-path-arg-cljs-sequential-coerces-to-vector
  (is (= [:a :b :c] (parse-path-arg (list :a :b :c)))))

(deftest parse-path-arg-js-array-of-edn-strings
  ;; Each segment is parsed as EDN: keywords, integers, etc.
  (is (= [:cart :items 0]
         (parse-path-arg #js [":cart" ":items" "0"]))))

(deftest parse-path-arg-js-array-with-bare-strings-stays-strings
  ;; Non-EDN segments (bare strings) pass through as map keys.
  (is (= [:a "bare-key" :b]
         (parse-path-arg #js [":a" "bare-key" ":b"]))))

(deftest parse-path-arg-non-vector-edn-wraps-as-single-segment
  ;; A lone keyword string becomes a 1-segment path.
  (is (= [:foo] (parse-path-arg ":foo")))
  ;; A bare integer string also becomes a 1-segment path.
  (is (= [42] (parse-path-arg "42"))))

(deftest parse-path-arg-unparseable-string-is-single-string-segment
  ;; Pathological — EDN parser barfs; fall back to treating as one
  ;; map-key string segment rather than raising.
  (is (= ["((("] (parse-path-arg "(((")))
  (is (= ["[" "]"] (parse-path-arg #js ["[" "]"]))))

;; ---------------------------------------------------------------------------
;; tree-summary — the {:rf.mcp/summary ...} marker shape.
;; ---------------------------------------------------------------------------

(deftest tree-summary-map-records-top-level-keys-and-bytes
  (let [v {:a 1 :b 2 :nested {:deep {:value 42}}}
        s (tree-summary v)
        marker (:rf.mcp/summary s)]
    (is (= :map (:type marker)))
    (is (= #{:a :b :nested} (set (:keys marker))))
    (is (= 3 (:count marker)))
    (is (= (count (pr-str v)) (:bytes marker)))))

(deftest tree-summary-vector-records-count
  (let [v [1 2 3 4 5]
        marker (:rf.mcp/summary (tree-summary v))]
    (is (= :vector (:type marker)))
    (is (= 5 (:count marker)))))

(deftest tree-summary-set-and-seq
  (is (= :set (-> (tree-summary #{1 2 3}) :rf.mcp/summary :type)))
  (is (= :seq (-> (tree-summary (list 1 2 3)) :rf.mcp/summary :type))))

(deftest tree-summary-map-truncates-huge-key-lists
  ;; A 5k-entry map's key list alone would blow the cap. The summary
  ;; marker MUST stay bounded.
  (let [big-map (zipmap (map #(keyword (str "k" %)) (range 5000))
                        (repeat :_))
        marker  (:rf.mcp/summary (tree-summary big-map))]
    (is (= :map (:type marker)))
    (is (= 5000 (:count marker)))
    (is (= summary-keys-cap (count (:keys marker))))
    (is (true? (:keys-truncated? marker)))
    (is (< (token-estimate (pr-str marker)) 5000)
        "Marker for a 5k-entry map MUST still fit the wire cap")))

(deftest tree-summary-scalar-keeps-the-value
  ;; Scalars are cheap; summarising would lose information without
  ;; saving any tokens.
  (let [marker (:rf.mcp/summary (tree-summary 42))]
    (is (= :scalar (:type marker)))
    (is (= 42 (:value marker)))))

;; ---------------------------------------------------------------------------
;; deepest-valid-prefix — the error-recovery breadcrumb.
;; ---------------------------------------------------------------------------

(deftest deepest-valid-prefix-walks-map-keys
  (let [db {:user {:auth {:token "abc"}}}]
    (is (= [:user :auth :token]
           (deepest-valid-prefix db [:user :auth :token])))
    (is (= [:user :auth]
           (deepest-valid-prefix db [:user :auth :missing])))
    (is (= []
           (deepest-valid-prefix db [:missing])))))

(deftest deepest-valid-prefix-walks-vector-indices
  (let [db {:items [:apple :banana :cherry]}]
    (is (= [:items 1]
           (deepest-valid-prefix db [:items 1])))
    (is (= [:items]
           (deepest-valid-prefix db [:items 99])))
    (is (= [:items]
           ;; non-integer key on a vector terminates the walk
           (deepest-valid-prefix db [:items :nope])))))

(deftest deepest-valid-prefix-stops-at-scalar
  (let [db {:user {:name "alice"}}]
    ;; Walking past a string scalar stops at the scalar's parent.
    (is (= [:user :name]
           (deepest-valid-prefix db [:user :name :char])))))

(deftest deepest-valid-prefix-handles-nil-leaf
  ;; nil is a legitimate map value; the path that points at it is
  ;; "valid" up to the nil, but a further step terminates.
  (let [db {:k nil}]
    (is (= [:k] (deepest-valid-prefix db [:k])))
    (is (= [:k] (deepest-valid-prefix db [:k :anything])))))

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
  (let [[out status] (slice-app-db-in-snapshot fixture-snapshot nil)]
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
  (let [[out status] (slice-app-db-in-snapshot
                       fixture-snapshot
                       [:user :profile])]
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
  (let [[out _] (slice-app-db-in-snapshot
                  fixture-snapshot
                  [:cart :items 1 :sku])]
    (is (= "A2" (-> out :rf/default :app-db)))))

(deftest snapshot-with-empty-path-returns-full-app-db
  ;; Root path is the agent opting in to the full slice.
  (let [[out _] (slice-app-db-in-snapshot fixture-snapshot [])]
    (is (= {:user {:profile {:name "alice"}}
            :cart {:items [{:sku "A1"} {:sku "A2"}]
                   :total 42}}
           (-> out :rf/default :app-db)))))

(deftest snapshot-path-not-found-attaches-deepest-prefix
  (let [[_ status] (slice-app-db-in-snapshot
                     fixture-snapshot
                     [:user :auth :token])]
    (is (= false (-> status :rf/default :exists?)))
    (is (= [:user] (-> status :rf/default :deepest-valid-prefix)))))

(deftest snapshot-summarise-handles-empty-map
  (let [snap {:f1 {:app-db {} :sub-cache {} :machines {} :epochs [] :traces []}}
        [out _] (slice-app-db-in-snapshot snap nil)
        marker (-> out :f1 :app-db :rf.mcp/summary)]
    (is (= :map (:type marker)))
    (is (= 0 (:count marker)))
    (is (= [] (:keys marker)))))

(deftest snapshot-skips-frames-without-app-db
  ;; The :include filter may exclude :app-db from the slice list.
  ;; In that case the frame map has no :app-db key at all; the
  ;; post-processor MUST NOT add one.
  (let [snap {:f1 {:sub-cache {} :epochs []}}
        [out _] (slice-app-db-in-snapshot snap nil)
        [out2 _] (slice-app-db-in-snapshot snap [:foo])]
    (is (not (contains? (:f1 out) :app-db)))
    (is (not (contains? (:f1 out2) :app-db)))))

;; ---------------------------------------------------------------------------
;; Wire-cap interaction: tree-summary keeps a 5MB app-db inside the cap.
;; ---------------------------------------------------------------------------

(deftest summary-mode-bounds-the-5mb-scenario
  ;; rf2-jlq5j: a 5MB app-db pr-strs to ~5.6M chars ⇒ ~1.4M tokens,
  ;; 290× the 5,000-token cap. With path slicing's :summary default,
  ;; the same call replaces the slice with a small marker — fits the
  ;; cap by construction. The wire-cap remains the backstop for the
  ;; remaining slices, but :app-db alone no longer blows the budget.
  (let [big-app-db (apply hash-map
                          (mapcat (fn [i] [(keyword (str "k" i))
                                           (apply str (repeat 1024 "x"))])
                                  (range 5120)))   ;; ~5MB worth of map
        snap {:rf/default {:app-db big-app-db
                            :sub-cache {} :machines {} :epochs [] :traces []}}
        [out _] (slice-app-db-in-snapshot snap nil)
        wire    (pr-str (-> out :rf/default :app-db))]
    (is (contains? (-> out :rf/default :app-db) :rf.mcp/summary))
    (is (< (token-estimate wire) 5000)
        (str "Summary marker MUST be under the 5k cap. Got "
             (token-estimate wire) " tokens for serialised marker"))))
