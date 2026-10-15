# First-time developer evaluation — 2026-10-16

## Result

The locally installed 1.0.0 Maven plugin can analyze an independent Java project
and produce an actionable file/line warning. A fresh Maven Central install is
still blocked: the plugin is not yet resolvable from Central. This is an early
adopter tool with observable false positives, not a validated replacement for
existing null-safety checks. No real developer usability interview was performed;
this was an automated walkthrough of the README by the coding agent.

## Environment and evidence

- macOS arm64, JDK 17.0.12, Maven 3.9.14.
- Coordinates: `com.arinits.nullguard:nullguard-maven-plugin:1.0.0`.
- Local-install checks used the current workspace build already installed locally.
- Independent project: Spring Petclinic, commit
  `500158f732419217507c7656904b8e6aa1bcc0d6` from
  https://github.com/spring-projects/spring-petclinic.
- Raw logs and generated reports from this run are in the local temporary directory
  `/tmp/nullguard-onboarding-mSyJ03/` (not permanent release artifacts).

| Check | Observation | Result |
| --- | --- | --- |
| Fresh repository install | Plugin resolution failed with artifact-not-found from Central | Blocked |
| Petclinic local-install analysis | Maven BUILD SUCCESS; HTML, JSON and SARIF generated | Passed |
| API discovery | 17 endpoints reported | Observed, completeness not audited |
| Seeded definite null dereference | NG001 at `demo/CustomerService.java:5` | Passed |
| Guarded method | No warning for the guarded parameter dereference | Passed |
| Apply suggested null guard | Seeded findings dropped from 1 to 0 | Passed |
| Real-project precision | Initialized-list getter flagged as possibly null | Needs improvement |

## Reproduce the local-install route

First build and install the current NullGuard source using `mvn clean install`.
In a separate checkout:

```sh
git clone https://github.com/spring-projects/spring-petclinic.git
cd spring-petclinic
git checkout 500158f732419217507c7656904b8e6aa1bcc0d6
mvn com.arinits.nullguard:nullguard-maven-plugin:1.0.0:analyze -Dnullguard.failBuild=false
```

Open `target/nullguard/nullguard-dashboard-latest.html`; inspect detailed warnings
in `nullguard-results.sarif` or `nullguard-report-latest.json` in the same directory.
No application server or database was started for this analysis.

To reproduce a Central-only install, add
`-Dmaven.repo.local=/tmp/nullguard-fresh-repository` to the Maven command, choosing
a new empty directory. This prevents an existing local NullGuard install from
masking missing published artifacts.

## Petclinic observations

The report contained 87 project methods and 48 external methods, 17 API endpoints,
177 findings, 18 suggestions and zero architectural hotspots. The stability grade
was C (77.61), with 25 high-risk methods. Analysis took approximately 1.76 seconds;
the first Maven invocation including dependency downloads took 29.02 seconds.
These timings are one observation, not a benchmark.

A concrete false positive is `Owner.addPet`, line 104: `getPets().contains(pet)`.
`getPets()` returns `this.pets`, a final list initialized with `new ArrayList<>()`.
The warning that `getPets()` may return null is therefore unsupported for this
source. Framework callback arguments and fluent API calls also attracted warnings;
those have not all been adjudicated and should not be described as confirmed bugs.
No precision or recall percentage can be inferred from this single sample.

## Seeded finding and fix

An independent minimal Maven project contained:

```java
package demo;
public class CustomerService {
    public int unsafe(String name) {
        String missing = null;
        return missing.length();
    }
    public int guarded(String name) {
        if (name == null) return 0;
        return name.length();
    }
}
```

The report identified only the unsafe method: NG001, receiver `missing` may be
null, line 5. Adding `if (missing == null) return 0;` immediately before the
unsafe dereference removed the finding on the next run.

The original sample still received an A grade and zero hotspots. The grade is a
risk-prioritization heuristic; it does not establish that every method is safe.
The default report-only invocation intentionally returned success with a warning.
The hotspot gate does not block every individual null-dereference warning.

## Follow-up priorities

1. Confirm Central publication and repeat installation with an empty local cache.
2. Improve initialized-field getter return summaries and framework non-null contracts.
3. Review labeled findings across several real applications before recommending
   CI enforcement; the Petclinic findings have not all been triaged.
4. Observe a real developer following the README unaided and collecting feedback.

## Follow-up — 2026-10-16

The Central installation blocker is now resolved: published 1.0.0 was installed
and executed using an initially empty local Maven cache. Improvements under
1.0.1-SNAPSHOT and the expanded three-project labeled comparison are documented
in [the real-project review](evaluation/real-project-review.md).
Human observation remains pending; [recruitment and feedback materials](usability/README.md)
are prepared for LinkedIn volunteers. The original walkthrough above remains
historical evidence, not a current statement that publication is blocked.
