# Contributing to GhostNode

Thank you for your interest in contributing to GhostNode! We welcome pull requests, bug reports, feature requests, and documentation improvements to help make distributed synchronization robust and accessible.

---

## 🛠️ Local Development Setup

To set up the project locally for development, ensure you have:
* **Java Development Kit (JDK) 21** or newer.
* **Gradle** (the wrapper `./gradlew` is provided in the repository root).
* An IDE such as IntelliJ IDEA (recommended for Kotlin projects).

### Clone the Repository

```bash
git clone https://github.com/mathantkumar/GhostNode.git
cd GhostNode
```

### Import the Project
Import the project into your IDE as a **Gradle Project** using the root `build.gradle.kts` file.

---

## 🧪 Building & Running Tests

To verify that your local environment is configured correctly, run the build and verification suites:

### Run Unit Tests
```bash
./gradlew test
```

### Run the Concurrency and Partitioning Fuzz Simulator
```bash
./gradlew test --info --tests com.ghostnode.core.crdt.GhostNodeSimulator
```

### Verify Code Quality & Vulnerabilities
```bash
# Perform OWASP Dependency Check
./gradlew dependencyCheckAnalyze
```

---

## 📝 Code Style & Guidelines

To maintain code readability and a high bar of quality, please follow these guidelines:

1. **Kotlin Style Guide**: Follow the official [Kotlin Coding Conventions](https://kotlinlang.org/docs/coding-conventions.html).
2. **Immutability**: All core clock and CRDT representations MUST remain immutable. Return copy-on-write persistent structures when performing mutations.
3. **Tests**: Every new feature or bug fix must be accompanied by comprehensive tests under `src/test/kotlin`.
4. **Documentation**: Update [README.md](README.md) or [DESIGN.md](DESIGN.md) if you are adding new configuration options, starter behaviors, or changing algorithmic mechanics.

---

## 📬 Pull Request Process

1. **Create a Branch**: Create a feature branch from `main` using a descriptive name:
   ```bash
   git checkout -b feature/your-feature-name
   # or
   git checkout -b fix/bug-description
   ```
2. **Commit Changes**: Write clean, descriptive commit messages. Ensure each commit contains a single logical unit of change.
3. **Verify Locally**: Run `./gradlew build` to verify compiling, tests, and static checks pass.
4. **Submit a PR**:
   * Open a Pull Request targeting the `main` branch.
   * Provide a clear description of the problem solved and the implementation details.
   * Reference any related issues.
5. **Review Phase**: A maintainer will review your PR. Address comments by committing directly to your feature branch.

---

## ⚖️ Code of Conduct

We expect all contributors to adhere to standard professional code of conduct guidelines, fostering an inclusive, welcoming, and harassment-free community.
