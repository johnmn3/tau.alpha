tau.alpha
========================================

tau.alpha is a ClojureScript library for managing webworkers, ideally similar to Clojure's concurrency primitives.

The purpose of this library is to:

* explore usage of [SharedArrayBuffers](https://hacks.mozilla.org/2017/06/a-cartoon-intro-to-arraybuffers-and-sharedarraybuffers/) in ClojureScript.
* explore usage of implementing Clojure's concurrency primitives using [webworkers](https://developer.mozilla.org/en-US/docs/Web/API/Web_Workers_API/Using_web_workers) in ClojureScript.
* explore usage of [ServiceWorkers](https://developers.google.com/web/fundamentals/primers/service-workers/) in ClojureScript

tau.alpha is very alpha. Most of it is incomplete. The current form is just enough to demonstrate a very simple version of `future` in ClojureScript.

The current implementation uses a very naive method to manage memory over SharedArrayBuffers - it just has twenty-five 20KB slices over a SharedArrayBuffer which `future` invocations cycle over. That single SharedArrayBuffer is shared between all the webworkers, so we don't pay for data over the wire. However, we still convert ClojureScript datastructures to binary and back. Having TypedArray-backed persistent datastructures might help us out here.

Another caveat is that not all code survives serialization. Certain closures and JS things like regexes do not survive serialization. A surprising amount does work though.

# Usage

The [ex](https://github.com/johnmn3/tau.alpha/blob/master/src/tau/alpha/ex.cljs)ample namespace contains some example code showing how to use `tauon`s. Tauons are the concurrency primitives that make webworkers act more like Clojure atoms.

A [tau] is also similar to a Clojure atom, but is backed by a slice of a SharedArrayBuffer.

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
;=> 6
```

[Eclipse Public License 1.0]: http://opensource.org/licenses/eclipse-1.0.php
