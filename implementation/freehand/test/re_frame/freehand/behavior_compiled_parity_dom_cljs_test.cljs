(ns re-frame.freehand.behavior-compiled-parity-dom-cljs-test
  "rf2-drpa3.127 — a COMPILED view attaches a behavior and RUNS it the same as
  its interpreted twin.

  rf2-drpa3.116 gave the compiled tier a `v/behavior` form and rf2-drpa3.153
  stopped it being misnamed a foreign component; this file is the browser proof
  that the form is not merely ACCEPTED but LIVE. A `{:compiled true}` view
  connects, updates on `rf=` movement, takes a command through the ordinary
  effect path, and tears down totally — all through the SAME
  `re-frame.freehand.behaviors` runtime the interpreted boundary reaches. One
  implementation, both modes.

  The headline is a transcript EQUALITY: the identical mount / equal-render /
  moved-render / unmount sequence over `bv/plain` and its `{:compiled true}`
  twin `bv/plain-compiled` produces the same lifecycle, entry for entry — the
  cross-mode agreement D013 requires.

  It rides the browser lane through its `-dom-cljs-test` suffix, and skips with
  a spoken reason under the node runtimes where there is no DOM to mount."
  (:require ["react" :as react]
            ["react-dom/client" :as rdc]
            [cljs.test :refer-macros [async deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.freehand :as v]
            [re-frame.freehand.behavior-views :as bv]
            [re-frame.freehand.behaviors :as behaviors]
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

(def ^:private frame-id :dom/behaviors-compiled-parity)

(defn- browser? []
  (and (exists? js/document) (some? (.-createElement js/document))))

(defn- skip! [why]
  (is true (str "a real React mount needs a DOM host — " why)))

(defn- act
  "A React 19 `act` boundary as a promise, so assertions run after the commit
  AND its flushed effects rather than racing them."
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

(defn- reset-lifecycle! []
  (behaviors/reset-connections!)
  (bv/reset-transcript!)
  (bv/reset-dispatches!)
  (reset! bv/last-dispatch nil)
  nil)

(defn- setup! []
  (reset-lifecycle!)
  (live-frame/make-frame {:id frame-id})
  (rf/reg-event :probe/command (fn [_ [_ cmd]] {:fx [[behaviors/command-fx-id cmd]]}))
  nil)

(defn- element [form]
  (shell/provide-frame frame-id (fr/element form)))

(defn- attr [container selector name*]
  (some-> (.querySelector container selector) (.getAttribute name*)))

(defn- lifecycle-of!
  "Mount `view`, run the identical connect / equal-render / moved-render /
  unmount sequence, and resolve with the whole lifecycle transcript. The equal
  re-render moves nothing by `rf=` and must add no `:update`; the moved one
  reconciles once."
  [view]
  (let [[container root] (mount!)]
    (-> (act #(.render root (element [view {:label "a"}])))
        (.then (fn [_] (act #(.render root (element [view {:label "a"}])))))
        (.then (fn [_] (act #(.render root (element [view {:label "moved"}])))))
        (.then (fn [_] (act #(teardown! container root))))
        (.then (fn [_] @bv/transcript)))))

;; ===========================================================================
;; The headline — a compiled behavior LIVES the same as an interpreted one
;; ===========================================================================

(deftest a-compiled-behavior-connects-updates-and-tears-down-like-interpreted
  (testing "The cross-mode agreement (rf2-drpa3.127): the SAME lifecycle
            sequence over an interpreted use site and its {:compiled true} twin
            produces the SAME transcript — the connect, the single :update on
            rf= movement (the equal re-render moving nothing), the private
            memory the connection carried, and the disconnect on unmount. A
            behavior LIVES in the compiled tier, it does not merely compile."
    (if-not (browser?)
      (skip! "the browser job runs the cross-mode lifecycle assertion")
      (async done
        (setup!)
        (-> (lifecycle-of! bv/plain)
            (.then (fn [interpreted]
                     (reset-lifecycle!)
                     ;; The inner chain keeps its OWN diagnostic — a compiled
                     ;; rejection and an interpreted one are different findings —
                     ;; but neither handler finishes the row. Both report and
                     ;; release; the single `done` is at the tail of the outer chain.
                     (-> (lifecycle-of! bv/plain-compiled)
                         (.then (fn [compiled]
                                  (is (= [:connect :update :disconnect] (mapv :op compiled))
                                      "the compiled twin connects, updates once on movement, disconnects")
                                  (is (= interpreted compiled)
                                      "and its WHOLE transcript equals the interpreted one — one runtime, both modes")
                                  (is (zero? (behaviors/connection-count))
                                      "teardown left the connection table empty")))
                         (.catch (fn [e]
                                   (is false (str "compiled mount rejected: " e))
                                   nil)))))
            (.catch (fn [e]
                      (is false (str "interpreted mount rejected: " e))
                      nil))
            (.then (fn [_] (done))))))))

;; ===========================================================================
;; The command channel reaches a compiled connection
;; ===========================================================================

(deftest a-command-reaches-a-compiled-behaviors-live-connection
  (testing "rf2-drpa3.127: a command dispatched through the ordinary effect
            path reaches a COMPILED behavior's live connection and performs its
            host work — the compiled boundary is on the SAME bounded command
            channel as the interpreted one. The :mark command writes data-mark
            on the very node the compiled view declared."
    (if-not (browser?)
      (skip! "the browser job runs the command-delivery assertion")
      (async done
        (setup!)
        (let [[container root] (mount!)]
          (-> (act #(.render root (element [bv/plain-compiled {}])))
              (.then (fn [_]
                       (is (= 1 (behaviors/connection-count))
                           "the compiled behavior connected")
                       (is (= #{:probe/one} (behaviors/target-ids))
                           "claiming the semantic id its use site declared")
                       (act #(rf/dispatch-sync
                               [:probe/command {:target :probe/one :op :mark
                                                :args {:label "hit"}}]
                               {:frame frame-id}))))
              (.then (fn [_]
                       (is (= "hit" (attr container ".node" "data-mark"))
                           "the command performed host work on the compiled node")
                       (teardown! container root)
                       (is (zero? (behaviors/connection-count))
                           "and teardown released the connection")))
              ;; Teardown is the ACT under test on the success arm, so it is not
              ;; hoisted — the failure arm tears down for its own reason.
              (.catch (fn [e]
                        (is false (str "mount rejected: " e))
                        (teardown! container root)
                        nil))
              (.then (fn [_] (done)))))))))

;; ===========================================================================
;; The :layout arm runs before paint in the compiled tier
;; ===========================================================================

(deftest a-compiled-layout-behavior-does-its-work-before-paint
  (testing "rf2-drpa3.127: `:timing :layout` — the only declared moment before
            paint, and the one that makes measure-then-place work honest — runs
            in the compiled tier too. The compiled twin of the measure behavior
            connects and writes data-measured on its node, which is exactly the
            capability that was interpreted-forever before this slice."
    (if-not (browser?)
      (skip! "the browser job runs the layout-timing assertion")
      (async done
        (setup!)
        (let [[container root] (mount!)]
          (-> (act #(.render root (element [bv/layout-compiled {}])))
              (.then (fn [_]
                       (is (= [:connect] (bv/ops))
                           "the compiled :layout behavior connected")
                       (is (= "layout" (attr container ".node" "data-measured"))
                           "and its before-paint work is on the node")
                       (teardown! container root)
                       (is (zero? (behaviors/connection-count))
                           "and released on unmount")))
              ;; Teardown is the ACT under test on the success arm, so it is not
              ;; hoisted — the failure arm tears down for its own reason.
              (.catch (fn [e]
                        (is false (str "mount rejected: " e))
                        (teardown! container root)
                        nil))
              (.then (fn [_] (done)))))))))

;; ===========================================================================
;; The opaque-child law holds at RUNTIME in the compiled browser path
;; ===========================================================================

(defn- mount-outcome!
  "Mount `view` and resolve with `{:id .. :connections ..}` — `::no-throw` when
  it mounted, else the caught `:rf.error/id`. A render refusal rejects the act
  promise (there is no error boundary), and because a behavior connects only on
  COMMIT, a refused render leaves the connection table untouched — so the
  companion count proves the refusal came BEFORE any attachment."
  [view]
  (let [[container root] (mount!)]
    (-> (act #(.render root (element [view {}])))
        (.then (fn [_]
                 (let [n (behaviors/connection-count)]
                   (teardown! container root)
                   {:id ::no-throw :connections n}))
               (fn [e]
                 (let [n (behaviors/connection-count)]
                   (.remove container)
                   (try (.unmount root) (catch :default _ nil))
                   {:id (or (:rf.error/id (ex-data e)) ::no-id) :connections n}))))))

(deftest the-compiled-browser-path-enforces-the-opaque-child-law
  (testing "rf2-drpa3.127, the discriminating case. An {:opaque true} behavior
            owns its node's descendants, so a node carrying Freehand children is
            refused — and the compiled build cannot prove that, because the
            opacity is a REGISTERED fact and the child may carry a runtime-
            selected :use. So the COMPILED BROWSER path enforces the opaque-child
            law at runtime, raising :rf.error/behavior-bad-args BEFORE any
            connection opens or the host overwrites already-rendered children. The
            positive control — the same opaque behavior over an EMPTY node —
            mounts and connects. The interpreted browser twin agrees on both, so
            the compiled path is proven equal, not merely non-crashing."
    (if-not (browser?)
      (skip! "the browser job runs the opaque-child runtime refusal")
      (async done
        (setup!)
        (-> (mount-outcome! bv/opaque-host-compiled)
            (.then (fn [host]
                     (is (= ::no-throw (:id host))
                         "the compiled opaque behavior over an EMPTY node mounts (positive control)")
                     (reset-lifecycle!)
                     (mount-outcome! bv/opaque-with-children-compiled)))
            (.then (fn [kids]
                     (is (= :rf.error/behavior-bad-args (:id kids))
                         "the compiled opaque behavior over a node WITH children is refused at runtime")
                     (is (zero? (:connections kids))
                         "and refused BEFORE any connection opened — no child-host overwrite")
                     (reset-lifecycle!)
                     (mount-outcome! bv/opaque-host)))
            (.then (fn [i-host]
                     (is (= ::no-throw (:id i-host))
                         "the INTERPRETED opaque behavior over an empty node mounts too")
                     (reset-lifecycle!)
                     (mount-outcome! bv/opaque-with-children)))
            (.then (fn [i-kids]
                     (is (= :rf.error/behavior-bad-args (:id i-kids))
                         "and the interpreted twin refuses on the SAME diagnostic — the two browser modes agree")))
            (.catch (fn [e]
                      (is false (str "an opaque-child mount chain rejected unexpectedly: " e))
                      nil))
            (.then (fn [_] (done))))))))
