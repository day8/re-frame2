(ns re-frame.ui.preflight-generation-fence-cljs-test
  "rf2-vxgfnd.261 — the exact-adapter-generation preflight fence (rf2-vxgfnd.199)
  exercised through the LIVE ENSURE `:initial-events` flow, with real
  preflight-attempt RECEIPT cleanup.

  The fence itself is pinned by the DOM smokes
  (`re-frame.ui.root-mount-dom-cljs-test/fresh-mount-adapter-*-in-preflight-*`),
  but those drive `client/set-preflight-hook!` — a SYNTHETIC preflight that
  returns nil. A capture hook never runs `frames/execute-frame-plans!`, so it
  produces NO real ENSURE, NO `:initial-events` drain, and NO preflight-attempt
  receipt. It therefore cannot prove the load-bearing rf2-vxgfnd.261 claim through
  a REAL ENSURE run:

    - a REAL `:initial-events` that TERMINALLY disposes generation A fails the
      mount LOUD before `createRoot`, allocating no Root (caught at the live
      ENSURE layer, since no adapter remains for make-frame to complete against);
    - a REAL `:initial-events` that destroys A and installs a replacement
      generation B — the only shape that lets the ENSURE run COMPLETE and hand
      the client a real preflight-attempt RECEIPT — is caught by the exact-
      generation FENCE (a boolean disposed? recheck cannot see B), and that real
      receipt is terminally settled `:mount-incomplete`: never a phantom completed
      installer, never a Root allocated under a superseded generation.

  Node-runtime: the fence throws BEFORE `createRoot`, live-root registration, DOM
  mutation, ViewCell creation, or observation acquisition (rf2-vxgfnd.199), so no
  real React root is needed — containers are plain JS objects (the same shape as
  `re-frame.ui.root-registry-cljs-test`). We observe the REAL frame
  evidence + ownership ledgers (`frames/installed-plan-entry`,
  `client/live-root-ids`), not merely the absence of a live React root. The live
  end-to-end mount under a replacement generation B is the browser smoke
  (`root-mount-dom-cljs-test/fresh-mount-adapter-replaced-in-preflight-*`)."
  (:require [cljs.test :refer-macros [deftest is use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.substrate.adapter :as substrate-adapter]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]
            [re-frame.ui.client :as client]
            [re-frame.ui.frames :as frames]))

;; `:ambient-frame nil` — opt out of the `:rf/default` ambient scope so the
;; preflight ENSURE make-frame drains its `:initial-events` synchronously as a
;; top-level construction (not a mid-cascade child), matching the live
;; single-root mount path.
(use-fixtures :each
  (test-support/make-reset-runtime-fixture {:adapter plain-atom/adapter
                                            :ambient-frame nil})
  (fn [t]
    (client/set-preflight-hook! nil)   ;; no override — the LIVE ENSURE executor
    (client/reset-live-roots!)
    (frames/reset-installed-plans!)
    (try
      (t)
      (finally
        (client/set-preflight-hook! nil)
        ;; a fixture below may terminally dispose the adapter; leave the process
        ;; with a live adapter so a bare downstream test never inherits a
        ;; disposed generation (the next :each reset re-installs cleanly anyway).
        (when (substrate-adapter/adapter-disposed?)
          (substrate-adapter/install-adapter! plain-atom/adapter))
        (client/reset-live-roots!)
        (frames/reset-installed-plans!)))))

(defn- thrown-error [f]
  (try (f) nil
       (catch cljs.core/ExceptionInfo e
         {:id (:rf.error/id (ex-data e))
          :msg (ex-message e)
          :data (ex-data e)})))

(defn- adapter-generation []
  (get-in @substrate-adapter/adapter-lifecycle-state [:installed :generation]))

;; A plain (non-DOM) container — ownership is identity-based and the fence
;; throws before createRoot, so a real element is never needed.
(defn- container [] (js-obj))

(def ^:private drain-log (atom []))

(defn- reg-events! []
  (reset! drain-log [])
  (rf/reg-event :test/log
    (fn [_ [_ msg]] (swap! drain-log conj msg) {}))
  ;; A real `:initial-events` step that TERMINALLY disposes the adapter
  ;; generation that admitted the mount — the destructive boot code
  ;; rf2-vxgfnd.199 fences. Returns no `:db`, so no post-dispose container write
  ;; is attempted inside the drain.
  (rf/reg-event :test/dispose-adapter
    (fn [_ _]
      (swap! drain-log conj :dispose)
      (substrate-adapter/dispose-adapter!)
      {}))
  ;; A real `:initial-events` step that destroys generation A and installs a
  ;; SAME-spec replacement generation B. `install-adapter!` clears the disposed
  ;; breadcrumb, so a boolean recheck would see an open adapter — only the
  ;; exact-generation compare distinguishes B from the A that admitted the mount.
  (rf/reg-event :test/replace-adapter
    (fn [_ _]
      (swap! drain-log conj :replace)
      (substrate-adapter/dispose-adapter!)
      (substrate-adapter/install-adapter! plain-atom/adapter)
      {}))
  (rf/reg-event :test/destroy-own-frame
    (fn [_ [_ frame-id]]
      (swap! drain-log conj :destroy-own-frame)
      (frame/destroy-frame! frame-id)
      {})))

(deftest real-self-destroying-setup-fails-before-host-root-creation
  (reg-events!)
  (let [fid   :frame/self-destroying-mount
        rid   :preflight/self-destroying-mount
        c     (container)
        info  {:root-id rid :provenance :authored}
        plans (fn [] [{:frame-id fid
                       :config {:initial-events [[:test/destroy-own-frame fid]]}
                       :config-fingerprint "cf1-selfdestroying"}])
        {:keys [id data]}
        (thrown-error #(client/mount* info c (fn [] nil) nil plans))]
    (is (= [:destroy-own-frame] @drain-log)
        "the real setup reached its self-destroying initial event")
    (is (= :rf.error/frame-construction-in-progress id)
        "the lifecycle-dead provisional frame fails loud inside preflight")
    (is (= :lifecycle-dead (:reason data)))
    (is (nil? (frame/frame fid))
        "exact rollback leaves no self-destroyed frame")
    (is (nil? (frames/installed-plan-entry fid))
        "no plan record is published for the absent frame")
    (is (not (contains? (client/live-root-ids) rid))
        "the typed preflight failure precedes createRoot and live-root registration")))

;; ---------------------------------------------------------------------------
;; Terminal disposal from a REAL initial event — fail loud before createRoot
;;
;; A REAL `:initial-events` step that TERMINALLY disposes the admitting
;; generation (with no replacement) is caught at the LIVE ENSURE layer itself:
;; the disposal leaves the substrate with no adapter, so make-frame's own
;; post-drain adapter delegation raises `:rf.error/adapter-disposed` inside
;; `run-preflight!` — before the preflight receipt is produced and long before
;; `createRoot`. (The `require-root-creation-open!` disposed? branch of the
;; rf2-vxgfnd.199 fence is the SECONDARY backstop for the same terminal case; it
;; is exercised through a synthetic hook by
;; `root-mount-dom-cljs-test/fresh-mount-adapter-destroyed-in-preflight-*`.) The
;; contract that AC1 pins holds regardless of which layer catches it: a real
;; frame-root initial event destroys A, and the mount fails LOUD with the typed
;; pre-createRoot error, allocating no Root. The exact-generation FENCE with a
;; real receipt to clean up requires a LIVE post-drain generation and is proven
;; by the replacement fixture below.
;; ---------------------------------------------------------------------------

(deftest real-initial-event-terminal-disposal-fails-loud-before-createroot
  (reg-events!)
  (let [c    (container)
        info {:root-id :gen261/terminal :provenance :authored}
        gen-a (adapter-generation)
        plans (fn [] [{:frame-id :frame/gen261-terminal
                       :config {:initial-events [[:test/log :first]
                                                 [:test/dispose-adapter]]}
                       :config-fingerprint "cf1-gen261terminal"}])
        {:keys [id data]}
        (thrown-error #(client/mount* info c (fn [] nil) nil plans))]
    (is (some? gen-a) "sanity: generation A was installed before the mount")
    ;; REAL ENSURE ran: both real :initial-events drained synchronously, in order
    (is (= [:first :dispose] @drain-log)
        "the LIVE ENSURE executor drained both real :initial-events synchronously, in document order")
    ;; the mount failed LOUD with the typed error, before createRoot (AC1)
    (is (= :rf.error/adapter-disposed id)
        "a mount whose REAL :initial-events terminally disposed its admitting generation fails loud before createRoot")
    (is (= :install-a-fresh-adapter (:recovery data)))
    (is (true? (substrate-adapter/adapter-disposed?))
        "the real initial event terminally disposed the adapter (no replacement installed)")
    ;; the :initial-events fired (irreversible), then the post-drain adapter
    ;; failure made the owning construction transaction roll its frame back.
    (is (nil? (frame/frame :frame/gen261-terminal))
        "the live ENSURE drained setup, then exact construction rollback removed the provisional frame")
    ;; NO Root / DOM / ViewCell / observation owner was ever allocated
    (is (not (contains? (client/live-root-ids) :gen261/terminal))
        "no live-root id, container claim, ViewCell, or observation owner — the failure preceded any React allocation")))

;; ---------------------------------------------------------------------------
;; Destroy A + install replacement B from a REAL initial event — fail loud,
;; receipt settled, B left untouched (defeats the boolean disposed? recheck)
;; ---------------------------------------------------------------------------

(deftest real-initial-event-replacement-fails-and-leaves-b-untouched
  ;; The re-entrant-replacement companion: the REAL `:initial-events` destroy
  ;; generation A and install a SAME-spec generation B. The disposed breadcrumb
  ;; is cleared, so only the exact-generation compare (not a boolean recheck)
  ;; catches that B never admitted this mount. The real receipt is still settled
  ;; :mount-incomplete, and generation B — token, spec, and any root under it —
  ;; is untouched by the failed A attempt.
  (reg-events!)
  (let [c    (container)
        info {:root-id :gen261/replace :provenance :authored}
        gen-a (adapter-generation)
        plans (fn [] [{:frame-id :frame/gen261-replace
                       :config {:initial-events [[:test/replace-adapter]]}
                       :config-fingerprint "cf1-gen261replace"}])
        {:keys [id data]}
        (thrown-error #(client/mount* info c (fn [] nil) nil plans))]
    (is (some? gen-a) "sanity: generation A was installed before the mount")
    (is (= [:replace] @drain-log)
        "the LIVE ENSURE drained the real replacing :initial-event synchronously")
    (is (= :rf.error/adapter-disposed id)
        "an A-admitted mount cannot allocate a Root under replacement generation B — the exact-generation compare a boolean recheck cannot make")
    (is (= :install-a-fresh-adapter (:recovery data)))
    ;; the real receipt is terminally settled :mount-incomplete
    (let [entry (frames/installed-plan-entry :frame/gen261-replace)]
      (is (true? (:mount-incomplete entry))
          "the real receipt is terminally settled :mount-incomplete (receipt cleanup ran)")
      (is (not (:committed entry)) "never a phantom committed root scope"))
    (is (not (contains? (client/live-root-ids) :gen261/replace))
        "no live-root id / container claim / ViewCell / observation owner for the aborted A attempt")
    ;; generation B is installed and untouched by the failed A attempt
    (is (false? (substrate-adapter/adapter-disposed?))
        "generation B is live — the disposed breadcrumb was cleared by its install")
    (is (not (identical? gen-a (adapter-generation)))
        "B is a DISTINCT generation token from the A that admitted the failed mount")
    (is (identical? plain-atom/adapter (substrate-adapter/current-adapter-spec))
        "generation B remains the installed adapter, unaffected by the failed A attempt")))
