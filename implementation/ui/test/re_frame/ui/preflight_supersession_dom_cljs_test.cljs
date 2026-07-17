(ns re-frame.ui.preflight-supersession-dom-cljs-test
  "rf2-5ep117 / rf2-9kj98 — the REAL-BROWSER DOM integration proof for the
  same-root render-supersession settlement (PR #6060). The receipt-settlement
  layer is pinned host-shared in `preflight-authority-cljs-test` section E, which
  drives `frames/abort-preflight-attempt!` with two hand-built receipts. This
  file closes that bead's OTHER acceptance arm: two ORDINARY back-to-back
  `ui/render!` calls — no React `act`, no explicit render flush between them —
  through the LIVE `re-frame.ui.client/seat-pending-attempt!` supersession path,
  the path Node cannot fake.

  THE SCENARIO — a single live Root rendered twice back-to-back:

    - render A installs the SHARED same-root frame FRESH (config A, rev1) AND a
      DISJOINT frame (`:sup/omitted`) that the superseding render omits. A nested
      `frame-root` yields TWO document-order plans (compiler `scan-root-ast`), so
      A's preflight RECEIPT covers BOTH writes. React SCHEDULES A but does not
      commit it — A's receipt is now the Root's single pending render-attempt
      owner.
    - render B REFRESHES the shared frame (config B — a differing
      `:initial-events`, so a differing `config-fingerprint`, rev1 → rev2) and
      DROPS the disjoint frame entirely, so B's receipt covers ONLY the shared
      write. `seat-pending-attempt!` hands B to the abort of A's still-pending
      receipt as the SUPERSEDING attempt.

  WHAT THE ABORT DOES — the two arms this fixture makes CAUSALLY OBSERVABLE:

    1. the SHARED write B legitimately overtook (rev1 → rev2, same root) settles
       as EXPECTED supersession — silently, emitting NO
       `:rf.error/frame-preflight-evidence-mismatch` (the pre-#6060 / pre-x2vrh
       superseding-receipt handoff — passing B's receipt as the superseding
       envelope — is what makes it benign);
    2. A's DISJOINT write, which B does NOT account for, is TERMINALLY aborted:
       its record is marked `:mount-incomplete`. This is A's independently
       observable settlement — remove the old-receipt abort and this record
       stays unsettled.

  Then flushSync commits B: the shared record finalizes `:committed`, the
  pending-attempt slot CLEARS, render B is the committed DOM, and render A never
  rendered.

  NO REACT `act`. The two renders are ordinary `ui/render!` calls; React's
  concurrent-root scheduling leaves A genuinely uncommitted when B seats over it
  (the act env is OFF, per the fixture). Only B's COMMIT is forced, via
  `react-dom/flushSync` wrapping render B — a raw concurrent `.render` defers to
  a microtask that `flushSync` of a no-op will NOT force (verified against the
  pinned react-dom 19.2 `flushSyncWorkAcrossRoots_impl`: it flushes SYNC-lane
  work only), so B is rendered inside `flushSync` to commit deterministically.
  The seat + the abort of A's still-pending receipt run SYNCHRONOUSLY as B
  renders, BEFORE that commit — so the natural uncommitted A→B window is real and
  captured, never a manufactured act batch.

  TEETH. Mutating the superseding-receipt handoff (dropping B's receipt from the
  abort call) makes the shared write emit a spurious evidence-mismatch → the
  zero-mismatch assertion FAILS. Mutating the old-receipt abort (removing it)
  leaves A's disjoint write unsettled → the `:mount-incomplete` assertion FAILS.

  Browser-only body — the `-dom-cljs-test$` suffix opts this file into the
  `:browser-test` build; under `:node-test` the DOM body gates on `(browser?)`
  and exits early."
  (:require [cljs.test :refer [deftest is testing use-fixtures]]
            ["react-dom" :as react-dom]
            [re-frame.core :as rf]
            [re-frame.error-emit :as error-emit]
            [re-frame.frame :as frame]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]
            [re-frame.ui :as ui :refer [defview frame-root]]
            [re-frame.ui.client :as client]
            [re-frame.ui.frames :as frames]))

(defn- browser? [] (exists? js/document))

(def ^:private root-id    :sup-dom/root)
(def ^:private shared-id  :sup/shared)   ; the same-root frame REFRESHED A→B (commits B)
(def ^:private omitted-id :sup/omitted)  ; A's DISJOINT frame, omitted by B (terminally aborted)

(def ^:private prior-act-env (atom nil))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture
   {:adapter plain-atom/adapter :ambient-frame nil :async? true})
  {:before (fn []
             ;; This suite drives supersession through `flushSync`, NEVER `act`,
             ;; so React's natural (unbatched) concurrent scheduling stands and
             ;; render A stays genuinely uncommitted when B seats over it. The
             ;; `:browser-test` page shares IS_REACT_ACT_ENVIRONMENT across sibling
             ;; suites that DO use act — capture it, force OFF here, restore after.
             (reset! prior-act-env
                     (when (browser?) (.-IS_REACT_ACT_ENVIRONMENT js/globalThis)))
             (when (browser?) (set! (.-IS_REACT_ACT_ENVIRONMENT js/globalThis) false))
             (client/reset-live-roots!)
             (frames/reset-installed-plans!))
   :after  (fn []
             (client/reset-live-roots!)
             (frames/reset-installed-plans!)
             (when (browser?)
               (set! (.-IS_REACT_ACT_ENVIRONMENT js/globalThis) @prior-act-env)))})

(defn- owner-root [entry]
  (or (:installed-by entry) (:adopted-by entry)))

(defview mini [{:keys [label]}] [:div.mini label])

;; render A installs the SHARED frame FRESH (rev1) AND a DISJOINT frame the
;; superseding B omits — a nested `frame-root` yields TWO document-order plans, so
;; A's receipt covers BOTH writes.
;; NB: `frame-root :id` is a compile-time LITERAL keyword; the literals here MUST
;; equal `shared-id` / `omitted-id`.
(defn- render-a! [root]
  (ui/render! root
    [frame-root {:id :sup/shared :initial-events [[:test/set-db {:n 1}]]}
     [frame-root {:id :sup/omitted :initial-events [[:test/set-db {:n 10}]]}
      [mini {:label "render-A"}]]]))

;; render B REFRESHES the shared frame (a DIFFERING `:initial-events` → a differing
;; `config-fingerprint`, rev1 → rev2) and DROPS the disjoint frame entirely, so B's
;; receipt covers ONLY the shared write.
(defn- render-b! [root]
  (ui/render! root
    [frame-root {:id :sup/shared :initial-events [[:test/set-db {:n 2}]]}
     [mini {:label "render-B"}]]))

(deftest same-root-supersession-terminally-settles-a-and-commits-b
  (if-not (browser?)
    (is true ":node — the browser gate runs the real seat-pending-attempt! body")
    (let [_           (rf/reg-event :test/set-db (fn [_ [_ db]] {:db db}))
          container   (js/document.createElement "div")
          ;; :root-id is a compile-time literal — the literal MUST equal `root-id`.
          root        (ui/create-root container {:root-id :sup-dom/root})
          mismatches  (atom [])
          listener-id (keyword "test" (str (gensym "supersession-evidence")))]
      (.appendChild (.-body js/document) container)
      (error-emit/register-error-listener!
       listener-id
       (fn [rec]
         (when (= :rf.error/frame-preflight-evidence-mismatch (:error rec))
           (swap! mismatches conj rec))))
      (try
        ;; ---- render A: ordinary, NO flush — React schedules but does NOT commit.
        ;; A's receipt (shared rev1 + disjoint rev1) becomes the Root's pending
        ;; render attempt.
        (render-a! root)
        (let [pending-a (:pending-attempt (client/live-root-entry root-id))
              pending-b (volatile! ::unset)]
          ;; ---- render B: back-to-back, NO act/flush BETWEEN the two renders.
          ;; render B runs INSIDE `flushSync` ONLY so its commit is deterministic
          ;; (a raw concurrent `.render` defers past a `flushSync`-of-a-no-op). The
          ;; seat + the abort of A's still-pending receipt happen SYNCHRONOUSLY as B
          ;; renders, BEFORE the commit — captured here, before the slot clears.
          (react-dom/flushSync
           (fn []
             (render-b! root)
             (vreset! pending-b (:pending-attempt (client/live-root-entry root-id)))))
          ;; flushSync has committed B and run the reporter's layout effect.
          (let [omitted (frames/installed-plan-entry omitted-id)
                shared  (frames/installed-plan-entry shared-id)
                entry   (client/live-root-entry root-id)
                html    (.-innerHTML container)]

            (testing "the NATURAL uncommitted A→B window occurred — no act batch"
              (is (some? pending-a)
                  "render A seated a pending render attempt — React had NOT committed it")
              (is (some? @pending-b)
                  "render B seated its own pending attempt as it overtook A")
              (is (not (identical? pending-a @pending-b))
                  (str "B superseded A's STILL-PENDING receipt through the live "
                       "seat-pending-attempt! path — the uncommitted window was real")))

            (testing "A owns an INDEPENDENTLY OBSERVABLE write terminally aborted by supersession"
              (is (true? (:mount-incomplete omitted))
                  (str "the disjoint frame A installed and B omitted has its EXACT "
                       "receipt TERMINALLY aborted (:mount-incomplete) — removing the "
                       "old-receipt abort leaves this record UNSETTLED (real teeth)"))
              (is (= root-id (owner-root omitted))
                  "the aborted disjoint record is still owned by this exact root")
              (is (not (:committed omitted))
                  "the superseded disjoint write never became a committed root scope"))

            (testing "the SHARED frame commits B with ZERO preflight-evidence-mismatch"
              (is (= [] @mismatches)
                  (str "the overtaken same-root write is EXPECTED supersession, not a "
                       "spurious frame-preflight-evidence-mismatch — dropping the "
                       "superseding-receipt handoff makes THIS emit (rf2-5ep117 / #6060)"))
              (is (true? (:committed shared))
                  "B finalized the shared frame as a committed root scope")
              (is (= root-id (:installed-by shared))
                  "the shared record is owned by this exact root")
              (is (nil? (:mount-incomplete shared))
                  "no incomplete-mount residue on the committed shared record")
              (is (nil? (:preflight-attempt-failed shared))
                  "…and no failed-attempt residue"))

            (testing "B is the authoritative committed render"
              (is (some? (re-find #"render-B" html))
                  "render B's subtree is the committed DOM")
              (is (nil? (re-find #"render-A" html))
                  "render A never committed — it was superseded, not rendered"))

            (testing "the committed live-root entry carries NO pending-attempt"
              (is (some? entry) "the root is still live after B commits")
              (is (nil? (:pending-attempt entry))
                  (str "B's commit CLEARED the pending-attempt slot — the terminal "
                       "settlement the pre-#6067 fixture never asserted"))))

          ;; ---- teardown: unmount releases the root claim; destroying the frames
          ;; prunes their plan records — zero root/plan residue.
          (ui/unmount! root)
          (is (not (contains? (client/live-root-ids) root-id))
              "unmount releases the root claim")
          (is (nil? (client/live-root-entry root-id))
              "no residual live-root entry — root residue cleared")
          (frame/destroy-frame! shared-id)
          (frame/destroy-frame! omitted-id)
          (is (nil? (frames/installed-plan-entry shared-id))
              "destroying the shared frame prunes its plan record — plan residue cleared")
          (is (nil? (frames/installed-plan-entry omitted-id))
              "…and the disjoint frame's record too"))
        (finally
          (error-emit/unregister-error-listener! listener-id)
          (try (ui/unmount! root) (catch :default _ nil))
          (.remove container))))))
