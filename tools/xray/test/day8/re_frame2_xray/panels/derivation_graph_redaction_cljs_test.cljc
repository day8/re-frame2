(ns day8.re-frame2-xray.panels.derivation-graph-redaction-cljs-test
  "The POSITIVE off-box egress redaction test for the Derivation-Graph panel
  (rf2-yjarv6 — the EP-0014 testing-coverage gap the audit rf2-4j6w6v flagged
  as the missing arm).

  ## The gap this closes

  spec/Derivations.md §Conformance 'Tool redaction' + §Redaction metadata
  require: graph inspection can summarize or redact sensitive params,
  scopes, and values WITHOUT losing graph STRUCTURE — a redacted param is
  still an edge — composing through the single shared `rf/elide-wire-value`
  walker. Before this test the only related assertion was a weak NEGATIVE
  ('the snapshot :value is NOT inlined'). There was NO positive test that
  (a) a sensitive value is run through `rf/elide-wire-value` AND (b) the
  corresponding edge / node STRUCTURE survives.

  ## Where the redaction lives (the tail-2 ruling)

  Per the EP-0014 tail-2 correctness ruling (rf2-6y7wnb): raw params/values
  on the live algebra views are NOT a defect — they are correct-as-designed
  raw-on-box projections. Redaction is an EGRESS concern owned by the FRAME
  elision policy via the shared `rf/elide-wire-value` walker (per-frame,
  fail-closed), applied at the wire boundary where a consuming tool ships
  the graph OFF-BOX — NOT at the registrar-derived composer. The named first
  consumer Xray is where that egress call site is BORN
  (`derivation-graph-helpers/redact-graph-for-egress`), so the
  redact-without-losing-structure test belongs HERE alongside the consumer
  wiring (rf2-9ett2d).

  ## What's asserted

    1. **POSITIVE redact-value-keep-edge** — a node carries a sensitive
       value at a frame-declared sensitive path; egress projection runs it
       through the frame's `rf/elide-wire-value`; the value is replaced by
       `:rf/redacted` WHILE the node remains present + classified AND the
       edge that names it survives intact (structure preserved).
    2. **per-frame** — egress redacts under the OBSERVED frame's policy,
       not an ambient or borrowed one; a non-sensitive value rides through.
    3. **fail-closed** — egress for an unknown / nil / frameless target
       redacts the whole value rather than ship it raw under no policy — and
       must NOT borrow an AMBIENT dynamically-bound frame (rf2-udkj69): a nil
       frame-id under an ambient binding still redacts, never ships raw.
    4. **large elision** — a frame-declared `:large` value is replaced by
       the `:rf.size/large-elided` marker, again keeping structure."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.frame-classification :as frame-class]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]
            [day8.re-frame2-xray.panels.derivation-graph-helpers :as h]))

;; ---------------------------------------------------------------------------
;; Runtime fixture — reset the registry / frame state, install the plain-atom
;; substrate (a non-React JVM-and-node substrate), then register two frames
;; with declared elision policy:
;;
;;   :app/secure   — declares the path [:current :params :token] sensitive
;;                   (a route :params slug carrying a session token) and
;;                   [:cart :items] large.
;;   :app/plain    — no classification (every value egresses verbatim, modulo
;;                   the runtime size threshold which we leave at default).
;; ---------------------------------------------------------------------------

(def secure-frame :app/secure)
(def plain-frame  :app/plain)

(defn- install-policy! []
  ;; Frame-owned classification, the EP-0015 §8 successor to the removed
  ;; schema→elision route — install :sensitive / :large :app-db declarations
  ;; directly through the frame-classification path (same seam reg-frame uses).
  (frame-class/install!
    secure-frame
    (frame-class/validate+extract
      secure-frame
      {:sensitive {:app-db [[:current :params :token]]}
       :large     {:app-db [[:cart :items]]}})))

(defn- init-fn []
  (rf/reg-frame plain-frame {})
  (rf/reg-frame secure-frame {})
  (install-policy!))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter plain-atom/adapter
     ;; OPT OUT of the default `:rf/default` ambient scope: the fail-closed
     ;; arm asserts that egress against an unreachable frame redacts because
     ;; NO frame is resolvable. A bound ambient `:rf/default` would let the
     ;; frameless walk resolve to it (empty-policy identity) and ship raw —
     ;; masking the fail-closed contract. Our frames carry no `:initial-events`
     ;; work, so a clear ambient scope is safe.
     :ambient-frame nil
     :init-fn init-fn}))

;; ---------------------------------------------------------------------------
;; A live-shaped derivation graph fixture: a route-fact node whose live
;; `:params` carry a SENSITIVE token at the frame-declared sensitive path,
;; plus the edge that names it. This mirrors the live route-slice node shape
;; (`re-frame.routing.tooling/route-slice-algebra-view` surfaces raw
;; `:params` / `:query` value summaries — the exact case the gap names).
;; ---------------------------------------------------------------------------

(def ^:private route-fact-node
  {:id :rf/route :kind :process :refinement :route-fact :rf/family :routes
   :route-id :route/article
   :params {:current {:params {:token "secret-session-jwt-abc123"
                               :slug "welcome"}}}
   :query {:ref "home"}
   :output [:runtime [:rf.runtime/routing :current]]
   :storage :runtime-db :evaluation :on-route :lifecycle :frame
   :nav-token 17})

(def ^:private resource-node
  ;; the resource the route activates — the :param edge target; its node body
  ;; carries no value-bearing summary field, only structure.
  {:id :article/by-slug :kind :process :refinement :resource-process :rf/family :resources
   :inputs :parametric :output [:runtime [:rf.runtime/resources :entries]]
   :storage :runtime-db :authority {:kind :remote :system :server}
   :evaluation #{:on-route} :lifecycle :scoped-resource-key})

(def ^:private cart-sub-node
  ;; a sub-cache node whose live :value is at the frame-declared LARGE path.
  {:id [:cart/items] :kind :derivation :rf/family :subs
   :inputs [[:sub [:cart/raw]]] :output [:fact [:cart/items]]
   :storage :ephemeral :evaluation :on-demand :lifecycle :subscription-cache-entry
   :value {:cart {:items (vec (range 200))}}})

(def ^:private live-graph
  {:mode :live
   :frame secure-frame
   :nodes {[:rf/route :route/article]    route-fact-node
           [:resource :article/by-slug]  resource-node
           [:sub [:cart/items]]          cart-sub-node}
   :edges [{:from [:rf/route :route/article] :to [:resource :article/by-slug] :role :param}
           {:from [:sub [:cart/raw]] :to [:sub [:cart/items]] :role :input}]})

;; ---------------------------------------------------------------------------
;; 1. THE POSITIVE TEST — redact value, keep edge (the missing arm).
;; ---------------------------------------------------------------------------

(deftest redact-sensitive-value-keeps-edge-and-node-structure
  (let [redacted (h/redact-graph-for-egress live-graph secure-frame)
        route    (get-in redacted [:nodes [:rf/route :route/article]])]

    (testing "(a) the sensitive value was RUN THROUGH rf/elide-wire-value and
              ELIDED — the token at the frame-declared sensitive path is
              replaced by the :rf/redacted sentinel"
      (is (= :rf/redacted
             (get-in route [:params :current :params :token]))
          "the sensitive token must be redacted at egress"))

    (testing "(b) the node STRUCTURE survives — the node is still present and
              still classified by its superkind + refinement; the sibling
              non-sensitive value in the same map rides through raw"
      (is (some? route) "the route node is still present")
      (is (= :process (h/superkind route)) "still classified by :kind alone")
      (is (= :route-fact (:refinement route)))
      (is (= "welcome" (get-in route [:params :current :params :slug]))
          "the NON-sensitive sibling slug is NOT redacted")
      (is (= {:ref "home"} (:query route))
          "the non-sensitive :query summary rides through"))

    (testing "(c) the EDGE that names the redacted node survives intact — a
              redacted param is STILL a :param edge (structure preserved)"
      (is (= (:edges live-graph) (:edges redacted))
          "the entire edge vector is untouched by egress redaction")
      (is (some #(and (= :param (:role %))
                      (= [:rf/route :route/article] (:from %))
                      (= [:resource :article/by-slug] (:to %)))
                (:edges redacted))
          "the route→resource :param edge is still present"))

    (testing "(d) the node KEYS (canonical family-tagged ids) are unchanged —
              the graph topology a tool draws edges between is preserved"
      (is (= (set (keys (:nodes live-graph)))
             (set (keys (:nodes redacted)))))
      (is (some? (get-in redacted [:nodes [:resource :article/by-slug]]))
          "the edge's target node is still present + classified")
      (is (= :process (h/superkind (get-in redacted [:nodes [:resource :article/by-slug]])))))

    (testing "(e) the storage / evaluation / lifecycle classifications + the
              :output address + :inputs topology are STRUCTURE, never walked"
      (is (= :runtime-db (:storage route)))
      (is (= :on-route (:evaluation route)))
      (is (= :frame (:lifecycle route)))
      (is (= [:runtime [:rf.runtime/routing :current]] (:output route)))
      (is (= 17 (:nav-token route))))))

;; ---------------------------------------------------------------------------
;; 2. large elision — a frame-declared :large value → marker, keeping structure.
;; ---------------------------------------------------------------------------

(deftest large-value-elided-to-marker-keeps-structure
  (let [redacted (h/redact-graph-for-egress live-graph secure-frame)
        sub      (get-in redacted [:nodes [:sub [:cart/items]]])
        ;; the large path [:cart :items] sits NESTED inside the node's
        ;; :value summary {:cart {:items <large>}}; the walker descends to it
        ;; and replaces THAT leaf with the marker, leaving the surrounding
        ;; map shape intact (structure preserved).
        marker   (get-in sub [:value :cart :items])]
    (testing "the large-declared value is replaced by the :rf.size/large-elided
              marker, not shipped in full"
      (is (h/large-elided? marker)
          "the large value egresses as a size-elision marker")
      (is (= [:cart :items] (get-in marker [:rf.size/large-elided :path]))
          "the marker preserves the path (structure), withholds the value"))
    (testing "the node + its :input edge survive"
      (is (= :derivation (h/superkind sub)))
      (is (= [[:sub [:cart/raw]]] (:inputs sub)) "inputs topology untouched")
      (is (some #(and (= :input (:role %)) (= [:sub [:cart/items]] (:to %)))
                (:edges redacted))))))

;; ---------------------------------------------------------------------------
;; 3. per-frame — a frame with NO classification ships the same value raw.
;; ---------------------------------------------------------------------------

(deftest egress-applies-the-named-frames-policy-not-a-borrowed-one
  (testing "redacting under the SECURE frame's policy elides the token"
    (let [r (h/redact-graph-for-egress live-graph secure-frame)]
      (is (= :rf/redacted
             (get-in r [:nodes [:rf/route :route/article] :params :current :params :token])))))
  (testing "redacting under the PLAIN frame's policy (no sensitive decl)
            ships the same token VERBATIM — the policy is per-frame, applied
            from the named frame, not a borrowed or ambient one"
    (let [r (h/redact-graph-for-egress live-graph plain-frame)]
      (is (= "secret-session-jwt-abc123"
             (get-in r [:nodes [:rf/route :route/article] :params :current :params :token]))
          "no sensitive decl on :app/plain ⇒ the value rides through raw"))))

;; ---------------------------------------------------------------------------
;; 4. fail-closed — an unknown/frameless target redacts the WHOLE value
;;    rather than ship it under no policy (the silent-leak this contract
;;    abolishes).
;; ---------------------------------------------------------------------------

(deftest frameless-egress-fails-closed
  (testing "egress against an UNKNOWN frame (no reachable elision policy)
            fails closed — the whole value-bearing field is redacted to the
            :rf/redacted sentinel rather than shipped raw"
    (let [r     (h/redact-graph-for-egress live-graph :app/does-not-exist)
          route (get-in r [:nodes [:rf/route :route/article]])]
      (is (= :rf/redacted (:params route))
          "no reachable policy ⇒ the whole :params summary is redacted")
      (is (= :rf/redacted (:query route)))
      (testing "structure STILL survives even in the fail-closed case"
        (is (some? route))
        (is (= :process (h/superkind route)))
        (is (= (:edges live-graph) (:edges r)))
        (is (= (set (keys (:nodes live-graph))) (set (keys (:nodes r))))))))

  (testing "egress against a NIL frame fails closed EVEN WHEN an AMBIENT frame
            is bound — it must NOT borrow that ambient frame's policy and ship
            the value raw (rf2-udkj69). We bind ambient :app/plain (no sensitive
            decl, so a borrow WOULD ship the token raw); a nil frame-id MUST
            redact the whole value-bearing field, not resolve :app/plain."
    (rf/with-frame plain-frame
      (is (some? (frame/resolve-current-frame))
          "PRECONDITION — an ambient frame IS dynamically bound, so a frameless
           walk WOULD resolve it (the borrow this arm forbids)")
      (let [r     (h/redact-graph-for-egress live-graph nil)
            route (get-in r [:nodes [:rf/route :route/article]])]
        (is (= :rf/redacted (:params route))
            "nil frame ⇒ the whole :params summary is redacted, NOT shipped raw
             under the borrowed ambient :app/plain (empty) policy")
        (is (= :rf/redacted (:query route)))
        (is (not= "secret-session-jwt-abc123"
                  (get-in route [:params :current :params :token]))
            "the session token must NOT ride through under the borrowed frame")
        (testing "structure STILL survives in the nil-frame fail-closed case"
          (is (some? route))
          (is (= :process (h/superkind route)))
          (is (= (:edges live-graph) (:edges r)))
          (is (= (set (keys (:nodes live-graph))) (set (keys (:nodes r))))))))))

;; ===========================================================================
;; 5. THE ADVERSARIAL ARM — live resource scoped-key identity egress
;;    (rf2-k0meap.1, the P1 correctness gap).
;;
;; The §1-4 fixtures use a STATIC-style resource node (`:inputs :parametric`,
;; `:id :article/by-slug`), which never exercised the LIVE case the gap
;; names: a live resource node carries its sensitive scope/params NOT in a
;; value-bearing field but in its IDENTITY — the concrete scoped key
;; `[cache-scope resource-id canonical-params]` that is the node KEY, the
;; `:id`, the `:output` runtime-path tail, the realized `:inputs` `[:scope …]`
;; / `[:param …]`, AND the in-flight `:work-ledger :record :resource/key`.
;; The frame-policy value-path walk is structurally BLIND to those positions.
;;
;; This adversarial fixture plants a session token as the cache SCOPE (a
;; tenant/user-scoped activation) AND inside the canonical PARAMS, then
;; asserts NO raw secret survives ANYWHERE in the egressed graph while the
;; graph connectivity (node presence + the edge naming the resource node)
;; survives.
;; ===========================================================================

(def ^:private secret-token "tenant-jwt-9f3a-SECRET")
(def ^:private secret-scope  [:rf.scope/tenant secret-token])
(def ^:private secret-params {:slug "welcome" :auth-token secret-token})
(def ^:private live-scoped-key
  ;; [cache-scope resource-id canonical-params] — the live fact identity.
  [secret-scope :article/by-slug secret-params])

(def ^:private live-resource-node
  ;; mirrors `resource-cache-algebra-view`'s live-node-for shape: the scoped
  ;; key is the :id; the realized [:scope …]/[:param …] inputs; the concrete
  ;; :output entry address embeds the scoped key; the in-flight work-ledger
  ;; record carries the scoped key under :resource/key.
  {:id            live-scoped-key
   :kind          :process
   :refinement    :resource-process
   :rf/family     :resources
   :inputs        [[:scope secret-scope] [:param secret-params]]
   :output        [:runtime [:rf.runtime/resources :entries live-scoped-key]]
   :storage       :runtime-db
   :authority     {:kind :remote :system :server}
   :evaluation    #{:on-route}
   :lifecycle     {:kind :scoped-resource-key
                   :owners #{[:route :route/article 17]}}
   :status        :loading
   :work-ledger   {:work/id 99
                   :record  {:work/id      99
                             :work/kind    :rf.http/managed
                             :status       :pending
                             :resource/key live-scoped-key}}
   :host-transient [[:rf.http/in-flight 99]]})

(def ^:private live-resource-graph
  {:mode  :live
   :frame secure-frame
   :nodes {[:resource live-scoped-key] live-resource-node}
   ;; a route-owned activation edge naming the live resource node — the
   ;; realized route→resource edge whose :to end embeds the scoped key.
   :edges [{:from :rf/route :to [:resource live-scoped-key] :role :param
            :owner [:route :route/article 17]}]})

(defn- contains-secret?
  "Deep-walk `v` and return true iff the raw secret token string appears
  ANYWHERE (as a leaf, a key, inside a scope/params, a work-ledger record,
  an :output path — anywhere). Returns a strict boolean."
  [v]
  (boolean
    (cond
      (= v secret-token) true
      (map? v)           (some contains-secret? (concat (keys v) (vals v)))
      (coll? v)          (some contains-secret? v)
      :else              false)))

(defn- projected-scoped-key?
  "True when `v` keeps the 3-tuple scoped-key SHAPE after egress projection
  — `[<scope-handle> resource-id <params-handle>]`: the registration
  resource-id keyword is PRESERVED in the middle (so a tool still sees WHICH
  resource), the scope + params replaced by opaque handles (structure
  preserved, secrets withheld)."
  [v]
  (and (vector? v)
       (= 3 (count v))
       (keyword? (nth v 1))))

(deftest live-resource-scoped-key-identity-is-redacted-at-egress
  ;; NB the secure-frame declares [:current :params :token] sensitive — but
  ;; the secret here lives in the resource scoped-key identity, NOT at a
  ;; frame-declared app-db path. So this PROVES the identity projection, not
  ;; the value-path walk: even under a frame whose policy does NOT name this
  ;; path, the scoped-key scope/params must not egress raw.
  (let [redacted (h/redact-graph-for-egress live-resource-graph secure-frame)]

    (testing "(a) NO raw secret token survives ANYWHERE in the egressed graph"
      (is (contains-secret? live-resource-graph)
          "sanity: the raw graph DOES carry the secret")
      (is (not (contains-secret? redacted))
          "the secret must NOT appear anywhere in the egressed graph — not in
           the node key, :id, :inputs, :output, work-ledger, or edges"))

    (testing "(b) the node KEY no longer carries the raw scoped key — it is
              projected to [:resource [<scope-handle> resource-id <params-handle>]]"
      (is (not (contains? (:nodes redacted) [:resource live-scoped-key]))
          "the raw-scoped-key node key is gone")
      (let [[node-key _node] (first (:nodes redacted))
            scoped           (second node-key)]
        (is (= :resource (first node-key)) "still a :resource node key")
        (is (projected-scoped-key? scoped) "still a 3-tuple scoped-key shape (structure preserved)")
        (is (= :article/by-slug (nth scoped 1))
            "the registration resource-id is PRESERVED (a tool still sees WHICH resource)")
        (is (not= secret-scope (nth scoped 0)) "the scope component is opaqued")
        (is (not= secret-params (nth scoped 2)) "the params component is opaqued")))

    (testing "(c) graph CONNECTIVITY survives — the resource node is still
              present + classified, and the edge naming it still CONNECTS to
              the (projected) node key consistently"
      (is (= 1 (count (:nodes redacted))) "the node is still present")
      (let [node     (-> redacted :nodes vals first)
            node-key (-> redacted :nodes keys first)
            edge     (first (:edges redacted))]
        (is (= :process (h/superkind node)) "still classified by superkind")
        (is (= :resource-process (:refinement node)))
        (is (= :param (:role edge)) "the edge role survives")
        (is (= node-key (:to edge))
            "the edge :to is remapped to the SAME projected node key (consistent remap — connectivity preserved)")))

    (testing "(d) the realized :inputs scope/params, the :output entry path,
              and the work-ledger :resource/key all carry NO raw secret but
              keep their structural shape"
      (let [node (-> redacted :nodes vals first)]
        ;; :inputs still [[:scope <h>] [:param <h>]] — the roles survive.
        (is (= [:scope :param] (mapv first (:inputs node)))
            "the [:scope …]/[:param …] input roles survive")
        (is (not= secret-scope (second (first (:inputs node)))))
        (is (not= secret-params (second (second (:inputs node)))))
        ;; :output is still a runtime path of the same shape, scoped key projected.
        (is (= :runtime (first (:output node))) ":output is still a runtime address")
        (is (= [:rf.runtime/resources :entries] (take 2 (second (:output node))))
            "the :output runtime path prefix survives")
        ;; work-ledger record :resource/key projected; the other ledger fields ride through.
        (let [rec (get-in node [:work-ledger :record])]
          (is (= 99 (:work/id rec)) "the work id (non-secret) rides through")
          (is (= :pending (:status rec)))
          (is (projected-scoped-key? (:resource/key rec))
              "the work-ledger :resource/key keeps the scoped-key shape")
          (is (= :article/by-slug (nth (:resource/key rec) 1))
              "and preserves the resource-id"))))

    (testing "(e) the projection is DETERMINISTIC — the same scoped key
              projects to the same handle in the node key, :id, :output, and
              work-ledger (so all the identity positions stay mutually
              consistent — one fact, one projected identity)"
      (let [node          (-> redacted :nodes vals first)
            node-key      (-> redacted :nodes keys first)
            key-scoped    (second node-key)
            id-scoped     (:id node)
            output-scoped (last (second (:output node)))
            ledger-scoped (get-in node [:work-ledger :record :resource/key])]
        (is (= key-scoped id-scoped output-scoped ledger-scoped)
            "every identity position projects to the SAME opaque scoped key")))))

(deftest live-resource-identity-projection-is-idempotent
  ;; A value may egress MORE THAN ONCE (re-egress on re-render / re-subscribe /
  ;; a forwarder cascade), so re-projecting an already-egressed graph MUST be
  ;; the identity — `project(project(x)) == project(x)`. The opaque handle of
  ;; an already-opaque handle is the SAME handle, and `:rf/redacted` re-projects
  ;; to itself.
  ;;
  ;; rf2-g197ep: the prior assertion checked ONLY node-key set + no-raw-secret.
  ;; That MISSED the real non-idempotence — node-keys/`:id` are stable only
  ;; because a projected scoped key no longer matches `scoped-resource-key?`
  ;; (opaque-vector tail, not a map), so they are not re-projected; but the
  ;; realized `:inputs` `[:scope …]`/`[:param …]` payloads were re-hashed
  ;; UNCONDITIONALLY, minting fresh handles on the second pass. Full graph
  ;; equality catches it; it FAILS against the pre-fix unconditional re-hash.
  (testing "re-projecting an already-egressed graph is the IDENTITY — a
            forwarder pipeline that double-projects does not corrupt the graph"
    (let [once   (h/redact-graph-for-egress live-resource-graph secure-frame)
          twice  (h/redact-graph-for-egress once secure-frame)
          thrice (h/redact-graph-for-egress twice secure-frame)
          node1  (-> once :nodes vals first)
          node2  (-> twice :nodes vals first)]
      (is (not (contains-secret? twice)) "still no secret after double projection")
      ;; The headline idempotence law: a second (and third) egress pass changes
      ;; NOTHING.
      (is (= once twice)
          "egress is idempotent — project(project(x)) == project(x)")
      (is (= twice thrice)
          "and stays the identity on every further pass")
      ;; Spelled-out witnesses so a regression names WHICH position drifted.
      (is (= (set (keys (:nodes once))) (set (keys (:nodes twice))))
          "node keys are stable under re-projection")
      (is (= (:id node1) (:id node2))
          "the projected scoped-key :id is stable under re-projection")
      (is (= (:inputs node1) (:inputs node2))
          "the projected realized :inputs ([:scope …]/[:param …]) are stable — a
           second pass must NOT re-hash the opaque handles into fresh handles")
      (is (= (:output node1) (:output node2))
          "the projected :output runtime path is stable under re-projection")
      (is (= (get-in node1 [:work-ledger :record :resource/key])
             (get-in node2 [:work-ledger :record :resource/key]))
          "the projected work-ledger :resource/key is stable under re-projection")
      (is (= (:edges once) (:edges twice))
          "the projected edge endpoints are stable under re-projection"))))
