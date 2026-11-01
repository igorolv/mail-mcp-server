# Third-party Notices

This project is licensed under the Apache License, Version 2.0. Its dependencies are licensed by
their respective owners under their own terms. The release jar bundles the runtime dependencies
below, and each keeps its own license.

This file is informational, taken from the dependency POMs on 2026-11-01. The dependency POMs and
upstream license files remain authoritative.

## Runtime Dependencies

| Component | License |
|---|---|
| Spring Boot, Spring Framework, Spring AI | Apache License 2.0 |
| Apache Lucene, HttpComponents, PDFBox, POI, XMLBeans, Commons, Log4j API | Apache License 2.0 |
| Jackson, Reactor, Micrometer, SnakeYAML, SnakeYAML Engine, ClassMate, ITU, JSpecify, SparseBitSet | Apache License 2.0 |
| victools JSON Schema Generator, networknt JSON Schema Validator, Swagger Annotations | Apache License 2.0 |
| MCP Java SDK, JTokkit | MIT License |
| SLF4J | MIT License |
| Reactive Streams | MIT-0 |
| ANTLR 4 runtime | BSD 3-Clause License |
| ANTLR 3 runtime, StringTemplate 4, curvesapi | BSD License |
| HdrHistogram | CC0 or BSD 2-Clause License |
| Logback | Eclipse Public License 2.0 or GNU LGPL 2.1 |
| Jakarta Mail API, Angus Mail | Eclipse Public License 2.0, GPL 2 with Classpath Exception, or Eclipse Distribution License 1.0 |
| Angus Activation | Same project family as Angus Mail; see its upstream license files |
| Jakarta Annotations API | Eclipse Public License 2.0 or GPL 2 with Classpath Exception |
| Jakarta Activation API | Eclipse Distribution License 1.0 |

## External Programs

[Pandoc](https://pandoc.org/) is required at runtime but is not bundled: you install it separately,
and it is licensed under GNU GPL 2 or later (to verify against the version you install). The server
runs it as a separate process.

## Test Dependencies

These are used only to build and test the project and are not in the release jar.

| Component | License |
|---|---|
| JUnit and Spring Boot test components | Eclipse Public License 2.0 or Apache License 2.0, depending on the component |
