(ns re-frame.bench.hicasso.ssr.host-policy
  "THE SERVER SIDE OF `defhost`'s `:ssr` POLICY — the READ, never the write
  (rf2-2rtt6.86 clause 6).

  HD-011 declared `defhost`'s SSR placeholder; rf2-2rtt6.85 activates it —
  it owns the OPTION (`:client-only` / `{:fallback …}`), the mint-time
  refusal of anything else, and the client-side adoption behaviour. This
  namespace owns one half only: **what the server does with the policy it
  finds**, which is the half rf2-2rtt6.85's own clause 3 hands to the
  Node-entry bead.

  So nothing here declares a policy, validates one, or writes one onto a
  head. [[policy-of]] READS the slot and [[apply-policy]] applies the two
  ruled meanings:

      :client-only        the region renders NOTHING server-side
      {:fallback hiccup}  the region renders `hiccup` server-side

  ## The default is the ruled default, and it is real today

  `mint-host!` carries no policy slot yet, so [[policy-of]] finds nothing
  on every head in the tree and answers `:client-only` — which is exactly
  the option rf2-2rtt6.85 makes the DEFAULT. The conservative reading is
  also the correct one: a host is foreign code with its own hooks, state
  and refs, and a server that renders it by default would ship markup the
  client cannot be trusted to adopt. Fail-closed here means render less,
  never render something the client will not agree with.

  ## The seam, named so it is one line to reconcile

  [[policy-slot]] is the own-property the minted head carries the policy
  on. It is a guess at rf2-2rtt6.85's spelling made ahead of that bead
  landing, deliberately isolated to ONE constant and ONE reader so
  reconciling the two halves is a one-line change rather than a hunt.
  rf2-2rtt6.92 is the reconciliation.

  ## What this walk reaches, stated rather than implied

  It walks the hiccup form the render entry is HANDED — the root tree and
  everything reachable from it through vectors and seqs. A host minted
  inside a boundary BODY is not in that tree: the body runs inside
  `renderToString`, and its host element is created by the codec's own
  crossing, which is `front/codec.cljs` — rf2-2rtt6.85's file, not this
  bead's. That case is covered by the SAME policy from the other side:
  rf2-2rtt6.85's host boundary reads its adoption state through
  `useSyncExternalStore(…, …, (constantly false))`, and under
  `renderToString` React takes the server snapshot, so the region renders
  its placeholder without this walk touching it. The two halves meet when
  that bead lands; neither is a substitute for the other, and saying so
  here is cheaper than a reader discovering it. **That is an argument,
  not a witness** — rf2-2rtt6.92 is the fixture that turns it into one,
  and the ruling on whether one policy should have two mechanisms."
  (:require [re-frame.bench.hicasso.front.codec :as codec]))

(def ^:const policy-slot
  "The own-property a minted host head carries its `:ssr` policy on.

  ONE constant, read in ONE place ([[policy-of]]), because the WRITE side
  is rf2-2rtt6.85's and this bead consumes it ahead of it landing."
  "ssr")

(def default-policy
  "The policy a head with no declared `:ssr` gets — the ruled default."
  :client-only)

(defn policy-of
  "The `:ssr` policy declared on a minted host `head`, or [[default-policy]]
  when it declares none. One own-property read; no registry, no map —
  the same shape as `codec/host-head?`."
  [head]
  (let [declared (unchecked-get head policy-slot)]
    (if (some? declared) declared default-policy)))

(defn client-only?
  "Does `policy` mean *render nothing server-side*?"
  [policy]
  (= :client-only policy))

(defn fallback-policy?
  "Does `policy` mean *render this hiccup server-side*?"
  [policy]
  (and (map? policy) (contains? policy :fallback)))

(defn- refuse!
  "A head carrying a policy that is neither ruled meaning.

  Unreachable once rf2-2rtt6.85 lands — that bead refuses an unknown
  `:ssr` value at MINT time, where the author's stack is the declaration
  site. It is kept as a fail-CLOSED floor rather than a fall-through to
  rendering the component, because the failure mode of guessing here is
  shipping a client-only region's markup to every reader of the page."
  [host-name policy]
  (throw (ex-info (str "defhost " (pr-str host-name) " carries an :ssr policy this "
                       "server walk does not know: " (pr-str policy) ". The ruled "
                       "meanings are :client-only and {:fallback <hiccup>}. "
                       "[:rf.error/hicasso-unknown-ssr-policy]")
                  {:rf.error/id :rf.error/hicasso-unknown-ssr-policy
                   :where       'ssr.host-policy/apply-policy
                   :reason      "An :ssr policy is :client-only or {:fallback <hiccup>}."
                   :recovery    :declare-client-only-or-a-fallback
                   :host        host-name
                   :policy      policy})))

(declare apply-policy)

(defn- walk-vector
  "Every slot of `v` through [[apply-policy]], rebuilding **only if
  something moved**. A tree with no hosts in it comes back by identity,
  so the walk costs a traversal and no allocation on the overwhelmingly
  common page.

  Slot 0 is the head and goes through the same call: a tag keyword, `:<>`
  and a boundary head are all values [[apply-policy]] returns unchanged,
  so the head needs no special case to be safe from one."
  [v]
  (let [n (count v)]
    (loop [i 0
           acc nil]
      (if (< i n)
        (let [x (nth v i)
              y (apply-policy x)]
          (recur (inc i)
                 (if (identical? x y) acc (assoc (or acc v) i y))))
        (or acc v)))))

(defn apply-policy
  "The server walk. Returns `form` with every `defhost` region replaced by
  what its `:ssr` policy says the server renders — nothing, or the
  fallback hiccup (itself walked, so a fallback containing a host is not a
  hole).

  Pure, and identity-preserving where nothing changes."
  [form]
  (cond
    (vector? form)
    (let [head (nth form 0 nil)]
      (if (codec/host-head? head)
        (let [policy (policy-of head)]
          (cond
            (client-only? policy)    nil
            (fallback-policy? policy) (apply-policy (:fallback policy))
            :else                    (refuse! (unchecked-get head "displayName") policy)))
        (walk-vector form)))

    ;; A `for`'s rows are the case the walk exists for: laziness is why a
    ;; collector closed at body-return loses them (the Arm 2 finding), and
    ;; it is equally why a walk that returned the seq untouched would miss
    ;; every host inside one. Realized here, once, on the server.
    (seq? form)
    (doall (map apply-policy form))

    :else form))
