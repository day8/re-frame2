;;;; tests/runtime/app_db_hash_test.clj
;;;;
;;;; Babashka-runnable verification of the `app-db-hash` O(1) accessor in
;;;; `preload/re_frame_pair2/runtime.cljs` (rf2-9pe31) — the per-frame
;;;; cached hash the pair2-mcp precheck (rf2-36xod) threads through to
;;;; decide a cache hit in one bencode round-trip.
;;;;
;;;; Why a parallel implementation lives here:
;;;;
;;;;   `preload/re_frame_pair2/runtime.cljs` is a CLJS-only file loaded
;;;;   into the consumer app via shadow-cljs `:devtools :preloads`. It
;;;;   depends on the live re-frame2 frame registry + epoch listener
;;;;   surface, neither of which run under bb. This file mirrors the
;;;;   cache + lazy-compute + update-on-epoch behaviour and pins the
;;;;   contract (every mutation rotates the cached hash; reads are O(1)
;;;;   atom-deref + map-lookup).
;;;;
;;;;   KEEP IN SYNC WITH preload/re_frame_pair2/runtime.cljs §O(1)
;;;;   per-frame app-db hash cache.
;;;;
;;;; Run:    bb tests/runtime/app_db_hash_test.clj
;;;; Exit:   0 = pass, non-zero = fail.

(ns app-db-hash-test
  (:require [clojure.test :refer [deftest is run-tests testing]]))

;; ---------------------------------------------------------------------------
;; Stubbed framework surfaces.
;; ---------------------------------------------------------------------------

(def ^:private frame-dbs (atom {:rf/default {:cart {:items 3}}
                                :stories    {:scenarios {:checkout :ready}}}))

(defn stub-get-frame-db [fid] (get @frame-dbs fid))

;; ---------------------------------------------------------------------------
;; Mirror of preload/re_frame_pair2/runtime.cljs §O(1) per-frame app-db
;; hash cache. KEEP IN SYNC.
;; ---------------------------------------------------------------------------

(def ^:private frame-db-hashes (atom {}))

(defn- update-frame-db-hash!
  "Update the cached hash for `frame-id` from the epoch record's
   `:db-after` slot. Called from the epoch listener."
  [frame-id db-after]
  (swap! frame-db-hashes assoc frame-id (hash db-after)))

(defn app-db-hash
  "Cheap O(1) accessor — cached value, lazy-compute on first read."
  [frame-id]
  (when frame-id
    (or (get @frame-db-hashes frame-id)
        (let [db (stub-get-frame-db frame-id)
              h  (hash db)]
          (swap! frame-db-hashes assoc frame-id h)
          h))))

;; Per-test reset.
(defn- reset-state! []
  (reset! frame-db-hashes {})
  (reset! frame-dbs {:rf/default {:cart {:items 3}}
                     :stories    {:scenarios {:checkout :ready}}}))

;; ---------------------------------------------------------------------------
;; Tests
;; ---------------------------------------------------------------------------

(deftest lazy-compute-on-first-read
  (reset-state!)
  (is (= {} @frame-db-hashes)
      "precondition: cache is empty before first read")
  (let [h1 (app-db-hash :rf/default)]
    (is (integer? h1) "returns an integer hash")
    (is (= h1 (hash {:cart {:items 3}}))
        "computes via (hash db) on the lazy path")
    (is (= h1 (get @frame-db-hashes :rf/default))
        "stashes the computed value so subsequent reads are O(1)")))

(deftest second-read-is-cache-hit
  (reset-state!)
  (let [h1 (app-db-hash :rf/default)
        ;; Mutate the underlying db WITHOUT firing the epoch listener.
        ;; The cache should still report the stale hash — proves the
        ;; reader does not re-walk the db on every call.
        _  (swap! frame-dbs assoc :rf/default {:wholly :different})
        h2 (app-db-hash :rf/default)]
    (is (= h1 h2)
        "subsequent reads return the cached value verbatim")))

(deftest update-rotates-the-cache
  (reset-state!)
  (let [h1 (app-db-hash :rf/default)
        new-db {:cart {:items 99}}
        _  (update-frame-db-hash! :rf/default new-db)
        h2 (app-db-hash :rf/default)]
    (is (not= h1 h2)
        "mutation through update-frame-db-hash! rotates the cache")
    (is (= h2 (hash new-db))
        "rotated value matches (hash new-db)")))

(deftest per-frame-isolation
  (reset-state!)
  (let [h-default (app-db-hash :rf/default)
        h-stories (app-db-hash :stories)]
    (is (not= h-default h-stories)
        "different frames produce different cached hashes")
    (update-frame-db-hash! :rf/default {:cart {:items 99}})
    (is (not= h-default (app-db-hash :rf/default))
        ":rf/default's hash rotated")
    (is (= h-stories (app-db-hash :stories))
        ":stories's hash is untouched by the :rf/default mutation")))

(deftest nil-frame-returns-nil
  (reset-state!)
  (is (nil? (app-db-hash nil))
      "nil frame-id short-circuits to nil"))

(deftest deterministic-across-reads
  (reset-state!)
  (let [h1 (app-db-hash :rf/default)
        h2 (app-db-hash :rf/default)
        h3 (app-db-hash :rf/default)]
    (is (= h1 h2 h3)
        "the cached value is identical across repeated reads")))

(deftest hash-equal-when-db-equal
  ;; Pin the bedrock invariant the precheck depends on: structurally-
  ;; equal app-db values hash to the same integer. This is a property
  ;; of CLJS persistent maps' `-hash` — restated here so a future
  ;; runtime change that swapped the cache key for, say, `identical?`
  ;; would fail this test fast.
  (reset-state!)
  (let [h1 (app-db-hash :rf/default)
        ;; A value-equal but freshly-constructed map.
        new-db {:cart {:items 3}}
        _  (update-frame-db-hash! :rf/default new-db)]
    (is (= h1 (app-db-hash :rf/default))
        "structurally-equal app-db values produce the same hash")))

(let [{:keys [fail error]} (run-tests 'app-db-hash-test)
      failures (+ (or fail 0) (or error 0))]
  (System/exit (if (zero? failures) 0 1)))
