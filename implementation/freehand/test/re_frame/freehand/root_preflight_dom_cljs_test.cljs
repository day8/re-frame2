(ns re-frame.freehand.root-preflight-dom-cljs-test
  "FH-ROOT-004 in a real browser — the frame is settled before React exists.

  The claim is an ORDERING, so the proof has to be something only the
  ordering can produce: the FIRST rendered text. `counter` reads
  `[:root/n]` and prints it, and the plan's `:initial-events` seed that
  value. A preflight that ran late — an effect after the first commit, a
  microtask, anything at all after `createRoot` — would put the un-seeded
  read on the page once and then correct itself, and the corrected frame
  would look identical to a correct one. So the assertion is taken inside
  the same `act` boundary as the mount, against the text React committed
  first.

  The conflict arm is the isolation claim in miniature: a second root
  arriving with a DIFFERENT config for one frame fails, before install and
  before React, and the incumbent — the frame, its value, and the sibling
  root already rendering against it — is untouched.

  ## The boundary the ordering creates

  Running the plan before React makes preflight the first thing a mount
  WRITES, and that splits the mount in two. What comes before it can fail
  with nothing to undo, so the last step that can fail on the shape of the
  call — building the root's element — is put there. What comes after it
  is looking at a document the plan itself was free to change:
  `:initial-events` are application code running before React, and
  application code can mount a root, unmount one, or take the container.
  So the claim is re-asserted after the plan, and a mount whose ground
  moved refuses rather than allocating a host root over whatever moved
  onto it.

  The four tests at the foot of this file are that boundary from both
  sides, and the fourth is the one that keeps the other three honest: an
  ordinary re-mount still goes through. A fence strict enough to refuse a
  hot reload would be a worse defect than the one it closed.

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
            [re-frame.late-bind :as late-bind]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]
            [re-frame.trace.tooling :as trace-tooling]))

(def root-004 (conf/fixture :FH-ROOT-004))

;; ONE `:each` fixture, deliberately: `cljs.test` REPLACES the registry on a
;; second `use-fixtures` call rather than composing, so the runtime reset and
;; the root-registry reset are composed here by hand.
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

(defn- error-id [thunk]
  (try (thunk) ::no-throw
       (catch :default e (:rf.error/id (ex-data e)))))

(defn- error-data
  "The whole ex-data of the error `thunk` raised, or nil when it returned
  normally — for the authority assertions that read a diagnostic's
  `:recovery` alongside its id."
  [thunk]
  (try (thunk) nil (catch :default e (ex-data e))))

(defn- detached-container
  "A container the config-bearing AUTHORITY guard never renders into: it
  throws in preflight, before createRoot, so this only has to satisfy the
  admission container check. A real element in the browser, a minimal
  stand-in in node — the node lane runs the fail-loud test because the
  throw precedes React."
  []
  (if (browser?)
    (js/document.createElement "div")
    #js {:nodeType 1}))

(defn- text [container selector]
  (some-> (.querySelector container selector) .-textContent))

(defn- reg! []
  (rf/reg-sub :root/n (fn [db _] (:n db)))
  (rf/reg-event :root/seed (fn [{:keys [db]} [_ n]] {:db (assoc db :n n)})))

(defn- listen!
  "Register a trace listener collecting every event whose :operation is
  `operation` into `a`, and answer its key for unregistration."
  [operation a]
  (let [k (keyword (gensym "root-ownership-probe-"))]
    (trace-tooling/register-listener!
      k (fn [ev] (when (= operation (:operation ev)) (swap! a conj ev))))
    k))

;; ===========================================================================
;; FH-ROOT-004 — the plan runs to completion before the host root exists
;; ===========================================================================

(deftest fh-root-004-preflight-seeds-the-frame-before-the-first-render
  (testing "Per FH-ROOT-004 (browser): an ENSURE plan creates the frame and
            drains its :initial-events BEFORE createRoot, so the body's very
            first subscription read resolves against a seeded frame. The
            first committed text is the whole proof — a plan that ran after
            the first commit would render the un-seeded read once and then
            look correct forever."
    (if-not (browser?)
      (skip! "the browser job runs the preflight assertions")
      (async done
        (reg!)
        (let [node   (host-node!)
              ensure (:ensure root-004)]
          (-> (act #(v/mount [views/counter {}] node
                             {:frame {:id             (:frame-id ensure)
                                      :initial-events [[:root/seed (:seeded ensure)]]}}))
              (.then (fn [mounted]
                       (is (= (:first-render-text ensure) (text node ".count"))
                           "the first render read a SEEDED frame")
                       (is (some? (frame/frame (:frame-id ensure)))
                           "the plan created the frame")
                       (is (= (:frame-id ensure) (root/root-frame-id mounted))
                           "and the root bound it into its React tree")
                       (is (= #{(:frame-id ensure)}
                              (set (keys (root/frame-ledger-snapshot))))
                           "one ledger record, for the frame this root installed")
                       (act #(v/unmount! mounted))))
              (.then (fn [_] (.remove node) (done))
                     (fn [e]
                       (is false (str "ensure mount rejected: " e))
                       (.remove node)
                       (done)))))))))

(deftest fh-root-004-a-scope-reference-borrows-a-frame-it-did-not-create
  (testing "Per FH-ROOT-004 (browser): a keyword :frame SCOPES a frame
            something else owns — the root creates nothing, and the body's
            first read resolves against whatever that owner seeded. A
            keyword naming no live frame is a configuration error, not a
            silently frameless subtree: scoping a subtree to a phantom would
            make every read below it resolve somewhere nobody chose."
    (if-not (browser?)
      (skip! "the browser job runs the scope assertions")
      (async done
        (reg!)
        (let [node   (host-node!)
              scope  (:scope root-004)
              absent (:absent-scope root-004)]
          (rf/make-frame {:id             (:frame-id scope)
                          :initial-events [[:root/seed (:seeded scope)]]})
          (is (= (:error absent)
                 (error-id #(v/mount [views/counter {}] (host-node!)
                                     {:frame (:frame-id absent)})))
              "a keyword :frame naming no live frame fails loud")
          (is (empty? (root/live-root-ids))
              "and the rejected root registered nothing")
          (-> (act #(v/mount [views/counter {}] node {:frame (:frame-id scope)}))
              (.then (fn [mounted]
                       (is (= (:first-render-text scope) (text node ".count")))
                       (is (nil? (get-in (root/frame-ledger-snapshot)
                                         [(:frame-id scope) :ownership :plan-author]))
                           "the ledger records the reference but NO installer — the
                            root borrowed this frame, so nothing here owns it")
                       (act #(v/unmount! mounted))))
              (.then (fn [_] (.remove node) (done))
                     (fn [e]
                       (is false (str "scope mount rejected: " e))
                       (.remove node)
                       (done)))))))))

;; ===========================================================================
;; FH-ROOT-004 — a conflicting plan fails THAT root, and only that root
;; ===========================================================================

(deftest fh-root-004-a-conflicting-plan-fails-before-install-and-before-react
  (testing "Per FH-ROOT-004 (browser): a second root whose ENSURE plan names
            an installed frame under a DIFFERENT config fingerprint fails
            with :rf.error/frame-payload-conflict. The interesting assertions
            are the ones after: the installed frame still holds the FIRST
            root's seed, the first root is still rendering it, the rejected
            root put nothing on its container and registered nothing, and
            the ledger still holds exactly one record. Re-seeding here would
            not be additive corruption — it would be a silent reset."
    (if-not (browser?)
      (skip! "the browser job runs the conflict assertions")
      (async done
        (reg!)
        (let [node     (host-node!)
              rejected (host-node!)
              ensure   (:ensure root-004)
              conflict (:conflict root-004)]
          (-> (act #(v/mount [views/counter {}] node
                             {:frame {:id             (:frame-id ensure)
                                      :initial-events [[:root/seed (:seeded ensure)]]}}))
              (.then
                (fn [mounted]
                  (is (= (:error conflict)
                         (error-id #(v/mount [views/counter {}] rejected
                                             {:root-id :fh.root/second
                                              :frame   {:id (:frame-id ensure)
                                                        :initial-events
                                                        [[:root/seed 999]]}})))
                      "one frame, one plan")
                  (is (= (:incumbent-text conflict) (text node ".count"))
                      "the installed frame was NOT re-seeded — a second install is a
                       silent reset, not additive corruption")
                  (is (= (:live-roots-after conflict) (count (root/live-root-ids)))
                      "and the rejected root registered nothing")
                  (is (= (:rejected-container-children root-004)
                         (.-childElementCount rejected))
                      "nor put anything on its container — the throw preceded React")
                  (is (= (:ledger-entries-after root-004)
                         (count (root/frame-ledger-snapshot)))
                      "one ledger record, still the first root's")
                  (act #(v/unmount! mounted))))
              (.then (fn [_] (.remove node) (.remove rejected) (done))
                     (fn [e]
                       (is false (str "conflict suite rejected: " e))
                       (.remove node) (.remove rejected)
                       (done)))))))))

;; ===========================================================================
;; FH-ROOT-004 — the preflight boundary, from both sides
;; ===========================================================================

(def ^:private boundary (:boundary root-004))

(deftest fh-root-004-a-call-shape-failure-precedes-every-write
  (testing "Per FH-ROOT-004 (browser): preflight is the first thing a mount
            WRITES, so everything that can still fail on the shape of the
            call happens before it. Building the root's element is the last
            such step, and a bad props slot at the head is the cheapest way
            to make it fail: the view is declared, so identity and the three
            claims all resolve, and the rejection lands after them. What the
            counts prove is that there was nothing to roll back — no frame,
            no ledger record, no registry claim, no DOM — and the immediate
            corrected retry proves the refusal left no residue behind it
            either."
    (if-not (browser?)
      (skip! "the browser job runs the boundary assertions")
      (async done
        (reg!)
        (let [node (host-node!)
              bad  (:bad-props boundary)
              fid  (:frame-id bad)
              plan {:frame {:id fid :initial-events [[:root/seed (:seeded bad)]]}}]
          (is (= (:error bad)
                 (error-id #(v/mount [views/counter nil] node plan)))
              "a non-map props slot fails the element build")
          (is (= (:frame-live bad) (some? (frame/frame fid)))
              "the plan never ran — no frame was created")
          (is (= (:ledger-entries bad) (count (root/frame-ledger-snapshot)))
              "and no ledger record was written")
          (is (= (:live-roots bad) (count (root/live-root-ids)))
              "nothing was registered")
          (is (= (:container-children bad) (.-childElementCount node))
              "and nothing reached the container")
          (-> (act #(v/mount [views/counter {}] node plan))
              (.then (fn [mounted]
                       (is (= (:retry-text bad) (text node ".count"))
                           "the corrected call is an ordinary successful mount —
                            the refusal left no residue for it to trip over")
                       (act #(v/unmount! mounted))))
              (.then (fn [_] (.remove node) (done))
                     (fn [e]
                       (is false (str "bad-props boundary suite rejected: " e))
                       (.remove node)
                       (done)))))))))

(deftest fh-root-004-a-re-entrant-plan-keeps-the-container-it-took
  (testing "Per FH-ROOT-004 (browser): `:initial-events` run before React and
            are ordinary application code, so a handler is free to mount a
            root — including into the very container this mount was admitted
            for. The claim taken before the plan is stale by the time the
            plan returns, so it is taken again: the inner root owns the
            container, and the outer attempt refuses rather than allocating
            a second host root over it, which would tear the inner root's
            tree down and re-seed it. The refusal gives back exactly what the
            attempt took — the frame reference its own preflight acquired,
            and nothing else."
    (if-not (browser?)
      (skip! "the browser job runs the re-entrancy assertions")
      (async done
        (reg!)
        (let [node  (host-node!)
              re-in (:re-entrant boundary)
              fid   (:frame-id re-in)
              inner (atom nil)]
          (rf/reg-event :root/mount-inner (fn [_ _] {:fx [[:root/mount-inner true]]}))
          (rf/reg-fx :root/mount-inner
                     (fn [_ _]
                       (reset! inner
                               (v/mount [views/app {:label "inner"}] node
                                        {:root-id (:inner-root-id re-in)}))))
          (-> (act (fn []
                     (is (= (:error re-in)
                            (error-id
                              (fn []
                                (v/mount [views/counter {}] node
                                         {:frame {:id             fid
                                                  :initial-events [[:root/mount-inner]]}}))))
                         "the outer mount refuses the container its own plan gave away")))
              (.then
                (fn [_]
                  (is (= (:live-root-ids re-in) (root/live-root-ids))
                      "the re-entrant root is the only live root — it kept the
                       container, and the outer attempt registered nothing")
                  (is (= (:ledger-entries re-in) (count (root/frame-ledger-snapshot)))
                      "the outer attempt gave back the frame reference it took")
                  (is (= (:frame-live re-in) (some? (frame/frame fid)))
                      "and with no reference left, the frame it installed is gone")
                  (act #(v/unmount! @inner))))
              (.then (fn [_] (.remove node) (done))
                     (fn [e]
                       (is false (str "re-entrancy suite rejected: " e))
                       (.remove node)
                       (done)))))))))

(deftest fh-root-004-a-superseded-incumbent-is-never-rendered-through
  (testing "Per FH-ROOT-004 (browser): a re-mount captures the live root it is
            going to re-render BEFORE running the plan, and the plan can
            unmount it. Rendering through the captured handle afterwards
            would render into a host root React has already discarded, and
            registering it would overwrite whatever legitimately holds the id
            now. So the incumbent is re-read after the plan and a supersession
            fails loud — :rf.error/root-not-live, the same diagnostic a stale
            handle earns anywhere else."
    (if-not (browser?)
      (skip! "the browser job runs the supersession assertions")
      (async done
        (reg!)
        (let [node  (host-node!)
              sup   (:superseded boundary)
              fid   (:frame-id sup)
              first-root (atom nil)]
          (rf/reg-event :root/unmount-first (fn [_ _] {:fx [[:root/unmount-first true]]}))
          (rf/reg-fx :root/unmount-first (fn [_ _] (v/unmount! @first-root)))
          (-> (act #(v/mount [views/app {:label "first"}] node))
              (.then
                (fn [mounted]
                  (reset! first-root mounted)
                  (act (fn []
                         (is (= (:error sup)
                                (error-id
                                  (fn []
                                    (v/mount [views/app {:label "second"}] node
                                             {:frame {:id             fid
                                                      :initial-events [[:root/unmount-first]]}}))))
                             "the re-mount refuses a handle its own plan unmounted")))))
              (.then
                (fn [_]
                  (is (= (:live-roots sup) (count (root/live-root-ids)))
                      "the plan's unmount stands; the refused re-mount registered
                       nothing over it")
                  (is (= (:ledger-entries sup) (count (root/frame-ledger-snapshot)))
                      "and gave back the frame reference its preflight took")
                  (is (= (:frame-live sup) (some? (frame/frame fid)))
                      "so the frame it installed is gone too")
                  (.remove node)
                  (done))
                (fn [e]
                  (is false (str "supersession suite rejected: " e))
                  (.remove node)
                  (done)))))))))

(deftest fh-root-004-an-ordinary-re-mount-is-still-admitted
  (testing "Per FH-ROOT-004 (browser): the other direction, and the one that
            keeps the three refusals above honest. A re-mount under the same
            id, into the same container, under the same plan is the RELOAD
            path: it must be admitted, it must re-render, and it must not
            re-seed. A fence strict enough to refuse it would be a worse
            defect than the one it closed, and a fence that let the plan
            re-run would silently reset the frame. The setup COUNT is what
            separates those two — the rendered text cannot, because a
            replayed seed writes the same value back."
    (if-not (browser?)
      (skip! "the browser job runs the idempotent-re-mount assertions")
      (async done
        (reg!)
        (let [node  (host-node!)
              idem  (:idempotent boundary)
              fid   (:frame-id idem)
              setup (atom 0)
              plan  {:frame {:id             fid
                             :initial-events [[:root/seed (:seeded idem)]
                                              [:root/count-setup]]}}]
          (rf/reg-event :root/count-setup (fn [_ _] {:fx [[:root/count-setup true]]}))
          (rf/reg-fx :root/count-setup (fn [_ _] (swap! setup inc)))
          (-> (act #(v/mount [views/counter {}] node plan))
              (.then (fn [_]
                       (is (= (:text idem) (text node ".count")))
                       (act #(v/mount [views/counter {}] node plan))))
              (.then
                (fn [remounted]
                  (is (= (:text idem) (text node ".count"))
                      "the re-mount re-rendered")
                  (is (= (:setup-runs idem) @setup)
                      "and did NOT re-seed — the plan met its own frame under its
                       own fingerprint, which is the ratified no-op")
                  (is (= (:live-roots idem) (count (root/live-root-ids)))
                      "one live root, not two")
                  (is (= (:ledger-entries idem) (count (root/frame-ledger-snapshot)))
                      "one ledger record")
                  (is (= (:frame-live idem) (some? (frame/frame fid)))
                      "and the frame the root still references is still live")
                  (act #(v/unmount! remounted))))
              (.then (fn [_] (.remove node) (done))
                     (fn [e]
                       (is false (str "idempotent re-mount suite rejected: " e))
                       (.remove node)
                       (done)))))))))

(deftest fh-root-004-a-same-id-re-entrant-successor-keeps-its-frame
  (testing "Per FH-ROOT-004 (browser): the re-entrancy fence from its hardest
            side. The outer plan's `:initial-events` re-enter `v/mount` under
            the OUTER's own derived id, on the outer's container, SCOPING the
            frame the outer just published — so the inner root is the live
            root under the id AND the legitimate holder of the frame
            reference, a reference keyed by id that the outer attempt and this
            successor now share. The outer refuses (its container is taken),
            and the give-back must NOT release the shared reference: a stale
            `acquired?` snapshot taken before preflight would yank it and
            destroy the frame the inner root is rendering against. So the inner
            root, its DOM, the frame, and the id -> frame ledger reference all
            stay live — the assertion that the exact successor's claim stands,
            not merely that the outer threw."
    (if-not (browser?)
      (skip! "the browser job runs the same-id re-entrancy assertions")
      (async done
        (reg!)
        (let [node  (host-node!)
              re    (:re-entrant-same-id boundary)
              fid   (:frame-id re)
              inner (atom nil)]
          (rf/reg-event :root/mount-inner-same (fn [_ _] {:fx [[:root/mount-inner-same true]]}))
          (rf/reg-fx :root/mount-inner-same
                     ;; same derived root-id (same view) + same container,
                     ;; SCOPING the just-published frame.
                     (fn [_ _]
                       (reset! inner (v/mount [views/counter {}] node {:frame fid}))))
          (-> (act
                (fn []
                  (is (= (:error re)
                         (error-id
                           (fn []
                             (v/mount [views/counter {}] node
                                      {:frame {:id             fid
                                               :initial-events [[:root/seed (:seeded re)]
                                                                [:root/mount-inner-same]]}}))))
                      "the outer mount refuses the container its own plan gave to a
                       same-id successor")))
              (.then
                (fn [_]
                  (is (= (:frame-live re) (some? (frame/frame fid)))
                      "the frame the successor scopes is still live — the outer
                       give-back did not yank the shared reference")
                  (is (= (:ledger-entries re) (count (root/frame-ledger-snapshot)))
                      "and its id -> frame ledger reference stands, held by the successor")
                  (is (contains? (root/live-root-ids)
                                 (:root-id (root/root-descriptor @inner)))
                      "the inner successor is the live root under the id")
                  (is (= (:inner-text re) (text node ".count"))
                      "and is rendering the scoped frame's seed")
                  (act #(v/unmount! @inner))))
              (.then (fn [_] (.remove node) (done))
                     (fn [e]
                       (is false (str "same-id re-entrancy suite rejected: " e))
                       (.remove node)
                       (done)))))))))

;; ===========================================================================
;; FH-ROOT-004 — the authority rule (Spec 004C §7, AC4): a boot/external frame
;; is never silently converted into address-directed teardown authority
;; ===========================================================================

(deftest fh-root-004-config-bearing-ensure-over-a-live-external-frame-fails-loud
  (testing "Per FH-ROOT-004 / Spec 004C §7 (AC4): a config-bearing :frame
            {:id …} ENSURE meeting a frame already LIVE that this page does
            NOT own — a fresh boot/external frame the ledger records no
            :installed-by for — must FAIL before make-frame mutates. Letting
            it through would reset the boot frame's config, write :installed-by
            and :installed-value for the arriving root, and hand that root the
            address-directed authority to DESTROY at unmount a frame it never
            installed. The throw precedes React, so the node lane runs this too.
            The proof straddles the throw: the diagnostic is the authority
            conflict under the scope-or-own recovery, and on the other side the
            external frame's EXACT incarnation is untouched — same live token,
            no ledger authority, nothing registered."
    (reg!)
    (let [fid   :fh.root/external-boot
          _     (rf/make-frame {:id fid :initial-events [[:root/seed 7]]})
          token (frame/frame-incarnation-token fid)
          data  (error-data #(v/mount [views/counter {}] (detached-container)
                                      {:frame {:id             fid
                                               :initial-events [[:root/seed 999]]}}))]
      (is (= :rf.error/frame-payload-conflict (:rf.error/id data))
          "the config-bearing plan over a boot-authoritative frame is refused")
      (is (= :scope-config-less-or-own-the-lifetime (:recovery data))
          "under the scope-config-less-or-own-the-lifetime recovery — distinct
           from the differing-fingerprint arm's :align-frame-plan-config")
      (is (= fid (:frame-id data))
          "and the diagnostic names the frame at stake")
      (is (some? (frame/frame fid))
          "the external frame is still LIVE — the guard fired before make-frame")
      (is (identical? token (frame/frame-incarnation-token fid))
          "and is the EXACT same incarnation the boot created — no reset")
      (is (nil? (get (root/frame-ledger-snapshot) fid))
          "no ledger authority was written for the frame this root does not own")
      (is (empty? (root/live-root-ids))
          "and the refused mount registered nothing"))))

(deftest fh-root-004-a-config-less-scope-over-an-external-frame-transfers-no-ownership
  (testing "Per FH-ROOT-004 / Spec 004C §7 (AC4): the other side of the
            authority rule. A config-less :frame keyword SCOPES a boot/external
            frame — it borrows it, recording a reference but NO installer — and
            unmounting the scoping root leaves the exact external incarnation
            LIVE: scoping never transfers ownership. A config-bearing plan over
            that SAME boot-authoritative frame — even from the very root that is
            scoping it — is refused as the authority conflict, and its refusal
            leaves the incumbent root, the frame's incarnation and the ledger
            untouched: a borrow cannot be retroactively promoted to ownership."
    (if-not (browser?)
      (skip! "the browser job renders the scope + unmount")
      (async done
        (reg!)
        (let [node  (host-node!)
              fid   :fh.root/external-scoped]
          (rf/make-frame {:id fid :initial-events [[:root/seed 4]]})
          (let [token (frame/frame-incarnation-token fid)]
            (-> (act #(v/mount [views/counter {}] node {:frame fid}))
                (.then
                  (fn [scoped]
                    (is (= "4" (text node ".count"))
                        "the scoping root borrowed the owner's seed")
                    (is (nil? (get-in (root/frame-ledger-snapshot) [fid :ownership :plan-author]))
                        "the ledger records the reference but NO installer — borrowed, not owned")
                    (is (contains? (:refs (get (root/frame-ledger-snapshot) fid))
                                   (:root-id (root/root-descriptor scoped)))
                        "the scoping root's reference is recorded")
                    ;; A config-bearing plan over the SAME boot-authoritative frame is
                    ;; the authority conflict — refused, incumbent untouched.
                    (let [data (error-data
                                 #(v/mount [views/counter {}] node
                                           {:frame {:id             fid
                                                    :initial-events [[:root/seed 999]]}}))]
                      (is (= :rf.error/frame-payload-conflict (:rf.error/id data))
                          "a config-bearing plan cannot take over the borrowed frame")
                      (is (= :scope-config-less-or-own-the-lifetime (:recovery data))
                          "under the scope-config-less-or-own-the-lifetime recovery")
                      (is (identical? token (frame/frame-incarnation-token fid))
                          "the refusal left the EXACT incarnation untouched")
                      (is (nil? (get-in (root/frame-ledger-snapshot) [fid :ownership :plan-author]))
                          "and wrote no installer authority over the borrowed frame")
                      (is (= "4" (text node ".count"))
                          "the incumbent scoping root is still rendering its borrowed seed"))
                    (act #(v/unmount! scoped))))
                (.then
                  (fn [_]
                    (is (some? (frame/frame fid))
                        "unmounting the SCOPING root leaves the external frame LIVE — no transfer")
                    (is (identical? token (frame/frame-incarnation-token fid))
                        "and it is the exact same incarnation the boot created")
                    (is (empty? (root/live-root-ids))
                        "the scoping root is gone, and it destroyed nothing it borrowed")
                    (.remove node)
                    (done))
                  (fn [e]
                    (is false (str "scope non-transfer suite rejected: " e))
                    (.remove node)
                    (done))))))))))

;; ===========================================================================
;; FH-ROOT-004 — the ownership proof is the incarnation TOKEN, not a non-nil
;; :installed-by (rf2-drpa3.110, third reopen — the same-id-successor residual)
;;
;; The AC4 arm above refuses a config-bearing ENSURE over a frame with no
;; :installed-by. But a non-nil :installed-by is not itself proof that the frame
;; live under the id NOW is the one that root installed: the core frame id is
;; address-directed, so a same-id SUCCESSOR (the installed incarnation torn down
;; and re-created under the same id) is a distinct frame carrying a distinct
;; token. A row still naming the original install value owns that successor of
;; nothing. `owns-live-incarnation?` proves ownership by matching the recorded
;; install-value token to the live frame's current token, so a stale row does
;; NOT assert ownership of a successor — whatever the remount's fingerprint.
;; ===========================================================================

(deftest fh-root-004-a-config-bearing-remount-over-a-same-id-successor-fails-loud
  (testing "Per FH-ROOT-004 / rf2-drpa3.110 (browser): install-own → same-id
            teardown+reincarnation → remount. A root installs incarnation A (its
            value token recorded); external code destroys A and stands a same-id
            successor B; the root remounts under the SAME plan. :installed-by
            still names the root, but its recorded token no longer identifies the
            live frame, so the stale row does NOT assert ownership of B — the
            remount fails loud before mutation, B is untouched, and the final
            unmount of the original installer destroys exactly the original
            incarnation (already gone), no-ops against B, and empties the ledger."
    (if-not (browser?)
      (skip! "the browser job runs the same-id-successor remount assertions")
      (async done
        (reg!)
        (let [succ    (:unprovable-incarnation root-004)
              node    (host-node!)
              fid     (:frame-id succ)
              plan    {:frame {:id fid :initial-events [[:root/seed (:seeded succ)]]}}
              a-token (atom nil)
              b-token (atom nil)]
          (-> (act #(v/mount [views/counter {}] node plan))
              (.then
                (fn [installer]
                  (reset! a-token (frame/frame-incarnation-token fid))
                  (is (identical? @a-token
                                  (frame/frame-value-incarnation-token
                                    (get-in (root/frame-ledger-snapshot) [fid :ownership :handle])))
                      "the install recorded A's exact incarnation token")
                  ;; external: destroy A, stand a same-id successor B.
                  (rf/destroy-frame! fid)
                  (rf/make-frame {:id fid :initial-events [[:root/seed (:successor-seed succ)]]})
                  (reset! b-token (frame/frame-incarnation-token fid))
                  (is (not (identical? @a-token @b-token))
                      "B is a distinct incarnation")
                  ;; same-plan remount over B — the recorded token no longer matches.
                  (let [data (error-data #(v/mount [views/counter {}] node plan))]
                    (is (= (:error succ) (:rf.error/id data))
                        "the stale row does not assert ownership of the successor")
                    (is (= (:recovery succ) (:recovery data)))
                    (is (= fid (:frame-id data))
                        "and the diagnostic names the frame at stake")
                    (is (identical? @b-token (frame/frame-incarnation-token fid))
                        "B is untouched — the guard fired before any mutation"))
                  (act #(v/unmount! installer))))
              (.then
                (fn [_]
                  (is (= (:successor-live succ) (some? (frame/frame fid)))
                      "final unmount destroyed EXACTLY the original incarnation
                       (already gone) and no-opped against the successor")
                  (is (identical? @b-token (frame/frame-incarnation-token fid))
                      "so B — the successor — still stands, its exact incarnation")
                  (is (= (:ledger-after-unmount succ) (count (root/frame-ledger-snapshot)))
                      "and the ledger is emptied: the installer released its own row")
                  (rf/destroy-frame! fid)
                  (.remove node)
                  (done))
                (fn [e]
                  (is false (str "same-id-successor remount suite rejected: " e))
                  (.remove node)
                  (done)))))))))

(deftest fh-root-004-a-config-refresh-over-a-same-id-successor-cannot-take-it-over
  (testing "Per FH-ROOT-004 / rf2-drpa3.110 (browser): the CHANGED-fingerprint
            arm of the same breach. With a config edit the remount's fingerprint
            differs, so make-frame WANTS to run — and address-directed it would
            run against the successor B, surgically rewriting B's config and
            recording B's token as the install value: a silent takeover the final
            unmount would consummate by destroying B. The ownership proof fires
            FIRST, before make-frame: the recorded token names the dead original,
            not B, so the refresh is refused and B is preserved exactly."
    (if-not (browser?)
      (skip! "the browser job runs the same-id-successor refresh assertions")
      (async done
        (reg!)
        (let [node    (host-node!)
              fid     :fh.root/succ-refresh
              plan-x  {:frame {:id fid :initial-events [[:root/seed 1]]}}
              plan-y  {:frame {:id fid :initial-events [[:root/seed 1] [:root/noop]]}}
              b-token (atom nil)]
          (rf/reg-event :root/noop (fn [{:keys [db]} _] {:db db}))
          (-> (act #(v/mount [views/counter {}] node plan-x))
              (.then
                (fn [installer]
                  ;; external: destroy A, stand a same-id successor B.
                  (rf/destroy-frame! fid)
                  (rf/make-frame {:id fid :initial-events [[:root/seed 2]]})
                  (reset! b-token (frame/frame-incarnation-token fid))
                  ;; a config EDIT (different fingerprint) over B — make-frame would
                  ;; run address-directed against B if the guard did not precede it.
                  (let [data (error-data #(v/mount [views/counter {}] node plan-y))]
                    (is (= :rf.error/frame-payload-conflict (:rf.error/id data))
                        "the config refresh cannot take over a frame it cannot prove it owns")
                    (is (= :scope-config-less-or-own-the-lifetime (:recovery data)))
                    (is (identical? @b-token (frame/frame-incarnation-token fid))
                        "B's EXACT incarnation is untouched — the guard fired before
                         make-frame could rewrite its config or record its token"))
                  (act #(v/unmount! installer))))
              (.then
                (fn [_]
                  (is (some? (frame/frame fid))
                      "and the final unmount never reached B — the install value still
                       named the original incarnation, so teardown no-opped against
                       the successor rather than destroying it")
                  (is (identical? @b-token (frame/frame-incarnation-token fid)))
                  (is (empty? (root/frame-ledger-snapshot)))
                  (rf/destroy-frame! fid)
                  (.remove node)
                  (done))
                (fn [e]
                  (is false (str "same-id-successor refresh suite rejected: " e))
                  (.remove node)
                  (done)))))))))

(deftest fh-root-004-a-fresh-incarnation-belongs-to-the-root-that-created-it
  (testing "Per rf2-2pvp7 (browser): a fresh incarnation is a fresh ownership
            row. R1 installs incarnation A; external code destroys A EXACTLY and
            stands no successor — the frame is absent, but R1's row survives in
            the defonce ledger, still naming R1. A DIFFERENT root R2 then runs a
            config-bearing ENSURE for the same id: make-frame CREATES a fresh
            incarnation C, and the ledger must record R2 — the creating root —
            as the author, never lend R1's stale name to an incarnation R1 did
            not install. The user-visible symptom pinned second: with the stale
            author, R2's own config EDIT (differing fingerprint) trips the
            differing-plan conflict arm against a root that installed nothing."
    (if-not (browser?)
      (skip! "the browser job runs the fresh-incarnation authorship assertions")
      (async done
        (reg!)
        (let [node-1  (host-node!)
              node-2  (host-node!)
              fid     :fh.root/fresh-author
              plan-1  {:root-id :fh.root/author-r1
                       :frame   {:id fid :initial-events [[:root/seed 1]]}}
              ;; SAME frame plan as R1's (equal fingerprint): the differing-plan
              ;; arm carries no liveness check, so only an equal plan reaches the
              ;; create at all — which is exactly the defect's setup.
              plan-2  {:root-id :fh.root/author-r2
                       :frame   {:id fid :initial-events [[:root/seed 1]]}}
              plan-2' {:root-id :fh.root/author-r2
                       :frame   {:id fid :initial-events [[:root/seed 1] [:root/noop]]}}
              c-token (atom nil)
              root-1  (atom nil)]
          (rf/reg-event :root/noop (fn [{:keys [db]} _] {:db db}))
          (-> (act #(v/mount [views/counter {}] node-1 plan-1))
              (.then
                (fn [installer-1]
                  (reset! root-1 installer-1)
                  ;; external: destroy A exactly; stand NO successor. The frame is
                  ;; absent; R1's row survives, still naming R1.
                  (rf/destroy-frame! fid)
                  (is (nil? (frame/frame fid)) "A is gone and nothing replaced it")
                  (is (= :fh.root/author-r1
                         (get-in (root/frame-ledger-snapshot) [fid :ownership :plan-author]))
                      "the surviving row still names R1")
                  ;; R2's ENSURE finds the id absent and CREATES incarnation C.
                  (act #(v/mount [views/counter {}] node-2 plan-2))))
              (.then
                (fn [root-2]
                  (reset! c-token (frame/frame-incarnation-token fid))
                  (is (= :fh.root/author-r2
                         (get-in (root/frame-ledger-snapshot) [fid :ownership :plan-author]))
                      "the fresh incarnation's author is the root that CREATED it —
                       a create never wears the stale author of a dead predecessor")
                  (is (identical? @c-token
                                  (frame/frame-value-incarnation-token
                                    (get-in (root/frame-ledger-snapshot) [fid :ownership :handle])))
                      "and the recorded install value is C's exact token")
                  ;; R2's own config EDIT must be a proven-owner refresh, not a
                  ;; foreign-plan conflict blamed on R1.
                  (act #(v/mount [views/counter {}] node-2 plan-2'))))
              (.then
                (fn [root-2]
                  (is (identical? @c-token (frame/frame-incarnation-token fid))
                      "the refresh was surgical — C's exact incarnation survived it")
                  (-> (act #(v/unmount! @root-1))
                      (.then (fn [_]
                               (is (some? (frame/frame fid))
                                   "R1's unmount released only its stale reference")
                               (act #(v/unmount! root-2))))
                      (.then
                        (fn [_]
                          (is (nil? (frame/frame fid))
                              "the last reference destroyed C — exactly the
                               incarnation R2 installed")
                          (is (empty? (root/frame-ledger-snapshot))))))))
              ;; Reports and RELEASES; it never finishes (rf2-o0n1). `done` runs
              ;; the whole remainder of the run synchronously, so a `.catch`
              ;; downstream of it would claim a later namespace's throw as this
              ;; row's and fire `done` a second time.
              (.catch
                (fn [e]
                  (is false (str "fresh-incarnation authorship suite rejected: " e))
                  nil))
              ;; The node detach both arms DID share rides the single trailing
              ;; step: written once, run once per path.
              (.then (fn [_] (.remove node-1) (.remove node-2) (done)))))))))

;; ===========================================================================
;; FH-ROOT-004 — Fable sequence (3): an external destroy of a root-ensured
;; frame under a LIVE root warns LOUD (Layer 2), the row is tombstoned, and a
;; different root's fresh ENSURE gets a WHOLLY FRESH ownership tuple.
;; ===========================================================================

(deftest fh-root-004-external-destroy-under-a-live-root-warns-loud-and-a-fresh-ensure-gets-a-wholly-fresh-tuple
  (testing "Per FH-ROOT-004 / Fable sequence (3) (browser): R1 ENSUREs A; A is
            destroyed EXTERNALLY while R1 is still live. The
            :freehand/on-frame-destroyed! hook TOMBSTONES R1's row (drops the
            handle, stamps :destroyed-at) and emits the loud
            :rf.warning/root-ensured-frame-destroyed-under-live-roots naming R1.
            Then a DIFFERENT root R2's fresh ENSURE over the now-absent id gets a
            WHOLLY FRESH tuple — handle, author AND fingerprint all C's/R2's,
            never a merge that lends R1's stale author to the incarnation it did
            not create. The token-identity join is load-bearing: it is what makes
            the tombstone target A and only A, and the handle assertion pins C."
    (if-not (browser?)
      (skip! "the browser job runs the loud-destroy + fresh-tuple assertions")
      (async done
        (reg!)
        (let [node-1  (host-node!)
              node-2  (host-node!)
              fid     :fh.root/loud-succ
              warns   (atom [])
              k       (listen! :rf.warning/root-ensured-frame-destroyed-under-live-roots warns)
              ;; R2's plan carries the SAME fingerprint as R1's (equal
              ;; :initial-events): the differing-fingerprint arm has no liveness
              ;; check, so only an EQUAL plan reaches the create over the surviving
              ;; tombstoned row — a differing one is refused :align-frame-plan-config
              ;; regardless of liveness (same as the authorship test above).
              plan-1  {:root-id :fh.root/loud-r1
                       :frame   {:id fid :initial-events [[:root/seed 1]]}}
              plan-2  {:root-id :fh.root/loud-r2
                       :frame   {:id fid :initial-events [[:root/seed 1]]}}
              c-token (atom nil)
              r1      (atom nil)]
          (-> (act #(v/mount [views/counter {}] node-1 plan-1))
              (.then
                (fn [installer-1]
                  (reset! r1 installer-1)
                  ;; external destroy of A, R1 still mounted (still a live ref).
                  (rf/destroy-frame! fid)
                  (is (= 1 (count @warns))
                      "exactly one loud destroyed-under-live-roots diagnostic fired")
                  (let [ev (first @warns)]
                    (is (= fid (get-in ev [:tags :frame-id]))
                        "and it names the frame at stake")
                    (is (contains? (set (get-in ev [:tags :live-roots])) :fh.root/loud-r1)
                        "and the live root that still references it, by name")
                    (is (= :observed-external-destroy-of-a-root-ensured-frame
                           (:recovery ev))))
                  (let [owner (:ownership (get (root/frame-ledger-snapshot) fid))]
                    (is (not (contains? owner :handle))
                        "the tombstone dropped the dead incarnation's handle")
                    (is (some? (:destroyed-at owner))
                        "and stamped the destroy time, so a later arm can report it")
                    (is (= :fh.root/loud-r1 (:plan-author owner))
                        "the author LABEL survives the tombstone (diagnostics only)"))
                  (is (nil? (frame/frame fid)) "A is gone and nothing replaced it")
                  (act #(v/mount [views/counter {}] node-2 plan-2))))
              (.then
                (fn [root-2]
                  (reset! c-token (frame/frame-incarnation-token fid))
                  (let [owner (:ownership (get (root/frame-ledger-snapshot) fid))]
                    (is (= :fh.root/loud-r2 (:plan-author owner))
                        "WHOLLY FRESH tuple: the author is R2, the root that created C")
                    (is (identical? @c-token
                                    (frame/frame-value-incarnation-token (:handle owner)))
                        "the handle is C's EXACT token — never R1's dead one merged in")
                    (is (not (contains? owner :destroyed-at))
                        "and the fresh tuple carries no tombstone from the predecessor"))
                  (trace-tooling/unregister-listener! k)
                  (-> (act #(v/unmount! @r1))
                      (.then (fn [_] (act #(v/unmount! root-2)))))))
              (.then
                (fn [_]
                  (is (nil? (frame/frame fid)) "the last reference destroyed C exactly")
                  (is (empty? (root/frame-ledger-snapshot)))))
              ;; Reports and RELEASES, as above. The unregister is written on
              ;; both arms rather than hoisted: the success arm drops the
              ;; listener mid-chain, BEFORE the two unmounts it is not meant to
              ;; observe.
              (.catch
                (fn [e]
                  (trace-tooling/unregister-listener! k)
                  (is false (str "loud-destroy + fresh-tuple suite rejected: " e))
                  nil))
              ;; Shared node detach, hoisted onto the single trailing step.
              (.then (fn [_] (.remove node-1) (.remove node-2) (done)))))))))

;; ===========================================================================
;; FH-ROOT-004 — Layer 1's join is the SOLE authority, even when the destroy
;; hook (Layer 2) never fires. This is the severability proof and the
;; non-vacuity anchor for the token-identity check: with the hook stubbed to a
;; no-op the row is NOT tombstoned, so it reaches owns-live-incarnation? as a
;; genuine :successor-live row (a handle that names a torn-down incarnation),
;; and the join alone must still refuse. Flip the identical? in
;; owns-live-incarnation? and THIS test reds by name.
;; ===========================================================================

(deftest fh-root-004-the-join-alone-refuses-a-successor-even-when-the-destroy-hook-never-fires
  (testing "Per FH-ROOT-004 (browser): the :freehand/on-frame-destroyed! hook is
            severable diagnostics — frame-standing's live-token join stays the
            SOLE ownership authority even if the hook never fired. With the hook
            stubbed to a no-op, an installed incarnation A is destroyed and a
            same-id SUCCESSOR B stood up: the row keeps its stale handle (no
            tombstone), so it is a genuine :successor-live row. The join alone
            must refuse a config-bearing remount over B before any mutation, and
            B is untouched — the belt is load-bearing."
    (if-not (browser?)
      (skip! "the browser job runs the join-is-sole-authority assertions")
      (async done
        (reg!)
        (let [node    (host-node!)
              fid     :fh.root/join-only-succ
              plan    {:frame {:id fid :initial-events [[:root/seed 1]]}}
              saved   (late-bind/get-fn :freehand/on-frame-destroyed!)
              b-token (atom nil)]
          ;; Stub the destroy hook to a no-op so the row is NOT tombstoned and
          ;; keeps its stale handle — a genuine :successor-live row.
          (late-bind/set-fn! :freehand/on-frame-destroyed! (fn [& _] nil))
          (-> (act #(v/mount [views/counter {}] node plan))
              (.then
                (fn [installer]
                  (rf/destroy-frame! fid)
                  (rf/make-frame {:id fid :initial-events [[:root/seed 2]]})
                  (reset! b-token (frame/frame-incarnation-token fid))
                  (is (some? (:handle (:ownership (get (root/frame-ledger-snapshot) fid))))
                      "the stubbed hook left the stale handle — a genuine successor row")
                  (let [data (error-data #(v/mount [views/counter {}] node plan))]
                    (is (= :rf.error/frame-payload-conflict (:rf.error/id data))
                        "the JOIN alone refuses the successor — no hook needed")
                    (is (= :scope-config-less-or-own-the-lifetime (:recovery data)))
                    (is (identical? @b-token (frame/frame-incarnation-token fid))
                        "B is untouched — the guard fired before any mutation"))
                  (act #(v/unmount! installer))))
              (.then
                (fn [_]
                  (is (identical? @b-token (frame/frame-incarnation-token fid))
                      "final unmount never reached B — the exact-token teardown no-oped")
                  (late-bind/set-fn! :freehand/on-frame-destroyed! saved)
                  (rf/destroy-frame! fid)))
              ;; Reports and RELEASES, as above. The hook restore is written on
              ;; both arms rather than hoisted: it has to precede the success
              ;; arm's `destroy-frame!`, so that destroy runs through the
              ;; genuine hook and not the stub this row installed.
              (.catch
                (fn [e]
                  (late-bind/set-fn! :freehand/on-frame-destroyed! saved)
                  (is false (str "join-is-sole-authority suite rejected: " e))
                  nil))
              ;; Shared node detach, hoisted onto the single trailing step.
              (.then (fn [_] (.remove node) (done)))))))))
