;;;; tests/runtime/snapshot_test.clj
;;;;
;;;; Babashka-runnable verification of the `snapshot-state` mega-op in
;;;; `preload/re_frame2_pair/runtime.cljs` — the coarse-grained
;;;; per-frame state reader the re-frame2-pair MCP server exposes as the
;;;; `snapshot` tool.
;;;;
;;;; Why a parallel implementation lives here:
;;;;
;;;; `preload/re_frame2_pair/runtime.cljs` is a CLJS-only file loaded
;;;; into the consumer app via shadow-cljs `:devtools :preloads`. It
;;;; depends on the live re-frame2 frame registry, sub-cache, trace
;;;; buffer, and epoch history — none of which run under bb. The real
;;;; shadow-cljs test build (planned per `docs/TESTING.md` §1) will
;;;; exercise the `.cljs` source in place under Node; until then this
;;;; file mirrors the slice routing and the per-frame composer and
;;;; asserts behaviour against stubbed framework surfaces.
;;;;
;;;; KEEP IN SYNC WITH preload/re_frame2_pair/runtime.cljs §Coarse-grained
;;;; snapshot.
;;;;
;;;; Run: bb tests/runtime/snapshot_test.clj
;;;; Exit: 0 = pass, non-zero = fail.

(ns snapshot-test
 (:require [clojure.test :refer [deftest is run-tests testing]]))

;; ---------------------------------------------------------------------------
;; Stubbed framework surfaces.
;; ---------------------------------------------------------------------------
;;
;; Two registered frames with predictable per-slice state. The composer
;; under test routes by `:include` keys and assembles one map per
;; frame-id.

;; Machine snapshots are RUNTIME-DB-partition state. The fixture carries a
;; `:runtime-db` partition per frame (read via `rf/runtime-db-value` in
;; production); the `:machines` slice sources its `:state` from
;; `[:rf.runtime/machines :snapshots]` there, NOT from app-db `:rf/runtime`.
;; KEEP IN SYNC with preload/re_frame2_pair/runtime.cljs.
(def fixture-frames
 {:rf/default {:app-db {:cart {:items 3 :total 4200}}
 :runtime-db {:rf.runtime/machines {:snapshots {:auth {:state :authed}}}}
 :sub-cache {[:cart/total] {:value 4200 :ref-count 2}}
 :epochs [{:epoch-id "e1" :event-id :app/init}
 {:epoch-id "e2" :event-id :cart/add}]
 :traces [{:id 1 :operation :rf.event/dispatched :tags {:frame :rf/default}}
 {:id 2 :operation :rf.sub/run :tags {:frame :rf/default}}]}
 :stories {:app-db {:scenarios {:checkout :ready}}
 :runtime-db {:rf.runtime/machines {:snapshots {}}}
 :sub-cache {[:stories/active] {:value :checkout :ref-count 1}}
 :epochs [{:epoch-id "s1" :event-id :stories/load}]
 :traces [{:id 3 :operation :rf.event/dispatched :tags {:frame :stories}}]}
 ;; A reserved :rf/* TOOL frame (Xray's inspector frame).
 ;; Its :app-db is the huge tool-frame inspection state that the default
 ;; `:app` scope MUST exclude so the first read doesn't overflow on it.
 :rf/xray {:app-db {:xray/inspector {:xray-mega-state :massive}}
 :runtime-db {}
 :sub-cache {}
 :epochs []
 :traces []}})

(defn stub-frame-ids [] (keys fixture-frames))
(defn stub-get-frame-db [fid] (get-in fixture-frames [fid :app-db]))
(defn stub-runtime-db [fid] (get-in fixture-frames [fid :runtime-db]))
(defn stub-sub-cache [fid] (get-in fixture-frames [fid :sub-cache]))
(defn stub-epoch-history [fid] (get-in fixture-frames [fid :epochs]))
(defn stub-machines [] [:auth :session]) ;; global registrar surface
(defn stub-trace-buffer
 "The framework's `trace-buffer` is per-frame and event-keyed; the
 frame-id is the required first arg. The snapshot `:traces` slice ships
 event bundles by default (the framework's storage unit), matching the
 streaming subscribe wire shape. This stub returns the fixture's `:traces`
 vector directly — the loosely-typed stub is shape-agnostic; the
 production snapshot slice calls `(trace-tooling/trace-buffer
 frame-id)` (zero-arg opts) for the event-bundle default."
 ([frame-id] (stub-trace-buffer frame-id {}))
 ([frame-id _opts]
 (filter #(= frame-id (get-in % [:tags :frame]))
 (mapcat (fn [[_ slices]] (:traces slices)) fixture-frames))))

;; Mirror of preload/re_frame2_pair/runtime.cljs §Coarse-grained snapshot.
;; KEEP IN SYNC.

(def ^:private all-snapshot-slices
 [:app-db :sub-cache :machines :epochs :traces])

;; Reserved-frame predicate + app-frame view. KEEP IN SYNC with
;; `reserved-tool-frame?` / `app-frame-ids` in
;; preload/re_frame2_pair/runtime.cljs. A `:rf/*` frame is a TOOL frame
;; (Xray's :rf/xray, SSR slots) excluded from the default snapshot scope;
;; the sole `:rf/default` carve-out is an ordinary APP frame id (no
;; framework privilege), not a tool frame.

(defn- reserved-tool-frame? [frame-id]
 (and (keyword? frame-id)
 (= "rf" (namespace frame-id))
 (not= :rf/default frame-id)))

(defn- app-frame-ids []
 (vec (remove reserved-tool-frame? (stub-frame-ids))))

(defn- snapshot-frame-slice [frame-id slice]
 (case slice
 :app-db (stub-get-frame-db frame-id)
 :sub-cache (stub-sub-cache frame-id)
 ;; Machine snapshots read from the RUNTIME-DB partition at
 ;; [:rf.runtime/machines :snapshots], NOT app-db :rf/runtime.
 :machines {:ids (vec (stub-machines))
 :state (or (get-in (stub-runtime-db frame-id)
 [:rf.runtime/machines :snapshots])
 {})}
 :epochs (vec (stub-epoch-history frame-id))
 ;; The trace ring is per-frame; the snapshot `:traces` slice ships
 ;; event bundles by default (the framework's storage unit, matching
 ;; the streaming subscribe wire shape per Tool-Pair §Reading the
 ;; per-frame trace ring). Production runtime calls
 ;; `(trace-tooling/trace-buffer frame-id)` (zero-arg opts).
 :traces (vec (stub-trace-buffer frame-id))
 nil))

(defn- snapshot-frame [frame-id slices]
 (reduce (fn [m slice]
 (assoc m slice (snapshot-frame-slice frame-id slice)))
 {}
 slices))

(defn snapshot-state
 ([] (snapshot-state {}))
 ([{:keys [frames include]
 :or {frames :app include all-snapshot-slices}}]
 (let [fids (cond
 ;; DEFAULT scope `:app` = app frames only (reserved :rf/*
 ;; tool frames excluded). `:all` is the explicit opt-in
 ;; to tool-frame state.
 (= :app frames) (app-frame-ids)
 (= :all frames) (vec (stub-frame-ids))
 (sequential? frames) (vec frames)
 :else (app-frame-ids))
 slices (vec include)]
 (reduce (fn [m fid] (assoc m fid (snapshot-frame fid slices))) {} fids))))

;; ---------------------------------------------------------------------------
;; Tests
;; ---------------------------------------------------------------------------

(deftest default-scope-is-app-frames-excludes-reserved-tool-frames
 ;; The central assertion. The no-arg / default snapshot returns the APP
 ;; frames only; the reserved :rf/xray TOOL frame is EXCLUDED so the first
 ;; read doesn't overflow on tool-frame inspection state.
 (let [snap (snapshot-state)]
 (testing "default scope = app frames; reserved :rf/* tool frame excluded"
 (is (= #{:rf/default :stories} (set (keys snap)))
 ":rf/xray (a reserved tool frame) must NOT appear in the default snapshot")
 (is (not (contains? snap :rf/xray))
 "the huge tool-frame inspection state is excluded by default"))
 (testing "every slice is present per app frame"
 (doseq [fid [:rf/default :stories]]
 (is (= #{:app-db :sub-cache :machines :epochs :traces}
 (set (keys (get snap fid))))
 (str fid " missing slice keys"))))))

(deftest explicit-all-includes-reserved-tool-frames
 ;; The opt-in. `:all` returns EVERY frame INCLUDING the
 ;; reserved :rf/xray tool frame.
 (let [snap (snapshot-state {:frames :all})]
 (is (= #{:rf/default :stories :rf/xray} (set (keys snap)))
 "explicit :all opts into tool-frame state")
 (is (= :massive (get-in snap [:rf/xray :app-db :xray/inspector :xray-mega-state]))
 "the tool frame's full state is included on the explicit opt-in")))

(deftest explicit-app-keyword-matches-default
 ;; `:app` is the same scope as the default.
 (is (= (set (keys (snapshot-state)))
 (set (keys (snapshot-state {:frames :app}))))))

(deftest explicit-tool-frame-vector-includes-it
 ;; Naming a tool frame explicitly is also a valid opt-in.
 (let [snap (snapshot-state {:frames [:rf/xray]})]
 (is (= #{:rf/xray} (set (keys snap)))
 "naming :rf/xray explicitly includes it")))

(deftest app-db-slice-delegates-to-frame-db
 ;; The app-db partition does not carry machine snapshots (those live in
 ;; runtime-db); the `:app-db` slice is the pure app partition.
 (let [snap (snapshot-state {:include [:app-db]})]
 (is (= {:cart {:items 3 :total 4200}}
 (get-in snap [:rf/default :app-db])))
 (is (= {:scenarios {:checkout :ready}}
 (get-in snap [:stories :app-db])))))

(deftest sub-cache-slice-delegates-to-rf-sub-cache
 (let [snap (snapshot-state {:include [:sub-cache]})]
 (is (= {[:cart/total] {:value 4200 :ref-count 2}}
 (get-in snap [:rf/default :sub-cache])))))

(deftest machines-slice-combines-registrar-and-per-frame-state
 (let [snap (snapshot-state {:include [:machines]})]
 (is (= [:auth :session]
 (get-in snap [:rf/default :machines :ids]))
 "global registrar ids appear under :ids")
 (is (= {:auth {:state :authed}}
 (get-in snap [:rf/default :machines :state]))
 "per-frame state appears under :state")
 (is (= {} (get-in snap [:stories :machines :state]))
 "frames with no machines snapshot get an empty state map")))

(deftest epochs-slice-returns-history-vector
 (let [snap (snapshot-state {:include [:epochs]})]
 (is (vector? (get-in snap [:rf/default :epochs])))
 (is (= 2 (count (get-in snap [:rf/default :epochs]))))
 (is (= "e1" (-> snap :rf/default :epochs first :epoch-id)))))

(deftest traces-slice-is-frame-scoped
 (let [snap (snapshot-state {:include [:traces]})]
 (is (every? #(= :rf/default (get-in % [:tags :frame]))
 (get-in snap [:rf/default :traces]))
 "trace buffer filtered by frame, no leaks across frames")
 (is (every? #(= :stories (get-in % [:tags :frame]))
 (get-in snap [:stories :traces])))))

(deftest include-subset-honours-pick-list
 (let [snap (snapshot-state {:include [:app-db :epochs]})]
 (is (= #{:app-db :epochs}
 (set (keys (get snap :rf/default))))
 "only the requested slices appear")))

(deftest frames-vector-narrows-to-listed-frames
 (let [snap (snapshot-state {:frames [:stories]})]
 (is (= #{:stories} (set (keys snap)))
 "only :stories appears in the result")))

(deftest frames-all-keyword-matches-registered-frames
 (is (= (set (stub-frame-ids))
 (set (keys (snapshot-state {:frames :all}))))))

(let [{:keys [fail error]} (run-tests 'snapshot-test)]
 (System/exit (if (zero? (+ (or fail 0) (or error 0))) 0 1)))
