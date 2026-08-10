(ns re-frame.hicasso.hmr-registry-cljs-test
  "THE HMR CONTRACT, PART 2 — what a save's re-registrations do to the
  number React re-reads, and when an edited subscription reaches the
  screen (rf2-hic-015).

  `hmr_remount_cljs_test` takes the structural half: the head is re-minted,
  so the subtree is replaced and the retired generation must let go. This
  file takes the half that has nothing to do with the view at all. A
  reload re-runs a namespace's `reg-sub` and `reg-event` forms, and
  [[re-frame.hicasso.impl.generation]] says in its own docstring that
  **the HMR contract lands on that file** — a reload re-registers `:sub`
  handlers, which is exactly what
  [[re-frame.hicasso.impl.generation/registry-epoch]] counts and exactly
  what the collector's cell-invalidation repair rides. Nothing had ever
  measured a reload against those numbers.

  ## Why a rendered assertion is the wrong instrument for all of this

  Every property below is invisible to markup, and in both directions.

  A boundary re-renders when React's `useSyncExternalStore` decides the
  store moved, which it decides by comparing the number `getSnapshot`
  returns; [[re-frame.hicasso.impl.collector/snapshot-of]] is that exact
  number. When a save re-registers a subscription a mounted boundary
  reads, the number **does not move** at the instant of the save — the
  key is held, so it contributes its cell's frozen stamp — while the read
  path is address-directed and a body re-run in that window already
  answers with the edited subscription. So the markup is correct
  throughout and React has been told nothing: read the DOM inside the
  window and it is green because the body was re-run by the harness, read
  it after and it is green because the repair landed. Only the snapshot
  number, the notification count and the cell's reaction reference
  separate *repaired* from *never disturbed*.

  In the other direction, a save that re-registers subscriptions nothing
  on screen reads must NOT move any mounted boundary's number — and a
  runtime that moved it would also render perfectly, just with an extra
  pass per boundary per save. Green markup, wrong runtime.

  ## The harness

  [[re-frame.hicasso.impl.collector/commit-boundary!]] is the seam React
  occupies, as in the sibling file. `snapshot-of` is, in the collector's
  own words, the witness's way of performing React's
  `checkIfSnapshotChanged` without a browser."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures async]]
            [clojure.string :as str]
            [re-frame.adapter.uix :as uix-adapter]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.hicasso.impl.collector :as collector]
            [re-frame.hicasso.impl.generation :as generation]
            [re-frame.hicasso.impl.inventory :as inventory]
            [re-frame.test-support :as test-support]))

(def ^:private frame-id ::hmr-registry)

(rf/reg-event :hmr-registry/seed (fn [_ [_ label]] {:db {:label label}}))
(rf/reg-sub   :hmr-registry/label (fn [db _] (:label db)))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter       uix-adapter/adapter
     :ambient-frame nil
     :async?        true
     :init-fn       (fn [] (collector/reset-runtime!))}))

;; ---------------------------------------------------------------------------
;; Harness
;; ---------------------------------------------------------------------------

(def ^:private label-key [frame-id [:hmr-registry/label]])

(defn- seeded!
  [label]
  (set! (.-IS_REACT_ACT_ENVIRONMENT js/globalThis) false)
  (rf/make-frame {:id frame-id})
  (rf/with-frame frame-id (rf/dispatch-sync [:hmr-registry/seed label]))
  frame-id)

(defn- label-body [_props] (collector/sub [:hmr-registry/label]))

(defn- mount-boundary!
  "React's place at the commit seam, for a body of the caller's choosing."
  [body]
  (let [value    (collector/render-body frame-id body {})
        entry    (collector/last-reads)
        notified (volatile! 0)
        release  (collector/commit-boundary! entry (fn [] (vswap! notified inc)))]
    {:value value :entry entry :notified notified :release release}))

(defn- same-object?
  "`identical?`, answered as a plain boolean. `cljs.test` pr-strs a failing
  predicate's arguments and an incarnation token reaches a cyclic object
  graph, so a bare `(is (identical? …))` that fails reports a stack
  overflow rather than a diagnosis. Same reason, same spelling, as the
  sibling `hmr_remount_cljs_test`."
  [a b]
  (identical? a b))

(defn- after-macrotask
  "Run `f` strictly after the deferred repair's own `setTimeout 0`. 30 ms
  rather than 0 so the wait is not a race against the runtime's timers —
  the number the sibling reincarnation suites use, for the same reason."
  [f]
  (js/setTimeout f 30))

;; ---------------------------------------------------------------------------
;; 1. The counter a reload moves
;; ---------------------------------------------------------------------------

(deftest a-save-moves-the-registry-epoch-once-per-sub-registration
  (seeded! "A")
  (let [before (generation/registry-epoch)]

    ;; NEGATIVE CONTROL, taken FIRST. The epoch is the arm's count of `:sub`
    ;; registrations specifically, so a reload's OTHER top-level forms must
    ;; leave it alone. If this moved, every number below would be measuring
    ;; "a namespace loaded" rather than "a subscription was registered".
    (rf/reg-event :hmr-registry/other (fn [{:keys [db]} _] {:db db}))
    (is (= before (generation/registry-epoch))
        "an event registration moves the registry epoch by zero")

    (testing "a reload's re-registration of an existing sub moves it by one —
              the replacement case, which is the case a save is"
      (rf/reg-sub :hmr-registry/label (fn [db _] (:label db)))
      (is (= (inc before) (generation/registry-epoch))))

    (testing "and a first-time registration moves it by one as well: the
              counter does not distinguish them, because in the render-commit
              gap they are the same defect"
      (rf/reg-sub :hmr-registry/fresh (fn [db _] (:label db)))
      (is (= (+ 2 before) (generation/registry-epoch))))))

;; ---------------------------------------------------------------------------
;; 2. A save is not a re-render trigger
;; ---------------------------------------------------------------------------

(deftest an-unrelated-namespaces-save-disturbs-no-mounted-boundary
  ;; The property a developer feels as "saving a file elsewhere in the app
  ;; does not make the whole page thrash". `commit-basis` carries a registry
  ;; term, so this is not free by construction — it is bought by
  ;; `make-snapshot` reading that term ONLY for a key no cell holds, and it
  ;; is worth a witness because the rejected design (rf2-2rtt6.44) put the
  ;; term in every key's live contribution and would render identically.
  (seeded! "A")
  (let [{:keys [entry notified release]} (mount-boundary! label-body)
        snapshot-a (collector/snapshot-of entry)]

    ;; NEGATIVE CONTROL, taken FIRST so the instrument is proven live before
    ;; it is asked to report a tie.
    (rf/with-frame frame-id (rf/dispatch-sync [:hmr-registry/seed "A2"]))
    (is (> (collector/snapshot-of entry) snapshot-a)
        "an ordinary write MOVES the number React re-reads — so a tie below
         is the save's property and not a dead instrument")
    (is (= 1 @notified) "and notifies the committed boundary exactly once")

    (let [snapshot-before (collector/snapshot-of entry)
          notified-before @notified
          epoch-before    (generation/registry-epoch)]

      ;; The save: another namespace reloads and re-runs its registrations.
      (rf/reg-sub :hmr-registry/somewhere-else (fn [db _] (:label db)))
      (rf/reg-sub :hmr-registry/somewhere-else-2 (fn [db _] (:label db)))

      (testing "the registry epoch moved, so the save really happened"
        (is (= (+ 2 epoch-before) (generation/registry-epoch))))

      (testing "and the mounted boundary's number did not move, so React
                schedules nothing: a held key contributes its cell's frozen
                stamp, which no registration touches"
        (is (= snapshot-before (collector/snapshot-of entry)))
        (is (= notified-before @notified)))

      (testing "nor was its cell disturbed — the first-registration scan
                reaches only cells holding the id being registered"
        (is (some? (inventory/cell-reaction label-key)))))
    (release)))

;; ---------------------------------------------------------------------------
;; 3. One registration, two answers — the staged half and the held half
;; ---------------------------------------------------------------------------

(deftest one-save-is-invisible-to-a-mounted-boundary-and-visible-to-an-in-flight-one
  ;; The safety half of the same mechanism, and the sharpest way to state
  ;; it: ONE registration event, two entries, two opposite correct answers.
  ;; A boundary inside the render-commit gap holds no cell for its key, so
  ;; its contribution is a LIVE `commit-basis` read and the registry term
  ;; reaches it — which is what makes a `reg-sub` landing mid-render show up
  ;; as a tear React corrects, instead of a value silently one commit stale.
  (seeded! "A")
  (let [;; The held boundary: rendered AND committed, so it owns a cell.
        held    (mount-boundary! label-body)
        ;; The in-flight boundary: rendered, not yet committed, on a key no
        ;; cell holds. This is the render-commit gap, held open.
        _staged (collector/render-body frame-id
                                       (fn [_] (collector/sub [:hmr-registry/staged]))
                                       {})
        staged-entry (collector/last-reads)
        held-before   (collector/snapshot-of (:entry held))
        staged-before (collector/snapshot-of staged-entry)]

    (rf/reg-sub :hmr-registry/unrelated-to-both (fn [db _] (:label db)))

    (testing "the in-flight boundary's number MOVES — conservative in the
              safe direction, which is the only direction a monotone term
              added to a monotone sum can err in"
      (is (> (collector/snapshot-of staged-entry) staged-before)))

    (testing "and the mounted boundary's number TIES, on the very same
              registration — so the term's reach is exactly the set of keys
              inside a render-commit gap and not one key more"
      (is (= held-before (collector/snapshot-of (:entry held)))))

    ((:release held))))

;; ---------------------------------------------------------------------------
;; 4. The edited subscription: deaf for one macrotask, then repaired
;; ---------------------------------------------------------------------------

(deftest editing-a-sub-and-saving-repairs-its-readers-one-macrotask-later
  ;; The reload a developer actually performs: change the body of a `reg-sub`
  ;; and hit save. The re-registration arrives at the arm as a disposal, so
  ;; `invalidate-cell!` drops the held reference SYNCHRONOUSLY and defers the
  ;; rebuild to a macrotask — the same two-phase repair rf2-hic-013 measured
  ;; from the reincarnation side, reached here by the registry route.
  (async done
    (seeded! "hello")
    (let [{:keys [entry notified release value]} (mount-boundary! label-body)]
      (is (= "hello" value) "the boundary committed against the original sub")
      (is (some? (inventory/cell-reaction label-key)) "holding a live reaction")

      ;; NEGATIVE CONTROL, taken FIRST. Saving a file that registers OTHER
      ;; subscriptions must leave this cell's reaction in place — so the drop
      ;; measured below is caused by editing THIS subscription, and not by
      ;; the mere fact that a registration happened.
      (rf/reg-sub :hmr-registry/some-other-sub (fn [db _] (:label db)))
      (is (some? (inventory/cell-reaction label-key))
          "an unrelated registration leaves the held reaction intact")

      (let [snapshot-before (collector/snapshot-of entry)
            notified-before @notified]

        ;; THE SAVE: the same id, a changed computation. This is the edit.
        (rf/reg-sub :hmr-registry/label (fn [db _] (str/upper-case (:label db))))

        (testing "synchronously the held reference is gone — the replacement
                  reaches the arm as a disposal, and the repair's first phase
                  drops the reaction rather than deref a retired computation"
          (is (nil? (inventory/cell-reaction label-key))))

        (testing "yet React has been told nothing: the key is held, so its
                  contribution is the cell's frozen stamp and no registration
                  moves it"
          (is (= snapshot-before (collector/snapshot-of entry))
              "the number React re-reads TIES across the save")
          (is (= notified-before @notified)
              "and no re-render is scheduled"))

        (testing "meanwhile a body re-run inside the window already answers
                  with the EDITED subscription, because the read path resolves
                  the registration on the cold probe. This is exactly why a
                  rendered assertion is green on both sides of the window and
                  can see none of this"
          (is (= "HELLO" (collector/render-body frame-id label-body {}))))

        (after-macrotask
          (fn []
            (testing "one macrotask later the deferred phase has re-wired the
                      cell against the new registration, moved the number and
                      delivered the notification — so a boundary that painted
                      before the save is told to correct itself"
              (is (some? (inventory/cell-reaction label-key))
                  "re-wired rather than left deaf")
              (is (> (collector/snapshot-of entry) snapshot-before)
                  "the number React re-reads has moved")
              (is (> @notified notified-before)
                  "the repair is delivered, not merely performed"))
            (release)
            (done)))))))

;; ---------------------------------------------------------------------------
;; 5. The frame across a reload
;; ---------------------------------------------------------------------------

(deftest re-make-frame-is-the-reload-door-and-does-not-reincarnate
  ;; The bead's frame-routing row. An `^:dev/after-load` hook that re-runs
  ;; the application's `(rf/make-frame {:id …})` is the ordinary shape of a
  ;; re-initialising reload, and `make-frame`'s own contract calls that case
  ;; IDEMPOTENT REPLACEMENT — "config + generation refresh, durable state
  ;; preserved — hot-reload / Story-friendly". Witnessed here at the arm,
  ;; because what the arm cares about is whether the incarnation moved.
  (seeded! "A")
  (let [token-before  (frame/frame-incarnation-token frame-id)
        ;; Acquire the arm's frame row BEFORE the reload (rf2-x874). Since the
        ;; incarnation is pinned into the row at mint time, a render acquires
        ;; the row rather than a first dispatch — so a count sampled while the
        ;; table was still empty would compare an empty table against a
        ;; populated one and say nothing about the reload. Priming here is what
        ;; makes this row's own rationale — "a bundle captured before the
        ;; reload" — name something that exists.
        row-before    (collector/frame-row frame-id)
        frames-before (:frames (inventory/stats))]

    (rf/make-frame {:id frame-id})

    (testing "re-running the constructor does NOT mint a new incarnation"
      (is (true? (same-object? token-before (frame/frame-incarnation-token frame-id)))
          "the incarnation token is the same object"))

    (testing "durable state survives, so a reload does not empty app-db"
      (is (= "A" (collector/render-body frame-id label-body {}))))

    (testing "and the arm's frame-op memo is untouched, so a bundle captured
              before the reload still addresses the same incarnation"
      (is (= frames-before (:frames (inventory/stats)))
          "the reload added no row")
      (is (true? (same-object? row-before (collector/frame-row frame-id)))
          "and it is the SAME row object — so the bundle captured before the
           reload is the one still in use, which is the claim this row makes
           and could not previously check")
      (is (true? (same-object? token-before (:incarnation (collector/frame-row frame-id))))
          "pinned to the pre-reload incarnation, by object identity"))

    ;; NEGATIVE CONTROL. The token reader is only worth anything if it can
    ;; report a change; this is the transition that produces one, and it is
    ;; the one an after-load hook reaches by DESTROYING the frame before
    ;; rebuilding it.
    (testing "destroying and rebuilding, which is what a reload hook that
              tears the app down does, DOES reincarnate — and that is
              rf2-x874's territory, where an operation minted before the
              transition resolves its bundle when it fires and can write the
              successor silently. rf2-hic-013 owns that measurement; this row
              only marks the boundary between the two reload shapes"
      (rf/destroy-frame! frame-id)
      (rf/make-frame {:id frame-id})
      (is (false? (same-object? token-before (frame/frame-incarnation-token frame-id)))
          "a new incarnation, under the same public id"))))
