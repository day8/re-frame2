(ns re-frame.hicasso.evidence
  "The envelope every `re-frame.hicasso.tool` read answers in, and the
  vocabulary it is written in.

  An envelope names its schema, its producer, the read that answered,
  whether it is complete, and what it dropped. Two rules hold every read
  to one standard of honesty: a loss is never stated beside a
  completeness claim, and an unknown is never spelled as an empty
  collection — a roster the producer did not survey states `unknown`
  where the vector would be, so a reader can tell *found nothing* from
  *looked at nothing*.

  Why: Xray and the AI pair consume one door with no consumer
  discriminator, so the shape they parse is pinned by a version and the
  trust a roster deserves rides on the roster. The contract is
  docs/design/hicasso/product/lanes/testing-xray.md §Evidence contract."
  (:require [clojure.string :as str]))

(def schema
  "The version every envelope carries. A consumer checks it first and
  refuses any other — there is no compatibility path, and a shape that
  evolved under an unchanged stamp is the one defect a version exists to
  prevent. v3 folds the former scope/basis axes into `:complete?` and
  `:loss`, drops the `:naming`, `:host` and `:origin` sub-projections,
  and names the declared views that rendered a boundary as `:views`."
  :re-frame.hicasso.evidence/v3)

(def producer
  "Which substrate produced the envelope. The schema is adapter-neutral,
  so the producer is stamped rather than inferred from the read's name."
  :re-frame/hicasso)

(def unknown
  "The value a field states where the fact is not held, and the
  `:dropped` count of a loss that cannot be sized. A named value rather
  than nil or an absent key, because both of those read as none."
  :unknown)

(def reads
  "The four read operations, stamped on every envelope as `:read`."
  #{:mounted-boundaries :read-attribution :intents :explain-render})

(def loss-reasons
  "Why an envelope could not carry something — each a different remedy
  for the reader. A cap is a knob; an opaque fact is one the substrate
  keeps no record of; a host-opaque one is React's to tell; an
  uncorrelated one is a join that was never available."
  #{:cap :opaque :host-opaque :uncorrelated})

(defn- problems
  "The sentences that refuse `read`, `complete?` and `loss` — empty when
  the three cohere."
  [read complete? loss]
  (cond-> []
    (not (contains? reads read))
    (conj (str ":read " (pr-str read) " is not one of " (pr-str (vec (sort reads)))))

    (not (boolean? complete?))
    (conj ":complete? must be true or false")

    (and (some? loss)
         (not (and (map? loss)
                   (contains? loss-reasons (:reason loss))
                   (let [d (get loss :dropped ::absent)]
                     (or (nat-int? d) (= unknown d))))))
    (conj (str ":loss " (pr-str loss) " must be nil or {:reason <one of "
               (pr-str (vec (sort loss-reasons))) "> :dropped <count or " unknown
               ">} — an absent :dropped reads as none"))

    (and (true? complete?) (some? loss))
    (conj "it claims completeness and also reports loss; a truncated roster reported as total is the one shape this door refuses")))

(defn envelope
  "Answer `body` stamped as the envelope for `read`, or throw.

  `read` is one of `reads`; `complete?` is a boolean; `loss` is nil or
  `{:reason <one of `loss-reasons`> :dropped <count or `unknown`>}`.
  The stamp is `:schema`, `:producer`, `:read`, `:complete?` and `:loss`,
  merged over `body`. Refused, with an `ex-info` whose data carries
  `:re-frame.hicasso.evidence/defect` and the `:problems`: a read outside
  the vocabulary, a loss with a foreign reason or no sizeable `:dropped`,
  and `:complete? true` beside a loss.

  Why one door with no lenient variant: an envelope that may be emitted
  without its completeness claim eventually is, and a reader cannot tell
  which one they hold."
  [read complete? loss body]
  (let [ps (problems read complete? loss)]
    (when (seq ps)
      (throw (ex-info (str "This evidence envelope cannot be emitted: " (str/join "; " ps)
                           ". Every envelope states its read, its completeness and its loss; "
                           "unknown is never encoded as an empty collection.")
                      {:re-frame.hicasso.evidence/defect :incoherent-envelope
                       :problems ps})))
    (assoc body
           :schema    schema
           :producer  producer
           :read      read
           :complete? complete?
           :loss      loss)))
