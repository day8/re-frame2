(ns re-frame.ui.test
  "`ui.test` — the testing surface of the compiled-view substrate (Spec 004
  S1/S3, Spec 008). SIX names across two hosts.

  ## JVM structural host (Tier-1, headless)

      (render [view props] {:sub-overrides {query-v value}})
      (attrs node)
      (text node)

  `render` runs the real compiled view against the AMBIENT frame and returns
  the versioned public STRUCTURAL TREE (node schema v1, the
  jvm-tree-and-conversion-contract ABI). ONE input grammar — the literal view
  form, props carried IN the form; ONE option — `:sub-overrides`. Frame scope
  is the programmer's ordinary bracket: `rf/with-new-frame` for a fresh owned
  frame (eval-bind-run-destroy), `rf/with-frame` to pin an existing one. Drive
  state with `rf/dispatch-sync` and assert on a FRESH `render`.

  Handlers are event vectors as data, so 'what does this button do' is an
  equality check — no click simulation, no DOM, no flake:

      (deftest add-button-carries-intent
        (let [tree (ui.test/render [app/add-button {:product-id 42}]
                                   {:sub-overrides {[:cart/locked?] false}})]
          (is (= [:cart/add 42]
                 (-> (some #(when (= :button (:tag %)) %)
                           (tree-seq map? :children tree))
                     ui.test/attrs :on-click)))))

  Traverse with ordinary Clojure: `(tree-seq map? :children tree)` and a
  predicate over `(:tag %)` (element tag) or `(:view-id %)` (view boundary);
  `filterv` for every match. `attrs`/`text` are the read projections — the ONE
  attribute read (a bare `(:on-click node)` is a FIELD miss: events live under
  `:events`, so read them through `attrs`) and the document-order text
  concatenation.

  ## CLJS mounted host (Tier-3)

      (with-root [container [app-root ...]] body ...)
      (flush!)  (flush! thunk)
      (flush-presence!)  (flush-presence! ms)

  `with-root` mounts the literal root form into a connected test-owned DOM
  CONTAINER, awaits the initial commit, runs/awaits the body with that container
  bound, then tears down the React root and container on every exit. Query the
  container with native `.querySelector` / `.querySelectorAll` and read ordinary
  DOM properties/events. `flush!` is the sole compiled-view test flush: the thunk
  (when supplied) runs inside awaited React 19 `act`, then framework
  notifications and React commits alternate to a fixed point — drive a mounted
  dispatch with `(flush! #(rf/dispatch-sync event {:frame f}))`.
  `flush-presence!` advances the presence fake clock so retained
  (`:unmounting`) children reach their `:timeout-ms` removal without wall-clock
  sleeps.

  ## JVM semantics under test (06 §1 subset)

  Structure, branches, lists and event intent are fully faithful; `sub` reads
  are the one-shot headless read (03 §3) — resolved against the ambient frame
  and the explicit `:sub-overrides` door; effects don't run, host ops raise
  `:rf.error/jvm-host-op`. The events/subs a view touches must be `.cljc` — the
  standard re-frame discipline.

  Dev/test scope ONLY: nothing in a production bundle may `:require` this
  namespace (bundle-isolation gate)."
  #?(:cljs (:require-macros [re-frame.ui.test]))
  (:require [re-frame.error :as error]
            [re-frame.frame :as frame]
            [re-frame.ui.presence-runtime :as presence]
            [re-frame.ui.reactive :as reactive]
            #?@(:clj [[re-frame.ui.compiler.emit-jvm :as emit-jvm]
                      [re-frame.ui.compiler.env :as env]
                      [re-frame.ui.compiler.root :as root]
                      [re-frame.ui.tree :as tree]]
                :cljs [["react" :as React]
                       [re-frame.ui :as ui]
                       [re-frame.ui.client :as client]])))

;; ---------------------------------------------------------------------------
;; Typed-error helpers (Spec 009 thrown-error shape; ids catalogued)
;; ---------------------------------------------------------------------------

(defn- malformed!
  [where reason value]
  (error/throw-error! :rf.error/ui-tree-malformed where reason
                      {:extra {:value value}}))

(defn- tier-mismatch!
  [where reason extra]
  (error/throw-error! :rf.error/ui-test-tier-mismatch where reason
                      {:recovery :use-the-other-tier
                       :extra    extra}))

(defn- bad-opts!
  [where reason extra]
  (error/throw-error! :rf.error/ui-test-bad-opts where reason
                      {:recovery :fix-the-opts-map
                       :extra    extra}))

;; ---------------------------------------------------------------------------
;; Node discrimination (tree contract §Node schema)
;; ---------------------------------------------------------------------------

(defn- node-kind
  "Discriminate a MAP node per the pinned order (string → text is handled
  by callers): `:tag` → element, else `:view-id` → view-boundary, else
  `:html` → trusted-HTML, else `:children` → fragment. Carrying more than
  one primary discriminating field, or no primary and no `:children`, is
  malformed — every tree consumer fails loud (tree contract §Node schema).

  The roster's remaining primary, `:rf.ui/host`, has no arm here and needs
  none. A host node is one `v/defhost` crossing; `v/defhost` is a Freehand
  verb and this compiled tier mints no such head, so no tree reaching this
  namespace can carry one. The variant roster and its discrimination order
  are the tree contract's table — this docstring states neither a count nor
  a second copy of the set."
  [where m]
  (let [primaries (cond-> 0
                    (contains? m :tag)     inc
                    (contains? m :view-id) inc
                    (contains? m :html)    inc)]
    (when (> primaries 1)
      (malformed! where
                  (str "malformed tree node — a map may carry only ONE primary "
                       "discriminating field (here :tag / :view-id / :html; the "
                       "roster is the tree contract's §Node schema); got "
                       (pr-str (select-keys m [:tag :view-id :html])))
                  m))
    (cond
      (contains? m :tag)      :element
      (contains? m :view-id)  :view-boundary
      (contains? m :html)     :html
      (contains? m :children) :fragment
      :else
      (malformed! where
                  (str "malformed tree node — a map node needs a discriminating "
                       "field (:tag / :view-id / :html / :children); got "
                       (pr-str m))
                  m))))

#?(:cljs
   (defn- dom-element? [x]
     (and (exists? js/Element) (instance? js/Element x))))

(defn- not-a-node!
  "Shared rejection for a non-node input where a structural node was
  required — a live DOM element points at the Tier-3 mounted surface."
  [where x]
  #?(:cljs
     (when (dom-element? x)
       (tier-mismatch!
        where
        (str where " projects Tier-1 STRUCTURAL nodes — got a live DOM "
             "element (Tier 3). Read live DOM via host interop on the "
             "element (e.g. (.-value el)) inside a ui.test/with-root body")
        {:got :dom-element})))
  (tier-mismatch!
   where
   (str where " takes a structural Tier-1 tree node (the value "
        "ui.test/render returns, or any node reached by traversing it with "
        "(tree-seq map? :children tree)); got " (pr-str x) ". A mounted "
        "(Tier-3) test reads the DOM natively via the container bound by "
        "ui.test/with-root")
   {:got x}))

;; ---------------------------------------------------------------------------
;; Projections (tree contract §Projections)
;; ---------------------------------------------------------------------------

(defn attrs
  "The MERGED attribute projection of a structural node — the ONE
  attribute read (keyword lookup on a node reads its FIELDS, never its
  attributes: `(:on-click node)` is a field miss; attrs and events live
  under their own keys):

    element        → `:attrs` merged with `:events` (collision-free by
                     construction — the compiler routes `:on-*` to
                     `:events`; handler slots carry event vectors /
                     options maps / opaque markers AS DATA)
    view-boundary  → the `:props` map
    fragment/html  → `{}` (no attributes exist; total, not an error)
    nil            → nil (nil-punning threads through a missed traversal)

  Intent assertion is an equality check:
  `(is (= [:cart/add 42] (:on-click (ui.test/attrs node))))` where `node`
  is the button reached by `(some #(when (= :button (:tag %)) %)
  (tree-seq map? :children tree))`."
  [node]
  (cond
    (nil? node) nil
    (string? node)
    (malformed! 'rf.ui.test/attrs
                "text content is not a node — attrs projects map nodes; read text with ui.test/text on the PARENT node"
                node)
    (map? node)
    (case (node-kind 'rf.ui.test/attrs node)
      :element        (merge {} (:attrs node) (:events node))
      :view-boundary  (or (:props node) {})
      (:fragment :html) {})
    :else (not-a-node! 'rf.ui.test/attrs node)))

(defn- text*
  [where n]
  (case (node-kind where n)
    :html "" ; trusted-HTML contributes nothing — unparsed markup, not text data
    (apply str
           (map (fn [c]
                  (cond
                    (string? c) c
                    (map? c)    (text* where c)
                    :else (malformed!
                           where
                           (str "malformed tree — a :children entry must be a "
                                "node map or text content (a string); got "
                                (pr-str c))
                           c)))
                (:children n)))))

(defn text
  "The concatenation of `node`'s text descendants in document order —
  descending through elements, fragments and view boundaries alike;
  trusted-HTML nodes contribute nothing (their content is unparsed
  markup). No whitespace normalization beyond what the tree carries.
  `nil` → nil (nil-punning)."
  [node]
  (cond
    (nil? node) nil
    (string? node)
    (malformed! 'rf.ui.test/text
                "text content is not a node — it IS the text; call ui.test/text on the node that contains it"
                node)
    (map? node) (text* 'rf.ui.test/text node)
    :else (not-a-node! 'rf.ui.test/text node)))

;; ---------------------------------------------------------------------------
;; Mounted Tier 3 — one connected container, total ownership
;; ---------------------------------------------------------------------------

(defn- mounted-unavailable!
  [where got]
  (tier-mismatch!
   where
   #?(:clj  (str "Tier-3 mounted tests require a browser/jsdom host — the "
                 "JVM surface is the Tier-1 structural render")
      :cljs (str "Tier-3 mounted tests require a live DOM host and a "
                 "container bound by ui.test/with-root"))
   {:got got :other-tier 'rf.ui.test/render}))

#?(:cljs
   (do
     ;; React 19's supported test boundary is Promise-backed even when the
     ;; callback happens to be synchronous. Keep one explicit in-flight token:
     ;; a forgotten await must fail at the second call instead of creating
     ;; overlapping act scopes whose commit/cleanup order React cannot promise.
     (defonce ^:private active-act (atom nil))
     (defonce ^:private cleanup-tail (atom (js/Promise.resolve nil)))

     (defn- promise-call
       [thunk]
       (try
         (js/Promise.resolve (thunk))
         (catch :default e
           (js/Promise.reject e))))

     (defn- overlapping-act!
       [where active-where]
       (error/throw-error!
        :rf.error/ui-test-overlapping-act where
        (str "a previous ui.test React act operation from "
             (pr-str active-where) " is still pending — await each with-root/flush! "
             "Promise before starting the next mounted-test operation")
        {:recovery :await-the-prior-operation
         :extra {:active-where active-where}}))

     (defn- overlap-error
       "Build the typed overlap error without losing the synchronous public
       guard. Cleanup uses the returned value as its primary diagnostic while
       it waits for the in-flight act to settle and reclaims its own root."
       [where]
       (when-let [{active-where :where origin :origin} @active-act]
         ;; A private cleanup act is serialized by cleanup-tail. Another owner
         ;; joining that queue is reclamation, not a second public misuse.
         (when (= :public origin)
         (try
           (overlapping-act! where active-where)
           (catch :default e e)))))

     (defn- guard-no-active-act!
       [where]
       (when-let [{active-where :where} @active-act]
         (overlapping-act! where active-where)))

     (defn- act-operation!
       "Run one mounted-test step through React 19's directly imported `act`.
       Always returns a Promise and restores the caller's act-environment flag
       only after React settles. Overlap is rejected synchronously."
       [where origin thunk]
       (let [token (js-obj)
             prior (.-IS_REACT_ACT_ENVIRONMENT js/globalThis)]
         (guard-no-active-act! where)
         (reset! active-act {:token token :where where :origin origin})
         (set! (.-IS_REACT_ACT_ENVIRONMENT js/globalThis) true)
         (letfn [(finish! []
                   (when (identical? token (:token @active-act))
                     (reset! active-act nil))
                   (set! (.-IS_REACT_ACT_ENVIRONMENT js/globalThis) prior))]
           (let [settled
                 (try
                   (-> (js/Promise.resolve
                        (React/act (fn [] (promise-call thunk))))
                       (.then (fn [value]
                                (finish!)
                                value)
                              (fn [e]
                                (finish!)
                                (throw e))))
                   (catch :default e
                     (finish!)
                     (js/Promise.reject e)))]
             ;; Publish the exact Promise while this token is still active.
             ;; Internal teardown can then wait for (and sequence after) the
             ;; operation it collided with instead of leaking its own Root.
             (when (identical? token (:token @active-act))
               (swap! active-act assoc :settled settled))
             settled))))

     (defn- act!
       [where thunk]
       (act-operation! where :public thunk))

     (defn- act-when-idle!
       "Internal teardown boundary. Wait through every act already in flight,
       then claim the next act slot. Public overlap remains a synchronous
       error; this queue exists only so reporting misuse cannot strand the
       with-root allocation that discovered it."
       [where thunk]
       (if-let [{:keys [settled]} @active-act]
         (-> (or settled (js/Promise.resolve nil))
             (.then (fn [_] (act-when-idle! where thunk))
                    (fn [_] (act-when-idle! where thunk))))
         (act-operation! where :cleanup thunk)))

     (defn- enqueue-cleanup-act!
       "Serialize private owner reclamation. Each task runs after the previous
       cleanup regardless of its outcome and after any public act then active.
       The published tail always resolves, so one cleanup failure cannot poison
       later owners; the task Promise itself preserves that owner's failure."
       [where thunk]
       (let [prior @cleanup-tail
             task  (-> prior
                       (.then (fn [_] (act-when-idle! where thunk))
                              (fn [_] (act-when-idle! where thunk))))
             tail  (-> task
                       (.then (fn [_] nil) (fn [_] nil)))]
         (reset! cleanup-tail tail)
         task))

     ;; A failure slot holds this unique private sentinel until a failure is
     ;; recorded. PRESENCE — not JS truthiness — decides "did a failure occur?",
     ;; so a legitimately falsy caught/rejected reason (nil, false, 0) is a real,
     ;; preserved failure rather than being silently reclassified as success.
     (defonce ^:private absent #js {})

     (defn- recorded?
       "Has a failure been recorded into `slot`? Presence, not truthiness."
       [slot]
       (not (identical? @slot absent)))

     (defn- remember-first-error!
       "Record the FIRST failure in `slot` BY PRESENCE: once the slot holds a
       reason — even a falsy one (nil, false) — a later failure never overwrites
       it, so first-failure ordering is stable regardless of payload truthiness.
       The slot starts at the private `absent` sentinel."
       [slot e]
       (when (identical? @slot absent) (vreset! slot e))
       nil)

     (defn- cleanup-step!
       [slot thunk]
       (-> (promise-call thunk)
           (.then (fn [_] nil)
                  (fn [e]
                    (remember-first-error! slot e)))))

     (defn- attach-cleanup-diagnostic!
       "Preserve the primary throwable AS THE THROWN VALUE while, when a secondary
       cleanup failure is PRESENT, retaining it on the primary as
       `rfUiTestCleanupError` for diagnostics. Presence — not truthiness — gates
       the attach, so a falsy secondary is real. A primitive primary (nil, false,
       a number) cannot receive `defineProperty`; the secondary then rides the
       always-on console diagnostic instead, so it stays observable. The primary
       reason is returned UNCHANGED either way — never coerced or replaced."
       [primary secondary-present? secondary]
       (when secondary-present?
         (let [attached?
               (try
                 (js/Object.defineProperty
                  primary "rfUiTestCleanupError"
                  #js {:value secondary :configurable true})
                 true
                 (catch :default _ false))]
           (when (and (not attached?) (exists? js/console))
             (.warn js/console
                    (str "[re-frame.ui.test] a with-root cleanup failure could "
                         "not ride the primary rejection (a primitive reason "
                         "cannot carry a diagnostic property); the primary is "
                         "rethrown unchanged. Secondary cleanup error:")
                    secondary))))
       primary)

     (defn ^:no-doc with-root-outcome
       "The outcome policy of a `with-root` run, decided by failure PRESENCE
       (never JS truthiness): a first mount/render/body/flush failure wins as the
       rejection (a present secondary cleanup failure rides it as a diagnostic),
       else a cleanup-only failure is the rejection, else the awaited body value
       — which may itself be a legitimate nil/false — resolves. Returns
       `[:reject reason]` or `[:resolve value]`. Extracted as the ONE place the
       presence decision lives, so it is unit-checkable off the DOM."
       [primary-present? primary secondary-present? secondary result]
       (cond
         primary-present?
         [:reject (attach-cleanup-diagnostic! primary secondary-present? secondary)]

         secondary-present? [:reject secondary]
         :else              [:resolve result]))))

#?(:cljs
   (defn- with-root*
     "Promise-backed runtime owner for the `with-root` macro. Initial mount
     settles under React 19 `act` before the body runs; the body binds the
     connected DOM CONTAINER (native `.querySelector`/`.querySelectorAll` +
     ordinary DOM properties/events), may return a value or Promise, and is
     awaited. Every exit awaits host unmount and container removal. Cleanup
     never masks a primary mount/body failure; a second cleanup failure is
     attached as `rfUiTestCleanupError`."
     [create-f render-f body-f]
     (when-not (exists? js/document)
       (mounted-unavailable! 'rf.ui.test/with-root :no-dom-host))
     ;; Fail before createElement/appendChild. A forgotten await must never
     ;; allocate a container or claim a React root merely to report overlap.
     (guard-no-active-act! 'rf.ui.test/with-root)
     (let [container     (js/document.createElement "div")
           host-root     (volatile! nil)
           result        (volatile! nil)
           primary-error (volatile! absent)
           cleanup-error (volatile! absent)]
       (.setAttribute container "data-rf-ui-test-root" "")
       (.appendChild js/document.body container)
       (->
        ;; Capture the Root inside the callback: React can surface a render
        ;; failure at the awaited act boundary after the mount thunk returned.
        (act! 'rf.ui.test/with-root
              #(let [root (create-f container)]
                 ;; Creation registers the host root before render. Capture
                 ;; it first so a throwing initial render is still torn down.
                 (vreset! host-root root)
                 (render-f root)))
        (.then
         (fn []
           ;; Only an ACTUALLY settled initial commit opens the body context;
           ;; the body binds the connected DOM CONTAINER.
           (promise-call #(body-f container))))
        (.then (fn [value]
                 (vreset! result value)
                 nil)
               (fn [e]
                 (vreset! primary-error e)
                 nil))
        (.then
         (fn []
           ;; An un-awaited nested operation can still be inside act when the
           ;; outer body returns. Preserve that misuse as the rejection, but
           ;; wait/sequence the host unmount so the outer Root is reclaimed.
           (when-let [e (overlap-error 'rf.ui.test/with-root)]
             (remember-first-error! cleanup-error e))
           (-> (if @host-root
                 (cleanup-step!
                  cleanup-error
                  #(enqueue-cleanup-act! 'rf.ui.test/with-root
                                         (fn [] (ui/unmount! @host-root))))
                 (js/Promise.resolve nil))
               (.then (fn []
                        (cleanup-step! cleanup-error #(.remove container)))))))
        (.then
         (fn []
           (let [[outcome v] (with-root-outcome
                              (recorded? primary-error) @primary-error
                              (recorded? cleanup-error) @cleanup-error
                              @result)]
             (if (= :reject outcome) (throw v) v))))))))

#?(:clj
   (defmacro with-root
     "`(with-root [container root-form] body...)` — return a Promise that mounts
     the literal root form into a connected test-owned DOM CONTAINER, awaits the
     initial commit, invokes/awaits the body with that container bound, then
     awaits teardown of the React root and container on every exit. Query the
     container with native `.querySelector` / `.querySelectorAll` and read
     ordinary DOM properties/events.

     Browser/jsdom only. The root form is compiled by the same analyzer and
     emitter as `ui/render!`; each invocation mints a private runtime root
     identity, so concurrent calls through the same helper cannot collide
     with each other or claim an application's authored roots."
     [[binding root-form :as binding-form] & body]
     (when-not (and (vector? binding-form)
                    (= 2 (count binding-form))
                    (symbol? binding))
       (throw (IllegalArgumentException.
               "ui.test/with-root requires [binding literal-root-form]")))
     (if (some? (:ns &env))
       (let [container (gensym "container")
             root-sym  (gensym "root")
             root-id   (gensym "root-id")
             prefix    (gensym "prefix")
             render    (root/render-form &form &env root-sym root-form)]
         `(re-frame.ui.test/with-root*
           (fn [~container]
             (let [~root-id (keyword "rf.ui.test.root" (str (gensym "")))
                   ~prefix  (str "rf2-ui-test-" (name ~root-id) "-")]
               (re-frame.ui.client/create-root*
                {:root-id ~root-id
                 :provenance :authored
                 :identifier-prefix ~prefix
                 :site nil}
                ~container
                (re-frame.ui.client/root-options ~prefix nil nil nil))))
           (fn [~root-sym] ~render)
           (fn [~binding] ~@body)))
       `(re-frame.error/throw-error!
         :rf.error/ui-test-tier-mismatch 'rf.ui.test/with-root
         (str "Tier-3 mounted tests require a browser/jsdom host — the "
              "JVM surface is the Tier-1 structural render")
         {:recovery :use-the-other-tier
          :extra {:got :jvm :other-tier 'rf.ui.test/render}}))))

;; ---------------------------------------------------------------------------
;; flush! — the epoch drain (07 §2 "act + epoch drain + commit — the only
;; flush idiom"). CLJS mounted host only: the JVM structural render has no
;; React tree to settle — a Tier-1 checkpoint is a FRESH render after a
;; synchronous rf/dispatch-sync.
;; ---------------------------------------------------------------------------

#?(:cljs
   (do
     (defn- guard-open-drain!
       []
       ;; The open-event-drain ruling is owned by CORE (`re-frame.frame`) and shared
       ;; by every substrate whose synchronous flush can publish a render phase —
       ;; this all-roots test spelling, the first-party adapter's `flush-render!`,
       ;; and Freehand's. ONE guard, not one copy per substrate (rf2-87ouj).
       ;; It survives a handler destroying its own frame (`*run-frame-state-before*`
       ;; outlives a live-registry scan), so a destroy-self-then-flush call still
       ;; fails before delivering render-phase work inside the still-open run.
       (frame/guard-open-drain! 'rf.ui.test/flush!))

     (defn- flush-async!
       [thunk]
       ;; The async twin of the JVM `converge-flush!` loop: framework
       ;; notifications and React commits alternate to a fixed point, BOUNDED by
       ;; the same `reactive/flush-convergence-budget` (rf2-0faipl). The happy
       ;; path (a registry that quiesces in a handful of passes) is unchanged; a
       ;; commit path that re-dirties every pass rejects the returned Promise
       ;; with :rf.error/flush-convergence-exceeded — via the shared
       ;; `reactive/flush-nonconvergence!` diagnostic — instead of chaining
       ;; passes forever.
       (letfn [(cycle! [run-thunk pass]
                 (-> (act! 'rf.ui.test/flush!
                           (fn []
                             (-> (promise-call run-thunk)
                                 (.then (fn [_]
                                          (reactive/flush-pending!)
                                          nil)))))
                     (.then (fn []
                              (let [pending (reactive/pending-cell-count)]
                                (when (pos? pending)
                                  (if (>= pass reactive/flush-convergence-budget)
                                    (reactive/flush-nonconvergence!
                                      'rf.ui.test/flush! pending)
                                    (cycle! (fn [] nil) (inc pass)))))))))]
         (cycle! thunk 0)))

     (defn flush!
       "The sole public compiled-view test flush.

       `(flush!)` and `(flush! thunk)` return Promises on CLJS. The thunk
       (when supplied) runs inside awaited React 19 `act`; then framework
       notifications and React commits alternate to a fixed point. Await the
       Promise before asserting or beginning another mounted operation. Drive a
       mounted dispatch with `(flush! #(rf/dispatch-sync event {:frame f}))`.

       The open-event-drain guard runs synchronously BEFORE Promise
       construction and throws `:rf.error/flush-in-open-epoch`."
       ([]
        (guard-open-drain!)
        (flush-async! (fn [] nil)))
       ([thunk]
        (guard-open-drain!)
        (when-not (fn? thunk)
          (bad-opts! 'rf.ui.test/flush!
                     (str "the flush! thunk arity requires a function; got "
                          (pr-str thunk))
                     {:got thunk}))
        (flush-async! thunk)))

     ;; ------------------------------------------------------------------------
     ;; flush-presence! — the S4 fake-clock transition advance (rf2-uckeg)
     ;; ------------------------------------------------------------------------

     (defn flush-presence!
       "Advance the presence fake clock so retained (:unmounting) children reach
       their :timeout-ms removal WITHOUT wall-clock sleeps — the S4 twin of
       `flush!`. `(flush-presence!)` advances to quiescence (every pending exit
       fires); `(flush-presence! ms)` advances the logical clock by `ms`, firing
       only the exits that come due. The advance + its removal commits run inside
       awaited React `act`; the returned Promise settles at the framework/React
       fixed point (drive an enter/exit assertion after awaiting it). The
       open-event-drain guard runs synchronously first."
       ([]
        (guard-open-drain!)
        (flush-async! (fn [] (presence/advance-clock!) nil)))
       ([ms]
        (guard-open-drain!)
        (flush-async! (fn [] (presence/advance-clock! ms) nil))))))

;; ---------------------------------------------------------------------------
;; render (S1: JVM structural render — root-identity-and-mount §9)
;; ---------------------------------------------------------------------------

#?(:clj
   (defn- validate-render-opts!
     [opts]
     (when (some? opts)
       (when-not (map? opts)
         (bad-opts! 'rf.ui.test/render
                    (str "render opts must be a map; got " (pr-str opts))
                    {:got opts}))
       (when-let [unknown (seq (remove #{:sub-overrides} (keys opts)))]
         (bad-opts! 'rf.ui.test/render
                    (str "unknown render opt" (when (next unknown) "s") " "
                         (pr-str (vec unknown)) " — the only render option is "
                         ":sub-overrides. Props ride IN the view form "
                         "([view props]); frame scope is rf/with-frame / "
                         "rf/with-new-frame")
                    {:unknown (vec unknown)}))
       (when (contains? opts :sub-overrides)
         (let [so (:sub-overrides opts)]
           (when-not (and (map? so) (every? vector? (keys so)))
             (bad-opts! 'rf.ui.test/render
                        (str ":sub-overrides is a map of query VECTOR → value "
                             "(e.g. {[:cart/locked?] true}); got " (pr-str so))
                        {:sub-overrides so})))))))

#?(:clj
   (defn- render-with-opts
     "Establish the override door, run the compiled render thunk under the
  AMBIENT frame, stamp the root with the tree version (the root is always the
  view's boundary node — a map). With no ambient frame bound, structural
  rendering proceeds frameless — any frame-scoped read raises honestly rather
  than defaulting; establish frame scope with rf/with-frame / rf/with-new-frame."
     [opts thunk]
     (let [run   (if (contains? opts :sub-overrides)
                   #(binding [reactive/*sub-overrides* (:sub-overrides opts)] (thunk))
                   thunk)
           ;; Establish the JVM render-slice memo scope around the render thunk
           ;; (rf2-vxgfnd.267): a single render shares ONE slice memo (sibling
           ;; cold probes of a shared derived parent compute it once) and a later
           ;; render recomputes. Re-entrant, so a nested `with-capture` reuses it.
           slice #(reactive/with-slice-memo run)
           root  (slice)]
       (assoc root :rf.ui/tree-version tree/tree-version))))

#?(:clj
   (defn ^:no-doc render-form*
     "Runtime half of `render` — the compiled template thunk of a literal
  view form."
     [thunk opts]
     (validate-render-opts! opts)
     (render-with-opts (or opts {}) thunk)))

#?(:clj
   (defn- render-literal-form
     "Expansion for the render form: a literal view form — the SAME root grammar
  `ui/mount` takes (`root/analyze-root`) surrounding exactly ONE mounted view
  (root identity is the view's id). A top-region `frame-root` (a plan-bearing
  form) is NOT a render form — render owns no frame lifecycle."
     [menv form opts]
     (let [e   (-> (env/make-env {:host :clj :ns-sym (ns-name *ns*)})
                   (env/with-locals (keys menv)))
           {:keys [ast views plans]} (root/analyze-root e 'rf.ui.test/render form)]
       (when (seq plans)
         (throw (env/compile-error
                 :rf.ui.compile/bad-test-root
                 (str "ui.test/render: a plan-bearing root form (a top-region "
                      "frame-root) is not a render form — render owns no frame "
                      "lifecycle. Establish the frame with rf/with-new-frame "
                      "(eval-bind-run-destroy) and render the plan-free view, "
                      "or mount the root under ui.test/with-root where runtime "
                      "root preflight is the test subject")
                 {:form form})))
       (when-not (= 1 (count views))
         (throw (env/compile-error
                 :rf.ui.compile/bad-test-root
                 (str "ui.test/render: a root form mounts exactly ONE view — "
                      "root identity is the mounted view's id; this form has "
                      (count views) ". "
                      (if (zero? (count views))
                        (str "Wrap the markup in a defview (bare elements / "
                             "fragments / foreign heads have no view identity)")
                        (str "Keep one mounted view (a fragment of two views "
                             "has no single identity)")))
                 {:form form :view-ids (mapv :view-id views)})))
       (doseq [w @(:warnings e)]
         (binding [*out* *err*]
           (println (str "WARNING re-frame.ui [ui.test/render] "
                         (:id w) ": " (:msg w)))))
       `(render-form* (fn [] ~(emit-jvm/emit-node ast)) ~opts))))

#?(:clj
   (defmacro render
     "`(render [view props] opts?)` — run the real compiled view against the
  AMBIENT frame on the JVM and return the versioned public STRUCTURAL TREE
  (the top node — a view boundary, or the top-region wrapper enclosing the one
  mounted view — stamped `:rf.ui/tree-version`).

  ONE input grammar: a LITERAL view form — the SAME root grammar `mount` takes
  (`root/analyze-root`): the top-region wrappers (element / fragment /
  `frame-provider`) surrounding exactly ONE mounted view, props carried IN the
  form, e.g. `(render [product-card {:product p}])`. A runtime-assembled vector
  is the same compile error as at `mount` — hiccup is compiled, not
  interpreted.

  ONE option: `{:sub-overrides {query-v value}}` (the explicit JVM override
  door, consumed by the S2 read slice). `:sub-overrides` affects render READS
  only — it does not mutate app-db.

  Frame scope is the programmer's ordinary bracket — there is no frame option:
  `rf/with-new-frame [f (rf/make-frame {:initial-events [...]})]` for a fresh
  owned frame (eval-bind-run-destroy — the frame never leaks into `rf/frame-ids`),
  or `rf/with-frame f` to pin one you hold. Drive state with
  `(rf/dispatch-sync [...] {:frame f})` and assert on a FRESH `render`. With no
  ambient frame, structural rendering proceeds frameless and any frame-scoped
  read raises honestly.

  Tier-1 renders the JVM structural subset: no effects, no host ops; `sub` is
  the one-shot headless read (03 §3). CLJS has no structural trees (the client
  emitter targets React directly) — expanding this macro in a CLJS compile is a
  didactic compile error."
     ([root-form] `(render ~root-form nil))
     ([root-form opts]
      (when (some? (:ns &env))
        (throw (env/compile-error
                :rf.ui.compile/ui-test-jvm-only
                (str "ui.test/render is the Tier-1 JVM structural render — "
                     "CLJS builds have no structural trees (the client "
                     "emitter targets React directly). Tier-1 tests are "
                     ".clj/.cljc-on-JVM; mounted CLJS tests arrive with the "
                     "Tier-3 surface (with-root, S1c/S2)")
                nil)))
      (if (vector? root-form)
        (render-literal-form &env root-form opts)
        (throw (env/compile-error
                :rf.ui.compile/bad-test-render-form
                (str "ui.test/render takes a LITERAL view form vector "
                     "(hiccup is compiled, not interpreted; a runtime-assembled "
                     "value cannot be a template); got " (pr-str root-form))
                {:form root-form}))))))
