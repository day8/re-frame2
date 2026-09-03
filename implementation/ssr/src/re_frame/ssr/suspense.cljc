(ns re-frame.ssr.suspense
  "The cross-host suspense boundary — a `.cljc` COMPONENT, not a hiccup
  keyword head.

  ## Why this namespace is not called `re-frame.ssr.boundary`

  Because the façade exports the component as `re-frame.ssr/boundary`,
  and in ClojureScript a var and a namespace share ONE JavaScript object
  graph. A `boundary` var in `re-frame.ssr` compiles to
  `re_frame.ssr.boundary = …`, which is byte-for-byte the object
  `goog.provide` created for a namespace named `re-frame.ssr.boundary` —
  so the def SILENTLY OVERWRITES the namespace, and every other var in it
  (`failed-boundaries`, `failed-boundaries-path`) becomes `undefined` for
  anyone who required both. That is not a hypothetical: this namespace
  WAS called `…ssr.boundary` and the compiled output read

      re_frame.ssr.boundary = re_frame.ssr.boundary.boundary;

  Renaming the namespace keeps the good authoring name (`ssr/boundary`)
  and removes the collision structurally. Do not rename it back.

  A streaming region is one authoring form that works on every host:

      (require '[re-frame.ssr :as ssr])

      [ssr/boundary {:id :card.revenue :fallback [card-skeleton :revenue]}
       [card-view :revenue]]

  On the **server** the component expands to the internal
  `:rf/suspense-boundary` wire marker, which the streaming shell walker
  (`re-frame.ssr.streaming`) recognises: it materialises the `:fallback`
  inline as a `<template>` placeholder and registers a continuation for
  the body. On the **client** the component renders the body directly —
  or the declared `:fallback` when this boundary is one the server
  reported as FAILED.

  ## Why a component and not a keyword head

  `:rf/suspense-boundary` cannot be an authoring surface on the client.
  A keyword head is an HTML element on every host (Conventions
  §Render-tree shape vs runtime lookup; rf2-j81hs made that one rule
  corpus-wide), so a marker left in a client render tree paints a phantom
  `<suspense-boundary>` element. And the marker could not be *given*
  client semantics either: stock Reagent is an external dependency whose
  element dispatch is not ours to extend, and UIx views are
  `defui` / `$` forms where a hiccup keyword head cannot occur at all.

  A callable component head is the ONE form expressible on every
  substrate, so that is the authoring surface. The keyword marker stays,
  demoted to internal wire syntax between this component and the walker —
  the walker protocol is unchanged, and the non-streaming emitter's
  `:rf.error/ssr-suspense-boundary-outside-stream` throw still guards a
  marker that escapes a stream.

  This also removes the reader-conditional `card-slot` pattern that the
  streaming example was forced into (a `#?(:clj … :cljs …)` slot, plus
  hand-maintained fallback duplication in every view's nil branch). One
  form, two hosts — and because both hosts now hash the SAME raw tree,
  the render-tree hash agrees by construction. A reader conditional made
  the two hosts hash structurally different trees; a component head
  canonicalises to the existing `#fn[]` token on both (Spec 011
  §Render-tree hash, rf2-jsa2ml), so no hash change is needed.

  ## Not React Suspense

  No promises, no thrown thenables, no selective hydration. This is a
  server-driven streaming protocol: the server decides what defers, the
  client paints the fallback and swaps content as chunks land. It shares
  the *shape* of React 18 / Solid suspense (a visible fallback plus a
  stable mount) and nothing of the mechanism.

  ## Failed boundaries

  A boundary whose server-side render threw ships its FALLBACK html in
  the chunk (Spec 011 §Failure semantics — inline fallback) and no
  hydration delta. The final payload carries that boundary's id in the
  failed set, which rides the serialisable runtime-db slice to
  `[:rf.runtime/ssr :streaming :failed-boundaries]`. This component reads
  that set, so a failed boundary renders its DECLARED `:fallback` — the
  exact markup the failed chunk left in the DOM. Views no longer need a
  defensive nil branch to keep the client render agreeing with the
  painted DOM; the boundary that declared the fallback is the one that
  re-renders it.

  Absence of a recorded outcome — a plain client mount, a non-streamed
  page, no frame scope at all — means the body renders. That is the
  ordinary case and it fails soft by construction."
  (:require [re-frame.error :as error]
            [re-frame.frame :as frame]))

;; ---- reserved runtime-db slot ---------------------------------------------

(def failed-boundaries-path
  "Runtime-db path holding the set of boundary ids whose server-side
  render FAILED, as reported by the final payload's serialisable
  runtime-db slice (`re-frame.ssr.streaming/build-final-payload`).

  Under the already-reserved `:rf.runtime/ssr` key (Conventions §Reserved
  runtime-db keys), a sibling of the `:hydration` metadata the
  `:rf/hydrate` handler writes. Runtime-managed: user code MUST NOT
  write it.

  This is the DURABLE record — serialisable, inspectable through ordinary
  frame state, and the server's own answer. It is not what the component
  reads at render time; see `failed-boundaries` below for why."
  [:rf.runtime/ssr :streaming :failed-boundaries])

(defn frame-failed-boundaries
  "The durable failed-boundary set recorded in `frame-id`'s runtime-db by
  hydration, or `#{}`. Never throws for an unknown / destroyed frame.

  For consumers that HAVE a frame — host code, tools, tests, server-side
  assertions. The component cannot use this; see below."
  [frame-id]
  (or (get-in (frame/frame-runtime-db-value frame-id) failed-boundaries-path)
      #{}))

;; ---- the render-time record ------------------------------------------------
;;
;; Why the component does NOT read the frame's runtime-db:
;;
;; `boundary` is a plain function component, and on Reagent a plain fn
;; lacking `:contextType` CANNOT read the enclosing `frame-provider`'s
;; frame from React context (Spec 006 §Frame-provider via React context —
;; only `reg-view`-wrapped components carry the stamp; a plain fn's
;; ambient `subscribe` raises `:rf.error/no-frame-context` for exactly
;; this reason). So there is no ambient frame to resolve from inside the
;; component on the dominant substrate, and a frame-scoped read is
;; structurally unavailable to it.
;;
;; Making `boundary` a `reg-view` would buy the frame back, but it would
;; make the framework's streaming primitive a registrar citizen — with a
;; registrar-cleared failure mode, and with `reg-view`'s dev-time
;; `data-rf-view` / `data-rf2-source-coord` root-element stamping landing
;; on the author's body markup. That is a large amount of machinery to
;; buy one boolean.
;;
;; So the render-time record is a process-level set, written once at
;; stream finalization by `re-frame.ssr.streaming.client/install!` from
;; the outcomes it observed on the wire. A page's boundary ids are unique
;; by contract (duplicates are already `:rf.error/suspense-boundary-
;; duplicate-id`), so an id identifies a boundary without a frame.

(defonce ^:private failed-registry
  ;; Process-global. Neither `clear-all!` nor a frames reset touches it —
  ;; a test that records a failure MUST call `reset-failed-boundaries!`
  ;; or it leaks into siblings.
  (atom #{}))

(defn failed-boundaries
  "The set of boundary ids recorded as FAILED for this page. `#{}` when
  nothing failed, or when the page was never streamed — the ordinary
  case, and what makes a plain client mount render bodies."
  []
  @failed-registry)

(defn record-failed-boundaries!
  "Record `ids` as failed. Called once per page by the streaming client's
  finalization step; additive, so a second streamed root on the same page
  contributes its own outcomes rather than replacing them."
  [ids]
  (swap! failed-registry into ids)
  nil)

(defn reset-failed-boundaries!
  "Clear the record. Test surface — see the `defonce` note above."
  []
  (reset! failed-registry #{})
  nil)

;; ---- attrs validation ------------------------------------------------------

(defn- valid-attrs? [m]
  (and (map? m) (contains? m :id) (contains? m :fallback)))

(defn- reject-attrs! [attrs]
  ;; Same error id the shell walker raises for a malformed marker
  ;; (`re-frame.ssr.streaming/walk-suspense-boundary`), so the authoring
  ;; mistake reads identically whichever host catches it first. A
  ;; client-only app would otherwise discover a missing `:fallback` only
  ;; when its server build ran.
  (error/throw-error!
    :rf.error/suspense-boundary-invalid-attrs
    'rf.ssr/boundary
    (str "boundary requires an attrs map with both :id and :fallback; "
         "give it {:id … :fallback …} as its first argument.")
    {:recovery :supply-id-and-fallback-attrs
     :extra    {:got attrs}}))

;; ---- the component ---------------------------------------------------------

#?(:cljs
   ;; Client-only: the `:clj` arm of `boundary` expands to the wire marker
   ;; and never assembles the body itself.
   (defn- subtree
     "The body as ONE hiccup form. Mirrors the walker's continuation-subtree
     construction (`re-frame.ssr.streaming/walk-suspense-boundary`): a lone
     child renders as itself, several are wrapped in a `:<>` fragment. The
     fragment emits no DOM, so the client's rendered structure matches the
     server's resolved-subtree html exactly."
     [body]
     (case (count body)
       0 nil
       1 (first body)
       (into [:<>] body))))

(defn boundary
  "Declare a streaming suspense boundary around `body`.

  `attrs` is a map with two REQUIRED keys:

    :id       — the boundary's identity. Unique per page; it is how an
                arriving chunk is paired with its placeholder. A keyword
                or a string.
    :fallback — hiccup rendered inline in the shell while the body is
                still resolving, and re-rendered by this component when
                the boundary is reported failed.

  Server (`:clj`): expands to the internal `:rf/suspense-boundary` marker
  the streaming shell walker consumes. Outside a stream the standard
  emitter rejects that marker with
  `:rf.error/ssr-suspense-boundary-outside-stream` — unchanged.

  Client (`:cljs`): renders `body`, or `:fallback` when `:id` is in the
  page's failed-boundary record (`failed-boundaries`, written at stream
  finalization). No recorded outcome — a plain client mount, a
  non-streamed page — renders `body`."
  [attrs & body]
  (when-not (valid-attrs? attrs)
    (reject-attrs! attrs))
  (let [{:keys [id fallback]} attrs]
    #?(:clj
       ;; Internal wire syntax. Rebuilt (rather than passed through) so
       ;; only the two contract keys reach the walker.
       (into [:rf/suspense-boundary {:id id :fallback fallback}] body)

       :cljs
       ;; Frame-free by necessity (see the render-time record above). An
       ;; unrecorded id is the ordinary case and renders the body.
       (if (contains? (failed-boundaries) id)
         fallback
         (subtree body)))))
