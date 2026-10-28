(ns re-frame.privacy
  "Privacy policy helpers: the `:rf/redacted` sentinel and the `sensitive?`
  predicate over a trace event's top-level stamp.

  Redaction itself is declared, never inferred. Durable app-db paths are
  classified by `reg-classification` beside the feature that owns them, and
  transient payloads by `:sensitive` registration metadata on the `reg-*`
  that owns them. Both are applied at egress by the classification
  projection (`re-frame.classification`, `re-frame.elision`), never by the
  handler's interceptor chain. Sensitivity is a property of the data VALUE
  at a path, not of the handler that touched it.

  EP-0015 §8: schemas describe shape, not durable app-db egress policy — a
  `reg-app-schema` `{:sensitive? true}` slot prop is not a route into the
  registry. An app-db path's schema-validation-failure trace is redacted by
  the same classification, so the path is declared once; schema
  `:sensitive?` props redact the validation failures of the transient
  values and machine `:data` their schemas check (Spec 010 §`:sensitive?`).")

#?(:clj (set! *warn-on-reflection* true))

(def redacted-sentinel
  :rf/redacted)

(defn sensitive?
  "Does `trace-event` carry the top-level `:sensitive?` stamp? THE
  framework-published predicate every forwarder composes against, so the
  five-token check is written once (Spec 009 §Privacy).

  FAIL-CLOSED, and the truthiness is deliberate:

    | `:sensitive?`            | result |
    |--------------------------|--------|
    | `true`                   | true   |
    | `false` / `nil` / absent | false  |
    | any other truthy value   | true   |

  The `:rf/trace-event` schema types `:sensitive?` as a boolean, so a string
  `\"true\"`, a keyword `:yes` or a `1` is a CONTRACT VIOLATION — and the only
  safe reading of a violation on this axis is the conservative one. A
  `(true? …)` check reads every one of them as NOT sensitive and forwards the
  event, which is fail-OPEN in exactly the case where the producer has already
  proved itself unreliable. `boolean` is therefore the whole implementation:
  do not \"tighten\" it to `true?`.

  Matches the fail-closed posture `re-frame.mcp-base.sensitive/sensitive-stamp?`
  already applies on the MCP wire — that one additionally logs and counts the
  malformed stamp, which is envelope bookkeeping the framework predicate does
  not owe its callers."
  [trace-event]
  (and (map? trace-event)
       (boolean (:sensitive? trace-event))))
