(ns re-frame.ssr.streaming
  "Streaming SSR — `:rf/suspense-boundary` walker, per-request continuations
  registry, per-subtree hydration deltas. Per Spec 011 §Streaming SSR
  (rf2-ojakd / rf2-olb64 (a)).

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
    body. Returns `{:shell-html … :continuations [{:id … :subtree …} …]}`.

  - `render-continuation` — drains one continuation. Captures the
    before-db, calls `render-to-string` on the subtree, captures the
    after-db, computes the per-subtree app-db delta via
    `clojure.data/diff`. Returns `{:html … :delta {…} :failed? false}`
    or, on subtree-render throw, `{:html <fallback-html>
    :delta nil :failed? true}` with a `:rf.ssr/suspense-boundary-failed`
    trace emitted.

  - `build-final-payload` — after every continuation has drained,
    constructs the canonical `:rf/hydration-payload`. The client
    `:rf/hydrate`s against this with `:replace-app-db` semantics — the
    final payload wins, deltas were speculative.

  All three are pure-ish helper fns; the host adapter (`ssr-ring/streaming`)
  owns the chunked-HTTP wiring and threading.

  Host adapters consume these surfaces via late-bind hooks
  (`:ssr.streaming/render-shell!`, `:ssr.streaming/render-continuation!`,
  `:ssr.streaming/build-final-payload`) registered at ns-load time below.

  Per the rf2-ojakd / rf2-olb64 cluster."
  (:require [clojure.data]
            [clojure.string]
            [re-frame.frame :as frame]
            [re-frame.late-bind :as late-bind]
            [re-frame.registrar :as registrar]
            [re-frame.ssr.emit :as emit]
            [re-frame.ssr.html-helpers :as html]
            [re-frame.ssr.payload-policy :as payload-policy]
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

(defn fallback-template
  "The fallback chunk shape — emitted inline in the shell HTML so the
  browser paints a placeholder immediately."
  [id fallback-html]
  (str "<template data-rf2-suspense-id=\""
       (html/escape-attr (str id))
       "\" data-rf2-suspense-fallback=\"1\">"
       fallback-html
       "</template>"))

(defn resolved-template
  "The resolved-subtree chunk shape — flushed when a continuation drains
  successfully. The client-side streaming runtime swaps the matching
  fallback placeholder for this resolved content in DOM."
  [id resolved-html]
  (str "<template data-rf2-suspense-id=\""
       (html/escape-attr (str id))
       "\" data-rf2-suspense-resolved=\"1\">"
       resolved-html
       "</template>"))

(defn failed-template
  "The failed-continuation chunk shape — same wire shape as
  `resolved-template` plus a `data-rf2-suspense-failed` marker so the
  client-side runtime can surface the failure observably without
  surfacing a 500. Per Spec 011 §Failure semantics — inline fallback."
  [id fallback-html]
  (str "<template data-rf2-suspense-id=\""
       (html/escape-attr (str id))
       "\" data-rf2-suspense-resolved=\"1\""
       " data-rf2-suspense-failed=\"1\">"
       fallback-html
       "</template>"))

(defn hydrate-delta-script
  "The per-subtree hydration delta chunk — application/edn. The client
  reads the EDN, then `(swap! app-db merge delta)`s. Per Spec 011
  §Hydration interleaving."
  [id delta-edn]
  (str "<script data-rf2-suspense-hydrate=\""
       (html/escape-attr (str id))
       "\" type=\"application/edn\">"
       ;; rf2-7ksyr — escape `<` chars so a delta carrying `</script>`
       ;; from any user-controlled string cannot close the envelope.
       ;; Same shape as the final-payload escape in default-html-shell.
       (html/escape-script-body-string delta-edn)
       "</script>"))

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

(defn- dedupe-continuations
  "Per Spec 011 §Boundary nesting and recursion — duplicate `:id` is a
  programmer error. Emit `:rf.error/suspense-boundary-duplicate-id` and
  keep the LAST registration (matches the spec's wire shape: the second
  registration overwrites the first; the earlier fallback placeholder
  is left in place because the client never finds a matching resolved
  chunk). Fail-soft."
  [conts]
  (let [by-id (group-by :id conts)
        dupes (->> by-id (filter (fn [[_ vs]] (> (count vs) 1))) (map key))]
    (when (seq dupes)
      (doseq [dup dupes]
        (trace/emit-error! :rf.error/suspense-boundary-duplicate-id
                           {:id       dup
                            :count    (count (by-id dup))
                            :recovery :last-write-wins})))
    ;; Reduce preserving insertion order — keep the LAST registration
    ;; for each id by walking forward and overwriting.
    (let [seen (volatile! #{})]
      (->> (reverse conts)
           (filter (fn [{:keys [id]}]
                     (if (contains? @seen id)
                       false
                       (do (vswap! seen conj id) true))))
           reverse
           vec))))

;; ---- the shell walker ----------------------------------------------------
;;
;; A specialised emitter that recognises `:rf/suspense-boundary` and emits
;; a placeholder + records a continuation instead of recursing into the
;; subtree. Everything else is delegated to the standard emitter via the
;; recursive `walk` helper.
;;
;; The walker is intentionally a sibling of `emit/emit-element` rather
;; than a drop-in replacement — non-streaming consumers should not pay
;; the suspense-boundary recognition cost on every keyword check, and
;; the recursion shape is different (this walker MUST recurse through
;; children of standard elements to find nested boundaries; the
;; non-streaming emitter is a pure right-fold over strings).

(declare walk)

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
  (clojure.string/join (mapv #(walk % acc) children)))

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
        void?                 (contains? emit/void-elements (keyword tag-name))]
    (if void?
      (str "<" tag-name (emit/attr-string merged-attrs) ">")
      (str "<" tag-name (emit/attr-string merged-attrs) ">"
           (walk-children children acc)
           "</" tag-name ">"))))

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
      (throw (ex-info ":rf.error/suspense-boundary-invalid-attrs"
                      {:rf.error/id :rf.error/suspense-boundary-invalid-attrs
                       :where    'rf.ssr/streaming
                       :reason   ":rf/suspense-boundary requires {:id … :fallback …} attrs map"
                       :got      attrs
                       :element  el
                       :recovery :no-recovery})))
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

(defn walk
  "Walk a hiccup form, building shell HTML and recording continuation
  entries in `acc` (an atom of vector). Standard hiccup is delegated to
  `emit-element`; the suspense-boundary head is intercepted."
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
        ;; Fragment / Reagent-native — splice children, recurse.
        (or (= :<> head) (= :> head))
        (walk-children (rest el) acc)

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
                ;; the annotation.
                coord (emit/format-view-source-coord head v)
                out   (if coord
                        (emit/inject-coord-on-root-hiccup coord raw)
                        raw)]
            (walk out acc))
          ;; DOM tag — always recurse via `walk-dom-tag` so nested
          ;; suspense-boundaries are reachable. Per rf2-muasb the
          ;; prior `some-suspense-boundary?` pre-scan was a perf
          ;; anti-pattern: O(N) per descent, dominated whatever it
          ;; saved by short-circuiting to `emit/emit-element`.
          (walk-dom-tag el acc))))

    (and (vector? el) (fn? (first el)))
    ;; fn-headed component — invoke + recurse on the body.
    (walk (apply (first el) (rest el)) acc)

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
          shell     (walk root-hiccup acc)
          conts     (dedupe-continuations @acc)]
      {:shell-html shell
       :continuations conts})))

(defn render-continuation
  "Drain one continuation. `frame-id` is the per-request frame whose
  app-db is the state source; `entry` is one map from `render-shell`'s
  `:continuations` vector.

  Returns:

    {:id      <boundary-id>
     :html    <subtree-html-or-fallback-html>
     :delta   <app-db-delta-map-or-nil>
     :failed? <boolean>}

  Failure semantics per Spec 011 §Failure semantics — inline fallback:
  a subtree-render throw is caught, the original `:fallback` hiccup is
  materialised in its place, `:rf.ssr/suspense-boundary-failed` is
  emitted on the trace bus, and the per-subtree delta is omitted (the
  client keeps its pre-failure delta)."
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
      (let [resolved-html (emit/render-to-string subtree nil)
            after-db     (frame/frame-app-db-value frame-id)
            [_a only-in-after in-both] (clojure.data/diff before-db after-db)
            ;; The "delta" is the after-db keys that changed OR are
            ;; new. `data/diff` returns [only-in-a only-in-b in-both]
            ;; where the third slot is the shared structure. We keep
            ;; what's NEW in after-db (only-in-after) since the client
            ;; already has the before-state from the prior chunk /
            ;; payload.
            delta         (or only-in-after {})]
        {:id      id
         :html    resolved-html
         :delta   delta
         :failed? false})
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
          {:id      id
           :html    fallback-html
           :delta   nil
           :failed? true})))))

(def ^:private default-pattern-protocol-version
  "The v1 pattern-protocol version stamp (per Spec-Schemas
  §`:rf/hydration-payload` — \"integer; v1 = 1\"). Terminal fallback in
  `resolve-version` so the canonical `:rf/version` key is always present
  (Malli `:int` slot, not `:optional`)."
  1)

(defn resolve-version
  "Pick the `:rf/version` value for the streaming hydration payload.
  Resolution order, identical to the non-streaming
  `re-frame.ssr.ring.payload/resolve-version`:

    1. an explicit `:version` opt from the caller (host-supplied stamp),
    2. the framework-global `:rf2/runtime-version` late-bind hook — the
       same source the client-side `:rf.ssr/check-version` fx reads, so
       both sides of the wire pin one value with no extra wiring,
    3. `default-pattern-protocol-version` (v1 = 1) as the terminal
       numeric fallback (the schema slot is required).

  Audit rf2-asmj1 S8 fixed the non-streaming path; rf2-via0g extends the
  same fix here. The streaming path previously hard-coded `(or version 1)`
  inline, which silently disagreed with whatever the client-side
  `:rf2/runtime-version` hook returned and defeated the
  `:rf.ssr/version-mismatch` check on every host that hadn't passed
  `:version` explicitly."
  [explicit-version]
  (or explicit-version
      (when-let [f (late-bind/get-fn :rf2/runtime-version)]
        (f))
      default-pattern-protocol-version))

(defn build-final-payload
  "After every continuation has drained, construct the canonical
  `:rf/hydration-payload` for the `__rf_payload` final chunk. The
  client `:rf/hydrate`s against this with `:replace-app-db` semantics
  — the final payload wins, deltas were speculative.

  Mirrors `re-frame.ssr.ring.payload/build-payload`'s shape so the
  client-side bootstrap can read either streaming or non-streaming
  payloads with the same code path. Version source-of-truth:
  `resolve-version` above — caller opt wins, falling back to the
  `:rf2/runtime-version` late-bind hook so server and client read from
  the same source (rf2-via0g, mirroring the non-streaming rf2-asmj1 S8
  fix).

  The `:rf/app-db` slice is projected per the explicit, fail-closed
  policy in `re-frame.ssr.payload-policy/apply-policy` (rf2-gtgf9):
  callers MUST declare `:payload-keys` (allowlist, recommended) or
  `:payload-policy :rf.ssr.payload/whole-app-db` (explicit opt-in to
  shipping the whole `app-db`). Absence of both throws
  `:rf.error/ssr-missing-payload-policy`. The Ring host adapter
  validates at handler-construction time so misconfigured deployments
  fail at boot rather than at first request."
  [frame-id render-hash {:keys [version schema-digest] :as policy-opts}]
  (let [app-db   (frame/frame-app-db-value frame-id)
        db-slice (payload-policy/apply-policy app-db policy-opts)]
    (cond-> {:rf/version     (resolve-version version)
             :rf/frame-id    frame-id
             :rf/app-db      db-slice
             :rf/render-hash render-hash}
      schema-digest (assoc :rf/schema-digest schema-digest))))

;; ---- late-bind hook registration -----------------------------------------
;;
;; Per Spec 011 §Late-bind hook surface — host adapters call these by
;; key. The chunked-Ring adapter (ssr-ring/streaming) consumes all three.

(late-bind/set-fn! :ssr.streaming/render-shell!         render-shell)
(late-bind/set-fn! :ssr.streaming/render-continuation!  render-continuation)
(late-bind/set-fn! :ssr.streaming/build-final-payload   build-final-payload)
