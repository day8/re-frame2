(ns re-frame.ui.root-registry-cljs-test
  "S1c Layer-3 live-root registry semantics (rf2-vxgfnd.3), node-runtime:
  the claim checks (duplicate root-id / container ownership / missing
  container), registration + release, the react-root options builder, the
  preflight seam, and the S1 hydrate fail-loud. No DOM — containers are
  plain JS objects (ownership is identity-based); the full React mount
  path is the browser smoke (`re-frame.ui.root-mount-dom-cljs-test`)."
  (:require [cljs.test :refer [deftest is testing use-fixtures]]
            [re-frame.error :as error]
            [re-frame.ui.client :as client]))

(use-fixtures :each
  {:before client/reset-live-roots!
   :after  client/reset-live-roots!})

(defn- thrown-error [f]
  (try (f) nil
       (catch cljs.core/ExceptionInfo e
         {:id (:rf.error/id (ex-data e))
          :msg (ex-message e)
          :data (ex-data e)})))

(defn- fake-root [root-id]
  (client/->Root (js-obj) (js-obj) root-id))

;; ---------------------------------------------------------------------------
;; Claim checks
;; ---------------------------------------------------------------------------

(deftest nil-container-is-container-missing
  (let [{:keys [id msg]}
        (thrown-error
         #(client/check-root-claim! 're-frame.ui/mount
                                    {:root-id :page/shop :provenance :authored}
                                    nil))]
    (is (= :rf.error/root-container-missing id))
    (is (error/message-has-id-token? msg))))

(deftest duplicate-root-id-rejected-before-any-render
  (let [c1 (js-obj) c2 (js-obj)
        info {:root-id :page/shop :provenance :authored :site {:file "a" :line 1}}]
    (client/check-root-claim! 're-frame.ui/mount info c1)
    (client/register-live-root! info c1 (fake-root :page/shop))
    (let [{:keys [id data]}
          (thrown-error
           #(client/check-root-claim! 're-frame.ui/mount
                                      {:root-id :page/shop :provenance :authored
                                       :site {:file "b" :line 2}}
                                      c2))]
      (is (= :rf.error/duplicate-root-id id))
      (is (= :authored (get-in data [:existing :provenance]))
          "the data map names both parties")
      (is (= {:file "b" :line 2} (get-in data [:arriving :site])))
      (is (= #{:page/shop} (client/live-root-ids))
          "the existing root is untouched (failure isolation)"))))

(deftest both-derived-duplicate-names-the-fix
  (let [c1 (js-obj)
        info {:root-id :app/main :provenance :derived}]
    (client/register-live-root! info c1 (fake-root :app/main))
    (let [{:keys [msg]}
          (thrown-error
           #(client/check-root-claim! 're-frame.ui/mount
                                      {:root-id :app/main :provenance :derived}
                                      (js-obj)))]
      (is (re-find #"add :disambiguator or author :root-id" msg)))))

(deftest container-in-use-by-a-different-root
  (let [c1 (js-obj)]
    (client/register-live-root! {:root-id :page/shop :provenance :authored}
                                c1 (fake-root :page/shop))
    (let [{:keys [id data]}
          (thrown-error
           #(client/check-root-claim! 're-frame.ui/create-root
                                      {:root-id :page/cart :provenance :authored}
                                      c1))]
      (is (= :rf.error/root-container-in-use id))
      (is (= :page/shop (:owner-root-id data)))
      (is (= :page/cart (:root-id data))))))

;; ---------------------------------------------------------------------------
;; Re-entrant mount during preflight (rf2-vxgfnd.52)
;; ---------------------------------------------------------------------------

(deftest reentrant-claim-change-during-preflight-fails-loud
  ;; mount* RE-CHECKS the claim AFTER preflight. run-preflight! drains
  ;; :initial-events synchronously (arbitrary app code); here the preflight
  ;; hook stands in for a boot handler that RE-ENTERS and mounts the SAME
  ;; root-id into a DIFFERENT container — registering inner-root B while the
  ;; outer mount A is still unregistered. Without the re-check, A would
  ;; createRoot + unconditionally register, CLOBBERING B's entry; the
  ;; re-check detects the ownership change and fails A loud BEFORE createRoot,
  ;; so B's entry survives and no orphan is created. Node-level: the throw
  ;; fires before createRoot, so no real DOM root is built (the container is
  ;; a plain js-obj); the live end-to-end tree-survival fixture is the browser
  ;; smoke (`re-frame.ui.root-mount-dom-cljs-test`).
  (let [c-a  (js-obj)
        c-b  (js-obj)
        b    (fake-root :re/x)
        info {:root-id :re/x :provenance :authored :identifier-prefix "rf2-re-x-"}
        plans-thunk (fn [] [{:frame-id :re/session :config-fingerprint "cf" :config {}}])]
    (client/set-preflight-hook!
     (fn [_root-id _plans]
       ;; the re-entrant inner mount B claims :re/x into c-b, mid-preflight
       (client/set-preflight-hook! nil)
       (client/register-live-root! {:root-id :re/x :provenance :authored
                                    :identifier-prefix "rf2-re-x-"}
                                   c-b b)))
    (try
      (let [{:keys [id data]}
            (thrown-error #(client/mount* info c-a (fn [] nil) (js-obj) plans-thunk))]
        (is (= :rf.error/duplicate-root-id id)
            "the re-check after preflight detects B's re-entrant claim and fails A loud")
        (is (= :re/x (:root-id data)))
        (is (identical? b (:root (client/live-root-entry :re/x)))
            "B's registry entry survives — not clobbered by A")
        (is (identical? c-b (:container (client/live-root-entry :re/x)))
            ":re/x still maps to B's container, never A's")
        (is (= #{:re/x} (client/live-root-ids))
            "A never registered — no phantom entry, no orphaned tree"))
      (finally (client/set-preflight-hook! nil)))))

(deftest reentrant-container-claim-during-preflight-fails-loud
  ;; the sibling axis: the re-entrant inner mount claims A's CONTAINER under a
  ;; DIFFERENT root-id. The re-check must surface :rf.error/root-container-in-use
  ;; (not clobber the container owner).
  (let [c    (js-obj)
        y    (fake-root :re/y)
        info {:root-id :re/x :provenance :authored :identifier-prefix "rf2-re-x-"}
        plans-thunk (fn [] [{:frame-id :re/session :config-fingerprint "cf" :config {}}])]
    (client/set-preflight-hook!
     (fn [_root-id _plans]
       (client/set-preflight-hook! nil)
       (client/register-live-root! {:root-id :re/y :provenance :authored
                                    :identifier-prefix "rf2-re-y-"}
                                   c y)))
    (try
      (let [{:keys [id data]}
            (thrown-error #(client/mount* info c (fn [] nil) (js-obj) plans-thunk))]
        (is (= :rf.error/root-container-in-use id)
            "the re-check detects the container was claimed during preflight")
        (is (= :re/y (:owner-root-id data)))
        (is (= #{:re/y} (client/live-root-ids))
            "the container owner survives — A never registered"))
      (finally (client/set-preflight-hook! nil)))))

(deftest release-is-handle-guarded
  (let [c1 (js-obj)
        r1 (fake-root :page/shop)
        r2 (fake-root :page/shop)]
    (client/register-live-root! {:root-id :page/shop :provenance :authored} c1 r1)
    (client/release-root! :page/shop r2)
    (is (= #{:page/shop} (client/live-root-ids))
        "a stale handle never evicts a newer claim")
    (client/release-root! :page/shop r1)
    (is (= #{} (client/live-root-ids)))))

(deftest unmount-of-an-unregistered-root-is-a-no-op
  (is (nil? (client/unmount!* (fake-root :never/registered))))
  (is (nil? (client/unmount!* nil))))

(defn- root-with-throwing-unmount [root-id container]
  ;; a Root whose host react-root's `.unmount` throws — exercises the
  ;; unmount!* teardown boundary without a real React root (the registry
  ;; release must still run in the `finally`).
  (let [rr (js-obj)]
    (unchecked-set rr "unmount"
                   (fn [] (throw (js/Error. "host teardown boom"))))
    (client/->Root rr container root-id)))

(deftest unmount-releases-claim-even-when-host-teardown-throws
  ;; rf2-vxgfnd.18 — TOTAL teardown: a throwing host `.unmount` must not
  ;; strand the framework claim. The registry release rides a `finally`,
  ;; the host error still propagates, and a second unmount!* is a no-op.
  (let [c    (js-obj)
        root (root-with-throwing-unmount :reg/boom c)]
    (client/register-live-root! {:root-id :reg/boom :provenance :authored} c root)
    (is (= #{:reg/boom} (client/live-root-ids)))
    (is (thrown-with-msg? js/Error #"host teardown boom"
                          (client/unmount!* root))
        "the host teardown error propagates to the caller")
    (is (= #{} (client/live-root-ids))
        "the exact claim is released despite the throw (finally-shaped)")
    (is (nil? (client/unmount!* root))
        "a second unmount!* is a no-op — the claim is already gone")))

(deftest unmount-throw-release-is-identity-guarded
  ;; a STALE handle whose root-id now maps to a NEWER root must not evict
  ;; the newer claim, even on the throwing-teardown path (the `when` guard
  ;; short-circuits before `.unmount` is ever called).
  (let [c        (js-obj)
        newer    (fake-root :reg/id)
        stale    (root-with-throwing-unmount :reg/id c)]
    (client/register-live-root! {:root-id :reg/id :provenance :authored} c newer)
    (is (nil? (client/unmount!* stale))
        "a stale handle is a no-op — its throwing .unmount is never reached")
    (is (= #{:reg/id} (client/live-root-ids))
        "the newer claim survives")
    (is (identical? newer (:root (client/live-root-entry :reg/id))))))

;; ---------------------------------------------------------------------------
;; React root options
;; ---------------------------------------------------------------------------

(deftest root-options-shape
  (let [cb (fn [_])
        o  (client/root-options "rf2-page-shop-" cb nil nil)]
    (is (= "rf2-page-shop-" (unchecked-get o "identifierPrefix")))
    (is (identical? cb (unchecked-get o "onUncaughtError")))
    (is (= ["identifierPrefix" "onUncaughtError"]
           (vec (js/Object.keys o)))
        "absent callbacks leave no keys behind")))

;; ---------------------------------------------------------------------------
;; The preflight seam
;; ---------------------------------------------------------------------------

(deftest preflight-hook-seam
  ;; S2c: preflight is LIVE (the default runs re-frame.ui.frames'
  ;; execute-frame-plans!). This seam test drives the test/tool OVERRIDE
  ;; hook — a capture consumer that observes plan threading WITHOUT
  ;; touching the frames registry (so it needs no adapter). The live ENSURE
  ;; path (install + drain + conflict + non-reseed) is pinned by the
  ;; preflight-frame-wiring DOM fixtures (G-4/G-6).
  (let [seen (atom nil)
        evals (atom 0)
        plans-thunk (fn [] (swap! evals inc)
                      [{:frame-id :shop :config-fingerprint "cf1-x"
                        :config {:n 1}}])]
    (testing "an installed override hook receives (root-id plans)"
      (let [hook (fn [rid plans] (reset! seen [rid plans]))
            prev (client/set-preflight-hook! hook)]
        (is (nil? prev))
        (client/run-preflight! :page/shop plans-thunk)
        (is (= 1 @evals) "config expressions evaluate exactly at preflight")
        (is (= [:page/shop
                [{:frame-id :shop :config-fingerprint "cf1-x" :config {:n 1}}]]
               @seen)
            "the hook sees the arriving root-id + evaluated plans")))
    (testing "a nil plans-thunk (no static plans) is a preflight no-op"
      (reset! seen ::untouched)
      (client/run-preflight! :page/shop nil)
      (is (= ::untouched @seen) "the hook is not called")
      (is (= 1 @evals) "the plans-thunk is never evaluated"))
    (is (fn? (client/set-preflight-hook! nil)))))

;; ---------------------------------------------------------------------------
;; S1 hydrate: fail loud, never guess identity
;; ---------------------------------------------------------------------------

(deftest hydrate-fails-loud-at-s1
  (let [{:keys [id msg data]}
        (thrown-error #(client/hydrate-root* (js-obj) (fn [] nil) nil (js-obj)))]
    (is (= :rf.error/root-manifest-invalid id))
    (is (= :manifest (:missing data))
        "the data map names what is missing, contract-style")
    (is (error/message-has-id-token? msg))))
