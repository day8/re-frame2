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
    3. **fail-closed** — egress for an unknown / frameless target redacts
       the whole value rather than ship it raw under no policy.
    4. **large elision** — a frame-declared `:large` value is replaced by
       the `:rf.size/large-elided` marker, again keeping structure."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
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
     ;; masking the fail-closed contract. Our frames carry no `:on-create`
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
   :evaluation #{:on-route} :lifecycle :resource-key})

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
        (is (= (set (keys (:nodes live-graph))) (set (keys (:nodes r)))))))))
