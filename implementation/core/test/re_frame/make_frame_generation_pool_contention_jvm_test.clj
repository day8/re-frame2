(ns re-frame.make-frame-generation-pool-contention-jvm-test
  "rf2-rt4jz, contended — only the attempt the engine ADMITS may touch a frame
  id's generation-provenance row.

  THE ADMISSION CONTRACT. `rf.frame/upsert-frame!` reserves an id for exactly one
  in-flight construction and fails every same-id contender promptly with
  `:rf.error/frame-construction-in-progress`. That loss is defined to be a
  ZERO-WRITE loss: it is raised at admission precisely so a contender never
  reaches an adapter callback, a setup cascade or a teardown — and so it never
  leaves a trace of having tried.

  WHAT BROKE IT. `make-frame` writes a SECOND process-global store beside the
  frame record — `frame-generation-pool`, the row naming which descriptor pool
  a frame's current generation was resolved against (rf2-rf3zgt) — and rf2-rt4jz
  moved that write BEFORE the engine commit, which is right for a reason that
  has nothing to do with contention (the `:initial-events` cascade runs INSIDE
  the commit and reprojects against the row; see
  `make-frame-generation-pool-window-jvm-test`). Before the commit is also
  before ADMISSION, and that is what this namespace is about:

    - a rejected same-id contender published its own pool into the shared table
      on its way to being told it had lost. Reprojection is a READ of that
      table, takes no reservation, and runs on any thread — so a reader landing
      in the loser's interval re-resolved the WINNER's live frame against the
      LOSER's pool and swapped the result on. The frame ran descriptors nobody
      asked for, sourced from a construction that never happened.

    - the rollback had the same shape in reverse. `restore-frame-generation-pool!`
      undoes the write by restoring the value read at write time; computed
      outside any reservation, that undo can land on a row a NEWER owner has
      since written.

  THE REPAIR is not a defensive read and not a re-write after the fact. It is to
  put the publication, the commit and the rollback inside the SAME exact per-id
  reservation the frame revision is made under
  (`rf.frame/call-with-frame-construction-claim!`), so a losing attempt throws
  before its first `swap!` and a winning attempt cannot interleave with any
  other attempt on the id. The engine's zero-write admission loss then covers
  both stores instead of one.

  THE HARNESS is deterministic — no sleeps decide anything, and both windows are
  opened by seams that already exist:

    - the WINNER parks inside its construction transaction on
      `rf.frame/*upsert-decide-probe*` (the `nil`-in-production JVM linearization
      seam `frame-upsert-linearization-jvm-test` and
      `make-frame-generation-seal-race-jvm-test` use). Parked there it holds the
      id's reservation, so the contender that follows is a genuine engine loss.

    - the READER lands on the exact instant of a transient publication via an
      atom WATCH on the provenance table. A watch fires synchronously, on the
      writing thread, inside the `swap!` that made the transition — so it is the
      one place a test can stand that no timing tolerance can reach. What it
      then runs is the REAL reader: `live-frame/reproject-live-frame!`, the
      per-frame reprojection both sweeps call.

  JVM-scoped because contention is: CLJS is single-threaded, so no second
  attempt can be in flight at all, and the repair is common `.cljc` code that
  simply never loses there. Its SINGLE-THREADED half — the ordering wart the
  cascade reaches on both hosts — is covered by
  `make-frame-generation-pool-window-jvm-test` and, on CLJS,
  `live-frame-reload-cljs-test`."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as rf.frame]
            [re-frame.image :as rf.image]
            [re-frame.image-assembly :as rf.image-assembly]
            [re-frame.live-frame :as rf.live-frame]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.test-support :as rf.test-support])
  (:import [java.util.concurrent CountDownLatch TimeUnit]))

;; ---------------------------------------------------------------------------
;; White-box handles. The defect lives entirely in the relationship between two
;; pieces of private process-local bookkeeping — the provenance table and the
;; per-id construction reservations — so the reproduction observes both
;; directly, exactly as its two sibling namespaces do.
;; ---------------------------------------------------------------------------

(def ^:private provenance #'rf.live-frame/frame-generation-pool)

(defn- pool-row [id] (get (deref @provenance) id))

(def ^:private ids
  [:pool-race/target :pool-race/rollback :pool-race/nil-pool :pool-race/adopted
   :pool-race/after-release])

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter})
  (fn [t]
    ;; The provenance table is process-local `defonce` bookkeeping that no
    ;; fixture resets (a destroyed id's row deliberately survives). Clear this
    ;; namespace's own ids so a re-run inside one JVM starts from the documented
    ;; "no row" state, and leave none behind for a sibling.
    (swap! @provenance #(apply dissoc % ids))
    (try
      (t)
      (finally
        (remove-watch @provenance ::row-watch)
        (swap! @provenance #(apply dissoc % ids))))))

;; A latch we expect to FIRE waits this long; it only bounds a hang.
(def ^:private ^:const settle-ms 10000)

(defn- await! [^CountDownLatch latch ^long ms]
  (.await latch ms TimeUnit/MILLISECONDS))

(defn- err-id [thunk]
  (try
    (thunk)
    nil
    (catch clojure.lang.ExceptionInfo e
      (:rf.error/id (ex-data e)))))

;; Park the constructor of `target` inside its construction transaction: the
;; per-id reservation is held, and the authoritative registry decision has not
;; been taken yet.
(defn- window-probe [target ^CountDownLatch reached ^CountDownLatch release]
  (fn [id]
    (when (= id target)
      (.countDown reached)
      (.await release settle-ms TimeUnit/MILLISECONDS))))

(defn- exclusively-reserved?
  "Is `id` held by an in-flight construction transaction RIGHT NOW? Asked the
  only way the reservation registry answers: by trying to claim it. A claim that
  succeeds is released immediately — the question is the answer's only purpose."
  [id]
  (let [owner (try
                (rf.frame/claim-frame-construction! #{id} ::reservation-probe)
                (catch clojure.lang.ExceptionInfo e
                  (if (= :rf.error/frame-construction-in-progress
                         (:rf.error/id (ex-data e)))
                    nil
                    (throw e))))]
    (if owner
      (do (rf.frame/release-frame-construction! owner) false)
      true)))

(defn- watch-row!
  "Record every REAL transition of `id`'s provenance row into `log`, noting
  whether the id was exclusively reserved at that instant, and run `on-change`
  (nil for none) there too.

  The watch fires synchronously on the WRITING thread, inside the `swap!` that
  made the transition — so `log` is a record of what a reader standing at each
  publication would have seen, with no timing tolerance anywhere. Writes that do
  not MOVE the row (a re-construction re-recording the pool it already names)
  are not transitions and are ignored."
  [id log on-change]
  (add-watch
    @provenance ::row-watch
    (fn [_ _ old new]
      (let [before (get old id ::absent)
            after  (get new id ::absent)]
        (when (not= before after)
          (swap! log conj {:from before :to after :reserved? (exclusively-reserved? id)})
          (when on-change (on-change)))))))

;; ---------------------------------------------------------------------------
;; Two explicit descriptor pools selected by the SAME image (the shape
;; `make-frame-generation-pool-window-jvm-test` uses, renamed for this
;; namespace). Both carry the frame's namespace, so a reprojection against the
;; WRONG one resolves happily rather than zero-matching: the clobber is SILENT.
;; ---------------------------------------------------------------------------

(defn- reg-desc [provenance-ns kind id impl]
  {:rf.provenance/ns provenance-ns
   :kind             kind
   :id               id
   :handler-fn       impl})

(def ^:private pool-v1
  [(reg-desc "pool.race.core" :event :pool-race/inc ::inc-v1)])

(def ^:private pool-v2
  [(reg-desc "pool.race.core" :event :pool-race/inc   ::inc-v2)
   (reg-desc "pool.race.core" :event :pool-race/reset ::reset)])

(def ^:private img
  (rf.image/image {:id :pool-race/img :select-ns {:include ["pool.race.core"]}}))

(defn- inc-impl
  "The `:pool-race/inc` implementation the frame's CURRENT generation resolves —
  `::inc-v1` when the frame is running pool V1, `::inc-v2` for pool V2. The
  one-line discriminator between the pool the winner sealed and the pool a
  rejected contender transiently published."
  [id]
  (:handler-fn (rf.image-assembly/resolve-descriptor (rf.live-frame/frame-generation id) :event :pool-race/inc)))

;; ---------------------------------------------------------------------------
;; 1. THE REPRODUCTION. A same-id contender the engine REJECTS must leave the
;;    provenance table exactly as it found it — and therefore must not be able
;;    to make a live frame run its pool.
;; ---------------------------------------------------------------------------

(deftest rejected-same-id-attempt-neither-publishes-nor-clobbers
  (testing "a make-frame that loses admission for an id publishes no provenance
            row, so no reprojection can resolve the winner's live frame against
            the loser's pool"
    (let [target :pool-race/target]
      ;; The frame the winner owns, sealed from pool V2.
      (rf.live-frame/make-frame {:id target :images [img]} pool-v2)
      (is (= ::inc-v2 (inc-impl target)) "control: the frame is running pool V2")
      (is (= pool-v2 (pool-row target)) "control: its row names pool V2")

      (let [log      (atom [])
            moved    (atom [])
            reached  (CountDownLatch. 1)
            release  (CountDownLatch. 1)]
        ;; THE READER, standing on the instant of any transient publication: the
        ;; real per-frame reprojection, which re-resolves the frame against
        ;; whatever the table says right now and swaps the result on. Its return
        ;; is the reload diff — non-nil exactly when the frame MOVED.
        (watch-row! target log #(swap! moved conj (rf.live-frame/reproject-live-frame! target)))
        (try
          (let [winner (binding [rf.frame/*upsert-decide-probe*
                                 (window-probe target reached release)]
                         (future (rf.live-frame/make-frame {:id target :images [img]} pool-v2)))]
            (is (await! reached settle-ms)
                "the winner parked inside its construction transaction")
            (is (exclusively-reserved? target)
                "control: the winner holds the id's reservation")

            ;; THE CONTENDER. It loses admission — and pre-fix it had already
            ;; published pool V1 into the shared table before finding out.
            (is (= :rf.error/frame-construction-in-progress
                   (err-id #(rf.live-frame/make-frame {:id target :images [img]} pool-v1)))
                "the same-id contender is rejected by the engine")

            (is (= [] @log)
                (str "the rejected contender wrote nothing to the provenance "
                     "table — pre-fix it published pool V1 there and then "
                     "restored it, a window any reader on any thread could "
                     "land in"))
            (is (= [] @moved)
                (str "no reprojection was offered a pool the winner had not "
                     "sealed — pre-fix the reader in that window re-resolved "
                     "the live frame against V1 and swapped it on"))
            (is (= ::inc-v2 (inc-impl target))
                "the live frame is still running the pool its owner sealed")
            (is (= pool-v2 (pool-row target))
                "the row still names the pool the frame is running")

            (.countDown release)
            (is (not= ::timeout (deref winner settle-ms ::timeout))
                "the winner completed")
            (is (= ::inc-v2 (inc-impl target))
                "and completed on its own generation")
            (is (= pool-v2 (pool-row target))
                "with its row still in step"))
          (finally
            (.countDown release)))))))

;; ---------------------------------------------------------------------------
;; 2. THE ROLLBACK, and the publication it undoes, run INSIDE the reservation.
;;    This is the structural statement the reproduction above cannot make on its
;;    own: an undo computed from a row read outside any reservation is only
;;    accidentally exact. Under the reservation the row it restores is still the
;;    row it displaced, because no other attempt on the id can have run.
;; ---------------------------------------------------------------------------

(deftest provenance-write-and-rollback-both-hold-the-id-reservation
  (testing "a construction that fails in the engine publishes and rolls back its
            provenance row while it exclusively owns the id — so neither half
            can land on another attempt's row"
    (let [id  :pool-race/rollback
          log (atom [])]
      (rf.live-frame/make-frame {:id id :images [img]} pool-v1)
      (is (= pool-v1 (pool-row id)) "control: V1 recorded")

      (watch-row! id log nil)
      ;; `:on-create` is retired and fails loud INSIDE the engine — i.e. after
      ;; the early provenance write, which is the branch the rollback exists for.
      (is (= :rf.error/on-create-retired
             (err-id #(rf.live-frame/make-frame {:id id :images [img] :on-create [:boom]}
                                     pool-v2)))
          "control: the re-construction failed inside the engine")

      (is (= [{:from pool-v1 :to pool-v2 :reserved? true}
              {:from pool-v2 :to pool-v1 :reserved? true}]
             @log)
          (str "the publication and its rollback both ran while the id was "
               "exclusively reserved — pre-fix both ran outside every "
               "reservation, so a same-id successor could be interleaved "
               "between them"))
      (is (= pool-v1 (pool-row id))
          "the failed re-construction preserved the ORIGINAL row"))))

;; ---------------------------------------------------------------------------
;; 3. ABSENT versus RECORDED-NIL, under the reservation. `nil` is a legitimate
;;    recorded pool — it means "the live source store", which is what the
;;    1-arity `make-frame` resolves against — so an absent row cannot be spelled
;;    by assoc'ing nil, and a rollback that confused the two would convert a
;;    live-store frame into one with no provenance at all.
;; ---------------------------------------------------------------------------

(deftest rollback-restores-a-recorded-nil-row-rather-than-removing-it
  (testing "a 1-arity frame's row is PRESENT with value nil, and a failed
            re-construction against an explicit pool restores it to present-nil
            — not to absent, and not to the incoming pool"
    (let [id  :pool-race/nil-pool
          log (atom [])]
      ;; The 1-arity: the DEFAULT image over the LIVE source store. Its row is
      ;; recorded as nil, which is what "the live store" is spelled as.
      (rf.live-frame/make-frame {:id id})
      (is (contains? (deref @provenance) id) "control: the row is present")
      (is (nil? (pool-row id)) "control: and its value is nil — the live store")

      (watch-row! id log nil)
      (is (= :rf.error/on-create-retired
             (err-id #(rf.live-frame/make-frame {:id id :on-create [:boom]} pool-v1))))
      (is (= [{:from nil :to pool-v1 :reserved? true}
              {:from pool-v1 :to nil  :reserved? true}]
             @log)
          "both halves ran under the reservation, and the undo restored nil")
      (is (contains? (deref @provenance) id)
          "the row is still PRESENT — a recorded nil was not mistaken for absent")
      (is (nil? (pool-row id))
          "and still names the live source store"))))

;; ---------------------------------------------------------------------------
;; 4. AN OUTER PREFLIGHT'S RESERVATION IS ADOPTED, NOT NESTED. A multi-id
;;    preflight claims its whole plan set up front and hands each id
;;    to `make-frame` in turn. `make-frame` must recognise that it is already
;;    inside an exact reservation for the id and neither re-claim it (which
;;    would collide with its own caller) nor release it early. A hand-off
;;    already SPENT is not adoption: the nested public entry loses normally, and
;;    must lose without publishing.
;; ---------------------------------------------------------------------------

(deftest a-handed-off-reservation-is-adopted-and-a-spent-one-is-not
  (testing "make-frame under an outer preflight's hand-off publishes its row
            inside that owner's reservation, and a second entry under the same
            spent hand-off loses without touching the table"
    (let [id    :pool-race/adopted
          log   (atom [])
          owner (rf.frame/claim-frame-construction! #{id} :plan-preflight)]
      (watch-row! id log nil)
      (try
        (is (= [true :rf.error/frame-construction-in-progress]
               (rf.frame/call-with-frame-construction-handoff!
                 owner id
                 (fn []
                   [(some? (rf.live-frame/make-frame {:id id :images [img]} pool-v1))
                    (err-id #(rf.live-frame/make-frame {:id id :images [img]} pool-v2))])))
            "one hand-off permits exactly one construction; a second loses")
        (finally
          (rf.frame/release-frame-construction! owner)))

      (is (= [{:from ::absent :to pool-v1 :reserved? true}] @log)
          (str "exactly ONE publication, made under the outer owner's "
               "reservation — the losing second entry published nothing"))
      (is (= pool-v1 (pool-row id)) "the row names the pool that was admitted")
      (is (= ::inc-v1 (inc-impl id)) "and the frame is running it")

      ;; The outer owner compare-released, so ordinary construction resumes.
      (is (some? (rf/make-frame {:id :pool-race/after-release}))
          "the adopted reservation was not released early by make-frame"))))
