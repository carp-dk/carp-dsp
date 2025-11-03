# Branch Protection Setup Guide

This guide walks you through setting up branch protection rules for the `develop` and `main` branches to enforce code quality checks.

## Prerequisites

**⚠️ IMPORTANT: Complete these steps BEFORE configuring branch protection:**

1. **Push your code to GitHub**:
   ```bash
   git checkout develop
   git push origin develop
   git checkout main  
   git push origin main
   ```

2. **Verify workflows exist on GitHub**:
   - Go to: `https://github.com/carp-dk/carp-dsp/actions`
   - You should see the "CI" workflow
   - If not, check that `.github/workflows/ci.yml` is committed

3. **Trigger the workflow at least once**:
   - Make a small commit to `develop`:
     ```bash
     git checkout develop
     echo "# Test" >> README.md
     git add README.md
     git commit -m "Trigger CI workflow"
     git push origin develop
     ```
   - Go to Actions tab and wait for the workflow to complete (green checkmark)

4. **Verify you have admin access** to the repository

**Only after the workflow has run successfully can you configure branch protection!**

## Steps to Configure Branch Protection

### 1. Navigate to Branch Protection Settings

1. Go to your repository on GitHub: `https://github.com/carp-dk/carp-dsp`
2. Click **Settings** (top right, near the repository name)
3. In the left sidebar, click **Branches** (under "Code and automation")

### 2. Protect the `develop` Branch

#### Add Branch Protection Rule

1. Click **Add branch protection rule** (or **Add rule** button)
2. In **Branch name pattern**, enter: `develop`

#### Configure Protection Rules

Check the following options:

**Required Status Checks:**
- ✅ **Require status checks to pass before merging**
  - ✅ **Require branches to be up to date before merging**
  - Search and select the following status checks:
    - ✅ `build-and-test` (from CI workflow)
    - ✅ `code-quality` (from CI workflow)

**Pull Request Requirements:**
- ✅ **Require a pull request before merging**
  - ✅ **Require approvals**: Set to `1` (or more if you prefer)
  - ✅ **Dismiss stale pull request approvals when new commits are pushed**
  - ⬜ **Require review from Code Owners** (optional, if you have CODEOWNERS file)

**Additional Settings:**
- ✅ **Require conversation resolution before merging**
- ✅ **Include administrators** (enforce rules on admins too)
- ⬜ **Allow force pushes** (keep unchecked for safety)
- ⬜ **Allow deletions** (keep unchecked for safety)

#### Save the Rule

3. Scroll down and click **Create** or **Save changes**

### 3. Protect the `main` Branch

Repeat the same steps for `main`, but with stricter requirements:

1. Click **Add branch protection rule** again
2. In **Branch name pattern**, enter: `main`

**Configure the same options as `develop`, plus:**
- ✅ **Require linear history** (optional, keeps history clean)
- ✅ **Do not allow bypassing the above settings** (stricter enforcement)
- Set **Require approvals** to `2` or more (more reviewers for main)

### 4. Verify the Setup

After saving, you should see both branch protection rules listed:

```
Branch name pattern: develop
- Requires pull request
- Requires 1 approval
- Requires status checks: build-and-test, code-quality

Branch name pattern: main  
- Requires pull request
- Requires 2 approvals
- Requires status checks: build-and-test, code-quality
```

## Testing the Protection

### Test 1: Try Direct Push (Should Fail)

```bash
git checkout develop
echo "test" > test.txt
git add test.txt
git commit -m "Test direct push"
git push origin develop
```

**Expected result:** Push is rejected with message about branch protection

### Test 2: Create Pull Request (Should Work)

```bash
git checkout -b feature/test-branch-protection
echo "test" > test.txt
git add test.txt
git commit -m "Test PR workflow"
git push origin feature/test-branch-protection
```

Then create a PR on GitHub → Status checks should run automatically

## Status Checks Reference

The following checks must pass for PRs to `develop` and `main`:

### `build-and-test` Job
- ✅ Build project
- ✅ Run tests
- ✅ Generate coverage reports
- ✅ Upload to Codecov
- ⚠️ Verify coverage thresholds (warning only initially)

### `code-quality` Job  
- ✅ Run Detekt static analysis
- ✅ Check code formatting
- ✅ Upload Detekt reports

## Troubleshooting

### Status Checks Not Appearing

**Problem:** The status checks don't show up in the dropdown when configuring branch protection.

**Solution:**
1. Make sure the CI workflow has run at least once on the branch
2. Push a commit to `develop` to trigger the workflow
3. Wait for the workflow to complete
4. Refresh the branch protection settings page
5. The checks should now appear in the dropdown

### Can't Push Even with Admin Rights

**Problem:** Even as an admin, pushes are blocked.

**Solution:**
- This is expected if "Include administrators" is checked
- Either:
  1. Temporarily disable branch protection
  2. Or follow the PR process (recommended)

### Status Check Always Failing

**Problem:** A status check always fails, blocking PRs.

**Solution:**
1. Check the Actions tab for error details
2. Fix the underlying issue (tests, coverage, Detekt)
3. Or temporarily remove that check from required status checks

## Advanced: Require Coverage Threshold

Once coverage is stable, you can enforce stricter thresholds:

1. In `build.gradle.kts`, ensure coverage verification is enabled:
   ```kotlin
   verify {
       onCheck.set(true)
       rule {
           minBound(70)
       }
   }
   ```

2. In `.github/workflows/ci.yml`, remove `continue-on-error: true` from the coverage step:
   ```yaml
   - name: Verify coverage thresholds
     run: ./gradlew koverVerify --no-daemon
     # Remove: continue-on-error: true
   ```

3. Add `koverVerify` as a status check in branch protection rules

## GitFlow Workflow Summary

With branch protection enabled:

```
feature/my-feature (local)
    ↓ (git push)
feature/my-feature (remote)
    ↓ (create PR)
develop (requires: tests pass, Detekt pass, 1 approval)
    ↓ (create release PR)
main (requires: tests pass, Detekt pass, 2 approvals)
    ↓ (automatic)
GitHub Release + Maven publish (future)
```

## Next Steps

After setting up branch protection:

1. ✅ Test the workflow with a small PR
2. ✅ Verify all status checks run correctly
3. ✅ Adjust approval requirements as needed
4. ✅ Add CODEOWNERS file (optional)
5. ✅ Enable auto-merge (optional)

## References

- [GitHub Branch Protection Documentation](https://docs.github.com/en/repositories/configuring-branches-and-merges-in-your-repository/managing-protected-branches/about-protected-branches)
- [Required Status Checks](https://docs.github.com/en/repositories/configuring-branches-and-merges-in-your-repository/managing-protected-branches/about-protected-branches#require-status-checks-before-merging)

---

**Created**: October 29, 2025  
**Status**: Ready to implement

