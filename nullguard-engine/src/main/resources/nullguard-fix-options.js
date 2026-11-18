function fixOptions(rule, {route, calls = [], related = []} = {}) {
  switch (rule) {
    case 'NG001': return [
      'If absence is expected, check the receiver before dereferencing it and handle the missing-value case.',
      'If the receiver must exist, enforce that invariant where it is initialized or retrieved.',
      'Test the missing-value path and the normal path.'
    ];
    case 'NG002': return [
      'If callers may pass null, branch before using the parameter and define the fallback behavior.',
      'If null is invalid, validate the argument at the method boundary and update callers to honor that contract.',
      'Test both a null argument and a valid argument.'
    ];
    case 'NG003': return [
      'If the non-null contract is correct, return a valid result or fail explicitly on the branch returning null.',
      'If absence is part of the API, update the return contract and make callers handle it.',
      'Test each return path and any affected callers.'
    ];
    case 'NG004': return [
      'Check the argument at the call site and handle absence before calling the method.',
      'If the called method should accept null, update its contract and implementation together.',
      'Test the caller with a missing value and a valid value.'
    ];
    case 'NG005': return [
      'Inspect the reported method and its callers to find where a failure propagates.',
      'If the method mixes responsibilities, consider separating validation from work that has side effects.',
      'Add a test for the failing path and rerun the scan to compare the hotspot.'
    ];
    case 'NG006': return [
      'Initialize the field before the object can be used, preferably during construction if the value is required.',
      'If the field is optional, handle the uninitialized state at each read.',
      'Test object creation and the path that reads the field.'
    ];
    case 'NG101': return [
      'Find the first dereference and handle a missing value before reaching it.',
      'Choose an early return or an explicit error based on what callers expect.',
      'Add a regression test that exercises the missing-value branch.'
    ];
    case 'NG102': return [
      'Decide whether callers can supply null and document the contract accordingly.',
      'Validate required inputs at the method boundary or update callers to handle optional values.',
      'Test the contract at both the method boundary and a representative call site.'
    ];
    case 'NG103': return [
      'Check the dependency result at the call boundary before passing it downstream.',
      'If an empty response is valid, handle it explicitly. Otherwise surface the failure with useful context.',
      'Stub the dependency to return null and verify the boundary behavior.'
    ];
    case 'NG104': return [
      related.length
        ? 'Review the linked null-safety findings along this path first. If one explains the failure, fix that operation and cover it with a regression test before restructuring the target method.'
        : calls.length
          ? 'Review the direct calls listed above. Identify which call can produce the failure and which boundary owns the response before choosing a refactor.'
          : 'Inspect the target method and identify the operation that can fail before choosing a refactor.',
      route
        ? 'If this endpoint owns the failure response, handle the expected failure before side effects. If the same contract applies to other callers, enforce it in the shared service and keep HTTP response mapping at the endpoint.'
        : 'If the failure is expected, handle it at the boundary with enough context. If a required invariant is broken, enforce it where the value is produced.',
      route
        ? 'For ' + route + ', test the normal response and the dependency failure. Verify the documented status and body, and check that no unintended side effects occur. Rerun NullGuard and compare the same method.'
        : 'Test a normal call and the identified failure path. Rerun NullGuard and compare the same method and its affected callers.'
    ];
    case 'NG105': return [
      'Trace the call chain and identify where invalid data or a failure first enters it.',
      'Handle the failure at the earliest boundary that has enough context to make the right decision.',
      'Exercise the failing dependency in a test and check how far the failure propagates.'
    ];
    default: return [];
  }
}
