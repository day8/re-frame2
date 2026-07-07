(ns re-frame2-pair-mcp.dx-papercuts-test
  "Unit tests for four re-frame2-pair DX papercuts.

  1. Session-sticky operating build — an explicit `:build` on any
     non-discover tool call becomes the default for subsequent
     no-`:build` calls (`wire/stick-build!` at the `invoke` boundary).
  2. Colon-normalise — `:frame` on `trace-window` / `watch-epochs` is
     coerced via the colon-tolerant `args/->frame-keyword` (the build
     arg's colon tolerance is already pinned in `build_id_cache_test`).
  3. Batch read — the plural `paths` arg on `get-path`: parsing
     (`args/parse-paths-arg`) + the batch eval form
     (`get-path/batch-paths-form`) + the mutual-exclusion guard.
  4. Snapshot full-mode epoch overflow — `tree-summary`'s `:bytes`
     hint samples a representative entry so a slice of deep entries
     (the `:epochs` slice) reports its full-expansion cost, and the
     `:epochs` slice is record-capped in full mode
     (`pipeline/cap-full-epochs-in-snapshot`).

  Tests pin the public surfaces directly so a rename or signature
  drift surfaces as a failing test rather than silent contract drift.
  Live end-to-end coverage rides on `test/stdio-roundtrip.js` (degraded
  dispatch) and the manual live-nREPL integration test."
  (:require [cljs.test :refer-macros [deftest is async]]
            [re-frame2-pair-mcp.nrepl :as nrepl]
            [re-frame2-pair-mcp.tools.args :as args]
            [re-frame2-pair-mcp.tools.get-path :as get-path]
            [re-frame2-pair-mcp.tools.eval-form :as ef]
            [re-frame2-pair-mcp.tools.snapshot-pipeline :as pipeline]
            [re-frame2-pair-mcp.tools.summary :as summary]
            [re-frame2-pair-mcp.tools.wire :as wire]
            [re-frame2-pair-mcp.test-utils :as tu]))

;; ===========================================================================
;; Papercut 1 — session-sticky operating build.
;; ===========================================================================

(defn- fresh-conn []
  (let [conn (nrepl/make-conn 0 "127.0.0.1")]
    (swap! conn assoc :probed-builds #{} :resolved-build-id nil)
    conn))

(deftest stick-build-records-explicit-build
  ;; An explicit `:build` is written into the resolved-build-id cache so
  ;; a subsequent no-`:build` call inherits it.
  (let [conn (fresh-conn)]
    (wire/stick-build! conn (tu/args->js {:build "examples/standard-epochs"}))
    (is (= :examples/standard-epochs (:resolved-build-id @conn))
        "explicit build sticks on the conn-atom")
    (is (= :examples/standard-epochs
           (wire/arg-build conn (tu/args->js {})))
        "the next call without :build inherits the stuck build")))

(deftest stick-build-tolerates-leading-colon
  ;; Colon-form build sticks identically (footgun-free) — papercut 2 on
  ;; the build arg, exercised through the sticky write.
  (let [conn (fresh-conn)]
    (wire/stick-build! conn (tu/args->js {:build ":examples/standard-epochs"}))
    (is (= :examples/standard-epochs (:resolved-build-id @conn)))))

(deftest stick-build-omitted-build-does-not-clobber
  ;; A later call WITHOUT :build must not wipe a previously-stuck build.
  (let [conn (fresh-conn)]
    (swap! conn assoc :resolved-build-id :examples/standard-epochs)
    (wire/stick-build! conn (tu/args->js {}))
    (is (= :examples/standard-epochs (:resolved-build-id @conn))
        "no :build arg leaves the stuck build untouched")))

(deftest stick-build-explicit-repoints-sticky-default
  ;; A one-off :build on a later call both routes that call AND re-points
  ;; the sticky default (explicit-wins, and it sticks).
  (let [conn (fresh-conn)]
    (swap! conn assoc :resolved-build-id :examples/standard-epochs)
    (wire/stick-build! conn (tu/args->js {:build "other-build"}))
    (is (= :other-build (:resolved-build-id @conn)))))

(deftest stick-build-nil-conn-is-noop
  ;; Defensive — a non-atom / nil conn (test stubs) must not throw.
  (is (nil? (wire/stick-build! nil (tu/args->js {:build "app"})))
      "nil conn returns nil, no throw"))

;; ===========================================================================
;; Papercut 2 — colon-normalise the :frame arg on trace-window /
;; watch-epochs. The coercion is `args/->frame-keyword`; the production
;; tool bodies route :frame through it. Pin the helper contract here;
;; the full tool-body wiring is covered by the eval-form mirrors below.
;; ===========================================================================

(deftest frame-keyword-strips-leading-colon
  (is (= :rf/default (args/->frame-keyword ":rf/default")))
  (is (= :rf/default (args/->frame-keyword "rf/default")))
  (is (= :foo (args/->frame-keyword ":foo")))
  (is (= :foo (args/->frame-keyword "foo")))
  (is (= (args/->frame-keyword ":rf/default")
         (args/->frame-keyword "rf/default"))
      "both forms resolve identically — no doubled-colon footgun"))

;; ===========================================================================
;; Papercut 3 — batch read via the plural `paths` arg.
;; ===========================================================================

(deftest parse-paths-arg-nil-is-nil
  (is (nil? (args/parse-paths-arg nil)))
  (is (nil? (args/parse-paths-arg "")))
  (is (nil? (args/parse-paths-arg "   "))))

(deftest parse-paths-arg-edn-string-of-path-vectors
  (is (= [[:cart :total] [:user :id]]
         (args/parse-paths-arg "[[:cart :total] [:user :id]]"))))

(deftest parse-paths-arg-cljs-vector-of-paths
  (is (= [[:a :b] [:c]]
         (args/parse-paths-arg [[:a :b] [:c]]))))

(deftest parse-paths-arg-js-array-of-edn-path-strings
  (is (= [[:cart :total] [:user :id]]
         (args/parse-paths-arg #js ["[:cart :total]" "[:user :id]"]))))

(deftest parse-paths-arg-js-array-of-segment-arrays
  ;; Each entry may itself be a JS array of segment strings.
  (is (= [[:cart :items 0] [:user :id]]
         (args/parse-paths-arg #js [#js [":cart" ":items" "0"] #js [":user" ":id"]]))))

(deftest parse-paths-arg-non-collection-string-is-nil
  ;; A bare scalar EDN string isn't a batch — return nil so the caller
  ;; falls back to the singular surface rather than reading garbage.
  (is (nil? (args/parse-paths-arg ":foo")))
  (is (nil? (args/parse-paths-arg "42"))))

(deftest parse-paths-arg-empty-edn-vector-is-empty-batch
  ;; An explicit empty batch is distinguishable from "no batch" (nil) so
  ;; the tool can return :empty-paths rather than silently no-op'ing.
  (is (= [] (args/parse-paths-arg "[]")))
  (is (= [] (args/parse-paths-arg #js []))))

(deftest batch-paths-form-folds-over-paths
  ;; The batch eval form folds over the path vectors server-side and
  ;; returns `{:ok? true :results {...} :elided-count N}`. The form
  ;; carries a `#js {}` reader literal (the missing sentinel) so it
  ;; isn't plain-EDN-readable — assert on the emitted source instead.
  (let [snapshot-call (ef/rt-call 'snapshot :rf/default)
        paths         [[:cart :total] [:user :id]]
        form          (get-path/batch-paths-form
                        snapshot-call paths true ":rf/default" "{}")]
    (is (string? form))
    (is (re-find #"^\(let " form) "batch form opens a let block")
    ;; The embedded paths literal rides verbatim in the reduce seed.
    (is (re-find #"\[:cart :total\]" form))
    (is (re-find #"\[:user :id\]" form))
    (is (re-find #":results" form))
    (is (re-find #":elided-count" form))
    ;; Elision-on emits the walker call; the per-iteration path `p` is
    ;; the marker handle.
    (is (re-find #"elide-wire-value raw-v" form))))

(deftest get-path-rejects-path-and-paths-together
  ;; Mutual exclusion: supplying both is a structured usage error, not a
  ;; silent precedence pick.
  (async done
    (let [conn (fresh-conn)]
      (-> (get-path/get-path-tool
            conn (tu/args->js {:path "[:a]" :paths "[[:b]]"}))
          (.then (fn [r]
                   (is (tu/error? r))
                   (is (= :path-and-paths-both-supplied
                          (:reason (tu/extract-edn r))))
                   (done)))))))

(deftest get-path-empty-paths-is-usage-error
  (async done
    (let [conn (fresh-conn)]
      (-> (get-path/get-path-tool conn (tu/args->js {:paths "[]"}))
          (.then (fn [r]
                   (is (tu/error? r))
                   (is (= :empty-paths (:reason (tu/extract-edn r))))
                   (done)))))))

(deftest get-path-batch-returns-results-map
  ;; End-to-end with a stubbed runtime: the batch eval form's envelope
  ;; rides through the wire-pipeline and the tool re-attaches `:results`.
  (async done
    (let [conn   (fresh-conn)
          ;; Prime the probe cache so `ensure-runtime!` short-circuits to
          ;; true (the stubbed eval returns the canned envelope, not the
          ;; `true` the preload-marker probe expects).
          _      (swap! conn update :probed-builds (fnil conj #{}) :app)
          canned {:ok?     true
                  :results {[:cart :total] {:exists? true  :value 42}
                            [:user :id]    {:exists? true  :value "u-1"}
                            [:missing :k]  {:exists? false :value nil}}
                  :elided-count 0}]
      (-> (tu/with-stubbed-eval! canned
            (fn []
              (get-path/get-path-tool
                conn (tu/args->js {:paths "[[:cart :total] [:user :id] [:missing :k]]"
                                   :frame ":rf/default"}))))
          (.then (fn [r]
                   (let [edn (tu/extract-edn r)]
                     (is (true? (:ok? edn)))
                     (is (= :rf/default (:frame edn)))
                     (is (= 42 (get-in edn [:results [:cart :total] :value])))
                     (is (true? (get-in edn [:results [:user :id] :exists?])))
                     (is (false? (get-in edn [:results [:missing :k] :exists?]))))
                   (done)))))))

(deftest get-path-blank-runtime-result-is-isError
  ;; rf2-acckgr regression: a nil / non-map envelope back from the
  ;; runtime (e.g. a dead runtime after a page reload answering blank)
  ;; must NOT silently ship as ok-text. `(:ok? nil)` is `nil`, not
  ;; `false`, so the old `(if (false? (:ok? rebuilt)) err-text ok-text)`
  ;; check let a nil envelope fall through to ok-text — masking the
  ;; failure as a success.
  (async done
    (let [conn (fresh-conn)
          _    (swap! conn update :probed-builds (fnil conj #{}) :app)]
      (-> (tu/with-stubbed-eval! nil
            (fn []
              (get-path/get-path-tool
                conn (tu/args->js {:path "[:cart :total]" :frame ":rf/default"}))))
          (.then (fn [r]
                   (is (tu/error? r)
                       "a blank/non-map eval result MUST be isError: true")
                   (let [edn (tu/extract-edn r)]
                     (is (false? (:ok? edn)))
                     (is (= :blank-eval-result (:reason edn))))
                   (done)))))))

;; ===========================================================================
;; Papercut 4 — snapshot full-mode epoch overflow.
;; ===========================================================================

;; --- 4a. tree-summary :bytes reflects per-entry depth (sampled). ---

(deftest tree-summary-bytes-samples-deep-vector-entries
  ;; A vector of DEEP entries (each a fat map) must report a `:bytes`
  ;; hint that reflects the per-entry depth — sampling a representative
  ;; entry, not a flat per-entry constant that would under-report epoch
  ;; slices by orders of magnitude.
  (let [fat-entry (zipmap (map #(keyword (str "k" %)) (range 200)) (range 200))
        deep-vec  (vec (repeat 5 fat-entry))
        scalar-vec [1 2 3 4 5]
        deep-bytes   (-> deep-vec   summary/tree-summary :rf.mcp/summary :bytes)
        scalar-bytes (-> scalar-vec summary/tree-summary :rf.mcp/summary :bytes)]
    (is (> deep-bytes (* 100 scalar-bytes))
        "a 5-entry vector of fat maps must estimate vastly more than a 5-scalar vector")))

(deftest tree-summary-bytes-scales-with-count-and-entry-size
  ;; The estimate is count × sampled-per-entry. Doubling the entry depth
  ;; roughly doubles the estimate at the same count.
  (let [small-entry {:a 1}
        big-entry   (zipmap (map #(keyword (str "k" %)) (range 50)) (range 50))
        small-bytes (-> (vec (repeat 10 small-entry)) summary/tree-summary :rf.mcp/summary :bytes)
        big-bytes   (-> (vec (repeat 10 big-entry))   summary/tree-summary :rf.mcp/summary :bytes)]
    (is (> big-bytes small-bytes)
        "a vector of bigger entries must estimate more bytes at equal count")))

(deftest tree-summary-bytes-still-linear-in-count
  ;; Same-shaped entries: 10x the count ⇒ ~10x the bytes (sampling reads
  ;; one entry, so the per-entry factor is constant).
  (let [entry {:a 1 :b 2}
        one   (-> [entry] summary/tree-summary :rf.mcp/summary :bytes)
        ten   (-> (vec (repeat 10 entry)) summary/tree-summary :rf.mcp/summary :bytes)]
    (is (= 10 (/ ten one)))))

(deftest tree-summary-bytes-handles-empty-collections
  ;; Empty collections have no entries, so the `count × per-entry`
  ;; estimate is a clean 0 — never NaN / negative. A single non-empty
  ;; entry floors at the per-entry minimum (no zero-byte estimate for a
  ;; real entry).
  (is (= 0 (-> [] summary/tree-summary :rf.mcp/summary :bytes)))
  (is (= 0 (-> #{} summary/tree-summary :rf.mcp/summary :bytes)))
  (is (= 0 (-> {} summary/tree-summary :rf.mcp/summary :bytes)))
  (is (= 0 (-> [] summary/tree-summary :rf.mcp/summary :count)))
  (is (pos? (-> [1] summary/tree-summary :rf.mcp/summary :bytes))
      "a real scalar entry floors above zero"))

(deftest tree-summary-map-bytes-samples-value-depth
  ;; A map of DEEP values estimates more than a map of scalar values at
  ;; equal count — the per-value sample captures depth.
  (let [deep-val (zipmap (map #(keyword (str "k" %)) (range 100)) (range 100))
        deep-map (zipmap (map #(keyword (str "m" %)) (range 5)) (repeat deep-val))
        flat-map (zipmap (map #(keyword (str "m" %)) (range 5)) (range 5))
        deep-bytes (-> deep-map summary/tree-summary :rf.mcp/summary :bytes)
        flat-bytes (-> flat-map summary/tree-summary :rf.mcp/summary :bytes)]
    (is (> deep-bytes (* 10 flat-bytes)))))

;; --- 4b. full-mode epoch-history record cap. ---

(defn- fat-epoch [i big-map]
  {:event-id (keyword (str "e" i)) :db-before big-map :db-after big-map})

(def ^:private big-map
  (zipmap (map #(keyword (str "k" %)) (range 200)) (range 200)))

(defn- snapshot-with-n-epochs [n]
  {:rf/default {:app-db    {:k 1}
                :sub-cache {}
                :machines  {}
                :epochs    (vec (for [i (range n)] (fat-epoch i big-map)))
                :traces    []}})

(deftest full-mode-caps-epoch-history-to-most-recent
  ;; A 30-record history in :full mode is capped to the most-recent N;
  ;; the dropped count + sibling marker make the truncation explicit.
  (let [snap   (snapshot-with-n-epochs 30)
        capped (pipeline/cap-full-epochs-in-snapshot snap {} :full pipeline/full-epochs-cap)
        epochs (-> capped :rf/default :epochs)
        meta   (-> capped :rf/default :rf.mcp/epochs-capped)]
    (is (= pipeline/full-epochs-cap (count epochs))
        "kept exactly the cap")
    (is (= :e29 (:event-id (last epochs)))
        "kept the MOST-RECENT records (tail of a chronological history)")
    (is (= :e20 (:event-id (first epochs)))
        "oldest kept record is total-cap from the end")
    (is (some? meta) "a truncation marker is attached")
    (is (= 30 (:total meta)))
    (is (= pipeline/full-epochs-cap (:shown meta)))
    (is (= (- 30 pipeline/full-epochs-cap) (:dropped meta)))
    (is (= :most-recent (:kept meta)))))

(deftest full-mode-under-cap-passes-through-untouched
  ;; A history at/under the cap is unchanged — no marker, no truncation.
  (let [snap   (snapshot-with-n-epochs pipeline/full-epochs-cap)
        capped (pipeline/cap-full-epochs-in-snapshot snap {} :full pipeline/full-epochs-cap)]
    (is (= pipeline/full-epochs-cap (count (-> capped :rf/default :epochs))))
    (is (not (contains? (:rf/default capped) :rf.mcp/epochs-capped))
        "no truncation marker when nothing was dropped")))

(deftest summary-mode-does-not-trigger-the-epoch-cap
  ;; In :summary mode the slice is a marker, not a vector — the cap is a
  ;; no-op (it gates on resolved :full mode).
  (let [snap   (snapshot-with-n-epochs 30)
        capped (pipeline/cap-full-epochs-in-snapshot snap {} :summary pipeline/full-epochs-cap)]
    (is (= 30 (count (-> capped :rf/default :epochs)))
        "summary-mode snapshot is left for the summariser to collapse")
    (is (not (contains? (:rf/default capped) :rf.mcp/epochs-capped)))))

(deftest per-slice-full-epochs-override-triggers-the-cap
  ;; Global :summary but per-slice `{:epochs :full}` — the cap fires
  ;; because :epochs RESOLVES to :full.
  (let [snap   (snapshot-with-n-epochs 25)
        capped (pipeline/cap-full-epochs-in-snapshot snap {:epochs :full} :summary
                                                      pipeline/full-epochs-cap)]
    (is (= pipeline/full-epochs-cap (count (-> capped :rf/default :epochs))))
    (is (= 25 (-> capped :rf/default :rf.mcp/epochs-capped :total)))))

(deftest cap-handles-non-map-and-missing-epochs
  ;; Defensive: a scalar frame / a frame without :epochs passes through.
  (is (= {:rf/default :scalar}
         (pipeline/cap-full-epochs-in-snapshot {:rf/default :scalar} {} :full 10)))
  (is (= {:rf/default {:app-db {:k 1}}}
         (pipeline/cap-full-epochs-in-snapshot {:rf/default {:app-db {:k 1}}} {} :full 10))))
