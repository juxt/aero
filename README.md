# Aero

[![Join the chat at https://gitter.im/juxt/aero](https://badges.gitter.im/juxt/aero.svg)](https://gitter.im/juxt/aero?utm_source=badge&utm_medium=badge&utm_campaign=pr-badge&utm_content=badge)

(<b>a</b>ero is <b>e</b>dn <b>r</b>eally, <b>o</b>k?)

A small library for explicit, intentful configuration.

![Light and fluffy configuration](aero.jpg)

## Installation

Add the following dependency to your `project.clj` file

[![Clojars Project](http://clojars.org/aero/latest-version.svg)](http://clojars.org/aero)

[![Build Status](https://circleci.com/gh/juxt/aero.svg?style=svg)](https://circleci.com/gh/juxt/aero)

## Status

Please note that being a beta version indicates the provisional status
of this library, and that features are subject to change.

## Getting started

Create a file called `config.edn` containing the following

```clojure
{:greeting "World!"}
```

In your code, read the configuration like this

```clojure
(require '[aero.core :refer (read-config)])
(read-config "config.edn")
```

or to read from the classpath, like this

```clojure
(read-config (clojure.java.io/resource "config.edn"))
```

This isn't actually any different since `clojure.java.io/resource` is returning
a string URL to where the file is but it's helpful to point out.

## Design goals

### Explicit and intentional

Configuration should be explicit, intentful, obvious, but not clever. It
should be easy to understand what the config is, and where it is
declared.

Determining config in stressful situations, for example, while
diagnosing the cause of a production issue, should not be a
[wild goose chase](http://en.wiktionary.org/wiki/wild-goose_chase).

### Avoid duplication ...

Config files are often duplicated on a per-environment basis, attracting
all the problems associated with duplication.

### ... but allow for difference

When looking at a config file, a reader will usually ask: "Does the value differ from the default, and if so how?". It's clearly better to answer that question in-place.

### Allow config to be stored in the source code repository ...

When config is left out of source code control it festers and diverges from the code base. Better to keep a single config file in source code control.

### ... while hiding passwords

While it is good to keep config in source code control, it is important to ensure passwords and other sensitive information remain hidden.

### Config should be data

While it can be very flexible to have 'clever' configuration 'programs', it can be [unsafe](http://www.learningclojure.com/2013/02/clojures-reader-is-unsafe.html), lead to exploits and compromise security. Configuration is a key input to a program. Always use data for configuration and [avoid turing-complete](http://langsec.org/occupy) languages!

### Use environment variables sparingly

We suggest using environment variables judiciously and sparingly, the way Unix intends, and not [go mad](http://12factor.net/config). After all, we want to keep configuration explicit and intentional.

Also, see these arguments [against](https://gist.github.com/telent/9742059).

### Use edn

Fortunately for Clojure developers like us, most of the tech to read configuration in a safe, secure and extensible way already exists in the Clojure core library (EDN).

## Tag literals

Aero provides a small library of tag literals.

### env

Use `#env` to reference an environment variable.

```clojure
{:database-uri #env DATABASE_URI}
```

It is considered bad practice to use environment variables for passwords and other confidential information. This is because it is very easy for anyone to read a process's environment (e.g. via `ps -ef`). Environment variables are also commonly dumped out in a debugging sessions. Instead you should use `#include` - see [here](#hide-passwords-in-local-private-files).

### envf

Use `#envf` to insert environment variables into a formatted string.

```clojure
{:database #envf ["protocol://%s:%s" DATABASE_HOST DATABASE_NAME]}
```

### or

Use `#or` when you want to provide a list of possibilities, perhaps with a default and the end.

```clojure
{:port #or [#env PORT 8080]}
```

### join

`#join` is used as a string builder, useful in a variety of situations such as building up connection strings.

``` clojure
{:url #join ["jdbc:postgresql://psq-prod/prod?user="
             #env PROD_USER
             "&password="
             #env PROD_PASSWD]}
```

### profile

Use profile as a kind of reader conditional.

`#profile` expects a map, from which it extracts the entry corresponding to the __profile__.

```clojure
{:webserver
  {:port #profile {:default 8000
                   :dev 8001
                   :test 8002}}}
```

You can specify the value of __profile__ when you read the config.

```clojure
(read-config "config.edn" {:profile :dev})
```

which will return

```clojure
{:webserver
  {:port 8001}}
```

(`#profile` replaces the now deprecated `#cond`, found in previous versions of Aero)

### hostname

Use when config has to differ from host to host, using the hostname. You
can specify multiple hostnames in a set.

```clojure
{:webserver
  {:port #hostname {"stone" 8080
                    #{"emerald" "diamond"} 8081
                    :default 8082}}}
```

### long, double, keyword, boolean

Use to parse a `String` value into a `Long`, `Double`, keyword or boolean.

``` clojure
{:debug #boolean #or [#env DEBUG "true"]
 :webserver
  {:port #long #or [#env PORT 8080]
   :factor #double #env FACTOR
   :mode #keyword #env MODE}}
```

### user

`#user` is like `#hostname`, but switches on the user.

### include

Use to include another config file. This allows you to split your config files
to prevent them from getting too large.

``` clojure
{:webserver #include "webserver.edn"
 :analytics #include "analytics.edn"}
```

NOTE: By default `#include` will attempt to resolve the file to be included *relative* to the
config file it's being included from. (this won't work for jars)

You can provide your own custom resolver to replace the default behaviour or use one that
aero provides (`resource-resolver`, `root-resolver`). For example

```clojure
(require '[aero.core :refer (read-config resource-resolver)])
(read-config "config.edn" {:resolver resource-resolver})
```

You can also provide a map as a resolver. For example

```clojure
(read-config "config.edn" {:resolver {"webserver.edn" "resources/webserver/config.edn"}})
```

### merge

Merge multiple maps together

```clojure
#merge [{:foo :bar} {:foo :zip}]
```

### ref

To avoid duplication you can refer to other parts of your configuration file using the `#ref` tag.

The `#ref` value should be a vector resolveable by `get-in`. Take the following config map for example:

```clojure
{:db-connection "datomic:dynamo://dynamodb"
 :webserver
  {:db #ref [:db-connection]}
 :analytics
  {:db #ref [:db-connection]}}
```

Both `:analytics` and `:webserver` will have their `:db` keys resolved
to `"datomic:dynamo://dynamodb"`

References are recursive. They can be used in `#include` files.

### Define your own

Aero supports user-defined tag literals. Just extend the `reader` multimethod.

```clojure
(defmethod reader 'mytag
 [{:keys [profile] :as opts} tag value]
  (if (= value :favorite)
     :chocolate
     :vanilla))
```

## Deferreds

Sometimes you may not want your tag literal to be run during the EDN load, but only after the tree has fully loaded.

For example, you may have a :dev profile and a :prod profile. The :prod profile may require accessing an enterprise configuration store or key management service. You don't want that processing as part of the load, because it will also happen for :dev profiles.

In this case, you can return your tag literal's computation as a deferred value. For example:

```
(defmethod aero.core/reader 'aws-kms-decrypt
  [_ tag value]
  (aero/deferred (kms-decrypt-str value)))
```

## Recommended usage patterns, tips and advice

### Hide passwords in local private files

Passwords and other confidential information should not be stored in version control, nor be specified in environment variables. One alternative option is to create a private file in the HOME directory that contains only the information that must be kept outside version control (it is good advice that everything else be subject to configuration management via version control).

Here is how this can be achieved:

```clojure
{:secrets #include #join [#env HOME "/.secrets.edn"]

 :aws-secret-access-key
  #profile {:test #ref [:secrets :aws-test-key]
            :prod #ref [:secrets :aws-prod-key]}}
```

### Use functions to wrap access to your configuration.

Here's some good advice on using Aero in your own programs.

Define a dedicated namespace for config that reads the config and provides functions to access it.

```clojure
(ns myproj.config
  (:require [aero.core :as aero]))

(defn config [profile]
  (aero/read-config "dev/config.edn" {:profile profile}))

(defn webserver-port [config]
  (get-in config [:webserver :port]))
```

This way, you build a simple layer of indirection to insulate the parts of your program that access configuration from the evolving structure of the configuration file. If your configuration structure changes, you only have to change the wrappers, rather than locate and update all the places in your code where configuration is accessed.

Your program should call the `config` function, usually with an argument specifying the configuration profile. It then returned value passes the returned value through functions or via lexical scope (possibly components).

### Using Aero with Plumatic schema

Aero has frictionless integration with [Plumatic Schema](https://github.com/plumatic/schema). If you wish, specify your configuration schemas and run `check` or `validate` against the data returned from `read-config`.

### Using Aero with components

If you are using Stuart Sierra's
[component](https://github.com/stuartsierra/component) library, here's how you might integrate Aero.

```clojure
(ns myproj.server
  (:require [myproj.config :as config]))

(defrecord MyServer [config]
  Lifecycle
  (start [component]
    (assoc component :server (start-server :port (config/webserver-port config))))
  (stop [component]
    (when-let [server (:server component)] (stop-server server))))

(defn new-server [config]
  (->MyServer config))
```

```clojure
(ns myproj.system
  [com.stuartsierra.component :as component]
  [myproj.server :refer [new-server]])

(defn new-production-system []
  (let [config (config/config :prod)]
    (system-using
      (component/system-map :server (new-server config))
      {})))
```

However, another useful pattern you might consider is to keep your system map and configuration map aligned.

For example, imagine you have a config file:

```clojure
{:listener {:port 8080}
 :database {:uri "datomic:mem://myapp/dev"}}
```

Here we create a system as normal but with the key difference that we configure the system map after we have created using `merge-with merge`. This avoids all the boilerplate required in passing config around the various component constructors.

```clojure
(defrecord Listener [database port]
  Lifecycle …)

(defn new-listener []
  (using (map->Listener {}) [:database])

(defrecord Database [uri]
  Lifecycle …)

(defn new-database []
  (map->Database {}))

(defn new-system-map
  "Create a configuration-free system"
  []
  (system-map
   :listener (new-listener)
   :database (new-database)))

(defn configure [system profile]
  (let [config (aero/read-config "config.edn" {:profile profile})]
    (merge-with merge system config)))

(defn new-dependency-map [] {})

(defn new-system
  "Create the production system"
  [profile]
  (-> (new-system-map)
      (configure profile)
      (system-using (new-dependency-map))))
```

Also, if you follow the pattern described [here](https://juxt.pro/blog/posts/component-meet-schema.html) you can also ensure accurate configuration is given to each component without having to maintain explicit schemas. This way, you only verify the config that you are actually using.

### Feature toggles

Aero is a great way to implement [feature toggles](http://martinfowler.com/articles/feature-toggles.html).

### Use a single configuration file

If at all possible, try to avoid having lots of configuration files and stick with a single file. That way, you're encouraged to keep configuration down to a minimum. Having a single file is also useful because it can be more easily edited, published, emailed, [watched](https://github.com/juxt/dirwatch) for changes. It is generally better to surface complexity than hide it away.

## References

Aero is built on Clojure's [edn](https://github.com/edn-format/edn).

Aero is influenced by [nomad](https://github.com/james-henderson/nomad), but purposely avoids instance, environment and private config.

## Acknowledgments

Thanks to the following people for inspiration, contributions, feedback and suggestions.

* Gardner Vickers

## Copyright & License

The MIT License (MIT)

Copyright © 2015 JUXT LTD.

Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated documentation files (the "Software"), to deal in the Software without restriction, including without limitation the rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to permit persons to whom the Software is furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
