(ns re-frame.frame-destroy-generation-provenance-cljs-test
  "rf2-cq0yi — `destroy-frame!` RELEASES the destroyed frame's
  generation-provenance row.

  `re-frame.live-frame` keeps one private process-global row per frame naming
  which descriptor pool that frame's CURRENT generation was resolved against:
  `nil` for the live source store (`make-frame`'s 1-arity — the ordinary case),
  a real descriptors value for an explicit pool (the 2-arity). Every successful
  public `make-frame` writes one, and `reproject-live-frame!` threads the same
  pool back on every re-resolution.

  Nothing removed the row. `destroy-frame!` released the frame record and a
  dozen other frame-keyed side tables and left this one standing, so destroying
  N never-reused ids left N permanent keys — and on the 2-arity, the caller's
  whole explicit descriptor-pool object graph stayed reachable through them.
  Spec 002 §Destroy makes `destroy-frame!` the single normative teardown
  boundary every frame-scoped table hangs its cleanup off, so the residue was a
  contract violation as well as a leak; the source comment classified it as
  harmless \"dev-process memory\", but the write is unconditional in the PUBLIC
  constructor and the documented per-request SSR recipe mints a fresh gensym
  id, constructs, renders and destroys in a `finally` — one retained row per
  request served, for the life of the server. Exactly the shape rf2-uejlj
  fixed one layer up for the Hicasso frame-ops row.

  The fix is `live-frame/release-frame-generation-pool!`, published as
  `:live-frame/on-frame-destroyed!` and invoked from `destroy-frame!`'s step-6
  auxiliary-cleanup pass beside its siblings.

  Every case below starts from a COMPLETE late-bind registry, so none of them
  can see whether that publication survives a hot reload of the producing
  namespace — see `live_frame_teardown_hook_reload_jvm_test`, which constructs
  the discriminating state (once-flag latched, this one key missing) that the
  audit of PR #8887 measured.

  ## Why each case asserts PRESENCE before it asserts absence

  A leak is an ABSENCE, and \"the key is gone after teardown\" passes trivially
  against a build where `make-frame` never wrote the key at all — a different
  bug, and one this file would then certify as fixed. So every case here proves
  the row was THERE while the frame was live before it proves it is gone after,
  and the churn case pins the exact count in both directions rather than
  probing one selected key.

  `contains?` — never `get` — is the presence probe, and that is load-bearing
  rather than stylistic: an ordinary 1-arity frame's recorded pool IS `nil`, so
  `(get pool id)` returns `nil` both for a live ordinary frame and for a frame
  that was never created. The one probe that can tell those apart is key
  membership. (Informationally the two are identical to the row's only READER,
  `reproject-live-frame!` — which is the argument that releasing the row is
  safe, not an argument that the release is unobservable.)

  `.cljc` ending `-cljs-test` so it rides `npm run test:cljs` (the `:node-test`
  build's `cljs-test$` ns-regexp) AND `clojure -M:test` (cognitect-test-runner's
  `-test$`) — the row and its release are host-neutral, so both lanes run every
  case."
  (:require #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
               :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
            [re-frame.core       :as rf]
            [re-frame.frame      :as rf.frame]
            [re-frame.image      :as rf.image]
            [re-frame.late-bind  :as rf.late-bind]
            [re-frame.live-frame :as rf.live-frame]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.test-support :as rf.test-support]))

;; The runtime fixture installs the plain-atom adapter and resets `rf.frame/frames`
;; between cases. It deliberately does NOT touch the provenance table — these
;; cases capture their own baseline and assert against it, which is also what
;; makes the churn case's exact-count assertion honest in a shared test bundle.
(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter}))

;; ---------------------------------------------------------------------------
;; Helpers
;; ---------------------------------------------------------------------------

(defn- provenance
  "The private generation-provenance table, as a plain map."
  []
  (deref @#'rf.live-frame/frame-generation-pool))

(defn- row?
  "Does the table carry a row for `id`? Key MEMBERSHIP — see the ns docstring
  for why `get` cannot answer this for an ordinary 1-arity frame."
  [id]
  (contains? (provenance) id))

(defn- live?
  [id]
  (contains? (set (rf.frame/frame-ids)) id))

(defn- reg-desc
  "A synthetic REGISTERED descriptor authored in `provenance-ns` (mirrors the
  source-store output shape the selector consumes)."
  [provenance-ns kind id impl]
  {:rf.provenance/ns provenance-ns
   :kind             kind
   :id               id
   :handler-fn       impl})

;; ---------------------------------------------------------------------------
;; The hook itself
;; ---------------------------------------------------------------------------

(deftest make-frame-publishes-the-destroy-release-hook-rf2-cq0yi
  (testing "rf2-cq0yi: the release is published at `re-frame.live-frame`'s NS
            LOAD, which strictly precedes any `make-frame`, so it is bound
            before the first row can exist. This pins the REGISTRATION half
            directly: a release function that is never published is a silent
            no-op at teardown, and every other case in this file would then be
            red for a reason indistinguishable from a broken release.

            What this case CANNOT see is whether the publication re-arms on a
            hot reload — every case here starts from a complete registry, which
            is green whether the key is published at load time or from the
            `make-frame`-rooted once-body. That distinction is the audit of
            PR #8887 and is pinned by
            `live_frame_teardown_hook_reload_jvm_test`."
    (let [f (rf/make-frame {:id :cq0yi-hook/main})]
      (is (some? (rf.late-bind/get-fn :live-frame/on-frame-destroyed!))
          ":live-frame/on-frame-destroyed! is published once a frame exists")
      (rf/destroy-frame! f))))

;; ---------------------------------------------------------------------------
;; The ordinary (1-arity) row — the common case, and the nil-valued one
;; ---------------------------------------------------------------------------

(deftest destroy-frame-releases-ordinary-generation-provenance-rf2-cq0yi
  (testing "rf2-cq0yi: an ORDINARY one-arity `make-frame` records a row whose
            VALUE is nil (meaning: resolved against the live source store), and
            `destroy-frame!` releases it along with the frame record"
    (let [id :cq0yi-ordinary/main]
      (testing "baseline: no row for this never-used id"
        (is (not (row? id))))
      (let [f (rf/make-frame {:id id})]
        (testing "NON-VACUITY control — the row WAS written while the frame was
                  live, so the post-teardown absence below is a release rather
                  than a row that never existed"
          (is (row? id) "make-frame recorded a provenance row for the live frame")
          (is (nil? (get (provenance) id))
              "and its value is nil — the live source store. This is exactly why
               the presence probe above is `contains?`: `get` cannot separate
               this live row from no row at all.")
          (is (live? id) "the frame record is live"))
        (rf/destroy-frame! f)
        (testing "after teardown BOTH are gone"
          (is (not (live? id)) "the frame record was destroyed")
          (is (not (row? id))
              "the provenance row was RELEASED — pre-fix it survived for the
               remainder of the process"))))))

;; ---------------------------------------------------------------------------
;; The explicit-pool (2-arity) row, across two same-id incarnations
;; ---------------------------------------------------------------------------

(deftest destroy-frame-releases-explicit-pool-provenance-across-incarnations-rf2-cq0yi
  (testing "rf2-cq0yi: the 2-arity records the caller's EXACT descriptor pool —
            the object graph the leak kept reachable — and teardown releases it.
            Two same-id incarnations with DISTINCT pool objects also pin that
            the release is incarnation-correct: a STALE exact-value destroy of A
            must not strip successor B's row."
    (let [id     :cq0yi-explicit/main
          pool-a [(reg-desc "cq0yi-explicit.core" :event :cq0yi-explicit/inc ::a)]
          pool-b [(reg-desc "cq0yi-explicit.core" :event :cq0yi-explicit/inc ::b)]
          img    (rf.image/image {:id        :cq0yi-explicit/img
                               :select-ns {:include ["cq0yi-explicit.core"]}})
          a      (rf/make-frame {:id id :images [img]} pool-a)]
      (testing "control: A's live row is A's exact pool"
        (is (row? id))
        (is (identical? pool-a (get (provenance) id))))
      (rf/destroy-frame! a)
      (testing "destroying A releases A's row"
        (is (not (row? id)))
        (is (not (live? id))))
      (let [b (rf/make-frame {:id id :images [img]} pool-b)]
        (testing "control: constructing B installs B's OWN pool under the reused id"
          (is (identical? pool-b (get (provenance) id))))
        (testing "a STALE exact-value destroy of A no-ops against successor B —
                  it fails its incarnation claim before the cleanup walk, so it
                  strips nothing"
          (rf/destroy-frame! a)
          (is (live? id) "B is still live")
          (is (identical? pool-b (get (provenance) id)) "B's row is intact"))
        (rf/destroy-frame! b)
        (testing "destroying B releases B's row"
          (is (not (live? id)))
          (is (not (row? id))))))))

;; ---------------------------------------------------------------------------
;; Churn — the SSR-per-request shape the leak was measured on
;; ---------------------------------------------------------------------------

(deftest destroy-frame-returns-provenance-table-to-baseline-under-churn-rf2-cq0yi
  (testing "rf2-cq0yi: the failure was unbounded GROWTH, not one stale key, so
            the churn case pins the whole table's cardinality in both directions
            rather than probing a selected id. 100 fresh anonymous frames is the
            documented per-request SSR shape (a fresh id, construct, render,
            destroy in a `finally`) — pre-fix this left 100 permanent rows and
            the after-destroy count equalled the after-create count."
    (let [n        100
          baseline (count (provenance))
          frames   (vec (repeatedly n #(rf/make-frame {})))]
      (testing "NON-VACUITY control — the table GREW by exactly the live set, so
                the baseline restored below is a release of 100 real rows and
                not a `make-frame` that quietly stopped recording"
        (is (= (+ baseline n) (count (provenance)))
            "one new row per frame, all keys distinct"))
      (doseq [f frames]
        (rf/destroy-frame! f))
      (testing "every row is released, exactly back to the captured baseline"
        (is (= baseline (count (provenance)))
            "pre-fix this read (baseline + 100)")))))
