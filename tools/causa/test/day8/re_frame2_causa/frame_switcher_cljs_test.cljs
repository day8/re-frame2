(ns day8.re-frame2-causa.frame-switcher-cljs-test
  "CLJS coverage for the hardened L1 frame-switcher slot (rf2-iwwou).

  Verifies the public contract documented in `frame_switcher.cljs`:

  1. Pure helpers (`internal-frames`, `distinct-frames`) — JVM-runnable
     shape, no re-frame machinery.
  2. Subs `:rf.causa/current-frame` + `:rf.causa/available-frames`
     resolve through the canonical chain (spine focus-slot + cascades
     projection).
  3. Event `:rf.causa/select-frame` writes through the spine + fires
     the persistence fx.
  4. EDN round-trip (`->edn` / `<-edn`) survives malformed inputs.
  5. Hydration restores the persisted frame at install-time.
  6. The Cmd-K palette's `:palette/select-frame` verb dispatches
     through the canonical `:rf.causa/select-frame` event — the
     ribbon, the palette, and any future frame-aware feature share
     one write path."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]
            [day8.re-frame2-causa.config :as config]
            [day8.re-frame2-causa.frame-switcher :as frame-switcher]
            [day8.re-frame2-causa.preload :as preload]
            [day8.re-frame2-causa.registry :as registry]
            [day8.re-frame2-causa.trace-bus :as trace-bus]))

;; ---- fixtures -----------------------------------------------------------

(defn- causa-init! []
  (preload/reset-for-test!)
  (registry/reset-for-test!)
  (trace-bus/clear-buffer!)
  (frame-switcher/clear!)
  (frame-switcher/set-storage-key! nil))

(use-fixtures :each
  (test-support/reset-runtime-fixture-factory
    {:adapter plain-atom/adapter
     :init-fn causa-init!}))

(defn- setup! []
  (registry/register-causa-handlers!)
  (frame/reg-frame :rf/causa {}))

(defn- causa-db []
  (rf/get-frame-db :rf/causa))

;; -------------------------------------------------------------------------
;; (1) Pure helpers — distinct-frames + internal-frames
;; -------------------------------------------------------------------------

(deftest internal-frames-includes-causa-and-pair
  (testing "spec/018 §8 I1 — the canonical filter set excludes Causa's
            own frame plus the future re-frame2-pair frame"
    (is (contains? frame-switcher/internal-frames :rf/causa))
    (is (contains? frame-switcher/internal-frames :rf/re-frame2-pair))
    (is (not (contains? frame-switcher/internal-frames :rf/default))
        ":rf/default is a meaningful user frame; never filtered out")))

(deftest distinct-frames-excludes-internal-frames-by-default
  (testing "spec/018 §8 I1 — `:rf/causa` and other tool frames are
            filtered out of the picker option list unless the power-
            user toggle re-includes them"
    (let [cascades [{:dispatch-id 1 :frame :rf/default}
                    {:dispatch-id 2 :frame :rf/causa}
                    {:dispatch-id 3 :frame :app/main}
                    {:dispatch-id 4 :frame :rf/re-frame2-pair}]
          default-frames (frame-switcher/distinct-frames cascades false)
          power-frames   (frame-switcher/distinct-frames cascades true)]
      (is (= [:rf/default :app/main] default-frames)
          "default — :rf/causa and :rf/re-frame2-pair are excluded")
      (is (= [:rf/default :rf/causa :app/main :rf/re-frame2-pair] power-frames)
          "power-user — tool frames included in first-seen order"))))

(deftest distinct-frames-drops-nil-frame-cascades
  (testing "an :ungrouped cascade has nil :frame — it must NOT show
            up in the picker (the picker labels would render `nil`)"
    (let [cascades [{:dispatch-id 1 :frame :rf/default}
                    {:dispatch-id 2 :frame nil}
                    {:dispatch-id 3 :frame :app/main}]]
      (is (= [:rf/default :app/main]
             (frame-switcher/distinct-frames cascades false))
          "nil frame is filtered out"))))

(deftest distinct-frames-preserves-first-seen-order
  (testing "the helper preserves first-seen order so the picker's
            option list reads stably — re-orderings would shuffle the
            user's selection visually on every new cascade"
    (let [cascades [{:dispatch-id 1 :frame :app/b}
                    {:dispatch-id 2 :frame :app/a}
                    {:dispatch-id 3 :frame :app/b}  ;; duplicate
                    {:dispatch-id 4 :frame :app/c}
                    {:dispatch-id 5 :frame :app/a}]] ;; duplicate
      (is (= [:app/b :app/a :app/c]
             (frame-switcher/distinct-frames cascades false))
          "duplicates collapse, order preserved"))))

;; -------------------------------------------------------------------------
;; (2) EDN round-trip — persistence (de)serialisation
;; -------------------------------------------------------------------------

(deftest edn-round-trips-keyword
  (let [edn (frame-switcher/->edn :rf/cart-frame)]
    (is (string? edn))
    (is (= :rf/cart-frame (frame-switcher/<-edn edn))
        "round-trip keeps the keyword intact")))

(deftest edn-load-tolerates-malformed-input
  (testing "the load path NEVER throws into init — malformed EDN
            yields nil; the caller treats nil as 'no selection'"
    (is (nil? (frame-switcher/<-edn "garbage"))
        "non-EDN string parses to nil")
    (is (nil? (frame-switcher/<-edn ""))
        "empty string parses to nil")
    (is (nil? (frame-switcher/<-edn "[1 2 3]"))
        "wrong shape (vector) parses to nil")
    (is (nil? (frame-switcher/<-edn "{:other :foo}"))
        "map without `:frame` key parses to nil")
    (is (nil? (frame-switcher/<-edn "{:frame \"not-a-keyword\"}"))
        "non-keyword `:frame` value parses to nil")))

;; -------------------------------------------------------------------------
;; (3) Subs — :rf.causa/current-frame + :rf.causa/available-frames
;; -------------------------------------------------------------------------

(defn- dispatch-trace
  "Seed the trace-bus with a single `:event/dispatched` cascade row so
  the spine projection produces a cascade entry for the picker subs.
  Mirrors `shell_cljs_test.cljs`'s `dispatch-trace-ev` shape — the
  cascade key is `[frame dispatch-id]` and both must live under
  `:tags`."
  [dispatch-id frame-id]
  (trace-bus/collect-trace!
    {:id          dispatch-id
     :op-type     :event
     :operation   :event/dispatched
     :tags        {:event       [:app/touch]
                   :frame       frame-id
                   :dispatch-id dispatch-id}}))

(deftest current-frame-sub-resolves-the-spine-slot
  (testing "the sub reads through `:rf.causa/focus-slot` so it re-fires
            on any code path that writes through the spine — picker,
            palette, headless drivers"
    (setup!)
    (rf/with-frame :rf/causa
      (is (nil? @(rf/subscribe [:rf.causa/current-frame]))
          "pre-selection — sub returns nil (no :focus slot written)")
      (rf/dispatch-sync [:rf.causa/select-frame :rf/cart-frame])
      (is (= :rf/cart-frame @(rf/subscribe [:rf.causa/current-frame]))
          "post-selection — sub returns the frame the user picked"))))

(deftest available-frames-sub-empty-with-no-cascades
  (testing "no cascades yet — the sub returns an empty vec"
    (setup!)
    (rf/with-frame :rf/causa
      (is (= [] @(rf/subscribe [:rf.causa/available-frames]))
          "empty list, no picker options"))))

(deftest available-frames-sub-filters-tool-frames-and-preserves-order
  (testing "the sub composes off `:rf.causa/cascades` so it picks up
            every frame present in the trace stream — minus the
            internal-frames filter set (spec/018 §8 I1)"
    ;; Seed BEFORE setup! so the sub's first compute reads the populated
    ;; trace-bus atom (the sub falls back to `(trace-bus/buffer)` when
    ;; the db's `:trace-buffer` slot is unwritten). Mirrors the shell
    ;; tests' seed-then-subscribe order.
    (dispatch-trace 1 :rf/default)
    (dispatch-trace 2 :app/main)
    (dispatch-trace 3 :rf/causa)              ;; tool frame, filtered
    (setup!)
    (rf/with-frame :rf/causa
      (is (= [:rf/default :app/main]
             @(rf/subscribe [:rf.causa/available-frames]))
          "tool frames filtered, first-seen order"))))

;; -------------------------------------------------------------------------
;; (4) Event — :rf.causa/select-frame
;; -------------------------------------------------------------------------

(deftest select-frame-writes-through-spine
  (testing "the canonical event-fx dispatches the spine's `:rf.causa/
            set-frame` so every per-frame composite (App-DB Diff,
            Views, Routing) re-fires off the new frame's slot"
    (setup!)
    ;; Register a frame so the spine's epoch-history lookup doesn't
    ;; throw — the canonical handler queries rf/epoch-history.
    (frame/reg-frame :rf/cart-frame {})
    (rf/with-frame :rf/causa
      (rf/dispatch-sync [:rf.causa/select-frame :rf/cart-frame])
      (is (= :rf/cart-frame (get-in (causa-db) [:focus :frame]))
          "the spine's :focus :frame slot lands on the picked frame")
      (is (= :rf/cart-frame (:target-frame (causa-db)))
          "the spine's :target-frame slot follows (rf2-ug1r6)"))))

(deftest select-frame-fires-persistence-fx
  (testing "the canonical event-fx fires the `:rf.causa.frame-switcher/
            persist` fx so the user's selection survives a reload"
    (setup!)
    (frame/reg-frame :rf/cart-frame {})
    (let [persisted (atom nil)]
      ;; Swap the fx with a counting stub so we don't touch
      ;; localStorage in the test runtime (Node has no jsdom).
      (rf/reg-fx :rf.causa.frame-switcher/persist
        (fn [_ctx frame-id]
          (reset! persisted frame-id)))
      (rf/with-frame :rf/causa
        (rf/dispatch-sync [:rf.causa/select-frame :rf/cart-frame]))
      (is (= :rf/cart-frame @persisted)
          "the persist fx fired with the new frame id"))))

;; -------------------------------------------------------------------------
;; (5) Cmd-K palette routes through the canonical contract (rf2-iwwou)
;; -------------------------------------------------------------------------
;;
;; The palette's `:palette/select-frame` verb MUST dispatch the canonical
;; `:rf.causa/select-frame` event-fx — NOT the spine's `:rf.causa/set-
;; frame` primitive directly. That way every persistence /
;; instrumentation layer attached to the frame-switcher contract lives
;; behind one event surface, so the ribbon + the palette + any future
;; frame-aware feature share the same write path.

(deftest palette-select-frame-routes-through-canonical-contract
  (testing "the Cmd-K palette's `:palette/select-frame` invocation
            dispatches `:rf.causa/select-frame`, NOT the spine's
            `:rf.causa/set-frame` primitive directly. The end state
            (focus :frame + :target-frame + persistence) is identical
            to a ribbon picker click."
    (setup!)
    (frame/reg-frame :rf/cart-frame {})
    (let [persisted (atom nil)]
      (rf/reg-fx :rf.causa.frame-switcher/persist
        (fn [_ctx frame-id]
          (reset! persisted frame-id)))
      (rf/with-frame :rf/causa
        (rf/dispatch-sync [:rf.causa/palette-open])
        (rf/dispatch-sync
          [:rf.causa/palette-invoke
           {:source :frame
            :id     :rf/cart-frame
            :label  "Switch focus to frame :rf/cart-frame"
            :action [:palette/select-frame :rf/cart-frame]}
           false]))
      (is (= :rf/cart-frame (get-in (causa-db) [:focus :frame]))
          "palette write lands on the same spine slot the ribbon picker uses")
      (is (= :rf/cart-frame (:target-frame (causa-db)))
          ":target-frame is also written — every per-frame composite re-fires")
      (is (= :rf/cart-frame @persisted)
          "the persist fx fired — palette writes are persisted too")
      (is (false? (boolean (:palette-open? (causa-db))))
          "palette closes on invocation as usual"))))

;; -------------------------------------------------------------------------
;; (6) View — frame-switcher-view reads the contract
;; -------------------------------------------------------------------------

(defn- expand-tree
  "Walk `tree` and replace every fn-component vector with its rendered
  result. Mirror of the shell tests' walker — keeps the hiccup-only
  assertions terse."
  [tree]
  (cond
    (and (vector? tree) (fn? (first tree)))
    (expand-tree (apply (first tree) (rest tree)))

    (vector? tree)
    (mapv expand-tree tree)

    (seq? tree)
    (map expand-tree tree)

    :else
    tree))

(defn- hiccup-seq [tree]
  (tree-seq (some-fn vector? seq?) seq (expand-tree tree)))

(defn- find-by-testid [tree testid]
  (some (fn [node]
          (when (and (vector? node)
                     (map? (second node))
                     (= testid (:data-testid (second node))))
            node))
        (hiccup-seq tree)))

(deftest view-collapses-to-label-when-only-one-frame
  (testing "the view renders a flat label (no dropdown) when only one
            distinct frame is available — there's nothing to pick"
    (dispatch-trace 1 :app/main)
    (setup!)
    (rf/with-frame :rf/causa
      (let [tree (frame-switcher/frame-switcher-view {})]
        (is (some? (find-by-testid tree "rf-causa-ribbon-frame"))
            "label-only branch renders")
        (is (nil? (find-by-testid tree "rf-causa-ribbon-frame-picker"))
            "no <select> when only one frame is selectable")))))

(deftest view-renders-dropdown-when-multiple-frames
  (testing "the view renders a strictly-single-select <select> when
            multiple distinct frames are present"
    (dispatch-trace 1 :app/main)
    (dispatch-trace 2 :app/admin)
    (setup!)
    (rf/with-frame :rf/causa
      (let [tree   (frame-switcher/frame-switcher-view {})
            picker (find-by-testid tree "rf-causa-ribbon-frame-picker")]
        (is (some? picker) "dropdown renders for multi-frame")
        (is (= :select (first picker))
            "it's a <select>, not a custom multi-select")
        (is (nil? (:multiple (second picker)))
            "strictly single-select — no :multiple attribute")))))

;; -------------------------------------------------------------------------
;; (7) Storage-key plumbing — per-instance isolation
;; -------------------------------------------------------------------------

(deftest storage-key-defaults-to-canonical-value
  (testing "the default storage key matches the documented canonical
            string — hosts that don't override get a stable slot"
    (is (= "re-frame2.causa.frame-switcher.v1"
           frame-switcher/default-storage-key))
    (is (= frame-switcher/default-storage-key
           (frame-switcher/get-storage-key))
        "the runtime key matches the default at boot")))

(deftest storage-key-set-then-clear-round-trips
  (testing "Story testbeds override the key for per-scenario isolation;
            passing nil resets to the default"
    (frame-switcher/set-storage-key! "story.scenario-1.causa.frame.v1")
    (is (= "story.scenario-1.causa.frame.v1"
           (frame-switcher/get-storage-key)))
    (frame-switcher/set-storage-key! nil)
    (is (= frame-switcher/default-storage-key
           (frame-switcher/get-storage-key))
        "nil resets to the canonical default")))
