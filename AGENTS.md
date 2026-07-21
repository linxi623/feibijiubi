# AGENTS.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project overview

This is a Java 17 Spring Boot backend project for a beginner-oriented mini-Bilibili-style application. The current codebase is a minimal Spring Boot skeleton with MyBatis and MySQL dependencies already configured, but no domain controllers/services/mappers have been added yet.

## Learning goal

The user's goal for this project is to become a backend engineer who can write standardized,规范化, maintainable code. When making suggestions or changes, prioritize clear layering, consistent API design, readable naming, validation, error handling, and beginner-friendly explanations of why a pattern is considered standard.

## Common commands

Use the Maven wrapper from the repository root so future work uses the project-pinned Maven setup.

```bash
# Run the full test suite
./mvnw test

# Run a single test class
./mvnw -Dtest=BackendApplicationTests test

# Run a single test method
./mvnw -Dtest=BackendApplicationTests#contextLoads test

# Compile without running tests
./mvnw -DskipTests compile

# Build the application jar
./mvnw package

# Start the Spring Boot application locally
./mvnw spring-boot:run
```

On Windows `cmd.exe`, use `mvnw.cmd` instead of `./mvnw`. This Claude Code session uses Git Bash, where `./mvnw` is appropriate.

There is no dedicated lint or formatting plugin configured in `pom.xml` at this time.

## Architecture and structure

- `pom.xml` defines a Spring Boot 3.5.15 application targeting Java 17.
- The application entry point is `src/main/java/com/feibijiubi/backend/BackendApplication.java`, annotated with `@SpringBootApplication`. Components should live under `com.feibijiubi.backend` (or subpackages) so Spring Boot component scanning finds them automatically.
- Persistence dependencies are present but not yet wired into application code:
    - `mybatis-spring-boot-starter` for MyBatis integration.
    - `mysql-connector-j` as a runtime database driver.
    - `mybatis-spring-boot-starter-test` for MyBatis-related tests.
- Lombok is configured as an annotation processor for both main and test compilation. The Spring Boot Maven plugin excludes Lombok from the packaged artifact.
- Runtime configuration currently lives in `src/main/resources/application.properties`; it only sets `spring.application.name=backend`. Database connection properties have not been added yet.
- Tests use JUnit 5 through `spring-boot-starter-test`. The current smoke test is `BackendApplicationTests.contextLoads()`, which verifies that the Spring context can start.

## Notes from existing project files

The previous `CLAUDE.md` described the project in Chinese as: this is a backend beginner's project intended to become a mini-Bilibili-style application. Preserve that intent when adding new backend features.