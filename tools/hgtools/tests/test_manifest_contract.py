import unittest

from tools.hgtools import manifest_contract as m

NS = 'xmlns:android="http://schemas.android.com/apk/res/android"'

RECENTS_ACTIVITY = (
    '<activity android:name="com.android.quickstep.RecentsActivity" />'
)
QUICKSTEP_SERVICE = (
    '<service android:name="com.android.quickstep.TouchInteractionService" '
    'android:permission="android.permission.STATUS_BAR_SERVICE">'
    '<intent-filter><action android:name="android.intent.action.QUICKSTEP_SERVICE" />'
    '</intent-filter></service>'
)


NO_BACKUP = 'android:allowBackup="false"'


def _manifest(application_inner: str, package: str = "com.hypergestures",
              application_attrs: str = NO_BACKUP) -> str:
    return (
        f'<manifest {NS} package="{package}">'
        f"<application {application_attrs}>{application_inner}</application>"
        "</manifest>"
    )


def _component(tag: str, name: str, action: str = "") -> str:
    inner = f'<intent-filter><action android:name="{action}" /></intent-filter>' if action else ""
    return f'<{tag} android:name="{name}" android:exported="true">{inner}</{tag}>'


# (tag, component name, action) for the home-launcher-only surface the app must not declare.
HOME_ONLY = [
    ("activity", "com.android.launcher3.QuickstepAddItemActivity",
     "android.content.pm.action.CONFIRM_PIN_SHORTCUT"),
    ("activity", "com.android.launcher3.QuickstepAddItemActivity",
     "android.content.pm.action.CONFIRM_PIN_APPWIDGET"),
    ("activity", "com.android.launcher3.QuickstepWidgetPickerActivity", "android.intent.action.PICK"),
    ("service", "com.android.launcher3.taskbar.CueBarInsightRendererService",
     "android.service.personalcontext.renderer.InsightRendererService"),
    ("service", "com.android.launcher3.notification.NotificationListener",
     "android.service.notification.NotificationListenerService"),
    ("service", "androidx.appfunctions.service.PlatformAppFunctionService",
     "android.app.appfunctions.AppFunctionService"),
    ("service", "androidx.appfunctions.service.ExtensionAppFunctionService",
     "android.app.appfunctions.AppFunctionService"),
    ("receiver", "com.android.launcher3.SessionCommitReceiver",
     "android.content.pm.action.SESSION_COMMITTED"),
    ("receiver", "com.android.launcher3.AppWidgetsRestoredReceiver",
     "android.appwidget.action.APPWIDGET_HOST_RESTORED"),
]

# `aapt2 dump xmltree --file AndroidManifest.xml <apk>` output, trimmed from the real release APK.
XMLTREE = """N: android=http://schemas.android.com/apk/res/android (line=14)
  E: manifest (line=14)
    A: http://schemas.android.com/apk/res/android:versionCode(0x0101021b)=2
    A: package="com.hypergestures" (Raw: "com.hypergestures")
      E: uses-sdk (line=19)
        A: http://schemas.android.com/apk/res/android:minSdkVersion(0x0101020c)=37
      E: application (line=93)
        A: http://schemas.android.com/apk/res/android:label(0x01010001)="HyperGestures" (Raw: "HyperGestures")
        A: http://schemas.android.com/apk/res/android:allowBackup(0x01010280)=false
        A: http://schemas.android.com/apk/res/android:largeHeap(0x0101035a)=@0x7f050003
          E: service (line=106)
            A: http://schemas.android.com/apk/res/android:name(0x01010003)="com.android.quickstep.TouchInteractionService" (Raw: "com.android.quickstep.TouchInteractionService")
            A: http://schemas.android.com/apk/res/android:permission(0x01010006)="android.permission.STATUS_BAR_SERVICE" (Raw: "android.permission.STATUS_BAR_SERVICE")
            A: http://schemas.android.com/apk/res/android:exported(0x01010010)=true
              E: intent-filter (line=111)
                  E: action (line=112)
                    A: http://schemas.android.com/apk/res/android:name(0x01010003)="android.intent.action.QUICKSTEP_SERVICE" (Raw: "android.intent.action.QUICKSTEP_SERVICE")
          E: activity (line=124)
            A: http://schemas.android.com/apk/res/android:theme(0x01010000)=@0x7f120184
            A: http://schemas.android.com/apk/res/android:name(0x01010003)="com.android.quickstep.RecentsActivity" (Raw: "com.android.quickstep.RecentsActivity")
            A: http://schemas.android.com/apk/res/android:taskAffinity(0x01010012)=""
"""

LISTENER_XMLTREE = XMLTREE + """          E: service (line=228)
            A: http://schemas.android.com/apk/res/android:name(0x01010003)="com.android.launcher3.notification.NotificationListener" (Raw: "com.android.launcher3.notification.NotificationListener")
              E: intent-filter (line=233)
                  E: action (line=234)
                    A: http://schemas.android.com/apk/res/android:name(0x01010003)="android.service.notification.NotificationListenerService" (Raw: "android.service.notification.NotificationListenerService")
"""


MINIMAL = _manifest(RECENTS_ACTIVITY + QUICKSTEP_SERVICE)


class ManifestContractTest(unittest.TestCase):
    def test_minimal_manifest_is_clean(self):
        self.assertEqual(m.check(MINIMAL), [])

    def test_missing_service_is_reported(self):
        problems = m.check(_manifest(RECENTS_ACTIVITY))
        self.assertTrue(any("QUICKSTEP_SERVICE" in p for p in problems), problems)

    def test_home_category_is_reported(self):
        home_activity = (
            '<activity android:name=".Home">'
            '<intent-filter><action android:name="android.intent.action.MAIN" />'
            '<category android:name="android.intent.category.HOME" /></intent-filter>'
            "</activity>"
        )
        problems = m.check(_manifest(RECENTS_ACTIVITY + QUICKSTEP_SERVICE + home_activity))
        self.assertTrue(any("HOME" in p for p in problems), problems)

    def test_service_missing_permission_is_reported(self):
        bad_service = (
            '<service android:name="com.android.quickstep.TouchInteractionService">'
            '<intent-filter><action android:name="android.intent.action.QUICKSTEP_SERVICE" />'
            "</intent-filter></service>"
        )
        problems = m.check(_manifest(RECENTS_ACTIVITY + bad_service))
        self.assertTrue(any("STATUS_BAR_SERVICE" in p for p in problems), problems)

    def test_secondary_home_category_is_reported(self):
        secondary = (
            '<activity android:name="com.android.launcher3.secondarydisplay.SecondaryDisplayLauncher">'
            '<intent-filter><action android:name="android.intent.action.MAIN" />'
            '<category android:name="android.intent.category.SECONDARY_HOME" /></intent-filter>'
            "</activity>"
        )
        problems = m.check(_manifest(RECENTS_ACTIVITY + QUICKSTEP_SERVICE + secondary))
        self.assertTrue(any("SECONDARY_HOME" in p for p in problems), problems)

    def test_application_preferences_action_is_reported(self):
        settings = (
            '<activity android:name="com.android.launcher3.settings.SettingsActivity">'
            '<intent-filter><action android:name="android.intent.action.APPLICATION_PREFERENCES" />'
            "</intent-filter></activity>"
        )
        problems = m.check(_manifest(RECENTS_ACTIVITY + QUICKSTEP_SERVICE + settings))
        self.assertTrue(any("APPLICATION_PREFERENCES" in p for p in problems), problems)

    def test_search_indexables_provider_is_reported(self):
        provider = (
            '<provider android:name="com.android.quickstep.LauncherSearchIndexablesProvider" '
            'android:authorities="com.hypergestures" />'
        )
        problems = m.check(_manifest(RECENTS_ACTIVITY + QUICKSTEP_SERVICE + provider))
        self.assertTrue(any("LauncherSearchIndexablesProvider" in p for p in problems), problems)

    def test_backup_agent_is_reported(self):
        xml = (
            f"<manifest {NS} package=\"com.hypergestures\">"
            f'<application android:backupAgent="com.android.launcher3.LauncherBackupAgent">'
            f"{RECENTS_ACTIVITY}{QUICKSTEP_SERVICE}</application></manifest>"
        )
        problems = m.check(xml)
        self.assertTrue(any("backupAgent" in p for p in problems), problems)

    def test_backup_must_be_explicitly_off(self):
        for attrs in ("", 'android:allowBackup="true"'):
            with self.subTest(attrs=attrs):
                problems = m.check(_manifest(RECENTS_ACTIVITY + QUICKSTEP_SERVICE, application_attrs=attrs))
                self.assertTrue(any("allowBackup" in p for p in problems), problems)

    def test_full_backup_content_is_reported(self):
        attrs = NO_BACKUP + ' android:fullBackupContent="@xml/backupscheme"'
        problems = m.check(_manifest(RECENTS_ACTIVITY + QUICKSTEP_SERVICE, application_attrs=attrs))
        self.assertTrue(any("fullBackupContent" in p for p in problems), problems)

    def test_home_launcher_only_components_are_reported(self):
        for tag, name, action in HOME_ONLY:
            with self.subTest(name=name, action=action):
                # by the action they handle, whatever the component is called ...
                problems = m.check(_manifest(RECENTS_ACTIVITY + QUICKSTEP_SERVICE
                                             + _component(tag, "com.example.Renamed", action)))
                self.assertTrue(any(action in p for p in problems), problems)
                # ... and by name, even without an intent filter
                problems = m.check(_manifest(RECENTS_ACTIVITY + QUICKSTEP_SERVICE + _component(tag, name)))
                self.assertTrue(any(name in p for p in problems), problems)

    def test_packaged_manifest_xmltree_is_parsed(self):
        root = m.parse_xmltree(XMLTREE)
        self.assertEqual(root.tag, "manifest")
        self.assertEqual(root.get("package"), "com.hypergestures")
        app = root.find("application")
        self.assertEqual(app.get("{http://schemas.android.com/apk/res/android}allowBackup"), "false")
        self.assertEqual(app.get("{http://schemas.android.com/apk/res/android}label"), "HyperGestures")
        service = app.find("service")
        self.assertEqual(service.find("intent-filter/action").get(
            "{http://schemas.android.com/apk/res/android}name"), "android.intent.action.QUICKSTEP_SERVICE")
        self.assertEqual(app.find("activity").get("{http://schemas.android.com/apk/res/android}taskAffinity"), "")
        self.assertEqual(m.check_root(root), [])

    def test_packaged_manifest_problems_are_reported(self):
        problems = m.check_root(m.parse_xmltree(LISTENER_XMLTREE))
        self.assertTrue(any("NotificationListener" in p for p in problems), problems)

    def test_customization_provider_is_reported(self):
        name = "com.android.launcher3.graphics.LauncherCustomizationProvider"
        problems = m.check(_manifest(RECENTS_ACTIVITY + QUICKSTEP_SERVICE
                                     + _component("provider", name)))
        self.assertTrue(any(name in p for p in problems), problems)


PATHS_NS = 'xmlns:android="http://schemas.android.com/apk/res/android"'


def _paths(inner: str) -> str:
    return f"<paths {PATHS_NS}>{inner}</paths>"


class FileProviderPathsTest(unittest.TestCase):
    def test_one_subdirectory_is_clean(self):
        xml = _paths('<!-- why --><cache-path name="shared_images" path="Overview/" />')
        self.assertEqual(m.check_file_provider_paths(xml), [])

    def test_root_path_is_reported(self):
        problems = m.check_file_provider_paths(_paths('<root-path name="apps" path="Overview/" />'))
        self.assertTrue(any("root-path" in p for p in problems), problems)

    def test_whole_directories_are_reported(self):
        for path in ("/", "", ".", "./"):
            with self.subTest(path=path):
                xml = _paths(f'<files-path name="f" path="{path}" />')
                self.assertTrue(m.check_file_provider_paths(xml))
        self.assertTrue(m.check_file_provider_paths(_paths('<cache-path name="c" />')))

    def test_vendored_recents_paths_are_clean(self):
        paths = m.ROOT / "aosp/launcher3/quickstep/res/xml/overview_file_provider_paths.xml"
        self.assertEqual(m.check_file_provider_paths(paths.read_text(encoding="utf-8")), [])


if __name__ == "__main__":
    unittest.main()
