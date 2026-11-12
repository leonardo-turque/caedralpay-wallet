"""Release criteria for a public beta; external audit remains a separate stable-release item."""
REQUIRED_BETA_GATES = frozenset({
    'automatedUnitTests', 'deviceRegressionTests', 'localEvmEndToEnd',
    'signedReleaseVerification', 'publicTestnetRoundTrip', 'publicMainnetRoundTrip',
    'internalSecurityReview', 'signingKeyBackup', 'cloudflareAndDownloadDomain',
    'providerCapacity', 'releaseUpgradeAndRecovery', 'privacyPublication',
})
OPTIONAL_BETA_GATES = frozenset({'independentSecurityReview', 'publicTestnetTokenRoundTrip'})

def beta_blockers(readiness):
    gates = readiness.get('gates', {})
    if not isinstance(gates, dict): return ['gates: formato inválido']
    missing = sorted((REQUIRED_BETA_GATES | OPTIONAL_BETA_GATES) - gates.keys())
    if readiness.get('releaseChannel') != 'public-beta': missing.append('releaseChannel')
    for name, gate in gates.items():
        if not isinstance(gate, dict): missing.append(name); continue
        requirement = gate.get('requiredForBeta', True)
        if type(requirement) is not bool:
            missing.append(name); continue
        if not requirement:
            if name not in OPTIONAL_BETA_GATES: missing.append(name)
            elif not isinstance(gate.get('evidence'), str) or not gate['evidence'].strip(): missing.append(name)
            elif gate.get('status') not in {'passed', 'pending'}: missing.append(name)
            continue
        if gate.get('status') != 'passed' or not isinstance(gate.get('evidence'), str) or not gate['evidence'].strip():
            missing.append(name)
    return sorted(set(missing))
