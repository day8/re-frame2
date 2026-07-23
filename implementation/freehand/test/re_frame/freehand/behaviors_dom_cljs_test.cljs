(ns re-frame.freehand.behaviors-dom-cljs-test
  "FH-BEHAVIOR-004 / -005 / -006 — the MOUNTED half of the behavior contract.

  A behavior's whole value is that imperative host work is bounded to
  moments the substrate names, over a node the substrate handed it, with a
  release that leaves nothing behind. None of those three is a structural
  fact: they are facts about a real `react-dom/client` commit, a real DOM
  node, and a table that must be EMPTY afterwards. So this file mounts.

  Three laws, three shapes of evidence:

    FH-BEHAVIOR-004  a candidate render React ABANDONS connects nothing,
                     a committed one connects once, `:update` fires on
                     `rf=` movement and not on a re-render, and a
                     `:layout` behavior runs before a `:passive` one in
                     the same commit even when it is declared second.
    FH-BEHAVIOR-005  teardown is TOTAL, asserted as an absence against a
                     CONTROL mount of the same markup with no behavior —
                     so a zero cannot be a counter that was never written.
    FH-BEHAVIOR-006  a command reaches the one live connection claiming
                     its explicit semantic target and NOTHING else (a live
                     decoy proves the `nothing else`), and every other
                     outcome is a visible refusal that performs no host
                     work.

  This file rides the browser lane through its `-dom-cljs-test` suffix. It
  also matches the node suites' broader regex, where it has no DOM to mount
  and says so rather than passing quietly."
  (:require ["react" :as react]
            ["react-dom/client" :as rdc]
            [cljs.test :refer-macros [async deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.freehand.behavior-views :as bv]
            [re-frame.freehand.behaviors :as behaviors]
            [re-frame.freehand.conformance :as conf]
            [re-frame.freehand.react :as fr]
            [re-frame.freehand.shell :as shell]
            [re-frame.live-frame :as live-frame]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter       plain-atom/adapter
     :ambient-frame nil
     :async?        true}))

(def ^:private fh-004 (conf/fixture :FH-BEHAVIOR-004))
(def ^:private fh-005 (conf/fixture :FH-BEHAVIOR-005))
(def ^:private fh-006 (conf/fixture :FH-BEHAVIOR-006))

(def ^:private frame-id :dom/behaviors)

(def ^:private decoy-frame-id
  "A SECOND live frame, mounted with the same declaration under the same
  semantic target. Frame isolation is not observable with one frame: the
  decoy is what makes `the command stayed in its own frame` an assertion
  rather than a description."
  :dom/behaviors-other)

(defn- browser? []
  (and (exists? js/document) (some? (.-createElement js/document))))

(defn- skip! [why]
  (is true (str "a real React mount needs a DOM host — " why)))

(defn- act
  "A React 19 `act` boundary as a promise, so assertions run after the
  commit AND its flushed effects rather than racing them."
  [thunk]
  (try
    (set! (.-IS_REACT_ACT_ENVIRONMENT js/globalThis) true)
    (js/Promise.resolve (react/act (fn [] (js/Promise.resolve (thunk)))))
    (catch :default e
      (js/Promise.reject e))))

(defn- mount! []
  (let [container (js/document.createElement "div")]
    (.appendChild js/document.body container)
    [container (rdc/createRoot container)]))

(defn- teardown! [container root]
  (.unmount root)
  (.remove container)
  nil)

(defn- setup! []
  (behaviors/reset-connections!)
  (bv/reset-transcript!)
  (bv/reset-dispatches!)
  (reset! bv/last-dispatch nil)
  (live-frame/make-frame {:id frame-id})
  (live-frame/make-frame {:id decoy-frame-id})
  (rf/reg-event :probe/announced (fn [db _] db))
  (rf/reg-event :probe/command   (fn [_ [_ cmd]] {:fx [[behaviors/command-fx-id cmd]]}))
  nil)

(defn- element-in
  "The form, mounted under `fid`'s frame boundary."
  [fid form]
  (shell/provide-frame fid (fr/element form)))

(defn- element [form]
  (element-in frame-id form))

(defn- attr [container selector name*]
  (some-> (.querySelector container selector) (.getAttribute name*)))

;; ---------------------------------------------------------------------------
;; The abandoned candidate — how a render is made to really NOT commit
;;
;; A plain React component, deliberately: suspending is a React-host fact, not
;; something a Freehand view declares. It sits AFTER the behavior's use site in
;; document order, so the candidate has already rendered the behavior boundary
;; by the time this throws — and React renders work that suspends without ever
;; committing it, which is exactly the abandoned candidate the law is about.
;; ---------------------------------------------------------------------------

(defonce ^:private never-resolves (js/Promise. (fn [_ _] nil)))

(def ^:private suspender-renders (atom 0))

(defn- suspender [^js props]
  (when (.-suspend props)
    (swap! suspender-renders inc)
    (react/use never-resolves))
  nil)

(defn- probe-element [suspend?]
  (react/createElement
    react/Suspense
    #js {:fallback (react/createElement "div" #js {:id "fallback"} "waiting")}
    (element [bv/plain {}])
    (react/createElement suspender #js {:suspend suspend?})))

;; ===========================================================================
;; FH-BEHAVIOR-004 — connection is COMMIT-ONLY, and update observes movement
;; ===========================================================================

(deftest fh-behavior-004-an-abandoned-candidate-connects-nothing
  (testing "Per FH-BEHAVIOR-004: React renders speculatively — a transition
            that suspends, a superseded update, a StrictMode double render.
            A behavior that connected from a RENDER would open a host
            connection for every one of them and close none. The probe below
            renders the use site inside a Suspense whose sibling suspends, so
            the candidate really runs and really never commits: the lifecycle
            transcript must be empty and the connection table must be zero.
            The same tree, committed, then connects exactly once."
    (if-not (browser?)
      (skip! "the browser job runs the commit-only assertions")
      (async done
        (setup!)
        (reset! suspender-renders 0)
        (let [[container root] (mount!)]
          (-> (act #(.render root (probe-element true)))
              (.then (fn [_]
                       (is (pos? @suspender-renders)
                           "the candidate really rendered — it reached the suspending sibling")
                       (is (some? (.querySelector container "#fallback"))
                           "and really did not commit — the fallback is what is on screen")
                       (is (= (:abandoned fh-004) (bv/ops))
                           "so NOTHING connected")
                       (is (zero? (behaviors/connection-count))
                           "and the connection table is empty")
                       (act #(.render root (probe-element false)))))
              (.then (fn [_]
                       (is (= (:committed fh-004) (bv/ops))
                           "the COMMITTED render connects, exactly once")
                       (is (= 1 (behaviors/connection-count)))
                       (is (= #{:probe/one} (behaviors/target-ids))
                           "and claims the semantic id the use site declared")
                       (teardown! container root)
                       (done)))
              (.catch (fn [e]
                        (is false (str "mount rejected: " e))
                        (teardown! container root)
                        (done)))))))))

(deftest fh-behavior-004-update-observes-movement-not-renders
  (testing "Per FH-BEHAVIOR-004: `:update` runs when the committed config
            MOVES by `rf=` and not otherwise. A behavior that reconciled its
            host on every commit would reset a cursor, a selection or a
            scroll offset for no reason at all — so a re-render carrying an
            equal config must leave the transcript untouched, and a moved one
            must arrive with both the new config and the previous one."
    (if-not (browser?)
      (skip! "the browser job runs the update assertions")
      (async done
        (setup!)
        (let [[container root] (mount!)]
          (-> (act #(.render root (element [bv/plain {:label "a"}])))
              (.then (fn [_]
                       (is (= (:committed fh-004) (bv/ops)))
                       ;; an equal config, rendered again
                       (act #(.render root (element [bv/plain {:label "a"}])))))
              (.then (fn [_]
                       (is (= (:equal-config fh-004) (bv/ops))
                           "an rf=-equal config is not movement")
                       (act #(.render root (element [bv/plain {:label "moved"}])))))
              (.then (fn [_]
                       (is (= (:moved-config fh-004) (bv/ops))
                           "a moved config reconciles the host, once")
                       (let [{:keys [config prev-config memory]} (last @bv/transcript)]
                         (is (= (:config (:moved fh-004)) config))
                         (is (= (:prev-config (:moved fh-004)) prev-config)
                             "with the previous config alongside the new one")
                         (is (= (:memory (:moved fh-004)) memory)
                             "and the private memory :connect returned"))
                       (teardown! container root)
                       (done)))
              (.catch (fn [e]
                        (is false (str "mount rejected: " e))
                        (teardown! container root)
                        (done)))))))))

(deftest fh-behavior-004-layout-timing-runs-before-passive
  (testing "Per FH-BEHAVIOR-004: `:timing :layout` means the work is complete
            before the browser paints. `mixed-timing` declares the PASSIVE
            behavior FIRST in document order and the layout one second, so a
            transcript in document order would prove the timing was ignored.
            The layout connection must come first, and its DOM write must be
            visible on the node."
    (if-not (browser?)
      (skip! "the browser job runs the timing assertions")
      (async done
        (setup!)
        (let [[container root] (mount!)]
          (-> (act #(.render root (element [bv/mixed-timing {}])))
              (.then (fn [_]
                       (is (= (:timing-order fh-004) (mapv :behavior @bv/transcript))
                           "the declared timing overrides document order")
                       (let [{:keys [attribute value]} (:layout fh-004)]
                         (is (= value (attr container ".node[data-id='layout']" attribute))
                             "and the layout behavior's DOM write is on its node"))
                       (is (= 2 (behaviors/connection-count)))
                       (teardown! container root)
                       (done)))
              (.catch (fn [e]
                        (is false (str "mount rejected: " e))
                        (teardown! container root)
                        (done)))))))))

;; ===========================================================================
;; FH-BEHAVIOR-005 — cleanup is TOTAL, and it is asserted as an ABSENCE
;; ===========================================================================

(deftest fh-behavior-005-teardown-releases-everything
  (testing "Per FH-BEHAVIOR-005: after the last behavior unmounts the
            substrate holds no connection record, no target claim, no node
            and no memory — the exact integer zero, not a threshold. The
            CONTROL mount is what makes the zero mean something: the same
            markup with no behavior attached is measured FIRST, proving the
            counters read zero for a mount that never connected, so the zero
            after teardown is the behavior's release rather than a counter
            that was never written. `:disconnect` runs EXACTLY once — both
            timing arms are installed on every render, and only the arm that
            connected may release."
    (if-not (browser?)
      (skip! "the browser job runs the teardown assertions")
      (async done
        (setup!)
        (let [[control-container control-root] (mount!)
              [container root]                 (mount!)]
          (-> (act #(.render control-root (element [bv/control-mount {}])))
              (.then (fn [_]
                       (let [{:keys [connections targets lifecycle]} (:control fh-005)]
                         (is (= connections (behaviors/connection-count))
                             "the CONTROL mount connects nothing")
                         (is (= (set targets) (behaviors/target-ids)))
                         (is (= lifecycle (bv/ops))))
                       (is (some? (.querySelector control-container ".node"))
                           "and it really did mount the same node")
                       (act #(teardown! control-container control-root))))
              (.then (fn [_]
                       (is (= 0 (behaviors/connection-count))
                           "tearing the control down changes nothing either")
                       (act #(.render root (element [bv/plain {}])))))
              (.then (fn [_]
                       (let [{:keys [connections targets lifecycle]} (:mounted fh-005)]
                         (is (= connections (behaviors/connection-count)))
                         (is (= (set targets) (behaviors/target-ids)))
                         (is (= lifecycle (bv/ops))))
                       (act #(teardown! container root))))
              (.then (fn [_]
                       (let [{:keys [connections targets lifecycle]} (:after-teardown fh-005)]
                         (is (= connections (behaviors/connection-count))
                             "after teardown the connection table is EMPTY")
                         (is (= (set targets) (behaviors/target-ids))
                             "and the live target index holds nothing")
                         (is (= lifecycle (bv/ops))
                             "with :disconnect having run exactly once"))
                       (done)))
              (.catch (fn [e]
                        (is false (str "mount rejected: " e))
                        (done)))))))))

(deftest fh-behavior-005-a-layout-behavior-releases-exactly-once-too
  (testing "Per FH-BEHAVIOR-005: both timing arms are installed on every
            render and guarded by the registered timing, so a `:layout`
            behavior must be released by the layout arm and by that arm only
            — a second release would show as a duplicate `:disconnect`, and a
            release by the wrong arm would show as none at all."
    (if-not (browser?)
      (skip! "the browser job runs the layout teardown assertions")
      (async done
        (setup!)
        (let [[container root] (mount!)]
          (-> (act #(.render root (element [bv/layout-timed {}])))
              (.then (fn [_]
                       (is (= 1 (behaviors/connection-count)))
                       (act #(teardown! container root))))
              (.then (fn [_]
                       (let [{:keys [connections targets lifecycle]}
                             (:layout-after-teardown fh-005)]
                         (is (= connections (behaviors/connection-count)))
                         (is (= (set targets) (behaviors/target-ids)))
                         (is (= lifecycle (bv/ops))))
                       (done)))
              (.catch (fn [e]
                        (is false (str "mount rejected: " e))
                        (done)))))))))

(deftest fh-behavior-005-a-partial-teardown-leaves-the-survivor-alone
  (testing "Per FH-BEHAVIOR-005: a release names the exact generation it
            opened, so removing one of two live behaviors releases that one
            and leaves the other's claim installed. A release keyed on the
            TARGET rather than the connection would be the same test with the
            survivor's claim missing."
    (if-not (browser?)
      (skip! "the browser job runs the partial-teardown assertions")
      (async done
        (setup!)
        (let [[container root] (mount!)]
          (-> (act #(.render root (element [bv/pair {}])))
              (.then (fn [_]
                       (is (= 2 (behaviors/connection-count)))
                       (is (= #{:probe/one :probe/two} (behaviors/target-ids)))
                       (act #(.render root (element [bv/plain {}])))))
              (.then (fn [_]
                       (let [{:keys [connections targets]} (:partial-teardown fh-005)]
                         (is (= connections (behaviors/connection-count)))
                         (is (= (set targets) (behaviors/target-ids))
                             "the survivor's claim is untouched"))
                       (act #(teardown! container root))))
              (.then (fn [_]
                       (is (zero? (behaviors/connection-count)))
                       (done)))
              (.catch (fn [e]
                        (is false (str "mount rejected: " e))
                        (done)))))))))

;; ===========================================================================
;; FH-BEHAVIOR-006 — the bounded command channel
;; ===========================================================================

(deftest fh-behavior-006-a-command-reaches-its-target-and-nothing-else
  (testing "Per FH-BEHAVIOR-006: two behaviors of the SAME registered type
            are live at once under DISTINCT caller-authored ids, and the
            command names one. If addressing were derived from render
            position, from the behavior id, or from a document query, the
            decoy would be reachable — so the assertion is symmetric: the
            named node carries the mark and the decoy carries nothing at all.
            It travels the real effect path, dispatched from an ordinary
            re-frame event handler."
    (if-not (browser?)
      (skip! "the browser job runs the command-delivery assertions")
      (async done
        (setup!)
        (let [[container root] (mount!)
              {:keys [command marked untouched lifecycle]} (:delivered fh-006)]
          (-> (act #(.render root (element [bv/pair {}])))
              (.then (fn [_]
                       (is (= 2 (behaviors/connection-count)))
                       (act #(rf/dispatch-sync [:probe/command command]
                                               {:frame frame-id}))))
              (.then (fn [_]
                       (is (= lifecycle (bv/ops))
                           "the command ran on exactly one connection")
                       (is (= (:value marked)
                              (attr container (:selector marked) (:attribute marked)))
                           "the named target performed the host work")
                       (is (nil? (attr container (:selector untouched)
                                       (:attribute untouched)))
                           "and the live DECOY was untouched")
                       (is (= :probe/one (:target (last @bv/transcript)))
                           "the context carried the target it was addressed by")
                       (teardown! container root)
                       (done)))
              (.catch (fn [e]
                        (is false (str "mount rejected: " e))
                        (teardown! container root)
                        (done)))))))))

(deftest fh-behavior-006-a-command-does-not-cross-into-another-frame
  (testing "Per FH-BEHAVIOR-006: a connection is committed under the frame its
            view was mounted in, and a command resolves its target in the frame
            the effect was produced in. Here the ONLY live connection claiming
            the target belongs to the decoy frame, so the command — issued from
            a frame that owns no such connection — must be refused exactly as if
            the target were absent, and the decoy's node must be untouched. A
            channel that filtered nothing would find the sole global claimant
            and mutate a node another frame owns."
    (if-not (browser?)
      (skip! "the browser job runs the frame-scope assertions")
      (async done
        (setup!)
        (let [[container root] (mount!)
              {:keys [command outcome untouched lifecycle]}
              (:crossing (:frame-scope fh-006))]
          (-> (act #(.render root (element-in decoy-frame-id [bv/plain {}])))
              (.then (fn [_]
                       (is (= 1 (behaviors/connection-count))
                           "the decoy frame really holds the only live claim")
                       (is (= outcome
                              (conf/caught-id
                                #(behaviors/command! frame-id command)))
                           "a command from the origin frame is refused")
                       (is (nil? (attr container (:selector untouched)
                                       (:attribute untouched)))
                           "and the other frame's node was not touched")
                       (is (= lifecycle (bv/ops))
                           "no host work ran at all")
                       (teardown! container root)
                       (done)))
              (.catch (fn [e]
                        (is false (str "mount rejected: " e))
                        (teardown! container root)
                        (done)))))))))

(deftest fh-behavior-006-one-target-per-frame-is-not-ambiguous
  (testing "Per FH-BEHAVIOR-006: uniqueness is a claim about ONE frame. Two
            independent frames mounting the same declaration legitimately claim
            the same library target — that is two addresses, not one ambiguity —
            so each frame's command reaches its own connection and neither is
            refused. A process-global target index would collapse the pair and
            refuse both. Both commands travel the real effect path, so the frame
            they are scoped by is the one the fx context supplied."
    (if-not (browser?)
      (skip! "the browser job runs the per-frame delivery assertions")
      (async done
        (setup!)
        (let [[container-a root-a] (mount!)
              [container-b root-b] (mount!)
              {:keys [per-frame lifecycle]} (:frame-scope fh-006)
              [origin decoy]                per-frame]
          (-> (act #(.render root-a (element-in frame-id [bv/plain {}])))
              (.then (fn [_] (act #(.render root-b (element-in decoy-frame-id
                                                               [bv/plain {}])))))
              (.then (fn [_]
                       (is (= 2 (behaviors/connection-count))
                           "two live connections, one per frame")
                       (is (= #{:probe/one} (behaviors/target-ids))
                           "claiming the SAME semantic target")
                       (act #(rf/dispatch-sync [:probe/command (:command origin)]
                                               {:frame frame-id}))))
              (.then (fn [_] (act #(rf/dispatch-sync [:probe/command (:command decoy)]
                                                     {:frame decoy-frame-id}))))
              (.then (fn [_]
                       (doseq [[container {:keys [note marked]}]
                               [[container-a origin] [container-b decoy]]]
                         (is (= (:value marked)
                                (attr container (:selector marked)
                                      (:attribute marked)))
                             note))
                       (is (= lifecycle (bv/ops))
                           "each command ran exactly once, and neither was refused")
                       (teardown! container-a root-a)
                       (teardown! container-b root-b)
                       (done)))
              (.catch (fn [e]
                        (is false (str "mount rejected: " e))
                        (teardown! container-a root-a)
                        (teardown! container-b root-b)
                        (done)))))))))

(deftest fh-behavior-006-every-other-outcome-is-a-visible-refusal
  (testing "Per FH-BEHAVIOR-006: an absent target, an ambiguous target, an
            unregistered operation and a malformed command are each refused
            with a typed diagnostic and perform NO host work. The refusals
            are driven at the channel itself rather than through the event
            loop, so the assertion is on the diagnostic the channel raises
            rather than on whatever an fx wrapper makes of it."
    (if-not (browser?)
      (skip! "the browser job runs the command-refusal assertions")
      (async done
        (setup!)
        (let [[container root] (mount!)]
          (letfn [(step [remaining]
                    (if-let [{:keys [note view command outcome]} (first remaining)]
                      (-> (act #(.render root (element [(case view
                                                          :pair  bv/pair
                                                          :twins bv/twins)
                                                        {}])))
                          (.then (fn [_]
                                   (let [before (bv/ops)
                                         got    (conf/caught-id
                                                  #(behaviors/command! frame-id command))]
                                     (is (= outcome got) note)
                                     (is (= before (bv/ops))
                                         (str note " — and no host work ran")))
                                   (step (rest remaining)))))
                      (js/Promise.resolve :done)))]
          (-> (step (:refusals fh-006))
              (.then (fn [_]
                       (teardown! container root)
                       (done)))
              (.catch (fn [e]
                        (is false (str "mount rejected: " e))
                        (teardown! container root)
                        (done))))))))))

(deftest fh-behavior-006-a-command-is-refused-after-teardown-never-queued
  (testing "Per FH-BEHAVIOR-006: once the connection is released, the same
            command that worked a moment ago is refused. Nothing is retained
            for a future mount — a queued imperative request would arrive at
            a node the application has since changed its mind about, and a
            future mount is driven by state and config or by a fresh event."
    (if-not (browser?)
      (skip! "the browser job runs the post-teardown assertions")
      (async done
        (setup!)
        (let [[container root] (mount!)
              {:keys [command outcome]} (:after-teardown fh-006)]
          (-> (act #(.render root (element [bv/plain {}])))
              (.then (fn [_]
                       (is (= conf/no-throw
                              (conf/caught-id
                                #(behaviors/command!
                                   frame-id {:target :probe/one :op :mark})))
                           "the command works while the connection is live")
                       (act #(teardown! container root))))
              (.then (fn [_]
                       (is (= outcome (conf/caught-id
                                        #(behaviors/command! frame-id command)))
                           "and is refused the moment the connection is gone")
                       (is (zero? (behaviors/connection-count)))
                       (done)))
              (.catch (fn [e]
                        (is false (str "mount rejected: " e))
                        (done)))))))))

(deftest fh-behavior-006-an-outward-context-is-fenced-to-its-generation
  (testing "Per FH-BEHAVIOR-006: a behavior's `:dispatch` is fenced to the
            connection that minted it. A host callback that outlives its node
            — a listener the library forgot, a promise that resolves late —
            must be INERT rather than firing into a successor. The suite holds
            the context past its own teardown, which is the only way to prove
            the fence rather than merely never exercise it."
    (if-not (browser?)
      (skip! "the browser job runs the fenced-dispatch assertions")
      (async done
        (setup!)
        (let [[container root] (mount!)
              {:keys [event while-live after-teardown]} (:fenced-dispatch fh-006)]
          (-> (act #(.render root (element [bv/plain {}])))
              (.then (fn [_]
                       (is (some? @bv/last-dispatch)
                           "connect handed the behavior an outward dispatch")
                       (is (= while-live (@bv/last-dispatch event))
                           "which is live while its connection is")
                       (act #(teardown! container root))))
              (.then (fn [_]
                       (is (= after-teardown (@bv/last-dispatch event))
                           "and inert the moment the connection is released")
                       (done)))
              (.catch (fn [e]
                        (is false (str "mount rejected: " e))
                        (done)))))))))

(deftest the-command-channel-is-a-registered-effect
  (testing "Per FH-BEHAVIOR-006: the channel is an ordinary re-frame effect,
            registered under the reserved id — which is what makes a command
            something an event handler RETURNS as data rather than something
            a view calls."
    (is (= (:fx-id fh-006) behaviors/command-fx-id))
    (is (some? (rf/handler-meta :fx behaviors/command-fx-id))
        "the reserved fx id is registered")))
