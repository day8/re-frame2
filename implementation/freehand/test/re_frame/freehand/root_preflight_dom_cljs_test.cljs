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
