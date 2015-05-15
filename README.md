# aero

(<b>a</b>ero is <b>e</b>dn <b>r</b>eally, <b>o</b>k?)

Light and fluffy configuration.

![Light and fluffy configuration](aero.jpg)

## Installation

Add the following dependency to your `project.clj` file :-

```clojure
[aero "0.1.0"]
```

## Status

Please note that being a 0.x.y version indicates the provisional status
of this library, and that features are subject to change.

## Getting started

Create a file called `config.edn` containing the following

```clojure
{:greeting "World!"}
```

In your code, read the configuration like this :-

```clojure
(require '[aero.core :refer (read-config)])
(read-config "config.edn")
```

## Design goals

### Explicit and intentional

Configuration should be explicit, obvious and not be clever. It should be easy to understand what the config is, and where it is declared.

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

While it can be very flexible to have 'clever' configuration 'programs', it is
[unsafe](http://www.learningclojure.com/2013/02/clojures-reader-is-unsafe.html),
can lead to exploits and compromise
[security](http://boingboing.net/2011/12/28/linguistics-turing-completene.html). It
is best avoided. Always use data for configuration.

### Use edn

Fortunately, there already exists

AERO

## References

Aero is built on Clojure's [edn](https://github.com/edn-format/edn).

Aero is influenced by [nomad](https://github.com/james-henderson/nomad), but explicitly avoids environmental factors, such as hostname.

## Copyright & License

The MIT License (MIT)

Copyright © 2015 JUXT LTD.

Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated documentation files (the "Software"), to deal in the Software without restriction, including without limitation the rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to permit persons to whom the Software is furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
