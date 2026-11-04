import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch
import mirror_github_packages as mirror

class MirrorSafetyTest(unittest.TestCase):
    def test_invalid_version_cannot_download(self):
        with patch.object(mirror, 'download') as download:
            with self.assertRaises(ValueError): mirror.prepare('../2.0.0', Path('/tmp'))
            download.assert_not_called()

    def test_existing_different_or_incomplete_package_refuses_overwrite(self):
        with tempfile.TemporaryDirectory() as temp:
            root=Path(temp)
            folder=root/mirror.GROUP/'nullguard-core'/'2.0.0'
            folder.mkdir(parents=True)
            (folder/'nullguard-core-2.0.0.pom').write_bytes(b'expected')
            inventory=[{'artifact':'nullguard-core','files':['nullguard-core-2.0.0.pom']}]
            with patch.object(mirror,'registry_read',return_value=b'different'):
                with self.assertRaisesRegex(ValueError,'refusing overwrite'):
                    mirror.existing_components('2.0.0',root,inventory)
            with patch.object(mirror,'registry_read',return_value=b'expected'):
                self.assertEqual({'nullguard-core'},mirror.existing_components('2.0.0',root,inventory))
            with patch.object(mirror,'registry_read',return_value=None):
                self.assertEqual(set(),mirror.existing_components('2.0.0',root,inventory))

    def test_deployment_is_nonrecursive_and_preserves_classifiers(self):
        with tempfile.TemporaryDirectory() as temp:
            root=Path(temp)
            folder=root/mirror.GROUP/'nullguard-core'/'2.0.0'
            folder.mkdir(parents=True)
            names=['nullguard-core-2.0.0.pom','nullguard-core-2.0.0.jar','nullguard-core-2.0.0-sources.jar','nullguard-core-2.0.0-javadoc.jar','nullguard-core-2.0.0.module']
            for name in names: (folder/name).write_bytes(b'original')
            inventory=[{'artifact':'nullguard-core','files':names}]
            with patch.dict('os.environ',{'GITHUB_PACKAGES_USERNAME':'user','GITHUB_PACKAGES_TOKEN':'secret'}), patch.object(mirror,'existing_components',return_value=set()), patch.object(mirror,'registry_read',return_value=b'original'), patch.object(mirror,'registry_upload') as upload, patch.object(mirror.subprocess,'run') as run:
                mirror.publish('2.0.0',root,inventory)
                args=run.call_args.args[0]
                self.assertIn('--non-recursive',args)
                self.assertIn('-Dclassifiers=sources,javadoc',args)
                self.assertNotIn('secret',str(args))
                upload.assert_called_once()
                self.assertTrue(upload.call_args.args[0].endswith('.module'))

    def test_missing_credentials_cannot_publish(self):
        with patch.dict('os.environ',{},clear=True),patch.object(mirror,'registry_upload') as upload, patch.object(mirror.subprocess,'run') as run:
            with self.assertRaises(ValueError): mirror.publish('2.0.0',Path('/tmp'),[])
            run.assert_not_called()

if __name__=='__main__': unittest.main()
