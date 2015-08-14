# aero

(<b>a</b>ero is <b>e</b>dn <b>r</b>eally, <b>o</b>k?)

Light and fluffy configuration.

![Light and fluffy configuration](aero.jpg)

## Installation

Add the following dependency to your `project.clj` file

```clojure
[aero "0.1.2"]
```

## Status

Please note that being a 0.x.y version indicates the provisional status
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

## Design goals

### Explicit and intentional

Configuration should be explicit, obvious, not clever. It should be easy to understand what the config is, and where it is declared.

Determining config while diagnosing a support issue should not be a
[wild goose chase](http://en.wiktionary.org/wiki/wild-goose_chase).

### Avoid duplication ...

Config files are often duplicated on a per-environment basis, attracting all the problems associated with duplication.

### ... but allow for difference

When looking at a config file, a reader will usually ask: "Does the value differ from the default, and if so how?". It's clearly better to answer that question in-place.

### Allow config to be stored in the repository ...

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

We suggest using environment variables sparingly, the way Unix intends,
and not [go mad](http://12factor.net/config). After all, we want to keep configuration explicit and intentional.

Also, see these arguments [against](https://gist.github.com/telent/9742059).

### Use edn

Fortunately, most of the tech to read configuration in a safe, secure
and extensible way already exists in the Clojure core library (EDN).

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
