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
- **A sample file is included**: [`samples/sample-questions.txt`](samples/sample-questions.txt)
  is a five-question file that the test suite parses on every build.
- **`Topic` and `Difficulty` are optional** and default to `General` and `MEDIUM`.
  Recognised difficulty spellings are `EASY`/`MEDIUM`/`HARD` plus the obvious synonyms
  (`Easy`, `Difficult`, `Med`, `Hard (level 3)`); anything else falls back to `MEDIUM`
  rather than costing you the question.
- **A whole question on one line is understood**, so a `.txt` written as
  `Q: Capital of France? A) Berlin B) Paris C) Madrid D) Rome Answer: B` works. The line is
  only split when it holds all four option markers `A) B) C) D)` in order.
- **An answer may be written out instead of as a letter** (`Answer: Paris`). It is matched
  against the option texts, and if two options fit, nothing is guessed - the block is
  reported instead.
- **Extraction artefacts are removed**: page breaks between PDF pages, non-breaking spaces,
  the byte order mark a Windows editor adds, and the Unicode line/paragraph separators.
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
- **`QuestionFileToleranceTest`** — the cases real files throw at it: a whole question on
  one line, several questions on one line, an answer written as text (and the ambiguous
  case that must *not* be guessed), difficulty synonyms, page breaks and non-breaking
  spaces, and that an ordinary one-field-per-line file is left alone.
- **`QuestionFileLayoutTest`** — the layouts a teacher can reasonably produce that used to
  be refused: the options and answer on one line under the question, a wrapped option that
  enumerates markers and must *not* be cut apart, and that a skipped block is reported at
  the line it is actually on rather than the line an expanded single-line question left
  behind.
- **`SampleFileTest`** — reads `samples/sample-questions.txt` through the reader and the
  parser together, so the sample teachers copy always parses.
- **`SchemaSeedTest`** — reads `schema.sql` and verifies that the documented demo hashes
  really are `teacher123` / `student123`, that every table and index is declared, and that
  the runtime bootstrap in `DBConnection` declares the same indexes.

`QuestionFileReader`, which does the PDF/DOCX/TXT extraction, is verified end to end
rather than unit tested, because it only wraps PDFBox and POI: during development, real
`.txt`, `.pdf` and `.docx` files (including a PDF written by PDFBox itself, a scanned PDF
with no text layer, a corrupt DOCX, an oversized file, a legacy `.doc` and a `.doc`
renamed to `.docx`) were run through `QuestionFileReader.readText` and then
`QuestionFileParser.parse` — the same pair of calls the upload screen makes — and then
saved to a real MySQL/MariaDB server through `TestDAO` and read back to confirm the
questions arrive in file order with the right topics and difficulties. Every error path
raises an `IOException` carrying a message meant for the teacher, never an unchecked
exception.

---

## Project structure

```
.
├── pom.xml                     Maven build (JavaFX 25, MySQL Connector/J, PDFBox, POI, JUnit 5)
├── schema.sql                  Full database schema for manual import (+ optional demo data)
├── samples
│   └── sample-questions.txt    Example file for the question import
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
        ├── importing        QuestionFileParserTest, QuestionFileToleranceTest, SampleFileTest
        ├── security         PasswordUtilTest, SchemaSeedTest
        └── validation       InputValidatorTest
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

---

## What this revision fixed

Bugs found while adding the import feature. Each one is a defect that could be reproduced
before the change.

| # | Problem | Fix |
| --- | --- | --- |
| 1 | The manual wizard never called `InputValidator.validateTopic`, so a topic longer than the `questions.topic` column (`VARCHAR(100)`) reached MySQL and failed the whole save with "Data too long for column 'topic'". | The wizard validates the topic on **Next** and on **Finish**, with the length named in the message. (The import screens already did this.) |
| 2 | `validateQuestion` bounded the four options but not the question text, so a huge paste produced a raw SQL error instead of a message. | `QUESTION_TEXT_MAX_LENGTH` (2 000) is enforced for typed and imported questions alike. |
| 3 | A question block whose `Topic:` line was blank was stored as an empty topic, because the wizard copied the draft straight into a `NOT NULL` column with a `'General'` default. | A blank topic or difficulty is normalised to `General` / `MEDIUM` on the way in. |
| 4 | An answer written as a sentence containing the word "a" (`Answer: Pacific Ocean - it covers ...`) was read as option **A**, because any single-letter token `A`–`D` anywhere in the answer was accepted. | Only a whole-value letter (`" b "`, `"(c)"`), a leading marker (`"B)"`, `"A - the first one"`) or an explicit phrase (`"answer is B"`, `"option D"`) counts. Anything else is matched against the option texts, and reported if ambiguous. |
| 5 | A question written on one line (`Q: ... A) ... D) ... Answer: B`) was reported as a failed block, although a `.txt` file written that way is a natural mistake. | A line holding all four option markers in order is expanded into its fields first. Ordinary files are untouched. |
| 6 | A form feed (where a PDF page ends) was left in the text, gluing the last line of one page to the first line of the next - the following question was swallowed as continuation text. | Page breaks, vertical tabs, non-breaking/figure/narrow spaces, zero-width spaces and the byte order mark are normalised away. |
| 7 | Difficulty spellings like `Difficult`, `Med` or `Easy` silently became `MEDIUM`, quietly losing a HARD rating. | Synonyms map to the nearest level; only a genuinely unrecognised value uses the documented `MEDIUM` fallback. |
| 8 | The schema created at runtime did not match `schema.sql`: the five `idx_...` indexes existed only in the file, so a database bootstrapped by the app had none of them while the README promised the two schemas were identical. | The bootstrap creates the indexes for a new database and adds them to an existing one (a duplicate-index error is ignored). `SchemaSeedTest` fails the build if the two ever drift apart again. |
| 9 | A wrong database password was reported as "Could not create database ... or grant the CREATE privilege", sending the user to look at privileges instead of credentials. | An access-denied error (MySQL 1044/1045) is now reported as a credentials problem, naming the properties and environment variables to set. |
| 10 | The test countdown (an `INDEFINITE` JavaFX animation kept outside the screen's animation list) was never stopped: it kept ticking once a second for the rest of the session and kept the stage alive after the window closed. Starting a second test could leave two countdowns running. | The countdown is tracked in a field, stopped on application exit, and replaced when a new test starts. |
| 11 | A double click on "Finish & Save Test" could create the same test twice. | Both save handlers ignore a second click; a failed save still leaves the button usable. |
| 12 | The demo accounts documented in `schema.sql` used placeholder hashes, so uncommenting the block produced accounts that could never log in - while the comment claimed they were real hashes of `teacher123` / `student123`. | Real hashes are in place (verified against a server), the class and enrolment seeding statements are included, and `SchemaSeedTest` fails the build if either hash stops matching its password. |
| 13 | A few `String.format` calls on the student screens (countdown, "You scored x out of y", the results history, the answer review) used the default locale, which renders non-Latin digits in an otherwise English UI. | All user-visible formatting uses `Locale.ROOT`. |

### Bugs found by exercising the import feature end to end

Each of these was reproduced before being fixed - the import flow was driven with real
`.txt`, `.pdf` and `.docx` files (including a PDF written by PDFBox itself, a scanned PDF
with no text layer, a corrupt DOCX, a `.doc` renamed to `.docx` and an oversized file) and
the saved tests were read back from a real MySQL/MariaDB server.

| # | Problem | Fix |
| --- | --- | --- |
| 14 | Editing an imported question on the preview screen did nothing. The edit screen saved the change into the working list and then called `showImportPreviewScreen`, which rebuilt that list from `result.getQuestions()` - so the original text came straight back, and removing a question from the edit screen brought it back too. The "N questions found" count and the "everything was removed" note were reset by the same rebuild. | The preview screen now takes the working list, the failure list and the removal count as parameters, and the edit screen returns through a single navigation that hands the same list back. |
| 15 | A double click on "Confirm & Save All" created the same test twice. The README claimed both save handlers ignored a second click, but only the manual "Finish" handler had the guard. | The import confirm handler has the same one-shot guard; a failed save still leaves the button usable. |
| 16 | A half-typed question could be persisted. The wizard's **Back** and **Upload** buttons save the question on screen without validating it, and neither save path re-checked the questions already in the draft list - only the one on screen (manual) or only the imported rows (import). MySQL accepts an empty string in a `NOT NULL` column, so the test was created and students then saw four empty radio buttons. | Both save paths re-validate **every** question before writing and list which ones are incomplete, and `TestDAO` refuses an incomplete question itself (`InputValidator.validateQuestion(Question)`), so the database cannot be handed one again. |
| 17 | The schema bootstrap declared the `tests.class_id` foreign key twice: once in `CREATE TABLE` and once in the migration `ALTER TABLE ... ADD CONSTRAINT fk_tests_class`. MySQL and MariaDB accept a second constraint on the same column, so an application-created database ended up with a duplicate that `schema.sql` does not declare - and every later start failed with a duplicate-key error and printed "DB migration skipped", which reads like a fault. | The migration checks `information_schema` first and only adds the constraint when there is not already one. The five `idx_...` indexes are added the same way, so an existing database is no longer hit with five no-op `ALTER`s on every start. |
| 18 | A `.txt` written with the question on one line and its options plus the answer on the next (`Q: Capital of France?` / `A) Berlin B) Paris C) Madrid D) Rome Answer: B`) was rejected with "Missing option B", even though the line holds all four option markers in order - the documented condition for splitting it. | A line that starts with an option marker is now split too, provided it also carries a keyword label. A wrapped option that merely enumerates markers inside its own text is still left alone, so nothing is ever cut apart on a guess. |
| 19 | A skipped block was reported at the wrong line whenever an earlier question had been written on one line: expanding that line into several changed the line count, so the reported number pointed at a line that does not exist in the file. | Each expanded field keeps the line number it came from, so every failure is reported against the line the teacher can actually see. |

`QuestionFileLayoutTest` covers 18 and 19, `InputValidatorTest` covers 16 at the rule level,
and `SchemaSeedTest` fails the build if the duplicate-constraint migration (17) comes back.
