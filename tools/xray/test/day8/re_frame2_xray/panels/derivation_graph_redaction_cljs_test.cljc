(ns day8.re-frame2-xray.panels.derivation-graph-redaction-cljs-test
  "The POSITIVE off-box egress redaction test for the Derivation-Graph panel
  (EP-0014).

  ## What this pins

  spec/Derivations.md §Conformance 'Tool redaction' + §Redaction metadata
  require: graph inspection can summarize or redact sensitive params,
  scopes, and values WITHOUT losing graph STRUCTURE — a redacted param is
  still an edge — composing through the single shared `rf/elide-wire-value`
  walker. This file is the POSITIVE test that (a) a sensitive value is run
  through `rf/elide-wire-value` AND (b) the corresponding edge / node
  STRUCTURE survives — a NEGATIVE ('the snapshot :value is NOT inlined')
  alone shows neither.

  ## Where the redaction lives

  Under EP-0014, raw params/values on the live algebra views are
  correct-as-designed raw-on-box projections. Redaction is an EGRESS concern owned by the FRAME
  elision policy via the shared `elide-wire-value` walker (per-frame,
  fail-closed), applied at the wire boundary where a consuming tool ships
  the graph OFF-BOX — NOT at the registrar-derived composer. The first
  consumer, Xray, is where that egress call site lives
  (`derivation-graph-helpers/redact-graph-for-egress`), so the
  redact-without-losing-structure wiring test belongs HERE alongside the
  consumer wiring.

  ## The algorithm is core-owned; this suite is the CONSUMER/WIRING pin

  The redaction ALGORITHM is owned by the bundle-isolated core tooling ns
  `re-frame.derivation.egress/project-graph`;
  `derivation-graph-helpers/redact-graph-for-egress` is a thin DELEGATE to it,
  and the derivation-conformance egress arms (`g-*`) carry the COMPREHENSIVE
  algorithm coverage (no-secret-in-any-position, stable opaque identity,
  connectivity, idempotence, work-id/host-transient, fail-closed, live
  composition) over that same owner. So this suite keeps FOCUSED
  consumer/wiring tests: the frame-policy value walk through Xray's real
  registered frames (§1-4) and one live-resource identity-projection smoke
  (§5) proving the delegate wires the full projection through — it does NOT
  re-prove the algorithm.

  ## What's asserted

    1. **POSITIVE redact-value-keep-edge** — a route navigated for real
       carries a param its route declared `:sensitive`; the live route node,
       composed by `live-derivation-graph`, egresses that param as
       `:rf/redacted` beside its raw undeclared sibling WHILE the node
       remains present + classified AND the edge that names it survives
       intact (structure preserved).
    2. **per-frame** — egress redacts under the NAMED frame's policy, not an
       ambient or borrowed one: a frame whose image holds no route ships the
       same param raw.
    3. **fail-closed** — egress for an unknown / nil / frameless target
       redacts the whole value rather than ship it raw under no policy — and
       must NOT borrow an AMBIENT dynamically-bound frame: a nil
       frame-id under an ambient binding still redacts, never ships raw.
    4. **large elision** — a frame-declared `:large` value is replaced by
       the `:rf.size/large-elided` marker, again keeping structure.
    5. **identity-projection wiring smoke** — the delegate projects a live
       resource node's scoped-key identity (no raw secret anywhere, the
       registration resource-id preserved, connectivity preserved, one
       opaque identity across every position); the per-position + idempotence
       depth lives in derivation-conformance."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.string :as str]
            [re-frame.core :as rf]
            [re-frame.derivation.graph :as rf.derivation.graph]
            [re-frame.elision]
            [re-frame.identity :as rf.identity]
            [re-frame.routing]
            [re-frame.routing.tooling :as rf.routing.tooling]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.test-support :as rf.test-support]
            [day8.re-frame2-xray.panels.derivation-graph-helpers :as h]))

;; ---------------------------------------------------------------------------
;; Runtime fixture — reset the registry / frame state, install the plain-atom
;; substrate (a non-React JVM-and-node substrate), then register two frames
;; and the declarations under test:
;;
;;   :app/secure   — runs the default image, so it sees `:route/article`,
;;                   whose `:token` param is declared sensitive, and the
;;                   classification declaring [:cart :items] large.
;;   :app/plain    — runs an image holding no route and no classification, so
;;                   both declarations are outside its view and every value
;;                   egresses verbatim (modulo the runtime size threshold,
;;                   left at default).
;; ---------------------------------------------------------------------------

(def secure-frame :app/secure)
(def plain-frame  :app/plain)

(def ^:private unclassified-image
  "A frame running this image classifies nothing: classification resolves
  through the frame's own view (Spec 015 §Static app-db classification), and
  this image holds one inline event, no route and no classification."
  (rf/image {:id            ::unclassified
             :registrations {:reg-event [[::noop {} (fn [{:keys [db]} _] {:db db})]]}}))

(defn- install-policy! []
  (rf/reg-route :route/article {:sensitive [[:params :token]]} "/article/:slug/:token")
  (rf/reg-classification ::classified {:large [[:cart :items]]}))

(defn- init-fn []
  (rf/make-frame {:id plain-frame :images [unclassified-image]})
  (rf/make-frame {:id secure-frame})
  (install-policy!))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter rf.substrate.plain-atom/adapter
     ;; OPT OUT of the default `:rf/default` ambient scope: the fail-closed
     ;; arm asserts that egress against an unreachable frame redacts because
     ;; NO frame is resolvable. A bound ambient `:rf/default` would let the
     ;; frameless walk resolve to it (empty-policy identity) and ship raw —
     ;; masking the fail-closed contract. Our frames carry no `:initial-events`
     ;; work, so a clear ambient scope is safe.
     :ambient-frame nil
     :init-fn init-fn}))

;; ---------------------------------------------------------------------------
;; The live graph Xray egresses. The route node is composed by
;; `live-derivation-graph` from a real navigation, through the routes family
;; as `derivation-graph/xray-contributors` carries it, so it is keyed
;; `:rf/route` and carries the live `:params` / `:query` bare: the shape the
;; route's own `:sensitive` declaration has to reach. Beside it sit a
;; resource node the route's `:param` edge names and a sub-cache node holding
;; a frame-declared large value, the structure the walk must keep.
;; ---------------------------------------------------------------------------

(def ^:private route-secret "secret-session-jwt-abc123")

(def ^:private route-contributors
  {:routes {:static-fn  rf.routing.tooling/route-algebra-view
            :live-fn    rf.routing.tooling/route-slice-algebra-view
            :live-shape :node}})

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

(def ^:private structure-edges
  [{:from :rf/route :to [:resource :article/by-slug] :role :param}
   {:from [:sub [:cart/raw]] :to [:sub [:cart/items]] :role :input}])

(defn- live-graph
  "Navigate `:app/secure` to an article whose `:token` param is
  `route-secret`, and return the live graph composed for that frame with the
  structure fixtures beside its route node."
  []
  (rf/dispatch-sync [:rf.route/navigate {:to     :route/article
                                         :params {:slug "welcome" :token route-secret}
                                         :query  {:ref "home"}}]
                    {:frame secure-frame})
  (-> (rf.derivation.graph/live-derivation-graph secure-frame route-contributors)
      (update :nodes assoc
              [:resource :article/by-slug] resource-node
              [:sub [:cart/items]]         cart-sub-node)
      (update :edges into structure-edges)))

;; ---------------------------------------------------------------------------
;; 1. THE POSITIVE TEST — redact value, keep edge.
;; ---------------------------------------------------------------------------

(deftest redact-sensitive-value-keeps-edge-and-node-structure
  (let [graph    (live-graph)
        route    (get-in graph [:nodes :rf/route])
        redacted (h/redact-graph-for-egress graph secure-frame)]

    (is (= route-secret (get-in route [:params :token]))
        "PRECONDITION: the composed route node carries the raw token")

    (testing "(a) the param the route declared :sensitive is replaced by the
              :rf/redacted sentinel while every other field of the node — the
              sibling slug, :query, the classifications, :output — rides
              through untouched"
      (is (= (assoc-in route [:params :token] :rf/redacted)
             (get-in redacted [:nodes :rf/route]))))

    (testing "(b) the edges and node keys survive intact — a redacted param is
              STILL a :param edge"
      (is (= (:edges graph) (:edges redacted)))
      (is (= (set (keys (:nodes graph)))
             (set (keys (:nodes redacted))))))

    (testing "(c) a frame-declared :large value nested in a node's :value is
              replaced by the size-elision marker, which keeps the path"
      (is (= [:cart :items]
             (get-in redacted [:nodes [:sub [:cart/items]] :value :cart :items
                               :rf.size/large-elided :path]))))))

;; ---------------------------------------------------------------------------
;; 2. per-frame — a frame whose view holds no route ships the same param raw.
;; ---------------------------------------------------------------------------

(deftest egress-applies-the-named-frames-policy-not-a-borrowed-one
  (testing "redacting under the PLAIN frame, whose image holds no route,
            ships the same token VERBATIM — the declaration resolves through
            the named frame's own view, not a borrowed or ambient one"
    (let [r (h/redact-graph-for-egress (live-graph) plain-frame)]
      (is (= route-secret (get-in r [:nodes :rf/route :params :token]))
          "`:route/article` is outside :app/plain's view ⇒ the token rides through raw"))))

;; ---------------------------------------------------------------------------
;; 3. fail-closed — an unknown/frameless target redacts the WHOLE value
;;    rather than ship it under no policy (which would be a silent leak).
;; ---------------------------------------------------------------------------

(deftest frameless-egress-fails-closed
  (let [graph (live-graph)]
    (testing "egress against an UNKNOWN frame (no reachable elision policy)
              fails closed — the whole value-bearing field is redacted to the
              :rf/redacted sentinel rather than shipped raw"
      (is (= :rf/redacted
             (get-in (h/redact-graph-for-egress graph :app/does-not-exist)
                     [:nodes :rf/route :params]))))

    (testing "egress against a NIL frame fails closed EVEN WHEN an AMBIENT frame
              is bound — it must NOT borrow that ambient frame's policy and ship
              the value raw. We bind ambient :app/plain (no route in view, so a
              borrow WOULD ship the token raw)."
      (rf/with-frame plain-frame
        (is (= :rf/redacted
               (get-in (h/redact-graph-for-egress graph nil)
                       [:nodes :rf/route :params])))))))

;; ===========================================================================
;; 5. FOCUSED IDENTITY-PROJECTION WIRING SMOKE.
;;
;; Tests 1-3 are the consumer/wiring pins over the frame-policy VALUE walk (a
;; declared-sensitive route param and a declared-large sub `:value`, redacted
;; under the named frame's elision policy). They use a STATIC-style resource
;; node and never exercise the LIVE resource IDENTITY path — a live resource
;; node carries its
;; sensitive scope/params NOT in a value-bearing field but in its scoped-key
;; identity (node key / `:id` / `:output` / realized `:inputs` / the
;; `:work` record's `:resource/key`), positions the value walk is
;; structurally blind to. This ONE smoke proves the Xray delegate carries the
;; core-owned identity projection through; the COMPREHENSIVE per-position +
;; idempotence coverage lives in the
;; derivation-conformance egress arms (`g-*`), which test the same owner
;; `re-frame.derivation.egress/project-graph` directly. Xray does
;; not re-prove the algorithm — it proves the wiring.
;; ===========================================================================

(def ^:private secret-token "tenant-jwt-9f3a-SECRET")
(def ^:private secret-scope  [:rf.scope/tenant secret-token])
(def ^:private secret-params {:slug "welcome" :auth-token secret-token})
(def ^:private live-scoped-key
  ;; [cache-scope resource-id canonical-params] — the live fact identity.
  [secret-scope :article/by-slug secret-params])
(def ^:private live-work-id
  ;; the resource-family work-id — `[:rf.work/resource <resource-id>
  ;; <generation>]`, which names the attempt without its scope or params.
  [:rf.work/resource :article/by-slug 4])

(def ^:private live-resource-node
  ;; mirrors `resource-cache-algebra-view`'s live-node-for shape: the scoped
  ;; key is the :id; the realized [:scope …]/[:param …] inputs; the concrete
  ;; :output entry address embeds the scoped key; the in-flight :work
  ;; record carries the scoped key, and it and the host-transient address
  ;; name the attempt by its work-id.
  {:id             live-scoped-key
   :kind           :process
   :refinement     :resource-process
   :rf/family      :resources
   :inputs         [[:scope secret-scope] [:param secret-params]]
   :output         [:runtime [:rf.runtime/resources :entries live-scoped-key]]
   :storage        :runtime-db
   :authority      {:kind :remote :system :server}
   :evaluation     #{:on-route}
   :lifecycle      {:kind :scoped-resource-key :owners #{[:route :route/article 17]}}
   :status         :loading
   :work           {:work/id live-work-id
                    :record  {:work/id      live-work-id
                              :status       :pending
                              :resource/key live-scoped-key}}
   :host-transient [[:rf.http/in-flight live-work-id]]})

(def ^:private live-resource-graph
  {:mode  :live
   :frame secure-frame
   :nodes {[:resource live-scoped-key] live-resource-node}
   ;; a route-owned activation edge naming the live resource node.
   :edges [{:from :rf/route :to [:resource live-scoped-key] :role :param
            :owner [:route :route/article 17]}]})

(defn- contains-secret?
  "Deep-walk `v`; true iff the raw secret token appears ANYWHERE, map keys
  included: as a whole string leaf, EMBEDDED in a larger string (a CEDN-1
  token such as `v[k::rf.scope/tenant s:\"<secret>\"]` carries the raw
  value inside it), or inside the printed form of any other leaf, such as
  a keyword or symbol built from it. It mirrors the
  derivation-conformance predicate."
  [v]
  (boolean
    (cond
      (string? v) (str/includes? v secret-token)
      (map? v)    (some contains-secret? (concat (keys v) (vals v)))
      (coll? v)   (some contains-secret? v)
      :else       (str/includes? (pr-str v) secret-token))))

(deftest leak-predicate-catches-an-embedded-secret-rf2-413u0
  ;; Every "no raw secret survives" assertion below is only as strong as
  ;; `contains-secret?`. A handle minted from the CEDN-1 token instead of
  ;; its digest carries the secret INSIDE a larger string, and a predicate
  ;; matching only a leaf EQUAL to the secret would let it pass.
  (is (contains-secret? [:rf.resource/opaque (rf.identity/canonical-bytes secret-scope)])
      "the predicate finds the secret embedded in a CEDN-1 token string")
  (is (contains-secret? {:tenant (keyword "tenant" secret-token)})
      "and in a keyword built from it"))

(deftest delegate-projects-live-resource-identity
  (let [redacted        (h/redact-graph-for-egress live-resource-graph secure-frame)
        [node-key node] (first (:nodes redacted))
        scoped-key      (second node-key)]
    (is (not (contains-secret? redacted))
        "NO raw secret survives in any identity position — node key, :id,
         :inputs, :output, :work, host-transient, edges")
    (is (= :article/by-slug (nth scoped-key 1))
        "the registration resource-id stays visible")
    (is (= node-key (:to (first (:edges redacted))))
        "the :param edge is remapped to the projected key (connectivity)")
    (is (= scoped-key (:id node) (last (second (:output node)))
           (get-in node [:work :record :resource/key]))
        "every identity position projects to the SAME opaque scoped key")))
