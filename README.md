Visit [the official web site](https://line.github.io/centraldogma/) for more information.

# Central Dogma

[![CI](https://github.com/line/centraldogma/actions/workflows/actions_build.yml/badge.svg?branch=main&event=push)](https://github.com/line/centraldogma/actions/workflows/actions_build.yml)
[![codecov.io](https://codecov.io/github/line/centraldogma/coverage.svg?branch=main)](https://codecov.io/github/line/centraldogma?branch=main)
[![Latest Release Version](https://img.shields.io/github/v/release/line/centraldogma)](https://github.com/line/centraldogma/releases/latest)
[![Discord Server](https://img.shields.io/badge/join-discord-5865F2?logo=discord&logoColor=white)](https://armeria.dev/s/discord)

_Central Dogma_ is an open-source, highly-available and version-controlled service configuration repository based on Git, ZooKeeper and HTTP/2.

It is open-sourced and licensed under [Apache License 2.0](https://tldrlegal.com/license/apache-license-2.0-(apache-2.0)) by [LINE Corporation](http://linecorp.com/en/), who uses it in production.

## How to build

We use [Gradle](https://gradle.org/) to build Central Dogma. The following command will compile Central Dogma and generate JARs, tarball and web site:

```bash
$ ./gradlew build
```

## How to ask a question

Just [create a new issue](https://github.com/line/centraldogma/issues/new) to ask a question, and browse [the list of previously answered questions](https://github.com/line/centraldogma/issues?q=label%3Aquestion-answered).

We also have [a Discord Server](https://armeria.dev/s/discord).

## How to contribute

See [`CONTRIBUTING.md`](CONTRIBUTING.md).
