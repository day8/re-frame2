(ns re-frame.ui.preflight-supersession-dom-cljs-test
  "rf2-9ydq8q — the REAL-BROWSER DOM integration proof for the same-root
  render-supersession settlement (rf2-5ep117, PR #6060). The receipt-settlement
  layer is pinned host-shared in `preflight-authority-cljs-test` section E, which
  drives `frames/abort-preflight-attempt!` with two hand-built receipts. This
  file closes that bead's OTHER acceptance arm: a no-`act`/no-flush *integration*
  fixture that drives two ORDINARY `ui/render!` calls through the LIVE
  `re-frame.ui.client/seat-pending-attempt!` supersession path — the path Node
  cannot fake — and proves render B commits with ZERO
  `:rf.error/frame-preflight-evidence-mismatch` diagnostics.

  THE SCENARIO. A single live Root, rendered twice back-to-back inside one
  ordinary React `act` batch (so React has NOT committed A when B seats — the
  real supersession window):

    - render A installs `:sup/dom-frame` FRESH (config A, rev1) and seats its
      receipt as the Root's single pending render-attempt owner; React schedules
      but does not yet commit it;
    - render B REFRESHES the same-root frame (config B — a differing
      `:initial-events`, so a differing `config-fingerprint`, rev1 → rev2) and
      seats its OWN receipt. `seat-pending-attempt!` hands B to the abort of A's
      still-pending receipt as the SUPERSEDING attempt. A's overtaken same-root
      write is EXPECTED SETTLEMENT — it settles silently, emitting NO
      evidence-mismatch — while B commits as the authoritative attempt.

  A pre-#6060 runtime (abort not told about the superseding receipt) would raise
  a spurious `record-revision-mismatch` here — the fixture would go RED. If this
  goes red in a real browser for a REAL reason, that is a #6060 regression, not a
  fixture bug.

  Browser-only body — the `-dom-cljs-test$` suffix opts this file into the
  `:browser-test` build; under `:node-test` the DOM body gates on `(browser?)`
  and exits early."
  (:require [cljs.test :refer [async deftest is testing use-fixtures]]
            ["react" :as React]
            [re-frame.core :as rf]
            [re-frame.error-emit :as error-emit]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]
            [re-frame.ui :as ui :refer [defview frame-root]]
            [re-frame.ui.client :as client]
            [re-frame.ui.frames :as frames]))

(defn- browser? [] (exists? js/document))

(def ^:private frame-id :sup/dom-frame)
(def ^:private root-id  :sup-dom/root)

(use-fixtures :each
  (test-support/make-reset-runtime-fixture
   {:adapter plain-atom/adapter :ambient-frame nil :async? true})
  {:before (fn []
             (client/reset-live-roots!)
             (frames/reset-installed-plans!))
   :after  (fn []
             (client/reset-live-roots!)
             (frames/reset-installed-plans!))})

;; The repository's canonical native-React act helper (exact-render-capture
;; template): React's OWN `act()` running an async callback. Inside a single act
;; callback React BATCHES — two successive `.render` calls schedule without
;; committing between them — and flushes AFTER the callback resolves, committing
;; the LATEST render (B). That batch window is exactly the supersession window:
;; A stays pending (uncommitted) while B seats over it.
(defn- act-promise [thunk]
  (try
    (js/Promise.resolve (React/act (fn [] (js/Promise.resolve (thunk)))))
    (catch :default e
      (js/Promise.reject e))))

(defn- owner-root [entry]
  (or (:installed-by entry) (:adopted-by entry)))

(defn- unexpected! [done label e]
  (is false (str label ": " e))
  (done))

(defview mini [{:keys [label]}] [:div.mini label])

;; Two DISTINCT render call-sites: differing `:initial-events` source forms give
;; differing `config-fingerprint`s, so render B is a genuine same-root REFRESH
;; (rev1 -> rev2) — not a found-live no-op — reproducing section E's
;; config-change supersession through the real render path.
;; NB: `frame-root :id` is a compile-time LITERAL keyword (plans are static
;; identity) — the literal here MUST equal `frame-id`.
(defn- render-a! [root]
  (ui/render! root
    [frame-root {:id :sup/dom-frame :initial-events [[:test/set-db {:n 1}]]}
     [mini {:label "render-A"}]]))

(defn- render-b! [root]
  (ui/render! root
    [frame-root {:id :sup/dom-frame :initial-events [[:test/set-db {:n 2}]]}
     [mini {:label "render-B"}]]))

(deftest same-root-supersession-commits-b-with-zero-evidence-mismatch
  (if-not (browser?)
    (is true ":node — the browser gate runs the real seat-pending-attempt! body")
    (async done
      (rf/reg-event :test/set-db (fn [_ [_ db]] {:db db}))
      (let [container   (js/document.createElement "div")
            ;; :root-id is a compile-time literal (identity is fixed here) — the
            ;; literal MUST equal `root-id`.
            root        (ui/create-root container {:root-id :sup-dom/root})
            mismatches  (atom [])
            listener-id (keyword "test" (str (gensym "supersession-evidence")))
            cleanup!    (fn []
                          (error-emit/unregister-error-listener! listener-id)
                          (try (ui/unmount! root) (catch :default _ nil))
                          (.remove container))]
        (.appendChild (.-body js/document) container)
        (error-emit/register-error-listener!
         listener-id
         (fn [rec]
           (when (= :rf.error/frame-preflight-evidence-mismatch (:error rec))
             (swap! mismatches conj rec))))
        (-> (act-promise
             (fn []
               ;; render A — installs the frame fresh (rev1); React schedules but
               ;; has NOT committed it. Its receipt is now the Root's pending
               ;; render-attempt owner.
               (render-a! root)
               (let [pending-a (:pending-attempt (client/live-root-entry root-id))]
                 ;; render B — a same-root REFRESH (rev2); its seat supersedes A's
                 ;; still-pending receipt (the real seat-pending-attempt! path).
                 (render-b! root)
                 (let [pending-b (:pending-attempt (client/live-root-entry root-id))]
                   (testing "the real supersession fired — A was pending, B overtook it"
                     (is (some? pending-a)
                         "render A seated a pending attempt (still uncommitted)")
                     (is (some? pending-b)
                         "render B seated its own pending attempt")
                     (is (not (identical? pending-a pending-b))
                         (str "B superseded A's still-pending receipt — the live "
                              "seat-pending-attempt! supersession path was exercised")))))))
            (.then
             (fn []
               ;; act flushed → B is the committed render.
               (let [entry (frames/installed-plan-entry frame-id)
                     html  (.-innerHTML container)]
                 (testing "B commits benignly — ZERO preflight-evidence-mismatch"
                   (is (= [] @mismatches)
                       (str "the overtaken same-root write is EXPECTED settlement, "
                            "not a spurious frame-preflight-evidence-mismatch "
                            "(rf2-5ep117 / #6060)")))
                 (testing "B is the authoritative committed attempt"
                   (is (some? (re-find #"render-B" html))
                       "render B's subtree is the committed DOM")
                   (is (nil? (re-find #"render-A" html))
                       "render A never committed — it was superseded, not rendered")
                   (is (true? (:committed entry))
                       "B finalized as the committed root scope")
                   (is (= root-id (owner-root entry))
                       "the record is owned by this exact root")
                   (is (nil? (:mount-incomplete entry))
                       "aborting A's superseded receipt left NO spurious mount-incomplete")
                   (is (nil? (:preflight-attempt-failed entry))
                       "…and no failed-attempt residue on B's live record")))
               (act-promise #(ui/unmount! root))))
            (.then
             (fn []
               (error-emit/unregister-error-listener! listener-id)
               (.remove container)
               (is (not (contains? (client/live-root-ids) root-id))
                   "unmount releases the root claim")
               (done)))
            (.catch
             (fn [e]
               (cleanup!)
               (unexpected! done "same-root supersession browser proof rejected" e))))))))
