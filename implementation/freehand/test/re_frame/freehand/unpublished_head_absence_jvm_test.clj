(ns re-frame.freehand.unpublished-head-absence-jvm-test
  "rf2-tb5yq — the analyzer heads whose authoring vars `re-frame.freehand`
  does not publish, proved through the PRODUCTION resolution path.

  Six analyzer fqn sets named vars that were interned nowhere:

      re-frame.freehand/frame            (frame-fqns)          — arm RETIRED
      re-frame.freehand/raw              (ui-raw-fqns)         — arm RETIRED
      re-frame.freehand/html             (ui-html-fqns)        — SHIPPED
      re-frame.freehand/frame-root       (frame-root-fqns)     — arm RETIRED
      re-frame.freehand/frame-provider   (frame-provider-fqns) — arm RETIRED
      re-frame.freehand.react/lazy       — arm REMOVED with this suite

  Recognition for each ran off `env/resolves-to?`, and production
  `env/resolve-sym` answers nil for a var that does not exist, so no compiled
  declaration could reach any of those arms through the door. Nothing here
  injects a `:resolver`: a test resolver MANUFACTURES the missing definitions
  and can make an unreachable arm look reachable, which is the synthetic proof
  the rf2-1a9au audit rejected and the whole reason this suite exists.

  All five arms that could go have gone. `react/lazy` went first, being
  self-contained. The FRAME FAMILY — `frame`, `frame-root`, `frame-provider` —
  went with rf2-h1ae3, taking the `:frame-ops` site bucket, its `FH-STRUCT-010`
  manifest roster, the `:frame` capability bit and the compile-tier static
  frame-plan scan; the elision verdict did not move, because the bucket was
  provably always empty. `raw` went with rf2-4gnrs, along with the `:foreign`
  crossing-prop marker it alone minted — and there the capability was never
  lost, since a runtime React ELEMENT crosses into a Freehand tree unwrapped on
  its own account.

  ## `html` went the other way, and this suite records that

  `html` is the sixth, and rf2-rrosy SHIPPED it rather than retiring it. It had
  no other spelling to fall back on: the attribute grammar refuses every
  `dangerouslySetInnerHTML` prop spelling on three tiers and names `(v/html …)`
  as the recovery, so retiring the verb would have left a substrate that
  refuses raw markup everywhere and offers no supervised alternative. So
  `re-frame.freehand/html` is published, its recognition is reachable through
  production resolution, `:html` is admitted by the v1 grammar, and both
  emitters lower it — the React one to `dangerouslySetInnerHTML`, the JVM one
  to the canonicaliser's `:html` slot.

  It therefore moves from the ABSENCE roster to the COVERAGE one, and the two
  are asserted in the same shape and by the same probes: unpublished heads
  resolve to nil on both hosts, `html` resolves to its var on both and carries
  a `spec/api-manifest.edn` row. That is the tripwire kept POINTED — a suite
  that simply dropped the name would have stopped saying anything about it, and
  the fact worth pinning is no longer that the var is absent but that
  recognition, publication and lowering landed together.

  ## Why the JVM probe alone is not the proof

  `re-frame.freehand` is `.cljc`, so a var published under `#?(:cljs …)` — as
  `v/mount` is — is genuinely absent from a JVM `ns-resolve` while being fully
  public. A JVM-only probe therefore cannot tell an unpublished var from a
  browser-only one, and `mount` is carried below as the CONTROL that makes that
  gap visible rather than assumed.

  What closes it is `spec/api-manifest.edn`: the generated roster of live
  public vars, reconciled against the real surface in BOTH directions on both
  hosts (`implementation/scripts/api-manifest/probe/`, and see
  `re-frame.freehand.public-surface-jvm-test`). A name absent from that roster
  is published on NEITHER host. The two probes together are the fact this Bead
  rests on.

  ## Doubly dead: the frame lowerings named a phantom namespace

  Three of the retired arms lowered to `re-frame.freehand.frames/*`, a namespace
  that exists nowhere in the tree — the same defect class as the
  `re-frame.freehand.hooks/*` lowerings rf2-1a9au removed. So even a published
  authoring var would not have made those arms lowerable, which is why they were
  retired rather than shipped: recognition and lowering have to land together,
  with the slice that owns them. The absence is still pinned below, and the
  production door is driven through a body spelling each retired head so the
  removal is proved rather than asserted.

  ## Tripwire

  Like `re-frame.freehand.host-hook-absence-jvm-test`, this suite is a
  TRIPWIRE. The day a slice publishes one of these authoring vars, or defines
  the runtime namespace behind it, a row here goes red — and that is the signal
  that the arm's recognition, position law and lowering must land in the SAME
  change, because a compiler that recognises a form it cannot lower is exactly
  the state these Beads exist to remove."
  (:require [clojure.edn :as edn]
            [clojure.test :refer [deftest is testing]]
            [clojure.walk :as walk]
            [re-frame.build.spec-resource :as spec-resource]
            [re-frame.freehand]
            [re-frame.freehand.compiler :as compiler]
            [re-frame.freehand.compiler.env :as env]
            [re-frame.freehand.compiler.grammar :as grammar]))

(def ^:private unpublished-heads
  "The five authoring heads the analyzer recognised and the door does not
  publish, as the fully-qualified symbols an author's `(frame)` would have to
  resolve to. All five arms are retired."
  '[re-frame.freehand/frame
    re-frame.freehand/raw
    re-frame.freehand/frame-root
    re-frame.freehand/frame-provider
    re-frame.freehand.react/lazy])

(def ^:private shipped-heads
  "The head that went the OTHER way (rf2-rrosy): recognised, PUBLISHED, and
  lowered by both emitters. Carried here rather than deleted so the tripwire
  keeps saying something about the name — the fact to pin is no longer the
  absence but that all three landed together."
  '[re-frame.freehand/html])

(def ^:private phantom-runtime-targets
  "The runtime vars the retired `frame` / `frame-root` / `frame-provider` arms
  lowered to. Their NAMESPACE does not exist, so no lowering could have
  evaluated even if the authoring var were published."
  '[re-frame.freehand.frames/frame-ops
    re-frame.freehand.frames/jvm-root-scope
    re-frame.freehand.frames/jvm-provider-scope])

(def ^:private published-on-both-hosts
  "Published `re-frame.freehand` vars that the JVM also interns — the control
  for the nils below."
  '[re-frame.freehand/sub
    re-frame.freehand/slot
    re-frame.freehand/raw-fn])

(defn- production-env
  "The env the JVM structural door builds — `:clj` host, this namespace, no
  `:resolver`. `re-frame.freehand` is required above, so a resolution failure
  here is a MISSING VAR and not a missing namespace."
  []
  (env/make-env {:host    :clj
                 :ns-sym  're-frame.freehand.unpublished-head-absence-jvm-test
                 :self    'probe
                 :self-id :re-frame.freehand.unpublished-head-absence-jvm-test/probe}))

(defn- interned? [sym]
  (some? (try (ns-resolve 're-frame.freehand.unpublished-head-absence-jvm-test sym)
              (catch Exception _ nil))))

(def ^:private manifest-vars
  "Every `{:namespace :var}` pair in the generated public-API roster."
  (->> (edn/read-string (spec-resource/slurp-resource nil "api-manifest.edn"))
       :vars
       (into #{} (map (fn [{:keys [namespace var]}] (symbol namespace var))))))

;; ---------------------------------------------------------------------------
;; The vars are unpublished, on BOTH hosts
;; ---------------------------------------------------------------------------

(deftest the-generated-api-roster-is-readable-and-carries-the-door
  (testing "Non-vacuity for every roster assertion below: the manifest read
            really produced the published surface. An empty or unreadable
            roster would make every absence claim true for the wrong reason."
    (is (< 100 (count manifest-vars))
        "the generated roster loaded")
    (is (contains? manifest-vars 're-frame.freehand/defview)
        "and it carries the Freehand door")))

(deftest no-unpublished-head-appears-in-the-public-api-roster
  (testing "The host-independent half. `spec/api-manifest.edn` is generated
            from live public vars and reconciled in both directions, so a name
            absent from it is published on NEITHER host — which is what
            distinguishes these six from a browser-only var."
    (doseq [sym unpublished-heads]
      (is (not (contains? manifest-vars sym))
          (str sym " has no public-API row — when it gains one, the slice that "
               "publishes it must land its analyzer recognition, position law "
               "and lowering in the same change")))))

(deftest a-browser-only-published-var-is-the-control-for-that-claim
  (testing "`v/mount` is published under `#?(:cljs …)`: absent from a JVM
            `ns-resolve` and absent from `:clj` production resolution, yet
            fully public. Without this row the nils below would look like proof
            of absence when they are only proof of host."
    (is (not (interned? 're-frame.freehand/mount))
        "the JVM does not intern it")
    (is (nil? (env/resolve-sym (production-env) 're-frame.freehand/mount))
        "and :clj production resolution answers nil for it")
    (is (contains? manifest-vars 're-frame.freehand/mount)
        "yet it IS published — so the JVM probe alone never proves absence")))

(deftest no-unpublished-head-is-interned-on-the-jvm
  (testing "The JVM half, which is the door the structural compile really
            runs. Neither `re-frame.freehand` nor `re-frame.freehand.react`
            interns any of the six."
    (doseq [sym unpublished-heads]
      (is (not (interned? sym)) (str sym " is not interned")))))

(deftest production-resolution-answers-nil-for-every-unpublished-head
  (testing "Through the resolution the compiled door actually performs —
            `env/resolve-sym` over a production env with NO injected resolver —
            every one of the six answers nil. An arm gated on
            `env/resolves-to?` therefore cannot run, whatever body is compiled."
    (let [e (production-env)]
      (doseq [sym unpublished-heads]
        (is (nil? (env/resolve-sym e sym))
            (str "production resolution of " sym))))))

;; ---------------------------------------------------------------------------
;; `html` is published — recognition, publication and lowering, one slice
;; ---------------------------------------------------------------------------

(deftest the-shipped-head-is-published-recognised-and-lowered
  (testing "rf2-rrosy. `html` sat in the roster above with the note that it
            would ship, and this is the row that says it did. Three facts, in
            the same shape the absence rows use, because half of them was
            exactly the state the Bead existed to remove: a compiler that
            recognises a form it cannot lower, and a var whose publication
            without the lowerings would have rendered NOTHING."
    (let [e (production-env)]
      (doseq [sym shipped-heads]
        (is (interned? sym) (str sym " is interned on the JVM"))
        (is (= sym (:fqn (env/resolve-sym e sym)))
            (str "production resolution reaches " sym " — the analyzer arm that "
                 "recognises it is live through the real door"))
        (is (contains? manifest-vars sym)
            (str sym " carries a public-API row, so it is published on BOTH hosts")))))
  (testing "The lowering half, at the grammar. `:html` is inside
            `:re-frame.freehand/v1`, so `grammar/check!` no longer refuses a
            body carrying one — and the per-carrier emitter coverage suites
            (`re-frame.freehand.react-lowering-jvm-test`) prove each emitter
            really reads the slot."
    (is (contains? grammar/admitted-ops :html)
        ":html is admitted by the v1 grammar")))

(deftest production-resolution-still-sees-the-vars-that-do-exist
  (testing "The control that makes the nils above mean something: the SAME env
            resolves the authoring vars the analyzer really does recognise. A
            resolver answering nil to everything would pass every row above
            while proving nothing."
    (let [e (production-env)]
      (doseq [sym published-on-both-hosts]
        (is (= sym (:fqn (env/resolve-sym e sym))) (str sym))
        (is (contains? manifest-vars sym)
            (str sym " is published, and the roster agrees"))))))

;; ---------------------------------------------------------------------------
;; The lowerings name a namespace that does not exist
;; ---------------------------------------------------------------------------

(deftest the-retired-frame-arm-lowerings-named-a-phantom-namespace
  (testing "`(frame)`, `[frame-root …]` and `[frame-provider …]` lowered to
            `re-frame.freehand.frames/*`. That namespace is defined nowhere, so
            those three arms were dead at BOTH ends — the authoring var they
            recognised and the runtime var they emitted. Publishing the authoring
            half alone would have turned an unresolvable symbol into a compiled
            view that cannot load, which is strictly worse; that is why rf2-h1ae3
            retired them rather than shipping half a slice."
    (is (nil? (find-ns 're-frame.freehand.frames))
        "re-frame.freehand.frames is not a loaded namespace")
    (is (nil? (try (require 're-frame.freehand.frames) :loaded
                   (catch Exception _ nil)))
        "and there is no such namespace on the classpath to load")
    (doseq [sym phantom-runtime-targets]
      (is (not (interned? sym)) (str sym " names no var")))))

;; ---------------------------------------------------------------------------
;; The door itself — what a retired-head body compiles to now
;; ---------------------------------------------------------------------------

(defn- compile-body
  "Lower `body` through the PRODUCTION compiled door — `compile-structural-view`
  with a nil `menv` (the JVM structural host) and no resolver, which is exactly
  what `v/defview {:compiled true}` calls. The emitted `:body` is a FORM and is
  never evaluated here."
  [body]
  (compiler/compile-structural-view
   {:form            nil
    :menv            nil
    :ns-sym          're-frame.freehand.unpublished-head-absence-jvm-test
    :vname           'probe
    :view-id         :re-frame.freehand.unpublished-head-absence-jvm-test/probe
    :params          '[_]
    :body            body
    :children-policy :none}))

(defn- syms-in [form]
  (let [hits (atom #{})]
    (walk/postwalk (fn [x] (when (symbol? x) (swap! hits conj x)) x) form)
    @hits))

(deftest a-frame-body-compiles-to-no-phantom-frames-symbol
  (testing "The regression rf2-h1ae3 names. A body spelling `(v/frame)` once
            lowered to re-frame.freehand.frames/frame-ops — a var in a namespace
            defined nowhere — so any compiled view reaching the arm carried an
            unresolvable symbol. Through the production door the head is now just
            an opaque call the analyzer passes through, and NOTHING in the emitted
            form names the phantom namespace."
    (let [{:keys [body]} (compile-body '[[:div (re-frame.freehand/frame)]])
          syms           (syms-in body)]
      (is (not-any? #(= "re-frame.freehand.frames" (namespace %)) syms)
          "no emitted symbol lives in the phantom frames namespace")
      (is (contains? syms 're-frame.freehand/frame)
          "the authored head survives verbatim — an ordinary unresolved symbol
           the host compiler reports, not a compiler-invented one"))))

(deftest a-retired-head-body-claims-no-retired-capability
  (testing "The manifest half. With the site bucket gone there is no way for a
            compiled declaration to claim `:frame`, and with the `:raw`
            recognition gone none can claim `:raw` — so a manifest cannot
            advertise a capability the substrate has no runtime for, and the
            ViewCell verdict reads only the inputs it really has."
    (let [{:keys [manifest]} (compile-body '[[:div (re-frame.freehand/frame)
                                              (re-frame.freehand/raw el)]])]
      (is (empty? (filter #{:frame :raw} (:capabilities manifest)))
          "the manifest claims neither retired capability")
      (is (empty? (filter #{:frame :raw} (:caps (:static-facts manifest))))
          "and neither do its render-static facts")
      (is (not (contains? manifest :frame-ops))
          "the retired roster is absent from the manifest, not present-and-empty")
      (is (= :elided (:view-cell manifest))
          "a body whose only 'reads' are opaque calls still proves the ViewCell
           elidable — subs and committed handlers are what the verdict turns on"))))
