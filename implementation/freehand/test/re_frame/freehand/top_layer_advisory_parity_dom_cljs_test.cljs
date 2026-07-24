(ns re-frame.freehand.top-layer-advisory-parity-dom-cljs-test
  "rf2-drpa3.173 — the compiled top-layer DYNAMIC-HANDLER advisory, proven END
  TO END in a real browser, across BOTH execution modes.

  #6792 recorded every analyzed reconciler position (`:on-toggle`,
  `:on-before-toggle`, `:on-close`, `:on-cancel`) as a compile-time `true` in
  the top-layer advisory context. That is not the runtime predicate the shared
  advisory uses: `top-layer/reconciled?` asks `(some? (get attrs k))`, and a
  DYNAMIC handler is explicitly allowed to evaluate to nil. On the compiled
  path a nil dynamic handler makes `reactive/event-site` return nil and
  `handler!` write no DOM handler — yet the compile-time-`true` context said
  the node reconciled itself, so a controlled node could spring back open
  after native dismissal with the diagnostic silent. The fix carries the
  runtime some?-verdict; #6860 shipped it with JVM FORM-inspection tests only.

  This is the composition those layer tests do not reach: a mounted compiled
  view whose dynamic handler evaluates to nil must have no DOM handler AND
  publish exactly one committed advisory, matching its interpreted twin; a
  non-nil dynamic handler must suppress the advisory and really reach the DOM.

  NON-VACUOUS by construction. The advisory rides the trace DIAGNOSTIC channel
  and is published from the COMMITTED ref, once per commit — there is no
  once-per-session latch to make a count pass for free (unlike a React console
  warning). So `(= 1 advisory)` genuinely counts, and it REDS on the exact
  regression: were the compiled context a compile-time `true` again, the
  compiled nil-handler row would publish ZERO advisories and fail. The
  interpreted twin, which reads the real attribute map, is the always-correct
  oracle the compiled row is held against. The single-evaluation half of the
  fix (the reconciler site bound once, shared by the handler write and the
  context) is pinned structurally in `react-lowering-jvm-test`.

  Rides the browser lane through its `-dom-cljs-test` suffix; guarded on
  `showPopover`, so under node it has no top layer and says so."
  (:require [cljs.test :refer-macros [async deftest is testing use-fixtures]]
            [re-frame.freehand.conformance :as conf]
            [re-frame.freehand.matrix-support :as ms]
            [re-frame.freehand.react :as fr]
            [re-frame.freehand.shell :as shell]
            [re-frame.freehand.top-layer-advisory-views :as iv]
            [re-frame.freehand.top-layer-advisory-views-compiled :as cv]
            [re-frame.live-frame :as live-frame]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]
            [re-frame.trace.tooling :as trace-tooling]))

(def ^:private fx-005 (conf/fixture :FH-TOPLAYER-005))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter       plain-atom/adapter
     :ambient-frame nil
     :async?        true}))

(def ^:private fid :adv.top-layer/frame)

(defn- top-layer?
  "A real top layer, not merely a DOM — the capability every assertion rests
  on."
  []
  (and (ms/browser?) (some? (.-showPopover (.-prototype js/HTMLElement)))))

(defn- setup! []
  (live-frame/make-frame {:id fid})
  fid)

(defn- render!
  "Render `view` with `props` under the test frame, inside an act boundary so
  the top-layer commit batch has flushed before the returned promise
  resolves."
  [root view props]
  (ms/act #(.render root (shell/provide-frame fid (fr/element [view props])))))

(defn- listen!
  "Register a trace listener collecting the top-layer advisory records into
  `records`; answer its key for [[unlisten!]]."
  [records]
  (let [ids #{(:unreconciled-id fx-005) (:refused-id fx-005)}
        k   (keyword (gensym "adv-listener-"))]
    (trace-tooling/register-listener!
      k (fn [ev] (when (contains? ids (:operation ev)) (swap! records conj ev))))
    k))

(defn- unlisten! [k] (trace-tooling/unregister-listener! k))

;; ---------------------------------------------------------------------------
;; The mode axis — the same declaration, interpreted and compiled.
;; ---------------------------------------------------------------------------

(def ^:private popover-modes
  [["interpreted" iv/popover] ["compiled" cv/popover]])

(def ^:private popover-literal-modes
  [["interpreted" iv/popover-literal] ["compiled" cv/popover-literal]])

(def ^:private dialog-close-modes
  [["interpreted" iv/dialog-close] ["compiled" cv/dialog-close]])

(def ^:private dialog-cancel-modes
  [["interpreted" iv/dialog-cancel] ["compiled" cv/dialog-cancel]])

;; ===========================================================================
;; Popover — a dynamic :on-toggle
;; ===========================================================================

(deftest a-dynamic-popover-reconciler-tracks-its-runtime-verdict-in-both-modes
  (testing "A popover whose dynamic :on-toggle evaluates to nil emits exactly
            one unreconciled advisory at commit and attaches NO DOM handler —
            matching its interpreted twin — while a non-nil dynamic handler
            SUPPRESSES the advisory and really reaches the DOM. The value that
            decides both is the runtime some?-verdict, so one view answers
            differently for nil vs a fn, identically in each mode."
    (if-not (top-layer?)
      (ms/skip! "the browser job runs the top-layer advisory assertions")
      (async done
        (ms/each-mode
          popover-modes
          (fn [[label view]]
            (setup!)
            (let [[container root] (ms/create-root!)
                  records (atom [])
                  seen    (atom [])
                  on-tog  (fn [^js e] (swap! seen conj (or (.-newState e)
                                                           (some-> (.-nativeEvent e) .-newState))))
                  k       (listen! records)
                  pop-el  #(.querySelector container "#adv-popover")]
              (-> (render! root view {:open? true :handler nil})
                  (.then (fn [_]
                           (is (= 1 (count @records))
                               (str label ": a nil dynamic :on-toggle emits exactly one advisory"))
                           (when-some [ev (first @records)]
                             (is (= (:unreconciled-id fx-005) (:operation ev))
                                 (str label ": under the catalogued unreconciled id"))
                             (is (= :popover (:mechanism (:tags ev)))
                                 (str label ": naming the popover mechanism"))
                             (is (= [:on-toggle :on-before-toggle] (:handlers (:tags ev)))
                                 (str label ": and the handlers that would reconcile it")))
                           (is (true? (.matches (pop-el) ":popover-open"))
                               (str label ": the popover is open"))
                           (.hidePopover (pop-el))
                           (ms/tick!)))
                  (.then (fn [_]
                           (is (= [] @seen)
                               (str label ": nil handler — the browser's dismissal reached no DOM handler"))
                           (render! root view {:open? true :handler on-tog})))
                  (.then (fn [_]
                           (is (= 1 (count @records))
                               (str label ": a non-nil dynamic handler published NO further advisory"))
                           (is (true? (.matches (pop-el) ":popover-open"))
                               (str label ": the popover re-opened under the still-open desired state"))
                           (.hidePopover (pop-el))
                           (ms/tick!)))
                  (.then (fn [_]
                           (is (= ["closed"] @seen)
                               (str label ": the non-nil handler really reached the DOM and fired on dismissal"))
                           (unlisten! k)
                           (ms/destroy-root! container root)
                           nil))
                  (.catch (fn [e] (unlisten! k) (throw e))))))
          done)))))

;; ===========================================================================
;; Dialog — the close / cancel dismissal axis, each with a nil dynamic position
;; ===========================================================================

(deftest a-dynamic-dialog-on-close-tracks-its-runtime-verdict-in-both-modes
  (testing "The :on-close half of the dialog axis: a nil dynamic :on-close
            leaves the modal unreconciled — exactly one advisory, naming the
            modal mechanism — and a non-nil :on-close suppresses it and really
            reaches the DOM: the browser's own dismissal fires a `close` event,
            and the author's :on-close receives it. In each mode."
    (if-not (top-layer?)
      (ms/skip! "the browser job runs the dialog advisory assertions")
      (async done
        (ms/each-mode
          dialog-close-modes
          (fn [[label view]]
            (setup!)
            (let [[container root] (ms/create-root!)
                  records  (atom [])
                  seen     (atom [])
                  on-close (fn [_] (swap! seen conj :closed))
                  k        (listen! records)
                  dlg      #(.querySelector container "#adv-dialog")]
              (-> (render! root view {:open? true :handler nil})
                  (.then (fn [_]
                           (is (= 1 (count @records))
                               (str label ": a nil dynamic :on-close emits exactly one advisory"))
                           (when-some [ev (first @records)]
                             (is (= :modal (:mechanism (:tags ev)))
                                 (str label ": naming the modal mechanism"))
                             (is (= [:on-close :on-cancel] (:handlers (:tags ev)))
                                 (str label ": and the dialog's reconciling handlers")))
                           (is (true? (.matches (dlg) ":modal"))
                               (str label ": the dialog opened as a real modal"))
                           (render! root view {:open? true :handler on-close})))
                  (.then (fn [_]
                           (is (= 1 (count @records))
                               (str label ": a non-nil :on-close published NO further advisory"))
                           (is (true? (.matches (dlg) ":modal"))
                               (str label ": the dialog is still a real modal before dismissal"))
                           ;; The browser's own dismissal — Escape, the close button,
                           ;; close() — fires a non-bubbling `close` event, which React
                           ;; attaches directly to the node and delivers to :on-close. It
                           ;; is dispatched SYNCHRONOUSLY here rather than via .close(),
                           ;; because a queued close() event is diverted inside React's act
                           ;; environment (the popover's toggle is not) — so this is the
                           ;; deterministic form of the same wiring proof.
                           (reset! seen [])
                           (.dispatchEvent (dlg) (js/Event. "close" #js {:bubbles true}))
                           (ms/tick!)))
                  (.then (fn [_]
                           (is (= [:closed] @seen)
                               (str label ": the non-nil :on-close received the close event at the DOM"))
                           (unlisten! k)
                           (ms/destroy-root! container root)
                           nil))
                  (.catch (fn [e] (unlisten! k) (throw e))))))
          done)))))

(deftest a-dynamic-dialog-on-cancel-tracks-its-runtime-verdict-in-both-modes
  (testing "The :on-cancel half of the axis, with a nil dynamic position: a nil
            dynamic :on-cancel (and no :on-close) leaves the modal wholly
            unreconciled and warns once; a non-nil :on-cancel suppresses it.
            :on-cancel is the browser's own report of an Escape dismissal. In
            each mode."
    (if-not (top-layer?)
      (ms/skip! "the browser job runs the dialog advisory assertions")
      (async done
        (ms/each-mode
          dialog-cancel-modes
          (fn [[label view]]
            (setup!)
            (let [[container root] (ms/create-root!)
                  records (atom [])
                  k       (listen! records)
                  dlg     #(.querySelector container "#adv-dialog")]
              (-> (render! root view {:open? true :handler nil})
                  (.then (fn [_]
                           (is (= 1 (count @records))
                               (str label ": a nil dynamic :on-cancel emits exactly one advisory"))
                           (when-some [ev (first @records)]
                             (is (= :modal (:mechanism (:tags ev)))
                                 (str label ": naming the modal mechanism"))
                             (is (= [:on-close :on-cancel] (:handlers (:tags ev)))
                                 (str label ": and the dialog's reconciling handlers")))
                           (is (true? (.matches (dlg) ":modal"))
                               (str label ": the dialog opened as a real modal"))
                           (render! root view {:open? true :handler (fn [_] nil)})))
                  (.then (fn [_]
                           (is (= 1 (count @records))
                               (str label ": a non-nil :on-cancel published NO further advisory"))
                           (unlisten! k)
                           (ms/destroy-root! container root)
                           nil))
                  (.catch (fn [e] (unlisten! k) (throw e))))))
          done)))))

;; ===========================================================================
;; Control — a literal event-vector handler is unchanged (never warns)
;; ===========================================================================

(deftest a-literal-event-vector-handler-never-warns-in-either-mode
  (testing "The control: a LITERAL event-vector :on-toggle is always present,
            so a controlled popover carrying one is reconciled and publishes no
            advisory. The dynamic-handler work leaves literal handlers
            unchanged. Asserted in both modes."
    (if-not (top-layer?)
      (ms/skip! "the browser job runs the literal-handler control")
      (async done
        (ms/each-mode
          popover-literal-modes
          (fn [[label view]]
            (setup!)
            (let [[container root] (ms/create-root!)
                  records (atom [])
                  k       (listen! records)]
              (-> (render! root view {:open? true})
                  (.then (fn [_]
                           (is (= [] @records)
                               (str label ": a literal event-vector handler publishes no advisory"))
                           (is (true? (.matches (.querySelector container "#adv-popover-lit") ":popover-open"))
                               (str label ": and the popover is genuinely open"))
                           (unlisten! k)
                           (ms/destroy-root! container root)
                           nil))
                  (.catch (fn [e] (unlisten! k) (throw e))))))
          done)))))
