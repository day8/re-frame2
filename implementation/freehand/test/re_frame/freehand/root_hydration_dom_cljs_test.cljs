(ns re-frame.freehand.root-hydration-dom-cljs-test
  "FH-ROOT-006 and FH-ROOT-007 in a real browser — hydration, the mismatch
  report, and the fallback.

  Two things about this file are deliberate and load-bearing.

  FIRST, adoption is proven by NODE IDENTITY. The server's markup is put
  into the container, the exact DOM node objects it created are captured,
  and after hydration those SAME objects must still be mounted. A root
  that quietly threw the server render away and client-rendered instead
  would produce identical text, and every text assertion would pass.

  SECOND, the mismatch harness is proved to CAPTURE before it is trusted
  to report absence. React's `onRecoverableError` is the only signal here,
  so a harness wired to nothing reports nothing and passes on broken and
  correct HTML alike. The divergent case therefore runs first in reading
  order and asserts a NON-EMPTY capture; only then does the matching case
  assert an empty one. Neither assertion means anything without the other.

  The mismatch is reported by React on its own schedule, not inside `act`,
  so the divergent tests run with the act environment OFF and poll for the
  signal — the treatment the substrate suites already use for exactly this.

  This file rides the browser lane through its `-dom-cljs-test` namespace
  suffix. It also matches the node suites' broader regex, where it has no
  DOM to mount and says so rather than passing quietly."
  (:require ["react" :as react]
            [cljs.test :refer-macros [async deftest is testing use-fixtures]]
            [re-frame.freehand :as v]
            [re-frame.freehand.conformance :as conf]
            [re-frame.freehand.react :as fr]
            [re-frame.freehand.root :as root]
            [re-frame.freehand.root-views :as views]
            [re-frame.trace.tooling :as trace-tooling]))

(def root-006 (conf/fixture :FH-ROOT-006))
(def root-007 (conf/fixture :FH-ROOT-007))

(use-fixtures :each
  {:before (fn [] (root/reset-registry!) (fr/reset-boundaries!))
   :after  (fn [] (root/reset-registry!) (fr/reset-boundaries!))})

(defn- browser? []
  (and (exists? js/document) (some? (.-createElement js/document))))

(defn- act
  [thunk]
  (try
    (set! (.-IS_REACT_ACT_ENVIRONMENT js/globalThis) true)
    (js/Promise.resolve (react/act (fn [] (js/Promise.resolve (thunk)))))
    (catch :default e
      (js/Promise.reject e))))

(defn- server-node!
  "A container carrying `html` — the server's bytes, already in the
  document, exactly as a hydrating page presents them."
  [html]
  (let [container (js/document.createElement "div")]
    (set! (.-innerHTML container) html)
    (.appendChild js/document.body container)
    container))

(defn- skip! [why]
  (is true (str "a real React mount needs a DOM host — " why)))

(defn- error-id [thunk]
  (try (thunk) ::no-throw
       (catch :default e (:rf.error/id (ex-data e)))))

(defn- text [container selector]
  (some-> (.querySelector container selector) .-textContent))

(defn- listen!
  "Collect every `:rf.ssr/hydration-mismatch` the diagnostic bus carries
  while `k` is registered."
  [sink]
  (let [k (keyword (gensym "rf.fh-hydration-"))]
    (trace-tooling/register-listener!
      k (fn [ev] (when (= :rf.ssr/hydration-mismatch (:operation ev))
                   (swap! sink conj ev))))
    k))

(defn- poll-until
  "Poll `pred` every 5ms up to ~2s, then call `k`. Bounded, so an outcome
  that never arrives lets the assertions fail honestly rather than hang."
  [pred k]
  (let [tries (atom 0)]
    (letfn [(step []
              (if (or (pred) (>= @tries 400))
                (k)
                (do (swap! tries inc) (js/setTimeout step 5))))]
      (step))))

(defn- settle
  "Give React's adoption window a generous moment to close, then call `k`.
  Used where the assertion is an ABSENCE — waiting longer can only make an
  absence claim harder to satisfy, never easier."
  [k]
  (js/setTimeout k 300))

(def ^:private greeting-form [views/greeting (:props root-006)])

;; ===========================================================================
;; FH-ROOT-006 — the KNOWN-BAD case first: the harness can see a mismatch
;; ===========================================================================

(deftest fh-root-006-a-divergent-server-render-is-reported
  (testing "Per FH-ROOT-006 (browser): server markup that disagrees with what
            the client renders fires React's onRecoverableError during
            adoption, and the framework surfaces it as
            :rf.ssr/hydration-mismatch carrying this root's id. This test
            runs FIRST on purpose — it is the harness's proof that it can see
            anything at all. Without it the clean-adoption test below would
            pass on a reporter wired to nothing."
    (if-not (browser?)
      (skip! "the browser job runs the hydration assertions")
      (async done
        (set! (.-IS_REACT_ACT_ENVIRONMENT js/globalThis) false)
        (let [node       (server-node! (:divergent-html root-006))
              divergent  (:divergent root-006)
              mismatches (atom [])
              host-calls (atom [])
              k          (listen! mismatches)
              mounted    (v/hydrate-root node greeting-form
                                         {:on-recoverable-error
                                          (fn [e _] (swap! host-calls conj e))})]
          (poll-until
            #(seq @mismatches)
            (fn []
              (trace-tooling/unregister-listener! k)
              (is (<= (:mismatches-at-least divergent) (count @mismatches))
                  (str "a divergent adoption is REPORTED. Saw: " (pr-str @mismatches)))
              (when-let [mm (first @mismatches)]
                (let [tags (merge (:tags mm) mm)]
                  (is (= (:operation divergent) (:operation mm)))
                  (is (= (:where divergent) (:where tags))
                      "tier-discriminated by :where — the Freehand hydrate site")
                  (is (= (:root-id root-006) (:root-id tags))
                      "and named by the root that diverged")
                  (is (= (:recovery divergent) (:recovery tags))
                      "React's own recovery is a replacement, not a patch")))
              (is (<= (:invocations-at-least (:host-callback root-006))
                      (count @host-calls))
                  "the host's own :on-recoverable-error still fired — the framework
                   reporter composes OVER it and never clobbers it")
              (is (= (:final-text divergent) (text node "#who"))
                  "and the page carries the CLIENT's truth afterwards — the stale
                   server value is replaced, never left on screen under a tree that
                   disagrees with it")
              (is (root/hydrated? mounted)
                  "a disagreeing container is still a hydration — it did not take the
                   fallback")
              (v/unmount! mounted)
              (.remove node)
              (done))))))))

;; ===========================================================================
;; FH-ROOT-006 — the matching case: adoption, proven by node identity
;; ===========================================================================

(deftest fh-root-006-matching-server-markup-is-adopted-not-replaced
  (testing "Per FH-ROOT-006 (browser): the server's own DOM nodes survive the
            mount. Node IDENTITY is the assertion, because text is not: a
            root that discarded the server render and client-rendered would
            produce byte-identical markup. Nothing is reported, and that
            silence means something only because the divergent test above
            proved the same listener does report."
    (if-not (browser?)
      (skip! "the browser job runs the hydration assertions")
      (async done
        (set! (.-IS_REACT_ACT_ENVIRONMENT js/globalThis) false)
        (let [node       (server-node! (:server-html root-006))
              match      (:match root-006)
              before     (into {} (map (juxt identity #(.querySelector node %)))
                               (:same-nodes match))
              mismatches (atom [])
              k          (listen! mismatches)
              mounted    (v/hydrate-root node greeting-form)]
          (settle
            (fn []
              (trace-tooling/unregister-listener! k)
              (doseq [[selector node*] before]
                (is (some? node*) (str "the server rendered " selector))
                (is (identical? node* (.querySelector node selector))
                    (str selector " is the SAME node object the server created — "
                         "adopted, not re-created")))
              (is (= (:mismatches match) (count @mismatches))
                  (str "a clean adoption reports nothing. Saw: " (pr-str @mismatches)))
              (is (= (:adopted match) (root/hydrated? mounted)))
              (is (= (:root-id root-006) (:root-id (root/root-descriptor mounted)))
                  "identity is derived from the mounted view, exactly as at mount")
              (v/unmount! mounted)
              (.remove node)
              (done))))))))

;; ===========================================================================
;; FH-ROOT-006 — identity comes from the server, so client-side opts conflict
;; ===========================================================================

(deftest fh-root-006-identity-opts-are-refused-client-side
  (testing "Per FH-ROOT-006: a hydrating root renders under the identity the
            server already fixed. A client that picks its own
            identifierPrefix breaks use-id hydration outright, so an identity
            opt here is a conflict rather than an override — and the one
            thing that must not be allowed to succeed quietly."
    (if-not (browser?)
      (skip! "the browser job runs the hydration assertions")
      (do
        (doseq [{:keys [opts error]} (:identity-opts-refused root-006)]
          (let [node (server-node! (:server-html root-006))]
            (is (= error (error-id #(v/hydrate-root node greeting-form opts)))
                (str (pr-str opts) " is refused client-side"))
            (.remove node)))
        (is (empty? (root/live-root-ids))
            "and every refusal happened before anything was claimed")))))

;; ===========================================================================
;; FH-ROOT-007 — the fallback, and its boundary
;; ===========================================================================

(deftest fh-root-007-an-empty-container-falls-back-to-a-client-mount
  (testing "Per FH-ROOT-007 (browser): a container with nothing to adopt is
            the client-only first load, not a degraded adoption. Hydrating a
            non-empty client tree against nothing is a TOTAL divergence React
            answers by discarding and re-rendering everything, loudly, for a
            page that was never server-rendered — so the root recognises the
            one input it can recognise before React is involved, mounts
            client-side, and reports it as the mount KIND rather than as a
            pile of adoption errors."
    (if-not (browser?)
      (skip! "the browser job runs the fallback assertions")
      (async done
        (set! (.-IS_REACT_ACT_ENVIRONMENT js/globalThis) false)
        (let [empty*     (:empty-container root-007)
              node       (server-node! "")
              mismatches (atom [])
              k          (listen! mismatches)
              mounted    (v/hydrate-root node [views/greeting (:props root-007)])]
          (is (= (:live-roots-after-fallback root-007) (count (root/live-root-ids)))
              "a fallback root claims its id like any other")
          (settle
            (fn []
              (trace-tooling/unregister-listener! k)
              (is (= (:hydrated empty*) (root/hydrated? mounted))
                  "an empty container did not hydrate")
              (is (= (:text empty*) (text node "#who"))
                  "and the root came up correct anyway")
              (is (= (:mismatches empty*) (count @mismatches))
                  (str "with no adoption errors at all. Saw: " (pr-str @mismatches)))
              (v/unmount! mounted)
              (is (= (:live-roots-after-unmount root-007) (count (root/live-root-ids)))
                  "a fallback root is an ordinary root — it tears down the same way")
              (.remove node)
              (done))))))))

(deftest fh-root-007-a-whitespace-only-container-is-the-same-case
  (testing "Per FH-ROOT-007 (browser): a formatted page skeleton is empty. A
            container holding only the newlines and indentation an HTML
            emitter leaves behind carries nothing to adopt, and treating it
            as server markup would make the fallback depend on the server's
            pretty-printing."
    (if-not (browser?)
      (skip! "the browser job runs the fallback assertions")
      (async done
        (set! (.-IS_REACT_ACT_ENVIRONMENT js/globalThis) false)
        (let [whitespace (:whitespace-container root-007)
              node       (server-node! (:html whitespace))
              mounted    (v/hydrate-root node [views/greeting (:props root-007)])]
          (settle
            (fn []
              (is (= (:hydrated whitespace) (root/hydrated? mounted)))
              (is (= (:name (:props root-007)) (text node "#who"))
                  "and it rendered")
              (v/unmount! mounted)
              (.remove node)
              (done))))))))

(deftest fh-root-007-markup-that-merely-disagrees-is-still-a-hydration
  (testing "Per FH-ROOT-007 (browser): the boundary, and the part that could
            quietly go wrong. A container carrying markup that DISAGREES is a
            real hydration with a real mismatch — falling back there would
            throw away the adoption the server render was paid for on the
            strength of one wrong character, and would hide the disagreement
            while doing it."
    (if-not (browser?)
      (skip! "the browser job runs the fallback-boundary assertions")
      (async done
        (set! (.-IS_REACT_ACT_ENVIRONMENT js/globalThis) false)
        (let [divergent  (:divergent-container root-007)
              node       (server-node! (:html divergent))
              mismatches (atom [])
              k          (listen! mismatches)
              ;; A host callback, because with none the framework reporter
              ;; replicates React's own default and re-throws the recoverable
              ;; error at the window — correct behaviour, and an uncaught
              ;; page error the browser runner fails the whole suite on.
              mounted    (v/hydrate-root node [views/greeting (:props root-007)]
                                         {:on-recoverable-error (fn [_ _] nil)})]
          (poll-until
            #(seq @mismatches)
            (fn []
              (trace-tooling/unregister-listener! k)
              (is (= (:hydrated divergent) (root/hydrated? mounted))
                  "it took the ADOPTION path, not the fallback")
              (is (<= (:mismatches-at-least divergent) (count @mismatches))
                  (str "and the disagreement was reported rather than hidden by a "
                       "fallback. Saw: " (pr-str @mismatches)))
              (is (= (:final-text divergent) (text node "#who"))
                  "React replaced the divergent DOM with the client's truth")
              (v/unmount! mounted)
              (.remove node)
              (done))))))))
