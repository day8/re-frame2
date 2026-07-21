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
            [re-frame.registrar :as registrar]
            [re-frame.ui.client :as client]
            [re-frame.ui.reactive :as reactive]
            [re-frame.ui.runtime :as runtime]))

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

(deftest unmount-quarantines-claim-when-host-teardown-throws
  ;; rf2-vxgfnd.275 — a throwing host `.unmount` cannot PROVE the container free,
  ;; so the framework FAILS CLOSED: the exact claim is left QUARANTINED
  ;; `:tearing-down` (NOT released — reversing the earlier .18 AC4 immediate
  ;; release, which could be clobbered by React self-completing the aborted
  ;; teardown). The host error still propagates and a second unmount!* is a no-op.
  (let [c    (js-obj)
        root (root-with-throwing-unmount :reg/boom c)]
    (client/register-live-root! {:root-id :reg/boom :provenance :authored} c root)
    (is (= #{:reg/boom} (client/live-root-ids)))
    (is (thrown-with-msg? js/Error #"host teardown boom"
                          (client/unmount!* root))
        "the host teardown error propagates to the caller")
    (is (= #{:reg/boom} (client/live-root-ids))
        "the exact claim is QUARANTINED :tearing-down, not released")
    (is (:tearing-down? (client/live-root-entry :reg/boom))
        "…the entry is marked tearing-down (reuse fails loud; recover via a fresh container)")
    (is (nil? (client/unmount!* root))
        "a second unmount!* is a no-op — the tearing-down guard short-circuits")))

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

;; The RUNTIME half of the Root lifecycle drift gate (rf2-vizyct) — asserting
;; that each of the three claim/render diagnostics EMITS `:existing {…
;; :tearing-down? true}` in its tearing-down arm — lives in
;; `re-frame.ui.root-teardown-wiring-cljs-test`
;; (`tearing-down-root-diagnostics-carry-complete-ownership-evidence`), which
;; pins the full ex-data shape (key-set + message) for all three ids off one
;; torn-down root. That is the implementation-side counterpart to the spec-009
;; `:tearing-down? true` row-scoped drift teeth: strip the evidence from either
;; the runtime emitters or the spec rows and a gate turns red.

(deftest adapter-disposal-snapshots-one-root-generation-and-never-chases-a-replacement
  (let [c      (js-obj)
        old    (fake-root :reg/generation)
        newer  (fake-root :reg/generation)
        calls  (atom [])
        blocked (volatile! nil)]
    (client/register-live-root!
     {:root-id :reg/generation :provenance :authored} c old)
    (with-redefs [client/unmount!*
                  (fn [root]
                    (swap! calls conj root)
                    (client/release-root! :reg/generation root)
                    ;; Public creation is forbidden during this exact disposal
                    ;; generation, before React/createRoot could run.
                    (vreset! blocked
                             (thrown-error
                              #(client/create-root*
                                {:root-id :reg/public-late
                                 :provenance :authored}
                                (js-obj) nil)))
                    ;; Model an internal/test bypass installing a later same-id
                    ;; incarnation. Stale disposal must not refresh its snapshot
                    ;; and kill ownership it never acquired.
                    (client/register-live-root!
                     {:root-id :reg/generation :provenance :authored} c newer))]
      (client/dispose-live-roots!))
    (is (= [old] @calls) "only the entry snapshot at disposal start is visited")
    (is (= :rf.error/adapter-disposed (:id @blocked))
        "public root creation fails before allocation while teardown is active")
    (is (identical? newer (:root (client/live-root-entry :reg/generation)))
        "the later same-id incarnation survives stale disposal")))

(deftest adapter-disposal-retains-every-root-cleanup-failure
  (let [ca     (js-obj)
        cb     (js-obj)
        ea     (js/Error. "dispose a failed")
        eb     (js/Error. "dispose b failed")
        a      (client/->Root #js {:unmount (fn [] (throw ea))}
                                 ca :reg/dispose-a)
        b      (client/->Root #js {:unmount (fn [] (throw eb))}
                                 cb :reg/dispose-b)]
    (set! (.-innerHTML ca) "<span>stale-a</span>")
    (set! (.-innerHTML cb) "<span>stale-b</span>")
    (unchecked-set ca "__reactContainer$fixture" #js {:old-root true})
    (unchecked-set cb "__reactContainer$fixture" #js {:old-root true})
    (client/register-live-root! {:root-id :reg/dispose-a :provenance :authored} ca a)
    (client/register-live-root! {:root-id :reg/dispose-b :provenance :authored} cb b)
    (let [caught
          (try (client/dispose-live-roots!) nil
               (catch :default e e))
          diagnostics (.-rfUiAdapterCleanupErrors caught)
          retained    (if diagnostics (vec (array-seq diagnostics)) [])]
      (is (contains? #{ea eb} caught) "one cleanup error remains primary")
      (is (some? diagnostics) "secondary failures are attached, not swallowed")
      (is (= #{ea eb} (conj (set retained) caught))
          "the sibling cleanup error remains attached as diagnostic evidence")
      (is (= #{} (client/live-root-ids))
          "both exact claims were still released")
      (is (= ["" ""] [(.-innerHTML ca) (.-innerHTML cb)])
          "adapter failure fallback empties every failed root container")
      (is (nil? (unchecked-get ca "__reactContainer$fixture"))
          "the consumed React container marker is cleared as a SNAPSHOT — the exact node stays fail-closed, reuse is terminally denied")
      (is (nil? (unchecked-get cb "__reactContainer$fixture")))
      ;; rf2-fjti6 — clearing DOM + marker is a snapshot, NOT proof the surface is
      ;; free: each exact node is recorded fail-closed and terminally denied.
      (is (true? (client/container-consumed? ca))
          "the exact reclaimed node is denied (terminally fail-closed, never same-container re-init)")
      (is (true? (client/container-consumed? cb))))))

(defn- root-with-consuming-unmount-throws-once
  "A Root whose host react-root models the REAL react-dom 19.2.0
  `ReactDOMRoot.unmount()` shape (rf2-vxgfnd.84): the handle is CONSUMED — its
  internal root is nulled BEFORE the flush that throws. So the FIRST `.unmount`
  throws (React scheduled the teardown, then the synchronous flush refused from
  inside a render pass), and EVERY later `.unmount` is a SILENT NO-OP: the
  internal root is already null, so React early-returns without tearing anything
  down. This is the fidelity fix — the pre-.84 fake had the second call SUCCEED,
  which real react-dom never does (a consumed handle cannot re-drive teardown).
  `calls` counts only REAL teardown attempts (the throwing one); the no-op
  early-return does NOT increment it."
  [root-id container calls]
  (let [rr        (js-obj)
        consumed? (atom false)]
    (unchecked-set rr "unmount"
                   (fn []
                     (if @consumed?
                       ;; internal root already null → React no-ops (no teardown)
                       js/undefined
                       (do
                         (reset! consumed? true)   ;; null the internal root FIRST
                         (swap! calls inc)
                         (throw (js/Error. "unmount during render boom"))))))
    (client/->Root rr container root-id)))

(deftest unmount-throw-aftermath-warns-honestly-there-is-no-manual-escape
  ;; rf2-vxgfnd.53/.84 diagnostic honesty, now FAIL-CLOSED per rf2-vxgfnd.275.
  ;; The handle is CONSUMED — ReactDOMRoot.unmount() nulls its internal root
  ;; BEFORE the flush that throws (pinned react-dom 19.2.0), so (.unmount
  ;; (.-react-root root)) again is a SILENT NO-OP: no manual escape. rf2-vxgfnd.275
  ;; REVERSES the .18 AC4 release-on-throw: because the container cannot be proven
  ;; free, the claim is QUARANTINED `:tearing-down` (not released), so a reused id
  ;; or container fails loud instead of racing React's aborted teardown; recovery
  ;; is a FRESH container. The host error still propagates and a second unmount!*
  ;; is a no-op (the tearing-down guard).
  (let [c        (js-obj)
        calls    (atom 0)
        root     (root-with-consuming-unmount-throws-once :reg/orphan c calls)
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
    (is (= #{:reg/orphan} (client/live-root-ids))
        "the exact claim is QUARANTINED :tearing-down, not released (rf2-vxgfnd.275)")
    (is (:tearing-down? (client/live-root-entry :reg/orphan))
        "…the entry is marked tearing-down — reuse fails loud, recover via a fresh container")
    (is (nil? (client/unmount!* root))
        "a second unmount!* is a no-op — the tearing-down guard short-circuits")
    (is (= 1 @calls)
        "the no-op second unmount!* never reaches React's .unmount again")
    ;; (2) the aftermath is NON-SILENT AND HONEST — the diagnostic states the
    ;; handle is consumed (no manual escape), that the claim is quarantined rather
    ;; than released, and names the real recovery (a fresh container); it does NOT
    ;; recommend the (no-op) (.unmount (.-react-root root)).
    (let [msg (str/lower-case (str/join "\n" @captured))]
      (is (seq @captured) "a dev diagnostic fired on the throwing unmount")
      (is (not (str/includes? msg "(.unmount (.-react-root root))"))
          "the diagnostic NO LONGER recommends the consumed-handle no-op escape")
      (is (str/includes? msg "no-op")
          "the diagnostic states re-calling the consumed handle is a no-op")
      (is (str/includes? msg "consumed")
          "the diagnostic names the CONSUMED handle as the reason there is no escape")
      (is (str/includes? msg "quarantin")
          "the diagnostic states the claim is quarantined, not released (fail closed)")
      (is (str/includes? msg "fresh container")
          "the diagnostic names the real recovery — mount into a fresh container"))
    ;; (3) FIDELITY: the handle is CONSUMED — calling .unmount on the SAME handle
    ;; again is a SILENT NO-OP (this._internalRoot was nulled BEFORE the throw),
    ;; NOT a successful teardown. The pre-.84 fake modelled a second-call SUCCESS,
    ;; which real react-dom never does — that false model was the only thing that
    ;; made the old "manual escape works" assertion pass.
    (is (nil? (.unmount (.-react-root root)))
        "the consumed handle's .unmount returns without effect")
    (is (= 1 @calls)
        "the re-called .unmount is a NO-OP — it never re-drives a real teardown")))

(deftest reclaim-installed-successor-keeps-reporter-authority-and-claim
  ;; rf2-j7225 — `reclaim-consumed-container!` runs SYNCHRONOUS host code
  ;; (`replaceChildren`), and a custom-element callback or a low-level/test seam
  ;; can install a same-id SUCCESSOR (B) into `live-roots` DURING that reclaim.
  ;; Recovery must retire the reporter authority of the EXACT captured predecessor
  ;; (A) — never a fresh `(root-incarnation-of root-id)` read, which now resolves
  ;; to B: retiring B leaves it LIVE WITHOUT reporter authority (its teardown could
  ;; settle before React's cleanup) while `release-root!`'s identity fence
  ;; correctly PRESERVES B's claim, and A's dead token stays strongly retained.
  ;; A bypass-installed replacement is not owned by the stale disposal snapshot.
  (let [rid       :reg/reclaim-successor
        container (js-obj)
        a-inc     (reactive/make-root-incarnation)
        a-boom    (js/Error. "predecessor host unmount boom")
        a-root    (client/->Root #js {:unmount (fn [] (throw a-boom))} container rid)
        succ-root (volatile! nil)
        succ-inc  (volatile! nil)]
    ;; Predecessor A: a committed root (reporter-live), NOT tearing-down, so the
    ;; drain visits it on the `:else`/unmount!* recovery arm.
    (client/register-live-root!
     {:root-id rid :provenance :authored} container a-root a-inc)
    (reactive/report-root-commit! a-inc)
    (is (true? (reactive/live-reporter? a-inc))
        "the committed predecessor holds live reporter authority")
    ;; The reclaim seam installs + commits a same-id successor B synchronously,
    ;; the moment `reclaim-consumed-container!` calls `replaceChildren`.
    (unchecked-set
     container "replaceChildren"
     (fn []
       (let [b-inc  (reactive/make-root-incarnation)
             b-root (client/->Root #js {:unmount (fn [] nil)} container rid)]
         (vreset! succ-root b-root)
         (vreset! succ-inc b-inc)
         (client/register-live-root!
          {:root-id rid :provenance :authored} container b-root b-inc)
         (reactive/report-root-commit! b-inc))))
    ;; Drain: A is not pre-quarantined, so unmount!* A throws (its host `.unmount`),
    ;; the `:else` recovery reclaims (installing B), then retires + releases.
    (is (identical? a-boom
                    (try (client/dispose-live-roots!) nil (catch :default e e)))
        "the predecessor host teardown error still propagates as primary")
    ;; B was installed DURING the reclaim — a DISTINCT incarnation…
    (is (some? @succ-inc))
    (is (not (identical? a-inc @succ-inc))
        "the same-id successor is a distinct incarnation")
    ;; …and its claim survives release-root!'s identity fence (true both before and
    ;; after the fix — the fence was never the bug).
    (is (contains? (client/live-root-ids) rid)
        "the bypass-installed successor's live claim survives")
    (is (identical? @succ-root (:root (client/live-root-entry rid)))
        "the surviving claim is the successor B, not the failed predecessor A")
    ;; The DISCRIMINATING assertions — RED before rf2-j7225 (the bare-id lookup
    ;; retired B and stranded A), GREEN after (the captured token retires A only):
    (is (false? (reactive/live-reporter? a-inc))
        "the failed predecessor's reporter is retired via the CAPTURED incarnation")
    (is (true? (reactive/live-reporter? @succ-inc))
        "the same-id successor keeps reporter authority — recovery never re-read the
         mutable registry after the synchronous reclaim")
    ;; Restore the reporter ledger to baseline regardless of outcome (idempotent).
    (reactive/retire-root-reporter! a-inc)
    (reactive/retire-root-reporter! @succ-inc)
    (client/release-root! rid @succ-root)))

;; ---------------------------------------------------------------------------
;; rf2-sddbc — a throwing/consumed host `.unmount` FAILS the EXACT container
;; closed. Adapter reclaim releases the id/prefix (a same-id re-mount on a FRESH
;; container is the ergonomic recovery), but the exact node is recorded fail-closed
;; because a queued host task (a scheduled `replaceChildren`) may not have settled —
;; clearing the DOM + React marker is a snapshot, NOT proof of settlement.
;; ---------------------------------------------------------------------------

(deftest adapter-reclaim-frees-the-id-but-fails-the-exact-container-closed
  (let [old   (js-obj)
        fresh (js-obj)
        boom  (js/Error. "queue-then-throw host cleanup")
        root  (client/->Root #js {:unmount (fn [] (throw boom))} old :reg/consumed)]
    (client/register-live-root!
     {:root-id :reg/consumed :provenance :authored
      :identifier-prefix "rf2-consumed-"}
     old root)
    (is (identical? boom (try (client/dispose-live-roots!) nil (catch :default e e)))
        "the throwing host cleanup error still propagates as primary")
    ;; the id/prefix are RELEASED — the registry is clean, NOT stranded :tearing-down
    (is (= #{} (client/live-root-ids))
        "adapter reclaim released the id/prefix — no permanent :tearing-down strand")
    (is (true? (client/container-consumed? old))
        "the exact consumed node is recorded fail-closed")
    (is (false? (client/container-consumed? fresh)))
    ;; exact-container reuse (ANY id) is fail-closed with the fresh-node recovery.
    ;; RED before rf2-sddbc: reclaim released the claim, so check-root-claim! ADMITTED.
    (let [{:keys [id data msg]}
          (thrown-error #(client/check-root-claim!
                          're-frame.ui/mount
                          {:root-id :reg/consumed :provenance :authored} old))]
      (is (= :rf.error/root-container-consumed id)
          "exact-container reuse fails closed — never admitted onto a poisoned node")
      (is (= :use-a-fresh-container (:recovery data)))
      (is (= :reg/consumed (:root-id data)))
      (is (error/message-has-id-token? msg)))
    ;; the ergonomic recovery — the SAME root-id on a FRESH container — is admitted
    (is (nil? (thrown-error #(client/check-root-claim!
                              're-frame.ui/mount
                              {:root-id :reg/consumed :provenance :authored} fresh)))
        "the same root-id re-mounts on a fresh container (the recovery path)")
    ;; an UNRELATED fresh id/container is never blocked (criterion 4)
    (is (nil? (thrown-error #(client/check-root-claim!
                              're-frame.ui/mount
                              {:root-id :reg/unrelated :provenance :authored} (js-obj))))
        "unrelated fresh roots admit — the quarantine fences only its exact node")))

(deftest isolated-throwing-unmount-consumes-container-and-id-message-is-honest
  ;; An ISOLATED unmount! whose host `.unmount` throws quarantines fail-closed
  ;; BEFORE any adapter destroy: the exact container reads consumed (via the
  ;; unreleased cleanup-failure owner), and reusing the SAME root-id reports
  ;; HONESTLY — no "re-mount after settlement" (there is no settlement signal).
  ;; rf2-h05lm — the structured ex-data is exact for every retry shape: recovery
  ;; and owner/existing evidence, not only the error id + message.
  (let [c    (js-obj)
        root (root-with-throwing-unmount :reg/iso c)]
    (client/register-live-root! {:root-id :reg/iso :provenance :authored} c root)
    (is (thrown-with-msg? js/Error #"host teardown boom" (client/unmount!* root)))
    (is (:cleanup-failure? (client/live-root-entry :reg/iso))
        "the isolated throwing unmount is a cleanup-failure quarantine")
    ;; (a) DIFFERENT id + the exact poisoned node → consumed (owner named)
    (let [{:keys [id data]}
          (thrown-error #(client/check-root-claim!
                          're-frame.ui/mount
                          {:root-id :reg/other :provenance :authored} c))]
      (is (= :rf.error/root-container-consumed id))
      (is (= :reg/iso (:owner-root-id data)))
      (is (= :use-a-fresh-container (:recovery data))))
    ;; (b) SAME id + the EXACT poisoned node → the poisoned node is reported (terminal
    ;; consumed-container condition), NOT hidden behind duplicate-ID ordering.
    ;; rf2-h05lm RED-BEFORE: this returned :rf.error/duplicate-root-id + :make-root-ids-unique.
    (let [{:keys [id data msg]}
          (thrown-error #(client/check-root-claim!
                          're-frame.ui/mount
                          {:root-id :reg/iso :provenance :authored} c))]
      (is (= :rf.error/root-container-consumed id)
          "same-id retry onto the exact poisoned node reports the consumed node, not a duplicate id")
      (is (= :use-a-fresh-container (:recovery data)))
      (is (= :reg/iso (:owner-root-id data)) "owner evidence names the cleanup-failure quarantine")
      (is (error/message-has-id-token? msg)))
    ;; (c) SAME id + a FRESH node → still a duplicate-ID refusal, but the ex-data is
    ;; structurally exact: :cleanup-failure? rides :existing and the recovery names the
    ;; real choices (adapter re-init, or a distinct id + fresh node) — NOT a settlement.
    ;; rf2-h05lm RED-BEFORE: recovery was :make-root-ids-unique and :existing omitted :cleanup-failure?.
    (let [{:keys [id data msg]}
          (thrown-error #(client/check-root-claim!
                          're-frame.ui/mount
                          {:root-id :reg/iso :provenance :authored} (js-obj)))]
      (is (= :rf.error/duplicate-root-id id))
      (is (= :reinit-adapter-or-use-a-fresh-identity (:recovery data))
          "the cleanup-failure duplicate recovery names adapter re-init / a fresh identity, not a settlement")
      (is (true? (get-in data [:existing :cleanup-failure?]))
          "structured consumers can recover the terminal cleanup-failure distinction")
      (is (true? (get-in data [:existing :tearing-down?])))
      (is (not (re-find #"after settlement" msg))
          "the cleanup-failure id message does NOT promise a settlement that never comes")
      (is (re-find #"CONSUMED|fresh container|destroyed and reinstalled" msg)
          "…it directs to a fresh container / adapter re-init instead"))))

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

;; ---------------------------------------------------------------------------
;; Root Descriptor v1 — the client read surface. Spec 004C §2: the per-root
;; static descriptor rides the live-root entry's :descriptor; `client/descriptor`
;; / `descriptor-index` read it back. It carries no whole-build aggregate.
;; ---------------------------------------------------------------------------

(defn- reg-view! [id tf hs]
  (runtime/register-view! id
                          (fn [_props] nil)
                          (fn [_prev _next] true)
                          (str id)
                          {:view-id id
                           :template-fingerprint tf
                           :hook-signature hs}))

(deftest client-descriptor-reads-the-static-core
  (reg-view! ::a "tf1-aaaaaaaaaaaaaaaa" "hs1-0000000000000000")
  (reg-view! ::b "tf1-bbbbbbbbbbbbbbbb" "hs1-0000000000000000")
  (try
    (let [core {:rf.root/schema-version 1 :root-id :page/shop :view-id ::a
                :props-shape :dynamic}]
      (client/register-live-root!
       {:root-id :page/shop :provenance :authored :descriptor core}
       (js-obj) (fake-root :page/shop))
      (testing "the entry carries NO :build-digest (§2)"
        (is (not (contains? (:descriptor (client/live-root-entry :page/shop))
                            :build-digest))))
      (testing "client/descriptor returns the per-root static core verbatim"
        (is (= core (client/descriptor :page/shop))
            "the descriptor is the stored static core; no whole-build aggregate is added"))
      (testing "descriptor-index returns each live root's static descriptor"
        (let [idx (client/descriptor-index)]
          (is (= core (get idx :page/shop))))))
    (finally
      (registrar/unregister! :view ::a)
      (registrar/unregister! :view ::b))))

(deftest no-pass-repl-registration-does-not-change-the-static-core
  ;; Option C: direct REPL evaluation may replace the live view body, but the
  ;; stored per-root static descriptor is untouched — only the next successful
  ;; configured file/watch pass re-derives descriptors.
  (reg-view! ::v "tf1-1111111111111111" "hs1-0000000000000000")
  (try
    (let [core {:rf.root/schema-version 1 :root-id :page/v :view-id ::v}]
      (client/register-live-root!
       {:root-id :page/v :provenance :authored :descriptor core}
       (js-obj) (fake-root :page/v))
      ;; The REPL re-evaluates the view only; the mount site does not run.
      (reg-view! ::v "tf1-2222222222222222" "hs1-0000000000000000")
      (is (= core (:descriptor (client/live-root-entry :page/v)))
          "the mount site never re-expanded — the static core is untouched"))
    (finally
      (registrar/unregister! :view ::v))))
