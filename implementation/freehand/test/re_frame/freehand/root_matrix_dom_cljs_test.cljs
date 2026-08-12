(ns re-frame.freehand.root-matrix-dom-cljs-test
  "F6b matrix 7/8 — ROOT ergonomics and TEARDOWN, in a real browser, across
  BOTH execution modes (EP-0036 §6, gate row \"browser correctness\";
  acceptance 3 — cleanup assertions are EXACT counts).

  A root is where a declared view meets the host: `v/mount` puts a tree on
  a real container against a frame it either OWNS (ensured for the root's
  lifetime) or merely SCOPES (borrowed), and `v/unmount!` must leave
  exactly nothing of an owned root behind — no registry claim, no ledger
  record, no live subscription. `v/mount` is a browser fact and teardown is
  an ABSENCE, so this mounts and reads every count off a different place.

  The mode dimension: the same declaration mounts through the same
  `v/mount` in each mode. Each claim — a minimal root on screen, an owned
  frame torn down to the exact integer zero, and two roots of one
  declaration keeping independent per-occurrence identity — is asserted
  interpreted AND compiled, and the two build the same root DOM.

  Rides the browser lane through its `-dom-cljs-test` suffix; under node it
  has no DOM and says so."
  (:require [cljs.test :refer-macros [async deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.freehand :as v]
            [re-frame.freehand.mount-support :as ms]
            [re-frame.freehand.root :as root]
            [re-frame.freehand.root-views :as views]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]))

(def ^:private runtime-fixture
  (test-support/make-reset-runtime-fixture
    {:adapter       plain-atom/adapter
     :ambient-frame nil
     :async?        true}))

(use-fixtures :each
  {:before (fn [] (root/reset-registry!) ((:before runtime-fixture)))
   :after  (fn [] ((:after runtime-fixture)) (root/reset-registry!))})

;; ---------------------------------------------------------------------------
;; Compiled twins of the host-neutral root views (same body, marker only)
;; ---------------------------------------------------------------------------

(v/defview app-compiled
  {:compiled true}
  [{:keys [label]}]
  [:main#app label])

(v/defview counter-compiled
  {:compiled true}
  [_]
  [:p.count (str (v/sub [:root/n]))])

(v/defview panel-compiled
  {:compiled true}
  [{:keys [label seed]}]
  [:section.panel
   [:span.label label]
   [:input.field {:type "text" :default-value seed}]])

(def ^:private app-modes
  [["interpreted" views/app] ["compiled" app-compiled]])

(def ^:private counter-modes
  [["interpreted" views/counter] ["compiled" counter-compiled]])

(def ^:private panel-modes
  [["interpreted" views/panel] ["compiled" panel-compiled]])

;; ---------------------------------------------------------------------------
;; Harness
;; ---------------------------------------------------------------------------

(defn- reg! []
  (rf/reg-sub :root/n (fn [db _] (:n db)))
  (rf/reg-event :root/seed (fn [{:keys [db]} [_ n]] {:db (assoc db :n n)})))

(defn- ref-count [fid q]
  (some-> (frame/frame fid) :sub-cache deref (get q) :ref-count))

(defn- mount! [form node opts]
  (ms/act #(v/mount form node opts)))

;; ===========================================================================
;; Row 1 — a minimal root mounts and tears down, both modes
;; ===========================================================================

(deftest root-matrix-a-minimal-root-mounts-and-tears-down-in-both-modes
  (testing "The minimal single-root spelling puts a plain element on a real
            container and claims exactly one live root; `v/unmount!` removes
            the tree and drops the claim to zero. In each mode."
    (if-not (ms/browser?)
      (ms/skip! "the browser job runs the root assertions")
      (async done
        (ms/each-mode
          app-modes
          (fn [[label view]]
            (reg!)
            (let [node (ms/host-node!)]
              (-> (mount! [view {:label "hello"}] node {:frame {:id (keyword "matrix.root" (str "app-" label))}})
                  (.then (fn [mounted]
                           (is (= "hello" (ms/text-of node "#app")) (str label ": the root rendered"))
                           (is (= 1 (count (root/live-root-ids))) (str label ": exactly one live root"))
                           (-> (ms/act #(v/unmount! mounted))
                               (.then (fn [_]
                                        (is (= 0 (count (root/live-root-ids)))
                                            (str label ": the claim dropped to zero"))
                                        (is (= 0 (.-childElementCount node))
                                            (str label ": and the container is empty"))
                                        (.remove node)
                                        nil))))))))
          done)))))

;; ===========================================================================
;; Row 2 — an owned frame tears down to the exact integer zero, both modes
;; ===========================================================================

(deftest root-matrix-an-owned-frame-tears-down-to-exact-zero-in-both-modes
  (testing "A root that ENSURES its own frame reads the seed on its first
            render — the frame was live before React saw anything — and,
            after `v/unmount!`, the registry holds no claim, the frame ledger
            no record, the ensured frame is destroyed, and the subscription
            the view held is released from the live sub-cache. Each is the
            exact integer zero read from a different place. In each mode."
    (if-not (ms/browser?)
      (ms/skip! "the browser job runs the owned-teardown assertions")
      (async done
        (ms/each-mode
          counter-modes
          (fn [[label view]]
            (reg!)
            (let [node (ms/host-node!)
                  fid  (keyword "matrix.root" (str "owned-" label))
                  q    [:root/n]]
              (-> (mount! [view {}] node {:frame {:id fid :initial-events [[:root/seed 7]]}})
                  (.then (fn [mounted]
                           (is (= "7" (ms/text-of node ".count")) (str label ": the seed read on first render"))
                           (is (= 1 (count (root/live-root-ids))) (str label ": one live root"))
                           (is (= 1 (count (root/frame-ledger-snapshot))) (str label ": one ledger record"))
                           (is (some? (ref-count fid q)) (str label ": a live sub-cache node while mounted"))
                           (-> (ms/act #(v/unmount! mounted))
                               (.then (fn [_]
                                        (is (= 0 (count (root/live-root-ids)))
                                            (str label ": zero registry claims"))
                                        (is (= 0 (count (root/frame-ledger-snapshot)))
                                            (str label ": zero ledger records"))
                                        (is (nil? (frame/frame fid))
                                            (str label ": the ensured frame is destroyed"))
                                        (is (nil? (ref-count fid q))
                                            (str label ": and no sub-cache node holds a released dependency"))
                                        (.remove node)
                                        nil))))))))
          done)))))

;; ===========================================================================
;; Row 3 — two roots of one declaration keep independent identity, both modes
;; ===========================================================================

(deftest root-matrix-multi-root-identity-in-both-modes
  (testing "One declaration mounted TWICE on one page is two roots, not one:
            each occurrence's per-occurrence state (an uncontrolled field
            seeded differently) is its own, and the registry holds two live
            claims. Two roots that shared identity would show one field's
            seed in both. In each mode."
    (if-not (ms/browser?)
      (ms/skip! "the browser job runs the multi-root assertions")
      (async done
        (ms/each-mode
          panel-modes
          (fn [[label view]]
            (reg!)
            (let [n1 (ms/host-node!)
                  n2 (ms/host-node!)]
              (-> (mount! [view {:label "one" :seed "alpha"}] n1
                          {:frame {:id (keyword "matrix.root" (str "p1-" label))}
                           :disambiguator :one})
                  (.then (fn [m1]
                           (-> (mount! [view {:label "two" :seed "beta"}] n2
                                       {:frame {:id (keyword "matrix.root" (str "p2-" label))}
                                        :disambiguator :two})
                               (.then (fn [m2]
                                        (is (= 2 (count (root/live-root-ids)))
                                            (str label ": two live roots"))
                                        (is (= "alpha" (.-value (ms/q n1 ".field")))
                                            (str label ": occurrence one holds its own seed"))
                                        (is (= "beta" (.-value (ms/q n2 ".field")))
                                            (str label ": occurrence two holds its own — identity is not shared"))
                                        (-> (ms/act #(v/unmount! m1))
                                            (.then (fn [_] (ms/act #(v/unmount! m2))))
                                            (.then (fn [_]
                                                     (is (= 0 (count (root/live-root-ids)))
                                                         (str label ": both roots released"))
                                                     (.remove n1) (.remove n2)
                                                     nil)))))))))))
          done)))))

;; ===========================================================================
;; Row 4 — both modes build the same root DOM
;; ===========================================================================

(deftest root-matrix-both-modes-build-the-same-root-dom
  (testing "The mounted root is the SAME real DOM in each mode — same
            element, same id, same text. `v/mount` is one door; promotion
            must not change what lands on the container."
    (if-not (ms/browser?)
      (ms/skip! "the browser job runs the parity assertion")
      (async done
        (reg!)
        (let [ni (ms/host-node!)
              nc (ms/host-node!)]
          (-> (mount! [views/app {:label "same"}] ni {:frame {:id :matrix.root/parity-i}})
              (.then (fn [mi]
                       (-> (mount! [app-compiled {:label "same"}] nc {:frame {:id :matrix.root/parity-c}})
                           (.then (fn [mc]
                                    (ms/outlines-agree? (ms/q ni "#app") (ms/q nc "#app") "root element")
                                    (is (= "same" (ms/text-of ni "#app")) "non-vacuous: the root really rendered")
                                    ;; ASYMMETRIC, so they stay put: `mi` and
                                    ;; `mc` are what the two mounts resolved
                                    ;; with, and a rejection arm never had a
                                    ;; root to unmount.
                                    (-> (ms/act #(v/unmount! mi))
                                        (.then (fn [_] (ms/act #(v/unmount! mc))))))))))
              ;; Reports and RELEASES; it never finishes (rf2-o0n1). `done` runs
              ;; the whole remainder of the run synchronously, so a `.catch`
              ;; downstream of it would claim a later namespace's throw as this
              ;; row's and fire `done` a second time.
              (.catch (fn [e] (is false (str "a root mount rejected: " e)) nil))
              ;; The node detach both arms DID share rides the single trailing
              ;; step: written once, run once per path.
              (.then (fn [_] (.remove ni) (.remove nc) (done)))))))))
