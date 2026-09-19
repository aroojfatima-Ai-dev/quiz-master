# QuizMaster

A desktop quiz application built with **JavaFX 25** and **MySQL**. Teachers create
classes and timed multiple-choice tests (in English or Urdu, right-to-left); students
join a class with a 6-character code, take tests against a countdown timer, and get a
full answer review afterwards.

---

## Features

| Role | Capabilities |
| --- | --- |
| **Teacher** | Create classes with a shareable 6-character code, build tests with a two-step wizard, choose public or class-only visibility, review every student submission |
| **Student** | Register, join a class by code, see available tests, take a timed test, view score and a per-question answer review |

- Animated water-bubble background on every screen
- Per-test countdown timer with configurable expiry: **auto-submit** or **allow overtime**
  (overtime is recorded against the attempt)
- Urdu (RTL) question authoring and display
- Passwords stored as salted **PBKDF2-HMAC-SHA256** hashes, never plain text
- The database and all six tables are created automatically on first run

---

## Requirements

- **JDK 25** or newer (the build targets `--release 25`)
- **Maven 3.9+**
- **MySQL 8.0+** or **MariaDB 10.6+**

---

## Getting started

### 1. Start MySQL

With XAMPP, start Apache/MySQL from the control panel. The defaults
(`localhost:3306`, user `root`, empty password) work out of the box.

### 2. Run the app

```bash
mvn clean javafx:run
```

On the first connection the app creates the `quizmaster` database and all of its
tables, so no manual setup is required.

### 3. Or create the schema by hand

```bash
mysql -u root -p < schema.sql
```

`schema.sql` declares exactly the same schema the application bootstraps at runtime,
in foreign-key order: `users` → `classes` → `enrollments` → `tests` → `questions` →
`results`.

---

## Database configuration

Credentials are read from a **system property**, then an **environment variable**,
then the XAMPP default. Nothing needs to be recompiled to point at another server.

| Setting | System property | Environment variable | Default |
| --- | --- | --- | --- |
| Host | `quizmaster.db.host` | `QUIZMASTER_DB_HOST` | `localhost` |
| Port | `quizmaster.db.port` | `QUIZMASTER_DB_PORT` | `3306` |
| Database | `quizmaster.db.name` | `QUIZMASTER_DB_NAME` | `quizmaster` |
| User | `quizmaster.db.user` | `QUIZMASTER_DB_USER` | `root` |
| Password | `quizmaster.db.password` | `QUIZMASTER_DB_PASSWORD` | *(empty)* |

```bash
# Environment variables
QUIZMASTER_DB_USER=quiz QUIZMASTER_DB_PASSWORD=secret mvn javafx:run

# Or system properties
mvn javafx:run -Djavafx.args="-Dquizmaster.db.password=secret"
```

To check connectivity and bootstrap the schema without opening the UI, run
`com.quiz.db.DBConnection` — it prints `DB connection OK` or the reason it failed.

---

## Running from an IDE

Run **`com.quiz.Runner`**, not `com.quiz.App`.

`Runner` is a plain launcher that calls `App.main(args)`. Because it does not itself
extend `javafx.application.Application`, the JVM does not perform the JavaFX
"runtime components are missing" check, which is what lets the app start from an IDE
that puts JavaFX on the classpath rather than the module path.

> When launched this way JavaFX prints
> `Unsupported JavaFX configuration: classes were loaded from 'unnamed module'`.
> That warning is harmless — the project deliberately has no `module-info.java`.

---

## Tests

```bash
mvn test
```

The test suite covers the two pieces of logic that were deliberately extracted out of
the JavaFX `App` class so they could be tested without opening a window:

- **`PasswordUtilTest`** — hashing and verification. The expected hashes were produced
  by an *independent* implementation (Python's `hashlib.pbkdf2_hmac`), so the tests
  would catch a wrong character encoding, a non-standard Base64 alphabet, or a changed
  key length. They also pin the legacy plain-text upgrade path.
- **`InputValidatorTest`** — registration, class-code, and question validation, field
  length limits, duration parsing and formatting, plus a locale-independence regression
  test for class-code normalisation.

---

## Project structure

```
.
├── pom.xml                     Maven build (JavaFX 25, MySQL Connector/J, JUnit 5)
├── schema.sql                  Full database schema for manual import
└── src
    ├── main/java/com/quiz
    │   ├── App.java            All JavaFX screens and navigation
    │   ├── Runner.java         IDE-friendly launcher (see above)
    │   ├── dao                 ClassDAO, ResultDAO, TestDAO, UserDAO
    │   ├── db/DBConnection     Connection settings + schema bootstrap
    │   ├── model               User, Question
    │   ├── security            PasswordUtil (PBKDF2 hashing)
    │   └── validation          InputValidator (all field rules)
    └── test/java/com/quiz
        ├── security/PasswordUtilTest.java
        └── validation/InputValidatorTest.java
```

---

## Notes on data handling

- **Passwords** are stored as `PBKDF2:<iterations>:<base64 salt>:<base64 hash>`.
  Accounts created before hashing existed keep working: on the first successful login
  the stored value is transparently re-hashed and the row updated.
- **Character set** is `utf8mb4` on every table, and the JDBC connection negotiates
  UTF-8. This is required for Urdu text; MySQL's 3-byte `utf8mb3` cannot store it.
- **A test and its questions are written in one transaction**, so a failure part-way
  through can never leave a test holding only some of its questions.
- **Deleting a user cascades** to their classes, tests and results; deleting a class
  detaches its tests (they become public-only via `class_id = NULL`) and removes its
  enrolments.

---

## Known limitations

These are design limits rather than defects, and are noted so they are not mistaken
for bugs:

- There is no session timeout or "forgot password" flow.
- A student may retake a test any number of times; every attempt is stored as a
  separate row in `results`.
- The "Planned number of questions" field in the test wizard is advisory — the wizard
  lets you save any number of questions, and the confirmation message reports the
  actual count.
- `App.java` builds every screen in code and is over 2,000 lines long. Splitting each
  screen into its own class would be the natural next refactor.
