(ns re-frame.story.extends
  "`:extends` resolution for `reg-variant`.

  Per /spec/007-Stories.md §Composed variants and `002-Runtime.md` §Args resolution precedence, a variant body
  may carry `:extends <variant-id>` — the parent's body is merged into
  the child's (child wins key-by-key), producing a fully data-shaped
  variant artefact.

  Merge semantics:

  - **Top-level keys**: child wins. `(merge parent child)` semantics —
    a key present on the child replaces the same key on the parent.
  - **No vector concat / no map-deep-merge** at this layer. Stage 2's
    contract is straight `merge`; Stage 3's args-resolution layer is
    the deep-merge surface (per /spec/007-Stories.md §Args at three levels — there
    we deep-merge args). This separation keeps the variant-body merge
    semantically simple.
  - **`:extends` itself is dropped** from the resolved body — it's a
    registration-time directive, not a runtime artefact.

  Cycle detection:

  - Walks the parent chain bounded by `*max-extends-depth*` (default 32).
  - Throws `:rf.error/story-extends-cycle` if a cycle is detected (we
    revisit an id already in the chain), or
    `:rf.error/story-extends-chain-too-long` if the chain exceeds the
    depth cap.
  - Throws `:rf.error/story-extends-unknown` if a parent id is not
    registered when `:extends` is resolved.

  Stage 2 resolves at registration time (per `001-Authoring.md` §Registration macros). Production
  builds elide the entire registration surface so `:extends` resolution
  doesn't survive into a production bundle anyway."
  (:refer-clojure :exclude [resolve]))

(def ^:dynamic *max-extends-depth*
  "Hard cap on `:extends` chain length. A registration that hits this
  limit is treated as a cycle. 32 is more than any sane chain — projects
  hitting this limit either have a cycle or a structural problem.

  Authors who need a longer chain can rebind, but this is a smell."
  32)

(defn- chain-of
  "Walk the `:extends` chain from `body` outward. Returns a vector of
  parent bodies, root-first. The first entry is the deepest parent;
  the last entry is the immediate parent of the child.

  `lookup` is a fn `(parent-id) → body-or-nil`.

  Throws `:rf.error/story-extends-cycle` if a cycle is detected (an id
  appears twice on the chain) or `:rf.error/story-extends-chain-too-long`
  if the chain exceeds `*max-extends-depth*`. Throws
  `:rf.error/story-extends-unknown` if a named parent is not yet
  registered."
  [body lookup]
  (loop [acc      []
         current  body
         visited  #{}
         depth    0]
    (let [parent-id (:extends current)]
      (cond
        (nil? parent-id)
        (vec (reverse acc))

        (contains? visited parent-id)
        (throw (ex-info ":rf.error/story-extends-cycle"
                        {:rf.error/id :rf.error/story-extends-cycle
                         :where    'rf.story/reg-variant
                         :recovery :fix-registration
                         :reason   (str "re-frame2-story: :extends cycle through " parent-id)
                         :chain    (conj (vec visited) parent-id)
                         :id       parent-id}))

        (>= depth *max-extends-depth*)
        (throw (ex-info ":rf.error/story-extends-chain-too-long"
                        {:rf.error/id :rf.error/story-extends-chain-too-long
                         :where    'rf.story/reg-variant
                         :recovery :fix-registration
                         :reason   (str "re-frame2-story: :extends chain exceeds "
                                        *max-extends-depth* " levels at " parent-id)
                         :chain    (conj (vec visited) parent-id)
                         :id       parent-id}))

        :else
        (if-let [parent (lookup parent-id)]
          (recur (conj acc parent)
                 parent
                 (conj visited parent-id)
                 (inc depth))
          (throw (ex-info ":rf.error/story-extends-unknown"
                          {:rf.error/id :rf.error/story-extends-unknown
                           :where    'rf.story/reg-variant
                           :recovery :fix-registration
                           :reason   (str "re-frame2-story: :extends references "
                                          "unregistered variant " parent-id)
                           :parent   parent-id})))))))

(defn- drop-shadowed-siblings
  "For each mutual-exclusion group in `exclusive-groups` (a coll of
  key-sets), if `child` declares ANY key in the group then strip EVERY
  key in that group from `inherited` before the merge. This lets a child
  override a parent's alternative encoding wholesale — e.g. a parent's
  `:play-script` and a child's `:plays` are sibling encodings of the
  same play surface; without this the straight merge would carry BOTH
  keys and the schema's mutual-exclusion `:fn` would reject a body the
  author never wrote with both keys."
  [inherited child exclusive-groups]
  (reduce
    (fn [acc group]
      (let [group-set (set group)]
        (if (some #(contains? child %) group-set)
          (apply dissoc acc group-set)
          acc)))
    inherited
    exclusive-groups))

(defn resolve-extends
  "Resolve `:extends` on `body` against `lookup` (a fn from parent-id to
  parent-body). Returns the merged body with `:extends` stripped.

  `lookup` is parameterised so the caller (the registrar) decides where
  to read parent bodies from. In Stage 2 this is the side-table; the
  same shape works for any future remote registry.

  Resolution is single-pass: when a parent itself has `:extends`, we
  recurse — the final body is the result of merging every ancestor in
  root-first order.

  `exclusive-groups` (optional) is a coll of key-sets that are mutually-
  exclusive ENCODINGS of one slot — when the child declares any key in a
  group, the whole group is stripped from every inherited layer before
  the merge so the child's encoding wins wholesale (the Story play surface
  `#{:play-script :plays}` is one such group). Layers closer to the child
  still shadow farther ones key-by-key as usual.

  Per /spec/007-Stories.md §Composed variants: 'Resolution at registration time. Cycles raise
  `:rf.error/extends-cycle`.' (The implemented error id is namespaced
  `:rf.error/story-extends-cycle` — see `chain-of`.)"
  ([body lookup] (resolve-extends body lookup nil))
  ([body lookup exclusive-groups]
   (if-let [_parent-id (:extends body)]
     (let [chain  (chain-of body lookup)
           ;; Reduce parent-first: every parent's keys are overridden by
           ;; the next (more specific) layer, ending with the child. For
           ;; each layer, strip any inherited mutual-exclusion group the
           ;; more-specific layer overrides with its sibling encoding.
           merged (reduce
                    (fn [acc layer]
                      (merge (drop-shadowed-siblings acc layer exclusive-groups)
                             layer))
                    {}
                    (conj (vec chain) body))]
       (dissoc merged :extends))
     body)))
