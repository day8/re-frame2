(ns re-frame.ui.root-incarnation-wiring-cljs-test
  "rf2-vxgfnd.92 — the `client` root-incarnation WIRING on node (no real React).

  rf2-vxgfnd.85 (#5773) landed the substrate mechanism — per-cell root-incarnation
  ownership (`attach-root!` / `root-cells` / `make-root-incarnation`) and the
  incarnation-aware `teardown-root!` that reaps Activity-hidden cells — but the
  .85+.86 cluster was surface-restricted to reactive.cljc, so the REAL mount /
  unmount path did not mint, thread, or pass a root incarnation. .92 wires it:

    - `register-live-root!` MINTS one incarnation per Root (read via the entry);
    - `unmount!*` PASSES it to `teardown-root!` (the 2-arg arity), so a cell hidden
      BEFORE the unmount window — which the window can never capture — is still
      reaped through the root-incarnation ownership registry.

  This pins the client kernel with a FAKE Root (a plain JS object with an
  `unmount` method), mimicking what `viewcell/render` does at mount by enrolling
  a cell under the entry's incarnation via `attach-root!`. The real-React /
  real-DOM counterpart (context→attach + Activity hide) is the browser fixture
  `re-frame.ui.root-incarnation-activity-dom-cljs-test`."
  (:require [cljs.test :refer [deftest is testing use-fixtures]]
            [re-frame.core                 :as rf]
            [re-frame.frame                :as frame]
            [re-frame.live-frame           :as live-frame]
            [re-frame.test-support         :as test-support]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.ui.client            :as client]
            [re-frame.ui.reactive          :as reactive]))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture {:adapter plain-atom/adapter})
  (fn [f]
    (reactive/reset-scheduler!)
    (client/reset-live-roots!)
    (try (f) (finally (reactive/reset-scheduler!) (client/reset-live-roots!)))))

(defn- root-incarnation
  "The incarnation the client minted for live root `root-id`, read off the
  public registry entry (`register-live-root!` stores it there)."
  [root-id]
  (:root-incarnation (client/live-root-entry root-id)))

(defn- register!
  "Register a fake Root whose host `.unmount` runs `on-unmount`."
  [root-id on-unmount]
  (let [react-root #js {:unmount (fn [] (when on-unmount (on-unmount)))}
        container  (js-obj)
        root       (client/->Root react-root container root-id)]
    (client/register-live-root! {:root-id root-id :provenance :authored}
                                container root)
    root))

(defn- owned-cell!
  "Graft analogue of a real mount UNDER a root: a connected cell ENROLLED under
  the root's incarnation, exactly as `viewcell/render` does via `attach-root!`
  once it reads the root-incarnation context at mount (rf2-vxgfnd.92)."
  [root-id fid queries]
  (let [cell (reactive/make-cell ::v)]
    (reactive/attach-root! cell (root-incarnation root-id))
    (rf/with-frame fid
      (reactive/with-capture cell (fn [] (mapv reactive/sub-read queries))))
    (reactive/commit! cell)
    cell))

(deftest register-mints-a-fresh-incarnation-per-root
  (let [_a (register! :ri/root-a nil)
        _b (register! :ri/root-b nil)]
    (is (some? (root-incarnation :ri/root-a)) "root A got an incarnation")
    (is (some? (root-incarnation :ri/root-b)) "root B got an incarnation")
    (is (not (identical? (root-incarnation :ri/root-a)
                         (root-incarnation :ri/root-b)))
        "each root's incarnation is distinct")))

(deftest unmount-passes-the-incarnation-and-reaps-a-cell-hidden-before-the-window
  ;; The core .92 live-path proof: `unmount!*` passes the root incarnation, so a
  ;; cell Activity-hidden BEFORE the unmount window (which the window can never
  ;; capture) is still reaped through the ownership registry.
  (rf/reg-sub :ri/a (fn [db _] (:a db)))
  (live-frame/make-frame {:id :ri/frame})
  (frame/replace-app-db! :ri/frame {:a 1})
  (let [root (register! :ri/root nil)                  ;; host .unmount collects NOTHING
        cell (owned-cell! :ri/root :ri/frame [[:ri/a]])]
    (is (identical? (root-incarnation :ri/root) (reactive/cell-root cell))
        "the cell is enrolled under its root's incarnation (the mount seam)")
    (testing "an Activity hide BEFORE the window is a transient disconnect, still owned"
      (reactive/disconnect! cell)
      (is (= :disconnected (reactive/lifecycle cell)))
      (is (= 1 (reactive/root-cell-count (root-incarnation :ri/root)))
          "root ownership SURVIVES the hide"))
    (testing "unmount!* reaps the pre-hidden cell via the incarnation it passed"
      (is (nil? (client/unmount!* root)))
      (is (= :dead (reactive/lifecycle cell))
          "the pre-hidden cell is reaped — not left reconnectable after its root is gone")
      (is (= {:state :disconnected :reason :unmounted :proof :host-teardown}
             (peek (reactive/intervals cell))))
      (is (= #{} (client/live-root-ids)) "the claim is still released (contract §7)"))))

(deftest a-hide-without-unmount-stays-reconnectable
  ;; The discriminator: a hide with NO root teardown must stay reconnectable —
  ;; the ownership registry must never itself kill a merely-hidden cell.
  (rf/reg-sub :ri/a (fn [db _] (:a db)))
  (live-frame/make-frame {:id :ri/frame})
  (frame/replace-app-db! :ri/frame {:a 1})
  (let [_root (register! :ri/root nil)
        cell  (owned-cell! :ri/root :ri/frame [[:ri/a]])]
    (reactive/disconnect! cell)
    (is (= :disconnected (reactive/lifecycle cell)) "hidden, not :dead")
    (testing "a reveal reconnects and proves the interval an Activity hide"
      ;; GENUINE hide→reveal — settle models the host yield a real reveal spans;
      ;; an unsettled same-commit reconnect is a StrictMode dev replay and must
      ;; NOT be proven a hide (rf2-vxgfnd.44).
      (reactive/settle-disconnect! cell)
      (rf/with-frame :ri/frame
        (reactive/with-capture cell (fn [] (reactive/sub-read [:ri/a]))))
      (reactive/commit! cell)
      (is (= :connected (reactive/lifecycle cell)))
      (is (= {:state :disconnected :reason :activity-hidden :proof :reconnect}
             (peek (reactive/intervals cell)))
          "the registry did not kill a merely-hidden cell"))))
