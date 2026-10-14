## How to Contribute

[中文版本](./CONTRIBUTING-zh.md)

## Thank you for contributing to ARGI!

Since its open-source inception, ARGI has received attention from many community members. Every Issue and PR from the community helps the entire project and contributes to building a better ARGI.

We sincerely thank the developers who have submitted Issues and PRs for this project. We hope more community developers will join us to make this project even better.

## Contribution Process

Before contributing code, please take a moment to understand the process of contributing to ARGI.

### What to Contribute?

We welcome any contribution at any time, whether it's a simple typo fix, bug fix, or new feature. Please feel free to raise issues or submit PRs. We also value documentation and integration with other open-source projects, and welcome contributions in these areas.

For more complex changes, we suggest first adding a Feature tag in an Issue with a brief description of the design and modification points.

### Where to Start?

If you are a first-time contributor, you can claim a relatively simple task from [good first issue](https://github.com/agentic-ai-java/argi/labels/good%20first%20issue) or [help wanted](https://github.com/agentic-ai-java/argi/labels/help%20wanted).

### Fork the Repository and Clone it Locally

- Click the `Fork` icon in the upper right corner of [this project](https://github.com/agentic-ai-java/argi) to fork agentic-ai-java/argi to your own space.
- Clone the argi repository from your account to your local machine. For example, if my account is `chickenlj`, I would execute `git clone https://github.com/chickenlj/argi.git` to clone it.

### Configure Github Information

- Execute `git config --list` on your machine to check git's global username and email.
- Verify that the displayed user.name and user.email match your github username and email.
- If your company has its own gitlab or uses other commercial gitlab solutions, there might be a mismatch. In this case, you need to set a separate username and email for the argi project.
- For instructions on setting your username and email, please refer to the official github documentation: [Setting your username](https://help.github.com/articles/setting-your-username-in-git/#setting-your-git-username-for-a-single-repository) and [Setting your email](https://help.github.com/articles/setting-your-commit-email-address-in-git/).

### Merge Latest Code

After forking the repository, new commits may have appeared in the original repository's main branch. To avoid conflicts between your PR and the commits in the main branch, you need to regularly merge from the main branch.

- In your local argi directory, execute `git remote add upstream https://github.com/agentic-ai-java/argi` to add the original repository address as the remote upstream.
- In your local argi directory, execute `git fetch upstream` to fetch the remote updates to your local machine.
- In your local argi directory, execute `git checkout main` to switch to the main branch.
- In your local argi directory, execute `git rebase upstream/main` to rebase the latest code.

### Configure Code Format

Before you start, configure your IDE to use the repository's Java formatting rules. Run the formatting and Checkstyle checks before submitting code.

### Development

Develop your feature, and **after completing development, it's recommended to use the `mvn clean package` command to ensure that the modified code compiles successfully locally. This command also automatically formats the code according to Spring standards.** Before committing your code, please create a new branch specific to your feature and commit your changes to this branch.

### Local CI

After completing development in your local environment, it's strongly recommended to run the provided `make` command in the project's `tools/make` directory to perform local Continuous Integration (CI) checks. This ensures your code adheres to the project's standards and guidelines. If you have questions about local CI, you can enter `make help` in the console for more detailed information.

### Local Checkstyle

To reduce unnecessary code style issues, ARGI provides a local Checkstyle check feature. You can run `mvn checkstyle:check` in the project's root directory to verify if your code style complies with the standards.

### Remove Unused Imports

To ensure code cleanliness, please remove any unused imports in your Java files. You can do this by using the `mvn spotless:apply` command, which will automatically remove unused imports from your code.

### Commit the Latest Code

Use the `type(scope): description` format for commit messages and PR titles; `scope` is optional. Common types are `fix`, `feat`, `refactor`, `docs`, `chore`, `perf`, `infra`, `revert`, `release`, `test`, and `style`.

The build-and-test workflow runs on pull requests targeting `main` and pushes to
`main`; feature-branch pushes do not trigger a second full pipeline. Job repository
guards restrict runs to `agentic-ai-java/argi`, but do **not** exclude fork-origin
pull requests opened against that upstream repository. Those runs execute the PR
checkout's local actions and scripts with read-only repository permissions and
without repository secrets. GitHub's fork-workflow approval settings still apply;
maintainers should review untrusted workflow/script changes before approving runs.
No welcome message or label is added automatically. Review requirements are
managed by repository branch protection or rulesets.

Run local checks before submitting even when upstream CI is available. The CI
test job installs the tested artifacts once, then uses
`verify-api-contract.sh --skip-install` to compile the independent consumer.

Commit format example:

```bash
git commit -m "type(module): descriptive message"
```

For instance: `feat(docs): update contribute-zh`

### Merge Latest Code Again

- Similarly, before submitting a PR, you need to rebase the code from the main branch (if your target branch is not the main branch, you need to rebase from the corresponding target branch). Please refer to the previous section for specific operation steps.
- If conflicts occur, you need to resolve them first.

### Submit PR

Submit your PR, explain the modifications and implemented features according to the `Pull request template`, and wait for code review and merging. Become an ARGI Contributor and make a contribution to a better ARGI.
