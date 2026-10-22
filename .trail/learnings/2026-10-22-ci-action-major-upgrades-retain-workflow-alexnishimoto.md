---
title: CI action major upgrades retain workflow configuration
author: Alexnishimoto
date: 2026-10-22
branch: modernize-and-harden
id: l-bbd49652
scope: repo
status: active
touches:
  - ".github/workflows/build.yml"
  - ".github/workflows/security.yml"
---

## Decided
Use checkout v7, setup-java v6, and upload-artifact v7, retaining major-tag pinning and all existing workflow inputs. Baseline run 39839872765 used runner 2.338.0 in all nine jobs, above Node 24 minimum 2.327.1.
## Tried and ruled out
No archive:false or credential overrides: upload-artifact v7 retains archived multi-file uploads by default; checkout v6+ persists credentials in RUNNER_TEMP automatically. Checkout v7 fork restrictions affect pull_request_target/workflow_run, neither used here.
## Watch out
setup-java v6 adds JDK/wrapper caching and integrity checks. Local clean verify and distribution smoke tests passed (109 tests); they do not validate Actions runtime annotations or hosted uploads. Fresh Build and Dependencies runs remain necessary after an authorized push.
