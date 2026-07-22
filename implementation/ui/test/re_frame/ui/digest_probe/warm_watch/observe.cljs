(ns re-frame.ui.digest-probe.warm-watch.observe
  "Runtime-side observer for the real Shadow warm-watch fixture (rf2-4vm19).

  The emitted `:node-script` runtime appends one JSON line per observation to
  the runner's runtime log (RF2_WARM_WATCH_RUNTIME_LOG), mirroring the JSONL
  protocol the JVM observe hook uses for the compile side. Three record kinds:

  * `boot` — written once by `client/main` after the initial load: the REAL
    build identity `current-build-id` resolved at the runtime seam (must be
    `:ui-warm-watch`, never `::default`), plus the write-through aggregate and
    ledger membership of the initial load.
  * `publish` — written by a watch on the public `custom-elements` aggregate,
    so every committed reload cycle (including the removed-source projection's
    removal cycle) produces exactly one runtime snapshot the runner can await.
  * `source-load` — the MID-CHAIN witness `trigger.cljs` emits at its own top
    level while shadow's loader is re-running it: at that moment the
    `^:dev/before-load` `notify-reload!` must already have OPENED the build's
    reload cycle and the `^:dev/after-load` `commit-reload!` must not yet have
    closed it, so `:cycles-open` here is direct proof both lifecycle
    annotations are load-bearing (drop either and the record — or the commit
    that follows it — goes wrong, reddening the runner).

  Every record carries the same image set (build id, open cycles, the exact
  sorted aggregate, the ledger source membership), so the runner's assertions
  are exact-manifest equality, not presence probes. Recording is contained: a
  throw here must never abort shadow's load chain, so failures degrade to a
  missing record — which the runner's await then reports loudly.

  Test-only fixture code; never part of any production build."
  (:require ["fs" :as fs]
            [re-frame.ui.rules :as rules]))

(def ^:private log-file
  (or (unchecked-get (.-env js/process) "RF2_WARM_WATCH_RUNTIME_LOG")
      "target/ui-warm-watch/runtime.jsonl"))

(defn- aggregate-image
  "The live aggregate as an exactly-comparable image: sorted tag name ->
  sorted property-name vector."
  []
  (into (sorted-map)
        (map (fn [[tag decl]]
               [(name tag) (vec (sort (map name (:properties decl))))]))
        @rules/custom-elements))

(defn- ledger-image
  "The committed ledger membership as sorted [build-str ns-str] pairs — the
  runtime mirror of the compile side's `:build-sources` membership."
  []
  (->> (rules/ledger-sources)
       (map (fn [[b n]] [(str b) (str n)]))
       sort
       vec))

(defn record!
  "Append one observation record; contained so a recording failure can never
  abort shadow's load chain (it degrades to a missing record the runner's
  await reports)."
  [evt extra]
  (try
    (let [m (merge {:evt         evt
                    :build-id    (str (rules/current-build-id))
                    :cycles-open (vec (sort (map str (rules/in-flight-cycles))))
                    :aggregate   (aggregate-image)
                    :ledger      (ledger-image)}
                   extra)]
      (fs/appendFileSync log-file (str (js/JSON.stringify (clj->js m)) "\n")))
    (catch :default e
      (when (exists? js/console)
        (.error js/console "[warm-watch observe] record! failed" e))))
  nil)

(defn note-load!
  "Top-level mid-chain witness: called from a fixture source's own top level,
  so it runs exactly while shadow's loader is re-evaluating that source —
  between `notify-reload!` (before-load) and `commit-reload!` (after-load)."
  [src]
  (record! "source-load" {:src src}))

(defn install!
  "Install the publish watch: one `publish` record per committed reload cycle
  (the public aggregate is republished after every ledger commit, including
  the removed-source projection's removal cycle)."
  []
  (add-watch rules/custom-elements ::runtime-observe
             (fn [_ _ _ _] (record! "publish" {})))
  nil)
