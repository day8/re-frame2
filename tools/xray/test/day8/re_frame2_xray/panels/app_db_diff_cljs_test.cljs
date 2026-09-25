(ns day8.re-frame2-xray.panels.app-db-diff-cljs-test
  "CLJS-side wiring + view tests for Xray's app-db tab.

  ## Current-state inspector

  The app-db tab is a CURRENT-STATE inspector (re-frame-10x style), not
  a diff view, sectioned by reserved `:rf/*` area.
  The view-render tests assert the inspector shape (TOP user-domain
  section, per-instance machine fan-out, singleton route; empty /
  absent reserved areas are filtered at projection time so
  no placeholder cards reach the renderer) via
  `app-db-diff-state/state-body` through the Panel.

  ## No composite diff-sub family

  There is no `:rf.xray/selected-epoch-diff` → `:rf.xray/app-db-diff`
  composite family (nor `:rf.xray/selected-epoch-redacted-modified-count`
  / `:rf.xray/selected-epoch-flow-writes` and their `[frame-id epoch-id]`
  caches): the Epoch panel's `:db` diff reads
  `:rf.xray/selected-epoch-record` + runs its own `db-diff-paths`, and
  the MCP `get-app-db-diff` tool projects directly through
  `diff.engine/project`. The diff-projection correctness lives in the
  engine's own tests. This file's tests pin the LIVE app-db-tab surface.

  ## Contracts under test (beyond the pure-data tests in
  `app_db_diff_helpers_cljs_test.cljc` / the view-shape tests in
  `app_db_diff_state_cljs_test.cljs`)

  1. **Registry wires the subs / events** under the `:rf.xray/*`
     namespace, including the `:rf.xray/app-db-state` +
     `:rf.xray/app-db-current+diff` subs (and not the composite diff
     family).

  2. **There are no path-click handlers** — no segment-inspector
     popup, no 'show me when this changed' sub, no slice-focus event
     pair, since nothing in `tools/xray/src` would dispatch or
     subscribe them. Each nil-assert
     sits beside a positive control so absence cannot read as a failed
     install.

  3. **Current-state inspector view** renders the section model and
     follows the picker-selected frame.

  ## Pure hiccup

  We walk the view's hiccup tree by `data-testid` rather than mounting
  to a DOM. Keeps the suite fast + host-portable on node-test."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [reagent.core :as r]
            [re-frame.core :as rf]
            [re-frame.elision :as rf.elision]
            [re-frame.frame :as rf.frame]
            [re-frame.registrar :as rf.registrar]
            [day8.re-frame2-xray.egress :as egress]
            [day8.re-frame2-xray.registry :as registry]
            [day8.re-frame2-xray.test-support :as xray-test-support]
            [day8.re-frame2-xray.panels.app-db-diff :as app-db-diff]))

;; ---- fixtures -----------------------------------------------------------

(use-fixtures :each
  ;; `make-xray-runtime-fixture` owns the setup: plain-atom adapter + the
  ;; default `:all` reset tier, which includes the trace-collector ring
  ;; reset.
  (xray-test-support/make-xray-runtime-fixture))

;; ---- the panel under test ------------------------------------------------

(defn- panel-tree
  "The app-db panel's hiccup, driven the way the rows below need it.

  `app-db-diff/Panel` is an `rf.fresco/defview` boundary,
  a real React function component whose body may only run inside a React
  render window, so it is not callable here. This helper REPRODUCES THE
  BOUNDARY EXACTLY — the same two reads, in the same order, with the same
  query vectors, and the same keyed-fragment wrapper around
  `state-body` — so every row asserts on the hiccup the boundary renders.

  Only the READS are reproduced here; the MARKUP stays in the panel, as
  `app-db-diff/panel-tree`, so these rows fail when the panel's own
  shape drifts.

  The 1-arity reproduces a `Panel` mounted with an
  `:instance-id` prop, which is how a caller names ONE of two live panels
  under one `frame-provider`."
  ([] (panel-tree nil))
  ([instance-id]
   (app-db-diff/panel-tree
     @(rf/subscribe [:rf.xray/app-db-state])
     (:epoch-id @(rf/subscribe [:rf.xray/app-db-current+diff]))
     instance-id)))

;; ---- fixture data --------------------------------------------------------

(defn- mk-record
  "Build a minimal `:rf/epoch-record` map for the epoch-history tests."
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
  value via the framework's replace-app-db!. The panel
  derefs the host frame via :rf.xray/target-frame-db."
  [db-value]
  (rf/make-frame {:id :rf/default})
  (rf/replace-frame-state! :rf/default {:rf.db/app db-value}))

(defn- seed-host-runtime-db!
  "Install `runtime-db-value` into the host (:rf/default) frame's
  RUNTIME-DB partition (EP-0001 — the App-DB panel sources its
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
  "Wire the sub graph + seed history + host-frame db. The
  test environment proxies the production wiring path (preload +
  registry + epoch-cb) so the subs read live values."
  [host-db-value history]
  (registry/register-xray-handlers!)
  (rf/make-frame {:id :rf/xray})
  (register-seed-events!)
  (seed-host-frame! host-db-value)
  ;; EP-0002 — the inspected target does not default to
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

(defn- hiccup-seq
  "Walk the panel's hiccup tree. Plain descent — nothing is CALLED.

  `state-body` is CALLED by the panel (a plain fn in hiccup head position
  is a loud error under Fresco), so its sections are realized in the tree
  and a shallow walk reaches them. Expanding fn-components while
  descending would be UNSAFE: the one fn-headed vector is
  `[ei/edn-inspector-view …]`,
  a FRESCO BOUNDARY — a React function component whose body may only run
  inside a React render window. Applying it here would run `rf.fresco/sub`
  outside the collector, which is not a leaf-expansion at all. So the
  widget stays a leaf."
  [tree]
  (tree-seq (some-fn vector? seq?) seq tree))

(defn- widget-mount-ids
  "The `:mount-id` the panel composed for every edn-inspector widget in
  `tree`, in render order. The widget's Fresco head is the one SYMBOL in
  hiccup head position on this path (see `hiccup-seq` above), so a
  `[fn? {map?}]` node is one mount."
  [tree]
  (->> (hiccup-seq tree)
       (keep (fn [n]
               (when (and (vector? n) (fn? (first n)) (map? (second n)))
                 (:mount-id (second n)))))
       vec))

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
  (testing "register-xray-handlers! installs the app-db tab's subs,
            and no pinned-slices subs (there is no pinned-watches
            strip)."
    (registry/register-xray-handlers!)
    (is (some? (rf.registrar/handler :sub :rf.xray/target-frame-db)))
    (is (some? (rf.registrar/handler :sub :rf.xray/selected-epoch-record)))
    ;; The current-state inspector's section-model sub.
    (is (some? (rf.registrar/handler :sub :rf.xray/app-db-state)))
    ;; The atomic current-state + before-image sub the panel
    ;; pivots on.
    (is (some? (rf.registrar/handler :sub :rf.xray/app-db-current+diff)))
    ;; No segment-inspector popup sub and no show-me-when walker sub:
    ;; nothing in src would dispatch the popup's opener or subscribe the
    ;; walker's result.
    (is (nil? (rf.registrar/handler :sub :rf.xray/segment-inspector-open?)))
    (is (nil? (rf.registrar/handler :sub :rf.xray/segment-inspector-path)))
    (is (nil? (rf.registrar/handler :sub :rf.xray/segment-inspector-value)))
    (is (nil? (rf.registrar/handler :sub :rf.xray/focused-slice-path)))
    (is (nil? (rf.registrar/handler :sub :rf.xray/show-me-when-this-changed-result)))
    ;; No composite diff family (no production view would consume it).
    (is (nil? (rf.registrar/handler :sub :rf.xray/selected-epoch-diff)))
    (is (nil? (rf.registrar/handler :sub :rf.xray/app-db-diff)))
    (is (nil? (rf.registrar/handler :sub :rf.xray/selected-epoch-redacted-modified-count)))
    (is (nil? (rf.registrar/handler :sub :rf.xray/selected-epoch-flow-writes)))
    ;; No pinned-slices subs.
    (is (nil? (rf.registrar/handler :sub :rf.xray/pinned-slices-store)))
    (is (nil? (rf.registrar/handler :sub :rf.xray/pinned-slices)))))

(deftest registry-installs-app-db-diff-events
  (testing "register-xray-handlers! installs the app-db tab's events,
            and none of the pin / unpin / reorder or segment-inspector
            open / close events, which nothing would dispatch. There are
            no clipboard copy events either; the clipboard fx is pinned
            below."
    (registry/register-xray-handlers!)
    ;; The positive control that the orchestrator really ran, so the
    ;; nil-asserts below are absence rather than a failed install.
    (is (some? (rf.registrar/handler :event :rf.xray/set-frame)))
    ;; No pin events.
    (is (nil? (rf.registrar/handler :event :rf.xray/pin-slice)))
    (is (nil? (rf.registrar/handler :event :rf.xray/unpin-slice)))
    (is (nil? (rf.registrar/handler :event :rf.xray/reorder-pinned-slices)))
    ;; No path-click events.
    (is (nil? (rf.registrar/handler :event :rf.xray/open-segment-inspector)))
    (is (nil? (rf.registrar/handler :event :rf.xray/close-segment-inspector)))
    (is (nil? (rf.registrar/handler :event :rf.xray/focus-slice-path)))
    (is (nil? (rf.registrar/handler :event :rf.xray/clear-slice-focus)))))

(deftest registry-installs-clipboard-fx
  (testing "register-xray-handlers! installs the :rf.xray.fx/copy-to-
            clipboard effect"
    (registry/register-xray-handlers!)
    (is (some? (rf.registrar/handler :fx :rf.xray.fx/copy-to-clipboard)))))

;; ---- (7) the off-box safe-egress projection ------------------------------
;;
;; These are the fail-closed proofs for `egress/egress-value`, Xray's single
;; named panel-local off-box projection. They call `egress-value` directly
;; and assert on its RETURN VALUE (there is no value-copy event to reach it
;; through).
;;
;; `egress.cljs` prescribes the projection as the MUST-use gesture any
;; panel affordance inherits, and the palette's `Snapshot app-db` verb uses
;; it, so its invariants need pins: a green suite that lost them would be
;; a silent fail-open.
;;
;; The invariants:
;; sensitive slot ⇒ :rf/redacted · large slot ⇒ :rf.size/large-elided ·
;; undeclared value passes through · nil :frame ⇒ whole value redacted ·
;; destroyed frame id ⇒ redacted · a frame registered under nil itself ⇒
;; redacted all the same.
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
  (testing "a value carrying a frame-declared sensitive slot
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
               "fail-open. text: " (pr-str text)))
      (is (re-find #"ada" text)
          "non-sensitive sibling survives the projection"))))

(deftest egress-value-size-elides-large-slot
  (testing "a value carrying a frame-declared :large slot is
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
  (testing "the projection is a no-op for a value with no
            sensitive/large declarations: the full value round-trips
            (fail-closed only bites declared slots)"
    (rf/make-frame {:id :rf/default})
    (is (= "{:a 1, :b [2 3]}"
           (pr-str (egress/egress-value {:a 1 :b [2 3]} {:frame :rf/default})))
        "an undeclared value projects verbatim")))

;; ---- (7b) no-target / stale-target egress FAILS CLOSED -------------------
;;
;; A panel affordance runs UNDER the live `:rf/xray` chrome frame. A bare
;; `(egress/egress-value value)` whenever the observed frame is nil or not
;; live would therefore resolve `:rf/xray`, apply its normally-EMPTY
;; declaration registry, and pass the value through RAW.
;; `elide-wire-value`'s frameless arm can only be reached by NAMING a frame
;; that does not resolve — which is why `egress.cljs` requires a caller to
;; forward `:frame` unconditionally, including when it is nil.

(deftest egress-value-with-nil-frame-fails-closed
  (testing "an explicitly-nil :frame (the unselected picker)
            redacts the value whole rather than projecting it under the
            Xray chrome frame's empty policy"
    (let [out (egress/egress-value {:auth {:token "shh"}} {:frame nil})]
      (is (= :rf/redacted out)
          (str "no resolvable frame must egress the whole-value redaction "
               "sentinel. got: " (pr-str out)))
      (is (not (re-find #"shh" (pr-str out)))
          (str "the RAW value survived with no frame policy in force "
               "— got: " (pr-str out))))))

(deftest egress-value-with-destroyed-frame-fails-closed
  (testing "a host frame destroyed between render and use
            leaves a STALE observed id; a frame-id that does not resolve
            fails closed exactly like the nil case"
    (rf/make-frame {:id :rf/default})
    ;; The inspected frame goes away while the caller still names it.
    (rf/destroy-frame! :rf/default)
    (let [out (egress/egress-value {:auth {:token "shh"}} {:frame :rf/default})]
      (is (= :rf/redacted out)
          (str "a stale frame id must egress the whole-value redaction "
               "sentinel. got: " (pr-str out)))
      (is (not (re-find #"shh" (pr-str out)))
          (str "the RAW value survived under a destroyed frame. "
               "got: " (pr-str out))))))

(deftest egress-value-with-nil-frame-is-unregistrable-and-fails-closed
  (testing "an explicitly-nil :frame must redact
            whole, and nil is the one frame id an app CANNOT make resolve.
            Substituting a fake id for the nil would not do: any
            substitute is itself a
            registrable value, so an app could register a live frame under it,
            take the walker's live-frame branch, and pass the value through RAW
            under that frame's empty registry. The walker believes the nil,
            and guards its live-frame arm with `(some? frame-id)`, so no
            registration can reach it"
    ;; The app's own frame holds the secret and declares it sensitive — but
    ;; it is not the frame named here, so its policy is not what governs.
    (rf/make-frame {:id :rf/default})
    (rf/with-frame :rf/default (seed-sensitive-schema!))
    ;; Attempt that registration aimed
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
                   "— got: " (pr-str out))))
        (finally
          (when registered?
            (try (rf/destroy-frame! nil) (catch :default _ nil))))))))

;; ---- (8) view renders — current-state inspector -------------------------
;;
;; The app-db tab is a CURRENT-STATE inspector, not a diff. The Panel
;; renders `app-db-diff-state/state-body` over the observed frame's live
;; app-db: a TOP user-domain section + one section per reserved `:rf/*`
;; area (machines/spawned fan out per id; route + slices are singletons;
;; absent/empty areas render an empty-state). There are no diff /
;; focus-result / redacted-chip view affordances, and no data subs for
;; them either (the registry test above pins their absence).

(deftest panel-renders-current-state-container
  (testing "the Panel renders the current-state inspector container +
            the TOP user-domain section over the observed frame's db"
    (seed-host-frame! {:counter 5 :user {:name "ada"}})
    (registry/register-xray-handlers!)
    (rf/make-frame {:id :rf/xray})
    (rf/with-frame :rf/xray
      (let [tree (panel-tree)]
        (is (some? (find-by-testid tree "rf-xray-app-db-diff"))
            "panel root present")
        (is (some? (find-by-testid tree "rf-xray-app-db-state"))
            "current-state inspector body present")
        (is (some? (find-by-testid tree "rf-xray-app-db-state-top"))
            "TOP user-domain section present")
        ;; No diff machinery on this view.
        (is (nil? (find-by-testid tree "rf-xray-diff-sections"))
            "no diff sections")
        (is (nil? (find-by-testid tree "rf-xray-app-db-diff-slices"))
            "no slice stack")))))

(deftest panel-sections-reserved-areas
  (testing "reserved runtime subsystems render as their own sections:
            machines fan out one section per machine id; route is a
            singleton. EP-0001: the runtime subsystems live
            in the runtime-db partition at [:rf.runtime/...], NOT in an
            app-db :rf/runtime container."
    (seed-host-frame! {:cart {:items [{:id 7}]}})
    (seed-host-runtime-db! {:rf.runtime/routing  {:current {:route-id :app/cart}}
                            :rf.runtime/machines {:snapshots {:auth       {:state :idle}
                                                              :title/flow {:state :playing}}}})
    (registry/register-xray-handlers!)
    (rf/make-frame {:id :rf/xray})
    ;; EP-0002 — select the host `:rf/default` frame as the
    ;; observed target explicitly (it does not default).
    (rf/with-frame :rf/xray
      (rf/dispatch-sync [:rf.xray/set-target-frame :rf/default]))
    (rf/with-frame :rf/xray
      (let [tree (panel-tree)]
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
  (testing "absent reserved areas are OMITTED from the
            panel entirely; no placeholder cards. Visibility is
            data-driven — a card appears when state accrues at that
            slot, never as a persistent 'No X' placeholder."
    (seed-host-frame! {:counter 1})
    (registry/register-xray-handlers!)
    (rf/make-frame {:id :rf/xray})
    ;; EP-0002 — select the host frame as observed target so
    ;; the panel actually projects against it (the no-placeholder check is
    ;; meaningful only when a target is selected).
    (rf/with-frame :rf/xray
      (rf/dispatch-sync [:rf.xray/set-target-frame :rf/default]))
    (rf/with-frame :rf/xray
      (let [tree (panel-tree)]
        (doseq [area [:rf/machines :rf/spawned :rf/route
                      :rf/pending-navigation :rf/elision]]
          (is (nil? (find-by-testid
                      tree (str "rf-xray-app-db-state-area-" (pr-str area))))
              (str "no placeholder card for empty reserved area " area)))))))

;; ---- App-db panel follows picker / focused frame ------------------------

(deftest observed-frame-follows-rf-xray-set-frame
  (testing "the frame-picker dispatches `:rf.xray/set-frame`
            which writes `[:focus :frame]`. The App-db panel's
            `:rf.xray/observed-frame` sub picks the focused frame up,
            rather than staying on the default regardless of picker
            selection."
    (registry/register-xray-handlers!)
    (rf/make-frame {:id :rf/xray})
    (rf/make-frame {:id :rf/default})
    (rf/make-frame {:id :checkout-frame})
    (rf/with-frame :rf/xray
      ;; EP-0002 — cold-start: observed frame is UNSELECTED
      ;; (nil) before any picker selection lands, NOT a synthesised
      ;; `:rf/default`.
      (is (nil? @(rf/subscribe [:rf.xray/observed-frame])))
      ;; User picks :checkout-frame in the ribbon dropdown.
      (rf/dispatch-sync [:rf.xray/set-frame :checkout-frame])
      (is (= :checkout-frame @(rf/subscribe [:rf.xray/observed-frame]))
          "observed-frame sub follows the picker"))))

(deftest current-state-view-follows-picker-selected-frame
  (testing "the current-state inspector reads the
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
      (let [tree (panel-tree)
            top  (find-by-testid tree "rf-xray-app-db-state-top")]
        (is (some? top) "TOP section present")
        (is (= :checkout-frame @(rf/subscribe [:rf.xray/observed-frame]))
            "inspector observes the picker-selected frame")
        (is (= {:checkout {:step :payment}}
               @(rf/subscribe [:rf.xray/target-frame-db]))
            "the observed db is the picked frame's live value")))))

;; ---- picker change resets epoch-history slot ----------------------------
;;
;; `:rf.xray/observed-frame` + `:rf.xray/target-frame-db` follow the
;; picker, and the App-DB panel's focused-epoch sub chain reads off
;; `:rf.xray/epoch-history` — an Xray-side slot keyed on the
;; `:target-frame` axis. A picker that left that axis alone would leave
;; the slot on the previous (likely empty `:rf/default`) frame's history
;; after a picker change, and the panel would render the boot
;; empty-state "app-db for :cart-frame is at the boot value. No diffs
;; yet." EVEN WITH a focused cascade in the picked frame.
;;
;; `spine/set-frame-reducer` aligns the two axes — the picker also writes
;; `:target-frame` and re-seeds `:epoch-history` from the framework's
;; per-frame ring. This regression guard asserts the alignment.

(deftest set-frame-aligns-target-frame-and-resets-epoch-history
  (testing "picker writes `:target-frame` + clears the
            `:epoch-history` slot so future `:rf.xray/epoch-recorded`
            callbacks pump the picked frame's epochs into the right
            slot. A slot left keyed to the previous target frame would
            render the App-DB panel's empty-state with a focused cascade
            present."
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
  (testing "after a picker change and a history reseed, the
            App-DB panel resolves the cart-frame's focused epoch (not
            the boot empty-state): with the axes aligned, focused
            cascades in the picked frame produce a real focused-epoch
            read-model through the panel's primary sub
            `:rf.xray/app-db-current+diff`."
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
             state")
        (is (= {:cart {:items [{:id 7}]}} (:value data))
            ":value is the focused epoch's :db-after")
        (is (= :cart-frame @(rf/subscribe [:rf.xray/observed-frame]))
            "observed-frame reflects the picker selection")))))

;; ---- (9) `:instance-id` — naming one of two live panels -----------------
;;
;; The edn-inspector's per-mount store is keyed by `[frame-id
;; mount-id]`, which separates two panels under two `frame-provider`s and
;; cannot separate two under ONE — a Fresco boundary has no per-instance
;; storage its body may use, so the distinction is the caller's to make.
;; `Panel` takes an optional `:instance-id` prop for it and hands it to
;; `panel-tree`; what the ids then compose to, and the lifecycle and width
;; consequences of getting it wrong, are pinned in
;; `app_db_diff_state_cljs_test`'s closing block (positive half plus the
;; defect as an explicit negative control). These two rows pin the THREADING
;; — that the prop reaches the sections at all, and that the two doors into
;; the boundary both carry it.

(deftest panel-tree-threads-instance-id-to-the-sections
  (testing "`panel-tree` hands its `:instance-id` down to
            `state-body`, so two instances of the panel over one frame's
            app-db render two disjoint sets of widget mount-ids. The
            2-arity is the single-mount call."
    (seed-host-frame! {:counter 5 :user {:name "ada"}})
    (registry/register-xray-handlers!)
    (rf/make-frame {:id :rf/xray})
    ;; EP-0002 — the observed target does not default, and
    ;; without it the panel projects against nothing: the TOP section
    ;; renders (it is the panel's anchor) but with the EMPTY-STATE body, so
    ;; no edn-inspector mounts at all. That is exactly the silence the
    ;; control below is here to separate from real separation.
    (rf/with-frame :rf/xray
      (rf/dispatch-sync [:rf.xray/set-target-frame :rf/default]))
    (rf/with-frame :rf/xray
      (let [mount-ids widget-mount-ids
            plain (mount-ids (panel-tree))
            left  (mount-ids (panel-tree "left"))
            right (mount-ids (panel-tree "right"))]
        (is (seq plain)
            "control: the panel mounts at least one edn-inspector widget, so
             an empty intersection below means separation and not silence")
        (is (= (count plain) (count left) (count right))
            "naming an instance changes the ids, never the sections")
        (is (nil? (some (set left) right))
            "no mount-id survives from one named instance to the other")
        (is (= plain
               (mount-ids
                 (app-db-diff/panel-tree
                   @(rf/subscribe [:rf.xray/app-db-state])
                   (:epoch-id @(rf/subscribe [:rf.xray/app-db-current+diff])))))
            "and the 2-arity — the single-mount call — composes exactly
             what a nil `:instance-id` does")))))

(defn- crossed-instance-id
  "THE VALUE THAT ACTUALLY ARRIVES AT THE BOUNDARY when a Reagent parent
  mounts the bridge — read off a real React element rather than off the
  hiccup the bridge returned.

  `reagent.core/as-element` runs the crossing the shell runs: `[:>]`
  camelCases each top-level key and puts each VALUE through Reagent's
  `convert-prop-value` before React sees it, and `rf.fresco/as-component`'s
  `outward-props` then decodes the key back and takes the value AS IT
  FINDS IT. So this is the whole of what the boundary's `:instance-id`
  can be, and asserting on the bridge's pre-conversion hiccup map would
  inspect a value that no mount ever sees.

  The bridge passes exactly ONE prop, so the single key is read without
  naming its camelCased spelling; the arity assertion below is what keeps
  that from silently reading the wrong slot."
  [props]
  (let [el (r/as-element (app-db-diff/Panel-bridge props))
        p  (.-props el)
        ks (js/Object.keys p)]
    (when (= 1 (alength ks))
      (unchecked-get p (aget ks 0)))))

(deftest panel-bridge-carries-the-instance-id-across-the-reagent-door
  (testing "the Reagent-facing bridge passes `:instance-id`
            through to the React component, and its 0-arity — the shell's
            `[(:panel tab)]` and `render-panel!`'s `[panel-view]` —
            mounts with no props at all."
    (let [named   (app-db-diff/Panel-bridge {:instance-id "left"})
          unnamed (app-db-diff/Panel-bridge)]
      (is (= :> (first named))
          "an interop vector onto the boundary's React component")
      (is (= 1 (count (nth named 2)))
          "control: exactly one prop crosses, so `crossed-instance-id`
           reading the single key cannot be reading the wrong slot")
      (is (= "left" (crossed-instance-id {:instance-id "left"}))
          "the caller's instance name reaches the component's props")
      (is (= {} (nth unnamed 2))
          "and the 0-arity mounts with no props"))))

;; ---- (9b) a NAMESPACED keyword must survive the crossing -----------------
;;
;; A keyword `:instance-id` is accepted alongside a string, and
;; the two doors into the boundary disagree about what survives: a Fresco
;; body hands a keyword over as a keyword, a Reagent parent's `[:>]`
;; converts the VALUE first. `instance-token` reads a keyword with
;; `(subs (str id) 1)`, which keeps the namespace — so the Fresco door
;; composes `left/panel` from `:left/panel`.
;;
;; The Reagent door, unaided, would not. Reagent 2.0.1's `convert-prop-value`
;; converts a named value with `cljs.core/name`, which DROPS the namespace,
;; so `:left/panel` and `:right/panel` would both arrive at the boundary as
;; `"panel"` — two panels the caller had deliberately named apart sharing
;; one `mount-id`, one width slot and one expansion/zoom `:site-id`, which
;; is the same-frame collision `:instance-id` exists to prevent. The
;; asymmetry is what would make it a contract violation rather than a
;; quirk: the contract accepts keywords without excluding namespaces and
;; promises the two doors behave alike.
;;
;; So the bridge normalises the prop to its token BEFORE the
;; crossing, through the SAME `instance-token` the boundary uses — so a
;; string crosses (which Reagent preserves) and the two doors compose one
;; answer. These rows assert on what the mounts RECEIVE, never on what was
;; passed: a row reading the bridge's own hiccup map would pass while both
;; mounts collide.

(deftest ns4bsq-namespaced-keyword-instance-id-survives-the-reagent-crossing
  (testing "two namespaced keywords that differ only in their
            NAMESPACE arrive at the boundary as two different values."
    (let [left  (crossed-instance-id {:instance-id :left/panel})
          right (crossed-instance-id {:instance-id :right/panel})]
      (is (= "left/panel"  left))
      (is (= "right/panel" right))
      (is (not= left right)
          "THE CLAIM: the namespace is what tells these two panels apart,
           and it reaches the boundary. Unnormalised, both would read
           \"panel\" — Reagent's `convert-prop-value` names a keyword")
      (is (= ["left/panel" "right/panel"]
             [(crossed-instance-id {:instance-id "left/panel"})
              (crossed-instance-id {:instance-id "right/panel"})])
          "live control: plain strings cross distinctly with or without the
           normalisation, so the instrument reads the CROSSING and is not merely
           echoing what it was handed")
      (is (= "left" (crossed-instance-id {:instance-id :left}))
          "and an UNQUALIFIED keyword crosses as its bare name")))

  (testing "and the two entry paths AGREE. What a Reagent parent
            gets through `[:>]` composes the same ids a Fresco parent gets
            by handing the keyword straight to the boundary."
    (seed-host-frame! {:counter 5 :user {:name "ada"}})
    (registry/register-xray-handlers!)
    (rf/make-frame {:id :rf/xray})
    (rf/with-frame :rf/xray
      (rf/dispatch-sync [:rf.xray/set-target-frame :rf/default]))
    (rf/with-frame :rf/xray
      (let [;; the Reagent door: the value the crossing actually delivered
            via-reagent (fn [kw] (widget-mount-ids
                                   (panel-tree (crossed-instance-id
                                                 {:instance-id kw}))))
            ;; the Fresco door: the keyword reaches the body unconverted
            via-fresco  (fn [kw] (widget-mount-ids (panel-tree kw)))
            left-r  (via-reagent :left/panel)
            right-r (via-reagent :right/panel)]
        (is (seq left-r)
            "control: the panel mounts at least one widget, so a disjoint
             intersection below is separation and not silence")
        (is (= (via-fresco :left/panel)  left-r)
            "the two doors compose ONE set of ids for `:left/panel`")
        (is (= (via-fresco :right/panel) right-r)
            "and for `:right/panel`")
        (is (nil? (some (set left-r) right-r))
            "THE GATE: no mount-id survives from one namespaced instance to
             the other. The store key, the width slot and the `:site-id`
             are all derived from this string, so one shared id is the
             whole collision — unnormalised, these two sets would be
             IDENTICAL, both composed from \"panel\"")))))
