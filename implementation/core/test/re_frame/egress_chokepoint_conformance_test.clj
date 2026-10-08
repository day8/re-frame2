(ns re-frame.egress-chokepoint-conformance-test
  "The egress-redaction choke-point, enforced at the ALWAYS-ON union-record
  fan-out.

  Security.md §The privacy / classification surface: projection is
  centralized at trust boundaries via `project-egress`, and sinks consume
  already-projected records only. The always-on fan-out chokepoints
  `re-frame.error-emit/dispatch-error-record!` and
  `dispatch-frame-teardown-report!` ship their record to corpus listeners
  (Sentry / Datadog shippers) unchanged, so safety rests on every CALLER.
  Each calling namespace must be the chokepoint namespace itself, reference
  `project-egress` (it routes its untrusted slots first, the ssr/hydrate
  model), or sit on `structural-only-allow-list` (vetted to carry value-free
  slots only).

  The scan walks `re-frame.impl-source-corpus`, shared with
  error_catalogue_channel_conformance_test, whose corpus cross-check proves
  the walk reaches every artefact. It reads direct calls, so it under-reports
  variable-arg indirection rather than false-positiving. Record-level slots
  only: a host `:exception` and its ex-data are an opaque residual
  (Security.md §Out-of-scope).

  JVM-only: it `slurp`s repo source files."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.set :as set]
            [clojure.string :as str]
            [re-frame.impl-source-corpus :as rf.impl-source-corpus]))

(def ^:private chokepoint-call-re
  "A CALL to either chokepoint, ns-qualified or bare (the late-bound local
  rebinding ssr/hydrate, ssr/boot and ssr-ring use). Anchored on `(` so a
  docstring mention does not count."
  #"\(\s*(?:[a-zA-Z0-9_.-]+/)?(dispatch-error-record!|dispatch-frame-teardown-report!)")

(def ^:private chokepoint-def-ns
  "Defines the chokepoints; its own internal call routes the frame leg through
  `route-error-record!` -> `project-egress`."
  're-frame.error-emit)

(def ^:private routing-marker-re #"project-egress")

(def ^:private ns-decl-re
  "The leading `(ns <name>` declaration. A text scan, because `read-string`
  rejects the reader conditionals in `.cljc` files."
  #"(?m)^\s*\(ns\s+([a-zA-Z][a-zA-Z0-9_.*+!?<>=-]*)")

(defn- ns-form-symbol [src]
  (when-let [[_ ns-name] (re-find ns-decl-re src)]
    (symbol ns-name)))

(defn- chokepoint-caller-namespaces
  "`{<ns-symbol> {:file <path> :routes? <bool>}}` for every non-test source
  namespace that calls a chokepoint."
  []
  (reduce
    (fn [acc f]
      (let [src (slurp f)]
        (if (re-find chokepoint-call-re src)
          (if-let [ns-sym (ns-form-symbol src)]
            (assoc acc ns-sym
                   {:file    (str/replace (.getPath f) "\\" "/")
                    :routes? (boolean (re-find routing-marker-re src))})
            acc)
          acc)))
    {}
    (rf.impl-source-corpus/non-test-source-files)))

(def ^:private structural-only-allow-list
  "Callers vetted to ship value-free structural slots only, so the raw corpus
  fan-out is safe without `project-egress`. Each entry says why.

  `re-frame.ssr.hydrate` is deliberately absent: it lifts the untrusted
  `:payload-frame-id` out of the payload, so it routes through
  `project-egress` and the routing arm governs it."
  '#{;; Frameless `:rf.error/malformed-hydration-payload`: `:where`,
     ;; `:failing-id`, a DOM `:element-id`, a framework `:reason`,
     ;; `:recovery`. The parse failed, so there is no parsed value to carry.
     re-frame.ssr.boot

     ;; The `:rf.error/sanitised-on-projection` fallback: a status fact. The
     ;; unprojected payload is deliberately not carried forward.
     re-frame.ssr.error-projector

     ;; Ring-host lifecycle records: structural host facts plus a host
     ;; `:exception` residual.
     re-frame.ssr.ring.lifecycle

     ;; Three records, each built from a closed key set with every
     ;; payload-derived slot omitted: the `:rf.error/drain-depth-exceeded`
     ;; halt (counts and event-id keywords only), the `:boundary? true`
     ;; schema refusal (no value, explain, schema or interpolated `:reason`;
     ;; pinned closed by `re-frame.always-on-validation-production-test`), and
     ;; the dev-gated `:rf.error/malformed-schema` backstop, whose `:reason`
     ;; is composed here rather than taken from the validator's message.
     re-frame.router

     ;; The `:kind :route` `:rf.error/no-such-handler` miss: framework enums
     ;; plus `:url`, scrubbed by `privacy.url/redact-url-tag` before either
     ;; axis sees it. `project-egress` is the wrong instrument here: a route
     ;; miss has no schema to path-target.
     re-frame.routing.url-change

     ;; The `:rf.error/safe-redirect-*` rejections: enums, the parsed
     ;; `:scheme` / `:host`, the call's own `:allowlist`, and `:location`,
     ;; scrubbed by `privacy.url/redact-url-tag` for the same reason as
     ;; url-change. Pinned closed by `re-frame.ssr-safe-redirect-production-test`.
     re-frame.ssr.response

     ;; The dev-gated `:where :app-db` rejection and `:rf.error/malformed-schema`
     ;; records, built from a closed key set: framework keywords, structural
     ;; ids, the author's own `:registered-path`, and a `:reason` composed
     ;; from that path (plus `type-of-value` on the rejection), never from the
     ;; value, the leaf path, the schema or the validator's message.
     re-frame.schemas.validate

     ;; The dev-gated `:where :machine-data` rejection: framework keywords,
     ;; the machine's registered id, `:phase`, and a `:reason` composed from
     ;; those two keywords. The machine's `:data`, explain and schema stay on
     ;; the dev trace.
     re-frame.machines.data-validation

     ;; `:rf.ssr/suspense-boundary-failed`: a closed literal key set (`:error`,
     ;; `:frame`, `:where`, `:recovery`, `:time` and the author-written
     ;; boundary `:id`). The delta, the branch `:reason` and the reader
     ;; exception never ride.
     re-frame.ssr.streaming.client})

(deftest every-chokepoint-caller-routes-or-is-allow-listed
  (testing "a caller that neither routes through `project-egress` nor is
            allow-listed ships a payload-bearing record to corpus listeners
            raw"
    (let [unhandled (->> (chokepoint-caller-namespaces)
                         (remove (fn [[ns-sym {:keys [routes?]}]]
                                   (or (= ns-sym chokepoint-def-ns)
                                       routes?
                                       (contains? structural-only-allow-list
                                                  ns-sym))))
                         (map (fn [[ns-sym info]] [ns-sym (:file info)]))
                         (into {}))]
      (is (empty? unhandled)
          (str "always-on fan-out callers that neither route their untrusted "
               "slots through `project-egress` nor are on "
               "structural-only-allow-list: " (pr-str unhandled)
               " — route the untrusted slots through `project-egress` before "
               "the fan-out, or, if the record is vetted value-free, add the "
               "namespace to structural-only-allow-list with its reason.")))))

(deftest allow-list-stays-honest
  (testing "every allow-listed namespace still calls a chokepoint and does not
            route; this is also what keeps the call scan and the routing
            marker from going vacuous"
    (let [callers          (chokepoint-caller-namespaces)
          no-longer-caller (set/difference structural-only-allow-list
                                           (set (keys callers)))
          now-routes       (set (filter #(get-in callers [% :routes?])
                                        structural-only-allow-list))]
      (is (empty? no-longer-caller)
          (str "allow-list entries that no longer call a chokepoint (drop "
               "them): " (pr-str (sort no-longer-caller))))
      (is (empty? now-routes)
          (str "allow-list entries that now route through `project-egress` "
               "(drop them so the routing arm governs them): "
               (pr-str (sort now-routes)))))))
