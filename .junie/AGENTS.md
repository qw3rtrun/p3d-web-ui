# Developer Guide for p3d-web-ui

## 1. Build and Configuration Instructions

### Prerequisites

- **JDK**: Java 25 (OpenJDK 25). The build conventions (`buildSrc/src/main/groovy/p3d.java-conventions.gradle`) enforce
  Java toolchain language version 25.
- **Build Tool**: Gradle 9.2.1 via the included Gradle wrapper (`gradlew` / `gradlew.bat`).
- **Node.js**: Handled automatically by the Gradle frontend plugin (Node version `22.22.0` configured in
  `frontend/web-ui/build.gradle`).

### Module Architecture

The project is a multi-module Gradle project composed of:

- `:app` &mdash; The main Spring Boot executable application integrating backend services, APIs, and the frontend web
  UI.
- `:backend:core` &mdash; Common domain models, messaging abstractions, and `CommandQueryBus`.
- `:backend:terminal` &mdash; Reactor Netty-based asynchronous TCP communication and publisher queues for 3D printer
  hardware/firmware interaction.
- `:backend:api` &mdash; Spring WebFlux reactive REST and streaming APIs for printer control and monitoring.
- `:backend:machine-mng` &mdash; Machine management persistence layer built on Spring Data JPA, Spring Modulith,
  Liquibase migrations, and H2 database.
- `:frontend:web-ui` &mdash; Vue 3 / TypeScript frontend bundled using Vite and packaged into Spring Boot static
  resources via Gradle.
- `:gcode` &mdash; G-code parser, tokenizer, AST representation, and command codecs.

### Build Commands

- **Full Project Build**:
  ```bash
  ./gradlew build
  ```
  *(On Windows PowerShell: `.\gradlew.bat build`)*

- **Build Specific Subproject**:
  ```bash
  ./gradlew :backend:core:assemble
  ./gradlew :backend:machine-mng:assemble
  ./gradlew :app:assemble
  ```

- **Frontend Build & Asset Copy**:
  ```bash
  ./gradlew :frontend:web-ui:copyVueFiles
  ```

---

## 2. Testing Information

### Test Stack

- **Unit Testing**: JUnit 5 (JUnit Jupiter) configured via `useJUnitPlatform()` across all subprojects.
- **Integration Testing**: Spring Boot Test (`@SpringBootTest`), Spring Modulith Starter Test, and Liquibase Test.
- **Reactive Stream Verification**: Project Reactor Test (`StepVerifier`).

### Running Tests

- **Run all tests across all modules**:
  ```bash
  ./gradlew test
  ```

- **Run tests for a specific module**:
  ```bash
  ./gradlew :backend:core:test
  ./gradlew :backend:machine-mng:test
  ./gradlew :frontend:web-ui:test
  ```

- **Run a specific test class**:
  ```bash
  ./gradlew :backend:core:test --tests "org.qw3rtrun.p3d.core.bus.CommandQueryBusTest"
  ./gradlew :backend:machine-mng:test --tests "org.qw3rtrun.p3d.mng.MachineManagementApplicationTests"
  ```

- **Run a specific test method**:
  ```bash
  ./gradlew :backend:machine-mng:test --tests "*MachineManagementApplicationTests.contextLoads*"
  ```

- **Force re-execution of tests**:
  ```bash
  ./gradlew :backend:machine-mng:test --rerun
  ```

### Guidelines for Adding New Tests

1. **Test Location**: Place test classes under `<subproject>/src/test/java` or `<subproject>/src/test/kotlin` following
   matching package conventions (e.g. `org.qw3rtrun.p3d.*`).
2. **Standard Unit Test Structure**:
   ```java
   package org.qw3rtrun.p3d.core;

   import org.junit.jupiter.api.Test;
   import static org.junit.jupiter.api.Assertions.assertEquals;

   class MyServiceTest {

       @Test
       void testOperation() {
           int result = 2 + 2;
           assertEquals(4, result);
       }
   }
   ```
3. **Reactive Streams Testing**: When testing Reactor `Flux` or `Mono` publishers (e.g. in `backend:terminal` or
   `backend:api`), use `StepVerifier`:
   ```java
   StepVerifier.create(publisher)
       .expectNext(expectedValue)
       .verifyComplete();
   ```
4. **Spring Boot Integration Tests**:
    - Annotate test class with `@SpringBootTest`.
    - Provide in-memory test configuration (such as `spring.datasource.url=jdbc:h2:mem:testdb` in
      `src/test/resources/application.properties`).

---

## 3. Additional Development Information

- **Technology Versions**:
    - Spring Boot `4.0.1`
    - Spring Modulith `2.0.1`
    - Kotlin `2.3.0`
    - Lombok `9.2.0` via `io.freefair.lombok` plugin
    - Liquibase plugin `3.0.0`
- **Code Style and Conventions**:
    - Java 25 modern features (records, sealed interfaces, pattern matching, switch expressions) are encouraged
      throughout the codebase.
    - Reactive programming paradigms (Project Reactor `Mono`/`Flux`) are used for non-blocking I/O in printer
      communication and web APIs.
    - Lombok annotations (e.g. `@Slf4j`, `@Getter`, `@RequiredArgsConstructor`) are widely utilized for boilerplate
      reduction.
- **Database Migrations**:
    - `backend:machine-mng` manages database schema via Liquibase change logs located under
      `src/main/resources/db/changelog/`.
- **Frontend Development**:
    - Located in `frontend/web-ui/src/frontend`.
    - Driven by Vite and Vue 3. When compiled, output assets from `dist` are placed into Spring Boot's static resources
      path via the `:frontend:web-ui:copyVueFiles` task.
