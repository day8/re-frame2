(ns re-frame.freehand.unpublished-head-absence-jvm-test
  "rf2-tb5yq — the analyzer heads whose authoring vars `re-frame.freehand`
  does not publish, proved through the PRODUCTION resolution path.

  Six analyzer fqn sets named vars that are interned nowhere:

      re-frame.freehand/frame            (frame-fqns)          — still recognised
      re-frame.freehand/raw              (ui-raw-fqns)         — still recognised
      re-frame.freehand/html             (ui-html-fqns)        — still recognised
      re-frame.freehand/frame-root       (frame-root-fqns)     — still recognised
      re-frame.freehand/frame-provider   (frame-provider-fqns) — still recognised
      re-frame.freehand.react/lazy       — arm REMOVED with this suite

  Recognition for each runs off `env/resolves-to?`, and production
  `env/resolve-sym` answers nil for a var that does not exist, so no compiled
  declaration can reach any of those arms through the door. Nothing here
  injects a `:resolver`: a test resolver MANUFACTURES the missing definitions
  and can make an unreachable arm look reachable, which is the synthetic proof
  the rf2-1a9au audit rejected and the whole reason this suite exists.

  The `react/lazy` arm went, being self-contained — one rejection, no site
  bucket, no manifest roster, no emitter arm. The other five are entangled with
  surfaces a conformance row already pins, so their removal is a change to the
  conformance contract and is sequenced separately; every one of them is
  asserted unreachable HERE meanwhile, which is what stops the state being
  rediscovered a third time. All six stay in the roster below: the tripwire is
  about the VARS, and it must red whether an arm is standing or not.

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

  ## Doubly dead: the lowerings name a phantom namespace

  Three of the arms lower to `re-frame.freehand.frames/*`, a namespace that
  exists nowhere in the tree — the same defect class as the
  `re-frame.freehand.hooks/*` lowerings rf2-1a9au removed. So even a published
  authoring var would not make those arms lowerable; recognition and lowering
  have to land together, with the slice that owns them.

  ## Tripwire

  Like `re-frame.freehand.host-hook-absence-jvm-test`, this suite is a
  TRIPWIRE. The day a slice publishes one of these authoring vars, or defines
  the runtime namespace behind it, a row here goes red — and that is the signal
  that the arm's recognition, position law and lowering must land in the SAME
  change, because a compiler that recognises a form it cannot lower is exactly
  the state these Beads exist to remove."
  (:require [clojure.edn :as edn]
            [clojure.test :refer [deftest is testing]]
            [re-frame.build.spec-resource :as spec-resource]
            [re-frame.freehand]
            [re-frame.freehand.compiler.env :as env]))

(def ^:private unpublished-heads
  "The six authoring heads the analyzer recognises and the door does not
  publish, as the fully-qualified symbols an author's `(frame)` / `(v/html s)`
  would have to resolve to."
  '[re-frame.freehand/frame
    re-frame.freehand/raw
    re-frame.freehand/html
    re-frame.freehand/frame-root
    re-frame.freehand/frame-provider
    re-frame.freehand.react/lazy])

(def ^:private phantom-runtime-targets
  "The runtime vars the `frame` / `frame-root` / `frame-provider` arms lower
  to. Their NAMESPACE does not exist, so the lowering could not evaluate even
  if the authoring var were published."
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

(deftest the-frame-arm-lowerings-name-a-phantom-namespace
  (testing "`(frame)`, `[frame-root …]` and `[frame-provider …]` lower to
            `re-frame.freehand.frames/*`. That namespace is defined nowhere, so
            those three arms are dead at BOTH ends — the authoring var they
            recognise and the runtime var they emit. Publishing the authoring
            half alone would turn an unresolvable symbol into a compiled view
            that cannot load, which is strictly worse."
    (is (nil? (find-ns 're-frame.freehand.frames))
        "re-frame.freehand.frames is not a loaded namespace")
    (is (nil? (try (require 're-frame.freehand.frames) :loaded
                   (catch Exception _ nil)))
        "and there is no such namespace on the classpath to load")
    (doseq [sym phantom-runtime-targets]
      (is (not (interned? sym)) (str sym " names no var")))))
