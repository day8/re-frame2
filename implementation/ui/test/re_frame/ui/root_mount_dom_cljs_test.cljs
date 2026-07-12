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
  (:require [cljs.test :refer [deftest is testing use-fixtures]]
            ["react-dom" :as react-dom]
            [re-frame.error :as error]
            [re-frame.ui :as ui :refer [defview frame-root]]
            [re-frame.ui.client :as client]))

(defn- browser? [] (exists? js/document))

(use-fixtures :each
  {:before client/reset-live-roots!
   :after  client/reset-live-roots!})

(defn- container []
  (js/document.createElement "div"))

(defn- thrown-error [f]
  (try (f) nil
       (catch cljs.core/ExceptionInfo e
         {:id (:rf.error/id (ex-data e))
          :msg (ex-message e)
          :data (ex-data e)})))

;; ---------------------------------------------------------------------------
;; Fixtures
;; ---------------------------------------------------------------------------

(defview mini-app
  [{:keys [title]}]
  [:div.mini-app [:h1 title]])

(defview other-app
  []
  [:div.other "other content"])

;; ---------------------------------------------------------------------------
;; mount — the guide-01 one-liner + idempotent re-mount
;; ---------------------------------------------------------------------------

(defn- mount-main! [c]
  (ui/mount [mini-app {:title "hello S1c"}] c {:root-id :dom-smoke/main}))

(deftest mount-smoke
  (when (browser?)
    (let [c (container)
          root (react-dom/flushSync #(mount-main! c))]
      (is (some? root))
      (is (= :dom-smoke/main (client/root-id-of root)))
      (is (re-find #"<div class=\"mini-app\"><h1>hello S1c</h1></div>"
                   (.-innerHTML c))
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
          r1 (react-dom/flushSync #(mount-main! c))
          r2 (react-dom/flushSync #(mount-main! c))]
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
    (react-dom/flushSync #(mount-derived! (container)))
    (let [{:keys [id msg]} (thrown-error #(mount-derived! (container)))]
      (is (= :rf.error/duplicate-root-id id)
          "same root-id on a DIFFERENT container fails loud (Layer 3)")
      (is (re-find #"add :disambiguator or author :root-id" msg)
          "both-derived diagnostics name the fix"))))

(deftest container-in-use-fails-loud-live
  (when (browser?)
    (let [c (container)]
      (react-dom/flushSync
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
      (react-dom/flushSync #(ui/render! root [mini-app {:title "one"}]))
      (is (re-find #"one" (.-innerHTML c)))
      (react-dom/flushSync #(ui/render! root [mini-app {:title "two"}]))
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
      (ui/unmount! r1)
      (let [r2 (ui/create-root c2 {:root-id :dom-pfx/b :identifier-prefix "rf2-shared-"})]
        (is (some? r2) "the prefix is reclaimable once its owner unmounts")
        (ui/unmount! r2)))))

;; ---------------------------------------------------------------------------
;; unmount! — total teardown + remount
;; ---------------------------------------------------------------------------

(deftest unmount-tears-down-and-frees-identity
  (when (browser?)
    (let [c (container)
          root (react-dom/flushSync
                #(ui/mount [other-app {}] c {:root-id :dom-un/x}))]
      (is (re-find #"other content" (.-innerHTML c)))
      (ui/unmount! root)
      (is (= "" (.-innerHTML c)) "total teardown")
      (is (not (contains? (client/live-root-ids) :dom-un/x))
          "the root-id is unregistered")
      (is (nil? (ui/unmount! root)) "idempotent")
      (let [root2 (react-dom/flushSync
                   #(ui/mount [other-app {}] c {:root-id :dom-un/x}))]
        (is (re-find #"other content" (.-innerHTML c))
            "identity + container free for a fresh mount")
        (ui/unmount! root2)))))

;; ---------------------------------------------------------------------------
;; exception-safety: total teardown + idempotent mount retry (rf2-vxgfnd.18)
;; ---------------------------------------------------------------------------

(deftest failed-first-mount-leaves-no-phantom-and-retries-clean
  ;; criterion 2 — a throwing element thunk on a FIRST mount rolls back
  ;; TOTALLY: no live-root/container claim, the host root best-effort
  ;; unmounted, the ORIGINAL error rethrown, and a subsequent mount clean.
  (when (browser?)
    (let [c    (container)
          info {:root-id :dom-throw/x :provenance :authored}
          boom (fn [] (throw (ex-info "element thunk boom" {:tag :element})))]
      (is (thrown-with-msg? cljs.core/ExceptionInfo #"element thunk boom"
                            (react-dom/flushSync #(client/mount* info c boom nil nil)))
          "the original mount error propagates")
      (is (not (contains? (client/live-root-ids) :dom-throw/x))
          "no phantom live root remains after the failed first mount")
      (is (nil? (client/live-root-entry :dom-throw/x)))
      ;; retry on the SAME container + root-id via the public API succeeds
      (let [root (react-dom/flushSync
                  #(ui/mount [mini-app {:title "retry ok"}] c {:root-id :dom-throw/x}))]
        (is (some? root))
        (is (re-find #"retry ok" (.-innerHTML c))
            "a clean retry mounts successfully into the freed container")
        (ui/unmount! root)))))

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
          root (react-dom/flushSync
                #(ui/mount [mini-app {:title "committed"}] c
                           {:root-id :dom-rerender/x}))]
      (is (re-find #"committed" (.-innerHTML c)))
      (let [info {:root-id :dom-rerender/x :provenance :authored}
            boom (fn [] (throw (ex-info "re-render boom" {})))]
        ;; same root-id + same container -> the idempotent re-render branch
        (is (thrown-with-msg? cljs.core/ExceptionInfo #"re-render boom"
                              (react-dom/flushSync #(client/mount* info c boom nil nil))))
        (is (contains? (client/live-root-ids) :dom-rerender/x)
            "the existing root stays registered on a failed re-render")
        (is (identical? root (:root (client/live-root-entry :dom-rerender/x)))
            "it is the same Root — not evicted or replaced")
        (is (re-find #"committed" (.-innerHTML c))
            "its last committed render is intact"))
      (ui/unmount! root))))

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
      (react-dom/flushSync #(ui/render! root [mini-app {:title "live"}]))
      (is (re-find #"live" (.-innerHTML c)))
      ;; tear it down — the handle is now STALE
      (ui/unmount! root)
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
    (let [c-a (container)
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
          (some-> @b-root ui/unmount!))))))

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
        (react-dom/flushSync #(mount-framed! (container) 42))
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
        (react-dom/flushSync
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
