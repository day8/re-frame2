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
            [re-frame.test-support :as test-support]
            [re-frame.trace.tooling :as trace-tooling]))

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

(defn- error-data
  "The whole ex-data of the error `thunk` raised, or nil when it returned
  normally — the ownership-proof refusals throw synchronously in preflight,
  BEFORE React, so a fail-loud remount is caught here without an `act`."
  [thunk]
  (try (thunk) nil (catch :default e (ex-data e))))

;; The live sub-cache, read directly. This is the observation that separates
;; "React took the DOM away" from "the ViewCell released what it owned".
(defn- ref-count [fid q]
  (some-> (frame/frame fid) :sub-cache deref (get q) :ref-count))

(defn- reg! []
  (rf/reg-sub :root/n (fn [db _] (:n db)))
  (rf/reg-event :root/seed (fn [{:keys [db]} [_ n]] {:db (assoc db :n n)})))

(defn- listen!
  "Register a trace listener collecting every event whose :operation is
  `operation` into `a`, and answer its key for unregistration."
  [operation a]
  (let [k (keyword (gensym "root-release-probe-"))]
    (trace-tooling/register-listener!
      k (fn [ev] (when (= operation (:operation ev)) (swap! a conj ev))))
    k))

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

;; ===========================================================================
;; FH-ROOT-005 — a pre-#6818 legacy ledger row cannot bootstrap ownership
;; (rf2-drpa3.110, third reopen — supersedes the rf2-4pvsy address-migration)
;;
;; `frame-ledger` is `defonce`, so a row a pre-#6818 build wrote survives a hot
;; reload WITHOUT an `:installed-value` — an `:installed-by` but no incarnation
;; token. An earlier fix MIGRATED that row on remount by reading the live frame's
;; token by ADDRESS, but a token read by address is the CURRENT incarnation, not
;; provably the one the installer created: if external code destroyed the
;; original and stood a same-id SUCCESSOR before the reloaded remount, the
;; migration silently recorded the successor's token and the final unmount
;; destroyed a frame the programmer owns. The ownership proof
;; (`owns-live-incarnation?`) is keyed on the token, and a legacy row has none,
;; so it proves ownership of nothing: a config-bearing remount over it FAILS
;; LOUD before any mutation rather than bootstrapping ownership from the address.
;; The honest cost is that the un-owned row's teardown is a safe no-op — it never
;; destroys a frame it cannot prove it owns.
;; ===========================================================================

(deftest fh-root-005-a-legacy-row-cannot-bootstrap-ownership-of-the-live-frame
  (testing "Per FH-ROOT-005 / rf2-drpa3.110 (browser): a defonce ledger row a
            pre-#6818 build carried across a reload has :installed-by but no
            :installed-value — no incarnation token. On the config-bearing
            remount the ownership proof cannot show the frame live under the id
            is the one this root installed (there is no recorded token to
            compare), so bootstrapping ownership from the current address could
            silently take over whatever incarnation happens to be live. The
            remount FAILS LOUD before any mutation, the exact live incarnation is
            untouched, and the un-owned row's teardown is a safe no-op."
    (if-not (browser?)
      (skip! "the browser job runs the legacy-row ownership-proof assertions")
      (async done
        (reg!)
        (let [node  (host-node!)
              fid   :fh.root/legacy-live
              plan  {:frame {:id fid :initial-events [[:root/seed 11]]}}
              token (atom nil)]
          (-> (act #(v/mount [views/counter {}] node plan))
              (.then
                (fn [installer]
                  (reset! token (frame/frame-incarnation-token fid))
                  ;; simulate the pre-#6818 shape the defonce ledger carried.
                  (root/downgrade-ledger-row-to-pre-6818! fid)
                  (is (some? (get-in (root/frame-ledger-snapshot) [fid :ownership :plan-author]))
                      "the legacy row still names its installer")
                  (is (nil? (get-in (root/frame-ledger-snapshot) [fid :ownership :handle]))
                      "but carries no install value — no token to prove ownership with")
                  ;; the reloaded code re-runs the config-bearing mount over the row.
                  (let [data (error-data #(v/mount [views/counter {}] node plan))]
                    (is (= :rf.error/frame-payload-conflict (:rf.error/id data))
                        "the remount cannot prove it owns the live frame, so it fails loud")
                    (is (= :scope-config-less-or-own-the-lifetime (:recovery data))
                        "under the scope-or-own recovery — a legacy row is not a licence
                         to bootstrap ownership from the current address")
                    (is (identical? @token (frame/frame-incarnation-token fid))
                        "and the live frame is the EXACT same incarnation — the guard
                         fired before make-frame or the ledger could touch it")
                    (is (nil? (get-in (root/frame-ledger-snapshot) [fid :ownership :handle]))
                        "the legacy row is unchanged — no address-directed value written"))
                  (act #(v/unmount! installer))))
              (.then
                (fn [_]
                  (is (some? (frame/frame fid))
                      "the un-owned legacy row's teardown is a safe no-op — it proved
                       no token, so it never destroys the frame (better a leak than an
                       address-directed destroy of a frame it cannot prove it owns)")
                  (rf/destroy-frame! fid)
                  (.remove node)
                  (done))
                (fn [e]
                  (is false (str "legacy-row ownership-proof suite rejected: " e))
                  (.remove node)
                  (done)))))))))

(deftest fh-root-005-a-legacy-row-refuses-a-same-id-successor-before-remount
  (testing "Per FH-ROOT-005 / rf2-drpa3.110 (browser): the residual the third
            reopen names — a successor seated BEFORE the reloaded remount, which
            the earlier post-migration test could not reach. A legacy row
            survives the reload; external code then tears the installed
            incarnation down and stands a same-id SUCCESSOR B in its place. The
            address-migration used to read B's live token and record it — a
            silent takeover. Now the ownership proof has no token to compare, so
            the remount FAILS LOUD before any mutation and B is preserved EXACTLY;
            unmounting the stale installer never reaches B either."
    (if-not (browser?)
      (skip! "the browser job runs the legacy-successor assertions")
      (async done
        (reg!)
        (let [node    (host-node!)
              fid     :fh.root/legacy-successor
              plan    {:frame {:id fid :initial-events [[:root/seed 11]]}}
              b-token (atom nil)]
          (-> (act #(v/mount [views/counter {}] node plan))
              (.then
                (fn [installer]
                  ;; downgrade to the pre-#6818 shape,
                  (root/downgrade-ledger-row-to-pre-6818! fid)
                  ;; then, BEFORE the remount, external code reseats a same-id
                  ;; successor — the case the post-migration test cannot reach.
                  (rf/destroy-frame! fid)
                  (rf/make-frame {:id fid :initial-events [[:root/seed 22]]})
                  (reset! b-token (frame/frame-incarnation-token fid))
                  (let [data (error-data #(v/mount [views/counter {}] node plan))]
                    (is (= :rf.error/frame-payload-conflict (:rf.error/id data))
                        "the remount cannot bootstrap ownership from the current address")
                    (is (= :scope-config-less-or-own-the-lifetime (:recovery data)))
                    (is (identical? @b-token (frame/frame-incarnation-token fid))
                        "the same-id successor B is the EXACT untouched incarnation —
                         no address-directed value recorded, no takeover")
                    (is (nil? (get-in (root/frame-ledger-snapshot) [fid :ownership :handle]))
                        "the legacy row is unchanged"))
                  (act #(v/unmount! installer))))
              (.then
                (fn [_]
                  (is (identical? @b-token (frame/frame-incarnation-token fid))
                      "unmounting the stale installer never reached B — the un-owned
                       legacy row's teardown is a safe no-op, not an address-directed
                       destroy that would have killed the programmer's successor")
                  (rf/destroy-frame! fid)
                  (.remove node)
                  (done))
                (fn [e]
                  (is false (str "legacy-successor suite rejected: " e))
                  (.remove node)
                  (done)))))))))

;; ===========================================================================
;; FH-ROOT-005 — Fable sequence (6) / Finding 1: a legacy row at FINAL release
;; fails LOUD, not silent.
;;
;; A pre-#6818 legacy row (a `:plan-author` but no `:handle`) reaching its final
;; release cannot destroy the root-owned frame it named exactly, so the frame
;; LEAKS. Release is now LOUD about it, matching mount's fail-loud posture on the
;; same unprovable-provenance shape — the old code silently no-op'd on the nil
;; handle. A row the destroy hook already TOMBSTONED (marked `:destroyed-at`) is
;; NOT re-alarmed; only a genuine legacy row is.
;; ===========================================================================

(deftest fh-root-005-a-legacy-row-final-release-fails-loud-not-silent
  (testing "Per FH-ROOT-005 / Fable sequence (6) / Finding 1 (browser): a
            pre-#6818 legacy row reaching its FINAL release fires the loud
            :rf.warning/root-owned-frame-leaked-at-release diagnostic, instead of
            the old silent nil-handle no-op. The frame still SURVIVES — a legacy
            row proves no token, so a surfaced leak is the honest posture, not an
            address-directed destroy of a frame it cannot prove it owns."
    (if-not (browser?)
      (skip! "the browser job runs the legacy-release loudness assertions")
      (async done
        (reg!)
        (let [node  (host-node!)
              fid   :fh.root/legacy-loud-release
              plan  {:frame {:id fid :initial-events [[:root/seed 11]]}}
              leaks (atom [])
              k     (listen! :rf.warning/root-owned-frame-leaked-at-release leaks)]
          (-> (act #(v/mount [views/counter {}] node plan))
              (.then
                (fn [installer]
                  ;; The pre-#6818 legacy shape: a plan-author, no handle, and NO
                  ;; :destroyed-at (a genuine legacy row, not a hook tombstone).
                  (root/downgrade-ledger-row-to-pre-6818! fid)
                  (act #(v/unmount! installer))))
              (.then
                (fn [_]
                  (is (= 1 (count @leaks))
                      "the legacy final release fired exactly one loud leak diagnostic")
                  (let [ev (first @leaks)]
                    (is (= fid (get-in ev [:tags :frame-id]))
                        "naming the leaked frame")
                    (is (= :fh.root/legacy-loud-release (get-in ev [:tags :frame-id])))
                    (is (= :own-the-lifetime-or-scope-config-less (:recovery ev))))
                  (is (some? (frame/frame fid))
                      "the frame still survives — a legacy row cannot prove which
                       incarnation to destroy, so the leak is surfaced, not torn down")
                  (is (empty? (root/frame-ledger-snapshot))
                      "and the row is released")
                  (trace-tooling/unregister-listener! k)
                  (rf/destroy-frame! fid)))
              ;; Reports and RELEASES; it never finishes (rf2-o0n1). `done` runs
              ;; the whole remainder of the run synchronously, so a `.catch`
              ;; downstream of it would claim a later namespace's throw as this
              ;; row's and fire `done` a second time.
              ;;
              ;; The unregister is written on both arms rather than hoisted: on
              ;; the success arm it has to run BEFORE `destroy-frame!`, whose
              ;; own diagnostics the listener is not meant to collect.
              (.catch
                (fn [e]
                  (trace-tooling/unregister-listener! k)
                  (is false (str "legacy-release loudness suite rejected: " e))
                  nil))
              ;; Shared node detach, hoisted onto the single trailing step.
              (.then (fn [_] (.remove node) (done)))))))))

;; ===========================================================================
;; FH-ROOT-005 — the UPGRADE boundary: a pre-#6871 FLAT ledger row across the
;; ownership-schema hot reload (rf2-4pvsy)
;;
;; `frame-ledger` is `defonce`, so the FIRST reload onto the nested-`:ownership`
;; code meets a row the immediately previous build wrote in the FLAT shape
;; `{:config-fingerprint :installed-by :installed-value :refs}`. That row is NOT
;; unprovable legacy data — its `:installed-value` IS the exact incarnation
;; handle — so it must migrate LOSSLESSLY: `:installed-value` → `:handle`,
;; `:installed-by` → `:plan-author`, `:config-fingerprint` → `:plan-fingerprint`,
;; refs unchanged. Un-normalized, `frame-standing` reads `(:ownership row)` as nil
;; and mis-verdicts a live OWNED frame `:unowned-live`, so the ordinary same-plan
;; HMR remount fails `:scope-config-less-or-own-the-lifetime` and neither release
;; nor the destroy hook can match the flat handle. The existing downgrade fixture
;; removes `:handle` from the NEW nested shape and so cannot reach this UPGRADE
;; boundary — these three rows do.
;; ===========================================================================

(deftest fh-root-005-a-pre-6871-flat-row-normalizes-across-the-ownership-schema-reload
  (testing "Per FH-ROOT-005 / rf2-4pvsy (browser): a pre-#6871 FLAT defonce row
            carrying its real :installed-value is normalized losslessly to the
            nested :ownership tuple before any verdict reads it, so the ordinary
            same-plan HMR remount SUCCEEDS as the idempotent no-op — durable state
            is preserved (no reseed), the EXACT original incarnation survives
            (make-frame never re-runs), and the migrated :handle is the identical
            old incarnation token. The final unmount then destroys exactly that
            incarnation and empties the ledger."
    (if-not (browser?)
      (skip! "the browser job runs the flat-row upgrade-boundary assertions")
      (async done
        (reg!)
        (let [node       (host-node!)
              fid        :fh.root/upgrade-owned
              plan       {:frame {:id fid :initial-events [[:root/seed 11]]}}
              orig-token (atom nil)
              root-id    (atom nil)]
          (-> (act #(v/mount [views/counter {}] node plan))
              (.then
                (fn [installer]
                  (reset! orig-token (frame/frame-incarnation-token fid))
                  (reset! root-id (:root-id (root/root-descriptor installer)))
                  (is (= 11 (:n (frame/frame-app-db-value fid))) "the installer seeded the frame")
                  ;; Seed the EXACT pre-#6871 FLAT row a defonce ledger carried
                  ;; across the ownership-schema reload, with its real handle.
                  (root/flatten-ledger-row-to-pre-6871! fid)
                  (is (nil? (get-in (root/frame-ledger-snapshot) [fid :ownership]))
                      "the seeded row is FLAT — no nested :ownership")
                  (is (some? (get-in (root/frame-ledger-snapshot) [fid :installed-value]))
                      "and it carries the exact install handle at :installed-value")
                  ;; Mutate durable state, so a silent reseed to 11 would show. The
                  ;; frame db value is read directly — timing-independent of the
                  ;; reactive repaint, which is a substrate fact, not this fixture's.
                  (act #(rf/dispatch-sync [:root/seed 99] {:frame fid}))))
              (.then
                (fn [_]
                  (is (= 99 (:n (frame/frame-app-db-value fid))) "durable state is now 99")
                  ;; The reloaded code re-runs the same-plan mount over the flat row.
                  (act #(v/mount [views/counter {}] node plan))))
              (.then
                (fn [remounted]
                  (is (root/root? remounted)
                      "the same-plan HMR remount SUCCEEDED — no scope-or-own fail-loud")
                  (is (= 99 (:n (frame/frame-app-db-value fid)))
                      "durable state preserved — the idempotent no-op did NOT reseed to 11")
                  (is (identical? @orig-token (frame/frame-incarnation-token fid))
                      "the EXACT original incarnation survived — make-frame never re-ran")
                  (let [owner (get-in (root/frame-ledger-snapshot) [fid :ownership])]
                    (is (some? owner)
                        "the flat row is normalized to the nested :ownership tuple")
                    (is (identical? @orig-token
                                    (frame/frame-value-incarnation-token (:handle owner)))
                        "the migrated :handle is the identical old incarnation token — lossless")
                    (is (= @root-id (:plan-author owner))
                        "the author label is preserved (:installed-by → :plan-author)")
                    (is (some? (:plan-fingerprint owner))
                        "and the fingerprint (:config-fingerprint → :plan-fingerprint)"))
                  (is (nil? (get-in (root/frame-ledger-snapshot) [fid :installed-value]))
                      "the flat keys are gone — normalized in place, not shadowing the tuple")
                  (act #(v/unmount! remounted))))
              (.then
                (fn [_]
                  (is (nil? (frame/frame fid))
                      "final unmount destroyed EXACTLY that original incarnation")
                  (is (empty? (root/frame-ledger-snapshot)) "and emptied the ledger")
                  (.remove node) (done))
                (fn [e]
                  (is false (str "flat-row upgrade-boundary suite rejected: " e))
                  (.remove node) (done)))))))))

(deftest fh-root-005-the-destroy-hook-tombstones-a-migrated-flat-row
  (testing "Per FH-ROOT-005 / rf2-4pvsy (browser): a frame whose FLAT row survived
            the reload is destroyed EXTERNALLY before its first remount. The
            :freehand/on-frame-destroyed! hook normalizes the flat row first, so
            it can match the migrated handle against the dying token — it
            TOMBSTONES the row (drops the handle, stamps :destroyed-at) and, the
            installer still referencing it, emits exactly one loud
            :rf.warning/root-ensured-frame-destroyed-under-live-roots naming it.
            Without the normalization the flat handle is unreachable and the row
            is missed entirely."
    (if-not (browser?)
      (skip! "the browser job runs the migrated-flat destroy-hook assertions")
      (async done
        (reg!)
        (let [node  (host-node!)
              fid   :fh.root/upgrade-hook
              plan  {:frame {:id fid :initial-events [[:root/seed 11]]}}
              warns (atom [])
              k     (listen! :rf.warning/root-ensured-frame-destroyed-under-live-roots warns)]
          (-> (act #(v/mount [views/counter {}] node plan))
              (.then
                (fn [installer]
                  ;; the pre-#6871 FLAT reload-survivor, carrying its real handle.
                  (root/flatten-ledger-row-to-pre-6871! fid)
                  (is (some? (get-in (root/frame-ledger-snapshot) [fid :installed-value]))
                      "the seeded row is FLAT and carries the install handle")
                  ;; external destroy while the installer is still a live ref.
                  (rf/destroy-frame! fid)
                  (is (= 1 (count @warns))
                      "the migrated handle matched the dying token → exactly one loud diagnostic")
                  (is (contains? (set (get-in (first @warns) [:tags :live-roots]))
                                 (:root-id (root/root-descriptor installer)))
                      "naming the live root that still referenced it")
                  (let [owner (get-in (root/frame-ledger-snapshot) [fid :ownership])]
                    (is (not (contains? owner :handle))
                        "the tombstone dropped the migrated handle")
                    (is (some? (:destroyed-at owner))
                        "and stamped the destroy time — the flat row became a nested tombstone")
                    (is (some? (:plan-author owner))
                        "the author LABEL survives normalization"))
                  (is (nil? (frame/frame fid)) "the frame is gone")
                  (trace-tooling/unregister-listener! k)
                  (act #(v/unmount! installer))))
              (.then
                (fn [_]
                  (is (empty? (root/frame-ledger-snapshot))
                      "final release of the tombstoned row empties the ledger, no re-alarm")))
              ;; Reports and RELEASES, as above; the unregister stays on both
              ;; arms for the same reason.
              (.catch
                (fn [e]
                  (trace-tooling/unregister-listener! k)
                  (is false (str "migrated-flat destroy-hook suite rejected: " e))
                  nil))
              ;; Shared node detach, hoisted onto the single trailing step.
              (.then (fn [_] (.remove node) (done)))))))))

(deftest fh-root-005-a-tokenless-flat-row-stays-legacy-and-fails-loud
  (testing "Per FH-ROOT-005 / rf2-4pvsy AC4 (browser): a genuinely tokenless
            pre-#6818 row flattened to the FLAT shape — an :installed-by with NO
            :installed-value — normalizes to :ownership WITHOUT a :handle, so it
            stays :legacy-live: the config-bearing remount FAILS LOUD
            (:scope-config-less-or-own-the-lifetime) before any mutation, the
            exact live incarnation is untouched, and the migration NEVER backfills
            a handle from the bare id."
    (if-not (browser?)
      (skip! "the browser job runs the tokenless-flat legacy assertions")
      (async done
        (reg!)
        (let [node  (host-node!)
              fid   :fh.root/upgrade-tokenless
              plan  {:frame {:id fid :initial-events [[:root/seed 11]]}}
              token (atom nil)]
          (-> (act #(v/mount [views/counter {}] node plan))
              (.then
                (fn [installer]
                  (reset! token (frame/frame-incarnation-token fid))
                  ;; strip the handle (a genuinely tokenless pre-#6818 row), THEN
                  ;; flatten it — an :installed-by with no :installed-value.
                  (root/downgrade-ledger-row-to-pre-6818! fid)
                  (root/flatten-ledger-row-to-pre-6871! fid)
                  (is (nil? (get-in (root/frame-ledger-snapshot) [fid :ownership]))
                      "the seeded row is FLAT")
                  (is (contains? (get (root/frame-ledger-snapshot) fid) :installed-by)
                      "and names an installer")
                  (is (not (contains? (get (root/frame-ledger-snapshot) fid) :installed-value))
                      "but carries NO install value — a genuinely tokenless legacy row")
                  (let [data (error-data #(v/mount [views/counter {}] node plan))]
                    (is (= :rf.error/frame-payload-conflict (:rf.error/id data))
                        "normalized to :legacy-live (ownership, no handle), the remount fails loud")
                    (is (= :scope-config-less-or-own-the-lifetime (:recovery data))
                        "under scope-or-own — never a bare-id handle backfill")
                    (is (identical? @token (frame/frame-incarnation-token fid))
                        "the exact live incarnation is untouched — the guard fired before any mutation")
                    (is (nil? (get-in (root/frame-ledger-snapshot) [fid :ownership :handle]))
                        "the normalized legacy row carries no handle — unprovable, as the law requires"))
                  (act #(v/unmount! installer))))
              (.then
                (fn [_]
                  (is (some? (frame/frame fid))
                      "the un-owned legacy row's teardown is a safe no-op — no address-directed destroy")
                  (rf/destroy-frame! fid)
                  (.remove node) (done))
                (fn [e]
                  (is false (str "tokenless-flat legacy suite rejected: " e))
                  (.remove node) (done)))))))))
