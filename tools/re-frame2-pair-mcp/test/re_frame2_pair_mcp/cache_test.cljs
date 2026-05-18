(ns re-frame2-pair-mcp.cache-test
  "Unit tests for the per-session response cache (rf2-3rt1f).

  Per `tools/re-frame2-pair-mcp/spec/Principles.md` §\"Per-session response
  cache\", every read-tool result passes through an 8-slot LRU keyed
  on `(tool, args-fingerprint)`. On a hit (same hash for the same
  (tool, args) as the prior call), the full payload is replaced with
  a `{:rf.mcp/cache-hit ...}` marker. On a miss or fresh hash, the
  result is stored and returned unchanged.

  These tests pin the contract directly against the real
  `re-frame2-pair-mcp.cache` namespace (not a mirror — the module
  is fresh and has no JS interop that would force a copy)."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [cljs.reader :as edn]
            [applied-science.js-interop :as j]
            [re-frame2-pair-mcp.cache :as cache]))

;; ---------------------------------------------------------------------------
;; Fixtures — cache is module-level state; reset between tests so each
;; case starts from a clean slate.
;; ---------------------------------------------------------------------------

(use-fixtures :each
  {:before (fn [] (cache/clear!))
   :after  (fn [] (cache/clear!))})

;; ---------------------------------------------------------------------------
;; Test helpers — build MCP-shaped JS results with predictable text.
;; ---------------------------------------------------------------------------

(defn- mcp-result
  "Build an MCP `{:content [{:type \"text\" :text ...}]}` shape with
  the given text. Mirrors the output of `ok-text` in `tools.cljs`."
  [text & {:keys [error?]}]
  (cond-> #js {:content #js [#js {:type "text" :text text}]}
    error? (j/assoc! :isError true)))

(defn- args-js
  "Construct a JS args object from a CLJS map (helps the
  `j/get`-by-name paths in `args->fingerprint`)."
  [m]
  (let [o #js {}]
    (doseq [[k v] m]
      (j/assoc! o (name k) v))
    o))

(defn- extract-text
  "Pull the first text slot off an MCP result."
  [result]
  (let [content (j/get result :content)
        item    (when (array? content) (aget content 0))]
    (when item (j/get item :text))))

(defn- cache-hit-result?
  "True iff `result` is a `:rf.mcp/cache-hit` marker."
  [result]
  (let [t (extract-text result)
        v (when t (try (edn/read-string t) (catch :default _ nil)))]
    (and (map? v) (contains? v :rf.mcp/cache-hit))))

;; ---------------------------------------------------------------------------
;; `:cache` MCP-arg normalisation now lives on the shared table-driven
;; parser — see `re-frame2-pair-mcp.args-test` (rf2-c4fmh).
;; ---------------------------------------------------------------------------

;; ---------------------------------------------------------------------------
;; cacheable? — tool-allowlist guard.
;; ---------------------------------------------------------------------------

(deftest cacheable-read-tools
  ;; The five read tools listed in `cacheable-tools` should all hit.
  (is (cache/cacheable? "snapshot"))
  (is (cache/cacheable? "get-path"))
  (is (cache/cacheable? "trace-window"))
  (is (cache/cacheable? "watch-epochs"))
  (is (cache/cacheable? "discover-app")))

(deftest cacheable-excludes-action-and-streaming-tools
  ;; Action tools' return values are the result of an action; their
  ;; hashes will differ even on \"identical\" state.
  (is (not (cache/cacheable? "dispatch")))
  (is (not (cache/cacheable? "eval-cljs")))
  (is (not (cache/cacheable? "tail-build")))
  (is (not (cache/cacheable? "subscribe")))
  (is (not (cache/cacheable? "unsubscribe")))
  (is (not (cache/cacheable? "unknown-tool"))))

;; ---------------------------------------------------------------------------
;; args->fingerprint — stable across JS-object key order.
;; ---------------------------------------------------------------------------

(deftest args-fingerprint-stable-across-key-order
  ;; Same logical args, different insertion order → same fingerprint.
  (let [a (args-js {:frame ":rf/default" :path "[:cart :items]"})
        b (let [o #js {}]
            (j/assoc! o "path" "[:cart :items]")
            (j/assoc! o "frame" ":rf/default")
            o)]
    (is (= (cache/args->fingerprint a)
           (cache/args->fingerprint b))
        "args fingerprint is order-insensitive")))

(deftest args-fingerprint-empty-cases
  (is (nil? (cache/args->fingerprint nil)))
  (is (nil? (cache/args->fingerprint js/undefined)))
  (is (= {} (cache/args->fingerprint #js {}))))

(deftest args-fingerprint-distinguishes-different-args
  (let [a (args-js {:frame ":rf/default"})
        b (args-js {:frame ":stories"})]
    (is (not= (cache/args->fingerprint a)
              (cache/args->fingerprint b)))))

;; ---------------------------------------------------------------------------
;; hash-result — sensitive to text + error flag.
;; ---------------------------------------------------------------------------

(deftest hash-result-stable-for-same-text
  (let [r1 (mcp-result "{:ok? true :app-db {:k :v}}")
        r2 (mcp-result "{:ok? true :app-db {:k :v}}")]
    (is (= (cache/hash-result r1) (cache/hash-result r2)))))

(deftest hash-result-differs-for-different-text
  (let [r1 (mcp-result "{:ok? true :app-db {:k :v}}")
        r2 (mcp-result "{:ok? true :app-db {:k :other}}")]
    (is (not= (cache/hash-result r1) (cache/hash-result r2)))))

(deftest hash-result-distinguishes-error-vs-success
  ;; Same text payload but :isError true vs. false should hash
  ;; distinctly — otherwise a transient error could mask the success.
  (let [text "{:ok? false :reason :foo}"
        ok   (mcp-result text)
        err  (mcp-result text :error? true)]
    (is (not= (cache/hash-result ok) (cache/hash-result err)))))

;; ---------------------------------------------------------------------------
;; The load-bearing scenarios from the bead.
;; ---------------------------------------------------------------------------

(deftest fresh-call-stores-and-returns-unchanged
  ;; First call: cache is cold → return the original result, record
  ;; the hash, leave a single entry behind.
  (let [args   (args-js {:frame ":rf/default"})
        result (mcp-result "{:ok? true :snapshot {:db {:k :v}}}")
        out    (cache/apply-cache result {:tool "snapshot"
                                          :args args
                                          :enabled? true})]
    (is (identical? result out)
        "fresh call returns the original result untouched")
    (is (not (cache-hit-result? out))
        "fresh call does not emit a cache-hit marker")
    (is (= 1 (cache/size))
        "fresh call stored an entry in the LRU")))

(deftest same-state-call-returns-cache-hit
  ;; Second call with byte-identical state: the hash matches, the
  ;; LRU emits the marker instead of the full payload.
  (let [args (args-js {:frame ":rf/default"})
        text "{:ok? true :snapshot {:db {:k :v}}}"
        r1   (mcp-result text)
        r2   (mcp-result text)
        opts {:tool "snapshot" :args args :enabled? true}]
    ;; Prime the cache.
    (cache/apply-cache r1 opts)
    ;; Second call hits.
    (let [out (cache/apply-cache r2 opts)]
      (is (cache-hit-result? out)
          "second call returns a :rf.mcp/cache-hit marker")
      (let [v (edn/read-string (extract-text out))]
        (testing "marker carries the expected slots"
          (is (= "snapshot"
                 (get-in v [:rf.mcp/cache-hit :tool])))
          (is (number?
                (get-in v [:rf.mcp/cache-hit :unchanged-since])))
          (is (integer?
                (get-in v [:rf.mcp/cache-hit :hash])))
          (is (string?
                (get-in v [:rf.mcp/cache-hit :hint]))))))
    (is (= 1 (cache/size))
        "no new entry — same key still occupies one slot")))

(deftest mutation-invalidates-cache
  ;; Third scenario: state moves on between calls. Second call's hash
  ;; differs → the LRU stores the new hash and returns the fresh
  ;; payload, NOT a hit marker. The agent host gets the new bytes.
  (let [args (args-js {:frame ":rf/default"})
        r1   (mcp-result "{:ok? true :snapshot {:db {:k :v}}}")
        r2   (mcp-result "{:ok? true :snapshot {:db {:k :other}}}")
        opts {:tool "snapshot" :args args :enabled? true}]
    ;; Prime.
    (cache/apply-cache r1 opts)
    ;; Mutation → different text → different hash → miss.
    (let [out (cache/apply-cache r2 opts)]
      (is (identical? r2 out)
          "mutation returns the fresh result untouched")
      (is (not (cache-hit-result? out))
          "mutation does not emit a cache-hit marker"))
    (is (= 1 (cache/size))
        "key was overwritten in place, not duplicated")))

;; ---------------------------------------------------------------------------
;; Opt-out / bypass semantics.
;; ---------------------------------------------------------------------------

(deftest disabled-cache-is-pure-pass-through
  ;; enabled? false → nothing stored, nothing read.
  (let [args (args-js {:frame ":rf/default"})
        r    (mcp-result "{:ok? true}")
        opts {:tool "snapshot" :args args :enabled? false}]
    (let [out (cache/apply-cache r opts)]
      (is (identical? r out)))
    (is (zero? (cache/size))
        "disabled cache stores nothing")))

(deftest non-cacheable-tool-bypasses
  ;; Action tools (`dispatch`, `eval-cljs`, `tail-build`) bypass even
  ;; when `enabled? true`.
  (let [args (args-js {:event "[:cart/checkout]"})
        r    (mcp-result "{:ok? true :dispatched? true}")
        opts {:tool "dispatch" :args args :enabled? true}]
    (let [out (cache/apply-cache r opts)]
      (is (identical? r out)))
    (is (zero? (cache/size))
        "action tools never poison the cache")))

(deftest error-results-bypass-cache
  ;; An :isError result should not be cached — a transient error
  ;; would otherwise mask future successful reads on the same key.
  (let [args (args-js {:frame ":rf/default"})
        err  (mcp-result "{:ok? false :reason :foo}" :error? true)
        opts {:tool "snapshot" :args args :enabled? true}]
    (let [out (cache/apply-cache err opts)]
      (is (identical? err out)
          "error result passes through untouched"))
    (is (zero? (cache/size))
        "error did not poison the cache")
    ;; Subsequent successful call still treated as fresh.
    (let [ok (mcp-result "{:ok? true}")]
      (let [out (cache/apply-cache ok opts)]
        (is (identical? ok out)
            "successful follow-up is a fresh miss")))))

(deftest different-tools-same-args-do-not-collide
  ;; (snapshot, args) and (get-path, args) are different keys.
  (let [args (args-js {:frame ":rf/default"})
        text "{:ok? true}"
        r1   (mcp-result text)
        r2   (mcp-result text)]
    (cache/apply-cache r1 {:tool "snapshot" :args args :enabled? true})
    (let [out (cache/apply-cache r2 {:tool "get-path" :args args :enabled? true})]
      (is (identical? r2 out)
          "get-path miss — separate key from snapshot"))
    (is (= 2 (cache/size))
        "both tools have their own slot")))

;; ---------------------------------------------------------------------------
;; LRU policy — capacity + eviction.
;; ---------------------------------------------------------------------------

(deftest lru-bounded-at-capacity
  ;; Capacity is 8. 12 distinct (tool, args) pairs → 8 entries
  ;; survive, the 4 oldest get evicted.
  (let [opts (fn [i] {:tool "snapshot"
                      :args (args-js {:frame (str ":f" i)})
                      :enabled? true})]
    (dotimes [i 12]
      (cache/apply-cache (mcp-result (str "{:i " i "}")) (opts i)))
    (is (= 8 (cache/size))
        "LRU never exceeds capacity")))

(deftest lru-evicts-oldest-first
  ;; Fill to capacity, then add one more — the first key is gone.
  (let [opts (fn [i] {:tool "snapshot"
                      :args (args-js {:frame (str ":f" i)})
                      :enabled? true})]
    (dotimes [i 8]
      (cache/apply-cache (mcp-result (str "{:i " i "}")) (opts i)))
    (is (= 8 (cache/size)))
    ;; A re-call against the OLDEST key should still be a hit (it's
    ;; been in the cache since we primed it).
    (let [out (cache/apply-cache (mcp-result "{:i 0}") (opts 0))]
      (is (cache-hit-result? out)
          "before eviction, oldest entry still hits"))
    ;; Add a NEW key — capacity stays at 8, but the oldest
    ;; un-touched entry rotates out. After the touch above, entry 0
    ;; is now most-recently-used; entry 1 is the new oldest.
    (cache/apply-cache (mcp-result "{:i 99}") (opts 99))
    (is (= 8 (cache/size)))
    ;; Entry 1 should be evicted now → cold miss on re-call.
    (let [out (cache/apply-cache (mcp-result "{:i 1}") (opts 1))]
      (is (not (cache-hit-result? out))
          "least-recently-used key was evicted; re-call is a miss"))))

;; ---------------------------------------------------------------------------
;; Marker shape — cross-MCP vocabulary.
;; ---------------------------------------------------------------------------

(deftest cache-hit-marker-uses-rf-mcp-namespace
  ;; The `:rf.mcp/cache-hit` key follows the same namespace convention
  ;; as `:rf.mcp/overflow`, `:rf.mcp/dedup-table`, etc. Agents
  ;; recognise the family.
  (let [args (args-js {})
        text "{:ok? true}"
        opts {:tool "snapshot" :args args :enabled? true}]
    (cache/apply-cache (mcp-result text) opts)
    (let [hit (cache/apply-cache (mcp-result text) opts)
          v   (edn/read-string (extract-text hit))]
      (is (contains? v :rf.mcp/cache-hit)
          "marker key is :rf.mcp/cache-hit"))))

(deftest cache-hit-marker-payload-is-small
  ;; The whole point — the marker should be sub-100 bytes so it can't
  ;; itself blow the wire-cap.
  (let [args (args-js {:frame ":rf/default"})
        big  (mcp-result (apply str (repeat 50000 \X)))
        opts {:tool "snapshot" :args args :enabled? true}]
    (cache/apply-cache big opts)
    (let [hit  (cache/apply-cache big opts)
          text (extract-text hit)]
      (is (cache-hit-result? hit))
      (is (< (count text) 500)
          "cache-hit marker fits well under a 5K-token cap"))))

(deftest cache-hit-marker-carries-via-slot
  ;; rf2-36xod: the marker carries `:via` to distinguish a pre-eval
  ;; short-circuit (`:precheck`) from the legacy post-eval match
  ;; (`:result-hash`). Agents that read the marker can attribute the
  ;; saving correctly; tooling can graph cache-hit volume by source.
  (let [args (args-js {:frame ":rf/default"})
        text "{:ok? true :app-db {:k :v}}"
        opts {:tool "snapshot" :args args :enabled? true}]
    (cache/apply-cache (mcp-result text) opts)
    (let [hit (cache/apply-cache (mcp-result text) opts)
          v   (edn/read-string (extract-text hit))]
      (is (= :result-hash (get-in v [:rf.mcp/cache-hit :via]))
          "apply-cache hit annotates :via :result-hash"))))

;; ---------------------------------------------------------------------------
;; Precheck — rf2-36xod. Decide cache-hit BEFORE running the tool.
;;
;; Same `apply-cache` contract — same `(tool, args)` key, same LRU
;; semantics, same marker shape — but with an extra `:precheck-hash`
;; slot the caller can match against a runtime-side cheap hash. On
;; a match, `precheck` emits the marker WITHOUT the caller ever
;; running the tool. Saves the nREPL round-trip + full transform
;; pipeline, not just the wire bytes.
;; ---------------------------------------------------------------------------

(defn- via-of
  "Pull the `:via` slot out of a cache-hit marker result."
  [result]
  (let [v (edn/read-string (extract-text result))]
    (get-in v [:rf.mcp/cache-hit :via])))

(deftest precheck-returns-nil-without-prior-entry
  ;; No prior entry for (tool, args) → precheck has nothing to match
  ;; against → returns nil (caller proceeds with the full tool eval).
  (let [args (args-js {:frame ":rf/default"})
        opts {:tool "snapshot" :args args :enabled? true}]
    (is (nil? (cache/precheck opts 12345))
        "cold cache → precheck returns nil")))

(deftest precheck-returns-nil-when-disabled
  ;; `enabled? false` is a global opt-out — precheck must not engage.
  ;; First prime the cache with a precheck-hash so a hit COULD happen
  ;; if precheck were enabled.
  (let [args      (args-js {:frame ":rf/default"})
        opts-on   {:tool "snapshot" :args args :enabled? true
                   :precheck-hash 12345}
        opts-off  {:tool "snapshot" :args args :enabled? false}]
    (cache/apply-cache (mcp-result "{:k :v}") opts-on)
    (is (nil? (cache/precheck opts-off 12345))
        "disabled cache → precheck always nil, even with matching hash")))

(deftest precheck-returns-nil-for-non-cacheable-tool
  ;; Action tools never participate in the cache; precheck mirrors
  ;; that opt-out.
  (let [args (args-js {:event "[:cart/checkout]"})
        opts {:tool "dispatch" :args args :enabled? true}]
    (is (nil? (cache/precheck opts 12345))
        "dispatch is not cacheable → precheck returns nil")))

(deftest precheck-returns-nil-when-no-current-hash
  ;; Caller has no precheck wiring for this tool → passes nil → we
  ;; return nil and let the legacy post-eval path take over.
  (let [args (args-js {:frame ":rf/default"})
        opts {:tool "snapshot" :args args :enabled? true}]
    ;; Prime the cache with a precheck-hash.
    (cache/apply-cache (mcp-result "{:k :v}")
                       (assoc opts :precheck-hash 12345))
    (is (nil? (cache/precheck opts nil))
        "no current-precheck-hash supplied → precheck returns nil")))

(deftest precheck-returns-nil-when-prior-has-no-precheck-hash
  ;; A prior entry without a stored :precheck-hash (e.g. cached
  ;; before precheck wiring existed, or the precheck fetch failed
  ;; that round) cannot short-circuit even with a current hash.
  (let [args (args-js {:frame ":rf/default"})
        opts {:tool "snapshot" :args args :enabled? true}]
    ;; Prime WITHOUT a precheck-hash.
    (cache/apply-cache (mcp-result "{:k :v}") opts)
    (is (nil? (cache/precheck opts 12345))
        "no stored :precheck-hash → precheck returns nil even with current hash")))

(deftest precheck-hit-short-circuits-with-marker
  ;; The load-bearing path. Prime the cache with a precheck-hash;
  ;; a second call carrying the SAME hash → precheck returns the
  ;; marker. The caller never runs the tool.
  (let [args (args-js {:frame ":rf/default"})
        opts {:tool "snapshot" :args args :enabled? true}
        h    98765]
    ;; Prime — tool ran once, result + precheck-hash stored.
    (cache/apply-cache (mcp-result "{:ok? true :app-db {:k :v}}")
                       (assoc opts :precheck-hash h))
    ;; Second tool call would be preceded by a precheck.
    (let [out (cache/precheck opts h)]
      (is (some? out)
          "matching precheck-hash → marker returned")
      (is (cache-hit-result? out)
          "marker shape is :rf.mcp/cache-hit")
      (is (= :precheck (via-of out))
          "marker carries :via :precheck — distinguishes from result-hash path"))))

(deftest precheck-miss-on-different-hash
  ;; State moved on → current hash differs from stored → precheck
  ;; returns nil and the caller falls back to the full tool eval.
  (let [args (args-js {:frame ":rf/default"})
        opts {:tool "snapshot" :args args :enabled? true}]
    (cache/apply-cache (mcp-result "{:ok? true :app-db {:k :v}}")
                       (assoc opts :precheck-hash 12345))
    (is (nil? (cache/precheck opts 67890))
        "different current-hash → precheck returns nil")))

(deftest precheck-uses-tool-args-key
  ;; Cache key is `(tool, args)` — a precheck for (snapshot, frame=:a)
  ;; must NOT hit an entry stored for (snapshot, frame=:b) even when
  ;; the hashes match (because the hashes would, in real usage, be
  ;; the per-frame app-db hash — different frames have different dbs).
  (let [args-a (args-js {:frame ":rf/a"})
        args-b (args-js {:frame ":rf/b"})
        h      11111
        opts-a {:tool "snapshot" :args args-a :enabled? true}
        opts-b {:tool "snapshot" :args args-b :enabled? true}]
    (cache/apply-cache (mcp-result "{:db {:k :v}}")
                       (assoc opts-a :precheck-hash h))
    (is (nil? (cache/precheck opts-b h))
        "different args → different key → precheck returns nil")
    (is (some? (cache/precheck opts-a h))
        "matching key → precheck hits")))

(deftest precheck-hit-touches-lru
  ;; A precheck hit is still a use of the entry — touch the LRU so
  ;; the entry doesn't rotate out under capacity pressure.
  (let [opts (fn [i] {:tool "snapshot"
                      :args (args-js {:frame (str ":f" i)})
                      :enabled? true})]
    ;; Fill to capacity with precheck-hashes.
    (dotimes [i 8]
      (cache/apply-cache (mcp-result (str "{:i " i "}"))
                         (assoc (opts i) :precheck-hash (* i 1000))))
    (is (= 8 (cache/size)))
    ;; Touch the OLDEST entry via precheck.
    (is (some? (cache/precheck (opts 0) 0))
        "oldest entry still precheck-hits")
    ;; Now add a new entry — the LRU evicts entry 1 (current oldest
    ;; since entry 0 was just touched), not entry 0.
    (cache/apply-cache (mcp-result "{:i 99}")
                       (assoc (opts 99) :precheck-hash 99000))
    (is (= 8 (cache/size)))
    (is (some? (cache/precheck (opts 0) 0))
        "entry 0 survived — precheck-hit touched the LRU")
    (is (nil? (cache/precheck (opts 1) 1000))
        "entry 1 evicted as the new oldest")))

(deftest precheck-hash-stored-by-apply-cache
  ;; `apply-cache` is the single write surface — when the caller
  ;; supplies `:precheck-hash`, it's stored alongside the result
  ;; hash so the next `precheck` call can match it.
  (let [args (args-js {:frame ":rf/default"})
        opts {:tool "snapshot" :args args :enabled? true
              :precheck-hash 54321}]
    (cache/apply-cache (mcp-result "{:k :v}") opts)
    (is (some? (cache/precheck (dissoc opts :precheck-hash) 54321))
        "precheck-hash supplied at store time is queryable on the next call")))

(deftest precheck-hash-absent-when-not-supplied
  ;; Backwards-compat path. When `apply-cache` is called WITHOUT
  ;; `:precheck-hash` (the rf2-3rt1f-era call sites), the stored
  ;; entry doesn't carry one; future precheck attempts on that key
  ;; return nil and the legacy post-eval cache catches the hit.
  (let [args (args-js {:frame ":rf/default"})
        opts {:tool "snapshot" :args args :enabled? true}
        text "{:k :v}"]
    ;; First store — no precheck-hash.
    (cache/apply-cache (mcp-result text) opts)
    (is (nil? (cache/precheck opts 12345))
        "no stored :precheck-hash → precheck returns nil")
    ;; Legacy post-eval path still works.
    (let [hit (cache/apply-cache (mcp-result text) opts)]
      (is (cache-hit-result? hit)
          "post-eval cache-hit still fires when text matches")
      (is (= :result-hash (via-of hit))
          "marker carries :via :result-hash"))))
