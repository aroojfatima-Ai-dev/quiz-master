# QuizMaster

A desktop quiz application built with **JavaFX 25** and **MySQL**. Teachers create
classes and timed multiple-choice tests (in English or Urdu, right-to-left); students
join a class with a 6-character code, take tests against a countdown timer, and get a
full answer review afterwards.

---

## Features

| Role | Capabilities |
| --- | --- |
| **Teacher** | Create classes with a shareable 6-character code, build tests with a two-step wizard (typing questions, or importing them from a file), choose public or class-only visibility, review every student submission |
| **Student** | Register, join a class by code, see available tests, take a timed test, view score and a per-question answer review |

- Animated water-bubble background on every screen
- Per-test countdown timer with configurable expiry: **auto-submit** or **allow overtime**
  (overtime is recorded against the attempt)
- Urdu (RTL) question authoring and display
- **Bulk question import** from a PDF, Word `.docx` or `.txt` file, with a preview,
  per-question editing and a list of anything that was skipped (see below)
- Passwords stored as salted **PBKDF2-HMAC-SHA256** hashes, never plain text
- The database and all six tables are created automatically on first run

---

## Importing questions from a file

On **Create Test - Step 2**, "Upload Questions File" imports questions instead of
typing them one by one. Manual entry is unchanged - this is an additional option, and
the two can be combined in the same test.

The file must follow this format (the same example is shown on the upload screen):

```
Q: <question text>
A) <option A>
B) <option B>
C) <option C>
D) <option D>
Answer: <A, B, C, or D>
Topic: <topic text>
Difficulty: <EASY, MEDIUM, or HARD>
```

with a blank line between questions.

| File type | Read with |
| --- | --- |
| `.txt` | the JDK, as UTF-8 |
| `.pdf` | Apache PDFBox |
| `.docx` | Apache POI |

How the parser behaves:

- **Order is preserved** - questions are stored in the order they appear in the file.
- **Only the `Q:` line separates questions.** Blank lines are conventional but not
  required, because text extracted from a PDF or Word file does not reliably keep them.
- **Wrapped lines are joined.** Extraction breaks long lines wherever the page ends, so
  any line that is not a keyword continues the field above it.
- **`Topic` and `Difficulty` are optional** and default to `General` and `MEDIUM`. An
  unrecognised difficulty also falls back to `MEDIUM`.
- A block is **skipped and listed with its line number and reason** when it has no
  question text, is missing one of the four options, has an option longer than the
  `VARCHAR(500)` column, or has a missing or unreadable `Answer:`.
- Anything before the first `Q:` line is treated as a heading and ignored.
- A maximum of 200 questions per file; the rest are reported as skipped.
- Keywords are matched case-insensitively: `Q:`, `Q1:`, `Question 2:`, `Answer:`/`Ans:`,
  `Topic:`, `Difficulty:`; options may be written `A)`, `A.`, `A:` or `(A)`.

**Nothing is saved until "Confirm & Save All"** on the preview screen, where each
imported question can be edited or removed. The test and every question are then written
on one connection in one transaction, so a failure cannot leave a half-saved test.

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

The test suite covers the logic that was deliberately extracted out of the JavaFX `App`
class so it could be tested without opening a window:

- **`PasswordUtilTest`** — hashing and verification. The expected hashes were produced
  by an *independent* implementation (Python's `hashlib.pbkdf2_hmac`), so the tests
  would catch a wrong character encoding, a non-standard Base64 alphabet, or a changed
  key length. They also pin the legacy plain-text upgrade path.
- **`InputValidatorTest`** — registration, class-code, and question validation, field
  length limits, duration parsing and formatting, plus a locale-independence regression
  test for class-code normalisation.
- **`QuestionFileParserTest`** — the question-file format: the documented example, file
  order, wrapped lines from PDF/DOCX extraction, keyword and option spellings, the
  `General`/`MEDIUM` defaults, every skip reason, CRLF/BOM/non-breaking-space handling,
  the 200-question cap, and that no input at all can make the parser throw.

`QuestionFileReader`, which does the PDF/DOCX/TXT extraction, is not unit tested because
it only wraps PDFBox and POI. It is verified end to end instead: real `.txt`, `.pdf` and
`.docx` files (including a corrupt PDF, a corrupt DOCX, an oversized file and an old
`.doc`) are run through `QuestionFileReader.readText` and then
`QuestionFileParser.parse` — the same pair of calls the upload screen makes. Every error
path is asserted to raise an `IOException` carrying a message meant for the teacher,
never an unchecked exception.

---

## Project structure

```
.
├── pom.xml                     Maven build (JavaFX 25, MySQL Connector/J, PDFBox, POI, JUnit 5)
├── schema.sql                  Full database schema for manual import
└── src
    ├── main/java/com/quiz
    │   ├── App.java            All JavaFX screens and navigation
    │   ├── Runner.java         IDE-friendly launcher (see above)
    │   ├── dao                 ClassDAO, ResultDAO, TestDAO, UserDAO
    │   ├── db/DBConnection     Connection settings + schema bootstrap
    │   ├── importing           QuestionFileReader (PDF/DOCX/TXT text extraction),
    │   │                       QuestionFileParser (the format), ParsedQuestion,
    │   │                       ParseFailure, ImportResult
    │   ├── model               User, Question
    │   ├── security            PasswordUtil (PBKDF2 hashing)
    │   └── validation          InputValidator (all field rules)
    └── test/java/com/quiz
        ├── importing/QuestionFileParserTest.java
        ├── security/PasswordUtilTest.java
        └── validation/InputValidatorTest.java
```

The `importing` package holds no database or JavaFX code, which is what lets the whole
file format be tested without a window or a server.

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
- `App.java` builds every screen in code and is over 2,700 lines long. Splitting each
  screen into its own class would be the natural next refactor.
