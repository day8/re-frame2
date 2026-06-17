(ns re-frame.ssr.streaming
  "Streaming SSR — `:rf/suspense-boundary` walker, per-request continuations
  registry, per-subtree hydration deltas. Per Spec 011 §Streaming SSR
  (rf2-ojakd / rf2-olb64 (a)).

  ## Chunk-ordering invariant (FIFO over registration)

  Continuations stream in **registration FIFO order** — the order the
  shell walk encountered each `:rf/suspense-boundary` (document order
  for siblings, parent-before-children for nesting). `render-shell`'s
  `:continuations` vector preserves that order; the host adapter MUST
  drain it sequentially without reordering. Two boundaries registering
  the same `:id` is a programmer error: `dedupe-continuations` keeps
  the LAST registration and emits `:rf.error/suspense-boundary-
  duplicate-id`. Conformance: `ssr_streaming_test` (`'FIFO registration
  in document order'`) + `ssr_streaming_conformance_test` (fixture-
  pinned FIFO).

  The hiccup author marks deferred subtrees with the
  `:rf/suspense-boundary` reserved hiccup head:

    [:rf/suspense-boundary
     {:id      :news/comments
      :fallback [:p \"Loading comments…\"]}
     [:comments/section]]

  Three load-bearing operations:

  - `render-shell` — walks the hiccup tree once, top-down. At each
    `:rf/suspense-boundary` node it (a) renders the fallback wrapped in
    a `<template data-rf2-suspense-id … data-rf2-suspense-fallback>`
    placeholder, (b) registers a continuation `{:id :subtree}` for the
    body. Returns `{:shell-html … :continuations [{:id … :subtree …} …]}`
    where `:continuations` is in registration FIFO order (see invariant
    above).

  - `render-continuation` — drains one continuation. Captures the
    before-db, calls `render-to-string` on the subtree, captures the
    after-db, computes the per-subtree app-db delta via
    `clojure.data/diff`. Returns `{:html … :delta {…} :failed? false}`
    or, on subtree-render throw, `{:html <fallback-html>
    :delta nil :failed? true}` with a `:rf.ssr/suspense-boundary-failed`
    trace emitted.

  - `build-final-payload` — after every continuation has drained,
    constructs the canonical `:rf/hydration-payload`. The client
    `:rf/hydrate`s against this with `:replace-frame-state` semantics — the
    final payload wins, deltas were speculative.

  All three are pure-ish helper fns; the host adapter (`ssr-ring/streaming`)
  owns the chunked-HTTP wiring and threading.

  Host adapters consume these surfaces via late-bind hooks
  (`:ssr.streaming/render-shell!`, `:ssr.streaming/render-continuation!`,
  `:ssr.streaming/build-final-payload`) registered at ns-load time below.

  Per the rf2-ojakd / rf2-olb64 cluster."
  (:require [clojure.data]
            [clojure.string]
            [re-frame.error :as error]
            [re-frame.frame :as frame]
            [re-frame.interop :as interop]
            [re-frame.late-bind :as late-bind]
            [re-frame.registrar :as registrar]
            [re-frame.ssr.emit :as emit]
            [re-frame.ssr.html-helpers :as html]
            [re-frame.ssr.payload-policy :as payload-policy]
            [re-frame.ssr.streaming.constants :as wire]
            [re-frame.trace :as trace]))

#?(:clj (set! *warn-on-reflection* true))

;; ---- chunk shapes (the wire-format contract) ------------------------------
;;
;; The Spec 011 §Chunk-ordering contract names FOUR chunk kinds:
;;
;;   1. Shell:           <!DOCTYPE html>…<div id=app>…<template>fallback</template>…</div>
;;   2. Resolved subtree: <template …suspense-resolved>html</template>
;;                        <script …suspense-hydrate type="application/edn">delta-edn</script>
;;   3. Final payload:    <script id="__rf_payload" …>full-payload-edn</script>
;;   4. Close:            </body></html>
;;
;; Chunk-emission strings are assembled here; the host adapter chooses
;; how to flush (Ring chunked OutputStream, edge-runtime ReadableStream,
;; etc).

(defn- suspense-template
  "The shared `<template data-rf2-suspense-id=\"…\" MARKERS>BODY</template>`
  scaffold. `markers` is the marker-attribute fragment that distinguishes
  the three chunk kinds (fallback / resolved / failed); the id-escaping
  and open/close-tag wrapping live here once. The three public builders
  below are thin one-liners over this."
  [id markers body-html]
  (str "<template " wire/attr-suspense-id "=\""
       (html/escape-attr (str id))
       "\" " markers ">"
       body-html
       "</template>"))

(defn fallback-template
  "The fallback chunk shape — the boundary's fallback markup wrapped in an
  inline `<template data-rf2-suspense-fallback>` placeholder in the shell
  HTML.

  rf2-xzhf2a — a `<template>`'s content is INERT by the HTML spec (its
  `.content` is a detached `DocumentFragment`, never painted), so this is
  NOT first-byte-visible content: nothing paints until the client runtime
  (`re-frame.ssr.streaming.client/install!`) materialises each inert
  fallback into a live, visible `<rf-suspense data-rf2-suspense-mount>`
  mount. This is the deliberate streaming model (Spec 011 §Client-side
  hydration semantics — the same visible-fallback-plus-stable-mount shape
  React 18 / Solid use): the inert `<template>` is the wire-carrier for the
  fallback markup, the client owns the painted mount. A streaming page
  therefore requires the client runtime to show fallbacks — non-JS clients
  see the shell structure without painted skeletons until the final payload."
  [id fallback-html]
  (suspense-template id (str wire/attr-suspense-fallback "=\"1\"") fallback-html))

(defn resolved-template
  "The resolved-subtree chunk shape — flushed when a continuation drains
  successfully. The client-side streaming runtime swaps the matching
  fallback placeholder for this resolved content in DOM."
  [id resolved-html]
  (suspense-template id (str wire/attr-suspense-resolved "=\"1\"") resolved-html))

(defn failed-template
  "The failed-continuation chunk shape — same wire shape as
  `resolved-template` plus a `data-rf2-suspense-failed` marker so the
  client-side runtime can surface the failure observably without
  surfacing a 500. Per Spec 011 §Failure semantics — inline fallback."
  [id fallback-html]
  (suspense-template id
                     (str wire/attr-suspense-resolved "=\"1\" "
                          wire/attr-suspense-failed "=\"1\"")
                     fallback-html))

(defn hydrate-delta-script
  "The per-subtree hydration delta chunk — application/edn. The client
  reads the EDN, then `(swap! app-db merge delta)`s. Per Spec 011
  §Hydration interleaving."
  [id delta-edn]
  (str "<script " wire/attr-suspense-hydrate "=\""
       (html/escape-attr (str id))
       "\" type=\"application/edn\">"
       ;; rf2-7ksyr / rf2-rdxxa — EDN-aware escape: `<` inside string
       ;; literals becomes `<` so a delta carrying `</script>` from
       ;; a user-controlled string cannot close the envelope, while
       ;; keyword/symbol tokens with `<` (`:<`, `:a<b`) round-trip
       ;; unchanged through the client's EDN reader. A `</` / `<!`
       ;; breakout in a token position fails loud. Same encoder as the
       ;; final-payload escape in payload-script-tag.
       (html/escape-edn-script-body delta-edn)
       "</script>"))

;; ---- per-subtree hydration delta -----------------------------------------
;;
;; Per Spec 011 §Hydration interleaving — per-subtree deltas. The delta
;; carries the top-level `app-db` keys present-or-changed in `after-db`,
;; and for each such key its FULL `after-db` value.
;;
;; Why the full value, not `(clojure.data/diff …)`'s partial second slot:
;; the client merges the delta via `(into existing delta)` over the
;; TOP-LEVEL keys (Spec 011 line 912). `data/diff`'s second slot returns
;; only the changed SUB-portion of a changed nested-map value
;; (`{:user {:a 1 :b 2}}` → `{:user {:a 1 :b 3}}` yields `{:user {:b 3}}`),
;; so `(into existing {:user {:b 3}})` would REPLACE `:user` with
;; `{:b 3}`, dropping `:a 1`. Shipping the full `after-db` value for each
;; changed top-level key makes the documented top-level `into` merge
;; lossless: a changed nested key is replaced wholesale with its complete
;; new value, a new top-level key is added, and unchanged keys are
;; omitted (the client already holds them). This satisfies both line 911
;; ("keys present-or-changed in after-db") and line 912 (top-level
;; `into`-merge) without the deep-merge corruption window.

(defn- subtree-delta
  "Compute the per-subtree hydration delta between `before-db` and
  `after-db`. Returns a map of the top-level keys whose value changed or
  is new in `after-db`, each mapped to its FULL `after-db` value. An
  unchanged db yields `{}`. See the contract comment above for why the
  full value (not `data/diff`'s partial slot) is shipped — it keeps the
  client's documented `(into existing delta)` top-level merge lossless."
  [before-db after-db]
  (let [[_only-in-before only-in-after _in-both]
        (clojure.data/diff before-db after-db)]
    ;; `only-in-after` enumerates the top-level keys that changed or are
    ;; new (its value for a changed nested map is the partial sub-diff —
    ;; we use only its KEY SET and re-project the full value from
    ;; `after-db`). Falsy keys (`false` / `nil`) survive `data/diff`'s
    ;; slot as map entries, so `keys` is the correct extractor.
    (reduce (fn [acc k] (assoc acc k (get after-db k)))
            {}
            (keys only-in-after))))

;; ---- streaming hydration-delta egress projection (rf2-uc3cs4) -------------
;;
;; A per-boundary hydration delta is BROWSER-DELIVERED hydration state — it
;; arrives in the stream BEFORE the final `__rf_payload` and the client merges
;; it into the live app-db. `subtree-delta` computes it as the raw changed/new
;; top-level keys each mapped to the FULL `after-db` value, so without
;; projection a continuation that mutates `:secret` streams `{:secret …}` even
;; when the handler's `:payload` allowlist names only public keys, and a changed
;; sensitive CHILD under an allowed top-level key rides raw. That violates
;; EP-0015 §14: production browser egress is ALLOWLIST-FIRST and then centrally
;; projected under `:rf.egress/ssr-hydration` — the SAME contract the final
;; `__rf_payload`'s `:rf/app-db` already obeys (rf2-bt9kct).
;;
;; `project-delta` applies that SAME two-step boundary to the raw delta:
;;
;;   1. ALLOWLIST — `payload-policy/apply-policy` over the delta map, so an
;;      off-allowlist changed key (`:secret`) is dropped (a vector `:payload`
;;      selects only the named keys; `:rf.ssr.payload/whole-app-db` keeps them
;;      all — the deliberate whole-db opt-in);
;;   2. PROJECT — `payload-policy/project-app-db-egress` under the request
;;      frame, so a frame-sensitive child inside an allowed changed key redacts
;;      (fail-closed against a missing frame policy, exactly like the final
;;      payload).
;;
;; Pure (the policy + projection are pure over the supplied delta + frame). The
;; Ring host adapter calls this on each non-empty delta BEFORE serialising the
;; `data-rf2-suspense-hydrate` EDN script (mirroring how it filters the final
;; payload through the same `payload-policy`).

(defn project-delta
  "Project a streaming per-subtree hydration `delta` (from `render-continuation`)
  for browser egress before it serialises into the `data-rf2-suspense-hydrate`
  EDN script (rf2-uc3cs4). Applies the handler's `:payload` allowlist
  (`payload-policy/apply-policy`) then the centralized `:rf.egress/ssr-hydration`
  projection under `frame-id` (`payload-policy/project-app-db-egress`) — the
  SAME allowlist-first-then-project boundary the final `__rf_payload` obeys, so
  an off-allowlist changed key is dropped and a frame-sensitive child inside an
  allowed changed key redacts. `policy-opts` carries the `:payload` policy
  (validated at handler-construction time, so a malformed policy never reaches
  here). A nil / empty delta returns `{}` (nothing to hydrate). Pure."
  [delta frame-id {:as policy-opts}]
  (if-not (seq delta)
    {}
    (let [allowed (payload-policy/apply-policy delta policy-opts)]
      ;; An allowlist that drops every changed key yields an empty map — there
      ;; is nothing to project (and projecting `{}` against a fail-closed
      ;; unresolvable frame would redact the empty map to the scalar
      ;; `:rf/redacted` sentinel, which the host's `(seq …)` emit guard cannot
      ;; walk). Short-circuit to `{}` so the host emits no delta script.
      (if (seq allowed)
        (payload-policy/project-app-db-egress allowed frame-id)
        {}))))

;; ---- continuation registry (per-request, transient) -----------------------
;;
;; The walker owns a per-call mutable seq accumulator. We do NOT use a
;; framework-private side-channel atom keyed on frame-id for this — the
;; registry lifetime is one shell-walk, not one request, and threading
;; a transient through the recursive walker is simpler than sweeping a
;; side-channel after a render-throw. The host adapter drains the
;; returned vector directly.

(defn- new-continuations-acc []
  ;; Atom of `[{:id :subtree} …]` so the recursive walker can record
  ;; from nested branches without threading the result through.
  (atom []))

(defn- record-continuation!
  "Record a continuation entry for one `:rf/suspense-boundary`. The
  declared `:fallback` hiccup is stored alongside the `:subtree` so the
  drain path (`render-continuation`) can re-materialise it when the
  subtree render throws (Spec 011 §Failure semantics — inline fallback).
  Without it, a FAILED continuation would render `nil` → empty string,
  silently dropping the author-declared loading state (rf2-405ld)."
  [acc id subtree fallback]
  (swap! acc conj {:id id :subtree subtree :fallback fallback}))

;; ---- duplicate-id detection ----------------------------------------------

(defn- wire-id
  "The canonical WIRE id string for a boundary `:id` — `(str id)`, the
  exact value stamped into `data-rf2-suspense-id` /
  `data-rf2-suspense-hydrate` (and matched by the client). Duplicate
  detection MUST key on this, not the raw `:id`, so ids that DIFFER as
  values but COLLIDE under `str` (`:a` keyword vs `\":a\"` string) are
  treated as the same boundary — the client can only match them by the
  one wire string (rf2-yvc0t7)."
  [id]
  (str id))

(defn- dedupe-continuations
  "Per Spec 011 §Boundary nesting and recursion — a duplicate boundary id
  is a programmer error. Emit `:rf.error/suspense-boundary-duplicate-id`
  and keep the LAST registration (matches the spec's wire shape: the
  second registration overwrites the first; the earlier fallback
  placeholder is left in place because the client never finds a matching
  resolved chunk). Fail-soft.

  Duplicates are detected on the canonical WIRE id (`wire-id`, i.e.
  `(str id)`) — the exact string stamped into the suspense attributes and
  matched by the client (rf2-yvc0t7). Grouping on the raw `:id` would let
  string-colliding ids (`:a` keyword vs `\":a\"` string) escape detection
  even though they share one `data-rf2-suspense-id` on the wire, leaving
  two chunks the client can only resolve against the same mount."
  [conts]
  (let [by-wire (group-by (comp wire-id :id) conts)
        dupes   (->> by-wire (filter (fn [[_ vs]] (> (count vs) 1))) (map key))]
    (when (seq dupes)
      (doseq [dup dupes]
        (let [group (by-wire dup)]
          (trace/emit-error! :rf.error/suspense-boundary-duplicate-id
                             {:id       dup
                              :raw-ids  (mapv :id group)
                              :count    (count group)
                              :recovery :last-write-wins}))))
    ;; Reduce preserving insertion order — keep the LAST registration for
    ;; each WIRE id by walking forward and overwriting.
    (let [seen (volatile! #{})]
      (->> (reverse conts)
           (filter (fn [{:keys [id]}]
                     (let [w (wire-id id)]
                       (if (contains? @seen w)
                         false
                         (do (vswap! seen conj w) true)))))
           reverse
           vec))))

;; ---- the shell walker ----------------------------------------------------
;;
;; A specialised emitter that recognises `:rf/suspense-boundary` and emits
;; a placeholder + records a continuation instead of recursing into the
;; subtree. Everything else is delegated to the standard emitter via the
;; recursive `walk-shell` helper.
;;
;; The walker is intentionally a sibling of `emit/emit-element` rather
;; than a drop-in replacement — non-streaming consumers should not pay
;; the suspense-boundary recognition cost on every keyword check, and
;; the recursion shape is different (this walker MUST recurse through
;; children of standard elements to find nested boundaries; the
;; non-streaming emitter is a pure right-fold over strings).

(declare walk-shell)

;; Per rf2-muasb (perf-sweep H2, ai/findings/perf-sweep-2026-05-15.md):
;; the prior implementation called `(some-suspense-boundary? el)` —
;; itself a recursive scan of the entire subtree — on every DOM-tag
;; descent, then took a fast `emit/emit-element` path when the
;; subtree was boundary-free. For a shell with N DOM nodes the
;; ancestor-chain scans collectively walked O(N × tree-depth) nodes,
;; quadratic on deep trees with a buried boundary, and the fast path
;; saved only one fn-call boundary on a tree-walk that emit-element
;; would do anyway.
;;
;; Replaced with the simplest correct shape: walk always recurses
;; through DOM-tag children via `walk-dom-tag`. Single keyword check
;; per node either way. A registered view resolved via `:view`
;; lookup may itself contain boundaries (the conformance fixture
;; root does); the recursion-always shape catches those without any
;; pre-scan. The fast-path option (emit/emit-element on
;; statically-clean subtrees) cannot see through view-refs and so
;; cannot serve as a correct pre-filter without the per-descent
;; cost the audit calls out.

(defn- walk-children [children acc]
  (clojure.string/join (mapv #(walk-shell % acc) children)))

(defn- walk-dom-tag
  "Emit a DOM tag element while recursing into its children with the
  walker (so nested boundaries get caught). Mirrors the non-void branch
  of `emit/emit-element` but threads `acc` through. Per rf2-muasb the
  prior shape called `parse-tag-name` twice on `head` (once destructured
  to `[tag-name _]`, then again to `[_ tag-attrs]`); collapsed to one
  call here, the binding shape mirrors `emit/emit-element` exactly."
  [el acc]
  (let [head                  (first el)
        [tag-name tag-attrs]  (emit/parse-tag-name head)
        [user-attrs children] (if (map? (second el))
                                [(second el) (drop 2 el)]
                                [{} (rest el)])
        merged-attrs          (emit/merge-class-attrs tag-attrs user-attrs)
        ;; rf2-hzttr finding 3 — case-insensitive void classification,
        ;; mirroring the non-streaming emitter. A `[:BR]` admitted by
        ;; `validate-tag-name!` must be recognised as void here too, or the
        ;; shell walker emits a `<BR></BR>` open+close pair. The raw-text
        ;; body guard below (`reject-raw-text-string-children!`) already
        ;; lower-cases internally for the same reason.
        void?                 (contains? emit/void-elements
                                         (keyword (clojure.string/lower-case tag-name)))]
    (if void?
      (str "<" tag-name (emit/attr-string merged-attrs) ">")
      (do
        ;; rf2-ee38b.10 — mirror the non-streaming emitter's raw-text body
        ;; guard so a body-position `<script>`/`<style>` with raw string
        ;; content fails loud in the shell walk too (rather than silently
        ;; `escape-html`-ing it via the standard emitter).
        (emit/reject-raw-text-string-children! tag-name children head)
        (str "<" tag-name (emit/attr-string merged-attrs) ">"
             (walk-children children acc)
             "</" tag-name ">")))))

(defn- suspense-attrs? [m]
  (and (map? m) (contains? m :id) (contains? m :fallback)))

(defn- walk-suspense-boundary
  "At a `:rf/suspense-boundary`, materialise the fallback inline as a
  `<template>` placeholder + register a continuation for the subtree."
  [el acc]
  (let [attrs    (second el)
        ;; Per rf2-ezdwh — `children` was a `(drop 2 el)` lazy seq the
        ;; cond branches below counted twice (`(zero? (count children))`
        ;; then `(= 1 (count children))`). Realise to a vector once and
        ;; bind `n` so the cond reads as a constant-time arity dispatch.
        children (vec (drop 2 el))
        n        (count children)]
    (when-not (suspense-attrs? attrs)
      (error/throw-error!
        :rf.error/suspense-boundary-invalid-attrs
        'rf.ssr/streaming
        (str ":rf/suspense-boundary requires an attrs map with both "
             ":id and :fallback; give it {:id … :fallback …} as its "
             "second element.")
        {:recovery :supply-id-and-fallback-attrs
         :extra    {:got     attrs
                    :element el}}))
    (let [{:keys [id fallback]} attrs
          ;; The subtree is the rest of the hiccup vector after the
          ;; attrs map. Single-child or multi-child both work — we wrap
          ;; multi-children in a `:<>` fragment so the continuation
          ;; renders one logical hiccup form.
          subtree (case n
                    0 nil
                    1 (nth children 0)
                    (into [:<>] children))
          ;; Render the fallback with the standard emitter so it can
          ;; itself contain view-refs / nested hiccup. NOT a recursive
          ;; walk — fallbacks cannot nest suspense-boundaries (they
          ;; render synchronously inline). A `:rf/suspense-boundary`
          ;; INSIDE a fallback is a programmer error; we do not check.
          fallback-html (emit/render-to-string fallback nil)]
      (record-continuation! acc id subtree fallback)
      (fallback-template id fallback-html))))

(defn- walk-shell
  "Walk a hiccup form, building shell HTML and recording continuation
  entries in `acc` (an atom of vector). Standard hiccup is delegated to
  `emit-element`; the suspense-boundary head is intercepted.

  Private — the streaming module's recursive shell walker. The public
  entry point is `render-shell` (below), which binds the per-render
  parse-tag-name memo and the continuations accumulator before invoking
  this fn on the root hiccup."
  [el acc]
  (cond
    (and (vector? el)
         (= :rf/suspense-boundary (first el)))
    (walk-suspense-boundary el acc)

    ;; Recurse into vector children so nested suspense-boundaries are
    ;; reachable. For DOM-tag heads, we re-walk children manually and
    ;; emit the wrapping tag. For view-refs, we resolve the view and
    ;; recurse on its output. For fragments, we splice children.
    (and (vector? el) (keyword? (first el)))
    (let [head (first el)]
      (cond
        ;; Fragment — splice children, recurse.
        (= :<> head)
        (walk-children (rest el) acc)

        ;; Reagent-native interop head `:>` — cannot be rendered on the
        ;; JVM (no React). rf2-ee38b.10 — mirror the non-streaming
        ;; emitter and fail loud rather than splice the component+props
        ;; as raw text. Delegate to the standard emitter so the single
        ;; `:rf.error/ssr-reagent-native-head` throw lives in one place.
        (= :> head)
        (emit/emit-element el)

        ;; Registered view — resolve and recurse on the body. Note that
        ;; subscribe calls inside the view body run synchronously
        ;; against the frame's static app-db, same as the non-streaming
        ;; emitter (per Spec 011 §The render-tree → HTML emitter).
        :else
        (if-let [v (registrar/lookup :view head)]
          (let [raw (apply (:handler-fn v) (rest el))
                ;; Source-coord annotation (Spec 006) is applied by
                ;; `emit/emit-element` on its registered-view branch;
                ;; we mirror the structural-injection shape here on
                ;; the walked subtree so streamed shells still carry
                ;; the annotation. Gated on `interop/debug-enabled?` so a
                ;; production streamed render elides the internal source
                ;; coords — same production-elision contract the non-
                ;; streaming emitter obeys (rf2-wtd8z finding 3).
                coord (when interop/debug-enabled?
                        (emit/format-view-source-coord head v))
                out   (if coord
                        (emit/inject-coord-on-root-hiccup coord raw)
                        raw)]
            (walk-shell out acc))
          ;; DOM tag — always recurse via `walk-dom-tag` so nested
          ;; suspense-boundaries are reachable. Per rf2-muasb the
          ;; prior `some-suspense-boundary?` pre-scan was a perf
          ;; anti-pattern: O(N) per descent, dominated whatever it
          ;; saved by short-circuiting to `emit/emit-element`.
          (walk-dom-tag el acc))))

    (and (vector? el) (ifn? (first el)))
    ;; Callable component head — a plain fn OR a Var reference
    ;; (`[#'component & args]`). On the JVM a Var is `ifn?` but NOT `fn?`,
    ;; so a bare `(fn? …)` test left a Var-headed component falling through
    ;; to the `(sequential? el)` / scalar arms and emitting EDN text rather
    ;; than resolving it (rf2-wtd8z finding 2 — same gap as the non-
    ;; streaming emitter). The keyword branch above (DOM tags, fragments,
    ;; `:>`, registered views) is reached first, so the only callables
    ;; reaching here are fns and Var references. Invoke + recurse on the
    ;; body so the shell walk threads through the Var head.
    (walk-shell (apply (first el) (rest el)) acc)

    (sequential? el)
    (walk-children el acc)

    :else
    ;; Scalar / no-recursion-needed — delegate to the standard emitter.
    (emit/emit-element el)))

;; ---- public surface ------------------------------------------------------

(defn render-shell
  "Walk `root-hiccup` once, materialising the shell HTML with
  `<template>` fallbacks at every `:rf/suspense-boundary` and recording
  the corresponding continuations.

  Returns:

    {:shell-html    \"…\"        ;; the HTML string with fallbacks inline
     :continuations [{:id … :subtree …} …]   ;; FIFO drain order}

  The shell-html is what the host adapter flushes as the first chunk.
  The `:continuations` vector is the drain queue — the host walks it
  in order, calling `render-continuation` on each.

  Throws inside the shell walk propagate. Per Spec 011 §Failure
  semantics — the failure boundary stops at the continuation; a shell-
  walk throw is a structural failure that escalates."
  [root-hiccup]
  ;; Per rf2-ezdwh — bind the per-render parse-tag-name memo so the
  ;; walker's DOM-tag emissions and any inline fallback renders share
  ;; one cache for the whole shell pass. The cache lives only for the
  ;; duration of `render-shell`.
  (binding [emit/*tag-name-cache* (volatile! {})]
    (let [acc       (new-continuations-acc)
          shell     (walk-shell root-hiccup acc)
          conts     (dedupe-continuations @acc)]
      {:shell-html shell
       :continuations conts})))

(defn render-continuation
  "Drain one continuation. `frame-id` is the per-request frame whose
  app-db is the state source; `entry` is one map from `render-shell`'s
  `:continuations` vector.

  Returns:

    {:id            <boundary-id>
     :html          <subtree-html-or-fallback-html>
     :delta         <app-db-delta-map-or-nil>
     :failed?       <boolean>
     :continuations [{:id … :subtree … :fallback …} …]}

  The subtree is rendered through the SAME streaming shell walker
  `render-shell` uses (Spec 011 §922-924/§966/§983 — \"the same emitter,
  recursion-friendly — a nested `:rf/suspense-boundary` re-recurses
  through this same drain\"). A nested `:rf/suspense-boundary` inside the
  subtree therefore does NOT throw (the non-streaming emitter would —
  `emit.cljc` `:rf.error/ssr-suspense-boundary-outside-stream`); instead
  it materialises its OWN fallback `<template>` inline and registers a
  NEW continuation. Those newly-discovered continuations are returned on
  `:continuations` so the host adapter appends them at the TAIL of its
  FIFO drain queue and keeps draining until empty — preserving the
  document-order intuition (each chunk hydrates a strictly-later DOM
  region). `:continuations` is `[]` when the subtree carries no nested
  boundaries (the common case).

  Failure semantics per Spec 011 §Failure semantics — inline fallback:
  a subtree-render throw is caught, the original `:fallback` hiccup is
  materialised in its place, `:rf.ssr/suspense-boundary-failed` is
  emitted on the trace bus, the per-subtree delta is omitted (the client
  keeps its pre-failure delta), and `:continuations` is `[]` (a failed
  outer cannot have registered inner boundaries — the walk aborted)."
  [frame-id {:keys [id subtree fallback]}]
  ;; Snapshot before-db. The continuation may dispatch events
  ;; synchronously during render (subscribe-time computation reads from
  ;; the static db, but a continuation that needs a fresh fetch would
  ;; dispatch the fetch's event before the render — typical pattern).
  ;; For this v1 the delta is computed against the same db the shell
  ;; walked with. Apps that need true async-resolution per-subtree wire
  ;; their fetches as :on-create-time fanout (the test below
  ;; demonstrates the pattern); see Spec 011 §Streaming SSR.
  (let [before-db (frame/frame-app-db-value frame-id)]
    (try
      ;; Bind the per-render parse-tag-name memo (mirrors `render-shell`)
      ;; and drain the subtree through `walk-shell` so a nested boundary
      ;; registers a continuation instead of hitting the non-streaming
      ;; emitter's `:rf/suspense-boundary` reject. `dedupe-continuations`
      ;; applies the same last-write-wins duplicate-id contract within
      ;; this continuation's nested registrations.
      (binding [emit/*tag-name-cache* (volatile! {})]
        (let [acc           (new-continuations-acc)
              resolved-html (walk-shell subtree acc)
              nested        (dedupe-continuations @acc)
              after-db      (frame/frame-app-db-value frame-id)
              delta         (subtree-delta before-db after-db)]
          {:id            id
           :html          resolved-html
           :delta         delta
           :failed?       false
           :continuations nested}))
      (catch #?(:clj Throwable :cljs :default) t
        (trace/emit-error! :rf.ssr/suspense-boundary-failed
                           {:id        id
                            :frame     frame-id
                            :exception (#?(:clj .getMessage :cljs ex-message) t)
                            :recovery  :inline-fallback})
        (let [fallback-html (try
                              (emit/render-to-string fallback nil)
                              (catch #?(:clj Throwable :cljs :default) _
                                ;; Fallback render also threw — emit
                                ;; an empty placeholder. The client-
                                ;; side runtime treats an empty
                                ;; resolved chunk as a no-op.
                                ""))]
          {:id            id
           :html          fallback-html
           :delta         nil
           :failed?       true
           :continuations []})))))

(defn build-final-payload
  "After every continuation has drained, construct the canonical
  `:rf/hydration-payload` for the `__rf_payload` final chunk. The
  client `:rf/hydrate`s against this with `:replace-frame-state` semantics
  — the final payload wins, deltas were speculative.

  Mirrors `re-frame.ssr.ring.payload/build-payload`'s shape so the
  client-side bootstrap can read either streaming or non-streaming
  payloads with the same code path. Both are thin wrappers over the
  shared `re-frame.ssr.payload-policy/build-payload` (rf2-8wrzz.4):
  version resolution (`:rf/version`) and the four-key assembly live
  once there. The streaming path differs only in sourcing `app-db` —
  it reads the live frame's `app-db` after every continuation has
  drained, where the non-streaming path is handed it directly.

  Version source-of-truth: `re-frame.ssr.payload-policy/resolve-version`
  — caller opt wins, falling back to the `:rf2/runtime-version`
  late-bind hook so server and client read from the same source
  (rf2-via0g, mirroring the non-streaming rf2-asmj1 S8 fix).

  The `:rf/app-db` slice is projected per the explicit, fail-closed
  policy in `re-frame.ssr.payload-policy/apply-policy` (rf2-gtgf9,
  rf2-pffil single-opt consolidation): callers MUST declare `:payload`
  as either a vector allowlist of top-level keys (recommended) or the
  keyword `:rf.ssr.payload/whole-app-db` (explicit opt-in to shipping
  the whole `app-db`). Absence throws
  `:rf.error/ssr-missing-payload-policy`. The Ring host adapter
  validates at handler-construction time so misconfigured deployments
  fail at boot rather than at first request."
  [frame-id render-hash {:as policy-opts}]
  (let [app-db     (frame/frame-app-db-value frame-id)
        ;; EP-0001 (rf2-30kzz2): project the live runtime-db value to the
        ;; serializable `:rf/runtime-db` slice (machine snapshots, route slice,
        ;; elision declarations, SSR metadata) so the streamed final payload
        ;; hydrates a coherent frame-state, symmetric with the non-streaming
        ;; path. Transient side channels are excluded by `project-runtime-db`.
        runtime-db (frame/frame-runtime-db-value frame-id)]
    (payload-policy/build-payload
     frame-id
     ;; rf2-bt9kct — allowlist FIRST (`apply-policy`), THEN run the surviving
     ;; slice through the centralized `:rf.egress/ssr-hydration` projection
     ;; seeded at the request frame (defense-in-depth, EP-0015 §14) — symmetric
     ;; with the non-streaming `re-frame.ssr.ring.payload/build-payload` so a
     ;; frame-sensitive child inside an allowlisted key never rides the final
     ;; `__rf_payload` raw.
     (payload-policy/project-app-db-egress
      (payload-policy/apply-policy app-db policy-opts)
      frame-id)
     render-hash
     (assoc policy-opts :runtime-db (payload-policy/project-runtime-db runtime-db)))))

;; ---- late-bind hook registration -----------------------------------------
;;
;; Per Spec 011 §Late-bind hook surface — host adapters call these by
;; key. The chunked-Ring adapter (ssr-ring/streaming) consumes all three.

(late-bind/set-fn! :ssr.streaming/render-shell!         render-shell)
(late-bind/set-fn! :ssr.streaming/render-continuation!  render-continuation)
(late-bind/set-fn! :ssr.streaming/build-final-payload   build-final-payload)
