# The re-frame2 Guide


This is the human-facing user guide for the ClojureScript reference implementation.

## It is a virtual machine

An application that you write using re-frame is a virtual machine.

The `handlers` that you register are the instruction set. Events — coming from user actions, websocket frames, web workers, timers, whatever — are one instruction which executes on the machine. The series of events which occur over an app's lifetime are collectively the program.

## It is functional

re-frame2 embraces the principles of functional programming. Pure functions. Immutable data. Isolated, controlled effects.

For every event, the re-frame2 runtime executes the same six-step computational pipeline. Data flows through this pipeline being transformed by pure functions. At one point, "Effects", described by a data DSL, are actioned by the runtime. State is central and views sit at the end of the pipeline declaratively computing the view which represents the current state of the machine. Views are not causal, they are derivative.

One pass through this pipeline is called an `epoch`. And, you will soon see, one `epoch` can be thought of as a 6-domino cascade.
<p align="center"><img src="../images/guide/Dominoes.jpg" alt="A line of physical dominoes mid-fall — the visual metaphor for the six-domino cascade re-frame uses." width="500"></p>

## It is data-oriented

Data flows through the computational pipeline. It is a Clojure idiom to use open maps for data, and to have highly sophisticated schemas for it, as necessary. And often, this data is a micro DSL which encodes instructions for the next step in the 6-domino cascade.

Within the Clojure community the importance of data is expressed by the aphorism `data > functions > macros`. And if you don't know what a macro is, that doesn't matter, you get the idea.

## This guide

This guide is opinionated. It will tell you, with confidence, that a single source of truth is a good idea, that constrained execution models are easier to reason about than Turing-complete ones, and it may contain the occasional rant.

All code given is ClojureScript code. If you are not a confident reader of ClojureScript then you may want to spend 30 mins reading [the CLJS reading guide](../cljs/index.md) we have prepared. You won't be able to write Clojure but you'll be able to largely read it.
