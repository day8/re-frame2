(ns re-frame.ui.reactive-two-frame-per-observation-frame-id-cljs-test
  "rf2-9utbt (S6 view-evidence audit obligation) — the TWO-live-frames
  strengthening of the per-observation `:frame-id` proof deferred by slice d
  (rf2-rvs56).

  `reactive-commit-record-cljs-test/observations-carry-per-observation-frame-id`
  already proves each observation carries a `:frame-id`, but its two observation
  sites live in the SAME `:rf/default` frame, so the frame-id SET it derives is a
  singleton `#{:rf/default}`. That test CANNOT distinguish true per-observation
  attribution from an accidental CELL-WIDE frame stamp: a bug that stamped one
  fabricated frame-id across every observation would still pass it (both sites
  would report the one shared frame).

  This proof mounts ONE cell whose commit record holds TWO observation sites
  resolving TWO DISTINCT live frames, then asserts each observation carries its
  OWN target's frame-id — `[:tfp/frame-a :tfp/frame-b]` in render order, a SET of
  size two, and NO singular top-level `:frame-id` on the record. A cell-wide
  stamp would collapse the two frame-ids to one and fail these assertions; that
  is the accidental stamping this proof rejects.

  ## Legitimate multi-frame OBSERVATION — not a cross-frame reach

  A ViewCell observing a SET of frames is a first-class runtime scenario, NOT an
  anti-pattern: `reactive/cell-frames` returns a SET, `flush-frame!` scopes on
  it, and Spec 004 §S6 states a cell observes a SET of frames (so the record
  holds no singular frame-id). The legitimate shape is a host/shell cell that
  hosts two independent frame instances of the same app — each observation site
  scoped (via its own ambient frame) to its OWN frame, like two isolated embedded
  widgets side by side.

  What this proof does NOT do — the genuine anti-pattern — is cross-frame sub
  COMPUTATION: no sub reaches into another frame during its derivation. The one
  registered sub reads only `(:v db)` of whatever frame is ambient at its site;
  site A resolves frame A's isolated db, site B resolves frame B's. The distinct
  observed values (`:a` vs `:b`) prove the frames stay isolated — each site saw
  only its own frame's state.

  `.cljc` ending `-cljs-test` rides `npm run test:cljs` (node) AND
  `clojure -M:test` (JVM) — the committed-instance record is host-agnostic, so
  the two-frame attribution is graft-checked on both hosts over the plain-atom
  adapter. Per the slice-a scope fence this asserts on the commit-time integer
  `:render-key` and never identifies it with the legacy
  `:rf.sub/reader-render-key`."
  (:require #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
               :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
            [re-frame.core                 :as rf]
            [re-frame.frame                :as frame]
            [re-frame.live-frame           :as live-frame]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support         :as test-support]
            [re-frame.ui.reactive          :as reactive]))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture {:adapter plain-atom/adapter}))

(def ^:private fa :tfp/frame-a)
(def ^:private fb :tfp/frame-b)

(defn- make-frame! [id db]
  (live-frame/make-frame {:id id})
  (frame/replace-app-db! id db)
  id)

(defn- render-two-frames!
  "One render pass of `cell`: site 0 reads `[:tfp/v]` under frame A, site 1 reads
  it under frame B — TWO observation sites in ONE capture, each scoped to its own
  ambient frame. Returns `[values capture]` where `values` is the render body's
  `[<a-value> <b-value>]`."
  [cell]
  (reactive/with-capture
    cell
    (fn []
      [(rf/with-frame fa (reactive/sub-read [::site 0] [:tfp/v]))
       (rf/with-frame fb (reactive/sub-read [::site 1] [:tfp/v]))])))

(defn- render+commit-two-frames! [cell]
  (let [[_ cap] (render-two-frames! cell)]
    (reactive/commit! cell cap))
  cell)

;; ===========================================================================
;; TWO live frames, TWO observation sites, ONE commit — per-observation
;; frame-id is each site's TRUE source frame (rejects cell-wide stamping)
;; ===========================================================================

(deftest two-live-frames-carry-distinct-per-observation-frame-ids
  (rf/reg-sub :tfp/v (fn [db _] (:v db)))
  ;; Two independent frame instances of the same app — isolated contexts with
  ;; distinct app-dbs, so a site that genuinely reads its own frame sees a
  ;; distinct value.
  (make-frame! fa {:v :a})
  (make-frame! fb {:v :b})
  (let [cell (reactive/make-cell ::shell)]
    ;; First commit builds each frame's node; the SECOND render probes them live,
    ;; so the second record's observations carry live node identity + version.
    (render+commit-two-frames! cell)
    (let [[values _] (render-two-frames! cell)]
      (testing "each site read ONLY its own frame's db — frames stay isolated"
        (is (= [:a :b] values)
            "site A resolved frame A's :v and site B resolved frame B's :v; no
             sub reached across frames (isolated contexts, not a cross-frame reach)")))
    (render+commit-two-frames! cell)
    (let [record (reactive/commit-record cell)
          obs    (:observations record)]
      (testing "the record shape (slice-a/b) still holds for a two-frame commit"
        (is (map? record) "a connected commit publishes a per-commit record")
        (is (integer? (:render-key record)) ":render-key is an integer")
        (is (= ::shell (:view-id record)) ":view-id is the cell's authoring identity")
        (is (= :connected (:connection record))
            ":connection is the certified :connected state at a connected commit")
        (is (vector? obs) ":observations is a vector"))
      (testing "no singular top-level :frame-id — attribution is per-observation"
        (is (not (contains? record :frame-id))
            "a cell observing a SET of frames claims NO singular record-level
             :frame-id (Spec 004 §S6)"))
      (testing "TWO observations, each carrying its TRUE target frame-id"
        (is (= 2 (count obs)) "one observation per rendered site, in render order")
        (is (= [[:tfp/v] [:tfp/v]] (mapv :query obs))
            "both sites read the SAME query — only the ambient frame differs, so
             the query alone cannot carry the attribution")
        (is (every? (fn [o] (= :subscription (:kind o))) obs)
            "each subscription read is a :subscription observation")
        (is (= [fa fb] (mapv :frame-id obs))
            "PER-OBSERVATION attribution: site 0 is frame A, site 1 is frame B —
             in render order, NOT one cell-wide stamp"))
      (testing "the two frame-ids are DISTINCT — this is what rejects cell-wide stamping"
        (is (= #{fa fb} (into #{} (map :frame-id) obs))
            "the frames the cell observes are a SET of size TWO derived from the
             observations; a fabricated cell-wide frame-id would collapse this to
             one and fail")
        (is (apply not= (mapv :frame-id obs))
            "the per-observation frame-ids are not equal to one another"))
      (testing "the runtime's frame-scope membership sees BOTH frames for this cell"
        (is (reactive/cell-observes-frame? cell fa)
            "the cell observes frame A (first-class multi-frame membership)")
        (is (reactive/cell-observes-frame? cell fb)
            "the cell observes frame B"))
      (testing "each observation captured its own live node identity + owned handle"
        (is (every? :owned? obs)
            "an owned subscription node handle reports :owned? true in each frame")
        (is (every? (fn [o] (integer? (:target-id o))) obs)
            "each observed live node identity is captured (:target-id)")
        (is (every? (fn [o] (integer? (:version o))) obs)
            "each observed live node version is captured (:version)")
        (is (apply not= (mapv :target-id obs))
            "the two sites observe DISTINCT live nodes — one per frame")))))
