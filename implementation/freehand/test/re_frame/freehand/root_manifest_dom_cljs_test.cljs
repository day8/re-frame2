(ns re-frame.freehand.root-manifest-dom-cljs-test
  "FH-ROOT-006 and FH-ROOT-007 in a real browser — the half of hydration
  that happens BEFORE React: where a hydrating root gets its identity.

  `v/hydrate-root` READS its identity off the wire. The server emits a Root
  Manifest as the container's immediately following element sibling, and
  the root takes its `:root-id` and its `identifierPrefix` from that
  manifest's CONTENT (Spec 011 §Root Manifest v1). Everything in this file
  turns on one fixture decision: the planted manifest names an id the
  mounted root form would NEVER derive. A manifest that agreed with
  derivation would satisfy every assertion here by coincidence and prove
  nothing at all — which is precisely the state the derived implementation
  was in before this row was proved.

  Discovery lives in the optional SSR artefact and is reached through the
  `:ssr/discover-root-manifest` late-bind hook (`freehand → core late-bind
  ← ssr`). This suite drives the REAL `re-frame.ssr.manifest/discover` and
  the REAL wire form its `script-html` emits — a hand-rolled stand-in would
  prove the client half against a fiction of the server half.

  The two absences are DIFFERENT faults and are pinned apart: the artefact
  missing entirely (`:rf.error/ssr-artefact-missing`, naming the Maven
  coordinate to add) versus the artefact present and finding nothing
  adjacent (`:rf.error/root-manifest-invalid` `{:missing :manifest}`).

  Browser-only — discovery walks `nextElementSibling` and hydration is a
  real-DOM operation. The `-dom-cljs-test` suffix opts this file into the
  browser lane; the node suites load it too, where it has no DOM and says
  so rather than passing quietly."
  (:require [cljs.test :refer-macros [async deftest is testing use-fixtures]]
            [clojure.string :as str]
            [re-frame.freehand :as v]
            [re-frame.freehand.conformance :as conf]
            [re-frame.freehand.react :as fr]
            [re-frame.freehand.root :as root]
            [re-frame.freehand.root-id :as root-id]
            [re-frame.freehand.root-views :as views]
            [re-frame.late-bind :as late-bind]
            ;; The SSR artefact's manifest namespace: it publishes the
            ;; discovery hook at ns-load and owns the wire form. Requiring it
            ;; from a TEST is the seam being proved, not a dependency — nothing
            ;; under `implementation/freehand/src` requires it, and nothing
            ;; ever will.
            [re-frame.ssr.manifest :as ssr-manifest]))

(def root-006 (conf/fixture :FH-ROOT-006))
(def root-007 (conf/fixture :FH-ROOT-007))

;; React commits a hydration on ITS OWN schedule, not inside `act`, so this
;; file runs with the act environment off — and pins it rather than inheriting
;; whatever a sibling suite on the shared browser page left behind. The
;; previous value is restored, because the suites that DO want `act` run after
;; this one.
(def ^:private act-env (atom nil))

(use-fixtures :each
  {:before (fn []
             (reset! act-env (.-IS_REACT_ACT_ENVIRONMENT js/globalThis))
             (set! (.-IS_REACT_ACT_ENVIRONMENT js/globalThis) false)
             (root/reset-registry!)
             (fr/reset-boundaries!))
   :after  (fn []
             (set! (.-IS_REACT_ACT_ENVIRONMENT js/globalThis) @act-env)
             (root/reset-registry!)
             (fr/reset-boundaries!))})

(defn- browser? []
  (and (exists? js/document) (some? (.-createElement js/document))))

(defn- skip! [why]
  (is true (str "a real React mount needs a DOM host — " why)))

(defn- settle
  "Give React its hydration commit, then call `k`.

  This is load-bearing, not decoration. Tearing a HYDRATING root down in
  the same tick it was created is an update that arrives before React has
  adopted anything, which React reports as a recovery — *\"this root
  received an early update, before anything was able hydrate\"* — and with
  no host `:on-recoverable-error` the framework reporter faithfully
  replicates React's own default and re-throws it at the window. That is
  correct behaviour and an uncaught page error, which the browser runner
  fails the whole run on even when every assertion passed.

  Waiting for the adoption is also the honest thing to assert against: the
  identity facts below are then read off a root that really hydrated,
  which the node-identity check makes self-verifying rather than a
  hopeful sleep."
  [k]
  (js/setTimeout k 300))

(defn- server-page!
  "Plant the shape a real server render leaves on the page: the root's
  CONTAINER, and — when `manifest` is given — that root's Root Manifest as
  the immediately following element sibling, in the wire form the shipped
  emitter produces. Adjacency IS the discovery rule, so the pair goes
  inside its own host element where nothing can come between them.

  Answers the container."
  [html manifest]
  (let [host (js/document.createElement "div")]
    (set! (.-innerHTML host)
          (str "<div>" html "</div>"
               (when manifest (ssr-manifest/script-html manifest))))
    (.appendChild js/document.body host)
    (.-firstElementChild host)))

(defn- remove-page! [container]
  (some-> container .-parentElement .remove))

(defn- thrown
  "`{:id :data :msg}` for the ex-info `thunk` throws, or nil when it does
  not throw. Never merely asserts that something was raised."
  [thunk]
  (try (thunk) nil
       (catch :default e
         {:id   (:rf.error/id (ex-data e))
          :data (ex-data e)
          :msg  (ex-message e)})))

(def ^:private greeting-form [views/greeting (:props root-006)])

;; ===========================================================================
;; Identity comes from the wire
;; ===========================================================================

(deftest fh-root-006-identity-is-read-from-the-manifest-never-derived
  (testing "Per FH-ROOT-006 (browser): a hydrating root takes its :root-id
            and its identifierPrefix from the manifest's CONTENT. The
            manifest here names an id `[greeting {…}]` could not possibly
            derive, and a prefix the client has no rule that would produce —
            so every value asserted below can only have come off the wire."
    (if-not (browser?)
      (skip! "the browser job runs the hydration assertions")
      (async done
        (let [wire-id  (:root-id root-006)
              derived  (:derived-root-id root-006)
              prefix   (:identifier-prefix root-006)
              node     (server-page! (:server-html root-006) (:manifest root-006))
              ;; captured BEFORE React touches the container — the adoption
              ;; check below is object identity, which a client re-render
              ;; cannot fake
              served   (.querySelector node "#greeting")
              mounted  (v/hydrate-root node greeting-form)
              desc     (root/root-descriptor mounted)]
          (is (not= derived wire-id)
              "the fixture's manifest names an id derivation would never produce —
               without that this whole test is a tautology")
          (is (= wire-id (:root-id desc))
              "the root's identity is the MANIFEST's root-id")
          (is (= (:provenance root-006) (:root-id-provenance desc))
              "and the descriptor says where it came from: off the wire, not out
               of a derivation")
          (is (= derived (:view-id desc))
              ":view-id stays the CLIENT's own fact — which declared view this
               site mounted, which is how a tool gets from a root back to a
               declaration. For a single-root derivation that id IS what the
               root-id would have derived to, which is why the two differ here")
          (is (= #{wire-id} (root/live-root-ids))
              "the registry is keyed on the wire id too — the derived id claims
               nothing")
          (is (= prefix (root/root-identifier-prefix mounted))
              "and the effective identifierPrefix is the server's, verbatim")
          (is (not= (root-id/default-identifier-prefix derived)
                    (root/root-identifier-prefix mounted))
              "which is NOT the prefix this root form's derivation would have
               produced")
          (settle
            (fn []
              (is (identical? served (.querySelector node "#greeting"))
                  "the adoption really happened — the server's own node object is
                   still the mounted one, which is what makes the settle above a
                   verified precondition rather than a hopeful sleep")
              ;; The prefix claim is real, not merely recorded: a second root
              ;; asking for the manifest's prefix is refused, which is the
              ;; framework's own uniqueness fence reading back what this root
              ;; actually claimed.
              (let [other (server-page! "" nil)
                    t     (thrown #(v/mount [views/app {:label "other"}] other
                                            {:root-id :fh.root/prefix-probe
                                             :identifier-prefix prefix}))]
                (is (= :rf.error/duplicate-identifier-prefix (:id t))
                    "the hydrating root really holds the manifest's prefix claim")
                (remove-page! other))
              (v/unmount! mounted)
              (remove-page! node)
              (done))))))))

;; ===========================================================================
;; The two absences, pinned apart
;; ===========================================================================

(deftest fh-root-006-server-markup-with-no-manifest-fails-loud
  (testing "Per FH-ROOT-006 (browser): a container the server DID render,
            with nothing adjacent to say what it rendered it as, has no
            identity to hydrate under. That is a broken server render rather
            than a client-only load, so it fails loud instead of quietly
            guessing an identity and hoping the guess matches."
    (if-not (browser?)
      (skip! "the browser job runs the hydration assertions")
      (let [expected (:no-manifest root-006)
            node     (server-page! (:server-html root-006) nil)
            served   (.querySelector node "#greeting")
            t        (thrown #(v/hydrate-root node greeting-form))]
        (is (= (:error expected) (:id t))
            (str "server markup with no adjacent manifest is refused. Saw: "
                 (pr-str t)))
        (is (= (:missing expected) (:missing (:data t)))
            "and the ex-data says WHICH absence it was")
        (is (empty? (root/live-root-ids))
            "the refusal happened before anything was claimed")
        (is (identical? served (.querySelector node "#greeting"))
            "…and before React: the server's own node is still the node in the
             container, so nothing was rendered over it and nothing had to be
             rolled back")
        (remove-page! node)))))

(deftest fh-root-006-hydration-without-the-ssr-artefact-names-the-coordinate
  (testing "Per FH-ROOT-006 (browser): discovery lives in the optional SSR
            artefact, so an app that hydrates without it on the classpath
            has an UNBOUND hook — a different fault from finding nothing
            adjacent, and one whose only useful answer is the coordinate to
            add. The hook is unbound here for the duration, which is exactly
            the state such an app boots in."
    (if-not (browser?)
      (skip! "the browser job runs the hydration assertions")
      (let [expected (:no-ssr-artefact root-006)
            original (late-bind/get-fn :ssr/discover-root-manifest)
            node     (server-page! (:server-html root-006) (:manifest root-006))]
        (is (some? original)
            "the hook is bound to begin with — otherwise this test proves
             nothing about unbinding it")
        (late-bind/set-fn! :ssr/discover-root-manifest nil)
        (let [t (thrown #(v/hydrate-root node greeting-form))]
          (late-bind/set-fn! :ssr/discover-root-manifest original)
          (is (= (:error expected) (:id t))
              (str "an unbound hook is the artefact-missing fault. Saw: " (pr-str t)))
          (is (str/includes? (or (:msg t) "") (:maven expected))
              (str "and it names the copy-pasteable coordinate. Saw: " (pr-str (:msg t))))
          (is (empty? (root/live-root-ids))
              "nothing was claimed"))
        (remove-page! node)))))

;; ===========================================================================
;; FH-ROOT-007 — the ordering
;; ===========================================================================

(deftest fh-root-007-the-fallback-precedes-manifest-discovery
  (testing "Per FH-ROOT-007 (browser): a client-only first load has an empty
            container AND no manifest — a page the server never rendered
            emits neither. So the empty container is recognised BEFORE any
            manifest is asked for; asking first would turn every client-only
            first load into {:missing :manifest} and delete the fallback
            outright. The root that comes up carries the DERIVED identity,
            because there is no server render here to read one from."
    (if-not (browser?)
      (skip! "the browser job runs the fallback assertions")
      (let [expected (:empty-container root-007)
            node     (server-page! "" nil)
            mounted  (v/hydrate-root node [views/greeting (:props root-007)])
            desc     (root/root-descriptor mounted)]
        (is (= (:hydrated expected) (root/hydrated? mounted))
            "it fell back rather than adopting")
        (is (= (:root-id expected) (:root-id desc))
            "under the DERIVED identity — the id an ordinary v/mount would give it")
        (is (= (:provenance expected) (:root-id-provenance desc))
            "and it says so")
        (v/unmount! mounted)
        (remove-page! node)))))
