(ns day8.re-frame2-causa.panels.ai-co-pilot-redaction
  "Privacy-by-default redaction helpers for LLM-bound payloads.")

(def default-redaction-settings
  "Event args and app-db leaves are redacted unless explicitly unmasked."
  {:unmask-event-args false
   :unmask-app-db     false})

(def ^:private redacted-token "<redacted>")

(defn- redact-event-vec
  [event-vec]
  (if (and (vector? event-vec) (seq event-vec))
    (into [(first event-vec)] (repeat (dec (count event-vec)) redacted-token))
    event-vec))

(defn redact-trace-event
  "Redact trace event-vector args unless `:unmask-event-args` is true."
  [{:keys [unmask-event-args]} event]
  (if unmask-event-args
    event
    (cond-> event
      (some? (get-in event [:tags :event]))
      (update-in [:tags :event] redact-event-vec))))

(defn- redact-walk-app-db
  [value]
  (cond
    (map? value)
    (into {} (map (fn [[k v]] [k (redact-walk-app-db v)]) value))

    (vector? value)
    (mapv redact-walk-app-db value)

    (set? value)
    (into #{} (map redact-walk-app-db value))

    (seq? value)
    (map redact-walk-app-db value)

    :else
    redacted-token))

(defn redact-app-db
  "Redact app-db leaves unless `:unmask-app-db` is true."
  [{:keys [unmask-app-db]} db]
  (if unmask-app-db
    db
    (redact-walk-app-db db)))

(defn redact-payload
  "Apply trace-event and app-db redaction to the LLM-bound payload."
  [settings payload]
  (cond-> payload
    (contains? payload :trace-events)
    (update :trace-events
            (fn [events] (mapv #(redact-trace-event settings %) events)))

    (contains? payload :app-db)
    (update :app-db #(redact-app-db settings %))))
