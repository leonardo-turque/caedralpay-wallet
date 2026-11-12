import copy
import unittest
from beta_policy import beta_blockers, REQUIRED_BETA_GATES, OPTIONAL_BETA_GATES

class BetaPolicyTest(unittest.TestCase):
    def ready(self):
        gates={name:{'status':'passed','evidence':'qa/result.json'} for name in REQUIRED_BETA_GATES}
        gates.update({name:{'status':'pending','requiredForBeta':False,'evidence':'Explicitly not performed.'} for name in OPTIONAL_BETA_GATES})
        return {'releaseChannel':'public-beta', 'gates':gates}
    def test_all_technical_evidence_is_required(self):
        for name in REQUIRED_BETA_GATES:
            value=self.ready();value['gates'][name]['status']='pending'
            self.assertIn(name,beta_blockers(value))
    def test_deleting_or_disabling_required_gate_cannot_enable_beta(self):
        value=self.ready();del value['gates']['publicMainnetRoundTrip']
        self.assertIn('publicMainnetRoundTrip',beta_blockers(value))
        value=self.ready();value['gates']['publicMainnetRoundTrip']['requiredForBeta']=False
        self.assertIn('publicMainnetRoundTrip',beta_blockers(value))
    def test_documented_optional_work_is_not_claimed_passed(self):
        value=self.ready();value['gates']['independentSecurityReview']={
            'status':'pending','requiredForBeta':False,'requiredBeforeStable':True,'evidence':'External review not performed.'}
        self.assertEqual([],beta_blockers(value))
        self.assertEqual('pending',value['gates']['independentSecurityReview']['status'])
    def test_malformed_approval_or_empty_evidence_does_not_pass(self):
        value=self.ready();value['gates']['automatedUnitTests']['requiredForBeta']='false'
        self.assertIn('automatedUnitTests',beta_blockers(value))
        value=self.ready();value['gates']['signedReleaseVerification']['evidence']=' '
        self.assertIn('signedReleaseVerification',beta_blockers(value))
    def test_unrecognized_pending_gate_still_blocks(self):
        value=self.ready();value['gates']['newRequirement']={'status':'pending','evidence':'Needed'}
        self.assertIn('newRequirement',beta_blockers(value))
    def test_stable_channel_is_not_approved_by_beta_policy(self):
        value=self.ready();value['releaseChannel']='stable'
        self.assertIn('releaseChannel',beta_blockers(value))
    def test_optional_work_cannot_hide_failure_or_disappear(self):
        value=self.ready();value['gates']['independentSecurityReview']['status']='failed'
        self.assertIn('independentSecurityReview',beta_blockers(value))
        value=self.ready();del value['gates']['publicTestnetTokenRoundTrip']
        self.assertIn('publicTestnetTokenRoundTrip',beta_blockers(value))
