(ns re-frame.freehand.behavior-matrix-dom-cljs-test
  "F6b matrix 4/8 — registered BEHAVIOR command / commit / replay / cleanup,
  in a real browser (EP-0036 §6, gate row \"browser correctness\";
  acceptance 3 — cleanup assertions are EXACT counts).

  A behavior's whole value is that imperative host work is bounded to
  moments the substrate names, over a node the substrate handed it, with a
  release that leaves NOTHING behind. None of that is a structural fact:
  it is a fact about a real commit, a real node, and a table that must be
  empty afterwards. So this mounts.

  ## The mode dimension for behaviors

  `v/defbehavior` is the ONE sanctioned imperative boundary, and the
  compiled grammar now HAS a `[v/behavior …]` form (rf2-drpa3.116/.127/.153)
  — a view that attaches a behavior can be promoted rather than interpreted
  forever. So the behavior matrix's compiled cell is a PASS, not a refusal:
  the compiled twin carries a manifest the interpreted view has none of, and
  mounted it connects and claims its target exactly as its interpreted twin
  does (full cross-mode lifecycle equality is
  `behavior-compiled-parity-dom-cljs-test`). Both cells share one browser
  contract: commit-only connection, a command that reaches its explicit
  target and runs host work, `:update` on movement and not on a re-render,
  and a teardown that releases everything to the exact integer zero measured
  against a behavior-free control.

  Rides the browser lane through its `-dom-cljs-test` suffix; under node it
  has no DOM and says so."
  (:require [cljs.test :refer-macros [async deftest is testing use-fixtures]]
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
     :async?        true}))

(def ^:private fid :matrix.behavior/frame)

(defn- setup! []
  (behaviors/reset-connections!)
  (bv/reset-transcript!)
  (bv/reset-dispatches!)
  (live-frame/make-frame {:id fid})
  fid)

(defn- render! [root form]
  (ms/act #(.render root (shell/provide-frame fid (fr/element form)))))

(defn- node-attr [container attribute]
  (some-> (.querySelector container ".node") (.getAttribute attribute)))

;; ===========================================================================
;; Row 1 — a connection is commit-only and claims its declared target
;; ===========================================================================

(deftest behavior-matrix-a-committed-render-connects-once-and-claims-its-target
  (testing "A committed render connects the behavior EXACTLY once and claims
            the semantic id its use site declared — the connection table
            holds one entry, on `:probe/one`, and the lifecycle transcript
            is a single `:connect`."
    (if-not (ms/browser?)
      (ms/skip! "the browser job runs the connection assertions")
      (async done
        (setup!)
        (let [[container root] (ms/create-root!)]
          (-> (render! root [bv/plain {}])
              (.then (fn [_]
                       (is (= 1 (behaviors/connection-count)) "exactly one connection")
                       (is (= #{:probe/one} (behaviors/target-ids))
                           "claiming the id the use site declared")
                       (is (= [:connect] (bv/ops)) "and connected exactly once")))
              (.catch (fn [e]
                        (is false (str "the behavior mount rejected: " e))
                        nil))
              (.then (fn [_]
                       (ms/destroy-root! container root)
                       (done)))))))))

;; ===========================================================================
;; Row 2 — a command reaches its target and runs host work; bounded refusals
;; ===========================================================================

(deftest behavior-matrix-a-command-reaches-its-target-and-refuses-otherwise
  (testing "A command carrying an explicit target reaches the ONE live
            connection claiming it and runs its host work — a real
            `data-mark` on the real node — and every other command shape is
            a visible refusal that performs NO host work: an absent target,
            an unregistered op, and a malformed command are each a typed
            diagnostic. Bounded, in a real browser."
    (if-not (ms/browser?)
      (ms/skip! "the browser job runs the command assertions")
      (async done
        (setup!)
        (let [[container root] (ms/create-root!)]
          (-> (render! root [bv/plain {}])
              (.then (fn [_]
                       (behaviors/command! fid {:target :probe/one :op :mark :args {:label "hit"}})
                       (is (= "hit" (node-attr container "data-mark"))
                           "the command ran its host work on the target's node")
                       (is (some #{:mark} (bv/ops)) "and the lifecycle recorded the command")
                       ;; bounded refusals — each performs no host work
                       (doseq [[why cmd] [["an absent target"    {:target :probe/nobody :op :mark}]
                                          ["an unregistered op"  {:target :probe/one :op :teleport}]
                                          ["a malformed command" "mark it"]]]
                         (let [d (conf/caught-data #(behaviors/command! fid cmd))]
                           (is (= :rf.error/behavior-command-refused (:rf.error/id d))
                               (str why " is refused with the bounded diagnostic"))))
                       (is (= "hit" (node-attr container "data-mark"))
                           "and no refusal touched the node the accepted command marked")))
              (.catch (fn [e]
                        (is false (str "the behavior mount rejected: " e))
                        nil))
              (.then (fn [_]
                       (ms/destroy-root! container root)
                       (done)))))))))

;; ===========================================================================
;; Row 3 — :update observes committed-config movement, not re-renders
;; ===========================================================================

(deftest behavior-matrix-update-observes-movement-not-renders
  (testing "`:update` runs when the committed config MOVES by `rf=` and not
            otherwise — an rf=-equal re-render leaves the transcript
            untouched, and a moved config arrives once, carrying both the
            new config and the previous one. A behavior that reconciled on
            every commit would reset a cursor for no reason."
    (if-not (ms/browser?)
      (ms/skip! "the browser job runs the replay assertions")
      (async done
        (setup!)
        (let [[container root] (ms/create-root!)]
          (-> (render! root [bv/plain {:label "a"}])
              (.then (fn [_]
                       (is (= [:connect] (bv/ops)) "connected on the first commit")
                       (render! root [bv/plain {:label "a"}])))     ; equal config
              (.then (fn [_]
                       (is (= [:connect] (bv/ops)) "an rf=-equal config is not movement")
                       (render! root [bv/plain {:label "moved"}]))) ; moved config
              (.then (fn [_]
                       (is (= [:connect :update] (bv/ops))
                           "a moved config reconciles the host, exactly once")
                       (let [{:keys [config prev-config]} (last @bv/transcript)]
                         (is (= {:label "moved"} config) "with the new config")
                         (is (= {:label "a"} prev-config) "and the previous one alongside it"))))
              (.catch (fn [e]
                        (is false (str "the behavior mount rejected: " e))
                        nil))
              (.then (fn [_]
                       (ms/destroy-root! container root)
                       (done)))))))))

;; ===========================================================================
;; Row 4 — teardown releases everything, the exact integer zero (acceptance 3)
;; ===========================================================================

(deftest behavior-matrix-teardown-releases-everything-to-exact-zero
  (testing "After the behavior unmounts, the substrate holds NO connection
            record and no target claim — the exact integer zero, not a
            threshold. A behavior-free CONTROL mount is measured first,
            proving the counters read zero for a mount that never connected,
            so the zero after teardown is the behavior's release and
            `:disconnect` ran exactly once."
    (if-not (ms/browser?)
      (ms/skip! "the browser job runs the teardown assertions")
      (async done
        (setup!)
        (let [[cc rc] (ms/create-root!)
              [container root] (ms/create-root!)]
          (-> (render! rc [bv/control-mount {}])
              (.then (fn [_]
                       (is (= 0 (behaviors/connection-count))
                           "the control mount connected nothing")
                       (is (= #{} (behaviors/target-ids)) "and claims no target")
                       (render! root [bv/plain {}])))
              (.then (fn [_]
                       (is (= 1 (behaviors/connection-count)) "the behavior connected")
                       (is (= #{:probe/one} (behaviors/target-ids)))
                       (ms/destroy-root! container root)
                       ;; the behavior's own root is gone; the control is still mounted,
                       ;; so a nonzero here would be the control, not a leak.
                       (is (= 0 (behaviors/connection-count))
                           "teardown released the connection to EXACTLY zero")
                       (is (= #{} (behaviors/target-ids)) "and dropped the target claim")
                       (is (= [:connect :disconnect] (bv/ops)) "disconnecting exactly once")))
              ;; The behaviour root's teardown is the ACT under test above, so the
              ;; failure arm keeps its own — the two arms are destroying it for
              ;; different reasons. Only the control root is shared teardown.
              (.catch (fn [e]
                        (is false (str "a behavior mount rejected: " e))
                        (ms/destroy-root! container root)
                        nil))
              (.then (fn [_]
                       (ms/destroy-root! cc rc)
                       (done)))))))))

;; ===========================================================================
;; Row 5 — the compiled tier ATTACHES the behavior boundary (the mode cell)
;; ===========================================================================

(deftest behavior-matrix-the-compiled-tier-attaches-the-behavior-boundary
  (testing "The behavior matrix's compiled dimension is now a PASS, not a
            refusal (rf2-drpa3.116/.127/.153): `[v/behavior …]` has a compiled
            form, so a view that attaches a behavior can be promoted rather
            than interpreted forever. The compiled twin carries a MANIFEST —
            the compiled analysis the interpreted view has none of — and mounted
            in a real browser it connects and claims its target exactly as its
            interpreted twin does (Row 1). Full cross-mode lifecycle equality —
            connect, update-on-movement, command, teardown, entry for entry —
            is `behavior-compiled-parity-dom-cljs-test`."
    (is (some? (v/manifest bv/plain-compiled))
        "the compiled behavior view carries a manifest — it really is analysed and lowered")
    (is (nil? (v/manifest bv/plain))
        "and its interpreted twin has none, the honest answer for a mode with no analysis step")
    (if-not (ms/browser?)
      (ms/skip! "the browser job runs the compiled-cell mount")
      (async done
        (setup!)
        (let [[container root] (ms/create-root!)]
          (-> (render! root [bv/plain-compiled {}])
              (.then (fn [_]
                       (is (= 1 (behaviors/connection-count))
                           "the compiled behavior connected, exactly like its interpreted twin (Row 1)")
                       (is (= #{:probe/one} (behaviors/target-ids))
                           "claiming the semantic id its use site declared")
                       (is (= [:connect] (bv/ops)) "and connected exactly once")
                       (ms/destroy-root! container root)
                       (is (= 0 (behaviors/connection-count))
                           "and released on teardown to exact zero")))
              ;; Teardown is the act under test on the success arm, so it is NOT
              ;; hoisted — the failure arm tears down for its own reason.
              (.catch (fn [e]
                        (is false (str "the compiled behavior mount rejected: " e))
                        (ms/destroy-root! container root)
                        nil))
              (.then (fn [_] (done)))))))))
