(ns re-frame2-pair-mcp.descriptor-privacy-knob-parity-test
  "Descriptor ↔ handler privacy-knob parity gate.

  THE CONTRACT. Several egress tools' handlers consume privacy/egress
  knobs (`:include-sensitive`, `:elision`) that govern whether app-db /
  sub / epoch values cross the LLM-facing wire verbatim. Every descriptor
  declares `:additionalProperties false`, so a schema-aware client would
  never offer (or could reject) a supported knob that the descriptor
  omits, and agents would be taught an INCOMPLETE privacy boundary — the
  live `tools/call` behaviour diverging from `tools/list` / the generated
  `tool-descriptors.edn`. Every handler-consumed knob must therefore be
  published on the descriptor.

  THE GATE. For every tool whose handler reads a privacy knob, the knob
  MUST appear as a boolean property on that tool's descriptor
  `:inputSchema :properties` — both on the raw registry descriptor AND on
  the spliced `tools/list` surface (`tool-descriptors-js`). The expected
  set is pinned here from the handler source, so a FUTURE egress-control
  addition that wires a handler knob without publishing it trips this test
  rather than silently shipping an unadvertised privacy control.

  WHY A STATIC EXPECTATION (not a source scrape). The handlers read the
  knobs via `(wire/arg args :include-sensitive)` / `(args/parse-bool-arg
  raw-args :elision)` — there is no machine-readable registry of which
  knob a handler consumes. The authoritative cross-check is therefore a
  curated table derived from the handler source, ratcheted here. The
  drift-check (`npm run check:descriptors`) independently pins that the
  generated `tool-descriptors.edn` agrees with these descriptors, so the
  manifest `:input-keys` are covered transitively; the
  `descriptors-self-consistency` test below also asserts the knob rides
  through the `clj->js` projection that feeds the wire."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [applied-science.js-interop :as j]
            [re-frame2-pair-mcp.tools :as tools]
            [re-frame2-pair-mcp.tools.registry :as registry]))

;; ---------------------------------------------------------------------------
;; Authoritative table — tool -> privacy knobs its HANDLER consumes.
;;
;; Source of truth (verify against the cited handler if you change this):
;;   dispatch     :include-sensitive  — dispatch.cljs (`incl?`, :trace/:settle epoch egress)
;;   subscribe    :include-sensitive  — subscribe.cljs (`incl?`, top-level sensitive drop)
;;                :elision            — subscribe.cljs (`elision?`, per-event value walk)
;;   record       :include-sensitive  — record.cljs   (`incl?`,    :elide-opts)
;;                :elision            — record.cljs   (`elision?`, :elide-opts)
;;   watch-until  :include-sensitive  — watch_until.cljs (`incl?`,    :sample / :last-sample)
;;                :elision            — watch_until.cljs (`elision?`, :sample / :last-sample)
;;
;; (snapshot / get-path / read-sub / list-subscriptions / dispatch-dry-run
;; publish their knobs and are covered by the raw-state conformance
;; fixtures; they are included here so the gate's coverage of the full
;; structured-egress surface is explicit and a regression on them also
;; trips.)
;; ---------------------------------------------------------------------------

(def ^:private handler-consumed-knobs
  {"dispatch"           #{:include-sensitive}
   "subscribe"          #{:include-sensitive :elision}
   "record"             #{:include-sensitive :elision}
   "watch-until"        #{:include-sensitive :elision}
   "dispatch-dry-run"   #{:include-sensitive :elision}
   "snapshot"           #{:include-sensitive :elision}
   "get-path"           #{:include-sensitive :elision}
   "read-sub"           #{:include-sensitive :elision}
   "list-subscriptions" #{:include-sensitive :elision}})

(def ^:private descriptor-by-name
  (into {} (map (juxt :name identity)) registry/tool-descriptors))

(defn- props [descriptor]
  (get-in descriptor [:inputSchema :properties]))

(deftest every-consumed-knob-is-published-on-the-raw-descriptor
  (testing "each handler-consumed privacy knob is a boolean property on its descriptor"
    (doseq [[tool knobs] handler-consumed-knobs
            :let [descriptor (descriptor-by-name tool)
                  p          (props descriptor)]]
      (is (some? descriptor) (str "no descriptor registered for tool " tool))
      (doseq [knob knobs]
        (is (contains? p knob)
            (str tool " descriptor must publish " knob
                 " — its handler consumes it, but :additionalProperties false "
                 "would reject the supported knob (rf2-mmd7o6)"))
        (is (= "boolean" (get-in p [knob :type]))
            (str tool "'s " knob " must be declared boolean"))
        (is (string? (get-in p [knob :description]))
            (str tool "'s " knob " must carry a :description for the agent"))))))

(deftest published-knobs-survive-the-js-projection
  (testing "the spliced tools/list surface (tool-descriptors-js) carries each knob"
    (let [js-arr   (tools/tool-descriptors-js)
          by-name  (into {}
                         (for [i (range (alength js-arr))
                               :let [d (aget js-arr i)]]
                           [(j/get d :name) d]))]
      (doseq [[tool knobs] handler-consumed-knobs
              :let [d (get by-name tool)]]
        (is (some? d) (str tool " missing from tool-descriptors-js"))
        (let [js-props (-> d (j/get :inputSchema) (j/get :properties))]
          (doseq [knob knobs]
            ;; A declared property is a non-nil JS schema object; an
            ;; absent one reads as nil through `j/get`.
            (is (some? (j/get js-props (name knob)))
                (str tool "'s " (name knob) " must ride through clj->js onto the wire surface"))
            (is (= "boolean" (j/get (j/get js-props (name knob)) :type))
                (str tool "'s " (name knob) " must surface as a boolean on the wire surface"))))))))

(deftest additional-properties-closed-but-knobs-allowed
  (testing "descriptors stay :additionalProperties false yet declare the knobs"
    ;; The closed-schema posture is the whole reason the omission was a
    ;; bug — a closed schema that omits a supported input makes that input
    ;; unavailable. Assert the schema stays closed (so we never loosen it
    ;; as a shortcut) AND the knobs are explicitly declared.
    (doseq [[tool knobs] handler-consumed-knobs
            :let [descriptor (descriptor-by-name tool)]]
      (is (false? (get-in descriptor [:inputSchema :additionalProperties]))
          (str tool " must keep :additionalProperties false"))
      (doseq [knob knobs]
        (is (contains? (props descriptor) knob)
            (str tool " must EXPLICITLY declare " knob " under the closed schema"))))))
