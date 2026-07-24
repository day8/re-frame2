(ns re-frame.freehand.root-teardown-dom-cljs-test
  "FH-ROOT-005 in a real browser — teardown is total, and total means zero.

  Every number here is an EXACT count. \"Approximately zero\" is the shape a
  leak hides in: a released root whose ViewCell still holds a live
  sub-cache node keeps recomputing on every write for the rest of the
  session, and nothing on screen says so. The symptom is a dev session
  that gets slower for reasons nobody can attribute — which is exactly the
  kind of defect an exact assertion catches on the day it lands and a
  vague one never catches at all.

  The four things that must go are read from FOUR different places — the
  root registry, the document, the frame registry, and the live sub-cache
  — so no single mistake can make all four look right. The sub-cache read
  is the load-bearing one: it is the only one that can distinguish \"React
  removed the DOM\" from \"the ViewCell released what it owned\", and those
  are very different claims.

  Two things must SURVIVE, and they are the same claim from the other
  side: a frame the root merely SCOPED is not the root's to destroy, and a
  stale handle must not tear down the successor that legitimately claimed
  its id.

  This file rides the browser lane through its `-dom-cljs-test` namespace
  suffix. It also matches the node suites' broader regex, where it has no
  DOM to mount and says so rather than passing quietly."
  (:require ["react" :as react]
            [cljs.test :refer-macros [async deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.freehand :as v]
            [re-frame.freehand.conformance :as conf]
            [re-frame.freehand.react :as fr]
            [re-frame.freehand.root :as root]
            [re-frame.freehand.root-views :as views]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]))

(def root-005 (conf/fixture :FH-ROOT-005))

(def ^:private runtime-fixture
  (test-support/make-reset-runtime-fixture
    {:adapter       plain-atom/adapter
     :ambient-frame nil
     :async?        true}))

(use-fixtures :each
  {:before (fn []
             ((:before runtime-fixture))
             (root/reset-registry!)
             (fr/reset-boundaries!))
   :after  (fn []
             (root/reset-registry!)
             (fr/reset-boundaries!)
             ((:after runtime-fixture)))})

(defn- browser? []
  (and (exists? js/document) (some? (.-createElement js/document))))

(defn- act
  [thunk]
  (try
    (set! (.-IS_REACT_ACT_ENVIRONMENT js/globalThis) true)
    (js/Promise.resolve (react/act (fn [] (js/Promise.resolve (thunk)))))
    (catch :default e
      (js/Promise.reject e))))

(defn- host-node! []
  (let [container (js/document.createElement "div")]
    (.appendChild js/document.body container)
    container))

(defn- skip! [why]
  (is true (str "a real React mount needs a DOM host — " why)))

(defn- text [container selector]
  (some-> (.querySelector container selector) .-textContent))

;; The live sub-cache, read directly. This is the observation that separates
;; "React took the DOM away" from "the ViewCell released what it owned".
(defn- ref-count [fid q]
  (some-> (frame/frame fid) :sub-cache deref (get q) :ref-count))

(defn- reg! []
  (rf/reg-sub :root/n (fn [db _] (:n db)))
  (rf/reg-event :root/seed (fn [{:keys [db]} [_ n]] {:db (assoc db :n n)})))

;; ===========================================================================
;; FH-ROOT-005 — an OWNED frame: every count goes to zero
;; ===========================================================================

(deftest fh-root-005-unmount-leaves-exactly-nothing
  (testing "Per FH-ROOT-005 (browser): after v/unmount! the registry holds no
            claim for the root, its container holds no child nodes, the frame
            it ENSURED is destroyed and its ledger record gone, and the
            subscription its view held is released from the live sub-cache.
            Each is an exact count read from a different place — a teardown
            that only unmounted React would leave the ledger and the frame
            behind, and the DOM would look identical."
    (if-not (browser?)
      (skip! "the browser job runs the teardown assertions")
      (async done
        (reg!)
        (let [node   (host-node!)
              owned  (:owned root-005)
              fid    (:frame-id owned)
              q      (:query root-005)
              before (:before root-005)
              after  (:after root-005)]
          (-> (act #(v/mount [views/counter {}] node
                             {:frame {:id             fid
                                      :initial-events [[:root/seed (:seeded owned)]]}}))
              (.then
                (fn [mounted]
                  (is (= (str (:seeded owned)) (text node ".count")))
                  (is (= (:live-roots before) (count (root/live-root-ids))))
                  (is (= (:ledger-entries before) (count (root/frame-ledger-snapshot))))
                  (is (= (:ref-count before) (ref-count fid q))
                      "while mounted the view's read is a live sub-cache node")
                  (act #(v/unmount! mounted))))
              (.then
                (fn [_]
                  (is (= (:live-roots after) (count (root/live-root-ids)))
                      "zero registry claims — id, container and prefix all released")
                  (is (= (:ledger-entries after) (count (root/frame-ledger-snapshot)))
                      "zero ledger records")
                  (is (= (:container-children after) (.-childElementCount node))
                      "zero child nodes — React unmounted the tree")
                  (is (= (:frame-live after) (some? (frame/frame fid)))
                      "the frame this root ENSURED is destroyed — the root owned its
                       lifetime, and nothing else references it")
                  (is (nil? (ref-count fid q))
                      "and with the frame gone there is no sub-cache node left to hold
                       a released dependency")
                  (.remove node)
                  (done))
                (fn [e]
                  (is false (str "teardown suite rejected: " e))
                  (.remove node)
                  (done)))))))))

;; ===========================================================================
;; FH-ROOT-005 — a SCOPED frame survives, holding nothing of the dead root's
;; ===========================================================================

(deftest fh-root-005-a-scoped-frame-survives-its-root
  (testing "Per FH-ROOT-005 (browser): a frame the root merely SCOPED is not
            the root's to destroy — it borrowed it. So the frame is still
            live afterwards, and this is the assertion that makes the
            teardown above meaningful rather than a blanket wipe. The
            subscription is still released, so the surviving frame holds
            NOTHING of the dead root's: that is the pair that distinguishes
            'released' from 'destroyed'."
    (if-not (browser?)
      (skip! "the browser job runs the scoped-teardown assertions")
      (async done
        (reg!)
        (let [node   (host-node!)
              scoped (:scoped root-005)
              fid    (:frame-id scoped)
              q      (:query root-005)
              after  (:scoped-after root-005)]
          (rf/make-frame {:id fid :initial-events [[:root/seed (:seeded scoped)]]})
          (-> (act #(v/mount [views/counter {}] node {:frame fid}))
              (.then
                (fn [mounted]
                  (is (= (str (:seeded scoped)) (text node ".count")))
                  (is (= (:ref-count (:before root-005)) (ref-count fid q))
                      "a scoped root's read is an ordinary live sub-cache node")
                  (act #(v/unmount! mounted))))
              (.then
                (fn [_]
                  (is (= (:frame-live after) (some? (frame/frame fid)))
                      "the borrowed frame survives its borrower")
                  (is (= (:ref-count after) (ref-count fid q))
                      "and holds nothing of the dead root's — the dependency was
                       RELEASED, which is a different fact from the frame being gone")
                  (is (= (:live-roots after) (count (root/live-root-ids))))
                  (is (empty? (root/frame-ledger-snapshot))
                      "the reference is dropped even though the frame stays")
                  (.remove node)
                  (done))
                (fn [e]
                  (is false (str "scoped teardown rejected: " e))
                  (.remove node)
                  (done)))))))))

;; ===========================================================================
;; FH-ROOT-005 — a stale handle is a guarded no-op
;; ===========================================================================

(deftest fh-root-005-a-stale-handle-does-not-tear-down-the-successor
  (testing "Per FH-ROOT-005 (browser): unmounting a handle the registry no
            longer holds is a NO-OP, not a throw and not a teardown. The
            reachable case is a root that was unmounted and re-mounted under
            the same id: the old handle still exists in whatever variable was
            holding it, and honouring it would kill the successor. Doing
            nothing is the only answer that cannot be wrong."
    (if-not (browser?)
      (skip! "the browser job runs the stale-handle assertions")
      (async done
        (reg!)
        (let [node  (host-node!)
              fresh (host-node!)
              owned (:owned root-005)
              fid   (:frame-id owned)
              stale (:stale-handle root-005)
              first-root (atom nil)]
          (-> (act #(v/mount [views/counter {}] node
                             {:frame {:id             fid
                                      :initial-events [[:root/seed (:seeded owned)]]}}))
              (.then (fn [mounted]
                       (reset! first-root mounted)
                       (act #(v/unmount! mounted))))
              (.then (fn [_]
                       ;; the successor, under the same id, on a fresh node
                       (act #(v/mount [views/counter {}] fresh
                                      {:frame {:id             fid
                                               :initial-events [[:root/seed (:seeded owned)]]}}))))
              (.then
                (fn [successor]
                  ;; the stale handle is honoured by nobody
                  (v/unmount! @first-root)
                  (is (= (:successor-still-live stale)
                         (contains? (root/live-root-ids)
                                    (:root-id (root/root-descriptor successor))))
                      "the successor is still the live root under that id")
                  (is (= (:successor-text stale)
                         (text (.-container ^root/Root successor) ".count"))
                      "and is still rendering")
                  (act #(v/unmount! successor))))
              (.then (fn [_] (.remove node) (.remove fresh) (done))
                     (fn [e]
                       (is false (str "stale-handle suite rejected: " e))
                       (.remove node) (.remove fresh)
                       (done)))))))))

;; ===========================================================================
;; FH-ROOT-005 — ownership is INCARNATION-EXACT: a stale installer never
;; reaches a same-id successor
;; ===========================================================================

(deftest fh-root-005-a-stale-installer-does-not-destroy-a-same-id-successor
  (testing "Per FH-ROOT-005 (browser): a root installs a frame VALUE, not a
            frame id. The id is address-directed authority — it destroys
            whichever same-id incarnation is live NOW — so a root that
            installed one incarnation, watched it torn down, and unmounts
            while a same-id SUCCESSOR is live must NOT reach through the id
            and kill the successor. It tears down the exact incarnation it
            installed, which is already gone, so the successor stands. This is
            the load-bearing assertion: it asserts the successor's EXACT
            incarnation token is untouched, not merely that some frame is
            there — an address-directed teardown would leave the id naming
            nothing at all."
    (if-not (browser?)
      (skip! "the browser job runs the same-id-successor assertions")
      (async done
        (reg!)
        (let [node       (host-node!)
              succ       (:same-id-successor root-005)
              fid        (:frame-id succ)
              succ-token (atom nil)]
          (-> (act #(v/mount [views/counter {}] node
                             {:frame {:id             fid
                                      :initial-events [[:root/seed (:installed succ)]]}}))
              (.then
                (fn [installer]
                  ;; Tear down the installed incarnation and stand a same-id
                  ;; successor in its place, retaining its EXACT incarnation
                  ;; token, THEN unmount the now-stale installer.
                  (rf/destroy-frame! fid)
                  (rf/make-frame {:id fid :initial-events [[:root/seed (:successor succ)]]})
                  (reset! succ-token (frame/frame-incarnation-token fid))
                  (act #(v/unmount! installer))))
              (.then
                (fn [_]
                  (is (= (:frame-live succ) (some? (frame/frame fid)))
                      "the same-id successor is still live — the stale installer
                       tore down the incarnation it installed, which was already
                       gone, and never reached the successor")
                  (is (identical? @succ-token (frame/frame-incarnation-token fid))
                      "and it is the SUCCESSOR's exact incarnation, untouched — an
                       address-directed teardown would have destroyed it, leaving the
                       id naming nothing")
                  (is (= (:ledger-after succ) (count (root/frame-ledger-snapshot)))
                      "the stale installer released its own ledger reference")
                  (rf/destroy-frame! fid)
                  (.remove node)
                  (done))
                (fn [e]
                  (is false (str "same-id-successor suite rejected: " e))
                  (.remove node)
                  (done)))))))))

;; ===========================================================================
;; FH-ROOT-005 — a re-mount TRANSITIONS its frame reference; the stale one
;; leaves, it never leaks
;; ===========================================================================

(deftest fh-root-005-a-re-mount-transitions-its-frame-reference
  (testing "Per FH-ROOT-005 (browser): a re-mount can name a different frame
            from the one the incumbent held — an owned A to an owned B, or A
            to no frame at all. Preflight takes the new reference; the stale
            one has to leave with the move, or it leaks a ledger row and an
            owned frame past the root that abandoned it. A destroyed when the
            root moves off it, only B's ledger row survives the A->B move, and
            dropping the frame entirely leaves the ledger empty."
    (if-not (browser?)
      (skip! "the browser job runs the frame-transition assertions")
      (async done
        (reg!)
        (let [node1 (host-node!)
              node2 (host-node!)
              tr    (:transition root-005)
              a     (:frame-a tr)
              b     (:frame-b tr)]
          ;; Part 1 — A -> B, with `counter` so the moved reference also shows
          ;; on screen: the first render read A's seed, the re-mount reads B's.
          (-> (act #(v/mount [views/counter {}] node1
                             {:frame {:id a :initial-events [[:root/seed (:seeded-a tr)]]}}))
              (.then
                (fn [_]
                  (act #(v/mount [views/counter {}] node1
                                 {:frame {:id b :initial-events [[:root/seed (:seeded-b tr)]]}}))))
              (.then
                (fn [remounted]
                  (is (= (:a-live-after tr) (some? (frame/frame a)))
                      "A is destroyed when the root moves off it — its last reference left")
                  (is (= (:b-live-after tr) (some? (frame/frame b)))
                      "B is live")
                  (is (= (:ledger-remount tr) (count (root/frame-ledger-snapshot)))
                      "only B's ledger row survives the move — A's did not leak")
                  (is (= b (root/root-frame-id remounted))
                      "and the root now references B")
                  (is (= (:b-text tr) (text node1 ".count"))
                      "the re-render read B's seed, not A's")
                  (act #(v/unmount! remounted))))
              (.then
                (fn [_]
                  ;; Part 2 — A -> no frame, with `app` (no subscription read),
                  ;; so a frameless re-mount renders cleanly.
                  (act #(v/mount [views/app {:label "x"}] node2
                                 {:frame {:id a :initial-events [[:root/seed (:seeded-a tr)]]}}))))
              (.then
                (fn [_]
                  (act #(v/mount [views/app {:label "x"}] node2 {}))))
              (.then
                (fn [dropped]
                  (is (nil? (root/root-frame-id dropped))
                      "re-mounting with no :frame drops the frame binding")
                  (is (= (:a-live-after tr) (some? (frame/frame a)))
                      "and A — the frame it abandoned — is destroyed, not orphaned")
                  (is (= (:ledger-drop tr) (count (root/frame-ledger-snapshot)))
                      "the ledger is empty: the reference left with the move")
                  (act #(v/unmount! dropped))))
              (.then
                (fn [_]
                  (is (= (:ledger-final tr) (count (root/frame-ledger-snapshot)))
                      "final teardown leaves nothing behind")
                  (.remove node1) (.remove node2)
                  (done))
                (fn [e]
                  (is false (str "frame-transition suite rejected: " e))
                  (.remove node1) (.remove node2)
                  (done)))))))))

;; ===========================================================================
;; FH-ROOT-005 — an installed frame survives its installer while a sibling
;; still references it
;; ===========================================================================

(deftest fh-root-005-an-installed-frame-outlives-its-installer-until-the-last-ref
  (testing "Per FH-ROOT-005 (browser): two roots reference one installed
            frame — the installer owns it, a second root borrows it by
            SCOPE. The installer leaving is not the last reference, so the
            frame survives; the borrower leaving is, so it is destroyed. Each
            root removes only its OWN reference."
    (if-not (browser?)
      (skip! "the browser job runs the shared-frame assertions")
      (async done
        (reg!)
        (let [installer-node (host-node!)
              borrower-node  (host-node!)
              tr             (:two-refs root-005)
              fid            (:frame-id tr)
              installer-root (atom nil)
              borrower-root  (atom nil)]
          (-> (act #(v/mount [views/counter {}] installer-node
                             {:root-id (:installer tr)
                              :frame   {:id fid :initial-events [[:root/seed (:seeded tr)]]}}))
              (.then
                (fn [installer]
                  (reset! installer-root installer)
                  (act #(v/mount [views/counter {}] borrower-node
                                 {:root-id (:borrower tr) :frame fid}))))
              (.then
                (fn [borrower]
                  (reset! borrower-root borrower)
                  ;; the installer leaves first — not the last reference
                  (act #(v/unmount! @installer-root))))
              (.then
                (fn [_]
                  (is (= (:live-after-1 tr) (some? (frame/frame fid)))
                      "the installer's departure is not the last reference — the
                       borrower still holds one, so the frame survives")
                  (act #(v/unmount! @borrower-root))))
              (.then
                (fn [_]
                  (is (= (:live-after-2 tr) (some? (frame/frame fid)))
                      "the borrower's departure is the last legitimate reference, so
                       the installed frame is destroyed")
                  (is (empty? (root/frame-ledger-snapshot))
                      "and the ledger is empty")
                  (.remove installer-node) (.remove borrower-node)
                  (done))
                (fn [e]
                  (is false (str "shared-frame suite rejected: " e))
                  (.remove installer-node) (.remove borrower-node)
                  (done)))))))))
