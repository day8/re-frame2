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
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]))

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

(defn- text [container selector]
  (some-> (.querySelector container selector) .-textContent))

(defn- reg! []
  (rf/reg-sub :root/n (fn [db _] (:n db)))
  (rf/reg-event :root/seed (fn [{:keys [db]} [_ n]] {:db (assoc db :n n)})))

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
                       (is (nil? (:installed-by (get (root/frame-ledger-snapshot)
                                                     (:frame-id scope))))
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
