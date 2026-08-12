(ns re-frame.freehand.behaviors-dom-cljs-test
  "FH-BEHAVIOR-004 / -005 / -006 / -008 — the MOUNTED half of the behavior
  contract.

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
                     its explicit semantic target IN ITS OWN FRAME and
                     NOTHING else (a live decoy, and a second frame, prove
                     the `nothing else`), and every other outcome is a
                     visible refusal that performs no host work.
    FH-BEHAVIOR-008  the tool plane is two read-only projections over that
                     same live table, and what they OMIT — node, memory,
                     any route to a host instance — is the assertion. They
                     cross the ONE public door, and the door read is the
                     SAME projection rather than a second one.

  This file rides the browser lane through its `-dom-cljs-test` suffix. It
  also matches the node suites' broader regex, where it has no DOM to mount
  and says so rather than passing quietly."
  (:require ["react" :as react]
            [cljs.test :refer-macros [async deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            ;; The DOOR, required alongside the internal namespace on
            ;; purpose: FH-BEHAVIOR-008 asserts that the published verbs are
            ;; the SAME projections rather than a second pair that could
            ;; drift, and only a suite holding both can say so.
            [re-frame.freehand :as v]
            [re-frame.freehand.behavior-views :as bv]
            [re-frame.freehand.behaviors :as behaviors]
            [re-frame.freehand.conformance :as conf]
            [re-frame.freehand.mount-support :as ms]
            [re-frame.freehand.react :as fr]
            [re-frame.freehand.shell :as shell]
            [re-frame.live-frame :as live-frame]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter       plain-atom/adapter
     :ambient-frame nil
     :async?        true
     ;; Scope the shared lifecycle ledger to this test. Bookkeeping only —
     ;; it forgets what the previous test mounted and tears nothing down, so
     ;; `ms/residue-clean!` reads a leak AFTER a test rather than a reset
     ;; hiding one before the next.
     :init-fn       (fn [] (ms/reset-ledger!))}))

(def ^:private fh-004 (conf/fixture :FH-BEHAVIOR-004))
(def ^:private fh-005 (conf/fixture :FH-BEHAVIOR-005))
(def ^:private fh-006 (conf/fixture :FH-BEHAVIOR-006))
(def ^:private fh-008 (conf/fixture :FH-BEHAVIOR-008))
(def ^:private fh-009 (conf/fixture :FH-BEHAVIOR-009))

(def ^:private frame-id :dom/behaviors)

(def ^:private decoy-frame-id
  "A SECOND live frame, mounted with the same declaration under the same
  semantic target. Frame isolation is not observable with one frame: the
  decoy is what makes `the command stayed in its own frame` an assertion
  rather than a description."
  :dom/behaviors-other)

;; The mounted LIFECYCLE — the `act` boundary, the `[container root]` pair
;; and the teardown — comes from `re-frame.freehand.mount-support`, the one
;; facade the browser tier shares (rf2-n9rzw). This file used to carry its
;; own byte-identical copy of each.

(defn- released!
  "FH-BEHAVIOR-005's absence, read AFTER teardown: the facade's own books
  (every root retired, its container empty and detached) plus the
  substrate's two — the connection table and the live target index — as
  EXACT zeros rather than thresholds.

  The rows below already compare those two books against the fixture's
  stated `:after-teardown` answers, which is what earns the claim. This
  adds the half no per-suite teardown can make: that the ROOT itself went,
  which is the leak that contaminates every later suite in the process
  rather than this one."
  [where]
  (ms/residue-clean! where
                     [["the connection table"  #(behaviors/connection-count)]
                      ["the live target index" #(behaviors/target-ids)]]))

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
    (if-not (ms/browser?)
      (ms/skip! "the browser job runs the commit-only assertions")
      (async done
        (setup!)
        (reset! suspender-renders 0)
        (let [[container root] (ms/create-root!)]
          (-> (ms/act #(.render root (probe-element true)))
              (.then (fn [_]
                       (is (pos? @suspender-renders)
                           "the candidate really rendered — it reached the suspending sibling")
                       (is (some? (.querySelector container "#fallback"))
                           "and really did not commit — the fallback is what is on screen")
                       (is (= (:abandoned fh-004) (bv/ops))
                           "so NOTHING connected")
                       (is (zero? (behaviors/connection-count))
                           "and the connection table is empty")
                       (ms/act #(.render root (probe-element false)))))
              (.then (fn [_]
                       (is (= (:committed fh-004) (bv/ops))
                           "the COMMITTED render connects, exactly once")
                       (is (= 1 (behaviors/connection-count)))
                       (is (= #{:probe/one} (behaviors/target-ids))
                           "and claims the semantic id the use site declared")))
              ;; Reports and releases; it never finishes (rf2-fyba). `cljs.test`
              ;; hands `done` a continuation that runs the WHOLE REMAINDER of the
              ;; run synchronously, so a `.catch` downstream of it claims whatever
              ;; a later namespace throws as this row's failure, prints it against
              ;; this row's label, and fires `done` a SECOND time — re-forcing
              ;; `run-block`'s unrealized delay and re-running that namespace.
              ;; `ms/destroy-root!` is what both arms duplicated, so it rides the
              ;; single trailing step: written once, still run once per path.
              (.catch (fn [e]
                        (is false (str "mount rejected: " e))
                        nil))
              (.then (fn [_]
                       (ms/destroy-root! container root)
                       (done)))))))))

(deftest fh-behavior-004-update-observes-movement-not-renders
  (testing "Per FH-BEHAVIOR-004: `:update` runs when the committed config
            MOVES by `rf=` and not otherwise. A behavior that reconciled its
            host on every commit would reset a cursor, a selection or a
            scroll offset for no reason at all — so a re-render carrying an
            equal config must leave the transcript untouched, and a moved one
            must arrive with both the new config and the previous one."
    (if-not (ms/browser?)
      (ms/skip! "the browser job runs the update assertions")
      (async done
        (setup!)
        (let [[container root] (ms/create-root!)]
          (-> (ms/act #(.render root (element [bv/plain {:label "a"}])))
              (.then (fn [_]
                       (is (= (:committed fh-004) (bv/ops)))
                       ;; an equal config, rendered again
                       (ms/act #(.render root (element [bv/plain {:label "a"}])))))
              (.then (fn [_]
                       (is (= (:equal-config fh-004) (bv/ops))
                           "an rf=-equal config is not movement")
                       (ms/act #(.render root (element [bv/plain {:label "moved"}])))))
              (.then (fn [_]
                       (is (= (:moved-config fh-004) (bv/ops))
                           "a moved config reconciles the host, once")
                       (let [{:keys [config prev-config memory]} (last @bv/transcript)]
                         (is (= (:config (:moved fh-004)) config))
                         (is (= (:prev-config (:moved fh-004)) prev-config)
                             "with the previous config alongside the new one")
                         (is (= (:memory (:moved fh-004)) memory)
                             "and the private memory :connect returned"))))
              ;; Reports and releases; it never finishes (rf2-fyba). The teardown
              ;; both arms duplicated rides the single trailing step.
              (.catch (fn [e]
                        (is false (str "mount rejected: " e))
                        nil))
              (.then (fn [_]
                       (ms/destroy-root! container root)
                       (done)))))))))

(deftest fh-behavior-004-layout-timing-runs-before-passive
  (testing "Per FH-BEHAVIOR-004: `:timing :layout` means the work is complete
            before the browser paints. `mixed-timing` declares the PASSIVE
            behavior FIRST in document order and the layout one second, so a
            transcript in document order would prove the timing was ignored.
            The layout connection must come first, and its DOM write must be
            visible on the node."
    (if-not (ms/browser?)
      (ms/skip! "the browser job runs the timing assertions")
      (async done
        (setup!)
        (let [[container root] (ms/create-root!)]
          (-> (ms/act #(.render root (element [bv/mixed-timing {}])))
              (.then (fn [_]
                       (is (= (:timing-order fh-004) (mapv :behavior @bv/transcript))
                           "the declared timing overrides document order")
                       (let [{:keys [attribute value]} (:layout fh-004)]
                         (is (= value (attr container ".node[data-id='layout']" attribute))
                             "and the layout behavior's DOM write is on its node"))
                       (is (= 2 (behaviors/connection-count)))))
              ;; Reports and releases; it never finishes (rf2-fyba). The teardown
              ;; both arms duplicated rides the single trailing step.
              (.catch (fn [e]
                        (is false (str "mount rejected: " e))
                        nil))
              (.then (fn [_]
                       (ms/destroy-root! container root)
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
    (if-not (ms/browser?)
      (ms/skip! "the browser job runs the teardown assertions")
      (async done
        (setup!)
        (let [[control-container control-root] (ms/create-root!)
              [container root]                 (ms/create-root!)]
          (-> (ms/act #(.render control-root (element [bv/control-mount {}])))
              (.then (fn [_]
                       (let [{:keys [connections targets lifecycle]} (:control fh-005)]
                         (is (= connections (behaviors/connection-count))
                             "the CONTROL mount connects nothing")
                         (is (= (set targets) (behaviors/target-ids)))
                         (is (= lifecycle (bv/ops))))
                       (is (some? (.querySelector control-container ".node"))
                           "and it really did mount the same node")
                       (ms/act #(ms/destroy-root! control-container control-root))))
              (.then (fn [_]
                       (is (= 0 (behaviors/connection-count))
                           "tearing the control down changes nothing either")
                       (ms/act #(.render root (element [bv/plain {}])))))
              (.then (fn [_]
                       (let [{:keys [connections targets lifecycle]} (:mounted fh-005)]
                         (is (= connections (behaviors/connection-count)))
                         (is (= (set targets) (behaviors/target-ids)))
                         (is (= lifecycle (bv/ops))))
                       (ms/act #(ms/destroy-root! container root))))
              (.then (fn [_]
                       (let [{:keys [connections targets lifecycle]} (:after-teardown fh-005)]
                         (is (= connections (behaviors/connection-count))
                             "after teardown the connection table is EMPTY")
                         (is (= (set targets) (behaviors/target-ids))
                             "and the live target index holds nothing")
                         (is (= lifecycle (bv/ops))
                             "with :disconnect having run exactly once"))
                       ;; BOTH roots — the control and the behaving one —
                       ;; are read here, which is what the shared ledger
                       ;; buys over a per-suite teardown.
                       (released! "FH-BEHAVIOR-005 — after the last unmount")))
              ;; Reports and releases; it never finishes (rf2-fyba). Nothing to
              ;; hoist here — both roots were already torn down upstream, inside
              ;; the chain, which is what the residue read above depends on.
              (.catch (fn [e]
                        (is false (str "mount rejected: " e))
                        nil))
              (.then (fn [_] (done)))))))))

(deftest fh-behavior-005-a-layout-behavior-releases-exactly-once-too
  (testing "Per FH-BEHAVIOR-005: both timing arms are installed on every
            render and guarded by the registered timing, so a `:layout`
            behavior must be released by the layout arm and by that arm only
            — a second release would show as a duplicate `:disconnect`, and a
            release by the wrong arm would show as none at all."
    (if-not (ms/browser?)
      (ms/skip! "the browser job runs the layout teardown assertions")
      (async done
        (setup!)
        (let [[container root] (ms/create-root!)]
          (-> (ms/act #(.render root (element [bv/layout-timed {}])))
              (.then (fn [_]
                       (is (= 1 (behaviors/connection-count)))
                       (ms/act #(ms/destroy-root! container root))))
              (.then (fn [_]
                       (let [{:keys [connections targets lifecycle]}
                             (:layout-after-teardown fh-005)]
                         (is (= connections (behaviors/connection-count)))
                         (is (= (set targets) (behaviors/target-ids)))
                         (is (= lifecycle (bv/ops))))
                       (released! "FH-BEHAVIOR-005 — after the layout arm releases")))
              ;; Reports and releases; it never finishes (rf2-fyba). Teardown
              ;; already ran upstream, so nothing rides the trailing step but `done`.
              (.catch (fn [e]
                        (is false (str "mount rejected: " e))
                        nil))
              (.then (fn [_] (done)))))))))

(deftest fh-behavior-005-a-partial-teardown-leaves-the-survivor-alone
  (testing "Per FH-BEHAVIOR-005: a release names the exact generation it
            opened, so removing one of two live behaviors releases that one
            and leaves the other's claim installed. A release keyed on the
            TARGET rather than the connection would be the same test with the
            survivor's claim missing."
    (if-not (ms/browser?)
      (ms/skip! "the browser job runs the partial-teardown assertions")
      (async done
        (setup!)
        (let [[container root] (ms/create-root!)]
          (-> (ms/act #(.render root (element [bv/pair {}])))
              (.then (fn [_]
                       (is (= 2 (behaviors/connection-count)))
                       (is (= #{:probe/one :probe/two} (behaviors/target-ids)))
                       (ms/act #(.render root (element [bv/plain {}])))))
              (.then (fn [_]
                       (let [{:keys [connections targets]} (:partial-teardown fh-005)]
                         (is (= connections (behaviors/connection-count)))
                         (is (= (set targets) (behaviors/target-ids))
                             "the survivor's claim is untouched"))
                       (ms/act #(ms/destroy-root! container root))))
              (.then (fn [_]
                       (is (zero? (behaviors/connection-count)))
                       (released! "FH-BEHAVIOR-005 — after the survivor's own unmount")))
              ;; Reports and releases; it never finishes (rf2-fyba). Teardown
              ;; already ran upstream, so nothing rides the trailing step but `done`.
              (.catch (fn [e]
                        (is false (str "mount rejected: " e))
                        nil))
              (.then (fn [_] (done)))))))))

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
    (if-not (ms/browser?)
      (ms/skip! "the browser job runs the command-delivery assertions")
      (async done
        (setup!)
        (let [[container root] (ms/create-root!)
              {:keys [command marked untouched lifecycle]} (:delivered fh-006)]
          (-> (ms/act #(.render root (element [bv/pair {}])))
              (.then (fn [_]
                       (is (= 2 (behaviors/connection-count)))
                       (ms/act #(rf/dispatch-sync [:probe/command command]
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
                           "the context carried the target it was addressed by")))
              ;; Reports and releases; it never finishes (rf2-fyba). The teardown
              ;; both arms duplicated rides the single trailing step.
              (.catch (fn [e]
                        (is false (str "mount rejected: " e))
                        nil))
              (.then (fn [_]
                       (ms/destroy-root! container root)
                       (done)))))))))

(deftest fh-behavior-006-a-command-does-not-cross-into-another-frame
  (testing "Per FH-BEHAVIOR-006: a connection is committed under the frame its
            view was mounted in, and a command resolves its target in the frame
            the effect was produced in. Here the ONLY live connection claiming
            the target belongs to the decoy frame, so the command — issued from
            a frame that owns no such connection — must be refused exactly as if
            the target were absent, and the decoy's node must be untouched. A
            channel that filtered nothing would find the sole global claimant
            and mutate a node another frame owns.

            The refusal's PAYLOAD is asserted here too, because the frame is
            half the address and a diagnostic that did not say so would send a
            reader looking for a bug in the target instead of in the frame. The
            record names the frame the command was ISSUED in — not the frame
            that happens to hold the connection — and its `:live` alternatives
            are that frame's claims alone. The decoy's `:probe/one` is live
            process-wide at this instant, which is what makes an empty `:live`
            an assertion rather than an accident: naming it would offer a
            recovery the command could never have taken."
    (if-not (ms/browser?)
      (ms/skip! "the browser job runs the frame-scope assertions")
      (async done
        (setup!)
        (let [[container root] (ms/create-root!)
              {:keys [command outcome untouched lifecycle]}
              (:crossing (:frame-scope fh-006))]
          (-> (ms/act #(.render root (element-in decoy-frame-id [bv/plain {}])))
              (.then (fn [_]
                       (is (= 1 (behaviors/connection-count))
                           "the decoy frame really holds the only live claim")
                       (is (= #{(:target command)} (behaviors/target-ids))
                           "and that claim is on the very id the command names")
                       (let [d (conf/caught-data
                                 #(behaviors/command! frame-id command))]
                         (is (= outcome (:rf.error/id d))
                             "a command from the origin frame is refused")
                         (is (= frame-id (:frame d))
                             (str "and the refusal names the ORIGIN frame — the "
                                  "frame the command resolved in, not the one "
                                  "holding the connection; got "
                                  (pr-str (:frame d))))
                         (is (= [] (:live d))
                             (str "and its :live alternatives are that frame's "
                                  "claims alone, so a sibling frame's live "
                                  "target is not offered as reachable; got "
                                  (pr-str (:live d)))))
                       (is (nil? (attr container (:selector untouched)
                                       (:attribute untouched)))
                           "and the other frame's node was not touched")
                       (is (= lifecycle (bv/ops))
                           "no host work ran at all")))
              ;; Reports and releases; it never finishes (rf2-fyba). The teardown
              ;; both arms duplicated rides the single trailing step.
              (.catch (fn [e]
                        (is false (str "mount rejected: " e))
                        nil))
              (.then (fn [_]
                       (ms/destroy-root! container root)
                       (done)))))))))

(deftest fh-behavior-006-one-target-per-frame-is-not-ambiguous
  (testing "Per FH-BEHAVIOR-006: uniqueness is a claim about ONE frame. Two
            independent frames mounting the same declaration legitimately claim
            the same library target — that is two addresses, not one ambiguity —
            so each frame's command reaches its own connection and neither is
            refused. A process-global target index would collapse the pair and
            refuse both. Both commands travel the real effect path, so the frame
            they are scoped by is the one the fx context supplied."
    (if-not (ms/browser?)
      (ms/skip! "the browser job runs the per-frame delivery assertions")
      (async done
        (setup!)
        (let [[container-a root-a] (ms/create-root!)
              [container-b root-b] (ms/create-root!)
              {:keys [per-frame lifecycle]} (:frame-scope fh-006)
              [origin decoy]                per-frame]
          (-> (ms/act #(.render root-a (element-in frame-id [bv/plain {}])))
              (.then (fn [_] (ms/act #(.render root-b (element-in decoy-frame-id
                                                               [bv/plain {}])))))
              (.then (fn [_]
                       (is (= 2 (behaviors/connection-count))
                           "two live connections, one per frame")
                       (is (= #{:probe/one} (behaviors/target-ids))
                           "claiming the SAME semantic target")
                       (ms/act #(rf/dispatch-sync [:probe/command (:command origin)]
                                               {:frame frame-id}))))
              (.then (fn [_] (ms/act #(rf/dispatch-sync [:probe/command (:command decoy)]
                                                     {:frame decoy-frame-id}))))
              (.then (fn [_]
                       (doseq [[container {:keys [note marked]}]
                               [[container-a origin] [container-b decoy]]]
                         (is (= (:value marked)
                                (attr container (:selector marked)
                                      (:attribute marked)))
                             note))
                       (is (= lifecycle (bv/ops))
                           "each command ran exactly once, and neither was refused")))
              ;; Reports and releases; it never finishes (rf2-fyba). BOTH teardowns
              ;; were duplicated across the two arms, so both ride the single
              ;; trailing step.
              (.catch (fn [e]
                        (is false (str "mount rejected: " e))
                        nil))
              (.then (fn [_]
                       (ms/destroy-root! container-a root-a)
                       (ms/destroy-root! container-b root-b)
                       (done)))))))))

(deftest fh-behavior-006-every-other-outcome-is-a-visible-refusal
  (testing "Per FH-BEHAVIOR-006: an absent target, an ambiguous target, an
            unregistered operation and a malformed command are each refused
            with a typed diagnostic and perform NO host work. The refusals
            are driven at the channel itself rather than through the event
            loop, so the assertion is on the diagnostic the channel raises
            rather than on whatever an fx wrapper makes of it."
    (if-not (ms/browser?)
      (ms/skip! "the browser job runs the command-refusal assertions")
      (async done
        (setup!)
        (let [[container root] (ms/create-root!)]
          (letfn [(step [remaining]
                    (if-let [{:keys [note view command outcome]} (first remaining)]
                      (-> (ms/act #(.render root (element [(case view
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
              ;; Reports and releases; it never finishes (rf2-fyba). The teardown
              ;; both arms duplicated rides the single trailing step.
              (.catch (fn [e]
                        (is false (str "mount rejected: " e))
                        nil))
              (.then (fn [_]
                       (ms/destroy-root! container root)
                       (done))))))))))

(deftest fh-behavior-006-a-command-is-refused-after-teardown-never-queued
  (testing "Per FH-BEHAVIOR-006: once the connection is released, the same
            command that worked a moment ago is refused. Nothing is retained
            for a future mount — a queued imperative request would arrive at
            a node the application has since changed its mind about, and a
            future mount is driven by state and config or by a fresh event."
    (if-not (ms/browser?)
      (ms/skip! "the browser job runs the post-teardown assertions")
      (async done
        (setup!)
        (let [[container root] (ms/create-root!)
              {:keys [command outcome]} (:after-teardown fh-006)]
          (-> (ms/act #(.render root (element [bv/plain {}])))
              (.then (fn [_]
                       (is (= conf/no-throw
                              (conf/caught-id
                                #(behaviors/command!
                                   frame-id {:target :probe/one :op :mark})))
                           "the command works while the connection is live")
                       (ms/act #(ms/destroy-root! container root))))
              (.then (fn [_]
                       (is (= outcome (conf/caught-id
                                        #(behaviors/command! frame-id command)))
                           "and is refused the moment the connection is gone")
                       (is (zero? (behaviors/connection-count)))))
              ;; Reports and releases; it never finishes (rf2-fyba). Teardown
              ;; already ran upstream, so nothing rides the trailing step but `done`.
              (.catch (fn [e]
                        (is false (str "mount rejected: " e))
                        nil))
              (.then (fn [_] (done)))))))))

(deftest fh-behavior-006-an-outward-context-is-fenced-to-its-generation
  (testing "Per FH-BEHAVIOR-006: a behavior's `:dispatch` is fenced to the
            connection that minted it. A host callback that outlives its node
            — a listener the library forgot, a promise that resolves late —
            must be INERT rather than firing into a successor. The suite holds
            the context past its own teardown, which is the only way to prove
            the fence rather than merely never exercise it."
    (if-not (ms/browser?)
      (ms/skip! "the browser job runs the fenced-dispatch assertions")
      (async done
        (setup!)
        (let [[container root] (ms/create-root!)
              {:keys [event while-live after-teardown]} (:fenced-dispatch fh-006)]
          (-> (ms/act #(.render root (element [bv/plain {}])))
              (.then (fn [_]
                       (is (some? @bv/last-dispatch)
                           "connect handed the behavior an outward dispatch")
                       (is (= while-live (@bv/last-dispatch event))
                           "which is live while its connection is")
                       (ms/act #(ms/destroy-root! container root))))
              (.then (fn [_]
                       (is (= after-teardown (@bv/last-dispatch event))
                           "and inert the moment the connection is released")))
              ;; Reports and releases; it never finishes (rf2-fyba). Teardown
              ;; already ran upstream, so nothing rides the trailing step but `done`.
              (.catch (fn [e]
                        (is false (str "mount rejected: " e))
                        nil))
              (.then (fn [_] (done)))))))))

(deftest the-command-channel-is-a-registered-effect
  (testing "Per FH-BEHAVIOR-006: the channel is an ordinary re-frame effect,
            registered under the reserved id — which is what makes a command
            something an event handler RETURNS as data rather than something
            a view calls."
    (is (= (:fx-id fh-006) behaviors/command-fx-id))
    (is (some? (rf/handler-meta :fx behaviors/command-fx-id))
        "the reserved fx id is registered")))

;; ===========================================================================
;; FH-BEHAVIOR-009 — `:connect` establishes the memory; nothing else writes it
;; ===========================================================================
;;
;; The row for the ordinary integration. Every OTHER behavior in this corpus
;; returns its memory from `:update` and from each command, which is what a
;; Clojure author writes without thinking — and it hid the defect completely
;; (rf2-wj1ao). The libraries a behavior exists for do not look like that:
;; `map.setOptions(…)`, `workbook.setValue(…)`, `chart.update(spec)` and
;; `addEventListener` mutate and answer NOTHING. `bv/mutator` is that shape, so
;; this is the one row where a lifecycle entry's return being written back would
;; be observable — as an empty memory at `:disconnect`, and a host instance
;; nobody released.

(deftest fh-behavior-009-only-connect-establishes-the-memory
  (testing "Per FH-BEHAVIOR-009: `:connect` establishes the private memory and
            nothing else writes it. `bv/mutator`'s `:update` and its `:mutate`
            command are VOID host mutators — the ordinary JS shape — so if
            either return replaced the memory, `:disconnect` would be handed
            nothing and the instance it must release would be gone. The
            assertion is made at all three moments, because a memory that
            survived the update and died at the command is the same defect one
            step later; and the mutations are read off the instance's OWN cell,
            so the evidence is that the entries ran against the SAME instance
            rather than that some map happened to survive."
    (if-not (ms/browser?)
      (ms/skip! "the browser job runs the memory-ownership assertions")
      (async done
        (setup!)
        (let [[container root] (ms/create-root!)
              {:keys [lifecycle instance mutations command moved]} fh-009
              memory-at (fn [op] (some #(when (= op (:op %)) (:memory %))
                                       @bv/transcript))]
          (-> (ms/act #(.render root (element [bv/mutating {}])))
              (.then (fn [_]
                       (is (= 1 (behaviors/connection-count)))
                       ;; a MOVED config, so `:update` really runs
                       (ms/act #(.render root (element [bv/mutating moved])))))
              (.then (fn [_]
                       (is (= instance (:instance (memory-at :update)))
                           "`:update` receives the instance `:connect` built")
                       (ms/act #(rf/dispatch-sync [:probe/command command]
                                               {:frame frame-id}))))
              (.then (fn [_]
                       (is (= instance (:instance (memory-at :mutate)))
                           "and so does the command, AFTER `:update` returned nothing")
                       (ms/act #(ms/destroy-root! container root))))
              (.then (fn [_]
                       (is (= lifecycle (bv/ops))
                           "the whole lifecycle ran, in order")
                       (let [released (memory-at :disconnect)]
                         (is (= instance (:instance released))
                             "and `:disconnect` is handed the instance to release —
                              the claim two void returns used to erase")
                         (is (= mutations @(:calls released))
                             "both void mutators ran against that SAME instance"))
                       (is (zero? (behaviors/connection-count)))))
              ;; Reports and releases; it never finishes (rf2-fyba). The teardown
              ;; here is ASYMMETRIC and stays put: the success path already tore
              ;; the root down upstream — the `:disconnect` assertions above depend
              ;; on it having run — so this is the failure arm's own defensive
              ;; cleanup for a rejection that arrived before that point. Hoisting
              ;; it would destroy the root twice on the success path.
              (.catch (fn [e]
                        (is false (str "mount rejected: " e))
                        (ms/destroy-root! container root)
                        nil))
              (.then (fn [_] (done)))))))))

;; ===========================================================================
;; FH-BEHAVIOR-008 — the tool plane: two read-only projections
;; ===========================================================================

(defn- absent-keys-are-absent
  "Assert that no row in `rows` carries any of `absent`. The whole point of
  a projection is what it does NOT answer, so this is the load-bearing
  half — a leak of `:node` or `:memory` would otherwise pass every
  positive assertion above it."
  [rows absent note]
  (doseq [row rows, k absent]
    (is (not (contains? row k))
        (str note " — the projection answers no " k))))

(deftest fh-behavior-008-active-connections-project-the-public-half-only
  (testing "Per FH-BEHAVIOR-008: the active-connection projection answers
            which behaviors are connected, under which frame, claiming which
            semantic ids, on which public config — as VALUES, oldest first.
            The omission is the law: no node, no private memory, no
            lifecycle entry and no route to a host instance, absent because
            the projection is built from the record's public half rather
            than filtered out of its whole."
    (if-not (ms/browser?)
      (ms/skip! "the browser job runs the projection assertions")
      (async done
        (setup!)
        (let [[container root]        (ms/create-root!)
              {:keys [present absent]} (:connection-keys fh-008)
              connected                (:connected fh-008)
              no-target                (:no-target fh-008)]
          (-> (ms/act #(.render root (element [bv/plain {}])))
              (.then (fn [_]
                       (let [rows (behaviors/active-connections)
                             row  (first rows)]
                         (is (= 1 (count rows))
                             "one mounted behavior, one projected connection")
                         (is (= ["active-connections" "command-log"]
                                (:published (:door fh-008)))
                             "non-vacuous: the fixture really names the two door verbs")
                         (is (= rows (v/active-connections))
                             "and the DOOR answers that same projection — a tool
                              reads the live plane through re-frame.freehand
                              alone, never through the implementation namespace")
                         (is (= (set present) (set (keys row)))
                             "the key roster is closed in the present direction")
                         (absent-keys-are-absent rows absent "a live connection")
                         (is (= (:behavior connected) (:behavior row)))
                         (is (= (:target connected) (:target row)))
                         (is (= (:config connected) (:config row)))
                         (is (= frame-id (:frame row))
                             "carrying the frame the connection was committed under")
                         (is (pos-int? (:generation row))
                             "and the generation the release will name"))
                       (ms/act #(.render root (element [bv/no-target {}])))))
              (.then (fn [_]
                       (let [row (first (behaviors/active-connections))]
                         (is (= (disj (set present) :target) (set (keys row)))
                             "a behavior nothing commands projects NO target — absent, never nil")
                         (is (= (:config no-target) (:config row))))
                       (ms/act #(.render root (element [bv/pair {}])))))
              (.then (fn [_]
                       (let [rows (behaviors/active-connections)]
                         (is (= 2 (count rows)))
                         (is (= (sort (map :generation rows)) (map :generation rows))
                             "projected oldest first, by the generation that ordered them")
                         (is (= [:probe/one :probe/two] (mapv :target rows))))))
              ;; Reports and releases; it never finishes (rf2-fyba). The teardown
              ;; both arms duplicated rides the single trailing step.
              (.catch (fn [e]
                        (is false (str "mount rejected: " e))
                        nil))
              (.then (fn [_]
                       (ms/destroy-root! container root)
                       (done)))))))))

(deftest fh-behavior-008-the-command-log-records-what-was-asked-and-decided
  (testing "Per FH-BEHAVIOR-008: the command-traffic projection records what
            each command NAMED and what the channel DECIDED — refusals as
            faithfully as deliveries, because a projection that only saw the
            successes would be evidence for the one case nobody debugs. The
            resolved behavior and generation appear on a delivered row only,
            and the operation's return value — the connection's private
            memory — has no representation here at all."
    (if-not (ms/browser?)
      (ms/skip! "the browser job runs the command-traffic assertions")
      (async done
        (setup!)
        (let [[container root]        (ms/create-root!)
              {:keys [present absent]} (:log-keys fh-008)
              traffic                  (:traffic fh-008)]
          (-> (ms/act #(.render root (element [bv/plain {}])))
              (.then (fn [_]
                       (is (empty? (behaviors/command-log))
                           "a mount commands nothing, so the log starts empty")
                       (is (empty? (v/command-log))
                           "and the door says the same, before any traffic")
                       (doseq [{:keys [command]} traffic]
                         (conf/caught-id #(behaviors/command! frame-id command)))
                       (let [rows (behaviors/command-log)]
                         (is (= (count traffic) (count rows))
                             "every command left exactly one row, in order")
                         (is (= rows (v/command-log))
                             "and the DOOR answers that same window — one
                              projection published, not a second one beside it")
                         (absent-keys-are-absent rows absent "a traffic row")
                         (doseq [[{:keys [note row resolved?]} got]
                                 (map vector traffic rows)]
                           (is (= frame-id (:frame got))
                               (str note " — scoped by the frame it resolved in"))
                           (is (= row (dissoc got :frame :behavior :generation)) note)
                           (if resolved?
                             (do (is (= :re-frame.freehand.behavior-views/probe
                                        (:behavior got))
                                     (str note " — naming the behavior it reached"))
                                 (is (pos-int? (:generation got))
                                     (str note " — and the generation it reached it at")))
                             (do (is (not (contains? got :behavior))
                                     (str note " — no connection resolved, so no behavior"))
                                 (is (not (contains? got :generation))
                                     (str note " — and no generation"))))
                           (is (every? (set present) (keys got))
                               (str note " — the key roster is closed"))))))
              ;; Reports and releases; it never finishes (rf2-fyba). The teardown
              ;; both arms duplicated rides the single trailing step.
              (.catch (fn [e]
                        (is false (str "mount rejected: " e))
                        nil))
              (.then (fn [_]
                       (ms/destroy-root! container root)
                       (done)))))))))

(deftest fh-behavior-008-the-command-log-is-a-bounded-window
  (testing "Per FH-BEHAVIOR-008: the traffic window is BOUNDED — an
            unbounded log is a retention leak dressed up as evidence, and a
            session that runs for a day would carry a day of it. The
            eviction is proved rather than assumed: the run's only refusal
            is issued FIRST, so a cap that discarded the newest rows would
            still be holding it."
    (if-not (ms/browser?)
      (ms/skip! "the browser job runs the bounded-window assertions")
      (async done
        (setup!)
        (let [[container root] (ms/create-root!)
              {:keys [overflow-by op target evicted]} (:bounded fh-008)]
          (-> (ms/act #(.render root (element [bv/plain {}])))
              (.then (fn [_]
                       (conf/caught-id #(behaviors/command! frame-id evicted))
                       (is (= [:refused] (mapv :outcome (behaviors/command-log)))
                           "the row that must later be gone is really written first")
                       (dotimes [_ (+ behaviors/command-log-limit overflow-by)]
                         (behaviors/command! frame-id {:target target :op op}))
                       (let [rows (behaviors/command-log)]
                         (is (= behaviors/command-log-limit (count rows))
                             "the window holds the limit and no more")
                         (is (every? #(= :delivered (:outcome %)) rows)
                             "and the OLDEST rows were the ones dropped"))))
              ;; Reports and releases; it never finishes (rf2-fyba). The teardown
              ;; both arms duplicated rides the single trailing step.
              (.catch (fn [e]
                        (is false (str "mount rejected: " e))
                        nil))
              (.then (fn [_]
                       (ms/destroy-root! container root)
                       (done)))))))))
