# Kotlin Language Server

This is a fork of [amgdev9/kotlin-lsp](https://github.com/amgdev9/kotlin-lsp) which is an implementation of the [Language Server Protocol](https://microsoft.github.io/language-server-protocol/specification) for the [Kotlin](https://kotlinlang.org) programming language, leveraging the Kotlin [Analysis API](https://github.com/JetBrains/kotlin/blob/master/docs/analysis/analysis-api/analysis-api.md) to provide real time diagnostics, syntax and semantic analysis of Kotlin source files and libraries.

It's developed as part of the [Kotlin LSP Project](https://kotlinlang.org/docs/gsoc-2025.html#kotlin-language-server-lsp-hard-350-hrs) of [Google Summer of Code](https://summerofcode.withgoogle.com) 2025 under [Kotlin Foundation](https://kotlinfoundation.org) 

## RoadMap

### Current Scope for MVP
- [x] Launch Modes
    - [x] TCP Server (--tcp and --port {2090 by default})
    - [x] stdio
- [x] Project Handling 
    - [x] Project Handling (Gradle: Single and Multi Module)
- [x] Diagnostics
- [x] Go to Definition
- [x] Go to Implementations
- [x] Go to References
- [x] Hover
    - [ ] Hover Documentation (KDoc based Description)
- [ ] Code Completion
    - [x] Dot based completions
    - [ ] General completions
    - [ ] Context Aware completions
    - [ ] Import completions
- [ ] Code Actions
    - [x] Add Imports
- [ ] Rename Refactoring
- [ ] Document Symbols
- [ ] Semantic Highlighting
- [ ] Signature Help
- [ ] Inlay Hints
- [ ] Formatting

### Out of Scope ( for now )
- [ ] Project Handling 
    - [ ] Project Handling (Android: Single and Multi Module)
    - [ ] Project Handling (KMP)
    - [ ] Project Handling (Maven)
    - [ ] Project Handling (Bazel)
