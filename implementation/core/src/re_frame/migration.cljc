(ns re-frame.migration
  "EP-0023 §Backwards Compatibility And EP-0013 Partial Supersession — the
  MIGRATION SHIMS + DIAGNOSTICS slice (rf2-32siq3.11).

  EP-0023 moves the PUBLIC model to `image -> frame -> event stream` while
  RETAINING EP-0013's realm machinery as an internal installation substrate
  (EP-0023 §Backwards Compatibility: \"The proposal retains EP-0013's D1
  runtime-realm machinery as the internal installation boundary\"). This ns is
  the cohesive home for the developer-facing migration surface that move
  produces:

    * `migration-map` — the EP-0013 surface dispositions as INSPECTABLE DATA.
      One entry per superseded / re-expressed / retained EP-0013 public name,
      carrying its EP-0023 disposition + the replacement vocabulary. Tools, the
      migration skill, and the diagnostics below all read it, so the mapping is
      one value, not prose duplicated across N call sites.
    * `explain` — given an EP-0013 surface keyword, return the one-line
      migration guidance string (the human sentence a diagnostic leads with).
    * `assert-process-local-frame-id!` — the actionable DUPLICATE-FRAME-ID
      migration diagnostic. EP-0013's `(realm, frame)` addressing let the SAME
      public frame id coexist in two realms; EP-0023 collapses that to ONE
      process-local frame-id space (EP-0023 §Id Spaces / §Backwards
      Compatibility: \"Code that relied on the same frame id in multiple realms
      must give live frames distinct public ids\"). A caller migrating off the
      `(realm, frame)` address calls this to learn — with an actionable error —
      that a frame id already live in a DIFFERENT realm cannot be reused under
      the EP-0023 model. Fail-loud `:rf.error/cross-realm-frame-id`.

  ## Production elision

  Pure data + fail-loud diagnostics on the migration / frame-creation path (not
  a per-event hot path, not a DEBUG-gated branch). An app that never reaches a
  superseded EP-0013 surface never calls these (Closure DCE removes them). The
  requires are `re-frame.error` (the canonical thrown-error builder) and
  `re-frame.realm` (the realm-frame membership read for the cross-realm check) —
  both already in the core spine; this ns is a leaf (nothing in core requires it
  back, so no cycle)."
  (:require [re-frame.error :as error]
            [re-frame.realm :as realm]))

#?(:clj (set! *warn-on-reflection* true))

;; ===========================================================================
;; The EP-0013 -> EP-0023 surface dispositions (EP-0023 §Backwards Compatibility)
;; ===========================================================================
;;
;; One entry per EP-0013 public name EP-0023 touches. `:status` is the EP-0023
;; disposition (EP-0023 §Surface dispositions table): `:publicly-replaced` (the
;; public name moves to the EP-0023 replacement), `:re-expressed` (the concept
;; survives under image vocabulary), or `:retained-internal` (kept as an
;; implementation / migration / tooling surface, NOT the taught public
;; vocabulary). `:replacement` is the EP-0023 vocabulary the migration guidance
;; points at. `:guidance` is the one-line human sentence a diagnostic leads
;; with. This is DATA so the migration skill + tools + the diagnostics below all
;; read ONE value.

(def migration-map
  "EP-0013 -> EP-0023 surface dispositions as inspectable data — `{surface-kw
  {:status … :replacement … :guidance …}}`. Per EP-0023 §Backwards Compatibility
  / §Surface dispositions. Read by `explain`, the diagnostics in this ns, and
  the migration skill/tools. The public model is `image -> frame -> event
  stream`; the realm substrate is RETAINED internally where it still earns its
  keep."
  {:rf/app
   {:status      :publicly-replaced
    :replacement :rf/image
    :guidance    (str "EP-0023: rf/app (the EP-0013 app value) is publicly "
                      "replaced by rf/image — the selected registration-set "
                      "value a frame loads. Construct an image (rf/image "
                      "{:include-ns [...]}) and supply it to make-frame via "
                      ":images, instead of composing modules into an app value. "
                      "rf/app is retained as an internal/migration surface.")}

   :rf/module
   {:status      :re-expressed
    :replacement :rf/image
    :guidance    (str "EP-0023: rf/module (the EP-0013 module descriptor) is "
                      "re-expressed as an image fragment — a feature namespace "
                      "registers ordinary reg-* forms and an rf/image selects "
                      "them by :include-ns provenance glob. No separate public "
                      "module noun is needed for the core model; rf/module is "
                      "retained as an internal/migration surface.")}

   :rf/install!
   {:status      :retained-internal
    :replacement :rf/make-frame
    :guidance    (str "EP-0023: rf/install! (seat an app value into a realm) is "
                      "retained as an internal/migration/tooling surface — it "
                      "is no longer the taught public vocabulary. The public "
                      "path is make-frame with :images; the realm registrar "
                      "remains the backing installation substrate during "
                      "migration.")}

   :rf/reinstall!
   {:status      :retained-internal
    :replacement :rf/reload-images!
    :guidance    (str "EP-0023: rf/reinstall! (hot-reload a realm's installed "
                      "app value) is retained as an internal/migration surface. "
                      "The public hot-reload path is reload-images! against a "
                      "frame target, which swaps the frame's image generation "
                      "while preserving frame memory.")}

   :rf/installed-app
   {:status      :retained-internal
    :replacement :rf/image
    :guidance    (str "EP-0023: rf/installed-app (the realm's installed app "
                      "value) is retained as a tooling/migration surface. The "
                      "public model inspects a frame's resolved image "
                      "generation; tools may still read the internal "
                      "installation boundary but should label it as "
                      "implementation structure.")}

   :rf/realm
   {:status      :retained-internal
    :replacement :rf/make-frame
    :guidance    (str "EP-0023: rf/realm (the EP-0013 runtime-realm container) "
                      "is retained as the INTERNAL installation substrate — it "
                      "stops being the beginner-facing public architecture. The "
                      "public model targets a FRAME (a process-local frame id, "
                      "or a direct frame object for tests); the frame determines "
                      "the image generation used for registration resolution.")}

   :rf.realm/frame-address
   {:status      :publicly-replaced
    :replacement :rf/make-frame
    :guidance    (str "EP-0023: the EP-0013 (realm, frame) address is replaced "
                      "by a single frame target — a process-local frame id for "
                      "mounted/product code, or a direct frame object for "
                      "tests/harnesses. Code that relied on the SAME frame id in "
                      "multiple realms must give live frames distinct public ids "
                      "or keep direct frame objects in local scope (EP-0023 "
                      "§Id Spaces).")}

   :rf.realm/scoped-query
   {:status      :retained-internal
    :replacement :rf/handler-meta
    :guidance    (str "EP-0023 (rf2-10nggz): the public realm-scoped registrar "
                      "query map arity ((registrations {:realm r :kind k}), the "
                      "{:realm …} handler-meta/handler-ids forms) was REMOVED "
                      "from the facade — a registrar-query map is now ALWAYS a "
                      "frame-targeted read. The realm-scoped readers survive as "
                      "INTERNAL substrate (re-frame.realm/realm-registrations / "
                      "realm-handler-meta / realm-handler-ids) for tooling that "
                      "requires the ns directly. The public model resolves "
                      "registration lookups through the target FRAME's image "
                      "generation.")}})

;; ===========================================================================
;; explain — the migration-guidance lookup
;; ===========================================================================

(defn explain
  "Return the one-line EP-0013 -> EP-0023 migration guidance string for
  `surface-kw` (an EP-0013 public-name keyword such as `:rf/app`, `:rf/module`,
  `:rf/install!`, `:rf/realm`, `:rf.realm/frame-address`,
  `:rf.realm/scoped-query`), or nil for an unknown surface. The human sentence a
  migration diagnostic leads with; reads `migration-map`. Per EP-0023 §Backwards
  Compatibility."
  [surface-kw]
  (get-in migration-map [surface-kw :guidance]))

;; ===========================================================================
;; assert-process-local-frame-id! — the duplicate-frame-id migration diagnostic
;; ===========================================================================
;;
;; EP-0013's `(realm, frame)` addressing keys the `frames` registry by the
;; (realm, frame) pair, so the SAME public frame id is legal in two realms. The
;; EP-0023 public model has ONE process-local frame-id space (EP-0023 §Id
;; Spaces). A codebase migrating off `(realm, frame)` addressing needs an
;; ACTIONABLE error when it tries to reuse a frame id that is already live in a
;; DIFFERENT realm — the EP-0023 deliberate hardening break (EP-0023 §Backwards
;; Compatibility: "Code that relied on the same frame id in multiple realms must
;; give live frames distinct public ids"). This is a CALLER-INVOKED diagnostic:
;; it does NOT mutate the shipped EP-0013 reg-frame path (which legitimately
;; allows the same id across realms for the retained internal substrate), so
;; every existing EP-0013 caller stays byte-identical. A migration tool or a
;; consumer adopting the EP-0023 frame-id contract calls this BEFORE registering
;; a public frame id to surface the collision the new model forbids.

(defn frame-id-realms
  "Return the set of realm ids in which `frame-id` is currently live in the
  EP-0013 `(realm, frame)`-addressed frame registry — `#{}` when the id is live
  in no realm. Reads the realm-owned membership view (`realm/realm-frames` per
  realm). The cross-realm duplicate detector below uses it; tools can read it to
  inspect which realms a given frame id spans before migrating to the EP-0023
  process-local frame-id space. Pure read."
  [frame-id]
  (persistent!
    (reduce
      (fn [acc rid]
        (if (contains? (realm/realm-frames rid) frame-id)
          (conj! acc rid)
          acc))
      (transient #{})
      (realm/realm-ids))))

(defn assert-process-local-frame-id!
  "Fail loud with `:rf.error/cross-realm-frame-id` when `frame-id` is already
  live in a realm OTHER than `target-realm` — the EP-0013 -> EP-0023
  duplicate-frame-id migration break (EP-0023 §Id Spaces / §Backwards
  Compatibility). Under EP-0013's `(realm, frame)` addressing the same public
  frame id could coexist in two realms; the EP-0023 public model has one
  process-local frame-id space, so reusing an id that is already live elsewhere
  is rejected with an actionable error naming the conflicting realms and the
  fix (distinct ids, or a direct frame object in local scope). `target-realm`
  may be a realm map, a realm-id keyword, or nil/absent for the default realm —
  the id is allowed to remain live in the target realm itself (re-registration
  in place is the EP-0013 surgical-update path, not a cross-realm collision).
  Returns `frame-id` when there is no cross-realm collision. A CALLER-INVOKED
  migration diagnostic — it does not mutate the shipped reg-frame path."
  ([frame-id] (assert-process-local-frame-id! frame-id nil))
  ([frame-id target-realm]
   (let [target-rid (realm/realm-id target-realm)
         other      (disj (frame-id-realms frame-id) target-rid)]
     (when (seq other)
       (error/throw-error!
         :rf.error/cross-realm-frame-id
         'rf/make-frame
         (str "rf/make-frame: frame id " (pr-str frame-id) " is already live in "
              "realm(s) " (pr-str (vec (sort other))) " under EP-0013's "
              "(realm, frame) addressing. The EP-0023 public model has ONE "
              "process-local frame-id space (registration ids like :counter/inc "
              "may be reused across images; frame ids like :counter/main may "
              "name only one live frame). Give each live frame a DISTINCT public "
              "frame id, or keep a direct frame object (omit :id) in local scope "
              "for a test/harness — see EP-0023 §Id Spaces.")
         {:recovery :use-a-distinct-frame-id-or-a-direct-frame-object
          :extra    {:frame-id     frame-id
                     :target-realm target-rid
                     :other-realms (vec (sort other))}}))
     frame-id)))
