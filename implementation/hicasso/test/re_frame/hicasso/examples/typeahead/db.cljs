(ns re-frame.hicasso.examples.typeahead.db
  "THE TYPEAHEAD'S MODEL — the shape, and the four pure questions the
  ceremony keeps asking (rf2-hic-044).

  This namespace holds no ceremony of its own. It holds the model, and the
  predicates the handlers in
  [[re-frame.hicasso.examples.typeahead.events]] have to consult in order
  to keep a resource's life correlated with a read's. Naming them here
  rather than inlining them is what makes the census in that file
  readable: a ceremony site is then the CALL and the decision around it,
  not a paragraph of destructuring.

  ## `wanted` is the whole hypothesis in one expression

  [[wanted]] answers the term a live read wants, or `nil` when no read
  wants anything. It is a function of `app-db` — which is exactly the
  problem the flagship experiment is about. Under demand-driven resource
  ownership the same answer would be a function of the COMMITTED READ SET:
  the suggestion panel's body reads `[::subs/suggestions term]` when it is
  on screen and does not when it is not, so the parameter of the live read
  IS this value, and nothing would have to recompute it from the model.

  Every OWNERSHIP row of the census exists because that identity is not
  available today. The application has to derive `wanted`, remember what
  it derived last time, and fire the difference by hand at every intent
  that could have moved it.

  ## Two resources, deliberately

  Suggestions are parameterised by a TERM the user is still typing;
  details are parameterised by an ID the user has chosen. They fail
  differently and they are released differently, and a witness with one
  resource could not tell a parameter-change release from an unmount
  release. See the report's C4 section."
  (:require [clojure.string :as str]))

(def min-term-length
  "How much a user has to type before a search is worth making. Two, so a
  witness can cross the threshold inside one word."
  2)

(def seed
  "The starting `app-db`. Everything the application holds, and nothing a
  test needs that the application would not have.

  `:generation` is the correlation token this whole file exists to
  service: a monotone ordinal minted on every keystroke, carried by the
  debounce tick and by the search reply, and compared on the way back in.
  It is the hand-written stand-in for a read's identity."
  {:search {:term       ""
            :revision   0
            :open?      false
            :generation 0
            :requested  nil
            :shown      nil
            :status     :idle
            :problem    nil}
   :chosen  nil
   ;; `id -> :pending | row`. **Keyed by the resource's parameter**, which
   ;; is exactly how the criteria say demand state would be keyed. The
   ;; application already manages to key its CACHE that way; what it
   ;; cannot key that way is the demand, because a request's life has to
   ;; be tied to a read's and only the model knows about reads.
   :details {}})

(defn searchable?
  "Is `term` long enough to be worth a request? Trimmed, because a field
  holding two spaces is a field holding nothing."
  [term]
  (>= (count (str/trim (or term ""))) min-term-length))

(defn wanted
  "**The term a live read wants, or `nil`.** The suggestion panel renders
  — and therefore reads — exactly when it is open over a searchable term,
  so this is the parameter of the live read, computed from the model
  because the read set is not available to application code.

  Read the report's C1 section beside this: it is the value every
  OWNERSHIP site is keeping something correlated with."
  [db]
  (let [{:keys [term open?]} (:search db)]
    (when (and open? (searchable? term))
      (str/trim term))))

(defn satisfied?
  "Do the rows already on screen answer `term`? The question an acquire
  site has to ask so that re-opening a panel over an unchanged term does
  not re-fetch what is already there."
  [db term]
  (= term (get-in db [:search :shown :term])))

(defn current-generation?
  "Is `generation` the newest one minted? The guard behind debounce
  supersession and stale-reply suppression alike."
  [db generation]
  (= generation (get-in db [:search :generation])))

(defn in-flight
  "What the application believes is out on the wire for the search, or
  `nil`. `{:generation … :term …}`."
  [db]
  (get-in db [:search :requested]))

(defn take-rows
  "**The unguarded fold** — fold a search reply into `db` with no
  correlation check at all.

  Split out and named because it is the half of `::events/suggestions`
  that a reply-handling application genuinely needs; the `if` around it in
  that handler is the whole of stale-reply suppression. The witness's
  defect demonstration registers this function as a handler of its own
  rather than editing the application, so the mutation under test is
  exactly one form — the guard — and neither copy can drift from the
  other."
  [db {:keys [term rows]}]
  (-> db
      (assoc-in [:search :shown] {:term term :rows rows})
      (assoc-in [:search :requested] nil)
      (assoc-in [:search :status] (if (seq rows) :ready :empty))
      (assoc-in [:search :problem] nil)))

(defn close-panel
  "Shut the suggestion panel. The MODEL half of every dismissal — the
  release half is the `:fx` beside the call, and that is the distinction
  the census turns on."
  [db]
  (assoc-in db [:search :open?] false))
