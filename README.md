# aero

(<b>a</b>ero is <b>e</b>dn <b>r</b>eally, <b>o</b>k?)

A small library for explicit, intentful configuration.

![Light and fluffy configuration](aero.jpg)

## Installation

Add the following dependency to your `project.clj` file

```clojure
[aero "0.1.3"]
```

## Status

Please note that being a 0.x.y version indicates the provisional status
of this library, and that features are subject to change.

[![Build Status](https://travis-ci.org/juxt/aero.png)](https://travis-ci.org/juxt/aero)

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

While it can be very flexible to have 'clever' configuration 'programs',
it can be
[unsafe](http://www.learningclojure.com/2013/02/clojures-reader-is-unsafe.html),
lead to exploits and compromise security. Configuration is a key input
to a program. Always use data for configuration and
[avoid turing-complete](http://langsec.org/occupy) languages!

### Use environment variables sparingly

We suggest using environment variables judiciously and sparingly, the
way Unix intends, and not [go mad](http://12factor.net/config). After
all, we want to keep configuration explicit and intentional.

Also, see these arguments
[against](https://gist.github.com/telent/9742059).

### Use edn

Fortunately for Clojure developers like us, most of the tech to read
configuration in a safe, secure and extensible way already exists in the
Clojure core library (EDN).

## Tag literals

Aero provides a small library of tag literals.

### env

Use `#env` to reference an environment variable.

```clojure
{:password #env DATABASE_PASSWORD}
```

When you need to hide a configuration detail, such as a password, use
this feature. If you're using AWS Beanstalk, you can set environment
variables in the console, which keeps them safe from unauthorised
access.

Use `#env` with a vector to provide a default if the environment variable doesn't exist.

```clojure
{:password #env [PORT 8080]}
```

### cond

Use cond as a kind of reader conditional.

`#cond` expects a map, from which is extracts the entry corresponding to the of __profile__.

```clojure
{:webserver
  {:port #cond {:default 8000
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

### hostname

Use when config has to differ from host to host, using the hostname. You
can specify multiple hostnames in a set.

```clojure
{:webserver
  {:port #hostname {"stone" 8080
                    #{"emerald" "diamond"} 8081
                    :default 8082}}}
```

### Define your own

Aero supports user-defined tag literals. Just extend the `reader` multimethod.

```
(defmethod reader `mytag
 [{:keys [profile] :as opts} tag value]
  (if (= value :favorite)
     :chocolate
     :vanilla))
```

## Support for Prismatic's schema

A config can be given a :schema entry in the options argument, to specify a schema.


```clojure
(ns myproj.config
  (:require [schema.core :as s]))

(s/defschema UserPort (s/both s/Int (s/pred #(<= 1024 % 65535))))

(s/defschema ConfigSchema
  {:webserver {:port UserPort}})

(read-config
   "config.edn"
   {:profile profile
    :schema ConfigSchema})
```

## Good advice: Use functions to wrap access to your configuration.

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

This way, you build a simple layer of indirection to insulate the parts
of your program that access configuration from the evolving structure of
the configuration file. If your configuration structure changes, you
only have to change the wrappers, rather than locate and update all the
places in your code where configuration is accessed.

Your program should call the `config` function, usually with an argument
specifying the configuration profile. It then returned value passes the
returned value through functions or via lexical scope (possibly
components).

## How to use Aero with components

If you are using Stuart Sierra's
[component[(https://github.com/stuartsierra/component) library, here's how you might integrate Aero.

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
  (->MyServer))
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

## References

Aero is built on Clojure's [edn](https://github.com/edn-format/edn).

Aero is influenced by [nomad](https://github.com/james-henderson/nomad),
but purposely avoids instance, environment and private config.

## Copyright & License

The MIT License (MIT)

Copyright © 2015 JUXT LTD.

Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated documentation files (the "Software"), to deal in the Software without restriction, including without limitation the rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to permit persons to whom the Software is furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
