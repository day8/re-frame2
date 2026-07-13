(ns re-frame.ui.reactive-root-retention-cljs-test
  "rf2-mc62sp — root-incarnation membership must not RETAIN ordinarily-
  unmounted ViewCells.

  An ordinary React reconciliation unmount (conditional subtree, route
  change, keyed-list eviction) runs only the effect cleanup → `disconnect!`,
  which deliberately never detaches (hide vs unmount are indistinguishable
  there — 03 §4). A STRONG `root-cells` set therefore pinned every such cell —
  with its retained committed site values — until the whole root tore down:
  unbounded production memory growth per UI churn, contradicting 03 §4's
  ':disconnected … if React does not reconnect the cell, the cell is garbage'.

  Membership is now WEAK (WeakHashMap keyset on the JVM; js/WeakRef set +
  FinalizationRegistry reaper on CLJS), preserving BOTH registry consumers:

    - lease/root/frame TEARDOWN DISCOVERY of hidden cells — a genuinely
      Activity-hidden cell is strongly reachable from React's retained fiber
      (`use-cell`'s useRef), so its weak entry lives exactly as long as
      Activity retention and `teardown-root!`/`teardown-frame!` still find it
      (rf2-vxgfnd.85);
    - the Xray EVIDENCE PROJECTION (rf2-vxgfnd.75), which reads the per-cell
      `:root` field (`reactive/cell-root`) — never this registry — and is
      structurally unaffected (its own suite pins that).

  The leak proof forces GC on the JVM (the graft-checked host where weak
  clearing is deterministic under System/gc); the cross-host fixtures pin the
  survive-hide / deterministic-teardown semantics of the weak rewrite on both
  hosts. `.cljc` ending `-cljs-test` runs on node (`test:cljs`) AND JVM
  (`clojure -M:test`)."
  (:require #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
               :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
            [re-frame.core                 :as rf]
            [re-frame.frame                :as frame]
            [re-frame.live-frame           :as live-frame]
            [re-frame.test-support         :as test-support]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.ui.reactive          :as reactive]))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture {:adapter plain-atom/adapter})
  (fn [f]
    (reactive/reset-scheduler!)
    (try (f) (finally (reactive/reset-scheduler!)))))

(defn- make-frame! [id db]
  (live-frame/make-frame {:id id})
  (frame/replace-app-db! id db)
  id)

(defn- render+commit! [cell fid queries]
  (let [[_ capture] (rf/with-frame fid
                      (reactive/with-capture
                       cell (fn [] (mapv (fn [i q]
                                          (reactive/sub-read [:ret/site i] q))
                                        (range) queries))))]
    (reactive/commit! cell capture))
  cell)

(defn- mount!
  "Graft analogue of a real mount UNDER a root: mint a cell, attach it to
  `incarnation`, commit it connected under `fid` observing `queries`."
  [vid incarnation fid queries]
  (let [cell (reactive/make-cell vid)]
    (reactive/attach-root! cell incarnation)
    (render+commit! cell fid queries)))

;; ===========================================================================
;; Weak membership keeps the rf2-vxgfnd.85 semantics: survives a hide,
;; deterministic departure on teardown!, entry dropped with the last member
;; ===========================================================================

(deftest membership-survives-hide-and-drops-deterministically-on-teardown
  (rf/reg-sub :ret/a (fn [db _] (:a db)))
  (let [fid         (make-frame! :ret/frame {:a 1})
        incarnation (reactive/make-root-incarnation)
        base        (reactive/root-cell-count)
        cell        (mount! ::v incarnation fid [[:ret/a]])]
    (testing "attached: one live member under the incarnation"
      (is (= 1 (reactive/root-cell-count incarnation)))
      (is (identical? incarnation (reactive/cell-root cell))
          "the projection's per-cell :root read is untouched by weak membership"))
    (testing "an Activity hide does NOT drop membership (the whole point)"
      (reactive/disconnect! cell)
      (is (= :disconnected (reactive/lifecycle cell)))
      (is (= 1 (reactive/root-cell-count incarnation))
          "the hidden cell — strongly retained by its fiber/test handle —
           stays discoverable through the weak registry"))
    (testing "final teardown! leaves deterministically (the fast path)"
      (reactive/teardown! cell)
      (is (= 0 (reactive/root-cell-count incarnation)))
      (is (= base (reactive/root-cell-count))
          "the incarnation entry dropped with its last member (AC5)"))))

(deftest hidden-cells-stay-discoverable-for-root-teardown
  (rf/reg-sub :ret/a (fn [db _] (:a db)))
  (let [fid         (make-frame! :ret/frame {:a 1})
        incarnation (reactive/make-root-incarnation)
        hidden      (mount! ::hidden incarnation fid [[:ret/a]])]
    ;; Hidden BEFORE any teardown window exists — the .85 shape. The test
    ;; handle stands in for React's retained fiber (Activity keeps the fiber,
    ;; so a real hidden cell is strongly reachable exactly like this).
    (reactive/disconnect! hidden)
    (is (= :disconnected (reactive/lifecycle hidden)))
    (testing "root teardown still reaps the hidden cell through weak membership"
      (reactive/teardown-root! incarnation (fn [] nil))
      (is (= :dead (reactive/lifecycle hidden))
          "lease-teardown discovery of hidden-but-alive cells is preserved")
      (is (= 0 (reactive/root-cell-count incarnation))))))

;; ===========================================================================
;; The leak fixture (JVM — deterministic weak clearing under System/gc):
;; ordinary reconciliation unmounts must leave COLLECTABLE cells, while a
;; hidden sibling under the same root stays retained + reapable
;; ===========================================================================

#?(:clj
   (defn- gc-until
     "Hint GC (bounded retries) until `pred` returns true; returns its final
     value. Weakly-reachable objects clear on the first full GC in practice."
     [pred]
     (loop [i 0]
       (cond
         (pred)     true
         (>= i 100) (pred)
         :else      (do (System/gc)
                        (Thread/sleep 10)
                        (recur (inc i)))))))

#?(:clj
   (deftest reconciliation-unmounted-cells-are-garbage-not-root-retained
     (rf/reg-sub :ret/a (fn [db _] (:a db)))
     (let [fid         (make-frame! :ret/frame {:a 1})
           incarnation (reactive/make-root-incarnation)
           n           32
           ;; one genuinely hidden sibling under the SAME root — must survive
           hidden      (doto (mount! ::hidden incarnation fid [[:ret/a]])
                         (reactive/disconnect!))
           ;; churn: mount + ordinary reconciliation unmount, keyed-list
           ;; style. Hold each churned cell only WEAKLY once its cycle ends —
           ;; React drops the fiber, so nothing else references it (its
           ;; leases were already released at disconnect!).
           refs        (mapv (fn [i]
                               (let [cell (mount! (keyword "ret" (str "row-" i))
                                                  incarnation fid [[:ret/a]])]
                                 (reactive/disconnect! cell)
                                 (java.lang.ref.WeakReference. cell)))
                             (range n))]
       (is (= (+ n 1) (reactive/root-cell-count incarnation))
           "precondition: every churned cell is (weakly) enrolled pre-GC")
       (testing "the churned population is GARBAGE — collectable, not pinned
                 by root ownership (rf2-mc62sp; red under strong membership)"
         (is (true? (gc-until #(every? (fn [^java.lang.ref.WeakReference r]
                                         (nil? (.get r)))
                                       refs)))
             "every reconciliation-unmounted cell was collected — root-cells
              no longer strongly retains the ordinary-unmount population")
         (is (= 1 (reactive/root-cell-count incarnation))
             "membership returned to baseline: exactly the hidden sibling"))
       (testing "…while the hidden sibling stayed reapable for lease teardown"
         (is (= :disconnected (reactive/lifecycle hidden)))
         (reactive/teardown-root! incarnation (fn [] nil))
         (is (= :dead (reactive/lifecycle hidden)))
         (is (= 0 (reactive/root-cell-count incarnation)))))))
