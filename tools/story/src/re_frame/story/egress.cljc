(ns re-frame.story.egress
  "Human-facing egress — the reproducibility contract behind Story's
  share / static-export / copy / screenshot commands (spec/022 §3,
  spec/018 §4 T4; rf2-ba86n.16).

  ## What this is NOT

  This is NOT a privacy / redaction seam. A local developer already has
  programmatic access to their own app's state and secrets, so redacting
  the URLs, EDN, static builds, and screenshots they emit of their OWN
  running app is futile and not the goal (Mike's 2026-05-30 reframe). The
  two real redaction points — the AI/MCP boundary and logs — are handled
  elsewhere (rf2-m25hd verified the MCP gate; rf2-6773q scrubbed logs).
  Human egress ships freely and is NOT privacy-gated.

  ## What this IS

  The genuinely valuable half of the T4 tension (spec/018 §4): a
  REPRODUCIBILITY honesty contract. A shared / exported / copied artifact
  states whether the recipient can reproduce it:

  - `:full`      — every input that drives the variant survives the EDN /
                   URL round-trip. Paste the URL or the EDN and you land on
                   the exact same cell.
  - `:partial`   — most state carries, but something is omitted or does not
                   survive serialisation (a cell-override value that is not
                   readable EDN, a `:sub-overrides` value that is a function,
                   a `:network` reply that is a closure, share-URL overrides
                   that no longer apply). The recipient reproduces a degraded-
                   but-usable approximation.
  - `:view-only` — the variant fundamentally cannot be replayed from the
                   artifact (a function pinned as an arg value the view calls,
                   external state the artifact cannot capture). The recipient
                   gets a view-only snapshot.

  This is `\"can the recipient reproduce this?\"`, never `\"is this
  sensitive?\"`. The classifier walks a variant's compiled PLAN plus the
  share params and reports a status + the reasons that downgraded it, so the
  UI can say WHAT made an artifact less than fully replayable.

  ## Pure / portable

  Everything here is pure data → data, JVM + CLJS portable, so the JVM
  test corpus covers the classification without booting Reagent / the
  runtime. The fn-detection rides `re-frame.story.fingerprint`'s
  `Canonicalise` protocol — the same opaque-fn fold the plan-hash uses —
  so a value is judged exactly the way the hashing layer judges it."
  (:require [re-frame.story.fingerprint :as fingerprint]
            #?(:clj  [clojure.edn :as edn]
               :cljs [cljs.reader :as edn])))

;; ---- status ordering -----------------------------------------------------

(def statuses
  "The reproducibility statuses, highest fidelity first. A classifier that
  collects several reasons takes the LOWEST status any reason implies — one
  view-only reason makes the whole artifact view-only."
  [:full :partial :view-only])

(def ^:private status-rank
  {:full 0 :partial 1 :view-only 2})

(defn worse
  "Return the LOWER-fidelity of two reproducibility statuses (`:view-only`
  beats `:partial` beats `:full`). Pure. The fold a reason-collector uses to
  arrive at the artifact's overall status."
  [a b]
  (if (>= (status-rank a 0) (status-rank b 0)) a b))

(def status-labels
  "Human-readable label per status — the text the share/export UI shows."
  {:full      "fully reproducible"
   :partial   "partially reproducible"
   :view-only "view-only"})

;; ---- pure: function / EDN-round-trip detection ---------------------------

(defn contains-fn?
  "True iff `x` (walked recursively through maps / vectors / sets / seqs)
  carries a function value anywhere. Pure. Rides the SAME `Canonicalise`
  fold the plan-hash uses (`fingerprint/canonical-form`): a genuine fn
  canonicalises to the `:rf/opaque-fn` sentinel, so detecting the sentinel
  in the canonical form is exactly detecting a fn in the original — across
  JVM and CLJS, without a host-specific `fn?`/`function` walk here."
  [x]
  (let [canon (fingerprint/canonical-form x)]
    (loop [stack [canon]]
      (cond
        (empty? stack) false
        :else
        (let [[h & t] stack]
          (cond
            (= h fingerprint/opaque-fn) true
            (map? h)        (recur (into (vec t) (concat (keys h) (vals h))))
            (sequential? h) (recur (into (vec t) h))
            (set? h)        (recur (into (vec t) h))
            :else           (recur (vec t))))))))

(defn edn-round-trips?
  "True iff `x` survives a `pr-str` → `read-string` round-trip to an EQUAL
  value. Pure-ish: the only impurity is `read-string` over our OWN
  `pr-str` output (data → data). This is the test for whether a value
  fits in the share URL's `overrides=` slot / the copied EDN snapshot.
  A value carrying a fn never round-trips (the fn prints as an unreadable
  `#object[...]` tagged literal), so `contains-fn?` is the strong signal;
  this catches the rarer non-fn non-EDN value (a host object, a regex on
  some hosts, etc.) too."
  [x]
  (if (contains-fn? x)
    false
    (try
      (= x (edn/read-string (pr-str x)))
      (catch #?(:clj Exception :cljs :default) _ false))))

;; ---- reasons --------------------------------------------------------------
;;
;; A reason is `{:status :partial|:view-only :code <kw> :detail <string>}`.
;; `:code` is a stable machine token the UI can data-test against; `:detail`
;; is the human sentence the share/export panel surfaces verbatim.

(defn- non-edn-overrides
  "Reasons for any share-URL / copied cell-override whose VALUE does not
  survive the EDN round-trip. A fn-valued override is `:view-only` (the
  recipient cannot reconstruct the closure the view calls); any other
  non-round-tripping value is `:partial` (the override is simply dropped on
  the recipient's side, the rest of the cell still applies)."
  [cell-overrides]
  (keep (fn [[k v]]
          (cond
            (contains-fn? v)
            {:status :view-only
             :code   :override-fn
             :detail (str "the `" k "` override pins a function value that "
                          "cannot be serialised — the recipient gets a "
                          "view-only snapshot for this arg")}
            (not (edn-round-trips? v))
            {:status :partial
             :code   :override-non-edn
             :detail (str "the `" k "` override value does not survive the "
                          "share-URL round-trip and is dropped for the "
                          "recipient")}
            :else nil))
        (or cell-overrides {})))

(defn- world-reasons
  "Reasons drawn from the variant's compiled-plan `:world` slot — the
  inputs that drive a run but may not survive into a shared/exported
  artifact.

  - A `:sub-overrides` value that is a function is `:view-only` for that
    subscription: the recipient cannot reconstruct the closure (an exact
    pinned-data override is fine — it serialises).
  - A `:network` route whose `:reply` is a function is `:partial`: the
    recipient loses the dynamic reply but the route + static shape carry.
  - `:setup` events / a `:script` carrying a function step is `:partial`:
    most of the program serialises; the fn step degrades."
  [plan]
  (let [world (:world plan)
        subs  (get-in world [:render :sub-overrides])
        net   (:network world)]
    (concat
      (keep (fn [[q v]]
              (when (contains-fn? v)
                {:status :view-only
                 :code   :sub-override-fn
                 :detail (str "the subscription override for `" (pr-str q)
                              "` pins a function — the recipient gets a "
                              "view-only snapshot for this signal")}))
            (or subs {}))
      (keep (fn [[route reply]]
              (when (contains-fn? reply)
                {:status :partial
                 :code   :network-reply-fn
                 :detail (str "the network stub for `" (pr-str route)
                              "` replies via a function — the recipient "
                              "loses the dynamic reply but keeps the route")}))
            (or net {}))
      (when (contains-fn? (:setup plan))
        [{:status :partial
          :code   :setup-fn
          :detail (str "a setup step carries a function — the recipient "
                       "reproduces the rest of the setup without it")}])
      (when (contains-fn? (:script plan))
        [{:status :partial
          :code   :script-fn
          :detail (str "a play-script step carries a function — the "
                       "recipient reproduces the rest of the script without "
                       "it")}]))))

(defn- dropped-override-reason
  "Reason for share-URL overrides that no longer apply to the current
  variant body (variant args refactored / renamed / removed). `:partial` —
  the rest of the URL still lands the recipient on the variant, but the
  named overrides are gone. `dropped` is the vector the share-import-hint
  carries (`re-frame.story.share/parse-overrides-param*` → `:dropped`)."
  [dropped]
  (when (seq dropped)
    [{:status :partial
      :code   :dropped-overrides
      :detail (str (count dropped) " override"
                   (when (not= 1 (count dropped)) "s")
                   " from this URL no longer apply to the variant and are "
                   "dropped for the recipient")}]))

;; ---- the classifier -------------------------------------------------------

(defn classify
  "Classify the reproducibility of a human-egress artifact. Pure data →
  data; JVM-testable.

  `inputs`:

    :plan           — the variant's compiled plan (`plan/variant-plan`), or
                      nil. Supplies `:world` (`:sub-overrides`, `:network`),
                      `:setup`, `:script`. A nil plan contributes no plan-
                      derived reasons (a variant whose plan does not compile
                      still shares — its overrides are classified on their
                      own).
    :cell-overrides — the runtime cell-overrides map the artifact encodes.
    :dropped        — share-URL override tokens that no longer apply
                      (`share/parse-overrides-param*` → `:dropped`); optional.

  Returns:

      {:status   :full | :partial | :view-only
       :label    \"fully reproducible\" | ...        ; status-labels lookup
       :reasons  [{:status :code :detail} ...]}      ; what downgraded it

  An artifact with no downgrade reasons is `:full`. The status is the
  LOWEST any reason implies — one view-only reason makes the whole artifact
  view-only — so the label never overstates what the recipient can do."
  [{:keys [plan cell-overrides dropped]}]
  (let [reasons (vec (concat
                       (non-edn-overrides cell-overrides)
                       (world-reasons plan)
                       (dropped-override-reason dropped)))
        status  (reduce (fn [acc {:keys [status]}] (worse acc status))
                        :full
                        reasons)]
    {:status  status
     :label   (status-labels status)
     :reasons reasons}))

(defn full?
  "Convenience predicate: is the classified artifact fully reproducible?
  Pure. `report` is a `classify` return."
  [report]
  (= :full (:status report)))
