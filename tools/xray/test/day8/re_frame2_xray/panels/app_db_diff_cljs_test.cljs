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
            [re-frame.elision :as elision]
            [re-frame.frame :as frame]
            [re-frame.registrar :as registrar]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]
            [day8.re-frame2-xray.preload :as preload]
            [day8.re-frame2-xray.registry :as registry]
            [day8.re-frame2-xray.test-support :as xray-test-support]
            [day8.re-frame2-xray.trace-collector :as trace-collector]
            [day8.re-frame2-xray.panels.app-db-diff :as app-db-diff]
            [day8.re-frame2-xray.panels.app-db-diff-state :as state]))

;; ---- fixtures -----------------------------------------------------------

(defn- xray-init! []
  (xray-test-support/reset-all!)
  (trace-collector/reset-for-test!))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter plain-atom/adapter
     :init-fn xray-init!}))

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
  (frame/reg-frame :rf/default {})
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
  (frame/reg-frame :rf/default {})
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
  (frame/reg-frame :rf/xray {})
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
    (is (some? (registrar/handler :sub :rf.xray/target-frame-db)))
    (is (some? (registrar/handler :sub :rf.xray/selected-epoch-record)))
    (is (some? (registrar/handler :sub :rf.xray/focused-slice-path)))
    (is (some? (registrar/handler :sub :rf.xray/show-me-when-this-changed-result)))
    ;; rf2-okvit — the current-state inspector's section-model sub.
    (is (some? (registrar/handler :sub :rf.xray/app-db-state)))
    ;; rf2-yng0y — the atomic current-state + before-image sub the panel
    ;; pivots on (and the segment-inspector reads through, rf2-jmucu).
    (is (some? (registrar/handler :sub :rf.xray/app-db-current+diff)))
    ;; rf2-e9tb0 — segment-inspector subs registered alongside.
    (is (some? (registrar/handler :sub :rf.xray/segment-inspector-open?)))
    (is (some? (registrar/handler :sub :rf.xray/segment-inspector-path)))
    (is (some? (registrar/handler :sub :rf.xray/segment-inspector-value)))
    ;; rf2-p53m2 — the dead composite diff family was pruned (no
    ;; production view consumer); guard against re-introduction.
    (is (nil? (registrar/handler :sub :rf.xray/selected-epoch-diff)))
    (is (nil? (registrar/handler :sub :rf.xray/app-db-diff)))
    (is (nil? (registrar/handler :sub :rf.xray/selected-epoch-redacted-modified-count)))
    (is (nil? (registrar/handler :sub :rf.xray/selected-epoch-flow-writes)))
    ;; Pinned-slices subs are gone.
    (is (nil? (registrar/handler :sub :rf.xray/pinned-slices-store)))
    (is (nil? (registrar/handler :sub :rf.xray/pinned-slices)))))

(deftest registry-installs-app-db-diff-events
  (testing "register-xray-handlers! installs the Phase 5 events.
            Pin / unpin / reorder events were removed under rf2-e9tb0;
            the segment-inspector open / close events landed in their
            place."
    (registry/register-xray-handlers!)
    (is (some? (registrar/handler :event :rf.xray/focus-slice-path)))
    (is (some? (registrar/handler :event :rf.xray/clear-slice-focus)))
    (is (some? (registrar/handler :event :rf.xray/copy-value-to-clipboard)))
    (is (some? (registrar/handler :event :rf.xray/copy-path-to-clipboard)))
    ;; rf2-e9tb0 — segment-inspector events registered.
    (is (some? (registrar/handler :event :rf.xray/open-segment-inspector)))
    (is (some? (registrar/handler :event :rf.xray/close-segment-inspector)))
    ;; Pin events are gone.
    (is (nil? (registrar/handler :event :rf.xray/pin-slice)))
    (is (nil? (registrar/handler :event :rf.xray/unpin-slice)))
    (is (nil? (registrar/handler :event :rf.xray/reorder-pinned-slices)))))

(deftest registry-installs-clipboard-fx
  (testing "register-xray-handlers! installs the :rf.xray.fx/copy-to-
            clipboard effect"
    (registry/register-xray-handlers!)
    (is (some? (registrar/handler :fx :rf.xray.fx/copy-to-clipboard)))))

;; ---- (5) rf2-e9tb0 — segment-inspector events + state ------------------

(deftest open-segment-inspector-writes-path-to-xray-frame
  (testing "rf2-e9tb0 — :rf.xray/open-segment-inspector writes the
            requested path into Xray's frame at :segment-inspector.
            The path is normalised to a vector so callers can pass
            seqs / lists / vectors interchangeably."
    (registry/register-xray-handlers!)
    (frame/reg-frame :rf/xray {})
    (frame/reg-frame :rf/default {})
    (rf/with-frame :rf/xray
      (rf/dispatch-sync [:rf.xray/open-segment-inspector
                         (list :cart :items 0 :price)])
      (is (= {:path [:cart :items 0 :price]}
             (:segment-inspector (frame/frame-app-db-value :rf/xray)))))))

(deftest close-segment-inspector-drops-slot
  (testing "rf2-e9tb0 — :rf.xray/close-segment-inspector dissocs the
            slot so the popup reg-view's `when`-gate short-circuits"
    (registry/register-xray-handlers!)
    (frame/reg-frame :rf/xray {})
    (rf/with-frame :rf/xray
      (rf/dispatch-sync [:rf.xray/open-segment-inspector [:cart]])
      (is (some? (:segment-inspector (frame/frame-app-db-value :rf/xray))))
      (rf/dispatch-sync [:rf.xray/close-segment-inspector])
      (is (nil? (:segment-inspector (frame/frame-app-db-value :rf/xray)))))))

(deftest segment-inspector-open?-tracks-slot-presence
  (testing "rf2-e9tb0 — :rf.xray/segment-inspector-open? is true iff
            the :segment-inspector slot is non-nil"
    (registry/register-xray-handlers!)
    (frame/reg-frame :rf/xray {})
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
    (frame/reg-frame :rf/xray {})
    (rf/with-frame :rf/xray
      (rf/dispatch-sync [:rf.xray/focus-slice-path [:cart :items]]))
    (is (= [:cart :items]
           (:focused-slice-path (frame/frame-app-db-value :rf/xray))))))

(deftest clear-slice-focus-event-drops-focus
  (registry/register-xray-handlers!)
  (frame/reg-frame :rf/xray {})
  (rf/with-frame :rf/xray
    (rf/dispatch-sync [:rf.xray/focus-slice-path [:any]])
    (rf/dispatch-sync [:rf.xray/clear-slice-focus]))
  (is (nil? (:focused-slice-path (frame/frame-app-db-value :rf/xray)))))

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
  (frame/reg-frame :rf/xray {})
  (rf/with-frame :rf/xray
    (is (= [] @(rf/subscribe [:rf.xray/show-me-when-this-changed-result])))))

;; ---- (7) clipboard fx is wired (fires without crashing) -----------------

(deftest copy-events-fire-clipboard-fx
  (testing ":rf.xray/copy-value-to-clipboard + :rf.xray/copy-path-to-
            clipboard route through :rf.xray.fx/copy-to-clipboard; the
            fx is best-effort (no-op on non-browser targets)"
    (registry/register-xray-handlers!)
    (frame/reg-frame :rf/xray {})
    (let [captured (atom [])]
      (rf/reg-fx :rf.xray.fx/copy-to-clipboard
        (fn [_ctx args] (swap! captured conj args)))
      (rf/with-frame :rf/xray
        (rf/dispatch-sync [:rf.xray/copy-value-to-clipboard {:a 1}])
        (rf/dispatch-sync [:rf.xray/copy-path-to-clipboard [:cart :items]]))
      (is (= 2 (count @captured)))
      (is (= "{:a 1}" (:text (first @captured))))
      (is (= "[:cart :items]" (:text (second @captured)))))))

;; ---- (7b) clipboard copy is an OFF-BOX EGRESS site (rf2-uo0rc.2) ---------
;;
;; The system clipboard is an off-box sink. The copy fx wrote the RAW
;; (pr-str value) — a `{:sensitive? true}` slot or a large blob leaked
;; verbatim. The value-copy event now routes through runtime/egress-value
;; (fail-closed: sensitive ⇒ :rf/redacted, large ⇒ :rf.size/large-elided),
;; pinned to the observed frame so that frame's schema declarations govern
;; — the same fix class as the palette snapshot (rf2-mxzgg) + get-app-db
;; (rf2-a96xq).

(defn- seed-sensitive-schema! []
  ;; Mirror runtime_cljs_test/seed-sensitive-schema! — EP-0025: durable app-db
  ;; classification rides the commit-plane classification effects.
  ;; `elision/apply-classification-effects` writes a `:source :effect`
  ;; declaration (index-free :rf/path) onto :rf/default's per-frame
  ;; sensitive-declarations so the wire walker substitutes :rf/redacted on
  ;; off-box egress (the same write a reg-event returning `:sensitive` makes).
  ;; Callers reg-frame :rf/default before invoking.
  (frame/swap-runtime-db! :rf/default
    (fn [rt] (elision/apply-classification-effects rt {:sensitive [[:auth :password]]}))))

(defn- seed-large-schema! []
  (frame/swap-runtime-db! :rf/default
    (fn [rt] (elision/apply-classification-effects rt {:large [[:blob :payload]]}))))

(defn- capture-copy! []
  (let [captured (atom [])]
    (rf/reg-fx :rf.xray.fx/copy-to-clipboard
      (fn [_ctx args] (swap! captured conj args)))
    captured))

(deftest copy-value-redacts-sensitive-slot
  (testing "rf2-uo0rc.2 — a copied value carrying a frame-declared
            sensitive slot is REDACTED before it reaches the clipboard fx;
            the raw secret never crosses the off-box boundary"
    (registry/register-xray-handlers!)
    (frame/reg-frame :rf/default {})
    (frame/reg-frame :rf/xray {})
    ;; Declare the sensitive path on the OBSERVED frame (:rf/default) so the
    ;; egress, pinned to the observed frame, matches the declaration.
    (rf/with-frame :rf/default (seed-sensitive-schema!))
    (let [captured (capture-copy!)]
      ;; Pin the spine focus at the observed frame so the event resolves it.
      (rf/with-frame :rf/xray
        (rf/dispatch-sync [:rf.xray/set-target-frame :rf/default])
        (rf/dispatch-sync
          [:rf.xray/copy-value-to-clipboard
           {:auth {:username "ada" :password "shh"}}]))
      (is (= 1 (count @captured)))
      (let [text (:text (first @captured))]
        (is (re-find #":rf/redacted" text)
            (str "the sensitive slot must be redacted on the clipboard text. "
                 "text: " (pr-str text)))
        (is (not (re-find #"shh" text))
            (str "the RAW secret leaked to the clipboard — off-box egress "
                 "fail-open (rf2-uo0rc.2). text: " (pr-str text)))
        (is (re-find #"ada" text)
            "non-sensitive sibling survives the copy")))))

(deftest copy-value-size-elides-large-slot
  (testing "rf2-uo0rc.2 — a copied value carrying a frame-declared
            :large slot is size-elided before it reaches the clipboard fx"
    (registry/register-xray-handlers!)
    (frame/reg-frame :rf/default {})
    (frame/reg-frame :rf/xray {})
    (rf/with-frame :rf/default (seed-large-schema!))
    (let [captured (capture-copy!)]
      (rf/with-frame :rf/xray
        (rf/dispatch-sync [:rf.xray/set-target-frame :rf/default])
        (rf/dispatch-sync
          [:rf.xray/copy-value-to-clipboard
           {:blob {:payload {:big "value"}}}]))
      (is (= 1 (count @captured)))
      (let [text (:text (first @captured))]
        (is (re-find #":rf.size/large-elided" text)
            (str "the large slot must be size-elided on the clipboard text. "
                 "text: " (pr-str text)))
        (is (not (re-find #"\"value\"" text))
            (str "the RAW large value leaked to the clipboard. text: "
                 (pr-str text)))))))

(deftest copy-value-non-sensitive-passes-through
  (testing "rf2-uo0rc.2 — egress is a no-op for a value with no
            sensitive/large declarations: the copy still round-trips the
            full value (fail-closed only bites declared slots)"
    (registry/register-xray-handlers!)
    (frame/reg-frame :rf/default {})
    (frame/reg-frame :rf/xray {})
    (let [captured (capture-copy!)]
      (rf/with-frame :rf/xray
        (rf/dispatch-sync [:rf.xray/set-target-frame :rf/default])
        (rf/dispatch-sync [:rf.xray/copy-value-to-clipboard {:a 1 :b [2 3]}]))
      (is (= "{:a 1, :b [2 3]}" (:text (first @captured)))
          "an undeclared value copies verbatim"))))

(deftest copy-path-is-not-a-value-egress
  (testing "rf2-uo0rc.2 — the path-copy variant copies only the path
            vector (key names, no values); it is not a value-egress site
            and must NOT redact path segments"
    (registry/register-xray-handlers!)
    (frame/reg-frame :rf/default {})
    (frame/reg-frame :rf/xray {})
    (rf/with-frame :rf/default (seed-sensitive-schema!))
    (let [captured (capture-copy!)]
      (rf/with-frame :rf/xray
        (rf/dispatch-sync [:rf.xray/set-target-frame :rf/default])
        (rf/dispatch-sync [:rf.xray/copy-path-to-clipboard [:auth :password]]))
      (is (= "[:auth :password]" (:text (first @captured)))
          "the path vector copies verbatim — it carries no values to elide"))))

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
    (frame/reg-frame :rf/xray {})
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
    (frame/reg-frame :rf/xray {})
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
    (frame/reg-frame :rf/xray {})
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
      (let [xray-db (frame/frame-app-db-value :rf/xray)]
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
    (frame/reg-frame :rf/xray {})
    (frame/reg-frame :rf/default {})
    (frame/reg-frame :checkout-frame {})
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
    (frame/reg-frame :rf/xray {})
    (frame/reg-frame :rf/default {})
    (frame/reg-frame :checkout-frame {})
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
    (frame/reg-frame :rf/xray {})
    (frame/reg-frame :rf/default {})
    (frame/reg-frame :cart-frame {})
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
    (frame/reg-frame :rf/xray {})
    (frame/reg-frame :rf/default {})
    (frame/reg-frame :cart-frame {})
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
