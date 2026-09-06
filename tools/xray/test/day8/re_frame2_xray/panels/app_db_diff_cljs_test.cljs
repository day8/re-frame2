(ns day8.re-frame2-xray.panels.app-db-diff-cljs-test
  "CLJS-side wiring + view tests for Xray's app-db tab.

  ## rf2-okvit — current-state inspector

  The app-db tab was redesigned from a diff view into a CURRENT-STATE
  inspector (re-frame-10x style), sectioned by reserved `:rf/*` area.
  The view-render tests assert the inspector shape (TOP user-domain
  section, per-instance machine fan-out, singleton route; rf2-jcdvo
  — empty / absent reserved areas are filtered at projection time so
  no placeholder cards reach the renderer) via
  `app-db-diff-state/state-body` through the Panel.

  ## rf2-p53m2 — dead diff-sub family pruned

  The `:rf.xray/selected-epoch-diff` → `:rf.xray/app-db-diff` composite
  family (plus `:rf.xray/selected-epoch-redacted-modified-count` /
  `:rf.xray/selected-epoch-flow-writes` and their `[frame-id epoch-id]`
  caches) had NO production view consumer and was removed — the Epoch
  panel's `:db` diff reads `:rf.xray/selected-epoch-record` + runs its
  own `db-diff-paths`, and the MCP `get-app-db-diff` tool projects
  directly through `diff.engine/project`. The diff-projection
  correctness those subs exercised lives in the engine's own tests.
  This file's remaining tests pin the LIVE app-db-tab surface.

  ## Contracts under test (beyond the pure-data tests in
  `app_db_diff_helpers_cljs_test.cljc` / the view-shape tests in
  `app_db_diff_state_cljs_test.cljs`)

  1. **Registry wires the subs / events** under the `:rf.xray/*`
     namespace, including the `:rf.xray/app-db-state` +
     `:rf.xray/app-db-current+diff` subs (and the dead family stays
     gone).

  2. **Focus events update Xray's frame.** `:rf.xray/focus-slice-path`
     remains wired (the 'show me when this changed' walker dispatches it).

  3. **Current-state inspector view** renders the section model and
     follows the picker-selected frame.

  4. **Segment-inspector** events / state contract.

  ## Pure hiccup

  We walk the view's hiccup tree by `data-testid` rather than mounting
  to a DOM. Keeps the suite fast + host-portable on node-test."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.elision :as rf.elision]
            [re-frame.frame :as rf.frame]
            [re-frame.registrar :as rf.registrar]
            [day8.re-frame2-xray.egress :as egress]
            [day8.re-frame2-xray.registry :as registry]
            [day8.re-frame2-xray.test-support :as xray-test-support]
            [day8.re-frame2-xray.panels.app-db-diff :as app-db-diff]
            [day8.re-frame2-xray.panels.app-db-diff-state :as state]))

;; ---- fixtures -----------------------------------------------------------

(use-fixtures :each
  ;; `make-xray-runtime-fixture` (rf2-vj80u8) folds the bespoke `xray-init!`
  ;; into one owner: plain-atom adapter + the default `:all` reset tier,
  ;; which already includes the trace-collector ring reset the old init
  ;; called a SECOND, redundant time.
  (xray-test-support/make-xray-runtime-fixture))

;; ---- fixture data --------------------------------------------------------

(defn- mk-record
  "Build a minimal `:rf/epoch-record` map for the diff walker /
  selected-epoch-diff sub tests."
  [epoch-id event db-before db-after]
  {:epoch-id      epoch-id
   :frame         :rf/default
   :committed-at  0
   :event-id      (first event)
   :trigger-event event
   :db-before     db-before
   :db-after      db-after
   :trace-events  []})

(defn- register-seed-events!
  "Register test-only seed events that write directly to the Xray
  frame's app-db. Production code reaches the same shape via
  :rf.xray/epoch-recorded + the host frame's runtime mutations."
  []
  (rf/reg-event :rf.xray-test/seed-history
    (fn [{:keys [db]} [_ records]]
      {:db (assoc db :epoch-history (vec records))}))
  (rf/reg-event :rf.xray-test/seed-target-frame-db
    (fn [{:keys [db]} _]
      ;; This is a no-op marker — the host frame's db is the source
      ;; of truth, and we set it via replace-app-db! below. Kept so
      ;; tests can locate the seed step by name.
      {:db db})))

(defn- seed-host-frame!
  "Reset the host (:rf/default) frame's app-db to the supplied
  value via the framework's replace-app-db!. The Phase 5 panel
  derefs the host frame via :rf.xray/target-frame-db."
  [db-value]
  (rf/make-frame {:id :rf/default})
  (rf/replace-frame-state! :rf/default {:rf.db/app db-value}))

(defn- seed-host-runtime-db!
  "Install `runtime-db-value` into the host (:rf/default) frame's
  RUNTIME-DB partition (EP-0001 rf2-tj6w9l — the App-DB panel sources its
  reserved AREAS from the runtime-db partition via
  `:rf.xray/target-frame-runtime-db`). A framework-authority
  `reg-event` handler returning the reserved `:rf.db/runtime` effect installs
  the partition (the same path the machines / routing lifecycle-fx write);
  `:rf/machine? true` marks it framework-authority so the runtime-write
  diagnostic does not fire."
  [runtime-db-value]
  (rf/make-frame {:id :rf/default})
  (rf/reg-event :rf.xray-test/seed-runtime-db
    {:rf/machine? true}
    (fn [_ _] {:rf.db/runtime runtime-db-value}))
  (rf/with-frame :rf/default
    (rf/dispatch-sync [:rf.xray-test/seed-runtime-db])))

(defn- seed-xray!
  "Wire the Phase 5 sub graph + seed history + host-frame db. The
  test environment proxies the production wiring path (preload +
  registry + epoch-cb) so the subs read live values."
  [host-db-value history]
  (registry/register-xray-handlers!)
  (rf/make-frame {:id :rf/xray})
  (register-seed-events!)
  (seed-host-frame! host-db-value)
  ;; EP-0002 (rf2-bd4div) — the inspected target no longer defaults to
  ;; `:rf/default`; select it EXPLICITLY here. These panel tests use the
  ;; ordinary `:rf/default` frame as the host under inspection, so the
  ;; test pins it as the observed target (the gesture the frame picker /
  ;; mount discovery policy performs in production).
  (rf/with-frame :rf/xray
    (rf/dispatch-sync [:rf.xray/set-target-frame :rf/default]))
  (when (seq history)
    (rf/with-frame :rf/xray
      (rf/dispatch-sync [:rf.xray-test/seed-history history]))))

;; ---- hiccup walker (mirrors event_detail_cljs_test.cljs) ----------------

(defn- structural-component?
  "True when `node` is one of the App-DB Panel's OWN pure-hiccup
  structural fn-components — currently just `state/state-body`, the
  per-epoch keyed body wrapper the Panel mounts (rf2-yng0y). These
  realize to plain section `:div`s carrying the `data-testid` hooks the
  assertions below pin. Leaf widgets like the `edn-inspector` (which
  produce render-context-bound wrapper fns that are not ISeqable) are
  deliberately NOT structural — they stay un-expanded so the walker
  treats them as leaves, exactly as the pre-rf2-yng0y shallow walker
  did when `state-body` was called inline."
  [node]
  (and (vector? node) (= state/state-body (first node))))

(defn- expand-fn-component [node]
  (if (and (vector? node) (fn? (first node)))
    (apply (first node) (rest node))
    node))

(defn- hiccup-seq
  "Walk a (possibly component-wrapped) hiccup tree, expanding the
  Panel's own structural fn-components (see `structural-component?`) AS
  the walk descends so their nested section testids are realized.

  rf2-yng0y — the App-DB Panel now wraps its body as a KEYED component
  `^{:key epoch-id} [state/state-body model]` (to force a clean per-epoch
  React remount). The pre-rf2-yng0y walker expanded one component deep at
  each leaf, which sufficed when `state-body` was called inline; the new
  wrapper hides the section testids one level down. Expanding the
  structural wrapper during descent restores full visibility while
  leaving the leaf widgets (edn-inspector) un-expanded, as before."
  [tree]
  (let [descend (fn [node]
                  (seq (if (structural-component? node)
                         (expand-fn-component node)
                         node)))
        seed    (if (structural-component? tree)
                  (expand-fn-component tree)
                  tree)]
    (->> (tree-seq (some-fn vector? seq?) descend seed)
         (map expand-fn-component))))

(defn- find-by-testid [tree testid]
  (some (fn [node]
          (when (and (vector? node)
                     (map? (second node))
                     (= testid (:data-testid (second node))))
            node))
        (hiccup-seq tree)))

(defn- find-all-by-testid-prefix [tree prefix]
  (filter (fn [node]
            (and (vector? node)
                 (map? (second node))
                 (some-> (:data-testid (second node))
                         (.startsWith prefix))))
          (hiccup-seq tree)))

;; ---- (1) registry wires the subs / events -------------------------------

(deftest registry-installs-app-db-diff-subs
  (testing "register-xray-handlers! installs the Phase 5 subs.
            Pinned-slices subs were removed under rf2-e9tb0 (clickable
            path segments replaced the pinned-watches strip)."
    (registry/register-xray-handlers!)
    (is (some? (rf.registrar/handler :sub :rf.xray/target-frame-db)))
    (is (some? (rf.registrar/handler :sub :rf.xray/selected-epoch-record)))
    (is (some? (rf.registrar/handler :sub :rf.xray/focused-slice-path)))
    (is (some? (rf.registrar/handler :sub :rf.xray/show-me-when-this-changed-result)))
    ;; rf2-okvit — the current-state inspector's section-model sub.
    (is (some? (rf.registrar/handler :sub :rf.xray/app-db-state)))
    ;; rf2-yng0y — the atomic current-state + before-image sub the panel
    ;; pivots on (and the segment-inspector reads through, rf2-jmucu).
    (is (some? (rf.registrar/handler :sub :rf.xray/app-db-current+diff)))
    ;; rf2-e9tb0 — segment-inspector subs registered alongside.
    (is (some? (rf.registrar/handler :sub :rf.xray/segment-inspector-open?)))
    (is (some? (rf.registrar/handler :sub :rf.xray/segment-inspector-path)))
    (is (some? (rf.registrar/handler :sub :rf.xray/segment-inspector-value)))
    ;; rf2-p53m2 — the dead composite diff family was pruned (no
    ;; production view consumer); guard against re-introduction.
    (is (nil? (rf.registrar/handler :sub :rf.xray/selected-epoch-diff)))
    (is (nil? (rf.registrar/handler :sub :rf.xray/app-db-diff)))
    (is (nil? (rf.registrar/handler :sub :rf.xray/selected-epoch-redacted-modified-count)))
    (is (nil? (rf.registrar/handler :sub :rf.xray/selected-epoch-flow-writes)))
    ;; Pinned-slices subs are gone.
    (is (nil? (rf.registrar/handler :sub :rf.xray/pinned-slices-store)))
    (is (nil? (rf.registrar/handler :sub :rf.xray/pinned-slices)))))

(deftest registry-installs-app-db-diff-events
  (testing "register-xray-handlers! installs the Phase 5 events.
            Pin / unpin / reorder events were removed under rf2-e9tb0;
            the segment-inspector open / close events landed in their
            place. The two dispatcher-less clipboard copy events retired
            under rf2-6r9j.24 — the fx they rode survives, pinned below."
    (registry/register-xray-handlers!)
    (is (some? (rf.registrar/handler :event :rf.xray/focus-slice-path)))
    (is (some? (rf.registrar/handler :event :rf.xray/clear-slice-focus)))
    ;; rf2-e9tb0 — segment-inspector events registered.
    (is (some? (rf.registrar/handler :event :rf.xray/open-segment-inspector)))
    (is (some? (rf.registrar/handler :event :rf.xray/close-segment-inspector)))
    ;; Pin events are gone.
    (is (nil? (rf.registrar/handler :event :rf.xray/pin-slice)))
    (is (nil? (rf.registrar/handler :event :rf.xray/unpin-slice)))
    (is (nil? (rf.registrar/handler :event :rf.xray/reorder-pinned-slices)))))

(deftest registry-installs-clipboard-fx
  (testing "register-xray-handlers! installs the :rf.xray.fx/copy-to-
            clipboard effect"
    (registry/register-xray-handlers!)
    (is (some? (rf.registrar/handler :fx :rf.xray.fx/copy-to-clipboard)))))

;; ---- (5) rf2-e9tb0 — segment-inspector events + state ------------------

(deftest open-segment-inspector-writes-path-to-xray-frame
  (testing "rf2-e9tb0 — :rf.xray/open-segment-inspector writes the
            requested path into Xray's frame at :segment-inspector.
            The path is normalised to a vector so callers can pass
            seqs / lists / vectors interchangeably."
    (registry/register-xray-handlers!)
    (rf/make-frame {:id :rf/xray})
    (rf/make-frame {:id :rf/default})
    (rf/with-frame :rf/xray
      (rf/dispatch-sync [:rf.xray/open-segment-inspector
                         (list :cart :items 0 :price)])
      (is (= {:path [:cart :items 0 :price]}
             (:segment-inspector (rf.frame/frame-app-db-value :rf/xray)))))))

(deftest close-segment-inspector-drops-slot
  (testing "rf2-e9tb0 — :rf.xray/close-segment-inspector dissocs the
            slot so the popup reg-view's `when`-gate short-circuits"
    (registry/register-xray-handlers!)
    (rf/make-frame {:id :rf/xray})
    (rf/with-frame :rf/xray
      (rf/dispatch-sync [:rf.xray/open-segment-inspector [:cart]])
      (is (some? (:segment-inspector (rf.frame/frame-app-db-value :rf/xray))))
      (rf/dispatch-sync [:rf.xray/close-segment-inspector])
      (is (nil? (:segment-inspector (rf.frame/frame-app-db-value :rf/xray)))))))

(deftest segment-inspector-open?-tracks-slot-presence
  (testing "rf2-e9tb0 — :rf.xray/segment-inspector-open? is true iff
            the :segment-inspector slot is non-nil"
    (registry/register-xray-handlers!)
    (rf/make-frame {:id :rf/xray})
    (rf/with-frame :rf/xray
      (is (false? @(rf/subscribe [:rf.xray/segment-inspector-open?])))
      (rf/dispatch-sync [:rf.xray/open-segment-inspector [:cart :items]])
      (is (true? @(rf/subscribe [:rf.xray/segment-inspector-open?])))
      (rf/dispatch-sync [:rf.xray/close-segment-inspector])
      (is (false? @(rf/subscribe [:rf.xray/segment-inspector-open?]))))))

(deftest segment-inspector-value-resolves-against-focused-epoch-value
  (testing "rf2-e9tb0 / rf2-jmucu — :rf.xray/segment-inspector-value reads
            through :rf.xray/app-db-current+diff's `:value` (the focused
            epoch's `:db-after`, the same image the panel body shows) with
            `get-in`, so the popup agrees with the body. With no epoch
            history (this case) `:value` falls back to the live observed-
            frame db, so the popup shows current state. Empty path yields
            the whole value (root inspection). The OFF-HEAD consistency
            invariant is pinned in app_db_segment_inspector_cljs_test."
    (seed-xray! {:cart {:items [{:id 7 :qty 1}]
                         :gross 42}
                  :user "ada"}
                 [])
    (rf/with-frame :rf/xray
      ;; Leaf at [:cart :gross]
      (rf/dispatch-sync [:rf.xray/open-segment-inspector [:cart :gross]])
      (is (= 42 @(rf/subscribe [:rf.xray/segment-inspector-value])))
      ;; Sub-map at [:cart]
      (rf/dispatch-sync [:rf.xray/open-segment-inspector [:cart]])
      (is (= {:items [{:id 7 :qty 1}] :gross 42}
             @(rf/subscribe [:rf.xray/segment-inspector-value])))
      ;; Root: empty path → whole db.
      (rf/dispatch-sync [:rf.xray/open-segment-inspector []])
      (is (= {:cart {:items [{:id 7 :qty 1}] :gross 42}
              :user "ada"}
             @(rf/subscribe [:rf.xray/segment-inspector-value]))))))

;; ---- (6) focus event + 'Show me when this changed' result ---------------

(deftest focus-slice-path-event-writes-to-xray-frame
  (testing ":rf.xray/focus-slice-path lands the path on :rf/xray's
            :focused-slice-path"
    (registry/register-xray-handlers!)
    (rf/make-frame {:id :rf/xray})
    (rf/with-frame :rf/xray
      (rf/dispatch-sync [:rf.xray/focus-slice-path [:cart :items]]))
    (is (= [:cart :items]
           (:focused-slice-path (rf.frame/frame-app-db-value :rf/xray))))))

(deftest clear-slice-focus-event-drops-focus
  (registry/register-xray-handlers!)
  (rf/make-frame {:id :rf/xray})
  (rf/with-frame :rf/xray
    (rf/dispatch-sync [:rf.xray/focus-slice-path [:any]])
    (rf/dispatch-sync [:rf.xray/clear-slice-focus]))
  (is (nil? (:focused-slice-path (rf.frame/frame-app-db-value :rf/xray)))))

(deftest show-me-when-this-changed-result-filters-history
  (testing ":rf.xray/show-me-when-this-changed-result walks
            :rf.xray/epoch-history and returns only epochs that
            touched the focused path.

            Per spec §Changed-paths derivation the walker uses pointer-
            equality at each level — we use `assoc-in` so the
            unchanged :cart subtree keeps its PersistentHashMap
            identity across epoch boundaries (mirrors how host
            db-only reg-event handlers build successor app-dbs)."
    (let [db-0 {}
          db-1 (assoc-in db-0 [:cart :items] [])
          db-2 (assoc-in db-1 [:cart :items] [{:id 7}])
          db-3 (assoc    db-2 :user "ada")  ;; :cart identity preserved
          hist [(mk-record :e-1 [:app/boot]     db-0 db-1)
                (mk-record :e-2 [:cart/add-item] db-1 db-2)
                (mk-record :e-3 [:user/login]    db-2 db-3)]]
      (seed-xray! db-3 hist)
      (rf/with-frame :rf/xray
        (rf/dispatch-sync [:rf.xray/focus-slice-path [:cart :items]])
        (let [hits @(rf/subscribe [:rf.xray/show-me-when-this-changed-result])
              eids (mapv :epoch-id hits)]
          (is (= [:e-2 :e-1] eids)
              "newest-first; :e-3 didn't touch :cart :items"))))))

(deftest show-me-when-this-changed-result-empty-when-no-focus
  (registry/register-xray-handlers!)
  (rf/make-frame {:id :rf/xray})
  (rf/with-frame :rf/xray
    (is (= [] @(rf/subscribe [:rf.xray/show-me-when-this-changed-result])))))

;; ---- (7) the off-box safe-egress projection (rf2-uo0rc.2 + rf2-7htk7) ----
;;
;; These are the fail-closed proofs for `egress/egress-value`, Xray's single
;; named panel-local off-box projection. They used to reach it THROUGH the
;; value-copy event that rode the universal `⎘` affordance; rf2-6r9j.24
;; retired both, so they now call `egress-value` directly and assert on its
;; RETURN VALUE rather than on a captured fx.
;;
;; Re-pointing rather than deleting is deliberate. The projection SURVIVES
;; the retraction — `egress.cljs` prescribes it as the MUST-use gesture any
;; future panel affordance inherits, and the palette's `Snapshot app-db`
;; verb uses it today — so its invariants still need pins. A green suite
;; that lost them is exactly the fail-open this bead was filed about.
;;
;; The invariants, in the same order the copy-event tests proved them:
;; sensitive slot ⇒ :rf/redacted · large slot ⇒ :rf.size/large-elided ·
;; undeclared value passes through · nil :frame ⇒ whole value redacted ·
;; destroyed frame id ⇒ redacted · a LIVE frame registered under the
;; keyword the second pass used as its explicit-nil substitute ⇒ still
;; redacted (the third-pass collision regression).
;;

(defn- seed-sensitive-schema! []
  ;; EP-0025: durable app-db
  ;; classification rides the commit-plane classification effects.
  ;; `rf.elision/apply-classification-effects` writes a `:source :effect`
  ;; declaration (index-free :rf/path) onto :rf/default's per-frame
  ;; sensitive-declarations so the wire walker substitutes :rf/redacted on
  ;; off-box egress (the same write a reg-event returning `:sensitive` makes).
  ;; Callers make-frame :rf/default before invoking.
  (rf.frame/swap-runtime-db! :rf/default
    (fn [rt] (rf.elision/apply-classification-effects rt {:sensitive [[:auth :password]]}))))

(defn- seed-large-schema! []
  (rf.frame/swap-runtime-db! :rf/default
    (fn [rt] (rf.elision/apply-classification-effects rt {:large [[:blob :payload]]}))))

(deftest egress-value-redacts-sensitive-slot
  (testing "rf2-uo0rc.2 — a value carrying a frame-declared sensitive slot
            is REDACTED by the projection; the raw secret never reaches an
            off-box sink"
    (rf/make-frame {:id :rf/default})
    ;; Declare the sensitive path on the OBSERVED frame (:rf/default) so the
    ;; egress, pinned to that frame by name, matches the declaration.
    (rf/with-frame :rf/default (seed-sensitive-schema!))
    (let [out (egress/egress-value {:auth {:username "ada" :password "shh"}}
                                   {:frame :rf/default})
          text (pr-str out)]
      (is (re-find #":rf/redacted" text)
          (str "the sensitive slot must be redacted. text: " (pr-str text)))
      (is (not (re-find #"shh" text))
          (str "the RAW secret survived the projection — off-box egress "
               "fail-open (rf2-uo0rc.2). text: " (pr-str text)))
      (is (re-find #"ada" text)
          "non-sensitive sibling survives the projection"))))

(deftest egress-value-size-elides-large-slot
  (testing "rf2-uo0rc.2 — a value carrying a frame-declared :large slot is
            size-elided by the projection"
    (rf/make-frame {:id :rf/default})
    (rf/with-frame :rf/default (seed-large-schema!))
    (let [out  (egress/egress-value {:blob {:payload {:big "value"}}}
                                    {:frame :rf/default})
          text (pr-str out)]
      (is (re-find #":rf.size/large-elided" text)
          (str "the large slot must be size-elided. text: " (pr-str text)))
      (is (not (re-find #"\"value\"" text))
          (str "the RAW large value survived the projection. text: "
               (pr-str text))))))

(deftest egress-value-non-sensitive-passes-through
  (testing "rf2-uo0rc.2 — the projection is a no-op for a value with no
            sensitive/large declarations: the full value round-trips
            (fail-closed only bites declared slots)"
    (rf/make-frame {:id :rf/default})
    (is (= "{:a 1, :b [2 3]}"
           (pr-str (egress/egress-value {:a 1 :b [2 3]} {:frame :rf/default})))
        "an undeclared value projects verbatim")))

;; ---- (7b) no-target / stale-target egress FAILS CLOSED (rf2-7htk7) -------
;;
;; A panel affordance runs UNDER the live `:rf/xray` chrome frame. A bare
;; `(egress/egress-value value)` whenever the observed frame is nil or no
;; longer live therefore resolves `:rf/xray`, applies its normally-EMPTY
;; declaration registry, and passes the value through RAW. An earlier
;; comment on that branch claimed it was "still fail-closed"; it was not.
;; `elide-wire-value`'s frameless arm can only be reached by NAMING a frame
;; that does not resolve — which is why `egress.cljs` requires a caller to
;; forward `:frame` unconditionally, including when it is nil.

(deftest egress-value-with-nil-frame-fails-closed
  (testing "rf2-7htk7 — an explicitly-nil :frame (the unselected picker)
            redacts the value whole rather than projecting it under the
            Xray chrome frame's empty policy"
    (let [out (egress/egress-value {:auth {:token "shh"}} {:frame nil})]
      (is (= :rf/redacted out)
          (str "no resolvable frame must egress the whole-value redaction "
               "sentinel. got: " (pr-str out)))
      (is (not (re-find #"shh" (pr-str out)))
          (str "the RAW value survived with no frame policy in force "
               "(rf2-7htk7). got: " (pr-str out))))))

(deftest egress-value-with-destroyed-frame-fails-closed
  (testing "rf2-7htk7 — a host frame destroyed between render and use
            leaves a STALE observed id; a frame-id that no longer resolves
            fails closed exactly like the nil case"
    (rf/make-frame {:id :rf/default})
    ;; The inspected frame goes away while the caller still names it.
    (rf/destroy-frame! :rf/default)
    (let [out (egress/egress-value {:auth {:token "shh"}} {:frame :rf/default})]
      (is (= :rf/redacted out)
          (str "a stale frame id must egress the whole-value redaction "
               "sentinel. got: " (pr-str out)))
      (is (not (re-find #"shh" (pr-str out)))
          (str "the RAW value survived under a destroyed frame (rf2-7htk7). "
               "got: " (pr-str out))))))

(deftest egress-value-with-nil-frame-is-unregistrable-and-fails-closed
  (testing "rf2-7htk7 / rf2-kuky.5 — an explicitly-nil :frame must redact
            whole, and nil is the one frame id an app CANNOT make resolve.
            Earlier passes substituted a fake id for the nil because the
            walker read nil as absence; each substitute was itself a
            registrable value, so an app could register a live frame under it,
            take the walker's live-frame branch, and pass the value through RAW
            under that frame's empty registry. The walker now believes the nil,
            and guards its live-frame arm with `(some? frame-id)`, so no
            registration can reach it"
    ;; The app's own frame holds the secret and declares it sensitive — but
    ;; it is not the frame named here, so its policy is not what governs.
    (rf/make-frame {:id :rf/default})
    (rf/with-frame :rf/default (seed-sensitive-schema!))
    ;; Attempt the registration every earlier collision turned on, now aimed
    ;; at nil itself. A runtime that refuses a nil id is fine — the assertion
    ;; must hold either way.
    (let [registered? (try (rf/make-frame {:id nil}) true
                           (catch :default _ false))]
      (try
        (let [out (egress/egress-value {:auth {:username "ada" :password "shh"}}
                                       {:frame nil})]
          (is (= :rf/redacted out)
              (str "an explicitly-nil frame must egress the whole-value "
                   "redaction sentinel. got: " (pr-str out)))
          (is (not (re-find #"shh" (pr-str out)))
              (str "the RAW secret survived an explicit-nil frame "
                   "(rf2-7htk7, rf2-kuky.5). got: " (pr-str out))))
        (finally
          (when registered?
            (try (rf/destroy-frame! nil) (catch :default _ nil))))))))

;; ---- (8) view renders — current-state inspector (rf2-okvit) -------------
;;
;; The app-db tab is a CURRENT-STATE inspector, not a diff. The Panel
;; renders `app-db-diff-state/state-body` over the observed frame's live
;; app-db: a TOP user-domain section + one section per reserved `:rf/*`
;; area (machines/spawned fan out per id; route + slices are singletons;
;; absent/empty areas render an empty-state). The diff / focus-result /
;; redacted-chip view affordances were dropped here (rf2-okvit) — their
;; data subs survive and are exercised at the sub level above.

(deftest panel-renders-current-state-container
  (testing "the Panel renders the current-state inspector container +
            the TOP user-domain section over the observed frame's db"
    (seed-host-frame! {:counter 5 :user {:name "ada"}})
    (registry/register-xray-handlers!)
    (rf/make-frame {:id :rf/xray})
    (rf/with-frame :rf/xray
      (let [tree (app-db-diff/Panel)]
        (is (some? (find-by-testid tree "rf-xray-app-db-diff"))
            "panel root present")
        (is (some? (find-by-testid tree "rf-xray-app-db-state"))
            "current-state inspector body present")
        (is (some? (find-by-testid tree "rf-xray-app-db-state-top"))
            "TOP user-domain section present")
        ;; rf2-okvit — no diff machinery on this view.
        (is (nil? (find-by-testid tree "rf-xray-diff-sections"))
            "no diff sections")
        (is (nil? (find-by-testid tree "rf-xray-app-db-diff-slices"))
            "no slice stack")))))

(deftest panel-sections-reserved-areas
  (testing "reserved runtime subsystems render as their own sections:
            machines fan out one section per machine id; route is a
            singleton. EP-0001 (rf2-tj6w9l): the runtime subsystems live
            in the runtime-db partition at [:rf.runtime/...], NOT app-db's
            retired :rf/runtime container."
    (seed-host-frame! {:cart {:items [{:id 7}]}})
    (seed-host-runtime-db! {:rf.runtime/routing  {:current {:route-id :app/cart}}
                            :rf.runtime/machines {:snapshots {:auth       {:state :idle}
                                                              :title/flow {:state :playing}}}})
    (registry/register-xray-handlers!)
    (rf/make-frame {:id :rf/xray})
    ;; EP-0002 (rf2-bd4div) — select the host `:rf/default` frame as the
    ;; observed target explicitly (it no longer defaults).
    (rf/with-frame :rf/xray
      (rf/dispatch-sync [:rf.xray/set-target-frame :rf/default]))
    (rf/with-frame :rf/xray
      (let [tree (app-db-diff/Panel)]
        ;; machines fan out one section per id (title = machine id).
        (is (some? (find-by-testid
                     tree "rf-xray-app-db-state-instance-:rf/machines-:auth"))
            "machine :auth has its own section")
        (is (some? (find-by-testid
                     tree "rf-xray-app-db-state-instance-:rf/machines-:title/flow"))
            "machine :title/flow has its own section")
        ;; route is a singleton section.
        (is (some? (find-by-testid tree "rf-xray-app-db-state-area-:rf/route"))
            "route singleton section present")))))

(deftest panel-omits-empty-reserved-areas
  (testing "rf2-jcdvo — absent reserved areas are OMITTED from the
            panel entirely; no placeholder cards. Visibility is
            data-driven — a card appears when state accrues at that
            slot, never as a persistent 'No X' placeholder."
    (seed-host-frame! {:counter 1})
    (registry/register-xray-handlers!)
    (rf/make-frame {:id :rf/xray})
    ;; EP-0002 (rf2-bd4div) — select the host frame as observed target so
    ;; the panel actually projects against it (the no-placeholder check is
    ;; meaningful only when a target is selected).
    (rf/with-frame :rf/xray
      (rf/dispatch-sync [:rf.xray/set-target-frame :rf/default]))
    (rf/with-frame :rf/xray
      (let [tree (app-db-diff/Panel)]
        (doseq [area [:rf/machines :rf/spawned :rf/route :rf/system-ids
                      :rf/pending-navigation :rf/elision]]
          (is (nil? (find-by-testid
                      tree (str "rf-xray-app-db-state-area-" (pr-str area))))
              (str "no placeholder card for empty reserved area " area)))))))

(deftest focus-slice-path-event-still-wired
  (testing "the :rf.xray/focus-slice-path event remains registered. UI
            affordance moved off the slice mini-panel — same follow-on
            as the pin button above."
    (seed-xray! {:cart {:items [{:id 7}]}}
                 [(mk-record :e-1 [:cart/add]
                             {:cart {:items []}}
                             {:cart {:items [{:id 7}]}})])
    (rf/with-frame :rf/xray
      (rf/dispatch-sync [:rf.xray/focus-slice-path [:cart :items]])
      (let [xray-db (rf.frame/frame-app-db-value :rf/xray)]
        (is (= [:cart :items]
               (:focused-slice-path xray-db)))))))

;; ---- rf2-fvplw — App-db panel follows picker / focused frame -----------

(deftest observed-frame-follows-rf-xray-set-frame
  (testing "rf2-fvplw — the frame-picker dispatches `:rf.xray/set-frame`
            which writes `[:focus :frame]`. The App-db panel's
            `:rf.xray/observed-frame` sub picks the focused frame up.
            Pre-fix the panel read the legacy `:rf.xray/target-frame`
            slot (which `:rf.xray/set-frame` does NOT touch) and stayed
            stuck on the default regardless of picker selection."
    (registry/register-xray-handlers!)
    (rf/make-frame {:id :rf/xray})
    (rf/make-frame {:id :rf/default})
    (rf/make-frame {:id :checkout-frame})
    (rf/with-frame :rf/xray
      ;; EP-0002 (rf2-bd4div) — cold-start: observed frame is UNSELECTED
      ;; (nil) before any picker selection lands, NOT a synthesised
      ;; `:rf/default`.
      (is (nil? @(rf/subscribe [:rf.xray/observed-frame])))
      ;; User picks :checkout-frame in the ribbon dropdown.
      (rf/dispatch-sync [:rf.xray/set-frame :checkout-frame])
      (is (= :checkout-frame @(rf/subscribe [:rf.xray/observed-frame]))
          "observed-frame sub follows the picker"))))

(deftest current-state-view-follows-picker-selected-frame
  (testing "rf2-okvit / rf2-fvplw — the current-state inspector reads the
            observed (picker-selected) frame's live app-db via
            `:rf.xray/target-frame-db`. Picking `:checkout-frame`
            surfaces THAT frame's value in the TOP section, not the
            `:rf/default` frame's."
    (registry/register-xray-handlers!)
    (rf/make-frame {:id :rf/xray})
    (rf/make-frame {:id :rf/default})
    (rf/make-frame {:id :checkout-frame})
    ;; Give the two frames distinguishable values.
    (rf/replace-frame-state! :rf/default {:rf.db/app {:counter 0}})
    (rf/replace-frame-state! :checkout-frame {:rf.db/app {:checkout {:step :payment}}})
    (rf/with-frame :rf/xray
      (rf/dispatch-sync [:rf.xray/set-frame :checkout-frame])
      (let [tree (app-db-diff/Panel)
            top  (find-by-testid tree "rf-xray-app-db-state-top")]
        (is (some? top) "TOP section present")
        (is (= :checkout-frame @(rf/subscribe [:rf.xray/observed-frame]))
            "inspector observes the picker-selected frame")
        (is (= {:checkout {:step :payment}}
               @(rf/subscribe [:rf.xray/target-frame-db]))
            "the observed db is the picked frame's live value")))))

;; ---- rf2-ug1r6 — picker change resets epoch-history slot ----------------
;;
;; rf2-fvplw wired `:rf.xray/observed-frame` + `:rf.xray/target-frame-
;; db` to follow the picker, but the App-DB Diff panel's
;; selected-epoch-* sub chain (the diff triples + annotated tree +
;; sections) read off `:rf.xray/epoch-history` — a Xray-side slot
;; keyed on the legacy `:target-frame` axis the picker did NOT touch.
;; After a picker change the slot stayed on the previous (likely
;; empty `:rf/default`) frame's history, so:
;;
;;   :rf.xray/selected-epoch-diff → (peek <empty>) → nil → composite's
;;   :history-empty? → true → the panel rendered the boot empty-state
;;   "app-db for :cart-frame is at the boot value. No diffs yet."
;;   EVEN WITH a focused cascade in the picked frame (the Mike report).
;;
;; The fix in `spine/set-frame-reducer` aligns the two axes — picker
;; now also writes `:target-frame` and re-seeds `:epoch-history` from
;; the framework's per-frame ring. This regression guard asserts the
;; alignment.

(deftest set-frame-aligns-target-frame-and-resets-epoch-history
  (testing "rf2-ug1r6 — picker writes `:target-frame` + clears the
            `:epoch-history` slot so future `:rf.xray/epoch-recorded`
            callbacks pump the picked frame's epochs into the right
            slot. Pre-fix the slot stayed keyed to the legacy
            target frame and the App-DB diff panel rendered empty-
            state with a focused cascade present."
    (registry/register-xray-handlers!)
    (rf/make-frame {:id :rf/xray})
    (rf/make-frame {:id :rf/default})
    (rf/make-frame {:id :cart-frame})
    (rf/with-frame :rf/xray
      ;; Seed the slot with stale `:rf/default` history; user picks
      ;; `:cart-frame`; the slot MUST reset so the wrong-frame epochs
      ;; do not bleed into the picked-frame panel.
      (rf/dispatch-sync [:rf.xray/sync-epoch-history
                         [(mk-record :stale-e [:default/event] {} {})]])
      (is (= 1 (count @(rf/subscribe [:rf.xray/epoch-history])))
          "stale slot present pre-picker")
      (rf/dispatch-sync [:rf.xray/set-frame :cart-frame])
      (is (= :cart-frame @(rf/subscribe [:rf.xray/target-frame]))
          ":target-frame follows picker so :rf.xray/epoch-recorded
           delivers future cart-frame epochs into the slot")
      (is (= [] @(rf/subscribe [:rf.xray/epoch-history]))
          "stale `:rf/default` history MUST clear so the wrong-frame
           records do not leak into the picked-frame panel"))))

(deftest app-db-diff-renders-cart-frame-diff-after-picker-and-seed
  (testing "rf2-ug1r6 — post-picker-change + post-history-reseed, the
            App-DB panel resolves the cart-frame's focused epoch (not
            the boot empty-state). This is the structural inverse of
            Mike's bug report: with the alignment in place, focused
            cascades in the picked frame produce a real focused-epoch
            read-model. rf2-p53m2 — re-pointed off the pruned
            `:rf.xray/app-db-diff` composite onto the panel's live
            primary sub `:rf.xray/app-db-current+diff`."
    (registry/register-xray-handlers!)
    (rf/make-frame {:id :rf/xray})
    (rf/make-frame {:id :rf/default})
    (rf/make-frame {:id :cart-frame})
    (rf/with-frame :rf/xray
      (rf/dispatch-sync [:rf.xray/set-frame :cart-frame])
      ;; Framework's `:rf.xray/epoch-recorded` cb re-pumps the
      ;; cart-frame's ring into the slot; tests drive the wholesale
      ;; overwrite directly.
      (let [rec (-> (mk-record :cart-e [:cart/add]
                               {:cart {:items []}}
                               {:cart {:items [{:id 7}]}})
                    (assoc :frame :cart-frame))]
        (rf/dispatch-sync [:rf.xray/sync-epoch-history [rec]])
        ;; Focus the picked frame's epoch so the panel's primary sub
        ;; resolves a real focused-epoch read-model.
        (rf/dispatch-sync [:rf.xray/select-epoch :cart-e]))
      (is (= 1 (count @(rf/subscribe [:rf.xray/epoch-history])))
          "sanity: the re-seeded cart-frame epoch is in the slot")
      (let [data @(rf/subscribe [:rf.xray/app-db-current+diff])]
        (is (= :cart-e (:epoch-id data))
            "panel resolves the picked frame's focused epoch → renders
             the focused-epoch read-model rather than the boot empty-
             state (rf2-ug1r6)")
        (is (= {:cart {:items [{:id 7}]}} (:value data))
            ":value is the focused epoch's :db-after")
        (is (= :cart-frame @(rf/subscribe [:rf.xray/observed-frame]))
            "observed-frame reflects the picker selection (rf2-fvplw —
             preserved post-rf2-ug1r6)")))))
