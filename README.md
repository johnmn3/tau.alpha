tau.alpha
========================================

tau.alpha is a ClojureScript library for managing webworkers, ideally similar to Clojure's concurrency primitives.

The purpose of this library is to:

* explore usage of [SharedArrayBuffers](https://hacks.mozilla.org/2017/06/a-cartoon-intro-to-arraybuffers-and-sharedarraybuffers/) (SAB) in ClojureScript
* explore implementing Clojure's concurrency primitives using [WebWorkers](https://developer.mozilla.org/en-US/docs/Web/API/Web_Workers_API/Using_web_workers) in ClojureScript
* explore usage of [ServiceWorkers](https://developers.google.com/web/fundamentals/primers/service-workers/) in ClojureScript

tau.alpha is very alpha. Most of it is incomplete. Consider it experimental. In its current form, it's just enough to demonstrate a very simple version of using `future` and some other simple experiments in ClojureScript.

### Demo

To see a super simple example of what can be done performancewise, checkout this demo: https://johnmn3.github.io/tau.alpha/

### Memory Management

The current implementation uses a very naive method to manage memory on SharedArrayBuffers. `(def t (tau {:input 1 :fn #(inc)))` will produce an object backed by a SAB of `(* 2 *tau-size*)` size, with a stringified version of the datastructure `{:input 1 :fn #(+ 5 %)}` stored in the first half of the SAB. Executing `(swap! t #((:fn %) (:input %)))` will deserialize the contents, apply the function to the state, then store the stringified result back into the second half of the SAB. This is a naive method of achieving non-blocking reads and blocking, atomic writes. On webworkers, (tauons) we can optionally read with a blocking dereference. The downside is that it doubles your necessary memory use. A more advanced memory update scheme might use a structural diff on the old value and new value and use an append-only log. Using [TypedArray-backed persistent datastructures](https://dev.clojure.org/jira/browse/CLJS-1153) would make all that easier and we also wouldn't have to pay for the serialization to and from the SAB.

A previous version of tau.alpha was built _without_ SharedArrayBuffers, passing all data via webworker's `postMessage` interface. That option available again eventually, but passing data back and forth is more costly than the SAB backed method.

### future

The `future` implementation has twenty-five 20KB slices (a `tau`'s memory store) on a SharedArrayBuffer which `future` invocations cycle over. `future`s are called on a rotating pool of _n_ `tauon`s, where _n_ is `(+ 1 (num-cores))`. You can change the size and number of `tau`s in the SharedArrayBuffer in the [tau.alpha.tau namespace](https://github.com/johnmn3/tau.alpha/blob/master/src/tau/alpha/tau.cljs#L12).

### About Serialization

Note that not all code survives serialization. Certain closures and JS things like regexes do not survive serialization. A surprising amount does work though. Additionally, if you define your function _outside_ of an `on` declaration or any other serialized function context that is built upon `on` declarations - such that the compiled version of the function is already present in the compiled project's artifacts - then you can reference those function names within the serialized contexts. So it's really not that big of a deal, since ClojureScript is intended to have mostly statically compiled behavior anyway. If you get bitten by a var not being defined within the invocation of a serialized context, just define your dynamic thing outside of your particular declaration and pass in the named version of it. If you don't want to litter your runtimes in vars, you can create your tauon with a name, like `(tauon :foo1 nil nil)`, and then def things when only on that tauon by passing a tauon ID to the `on?` predicate.

### Tauons

All `tauon`s maintain a fully connected mesh of port channels and a db of all `tauon`s in the network, allowing you to invoke staged expressions down arbitrary chains of `tauon`s, simplifying the design of higher-level concurrency primitives such as the [executor pool](https://github.com/johnmn3/tau.alpha/blob/master/src/tau/alpha/exec.cljs#L17) that `future`s leverage. Expressions are invoked on `tauon`s using the `on` macro (see below).

### Tau

A `tau` is similar to a Clojure atom, but is backed by a slice of a SharedArrayBuffer. You can `swap!` on it synchronously from multiple `tauon`s (webworkers) in parallel.

NOTE: [Recent vulnerabilities](https://developers.google.com/web/updates/2018/02/meltdown-spectre) have caused browser vendors to temporarily disable SharedArrayBuffers, hidden behind a configuration flag. Change this setting in [chrome://flags/](chrome://flags/) at your own risk.

### Usage

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

You can also `yield` with the `later` macro:

```
(on t (println "result" @(later (yield (+ 1 2 3)))))

```

So that you can turn asynchronous functions into synchronous ones. Like, with the following functions defined in the example namespce:

```
(defn handle-response [event]
  (let [xhr (.-target event)]
    (if (= (.-readyState xhr) 4)
      (if (= (.-status xhr) 200)
        (let [res (.-responseText xhr)]
          (when res
            (yield res)))
        (yield (.-statusText xhr))))))

(defn xget [url]
  (later [url]
    (let [axhr (js/XMLHttpRequest.)]
      (.open axhr "GET" url)
      (set! (.-onreadystatechange axhr) handle-response)
      (.send axhr nil))))
```

Now we can use it synchronously:

```
(on t (println "res:" @(xget "http://api.open-notify.org/iss-now.json")))
;=> res: {"iss_position": {"latitude": "-36.8520", "longitude": "155.9574"}, "timestamp": 1531614629, "message": "success"}
```

### Tauon REPLing

Using [figwheel.repl](https://github.com/bhauman/figwheel-repl) we can also open new repls from a `tauon` and switch between them at will:

```
(on t (connect "ws://localhost:9550/figwheel-connect?fwbuild=dev"))
```

You should see a new session created in the browser console with log statements like: ` [Figwheel REPL] Session Name: Vashti`

Then you can view available connections with `conns` and switch to them with `focus` like so:

```
tau.alpha.ex=> (conns)

Will Eval On:  Vashti
Session Name     Age URL
Vashti           19m /figwheel-connect
Stephan          20m /figwheel-connect
nil

tau.alpha.ex=> js/document
#object[ReferenceError ReferenceError: document is not defined]
	 (<NO_SOURCE_FILE>)

tau.alpha.ex=> (focus "Stephan")
"Focused On: Stephan"

tau.alpha.ex=> js/document
#object[HTMLDocument [object HTMLDocument]]

tau.alpha.ex=> (focus "Vashti")
"Focused On: Vashti"

tau.alpha.ex=> (let [res (xget "http://api.open-notify.org/iss-now.json")
          #_=>       iss (-> @res js/JSON.parse (js->clj :keywordize-keys true))
          #_=>       loc (:iss_position iss)]
          #_=>   (println "International Space Station, Current Location:" loc))
International Space Station, Current Location: {:longitude -175.7632, :latitude -10.2605}
nil
```

[Eclipse Public License 1.0]: http://opensource.org/licenses/eclipse-1.0.php
