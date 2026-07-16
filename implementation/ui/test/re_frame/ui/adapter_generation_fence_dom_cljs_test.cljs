(ns re-frame.ui.adapter-generation-fence-dom-cljs-test
  "Browser proof of the destroy → immediate `rf/init!` generation fence
  (rf2-9pyles). `destroy-adapter!` returns SYNCHRONOUSLY while react-dom may have
  DEFERRED each public Root's teardown, and re-`init!` clears the disposed
  breadcrumb — so without an honest generation boundary a predecessor root's
  deferred layout/effect cleanup, or a queued cleanup task a throwing host
  `.unmount` left behind, runs AFTER a fresh generation (and a fresh same-id
  frame) are live and mutates the successor.

  Two composed fences are exercised here — both under a REAL react-dom root, not
  a headless model:

    1. The SUCCESSOR ROOT-ADMISSION fence: while any live-root claim from a PRIOR
       adapter generation is still `:tearing-down` (a deferred host teardown not
       yet settled), a fresh public Root — under ANY id — is rejected with the
       typed lifecycle error `:rf.error/adapter-teardown-in-flight`, EVEN AFTER a
       fresh adapter installed. Proven for a committed ZERO-ViewCell root and for
       MULTIPLE deferred roots, so completion waits on root-level reporter
       authority, not cell population.

    2. The INCARNATION-KEYED frame-capture fence: a `capture-frame` op pinned to
       incarnation A recovers-but-emits `:rf.error/frame-destroyed` (never
       enqueues) when the captured frame was destroyed and a same-id successor
       incarnation B reseated — so a predecessor root cleanup (deferred, OR a
       queued task a throwing `.unmount` left) can never mutate frame B."
  (:require [cljs.test :refer [async deftest is use-fixtures]]
            ["react" :as React]
            ["react-dom/client" :as rdc]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.test-support :as test-support]
            [re-frame.ui :as ui :refer [defview]]
            [re-frame.ui.client :as client]
            [re-frame.ui.reactive :as reactive]))

(defn- browser? [] (exists? js/document))

(use-fixtures
  :each
  (test-support/make-reset-runtime-fixture
   {:adapter ui/adapter :ambient-frame :rf/default :async? true}))

;; ---- helpers (mirrors adapter-public-root-disposal-dom-cljs-test) ----------

(defn- act-promise
  [thunk]
  (try
    (js/Promise.resolve (React/act (fn [] (js/Promise.resolve (thunk)))))
    (catch :default e
      (js/Promise.reject e))))

(defn- unexpected! [done label e] (is false (str label ": " e)) (done))

(defn- thrown-id
  "The `:rf.error/id` of the ExceptionInfo `f` throws, or nil if it does not."
  [f]
  (try (f) nil (catch cljs.core/ExceptionInfo e (:rf.error/id (ex-data e)))))

(defn- thrown-value [f] (try (f) nil (catch :default e e)))

(defn- next-macrotask
  "A promise resolving on the next macrotask — drains the whole microtask queue
  (React's deferred teardown + `settle-deferred-root!`'s FIFO settlement + any
  queued predecessor cleanup task) so a post-settlement assertion observes the
  converged state."
  []
  (js/Promise. (fn [resolve _] (js/setTimeout resolve 0))))

(defn- destroy-adapter-in-commit-phase!
  "Run `thunk` INSIDE a throwaway plain-React root's layout effect, so a
  `.unmount` of any OTHER root reached from it hits react-dom 19.2's in-render
  deferral (returns normally, teardown scheduled for a later microtask), then
  unmount the throwaway. Both act boundaries flush the deferred teardown +
  settlement microtasks."
  [thunk]
  (let [c2  (js/document.createElement "div")
        r   (rdc/createRoot c2)
        drv (fn [] (React/useLayoutEffect (fn [] (thunk) js/undefined) #js []) nil)]
    (-> (act-promise #(.render r (React/createElement drv)))
        (.then (fn [] (act-promise #(.unmount r)))))))

;; ---- views ----------------------------------------------------------------

;; A ZERO-ViewCell public root — no `ui/sub`, so it seats no connected ViewCell,
;; yet its `root-commit-reporter` still enrols a live reporter. The fence must
;; track its deferred teardown off that root-level authority, not cell count.
(defview cell-less-root []
  [:span {:data-cell-less "1"} "cell-less"])

;; A public root that DOES seat a ViewCell (reads a sub).
(defview celled-root [{:keys [label]}]
  [:output {:data-root label} label ":" (ui/sub [::gen-value])])

(defn- mount-cell-less! [container]
  (ui/mount [cell-less-root] container {:root-id :gen-fence/cell-less}))

(defn- mount-celled! [container label]
  (ui/mount [celled-root {:label label}] container {:root-id :gen-fence/celled}))

(defn- mount-fresh-probe! [container]
  ;; A DIFFERENT id / fresh container — proves the fence is GLOBAL to the
  ;; successor generation, not merely the exact quarantined id/container.
  (ui/mount [cell-less-root] container {:root-id :gen-fence/fresh}))

;; ===========================================================================
;; Fence 1 — successor root admission is fenced across the destroy → immediate
;; init boundary until every predecessor deferred teardown settles (AC2).
;; Covers a committed ZERO-ViewCell root AND multiple deferred roots (AC3).
;; ===========================================================================

(deftest immediate-init-is-fenced-until-predecessor-deferred-teardown-settles
  (when (browser?)
    (async done
      (rf/reg-sub ::gen-value (fn [db _] (:value db)))
      (frame/replace-app-db! :rf/default {:value 5})
      (let [cell-less (js/document.createElement "div")
            celled    (js/document.createElement "div")
            fresh     (js/document.createElement "div")
            cell-base (reactive/root-cell-count)
            ids-at-init         (atom :unset)
            reinit-mount-error  (atom :unset)]
        (-> (act-promise
             #(do (mount-cell-less! cell-less)
                  (mount-celled! celled "a")))
            (.then
             (fn []
               (is (= #{:gen-fence/cell-less :gen-fence/celled}
                      (client/live-root-ids)))
               (is (= "cell-less" (.-textContent cell-less))
                   "the cell-less root rendered (no sub — it seats no ViewCell)")
               (is (= "a:5" (.-textContent celled)))
               (is (> (reactive/root-cell-count) cell-base)
                   "the celled root seated a ViewCell; the cell-less root did not")
               ;; Destroy from a layout effect → BOTH public roots' `.unmount` is
               ;; DEFERRED. Immediately re-init BEFORE the settlement microtask and
               ;; attempt a fresh mount from inside the same commit thunk.
               (destroy-adapter-in-commit-phase!
                (fn []
                  (rf/destroy-adapter!)
                  (rf/init! ui/adapter)
                  (reset! ids-at-init (client/live-root-ids))
                  (reset! reinit-mount-error
                          (thrown-id #(mount-fresh-probe! fresh)))))))
            (.then (fn [] (next-macrotask)))
            (.then
             (fn []
               (is (= #{:gen-fence/cell-less :gen-fence/celled} @ids-at-init)
                   "both predecessor claims (cell-less AND celled) were still
                    :tearing-down when the successor generation installed")
               (is (= :rf.error/adapter-teardown-in-flight @reinit-mount-error)
                   "a fresh adapter is installed, but the successor generation
                    cannot admit a Root — under ANY id — until the predecessor
                    deferred teardown settles (NOT :rf.error/adapter-disposed,
                    which would falsely claim no adapter is installed)")
               (is (= "" (.-textContent fresh)) "no successor Root was created")
               ;; After the settlement boundary every predecessor claim releases.
               (is (= #{} (client/live-root-ids))
                   "the deferred settlement released every predecessor claim")
               (is (= cell-base (reactive/root-cell-count)))
               ;; Admission has reopened: the EXACT ids/containers reuse.
               (frame/replace-app-db! :rf/default {:value 9})
               (act-promise
                #(do (mount-cell-less! cell-less)
                     (mount-celled! celled "a2")))))
            (.then
             (fn []
               (is (= #{:gen-fence/cell-less :gen-fence/celled}
                      (client/live-root-ids))
                   "the exact ids/containers re-mount once the predecessor settled")
               (is (= "a2:9" (.-textContent celled)))
               (act-promise rf/destroy-adapter!)))
            (.then (fn [] (done))
                   (fn [e]
                     (unexpected! done "successor settlement fence rejected" e))))))))

;; ===========================================================================
;; Fence 2 (focused) — a frame api captured against incarnation A cannot enter a
;; same-id successor incarnation B; a stale captured dispatch — the exact shape a
;; deferred/queued root cleanup fires — recovers-but-emits (AC1). The REAL
;; host-teardown delivery of this same fence is exercised by the queue-then-throw
;; regression below.
;; ===========================================================================

(deftest captured-dispatch-cannot-enter-a-same-id-successor-frame
  (when (browser?)
    (async done
      (rf/make-frame {:id :cap/frame})
      (frame/replace-app-db! :cap/frame {})
      (rf/reg-event :cap/mutate (fn [{:keys [db]} _] {:db (assoc db :leaked? true)}))
      (let [errors (atom [])
            cap    (rf/capture-frame :cap/frame)]   ;; pins incarnation A (live now)
        (rf/register-listener! :errors ::cap-leak (fn [r] (swap! errors conj (:error r))))
        ;; while A is live the capture dispatches into A verbatim
        ((:dispatch-sync cap) [:cap/mutate])
        (is (true? (:leaked? (rf/app-db-value :cap/frame)))
            "the live-incarnation capture dispatches into frame A")
        ;; destroy A and reseat a fresh SAME-ID frame B (a DISTINCT incarnation)
        (rf/destroy-frame! :cap/frame)
        (rf/make-frame {:id :cap/frame})
        (frame/replace-app-db! :cap/frame {})
        (is (nil? (:leaked? (rf/app-db-value :cap/frame))) "frame B starts clean")
        ;; the STALE frame-A capture — the shape a predecessor root cleanup fires —
        ;; must NOT enter same-id frame B
        ((:dispatch-sync cap) [:cap/mutate])
        (is (nil? (:leaked? (rf/app-db-value :cap/frame)))
            "the stale frame-A capture did not leak into same-id frame B")
        (is (some #{:rf.error/frame-destroyed} @errors)
            "the superseded capture recovered-but-emitted :rf.error/frame-destroyed")
        (rf/unregister-listener! :errors ::cap-leak)
        (done)))))

;; ===========================================================================
;; The queue-then-throw host arm (bead NOTES / rf2-vxgfnd.275 §queue arm): a
;; predecessor `.unmount` SCHEDULES a late captured dispatch, then THROWS.
;; Destroy reclaims the quarantine (clears DOM + React marker), an immediate
;; init mounts a successor on the exact id/container, and the queued predecessor
;; dispatch must NOT enter same-id frame B — clearing DOM/markers is not
;; settlement, and the incarnation fence is the last-line guard.
;; ===========================================================================

(deftest queue-then-throw-cleanup-cannot-enter-a-same-id-successor
  (when (browser?)
    (async done
      (rf/make-frame {:id :qt/frame})
      (frame/replace-app-db! :qt/frame {})
      (rf/reg-event :qt/mutate (fn [{:keys [db]} _] {:db (assoc db :leaked? true)}))
      (let [container (js/document.createElement "div")
            root*     (volatile! nil)
            cap       (rf/capture-frame :qt/frame)        ;; pins incarnation A
            errors    (atom [])
            cleanup   (js/Error. "queue-then-throw host cleanup")]
        (rf/register-listener! :errors ::qt-leak (fn [r] (swap! errors conj (:error r))))
        (-> (act-promise
             #(vreset! root* (ui/mount [cell-less-root] container {:root-id :qt/root})))
            (.then
             (fn []
               ;; Replace the exact host handle's unmount seam: SCHEDULE a late
               ;; captured dispatch, then THROW. The marker/DOM deletion the
               ;; adapter reclaim performs is NOT settlement of the queued work.
               (let [^client/Root root @root*]
                 (set! (.-unmount (.-react-root root))
                       (fn []
                         (js/queueMicrotask
                          (fn [] ((:dispatch-sync cap) [:qt/mutate])))
                         (throw cleanup))))
               ;; Adapter destroy reclaims the throwing quarantine + sets the
               ;; disposed breadcrumb, then rethrows the host cleanup error.
               (is (identical? cleanup (thrown-value rf/destroy-adapter!)))
               (is (= #{} (client/live-root-ids))
                   "the throwing quarantine was reclaimed by adapter destroy")
               ;; Fresh successor generation FIRST (so the frame teardown/rebuild
               ;; below runs against a live adapter), then frame A destroyed + a
               ;; fresh SAME-ID frame B reusing the EXACT id/container.
               (rf/init! ui/adapter)
               (rf/destroy-frame! :qt/frame)
               (rf/make-frame {:id :qt/frame})
               (frame/replace-app-db! :qt/frame {})
               (act-promise
                #(ui/mount [cell-less-root] container {:root-id :qt/root}))))
            (.then (fn [] (next-macrotask)))          ;; flush the queued dispatch
            (.then
             (fn []
               (is (= "cell-less" (.. container -firstChild -textContent))
                   "the exact id/container mounted a fresh successor generation")
               (is (nil? (:leaked? (rf/app-db-value :qt/frame)))
                   "the queued predecessor captured dispatch did not enter same-id
                    frame B — clearing DOM/markers was not treated as settlement")
               (is (some #{:rf.error/frame-destroyed} @errors)
                   "the superseded queued dispatch recovered-but-emitted
                    :rf.error/frame-destroyed")
               (rf/unregister-listener! :errors ::qt-leak)
               (act-promise rf/destroy-adapter!)))
            (.then (fn [] (done))
                   (fn [e]
                     (unexpected! done "queue-then-throw fence rejected" e))))))))
