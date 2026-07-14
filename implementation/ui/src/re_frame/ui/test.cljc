(ns re-frame.ui.test
  "`ui.test` — the Tier-1 testing surface of the compiled-view substrate
  (rf2-vxgfnd S1d; 07 §2 'one namespace, one table').

  Tier-1 tests are HEADLESS: `render` runs the real compiled view
  against a real frame on the JVM and returns the versioned public
  STRUCTURAL TREE (node schema v1, the jvm-tree-and-conversion-contract
  ABI); `find`/`find-all` query it with the CLOSED selector grammar;
  `attrs`/`text` are the read projections. Handlers are event vectors as
  data, so 'what does this button do' is an equality check — no click
  simulation, no DOM, no flake:

      (let [frame (ui.test/frame {:app-db {:cart #{}}})
            tree  (ui.test/render [product-card {:product p}]
                                  {:frame frame})]
        (is (= [:cart/add 42]
               (-> tree (ui.test/find :button) ui.test/attrs :on-click)))
        (is (= \"Add to cart\"
               (-> tree (ui.test/find :button) ui.test/text))))

  ## The selector grammar (closed — drafts/ui-test-selector-grammar.md)

      selector := tag-kw    ; unqualified keyword — element tag, exact
                | view-sel  ; qualified keyword (view id) or defview var
                | attr-map  ; {attr-key expected} matched by rf= over the
                            ; attrs projection (events match by vector)
                | pred-fn   ; (fn [node] boolean) — the escape

  The path/vector form `[selector+]` (OPEN-2) and the strict `find!`
  (OPEN-3) are demand-bar items NOT shipped at S1 — no Stage-1 fixture
  or guide example materialised the need; a vector selector raises a
  typed error naming the composition alternative
  `(find (find tree :form) :button)`.

  ## Tier split

  `query` is the Tier-3 LIVE-DOM counterpart of `find` — a mounted root
  + a native CSS selector string, sharing nothing with the grammar
  above. Handing a CSS string to `find`, a structural tree to `query`,
  or a DOM element to `attrs`/`text` is a typed error
  (`:rf.error/ui-test-tier-mismatch`) pointing at the other tier.
  Tier-3 mounting is deliberately small: `with-root` owns one real React
  mount with total teardown, `query` delegates a native CSS selector to
  that root's container, programmatic `dispatch!` drives S2 framework
  state, and Promise-backed `flush!` drains framework work to quiescence
  under awaited React `act`. Ordinary DOM APIs cover already-host-owned mechanics; compiled
  event-vector delivery through native events lands S3. There is no gesture
  DSL and no production `re-frame.ui/flush!` twin.

  ## JVM semantics under test (06 §1 subset)

  Structure, branches, lists and event intent are fully faithful; `sub`
  reads are the one-shot headless read (03 §3) — resolved against the
  render's frame and the explicit `:sub-overrides` door — effects don't
  run, host ops raise `:rf.error/jvm-host-op`. The events/subs a view
  touches must be `.cljc` — the standard re-frame discipline.

  Dev/test scope ONLY: nothing in a production bundle may `:require`
  this namespace (bundle-isolation gate)."
  (:refer-clojure :exclude [find])
  #?(:cljs (:require-macros [re-frame.ui.test]))
  (:require [re-frame.error :as error]
            [re-frame.frame :as rframe]
            [re-frame.live-frame :as live-frame]
            [re-frame.registrar :as registrar]
            [re-frame.router :as router]
            [re-frame.ui.eq :as eq]
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

(defn- bad-selector!
  [where reason extra]
  (error/throw-error! :rf.error/ui-test-bad-selector where reason
                      {:recovery :use-a-grammar-selector
                       :extra    extra}))

(defn- bad-opts!
  [where reason extra]
  (error/throw-error! :rf.error/ui-test-bad-opts where reason
                      {:recovery :fix-the-opts-map
                       :extra    extra}))

;; ---------------------------------------------------------------------------
;; Node discrimination + traversal (tree contract §Node schema)
;; ---------------------------------------------------------------------------

(defn- node-kind
  "Discriminate a MAP node per the pinned order (string → text is handled
  by callers): `:tag` → element, else `:view-id` → view-boundary, else
  `:html` → trusted-HTML, else `:children` → fragment. More than one
  primary discriminating field, or none of the four, is malformed —
  every tree consumer fails loud (tree contract §Node schema)."
  [where m]
  (let [primaries (cond-> 0
                    (contains? m :tag)     inc
                    (contains? m :view-id) inc
                    (contains? m :html)    inc)]
    (when (> primaries 1)
      (malformed! where
                  (str "malformed tree node — a map may carry only ONE of "
                       ":tag / :view-id / :html (the closed five-variant node "
                       "set); got " (pr-str (select-keys m [:tag :view-id :html])))
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

(defn- child-nodes
  "The MAP-node children of `m` in document order — text content (strings)
  is skipped (content, not a queryable node); anything else in a
  :children vector is malformed."
  [where m]
  (into []
        (keep (fn [c]
                (cond
                  (string? c) nil
                  (map? c)    c
                  :else (malformed!
                         where
                         (str "malformed tree — a :children entry must be a node "
                              "map or text content (a string); got " (pr-str c))
                         c))))
        (:children m)))

(defn- node-seq
  "Depth-first pre-order (document order) seq of the MAP nodes of `n` —
  the node itself first, then its descendants; fragment and view-boundary
  children are descended alike; trusted-HTML nodes are leaves (their
  markup is unparsed); text is never visited."
  [where n]
  (node-kind where n) ; validates (throws on malformed)
  (lazy-seq (cons n (mapcat #(node-seq where %) (child-nodes where n)))))

#?(:cljs
   (defn- dom-element? [x]
     (and (exists? js/Element) (instance? js/Element x))))

(defn- not-a-node!
  "Shared rejection for a non-node input where a structural node was
  required — a mounted/host root points at the Tier-3 surface."
  [where x]
  #?(:cljs
     (when (dom-element? x)
       (tier-mismatch!
        where
        (str where " projects Tier-1 STRUCTURAL nodes — got a live DOM "
             "element (Tier 3). Read live DOM via host interop on the "
             "element (e.g. (.-value el)) after ui.test/query")
        {:got :dom-element})))
  (tier-mismatch!
   where
   (str where " takes a structural Tier-1 tree node (the value "
        "ui.test/render returns / ui.test/find matches); got "
        (pr-str x) ". A mounted (Tier-3) root queries with "
        "ui.test/query + a native CSS selector string")
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
    nil            → nil (nil-punning threads through a missed `find`)

  Intent assertion is an equality check:
  `(is (= [:cart/add 42] (:on-click (ui.test/attrs (ui.test/find tree :button)))))`."
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
;; The selector grammar (closed)
;; ---------------------------------------------------------------------------

(defn- registered-view-fn?
  "Best-effort didactic guard: is `f` a registered compiled view's
  handler-fn? (A defview NAME evaluates to the view fn — as a selector
  that would silently behave as a match-everything pred-fn; the grammar's
  view-selector spellings are the view-id keyword or the defview VAR.)"
  [f]
  (boolean
   (some (fn [[_ m]] (identical? f (:handler-fn m)))
         (get @registrar/kind->id->metadata :view))))

(defn- var-view-pred
  [where sel]
  (let [m  (meta sel)
        id (:rf.ui/view-id m)]
    (when-not (and (:rf.ui/view m) id)
      (bad-selector!
       where
       (str "var " sel " is not a defview — a view selector is the "
            "registered view id (qualified keyword) or the defview var")
       {:selector sel}))
    (fn [node] (= id (:view-id node)))))

(defn- selector-pred
  "Compile one selector of the CLOSED grammar into a node predicate.
  Anything else is a typed error — deliberately absent forms
  (sibling/nth/positional combinators, wildcard, text-content selectors)
  are covered by pred-fn, the grammar's escape."
  [where sel]
  (cond
    ;; tag-kw — unqualified keyword, exact element-tag match
    ;; view-sel — qualified keyword matches the view-boundary node
    ;; (fragment/nil-rooted views ARE matchable — the boundary marker
    ;; exists even when no single root element does)
    (keyword? sel)
    (if (namespace sel)
      (fn [node] (= sel (:view-id node)))
      (fn [node] (= sel (:tag node))))

    (var? sel)
    (var-view-pred where sel)

    ;; attr-map — every entry present in the attrs projection and rf= to
    ;; the expected value ({} matches every node — vacuous truth)
    (map? sel)
    (fn [node]
      (let [proj (attrs node)]
        (every? (fn [[k v]]
                  (and (contains? proj k)
                       (eq/rf= v (get proj k))))
                sel)))

    ;; CSS string → the Tier-3 surface
    (string? sel)
    (tier-mismatch!
     where
     (str "a CSS selector string was handed to the Tier-1 structural query "
          where " — CSS is the Tier-3 contract: (ui.test/query root "
          (pr-str sel) ") on a MOUNTED root. Structural trees query with "
          "the closed grammar: tag keyword, view id / defview var, attr "
          "map, or pred fn")
     {:selector sel :other-tier 'rf.ui.test/query})

    ;; path/vector form — OPEN-2, demand-bar: NOT shipped at S1
    (vector? sel)
    (bad-selector!
     where
     (str "the path/vector selector form " (pr-str sel) " is not in the "
          "shipped grammar (demand-bar item OPEN-2 — no Stage-1 fixture "
          "or guide example needs it). Compose finds instead: "
          "(find (find tree :form) :button) — a found node is itself a "
          "valid tree argument")
     {:selector sel})

    ;; pred-fn — the escape (any fn; receives MAP nodes only)
    (fn? sel)
    (do (when (registered-view-fn? sel)
          (bad-selector!
           where
           (str "the selector is a compiled view FN — as a pred-fn it would "
                "match every node. A view selector is the registered view id "
                "(qualified keyword) or the defview VAR (#'the-view)")
           {:selector sel}))
        (fn [node] (boolean (sel node))))

    :else
    (bad-selector!
     where
     (str (pr-str sel) " is not a selector — the CLOSED grammar is: an "
          "unqualified keyword (element tag), a qualified keyword or "
          "defview var (view boundary), an attr map (rf= over the attrs "
          "projection), or a pred fn")
     {:selector sel})))

(defn- find-tree-seq
  "Validated document-order node seq of a `find`/`find-all` tree argument."
  [where tree]
  (cond
    (string? tree)
    (malformed! where
                "text content is not a queryable node — selectors never match text; query the node that contains it"
                tree)
    (map? tree) (node-seq where tree)
    :else
    (tier-mismatch!
     where
     (str where " queries structural Tier-1 trees (the value ui.test/render "
          "returns); got " (pr-str tree) ". A mounted (Tier-3) root queries "
          "with ui.test/query + a native CSS selector string")
     {:got tree :other-tier 'rf.ui.test/query})))

(defn find
  "The FIRST node of `tree` matching `selector`, in depth-first pre-order
  (document order) — the tree node itself is tested first, then its
  descendants. Returns the structural node (a plain map — itself a valid
  `tree` argument, so finds compose), or nil on no match (idiomatic
  nil-punning: `(-> tree (find :button) attrs :on-click)` yields nil
  through the thread)."
  [tree selector]
  (if (nil? tree)
    nil
    (let [pred (selector-pred 'rf.ui.test/find selector)]
      (some #(when (pred %) %) (find-tree-seq 'rf.ui.test/find tree)))))

(defn find-all
  "ALL nodes of `tree` matching `selector`, as a vector in document order
  (possibly empty — `[]` on no match)."
  [tree selector]
  (if (nil? tree)
    []
    (let [pred (selector-pred 'rf.ui.test/find-all selector)]
      (into [] (filter pred) (find-tree-seq 'rf.ui.test/find-all tree)))))

;; ---------------------------------------------------------------------------
;; Mounted Tier 3 — one opaque root, one native query, total ownership
;; ---------------------------------------------------------------------------

#?(:cljs (deftype ^:private MountedRoot [host-root container live?]))

#?(:cljs
   (defn- mounted-root? [x]
     (instance? MountedRoot x)))

(defn- mounted-unavailable!
  [where got]
  (tier-mismatch!
   where
   #?(:clj  (str "Tier-3 mounted tests require a browser/jsdom host — the "
                 "JVM surface is Tier-1 structural render + find/find-all")
      :cljs (str "Tier-3 mounted tests require a live DOM host and a root "
                 "created by ui.test/with-root"))
   {:got got :other-tier 'rf.ui.test/find}))

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

     (defn- remember-first-error!
       [slot e]
       (when-not @slot (vreset! slot e))
       nil)

     (defn- cleanup-step!
       [slot thunk]
       (-> (promise-call thunk)
           (.then (fn [_] nil)
                  (fn [e]
                    (remember-first-error! slot e)))))

     (defn- attach-cleanup-diagnostic!
       "Preserve the primary throwable while retaining a cleanup failure on
       the ordinary JS Error/ExceptionInfo object for diagnostics."
       [primary cleanup]
       (when (and primary cleanup)
         (try
           (js/Object.defineProperty
            primary "rfUiTestCleanupError"
            #js {:value cleanup :configurable true})
           (catch :default _ nil)))
       primary)))

#?(:cljs
   (defn- with-root*
     "Promise-backed runtime owner for the `with-root` macro. Initial mount
     settles under React 19 `act` before the body runs; the body may return a
     value or Promise and is awaited. Every exit awaits host unmount and
     container removal. Cleanup never masks a primary mount/body failure; a
     second cleanup failure is attached as `rfUiTestCleanupError`."
     [create-f render-f body-f]
     (when-not (exists? js/document)
       (mounted-unavailable! 'rf.ui.test/with-root :no-dom-host))
     ;; Fail before createElement/appendChild. A forgotten await must never
     ;; allocate a container or claim a React root merely to report overlap.
     (guard-no-active-act! 'rf.ui.test/with-root)
     (let [container     (js/document.createElement "div")
           host-root     (volatile! nil)
           mounted-root  (volatile! nil)
           result        (volatile! nil)
           primary-error (volatile! nil)
           cleanup-error (volatile! nil)]
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
           ;; Only an ACTUALLY settled initial commit opens the body context.
           (vreset! mounted-root
                    (MountedRoot. @host-root container (atom true)))
           (promise-call #(body-f @mounted-root))))
        (.then (fn [value]
                 (vreset! result value)
                 nil)
               (fn [e]
                 (vreset! primary-error e)
                 nil))
        (.then
         (fn []
           (when-let [^MountedRoot r @mounted-root]
             (reset! (.-live? r) false))
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
           (cond
             @primary-error
             (throw (attach-cleanup-diagnostic! @primary-error @cleanup-error))

             @cleanup-error (throw @cleanup-error)
             :else @result)))))))

#?(:clj
   (defmacro with-root
     "`(with-root [root root-form] body...)` — return a Promise that mounts
     the literal root form into a connected test-owned DOM container, awaits
     the initial commit, invokes/awaits the body with its opaque mounted root,
     then awaits teardown of the React root and container on every exit.

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
              "JVM surface is Tier-1 structural render + find/find-all")
         {:recovery :use-the-other-tier
          :extra {:got :jvm :other-tier 'rf.ui.test/find}}))))

(defn query
  "`(query root css-selector)` — the Tier-3 LIVE-DOM counterpart of
  `find`: a MOUNTED root (`with-root`) + a native CSS selector string,
  answered by the host DOM's querySelector. It shares nothing with the
  Tier-1 selector grammar — no rf= matching, no structural projections,
  no view-id selectors: CSS is the whole contract.

  The root must be the live opaque value bound by `with-root`; the selector
  must be a string. A miss returns nil, exactly like `querySelector`."
  [root css-selector]
  (if (map? root)
    (tier-mismatch!
     'rf.ui.test/query
     (str "a structural Tier-1 tree was handed to ui.test/query — query is "
          "the Tier-3 live-DOM counterpart (mounted root + native CSS). "
          "Structural trees query with ui.test/find / find-all and the "
          "closed selector grammar (tag keyword, view id / defview var, "
          "attr map, pred fn)")
     {:got root :other-tier 'rf.ui.test/find})
    #?(:clj
       (mounted-unavailable! 'rf.ui.test/query root)
       :cljs
       (let [^MountedRoot root root]
         (when-not (mounted-root? root)
           (mounted-unavailable! 'rf.ui.test/query root))
         (when-not @(.-live? root)
           (mounted-unavailable! 'rf.ui.test/query :released-root))
         (when-not (string? css-selector)
           (bad-selector!
            'rf.ui.test/query
            (str "ui.test/query takes a native CSS selector STRING; got "
                 (pr-str css-selector))
            {:selector css-selector}))
         (.querySelector (.-container root) css-selector)))))

;; ---------------------------------------------------------------------------
;; Frames (07 §2 — `frame`, `dispatch!`)
;; ---------------------------------------------------------------------------

(defn frame
  "Mint a TEST frame — registrations come from the loaded namespaces (the
  default image over the active source store + framework standards), the
  app-db seed from `:app-db` (dispatched as `[:rf/set-db seed]`, drained
  to fixed point before this returns).

  `opts` is CLOSED: `{:app-db <map>}` (or `{}`). Anything richer —
  images, ids, fx-overrides — is `rf/make-frame`'s vocabulary; use it
  directly.

  Returns the live frame VALUE — pass it (or hold it) wherever a frame
  target is accepted: `(render … {:frame f})`, `(dispatch! f event)`,
  `rf/app-db-value`. Prerequisite: an installed substrate adapter (JVM
  tests: the plain-atom adapter via `re-frame.test-support/`
  `make-reset-runtime-fixture` or `rf/init!`)."
  ([] (frame {}))
  ([opts]
   (when-not (map? opts)
     (bad-opts! 'rf.ui.test/frame
                (str "frame opts must be a map; got " (pr-str opts))
                {:got opts}))
   (when-let [unknown (seq (remove #{:app-db} (keys opts)))]
     (bad-opts! 'rf.ui.test/frame
                (str "unknown frame opt" (when (next unknown) "s") " "
                     (pr-str (vec unknown)) " — ui.test/frame's opts are "
                     "CLOSED: {:app-db <map>}. Richer construction (images, "
                     "ids, fx-overrides) is rf/make-frame's vocabulary")
                {:unknown (vec unknown)}))
   (when (and (contains? opts :app-db) (not (map? (:app-db opts))))
     (bad-opts! 'rf.ui.test/frame
                (str ":app-db must be a map (the app-db seed); got "
                     (pr-str (:app-db opts)))
                {:app-db (:app-db opts)}))
   (live-frame/make-frame
    (cond-> {}
      (contains? opts :app-db)
      (assoc :initial-events [[:rf/set-db (:app-db opts)]])))))

(defn dispatch!
  "Real dispatch + drain into `frame-target` (a frame value or id):
  processes `event` synchronously end-to-end, then drains any
  synchronously-enqueued events to fixed point. Drive state with real
  events, re-render, assert on the new tree."
  [frame-target event]
  (router/dispatch-sync! event {:frame frame-target})
  nil)

;; ---------------------------------------------------------------------------
;; flush! — the epoch drain (07 §2 "act + epoch drain + commit — the only
;; flush idiom")
;; ---------------------------------------------------------------------------

(defn- guard-open-drain!
  []
  ;; `*run-frame-state-before*` is bound around the current event-pipeline
  ;; run and survives a handler destroying its own frame. A live-registry scan
  ;; cannot: destroy removes the active frame before the handler returns, which
  ;; used to let a destroy-self-then-flush call cross this guard and deliver
  ;; read-side work inside the still-open run.
  (when (some? rframe/*run-frame-state-before*)
    (let [frame-id (rframe/frame-target->id rframe/*current-frame*)]
      (error/throw-error!
       :rf.error/flush-in-open-epoch 'rf.ui.test/flush!
       (str "ui.test/flush! was called while frame " (pr-str frame-id)
            " is still inside its event drain — let the queued write side "
            "settle to drain quiescence before forcing the one read/render batch")
       {:recovery :no-recovery
        :extra {:frame frame-id
                :frame-epoch (rframe/frame-commit-epoch frame-id)}}))))

#?(:clj
   (defn flush!
     "`(flush!)` — synchronously drain the host-agnostic ViewCell registry on
     the JVM Tier-1 host. There is no React tree to settle. Returns nil."
     []
     (guard-open-drain!)
     (loop []
       (reactive/flush-pending!)
       (when (pos? (reactive/pending-cell-count))
         (recur)))
     nil)
   :cljs
   (do
     (defn- flush-async!
       [thunk]
       (letfn [(cycle! [run-thunk]
                 (-> (act! 'rf.ui.test/flush!
                           (fn []
                             (-> (promise-call run-thunk)
                                 (.then (fn [_]
                                          (reactive/flush-pending!)
                                          nil)))))
                     (.then (fn []
                              (if (pos? (reactive/pending-cell-count))
                                (cycle! (fn [] nil))
                                nil)))))]
         (cycle! thunk)))

     (defn flush!
       "The sole public compiled-view test flush.

       `(flush!)` and `(flush! thunk)` return Promises on CLJS. The thunk
       (when supplied) runs inside awaited React 19 `act`; then framework
       notifications and React commits alternate to a fixed point. Await the
       Promise before asserting or beginning another mounted operation.

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
        (flush-async! thunk)))))

;; ---------------------------------------------------------------------------
;; render (S1: JVM structural render — root-identity-and-mount §9)
;; ---------------------------------------------------------------------------

#?(:clj
   (defn- validate-render-opts!
     [opts allow-props? plan-bearing?]
     (when (some? opts)
       (when-not (map? opts)
         (bad-opts! 'rf.ui.test/render
                    (str "render opts must be a map; got " (pr-str opts))
                    {:got opts}))
       (when-let [unknown (seq (remove #{:frame :app-db :props :sub-overrides}
                                       (keys opts)))]
         (bad-opts! 'rf.ui.test/render
                    (str "unknown render opt" (when (next unknown) "s") " "
                         (pr-str (vec unknown)) " — the opts are CLOSED: "
                         ":frame XOR :app-db, :props (bare-view form only), "
                         ":sub-overrides")
                    {:unknown (vec unknown)}))
       (when (and (contains? opts :props) (not allow-props?))
         (bad-opts! 'rf.ui.test/render
                    "a literal root form carries its props IN the form — {:props …} combines only with a bare view reference"
                    {:props (:props opts)}))
       (when (and plan-bearing? (or (contains? opts :frame) (contains? opts :app-db)))
         (bad-opts! 'rf.ui.test/render
                    (str "a PLAN-BEARING root form OWNS its frames — its "
                         "top-region frame-root(s) preflight fresh test frames "
                         "before the render. Drop {:frame …}/{:app-db …}; a "
                         "frame plan and an explicit frame are two ways to say "
                         "one thing. Explicit frame opts stay valid for "
                         "plan-free root/view forms")
                    (select-keys opts [:frame :app-db])))
       (when (and (contains? opts :frame) (contains? opts :app-db))
         (bad-opts! 'rf.ui.test/render
                    "{:frame f} XOR {:app-db v} — :app-db mints a fresh test frame, :frame targets one you hold; pass one"
                    {:frame (:frame opts) :app-db (:app-db opts)}))
       (when (and (contains? opts :props) (not (map? (:props opts))))
         (bad-opts! 'rf.ui.test/render
                    (str ":props must be a map — a view is a pure function of "
                         "ONE props map; got " (pr-str (:props opts)))
                    {:props (:props opts)}))
       (when (contains? opts :sub-overrides)
         (let [so (:sub-overrides opts)]
           (when-not (and (map? so) (every? vector? (keys so)))
             (bad-opts! 'rf.ui.test/render
                        (str ":sub-overrides is a map of query VECTOR → value "
                             "(e.g. {[:cart/locked?] true}); got " (pr-str so))
                        {:sub-overrides so})))))))

#?(:clj
   (defn- render-with-opts
     "Establish the frame scope + override door, run the compiled render
  thunk, stamp the root with the tree version (the root is always the
  view's boundary node — a map). With NEITHER :frame nor :app-db,
  structural rendering proceeds frameless — any frame-scoped read
  raises honestly rather than defaulting."
     [opts thunk]
     (let [run   (if (contains? opts :sub-overrides)
                   #(binding [reactive/*sub-overrides* (:sub-overrides opts)] (thunk))
                   thunk)
           ;; Establish the JVM render-slice memo scope around the render thunk
           ;; (rf2-vxgfnd.267): the two `ui.test/render` compiled routes — a
           ;; view reference and a plan-free literal root form — converge HERE,
           ;; so a single render shares ONE slice memo (sibling cold probes of a
           ;; shared derived parent compute it once) and a later render
           ;; recomputes. Re-entrant, so a nested `with-capture` reuses it.
           slice #(reactive/with-slice-memo run)
           root  (cond
                   (contains? opts :frame)
                   (binding [rframe/*current-frame*
                             (rframe/frame-target->id (:frame opts))]
                     (slice))

                   (contains? opts :app-db)
                   (let [id (rframe/frame-target->id
                             (frame {:app-db (:app-db opts)}))]
                     (try
                       (binding [rframe/*current-frame* id] (slice))
                       (finally (rframe/destroy-frame! id))))

                   :else (slice))]
       (assoc root :rf.ui/tree-version tree/tree-version))))

#?(:clj
   (defn- reject-frame-collision!
     "The Tier-1 fresh-frame contract (rf2-vxgfnd.55): a plan-bearing
  `ui.test/render` GUARANTEES fresh ISOLATED test frames — each declared
  `frame-root` plan mints (and, after the render, tears down) its OWN frame,
  seeded with its declared `:initial-events`/config. If a plan frame-id is
  already LIVE at render time, running the production ENSURE path would
  silently ADOPT that ambient frame (03 §8 / `frames` ns — create-if-absent
  ADOPTS a live frame, its config authoritative), so the plan's declared
  seed/config would be IGNORED, ambient state reused, and the assertion still
  pass. That is a test-isolation violation, not an adoption — reject BEFORE
  any frame/install mutation, naming the colliding frame(s) and root, rather
  than adopting via the production path. (Production adoption semantics are
  deliberately unchanged; this reject is the test HOST's contract, not a
  `frame-root` behaviour.)"
     [root-id live-collisions]
     (let [many? (next live-collisions)]
       (error/throw-error!
        :rf.error/ui-test-frame-collision 'rf.ui.test/render
        (str "plan-bearing ui.test/render for root " (pr-str root-id)
             " declares a frame-root plan" (when many? "s")
             " for the ALREADY-LIVE frame" (when many? "s") " "
             (pr-str (vec live-collisions)) " — a Tier-1 test render OWNS "
             "FRESH ISOLATED frames and must not adopt ambient frame state "
             "(the plan's declared :initial-events/config would be silently "
             "ignored, the pre-existing frame's state reused). Destroy the "
             "pre-existing frame(s) before rendering — a fresh test frame is "
             "created + seeded per plan — or target a frame you hold with a "
             "plan-free form + {:frame f}")
        {:recovery :isolate-the-test-frame
         :extra {:root-id     root-id
                 :collisions  (vec live-collisions)}}))))

;; ---------------------------------------------------------------------------
;; The Tier-1 atomic plan-frame claim registry (rf2-vxgfnd.64)
;; ---------------------------------------------------------------------------

#?(:clj
   ;; A plan-bearing render RESERVES its declared frame ids here — as one
   ;; all-or-nothing claim — before installing anything. Without a claim, two
   ;; renders racing on the same id could BOTH pass a bare liveness check and
   ;; then silently share one frame (the production ENSURE adopt path), and a
   ;; stale before/after id snapshot could let a concurrently-created frame be
   ;; adopted (and later destroyed) by a losing render. The claim closes both
   ;; windows. Keyed by the bare frame-id: the set of ids currently owned by an
   ;; in-flight render. Process-global; each render's `finally` releases its
   ;; OWN ids, so a completed or failed render never leaks a claim. This is the
   ;; test HOST's stronger ownership rule — production ENSURE is unchanged.
   (defonce ^:private claimed-plan-ids (atom #{})))

#?(:clj
   (defn- claim-plan-frames!
     "ATOMIC all-or-nothing acquisition of a plan-bearing render's declared
  frame ids (`wanted`, document order). Reserves EVERY id iff none is already
  claimed by another in-flight render AND none is LIVE in the frames registry;
  the liveness read and the reservation linearize through a single CAS on the
  claim set, so two renders racing on the same id cannot both win — one CAS
  succeeds and installs, the other sees the id claimed (or live) and loses.
  Returns nil on a successful claim (the ids are now this render's to install
  and tear down), or the document-order vector of colliding ids on failure —
  a fail-BEFORE-write: nothing is reserved, no frame is installed, no initial
  event drains."
     [wanted]
     (loop []
       (let [claimed @claimed-plan-ids
             busy    (filterv (fn [fid]
                                (or (contains? claimed fid)
                                    (some? (rframe/frame fid))))
                              wanted)]
         (cond
           (seq busy) busy
           (compare-and-set! claimed-plan-ids claimed (into claimed wanted)) nil
           :else (recur))))))

#?(:clj
   (defn- release-plan-frames!
     "Release this render's claim on `wanted` (the `finally` half of the
  acquisition — a completed or failed render leaves no claim behind)."
     [wanted]
     (swap! claimed-plan-ids #(reduce disj % wanted))
     nil))

#?(:clj
   (defn- render-plan-bearing
     "Runtime half of a PLAN-BEARING literal root form: install the root's
  static frame plans as FRESH ISOLATED test frames, bind the resolved ambient
  frame (the innermost top-region frame-root enclosing the mounted view) for the
  JVM structural render, and tear down every frame THIS render created in a
  `finally`. Plan config expressions evaluate exactly once, at the top, before
  any install or tree traversal.

  ATOMIC, REGISTRY-RESTED, INCARNATION-OWNED acquisition (rf2-vxgfnd.76,
  strengthening rf2-vxgfnd.64 / .55). Correctness rests on the AUTHORITATIVE core
  frame registry, not a parallel advisory atom:

    - CLAIM (render-vs-render fast path): the declared plan ids are reserved
      together in `claimed-plan-ids` (`claim-plan-frames!`). Two `ui.test`
      renders racing on the same id linearize through that single CAS — the
      loser sees the id claimed (or already live) and is REJECTED before any
      write, so render-vs-render losses are strictly ZERO-write.

    - INSTALL (EXCLUSIVE mode — the registry IS the linearization authority):
      each plan is installed by `make-frame` with `:rf.frame/must-create? true`,
      so the FRESH-frame contract rests on core's own guarded-CAS decide→install
      (frame.cljc `upsert-frame!`), NOT on the advisory claim atom. A raw actor
      that creates a wanted id in the window between the claim CAS and this
      install loses the guarded CAS inside `upsert-frame!` and throws typed
      `:rf.error/frame-id-taken`: adopt/refresh (production ENSURE dispositions)
      become COLLISIONS here — a plan NEVER silently adopts an ambient frame.

    - TEARDOWN (exact incarnation tokens ONLY, never a bare id): each installed
      frame's incarnation token is PINNED IMMEDIATELY after its own install, so
      the `finally` (which serves both success and partial-failure) destroys a
      frame ONLY while that EXACT pinned token is still the live one. A frame a
      later actor destroyed + re-created under the same id (a distinct token) —
      or the raw actor's colliding frame — is left UNTOUCHED. There is no bare-id
      teardown on any path.

  AC3 RELAXATION — a DOCUMENTED, NAMED RESIDUAL (rf2-vxgfnd.76 ruling): a
  raw-actor collision introduced on plan N is detected at N's install, AFTER
  plans 1..N-1 already installed and drained their (irreversible) `:initial-
  events`. So on such a collision this render tears down its 1..N-1 incarnations
  (exact tokens) and re-raises `:rf.error/frame-id-taken`, matching the
  executor's existing partial-failure posture — NOT a strict zero-initial-events
  rollback (which would require holding a lock across user construction code, a
  contention shape no real workload reaches). Render-vs-render losses remain
  strictly zero-write via the claim CAS above.

  Production `execute-frame-plans!` adoption/HMR semantics are deliberately
  unchanged; this stronger exclusive rule is the test HOST's, not a `frame-root`
  behaviour."
     [opts thunk root-id plans-thunk ambient-frame-id]
     (let [plans  (plans-thunk)
           wanted (mapv :frame-id plans)
           busy   (claim-plan-frames! wanted)]
       (when (seq busy)
         (reject-frame-collision! root-id busy))
       (let [run   (if (contains? opts :sub-overrides)
                     #(binding [reactive/*sub-overrides* (:sub-overrides opts)] (thunk))
                     thunk)
             ;; [frame-id token] pairs THIS render installed, in document order.
             ;; Each pair is conj'd IMMEDIATELY after its own must-create install
             ;; so a partial failure (a later plan collides / throws) tears down
             ;; exactly the frames already installed — and the exact-token guard
             ;; means a bare id is never destroyed. Serves both the success-path
             ;; and partial-failure teardown; there is no separate catch.
             owned (volatile! [])]
         (try
           ;; EXCLUSIVE install: every plan MUST create a fresh frame. The claim
           ;; proved every id absent; must-create rests correctness on the core
           ;; registry's guarded CAS — a raw-actor collision in the claim→install
           ;; window throws `:rf.error/frame-id-taken` here, and the `finally`
           ;; tears down the pinned prefix (AC3 residual documented above).
           (doseq [{:keys [frame-id config]} plans]
             (live-frame/make-frame (assoc (or config {}) :id frame-id
                                           :rf.frame/must-create? true))
             (vswap! owned conj [frame-id (rframe/frame-incarnation-token frame-id)]))
           ;; Establish the JVM render-slice memo scope around the plan-bearing
           ;; render thunk (rf2-vxgfnd.267) — the plan-bearing literal route's
           ;; Tier-1 boundary — so a single render shares ONE slice memo and a
           ;; later render recomputes. Re-entrant (a nested capture reuses it).
           (let [slice #(reactive/with-slice-memo run)
                 root  (if (some? ambient-frame-id)
                         (binding [rframe/*current-frame* ambient-frame-id] (slice))
                         (slice))]
             (assoc root :rf.ui/tree-version tree/tree-version))
           (finally
             ;; Incarnation-owned teardown: destroy each frame we installed ONLY
             ;; while its pinned incarnation is still the live one. A
             ;; destroy+recreate under the same id (a distinct token), or a
             ;; colliding actor's frame, survives. Never a bare id.
             (doseq [[fid t] @owned]
               (rframe/destroy-frame! fid t))
             (release-plan-frames! wanted)))))))

#?(:clj
   (defn ^:no-doc render-view*
     "Runtime half of `render` form 1 (bare view reference — never plan-bearing)."
     [view-fn opts]
     (validate-render-opts! opts true false)
     (render-with-opts (or opts {}) #(view-fn (get opts :props {})))))

#?(:clj
   (defn ^:no-doc render-form*
     "Runtime half of `render` form 2 (literal root form — the compiled
  template thunk). `plans-thunk` is nil for a plan-free form (frames combine
  with :frame/:app-db, today's behaviour) and the evaluate-at-preflight
  thunk when the root owns frames (a top-region frame-root); `root-id` and
  `ambient-frame-id` are the compile-resolved identity + innermost ambient
  frame the plan-bearing path binds."
     [thunk opts root-id plans-thunk ambient-frame-id]
     (validate-render-opts! opts false (some? plans-thunk))
     (if plans-thunk
       (render-plan-bearing (or opts {}) thunk root-id plans-thunk ambient-frame-id)
       (render-with-opts (or opts {}) thunk))))

#?(:clj
   (defn- render-view-reference-form
     "Expansion for form 1: a compile-resolved defview var/symbol."
     [menv vsym opts]
     (when (contains? menv vsym)
       (throw (env/compile-error
               :rf.ui.compile/bad-test-render-form
               (str "ui.test/render: " vsym " is a LOCAL binding — render "
                    "needs the compile-resolved defview var/symbol (or a "
                    "literal root form). Hiccup is compiled, not interpreted; "
                    "a runtime-chosen view cannot be a template")
               {:head vsym})))
     (let [e (env/make-env {:host :clj :ns-sym (ns-name *ns*)})
           r (env/resolve-sym e vsym)]
       (when-not (and r (:rf.ui/view (:meta r)))
         (throw (env/compile-error
                 :rf.ui.compile/bad-test-render-form
                 (str "ui.test/render: " vsym
                      (if r
                        " resolves but is not a defview"
                        " does not resolve")
                      " — render accepts exactly two forms: a defview "
                      "var/symbol, or a literal root form vector")
                 {:head vsym})))
       `(render-view* ~vsym ~opts))))

#?(:clj
   (defn- innermost-frame-root-id
     "The frame-id of the INNERMOST top-region `frame-root` enclosing the
  root form's single mounted view (nil when the view sits under no
  frame-root). The JVM emits `frame-root` transparently, so this collapses
  the client's nearest-ancestor React-context scope to the one ambient
  frame a Tier-1 headless render binds; a `frame-provider` in the subtree
  re-binds its own frame at emission and wins for its descendants."
     [ast]
     (let [result (volatile! nil)]
       (letfn [(walk [n enclosing]
                 (case (:op n)
                   :view       (vreset! result enclosing)
                   :frame-root (run! #(walk % (:frame-id n)) (:children n))
                   (:element :fragment :frame-provider)
                   (run! #(walk % enclosing) (:children n))
                   nil))]
         (walk ast nil))
       @result)))

#?(:clj
   (defn- emit-plans-thunk
     "Emit the evaluate-at-preflight plans thunk — `(fn [] [{:frame-id ..
  :config-fingerprint .. :config <expr>} ..])` in document order — mirroring
  `re-frame.ui.compiler.root`'s mount-side thunk so config EXPRESSIONS
  evaluate exactly when preflight runs. nil when the root form carries no
  plans."
     [plans]
     (when (seq plans)
       `(fn []
          [~@(map (fn [{:keys [frame-id config-fingerprint config]}]
                    `{:frame-id ~frame-id
                      :config-fingerprint ~config-fingerprint
                      :config ~config})
                  plans)]))))

#?(:clj
   (defn- render-literal-form
     "Expansion for form 2: a literal root form — the SAME root grammar
  `ui/mount` takes (`root/analyze-root`: the top-region scan collects the
  mounted view + static frame plans; conditional/list `frame-root` is a
  compile error there). One mounted view per root form is the invariant
  (root identity is the view's id). A plan-bearing form preflights fresh
  test frames and binds the ambient frame; a plan-free form combines with
  the explicit :frame/:app-db opts as before."
     [menv form opts]
     (let [e   (-> (env/make-env {:host :clj :ns-sym (ns-name *ns*)})
                   (env/with-locals (keys menv)))
           {:keys [ast views plans]} (root/analyze-root e 'rf.ui.test/render form)]
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
       `(render-form* (fn [] ~(emit-jvm/emit-node ast))
                      ~opts
                      ~(:view-id (first views))
                      ~(emit-plans-thunk plans)
                      ~(innermost-frame-root-id ast)))))

#?(:clj
   (defmacro render
     "`(render root-or-view opts?)` — run the real compiled view against a
  real frame on the JVM and return the versioned public STRUCTURAL TREE
  (the top node — a view boundary, or the top-region wrapper enclosing the
  one mounted view — stamped `:rf.ui/tree-version`).

  Accepted forms — exactly two (root-identity-and-mount §9):

    1. A VIEW REFERENCE — the compile-resolved defview var/symbol:
       `(render product-card {:props {:product p} :frame f})`.
       Props ride `{:props p}`; frames ride `{:frame f}` XOR
       `{:app-db v}` (a test frame is minted around the render and
       destroyed after). With neither, structural rendering proceeds
       frameless and any frame-scoped read raises honestly.

    2. A LITERAL ROOT FORM — the SAME root grammar `mount` takes
       (`root/analyze-root`): the top-region wrappers (element / fragment /
       `frame-root` / `frame-provider`) surrounding exactly ONE mounted
       view, e.g. `(render [product-card {:product p}] {:frame f})` or
       `(render [frame-root {:id :shop :initial-events [[:shop/boot]]}
                 [product-card {:product p}]])`.
       `{:props p}` is REJECTED (props live in the form). A PLAN-BEARING
       root form (a top-region `frame-root`) OWNS its frames: its static
       plans preflight FRESH test frames (the S2c ENSURE executor) before
       the structural render, the mounted view resolves the innermost
       enclosing `frame-root`'s frame as its ambient scope, and every
       test-owned frame is torn down after. `{:frame …}`/`{:app-db …}`
       alongside a plan-bearing form is rejected ('the root form owns its
       frames' — §9 [S1-CONFIRM]); with plan-free forms they combine.

  A runtime-assembled vector is the same compile error as at `mount` —
  hiccup is compiled, not interpreted. `{:sub-overrides {query value}}`
  combines with both forms (the explicit JVM override door; consumed by
  the S2 read slice). Registrations come from the loaded namespaces.

  Tier-1 renders the JVM structural subset: no effects, no host ops;
  `sub` is the one-shot headless read (03 §3). CLJS has no structural
  trees (the client emitter targets React directly) — expanding this
  macro in a CLJS compile is a didactic compile error."
     ([root-or-view] `(render ~root-or-view nil))
     ([root-or-view opts]
      (when (some? (:ns &env))
        (throw (env/compile-error
                :rf.ui.compile/ui-test-jvm-only
                (str "ui.test/render is the Tier-1 JVM structural render — "
                     "CLJS builds have no structural trees (the client "
                     "emitter targets React directly). Tier-1 tests are "
                     ".clj/.cljc-on-JVM; mounted CLJS tests arrive with the "
                     "Tier-3 surface (with-root, S1c/S2)")
                nil)))
      (cond
        (vector? root-or-view)
        (render-literal-form &env root-or-view opts)

        (symbol? root-or-view)
        (render-view-reference-form &env root-or-view opts)

        (and (seq? root-or-view)
             (= 'var (first root-or-view))
             (symbol? (second root-or-view)))
        (render-view-reference-form &env (second root-or-view) opts)

        :else
        (throw (env/compile-error
                :rf.ui.compile/bad-test-render-form
                (str "ui.test/render accepts exactly two forms — a defview "
                     "var/symbol, or a LITERAL root form vector (hiccup is "
                     "compiled, not interpreted; a runtime-assembled value "
                     "cannot be a template); got " (pr-str root-or-view))
                {:form root-or-view}))))))
