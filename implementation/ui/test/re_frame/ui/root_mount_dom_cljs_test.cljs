(ns re-frame.ui.root-mount-dom-cljs-test
  "S1c client mount smoke — REAL React roots (react-dom/client 19.x)
  against compiled views: mount / idempotent re-mount / create-root +
  render! / unmount! + remount, Layer-3 duplicate + container-ownership
  failures live, frame-root transparency + the preflight seam, the dev
  descriptor on the registry, and the S1 hydrate fail-loud.

  Browser-only bodies — the `-dom-cljs-test$` suffix (rf2-2hrj8) opts
  this file into the `:browser-test` build; `:node-test` /
  `:node-test-ui` still load it and every DOM-mounting test gates on
  `(browser?)` and exits early where `js/document` is absent."
  (:require [cljs.test :refer [deftest is testing use-fixtures async]]
            [clojure.string :as str]
            ["react" :as react]
            ["react-dom" :as react-dom]
            [re-frame.error :as error]
            [re-frame.substrate.adapter :as substrate-adapter]
            [re-frame.ui :as ui :refer [defview frame-root]]
            [re-frame.ui.client :as client]
            [re-frame.ui.reactive :as reactive]))

(defn- browser? [] (exists? js/document))

;; ---------------------------------------------------------------------------
;; The canonical React act() helper (rf2-vxgfnd.89 template; rf2-powx4d sweep)
;;
;; The repository's established native-React act pattern — the same
;; get-act / enable-react-act-env! shape as core's shared React suite
;; (react_shared_suite.cljs `with-browser-act`) and the frame-scope keystone
;; fixture. It uses React's OWN `act()`, NOT UIx/Reagent machinery.
;;
;; `flushSync` is NOT an act substitute: React's test contract treats any
;; update committed outside an act scope as "not wrapped in act(...)". The
;; `:browser-test` build runs EVERY `-dom-cljs-test` namespace on one shared
;; page, and sibling suites set IS_REACT_ACT_ENVIRONMENT=true and never reset
;; it — so a flushSync-only mount here emits that warning once the flag has
;; leaked true. Wrapping every mount / render / unmount in `act` — with the act
;; env enabled per-test in the fixture — makes the fixture clean under act
;; rather than silencing anything.
(defn- get-act
  "React's act() — React 19 promotes it to the React namespace proper
  (react-dom/test-utils on 18)."
  []
  (or (when (exists? (.-act react)) (.-act react))
      (try (.-act (js/require "react-dom/test-utils")) (catch :default _ nil))))

(defn- enable-react-act-env! []
  (when (browser?)
    (set! (.-IS_REACT_ACT_ENVIRONMENT js/globalThis) true)))

(defn- act!
  "Run `thunk` inside React `act()`, returning the thunk's value. The
  mount/render/unmount work commits synchronously under
  IS_REACT_ACT_ENVIRONMENT (enabled per-test in the fixture), so the callback
  runs eagerly and we capture its result."
  [thunk]
  (let [ret    (volatile! nil)
        act-fn (get-act)]
    (act-fn (fn [] (vreset! ret (thunk))))
    @ret))

(defn- flush-without-act!
  "Run `thunk` inside `flushSync` with the act env toggled OFF, restoring the
  prior flag after. For deliberately-throwing / aborting mounts (a throwing
  element thunk that mount* rolls back and re-throws): React `act` would
  surface the intended error at the act boundary, and the best-effort teardown
  commit during rollback would warn under the leaked act env. The canonical
  'real flushSync commit path' (react_shared_suite.cljs) — the throw still
  propagates for the surrounding `thrown-with-msg?` / `thrown-error`."
  [thunk]
  (let [prior (.-IS_REACT_ACT_ENVIRONMENT js/globalThis)]
    (set! (.-IS_REACT_ACT_ENVIRONMENT js/globalThis) false)
    (try (react-dom/flushSync thunk)
         (finally (set! (.-IS_REACT_ACT_ENVIRONMENT js/globalThis) prior)))))

(def ^:private prior-act-env (atom nil))

;; Map-form fixture because this namespace carries a real async settlement test.
(use-fixtures :each
  {:before (fn []
             ;; Enable React's act environment for this ns's DOM tests, then
             ;; RESTORE the prior value so the fixture neither depends on nor
             ;; leaks the shared-page flag.
             (reset! prior-act-env
                     (when (browser?) (.-IS_REACT_ACT_ENVIRONMENT js/globalThis)))
             (enable-react-act-env!)
             (reactive/reset-scheduler!)
             (client/reset-live-roots!))
   :after  (fn []
             (reactive/reset-scheduler!)
             (client/reset-live-roots!)
             (when (browser?)
               (set! (.-IS_REACT_ACT_ENVIRONMENT js/globalThis) @prior-act-env)))})

(defn- container []
  (js/document.createElement "div"))

(defn- thrown-error [f]
  (try (f) nil
       (catch cljs.core/ExceptionInfo e
         {:id (:rf.error/id (ex-data e))
          :msg (ex-message e)
           :data (ex-data e)})))

(defn- thrown-value
  "Capture a throw without placing `try` inside a cljs.test `async` body.
  The async macro lowers an inline try/catch to `await`, which would yield to
  the very rollback microtask these same-stack fixtures must precede."
  [f]
  (try (f) nil
       (catch :default e e)))

(defn- mount-outcome
  "Call the raw one-shot mount path and retain either its Root or its exact
  thrown value. Used by rollback fixtures whose whole point is whether claim
  admission reaches React allocation."
  [info c element-thunk]
  (try
    {:root (client/mount* info c element-thunk nil nil)}
    (catch :default e
      {:error e})))

(defn- cleanup-mounted-outcome!
  [{:keys [root]}]
  (when root
    (flush-without-act! #(client/unmount!* root))))

;; ---------------------------------------------------------------------------
;; Fixtures
;; ---------------------------------------------------------------------------

(defview mini-app
  [{:keys [title]}]
  [:div.mini-app [:h1 title]])

(defview other-app
  []
  [:div.other "other content"])

;; A plain React function component that reads React's `useId` and renders it
;; as text. useId's output carries the mounted root's effective
;; `identifierPrefix` (React root option), so it is the live proof that a
;; root's prefix reaches the mounted React tree (rf2-vxgfnd.59 AC6). Built and
;; mounted through the SAME `root-options` → `createRoot` path the compiler
;; emits (`client/root-options` + `client/mount*`).
(defn- use-id-probe []
  (react/createElement "span" #js {:className "uid"} (react/useId)))

(defn- mount-probe! [container prefix root-id]
  (client/mount*
   {:root-id root-id :provenance :authored :identifier-prefix prefix}
   container
   (fn [] (react/createElement use-id-probe))
   (client/root-options prefix nil nil nil)
   nil))

;; ---------------------------------------------------------------------------
;; mount — the guide-01 one-liner + idempotent re-mount
;; ---------------------------------------------------------------------------

(defn- mount-main! [c]
  (ui/mount [mini-app {:title "hello S1c"}] c {:root-id :dom-smoke/main}))

(defn- strip-view-evidence
  "Drop the DEV host-root view-evidence annotations from committed markup: the
  compiler's static `data-rf2-source-coord` / `data-rf-view` (rf2-hac8p) and the
  substrate's commit-time `data-rf-render-key` (rf2-ny34u). This smoke pins the
  rendered template shape, not the annotations — whose own coverage is the
  emit-annotation tests, the parity corpus, the render-key DOM-stamp tests, and
  test:elision."
  [html]
  (str/replace html #"\s+data-rf(?:2-source-coord|-view|-render-key)=\"[^\"]*\"" ""))

(deftest mount-smoke
  (when (browser?)
    (let [c (container)
          root (act! #(mount-main! c))]
      (is (some? root))
      (is (= :dom-smoke/main (client/root-id-of root)))
      (is (re-find #"<div class=\"mini-app\"><h1>hello S1c</h1></div>"
                   (strip-view-evidence (.-innerHTML c)))
          "the compiled root template renders through the React root")
      (is (contains? (client/live-root-ids) :dom-smoke/main))
      (let [entry (client/live-root-entry :dom-smoke/main)]
        (is (= :authored (:provenance entry)))
        (is (= 1 (get-in entry [:descriptor :rf.root/schema-version]))
            "the dev registry entry carries Root Descriptor v1")
        (is (= :dom-smoke/main (get-in entry [:descriptor :root-id])))
        (is (= ::mini-app (get-in entry [:descriptor :view-id])))
        (is (= :literal (get-in entry [:descriptor :props-shape])))
        (is (= {:title "hello S1c"}
               (get-in entry [:descriptor :static-props])))))))

(deftest mount-is-idempotent-per-root
  (when (browser?)
    (let [c (container)
          r1 (act! #(mount-main! c))
          r2 (act! #(mount-main! c))]
      (is (identical? r1 r2)
          "same root-id + same container re-renders the existing Root")
      (is (= 1 (count (client/live-root-ids)))))))

;; ---------------------------------------------------------------------------
;; Layer-3 failures, live
;; ---------------------------------------------------------------------------

(defn- mount-derived! [c]
  (ui/mount [mini-app {:title "derived"}] c))

(deftest duplicate-derived-root-id-fails-loud-live
  (when (browser?)
    (act! #(mount-derived! (container)))
    (let [{:keys [id msg]} (thrown-error #(mount-derived! (container)))]
      (is (= :rf.error/duplicate-root-id id)
          "same root-id on a DIFFERENT container fails loud (Layer 3)")
      (is (re-find #"add :disambiguator or author :root-id" msg)
          "both-derived diagnostics name the fix"))))

(deftest container-in-use-fails-loud-live
  (when (browser?)
    (let [c (container)]
      (act!
       #(ui/mount [mini-app {:title "first"}] c {:root-id :dom-inuse/first}))
      (let [{:keys [id data]}
            (thrown-error
             #(ui/create-root c {:root-id :dom-inuse/second}))]
        (is (= :rf.error/root-container-in-use id))
        (is (= :dom-inuse/first (:owner-root-id data)))))))

(deftest nil-container-fails-loud
  (let [{:keys [id]}
        (thrown-error
         #(ui/mount [mini-app {:title "x"}] nil {:root-id :dom-miss/x}))]
    (is (= :rf.error/root-container-missing id))))

;; ---------------------------------------------------------------------------
;; create-root + render!
;; ---------------------------------------------------------------------------

(deftest create-root-then-render
  (when (browser?)
    (let [c (container)
          root (ui/create-root c {:root-id :dom-cr/panel
                                  :identifier-prefix "rf2-custom-"})]
      (is (= "" (.-innerHTML c)) "create-root renders nothing")
      (act! #(ui/render! root [mini-app {:title "one"}]))
      (is (re-find #"one" (.-innerHTML c)))
      (act! #(ui/render! root [mini-app {:title "two"}]))
      (is (re-find #"two" (.-innerHTML c)) "render! re-renders")
      (let [entry (client/live-root-entry :dom-cr/panel)]
        (is (= :dom-cr/panel (get-in entry [:descriptor :root-id]))
            "render! completes the descriptor-base with the Root's identity")
        (is (= :authored (get-in entry [:descriptor :root-id-provenance])))))))

(deftest shared-authored-identifier-prefix-fails-loud-live
  ;; rf2-ez3fqk end-to-end: two DISTINCT real roots authoring the SAME
  ;; :identifier-prefix collide (their use-id output would alias). Proves the
  ;; compiler threads the effective :identifier-prefix through create-root*
  ;; into the client-tier uniqueness check, and that release frees it.
  (when (browser?)
    (let [c1 (container) c2 (container)
          r1 (ui/create-root c1 {:root-id :dom-pfx/a :identifier-prefix "rf2-shared-"})]
      (let [{:keys [id data]}
            (thrown-error
             #(ui/create-root c2 {:root-id :dom-pfx/b :identifier-prefix "rf2-shared-"}))]
        (is (= :rf.error/duplicate-identifier-prefix id)
            "the second root's shared prefix fails loud")
        (is (= :dom-pfx/a (:owner-root-id data)))
        (is (= "rf2-shared-" (:identifier-prefix data)))
        (is (= #{:dom-pfx/a} (client/live-root-ids))
            "the first root is untouched"))
      ;; release the owner — the prefix is now free for a fresh root
      (act! #(ui/unmount! r1))
      (let [r2 (ui/create-root c2 {:root-id :dom-pfx/b :identifier-prefix "rf2-shared-"})]
        (is (some? r2) "the prefix is reclaimable once its owner unmounts")
        (act! #(ui/unmount! r2))))))

;; ---------------------------------------------------------------------------
;; identifier-prefix reaches React useId; same-root prefix change fails loud
;; (rf2-vxgfnd.59)
;; ---------------------------------------------------------------------------

(deftest identifier-prefix-reaches-react-use-id-and-collisions-stop-before-commit
  ;; AC6 — mounted DOM proof via React useId. Two roots with DISTINCT
  ;; effective prefixes render distinct useId output that each carries its own
  ;; prefix (proving the prefix reaches the mounted React tree), and an
  ;; AUTHORED collision (a third root reusing a live prefix) is stopped at the
  ;; claim BEFORE createRoot / any commit — the colliding container stays empty.
  (when (browser?)
    (let [ca (container) cb (container) cc (container)
          ra (act! #(mount-probe! ca "rf2-uida-" :uid/a))
          rb (act! #(mount-probe! cb "rf2-uidb-" :uid/b))
          id-a (.-textContent ca)
          id-b (.-textContent cb)]
      (is (str/includes? id-a "rf2-uida-")
          "root A's useId carries its effective identifierPrefix — the prefix reaches React")
      (is (str/includes? id-b "rf2-uidb-")
          "root B's useId carries its own effective identifierPrefix")
      (is (not= id-a id-b)
          "distinct prefixes yield distinct useId output — no cross-root collision")
      (let [{:keys [id data]}
            (thrown-error
             #(react-dom/flushSync (fn [] (mount-probe! cc "rf2-uida-" :uid/c))))]
        (is (= :rf.error/duplicate-identifier-prefix id)
            "a third root reusing A's prefix fails loud")
        (is (= :uid/a (:owner-root-id data)))
        (is (= "" (.-innerHTML cc))
            "the colliding root committed nothing — stopped before createRoot"))
      (act! #(client/unmount!* ra))
      (act! #(client/unmount!* rb)))))

(deftest same-root-remount-changed-prefix-fails-loud-live
  ;; AC3 end-to-end: a real root mounted with prefix P1, then re-mounted on the
  ;; SAME root-id + container with a CHANGED prefix P2, fails loud — React root
  ;; options are immutable, so the running root cannot adopt P2. Its committed
  ;; render + registered prefix stay intact (untouched).
  (when (browser?)
    (let [c   (container)
          r1  (act! #(mount-probe! c "rf2-p1-" :hmr/x))
          id1 (.-textContent c)]
      (is (str/includes? id1 "rf2-p1-"))
      (let [{:keys [id data msg]}
            (thrown-error
             #(react-dom/flushSync (fn [] (mount-probe! c "rf2-p2-" :hmr/x))))]
        (is (= :rf.error/root-identifier-prefix-immutable id)
            "a changed prefix on a same-root re-mount fails loud")
        (is (= "rf2-p1-" (:existing data)) "the error names the live prefix")
        (is (= "rf2-p2-" (:requested data)) "and the requested one")
        (is (error/message-has-id-token? msg)))
      (is (= id1 (.-textContent c))
          "the original render is intact — the running root kept its prefix")
      (is (= "rf2-p1-" (:identifier-prefix (client/live-root-entry :hmr/x)))
          "the registry entry still carries the original prefix")
      (act! #(client/unmount!* r1)))))

;; ---------------------------------------------------------------------------
;; unmount! — total teardown + remount
;; ---------------------------------------------------------------------------

(deftest unmount-tears-down-and-frees-identity
  (when (browser?)
    (let [c (container)
          root (act!
                #(ui/mount [other-app {}] c {:root-id :dom-un/x}))]
      (is (re-find #"other content" (.-innerHTML c)))
      (act! #(ui/unmount! root))
      (is (= "" (.-innerHTML c)) "total teardown")
      (is (not (contains? (client/live-root-ids) :dom-un/x))
          "the root-id is unregistered")
      (is (nil? (act! #(ui/unmount! root))) "idempotent")
      (let [root2 (act!
                   #(ui/mount [other-app {}] c {:root-id :dom-un/x}))]
        (is (re-find #"other content" (.-innerHTML c))
            "identity + container free for a fresh mount")
        (act! #(ui/unmount! root2))))))

;; ---------------------------------------------------------------------------
;; exception-safety: total teardown + idempotent mount retry (rf2-vxgfnd.18)
;; ---------------------------------------------------------------------------

(deftest failed-first-mount-fences-claim-until-normal-cleanup-settlement
  ;; rf2-vxgfnd.291 RED-on-current: a throwing element thunk reaches the
  ;; registered first-mount rollback path after React allocation. A normally
  ;; returning host cleanup has no committed reporter from which to observe
  ;; settlement, so the exact predecessor claim must remain :tearing-down
  ;; until the next FIFO microtask. Same-id and different-id/same-container
  ;; retries are rejected before either successor element thunk (and therefore
  ;; before any successor React allocation can become a registered root).
  (when (browser?)
    (async
     done
     (let [c           (container)
           info        {:root-id :dom-throw/x :provenance :authored
                        :identifier-prefix "rf2-rollback-"}
           primary     (ex-info "element thunk boom" {:tag :element})
           predecessor (volatile! nil)
           successor-renders (atom [])
           boom        (fn []
                         (vreset! predecessor
                                  (:root (client/live-root-entry :dom-throw/x)))
                         (throw primary))
           ;; The element thunk throws before `.render` receives an element, so
           ;; the raw mount call is the exact run-to-completion boundary under
           ;; test. Keeping this off `flushSync` prevents React's test helper
           ;; from inserting its own post-callback checkpoint between the
           ;; rollback and the same-stack admission probes below.
           thrown      (thrown-value
                        #(client/mount* info c boom nil nil))
           same        (mount-outcome
                        info c
                        (fn []
                          (swap! successor-renders conj :same-id)
                          (react/createElement "div" nil "same")))
           ;; Current main admits `same`; clear that erroneous successor before
           ;; independently probing the different-id/same-container arm.
           _same-cleaned (cleanup-mounted-outcome! same)
           other       (mount-outcome
                        {:root-id :dom-throw/other :provenance :authored
                         :identifier-prefix "rf2-rollback-other-"}
                        c
                        (fn []
                          (swap! successor-renders conj :same-container)
                          (react/createElement "div" nil "other")))
           _other-cleaned (cleanup-mounted-outcome! other)]
       (testing "the mount error stays primary and the exact claim is fenced"
         (is (identical? primary thrown))
         (is (true? (:tearing-down?
                     (client/live-root-entry :dom-throw/x))))
         (is (= :rf.error/duplicate-root-id
                (:rf.error/id (ex-data (:error same)))))
         (is (= :rf.error/root-container-in-use
                (:rf.error/id (ex-data (:error other)))))
         (is (= [] @successor-renders)
             "both retries stop before their element/allocation boundary"))
       ;; The assertion callback is FIFO-after the rollback's settlement job.
       (js/queueMicrotask
        (fn []
          (try
            (testing "the claim releases only at settlement, then is reusable"
              (is (nil? (client/live-root-entry :dom-throw/x)))
              (let [successor (act!
                               #(ui/mount [mini-app {:title "retry ok"}]
                                          c {:root-id :dom-throw/x
                                             :identifier-prefix "rf2-rollback-"}))]
                (is (some? successor))
                (is (re-find #"retry ok" (.-innerHTML c)))
                ;; Exact-incarnation/root fencing: even if a predecessor release
                ;; signal is replayed late, it cannot evict the successor.
                (client/release-root! :dom-throw/x @predecessor)
                (is (identical? successor
                                (:root (client/live-root-entry :dom-throw/x))))
                (act! #(ui/unmount! successor))))
            (finally (done)))))))))

(deftest failed-first-mount-cleanup-throw-quarantines-and-force-deads
  ;; rf2-vxgfnd.291 RED-on-current: make the exact allocated React Root's
  ;; rollback cleanup throw deterministically. The original mount failure is
  ;; primary; cleanup is canonical secondary evidence. Framework ownership is
  ;; force-dead, but the host id/container/prefix claim remains fail-closed
  ;; :tearing-down because the container was never proven settled.
  (when (browser?)
    (let [c           (container)
          info        {:root-id :dom-throw/quarantine :provenance :authored
                       :identifier-prefix "rf2-quarantine-"}
          primary     (ex-info "first render failed" {:tag :primary})
          cleanup     (js/Error. "rollback cleanup failed")
          predecessor (volatile! nil)
          cell*       (volatile! nil)
          retry-renders (atom [])
          boom        (fn []
                        (let [entry (client/live-root-entry :dom-throw/quarantine)
                              ^client/Root root (:root entry)
                              cell  (reactive/make-cell ::rollback-owned)]
                          (vreset! predecessor root)
                          (vreset! cell* cell)
                          (reactive/attach-root! cell (:root-incarnation entry))
                          ;; The genuine mount path allocated this React Root;
                          ;; replace only its cleanup edge to make the host throw.
                          (set! (.-unmount (.-react-root root))
                                (fn [] (throw cleanup)))
                          (throw primary)))
          thrown      (try
                        (flush-without-act! #(client/mount* info c boom nil nil))
                        nil
                        (catch :default e e))
          same        (flush-without-act!
                       #(mount-outcome
                         info c
                         (fn []
                           (swap! retry-renders conj :same-id)
                           (react/createElement "div" nil "same"))))
          ;; Current main wrongly released the predecessor before its throwing
          ;; cleanup; clear the admitted same-id successor before the independent
          ;; same-container probe.
          _same-cleaned (cleanup-mounted-outcome! same)
          other       (flush-without-act!
                       #(mount-outcome
                         {:root-id :dom-throw/quarantine-other :provenance :authored
                          :identifier-prefix "rf2-quarantine-other-"}
                         c
                         (fn []
                           (swap! retry-renders conj :same-container)
                           (react/createElement "div" nil "other"))))
          _other-cleaned (cleanup-mounted-outcome! other)]
      (testing "primary/secondary error precedence is canonical"
        (is (identical? primary thrown))
        (is (identical? cleanup (.-rfUiRollbackCleanupError thrown))))
      (testing "framework ownership is dead while the exact host claim is quarantined"
        (is (= :dead (reactive/lifecycle @cell*)))
        (is (zero? (reactive/root-cell-count
                    (:root-incarnation
                     (client/live-root-entry :dom-throw/quarantine)))))
        (is (true? (:tearing-down?
                    (client/live-root-entry :dom-throw/quarantine))))
        (is (nil? (client/unmount!* @predecessor))
            "double cleanup is a no-op against the consumed quarantine"))
      (testing "neither same-id nor same-container retry can reopen quarantine"
        ;; rf2-h05lm — the same-id retry targets the EXACT poisoned node, so it reports
        ;; the terminal consumed-container condition (owner named) rather than hiding
        ;; the poisoned node behind duplicate-ID ordering + a `:make-root-ids-unique`
        ;; that promises a settlement this cleanup-failure quarantine will never reach.
        (is (= :rf.error/root-container-consumed
               (:rf.error/id (ex-data (:error same)))))
        (is (= :use-a-fresh-container
               (:recovery (ex-data (:error same)))))
        (is (= :dom-throw/quarantine
               (:owner-root-id (ex-data (:error same)))))
        ;; rf2-sddbc — a THROWING first-mount cleanup is a cleanup-failure
        ;; quarantine, so a same-container retry (any id) is fail-closed CONSUMED
        ;; (never merely in-use): the exact node can't be proven free, recovery is
        ;; a fresh node.
        (is (= :rf.error/root-container-consumed
               (:rf.error/id (ex-data (:error other)))))
        (is (= :use-a-fresh-container
               (:recovery (ex-data (:error other)))))
        (is (= [] @retry-renders)))
      )))

;; criterion 3 (a synchronous host `.render` throw AFTER host creation) is
;; covered by the SAME rollback catch as the thunk throw above: `mount*`
;; wraps `(.render react-root (element-thunk))` as one unit, so the thunk
;; throw exercises the identical release + best-effort-unmount + rethrow
;; path. There is deliberately no separate "component throws during render"
;; fixture: under React 19 an uncaught render-phase error does NOT
;; propagate synchronously out of `.render` — React reports it
;; (onUncaughtError) and internally unmounts the tree, and `.render`
;; returns normally, so the root stays legitimately registered (no
;; phantom). A genuine synchronous `.render` throw is a host-tier
;; invariant, not a component error, and is not reliably reproducible here.

(deftest re-render-failure-keeps-existing-root-registered
  ;; criterion 6 — the DISTINCT case: a throwing RE-render on an
  ;; already-live root leaves that root registered and its last committed
  ;; render intact (no rollback — the root was live before this call).
  (when (browser?)
    (let [c    (container)
          root (act!
                #(ui/mount [mini-app {:title "committed"}] c
                           {:root-id :dom-rerender/x}))]
      (is (re-find #"committed" (.-innerHTML c)))
      (let [info {:root-id :dom-rerender/x :provenance :authored}
            boom (fn [] (throw (ex-info "re-render boom" {})))]
        ;; same root-id + same container -> the idempotent re-render branch
        (is (thrown-with-msg? cljs.core/ExceptionInfo #"re-render boom"
                              (flush-without-act! #(client/mount* info c boom nil nil))))
        (is (contains? (client/live-root-ids) :dom-rerender/x)
            "the existing root stays registered on a failed re-render")
        (is (identical? root (:root (client/live-root-entry :dom-rerender/x)))
            "it is the same Root — not evicted or replaced")
        (is (re-find #"committed" (.-innerHTML c))
            "its last committed render is intact"))
      (act! #(ui/unmount! root)))))

;; ---------------------------------------------------------------------------
;; stale-root render! guard (rf2-vxgfnd.31)
;; ---------------------------------------------------------------------------

(deftest render-on-stale-root-fails-loud-with-zero-side-effects
  ;; render! on a root-id that is no longer live (its handle was
  ;; unmount!ed) must fail loud with :rf.error/root-not-live BEFORE any
  ;; side effect — the render-side mirror of unmount!*'s membership guard.
  ;; The pre-fix path ran frame preflight (draining :initial-events —
  ;; IRREVERSIBLE fx) and wrote install records under the dead root-id, then
  ;; only failed on .render against the unmounted React root.
  (when (browser?)
    (let [c    (container)
          root (ui/create-root c {:root-id :dom-stale/panel})]
      (act! #(ui/render! root [mini-app {:title "live"}]))
      (is (re-find #"live" (.-innerHTML c)))
      ;; tear it down — the handle is now STALE
      (act! #(ui/unmount! root))
      (is (not (contains? (client/live-root-ids) :dom-stale/panel)))
      (is (= "" (.-innerHTML c)) "the container is cleared by unmount!")
      ;; a preflight capture hook stands in for the side-effecting frame
      ;; executor: if the guard fired BEFORE run-preflight!, it is never
      ;; called (0 invocations) — proving no frame install / :initial-events
      ;; drain was attempted against the dead root.
      (let [preflight-calls (atom 0)]
        (client/set-preflight-hook! (fn [_ _] (swap! preflight-calls inc)))
        (try
          (let [{:keys [id data]}
                (thrown-error
                 (fn []
                   (react-dom/flushSync
                    (fn []
                      (ui/render! root
                                  [frame-root {:id :dom-stale/session
                                               :initial-events [[:boot 1]]}
                                   [mini-app {:title "stale"}]])))))]
            (is (= :rf.error/root-not-live id)
                "render! on the stale handle fails loud with the typed error")
            (is (= :dom-stale/panel (:root-id data))
                "the error names the stale root-id"))
          (is (zero? @preflight-calls)
              "the guard throws BEFORE run-preflight! — zero frame preflight, zero :initial-events drain")
          (is (= "" (.-innerHTML c))
              "no .render happened — the unmounted container stays empty")
          (is (not (contains? (client/live-root-ids) :dom-stale/panel))
              "the stale render! writes no install record under the dead root-id")
          (finally
            (client/set-preflight-hook! nil)))))))

;; ---------------------------------------------------------------------------
;; re-entrant mount during preflight (rf2-vxgfnd.52)
;; ---------------------------------------------------------------------------

(deftest reentrant-mount-during-preflight-keeps-inner-tree-and-fails-outer
  ;; End-to-end: outer mount A carries a frame-root, so its preflight runs.
  ;; The preflight hook stands in for A's :initial-events boot code, which
  ;; RE-ENTERS and mounts inner root B for the SAME root-id into a DIFFERENT
  ;; container. B's live React tree must survive and A must fail loud with no
  ;; orphan — the re-check (rf2-vxgfnd.52) fires before A createRoots/registers.
  (when (browser?)
    ;; This re-entrant path mounts inner root B from INSIDE the outer mount's
    ;; preflight (a bare ui/mount, deliberately outside any flushSync) while the
    ;; outer mount throws. It is a real flushSync commit path, not an act path:
    ;; run it with IS_REACT_ACT_ENVIRONMENT OFF (the react_shared_suite.cljs
    ;; pattern) so B's scheduled render — flushed here, not under act — commits
    ;; without an act warning, and the outer throw still propagates. The fixture
    ;; restores the prior flag; we restore it in the finally too.
    (let [prior  (.-IS_REACT_ACT_ENVIRONMENT js/globalThis)
          _      (set! (.-IS_REACT_ACT_ENVIRONMENT js/globalThis) false)
          c-a (container)
          c-b (container)
          b-root (atom nil)]
      (client/set-preflight-hook!
       (fn [_root-id _plans]
         (client/set-preflight-hook! nil)
         (reset! b-root (ui/mount [mini-app {:title "inner B"}] c-b
                                  {:root-id :reentry/x}))))
      (try
        (let [{:keys [id msg]}
              (thrown-error
               #(react-dom/flushSync
                 (fn []
                   (ui/mount [frame-root {:id :reentry/session
                                          :initial-events [[:boot 1]]}
                              [mini-app {:title "outer A"}]]
                             c-a {:root-id :reentry/x}))))]
          (is (= :rf.error/duplicate-root-id id)
              "outer A fails loud — the re-entrant inner claim is detected")
          (is (error/message-has-id-token? msg)))
        ;; flush B's scheduled render (mounted outside a flushSync)
        (react-dom/flushSync (fn []))
        (is (identical? @b-root (:root (client/live-root-entry :reentry/x)))
            "the registry still points at inner root B — no clobber")
        (is (re-find #"inner B" (.-innerHTML c-b))
            "B's live React tree survives")
        (is (= "" (.-innerHTML c-a))
            "outer A created no React root — no orphan tree in its container")
        (is (= #{:reentry/x} (client/live-root-ids))
            "only B is live — A left no phantom entry")
        (finally
          (client/set-preflight-hook! nil)
          (some-> @b-root ui/unmount!)
          (set! (.-IS_REACT_ACT_ENVIRONMENT js/globalThis) prior))))))

;; ---------------------------------------------------------------------------
;; adapter-generation admission across re-entrant preflight (rf2-vxgfnd.199)
;; ---------------------------------------------------------------------------
;;
;; A fresh mount admits under the exact adapter generation live BEFORE its
;; side-effecting preflight. run-preflight! drains :initial-events (arbitrary
;; app code) which can destroy — or destroy AND replace — the adapter while this
;; mount holds no React root yet. The exact-generation receipt must fail the
;; attempt loud before createRoot, so it never allocates a Root under a disposed
;; generation or a replacement generation B it was never admitted by.

(def ^:private minimal-adapter-a
  {:kind :custom :dispose-adapter! (fn [] nil)})
(def ^:private minimal-adapter-b
  {:kind :custom :dispose-adapter! (fn [] nil)})

(deftest fresh-mount-adapter-destroyed-in-preflight-fails-before-createroot
  ;; Terminal disposal from a fresh mount's real preflight: createRoot / register
  ;; / render must NOT run under the disposed adapter. Pre-fix, the post-preflight
  ;; re-check covered only root-id/container ownership, so the mount proceeded.
  (when (browser?)
    (let [c     (container)
          info  {:root-id :dom-gen/terminal :provenance :authored}
          prior @substrate-adapter/adapter-lifecycle-state]
      ;; The shared browser page seats the real ui adapter; clear then install
      ;; generation A cleanly, and restore the page's adapter in finally.
      (substrate-adapter/reset-lifecycle-state-for-tests!)
      (substrate-adapter/install-adapter! minimal-adapter-a)
      (client/set-preflight-hook!
       (fn [_root-id _plans]
         ;; A's :initial-events boot code terminally destroys the adapter and
         ;; returns normally; no outer React root exists to be torn down yet.
         (substrate-adapter/dispose-adapter!)))
      (try
        (let [{:keys [id data]}
              (thrown-error
               #(flush-without-act!
                 (fn []
                   (client/mount* info c
                                  (fn [] (react/createElement "div" nil "boot"))
                                  nil
                                  (fn [] [])))))]
          (is (= :rf.error/adapter-disposed id)
              "a fresh mount whose preflight destroyed the adapter fails loud before createRoot")
          (is (= :install-a-fresh-adapter (:recovery data))))
        (is (= "" (.-innerHTML c))
            "no React root or DOM was allocated under the disposed adapter")
        (is (not (contains? (client/live-root-ids) :dom-gen/terminal))
            "no live-root id, container claim, ViewCell, or observation owner remains")
        (finally
          (client/set-preflight-hook! nil)
          (reset! substrate-adapter/adapter-lifecycle-state prior))))))

(deftest fresh-mount-adapter-replaced-in-preflight-fails-and-leaves-b-untouched
  ;; Destroy generation A and install a replacement generation B during preflight.
  ;; disposed? is cleared, so a boolean recheck sees an open adapter — only the
  ;; exact-generation receipt distinguishes B from the A that admitted the attempt.
  (when (browser?)
    (let [c     (container)
          info  {:root-id :dom-gen/replace :provenance :authored}
          prior @substrate-adapter/adapter-lifecycle-state]
      (substrate-adapter/reset-lifecycle-state-for-tests!)
      (substrate-adapter/install-adapter! minimal-adapter-a)
      (client/set-preflight-hook!
       (fn [_root-id _plans]
         (substrate-adapter/dispose-adapter!)
         (substrate-adapter/install-adapter! minimal-adapter-b)))
      (try
        (let [{:keys [id]}
              (thrown-error
               #(flush-without-act!
                 (fn []
                   (client/mount* info c
                                  (fn [] (react/createElement "div" nil "boot"))
                                  nil
                                  (fn [] [])))))]
          (is (= :rf.error/adapter-disposed id)
              "an A-admitted mount cannot allocate a Root under replacement generation B"))
        (is (= "" (.-innerHTML c))
            "the A attempt created no React root — B and the container are untouched")
        (is (not (contains? (client/live-root-ids) :dom-gen/replace)))
        (is (identical? minimal-adapter-b
                        (substrate-adapter/current-adapter-spec))
            "generation B remains the installed adapter, unaffected by the failed A attempt")
        ;; A later fresh mount admitted UNDER B succeeds on the same id/container.
        (client/set-preflight-hook! nil)
        (let [root (act! #(client/mount* info c
                                         (fn [] (react/createElement "div" nil "under B"))
                                         nil nil))]
          (is (some? root) "a fresh mount admitted under B succeeds")
          (is (re-find #"under B" (.-innerHTML c)))
          (act! #(client/unmount!* root)))
        (finally
          (client/set-preflight-hook! nil)
          (reset! substrate-adapter/adapter-lifecycle-state prior))))))

;; ---------------------------------------------------------------------------
;; frame-root: transparent render + the preflight seam
;; ---------------------------------------------------------------------------

(defn- mount-framed! [c n]
  (ui/mount [frame-root {:id :dom-frames/session :initial-events [[:boot n]]}
             [mini-app {:title "framed"}]]
            c
            {:root-id :dom-frames/root}))

(deftest frame-root-renders-transparently-and-plans-ride-preflight
  (when (browser?)
    (let [seen (atom nil)]
      ;; a capture OVERRIDE observes plan threading without touching the
      ;; frames registry (no adapter needed here — the live ENSURE path is
      ;; pinned by the preflight-frame-wiring fixtures)
      (client/set-preflight-hook! (fn [_root-id plans] (reset! seen plans)))
      (try
        (act! #(mount-framed! (container) 42))
        (let [[plan :as plans] @seen]
          (is (= 1 (count plans)))
          (is (= :dom-frames/session (:frame-id plan)))
          (is (= {:initial-events [[:boot 42]]} (:config plan))
              "config expressions evaluate at preflight, with site locals")
          (is (re-find #"^cf1-[0-9a-f]{16}$" (:config-fingerprint plan))))
        (let [entry (client/live-root-entry :dom-frames/root)]
          (is (= [:dom-frames/session]
                 (mapv :frame-id (get-in entry [:descriptor :frame-plans])))
              "the descriptor carries the static plan subset"))
        (finally
          (client/set-preflight-hook! nil))))))

(deftest plans-thunk-absent-for-plan-free-root
  ;; a root form with no frame-root plans threads a nil plans-thunk — the
  ;; preflight is a no-op (nothing to ensure), so the override hook never
  ;; fires even though one is installed.
  (when (browser?)
    (let [calls (atom 0)]
      (client/set-preflight-hook! (fn [_ _] (swap! calls inc)))
      (try
        (act!
         #(ui/mount [mini-app {:title "plain"}] (container)
                    {:root-id :dom-plain/root}))
        (is (zero? @calls)
            "no static plans -> nil plans-thunk -> preflight no-op")
        (finally
          (client/set-preflight-hook! nil))))))

;; ---------------------------------------------------------------------------
;; hydrate-root: S1 fails loud (manifests land S5)
;; ---------------------------------------------------------------------------

(deftest hydrate-root-fails-loud-at-s1
  (when (browser?)
    (let [{:keys [id msg]}
          (thrown-error #(ui/hydrate-root (container)
                                          [mini-app {:title "h"}]))]
      (is (= :rf.error/root-manifest-invalid id))
      (is (error/message-has-id-token? msg)))))
