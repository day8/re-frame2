(ns re-frame.ui.root-registry-cljs-test
  "S1c Layer-3 live-root registry semantics (rf2-vxgfnd.3), node-runtime:
  the claim checks (duplicate root-id / container ownership / missing
  container), registration + release, the react-root options builder, the
  preflight seam, and the S1 hydrate fail-loud. No DOM — containers are
  plain JS objects (ownership is identity-based); the full React mount
  path is the browser smoke (`re-frame.ui.root-mount-dom-cljs-test`)."
  (:require [clojure.string :as str]
            [cljs.test :refer [deftest is testing use-fixtures]]
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
;; identifier-prefix uniqueness (rf2-ez3fqk)
;; ---------------------------------------------------------------------------

(deftest shared-identifier-prefix-across-distinct-roots-fails-loud
  ;; two DISTINCT roots (distinct root-ids, distinct containers) that author
  ;; the SAME :identifier-prefix would alias React's use-id output. The
  ;; derived default is injective over root-id (rf2-vxgfnd.17), so this
  ;; backstops AUTHORED prefixes; the check is registry-level (claim time).
  (let [c1 (js-obj) c2 (js-obj)
        info-a {:root-id :page/a :provenance :authored :identifier-prefix "app-"}]
    (client/check-root-claim! 're-frame.ui/mount info-a c1)
    (client/register-live-root! info-a c1 (fake-root :page/a))
    (let [{:keys [id data msg]}
          (thrown-error
           #(client/check-root-claim! 're-frame.ui/mount
                                      {:root-id :page/b :provenance :authored
                                       :identifier-prefix "app-"}
                                      c2))]
      (is (= :rf.error/duplicate-identifier-prefix id))
      (is (= :page/a (:owner-root-id data)) "the data map names the owning root")
      (is (= :page/b (:root-id data)))
      (is (= "app-" (:identifier-prefix data)))
      (is (error/message-has-id-token? msg))
      (is (= #{:page/a} (client/live-root-ids))
          "the existing root is untouched (failure isolation)"))))

(deftest release-frees-the-identifier-prefix
  ;; unregistering the owner frees its prefix — a different root may then
  ;; claim it (release rides the same registry entry, no side index).
  (let [c1 (js-obj) c2 (js-obj)
        info-a {:root-id :page/a :provenance :authored :identifier-prefix "app-"}
        r-a (fake-root :page/a)]
    (client/register-live-root! info-a c1 r-a)
    (client/release-root! :page/a r-a)
    (is (nil? (thrown-error
               #(client/check-root-claim! 're-frame.ui/mount
                                          {:root-id :page/b :provenance :authored
                                           :identifier-prefix "app-"}
                                          c2)))
        "the prefix is free once its owner is released")))

(deftest distinct-prefixes-and-absent-prefixes-do-not-alias
  ;; distinct effective prefixes coexist; and entries without an effective
  ;; prefix (bare infos — the derived-default path always supplies one, but
  ;; the registry helpers accept bare infos) never trip the check.
  (let [c1 (js-obj) c2 (js-obj) c3 (js-obj)]
    (client/register-live-root! {:root-id :page/a :provenance :authored
                                 :identifier-prefix "a-"}
                                c1 (fake-root :page/a))
    (is (nil? (thrown-error
               #(client/check-root-claim! 're-frame.ui/mount
                                          {:root-id :page/b :provenance :authored
                                           :identifier-prefix "b-"}
                                          c2)))
        "distinct prefixes are fine")
    ;; a bare info (no :identifier-prefix) against a prefixed live root — the
    ;; arm is a no-op, so only the root-id/container arms can fire (neither
    ;; does here: distinct id, distinct container)
    (is (nil? (thrown-error
               #(client/check-root-claim! 're-frame.ui/mount
                                          {:root-id :page/c :provenance :authored}
                                          c3)))
        "an absent effective prefix never aliases")))

;; ---------------------------------------------------------------------------
;; same-root re-mount: the identifier-prefix is immutable (rf2-vxgfnd.59)
;; ---------------------------------------------------------------------------

(deftest same-root-remount-with-changed-prefix-fails-loud
  ;; the idempotent same-root/same-container fast path COMPARES the requested
  ;; effective identifierPrefix against the live root's. A live root's prefix
  ;; is fixed at createRoot (React root options are immutable), so an HMR
  ;; re-mount that authored a DIFFERENT prefix cannot be applied — it fails
  ;; loud BEFORE preflight rather than silently reusing the old option.
  (let [c      (js-obj)
        a      (fake-root :page/x)
        info-a {:root-id :page/x :provenance :authored :identifier-prefix "rf2-a-"}]
    (client/register-live-root! info-a c a)
    (let [preflight-calls (atom 0)]
      (client/set-preflight-hook! (fn [_ _] (swap! preflight-calls inc)))
      (try
        (let [{:keys [id data msg]}
              (thrown-error
               #(client/mount*
                 {:root-id :page/x :provenance :authored :identifier-prefix "rf2-b-"}
                 c (fn [] nil) (js-obj)
                 (fn [] [{:frame-id :page/session :config-fingerprint "cf" :config {}}])))]
          (is (= :rf.error/root-identifier-prefix-immutable id))
          (is (= :page/x (:root-id data)))
          (is (= "rf2-b-" (:requested data)) "the data names the requested prefix")
          (is (= "rf2-a-" (:existing data)) "and the live root's current prefix")
          (is (error/message-has-id-token? msg)))
        (is (zero? @preflight-calls)
            "the guard throws BEFORE preflight — no :initial-events drain")
        (is (identical? a (:root (client/live-root-entry :page/x)))
            "the existing root is untouched — same Root")
        (is (= "rf2-a-" (:identifier-prefix (client/live-root-entry :page/x)))
            "and still carries its original prefix")
        (finally (client/set-preflight-hook! nil))))))

(deftest same-root-remount-with-unchanged-prefix-is-idempotent
  ;; the common reload path: same root-id + same container + same effective
  ;; prefix re-runs preflight and re-renders the existing Root (no prefix
  ;; throw). A counting fake react-root proves the fast path was taken.
  (let [c        (js-obj)
        rendered (atom 0)
        rr       (js-obj)
        _        (unchecked-set rr "render" (fn [_] (swap! rendered inc)))
        a        (client/->Root rr c :page/x)
        info     {:root-id :page/x :provenance :authored :identifier-prefix "rf2-a-"}
        plans    (fn [] [{:frame-id :page/session :config-fingerprint "cf" :config {}}])]
    (client/register-live-root! info c a)
    (let [preflight-calls (atom 0)]
      (client/set-preflight-hook! (fn [_ _] (swap! preflight-calls inc)))
      (try
        (let [ret (client/mount* info c (fn [] nil) (js-obj) plans)]
          (is (identical? a ret) "the existing Root is returned (fast path)")
          (is (= 1 @preflight-calls) "preflight ran once")
          (is (= 1 @rendered) "the existing Root re-rendered"))
        (finally (client/set-preflight-hook! nil))))))

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

;; ---------------------------------------------------------------------------
;; post-preflight ownership revalidation (rf2-vxgfnd.69)
;; ---------------------------------------------------------------------------

(deftest mount-fast-path-revalidates-after-preflight
  ;; the same-root/same-container fast path runs side-effecting preflight
  ;; (:initial-events drain) on a CAPTURED Root, then re-renders. A preflight
  ;; that supersedes the captured root (mounts a replacement B under the same
  ;; id/container) must be caught BEFORE the stale .render — the fast path
  ;; revalidates ownership and fails loud, never .rendering the stale handle.
  (let [c        (js-obj)
        rendered (atom 0)
        rr-a     (js-obj)
        _        (unchecked-set rr-a "render" (fn [_] (swap! rendered inc)))
        a        (client/->Root rr-a c :re/z)
        b        (fake-root :re/z)
        info     {:root-id :re/z :provenance :authored :identifier-prefix "p-"}
        plans    (fn [] [{:frame-id :re/session :config-fingerprint "cf" :config {}}])]
    (client/register-live-root! info c a)
    ;; the preflight drain stands in for :initial-events app code that
    ;; supersedes A with a replacement B under the SAME id/container (same
    ;; prefix, so the .59 fast-path prefix guard passes cleanly)
    (client/set-preflight-hook!
     (fn [_ _]
       (client/set-preflight-hook! nil)
       (client/register-live-root! {:root-id :re/z :provenance :authored
                                    :identifier-prefix "p-"} c b)))
    (try
      (let [{:keys [id data]}
            (thrown-error #(client/mount* info c (fn [] nil) (js-obj) plans))]
        (is (= :rf.error/root-not-live id)
            "the captured root A was superseded during preflight — fail loud")
        (is (= :re/z (:root-id data)))
        (is (zero? @rendered)
            "A.render is never called — no write into the superseded root")
        (is (identical? b (:root (client/live-root-entry :re/z)))
            "the replacement B survives untouched"))
      (finally (client/set-preflight-hook! nil)))))

(deftest render-revalidates-ownership-after-preflight
  ;; render!* runs its pre-preflight stale guard, then side-effecting
  ;; preflight, then (rf2-vxgfnd.69) REVALIDATES before the descriptor write
  ;; and .render. A preflight that supersedes the captured root fails loud —
  ;; no .render on the stale handle, and B's registry descriptor is untouched
  ;; (the swap is identity-guarded to the exact Root, never merely the id).
  (let [c         (js-obj)
        rendered  (atom 0)
        rr-a      (js-obj)
        _         (unchecked-set rr-a "render" (fn [_] (swap! rendered inc)))
        a         (client/->Root rr-a c :re/w)
        b         (fake-root :re/w)
        info      {:root-id :re/w :provenance :authored :identifier-prefix "p-"}
        plans     (fn [] [{:frame-id :re/session :config-fingerprint "cf" :config {}}])
        desc-base {:rf.root/schema-version 1 :view-id ::stale-a}]
    (client/register-live-root! info c a)
    (client/set-preflight-hook!
     (fn [_ _]
       (client/set-preflight-hook! nil)
       ;; B replaces A mid-preflight, carrying its OWN descriptor
       (client/register-live-root! {:root-id :re/w :provenance :authored
                                    :identifier-prefix "p-"
                                    :descriptor {:root-id :re/w :view-id ::real-b}}
                                   c b)))
    (try
      (let [{:keys [id data]}
            (thrown-error #(client/render!* a (fn [] nil) plans desc-base))]
        (is (= :rf.error/root-not-live id)
            "the captured root A was superseded during preflight — fail loud")
        (is (= :re/w (:root-id data)))
        (is (zero? @rendered)
            "A.render is never called — no write into the superseded root")
        (is (identical? b (:root (client/live-root-entry :re/w)))
            "the replacement B survives")
        (is (= ::real-b (get-in (client/live-root-entry :re/w) [:descriptor :view-id]))
            "B's descriptor is untouched — A's descriptor-base is never written onto the id"))
      (finally (client/set-preflight-hook! nil)))))

(deftest render-happy-path-revalidation-does-not-overfire
  ;; the matching case: no preflight movement — render!* revalidates (passes),
  ;; runs preflight exactly once, and re-renders the captured Root. Proves the
  ;; post-preflight revalidation does not over-fire on the normal path.
  (let [c        (js-obj)
        rendered (atom 0)
        rr       (js-obj)
        _        (unchecked-set rr "render" (fn [_] (swap! rendered inc)))
        a        (client/->Root rr c :re/ok)
        info     {:root-id :re/ok :provenance :authored :identifier-prefix "p-"}
        plans    (fn [] [{:frame-id :re/session :config-fingerprint "cf" :config {}}])]
    (client/register-live-root! info c a)
    (let [preflight-calls (atom 0)]
      (client/set-preflight-hook! (fn [_ _] (swap! preflight-calls inc)))
      (try
        (let [ret (client/render!* a (fn [] nil) plans nil)]
          (is (identical? a ret) "the captured Root is returned")
          (is (= 1 @preflight-calls) "preflight ran once")
          (is (= 1 @rendered) "the Root re-rendered — revalidation passed"))
        (finally (client/set-preflight-hook! nil))))))

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

(defn- root-with-unmount-throws-once
  "A Root whose host react-root's `.unmount` THROWS on its first call and
  SUCCEEDS on every call after — models the concrete rf2-vxgfnd.53 case:
  React refuses a synchronous unmount from inside a render pass, but the
  SAME call succeeds once out of it. `calls` counts every `.unmount`."
  [root-id container calls]
  (let [rr (js-obj)]
    (unchecked-set rr "unmount"
                   (fn []
                     (when (= 1 (swap! calls inc))
                       (throw (js/Error. "unmount during render boom")))))
    (client/->Root rr container root-id)))

(deftest unmount-throw-aftermath-warns-and-names-the-escape
  ;; rf2-vxgfnd.53 — the UNCOVERED aftermath of .18 AC4's release-on-throw
  ;; (no test covered the React-didn't-unmount case). RULING (b): KEEP AC4
  ;; (release the claim on a throwing host .unmount; a second unmount!* is a
  ;; no-op; the host error propagates) — reversing it would contradict AC4's
  ;; dedicated `unmount-releases-claim-even-when-host-teardown-throws` test —
  ;; but make the ORPHANED-TREE aftermath non-silent with a dev diagnostic
  ;; that names the manual escape, and prove the escape recovers the
  ;; container so a subsequent mount does not double-root over a live tree.
  (let [c        (js-obj)
        calls    (atom 0)
        root     (root-with-unmount-throws-once :reg/orphan c calls)
        captured (atom [])
        orig     (.-warn js/console)]
    (client/register-live-root! {:root-id :reg/orphan :provenance :authored} c root)
    (is (= #{:reg/orphan} (client/live-root-ids)))
    ;; (1) AC4 preserved: the host error propagates, and a dev diagnostic
    ;; fires on the throwing teardown (captured around this one call).
    (try
      (set! (.-warn js/console)
            (fn [& args] (swap! captured conj (apply str args))))
      (is (thrown-with-msg? js/Error #"unmount during render boom"
                            (client/unmount!* root))
          "the host teardown error still propagates (.18 AC4)")
      (finally
        (set! (.-warn js/console) orig)))
    (is (= 1 @calls) "React's .unmount was reached exactly once (and it threw)")
    (is (= #{} (client/live-root-ids))
        "the exact claim is released despite the throw (.18 AC4, finally-shaped)")
    (is (nil? (client/unmount!* root))
        "a second unmount!* is a no-op — the framework cannot retry the host unmount")
    (is (= 1 @calls)
        "the no-op second unmount!* never reaches React's .unmount again")
    ;; (2) rf2-vxgfnd.53: the aftermath is NON-SILENT — one dev diagnostic
    ;; naming the manual escape VERBATIM + the double-root hazard.
    (let [msg (str/join "\n" @captured)]
      (is (seq @captured) "a dev diagnostic fired on the throwing unmount")
      (is (str/includes? msg "(.unmount (.-react-root root))")
          "the diagnostic names the manual escape verbatim")
      (is (str/includes? msg "double-root")
          "the diagnostic warns about the remount double-root hazard")
      (is (str/includes? msg "orphaned")
          "the diagnostic names the orphaned live tree"))
    ;; (3) the documented ESCAPE WORKS: calling .unmount on the SAME handle
    ;; now succeeds (out of the render pass), tearing the orphaned tree down.
    (is (nil? (.unmount (.-react-root root)))
        "the manual escape (.unmount (.-react-root root)) succeeds on retry")
    (is (= 2 @calls) "the escape actually invoked React's .unmount again")
    ;; (4) NO SILENT DOUBLE-ROOT: with the tree torn down (via the escape)
    ;; AND the claim already released, a subsequent create-root* / mount
    ;; re-claims the container cleanly — no createRoot over a live tree.
    (client/check-root-claim! 're-frame.ui/mount
                              {:root-id :reg/remount :provenance :authored} c)
    (client/register-live-root! {:root-id :reg/remount :provenance :authored}
                                c (fake-root :reg/remount))
    (is (= #{:reg/remount} (client/live-root-ids))
        "the container re-claims cleanly after the escape — no double-root")))

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
