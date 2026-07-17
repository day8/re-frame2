(ns re-frame.ui.adapter-public-root-disposal-dom-cljs-test
  "Browser proof that the first-party adapter owns public compiled Roots.

  The generic React spine has a separate root registry, so this fixture must
  stay on the public `ui/mount` path: destroying `ui/adapter` drains every
  compiled Root, its ViewCells and observation owners, prevents late renders,
  and permits the same root-id/container to be mounted again. The failure arm
  pins total cleanup when the exact real Root's host unmount seam throws."
  (:require [cljs.test :refer [async deftest is use-fixtures]]
            [clojure.string :as str]
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

(defn- act-promise
  "Direct React 19 act boundary with an explicit rejection channel."
  [thunk]
  (try
    (js/Promise.resolve
     (React/act (fn [] (js/Promise.resolve (thunk)))))
    (catch :default e
      (js/Promise.reject e))))

(defn- unexpected!
  [done label e]
  (is false (str label ": " e))
  (done))

(defn- thrown-id
  "The `:rf.error/id` of the ExceptionInfo `f` throws, or nil if it does not."
  [f]
  (try (f) nil (catch cljs.core/ExceptionInfo e (:rf.error/id (ex-data e)))))

(defn- thrown-value [f]
  (try (f) nil (catch :default e e)))

(defn- next-macrotask
  "A promise resolving on the next macrotask — drains the whole microtask queue
  (React's deferred teardown + `settle-deferred-root!`'s FIFO settlement) so a
  post-settlement assertion observes the converged state."
  []
  (js/Promise. (fn [resolve _] (js/setTimeout resolve 0))))

(defn- destroy-adapter-in-commit-phase!
  "Invoke `rf/destroy-adapter!` from INSIDE a React layout effect (rf2-vxgfnd.276):
  render a throwaway plain-React root whose sole layout effect runs `thunk` — so
  a `.unmount` of any OTHER root reached from it hits react-dom 19.2's in-render
  deferral (returns normally, teardown scheduled for a later microtask) — then
  unmount the throwaway. Both act boundaries flush the deferred teardown +
  settlement microtasks. `thunk` captures the SYNCHRONOUS post-return state
  before those microtasks run. The throwaway is cell-less plain React, so it
  never perturbs the observed public-root population."
  [thunk]
  (let [c2  (js/document.createElement "div")
        r   (rdc/createRoot c2)
        drv (fn [] (React/useLayoutEffect (fn [] (thunk) js/undefined) #js []) nil)]
    (-> (act-promise #(.render r (React/createElement drv)))
        (.then (fn [] (act-promise #(.unmount r)))))))

(defview observed-root [{:keys [label renders]}]
  (let [_ (swap! renders inc)]
    [:output {:data-root label}
     label ":" (ui/sub [::adapter-value])]))

(defview admission-probe []
  [:span {:data-admission "open"} "admitted"])

(defn- mount-phase-two-root! [container]
  (ui/mount [admission-probe] container
            {:root-id :adapter-public/phase-two-attempt}))

(defn- mount-post-destroy-root! [container]
  (ui/mount [admission-probe] container
            {:root-id :adapter-public/post-destroy-mount}))

(defn- phase-two-cleanup-probe [props]
  (let [target (unchecked-get props "target")
        errors (unchecked-get props "errors")]
    (React/useEffect
     (fn []
       (fn []
         (try
           (mount-phase-two-root! target)
           (catch :default e
             (swap! errors conj e)))))
     #js [])
    (React/createElement "span" nil "generic-spine-owner")))

(defn- mount-observed!
  [container root-id label renders]
  (case root-id
    :adapter-public/a
    (ui/mount [observed-root {:label label :renders renders}]
              container {:root-id :adapter-public/a})

    :adapter-public/b
    (ui/mount [observed-root {:label label :renders renders}]
              container {:root-id :adapter-public/b})))

(deftest adapter-destroy-drains-all-public-compiled-roots
  (when (browser?)
    (async done
      (rf/reg-sub ::adapter-value (fn [db _] (:value db)))
      (frame/replace-app-db! :rf/default {:value 1})
      (let [a          (js/document.createElement "div")
            b          (js/document.createElement "div")
            renders    (atom 0)
            cell-base  (reactive/root-cell-count)
            frame-cell (frame/frame-state-container :rf/default)]
        (-> (act-promise
             #(do (mount-observed! a :adapter-public/a "a" renders)
                  (mount-observed! b :adapter-public/b "b" renders)))
            (.then
             (fn []
               (is (= #{:adapter-public/a :adapter-public/b}
                      (client/live-root-ids)))
               (is (= ["a:1" "b:1"] [(.-textContent a) (.-textContent b)]))
               (is (> (reactive/root-cell-count) cell-base))
               (is (some? (get @(:sub-cache (frame/frame :rf/default))
                               [::adapter-value])))
               (act-promise rf/destroy-adapter!)))
            (.then
             (fn []
               (let [settled-renders @renders]
                 (is (= #{} (client/live-root-ids)))
                 (is (= ["" ""] [(.-textContent a) (.-textContent b)]))
                 (is (= cell-base (reactive/root-cell-count)))
                 (is (nil? (get @(:sub-cache (frame/frame :rf/default))
                                [::adapter-value])))
                 ;; Mutate the retained SOURCE atom directly: no disposed
                 ;; observation or queued ViewCell may render after adapter
                 ;; destruction. Going through the adapter is deliberately
                 ;; impossible after destroy, and :app-db is a read-only
                 ;; derived value rather than this physical source.
                 (reset! frame-cell
                         (assoc @frame-cell :rf.db/app {:value 99}))
                 (-> (js/Promise.resolve nil)
                     (.then (fn []
                              (is (= settled-renders @renders))
                              (is (= ["" ""]
                                     [(.-textContent a) (.-textContent b)]))))))))
            (.then
             (fn []
               (rf/init! ui/adapter)
               (act-promise
                #(do (mount-observed! a :adapter-public/a "a2" renders)
                     (mount-observed! b :adapter-public/b "b2" renders)))))
            (.then
             (fn []
               (is (= ["a2:99" "b2:99"]
                      [(.-textContent a) (.-textContent b)]))
               (is (= #{:adapter-public/a :adapter-public/b}
                      (client/live-root-ids)))
               (act-promise rf/destroy-adapter!)))
            (.then (fn [] (done))
                   (fn [e]
                     (unexpected! done "public-root adapter disposal rejected" e))))))))

(deftest throwing-public-root-cleanup-is-total-and-container-fails-closed
  ;; rf2-sddbc — a throwing/consumed host `.unmount` reclaimed by adapter disposal
  ;; releases the root-id + identifier-prefix (a same-id re-mount on a FRESH
  ;; container is the ergonomic recovery), but the EXACT container is recorded
  ;; fail-closed: clearing its DOM + React marker is a snapshot, never proof that
  ;; host authority the `.unmount` may have QUEUED before it threw has settled.
  (when (browser?)
    (async done
      (rf/reg-sub ::adapter-value (fn [db _] (:value db)))
      (frame/replace-app-db! :rf/default {:value 7})
      (let [container (js/document.createElement "div")
            fresh     (js/document.createElement "div")
            cleanup   (js/Error. "public root cleanup failed")
            cell-base (reactive/root-cell-count)
            root-cell (atom nil)]
        (-> (act-promise
             #(reset! root-cell
                      (ui/mount [observed-root
                                 {:label "owned" :renders (atom 0)}]
                                container
                                {:root-id :adapter-public/throwing})))
            (.then
             (fn []
               ;; Keep the public Root, container, ViewCell and React marker
               ;; real; replace only this exact host handle's unmount seam so
               ;; the adapter's synchronous failure fallback is deterministic.
               ;; Exercise :dispose-adapter! directly to avoid taking a
               ;; position on the separately frozen core breadcrumb semantics.
               (let [^client/Root root @root-cell]
                 (set! (.-unmount (.-react-root root))
                       (fn [] (throw cleanup))))
               (try
                 ((:dispose-adapter! ui/adapter))
                 (throw (js/Error. "throwing cleanup unexpectedly resolved"))
                 (catch :default e
                   (is (identical? cleanup e)
                       "the host cleanup error remains observable")
                   nil))))
            (.then
             (fn []
               (is (= #{} (client/live-root-ids))
                   "the id/prefix are released — no permanent :tearing-down strand")
               (is (= cell-base (reactive/root-cell-count)))
               (is (true? (client/container-consumed? container))
                   "the exact consumed node is recorded fail-closed")
               ;; rf2-sddbc — reuse of the EXACT container is REJECTED fail-closed
               ;; with the honest fresh-node recovery (RED before the fix: it mounted).
               (let [err (try
                           (ui/mount [observed-root {:label "x" :renders (atom 0)}]
                                     container {:root-id :adapter-public/throwing})
                           nil (catch :default e e))]
                 (is (= :rf.error/root-container-consumed
                        (:rf.error/id (ex-data err)))
                     "exact-container reuse fails closed — never admitted onto a poisoned node")
                 (is (= :use-a-fresh-container (:recovery (ex-data err)))))
               ;; the ergonomic recovery: the SAME root-id on a FRESH container
               (act-promise
                #(ui/mount [observed-root {:label "fresh" :renders (atom 0)}]
                           fresh {:root-id :adapter-public/throwing}))))
            (.then
             (fn []
               (is (str/starts-with? (.-textContent fresh) "fresh:")
                   "the same root-id re-mounts on a FRESH container (the recovery)")
               (is (false? (client/container-consumed? fresh)))
               (act-promise rf/destroy-adapter!)))
            (.then (fn [] (done))
                   (fn [e]
                     (unexpected! done "throwing-root fail-closed recovery rejected" e))))))))

(deftest throwing-unmount-that-queues-dom-mutation-cannot-clobber-a-successor
  ;; rf2-sddbc — the counterexample the captured-dispatch queue-then-throw arm
  ;; missed: a host `.unmount` SCHEDULES a late `container.replaceChildren(...)` and
  ;; THEN throws. Adapter destroy reclaims the quarantine (clears DOM + marker,
  ;; releases the id/prefix), but that is a SNAPSHOT, not proof the queued host task
  ;; settled — no incarnation fence protects RAW DOM mutation. So the EXACT container
  ;; is fail-closed and the successor mounts on a FRESH node the queued predecessor
  ;; task can neither clobber nor replace. RED before the fix: the exact container
  ;; was reusable, and the queued replaceChildren then replaced the successor tree.
  (when (browser?)
    (async done
      (rf/reg-sub ::adapter-value (fn [db _] (:value db)))
      (frame/replace-app-db! :rf/default {:value 7})
      (let [container (js/document.createElement "div")
            fresh     (js/document.createElement "div")
            cleanup   (js/Error. "queue-then-throw host cleanup")
            root*     (volatile! nil)
            sentinel  (js/document.createElement "section")]
        (set! (.-textContent sentinel) "PREDECESSOR-CLOBBER")
        (-> (act-promise
             #(vreset! root*
                       (ui/mount [observed-root {:label "owned" :renders (atom 0)}]
                                 container {:root-id :adapter-public/queue-throw})))
            (.then
             (fn []
               ;; SCHEDULE a late DOM mutation against the container, THEN throw.
               (let [^client/Root root @root*]
                 (set! (.-unmount (.-react-root root))
                       (fn []
                         (js/queueMicrotask
                          (fn [] (.replaceChildren container sentinel)))
                         (throw cleanup))))
               (is (identical? cleanup (thrown-value rf/destroy-adapter!))
                   "the throwing host cleanup error still propagates")))
            (.then
             (fn []
               (is (= #{} (client/live-root-ids)) "the id/prefix are released")
               (is (true? (client/container-consumed? container))
                   "the exact queued-then-threw container is fail-closed")
               (rf/init! ui/adapter)
               ;; the exact container is REJECTED fail-closed …
               (let [err (try
                           (ui/mount [observed-root {:label "x" :renders (atom 0)}]
                                     container {:root-id :adapter-public/queue-throw})
                           nil (catch :default e e))]
                 (is (= :rf.error/root-container-consumed
                        (:rf.error/id (ex-data err)))
                     "exact-container reuse fails closed"))
               ;; … the successor mounts on a FRESH node instead.
               (act-promise
                #(ui/mount [observed-root {:label "successor" :renders (atom 0)}]
                           fresh {:root-id :adapter-public/queue-throw}))))
            (.then (fn [] (next-macrotask)))          ;; flush the queued replaceChildren
            (.then
             (fn []
               (is (str/starts-with? (.-textContent fresh) "successor:")
                   "the successor tree on the fresh node is intact")
               (is (not (str/includes? (.-textContent fresh) "PREDECESSOR-CLOBBER"))
                   "the queued predecessor task cannot clobber or replace the successor tree")
               (is (= "PREDECESSOR-CLOBBER" (.-textContent container))
                   "the queued replaceChildren landed harmlessly on the fail-closed old node")
               (act-promise rf/destroy-adapter!)))
            (.then (fn [] (done))
                   (fn [e]
                     (unexpected! done "queue-then-throw clobber fence rejected" e))))))))

(deftest adapter-destroy-reclaims-a-preexisting-failed-first-mount-quarantine
  (when (browser?)
    (async done
      (let [container   (js/document.createElement "div")
            fresh       (js/document.createElement "div")
            info        {:root-id :adapter-public/failed-first
                         :provenance :authored
                         :identifier-prefix "rf2-failed-first-"}
            primary     (js/Error. "first mount failed")
            cleanup     (js/Error. "failed-first cleanup failed")
            predecessor (volatile! nil)
            successor   (volatile! nil)
            thrown
            (thrown-value
             #(client/mount*
               info container
               (fn []
                 (let [^client/Root root
                       (:root (client/live-root-entry
                               :adapter-public/failed-first))]
                   (vreset! predecessor root)
                   (set! (.-unmount (.-react-root root))
                         (fn [] (throw cleanup)))
                   (throw primary)))
               nil nil))]
        (is (identical? primary thrown))
        (is (identical? cleanup (.-rfUiRollbackCleanupError thrown)))
        (is (true? (:tearing-down?
                    (client/live-root-entry :adapter-public/failed-first))))
        (is (true? (:cleanup-failure?
                    (client/live-root-entry :adapter-public/failed-first))))
        (-> (act-promise rf/destroy-adapter!)
            (.then
             (fn []
               (is (empty? (client/live-root-ids))
                   "adapter destroy reclaims a quarantine created before its drain")
               (is (true? (client/container-consumed? container))
                   "rf2-sddbc — the failed-first quarantine's exact container is fail-closed")
               (rf/init! ui/adapter)
               ;; rf2-sddbc — the ergonomic recovery is the SAME id/prefix on a
               ;; FRESH container; the exact old container stays fail-closed.
               (act-promise
                #(vreset!
                  successor
                  (client/mount*
                   info fresh
                   (fn [] (React/createElement admission-probe))
                   nil nil)))))
            (.then
             (fn []
               (is (= "admitted" (.-textContent fresh)))
               (is (identical?
                    @successor
                    (:root (client/live-root-entry
                            :adapter-public/failed-first))))
               ;; A late predecessor settlement/release signal remains fenced
               ;; to the exact old Root and cannot evict this fresh generation.
               (client/release-root!
                :adapter-public/failed-first @predecessor)
               (is (identical?
                    @successor
                    (:root (client/live-root-entry
                            :adapter-public/failed-first))))
               (act-promise rf/destroy-adapter!)))
            (.then (fn [] (done))
                   (fn [e]
                     (unexpected! done "failed-first quarantine reclaim rejected" e))))))))

(deftest adapter-destroy-reclaims-a-preexisting-public-unmount-quarantine
  (when (browser?)
    (async done
      (let [container   (js/document.createElement "div")
            fresh       (js/document.createElement "div")
            cleanup     (js/Error. "preexisting public cleanup failed")
            predecessor (volatile! nil)
            successor   (volatile! nil)]
        (-> (act-promise
             #(vreset!
               predecessor
               (ui/mount [admission-probe] container
                         {:root-id :adapter-public/prequarantined})))
            (.then
             (fn []
               (let [^client/Root root @predecessor]
                 (set! (.-unmount (.-react-root root))
                       (fn [] (throw cleanup)))
                 (is (identical? cleanup
                                 (thrown-value #(client/unmount!* root))))
                 (is (true? (:tearing-down?
                             (client/live-root-entry
                              :adapter-public/prequarantined))))
                 (is (true? (:cleanup-failure?
                             (client/live-root-entry
                              :adapter-public/prequarantined))))
                 (act-promise rf/destroy-adapter!))))
            (.then
             (fn []
               (is (empty? (client/live-root-ids)))
               (is (= "" (.-textContent container)))
               (is (true? (client/container-consumed? container))
                   "rf2-sddbc — the reclaimed public-unmount quarantine's node is fail-closed")
               (rf/init! ui/adapter)
               ;; rf2-sddbc — recover the SAME root-id on a FRESH container.
               (act-promise
                #(vreset!
                  successor
                  (ui/mount [admission-probe] fresh
                            {:root-id :adapter-public/prequarantined})))))
            (.then
             (fn []
               (client/release-root!
                :adapter-public/prequarantined @predecessor)
               (is (identical?
                    @successor
                    (:root (client/live-root-entry
                            :adapter-public/prequarantined))))
               (is (= "admitted" (.-textContent fresh)))
               (act-promise rf/destroy-adapter!)))
            (.then (fn [] (done))
                   (fn [e]
                     (unexpected! done "public prequarantine reclaim rejected" e))))))))

(deftest adapter-destroy-does-not-reclaim-a-preexisting-deferred-teardown
  (when (browser?)
    (async done
      (let [container        (js/document.createElement "div")
            root*            (volatile! nil)
            claim-pre-drain  (atom nil)
            claim-post-drain (atom nil)]
        (-> (act-promise
             #(vreset!
               root*
               (ui/mount [admission-probe] container
                         {:root-id :adapter-public/predeferred})))
            (.then
             (fn []
               (destroy-adapter-in-commit-phase!
                (fn []
                  ;; This first unmount occurs inside another root's layout
                  ;; effect, so react-dom defers its reporter cleanup.
                  (client/unmount!* @root*)
                  (reset! claim-pre-drain
                          (client/live-root-entry
                           :adapter-public/predeferred))
                  ;; Adapter destruction sees an already-tearing claim. It must
                  ;; distinguish this genuinely pending host settlement from a
                  ;; cleanup-failure quarantine and leave it fenced.
                  (rf/destroy-adapter!)
                  (reset! claim-post-drain
                          (client/live-root-entry
                           :adapter-public/predeferred))))))
            (.then (fn [] (next-macrotask)))
            (.then
             (fn []
               (is (true? (:tearing-down? @claim-pre-drain)))
               (is (not (:cleanup-failure? @claim-pre-drain))
                   "ordinary deferral is not classified as cleanup failure")
               (is (identical? (:root @claim-pre-drain)
                               (:root @claim-post-drain))
                   "adapter destroy did not reclaim the pending incarnation")
               (is (empty? (client/live-root-ids))
                   "the reporter-authoritative deferred settlement releases later")
               (rf/init! ui/adapter)
               (act-promise
                #(ui/mount [admission-probe] container
                           {:root-id :adapter-public/predeferred}))))
            (.then
             (fn []
               (is (= "admitted" (.-textContent container)))
               (act-promise rf/destroy-adapter!)))
            (.then (fn [] (done))
                   (fn [e]
                     (unexpected! done "preexisting deferred teardown rejected" e))))))))

(deftest adapter-destroy-closes-root-admission-through-the-generic-spine-tail
  (when (browser?)
    (async done
      (let [generic-container (js/document.createElement "div")
            attempted-root    (js/document.createElement "div")
            post-destroy      (js/document.createElement "div")
            cleanup-errors    (atom [])]
        (-> (act-promise
             #((:render ui/adapter)
               (React/createElement
                phase-two-cleanup-probe
                #js {:target attempted-root :errors cleanup-errors})
               generic-container
               {}))
            (.then
             (fn []
               (is (= "generic-spine-owner"
                      (.-textContent generic-container)))
               ;; The component's effect cleanup runs in phase two: AFTER the
               ;; public-root snapshot drained, while the generic spine root
               ;; unmounts. Its attempted public mount must still be fenced.
               (act-promise rf/destroy-adapter!)))
            (.then
             (fn []
               (let [cleanup-error (first @cleanup-errors)]
                 (is (= 1 (count @cleanup-errors))
                     "the generic-spine cleanup attempted exactly one public remount")
                 (is (= :rf.error/adapter-disposed
                        (:rf.error/id (ex-data cleanup-error)))
                     "phase-two root creation sees terminal adapter disposal")
                 (is (= :install-a-fresh-adapter
                        (:recovery (ex-data cleanup-error)))))
               (is (= "" (.-textContent attempted-root)))
               (is (= #{} (client/live-root-ids)))

               ;; The local drain fence has reopened, but the process-level
               ;; terminal breadcrumb must keep BOTH public creation spellings
               ;; closed until a fresh adapter generation installs.
               (let [create-error
                     (try
                       (ui/create-root post-destroy
                                       {:root-id :adapter-public/post-destroy-create})
                       nil
                       (catch :default e e))
                     mount-error
                     (try
                       (mount-post-destroy-root! post-destroy)
                       nil
                       (catch :default e e))]
                 (is (= [:rf.error/adapter-disposed
                         :rf.error/adapter-disposed]
                        (mapv #(-> % ex-data :rf.error/id)
                              [create-error mount-error])))
                 (is (every? #(= :install-a-fresh-adapter
                                  (:recovery (ex-data %)))
                             [create-error mount-error])))
               (is (= "" (.-textContent post-destroy)))
               (is (= #{} (client/live-root-ids)))

               (rf/init! ui/adapter)
               (act-promise #(mount-post-destroy-root! post-destroy))))
            (.then
             (fn []
               (is (= "admitted" (.-textContent post-destroy))
                   "a fresh generation atomically reopens public root admission")
               (act-promise rf/destroy-adapter!)))
            (.then (fn [] (done))
                   (fn [e]
                     (unexpected! done "root-admission lifecycle rejected" e))))))))

;; ===========================================================================
;; rf2-vxgfnd.276 — adapter destruction from a React layout effect COMPLETES
;; only after every deferred root settles.
;;
;; `adapter-destroy-drains-all-public-compiled-roots` above drives
;; `destroy-adapter!` through `act`, which flushes any deferral, so it never
;; observes the commit-phase window. This fixture invokes `rf/destroy-adapter!`
;; from INSIDE a React layout effect: `drain-live-roots!` then loops
;; `unmount!*` over roots whose `.unmount` react-dom 19.2 DEFERS. It proves the
;; retained synchronous `destroy-adapter! → nil` contract is HONEST in commit
;; phase — completion returns while each exact-root claim is still quarantined
;; `:tearing-down` (so a fresh mount is fail-closed rejected, never clobbering a
;; container React is still scheduled to clear), and the exact id/container
;; becomes reusable only AFTER the deferred settlement releases the claim.
;; ===========================================================================

(defn- mount-commit-root!
  "Mount a reactive public root under a LITERAL root-id (the `ui/mount` macro
  validates :root-id at expansion, so each id must be a literal keyword)."
  [container which label]
  (case which
    :a (ui/mount [observed-root {:label label :renders (atom 0)}]
                 container {:root-id :adapter-public/commit-a})
    :b (ui/mount [observed-root {:label label :renders (atom 0)}]
                 container {:root-id :adapter-public/commit-b})))

(deftest destroy-adapter-from-commit-phase-defers-then-settles
  (when (browser?)
    (async done
      (rf/reg-sub ::adapter-value (fn [db _] (:value db)))
      (frame/replace-app-db! :rf/default {:value 42})
      (let [a         (js/document.createElement "div")
            b         (js/document.createElement "div")
            cell-base (reactive/root-cell-count)
            ;; captured SYNCHRONOUSLY inside the layout effect, right after
            ;; destroy returns and BEFORE the deferred settlement microtasks run.
            returned          (atom :unset)
            ids-at-return     (atom :unset)
            reuse-at-return   (atom :unset)]
        (-> (act-promise
             #(do (mount-commit-root! a :a "a")
                  (mount-commit-root! b :b "b")))
            (.then
             (fn []
               (is (= #{:adapter-public/commit-a :adapter-public/commit-b}
                      (client/live-root-ids)))
               (is (= ["a:42" "b:42"] [(.-textContent a) (.-textContent b)]))
               (is (> (reactive/root-cell-count) cell-base)
                   "the two reactive roots each seated a ViewCell")
               ;; Invoke destroy from a layout effect → each public root's
               ;; `.unmount` is DEFERRED by react-dom.
               (destroy-adapter-in-commit-phase!
                (fn []
                  (reset! returned (rf/destroy-adapter!))
                  ;; Immediate post-return snapshot: the drain looped every root
                  ;; but each deferred teardown is still in flight, so the claims
                  ;; remain registered `:tearing-down` — completion did NOT wait,
                  ;; yet did NOT release a still-scheduled container either.
                  (reset! ids-at-return (client/live-root-ids))
                  ;; Admission is fail-closed while completion is pending: a fresh
                  ;; mount on the exact container is rejected (the terminal
                  ;; breadcrumb keeps public creation closed until a fresh
                  ;; adapter), never clobbering root a's deferred teardown.
                  (reset! reuse-at-return
                          (thrown-id #(mount-commit-root! a :a "a")))))))
            (.then (fn [] (next-macrotask)))
            (.then
             (fn []
               (is (nil? @returned)
                   "destroy-adapter! kept its synchronous nil completion in commit
                    phase (AC5 — the frozen contract is retained, not made async)")
               (is (= #{:adapter-public/commit-a :adapter-public/commit-b}
                      @ids-at-return)
                   "at the immediate return every root was still quarantined
                    :tearing-down — completion is reported without awaiting the
                    microtask, and a mutation that eagerly released here would
                    make this set empty (AC6)")
               (is (= :rf.error/adapter-disposed @reuse-at-return)
                   "a fresh mount before completion settles is rejected
                    deterministically — no new root clobbers a deferred teardown
                    (AC2)")
               ;; After the settlement boundary every claim/cell/DOM converges.
               (is (= #{} (client/live-root-ids))
                   "the deferred settlement released every exact-root claim (AC3)")
               (is (= ["" ""] [(.-textContent a) (.-textContent b)])
                   "React cleared both deferred containers")
               (is (= cell-base (reactive/root-cell-count))
                   "every ViewCell was reaped — no observation owner survived")
               ;; A fresh adapter generation, then reuse of the EXACT ids and
               ;; containers, now succeeds (AC3 — the ratified reuse after settle).
               (rf/init! ui/adapter)
               (act-promise
                #(do (mount-commit-root! a :a "a2")
                     (mount-commit-root! b :b "b2")))))
            (.then
             (fn []
               (is (= ["a2:42" "b2:42"] [(.-textContent a) (.-textContent b)])
                   "the exact ids/containers re-mount after the settlement boundary")
               (is (= #{:adapter-public/commit-a :adapter-public/commit-b}
                      (client/live-root-ids)))
               (act-promise rf/destroy-adapter!)))
            (.then (fn [] (done))
                   (fn [e]
                     (unexpected! done "commit-phase adapter disposal rejected" e))))))))

;; ===========================================================================
;; rf2-nd7z9h — retire the exact root-reporter authority after a SUCCESSFUL
;; manual/adapter quarantine recovery.
;;
;; PR #5911 added `live-reporters`, a STRONG set of committed root-incarnation
;; tokens. A throwing host `.unmount` never runs the reporter's mount-lifetime
;; cleanup, so `report-root-teardown!` never fires; adapter-wide recovery clears
;; the consumed container and calls `release-root!` (which dissociates
;; `live-roots` only), STRONGLY RETAINING the dead incarnation in
;; `live-reporters` — one leaked token per recovery cycle, plus stale
;; reporter-deferred teardown authority for the old token. These fixtures pin
;; that a SUCCESSFUL terminal reclaim now also retires the reporter authority,
;; while a FAILED reclaim (and an isolated `unmount!`) keeps both quarantine and
;; reporter authority fail-closed.
;; ===========================================================================

(defn- mount-retire-probe!
  "Mount a public compiled Root (every root render commits a `root-commit-reporter`,
  so its incarnation enrols in `live-reporters`) under a LITERAL root-id — the
  `ui/mount` macro validates :root-id at expansion, so each id must be a literal."
  [container which]
  (case which
    :sync         (ui/mount [admission-probe] container
                            {:root-id :adapter-public/retire-sync})
    :baseline     (ui/mount [admission-probe] container
                            {:root-id :adapter-public/retire-baseline})
    :succ         (ui/mount [admission-probe] container
                            {:root-id :adapter-public/retire-succ})
    :fail-closed  (ui/mount [admission-probe] container
                            {:root-id :adapter-public/retire-fail-closed})
    :reclaim-fail (ui/mount [admission-probe] container
                            {:root-id :adapter-public/retire-reclaim-fail})))

(deftest recovered-throwing-root-old-token-teardown-settles-synchronously
  (when (browser?)
    (async done
      (let [container (js/document.createElement "div")
            root*     (volatile! nil)
            old-inc   (volatile! nil)
            cleanup   (js/Error. "retire-sync host cleanup failed")]
        (-> (act-promise
             #(vreset! root* (mount-retire-probe! container :sync)))
            (.then
             (fn []
               (vreset! old-inc
                        (:root-incarnation
                         (client/live-root-entry :adapter-public/retire-sync)))
               (is (some? @old-inc))
               (is (true? (reactive/live-reporter? @old-inc))
                   "a committed root holds live reporter authority")
               ;; Replace only this exact host handle's unmount seam so the
               ;; adapter's synchronous failure fallback (reclaim + release) runs.
               (let [^client/Root root @root*]
                 (set! (.-unmount (.-react-root root)) (fn [] (throw cleanup))))
               (is (identical? cleanup
                               (thrown-value #((:dispose-adapter! ui/adapter))))
                   "the throwing host cleanup error still propagates")))
            (.then
             (fn []
               (is (= #{} (client/live-root-ids)))
               (is (false? (reactive/live-reporter? @old-inc))
                   "successful reclaim retired the old incarnation's reporter")
               ;; A no-signal `teardown-root!` for the RETIRED old token settles
               ;; SYNCHRONOUSLY — no live reporter to await. Before the fix the
               ;; retained token classifies it reporter-DEFERRED, so `on-settled`
               ;; would fire a microtask later and this volatile stay false here.
               (let [settled (volatile! false)]
                 (reactive/teardown-root! @old-inc (fn [] nil)
                                          (fn [] (vreset! settled true)))
                 (is (true? @settled)
                     "a teardown of the retired old token is synchronously terminal"))))
            (.then (fn [] (done))
                   (fn [e]
                     (unexpected! done "sync-teardown proof rejected" e))))))))

(deftest repeated-throwing-recovery-returns-reporter-ledger-to-baseline
  (when (browser?)
    (async done
      (let [reporter-base (reactive/live-reporter-count)
            root-base     (client/live-root-ids)
            cleanup       (js/Error. "retire-baseline host cleanup failed")
            run-cycle
            (fn run-cycle [n]
              (if (zero? n)
                (js/Promise.resolve nil)
                (let [container (js/document.createElement "div")
                      root*     (volatile! nil)]
                  (-> (act-promise
                       #(vreset! root* (mount-retire-probe! container :baseline)))
                      (.then
                       (fn []
                         (is (= (inc reporter-base) (reactive/live-reporter-count))
                             "a committed root adds exactly one reporter token")
                         (let [^client/Root root @root*]
                           (set! (.-unmount (.-react-root root))
                                 (fn [] (throw cleanup))))
                         (is (identical?
                              cleanup
                              (thrown-value #((:dispose-adapter! ui/adapter)))))
                         ;; The dead incarnation's reporter authority is retired at
                         ;; the successful reclaim — the ledger is back to baseline,
                         ;; not one strong token heavier.
                         (is (= reporter-base (reactive/live-reporter-count))
                             "adapter reclaim retired the reporter — ledger at baseline")
                         (is (= root-base (client/live-root-ids)))
                         (run-cycle (dec n))))))))]
        (-> (run-cycle 4)
            (.then
             (fn []
               (is (= reporter-base (reactive/live-reporter-count))
                   "after many recovery cycles the reporter ledger is at baseline,
                    not grown one strong token per cycle")))
            (.then (fn [] (done))
                   (fn [e]
                     (unexpected! done "reporter-ledger baseline proof rejected" e))))))))

(deftest late-predecessor-reporter-cleanup-cannot-affect-successor
  (when (browser?)
    (async done
      (let [container (js/document.createElement "div")
            fresh     (js/document.createElement "div")
            pred*     (volatile! nil)
            pred-inc  (volatile! nil)
            succ-inc  (volatile! nil)
            cleanup   (js/Error. "retire-succ predecessor cleanup failed")]
        (-> (act-promise
             #(vreset! pred* (mount-retire-probe! container :succ)))
            (.then
             (fn []
               (vreset! pred-inc
                        (:root-incarnation
                         (client/live-root-entry :adapter-public/retire-succ)))
               (is (true? (reactive/live-reporter? @pred-inc)))
               (let [^client/Root root @pred*]
                 (set! (.-unmount (.-react-root root)) (fn [] (throw cleanup))))
               ;; isolated unmount! quarantines fail-closed; adapter destroy then
               ;; reclaims (the `quarantined?` recovery branch, seam 1).
               (is (identical? cleanup (thrown-value #(client/unmount!* @pred*))))
               (is (true? (:cleanup-failure?
                           (client/live-root-entry :adapter-public/retire-succ))))
               (act-promise rf/destroy-adapter!)))
            (.then
             (fn []
               (is (= #{} (client/live-root-ids)))
               (is (false? (reactive/live-reporter? @pred-inc))
                   "the reclaimed quarantine retired the predecessor reporter")
               (is (true? (client/container-consumed? container))
                   "rf2-sddbc — the predecessor's poisoned container is fail-closed")
               (rf/init! ui/adapter)
               ;; rf2-sddbc — the same id re-mounts a fresh incarnation on a FRESH
               ;; container (the exact old node stays fail-closed).
               (act-promise #(mount-retire-probe! fresh :succ))))
            (.then
             (fn []
               (vreset! succ-inc
                        (:root-incarnation
                         (client/live-root-entry :adapter-public/retire-succ)))
               (is (not (identical? @pred-inc @succ-inc))
                   "the same-id successor is a distinct incarnation")
               (is (true? (reactive/live-reporter? @succ-inc)))
               ;; A LATE real-React reporter cleanup for the RETIRED predecessor:
               ;; idempotent (`disj`) + identity-checked, so it clears nothing of
               ;; the same-id successor.
               (reactive/report-root-teardown! @pred-inc)
               (is (false? (reactive/live-reporter? @pred-inc))
                   "the retired predecessor stays retired (idempotent)")
               (is (true? (reactive/live-reporter? @succ-inc))
                   "the successor's reporter authority is untouched")
               (act-promise rf/destroy-adapter!)))
            (.then (fn [] (done))
                   (fn [e]
                     (unexpected! done "late-predecessor idempotency proof rejected" e))))))))

(deftest public-unmount-alone-fails-closed-and-retains-reporter-authority
  (when (browser?)
    (async done
      (let [container (js/document.createElement "div")
            root*     (volatile! nil)
            inc*      (volatile! nil)
            cleanup   (js/Error. "retire-fail-closed host cleanup failed")]
        (-> (act-promise
             #(vreset! root* (mount-retire-probe! container :fail-closed)))
            (.then
             (fn []
               (vreset! inc*
                        (:root-incarnation
                         (client/live-root-entry :adapter-public/retire-fail-closed)))
               (is (true? (reactive/live-reporter? @inc*)))
               (let [^client/Root root @root*]
                 (set! (.-unmount (.-react-root root)) (fn [] (throw cleanup))))
               (is (identical? cleanup (thrown-value #(client/unmount!* @root*))))
               ;; No reclaim proof: the container is not advertised free, the claim
               ;; stays quarantined, AND the reporter authority is RETAINED —
               ;; retirement belongs only at a SUCCESSFUL exact recovery.
               (let [entry (client/live-root-entry :adapter-public/retire-fail-closed)]
                 (is (true? (:tearing-down? entry)))
                 (is (true? (:cleanup-failure? entry))))
               (is (contains? (client/live-root-ids)
                              :adapter-public/retire-fail-closed)
                   "the unproven container is not advertised free")
               (is (true? (reactive/live-reporter? @inc*))
                   "isolated unmount! retains reporter authority (no reclaim proof)")
               ;; adapter destroy then reclaims + retires, leaving a clean registry.
               (act-promise rf/destroy-adapter!)))
            (.then
             (fn []
               (is (= #{} (client/live-root-ids)))
               (is (false? (reactive/live-reporter? @inc*))
                   "the later adapter reclaim retired the reporter authority")))
            (.then (fn [] (done))
                   (fn [e]
                     (unexpected! done "unmount-alone fail-closed proof rejected" e))))))))

(deftest failed-container-reclaim-retains-quarantine-and-reporter-authority
  (when (browser?)
    (async done
      (let [container    (js/document.createElement "div")
            root*        (volatile! nil)
            inc*         (volatile! nil)
            cleanup      (js/Error. "retire-reclaim-fail host cleanup failed")
            reclaim-boom (js/Error. "container reclaim failed")]
        (-> (act-promise
             #(vreset! root* (mount-retire-probe! container :reclaim-fail)))
            (.then
             (fn []
               (vreset! inc*
                        (:root-incarnation
                         (client/live-root-entry :adapter-public/retire-reclaim-fail)))
               (is (true? (reactive/live-reporter? @inc*)))
               (let [^client/Root root @root*]
                 (set! (.-unmount (.-react-root root)) (fn [] (throw cleanup)))
                 ;; Force the adapter's terminal container reclaim to FAIL: the
                 ;; surface is never proven free, so recovery must retain BOTH the
                 ;; quarantine AND the reporter authority. Retiring the reporter
                 ;; BEFORE a proven reclaim is the mutation this fixture pins.
                 (set! (.-replaceChildren (.-container root))
                       (fn [] (throw reclaim-boom))))
               (is (identical? cleanup
                               (thrown-value #((:dispose-adapter! ui/adapter))))
                   "the primary host cleanup error still surfaces")))
            (.then
             (fn []
               (let [entry (client/live-root-entry :adapter-public/retire-reclaim-fail)]
                 (is (true? (:tearing-down? entry))
                     "a failed reclaim keeps the claim quarantined")
                 (is (true? (:cleanup-failure? entry))))
               (is (contains? (client/live-root-ids)
                              :adapter-public/retire-reclaim-fail))
               (is (true? (reactive/live-reporter? @inc*))
                   "a failed reclaim retains reporter authority (never retire before
                    a reclaim proves the surface free)")
               ;; Repair the reclaim seam and recover cleanly, so the fixture
               ;; teardown starts from an empty registry (and prove a subsequent
               ;; successful reclaim now retires the reporter).
               (js/Reflect.deleteProperty (.-container @root*) "replaceChildren")
               (thrown-value #((:dispose-adapter! ui/adapter)))
               (is (= #{} (client/live-root-ids)))
               (is (false? (reactive/live-reporter? @inc*))
                   "the repaired reclaim finally retired the reporter authority")))
            (.then (fn [] (done))
                   (fn [e]
                     (unexpected! done "failed-reclaim fail-closed proof rejected" e))))))))
