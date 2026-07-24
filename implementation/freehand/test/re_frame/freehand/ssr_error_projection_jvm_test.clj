(ns re-frame.freehand.ssr-error-projection-jvm-test
  "A Freehand VIEW that throws during a server render projects to the locked
  public-error shape and carries NO private data (Spec 011 §Server error
  projection, §View-time exceptions).

  `v/render-static` is the Freehand server render path (Spec 011 §The server
  render on the Freehand paved path): it compiles the literal root form to the
  versioned JVM structural tree and folds it to HTML. A compiled view body runs
  inside that render, so a view-time exception is a real, reachable failure —
  and the throwable it raises carries whatever the view was holding (props, a
  db-derived value, its own ex-data, a host stack).

  That throwable is INTERNAL detail. The HTTP response carries a PUBLIC
  projection — a sanitised, client-safe shape a crawler or an unauthenticated
  user may see. `re-frame.ssr/project-render-exception!` is the seam between the
  two, and this suite pins what crosses it: exactly the four locked
  `:rf/public-error` keys, with the view's private payload nowhere in them.

  ## The independence wall is not crossed here

  `re-frame.freehand` takes NO require on `re-frame.ssr` — the render reaches
  the serialiser by LATE resolution at render time, and the projector is the
  HOST's call, made after the render throw escapes. The suite lives in the
  Freehand artefact with `day8/re-frame2-ssr` as a TEST-ONLY dep (the same
  arrangement the `v/render-static` suite uses) because proving the end-to-end
  path needs both artefacts on one classpath.

  ## How the absence is asserted

  The private payload is a SENTINEL MINTED AT RUN TIME. This file never spells
  it out — writing the secret into the test source would root that string in the
  artefact, so an `includes?` probe over it would prove nothing about the
  projection. `private-datum` below is generated per run, threaded through the
  view's props into the exception, and the assertions only ever compare against
  the var.

  Each absence assertion is paired with a positive control, so it cannot pass
  vacuously: the throwable is asserted to CARRY the sentinel before the
  projection is asserted not to, and the `:dev-error-detail?` arm shows the
  projector genuinely had the private detail in hand and withheld it by
  default."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [re-frame.frame :as frame]
            [re-frame.freehand :as v]
            [re-frame.ssr :as ssr]))

;; ---------------------------------------------------------------------------
;; The sentinel — minted per run, never spelled in this file.
;; ---------------------------------------------------------------------------

(def ^:private private-datum
  "The view's private payload. Generated at load time so its VALUE exists
  nowhere in this artefact's source: an assertion that the projection does not
  contain it is then a statement about the projection, not about a literal the
  test itself planted. The `secret-` prefix is a debugging aid only — the
  entropy is what makes the probe meaningful."
  (str "secret-" (java.util.UUID/randomUUID)))

(defn- fail-with!
  "Raise a view-time exception whose MESSAGE and EX-DATA both carry `datum` —
  the shape a real leak takes (a view interpolates something it was handed into
  the failure it raises). Called from the view body, so the throw happens inside
  the render rather than around it."
  [datum]
  (throw (ex-info (str "view-time failure while rendering " datum)
                  {:private datum})))

;; A compiled view that throws mid-render. It reads no state and commits no
;; handler, so it clears render-static's no-silent-elision proof (Spec 004C §3)
;; and reaches the render — the failure is a VIEW-TIME one, not a build error.
(v/defview leaky-card
  {:compiled true}
  [{:keys [datum]}]
  [:div.card [:h2 (fail-with! datum)]])

;; ---------------------------------------------------------------------------
;; The render failure, captured once through the REAL v/render-static path.
;; ---------------------------------------------------------------------------

(def ^:private view-failure
  "The Throwable `v/render-static` raises when the mounted view throws. Captured
  at ns load — the same moment the macro expands — so this is the shipped server
  render path failing, not a synthesised stand-in."
  (try (v/render-static [leaky-card {:datum private-datum}])
       nil
       (catch Throwable t t)))

(def ^:private locked-public-error
  "The `:rf/public-error` shape Spec 011 §Default projector locks for an
  unenumerated 500-class category. `:rf.error/ssr-render-failed` — the category
  `project-render-exception!` synthesises — is unenumerated by design, so this
  is the ATTRIBUTED result, not merely evidence that something was raised."
  {:status     500
   :code       :internal-error
   :message    "Something went wrong"
   :retryable? false})

(defn- server-frame
  "A `:platform :server` frame carrying `ssr-config` as its `:ssr` metadata."
  [ssr-config]
  (frame/make-anon-frame-record!
    (cond-> {:platform :server}
      (some? ssr-config) (assoc :ssr ssr-config))))

;; ---------------------------------------------------------------------------
;; The headline — the projection is the locked shape, and the private payload
;; is not in it.
;; ---------------------------------------------------------------------------

(deftest freehand-view-failure-projects-with-no-private-data
  (testing "the render really failed, and the private payload really was in
            flight — without this the absence assertions below would be vacuous"
    (is (some? view-failure)
        "v/render-static propagates a view-time throw out of the render")
    (is (str/includes? (ex-message view-failure) private-datum)
        "the throwable's MESSAGE carries the view's private payload")
    (is (= private-datum (:private (ex-data view-failure)))
        "the throwable's EX-DATA carries it too"))

  (let [f         (server-frame {:public-error-id :rf.ssr/default-error-projector})
        projected (ssr/project-render-exception! f view-failure)]

    (testing "the ATTRIBUTED result — exactly the four locked :rf/public-error
              keys with the locked generic-500 contents. Map equality is the
              field-set assertion: no :details, no app-db, no event history, no
              props, no ex-data, no host stack can ride an equal map"
      (is (= locked-public-error projected)))

    (testing "no private data crosses the boundary"
      (is (not (str/includes? (pr-str projected) private-datum))
          "the view's private payload appears nowhere in the projection"))

    (testing "the projection is stamped onto the wire — the response the host
              serialises carries the projected status"
      (is (= 500 (:status (ssr/peek-response f)))))))

;; ---------------------------------------------------------------------------
;; Safe by default — a server frame with no :ssr config at all.
;; ---------------------------------------------------------------------------

(deftest freehand-view-failure-projection-is-safe-by-default
  (testing "a server frame that declares NO :ssr config projects through the
            built-in default projector and still sheds the private payload —
            sanitisation is the default posture, not an opt-in (Spec 011 §Dev vs
            prod default behaviour)"
    (let [f         (server-frame nil)
          projected (ssr/project-render-exception! f view-failure)]
      (is (= locked-public-error projected))
      (is (not (str/includes? (pr-str projected) private-datum))
          "an unconfigured server frame leaks nothing either"))))

;; ---------------------------------------------------------------------------
;; The positive control — the detail exists, and :dev-error-detail? is the ONLY
;; thing that lets it through.
;; ---------------------------------------------------------------------------

(deftest freehand-view-failure-detail-is-a-dev-opt-in
  (testing "under the explicit `:dev-error-detail? true` opt-in the projection
            carries `:details` — proving the projector HELD the view's private
            payload and withheld it above by policy, not because the payload had
            already been lost upstream"
    (let [f         (server-frame {:public-error-id   :rf.ssr/default-error-projector
                                   :dev-error-detail? true})
          projected (ssr/project-render-exception! f view-failure)]
      (is (= locked-public-error (dissoc projected :details))
          "the four locked keys are unchanged — :details is additive")
      (is (str/includes? (pr-str (:details projected)) private-datum)
          "the dev detail reaches the private payload the prod projection dropped"))))
