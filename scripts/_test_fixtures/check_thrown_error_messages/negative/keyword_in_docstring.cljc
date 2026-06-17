(ns fixture.negative.keyword-in-docstring
  "NEGATIVE fixture: a docstring that NAMES :rf.error/… keywords and describes
  the thrown-error contract in prose, without reproducing an `(ex-info …)`
  message-position bad byte sequence. The gate anchors on `(ex-info` + the bad
  first argument, so prose naming a keyword (a :rf.error/… mention, or talk of
  the OLD message shape) never fires. MUST stay green."
  (:require [re-frame.error :as error]))

(defn boom
  "Throw the canonical :rf.error/no-frame-context error. The retired form put
  the discriminator keyword (e.g. :rf.error/no-frame-context) into the message
  position; this routes through the central builder so the message is a human
  sentence carrying the [:rf.error/<id>] token instead. See Spec 009 §The
  thrown-error shape."
  [reason]
  (error/throw-error! :rf.error/no-frame-context 'rf/x reason))
