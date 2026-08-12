(ns day8.re-frame2-xray.panels.hicasso-causal
  "ONE complete causal slice, link by link (rf2-hic-037).

  Spec SN §10 states the causal lens as a chain:

      event -> subscriptions recomputed -> values changed
            -> boundaries notified -> bodies run -> React commit -> paint

  and then states the rule that makes it hard: *each link needs an
  explicit evidence seam. Timing proximity never proves commit, paint,
  hidden, or suspended state.* This namespace renders the WHOLE chain for
  one real dispatch against one real boundary, and every link is either
  read off a named seam or labelled with what it is missing.

  ## The chain is not evenly evidenced, and that is the finding

  | # | Link | Seam | Basis |
  |---|---|---|---|
  | 1 | event | Spec 009's retained ring, the bundle for this dispatch | `:observation` |
  | 2 | subscriptions recomputed | that bundle's `:subs` events, keyed `:rf.sub/id` | `:observation` |
  | 3 | values changed | the cell table's epoch stamps, at the boundary's peak | `:observation` |
  | 4 | boundaries notified | the cells' reader arrays — the reverse edge `notify!` walks | `:derivation` |
  | 5 | bodies run | — | `:host-opaque` |
  | 6 | React commit | — | `:host-opaque` |
  | 7 | paint | — | `:host-opaque` |

  **A link's own basis is not the same question as whether it joins to
  the one before it**, and collapsing the two is how a causal display
  starts lying. Links 2 and 3 are both `:observation` — the sub really
  recomputed, the cell's epoch really moved — and the join between them
  is `:uncorrelated`, because Hicasso's commit seam records no cascade id
  and an epoch stamp carries no dispatch. Two solid facts, printed
  adjacent, joined by nothing: exactly the adjacency `re-frame.hicasso.tool`
  refuses to call causality, surfaced here as its own row rather than
  implied away by the arrow between two green links.

  Link 4 is the other one worth reading twice. The reverse edge IS what
  `collector/notify!` walks, so the notified set is derivable — but the
  notify CALL is not recorded, and the reader arrays are read at ASK time
  rather than at commit time. A boundary that unmounted between the
  commit and this read is absent from a set it was in. The link says so.

  ## Every link is mutation-tested

  A display that renders `evidenced` from a seam it is not actually
  reading is worse than one that renders `unknown`, and the two are
  indistinguishable from the outside on a healthy application. So each
  evidenced link has a sabotage row in `hicasso_causal_cljs_test`: break
  that link's seam, and the link must stop being evidenced — degrading to
  a stated loss, never to a confident wrong answer, and never borrowing a
  neighbouring seam to keep its colour. Each sabotage carries the
  positive control that proves the link was green before it.

  Pure data → data, CLJC, so the algebra runs under the JVM target too.

  Normative owner: `tools/xray/spec/028-Hicasso-Advisor.md`."
  (:require [clojure.string :as string]
            [day8.re-frame2-xray.panels.hicasso-helpers :as hh]))

(def causal-schema
  "Xray's own stamp. See `hicasso-advisor/advice-schema` for why the
  producer's is not borrowed for a derivation the producer never made."
  :day8.re-frame2-xray.hicasso-causal/v1)

(def causal-producer :re-frame2/xray)

;; ---------------------------------------------------------------------------
;; Picking the slice
;; ---------------------------------------------------------------------------

(defn newest-dispatch
  "The highest numeric `:dispatch-id` in the retained windows, or nil.

  The id is allocated process-monotonically at queue time, so the highest
  retained one is the most recent dispatch the ring still holds — the
  interaction a developer just performed. Non-numeric ids (the producer's
  unjoinable sentinel) are skipped rather than sorted after: an id that
  cannot be joined cannot anchor a slice."
  [windows]
  (let [ids (for [[_ bundles] windows
                  b           bundles
                  :let        [d (:dispatch-id b)]
                  :when       (number? d)]
              d)]
    (when (seq ids) (reduce max ids))))

(defn- bundle-for
  "The `[frame-id bundle]` for `dispatch-id`, or nil.

  A dispatch that touched two frames is captured in both rings; the first
  in frame order is taken and the OTHER frames are reported beside it, so
  the slice never silently describes half of what a dispatch did."
  [windows dispatch-id]
  (first (for [fid (sort-by pr-str (keys windows))
               b   (get windows fid)
               :when (= dispatch-id (:dispatch-id b))]
           [fid b])))

(defn- frames-touched
  [windows dispatch-id]
  (vec (sort-by pr-str
                (for [[fid bundles] windows
                      :when (some #(= dispatch-id (:dispatch-id %)) bundles)]
                  fid))))

(defn- explanation-for
  "The `explain-render` row for `boundary-key`, or nil."
  [explain boundary-key]
  (when (hh/supported? explain)
    (first (filter #(= boundary-key (:key (:boundary %))) (:explanations explain)))))

;; ---------------------------------------------------------------------------
;; The seven links
;; ---------------------------------------------------------------------------

(def host-opaque-links
  "Links 5, 6 and 7 — stated once, as data.

  They are not three phrasings of one absence. A body that ran and a
  commit that landed are different events with different authorities, and
  a paint is a third; a reader deciding what to open next needs to know
  which one they are missing. React DevTools answers the first two, the
  browser's performance tools the third."
  [{:id    :bodies-run
    :label "bodies run"
    :says  (str "Whether a notified boundary re-ran, retried, was abandoned or "
                "was bailed out by its memo comparator is React's to know. The "
                "comparator sits ABOVE the boundary's own function, so a "
                "bail-out never enters it. Hicasso emits no `:rf.view/render` "
                "trace, and per-boundary self time was killed as a decision: "
                "the 0.1 ms timer grain is coarser than the quantity, so a "
                "ranking built on it would order noise.")
    :authority "React DevTools Profiler"}
   {:id    :react-commit
    :label "React commit"
    :says  (str "A render measure is not a commit. React consults its own "
                "scheduled work above this runtime, and a notification "
                "delivered is not a render performed, let alone one committed.")
    :authority "React DevTools Profiler"}
   {:id    :paint
    :label "paint"
    :says  (str "Nothing in a subscription table or a trace ring observes the "
                "compositor. Timing proximity to a commit never proves a "
                "paint.")
    :authority "the browser's own performance tools"}])

(defn- link-event
  [frame-id bundle dispatch-id frames]
  (if (nil? bundle)
    {:id :event :ordinal 1 :label "event"
     :seam "Spec 009's per-frame retained ring"
     :basis :cap :evidenced? false
     :holds hh/unknown
     :loss  {:reason :cap :dropped hh/unknown}
     :says  (str "No bundle for dispatch " (hh/format-id dispatch-id)
                 " is retained. The ring holds `:rf.trace/events-retained` "
                 "runs and cannot say what fell off it — so this is a capped "
                 "window, not a dispatch that never happened.")}
    {:id :event :ordinal 1 :label "event"
     :seam "Spec 009's per-frame retained ring"
     :basis :observation :evidenced? true
     :holds {:dispatch-id dispatch-id
             :event-id    (if (vector? (:event bundle)) (nth (:event bundle) 0) hh/unknown)
             ;; The ARITY, never the arguments. The classification model is
             ;; fail-open, so an event that declared nothing would ship its
             ;; arguments raw through the egress chokepoint — and a slice
             ;; that promised no application data while routing an
             ;; undeclared payload through would be making the promise
             ;; falsely. The Trace surface is where Spec 015 governs that.
             :arg-count   (if (vector? (:event bundle)) (dec (count (:event bundle))) hh/unknown)
             :frame       frame-id
             :frames      frames}
     :loss  {:reason :cap :dropped hh/unknown}
     :says  "read off the retained bundle for this dispatch."}))

(defn- link-subs
  [bundle]
  (if (nil? bundle)
    {:id :subs-recomputed :ordinal 2 :label "subscriptions recomputed"
     :seam "the bundle's `:subs` trace events"
     :basis :cap :evidenced? false
     :holds hh/unknown
     :loss  {:reason :cap :dropped hh/unknown}
     :joins {:on :rf.trace/dispatch-id :status :cap
             :says "no bundle to join to."}
     :says  "the window holds no bundle for this dispatch."}
    (let [subs    (:subs bundle)
          named   (into [] (distinct) (keep #(get-in % [:tags :rf.sub/id]) subs))
          unnamed (count (remove #(get-in % [:tags :rf.sub/id]) subs))]
      {:id :subs-recomputed :ordinal 2 :label "subscriptions recomputed"
       :seam "the bundle's `:subs` trace events, keyed `:rf.sub/id`"
       :basis :observation
       :evidenced? (or (seq named) (zero? unnamed))
       ;; An empty roster with no unnamed runs is a genuine survey result:
       ;; this dispatch recomputed nothing. An empty roster WITH unnamed
       ;; runs is not — work happened and joins to no subscription — so the
       ;; field states `:unknown` rather than `[]`, which is the one
       ;; substitution the evidence schema exists to refuse.
       :holds (if (and (empty? named) (pos? unnamed)) hh/unknown named)
       :loss  (if (pos? unnamed)
                {:reason :uncorrelated :dropped unnamed}
                {:reason :cap :dropped hh/unknown})
       :joins {:on :rf.trace/dispatch-id :status :evidenced
               :says (str "every event in the bundle carries this dispatch's "
                          "own `:rf.trace/dispatch-id` — the ring groups by it, "
                          "so the join is the storage and not an inference.")}
       :says  (if (pos? unnamed)
                (str unnamed " sub run" (when (not= 1 unnamed) "s")
                     " in this bundle carried no `:rf.sub/id` and join to no "
                     "subscription.")
                "read off the bundle's own sub events.")})))

(defn- link-values
  [ex]
  (cond
    (nil? ex)
    {:id :values-changed :ordinal 3 :label "values changed"
     :seam "the cell table's epoch stamps"
     :basis :observation :evidenced? false
     :holds hh/unknown :loss nil
     :joins {:on nil :status :uncorrelated
             :says (str "no explanation row for this boundary — the census and "
                        "the slice disagree about what is mounted.")}
     :says  "no `explain-render` row holds this boundary."}

    (hh/unknown? (:latest-reads ex))
    {:id :values-changed :ordinal 3 :label "values changed"
     :seam "the cell table's epoch stamps"
     :basis :observation :evidenced? false
     :holds hh/unknown :loss nil
     :joins {:on nil :status :uncorrelated
             :says "nothing to join — no epoch moved for this boundary."}
     :says  (str "this boundary holds no read with an epoch, so no value of "
                 "its can have moved. That is a fact about the boundary, not "
                 "a gap in the instrument.")}

    :else
    {:id :values-changed :ordinal 3 :label "values changed"
     :seam "the cell table's epoch stamps"
     :basis :observation :evidenced? true
     :holds {:latest-reads (:latest-reads ex)
             :peak-epoch   (:peak-epoch ex)
             ;; The exact number React compares in `checkIfSnapshotChanged`.
             :snapshot     (:snapshot ex)}
     :loss  nil
     ;; THE UNCORRELATED JOIN. Both sides are solid and nothing links them.
     :joins {:on nil :status :uncorrelated
             :says (str "an epoch stamp carries no dispatch id and Hicasso's "
                        "commit seam records no cascade id, so the recompute "
                        "above and the movement here CANNOT be joined. They "
                        "are printed adjacent because the chain runs that way, "
                        "not because this slice can show one caused the other. "
                        "Leads live on the Why view.")}
     :says  (str "every commit re-stamps a moved cell's epoch, so the reads at "
                 "this boundary's highest epoch are the ones whose values "
                 "moved most recently — read off the table.")}))

(defn- link-notified
  [attribution ex]
  (cond
    (or (nil? ex) (hh/unknown? (:latest-reads ex)))
    {:id :boundaries-notified :ordinal 4 :label "boundaries notified"
     :seam "the cells' reader arrays — the reverse edge `notify!` walks"
     :basis :derivation :evidenced? false
     :holds hh/unknown :loss nil
     :joins {:on nil :status :uncorrelated
             :says "no moved read to take a reverse edge from."}
     :says  "there is no moved read whose readers could be named."}

    (not (hh/supported? attribution))
    {:id :boundaries-notified :ordinal 4 :label "boundaries notified"
     :seam "the cells' reader arrays — the reverse edge `notify!` walks"
     :basis :derivation :evidenced? false
     :holds hh/unknown :loss nil
     :joins {:on [:frame-id :sub-id] :status :uncorrelated
             :says "the reverse-edge table is not readable on this host."}
     ;; NOT substituted from the mounted census. The census carries each
     ;; boundary's own reads, so a plausible-looking notified set could be
     ;; assembled from it — and it would be a different fact, assembled
     ;; from the forward edge, printed under the reverse edge's name.
     :says  (str "the read-attribution envelope is absent or unparseable, so "
                 "the reverse edge cannot be read. The mounted census is NOT "
                 "substituted: it holds the forward edge, and answering a "
                 "reverse-edge question from it would be a different fact "
                 "under this one's name.")}

    :else
    (let [moved   (into #{} (map (juxt :frame-id :sub-id)) (:latest-reads ex))
          matched (filter #(contains? moved [(:frame-id %) (:sub-id %)]) (:edges attribution))
          readers (into [] (distinct) (mapcat :readers matched))]
      {:id :boundaries-notified :ordinal 4 :label "boundaries notified"
       :seam "the cells' reader arrays — the reverse edge `notify!` walks"
       :basis :derivation :evidenced? true
       :holds {:readers  readers
               :fan-out  (reduce + 0 (map #(or (:fan-out %) 0) matched))
               :cells    (mapv #(select-keys % [:frame-id :sub-id :query :fan-out]) matched)}
       :loss  nil
       :joins {:on [:frame-id :sub-id] :status :evidenced
               :says (str "the moved reads and the cell table are keyed the "
                          "same way, so this join is an equality on the "
                          "producer's own identity rather than a correlation.")}
       :says  (str "a commit unions the dirty cells' readers and notifies "
                   "them, and the reader array IS that set — one slot per "
                   "reading boundary, maintained by the same commit and "
                   "cleanup that acquire and release the reference. DERIVED, "
                   "not observed: the notify CALL is not recorded, and this "
                   "array is read now rather than at commit time, so a "
                   "boundary that unmounted in between is absent from a set "
                   "it was in.")})))

(defn- link-host
  [{:keys [id label says authority]} ordinal]
  {:id id :ordinal ordinal :label label
   :seam nil
   :basis :host-opaque :evidenced? false
   :holds hh/unknown
   :loss  {:reason :host-opaque :dropped hh/unknown}
   :joins {:on nil :status :host-opaque
           :says (str "not joinable from here — " authority " is the authority.")}
   :authority authority
   :says says})

;; ---------------------------------------------------------------------------
;; The slice
;; ---------------------------------------------------------------------------

(defn slice
  "The seven-link causal chain for one dispatch against one boundary.

      (slice {:envelopes envelopes :windows windows
              :dispatch-id 41 :boundary-key [[:app/main :todo [:todo 7]]]})

  `:dispatch-id` defaults to [[newest-dispatch]] — the most recent
  interaction the ring still holds. `:boundary-key` has no default here:
  the panel passes the advisor's top-ranked boundary, so the slice is
  about the boundary the roster just pointed at.

  The envelope's own `:loss` is `:uncorrelated` and its `:complete?` is
  false, and neither depends on how well the run went. Three links are
  host-opaque by construction and the 2→3 join has no id to make; a slice
  that reported itself complete because its first four links came back
  green would be claiming the chain, having evidenced its prefix."
  [{:keys [envelopes windows dispatch-id boundary-key]}]
  (let [did            (or dispatch-id (newest-dispatch windows))
        [fid bundle]   (when did (bundle-for windows did))
        frames         (if did (frames-touched windows did) [])
        ex             (explanation-for (:explain-render envelopes) boundary-key)
        attribution    (:read-attribution envelopes)
        links          (into [(link-event fid bundle did frames)
                              (link-subs bundle)
                              (link-values ex)
                              (link-notified attribution ex)]
                             (map-indexed (fn [i l] (link-host l (+ 5 i))))
                             host-opaque-links)]
    {:schema     causal-schema
     :producer   causal-producer
     :read       :causal-slice
     :scope      {:dispatch-id (or did hh/unknown)
                  :boundary    boundary-key
                  :frames      frames}
     :basis      :derivation
     :complete?  false
     :loss       {:reason :uncorrelated :dropped hh/unknown}
     :links      links
     :evidenced  (count (filter :evidenced? links))
     :total      (count links)}))

(defn slice-summary
  "One line: how much of the chain this run could show.

  Counts links and JOINS separately, because they answer different
  questions and a reader who conflates them reads four green links as a
  proven cause. The join count is the one that says whether the arrow
  between two facts means anything."
  [s]
  (when s
    (let [links (:links s)
          joins (keep :joins links)]
      (string/join " · "
                   [(str (:evidenced s) " of " (:total s) " links evidenced")
                    (str (count (filter #(= :evidenced (:status %)) joins))
                         " of " (count joins) " joins evidenced")
                    (str "dispatch " (hh/format-id (get-in s [:scope :dispatch-id])))]))))
