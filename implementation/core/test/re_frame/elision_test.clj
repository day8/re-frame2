(ns re-frame.elision-test
  "Wire elision tests.

  EP-0025: durable app-db classification is declared by the commit-plane
  classification effects — a `reg-event` returns `:sensitive` / `:large`
  alongside `:db`, written into the per-frame elision registry under
  `:source :effect` (`elision/apply-classification-effects`). The durable
  `:sensitive` / `:large {:app-db …}` *frame annotation* is REMOVED.
  Schema-attached `{:sensitive? true}` / `{:large? true}` slot props are NOT
  a route into this registry. These tests seed declarations through the
  effect path; the walker behaviour (marker shape, sensitive-wins-over-large,
  idempotence, threshold interaction, frame isolation) is unchanged."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.elision :as elision]
            [re-frame.flows :as flows]
            [re-frame.frame :as frame]
            [re-frame.registrar :as registrar]
            [re-frame.schemas :as schemas]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.trace :as trace]))

(defn- install-class!
  "Seed the frame's elision registry through the EP-0025 commit-plane
  classification effect path — the same registry write a `reg-event`
  returning `:sensitive` / `:large` alongside `:db` performs
  (`elision/apply-classification-effects`, `:source :effect`). `sensitive` /
  `large` are vectors of `:rf/path` vectors. (EP-0025 removed the durable
  `:sensitive` / `:large {:app-db …}` frame annotation; tests seed via the
  surviving effect mechanism.)"
  ([sensitive large] (install-class! :rf/default sensitive large))
  ([frame-id sensitive large]
   (let [effects (cond-> {}
                   (seq sensitive) (assoc :sensitive (mapv vec sensitive))
                   (seq large)     (assoc :large     (mapv vec large)))]
     (frame/swap-runtime-db! frame-id
       (fn [rt] (elision/apply-classification-effects rt effects))))))

(defn- reset-runtime [test-fn]
  (registrar/clear-all!)
  (reset! frame/frames {})
  (flows/reset-flows!)
  (schemas/clear-schemas-by-frame!)
  (trace/clear-listeners!)
  (elision/clear-warning-cache!)
  (rf/init! plain-atom/adapter)
  (require 're-frame.elision :reload)
  (require 're-frame.schemas :reload)
  ;; `config` is a `defonce` (survives `:reload`); restore the documented
  ;; default so a configure tweak in one test does not leak into the next.
  (elision/configure! {:rf.size/threshold-bytes 16384})
  ;; EP-0002 (rf2-5q7um6 + rf2-gjq3ow): reg-app-schema + the zero-arity
  ;; populate-*-from-schemas! / declarations / sensitive-declarations
  ;; readers are context-required frame-local — an ambient call under no
  ;; scope raises :rf.error/no-frame-context. The zero-arity
  ;; `elide-wire-value` now resolves its frame from the carried scope and
  ;; FAILS CLOSED (whole-value `:rf/redacted`) when none is established
  ;; (rf2-gjq3ow). Pin :rf/default as the ambient scope so those ambient
  ;; registration / populate / elide calls carry a frame stamp and read the
  ;; same :rf/default registry — consistent end-to-end. Tests that want to
  ;; exercise the frameless-egress fail-closed branch unbind / override.
  (frame/ensure-default-frame!)
  (binding [frame/*current-frame* :rf/default]
    (test-fn)))

(use-fixtures :each reset-runtime)

(defn- collect-traces! [id]
  (let [acc (atom [])]
    (rf/register-listener! :trace id (fn [ev] (swap! acc conj ev)))
    acc))

(deftest walker-noop-on-small-values
  (is (= 42 (rf/elide-wire-value 42)))
  (is (= "hello" (rf/elide-wire-value "hello")))
  (is (= {:a 1 :b [2 3]} (rf/elide-wire-value {:a 1 :b [2 3]}))))

(deftest frame-large-path-emits-marker
  (install-class! [] [[:user :uploaded-pdf]])
  (let [decls (elision/declarations)
        out   (rf/elide-wire-value
                {:user {:name "Ada" :uploaded-pdf "<<5MB-blob>>"}})
        slot  (get-in out [:user :uploaded-pdf])]
    (is (= {:source :effect}
           (get decls [:user :uploaded-pdf])))
    (is (elision/marker? slot))
    (is (= [:user :uploaded-pdf]
           (get-in slot [:rf.size/large-elided :path])))
    (is (= :effect (get-in slot [:rf.size/large-elided :reason])))
    (is (= "Ada" (get-in out [:user :name])))))

(deftest include-large-bypasses-frame-elision
  (install-class! [] [[:big]])
  (is (elision/marker? (:big (rf/elide-wire-value {:big "blob"}))))
  (is (= "blob"
         (:big (rf/elide-wire-value {:big "blob"}
                                    {:rf.size/include-large? true})))))

(deftest unschema'd-large-value-warns-but-does-not-elide
  (let [big    (apply str (repeat 3000 "ABCDEFGH"))
        traces (collect-traces! :elision-test/unschema'd)
        out    (rf/elide-wire-value {:user {:photo big}})]
    (is (= big (get-in out [:user :photo]))
        "schema-less large values are not auto-elided")
    (let [warnings (filterv #(= :rf.warning/large-value-unschema'd
                                (:operation %))
                            @traces)]
      (is (= 1 (count warnings)))
      (is (= [:user :photo] (get-in (first warnings) [:tags :path])))
      (is (pos-int? (get-in (first warnings) [:tags :bytes])))
      (is (= "Classify this path large by returning `:large [[...]]` from the event that writes it (EP-0025 commit-plane classification effect, alongside `:db`)."
             (get-in (first warnings) [:tags :hint]))))
    (rf/unregister-listener! :trace :elision-test/unschema'd)))

(deftest unschema'd-large-warning-is-once-per-path
  (let [big    (apply str (repeat 3000 "ABCDEFGH"))
        traces (collect-traces! :elision-test/once)]
    (rf/elide-wire-value {:photo big})
    (rf/elide-wire-value {:photo big})
    (rf/elide-wire-value {:photo big})
    (is (= 1 (count (filter #(= :rf.warning/large-value-unschema'd
                                (:operation %))
                            @traces))))
    (rf/unregister-listener! :trace :elision-test/once)))

;; ---------------------------------------------------------------------------
;; Runtime size-threshold configuration (rf2-le2qu).
;;
;; Per API.md §Size-elision wire-boundary walker (L507) and §Configure keys
;; (`:elision`), the runtime auto-detect threshold for the
;; `:rf.warning/large-value-unschema'd` advisory is configurable, with
;; normative precedence:
;;
;;   explicit `:rf.size/threshold-bytes` opt  >  `(rf/configure! {:elision …})`  >  16384
;;
;; A threshold of 0 disables runtime auto-detect (only declared / schema
;; entries elide; the unschema'd-large warning never fires).
;; ---------------------------------------------------------------------------

(defn- count-unschema'd-warnings [traces]
  (count (filter #(= :rf.warning/large-value-unschema'd (:operation %))
                 @traces)))

(deftest default-threshold-is-16384
  ;; A string just under the documented 16384-byte default does not warn;
  ;; one just over does. Pins the default when neither opt nor configure set.
  (let [under   (apply str (repeat 16000 "x"))      ; ~16002 pr-str bytes? -> under cap
        over    (apply str (repeat 20000 "x"))
        traces  (collect-traces! :elision-test/default-thresh)]
    ;; `under` here is genuinely under 16384 bytes once quoted (16000 chars
    ;; + 2 quote bytes = 16002), so no warning.
    (rf/elide-wire-value {:a {:small under}})
    (is (= 0 (count-unschema'd-warnings traces))
        "value under the 16384 default does not trip the auto-detect warning")
    (rf/elide-wire-value {:b {:big over}})
    (is (= 1 (count-unschema'd-warnings traces))
        "value over the 16384 default trips the warning")
    (rf/unregister-listener! :trace :elision-test/default-thresh)))

(deftest configured-threshold-takes-effect
  ;; rf2-le2qu — the IMPL gap: `(rf/configure! {:elision {:rf.size/threshold-bytes N}})`
  ;; must lower (or raise) the runtime auto-detect threshold. A 100-byte
  ;; threshold makes a small string trip the warning that the 16384 default
  ;; would have ignored.
  (let [small  (apply str (repeat 300 "y"))         ; ~302 bytes — under default, over 100
        traces (collect-traces! :elision-test/configured)]
    ;; Baseline: under the default, no warning.
    (rf/elide-wire-value {:a {:s small}})
    (is (= 0 (count-unschema'd-warnings traces))
        "300-byte string is under the 16384 default — no warning")
    ;; Lower the configured threshold; now the same shape warns.
    (rf/configure! {:elision {:rf.size/threshold-bytes 100}})
    (rf/elide-wire-value {:b {:s small}})
    (is (= 1 (count-unschema'd-warnings traces))
        "after (configure :elision {:rf.size/threshold-bytes 100}) the 300-byte string warns")
    (rf/unregister-listener! :trace :elision-test/configured)))

(deftest configure-threshold-reaches-elision-config
  ;; The configure case stores the value where elision reads it — direct
  ;; assertion against the elision config, mirroring the :sub-cache shape
  ;; of configure-test.
  (rf/configure! {:elision {:rf.size/threshold-bytes 4096}})
  (is (= 4096 (:rf.size/threshold-bytes (elision/current-config)))
      "(configure :elision {:rf.size/threshold-bytes N}) reaches the elision config"))

(deftest explicit-opt-wins-over-configured
  ;; Precedence: an explicit `:rf.size/threshold-bytes` on the call wins
  ;; over the configured value. Configure a tiny threshold (would warn),
  ;; then pass a large explicit opt on the call (must NOT warn).
  (let [s      (apply str (repeat 300 "z"))          ; ~302 bytes
        traces (collect-traces! :elision-test/opt-wins)]
    (rf/configure! {:elision {:rf.size/threshold-bytes 50}})
    ;; Configured 50 alone would warn for a 302-byte string; but the
    ;; explicit per-call opt of 100000 raises the bar above it.
    (rf/elide-wire-value {:a {:s s}} {:rf.size/threshold-bytes 100000})
    (is (= 0 (count-unschema'd-warnings traces))
        "explicit :rf.size/threshold-bytes opt (100000) overrides configured (50) — no warning")
    ;; And conversely an explicit small opt wins over a large configured value.
    (rf/configure! {:elision {:rf.size/threshold-bytes 1000000}})
    (rf/elide-wire-value {:b {:s s}} {:rf.size/threshold-bytes 100})
    (is (= 1 (count-unschema'd-warnings traces))
        "explicit :rf.size/threshold-bytes opt (100) overrides configured (1000000) — warns")
    (rf/unregister-listener! :trace :elision-test/opt-wins)))

(deftest threshold-zero-disables-runtime-auto-detect
  ;; Per API.md §Configure keys — "0 disables runtime auto-detect (only
  ;; declared / schema entries elide)". With threshold 0, even a very large
  ;; unschema'd string never trips the warning.
  (let [big    (apply str (repeat 5000 "ABCDEFGH")) ; ~40002 bytes — well over default
        traces (collect-traces! :elision-test/zero)]
    (rf/configure! {:elision {:rf.size/threshold-bytes 0}})
    (rf/elide-wire-value {:a {:big big}})
    (is (= 0 (count-unschema'd-warnings traces))
        "threshold 0 disables runtime auto-detect — no warning even for a 40KB string")
    ;; Sanity: a per-call explicit 0 also disables, overriding a configured non-zero.
    (rf/configure! {:elision {:rf.size/threshold-bytes 100}})
    (rf/elide-wire-value {:b {:big big}} {:rf.size/threshold-bytes 0})
    (is (= 0 (count-unschema'd-warnings traces))
        "explicit threshold-bytes 0 opt disables runtime auto-detect for that call")
    (rf/unregister-listener! :trace :elision-test/zero)))

(deftest configured-threshold-does-not-affect-declared-elision
  ;; The threshold governs ONLY the runtime auto-detect warning for
  ;; unschema'd values — frame-declared `:large` `:app-db` paths still elide
  ;; to a marker regardless of threshold (including threshold 0).
  (install-class! [] [[:doc]])
  (rf/configure! {:elision {:rf.size/threshold-bytes 0}})
  (let [out (rf/elide-wire-value {:doc "x"})]
    (is (elision/marker? (:doc out))
        "frame-declared :large paths elide independent of the runtime threshold")))

(deftest frame-sensitive-path-redacts
  (install-class! [[:auth :password]] [])
  (let [out (rf/elide-wire-value {:auth {:username "ada"
                                         :password "shh"}})]
    (is (= "ada" (get-in out [:auth :username])))
    (is (= :rf/redacted (get-in out [:auth :password])))
    (is (= "shh"
           (get-in (rf/elide-wire-value
                     {:auth {:password "shh"}}
                     {:rf.size/include-sensitive? true})
                   [:auth :password])))))

(deftest frame-sensitive-position-precise-redacts
  ;; rf2-ss06u.4 / rf2-4q681i — a position-pinned `:rf/path` (e.g.
  ;; `[:point 0]`) declares an exact vector index; the runtime elision walk
  ;; descends the value (a vector) through its literal-index fork
  ;; (`fork-index-paths`, `(conj c i)`) and matches that exact position —
  ;; redacting ONLY the declared element while the non-sensitive sibling
  ;; rides verbatim. Under EP-0015 §8 app-db classification is frame-owned,
  ;; so the position is declared directly as a `:rf/path`; the walker's
  ;; coordinate-system consistency (declared index ↔ runtime indexed path)
  ;; is the property under test, independent of how the path was authored.
  (install-class! [[:point 0]] [])
  (let [out (rf/elide-wire-value {:point ["the-secret" 42]})]
    (is (= :rf/redacted (get-in out [:point 0]))
        "the declared-sensitive element 0 is redacted")
    (is (= 42 (get-in out [:point 1]))
        "the non-sensitive sibling element 1 rides verbatim — no over-redaction")))

(deftest sensitive-wins-over-large
  (install-class! [[:secret-pdf]] [[:secret-pdf]])
  (let [out (rf/elide-wire-value {:secret-pdf "payload"})]
    (is (= :rf/redacted (:secret-pdf out)))
    (is (not (elision/marker? (:secret-pdf out))))))

(deftest marker-options
  (install-class! [] [[:b]])
  (let [out    (rf/elide-wire-value {:b "X"}
                                    {:rf.size/include-digests? true
                                     :as-of-epoch 42})
        marker (get-in out [:b :rf.size/large-elided])]
    (is (= [:rf.elision/at [:b] :as-of-epoch 42] (:handle marker)))
    (is (= :string (:type marker)))
    (is (= :effect (:reason marker)))
    (is (string? (:digest marker)))))

;; ---------------------------------------------------------------------------
;; EP-0025: the LARGE value-match engine (`large-value-marker-map` /
;; `redact-matching-large-values` / `redact-derived-large-values`) and its
;; sensitive dual are REMOVED — value-match of a blob re-keyed into a derived
;; tree at a non-app-db position is "propagation/taint by another name" the EP
;; disclaims. Large/sensitive PATH-based elision (`elide-wire-value`) is kept and
;; covered above; derived-tree egress is now path-based / fail-open (covered by
;; the projection_cljs_test derived-tree suite).
;; ---------------------------------------------------------------------------

(deftest walker-is-idempotent-on-large-marker
  ;; rf2-fq8ep — the walker recognises its own `:rf.size/large-elided`
  ;; marker shape at a `:large?`-declared path and passes it through
  ;; unchanged on a re-projection pass. Without the guard, the marker
  ;; map itself satisfies `(map? v)` at the same declared path on the
  ;; next walk, and the walker substituted a fresh marker whose
  ;; `:bytes` reflected the printed length of the previous marker —
  ;; not the original payload — which broke fingerprint-based dedup
  ;; for forwarder pipelines that double-projected.
  (install-class! [] [[:doc :body]])
  (let [input  {:doc {:body (apply str (repeat 2000 "X"))}}
        once   (rf/elide-wire-value input)
        twice  (rf/elide-wire-value once)
        thrice (rf/elide-wire-value twice)]
    (is (elision/marker? (get-in once [:doc :body]))
        "first pass substitutes a marker at the large slot")
    (is (= once twice)
        "second pass is byte-identical — the walker passed the marker
         through unchanged rather than re-marking it")
    (is (= once thrice)
        "third pass remains byte-identical — large-marker substitution
         is irreversible across passes")))

(deftest walker-idempotence-respects-include-large
  ;; The marker passthrough is gated on the same `:include-large?`
  ;; branch that produces the marker. With `:include-large? true`, the
  ;; walker descends through the marker map (because the substitution
  ;; branch is bypassed) — caller opted in to see the raw payload, so
  ;; the marker is just an opaque map at that point. Pinning so a
  ;; future refactor does not move the guard outside the gate.
  (install-class! [] [[:doc :body]])
  (let [input  {:doc {:body (apply str (repeat 2000 "X"))}}
        once   (rf/elide-wire-value input)
        opened (rf/elide-wire-value once {:rf.size/include-large? true})]
    (is (elision/marker? (get-in once [:doc :body])))
    (is (= once opened)
        ":include-large? true descends into the marker map but the
         marker's structure is unchanged — the walker recurses through
         a map whose only key (`:rf.size/large-elided`) sits at a path
         that is not itself `:large?`-declared")))

(deftest nested-classification
  (install-class! [[:root :a :b :token]] [[:root :a :b :c]])
  (is (contains? (elision/declarations) [:root :a :b :c]))
  (is (contains? (elision/sensitive-declarations) [:root :a :b :token]))
  (is (= {:source :effect}
         (get (elision/declarations) [:root :a :b :c]))))

(deftest clear-large-effect-removes-declaration
  ;; EP-0025: the commit-plane classification effects are additive per axis;
  ;; a path is un-classified by the `:clear-large` effect (the set/unset
  ;; symmetry of the axis), NOT by an absent-key replace (the frame
  ;; annotation's replace semantics are gone).
  (install-class! [] [[:user :pdf]])
  (is (contains? (elision/declarations) [:user :pdf]))
  (frame/swap-runtime-db! :rf/default
    (fn [rt] (elision/apply-classification-effects rt {:clear-large [[:user :pdf]]})))
  (is (not (contains? (elision/declarations) [:user :pdf]))))

(deftest registries-are-frame-isolated
  (frame/reg-frame :elision-test/other {})
  (install-class! :rf/default [] [[:blob]])
  (install-class! :elision-test/other [] [])
  (is (contains? (elision/declarations :rf/default) [:blob]))
  (is (not (contains? (elision/declarations :elision-test/other) [:blob]))))

(deftest streamed-cascades-elide-per-element-frame
  ;; rf2-1we9fa defect 2 — the streaming subscribe drain walks several
  ;; frames' cascade bundles in one tick (all-frame streams, or a filter
  ;; frame != the operating frame). Per EP-0015 sensitive/large
  ;; declarations are PER FRAME, so eliding each bundle MUST resolve the
  ;; `:frame` opt from THAT bundle's own frame — NOT a single operating
  ;; frame applied to every bundle. The sharp edge is UNDER-redaction: a
  ;; frame-A value A declares sensitive but the operating frame B does not
  ;; would leak across the off-box MCP→LLM boundary.
  (frame/reg-frame :elision-test/frame-a {})
  (frame/reg-frame :elision-test/frame-b {})
  ;; Frame A declares :secret-a sensitive; frame B declares :secret-b
  ;; sensitive. Neither marks the OTHER frame's slot.
  (install-class! :elision-test/frame-a [[:secret-a]] [])
  (install-class! :elision-test/frame-b [[:secret-b]] [])
  ;; Two bundles streamed in one tick, each carrying both slots, each
  ;; stamped with its own frame (as group-cascades-with-events emits).
  (let [bundle-a {:frame :elision-test/frame-a
                  :secret-a "A-private" :secret-b "A-public"}
        bundle-b {:frame :elision-test/frame-b
                  :secret-a "B-public" :secret-b "B-private"}
        ;; Mirror the drain-form walk: resolve :frame PER ELEMENT off the
        ;; bundle's own :frame slot, then elide. (current-frame fallback is
        ;; exercised separately by the existing frameless tests.)
        walk     (fn [bundles]
                   (mapv (fn [x]
                           (rf/elide-wire-value
                             x {:frame (:frame x)}))
                         bundles))
        [out-a out-b] (walk [bundle-a bundle-b])]
    (testing "each bundle redacts ONLY its own frame's declared-sensitive slot"
      (is (= :rf/redacted (:secret-a out-a))
          "frame A's bundle redacts :secret-a (A declared it sensitive)")
      (is (= "A-public" (:secret-b out-a))
          "frame A's bundle leaves :secret-b verbatim (A did not declare it)")
      (is (= :rf/redacted (:secret-b out-b))
          "frame B's bundle redacts :secret-b (B declared it sensitive)")
      (is (= "B-public" (:secret-a out-b))
          "frame B's bundle leaves :secret-a verbatim (B did not declare it)"))
    (testing "the buggy single-operating-frame walk UNDER-redacts the other frame"
      ;; Applying frame A (the would-be operating frame) to BOTH bundles
      ;; leaks frame B's :secret-b — the exact off-box leak the per-element
      ;; fix prevents.
      (let [buggy (mapv #(rf/elide-wire-value
                           % {:frame :elision-test/frame-a})
                        [bundle-a bundle-b])]
        (is (= "B-private" (:secret-b (second buggy)))
            "operating-frame-for-every-bundle leaks frame B's secret — the defect")))))

;; ---------------------------------------------------------------------------
;; Sub-cache direct-read wire-egress posture (rf2-0hert / rf2-vflrg).
;;
;; Per Tool-Pair §"Direct-read privacy posture for sub-cache and get-path",
;; a pair-shaped tool that ships a `sub-cache` surface MUST route the
;; returned `{query-v {:value v :ref-count n}}` map through
;; `elide-wire-value` before egress. The re-frame2-pair-mcp `snapshot` tool's
;; `:sub-cache` slice does this (per `tools/re-frame2-pair-mcp/src/.../tools/snapshot.cljs`).
;;
;; These regressions pin the framework half of the contract: the walker
;; honours sensitive / large declarations against the walked path
;; whatever the input shape — sub-cache-shaped data is no different from
;; app-db-shaped data, the walker just compares the walked path to the
;; declaration table. A future refactor of the walker that special-cases
;; map-shape will break here as well as in production.
;; ---------------------------------------------------------------------------

(deftest sub-cache-shape-walker-redacts-declared-sensitive-path
  ;; The sub-cache slice has shape `{[query-v] {:value v :ref-count n}}`.
  ;; A sensitive declaration whose path matches the walker's reach into
  ;; the cached `:value` redacts to `:rf/redacted`. The declaration path
  ;; uses the actual walked-from-root path the walker traverses (the
  ;; query-v key, then `:value`, then the slot inside the cached
  ;; projection).
  (let [path     [[:auth/token] :value :token]
        frame-id :rf/default
        sub-cache {[:auth/token]   {:value {:token "shh-secret"} :ref-count 1}
                   [:cart/total]   {:value 42 :ref-count 2}}]
    ;; Install the sensitive declaration directly into the live registry
    ;; via the internal `swap-elision-slot!` helper — the same code path
    ;; `populate-sensitive-from-schemas!` uses, just without the
    ;; schema-extraction step (sub-cache content has no natural app-
    ;; schema path).
    (re-frame.elision/swap-elision-slot!
       frame-id
       (fn [reg]
         (assoc reg :sensitive-declarations
                {path {:sensitive? true :source :test}})))
    (let [out (rf/elide-wire-value sub-cache {:frame frame-id})]
      (is (= :rf/redacted (get-in out [[:auth/token] :value :token]))
          "Declared sensitive path inside the sub-cache `:value` redacts on egress")
      (is (= 42 (get-in out [[:cart/total] :value]))
          "Non-sensitive sub-cache entries pass through unchanged")
      (is (= 1 (get-in out [[:auth/token] :ref-count]))
          ":ref-count metadata is untouched"))
    ;; Opt-in: `:rf.size/include-sensitive? true` passes the raw value
    ;; through — the same escape hatch get-path / snapshot expose at the
    ;; MCP layer.
    (let [out (rf/elide-wire-value sub-cache
                                   {:frame frame-id
                                    :rf.size/include-sensitive? true})]
      (is (= "shh-secret" (get-in out [[:auth/token] :value :token]))
          "include-sensitive? true ⇒ sensitive sub-cache slots pass through verbatim"))))

(deftest sub-cache-shape-walker-emits-large-marker-on-declared-path
  ;; A declared `:large?` path inside a sub-cache `:value` emits the
  ;; `:rf.size/large-elided` marker. The marker's `:path` is the actual
  ;; walked-from-root path so the agent's follow-up `get-path` can drill
  ;; in directly. Mirrors the frame-declared :large? coverage above,
  ;; but with sub-cache-shaped input.
  (let [path     [[:user/uploaded] :value :pdf]
        frame-id :rf/default
        sub-cache {[:user/uploaded] {:value {:pdf "<<5MB-blob>>"} :ref-count 1}}]
    (re-frame.elision/swap-elision-slot!
       frame-id
       (fn [reg]
         (assoc reg :declarations
                {path {:large? true :source :test :hint "Upload preview"}})))
    (let [out  (rf/elide-wire-value sub-cache {:frame frame-id})
          slot (get-in out [[:user/uploaded] :value :pdf])]
      (is (elision/marker? slot)
          "Declared large path inside sub-cache `:value` emits the size marker")
      (is (= path (get-in slot [:rf.size/large-elided :path]))
          "Marker carries the actual walked path so the agent can re-fetch")
      (is (= "Upload preview" (get-in slot [:rf.size/large-elided :hint]))))))

(deftest sub-cache-shape-walker-passes-through-when-no-declarations
  ;; The walker is a no-op on sub-cache content with no matching
  ;; declarations — routing the slice through `elide-wire-value` does
  ;; not perturb the wire shape. This pins the "uniform direct-read
  ;; surface, identity for typical content" guarantee.
  (let [sub-cache {[:cart/total] {:value 42 :ref-count 2}
                   [:user/name]  {:value "Ada" :ref-count 1}}]
    (is (= sub-cache (rf/elide-wire-value sub-cache))
        "No declarations ⇒ walker returns the sub-cache shape verbatim")))

;; ---------------------------------------------------------------------------
;; EP-0002 (rf2-gjq3ow) — wire-egress fails CLOSED when no frame is carried.
;;
;; `elide-wire-value` resolves its frame from the carried stamp: explicit
;; `:frame` opt (*override*) → the in-effect scope (*scope*). There is no
;; `:rf/default` floor. With no carried frame the per-frame elision registry
;; is unreachable, so the whole value is conservatively redacted to
;; `:rf/redacted` rather than shipped verbatim under no policy (which would
;; be the silent leak the contract abolishes). `:rf.size/include-sensitive?
;; true` is the deliberate opt-out — the caller waived sensitive redaction,
;; so the value rides through (identity walk against an empty policy).
;; ---------------------------------------------------------------------------

(deftest frameless-egress-fails-closed
  ;; The fixture pins `*current-frame* :rf/default`; unbind it to model a
  ;; token that crossed an async / tool boundary and lost its stamp.
  (binding [frame/*current-frame* nil]
    (is (= :rf/redacted (rf/elide-wire-value {:a 1 :b [2 3]}))
        "no carried frame ⇒ whole value redacted (no :rf/default borrow)")
    (is (= :rf/redacted (rf/elide-wire-value 42))
        "fail-closed applies to scalars too — nothing escapes without a frame")
    (is (= :rf/redacted (rf/elide-wire-value {:secret "shh"} {}))
        "an explicit empty opts map does not supply a frame ⇒ still fail-closed")))

(deftest frameless-egress-explicit-frame-override-resolves
  ;; An explicit `:frame` override resolves regardless of the ambient scope:
  ;; the contract's *override* tier. With no scope but an explicit frame,
  ;; the named frame's (empty) registry applies — identity, not fail-closed.
  (binding [frame/*current-frame* nil]
    (is (= {:a 1} (rf/elide-wire-value {:a 1} {:frame :rf/default}))
        "explicit :frame override resolves a known frame ⇒ its policy applies")))

(deftest frameless-egress-include-sensitive-opt-out
  ;; `:rf.size/include-sensitive? true` is the deliberate opt-out: a caller
  ;; that has waived sensitive redaction gets the value verbatim even with
  ;; no carried frame (identity walk against an empty policy).
  (binding [frame/*current-frame* nil]
    (is (= {:a 1 :b [2 3]}
           (rf/elide-wire-value {:a 1 :b [2 3]}
                                {:rf.size/include-sensitive? true}))
        "include-sensitive? true ⇒ frameless value rides verbatim (opt-out)")))

;; ---------------------------------------------------------------------------
;; EP-0015 issue 1 (rf2-t55hxg.18) — an explicit / carried frame-id that
;; cannot RESOLVE to a live frame fails CLOSED, identical to the frameless
;; case. Before the fix `elide-wire-value` only checked `(some? frame-id)`:
;; a non-nil id that named a never-registered or destroyed frame slipped
;; into the policy walk, where `registry-of` returned nil → empty `:large` /
;; `:sensitive` tables → an IDENTITY walk that shipped every value verbatim
;; under NO policy. Spec 015 §Direct reads and fail-closed frame resolution:
;; an unresolved frame must fail closed, never fall through to a permissive
;; walk and never synthesize `:rf/default`.
;; ---------------------------------------------------------------------------

(deftest never-registered-explicit-frame-fails-closed
  ;; An explicit `:frame` opt naming a frame that was never registered is
  ;; non-nil but unresolvable ⇒ redact the whole value (do NOT pass through
  ;; under an empty policy).
  (binding [frame/*current-frame* nil]
    (is (= :rf/redacted
           (rf/elide-wire-value {:a 1 :b [2 3]}
                                {:frame :elision-test/never-registered}))
        "explicit unknown frame ⇒ whole value redacted (no empty-policy leak)")
    (is (= :rf/redacted
           (rf/elide-wire-value 42 {:frame :elision-test/never-registered}))
        "fail-closed applies to scalars under an unknown explicit frame too")))

(deftest destroyed-explicit-frame-fails-closed
  ;; A frame that WAS registered (and could have carried a real `:large` /
  ;; `:sensitive` policy) but has since been destroyed is no longer
  ;; resolvable ⇒ fail closed. `frame/frame` returns nil for a destroyed id.
  (frame/reg-frame :elision-test/doomed {})
  (frame/destroy-frame! :elision-test/doomed)
  (binding [frame/*current-frame* nil]
    (is (= :rf/redacted
           (rf/elide-wire-value {:secret "shh"}
                                {:frame :elision-test/doomed}))
        "destroyed explicit frame ⇒ whole value redacted")))

(deftest unresolvable-frame-redacts-value-that-would-be-public-under-known-frame
  ;; ADVERSARIAL — the value here carries NO sensitive declaration, so under
  ;; a KNOWN frame it rides through verbatim (the value is "public"). The
  ;; safety property is that the SAME value, projected under a frame that
  ;; cannot be resolved, must NOT leak: fail-closed redacts the whole value
  ;; regardless of what its policy WOULD have been under a live frame.
  (binding [frame/*current-frame* nil]
    ;; Baseline: under the live :rf/default frame (no declarations) the value
    ;; passes through verbatim — it is "public".
    (is (= {:profile {:name "Ada"}}
           (rf/elide-wire-value {:profile {:name "Ada"}}
                                {:frame :rf/default}))
        "baseline: under a KNOWN frame with no policy the value is public")
    ;; Same value, unresolvable frame ⇒ redacted whole. A would-be-public
    ;; value still fails closed when the frame can't be resolved.
    (is (= :rf/redacted
           (rf/elide-wire-value {:profile {:name "Ada"}}
                                {:frame :elision-test/never-registered}))
        "the would-be-public value redacts whole under an unresolvable frame")))

(deftest unresolvable-frame-include-sensitive-opt-out-still-identity
  ;; The deliberate opt-out is unchanged: `:rf.size/include-sensitive? true`
  ;; against an unresolvable frame walks the value under an empty (no-frame)
  ;; policy — the caller explicitly waived redaction, so the value rides
  ;; through. This keeps the escape hatch symmetric with the frameless case.
  (binding [frame/*current-frame* nil]
    (is (= {:a 1 :b [2 3]}
           (rf/elide-wire-value {:a 1 :b [2 3]}
                                {:frame :elision-test/never-registered
                                 :rf.size/include-sensitive? true}))
        "include-sensitive? true ⇒ unresolvable-frame value rides verbatim (opt-out)")))

(deftest stale-carried-scope-frame-fails-closed
  ;; The carried scope (`*current-frame*`) can outlive the frame it names —
  ;; e.g. a `frame-bound-fn` closure fires after the frame was destroyed.
  ;; A stale scope id that no longer resolves to a live frame must fail
  ;; closed exactly like an explicit unknown `:frame` opt (the same
  ;; `frame/frame` liveness check governs both resolution tiers).
  (frame/reg-frame :elision-test/stale {})
  (frame/destroy-frame! :elision-test/stale)
  (binding [frame/*current-frame* :elision-test/stale]
    (is (= :rf/redacted
           (rf/elide-wire-value {:a 1}))
        "stale carried scope naming a destroyed frame ⇒ fail closed")))

;; ---------------------------------------------------------------------------
;; Collection-nested frame-declared elision at direct-read egress
;; (rf2-wm9kp).
;;
;; A frame `:sensitive {:app-db [[:items :token]]}` declares an INDEX-FREE
;; path for positional/keyed containers — `[:items :token]` (NOT `[:items 0
;; :token]`); a `:map-of` value declares `[:by-id :secret]` (NOT `[:by-id "a"
;; :secret]`). The wire-elision walker walks a RUNTIME value, so it sees the
;; indexed/keyed paths. Before rf2-wm9kp the walker matched only EXACT
;; concrete runtime paths, so the declaration never matched and the secret
;; crossed the direct-read MCP boundary (`get-app-db` / `get-path` /
;; `snapshot`) RAW. The fix threads a candidate declaration-coordinate set
;; that drops vector indices / map-of keys, so the index-free declaration
;; matches the indexed/keyed runtime path. This walker-matching behaviour is
;; independent of how the path was declared — EP-0015 §8 makes the declared
;; path frame-owned, but the index-free matching is the same.

(deftest collection-nested-sensitive-vector-of-maps-redacts
  ;; rf2-wm9kp Finding 1 (the headline leak). WITHOUT the fix
  ;; `(rf/elide-wire-value {:items [{:token "SECRET"}]})` returned the
  ;; secret verbatim because decl `[:items :token]` did not match runtime
  ;; `[:items 0 :token]`.
  (install-class! [[:items :token]] [])
  (let [out (rf/elide-wire-value {:items [{:token "SECRET"}
                                          {:token "SECRET2"}]})]
    (is (= :rf/redacted (get-in out [:items 0 :token]))
        "vector-element sensitive slot redacts at direct-read egress")
    (is (= :rf/redacted (get-in out [:items 1 :token]))
        "every vector element redacts, not just index 0"))
  ;; Opt-in escape hatch still passes the raw value (the get-path /
  ;; snapshot `:rf.size/include-sensitive? true` path).
  (is (= "SECRET"
         (get-in (rf/elide-wire-value {:items [{:token "SECRET"}]}
                                      {:rf.size/include-sensitive? true})
                 [:items 0 :token]))
      "include-sensitive? true ⇒ collection-nested sensitive passes raw"))

(deftest collection-nested-sensitive-map-of-redacts
  ;; rf2-wm9kp Finding 1 — `:map-of` value-map sensitive slot. Decl
  ;; `[:by-id :secret]` must match runtime `[:by-id "a" :secret]`.
  (install-class! [[:by-id :secret]] [])
  (let [out (rf/elide-wire-value {:by-id {"a" {:secret "SECRET"}
                                          "b" {:secret "SECRET2"}}})]
    (is (= :rf/redacted (get-in out [:by-id "a" :secret])))
    (is (= :rf/redacted (get-in out [:by-id "b" :secret]))
        "map-of value-map sensitive slot redacts for every key")))

(deftest collection-nested-sensitive-sequential-redacts
  ;; rf2-wm9kp Finding 1 — a sequential of maps (the runtime value can
  ;; arrive as a lazy seq / list, not just a vector).
  (install-class! [[:logs :pw]] [])
  (let [out (rf/elide-wire-value {:logs (list {:pw "SECRET"})})]
    (is (= :rf/redacted (-> out :logs vec (get-in [0 :pw])))
        "sequential-element sensitive slot redacts")))

(deftest collection-nested-sensitive-set-of-maps-redacts
  ;; rf2-wm9kp Finding 1 — `:set` element maps descend at the same base
  ;; path (no positional segment), same as vector/sequential.
  (install-class! [[:tags :s]] [])
  (let [out (rf/elide-wire-value {:tags #{{:s "SECRET"}}})]
    (is (= :rf/redacted (:s (first (:tags out))))
        "set-element sensitive slot redacts")))

(deftest collection-nested-sensitive-mixed-map-vector-map-redacts
  ;; rf2-wm9kp Finding 1 — mixed map → vector → map nesting. Decl
  ;; `[:root :rows :pw]` matches runtime `[:root :rows N :pw]`.
  (install-class! [[:root :rows :pw]] [])
  (let [out (rf/elide-wire-value {:root {:rows [{:pw "SECRET"} {:pw "SECRET2"}]}})]
    (is (= :rf/redacted (get-in out [:root :rows 0 :pw])))
    (is (= :rf/redacted (get-in out [:root :rows 1 :pw])))))

(deftest collection-nested-no-over-redaction-of-sibling-slots
  ;; rf2-wm9kp Finding 1 — the matcher must be PRECISE: a non-sensitive
  ;; sibling leaf inside the same collection element map rides verbatim.
  ;; The candidate-path fork must not blanket-redact the element.
  (install-class! [[:items :token]] [])
  (let [out (rf/elide-wire-value {:items [{:token "SECRET" :name "Ada"}]})]
    (is (= :rf/redacted (get-in out [:items 0 :token])))
    (is (= "Ada" (get-in out [:items 0 :name]))
        "sibling non-sensitive slot is NOT over-redacted")))

(deftest collection-nested-no-over-redaction-at-non-declared-position
  ;; rf2-wm9kp follow-up — the candidate-coordinate match must be
  ;; POSITION-PRECISE, not a free-floating suffix/anywhere match. A decl
  ;; `[:auth :password]` must NOT redact the SAME key-sequence
  ;; `:auth :password` when it sits at a DIFFERENT, non-declared position
  ;; (nested under leading named map slots `:tags :some-other-slot`). The
  ;; empty seed candidate must not be allowed to skip the leading named
  ;; slots and resume the declaration deeper in the tree.
  (install-class! [[:auth :password]] [])
  (let [out (rf/elide-wire-value
              {;; the DECLARED position — must redact
               :auth {:username "ada" :password "shh"}
               ;; a coincidentally same-named subtree at a NON-declared
               ;; position — must ride through verbatim
               :tags {:some-other-slot {:auth {:password "scoped-marker"}}}})]
    (is (= :rf/redacted (get-in out [:auth :password]))
        "the declared [:auth :password] position still redacts")
    (is (= "ada" (get-in out [:auth :username]))
        "the non-sensitive sibling at the declared position is untouched")
    (is (= "scoped-marker"
           (get-in out [:tags :some-other-slot :auth :password]))
        "the same :auth :password key-sequence at a DIFFERENT position is
         NOT over-redacted — the match is position-precise, not free-floating")))

(deftest collection-nested-map-of-skip-requires-started-match
  ;; rf2-wm9kp follow-up — the `:map-of`-key SKIP is only granted to a
  ;; candidate that has ALREADY begun matching the declaration (a non-empty
  ;; partial prefix). This pins that the legit map-of path keeps working
  ;; (`[:by-id]` is a non-empty partial match, so it skips the key `"a"`),
  ;; while a leaf at a NON-declared top-level map key never matches.
  (install-class! [[:by-id :secret]] [])
  (let [out (rf/elide-wire-value
              {:by-id   {"a" {:secret "SECRET"}}
               ;; `:secret` here is a top-level map slot, NOT under :by-id —
               ;; decl [:by-id :secret] must not float to match it.
               :secret  "TOP-LEVEL-NOT-DECLARED"})]
    (is (= :rf/redacted (get-in out [:by-id "a" :secret]))
        "the declared map-of-nested :secret redacts (skip after started match)")
    (is (= "TOP-LEVEL-NOT-DECLARED" (get out :secret))
        "a same-named leaf at a non-declared position is not over-redacted")))

(deftest collection-nested-literal-index-declaration-redacts
  ;; rf2-wm9kp follow-up — a declaration may carry a CONCRETE integer index
  ;; (`[:tokens 0]`, declared directly against the indexed runtime position
  ;; rather than schema-derived index-free). The candidate-coordinate match
  ;; must still fire for it: the seq/vector descent forks the literal-index
  ;; interpretation `(conj c i)`. Pins the origin behaviour (exact indexed
  ;; path match) that the index-free coordinate threading must not regress
  ;; (the story-mcp derived-tree scrub relies on this exact match).
  (let [frame-id :rf/default]
    (re-frame.elision/swap-elision-slot!
      frame-id
      (fn [reg]
        (assoc reg :sensitive-declarations
               {[:tokens 0] {:sensitive? true :source :test}})))
    ;; seq form (list) — the shape story-mcp's derived-tree scrub relies on
    (let [out (rf/elide-wire-value
                {:tokens (list "SECRET" "public") :other "x"}
                {:frame frame-id})]
      (is (= :rf/redacted (-> out :tokens vec (get 0)))
          "literal-index decl [:tokens 0] redacts the indexed seq element")
      (is (= "public" (-> out :tokens vec (get 1)))
          "the non-declared sibling index rides through verbatim"))
    ;; vector form — same literal-index decl matches the vector element
    (let [out (rf/elide-wire-value
                {:tokens ["SECRET" "public"]}
                {:frame frame-id})]
      (is (= :rf/redacted (get-in out [:tokens 0])))
      (is (= "public" (get-in out [:tokens 1]))
          "only the literally-declared index redacts"))))

(deftest collection-nested-large-emits-marker-with-runtime-path
  ;; rf2-wm9kp Finding 1 (symmetry) — a `:large` slot nested under a
  ;; collection element map emits the `:rf.size/large-elided` marker, and
  ;; the marker's `:path` is the CONCRETE indexed runtime path so a
  ;; follow-up `get-path` lands on the exact element.
  (install-class! [] [[:docs :blob]])
  (let [out  (rf/elide-wire-value {:docs [{:blob "<<5MB-blob>>"}]})
        slot (get-in out [:docs 0 :blob])]
    (is (elision/marker? slot)
        "collection-nested :large slot emits a size marker")
    (is (= [:docs 0 :blob] (get-in slot [:rf.size/large-elided :path]))
        "marker :path is the concrete indexed runtime path (re-fetchable)")
    (is (= :effect (get-in slot [:rf.size/large-elided :reason])))))

(deftest collection-nested-sensitive-wins-over-large
  ;; rf2-wm9kp Finding 1 (symmetry) — when a collection-nested slot is
  ;; BOTH `:large` and `:sensitive`, sensitive wins (redact, no marker),
  ;; same precedence as the top-level `sensitive-wins-over-large` case.
  (install-class! [[:vault :k]] [[:vault :k]])
  (let [out  (rf/elide-wire-value {:vault [{:k "payload"}]})
        slot (get-in out [:vault 0 :k])]
    (is (= :rf/redacted slot))
    (is (not (elision/marker? slot))
        "sensitive suppresses the large marker even when nested in a vector")))


;; EP-0025: the derived-tree value-match egress engine (`redact-derived-slots`,
;; `sensitive-value-set`, `collect-sensitive-values`, the non-unique-secret
;; guard, and the large/sensitive value-match duals) is REMOVED — it is
;; "propagation/taint by another name" the EP disclaims. Path-based elision
;; (`elide-wire-value`) is the only egress walker and is covered above; the
;; derived-tree record's now-path-based / fail-open egress is covered by the
;; projection_cljs_test derived-tree suite.
