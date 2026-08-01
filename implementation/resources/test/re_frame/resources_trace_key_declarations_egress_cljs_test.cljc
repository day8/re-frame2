(ns re-frame.resources-trace-key-declarations-egress-cljs-test
  "A `:serialize` owner's per-slot `:params` / `:scope` declaration is honoured
  INSIDE `:resource/key` at trace / tool egress (rf2-dl7bz).

  ## The leak this suite pins

  A resource declaring

    (rf/reg-resource :account/summary
      {:sensitive [[:params :account-id]] …} …)

  and NO coarse `:sensitive?` root prop classifies `:serialize`. The trace /
  tool key projection read only that coarse disposition
  (`ssr/project-scoped-key`), so the declared `:account-id` rode RAW inside
  `:resource/key` on every `:rf.resource/*` / `:rf.mutation/*` row, inside every
  scoped-keys vector slot, inside every `[:rf.work/resource <key> <gen>]`
  work-id, and inside every fx carrier (`:rf.fx/args` / `:rf.event/fx`) the key
  reaches — off-box, epoch, MCP. Meanwhile the SAME bytes in the durable entry
  redact, because `reconcile-registry` lowered the declaration to
  `[… :resource/key 2 :account-id]` and the SSR wire key walks it
  (`classification/project-entry-params`). One value, two carriers, one rule
  applied: the rf2-irwsq shape.

  The artefact ALREADY DOCUMENTED the closed behaviour before it existed —
  `tooling.cljc` claimed `:serialize` \"applies the resource's per-slot
  `:params` projection-relative declarations\" and \"projects per-slot
  `:params-schema` marks\", and `ssr/project-scoped-key` took a `spec` argument
  it named `_spec`. §1 below is the standing statement that the prose is now
  true of the code, and §5 is the standing statement that it was made true
  WITHOUT widening `project-scoped-key`, whose `:serialize` deferral is
  deliberate (the SSR durable path resolves the same declaration from the
  per-frame elision REGISTRY, which a frameless trace boundary cannot read).

  ## No over-redaction — every assertion here has a two-sided control

  §2 pins the negative side: a plain owner's key rides BYTE-IDENTICAL, the
  UNDECLARED sibling param stays readable (a tool must still see `:page 3`),
  `:rf.scope/global` is untouched, and the resource-id survives at position 1
  so every per-key join a tool makes still lands. A change that
  over-redacts fails this suite as loudly as the leak did.

  ## Build posture

  This whole surface is DEV-ONLY. Both callers of the projection sit behind
  `interop/debug-enabled?` or bundle isolation: the off-box trace projector is
  reached through the epoch tool-pair, and every trace emit (`trace/emit!` AND
  the `:rf.error/*` `trace/emit-error!`) is gated on `interop/debug-enabled?`,
  which folds to `false` under `:advanced` + `goog.DEBUG=false` / a JVM
  `-Dre-frame.debug=false`; the tool-egress caller lives in the
  bundle-isolated `re-frame.resources.tooling` sibling. This is therefore a
  DEV / tool-console guarantee, not an always-on production one — the always-on
  `:rf.observe/*` axis carries no resource scoped key, and nothing here changes
  that.

  Dual-target (`.cljc` + `_cljs_test`): the JVM runner picks it up via the
  `.*-test$` ns regex, Shadow's `:node-test` build via the `cljs-test$` regex.
  The browser / MCP off-box channel is where this matters most, so the
  guarantee is asserted on BOTH hosts."
  (:require
   #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
      :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
   [clojure.string :as str]
   [re-frame.core :as rf]
   [re-frame.frame :as frame]
   ;; load-bearing side-effecting require: the façade registers the
   ;; :rf.resource/* events + subs + the :resource registrar kind, and
   ;; publishes the trace-egress hooks the epoch tool-pair consults.
   [re-frame.resources]
   [re-frame.resources.classification :as classification]
   [re-frame.resources.registry :as registry]
   [re-frame.resources.ssr :as ssr]
   [re-frame.resources.state :as state]
   [re-frame.resources.tooling :as resources-tooling]
   [re-frame.resources.trace-egress :as trace-egress]
   [re-frame.resources.work-ledger :as work-ledger]
   ;; production HTTP fx surface (so the transport feature probe resolves).
   [re-frame.http.managed]
   [re-frame.schemas]
   [re-frame.test-support :as core-test-support]
   #?@(:clj  [[re-frame.substrate.plain-atom :as plain-atom]]
       :cljs [[re-frame.adapter.reagent :as reagent-adapter]])))

(def ^:private account-secret "acct-SECRET-4417")
(def ^:private tenant-secret "tenant-SECRET-99")
(def ^:private avatar-blob "avatar-bytes-XXXXXXXX")

;; ---- fixture --------------------------------------------------------------

(defn- init!
  "Four owners spanning the grains this suite discriminates:

    :account/summary — `:serialize` (NO coarse prop) + a per-slot
                       `[:params :account-id]` declaration. The subject.
    :plain/summary   — declares NOTHING. The byte-identity control.
    :tenant/report   — a `[:scope :tenant-id]` declaration, to prove a
                       `:scope`-rooted path reaches the key's index-0 component
                       through the `[tier {identity}]` tuple.
    :sealed/summary  — the COARSE `:sensitive?` owner, to prove the two arms
                       compose by grain rather than overlap (the coarse arm's
                       whole-component digests are untouched)."
  []
  (rf/make-frame {:id :rf/default :url-bound? true
                  :doc "per-slot key-declaration trace-egress suite frame."})
  (rf/reg-resource :account/summary
    {:scope         :rf.scope/global
     :sensitive     [[:params :account-id]]
     :params-schema [:map [:account-id :string] [:page :int]]}
    (fn [_p _ctx] {:request {:method :get :url "/account"}}))
  (rf/reg-resource :plain/summary
    {:scope         :rf.scope/global
     :params-schema [:map [:account-id :string] [:page :int]]}
    (fn [_p _ctx] {:request {:method :get :url "/plain"}}))
  (rf/reg-resource :tenant/report
    {:scope         :rf.scope/global
     :sensitive     [[:scope :tenant-id]]
     :large         [[:params :avatar]]
     :params-schema [:map [:avatar :string] [:page :int]]}
    (fn [_p _ctx] {:request {:method :get :url "/tenant"}}))
  (rf/reg-resource :sealed/summary
    {:scope         :rf.scope/global
     :sensitive?    true
     :sensitive     [[:params :account-id]]
     :params-schema [:map [:account-id :string] [:page :int]]}
    (fn [_p _ctx] {:request {:method :get :url "/sealed"}})))

(use-fixtures :each
  (core-test-support/make-reset-runtime-fixture
    {:adapter #?(:clj plain-atom/adapter :cljs reagent-adapter/adapter)
     :init-fn init!}))

;; ---- helpers --------------------------------------------------------------

(def ^:private declared-params {:account-id account-secret :page 3})

(defn- key-for [resource-id]
  (state/scoped-resource-key :rf.scope/global resource-id declared-params))

(defn- tenant-key []
  (state/scoped-resource-key [:rf.scope/session {:tenant-id tenant-secret :region "au"}]
                             :tenant/report
                             {:avatar avatar-blob :page 3}))

(defn- project-row
  "Project `tags` for OFF-BOX egress exactly as the epoch tool-pair does —
  through the late-bound `:resources/project-resource-trace-egress` hook's body,
  against the row's own `:rf.frame/id`."
  [tags]
  (trace-egress/project-resource-trace-egress tags (:rf.frame/id tags)))

(defn- projected-key
  "The single `:resource/key` slot of a one-key row, projected off-box."
  [scoped-key]
  (:resource/key (project-row {:rf.frame/id :rf/default :resource/key scoped-key})))

(defn- leaks?
  "Whether `secret` survives ANYWHERE in `v` — the plaintext test."
  [secret v]
  (str/includes? (pr-str v) secret))

(defn- install-entry!
  "Write a durable `:loaded` entry for `scoped-key` into the frame's runtime-db
  AND reconcile the per-frame elision registry, so the SSR durable projection
  (`project-entry-params`) has the lowered declaration to read — the same two
  steps a real resource commit folds into one transition. Returns the key."
  [frame-id scoped-key]
  (let [resource-id (second scoped-key)]
    (frame/swap-runtime-db!
      frame-id
      (fn [rdb]
        (-> (or rdb {})
            (assoc-in (state/entry-path scoped-key)
                      (assoc (state/empty-entry resource-id scoped-key)
                             :status :loaded
                             :data   {:total 1}))
            (classification/reconcile-registry registry/resource-meta)))))
  scoped-key)

(defn- ssr-wire-key
  "The SSR DURABLE wire key the registry-driven projection produced for
  `scoped-key`'s entry — the counterpart this suite must agree with byte for
  byte.

  Read from `projection-metadata`'s `:projected-key` rather than from the wire
  row, because a `:serialize` entry re-keyed by its own per-slot declaration is
  WITHHELD from the wire (rf2-rjq9d — an unaddressable row hydrates as an
  ownerless duplicate nothing can reach or collect). The projection itself is
  untouched: `classification/project-entry-params` still runs and still produces
  this key, so the agreement asserted below is the same agreement, read off the
  carrier that survives."
  [frame-id scoped-key]
  (let [rdb (frame/frame-runtime-db-value frame-id)]
    (some (fn [m]
            (when (= (second (:resource/key m)) (second scoped-key))
              (:projected-key m)))
          (ssr/projection-metadata frame-id 5000 (get-in rdb (state/entries-path))))))

;; ===========================================================================
;; 1. THE LEAK. A `:serialize` owner's declared params slot must not ride raw
;;    inside `:resource/key` on the trace path.
;;
;;    This is the assertion `epoch_egress_resource_trace_test.clj` §(rf2-ko5lm)
;;    named in prose and deliberately did not make.
;; ===========================================================================

(deftest serialize-owner-declared-param-does-not-ride-raw-in-the-key
  (testing "rf2-dl7bz — a resource declaring {:sensitive [[:params :account-id]]}
            with NO coarse :sensitive? prop classifies :serialize, and its
            DECLARED param must be substituted inside the projected
            :resource/key rather than riding verbatim"
    (let [k    (key-for :account/summary)
          tags (project-row {:rf.frame/id :rf/default :resource/key k})
          pk   (:resource/key tags)]
      (is (leaks? account-secret k)
          "premise: the RAW key carries the declared account-id in the clear")
      (is (not (leaks? account-secret pk))
          (str "the declared [:params :account-id] MUST NOT survive in the "
               "projected :resource/key — got " (pr-str pk)))
      (is (= :rf/redacted (get-in pk [2 :account-id]))
          "the declared slot carries the redaction sentinel, in place")
      (is (true? (:sensitive? tags))
          "a row whose key redacted is stamped :sensitive?"))))

(deftest declared-param-is-closed-on-every-carrier-of-the-key
  (testing "rf2-dl7bz — :resource/key is the family's UNIVERSAL carrier: the
            same declaration must close the key inside a scoped-keys VECTOR
            slot, inside a work-id, and inside the fx carriers a foreign row
            stamps"
    (let [k       (key-for :account/summary)
          work-id (work-ledger/resource-work-id k 1)
          rows    {:rf.frame/id :rf/default
                   :matched     [k]
                   :work/id     work-id
                   ;; a slot NOBODY has enumerated, reached by the shape-driven
                   ;; default (rf2-wd9im) rather than by the slot roster.
                   :unnamed     [[k]]}
          proj    (project-row rows)]
      (is (not (leaks? account-secret proj))
          (str "no carrier on the row may keep the declared param raw — got "
               (pr-str proj)))
      (is (= :rf/redacted (get-in proj [:matched 0 2 :account-id]))
          "the scoped-keys vector slot honours the declaration")
      (is (= :rf/redacted (get-in proj [:work/id 1 2 :account-id]))
          "the key EMBEDDED at position 1 of a work-id honours it")
      (is (= :rf/redacted (get-in proj [:unnamed 0 0 2 :account-id]))
          "the SHAPE-driven default (an unnamed slot) honours it too"))
    (testing "…including the FX carriers of a row the family does not own"
      (let [k    (key-for :account/summary)
            tags {:rf.frame/id :rf/default
                  :rf.fx/args  {:request-id [:rf.req :rf/default
                                             [:rf.work/resource k 1]]}}
            out  (trace-egress/project-fx-args-egress tags :rf/default)]
        (is (not (leaks? account-secret out))
            (str "an ensure's fx carrier must honour the declaration — got "
                 (pr-str out)))
        (is (true? (:sensitive? out)) "and the fx row is stamped")))))

;; ===========================================================================
;; 2. THE TWO-SIDED CONTROL. Over-redaction is as wrong as under-redaction.
;; ===========================================================================

(deftest plain-owners-key-rides-byte-identical
  (testing "a resource declaring NOTHING has its key ride verbatim — same value
            AND same CEDN-1 bytes (no walk, so no list↔vector collapse)"
    (let [k  (key-for :plain/summary)
          pk (projected-key k)]
      (is (= k pk) "a plain owner's key is unchanged")
      (is (= (state/key-id k) (state/key-id pk))
          "…byte-identical, so the cache-key-identity round-trip is intact")
      (is (leaks? account-secret pk)
          "an undeclared param is app data and stays readable to a tool"))))

(deftest kind-preserving-when-nothing-is-declared
  (testing "rf2-wgutc2 — the walker reconstructs collections, so an UNNECESSARY
            walk would collapse a list-valued param to a vector and change the
            key's bytes. The declaration-existence gate is what stops it"
    (let [k  (state/scoped-resource-key :rf.scope/global :plain/summary
                                        {:account-id "a" :page 3 :ids '(1 2 3)})
          pk (projected-key k)]
      (is (= (pr-str k) (pr-str pk))
          "an undeclared owner's key is byte-for-byte the same, list kind included")
      (is (list? (get-in pk [2 :ids]))
          "the list stays a list"))))

(deftest undeclared-siblings-and-structure-survive-the-substitution
  (testing "the substitution is IN PLACE: only the declared slot changes. The
            undeclared sibling, the scope, and the resource-id all survive —
            which is what keeps a tool's per-key joins and attribution working"
    (let [k  (key-for :account/summary)
          pk (projected-key k)]
      (is (= :rf.scope/global (nth pk 0))
          ":rf.scope/global is untouched")
      (is (= :account/summary (nth pk 1))
          "the resource-id survives at position 1 (attribution)")
      (is (= 3 (get-in pk [2 :page]))
          "the UNDECLARED sibling param stays readable")
      (is (= #{:account-id :page} (set (keys (nth pk 2))))
          "no param slot is added or dropped — the params key set is closed"))))

(deftest an-undeclared-row-is-not-stamped-sensitive
  (testing "stamp-precision: a plain owner's row carries no :sensitive? stamp,
            so the flag keeps meaning 'something on this row redacted'"
    (let [tags (project-row {:rf.frame/id :rf/default
                             :resource/key (key-for :plain/summary)})]
      (is (nil? (:sensitive? tags))))))

(defn- read-reply
  "The continuation reply map `events/read-continuation-reply` builds, trimmed
  to the slots this suite reads."
  [scoped-key value]
  (let [[scope resource-id params] scoped-key]
    {:status               :ok
     :value                value
     :rf.reply/work-id     [:rf.work/resource scoped-key 1]
     :rf.reply/work-kind   :resource
     :rf.reply/work-status :completed
     :resource             resource-id
     :params               params
     :scope                scope
     :resource/key         scoped-key}))

(deftest a-declared-key-must-not-widen-the-reply-to-a-coarse-redaction
  (testing "rf2-dl7bz — the row's :sensitive? STAMP and the owner's COARSE claim
            are two readings, and `row-owner-redacts?` must take the coarse one.

            A `:params` declaration substituting inside the key makes the KEY
            redact, which the stamp must report. It says NOTHING about the free
            cursor or the reply body, which no declaration names. Conflating the
            two tokenized a declaration-only owner's whole :value / :params —
            destroying exactly the undeclared siblings rf2-ko5lm's grain
            argument exists to keep readable"
    (let [k     (key-for :account/summary)
          reply (read-reply k {:ok true})
          tags  {:rf.frame/id :rf/default :rf.fx/args reply}
          out   (:rf.fx/args (trace-egress/project-fx-args-egress tags :rf/default))]
      (is (= :rf/redacted (:account-id (:params out)))
          "the reply's own :params copy still redacts through its declaration")
      (is (= 3 (:page (:params out)))
          "…and its UNDECLARED sibling still rides — not swallowed by a token")
      (is (= {:ok true} (:value out))
          "the body is untouched: this owner declares nothing under :data, and
           its :params declaration must not promote the row to a coarse claim")
      (is (= :rf/redacted (get-in out [:resource/key 2 :account-id]))
          "while the sibling KEY carrier redacts the same declared slot"))))

;; ===========================================================================
;; 3. A `:scope`-rooted declaration reaches the key's index-0 component.
;; ===========================================================================

(deftest scope-rooted-declaration-is-honoured-in-the-key
  (testing "rf2-dl7bz — `split-projection-paths` routes a `:scope`-rooted path
            into the scoped-key bucket, so [:scope :tenant-id] must redact the
            key's SCOPE component, reaching through the [tier {identity}] tuple
            the way a projection-relative declaration always reads"
    (let [k  (tenant-key)
          pk (projected-key k)]
      (is (leaks? tenant-secret k) "premise: the raw key carries the tenant id")
      (is (not (leaks? tenant-secret pk))
          (str "the declared [:scope :tenant-id] must not survive — got " (pr-str pk)))
      (is (= :rf.scope/session (get-in pk [0 0]))
          "the scope TIER keyword survives (attribution)")
      (is (= "au" (get-in pk [0 1 :region]))
          "an undeclared sibling of the scope identity stays readable"))
    (testing "…and a :large declaration on the same spec elides its own slot"
      (let [pk (projected-key (tenant-key))]
        (is (not (leaks? avatar-blob pk))
            "the [:params :avatar] :large declaration elides in the key too")
        (is (= 3 (get-in pk [2 :page]))
            "its undeclared sibling still rides")))))

;; ===========================================================================
;; 4. AGREEMENT WITH THE SSR DURABLE WIRE KEY (the bead's Q1).
;;
;;    This is what stops a fourth answer appearing later: the trace key, the
;;    tool key and the SSR wire key are ONE value derived two ways.
;; ===========================================================================

(deftest trace-key-agrees-with-the-ssr-durable-wire-key
  (testing "rf2-dl7bz Q1 — the spec-derived per-slot projection is BYTE-EQUAL
            to the registry-driven `classification/project-entry-params` the SSR
            durable path runs. Two derivations, one answer"
    (let [k        (install-entry! :rf/default (key-for :account/summary))
          wire     (ssr-wire-key :rf/default k)
          trace    (projected-key k)]
      (is (some? wire) "the SSR projection produced a wire key for the entry")
      (is (= :rf/redacted (get-in wire [2 :account-id]))
          "premise: the SSR durable path already honours the declaration")
      (is (= (pr-str (nth wire 2)) (pr-str (nth trace 2)))
          (str "the trace key's params component must be BYTE-equal to the SSR "
               "wire key's — wire " (pr-str (nth wire 2))
               " vs trace " (pr-str (nth trace 2)))))))

(deftest projection-is-idempotent
  (testing "rf2-dl7bz Q2 — re-projecting an already-projected key substitutes
            the same sentinel at the same path, so a doubly-projected row and a
            singly-projected one agree"
    (let [k (key-for :account/summary)]
      (is (= (projected-key k) (projected-key (projected-key k)))))))

;; ===========================================================================
;; 5. THE DEFERRAL IS INTACT. `project-scoped-key` was NOT widened.
;; ===========================================================================

(deftest project-scoped-key-still-defers-on-serialize
  (testing "rf2-dl7bz ruling — the per-slot arm lives at the trace / tool
            boundary, NOT inside `ssr/project-scoped-key`, whose `:serialize`
            deferral is deliberate (the SSR durable path resolves the same
            declaration from the per-frame elision registry). This test reds if
            anyone moves the arm into the shared projection"
    (let [k    (key-for :account/summary)
          spec (registry/resource-meta :account/summary)]
      (is (= k (ssr/project-scoped-key k :serialize spec))
          "`:serialize` still rides VERBATIM through `project-scoped-key`")
      (is (= (ssr/project-scoped-key k :serialize spec)
             (ssr/project-scoped-key k :serialize nil))
          "…and it still ignores the spec argument, exactly as documented"))))

;; ===========================================================================
;; 6. THE COARSE ARM IS UNCHANGED — the two arms compose by grain.
;; ===========================================================================

(deftest coarse-owner-still-tokenizes-the-whole-component
  (testing "a COARSE :sensitive? owner's key still redacts to the opaque
            content-addressed tokens, subsuming the per-slot surface — the
            declaration arm must not change what the coarse arm produces"
    (let [k    (key-for :sealed/summary)
          tags (project-row {:rf.frame/id :rf/default :resource/key k})
          pk   (:resource/key tags)]
      (is (contains? (nth pk 2) :rf/redacted)
          "the WHOLE params component is one opaque token")
      (is (contains? (nth pk 0) :rf/redacted)
          "…and so is the whole scope component")
      (is (= :sealed/summary (nth pk 1)) "the resource-id survives")
      (is (true? (:sensitive? tags)))
      (is (= pk (ssr/project-scoped-key k :redact nil))
          "byte-for-byte what `project-scoped-key` alone produced before"))))

(deftest unregistered-owner-still-fails-closed
  (testing "the nil-spec fail-closed arm is untouched by the declaration arm"
    (let [k    (state/scoped-resource-key :rf.scope/global :never/registered
                                          {:account-id account-secret})
          tags (project-row {:rf.frame/id :rf/default :resource/key k})]
      (is (not (leaks? account-secret (:resource/key tags))))
      (is (true? (:sensitive? tags))))))

;; ===========================================================================
;; 7. THE TOOL BOUNDARY ANSWERS IDENTICALLY.
;;
;;    `tooling.cljc` documented this behaviour before it existed. The live
;;    algebra view is where that prose is cashed out.
;; ===========================================================================

(deftest tool-egress-honours-the-same-declaration
  (testing "rf2-dl7bz — the EP-0015 tool-egress projection opts into the SAME
            helper, so a tool's node identity carries no declared param either.
            Before this, `project-key-for-egress`'s docstring claimed `:serialize`
            projected per-slot marks and the code did nothing"
    (let [k    (install-entry! :rf/default (key-for :account/summary))
          view (resources-tooling/resource-cache-algebra-view :rf/default)
          node (first (vals view))]
      (is (some? node) "the live entry projects to a node")
      (is (not (leaks? account-secret node))
          (str "no identity position on the node may carry the declared param — got "
               (pr-str node)))
      (is (= :rf/redacted (get-in (:id node) [2 :account-id]))
          "the node :id is the declaration-projected key")
      (is (= 3 (get-in (:id node) [2 :page]))
          "…and the undeclared sibling still rides, so joins survive")
      (testing "the DURABLE cache key is untouched — this is an egress-only projection"
        (let [rdb (frame/frame-runtime-db-value :rf/default)]
          (is (= k (:resource/key (get-in rdb (state/entry-path k))))
              "the entry still stores the raw scoped key"))))))
