(ns re-frame.resources.ssr
  "SSR and hydration for resources, late-bound both ways so the resources
  artefact never requires the SSR artefact. On the server it projects the
  allowed resource slice (classification-driven redact / omit, never all of
  `:rf.db/runtime`), supplies the blocking-drain predicate and its timeout
  policy. On the client it reconciles the installed slice on `:rf/hydrate`
  (indexes recomputed, SSR owners orphaned, underivable rows dropped, clock
  skew surfaced) and plans which hydrated entries refetch. Only `:entries`
  rides the wire; the indexes are rebuilt on install.
  Spec 016 §SSR and hydration"
  (:require [re-frame.error :as rf.error]
            [re-frame.frame :as rf.frame]
            [re-frame.identity :as rf.identity]
            [re-frame.interop :as rf.interop]
            [re-frame.late-bind :as rf.late-bind]
            [re-frame.privacy :as rf.privacy]
            [re-frame.resources.classification :as rf.resources.classification]
            [re-frame.resources.mutation-registry :as rf.resources.mutation-registry]
            [re-frame.resources.mutation-runtime :as rf.resources.mutation-runtime]
            [re-frame.resources.registry :as rf.resources.registry]
            [re-frame.resources.route :as rf.resources.route]
            [re-frame.resources.state :as rf.resources.state]
            [re-frame.resources.timers :as rf.resources.timers]
            [re-frame.resources.work-ledger :as rf.resources.work-ledger]
            [re-frame.trace :as rf.trace]))

#?(:clj (set! *warn-on-reflection* true))

(declare hydrate-refetch-plan)
(declare settle-entry-to-last-stable)

;; Clock: freshness and skew read the WALL clock `rf.interop/epoch-now-ms`,
;; never the perf clock `rf.interop/now-ms`, which is origin-relative and
;; incomparable with the absolute durable timestamps. Restore reads it only
;; for the skew diagnostic, never to freshen a durable restored timestamp.
;; Spec 016 §Freshness clock contract
;;
;; Staleness is always `rf.resources.state/entry-stale?`, so the server
;; projection, the client refetch decision and the subs layer agree.

;; ---- classification and scoped-key privacy --------------------------------
;;
;; The resource owns its classification. Its coarse `:sensitive?` / `:large?`
;; claim decides the whole-entry disposition (sensitive wins over large); its
;; projection-relative `:sensitive` / `:large` path declarations are claimed
;; on each entry present by the resources hook and read back from the frame's
;; classification when a serialized entry's data is projected. A
;; `:params-schema` mark validates; it does not classify.
;;
;; The scoped key is the MAP KEY of the projected slice, so a coarse entry's
;; scope and params are tokenised too. The client refetch is route-driven and
;; re-derives its own key, so it never needs the raw identity back. The
;; digest is a 32-bit non-cryptographic hash: collidable and brute-forceable
;; for a low-entropy identity. That is why coarse rows are withheld rather
;; than made addressable by it, and why the sensitive arm carries a
;; content-free shape token instead.
;; Spec 016 §The off-box redaction token carries no enumerable content

(def ^:private fnv-offset-basis 2166136261)
(def ^:private fnv-prime 16777619)

#?(:cljs
   (defn- utf8-bytes
     "UTF-8 encodes `text` to a JS array of byte values, byte-identical to the
     JVM's `(.getBytes text \"UTF-8\")`. A surrogate pair is one code point and
     four bytes; CEDN-1 text holds no unpaired surrogate."
     [text]
     (let [output-bytes    #js []
           code-unit-count (.-length text)]
       (loop [code-unit-index 0]
         (if (>= code-unit-index code-unit-count)
           output-bytes
           (let [code-unit (.charCodeAt text code-unit-index)]
             (cond
               ;; a HIGH surrogate and the low surrogate after it: one code point.
               (and (>= code-unit 0xd800) (<= code-unit 0xdbff))
               (let [code-point (+ 0x10000
                                   (bit-shift-left (- code-unit 0xd800) 10)
                                   (- (.charCodeAt text (inc code-unit-index)) 0xdc00))]
                 (doto output-bytes
                   (.push (bit-or 0xf0 (bit-shift-right code-point 18)))
                   (.push (bit-or 0x80 (bit-and (bit-shift-right code-point 12) 0x3f)))
                   (.push (bit-or 0x80 (bit-and (bit-shift-right code-point 6) 0x3f)))
                   (.push (bit-or 0x80 (bit-and code-point 0x3f))))
                 (recur (+ code-unit-index 2)))

               (< code-unit 0x80)
               (do (.push output-bytes code-unit)
                   (recur (inc code-unit-index)))

               (< code-unit 0x800)
               (do (doto output-bytes
                     (.push (bit-or 0xc0 (bit-shift-right code-unit 6)))
                     (.push (bit-or 0x80 (bit-and code-unit 0x3f))))
                   (recur (inc code-unit-index)))

               :else
               (do (doto output-bytes
                     (.push (bit-or 0xe0 (bit-shift-right code-unit 12)))
                     (.push (bit-or 0x80 (bit-and (bit-shift-right code-unit 6) 0x3f)))
                     (.push (bit-or 0x80 (bit-and code-unit 0x3f))))
                   (recur (inc code-unit-index))))))))))

(defn- fnv-1a-32
  "The 32-bit FNV-1a hash of `text` as 8 lower-case hex chars, byte-identical
  on CLJ and CLJS. Three hazards make that deliberate: the input must be UTF-8
  bytes (masking UTF-16 code units disagrees with the JVM on non-ASCII); the
  multiply must wrap at 32 bits (a CLJS double multiply loses its low bits
  past 2^53, hence `Math.imul`); the output must be unsigned (CLJS `bit-and`
  yields a signed int32). `resources-ssr-cljs-test` pins the two branches
  equal."
  [^String text]
  (let [bytes      #?(:clj (.getBytes text "UTF-8") :cljs (utf8-bytes text))
        byte-count #?(:clj (alength ^bytes bytes) :cljs (.-length bytes))]
    (loop [byte-index 0
           hash       fnv-offset-basis]
      (if (< byte-index byte-count)
        (let [byte      #?(:clj (bit-and (aget ^bytes bytes byte-index) 0xff)
                           :cljs (aget bytes byte-index))
              next-hash #?(:clj  (-> (bit-xor hash byte)
                                     (* fnv-prime)
                                     (bit-and 0xffffffff))
                           :cljs (js/Math.imul (bit-xor hash byte) fnv-prime))]
          (recur (inc byte-index) next-hash))
        #?(:clj  (format "%08x" (bit-and hash 0xffffffff))
           :cljs (let [hex (.toString (unsigned-bit-shift-right hash 0) 16)]
                   (str (subs "00000000" 0 (max 0 (- 8 (count hex))))
                        hex)))))))

(defn- shape-token
  "The content-free payload of a `{:rf/redacted …}` token: `value`'s shape
  as `rf.error/diag-value-summary` gives it (a closed `:type` tag plus a
  `:count`). Every map of two entries yields the same token, so no candidate
  space can be tested against it; it is order- and host-independent, and
  total (it never calls `toString` or `pr-str` on a host object)."
  [value]
  (rf.error/diag-value-summary value))

(defn- canonical-digest-token
  "The content-addressed payload of a `{:rf/redacted …}` token: `fnv-1a-32`
  over `value`'s CEDN-1 canonical bytes, so equal values give one token on
  both hosts (`pr-str` would follow map iteration order). A value
  `canonical-bytes` refuses falls back to `shape-token`, keeping redaction
  total."
  [value]
  (try
    (fnv-1a-32 (rf.identity/canonical-bytes value))
    (catch #?(:clj Throwable :cljs :default) _t
      (shape-token value))))

(defn redact-value
  "A `{:rf/redacted …}` token for an identity-bearing `value` under a
  metadata-only disposition; nil or empty projects to `{:rf/redacted nil}`.
  `:redact` (sensitive, and the 1-arity default) gets a content-free
  `shape-token`; `:omit` (large, a size claim) gets a `canonical-digest-token`
  so per-key joins survive. A sensitive value gets no digest because a 32-bit
  hash of a low-entropy id is recoverable by enumeration, and trace egress is
  a trust boundary too.
  Spec 016 §The off-box redaction token carries no enumerable content"
  ([value] (redact-value value :redact))
  ([value disposition]
   (if (or (nil? value) (and (coll? value) (empty? value)))
     {:rf/redacted nil}
     {:rf/redacted (if (= :omit disposition)
                     (canonical-digest-token value)
                     (shape-token value))})))

;; ---- wire keys the live client can derive ---------------------------------
;;
;; The client never receives a key it uses: it re-derives one from live scope
;; and params and reads `(rf.resources.state/entry-path scoped-key)`. A coarse
;; row's tokenised key is therefore unaddressable, and the row is WITHHELD.
;; Adopting it by re-deriving the digest would be unsafe (32-bit collisions
;; make it a cross-principal read; enumeration recovers low-entropy ids) and
;; pointless (a coarse row is metadata-only, so the client refetches anyway).
;; The row is removed rather than emptied: under `:gc-after-ms :never`, an
;; unregistered resource or a `:server` frame, no GC timer would ever collect
;; it.

(defn- coarse-redaction-token?
  "True iff `v` is the whole-component `{:rf/redacted …}` token
  `redact-value` mints."
  [v]
  (and (map? v) (= #{:rf/redacted} (set (keys v)))))

(defn- unaddressable-wire-key?
  "True iff the client cannot derive the hydrated `scoped-key`: its scope or
  params is a coarse redaction token. The client reads the shape because it
  has no raw key to compare; it agrees with the server's exact key-id test
  because the token lives in the reserved `:rf/*` namespace."
  [scoped-key]
  (let [[scope _resource-id params] scoped-key]
    (or (coarse-redaction-token? scope)
        (coarse-redaction-token? params))))

(defn project-scoped-key
  "The wire shape of a scoped key `[scope resource-id params]`: verbatim for
  `:serialize`, otherwise scope and params replaced by `redact-value` tokens
  chosen by `disposition`. The shape is kept so the client's index recompute
  and refetch plan parse it unchanged. `_spec` is unused.
  Spec 016 §SSR and hydration"
  [scoped-key disposition _spec]
  (if (= :serialize disposition)
    scoped-key
    (let [[scope resource-id params] scoped-key]
      [(redact-value scope disposition) resource-id (redact-value params disposition)])))

(defn disposition+project-key
  "Returns `[projected-key disposition spec]` for `scoped-key`: the owner spec,
  its whole-entry disposition, and the key projected by it. The one pipeline
  SSR, tool egress and trace egress share, so they never drift. It lives here
  rather than in `classification` because `registry` already requires
  `classification`. `_frame-id` is unused: the disposition is
  frame-independent."
  [scoped-key _frame-id]
  (let [resource-id (second scoped-key)
        spec        (rf.resources.registry/resource-meta resource-id)
        disposition (rf.resources.classification/whole-entry-disposition spec)]
    [(project-scoped-key scoped-key disposition spec) disposition spec]))

(defn- project-entry
  "Returns `[wire-entry metadata]` for one durable `entry`, projected by its
  classification against the SSR `frame-id`. The wire entry ships `:data`
  through the frame's classification (`:serialize`), the redaction sentinel
  (`:redact`) or no `:data` (`:omit`); the entry's other value slots
  (`entry-value-slots`) ride only with serialized data, and transient work
  pointers never ride. The metadata
  carries the raw `:resource/key`, `:projected-key`, `:disposition`,
  `:freshness`, `:withheld?` and `:refetch-on-client?`; it is a server-side
  diagnostic, so it reports withheld coarse entries too.
  Spec 016 §SSR and hydration"
  [frame-id clock-ms entry]
  (let [scoped-key  (:resource/key entry)
        resource-id (second scoped-key)
        key-id      (rf.resources.state/key-id scoped-key)
        [projected-key disposition _spec] (disposition+project-key scoped-key frame-id)
        stale?      (rf.resources.state/entry-stale? entry clock-ms)
        ;; `key-id` is canonical bytes over the whole key, so a coarse
        ;; tokenised key changes it and the client, which reads the RAW
        ;; key-id, cannot address the row (see `unaddressable-wire-key?`).
        ;; Keying the wire on the raw key-id is no repair: it is a reversible
        ;; plaintext encoding of the identity the coarse claim hides.
        key-derivable? (= (rf.resources.state/key-id projected-key) key-id)
        metadata-only? (not= :serialize disposition)
        base        {:resource/key   scoped-key
                     :resource-id    resource-id
                     :freshness      (if stale? :stale :fresh)
                     :status         (:status entry)
                     :loaded-at      (:loaded-at entry)
                     :stale-at       (:stale-at entry)
                     :invalidated-at (:invalidated-at entry)}
        wire-entry  (case disposition
                      ;; The egress reads the frame's classification, which
                      ;; claims the resource's `:data` declarations on the
                      ;; entry, never the spec. A frameless egress rides the
                      ;; data verbatim. An infinite
                      ;; feed's page vector needs no special branch: the
                      ;; walker's index-free fork matches every page.
                      :serialize
                      (assoc entry :data
                             (rf.resources.classification/project-entry-data
                               (:data entry) key-id frame-id
                               :rf.egress/ssr-hydration))
                      ;; The two coarse arms are defence in depth: their rows
                      ;; are withheld, and these shapes keep the metadata safe
                      ;; and fail closed should a caller ever ship the entry.
                      ;; They drop the slots the coarse claim covers; a
                      ;; redacted entry keeps the `:data` sentinel the
                      ;; client's refetch plan reads.
                      :redact
                      (-> (apply dissoc entry rf.resources.classification/entry-value-slots)
                          (assoc :data rf.privacy/redacted-sentinel))
                      :omit
                      (apply dissoc entry rf.resources.classification/entry-value-slots))
        ;; An in-flight attempt belongs to the server timeline, so neither it
        ;; nor its pointer rides the round-trip, and nor does the terminal tail.
        ;; Spec 016 §2. In-flight work does not survive restore as live work
        wire-entry  (dissoc wire-entry :current-work :invalidated-during :work :recent-work)
        ;; The in-entry key copy matches the wire map key, so the raw identity
        ;; rides in neither carrier.
        wire-entry  (assoc wire-entry :resource/key projected-key)
        meta'       (assoc base
                           :projected-key projected-key
                           :disposition (case disposition
                                          :serialize :serialized
                                          :redact    :redacted
                                          :omit      :omitted)
                           ;; One fact with one owner, the exact key-id
                           ;; comparison, so callers never re-derive it from
                           ;; `:disposition`.
                           :withheld? (not key-derivable?)
                           :refetch-on-client?
                           (boolean (or metadata-only? stale?)))]
    [wire-entry meta']))

(defn projection-metadata
  "The per-entry projection metadata for a frame's `entries` against
  `clock-ms`, as a vector. Pure, and a host affordance only: nothing in the
  repository records it. It must not ride `:ssr/extend-runtime-db-projection`,
  whose return merges onto the hydration wire, because it carries raw scoped
  keys. Pass the `clock-ms` the response was rendered under, or `:freshness`
  and `:refetch-on-client?` can disagree with the wire.
  Spec 016 §SSR and hydration"
  [frame-id clock-ms entries]
  (mapv (fn [[_k-id entry]] (second (project-entry frame-id clock-ms entry)))
        entries))

(defn project-resources-runtime-db
  "The resource slice of the hydration payload's runtime-db: `{:rf.runtime/resources
  {:entries …}}` with each entry classification-projected and re-keyed on its
  projected key, or `{}` when there are no entries. The indexes are not
  shipped (rebuilt on install), nor is the rest of `:rf.db/runtime`. A
  re-keyed coarse row is WITHHELD, not shipped empty (see
  `unaddressable-wire-key?`); it costs the client nothing because a coarse
  entry refetches anyway.

  The body of `:ssr/extend-runtime-db-projection`, and PRODUCTION: it ships to
  every visitor of every page. The 2-arity takes the explicit SSR frame whose
  registry governs projection; the 1-arity uses the ambient frame and is
  sound only inside that frame's `with-frame`.
  Spec 016 §SSR and hydration"
  ([runtime-db]
   (project-resources-runtime-db runtime-db (rf.frame/resolve-current-frame)))
  ([runtime-db frame-id]
   (let [resources (get runtime-db rf.resources.state/resources-key)
         entries   (:entries resources)]
     (if (seq entries)
       (let [clock-ms (rf.interop/epoch-now-ms)
             ;; Re-keyed on the projected key so the client installs and
             ;; indexes under the same byte identity; `:withheld?` is the
             ;; server's exact decision, so nothing sniffs key shapes here.
             wired    (into {}
                            (comp
                              (map (fn [[_k-id entry]] (project-entry frame-id clock-ms entry)))
                              (remove (fn [[_wire-entry meta']] (:withheld? meta')))
                              (map (fn [[wire-entry _meta]]
                                     [(rf.resources.state/key-id (:resource/key wire-entry)) wire-entry])))
                            entries)]
         {rf.resources.state/resources-key {:entries wired}})
       {}))))

;; ---- server blocking drain ------------------------------------------------
;;
;; The host owns the event pump; this namespace owns the predicate it loops
;; on, the timeout policy, and the loop that installs the result.

(defn entry-settled?
  "True iff `entry` is `:loaded` or `:error`. A nil entry is not settled.
  Spec 016 §SSR and hydration"
  [entry]
  (boolean (and entry (contains? #{:loaded :error} (:status entry)))))

(defn blocking-settled?
  "True iff every entry in `blocking` (`{<key-id> <scoped-key>}`, the route
  slice's byte-keyed map for the current nav-token) has settled in
  `entries`. Empty `blocking` is settled. Byte keys keep two
  `=`-equal-but-byte-distinct requirements separate.
  Spec 016 §SSR and hydration"
  [entries blocking]
  (every? (fn [[k-id _]] (entry-settled? (get entries k-id))) blocking))

(defn unsettled-blocking-keys
  "The sub-map of `blocking` whose entries have not settled.
  Spec 016 §SSR and hydration"
  [entries blocking]
  (into {} (remove (fn [[k-id _]] (entry-settled? (get entries k-id)))) blocking))

(defn ssr-timeout-error
  "The first-load failure envelope a blocking SSR timeout settles an entry
  to: `:rf.http/timeout` tagged `:reason :ssr-blocking-timeout`, so a
  renderer can tell the render deadline from an upstream timeout.
  Spec 016 §SSR and hydration"
  [deadline-ms]
  {:kind       :rf.http/timeout
   :reason     :ssr-blocking-timeout
   :limit-ms   deadline-ms
   :message    (str "SSR blocking resource did not settle within the "
                    deadline-ms "ms render deadline; settled as a first-load "
                    "failure so the request does not hang (Spec 016 §SSR and "
                    "hydration).")})

(defn settle-blocking-timeout
  "Pure timeout policy: returns `{:entries :route-blocking-failure
  :timed-out-work}` with every unsettled blocking entry settled to an
  `ssr-timeout-error` first-load failure and its abandoned attempt settled
  `:timed-out`, so the render never sees a hung `:loading`. `:timed-out-work`
  is the abandoned attempts' ids, whose host handles the caller clears.
  `:route-blocking-failure` is nil when nothing timed out. Emits
  `:rf.error/resource-ssr-blocking-timeout`; route state is not touched.
  Spec 016 §SSR and hydration"
  [entries blocking deadline-ms frame-id]
  (let [unsettled (unsettled-blocking-keys entries blocking)
        timed-out (vec (vals unsettled))]
    (if (empty? unsettled)
      {:entries entries :route-blocking-failure nil :timed-out-work []}
      (let [error    (ssr-timeout-error deadline-ms)
            outcome  {:reason :ssr-blocking-timeout :limit-ms deadline-ms}
            timed-out-work (into [] (keep (fn [[k-id _]] (:current-work (get entries k-id))))
                                 unsettled)
            entries' (reduce
                       (fn [es [k-id k]]
                         (let [entry (or (get es k-id)
                                         (rf.resources.state/empty-entry (second k) k))]
                           (assoc es k-id (-> (rf.resources.state/entry-failed entry {:error error})
                                              (rf.resources.work-ledger/settle-attempt
                                                (:current-work entry) :timed-out outcome)))))
                       entries unsettled)]
        (rf.trace/emit-error! :rf.error/resource-ssr-blocking-timeout
                           {:rf.error/id :rf.error/resource-ssr-blocking-timeout
                            :where       're-frame.resources.ssr/settle-blocking-timeout
                            :frame       frame-id
                            :recovery    :settled-as-first-load-failure
                            :timed-out   timed-out
                            :limit-ms    deadline-ms
                            :reason      (str (count unsettled) " blocking SSR "
                                              "resource(s) did not settle within "
                                              deadline-ms "ms; settled as first-load "
                                              "failures so the render does not hang.")})
        {:entries        entries'
         :timed-out-work timed-out-work
         :route-blocking-failure
         {:rf.error/id :rf.error/resource-ssr-blocking-timeout
          :timed-out   timed-out
          :limit-ms    deadline-ms
          :error       error}}))))

;; ---- routing slice literals -----------------------------------------------
;;
;; Mirrored rather than imported so resources never statically depends on
;; routing. Route readiness is a projection, not a literal, and has one
;; implementation: `rf.resources.route/reconcile-readiness`.

(def ^:private routing-key
  "The routing-runtime subtree key."
  :rf.runtime/routing)

(def ^:private routing-current-nav-token-path
  "Path to the live route slice's nav-token."
  [routing-key :current :nav-token])

(def ^:private routing-resource-blocking-key
  "The routing-runtime child holding `{<nav-token> {<key-id> <scoped-key>}}`
  blocking maps."
  :resource-blocking)

(defn current-blocking-keys
  "The blocking `{<key-id> <scoped-key>}` map the route slice enqueued for the
  current nav-token, or `{}`. A superseded navigation's slot is never read.
  Spec 016 §SSR and hydration"
  [runtime-db]
  (let [nav-token (get-in runtime-db routing-current-nav-token-path)]
    (or (get-in runtime-db [routing-key routing-resource-blocking-key nav-token])
        {})))

(defn drain-blocking-resources!
  "Drains the current nav-token's blocking resources for SSR `frame-id`
  until they settle or the deadline passes, so the render always sees settled
  state. The body of the `:resources/drain-blocking-ssr!` hook.

  Opts: `:pump!` (fn of `tick-ms`, advances the host event pump; nil
  re-checks without pumping), `:deadline-ms` (REQUIRED, the render budget),
  `:clock-fn` (epoch-ms, injectable for tests), `:tick-ms` (advisory yield
  hint, default 0).

  Returns `{:settled? :timed-out [<scoped-key> …] :route-blocking-failure}`.
  On a clean settle runtime-db is unchanged. On timeout one write installs
  the failed entries with each abandoned attempt settled `:timed-out`, then
  the host handles are cleared, so a late reply is stale-suppressed. Routing
  state is never touched.

  The timeout is decided inside that write, against the runtime-db the write
  replaces, because a reply landing on the transport thread after the loop's
  read would otherwise be reverted to `:loading` with an attempt that never
  settles. An entry that settled in that window keeps its reply, and a drain
  whose every blocking entry did reports a clean settle.
  Spec 016 §SSR and hydration"
  [frame-id {:keys [pump! deadline-ms clock-fn tick-ms]}]
  (let [clock-fn (or clock-fn rf.interop/epoch-now-ms)
        rdb0     (rf.frame/frame-runtime-db-value frame-id)
        blocking (current-blocking-keys rdb0)
        entries-path [rf.resources.state/resources-key :entries]]
    (if (empty? blocking)
      {:settled? true :timed-out [] :route-blocking-failure nil}
      (let [start    (clock-fn)
            deadline (+ start deadline-ms)]
        (loop []
          (let [rdb     (rf.frame/frame-runtime-db-value frame-id)
                entries (get-in rdb entries-path)]
            (cond
              (blocking-settled? entries blocking)
              {:settled? true :timed-out [] :route-blocking-failure nil}

              (>= (clock-fn) deadline)
              ;; `swap-runtime-db!` applies its fn once, so the trace
              ;; `settle-blocking-timeout` emits fires once.
              (let [outcome (volatile! nil)
                    _       (rf.frame/swap-runtime-db!
                              frame-id
                              (fn [live-rdb]
                                (let [result (settle-blocking-timeout
                                               (get-in live-rdb entries-path)
                                               blocking deadline-ms frame-id)]
                                  (vreset! outcome result)
                                  (assoc-in live-rdb entries-path (:entries result)))))
                    {:keys [route-blocking-failure timed-out-work]} @outcome]
                (run! #(rf.resources.work-ledger/clear-handle! frame-id %) timed-out-work)
                {:settled?              (nil? route-blocking-failure)
                 :timed-out             (vec (:timed-out route-blocking-failure))
                 :route-blocking-failure route-blocking-failure})

              :else
              (do (when pump! (pump! (or tick-ms 0)))
                  (recur)))))))))

;; ---- client hydration -----------------------------------------------------

(defn- ssr-owner?
  "True iff `owner` is an SSR owner token `[:ssr request-id nav-token]`; it
  belongs to one server render and never survives as a client owner.
  Spec 016 §Release authority is per owner kind"
  [owner]
  (and (vector? owner) (= :ssr (first owner))))

(defn- route-owner?
  "True iff `owner` is a route owner token `[:route route-id nav-token]`."
  [owner]
  (and (vector? owner) (= :route (first owner))))

;; A nil live nav-token means opposite things to the two callers. On
;; HYDRATION there is no client routing slice yet, so route owners ride
;; through for routing to reconcile ("cannot compare yet"). On RESTORE both
;; partitions were installed wholesale, so a nil token means no navigation is
;; live and every route owner orphans.
;; Spec 016 §4. Owners revive or orphan by kind

(def ^:private hydration-route-owner-policy
  "Hydration's route-owner policy: route owners ride through unchanged."
  :ride-through)

(defn- restore-route-owner-policy
  "Restore's route-owner policy: a route owner revives only when its nav-token
  equals `live-nav-token`; nil orphans them all."
  [live-nav-token]
  {:restore-live-nav-token live-nav-token})

(defn- route-owner-orphan?
  "True iff route `owner` must be released as an orphan under
  `route-owner-policy`. Never under `:ride-through`; under restore, iff its
  nav-token differs from the live one (a real nav-token is never nil)."
  [route-owner-policy owner]
  (and (route-owner? owner)
       (not= :ride-through route-owner-policy)
       (not= (:restore-live-nav-token route-owner-policy)
             (nth owner 2 nil))))

(defn- reconcile-entry-owners
  "Returns `[entry' dropped-orphans]`: SSR owners and policy-orphaned route
  owners removed from `:active-owners` (an orphan would pin the entry and
  refetch on focus forever), and the transient `:current-work` /
  `:invalidated-during` pointers cleared. Other owners ride through for
  their own subsystem. Spec 016 §4. Owners revive or orphan by kind"
  [entry route-owner-policy]
  (let [owners  (:active-owners entry #{})
        orphan? (fn [o] (or (ssr-owner? o)
                            (route-owner-orphan? route-owner-policy o)))
        dropped (into #{} (filter orphan?) owners)
        kept    (into #{} (remove orphan?) owners)]
    [(-> entry
         (assoc :active-owners kept)
         (assoc :current-work nil)
         ;; The stale mark stays; only the attempt it was stamped against is gone.
         (dissoc :invalidated-during))
     dropped]))

(defn clock-skew-ms
  "The ms a hydrated entry's `:stale-at` lies ahead of `clock-ms` when that
  exceeds one whole freshness window (`:stale-at` − `:loaded-at`), meaning
  the server clock ran ahead; else nil. Pure. Spec 016 §SSR and hydration"
  [entry clock-ms]
  (when-let [stale-at-ms (:stale-at entry)]
    (let [loaded-at-ms (:loaded-at entry)
          freshness-window-ms (when (and loaded-at-ms stale-at-ms)
                                (- stale-at-ms loaded-at-ms))]
      (when (and freshness-window-ms
                 (pos? freshness-window-ms)
                 (> stale-at-ms (+ clock-ms freshness-window-ms)))
        (- stale-at-ms clock-ms)))))

(defn hydrate-runtime-db
  "Reconciles the resource subtree of a runtime-db about to be installed on
  `:rf/hydrate`; the body of the `:resources/hydrate-runtime-db` hook. A
  runtime-db with no entries is returned unchanged. Per entry it drops
  unaddressable rows (cached HTML from an earlier deploy can still carry
  them), orphans SSR owners, and settles a `:loading` / `:fetching` entry to
  its last stable status, because the wire stripped its `:current-work` and
  it would otherwise render in flight forever. It then recomputes the
  indexes, emits `:rf.resource/hydrated` and any clock-skew warning, computes
  the refetch plan, and reconciles route readiness through the one projector.
  Never moves data between scopes. Spec 016 §SSR and hydration"
  ([runtime-db] (hydrate-runtime-db runtime-db nil))
  ([runtime-db frame-id]
   (let [resources (get runtime-db rf.resources.state/resources-key)
         entries   (:entries resources)]
     (if-not (seq entries)
       runtime-db
       (let [clock-ms (rf.interop/epoch-now-ms)
             ;; The accumulators name entries by SCOPED KEY, not key-id, because
             ;; they feed trace tags and a key-id is a reversible plaintext
             ;; encoding of scope and params. They are SEQUENCES because two
             ;; byte-distinct entries (vector vs list params) have `=` scoped
             ;; keys, and a keyed accumulator would collapse them.
             ;; Unaddressable rows are dropped here, where the fact is known
             ;; exactly once, rather than scanned for on every `ensure`.
             reconciled (reduce-kv
                          (fn [acc k entry]
                            (let [sk (:resource/key entry)]
                              (if (unaddressable-wire-key? sk)
                                (update acc :unaddressable conj sk)
                                (let [[entry' dropped] (reconcile-entry-owners
                                                         entry hydration-route-owner-policy)
                                      entry''          (settle-entry-to-last-stable entry')
                                      skew             (clock-skew-ms entry clock-ms)]
                                  (-> acc
                                      (assoc-in [:entries k] entry'')
                                      (update :orphaned into (map (fn [o] [sk o])) dropped)
                                      (cond-> skew (update :skews conj [sk skew])))))))
                          {:entries {} :orphaned [] :skews [] :unaddressable []}
                          entries)
             entries'  (:entries reconciled)
             subtree'  (rf.resources.state/recompute-indexes
                         (assoc resources :entries entries'))
             rdb'      (assoc runtime-db rf.resources.state/resources-key subtree')]
         (rf.trace/emit! :rf.event :rf.resource/hydrated
                      {:rf.frame/id    frame-id
                       :installed      (count entries')
                       :orphaned-owners (vec (:orphaned reconciled))
                       :clock-skews    (vec (:skews reconciled))
                       ;; Named, not counted, so a version-skewed payload does not
                       ;; read as one that carried fewer entries. The keys are
                       ;; already projected.
                       :unaddressable  (vec (:unaddressable reconciled))})
         (doseq [[sk skew] (:skews reconciled)]
           (rf.trace/emit! :warning :rf.resource/hydrate-clock-skew
                        {:rf.frame/id  frame-id
                         :resource/key sk
                         :skew-ms      skew
                         :reason       (str "hydrated entry's absolute :stale-at is "
                                            skew "ms ahead of the live client clock "
                                            "— server clock skew makes freshness "
                                            "ambiguous; refetch will resolve it.")}))
         ;; Computed here so the per-entry decision (a redacted entry is
         ;; metadata-only, not fresh data) is never lost; the route slice
         ;; issues the refetch itself.
         (hydrate-refetch-plan rdb' clock-ms frame-id)
         ;; A cached `:transition` / `:error` the reconciled entries contradict
         ;; would strand the route on a readiness no live work can move.
         ;; Spec 012 §Route readiness is a resource projection
         (rf.resources.route/reconcile-readiness rdb'))))))

;; ---- epoch-restore reconcile ----------------------------------------------
;;
;; Restore shares the hydration install path but installs the UNPROJECTED
;; snapshot, which still carries in-flight entries and instances holding live
;; attempts the restored timeline no longer owns. So on top of what hydration
;; does, restore orphans stale-nav route owners, settles in-flight entries to
;; last-stable, and dangles live attempts so a pre-restore reply is suppressed
;; by the ordinary work-id + generation check.
;; Restore never continues old work; a later live-owner `ensure` decides.
;; Spec 016 §Restore and replay

(defn settle-entry-to-last-stable
  "Returns `entry` with a `:loading` / `:fetching` status settled to `:loaded`
  (has data), `:error` (a first-load error, no data) or `:idle`; any other
  entry unchanged. Pure.
  Spec 016 §2. In-flight work does not survive restore as live work"
  [entry]
  (if (contains? #{:loading :fetching} (:status entry))
    (let [next (cond
                 (rf.resources.state/has-data? entry) :loaded
                 (some? (:error entry))  :error
                 :else                   :idle)]
      (assoc entry :status next))
    entry))

(defn dangle-non-terminal-work!
  "Returns `[runtime-db' dangled-work-ids]` with every live attempt, on an
  entry or a mutation instance, settled terminal `:suppressed` with a
  `:dangling` outcome, so a pre-restore reply finds no live attempt and is
  suppressed. Host handles are left to the restore teardown.
  Spec 016 §2. In-flight work does not survive restore as live work"
  [runtime-db]
  (let [outcome {:reason :dangling :recovery :restore-reconcile}
        live?   (fn [owner]
                  (contains? rf.resources.work-ledger/non-terminal-statuses
                             (:status (:work owner))))
        dangle  (fn [[rdb dangled] path]
                  ;; Owner paths are walked by their map's own byte ids, which
                  ;; `entry-path` / `instance-path` would re-encode.
                  (let [work-id (:work/id (:work (get-in rdb path)))]
                    [(update-in rdb path rf.resources.work-ledger/settle-attempt
                                work-id :suppressed outcome)
                     (conj dangled work-id)]))
        paths   (concat
                  (for [[k-id entry] (get-in runtime-db (rf.resources.state/entries-path))
                        :when (live? entry)]
                    (rf.resources.state/entry-path-by-id k-id))
                  (for [[k-id inst] (get-in runtime-db (rf.resources.mutation-runtime/instances-path))
                        :when (live? inst)]
                    (conj (rf.resources.mutation-runtime/instances-path) k-id)))]
    (reduce dangle [runtime-db []] paths)))

(defn dangle-pending-mutations!
  "Returns `[runtime-db' dangled-instance-ids rolled-back-keys]` with every
  pending mutation instance settled to `:error` (`:dangling-on-restore`) and
  its `:current-work` cleared. Without this a late pre-restore reply would
  still match the instance (mutation replies check the instance, not an
  entry) and write post-restore state. A dangled optimistic write rolls back
  in the same pure pass rather than in a later event that could race a fresh
  load; a dangled generated-id instance is retired as `:rf.mutation/clear`
  would, since no reply will ever retire it. `settled-at` stamps the settle.
  Spec 016 §2. In-flight work does not survive restore as live work"
  [runtime-db settled-at]
  (let [instances (get-in runtime-db (rf.resources.mutation-runtime/instances-path))
        ;; Iterate the map's own byte key-ids; `instance-path` would re-encode them.
        pending   (into []
                        (comp (filter (fn [[_ inst]] (rf.resources.mutation-runtime/pending? inst)))
                              (map key))
                        instances)]
    (if (empty? pending)
      [runtime-db [] []]
      (let [[rdb' rolled-keys dangled-ids]
            (reduce
              (fn [[rdb rk dids] key-id]
                (let [inst-path (conj (rf.resources.mutation-runtime/instances-path) key-id)
                      inst (get-in rdb inst-path)
                      ;; Roll back BEFORE settling, so the restored cache never
                      ;; shows an optimistic value with no write to confirm it.
                      spec (rf.resources.mutation-registry/mutation-meta (:mutation/id inst))
                      [rdb2 keys2] (rf.resources.mutation-runtime/dangle-rollback-optimistic
                                     rdb inst spec settled-at)
                      iid  (:instance/id inst)
                      rdb3 (update-in rdb2 inst-path
                                      rf.resources.mutation-runtime/instance-dangled settled-at)]
                  [(if (rf.resources.mutation-runtime/generated-instance-id? iid)
                     (update-in rdb3 (rf.resources.mutation-runtime/instances-path) dissoc key-id)
                     rdb3)
                   (into rk keys2)
                   (conj dids iid)]))
              [runtime-db [] []]
              pending)]
        [rdb' dangled-ids rolled-keys]))))

(defn clear-host-transients-on-restore!
  "Clears `frame-id`'s host-side timers and attempt handles armed by the
  pre-restore timeline, so neither fires against restored state; timers
  re-arm lazily from durable timestamps. It deliberately leaves the
  generation high-water mark (rewinding it would let a pre-restore reply
  re-match) and the revalidation listeners (frame-scoped, not epoch-scoped).
  Idempotent; returns nil.
  Spec 016 §5. Transient side tables and indexes are recomputed or cleared on install"
  [frame-id]
  (rf.resources.timers/release-frame! frame-id)
  (rf.resources.work-ledger/release-frame! frame-id)
  nil)

;; ---- deferred restore trace intents ---------------------------------------
;;
;; The reconcile runs before the atomic frame-state install, and the install
;; can still fail (a frame destroyed in the teardown window). So under
;; `:defer-traces? true` its trace rows ride back as data on the returned
;; runtime-db's metadata and are emitted only after a successful install. The
;; host-transient clear is not deferred: it is idempotent, and its only failure
;; path is a frame whose transients destroy already released.

(def ^:private deferred-trace-intents-key
  "Metadata key carrying the deferred restore trace intents."
  ::deferred-trace-intents)

(defn- emit-trace-intent!
  "Emits one `{:level :op :tags}` intent: `:error` through
  `rf.trace/emit-error!`, any other level through `rf.trace/emit!`."
  [{:keys [level op tags]}]
  (if (= :error level)
    (rf.trace/emit-error! op tags)
    (rf.trace/emit! level op tags))
  nil)

(defn commit-restore-reconcile-traces!
  "Emits every trace intent deferred on `reconciled-runtime-db`'s metadata;
  the body of the `:resources/commit-restore-reconcile!` hook, run only after
  a successful install. A no-op when none are deferred. It runs inside the
  restore's serialized drain section, so no listener runs between intents.
  Returns nil."
  [reconciled-runtime-db]
  (run! emit-trace-intent! (-> reconciled-runtime-db meta (get deferred-trace-intents-key)))
  nil)

(defn reconcile-on-restore
  "Reconciles the unprojected snapshot an epoch restore is about to install;
  the body of the `:resources/reconcile-on-restore` hook. Returns
  `runtime-db` unchanged when it has no entries or mutation instances.
  Otherwise, in order: orphans SSR and stale-nav route owners, clears work
  pointers and settles in-flight entries to last-stable; recomputes the
  indexes; dangles live attempts and pending mutation instances (rolling back
  their optimistic writes); reconciles
  route readiness last, over the settled facts; and clears the frame's host
  transients. Never moves data between scopes.

  `opts`: `:defer-traces?` returns the trace rows as metadata for
  `commit-restore-reconcile-traces!` instead of emitting them (and
  suppresses the readiness error emit), because the install can still fail;
  `:restore-time-ms` is the restore's causal time, the source of a dangled
  instance's durable `:settled-at`. The 1-/2-arity emits inline and falls
  back to the live clock, which is safe only because it installs no epoch.
  Spec 016 §Restore and replay"
  ([runtime-db] (reconcile-on-restore runtime-db nil nil))
  ([runtime-db frame-id] (reconcile-on-restore runtime-db frame-id nil))
  ([runtime-db frame-id {:keys [defer-traces? restore-time-ms]}]
   (let [resources (get runtime-db rf.resources.state/resources-key)
         entries   (:entries resources)
         mutations (get-in runtime-db (rf.resources.mutation-runtime/instances-path))
         ;; Present because restore installs both partitions wholesale; nil
         ;; when the app has no routing slice.
         live-nav-token (get-in runtime-db routing-current-nav-token-path)]
     (if-not (or (seq entries) (seq mutations))
       runtime-db
       (let [clock-ms (rf.interop/epoch-now-ms)
             route-owner-policy (restore-route-owner-policy live-nav-token)
             ;; Scoped keys and sequences, for the reasons in `hydrate-runtime-db`;
             ;; doubly so here, since a live snapshot's key-ids carry raw
             ;; scope and params.
             reconciled (reduce-kv
                          (fn [acc k entry]
                            (let [[entry' dropped] (reconcile-entry-owners entry route-owner-policy)
                                  entry''          (settle-entry-to-last-stable entry')
                                  skew             (clock-skew-ms entry clock-ms)
                                  sk               (:resource/key entry)]
                              (-> acc
                                  (assoc-in [:entries k] entry'')
                                  (update :orphaned into (map (fn [o] [sk o])) dropped)
                                  (cond-> skew (update :skews conj [sk skew])))))
                          {:entries {} :orphaned [] :skews []}
                          entries)
             entries'  (:entries reconciled)
             subtree'  (rf.resources.state/recompute-indexes
                         (assoc resources :entries entries'))
             rdb'      (cond-> runtime-db
                         (seq entries) (assoc rf.resources.state/resources-key subtree'))
             [rdb'' dangled] (dangle-non-terminal-work! rdb')
             ;; A durable field must come from causal time, never an ambient
             ;; read at install; `clock-ms` feeds only the skew diagnostic.
             ;; EP-0010 §Restore, Replay, And Hydration
             settled-at (or restore-time-ms clock-ms)
             ;; A rollback can re-create or drop entries and tags, so the
             ;; indexes are recomputed again when it touched the cache.
             [rdb-dangled dangled-mutations rolled-mutation-keys]
             (dangle-pending-mutations! rdb'' settled-at)
             rdb''' (if (seq rolled-mutation-keys)
                      (update rdb-dangled rf.resources.state/resources-key rf.resources.state/recompute-indexes)
                      rdb-dangled)
             intents (into
                       [{:level :rf.epoch
                         :op    :rf.resource/restored
                         :tags  {:rf.frame/id       frame-id
                                 :reconciled        (count entries')
                                 :orphaned-owners   (vec (:orphaned reconciled))
                                 :dangled-work      (vec dangled)
                                 :dangled-mutations (vec dangled-mutations)
                                 :rolled-back-mutation-keys (vec rolled-mutation-keys)
                                 :clock-skews       (vec (:skews reconciled))}}]
                       cat
                       [;; One row per released stale-nav route owner; SSR orphans
                        ;; ride the summary above.
                        (for [[sk owner] (:orphaned reconciled)
                              :when (route-owner? owner)]
                          {:level :rf.epoch
                           :op    :rf.resource/owner-released
                           :tags  {:rf.frame/id    frame-id
                                   :resource/key   sk
                                   :owner          owner
                                   :nav-token      (nth owner 2 nil)
                                   :live-nav-token live-nav-token
                                   :reason         :stale-nav-orphan
                                   :recovery       :restore-reconcile}})
                        (for [[sk skew] (:skews reconciled)]
                          {:level :warning
                           :op    :rf.resource/restore-clock-skew
                           :tags  {:rf.frame/id  frame-id
                                   :resource/key sk
                                   :skew-ms      skew
                                   :reason       (str "restored entry's absolute :stale-at is "
                                                      skew "ms ahead of the live clock — clock "
                                                      "skew makes freshness ambiguous; the next "
                                                      "live-owner ensure will resolve it.")}})
                        ;; A production restore always threads `:restore-time-ms`,
                        ;; so dangling real mutations without it is a
                        ;; replay-determinism hazard, surfaced loudly rather than
                        ;; refused.
                        (when (and (seq dangled-mutations) (nil? restore-time-ms))
                          [{:level :warning
                            :op    :rf.resource/restore-settled-at-from-live-clock
                            :tags  {:rf.frame/id        frame-id
                                    :dangled-mutations  (vec dangled-mutations)
                                    :settled-at         settled-at
                                    :reason             (str (count dangled-mutations)
                                                             " pending mutation instance(s) were "
                                                             "dangled on restore with NO causal "
                                                             ":restore-time-ms — their durable "
                                                             ":settled-at was stamped from the live "
                                                             "install clock (" settled-at "), which "
                                                             "is NOT replay-stable (EP-0010 §Time + "
                                                             "§Restore/Replay). Thread the restored "
                                                             "epoch's :committed-at as "
                                                             ":restore-time-ms so the durable stamp "
                                                             "folds the causal token. (Expected nil "
                                                             "only on the pure-unit 1-/2-arity, which "
                                                             "installs no epoch.)")
                                    :recovery           :restore-reconcile}}])])]
         ;; Not deferred (idempotent). It addresses the frame by bare id
         ;; before the atomic install, and reaches the right incarnation
         ;; because the restore holds the frame's drain serialization and
         ;; nothing between the liveness gate and here runs a callback. The
         ;; 1-arity passes no frame and has no host tables to clear.
         (when frame-id
           (clear-host-transients-on-restore! frame-id))
         ;; Last, so readiness projects over the facts actually installed: a
         ;; mid-load snapshot would otherwise restore a `:loading` whose work
         ;; was just dangled. Spec 012 §Route readiness is a resource projection
         (let [rdb-final (rf.resources.route/reconcile-readiness
                           rdb''' {:emit-error? (not defer-traces?)})]
           (if defer-traces?
             (vary-meta rdb-final assoc deferred-trace-intents-key intents)
             (do (run! emit-trace-intent! intents)
                 rdb-final))))))))

;; ---- client refetch decision ----------------------------------------------

(defn hydrated-data-usable?
  "True iff a hydrated `entry` has usable data: not the redaction sentinel,
  and `rf.resources.state/has-data?` (so an empty infinite page vector is no
  data). The sentinel check runs first because `has-data?` would `seq` the
  sentinel keyword of a redacted infinite entry.
  Spec 016 §SSR and hydration"
  [entry]
  (let [d (:data entry)]
    (and (not= rf.privacy/redacted-sentinel d)
         (rf.resources.state/has-data? entry))))

(defn entry-needs-refetch?
  "False only for a fresh hydrated entry with usable data (no double fetch);
  true for stale, metadata-only (redacted or omitted) and errored entries.
  It answers whether the hydrated value suffices; the route plan decides
  whether the route still needs it. Spec 016 §SSR and hydration"
  [entry clock-ms]
  (let [usable? (hydrated-data-usable? entry)]
    (cond
      (not usable?)                true
      (rf.resources.state/entry-stale? entry clock-ms) true
      :else                         false)))

(defn hydrate-refetch-plan
  "The hydrated entries that need a client refetch, as a vector of
  `{:resource/key :resource-id :reason}` (`:metadata-only` / `:stale` /
  `:no-data`), emitting one `:rf.resource/hydrate-refetch` trace per row.
  Unaddressable rows are never planned: their key names a fetch nobody can
  issue. The route slice issues the refetches it still needs. `frame-id`
  tags the traces. Spec 016 §SSR and hydration"
  ([runtime-db] (hydrate-refetch-plan runtime-db (rf.interop/epoch-now-ms) nil))
  ([runtime-db clock-ms] (hydrate-refetch-plan runtime-db clock-ms nil))
  ([runtime-db clock-ms frame-id]
   (let [entries (get-in runtime-db [rf.resources.state/resources-key :entries])
         plan    (into []
                       (comp
                         ;; The reconcile already dropped these on the
                         ;; `:rf/hydrate` path; hosts and tests also call this
                         ;; directly on an unreconciled slice.
                         (remove (fn [[_ entry]] (unaddressable-wire-key? (:resource/key entry))))
                         (filter (fn [[_ entry]] (entry-needs-refetch? entry clock-ms)))
                         (map (fn [[_k-id entry]]
                                {:resource/key (:resource/key entry)
                                 :resource-id  (second (:resource/key entry))
                                 :reason       (cond
                                                 ;; Distinct from `:no-data`, so a
                                                 ;; redaction refetch reads apart
                                                 ;; from a large-omission one.
                                                 (= rf.privacy/redacted-sentinel (:data entry)) :metadata-only
                                                 (not (rf.resources.state/has-data? entry))       :no-data
                                                 (rf.resources.state/entry-stale? entry clock-ms) :stale
                                                 :else                         :metadata-only)})))
                       entries)]
     (doseq [{:keys [resource-id reason] resource-key :resource/key} plan]
       (rf.trace/emit! :rf.event :rf.resource/hydrate-refetch
                    {:rf.frame/id  frame-id
                     :resource/key resource-key
                     :resource-id  resource-id
                     :reason       reason
                     :cause        :hydration}))
     plan)))

;; ---- client hydration timer rearm -----------------------------------------

(defn rearm-timers-after-hydration!
  "Arms the GC and stale timers of every entry in `frame-id`'s runtime-db, and
  the poll timer of each owned entry whose resource declares
  `:poll-interval-ms`, after a client `:rf/hydrate` commit. The body of the
  `:resources/rearm-after-hydration!` hook and the `:rf.resource/hydrate-rearm`
  fx. Returns nil.

  Hydration has to arm them because no client ensure does: a hydrated entry
  is already owned or no ensure runs at all. Without it an orphaned entry
  never collects, a polling resource never polls, and a held `:stale?` read
  never flips. Each fired handler re-checks the live facts, so arming is
  safe. Delays come from the resource's normalized `:gc-after-ms` /
  `:poll-interval-ms` and the remaining stale window; `:never`, absent or
  unregistered arms nothing. `schedule!` is cancel-then-arm, so repeats are
  idempotent. A `:server` frame arms nothing.
  Spec 016 §Freshness clock contract"
  [frame-id]
  (when (and frame-id (not (rf.resources.state/server-frame? frame-id)))
    (let [now-ms  (rf.interop/epoch-now-ms)
          entries (get-in (rf.frame/frame-runtime-db-value frame-id)
                          (rf.resources.state/entries-path))]
      (doseq [[_ entry] entries
              :let  [policy   (rf.resources.registry/resource-meta (:resource/id entry))
                     gc-ms    (rf.resources.state/positive-or-nil (:gc-after-ms policy))
                     stale-ms (rf.resources.state/stale-wake-delay-ms entry now-ms)
                     poll-ms  (when (seq (:active-owners entry))
                                (rf.resources.state/positive-or-nil (:poll-interval-ms policy)))]]
        (when gc-ms
          (rf.resources.timers/schedule! frame-id (:resource/key entry)
                                         rf.resources.timers/gc-kind gc-ms))
        (when stale-ms
          (rf.resources.timers/schedule! frame-id (:resource/key entry)
                                         rf.resources.timers/stale-kind stale-ms))
        (when poll-ms
          (rf.resources.timers/schedule! frame-id (:resource/key entry)
                                         rf.resources.timers/poll-kind poll-ms)))))
  nil)

;; ---- install / publish ----------------------------------------------------

(defn install-ssr-integration!
  "Publishes the late-bound SSR, hydration and restore hooks this namespace
  implements. They sit unread in an app that never SSRs or time-travels, and
  are no-ops without resources. Published from the `re-frame.resources`
  façade so a `:reload` re-wires them. Spec 016 §SSR and hydration"
  []
  (rf.late-bind/set-fns!
    {:ssr/extend-runtime-db-projection project-resources-runtime-db
     :resources/drain-blocking-ssr!    drain-blocking-resources!
     :resources/hydrate-runtime-db     hydrate-runtime-db
     :resources/reconcile-on-restore   reconcile-on-restore
     :resources/commit-restore-reconcile! commit-restore-reconcile-traces!
     :resources/rearm-after-hydration! rearm-timers-after-hydration!})
  nil)

(defn hydrate-resources!
  "Reconciles `frame-id`'s installed resource subtree (`hydrate-runtime-db`)
  and returns its refetch plan, for a host that drives hydration
  imperatively rather than through `:rf/hydrate`. `frame-id` is explicit;
  there is no ambient fallback. Spec 016 §SSR and hydration"
  [frame-id]
  (let [rdb (rf.frame/swap-runtime-db! frame-id hydrate-runtime-db frame-id)]
    (hydrate-refetch-plan (or rdb {}) (rf.interop/epoch-now-ms) frame-id)))
