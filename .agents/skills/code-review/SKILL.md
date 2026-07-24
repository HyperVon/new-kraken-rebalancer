---
name: code-review
description: Perform a thorough, structured code review covering Code Quality, Bug Detection, Security Analysis, Performance, and Best Practices.
---

# Code Review Skill

Use this skill when analyzing source code, pull requests, or code snippets to provide detailed, actionable, and structured feedback.

## Review Categories

When performing a code review, analyze the target code across the following 5 dimensions:

### 1. Code Quality

- **Code Smells & Anti-Patterns**: Identify long methods, complex conditional logic, duplicate code, tight coupling, and violation of SRP (Single Responsibility Principle).
- **Refactoring Opportunities**: Suggest structural improvements, cleaner abstractions, DRY extractions, and improved readability.
- **Naming & Organization**: Check for clear, self-documenting function/variable names, proper package structure, and separation of concerns.
- **No Fully Qualified Names (FQNs)**: Ensure explicit `import` statements are used at the top of the file rather than inline FQNs, unless resolving a strict class name collision.

### 2. Bug Detection

- **Logic & Control Flow**: Identify edge cases, off-by-one errors, infinite loops, or improper state mutations.
- **Null & Undefined Safety**: Check for potential `NullPointerExceptions`, unhandled `null` or `undefined` returns, unsafe platform calls, or missing default values.
- **Concurrency & Race Conditions**: Check for non-atomic state updates, shared mutable state without proper locking/mutexes, or improper coroutine/thread context switches.

### 3. Security Analysis

- **Vulnerabilities**: Inspect for injection risks (SQL, OS command, unsafe HTML rendering/XSS), secret leaks, or insecure deserialization.
- **Input Validation**: Check that input parameters, API payloads, and query parameters are strictly validated and sanitized before processing.
- **Authentication & Authorization**: Verify proper access controls, safe handling of credentials/tokens, and rate-limiting enforcement.

### 4. Performance

- **Bottlenecks & Complexity**: Identify inefficient algorithms, redundant database queries, or expensive operations inside loops.
- **Memory & Resource Management**: Check for unclosed resources (streams, database connections, DOM event listeners, coroutine job leaks), and unnecessary object allocations.
- **Async & Non-blocking I/O**: Ensure blocking I/O calls are offloaded from main threads or event loops.

### 5. Best Practices & Project Conventions

- **Financial Math & Precision**:
  - Always use `BigDecimal` (never `Double` or `Float`) for financial values.
  - Scale: 8 decimal places for crypto quantities, 2 decimal places for USD valuations.
  - Assertions: Always compare `BigDecimal` values using `compareTo() == 0` or Kotest `shouldBeEqualComparingTo` (never `.equals()`).
  - Default values: Default to `BigDecimal.ZERO` to prevent null issues.
- **Database & Persistence**:
  - Execute schema operations within Exposed ORM `transaction` blocks.
  - Target records by primary key ID.
  - Maintain cascading cleanups when deleting records.
- **Testing**:
  - Use Kotest `StringSpec` with `init { ... }` blocks and `@Suppress("unused")`.
  - Use in-memory SQLite (`:memory:`) for tests.
  - Use `runTest` for coroutine testing.
- **Error Handling**: Verify robust exception handling without swallowing errors or using silent empty try-catch blocks.
- **Documentation & Build Exclusions Synchronization**:
  - Whenever packages or modules under `src/main` are created, moved, or removed (e.g., `com.gemini.krakenbot.view.css`), verify that `README.md` project directory tree and `build.gradle.kts` JaCoCo coverage exclusions are updated accordingly.

---

## Response Output Format

Structure all code reviews cleanly in GitHub-flavored markdown using the following template:

````markdown
# Code Review Summary

Provide a high-level 2-3 sentence overview of the code quality and primary findings.

## Highlights & Strengths

- Bullet points noting well-implemented patterns or solid design choices.

## Critical Issues & Improvements

### [Category Name: Code Quality / Bug / Security / Performance / Best Practices]

- **Location**: `[filename.ext:L12-L34](file:///path/to/filename.ext#L12-L34)`
- **Issue**: Detailed explanation of the issue or smell.
- **Impact**: Potential consequences if unaddressed.
- **Suggested Fix**:

```language
// Corrected / refactored snippet
```
````
