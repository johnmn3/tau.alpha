tau.alpha
========================================

tau.alpha is a ClojureScript library for managing webworkers, ideally similar to Clojure's concurrency primitives.

The purpose of this library is to:

* explore usage of [SharedArrayBuffers](https://hacks.mozilla.org/2017/06/a-cartoon-intro-to-arraybuffers-and-sharedarraybuffers/) in ClojureScript
* explore implementing Clojure's concurrency primitives using [webworkers](https://developer.mozilla.org/en-US/docs/Web/API/Web_Workers_API/Using_web_workers) in ClojureScript
* explore usage of [ServiceWorkers](https://developers.google.com/web/fundamentals/primers/service-workers/) in ClojureScript

tau.alpha is very alpha. Most of it is incomplete. In its current form, it's just enough to demonstrate a very simple version of using `future` in ClojureScript.

The current implementation also uses a very naive method to manage memory on SharedArrayBuffers - it just has twenty-five 20KB slices (a `tau`'s memory store) on a SharedArrayBuffer which `future` invocations cycle over. `future`s are called on a rotating pool of _n_ `tauon`s, where _n_ is `(+ 1 (num-cores))`. You can change the size and number of `tau`s in the SharedArrayBuffer in the [tau.alpha.tau namespace](https://github.com/johnmn3/tau.alpha/blob/master/src/tau/alpha/tau.cljs#L12).

The SharedArrayBuffer in which all the `tau`s live is shared between all the `tauon`s, so we don't have to wait for messages to pass back and forth. However, we still convert ClojureScript datastructures to binary and back. Having [TypedArray-backed persistent datastructures](https://dev.clojure.org/jira/browse/CLJS-1153) might help us out here.

Another caveat is that not all code survives serialization. Certain closures and JS things like regexes do not survive serialization. A surprising amount does work though.

# Usage

The [example](https://github.com/johnmn3/tau.alpha/blob/master/src/tau/alpha/ex.cljs) namespace contains some example code showing how to use `tauon`s. `tauon`s wrap webworkers in an interface that allows you to pass (some) expressions to webworkers for invocation. All `tauon`s maintain a fully connected mesh of port channels and a db of all `tauon`s in the network, allowing you to invoke staged expressions down arbitrary chains of `tauon`s, simplifying the design of higher-level concurrency primitives. Expressions are invoked on `tauon`s using the `on` macro (see below).

A `tau` is similar to a Clojure atom, but is backed by a slice of a SharedArrayBuffer. You can `swap!` on it synchronously from multiple `tauon`s (webworkers) in parallel.

The `future` macro leverages both `tau`s and `tauon`s, sending functions to seperate `tauon`s (webworkers) while commiting updates to the `tau`s (SharedArrayBuffers).

To try it out:

```
clj -A:fig:dev
```

Then change your namespace to the example namespace:

```
(ns tau.alpha.ex)
```

Now, you might have to reload your namespaces first, but next do something like:

```
(def t (tauon))

(on t (println "result:" @(future (+ 1 2 3))))
;=> result: 6
```

[Eclipse Public License 1.0]: http://opensource.org/licenses/eclipse-1.0.php
