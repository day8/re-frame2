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
  Tier-3 mounting (`with-root` / `simulate!`, and `flush!`'s React-`act`
  half) lands with the mount/reactivity slices (S1c/S2) — `query` has no
  live behaviour yet; `flush!`'s host-agnostic epoch-drain half is landed
  (S2d, the global spelling of the Q51 flush scope ruling).

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
            [re-frame.ui.frames :as frames]
            [re-frame.ui.reactive :as reactive]
            #?@(:clj [[re-frame.ui.compiler.emit-jvm :as emit-jvm]
                      [re-frame.ui.compiler.env :as env]
                      [re-frame.ui.compiler.root :as root]
                      [re-frame.ui.tree :as tree]])))

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
;; query — Tier 3 by contrast (no live behaviour until the mount slice)
;; ---------------------------------------------------------------------------

(defn query
  "`(query root css-selector)` — the Tier-3 LIVE-DOM counterpart of
  `find`: a MOUNTED root (`with-root`) + a native CSS selector string,
  answered by the host DOM's querySelector. It shares nothing with the
  Tier-1 selector grammar — no rf= matching, no structural projections,
  no view-id selectors: CSS is the whole contract.

  Tier-3 mounting lands with the mount/reactivity slices (S1c/S2), so
  `query` has no live behaviour yet — every call raises the typed tier
  error. It exists now so the tier split is enforced from day one:
  a structural tree handed here points back at `find`."
  [root _css-selector]
  (if (map? root)
    (tier-mismatch!
     'rf.ui.test/query
     (str "a structural Tier-1 tree was handed to ui.test/query — query is "
          "the Tier-3 live-DOM counterpart (mounted root + native CSS). "
          "Structural trees query with ui.test/find / find-all and the "
          "closed selector grammar (tag keyword, view id / defview var, "
          "attr map, pred fn)")
     {:got root :other-tier 'rf.ui.test/find})
    (tier-mismatch!
     'rf.ui.test/query
     #?(:clj  (str "no mounted roots exist on the JVM — Tier-1 is the "
                   "headless structural surface (ui.test/render + find); "
                   "Tier-3 mounted tests (with-root + query) run on "
                   "browser/jsdom CI and land with the mount slice")
        :cljs (str "Tier-3 mounted queries land with the mount slice "
                   "(with-root/flush!) — until then there is no mounted "
                   "root for query to read"))
     {:got root})))

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

(defn flush!
  "`(flush!)` — the TEST-ONLY GLOBAL all-roots flush (07 §2's single flush
  idiom): synchronously drain EVERY pending ViewCell notification, closing
  the framework epoch so pending sub deltas advance their cells' revisions
  before the test's next assertion.

  This is the GLOBAL spelling of the Q51 scope ruling (03 §3; recorded in
  `re-frame.ui.reactive`): app code forces PER-ROOT (`ui/flush!`, S2f) or
  per-frame (`reactive/flush-frame!`); only the test surface flushes every
  root at once. Returns nil.

  Scope note — the epoch-drain half is host-agnostic and lands here; the
  `act()`/`flushSync` wrapping that also settles React's own commit for a
  MOUNTED Tier-3 root (`with-root`) rides the mount slice, since no mounted
  root exists to settle until then. On the JVM Tier-1 surface renders are
  one-shot (no mounted cells), so a `flush!` there drains an empty registry
  — honest, not a stub."
  []
  (reactive/flush-pending!)
  nil)

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
     (let [run  (if (contains? opts :sub-overrides)
                  #(binding [reactive/*sub-overrides* (:sub-overrides opts)] (thunk))
                  thunk)
           root (cond
                  (contains? opts :frame)
                  (binding [rframe/*current-frame*
                            (rframe/frame-target->id (:frame opts))]
                    (run))

                  (contains? opts :app-db)
                  (let [id (rframe/frame-target->id
                            (frame {:app-db (:app-db opts)}))]
                    (try
                      (binding [rframe/*current-frame* id] (run))
                      (finally (rframe/destroy-frame! id))))

                  :else (run))]
       (assoc root :rf.ui/tree-version tree/tree-version))))

#?(:clj
   (defn- render-plan-bearing
     "Runtime half of a PLAN-BEARING literal root form: run the root's static
  frame plans through the S2c preflight ENSURE against FRESH test frames
  (`re-frame.ui.frames/execute-frame-plans!` — the same executor `ui/mount`
  drives), bind the resolved ambient frame (the innermost top-region
  frame-root enclosing the mounted view) for the JVM structural render, and
  tear down EVERY test-owned frame (those this render created) in a
  `finally` — a preflight failure leaves no frame residue and preserves the
  typed error. Plan config expressions evaluate exactly once, here at
  preflight, before any tree traversal."
     [opts thunk root-id plans-thunk ambient-frame-id]
     (let [plans  (plans-thunk)
           before (set (keys @rframe/frames))
           run    (if (contains? opts :sub-overrides)
                    #(binding [reactive/*sub-overrides* (:sub-overrides opts)] (thunk))
                    thunk)]
       (try
         (frames/execute-frame-plans! root-id plans)
         (let [root (if (some? ambient-frame-id)
                      (binding [rframe/*current-frame* ambient-frame-id] (run))
                      (run))]
           (assoc root :rf.ui/tree-version tree/tree-version))
         (finally
           ;; test-owned = the plan frames not already live before preflight
           ;; (siblings installed before a failing plan are torn down too —
           ;; the Tier-1 no-residue guarantee, not production's irreversible-
           ;; fx posture).
           (doseq [fid (remove before (map :frame-id plans))]
             (rframe/destroy-frame! fid)))))))

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
