(ns re-frame.freehand.refs
  "REF COMPOSITION — how more than one Freehand mechanism shares ONE node
  (Spec 004 §Sharing one node).

  A React ref is the only position that runs at COMMIT with the real node
  in hand and never runs for a render the host abandons, so every
  substrate mechanism that needs a live node reaches for one. Two do
  already: the DOM top layer installs its idempotent host call as a ref on
  the element's props ([[re-frame.freehand.top-layer/install!]]), and a
  registered behavior gives the element it decorates a ref through
  `cloneElement` ([[re-frame.freehand.behaviors/attach]]).

  React holds ONE ref per element. A mechanism that writes a bare ref onto
  an element another mechanism has already claimed therefore does not
  fail — it CLOBBERS, silently, and the symptom is a popover that never
  opens or a behavior that never connects, on the one arrangement an
  author most reasonably expects to work.

  So Freehand composes rather than refusing. Both refs are observers of
  the same fact — \"the live node for this element\" — which is precisely
  what ref composition exists for; refusing the combination would forbid a
  legitimate authoring shape (a popover you cannot attach a behavior to
  reads as a substrate bug, not as a rule) and would have to be
  re-litigated the first time a third mechanism wanted a node. Refusing
  scales as O(pairs); chaining scales as O(1).

  ## The order, which is the part that has to be defined

  **Participants receive the node in the order they were added, and they
  are added from the INSIDE OUT** — the element's own intrinsics as its
  props are built, then each decorating boundary in the order it encloses
  the node. Release runs in the exact reverse.

  That order is not a preference, and it is not left to map iteration or
  to whichever mechanism happens to run first:

  - **It is the declaration's own containment order.** An intrinsic is a
    property OF the element; a behavior is a decoration applied OVER it.
    Reading outward from the node is the only reading in which a
    decoration cannot observe the node before the element's own property
    has.
  - **It falls out of how the element is built**, so it cannot drift. A
    mechanism adds itself with [[chain]] against whatever the element
    already carries, and the resulting order IS the containment order —
    obtained structurally, never declared in a table someone must keep in
    step.
  - **It leaves the top layer's document-order flush alone.** The
    intrinsic performs no host work at ref time: it ENQUEUES, and the
    commit's microtask checkpoint drains the batch in DOCUMENT order,
    because React attaches refs bottom-up and a naive per-ref
    implementation opens an inner popover and then closes it by opening
    the outer one. A chain that tried to order host operations would
    re-introduce exactly that bug. So this order governs registration on
    ONE element, where containment is unambiguous, and never becomes a
    second ordering mechanism competing with the batch.
  - **Reverse release is a stack unwinding.** The outermost decoration —
    the last to learn about the node — lets go first, so no participant
    is left holding a node a participant chained before it has already
    released.

  ## Identity, which composition must not change

  A composed ref is fresh whenever either participant is fresh, and a
  chain of ONE is that one participant, identically. That is deliberate:
  React detaches and re-attaches when a ref's identity moves, and each
  mechanism's attach FREQUENCY is part of its own contract — the top
  layer's ref is fresh per render on purpose (that re-entry into the
  commit batch is what makes the diff against the live node cheap), while
  a behavior's is created once so React does not tear its node's
  lifecycle down every commit. Memoising the composition would break the
  first; allocating for a lone participant would break the second.

  The detach and re-attach a fresh chain performs is confined to the
  commit: React attaches a host element's ref before any effect of the
  component above it runs, so a behavior's arm still reads the SAME node
  and reconciles rather than re-connecting.

  Normative owner:
  [`spec/004-Views.md`](../../../../../spec/004-Views.md) §Sharing one
  node."
  (:require [goog.object :as gobj]))

(defn element-ref
  "The ref `element` already carries, or nil. React 19 carries `ref` as an
  ordinary prop, so the element's props are where a ref lives and where a
  mechanism adding itself must look."
  [^js element]
  (gobj/get (.-props element) "ref"))

(defn- release!
  "Release one participant, under whichever protocol it answered with.

  A ref that returned a cleanup function is released by CALLING it —
  React 19's own rule — and one that returned anything else is released
  the way React would have released it, with `nil`. Honouring both is
  what makes release total for a participant this namespace has never
  heard of."
  [ref cleanup]
  (if (fn? cleanup) (cleanup) (ref nil))
  nil)

(defn chain
  "One ref that hands the node to `earlier` and then to `later`, and
  releases `later` before `earlier`.

  A chain of one participant IS that participant — nil in, nothing
  allocated, identity preserved — so the ordinary single-mechanism
  element pays nothing at all for the composition being available.

  The composed ref always answers a cleanup function, so React 19 uses it
  and the chain performs its own releases in reverse. Chains nest: adding
  a third participant is `(chain (chain a b) c)`, which still attaches a,
  b, c and still releases c, b, a."
  [earlier later]
  (cond
    (nil? earlier) later
    (nil? later)   earlier
    :else
    (fn chained-ref [node]
      (if (some? node)
        (let [earlier-cleanup (earlier node)
              later-cleanup   (later node)]
          (fn release-chained-ref []
            (release! later later-cleanup)
            (release! earlier earlier-cleanup)
            js/undefined))
        ;; A caller using the older callback-ref protocol, which signals a
        ;; detach with nil rather than by calling a cleanup. It is still a
        ;; release, so it still runs in release order.
        (do (later nil)
            (earlier nil)
            js/undefined)))))
