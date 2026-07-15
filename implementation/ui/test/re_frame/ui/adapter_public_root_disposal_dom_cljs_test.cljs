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

(deftest throwing-public-root-cleanup-is-total-and-container-is-reusable
  (when (browser?)
    (async done
      (rf/reg-sub ::adapter-value (fn [db _] (:value db)))
      (frame/replace-app-db! :rf/default {:value 7})
      (let [container (js/document.createElement "div")
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
               (is (= #{} (client/live-root-ids)))
               (is (= "" (.-textContent container))
                   "the adapter fallback empties a consumed host container")
               (is (= cell-base (reactive/root-cell-count)))
               (is (not-any? #(str/starts-with? % "__reactContainer$")
                             (array-seq
                              (js/Object.getOwnPropertyNames container)))
                   "the pinned React ownership marker is cleared for re-use")
               (act-promise
                #(ui/mount [observed-root {:label "fresh" :renders (atom 0)}]
                           container {:root-id :adapter-public/throwing}))))
            (.then
             (fn []
               (is (str/starts-with? (.-textContent container) "fresh:")
                   "the same root-id and container can mount a fresh generation")
               (act-promise rf/destroy-adapter!)))
            (.then (fn [] (done))
                   (fn [e]
                     (unexpected! done "throwing-root recovery rejected" e))))))))

(deftest adapter-destroy-reclaims-a-preexisting-failed-first-mount-quarantine
  (when (browser?)
    (async done
      (let [container   (js/document.createElement "div")
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
               (rf/init! ui/adapter)
               (act-promise
                #(vreset!
                  successor
                  (client/mount*
                   info container
                   (fn [] (React/createElement admission-probe))
                   nil nil)))))
            (.then
             (fn []
               (is (= "admitted" (.-textContent container)))
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
               (rf/init! ui/adapter)
               (act-promise
                #(vreset!
                  successor
                  (ui/mount [admission-probe] container
                            {:root-id :adapter-public/prequarantined})))))
            (.then
             (fn []
               (client/release-root!
                :adapter-public/prequarantined @predecessor)
               (is (identical?
                    @successor
                    (:root (client/live-root-entry
                            :adapter-public/prequarantined))))
               (is (= "admitted" (.-textContent container)))
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
