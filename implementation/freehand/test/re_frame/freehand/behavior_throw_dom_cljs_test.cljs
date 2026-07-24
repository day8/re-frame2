(ns re-frame.freehand.behavior-throw-dom-cljs-test
  "The ERROR PATH of the `v/behavior` attachment boundary — a real browser
  mount in which the decorated element's own walk throws.

  `v/behavior` is the one declared boundary whose child is walked INSIDE the
  boundary's own React render: the component reads its options, installs the
  timing arms, then builds the decorated element and hands it the node's ref.
  So a throw while that element is being built unwinds through the behavior's
  OCCURRENCE SEAM — the one place in this substrate that can say which
  declared occurrence a throw belongs to, because a throwable carries no view
  identity and the boundary that catches it several fibers above has no other
  source for one.

  Every other error-boundary property in this programme is pinned by a test
  that fails when the property breaks. This seam was not: nothing anywhere in
  the suite threw from inside a `v/behavior` child, so the seam's error path
  was reachable only by reading it. A defect on it therefore surfaced as a
  compiler warning rather than as a red test — which is what this file
  closes.

  The three claims, and why each is a separate assertion:

    ATTRIBUTION   the report names the occurrence that THREW. Here that is
                  the behavior attachment boundary itself, because the
                  decorated element's walk is that boundary's own render —
                  and NOT `unknown-view`, which is what a seam that failed to
                  note the throw would produce, and not the error boundary
                  that caught it, which keeps its own separate field.

    QUIET SEAM    the seam does not itself throw while a render throw is
                  already unwinding. The assertion is IDENTITY: the exception
                  that reaches the private egress is the very object the
                  child threw. A seam that raised anything of its own inside
                  that catch would replace it, and the report would describe
                  the substrate's failure instead of the application's.

    CLOSED EGRESS the boundary above receives a well-formed report — the safe
                  summary's exact field set, the opaque exception and a
                  capped host stack on the private record, and no app-db,
                  event history or `ex-data` anywhere the application can
                  read.

  A fourth case is the discriminator that gives the first its meaning: a
  DECLARED VIEW mounted under the decorated node renders in its own fiber, so
  its throw never passes the behavior's seam and must be attributed to that
  view. Without it, `:view-id` naming the behavior boundary would be
  indistinguishable from the boundary claiming every failure beneath it.

  ## Which assertions carry the discrimination

  Worth saying plainly, because it is the whole reason this file exists.
  \"SOMETHING THREW\" PASSES AGAINST THE DEFECT. The seam's failure mode is
  not a suppressed throw — the throw still arrives, the boundary still
  catches it, the fallback still commits. What is destroyed is WHO it is
  attributed to. So every assertion here that merely counts a contained
  failure — the fallback text, the one-record and one-intent counts, the
  identity of the promoted exception — is a PASSENGER against a broken note:
  true, worth stating, and green either way.

  The assertions that discriminate are the ones that name the failing view:
  `:view-id`, its two negative twins (`unknown-view` and the catcher), the
  evidence completeness that follows from a note being found, and the
  fingerprint derived from the id. A revert of the one-arg call reds exactly
  those and nothing else — which is what proves the file covers the defect
  class rather than merely visiting it.

  The identity assertion is a passenger HERE and a guard ELSEWHERE: in
  ClojureScript a wrong-arity call to a single-arity `defn` does not raise,
  so the seam stays quiet by accident rather than by design. It guards
  against a seam that raises anything of its own inside that catch — which is
  what the same mistake does on a host that checks arity, and what any future
  work inside that catch could do on any host.

  This file rides the browser lane through its `-dom-cljs-test` namespace
  suffix. It also matches the node suites' broader regex, where there is no
  DOM to mount and no React to render a component through, and it says so
  rather than passing quietly."
  (:require ["react" :as react]
            ["react-dom/client" :as rdc]
            [clojure.set :as set]
            [clojure.string :as string]
            [cljs.test :refer-macros [async deftest is testing use-fixtures]]
            [re-frame.error-emit :as error-emit]
            [re-frame.freehand :as v]
            [re-frame.freehand.behaviors :as behaviors]
            [re-frame.freehand.conformance :as conf]
            [re-frame.freehand.descriptor :as descriptor]
            [re-frame.freehand.errors :as eb]
            [re-frame.freehand.react :as fr]
            [re-frame.router :as router]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]))

(def ^:private error-002 (conf/fixture :FH-ERROR-002))
(def ^:private error-003 (conf/fixture :FH-ERROR-003))

;; ---------------------------------------------------------------------------
;; The declarations. Module-level, because a declared view cannot close over a
;; test's locals and a registered behavior is registered when its namespace
;; loads.
;; ---------------------------------------------------------------------------

(def ^:private connects
  "How many times the behavior's `:connect` ran. A render that threw was
  never committed, so this must stay at zero — host work is commit-only, and
  a failed render is the strongest case of a candidate the host abandons."
  (atom 0))

(v/defbehavior watcher
  "The smallest registered behavior that can legally be attached: one
  `:connect` arm that touches no host and only counts."
  {:timing  :passive
   :connect (fn [_] (swap! connects inc) nil)})

(def ^:private decorated-child-failure
  "The ONE exception the decorated element's children throw — a stable
  object, so the egress record's `:exception` can be compared by IDENTITY
  rather than by shape. Its `ex-data` carries a marked secret: a summary that
  ever leaked host detail would carry it too."
  (ex-info "the decorated element's children threw" {:secret "must-not-leak"}))

(defn- throwing-run
  "A child run that throws WHEN THE WALK REALISES IT, and not before.

  That distinction is the whole scenario. The enclosing view body builds this
  value and returns; realisation happens inside the emitter's child walk,
  which for a behavior's decorated element runs inside the behavior
  component's own render — so the throw unwinds through the behavior's
  occurrence seam. A value that threw while the body was still running would
  be the enclosing view's failure instead, which is a different (and already
  covered) seam."
  []
  (map (fn [_] (throw decorated-child-failure)) [:row]))

(v/defview decorated-child-throws
  "The scenario: a behavior over an ordinary element whose children throw as
  the behavior's own render walks them."
  [_]
  [v/behavior {:use watcher :target :probe/decorated :config {:label "panel"}}
   [:div.node (throwing-run)]])

(v/defview descendant-view
  "A declared view mounted UNDER the decorated node. React renders it in its
  own component, so its throw never reaches the behavior's seam."
  [_]
  (throw (ex-info "a declared descendant of the decorated node threw" {})))

(v/defview decorated-descendant-throws
  "The discriminator: the same behavior over the same node, failing one
  fiber lower down."
  [_]
  [v/behavior {:use watcher :target :probe/decorated}
   [:div.node [descendant-view {}]]])

(defn- id-of [view] (:view-id (descriptor/describe view)))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter       plain-atom/adapter
     :ambient-frame nil
     :async?        true
     :init-fn       (fn []
                      (fr/reset-boundaries!)
                      (behaviors/reset-connections!)
                      (error-emit/clear-error-listeners!)
                      (reset! connects 0))}))

;; ---------------------------------------------------------------------------
;; The harness — a real root, a real class boundary, both channels recorded at
;; the seams they actually travel.
;; ---------------------------------------------------------------------------

(defn- browser? []
  (and (exists? js/document) (some? (.-createElement js/document))))

(defn- skip! [why]
  (is true (str "a real React mount needs a DOM host — " why)))

(defn- act
  "A React 19 `act` boundary as a promise, so assertions run after the commit
  and its flushed effects rather than racing them."
  [thunk]
  (try
    (set! (.-IS_REACT_ACT_ENVIRONMENT js/globalThis) true)
    (js/Promise.resolve (react/act (fn [] (js/Promise.resolve (thunk)))))
    (catch :default e
      (js/Promise.reject e))))

(defn- open-channels!
  "Record the safe `:on-error` intent and the private egress record at their
  real seams, and answer the teardown that removes both. The intent rides a
  `router/dispatch!` redefinition because the assertion is an EXACT COUNT:
  an event that had to be processed before it could be counted would be
  counted on a later microtask than the commit that produced it."
  []
  (let [intents (atom [])
        egress  (atom [])
        orig    router/dispatch!]
    (error-emit/register-error-listener! ::recorder (fn [r] (swap! egress conj r)))
    (set! router/dispatch!
          (fn ([e] (swap! intents conj e))
            ([e _opts] (swap! intents conj e))))
    {:intents intents
     :egress  egress
     :close!  (fn []
                (set! router/dispatch! orig)
                (error-emit/unregister-error-listener! ::recorder))}))

(defn- guarded
  "One error boundary over `child`, reporting through the fixture's
  `:on-error` prefix."
  [child]
  (fr/element
    [v/error-boundary {:fallback  [:p {:id "fallback"} "contained"]
                       :reset-key (:reset-key-1 error-002)
                       :on-error  (:on-error error-002)}
     child]))

(defn- mount-one-failure!
  "Mount a guarded `child`, let the failure commit, and answer a promise of
  `{:fallback :intents :egress}` — what was on screen, and what each channel
  received."
  [child]
  (let [container (js/document.createElement "div")
        _         (.appendChild js/document.body container)
        root      (rdc/createRoot container)
        {:keys [intents egress close!]} (open-channels!)
        finish!   (fn []
                    (let [seen {:fallback (some-> (.querySelector container "#fallback")
                                                  .-textContent)
                                :intents  @intents
                                :egress   @egress}]
                      (.unmount root)
                      (.remove container)
                      (close!)
                      seen))]
    (-> (act #(.render root (guarded child)))
        (.then (fn [_] (finish!))
               (fn [e] (finish!) (js/Promise.reject e))))))

(defn- deep-keys
  "Every map key anywhere in `x`, so a forbidden slot cannot hide nested."
  [x]
  (cond
    (map? x)  (into (set (keys x)) (mapcat deep-keys (vals x)))
    (coll? x) (into #{} (mapcat deep-keys x))
    :else     #{}))

;; ===========================================================================
;; The seam: a throw from inside a behavior's decorated child
;; ===========================================================================

(deftest a-throw-inside-a-behaviors-decorated-child-is-attributed-to-it
  (testing "A behavior's decorated element is walked inside the behavior's
            OWN React render, so a throw from that walk passes the behavior's
            occurrence seam on its way to the error boundary. The report must
            name that occurrence. `unknown-view` is what a seam that failed to
            note the throw produces — truthful ignorance for a failure that
            was, in fact, plainly observed — and it is also what a seam whose
            note never reached the thrown value produces, which is the exact
            defect this case exists to catch."
    (if-not (browser?)
      (skip! "the browser job runs the behavior-seam attribution assertions")
      (async done
        (-> (mount-one-failure! [decorated-child-throws {}])
            (.then (fn [{:keys [fallback egress]}]
                     (let [summary (:summary (first egress))]
                       (is (= "contained" fallback)
                           "the throw was contained — the fallback is on screen")
                       (is (= 1 (count egress))
                           "and exactly one failure was reported")
                       (is (= behaviors/behavior-view-id (:view-id summary))
                           "attributed to the behavior boundary whose render threw")
                       (is (not= eb/unknown-view-id (:view-id summary))
                           "not the truthful-ignorance marker — the throw WAS observed")
                       (is (= eb/boundary-view-id (:boundary-view-id summary))
                           "the catcher keeps its own separate field")
                       (is (not= eb/boundary-view-id (:view-id summary))
                           "and is never named as the thrower")
                       (is (true? (:complete? (:evidence summary)))
                           "the evidence is complete — nothing about this failure was lost")
                       (is (nil? (:loss (:evidence summary)))
                           "so there is no loss to name")
                       (is (= (eb/fingerprint behaviors/behavior-view-id :normalize)
                              (:fingerprint summary))
                           "and the correlation token follows the attribution AND
                            the phase: the behavior body returned and the emitter's
                            WALK of its decorated child raised the failure, so the
                            token names the :normalize phase, not :render"))
                     (done))
                  (fn [e]
                    (is false (str "mount rejected: " e))
                    (done))))))))

(deftest the-seam-does-not-throw-while-a-render-throw-is-unwinding
  (testing "The seam's note runs INSIDE the catch, one statement before the
            re-throw. Anything it raised there would replace the exception on
            its way out, and the boundary would report the substrate's
            failure in place of the application's — destroying the very
            attribution the seam exists to record. The assertion is identity:
            the object the private egress carries is the object the decorated
            child threw."
    (if-not (browser?)
      (skip! "the browser job runs the quiet-seam assertions")
      (async done
        (-> (mount-one-failure! [decorated-child-throws {}])
            (.then (fn [{:keys [intents egress]}]
                     (let [record (first egress)]
                       (is (= 1 (count egress))
                           "exactly one private egress record")
                       (is (= (:intents-per-generation error-002) (count intents))
                           "and exactly one safe intent")
                       (is (identical? decorated-child-failure (:exception record))
                           "the exception promoted is the one the child threw, unreplaced")
                       (is (= (ex-message decorated-child-failure)
                              (ex-message (:exception record)))
                           "it is the application's failure that was reported, not the substrate's")
                       (is (= behaviors/behavior-view-id (:view-id (:summary record)))
                           "and the attribution the seam recorded survived the unwind")
                       (is (zero? @connects)
                           "the abandoned render connected nothing"))
                     (done))
                  (fn [e]
                    (is false (str "mount rejected: " e))
                    (done))))))))

(deftest the-boundary-above-receives-a-well-formed-report
  (testing "The report the boundary promotes for a behavior-seam failure is
            the same closed pair every other contained failure produces: the
            safe public summary by exact field set, and the private egress
            record adding the opaque exception and a capped host stack.
            Neither carries app-db, event history, `ex-data` or props — the
            automatic capture this substrate deliberately refuses."
    (if-not (browser?)
      (skip! "the browser job runs the closed-egress assertions")
      (async done
        (-> (mount-one-failure! [decorated-child-throws {}])
            (.then (fn [{:keys [intents egress]}]
                     (let [record  (first egress)
                           summary (:summary record)]
                       (is (= (:egress-record-keys error-003) (set (keys record)))
                           "the private record's field set is exactly the closed egress set")
                       (is (= (:egress-category error-003) (:error record))
                           "riding the always-on view-render-failed category")
                       (is (= (:recovery error-003) (:recovery record))
                           "and stating that the boundary contained it")
                       (is (some? (:exception record))
                           "the opaque host exception the private channel keeps")
                       (is (string? (:component-stack record))
                           "and the capped host stack an off-box shipper needs")
                       (is (= (:safe-summary-keys error-003) (set (keys summary)))
                           "the safe summary's field set is exactly the closed public set")
                       (is (= (:diagnostic-id error-003) (:diagnostic-id summary)))
                       (is (= :normalize (:phase summary))
                           "the behavior body returned; the emitter WALK realising
                            the lazy decorated child raised the failure, so the
                            phase is :normalize, not :render")
                       (is (= (:basis error-003) (:basis (:evidence summary))))
                       (is (empty? (set/intersection (:forbidden-keys error-003)
                                                     (deep-keys summary)))
                           "no exception, ex-data, props, app-db or event history in the summary")
                       (is (not (string/includes? (pr-str summary) "must-not-leak"))
                           "and the thrown value's ex-data never reaches the public envelope")
                       (is (= (conj (vec (:on-error error-002)) summary)
                              (first intents))
                           "the app's intent is its own prefix with that summary appended"))
                     (done))
                  (fn [e]
                    (is false (str "mount rejected: " e))
                    (done))))))))

;; ===========================================================================
;; The discriminator — a behavior boundary claims only what it threw
;; ===========================================================================

(deftest a-declared-descendant-of-the-decorated-node-keeps-its-own-identity
  (testing "React renders a declared view in its own component, so a view
            mounted under the decorated node fails one fiber below the
            behavior and its throw never passes the behavior's seam. The
            report must name that view. Without this case, a `:view-id` of
            the behavior boundary above would be indistinguishable from a
            boundary claiming every failure beneath it — and because the
            fingerprint derives from the view id, every bug under every
            behavior in an application would share one correlation token."
    (if-not (browser?)
      (skip! "the browser job runs the descendant-attribution assertions")
      (async done
        (-> (mount-one-failure! [decorated-descendant-throws {}])
            (.then (fn [{:keys [fallback egress]}]
                     (let [summary (:summary (first egress))]
                       (is (= "contained" fallback)
                           "the deeper failure is contained too")
                       (is (= (id-of descendant-view) (:view-id summary))
                           "the declared view that threw, not the behavior above it")
                       (is (not= behaviors/behavior-view-id (:view-id summary))
                           "the behavior boundary does not claim a descendant's failure")
                       (is (not= eb/unknown-view-id (:view-id summary))
                           "and the descendant's own seam observed it"))
                     (done))
                  (fn [e]
                    (is false (str "mount rejected: " e))
                    (done))))))))

;; ===========================================================================
;; Non-vacuity — the child really throws, and the recorders are really wired
;; ===========================================================================

(deftest non-vacuity-the-run-throws-and-the-channels-are-live
  (testing "Non-vacuity: the decorated element's child run really throws when
            it is realised — the same stable object the assertions compare by
            identity — and the two recorders sit on the seams a promotion
            actually travels. Neither a run that quietly produced nothing nor
            a silent channel could produce the counts above."
    (is (thrown? :default (doall (throwing-run)))
        "realising the run throws")
    (is (identical? decorated-child-failure
                    (try (doall (throwing-run))
                         (catch :default e e)))
        "and throws the very object the egress assertions compare against")
    (let [{:keys [intents egress close!]} (open-channels!)
          b (eb/boundary eb/boundary-view-id (:reset-key-1 error-002))]
      (eb/capture! b {:exception (ex-info "boom" {})
                      :phase     (:phase error-002)
                      :view-id   (:view-id error-002)
                      :frame-id  nil})
      (eb/promote! b {:on-error (:on-error error-002) :frame-id nil :time 0})
      (is (= (:intents-per-generation error-002) (count @intents))
          "the intent recorder is on the router seam the boundary dispatches through")
      (is (= (:egress-records-per-generation error-002) (count @egress))
          "and the egress recorder is on the always-on error axis")
      (close!))))
