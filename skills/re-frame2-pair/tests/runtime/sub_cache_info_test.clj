;;;; tests/runtime/sub_cache_info_test.clj
;;;;
;;;; Babashka-runnable verification of the `sub-cache-info` reactive
;;;; sub-cache reader in `preload/re_frame2_pair/runtime.cljs` — the
;;;; reader the re-frame2-pair MCP `list-subscriptions` tool wraps.
;;;;
;;;; ## What sub-cache-info reads
;;;;
;;;;   The MCP `list-subscriptions` tool wraps `sub-cache-info`, which reads
;;;;   the SAME live per-frame reactive sub-cache `snapshot`'s `:sub-cache`
;;;;   slice reads (`re-frame.subs.tooling/sub-cache-snapshot`) — the live
;;;;   reactive subscriptions, NOT the streaming-tap registry
;;;;   (`subscription-info`, the trace/epoch/fx/error queues). This test
;;;;   asserts the reader:
;;;;     - reports the cached query-vectors for a frame (the acceptance
;;;;       contract: `[["mounted?"]]` shows up when "mounted?" is cached);
;;;;     - AGREES with the sub-cache snapshot source by construction
;;;;       (both read the same cache);
;;;;     - reflects DISPOSAL — a query-v that's no longer in the cache
;;;;       (its reaction disposed) no longer appears;
;;;;     - resolves the operating frame (explicit > pin > sole frame)
;;;;       and refuses with `:ambiguous-frame` in a multi-frame session
;;;;       with no selection;
;;;;     - threads `:include-values?` into the per-entry shape.
;;;;
;;;; Why a parallel implementation lives here:
;;;;
;;;;   `preload/re_frame2_pair/runtime.cljs` is a CLJS-only file loaded
;;;;   into the consumer app via shadow-cljs `:devtools :preloads`. It
;;;;   depends on the live re-frame2 frame registry + sub-cache, none of
;;;;   which run under bb. This file mirrors `sub-cache-info` + the frame
;;;;   resolver against a stubbed sub-cache and asserts the contract.
;;;;
;;;; KEEP IN SYNC WITH preload/re_frame2_pair/runtime.cljs §Subscriptions
;;;; (`sub-cache-info`) and §Operating frame (`current-frame`).
;;;;
;;;; Run:    bb tests/runtime/sub_cache_info_test.clj
;;;; Exit:   0 = pass, non-zero = fail.

(ns sub-cache-info-test
  (:require [clojure.test :refer [deftest is run-tests testing use-fixtures]]))

;; ---------------------------------------------------------------------------
;; Stub frame registry + per-frame reactive sub-cache.
;;
;; `frame-sub-caches` stands in for the live per-frame sub-cache the
;; framework's `re-frame.subs.tooling/sub-cache-snapshot` projects:
;; `{query-v {:value v :ref-count n :input-kind k :realized-inputs [...]}}`.
;; Disposing a reaction removes its entry — modelled here by `dispose-sub!`.
;; ---------------------------------------------------------------------------

(def frame-registry (atom #{}))
(def frame-sub-caches (atom {}))   ;; frame-id -> {query-v {:value v :ref-count n :input-kind k :realized-inputs [...]}}
(def selected-frame (atom nil))

(defn register-frame! [frame-id]
  (swap! frame-registry conj frame-id)
  (swap! frame-sub-caches assoc frame-id {})
  frame-id)

(defn cache-sub!
  "Cache an entry. The 4-arity defaults a layer-1 `:db` reader (no
   realized input edges); the 6-arity sets the `:input-kind`
   + `:realized-inputs` slots explicitly (static / parametric subs)."
  ([frame-id query-v value ref-count]
   (cache-sub! frame-id query-v value ref-count :db []))
  ([frame-id query-v value ref-count input-kind realized-inputs]
   (swap! frame-sub-caches assoc-in [frame-id query-v]
          {:value value :ref-count ref-count
           :input-kind input-kind :realized-inputs realized-inputs})
   frame-id))

(defn dispose-sub!
  "Model the framework disposing a reaction once its last consumer
   drops — the entry leaves the live cache."
  [frame-id query-v]
  (swap! frame-sub-caches update frame-id dissoc query-v))

(defn frame-ids [] @frame-registry)

;; Stand-in for `re-frame.subs.tooling/sub-cache-snapshot` — the SAME
;; source `snapshot`'s `:sub-cache` slice reads. Returns nil for a
;; missing frame (mirrors the CLJS fn's nil-on-missing contract).
(defn sub-cache-snapshot [frame-id]
  (get @frame-sub-caches frame-id))

(defn select-frame! [frame-id] (reset! selected-frame frame-id))

;; Mirror of `current-frame`. KEEP IN SYNC.
(defn current-frame
  ([] (current-frame nil))
  ([override]
   (or override
       @selected-frame
       (let [fids (frame-ids)]
         (when (= 1 (count fids)) (first fids))))))

;; ---------------------------------------------------------------------------
;; Mirror of `sub-cache-info`. KEEP IN SYNC WITH
;; preload/re_frame2_pair/runtime.cljs §Subscriptions.
;; ---------------------------------------------------------------------------

;; Mirror of `ambiguous-frame-error` — the load-bearing slots
;; (operation, available frames, current pin). KEEP THE SLOT CONTRACT IN SYNC.
(defn ambiguous-frame-error [operation]
  {:ok? false :reason :ambiguous-frame :operation operation
   :available-frames (vec (frame-ids))
   :selected-frame @selected-frame})

(defn sub-cache-info
  ([] (sub-cache-info {}))
  ([opts]
   (let [{:keys [frame include-values?]} opts
         frame-id (current-frame frame)]
     (if (nil? frame-id)
       (ambiguous-frame-error :sub-cache-info)
       (let [cache (or (sub-cache-snapshot frame-id) {})
             qvs   (sort-by pr-str (keys cache))]
         {:ok?   true
          :frame frame-id
          :count (count qvs)
          :subs  (if include-values?
                   (mapv (fn [q]
                           (let [{:keys [value ref-count input-kind realized-inputs]}
                                 (get cache q)]
                             {:query-v q :value value :ref-count ref-count
                              :input-kind input-kind
                              :realized-inputs realized-inputs}))
                         qvs)
                   (vec qvs))})))))

(defn reset-state! []
  (reset! frame-registry #{})
  (reset! frame-sub-caches {})
  (reset! selected-frame nil))

(use-fixtures :each (fn [t] (reset-state!) (t) (reset-state!)))

;; ---------------------------------------------------------------------------
;; Tests
;; ---------------------------------------------------------------------------

(deftest reports-cached-query-vectors-for-the-frame
  ;; The acceptance contract: with ["mounted?"] cached in :rf/default,
  ;; list-subscriptions {frame :rf/default} surfaces it.
  (register-frame! :rf/default)
  (cache-sub! :rf/default ["mounted?"] true 1)
  (let [r (sub-cache-info {:frame :rf/default})]
    (is (true? (:ok? r)))
    (is (= :rf/default (:frame r)))
    (is (= 1 (:count r)))
    (is (= [["mounted?"]] (:subs r))
        "the cached query-vector is reported (matches snapshot :sub-cache)")))

(deftest agrees-with-sub-cache-snapshot-source
  ;; sub-cache-info reads sub-cache-snapshot — the SAME source snapshot's
  ;; :sub-cache slice reads — so the query-vectors AGREE by construction.
  (register-frame! :rf/xray)
  (cache-sub! :rf/xray ["subs-filter-mode"] :all 1)
  (cache-sub! :rf/xray ["active-filters"] #{} 1)
  (cache-sub! :rf/xray ["for-table" "views"] [] 2)
  (let [info-qvs (set (:subs (sub-cache-info {:frame :rf/xray})))
        ;; what snapshot's :sub-cache slice would key on for the frame:
        snap-qvs (set (keys (sub-cache-snapshot :rf/xray)))]
    (is (= snap-qvs info-qvs)
        "list-subscriptions query-vectors == snapshot :sub-cache keys for the same frame")
    (is (= 3 (:count (sub-cache-info {:frame :rf/xray}))))))

(deftest reflects-disposal
  ;; After a view unmounts and its sub is disposed, it no longer appears.
  (register-frame! :rf/default)
  (cache-sub! :rf/default ["mounted?"] true 1)
  (cache-sub! :rf/default ["cart" "total"] 42 1)
  (is (= 2 (:count (sub-cache-info {:frame :rf/default}))))
  (dispose-sub! :rf/default ["cart" "total"])
  (let [r (sub-cache-info {:frame :rf/default})]
    (is (= 1 (:count r)))
    (is (= [["mounted?"]] (:subs r))
        "the disposed sub dropped off; the live one remains")))

(deftest empty-cache-is-ok-with-empty-subs
  (register-frame! :rf/default)
  (let [r (sub-cache-info {:frame :rf/default})]
    (is (true? (:ok? r)) "empty cache is :ok? true, never an error")
    (is (= 0 (:count r)))
    (is (= [] (:subs r)))))

(deftest include-values-carries-value-ref-count-input-kind-realized
  ;; :include-values? surfaces :input-kind + :realized-inputs alongside
  ;; :value / :ref-count. A layer-1 :db reader has no realized input edges.
  (register-frame! :rf/default)
  (cache-sub! :rf/default ["cart" "total"] 4200 2)
  (let [r (sub-cache-info {:frame :rf/default :include-values? true})]
    (is (= [{:query-v ["cart" "total"] :value 4200 :ref-count 2
             :input-kind :db :realized-inputs []}]
           (:subs r)))))

(deftest include-values-surfaces-realized-parametric-inputs
  ;; The egress contract: a PARAMETRIC sub's live cache entry
  ;; surfaces its REALIZED input query-vectors (the (input-fn query-v)
  ;; result for the concrete outer query-v), the runtime counterpart to
  ;; the static sub-topology's :inputs :parametric sentinel.
  (register-frame! :rf/default)
  ;; static :<- sub — realized inputs are the literal :<- list
  (cache-sub! :rf/default [:visible-items] [] 1
              :static [[:items] [:filter]])
  ;; parametric input-fn sub — realized inputs are the concrete edges
  (cache-sub! :rf/default [:article/page :a1] {} 1
              :parametric [[:article/by-id :a1]
                           [:comments/for-article :a1]
                           [:viewer/current]])
  (let [subs (:subs (sub-cache-info {:frame :rf/default :include-values? true}))
        by-q (into {} (map (juxt :query-v identity)) subs)]
    (is (= :static (:input-kind (by-q [:visible-items]))))
    (is (= [[:items] [:filter]] (:realized-inputs (by-q [:visible-items])))
        "static realized inputs are the literal :<- query-vectors")
    (is (= :parametric (:input-kind (by-q [:article/page :a1]))))
    (is (= [[:article/by-id :a1]
            [:comments/for-article :a1]
            [:viewer/current]]
           (:realized-inputs (by-q [:article/page :a1])))
        "parametric realized inputs are the (input-fn query-v) result for :a1")))

(deftest resolves-sole-frame-without-explicit-arg
  (register-frame! :rf/default)
  (cache-sub! :rf/default ["mounted?"] true 1)
  (let [r (sub-cache-info)]
    (is (= :rf/default (:frame r)) "the sole registered frame is the operating frame")
    (is (= [["mounted?"]] (:subs r)))))

(deftest refuses-ambiguous-frame-in-multi-frame-session
  (register-frame! :rf/default)
  (register-frame! :rf/xray)
  (let [r (sub-cache-info)]
    (is (false? (:ok? r)))
    (is (= :ambiguous-frame (:reason r))
        "two frames + no selection ⇒ refuse rather than silently read :rf/default")
    ;; Enriched: operation + the available frames the caller can pin.
    (is (= :sub-cache-info (:operation r)))
    (is (= #{:rf/default :rf/xray} (set (:available-frames r))))))

(deftest explicit-frame-and-pin-both-steer-the-read
  (register-frame! :rf/default)
  (register-frame! :rf/xray)
  (cache-sub! :rf/xray ["xray-thing"] 1 1)
  (testing "explicit :frame arg wins"
    (is (= [["xray-thing"]] (:subs (sub-cache-info {:frame :rf/xray})))))
  (testing "select-frame! pin steers a no-arg read"
    (select-frame! :rf/xray)
    (is (= :rf/xray (:frame (sub-cache-info))))))

(deftest query-vectors-sorted-stable-across-calls
  (register-frame! :rf/default)
  (cache-sub! :rf/default ["z"] 1 1)
  (cache-sub! :rf/default ["a"] 1 1)
  (cache-sub! :rf/default ["m"] 1 1)
  (is (= [["a"] ["m"] ["z"]] (:subs (sub-cache-info {:frame :rf/default})))
      "sorted by pr-str — stable listing across calls"))

(let [{:keys [fail error]} (run-tests 'sub-cache-info-test)]
  (System/exit (if (zero? (+ (or fail 0) (or error 0))) 0 1)))
