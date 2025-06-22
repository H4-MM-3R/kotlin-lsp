# Kotlin Language Server

This is a fork of [amgdev9/kotlin-lsp](https://github.com/amgdev9/kotlin-lsp) which is an implementation of the [Language Server Protocol](https://microsoft.github.io/language-server-protocol/specification) for the [Kotlin](https://kotlinlang.org) programming language, leveraging the Kotlin [Analysis API](https://github.com/JetBrains/kotlin/blob/master/docs/analysis/analysis-api/analysis-api.md) to provide real time diagnostics, syntax and semantic analysis of Kotlin source files and libraries.

## RoadMap

- [x] Project Handling (Gradle: Single and Multi Module)
    - [ ] Project Handling (Android)
    - [ ] Project Handling (KMP)
    - [ ] Project Handling (Maven)
    - [ ] Project Handling (Bazel)
- [ ] Semantic Highlighting
- [x] Diagnostics
- [x] Go To Definition
- [ ] Code Completion
    - [x] Dot based completions
    - [ ] General completions
    - [ ] Context Aware completions
    - [ ] Import completions
- [ ] Find References
- [x] Hover
    - [ ] Hover Documentation (KDoc based Description)
- [x] Go to Implementations
- [ ] Code Actions
    - [x] Add Imports
- [ ] Rename Refactoring
- [ ] Document Symbols
- [ ] Signature Help
- [ ] Formatting
- [ ] Inlay Hints
