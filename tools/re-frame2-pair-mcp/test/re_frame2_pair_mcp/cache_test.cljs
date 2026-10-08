(ns re-frame2-pair-mcp.cache-test
  "Unit tests for the per-session response cache: an 8-slot LRU keyed on
  `(tool, build, args-fingerprint)` that replaces a repeat read's payload
  with a `{:rf.mcp/cache-hit ...}` marker."
  (:require [cljs.test :refer-macros [deftest is use-fixtures]]
            [cljs.reader :as edn]
            [applied-science.js-interop :as j]
            [re-frame2-pair-mcp.test-utils :as tu]
            [re-frame2-pair-mcp.cache :as cache]))

(use-fixtures :each
  {:before (fn [] (cache/clear!))
   :after  (fn [] (cache/clear!))})

(defn- mcp-result
  "An MCP `{:content [{:type \"text\" :text ...}]}` result carrying `text`."
  [text & {:keys [error?]}]
  (cond-> #js {:content #js [#js {:type "text" :text text}]}
    error? (j/assoc! :isError true)))

(def ^:private args-js tu/args->js)

(defn- marker-of
  "The `:rf.mcp/cache-hit` map carried by `result`, or nil."
  [result]
  (some-> (tu/extract-text result) edn/read-string :rf.mcp/cache-hit))

(deftest cacheable?-is-the-read-tool-allowlist
  ;; An action tool's result is the outcome of an action, and an
  ;; unregistered tool has no registry entry at all.
  (doseq [[tool cacheable?] [["dispatch" false]
                             ["unknown-tool" false]]]
    (is (= cacheable? (cache/cacheable? tool)) tool)))

(deftest args-fingerprint-stable-across-key-order
  (let [a (args-js {:frame ":rf/default" :path "[:cart :items]"})
        b (let [o #js {}]
            (j/assoc! o "path" "[:cart :items]")
            (j/assoc! o "frame" ":rf/default")
            o)]
    (is (= (cache/args->fingerprint a)
           (cache/args->fingerprint b)))))

(deftest same-state-call-returns-cache-hit
  ;; With no :precheck-hash stored, the repeat is caught by the post-eval
  ;; result-hash path.
  (let [opts {:tool "snapshot" :args (args-js {:frame ":rf/default"}) :enabled? true}
        text "{:ok? true :snapshot {:db {:k :v}}}"]
    (cache/apply-cache (mcp-result text) opts)
    (let [m (marker-of (cache/apply-cache (mcp-result text) opts))]
      (is (= {:tool "snapshot" :via :result-hash} (select-keys m [:tool :via])))
      (is (integer? (:hash m)))
      (is (number? (:unchanged-since m)))
      (is (string? (:hint m))))
    (is (= 1 (cache/size)) "the repeat reuses its slot")))

(deftest mutation-invalidates-cache
  (let [opts {:tool "snapshot" :args (args-js {:frame ":rf/default"}) :enabled? true}
        r2   (mcp-result "{:ok? true :snapshot {:db {:k :other}}}")]
    (cache/apply-cache (mcp-result "{:ok? true :snapshot {:db {:k :v}}}") opts)
    (is (identical? r2 (cache/apply-cache r2 opts))
        "a changed payload goes out fresh")
    (is (= 1 (cache/size)) "and replaces the entry in place")))

(deftest disabled-cache-is-pure-pass-through
  (let [off {:tool "snapshot" :args (args-js {:frame ":rf/default"}) :enabled? false}
        r   (mcp-result "{:ok? true}")]
    (is (identical? r (cache/apply-cache r off)))
    (is (zero? (cache/size)))
    (cache/apply-cache (mcp-result "{:k :v}") (assoc off :enabled? true :precheck-hash 12345))
    (is (nil? (cache/precheck off 12345))
        "no precheck hit even when a matching entry exists")))

(deftest get-operating-frame-never-serves-stale-cache-hit
  ;; It reads the live frame registry and the session pin, which move
  ;; without an app-db mutation, so byte-identical reads both go out fresh.
  (let [text "{:ok? true :frames [:rf/default] :selected nil :operating :rf/default}"
        opts {:tool "get-operating-frame" :args (args-js {}) :enabled? true}
        r2   (mcp-result text)]
    (cache/apply-cache (mcp-result text) opts)
    (is (identical? r2 (cache/apply-cache r2 opts)))
    (is (zero? (cache/size)))))

(deftest error-results-bypass-cache
  ;; A cached transient error would mask the next successful read.
  (let [err  (mcp-result "{:ok? false :reason :foo}" :error? true)
        opts {:tool "snapshot" :args (args-js {:frame ":rf/default"}) :enabled? true}]
    (is (identical? err (cache/apply-cache err opts)))
    (is (zero? (cache/size)))))

(deftest cache-key-discriminates-tool-args-and-build
  ;; A colliding precheck-hash or a byte-identical result under another
  ;; tool, args or build must never be served the primed entry.
  (let [base {:tool "get-path" :args (args-js {:path "[:k]"}) :enabled? true :build :app}
        text "{:k :v}"
        h    22222]
    (doseq [[axis other] [[:tool  (assoc base :tool "snapshot")]
                          [:args  (assoc base :args (args-js {:path "[:j]"}))]
                          [:build (assoc base :build :other)]]]
      (cache/clear!)
      (cache/apply-cache (mcp-result text) (assoc base :precheck-hash h))
      (is (nil? (cache/precheck other h)) (str axis ": no precheck hit"))
      (is (nil? (marker-of (cache/apply-cache (mcp-result text) other)))
          (str axis ": identical text is a fresh store, not a hit")))
    (is (some? (cache/precheck base h)) "the primed key itself still hits")))

(deftest lru-evicts-oldest-first
  ;; Capacity 8. A hit on either path touches its entry, so new stores
  ;; evict the least-recently-used entries instead.
  (let [opts (fn [i] {:tool "snapshot" :args (args-js {:frame (str ":f" i)}) :enabled? true})
        text (fn [i] (str "{:i " i "}"))]
    (dotimes [i 8]
      (cache/apply-cache (mcp-result (text i)) (assoc (opts i) :precheck-hash i)))
    (cache/precheck (opts 0) 0)
    (cache/apply-cache (mcp-result (text 1)) (opts 1))
    (cache/apply-cache (mcp-result (text 98)) (opts 98))
    (cache/apply-cache (mcp-result (text 99)) (opts 99))
    (is (= 8 (cache/size)))
    (is (= [true true false false]
           (mapv #(some? (cache/precheck (opts %) %)) [0 1 2 3]))
        "0 and 1 were touched by a precheck and a result-hash hit; 2 and 3 were the oldest")))

(deftest precheck-hit-short-circuits-with-marker
  ;; A matching current hash answers before the tool runs; any other hash
  ;; leaves the call to the full eval.
  (let [opts {:tool "snapshot" :args (args-js {:frame ":rf/default"}) :enabled? true}]
    (cache/apply-cache (mcp-result "{:ok? true :app-db {:k :v}}")
                       (assoc opts :precheck-hash 98765))
    (is (nil? (cache/precheck opts 67890)))
    (is (= :precheck (:via (marker-of (cache/precheck opts 98765)))))))
