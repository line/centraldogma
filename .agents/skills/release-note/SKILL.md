---
name: release-note
description: Generate release notes for a Central Dogma milestone from GitHub. Fetches closed issues/PRs, gathers PR context (descriptions, code examples, review comments), and produces polished Markdown release notes.
disable-model-invocation: true
argument-hint: <milestone-version>
allowed-tools: [WebFetch, Bash, Read]
---

# Release Note Generator

Generates release notes for a given Central Dogma milestone version by fetching
closed issues/PRs from GitHub, gathering detailed PR context, and producing
polished Markdown output.

## Prerequisites

- The `gh` CLI must be authenticated with access to `line/centraldogma`. Verify with `gh auth status`.
- If `gh` authentication fails, set the `GH_TOKEN` environment variable from
  `GITHUB_ACCESS_TOKEN` before retrying:
  ```bash
  export GH_TOKEN="$GITHUB_ACCESS_TOKEN"
  ```

## Invocation

```
/release-note <version>
```

Example: `/release-note 0.83.0`

---

## Phase 1: Fetch Milestone Issues and PRs

1. Fetch all **closed** issues for the milestone:
   ```bash
   gh issue list --repo line/centraldogma --milestone "<version>" --state closed --limit 200 --json number,title,labels
   ```
2. Fetch all **closed** PRs for the milestone (use `--search` since `gh pr list` does not support `--milestone`):
   ```bash
   gh pr list --repo line/centraldogma --state closed --limit 200 --search "milestone:<version>" --json number,title,labels
   ```
3. Combine the results, deduplicating by issue/PR number.
4. If no items are found, report the error and stop.

## Phase 2: Gather PR Context from GitHub

For each unique PR/issue number found in Phase 1:

1. Fetch PR details:
   ```bash
   gh pr view <number> --repo line/centraldogma --json title,body,labels,files
   ```
   If it is an issue rather than a PR:
   ```bash
   gh issue view <number> --repo line/centraldogma --json title,body,labels
   ```
2. Parse the PR/issue body to extract **Motivation**, **Modifications**, and **Result** sections.
3. Fetch PR review comments — these often contain important design decisions, caveats, and
   scope limitations that are not in the PR description:
   ```bash
   gh api repos/line/centraldogma/pulls/<number>/comments --jq '.[].body'
   ```
4. Extract linked issue numbers from the body (`Closes #NNNN`, `Fixes #NNNN`, `Resolves #NNNN`).
5. For each linked issue, fetch its context **including comments**, which often contain use cases,
   edge cases, and design discussions that inform the release note description:
   ```bash
   gh issue view <number> --repo line/centraldogma --json title,body
   gh api repos/line/centraldogma/issues/<number>/comments --jq '.[].body'
   ```
6. For PRs in the "New features" section that introduce significant new API:
   - Look for usage examples in the PR description's Result section first — prefer these over
     constructing examples from scratch.
   - Check review comments for scope limitations, caveats, or known constraints that should be
     mentioned in the release note.
   - Read key changed source files from the PR's `files` list to understand method signatures
     if the PR body lacks sufficient detail.

**Rate limiting**: If fetching many PRs, batch requests and pause briefly between them to avoid
GitHub API rate limits.

## Phase 3: Categorize Each Item

Classify every item into one of the following categories based on its labels and title:

| Category | Condition |
|---|---|
| **New features** | Has label `new feature` or `enhancement` |
| **Improvements** | Has label `improvement` |
| **Bug fixes** | Has label `bug` or `defect` |
| **Dependencies** | Has label `dependencies` or title starts with "Update dependencies" / "Bump" |
| **Miscellaneous** | Everything else |

If an item has multiple matching labels, pick the most specific category
(e.g., `bug` wins over a generic label).

## Phase 4: Format the Release Notes

Produce a Markdown document following this exact structure.
Only include sections that have at least one item.

```markdown
### New features

* <user-facing description of the change>. #<number>

  ```java
  // code example if available from the PR/issue body
  ```

### Improvements

* <one-line summary of the change>. #<number>

### Bug fixes

* <one-line summary of the change>. #<number>

### Dependencies

* <library-name> <old-version> → <new-version>
* ...
```

### Formatting Rules

#### All Sections — Common Rules

1. Each item is a single bullet starting with `* `.
2. The summary should be a concise, user-facing description rewritten from the PR/issue
   context gathered in Phase 2. Do **not** just copy the raw title verbatim — clean it up
   for readability (e.g., remove prefixes like "fix:", capitalize properly, form a
   complete sentence).
3. End each summary with `. #<number>` where `<number>` is the GitHub issue or PR number.
   If multiple issues/PRs are related, list all numbers (e.g., `#1243 #1244 #1251`).
4. Sort items within each section by issue/PR number ascending.
5. Omit any section header that would have zero items.
6. Do NOT copy PR titles verbatim — they are often terse commit-style messages.
7. Do NOT fabricate code examples. Derive them from PR descriptions, Result sections,
   or actual source code found during Phase 2.
8. Keep entries self-contained — a reader should understand the change without clicking
   the PR link.

#### New Features

- Write a rich, user-facing description (not just one line) explaining what the feature
  does, why it matters, and how to use it. Use the **Motivation** and **Result** sections
  from the PR body gathered in Phase 2.
- Include a Java code example (5-15 lines) whenever the PR body or Result section contains
  one. If no example is available and one cannot be confidently derived, omit it rather
  than fabricating one.
- Indent code blocks with 2 spaces under the bullet.

#### Improvements

- Concise description of what improved and why it matters.
- Code examples only if the improvement changes how users interact with an API.

#### Bug Fixes

- Describe the symptom that was fixed, not the internal cause.

#### Dependencies

- Parse the PR body of the dependency update PR to extract individual library version changes.
- Format each entry as `* <library-name> <old-version> → <new-version>` without an issue number.
- Use `→` (unicode arrow), not `->`.
- If a new dependency was added (not an upgrade), format as `* <library-name> <version> (new)`.
- Strip build-only dependencies (test, annotation processors, etc.) into a separate sub-section
  or omit them if they are not user-facing.
- Sort alphabetically.

## Phase 5: Output

Print the formatted release notes to the conversation so the user can review and copy them.

---

## Example Output

The following is an example of a well-formatted release note for reference:

````markdown
### New features

* Central Dogma now supports **templates and variables** for dynamic configuration. You can define variables at the project or repository level and use ${vars.varName} placeholders in your configuration files. When fetched with template rendering enabled, placeholders are replaced with actual variable values at runtime. This enables managing multiple environments with a single template and centralizing common configuration values. #1243 #1244 #1251 #1262

  ```java
  CentralDogma dogma = ...;

  // Fetch a rendered template with default variables
  Entry<String> rendered = dogma.forRepo("myProject", "myRepo")
                                .file(Query.ofText("/config.json"))
                                .renderTemplate(true)
                                .get()
                                .join();
  ```

  For detailed usage, see the [Templates and Variables](https://line.github.io/centraldogma/templates-variables.html) documentation.

### Improvements

* Micrometer metrics are now available to track xDS resource snapshot versions. #1248
* Migrated from JSR-305 to JSpecify annotations for nullability. #1194

### Bug fixes

* A mirror is now re-run when its configuration is updated. #1247
* Invalid YAML files are now served as TEXT type instead of causing errors. #1257

### Dependencies

* Armeria 1.35.0 → 1.36.0
* Jackson 2.20.1 → 2.21.0
* Spring Boot 3.5.8 → 3.5.10, 4.0.0 → 4.0.2
````

## Common Mistakes to Avoid

- **Copying PR titles as-is**: PR titles like "Fix NPE in FooBar" are not user-friendly.
  Rewrite as "Fixed a `NullPointerException` in `FooBar` when ..."
- **Fabricating code examples**: If you cannot find a clear usage pattern from the PR
  description or source code, write a descriptive sentence instead of guessing at code.
- **Skipping Phase 2**: Without gathering PR context, release notes will be shallow
  one-liners that don't help users understand the changes. Always fetch PR bodies and
  review comments.
- **Including build dependencies**: The dependency update PR body may contain build-only
  deps (test, annotation processors). These should be separated or omitted.
- **Leaving empty sections**: The final output should only contain sections with actual content.
