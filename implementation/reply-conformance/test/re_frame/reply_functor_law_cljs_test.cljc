(ns re-frame.reply-functor-law-cljs-test
  "SUBSTRATE-LEVEL reply-target functor/naturality law proof over the
  RELAY SHAPES the target-relocating families use (rf2-5ha70w, EP-0011
  testing-rigour audit rf2-h8xw2v).

  ## Scope — read this before trusting the suite (rf2-xyn0dv)

  This suite is NOT end-to-end family-relocation conformance. The route and
  machine families do NOT (today) expose a `map-target`-based relocation
  helper — there is no `re-frame.routing.reply/relay` or
  `re-frame.machines.reply/on-done` seam in the source. So this suite
  CANNOT exercise a real family relocation seam; instead it pins the
  functor laws over SYNTHETIC event-transforms (`route-relay`,
  `spawn-on-done`, `grandparent-relay` below) that MODEL THE SHAPE such a
  relocation would take, applied to REAL family reply maps (`route-reply`,
  `spawn-reply` are genuine route-loader / machine-spawn replies). It is a
  substrate-shape proof, not an integration proof: it proves
  `re-frame.reply/map-target` + `complete` obey the laws for the relay
  SHAPES a relocating family would build — it does NOT prove that any
  family actually wires those shapes through `map-target` end to end.

  If a real route/machine target-relocation seam ever lands (a family
  helper that calls `map-target` to relay a child continuation onto a
  parent event), point this suite at THAT helper and the proof becomes
  integration-level; until then, the prose above states the actual
  altitude so future drift is not masked by an overstated claim.

  The reply-target functor laws —

    identity      (map-target identity t)            ≡ t
    composition   (map-target f (map-target g t))     ≡ (map-target (comp f g) t)
    naturality    (complete (map-target f t) r)       ≡ (f (complete t r))

  — are pinned RIGOROUSLY at the substrate (`re-frame.reply-test`:
  identity + naturality + composition both orders + mapping-changes-only-
  the-event) and RE-PROVEN by the first fresh consumer
  (`re-frame.timer-probe-conformance-cljs-test/reply-target-functor-law`).
  They are NOT re-pinned at any LOWERED family.

  ## Why this suite exists

  For the families that pass HTTP's target through UNCHANGED (HTTP itself,
  resources, mutations — `map-target` stores nothing on the target, so the
  law holds structurally) a per-family re-proof is low-value; the substrate
  + probe already cover that case. The risk — if any — is in the families
  that would RELOCATE / WRAP their target before completion:

    - a ROUTE LOADER relaying a loader continuation onto a PARENT route
      event (the child's reply re-targeted onto `[:route/parent … reply]`);
    - a MACHINE SPAWN routing the child's reply onto the parent's
      `:on-done` / `:on-error` continuation (the child's reply re-targeted
      onto `[:parent/on-done … reply]`).

  Those relocations are exactly where a naturality break — a mapping that
  changes MORE than the completed event, or a composition whose order
  matters — would hide. The second review judged routing/machines PASS by
  READING (the nav-token wrap reads current + suppresses before the app
  target; spawn drives `:on-done` with `(:value reply)`). Because no family
  exposes a `map-target` relocation seam to point at, this is a
  belt-and-braces SUBSTRATE-SHAPE proof over the relay shapes those
  families would use — NOT executable proof of a real family relocation.

  ## What the relocation transforms model

  A relocating family WOULD build an event-transform `f` (the role Elm's
  `Cmd.map` plays) that wraps / relays the completed continuation. These
  transforms are SYNTHETIC stand-ins for that shape (no family exports
  them); the law proven is: relocating the target via `map-target` then
  completing equals completing then relocating — for the relay shapes the
  two families would use:

    - route-relay:   `(fn [event] [:route/parent-relay route-id event])`
                     — wrap the loader continuation under a parent route
                       relay event (the loader reply rides as the relay's
                       payload).
    - spawn-on-done: `(fn [event] [:parent/on-done parent-id event])`
                     — route the child completion onto the parent's
                       `:on-done` continuation event.

  Pure-fn conformance over `re-frame.reply` (`map-target` / `complete`) for
  the relocation SHAPES the relocating families would use. Lives in the
  cross-artefact `reply-conformance/` surface alongside the vocab
  conformance suite. Runs on `npm run test:cljs` (ns matches `cljs-test$`)
  AND the JVM gate (`reply-conformance/deps.edn` `:test`).

  Canonical contract: `spec/Managed-Effects.md` §Reply mapping and the
  functor law."
  (:require [clojure.test :refer [deftest is testing]]
            [re-frame.reply :as reply]
            [re-frame.reply-conformance-fixtures :as fixtures]
            [re-frame.routing.reply :as route-reply]
            [re-frame.machines.reply :as m-reply]))

;; ---------------------------------------------------------------------------
;; Canonical replies for the two relocating families. Both are REAL family
;; reply maps (a genuine route-loader :ok reply / a genuine machine-spawn
;; success reply) — so the law is exercised over a real family reply, not a
;; synthetic stub. NOTE the asymmetry the docstring calls out: the replies
;; are real, but the TRANSFORMS below are synthetic stand-ins — no family
;; exposes a map-target relocation seam to point at.
;; ---------------------------------------------------------------------------

;; EP-0017 — the durable causal completion time these canonical replies carry
;; as `:completed-at` (the value the live reply dispatch carries as flat
;; `:rf.cofx` `:rf/time-ms`). The relocation/naturality proof below asserts
;; this fact rides UNCHANGED through a target relocation — a relocation that
;; dropped or mutated the completion fact (mis-threading causal completion
;; time, the bead's rf2-ear61v concern) would surface in the identity-
;; preservation test.
;; Shared across the reply-conformance tier — owned by
;; `re-frame.reply-conformance-fixtures` (rf2-b2a3a2).
(def ^:private completion-time-ms fixtures/completion-time-ms)

(def ^:private route-reply
  "A canonical route-loader :ok reply (the loader's decoded payload). The
  route work-id rides; the value is the loaded resource; `:completed-at` is
  the durable causal completion time (EP-0017). Built via the shared
  `re-frame.reply-conformance-fixtures/canonical-ok-reply` (rf2-b2a3a2)."
  (fixtures/canonical-ok-reply
    {:value       {:article {:id 42 :title "Welcome"}}
     :work/id     (route-reply/work-id {:route-id :route/article :nav-token "nav-1" :loader-id :article/loaded})
     :work/kind   :route
     :rf.frame/id :app/main}))

(def ^:private spawn-reply
  "A canonical machine spawned-actor :ok reply (the child's :output-key
  result under :value). The machine work-id and the durable `:completed-at`
  causal completion time (EP-0017) ride."
  (m-reply/success-reply {:actor-id :auth/flow#1 :parent-id :auth/main
                          :work-bearing-path [:authenticating] :frame :app/main
                          :completed-at completion-time-ms}
                         {:user-id "u-42"}))

;; ---------------------------------------------------------------------------
;; The RELOCATION transforms. SYNTHETIC stand-ins (no family exports these —
;; see the ns docstring): each models the SHAPE a relocating family would
;; build. An event-transform receives the FULLY-COMPLETED event (the target
;; event with the reply map already appended) and returns the
;; relocated/rewrapped event — exactly what a relay loader / spawn :on-done
;; routing WOULD do.
;; ---------------------------------------------------------------------------

(defn- route-relay
  "Relocate a loader continuation onto a PARENT route relay event — the
  route-loader relay shape. The completed continuation rides as the relay
  event's payload (`[:route/parent-relay route-id <completed-event>]`)."
  [route-id]
  (fn [event] [:route/parent-relay route-id event]))

(defn- spawn-on-done
  "Route a child completion onto the parent's :on-done continuation event —
  the machine-spawn relay shape (`[:parent/on-done parent-id
  <completed-event>]`)."
  [parent-id]
  (fn [event] [:parent/on-done parent-id event]))

;; A SECOND relay per family so the COMPOSITION law is exercised with two
;; genuinely-different relocations. Both are WRAP transforms
;; (`event → [tag … event]`), the realistic relocating shape — a relay onto
;; a relay (a loader continuation relayed through a parent AND a
;; grandparent; a spawn child relayed through the parent AND a supervisor).
;; Wraps compose in EITHER order (each takes any event and wraps it), so the
;; composition law is exercised both ways without a shape-mismatch.
(defn- grandparent-relay
  "Relocate a continuation onto a GRANDPARENT relay event — a second,
  distinct wrap that composes with `route-relay` / `spawn-on-done` in either
  order (the relay-onto-a-relay relocation)."
  [grandparent-id]
  (fn [event] [:relay/grandparent grandparent-id event]))

;; ---------------------------------------------------------------------------
;; Naturality — the one that would catch a relocation that changes MORE than
;; the completed event. `(complete (map-target f t) r) == (f (complete t r))`.
;; ---------------------------------------------------------------------------

(deftest naturality-holds-for-the-route-relay-shape
  (testing "route-loader relay SHAPE (synthetic transform, real route reply): complete(map-target(relay, t), r) == relay(complete(t, r))"
    (let [target [:article/loaded {:slug "welcome"}]
          relay  (route-relay :route/article)]
      (is (= (reply/complete (reply/map-target relay target) route-reply)
             (relay (reply/complete target route-reply)))
          "relocating the loader target onto a parent relay then completing equals
           completing then relocating — the relay changes ONLY the event")
      (testing "the relocated event is the parent relay carrying the completed loader event"
        (is (= [:route/parent-relay :route/article
                [:article/loaded {:slug "welcome"} route-reply]]
               (reply/complete (reply/map-target relay target) route-reply)))))))

(deftest naturality-holds-for-the-spawn-on-done-shape
  (testing "machine-spawn :on-done SHAPE (synthetic transform, real spawn reply): complete(map-target(on-done, t), r) == on-done(complete(t, r))"
    (let [target  [:auth/done {:flow :login}]
          on-done (spawn-on-done :auth/main)]
      (is (= (reply/complete (reply/map-target on-done target) spawn-reply)
             (on-done (reply/complete target spawn-reply)))
          "routing the child completion onto the parent's :on-done then completing
           equals completing then routing — the routing changes ONLY the event")
      (testing "the routed event is the parent :on-done carrying the completed child event"
        (is (= [:parent/on-done :auth/main
                [:auth/done {:flow :login} spawn-reply]]
               (reply/complete (reply/map-target on-done target) spawn-reply)))))))

;; ---------------------------------------------------------------------------
;; Identity — relocating through identity is a no-op on completion.
;; ---------------------------------------------------------------------------

(deftest identity-holds-for-both-relay-shapes
  (testing "route relay target: map-target identity is the identity on completion"
    (let [target [:article/loaded {:slug "welcome"}]]
      (is (= (reply/complete target route-reply)
             (reply/complete (reply/map-target identity target) route-reply)))))
  (testing "spawn :on-done target: map-target identity is the identity on completion"
    (let [target [:auth/done {:flow :login}]]
      (is (= (reply/complete target spawn-reply)
             (reply/complete (reply/map-target identity target) spawn-reply))))))

;; ---------------------------------------------------------------------------
;; Composition — `map f ∘ map g == map (f ∘ g)`, BOTH orders, with two
;; genuinely-different relocations so a composition whose order mattered
;; would surface.
;; ---------------------------------------------------------------------------

(deftest composition-holds-for-the-route-relay-shape
  (testing "route parent-relay ∘ grandparent-relay (synthetic shapes) compose in either order — map-target composes the transforms"
    (let [target  [:article/loaded {:slug "welcome"}]
          relay   (route-relay :route/article)
          grandpa (grandparent-relay :route/root)]
      (is (= (reply/complete (reply/map-target relay (reply/map-target grandpa target)) route-reply)
             (reply/complete (reply/map-target (comp relay grandpa) target) route-reply))
          "map relay ∘ map grandpa == map (relay ∘ grandpa)")
      (is (= (reply/complete (reply/map-target grandpa (reply/map-target relay target)) route-reply)
             (reply/complete (reply/map-target (comp grandpa relay) target) route-reply))
          "and the other order: map grandpa ∘ map relay == map (grandpa ∘ relay)")
      (testing "the composed (relay ∘ grandpa) result wraps grandpa's relay inside the parent relay"
        (is (= [:route/parent-relay :route/article
                [:relay/grandparent :route/root
                 [:article/loaded {:slug "welcome"} route-reply]]]
               (reply/complete (reply/map-target (comp relay grandpa) target) route-reply)))))))

(deftest composition-holds-for-the-spawn-on-done-shape
  (testing "spawn :on-done ∘ grandparent-relay (synthetic supervisor-relay shapes) compose in either order"
    (let [target  [:auth/done {:flow :login}]
          on-done (spawn-on-done :auth/main)
          grandpa (grandparent-relay :auth/supervisor)]
      (is (= (reply/complete (reply/map-target on-done (reply/map-target grandpa target)) spawn-reply)
             (reply/complete (reply/map-target (comp on-done grandpa) target) spawn-reply))
          "map on-done ∘ map grandpa == map (on-done ∘ grandpa)")
      (is (= (reply/complete (reply/map-target grandpa (reply/map-target on-done target)) spawn-reply)
             (reply/complete (reply/map-target (comp grandpa on-done) target) spawn-reply))
          "and the other order"))))

;; ---------------------------------------------------------------------------
;; The structural guarantee — relocating the target changes ONLY the
;; completed event, NEVER the reply's identity facts (work-id / status /
;; correlation). This is the property a naturality break would violate: a
;; relocation that smuggled a status / work-id change would show here.
;; ---------------------------------------------------------------------------

(deftest relocation-changes-only-the-event-not-the-reply-identity
  (testing "route relay: the appended reply's work-id / status / correlation / completion-time are untouched by relocation"
    (let [target    [:article/loaded {:slug "welcome"}]
          relay     (route-relay :route/article)
          completed (reply/complete (reply/map-target relay target) route-reply)
          ;; the completed event is [:route/parent-relay route-id [loader-event reply]]
          inner     (nth completed 2)
          delivered (peek inner)]
      (is (= (:work/id route-reply) (:work/id delivered)) "work-id rides unchanged through the relay")
      (is (= :ok (:status delivered)) "status rides unchanged")
      (is (= :route (:work/kind delivered)))
      ;; EP-0017 (rf2-ear61v) — the durable causal completion fact rides
      ;; unchanged through relocation; a relay that dropped or mutated it
      ;; (mis-threading completion time) would surface here.
      (is (= completion-time-ms (:completed-at delivered))
          ":completed-at (the EP-0017 causal completion fact) rides unchanged through the relay")))
  (testing "spawn :on-done: the appended reply's work-id / status / completion-time are untouched by routing"
    (let [target    [:auth/done {:flow :login}]
          on-done   (spawn-on-done :auth/main)
          completed (reply/complete (reply/map-target on-done target) spawn-reply)
          inner     (nth completed 2)
          delivered (peek inner)]
      (is (= (:work/id spawn-reply) (:work/id delivered)) "machine work-id rides unchanged")
      (is (= :ok (:status delivered)))
      (is (= :machine (:work/kind delivered)))
      (is (= completion-time-ms (:completed-at delivered))
          ":completed-at (the EP-0017 causal completion fact) rides unchanged through the spawn :on-done routing"))))

;; ---------------------------------------------------------------------------
;; The relocation transforms are PURE DATA→DATA event transforms (no host
;; handle) — a relayed target stays data-only-projectable. (A relocation
;; that captured a host handle would make the durable target non-data-only;
;; durable-target would reject it. Prove the relayed target's DURABLE
;; projection — ephemeral ::post stripped — is data-only.)
;; ---------------------------------------------------------------------------

(deftest a-relocated-target-projects-to-a-data-only-durable-target
  (testing "the mapped (relocated) target carries the ephemeral ::post fn — NOT data-only — but its durable projection strips it and is data-only"
    (let [relayed (reply/map-target (route-relay :route/article) [:article/loaded {:slug "welcome"}])]
      (is (false? (reply/data-only-target? relayed))
          "a relocated target carries the functor accumulator fn (not safe to persist verbatim)")
      (is (true? (reply/data-only-target? (reply/durable-target relayed)))
          "durable projection strips the ::post accumulator — the persisted target is data-only"))))
